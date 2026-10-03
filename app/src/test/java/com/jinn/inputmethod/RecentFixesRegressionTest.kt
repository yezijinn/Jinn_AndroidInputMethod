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
            "if (failed || cleaned < 0 || maxIdUncertain) return removed" in codeOf("CredentialTrace.kt"),
        )
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
            "saveGuard.changed(editDeeplKey)" in settings &&
                "saveGuard.changed(editAliyunKeyId)" in settings,
        )
        assertTrue("载入必须记原值（L-247）", "saveGuard.remember(field, value)" in settings)
        assertTrue("OpenAI Key 也要进不留痕目标（L-245）", "prefs.openAiApiKey," in settings)
        assertTrue("不留痕走公共入口（L-244 / L-245）", "CredentialTrace.purge(db, targets)" in settings)

        val openAiPage = codeOf("OpenAiSettingsActivity.kt")
        assertTrue("OpenAI 页同样走公共入口（L-245）", "CredentialTrace.purge(" in openAiPage)
        assertTrue("OpenAI 页的 Key 也要 guard（L-247）", "saveGuard.changed(editApiKey)" in openAiPage)

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
            "private fun putDefaulted(key: String, value: String, fallback: String)" in prefs &&
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
            "下拉闸门必须认全用户来源并复位 customEdited",
            "isUserDriven(spinnerTarget)" in onSelected && "customEdited = false" in onSelected,
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
        // 导入线程写 Prefs ↔ 设置页 onPause 的**无条件**回写是两条独立执行流：交错时界面旧值
        // 覆盖刚导入的值，用户看到「导入成功」却发现配置没变（静默退回）。四个页面都有这个面。
        for (name in listOf(
            "TranslationSettingsActivity.kt",
            "OpenAiSettingsActivity.kt",
            "TranslationSourceActivity.kt",
            "SettingsActivity.kt",
        )) {
            val body = blockAfter(codeOf(name), "override fun onPause()")
            assertTrue(
                "$name 的 onPause 必须带 ConfigBackupManager.importing 闸门",
                "ConfigBackupManager.importing" in body,
            )
        }
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
        //（2026-10-02 修复 L-474）。
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
            "fun applyCandidateRows(rows: Int): Int" in view && "functionPanel" !in view,
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
        // ⚠ 对拍必须按**非空行**计数（2026-10-02 第五轮审查的回归修正）：服务端对空行根本不返回元素
        // （百度系 `trans_result` 只给有内容的行），把空行算进 expected 会让「原文含空行」的整篇 /
        // 整行翻译**必然被拒** —— 段落之间有空行是常态 ⇒ 等于百度家在默认场景下 100% 失败。
        assertTrue(
            "对拍必须按非空行计数（空行参与计数会让含空行的整篇翻译必然被拒）",
            "text.split('\\n').count { it.isNotBlank() }" in client &&
                "translated.split('\\n').count { it.isNotBlank() }" in client,
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
            "L-513：OPENAI 的凭据判据必须要求端点 https（否则 http:// 一路放行到「已配置」）",
            "OpenAiTranslator.joinUrl(openAiBaseUrl, openAiChatPath)?.isHttps == true" in codeOf("Prefs.kt"),
        )
        assertTrue(
            "L-513：OpenAI 页保存时也要标红非 https 端点（与运行期同一份判据）",
            "editBaseUrl.error = if (" in codeOf("OpenAiSettingsActivity.kt"),
        )
        assertTrue(
            "L-514：字段合法性必须计入保存成功口径",
            "val fieldsOk = listOf(editBaseUrl, editUser, editExtraJson).all { it.error == null }" in
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
        // 冷缓存下一次翻译要在主线程做最多 8 次 TEE 解密（5~20ms/次 = 40~160ms 停顿），而只用其中一家；
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
        assertEquals(
            "会话边界取消提示：四个边界入口（onFinishInput/onStartInput/onStartInputView/onFinishInputView）" +
                "都必须传 notify=true —— 不能靠后面的入口补弹（前面那个已把 flag 吃掉）",
            4,
            Regex("cancelTranslate\\(notify = true\\)").findAll(ime).count(),
        )
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
        // 再点一次会把**刚写入的译文**再翻一遍（二次付费 + 回译污染正文）。三条一起钉：
        // 判据存在、成功后记录「已送出的原文」、有专门文案。
        assertTrue(
            "L-628 守卫缺失：同一段文本的重复点击必须被拦下（并记录已送出原文）",
            "slice.text == lastSentText" in ime &&
                "lastSentText = snapshot.sent" in ime &&
                "TEXT_TRANSLATE_REPEATED" in ime,
        )
        // L-627（资金）：失败后闸门立即重开 ⇒ 连点即连付（超时 / 断连 / 落地失败 / 取消都可能已计费）。
        assertTrue(
            "L-627 守卫缺失：失败后必须有冷却窗口（并说明该请求可能已送往服务方）",
            "lastFailAtMs = System.currentTimeMillis()" in ime &&
                "sinceFail < failCooldownMs" in ime &&
                "TEXT_TRANSLATE_FAIL_COOLDOWN" in ime,
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
        //（只加早退不加清理 ⇒ 判据在首次请求后**永久成立**，按钮彻底失效）。
        run {
            val op = codeOf("OpenAiSettingsActivity.kt")
            assertTrue(
                "L-634 守卫缺失：连点保护（fetchCall != null 早退）必须与回调里的 fetchCall = null 成对",
                "if (fetchCall != null) {" in op && "fetchCall = null" in op,
            )
        }
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
}
