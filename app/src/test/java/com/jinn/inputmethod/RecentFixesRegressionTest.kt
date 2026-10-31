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

    /**
     * 导入配置后必须同步「不需要重启就生效」的开关（2026-09-27）。
     *
     * 这几个开关平时靠各自监听器即时生效，而导入是**绕过监听器**直接写 prefs 的：漏了同步，
     * 用户点「稍后」后看到的就是「导入成功但行为没变」（繁体 / 档位 / 模糊音 / 学习）。
     * 改回去不报错（只有真机导入才看得见），所以用源码把结论钉住。
     */
    @Test
    fun `导入配置后同步即时生效的开关`() {
        val code = codeOf("SettingsActivity.kt")
        // 判据是「定义之前已经出现过一次」而不是「文件里找得到」：只 contains 的话，
        // 定义行自己就把它满足了（第一版就是栽在这 —— 删掉调用照样绿，变异验证抓出来的）
        val call = code.indexOf("syncImmediateSettings()")
        val def = code.indexOf("private fun syncImmediateSettings")
        assertTrue("导入成功分支必须调用 syncImmediateSettings()（调用须出现在定义之前，当前 call=$call def=$def）",
            def >= 0 && call in 0 until def)
        val body = blockAfter(code, "private fun syncImmediateSettings()")
        // 循环变量别叫 call：会遮蔽上面的 val call（Kotlin 报 "Name shadowed" 告警）
        for (marker in listOf(
            "setRareTiers(p.rareTier2, p.rareTier3)",
            "setTraditional(p.useTraditional)",
            "setFuzzyMask(p.fuzzyPinyinMask)",
            "UserFrequency.setEnabled(p.userLearning)",
        )) {
            assertTrue("同步体缺少 $marker", body.contains(marker))
        }
    }

    // ── 源码对拍：只能用源码钉住的修复（行为要 Context / 视图 / 进程，JVM 测不到）──
    //
    // 这一组对应 2026-09-26 那批修复里「改回去会静默复现」的几处：改法都在一两行里，
    // 单测与 lint 都看不见（真机崩溃、隐私留盘、丢设置都属于这类），所以用源码把**结论**钉住。
    // 断言失败先回 `BUG.md` 的「已修复」区看当初的判定依据，别直接把断言删掉。

    // 顺序 / 频率语义的判据要求（L-109）已提升为**全仓元守卫**：见
    // `TestSourcesTest.顺序语义用例必须带位置或范围判据`（L-1151）—— 原实现只扫本文件、
    // 且方法体里出现任意一个 `assertFalse(` 即合规，两处都能绕（换文件 / 去掉用例名里的「先」）。

    /**
     * 断言 [call] 在 [body] 里**独占一行**（不是被包进 `if (…)` 或别的分支）。
     *
     * 名字型判据的第一种假绿（BUG.md L-109）：把调用挪进 `if (false)` / 另一个不执行的分支时，
     * `contains("call()")` 依然成立 —— 实测：把 `clearComposingState()` 包进死分支后守卫仍绿。
     */
    private fun assertStatementLine(body: String, call: String, why: String) {
        assertTrue(
            "$why（判据要求 `$call` 独占一行：被包进条件 / 死分支时不再算数，见 BUG.md L-109）",
            body.lines().any { it.trim() == call },
        )
    }

    /**
     * 断言 [first] 出现在 [second] **之前**（顺序语义必须落到位置上，BUG.md L-109）。
     *
     * 第二种假绿：把两行调换顺序（如 `commitComposing()` 挪到上屏之后）时 `contains` 依然成立。
     */
    private fun assertBefore(body: String, first: String, second: String, why: String) {
        val i = body.indexOf(first)
        val j = body.indexOf(second)
        assertTrue(
            "$why（判据要求位置：`$first` 应在 `$second` 之前，实际 $i / $j，见 BUG.md L-109）",
            i >= 0 && j > i,
        )
    }

    // ── 线程卫生（L-121）────────────────────────────────────

    /**
     * 主源码里**每个**线程 / 执行器创建点都必须在同一语句窗口内显式写 `isDaemon = true`。
     *
     * 窗口口径：从创建点起，**切到下一个创建点**为止（不跨语句），上限 30 行；注释行不参与判定
     * （否则「把 `isDaemon` 删掉、注释里留着」也会绿 —— L-116 家族的老坑）。
     * 正则必须带**词边界**：`runOnUiThread {` 里的 `Thread` 是子串，不加边界就会把它当线程创建点
     * （上一轮审计我的第一版扫描就栽在这上面，见 B-87）。
     *
     * 为什么值得钉：Android 强杀进程时 daemon 与否没有差别，但同一份代码两种写法会让读者猜意图；
     * 一旦有人把收尾逻辑改成 `join` / 等待，或换进程模型，daemon 与否就会变成真实行为差异。
     */
    @Test
    fun `主源码里每个线程创建点都显式声明 isDaemon`() {
        val dir = listOf(
            File("src/main/java/com/jinn/inputmethod"),
            File("app/src/main/java/com/jinn/inputmethod"),
        ).firstOrNull { it.isDirectory } ?: error("找不到主源码目录（cwd=${File("").absolutePath}）")
        val creator = Regex("""(?<![A-Za-z])Thread\s*[({]|Executors\.new\w+""")
        val missing = mutableListOf<String>()
        for (f in dir.listFiles { it -> it.extension == "kt" }!!.sortedBy { it.name }) {
            // 剥注释走共用 TestSources.codeOf（BUG.md L-116）：行数不变，下面的行号窗口判据不受影响
            val lines = TestSources.codeOf(f.readText()).lines()
            val points = lines.indices.filter { creator.containsMatchIn(lines[it]) }
            for ((k, i) in points.withIndex()) {
                // 窗口止于**它自己的** `.start()` 所在行（找不到则退化为 30 行）。
                // 两种「看着合理」的口径本轮都实测翻车：① 固定 N 行 —— SettingsActivity 的导入线程光函数体
                // 就 60+ 行、PinyinEngine 的 `thread.start()` 在 38 行外；② 切到下一个创建点 ——
                // BackgroundIo 的线程工厂把 `isDaemon` 写在**下一行**（`Executors.new* { r -> Thread(r…).apply{…} }`）。
                // 代价是窗口可能邻到下一个语句（偏保守：宁可漏判，不误报）。
                val nextPoint = points.getOrNull(k + 1) ?: lines.size
                val startLine = (i until minOf(nextPoint + 1, lines.size)).firstOrNull { ".start()" in lines[it] }
                val end = minOf(startLine?.plus(1) ?: (i + 30), lines.size)
                val window = (i until end).joinToString("\n") { lines[it] }
                if ("isDaemon = true" !in window) missing += "${f.name}:${i + 1}"
            }
        }
        assertTrue(
            "这些线程创建点没写 isDaemon = true（写法：Thread { … }.apply { isDaemon = true }.start()）：$missing",
            missing.isEmpty(),
        )
    }

    /**
     * 只留代码行（剥掉行注释 / 行尾注释 / 块注释，引号里的 `//` 不误切 —— 见 [TestSources.codeOf]）。
     *
     * 这一组的修复点旁边都写着「为什么必须这么写」的注释 —— 注释里大概率出现同一个调用名，
     * 不剔掉的话「把调用删掉、注释留着」也会绿（实测过：`saveAndRestart` 的 KDoc 里就写着
     * `restartImeProcess()`）。旧版只丢**整行**注释，行尾注释仍留着一个口子（BUG.md L-116 建议 ④）。
     */
    private fun codeOf(name: String): String = TestSources.codeSource(name)

    /**
     * 截取 [marker] 之后那个花括号块 —— 转调共用助手（BUG.md L-1145 / BUG-07：各测试文件原先各写一份，
     * 收敛到 [TestSources.blockAfter]）。
     *
     * 不用「取 marker 之后 N 个字符」：窗口取小了会把修复点漏在外面（`saveAndRestart` 第一版就栽在
     * 1500 字符窗口上，函数实际 2500+ 字符），取大了又会把相邻函数的代码算进来 —— 花括号配对没有这个两难。
     */
    private fun blockAfter(text: String, marker: String): String = TestSources.blockAfter(text, marker)

    @Test
    fun `保存并重启必须先落盘再杀进程`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "fun saveAndRestart")
        assertTrue(
            "saveAndRestart 必须调 restartImeProcess()：直接 postDelayed + killProcess " +
                "会在 IO 抖动时丢掉最后一批 apply()（重启后设置回退）",
            body.contains("restartImeProcess()"),
        )
        // 顺序也要钉住（BUG.md L-109）：重启函数内部必须先落盘再杀进程 ——
        // 只查「调了 restartImeProcess()」挡不住「把 flush 挪到 kill 之后」这种回退
        val restart = blockAfter(codeOf("SettingsActivity.kt"), "fun restartImeProcess")
        assertBefore(
            restart, "Prefs(this).flush()", "killProcess(",
            "主设置必须先落盘：`apply()` 是异步的，只靠延时 800ms 赌它写完，IO 压力大时会丢最后一批键",
        )
        assertBefore(
            restart, "ClipboardPrefs.of(this).flush()", "killProcess(",
            "剪贴板设置同样要先落盘再杀进程（否则重启后设置回退）",
        )
        // L-1420：false 只写日志还不够 —— 界面此时已宣告「正在重启」，照旧杀进程就是**确定性**
        // 地丢这批设置。两个 flush 的结果都必须收进变量、失败分支必须直接返回（不返回就落到 kill）
        assertTrue(
            "两个 flush 的结果都要收进变量（只在 if 里各调一次拿不到合并结论）",
            "val prefsFlushed = runCatching { Prefs(this).flush() }.getOrDefault(false)" in restart &&
                "val clipboardFlushed = runCatching { ClipboardPrefs.of(this).flush() }.getOrDefault(false)" in restart,
        )
        assertStatementLine(restart, "return", "落盘失败必须直接返回，否则会落到 killProcess")
        assertTrue("失败分支要有用户可见的回执（状态行 + Toast 同一个常量）", "TEXT_RESTART_FLUSH_FAILED" in restart)
        assertBefore(
            restart, "if (!prefsFlushed || !clipboardFlushed)", "killProcess(",
            "失败判据必须在杀进程之前",
        )
    }

    @Test
    fun `崩溃快照的原始中转件必须落在缓存目录且成对删除`() {
        val text = codeOf("Diagnostics.kt")
        // 原先这条只断言「表达式里有 cacheDir」——`cacheDir ?: dir`（退回日志目录）也满足它，
        // 于是守卫把缺陷一起钉住了（BUG.md L-1316）。改成：走缓存目录，或整次快照跳过
        assertTrue(
            "中转件是**未过滤** logcat 的唯一副本，不能落在会被导出诊断包整体打包的日志目录",
            text.contains("val cache = cacheDir ?: run {"),
        )
        assertFalse("兜底不得退回日志目录（宁可整次快照不产出）", text.contains("cacheDir ?: dir"))
        assertTrue("中转件成功 / 失败都要删（finally 里收口）", text.contains("runCatching { raw.delete() }"))
    }

    @Test
    fun `七天清理必须覆盖导出用的 device-info`() {
        // 锚点取真正干活的那半：清理现在分「试锁的外壳 + 干活的 Locked」两层（BUG.md L-174）
        val body = blockAfter(codeOf("Diagnostics.kt"), "private fun cleanupOldLogsLocked")
        assertTrue(
            "cleanupOldLogs 要认 DEVICE_INFO_FILE：进程被杀留下的该文件不匹配 jinn- / logcat- 前缀，" +
                "会永久留在日志目录并混进之后每次导出包",
            body.contains("DEVICE_INFO_FILE"),
        )
    }

    /**
     * `BUG.md` L-209：凭据页的落盘触发点。
     *
     * 只有「失焦 / onPause」两条时，用户在输入框里粘贴完凭据、焦点还没移开就被系统回收
     * （后台清理、内存压力），这一段配置根本不会写盘，而页面提示写着「自动保存」。
     */
    @Test
    fun `凭据页的输入停顿即落盘`() {
        val text = codeOf("TranslationSettingsActivity.kt")
        assertTrue("凭据字段要挂输入监听", "field.addTextChangedListener(autosave)" in text)
        assertTrue("停顿后落盘的防抖任务", "postDelayed(autosaveCredentials, CREDENTIAL_AUTOSAVE_DEBOUNCE_MS)" in text)
        assertTrue(
            "导入进行中不回写（与 onPause 同一守卫）",
            "if (!ConfigBackupManager.importing) saveCredentials(notify = false)" in text,
        )
        assertTrue(
            "onPause 落盘后撤掉排队的任务（避免重复空转）",
            "credentialAutosave.removeCallbacks(autosaveCredentials)" in text,
        )
    }

    @Test
    fun `切符号层与数字层必须先收起剪贴板面板`() {
        val text = codeOf("PinyinKeyboardView.kt")
        for (anchor in listOf("btnSymbol.setOnClickListener", "btnDigit.setOnClickListener")) {
            // 行锚定（BUG.md L-109）：只查名字时，把调用包进 `if (false)` / 挪进别的分支仍然绿
            assertStatementLine(
                blockAfter(text, anchor),
                "hidePanelForLayerSwitch()",
                "$anchor 必须调 hidePanelForLayerSwitch()：两层的键都在字母区里，" +
                    "面板显示时字母区整体 GONE ⇒ 切了层既看不到键、红色「返回」也被顶替，用户没有退出口",
            )
        }
    }

    @Test
    fun `进符号层必须清掉未上屏的拼音`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "btnSymbol.setOnClickListener")
        // 收紧到**符号层分支体内**、且要求调用**独占一行**（BUG.md L-109）：
        // 原来只查「处理体里出现这个名字」—— 把调用挪进 `if (false)` 或别的分支时仍然绿。
        // 分支体按花括号配对取（两端都要真锚点）：裸 `substringAfter("if (layer == LAYER_SYMBOL)")`
        // 在条件被改成 `!=` / 整段搬走时返回整个处理体，而 `clearComposingState()` 在别处还有若干处
        // ⇒ 进符号层不清也算绿（2026-10-23 变异实证）。锚点带 `{`，免得撞上上面那行
        // `layer = if (layer == LAYER_SYMBOL) LAYER_LETTER else LAYER_SYMBOL`。
        val branch = blockAfter(body, "if (layer == LAYER_SYMBOL) {")
        assertStatementLine(
            branch,
            "clearComposingState()",
            "进符号层要调 clearComposingState()：该层不显示拼音条与候选，残留 composing 会「看不见却仍生效」" +
                "（退格空删、收起键盘把上一次首候选上屏）",
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
    fun `语言下拉未被用户操作时不得改写导入值`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "private fun readLanguage")
        assertTrue(
            "readLanguage 必须先判下拉的「用户操作」探针：导入的备份可能带本版不认识的取值，" +
                "下拉退回显示第 0 项，读回它等于把导入值静默改写（其他四个下拉同款闸门，见 L-821）",
            body.contains("if (!spinnerLanguage.userInteracted) return prefs.language"),
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
                "重建会连上一次的拖选一起丢，而 IME 侧的 Anchor/Focus 要到下次弹键盘才复位（状态分裂一整个会话）。" +
                "galleryActive 同理（图库面板展开期间重建会丢目录与选中项）—— 新增面板时同步这一行",
            // MEM-05 起搜索面板可为空（首次打开才创建）：判据读的是「存在且活跃」
            text.contains("clipboardActive || galleryActive || isSearchPanelActive() || directionPanelVisible"),
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
        // 顺序（BUG.md L-109）：flush 必须排在 postDelayed(killProcess) 之前 ——
        // 「函数体里出现 flushUserFrequency()」挡不住「挪到 kill 之后」这种回退
        assertBefore(
            body, "flushUserFrequency()", "killProcess(",
            "killProcess 是 SIGKILL、不会走 onDestroy：不先同步 flush，防抖窗口（2s）内的学习会随重启丢掉，" +
                "而这条路径恰恰是应用自己主动发起的",
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
        // 位置判据（不是「窗口里出现这个名字」）：两行调换后必须变红，见 BUG.md L-109
        assertBefore(
            window,
            "commitComposing()",
            "onCommitText(digit)",
            "数字是即时上屏、候选要等收起键盘才提交：不先 commitComposing 就会「数字在前、候选在后」" +
                "（真机实测：打拼音 → 切数字层点 1 → 收起键盘，正文是「1你」）",
        )
    }

    @Test
    fun `标点上屏前必须先提交拼音`() {
        val text = codeOf("PinyinKeyboardView.kt")
        for ((anchor, field) in listOf(
            "btnComma.setOnClickListener" to "punctCommaCn",
            "btnPeriod.setOnClickListener" to "punctPeriodCn",
        )) {
            val body = blockAfter(text, anchor)
            // 只钉 else 分支：搜索态必须先 appendSearch，而 `commitComposing()` 在搜索态走
            // `clearComposingState()` —— 无条件调用会把用户正在打的搜索词清掉（L-1443）。
            // 锚点缺失（分支被改成无花括号 / 条件取反）时 blockAfter 当场抛错，不会静默放宽。
            val commit = blockAfter(body, "} else {")
            assertBefore(
                commit,
                "commitComposing()",
                "onCommitText(text)",
                "$field：标点是即时上屏，不先提交拼音就会插到拼音前面" +
                    "（真机实测：打 bi → 按「。」→ 空格取首候选，正文「。比」，L-1443）",
            )
        }
    }

    @Test
    fun `候选复位必须先 scrollTo 再 fling`() {
        val body = blockAfter(codeOf("CandidateScrollView.kt"), "fun resetScrollX()")
        // 顺序不能反：fling() 的起点取调用时的滚动位，先 fling 会把复位前的偏移记成终值。
        assertBefore(
            body,
            "scrollTo(0, 0)",
            "fling(0)",
            "复位必须先落位再中止甩动：反序时速度为 0 仍可能派发一帧，弹回旧位（L-1440）",
        )
        // ⚠ 本钉只钉源码形态：OverScroller 的帧行为在 JVM 上测不到，真机判据是
        // 「甩动后换一批候选，候选行首个暗像素回到 51」（旧形态恒 28，字形被左边缘裁切）。
        assertFalse(
            "换批 / 展开态切换必须走 resetScrollX()：裸 scrollTo 不中止甩动，下一帧就被覆盖（L-1440）",
            codeOf("PinyinKeyboardView.kt").contains("candidateScroll.scrollTo(0, 0)"),
        )
    }

    @Test
    fun `窗口隐藏时必须提交未上屏拼音`() {
        val body = blockAfter(codeOf("JinnIme.kt"), "override fun onWindowHidden()")
        // 切走输入法 / 宿主被遮挡时 onFinishInputView 不一定派发，未上屏拼音只存在视图里 ⇒ 净丢失
        // （真机实证：打 bi 后切成别的输入法，宿主框始终为空，进程并未重启；L-1445）。
        // commitComposing 内部是 `currentInputConnection?.commitText` + runCatching：连接已断则静默
        // 跳过，不会崩；composing 已清时空转，不会重复上屏。
        assertStatementLine(
            body,
            "pinyinKeyboard?.commitComposing()",
            "窗口隐藏是最后一个还能提交 composing 的时机，漏了就是净丢失（L-1445）",
        )
    }

    @Test
    fun `无发送方的配置广播不得再注册`() {
        // 全仓从无发送方，注释却按「有广播」写了两处 KDoc ⇒ 读代码会顺着推出一条不可达路径
        // （L-1447：本轮审计第一件事就是被它骗）。删除后不得再以任何形态复活。
        assertFalse(
            "整机配置广播没有发送方，注册它反而对外暴露一条可被第三方触发的 action（L-1447）",
            TestSources.rawSourceOfShortName("JinnIme.kt").contains("ACTION_CONFIG_UPDATED"),
        )
    }

    @Test
    fun `剪贴板导出必须防并发位移`() {
        val body = blockAfter(codeOf("ConfigBackupManager.kt"), "private fun collectClipboard(context: Context)")
        // 导出侧的分页游标是 SQL OFFSET：导出期间用户复制一条（插头 + 裁尾）会让后续页位移、
        // 静默跳过若干行（既没导出也不进 dropped），所以必须比对并重来（面板侧已有同样防护）。
        // 判据取「条数 + 最大 id + 密文总长」的指纹，而不是只看条数（BUG.md L-1085）：库到上限时
        // 插入与淘汰成对发生、总数不变，只看条数会把「变了」判成「没变」，重来永不触发。
        assertTrue(
            "导出必须按指纹比对并重来",
            body.contains("db.exportStamp() == before") && body.contains("EXPORT_CLIP_ATTEMPTS"),
        )
        val stamp = TestSources.window(codeOf("ClipboardDb.kt"), "fun exportStamp(): String =", "\"0:0:0\"")
        assertTrue("指纹必须含最大 id（插入会抬它）", "MAX(id)" in stamp)
        assertTrue("指纹必须含密文总长（删除 / 改写会动它）", "SUM(LENGTH(encrypted_content))" in stamp)
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
        // MEM-11 起 `fetchToFile` 是**文件级**函数（线程不能碰实例成员），客户端与目录由调用方传入：
        // 「复用同一个客户端」因此看两处 —— 下载线程从共享单例取（`val client = httpClient`），
        // 且函数内用参数调 newCall。整条链路仍不得自建 OkHttpClient。
        assertTrue("必须用共享单例", body.contains("client.newCall"))
        val dictThread = blockAfter(codeOf("DictManagerActivity.kt"), "private fun download(")
        assertTrue("下载线程必须取自共享单例", dictThread.contains("val client = httpClient"))
        assertFalse("下载链路不得自建 OkHttpClient", dictThread.contains("OkHttpClient.Builder()"))
    }

    /**
     * 内存与热路径两处「别再白付」不得退回去（2026-10-09，批 6 的 MEM-11 / MEM-28）。
     *
     * MEM-11：下载线程原先经 `filesDir` / 成员函数 / `runOnUiThread` 隐式持住整个 Activity，
     *   用户「点下载就返回」时旧实例连同视图树要等 callTimeout（600s）才可回收；现在只持
     *   应用上下文 / 静态客户端 / 目标目录，收尾走静态 Handler + 弱引用判活。
     * MEM-28：剪贴板重复复制（最常见路径）不再白跑 11 条正则分类与来源应用 IPC。
     */
    @Test
    fun `下载线程不得持 Activity 且复制热路径不得重复白付`() {
        val dict = codeOf("DictManagerActivity.kt")
        assertTrue(
            "下载线程不得直接摸 filesDir（那会把 Activity 一起捕获）",
            dict.contains("val dir = File(app.filesDir, PinyinEngine.OPT_DICT_DIR)"),
        )
        assertTrue(
            "收尾必须走静态 Handler + 弱引用判活",
            dict.contains("Handler(Looper.getMainLooper()).post {") && dict.contains("activePage?.get()"),
        )
        assertTrue(
            "下载实现必须是文件级函数（成员函数会被线程捕获）",
            dict.contains("private fun fetchToFile(client: okhttp3.OkHttpClient, dir: File,"),
        )

        val ctl = codeOf("ClipboardController.kt")
        assertTrue(
            "分类必须下沉到 upsert 的插入分支（复制路径不得再调 classify）",
            "ClipboardClassifier.classify" !in ctl,
        )
        assertTrue("来源应用名必须有进程内 memo", "appNameMemo[pkg]?.let { return it }" in ctl)
        assertTrue("限长检查必须先做字符数快筛", "text.length.toLong() * 3L <= limit.toLong()" in ctl)

        val db = codeOf("ClipboardDb.kt")
        assertTrue(
            "插入分支才算分类",
            "val resolvedCategory = category ?: ClipboardClassifier.classify(content)" in db,
        )
        assertTrue(
            "判重分支不得覆盖已有分类",
            "if (category != null) values.put(\"category\", category)" in db,
        )
    }

    /**
     * 按键链上两处「别再白付」不得退回去（2026-10-09，批 1 的 MEM-19 / MEM-20）。
     *
     * MEM-19：`filterRareChars` 一趟走完（原先是「扫一遍找坏词 + filter 再判一遍」）；
     *   前缀键从最长起逐级削尾（原先每级 `take(k).joinToString("")` 重新拼一遍）；
     *   出口只留一种容器（原先还要再建一个 LinkedHashSet 把四份合进去）。
     *   缓冲区必须是 `query` 的**局部量** —— `PinyinEngine` 是 object，类级可变容器会被
     *   JVM 用例的连续查询串状态（分析回执订正第 2 条）。
     * MEM-20：双拼码表按 5+5 bit 打包成 Int 下标直查（原先每两键 `substring` 出一个 String 当键）。
     *
     * 产物等价由 PinyinEngineTest / ShuangpinTest / 候选顺序类用例与临时分配基准（记录见回执）把守。
     */
    @Test
    fun `按键链不得退回逐级重拼与二次扫描`() {
        val engine = codeOf("PinyinEngine.kt")
        val filter = blockAfter(engine, "private fun filterRareChars(")
        assertTrue("filterRareChars 必须单趟", "var kept: ArrayList<String>? = null" in filter)
        assertTrue("filterRareChars 不得再先扫一遍找坏词", "needFilter" !in filter)

        val query = blockAfter(engine, "fun query(input: String): Result {")
        assertTrue("前缀键必须增量拼装（削尾）", "keyBuf.setLength(keyBuf.length - syllables[k - 1].length)" in query)
        assertTrue("不得再逐级 joinToString 重拼", "joinToString(\"\")" !in query)
        assertTrue("缓冲区必须是 query 的局部量", "val keyBuf = StringBuilder(raw.length)" in query)
        assertTrue("出口只留一种容器", "val result = words" in query)

        assertTrue("两个调用点都走 codeOf", "table.codeOf(raw[i], raw[i + 1])" in engine)
        val toQuanpin = blockAfter(engine, "fun toQuanpin(input: String, scheme: ShuangpinScheme): String {")
        assertTrue("toQuanpin 不得再 substring 出码表键", "raw.substring(i, i + 2)" !in toQuanpin)
        val display = blockAfter(engine, "fun displayQuanpin(input: String, scheme: ShuangpinScheme): String {")
        assertTrue("displayQuanpin 不得再 substring 出码表键", "raw.substring(i, i + 2)" !in display)
        val table = codeOf("ShuangpinSchemes.kt")
        assertTrue("双拼查表必须走打包下标（在表文件里）", "fun codeOf(a: Char, b: Char): String?" in table)
        assertTrue("打包算式必须存在", "fun packedIndexOf(a: Char, b: Char): Int" in table)
        assertTrue("键位数据本身不得改动（仍是同一张 codes）", "val codes: Map<String, String>" in table)
    }

    /**
     * 联动参数的一致性与导入口径不得退回去（2026-10-09，批 6 的 BUG-12 / BUG-35）。
     *
     * BUG-12：八个联动参数（条数 / 体积 / 收藏两条 / 页宽 / 搜索上限 / 单条上限 / 历史页宽）
     *   原先「逐键 apply + 逐键读」——导入进行中读到的会是半新半旧的组合，用户随后一保存就把
     *   旧值写回。现在读侧走 `snapshot()` 一把锁取整组、写侧（applyDraft / importFromBackup）
     *   同一把锁 + 单次 commit。
     * BUG-35：收藏符号的长度闸必须比**归一后**的长度 —— 运行期调用方传的就是归一串，
     *   导入侧却比备份原文，同一份收藏会「运行期能存、导入被整键拒收」。
     */
    @Test
    fun `联动参数必须整组一致且导入口径与运行期相同`() {
        val cp = codeOf("ClipboardPrefs.kt")
        assertTrue("必须有一致快照入口", "internal fun snapshot(): Snapshot = synchronized(LOCK) {" in cp)
        assertTrue("快照类型必须与页面草稿同组", "internal data class Snapshot(" in cp)
        assertTrue("保存路径必须进同一把锁", "return synchronized(LOCK) {" in cp)
        assertTrue(
            "导入路径必须整组单次落盘",
            "internal fun importFromBackup(values: Map<String, ConfigBackup.BackupValue>): Int = synchronized(LOCK) {" in cp,
        )
        val importBlock = blockAfter(cp, "internal fun importFromBackup(values: Map<String, ConfigBackup.BackupValue>): Int = synchronized(LOCK) {")
        assertTrue("导入不得再逐键走 setter", "= it; applied++" !in importBlock)
        assertTrue("导入必须写进同一个 editor", "val editor = sp.edit()" in importBlock)
        assertTrue("导入必须单次 commit", "editor.commit()" in importBlock)

        val page = codeOf("ClipboardCustomizeActivity.kt")
        assertTrue("定制页必须取一致快照", "val s = prefs.snapshot()" in page)
        assertTrue("定制页不得再逐键读", "maxItems = prefs.maxItems" !in page)

        val prefs = codeOf("Prefs.kt")
        assertTrue(
            "收藏符号长度闸必须比归一后的串",
            "val normalized = FavoriteSymbols.serialize(FavoriteSymbols.parse(raw))" in prefs &&
                "if (normalized.length > MAX_FAVORITE_SYMBOLS_CHARS)" in prefs,
        )
    }
    /**
     * 导入计数与导出回执不得退回去（2026-10-09，批 6 的 BUG-13）。
     *
     * ① 计数：导入报「剪贴板 +N」原先取 `db.count()` 的前后差，而采集常开 —— 导入期间用户的每次
     *   复制都会改动总数，差值把并发条目算进本次导入（数字虚高）。现在只按**本次插入的 id** 复查。
     * ② 回执：导出期间库一直变、重试耗尽时，原先只留一条 W 而界面照报「全部导出成功」——
     *   包是完整可导入的，但那是**假成功**（少了几条却不说）。现在一路上报并由界面如实补一句。
     */
    @Test
    fun `导入计数按本次插入复查且假成功必须上报`() {
        val mgr = codeOf("ConfigBackupManager.kt")
        val importBlock = blockAfter(mgr, "if (failedStage == null && includeClipboard &&")
        assertTrue(
            "导入计数必须按本次插入的 id 复查",
            "val restored = db.insertRestoredBatch(" in importBlock &&
                "val insertedIds = restored.insertedIds" in importBlock &&
                "val present = db.countPresent(insertedIds)" in importBlock,
        )
        assertTrue("导入不得再用前后总数差", "db.count() - before" !in importBlock)
        assertTrue(
            "跳过数必须由三项相加（结构上不超包内条数）",
            "clipSkipped = dedupeSkipped + insertFailed + trimmedAway" in importBlock,
        )

        val collect = blockAfter(mgr, "private fun collectClipboard(context: Context): ClipPayload {")
        assertTrue(
            "重试耗尽必须标记快照不稳定",
            "ClipPayload(last?.text ?: \"\", last?.dropped ?: 0, unstable = true)" in collect,
        )
        assertTrue("回执必须带上该标记", "clip?.unstable == true" in mgr)
        val db = codeOf("ClipboardDb.kt")
        assertTrue("按 id 复查的实现必须在", "fun countPresent(ids: List<Long>): Int" in db)
        assertTrue(
            "界面必须如实写出不稳定一句",
            "outcome.clipUnstable" in codeOf("SettingsActivity.kt") &&
                "本次快照可能不完整" in codeOf("SettingsActivity.kt"),
        )
    }
    /**
     * 被「用户抢先输入」挤掉的草稿必须留档而不是删掉（2026-10-09，批 7 的 BUG-30）。
     *
     * 「恢复不覆盖编辑框里已有输入」是既有口径（L-1191），但旧长文不该就此消失：旧稿改名进留档槽
     * （只留最新一份），下次打开本页且没有更新的草稿时会被铺回来；用户随后写的新草稿一旦落盘，
     * 留档槽即被清掉（那时它已被更新的内容取代）。
     */
    @Test
    fun `被挤掉的草稿必须留档而不是删掉`() {
        val src = codeOf("CustomDictEditActivity.kt")
        assertTrue("必须有留档槽常量", "private const val DRAFT_FILE_ARCHIVE" in src)
        val discard = blockAfter(src, "private fun discardPendingDraft() {")
        assertTrue("丢弃必须改成改名留档", "src.renameTo(dst)" in discard)
        assertTrue("留档必须记事（可诊断）", "已留档" in discard)
        assertFalse("不得再直接删草稿本体", "File(cacheDir, file).delete()" in discard)
        assertTrue("读盘时必须回看留档槽", "val archived = File(cacheDir, DRAFT_FILE_ARCHIVE)" in src)
        assertTrue("铺留档稿要有单独分支", "if (fromArchive) {" in src)
        assertTrue("新草稿落盘后必须清留档槽", "File(cacheDir, DRAFT_FILE_ARCHIVE).delete()" in src)
    }
    /**
     * 面透明度「整树重扫」的早退判据必须是联合的（2026-10-09，批 3 的 MEM-26）。
     *
     * 判据只比档位时，「仅换肤」（档位没动、底色由 applySkin 改了）会命中早退：键面与面板的
     * alpha 不再刷新，键面还带着上一套皮肤的底 —— 没有日志、没有异常，是**静默**外观错误。
     * 身份键由「原始档位 + 皮肤 id」拼出（不取格式化后的文案：文案将来改成档名会撞键）。
     */
    @Test
    fun `透明度重扫早退必须带上皮肤身份`() {
        val kb = codeOf("PinyinKeyboardView.kt")
        assertTrue("必须有身份键字段", "private var lastTransparencyKey = \"\"" in kb)
        assertTrue("判据必须由档位 + 皮肤拼出", "KeyTransparency.appearanceKey(percent, skin.id)" in kb)
        assertTrue("必须有早退", "if (appearanceKey == lastTransparencyKey) return" in kb)
        assertTrue("扫描完成后必须置位", "lastTransparencyKey = appearanceKey" in kb)
        assertTrue(
            "身份键必须是纯函数（可单测）",
            "fun appearanceKey(percent: Int, skinId: String): String" in codeOf("KeyTransparency.kt"),
        )
    }
    /**
     * 收藏符号的清洗口径必须覆盖 Unicode 空白与零宽字符（2026-10-09，批 7 的 BUG-36）。
     *
     * `String.trim()` 只去 ASCII ≤ 0x20：不换行空格 U+00A0、表意空格 U+3000、零宽空格 U+200B、
     * BOM U+FEFF 都会留下 —— 它们在 26 键键面上看不见却占格子，用户看到「空槽键」、按下没反应、
     * 也找不到那个字符去删。三条入口（页面输入 / append / parse）必须共用同一份清洗。
     */
    @Test
    fun `收藏符号清洗必须共用同一口径`() {
        val fs = codeOf("FavoriteSymbols.kt")
        assertTrue("必须有清洗入口", "fun clean(text: String): String" in fs)
        // 字符类由运行时 Character.getType 派生（位图），不再写正则；C 与 Z 的八个类别必须齐全
        val classes = listOf(
            "Character.CONTROL", "Character.FORMAT", "Character.SURROGATE",
            "Character.PRIVATE_USE", "Character.UNASSIGNED",
            "Character.SPACE_SEPARATOR", "Character.LINE_SEPARATOR", "Character.PARAGRAPH_SEPARATOR",
        )
        assertTrue("字符类必须覆盖 C 与 Z 两类：缺 ${classes.filterNot { it in fs }}", classes.all { it in fs })
        assertTrue("必须有单遍判定入口", "fun hasCleanable(text: String): Boolean" in fs)
        assertTrue("成对代理必须按码点判（Cs 只认孤立代理）", "Character.toCodePoint" in fs)
        val append = blockAfter(fs, "fun append(pages: List<List<String>>, item: String)")
        assertTrue("追加必须走清洗", "val s = clean(item)" in append)
        assertTrue("追加不得退回 trim", "item.trim()" !in append)
        assertTrue("解析必须走清洗", "getOrNull()?.let { clean(it) }" in fs)
        assertTrue("解析不得退回 trim 直读", "getOrNull()?.trim()" !in fs)
        val act = codeOf("FavoriteSymbolsActivity.kt")
        assertTrue("页面入口必须走清洗", "val item = FavoriteSymbols.clean(raw)" in act)
        assertTrue("页面入口不得再自写字符清洗", "raw.trim().replace" !in act)
        // 限长按码点数（BUG-37）：`String.length` 是码元数，一个 astral 字符算 2 ⇒ 5 个 emoji 被误拒。
        // 写入与解析必须同口径，否则解析会把刚收下的项再滤掉。
        assertTrue(
            "限长必须走码点判据",
            "fun withinLimit(item: String, max: Int = MAX_CHARS): Boolean" in fs,
        )
        assertTrue("追加与解析都要用它", Regex("""withinLimit\(""").findAll(fs).count() >= 3)
        assertTrue("不得再按码元数判限长", "s.length <= MAX_CHARS" !in fs && "s.length > MAX_CHARS" !in fs)
        // 关键写同步落盘（BUG-34）：编辑页是收藏的唯一写入口，`apply()` 在进程被回收时会丢最后一步。
        assertTrue("收藏写入口必须同步落盘", "prefs.flush()" in act)
        assertTrue("落盘失败要留痕", "收藏落盘失败" in act)
        // 注：BUG-32 是**纯注释**改动（修订号实例内作用域），而 `TestSources.codeOf` 会剥掉注释，
        // 因此它没有源码钉 —— 想钉住就得改成代码形态（例如另存时间戳字段），那时再补。
    }

    /**
     * 两项外观需求（2026-10-09 用户指定）：
     *
     * ① 候选栏工具栏**不再有第二行小字**（原先主标 13f + 小字 9f：「控制 / 文本 / 编辑 / 网络 / 快贴」）。
     *   小字整条去掉，但 hint 仍进 `contentDescription` —— 读屏用户照样听到「方向 控制」这种完整
     *   语义，视觉上只剩大字；三条就地刷新（方向 / 图库 / 翻译）也不再摸 `getChildAt(1)`。
     * ② 三行 28 键里「大写」「删除」改成**全宽 1/10**（= 第一行十个字母的宽度）：weight 由 1.4 改
     *   0.875 —— 七个字母各 1 ⇒ 行内总权重 8.75，两键各占 0.875 / 8.75 = 10%。
     */
    /**
     * 26 键大写字母的字形尺寸（2026-10-09 指定）：
     *
     * 铺满分支原先**只按键高定字号**：键高 70dp ⇒ 字号 49dp，而 26 键每键宽 36dp ——
     * 字母比键还宽，直观就是「太大、相邻字母相接」。现在补上与居中 / 长文本分支同源的
     * 宽度收束（[PinyinKey.FULL_TEXT_FIT_RATIO]），基准由视图下发（最宽字母 W + q 键宽）
     * 整页同号。实机同屏对拍：Q 排字形 122px → 75px、A 排 104px → 71px，其余各行不变。
     *
     * 「大写」「删除」两键的图标同时放大一倍（padding 13dp → 8dp：fitCenter 下图标被
     * 「键框减内缩」框死，13dp 只剩约 10dp）。同屏实测两键图标尺寸不变、图标盒 10dp → 20dp。
     */
    /**
     * 键盘外观页的控件文案与结构（2026-10-09 指定）：
     *
     * 每一项只留主标题 —— 三个开关的第二行说明、明暗切换的说明行、定时提示行整条删除；
     * 滑杆标题与拖动条间距 0（同在一行、紧贴）；皮肤两段改成主标题「亮色 / 暗色」+ 状态行
     * （生效那段写「已激活，唤起键盘可预览」，另一段写「未激活，不可预览」）；键高定义域收到 40~90dp。
     */
    /**
    一条后台任务的生命周期（2026-10-09 审查）：
     *
     * ① 剪贴板监听一次事件只读一次 `primaryClip`：读三次之间内容可能已被改写，而入库用的
     *   是后读到的值，「历史里最新一条」与触发事件的那一份对不上。
     * ② 键盘那条 500ms 的铺底诊断必须能在 detach 时撤掉：已附着时 `postDelayed` 的任务
     *   在 detach 后照样跑，那时整棵树尺寸归零，扫出来的是「一条视图都没有」的假结论。
     */
    @Test
    fun `后台任务不再多次读剪贴板且延迟诊断可撤`() {
        val clip = codeOf("ClipboardController.kt")
        assertTrue("必须只读一次并存进局部量", "val clip = clipboard.primaryClip" in clip)
        assertFalse(
            "不得再先判空一次、之后又读一次",
            "if (clipboard.primaryClip == null) {" in clip,
        )

        val kb = codeOf("PinyinKeyboardView.kt")
        assertTrue("延迟诊断必须提成字段", "postDelayed(backgroundsLogTask, 500L)" in kb)
        assertTrue(
            "detach 必须撤掉它",
            "removeCallbacks(backgroundsLogTask)" in kb,
        )
    }

    /**
    外部交互面的五处收口（2026-10-09 审查第二轮）：
     *
     * 自动重连不得撤销语音空闲关闭（撤了就永久保活、息屏后 ping 继续唤醒）；识别文本走
     * JSON null 安全取值（`optString` 对显式 null 返回字面量 "null"，会被直接 commit 进输入框）；
     * 词库下载改名之前必须 fsync（摘要是在页缓存上算的）；更新检查不得用无上限的 readLine；
     * 翻译 POST 不得静默重发（服务端可能已计费）。
     */
    /**
     * 配置存储面四处收口（2026-10-09 审查第三轮）：
     *
     * ① 导出只在**用户真的改过**时才写外观 / 图库 / 定时这些「不平凡默认」的键 —— 无条件导出会把
     *   当前默认固化成显式值，将来调默认对这些用户永久失效；
     * ② 外观量程的 KDoc 不再复述数字（改指常量，本仓有过注释与常量分叉的先例）；
     * ③ 滑杆上界必须可达（步进除不尽时取上整，显示侧进度取整）；
     * ④ 默认端点的注释不得再指向与常量不符的地址。
     *
     * ⚠ 2026-10-26 起量程 / 旧名的黑名单**扫全部主源码**（`src/main` 下 102 个 `.kt` + 54 个 `.xml`），
     * 不再只扫 `Prefs.kt`：`PinyinKey` 的外观 KDoc 就带着旧「圆角 0~24dp」活了一整年（真值 48 / 16）。
     * 用例名沿用历史叫法 —— 台账回执（`ledger/history.md`）按名引用，改名会造出一条新的悬空引用。
     */
    @Test
    fun `配置存储面的四处收口不得回退`() {
        val prefs = codeOf("Prefs.kt")
        for (key in listOf(
            "KEY_KEY_CORNER_DP", "KEY_KEY_GAP_DP", "KEY_KEY_HEIGHT_DP",
            "KEY_CANDIDATE_SPACING_DP", "KEY_CANDIDATE_TEXT_SP",
            "KEY_GALLERY_COLUMNS", "KEY_GALLERY_CELL_HEIGHT_DP",
            "KEY_THEME_LIGHT_AT", "KEY_THEME_DARK_AT",
        )) {
            assertTrue("导出必须只在改过时才写 $key（否则默认被固化）", "if (sp.contains($key)) put($key," in prefs)
        }

        val prefsRaw = TestSources.rawSource(
            "src/main/java/com/jinn/inputmethod/Prefs.kt",
            "app/src/main/java/com/jinn/inputmethod/Prefs.kt",
        )
        for (old in listOf("0~24dp", "0~8dp", "40~80dp")) {
            assertFalse("外观量程注释不得再复述旧数字：$old", old in prefsRaw)
        }
        // 2026-10-26（第 52 轮）：上面那条只扫 Prefs.kt，而 PinyinKey 的外观 KDoc 一直写着
        // 旧的「圆角 0~24dp / 间隙 0~8dp」（真值 48 / 16，2026-10-09 起）—— 单文件白名单就是漏项。
        // 改为扫**全部**主源码（含资源 XML，注释也在内）：旧的量程、从不存在过的常量名、
        // 已过期的按钮计数都不许回来。
        val mainDir = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("找不到主源码目录（当前工作目录=${File("").absolutePath}）")
        val staleText = listOf(
            "0~24dp", "0~8dp", "40~80dp", "TEXT_LONGPRESS_LOWER_TITLE", "翻页那 7 个按钮",
            // 2026-10-26 第 52 轮补：同批纠正的另外三处旧口径 —— 两个从没实现过的拖选状态名，
            // 以及图库标题行「7 个按钮」（真值 6 = 5 个小键 + 自动返回，`ae83706^` 可数）。
            "NORMAL_CURSOR", "TEXT_SELECTION_ACTIVE", "原先标题和 7 个按钮",
        )
        val stale = ArrayList<String>()
        mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .forEach { f ->
                val text = f.readText()
                for (s in staleText) if (s in text) stale.add("${f.name}: $s")
            }
        assertTrue("主源码里不得再出现旧的量程 / 名字 / 计数：$stale", stale.isEmpty())
        val appearRaw = TestSources.rawSource(
            "src/main/java/com/jinn/inputmethod/KeyAppearance.kt",
            "app/src/main/java/com/jinn/inputmethod/KeyAppearance.kt",
        )
        assertFalse("键高上界注释不得再写 120dp", "上界：120dp" in appearRaw)
        assertFalse("键高进度注释不得再写 20 格", "20 格）" in appearRaw)

        // 2026-10-27（第 60 轮）：算式搬进 `SliderGrid`（自定义页与单测共用一份），锚点跟着换；
        // 原先把算式钉在页面源码里，页面与单测各写一份算术，改了其中一处另一处照样绿。
        val grid = codeOf("SliderGrid.kt")
        assertTrue("滑杆上界必须可达（取上整）", "(((max - min) + step - 1) / step)" in grid)
        assertTrue("显示侧进度必须取整", "Math.round((value - min).toFloat() / step)" in grid)
        val page = codeOf("ClipboardCustomizeActivity.kt")
        assertTrue(
            "滑杆算式必须走 SliderGrid（页面里不许再重写一份）",
            "SliderGrid.progressMax(min, max, step)" in page &&
                "SliderGrid.valueAt(progress, min, max, step)" in page &&
                "SliderGrid.progressFor(value, min, max, step)" in page,
        )

        assertFalse(
            "默认端点注释不得再指向与常量不符的地址",
            "默认 Base URL（OpenAI 官方）" in TestSources.rawSource(
                "src/main/java/com/jinn/inputmethod/OpenAiTranslator.kt",
                "app/src/main/java/com/jinn/inputmethod/OpenAiTranslator.kt",
            ),
        )
    }

    @Test
    fun `外部交互面的五处收口不得回退`() {
        val asr = codeOf("AsrClient.kt")
        assertTrue("只有用户发起才撤销空闲关闭", "if (userInitiated) cancelIdleClose()" in asr)
        assertTrue("自动重连不算用户发起", "connect(force = true, userInitiated = false)" in asr)
        assertTrue("网络恢复重连同样不算用户发起", "asr?.connect(userInitiated = false)" in codeOf("JinnIme.kt"))

        val proto = codeOf("Protocol.kt")
        assertTrue("识别文本必须走 JSON null 安全取值", "jsonText(json, \"text\").orEmpty()" in proto)
        assertFalse("不得再用 optString 取识别文本", "json.optString(\"text\", \"\")" in proto)

        assertTrue("下载落盘必须在改名之前 fsync", "out.fd.sync()" in codeOf("DictManagerActivity.kt"))

        val up = codeOf("UpdateChecker.kt")
        assertTrue("响应必须按块读", "reader.read(buf)" in up)
        assertFalse("不得再用无换行的 readLine", "reader.readLine()" in up)

        assertTrue("翻译 POST 不得静默重发", "retryOnConnectionFailure(false)" in codeOf("TranslationClient.kt"))
    }

    /**
    键盘外观页的控件文案与结构（2026-10-09 指定）：
     *
     * 每一项只留主标题 —— 三个开关的第二行说明、明暗切换的说明行、定时提示行整条删除；
     * 滑杆标题与拖动条间距 0（同在一行、紧贴）；皮肤两段改成主标题「亮色 / 暗色」+ 状态行
     * （生效那段写「已激活，唤起键盘可预览」，另一段写「未激活，不可预览」）；键高定义域收到 40~90dp。
     */
    @Test
    fun `外观页控件只留主标题且键高上界收到九十`() {
        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_key_appearance.xml",
            "app/src/main/res/layout/activity_key_appearance.xml",
        )
        for (gone in listOf(
            "text_letter_upper_desc", "text_key_hint_desc", "text_pinyin_quanpin_desc",
            "text_theme_desc", "text_theme_schedule_hint",
        )) {
            assertFalse("第二行说明必须整条删除：$gone", gone in layout)
        }
        assertEquals(
            "八个滑杆与标题的间距必须都是 0dp（2026-10-21 删掉「数字字号」滑杆，九 → 八）",
            8,
            "android:layout_marginStart=\"0dp\"".toRegex().findAll(layout).count(),
        )
        assertFalse(
            "滑杆与标题之间不得再留 10dp 间距",
            "android:layout_marginStart=\"10dp\"\n                    android:layout_weight=\"1\"" in layout,
        )
        assertTrue(
            "皮肤两段必须各有状态行",
            "text_skin_light_desc" in layout && "text_skin_dark_desc" in layout,
        )

        val kt = codeOf("KeyAppearanceActivity.kt")
        for (title in listOf(
            "const val TEXT_CORNER_TITLE = \"按钮圆角\"",
            "const val TEXT_GAP_TITLE = \"按钮间隙\"",
            "const val TEXT_KEY_HEIGHT_TITLE = \"按钮键高\"",
            "const val TEXT_SPACING_TITLE = \"候选字距\"",
            "const val TEXT_TEXT_SIZE_TITLE = \"候选字号\"",
            "const val TEXT_TRANSPARENCY_TITLE = \"面板透明\"",
            "const val TEXT_PINYIN_LINE_GAP_TITLE = \"拼音占高\"",
            "const val TEXT_LETTER_UPPER_TITLE = \"拼音键盘大写\"",
            "const val TEXT_KEY_HINT_TITLE = \"键盘内显韵母\"",
            "const val TEXT_QUANPIN_TITLE = \"双拼候选全音\"",
            "const val TEXT_CANDIDATE_ROWS_TITLE = \"候选词的行数\"",
            "const val TEXT_THEME_TITLE = \"界面明暗切换\"",
        )) {
            assertTrue("主标题必须与需求逐字一致：$title", title in kt)
        }
        assertTrue(
            "皮肤两段的状态行文案",
            "const val TEXT_SKIN_ACTIVE_DESC = \"已激活，唤起键盘可预览\"" in kt &&
                "const val TEXT_SKIN_INACTIVE_DESC = \"未激活，不可预览\"" in kt,
        )
        assertFalse("旧的段标题拼接必须删除", "skinGroupTitle" in kt)
        assertTrue("皮肤状态行必须由共用函数下发", "private fun refreshSkinGroupState(" in kt)
        assertEquals("键高上界必须是 90dp", 90f, KeyAppearance.MAX_KEY_HEIGHT_DP, 0f)
    }

    /**
    26 键大写字母的字形尺寸（2026-10-09 指定）：
     *
     * 铺满分支原先**只按键高定字号**：键高 70dp ⇒ 字号 49dp，而 26 键每键宽 36dp ——
     * 字母比键还宽，直观就是「太大、相邻字母相接」。现在补上与居中 / 长文本分支同源的
     * 宽度收束（[PinyinKey.FULL_TEXT_FIT_RATIO]），基准由视图下发（最宽字母 W + q 键宽）
     * 整页同号。实机同屏对拍：Q 排字形 122px → 75px、A 排 104px → 71px，其余各行不变。
     *
     * 「大写」「删除」两键的图标同时放大一倍（padding 13dp → 8dp：fitCenter 下图标被
     * 「键框减内缩」框死，13dp 只剩约 10dp）。同屏实测两键图标尺寸不变、图标盒 10dp → 20dp。
     */
    @Test
    fun `26键大写字母按宽度收束且两键图标放大一倍`() {
        val key = codeOf("PinyinKey.kt")
        assertTrue("铺满分支必须做宽度收束", "(refWidth - inset * 2f) * FULL_TEXT_FIT_RATIO" in key)
        assertTrue("收束比例必须是有名常量", "const val FULL_TEXT_FIT_RATIO = 0.8f" in key)
        assertFalse(
            "不得退回「只按键高定字号」的老写法（高键上字母比键还宽）",
            "textPaint.textSize = (h * FULL_TEXT_RATIO + glyphSizeDeltaPx)" in key,
        )

        val kb = codeOf("PinyinKeyboardView.kt")
        assertTrue("基准字形必须是常量且取最宽字母", "const val LETTER_FIT_SAMPLE = \"W\"" in kb)
        assertTrue(
            "字母层必须改用字母基准，不能沿用符号层那一对",
            "key.uniformMeasureText = if (layer == LAYER_LETTER) uniformLetterMeasure else uniformSymbolText" in kb,
        )
        assertTrue(
            "26 键不显韵母档必须收到 −10sp",
            "showUpper -> -10f" in kb && "showUpper && showHint -> -3f" in kb,
        )

        val xml = TestSources.rawSource(
            "src/main/res/layout/keyboard_pinyin.xml",
            "app/src/main/res/layout/keyboard_pinyin.xml",
        )
        assertEquals(
            "大写与删除两键内缩必须是 8dp（13dp 只剩约 10dp 图标）",
            2,
            Regex("padding=\"8dp\"").findAll(xml).count(),
        )
    }


    @Test
    fun `工具栏不再渲染第二行小字且大写删除为全宽十分之一`() {
        val kb = codeOf("PinyinKeyboardView.kt")
        assertTrue("工具键不得再渲染小字那一行", "text = hint" !in kb)
        assertTrue("方向键就地刷新不得再改小字", "hintView?.text" !in kb)
        assertTrue("图库键就地刷新不得再收小字", "hintView?.visibility" !in kb)
        // 注：`getChildAt(1)` 不再作为反钉 —— 空格键的「主文字 + 顶部小字」仍是两段式（1250 行附近），
        // 全文级反钉会误伤；工具键那两处由上面的 `hintView` 反钉覆盖。
        assertTrue(
            "小字语义必须留在无障碍名里",
            "contentDescription = \"\$label \$hint\"" in kb &&
                "\"返回 退出控制\"" in kb && "\"图库 快贴\"" in kb,
        )
        val xml = TestSources.rawSource(
            "src/main/res/layout/keyboard_pinyin.xml",
            "app/src/main/res/layout/keyboard_pinyin.xml",
        )
        assertEquals(
            "「大写」「删除」两键必须是 0.875 权重（= 全宽 1/10）",
            2,
            Regex("layout_weight=\"0.875\"").findAll(xml).count(),
        )
        // 注：底部那一行的「回车」键仍是 1.4 权重（与本次需求无关、保持原样），
        // 因此这里**不做**「全文不得出现 1.4」的反钉 —— 那会误伤它。
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
            // 两个重建分支的搬运 SELECT 已合并到 rebuildKeepingData 一处（BUG.md L-1421），
            // 所以判据从「两处各有一句」变成「建 SELECT 的那一处必须 COALESCE」；
            // 「合并后仍有分支写死清单」由 ClipboardDbMigrationTest 钉（搬回写死 ⇒ 那条红）。
            "重建表搬 content_hash 时必须 COALESCE(content_hash, '')：新表该列是 NOT NULL，" +
                "旧表若留 NULL 会让 INSERT 失败 —— 升级抛异常就是整库打不开",
            "COALESCE(content_hash, '')" in text,
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
    fun `候选渲染分页不得回退`() {
        val src = codeOf("PinyinKeyboardView.kt")
        val initial = Regex("const val INITIAL_RENDERED_CANDIDATES = (\\d+)")
            .find(src)?.groupValues?.get(1)?.toInt()
        val step = Regex("const val RENDERED_CANDIDATES_STEP = (\\d+)")
            .find(src)?.groupValues?.get(1)?.toInt()
        assertTrue("首屏条数常量必须存在", initial != null)
        assertTrue("追加步长常量必须存在", step != null)
        assertTrue(
            "首屏不得少于 24（当前 $initial）：单音节候选可达 60 条，首屏太短时用户要多滑好几下才够到靠后的候选",
            initial!! >= 24,
        )
        assertTrue("追加步长必须为正（当前 $step）", step!! >= 1)
        // 增量追加按「绝对序号」两两成列 ⇒ 起点必须恒偶，否则双行档两排会错开一列。
        // 现在靠 [appendCandidateViews] 入口把起点对齐到偶数，不再依赖两个常量恰好都是偶数
        // （改参即静默劣化的面）。
        assertTrue(
            "增量追加必须自己把起点对齐到偶数",
            "var i = from - (from and 1)" in src,
        )
        assertTrue(
            "渲染路径必须按窗口条数截断候选（subList(0, candidateRenderLimit)）",
            "subList(0, candidateRenderLimit)" in src,
        )
        assertTrue(
            "必须有「横滑到末尾追加更多候选」的滚动监听",
            "setOnScrollChangeListener" in src && "maybeAppendMoreCandidates" in src,
        )
        assertTrue(
            "换候选必须把横滑位置复位（否则新候选停在旧滚动位，第一候选被推到屏外）",
            "candidateScroll.resetScrollX()" in src,
        )
        assertTrue(
            "内容没撑满视口时也要继续追加（否则剩余候选滚不到、永久不可达）",
            "maxScroll > 0 &&" in src,
        )
        assertTrue(
            "追加渲染前必须确认容器仍是候选形态（否则会把候选追加到功能面板 / 符号 / 密码条上）",
            "if (candidateRenderKey == null) return" in src,
        )
        assertTrue(
            "清空候选容器必须一并复位分页状态（args 置空），避免已排队的追加回调用到陈旧入参",
            "candidateRenderArgs = null" in src,
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

    // ── 键盘按键的无障碍激活入口 ──────────────────────────

    @Test
    fun `字母键与分号键必须有无障碍激活入口`() {
        // 字母键的触摸被外层 OnTouchListener 消费，触摸路径的 performClick 不产生输入；
        // 辅助服务的「双击」只能经 performAccessibilityAction 进来。两条路径互斥，
        // 不会「触摸输入一次、辅助服务再输入一次」——若改用 setOnClickListener 就会。
        val key = codeOf("PinyinKey.kt")
        assertTrue(
            "PinyinKey 必须覆写 performAccessibilityAction 并调用 onActivate",
            blockAfter(key, "override fun performAccessibilityAction(").contains("activate()"),
        )
        assertTrue(
            "节点必须挂上 ACTION_CLICK，辅助服务才会走上面的回调（用 AccessibilityAction 重载：" +
                "`addAction(Int)` 已弃用，退回旧写法会先在这里变红）",
            blockAfter(key, "override fun onInitializeAccessibilityNodeInfo(")
                .contains("addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)"),
        )
        assertTrue(
            "label 变化必须同步 contentDescription：辅助服务靠它朗读键面文字（字母层读字母、符号层读符号）",
            blockAfter(key, "var label: String = \"\"").contains("contentDescription = value"),
        )
        val view = codeOf("PinyinKeyboardView.kt")
        // 判据取**整个 onActivate 块**而不是整行字符串：块内允许再挂别的东西
        // （2026-10-05 起补了一次敲击反馈 —— 辅助服务用户同样该听到按键音），
        // 只要它仍把输入接回触摸同款函数，无障碍就不会失灵。
        assertTrue(
            "字母键必须把 onActivate 接回触摸同款输入函数（符号层复用同一批键，也走这里）",
            blockAfter(view, "key.onActivate =").contains("onLetterPressed(c)"),
        )
        // 与字母键同款：判据取**整个 onActivate 块**。块内允许再挂别的东西
        // （2026-10-06 起补了一次敲击反馈 —— 辅助服务用户同样该听到按键音），
        // 只要仍把输入接回同一个上屏函数，无障碍就不会失灵。
        assertTrue(
            "分号键必须把 onActivate 接到提取出的上屏函数",
            blockAfter(view, "keySemicolon.onActivate =").contains("onSemicolonPressed()"),
        )
    }

    @Test
    fun `退格的无障碍激活必须走 delegate 且不挂监听`() {
        val view = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "退格的 ACTION_CLICK 必须调 deleteOne()，与触摸路径同一删除语义",
            blockAfter(view, "btnBackspace.accessibilityDelegate").contains("deleteOne()"),
        )
        // 挂 OnClickListener 会被触摸路径的 performClick 一并触发（DOWN 已删一个，
        // 抬手再删一个 ⇒ 一次触摸删两个），所以这条断言盯住「没有监听」这个前提
        assertFalse(
            "退格不得挂 OnClickListener：触摸与辅助服务各删一次会变成删两个",
            view.contains("btnBackspace.setOnClickListener"),
        )
    }

    @Test
    fun `跨输入框必须收起方向面板`() {
        // 方向面板是视图内临时态，而视图实例跨输入框复用：configure（每次聚焦都会调用）
        // 不复位就会让新输入框以展开态弹出（字母区被面板接管）。且**必须**走 hideDirectionPanel()：
        // 它负责恢复字母三行、清 stale 面板并回传 onSelectionModeChanged(false)；
        // 裸置 `directionPanelVisible = false` 会让字母区永久失活（无触摸），见 BUG.md L-29。
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "fun configure(scheme: ShuangpinScheme")
        assertTrue(
            "configure 必须收起方向面板（会话边界复位）",
            body.contains("if (directionPanelVisible) hideDirectionPanel()"),
        )
        assertFalse(
            "不得裸置 directionPanelVisible = false（字母区会永久失活）",
            body.contains("directionPanelVisible = false"),
        )
    }

    @Test
    fun `生僻字页每次回前台都要重画且显示生效值`() {
        // 只在 onCreate 画一次时，从设置页导入配置后返回本页（仍在栈中）会停在做旧快照上，
        // 再拨任一档就把**另一档**按旧显示写回（等于回滚导入结果）；档 3 的显示值还要与
        // 引擎 setRareTiers 的 `tier2 && tier3` 掩码同口径，否则非法组合会「界面勾着、实际没生效」。
        val src = codeOf("RareCharsActivity.kt")
        assertTrue(
            "必须在 onStart 里渲染（每次回前台重读 prefs）",
            blockAfter(src, "override fun onStart()").contains("renderRows()"),
        )
        assertFalse(
            "渲染不得留在 onCreate（只画一次就会读到过期状态）",
            blockAfter(src, "override fun onCreate(").contains("renderRows()"),
        )
        assertTrue(
            "档 3 的显示值必须与档 2 取与（与引擎掩码同口径）",
            src.contains("prefs.rareTier3 && prefs.rareTier2"),
        )
    }

    @Test
    fun `剪贴板首屏止损后空态必须能继续查找`() {
        // 首屏填页止损（8 页）后，若最新约 400 行连续解不出，列表为空、不可滚动 ⇒ 更早那批
        // **能解密**的历史仍然翻不到（预取闸门要求 totalItemCount > 0）。修法：空态可点，
        // 沿游标续扫。见 BUG.md L-76。
        val src = codeOf("ClipboardPanelView.kt")
        // 形状钉改为「先判止损态、再续扫」这条语义：入口补敲击反馈后，中间多了一句反馈调用，
        // 判据不再绑死单行写法（中间允许零或一句其它语句），但「只在止损态才动作」必须留着。
        assertTrue(
            "空态必须挂点击入口，且只在止损态才动作并沿游标续扫（见 scanStoppedEarly）",
            Regex(
                """setOnClickListener \{\s*if \(!scanStoppedEarly\) return@setOnClickListener\s+(?:[^\n]*\n\s*)?continueScan\(\)""",
            ).containsMatchIn(src),
        )
        assertTrue(
            "止损态必须能从填页返回值推出（空结果 + 游标未到末尾）",
            src.contains("scanStoppedEarly = currentItems.isEmpty() && nextPageOffset < total"),
        )
        assertTrue(
            "续扫必须从既有游标开始，不能从 0 重扫（白解密又可能重复显示）",
            "fillFirstPage(total, ClipboardPrefs.of(context).panelPageItems, startOffset = offset)" in src,
        )
        assertTrue(
            "止损态要有自己的空态文案（不能与「整表都读不出」混用一句）",
            src.contains("TEXT_EMPTY_UNREADABLE_MORE"),
        )
    }

    @Test
    fun `长按数字必须进入密码模式并可退出`() {
        // 密码模式＝英文小写 26 键 + 候选栏 0-9（数字与字母同屏）。入口只认长按「数字」，
        // 退出口是同一个键的点击（此时标签已变成「退出」）—— 三条链路缺一不可，见更新日志。
        val code = codeOf("PinyinKeyboardView.kt")
        val longPress = blockAfter(code, "btnDigit.setOnLongClickListener")
        assertTrue("长按「数字」必须进入密码模式", longPress.contains("enterPasswordPad()"))
        assertTrue("长按时若已在密码模式，必须什么都不做（不能把点击一并吃掉成误退出）",
            longPress.contains("if (passwordPad)"))
        val click = blockAfter(code, "btnDigit.setOnClickListener")
        assertTrue("密码模式里点这个键必须退出", click.contains("if (passwordPad)"))
        assertTrue("退出口必须调用 exitPasswordPad()", click.contains("exitPasswordPad()"))
        assertTrue("键面必须变成「退出」标签（否则用户找不到出口）",
            code.contains("passwordPad -> TEXT_PASSWORD_EXIT"))
        assertTrue("候选栏必须渲染数字条", code.contains("renderPasswordDigits()"))
        assertTrue("数字键必须能上屏", code.contains("commitPasswordDigit("))
        assertTrue("密码模式内中英切换必须无效（否则会变成「数字条 + 拼音」并存）",
            blockAfter(code, "btnLang.setOnClickListener").contains("if (passwordPad)"))
        assertTrue("密码模式内进符号层必须无效（否则数字条被符号分组标签顶掉，模式前提「26 键 + 数字条」破掉）",
            blockAfter(code, "btnSymbol.setOnClickListener").contains("if (passwordPad)"))
    }

    @Test
    fun `密码模式数字条顺序与退出口醒目标记`() {
        // 用户 2026-09-28 指定：数字条 1 最左、0 最右、中间 2~9（1 与 0 是密码里最常点的两个键）；
        // 「退出」必须加粗 + 提示红（与候选栏的红色「返回」同一套令牌），否则用户找不到出口。
        assertEquals(
            "数字条顺序是契约：1 最左、0 最右、中间 2~9",
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            PASSWORD_DIGIT_ORDER,
        )
        val code = codeOf("PinyinKeyboardView.kt")
        // 用 window 取两端锚点之间的正文（两端都断言存在）而不是 substringAfter：
        // 后者锚点缺失时返回整串，接 .contains(...) 会被别处满足 ⇒ 判据恒绿（BUG.md L-1145）
        // 退出口的样式落在 `if (passwordPad) { … } else { … }` 里；文件里有多处同名 if，
        // 且 codeOf 已剥注释 ⇒ 只能用代码本身作锚点。
        // 起点取「加粗」那句自身（indexOf 定位、断言非负），终点取紧邻的 applyPunctuationLabel。
        // 不用 TestSources.window：它返回两锚点**之间**的正文、不含起始锚点，而这里要判的就是起始那句。
        val boldAt = code.indexOf("btnDigit.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)")
        check(boldAt >= 0) { "源码里找不到退出口的加粗语句 —— 改名/重构后请同步本用例" }
        val endAt = code.indexOf("applyPunctuationLabel(", boldAt)
        check(endAt > boldAt) { "找不到 applyPunctuationLabel 作结束锚点 —— 结构变了？" }
        val labelPath = code.substring(boldAt, endAt)

        assertTrue("退出口必须加粗", "btnDigit.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)" in labelPath)
        assertTrue(
            "退出口必须用提示红（与候选栏「返回」同一令牌）",
            "skinToken(skin.hintRed, R.color.kb_key_hint_red)" in labelPath,
        )
        assertTrue(
            "退出后必须还原成常规字重（否则「数字」会一直粗着）",
            "btnDigit.setTypeface(android.graphics.Typeface.DEFAULT)" in labelPath,
        )
        val skinBody = TestSources.blockAfter(code, "private fun applySkinToTexts")
        assertTrue(
            "换肤路径也必须保持退出口的红色（否则换肤会把它刷回普通色）",
            "if (passwordPad) btnDigit.setTextColor" in skinBody,
        )
    }

    @Test
    fun `数字层的「数字」键必须变成红色粗体的「返回」`() {
        // 用户 2026-09-29 指定：点底部「数字」进入数字层后，该键位显示「返回」红字粗体
        // （与符号层、候选栏的红色「返回」同一枚标签 / 同一套令牌；原先写的是「ABC」，
        // 同一枚键在两个层里同一功能两种说法）。
        val code = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "数字层必须显示「返回」（不得退回「ABC」）",
            code.contains("layer == LAYER_DIGIT -> backLabel()"),
        )
        assertFalse(
            "「ABC」标签已删（同一功能不再有两种说法），代码里不得再引用 key_abc",
            code.contains("R.string.key_abc"),
        )
        assertTrue(
            "符号层必须与数字层共用同一枚「返回」标签（否则两处样式会各自漂移）",
            TestSources.window(code, "btnSymbol.text = if (layer == LAYER_SYMBOL)", "} else {").contains("backLabel()"),
        )
        // 表达式函数体没有独立的花括号块，用「到下一个函数为止」切片（比 blockAfter 的锚点更贴切）
        val helper = blockAfter(code, "private fun backLabel")
        assertTrue("返回标签必须加粗", helper.contains("StyleSpan(android.graphics.Typeface.BOLD)"))
        assertTrue(
            "返回标签必须用提示红（与候选栏「返回」同一令牌）",
            helper.contains("skinToken(skin.hintRed, R.color.kb_key_hint_red)"),
        )
    }

    @Test
    fun `密码模式必须在会话边界退出`() {
        // 视图实例跨输入框复用：不复位，新输入框会以「英文小写 + 数字条」弹出（键盘变身）。
        // 语言必须由 configure 的 english 参数决定 ⇒ 边界上只复原层与大写（restoreLanguage = false）。
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "fun configure(scheme: ShuangpinScheme")
        assertTrue("configure 必须退出密码模式", body.contains("if (passwordPad) exitPasswordPad(restoreLanguage = false)"))
        assertTrue("退出时必须复原进入前的状态（层 / 语言 / 大写）",
            blockAfter(codeOf("PinyinKeyboardView.kt"), "private fun exitPasswordPad").contains("passwordPadRestore"))
    }

    @Test
    fun `逗号句号必须是全角半角加粗且字号加三`() {
        // 用户 2026-09-28 指定：拼音态全角（，。）、英文态半角（,.），居中 + 粗体 + 字号 +3。
        val code = codeOf("PinyinKeyboardView.kt")
        assertTrue("两个键的标签必须走用户可配置的取值（中文 / 英文两套，见 Prefs 的标点四键）",
            code.contains("applyPunctuationLabel(btnComma, cn = prefs.punctCommaCn, en = prefs.punctCommaEn)") &&
                code.contains("applyPunctuationLabel(btnPeriod, cn = prefs.punctPeriodCn, en = prefs.punctPeriodEn)"))
        // 全角 / 半角默认值仍必须钉住：它是自定义功能的底座，改了它等于改了所有「没设过」的用户的行为
        val punctDefaults = codeOf("PunctOutput.kt")
        for (d in listOf(
            "DEFAULT_COMMA_CN = \"，\"", "DEFAULT_COMMA_EN = \",\"",
            "DEFAULT_PERIOD_CN = \"。\"", "DEFAULT_PERIOD_EN = \".\"",
        )) {
            assertTrue("PunctOutput 缺默认值 $d", d in punctDefaults)
        }
        val helper = blockAfter(code, "private fun applyPunctuationLabel")
        for (need in listOf("gravity = android.view.Gravity.CENTER", "Typeface.DEFAULT_BOLD",
                            "includeFontPadding = false",
                            "setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, PUNCTUATION_TEXT_DP)")) {
            assertTrue("标点样式缺一项：$need", helper.contains(need))
        }
        val size = Regex("""const val PUNCTUATION_TEXT_DP = ([0-9.]+)f""").find(code)?.groupValues?.get(1)?.toFloat()
        assertTrue("PUNCTUATION_TEXT_DP 必须声明为数字", size != null)
        // 「基础字号 + 3」的机械核对：基础取**键盘底行**同类键的字号（L-1457 起底行统一按 dp 派生，
        // 不再借用页面样式 SettingsButton 的 13sp —— 那一档是给正文页面的，仍随 fontScale）
        val keyboard = listOf(
            File("app/src/main/res/layout/keyboard_pinyin.xml"),
            File("src/main/res/layout/keyboard_pinyin.xml"),
        ).first { it.isFile }.readText()
        val symbolTag = TestSources.window(keyboard, "@+id/key_symbol", "/>")
        val baseDp = Regex("""android:textSize="([0-9.]+)dp"""").find(symbolTag)?.groupValues?.get(1)?.toFloat()
        assertEquals("底行基础字号变了：标点字号必须跟着改（基础 + 3）",
            3f, size!! - (baseDp ?: 0f), 0.01f)
    }

    @Test
    fun `搜索判停令牌必须跨线程可见`() {
        // 分块搜索在后台线程的循环体里读 refreshToken 判停，主线程写它（++refreshToken）：
        // 缺 @Volatile 时 JMM 允许后台线程一直读到陈旧值 ⇒ 旧任务不会提前收工、白扫余下窗口
        // （每块 50 行还要解密），并占着单线程 BackgroundIo 让后续任务排队。见 BUG.md L-79。
        val src = codeOf("SearchPanelView.kt")
        val decl = Regex("""@Volatile\s+private var refreshToken""")
        assertTrue("@Volatile 必须紧贴在 refreshToken 声明上（判停令牌跨线程读写）", decl.containsMatchIn(src))
        assertFalse(
            "不得留一个没有 @Volatile 的 refreshToken 声明",
            Regex("""(?<!@Volatile\s)\bprivate var refreshToken""").containsMatchIn(src.replace(decl, "")),
        )
    }

    /**
     * 降级安装必须仍能打开剪贴板库（BUG.md L-101）。
     *
     * 未覆写 `onDowngrade` 会命中平台默认实现：直接抛 `Can't downgrade database from version X to Y`
     * ⇒ 库永远打不开，面板 / 搜索 / 自动记录全部失效；而调用点大多有兜底（BackgroundIo 包装 +
     * 面板 runCatching）⇒ **不崩、只留日志**，比崩溃更难自查。
     *
     * 钉住三件事：覆写在位、只回写 `user_version`（不建表 / 不删数据）、留 W 日志。
     */
    @Test
    fun `降级安装必须放行并留日志`() {
        val code = codeOf("ClipboardDb.kt")
        assertTrue("ClipboardDb 必须覆写 onDowngrade（缺了会命中平台默认实现直接抛）",
            "override fun onDowngrade(" in code)
        val body = blockAfter(code, "override fun onDowngrade(")
        assertTrue("降级必须把 user_version 回写成本版本认识的值", "db.version = newVersion" in body)
        assertTrue("降级必须留 W 日志，否则只剩「功能静默失效」这一条线索", "Diagnostics.w(" in body)
        assertFalse("降级不得删表 / 清库（回滚排查时用户的历史仍在其中）", "DROP TABLE" in body)
    }

    /**
     * 系统标记为敏感的剪贴板内容不得入库（BUG.md L-95）。
     *
     * Android 13+ 对「密码框复制 / 安全来源」的内容打 `EXTRA_IS_SENSITIVE`；
     * 入库 = 面板可列、搜索可命中、一键粘贴（加密存储也不改变这个结论）。
     * 与输入框侧已实现的 `InputFieldPrivacy`（密码框不学词频）同一口径。
     */
    @Test
    fun `敏感剪贴板条目不得入库`() {
        val code = codeOf("ClipboardController.kt")
        val body = blockAfter(code, "private fun extractAndSave(")
        assertTrue("extractAndSave 必须读系统敏感标记", "EXTRA_IS_SENSITIVE" in body)
        assertTrue(
            "敏感标记的读取必须在 API 33+ 守卫内（常量编译期可用，该标记运行时才存在）",
            "SDK_INT >= 33" in body || "SDK_INT >= Build.VERSION_CODES.TIRAMISU" in body,
        )
        val skipAt = body.indexOf("EXTRA_IS_SENSITIVE")
        val saveAt = body.indexOf("ClipboardStore.save(")
        assertTrue("敏感判断必须发生在写库之前（否则先入库再判断没有意义）", skipAt >= 0 && saveAt > skipAt)
    }

    /**
     * 视图重建后必须重放「敏感框不学习」抑制（BUG.md L-96）。
     *
     * `PinyinKeyboardView.suppressLearning` 是**视图上的字段**（默认 false），写入点只有
     * `onStartInputView` 一处；而换肤 / 符号布局变更会换掉整个视图 ⇒ 不重放则本会话余下时间
     * 在密码框里选候选会被学进词频。顺序必须是「先换视图、再重放」。
     */
    @Test
    fun `视图重建后必须重放敏感框抑制`() {
        val code = codeOf("JinnIme.kt")
        assertTrue("找不到 recreateKeyboardView（结构变了？）", "private fun recreateKeyboardView()" in code)
        assertTrue("抑制判定必须记在 IME 侧（供重建重放）", "suppressLearningForSession" in code)
        val body = blockAfter(code, "private fun recreateKeyboardView()")
        val swapAt = body.indexOf("setInputView(onCreateInputView())")
        val replayAt = body.indexOf("setSuppressLearning(")
        assertTrue("重建函数里没有换视图调用（结构变了？）", swapAt >= 0)
        assertTrue("视图重建后必须重放 setSuppressLearning（新视图默认 false）", replayAt > swapAt)
    }

    /**
     * 分页 / 去重排序必须带 tie-breaker（BUG.md L-97）。
     *
     * `created_at` 是毫秒时间戳，同毫秒的行构成**等值组**，而 SQLite 不承诺等值键之间的顺序
     * ⇒ 与 OFFSET 分页叠加时没有稳定序（同一行返回两次 / 一行从两页之间漏掉）。
     * 凡 `ORDER BY created_at DESC` 都必须补 `, id DESC`（id 自增唯一、与插入序一致）。
     */
    @Test
    fun `created_at 排序必须补 id tie-breaker`() {
        val code = codeOf("ClipboardDb.kt")
        val bare = Regex("""ORDER BY created_at DESC(?!,\s*id DESC)""").findAll(code).count()
        assertEquals("ClipboardDb 里有 $bare 处 `ORDER BY created_at DESC` 缺 `, id DESC`（等值组内序不定）", 0, bare)
    }

    /**
     * 存量分组重算线程必须降优先级（BUG.md L-103；AGENTS.md 约定「启动期任务不与词库加载抢 CPU」）。
     */
    @Test
    fun `存量重算线程必须降优先级`() {
        val code = codeOf("ClipboardController.kt")
        val body = blockAfter(code, "private fun reclassifyIfNeeded()")
        assertTrue("存量重算必须调用 reclassifyAll（结构变了？）", "db.reclassifyAll()" in body)
        assertTrue(
            "存量重算线程必须显式降优先级（它在 JinnIme.onCreate 路径上、与词库加载同时跑）",
            "setThreadPriority" in body && "THREAD_PRIORITY_BACKGROUND" in body,
        )
    }

    /**
     * 分页排序键必须与索引**同形**（BUG.md L-104）。
     *
     * L-97 给分页 SQL 补了 `, id DESC` 兜等值组的序；索引若还是只有 `created_at` 一列，
     * SQLite 每次分页都要 `USE TEMP B-TREE FOR LAST TERM OF ORDER BY`
     * （本机 9,999 行实测：深翻页 0.28 → 2.41ms）。已装机库同名索引已存在 ⇒ 必须配迁移分支。
     */
    @Test
    fun `分页排序键必须与索引同形`() {
        val code = codeOf("ClipboardDb.kt")
        // 版本号只判下限：写死 `= 6` 会让「再加一条迁移」时误红，而这里真正要守的是
        // 「每条库结构变更都配了迁移分支」，不是某个具体数字
        val version = Regex("""DB_VERSION = (\d+)""").find(code)?.groupValues?.get(1)?.toInt()
        assertTrue("DB_VERSION 必须 ≥ 6（v6 复合索引、v7 表达式索引各要一条迁移分支），当前 $version", (version ?: 0) >= 6)
        val composite = Regex("""CREATE INDEX idx_items_created ON \S+\(created_at DESC, id DESC\)""")
            .findAll(code).count()
        assertTrue("复合索引要同时出现在 onCreate 与迁移分支里（当前 $composite 处）", composite >= 2)
        assertTrue(
            "必须有 DROP + 重建的迁移分支（否则已装机库拿不到新索引）",
            "DROP INDEX IF EXISTS idx_items_created" in code,
        )
        // v7 的量长表达式索引：分页那条同样要守「全新安装 + 已装机库」两条路径
        assertTrue(
            "密文总长索引要同时覆盖 onCreate 与迁移分支（当前 ${Regex("bytesIndexSql\\(\\)").findAll(code).count()} 处）",
            Regex("""bytesIndexSql\(\)""").findAll(code).count() >= 3,   // 定义 + onCreate + 迁移分支
        )
        assertTrue(
            "索引必须建在 LENGTH(encrypted_content) 上（换成别的列就退回全表扫）",
            "CREATE INDEX IF NOT EXISTS idx_items_bytes ON \$TABLE_ITEMS(LENGTH(encrypted_content))" in code,
        )
    }

    /**
     * `fillFirstPage` 的可选参数必须排在最后（BUG.md L-86）。
     *
     * 两个可选参数同为 `Int`：`startOffset` 插在 `maxScanPages` 之前时，
     * `fillFirstPage(total, 50, 8) { … }` 会把 8 静默传成起点而不是页数上限。
     */
    @Test
    fun `fillFirstPage 的可选参数必须排在最后`() {
        // 取**签名**而不是函数体：blockAfter 是花括号平衡块提取器，
        // 而参数表里先出现的是 `(offset: Int, limit: Int) -> Page`（没有花括号），
        // 它返回的会是函数体，解析不到参数顺序。
        val sig = Regex("""fun fillFirstPage\([\s\S]*?\): Page \{""")
            .find(codeOf("ClipboardDb.kt"))?.value
        assertTrue("没解析出 fillFirstPage 的签名（结构变了？）", sig != null && sig.contains("maxScanPages"))
        val maxAt = sig!!.indexOf("maxScanPages: Int =")
        val startAt = sig.indexOf("startOffset: Int =")
        assertTrue("两个可选参数都要在签名里找到（结构变了？）", maxAt >= 0 && startAt >= 0)
        assertTrue("startOffset 必须排在 maxScanPages 之后（同为 Int，位置参数会静默错位）", startAt > maxAt)
        assertTrue(
            "续扫调用必须用命名参数 `startOffset =`（把「位置敏感」变成显式契约）",
            "startOffset = offset" in codeOf("ClipboardPanelView.kt"),
        )
    }

    /**
     * 空态的可点性必须与文案**同源**更新（BUG.md L-85）。
     *
     * `setOnClickListener` 会把视图永久标成可点击；另两种空态（真无历史 / 全坏读不出）下
     * 读屏仍播报「双击激活」而双击毫无反应 ⇒ `updateEmpty` 里要按 `scanStoppedEarly` 设 `isClickable`。
     */
    @Test
    fun `空态可点性必须与文案同源`() {
        val body = blockAfter(codeOf("ClipboardPanelView.kt"), "private fun updateEmpty()")
        assertTrue(
            "updateEmpty 必须按 scanStoppedEarly 设 isClickable（否则读屏把它当哑按钮）",
            "textEmpty.isClickable = scanStoppedEarly" in body,
        )
    }

    /**
     * 面板固定高度条必须给省略号（BUG.md L-98）。
     *
     * 操作条写死 36dp 高、文本用 SP（随系统字体放大）：不给 `maxLines = 1` + `ellipsize`
     * 时，大字号下文案会换行、多余的行被**硬裁**（不是省略号）。与 L-35 设置页开关同一口径。
     */
    @Test
    fun `面板固定高度条必须给省略号`() {
        val body = blockAfter(codeOf("ClipboardPanelView.kt"), "private fun tabButton(")
        assertTrue("tabButton 必须设 maxLines = 1", "maxLines = 1" in body)
        assertTrue("tabButton 必须设 ellipsize（固定高度 + SP 文本，大字号会硬裁）", "TruncateAt.END" in body)
    }

    /**
     * 「其他包」列表必须过滤成文件（BUG.md L-84）。
     *
     * `File.list()` 会把目录名一起交出去，而纯函数只按 `.xz` 后缀筛选 ⇒ 名为 `foo.xz` 的目录
     * 会被渲染成卡片，点删除还必然失败（`delete()` 对非空目录返回 false）。过滤留在调用侧。
     */
    @Test
    fun `其他包列表必须过滤成文件`() {
        // 用整文件断言而不是 blockAfter：该函数体里 `?.filter { … }` 的 lambda 才是首个花括号，
        // blockAfter 会返回 lambda 体（只含 `it.isFile`），拿不到 listFiles 调用。
        val code = codeOf("DictManagerActivity.kt")
        assertTrue(
            "必须用 listFiles() + isFile 过滤（File.list() 会把目录名交出去）",
            "dictDir().listFiles()?.filter { it.isFile }?.map { it.name }" in code,
        )
        assertFalse("不得再用 File.list() 交目录名", "OptionalDicts.unknownPackages(dictDir().list()" in code)
    }

    /**
     * 生成器的扩展 A 日志必须同时给两个口径（BUG.md L-82）。
     *
     * 「含**任意**扩展 A 字的词条」（实测 74）与「含**档外**扩展 A 字的词条」（KDoc 里的 37）
     * 是两个不同的数，措辞近似 ⇒ 只打一个时审核必然打架。日志还得标明是**本部分**口径
     * （它在按部分循环里打印，而 KDoc 引用的是四部分合计，直接相加会重复计字）。
     */
    @Test
    fun `扩展A日志必须同时给两个口径`() {
        val py = listOf(File("tools/dict_builder/build_dicts.py"), File("../tools/dict_builder/build_dicts.py"))
            .first { it.isFile }.readText()
        assertTrue(
            "扩展 A 日志必须同时出现「含任意」与「其中含档外」两个计数",
            "ext_a_words" in py && "ext_a_out_words" in py &&
                "含任意扩展 A" in py && "其中含档外" in py,
        )
    }

    /**
     * 可选词库加载线程只能持有**应用** Context（BUG.md L-22）。
     *
     * 线程活 21~34s（真机实测），期间 IME 服务可能被系统销毁重建 ⇒ 把 Service 的 Context
     * 一直挂在后台线程上是生命周期越界（短期强引用一个已销毁的服务）。
     */
    /**
     * WebView 类宿主不得被整体拒绝（2026-10-01 修复）。
     *
     * 精确选区只有 `getExtractedText` 一条路，而它对这些宿主恒返回 null。若翻译链路据此
     * 直接拒绝，浏览器搜索框里就是「点了没反应」；若哪次重构把那层 `getSelectedText` 兜底
     * 去掉、改回「null 即拒绝」，本用例会先红。
     */
    /**
     * 翻译的敏感闸不得复用学习判据（2026-10-01 真机修复）。
     *
     * `InputFieldPrivacy.suppressLearning` 把 `TYPE_NULL` / `NO_SUGGESTIONS` /
     * `IME_FLAG_NO_PERSONALIZED_LEARNING` 都算敏感，这是为本地词频学习定的（宁可多拦）；
     * 浏览器搜索框恰好带着这些标志，拿它拦翻译会让整类宿主「点了没反应」。
     */
    @Test
    fun `翻译的敏感闸不得复用学习判据`() {
        val src = codeOf("JinnIme.kt")
        assertTrue(
            "翻译必须用 blocksTranslation（密码框 + 显式声明不要个性化）",
            "InputFieldPrivacy.blocksTranslation(" in src,
        )
        val body = blockAfter(src, "private fun startTranslate(")
        assertFalse(
            "startTranslate 不得再用 suppressLearning 判敏感（浏览器搜索框会被误拦）",
            "suppressLearning(" in body,
        )
        assertTrue(
            "被拒时必须打出 inputType / imeOptions 的原始值，便于定位是哪一位命中的",
            "inputType=0x" in body && "imeOptions=0x" in body,
        )
    }

    @Test
    fun `翻译的选区判据必须带 getSelectedText 兜底`() {
        val src = codeOf("JinnIme.kt")
        assertTrue(
            "翻译必须有 canAppendTranslation（分层判据）",
            "private fun canAppendTranslation(" in src,
        )
        val body = blockAfter(src, "private fun canAppendTranslation(")
        assertTrue(
            "取不到精确选区时要用 getSelectedText 判断有没有选中内容",
            "getSelectedText(" in body,
        )
        assertTrue(
            "**问不出来**要给独立结论（L-262 fail-closed，L-284 起还要让调用方分文案），不能混进「无选区」",
            "return AppendCheck.HOST_UNREADABLE" in body,
        )
        assertTrue(
            "明确没有选中内容时放行（安全网交给提交时的原文比对）",
            "return AppendCheck.OK" in body,
        )
        // 发起处会把 `readSelectedText` 刚读到的选区一起传进来（L-315 少读一次），
        // 提交处没有现成的读数、照旧自己读 ⇒ 两种调用形态都算
        assertEquals(
            "发起前与提交前都要判选区（发起处 if、提交处 else if）",
            2,
            Regex("canAppendTranslation\\(connection[,)]").findAll(src).count(),
        )
        assertTrue(
            "选区模式必须在提交前校验选中内容未变，否则会用旧译文盖掉用户新选的内容",
            "nowSelected != snapshot.before" in src,
        )
        assertTrue(
            "选区模式必须原地替换、不得补前导换行（补了会把用户段落切碎）",
            "val submit = if (snapshot.replaceSelection) {" in src,
        )
    }

    /**
     * 本轮（2026-10-01）修复的形态守卫。
     *
     * 四条各自对应一条已入册缺陷，共同点是**回退后 JVM 单测依然全绿**：
     * L-261 选区超限时若照常替换会删掉选区后半段原文；L-262 宿主对 `getSelectedText` 抛异常时
     * 若放行，可能删掉用户选中的内容；L-263 选区探测不设 `hintMaxChars` 会让长文档每次回包整篇
     * 窗口；L-276 下拉闸门只认触摸时，读屏与外接键盘用户的选择永远不落盘。
     */
    @Test
    fun `本轮修复的形态不得回退`() {
        val src = codeOf("JinnIme.kt")
        // ⚠ 断「**调用形态**」而不是「标识符存在」（2026-10-03 修复 L-607）：这两个标识符各自
        // 还有一个常量声明，只断"出现"的话，把守卫条件改成永假（声明留着）断言仍会绿 ——
        // 现象就是「拒译逻辑事实上不可达，门禁却全绿」。加 `toast(` 前缀把断言钉在调用点上。
        assertTrue("选区超限必须在发请求之前拒绝（L-261）", "toast(TEXT_TRANSLATE_SELECTION_TOO_LONG)" in src)
        assertTrue("getSelectedText 抛异常时不能放行（L-262）", "getSelectedText 抛异常，无法确认选区" in src)
        assertTrue("选区探测必须设 hintMaxChars（L-263）", "hintMaxChars = TranslationText.MAX_READ_CHARS" in src)
        assertTrue("「光标前全部」读满窗口时必须拒绝（L-277）", "toast(TEXT_TRANSLATE_BEFORE_TOO_LONG)" in src)
        assertTrue("下拉的用户来源判据必须含焦点（L-276）", "isUserDriven" in codeOf("TranslationSettingsActivity.kt"))
        // L-820：服务方下拉的「界面跟随」不得再靠焦点 / 按压态猜 —— 那套判据在下拉弹窗路径上常常
        // 都是 false，结果是改了服务方凭据区块不跟随、重开页面才刷新。落盘闸门改由我们自己置位。
        run {
            val ts = codeOf("TranslationSettingsActivity.kt")
            val listener = blockAfter(ts, "spinnerProvider.onItemSelectedListener")
            assertTrue(
                "L-820 守卫缺失：服务方回调必须先按实际选中项切界面，且不得再判 isUserDriven",
                "applyProviderVisibility(pickedNow)" in listener && "isUserDriven" !in listener,
            )
            assertTrue(
                "L-820 守卫缺失：程序性回填必须自置闸门（loadValues 期间不落盘）",
                "suppressProviderPersist = true" in blockAfter(ts, "private fun loadValues()") &&
                    "suppressProviderPersist = false" in blockAfter(ts, "private fun loadValues()"),
            )
            assertTrue(
                "L-820：焦点猜测那套字段不许回来",
                "private var providerTouched" !in ts,
            )
        }
    }

    /**
     * 本批（2026-10-01）修复的形态守卫。
     *
     * 与上一批同样的道理：这八处**回退之后 JVM 单测依然全绿**，只有真机才能看出来，所以钉住形态。
     *
     * - L-293 `defaulted` 若退回「等于默认值 ⇒ 空串」，**打开一次 OpenAI 设置页就能让该家翻译整体
     *   不可用**，而且重填同一个 URL 也无效（下游 `joinUrl` 拿到空串是直接失败，不是回落默认）。
     * - L-288 Base URL 若退回 `trim()`，从网页复制时混进的 NBSP / 零宽字符会进 URL，两处消费点
     *   （摘要判据与建 provider）就会给出互相矛盾的结论。
     * - L-284 两种拒绝若合回一条文案，「宿主不支持选区检测」会被说成「请先取消选中的内容」。
     * - L-287 错误码若退回只认字符串的 `jsonText`，数字型错误码一律变成「服务异常」。
     * - L-289 清理失败若退回整批早退，一处抛错就让本轮谁都没清。
     * - L-285 / L-286 「`before` 取不到开头」的判据若退回「读满窗口」反推，`ALL` 范围与读取兜底
     *   路径都会漏拦。
     */
    @Test
    fun `本批修复的形态不得回退`() {
        val jinn = codeOf("JinnIme.kt")
        assertTrue(
            "选区判据必须三态 ——「宿主答不出」与「有选区」是两种要给不同提示的拒绝（L-284）",
            "private enum class AppendCheck { OK, SELECTION_PRESENT, HOST_UNREADABLE }" in jinn &&
                "AppendCheck.HOST_UNREADABLE" in jinn &&
                "TEXT_TRANSLATE_SELECTION_UNREADABLE" in jinn,
        )
        assertTrue(
            "「取不到文首」的判据必须同时覆盖 BEFORE_ALL 与 ALL（L-285）",
            "val needsDocStart = scope == TranslationScope.BEFORE_ALL || scope == TranslationScope.ALL" in jinn,
        )
        assertTrue(
            "「这段是不是从文首开始」必须由读取路径给出，不能靠长度反推（L-286 / L-565）" +
                "；短返回时还必须取证（宿主可能少给）",
            "private class BeforeText(val text: String, val fromDocStart: Boolean)" in jinn &&
                // 取满 ⇒ 明确「不是文首」（L-286）
                "if (s.length >= limit) return@runCatching BeforeText(s, false)" in jinn &&
                // 取不满 ⇒ 付一次取证再判（L-565；此前直接 `s.length < limit` 会把宿主少给当成到文首）
                "beforeStartsAtDocStart(connection, limit)" in jinn &&
                "extracted.startOffset == 0" in jinn &&
                "BeforeText(text.substring(0, endRel.coerceIn(0, text.length)), startOffset == 0)" in jinn,
        )

        assertTrue(
            "Base URL 必须与另外三个路径键同口径清洗（L-288）",
            "set(value) = putDefaulted(KEY_OPENAI_BASE_URL, value, OpenAiTranslator.DEFAULT_BASE_URL)" in
                codeOf("Prefs.kt"),
        )
        assertTrue(
            "OpenAI 错误码要认数字型（L-287）",
            "jsonCode(error, \"code\")" in codeOf("OpenAiTranslator.kt"),
        )
        assertTrue(
            "PARAM 文案要覆盖模型名 / 路径这类真因（L-290）",
            "模型名 / 路径 / 原文长度" in codeOf("Translation.kt"),
        )
        assertTrue(
            "凭据清理失败只影响标记，不得整批早退（L-289）；水位取失败同样不标记（L-258）",
            "if (failed || cleaned < 0 || maxIdUncertain || keptUncertain) return removed" in
                codeOf("CredentialTrace.kt"),
        )
        // L-592（2026-10-04）：收藏条目让删除恒为 0，而「0」的两种含义必须分开 —— 否则水位照推、
        // 该凭据永不重扫，Key 明文长期留在面板并随备份明文外发，日志却只说「无需删除」。
        run {
            val trace = codeOf("CredentialTrace.kt")
            assertTrue(
                "L-592 守卫缺失：必须问一次「有多少条因收藏被保留」并记 W",
                "favoriteKeptCounts(" in trace && "因收藏被保留" in trace,
            )
            assertTrue(
                "L-592 守卫缺失：被收藏保住的目标不得推进水位（删不掉就不算清过）",
                "for (v in targets) if (v !in kept) purged[v] = maxId" in trace,
            )
            assertTrue(
                "L-592 守卫缺失：收藏保留计数失败同样算「没得出结论」，不得标记水位",
                "maxIdUncertain || keptUncertain" in trace,
            )
            assertTrue(
                // 2026-10-10 起 selection 追加了 `AND content_type = ?`（图片行不参与凭据比对）⇒
                // 判据放宽成前缀匹配：仍要求「只扫收藏行」这条语义，允许在其后追加过滤条件
                "L-592 守卫缺失：ClipboardDb 必须只扫收藏行来数保留条数",
                "fun favoriteKeptCounts(" in codeOf("ClipboardDb.kt") &&
                    "\"is_favorite = 1" in codeOf("ClipboardDb.kt"),
            )
        }
    }

    /**
     * 第三批修复的形态守卫（BUG.md L-314 / L-315 / L-319 / L-320）。
     *
     * 同样都是「回退之后 JVM 单测依然全绿」的位置，只有真机能看出来，所以钉形态：
     *
     * - L-314 译文若**不净化**就落地，服务端回一个 `U+202E` 就能让用户自己那段文字的显示顺序反转
     *   （「有可见内容」这条判据完全不拦它 —— 它判的是有没有内容，不是内容里夹了什么）。
     * - L-315 选区若**各读一次**，每次点翻译就白付一次整窗口（最多 10 万字符）的 `getExtractedText`；
     *   `beforeNow` 那一份也是同样道理 —— 它已经在手里，没理由再为 1 个字符读一次 Binder。
     * - L-319 少了那条比对，服务端回显原文时界面会报成功而用户看到自己的话被复制了一遍，事后无从归因。
     * - L-320 成功日志若又排到 `commitText` **之前**，提交失败时诊断包里会先成功后失败，排障得到反结论。
     */
    @Test
    fun `第三批修复的形态不得回退`() {
        val ime = codeOf("JinnIme.kt")
        assertTrue(
            "译文必须先过净化再落地（L-314）",
            "translated.sanitizedForOutput()" in ime,
        )
        assertTrue(
            "选区读数必须复用给两个判据（L-315）",
            "canAppendTranslation(connection, selectionRange)" in ime,
        )
        assertTrue(
            "前导换行只能在**光标没动过**时复用 `beforeNow`（L-315 的省读 / L-323 的契约：" +
                "`appendOffset > 0` 时插入点已经不在原处，照旧复用会把译文黏到原文行尾）",
            "snapshot.appendOffset == 0 && !beforeNow.isNullOrEmpty()" in ime,
        )
        assertTrue(
            "服务端回显原文时必须留下可辨认的记录（L-319）",
            "译文与所发原文逐字相同" in ime,
        )
        assertTrue(
            "提交失败必须让用户看到（L-320）",
            "TEXT_TRANSLATE_COMMIT_FAILED" in ime && "toast(TEXT_TRANSLATE_COMMIT_FAILED)" in ime,
        )

        val tr = codeOf("Translation.kt")
        assertTrue(
            "双向控制符必须在净化表里（L-314）",
            "BIDI_CONTROLS" in tr && "\\u202E" in tr,
        )


        assertTrue(
            "净化必须保留换行与制表符（多行译文不能被压成一行，L-314）",
            "this == '\\n' || this == '\\t' -> false" in tr,
        )
    }


    /**
     * 第四批修复的形态守卫（BUG.md L-323…L-336）。
     *
     * 同样都是「回退之后 JVM 单测依然全绿」的位置：
     *
     * - L-323 前导换行若又去复用**原光标**的读数，`LINE_FULL` / `ALL` 两档会把译文黏到原文行尾。
     * - L-324 判空表若退回只看 `ZERO_WIDTH`，服务端只回一个变体选择符或一串 TAG 字符就能落地。
     * - L-325 选区标记若退回 `SelectionRange?`，WebView 类宿主上每次点翻译会多读一次整窗口。
     * - L-326 `commitText` 若又只看异常，返回 false（连接已失效）会被记成成功。
     * - L-327 提交阶段若又复用「未发起翻译」那两条文案，用户会以为什么都没外发。
     * - L-331 `null` 若又当成写值，用户就没有办法只替换掉一个过时的标准参数（`max_tokens`）。
     * - L-332 模板替换若退回链式 `replace`，被代入的值会被二次扫描。
     * - L-333 `stream` 若不被拦，响应是 SSE 而解析按非流式 ⇒ 必然报「服务异常」且永远重试不好。
     * - L-334 书写容错若没有，`{{ text }}` 会被静默丢弃并触发兜底追加。
     * - L-336 取消若挪回换肤之后，被翻译态挡下的换肤会在同一回调里失去补做机会。
     */
    @Test
    fun `第四批修复的形态不得回退`() {
        val ime = codeOf("JinnIme.kt")
        assertTrue(
            "选区标记必须能区分「没读过」与「读过但读不出来」（L-325）",
            "private class SelectionProbe(val range: SelectionRange?)" in ime &&
                "if (known != null) known.range else currentSelectionRange(connection)" in ime,
        )
        assertTrue(
            "`commitText` 的返回值必须看（L-326：返回 false 不抛异常）",
            "if (outcome.getOrDefault(false)) {" in ime,
        )
        assertTrue(
            "提交阶段不得复用「未发起翻译」那两条文案（L-327）",
            "TEXT_TRANSLATE_DISCARDED_UNREADABLE" in ime &&
                "TEXT_TRANSLATE_APPEND_UNKNOWN" in ime,
        )
        val startView = blockAfter(ime, "override fun onStartInputView(")
        assertTrue(
            "会话边界取消必须在换肤之前（L-336）：换肤的延后判据里含视图侧 translateInFlight" +
                "（2026-10-03 修复 L-613 后它已不在 `hasActiveOverlay` 里，但 `setTranslating(false)`" +
                "仍是视图字段的写入，顺序反过来会让同一回调里的补做机会丢失）",
            startView.indexOf("cancelTranslate(notify = true)") <
                startView.indexOf("applyThemeIfNeeded()"),
        )

        val tr = codeOf("Translation.kt")
        assertTrue(
            "判空表要覆盖软连字符 / 变体选择符 / TAG 字符（L-324）；" +
                "**按码位**判定 —— 逐 Char 判时 TAG 的低代理会漏网（L-339）",
            "isInvisibleCodePoint" in tr && "0xE0000" in tr,
        )
        assertTrue(
            "判空表要补盲文空白 / Hangul 填充符，剥除表要补 ALM（L-343）",
            "0x2800" in tr && "0x115F" in tr && "'\\u061C'" in tr,
        )

        val oa = codeOf("OpenAiTranslator.kt")
        assertTrue(
            "模板替换必须是单趟扫描（L-332：链式 replace 会二次展开被代入的值）",
            "normalizeKnownVars" in oa && "val hit = when {" in oa,
        )
        assertTrue(
            "已知变量的书写容错要认空格与大小写（L-334）",
            "KNOWN_VAR_PATTERN" in oa,
        )
        assertTrue(
            "`stream` 必须被当作不可用（L-333：会破坏本客户端，且失败被吞成 SERVER）",
            "UNSUPPORTED_EXTRA_KEYS" in oa,
        )
        assertTrue(
            "自定义 JSON 的 `null` 必须表示删键（L-331）；删键判定改为**按值**并与必需键闸同源（L-421）",
            "value === JSONObject.NULL" in oa && "body.remove(key)" in oa,
        )
    }

    /**
     * 凭据缓存必须是**进程级**（BUG.md L-298）。
     *
     * 挂在实例上时，设置页改完 Key 只更新它自己那一份，常驻的 IME 会一直拿旧 Key 发请求 ——
     * 「改 Key 不生效、删 Key 也不生效」，与设置页写下的「本页改完不需要重启输入法」直接矛盾。
     * 这条回退之后 JVM 单测依然全绿（同进程的两个 `Prefs` 实例要真机才演得出来）。
     */
    @Test
    fun `凭据缓存必须是进程级`() {
        val prefs = codeOf("Prefs.kt")
        val companion = blockAfter(prefs, "companion object {")
        assertTrue(
            "凭据缓存必须声明在 companion object 里（L-298）",
            "private val credentialCache" in companion,
        )
        assertFalse(
            "类体里不得再有第二份凭据缓存（L-298：实例级那份就是缺陷本身）",
            "\n    private val credentialCache" in prefs,
        )
        assertTrue(
            "回填缓存前必须确认密文没被换掉（L-298 的竞态）",
            "凭据在解密期间被改写" in prefs,
        )
    }

    @Test
    fun `可选词库线程不得持有 Service Context`() {
        val body = blockAfter(codeOf("PinyinEngine.kt"), "fun loadOptionalAsync(")
        assertTrue("必须取应用 Context（context.applicationContext）", "context.applicationContext" in body)
        assertFalse("线程内不得再用传入的 Service context 调 load", "load(context)" in body)
        assertFalse("线程内不得再用传入的 Service context 调 loadExtensionDict", "loadExtensionDict(context)" in body)
    }

    /**
     * 翻译收尾只能有一个入口（BUG.md L-229 / L-231）。
     *
     * 看门狗超时、会话边界取消、回调到达、请求发起失败，四处若各写各的收尾，最容易漏掉
     * 「作废代际」—— 那样 330s 看门狗复位之后到达的旧回调仍会被当成当前代际，把译文插进
     * 用户已经改过的输入框。判据是**收尾动作各只许出现一份**：三处共用 `finishTranslate`
     * 之后，裸复位与代际自增都只剩它里面那一处。
     */
    @Test
    fun `翻译收尾只能有一个入口`() {
        val src = codeOf("JinnIme.kt")
        assertEquals(
            "收尾函数只此一处定义（四处入口必须调它）",
            1,
            Regex("private fun finishTranslate\\(\\)").findAll(src).count(),
        )
        // ⚠ 三条正则都要**容忍写法变体**（2026-10-03 修复 L-608）：只认单一写法的计数断言
        // 是"脆钉" —— `translateInFlight=false`（无空格）、`this.translateInFlight = false`、
        // `translateGeneration += 1` 都能绕过「收尾只有一个入口」这条不变量，而测试仍绿。
        assertEquals(
            "裸复位 `translateInFlight = false` 只允许出现在 finishTranslate 里（字段初始化不算）",
            1,
            Regex("(?m)^\\s*(?:this\\.)?translateInFlight\\s*=\\s*false").findAll(src).count(),
        )
        // ⚠ `translateGeneration` 的递增在实现里是**两种形式**：finishTranslate 用后缀
        // `translateGeneration++`、startTranslate 用前缀 `++translateGeneration`。
        // 所以不能只钉一种形态（原先只数 `translateGeneration++`：谁把 finishTranslate 那处
        // 改成前缀形式，计数会变 0 ⇒ 断言**红**，看着还行；但谁**新增**第三种写法
        // （如 `translateGeneration += 1`）则两种形态都不匹配 ⇒ 计数不变 ⇒ **静默通过**）。
        // 现在分别钉住两个位置，再用总数 2 挡住"第三种写法"（2026-10-03 修复 L-608）。
        assertEquals(
            "后缀 `translateGeneration++` 只允许出现在 finishTranslate 里",
            1,
            Regex("translateGeneration\\s*\\+\\+").findAll(src).count(),
        )
        assertEquals(
            "前缀 `++translateGeneration` 只允许出现在 startTranslate 里",
            1,
            Regex("\\+\\+\\s*translateGeneration").findAll(src).count(),
        )
        assertEquals(
            "递增入口只允许这两处：多出第三种写法（+= 1 / inc() 等）即失败",
            2,
            Regex(
                "(?:translateGeneration\\s*\\+\\+|\\+\\+\\s*translateGeneration|" +
                    "translateGeneration\\s*\\+=\\s*1|translateGeneration\\s*=\\s*translateGeneration\\s*\\+\\s*1)",
            ).findAll(src).count(),
        )
        assertEquals(
            "撤看门狗只能有两处：startTranslate 清旧的、finishTranslate 收尾",
            2,
            Regex("removeCallbacks\\(\\s*translateWatchdog\\s*\\)").findAll(src).count(),
        )
    }

    /**
     * 本轮修复（2026-10-01 第九十七回）的关键调用不许退化。
     *
     * 全部用源码对拍，因为这几条的失败模式都是**静默**：删不掉（隐私目标落空）、把「读不到」
     * 报成「没有内容」（归因错）、裸 Binder 调用带走进程（崩溃）、摘要门槛失效（排障无门）。
     */
    @Test
    fun `本轮修复的关键调用不许退化`() {
        val db = codeOf("ClipboardDb.kt")
        assertTrue(
            "剪贴板库必须提供「清洗后相等」的删除（L-239，批量版见 L-244）",
            "fun deleteByCleanedPlaintexts(" in db,
        )
        assertTrue(
            "清洗后匹配只删未收藏条目（L-239）",
            "is_favorite = 0" in blockAfter(db, "fun deleteByCleanedPlaintexts("),
        )
        val settings = codeOf("TranslationSettingsActivity.kt")
        assertTrue(
            "凭据不留痕走公共入口（L-239 → L-244/L-245：两段匹配已收进 CredentialTrace）",
            "CredentialTrace.purge(db, targets)" in settings,
        )

        val ime = codeOf("JinnIme.kt")
        assertTrue(
            "光标前读取必须能区分「读失败」（L-240）",
            "private fun readTextBeforeCursor(" in ime && "): String? {" in ime,
        )
        assertTrue("光标后读取同样要三态（L-237）", "): String? {" in ime)
        assertTrue(
            "两条读取失败的提示必须用独立文案（L-240 / L-242）",
            "TEXT_TRANSLATE_BEFORE_UNREADABLE" in ime && "TEXT_TRANSLATE_AFTER_UNREADABLE" in ime,
        )
        assertTrue("移动光标后的日志必须按调用结果说话（L-242）", "if (applySelection(" in ime)
        assertTrue(
            "清空要兜底（L-241；2026-10-02 起统一走 guardHostCall，留痕由助手提供）",
            "guardHostCall(\"deleteAllText\")" in ime && "宿主调用失败" in ime,
        )
        assertTrue("打字上屏失败要留痕（L-241）", "commit: 上屏失败" in ime)

        val client = codeOf("TranslationClient.kt")
        assertTrue("2xx 里的错误体也要有摘要（L-243）", "(2xx)" in client)
        assertTrue("响应被截断要标出来（L-243）", "capped" in client)
        assertFalse(
            "摘要白名单不得再用 isLetterOrDigit（非 ASCII 也为真，L-243）",
            "it.isLetterOrDigit()" in client,
        )
    }

    /**
     * 凭据清理必须投长活池（BUG.md L-1423）。
     *
     * `CredentialTrace.purge` 的第二段是**非收藏全表逐行 AES-GCM 解密**（判据「剥不可见字符后相等」，
     * 见 `ClipboardDb.deleteByCleanedPlaintexts`）。历史上万条时是秒级活，投在短活池
     * （`BackgroundIo.run`）上会把面板首屏查询与剪贴板入库排到它后面 —— 而这条路径每次进出
     * 翻译设置页 / OpenAI 配置页都会跑一次，用户看到的是「面板白屏 / 复制了没反应」。
     *
     * 为什么钉源码形态：单线程队列里排一个大活，症状只在「库很大 + 此刻正在看面板」时出现，
     * 单测与真机都能全绿（与图库整份拷贝那条同款）。
     */
    @Test
    fun `凭据清理必须投长活池`() {
        for (name in listOf("TranslationSettingsActivity.kt", "OpenAiSettingsActivity.kt")) {
            val src = codeOf(name)
            val body = blockAfter(src, "BackgroundIo.runLong {")
            assertTrue(
                "$name 的凭据清理必须进长活池 —— 秒级全表解密排在交互队列上会推后面板首屏与入库",
                "CredentialTrace.purge(" in body,
            )
            // 入口只许有一处：多出来的那条迟早会漂回短活池（现状两页各一处）
            assertEquals(
                "$name 的凭据清理入口只应有一处",
                1,
                Regex("""CredentialTrace\.purge\(""").findAll(src).count(),
            )
        }
        // 备份导入也是一条「凭据落盘」路径（AI2 A-333）：它不经过两个设置页，那两处的清理不会被
        // 触发 —— 历史里同值的明文条目会一直留到用户下次进凭据页（面板明文可见 + 随备份导出）。
        val backup = codeOf("ConfigBackupManager.kt")
        assertTrue(
            "导入成功要调度一次凭据清理（AI2 A-333）",
            "scheduleCredentialPurge(context" in backup,
        )
        // 触发面钉在**闸门那一行**上：只看 prefsApplied 会漏掉「仅并入数据 + 勾了剪贴板」——
        // 那条路径 prefsApplied 恒为 0，而包里的剪贴板段照样能把与本机凭据同值的明文送进历史
        // （正是最该清的那一条）。反向的「多跑全表解密」由 clipAdded 兜住（没插进任何行时不跑）。
        val gateLine = backup.lineSequence().firstOrNull { "prefsApplied > 0" in it }
        assertTrue("找不到 A-333 的调度闸门（修法被删了？）", gateLine != null)
        assertTrue(
            "闸门必须同时看「设置写进去」与「剪贴板插进行」（A-333 的触发面不止 prefs）: $gateLine",
            "clipAdded > 0" in gateLine!!,
        )
        assertTrue(
            "调度要带上「导入前的凭据值」（覆盖还原会替换掉它们）",
            "scheduleCredentialPurge(context, before)" in backup,
        )
        // 覆盖还原会替换凭据：旧值必须一并当目标，否则历史里旧 Key 的明文再也不会有清理机会
        assertTrue(
            "清理目标要取「导入前后并集」（覆盖还原后旧凭据值不在当前值里了）",
            Regex("""configuredValues\(""").findAll(backup).count() >= 2,
        )
        assertTrue(
            "导入后的清理必须投长活池（秒级全表解密，L-1423 同口径）",
            Regex("""BackgroundIo\.runLong\s*\{[\s\S]{0,600}?CredentialTrace\.purge\(""").containsMatchIn(backup),
        )
        assertTrue(
            "导入后清理的目标要取本机凭据全集（与设置页同源）",
            "CredentialTrace.configuredValues(" in backup,
        )
    }

    /**
     * 凭据读写闭环与不留痕入口不许退化（2026-10-01 第九十九回）。
     *
     * 这四条失败时都**无声**：删数据（读失败当清空）、漏删（入口分叉）、不翻译（模板缺占位符）、
     * 扫描放大（每个目标各扫一遍全表）—— 所以钉在源码上。
     */
    @Test
    fun `凭据读写闭环与不留痕入口不许退化`() {
        val saveGuard = codeOf("CredentialSaveGuard.kt")
        assertTrue("必须存在保存护栏（L-247）", "fun changed(field: EditText)" in saveGuard)

        val settings = codeOf("TranslationSettingsActivity.kt")
        assertTrue(
            "保存必须逐字段判「改过才回写」（L-247）",
            "saveIfChanged(editDeeplKey)" in settings &&
                "saveIfChanged(editAliyunKeyId)" in settings,
        )
        assertTrue(
            "落盘成功要推进写入基线（L-970）：不推的话「粘贴 → 落盘 → 删空」那一次清空会被判成没改过",
            "for (f in credentialFields()) if (saveGuard.changed(f)) saveGuard.markWritten(f)" in settings,
        )
        assertTrue("载入必须记原值（L-247）", "saveGuard.remember(field, value)" in settings)
        // 目标键清单已收进 CredentialTrace.configuredValues（AI2 A-333 的导入路径也要用同一份）
        val traceKeys = codeOf("CredentialTrace.kt")
        assertTrue(
            "OpenAI Key 也要进不留痕目标（L-245）",
            "prefs.openAiApiKey," in traceKeys && "fun configuredValues(prefs: Prefs)" in traceKeys,
        )
        assertTrue("翻译设置页取同一份清单", "CredentialTrace.configuredValues(prefs)" in settings)
        assertTrue("不留痕走公共入口（L-244 / L-245）", "CredentialTrace.purge(db, targets)" in settings)

        val openAiPage = codeOf("OpenAiSettingsActivity.kt")
        assertTrue("OpenAI 页同样走公共入口（L-245）", "CredentialTrace.purge(" in openAiPage)
        assertTrue("OpenAI 页的 Key 也要 guard（L-247）", "saveGuard.changed(editApiKey)" in openAiPage)
        assertTrue(
            "OpenAI 页同样要推写入基线（L-970），且只在确认落盘后推",
            "saveGuard.markWritten(editApiKey)" in openAiPage &&
                "saveGuard.changed(editApiKey) && unpersisted.isEmpty()" in openAiPage,
        )

        val db = codeOf("ClipboardDb.kt")
        assertTrue("清洗匹配必须是批量、一次遍历（L-244）", "fun deleteByCleanedPlaintexts(" in db)
        assertFalse("不得退回「每个目标各扫一遍全表」（L-244）", "fun deleteByCleanedPlaintext(" in db)

        val translator = codeOf("OpenAiTranslator.kt")
        assertTrue("模板缺占位符要有兜底（L-246）", "fun applyTemplateEnsuringText(" in translator)
        assertTrue(
            "兜底必须落在 user 消息（L-246 → L-250：system 是角色指令，不该承载正文）",
            "applyTemplateEnsuringText(user, system" in translator,
        )
        assertFalse(
            "system 消息不得再走兜底（否则原文会在两条消息里各出现一遍）",
            "applyTemplateEnsuringText(system" in translator,
        )

        val trace = codeOf("CredentialTrace.kt")
        assertTrue(
            "不留痕必须收敛，且收敛要看**历史变化**而不只是内容（L-249 → L-254）",
            "private val purged" in trace && "private val purged = java.util.concurrent.ConcurrentHashMap<String, Long>" in trace,
        )
        assertTrue(
            "收敛判据要基于 maxItemId（历史里又出现新条目就重扫）",
            "fun maxItemId(" in codeOf("ClipboardDb.kt"),
        )
        assertTrue(
            "自定义 Headers / JSON 里的值也要进目标（L-252）",
            "fun candidatesFrom(" in trace,
        )
        assertTrue(
            "JSON 要递归收集（嵌套 / 数组里的密钥同样会发出去，L-256）",
            "collectJsonStrings" in trace,
        )

        val prefs = codeOf("Prefs.kt")
        assertTrue(
            "「留空即默认」的读侧只回落默认值，**不得**把「等于默认」折成空串（L-293）",
            "private fun defaulted(stored: String, fallback: String): String = stored.ifBlank { fallback }" in prefs,
        )
        assertTrue(
            "「留空即默认」的写侧走 putDefaulted 删键，而不是写死字面量（L-293 / L-288）",
            "private fun putDefaulted(" in prefs &&
                "multiline: Boolean = false" in prefs &&
                "if (v.isEmpty() || v == fallback) remove(key) else putString(key, v)" in prefs,
        )
        assertTrue(
            "等于默认值必须**删键**而不是写死（L-255：超时 / 界面语言 / 每家字节上限）",
            "remove(KEY_LANGUAGE)" in prefs &&
                "remove(KEY_OPENAI_TIMEOUT_SEC)" in prefs &&
                "remove(maxBytesKeyOf(id))" in prefs,
        )
    }

    /**
     * 「该家是否已配置」的两处判据必须同源（BUG.md L-231）。
     *
     * `Prefs.hasCredentialFor`（翻译设置页摘要）与 `TranslationClient.providerOf`（点翻译时
     * 真正建 provider）若一个用 `isNotBlank()`、一个用 `cleanCredential()`，一个纯零宽字符的
     * 假凭据就会造成「摘要说已配置、点翻译说未配置」—— 用户两边都看不出为什么。
     */
    @Test
    fun `摘要与建 provider 的凭据判据必须同源`() {
        // 用户看得见的那条路径（2026-10-01 复审 L-232）：上一版只盯了内部辅助函数，
        // 摘要照样把零宽「假凭据」显示成「已配置」—— 守卫必须盯用户路径，不是内部辅助。
        val ui = blockAfter(codeOf("TranslationSettingsActivity.kt"), "private fun refreshState(")
        assertTrue(
            "摘要必须走 cleanCredential（与 providerOf 逐字对齐）",
            "cleanCredential()" in ui,
        )
        assertFalse(
            "摘要不得再用 isNotBlank()（纯零宽凭据会被显示成「已配置」）",
            "isNotBlank()" in ui,
        )
        // 内部推导辅助（键缺失时推导默认 provider）同样要同源
        val helper = blockAfter(codeOf("Prefs.kt"), "private fun hasCredentialFor(")
        assertTrue(
            "hasCredentialFor 必须走 cleanCredential（与 providerOf 逐字对齐）",
            "cleanCredential()" in helper,
        )
        assertFalse(
            "hasCredentialFor 不得再用 isNotBlank()（纯零宽凭据会被当成已配置）",
            "isNotBlank()" in helper,
        )
    }

    @Test
    fun `本批修复的形态不得回退（第一百三十五回）`() {
        // 本轮最值得钉住的一条背景：**声称修好、实现却没改**（编辑未落盘）发生过多次 ——
        // `setTranslateScopeOf` 的删键、⓪ 步的提示分列都曾如此。机械对拍是唯一可靠的防线：
        // 源码形态一旦被改回旧写法，这里立刻变红。
        val prefs = codeOf("Prefs.kt")
        // L-425 后半：scope 等于默认必须删键，否则「进过原文范围页一次」就把当时的默认固化进备份
        assertTrue(
            "setTranslateScopeOf 必须含「等于默认删键」",
            blockAfter(prefs, "fun setTranslateScopeOf").let {
                "TranslationScope.DEFAULT.id" in it && "remove(scopeKeyOf(id))" in it
            },
        )
        // 导出侧必须是类型安全快照：`getInt` / `getString` 对类型不符的键抛 ClassCastException，
        // 一个脏键会让整次导出失败（本轮发现）
        assertTrue(
            "导出侧必须用类型安全快照（as? 过滤），不得回到 sp.getInt / sp.getString 直读",
            "fun putIfSetInt" in prefs && "as? Int" in prefs && "as? String" in prefs,
        )

        val ime = codeOf("JinnIme.kt")
        // L-438：⓪ 步必须把「读不到 EditorInfo」与「敏感框」分成两条提示
        val append = blockAfter(ime, "private fun appendTranslation")
        assertTrue(
            "提交前复查必须分开两种原因（读不到属性 vs 敏感框）",
            "if (info == null) {" in append &&
                "TEXT_TRANSLATE_DISCARDED_UNREADABLE" in append &&
                "TEXT_TRANSLATE_PRIVATE_FIELD" in append,
        )
        // L-427 的过度修正：窗口必须按范围分档，否则默认档（阿里云 5000 字节）被放大 20×
        assertTrue("读取窗口必须按 needAfter 分档", "val limit = if (needAfter) {" in ime)
        // 本轮新发现的两条交互缺口
        assertTrue(
            "剪贴板面板打开时必须拦下翻译（否则已计费的请求会被随后的粘贴作废）",
            "isClipboardActive() == true" in ime && "TEXT_TRANSLATE_CLIPBOARD_OPEN" in ime,
        )
        assertTrue(
            "切到语音键盘必须一并收剪贴板面板（否则 hasActiveOverlay 持续为真、换肤被无限延后）",
            "hideSearchPanel()" in ime && "hideClipboardPanel()" in ime,
        )

        val openAi = codeOf("OpenAiTranslator.kt")
        assertTrue(
            "stream 真值判据必须覆盖数值形态（2 / 1.0 会被放行成流式）",
            "private fun isTruthy" in openAi && "isTruthy(value)" in openAi,
        )
    }

    @Test
    fun `本批修复的形态不得回退（第一百三十六回）`() {
        // 最重的一条：自定义目标语言「打开页面再返回就被改回简体中文」。修法必须**成对** ——
        // 载入自定义值时置 `customEdited`，加上下拉闸门复位它；只留一半比不修更糟：
        // 读屏 / 外接键盘用户选完下拉会保存到**旧的自定义语言**（L-367 的老问题复活）。
        val settings = codeOf("OpenAiSettingsActivity.kt")
        assertTrue(
            "载入自定义目标语言时必须置 customEdited（否则打开页面再返回就改翻译方向）",
            "customEdited = true" in blockAfter(settings, "private fun applyTargetLanguage"),
        )
        val onSelected = blockAfter(settings, "override fun onItemSelected")
        assertTrue("下拉闸门必须先挡程序化回填", "if (loading) return" in onSelected)
        assertTrue(
            "下拉闸门必须认全用户来源（触摸 / 确认键 / 读屏，L-822）并复位 customEdited",
            "spinnerTarget.userInteracted" in onSelected && "customEdited = false" in onSelected,
        )
        assertTrue(
            "超时字段必须 trim（与同页数字三项、字节上限同口径）",
            "toString().trim().toIntOrNull()" in settings,
        )

        val openAi = codeOf("OpenAiTranslator.kt")
        assertTrue("容错写法必须认全角花括号（中文 IME 的常见输出）", "[{｛]{2}" in openAi)

        val translation = codeOf("Translation.kt")
        assertTrue(
            "判空必须同时问 isWhitespace 与 isSpaceChar（U+2007 / U+202F 落在后者）",
            "!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)" in translation,
        )
        assertFalse(
            "callTimeoutSec 的 KDoc 不得再声称「只有 OpenAI 兼容一家覆写」（百度大模型也覆写）",
            "只有 OpenAI 兼容一家暴露这个值" in translation,
        )
    }

    @Test
    fun `编辑路径的宿主调用必须走 guardHostCall（第一百三十七回 · L-404）`() {
        // 裸调同步的 InputConnection 写方法，会在**宿主进程被强停 / 崩溃**后抛 DeadObjectException
        // （`sendDownUpKeyEvents` 更是 AOSP 内部裸调 sendKeyEvent）；异常从主线程的点击回调上抛
        // ⇒ 崩溃处理器接手 ⇒ 整个 IME 进程终止：键盘消失，未上屏的组合串与暂存粘贴一起丢。
        // 最小复现：`adb shell am force-stop <宿主包>` 后立刻按「全选」/ 回车。
        // ⚠ 语音链路的调用点按红线**只记录不改**，所以这里只钉编辑与面板四条路径。
        val ime = codeOf("JinnIme.kt")
        assertTrue("guardHostCall 助手不得删除", "private inline fun guardHostCall" in ime)
        for (signature in listOf(
            "private fun selectAllText()",
            "private fun performEnter()",
            "private fun flushPendingPaste(",
            "private fun deleteAllText()",
        )) {
            val body = blockAfter(ime, signature)
            assertTrue("$signature 的宿主调用必须包在 guardHostCall 里", "guardHostCall(" in body)
        }
        // 包裹面不得缩水：上面四处共 5 个调用点 + 助手定义 1 处 = 6；少一个说明有人把某处改回了
        // 裸调、或删掉了某处兜底（负向「行首裸调」正则在这里不可用 —— 包裹后调用仍在行首）。
        val calls = Regex("guardHostCall\\(").findAll(ime).count()
        assertTrue("guardHostCall 的出现次数不得少于 6（助手 + 5 处调用），实际 $calls", calls >= 6)
    }

    @Test
    fun `正文落盘主闸门与往返长度闸不得被打开（第一百三十九回 · L-446 · L-375）`() {
        // L-446：V 级是**用户正文**的主要通道（拼音串 / 搜索词 / 按键串 / commit 正文都走 v()），
        // 而 `VERBOSE_TO_FILE` 是它们「不落盘」的**唯一防线** —— 脱敏是正则白名单，挡不住任意正文。
        // 此前它没有任何守卫：改成 true 不会有测试红，正文会直接进日志文件并随诊断包外发。
        val diag = codeOf("Diagnostics.kt")
        assertTrue(
            "VERBOSE_TO_FILE 必须保持 false（正文不落盘的主闸门）",
            "private const val VERBOSE_TO_FILE = false" in diag,
        )
        assertTrue("v() 的短路判据必须存在", "if (level == 'V' && !VERBOSE_TO_FILE) return" in diag)
        // 空包不得当成功交出（L-449）。用全文判据而非块判据：`blockAfter` 取的是 marker 之后
        // **第一个** `{` 的配对块，而那里第一个 `{` 是 `ZipOutputStream(...).use {`，
        // 空包判据在它**之后**（踩过这个坑）
        assertTrue("诊断包必须检测「一个条目都没写进去」", "if (written == 0)" in diag)

        // L-375：导入侧闸与写入侧闸必须**共用同一常量**（此前只有导入侧有闸 ⇒ 自产的包被自己拒收）。
        val prefs = codeOf("Prefs.kt")
        assertTrue("长度上限必须是单一来源的常量", "MAX_BACKUP_STRING_CHARS = 64 * 1024" in prefs)
        assertTrue("导入侧必须引用该常量", "length <= MAX_BACKUP_STRING_CHARS" in prefs)
        for (field in listOf("openAiExtraHeaders", "openAiExtraJson", "openAiModelsCache")) {
            assertTrue(
                "$field 的 setter 必须在写入侧过共享上限（否则导出成功、导入静默丢键）",
                "capForBackup(" in blockAfter(prefs, "var $field: String"),
            )
        }
        // 助手本身必须真的引用那个常量（否则「共享」只是名义上的）；
        // 用全文判据：助手的第一个 `{` 是 `if` 的 then 块，块判据取不到 take(...) 那行
        assertTrue("capForBackup 必须引用共享常量", "take(MAX_BACKUP_STRING_CHARS)" in prefs)
    }

    @Test
    fun `设置页 onPause 必须在导入期间跳过回写（第一百三十七回 · L-405）`() {
        // 导入线程写 Prefs ↔ 页面 onPause 的**无条件**回写是两条独立执行流：交错时界面旧值
        // 覆盖刚导入的值，用户看到「导入成功」却发现配置没变（静默退回）。
        // 2026-10-09：设置页那处回写（剪贴板上限输入框）连同控件一并移除 —— 功能已在「剪贴板自定义」页，
        // 于是这里对它改判**否定式**（那条路径不许再出现），另外三页的回写仍在，闸门照旧。
        for (name in listOf(
            "TranslationSettingsActivity.kt",
            "OpenAiSettingsActivity.kt",
            "TranslationSourceActivity.kt",
        )) {
            val body = blockAfter(codeOf(name), "override fun onPause()")
            assertTrue(
                "$name 的 onPause 必须带 ConfigBackupManager.importing 闸门",
                "ConfigBackupManager.importing" in body,
            )
        }
        val settings = codeOf("SettingsActivity.kt")
        assertTrue(
            "设置页不得再有「界面值回写 Prefs」的 onPause（剪贴板上限已迁出本页，真有回写就要补导入闸门）",
            "override fun onPause()" !in settings,
        )
    }

    @Test
    fun `本批修复的形态不得回退（第一百四十回）`() {
        // L-454：口令判据必须**连类位一起掩**。平台里 TYPE_TEXT_VARIATION_URI 与
        // TYPE_NUMBER_VARIATION_PASSWORD 同为 0x10（日期变体也是），只掩变体位会把地址栏
        // 判成口令框 —— 翻译被拒、词频不学，而人眼看不出它们是同一个值。
        // 行为断言在 InputFieldPrivacyTest.同一个变体位值下的其它语义不误判为口令框。
        assertTrue(
            "口令判据必须掩「类位 + 变体位」：只掩变体位会让 URI(0x10) 撞上数字密码(0x10)",
            "EditorInfo.TYPE_MASK_CLASS or EditorInfo.TYPE_MASK_VARIATION" in codeOf("InputFieldPrivacy.kt"),
        )
        val ime = codeOf("JinnIme.kt")
        // L-455：宿主带自定义动作 id 时必须发它 —— 只发标准动作码会让「搜索 / 提交」不触发，
        // 而且因为 hasAction 为真也不会退回发回车键，用户按下去「完全没反应」。
        assertTrue(
            "回车键必须把宿主的自定义 actionId 交给 performEditorAction",
            "editorInfo?.actionId" in ime && "performEditorAction(customActionId)" in ime,
        )
        // L-456：提交成功日志不得断言「已写入」—— commitText 返回 true 只代表调用被接受，
        // 宿主的 InputFilter（数字框最常见）会把正文整段丢弃而不报错。
        // ⚠ 钉的是**日志字面量**（带引号）：正文里引述旧措辞的历史注释不算。
        assertTrue(
            "提交成功日志必须用「已提交…（未验落地）」，不得写 \"已追加译文\"",
            "\"已提交追加\"" in ime && "\"已追加译文\"" !in ime,
        )
        // L-457：两条读取链（translate / fetchModels）必须共用同一个截断判据 ——
        // 只在一路记标记时，另一路截断后只剩「共 0 个」，与真的空列表不可分辨。
        val client = codeOf("TranslationClient.kt")
        assertTrue(
            "translate 与 fetchModels 必须共用 isBodyCapped（截断标记不能只在一路）",
            "private fun isBodyCapped(" in client &&
                client.split("isBodyCapped(body)").size - 1 >= 2,
        )
        // L-461：只写不读的视图字段不得复活（KDoc 曾声称它驱动回车键行为，实际没人读）。
        // 钉**声明**而不是「整个文件不许出现这个词」—— 后者会在注释 / 局部变量正常提到 imeOptions 时误红
        // （2026-10-02 修复 L-474）。
        val view = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "PinyinKeyboardView 不得再有只写不读的 imeOptions 字段",
            "var imeOptions" !in view && "fun updateImeOptions" !in view,
        )
        // 2026-10-02（用户澄清后改回）：候选栏高度**只按档位**算 —— 双行档恒定增高（72dp），
        // 不随「有候选 ↔ 功能面板」变化。此前曾按「本帧实际内容排数」把面板态收缩到单排，
        // 结果是键盘高度来回跳；用户明确要求不要这种动态变化，已撤销。
        // 钉住：几何入口只收「档位」一个参数，且不得再有按内容排数收缩的写法。
        assertTrue(
            "候选栏高度必须只按档位算（双行档恒定增高，不随内容变化）",
            // 入参只有「档位 + 候选字号 + 拼音字号 + 行间隔」四个**帧内稳定**的值
            Regex(
                """fun applyCandidateRows\(\s*rows: Int,\s*candidateSp: Float,\s*pinyinSp: Float,\s*gapDp: Float,\s*\): Int""",
            ).containsMatchIn(view) &&
                "functionPanel" !in view,
        )
        // L-478 的延续（2026-10-02，用户选「方案 A」）：候选栏底必须与功能面板按钮**同档**
        // （都走内容面档 keyFaceAlpha）。它曾按「是否有内容」在 plate / surface 之间切档：
        // 空白态（功能面板）比按钮更透（20% 时 80% vs 92%）⇒ 在浅色页面上按钮四周显出一圈底色差，
        // 用户读作「候选栏比按钮高出一截」（深色页面 / 会压掉它的 App 里看不见）。
        assertTrue(
            "候选栏底必须固定走内容面档（keyFaceAlpha）：不得再用 plateFaceAlpha 选档",
            "plateFaceAlpha" !in view && "candidateBarColor" in view,
        )
        assertTrue(
            "候选栏底的缓存判据必须是颜色（换肤/换色板才能刷新）",
            "if (color == candidateBarColor) return" in view,
        )
        assertTrue(
            "空白铺底的候选栏必须用功能按钮色（同色才连成一块，不在按钮四周留异色底）",
            "skinToken(skin.functionFill, R.color.key_bg)" in view,
        )
    }

    /**
     * 翻译提交链路的三处 2026-10-02 修复（L-480 / L-481 / L-482）——它们在 IME 里依赖真实
     * `InputConnection` 行为，单测跑不了，用源码守卫钉住「修法本身还在」。
     */
    @Test
    fun `翻译提交链路的落点与三态守卫`() {
        val ime = codeOf("JinnIme.kt")
        // L-482：发请求前必须用「宿主能否给出精确选区」拦下 —— 否则提交阶段必然失败、白付一次请求
        assertTrue(
            "L-482 守卫缺失：发请求前应有「宿主读不到精确选区就拒绝」的闸门（用本帧已读的探针）",
            "slice.appendOffset > 0 && selectionRange.range == null" in ime,
        )
        // L-480：移到「请求时的区间末尾」之后必须确认落点（后一个字符为空或换行）
        assertTrue(
            "L-480 守卫缺失：提交前应确认追加落点确为区间末尾",
            "getTextAfterCursor(1, 0)" in ime && "追加落点不是区间末尾" in ime,
        )
        // L-481：charBeforeCursor 的「读不到」必须真的触发**补前导换行**。
        // ⚠ 第一条刻意做成**行为断言**而不是「看源码里写了哪个字符」：上一版把「读不到」映射成 '\n'，
        // 而 `appendText` 里 `prev == null || prev == '\n'` 是**同一个分支** ⇒ 源码看着改了、行为没变，
        // 源码形态守卫还把这个 no-op 形态钉死了。现在直接拿占位符去跑 `appendText`，行为不对就红。
        assertTrue(
            "charBeforeCursor 的读失败占位必须让 appendText 补前导换行（不得是 null 或 '\\n'）",
            TranslationText.appendText('\uFFFD', "x").startsWith("\n"),
        )
        assertTrue(
            "charBeforeCursor 必须用替换字符占位（真文首仍返回 null 不补换行）",
            "return if (s == null) '\\uFFFD' else s.lastOrNull()" in ime,
        )

        // L-493（2026-10-02 用户反馈）：符号收藏编辑页的删除角标**看不到字** ——
        // 原实现把 `favorite_delete`（"移除"两个字）当显示文本 + 9sp + `Button` 自带内边距，
        // 塞进 22dp 方块（还被 -4dp 负 margin 裁掉右上角）后只剩蓝底，用户读作「按钮与背景同色」；
        // 负 margin 同时让角标溢出格子边界，与「在字符容器**内部**的右上角」相反。
        // 三条一起钉，缺一条就能悄悄回来：
        val fav = codeOf("FavoriteSymbolsActivity.kt")
        assertTrue(
            "删除角标必须显示符号（favorite_delete_mark），不得再把「移除」两字当显示文本",
            "getString(R.string.favorite_delete_mark)" in fav &&
                "text = getString(R.string.favorite_delete)" !in fav,
        )
        assertTrue(
            "删除角标必须红色粗体（用户指定）：kb_key_hint_red + Typeface.BOLD",
            "R.color.kb_key_hint_red" in fav &&
                "Typeface.create(Typeface.DEFAULT, Typeface.BOLD)" in fav,
        )
        assertTrue(
            "删除角标必须完整落在字符容器内部：不得再用负 margin 外溢（会被父容器裁掉）",
            "marginEnd = -PageStyle.dp" !in fav && "topMargin = -PageStyle.dp" !in fav,
        )
        // ⚠ 真机上「角标存在、bounds 正确、像素却全是按钮底色」的真因：Material `Button` 自带
        // stateListAnimator（elevation 2dp），Z 序高于无 elevation 的兄弟，把后添加的角标盖住了。
        // ⇒ 两条一起钉：符号按钮取消抬升 + 角标自带一点 elevation（双保险）。
        assertTrue(
            "符号格按钮必须取消 Material 抬升（否则 Z 序盖住角标，屏幕上只剩按钮底色）",
            "stateListAnimator = null" in fav && "elevation = 0f" in fav,
        )
        assertTrue(
            "删除角标必须自带 elevation（确保绘制在最上层）",
            "elevation = PageStyle.dp(context, 2).toFloat()" in fav,
        )
        // 纯文本（2026-10-02 用户指定）：角标块内不得再出现 background —— 那颗圆角蓝底会在符号按钮上
        // 再叠一个小圆片，看着像「角标自己也是一颗按钮」。
        assertTrue(
            "删除角标必须是纯文本：块内不得再有 background（圆角蓝底）",
            "background" !in blockAfter(fav, "R.string.favorite_delete_mark"),
        )

        // L-483（2026-10-02 第四轮审查）：按行对齐的 Provider（百度两家）在服务端「少给行」时，
        // 其后所有行上移一位 ⇒ 译文与原文**逐行错位**，界面照报 Ok 并把错位译文写进用户正文。
        // 解析侧手里只有响应体、无法自证；出口手上有实际送出的原文 ⇒ 在唯一出口对拍。
        // 三条一起钉：接口默认必须是 false / 百度两家必须声明 true / 出口必须做对拍且只对「少」拒绝。
        val client = codeOf("TranslationClient.kt")
        assertTrue(
            "L-483 守卫缺失：出口必须对按行对齐的 Provider 做「少给行」对拍（只对「少」拒绝）",
            "provider.alignsPerLine && actual < expected" in client,
        )
        assertTrue(
            "百度两家必须声明 alignsPerLine = true（否则对拍永不生效）",
            "override val alignsPerLine: Boolean = true" in codeOf("BaiduTranslator.kt") &&
                "override val alignsPerLine: Boolean = true" in codeOf("BaiduLlmTranslator.kt"),
        )
        assertTrue(
            "alignsPerLine 的接口默认值必须是 false（默认 true 会把不按行对齐的家也卷进对拍）",
            "val alignsPerLine: Boolean get() = false" in codeOf("Translation.kt"),
        )
        // ⚠ 对拍必须按「有可见内容的行」计数，**且行的切分与行分隔符集合同源**
        // （2026-10-02 第五轮审查的回归修正 → 2026-10-03 修复 L-679 / L-682）：
        //  ① 空行不参与：服务端对空行根本不返回元素（百度系 `trans_result` 只给有内容的行），
        //     把空行算进 expected 会让「原文含空行」的整篇 / 整行翻译**必然被拒**；
        //  ② 零宽字符行（ZWSP / BOM / TAG）同样不算「有内容」—— `isNotBlank()` 对它们返回
        //     true，而净化链认它们是空 ⇒ 原文多算一行、误报「服务端少给行」，且提示
        //     「去掉空行」对用户无效（他看不见那行）；
        //  ③ 切分必须走 `TranslationText.splitLines`（7 种分隔符同源）：只 `split('\n')` 时，
        //     服务端用 U+2028 分行 ⇒ 原文 2 行、译文 1 行 ⇒ 已付费却被判少给行而拒绝。
        assertTrue(
            "对拍必须用 splitLines + hasVisibleContent 计数（切分与行分隔符同源、零宽行不算内容）",
            "TranslationText.splitLines(text).count { it.hasVisibleContent() }" in client &&
                "TranslationText.splitLines(translated).count { it.hasVisibleContent() }" in client,
        )
        // 上面那条是形态钉，判据真的对不对要靠下面三条行为断言（形态钉挡不住「函数改了但语义反了」）
        assertEquals(
            "splitLines 必须把 U+2028 当行分隔符（否则服务端用它分行时误判少给行）",
            listOf("第一行", "第二行"),
            TranslationText.splitLines("第一行\u2028第二行"),
        )
        assertEquals(
            "splitLines 必须把 CRLF 当**一个**分隔符（否则凭空多出一行）",
            listOf("a", "b"),
            TranslationText.splitLines("a\r\nb"),
        )
        assertEquals(
            "零宽字符行不算「有可见内容」（否则原文多算一行 ⇒ 误报少给行）",
            0,
            TranslationText.splitLines("\u200B\uFEFF").count { it.hasVisibleContent() },
        )
        // L-678：读取上限必须覆盖「可发上限 × UTF-8 最坏字节数」，否则合法长请求被截成坏 JSON
        assertTrue(
            "L-678 违反：响应体读取上限必须 ≥ 可发文本上限的 UTF-8 最坏字节数（" +
                "${TranslationClient.bodyBytesLimit()} < ${TranslationClient.requiredBodyBytesLimit()}）" +
                "—— 否则长请求必然归「服务异常」",
            TranslationClient.bodyBytesLimit() >= TranslationClient.requiredBodyBytesLimit(),
        )
        // L-677：代理要求认证的两个码必须归 NETWORK（企业 / 校园网最常见的标准响应）
        assertTrue(
            "L-677 守卫缺失：407（代理认证）必须被识别",
            TranslationClient.isProxyAuthCode(407),
        )
        assertTrue(
            "L-677 守卫缺失：511（网络认证）必须被识别",
            TranslationClient.isProxyAuthCode(511),
        )
        assertFalse(
            "L-677 误判：普通状态码不得被当成代理认证（401/403/429 各自的分类另有其表）",
            TranslationClient.isProxyAuthCode(401) || TranslationClient.isProxyAuthCode(403) ||
                TranslationClient.isProxyAuthCode(429) || TranslationClient.isProxyAuthCode(200),
        )
        // L-679：U+2028 / U+2029 是行分隔符（模型常用），但它们在输入框里渲染不出来 ——
        // 写入前必须剥掉，否则用户只看到「译文粘在一起了」
        assertEquals(
            "U+2028 必须在写入前被剥除（渲染层不可见的换行）",
            "ab",
            "a\u2028b".sanitizedForOutput(),
        )
        assertEquals(
            "U+2029 必须在写入前被剥除",
            "ab",
            "a\u2029b".sanitizedForOutput(),
        )
        // 反向：剥除表不得误伤合法内容（ZWJ 家族 emoji / 变体选择符 / 组合字符 / 换行与制表）
        assertEquals(
            "剥除表不得误伤 ZWJ 家族 emoji 与换行",
            "a👨‍👩‍👧\n\tb",
            "a👨‍👩‍👧\n\tb".sanitizedForOutput(),
        )
        // L-485（2026-10-02 第四轮审查）：插入模式的截断必须提示。与选区模式的两套口径是**故意的**
        // —— 选区替换会丢尾部原文（不可逆）⇒ 拒绝；插入只翻前半段、原文不动 ⇒ 拒绝更糟。
        // 但不告知用户会以为服务端漏译、反复重试（每次都真计费）。
        assertTrue(
            "L-485 守卫缺失：插入模式原文被截断时必须 toast 提示",
            "toast(TEXT_TRANSLATE_TRUNCATED)" in ime && "TEXT_TRANSLATE_TRUNCATED =" in ime,
        )

        // L-494 的实现竞态（2026-10-03 第六轮自查发现，是上一轮修复自己引入的）：
        // 回调里**无条件**清 `translateCall` ⇒ 旧请求的回调迟到时会把**新请求**的句柄抹掉，
        // 之后取消就落空、新请求照样跑完计费 —— 正是 L-494 要修的现象被实现漏洞放了回来。
        // 判据必须用**块内顺序**（清空要在 return@post 之后），不能只看两个字符串在不在。
        val translateCb = blockAfter(ime, "translateCall = TranslationClient.translate")
        assertTrue(
            "translateCall 必须在代际判据之后才清空（否则旧回调会抹掉新请求的句柄）",
            "return@post" in translateCb &&
                translateCb.indexOf("translateCall = null") > translateCb.indexOf("return@post"),
        )
        // 语译互斥（2026-10-03 第六轮审查 B-1）：翻译在途开录音 ⇒ 语音的非终态回显会改动光标前
        // 文本 ⇒ 译文提交时的逐字快照校验必然失配 ⇒ **已付费的译文被丢弃**；反向则译文的
        // commitText 会落在语音预编辑区间上顶掉正在识别的字。两条入口各一道闸门。
        assertTrue(
            "startTranslate 必须拒绝「录音中发起翻译」（B-1）",
            "TEXT_RECORDING_PLEASE_WAIT" in ime &&
                blockAfter(ime, "private fun startTranslate").contains("mode != Mode.NONE"),
        )
        assertTrue(
            "startRecording 必须拒绝「翻译在途时开始录音」（B-1）",
            "TEXT_TRANSLATING_PLEASE_WAIT" in ime &&
                blockAfter(ime, "private fun startRecording").contains("translateInFlight"),
        )

        // L-513 / L-514 / L-515（2026-10-03 第七轮修复）：配置信任链与「保存成功」的口径。
        // 三条一起钉，缺一条就会回到「保存说成功、点翻译才失败 / 同屏提示自相矛盾」的旧状。
        assertTrue(
            "L-513 / L-811：OPENAI 的凭据判据必须要求端点 https（否则 http:// 一路放行到「已配置」）",
            "internal fun endpointReady(" in codeOf("OpenAiTranslator.kt") &&
                "?.isHttps == true" in codeOf("OpenAiTranslator.kt"),
        )
        // L-811：同一份「已配置」事实只允许有一份判据 —— 三个调用点必须都走它，不得各拼一份
        for (target in listOf("Prefs.kt", "TranslationSettingsActivity.kt", "TranslationClient.kt")) {
            assertTrue(
                "L-811：$target 的 OPENAI 判据必须走 OpenAiTranslator.isReady",
                "OpenAiTranslator.isReady(" in codeOf(target),
            )
        }
        // 运行链路也不得自己拼端点判据（那正是 L-811 的成因）
        assertTrue(
            "L-811：providerOf 不得自己判端点，统一走 isReady",
            "joinUrl(" !in blockAfter(codeOf("TranslationClient.kt"), "fun providerOf("),
        )
        assertTrue(
            "L-513：OpenAI 页保存时也要标红非 https 端点（与运行期同一份判据）",
            "editBaseUrl.error = if (" in codeOf("OpenAiSettingsActivity.kt"),
        )
        assertTrue(
            "L-514：字段合法性必须计入保存成功口径",
            "editTemperature, editTopP, editMaxTokens" in
                codeOf("OpenAiSettingsActivity.kt"),
        )
        assertTrue(
            "L-515 + L-546：翻译页保存提示必须纳入凭据完整性，且判据取**本页选中的那家**" +
                "（全局 getter 在键未写入时会逐家推导，会把「正在编辑的那家不完整」判成已配置）",
            "prefs.translationProvider(pickedProvider) == null -> TEXT_SAVE_INCOMPLETE" in
                codeOf("TranslationSettingsActivity.kt"),
        )

        // L-530 / L-531（2026-10-03 第九轮修复）：凭据读取必须**下沉到按 id 分开的工厂**且 id 只求值一次。
        // 此前把关凭据的 10 个 getter 全作为 `providerOf` 的参数求值（Kotlin 参数调用前求值）⇒
        // 冷缓存下一次翻译要在主线程做最多 8 次 TEE 解密（本机实测约 1.1 ms/次 = 约 9 ms 停顿），而只用其中一家；
        // 且 `translateProvider` 的 getter 在键未写入时会逐家探测、同帧还被求值两次。
        assertTrue(
            "L-530：Prefs 必须提供按 id 的 Provider 工厂（只读该家凭据）",
            "internal fun translationProvider(id: TranslationProviderId): TranslationProvider? = when (id)" in
                codeOf("Prefs.kt"),
        )
        assertTrue(
            "L-530：凭据参数必须有默认值（否则调用方仍会被迫全量传参）",
            "aliyunKeyId: String = \"\"," in codeOf("TranslationClient.kt"),
        )
        // L-537：这两个键只在用户真的设置过时才导出，否则「未设置」会被固化成显式值 ——
        // 新机上先配好 DeepL 再导入旧包，provider 被钉死为推导值，报「请先填写凭据」而 DeepL 已配置。
        assertTrue(
            "L-537：translate_provider / translate_target 必须条件导出",
            "if (sp.contains(KEY_TRANSLATE_PROVIDER)) put(KEY_TRANSLATE_PROVIDER, translateProvider)" in
                codeOf("Prefs.kt") &&
                "if (sp.contains(KEY_TRANSLATE_TARGET)) put(KEY_TRANSLATE_TARGET, translateTarget)" in
                codeOf("Prefs.kt"),
        )
        // L-538：键盘上点翻译时这是**唯一**的提示，必须给出落点（实际路径是四级设置）
        assertTrue(
            "L-538：未配置提示必须写全路径（含「翻译设置」）",
            "请先在「设置 → 翻译设置」里填写" in codeOf("Translation.kt"),
        )

        // L-563（2026-10-03 第十一轮）：providerOf 的凭据参数全部默认空值（为性能，见 L-530），于是
        // 「漏传」不再报编译错误 ⇒ 只表现为「配置明明填了却说没配置」的静默失败。
        // 守卫：按 id 的工厂里每个分支都必须传该家的凭据参数（将来新增服务方时漏传会立刻变红）。
        val prefsCode = codeOf("Prefs.kt")
        assertTrue(
            "L-563：按 id 的工厂里，每家分支都必须传该家的凭据参数",
            "aliyunKeyId = aliyunAccessKeyId" in prefsCode &&
                "aliyunKeySecret = aliyunAccessKeySecret" in prefsCode &&
                "azureKey = azureApiKey" in prefsCode &&
                "baiduAppId = baiduAppId" in prefsCode &&
                "baiduSecret = baiduSecretKey" in prefsCode &&
                "baiduLlmAppId = baiduLlmAppId" in prefsCode &&
                "baiduLlmApiKey = baiduLlmApiKey" in prefsCode &&
                "deeplKey = deeplApiKey" in prefsCode &&
                "openAi = openAiConfig" in prefsCode,
        )
        // L-547（2026-10-03 第十一轮）：词库下载完成时若页面已关闭，**不得**直接重启进程 ——
        // 本进程同时承载键盘、在途翻译、composing 未提交文本与剪贴板面板，强杀会一起带走。
        // 判据用**块内顺序**：置标记必须在「直接重启」之前（否则改了等于没改）。
        val dict = codeOf("DictManagerActivity.kt")
        assertTrue(
            "L-547：页面已关闭时必须置 pendingDictRestart（而不是直接 killProcess）",
            "pendingDictRestart = true" in dict &&
                dict.indexOf("pendingDictRestart = true") < dict.indexOf("if (ok) page.restartImeForDict()"),
        )
        assertTrue(
            "L-547：回到本页时必须补做重启（否则词库永不生效）",
            "if (pendingDictRestart) {" in dict && "restartImeForDict()" in dict,
        )
        // L-548：下载链路必须有整体预算（否则「开始回包后极慢吐字节」可把线程拖到理论无限）
        assertTrue(
            "L-548：词库下载必须设 callTimeout",
            "callTimeout(600, java.util.concurrent.TimeUnit.SECONDS)" in dict,
        )
        // L-552：三个翻译枚举的 keep 必须显式写进仓库（不能只靠 AGP 默认规则集的隐性契约）
        val pro = File("proguard-rules.pro").let { if (it.isFile) it else File("app/proguard-rules.pro") }
        assertTrue(
            "L-552：TranslationLanguage 的成员必须显式 keep（它的常量名是持久化值）",
            pro.isFile && "-keepclassmembers enum com.jinn.inputmethod.TranslationLanguage" in pro.readText(),
        )

        // L-564（2026-10-03 第十二轮审查）：提交阶段的「落点是不是区间末尾」必须**三态分开**且与
        // `TranslationText` 的行分隔符同源 ——
        //  · `null`（宿主读不到）不能当「已到末尾」：那会让译文插进**原文中间**，而 commitText
        //    不保证可撤销（这是防「插进中间」的唯一一道网）；
        //  · 只认 '\n' 会让 CR-only / U+2028 文档里的「整行 / 整篇」在**已付费之后**被判成
        //    「落点不对」而拒绝（L-499 扩了行分隔符，那一处当时没跟上）。
        assertTrue(
            "L-564：落点判据必须用 TranslationText.isLineBreak（不得再只认 '\\n'）",
            "TranslationText.isLineBreak(tail[0])" in ime,
        )
        assertTrue(
            "L-564：tail 为 null 时必须判「不是末尾」（三态不能塌缩成两态）",
            "tail != null && (tail.isEmpty() || TranslationText.isLineBreak(tail[0]))" in ime,
        )
        assertTrue(
            "isLineBreak 必须由 TranslationText 暴露（行分隔符只留一处判据）",
            "internal fun isLineBreak(c: Char): Boolean = c in LINE_BREAKS" in codeOf("Translation.kt"),
        )

        // 2026-10-03 第十四轮审查纠正的一处过度保守：`getSelectedText` 返回 **null 是官方契约里的
        // 「没有选区」**，必须放行 —— 走到这一步的都是「拿不到精确选区」的宿主（WebView 类），
        // 且本段注释自己写着**每次翻译都会走到这里**；把 null 判成「问不出来」会让这些宿主上的
        // 默认档（appendOffset = 0）100% 被拒 —— 把「可用」改成「不可用」。
        // 真正的危险面（有选区 + appendOffset > 0）已在发请求前被 ②.b 的闸拒掉。
        assertTrue(
            "getSelectedText 的 null 必须按「无选区」放行（不得判成 HOST_UNREADABLE）",
            "if (selected != null && selected.isNotEmpty()) {" in ime &&
                "getSelectedText 返回 null（官方契约 = 无选区），按「无选区」放行" in ime,
        )

        // L-575（2026-10-03 第十五轮）：**协议级 hex 必须显式 Locale.US** —— `"%02x".format(it)` 走
        // `String.format` 的默认 Locale，而 `Formatter` 会把 `%x` 里的 0-9 本地化 ⇒ 在 `ar-EG` /
        // `fa-IR` / `bn-*` / `mr-*` / `ne-*` 等区域下，字节摘要里混入本地数字：
        //  · 百度签名 ⇒ 与对端按 ASCII hex 重建的不等 ⇒ 54001 ⇒ 归 AUTH（「请检查凭据」）——
        //    凭据完全正确而用户怎么查都没用，该区域整家失败；
        //  · 备份包 SHA-256（写入侧与校验侧）⇒ 跨区域导入时合法备份被判「校验失败」拒绝导入；
        //  · 词库校验和 ⇒ 包被判损坏；剪贴板去重哈希 ⇒ 切换系统语言后不再匹配（重复入库）。
        for (f in listOf(
            "BaiduTranslator.kt",
            "ClipboardDb.kt",
            "ConfigBackup.kt",
            "ConfigBackupManager.kt",
            "OptionalDicts.kt",
        )) {
            assertTrue(
                "L-575：$f 的 hex 不得再用「\"%02x\".format(...)」（受默认 Locale 影响）",
                "\"%02x\".format(" !in codeOf(f) &&
                    "String.format(java.util.Locale.US, \"%02x\", it)" in codeOf(f),
            )
        }
        // L-578：三个翻译页必须声明 singleTop（默认 standard 会在连点入口时叠出两份实例）
        // ⚠ `TestSources` 只认 `.kt`（短名映射），xml 要用直接路径（工作目录 = 模块目录）
        val manifest = File("src/main/AndroidManifest.xml")
            .let { if (it.isFile) it else File("app/src/main/AndroidManifest.xml") }
            .readText()
        for (page in listOf(
            "TranslationSettingsActivity",
            "OpenAiSettingsActivity",
            "TranslationSourceActivity",
        )) {
            assertTrue(
                "L-578：$page 必须声明 android:launchMode=\"singleTop\"",
                Regex("android:name=\"\\.$page\"[\\s\\S]{0,200}?android:launchMode=\"singleTop\"")
                    .containsMatchIn(manifest),
            )
        }

        // ── 第六轮修复（2026-10-03）：出口分类 / 看门狗派生 / 取消可见性 ──────────────
        // （`client` 复用本方法前段已有的声明：L-483 那条守卫就在这里取的 TranslationClient 源码）

        // L-600：3xx 必须单列成 REDIRECT。重定向是**刻意关闭**的（L-306），所以"打开重定向"
        // 不是选项 —— 缺的是出路：不做这条断言，3xx 会被顺手并回 SERVER（与 5xx 同归），
        // 用户看到的仍是「服务异常，请稍后重试」，永远不会去改 Base URL。
        assertTrue(
            "L-600 守卫缺失：3xx 必须归 REDIRECT（不得与 5xx 同归 SERVER）",
            "TranslationError.REDIRECT" in client && "it.code in 300..399" in client,
        )
        // L-601：2xx + 非 JSON（门户页 / MITM 代理）必须归 NETWORK，而不是 SERVER。
        assertTrue(
            "L-601 守卫缺失：2xx 非 JSON 响应必须归 NETWORK（门户页/代理拦截）",
            "looksLikeJson" in client && "TranslationError.NETWORK" in client,
        )
        // L-604：取消不是网络故障 —— `call.isCanceled()` 必须排在异常分类之前。
        assertTrue(
            "L-604 守卫缺失：onFailure 必须先判 call.isCanceled()（否则取消被记成 NETWORK）",
            "call.isCanceled()" in client,
        )
        // L-606：401/403 要能分辨「网关拦 UA」与「Key 无效」。
        // ⚠ 取值必须走 headerBrief（L-620 修复后这里是新形态）：直接 take(N) 会把服务端
        // 回显的凭据（含 32 位纯 hex）写进落盘日志 —— 见下面 L-620 那条守卫。
        assertTrue(
            "L-606 守卫缺失：401/403 日志必须补 ua / server 摘要（且经 headerBrief 取值）",
            "ua=\${headerBrief(" in client && "server=\${headerBrief(" in client,
        )
        // L-602：看门狗必须**派生**自超时上限（硬编码会让两者静默失配）。
        assertTrue(
            "L-602 守卫缺失：看门狗必须派生自 Prefs.TIMEOUT_MAX_SEC，不得硬编码",
            "(Prefs.TIMEOUT_MAX_SEC + 30) * 1000L" in ime,
        )
        // L-603：会话边界取消要有用户可见提示（且只在该提示的入口传 true）。
        assertTrue(
            "L-603 守卫缺失：会话边界取消在途翻译必须提示用户",
            "cancelTranslate(notify = true)" in ime && "toast(TEXT_TRANSLATE_CANCELLED)" in ime,
        )

        // ── 第十八轮审查的修复（2026-10-03）：状态机缺口 ────────────────────────────
        // ⚠⚠ L-603 的**回归**（本人上一轮引入）：`onFinishInput` **先于** onStartInput /
        // onStartInputView 执行，并且**在它里面**就把 `translateInFlight` 复位了 —— 上一轮
        // 那里传的是默认 `notify = false`（理由是"留给后面的入口弹"），而后面两处取到的
        // `wasInFlight` 恒为 false ⇒「翻译已取消」在**同 App 换输入框**（最高频边界）
        // 这条路径上**永不弹**，而它正是 L-603 要关掉的那件事。
        // ⇒ 判据：**每个会复位 flag 的边界入口都必须自己带 notify**，不得靠后面的入口补弹。
        // ⚠ 不用「计数 == N」（L-726：计数型守卫会让「修得更好」也变红），也**不用字符窗口**：
        // 2026-10-03 审查 L-731 实测窗口法的两个致命失效 ——
        // ① `commit` 的**注释正文**里就写着「用 `cancelTranslate(notify = true)` 而不是拒绝」，
        //    删掉真闸门断言照样绿（注释即守卫）；② 切窗锚点 `\n    private fun ` 会把相邻函数
        //    的那一处算进来，于是 onBackspace / onStartInput / switchToVoiceKeyboard 三条都在
        //    窗口里「借」了别处的闸门通过。改用本文件已有的 `blockAfter`（花括号配对）。
        // 会话边界 4 个 + 改宿主文本的入口 7 个（见下），都必须自己带 notify=true。
        for ((sig, label) in listOf(
            "override fun onFinishInput(" to "onFinishInput",
            "override fun onStartInput(" to "onStartInput",
            "override fun onStartInputView(" to "onStartInputView",
            "override fun onFinishInputView(" to "onFinishInputView",
            "private fun switchToVoiceKeyboard(" to "switchToVoiceKeyboard（切键盘）",
            "override fun onOpenClipboard(" to "onOpenClipboard（打开剪贴板面板）",
            "private fun commit(" to "commit（打字/候选/空格/预测的唯一出口）",
            "override fun onBackspace(" to "onBackspace（拼音面板退格）",
            "private fun deleteAllText(" to "deleteAllText（清空）",
            "private fun pasteClipboard(" to "pasteClipboard（功能面板粘贴）",
            "private fun pasteClipboardTextInternal(" to "pasteClipboardTextInternal（面板条目粘贴）",
            "private fun performEnter(" to "performEnter（回车把原始拼音上屏，不经 commit）",
            "private fun bindBackspace(" to "bindBackspace（语音面板退格，不经 commit）",
        )) {
            assertTrue(
                "$label 必须自己带 notify=true 的取消（不能靠后面的入口补弹）",
                "cancelTranslate(notify = true)" in blockAfter(ime, sig),
            )
        }
        // 第 6 处必须落在 onOpenClipboard 体内（2026-10-03 修复 L-688）：翻译在途时打开面板，
        // 点任意一条就是 commitText 改宿主文本 ⇒ 已付费的译文必然作废，而提示还说「输入已变化」。
        // 「打开面板」不是会话边界，所以不能靠上面五个入口兜住。
        // L-688 的「onOpenClipboard 必须自己取消」已由上面列表里的 `onOpenClipboard` 一条覆盖
        // （2026-10-03 修复 L-731：原先那段用「下一个 override fun」切窗，同样吃注释），
        // 这里不再重复钉 —— 两处判据同源才不会出现「一条绿一条红」的矛盾。
        run {
            val openAt = ime.indexOf("override fun onOpenClipboard(")
            assertTrue("找不到 onOpenClipboard 的定义", openAt >= 0)
            assertTrue(
                "L-688 守卫缺失：onOpenClipboard 必须在翻译在途时取消请求（面板粘贴会改宿主文本）",
                "cancelTranslate(notify = true)" in blockAfter(ime, "override fun onOpenClipboard("),
            )
        }
        assertTrue(
            "onFinishInput 必须带 notify：它先复位 translateInFlight，后面的入口已无从判断",
            Regex("override fun onFinishInput\\(\\)[\\s\\S]{0,1200}?cancelTranslate\\(notify = true\\)")
                .containsMatchIn(ime),
        )
        // A2：视图态重放必须绑在「**视图创建**」这件事上（onCreateInputView），
        // 而不是绑在某个调用点 —— 框架自己调 onCreateInputView() 重建输入视图时，
        // 不走 recreateKeyboardView，只在那里重放就会让新视图的按钮变成"可点的翻译"，
        // 而 IME 侧闸门还关着 ⇒ 点击被静默吞掉（无提示、无日志）。
        // ⚠ 用**位置比较**而不是"起点后 N 字符内包含"：后者依赖函数体长度（加几行注释/代码
        // 就会失效，是又一种脆钉）。这里断言「**第一次**重放出现在 onCreateInputView 与
        // recreateKeyboardView 这两个函数之间」—— 即视图创建的路径上就有重放。
        run {
            val createAt = ime.indexOf("override fun onCreateInputView")
            val replayAt = ime.indexOf("pinyinKeyboard?.setTranslating(translateInFlight)")
            val recreateAt = ime.indexOf("private fun recreateKeyboardView")
            assertTrue(
                "视图态重放必须放进 onCreateInputView（框架自己重建视图时也要覆盖）；" +
                    "createAt=$createAt replayAt=$replayAt recreateAt=$recreateAt",
                createAt >= 0 && replayAt > createAt && (recreateAt < 0 || replayAt < recreateAt),
            )
        }
        // A3：总开关闸门必须留痕（按钮在面板上还是亮的、可点的，点下去不能毫无记录）。
        assertTrue(
            "总开关关闭时的点击必须留日志（否则诊断包无法回溯这次点击）",
            "总开关已关闭，忽略本次点击" in ime,
        )

        // ── 第七轮修复（2026-10-03）：凭据回显 / 端点可辨 / 关联 ID / 提交字段 ────────────
        // L-620（安全，**优先于本批其它项**）：三个服务端可控头（Content-Type / User-Agent /
        // Server）必须走 headerBrief —— 不可信网关可以把收到的 `Authorization: Bearer <key>`
        // 回显在里面，而脱敏表**刻意不含**「32~64 位纯 hex」（Azure 订阅密钥 / 百度 SecretKey
        // 的形态）⇒ 裸 take(N) 会把用户凭据写进落盘日志并随诊断包外发。
        assertTrue(
            "L-620 守卫缺失：服务端可控头必须走 headerBrief（含纯 hex 凭据负判据）",
            "private fun headerBrief" in client &&
                "HEX_CREDENTIAL_RE" in client &&
                "ct=\${headerBrief" in client &&
                "ua=\${headerBrief" in client &&
                "server=\${headerBrief" in client,
        )
        // L-619：发起日志必须带端点 host（六家里 OpenAiTranslator 一类覆盖无数 baseUrl 组合）。
        assertTrue(
            "L-619 守卫缺失：发起日志必须记录端点 host + path（且不得记 query）",
            "@\${request.url.host}\${request.url.encodedPath}" in client &&
                "encodedQuery" !in client,
        )
        // L-615：关联 ID 必须能穿过 Client 边界（形参默认值 ⇒ 既有调用点与测试不用改）。
        assertTrue(
            "L-615 守卫缺失：translate 必须有带默认值的 traceId 形参 + 日志前缀函数",
            "traceId: String = \"\"," in client && "private fun traceTag" in client,
        )
        assertTrue(
            "L-615 守卫缺失：IME 侧必须生成并传递 traceId",
            "translateTraceId = Diagnostics.traceId(\"TR\")" in ime &&
                "traceId = translateTraceId" in ime,
        )
        // L-617：看门狗日志必须带上下文（provider / target / 已等时长）。
        assertTrue(
            "L-617 守卫缺失：看门狗日志必须带 provider/target/elapsed",
            "看门狗超时，强制收尾" in ime &&
                "provider=\$translateProviderName target=\$translateTargetName" in ime &&
                "translateStartedAtMs" in ime,
        )
        // L-618：用户看得到的「已取消」，日志里也必须查得到（含是否提示）。
        assertTrue(
            "L-618 守卫缺失：会话边界取消在途请求时必须写日志",
            "会话边界取消在途请求" in ime,
        )
        // L-616：提交日志必须带落点与**统一长度口径**（原先成功记净化前长度、失败记实发串）。
        assertTrue(
            "L-616 守卫缺失：提交成功日志必须带 append/replace/landed 且长度用实发串",
            "append=\${snapshot.appendOffset} replace=\${snapshot.replaceSelection}" in ime &&
                "landed=\${landed?.let" in ime &&
                "len=\${submit.length}" in ime,
        )
        // L-613：在途翻译**不得**再进入换肤的延后判据（否则改主题最长要等 330s 才生效；
        // 引入它的理由已被「视图重放在 onCreateInputView」消解）。
        // 判据取 `hasActiveOverlay` 之后的**一小段窗口**（getter 很短），比按行正则更稳。
        run {
            val kb = codeOf("PinyinKeyboardView.kt")
            val at = kb.indexOf("val hasActiveOverlay")
            assertTrue("L-613 守卫缺失：找不到 hasActiveOverlay", at >= 0)
            val window = kb.substring(at, minOf(kb.length, at + 220))
            assertTrue(
                "L-613 守卫缺失：hasActiveOverlay 不得再含 translateInFlight（否则换肤被延后到会话结束）",
                "translateInFlight" !in window,
            )
        }

        // ── 第八轮修复（2026-10-03）：成本治理 / 按档取证 / 生命周期 ─────────────────────
        // L-628（资金 + 数据一致性）：译文追加后光标停在插入文本末尾，默认档取「光标前本行」⇒
        // 再点一次会把**刚写入的译文**再翻一遍（二次付费 + 回译污染正文）。
        // ⚠ 这三条守卫在 2026-10-03 被重写：原版钉的是 `slice.text == lastSentText` 与
        // `lastSentText = snapshot.sent` 两个**字面量** —— 把记账挪到提交之前、或把判据换成
        // 不含维度的实现，它照样全绿（这正是 L-655 / L-656 能合进来的原因）。
        // 现版钉「记账点在落地之后」（顺序判据）+「判据是含维度的指纹」（行为断言）。
        assertTrue(
            "L-628 守卫缺失：同一段文本的重复点击必须被拦下（判据用含维度的请求指纹）",
            "repeatKey == lastSentKey" in ime && "TEXT_TRANSLATE_REPEATED" in ime,
        )
        // 记账必须排在 appendTranslation **之后** —— 落地失败（宿主变敏感框 / 选区变化 / InputFilter
        // 丢弃）时用户一个字没拿到，30s 内的重试不该被自己的去重挡回去（L-656）
        run {
            val appendAt = ime.indexOf("appendTranslation(connection, snapshot, outcome.text)")
            val recordAt = ime.indexOf("lastSentKey = repeatKey")
            assertTrue("L-656 守卫缺失：找不到记账点或 appendTranslation 调用", appendAt >= 0 && recordAt >= 0)
            assertTrue(
                "L-656 违反：去重记账必须排在 appendTranslation 之后（落地失败不该被记成「已翻过」）",
                recordAt > appendAt,
            )
        }
        // 指纹必须带服务方 / 目标语言 / 原文范围：换了其中任何一项都是**另一次合法请求**（L-655）
        assertEquals(
            "请求指纹必须随目标语言变化（否则换语言后翻同一段会被误拦成「刚刚翻译过」）",
            false,
            translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.ENGLISH,
                openAiTarget = "",
                TranslationScope.LINE_BEFORE,
                "hello",
            ) == translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.JAPANESE,
                openAiTarget = "",
                TranslationScope.LINE_BEFORE,
                "hello",
            ),
        )
        assertEquals(
            "请求指纹必须随原文范围变化（否则改范围后翻同一段会被误拦）",
            false,
            translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.ENGLISH,
                openAiTarget = "",
                TranslationScope.LINE_BEFORE,
                "hello",
            ) == translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.ENGLISH,
                openAiTarget = "",
                TranslationScope.ALL,
                "hello",
            ),
        )
        assertEquals(
            "请求指纹必须随服务方变化",
            false,
            translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.ENGLISH,
                openAiTarget = "",
                TranslationScope.LINE_BEFORE,
                "hello",
            ) == translateRepeatKey(
                TranslationProviderId.DEEPL,
                TranslationLanguage.ENGLISH,
                openAiTarget = "",
                TranslationScope.LINE_BEFORE,
                "hello",
            ),
        )
        assertEquals(
            "同一请求（服务方+语言+范围+原文全同）必须命中同一指纹，否则去重永不生效",
            true,
            translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.ENGLISH,
                openAiTarget = "",
                TranslationScope.LINE_BEFORE,
                "hello",
            ) == translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.ENGLISH,
                openAiTarget = "",
                TranslationScope.LINE_BEFORE,
                "hello",
            ),
        )
        // L-787：OpenAI 兼容路径真正生效的是**自由文本**目标语言 ⇒ 指纹必须随它变化；
        // 而非 OpenAI 服务方忽略该字段（指纹仍只随枚举语言变），否则会凭空多出一维噪声
        assertEquals(
            "L-787：OpenAI 路径改自由文本目标语言后指纹必须变化（否则第二次付费请求被误拦）",
            false,
            translateRepeatKey(
                TranslationProviderId.OPENAI,
                TranslationLanguage.ENGLISH,
                openAiTarget = "日本語",
                TranslationScope.LINE_BEFORE,
                "hello",
            ) == translateRepeatKey(
                TranslationProviderId.OPENAI,
                TranslationLanguage.ENGLISH,
                openAiTarget = "English",
                TranslationScope.LINE_BEFORE,
                "hello",
            ),
        )
        assertEquals(
            "L-787：非 OpenAI 服务方不受自由文本影响（指纹仍只随枚举语言变）",
            true,
            translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.ENGLISH,
                openAiTarget = "日本語",
                TranslationScope.LINE_BEFORE,
                "hello",
            ) == translateRepeatKey(
                TranslationProviderId.ALIYUN,
                TranslationLanguage.ENGLISH,
                openAiTarget = "",
                TranslationScope.LINE_BEFORE,
                "hello",
            ),
        )
        assertEquals(
            "L-787：日志与指纹口径必须是生效语言（含 trim），不是枚举名",
            "openai:日本語",
            effectiveTargetLabel(TranslationProviderId.OPENAI, TranslationLanguage.ENGLISH, " 日本語 "),
        )
        assertEquals(
            "L-787：自由文本留空必须归一到默认语言（与请求组装同源，不许各写一份）",
            "openai:${OpenAiTranslator.DEFAULT_TARGET_LANGUAGE}",
            effectiveTargetLabel(TranslationProviderId.OPENAI, TranslationLanguage.JAPANESE, "   "),
        )
        // 换输入框必须清指纹：A 框翻过的文本在 B 框里不该被拦（提示让用户「改动原文」在这里无效）
        assertTrue(
            "L-655 守卫缺失：onStartInput 必须清空去重指纹（跨输入框误拦）",
            "lastSentKey = \"\"" in ime,
        )
        // ── 第二十四轮修复（2026-10-03）：回归面 + 交叉面 ──────────────────────────
        // L-702：凭据清洗必须按 Unicode 类别剥，而不是枚举 6 个码位（okhttp 只拒 C0 与 DEL，
        // 而 U+202F / U+2007 / U+180E / U+00AD / U+2061 既不抛异常也不该留在头里）
        for (raw in listOf("abc\u00A0def", "abc\u202Fdef", "abc\u007Fdef", "abc\u180Edef", "abc\u00ADdef")) {
            assertEquals(
                "凭据里的不可见 / 非法头值字符必须被剥掉：${raw.map { it.code.toString(16) }}",
                "abcdef",
                raw.cleanCredential(),
            )
        }
        assertEquals(
            "凭据**内部**的普通空格必须保留（Base URL 与区域名里合法；首尾空白由 trim 负责）",
            "https://host/v1/chat",
            "  https://host/v1/chat  ".cleanCredential(),
        )
        // L-707：appendText 的行判据必须与 LINE_BREAKS 同源（否则 CR-only / U+2028 文档里
        // 译文前多出一个空行）—— 两条行为断言，现状无任何守卫
        assertEquals(
            "光标前是 CR 时不该补前导换行（CR-only 文档）",
            "译文",
            TranslationText.appendText('\r', "译文"),
        )
        assertEquals(
            "光标前是 U+2028 时不该补前导换行",
            "译文",
            TranslationText.appendText('\u2028', "译文"),
        )
        assertEquals(
            "光标前是普通文字时仍要补前导换行（不得把这条判据放宽过头）",
            "\n译文",
            TranslationText.appendText('字', "译文"),
        )
        // L-704：不可见全集缺口（纯这些字符构成的原文不得判成「有内容」，否则白发一次计费请求）
        for (raw in listOf("\u180E", "\u3164", "\u034F", "\uD800")) {
            assertFalse(
                "该码位必须算「不可见」：U+${raw[0].code.toString(16).uppercase()}",
                raw.hasVisibleContent(),
            )
        }
        // L-703 + L-739：写超时必须**基线与两处派生都**设。
        // ⚠ 不用「精确计数 == 2」（L-726）：给基线补上更彻底的修法反而会让它变红 ——
        // 那种守卫会把「修得更好」判成失败。改成「基线块里有」+「总数 ≥ 3」双向判据。
        assertTrue(
            "L-739 守卫缺失：基线 client 必须显式设 writeTimeout" +
                "（OkHttp 默认只有 10s，而 callTimeoutSec=0 的四家走的就是基线）",
            "writeTimeout(" in blockAfter(client, "private val http: OkHttpClient by lazy"),
        )
        assertTrue(
            "L-703 守卫缺失：translate 与 fetchModels 两处派生也必须带 writeTimeout",
            Regex("""\.writeTimeout\(""").findAll(client).count() >= 3,
        )
        // L-701：自检请求必须与翻译请求同源（带自定义头）
        assertTrue(
            "L-701 守卫缺失：fetchModels 必须接受并注入自定义请求头",
            "extraHeaders: List<Pair<String, String>> = emptyList()" in client &&
                "extraHeaders.forEach { (name, value) -> builder.header(name, value) }" in client,
        )
        assertTrue(
            "L-701 守卫缺失：调用处必须把已保存的自定义头传进去",
            codeOf("OpenAiSettingsActivity.kt").let {
                "OpenAiTranslator.parseHeaders(prefs.openAiExtraHeaders)" in it
            },
        )
        // L-705：协议级头一律不接纳（填了 Accept-Encoding 会关掉 OkHttp 的透明解压）
        // L-590 残余（2026-10-04 补）：content-type 同样要被覆盖 ⇒ 一并拒；并把**被丢弃的头名**
        // 记进日志 —— 静默丢弃时用户「看着配置生效、实际没发出去」，只能靠猜。
        assertTrue(
            "L-705 守卫缺失：自定义头必须跳过客户端掌管的协议级头",
            "CLIENT_OWNED_HEADERS" in codeOf("OpenAiTranslator.kt") &&
                "accept-encoding" in codeOf("OpenAiTranslator.kt"),
        )
        assertTrue(
            "L-590 守卫缺失：content-type 必须进拒绝名单（会被 BridgeInterceptor 覆盖）",
            "\"content-type\"" in codeOf("OpenAiTranslator.kt"),
        )
        assertTrue(
            "L-589 守卫缺失：被丢弃的自定义头必须记一条日志（此前是静默丢弃）",
            "自定义请求头被丢弃" in codeOf("OpenAiTranslator.kt"),
        )
        // L-667 / L-668：凭据形态与脱敏表
        // ⚠ `looksLikeCredential` 是 companion 内的**成员扩展函数**，不能 `TranslationClient.looksLikeCredential(…)`
        // 那样调，必须进 companion 作用域（`TranslationClientTest` 已有同款写法）。
        with(TranslationClient) {
            assertTrue(
                "20~31 位的纯小写字母数字串必须被当成凭据形态（DeepL Pro 形态）",
                "0a1b2c3d-4e5f-6789-abcd-ef0123456789".looksLikeCredential(),
            )
            assertTrue(
                "L-667 误判防护：真实错误码不得被形态判据吞掉",
                !"InvalidAccessKeyIdNotFound".looksLikeCredential() &&
                    !"SignatureDoesNotMatch".looksLikeCredential() &&
                    !"InvalidTimeStamp.Expired".looksLikeCredential(),
            )
        }
        val diag = codeOf("Diagnostics.kt")
        assertTrue(
            "L-668 守卫缺失：脱敏表必须覆盖 api_key / signature / access_token / userinfo / 非 Bearer 授权头",
            "api_key" in diag && "access_token" in diag && "proxy-authorization" in diag &&
                // 源码里是正则字面量，`\\s` 是**两个字符**（反斜杠 + s），所以三引号里也要写两个
                """https?://[^/\\s:@]+:[^/\\s@]+@""" in diag,
        )
        // L-665：自动保存路径也必须消费 unpersistedCredentialKeys
        assertTrue(
            "L-665 守卫缺失：自动保存路径（saveCredentials）必须消费 unpersisted 并提示",
            "unpersistedCredentialKeys()" in codeOf("TranslationSettingsActivity.kt") &&
                "TEXT_SAVE_NOT_PERSISTED" in codeOf("TranslationSettingsActivity.kt"),
        )
        // ── 第二十六轮修复（2026-10-03）：收敛面 + 半扇门 ──────────────────────────
        // L-715：提示词 / 配置名 / 目标语言必须走**宽松**清洗（保留换行），否则多行提示词被压平，
        // 且「值 == 默认值就删键」失效 ⇒ 恢复默认后变成显式写键 ⇒ 将来改默认值这批用户拿不到新值。
        assertEquals(
            "宽松清洗必须保留换行（提示词本就是多行文本）",
            "第一行\n{{text}}",
            "第一行\n{{text}}".cleanPromptText(),
        )
        assertEquals(
            "宽松清洗仍要剥零宽族与 DEL",
            "ab",
            "a\u200B\u007Fb".cleanPromptText(),
        )
        // ⚠ 逐键断言，不数「出现次数」（L-734：数次数既不绑键名，也会让「把 URL 误改成 multiline」
        // 这类错误仍然全绿）
        // ⚠ 这些 setter 是**单行表达式**而不是函数定义，所以不能用 `blockAfter`——
        // 它会往后找第一个 `{` 而跨进别的函数里。按**行**定位才准确。
        val prefsSrc = codeOf("Prefs.kt")
        fun setterLineOf(key: String): String? =
            prefsSrc.lineSequence().firstOrNull { "putDefaulted($key" in it }
        for (key in listOf("KEY_OPENAI_SYSTEM_PROMPT", "KEY_OPENAI_USER_PROMPT",
            "KEY_OPENAI_TARGET_LANGUAGE", "KEY_OPENAI_NAME")) {
            val line = setterLineOf(key)
            assertTrue("L-715 守卫缺失：找不到 $key 的 setter 行（改名了？）", line != null)
            assertTrue(
                "L-715 守卫缺失：$key 是多行文本字段，必须声明 multiline = true",
                "multiline = true" in line!!,
            )
        }
        for (key in listOf("KEY_OPENAI_BASE_URL", "KEY_OPENAI_CHAT_PATH",
            "KEY_OPENAI_MODELS_PATH", "KEY_OPENAI_RESPONSE_PATH")) {
            val line = setterLineOf(key)
            assertTrue("L-715 回归防护：找不到 $key 的 setter 行（改名了？）", line != null)
            assertTrue(
                "L-715 回归防护：$key 要进 URL 与 HTTP 头，必须走严格版（不得声明 multiline）",
                "multiline" !in line!!,
            )
        }
        // L-732：宽松清洗必须 trim，否则「值 == 默认值就删键」在带首尾空白时失效
        assertEquals(
            "宽松清洗必须 trim（否则备份导入带空白的值不会被判为「等于默认值」而删键）",
            "第一行\n{{text}}",
            "  第一行\n{{text}}  ".cleanPromptText(),
        )
        // L-720：数字 / 日期类输入框在发请求前就拦（否则译文必被 InputFilter 整段丢弃）
        val privacy = codeOf("InputFieldPrivacy.kt")
        assertTrue(
            "L-720 守卫缺失：必须有独立的「数字/日期类不翻译」判据",
            "fun rejectsTranslationText(" in privacy &&
                "TYPE_CLASS_NUMBER" in privacy && "TYPE_CLASS_DATETIME" in privacy,
        )
        assertEquals(
            "数字类输入框必须被判为「不接受译文」",
            true,
            InputFieldPrivacy.rejectsTranslationText(android.text.InputType.TYPE_CLASS_NUMBER),
        )
        assertEquals(
            "日期类输入框必须被判为「不接受译文」",
            true,
            InputFieldPrivacy.rejectsTranslationText(
                android.text.InputType.TYPE_CLASS_DATETIME or
                    android.text.InputType.TYPE_DATETIME_VARIATION_DATE,
            ),
        )
        assertEquals(
            "普通文本框不得被这条判据拦下（浏览器与聊天框全靠它）",
            false,
            InputFieldPrivacy.rejectsTranslationText(
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            ),
        )
        assertEquals(
            "EditorInfo 缺失时不得拦（读不到原文时后面自然会报「读不到」）",
            false,
            InputFieldPrivacy.rejectsTranslationText(null),
        )
        assertTrue(
            "L-720 守卫缺失：startTranslate 必须在发请求前用上该判据并给专属文案",
            "rejectsTranslationText(info.inputType)" in ime && "TEXT_TRANSLATE_NUMERIC_FIELD" in ime,
        )
        // L-717：显式保存路径必须让 saveCredentials 闭嘴（否则两处 toast 互相打脸）
        // L-740：回车的三条分支都改宿主文本，闸门必须统一在函数开头一次
        // （上一轮只补了「原始拼音上屏」那一条，另两条仍会静默作废已付费译文）
        val enterBlock = blockAfter(ime, "private fun performEnter(")
        val enterGateAt = enterBlock.indexOf("cancelTranslate(notify = true)")
        assertTrue("L-740 守卫缺失：performEnter 必须有取消闸门", enterGateAt >= 0)
        assertTrue(
            "L-740 回归防护：闸门必须排在三条分支之前（提到函数开头）",
            enterGateAt < enterBlock.indexOf("performEnter(原始按键上屏)"),
        )
        // L-743：onResponse 也必须判 isCanceled（与 onFailure 对称，否则取消后出幽灵失败日志）
        // 4 = onFailure 两处（原有）+ onResponse 两处（L-743 补齐）：两条链各 2 个 callback
        assertEquals(
            "isCanceled 守卫必须四条回调链各一处（onFailure × 2 + onResponse × 2）",
            4,
            client.split("if (call.isCanceled())").size - 1,
        )
        // 结构化判据改用「守卫里那行日志文案」计数：它只出现在 L-743 新加的两处；
        // 而「{ 后紧跟 if」的正则形态在 codeOf 剥注释后会因残留空行而不稳（试过，不可靠）
        assertEquals(
            "onResponse 两处都必须有「响应到达时已被取消」这条守卫日志（该文案只在新加的两处）",
            2,
            client.split("响应到达时已被取消").size - 1,
        )
        val openAiSrc = codeOf("OpenAiSettingsActivity.kt")
        assertTrue(
            "L-741 守卫缺失：OpenAI 设置页 onPause 必须消费 unpersistedCredentialKeys",
            "unpersistedCredentialKeys()" in blockAfter(openAiSrc, "override fun onPause(") &&
                "TEXT_SAVE_NOT_PERSISTED" in openAiSrc,
        )
        val trSettings = codeOf("TranslationSettingsActivity.kt")

        // ── 词库下载链路：安全接线必须有守卫（L-802）────────────────────────────────
        // 此前把 `matchesChecksum(...)` 删掉、直接 `tmp.renameTo(dst)` 也能全绿：checksum 测试只测纯函数、
        // 不测接线，而 `MAX_DOWNLOAD_BYTES` 与 `copyCapped` 在整个测试目录零命中。
        run {
            val dictSrc = codeOf("DictManagerActivity.kt")
            val fetch = blockAfter(dictSrc, "private fun fetchToFile(")
            val verifyAt = fetch.indexOf("matchesChecksum")
            val renameAt = fetch.indexOf("tmp.renameTo(dst)")
            assertTrue("L-802 守卫缺失：fetchToFile 里找不到 matchesChecksum 调用", verifyAt >= 0)
            assertTrue("L-802 守卫缺失：fetchToFile 里找不到 tmp.renameTo(dst)", renameAt >= 0)
            assertTrue(
                "L-802 违反：SHA-256 校验必须早于改名（否则「校验后才收下」这个属性形同虚设）",
                verifyAt < renameAt,
            )
            assertTrue(
                "L-802 守卫缺失：校验失败必须删临时件（不得留下半个文件）",
                "tmp.delete()" in fetch,
            )
            val copy = blockAfter(dictSrc, "private fun copyCapped(")
            assertTrue(
                "L-802 守卫缺失：copyCapped 必须有大小上限判定（否则异常响应能写满用户存储）",
                "MAX_DOWNLOAD_BYTES" in copy,
            )
        }
        // L-797：下载线程必须降后台优先级（与其它后台重活线程同口径，守卫已存在但漏了这条链路）
        assertTrue(
            "L-797 守卫缺失：词库下载线程必须降后台优先级（默认优先级会与前台输入争 CPU/IO）",
            "THREAD_PRIORITY_BACKGROUND" in codeOf("DictManagerActivity.kt"),
        )
        // 2026-10-03 用户要求：词库卡片删掉「体积 / 加载说明」三行，且安装状态与按钮**同一行**
        run {
            val dict = codeOf("DictManagerActivity.kt")
            val card = blockAfter(dict, "private fun buildCard(")
            for (gone in listOf("dict_load_timing", "dict_startup_instant", "体积 %.1f MB")) {
                assertTrue(
                    "词库卡片不该再显示「$gone」（用户要求删掉这三行）",
                    gone !in card,
                )
            }
            assertTrue(
                "安装状态必须与按钮同处一个 row（weight=1 的状态 + 按钮），不能拆成两行",
                "LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)" in card &&
                    card.indexOf("dict_status_absent") in 0 until card.indexOf("card.addView(row, matchWrap(top = 12))"),
            )
            // 那两句加载说明从卡片搬到了页面顶部（四句提示），四句都要在
            val page = codeOf("DictManagerActivity.kt")
            for (res in listOf(
                "R.string.dict_manager_hint_line1",
                "R.string.dict_manager_hint_line2",
                "R.string.dict_load_timing",
                "R.string.dict_startup_instant",
            )) {
                assertTrue("页面顶部四句提示缺一句：$res", res in page)
            }
        }
        // L-800：下载完成的 UI 回调必须兜住（其中 refreshList 在主线程做文件 IO）。
        // ⚠ 2026-10-03 修复 L-812 把判据从「整段包住」升级为「**刷界面那一段**包住」——
        // 整段包住会把生效动作 `restartImeForDict` 一起吞掉（一次刷新异常 ⇒ 词库下完却不生效）。
        // 判据从「日志文案字面量」改成位置比较：文案会改，结构不会。
        run {
            // 锚点取「download 函数体内的第一个 runOnUiThread」：页面里还有别的 runOnUiThread
            // （自定义词库导入的回调），按全文第一个取会锚到那段、判据随锚点漂走
            val cb = blockAfter(
                blockAfter(codeOf("DictManagerActivity.kt"), "private fun download("),
                // MEM-11 起收尾走静态 Handler（线程不再持有 Activity），锚点随之改名；判据不变
                "Handler(Looper.getMainLooper()).post {",
            )
            val caught = cb.indexOf("runCatching")
            val refresh = cb.indexOf("page.refreshList()")
            val failed = cb.indexOf(".onFailure")
            assertTrue(
                "L-800 守卫缺失：下载完成的 UI 刷新必须包 runCatching（一次刷新异常会杀 IME 进程）",
                caught >= 0 && refresh > caught && failed > refresh,
            )
        }
        // L-796：候选条目渲染必须有缓存判据（每键全量重建 ≈900 对象/秒）
        run {
            val kb = codeOf("PinyinKeyboardView.kt")
            val render = blockAfter(kb, "private fun renderCandidateItems(")
            assertTrue(
                "L-796 守卫缺失：候选渲染入口必须比对指纹后再重建",
                "candidateRenderKey" in render,
            )
            assertTrue(
                "L-796 回归防护：外观刷新必须重置指纹，否则换肤后候选留着旧字距与旧颜色",
                "resetCandidateRender()" in blockAfter(kb, "fun refreshAppearance()"),
            )
            assertTrue(
                "L-796 回归防护：换肤必须重置指纹，否则候选留着旧颜色",
                "resetCandidateRender()" in blockAfter(kb, "private fun syncKeyboardSkin()"),
            )
            // L-809：候选容器被**五条旁路**共用（功能面板 / 密码数字条 / 符号分组 / 引擎加载提示 / 空态），
            // 每一处清空都必须复位指纹 —— 否则「输入 ni → ✕ 清空 → 再输入 ni」时候选栏停在功能面板，
            // 点那些按钮会真的执行动作（历史 / 设置 / 翻译…），候选无法点选上屏。
            for (fn in listOf(
                "private fun renderFunctionPanel(",
                "private fun renderPasswordDigits(",
                "private fun renderSymbolGroups(",
                "private fun renderCandidateHint(",
            )) {
                assertTrue(
                    "L-809 守卫缺失：$fn 清空候选容器前必须复位指纹（改走 clearCandidateList）",
                    "clearCandidateList()" in blockAfter(kb, fn),
                )
            }
            assertTrue(
                "L-809 守卫缺失：refreshCandidateBar 的空态分支也必须复位指纹",
                "clearCandidateList()" in blockAfter(kb, "private fun refreshCandidateBar()"),
            )
            // 「唯一出口」守卫：除 clearCandidateList（复位）与 renderCandidateItems（自己管指纹）之外，
            // 任何地方都不得直接清这个容器 —— 那正是 L-809 的成因。注释已由 codeOf 剥掉，不计入。
            assertEquals(
                "L-809：viewCandidateList.removeAllViews() 只允许出现在 clearCandidateList 与 renderCandidateItems",
                1,
                Regex("""viewCandidateList\.removeAllViews\(\)""").findAll(kb).count(),
            )
            assertTrue(
                "L-809：clearCandidateList 必须复位指纹后再清容器",
                // 2026-10-29：复位指纹 / 分页被抽成 resetCandidateForm（功能面板复用时要单独用它，
                // 见 renderFunctionPanel 的签名早退），所以判据顺着这一层走 —— 强度不变：
                // 清容器之前必须先复位，且复位里必须真的含 resetCandidateRender()。
                blockAfter(kb, "private fun clearCandidateList()").let {
                    it.indexOf("resetCandidateForm()") in 0 until it.indexOf("recycleCandidateViews()")
                } &&
                    "resetCandidateRender()" in blockAfter(kb, "private fun resetCandidateForm()"),
            )
            // 2026-10-20：清空必须**回收进池**而不是「丢弃 + 清池」。
            // 原先 clearCandidateList 是 removeAllViews() + candidateViewPool.clear()，
            // 于是每词首键（面板 → 候选）都要新建 32 个 TextView —— 实测 365~560µs/个，
            // 整帧 build 6.7~15.1ms，占该轮 work 约 30%，是候选渲染路径上真正的尖峰。
            run {
                val body = blockAfter(kb, "private fun clearCandidateList()")
                assertTrue(
                    "清空候选容器必须走回收（否则每词首键要新建 32 个 TextView，整帧 build 可达 15ms）",
                    "recycleCandidateViews()" in body,
                )
                assertFalse(
                    "清空时不得清空复用池（池常驻是首帧不再新建的前提）",
                    "candidateViewPool.clear()" in body,
                )
            }
        }
        // L-810：`peekBody` 不是内存上限（内部先 `source.request(Long.MAX_VALUE)` 读完整 body），
        // 读取必须自己向连接要「上限 + 1」字节，否则 KDoc 承诺的 OOM 防护根本不存在。
        run {
            val client = codeOf("TranslationClient.kt")
            val read = blockAfter(client, "private fun readBodyCapped(")
            assertTrue(
                "L-810 守卫缺失：读取响应体必须有界（只 request 上限 + 1 字节）",
                "request(MAX_BODY_BYTES + 1)" in read,
            )
            assertTrue(
                "L-810 回归防护：不得再用 peekBody（它会先把整个 body 读进内存）",
                "peekBody" !in read,
            )
        }
        // L-812：让词库生效的 `restartImeForDict()` 必须在 runCatching **之外** ——
        // 与「刷界面」绑在一个 try 里时，一次主线程 IO 异常会静默吃掉生效动作。
        run {
            val dict = codeOf("DictManagerActivity.kt")
            // 与 L-800 同一锚点口径：取 download 函数体内那个回调，别锚到自定义词库导入的回调
            val cb = blockAfter(blockAfter(dict, "private fun download("), "Handler(Looper.getMainLooper()).post {")
            val refresh = cb.indexOf("page.refreshList()")
            val restart = cb.indexOf("page.restartImeForDict()")
            val caught = cb.indexOf(".onFailure")
            assertTrue(
                "L-812：restartImeForDict 必须存在且排在刷新之后（实际生效顺序）",
                refresh >= 0 && restart > refresh,
            )
            assertTrue(
                "L-812：restartImeForDict 必须在 runCatching 之外（否则刷新异常会吃掉词库生效）",
                caught >= 0 && restart > caught,
            )
        }
        // L-813：日志里的长度口径必须统一走 UTF-8 字节（与同一响应的 bytes= 同源）
        assertTrue(
            "L-813：errorSummary 不得再按 UTF-16 字符数打长度（与 bytes= 相差 2~3 倍）",
            "body=len\${body.length}" !in codeOf("TranslationClient.kt") &&
                "len=\${body.length}" !in codeOf("TranslationClient.kt"),
        )
        // L-814：saveCredentials 的 notify 不留默认参数（三处调用点全传 false，默认值是假路径）
        assertTrue(
            "L-814：saveCredentials(notify) 不得带默认值（默认路径并不存在）",
            "saveCredentials(notify: Boolean = true)" !in codeOf("TranslationSettingsActivity.kt"),
        )
        // L-815：「不可用」必须**可分因** —— 判据收敛（isReady）之后 providerOf 的 null 不再区分
        // 「没填凭据」与「端点不是 https」，而这两者的用户动作完全相反。
        run {
            val prefsCode = codeOf("Prefs.kt")
            val reason = blockAfter(prefsCode, "internal fun translateNotReadyReason()")
            assertTrue(
                "L-815 守卫缺失：必须有「不可用成因」归因（NONE / MISSING_CREDENTIAL / ENDPOINT）",
                "internal enum class TranslateNotReady" in prefsCode &&
                    "ENDPOINT" in reason && "MISSING_CREDENTIAL" in reason,
            )
            assertTrue(
                "L-815：端点这一因必须走 endpointReady（不得在归因处再拼一份判据）",
                "OpenAiTranslator.endpointReady(" in reason,
            )
            val start = blockAfter(ime, "private fun startTranslate")
            assertTrue(
                "L-815 守卫缺失：键盘 toast 必须按成因选文案（ENDPOINT → INSECURE）",
                "translateNotReadyReason()" in start && "TranslationError.INSECURE" in start,
            )
            val state = blockAfter(codeOf("SettingsActivity.kt"), "private fun refreshTranslateState()")
            assertTrue(
                "L-815：主设置页摘要必须按成因分因，且不再用 translationProvider() != null 判",
                "translateNotReadyReason()" in state && "translationProvider() != null" !in state,
            )
        }
        // L-816：「端点必须 https」这句文案只允许**一处字面量**
        run {
            val literal = "端点必须以 https:// 开头"
            val sites = listOf("Translation.kt", "OpenAiSettingsActivity.kt", "TranslationSettingsActivity.kt")
                .filter { literal in codeOf(it) }
            assertEquals(
                "L-816：「端点必须 https」这句在 ${sites.size} 个文件里各写了一遍（只允许 Translation.kt 一处）：$sites",
                1,
                sites.size,
            )
            for (page in listOf("OpenAiSettingsActivity.kt", "TranslationSettingsActivity.kt")) {
                assertTrue(
                    "L-816：$page 必须引用共享常量 TEXT_ENDPOINT_NEEDS_HTTPS",
                    "TEXT_ENDPOINT_NEEDS_HTTPS" in codeOf(page),
                )
            }
        }
        // L-818：指纹哨兵必须落在取值域之外（0 会被线性和撞上 ⇒ 误判命中、候选栏空白）
        run {
            val kb = codeOf("PinyinKeyboardView.kt")
            assertTrue(
                "L-818：candidateRenderKey 必须是可空类型且初值为 null",
                "private var candidateRenderKey: Long? = null" in kb,
            )
            assertTrue(
                "L-818：复位必须写 null，且全文件不得再出现 = 0 的复位",
                "candidateRenderKey = null" in blockAfter(kb, "private fun resetCandidateRender()") &&
                    "candidateRenderKey = 0" !in kb,
            )
        }
        assertTrue(
            "L-717 守卫缺失：saveAndNotify 必须以 notify = false 调用 saveCredentials",
            "saveCredentials(notify = false)" in blockAfter(trSettings, "private fun saveAndNotify("),
        )
        assertTrue(
            "L-728 守卫缺失：onPause 的自动保存路径必须 notify = false（否则离开页面弹一次）",
            "saveCredentials(notify = false)" in blockAfter(trSettings, "override fun onPause("),
        )
        assertTrue(
            "L-728 守卫缺失：失焦监听的自动保存路径必须 notify = false（9 个输入框会弹十几次）",
            // 监听器声明可能跨行，故按「OnFocusChangeListener 之后的整段」判
            "saveCredentials(notify = false)" in trSettings.substring(
                trSettings.indexOf("val save = View.OnFocusChangeListener")
                    .let { if (it < 0) 0 else it },
            ),
        )

        // L-687（性能）：提交侧的整窗口取证是白付的 —— `BeforeText.fromDocStart` 在提交处没有消费者，
        // 而代价是一次 `getExtractedText(limit)` 的整窗口 Binder 往返（limit 上界 10 万字符 ≈ 200 KB）。
        // L-629 只在发起处按档关掉了它，提交处漏了 ⇒ 默认档每次成功落地都白付一次。
        assertTrue(
            "L-687 守卫缺失：提交阶段读光标前文本必须关掉 doc-start 取证（否则每次成功落地多一次 200KB 往返）",
            "readTextBeforeCursor(connection, snapshot.limit, probeDocStart = false)" in ime,
        )
        assertTrue(
            "L-687 回归：发起处仍必须按档取证（BEFORE_ALL / ALL 需要知道是否到文档首）",
            "probeDocStart = needDocStartProbe" in ime,
        )
        // L-689：选区模式不记去重（选区是用户明确重新框选的内容）
        assertTrue(
            "L-689 守卫缺失：选区翻译成功后不得写去重指纹（否则光标移到别处会被 30s 守卫误拦）",
            Regex("if \\(!snapshot\\.replaceSelection\\) \\{\\s*lastSentKey = repeatKey").containsMatchIn(ime),
        )
        // L-690：未出网的失败不进冷却（INSECURE / CREDENTIAL 是同步早退，一个字节都没出网）
        assertTrue(
            "L-690 守卫缺失：非 HTTPS 与凭据含非法字符这两类不得计入失败冷却（提示说「可能已送往服务方」）",
            Regex("outcome\\.error != TranslationError\\.INSECURE").containsMatchIn(ime) &&
                Regex("outcome\\.error != TranslationError\\.CREDENTIAL").containsMatchIn(ime),
        )
        assertTrue(
            "L-690 守卫缺失：未出网时要有一行说明（否则用户只看到「不发送」而无从判断）",
            "请求未发出" in ime,
        )
        // L-691：`ui.post` 返回 false 时回调永不执行 ⇒ 必须就地收尾（看门狗与它同队列，一起失效）
        assertTrue(
            "L-691 守卫缺失：必须处理 ui.post 返回 false（否则永久停在「翻译中」且看门狗同生共死）",
            "val posted = ui.post" in ime && "回调未能入队" in ime,
        )
        // L-692：落地复核判否时不得指示「手动粘贴」（改写型 InputFilter 下译文已在框里）
        assertTrue(
            "L-692 守卫缺失：落地复核判否的文案不得指示手动粘贴",
            "请手动粘贴" !in ime && "请查看输入框" in ime,
        )
        // L-394 的具体落点：语音「结果在途」时翻译入口必须拦（松手后立刻切回拼音是唯一可达时序）
        assertTrue(
            "L-394 守卫缺失：startTranslate 必须拦语音结果在途（awaitingResult）",
            Regex("if \\(awaitingResult\\) \\{").containsMatchIn(ime),
        )
        // L-627（资金）：失败后闸门立即重开 ⇒ 连点即连付（超时 / 断连 / 落地失败 / 取消都可能已计费）。
        // ⚠ 原守卫只钉「有一个 lastFailAtMs 赋值」，而四条应记账的路径里原先只有一条在写 ——
        // 现版钉「唯一写点收敛进 markTranslateFailed」+「四个调用点都在」（L-657）。
        assertTrue(
            "L-627 守卫缺失：失败后必须有冷却窗口（并说明该请求可能已送往服务方）",
            "sinceFail < failCooldownMs" in ime &&
                "TEXT_TRANSLATE_FAIL_COOLDOWN" in ime,
        )
        run {
            val markDef = ime.indexOf("private fun markTranslateFailed(")
            assertTrue("L-657 守卫缺失：找不到 markTranslateFailed（失败记账的唯一写点）", markDef >= 0)
            val calls = Regex("markTranslateFailed\\(").findAll(ime).count()
            // 1 处定义 + 4 个调用点：回调失败 / 落地失败 / 会话边界取消 / 看门狗超时 / 发起失败
            assertTrue(
                "L-657 违反：四条「可能已计费」的路径必须都记账（现状 $calls 处，含定义）",
                calls >= 5,
            )
            // 唯一写点：lastFailAtMs 的赋值只能出现在 markTranslateFailed 里
            val writerAt = ime.indexOf("lastFailAtMs = System.currentTimeMillis()")
            assertTrue("L-657 守卫缺失：找不到 lastFailAtMs 的写入", writerAt >= 0)
            assertTrue(
                "L-657 违反：lastFailAtMs 必须只在 markTranslateFailed 里写（分散写点必漏一路）",
                writerAt in (markDef + 1)..(markDef + 800),
            )
        }
        // 落地复核：宿主「少给字符」必须记为**未测**而不是「没写进去」（L-681）
        assertEquals(
            "读不到（null）必须记为未测、不得误报",
            null,
            commitProbeVerdict(null, "译文", 2),
        )
        assertEquals(
            "宿主少给字符时必须记为未测（否则一条已写入的译文被报成「可能未写入」）",
            null,
            commitProbeVerdict("译", "译文内容", 4),
        )
        assertEquals(
            "尾部对得上时确认落地",
            true,
            commitProbeVerdict("前文译文", "译文", 2),
        )
        assertEquals(
            "尾部对不上时判为未落地（宿主丢弃了写入）",
            false,
            commitProbeVerdict("前文别的", "译文", 2),
        )
        // L-629（性能）：取证只有 BEFORE_ALL / ALL 两档会用 ⇒ 其余档位不得为它多付一次整窗口往返。
        assertTrue(
            "L-629 守卫缺失：readTextBeforeCursor 必须有 probeDocStart 形参（默认 true 保持既有行为）",
            "probeDocStart: Boolean = true" in ime &&
                "probeDocStart && beforeStartsAtDocStart(" in ime &&
                "scope == TranslationScope.BEFORE_ALL || scope == TranslationScope.ALL" in ime,
        )
        // L-631（生命周期）：注释宣称的「视图引用断开」必须名副其实 —— 六个字段都要置空。
        assertTrue(
            "L-631 守卫缺失：onDestroy 必须把键盘容器 / 主题上下文 / 四个语音控件一并置空",
            "keyboardContainer = null" in ime && "keyboardThemeCtx = null" in ime &&
                "micButton = null" in ime && "statusDot = null" in ime &&
                "statusLabel = null" in ime && "hintLabel = null" in ime,
        )
        // L-633（生命周期）：清队列只能清「此刻已在队列里」的消息，而 cancel 触发的回调是**之后**才入队的
        // ⇒ 必须先自增代际（cancelTranslate）再清队列。用位置判据钉顺序。
        run {
            val destroyAt = ime.indexOf("override fun onDestroy()")
            assertTrue("L-633 守卫缺失：找不到 onDestroy", destroyAt >= 0)
            val destroyBlock = ime.substring(destroyAt, minOf(ime.length, destroyAt + 900))
            val cancelAt = destroyBlock.indexOf("cancelTranslate()")
            val clearAt = destroyBlock.indexOf("removeCallbacksAndMessages(null)")
            assertTrue(
                "L-633 守卫缺失：onDestroy 必须**先** cancelTranslate（代际自增）**再**清队列；" +
                    "cancelAt=$cancelAt clearAt=$clearAt",
                cancelAt >= 0 && clearAt >= 0 && cancelAt < clearAt,
            )
        }
        // L-634（成本）：设置页「获取模型 / 测试连接」不得无限连点 —— 早退保护与回调清理必须成对
        // （只加早退不加清理 ⇒ 判据在首次请求后**永久成立**，按钮彻底失效）。
        run {
            val op = codeOf("OpenAiSettingsActivity.kt")
            assertTrue(
                "L-634 守卫缺失：连点保护（fetchCall != null 早退）必须与回调里的 fetchCall = null 成对",
                "if (fetchCall != null) {" in op && "fetchCall = null" in op,
            )
        }
        // ── 第九轮修复（2026-10-03）：崩溃防御 / 互斥对称 / 闸门提示 / 取消可辨 ──────────────
        // L-638（唯一会崩进程的）：`SharedPreferences` 对类型不符的键**强转抛 ClassCastException**
        // （不是返回默认）⇒ 读侧必须走 intOr/strOr 收口，否则外部写坏 prefs 时点翻译即崩。
        // 判据落在「**翻译读点真的走收口**」上，而不是"helper 存在"：
        // 只看声明的话，把调用点改回裸读也能过。
        run {
            val prefs = codeOf("Prefs.kt")
            assertTrue(
                "L-638 守卫缺失：Prefs 读侧必须有类型防御收口（intOr/strOr）",
                "private fun intOr(" in prefs && "private fun strOr(" in prefs,
            )
            assertTrue(
                "L-638 守卫缺失：翻译相关读点必须走 intOr/strOr（裸 sp.getInt/getString 会崩进程）",
                "strOr(KEY_OPENAI_" in prefs && "strOr(KEY_TRANSLATE_" in prefs &&
                    "intOr(maxBytesKeyOf(id)" in prefs && "strOr(scopeKeyOf(id)" in prefs,
            )
            // L-653（2026-10-04）：收口必须**收全** —— 裸读只允许出现在 5 个 xxxOr 助手内部。
            // 用「出现次数」而不是「某几行存在」：上面那两条只认 4 个字符串模式，把凭据键 / 布尔总开关 /
            // 更新检查时间戳改回裸读照样全绿，而「打开设置页就崩」比「点翻译才崩」更早暴露。
            assertEquals(
                "L-653 守卫：Prefs 里只允许 5 个 xxxOr 助手内部出现裸读（其余读点一律走收口）",
                5,
                Regex("""sp\.get(Boolean|Int|Long|Float|String)\(""").findAll(prefs).count(),
            )
        }
        // L-641：翻译 × 语音互斥必须**双向** —— 切到语音键盘同样要作废在途翻译，
        // 否则译文回调会 commitText 压在语音的预编辑区间上（文本错序 + 两笔计费同时发生）。
        run {
            val i = ime
            val at = i.indexOf("private fun switchToVoiceKeyboard")
            assertTrue("L-641 守卫缺失：找不到 switchToVoiceKeyboard", at >= 0)
            val block = i.substring(at, minOf(i.length, at + 1400))
            assertTrue(
                "L-641 守卫缺失：切到语音键盘必须作废在途翻译（互斥要双向）",
                "cancelTranslate(notify = true)" in block,
            )
        }
        // L-648：总开关闸门必须有用户提示（按钮是亮的、可点的，只写日志用户以为键盘坏了）。
        assertTrue(
            "L-648 守卫缺失：总开关关闭时必须提示用户",
            "toast(TEXT_TRANSLATE_DISABLED)" in ime && "TEXT_TRANSLATE_DISABLED =" in ime,
        )
        // L-644：获取模型的失败回调也要先判取消（与 translate 侧同款），否则主动取消被报成网络故障。
        assertTrue(
            "L-644 守卫缺失：fetchModels 的 onFailure 必须判 call.isCanceled()",
            "获取模型: 请求已被取消" in client,
        )

        // L-636（性能 / 一致性）：包装剥离的两个正则必须提为 object 级单例，不得每次调用重新编译。
        // ⚠ 判据取 `stripWrapper` 的**函数体窗口**判不含 `Regex(` —— 不能断"全文件没有 Regex("：
        // 单例定义本身写的就是 `private val WRAPPER_FENCE_RE = Regex("^``` …")`，
        // 那种写法会**把自己判失败**（本轮自查踩到）。
        run {
            val tr = codeOf("Translation.kt")
            assertTrue(
                "L-636 守卫缺失：包装剥离的两个正则必须提为单例",
                "private val WRAPPER_FENCE_RE" in tr && "private val WRAPPER_PREFIX_RE" in tr,
            )
            val fnAt = tr.indexOf("fun stripWrapper(")
            assertTrue("L-636 守卫缺失：找不到 stripWrapper", fnAt >= 0)
            // 函数体到下一个 `fun ` 之前（本 object 里紧随其后的是 appendText）
            val nextFun = tr.indexOf("fun appendText(", fnAt)
            val body = tr.substring(fnAt, if (nextFun > fnAt) nextFun else minOf(tr.length, fnAt + 800))
            assertTrue(
                "L-636 守卫缺失：stripWrapper 体内不得再出现 Regex(（应复用上面的单例）",
                "Regex(" !in body,
            )
        }
        // A5：看门狗必须**紧跟置位**挂上 —— 原先它排在 setTranslating/日志/toast/快照之后，
        // 那些调用任一抛出都会留下 translateInFlight=true 却**没有看门狗**（按钮永久「翻译中」）。
        run {
            val flagAt = ime.indexOf("translateInFlight = true")
            val watchdogAt = ime.indexOf("postDelayed(translateWatchdog")
            assertTrue(
                "看门狗必须在置位之后立即挂上（中间不得夹可能抛出的调用）",
                flagAt in 1..<watchdogAt,
            )
            assertTrue(
                "置位与挂表之间不得夹 setTranslating / 日志 / toast（只允许 generation 递增）",
                watchdogAt - flagAt < 400,
            )
        }
    }

    // ── 第一百四十回（2026-10-04）：下拉的「用户操作」探针 ─────────────

    /**
     * L-821 / L-822：下拉的「用户主动操作过」判据不得退回「只认触摸」。
     *
     * 原形态是 `setOnTouchListener` 里置位一个布尔标记 ⇒ 键盘确认键与读屏（`ACTION_CLICK`）
     * 选中后**不落盘**：界面显示新值、配置未变、退出重进静默还原（真机取证见 BUG.md L-821）。
     * 修法把判定收进 [UserAwareSpinner]：触摸走 `onTouchEvent`（`spinnerMode=dropdown` 的触摸
     * 由 ForwardingListener 直接开弹窗，**不经过** `performClick`），确认键与读屏都走
     * `performClick`（AOSP `View.onKeyUp` / `performAccessibilityActionInternal`），而
     * `setSelection` 与实例状态恢复两条程序化路径都不经过它们 —— 正好等于「用户动过它」。
     */
    @Test
    fun `下拉的用户操作探针不得退回只认触摸`() {
        val probe = codeOf("UserAwareSpinner.kt")
        assertTrue("探针必须覆盖触摸路径（dropdown 触摸不经过 performClick）", "override fun onTouchEvent" in probe)
        assertTrue("探针必须覆盖 performClick（确认键 / 无障碍 ACTION_CLICK）", "override fun performClick" in probe)
        assertTrue("探针必须在 ACTION_DOWN 置位", "MotionEvent.ACTION_DOWN" in probe)
        assertFalse(
            "不许给 Spinner 设 OnClickListener：Spinner.performClick 在 super 返回 true 时会跳过打开下拉",
            "setOnClickListener" in probe,
        )

        val settings = codeOf("SettingsActivity.kt")
        for (spinner in listOf("spinnerLanguage", "spinnerDefaultMode", "spinnerShuangpin")) {
            assertTrue("$spinner 的闸门必须走用户操作探针", "$spinner.userInteracted" in settings)
        }
        // 2026-10-09：候选词行数与界面明暗两个下拉迁到「键盘外观」页 —— 探针闸门必须跟着控件走，
        // 留在设置页等于外观页那两个下拉永远不落盘（`userInteracted` 没人读）。
        val appearance = codeOf("KeyAppearanceActivity.kt")
        for (spinner in listOf("spinnerCandidateRows", "spinnerTheme")) {
            assertTrue("$spinner 的闸门必须走用户操作探针（随控件迁到外观页）", "$spinner.userInteracted" in appearance)
        }
        assertFalse("不得残留只认触摸的字段（SpinnerTouched）", "SpinnerTouched" in settings)
        assertFalse("不得再给下拉挂 OnTouchListener 当闸门", "setOnTouchListener" in settings)

        fun layoutOf(name: String): String = listOf(
            File("src/main/res/layout/$name"),
            File("app/src/main/res/layout/$name"),
        ).firstOrNull { it.isFile }?.readText() ?: error("找不到 $name")
        assertEquals(
            "设置页剩余 3 个下拉必须都是 UserAwareSpinner（退回普通 Spinner 时闸门静默失效：闸门永远为 false）",
            3,
            Regex("""<com\.jinn\.inputmethod\.UserAwareSpinner""").findAll(layoutOf("activity_settings.xml")).count(),
        )
        assertEquals(
            "外观页迁入的 2 个下拉（候选行数 / 界面明暗）同样必须是 UserAwareSpinner",
            2,
            Regex("""<com\.jinn\.inputmethod\.UserAwareSpinner""").findAll(layoutOf("activity_key_appearance.xml")).count(),
        )

        val openAi = codeOf("OpenAiSettingsActivity.kt")
        assertTrue("OpenAI 页目标语言下拉必须走同一探针", "spinnerTarget.userInteracted" in openAi)
        assertFalse(
            "isUserDriven 三标志判据必须删干净（L-820 实测其在 ColorOS 下拉路径上常为 false）",
            "isUserDriven" in openAi,
        )
        assertTrue(
            "每次载入必须复位探针（否则上一轮的点击会授权一次程序化回填）",
            "resetUserInteracted()" in blockAfter(openAi, "private fun loadValues"),
        )
    }

    // ── 第一百四十一回（2026-10-04）：备份密码对话框防截屏 ─────────────

    /**
     * L-588：`FLAG_SECURE` 覆盖到设置页的**两个密码对话框**，但不整页加。
     *
     * 三个翻译页早就整页加；本页的导出 / 导入密码框一直是裸的（`AlertDialog` 有自己的窗口，
     * 不继承页面的标志）。为什么不顺手整页加：`FLAG_SECURE` 按窗口生效，而本页只有密码框是机密 ——
     * 整页加会把「设置页截图」一并废掉（真机排查与用户留证都要用），却对密码框没有额外收益。
     */
    @Test
    fun `备份密码对话框必须防截屏`() {
        val s = codeOf("SettingsActivity.kt")
        // 只数**调用点**（`= securePasswordDialog(`）：函数声明那行也含 `securePasswordDialog(`，
        // 用裸名字数会把声明算进去
        assertEquals(
            "导出 / 导入两个密码对话框都要走 securePasswordDialog（L-588）",
            2,
            Regex("""= securePasswordDialog\(""").findAll(s).count(),
        )
        val helper = blockAfter(s, "private fun securePasswordDialog")
        assertTrue("对话框窗口必须设 FLAG_SECURE（截屏 / 录屏 / 投屏 / 最近任务缩略图）", "FLAG_SECURE" in helper)
        assertFalse(
            "设置页不得整页加 FLAG_SECURE（只有密码框是机密；整页加会废掉设置页截图，真机排查要用）",
            "setFlags(" in s.replace(helper, ""),
        )
    }
    @Test
    fun `响铃模式取不到音频服务时必须按放行处理`() {
        // `audioManager?.ringerMode == RINGER_MODE_NORMAL` 在左侧为 null 时整句求值为 false，
        // 被静默当成「手机静音」⇒ 闸门永远关着（真机实测：系统三项全开而设置页试听恒置灰，
        // runCatching 的兜底也救不了 —— 没有异常可抓）。这里钉住「取不到就当放行」这条兜底。
        val src = codeOf("KeyFeedback.kt")
        assertTrue(
            "响铃判据必须显式兜底为放行，不能靠 ?. 比较的隐式 false",
            src.contains("am?.ringerMode?.let { it == AudioManager.RINGER_MODE_NORMAL } ?: true"),
        )
        assertFalse(
            "不得回到 audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL",
            src.contains("audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL"),
        )
    }

    @Test
    fun `平台反馈策略要绑在视图创建上并覆盖运行期新建的键`() {
        // L-916 / L-917：只在一个调用点装一次会漏两类树 —— 会话内重建换掉的那棵，
        // 以及运行期按需建的键（候选词 / 功能面板按钮 / 密码数字条 / 面板按钮）。
        // 这里钉住四件事：调用点不止一处、视图创建末尾必须重放、整树套用时必须挂入树回调、
        // 响铃模式要有自己的刷新源（否则键盘显示期间用音量键静音仍会出声）。
        val ime = codeOf("JinnIme.kt")
        val callSites = Regex("""(?m)^\s+applyFeedbackSuppression\(\)$""").findAll(ime).count()
        assertTrue(
            "平台反馈策略的调用点缺一不可（弹键盘刷新 + 视图创建重放），当前 = $callSites",
            callSites >= 2,
        )
        assertTrue(
            "onCreateInputView 末尾必须重放策略（recreateKeyboardView 与框架自己都经过它）",
            ime.contains("applyFeedbackSuppression()\n        return container"),
        )
        val engine = codeOf("KeyFeedback.kt")
        assertTrue(
            "整树套用策略时要给容器挂入树回调，否则运行期新建的键回到平台默认",
            engine.contains("setOnHierarchyChangeListener(") &&
                engine.contains("for (i in 0 until childCount) getChildAt(i).applyPlatformFeedbackPolicy("),
        )
        assertTrue(
            "响铃模式必须有自己的刷新源，不能只靠弹键盘",
            ime.contains("AudioManager.RINGER_MODE_CHANGED_ACTION"),
        )
    }

    @Test
    fun `抑制机制的独占项与两处清理都不能少`() {
        // 入树回调（setOnHierarchyChangeListener）是单槽 API：键盘树里若出现第二处设置它的代码，
        // 两边会互相顶掉、抑制静默失效 —— 表现是「平台一声 + 引擎一声」，很难归因到这一行。
        val engine = codeOf("KeyFeedback.kt")
        assertTrue(
            "平台反馈抑制要靠入树回调覆盖运行期新建的键",
            "setOnHierarchyChangeListener(" in engine,
        )
        assertTrue(
            "入树回调要读当前策略，不能用闭包捕获的调用时快照",
            "child?.applyPlatformFeedbackPolicy(suppressSoundNow, suppressHapticNow)" in engine,
        )
        // 扫**整个源码目录**而不是列几个文件：这条回归要「将来多一个容器类并用了这条 API」才会漏，
        // 两种前缀都要试：Gradle 的 Test 任务工作目录是 app/，而从仓库根跑（IDE、部分脚本）也是真实场景。
        // 少了回退会**静默恒真** —— walkTopDown() 在目录不存在时返回空序列而不是抛错，
        // extra 恒为空、断言永远通过，且不给任何提示。
        val dir = listOf(
            File("src/main/java/com/jinn/inputmethod"),
            File("app/src/main/java/com/jinn/inputmethod"),
        ).firstOrNull { it.isDirectory } ?: error("找不到主源码目录（cwd=${File("").absolutePath}）")
        val extra = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("setOnHierarchyChangeListener") }
            .map { it.name }
            .filter { it != "KeyFeedback.kt" }
            .toList()
        assertTrue("入树回调只允许引擎那一处设置，现状另外出现在：$extra", extra.isEmpty())
        // 自检：目录里一个 kt 文件都没有 ⇒ 路径没找对，上面那条断言等于没跑
        assertTrue(
            "源码目录扫到 0 个 kt 文件（cwd=${File("").absolutePath}），这条守卫本身没生效",
            dir.walkTopDown().any { it.isFile && it.extension == "kt" },
        )

        // 两套键盘各有一个退格键（两个实例），收起键盘时都要复位按下态
        val ime = codeOf("JinnIme.kt")
        assertTrue(
            "语音退格的按下态要在收起键盘时复位（拼音那份由 stopBackspaceRepeat 负责）",
            "voiceBackspaceView?.isPressed = false" in ime,
        )
        // 反馈 / 可点性判据只判空、不展开动态值（expand 的默认参数会白跑一次时钟读取与格式化）。
        // 断言**三处都在**而不是「文件里出现过」：`symbolKeyHasContent(c)` 在触摸抬起、无障碍激活
        // 与无障碍可点性（`canActivate`，BUG.md L-1139）各出现一次，只改其中一处、另一处仍留着，
        // 「出现过」照样成立（变异验证实测过）。
        val pkv = codeOf("PinyinKeyboardView.kt")
        val contentChecks = Regex("symbolKeyHasContent\\(c\\)").findAll(pkv).count()
        assertTrue("符号层的判据要覆盖触摸、无障碍反馈与无障碍可点性三处，实际 $contentChecks 处", contentChecks == 3)
        assertTrue(
            "符号层的反馈判据不得走 symbolValueOf",
            "symbolKeyHasContent" in pkv && "symbolValueOf(c) != null" !in pkv,
        )
    }

    /**
     * 剪贴板参数入口与提示的四条收口（L-991 / L-992 / L-993 / L-995）＋ L-997 的复核结论。
     *
     * 共同点是「算式或文案错一处就失效」：置灰判据多带一个条件、刷新少一次、提示口径不区分来源，
     * 界面表现几乎不变，靠人眼回归看不出来。字面量对拍比行为测试便宜，也更能抗重构。
     */
    @Test
    fun 剪贴板参数入口的收口不得回退() {
        val page = codeOf("ClipboardCustomizeActivity.kt")
        // L-992：可用性只看解锁态。把草稿里的总开关算进来，「关掉历史」这条改动会连保存一起禁用
        assertTrue("参数区置灰必须只随解锁态", "val editable = unlocked" in page)
        assertFalse("不得再把草稿里的总开关算进可用性", "val editable = draft.enabled && unlocked" in page)
        // L-993：裁剪失败要让用户看到，不能只落诊断日志
        assertTrue("裁剪失败必须有可见提示", "TEXT_TRIM_FAILED" in page)
        // L-997 复核结论：数值文本必须用盘上原值。默认值 500 / 200 / 256 都不在步进格点上，
        // 一旦改成对齐值就会被显示成 451 / 191 / 228，并把它们写回盘（试过，代价更大）
        assertTrue("数值文本必须用盘上原值", "text = format(value)" in page)
        assertFalse(
            "不得再引入步进对齐",
            "val shown = min + ((value.coerceIn(min, max) - min) / step) * step" in page,
        )

        // 2026-10-09：设置页那份「历史数量上限」输入框连同回写路径一并移除（功能已在「剪贴板自定义」页，
        // 属重复入口）。L-991 的隐患（进页面拿旧值、离开时覆盖别处刚保存的值）随控件一起消失，
        // 这里改判否定式：不得再把「第二处可写入口」加回设置页。
        val settings = codeOf("SettingsActivity.kt")
        assertFalse("设置页不得再持有剪贴板上限（唯一入口是「剪贴板自定义」页）", "clipboardPrefs" in settings)
        assertFalse("设置页不得再有上限回写路径", "saveMaxItems" in settings)

        val history = codeOf("ClipboardHistoryActivity.kt")
        // L-995：只有条数触顶才够资格报「最多 N 条」，容量预算先触发时要说别的
        assertTrue("截断提示必须区分条数与容量两种来源", "cappedByCount" in history)
    }

    /**
     * 第十一批的三条修复不得回退（BUG.md L-1085 / L-1086 / L-1067）。
     *
     * 三条都是「静默错结果」：导出的稳定性判据自相抵消（漏条却报成功）、解锁产物被自己的残留
     * 清扫删掉后误报「包损坏」、备份里的空凭据删掉本机凭据还计成「已设置」。
     * 判据都落在「哪个函数被调用 / 谁的闸门在挡」，字面量对拍比行为测试便宜。
     */
    @Test
    fun 导出判据与解锁产物与空凭据的三条修复不得回退() {
        val manager = codeOf("ConfigBackupManager.kt")
        // L-1085：稳定性判据要用不会自相抵消的指纹（条数 + 最大 id + 密文总长）
        val export = TestSources.blockAfter(manager, "fun collectClipboard(")
        assertTrue("导出必须用指纹判稳", "db.exportStamp()" in export)
        assertFalse("只看条数会在「插入 + 裁剪成对发生」时判成没变", "db.count() == before" in export)
        val stamp = TestSources.window(codeOf("ClipboardDb.kt"), "fun exportStamp(): String =", "\"0:0:0\"")
        assertTrue("指纹要含最大 id", "MAX(id)" in stamp)
        assertTrue("指纹要含密文总长", "SUM(LENGTH(encrypted_content))" in stamp)

        // L-1086：交付给清单框的那份要继续在册，只有中间件与失败路径才除名；注销点要落在收尾路径上
        val unlock = blockAfter(manager, "fun unlock(context: Context")
        assertTrue("交付的产物必须继续在册", "it != delivered" in unlock)
        assertTrue("要有注销入口", "fun releaseUnlockedTemp(" in manager)
        assertTrue("导入收尾要注销", "releaseUnlockedTemp(zip)" in manager)
        val settings = codeOf("SettingsActivity.kt")
        val releases = settings.split("releaseUnlockedTemp(").size - 1
        assertTrue("取消 / 关闭 / 销毁三条路径都要注销（实见 $releases 处）", releases >= 3)

        // L-1067：空凭据不进包、也不在导入端当删除执行
        val prefs = codeOf("Prefs.kt")
        val exportBody = TestSources.blockAfter(prefs, "fun exportForBackup(")
        // 收在函数体里（花括号配对 + 锚点断言）：裸 `substringAfter` 在锚点缺失时返回整份文件，
        // 后面那条「导入分支要挡空串」就会被任意别的函数满足（BUG.md L-1145 的同型静默放宽）
        val importBody = TestSources.blockAfter(prefs, "fun importFromBackup(")
        for (key in listOf(
            "KEY_AZURE_API_KEY", "KEY_BAIDU_APP_ID", "KEY_BAIDU_SECRET_KEY",
            "KEY_ALIYUN_ACCESS_KEY_ID", "KEY_ALIYUN_ACCESS_KEY_SECRET", "KEY_DEEPL_API_KEY",
            "KEY_BAIDU_LLM_APP_ID", "KEY_BAIDU_LLM_API_KEY", "KEY_OPENAI_API_KEY",
        )) {
            assertTrue("$key 必须走 putCredential（空值不进包）", "putCredential($key)" in exportBody)
            assertFalse("$key 不得再无条件导出", "put($key," in exportBody)
            assertTrue(
                "$key 的导入分支要挡空串（空串在写入侧等于删除）",
                "$key -> asString(v)?.takeIf { it.isNotEmpty() }" in importBody,
            )
        }
    }

    /**
     * 重建态与无障碍的两条修复不得回退（2026-10-08 · L-1163 / L-1164）。
     *
     * 编辑页的草稿要跨重建存活（主题节拍器与系统深浅色都会 `recreate()`）；
     * 图库格子的读屏名要带序号（24 格同名时读屏无法定位）。
     */
    @Test
    fun `编辑页草稿与图库格子读屏名的两条修复不得回退`() {
        val edit = TestSources.codeSource("CustomDictEditActivity.kt")
        assertTrue("必须实现 onSaveInstanceState", "override fun onSaveInstanceState(" in edit)
        assertTrue("必须把编辑器文本写进状态", "putString(STATE_EDITOR_TEXT" in edit)
        assertTrue("必须把脏标记写进状态", "putBoolean(STATE_DIRTY" in edit)
        assertTrue("onCreate 必须读回草稿", "startDraftRestore(it)" in edit)
        assertTrue(
            "读回草稿必须排在 loadSource() 之前（回填分支据此跳过，否则草稿被盘上内容盖掉）",
            edit.indexOf("startDraftRestore(it)") < edit.indexOf("loadSource()"),
        )

        val gallery = TestSources.codeSource("GalleryPanelView.kt")
        assertTrue(
            "格子描述必须带序号（且钉 Locale，BUG.md L-1179）",
            "contentDescription = String.format(Locale.US, TEXT_CELL_FMT, ordinal)" in gallery,
        )
        assertTrue("格式串要含张数", "第 %d 张" in gallery)
        assertTrue("序号必须按整份列表编（跨页连续）", "var ordinal = from" in gallery)
    }

    /**
     * 重建态与资产读取的三条修复不得回退（2026-10-08 · L-1136 / L-1156 / L-1165）。
     *
     * 两页的状态都要跨 `recreate()` 存活（主题节拍器与系统深浅色都会重建）；
     * 资产读取要与用户来源路径**同口径**剥 BOM，否则带 BOM 重生成资产会多出首项垃圾并让首键失效。
     */
    @Test
    fun `历史页与收藏页的实例状态和资产剥 BOM 的三条修复不得回退`() {
        val hist = TestSources.codeSource("ClipboardHistoryActivity.kt")
        assertTrue("历史页必须实现 onSaveInstanceState", "override fun onSaveInstanceState(" in hist)
        val restore = hist.substringAfter("savedInstanceState?.let", "")
        for (k in listOf("STATE_FILTER", "STATE_KEYWORD", "STATE_PAGE", "STATE_CHECKED", "STATE_MULTI")) {
            assertTrue("$k 必须写进实例状态", (k + ")" in hist) || (k + "," in hist))
            assertTrue("$k 必须在重建后读回", (k + ")") in restore || (k + ",") in restore)
        }
        assertTrue(
            "筛选与多选要在建 chips 之前恢复（否则界面与状态不一致）",
            hist.indexOf("savedInstanceState?.let") < hist.indexOf("buildFilterChips()"),
        )
        assertTrue("确认框必须防重入", "if (confirming) return" in hist)

        val fav = TestSources.codeSource("FavoriteSymbolsActivity.kt")
        assertTrue("对话框输入必须写进实例状态", "putString(STATE_ADD_TEXT" in fav)
        assertTrue(
            "重建后要带初值重开对话框",
            "showAddDialog(" in fav.substringAfter("getString(STATE_ADD_TEXT)", ""),
        )

        val engine = TestSources.codeSource("PinyinEngine.kt")
        assertTrue("文本资产必须剥 BOM（首项失效那条）", "reader.read() != 0xFEFF" in engine)
        val dicts = TestSources.codeSource("CustomDicts.kt")
        assertTrue(
            "音节表资产读取必须限长（剥 BOM 收口在 readCapped 里）",
            "readCapped(reader, SYLLABLES_MAX_CHARS)" in TestSources.blockAfter(dicts, "fun loadSyllables"),
        )
    }

    /** 草稿分档判据：Bundle 侧只收小稿，大稿走临时文件（BUG.md L-1169）。 */
    @Test
    fun `草稿进实例状态的判据必须按上限分档`() {
        assertTrue("小稿走 Bundle", CustomDictEditActivity.draftInBundle(1_000))
        assertTrue("刚好等于上限仍走 Bundle", CustomDictEditActivity.draftInBundle(CustomDictEditActivity.DRAFT_BUNDLE_MAX_CHARS))
        assertTrue("超一个字符就该改走文件", !CustomDictEditActivity.draftInBundle(CustomDictEditActivity.DRAFT_BUNDLE_MAX_CHARS + 1))
        assertTrue("空稿当然走 Bundle", CustomDictEditActivity.draftInBundle(0))
        // 上限本身要远小于系统侧事务量级（约 1MB ⇒ UTF-16 下 50 万字符），留足余量
        assertTrue(
            "上限过大，起不到挡住事务超限的作用",
            CustomDictEditActivity.DRAFT_BUNDLE_MAX_CHARS <= 200_000,
        )
    }

    /**
     * 实例状态尺寸与两处伴生修复不得回退（2026-10-08 · L-1169 / L-1171 / L-1172）。
     *
     * 词库草稿按长度分档（小稿进状态、大稿进 cacheDir 文件），另两处站点按各自上限截断；
     * 历史页恢复出来的勾选集要按本次加载结果裁剪、页码不能被加载收尾归零。
     */
    @Test
    fun `实例状态尺寸与历史页恢复语义的三条修复不得回退`() {
        val edit = TestSources.codeSource("CustomDictEditActivity.kt")
        assertTrue("保存必须按 draftInBundle 分档", "if (draftInBundle(text.length))" in edit)
        // 兜底写入改走分段编码（BUG-27）：不再把整份文本一次编成约 48MB 的字节数组
        assertTrue(
            "大稿要落到 cacheDir 的文件",
            "writeTextChunked(staged, text)" in edit && "DRAFT_FILE_SAVE_TMP" in edit,
        )
        assertTrue("Bundle 里只留文件名", "putString(STATE_EDITOR_FILE, DRAFT_FILE)" in edit)
        val restore = edit.substringAfter("private fun startDraftRestore(", "")
        assertTrue("读回要先看状态再读文件", "state.getString(STATE_EDITOR_FILE)" in restore)
        assertTrue(
            "文件留到铺进编辑器之后才删（BUG.md L-1190）",
            "File(cacheDir, file).delete()" in
                blockAfter(edit, "private fun finishDraftApply("),
        )

        val fav = TestSources.codeSource("FavoriteSymbolsActivity.kt")
        assertTrue(
            "对话框输入要按条目上限截断",
            // 截断必须**按码点**：`take(8)` 数码元，8 个 emoji 转完只剩 4 个，还可能切出孤立代理项
            // （BUG.md L-1426）。判据钉在码点口径的实现名上，改回 `take(` 当场红。
            "FavoriteSymbols.takeCodePoints(" in
                fav.substringAfter("outState.putString(STATE_ADD_TEXT", ""),
        )
        assertTrue(
            "数字行单槽草稿同样按码点截断",
            "FavoriteSymbols.takeCodePoints(" in fav.substringAfter("STATE_LABEL_TEXT,", ""),
        )
        assertFalse(
            "草稿截断不得再用码元口径的 take(",
            ".take(FavoriteSymbols.MAX_CHARS)" in fav || ".take(NumberRowLabels.MAX_CHARS)" in fav,
        )
        assertTrue(
            "模型侧必须提供码点截断（与 withinLimit 同口径）",
            "fun takeCodePoints(text: String, max: Int = MAX_CHARS): String" in
                TestSources.codeSource("FavoriteSymbols.kt"),
        )

        val hist = TestSources.codeSource("ClipboardHistoryActivity.kt")
        assertTrue("搜索词要按上限截断", "keyword.take(MAX_STATE_KEYWORD)" in hist)
        assertTrue("勾选集要按本次加载结果裁剪", "checkedIds.retainAll(" in hist)
        assertTrue("页码只在改条件时归零", "if (resetPage) pageIndex = 0" in hist)
        assertTrue(
            "重建 / 回前台的那次加载不得归零页码",
            "loadAsync(resetPage = false)" in TestSources.blockAfter(hist, "override fun onStart()"),
        )

        val dicts = TestSources.codeSource("CustomDicts.kt")
        assertTrue(
            "音节表资产读取必须限长",
            "readCapped(reader, SYLLABLES_MAX_CHARS)" in blockAfter(dicts, "fun loadSyllables"),
        )
    }

    /**
     * 草稿的增量落盘、截断提示、收尾清理与页码保留四条不得回退（2026-10-08 · L-1173…L-1176）。
     *
     * 词库编辑页：大稿改成去抖落盘（走 [BackgroundIo]、临时名替换），保存侧只在文件不是最新时同步补一次，
     * 恢复侧推迟到首帧之后并写出状态行，兜底截断留可见标记，收尾只在不再重建时删文件；
     * 历史页：删除与收藏切换不再把用户弹回第 1 页。
     */
    @Test
    fun `草稿增量落盘与页码保留四条修复不得回退`() {
        val edit = TestSources.codeSource("CustomDictEditActivity.kt")
        assertTrue("落盘必须按去抖排", "postDelayed(draftWriteTask, DRAFT_WRITE_DEBOUNCE_MS)" in edit)
        val writer = edit.substringAfter("private val draftWriteTask", "")
        // MEM-30 起分块走长活池（同样是单线程，保序不变）：短活队列不再被秒级写入推后
        assertTrue("落盘必须交给单线程长活队列", "BackgroundIo.runLong {" in writer)
        assertTrue(
            "写文件必须临时名 + rename（避免读到写了一半的版本）",
            "renameTo(File(cacheDir, DRAFT_FILE))" in edit,
        )
        assertTrue("保存侧要按「文件是否最新」分档", "draftWrittenRevision == draftRevision" in edit)
        assertTrue("新鲜度必须按修订号，不是文本长度（同长度改写会漏）", "draftWrittenLength" !in edit)
        assertTrue("文本变化要自增修订号", "draftRevision++" in edit)
        val task = edit.substringAfter("private val draftWriteTask", "")
        assertTrue(
            "写入器要把当时的修订号记下来（末块成功才认）",
            "val revision = draftRevision" in task &&
                "if (last) draftWrittenRevision = revision else enqueueDraftChunk" in edit,
        )
        assertTrue("大稿恢复必须推迟到首帧之后", "editor.postOnAnimation {" in edit)
        assertTrue("恢复期间要有状态行", "TEXT_DRAFT_RESTORING" in edit)
        assertTrue("兜底截断必须记长度", "putInt(STATE_DRAFT_TRUNCATED, kept.length)" in edit)
        assertTrue(
            "截断恢复后必须有提示（钉 Locale，BUG.md L-1179）",
            "String.format(Locale.US, TEXT_DRAFT_TRUNCATED, truncatedAt)" in edit,
        )
        val destroy = edit.substringAfter("override fun onDestroy()", "")
        assertTrue("只有不再重建时才清草稿", "if (!isChangingConfigurations)" in destroy)
        assertTrue(
            "正式名与两个临时名都要清",
            "listOf(DRAFT_FILE, DRAFT_FILE_TMP, DRAFT_FILE_SAVE_TMP)" in destroy,
        )
        assertTrue(
            "去抖任务必须无条件撤销（重建时旧实例的写入会盖掉新实例那份）",
            destroy.indexOf("removeCallbacks(draftWriteTask)") < destroy.indexOf("if (!isChangingConfigurations)"),
        )

        val hist = TestSources.codeSource("ClipboardHistoryActivity.kt")
        // 判据是**下界**（2026-10-10）：图片条目的收藏 / 删除 / 转移（对话框与长按菜单两处入口）
        // 同样保留页码，等值计数会把合规新增误报成回退。四条原始修复（收藏 / 删除 / 多选删除 /
        // 单条删除）仍必须都在 —— 数量掉到 4 以下就是回退。
        assertTrue(
            "删除与收藏切换（含多选删除）必须保留页码（当前 ≥4 处，实测 " +
                Regex("""loadAsync\(resetPage = false\)""").findAll(hist).count() + " 处）",
            Regex("""loadAsync\(resetPage = false\)""").findAll(hist).count() >= 4,
        )
        // 搜索窗口不得被「搜索结果上限」截断（BUG.md L-1059）：loadAsync 原来把 maxResults
        // 当驻留窗口用、关键字过滤在窗口之后做 —— 比窗口更老的记录任何关键词都搜不到。
        // 判据：有搜索词时**不匹配的行不占窗口**（续扫到命中足够），且序号按绝对序位算。
        val load = TestSources.blockAfter(hist, "private fun loadAsync(resetPage: Boolean = true)")
        assertTrue(
            "有搜索词时不匹配的行不得占窗口（原样会把更老的记录挡在窗口外）",
            Regex("""if \(searching && !ClipboardSearch\.matches\([^\n]*\)\) continue""").containsMatchIn(load),
        )
        assertTrue("序号要按绝对序位算", "outOrdinal.add(scanned)" in load)
        assertTrue("序号映射要读绝对序位", "outOrdinal.getOrNull(idx)" in load)
        // 公式值也要钉（AI2 A-431 指出「只钉表达式存在」拦不住 off-by-one）：`scanned` 是 1 基，
        // 必须 −序位+1；漏掉 +1 就是整列序号少 1（BUG.md L-1379 / A-415）
        assertTrue(
            "序号换算必须带 +1（1 基序位）",
            "total - (outOrdinal.getOrNull(idx) ?: (idx + 1)) + 1" in load,
        )
        // 扫描预算（BUG.md L-1380）：续扫必须受行数预算约束，触顶走「列表不完整」提示
        assertTrue(
            "续扫必须受扫描预算约束",
            Regex("""if \(searching && scanned > SEARCH_SCAN_BUDGET\)""").containsMatchIn(load),
        )
        // 钉**分支体**，而不是「同块里出现过 capped = true」——后者被同块的条数/字节预算分支
        // 一并满足，掏空本分支仍全绿（AI2/审查通道已实证，BUG.md L-1383）
        assertTrue(
            "预算触顶要落进「列表不完整」提示",
            Regex(
                """if \(searching && scanned > SEARCH_SCAN_BUDGET\) \{[^}]*capped = true[^}]*break""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(load),
        )
        // 预算必须有界：**既要**常量声明存在，**又要**它落在 1..5000。
        // 两种合法形态都接受：① 直接写数字；② 引用 `ClipboardSearch.SCAN_LIMIT` 单一真源
        // （此时取 ClipboardSearch.SCAN_LIMIT 的实际值来判有界 —— 防止某天把它调成 20000）。
        val literal = Regex("""const val SEARCH_SCAN_BUDGET = (\d+)""").find(hist)
            ?.groupValues?.get(1)?.toInt()
        val budget = literal
            ?: if ("const val SEARCH_SCAN_BUDGET = ClipboardSearch.SCAN_LIMIT" in hist) {
                Regex("""const val SCAN_LIMIT = (\d+)""")
                    .find(TestSources.codeSource("ClipboardSearch.kt"))
                    ?.groupValues?.get(1)?.toInt()
            } else {
                null
            }
        assertTrue(
            "预算常量要有界且远小于库上限（实测 $budget）",
            budget?.let { it in 1..5_000 } == true,
        )
        // 并发覆盖（本轮新增）：慢的那次加载后落地会把新筛选的列表覆盖成旧数据。
        // 判据：世代令牌必须存在、发起时自增、扫描循环与 UI 落地各自校验一次。
        assertTrue("必须有加载代际令牌", "loadGeneration.incrementAndGet()" in load)
        assertTrue("扫描循环要按代际早退", "gen != loadGeneration.get()" in load)
        assertTrue(
            "UI 落地要按代际丢弃陈旧结果",
            Regex("""if \(stopped \|\| gen != loadGeneration\.get\(\)\) return@runOnUiThread""").containsMatchIn(load),
        )
    }

    /**
     * Gitee 取数的**两条截断路径必须同口径**（BUG.md L-1061）：取页失败（L-1048）与翻页到上限
     * 都要作废本源、交回退源。只留日志照旧定论会给出偏旧的「最新版本」——假的「已最新」
     * 加七天节流且不自愈，比如实报网络异常更糟。
     */
    @Test
    fun `Gitee 两条截断路径都要作废本源`() {
        val gitee = TestSources.blockAfter(
            TestSources.codeSource("UpdateChecker.kt"),
            "private fun fetchFromGitee(deadline: Long)",
        )
        assertTrue(
            "取页失败必须作废本源",
            Regex("""取不到，本源作废[^\n]*\n\s*return@runCatching null""").containsMatchIn(gitee),
        )
        assertTrue(
            "翻页到上限也必须作废本源（不能只留日志照旧定论）",
            Regex("""翻页到上限[^\n]*\n\s*return@runCatching null""").containsMatchIn(gitee),
        )
    }

    /**
     * 启动期词典线程必须降到后台（2026-10-08 · L-1154）。
     *
     * 索引解压是启动期最重的 CPU 活（16MB 定值缓冲 + 结尾整份拷贝），而它此前跑在默认优先级上：
     * 用户点开输入框后，前台按键要跟它抢 CPU。服务里的三处重活（双拼预热 / 基础预加载 / 补试）
     * 与 `PinyinEngine` 的可选包线程都要带 `THREAD_PRIORITY_BACKGROUND`，这条按**处数**钉住不回落。
     */
    @Test
    fun `启动期词典线程必须降到后台`() {
        val needle = "Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)"
        val ime = TestSources.codeSource("JinnIme.kt")
        val inIme = Regex(Regex.escape(needle)).findAll(ime).count()
        assertTrue("JinnIme 里三处重活都要带这条（双拼预热 / 基础预加载 / 补试），实际 $inIme 处", inIme >= 3)
        val engine = TestSources.codeSource("PinyinEngine.kt")
        assertTrue("PinyinEngine 的可选包加载线程也要带（早就有，一并钉住）", needle in engine)
    }

    /**
     * 草稿的「落盘分块 + 两端失败可见性 + 恢复期不停用保存」三条不得回退（2026-10-08 · L-1180 / L-1181 / L-1185）。
     *
     * 三条都在同一页、同一份大稿上：落盘要按块投递（串行队列不能被整份写堵住），
     * 恢复端读失败或读到半截文件都不能把唯一副本删掉，恢复期也不该按「源文本太大」停用保存。
     */
    @Test
    fun `草稿落盘分块与恢复失败可见性的三条修复不得回退`() {
        val edit = TestSources.codeSource("CustomDictEditActivity.kt")

        // L-1180：分块投递、世代号作废、代理对不劈开、下一块自投
        assertTrue("落盘必须分块投递（每块一个 IO 任务）", "private fun enqueueDraftChunk(" in edit)
        assertTrue("分块大小要有常量", "DRAFT_WRITE_CHUNK_CHARS" in edit)
        assertTrue("在途任务靠世代号作废", "if (generation != draftWriteGeneration) return@runLong" in edit)
        assertTrue("世代号在每批开始时自增", "++draftWriteGeneration" in edit)
        assertTrue("不能把代理对劈成两半", "Character.isHighSurrogate(text[end - 1])" in edit)
        assertTrue("第一块截断、其余追加", "FileOutputStream(File(cacheDir, DRAFT_FILE_TMP), from == 0)" in edit)
        assertTrue("下一块由当前块续投（不让两代交错）", "else enqueueDraftChunk(text, revision, generation, end)" in edit)

        // L-1185：同步补写走临时名 + 改名、随令牌记长度、两端都不删唯一副本
        val save = edit.substringAfter("override fun onSaveInstanceState(", "")
        assertTrue("同步补写也要走临时名", "DRAFT_FILE_SAVE_TMP" in save)
        assertTrue("并且用改名发布", "staged.renameTo(File(cacheDir, DRAFT_FILE))" in save)
        assertTrue("令牌要带上预期长度", "putInt(STATE_DRAFT_LEN, text.length)" in save)
        val restore = blockAfter(edit, "private fun startDraftRestore(")
        assertTrue(
            "读取阶段不许删草稿本体（两个临时名的清理不算；删除只发生在铺完或明确放弃时）",
            "File(cacheDir, draftName).delete()" !in restore && "File(cacheDir, file).delete()" !in restore,
        )
        assertTrue(
            "读失败要保留文件与令牌，下一次重建再试",
            "pendingDraftFile = draftName" in restore &&
                restore.indexOf("pendingDraftFile = draftName") < restore.indexOf("setStatus(TEXT_DRAFT_UNREADABLE)"),
        )
        assertTrue("读失败要给出可见提示", "TEXT_DRAFT_UNREADABLE" in restore)
        assertTrue(
            "读盘必须放后台线程并降优先级（BUG.md L-1193）",
            "Thread {" in restore && "THREAD_PRIORITY_BACKGROUND" in restore,
        )
        assertTrue(
            "长度不足要按截断提示且不删文件",
            "val truncatedAt = if (expected > 0 && restored.length < expected)" in restore,
        )
        assertTrue(
            "令牌要带预期长度（BUG.md L-1192）",
            "outState.putInt(STATE_DRAFT_LEN, pendingDraftLen)" in save,
        )
        assertTrue("读失败的文件令牌要续带到下一次重建", "val pending = pendingDraftFile" in edit)

        // L-1181：草稿在途时按「编辑框已有内容」处理，不停用保存、也不报「已跳过回填」
        assertTrue("恢复期要有在途标记", "draftPending = true" in edit && "draftPending = false" in edit)
        assertTrue("回填判据要认草稿在途", "draftPending || editor.text.isNotEmpty()" in edit)
        // 判据从「草稿在途」扩到「在途或刚铺完」：刚铺完草稿时编辑器非空的是**草稿**本身，
        // 报「编辑框里已有你输入的内容」会把刚恢复的草稿说成用户的输入
        assertTrue(
            "草稿在途 / 刚铺完草稿时不报「已跳过回填」",
            "if (!draftPending && !draftJustRestored) setStatus(TEXT_SKIP_REFILL)" in edit,
        )
    }

    /**
     * 草稿链路的五条收口不得回退（2026-10-08 · L-1189 ~ L-1193）。
     *
     * 一次改完的五条：发布前的世代二次校验、草稿文件「铺完才删」+ 令牌续传、
     * 铺前判「编辑器已有内容」、令牌带预期长度、读盘移后台。
     */
    @Test
    fun `草稿链路五条收口不得回退`() {
        val edit = TestSources.codeSource("CustomDictEditActivity.kt")

        // L-1189：末块改名发布前再查一次世代
        assertTrue(
            "发布前必须二次校验世代（否则旧快照会盖掉刚写好的正式名）",
            "if (generation != draftWriteGeneration) return@runLong" in edit &&
                edit.indexOf("if (generation != draftWriteGeneration) return@runLong") <
                edit.indexOf("File(cacheDir, DRAFT_FILE_TMP).renameTo(File(cacheDir, DRAFT_FILE))"),
        )

        // L-1190：文件铺完才删；令牌在途期间照旧带下去
        val apply = edit.substringAfter("private fun applyDraft(", "")
        assertTrue(
            "铺完收尾才删文件、并清令牌",
            "private fun finishDraftApply(file: String?, truncatedAt: Int)" in edit &&
                "File(cacheDir, file).delete()" in
                blockAfter(edit, "private fun finishDraftApply("),
        )
        assertTrue(
            "在途 / 读失败期间保存侧要接着带令牌与长度",
            // 判据里不再含 `!dirty`（BUG-22）：读盘在途时用户先敲了字就不能把令牌丢掉 ——
            // 大稿此刻编辑器是空的，丢令牌等于让那份草稿成为孤儿文件
            "if (pending != null && draftWrittenRevision < 0)" in edit &&
                "outState.putString(STATE_EDITOR_FILE, pending)" in edit,
        )

        // L-1191 / L-1196：让路只让给**用户真敲进去**的内容（程序回填不置 dirty，草稿要能盖掉它）
        assertTrue(
            "铺前要判「用户输入的已有内容」而不是「编辑器非空」",
            "if (editor.text.isNotEmpty() && dirty)" in apply,
        )
        assertTrue(
            "首帧回调里也要判（用户可能在这段窗口里敲字）",
            "TEXT_DRAFT_SKIPPED" in apply && "discardPendingDraft()" in apply,
        )

        // L-1193：读盘在后台线程、带后台优先级
        val restore = blockAfter(edit, "private fun startDraftRestore(")
        assertTrue("读盘必须放后台线程", "Thread {" in restore)
        assertTrue("读盘线程要降优先级", "THREAD_PRIORITY_BACKGROUND" in restore)
        assertTrue(
            "读盘一开始就要占住「草稿在途」（否则回填先铺，草稿到达时被丢掉）",
            restore.indexOf("draftPending = true") in 1..restore.indexOf("Thread {"),
        )
        assertTrue(
            "文件不在时令牌作废、提示另算（BUG.md L-1197）",
            "TEXT_DRAFT_GONE" in restore && "pendingDraftFile = null" in restore,
        )
        assertTrue(
            "收尾与放弃都要清「在途」标记（否则后续回填与保存使能被卡住）",
            "draftPending = false" in
                blockAfter(edit, "private fun finishDraftApply(") &&
                "draftPending = false" in edit.substringAfter("private fun discardPendingDraft(", ""),
        )
    }

    /**
     * 用户可见计数的格式化必须显式钉 Locale（2026-10-08 · L-1179），
     * 动态状态行必须有读屏播报通道（同日 · L-1195）。
     */
    @Test
    fun `计数文案的 Locale 与状态行的播报通道不得回退`() {
        val dir = listOf(
            File("app/src/main/java/com/jinn/inputmethod"),
            File("src/main/java/com/jinn/inputmethod"),
        ).first { it.isDirectory }
        val files = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".kt") }.map { it.name }
        assertTrue("没读到主源码（工作目录变了？）：${files.size} 个", files.size > 50)
        // L-1179：`TEXT_*.format(` 直接用默认 Locale，阿拉伯语 / 波斯语等地区会显示本地字形数字
        val loose = mutableListOf<String>()
        // L-1195：状态行把这些页面的失败/进度反馈承载在视觉上，读屏要能听见
        val silent = mutableListOf<String>()
        for (name in files) {
            val src = TestSources.codeSource(name)
            Regex("""TEXT_[A-Z0-9_]+\)?\.format\(""").findAll(src).forEach { m ->
                loose += "$name：${src.substring(0, m.range.first).count { it == '\n' } + 1} 行"
            }
            val hasStatus = listOf(
                "private fun setStatus(",
                "textStatus = TextView(",
                "status.apply {",
                "textConfigHint = findViewById(",
            ).any { it in src }
            if (hasStatus && "accessibilityLiveRegion" !in src) silent += name
        }
        assertTrue("这些计数文案没钉 Locale（改成 String.format(Locale.US, …)）：$loose", loose.isEmpty())
        assertTrue("这些页面有状态行却没有读屏播报通道（加 accessibilityLiveRegion）：$silent", silent.isEmpty())
    }

    /**
     * 后台重活统一降优先级（2026-10-08 · L-1194）：按文件计处数，少一处就红。
     */
    @Test
    fun `后台重活线程的降级处数不得减少`() {
        val need = mapOf(
            "CustomDictEditActivity.kt" to 3,   // 保存 / 源文本读取 / 草稿读回
            "DictManagerActivity.kt" to 2,      // 导入后重建 / 导入解析
            "SettingsActivity.kt" to 6,         // 诊断导出与落盘、配置导出与落盘、解锁、导入
            "GalleryPanelView.kt" to 1,         // 缩略图池（线程体内设置）
            "ClipboardController.kt" to 1,
            "JinnIme.kt" to 3,
            "PinyinEngine.kt" to 1,
            "UserFrequency.kt" to 1,
        )
        val short = mutableListOf<String>()
        for ((f, n) in need) {
            val got = Regex("setThreadPriority").findAll(TestSources.codeSource(f)).count()
            if (got < n) short += "$f（需要 $n，实际 $got）"
        }
        assertTrue("这些文件的后台优先级处数变少了（重活会与前台争 CPU）：$short", short.isEmpty())
    }

    /**
     * 「26 键常显大写」开关（2026-10-09）：只改键面字形，不改上屏；英文模式不套用。
     *
     * 为什么逐条钉：漏掉 `!englishMode` ⇒ 英文模式下「看到 A、打出 a」；
     * 漏掉 `layer == LAYER_LETTER` ⇒ 符号层 / 数字层的标签被一起大写（符号层标签来自动态符号表，
     * 大写化后会变成一堆对不上的标签）。
     */
    @Test
    fun `26 键常显大写必须落在大写判定上且英文模式除外`() {
        val text = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "「常显大写」没落进大写判定（开关会失效）",
            text.contains("prefs.keyLetterUppercase && !englishMode"),
        )
        assertTrue(
            "大写判定丢了 layer == LAYER_LETTER（符号层 / 数字层会被一起大写）",
            text.contains(
                "val showUpper = layer == LAYER_LETTER && (capsMode || (prefs.keyLetterUppercase && !englishMode))",
            ),
        )
        val page = codeOf("KeyAppearanceActivity.kt")
        assertTrue("外观页滑杆没写回 Prefs", page.contains("prefs.keyHeightDp = dp"))
        assertTrue("键高滑杆上限必须取自定义域", page.contains("seekKeyHeight.max = KeyAppearance.KEY_HEIGHT_PROGRESS_MAX"))
        assertTrue("大写开关没写回 Prefs", page.contains("prefs.keyLetterUppercase = checked"))
    }

    /**
     * 「大写键不锁定」与「小写长按变大」（2026-10-17）：两个默认关闭的输入行为开关。
     *
     * 前者：点一次大写键只锁一个大写字母，上屏即回落（英文 / 中文 caps / 搜索三条上屏分支都要复位）；
     * 开关关 = 历史行为（持续锁定到大写键再点一次）。后者：字母层 + 英文 + 非大写锁定时长按字母
     * 上大写，移动超阈值即取消。钉住字段名与外观页控件 id，防止「开关在、行为没接上」或反向。
     */
    @Test
    fun `大写一次性与长按转大写`() {
        val view = codeOf("PinyinKeyboardView.kt")
        val page = codeOf("KeyAppearanceActivity.kt")
        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_key_appearance.xml",
            "app/src/main/res/layout/activity_key_appearance.xml",
        )
        assertTrue("大写一次性状态字段丢失", "capsOneShotActive" in view)
        assertTrue("长按转大写没接进触摸处理", "longPressLowerUppercase" in view)
        assertTrue("外观页缺「大写键不锁定」开关", "switch_caps_oneshot" in layout)
        assertTrue("外观页缺「小写长按变大」开关", "switch_longpress_lower_uppercase" in layout)
        assertTrue("开关标题应写「大写键不锁定」", "\"大写键不锁定\"" in page)
        assertTrue("开关标题应写「小写长按变大」", "\"小写长按变大\"" in page)
        // 顺序：两个开关在「候选词的行数 / 界面明暗切换」两个下拉之前（2026-10-18 上移一行）
        val capsAt = layout.indexOf("switch_caps_oneshot")
        val rowsAt = layout.indexOf("spinner_candidate_rows")
        assertTrue("两个控件都要在布局里（否则 indexOf=-1 会让顺序断言恒真）", capsAt >= 0 && rowsAt >= 0)
        assertTrue("开关必须排在下拉控件之前", capsAt < rowsAt)
        assertTrue("大写一次性没有上屏后的复位入口", "releaseCapsOneShot()" in view)
        val prefs = codeOf("Prefs.kt")
        assertTrue(
            "两个开关默认必须关闭（缺省真值即现状行为）",
            "boolOr(KEY_CAPS_ONE_SHOT, false)" in prefs &&
                "boolOr(KEY_LONGPRESS_LOWER_UPPERCASE, false)" in prefs,
        )
    }

    /**
     * 「键高」拖动条（2026-10-09）：键区各行的高度由 [KeyAppearance] 定义域驱动。
     *
     * 三条判据对应的都是「改错了只在真机某一种形态下才显形」的情形：不套用 ⇒ 拖滑杆没反应；
     * 不跳过方向面板 ⇒ 九宫格被当成字母行改高；面板高度跟着键高走 ⇒ 最大档把小屏键盘顶出可视区
     * （面板固定 162dp×2 是有意为之，见 applyLetterRowHeight 的说明）。
     * 2026-10-21 起数字行也是字母区的一行（`qwertyuiop` 之上），故**不得**再按「只改前三行」早退。
     */
    @Test
    fun `键高必须作用于字母区每一行`() {
        val text = codeOf("PinyinKeyboardView.kt")
        assertTrue("各行没有按 keyHeightDp 套用高度", text.contains("applyLetterRowHeight(p.keyHeightDp, density)"))
        assertTrue("行高扫描必须跳过方向面板", text.contains("if (row.tag == DIRECTION_PANEL_TAG) continue"))
        assertFalse("键高不得只改前三行（数字行必须同步变高）", text.contains("rows >= LETTER_ROW_COUNT"))
        // 九宫格总高要跟随键高（BUG.md L-1332：行高与字母行同源，不再各算一份 dp 口径）
        assertTrue(
            "九宫格总高要跟随键高",
            text.contains("(prefs.keyHeightDp * resources.displayMetrics.density).roundToInt()") &&
                text.contains("rowPx - dp(2) * 2"),
        )
    }

    /**
     * 分词链：整串只查一次词库，未命中先早退（MEM-41，2026-10-09）。
     *
     * 判据取的是「只查一次」而不是「又一次也没查」：各路径 join 出来的 key 恒等于整串输入
     * （路径就是整串的一种划分），所以逐条枚举后再查属于纯重复；早退则省掉整棵 DFS 枚举树。
     * 两句必须同时存在 —— 只加早退而保留循环里的查询，等于白改一半。
     */
    @Test
    fun `分词先整串查一次再枚举`() {
        val text = codeOf("PinyinEngine.kt")
        // 「先…再…」用位置证明（BUG.md L-109）：两句都在时还要保证顺序，反了等于没早退
        assertBefore(
            text,
            "val hit = phrasesFor(input) ?: return null",
            "enumerateSegmentPaths(input, paths)",
            "整串命中判定必须排在枚举之前（否则白枚举整棵切分树）",
        )
        assertTrue(
            "路径循环里不许再逐条查词库（key 恒等于整串，属于重复查询）",
            !text.contains("val hit = phrasesFor(key)?.size ?: 0"),
        )
    }

    /**
     * 按键链的两处「省分配」不能退回去（2026-10-09）：合并缓存超限只淘汰一小撮、ue/ve 变体先走快路径。
     *
     * - `mergedCache.clear()` 整表打空会让紧随其后的每次查询重新合并（打字热路径上的顿挫）；
     * - `phraseKeysOf` 的两次 `contains("ue"/"ve")` 子串扫描在绝大多数键上是白花的（键里没有 u/v）。
     */
    @Test
    fun `查询热路径不得整表清缓存或全量扫变体`() {
        val text = codeOf("PinyinEngine.kt")
        assertTrue("合并缓存超限要走 trimMergedCache（只淘汰一小撮）", text.contains("trimMergedCache()"))
        assertTrue("不得整表 clear 合并缓存", !text.contains("mergedCache.size > 4096) mergedCache.clear()"))
        assertTrue(
            "phraseKeysOf 必须先走 u/v 快路径",
            text.contains("if (!raw.contains('u') && !raw.contains('v')) return setOf(raw)"),
        )
    }

    /**
     * 装配期「别再多存一份」不得退回去（2026-10-09 批 1 的 MEM-02 / MEM-03；2026-10-10 收紧到 L-1143）。
     *
     * - 索引组装（现行）：两段**大**段（键区 / 词区）边解析边写临时件，堆上只留两个长度数组与最终产物，
     *   构建期峰值 ≈ 1× 索引体积；装配时两个长度数组用 `writeTo` 直拷进目标数组，不再出中间数组。
     *   退回形态有两级：① 四段各留 `ByteArrayOutputStream`（峰值 ≈ 2×）；② 每段再 `toByteArray()` 再
     *   `arraycopy`（峰值 ≈ 3×，即 MEM-02 修的原始形态）—— 两条都在禁止之列。
     * - 自定义词库落盘：先压进 `ByteArrayOutputStream` 再 `toByteArray()` 同样存两份压缩结果；
     *   改成 XZ 直写文件流，收尾用 `finish()`（`close()` 会连文件流一起关，紧随的 `fd.sync()` 会抛）。
     *
     * 产物逐字节不变由 `IndexBuilderParityTest`（冻结产物 + 现跑脚本两层）与 `CustomDictsTest` 的往返用例把守。
     */
    @Test
    fun `装配与落盘不得再各存一份中间副本`() {
        val idx = codeOf("PhraseIndex.kt")
        // 前缀经常量拼装（BUG.md L-1220 加了 tempDir 入参），钉「仍走临时件」这一层
        assertTrue(
            "两段大段必须经临时件落盘（堆内不留整段缓冲）",
            idx.contains("createBuildTemp(BUILD_TEMP_PREFIX + \"keys\"") &&
                idx.contains("BUILD_TEMP_PREFIX = \"jinn_idx_\""),
        )
        assertTrue("临时件必须分片拷进目标数组", idx.contains("copyFileInto("))
        assertTrue("长度数组必须 writeTo 直拷进目标数组", idx.contains(".writeTo(sink)"))
        assertTrue("必须保留「尺寸先算好再分配」的写法", idx.contains("HEADER_SIZE + keyBytes + keyLens.size()"))
        assertTrue("必须保留单份上限闸", idx.contains("MAX_INDEX_BYTES"))
        assertTrue("不得再出现两个大段缓冲", !idx.contains("keyBlob") && !idx.contains("wordBlob"))
        assertTrue("不得再逐段 toByteArray 出中间数组", !idx.contains("val keys = keyBlob.toByteArray()"))

        val dicts = codeOf("CustomDicts.kt")
        val pack = blockAfter(dicts, "fun writePack(dir: File, pack: PackLines)")
        assertTrue("写包必须把 XZ 直接接到文件流上", pack.contains("XZOutputStream(out, LZMA2Options())"))
        assertTrue("XZ 收尾必须用 finish()（close 会连文件流一起关）", pack.contains("xz.finish()"))
        assertTrue("写包不得再经 encodePackLines 走内存压缩", !pack.contains("encodePackLines("))
    }

    /**
     * 后台线程都要有名字（2026-10-09 · BUG-33）。
     *
     * 诊断行头的 `[Thread-n]` 对应不到功能，拿日志查问题只能靠猜，崩溃快照里那些「谁在跑」的行同理。
     * 三种合法写法：构造式 `Thread({ … }, "jinn-xxx")`、收尾式 `.apply { …; name = "jinn-xxx" }`、
     * 先拿引用再 `thread.name = "jinn-xxx"`。
     *
     * 判据用**括号配对**定位线程 lambda 的收尾，不用固定行数窗口（那正是 L-1145 那族缺陷的形态：
     * 窗口一挪就判错）。`BackgroundIo.backgroundThread` 自身排除在外 —— 它的名字来自调用方参数。
     */
    @Test
    fun `后台线程都要有名字`() {
        val dir = listOf(File("app/src/main/java/com/jinn/inputmethod"), File("src/main/java/com/jinn/inputmethod"))
            .firstOrNull { it.isDirectory } ?: error("找不到生产源码目录")
        val offenders = mutableListOf<String>()
        for (file in dir.listFiles().orEmpty().filter { it.name.endsWith(".kt") }) {
            // 剥注释走共用 TestSources.codeOf（行数不变，行号仍可直接用；注释行不参与判据）
            val lines = TestSources.codeOf(file.readText()).split("\n")
            lines.forEachIndexed { i, raw ->
                val trimmed = raw.trim()
                // 前一个字符是字母的不算（`runOnUiThread {`、`Threads` 之类）
                val m = Regex("""(^|[^\w])Thread\s*[({]""").find(raw) ?: return@forEachIndexed
                val openIdx = raw.indexOf('{', m.range.first)
                var endLine = i
                if (openIdx >= 0) {
                    var depth = 0
                    outer@ for (k in i until lines.size) {
                        var inStr = false
                        var j = if (k == i) openIdx else 0
                        while (j < lines[k].length) {
                            val c = lines[k][j]
                            when {
                                c == '"' -> inStr = !inStr
                                !inStr && c == '/' && j + 1 < lines[k].length && lines[k][j + 1] == '/' -> break
                                !inStr && c == '{' -> depth++
                                !inStr && c == '}' -> {
                                    depth--
                                    if (depth == 0) {
                                        endLine = k
                                        break@outer
                                    }
                                }
                            }
                            j++
                        }
                    }
                }
                val window = lines.subList(i, minOf(lines.size, endLine + 6)).joinToString("\n")
                val named = "\"jinn-" in window || Regex(""",\s*name\s*[,)]""").containsMatchIn(window)
                if (!named) offenders += "${file.name}:${i + 1}  ${trimmed.take(72)}"
            }
        }
        assertTrue(
            "这些后台线程还没有名字（收尾处补 `name = \"jinn-xxx\"`）：\n    ${offenders.joinToString("\n    ")}",
            offenders.isEmpty(),
        )
    }

    /**
     * 图库整份拷贝的两条链路都必须走长活池（契约 C-1，2026-10-09）。
     *
     * 同一份 `GalleryInsert.copyToCache`（单张上限 20MB，见 `MAX_BYTES`）在两处被调用：图库选图页与
     * 键盘内的图库面板。键盘那条原先投在交互短活队列上 —— 而它恰好发生在用户刚复制完、准备粘贴的时刻，
     * 一次秒级拷贝会把剪贴板保存与面板首屏一起推后；图库页那条同款链路一直是 `runLong`，两条不一致。
     */
    @Test
    fun `图库整份拷贝的两条链路都必须走长活池`() {
        for (f in listOf("JinnIme.kt", "GalleryPickActivity.kt")) {
            val src = codeOf(f)
            val at = src.indexOf("GalleryInsert.copyToCache(")
            assertTrue("$f 里找不到 GalleryInsert.copyToCache 调用点（改名 / 搬迁后请同步本用例）", at >= 0)
            // 取调用点之前最近的一个 BackgroundIo 入口，必须是长活池那条。
            // ⚠ 形态必须是「`BackgroundIo.runLong {`」（尾随 lambda，没有左括号）；也别用不带后缀的
            // `BackgroundIo.run` 去比 —— 它是 `runLong` 的前缀，两个索引会落到同一处，判据恒假。
            val back = src.substring(0, at)
            assertTrue(
                "$f 的图库拷贝必须投长活池（BackgroundIo.runLong）—— 秒级拷贝排在交互队列上会推后剪贴板保存",
                back.lastIndexOf("BackgroundIo.runLong {") > back.lastIndexOf("BackgroundIo.run {"),
            )
        }
    }

    /**
     * 已有自定义词库时，导入入口必须先确认（BUG.md L-830）。
     *
     * 导入是**整体替换**（固定名 `custom_user.txt.xz`）：用户自己攒的词表会被选中的那个文件换掉，
     * 而同一张卡片上的「删除」早就有一道二次确认，导入反倒直接进选择器。
     * 反向一并钉住：SAF 类型保持开放通配 —— 限成 `text/plain` 会把很多 provider 报成
     * `application/octet-stream` 的正常 `.txt` 挡在门外，而内容层的把关在解析侧
     * （非法行计入跳过、无合法词条即拒存），不靠文件类型。
     */
    @Test
    fun `已有词库时导入必须先确认`() {
        val d = codeOf("DictManagerActivity.kt")
        val pick = blockAfter(d, "private fun openCustomPicker()")
        assertTrue(
            "已有词库时要先弹确认，不能直接进选择器",
            "CustomDicts.packFile(this).isFile" in pick && "TEXT_CUSTOM_IMPORT_CONFIRM" in pick,
        )
        assertTrue("确认通过之后才打开选择器", "launchCustomPicker()" in pick)
        val launch = blockAfter(d, "private fun launchCustomPicker()")
        assertTrue("SAF 类型必须保持开放（provider 的 .txt 常报 octet-stream）", "type = \"*/*\"" in launch)
        assertEquals("选择器只该有一个打开点", 1, Regex("""RC_CUSTOM_DICT\)""").findAll(d).count())
    }

    /**
     * 库重建后必须复位「凭据不留痕」的水位（BUG.md L-260）。
     *
     * 平台对损坏库的默认处理是**删库重建**，历史里最大 id 从 1 重来；而水位记的是旧库的 id ⇒
     * 不复位的话，新 id 永远小于旧水位、「没有新条目才跳过」恒成立 ⇒ 该进程内凭据清理彻底失效，
     * 用户之后再复制的 API Key 会明文留在剪贴板历史里（面板可见、随备份导出）。
     *
     * 复位点选在 `onCreate`：`SQLiteOpenHelper` 没有可覆写的 `onCorruption`（错误处理是平台默认的
     * `DefaultDatabaseErrorHandler`，会删掉整份库文件），而「库是全新的」这件事只有 `onCreate` 能作证 ——
     * 首次安装时它是无副作用的空操作。
     */
    @Test
    fun `库重建必须复位凭据清理水位`() {
        val db = codeOf("ClipboardDb.kt")
        val onCreate = blockAfter(db, "override fun onCreate(db: SQLiteDatabase)")
        assertTrue(
            "onCreate 必须复位凭据水位（库损坏重建后 id 从 1 重来）",
            "CredentialTrace.clearWatermarks()" in onCreate,
        )
        val trace = codeOf("CredentialTrace.kt")
        val clear = blockAfter(trace, "internal fun clearWatermarks()")
        assertTrue("复位必须清的是水位表本身", "purged.clear()" in clear)
        assertTrue(
            "水位判定必须仍是「旧水位 < 当前最大 id」（方向反了等于永不重扫）",
            "(purged[it] ?: -1L) < maxId" in trace,
        )
    }

    /**
     * 暂停时只在该页**真的写过**才同步落盘（BUG.md L-253）。
     *
     * `Prefs.flush` 是 `commit()`：无改动也照写整份 XML，且在**主线程**上等它写完 ——
     * 「打开页面再返回」这种一字未改的 onPause 不该付这笔磁盘写。两个写入点本就精确的页面
     * （凭据页、原文范围页）改走「写入结果参与判据」；OpenAI 页有 13 处无条件回写，要门控得先把
     * 它们改成「值不同才写」，属另一类改动，此条不覆盖。
     */
    @Test
    fun `暂停时只在该页真写过才同步落盘`() {
        val tr = codeOf("TranslationSettingsActivity.kt")
        assertTrue(
            "凭据页：saveCredentials 必须报告是否写过",
            "private fun saveCredentials(notify: Boolean): Boolean" in tr && "return wrote" in tr,
        )
        assertTrue(
            "凭据页：flush 必须挂在「写过」上",
            "if (wrote && !prefs.flush())" in tr,
        )
        val src = codeOf("TranslationSourceActivity.kt")
        assertTrue(
            "原文范围页：saveMaxBytes 必须报告是否写过",
            "private fun saveMaxBytes(): Boolean" in src,
        )
        assertTrue("原文范围页：值没变就不写", "val wrote = prefs.translateMaxBytesOf(currentId) != target" in src)
        assertTrue("原文范围页：flush 必须挂在「写过」上", "if (wrote && !prefs.flush())" in src)

        // OpenAI 页的写入项有 14 个且语义各异（数字字段归一、下拉与自定义框择一），不逐个改成条件写，
        // 而是**前后各取一次快照**比较：写入语义一字不动，只多一个「这次值有没有变」的结论。
        // 快照漏项 = 漏判（写了但没被看见 ⇒ 那次改动不落盘），所以抽样钉住几项。
        val oa = codeOf("OpenAiSettingsActivity.kt")
        assertTrue("OpenAI 页：saveValues 必须报告是否改过值", "private fun saveValues(): Boolean" in oa)
        assertTrue("OpenAI 页：结论必须来自前后快照比较", "return before != openAiSnapshot()" in oa)
        assertTrue("OpenAI 页：flush 必须挂在「改过」上", "if (wrote && !prefs.flush())" in oa)
        val snap = blockAfter(oa, "private fun openAiSnapshot()")
        for (f in listOf("openAiName", "openAiModel", "openAiTimeoutSec", "openAiExtraJson", "openAiTargetLanguage")) {
            assertTrue("快照必须覆盖 $f（漏项等于漏判）", f in snap)
        }
    }

    /**
     * `dumpLogcat` 的公开 KDoc 必须与实现同口径（BUG.md L-202）。
     *
     * 这段 KDoc 曾长期写着「无 root 时只给本进程、有 root 时是系统全量」「只剔本进程的 V 级行」，
     * 而实现早已是「默认 `--pid` 限定本进程 + 失败或为空退回全量一次 + 退回时剔所有进程的 V 级 +
     * 首行自证来源」。按旧 KDoc 读会把覆盖面判反：以为快照必然是系统全量（其实默认只有本进程），
     * 或以为退回全量时 V 级还在（其实连别家的一起剔了）—— 这份快照是要随诊断包外传的。
     */
    @Test
    fun `logcat 快照的 KDoc 必须与实现同口径`() {
        // 读**原文**（`codeOf` 会把 KDoc 一起剥掉，那就没得判了）
        val raw = TestSources.rawSourceOfShortName("Diagnostics.kt")
        val at = raw.indexOf("fun dumpLogcat(")
        assertTrue("找不到 dumpLogcat 的声明（改名后请同步本用例）", at > 0)
        // 注释定界符**必须拼接**：写成字面量会在本文件里构成注释开关，让 `TestSources.codeOf`
        // 把中间那段代码当注释吞掉（上批踩过同型：全仓例数当场少算 125 例）。
        val kdoc = raw.substring(0, at)
            .substringAfterLast("/" + "**")
            .substringBeforeLast("*" + "/")
        assertTrue("KDoc 必须写明默认带 --pid 抓", "--pid" in kdoc)
        assertTrue("KDoc 必须写明「失败或为空则退回全量」这条退路", "退回" in kdoc)
        assertTrue("KDoc 必须写明退回时连其它进程的 V 级一起剔", "其它进程" in kdoc)
        assertTrue("KDoc 必须写明首行的来源标签", "full(" in kdoc)
        assertFalse("KDoc 不得留着过时口径「有 root 时是系统全量」", "有 root 时是系统全量" in kdoc)
    }

    // ── 第五十三批（2026-10-10）：翻译成本与协议口径（L-1130 / L-1131）──────────────

    /**
     * 源文已是目标语言时必须在**发请求之前**拦下（BUG.md L-1131）。
     *
     * 判据不是「有没有这个分支」，而是**分支的位置**：拦在本地 ⇒ 零请求、零状态收拾；
     * 位置一旦挪到请求之后，用户就会重新经历「等一轮 + 看到内容一字未变 + 真计费」——
     * 这正是本条要修的现象，而只判 `contains("AlreadyTarget")` 时挪走也照样绿。
     */
    @Test
    fun `已是目标语言必须在发请求前拦下`() {
        val ime = codeOf("JinnIme.kt")
        assertBefore(
            ime, "is TranslateTarget.AlreadyTarget", "translateInFlight = true",
            "键盘侧：判定要排在 translateInFlight 置位之前（排在后面还得收拾按钮态与看门狗）",
        )
        assertBefore(
            ime, "is TranslateTarget.AlreadyTarget", "TranslationClient.translate(",
            "键盘侧：判定要排在发请求之前，否则等于没拦",
        )
        assertTrue(
            "提示要成句（TEXT_ALREADY_TARGET_LANGUAGE 一处定义、成句走 alreadyTargetMessage）",
            "alreadyTargetMessage(" in ime,
        )
    }

    // ── 第五十四批（2026-10-10）：剪贴板搜索的判据与提示（L-1134 / L-1135 / L-984）─────────

    /**
     * 搜索的命中判据与截断提示必须走纯层（BUG.md L-1134 / L-1135 / L-984）。
     *
     * 判据写在视图的扫描循环里时，「全半角」「零宽字符」这些边界只能真机试，而「库比扫描止损大、
     * 更旧的行根本没查」在界面上与「没找到」长得一模一样 —— 三处都由 `ClipboardSearch` 提供纯函数 +
     * `ClipboardSearchTest` 定值覆盖，这里只钉「视图必须调它、别再长回手写判据」。
     */
    @Test
    fun `搜索判据与截断提示必须走纯层`() {
        val view = codeOf("SearchPanelView.kt")
        assertTrue(
            "命中判据必须调 ClipboardSearch.matches",
            view.contains("ClipboardSearch.matches("),
        )
        assertFalse(
            "不得再手写 `content.lowercase().contains(...)`：它拿不到全半角 / 零宽的等价形态",
            view.contains(".lowercase().contains("),
        )
        assertTrue(
            "收尾截断状态必须由 ClipboardSearch.capState 判：命中截断与扫描止损要分开",
            view.contains("ClipboardSearch.capState("),
        )
        assertFalse(
            "截断提示不得写死上限数字：先触顶的可能是条数，也可能是驻留字节预算（L-984）",
            view.contains("只显示前 "),
        )
        assertTrue(
            "扫描止损必须用 ClipboardSearch.SCAN_LIMIT（视图里再存一份常量就会与纯函数判据脱节）",
            "ClipboardSearch.SCAN_LIMIT" in view && "SEARCH_SCAN_LIMIT" !in view,
        )
        // 管理页是第二个搜索入口：「与面板同口径」得真同源，别再各写一份过滤
        val history = codeOf("ClipboardHistoryActivity.kt")
        assertTrue(
            "管理页的搜索过滤也必须走 ClipboardSearch.matches",
            history.contains("ClipboardSearch.matches("),
        )
        assertFalse(
            "不得回到 `contains(keywordNow, ignoreCase = true)`：大小写对了，全半角与零宽仍搜不到",
            history.contains("contains(keywordNow, ignoreCase = true)"),
        )
    }

    // ── 第五十七批（2026-10-10）：日志清理的次序与异常兜底（L-972 / L-973）────────────

    /**
     * 清理与「目录正忙」的接线（BUG.md L-972 / L-973）。
     *
     * 这几处没法用纯函数测：能不能跳过得看锁与标志的时序（`dirLock` 可重入，快照 / 导出自己写日志时
     * 会重入清理判定，`tryLock` 照样成功），异常兜底更只有真机路径才碰得到。钉住三件事：两个目录流程
     * 都挂 busy 标志、清理先看标志再看锁、清理入口包住异常。
     */
    @Test
    fun `日志清理必须避开目录正忙并兜住异常`() {
        val code = codeOf("Diagnostics.kt")
        assertTrue(
            "清理必须先看 dirBusy（重入时 tryLock 挡不住自己人）",
            "if (dirBusy)" in code,
        )
        assertTrue("快照流程要挂 busy 标志", "withDirLockBusy { dumpLogcatLocked(" in code)
        assertTrue("导出流程要挂 busy 标志", "withDirLockBusy { exportBundleLocked(" in code)
        assertTrue(
            "跳过一轮要做短退避：占锁期间每条日志都重试一次并打一条 W（L-972①）",
            "cleanupRetryAt(" in code && "CLEANUP_SKIP_BACKOFF_MS" in code,
        )
        assertTrue(
            "清理入口必须包住异常：`listFiles()` 自身会抛，冒到业务调用点就违背「不影响业务」（L-973）",
            "runCatching { cleanupOldLogs() }" in code,
        )
    }

    /**
     * 配置导出 / 导入的失败原因不得走模块级共享字段（`BUG.md` L-1358，与 L-1354 同根）。
     *
     * 共享字段由后台线程写、主线程 UI 回调读，快速重复操作时会跨次串读：第 1 次的回调读到第 2 次的
     * 原因。现在原因随 `BackupResult.reason` 按本次调用返回，删掉 `lastError` / `lastImportError`。
     */
    @Test
    fun `配置导出导入失败原因不得走跨线程共享字段`() {
        val cfg = codeOf("ConfigBackupManager.kt")
        assertFalse("不得留模块级 lastError 共享字段", "var lastError" in cfg)
        assertFalse("不得留模块级 lastImportError 共享字段", "var lastImportError" in cfg)
        assertTrue(
            "export 要返回带原因的结果",
            "fun export(context: Context, options: ExportOptions): BackupResult<ExportOutcome>" in cfg,
        )
        assertTrue("import 要返回带原因的结果", "): BackupResult<ImportOutcome>" in cfg)
        val settings = codeOf("SettingsActivity.kt")
        assertFalse("UI 不得读 ConfigBackupManager.lastError", "ConfigBackupManager.lastError" in settings)
        assertFalse("UI 不得读 ConfigBackupManager.lastImportError", "ConfigBackupManager.lastImportError" in settings)
    }

    /**
     * 诊断导出失败必须留因、设备信息写入不得拖垮整包、界面按真因提示（`BUG.md` L-1312）。
     *
     * 先前 `exportBundleLocked` 整段 `runCatching{}.getOrNull()` 吞异常，`meta.writeText` 失败连累整包，
     * 而界面把所有失败都写成「日志目录不可读」——与「绝不静默吞异常」相抵，也给排障指错方向。
     */
    @Test
    fun `诊断导出失败必须留因且设备信息写入不得拖垮整包`() {
        val src = codeOf("Diagnostics.kt")
        assertTrue("设备信息写入必须单独兜住（失败只跳该条目）", "写设备信息失败（跳过该条目）" in src)
        assertTrue(
            "异常必须记类名，不得静默吞",
            "导出诊断包异常: \${it.javaClass.simpleName}" in src,
        )
        assertTrue("失败真因随返回值传递", "sealed interface ExportOutcome" in src)
        assertFalse("不得再用跨次共享的 lastBundleFailure（L-1354）", "lastBundleFailure" in src)
        val settings = codeOf("SettingsActivity.kt")
        assertFalse("界面不得再把失败写死成「日志目录不可读」", "\"日志目录不可读\"" in settings)
        assertTrue("界面按返回的真因分档", "outcome.reason" in settings)
    }

    /**
     * 凭据清理的全表解密不得持写监视器（`BUG.md` L-1361）：大历史下秒级全表解密若在
     * `@Synchronized` 内，会阻塞此刻的复制 / 粘贴 / 裁剪 / 删除。与 `reclassifyAll` 同口径 ——
     * 读+解密在锁外，只有「按 id 删除」段进锁。
     */
    @Test
    fun `凭据清理的全表解密不得持写监视器`() {
        val db = codeOf("ClipboardDb.kt")
        val at = db.indexOf("fun deleteByCleanedPlaintexts(")
        assertTrue("找不到 deleteByCleanedPlaintexts（改名了？）", at >= 0)
        val head = db.substring((at - 60).coerceAtLeast(0), at)
        assertFalse("全表解密不得整体 @Synchronized（A-26）", "@Synchronized" in head)
        val body = db.substring(at, (at + 1400).coerceAtMost(db.length))
        assertTrue("按 id 删除段仍须进写监视器", "synchronized(this)" in body)
    }

    /**
     * 配置导入部分成功时 `reason` 也要带真因（`BUG.md` L-1365）：否则消费者按 `reason` 取值会丢具体阶段。
     */
    @Test
    fun `配置导入部分成功时原因也要带出`() {
        val cfg = codeOf("ConfigBackupManager.kt")
        assertTrue("import 的结果要把 failedStage 兜进 reason", "reason.reason ?: outcome?.failedStage" in cfg)
    }

    // ── 第五十八批（2026-10-10）：收藏体积「存原值、算生效值」（L-982 / L-988）──────────

    /**
     * 收藏体积软上限的**写侧**不得按动态上限（历史体积的 1/4）钳位（BUG.md L-982 / L-988）。
     *
     * 动态上限随历史体积变；它一旦参与写侧，用户「先调小历史体积、再调回」就再也拿不回原值 ——
     * 值是真丢，而界面上看不出任何异常。判据就看三处写侧与一处读侧（淘汰必须用生效值）。
     */
    @Test
    fun `收藏体积写侧不得按动态上限钳位`() {
        val prefs = codeOf("ClipboardPrefs.kt")
        assertFalse(
            "setter / 批量保存 / 导入都不得再用 `coerceIn(1, favoriteBytesCapMb(...))`",
            "coerceIn(1, favoriteBytesCapMb(" in prefs,
        )
        assertTrue("存储值统一走静态钳位", "favoriteMaxMbStored(" in prefs)
        assertTrue("生效值单独算", "effectiveFavoriteMaxMb(" in prefs)
        assertTrue(
            "淘汰侧必须用生效值（原值可能大于 1/4，用它当预算等于把未生效的部分放行）",
            "get() = effectiveFavoriteMaxMb(favoriteMaxBytesMb, maxTotalBytesMb)" in prefs,
        )
        val page = codeOf("ClipboardCustomizeActivity.kt")
        assertFalse(
            "自定义页不得在草稿层把收藏体积压到动态上限",
            "minOf(draft.favMaxMb, cap)" in page,
        )
        assertTrue(
            "自定义页要显示实际生效值",
            "effectiveFavoriteMaxMb(draft.favMaxMb, draft.maxTotalMb)" in page,
        )
        // L-1221：收紧确认框也要按**生效值**比（原值 100→50 而生效值同为 2MB 时不该弹「会删除」）
        assertTrue("收紧判据按生效值", "effectiveFavMb(draft) < effectiveFavMb(snapshot)" in page)
        assertFalse(
            "不得按原值判收紧（原值差而生效率可能相同）",
            "draft.favMaxMb < snapshot.favMaxMb" in page,
        )
    }

    // ── 第五十九批（2026-10-10）：符号层的两处读屏口径（L-1138 / L-1139）───────────────

    /**
     * 读屏口径的接线（BUG.md L-1138 / L-1139）。
     *
     * 两处都没法用纯函数测到底：一处是 `AccessibilityNodeInfo` 的声明（要框架才建得出节点），
     * 一处是 ImageButton 的 `contentDescription`（要真机读屏才听得见）。钉住的是**判据同源**与
     * **不再用字形当名字**这两件在源码上就能判的事。
     */
    @Test
    fun `符号层的读屏名字与可点性必须同源`() {
        val key = codeOf("PinyinKey.kt")
        assertTrue("空槽键的判据要在节点声明处生效", "activateAvailable()" in key)
        assertTrue(
            "两个入口都要判（节点可能是切层前建的，辅助服务手里那份还没刷新）",
            Regex("activateAvailable\\(\\)").findAll(key).count() >= 2,
        )
        assertTrue("判据本身要能判「回调在 + 判据放行」", "onActivate != null && (canActivate?.invoke()" in key)

        val view = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "可点性判据必须与「空槽不报反馈」用同一条（都看 symbolKeyHasContent）",
            "key.canActivate = { layer != LAYER_SYMBOL || symbolKeyHasContent(c) }" in view,
        )
        assertTrue(
            "空槽键在读屏里要有个名字（不能用 null 留成无名按钮）",
            "key.contentDescription = TEXT_EMPTY_SLOT" in view,
        )

        val order = codeOf("SymbolOrderActivity.kt")
        assertFalse(
            "排序按钮不得再拿字形资源当无障碍名（读屏只会念「上箭头」）",
            "getString(R.string.symbol_order_move" in order,
        )
        assertTrue("名字要走「动作 + 对象」的构造", "SymbolOrder.moveDescription(" in order)
    }

    /**
     * 词频落盘、导入预算闸、出库类型与符号缺组的四处收口
     * （BUG.md L-1274 / L-1275 / L-1277 / L-1279）。
     */
    @Test
    fun `词频与导入的四处收口`() {
        val uf = codeOf("UserFrequency.kt")
        assertTrue("关闭学习后不得落盘（防抖尾沿会把空表写回）", "if (!enabled) return@synchronized" in uf)
        assertTrue("关闭学习后 flush 也不落盘", "if (!enabled) return" in uf)
        val mgr = codeOf("ConfigBackupManager.kt")
        assertTrue(
            "导入预算闸必须在词库落盘之前",
            mgr.indexOf("if (budget.exhausted)") < mgr.indexOf("restoreDicts(File("),
        )
        assertTrue(
            "出库类型按字节嗅探",
            "ClipboardImageCodec.sniffMimeOrNull(bytes)" in codeOf("ClipboardImageExport.kt"),
        )
        assertTrue("符号分组缺失要留痕", "无对应数据，已跳过" in codeOf("SymbolOrder.kt"))

        // L-1286：关开关之前必须先把「刚学的那一次」写下去（flush 要在置 false 之前）
        assertTrue("关闭学习前先落盘", uf.indexOf("flush()") in 1 until uf.indexOf("enabled = value"))
        // L-1287：写系统剪贴板失败的临时件要回收
        assertTrue("失败分支清理临时件", "已清理临时件" in codeOf("ClipboardImageExport.kt"))
        // L-1285：剪贴板类型不可靠时按字节嗅探兜底，并留痕（判型收敛到规范入口，见 L-1302/1303）
        val ctrl = codeOf("ClipboardController.kt")
        assertTrue("类型不可靠时嗅探兜底", "ClipboardImageCodec.sniffMimeOrNull(headBytes)" in ctrl)
        assertTrue("判否要留痕", "按字节嗅探不是图片" in ctrl)
        // L-1302：通配 image/* 不算可靠声明（否则跳过字节判定、非图片内容按兜底 png 入库）
        assertTrue("通配声明不算可靠", "declared != \"image/*\"" in ctrl)
        // L-1300 / L-1301：导出写入中断要回收刚建的那一行 / 那个文档
        val exp = codeOf("ClipboardImageExport.kt")
        assertTrue("相册写入中断回收行", "保存到相册写入中断" in exp)
        // L-1301：写目录树中断要删半截文档（文案随「转移到图库 / 导出全部」共用一处而改口径）
        assertTrue("写树中断删文档", "目录树写入中断，已删半截文档" in exp)
        // 需求：导出全部图片的目录由用户选（SAF 树），回执用阻断式弹框写明落点
        val exportPage = codeOf("ClipboardHistoryActivity.kt")
        assertTrue("目录可选", "ActivityResultContracts.OpenDocumentTree()" in exportPage)
        assertTrue(
            "取持久读写授权",
            "Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION" in exportPage,
        )
        assertTrue("记住所选目录", "Prefs(this).clipExportTreeUri = uri.toString()" in exportPage)
        assertTrue("回执写明落点", "showExportReceipt(ok, fail, path)" in exportPage)
        assertTrue("落点走可读路径函数", "ClipboardImageExport.displayPath(this, treeUri)" in exportPage)
        assertTrue("回执阻断式", ".setCancelable(false)" in exportPage)
        assertTrue("写树共用一处", "ClipboardImageExport.writeToTree(this, treeUri, item, bytes)" in exportPage)
        // 记住的目录写不进时**第一张即中止**：否则会把「换个目录」报成「图片都坏了」
        assertTrue("首张当探针", "if (attempts == 0) {" in exportPage)
        // SAF 目录树全 API 可用 ⇒ 该页不再有「系统版本过低」这条闸（单张保存到相册仍受它约束）
        assertFalse("导出不再有版本闸", "TEXT_IMAGE_EXPORT_UNAVAILABLE" in exportPage)
        // 日志只记计数：落点路径会随诊断包外带（L-1036 口径）
        assertTrue(
            "导出日志不记路径",
            "\"导出全部图片: 成功=\$ok 失败=\$fail 目录可用=\${!dirBad}\"" in exportPage,
        )
        assertTrue("落点路径纯函数", "fun externalStoragePath(" in exp)
        // 需求：图片预览的动作分两行（一排放不下：1080px 屏上第 4 个按钮起被压成一列竖字，
        // 删除 / 关闭 直接挤出屏幕）—— 按语义分成「出库」与「管理」两排
        assertTrue("出库一排", "val rowOut = makeBtnRow()" in exportPage)
        assertTrue("管理一排", "val rowManage = makeBtnRow()" in exportPage)
        // 图片弹层的动作必须落进某一排（旧写法是只带文案的 addBtn(...) 直塞单行）
        assertFalse("图片动作不得再单行直塞", "addBtn(TEXT_IMAGE_COPY)" in exportPage)
        // L-1303：出库与交付共用一个判型入口
        assertTrue("判型入口可复用", "internal fun mimeOf" in exp)
        assertTrue(
            "交付路径共用判型",
            "ClipboardImageExport.mimeOf(item, bytes)" in codeOf("JinnIme.kt"),
        )
        // 需求：2 级词库未就位时，「补充短语词库」入口要比旁边那个更显眼（词库决定能打出多少词）
        val settingsSrc = codeOf("SettingsActivity.kt")
        assertTrue("入口有提醒态", "private fun refreshDictEntryEmphasis()" in settingsSrc)
        assertTrue("提醒态换成主按钮底图", "R.drawable.btn_aurora_primary" in settingsSrc)
        assertTrue("常规态回到次级底图", "R.drawable.btn_aurora_secondary" in settingsSrc)
        assertTrue("判据与词库页同源", "OptionalDicts.isInstalled(" in settingsSrc)
        assertTrue("进页面就要应用", "refreshDictEntryEmphasis()" in settingsSrc)
        assertTrue(
            "词库页用同一判据",
            "OptionalDicts.isInstalled(dictDir(), dict)" in codeOf("DictManagerActivity.kt"),
        )
        // L-1281 / L-1319：词频补载进写锁并做换代校验；flush 有失败出口，导出 / 导入据此如实说明
        val ufSrc = codeOf("UserFrequency.kt")
        assertTrue("补载有换代校验", "internal fun applyReload(" in ufSrc)
        assertTrue("补载在锁内并入", "synchronized(saveLock)" in ufSrc)
        assertTrue("替换时换代", "version++" in ufSrc)
        assertTrue("flush 返回写盘结果", "fun flush(): Boolean" in ufSrc)
        val cbmSrc = codeOf("ConfigBackupManager.kt")
        assertTrue("导出据实记录词频状态", "val freqStale = !runCatching { UserFrequency.flush() }" in cbmSrc)
        assertTrue("导入失败即跳过词频段", "词频未落盘（已跳过词频导入）" in cbmSrc)
        assertTrue("回执带词频说明", "用户词频未能落盘" in codeOf("SettingsActivity.kt"))
        // L-1330：缩略图的三条失败路径（读取 / 解密 / 解码）都要记负缓存，否则每格反复重试 + 刷日志
        val thumb = codeOf("ClipboardThumbLoader.kt")
        assertEquals("三条失败路径都要记负缓存", 3, Regex("""markFailed\(key\)""").findAll(thumb).count())
        // L-1329：图库设置页重画前先提交草稿；两个拖动条都要忽略程序化同步（否则凭空落草稿）
        val gal = codeOf("GallerySettingsActivity.kt")
        assertTrue("重画前先提交草稿", "private fun render() {\n        commitDrafts()" in gal)
        assertEquals(
            "两个拖动条都要忽略程序化更新",
            2,
            Regex("""if \(!fromUser\) return""").findAll(gal).count(),
        )
        // L-1315：日志清理失败也要**短退避**（原先只有裸 logcat，时间闸已推进 ⇒ 跑到 24 小时后）
        val diagSrc = codeOf("Diagnostics.kt")
        assertTrue(
            "失败路径写回短退避",
            "lastCleanupAt = cleanupRetryAt(now, CLEANUP_INTERVAL_MS, CLEANUP_SKIP_BACKOFF_MS)" in diagSrc,
        )
        // 顺序有讲究：下面那行日志会重进本函数，先写回时间闸才不会多跑一轮判定
        assertTrue(
            "先写回时间闸再记日志",
            diagSrc.indexOf("lastCleanupAt = cleanupRetryAt(now,") in 1..<diagSrc.indexOf("日志清理失败: "),
        )
        // L-1311：`ui.post` 返回 false 只在主 Looper 已退出时发生 —— 不得在 IO 线程就地改状态，
        // 注释里的旧模型（「视图分离 ⇒ 队列不再 drain」）也要清掉
        val imeSrc = codeOf("JinnIme.kt")
        assertFalse("不得再在 IO 线程就地收尾", "markTranslateFailed(\"回调未入队" in imeSrc)
        assertFalse("旧模型的说法要清掉", "消息进了不会再 drain 的队列" in imeSrc)
        assertTrue("保留一行痕迹即可", "回调未能入队（主 Looper 已退出）" in imeSrc)
        // L-1333：面板里不得留无门控的布局探针（排查面板空窗那轮的遗留物）
        assertTrue("无遗留布局探针", "ImeLayoutProbe" !in codeOf("PinyinKeyboardView.kt"))
        assertTrue("无遗留布局探针(JinnIme)", "ImeLayoutProbe" !in codeOf("JinnIme.kt"))
        // L-1325：导入中途异常也要保住已累计统计（回执说「已导入的部分保留」）
        val impSrc = codeOf("ClipboardFileImporter.kt")
        assertTrue("中途异常单独归因", "Abort.INTERRUPTED" in impSrc)
        assertTrue("回执说部分保留", "中途出错，已中止（已导入的部分保留）" in codeOf("ClipboardHistoryActivity.kt"))
        // L-1326：搜索态下导入要清搜索并说明（否则新条目被旧关键词整批滤掉）
        val histNow = codeOf("ClipboardHistoryActivity.kt")
        assertTrue("导入后清搜索", "已清空搜索，便于看到刚导入的条目" in histNow)
        // L-1327 / L-1328：截断判据要含总数；「有行但读不出」要有独立文案
        assertTrue("截断判据含总数", "cappedByCount && total > maxResults" in histNow)
        assertTrue("不可读独立文案", "TEXT_UNREADABLE" in histNow)
        // L-1331 / L-1332：候选栏与方向面板的几何都从单一来源派生（不手写同式子）
        val pv2 = codeOf("PinyinKeyboardView.kt")
        // 2026-10-21：栏高分「收起档（baseRows，键区让位用）」与「展开态（按整行键高取整）」两路，
        // 仍然只能从派生函数取，不许手写加法
        assertTrue(
            "栏高走派生函数",
            Regex("""CandidateRows\.barHeightPx\(\s*\n\s*baseRows,""").containsMatchIn(pv2),
        )
        assertTrue("复用块走 px 派生", "CandidateRows.barHeightPx(\n            CandidateRows.SINGLE," in pv2)
        assertTrue("方向面板行高同源", "rowPx - dp(2) * 2" in pv2)
        // L-1321：图库收起也必须作废待揭示（否则 300ms 后面板自己亮出来 / 跨会话残留）
        val view = codeOf("PinyinKeyboardView.kt")
        // 两端锚点都断言（`window` 缺失即抛错）：裸 `substringAfter(...).substringBefore(...)` 分两句写时，
        // 元守卫看不见这条链 —— 左锚点一消失，切片就变成「文件头 → 那句早退」，而 `cancelPendingPanelReveal()`
        // 在全文另有 6 处（含它自己的定义），断言照样绿（BUG.md L-1145 的同型，见 L-1428）
        assertTrue(
            "图库收起要作废待揭示",
            "cancelPendingPanelReveal()" in
                TestSources.window(view, "fun hideGalleryPanel()", "if (!galleryActive) return"),
        )
        // L-1322：树授权要拿「读 + 写」两个位，且缺写授权时如实告知（用户要的动作是重绑目录）
        val histSrc = codeOf("ClipboardHistoryActivity.kt")
        assertTrue("绑定申请写位", "FLAG_GRANT_WRITE_URI_PERMISSION" in codeOf("GallerySettingsActivity.kt"))
        assertTrue("释放也按读写位", "FLAG_GRANT_WRITE_URI_PERMISSION" in codeOf("Prefs.kt"))
        assertTrue("缺写授权单独归因", "MoveResult.Denied" in codeOf("ClipboardImageExport.kt"))
        assertTrue("缺写授权有文案", "TEXT_IMAGE_MOVE_DENIED" in histSrc)
        // L-1323：页号钳位必须排在适配器刷新之前（刷新时越界 ⇒ getCount()==0 被记住，页面空白）
        assertTrue(
            "钳位在刷新之前",
            "clampPageIndex()\n                adapter.notifyDataSetChanged()" in histSrc,
        )
        // L-1324：配置导入后要刷「正在显示」的外观与主题（导入绕过监听器直接写 prefs）
        val settings = codeOf("SettingsActivity.kt")
        assertTrue("导入后刷外观", "JinnIme.onKeyAppearanceChanged()" in settings)
        assertTrue("导入后刷主题", "JinnIme.notifyThemeChanged()" in settings)
        // L-1220：索引构建的临时件落私有索引目录（不再依赖 java.io.tmpdir 的存在与可写），并清残留
        assertTrue("构建可传临时目录", "tempDir: java.io.File? = null" in codeOf("PhraseIndex.kt"))
        assertTrue("调用方传私有目录", "tempDir = indexDir" in codeOf("PinyinEngine.kt"))
        assertTrue("启动清构建残留", "clearStaleBuildTemps" in codeOf("PinyinEngine.kt"))
        // L-1226：批量导入的裁剪延后到块末（逐条裁 = 每行四条全表聚合），末尾必裁一次。
        // L-1536 之后入库改成「一块一个事务」（`ClipboardStore.saveChunk`），裁剪仍在块**外**做
        val importer = codeOf("ClipboardFileImporter.kt")
        assertTrue("攒批裁剪有常量", "internal const val TRIM_EVERY" in importer)
        assertTrue("写入走块事务", "ClipboardStore.saveChunk(" in importer)
        assertTrue("裁剪在块外", "db.trimTo(maxItems, maxTotalBytes)" in importer)
        assertTrue("入库侧按上限是否给裁", "if (maxItems != null) trimTo(" in codeOf("ClipboardDb.kt"))
        // L-1227：回执要报「实际保留」而不是插入成功数
        assertTrue("回执报实际保留", "实际保留 " in codeOf("ClipboardHistoryActivity.kt"))
        // L-1280：嗅探与记录类型冲突要留痕（真 PNG 不再走回落分支）
        assertTrue("类型冲突留痕", "类型不一致：" in codeOf("ClipboardImageExport.kt"))
        // L-1320：面板等首屏内容就绪再揭示（先出面板 = 一整块空面板，即「面板全屏化」瞬帧）
        assertTrue("面板揭示有等待机制", "private fun armPanelReveal(reveal: () -> Unit)" in
            codeOf("PinyinKeyboardView.kt"))
        assertTrue("收起要作废待揭示", "cancelPendingPanelReveal()" in
            codeOf("PinyinKeyboardView.kt"))
        assertTrue("剪贴板面板报首屏就绪", "hasCachedContent()" in codeOf("ClipboardPanelView.kt"))
        assertTrue("取数中要说正在载入", "TEXT_EMPTY_LOADING" in codeOf("ClipboardPanelView.kt"))
        assertTrue("图库首图即就绪", "markFirstContentReady()" in codeOf("GalleryPanelView.kt"))
        // L-1294：图片网格用当前页快照渲染与响应点击（翻页后不再停在旧页）
        val hist = codeOf("ClipboardHistoryActivity.kt")
        assertTrue("网格按快照取数", "private var gridPageItems" in hist)
        assertTrue("翻页要刷网格", "refreshPage()" in hist)
        assertTrue("网格点击按快照", "gridPageItems.getOrNull(position)" in hist)
        // L-1296：弹框前要判页面是否已销毁（图片动作回执来自长活池）
        assertTrue("弹框判销毁", "if (isFinishing || isDestroyed) return\n" in hist)
        // L-1297：导出中标志进程级、进度框随页面收起
        assertTrue("导出标志进程级", "EXPORTING.compareAndSet(false, true)" in hist)
        assertTrue("导出框随页面收起", "exportDlg?.dismiss()" in hist)
        // L-1298：三处重载都要带当前筛选（默认参数是 false）
        assertTrue(
            "重载带筛选态",
            "imageGrid.show(tid, favoritesOnly = imageFavOnly)" in codeOf("ClipboardPanelView.kt"),
        )
        // L-1299：失败态说真话 + 条带「空但没到底」不收起（续页判据按可见性）
        val grid = codeOf("ClipboardImageGridView.kt")
        assertTrue("失败态独立文案", "TEXT_LOAD_FAILED" in grid)
        assertTrue("续页判据按可见性", "!isShown || width <= 0" in grid)
        assertTrue("空但没到底不收起", "empty && exhausted" in hist)
        assertTrue("面板同判据", "empty && exhausted" in codeOf("ClipboardPanelView.kt"))
        // L-1288：嗅探不得在剪贴板监听回调里开流（跨进程 IO 会拖主线程）——
        // 判定随声明类型交给长活池，同一次开流完成
        assertTrue("嗅探不在监听回调里开流", "sniffImageFromUri" !in ctrl)
        assertTrue("声明类型传后台", "BackgroundIo.runLong { saveImageFromUri(uri, declared) }" in ctrl)
        // L-1289：「不是图片」与「读不到」必须分开记（否则排障会怀疑用户复制的不是图）
        assertTrue("读不到单独归因", "读剪贴板图片失败" in ctrl)
        assertTrue("一次开流读头部", "private fun readImageOnce" in ctrl)
        // 历史页补上「布局」入口（与键盘面板的键同名同义）
        assertTrue("历史页有布局入口", "TEXT_IMAGE_LAYOUT" in codeOf("ClipboardHistoryActivity.kt"))
    }

    /**
     * 拼音查询热路径与图库设置页的落盘收口（BUG.md L-1283 / L-1284）。
     */
    @Test
    fun `查询切分复用与设置页落盘`() {
        // L-1283：补全路径复用主查询已切的音节，不得对同一输入二次 segment
        val engine = codeOf("PinyinEngine.kt")
        assertTrue("补全复用切分结果", "queryWithCompletion(raw, syllables, partial)" in engine)
        assertTrue("重载接收切分结果", "partial: String,\n    ): List<String> {" in engine)
        // L-1284：拖动只记草稿，松手 / 离开时统一落盘并核对（失败提示）
        val gallery = codeOf("GallerySettingsActivity.kt")
        assertTrue("拖动只记草稿（每行张数）", "draftColumns = value" in gallery)
        assertTrue("拖动只记草稿（行高）", "draftHeight = value" in gallery)
        assertTrue("落盘要核对结果", "prefs.flush()" in gallery)
        assertTrue("失败要提示", "TEXT_LAYOUT_SAVE_FAILED" in gallery)
    }

    /**
     * 存储统计、缩略图负缓存、相册写入与收藏条带的自适应（BUG.md L-1233 / L-1237 / L-1238）。
     */
    @Test
    fun `存储统计与相册写入的收口`() {
        // L-1233：统计不得投在交互短活池（单线程 FIFO，遍历会堵住采集与粘贴）
        assertTrue("存储统计投长活池", "BackgroundIo.runLong {" in codeOf("StorageUsageActivity.kt"))
        // L-1237：缩略图解码失败要记负缓存（同图短时间内不再反复排队）
        assertTrue(
            "解码失败记负缓存",
            "failedAt[key] = System.currentTimeMillis()" in codeOf("ClipboardThumbLoader.kt"),
        )
        // L-1238：相册写入走「写入中」标记，文件名到毫秒
        val exp = codeOf("ClipboardImageExport.kt")
        assertTrue("相册写入有 pending 标记", "IS_PENDING, 1" in exp && "IS_PENDING, 0" in exp)
        assertTrue("文件名精确到毫秒", "yyyyMMdd_HHmmss_SSS" in exp)
        // 收藏条带按实际行数自适应（历史页与键盘面板两处）
        assertTrue(
            "历史页条带自适应",
            "applyFavStripHeight(count)" in codeOf("ClipboardHistoryActivity.kt"),
        )
        assertTrue(
            "面板条带自适应",
            "applyGridHeight(imageMode = false, imageCount = count)" in codeOf("ClipboardPanelView.kt"),
        )
    }

    /**
     * 图库目录授权的唯一入口与已装词库包的版本判据（BUG.md L-1056 / L-1234）。
     */
    @Test
    fun `目录授权回收与包版本判据`() {
        // L-1056：替换图库目录前必须先还旧授权 —— 收敛到 setter 后，设置页与配置导入三条路径一致
        val prefs = codeOf("Prefs.kt")
        assertTrue("换目录前先还旧授权", "releaseTreePermission(old)" in prefs)
        assertTrue("释放要容错并留痕", "releasePersistableUriPermission" in prefs)
        // L-1234：已装包版本判据的容差从 512KB 收到 64KB（漏判方向是「旧包永久留着」）
        assertTrue(
            "包体积容差收紧到 64KB",
            "PACK_SIZE_TOLERANCE_BYTES = 64L * 1024" in codeOf("OptionalDicts.kt"),
        )
    }

    /**
     * 方向面板行高跟随键高、外观注释不写死旧定义域（BUG.md L-1290 / L-1291）。
     */
    @Test
    fun `方向面板行高与外观注释`() {
        val view = codeOf("PinyinKeyboardView.kt")
        // L-1290：展开期间改键高也要重算面板行高（构建路径只覆盖「展开前已改好」）
        assertTrue("展开期间重算行高", "applyDirectionRowHeights(it)" in view)
        assertTrue("行高算式抽成函数", "private fun directionRowHeightPx()" in view)
        // L-1291：注释里的定义域不得写死旧数字（翻倍后旧值会变成估预算的错依据）
        assertTrue("字距注释不再写死旧域", "5~30dp" !in codeOf("KeyAppearance.kt"))
        assertTrue("字号注释不再写死旧域", "14~28sp" !in codeOf("CandidateText.kt"))
        assertTrue("键高注释不再写死旧档", "最大档（80dp" !in view)
    }

    /**
     * 贴图插入保留输入法组合态（BUG.md L-1099）。
     */
    @Test
    fun `AI 动作不得撞上翻译专属的判据与措辞`() {
        val ime = codeOf("JinnIme.kt")
        // 文案里指路用的设置页名（「翻译设置」页 → 「翻译原文范围」页）是合规内容：
        // 去掉它们之后仍不得出现「翻译」二字，才算没说死动作
        fun cleanUiPaths(s: String): String =
            s.replace("翻译设置", "").replace("翻译原文范围", "")

        // L-1310：与 8 个 AI 动作共用的出口文案不得写死「翻译 / 译文」—— 点「润色」的用户收到
        // 「翻译已取消」会以为点错了按钮。唯一合法的例外是指路用的设置页名「翻译设置」。
        val defs = Regex("const val TEXT_TRANSLATE_[A-Z_]+ =[^\\n]*(\\n\\s+\"[^\"]*\")?")
            .findAll(ime).map { it.value }.toList()
        assertTrue("共用文案定义取到了（提取器失效会让本守卫假绿）", defs.size >= 15)
        val literals = defs.flatMap { d ->
            Regex("\"([^\"]*)\"").findAll(d).map { it.groupValues[1] }.toList()
        }
        assertTrue("共用文案不得出现「译文」", literals.none { it.contains("译文") })
        assertTrue(
            "共用文案不得写死「翻译」（设置页名除外）",
            literals.none { cleanUiPaths(it).contains("翻译") },
        )
        // 错误文案会直接 toast 给用户：同一条口径
        val errBlock = TestSources.window(
            codeOf("Translation.kt"), "enum class TranslationError", "enum class",
        )
        val errLiterals = Regex("\"([^\"]*)\"").findAll(errBlock).map { it.groupValues[1] }.toList()
        assertTrue("错误文案取到了", errLiterals.size >= 8)
        assertTrue("错误文案不得出现「译文」", errLiterals.none { it.contains("译文") })
        assertTrue(
            "错误文案不得写死「翻译」（设置页名除外）",
            errLiterals.none { cleanUiPaths(it).contains("翻译") },
        )

        // L-1309：日志 / 失败归因 / 看门狗读的目标名必须与真实请求同源（同语对调后是 mutualTarget）
        assertTrue(
            "目标名与请求同源",
            "translateTargetName = effectiveTargetLabel(id, mutualTarget, prefs.openAiTargetLanguage)" in ime,
        )
        // 「原文已是目标语言 ⇒ 不发」只对翻译成立：润色等动作的产出与目标语言无关，
        // 按它拦下等于把用户的付费动作静默吞掉（还回一句与动作不相干的话）
        assertTrue(
            "已是目标语言的闸只对翻译",
            "if (id != TranslationProviderId.OPENAI && action == AiAction.TRANSLATE) {" in ime,
        )
        // L-1308：请求侧指纹（比键）与结果侧指纹（比内容）是两条判据，缺一条就会白花一次请求
        assertTrue("结果侧去重判据", "slice.text == lastCommittedText" in ime)
        assertTrue("结果侧记账用实发串", "lastCommittedText = submit" in ime)
        assertTrue("提示与请求侧分开", "TEXT_TRANSLATE_RESULT_REPEATED" in ime)
        assertTrue("跨输入框一并复位", "lastCommittedText = \"\"" in ime)
    }

    @Test
    fun `贴图走区间追加而非整段重建`() {
        val ret = codeOf("ReceivingEditText.kt")
        // setText 会替换整份文本、顺带清掉 IME 组合态 ⇒ 改为在 Editable 上区间追加
        assertTrue("贴图取 Editable 走追加", "val editable = getText() as? Editable" in ret)
        assertTrue("追加前先收尾组合区", "BaseInputConnection.removeComposingSpans(editable)" in ret)
        // 保底分支仍在（宿主塞非 Editable 时）
        assertTrue("保底分支保留", "SpannableStringBuilder(getText()).append(OBJECT_REPLACEMENT)" in ret)
    }

    @Test
    fun `拼音打标只改显示`() {
        val view = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "双拼关闭「双拼候选全音」时必须原样显示按键字母（不打标）",
            "shuangpinMode && !prefs.showQuanpin -> input" in view,
        )
        assertTrue(
            "双拼开启总开关时必须显示打标后的全拼",
            // 声韵档的显示串走 displayQuanpin（绝不丢键），查询串仍走 toQuanpin（丢弃非字母键）。
            // 两者是两份实现，早先为省一次转换共用查询串 ⇒ 分号键一按拼音行就空白（BUG.md L-1461）
            "shuangpinMode -> PinyinEngine.markSyllables(Shuangpin.displayQuanpin(input, scheme))" in view &&
                "val queryInput = if (shuangpinMode) Shuangpin.toQuanpin(input, scheme) else input" in view,
        )
        assertTrue(
            "全拼模式必须按音节打标",
            // 全拼模式下未打标的串就是 input，仍按音节打标（只是不再重复转换与分词）
            "else -> PinyinEngine.markSyllables(input)" in view,
        )
        // 反向钉：显示串不许再拿查询串打标（分号 / 任何非字母键都会在转换里被丢弃）
        assertFalse(
            "显示串不得复用查询串（会吞掉分号键）",
            "markSyllables(queryInput)" in view,
        )
        assertFalse("打标不得再读独立开关", "pinyinSyllableMark" in view)
        assertFalse(
            "外观页与配置里不得残留打标开关",
            "switch_pinyin_mark" in codeOf("KeyAppearanceActivity.kt") ||
                "pinyinSyllableMark" in codeOf("Prefs.kt"),
        )
        // 拼音串本身不带分隔符：composing 仍是逐键追加，查询走 queryInput
        assertTrue("composing 不得被插入分隔符", "composing.append(c)" in view)
        assertTrue("查询仍用未打标的串", "queryInput" in view)
    }

    /**
     * 候选栏展开 / 收起与「历史」面板同款：一次到位，不做补间。
     *
     * 曾是 220ms 高度补间 + 淡入淡出 + 三角旋转，为它长出一串守卫（过渡期跳过分页追加、取消补间、
     * detach 撤动画）。作者 2026-10-18 指定回退成硬切：状态翻完，由同一次刷新落栏高与内容。
     */
    @Test
    fun `审查修复_复用复位与入库时间与弃件`() {
        val view = codeOf("PinyinKeyboardView.kt")
        assertTrue("复用必须复位字重（功能面板标签是粗体）", "typeface = android.graphics.Typeface.DEFAULT" in view)
        assertTrue("台账按简体归一", "SelectionLedger.record(PinyinEngine.toSimplified(word), pinyin)" in view)
        // 合并词与 pred 相同时不得再记第二行（AI2 A-577）：没有前一个词时 fullWord == pred，
        // 同一「词 + 读音」会被记两次、频次翻倍。删掉这个条件看起来像删了个冗余判断，必须有守卫挡着
        // 判据取**该 if 块体**：只判 `fullWord != pred` 抓不到「条件还在、record 被移出块」（第 74 轮变异实证）
        val dedup = TestSources.blockAfter(view, "if (fullWord.isNotEmpty() && fullWord != pred)")
        assertTrue(
            "合并词与 pred 相同时不重复记",
            "SelectionLedger.record(PinyinEngine.toSimplified(fullWord), fullPinyin)" in dedup,
        )
        val db = codeOf("ClipboardDb.kt")
        assertTrue("图片入库也用单调时间", "put(\"created_at\", monotonicNow())" in db)
        val gallery = codeOf("GalleryInsert.kt")
        assertTrue("非图片拦截不看 declared", "if (sniffed == null && looksLikeNonImage(head))" in gallery)
        val ime = codeOf("JinnIme.kt")

        // 暂存件删除按**契约**钉，不数次数也不逐个点名字（AI2 A-578 / A-597）：契约是
        // 「已交给宿主 / 文件本就不在的分支不得删，其余分支必须删」。只有「不得删」那一小撮是名单
        // （每个名字都有理由），其余全部由 `InsertResult` 枚举派生 ⇒ 新增分支照契约办就自动落在
        // 「必须删」那一组，不必回来改本用例；漏删则当场红。
        val flush = TestSources.blockAfter(ime, "private fun flushPendingGalleryImage(")
        val commitWhen = TestSources.blockAfter(
            flush, "when (GalleryInsert.commit(this, picked.file, picked.mime))",
        )
        fun commitBranch(name: String): String {
            val at = commitWhen.indexOf("GalleryInsert.InsertResult.$name ->")
            check(at >= 0) { "flushPendingGalleryImage 里找不到 $name 分支" }
            val rest = commitWhen.substring(at)
            val next = Regex("""GalleryInsert\.InsertResult\.\w+ ->""").find(rest, 1)?.range?.first
            return if (next == null) rest else rest.substring(0, next)
        }
        val branchNames = GalleryInsert.InsertResult.values().map { it.name }.toSet()
        assertEquals(
            "每个 InsertResult 分支都要在这个 when 里接线（漏接的话下面取分支会当场报错）",
            branchNames,
            Regex("""GalleryInsert\.InsertResult\.(\w+) ->""").findAll(commitWhen).map { it.groupValues[1] }.toSet(),
        )
        // 豁免名单（往这里加名字必须给出理由）：Submitted / Rejected 已交给宿主，`commitContent`
        // 返回值不可信、有宿主异步读 URI，立即删会让对方读到裂图；FileMissing 是文件已经不在了，删是空操作。
        val deliveredToHost = setOf("Submitted", "Rejected", "FileMissing")
        for (name in branchNames) {
            val body = commitBranch(name)
            if (name in deliveredToHost) {
                assertFalse("$name 已交给宿主 / 文件已不在，不得删暂存件", "picked.file.delete()" in body)
            } else {
                assertTrue("$name 从未交给宿主，必须删掉暂存件", "picked.file.delete()" in body)
            }
        }
    }

    /**
     * 批量删除只删行，**调用方必须补一次图片文件回收**（AI2 A-559）。
     *
     * `deleteAll` / `deleteByCategory(null)` / `deleteByContentType` 的签名（返回 Int）不表达
     * 「调用方还得收文件」这个契约，而漏了就是「列表已删、密文仍在盘上」的孤儿文件
     * （与 A-558 同表现，且要等下次启动 gc 才收）。这里钉**配对**而不是「都调一次 gc」：
     * tag 维度的删除（`deleteByCategory("URL")`）碰不到图片行 —— 图片行的分类恒为 OTHER，
     * 那条前提由下面第二条断言单独钉住。
     */
    @Test
    fun 批量删除图片行后必须补文件回收() {
        val db = TestSources.codeSource("ClipboardDb.kt")
        assertTrue(
            "图片行的分类恒为 OTHER（tag 维度的批量删除因此碰不到图片行）",
            "values.put(\"category\", ClipboardClassifier.CATEGORY_OTHER)" in db,
        )
        // 能删到图片行的三处调用点：其后 400 字符内必须有文件回收。
        // ⚠ 字面量带上参数，只认「删得掉图片行」的那条：`deleteByContentType(TEXT)` 碰不到图片行，
        //    只按函数名判会把不该要求 gc 的调用点也算进来（方向仍安全 —— 是误红不是漏判，但会让
        //    后来人以为「所有 deleteByContentType 都要 gc」而照抄。
        val sites = listOf(
            "ClipboardHistoryActivity.kt" to "db.deleteByContentType(ClipboardDb.CONTENT_TYPE_IMAGE)",
            "ClipboardHistoryActivity.kt" to "db.deleteByCategory(null)",
            "ClipboardPanelView.kt" to "db.deleteAll()",
        )
        for ((file, call) in sites) {
            val src = TestSources.codeSource(file)
            // 逐个出现点判（旧写法只取 `indexOf` 的首个）：同一文件里再添一处同类调用时，
            // 首处仍在、窗口照样绿，新添那处漏 gc 就没人管 —— 判据会静默漂到另一处（BUG.md L-1145 的窗口形态）
            val hits = Regex(Regex.escape(call)).findAll(src).map { it.range.first }.toList()
            check(hits.isNotEmpty()) { "$file 里找不到 $call —— 改法后请同步本用例" }
            for (at in hits) {
                val tail = src.substring(at, (at + 400).coerceAtMost(src.length))
                assertTrue(
                    "$file 的 $call（偏移 $at）之后必须紧跟 ClipboardImageFiles.gc（否则删了行、密文还留在盘上）",
                    "ClipboardImageFiles.gc(" in tail,
                )
            }
        }
    }

    /**
     * 剪贴板采集开关的跨组件同步（AI2 A-572）：**同进程直连**，不得再走广播。
     *
     * 旧实现「自定义页 sendBroadcast + IME 在 Android 13+ 注册接收方」在 Android 8~12 上
     * 整条通知落空（低版本没有 `RECEIVER_NOT_EXPORTED`，索性不注册），而用户关掉采集后
     * 采集器仍在入库，直到下次弹键盘才停。
     */
    @Test
    fun 剪贴板采集开关改动必须当场同步到输入法() {
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue("IME 必须暴露同进程通知入口", "fun onClipboardConfigChanged()" in ime)
        assertTrue("通知必须落到采集器同步", "ime.ui.post { ime.syncClipboardController() }" in ime)
        assertFalse("不再保留那条应用内广播 action", "ACTION_CLIPBOARD_CONFIG_UPDATED" in ime)
        val page = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        val save = TestSources.blockAfter(page, "private fun doSave()")
        assertTrue("保存后开关变化必须当场通知", "JinnIme.onClipboardConfigChanged()" in save)
        assertFalse("保存路径不得再用广播（低版本收不到）", "sendBroadcast(" in save)
    }

    /**
     * 长按空格的语音契约（AI2 A-640）：语音不可用时**必须返回 false**，让框架照常走短按上屏空格。
     *
     * 旧实现长按恒返回 true，`View` 会置 `mHasPerformedLongPress` ⇒ 抬起不再派发 click，
     * 而 `KeyFeedback.fire(G_CONFIRM)` 已经响过 —— 用户听到「按下了」却什么都没上屏
     * （表现就是「空格偶尔失灵」）。这条契约此前零测试覆盖。
     */
    @Test
    fun 长按空格在语音不可用时不得吞掉空格() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        // 取到长按回调的**函数体**（花括号配对）再判，不靠「到某个字面量为止」的窗口
        val space = TestSources.blockAfter(view, "btnSpace.setOnLongClickListener {")
        assertTrue(
            "长按必须先问 canVoice()，不可用立刻放行（不消费手势）",
            "if (listener?.canVoice() != true) return@setOnLongClickListener false" in space,
        )
        // 反向：不可用分支之前不得先发反馈（否则用户听到「按下了」却什么都没上屏）
        val guard = space.indexOf("canVoice()")
        val firstFire = space.indexOf("KeyFeedback.fire(")
        assertTrue("反馈只能发在可用分支里", guard in 0 until firstFire)
        assertTrue("接口必须有 canVoice 契约", "fun canVoice(): Boolean" in view)
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue("实现必须跟随语音开关", "override fun canVoice(): Boolean = prefs.voiceInputEnabled" in ime)
    }

    @Test
    fun `审查修复_生命周期与形态判据一致`() {
        val page = codeOf("UserDictActivity.kt")
        assertTrue(
            "后台加载提交必须带存活判据（shutdownNow 后提交会抛异常并终止进程）",
            "if (isFinishing || isDestroyed) return" in page &&
                "runCatching { io.execute { loadOnIo() } }" in page,
        )
        assertTrue(
            "关闭失败要重新绑定台账（否则偏好显示开启而记录全丢）",
            "SelectionLedger.init(applicationContext, true)" in page,
        )
        assertTrue("导出格式要能跨重建恢复", "KEY_PENDING_FORMAT" in page && "onSaveInstanceState" in page)
        // 词库页三列的列序由**命名 tag** 承载，不靠 addView 顺序（AI2 A-554）：
        // 裸 getChildAt(0/1/2) 时插一个 View 就会把频次显示成汉字，不崩也不报错。
        // 取**行适配器类体**判「一个位置索引都不许有」—— 只否 `getChildAt(2)` 那种字面量的写法，
        // 把 0 或 1 换回来照样静默绿（第 67 轮那版守卫漏的就是这两个），改用别的取值写法也漏。
        val rowAdapter = TestSources.blockAfter(page, "private inner class RowAdapter(")
        assertFalse("不得再按位置索引取列", "getChildAt(" in rowAdapter)
        for (tag in listOf("TAG_COL_COUNT", "TAG_COL_WORD", "TAG_COL_PINYIN")) {
            assertTrue("列标识 $tag 必须在 addView 与读取处同时出现", Regex("$tag\\b").findAll(rowAdapter).count() >= 2)
        }
        // 位置不是稳定 id（AI2 A-555）：一旦配上 `setHasStableIds(true)`，同一位置换了内容而 id 没变，
        // ListView 认为这行没变 ⇒ 导入后列表不刷新。只否旧字面量不够（换个参数名 / 加花括号就静默绿），
        // 所以正向钉 INVALID_ROW_ID，再禁 `position.toLong()` 这个具体实现。
        assertTrue(
            "getItemId 必须显式返回 INVALID_ROW_ID",
            "override fun getItemId(position: Int): Long = AdapterView.INVALID_ROW_ID" in rowAdapter,
        )
        assertFalse("不得再拿位置当稳定 id", "position.toLong()" in rowAdapter)

        val view = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "三角显隐与「展开态在这些形态下会被收起」用同一判据（否则数字层挂着点不动的 ▲）",
            "layer == LAYER_LETTER && !hasActiveOverlay" in view,
        )

        val settings = codeOf("SettingsActivity.kt")
        assertTrue(
            "空壳删除只在确认 0 字节时执行（SAF 的 AFD 长度常是 -1）",
            "if (size != 0L) return" in settings,
        )

        val ledger = codeOf("SelectionLedger.kt")
        assertTrue("flush 必须带超时（队列里可能压着上万行）", "FLUSH_TIMEOUT_MS" in ledger)
        assertTrue(
            "台账删除失败要保留绑定（调用方写回 pref 后仍能记录）",
            "target = file" in ledger,
        )
    }

    @Test
    fun `切分缓存必须随词库重载失效`() {
        val src = codeOf("PinyinEngine.kt")
        // 每键要切两次（显示 + 查询），故在 segment 上做单条 memo；键必须含世代号，
        // 否则重载词库后拼音行仍按旧切分显示（候选靠 phrasesFor 自己失效，不会一起暴露）
        assertTrue("segment 必须有 memo", "private class SegmentHit" in src && "segmentHit" in src)
        assertTrue(
            "memo 键必须含世代号",
            Regex("if \\(it\\.gen == gen && it\\.input == input\\)").containsMatchIn(src),
        )
        assertTrue(
            "世代号必须来自 invalidateMergedCache",
            "val gen = dataGeneration" in src && "dataGeneration++" in src,
        )
        // markSyllables 只建一个 StringBuilder 并逐字符回校验，不再靠 replace 建第三个串
        assertFalse("回校验不得再建中间串", "marked.replace(" in src)
    }

    @Test
    fun `候选展开收起必须一次到位`() {
        val src = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "状态翻完后由同一次刷新落内容与栏高",
            "candidatesExpanded = expand" in src && "refreshCandidateBar()" in src,
        )
        // 收起 = 幂等 + 当场翻意图态；没有第二处要复位的渲染态
        assertTrue(
            "收起必须幂等且当场翻意图态",
            Regex("private fun collapseCandidates\\(\\) \\{\\s*"
                + "if \\(!candidatesExpanded\\) return\\s*candidatesExpanded = false")
                .containsMatchIn(src),
        )
        // 0 帧硬约束（作者 2026-10-20）：整套补间机制不得回来
        assertFalse("不得再有栏高补间", "ValueAnimator" in src)
        assertFalse("不得再有补间时长常量", "CANDIDATE_TWEEN_MS" in src)
        assertFalse("不得再用整体平移伪装栏高变化", "translationY" in src)
        assertFalse("不得再有三角旋转补间", "tweenExpandButton" in src)
        assertFalse("不得再有渲染态 / 意图态双轨", "candidateRenderExpanded" in src)
        assertFalse("不得再等窗口就位", "OnPreDrawListener" in src)
        assertFalse("不得再给候选区做淡入淡出", "candidateScroll.alpha" in src)
        assertFalse("不得再有过渡期守卫", "candidateAnimating" in src)
        // 栏高只有一个写入者，且一次写终值
        assertTrue(
            "栏高只在 applyCandidateRows 里写终值",
            "if (lp.height != barHeight) {" in src && "lp.height = barHeight" in src,
        )
        assertEquals("栏高写入点必须唯一", 1, Regex("lp\\.height = barHeight").findAll(src).count())
        // 展开排数在一次展开会话内固定
        assertTrue("展开排数必须复用缓存", "if (expandedRowsCached > 0) {" in src)
        // 三角方向由刷新统一对齐，且只有一处写
        assertTrue(
            "层切换早退前必须复位三角",
            "btnExpandCandidates.rotation = if (candidatesExpanded) 180f else 0f" in src,
        )
        assertEquals("三角角度写入点必须唯一", 1, Regex("btnExpandCandidates\\.rotation\\s*=").findAll(src).count())
        assertTrue(
            "候选集落定后必须重判三角",
            src.indexOf("lastCandidates = result.candidates") <
                src.lastIndexOf(
                    "updateExpandButton(rowsSetting, candidateSp, pinyinSp, pinyinGap, spacingHalfPx, " +
                        "lastCandidates.size, lastCandidates.firstOrNull())",
                ),
        )
        val relayoutCalls = Regex("collapseCandidatesAndRelayout\\(").findAll(src).count()
        assertTrue("收起后必须重落栏高（定义 + 空输入 + 候选集为空）", relayoutCalls >= 3)
        assertTrue(
            "空输入与候选集为空两处都要走重落栏高",
            src.contains("collapseCandidatesAndRelayout(rowsSetting, candidateSp, pinyinSp, pinyinGap, spacingHalfPx)"),
        )
        // 重落栏高必须是「先收起、再按档位落栏高」（AI2 A-563）：旧断言钉「collapseCandidates() + 换行
        // + 恰好 8 个空格 + applyCandidateRows(...)」，IDE 一重排 / 换个缩进就红而语义没变。
        // 现在取**函数体**判：两件事都在里面，且栏高首参是 rowsSetting（不是展开排数）。
        val relayoutBody = TestSources.blockAfter(src, "private fun collapseCandidatesAndRelayout(")
        assertTrue("重落栏高必须先收起展开态", "collapseCandidates()" in relayoutBody)
        assertTrue(
            "重落栏高必须按档位落，不得按展开排数",
            Regex("""applyCandidateRows\(\s*rowsSetting\s*,""").containsMatchIn(relayoutBody),
        )
        assertFalse("重落栏高不得把展开排数当档位", "applyCandidateRows(rows," in relayoutBody)
        // 本帧输入串只拷一次，且渲染侧**直接复用局部量**（AI2 A-619）：旧写法经字段 composingSnapshot
        // 中转，「写入点与读取点之间不许写 composing」成了隐式契约 —— 中间插一句清空，拼音行就显示旧串
        assertTrue("渲染必须复用本帧的 inputNow", "val input = inputNow" in src)
        assertFalse("不得再用跨段字段传本帧输入串", "composingSnapshot" in src)
        // 「词库加载中」早退分支也要收起（AI2 A-562）：那条分支只换提示，展开态下栏高仍是 3~4 排
        // ⇒ 一块空高栏。取**该分支体内**（到第一个 return 为止）再判，避免把紧随其后的
        // 「候选集为空」分支里的同一行算进来（那会让这条守卫恒绿）。
        val loadingBranch = Regex("""isFullyLoaded\)\s*\{[\s\S]{0,600}?\n            return""")
            .find(src)?.value.orEmpty()
        assertTrue(
            "「词库加载中」早退分支必须收起并重落栏高（AI2 A-562）",
            "collapseCandidatesAndRelayout(rowsSetting" in loadingBranch,
        )
    }

    /**
     * 切展开态必须复位候选横滑位（2026-10-21 真机复现）。
     *
     * 展开 / 收起只换排布、不换候选集，早先的复位判据只看 `items` ⇒ 不复位横滑位。而两套排布的
     * 列宽量纲完全不同：收起态滑到第 13 条再展开，首屏落在列表中段，词频最高的几条反而在屏外
     * （实测首屏 `即 激 忌 技`、`机 脊 急 饥` 屏外）—— 既不是「接着看」也不是「从头看」。
     *
     * 三条反向钉：① 复位必须在指纹早退**之前**（同内容刷新会走早退分支，放后面永远轮不到）；
     * ② 展开不得把分页窗口打回收起档（`refreshCandidateBar` 已按两屏放大过，打回去会让末排重新
     * 走上增量追加 ⇒ 列错位）；③ 纵轴不复位（内容高恒 ≤ 栏高，`maxScrollY` 恒 0）。
     */
    @Test
    fun `切换展开态必须把候选横滑位回零`() {
        val src = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "换形态必须单独触发横滑复位",
            Regex("""if \(candidatesExpanded != lastRenderExpanded\) \{""").containsMatchIn(src),
        )
        val resetAt = src.indexOf("candidatesExpanded != lastRenderExpanded")
        val skipAt = src.indexOf("if (renderKey == candidateRenderKey) return")
        assertTrue("指纹早退点必须存在（写法变了请同步本用例）", skipAt > 0)
        assertTrue("复位必须在指纹早退之前", resetAt in 1 until skipAt)
        assertEquals(
            "横滑复位点必须恰好两处（换候选 / 切展开态），纵轴不复位",
            2,
            Regex("""candidateScroll\.resetScrollX\(\)""").findAll(src).count(),
        )
        assertEquals(
            "展开态不得把分页窗口打回收起档（字段初值 / 清空 / 换候选 各一处）",
            3,
            Regex("candidateRenderLimit = INITIAL_RENDERED_CANDIDATES").findAll(src).count(),
        )
    }

    /**
     * 外观变更必须作废展开排数缓存（2026-10-21 真机复现）。
     *
     * 排数缓存在一次展开会话内固定，而网格（拼音条 + 排数 × 行高）每帧按**当前字号**重算。
     * 展开中调大候选字号而缓存不作废 ⇒ 排数是旧值、网格比栏高还高 ⇒ `expandedBarHeightPx` 按
     * `MAX_COVERED_KEY_ROWS` 夹顶、末排被栏底裁掉（默认档余量仅 17px，候选字号 >31.4sp 即裁）。
     */
    @Test
    fun `外观变更必须作废展开排数缓存`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "fun refreshAppearance()")
        assertTrue("外观变更必须作废展开排数缓存", "expandedRowsCached = 0" in body)
        assertTrue(
            "作废必须在重落栏高之前（否则这一帧仍按旧排数算栏高）",
            body.indexOf("expandedRowsCached = 0") < body.indexOf("refreshCandidateBar()"),
        )
    }

    /**
     * 三角判据与展开排数都必须与栏高同帧同源。
     *
     * `updateExpandButton` 每帧被调两次（栏高落位前 / 候选落定后）。它原先在函数内自己读 Prefs，
     * 而内部几何用的是 `currentPinyinSp`——**上一帧**由 `applyCandidateRows` 落位的值，于是
     * `wouldGrow` 恒按「上一帧拼音字号 + 本帧候选字号」的混合几何算，三角会闪一帧。
     * `toggleCandidateExpanded` 里算展开排数也是同一病：拿上一帧的候选列表配本帧重读的字号。
     * 改为全部取本帧 / 上一帧的**同一批**落位量后，两处都不得再出现 `prefs`。
     */
    @Test
    fun `三角与展开排数必须同帧同源`() {
        val src = codeOf("PinyinKeyboardView.kt")
        val fn = blockAfter(src, "private fun updateExpandButton(")
        assertFalse("三角判据不得自己读 Prefs", "prefs." in fn)
        assertFalse("三角判据不得读上一帧的落位值", "currentPinyinSp" in fn)
        assertEquals(
            "收尾：任何调用点都不得再漏下发条目数（旧 5 参形态必须为 0 处）；" +
                "字距是单条宽度的一部分，必须与候选字号同一个口径一起下发",
            0,
            Regex("""updateExpandButton\(rowsSetting, candidateSp, pinyinSp, pinyinGap, spacingHalfPx\)""")
                .findAll(src).count(),
        )
        assertEquals(
            "三角判据必须有定义 + 三处调用（折叠重落 / 栏高落位前 / 候选集落定后）",
            4,
            Regex("""updateExpandButton\(""")
                .findAll(src).count(),
        )
        // 展开排数缓存同理：候选条数取自上一帧列表，几何也必须取自上一帧的落位量
        val toggle = blockAfter(src, "private fun toggleCandidateExpanded()")
        assertFalse("展开排数不得重读 Prefs（否则与候选列表不同源）", "prefs." in toggle)
    }

    /**
     * 预测态的三角不得按「上一次查询的候选条数」显示（2026-10-22 真机复现）。
     *
     * 选完候选进预测态时 `lastCandidates` 不清空（只有「无输入且无预测」那一支才清），而三角判据
     * 原先直接读它 ⇒ 上一串 60 条候选、这帧只有 6 个预测词，一排放得下却照挂三角；
     * 点开还按 60 条算成 4 排，把 6 个预测词铺成 4 行空网格、压掉一整行字母键。
     *
     * 钉法：判据函数里不得再出现 `lastCandidates`；条目数由调用方下发；预测态那一处必须按渲染源取。
     */
    @Test
    fun `预测态三角不得按上一次候选数显示`() {
        val src = codeOf("PinyinKeyboardView.kt")
        val fn = blockAfter(src, "private fun updateExpandButton(")
        assertFalse("三角判据不得再读 lastCandidates（预测态那是上一次输入的残留）", "lastCandidates" in fn)
        assertTrue("三角判据必须用调用方下发的条目数", "itemCount" in fn)
        assertTrue(
            "预测态那一处必须按本帧渲染源取条目数",
            "val renderSource = renderedItems(inputNow.isEmpty())" in src,
        )
        // 单表达式判据就是渲染源本身：条数与取样字都从它派生（各取一次，不得各判一次）
        assertTrue(
            "渲染源按输入分支切换（无拼音串且预测非空 ⇒ 预测词）",
            "if (inputEmpty && lastPredictions.isNotEmpty()) lastPredictions else lastCandidates" in src,
        )
        assertTrue("条数由渲染源派生", "val renderItemCount = renderSource.size" in src)
        assertTrue("取样字由渲染源派生", "val renderSample = renderSource.firstOrNull()" in src)
    }

    /**
     * 展开排数与预取量必须与三角同源取条数，取样字也必须同源（2026-10-22）。
     *
     * 预测态下 `lastCandidates` 不清空（上一次查询的残留，真机 40 条），本帧只渲染 6 个预测词。
     * 只把三角判据收口到渲染源还不够 —— 收口前展开排数（`refreshCandidateBar` 缓存失效那一帧 /
     * `toggleCandidateExpanded`）与预取量仍直接读 `lastCandidates.size`：点开按 40 条铺 4 排空网格；
     * 取样字取自候选列表（两字词）又比预测词（多为单字）宽 ⇒ 6 个词一排放得下却挂 ▲（29sp 复现）。
     * 三处条数与取样字必须消费同一个本帧值。
     */
    @Test
    fun `展开排数与预取量必须与三角同源取条数`() {
        val src = codeOf("PinyinKeyboardView.kt")
        val bar = blockAfter(src, "private fun refreshCandidateBar()")
        val def = "val renderSource = renderedItems(inputNow.isEmpty())"
        assertTrue("本帧渲染源必须在刷新入口只取一次", def in bar)
        assertEquals(
            "条数只该有「一个定义 + 三个消费点」（三角 / 展开排数 / 预取量）",
            4,
            Regex("""\brenderItemCount\b""").findAll(bar).count(),
        )
        assertEquals(
            "取样字只该有「一个定义 + 两个消费点」（三角 / 展开排数）",
            3,
            Regex("""\brenderSample\b""").findAll(bar).count(),
        )
        assertTrue(
            "定义必须早于三处消费",
            bar.indexOf(def) < bar.indexOf("expandedRowsFor(renderItemCount") &&
                bar.indexOf(def) < bar.indexOf("minOf(renderItemCount") &&
                bar.indexOf(def) < bar.indexOf("renderSample,"),
        )
        assertTrue("展开排数按渲染源取条数", "expandedRowsFor(renderItemCount" in bar)
        assertTrue("预取量按渲染源取条数", "minOf(renderItemCount" in bar)
        assertTrue(
            "三角判据与展开排数消费同一对值（条数 + 取样字）",
            Regex("""renderItemCount,\s*renderSample,""").containsMatchIn(bar),
        )
        assertTrue(
            "展开排数也必须把同一个取样字量到底",
            "expandedRowsFor(renderItemCount, candidateSp, spacingHalfPx, renderSample)" in bar,
        )
        // 反向：残留口径不得在半修状态里留成第二份真相
        assertFalse("展开排数不得再读残留候选数", "expandedRowsFor(lastCandidates.size" in src)
        assertFalse("预取量不得再读残留候选数", "minOf(lastCandidates.size" in src)
        assertFalse("取样字不得再恒取候选列表", "lastCandidates.firstOrNull()?.takeIf" in src)
        val toggle = blockAfter(src, "private fun toggleCandidateExpanded()")
        assertFalse("预测态下 lastCandidates 是残留：展开排数不得直接读它", "lastCandidates" in toggle)
        assertTrue(
            "点 ▲ 那一刻的排数也必须按渲染源取条数与取样字",
            "renderedItems(" in toggle && "renderItems.firstOrNull()" in toggle,
        )
    }

    @Test
    fun `选图必须按字节核对类型`() {
        val src = codeOf("GalleryInsert.kt")
        // 嗅探先于声明：declared 只在 sniffed 为 null 时兜底
        assertTrue("必须先嗅探", "val sniffed = ClipboardImageCodec.sniffMimeOrNull(head)" in src)
        assertTrue("声明只在嗅探认不出时兜底", "val mime = sniffed ?: declared ?: sniffImageMime(head)" in src)
        assertTrue("不一致要留痕", "选图类型不一致" in src)
        assertTrue("名实不符要拒收", "looksLikeNonImage(head)" in src)
        assertTrue("必须有非图片形态判据", "internal fun looksLikeNonImage(head: ByteArray)" in src)
        // 纯函数判据：PDF / ZIP / RAR / 7z / gzip / PE / ELF 七类
        for (magic in listOf("0x25", "0x50 && at(1) == 0x4B", "0x52", "0x37", "0x1F", "0x4D", "0x7F")) {
            assertTrue("非图片魔数必须覆盖 $magic", magic in src)
        }
        // BMP / TIFF 是真图片，只不嗅探 ⇒ 不得被拒
        assertFalse("BMP 不得被当成非图片", "\"BM\"" in src)
    }

    /**
     * L-1098：SAF 导出回调在「临时包不存在」时必须删掉 CreateDocument 建出的空壳。
     *
     * `CreateDocument` 是**先建文件再回传 uri**，所以任何一条早退分支都会在用户选定的目录里
     * 留下 0 字节空壳。空壳外观与有效包一模一样，用户拿去导入只会得到「密码错误或文件已损坏」，
     * 把排查带偏。判据：两个导出回调的早退分支都调同一个删除助手，且助手本身不外抛。
     */
    @Test
    fun `导出早退必须删掉空壳目标`() {
        val src = codeOf("SettingsActivity.kt")
        assertTrue("必须有删空壳的助手", "private fun deleteEmptyTarget(uri: Uri)" in src)
        // 两个导出回调（诊断包 / 配置包）的「临时包不存在」分支都要删
        assertEquals(
            "两个导出回调的早退分支都必须删空壳（诊断包 + 配置包）",
            2,
            Regex("""deleteEmptyTarget\(uri\)""").findAll(src).count(),
        )
        // 删除失败只记日志，不得外抛（provider 可能已收回授权）
        assertTrue(
            "删空壳失败必须兜住",
            "runCatching { contentResolver.delete(uri, null, null) }" in src,
        )
    }

    /**
     * 设置页两键用 layout 的静态文案（2026-10-18 回退）。
     *
     * 曾按系统状态改成「已启用 · 再进系统设置 / 已是默认输入法」并把切换键置灰；
     * 作者要求改回静态文本：页面不读系统状态，也不改键的可用性。
     */
    @Test
    fun `设置页两键必须用静态文案`() {
        val src = codeOf("SettingsActivity.kt")
        assertFalse("不得再按系统状态改文案", "refreshImeState" in src)
        assertFalse("不得再读已启用列表", "enabledInputMethodList" in src)
        assertFalse("不得再读默认输入法", "Settings.Secure.DEFAULT_INPUT_METHOD" in src)
        assertFalse("不得再把切换键置灰", "btnPick.isEnabled" in src)
        // 上面的反向断言只保证「没退回动态文案」，还得保证两键**真的接上了入口** ——
        // 只删掉监听器的话四条反向断言全绿，而两个键变成点了没反应的死键
        // （首次使用者的必经两步：启用 → 设默认）。
        assertTrue(
            "「启用本输入法」必须跳系统输入法设置",
            "btnEnable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }" in src,
        )
        assertTrue("「改默认输入法」必须拉起切换器", "manager?.showInputMethodPicker()" in src)
        // 文案来自布局静态字符串，代码里不得再下发（否则与 layout 取值各说各话）
        assertFalse("启用键文案不得由代码下发", "btnEnable.text =" in src)
        assertFalse("切换键文案不得由代码下发", "btnPick.text =" in src)
    }

    /**
     * L-1095：Keystore 密钥句柄必须**可失效**。
     *
     * `by lazy` 的语义是「算一次即固定」，而句柄是可失效资源（Keystore 服务异常 / 用户清除凭据）：
     * 一旦失效，`Cipher.init` 恒抛，采集与解密在本进程内永久静默，只能靠重启恢复。
     * 判据分三层：① 不得再用 `by lazy` 持有句柄；② 有重建入口；③ 只在密钥类异常时重试一次
     * （密文损坏的 `AEADBadTagException` 不触发，否则 200 条批量解密每条都白跑一次 Keystore）。
     */
    @Test
    fun `剪贴板密钥句柄必须可失效重建`() {
        val src = codeOf("ClipboardCrypto.kt")
        assertFalse(
            "句柄不得再用 by lazy（算一次即固定，失效后无法重建）",
            "cachedKey: SecretKey by lazy" in src,
        )
        assertTrue("必须有可重建的取句柄入口", "private fun key(): SecretKey" in src)
        assertTrue("必须有清句柄的入口", "private fun invalidateKey()" in src)
        assertTrue("必须识别密钥失效类异常", "private fun isKeyFailure" in src)
        assertFalse(
            "密文损坏（AEADBadTagException）不得触发重建重试",
            "AEADBadTagException" in src,
        )
        assertFalse("不得再有裸 cachedKey 参与加密 init", "cipher.init(Cipher.ENCRYPT_MODE, cachedKey)" in src)
        assertFalse("不得再有裸 cachedKey 参与解密 init", "cipher.init(Cipher.DECRYPT_MODE, cachedKey" in src)
    }

    /**
     * 候选条目必须按行复用（每次按键整棵重建曾要新建最多 32 个 TextView + 32 个点击闭包）。
     *
     * 判据只钉接线的三处：池字段在、回收入口在、`renderCandidateItems` 里不再有裸 `removeAllViews()`
     * （绕过回收直接清容器 = 池永远取不到货，等于没复用）。
     */
    @Test
    fun `候选条目必须复用视图`() {
        val src = codeOf("PinyinKeyboardView.kt")
        assertTrue("必须有复用池字段", "private val candidateViewPool" in src)
        assertTrue("必须先把旧条目回收进池", "private fun recycleCandidateViews()" in src)
        // 只取 renderCandidateItems 的函数体（下一个函数是 buildCandidateView）
        val start = src.indexOf("private fun renderCandidateItems(")
        assertTrue("找不到 renderCandidateItems（改名后请同步本用例）", start > 0)
        val end = src.indexOf("private fun buildCandidateView(", start)
        assertTrue("找不到 renderCandidateItems 的结尾（改名后请同步本用例）", end > start)
        val body = src.substring(start, end)
        assertTrue("整棵重建必须走回收入口", "recycleCandidateViews()" in body)
        assertEquals(
            "不得再裸清容器：绕过回收等于池里永远取不到货",
            0,
            Regex("""viewCandidateList\.removeAllViews\(\)""").findAll(body).count(),
        )
        // 切到别的形态（功能面板 / 符号 / 密码条）时池要作废，否则候选 TextView 会被复用到那些形态上
        val clear = blockAfter(src, "private fun clearCandidateList()")
        // ⚠ 2026-10-20 修正：这里原先断言「非候选形态时要 candidateViewPool.clear()」，因果是反的 ——
        // 清池恰恰是**问题本身**：每词首键（面板 → 候选）因此要新建 32 个 TextView
        // （实测 365~560µs/个，整帧 build 6.7~15.1ms，占该轮 work 约 30%）。
        // 改为池常驻、只回收不清空：跨形态复用是安全的（粗体 / 布局参数 / 监听都会在取用时重设，
        // 见 buildCandidateView），池也有 CANDIDATE_VIEW_POOL_MAX 封顶，不会无限增长。
        assertFalse(
            "清空候选容器不得清空复用池（清池会让每词首键新建 32 个 TextView）",
            "candidateViewPool.clear()" in clear,
        )
        // ⚠ 不得用 `layoutParams = null` 清残留：setLayoutParams 拒绝 null，会抛
        // NPE「Layout parameters cannot be null」，每次按键必崩（2026-10-18 真机实测）
        assertFalse(
            "复用条目不得把 layoutParams 置 null（View 拒绝 null，必崩）",
            "layoutParams = null" in src,
        )
    }

    /**
     * AI2 A-185 / A-186 / A-190：三处「失败分支待遇不一致」。
     *
     * 共同点是「同一个动作，某条失败路径忘了收尾」—— 兄弟分支都做了，唯独一处漏掉，
     * 单看那一处代码并不显眼。三条都由 AI2 只读审查提出，此处合并钉住。
     */
    @Test
    fun `剪贴板写入失败分支必须同款收尾`() {
        // A-185：图片写系统剪贴板，setPrimaryClip 抛异常时也要删明文临时件
        val exportSrc = codeOf("ClipboardImageExport.kt")
        val copyBody = blockAfter(exportSrc, "fun copyToSystemClipboard(")
        assertEquals(
            "三条失败分支（取 URI / 服务不可用 / setPrimaryClip 抛）都要删临时件",
            3,
            Regex("""file.delete()""").findAll(copyBody).count(),
        )
        assertTrue("删临时件必须带留痕", "已清理临时件" in copyBody)

        // A-186：历史页「复制」与 IME 侧同款 —— 尺寸闸 + runCatching
        val histSrc = codeOf("ClipboardHistoryActivity.kt")
        val btnAt = histSrc.indexOf("addBtn(\"复制\")")
        assertTrue("找不到历史页「复制」按钮（改名后请同步本用例）", btnAt > 0)
        val btnBody = histSrc.substring(btnAt, minOf(btnAt + 1200, histSrc.length))
        assertTrue("历史页复制必须过尺寸闸", "ClipboardStore.exceedsItemLimit" in btnBody)
        assertTrue("历史页复制必须捕获异常", "runCatching { cm.setPrimaryClip(clip) }" in btnBody)
        // 与 IME 同源文案（各写一份会改一处漏一处）
        assertTrue("超长提示取 IME 的常量", "JinnIme.TEXT_COPY_TOO_LONG" in btnBody)
        assertTrue("失败提示取 IME 的常量", "JinnIme.TEXT_COPY_FAILED" in btnBody)

        // A-190：UriFailed 不得丢掉剪贴板兜底
        val imeSrc = codeOf("JinnIme.kt")
        val pasteAt = imeSrc.indexOf("粘贴图片: URI 构造失败")
        assertTrue("找不到 UriFailed 分支（改名后请同步本用例）", pasteAt > 0)
        val branch = imeSrc.substring(pasteAt, minOf(pasteAt + 400, imeSrc.length))
        assertTrue("UriFailed 必须退回剪贴板兜底（拆因不等于取消兜底）", "fallbackToClipboard(item, bytes)" in branch)
    }


    /**
     * ▲ 展开键不能是「空操作按钮」。
     *
     * 候选数不超过一屏列数（通常 2~6 条）时 `visibleRows` 仍只算 1 排 ⇒ 点 ▲ 后栏高与排布分毫不动、
     * 只有三角翻个身，用户判定「展开功能坏了」（这也是「突兀不协调」的一种表现）。
     * 判据必须落在 [updateExpandButton] 的显隐里；已展开时无条件显示，否则按钮当场消失、收不回去。
     */
    @Test
    fun expandButtonMustNotBeANoOp() {
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        assertTrue(
            "▲ 必须只在展开后排布真会变时出现",
            "val wouldGrow = candidatesExpanded || expandedRowsFor(" in src,
        )
        assertTrue("判据必须接进显隐", "&& wouldGrow &&" in src)
        assertTrue(
            "已展开时不得再受候选数判据约束（否则候选掉到 ≤1 时按钮当场消失、高栏收不回去）",
            "(candidatesExpanded || itemCount > 1)" in src,
        )
    }

    /**
     * 收藏符号的限长必须只有**码点**一个口径。
     *
     * Kotlin 的 `String.length` 是 UTF-16 码元数：一个 emoji 记 2 ⇒ 页面拒收 5 个 emoji（10 码元），
     * 而 `FavoriteSymbols.append` / `parse` 按 `codePointCount` 只算 5 ⇒ 两套口径，粘一串 emoji 一半报无效。
     */
    @Test
    fun favoriteSymbolLimitUsesCodePoints() {
        val fs = TestSources.codeSource("FavoriteSymbols.kt")
        val src = TestSources.codeSource("FavoriteSymbolsActivity.kt")
        assertFalse(
            "页面不得用 UTF-16 码元数限长",
            "item.length > FavoriteSymbols.MAX_CHARS" in src,
        )
        assertTrue(
            "限长必须走模型单一真源（码点口径）",
            "!FavoriteSymbols.withinLimit(item)" in src,
        )
        assertTrue(
            "模型侧 withinLimit 必须按码点算",
            "fun withinLimit(item: String, max: Int = MAX_CHARS): Boolean =" in fs &&
                "item.codePointCount(0, item.length) <= max" in fs,
        )
    }


    /**
     * ⚠ `onStartInput` **不得**清 composing / 调 configure（2026-10-19 真机证伪后钉住）。
     *
     * 曾按「换框不复位 ⇒ 上一框内容进新框」的判断，在 onStartInput 里加 `configure()`。
     * 真机复现（PACM00 / 短信 App 收件人→正文）证明那个判断是**误判**：
     *   - 本方拼音串从不进宿主：全链路只有 `commitText`，没有 `setComposingText`
     *     （`showPinyin` 只写自己的 TextView），宿主 EditText 里本来就没有拼音；
     *   - 换框全程日志只有 `onFinishInputView` / `onWindowHidden`，**没有任何 commit**；
     *   - 收件人框里出现的字是宿主自己的失焦补全，Home 后返回并不残留。
     * 而这次「修复」直接造成回归：onStartInput 早于宿主结束组合态，此时清 IME 侧 composing
     * 不经宿主，宿主把残余拼音当纯文本补进旧框（实测收件人里留下「ni」+ 后续字）。
     * 结论：composing / 面板 / 密码键盘的复位只归 onStartInputView 的 configure
     * （那时组合态已由框架正常收尾）；onStartInput 里只能放**不依赖宿主**的 IME 侧标记
     * （隐私抑制、翻译作废、去重指纹）。
     */
    @Test
    fun onStartInputMustNotTouchComposing() {
        val body = TestSources.blockAfter(
            TestSources.codeSource("JinnIme.kt"),
            "override fun onStartInput(",
        )
        assertFalse(
            "onStartInput 不得清 composing（会早于宿主结束组合态，造成拼音泄漏回归）",
            "configure(" in body,
        )
        assertFalse(
            "onStartInput 不得主动提交 composing",
            "commitComposing" in body,
        )
        // 隐私抑制必须在这里就算：框架可能不重发 onStartInputView（密码框切换即实例）
        assertTrue(
            "隐私抑制仍要在 onStartInput 重算",
            "suppressLearningForSession = InputFieldPrivacy.suppressLearning(" in body,
        )
    }

    /**
     * 键盘视图重建必须重放用户手选态（英文 / 大写 / 符号层）。
     *
     * 三者都是视图 private 字段且**不写 Prefs**（中英切换只翻 englishMode）。换视图即丢 ⇒
     * 定时换肤到点（如 19:00）时若用户在英文态，键面会静默变回中文、后续按键出拼音候选。
     */
    @Test
    fun recreateKeyboardViewMustReplayHandPickedViewState() {
        val src = TestSources.codeSource("JinnIme.kt")
        assertTrue(
            "重建后必须重放手选态",
            "pinyinKeyboard?.replayViewState(" in src,
        )
        assertTrue(
            "必须先留旧视图引用再覆盖字段",
            "val oldKeyboard = pinyinKeyboard" in src,
        )
        // 重放必须**只在真有旧视图时**发生（BUG.md L-1465）：首次创建时重放会把
        // `english` 落到「默认模式是不是 26 键英文」上，覆盖用户手选的 `prefs.keyboardEnglish`。
        // 首次创建的初值由 `configure(...)` 给出，本来就按用户偏好。
        val created = blockAfter(src, "override fun onCreateInputView()")
        // 取 if 块内正文（两端锚点都断言存在）：早先这里用裸 substringAfter + substringBefore 取窗，
        // 锚点一改就静默退化成整函数（TestSourcesTest.固定字符窗必须改用锚点助手 当场点名）
        val replay = TestSources.window(created, "if (oldKeyboard != null) {", "\n        }")
        assertTrue("重放受 oldKeyboard != null 保护", "replayViewState(" in replay)
        // 三个入参全部直接读旧视图：出现 `?:` 就是在兜「没有旧视图」的分支，
        // 首次创建即被默认模式 / 初值覆盖用户选择（L-1465 的成因）
        assertTrue("英文态读旧视图", "english = oldKeyboard.isEnglishMode()" in replay)
        assertTrue("大写态读旧视图", "caps = oldKeyboard.capsModeForReplay()" in replay)
        assertTrue("符号层读旧视图", "layer = oldKeyboard.layerForReplay()" in replay)
        assertFalse("重放入参不得再有 ?: 兜底", "?:" in replay)
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        assertTrue(
            "重放入手点必须刷新候选栏",
            "fun replayViewState(english: Boolean, caps: Boolean, layer: Int)" in view,
        )
        for (getter in listOf("fun capsModeForReplay()", "fun layerForReplay()")) {
            assertTrue("必须暴露 $getter 供 IME 读旧态", getter in view)
        }
    }

    /**
     * 图片单张上限的钳制只许活在**读侧**（BUG.md L-1463）。
     *
     * 旧写法在保存时把 `min(单张, 图片总量)` 写回盘上：设「单张 50 + 总量 10」保存后盘上变 10，
     * 之后把总量调回 500，用户的 50 再也回不来（备份包带出去的也是那个残值）。
     * 与收藏体积（`effectiveFavoriteMaxMb`）相反的两套口径必须收成一套：写侧存原值、读侧算生效值。
     *
     * 判据三段：① 保存路径不得再把钳制值写回；② 落盘传的是原值、快照同源；
     * ③ 生效值只在 `ClipboardPrefs` 一处算（采集侧走它见 `ClipboardImageTest.imageItemCapIsClampedByTotal`）。
     */
    @Test
    fun imageItemClampMustLiveOnReadSideOnly() {
        val src = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        val save = blockAfter(src, "private fun doSave()")
        assertFalse(
            "保存路径不得再把钳制值写回盘上（L-1463 的成因）",
            "copy(imageMaxItemMb" in save,
        )
        assertTrue("落盘必须传用户原值", "imageMaxItemMb = target.imageMaxItemMb" in save)
        assertTrue("快照与草稿都按原值同源", "snapshot = target" in save)
        // 回显用的生效值仍然只算一处
        assertTrue(
            "回显取生效值必须走 ClipboardPrefs 那一个纯函数",
            "ClipboardPrefs.effectiveImageMaxItemMbOf(d.imageMaxItemMb, d.imageMaxTotalMb)" in src,
        )
        val prefs = TestSources.codeSource("ClipboardPrefs.kt")
        assertTrue(
            "生效值只许在 ClipboardPrefs 算",
            "fun effectiveImageMaxItemMbOf(raw: Int, totalMb: Int): Int" in prefs,
        )
        // 真跑一遍纯函数：压制在，且原值本身没被写坏（同一个 raw 换个总量就回来）
        assertEquals(10, ClipboardPrefs.effectiveImageMaxItemMbOf(20, 10))
        assertEquals(20, ClipboardPrefs.effectiveImageMaxItemMbOf(20, 200))
        // 落盘入口（`applyDraft`）同样只许按**定义域**钳：谁把它改成按图片总量钳，
        // 「写侧不存残值」这条就又破了，而 `doSave` 上那几条判据一条都不会红（2026-10-27 审计补）
        val itemWriteLine = TestSources.lineOf(prefs, "KEY_IMAGE_MAX_ITEM_MB, imageMaxItemMb")
        assertTrue(
            "图片单张落盘要按定义域钳",
            "coerceIn(MIN_IMAGE_ITEM_MB, MAX_IMAGE_ITEM_MB)" in itemWriteLine,
        )
        assertFalse(
            "落盘不得再按图片总量钳（按总量钳 = 原值仍会丢）",
            "imageMaxTotalMb" in itemWriteLine,
        )
    }

    /**
     * 凭据解密的两种失败**必须分开处置**（AI2 A-672 / A-686；台账 L-1462）。
     *
     * 永久失败（换机 / 密钥失效 / 密文损坏）⇒ 缓存「解不开」：旧密文永远解不开，而翻译设置页
     * 一次进页要读这批键十余次，不缓存就每条都重跑一次 Keystore 解密、各刷一条 W。
     * 暂时失败（锁屏：`setUnlockedDeviceRequired` 让密钥在本轮不可用）⇒ **绝不能缓存** ——
     * 缓存成空串等于「一次锁屏 = 本进程内凭据永久未配置」，用户解锁后打开凭据页看到整页空字段，
     * 以为凭据丢了（磁盘上的密文其实完好）。
     *
     * ⚠ 旧版本这条用例只断言「`credentialCache[key] = ""` 在场」，等于把 A-672 的缺陷写成了规格
     * （A-686）。现在钉的是**分类不变式**：两态的判据各自成立，且暂时失败分支里不得出现缓存写入。
     */
    @Test
    fun credentialDecryptFailureMustBeClassified() {
        // 纯判据真跑：锁屏（未认证）不得被判成永久，密钥作废 / 校验不过才是永久
        assertFalse(
            "锁屏（密钥本轮不可用）不是永久失败",
            CredentialCrypto.isPermanentFailure(
                "android.security.keystore.UserNotAuthenticatedException",
                "This key can only be used when the device is unlocked",
            ),
        )
        assertFalse(
            "KeyStore 的「未认证」形态也不是永久失败",
            CredentialCrypto.isPermanentFailure("java.security.KeyStoreException", "Key user not authenticated"),
        )
        assertTrue(
            "密钥被系统作废是永久失败",
            CredentialCrypto.isPermanentFailure(
                "android.security.keystore.KeyPermanentlyInvalidatedException", null,
            ),
        )
        assertTrue(
            "GCM 校验不过（密钥已换 / 密文损坏）是永久失败",
            CredentialCrypto.isPermanentFailure("javax.crypto.AEADBadTagException", null),
        )
        // 真实异常实例走一遍分档（不是只对字符串字符串）：锁屏形态必须落到「暂时」
        assertTrue(
            "KeyStore 的未认证异常必须判为暂时",
            CredentialCrypto.failureOutcome(java.security.KeyStoreException("Key user not authenticated")) ==
                CredentialCrypto.DecryptOutcome.Transient,
        )
        assertTrue(
            "被包装的永久成因要能穿透 cause 链",
            CredentialCrypto.failureOutcome(
                IllegalStateException(
                    "keystore",
                    java.security.UnrecoverableKeyException("key gone"),
                ),
            ) == CredentialCrypto.DecryptOutcome.Permanent,
        )

        val crypto = TestSources.codeSource("CredentialCrypto.kt")
        assertTrue("解密结论必须分三态", "sealed interface DecryptOutcome" in crypto)
        assertTrue("暂时失败必须单独成一态", "object Transient : DecryptOutcome" in crypto)
        assertTrue("永久失败必须单独成一态", "object Permanent : DecryptOutcome" in crypto)

        val src = TestSources.codeSource("Prefs.kt")
        val read = TestSources.blockAfter(src, "private fun readCredential(")
        fun branch(name: String): String {
            val marker = "CredentialCrypto.DecryptOutcome.$name ->"
            val at = read.indexOf(marker)
            check(at >= 0) { "readCredential 里找不到 DecryptOutcome.$name 分支" }
            val rest = read.substring(at)
            // 从**本分支标记之后**开始找下一个分支，否则 `Permanent ->` 会在自己的限定名里再命中一次
            val next = listOf("Ok", "Transient", "Permanent")
                .map { rest.indexOf("DecryptOutcome.$it ->", marker.length) }
                .filter { it > 0 }.minOrNull()
            return if (next == null) rest else rest.substring(0, next)
        }
        // 永久失败：缓存空串（否则进页十余次读各跑一次 Keystore）
        assertTrue("永久失败必须缓存成「未配置」", "credentialCache[key] = \"\"" in branch("Permanent"))
        // 暂时失败：一个字节都不许写进缓存
        assertFalse("锁屏（暂时）失败不得写缓存", "credentialCache" in branch("Transient"))
        assertTrue("暂时失败要留痕（区分锁屏与换机）", "暂时解不开" in branch("Transient"))
    }


    /**
     * 候选栏**不得再内嵌数字行**（2026-10-21 重构后的反向钉）。
     *
     * 为什么钉「没有」而不是钉实现：候选栏里塞第二行（`function_digit_row`）是「大量位置 BUG」
     * 的根源 —— 两行同处横向滚动容器的内容里，父给子项高度是 UNSPECIFIED，weight 分到的是
     * 「两行内容高之和」而非栏高；单行档（默认 65dp）下按钮行最小高就等于整栏高，再加一行
     * 数字必然溢出（实测数字行只剩约 12dp 露在栏底、字形被裁一半）。为此曾需要
     * `functionButtonMinPx` / `syncFunctionButtonMinHeight` / `canFitFunctionDigitRow` 三件套
     * 互相让位，任何一处改动都会牵动候选栏几何。
     *
     * 重构把数字行搬去 26 键区（见 [固定数字行必须继承26键外观且开关默认关闭]），候选栏回到
     * 单行结构 ⇒ 这一整套让位机制与它的判据都不许回来。
     */
    @Test
    fun 候选栏不得再内嵌数字行() {
        val src = TestSources.codeSource("PinyinKeyboardView.kt")

        // ① 视图侧：整套「让位 / 判据 / 显隐」符号必须清干净
        for (gone in listOf(
            "functionDigitRow",
            "buildFunctionDigitRow",
            "canFitFunctionDigitRow",
            "functionButtonMinPx",
            "syncFunctionButtonMinHeight",
            "setFunctionDigitRowVisible",
            "applyFunctionDigitTextSize",
            "FUNCTION_DIGIT_KEYS",
            "FUNCTION_BUTTON_MIN_TOUCH_DP",
        )) {
            assertFalse("候选栏内嵌数字行的残留符号：$gone", gone in src)
        }
        // ② 功能按钮的最小高直接取复用块高（不再按形态收缩）
        assertTrue(
            "功能按钮最小高必须回到 reuseBlockMinPx()",
            "minimumHeight = reuseBlockMinPx()" in src,
        )

        // ③ XML 侧：那一行不许回来
        val xml = listOf(
            java.io.File("src/main/res/layout/keyboard_pinyin.xml"),
            java.io.File("app/src/main/res/layout/keyboard_pinyin.xml"),
        ).firstOrNull { it.isFile }?.readText() ?: error("找不到 keyboard_pinyin.xml")
        assertFalse("键盘布局里不得再有内嵌数字行", "function_digit_row" in xml)
        // ⚠ candidate_list 必须 match_parent，**不能**用 weight（2026-10-20 真机 GEO 实测）：
        // candidate_scroll 有 paddingTop（单行档为拼音条让位），fillViewport 会把
        // candidate_panel_stack 拉到**未扣 padding** 的高度 ⇒ 候选区溢出栏外，且
        // center_vertical 把候选 TextView 量成 0 高（padTop=63 / scrollH=161 /
        // listH=161 / firstH=0 ⇒ 输入 ji 后候选整体下沉看不见）。
        // match_parent 下 stack 高 = 视口 - paddingTop，候选区正好落在拼音条下方。
        val listBlock = TestSources.window(xml, "@+id/candidate_list", "/>")
        assertTrue(
            "候选容器必须 match_parent（不得用 weight：candidate_scroll 有 paddingTop，weight 会让候选区溢出栏外）",
            "android:layout_height=\"match_parent\"" in listBlock &&
                "layout_weight" !in listBlock,
        )

        // ④ 单行档候选条目必须给本排行高（与双行档列内条目同源）。
        // 只给 wrap_content 时 TextView 按字体行框长（20sp ≈ 1.33em = 93px @3.5），
        // 比行高（28dp = 98px）矮一截，中文字形在行框内又因字体度量偏下
        // ⇒ 候选整体观感下沉、底部贴栏底（真机 GEO：list 98 / first 93，
        // 字形带相对行框下移约 33px，2026-10-20 实测）。
        val single = TestSources.blockAfter(src, "if (rows != CandidateRows.DOUBLE) {")
        assertTrue(
            "单行档候选条目必须按 rowHeightPx 给高（不得 wrap_content）",
            "LayoutParams(" in single &&
                "rowHeightPx" in single &&
                "addView(build(text), itemLp)" in single,
        )

        // ⑥ 展开排数缓存只在展开方向重算：收起时意图态已为 false，此刻算出来的排数当场作废，
        // 白付一次 expandedRowsFor（含一次栏宽估列）。重算只该发生在「真的要展开」的那一刻。
        // ⚠ 缓存与当前几何不一致会让网格比栏高还高、末排被裁 —— 失效点在 refreshAppearance，
        // 由 `外观变更必须作废展开排数缓存` 钉着。
        val toggleBody = TestSources.blockAfter(src, "private fun toggleCandidateExpanded()")
        assertTrue(
            "展开排数缓存必须只在展开方向重算",
            "if (expand) {" in toggleBody &&
                toggleBody.indexOf("expandedRowsCached = expandedRowsFor(") >
                toggleBody.indexOf("if (expand) {"),
        )

        // 补间时代的三件套一律不许回来（0 帧硬约束，作者 2026-10-20）
        assertFalse("不得再有会话边界补间收尾", "resetTransientGeometry" in src)
        assertFalse("不得再在 JinnIme 里收补间", "resetTransientGeometry" in codeOf("JinnIme.kt"))

    }

    /**
     * 固定数字行必须是 26 键区的第四行，且**外观逐项继承**字母行。
     *
     * 用户 2026-10-21 的重构要求：「相当于把三行字母按键向上方扩展成了四行字母按钮的效果，
     * 圆角间隙键高完全继承」。每条判据各对应一种「只在真机上才显形」的退化：
     *  - 用 TextView 建键 ⇒ 圆角 / 间隙 / 皮肤 / 面透明度全丢（那正是内嵌版的老路）；
     *  - 没挂进字母区首位 / 没排在 `qwertyuiop` 之上 ⇒ 位置不对，且吃不到行高的统一落值；
     *  - 三处外观下发漏一处 ⇒ 改圆角或换肤后它停在旧外观上；
     *  - 落高度时提前退出 ⇒ 数字行不跟随键高（拖滑杆时它一动不动）；
     *  - 关着开关不 GONE ⇒ 几何与重构前不一致（用户明确「关闭就是维持现状」）；
     *  - 数字层 / 密码模式不隐藏 ⇒ 同屏两排一模一样的数字（各有专门的守卫钉点击语义）。
     */
    @Test
    fun 固定数字行必须继承26键外观且开关默认关闭() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val build = TestSources.blockAfter(view, "private fun buildNumberRow()")

        // ① 建键：必须是 PinyinKey 自绘（换成 TextView 等于把整套外观再抄一遍）
        assertTrue("数字行必须用 PinyinKey 建键", "PinyinKey(context)" in build)
        assertFalse("数字行不得用 TextView 建键（那样要把圆角 / 皮肤 / 面 alpha 全抄一遍）", "TextView(" in build)
        assertTrue("数字行必须居中排版（数字 / 符号层口径）", "centeredStyle = true" in build)
        // 默认顺序的真源已移到 NumberRowLabels（模型层），对拍见
        // `数字行与密码数字条同为电话键盘自然序`
        assertTrue("数字行文本必须走 NumberRowLabels.effective", "NumberRowLabels.effective(" in build)
        // ② 位置：字母区第 0 个子视图（`qwertyuiop` 之上），且不进 26 键映射
        //    判据按「插入到哪」而不是插入语句的字面写法：`addView(0, …)` 换行、换参数变量都不该误红
        assertTrue(
            "数字行必须作为字母区第 0 个子视图插入（否则排到字母行下面，也就吃不到行高）",
            Regex("""viewLetters\.addView\(\s*\w+,\s*0,""").containsMatchIn(build),
        )
        assertFalse(
            "buildNumberRow 里不得出现 keyViews（那是 26 键映射，切层会改标签 / 字色 / 居中口径）",
            "keyViews" in build,
        )
        // ③ 外观三处下发必须覆盖数字行 —— 钉「哪个函数覆盖了 numberRowKeys」，不钉循环写法
        for ((fn, what) in listOf(
            "private fun applyKeyAppearance()" to "圆角 / 间隙",
            "private fun applySkinToKeys()" to "皮肤",
            "private fun applyKeyTransparency()" to "面透明度",
        )) {
            assertTrue(
                "$fn 必须把${what}下发给数字行（漏一处数字行就停在旧外观上）",
                "numberRowKeys" in TestSources.blockAfter(view, fn),
            )
        }
        // ④ 键高：遍历**所有**非面板行，过程中不得提前退出（钉语义，不钉某一种写法）
        val rows = TestSources.blockAfter(view, "private fun applyLetterRowHeight(")
        assertTrue("键高扫描必须跳过方向面板", "if (row.tag == DIRECTION_PANEL_TAG) continue" in rows)
        assertTrue("循环里必须无条件给每一行计数（rows++）", "rows++" in rows)
        assertFalse(
            "键高循环里不得出现 return / break（提前退出 = 数字行永远拿不到高度）",
            Regex("""(?m)^\s*(return|break)\b""").containsMatchIn(rows),
        )
        // ⑤ 显隐：关着 GONE、方向面板收起不得把它点亮；数字层 / 密码模式同样让位
        val restore = TestSources.blockAfter(view, "private fun restoreLetterRows() {")
        assertTrue(
            "restoreLetterRows 不得无脑点亮数字行，必须把它交给 syncNumberRow 裁决",
            "numberRow" in restore && "syncNumberRow()" in restore,
        )
        val sync = TestSources.blockAfter(view, "private fun syncNumberRow() {")
        for ((needle, why) in listOf(
            "prefs.numberRowEnabled" to "开关",
            "!directionPanelVisible" to "方向面板展开态",
            "LAYER_DIGIT" to "数字层（首行就是 1234567890，再排一遍 = 同屏两排）",
            "passwordPad" to "密码模式（候选栏已是 0~9 数字条，同样重复）",
        )) {
            assertTrue("显隐判据必须排除$why：syncNumberRow 里找不到 `$needle`", needle in sync)
        }
        assertTrue(
            "层 / 密码态一变就要重判显隐：refreshKeyLabels 必须调 syncNumberRow()",
            "syncNumberRow()" in TestSources.blockAfter(view, "private fun refreshKeyLabels()"),
        )
        // ⑥ 点数字行必须上屏 + 数字音效
        assertTrue("点数字行必须上屏", "listener?.onCommitText(text)" in build)
        assertTrue("点数字行必须发数字组音效", "KeyFeedback.fire(TapSound.G_DIGIT)" in build)

        // ⑦ 落盘 + 外观页入口（默认 false = 维持现状）
        val prefs = TestSources.codeSource("Prefs.kt")
        for (needle in listOf(
            "var numberRowEnabled: Boolean",
            "boolOr(KEY_NUMBER_ROW_ENABLED, false)",
            "KEY_NUMBER_ROW_ENABLED = \"number_row_enabled\"",
            "put(KEY_NUMBER_ROW_ENABLED, numberRowEnabled)",
            "KEY_NUMBER_ROW_ENABLED -> asBool(v)?.let { numberRowEnabled = it; ok() } ?: bad(key)",
        )) {
            assertTrue("数字行开关没落盘 / 没进快照 / 没进导入：$needle", needle in prefs)
        }
        val page = TestSources.codeSource("KeyAppearanceActivity.kt")
        for (needle in listOf(
            "R.id.switch_number_row",
            "prefs.numberRowEnabled = checked",
            "JinnIme.onKeyAppearanceChanged()",
        )) {
            assertTrue("外观页缺项：$needle", needle in page)
        }
        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_key_appearance.xml",
            "app/src/main/res/layout/activity_key_appearance.xml",
        )
        assertTrue("外观页布局里必须有数字行开关", "@+id/switch_number_row" in layout)
    }


    /**
     * 固定数字行与密码模式数字条**同为电话键盘自然序**（2026-10-21）。
     *
     * 这条守卫的来源是一次假前提：重构时曾写「两处排列相反、必须不同」，并用
     * `digits.toList() != PASSWORD_DIGIT_ORDER` 去钉它 —— `List<Char>` 与 `List<String>`
     * 永不相等，那条断言**恒真**（抓不到任何改动），前提本身也是假的。
     *
     * 事实：两处都是 `1` 最左、`0` 最右（电话键盘自然序）。密码条这么排是因为 1 与 0 最常点、
     * 放两端靠近拇指；固定行在连续挑数字，自然序本身就是最优。**同序是对的**，
     * 一个应用里出现两种数字排列才是问题。所以这里正向钉住「同序」：
     * 任何一侧要改（含把密码条改成别的顺序）都必须同时改另一侧并显式改本用例。
     */
    @Test
    fun 数字行与密码数字条同为电话键盘自然序() {
        // 默认顺序真源在模型层（NumberRowLabels.DEFAULT_KEYS），运行时直接断言：
        // 比「正则从源码里刮字面量」更强，也更抗重构（改实现写法不会误红）
        assertEquals("固定数字行默认顺序必须是电话键盘自然序", "1234567890", NumberRowLabels.DEFAULT_KEYS.joinToString(""))
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), PASSWORD_DIGIT_ORDER)
        // 判据必须同类型比较（上一版拿 List<Char> 比 List<String>，恒真）
        assertEquals(
            "两处数字排列必须同序（一个应用里两种数字顺序才是问题）",
            NumberRowLabels.DEFAULT_KEYS,
            PASSWORD_DIGIT_ORDER,
        )
    }

    /**
     * 点固定数字行 = 「先落定拼音、再上屏」（2026-10-21 修 P0）。
     *
     * `commitText` 会**替换**宿主的 composing 区：打「ni」再点数字 5，若闭包不先
     * [PinyinKeyboardView.commitComposing]，正文只剩一个「5」，用户敲的拼音被静默丢弃
     * （2026-10-21 真机复现，与数字层「1你」是同一个不变式）。
     *
     * 判据必须是**位置**判据：只判「出现过 commitComposing」会被「挪到上屏之后」骗过。
     */
    @Test
    fun 数字行点数字前必须先提交拼音() {
        val build = TestSources.blockAfter(
            TestSources.codeSource("PinyinKeyboardView.kt"),
            "private fun buildNumberRow()",
        )
        assertBefore(
            build,
            "commitComposing()",
            "listener?.onCommitText(text)",
            "数字行点数字前必须先 commitComposing：commitText 会替换 composing 区，" +
                "不先落定就会把拼音吞掉（真机实测：打 ni → 点一下 → 正文只剩刚上屏的那个字）",
        )
        // 上屏文本必须读**键面标签**（2026-10-21 文本自定义起）：读构造期的默认数字会让
        // 「改文本」只改外观、不改上屏。位置判据：`val text = key.label` 必须在两个上屏点之前。
        assertBefore(
            build,
            "val text = key.label",
            "listener?.onCommitText(text)",
            "上屏文本必须取键面标签（用户把按钮改成「甲」，点它就得上屏「甲」）",
        )
    }

    /**
     * 点固定数字行必须判搜索态（2026-10-21 修 P0）。
     *
     * 剪贴板搜索面板打开时 26 键仍在、全部路由到搜索框（`onLetterPressed` / `commitPasswordDigit`
     * 都判 `isPanelSearch()`）。数字行不判，就会把数字 `commit` 给宿主 —— 用户在搜历史，
     * 正文被插入数字。判据：闭包里必须有这一分支，且分支体是 `appendSearch`（不是上屏）。
     */
    @Test
    fun 数字行点击必须判搜索态() {
        val build = TestSources.blockAfter(
            TestSources.codeSource("PinyinKeyboardView.kt"),
            "private fun buildNumberRow()",
        )
        assertTrue("数字行闭包必须判搜索态（否则搜索时文本进了宿主正文）", "isPanelSearch()" in build)
        assertBefore(
            build,
            "searchPanel?.appendSearch(text)",
            "listener?.onCommitText(text)",
            "搜索态分支必须在上屏之前（顺序反了就成了「先上屏再追加搜索词」）",
        )
    }

    /**
     * 数字行按钮文本：建行与就地刷新**都**必须走 [NumberRowLabels.effective]。
     *
     * 为什么两处都要钉：`buildNumberRow` 只覆盖「新建视图」，而框架跨会话复用同一个键盘视图
     * （`JinnIme.rebuildInputViewForSymbolLayout` 的注释），收藏页改完走的是就地刷新 ——
     * 只钉一处就会漏掉另一条路径，表现为「新建时是对的、改完不生效」。
     *
     * `effective` 是空槽回落默认的唯一落点：它恒返回 10 个非空串，
     * 因此键面不会出现「按了没反应」的空槽键（BUG.md 第 15 批 L7 的形态）。
     */
    @Test
    fun 数字行文本必须走NumberRowLabels归一() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val build = TestSources.blockAfter(view, "private fun buildNumberRow()")
        val apply = TestSources.blockAfter(view, "fun applyNumberRowLabels()")
        assertTrue("建行必须读 NumberRowLabels.effective", "NumberRowLabels.effective(" in build)
        assertTrue(
            "就地刷新必须读 NumberRowLabels.effective（否则改完不生效 / 空槽留空键）",
            "NumberRowLabels.effective(" in apply,
        )
        assertTrue(
            "就地刷新入口必须是公开方法（收藏页与 onStartInputView 都要调）",
            "fun applyNumberRowLabels()" in view,
        )
        // 默认值不得再在视图侧留一份可变副本：真源只在模型层
        assertFalse(
            "视图侧不得再定义数字行默认排列（真源是 NumberRowLabels.DEFAULT_KEYS）",
            Regex("""const val NUMBER_ROW_KEYS""").containsMatchIn(view),
        )
    }

    /**
     * 收藏页的数字行文本分区必须**只在开关开着时**渲染。
     *
     * 判据取「早退」而不是「GONE」：关着的人不该在本页看到一个与自己无关的分区，
     * 而 GONE 只是看不见、仍在视图树里占一次 measure。
     *
     * 位置判据：分区必须在 `removeAllViews()` 之后、`pages.isEmpty()` 早退**之前** ——
     * 排在空态分支后面，收藏被删光时这一节会跟着消失。
     */
    @Test
    fun 收藏页数字行分区只在开关开时渲染() {
        val page = TestSources.codeSource("FavoriteSymbolsActivity.kt")
        val section = TestSources.blockAfter(page, "private fun renderNumberRowSection()")
        assertTrue(
            "分区渲染必须只认数字行开关（关着时整段不渲染）",
            Regex("""if \(!prefs\.numberRowEnabled\) return""").containsMatchIn(section),
        )
        assertTrue("分区必须展示生效文本（空槽已回落默认）", "NumberRowLabels.effective(" in section)
        assertTrue("分区每格点击进单槽编辑", "showLabelDialog(" in section)
        // 一行 10 个等宽均分（用户 2026-10-21 指定）：与键盘那排的左右位置一一对应，
        // 改哪个不用在心里换算。钉「横排 + weight 均分」而不是列数字面量 ——
        // 写 5 列两行会让页面位置与键盘位置错位
        assertTrue(
            "数字行分区必须横排一行（与键盘那排的左右位置一致）",
            Regex("""LinearLayout\(this\)\.apply\s*\{\s*orientation = LinearLayout\.HORIZONTAL""")
                .containsMatchIn(section),
        )
        assertTrue(
            "10 个格子必须 weight 均分（等宽才与键盘逐列对齐）",
            Regex("""LinearLayout\.LayoutParams\(0,\s*cell,\s*1f\)""").containsMatchIn(section),
        )
        assertFalse(
            "不得改回多行网格（页面位置会与键盘位置错位）",
            "GridLayout(" in section,
        )
        val render = TestSources.blockAfter(page, "private fun renderPages()")
        assertTrue(
            "分区必须排在 removeAllViews 之后（否则被清掉）",
            render.indexOf("renderNumberRowSection()") > render.indexOf("removeAllViews()"),
        )
        assertTrue(
            "分区必须排在收藏空态早退之前（否则收藏删光时分区跟着消失）",
            render.indexOf("renderNumberRowSection()") < render.indexOf("pages.isEmpty()"),
        )
    }

    /**
     * 单槽写入必须先清洗校验、再落盘、最后轻量推送。
     *
     * 三条顺序各对应一个已发生过的缺陷形态：
     *  ① 不先清洗 ⇒ 零宽 / NBSP 进键面 = 看不见却占格子的空槽键（BUG-36 同源）；
     *  ② 限长不走码点口径 ⇒ emoji 被 UTF-16 码元数误判超限（BUG-37 同源）；
     *  ③ 不推 `onNumberRowLabelsChanged` ⇒ 视图跨会话复用，改完不生效。
     */
    @Test
    fun 数字行单槽写入必须先清洗校验再落盘并推送() {
        val page = TestSources.codeSource("FavoriteSymbolsActivity.kt")
        val save = TestSources.blockAfter(page, "private fun saveLabel(")
        assertTrue("写入前必须清洗（与收藏同一口径）", "FavoriteSymbols.clean(" in save)
        assertTrue(
            "限长必须走码点口径（emoji 不被误拒）",
            "FavoriteSymbols.withinLimit(item, NumberRowLabels.MAX_CHARS)" in save,
        )
        assertTrue("超限必须提示而不是静默丢弃", "TEXT_LABEL_TOO_LONG" in save)
        assertTrue("关键写必须同步落盘（BUG-34）", "prefs.flush()" in save)
        assertBefore(
            save,
            "prefs.numberRowLabels =",
            "JinnIme.onNumberRowLabelsChanged()",
            "必须先落盘再推送（顺序反了会让推送读到旧值）",
        )
        assertTrue("写完必须重渲染本页", "renderPages()" in save)
    }

    /**
     * 数字行文本必须进备份两侧，且与收藏同款「只在改过时导出」。
     *
     * 导出侧套 `sp.contains` 守卫：否则会把 10 个默认字面量固化成用户值，
     * 将来调默认顺序对老用户永久失效（与外观七键 / 拼音字号同一条纪律）。
     */
    @Test
    fun 数字行文本必须进备份两侧() {
        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("导出必须带数字行文本", "put(KEY_NUMBER_ROW_LABELS, numberRowLabels)" in prefs)
        assertTrue(
            "导出必须套「改过才导出」守卫（否则默认值被固化成用户值）",
            Regex("""if \(sp\.contains\(KEY_NUMBER_ROW_LABELS\)\)\s*put\(KEY_NUMBER_ROW_LABELS""")
                .containsMatchIn(prefs),
        )
        assertTrue("导入必须认这一键", "KEY_NUMBER_ROW_LABELS ->" in prefs)
        assertTrue(
            "导入侧 null 必须删键回落默认（与收藏符号同构）",
            Regex("""KEY_NUMBER_ROW_LABELS -> if \(v\.kind == ConfigBackup\.BackupValue\.KIND_NULL\)""")
                .containsMatchIn(prefs),
        )
        assertTrue(
            "键名必须是 private const val（PrefsBackupCoverageTest 的正则只认这一种）",
            """private const val KEY_NUMBER_ROW_LABELS = "number_row_labels"""" in prefs,
        )
        // 长度闸必须比归一后（BUG-35：两侧口径不同源会让脏值从另一侧漏进来）。
        // 判据取「导入分支起点」之后的窗口，不用整份文件：`MAX_NUMBER_ROW_LABELS_CHARS`
        //    在文件里的第一次出现是常量声明，拿整份文件对拍会让 indexOf 落到声明上而恒失败。
        val imp = prefs.substring(prefs.indexOf("KEY_NUMBER_ROW_LABELS ->"))
        assertTrue("导入侧找不到数字行文本分支", imp.isNotEmpty())
        assertBefore(
            imp,
            "NumberRowLabels.serialize(NumberRowLabels.parse(raw))",
            "MAX_NUMBER_ROW_LABELS_CHARS",
            "导入侧长度闸必须卡在归一之后",
        )
    }

    /**
     * 「导出全部图片」的进程级互斥必须内置在 [startExport] 那一侧。
     *
     * 2026-10-19 修：原先 CAS 只放在 `exportAllImages`，而无记忆目录时那条路径会
     * `EXPORTING.set(false)` 再弹系统选择器，用户选完目录由 launcher 回调**直接进
     * startExport** —— 那条路径从来没置过位，整个导出期间标记都是 false ⇒ 旋转后新实例
     * 再点一次就能并发起第二趟（同名文件被 SAF 改名成重复件、两个进度框、
     * 先完成者把 exportDlg 置 null）。
     *
     * 同时钉两件事：① 置位点在 startExport 内；② 任务体那一层必须有 finally 还标记
     * （BackgroundIo.wrapped 只记日志不上抛，中途抛异常会静默跳过 runOnUiThread 里那句
     * set(false) ⇒ 停在 true，这个进程再也导不出图片）。
     */
    @Test
    fun 图片导出互斥必须内置在startExport() {
        val src = TestSources.codeSource("ClipboardHistoryActivity.kt")

        // ① 置位点在 startExport 内，且必须**早于任务体**：用 assertBefore 断位置而不是
        // `substringBefore("BackgroundIo.runLong")` —— 后者在锚点消失（改成 run / 换个调度器）
        // 时返回整个函数体，判据退化成「函数里有这一句」，把置位点挪进 worker 也能绿（L-1433）
        val body = TestSources.blockAfter(src, "private fun startExport(treeUri: Uri)")
        assertBefore(
            body,
            "if (!EXPORTING.compareAndSet(false, true))",
            "BackgroundIo.runLong",
            "置位必须早于任务体（放进 worker 就挡不住并发起第二趟）",
        )

        // ② 任务体那一层必须有 finally 还标记
        assertTrue(
            "任务体必须有 finally 还标记（异常路径也要还，否则永久锁死）",
            "} finally {" in body,
        )
        assertTrue(
            "finally 里必须还 EXPORTING",
            "runOnUiThread { EXPORTING.set(false) }" in body,
        )

        // ③ 用户取消选目录必须还标记（否则本进程再也导不出）
        val launcher = TestSources.blockAfter(src, "private val exportDirLauncher")
        assertTrue(
            "取消选目录必须还回标记",
            "if (uri == null)" in launcher && "EXPORTING.set(false)" in launcher,
        )
    }

    /**
     * 拼音字号滑杆在「自动」档必须跟着候选字号走（2026-10-22）。
     *
     * 只修读数时，真机 28→42sp 后同屏出现「读数 29 sp、拇指停在 20 sp」——
     * 同一行控件与数字自相矛盾。拇指同样刷成派生值。
     * 两条反向陷阱必须同时钉住，否则「跟刷」反过来会把自动档写成显式值：
     * ① 拼音监听的 `onProgressChanged` 第一句就判 `!fromUser`（`setProgress()` 走 false，
     *    触摸 / 键盘方向键 / 无障碍 ACTION_SET_PROGRESS 走 true，见 AbsSeekBar）；
     * ② 跟刷本身只在自动档执行（显式档拇指不得被候选字号拖走）。
     */
    @Test
    fun `拼音滑杆自动档必须跟刷且程序化写不得回写`() {
        val code = codeOf("KeyAppearanceActivity.kt")
        val pinyin = blockAfter(code, "seekPinyinSize.setOnSeekBarChangeListener")
        val candidate = blockAfter(code, "seekTextSize.setOnSeekBarChangeListener")

        // ① 程序化写不得落盘：守卫必须出现在写 prefs 之前
        assertBefore(
            pinyin,
            "if (!fromUser) return",
            "prefs.pinyinTextSp = sp",
            "拼音字号监听必须挡掉程序化写（否则自动档被写成显式值）",
        )

        // ② 自动档才跟刷拇指，且值必须走同一套派生口径
        assertTrue(
            "候选字号监听必须在自动档跟刷拼音滑杆位置",
            "prefs.pinyinTextSp == CandidateText.PINYIN_DEFAULT_AUTO" in candidate &&
                "seekPinyinSize.progress" in candidate,
        )
        assertTrue(
            "跟刷值必须走同一套派生口径（pinyinSpOf → pinyinSpToProgress）",
            "CandidateText.pinyinSpToProgress(CandidateText.pinyinSpOf(sp))" in candidate,
        )
    }

    /**
     * L-1418：切分类必须同时收**操作条**与**确认条**。
     *
     * 操作条收不全只是「键作用在看不见的条目上」；确认条收不全更狠 —— 它的「确定」删的是
     * **全库**普通记录（`db.deleteAll`，与当前分类无关），而用户已经把视线移到新分类 ⇒
     * 切到「网址」再顺手确定，别的分类一起没了（2026-10-22 真机复现：4 条网址下仍挂着
     * 「清空历史？（收藏保留）」）。三条显示路径（`onPanelShown` / `showItemMenu` /
     * `selectCategory`）必须同口径，漏一条就是这个缺陷。
     */
    @Test
    fun `剪贴板面板切分类必须同时收起操作条与确认条`() {
        val src = codeOf("ClipboardPanelView.kt")
        val body = blockAfter(src, "private fun selectCategory(category: String?)")
        assertStatementLine(body, "hideActionBar()", "切分类必须收起长按操作条")
        assertStatementLine(
            body, "hideConfirmBar()",
            "切分类必须收起「清空历史」确认条（它的确定删的是全库，不是当前分类）",
        )
        // 位置也要钉住：必须在 `currentCategory` 落位之前（此后列表 / 网格已按新分类重取）
        assertBefore(
            body, "hideConfirmBar()", "currentCategory = category",
            "收起确认条必须发生在切换分类之前",
        )
        // 反向：另外两条显示路径也不许漏（同一危害，任何一条被删都会让缺陷换个入口重现）
        assertTrue(
            "onPanelShown 也必须收确认条",
            "hideConfirmBar()" in blockAfter(src, "fun onPanelShown()"),
        )
        assertTrue(
            "showItemMenu 也必须收确认条",
            "hideConfirmBar()" in blockAfter(src, "private fun showItemMenu("),
        )
    }

    /**
     * L-1413：用户词库页的导出键与「删除」必须**默认禁用**。
     *
     * `rows` 由后台读盘填，首帧还是空表；键默认可点的话，冷开页立刻点导出会得到
     * 「还没有可导出的词」——而磁盘上明明有几千条（假陈述）。删除更险：这一屏的条数在
     * 加载完成前不可信。判据落在 layout 的 `android:enabled="false"`（真正的首帧状态），
     * 加上「唯一的开启点在 `renderSummary`」。
     */
    @Test
    fun `用户词库页导出键必须默认禁用`() {
        val xml = TestSources.codeOf(
            TestSources.rawSource(
                "src/main/res/layout/activity_user_dict.xml",
                "app/src/main/res/layout/activity_user_dict.xml",
            )
        )
        for (id in listOf("btn_export_yaml", "btn_export_tsv", "btn_export_txt", "btn_delete_dict")) {
            val tag = Regex("""(?s)<Button[^>]*@\+id/$id"[^>]*>""").find(xml)?.value
            assertTrue("activity_user_dict.xml 里找不到 $id 的 Button", tag != null)
            assertTrue(
                "$id 必须写 android:enabled=\"false\"：首帧 rows 是空表，可点时导出会说" +
                    "「还没有可导出的词」/ 删除按不可信的条数操作真数据",
                tag!!.contains("android:enabled=\"false\""),
            )
        }
        // 唯一的开启点是加载完成后的 renderSummary（在 onCreate 里另开一处会让「默认禁用」失效）
        val act = codeOf("UserDictActivity.kt")
        val summary = blockAfter(act, "private fun renderSummary(")
        assertTrue(
            "renderSummary 必须按加载结果放开三个导出键",
            "btnExportYaml.isEnabled = !empty && !unreadable" in summary &&
                "btnExportTsv.isEnabled = !empty && !unreadable" in summary &&
                "btnExportTxt.isEnabled = !empty && !unreadable" in summary,
        )
        assertTrue("renderSummary 必须按加载结果放开删除", "btnClose.isEnabled = !unreadable" in summary)
    }

    /**
     * L-1414：关闭学习前的那次 `flush()` 必须按返回值留痕。
     *
     * `setEnabled(false)` 先 flush 再 `entries.clear()`：写盘失败时这次学习既进不了文件、
     * 内存表又被清空，重开也无从补写 —— 丢掉的是真数据，而它原先连一行日志都不留。
     * 该路径没有 UI 通道（开关就在用户手边，弹框会挡住操作），所以判据是「返回值被使用」。
     */
    @Test
    fun `关闭学习前必须按落盘结果留痕`() {
        val body = blockAfter(codeOf("UserFrequency.kt"), "fun setEnabled(value: Boolean)")
        assertTrue("关闭分支必须判 flush() 的返回值（丢弃即静默丢数据）", "if (!flush())" in body)
        assertTrue("失败必须留 W 级别日志（这里没有 UI 通道）", "Diagnostics.w(" in body)
        assertBefore(body, "if (!flush())", "enabled = value", "flush 必须在置 false 之前（否则被落盘入口拦掉）")
        assertBefore(body, "if (!flush())", "entries.clear()", "flush 必须在清内存之前")
    }

    // ── 触摸 / 手势 / 时序（2026-10-23）────────────────────

    /**
     * L-1435：清空输入框必须**先折叠光标、再量两侧长度**。
     *
     * 旧写法在 `setSelection(0, 0)` **之前**取 `getTextAfterCursor`：光标在末尾（正常打字后的
     * 常态）时 `after = 0` ⇒ `deleteSurroundingText(0, 0)` 一个字符都不删 ——「双击退格 + 长按」
     * 在界面上没有任何失败提示，用户只会以为手势失灵。真机取证：40 字 → 29（少掉的 11 个全是
     * 连删循环删的），光标移到文首做同一手势 40 → 0。
     *
     * 断言全在源码形态上（`InputConnection` 进不了 JVM 单测），三条缺一不可：
     * ① 两侧都量（只量 after 就是旧缺陷）；② 量在折叠之后；③ 删除量传 `before + after`。
     */
    @Test
    fun `清空输入框必须量光标两侧长度且在折叠之后取`() {
        val body = blockAfter(codeOf("JinnIme.kt"), "private fun deleteAllText()")
        assertTrue(
            "清空必须按光标**两侧**的实际长度删：只量 after 时，光标在末尾取到 0 ⇒ 一个字符都不删（L-1435）",
            "getTextBeforeCursor(" in body && "getTextAfterCursor(" in body,
        )
        assertBefore(
            body, "connection.setSelection(0, 0)", "getTextAfterCursor(",
            "长度必须在折叠光标**之后**量（在之前量到的 after 是旧光标之后那截，光标在末尾时为 0）",
        )
        assertTrue(
            "删除量必须同时带上两侧长度：AOSP 的 deleteSurroundingText 只删选区两侧、" +
                "选区本体不在范围内，少一侧就会留下半截",
            "connection.deleteSurroundingText(before, after)" in body,
        )
        assertFalse(
            "不得退回「只删光标之后」的写法（L-1435 的原形）",
            "deleteSurroundingText(0, after)" in body,
        )
    }

    /**
     * L-1436：26 个字母键的手势状态必须**一键一槽**。
     *
     * 共用单槽时，第二根手指按在**另一个键**上会覆盖基准点、并顺手撤掉对方待触发的长按：
     * 长按 `a` + 另一指轻点 `s` ⇒ 得到 `sa`（单指对照是 `A`）；符号层的翻页阈值同样按别人的
     * 基准点算，轻点会被判成滑动（L-1439）。三个单槽字段因此必须在源码里彻底消失 ——
     * 留一个都等于把「另一指按下就清空我的标记」那条路径留在原处。
     */
    @Test
    fun `字母键手势状态必须一键一槽`() {
        val src = codeOf("PinyinKeyboardView.kt")
        assertTrue(
            "手势状态必须按键存（KeyGesture + keyGestures 槽表）",
            "private class KeyGesture" in src && "keyGestures[c]" in src,
        )
        for (stale in listOf("keyTouchStartX", "keyTouchStartY", "keyTouchConsumed", "keyLongPressRunnable")) {
            assertFalse("单槽手势字段 $stale 必须绝迹（L-1436 的原形）", stale in src)
        }
        val body = blockAfter(src, "private fun handleKeyTouch(")
        assertTrue("取状态必须走本键的槽", "val g = gestureOf(c)" in body)
        assertTrue(
            "取消长按必须只作用于本键（撤全部键 = 另一根手指一按就毁掉别人的长按）",
            "cancelLongPressUppercase(g)" in body,
        )
        assertTrue(
            "翻页判定必须用本键的基准点（用别人的基准点算 dx ⇒ 轻点被判成滑动，L-1439）",
            "val dx = event.rawX - g.startX" in body,
        )
    }

    /**
     * L-1437：层 / 符号页在手势中途被改过，抬手不得上字。
     *
     * 旧写法在 `ACTION_UP` 才按**当前** `layer` 解析 `symbolValueOf(c)`：按住字母键、另一指切到
     * 「符号」，松手就把用户**从未按过**的符号上屏（短按路径没有任何挡板）。判据取「层 + 符号组 +
     * 页」三元组：切层、换组、翻页三种改法都能挡住。
     */
    @Test
    fun `按键抬手必须还在按下时那一层那一页`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "private fun handleKeyTouch(")
        assertTrue(
            "DOWN 必须快照当前层 / 符号组 / 页",
            "g.layerAtDown = layer" in body &&
                "g.groupAtDown = symbolGroupIndex" in body &&
                "g.pageAtDown = symbolPageInGroup" in body,
        )
        assertTrue(
            "UP 必须比对层是否没变",
            "val sameLayer = layer == g.layerAtDown" in body,
        )
        assertTrue(
            "UP 必须比对符号组与页（同层翻页同样会让上屏内容变成用户没按过的那个）",
            "symbolGroupIndex == g.groupAtDown && symbolPageInGroup == g.pageAtDown" in body,
        )
        assertTrue(
            "上字条件必须同时含「没消费 + 同层 + 同页」",
            "if (!g.consumed && sameLayer && sameSymbolPage && isInsideKey(" in body,
        )
    }

    /**
     * L-1438：按住退格滑出键面必须**停止**连删。
     *
     * 连删是 55ms 的自我重排循环，中止条件原先只有抬手：手指拖到键外（用户以为已取消）仍在
     * 一路删，真机实测 15 字删到 1 字。判据用「出键面」而不是 `touchSlop`：退格键宽，按住时
     * 手抖几 dp 不该中止，出键面才等于用户说的「停下」。
     */
    @Test
    fun `退格按住滑出键面必须停止连删`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "private fun handleBackspaceTouch(")
        assertTrue("退格触摸必须有 ACTION_MOVE 分支", "MotionEvent.ACTION_MOVE ->" in body)
        assertTrue(
            "滑出键面即停（出界判定 + 停循环）",
            "if (backspaceHeld && !isInsideKey(btnBackspace, event))" in body &&
                "stopBackspaceRepeat()" in body,
        )
        assertBefore(
            body, "MotionEvent.ACTION_MOVE ->", "MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->",
            "MOVE 分支必须在 UP 之前（写在 UP 之后永远不会被执行）",
        )
    }
    /**
     * L-1452：`runCatching` 的失败分支不得留空（`onFailure { }`）。
     *
     * 静默吞异常会把「失败」变成「数据消失 / 状态错」，而诊断包里查不到任何痕迹 ——
     * 本轮实测两处（`JinnIme.onStartInputView` 的 `hideWindow()`、`SearchPanelView`
     * 收起系统软键盘的 Binder 调用）都写成空 `onFailure`，症状分别是「键盘关不掉」
     * 与「搜索框盖着系统软键盘」却无据可查。判据扫**全部**主源码，不点名具体文件：
     * 点名写法只能护住已经改好的那两处，新加的空分支照样过关。
     */
    @Test
    fun `runCatching 的失败分支不得留空`() {
        val dir = listOf(
            File("app/src/main/java/com/jinn/inputmethod"),
            File("src/main/java/com/jinn/inputmethod"),
        ).firstOrNull { it.isDirectory } ?: error("找不到主源码目录（测试工作目录变了？）")
        val emptyHandler = Regex("""\.onFailure\s*\{\s*\}""")
        val offenders = dir.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
            .filter { emptyHandler.containsMatchIn(TestSources.codeOf(it.readText())) }
            .map { it.name }
        assertTrue("这些文件用空 onFailure{} 静默吞异常，必须留一行 W 日志：$offenders", offenders.isEmpty())
    }

    /** 取布局 XML 原文（Gradle 从 `app/` 跑、IDE 从仓库根跑，两种工作目录都认） */
    private fun layoutFile(name: String): String {
        val xml = listOf(
            File("src/main/res/layout/$name"),
            File("app/src/main/res/layout/$name"),
        ).firstOrNull { it.isFile } ?: error("找不到 $name")
        return xml.readText()
    }

    /** 取某 id 所在**开始标签**里的属性值（属性顺序无关，只在该标签内匹配） */
    private fun layoutAttr(xml: String, id: String, name: String): String {
        val at = xml.indexOf("@+id/$id")
        assertTrue("布局里没有 @+id/$id", at >= 0)
        val lt = xml.lastIndexOf('<', at)
        val end = listOf(xml.indexOf("/>", at), xml.indexOf('>', at)).filter { it > 0 }.minOrNull() ?: xml.length
        val tag = xml.substring(lt, end)
        return Regex("""android:$name="([^"]*)"""").find(tag)?.groupValues?.get(1)
            ?: error("@+id/$id 缺 android:$name")
    }

    /** [id] 的直接父容器标签名：取该元素**开始标签之前**最后出现的那个容器开始标签 */
    private fun parentTagOf(xml: String, id: String): String? {
        val at = xml.indexOf("@+id/$id")
        assertTrue("布局里没有 @+id/$id", at >= 0)
        // id 属于它自己那个开始标签 ⇒ 先退到标签的 '<'，再往前找父容器
        val before = xml.substring(0, xml.lastIndexOf('<', at))
        val candidates = listOf("<HorizontalScrollView", "<ScrollView", "<LinearLayout", "<FrameLayout")
            .map { it to before.lastIndexOf(it) }
            .filter { it.second >= 0 }
        return candidates.maxByOrNull { it.second }?.first?.removePrefix("<")
    }

    /**
     * L-1456：功能面板键标必须按 **dp** 派生，不随系统字体缩放。
     *
     * 事实（真机 1440×3200 / density 3.5 / font_scale 2.0）：13sp 的主标让每个按钮的内容宽
     * 从 191px 涨到 252px，7 键总宽 ≈1764px > 1440。`candidate_list` 在 `CandidateScrollView`
     * （HorizontalScrollView）里，`fillViewport` **只在内容比视口窄**时才补宽 ⇒ 一旦超宽就此
     * 撒手，最右端的退出口「收起」整键落在 x≈1581..1833，完全不可见（截图实测「图库」截断于
     * 1440，收起一个像素都不剩）。改成 dp 后：font 1.0 时 density == scaledDensity，换算结果
     * 逐像素相同；2.0 时键标不动 ⇒ 总宽回落到 1127px，fillViewport 重新介入、7 键均分。
     */
    @Test
    fun `功能面板键标按 dp 派生不随字体缩放`() {
        val code = codeOf("PinyinKeyboardView.kt")
        val body = blockAfter(code, "private fun buildFunctionButton(")
        assertTrue(
            "功能面板主标必须显式用 dp 单位（COMPLEX_UNIT_DIP）",
            "setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, FUNCTION_LABEL_DP)" in body,
        )
        assertFalse(
            "主标不许再写成 sp：`textSize = 13f` 会被 fontScale 放大到 26sp，撑破功能栏",
            "textSize = 13f" in body,
        )
        assertTrue("字号常量要声明为 13（与底行键标同档）", "const val FUNCTION_LABEL_DP = 13f" in code)
    }

    /**
     * L-1457：键盘底行（符号 / 数字 / 逗号 / 空格 / 句号 / 中英）的键标一律按 **dp** 派生。
     *
     * 事实（真机 font_scale 2.0）：底行是固定 56dp 高的 XML 键，13sp 被放大成 26sp 后两字标签
     * 宽 182px > 键内容区 ⇒ Button 自动折两行（行框 2×107px > 168px 可画高），「号」「字」
     * 「英文」的下半截被键底裁掉；而同排的 26 键字母是 px 自绘、根本不放大 —— 同一行两套口径。
     * 标点（16dp）与中英两行（12dp）在代码里落，故两侧一起钉。
     */
    @Test
    fun `键盘底行键标按 dp 派生不随字体缩放`() {
        val xml = layoutFile("keyboard_pinyin.xml")
        for ((id, size) in listOf(
            "key_symbol" to "13dp",
            "key_digit" to "13dp",
            "key_space_hint" to "9dp",
            "key_space_label" to "13dp",
        )) {
            assertEquals(
                "@+id/$id 的键标字号必须是 $size（写成 sp 会被 fontScale 撑破 56dp 行高）",
                size,
                layoutAttr(xml, id, "textSize"),
            )
        }
        val code = codeOf("PinyinKeyboardView.kt")
        val punct = blockAfter(code, "private fun applyPunctuationLabel")
        assertTrue(
            "逗号 / 句号键标必须按 dp 派生",
            "setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, PUNCTUATION_TEXT_DP)" in punct,
        )
        assertFalse("标点键标不许走 sp 单位", "PUNCTUATION_TEXT_SP" in punct)
        assertTrue(
            "中英切换键的两行键标必须按 dp 派生",
            "setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, LANG_LABEL_DP)" in code,
        )
        assertFalse("中英键不许再写 sp（`btnLang.textSize = 12f`）", "btnLang.textSize = 12f" in code)
        // 标点字号 = 底行基础字号 + 3：基础取 XML 里同类键的 13dp，两处必须同步改
        val base = layoutAttr(xml, "key_symbol", "textSize").removeSuffix("dp").toFloat()
        val punctSize = Regex("""const val PUNCTUATION_TEXT_DP = ([0-9.]+)f""")
            .find(code)?.groupValues?.get(1)?.toFloat()
        assertEquals("标点字号必须是底行基础字号 + 3", base + 3f, punctSize ?: 0f, 0.01f)
    }

    /**
     * L-1458：剪贴板历史页底部两行（翻页 / 操作）必须能横滑，且键标单行。
     *
     * 事实（真机 font_scale 2.0）：翻页行是普通 LinearLayout，四个按钮合计 1448px 比屏宽多 8px，
     * LinearLayout 把「末尾那个」按**剩余宽度**量 ⇒「下一页」只拿到 ~150px、折成两行、字比行框
     * 还高。操作行本来就有 HorizontalScrollView（横滑可达，实测 1300→200 一划即可看到
     * 「刷新 / 导入文件」），只是没有滚动条提示 —— 本轮把翻页行补成同款容器，两行都不许折行。
     */
    @Test
    fun `历史页底部两行可横滑且键标不折行`() {
        val xml = layoutFile("activity_clipboard_history.xml")
        assertEquals(
            "翻页行必须包在 HorizontalScrollView 里（否则 font 2.0 下「下一页」被压成两行）",
            "HorizontalScrollView",
            parentTagOf(xml, "hist_pager"),
        )
        assertEquals(
            "操作行必须继续包在 HorizontalScrollView 里（窄屏 / 大字号靠横滑兜底）",
            "HorizontalScrollView",
            parentTagOf(xml, "hist_actions"),
        )
        val view = blockAfter(codeOf("ClipboardHistoryActivity.kt"), "private fun compactView()")
        assertTrue(
            "底部行按钮必须 `maxLines = 1`：折行会让行高翻倍、字比行框还高",
            "maxLines = 1" in view,
        )
    }

    /**
     * 预测出口登记候选键时不得抹掉模糊音的真实键（BUG.md L-1467）。
     *
     * [PinyinEngine.predict] 登记的是索引里的**真实键**（延续词在词库中的读音），
     * 与模糊分支登记的「变体键」不是一回事；走默认值 `clearTrueKey = true` 会顺手清掉
     * `candidateTruePinyin` 里刚记下的真实键 ⇒ 下一级预测按用户实际输入的变体键去扫键区，
     * 延续词扫不到（模糊音打开后第二级预测凭空变少）。补全出口早就是 `clearTrueKey = false`。
     */
    @Test
    fun `预测出口登记候选键不得抹真实键`() {
        val src = TestSources.codeSource("PinyinEngine.kt")
        // 语义口径：凡「以 key.substring(...) 作读音」的 noteCandidateKeys 调用，一律不许走默认值
        val hits = src.lines().filter { "noteCandidateKeys(" in it && "substring(" in it }
        assertTrue("预料的两处预测出口都在（实际 ${hits.size} 处）", hits.size == 2)
        for (line in hits) {
            assertTrue(
                "预测出口必须传 clearTrueKey = false：${line.trim()}",
                "clearTrueKey = false" in line,
            )
        }
        // 反向：补全出口（注释里写明理由的那一处）仍保持 false
        assertTrue(
            "补全出口继续保留 clearTrueKey = false",
            "noteCandidateKeys(ws, key, clearTrueKey = false)" in src,
        )
    }

    /**
     * 剪贴板总开关必须在**入库入口**再查一次（BUG.md L-1468）。
     *
     * 入口 `onClipboardChanged` 在采集那一刻查过开关，但首次读不到剪贴板时会安排 250ms 后重试；
     * 用户在这个窗口里关掉开关，重试仍会落库一条 —— 关掉采集之后还在记，是隐私面。
     */
    @Test
    fun `剪贴板入库入口必须自查开关`() {
        val body = blockAfter(codeOf("ClipboardController.kt"), "private fun extractAndSave(")
        val statement = body.lines()
            .drop(1)
            .first { it.isNotBlank() && it.trim() != "{" }
            .trim()
        assertTrue(
            "extractAndSave 首句必须是总开关闸（实际：$statement）",
            statement == "if (!ClipboardPrefs.of(appContext).enabled) return",
        )
    }

    /**
     * 判据里的 `lowercase()` / `uppercase()` 必须锁 Locale（BUG.md L-1469 / A-298）。
     *
     * 无参 `lowercase()` 走默认 Locale：土耳其语 / 阿塞拜疆语下 `I` → `ı`，而两处判据词表
     * （HTTP 错误码、响应体顶层键名）都含 `i` —— 用户把系统语言改成 tr/az 就会**静默失效**
     * （限流被报成服务异常、请求护栏被整片绕过）。第 22 轮只覆盖了部分调用点。
     */
    @Test
    fun `判据小写化必须锁 Locale`() {
        for (name in listOf("ClipboardController.kt", "OpenAiTranslator.kt", "Translation.kt")) {
            val src = TestSources.codeSource(name)
            assertFalse("$name 仍有未锁 Locale 的 lowercase()", ".lowercase()" in src)
        }
        val openAi = TestSources.codeSource("OpenAiTranslator.kt")
        val localeRoot = openAi.split("lowercase(Locale.ROOT)").size - 1
        assertTrue("OpenAiTranslator 的三处判据都要锁 Locale（实际 $localeRoot 处）", localeRoot == 3)
    }

    /**
     * 「保存并重启」前必须把用户学习的两份缓冲刷下去（BUG.md L-1492）。
     *
     * 设置页的重启路径先 `flush()` 了 Prefs 与剪贴板参数，却漏了词频（2s 防抖）与选择台账
     * （写线程队列）；`killProcess` 是 SIGKILL —— 不走 onDestroy，队列里那几次学习直接丢。
     * 词库页的重启路径早就在刷（`DictManagerActivity.restartImeForDict`），两处口径必须一致。
     */
    @Test
    fun `保存并重启前必须刷用户学习缓冲`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "private fun restartImeProcess(")
        val killAt = body.indexOf("killProcess")
        assertTrue("restartImeProcess 里找不到 killProcess（重启改道了？）", killAt > 0)
        for (call in listOf("UserFrequency.flush()", "SelectionLedger.flush()")) {
            val at = body.indexOf(call)
            assertTrue("保存并重启前缺 $call（SIGKILL 不走 onDestroy，缓冲会安静丢）", at > 0)
            assertTrue("$call 必须在 killProcess 之前（实际 $at vs $killAt）", at < killAt)
        }
    }

    /**
     * 导出回执的条数必须取自「件内条目数」（BUG.md L-1491）。
     *
     * 旧实现两处都写 `val count = src.size`：`RIME_YAML` 会跳过没有读音的行、`TXT` 按词去重，
     * 件内条目于是少于表长 ⇒ 「已导出 N 条」比文件里的行数多。只钉「出现过 exportEntryCount」
     * 不够 —— 留一行按表长算的分支照样能过，所以同时反向钉住旧写法。
     */
    @Test
    fun `导出回执条数必须取自件内条目数`() {
        val page = codeOf("UserDictActivity.kt")
        for (fn in listOf("private fun writePlainExportOnIo(", "private fun writeExportOnIo(")) {
            val body = blockAfter(page, fn)
            assertTrue("$fn 必须用 SelectionLedger.exportEntryCount 报表内条数", "SelectionLedger.exportEntryCount(" in body)
            assertFalse("$fn 仍按入参表长报条数（`val count = src.size`）", "val count = src.size" in body)
        }
    }

    /**
     * 测试框不得把正文带进实例状态（BUG.md L-1488）。
     *
     * `EditText` 有 id 且光标在场时，框架会把**整份正文**写进实例状态的 `android:viewHierarchyState`。
     * 设置页测试框里有 40 万字时，`activityStopped` 的 Binder 事务即超限：真机实测 parcel 1,047,632B、
     * 其中 `edit_ime_test` 这一条占 1,040,172B，`TransactionTooLargeException` 在**主线程**抛出，
     * 整个输入法进程被带走（`ActivityClient.activityStopped`）。
     *
     * 守卫口径：`isSaveEnabled` 在**全文件只许有一个赋值且值为 false**。
     * 只钉「出现过 false」不够 —— 后面再补一行 `isSaveEnabled = true` 时字面量照样命中，
     * 而真值已经翻回可保存（变异实测：老写法照过）。
     */
    @Test
    fun `测试框不得把正文带进实例状态`() {
        val src = TestSources.codeSource("ReceivingEditText.kt")
        val assigns = Regex("""isSaveEnabled\s*=\s*(\w+)""").findAll(src)
            .map { it.groupValues[1] }.toList()
        assertTrue("测试框必须且只能关一次视图态保存（L-1488，实际 $assigns）", assigns == listOf("false"))
    }

    /**
     * 空候选时丢掉整串拼音必须留下 **release 可见** 的痕迹（BUG.md L-1448）。
     *
     * 冷启动词库尚未就绪时收起键盘，`commitComposing` 走的正是这条分支：原先只有
     * `if (Diagnostics.KEY_TRACE) Diagnostics.v(...)`，而 `KEY_TRACE = BuildConfig.DEBUG` ⇒
     * 用户装上正式包后「打的字消失」与「日志里什么都没有」同时成立，两侧都无从追查。
     *
     * 判据同时钉住**隐私面**：只许记长度，不得把拼音原文写进日志（日志可外发）。
     */
    @Test
    fun `提交时丢拼音串必须留痕且不记原文`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "fun commitComposing()")
        assertTrue(
            "空候选分支必须用 W 级：v 级在 release 里看不见（L-1448）",
            "Diagnostics.w(TAG, \"commitComposing: 无候选，丢弃拼音串" in body,
        )
        assertTrue("日志必须带长度（长度是唯一可外发的形态）", "composing.length" in body)
        assertFalse("不得把拼音原文写进日志（旧写法 `丢弃拼音 \$composing`）", "丢弃拼音 " in body)
        assertFalse(
            "该分支不得再挂在 KEY_TRACE 闸下",
            "KEY_TRACE) Diagnostics.v(TAG, \"commitComposing" in body,
        )
    }

    /**
     * 销毁路径等台账落盘必须有上限（BUG.md L-1451）。
     *
     * `SelectionLedger.flush()` 内部 `Future.get(FLUSH_TIMEOUT_MS = 2s)`，而 `onDestroy` 跑在
     * **主线程**：密钥库不可用或队列压着批量导入时，切走键盘 / 关输入法等于一次 ANR 级卡顿。
     * 写盘本身在台账的 writer 线程上，销毁路径少等不会丢行 ⇒ 只该限时，不该无限等。
     *
     * 判据取「调用点 + 参数常量 + 常量值」三处：只钉调用点的话，把常量改成 2000 照样绿。
     */
    @Test
    fun `销毁路径的台账落盘必须限时`() {
        val ime = codeOf("JinnIme.kt")
        val body = blockAfter(ime, "override fun onDestroy()")
        assertTrue(
            "onDestroy 必须用限时 flush（无参 = 主线程等满 2s）",
            "SelectionLedger.flush(FLUSH_ON_DESTROY_MS)" in body,
        )
        assertFalse("不得再用无参 flush（L-1451 的成因）", "SelectionLedger.flush()" in body)
        val ledger = codeOf("SelectionLedger.kt")
        assertTrue(
            "flush 必须接受超时参数（默认值仍是 FLUSH_TIMEOUT_MS）",
            "fun flush(timeoutMs: Long = FLUSH_TIMEOUT_MS): Boolean" in ledger,
        )
        val ms = Regex("""FLUSH_ON_DESTROY_MS = (\d+)L""").find(ime)?.groupValues?.get(1)?.toInt()
        assertTrue("找不到 FLUSH_ON_DESTROY_MS 的值", ms != null)
        assertTrue("销毁路径的等待上限过大（$ms ms，应 ≤ 500）", (ms ?: Int.MAX_VALUE) <= 500)
    }

    /**
     * 空白剪贴板内容被丢弃时必须留痕（BUG.md L-1477）。
     *
     * `if (text.isBlank()) return` 原先一行日志都不留：用户复制了东西却什么都没发生，事后翻诊断包
     * 也查不到 —— 与「空 `onFailure {}` 等于吞异常」是同一条仓库底线。
     *
     * 判据是**位置**判据：日志必须在同一个分支里、且在 `return` 之前（只判「出现过这句话」，
     * 把日志挪到分支外照样绿）。
     */
    @Test
    fun `空白剪贴板丢弃必须留痕`() {
        val src = codeOf("ClipboardController.kt")
        val at = src.indexOf("if (text.isBlank())")
        assertTrue("找不到剪贴板采集的空白分支（改名后要同步本用例）", at >= 0)
        val branch = src.substring(at, (at + 400).coerceAtMost(src.length))
        val logAt = branch.indexOf("剪贴板: 空白内容，跳过入库")
        val returnAt = branch.indexOf("return")
        assertTrue("空白分支必须先留痕（L-1477）", logAt >= 0)
        assertTrue("留痕必须在 return 之前（实际 $logAt vs $returnAt）", returnAt > logAt)
        assertTrue("必须是 W 级：V 级在正式包里不落盘，等于没留痕", "Diagnostics.w(TAG, \"剪贴板: 空白内容，跳过入库" in branch)
        assertTrue("日志必须带长度（原文是用户内容，不得入日志）", "text.length" in branch)
    }

    /**
     * 音效页的三条渲染不变式（`BUG.md` 附录 B `B-262`，后两条由第三道复核补）。
     *
     * ① 渲染前必须把**两道**闸门都刷进缓存：试听的可用态读 `KeyFeedback.soundGatesOpen()`，
     * 而它由三部分组成 —— 用户开关（`refresh(prefs)` 写）+ 系统触摸提示音 + 响铃模式
     * （`refreshSystemGates` 写）。原先 `renderAll` 只刷前一半，系统闸门只在 `onStart` 刷一次，
     * 页内每一次重画（拨开关、选完音色）都在用进页那一刻的快照。可达路径是「IME 未挂载 +
     * 页内按音量键落静音 / 震动 + 页内再拨开关」：键盘挂着时响铃模式一变就有接收器当场刷，
     * 只有没挂载时没人刷。
     *
     * ② 改了闸门就得重画（`L-1523`）：`soundOnSilent` 是闸门输入之一，那个开关原先只写盘不重画，
     * 静音下拨它，六个「试听」与页尾说明行都停在旧态（反向拨还更糟：按钮可点而 `playPreview`
     * 当场返回 ⇒ 点了没反应）。
     * ③ 粗粒度置灰只能收紧不能放宽（`L-1522`）：`setEnabledDeep(groupCard, soundOn)` 曾直接赋值，
     * 把 `makeGroupRow` 按「本组不播放 / 闸门不放行」设好的 `false` 又点亮成 `true`，按钮 40%
     * 透明却可点（点下去静默无反应）。
     *
     * 判据都是**位置 / 形态**判据：页面跑不了 JVM 单测。两次 refresh 必须落在 `soundGatesOpen()`
     * 之前（挪到读之后等于没刷）；写盘与重画必须同在一个回调块里；置灰那一行必须是相与。
     */
    @Test
    fun `音效页渲染前必须刷新两道闸门`() {
        val src = codeOf("TapSoundActivity.kt")
        val body = blockAfter(src, "private fun renderAll()")
        val readAt = body.indexOf("KeyFeedback.soundGatesOpen()")
        assertTrue("renderAll 里找不到 soundGatesOpen()（页面结构变了？）", readAt >= 0)
        for (call in listOf("KeyFeedback.refresh(prefs)", "KeyFeedback.refreshSystemGates(")) {
            val at = body.indexOf(call)
            assertTrue("renderAll 必须在读闸门之前刷「$call」（实际 $at vs $readAt）", at in 0 until readAt)
        }
        val onStart = blockAfter(src, "override fun onStart()")
        assertFalse(
            "系统闸门不许只靠 onStart 刷：页内重绘会读到过期缓存",
            "KeyFeedback.refreshSystemGates(" in onStart,
        )
        // ② 改闸门必须重画：写盘与重画要落在同一个回调块里
        assertTrue(
            "改「静音时仍播放」必须重画（它是 soundGatesOpen 的输入之一，不重画就停在旧态）",
            "renderAll()" in blockAfter(src, "makeSwitch(TEXT_SILENT_SWITCH"),
        )
        // ③ 粗粒度置灰只能收紧：直接赋值会把更细的判据重新点亮
        assertTrue(
            "粗粒度置灰只能收紧不能放宽（否则「不播放」组与闸门关闭时的试听按钮被重新点亮）",
            "view.isEnabled = enabled && view.isEnabled" in blockAfter(src, "private fun setEnabledDeep"),
        )
    }

    /**
     * 音色选择弹窗的序号 ↔ 映射值必须走 `TapSound`（`BUG.md` 附录 B `B-262` 同批）。
     *
     * 这两处映射原先各写一份字面量（`if (current == NONE) SOUND_COUNT else current` 与
     * `if (which == SOUND_COUNT) NONE else which`）：写错一个 `+1` 不会崩 —— 弹窗把「不播放」
     * 标到别的音上，或在末位选中时把 `SOUND_COUNT` 当音效索引写进映射（下游 `getOrElse` 兜底
     * ⇒ 静默无声）。收进 `TapSound.pickerIndex` / `pickerChoice` 之后由单测覆盖往返。
     *
     * 反向钉必须照**原表达式**写（`if (current == TapSound.NONE)`）：钉一个源码里从未出现过的
     * 串（`if (current == TapSound.SOUND_COUNT)`）是恒真的，等于没钉。
     */
    @Test
    fun `音色选择弹窗的序号映射必须走 TapSound`() {
        val src = codeOf("TapSoundActivity.kt")
        assertTrue("checked 必须走 TapSound.pickerIndex", "TapSound.pickerIndex(current)" in src)
        assertTrue("选中的序号必须走 TapSound.pickerChoice", "TapSound.pickerChoice(which)" in src)
        assertFalse("不许再自己把映射值翻成列表序号", "if (current == TapSound.NONE)" in src)
        assertFalse("不许再自己把列表序号翻成映射值", "if (which == TapSound.SOUND_COUNT)" in src)
    }

    /**
     * 默认映射的四条回落出口都必须过 `TapSound.clampIndex`（`BUG.md` 附录 B `B-262` 的修 1）。
     *
     * 为什么只能钉形态：`SOUND_COUNT = 16` 时 `DEFAULT_MAP` 的 9 本来就合法，
     * `clampIndex(9) == 9`，行为断言分辨不出「出口走归一」与「出口直接用 DEFAULT_MAP」。
     * 实测（2026-10-28）：把 `defaults()` 改回 `DEFAULT_MAP.copyOf()`、`v >= SOUND_COUNT` 改回
     * `DEFAULT_MAP[i].coerceIn(...)`，`TapSoundTest` 38 例**全绿** —— 整条修复被撤销也没人知道。
     * 而把 `SOUND_COUNT` 临时改成 8（不改默认映射）实跑旧代码：`parseMap("")` 得
     * `9,9,9,9,9,9`、`parseMap("1,2")` 得 `1,2,9,9,9,9`，都越界；只有「单项上界外」那一支
     * 得 7（`formatMap` 也钳）⇒ 读侧静音、写侧换成末位音。故此处按「不许再自算」的口径钉住。
     *
     * 前四条只钉「某个字面量没出现」，两条等价撤销仍全绿（2026-10-28 实跑）：把 `normalizedDefault`
     * 里那次 `clampIndex` 摘掉、把 `defaults()` 换成 `DEFAULT_MAP.clone()` —— 管线还在、归一没了。
     * 故补后两条钉住「归一这一步还在」与本表的拷贝来源。
     */
    @Test
    fun `默认映射的回落出口必须过归一`() {
        val src = codeOf("TapSound.kt")
        assertFalse(
            "回落不许用未归一的 DEFAULT_MAP.copyOf()（改小 SOUND_COUNT 时会漏出越界值）",
            "DEFAULT_MAP.copyOf()" in src,
        )
        assertFalse(
            "单项上界外也要读归一后的默认值，不许只在这一支补一次 coerceIn",
            "DEFAULT_MAP[i].coerceIn" in src,
        )
        val parse = blockAfter(src, "fun parseMap")
        assertEquals(
            "parseMap 的三条回落出口（串为空 / 错位整体回落 / 缺字段起点）必须都走 defaults()",
            3,
            Regex("""defaults\(\)""").findAll(parse).count(),
        )
        assertTrue(
            "formatMap 的缺省项也要过归一（读侧与落盘串同源）",
            "clampIndex(map.getOrElse(i) { normalizedDefault[i] })" in blockAfter(src, "fun formatMap"),
        )
        // 前四条钉的是「某个字面量没出现」，换个写法就能达成同样的撤销 ⇒ 再钉两个环节
        assertTrue(
            "归一化的默认副本必须真的过 clampIndex（抽掉它等于整条修复作废，而 16 音效下行为用例看不出来）",
            "clampIndex(" in blockAfter(src, "private val normalizedDefault"),
        )
        assertTrue(
            "默认副本每次必须是归一表的拷贝（`DEFAULT_MAP.clone()` 这类绕开名字的写法同样算撤销）",
            "normalizedDefault.copyOf()" in src,
        )
    }

    /**
     * 音效强度滑杆的三段算式必须首尾同源（`BUG.md` 附录 B `B-262` 顺带收口）。
     *
     * 闭合关系本身由 `TapSoundTest.音量滑杆的进度与音量互为逆` 钉在纯层；但**页面有没有按那个
     * 闭合关系算**只能看源码 —— 页面跑不了 JVM 单测。三种写法都不会崩、只会算错：`max` 少一个
     * `- VOLUME_MIN`（量程整体错一格）、`progress` 忘了减左端偏移（滑杆起点不是 0%）、写盘忘了
     * 加回偏移（盘上恒比显示低一格）。
     */
    @Test
    fun `音效强度滑杆的三段算式必须首尾同源`() {
        val src = codeOf("TapSoundActivity.kt")
        assertTrue(
            "右端量程必须是 VOLUME_MAX - VOLUME_MIN",
            "max = TapSound.VOLUME_MAX - TapSound.VOLUME_MIN" in src,
        )
        assertTrue(
            "进度必须减掉左端偏移（否则滑杆整体偏一格）",
            "progress = TapSound.clampVolume(prefs.tapSoundVolume) - TapSound.VOLUME_MIN" in src,
        )
        assertTrue(
            "写盘必须把偏移加回来",
            "TapSound.clampVolume(TapSound.VOLUME_MIN + progress)" in src,
        )
    }

    /**
     * 换输入框时功能面板必须**复用**既有按钮（第 65 轮实测的省时点）。
     *
     * 无候选态每次聚焦都会走到 `renderFunctionPanel`，而它原先无条件清容器 + 重建 7 个按钮：
     * 真机分段计时 6.1~13.9ms/次，占换框 `configure` 的 60% 以上、占整段 `onStartInputView`
     * 的 40% 以上（n=10，MuMu / API 32）。修复后同一屏 `configure` 9.84ms → 2.9~3.6ms。
     *
     * 守卫的意义：省时的前提是「签名同源」—— 任何一个影响按钮的输入漏进签名，界面就会停在
     * 旧键集 / 旧配色（比慢更糟）。所以签名清单在这里逐个钉住。
     */
    @Test
    fun `换输入框时功能面板未变必须复用既有按钮`() {
        val src = codeOf("PinyinKeyboardView.kt")
        val panel = blockAfter(src, "private fun renderFunctionPanel()")
        assertTrue(
            "面板内容未变时必须早退复用（否则每次换框白重建 7 个按钮）",
            "if (candidatePanelReusable()) {" in panel,
        )
        assertTrue(
            "复用早退必须在清容器之前",
            panel.indexOf("candidatePanelReusable()") in 1 until panel.indexOf("clearCandidateList()"),
        )
        val sig = blockAfter(src, "private fun candidatePanelSignature()")
        for ((needle, why) in listOf(
            "actionPanelExpanded" to "动作排形态",
            "isPanelSearch()" to "搜索态（只留「退出」）",
            "clipboardActive" to "剪贴板面板（历史 ↔ 红色返回）",
            "hostImageCapable" to "图库键的有无",
            "galleryActive" to "图库键的返回文案",
            "prefs.translateEnabled" to "翻译键的有无",
            "translateInFlight" to "翻译中的键面",
            "skin.id" to "皮肤（按钮底与字色）",
            "keyFaceAlpha" to "面不透明度",
            "reuseBlockMinPx()" to "复用块最小高",
        )) {
            assertTrue("功能面板签名必须含${why}（漏掉=界面停在旧键集/旧配色）：找不到 `$needle`", needle in sig)
        }
        val bar = blockAfter(src, "private fun refreshCandidateBar()")
        assertTrue(
            "空态分支必须按签名在「只复位状态」与「整清容器」之间二选一",
            "if (candidatePanelReusable()) resetCandidateForm() else clearCandidateList()" in bar,
        )
        val reset = blockAfter(src, "private fun resetCandidateForm()")
        assertTrue(
            "复位状态仍要复位渲染指纹（L-809 的形态就是「清了容器却留着指纹」）",
            "resetCandidateRender()" in reset,
        )
        assertFalse(
            "复位状态**不得**动容器（动了就等于重建，省时失效）",
            "recycleCandidateViews()" in reset,
        )
        // 「容器非空」不能当「按钮还在」的判据：候选词 / 符号分组标签 / 密码数字条 / 加载提示
        // 都往同一个容器里塞子视图，被它们占用过之后凭 childCount 会误判成「按钮仍在」⇒
        // 面板不复建、候选栏停在上一形态（符号层切回字母层必撞，第 65 轮评审实测路径）。
        val reusable = TestSources.window(
            src,
            "private fun candidatePanelReusable(): Boolean =",
            "private fun renderFunctionPanel()",
        )
        assertTrue(
            "复用判据必须看「容器里装的是不是那排按钮」（candidatePanelLive）",
            "candidatePanelLive" in reusable,
        )
        assertFalse(
            "复用判据不得只看子视图个数（被其它形态占用过就误判）",
            "viewCandidateList.childCount" in reusable,
        )
        assertTrue("面板建完必须置真", "candidatePanelLive = true" in panel)
        assertTrue(
            "容器被清空必须置假（recycleCandidateViews 是唯一出口）",
            "candidatePanelLive = false" in blockAfter(src, "private fun recycleCandidateViews()"),
        )
    }

    /**
     * 键皮肤未变时必须整段跳过（第 65 轮实测 1.2~2.6ms/次）。
     *
     * `configure` 每次聚焦都会重套皮肤，而逐键视觉只是「皮肤 × 面不透明度」的纯函数 ——
     * 两者都没变就不该再走 26 键 + 分号键 + 10 个数字键的 `visualFor`（每键都新建对象）。
     * 判据必须**同时**含两项：只比其一时「仅换肤」或「仅改透明度」会早退，键面停在旧色。
     */
    @Test
    fun `键皮肤与面透明度都没变时必须整段跳过`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "private fun applySkinToKeys()")
        assertTrue(
            "判据必须同时含皮肤与面不透明度：找不到联合指纹",
            "val token = skin.id + \"|\" + keyFaceAlpha" in body,
        )
        assertTrue(
            "指纹相同必须早退（在第一个键下发之前）",
            body.indexOf("if (token == skinKeysBuiltToken) return") in 1 until body.indexOf("val ordered"),
        )
        assertTrue(
            "早退后要记下本次指纹，否则永远重算",
            "skinKeysBuiltToken = token" in body,
        )
    }

    /**
     * 首弹不得把同一套外观再套一遍（第 65 轮实测：一次 `configure` 8~22ms）。
     *
     * 首弹的顺序是「建视图（内部已 configure 一遍）→ onStartInputView」，后者原先无条件再
     * configure 一次 —— 同一棵刚建好的树、同一套参数，整树重套白付一遍。
     * 只允许在**方案相同**时跳过：设置页改过双拼方案必须照旧重套（否则键面提示会停在旧方案）。
     * 凭据一次有效：会话结束 / 窗口隐藏都要清，避免把一次真实的会话边界 configure 也吞掉。
     */
    @Test
    fun `首弹不得把同一套外观再套一遍`() {
        val ime = codeOf("JinnIme.kt")
        val create = blockAfter(ime, "override fun onCreateInputView()")
        assertTrue(
            "建视图那次 configure 用的方案必须只读一次并记下来（与配置同源）",
            "val createScheme = prefs.effectiveShuangpinScheme" in create,
        )
        assertTrue(
            "建视图里的 configure 必须用那个同源的方案",
            "scheme = createScheme," in create,
        )
        assertTrue(
            "建视图末尾必须记下「已按此方案套过」的凭据",
            "createConfiguredScheme = createScheme" in create,
        )
        val start = blockAfter(ime, "override fun onStartInputView(")
        assertTrue(
            "跳过判据必须比方案（不是无条件跳过）",
            "if (createConfiguredScheme == currentScheme && pristine) {" in start,
        )
        assertTrue(
            "跳过还必须要求视图会话态为空（否则把上一个框的方向 / 剪贴板面板带进来，与 L-110 同型）",
            "pinyinKeyboard?.isSessionPristine() ?: false" in start,
        )
        assertTrue(
            "方案不同必须照旧整树重套",
            "pinyinKeyboard?.configure(" in start,
        )
        assertTrue(
            "凭据一次有效：跳过分支要清掉它",
            start.indexOf("createConfiguredScheme = null") in 1 until start.indexOf("} else {"),
        )
        for ((marker, why) in listOf(
            "override fun onFinishInputView(" to "会话结束",
            "override fun onWindowHidden()" to "窗口隐藏",
        )) {
            assertTrue(
                "${why}必须清掉「建视图时已套过」的凭据（否则下一次真实会话边界会被吞掉）",
                "createConfiguredScheme = null" in blockAfter(ime, marker),
            )
        }
        // 会话态判据必须与 configure 的复位清单**逐项对应**（BUG.md L-1531：图库面板与动作排
        // 曾同时漏在 isSessionPristine 里 —— 跳过 configure 就等于把它们带进下一个输入框）。
        // 闭集判据：configure 里每一条 `if (X) hideY()` / `exitY()` 都要求 pristine 里有 `!X`，
        // 这样以后往 configure 加一样复位而忘了 pristine，这条会直接点名那一项。
        val kb = codeOf("PinyinKeyboardView.kt")
        val cfg = blockAfter(kb, "fun configure(scheme: ShuangpinScheme, english: Boolean)")
        val guarded = Regex("""if \(([A-Za-z]\w*)\) (?:hide|exit)\w*\(""")
            .findAll(cfg).map { it.groupValues[1] }.toSet()
        assertTrue("configure 的复位清单必须能解析出来（解析不到 = 本守卫空转）", guarded.size >= 3)
        val pristine = TestSources.window(
            kb,
            "internal fun isSessionPristine()",
            "fun capsModeForReplay",
        )
        for (g in guarded) {
            assertTrue(
                "configure 里 `if ($g)` 复位了会话态，isSessionPristine 必须同时含 `!$g`" +
                    "（漏了就是 L-1531 同型：跳过 configure 把这项带进下一个输入框）",
                "!$g" in pristine,
            )
        }
        assertTrue("残留拼音也属会话态", "composing.isEmpty()" in pristine)
        assertTrue("残留预测也属会话态", "lastPredictions.isEmpty()" in pristine)
        assertTrue(
            "动作排也属会话临时态（顶掉候选栏的功能按钮，一点就作用于新框的正文）",
            "resetActionPanel()" in cfg,
        )
        assertTrue("动作排收起后 pristine 必须跟着判它", "!actionPanelExpanded" in pristine)
    }

    /**
     * 搜索未结束时不得报「未找到匹配内容」。
     *
     * 分块搜索每块 [SEARCH_WINDOW_ITEMS] 行、首块回来就发布一次保首屏；命中落在更旧的行里时，
     * 首块发布的是空集。若空态此时就说「未找到」，用户先看到一句否定结论、一两秒后结果又冒出来
     * （作者报的现象）。要求：扫描中显示「搜索中」；只有扫描结束才允许落到「未找到」。
     *
     * 四处必须同时在位，缺任一处都会退化：
     *  ① 文案常量；② updateEmpty 里排在「未找到」之前；
     *  ③ 中途发布标 scanning=true；④ 收尾（含结果没变的 else 分支）退回 false。
     */
    @Test
    fun 搜索未结束不得报未找到() {
        val src = codeOf("SearchPanelView.kt")
        assertTrue(
            "必须有「搜索中」这一档文案",
            Regex("""const val TEXT_EMPTY_SEARCHING = """).containsMatchIn(src),
        )
        val empty = blockAfter(src, "private fun updateEmpty()")
        val idx = listOf(
            "TEXT_EMPTY_IDLE", "TEXT_EMPTY_SEARCHING", "TEXT_EMPTY_NO_MATCH_SCAN", "TEXT_EMPTY_NO_MATCH",
        ).map { empty.indexOf(it) }
        assertTrue("空态四档文案必须都在 updateEmpty 里", idx.all { it >= 0 })
        assertTrue("「搜索中」必须排在「未找到」之前，否则先报未找到", idx[1] < idx[3])
        assertTrue(
            "输入一变就要进「搜索中」（否则 debounce 期间还挂着上一轮的空态结论）",
            "scanning = !s.isNullOrBlank()" in src,
        )
        assertTrue(
            "中途发布必须标 scanning = true",
            Regex("""publish\([^)]*(?:\)[^)]*)*?scanning = true""").containsMatchIn(src),
        )
        // 收尾状态机：结果变了走 publish(scanning = false)，没变走 else 分支单独退标记。
        // 用**窗口取整段（含 else）**判，不能按 `scanning = false` 的计数判：文件里有六处
        // （字段初值 / q 为空 / onShown / onHidden / stopBackgroundWork / 收尾），按计数判会
        // 放过「删掉 else 分支」—— 第 68 轮的 finally 洞就是同一种假守卫。
        val tail = TestSources.window(src, "if (publishedCount != matches.size", "Diagnostics.v(TAG, \"搜索完成")
        assertTrue("结果没变时也必须退掉「搜索中」（else 分支）", "} else {" in tail)
        assertTrue("else 分支里要把 scanning 置回 false", "scanning = false" in tail)
    }
}
