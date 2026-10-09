package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 共用对拍助手 [TestSources] 的守卫（`BUG.md` L-116）。
 *
 * 两件事：① 剥注释的判据本身正确（行注释 / 行尾注释 / 块注释 / KDoc / XML 注释都剥，
 * **引号里的 `//` 不许误切**、行数不许变）；② 全仓不再自建「按整行剥注释」的实现
 * （旧的四份副本正是 L-116 的机制残留：行尾注释仍能满足一条钉）。
 *
 * 夹具一律用**普通字符串**拼行，不放 `"""` —— 本文件要断言的正是「源码里的引号」，
 * 用原始字符串会把「测试夹具的引号」和「被断言的引号」搅在一起（第一版就编译不过）。
 */
class TestSourcesTest {

    private fun lines(vararg l: String): String = l.joinToString("\n")

    /** 源码里写的是三个双引号包起来的原始字符串（Kotlin 原文），夹具里按字面量拼出来 */
    private val tripleQuote = "\"\"\""

    @Test
    fun 行注释与行尾注释都要剥掉() {
        val src = lines(
            "val a = 1 // hideClipboardPanel()",
            "// hideSearchPanel()",
            "val b = 2",
        )
        val code = TestSources.codeOf(src)
        assertFalse("行尾注释没剥（L-116 建议 ④ 的正是这一处）", "hideClipboardPanel()" in code)
        assertFalse("整行注释没剥", "hideSearchPanel()" in code)
        assertTrue("代码行被误伤", "val a = 1" in code && "val b = 2" in code)
        assertEquals("行数必须不变（相对顺序 / 行号判据依赖它）", src.lines().size, code.lines().size)
    }

    @Test
    fun 块注释与KDoc整段剥掉但保留换行() {
        val src = lines(
            "/* stopBackgroundWork() 说明用 */",
            "val x = 1",
            "/**",
            " * refreshToken++",
            " */",
            "val y = 2",
        )
        val code = TestSources.codeOf(src)
        assertFalse("块注释没剥", "stopBackgroundWork()" in code)
        assertFalse("KDoc 没剥", "refreshToken++" in code)
        assertTrue("代码行被误伤", "val x = 1" in code && "val y = 2" in code)
        assertEquals("行数必须不变", src.lines().size, code.lines().size)
    }

    @Test
    fun 引号里的双斜杠不许误切() {
        // 项目源码里到处是 URL 与正则；旧实现「只丢整行注释」就是为了躲开这一条（代价是行尾注释留了口子）
        val src = lines(
            "const val URL = \"https://github.com/yezijinn/Jinn_AndroidInputMethod\" // 注释里的 hideClipboardPanel()",
            "val re = Regex($tripleQuote" + "https://example.com//path" + "$tripleQuote)",
            "val c = '/'",
        )
        val code = TestSources.codeOf(src)
        assertTrue("普通字符串里的 URL 被误切", "https://github.com/yezijinn/Jinn_AndroidInputMethod" in code)
        assertTrue("原始字符串里的 // 被误切", "https://example.com//path" in code)
        assertFalse("行尾注释仍留着", "hideClipboardPanel()" in code)
        assertTrue("字符字面量被误伤", "'/'" in code)
    }

    @Test
    fun 原始字符串以引号开头结尾时不许吞掉后面的代码() {
        // 真实写法（Gitee 标签解析）：原始字符串的内容以引号开头、以引号结尾 ⇒ 源码里引号是**连排 4 个**。
        // 定界符只找「第一个 `"""`」会提前收尾，把后面的代码整段当成字符串吞掉（实测：被吞区里的
        // 注释不再剥、`NetworkPolicyTest.更新检查只接受https地址` 当场变红）。
        val src = lines(
            "val nameRe = Regex(" + tripleQuote + "\"name\"\\s*:\\s*\"([^\"]+)\"" + tripleQuote + ")",
            "// 注释里的 hideClipboardPanel()",
            "val after = 1",
        )
        val code = TestSources.codeOf(src)
        assertTrue("原始字符串本体被误切", "val nameRe = Regex(" in code && "([^\"]+)" in code)
        assertFalse("后续行没剥注释（说明状态被吞了）", "hideClipboardPanel()" in code)
        assertTrue("后续代码被吞", "val after = 1" in code)
    }

    @Test
    fun 嵌套块注释必须按嵌套剥() {
        // Kotlin 的块注释**可以嵌套**（BUG.md L-133）：只认第一个 `*/` 会让内层结束符之后的
        // 注释文本落进「代码」⇒ 正向钉又能被注释满足（L-116 同型）。夹具把「实现名」写在
        // 内层注释里，剥完必须一点都不剩。
        val src = lines(
            "/* 外层说明",
            "   /* 内层示例：stopBackgroundWork() */",
            "   仍然在注释里：refreshToken++",
            "*/",
            "val after = 1",
        )
        val code = TestSources.codeOf(src)
        assertFalse("内层 `*/` 之后的注释文本漏进了代码（层数没算）", "refreshToken++" in code)
        assertFalse("内层注释本身漏进了代码", "stopBackgroundWork()" in code)
        assertTrue("代码行被误伤", "val after = 1" in code)
        assertEquals("行数必须不变", src.lines().size, code.lines().size)
    }

    @Test
    fun Python的f字符串字段要按代码处理() {
        // PEP 701（Python 3.12+）：字段内可以再出现**与外围同名**的引号（BUG.md L-138，夹具实证）。
        // 只按普通串扫 ⇒ `#key"]}` 起的内容被当注释删掉（正向钉误红、反向钉误绿 —— 静默方向）。
        val src = lines(
            "x = f\"{d[\"#key\"]}\"  # 尾注 os.replace(tmp, path)",
            "y = f'{d[\"#other\"]}'  # 尾注",
            "z = f\"{{not_a_field}} {value:{width}}\"",
            "os.replace(tmp, path)",
        )
        val code = TestSources.pyCode(src)
        assertTrue("字段内的内层字符串被当注释删了（PEP 701 形态）", "#key\"]}\"" in code)
        assertTrue("单引号 f-string 里的内层串被误删", "#other\"]" in code)
        assertFalse("字段之后的真注释没剥", "尾注 os.replace" in code)
        assertTrue("字面花括号被误当字段", "{{not_a_field}}" in code)
        assertTrue("格式串里的嵌套花括号被误判", "{value:{width}}" in code)
        assertTrue("真实调用被误伤", "os.replace(tmp, path)" in code)
        assertEquals("行数必须不变", src.lines().size, code.lines().size)
    }

    @Test
    fun XML注释同样剥掉() {
        val xml = lines(
            "<manifest>",
            "  <!-- 曾经用过 android:usesCleartextTraffic=\"true\" -->",
            "  <application android:networkSecurityConfig=\"@xml/network_security_config\" />",
            "</manifest>",
        )
        val code = TestSources.codeOf(xml)
        assertFalse("XML 注释没剥（manifest 的反向钉会被说明性注释骗过）", "usesCleartextTraffic" in code)
        assertTrue("XML 实体被误伤", "network_security_config" in code)
    }

    @Test
    fun Python注释要剥掉而行数不变() {
        // 工具链守卫对拍的是 `tools/**.py`（BUG.md L-128）：Python 的 `#` 必须剥，
        // 而**字符串里**的 `#`（路径 / 正则 / 格式串）必须原样留着。
        val src = lines(
            "#!/usr/bin/env python",
            "import os   # 行尾注释 os.replace(tmp, path)",
            "# 整行注释 write_bytes_atomically(OUT_XZ, out)",
            "S = \"# 不是注释：write_bytes_atomically 只在字符串里\"",
            "T = '" + "# 也不是注释" + "'",
            "D = " + tripleQuote + "三引号里的 os.replace( 与 # 都不算注释" + tripleQuote,
            "os.replace(tmp, path)",
        )
        val code = TestSources.pyCode(src)
        assertFalse("整行 `#` 注释没剥", "整行注释 write_bytes_atomically" in code)
        assertFalse("行尾 `#` 注释没剥", "行尾注释 os.replace" in code)
        assertFalse("shebang 没剥", "usr/bin/env" in code)
        assertTrue("双引号串里的 `#` 被误剥", "# 不是注释：write_bytes_atomically 只在字符串里" in code)
        assertTrue("单引号串里的 `#` 被误剥", "# 也不是注释" in code)
        assertTrue("三引号串内容被误伤", "三引号里的 os.replace( 与 # 都不算注释" in code)
        assertTrue("真实调用被误伤", "os.replace(tmp, path)" in code)
        assertEquals("行数必须不变（行号判据依赖它）", src.lines().size, code.lines().size)
        // 反例（L-128 的正题）：只把实现写在注释里，不得让「必须原子落盘」的钉成立
        assertFalse(
            "注释里的 os.replace 不得满足字面量钉",
            "os.replace(" in TestSources.pyCode(lines("# os.replace(tmp, path)", "run(tmp)")),
        )
    }

    @Test
    fun 真实源码剥完仍留着实现行() {
        val code = TestSources.codeSource("DictManagerActivity.kt")
        assertTrue("剥注释后不该丢掉实现（codeOf 把代码也吃了？）", "text = TEXT_LEGACY_LOAD," in code)
        assertFalse("剥注释后不该留下注释里的说明", "不能复用 dict_startup_cost_line1" in code)
    }

    /**
     * 元守卫：全仓不得再自建「按整行剥注释」实现。
     *
     * 旧状态是**四份**副本（`ClipboardKeysetPagingTest` / `PanelRebuildWorkTest` /
     * `RecentFixesRegressionTest` / `ThemeDeferralOverlayTest`），各自只丢整行注释 ⇒
     * 行尾注释仍能满足一条钉（L-116 建议 ④），且修一处不会顺带修好另三处。
     * 新写守卫时若需要剥注释，走 [TestSources.codeOf] / [TestSources.codeSource]。
     */
    @Test
    fun 不得再自建按整行剥注释的实现() {
        val dir = testDir()
        val offenders = (dir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray())
            .filterNot { it.name == "TestSources.kt" || it.name == "TestSourcesTest.kt" }
            .filter { f ->
                val t = f.readText()
                t.contains("startsWith(\"//\")") || t.contains("startsWith(\"*\")")
            }
            .map { it.name }
            .sorted()
        assertTrue(
            "这些测试文件自建了剥注释实现（改用 TestSources.codeOf / codeSource）：$offenders",
            offenders.isEmpty(),
        )
    }

    /** 源码对拍守卫必须接到共用助手（防「新写一处又自建一份」）。 */
    @Test
    fun 源码对拍守卫必须走共用助手() {
        val dir = testDir()
        val mustDelegate = listOf(
            "OptionalDictJunkTest.kt", "PanelRebuildWorkTest.kt", "RecentFixesRegressionTest.kt",
            "RuntimeStateSyncTest.kt", "MirroredSettingsPagesTest.kt", "RareCharsFilterTest.kt",
            "NetworkPolicyTest.kt", "ClipboardKeysetPagingTest.kt", "ThemeDeferralOverlayTest.kt",
            // 2026-09-29 元守卫筛出来的第二批（原以为只有四份副本）：
            "ClipboardClassifierExtractTest.kt", "DynamicSymbolsTest.kt", "PanelTimesTest.kt",
            // 第三批（L-128 审计轮）：对拍的是 `tools/**.py`，必须走 pyCode / rawSource
            "BuildToolGatesTest.kt",
            // 第四批（L-130 / L-131）：更新检查与设置页的源码钉
            "UpdateCheckerTest.kt",
            // 第五批（L-963）：备份覆盖面那几条钉原先自建按行剥注释，元守卫的形态集合漏了它
            "PrefsBackupCoverageTest.kt",
        )
        val lazy = mustDelegate.filterNot { name -> File(dir, name).readText().contains("TestSources.") }
        assertTrue("这些文件的源码装载/剥注释还没接到 TestSources（见 BUG.md L-116）：$lazy", lazy.isEmpty())
        // 只查「文件里提到过助手」不够（BUG.md L-1149）：提一次、另一处照旧裸读也算过。
        // 逐个检查读**生产源码**的调用点 —— 路径落在 `src/main/java` 的 `File(...)` 后跟 `readText()`
        // 即算自建；读本测试文件自身的对拍（元守卫要审自己的写法）不算。
        val offenders = mutableListOf<String>()
        for (name in mustDelegate) {
            val lines = File(dir, name).readText().split('\n')
            for ((i, line) in lines.withIndex()) {
                if (!line.contains("File(") || !line.contains("src/main/java")) continue
                if (line.contains(name)) continue          // 读自己：例外
                val near = lines.subList(i, minOf(lines.size, i + 3)).joinToString(" ")
                if (near.contains("readText(")) offenders += "$name:${i + 1}"
            }
        }
        assertTrue(
            "这些位置仍在用裸 readText 读生产源码（改用 TestSources.codeSource / rawSource）：$offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * 元守卫：源码对拍不得再用**固定字符窗**（BUG.md L-1145）。
     *
     * 三种禁止形态：`substringAfter(A).substringBefore(B)`、`substringAfter(A).take(N)`、
     * `substringBefore("\n    }")`（按缩进猜方法体收尾）。它们共有的毛病是**锚点缺失时静默放宽**：
     * 缺 A 返回整串、缺 B 返回 A 之后的全部剩余，按缩进猜的收尾在缩进一变就取到相邻函数或整段尾巴 ——
     * 判据仍在绿，而被钉的实现可能早已搬走（这正是 L-1145 的原型）。
     *
     * 一律改用带断言的助手：方法体走 [TestSources.blockAfter]（花括号配对）、元素 / 注释 / 段落窗口走
     * [TestSources.window]（两端锚点都断言存在）、整行走 [TestSources.lineOf]。
     * 判据在**剥注释后的代码**上做：注释里写「别用 substringAfter(A).substringBefore(B)」这类说明不算违规。
     *
     * ⚠ 只查**同一条语句链**（允许跨一行）：跨多行去撞上后面的无关语句会造成误报。
     */
    @Test
    fun 固定字符窗必须改用锚点助手() {
        val chain = Regex("""substringAfter\([^\n;]*\n?[^\n;]*?\.\s*(substringBefore|take)\(""")
        val braceWindow = Regex("""substringBefore\("\\n    \}"""")
        val offenders = (testDir().listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray())
            .filterNot { it.name == "TestSources.kt" || it.name == "TestSourcesTest.kt" }
            .flatMap { f ->
                val code = TestSources.codeOf(f.readText())
                (chain.findAll(code) + braceWindow.findAll(code)).map { m ->
                    "${f.name}:${code.substring(0, m.range.first).count { it == '\n' } + 1}"
                }
            }
            .sorted()
        assertTrue(
            "这些位置还在用固定字符窗（共 ${offenders.size} 处）—— 改用 TestSources.blockAfter / window / lineOf" +
                "（BUG.md L-1145）：${offenders.take(60)}",
            offenders.isEmpty(),
        )
    }

    /**
     * 元守卫（BUG.md L-1151）：用例名声明了**顺序或频率语义**时，判据必须落在「位置或范围」上。
     *
     * 起因（L-109）：`contains("名字")` 式判据在「调换顺序」「挪进死分支」两种语义回退下依然通过。
     * 原守卫只扫 `RecentFixesRegressionTest` 自己一个文件，且方法体里出现任意一个 `assertFalse(` 即合规 ——
     * 换到别的文件、或把用例名里的「先」字去掉，都能绕过（L-1151 的两条判据）。
     *
     * 现行口径（扫全量测试文件 + 逐个断言判据形态）：
     *  - 位置比较：`assertBefore(` / `assertStatementLine(` / 两个 `indexOf(` 的大小比较；
     *  - 范围限定：`blockAfter(` / `TestSources.window(` / `TestSources.lineOf(`（三种都断言锚点存在，
     *    见 L-1145）。
     * **`assertFalse(` 单独不算**：要「某个模式不得出现」这种**否定式判据**，必须把模式写进 `contains(...)`
     * （`assertFalse("…" in src)` / `assertFalse(src.contains("…"))`）—— 光有一个 `assertFalse(flag)`
     * 不能充当顺序判据（旧版正是被这一点绕过的）。
     *
     * 判据在**剥注释后的代码**上做（注释里写到这些形态不算实现），块按 `@Test` 切分。
     *
     * **只管读源码的用例**（含 `codeOf(` / `codeSource(` / `rawSource(` / `pyCode(`）：假绿口子只在
     * 「对拍源码文本」时出现（名字落在注释里、实现搬走）。纯行为用例（跑两次比结果、验候选表顺序、
     * 验文件删除顺序）的判据就是观察到的行为，没有这条问题，不在此处要求。
     */
    @Test
    fun 顺序语义用例必须带位置或范围判据() {
        val scopeForms = listOf(
            "assertBefore(", "assertStatementLine(",
            "blockAfter(", "TestSources.window(", "TestSources.lineOf(",
        )
        val sourceLoaders = listOf("codeOf(", "codeSource(", "rawSource(", "rawSourceOfShortName(", "pyCode(")
        // 自证（BUG.md L-110）：判据必须在**剥注释后的文本**上切块 —— 否则注释里写一句
        // `TestSources.blockAfter(` 就能满足「范围限定」。这条断言把「别把 codeOf 换成 readText」钉住。
        val selfText = listOf(
            File("src/test/java/com/jinn/inputmethod/TestSourcesTest.kt"),
            File("app/src/test/java/com/jinn/inputmethod/TestSourcesTest.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("找不到本文件")
        assertTrue(
            "本守卫必须在剥注释后的文本上切块（BUG.md L-110）",
            "TestSources.codeOf(f.readText())" in selfText,
        )
        // 位置比较：块里至少两处 indexOf(，且有一行在做大小比较（`indexOf(a)` 也可能先落到变量再比）
        val hasIndexCompare = { b: String ->
            Regex("""indexOf\(""").findAll(b).count() >= 2 && Regex("""(?m)^[^\n]*\s<\s[^\n]*$""").containsMatchIn(b)
        }
        // 否定式判据：把「不得出现的模式」写进 contains( —— 必须是**同一个 assertFalse 调用**里的
        // contains(（用括号配对取出实参），不能靠块里别处有个 contains( 蒙过去（这正是旧版被绕过的方式）
        val hasNegativeClaim = { b: String ->
            callArgs(b, "assertFalse(").any { "contains(" in it }
        }
        val offenders = (testDir().listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray())
            .filterNot { it.name == "TestSources.kt" || it.name == "TestSourcesTest.kt" }
            .flatMap { f ->
                val code = TestSources.codeOf(f.readText())
                code.split(Regex("(?m)^\\s*@Test\\b.*$")).drop(1).mapNotNull { block ->
                    val name = Regex("fun `?([^`\\n(]+)`?\\(").find(block)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    // 「先 / 之前 / 每次」才是顺序或频率语义；单字「前」会把「当前 / 向前」也算进来（误报）
                    if (!Regex("先|之前|每次").containsMatchIn(name)) return@mapNotNull null
                    if (sourceLoaders.none { it in block }) return@mapNotNull null
                    val judged = scopeForms.any { it in block } || hasIndexCompare(block) || hasNegativeClaim(block)
                    if (judged) null else "${f.name}：$name"
                }
            }
            .sorted()
        assertTrue(
            "这些读源码的用例名声明了顺序 / 频率语义，判据里却没有位置比较 / 范围限定 / 否定式判据" +
                "（BUG.md L-109 / L-1151）（共 ${offenders.size} 处）：${offenders.take(40)}",
            offenders.isEmpty(),
        )
    }

    /** 取出 [call]（如 `assertFalse(`）每次调用的实参文本（按括号配对，跨行也算一次调用） */
    private fun callArgs(code: String, call: String): List<String> {
        val out = mutableListOf<String>()
        var i = code.indexOf(call)
        while (i >= 0) {
            val start = i + call.length
            var depth = 1
            var j = start
            while (j < code.length && depth > 0) {
                when (code[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth == 0) {
                    out += code.substring(start, j)
                    break
                }
                j++
            }
            i = code.indexOf(call, i + call.length)
        }
        return out
    }

    private fun testDir(): File =
        listOf(File("src/test/java/com/jinn/inputmethod"), File("app/src/test/java/com/jinn/inputmethod"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到测试目录（当前工作目录=${File("").absolutePath}）")
}
