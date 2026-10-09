package com.jinn.inputmethod

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import okhttp3.Call

/**
 * 屏幕翻译服务：显式触发 → 读当前应用窗口的 UI 树 → 面板勾选 → 复用 [TranslationClient] 翻译
 * → 复制 / 整节点替换。
 *
 * ## 零事件订阅（本类最要紧的约束）
 * `screen_translate_config.xml` 里**故意不写** `accessibilityEventTypes` ⇒ 系统不会因任何界面变化
 * 唤醒本进程，[onAccessibilityEvent] 永不回调。它同时消灭了四类复杂度：防抖、自写抑制、选区去重、
 * 窗口事件作废快照 —— 因为没有「自动触发」就没有回声事件与中间态。
 * ⚠ 因此**禁止**在 [onAccessibilityEvent] 里补任何取文本 / 发请求逻辑（守卫钉 4）。
 *
 * ## 一次触发的完整路径（顺序即语义）
 * `requestCapture` → 判应用内开关 → 选窗口 → 遍历采集 → 渲染面板 → 用户勾选 → 点翻译 →
 * 组 provider（null ⇒ 错误态，零请求）→ 合并 + 按字节上限截断（null ⇒ 零请求）→
 * 代际 ++ 且取消在途 → `TranslationClient.translate` → 回调切主线程做代际匹配 → 结果态。
 *
 * ## 线程与持有
 * 一切 UI / 节点动作都在主线程；`TranslationClient` 的回调线程**不确定**（构造期失败在调用线程、
 * 异步在 OkHttp IO 线程）⇒ 一律 `ui.post`。全仓唯一跨异步持有的框架对象是「被勾选那一段的节点」，
 * 用户点「替换原文」时先 `refreshAlive()` 再比对原文（见 [onReplace]）。
 */
internal class ScreenTranslateService : AccessibilityService() {

    companion object {
        /** 已连接的服务实例；磁贴与设置页据此判活。onUnbind / onDestroy 必须清空。 */
        @Volatile
        var running: ScreenTranslateService? = null
            private set

        /**
         * 磁贴 / 中转页入口。返回 false = 服务未连接（调用方据此提示去系统「无障碍」开启）。
         *
         * 允许任意线程调用：主线程直接干活，其它线程 post 回主线程（`running` 用 `===` 复验，
         * 避免服务在排队期间被解绑后对着死实例采集）。
         */
        fun requestCapture(): Boolean {
            val s = running ?: return false
            if (Looper.myLooper() == Looper.getMainLooper()) {
                s.captureAndShow()
            } else {
                s.ui.post { if (running === s) s.captureAndShow() }
            }
            return true
        }

        /** 取不到活动应用窗口时的哨兵值（与任何段的 `windowId` 都不相等 ⇒ 替换闸门自动拒绝） */
        private const val NO_WINDOW = -1

        // 日志与剪贴板标签
        private const val TAG = Diagnostics.TAG
        private const val CLIP_LABEL = "jinn_screen_translate"

        // 提示文案（界面文字一律代码下发，不进 strings.xml —— 唯一例外是系统「无障碍」页要展示的
        // 服务说明，那条只能走资源引用）
        private const val TEXT_APP_OFF = "屏幕翻译已在应用内关闭"
        private const val TEXT_COPIED = "已复制译文"
        private const val TEXT_NOTHING_TO_TRANSLATE = "所选内容没有可翻译的文字"
        private const val TEXT_REPLACE_STALE = "原文已变化，未替换"
        private const val TEXT_REPLACE_REJECTED = "该控件拒绝了写入，可改用「复制」"
        private const val TEXT_OPEN_SETTINGS_FAIL = "请手动打开本应用设置页完成翻译配置"
    }

    private val ui = Handler(Looper.getMainLooper())
    // 对齐仓内形态（ClipboardController.kt:28）：不用 getSystemService(Class) 重载
    private val wm: WindowManager get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val clip: ClipboardManager get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var panel: ScreenTranslatePanel? = null
    private var capture: ScreenCapture? = null
    private var picked: Set<Int> = emptySet()
    private var pickedText: String = ""
    private var truncated = false
    private var result: String? = null
    private var generation = 0
    private var inFlight: Call? = null

    // ── 生命周期 ────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diagnostics.init(this)
        running = this
        ScreenTranslateSettings.notifyTileStateChanged(this)
        Diagnostics.i(TAG, "屏幕翻译服务已连接")
    }

    override fun onUnbind(intent: Intent): Boolean {
        running = null
        dispose()
        ScreenTranslateSettings.notifyTileStateChanged(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = null
        dispose()
        super.onDestroy()
    }

    override fun onInterrupt() = hidePanel()

    /** 配置变更（旋转 / 分屏）：面板按新的屏高与输入法顶边重算布局，在途请求不取消。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.relayout(screenHeight(), imeTop())
    }

    /** 零事件订阅 ⇒ 本方法永不回调；留空实现只为满足抽象类。**禁止**在此取文本或发请求。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    // ── 采集 ────────────────────────────────────────────

    /** 磁贴 / 中转页入口：应用内开关关闭时拒绝（服务照常连接，只是不干活）。 */
    private fun captureAndShow() {
        if (!Prefs(this).screenTranslateEnabled) {
            toast(TEXT_APP_OFF)
            return
        }
        val window = pickWindow()
        if (window == null) {
            Diagnostics.w(TAG, "采集: 没有可读的应用窗口 ${windowSummary()}")
            showPanel(ScreenCapture.EMPTY)
            return
        }
        val root = runCatching { window.root }.getOrNull()
        val pkg = root?.packageName?.toString()
        val collector = ScreenTextCollector(bandPx = bandPx())
        if (root != null) {
            collector.traverse(A11yTextNode(root))
        }
        val snap = collector.toCapture(pkg)
        // 只记段数 / 字数 / 来源包名，绝不记原文（与 AsrClient / Translation 同口径）
        Diagnostics.i(
            TAG,
            "采集: 来源=${pkg ?: "-"} 段数=${snap.segments.size} 字数=${snap.totalChars} 截断=${snap.truncatedByLimit}",
        )
        showPanel(snap)
    }

    /**
     * 采集窗口选取：
     *  ① 只认 `TYPE_APPLICATION` 且包名不是本应用 —— 快捷设置遮罩 / 状态栏 / 输入法窗口 / 自家面板
     *     全被排除（不排除就会把「WLAN / 蓝牙 / 亮度」或自家译文当正文翻出去）；
     *  ② `windows` 按层级从高到低返回 ⇒ 取第一个命中者（视觉最上层那个应用窗口）；
     *  ③ `windows` 取不到（个别 ROM 返回空）时回落 `rootInActiveWindow`，仍过同一套包名过滤。
     */
    private fun pickWindow(): AccessibilityWindowInfo? {
        runCatching {
            for (w in windows) {
                val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
                if (ScreenTranslateLogic.isCapturableWindow(w.type, pkg, packageName)) return w
            }
        }.onFailure { Diagnostics.w(TAG, "采集: windows 读取失败 ${it.javaClass.simpleName}") }
        val active = rootInActiveWindow ?: return null
        if (active.packageName?.toString() == packageName) return null
        return runCatching { windows.firstOrNull { it.id == active.windowId } }.getOrNull()
    }

    /**
     * 采集失败时的现场摘要（**只记窗口结构，不记任何文本**）：
     * `flags` 少了 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 时 `windows` 必为空 —— 这是最容易踩的配置坑，
     * 一句话就能分辨「配置没生效」还是「当时确实没有可采窗口」。
     */
    private fun windowSummary(): String = runCatching {
        val flags = Integer.toHexString(serviceInfo?.flags ?: 0)
        val list = windows.joinToString(",") { "${it.type}/${it.id}/${it.root?.packageName}" }
        "flags=0x$flags windows=[$list]"
    }.getOrElse { "窗口列表读取失败: ${it.javaClass.simpleName}" }

    private fun bandPx(): Int = (24 * resources.displayMetrics.density).toInt()

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    /** 输入法窗口顶边（面板高度上限用）；取不到返回屏高。 */
    private fun imeTop(): Int {
        runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let {
                val r = Rect()
                it.getBoundsInScreen(r)
                if (r.top > 0) return r.top
            }
        }
        return screenHeight()
    }

    /** 让路提示（方案 §1.2）：当前窗口有可编辑焦点节点且选区非空 ⇒ 键盘那颗翻译键更顺手。 */
    private fun hasEditableSelection(): Boolean = runCatching {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        node.isEditable &&
            node.getTextSelectionStart() >= 0 &&
            node.getTextSelectionEnd() > node.getTextSelectionStart()
    }.getOrDefault(false)

    // ── 面板 ────────────────────────────────────────────

    private fun showPanel(snap: ScreenCapture) {
        capture = snap
        picked = emptySet()
        pickedText = ""
        truncated = false
        result = null
        val p = panel ?: ScreenTranslatePanel(this, listener).also { panel = it }
        if (snap.segments.isEmpty()) {
            p.renderEmpty()
        } else {
            p.renderPicking(snap, picked, imeAroundHint = hasEditableSelection())
        }
        p.attach(wm, screenHeight(), imeTop())
    }

    private fun hidePanel() {
        generation++
        inFlight?.cancel()
        inFlight = null
        panel?.detach(wm)
        capture = null
        picked = emptySet()
        pickedText = ""
        truncated = false
        result = null
    }

    private fun dispose() {
        hidePanel()
        panel?.destroy()
        panel = null
    }

    // ── 面板回调（唯一实现处） ───────────────────────────

    private val listener = object : ScreenTranslatePanel.Listener {

        /** 勾选变化只更新状态：面板自己已经刷了计数与按钮可用性，整体重建复选行会打断点击。 */
        override fun onPickChanged(selected: Set<Int>) {
            picked = selected
        }

        override fun onTranslate() {
            val snap = capture ?: return
            val prefs = Prefs(this@ScreenTranslateService)
            val id = TranslationProviderId.of(prefs.translateProvider)

            // INV-2 第一道：未勾选 / 勾选内容没有可见字符 ⇒ 零请求
            val source = ScreenTranslateLogic.composeSource(
                picked.sorted().mapNotNull { snap.segments.getOrNull(it)?.text },
                prefs.translateMaxBytesOf(id),
            )
            if (source == null) {
                panel?.showHint(TEXT_NOTHING_TO_TRANSLATE)
                return
            }

            val provider = prefs.translationProvider(id)
            if (provider == null) {
                // 分因：端点不是 https 与「凭据没填」用户要做的事完全相反
                val err = if (prefs.translateNotReadyReason() == Prefs.TranslateNotReady.ENDPOINT) {
                    TranslationError.INSECURE
                } else {
                    TranslationError.NOT_CONFIGURED
                }
                panel?.renderError(err.message, showSettings = true)
                return
            }

            pickedText = source.first
            truncated = source.second
            // 「原文已经是目标语言」⇒ 零请求（L-1131）：日 / 韩没有对称对调，再发一次只会原样返回原文，
            // 用户看到「翻译成功」而内容一字未变，同时真计费。面板此时停在勾选态，只换提示行。
            val target = when (val decision = ScreenTranslateLogic.decideTarget(pickedText, id, prefs.translateTarget)) {
                is TranslateTarget.To -> decision.language

                is TranslateTarget.AlreadyTarget -> {
                    Diagnostics.i(TAG, "屏幕翻译: 原文已是目标语言（${decision.language.name}），未发请求")
                    panel?.showHint(alreadyTargetMessage(decision.language))
                    return
                }
            }
            val gen = ++generation
            inFlight?.cancel()
            panel?.renderLoading(truncated)
            val traceId = Diagnostics.traceId("ST")
            Diagnostics.i(
                TAG,
                "[$traceId] 屏幕翻译: ${provider.javaClass.simpleName} → ${target.name} " +
                    "段=${picked.size} len=${pickedText.length} 截断=$truncated",
            )
            inFlight = TranslationClient.translate(provider, pickedText, target, traceId) { outcome ->
                ui.post {
                    if (gen != generation) return@post   // 代际闸门：气泡已收 / 新请求已发 ⇒ 丢弃旧结果
                    when (outcome) {
                        is TranslationOutcome.Ok -> {
                            result = outcome.text
                            panel?.renderResult(pickedText, outcome.text, canReplace = canReplace())
                        }
                        is TranslationOutcome.Fail -> panel?.renderError(
                            outcome.error.message,
                            showSettings = outcome.error == TranslationError.NOT_CONFIGURED ||
                                outcome.error == TranslationError.INSECURE,
                        )
                    }
                }
            }
        }

        override fun onCopyAll() {
            val text = result ?: return
            runCatching { clip.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text)) }
                .onFailure { Diagnostics.w(TAG, "复制译文失败: ${it.javaClass.simpleName}") }
            toast(TEXT_COPIED)
        }

        override fun onReplace() {
            val snap = capture ?: return
            val translated = result ?: return
            val index = picked.singleOrNull() ?: return
            val seg = snap.segments.getOrNull(index) ?: return
            // 五闸缺一不写：单段 + 整节点 + 可编辑 + 窗口未切 + 原文未变（后两闸此刻才判）
            if (!ScreenTranslateLogic.replaceable(picked.size, seg.editable, seg.wholeNode, truncated)) {
                return failReplace()
            }
            if (seg.windowId != activeAppWindowId()) return failReplace()
            if (!seg.node.refreshAlive()) return failReplace()
            if (seg.node.text?.trim() != seg.text) return failReplace()
            if (seg.node.performSetText(translated)) {
                Diagnostics.i(TAG, "替换成功: len=${translated.length}")
                hidePanel()
            } else {
                panel?.renderResult(pickedText, translated, canReplace = false)
                toast(TEXT_REPLACE_REJECTED)
            }
        }

        /**
         * 重新读取当前屏幕（用户可能刚在宿主里滚动 / 翻页 / 换了界面）。
         *
         * 与磁贴触发走同一条 [captureAndShow]，所以「应用内开关 + 窗口过滤 + 四重上限」一个不少；
         * 面板已存在时只是原地重渲染（`attach` 内部按 `added` 走更新而不是叠加窗口）。
         */
        override fun onRecapture() {
            Diagnostics.i(TAG, "重新读取当前屏幕")
            captureAndShow()
        }

        override fun onOpenSettings() {
            hidePanel()
            runCatching {
                startActivity(
                    Intent(this@ScreenTranslateService, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { toast(TEXT_OPEN_SETTINGS_FAIL) }
        }

        override fun onClose() = hidePanel()
    }

    /** 可替换判据（五闸）：静态三闸 + 窗口 + 原文（点按钮那一刻的实时值）。 */
    private fun canReplace(): Boolean {
        val snap = capture ?: return false
        val index = picked.singleOrNull() ?: return false
        val seg = snap.segments.getOrNull(index) ?: return false
        return ScreenTranslateLogic.replaceable(picked.size, seg.editable, seg.wholeNode, truncated) &&
            seg.windowId == activeAppWindowId() &&
            seg.node.refreshAlive() &&
            seg.node.text?.trim() == seg.text
    }

    /** 当前活动应用窗口 id（替换前的窗口校验）；取不到返回哨兵，闸门自然拒绝。 */
    private fun activeAppWindowId(): Int = runCatching { pickWindow()?.id }.getOrNull() ?: NO_WINDOW

    private fun failReplace() {
        Diagnostics.w(TAG, "替换被拒: 选区信息已失效")
        toast(TEXT_REPLACE_STALE)
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
            .onFailure { Diagnostics.w(TAG, "toast 失败: ${it.javaClass.simpleName}") }
    }
}
