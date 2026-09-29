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

    /**
     * 元守卫：用例名声明「先 / 前 / 每次」语义时，判据必须带**位置或范围**限定。
     *
     * 起因（BUG.md L-109）：`contains("名字")` 式判据在「调换顺序」「挪进死分支」两种语义回退下
     * 依然通过（本轮已实证两例）。这里把它变成机械纪律：名字里有顺序 / 频率语义的用例，正文必须出现
     * 位置比较（`indexOf(` / `substringBefore` / `assertBefore(` / `assertStatementLine(`）或范围限定
     * （`blockAfter(`）—— 否则说明它只查了「名字出现」。
     */
    @Test
    fun `顺序与频率语义的守卫必须带位置或范围判据`() {
        val self = listOf(
            File("src/test/java/com/jinn/inputmethod/RecentFixesRegressionTest.kt"),
            File("app/src/test/java/com/jinn/inputmethod/RecentFixesRegressionTest.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("找不到本文件（cwd=${File("").absolutePath}）")
        // 允许的判据形态：
        //  - 位置比较 / 行锚定 / 前缀窗口：assertBefore( / assertStatementLine( / substringBefore(
        //  - 范围限定：blockAfter(
        //  - **否定式判据**：assertFalse(contains("禁止出现的模式")) —— 「不得先删目标」这类语义
        //    用「该模式不得存在」表达比位置比较更严（首版规则漏了这条，误报了 2 例）
        //  - **两个 indexOf 的比较**：`indexOf(a) ... < ... indexOf(b)` —— 只出现一个 indexOf
        //    通常是在「取窗口」（首版把它当成位置判据 ⇒ 自测里假阴性，已收紧）
        val allowed = listOf(
            "substringBefore(", "assertBefore(", "assertStatementLine(", "blockAfter(", "assertFalse(",
        )
        val orderByIndex = Regex("""indexOf\([^\n]*\)[^\n]*<[^\n]*indexOf\(""")
        val offenders = self.split(Regex("(?m)^\\s*@Test\\s*$")).drop(1).mapNotNull { b ->
            val name = Regex("fun `?([^`\\n(]+)`?\\(").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            // 「先 / 每次 / 之前」才是顺序或频率语义；单字「前」会把「当前」也算进来（误报）
            if (!Regex("先|每次|之前").containsMatchIn(name)) return@mapNotNull null
            if (allowed.any { it in b } || orderByIndex.containsMatchIn(b)) null else name
        }
        assertTrue(
            "这些用例名声明了顺序 / 频率语义，判据里却没有位置比较或范围限定（BUG.md L-109）：$offenders",
            offenders.isEmpty(),
        )
    }

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
        // 原来只查「处理体里出现这个名字」—— 把调用挪进 `if (false)` 或别的分支时仍然绿
        val branch = body.substringAfter("if (layer == LAYER_SYMBOL)")
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
        assertTrue(
            "字母键必须把 onActivate 接回触摸同款输入函数（符号层复用同一批键，也走这里）",
            view.contains("key.onActivate = { onLetterPressed(c) }"),
        )
        assertTrue(
            "分号键必须把 onActivate 接到提取出的上屏函数",
            view.contains("keySemicolon.onActivate = { onSemicolonPressed() }"),
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
        assertTrue(
            "空态必须挂点击入口（只在这一条死路成立时才有效，见 scanStoppedEarly）",
            src.contains("setOnClickListener { if (scanStoppedEarly) continueScan() }"),
        )
        assertTrue(
            "止损态必须能从填页返回值推出（空结果 + 游标未到末尾）",
            src.contains("scanStoppedEarly = currentItems.isEmpty() && nextPageOffset < total"),
        )
        assertTrue(
            "续扫必须从既有游标开始，不能从 0 重扫（白解密又可能重复显示）",
            "fillFirstPage(total, PANEL_PAGE_ITEMS, startOffset = offset)" in src,
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
        val labelPath = code.substringAfter("passwordPad -> TEXT_PASSWORD_EXIT")
        assertTrue("退出口必须加粗", labelPath.contains("btnDigit.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)"))
        assertTrue("退出口必须用提示红（与候选栏「返回」同一令牌）",
            labelPath.contains("skinToken(skin.hintRed, R.color.kb_key_hint_red)"))
        assertTrue("退出后必须还原成常规字重（否则「数字」会一直粗着）",
            labelPath.contains("btnDigit.setTypeface(android.graphics.Typeface.DEFAULT)"))
        assertTrue("换肤路径也必须保持退出口的红色（否则换肤会把它刷回普通色）",
            code.substringAfter("private fun applySkinToTexts").contains("if (passwordPad) btnDigit.setTextColor"))
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
            code.substringAfter("btnSymbol.text = if (layer == LAYER_SYMBOL)")
                .substringBefore("} else {").contains("backLabel()"),
        )
        // 表达式函数体没有独立的花括号块，用「到下一个函数为止」切片（比 blockAfter 的锚点更贴切）
        val helper = code.substringAfter("private fun backLabel").substringBefore("private fun buildLangLabel")
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
        assertTrue("两个键的全角 / 半角映射必须成对出现在标签刷新里",
            code.contains("applyPunctuationLabel(btnComma, cn = \"，\", en = \",\")") &&
                code.contains("applyPunctuationLabel(btnPeriod, cn = \"。\", en = \".\")"))
        val helper = blockAfter(code, "private fun applyPunctuationLabel")
        for (need in listOf("gravity = android.view.Gravity.CENTER", "Typeface.DEFAULT_BOLD",
                            "includeFontPadding = false", "textSize = PUNCTUATION_TEXT_SP")) {
            assertTrue("标点样式缺一项：$need", helper.contains(need))
        }
        val size = Regex("""const val PUNCTUATION_TEXT_SP = ([0-9.]+)f""").find(code)?.groupValues?.get(1)?.toFloat()
        assertTrue("PUNCTUATION_TEXT_SP 必须声明为数字", size != null)
        // 「基础字号 + 3」的机械核对：基础来自底部功能键共用的 SettingsButton 样式（13sp）
        val styles = listOf(
            File("app/src/main/res/values/styles.xml"),
            File("src/main/res/values/styles.xml"),
        ).first { it.isFile }.readText()
        val base = styles.substringAfter("<style name=\"SettingsButton\"").substringBefore("</style>")
        val baseSp = Regex("""android:textSize">(\d+)sp""").find(base)?.groupValues?.get(1)?.toFloat()
        assertEquals("SettingsButton 的基础字号变了：标点字号必须跟着改（基础 + 3）",
            3f, size!! - (baseSp ?: 0f), 0.01f)
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
        assertTrue("DB_VERSION 必须已抬到 6（复合索引需要一条迁移分支）", "DB_VERSION = 6" in code)
        val composite = Regex("""CREATE INDEX idx_items_created ON \S+\(created_at DESC, id DESC\)""")
            .findAll(code).count()
        assertTrue("复合索引要同时出现在 onCreate 与迁移分支里（当前 $composite 处）", composite >= 2)
        assertTrue(
            "必须有 DROP + 重建的迁移分支（否则已装机库拿不到新索引）",
            "DROP INDEX IF EXISTS idx_items_created" in code,
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
    @Test
    fun `可选词库线程不得持有 Service Context`() {
        val body = blockAfter(codeOf("PinyinEngine.kt"), "fun loadOptionalAsync(")
        assertTrue("必须取应用 Context（context.applicationContext）", "context.applicationContext" in body)
        assertFalse("线程内不得再用传入的 Service context 调 load", "load(context)" in body)
        assertFalse("线程内不得再用传入的 Service context 调 loadExtensionDict", "loadExtensionDict(context)" in body)
    }
}
