package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * 本次缺陷修复的回归护栏。
 *
 * 每条断言对应一个已交叉验证过的真实缺陷，回归即复现：
 *  - [fieldKeyOf] 守住「暂存粘贴只回原输入框」（跨字段/跨应用注入）；
 *  - [searchRetainLimitReached] 守住「搜索命中集合有驻留上限」（内存护栏）；
 *  - [readCapped] 守住「更新检查响应体限长读取」；
 *  - [isInsideKeyBounds] 守住「按键抬起必须落在键内（含外扩）才算命中」，
 *    分号键曾漏掉该判定，按下后滑到相邻键抬起仍会把 `;` 追加进拼音串。
 *
 * 这些都是曾经没有、被补上的约束，断言失败意味着缺陷回归，
 * 不要通过放宽阈值让它们变绿。
 */
class RecentFixesRegressionTest {

    // ── 暂存粘贴的字段身份 ────────────────────────────────

    @Test
    fun `fieldKeyOf 空包名返回 null 表示身份未知`() {
        assertNull(fieldKeyOf(null, 0))
        assertNull(fieldKeyOf("", 12))
    }

    @Test
    fun `fieldKeyOf 同包同 fieldId 才相等`() {
        assertEquals("com.tencent.mm#7", fieldKeyOf("com.tencent.mm", 7))
        assertEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.tencent.mm", 7))
    }

    @Test
    fun `fieldKeyOf 跨应用同 fieldId 必须不相等`() {
        // 只比对 fieldId 会让微信的粘贴落进备忘录，这正是缺陷本身
        assertNotEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.notes.app", 7))
    }

    @Test
    fun `fieldKeyOf 同应用不同 fieldId 必须不相等`() {
        assertNotEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.tencent.mm", 8))
    }

    // ── 搜索结果的驻留上限 ────────────────────────────────

    @Test
    fun `搜索驻留未触限时继续累积`() {
        assertFalse(ClipboardStore.searchRetainLimitReached(0, 0L))
        assertFalse(
            ClipboardStore.searchRetainLimitReached(
                ClipboardStore.MAX_SEARCH_RESULTS - 1, 0L
            )
        )
    }

    @Test
    fun `搜索驻留条数触顶即停`() {
        assertTrue(
            ClipboardStore.searchRetainLimitReached(ClipboardStore.MAX_SEARCH_RESULTS, 0L)
        )
    }

    @Test
    fun `搜索驻留字节触顶即停_少量巨文本也要拦住`() {
        // 只限条数挡不住 3 条 256KB 的巨文本：字节预算必须独立生效
        assertTrue(
            ClipboardStore.searchRetainLimitReached(
                3, ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES + 1
            )
        )
    }

    @Test
    fun `搜索驻留预算不超过解密窗口预算`() {
        // 驻留集合活得比瞬时解密窗口久，预算只能更小，不能反过来
        assertTrue(
            ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES
        )
    }

    // ── 更新检查响应体限长 ────────────────────────────────

    private fun reader(text: String): BufferedReader = BufferedReader(StringReader(text))

    @Test
    fun `readCapped 未超限返回原文`() {
        val body = "line1\nline2\nline3\n"
        assertEquals(body, UpdateChecker.readCapped(reader(body), 1000))
    }

    @Test
    fun `readCapped 超限时截断且不抛异常`() {
        val long = "x".repeat(50)
        val out = UpdateChecker.readCapped(reader("$long\n$long\n$long\n"), 60)
        assertTrue("超限必须截断: $out", out.length <= 60)
    }

    @Test
    fun `readCapped 空响应返回空串`() {
        assertEquals("", UpdateChecker.readCapped(reader(""), 100))
    }

    // ── 按键命中的几何判定（分号键滑出不得上字）────────────

    @Test
    fun `命中判定 键内与边界外扩内都算命中`() {
        // 键 (100,200) 100x100，外扩 8px
        assertTrue(isInsideKeyBounds(110f, 210f, 100, 200, 100, 100, 8f))   // 正中
        assertTrue(isInsideKeyBounds(93f, 210f, 100, 200, 100, 100, 8f))    // 左边界外扩内
        assertTrue(isInsideKeyBounds(207f, 210f, 100, 200, 100, 100, 8f))   // 右边界外扩内
        assertTrue(isInsideKeyBounds(110f, 193f, 100, 200, 100, 100, 8f))   // 上边界外扩内
        assertTrue(isInsideKeyBounds(110f, 307f, 100, 200, 100, 100, 8f))   // 下边界外扩内
    }

    @Test
    fun `命中判定 滑出到相邻键必须算不命中`() {
        // 分号键的缺陷场景：按下 `;` 后滑到相邻键再抬起，不应把 `;` 追加进拼音串
        assertFalse(isInsideKeyBounds(91f, 210f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(209f, 210f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(110f, 191f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(110f, 309f, 100, 200, 100, 100, 8f))
    }

    @Test
    fun `命中判定 取不到布局信息时保守算命中`() {
        // width/height ≤ 0（未测量/已分离）：宁可保留旧行为，也不能让用户按不出字
        assertTrue(isInsideKeyBounds(0f, 0f, 0, 0, 0, 0, 8f))
        assertTrue(isInsideKeyBounds(9999f, 9999f, 100, 200, 0, 100, 8f))
    }

    // ── 源码对拍：只能用源码钉住的修复（行为要 Context / 视图 / 进程，JVM 测不到）──
    //
    // 这一组对应 2026-09-26 那批修复里「改回去会静默复现」的几处：改法都在一两行里，
    // 单测与 lint 都看不见（真机崩溃、隐私留盘、丢设置都属于这类），所以用源码把**结论**钉住。
    // 断言失败先回 `BUG.md` 的「已修复」区看当初的判定依据，别直接把断言删掉。

    private fun sourceOf(name: String): String = (
        listOf(
            File("src/main/java/com/jinn/inputmethod/$name"),
            File("app/src/main/java/com/jinn/inputmethod/$name"),
        ).firstOrNull { it.isFile } ?: error("找不到 $name（cwd=${File("").absolutePath}）")
        ).readText()

    /**
     * 只留代码行（去掉整行注释）。
     *
     * 这一组的修复点旁边都写着「为什么必须这么写」的注释 —— 注释里大概率出现同一个调用名，
     * 不剔掉的话「把调用删掉、注释留着」也会绿（实测过：`saveAndRestart` 的 KDoc 里就写着
     * `restartImeProcess()`）。只丢整行注释，不动行尾注释：行尾注释里带 `//` 的字符串（URL）会被误切。
     */
    private fun codeOf(name: String): String = sourceOf(name).lines()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }
        .joinToString("\n")

    /**
     * 截取 [marker] 之后那个花括号块（从 marker 后的第一个 `{` 起配对到对应 `}`）。
     *
     * 不用「取 marker 之后 N 个字符」：窗口取小了会把修复点漏在外面（`saveAndRestart` 第一版就栽在
     * 1500 字符窗口上，函数实际 2500+ 字符），取大了又会把相邻函数的代码算进来 —— 花括号配对没有这个两难。
     */
    private fun blockAfter(text: String, marker: String): String {
        val i = text.indexOf(marker)
        assertTrue("源码里找不到锚点「$marker」—— 改名/重构后请同步本用例", i >= 0)
        val open = text.indexOf('{', i)
        assertTrue("锚点「$marker」之后没有花括号块", open > i)
        var depth = 0
        for (j in open until text.length) {
            when (text[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, j + 1)
                }
            }
        }
        error("锚点「$marker」的花括号不配对")
    }

    @Test
    fun `保存并重启必须先落盘再杀进程`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "fun saveAndRestart")
        assertTrue(
            "saveAndRestart 必须调 restartImeProcess()：直接 postDelayed + killProcess " +
                "会在 IO 抖动时丢掉最后一批 apply()（重启后设置回退）",
            body.contains("restartImeProcess()"),
        )
    }

    @Test
    fun `崩溃快照的原始中转件必须落在缓存目录且成对删除`() {
        val text = codeOf("Diagnostics.kt")
        assertTrue(
            "中转件是**未过滤** logcat 的唯一副本，不能落在会被导出诊断包整体打包的日志目录",
            text.contains("File(cacheDir ?: dir"),
        )
        assertTrue("中转件成功 / 失败都要删（finally 里收口）", text.contains("runCatching { raw.delete() }"))
    }

    @Test
    fun `七天清理必须覆盖导出用的 device-info`() {
        val body = blockAfter(codeOf("Diagnostics.kt"), "private fun cleanupOldLogs")
        assertTrue(
            "cleanupOldLogs 要认 DEVICE_INFO_FILE：进程被杀留下的该文件不匹配 jinn- / logcat- 前缀，" +
                "会永久留在日志目录并混进之后每次导出包",
            body.contains("DEVICE_INFO_FILE"),
        )
    }

    @Test
    fun `切符号层与数字层必须先收起剪贴板面板`() {
        val text = codeOf("PinyinKeyboardView.kt")
        for (anchor in listOf("btnSymbol.setOnClickListener", "btnDigit.setOnClickListener")) {
            assertTrue(
                "$anchor 必须调 hidePanelForLayerSwitch()：两层的键都在字母区里，" +
                    "面板显示时字母区整体 GONE ⇒ 切了层既看不到键、红色「返回」也被顶替，用户没有退出口",
                blockAfter(text, anchor).contains("hidePanelForLayerSwitch()"),
            )
        }
    }

    @Test
    fun `进符号层必须清掉未上屏的拼音`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "btnSymbol.setOnClickListener")
        assertTrue(
            "进符号层要调 clearComposingState()：该层不显示拼音条与候选，残留 composing 会「看不见却仍生效」" +
                "（退格空删、收起键盘把上一次首候选上屏）",
            body.contains("clearComposingState()"),
        )
    }

    @Test
    fun `预测候选必须让清空按钮可见`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "private fun refreshCandidateBar")
        assertTrue(
            "预测分支要走 showPinyinBarOnly()：✕ 是拼音条的子视图，拼音条 GONE 时它一起消失（只能退格清预测）",
            body.contains("showPinyinBarOnly()"),
        )
    }

    @Test
    fun `滚动收起操作条必须判 lateinit 已初始化`() {
        val text = codeOf("ClipboardPanelView.kt")
        assertTrue(
            "onScroll 里读 actionBar 前必须判 ::actionBar.isInitialized —— setOnScrollListener 注册时会**同步回调一次**，" +
                "那时 actionBar 还没赋值，真机实测会让键盘完全弹不出来（连崩 4 次）",
            text.contains("::actionBar.isInitialized"),
        )
    }

    @Test
    fun `词库重装不得先删旧包`() {
        val text = codeOf("DictManagerActivity.kt")
        assertTrue("必须直接 renameTo（POSIX 原子替换，目标已存在也覆盖）", text.contains("tmp.renameTo(dst)"))
        assertFalse(
            "不得出现 dst.delete()：先删目标再改名，改名失败时用户会同时失去旧包与新包",
            text.contains("dst.delete()"),
        )
    }

    @Test
    fun `系统剪贴板粘贴必须走统一实现`() {
        val body = blockAfter(codeOf("JinnIme.kt"), "private fun pasteClipboard()")
        assertTrue(
            "功能面板「粘贴」必须委托 pasteClipboardText(text)：系统剪贴板装着别的应用复制的整篇文档，" +
                "裸 commitText 会撞 Binder 事务上限抛 TransactionTooLargeException（未捕获 = IME 进程崩溃）",
            body.contains("pasteClipboardText(text)"),
        )
        assertFalse(
            "pasteClipboard 里不得直接 commitText：上限闸与 runCatching 都在 pasteClipboardText 里",
            body.contains("commitText"),
        )
    }

    @Test
    fun `分号键抬起必须判自身是否仍可见`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "private fun handleSemicolonTouch")
        assertTrue(
            "ACTION_UP 里必须判 keySemicolon.visibility：按下后按键可能被切层/中英/大写置 GONE，" +
                "而已缓存目标仍会收到 UP ⇒ 一个看不见的分号被追加进拼音串（英文态下尤其费解）",
            body.contains("keySemicolon.visibility != View.VISIBLE"),
        )
    }

    @Test
    fun `导入完成对话框必须登记且在取消时刷新页面`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "private fun doImportConfig")
        assertTrue(
            "「导入完成」框必须走 showTipDialog 登记：它是代码创建的，不登记会在旋转重建时泄漏窗口，" +
                "且重建后这次导入的结论无处可看",
            body.contains("showTipDialog(dialog)"),
        )
        assertTrue(
            "必须在 setOnCancelListener 里 recreate()：返回键/点框外走 cancel 不会触发「稍后」回调，" +
                "而此刻配置已落盘，页面上的旧值必须一并刷新",
            body.contains("setOnCancelListener { recreate() }"),
        )
    }

    @Test
    fun `词库下载完成必须刷新当前活页`() {
        val text = codeOf("DictManagerActivity.kt")
        assertTrue(
            "下载回调必须刷新 current 实例（activePage?.get()）：回调捕获的是发起下载的 Activity，" +
                "下载期间旋转/关掉重进时旧实例的刷新会写进已 detach 的 View ⇒ 新页面停在" +
                "「按钮禁用、无状态行」的旧快照上",
            text.contains("activePage?.get()"),
        )
    }

    @Test
    fun `日志目录必须支持延迟可用`() {
        val text = codeOf("Diagnostics.kt")
        assertTrue(
            "必须调 retryLogDirIfNeeded()：存储未挂载时 getExternalFilesDir 返回 null，而本对象是进程级单例、" +
                "IME 长期存活 ⇒ 只解析一次会让该进程此后一条文件日志都不写（诊断包恒为空）",
            text.contains("retryLogDirIfNeeded("),
        )
        assertTrue(
            "落盘路径上必须按天闸补清理（maybeCleanupOldLogs）：只在 init 清一次时，常驻进程超过 7 天" +
                "再不清理、日志目录无界增长",
            text.contains("maybeCleanupOldLogs()"),
        )
    }

    @Test
    fun `收藏页添加对话框必须随页面销毁`() {
        val text = codeOf("FavoriteSymbolsActivity.kt")
        assertTrue(
            "onDestroy 里必须 dismiss addDialog：对话框是代码创建的，不登记会随旋转泄漏窗口，" +
                "此时点「确定」还会把符号写进已 detach 的旧列表",
            text.contains("addDialog?.dismiss()"),
        )
    }

    @Test
    fun `语言下拉未触摸时不得改写导入值`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "private fun readLanguage")
        assertTrue(
            "readLanguage 必须先判 languageSpinnerTouched：导入的备份可能带本版不认识的取值，" +
                "下拉退回显示第 0 项，读回它等于把导入值静默改写（其他四个下拉同款闸门）",
            body.contains("if (!languageSpinnerTouched) return prefs.language"),
        )
    }

    @Test
    fun `导出诊断包在页面重建后必须留下提示`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "private fun exportDiagnostics")
        assertTrue(
            "isFinishing/isDestroyed 分支必须写 pendingNotice：与配置导出同款兜底，" +
                "否则用户点了导出、界面上什么都没发生（打包好的临时包留在缓存目录无人认领）",
            body.contains("pendingNotice ="),
        )
    }

    // ── 第二批：资源 / 协议 / 降级三视角并行审查后的修复 ──

    @Test
    fun `切层必须收起方向面板`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "private fun hidePanelForLayerSwitch")
        assertTrue(
            "hidePanelForLayerSwitch 必须收起方向面板：面板与符号层的键同在字母区，不收面板就" +
                "「切了层却看不到键」，候选栏又被符号分组标签顶替、红色「返回」根本没被创建，用户没有退出口",
            body.contains("hideDirectionPanel()"),
        )
    }

    @Test
    fun `换肤延后判据必须覆盖方向面板`() {
        val text = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "hasActiveOverlay 必须含 directionPanelVisible：方向面板也是视图内的临时状态，" +
                "重建会连上一次的拖选一起丢，而 IME 侧的 Anchor/Focus 要到下次弹键盘才复位（状态分裂一整个会话）",
            text.contains("clipboardActive || searchPanel.isActive() || directionPanelVisible"),
        )
    }

    @Test
    fun `粘贴前必须判剪贴板条目数`() {
        val body = blockAfter(codeOf("JinnIme.kt"), "private fun pasteClipboard()")
        assertTrue(
            "必须判 itemCount==0：`ClipData(label, mimeTypes, emptyArray())` 是合法构造，getItemAt(0) 会抛" +
                " IndexOutOfBoundsException，而这里不在任何 runCatching 内 —— 主线程点击回调上未捕获即崩进程",
            body.contains("(clip?.itemCount ?: 0) == 0"),
        )
    }

    @Test
    fun `索引缓存不得先删旧文件`() {
        val body = blockAfter(codeOf("PinyinEngine.kt"), "private fun writeIndexCacheAtomically")
        assertTrue("改名覆盖式写入（POSIX rename 替换目录项，旧映射仍指向旧 inode）", body.contains("tmp.renameTo(file)"))
        assertFalse(
            "不得 file.delete()：先删目标会制造一个「目标不存在」的窗口，改名失败时旧缓存与新缓存同时失去，" +
                "下次启动只能整份重建（同 UserFrequency.writeAtomically 的反例注释）",
            body.contains("file.delete()"),
        )
    }

    @Test
    fun `索引失败后必须能补试`() {
        val engine = codeOf("PinyinEngine.kt")
        assertTrue(
            "load 的重入判据必须是 loaded && fullLoaded：只看 loaded 会让「索引段失败」的进程永远拿不到补试，" +
                "整个进程只剩高频子集、「词库补全中」也永不消失",
            engine.contains("if (loaded && fullLoaded) return"),
        )
        assertTrue("第二段要抽成可重用的 loadFullIndex", engine.contains("private fun loadFullIndex(context: Context): Long"))
        assertTrue(
            "等待加载中不得占用重试次数（上限 3 次，冷启动 6~10.7s 里用户会反复弹键盘）",
            engine.contains("isLoading"),
        )
        assertTrue(
            "IME 侧补试闸门必须用 isFullyLoaded + isLoading，用 isLoaded 等于把索引失败排除在补试之外",
            codeOf("JinnIme.kt").contains("PinyinEngine.isFullyLoaded || PinyinEngine.isLoading"),
        )
    }

    @Test
    fun `可选词库加载失败不得报成未安装`() {
        val text = codeOf("PinyinEngine.kt")
        assertTrue(
            "必须区分「没装」与「装了但一个都没读进来」：后者原先也打「未安装可选词库」，" +
                "「词库装了却不生效」的排查会被直接带偏（包损坏只有上文一条 W 级日志）",
            text.contains("packs.isEmpty()") && text.contains("可选词库全部加载失败"),
        )
    }

    @Test
    fun `词库重装重启前必须刷用户词频`() {
        val body = blockAfter(codeOf("DictManagerActivity.kt"), "private fun restartImeForDict")
        assertTrue(
            "killProcess 是 SIGKILL、不会走 onDestroy：不先同步 flush，防抖窗口（2s）内的学习会随重启丢掉，" +
                "而这条路径恰恰是应用自己主动发起的",
            body.contains("flushUserFrequency()"),
        )
    }

    @Test
    fun `采集线程必须有顶层异常兜底`() {
        val text = codeOf("MicRecorder.kt")
        assertTrue(
            "采集线程的未捕获异常在 Android 上会走默认处理器杀掉 IME 进程：" +
                "stop() 的 interrupt 可能打在 Thread.sleep 上、release 后阻塞的 read 会抛 IllegalStateException",
            text.contains("采集线程异常退出") && text.contains("private fun loopBody(audioRecord: AudioRecord)"),
        )
    }

    @Test
    fun `等识别结果必须有超时兜底`() {
        val text = codeOf("JinnIme.kt")
        assertTrue(
            "stopRecording(commit=true) 后必须起 recognizeTimeout：服务端丢任务/卡住时没有任何回调，" +
                "状态条会永远停在「识别中…」（唯一复位点是下次弹键盘）",
            text.contains("ui.postDelayed(recognizeTimeout, RECOGNIZE_TIMEOUT_MS)"),
        )
        assertTrue("结果到达 / 取消 / 会话开始都要清掉兜底任务", text.contains("ui.removeCallbacks(recognizeTimeout)"))
        assertTrue(
            "掉线时若正在等结果也要复位状态条",
            blockAfter(text, "private fun renderLink").contains("awaitingResult"),
        )
    }

    @Test
    fun `语音结果必须校验应用归属`() {
        val body = blockAfter(codeOf("JinnIme.kt"), "private fun handleResult")
        assertTrue(
            "必须比对接收到结果时的包名（voiceResultPackage）：松手后服务端还要 1~3s 才回结果，" +
                "期间用户可能已切到别的应用 —— 跨应用落字是隐私问题，与暂存粘贴同一条口径",
            body.contains("voiceResultPackage"),
        )
    }

    @Test
    fun `剪贴板分页必须检测列表变化`() {
        val body = blockAfter(codeOf("ClipboardPanelView.kt"), "private fun loadNextPage")
        assertTrue(
            "翻下一页前必须比对总数：分页游标是 SQL OFFSET，面板打开期间有新内容入库时" +
                "头部插入会让下一页重复返回已显示的行、并永久跳过尾部若干行",
            body.contains("total != categoryTotal"),
        )
    }

    // ── 第三批：存储层与引擎状态机审查后的修复 ──

    @Test
    fun `数字层上屏前必须先提交拼音`() {
        val text = codeOf("PinyinKeyboardView.kt")
        val i = text.indexOf("val digit = DIGIT_MAP[c] ?: return")
        assertTrue("源码里找不到数字层按键分支（改名 / 重构后请同步本用例）", i >= 0)
        val window = text.substring(i, minOf(text.length, i + 400))
        assertTrue(
            "数字是即时上屏、候选要等收起键盘才提交：不先 commitComposing 就会「数字在前、候选在后」" +
                "（真机实测：打拼音 → 切数字层点 1 → 收起键盘，正文是「1你」）",
            window.contains("commitComposing()"),
        )
    }

    @Test
    fun `剪贴板导出必须防并发位移`() {
        val body = blockAfter(codeOf("ConfigBackupManager.kt"), "private fun collectClipboard(context: Context)")
        assertTrue(
            "导出侧的分页游标是 SQL OFFSET：导出期间用户复制一条（插头 + 裁尾）会让后续页位移、" +
                "静默跳过若干行（既没导出也不进 dropped）。必须比对总数并重来（面板侧已有同样防护）",
            body.contains("db.count() == before") && body.contains("EXPORT_CLIP_ATTEMPTS"),
        )
    }

    @Test
    fun `词库恢复的落盘上限不把跳过项算进去`() {
        val text = codeOf("ConfigBackupManager.kt")
        assertTrue("上限判据必须存在（重构后同步本用例）", text.contains("pending.size >= MAX_DICT_FILES"))
        assertFalse(
            "上限不得把跳过项（清单外 / 校验不符）算进来：它们不落盘，" +
                "计进来会让一个多余条目把整包（含设置 / 词频 / 剪贴板）拒收",
            text.contains("skippedByName >= MAX_DICT_FILES"),
        )
    }

    @Test
    fun `配置包的加解密写盘不得先删目标`() {
        assertFalse(
            "encrypt / decrypt 都不许 dest.delete()：先删目标会制造「目标不存在」的窗口，" +
                "改名失败时旧包与新包同时失去（与 UserFrequency.writeAtomically 同一条口径）",
            codeOf("ConfigCrypto.kt").contains("dest.delete()"),
        )
    }

    @Test
    fun `SAF 落盘失败必须清掉半截文件`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "private fun copyConfigZipTo")
        assertTrue(
            "失败分支必须 contentResolver.delete(uri)：覆盖写一开始就把目标截断，" +
                "留下半截文件只会让用户误以为是有效备份（拿去导入只会得到「密码错误或文件已损坏」）",
            body.contains("contentResolver.delete(uri"),
        )
    }

    // ── 第四批：协议与更新链路审查后的修复 ──

    @Test
    fun `检查更新的看门狗必须覆盖两源总预算`() {
        val watchdog = Regex("""UPDATE_WATCHDOG_MS = ([\d_]+)L""")
            .find(codeOf("SettingsActivity.kt"))?.groupValues?.get(1)
            ?.replace("_", "")?.toLongOrNull()
        assertTrue("源码里找不到 UPDATE_WATCHDOG_MS（改名后请同步本用例）", watchdog != null)
        assertTrue(
            "看门狗（${watchdog}ms）必须大于两源串行总预算（${UpdateChecker.TOTAL_BUDGET_MS}ms）：" +
                "GitHub 不可达时会「超时 + 回退 Gitee + 再超时」，看门狗短了会在请求仍在途时解锁按钮，" +
                "防重入判据随之失效、用户能并发发起第二次检查并重复弹窗",
            watchdog!! > UpdateChecker.TOTAL_BUDGET_MS,
        )
    }

    @Test
    fun `更新请求必须受总预算约束`() {
        val text = codeOf("UpdateChecker.kt")
        assertTrue("总预算常量必须存在", text.contains("TOTAL_BUDGET_MS"))
        assertTrue(
            "httpGet 必须按剩余预算设超时（coerceAtMost）：单个 10s 超时管不住两源串行",
            text.contains("coerceAtMost(TIMEOUT_MS.toLong())"),
        )
    }

    @Test
    fun `开始新录音必须清掉上一段的等待态`() {
        val body = blockAfter(codeOf("JinnIme.kt"), "private fun startRecording")
        assertTrue(
            "startRecording 必须复位 awaitingResult 并撤掉 recognizeTimeout：新录音会让上一段的结果" +
                "被 AsrClient 当过期任务丢弃，而那个 60s 兜底若留着，会在这次录音中途把状态条改成「未连接」",
            body.contains("awaitingResult = false") && body.contains("ui.removeCallbacks(recognizeTimeout)"),
        )
    }

    @Test
    fun `词库下载必须复用同一个客户端`() {
        val body = blockAfter(codeOf("DictManagerActivity.kt"), "private fun fetchToFile")
        assertFalse(
            "fetchToFile 里不得 new OkHttpClient：每个 URL（含重试）各建一套连接池与调度线程池",
            body.contains("OkHttpClient.Builder()"),
        )
        assertTrue("必须用共享单例", body.contains("httpClient.newCall"))
    }

    @Test
    fun `可选词库摘要的文档口径必须是压缩文件`() {
        assertFalse(
            "KDoc 不得写成「未压缩文件的 SHA-256」：两条校验路径算的都是 .xz 字节，" +
                "按文档填值会让下载与备份导入 100% 校验失败",
            codeOf("OptionalDicts.kt").contains("未压缩文件的 SHA-256"),
        )
    }

    @Test
    fun `迁移必须补齐空 content_hash`() {
        val text = codeOf("ClipboardDb.kt")
        assertTrue(
            "必须有 backfillBlankHashes：空哈希不代表同一内容（哈希算的是明文），多行空值会被" +
                "mergeDuplicates 判成同组、只留最新一条 —— 静默删掉用户的不同内容",
            text.contains("private fun backfillBlankHashes"),
        )
        assertTrue(
            "补齐判据要同时覆盖 NULL 与空串（列默认值是空串，更早的表可能留 NULL）",
            text.contains("WHERE content_hash IS NULL OR content_hash = ''"),
        )
        assertTrue(
            "占位值要用 legacy: 前缀 + id：稳定哈希是 64 位 hex，不会碰撞；占位行也不会被后续入库查重误命中",
            text.contains("LEGACY_HASH_PREFIX = \"legacy:\"") &&
                text.contains("content_hash = '\$LEGACY_HASH_PREFIX' || id"),
        )
        assertTrue(
            "两个重建分支的搬运 SELECT 都要 COALESCE(content_hash, '')：新表该列是 NOT NULL，" +
                "旧表若留 NULL 会让 INSERT 失败 —— 升级抛异常就是整库打不开",
            Regex("COALESCE\\(content_hash, ''\\)").findAll(text).count() >= 2,
        )
        val calls = Regex("backfillBlankHashes\\(db\\)").findAll(text).map { it.range.first }.toList()
        assertTrue("v4 与 v5 两个升级分支都要补（实际 ${calls.size} 处）", calls.size >= 2)
        assertTrue(
            "v4 分支：补齐必须紧挨在 mergeDuplicates 之前",
            Regex("backfillBlankHashes\\(db\\)\\s*\\n\\s*mergeDuplicates\\(db\\)").containsMatchIn(text),
        )
        assertTrue(
            "v5 分支：补齐必须排在建唯一索引之前（否则多行空值让建索引失败，升级抛异常 = 整库打不开）",
            calls.last() < text.lastIndexOf("CREATE UNIQUE INDEX idx_items_hash"),
        )
    }

    @Test
    fun `候选渲染上限不得回退`() {
        val m = Regex("const val MAX_RENDERED_CANDIDATES = (\\d+)")
            .find(codeOf("PinyinKeyboardView.kt"))
        assertTrue("常量必须存在", m != null)
        val n = m!!.groupValues[1].toInt()
        assertTrue(
            "渲染上限不得低于 36（当前 $n）：单音节候选可达 60 条，截断过狠时靠后的候选在滚动区里" +
                "根本不存在（点不到）；帧统计实测 54 个 View 与 36 个无差异",
            n >= 36,
        )
    }

    @Test
    fun `收尾包未送出时不得挂等待态`() {
        val asr = codeOf("AsrClient.kt")
        assertTrue(
            "endTask / cancelTask 必须返回 Boolean：调用方要据它决定是否还等最终结果",
            asr.contains("fun endTask(): Boolean") && asr.contains("fun cancelTask(): Boolean"),
        )
        val body = blockAfter(codeOf("JinnIme.kt"), "private fun stopRecording(")
        assertTrue(
            "commit 分支必须判 endTask() 的返回值：收尾包没送出时服务端不会回结果，" +
                "挂上等待态要等 60s 兜底才复位，期间用户以为还在识别",
            body.contains("asr?.endTask() != true"),
        )
    }

    @Test
    fun `麦克风永久拒绝必须给系统设置入口`() {
        val text = codeOf("SettingsActivity.kt")
        assertTrue(
            "必须记录永久拒绝态（拒绝且系统不再弹窗）",
            text.contains("micDeniedForever"),
        )
        assertTrue(
            "必须以「已请求过仍未授予」为判据（micRequested）：Android 10 起第二次请求被系统静默拒绝，" +
                "只看 shouldShowRequestPermissionRationale 会让按钮继续点了没反应",
            text.contains("micRequested"),
        )
        assertTrue(
            "永久拒绝时按钮要跳系统应用详情页：本页再申请不会弹窗，按钮会永远没效果",
            text.contains("ACTION_APPLICATION_DETAILS_SETTINGS"),
        )
        assertTrue(
            "onResume 必须刷新麦克风状态：从系统设置授权后返回，页面不能还显示「未授权」",
            blockAfter(text, "override fun onResume()").contains("refreshMicState()"),
        )
    }

    @Test
    fun `诊断落盘正文必须过护栏`() {
        val body = blockAfter(codeOf("Diagnostics.kt"), "private fun log(")
        assertTrue(
            "落盘组装必须用 sanitizeForFile(msg)：正文一旦写错（整篇剪贴板文本 / 搜索词），" +
                "会绕过 V 级闸直接落盘并随诊断包外传",
            body.contains("sanitizeForFile(msg)"),
        )
        assertFalse("不得裸 append(msg)", body.contains(".append(msg)"))
        assertTrue(
            "堆栈要脱敏但不截断（异常 message 可能含内容片段；堆栈上千字符，截断会砍关键帧）",
            body.contains("redactSensitive(stackTraceOf(it))"),
        )
    }
}
