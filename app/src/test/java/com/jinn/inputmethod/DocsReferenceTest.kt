package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文档与现状的对拍守卫（BUG.md L-68 / L-73）。
 *
 * `AGENTS.md`（2026-09-28 由 `工程约定.md` 改名回归标准名）是给未来的自己看的**现状说明**：
 * 一旦写的是过去的实现，读者会照着它做错判断 —— L-68 就是这样，文件里一边写着「已退场」，
 * 一边留着整节两段式加载的旧数字，还列着两个已删的测试类。
 * 文档不进编译、lint 也看不见，只能靠这种机械对拍兜住最容易腐烂的三处：
 *  - 文档提到的测试类必须真的存在（删测试时忘了改文档 ⇒ 变红）；
 *  - 「冷启动与加载顺序」一节必须写当前的单段加载，且不得留下旧机制的特征描述；
 *  - 「## 测试」一节的 `覆盖 N 类 / M 例` 必须与源码 `@Test` 数一致。
 */
class DocsReferenceTest {

    @Test
    fun AGENTS里提到的测试类都必须存在() {
        val doc = convention()
        val names = Regex("""\b([A-Za-z][A-Za-z0-9]*Test)\b""").findAll(doc)
            .map { it.groupValues[1] }
            // Gradle 任务名也会被这条正则匹配到，它不是测试类
            .filterNot { it == "testDebugUnitTest" }
            .toSet()
        assertTrue("文档里没解析出测试类（正则或文档结构变了？）", names.size >= 20)
        val missing = names.filterNot { testFile(it).isFile }
        assertTrue("AGENTS.md 提到了不存在的测试类：$missing", missing.isEmpty())
    }

    @Test
    fun 冷启动一节必须写当前的单段加载() {
        val sec = convention().substringAfter("## 冷启动与加载顺序").substringBefore("\n## ")
        assertTrue("没取到「冷启动与加载顺序」一节（标题变了？）", sec.length > 200)
        assertTrue("该节没写「单段加载」（漂回旧的两段式了？）", "单段加载" in sec)
        for (stale in listOf("两段式加载（", "高频 4 万词", "60.4 万键", "两段合计")) {
            assertTrue("该节仍留着旧描述：「$stale」", stale !in sec)
        }
    }

    /**
     * 「## 测试」一节写的 `覆盖 N 类 / M 例` 必须与源码 `@Test` 数一致（`BUG.md` L-73）。
     *
     * 这份计数最容易腐烂（今天就是 56 类 / 646 例 vs 实际 65 类 / 724 例），而它不在编译、lint
     * 与任何断言的视野里。口径固定为**源码 `@Test` 数**：报告目录 `app/build/test-results/`
     * 会因为已删测试类的残留 XML 虚高（见 L-51）。
     */
    @Test
    fun 文档计数须与源码一致() {
        val doc = convention()
        val m = Regex("""覆盖\s*\**\s*(\d+)\s*类\s*/\s*(\d+)\s*例""").find(doc)
        assertTrue("AGENTS.md 没写「覆盖 N 类 / M 例」（措辞变了？）", m != null)
        val dir = listOf(
            File("src/test/java/com/jinn/inputmethod"),
            File("app/src/test/java/com/jinn/inputmethod"),
        ).first { it.isDirectory }
        val per = (dir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray())
            .map { f -> Regex("""(?m)^\s*@Test\b""").findAll(f.readText()).count() }
        val classes = per.count { it > 0 }
        val total = per.sum()
        assertEquals("AGENTS.md 写的类数过期：文档 ${m!!.groupValues[1]} vs 源码 $classes", classes, m.groupValues[1].toInt())
        assertEquals("AGENTS.md 写的例数过期：文档 ${m.groupValues[2]} vs 源码 $total", total, m.groupValues[2].toInt())
    }

    /**
     * 台账（`BUG.md`）的计数必须与正文一致（`BUG.md` L-90）。
     *
     * 这三处计数最容易腐烂：`## 1` / `## 4` / `## 6` 的节标题、以及 front-matter 的五个字段，
     * 每轮有人增删条目就会落后（本轮开工前实测 front-matter 仍写着 42/42/74/37，正文已是 44/43/106/53）。
     * 口径（本方法即定义，改口径要一起改这里）：
     *  - `## 1` 索引行 = **该节内**的 `| L-XX |` 行数（`### 3.2` 里的遗留同形行不在其内 ——
     *    按全文件计数会把它算进来，这个坑踩过两次，见 `4. 已排除` 的 X-84）；
     *  - 详情块 = `### 3.1` 之前的 `### L-XX ·` 块数（3.2 的遗留块是 `####`，天然不算）；
     *  - `excluded_items` / `history_records` = 全文的 `### X-` / `### B-` 块数；
     *  - `id_range` 上界与「0.5 维护规则」里写的最大编号一致（**已分配**的最大编号），
     *    且不小于在册最大编号（已修条目移出后两者会不相等，见 L-101…L-104 的先例）。
     */
    @Test
    fun 台账计数须与正文一致() {
        val doc = ledger()
        val indexRows = Regex("""(?m)^\| L-\d+ \|""")
            .findAll(doc.substringAfter("## 1. 索引").substringBefore("\n## ")).count()
        // 全文数 `### L-` 块：3.2 的遗留详情是 `#### L-53`（四个井号），天然不算；
        // 而近几轮新增的条目块就落在 `### 3.1` 之后 ⇒ 按「3.1 之前」切会少数（实测只到 31）。
        val detailBlocks = Regex("""(?m)^### L-\d+ ·""").findAll(doc).count()
        val xCount = Regex("""(?m)^### X-\d+ ·""").findAll(doc).count()
        val bCount = Regex("""(?m)^### B-\d+ ·""").findAll(doc).count()
        val maxInBook = Regex("""(?m)^### L-(\d+) ·""").findAll(doc)
            .map { it.groupValues[1].toInt() }.maxOrNull() ?: 0

        fun titleNumber(start: String): Int {
            // 数字不在固定位置：`## 1. 索引（44 条在册）` 紧跟括号，而 `## 4. 已排除（复核判定不是缺陷，共 106 条）`
            // 在括号尾部 ⇒ 取该行**最后一个**数字（行首的节号不会是最后一个）。
            val line = doc.substringAfter(start, "").substringBefore("\n")
            val nums = Regex("""\d+""").findAll(line).map { it.value.toInt() }.toList()
            assertTrue("在「$start」标题里没找到数字：$line", nums.isNotEmpty())
            return nums.last()
        }
        assertEquals("`## 1. 索引` 的「N 条在册」与本节行数不一致", indexRows, titleNumber("## 1. 索引"))
        assertEquals("`## 4. 已排除` 的「共 N 条」与 X 块数不一致", xCount, titleNumber("## 4. 已排除"))
        assertEquals("`## 6. 附录 B` 的「N 块」与 B 块数不一致", bCount, titleNumber("## 6. 附录 B"))

        fun front(key: String) = Regex("""(?m)^%s: (\S+)$""".format(key))
            .find(doc)?.groupValues?.get(1)
        assertEquals("front-matter 的 active_entries 与索引行数不一致", indexRows, front("active_entries")?.toInt())
        assertEquals("front-matter 的 entries_with_detail 与详情块数不一致", detailBlocks, front("entries_with_detail")?.toInt())
        assertEquals("front-matter 的 excluded_items 与 X 块数不一致", xCount, front("excluded_items")?.toInt())
        assertEquals("front-matter 的 history_records 与 B 块数不一致", bCount, front("history_records")?.toInt())
        val allocMax = Regex("""最大编号（当前 L-(\d+)""").find(doc)?.groupValues?.get(1)?.toInt()
        assertTrue("找不到 0.5 里写的最大编号", allocMax != null)
        assertEquals("front-matter 的 id_range 上界与 0.5 写的最大编号不一致",
            allocMax, front("id_range")?.substringAfter("..L-")?.toInt())
        assertTrue("id_range 上界小于在册最大编号 $maxInBook", (allocMax ?: 0) >= maxInBook)
    }

    /** 取台账（`BUG.md`），工作目录不同时回退上一级。 */
    private fun ledger(): String =
        listOf("BUG.md", "../BUG.md").map(::File).firstOrNull { it.isFile }?.readText()
            ?: error("找不到 BUG.md（测试工作目录变了？）")

    /**
     * 取项目约定文件（标准名 `AGENTS.md`）。
     *
     * ⚠ 2026-09-28：约定文件由 `工程约定.md` 改名回归标准名 `AGENTS.md` —— 本方法**先找标准名**，
     * 找不到才回落到旧名（过渡期容错）。若两者都不在，说明工作目录变了或文件又被改名。
     */
    private fun convention(): String =
        listOf("AGENTS.md", "../AGENTS.md", "工程约定.md", "../工程约定.md")
            .map(::File)
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到 AGENTS.md（项目约定文件；测试工作目录变了或文件被改名？）")

    private fun testFile(name: String): File =
        listOf(
            File("src/test/java/com/jinn/inputmethod/$name.kt"),
            File("app/src/test/java/com/jinn/inputmethod/$name.kt"),
        ).first()
}
