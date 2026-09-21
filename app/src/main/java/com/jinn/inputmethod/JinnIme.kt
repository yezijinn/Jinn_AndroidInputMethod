package com.jinn.inputmethod

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Jinn 安卓输入法。
 *
 * 双模式：
 *  - 语音模式：麦克风 + 最小编辑键（长按说话 / 短按连续录音，实时推给服务端识别）
 *  - 键盘模式：26 键拼音键盘（全拼 / 自然码双拼），候选上屏
 *
 * 两种模式通过键盘上的「键盘 / 语音」键互相切换，互不干扰。
 */
class JinnIme : InputMethodService() {

    private enum class Mode { NONE, HOLD, TOGGLE }

    /** 当前键盘模式 */
    private enum class KeyboardMode { VOICE, PINYIN }

    private val ui = Handler(Looper.getMainLooper())

    /** 可选词库包是否已触发加载（三种空闲信号只生效一次） */
    private var optionalLoadTriggered = false

    /** 息屏接收器：锁屏即视为空闲，可安全做 21~34s 的后台重活 */
    private var screenOffReceiver: BroadcastReceiver? = null

    /** 键盘收起后的空闲检查（见 [maybeLoadOptionalDict]） */
    private val optionalIdleCheck = Runnable { maybeLoadOptionalDict("键盘收起后闲置") }

    private lateinit var prefs: Prefs

    /**
     * 上次创建键盘视图时生效的深浅色（null = 还没有键盘视图）。
     *
     * `onStartInputView` 用它判断主题是否被改过（设置页改了模式、或定时模式跨过切换点）：
     * 只有**决策结果变了**才重建键盘，常规弹出路径零额外开销。
     */
    private var appliedThemeDark: Boolean? = null

    /**
     * 键盘视图创建时用的**主题覆盖 Context**（见 [ThemeManager.themedContext]）。
     *
     * ⚠ 服务自身的 `getColor()` 走的是**系统**配置：强制亮白/暗黑时，服务里直接取色会拿到另一套色板
     * （状态点曾因此用错主题的 dot 色）。凡在服务层取色的地方，一律用这个 Context，未创建时退回自身。
     */
    private var keyboardThemeCtx: Context? = null
    /**
     * 语音组件。**仅在「语音输入」开关为开时才实例化**（见 [ensureVoiceReady]）。
     * 开关关闭时两者恒为 null —— 语音功能完全沉寂，不占用任何语音相关内存，
     * 因此所有使用点都必须走空安全（`?.`）。
     */
    private var asr: AsrClient? = null
    private var recorder: MicRecorder? = null

    /** 剪贴板控制器：监听系统剪贴板 → 按策略加密保存历史（普通模式核心） */
    private var clipboardController: ClipboardController? = null

    private var micButton: MicButton? = null
    private var statusDot: View? = null
    private var statusLabel: TextView? = null
    private var hintLabel: TextView? = null

    private var pinyinKeyboard: PinyinKeyboardView? = null
    /** 当前键盘模式。默认拼音——语音默认禁用，不能以语音键盘起步 */
    private var keyboardMode = KeyboardMode.PINYIN
    private var keyboardContainer: FrameLayout? = null

    /** 剪贴板页点击记录时若连接无效，暂存待粘贴文本，编辑框聚焦后自动粘贴 */
    private var pendingPasteText: String? = null
    /**
     * [pendingPasteText] 的暂存时刻。
     *
     * 暂存文本只在短时间内有效（[PENDING_PASTE_TTL_MS]）：用户点完剪贴板条目后
     * 若很久才切回输入框，或中途换了别的输入框，过期的暂存文本会被静默丢弃——
     * 否则一次 onStartInputView 就会把旧内容粘到完全无关的位置。
     */
    private var pendingPasteAt = 0L

    /**
     * [pendingPasteText] 的目标输入框标识（[pendingPasteFieldKey] 的格式）。
     *
     * 时效窗口（10s）远长于「切到另一个 App 的输入框」所需时间，只靠时间约束
     * 会把上一段剪贴板内容粘进无关的输入框甚至无关应用。暂存时锁定当时的输入框，
     * 提交前比对 [onStartInputView] 传来的 EditorInfo，不一致即丢弃。
     */
    private var pendingPasteFieldKey: String? = null

    /** 剪贴板面板打开标记：仅供诊断日志（视图侧的真实状态在 PinyinKeyboardView 内，重建后不恢复） */
    private var clipboardPanelOpen = false

    /** 音频焦点：录音期间持有，避免被来电/其他 App 抢占麦克风 */
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        ui.post {
            // 焦点被抢占（来电/其他应用播音）：停止当前录音，避免冲突与串音
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                if (mode != Mode.NONE) stopRecording(commit = false)
            }
        }
    }

    /** 网络恢复时主动重连，覆盖 WiFi↔热点切换导致 IP 变化的场景 */
    private val connectivity by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var mode = Mode.NONE

    /** 连续录音中再次按下：抬手即结束 */
    private var stopOnUp = false

    /** 长按已触发过（可能因未连接而没真正录起来），抬手就不要再当短按处理 */
    private var holdAttempted = false

    private var cancelArmed = false
    private var downY = 0f
    private var cancelSlidePx = 0f

    private val startHoldRunnable = Runnable {
        holdAttempted = true
        startRecording(Mode.HOLD)
    }

    /** 连续录音兜底，防止用户忘了关 */
    private val autoStopRunnable = Runnable {
        if (mode == Mode.TOGGLE) stopRecording(commit = true)
    }

    private val backspaceRunnable = object : Runnable {
        override fun run() {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            ui.postDelayed(this, BACKSPACE_REPEAT_MS)
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
        prefs = Prefs(this)
        instance = WeakReference(this)
        cancelSlidePx = CANCEL_SLIDE_DP * resources.displayMetrics.density
        // 按设置页配置的默认模式初始化键盘（语音 / 26键中文 / 26键英文）
        keyboardMode = when {
            // 语音关闭时一律进拼音键盘，绝不进语音面板
            !prefs.voiceInputEnabled -> KeyboardMode.PINYIN
            prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_CN ||
                prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_EN -> KeyboardMode.PINYIN
            else -> KeyboardMode.VOICE
        }
        Diagnostics.i(TAG, "onCreate: IME 服务创建（默认模式=$keyboardMode）")

        // 可选词库包的三种「空闲」触发（见 maybeLoadOptionalDict）：
        // 息屏（用户锁屏）：最可靠的空闲信号
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    maybeLoadOptionalDict("息屏")
                }
            }
        }.also {
            runCatching {
                registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF))
            }.onFailure { e ->
                // 注册失败不影响功能：还有键盘收起与兜底两条路
                Diagnostics.w(TAG, "息屏接收器注册失败: ${e.message}")
            }
        }
        // 兜底：用户一直开着键盘打字，既不息屏也不收起，也要保证最终加载
        ui.postDelayed({ maybeLoadOptionalDict("兜底超时") }, OPTIONAL_FALLBACK_DELAY_MS)
        // 双拼键位表预热：7 套表按需构建（实测单套首次使用约 1~1.7ms，7 套合计约 8ms）；而键盘视图是在
        // onCreateInputView（首次弹出）里创建的，若在那里同步建表会拖慢首次弹出。
        // 故这里用独立守护线程预热（不占用 BackgroundIo——那是剪贴板 DB 的单线程队列，
        // 不该被 CPU 预热阻塞）。失败也不影响功能：首次访问会按需同步构建。
        Thread({
            // 后台优先级：此刻词库正在加载、用户可能已在打字，预热不该与之抢 CPU
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val costMs = runCatching { Shuangpin.warmUpAll() }.getOrDefault(-1L)
            Diagnostics.i(TAG, "双拼键位表预热完成: ${SHUANGPIN_TABLES.size} 套 / ${costMs}ms")
        }, "jinn-shuangpin-warmup").apply { isDaemon = true }.start()

        // 语音关闭时不创建任何语音组件：不 new AsrClient/MicRecorder，也不发起 WebSocket 连接。
        ensureVoiceReady()

        // 预加载拼音词库（约 1MB 文本，后台线程避免主线程卡顿）
        Thread {
            val start = System.currentTimeMillis()
            // 必须兜住异常：本线程是裸 Thread，load() 内部也没有 try/catch。
            // 一旦基础包解压失败（APK 安装不完整、存储故障等），异常会直接穿透到线程外，
            // 线程静默死亡：loaded 永远为 false -> 打字没有任何候选，
            // 而且「加载完成」「内存采样」这些日志全都执行不到，排查时毫无线索。
            runCatching {
                Diagnostics.i(TAG, "onCreate: 开始预加载拼音词库")
                PinyinEngine.load(this)
            }.onSuccess {
                Diagnostics.i(TAG, "onCreate: 词库加载完成，耗时 ${System.currentTimeMillis() - start}ms")
            }.onFailure {
                Diagnostics.e(TAG, "onCreate: 词库加载失败，候选将不可用: ${it.message}", it)
            }

            // 可选词库包（分类词库页下载的那些，可达 1.14M 词条、实测解析 21~34s）**不再定时加载**：
            // 原来固定「基础包就绪后 5 秒」开始，而那正是用户开始打字的时间点，重活抢 CPU/内存带宽
            // → 冷启动后「很卡」。改为等**空闲信号**（见 maybeLoadOptionalDict）。
            Diagnostics.i(TAG, "可选词库: 已改为空闲时加载（息屏 / 键盘收起后 / 兜底超时）")
        }.start()

        // 剪贴板历史：启用时监听系统剪贴板，按策略加密保存
        if (ClipboardPrefs.of(this).enabled) {
            clipboardController = ClipboardController(this).also { it.start() }
        }

        // 监听网络恢复：WiFi↔热点切换导致 IP 变化时主动重连
        registerNetwork()

        // 监听设置页「保存配置」广播：参数改动立即生效，无需重启输入法进程。
        // Android 13+ 动态注册必须显式声明导出标志：同进程应用内广播用 NOT_EXPORTED。
        // ⚠ 只在 Android 13+ 注册：低版本没有 RECEIVER_NOT_EXPORTED 标志，
        // 动态注册默认导出且无权限保护，任意第三方 App 都能发这条 action 触发
        // refreshConfig（强制重连 + 反复启停剪贴板监听）。本 action 在应用内
        // 没有发送方（设置页走 killProcess 重启生效），低版本不注册不损失任何功能。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                registerReceiver(configReceiver, configFilter, Context.RECEIVER_NOT_EXPORTED)
            }.onFailure {
                Diagnostics.e(TAG, "onCreate: 注册配置广播失败", it)
            }
        }

    }

    /** 通过当前 InputConnection 粘贴文本（无效连接不崩溃）。返回是否成功提交。 */
    private fun pasteClipboardText(text: String): Boolean {
        val connection = currentInputConnection
        if (connection == null) {
            // 剪贴板面板在前台时 IME 可能无有效连接：暂存，并主动唤起键盘，
            // 触发 onStartInputView → flushPendingPaste 自动提交。
            // 此处未真正提交，返回 false（剪贴板面板不关闭，用户可等待或返回）。
            Diagnostics.w(TAG, "粘贴: 当前无有效 InputConnection，暂存并唤起键盘")
            pendingPasteText = text
            pendingPasteAt = System.currentTimeMillis()
            // 锁定目标输入框：暂存只在「同一个输入框重新聚焦」时提交
            pendingPasteFieldKey = pendingPasteFieldKey(getCurrentInputEditorInfo())
            // 自动唤起键盘关闭时不主动 requestShowSelf（即使调用了也会被
            // onShowInputRequested 拒绝并再次触发隐藏逻辑，这里直接不发起）。
            if (!prefs.autoShowKeyboard) {
                Diagnostics.i(TAG, "自动唤起键盘：关闭，暂存粘贴文本但不唤起 IME")
                return false
            }
            // requestShowSelf 是 API 28 才有的方法（minSdk 26）：低版本直接跳过，
            // 否则会抛 NoSuchMethodError——虽被 runCatching 兜住不崩溃，
            // 但功能静默失效且空 onFailure 违反「绝不静默吞异常」的约定。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { requestShowSelf(0) }.onFailure {
                    Diagnostics.w(TAG, "requestShowSelf 失败: ${it.message}")
                }
            } else {
                Diagnostics.i(TAG, "API < 28，不支持 requestShowSelf，跳过主动唤起")
            }
            return false
        }
        val ok = runCatching { connection.commitText(text, 1) }.getOrDefault(false)
        Diagnostics.i(TAG, "粘贴: len=${text.length} success=$ok")
        return ok
    }

    /**
     * 内嵌剪贴板面板点击记录：直接用当前 InputConnection 粘贴。
     * 与 [pasteClipboardText] 同逻辑；面板在 IME 内，连接必然有效。
     */
    private fun pasteClipboardTextInternal(text: String): Boolean = pasteClipboardText(text)

    /**
     * 设置页保存后广播：强制刷新连接与键盘配置。
     * 广播由 [SettingsActivity.saveAndTest] 发出，同进程内即时送达。
     */
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Diagnostics.i(TAG, "onReceive: 收到配置更新广播")
            refreshConfig()
        }
    }

    private val configFilter = IntentFilter().apply { addAction(ACTION_CONFIG_UPDATED) }

    // ── 方向控制 + 文字拖选状态机 ──────────────────────────

    /** 拖选模式是否激活 */
    @Volatile
    private var selectionActive = false

    /** 拖选起始锚点（进入拖选时固定，绝不变），-1 表示未设置 */
    private var selectionAnchor = -1

    /** 拖选焦点（方向键控制的对象，独立于 Android 的 selectionStart/End） */
    private var selectionFocus = -1

    /**
     * 我们最后一次请求设置的选区（`InputConnection.setSelection`）。
     *
     * 只用于在 [onUpdateSelection] 里区分「这次变化是我们自己造的」与「宿主改的」——
     * 后者必须让拖选状态失效。只放行一次：回调重复或延迟到达时，陈旧期望不能一直挡着外部变化。
     */
    private var lastSetSelection: Pair<Int, Int>? = null

    /**
     * 执行方向控制动作（通过当前 InputConnection）。
     *
     * 拖选状态机（NORMAL_CURSOR ↔ TEXT_SELECTION_ACTIVE）：
     *  - 点击中心 ●：未激活 → 固定当前光标为 Anchor 并激活；已激活 → 清除选区、
     *    退出拖选、光标停在 Focus 位置（不跳回 Anchor）
     *  - 激活时方向键/行首/行末：只移动 **Focus**，Anchor 固定不变，
     *    选区 = setSelection(min(Anchor,Focus), max(Anchor,Focus))
     *  - 复制：复制选中文字，保持选区与拖选模式
     *  - 粘贴：有选区 → 替换；无选区 → 光标处粘贴
     */
    private fun executeDirection(action: PinyinKeyboardView.DirectionAction) {
        when (action) {
            PinyinKeyboardView.DirectionAction.TOGGLE_SELECTION -> toggleSelection()
            PinyinKeyboardView.DirectionAction.COPY -> copySelection()
            PinyinKeyboardView.DirectionAction.PASTE -> pasteClipboard()
            PinyinKeyboardView.DirectionAction.UP,
            PinyinKeyboardView.DirectionAction.DOWN,
            PinyinKeyboardView.DirectionAction.LEFT,
            PinyinKeyboardView.DirectionAction.RIGHT,
            PinyinKeyboardView.DirectionAction.LINE_START,
            PinyinKeyboardView.DirectionAction.LINE_END -> moveOrExtend(action)
        }
    }

    /**
     * 清空拖选状态（IME 侧 + 同步键盘侧），供「会话切换 / 面板关闭 / 面板重开」共用。
     *
 * 三个入口共用一份实现：原先三处各自实现、容易漏改（见 onClipboardStateChanged 的注释）。
     */
    private fun clearSelectionState() {
        selectionActive = false
        selectionAnchor = -1
        selectionFocus = -1
        lastSetSelection = null
        pinyinKeyboard?.setSelectionActive(false)
    }

    /** 请求宿主设置选区，并记下这次期望值（供 [onUpdateSelection] 辨认自身动作） */
    private fun applySelection(
        connection: android.view.inputmethod.InputConnection,
        start: Int,
        end: Int,
    ) {
        lastSetSelection = start to end
        connection.setSelection(start, end)
    }

    /** 中心 ●/◉ 开关：切换拖选模式 */
    private fun toggleSelection() {
        val connection = currentInputConnection ?: return
        if (!selectionActive) {
            // 激活：固定当前光标位置为 Anchor，Focus 初始等于 Anchor
            val sel = currentSelectionRange(connection) ?: return
            selectionActive = true
            selectionAnchor = sel.start
            selectionFocus = sel.end
            Diagnostics.i(TAG, "拖选激活: Anchor=$selectionAnchor Focus=$selectionFocus")
        } else {
            // 取消：清除选区 + 退出拖选 + 光标停在当前 Focus（不跳回 Anchor）
            val end = selectionFocus.coerceAtLeast(0)
            selectionActive = false
            selectionAnchor = -1
            selectionFocus = -1
            applySelection(connection, end, end)
            Diagnostics.i(TAG, "拖选取消: 光标停在 Focus=$end")
        }
        pinyinKeyboard?.setSelectionActive(selectionActive)
    }

    /** 方向键 / 行首 / 行末：拖选激活时移动 Focus，否则普通光标移动 */
    private fun moveOrExtend(action: PinyinKeyboardView.DirectionAction) {
        val connection = currentInputConnection ?: return
        if (selectionActive) {
            extendSelection(connection, action)
        } else {
            moveCursor(connection, action)
        }
    }

    /**
     * 普通光标模式：只通过 InputConnection.setSelection 移动文本光标。
     *
     * **绝不发送 DPAD / MOVE_HOME / MOVE_END KeyEvent**——那些会触发
     * 目标 APP 的 View Focus Navigation（按钮/输入框/控件获得焦点）。
     * 正确行为：读取当前光标位置，计算新位置，setSelection 更新光标，
     * View Focus 保持不变。
     */
    private fun moveCursor(connection: android.view.inputmethod.InputConnection, action: PinyinKeyboardView.DirectionAction) {
        val range = currentSelectionRange(connection) ?: return
        // 全程按**窗口内下标**算，最后再加回 startOffset 交给宿主：selectionStart/End 是全文
        // 绝对下标，而 range.text 可能只是光标附近的窗口（见 [currentSelectionRange]）。
        val cursor = range.start - range.startOffset
        val newRel = when (action) {
            PinyinKeyboardView.DirectionAction.LEFT -> (cursor - 1).coerceAtLeast(0)
            PinyinKeyboardView.DirectionAction.RIGHT -> (cursor + 1).coerceAtMost(range.textLength)
            PinyinKeyboardView.DirectionAction.UP -> TextSelection.moveLine(range.text, cursor, up = true)
            PinyinKeyboardView.DirectionAction.DOWN -> TextSelection.moveLine(range.text, cursor, up = false)
            PinyinKeyboardView.DirectionAction.LINE_START -> TextSelection.lineStart(range.text, cursor)
            PinyinKeyboardView.DirectionAction.LINE_END -> TextSelection.lineEnd(range.text, cursor)
            else -> cursor
        }
        if (newRel != cursor) {
            val newPos = newRel + range.startOffset
            applySelection(connection, newPos, newPos)
            Diagnostics.i(TAG, "光标移动: 窗口内 $cursor → $newRel（offset=${range.startOffset}）")
        }
    }

    /**
     * 拖选扩展：**只移动 Focus**，Anchor 固定不变。
     * 选区始终 = setSelection(min(Anchor,Focus), max(Anchor,Focus))，
     * 不依赖 Android 当前的 selectionStart/End（避免归一化导致 Anchor/Focus 漂移）。
     * 行首/行末把 Focus 跳到行首/行末；上/下按行移动并保持列位置。
     */
    private fun extendSelection(connection: android.view.inputmethod.InputConnection, action: PinyinKeyboardView.DirectionAction) {
        val range = currentSelectionRange(connection) ?: return
        // Anchor/Focus 必须都已建立：任一为负（键盘侧在 IME 未记 Anchor 时置了拖选态）
        // 会让下面的归一化算出负下标，而宿主 Editable 收到越界的 setSelection 会抛异常。
        if (selectionAnchor < 0 || selectionFocus < 0) {
            Diagnostics.w(TAG, "拖选扩展: Anchor/Focus 无效（$selectionAnchor/$selectionFocus），退出拖选")
            clearSelectionState()
            return
        }
        // 与 [moveCursor] 同一套坐标：端点先换算到窗口内，算完再加回 startOffset
        val focusRel = TextSelection.toWindowOffset(selectionFocus, range.startOffset, range.textLength)
        val anchorRel = TextSelection.toWindowOffset(selectionAnchor, range.startOffset, range.textLength)
        if (focusRel == null || anchorRel == null) {
            Diagnostics.w(TAG, "拖选端点不在当前文本窗口内（offset=${range.startOffset}），退出拖选")
            clearSelectionState()
            return
        }
        val textRange = TextSelection.Range(anchorRel, focusRel, range.textLength, range.text)
        val newFocusRel = TextSelection.nextFocus(textRange, focusRel, action)
        selectionFocus = newFocusRel + range.startOffset
        // Anchor 固定，Focus 可移动：选区两端取 min/max
        val (start, end) = TextSelection.normalizedSelection(selectionAnchor, selectionFocus)
        applySelection(connection, start, end)
        Diagnostics.i(TAG, "拖选扩展: Anchor=${selectionAnchor} Focus=$selectionFocus → 选区[$start,$end]")
    }

    /**
     * 读取当前选区范围（未选中时 start == end == 光标位置）。
     *
     * **两类下标必须分清**：`selectionStart/End` 是全文**绝对**下标，而 `text` 在长文档下可能
     * 只是光标附近的一段**窗口**（`startOffset` 是它在全文里的起点）。端点落在窗口之外时无从
     * 计算 —— 返回 null 让调用方放弃本次操作。旧实现直接拿绝对下标去索引窗口文本，
     * 行移动 / 行首行末会算出无关位置，甚至把光标挪到「窗口长度」那个绝对下标上。
     */
    private fun currentSelectionRange(connection: android.view.inputmethod.InputConnection): SelectionRange? {
        val extracted = connection.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest(), 0
        ) ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        val start = extracted.selectionStart.coerceAtLeast(0)
        val end = extracted.selectionEnd.coerceAtLeast(start)
        val text = extracted.text?.toString().orEmpty()
        val startOffset = extracted.startOffset.coerceAtLeast(0)
        if (TextSelection.toWindowOffset(start, startOffset, text.length) == null ||
            TextSelection.toWindowOffset(end, startOffset, text.length) == null
        ) {
            Diagnostics.w(
                TAG,
                "光标/选区不在当前文本窗口内（offset=$startOffset len=${text.length} sel=[$start,$end]），跳过本次操作",
            )
            return null
        }
        return SelectionRange(start, end, text.length, text, startOffset)
    }

    /**
     * 搜索态的「作用于宿主」面板动作一律拒绝（**纵深防御**）。
     *
     * 主守卫在 `PinyinKeyboardView.renderFunctionPanel`：搜索态候选栏只渲染「退出搜索」，
     * 全选/复制/方向/粘贴都不出现。这里再挡一道，保证任何将来新增的调用路径
     * （无障碍、外部触发、新的面板入口）都不会让它们落到宿主输入框上 ——
     * 搜索态下 26 键只作用于搜索框，面板动作必须同口径。
     */
    private fun rejectedBySearchPanel(action: String): Boolean {
        if (pinyinKeyboard?.isSearchActive() != true) return false
        Diagnostics.w(TAG, "搜索态下忽略「$action」（避免作用于宿主输入框）")
        return true
    }

    /** 复制选中文字到系统剪贴板（保持选区与拖选模式） */
    private fun copySelection() {
        val connection = currentInputConnection ?: return
        // 与 moveCursor/extendSelection 同一套坐标：currentSelectionRange 已把
        // 「绝对下标 vs 窗口文本」的错位挡在外面（端点不在窗口内时返回 null）。
        // 旧实现只做 coerceIn 钳位——窗口化时会把绝对下标当窗口下标用，
        // 复制到与选区无关的内容（静默错误）或截出半截选区。
        val range = currentSelectionRange(connection) ?: return
        if (range.end <= range.start) {
            Diagnostics.w(TAG, "复制: 无选中文字")
            return
        }
        // 两个端点都已由 currentSelectionRange 校验过「在窗口内」，这里仍按可空处理，
        // 防御两次读取之间窗口发生变化（null 即放弃本次操作）。
        val from = TextSelection.toWindowOffset(range.start, range.startOffset, range.textLength)
        val to = TextSelection.toWindowOffset(range.end, range.startOffset, range.textLength)
        if (from == null || to == null || to <= from) {
            Diagnostics.w(TAG, "复制: 选区不在当前文本窗口内，跳过")
            return
        }
        val sel = range.text.substring(from, to)
        val clip = android.content.ClipData.newPlainText("jinn_selection", sel)
        clipboardManager.setPrimaryClip(clip)
        // 不再手动入库：setPrimaryClip 会触发 ClipboardController 的剪贴板监听，
        // 由它统一保存。此前这里也写一次库，导致同一条内容 upsert 两次，
        // 且手动传入的 sourceAppName（"本输入法"）会被监听路径按包名推断的值覆盖。
        // 统一入口后，分类逻辑只存在于 ClipboardStore.save 一处。
        Diagnostics.i(TAG, "复制: 选中 ${sel.length} 字（保持选区与拖选模式）")
    }

    /** 粘贴：有选区替换，无选区在光标处插入 */
    private fun pasteClipboard() {
        val connection = currentInputConnection ?: return
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            Diagnostics.w(TAG, "粘贴: 剪贴板为空")
            return
        }
        connection.commitText(text, 1)
        Diagnostics.i(TAG, "粘贴: len=${text.length}")
    }

    /**
     * `getExtractedText` 的结果。
     *
     * **两类下标**：`start`/`end` 是全文**绝对**下标（宿主给的就是这个），而 `text` 可能只是
     * 光标附近的一段**窗口**、`startOffset` 是它在全文里的起点 —— 拿 `text` 算行移动前必须
     * 先减 `startOffset`，算完再加回去（见 [moveCursor] / [extendSelection]）。
     * `textLength` 是**窗口长度**（窗口内的上界），不是全文长度。
     */
    private data class SelectionRange(
        val start: Int,
        val end: Int,
        val textLength: Int,
        val text: String,
        val startOffset: Int,
    )

    /** 系统剪贴板 */
    private val clipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    /** 立即生效：强制按新地址重连 WebSocket + 重新同步键盘输入方案 */
    fun refreshConfig() {
        // 地址/端口变化：旧连接指向旧服务器，强制断开重连
        Diagnostics.i(TAG, "refreshConfig: 强制重连 ${prefs.wsUrl}")
        asr?.connect(force = true)
        // 双拼/中英文方案变化：立即重新套用，键盘无需重建
        pinyinKeyboard?.configure(
            scheme = prefs.effectiveShuangpinScheme,
            english = prefs.keyboardEnglish,
        )
        // 剪贴板历史开关变化：按最新偏好启停监听
        val cpEnabled = ClipboardPrefs.of(this).enabled
        if (cpEnabled && clipboardController == null) {
            clipboardController = ClipboardController(this).also { it.start() }
        } else if (!cpEnabled) {
            clipboardController?.stop()
            clipboardController = null
        }
    }

    override fun onCreateInputView(): View {
        Diagnostics.i(TAG, "onCreateInputView: 键盘视图创建")

        // 主题：键盘视图一律用「按 Prefs 决策后的 Context」创建 —— 亮白 / 暗黑 / 定时模式下
        // uiMode 在这里被覆盖、色板随之锁定；「跟随系统」时该方法是恒等返回，
        // 系统深浅色变化由系统重建 IME 自动生效。
        val themeCtx = ThemeManager.themedContext(this, prefs)
        keyboardThemeCtx = themeCtx
        appliedThemeDark = ThemeManager.isDark(this, prefs)

        // 语音键盘
        val voice = LayoutInflater.from(themeCtx).inflate(R.layout.keyboard, null)
        micButton = voice.findViewById(R.id.mic_button)
        statusDot = voice.findViewById(R.id.status_dot)
        statusLabel = voice.findViewById(R.id.status_label)
        hintLabel = voice.findViewById(R.id.hint_label)
        micButton?.setOnTouchListener { view, event ->
            val consumed = onMicTouch(event)
            // 无障碍：抬手时补 performClick。麦克风按钮没有 OnClickListener，
            // performClick 只向无障碍服务补发一次点击事件，不影响手势判定与录音逻辑。
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            consumed
        }
        voice.findViewById<View>(R.id.status_bar).setOnClickListener { openSettings() }
        voice.findViewById<View>(R.id.key_comma).setOnClickListener { commit("，") }
        voice.findViewById<View>(R.id.key_period).setOnClickListener { commit("。") }
        voice.findViewById<View>(R.id.key_space).setOnClickListener { commit(" ") }
        voice.findViewById<View>(R.id.key_enter).setOnClickListener { performEnter() }
        // 语音键盘的「键盘」键：切到拼音键盘；长按仍切输入法
        voice.findViewById<View>(R.id.key_switch).setOnClickListener { switchToPinyinKeyboard() }
        voice.findViewById<View>(R.id.key_switch).setOnLongClickListener {
            showImePicker()
            true
        }
        bindBackspace(voice.findViewById(R.id.key_backspace))

        // 拼音键盘（同样用决策后的 Context，见上）
        val pinyin = PinyinKeyboardView(themeCtx).apply {
            listener = object : PinyinKeyboardView.Listener {
                override fun onCommitText(text: String) = commit(text)
                override fun onCommitSpace() = commit(" ")
                override fun onEnter() = performEnter()
                override fun onBackspace() {
                    Diagnostics.v(TAG, "退格删已上屏文本")
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                override fun onDeleteAll() = deleteAllText()
                override fun onVoiceRequested() = switchToVoiceKeyboard()
                override fun onOpenClipboard() {
                    Diagnostics.i(TAG, "功能面板: 打开剪贴板")
                    // 内嵌面板：替换 26 键字母区（候选栏/底部栏/InputConnection 保持）
                    clipboardPanelOpen = true
                    pinyinKeyboard?.showClipboardPanel()
                }
                override fun onPasteText(text: String): Boolean {
                    Diagnostics.i(TAG, "剪贴板面板: 请求粘贴 len=${text.length}")
                    return pasteClipboardTextInternal(text)
                }
                override fun onClipboardStateChanged(active: Boolean) {
                    Diagnostics.i(TAG, "剪贴板面板状态: active=$active")
                    if (!active) {
                        clipboardPanelOpen = false
                        // 面板打开/关闭时清除拖选状态（IME + 键盘两侧一起清，见 clearSelectionState）
                        clearSelectionState()
                    }
                }
                override fun onDirectionAction(action: PinyinKeyboardView.DirectionAction) {
                    if (rejectedBySearchPanel("方向")) return
                    Diagnostics.i(TAG, "方向按键: $action")
                    executeDirection(action)
                }
                override fun onSelectionModeChanged(active: Boolean) {
                    Diagnostics.i(TAG, "拖选模式变化: $active")
                    // 面板关闭/键盘重置时同步清除拖选状态，避免 anchor/focus 残留
                    if (!active) {
                        clearSelectionState()
                    } else {
                        pinyinKeyboard?.setSelectionActive(true)
                    }
                }
                override fun onPasteClipboard() {
                    if (rejectedBySearchPanel("粘贴")) return
                    Diagnostics.i(TAG, "功能面板: 粘贴剪贴板")
                    pasteClipboard()
                }
                override fun onSelectAll() {
                    if (rejectedBySearchPanel("全选")) return
                    Diagnostics.i(TAG, "功能面板: 全选")
                    selectAllText()
                }
                override fun onCopy() {
                    if (rejectedBySearchPanel("复制")) return
                    Diagnostics.i(TAG, "功能面板: 复制")
                    copySelection()
                }
                override fun onHideKeyboard() {
                    Diagnostics.i(TAG, "功能面板: 收起键盘")
                    // 只隐藏输入面板，服务保持运行，点击输入框再次唤醒
                    runCatching { requestHideSelf(0) }
                        .onFailure { Diagnostics.w(TAG, "收起键盘失败: ${it.message}") }
                }
            }
            configure(
                scheme = prefs.effectiveShuangpinScheme,
                // 26键英文模式：启动时强制英文；其余模式沿用键盘英文偏好
                english = if (prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_EN) {
                    true
                } else {
                    prefs.keyboardEnglish
                },
            )
            updateImeOptions(currentInputEditorInfo?.imeOptions ?: 0)
        }
        pinyinKeyboard = pinyin

        // 双模式容器：默认语音键盘，键盘模式时切到拼音键盘
        val container = FrameLayout(this)
        keyboardContainer = container
        container.addView(voice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(pinyin, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 键盘视图每次创建都重新套用默认模式（InputMethodService 可能复用实例，
        // 仅靠 onCreate 设置 keyboardMode 在复用场景下不生效）
        keyboardMode = when {
            !prefs.voiceInputEnabled -> KeyboardMode.PINYIN
            prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_CN ||
                prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_EN -> KeyboardMode.PINYIN
            else -> KeyboardMode.VOICE
        }
        applyKeyboardMode()
        // 注意：不在这里恢复剪贴板面板（见 onStartInputView 注释——恢复会诱发
        // IME 窗口反复 relayout 循环，反而导致面板抖动/空白）。
        return container
    }

    private fun applyKeyboardMode() {
        val container = keyboardContainer ?: return
        if (container.childCount < 2) return
        val voiceView = container.getChildAt(0)
        val pinyinView = container.getChildAt(1)
        when (keyboardMode) {
            KeyboardMode.VOICE -> {
                voiceView.visibility = View.VISIBLE
                pinyinView.visibility = View.GONE
            }
            KeyboardMode.PINYIN -> {
                voiceView.visibility = View.GONE
                pinyinView.visibility = View.VISIBLE
                // 调试：拼音键盘刚变为可见，等一帧布局完成后输出按键坐标供自动化定位
                ui.postDelayed({
                    if (pinyinKeyboard?.width != 0) {
                        Diagnostics.i(TAG, "拼音键盘坐标: ${pinyinKeyboard?.getFunctionKeyPositions()}")
                        Diagnostics.i(TAG, "拼音键盘候选栏: ${pinyinKeyboard?.getCandidateBarPosition()}")
                    }
                }, 150L)
            }
        }
        Diagnostics.i(TAG, "applyKeyboardMode: $keyboardMode")
    }

    private fun switchToPinyinKeyboard() {
        // 从语音切走时若还在录音，直接丢弃（防止结果落到别的输入框）
        if (mode != Mode.NONE) stopRecording(commit = false)
        keyboardMode = KeyboardMode.PINYIN
        applyKeyboardMode()
    }

    /**
     * 切到语音键盘。
     *
     * 进入语音前**先判断识别服务是否在线**，离线就直接拒绝并提示：
     * 否则用户长按空格进了语音面板才发现连不上，白等一次 WebSocket 握手，
     * 还会连带触发录音权限检查、录音器初始化等一整套语音链路负担。
     * 语音是自用功能，宁可在这里提前拦掉，也不让用户进到一个用不了的面板。
     */
    /**
     * 按需装配语音组件。**只有「语音输入」开关为开时才真正创建**。
     *
     * 幂等：已创建则直接返回。开关关闭时本方法不做任何事，
     * 于是 AsrClient / MicRecorder 始终为 null —— 语音完全沉寂、零内存占用。
     * 设置页保存会重启 IME 进程，所以开关变更后重新执行 onCreate 即自动生效。
     */
    private fun ensureVoiceReady() {
        if (!prefs.voiceInputEnabled) {
            Diagnostics.i(TAG, "语音输入已禁用：不创建语音组件、不连接服务端")
            return
        }
        if (asr != null) return
        asr = AsrClient(
            prefs = prefs,
            onState = { state, detail -> ui.post { renderLink(state, detail) } },
            onResult = { message -> ui.post { handleResult(message) } },
        )
        recorder = MicRecorder(
            onChunk = { chunk -> asr?.sendChunk(chunk) },
            onLevel = { level -> ui.post { micButton?.updateLevel(level) } },
            onError = { message -> ui.post { onRecorderError(message) } },
            onSilence = { ui.post { onSilenceDetected() } },
        )
        // 预热连接：首次弹键盘时 WebSocket 往往还没建好，
        // 不加这一步用户第一次点麦克风会因 beginTask() 返回 null 而"没反应"，得再点一次
        asr?.connect()
        Diagnostics.i(TAG, "语音输入已启用：语音组件已装配并开始预热连接")
    }

    private fun switchToVoiceKeyboard() {
        // 总开关优先：语音关闭时静默拒绝（不提示、不建实例），入口彻底置空
        if (!prefs.voiceInputEnabled) {
            Diagnostics.i(TAG, "语音输入已禁用，忽略进入语音键盘的请求")
            return
        }
        val client = asr ?: run {
            Diagnostics.w(TAG, "语音输入已启用但语音组件未装配，拒绝进入语音功能")
            return
        }
        if (client.state != LinkState.ONLINE) {
            Diagnostics.i(TAG, "语音服务未连通(${client.state})，拒绝进入语音功能")
            runCatching {
                Toast.makeText(
                    this,
                    getString(R.string.voice_offline_blocked, prefs.host, prefs.port),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { Diagnostics.w(TAG, "提示语音离线失败: ${it.message}") }
            return
        }
        // 从拼音切走时若有未上屏内容，先提交首候选
        pinyinKeyboard?.commitComposing()
        // 搜索态必须一并退出：面板在语音键盘下不可见却仍处激活态，
        // 切回拼音后 26 键输入会被「隐形路由」进搜索框（用户以为在打字）。
        pinyinKeyboard?.hideSearchPanel()
        keyboardMode = KeyboardMode.VOICE
        applyKeyboardMode()
    }

    /**
     * 输入会话结束（编辑器失焦/切换到别的输入框）：
     * 停止候选计算、清理拼音缓冲、取消录音与延时任务，降到最低功耗。
     * 不关闭 WebSocket（语音输入需要随时可用，且关闭会丢在途结果）。
     */
    /**
     * 宿主改了光标/选区时（用户点了别处、宿主程序自己改了）让拖选状态失效。
     *
     * [selectionAnchor]/[selectionFocus] 是**我们记下的绝对下标**，只在拖选会话内有意义；
     * 宿主把光标移到别处后它们指向的区间与用户意图无关 —— 再按方向键会以旧 Anchor 重新
     * `setSelection`，选区整个错位，接下来的复制 / 输入都作用在错误的位置上（原实现没有
     * 实现本回调，平台提供的这个同步点被完全漏掉）。
     *
     * 采用「退出拖选」而不是「把 Anchor 挪到新位置」：后者要求回调与我们的 setSelection
     * 严格配对，做不到时就会以错位的 Anchor 继续拖选；退出最坏只是用户再点一次 ◉。
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        val expected = lastSetSelection
        // 只放行一次：回调重复或延迟到达时，陈旧期望不能继续挡着外部变化
        lastSetSelection = null
        if (!selectionActive) return
        if (!TextSelection.isExternalSelectionChange(newSelStart, newSelEnd, expected)) return
        Diagnostics.i(TAG, "宿主改动选区[$newSelStart,$newSelEnd]，退出拖选（Anchor/Focus 已失效）")
        clearSelectionState()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Diagnostics.i(TAG, "onFinishInput: 会话结束 mode=$mode")
        Diagnostics.event("IME", "FinishInput", "mode=$mode")
        // 停掉可能仍在运行的录音/延时任务
        if (mode != Mode.NONE) stopRecording(commit = false)
        ui.removeCallbacks(backspaceRunnable)
        ui.removeCallbacks(autoStopRunnable)
        ui.removeCallbacks(startHoldRunnable)
    }

    /**
     * 系统每次请求显示 IME 窗口的闸门（InputMethodService 内部所有显示路径都经它判定：
     * 编辑框聚焦自动唤起 / [requestShowSelf] / 配置变化后的自动恢复显示）。
     *
     * 「自动唤起键盘」关闭时返回 false，从源头拒绝一切显示请求，窗口永不显示，
     * 系统端 mShowInputRequested 保持 false——不会形成「唤起 → 隐藏 → 系统重新唤起」循环。
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (!prefs.autoShowKeyboard) {
            Diagnostics.i(TAG, "自动唤起键盘：关闭，禁止主动显示 IME")
            return false
        }
        return super.onShowInputRequested(flags, configChange)
    }

    /**
     * 主题决策变了就用新色板重建键盘。
     *
     * 两个触发源：① 设置页改主题 —— 同进程直接调 [notifyThemeChanged]（键盘正显示时立即换肤）；
     * ② [onStartInputView] —— 覆盖「定时模式在键盘收起期间跨过了切换点」。
     * 键盘视图尚未创建（appliedThemeDark 为 null）时无需处理：onCreateInputView 会读到新值。
     */
    private fun applyThemeIfNeeded() {
        val wantDark = ThemeManager.isDark(this, prefs)
        if (appliedThemeDark != null && appliedThemeDark != wantDark) {
            // 视图上有用户正在进行的状态（未上屏拼音/预测、剪贴板面板、搜索面板）时不动视图：
            // 重建会把它们静默丢弃/关闭（宿主输入框毫无变化）。延后到下次弹出 ——
            // onStartInputView 会再判一次。
            if (pinyinKeyboard?.let { it.hasPendingInput || it.hasActiveOverlay } == true) {
                Diagnostics.i(TAG, "主题变更: 视图有未完成操作（输入或面板），延后到下次弹出换肤")
                return
            }
            Diagnostics.i(TAG, "主题变更: 重建键盘（${if (wantDark) "暗黑" else "亮白"}）")
            setInputView(onCreateInputView())
        }
    }

    /** 排序页/收藏编辑页改动符号数据后重建键盘视图（companion 的 [onSymbolLayoutChanged] 转发到这里） */
    fun rebuildInputViewForSymbolLayout() {
        val keyboard = pinyinKeyboard ?: return
        // 与换肤同口径地不打断未上屏输入：重建会清空拼音串/预测词，用户以为输入被吞。
        // 触发窗口极窄（需键盘可见时改符号），且数据已落盘 —— 延后到视图下次创建时自然生效。
        // ⚠ 这里**不**看面板态：编辑页场景下面板不可能同时打开，而推迟会造成「改了符号不生效」。
        if (keyboard.hasPendingInput) {
            Diagnostics.i(TAG, "符号分组顺序/收藏变更: 视图有未上屏输入，延后到下次创建视图")
            return
        }
        Diagnostics.i(TAG, "符号分组顺序/收藏变更: 重建键盘视图")
        setInputView(onCreateInputView())
    }

    /** 定时模式的到点检查（键盘可见期间跨过切换点也换肤）；非定时模式无操作 */
    private fun scheduleThemeTick() {
        ui.removeCallbacks(themeTick)
        if (prefs.themeMode != ThemeManager.MODE_SCHEDULED) return
        val minutes = ThemeManager.minutesUntilSwitch(
            prefs.themeMode, prefs.themeLightAtMinutes, prefs.themeDarkAtMinutes, ThemeManager.nowMinutes(),
        )
        if (minutes <= 0) return
        // 多等 2 秒：避免恰好落在分钟边界上、判定仍读到上一分钟
        ui.postDelayed(themeTick, minutes * 60_000L + 2_000L)
    }

    private val themeTick = Runnable {
        applyThemeIfNeeded()
        scheduleThemeTick()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 防御拦截：关闭开关时键盘可能正处于显示状态（窗口可见时才回调本方法），
        // 立即收起；此后一切显示请求都被 onShowInputRequested 拒绝，不会重新唤起。
        // 不显示输入视图，不初始化输入，键盘在本输入会话内完全不可用。
        if (!prefs.autoShowKeyboard) {
            Diagnostics.i(TAG, "自动唤起键盘：关闭，收起当前显示的 IME 窗口")
            runCatching { hideWindow() }.onFailure { }
            return
        }
        Diagnostics.i(TAG, "onStartInputView: restarting=$restarting package=${info?.packageName} fieldId=${info?.fieldId}")
        Diagnostics.event("IME", "StartInputView", "restart=$restarting pkg=${info?.packageName}")
        // 主题变更（设置页改了模式，或定时模式跨过切换点）：用新色板重建键盘
        applyThemeIfNeeded()
        scheduleThemeTick() // 定时模式：键盘可见期间也准点换肤
        // 用户回来了（开始输入）：取消待触发的可选词库加载。该任务解析耗时 21~34s，
        // 砸在打字期正是本机制要避免的「后台重活抢 CPU」，兜底 180s 仍能保证最终加载。
        ui.removeCallbacks(optionalIdleCheck)
        asr?.connect()
        renderLink(asr?.state ?: LinkState.OFFLINE, null)
        micButton?.recording = false
        micButton?.cancelArmed = false
        setHint(getString(R.string.hint_idle))
        pinyinKeyboard?.updateImeOptions(info?.imeOptions ?: 0)
        // 敏感输入框（密码框 / 声明 NO_SUGGESTIONS / imeOptions 声明不要个性化学习）
        // 不学用户词频：否则口令片段会被写进本地词频文件，之后在普通输入框里被优先推荐出来。
        pinyinKeyboard?.setSuppressLearning(
            InputFieldPrivacy.suppressLearning(info?.inputType, info?.imeOptions ?: 0)
        )
        // 每次输入框聚焦时重新同步双拼方案（全拼/双拼、中英文）：
        // 设置页改动后无需重启输入法，下次弹键盘即生效。
        // 英文态取键盘当前状态（保留用户手动切换结果，不强制覆盖）
        pinyinKeyboard?.configure(
            scheme = prefs.effectiveShuangpinScheme,
            english = pinyinKeyboard?.isEnglishMode() ?: prefs.keyboardEnglish,
        )
        // 剪贴板面板粘贴时连接无效会暂存文本，编辑框重新聚焦时自动提交
        flushPendingPaste(info)
        // 注意：不再在这里恢复剪贴板面板——恢复逻辑会触发 onPanelShown→refresh
        // （主线程 DB 查询数百毫秒）→ 诱发 IME 窗口反复 relayout（12:20 循环日志实证），
        // 反而让面板抖动/空白。INVISIBLE 方案下键盘视图实例不重建，面板状态天然保留。
    }

    /**
     * 输入框身份键：包名 + fieldId。
     *
     * 用于把暂存粘贴绑定到**发起粘贴时的那个输入框**——只比对 fieldId 不够
     * （不同 App 的 fieldId 会撞），必须带上包名。取不到 EditorInfo 时返回 null，
     * 表示「身份未知」，此时由时效窗口与 [onFinishInputView] 的会话边界兜底。
     */
    private fun pendingPasteFieldKey(info: EditorInfo?): String? = fieldKeyOf(info?.packageName, info?.fieldId ?: 0)

    /** 提交暂存的剪贴板粘贴文本（编辑框重新可用时调用；无暂存则空操作） */
    private fun flushPendingPaste(info: EditorInfo? = null) {
        // 会话边界：拖选状态必须复位。anchor/focus 是**上一个输入框**的坐标，
        // 跨字段残留会让后续 extendSelection 用旧下标去 setSelection（被系统钳位，
        // 表现为选区莫名跳动），且中心键图标与 IME 状态可能不一致。
        clearSelectionState()

        val text = pendingPasteText ?: return
        // 时效保护：暂存文本只在短时间窗口内有效，过期直接丢弃。
        // 否则用户换输入框、或隔很久才回到键盘，旧文本会被粘到完全无关的位置。
        val age = System.currentTimeMillis() - pendingPasteAt
        val target = pendingPasteFieldKey
        val current = pendingPasteFieldKey(info)
        // 身份保护：换到另一个输入框（哪怕同 App 的另一个 fieldId）就不再提交。
        // 10s 的时效窗口远比「切到微信再切回来」长，没有这层比对，剪贴板正文会被
        // 静默注入到用户当前正在输入的、完全无关的位置。
        if (target != null && current != null && target != current) {
            pendingPasteText = null
            pendingPasteFieldKey = null
            Diagnostics.w(TAG, "flushPendingPaste: 目标输入框已变更($target -> $current)，丢弃 len=${text.length}")
            return
        }
        if (age > PENDING_PASTE_TTL_MS) {
            pendingPasteText = null
            pendingPasteFieldKey = null
            Diagnostics.w(TAG, "flushPendingPaste: 暂存已过期(${age}ms)，丢弃 len=${text.length}")
            return
        }
        pendingPasteText = null
        pendingPasteFieldKey = null
        val connection = currentInputConnection
        if (connection == null) {
            // 仍无有效连接：放回暂存（保留原时刻），等待下次 onStartInputView。
            // **输入框身份必须一并放回**：原先只恢复了正文、把 fieldKey 留在 null，
            // 于是下一次进入时 target 为 null、上面的「目标已变更」判据直接短路，
            // 这 10 秒里的暂存正文就可能被提交到一个完全无关的输入框里。
            pendingPasteText = text
            pendingPasteFieldKey = target
            Diagnostics.w(TAG, "flushPendingPaste: 连接仍无效，继续暂存 len=${text.length}")
            return
        }
        Diagnostics.i(TAG, "flushPendingPaste: 提交暂存粘贴 len=${text.length}")
        connection.commitText(text, 1)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Diagnostics.i(TAG, "onFinishInputView: finishingInput=$finishingInput mode=$mode clipboardPanelOpen=$clipboardPanelOpen")
        Diagnostics.event("IME", "FinishInputView", "finish=$finishingInput clipboard=$clipboardPanelOpen")
        // 键盘收起时还在录音，直接丢弃：否则文本可能落到别的输入框里
        if (mode != Mode.NONE) stopRecording(commit = false)
        // 拼音键盘若有未上屏内容，提交首候选
        pinyinKeyboard?.commitComposing()
        // 会话边界：搜索面板必须退出——跨输入框残留会让下一次输入被路由进搜索框
        pinyinKeyboard?.hideSearchPanel()
        ui.removeCallbacks(backspaceRunnable)
        ui.removeCallbacks(themeTick) // 键盘已收起：到点检查交给下次弹出时的 scheduleThemeTick
        // 会话边界：暂存的剪贴板文本属于**上一个输入框**，新输入框聚焦时不得自动提交
        // （配合 flushPendingPaste 的 fieldId 比对，双重拦截跨字段注入）
        if (pendingPasteText != null) {
            Diagnostics.i(TAG, "onFinishInputView: 输入会话结束，丢弃暂存粘贴")
            pendingPasteText = null
            pendingPasteFieldKey = null
        }
        // 键盘收起 = 用户大概率停止输入了；再等一小段（避免只是切了个应用马上回来）后加载可选词库
        ui.removeCallbacks(optionalIdleCheck)
        ui.postDelayed(optionalIdleCheck, OPTIONAL_IDLE_DELAY_MS)
        // 不在这里关闭连接：输入法是常驻服务，WebSocket 应跨输入会话复用。
        // 松手后服务端 final 结果往往还要 1~3 秒才回来，此刻关连接会把在途
        // 结果丢掉，识别文本无法落地；连接统一在 onDestroy 里释放。
        super.onFinishInputView(finishingInput)
    }

    /** IME 窗口显示完成回调：用于确认面板打开后窗口状态 */
    override fun onWindowShown() {
        super.onWindowShown()
        Diagnostics.i(TAG, "onWindowShown: 窗口显示 clipboardPanelOpen=$clipboardPanelOpen keyboardMode=$keyboardMode")
        Diagnostics.event("IME", "WindowShown", "clipboard=$clipboardPanelOpen mode=$keyboardMode")
    }

    /** IME 窗口隐藏回调：排查「面板打开后约 0.5 秒窗口被收起」的直接证据 */
    override fun onWindowHidden() {
        super.onWindowHidden()
        Diagnostics.i(TAG, "onWindowHidden: 窗口隐藏 clipboardPanelOpen=$clipboardPanelOpen keyboardMode=$keyboardMode")
        Diagnostics.event("IME", "WindowHidden", "clipboard=$clipboardPanelOpen mode=$keyboardMode")
    }

    override fun onDestroy() {
        instance = null
        // 用户词频：把未落盘的最后几次学习刷出去（内部走 BackgroundIo，不阻塞）
        runCatching { PinyinEngine.flushUserFrequency() }
        Diagnostics.i(TAG, "onDestroy: IME 服务销毁, mode=$mode")
        ui.removeCallbacksAndMessages(null)
        unregisterNetwork()
        // 系统剪贴板监听挂在 ClipboardManager 上，不注销会随服务一起泄漏；
        // 这里不置 null：后续 refreshConfig 仍可能重新 start（stop 幂等）。
        runCatching { clipboardController?.stop() }
        runCatching { unregisterReceiver(configReceiver) }
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        abandonAudioFocus()
        // 采集线程的 stop 需要 join(300ms) + release；放在主线程阻塞会触发 ANR，
        // 抛到后台线程让它自己收尾，asr 的 socket close 也是异步发送 close 帧
        Thread {
            recorder?.stop()
            asr?.close()
            Diagnostics.i(TAG, "onDestroy: 录音与连接已释放")
        }.start()
        super.onDestroy()
    }

    /**
     * 触发可选词库包的后台加载（**单次**）。
     *
     * 不用固定延迟：实测那 1.14M 词条的解析要 **21~34s**，而「基础包就绪后 5 秒」
     * 恰好是用户开始打字的时刻 —— 后台重活与前台输入抢 CPU/内存带宽，主观感受就是
     * 「刚开机很卡」。改为等**空闲信号**，三选一（先到先得）：
     *  · 息屏：用户锁屏，最可靠的空闲信号；
     *  · 键盘收起后再闲置 [OPTIONAL_IDLE_DELAY_MS]：用户停止输入；
     *  · 兜底 [OPTIONAL_FALLBACK_DELAY_MS]：一直没出现上面两种情况时也必须加载。
     *
     * 加载线程本身已设 [android.os.Process.THREAD_PRIORITY_BACKGROUND]（见 PinyinEngine）。
     */
    private fun maybeLoadOptionalDict(reason: String) {
        if (optionalLoadTriggered) return
        optionalLoadTriggered = true
        Diagnostics.i(TAG, "可选词库: 开始后台加载（触发: $reason）")
        runCatching {
            PinyinEngine.loadOptionalAsync(this, delayMs = 0L) {
                Diagnostics.i(TAG, "可选词库已在后台就绪")
            }
        }.onFailure {
            Diagnostics.e(TAG, "启动可选词库加载失败: ${it.message}", it)
        }
    }

    /** 横屏时不要进全屏抽取模式，这个键盘很矮，没必要遮住宿主界面 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    // ── 麦克风手势 ────────────────────────────────────────────

    private fun onMicTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                holdAttempted = false
                setCancelArmed(false)
                if (mode == Mode.TOGGLE) {
                    stopOnUp = true
                    Diagnostics.i(TAG, "onMicTouch: DOWN 连续录音中，抬手即停")
                } else {
                    stopOnUp = false
                    ui.postDelayed(startHoldRunnable, HOLD_THRESHOLD_MS)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.HOLD) {
                    setCancelArmed(downY - event.rawY > cancelSlidePx)
                }
            }

            MotionEvent.ACTION_UP -> {
                ui.removeCallbacks(startHoldRunnable)
                when {
                    mode == Mode.HOLD -> {
                        Diagnostics.i(TAG, "onMicTouch: UP 长按结束 cancelArmed=$cancelArmed")
                        stopRecording(commit = !cancelArmed)
                    }
                    stopOnUp -> {
                        Diagnostics.i(TAG, "onMicTouch: UP 连续录音停止")
                        stopRecording(commit = true)
                    }
                    holdAttempted -> Unit // 长按没起来（未连接 / 无权限），别再当短按重试
                    else -> {
                        Diagnostics.i(TAG, "onMicTouch: UP 判定为短按 → 连续录音")
                        startRecording(Mode.TOGGLE)
                    }
                }
                stopOnUp = false
                setCancelArmed(false)
            }

            MotionEvent.ACTION_CANCEL -> {
                Diagnostics.i(TAG, "onMicTouch: CANCEL mode=$mode")
                ui.removeCallbacks(startHoldRunnable)
                if (mode == Mode.HOLD) stopRecording(commit = false)
                stopOnUp = false
                setCancelArmed(false)
            }
        }
        return true
    }

    private fun setCancelArmed(armed: Boolean) {
        if (cancelArmed == armed) return
        cancelArmed = armed
        micButton?.cancelArmed = armed
        if (mode == Mode.HOLD) {
            setHint(
                getString(
                    if (armed) R.string.hint_release_cancel else R.string.hint_release_send
                )
            )
        }
    }

    // ── 录音控制 ──────────────────────────────────────────────

    private fun startRecording(target: Mode) {
        // 语音输入关闭时 asr/recorder 为 null：入口直接置空，不做任何语音动作
        if (asr == null || recorder == null) {
            Diagnostics.i(TAG, "startRecording: 语音输入已禁用，忽略")
            return
        }
        if (mode != Mode.NONE) {
            Diagnostics.w(TAG, "startRecording: 已在录音中 mode=$mode，忽略")
            return
        }

        if (!hasMicPermission()) {
            Log.w(TAG, "startRecording: 缺少 RECORD_AUDIO 权限")
            Diagnostics.w(TAG, "startRecording: 缺少 RECORD_AUDIO 权限")
            setStatusText(getString(R.string.status_no_permission))
            setHint(getString(R.string.hint_grant_permission))
            return
        }

        // 请求音频焦点：被抢占时自动停止录音，避免与其他音频源冲突
        requestAudioFocus()

        if (asr?.beginTask() == null) {
            // 未连接：已请求的焦点要释放，避免占用却不录音
            Diagnostics.w(TAG, "startRecording: beginTask 返回 null（未连接）")
            abandonAudioFocus()
            setHint(getString(R.string.hint_not_connected))
            return
        }

        if (recorder?.start() != true) {
            Diagnostics.e(TAG, "startRecording: recorder.start 失败，取消任务")
            asr?.cancelTask()
            // 录音启动失败：同样释放焦点
            abandonAudioFocus()
            return
        }

        // 上一段还挂着预编辑文本的话先落地，避免被新结果覆盖掉
        if (prefs.useComposing) currentInputConnection?.finishComposingText()

        mode = target
        micButton?.recording = true
        Diagnostics.i(TAG, "startRecording: 开始录音 target=$target")
        setHint(
            getString(
                if (target == Mode.HOLD) R.string.hint_release_send else R.string.hint_tap_stop
            )
        )
        if (target == Mode.TOGGLE) ui.postDelayed(autoStopRunnable, MAX_TOGGLE_MS)
    }

    private fun stopRecording(commit: Boolean) {
        if (mode == Mode.NONE) return
        mode = Mode.NONE
        ui.removeCallbacks(autoStopRunnable)

        recorder?.stop()
        abandonAudioFocus()
        micButton?.recording = false
        micButton?.cancelArmed = false

        if (commit) {
            asr?.endTask()
            Diagnostics.i(TAG, "stopRecording: 收尾发送（识别中）")
            setHint(getString(R.string.hint_recognizing))
        } else {
            asr?.cancelTask()
            Diagnostics.i(TAG, "stopRecording: 取消（不采用结果）")
            // 取消时把已经回显的预编辑文本一起撤掉
            if (prefs.useComposing) {
                currentInputConnection?.setComposingText("", 1)
                currentInputConnection?.finishComposingText()
            }
            setHint(getString(R.string.hint_cancelled))
        }
    }

    private fun onRecorderError(message: String) {
        Diagnostics.e(TAG, "onRecorderError: $message")
        stopRecording(commit = false)
        setHint(message)
    }

    // ── 音频焦点 / 网络 / VAD ───────────────────────────────

    /** 本地 VAD：连续录音模式静音过久自动收尾出结果，长按模式只提示 */
    private fun onSilenceDetected() {
        Diagnostics.i(TAG, "onSilenceDetected: mode=$mode")
        if (mode == Mode.TOGGLE) {
            stopRecording(commit = true)
        } else {
            setHint(getString(R.string.hint_silence))
        }
    }

    private fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        focusRequest = req
        val result = runCatching { audioManager.requestAudioFocus(req) }
        result.onFailure {
            Log.w(TAG, "requestAudioFocus: ${it.message}")
            Diagnostics.w(TAG, "requestAudioFocus: ${it.message}")
        }.onSuccess {
            Diagnostics.i(TAG, "requestAudioFocus: result=$it")
        }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            runCatching { audioManager.abandonAudioFocusRequest(it) }
                .onFailure {
                    Log.w(TAG, "abandonAudioFocus: ${it.message}")
                    Diagnostics.w(TAG, "abandonAudioFocus: ${it.message}")
                }
        }
        focusRequest = null
    }

    private fun registerNetwork() {
        // 无需再做 SDK_INT 判断：NetworkCallback 是 API 24 引入，而本应用 minSdk = 26，
        // 原先的 `if (SDK_INT < N) return` 恒为 false（Lint ObsoleteSdkInt）。
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Diagnostics.i(TAG, "network: 网络恢复可用")
                ui.post { if (asr?.state == LinkState.OFFLINE) asr?.connect() }
            }

            override fun onLost(network: Network) {
                Diagnostics.w(TAG, "network: 网络断开")
            }
        }
        networkCallback = cb
        runCatching { connectivity.registerDefaultNetworkCallback(cb) }
            .onFailure {
                Log.w(TAG, "registerNetwork: ${it.message}")
                Diagnostics.w(TAG, "registerNetwork: ${it.message}")
            }
    }

    private fun unregisterNetwork() {
        networkCallback?.let {
            runCatching { connectivity.unregisterNetworkCallback(it) }
                .onFailure {
                    Log.w(TAG, "unregisterNetwork: ${it.message}")
                    Diagnostics.w(TAG, "unregisterNetwork: ${it.message}")
                }
        }
        networkCallback = null
    }

    // ── 识别结果 ──────────────────────────────────────────────

    private fun handleResult(message: RecognitionMessage) {
        val connection = currentInputConnection
        if (connection == null) {
            Log.w(TAG, "handleResult: InputConnection 已失效")
            Diagnostics.w(TAG, "handleResult: InputConnection 已失效 taskId=${message.taskId}")
            return
        }

        // 服务端给的是整段累积文本，直接整体覆盖，不要自己再拼
        val text = if (prefs.stripTrailingPunc) stripTrailingPunc(message.text) else message.text

        if (!message.isFinal) {
            if (prefs.useComposing) connection.setComposingText(text, 1)
            Diagnostics.v(TAG, "handleResult: 预编辑回显 ${message.duration.toInt()}s \"${text.take(40)}\"")
            setHint(getString(R.string.hint_progress, message.duration.toInt()))
            return
        }

        Diagnostics.i(TAG, "handleResult: 最终结果 \"${text.take(60)}\" (共${message.text.length}字)")
        if (prefs.useComposing) {
            connection.setComposingText(text, 1)
            connection.finishComposingText()
        } else {
            connection.commitText(text, 1)
        }
        setHint(getString(if (text.isBlank()) R.string.hint_empty else R.string.hint_done))
    }

    /** 对齐桌面端 trash_punc：句尾的逗号句号在聊天场景里更像噪音 */
    private fun stripTrailingPunc(text: String): String =
        text.trimEnd().trimEnd(*TRAILING_PUNC)

    // ── 编辑键 ────────────────────────────────────────────────

    private fun commit(text: String) {
        // 诊断：记录上屏内容（键盘/语音两种来源都走这里），方便核对输入链路
        Diagnostics.v(TAG, "commit: \"${text.take(40)}\" (键盘模式=$keyboardMode)")
        // 注意：打字/语音上屏走 commitText，不写系统剪贴板，不会触发剪贴板监听。
        // 这里【不能】调用 onOwnCommit()——否则标记会残留到下一次真实复制，
        // 导致用户复制的内容被误判为「自身操作」而跳过保存。
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * 删除键三击（双击+长按）：清空输入框全部文本。
     *
     * 实现：光标折叠到 0 后删「光标之后」的全部内容。**不再用 Ctrl+A + 删除**：
     * 按 Android 的 `InputConnection.deleteSurroundingText` 契约，它只作用于**选区前后**、
     * 无法影响选区内容 —— AOSP `BaseInputConnection` 里全选后 `a=0` ⇒ 删除量为 0，
     * 旧写法在标准宿主上一个字符都删不掉，只在「宿主不接受 Ctrl+A」时误删光标前的半截文本。
     * 新写法不依赖宿主对快捷键的支持：`afterLength` 会被实现钳到文本末尾。
     */
    private fun deleteAllText() {
        Diagnostics.i(TAG, "deleteAllText: 双击+长按退格，清空全部文本")
        val connection = currentInputConnection ?: return
        connection.setSelection(0, 0)
        connection.deleteSurroundingText(0, Int.MAX_VALUE)
        Diagnostics.i(TAG, "deleteAllText: 已发送清空（光标归零 + 删至末尾）")
    }

    /**
     * 全选：选中输入框全部文本（Ctrl+A）。
     * 与 [deleteAllText] 不同，本方法只选中不删除，配合「复制」使用。
     */
    private fun selectAllText() {
        Diagnostics.i(TAG, "selectAllText: 全选")
        val connection = currentInputConnection ?: return
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        connection.sendKeyEvent(makeCtrlKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, meta))
        connection.sendKeyEvent(makeCtrlKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, meta))
    }

    private fun makeCtrlKeyEvent(keyCode: Int, action: Int, meta: Int): KeyEvent =
        KeyEvent(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            action, keyCode, 0, meta,
        )

    /** 退格支持长按连删，纯语音输入改错字全靠它 */
    private fun bindBackspace(key: View) {
        key.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    ui.postDelayed(backspaceRunnable, BACKSPACE_DELAY_MS)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    ui.removeCallbacks(backspaceRunnable)
                    // 无障碍：抬手时补 performClick（CANCEL 不算点击，不补）
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 回车键：有拼音串时先按「原始按键」上屏英文，否则执行输入框声明的动作、再退化为换行。
     */
    private fun performEnter() {
        val connection = currentInputConnection ?: return

        // 拼音串非空说明用户已经打了字、候选栏也在显示：
        // 此时回车 = 把**实际按下的键**原样上屏（全拼 but→but；双拼 budv→budv，
        // 不做双拼→全拼转换），而不是选中候选、也不是换行。
        val rawComposing = pinyinKeyboard?.takeRawComposing().orEmpty()
        if (rawComposing.isNotEmpty()) {
            connection.commitText(rawComposing, 1)
            return
        }

        val editorInfo = currentInputEditorInfo
        val imeOptions = editorInfo?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val actionDisabled = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        val hasAction = !actionDisabled &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED

        if (hasAction) {
            connection.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun showImePicker() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showInputMethodPicker()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "openSettings: ${it.message}") }
    }

    // ── 状态渲染 ──────────────────────────────────────────────

    private fun renderLink(state: LinkState, detail: String?) {
        Diagnostics.v(TAG, "renderLink: state=$state detail=$detail mode=$mode")
        val colorRes: Int
        val label: String
        when (state) {
            LinkState.ONLINE -> {
                colorRes = R.color.dot_online
                label = getString(R.string.status_online, prefs.host, prefs.port)
            }

            LinkState.CONNECTING -> {
                colorRes = R.color.dot_connecting
                label = getString(R.string.status_connecting)
            }

            else -> {
                colorRes = R.color.dot_offline
                label = detail ?: getString(R.string.status_offline)
                // 录音中突然掉线：服务端永远收不到收尾包，主动放弃本次听写
                // 避免用户白白说话等不到结果，优于让 VAD 或松手自欺"识别中…"
                if (mode != Mode.NONE) {
                    stopRecording(commit = false)
                    setHint(getString(R.string.hint_not_connected))
                }
            }
        }
        statusDot?.backgroundTintList = ColorStateList.valueOf((keyboardThemeCtx ?: this).getColor(colorRes))
        statusLabel?.text = label
    }

    private fun setStatusText(text: String) {
        statusLabel?.text = text
    }

    private fun setHint(text: String) {
        hintLabel?.text = text
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val TAG = "JinnIme"

        /**
         * 同进程的 IME 实例：设置页改主题时直接通知它换肤（设置页与 IME 同进程，无需跨进程通信）。
         *
         * 用**弱引用**持有：静态强引用 Service 会被 lint 判为 `StaticFieldLeak`，且语义上
         * 不需要延长其生命周期 —— 服务存活期间系统自有强引用，`onDestroy` 亦会置空。
         */
        @Volatile
        private var instance: WeakReference<JinnIme>? = null

        /** 设置页改主题后调用（主线程）：键盘正显示时立即按新色板重建；尚未创建则等下次弹出自然读取 */
        fun notifyThemeChanged() {
            val ime = instance?.get() ?: return
            ime.ui.post { ime.applyThemeIfNeeded() }
        }

        /**
         * 设置页调整符号分组顺序 / 编辑收藏符号后调用（主线程）：键盘视图已创建则重建，**立即生效** ——
         * 分组顺序与收藏内容都在视图创建时读取一次，不重建就只会等到下次键盘整体重建。
         * 尚未创建（inputView == null）时不做事：下次创建自然读到新数据。
         */
        fun onSymbolLayoutChanged() {
            val ime = instance?.get() ?: return
            ime.ui.post { ime.rebuildInputViewForSymbolLayout() }
        }

        /** 键盘收起后多久视为「用户空闲」（太短会把「切个应用马上回来」也算空闲） */
        private const val OPTIONAL_IDLE_DELAY_MS = 20_000L

        /** 兜底等待：既不息屏也不收键盘时的最晚加载时间 */
        private const val OPTIONAL_FALLBACK_DELAY_MS = 180_000L

        /** 设置页「保存配置」广播 action：收到后立即刷新连接与键盘配置 */
        const val ACTION_CONFIG_UPDATED = "com.jinn.inputmethod.action.CONFIG_UPDATED"

        /**
         * 暂存粘贴文本的有效期：剪贴板面板点击粘贴但 IME 无连接时暂存，
         * 编辑框重新聚焦（onStartInputView）时提交。超过该时长视为过期丢弃，
         * 防止旧内容被粘到用户当前无关的其它输入框。
         */
        const val PENDING_PASTE_TTL_MS = 10_000L

        /** 超过这个时长判定为"按住说话" */
        const val HOLD_THRESHOLD_MS = 260L

        /** 按住时上滑超过这个距离即进入取消状态 */
        const val CANCEL_SLIDE_DP = 64f

        /** 连续录音的最长时长，3 分钟 */
        const val MAX_TOGGLE_MS = 180_000L

        const val BACKSPACE_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_MS = 55L

        /** 对齐 config_client.py 的 trash_punc */
        val TRAILING_PUNC = charArrayOf('，', '。', ',', '.')
    }
}

/**
 * 输入框身份键：包名 + `#` + fieldId（**纯函数**，便于单测）。
 *
 * 规则：
 *  - 包名缺失/为空时返回 null —— 身份未知，调用方退回「时效窗口 + 会话边界」兜底；
 *  - fieldId 必须带上包名：不同 App 的 fieldId 会撞号，只比对 fieldId 会放行跨应用粘贴。
 *
 * 注意：本函数是「暂存粘贴只允许提交回原输入框」这条安全约束的唯一判据，
 * 改动会直接影响剪贴板正文是否会被注入到无关输入框。
 */
internal fun fieldKeyOf(packageName: String?, fieldId: Int): String? =
    if (packageName.isNullOrEmpty()) null else "$packageName#$fieldId"
