package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
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
        val doc = conventionWithDetails()
        val names = Regex("""\b([A-Za-z][A-Za-z0-9]*Test)\b""").findAll(doc)
            .map { it.groupValues[1] }
            // Gradle 任务名也会被这条正则匹配到，它不是测试类
            .filterNot { it == "testDebugUnitTest" }
            .toSet()
        assertTrue("文档里没解析出测试类（正则或文档结构变了？）", names.size >= 20)
        val missing = names.filterNot { testFile(it).isFile }
        assertTrue("AGENTS.md 提到了不存在的测试类：$missing", missing.isEmpty())
        // 反向（BUG.md L-1152）：源码里有、文档没登记的测试类也要红 —— 单向检查时新增测试类
        // 可以永远不进文档，而文档正是「现状说明」的入口。口径与上面的类数一致：含 @Test 的文件。
        val dir = listOf(
            File("src/test/java/com/jinn/inputmethod"),
            File("app/src/test/java/com/jinn/inputmethod"),
        ).first { it.isDirectory }
        val onDisk = dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { f -> Regex("""(?m)^\s*@Test\b""").containsMatchIn(TestSources.codeOf(f.readText())) }
            .map { it.nameWithoutExtension }
            .toSet()
        val unlisted = onDisk.filterNot { it in names }.sorted()
        assertTrue("源码里有、文档没登记的测试类（新增测试类要同时写进约定）：$unlisted", unlisted.isEmpty())
    }

    @Test
    fun 冷启动一节必须写当前的单段加载() {
        // 2026-09-29 精简：该节外移到细节分册（工程约定 只留指针）⇒ 判据改成「在约定文件**或其明细分册**里」，
        // 同时要求 工程约定 指明去哪读（否则等于把这条现状说明藏起来）。
        val docs = conventionWithDetails()
        assertTrue("工程约定 必须指向细节分册（`details:` 或指针表）", "agents-extras" in convention())
        val sec = TestSources.window(docs, "## 冷启动与加载顺序", "\n## ")
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
        // **递归**收集（2026-09-30 审查）：原先只扫一层，测试文件一旦按包拆进子目录，
        // 「类数 / 例数」对拍与「文档提到的测试类必须存在」两条守卫会一起失效（静默变绿）。
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        // 剥注释后再数（BUG.md L-139）：原文计数会把「被注释掉的用例」也算进来 ⇒ 文档数字可稳定写错。
        // 口径：**类数 = 含 @Test 的文件数**（项目约定一文件一测试类，多类同文件会让类数偏低）
        val per = files.map { f -> Regex("""(?m)^\s*@Test\b""").findAll(TestSources.codeOf(f.readText())).count() }
        val classes = per.count { it > 0 }
        val total = per.sum()
        assertEquals("AGENTS.md 写的类数过期：文档 ${m!!.groupValues[1]} vs 源码 $classes", classes, m.groupValues[1].toInt())
        assertEquals("AGENTS.md 写的例数过期：文档 ${m.groupValues[2]} vs 源码 $total", total, m.groupValues[2].toInt())
        // 带 `@Test` 却不跑的用例会让上面两个数字虚高（且这条守卫是唯一核对）：出现即要求显式处理
        val ignored = files.sumOf { f ->
            Regex("""(?m)^\s*@Ignore\b""").findAll(TestSources.codeOf(f.readText())).count()
        }
        assertEquals("有 @Ignore 的用例（带 @Test 却不跑 ⇒ 计数虚高，BUG.md L-139）", 0, ignored)
        // front-matter 的 `numbers:` 是同一份文件里**第二处**门禁数字，原先没人对拍 ⇒ 正文与它各改一半
        // 就互相矛盾（2026-10-13 实测：正文 1413 / front-matter 1410，BUG.md L-1340）。两份都按源码对拍。
        val fm = readDoc("AGENTS.md")
            ?.let { Regex("""(?m)^numbers: .*?(\d+)\s*类\s*/\s*(\d+)\s*例""").find(it) }
            ?: error("AGENTS.md 的 front-matter `numbers:` 没写「N 类 / M 例」（措辞变了？）")
        assertEquals("AGENTS.md front-matter 的类数与正文 / 源码不一致", classes, fm.groupValues[1].toInt())
        assertEquals("AGENTS.md front-matter 的例数与正文 / 源码不一致", total, fm.groupValues[2].toInt())
    }

    /**
     * 台账的计数必须与正文一致（`BUG.md` L-90；2026-09-29 起**入口 + 六个分册**）。
     *
     * 这三处计数最容易腐烂：`## 1` / `## 4` / `## 6` 的节标题、以及 front-matter 的五个字段，
     * 每轮有人增删条目就会落后（曾有 front-matter 仍写 42/42/74/37、正文已是 44/43/106/53）。
     * 口径（本方法即定义，改口径要一起改这里）：
     *  - **入口** `BUG.md` 只放协议 / 待办 / 索引 / front-matter；详情在 `.wwlia-handoff/ledger/` 下的六个分册
     *    （`medium.md` / `low.md` / `excluded.md` / `fixed.md` / `history.md` / `verify.md`；
     *    ⚠ 注释里别写 `ledger` 后跟通配斜杠星号 —— Kotlin 块注释**可嵌套**，那个序列会被当成嵌套注释起始，
     *    编译直接报 `Unclosed comment`，这次实测踩过）；
     *  - `## 1` 索引行 = **入口的第 1 节内**的 `| L-XX |` 行数（`3.2` 里的遗留同形行不在其内 ——
     *    按全文件计数会把它算进来，这个坑踩过两次，见 `excluded.md` 的 X-84）；
     *  - 详情块 / `excluded_items` / `history_records` = **入口 + 全部分册**里的
     *    `### L-XX ·` / `### X-` / `### B-` 块数（跨文件求和，随便挪到哪个分册都算数）；
     *  - `id_range` 上界与「0.5 维护规则」里写的最大编号一致（**已分配**的最大编号），
     *    且不小于在册最大编号（已修条目移出后两者会不相等，见 L-101…L-104 的先例）。
     */
    @Test
    fun 台账计数须与正文一致() {
        val entry = ledger()            // 入口：协议 + 待办 + 索引 + front-matter
        val doc = ledgerAll()           // 入口 + 六个分册（块计数与标题查找都在全文上做）
        // 索引行在**索引分册**（`.wwlia-handoff/ledger/index.md`）里数：2026-09-29 起入口只留指针
        val indexPart = ledgerPartFiles()["index"]
            ?: error("索引分册缺失（见 BUG.md 的 0.6 详情文件地图）")
        val indexRows = Regex("""(?m)^\| L-\d+ \|""").findAll(indexPart).count()
        val detailBlocks = Regex("""(?m)^### L-\d+ ·""").findAll(doc).count()
        val xCount = Regex("""(?m)^### X-\d+ ·""").findAll(doc).count()
        val bCount = Regex("""(?m)^### B-\d+ ·""").findAll(doc).count()
        val maxInBook = Regex("""(?m)^### L-(\d+) ·""").findAll(doc)
            .map { it.groupValues[1].toInt() }.maxOrNull() ?: 0

        assertEquals("索引分册标题的「N 条在册」与行数不一致",
            indexRows, titleNumberOf(indexPart, """## 1\. 索引"""))
        // 入口（BUG.md）留的是**指针**：它写的条数也要与分册行数一致（两处都会被人改）
        assertEquals("入口索引指针里的条数与索引分册行数不一致",
            indexRows, titleNumberOf(entry, """## 1\. 索引"""))
        assertEquals("`## 4. 已排除` 的「共 N 条」与 X 块数不一致", xCount, titleNumberOf(doc, """## 4\. 已排除"""))
        assertEquals("`## 6. 附录 B` 的「N 块」与 B 块数不一致", bCount, titleNumberOf(doc, """## 6\. 附录 B"""))

        fun front(key: String) = Regex("""(?m)^%s: (\S+)$""".format(key))
            .find(entry)?.groupValues?.get(1)
        assertEquals("front-matter 的 active_entries 与索引行数不一致", indexRows, front("active_entries")?.toInt())
        assertEquals("front-matter 的 entries_with_detail 与详情块数不一致", detailBlocks, front("entries_with_detail")?.toInt())
        assertEquals("front-matter 的 excluded_items 与 X 块数不一致", xCount, front("excluded_items")?.toInt())
        assertEquals("front-matter 的 history_records 与 B 块数不一致", bCount, front("history_records")?.toInt())
        val allocMax = Regex("""最大编号（当前 L-(\d+)""").find(entry)?.groupValues?.get(1)?.toInt()
        assertTrue("找不到 0.5 里写的最大编号", allocMax != null)
        assertEquals("front-matter 的 id_range 上界与 0.5 写的最大编号不一致",
            allocMax, front("id_range")?.substringAfter("..L-")?.toInt())
        assertTrue("id_range 上界小于在册最大编号 $maxInBook", (allocMax ?: 0) >= maxInBook)
    }

    /**
     * 行首锚定地找节标题（`(?m)^…`）—— 台账 L-137。
     *
     * 为什么不能 `substringAfter("## 4. 已排除")`：台账**正文也会引用节名**（例如条目解释「块被放到了哪一节」），
     * 子串定位会命中正文那一处 ⇒ 读到说明句里的数字。实测（2026-09-29 写 L-136 时）该写法把
     * 「共 158 条」读成 **9**（那句「9 个块」的 9）；若那句里的数字恰好等于真值，还会**静默通过**。
     */
    private fun titleMatch(doc: String, escapedTitle: String): MatchResult =
        Regex("(?m)^" + escapedTitle + ".*$").find(doc)
            ?: error("台账里找不到节标题「$escapedTitle」（改名了？）")

    /** 节标题行里的**最后一个**数字（`## 4. 已排除（复核判定不是缺陷，共 158 条）` ⇒ 158）。 */
    private fun titleNumberOf(doc: String, escapedTitle: String): Int {
        val line = titleMatch(doc, escapedTitle).value
        val nums = Regex("""\d+""").findAll(line).map { it.value.toInt() }.toList()
        assertTrue("节标题里没找到数字：$line", nums.isNotEmpty())
        return nums.last()
    }

    /** 两个节标题之间的正文（两端都按 [titleMatch] 行首锚定）。 */
    private fun sectionSpan(doc: String, escapedTitle: String, escapedNext: String): String {
        val a = titleMatch(doc, escapedTitle).range
        val b = titleMatch(doc, escapedNext).range
        return doc.substring(a.last + 1, b.first)
    }

    /**
     * 节标题解析必须**行首锚定**（台账 L-137 的回归守卫）。
     *
     * 夹具刻意让正文**先**出现一次同形标题（并带一个更小的数字）——旧写法 `substringAfter` 会读到
     * 正文那一行（真实台账上就是这样把 158 读成 9 的）；锚定版必须仍取真标题。
     */
    @Test
    fun 节标题解析必须行首锚定() {
        val doc = listOf(
            "## 1. 索引（3 条在册）",
            "正文引用了一次「## 4. 已排除」并写到 9 个块",
            "## 4. 已排除（复核判定不是缺陷，共 158 条）",
            "## 6. 附录 B（2 块，逐字保留）",
        ).joinToString("\n")
        assertEquals("索引节标题", 3, titleNumberOf(doc, """## 1\. 索引"""))
        assertEquals("已排除节标题（正文先出现同形字面也不许读错）", 158, titleNumberOf(doc, """## 4\. 已排除"""))
        assertEquals("附录 B 节标题", 2, titleNumberOf(doc, """## 6\. 附录 B"""))
        val span = sectionSpan(doc, """## 1\. 索引""", """## 4\. 已排除""")
        assertTrue("区间必须从真标题**之后**开始（含正文那句、不含标题本身）",
            span.contains("正文引用了一次") && !span.contains("## 1. 索引"))
    }

    /**
     * 在册条目必须落在它**声明的分册**里（`BUG.md` 索引第 5 列；`0.5` 规则 ⑥）。
     *
     * 为什么需要它：L-136 / L-141 两次都是「块放错册」——计数对、位置错，而计数守卫按「入口 + 分册」
     * 求和，天然看不见位置。这条把「索引声明的分册」与「块实际所在的分册」对拍：
     *  - 每个索引行的条目必须在声明的那一册里有块（`### L-`，或 3.2 遗留区的 `#### L-`）；
     *  - 且不许在**别的**册里再出现（两处真相 = 下一轮必然改漏一处）。
     */
    @Test
    fun 在册条目必须落在它声明的分册() {
        val parts = ledgerPartFiles()
        val indexPart = ledgerPartFiles()["index"]
            ?: error("索引分册缺失（见 BUG.md 的 0.6 详情文件地图）")
        val rows = Regex("""(?m)^\| L-(\d+) \|([^\n]*)$""").findAll(indexPart)
            .map { m -> m.groupValues[1] to m.groupValues[2].split("|").map { it.trim() } }
            .toList()
        assertTrue("索引里没解析出条目行（列变了？）：${rows.size}", rows.size >= 70)
        val missing = mutableListOf<String>()
        val dup = mutableListOf<String>()
        for ((id, cells) in rows) {
            val part = cells.getOrNull(3) ?: ""      // cells[0]=风险 / [1]=维度 / [2]=状态 / [3]=分册
            // 编号可能补零（索引写 L-1、块标题写 `#### L-01 ·`）：用 `0*` 兼容两种写法
            val inPart = parts[part]?.let { Regex("""(?m)^#{3,4} L-0*$id ·""").containsMatchIn(it) } ?: false
            if (!inPart) missing += "L-$id（声明 $part）"
            for ((name, text) in parts) {
                if (name != part && Regex("""(?m)^### L-0*$id ·""").containsMatchIn(text)) {
                    dup += "L-$id 也在 $name"
                }
            }
        }
        assertTrue("这些条目没落在声明的分册里（改分册后要同步索引第 5 列）：$missing", missing.isEmpty())
        assertTrue("这些条目在多个分册都有块：$dup", dup.isEmpty())
    }

    /**
     * 索引行的标题必须**逐字等于**块的标题（BUG.md L-1182）。
     *
     * 索引第 6 列是块标题的镜像，此前只有行数 / 分册 / 状态三类对拍，没有一条比标题 ——
     * 2026-10-08 的机械检查因此发现 6 处早已漂移（索引行丢了反引号：`listFiles` / `trimFavorites` /
     * `BackgroundIo` / `FileProvider` / `@Volatile` / `ofMapped` 六条），而所有守卫与计数全绿。
     */
    @Test
    fun 索引行的标题必须与块标题一致() {
        val indexPart = ledgerPartFiles()["index"] ?: error("索引分册缺失")
        val titles = mutableMapOf<String, String>()
        for ((name, text) in ledgerPartFiles()) {
            if (name == "index" || name == "excluded" || name == "history" || name == "fixed" || name == "verify") continue
            Regex("""(?m)^### L-0*(\d+) · (.*)$""").findAll(text).forEach { m ->
                titles[m.groupValues[1]] = m.groupValues[2].trim()
            }
        }
        val bad = mutableListOf<String>()
        Regex("""(?m)^\| L-0*(\d+) \|([^\n]*)$""").findAll(indexPart).forEach { m ->
            val id = m.groupValues[1]
            // 行尾那个 `|` 属于 Markdown 边框，去掉再切列 —— 否则最后一列恒为空串，检查恒真
            val cells = m.groupValues[2].removeSuffix("|").split("|").map { it.trim() }
            val claim = cells.lastOrNull() ?: ""
            val real = titles[id] ?: return@forEach
            if (claim != real) bad += "L-$id\n      索引：$claim\n      块  ：$real"
        }
        assertTrue(
            "索引行的标题与块标题不一致（改标题时两处都要改，或让写入脚本从块里取）：\n    ${bad.joinToString("\n    ")}",
            bad.isEmpty(),
        )
    }

    /**
     * `0.3 待办视图` 与 `0.7 同族簇` 引用的编号必须在册（`BUG.md` L-1341）。
     *
     * 这两节是「挑活入口」与「改一处先看同族」的入口；指向已移出的条目时按编号只能检索到
     * fixed / history / excluded 里的旁证行、拿不到条目本身 ⇒ 挑活走空路。原先无守卫，
     * 每移出一条就可能留一处。判据：两节里出现的每个 `L-NNN` 都要落在「索引行 ∪ 详情块」内。
     */
    @Test
    fun 待办与同族簇引用的编号必须在册() {
        val entry = ledger()
        val inBook = mutableSetOf<String>()
        Regex("""(?m)^\| L-0*(\d+) \|""").findAll(ledgerPartFiles()["index"].orEmpty())
            .forEach { inBook += it.groupValues[1] }
        Regex("""(?m)^### L-0*(\d+) ·""").findAll(ledgerAll()).forEach { inBook += it.groupValues[1] }
        assertTrue("在册集合为空（索引 / 块取材失效）", inBook.size > 50)
        fun section(start: String): String {
            val from = Regex("(?m)^" + Regex.escape(start) + ".*$").find(entry)
                ?: error("台账里找不到 $start")
            val rest = entry.substring(from.range.last + 1)
            val end = Regex("""(?m)^#{2,3} """).find(rest)?.range?.first ?: rest.length
            return rest.substring(0, end)
        }
        val bad = mutableListOf<String>()
        for (sec in listOf("### 0.3 待办视图", "### 0.7 同族簇")) {
            Regex("""L-0*(\d+)""").findAll(section(sec)).forEach { m ->
                val id = m.groupValues[1]
                if (id !in inBook) bad += "$sec 引用了不在册的 L-$id"
            }
        }
        assertTrue("待办 / 同族簇引用了已移出的编号（改指现存条目或删引用）：\n    ${bad.joinToString("\n    ")}", bad.isEmpty())
    }

    /**
     * 条目判据里点名的**代码符号**必须还在源码里（BUG.md L-1183）。
     *
     * 条目的「判断依据 / 详情」大量引用函数名、常量名、类名；这些名字随代码改名 / 搬迁会悄悄失效，
     * 而结构与计数守卫看不见。2026-10-08 的人工复核就撞到一条（L-1154 的措辞早已过时）。
     *
     * 口径（宁窄勿误伤）：只查**反引号里**的令牌，且必须同时满足 ——
     * 以大写字母或下划线开头、长度 ≥ 5、不含路径与扩展名（`/`、`.md`、`.kt`）、
     * 不是纯大写短词（`SQL` / `HTTP` / `JSON` 这类协议名与文档同形）。
     * 命中判据是「主源码 + 测试源码里出现过该串」；带点的取第一段（`Locale.US` → `Locale`）。
     *
     * 两处豁免都对应真实误报：**只查 `###` 在册块**（`####` 遗留区与回执里的类名可能指已删测试，
     * `ConfigBackupKeyCoverageTest` 就是这样被清掉后仍留在历史记录里的），
     * 且跳过「建议 / 修法」行（那里点的是**拟定名**，如 `UriFailed`，本来就不存在）。
     */
    @Test
    fun 条目判据点名的代码符号必须还在源码里() {
        // 取材面＝源码 + 构建脚本 + 资源清单 + 工具脚本：判据里也点构建 DSL 的名字
        // （`applicationId` / `applicationIdSuffix`）与脚本名（`gen_hot_dict.py`），只扫 Kotlin 会把它们全报成漂移。
        // 刻意排除 `*.md`：台账自己就是 Markdown，把它算进来这条检查就恒真了
        val skip = setOf(".git", "build", ".gradle", ".kotlin", ".wwlia-handoff", ".codebuddy", ".ppt-master", "docs", "artifacts")
        val exts = listOf(".kt", ".kts", ".xml", ".py", ".json", ".properties", ".yml", ".pro")
        val sources = buildString {
            for (root in listOf("app/src", "src")) {
                val dir = File("$root/main/java/com/jinn/inputmethod")
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { append(it.readText()) }
                val tests = File("$root/test/java/com/jinn/inputmethod")
                if (tests.isDirectory) {
                    tests.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { append(it.readText()) }
                }
                break
            }
            File(".").walkTopDown()
                .onEnter { it.name !in skip }
                .filter { it.isFile && exts.any { e -> it.name.endsWith(e) } }
                .forEach { append(it.readText()) }
            // 仓库根的 CI / 发布脚本也要算进取材面：单测的 CWD 是模块目录（`app/`），
            // 上面的 `File(".")` 扫不到 `.github/` —— 而条目也会点名脚本里的符号
            // （`safe_url` / `api_request` / `URLError` …），不扫就会把它们全报成漂移
            for (name in listOf("../.github", "../tools", "../scripts")) {
                val dir = File(name)
                if (!dir.isDirectory) continue
                dir.walkTopDown()
                    .filter { it.isFile && exts.any { e -> it.name.endsWith(e) } }
                    .forEach { append(it.readText()) }
            }
            for (dir in listOf(File("."), File("tools"), File("scripts"))) {
                if (!dir.isDirectory) continue
                dir.listFiles()?.forEach { append(it.name) }
            }
        }
        assertTrue("没读到主源码（工作目录变了？）", sources.length > 100_000)
        // 采集面：标识符 + 可选的一对括号（`save()` 这类调用形态此前根本进不来，BUG.md L-1188）
        val tokenRe = Regex("""`([A-Za-z][A-Za-z0-9_.]*)\(?\)?`""")
        // 停用表：平台 / 框架侧的名字，源码里本就不该出现（如 `onCorruption` 是 SQLiteOpenHelper 的覆写点）
        val stop = setOf(
            "SQL", "HTTP", "JSON", "HTTP_STATUS", "UTF", "API", "ID", "URL", "IDEA", "JSONL",
            // 平台 / 框架侧的名字：源码里本就不该出现，或只在注释里解释「为什么没用它」
            "onCorruption", "accessibilityLiveRegion",
        )
        val bad = mutableListOf<String>()
        for ((name, text) in ledgerPartFiles()) {
            if (name == "index" || name == "excluded" || name == "history" || name == "fixed" || name == "verify") continue
            // 只取 `###` 在册块：到下一个任意层级的标题为止（`####` 遗留区、回执、索引都不算）
            val blocks = Regex("""(?ms)^### L-\d+ ·.*?(?=^#{3,4} |\z)""").findAll(text).map { it.value }
            for (block in blocks) {
                val body = block.lines()
                    .filterNot { it.trimStart().startsWith("- **建议**") || it.trimStart().startsWith("- **修法**") }
                    .joinToString("\n")
                for (m in tokenRe.findAll(body)) {
                    val tok = m.groupValues[1]
                    val head = tok.substringBefore('.')
                    if (head.length < 5 || tok in stop || head in stop) continue
                    // 首字母大写、含下划线，或含大写字母（小写驼峰）—— 后者是函数名的主力形态
                    // （`trimFavorites` / `readCapped` 这类此前整类漏检，BUG.md L-1188）；
                    // 全小写英文词仍不查，它们与散文同形
                    if (!head[0].isUpperCase() && '_' !in head && head.none { it.isUpperCase() }) continue
                    if ("/" in tok || ".md" in tok || ".kt" in tok || ".json" in tok) continue
                    if (head in sources) continue
                    bad += "L?($name) 找不到 `$tok`"
                }
            }
        }
        val uniq = bad.distinct().take(12)
        assertTrue(
            "这些判据点名的符号在源码里找不到（改名 / 搬迁后要同步条目）：\n    ${uniq.joinToString("\n    ")}",
            uniq.isEmpty(),
        )
    }

    /**
     * 块首行形态（`0.5` 规则 ⑦）：L 块首行是字段行、X 块首行是列表项。
     *
     * 机器可解析的前提：**同一类块的第一个内容行长得一样**。B 块（历史回执）逐字保留，不约束。
     */
    @Test
    fun 块首行形态必须统一() {
        val bad = mutableListOf<String>()
        for ((name, text) in ledgerPartFiles()) {
            val lines = text.lines()
            for (i in lines.indices) {
                // `#{3,4}`：素材段（3.3）的 `#### L-` 块首行与在册块同形，早先只查三级，
                // 于是一整段素材块删光也不会有判据变红（BUG.md L-1184）
                val m = Regex("""^#{3,4} (L|X)-(\d+) ·""").find(lines[i]) ?: continue
                val head = lines.drop(i + 1).firstOrNull { it.isNotBlank() } ?: ""
                if (m.groupValues[1] == "L") {
                    if ("**风险**" !in head || "**主维度**" !in head) bad += "$name:${i + 1}（L 块首行缺字段行）"
                } else if (!head.trimStart().startsWith("- ")) {
                    bad += "$name:${i + 1}（X 块首行不是列表项）"
                }
            }
        }
        assertTrue("块首行形态不一致（L 块要 `- **风险**：… ｜ **主维度**：…`，X 块要 `- ` 开头）：${bad.take(6)}", bad.isEmpty())
    }

    /**
     * 文档 front-matter 的必需字段（`0.5` 规则：AI 先读那套元数据必须齐）。
     *
     * 角色不同字段不同：入口要五个计数键 + `parts`；分册要 `part` / `back` / `numbers`；
     * 约定文件要 `numbers` / `details` / `ledger` / `guards`；流水账要 `note`（历史标注）。
     */
    @Test
    fun 文档frontMatter必须含必需字段() {
        val need = listOf(
            "BUG.md" to listOf("schema", "doc", "role", "audience", "updated", "read_when",
                "active_entries", "entries_with_detail", "excluded_items", "history_records", "id_range", "parts"),
            "AGENTS.md" to listOf("schema", "doc", "role", "audience", "updated", "read_when",
                "numbers", "details", "ledger", "guards"),
            "更新日志.md" to listOf("schema", "doc", "role", "audience", "updated", "read_when", "note"),
        ) + LEDGER_PARTS.map {
            ".wwlia-handoff/ledger/$it.md" to listOf("schema", "doc", "role", "part", "audience",
                "updated", "read_when", "back", "numbers")
        }
        val bad = need.filterNot { (rel, keys) ->
            val text = readDoc(rel) ?: return@filterNot false      // 文件不在（本机文档）：不判
            keys.all { k -> Regex("""(?m)^%s: """.format(k)).containsMatchIn(text) }
        }.map { it.first }
        assertTrue("这些文档的 front-matter 缺必需字段（见 BUG.md 0.5）：$bad", bad.isEmpty())
    }

    /**
     * 入口文档不得重新膨胀（2026-09-29 的拆分：**入口只留红线 / 协议 / 计数真值**）。
     *
     * 拆的理由是「读不完就没有约束力」：BUG.md 曾带着 97 行索引表、工程约定 曾 629 行。
     * 细节一律放 `.wwlia-handoff/ledger/index.md` 或 `.wwlia-handoff/memory/agents-extras*.md`，
     * 本用例给两个入口各留一个**宽松上限**——不是审美，是防止下一轮把细节又堆回来。
     */
    @Test
    fun 入口文档不得重新膨胀() {
        val entry = ledger()
        assertTrue(
            "索引表必须留在 `ledger/index.md`：入口（BUG.md）里又出现了 `| L-… |` 行",
            !Regex("""(?m)^\| L-\d+ \|""").containsMatchIn(entry),
        )
        val nonBlank = convention().lines().count { it.isNotBlank() }
        assertTrue(
            "工程约定 又变长了（非空行 $nonBlank，上限 260）：细节写进 `.wwlia-handoff/memory/` 的分册，别堆回入口",
            nonBlank <= 260,
        )
        val bugLines = entry.lines().count { it.isNotBlank() }
        assertTrue("BUG.md 又变长了（非空行 $bugLines，上限 160）", bugLines <= 160)
    }

    /**
     * 语音条目**不得**标「已修」（`工程约定`「规则」：语音链路只记录、不改）。
     *
     * 起因（L-164）：2026-09-29 有一轮确实改了语音三条并标「同日已修」，按红线回滚后
     * 台账里那三行**一度仍写着「已修」** —— 代码回滚了、文档没回滚，是最容易骗过下一轮的不一致。
     * 判据：索引里凡标题涉及语音实现的条目，状态词不得含「已修」。
     */
    @Test
    fun 语音条目不得标已修() {
        val indexPart = ledgerPartFiles()["index"] ?: error("索引分册缺失（见 BUG.md 的 0.6 详情文件地图）")
        val bad = Regex("""(?m)^\| L-(\d+) \|([^\n]*)$""").findAll(indexPart)
            .filter { m ->
                // 用**语音实现名**判定，不用泛词「语音」：L-100（明文开关，为语音而设但改的是 manifest）
                // 与 L-164（本条的来源）都只是在描述里提到语音，不是在改语音链路。
                val voice = Regex("""MicRecorder|AsrClient|MicButton|Protocol|WebSocket|sendChunk|onMessage|音频包|二进制帧|重连""")
                    .containsMatchIn(m.value)
                voice && "已修" in m.value
            }
            .map { it.groupValues[1] }
            .toList()
        assertTrue(
            "这些语音条目标了「已修」，但红线要求只记录不改（确已获授权才改，且要在回执里说明例外）：$bad",
            bad.isEmpty(),
        )
    }

    /** 构建产物在仓库根的文件名 —— 必须与 `build_apk.py` 的 `output_apk` 一致。 */
    private val APK_FILE_NAME = "com.jinn.inputmethod.apk"

    /**
     * README 的 APK 体积声明必须与本地发布包相符（`BUG.md` L-165）。
     *
     * 对外写「约 2.6MB」而实际 2.71 MB，用户按体积预估下载量会判错；这也是发布后最容易腐烂的一处数字
     * （每次改动都在长，而 README 没人回头改）。只在仓库根存在构建产物时判，干净检出 / CI 里没有这个包就跳过。
     * 单位口径见下方注释（2026-10-10 起为十进制 MB）。
     *
     * ⚠ 产物名必须与 `build_apk.py` 一致 —— 改名时只改一处，这条守卫就会**静默跳过**，
     * README 的体积声明从此没人核对（2026-10-02 产物由 `jinn-release.apk` 改为
     * `com.jinn.inputmethod.apk`，正是需要两处同改的一次）。所以先与脚本对拍：
     * 脚本里找不到这个名字即失败；只有「脚本对得上、但本机没有包」才算干净检出并跳过。
     */
    @Test
    fun README体积声明必须与实际发布包相符() {
        // 脚本缺失 → **报错**，不静默（与 `BuildScriptContractTest` 用 `first {}` 的口径一致；L-474）
        val script = listOf(File("build_apk.py"), File("../build_apk.py"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到 build_apk.py —— 产物名对拍与 README 体积核对都无法进行")
        // 脚本里的产物名是 `f"{APPLICATION_ID}.apk"` 拼出来的（不是字面量），
        // 所以先校验**派生关系**：仓库根的产物名 = 包名 + .apk。
        val appId = Regex("""(?m)^APPLICATION_ID\s*=\s*"([^"]+)"""")
            .find(script)?.groupValues?.get(1)
        assertTrue("build_apk.py 里找不到 APPLICATION_ID 常量", appId != null)
        assertEquals(
            "产物名必须由包名派生：build_apk.py 的 APPLICATION_ID 是 $appId，" +
                "而本守卫按 $APK_FILE_NAME 找包（改包名 / 改产物名时请两处同改）",
            "$appId.apk", APK_FILE_NAME,
        )
        // ⚠ 上面那条只证明「包名 + .apk == 守卫里的名字」，**证明不了脚本真按它产出文件**：
        // 把 `output_apk = ROOT / f"{APPLICATION_ID}.apk"` 改成别的表达式而保留 APPLICATION_ID，
        // 上面照样绿，下面的 `?: return` 又会静默跳过整个体积核对 —— 等于没修（2026-10-02 修复 L-464）。
        // 因此要钉**脚本里的那句构造式**本身。
        val expectedOutput = "ROOT / f\"{APPLICATION_ID}.apk\""
        assertEquals(
            "build_apk.py 必须按 `$expectedOutput` 产出（只对拍名字不够：改这句表达式时这里要红）",
            1, script.split(expectedOutput).size - 1,
        )
        val apk = listOf(File(APK_FILE_NAME), File("../$APK_FILE_NAME"))
            .firstOrNull { it.isFile } ?: return
        // 2026-10-10：单位由 MiB（1024 进制）改为 **MB（十进制）** —— 与 `ByteSize`（应用内
        // 体积文案）、`OptionalDicts.sizeMb`（清单）以及托管平台的下载体积展示同一口径；
        // 同一只包按 MiB 写 2.95、按十进制写 3.10，README 若与页面/发布页各用一套，用户对不上账
        val mb = apk.length() / 1e6
        for (name in listOf("README.md", "README_EN.md")) {
            val doc = readDoc(name) ?: continue
            val claims = Regex("""(?i)APK[^\n]{0,12}?约?\s*~?\s*([0-9]+(?:\.[0-9]+)?)\s*MB""")
                .findAll(doc).map { it.groupValues[1] }.toList()
            if (claims.isEmpty()) continue
            val actual = "%.1f".format(mb)      // 对外写的是「约 X.YMB」：取一位小数比
            // 全量集合：文档里**每处** APK 体积声明都必须等于当前值 —— 只取首个匹配会漏掉
            // 「历史段残留旧值」（AI2 只读审查 A-270）
            assertEquals(
                "$name 的 APK 体积声明应全部等于实际 ${"%.2f".format(mb)} MB（历史段残留旧值？体积变大时请同步改 README）",
                setOf(actual), claims.map { "%.1f".format(it.toDouble()) }.toSet(),
            )
        }
    }

    /**
     * 索引行的状态必须与详情块的 `**状态**：` 一致。
     *
     * 第五十四回实测：L-155 的索引行写「未修 · 需口径」、详情块写「已修·留存」—— 两处互相矛盾，
     * 而「未修 / 已修」直接决定下一轮要不要动手 ⇒ 必须机械对拍。比对前各自归一：去掉 markdown 标记
     * 与「（…）」后缀（索引里常带「（第五十四回）」这类注记），只比状态词那一层。
     */
    @Test
    fun 索引状态必须与详情块一致() {
        val indexPart = ledgerPartFiles()["index"] ?: error("索引分册缺失（见 BUG.md 的 0.6 详情文件地图）")
        val all = (listOf(ledger()) + ledgerPartFiles().values).joinToString("\n")
        val bad = ArrayList<String>()
        for (m in Regex("""(?m)^\| (L-\d+) \|([^\n]*)$""").findAll(indexPart)) {
            val id = m.groupValues[1]
            val cells = m.value.split("|")
            if (cells.size < 6) continue
            // 行格式：`| L-xx | 风险 | 维度 | 状态 | 分册 | 标题 |`
            // ⇒ split('|') 之后状态在第 5 段（下标 4）；取错列会拿「维度」当状态
            val indexWord = statusWord(cells[4])
            // `#{3,4}`：遗留区里有 `#### L-53` 这类四级块，只认三级会把它们静默跳过
            // （旁证：front-matter 的 entries_with_detail 恰好比 active_entries 少 1）
            val blk = Regex("(?s)#{3,4} $id · .*?(?=\n#{3,4} |\\z)").find(all)?.value ?: continue
            val detail = Regex("""\*\*状态\*\*：([^｜\n]*)""").find(blk)?.groupValues?.get(1) ?: continue
            val detailWord = statusWord(detail)
            if (indexWord != detailWord) bad.add("$id（索引=$indexWord｜详情=$detailWord）")
        }
        assertTrue("索引与详情的状态词不一致（改一处忘另一处）：$bad", bad.isEmpty())
    }

    /**
     * 状态词（判据按两段取，别只看括号前那一层）：
     *
     * ① 含「已修」⇒ 归为 `已修` —— 这套台账里最常写的状态差异就在括号里
     * （`真实缺陷（同日已修）` vs `真实缺陷（未修 · 需口径）`），只看括号前会把它们**整片漏掉**
     * （第五十五回审计实测）；
     * ② 否则取「（…）」之前的词（`真实缺陷 / 存疑 / 部分已修 / 有意设计 / 复核排除 / 已清理` …）。
     */
    private fun statusWord(raw: String): String {
        val t = raw.replace("*", "").replace("`", "").trim()
        // 「部分已修」必须**先**判：它本身含「已修」，压在后面会被当成整条已修（BUG.md L-191）——
        // 而「部分 / 全部」正是这套台账最常用的区分。
        if (t.contains("部分已修")) return "部分已修"
        if (t.contains("已修")) return "已修"
        return t.substringBefore("（").trim()
    }

    /**
     * 每个分册**声明**的块数必须等于该册里的实际块数 —— 声明有两种写法：页眉的 `blocks: N（`，
     * 与节标题里的「`## N. 中风险（X 条`」（2026-10-02 起两者都核对，见 L-460）。
     *
     * 全局计数（front-matter ↔ 全库）由 [文档计数须与源码一致] 把守，但**单册**页眉原先没人核对：
     * 第五十五回我的批量改写脚本吞掉 24 个块时，low.md 的页眉还写着 87（当时的真值），
     * 与文件里的 63 块自相矛盾 —— 这条守卫能在那一刻立即报出来（BUG.md L-167）。
     */
    @Test
    fun 分册页眉的块数必须与该册实际块数一致() {
        val bad = ArrayList<String>()
        for ((name, text) in ledgerPartFiles()) {
            val actual = Regex("""(?m)^#{3,4} (?:L|X|B)-\d+ ·""").findAll(text).count()
            // 两种「声明块数」的写法各自独立核对：有哪种就查哪种，都没有才算该册不声明计数。
            // 原先只认页眉、拿不到就 `continue`，于是 medium / low 这两册（块数最多、最常被
            // 批量脚本改写）一句都不报 —— 实测 medium 写 15 实为 12、low 写 191 实为 243
            // （2026-10-02 修复 L-460）。index 的「N 条在册」另有专门断言（它数的是索引行，
            // 不是 L 块），所以这里的节标题分支只认中风险 / 低风险两节。
            // 页眉声明有两种写法：现行 `> part=xx blocks=N frags=M …`（等号）与旧稿 `blocks: N（`。
            // 2026-10-23 前只认后者，而全部分册页眉早在统一改写成等号形态 ⇒ 这条守卫长期空转，
            // medium 写 46 实为 4、index 写 224 实为 217 都没人报。现在两种都认。
            val header = Regex("""(?m)^>\s*part=\w+\s+blocks=(\d+)""")
                .find(text)?.groupValues?.get(1)?.toInt()
                ?: Regex("""(?m)^blocks: (\d+)（""").find(text)?.groupValues?.get(1)?.toInt()
            // 索引册声明的是「索引行数」而不是 L 块数（它自己没有块）
            val headerRows = Regex("""(?m)^>\s*part=\w+\s+rows=(\d+)""")
                .find(text)?.groupValues?.get(1)?.toInt()
            if (headerRows != null) {
                val idxRows = Regex("""(?m)^\| L-\d+ \|""").findAll(text).count()
                if (headerRows != idxRows) bad.add("$name（页眉 rows $headerRows / 实际 $idxRows）")
            }
            val titled = Regex("""(?m)^## \d+\. (?:中风险|低风险)（(\d+) 条""")
                .find(text)?.groupValues?.get(1)?.toInt()
            if (header != null && header != actual) bad.add("$name（页眉 $header / 实际 $actual）")
            if (titled != null && titled != actual) bad.add("$name（节标题 $titled / 实际 $actual）")
            // 节标题把总数拆成「N 条在册 + M 条重建期素材块」时，两个分项各自对拍（BUG.md L-1184）：
            // 只比总数时，删掉的材料块数量恰好等于在册块数量才报得出来 —— 那一次纯属巧合
            val parts = Regex("""(?m)^## \d+\. 低风险（(\d+) 条：(\d+) 条在册 \+ (\d+) 条重建期素材块""")
                .find(text)?.groupValues
            if (parts != null) {
                val inBooks = Regex("""(?m)^### L-\d+ ·""").findAll(text).count()
                val fragments = Regex("""(?m)^#### L-\d+ ·""").findAll(text).count()
                if (parts[2].toInt() != inBooks) bad.add("$name（节标题写在册 ${parts[2]} / 实际 $inBooks）")
                if (parts[3].toInt() != fragments) bad.add("$name（节标题写素材 ${parts[3]} / 实际 $fragments）")
            }
        }
        assertTrue("分册页眉块数与实际不一致：$bad", bad.isEmpty())
    }

    /**
     * `功能总览.md` 里几处**会腐烂的事实**必须与源码一致（2026-10-02 修复 L-465 / L-473）。
     *
     * 这份文档自称「逐条列出全部用户可见功能……一个不漏」，但此前**零守卫**：默认值写反、
     * 控件改了名它不知道、键位序号与它自己的面板清单互相矛盾 —— 三处都是发布之后才由人工审出来的。
     * 这里只钉**能机械对拍**的四类；剩下没有权威来源的计数（「30 余个设置项」之类）仍靠人工，
     * 那是 L-473 留的口子。
     */
    @Test
    fun 功能总览的事实必须与源码一致() {
        val doc = readDoc("功能总览.md") ?: error("找不到 功能总览.md（改名或移位了？）")
        val prefs = TestSources.codeSource("Prefs.kt")
        val settings = TestSources.codeSource("SettingsActivity.kt")

        // ① 默认值：从 Prefs 读真值，再要求文档写对（曾写「双拼候选全音……默认开」，实际默认关）
        // 读侧收口后（BUG.md L-653）默认值写在 `boolOr(KEY_SHOW_QUANPIN, …)` 里：两种形态都认，
        // 否则「把裸读换成收口助手」这种纯加固会把本条守卫打红
        val quanpin = Regex("""(?:getBoolean|boolOr)\(KEY_SHOW_QUANPIN,\s*(true|false)\)""")
            .find(prefs)?.groupValues?.get(1)
            ?: error("Prefs.kt 里找不到 KEY_SHOW_QUANPIN 的默认值")
        val line = doc.lineSequence().firstOrNull { "双拼候选全音" in it }
            ?: error("功能总览里找不到「双拼候选全音」那一行")
        val expected = if (quanpin == "true") "默认开" else "默认关"
        assertTrue(
            "功能总览里「双拼候选全音」的默认值与 Prefs.KEY_SHOW_QUANPIN 不符（实际应为$expected）：$line",
            expected in line,
        )

        // ② 控件名必须是屏幕上那个（2026-10-01 由「键盘内嵌韵母」改名「键盘内显韵母」，文案在代码里下发
        //    而非 strings.xml；2026-10-09 该开关迁到「键盘外观」页 —— 锚点跟着控件走）
        val appearance = TestSources.codeSource("KeyAppearanceActivity.kt")
        assertTrue("KeyAppearanceActivity 里找不到「键盘内显韵母」", "键盘内显韵母" in appearance)
        assertFalse("功能总览仍在用旧控件名「键盘内嵌韵母」", "键盘内嵌韵母" in doc)

        // ③ 功能面板的清单与序号按**源码**推导（此前拿文档自己的清单对文档自己的序号 ——
        //    挪按钮、改序号都不会红，BUG.md L-1146）
        val panelSrc = TestSources.codeSource("PinyinKeyboardView.kt")
        val translateLabel = Regex("const val LABEL_TRANSLATE = \"([^\"]+)\"")
            .find(panelSrc)?.groupValues?.get(1) ?: error("PinyinKeyboardView 里找不到 LABEL_TRANSLATE")
        // 面板体：renderFunctionPanel 的 `label = …` 按出现顺序即按钮顺序（条件键也在内）
        val panelBody = TestSources.blockAfter(panelSrc, "private fun renderFunctionPanel()")
        val sourceOrder = Regex("""label = ([^\n]*)""").findAll(panelBody).mapNotNull { m ->
            val tail = m.groupValues[1]
            when {
                "LABEL_TRANSLATE" in tail -> translateLabel
                // 条件文案（剪贴板键的 返回 / 历史）：静止态是 else 分支那一个
                "else" in tail -> Regex("\"([^\"]+)\"").findAll(tail).lastOrNull()?.groupValues?.get(1)
                else -> Regex("\"([^\"]+)\"").find(tail)?.groupValues?.get(1)
            }
        }.filterNot { it == "退出" }.toList()   // 「退出」属搜索态的早退分支，不是常驻键
        assertTrue("从源码里没解析出面板按钮（renderFunctionPanel 结构变了？）：$sourceOrder", sourceOrder.size >= 6)

        val items = doc.substringAfter("**功能面板按钮**").lines().drop(1)
            .takeWhile { it.startsWith("  ") && it.trimStart().startsWith("- ") }
        val docLabels = items.map {
            it.trim().removePrefix("- ").substringBefore("（").substringBefore(" /").substringBefore("：").trim()
        }
        assertEquals("功能总览的面板清单与源码顺序不一致（源码为准）", sourceOrder, docLabels)

        // 序号：图库键由宿主决定是否出现，文档的「第 N 键」按**它缺席时**的源码位置算
        val translateItem = items.firstOrNull { "翻译" in it }
        assertTrue("功能总览的面板清单里找不到翻译键那一行", translateItem != null)
        val claimed = Regex("""第 (\d+) 键""").find(translateItem!!)?.groupValues?.get(1)?.toInt()
        assertTrue("翻译键那一行里找不到「第 N 键」：$translateItem", claimed != null)
        assertEquals(
            "翻译键的序号必须等于它在源码顺序里的位置（图库键缺席时）",
            sourceOrder.filterNot { it == "图库" }.indexOf(translateLabel) + 1,
            claimed,
        )

        // ④ 音效库条数：删音效那批改了设置页与测试，这份文档漏了（BUG.md L-473 留的「计数归人工」口子）。
        // 源码侧走**剥注释**的共用助手：注释里写一句同形常量不该能满足这条对拍；两侧都要求
        // 「出现多处时必须一致」，免得只钉到第一处。
        val tap = TestSources.codeSource("TapSound.kt")
        val soundCounts = Regex("""const val SOUND_COUNT\s*=\s*(\d+)""").findAll(tap)
            .map { it.groupValues[1] }.toList()
        assertTrue("TapSound.kt 里找不到 SOUND_COUNT", soundCounts.isNotEmpty())
        assertEquals("TapSound.kt 里 SOUND_COUNT 出现多处且不一致", 1, soundCounts.toSet().size)
        val soundClaims = Regex("""(\d+)\s*个真实键盘录音""").findAll(doc).map { it.groupValues[1] }.toList()
        assertTrue("功能总览里找不到「N 个真实键盘录音」那一句", soundClaims.isNotEmpty())
        assertEquals("功能总览里「N 个真实键盘录音」出现多处且不一致", 1, soundClaims.toSet().size)
        assertEquals("功能总览里的音效个数与 TapSound.SOUND_COUNT 不符", soundCounts[0], soundClaims[0])
    }

    /**
     * 可选词库包的条数必须与源码一致（2026-10-12 复核：重切包后 README 与功能总览还写着旧值）。
     *
     * 真值来自 `OptionalDicts` 的展示名（`"2级词库 +90万条短语"`）：换包时改名，真值随之更新；
     * 两份面向用户的文档必须跟上 —— README 是外部读者的唯一依据，功能总览是全量清单。
     */
    @Test
    fun 可选词库包条数必须与源码一致() {
        // 剥注释后再取（`BUG.md` L-1339）：拿原文对拍时，注释里一句同形样例就能骗过这条计数
        val src = TestSources.codeSource("OptionalDicts.kt")
        val counts = Regex("""name = "\d级词库 \+(\d+)万条短语"""").findAll(src)
            .map { it.groupValues[1] }.toList()
        assertEquals("OptionalDicts 的包名换了写法（本守卫要跟着改）", listOf("90", "70", "21").sorted(), counts.sorted())
        for (name in listOf("README.md", "功能总览.md")) {
            val text = readDoc(name) ?: continue
            for (n in counts) {
                assertTrue(
                    "$name 缺当前包条数 +$n 万（重切包后要同步文档）",
                    "+$n 万" in text || "+${n}万" in text,
                )
            }
            // 反向不写死旧值：文档里任何**非当前**的 `+N 万` 都是重切包后的残留。
            // 内置 40 万 / 29.9 万键这类不带 `+`，不会误伤（写死 40/50/60 则漏下一次重切的新旧值）
            val stray = Regex("""\+(\d+)\s*万""").findAll(text).map { it.groupValues[1] }
                .filter { it !in counts }.toSet()
            assertTrue(
                "$name 还写着旧包条数 +${stray.sorted().joinToString(" / ")} 万（当前 ${counts.joinToString(" / ")}）",
                stray.isEmpty(),
            )
        }
        // 英文 README 按 K 计数，钉住整句结构：只查中文文档会漏 —— 2026-10-12 复核时
        // 它的技术附录里仍留着重切前的 400K / 500K / 600K（功能说明那段早已是 900K / 700K / 210K）
        val en = readDoc("README_EN.md") ?: return
        val kCounts = counts.map { (it.toInt() * 10).toString() }
        val packs = Regex("""parts 2 / 3 / 4: (\d+)K / (\d+)K / (\d+)K""").findAll(en)
            .map { it.groupValues.drop(1) }.toSet()
        assertEquals("README_EN 的包条数行应恰为源码值（写法变了 / 改一处漏一处？）", setOf(kCounts), packs)
        for (k in kCounts) assertTrue("README_EN 缺当前包条数 ${k}K", "${k}K" in en)
        // 同一条数字在四处的写法各异，只钉其中几处仍会「改一处漏一处」（2026-10-12 的真实缺陷）：
        // 中文 README 的附录按「万条」写、英文 README 的功能说明按 K 写 —— 两处一起按结构钉住
        val cnReadme = readDoc("README.md") ?: error("找不到 README.md")
        val cnPart = Regex("""第 2 / 3 / 4 部分，\s*(\d+) / (\d+) / (\d+) 万条""").findAll(cnReadme)
            .map { it.groupValues.drop(1) }.toSet()
        assertEquals("README 的「第 2 / 3 / 4 部分」条数应恰为源码值（写法变了 / 改一处漏一处？）", setOf(counts), cnPart)
        val enLevels = Regex("""level 2 (\d+)K \(recommended\), level 3 (\d+)K, level 4 (\d+)K""").findAll(en)
            .map { it.groupValues.drop(1) }.toSet()
        assertEquals("README_EN 的 level 2/3/4 条数应恰为源码值", setOf(kCounts), enLevels)
    }

    /**
     * 用户可见的几处计数必须与源码一致（2026-10-12 逐条人工核过，当天补成守卫）。
     *
     * 这四类都是「文档声明一个数、源码里另有真值」——英文 README 就出过一次「技术附录留着旧包条数、
     * 功能说明已更新」的静默腐烂。源码换了写法一律 `error` 提示改守卫，不静默放行。
     *
     * **未钉、且是有意未钉**：内置短语索引那句「约 29.9 万键 / 40 万条」——数字本身是近似值
     * （「约」），且要遍历三十万键才能数准（`PhraseIndex` 只公开 `keyAt` / `wordsFor`）；
     * 重切内置索引时以 `更新日志.md` 为记，靠人工同步。
     */
    @Test
    fun 用户可见计数必须与源码一致() {
        // ① 皮肤：定义条数与明暗分档（README / 功能总览都对外声明）
        val skinSrc = TestSources.codeSource("KeyboardSkin.kt")
        val skinTotal = Regex("""(?m)^\s*val [A-Z_]+ = KeyboardSkin\(""").findAll(skinSrc).count()
        val skinDark = Regex("""tone = SkinTone\.DARK""").findAll(skinSrc).count()
        val skinLight = Regex("""tone = SkinTone\.LIGHT""").findAll(skinSrc).count()
        assertTrue(
            "KeyboardSkin 的写法变了（本守卫要跟着改）：共 $skinTotal / 暗 $skinDark / 亮 $skinLight",
            skinTotal > 0 && skinTotal == skinDark + skinLight,
        )
        // ② AI 动作：枚举里的中文名去掉「翻译」本身（它是长按翻译键的直连动作，不在展开清单里）
        val aiSrc = TestSources.codeSource("AiAction.kt")
        val aiLabels = Regex("""(?m)^\s*[A-Z_]+\("[a-z]+", "([^"]+)"\)""").findAll(aiSrc)
            .map { it.groupValues[1] }.toList()
        assertTrue("AiAction 的写法变了（本守卫要跟着改）：$aiLabels", aiLabels.size >= 2 && "翻译" in aiLabels)
        val aiExtras = aiLabels.filterNot { it == "翻译" }
        // 源码常量取值：命中必须**恰 1 处**（`BUG.md` L-1367）：同名旧声明残留 / 取到旧值时不再静默
        fun uniqueConst(src: String, pattern: String, tag: String): Int {
            val vals = Regex(pattern).findAll(src).map { it.groupValues[1].replace("_", "") }.toSet()
            if (vals.size != 1) error("$tag 在源码命中 ${vals.size} 处（应恰 1）——旧声明未删 / 写法变了？")
            return vals.first().toInt()
        }
        // ③ 文本单条上限（KB）；④ 图片单张默认上限（MB）
        val ctrlSrc = TestSources.codeSource("ClipboardController.kt")
        val textKb = uniqueConst(ctrlSrc, """MAX_ITEM_BYTES = (\d+) \* 1024""", "MAX_ITEM_BYTES")
        val prefsSrc = TestSources.codeSource("ClipboardPrefs.kt")
        val imageMb = uniqueConst(prefsSrc, """DEFAULT_IMAGE_ITEM_MB = (\d+)""", "DEFAULT_IMAGE_ITEM_MB")

        for (name in listOf("README.md", "功能总览.md")) {
            val text = readDoc(name) ?: continue
            assertTrue("$name 缺皮肤总数（源码 $skinTotal 套）", "$skinTotal 套" in text)
            assertTrue(
                "$name 的亮 / 暗分档与源码不符（源码 亮 $skinLight / 暗 $skinDark）",
                Regex("""亮色?\s*$skinLight\s*/\s*暗色?\s*$skinDark""").containsMatchIn(text),
            )
            // 反向：「单条上限」的每一处声明都必须是当前值 —— 只查「有没有写对」会漏掉改一半的
            // （2026-10-12 实测：把一处 256KB 改成 512KB，只查正向时守卫照样绿）。
            // 只扫这一种声明位，不扫全篇 NKB：功能总览那句「单条上限（256KB / 4KB~1MB，受解密窗自动收缩）」
            // 里的 4KB 是**可调范围**，属正当写法，全扫会误伤。
            val strayKb = Regex("""单条(?:上限)?[^\d]{0,6}(\d+)KB""").findAll(text)
                .map { it.groupValues[1] }.filter { it != textKb.toString() }.toSet()
            assertTrue("$name 的「单条上限」写着 ${strayKb.sorted().joinToString("/")}KB（源码 ${textKb}KB）", strayKb.isEmpty())
            val actionLine = text.lineSequence().firstOrNull { "长按翻译键" in it }
                ?: error("$name 找不到 AI 动作那一行（改了措辞？）")
            assertTrue("$name 的「N 个动作」与 AiAction 不一致（源码 ${aiExtras.size} 个）", "${aiExtras.size} 个动作" in actionLine)
            for (label in aiExtras) assertTrue("$name 的 AI 动作一行缺「$label」", label in actionLine)
        }
        val overview = readDoc("功能总览.md") ?: error("找不到 功能总览.md")
        assertTrue("功能总览缺「文本 ${textKb}KB」（源码 MAX_ITEM_BYTES）", "${textKb}KB" in overview)
        assertTrue("功能总览缺图片单张默认「${imageMb}MB」（源码 DEFAULT_IMAGE_ITEM_MB）", "默认 ${imageMb}MB" in overview)
        val strayMb = Regex("""默认\s*(\d+)\s*MB""").findAll(overview).map { it.groupValues[1] }
            .filter { it != imageMb.toString() }.toSet()
        assertTrue("功能总览写着非当前图片默认上限 ${strayMb.sorted().joinToString("/")}MB（源码 ${imageMb}MB）", strayMb.isEmpty())
        // ⑤ 自定义词库上限：字符数按源码精确值写（原先写「800 万」，比 8,388,608 少报 388,608）
        val dictSrc = TestSources.codeSource("CustomDicts.kt")
        val dictChars = uniqueConst(dictSrc, """MAX_INPUT_CHARS = (\d+) \* 1024 \* 1024""", "MAX_INPUT_CHARS") * (1024 * 1024)
        // 源码写作 `50_000`（带下划线）：`(\d+)` 只会吃到 `50`，算出「0 万条」——而 `"0 万条"` 又是
        // `"40 万条"` 的**子串** ⇒ 断言恒绿（BUG.md L-1343）。uniqueConst 已去下划线；断言也补定界。
        val dictEntries = uniqueConst(dictSrc, """MAX_ENTRIES = ([\d_]+)""", "MAX_ENTRIES")
        for (name in listOf("README.md", "功能总览.md")) {
            val text = (readDoc(name) ?: continue).replace(",", "")
            assertTrue("$name 缺自定义词库字符上限 $dictChars（源码 MAX_INPUT_CHARS）", dictChars.toString() in text)
            // 定界锚：`"5 万条"` 是 `"45 万条"` 的子串，裸子串判定会漏（BUG.md L-1343）
            assertTrue(
                "$name 缺自定义词库条数上限（源码 MAX_ENTRIES = $dictEntries，应写「/ ${dictEntries / 10000} 万条」）",
                "/ ${dictEntries / 10000} 万条" in text,
            )
        }
        // ⑥ 英文 README 的皮肤计数（中英同源；英文侧是已腐烂过两次的文件，单独钉）
        val enDoc = readDoc("README_EN.md") ?: error("找不到 README_EN.md")
        assertTrue("README_EN 缺皮肤总数（源码 $skinTotal 套）", "$skinTotal keyboard skins" in enDoc)
        assertTrue(
            "README_EN 的皮肤明暗分档与源码不符（源码 亮 $skinLight / 暗 $skinDark）",
            Regex("""$skinLight light,\s*$skinDark dark""").containsMatchIn(enDoc),
        )
        // 英文 README 的自定义词库上限也纳入视野（`BUG.md` L-1342 残留）：中文写精确值、英文按 M / K，
        // 两边都改到才跟随源码 —— 英文侧是已腐烂过两次的文件，单独立钉
        assertTrue("README_EN 缺自定义词库字符上限（源码 $dictChars）", "${dictChars / (1024 * 1024)}M characters" in enDoc)
        assertTrue("README_EN 缺自定义词库条数上限（源码 $dictEntries）", "${dictEntries / 1000}K entries" in enDoc)
        // ⑦ 剪贴板容量类默认值（README 那句「10 个参数」与功能总览的容量行都在对外写数）
        fun constOf(name: String): Int = uniqueConst(prefsSrc, """const val $name = (\d+)""", name)
        val dItems = constOf("DEFAULT_MAX_ITEMS")
        val dTotalMb = constOf("DEFAULT_MAX_TOTAL_MB")
        val dSearch = constOf("DEFAULT_MAX_SEARCH")
        val dImgItems = constOf("DEFAULT_IMAGE_MAX_ITEMS")
        val dImgTotalMb = constOf("DEFAULT_IMAGE_TOTAL_MB")
        // 「面板单页条数」的真值是 `panelPage`（2 ~ PANEL_PAGE_ITEMS_MAX），**不是**历史管理页那档
        // `PAGE_SIZE_OPTIONS` —— 两个参数的默认值都是 50，取错常量照样能过（2026-10-13 复核指出）
        val dPanelPage = constOf("DEFAULT_PANEL_PAGE")
        val readme = readDoc("README.md") ?: error("找不到 README.md")
        assertTrue("README 缺历史容量默认值（源码 $dItems 条 / ${dTotalMb}MB）", "（默认 $dItems 条 / ${dTotalMb}MB）" in readme)
        assertTrue("README 缺面板单页条数（源码 $dPanelPage）", "面板单页条数（$dPanelPage）" in readme)
        assertTrue("README 缺搜索结果上限（源码 $dSearch）", "搜索结果上限（$dSearch）" in readme)
        // 反向：同一参数在别处再写一次（或写）另一个值就是「改一处漏一处」——只在声明位做正向包含会漏
        for ((label, want) in listOf("面板单页条数" to dPanelPage, "搜索结果上限" to dSearch)) {
            val abroad = Regex("""$label（(\d+)）""").findAll(readme + overview).map { it.groupValues[1] }
                .filter { it != want.toString() }.toSet()
            assertTrue("文档里「$label」写着非当前值 ${abroad.sorted().joinToString("/")}（源码 $want）", abroad.isEmpty())
        }
        assertTrue(
            "README 缺图片张数 / 体积默认值（源码 $dImgItems 张 / ${dImgTotalMb}MB）",
            "（$dImgItems 张 / ${dImgTotalMb}MB / ${imageMb}MB）" in readme,
        )
        assertTrue("功能总览缺容量行（源码 $dItems / ${dTotalMb}MB）", "容量 $dItems" in overview && "总 ${dTotalMb}MB" in overview)
        // 结构对拍：两份文档的容量声明位都要与源码一致（`BUG.md` L-1344 残留）—— 原先只钉 README 的
        // 正向串与功能总览的速览行，功能总览那句「历史数量（默认 500 / 上限 20000）…」没人查。
        val overviewDecls = listOf(
            Regex("""历史数量（默认 (\d+)""") to dItems,
            Regex("""历史体积（(\d+)MB""") to dTotalMb,
            Regex("""图片张数上限（(\d+)""") to dImgItems,
            Regex("""图片体积上限（(\d+)MB""") to dImgTotalMb,
            Regex("""单张图片上限（(\d+)MB""") to imageMb,
        )
        for ((pat, want) in overviewDecls) {
            // 全量断言（`BUG.md` L-1360）：该声明位的**所有**命中都必须等于源码值 ——
            // 只取首个匹配时，同一声明位「旧值 + 新值」并存会静默放过旧值。
            val got = pat.findAll(overview).map { it.groupValues[1].toInt() }.toSet()
            assertEquals(
                "功能总览的容量声明位应恰为源码值 $want（旧值残留 / 写法变了？）：${pat.pattern}",
                setOf(want),
                got,
            )
        }
        assertEquals(
            "README 历史容量声明位与源码不一致（应恰为一处且取值等于源码）",
            setOf(listOf(dItems.toString(), dTotalMb.toString())),
            Regex("""默认 (\d+) 条 / (\d+)MB）""").findAll(readme).map { it.groupValues.drop(1) }.toSet(),
        )
        assertEquals(
            "README 图片容量声明位与源码不一致（应恰为一处且取值等于源码）",
            setOf(listOf(dImgItems.toString(), dImgTotalMb.toString(), imageMb.toString())),
            Regex("""(\d+) 张 / (\d+)MB / (\d+)MB）""").findAll(readme).map { it.groupValues.drop(1) }.toSet(),
        )
        // ⑧ 独立界面数与 Activity 数（真值 = AndroidManifest；`<activity\s` 排除 `<activity-alias`）
        //    「无界面」= **透明 / 无显示主题派生** + 显式白名单兜底（`BUG.md` L-1353）：纯硬编码名单
        //    在新增无界面页忘改名单时会静默；纯主题串又漏异类。取并集（Dialog 主题可见，不算）。
        val manifest = readDoc("app/src/main/AndroidManifest.xml") ?: error("找不到 AndroidManifest.xml")
        val activityTags = Regex("""<activity\s[^>]*>""").findAll(manifest).map { it.value }.toList()
        val activities = activityTags.size
        val declaredNames = activityTags.mapNotNull {
            Regex("""android:name="([^"]+)"""").find(it)?.groupValues?.get(1)
        }.toSet()
        val headlessWhitelist = setOf(".GalleryPickActivity") // 选图中转页（无布局）
        assertTrue(
            "页面数白名单里的无界面 Activity 在 manifest 里找不到：${headlessWhitelist - declaredNames}",
            declaredNames.containsAll(headlessWhitelist),
        )
        val headless = headlessActivities(manifest, headlessWhitelist).size
        assertTrue(
            "功能总览的 Activity 数与 AndroidManifest 不一致（源码 $activities 个）",
            "（$activities 个 Activity" in overview,
        )
        assertTrue(
            "功能总览的页面数与 AndroidManifest 不一致（源码 $activities 个 Activity 里 $headless 个无界面）：" +
                "${activities - headless}",
            "**${activities - headless} 个页面**" in overview,
        )
    }

    /**
     * 「无界面 Activity」集合（`BUG.md` L-1353）：`Theme.Translucent*` / `NoDisplay` 主题**派生**，
     * 加显式白名单兜底；Dialog 主题是可见界面，不算。只收 manifest 里确实声明的名字。
     */
    private fun headlessActivities(manifest: String, whitelist: Set<String>): Set<String> {
        val tags = Regex("""<activity\s[^>]*>""").findAll(manifest).map { it.value }
        val declared = tags.mapNotNull {
            Regex("""android:name="([^"]+)"""").find(it)?.groupValues?.get(1)
        }.toSet()
        val derived = tags.filter {
            Regex("""android:theme="[^"]*(Translucent|NoDisplay)""").containsMatchIn(it)
        }.mapNotNull {
            Regex("""android:name="([^"]+)"""").find(it)?.groupValues?.get(1)
        }
        return (derived.toSet() + whitelist).filter { it in declared }.toSet()
    }

    /** 「无界面」判定要认透明主题、不认可见的对话框主题（`BUG.md` L-1353 的自证用例）。 */
    @Test
    fun 无界面判定要认透明主题不认对话框() {
        val m = listOf(
            """<activity android:name=".A" android:theme="@android:style/Theme.Translucent.NoTitleBar" />""",
            """<activity android:name=".B" android:theme="@android:style/Theme.DeviceDefault.Dialog" />""",
            """<activity android:name=".C" />""",
        ).joinToString("\n")
        assertEquals("透明主题 + 白名单算无界面，Dialog 不算", setOf(".A", ".C"), headlessActivities(m, setOf(".C")))
    }

    /**
     * 索引行编号不得重复（块的唯一性由 `块编号必须全册唯一` 把守；行这边原先没有守卫）。
     *
     * 两个会话同时补同一批行时会静默出现两份，而计数守卫只对拍**总数** ⇒ 137 行里有两行同号也照样绿
     * （2026-10-13 补：当天两处「有块无行」刚被补上，正好是需要这条的时刻）。
     */
    @Test
    fun 索引行编号不得重复() {
        val part = ledgerPartFiles()["index"] ?: error("索引分册缺失（见 BUG.md 的 0.6 详情文件地图）")
        // 用 `[|]` 而不是 `\|`：后者紧挨 `"""` 时会被写成转义引号，正则永不匹配 ⇒ 守卫静默恒绿（BUG.md L-1348）
        val ids = Regex("""(?m)^[|] (L-\d+) [|]""").findAll(part).map { it.groupValues[1] }.toList()
        assertTrue("索引行正则没取到编号（写法变了？行数=${ids.size}）", ids.isNotEmpty())
        val dup = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("索引行编号重复（同一编号只能有一行）：$dup", dup.isEmpty())
    }

    /**
     * 字表 / 音节表的条数必须与 `assets` 里的 `*.xz` 实测一致（README 与功能总览都对外写这几数）。
     *
     * 手法同 `SimpTradWordBackTest.资产条数必须与公开文档一致`：**先在测试里数资产**，再要求文档写出同一个数。
     * ⚠ 计数口径必须与运行期一致（`RareCharsFilterTest` 的 `isHan`）：**扩展 A（0x3400–0x4DBF）+ 基本区
     * （0x4E00–0x9FFF）+ 非 BMP（0x20000–0x3FFFF）** —— 只数基本区会漏扩展 A 的字，把**正确**的文档数
     * 判成漂移（2026-10-13 实测踩过一次：误判 5,613 → 5,606）。
     */
    @Test
    fun 字表与音节表条数必须与资产一致() {
        fun assetText(name: String): String {
            val file = listOf("src/main/assets", "app/src/main/assets")
                .map { File("$it/$name.txt.xz") }.firstOrNull { it.isFile }
                ?: error("未找到 $name.txt.xz（路径或改名变了？）")
            return XZInputStream(file.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        }

        fun commafy(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
        fun stripped(name: String) = assetText(name).lineSequence().filterNot { it.trimStart().startsWith("#") }

        fun isHan(cp: Int): Boolean =
            cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0x20000..0x3FFFF

        /** 按**码点**收集汉字（不能用 `[\u4e00-\u9fff]` 正则：非 BMP 是代理对，且扩展 A 在基本区之外） */
        fun hanCodes(name: String): List<Int> {
            val out = ArrayList<Int>()
            for (line in stripped(name)) {
                var i = 0
                while (i < line.length) {
                    val cp = line.codePointAt(i)
                    i += Character.charCount(cp)
                    if (isHan(cp)) out.add(cp)
                }
            }
            return out
        }

        val tiers = listOf("common_chars", "tier2_chars", "tier3_chars").map { hanCodes(it).distinct().size }
        val chars = hanCodes("pinyin_chars")
        val readings = chars.size
        val distinct = chars.distinct().size
        val syllables = stripped("pinyin_syllables").joinToString(" ").split(Regex("""\s+"""))
            .count { it.matches(Regex("""[a-z]+""")) }

        val readme = readDoc("README.md") ?: error("找不到 README.md")
        assertTrue("README 缺常用字字表条数（资产实测 ${commafy(tiers[0])}）", "常用字 ${commafy(tiers[0])}" in readme)
        assertTrue("README 缺档 2 字表条数（资产实测 ${commafy(tiers[1])}）", "${commafy(tiers[1])} 字" in readme)
        assertTrue("README 缺档 3 字表条数（资产实测 ${commafy(tiers[2])}）", "${commafy(tiers[2])} 字" in readme)
        assertTrue("README 缺单字表字数（资产实测 ${commafy(distinct)}）", "${commafy(distinct)} 字" in readme)
        assertTrue("README 缺单字表读音数（资产实测 ${commafy(readings)}）", "${commafy(readings)} 条读音" in readme)
        assertTrue("README 缺合法音节数（资产实测 $syllables）", "$syllables 个" in readme)
        val overview = readDoc("功能总览.md") ?: error("找不到 功能总览.md")
        assertTrue("功能总览缺常用字字表条数（资产实测 ${commafy(tiers[0])}）", "常用字 ${commafy(tiers[0])}" in overview)
        assertTrue("功能总览缺档 2 字表条数（资产实测 ${commafy(tiers[1])}）", "${commafy(tiers[1])} 字" in overview)
        assertTrue("功能总览缺档 3 字表条数（资产实测 ${commafy(tiers[2])}）", "${commafy(tiers[2])} 字" in overview)
        // 反向 stray（A-270 余留的一半）：`常用字` 是**独有**声明位（不像 `N 字` 那样过泛、会误命中
        // 无关数字），故可安全全量扫描 —— 两文档里任何一处 `常用字 <数字>` 都必须是当前值，
        // 「改一处漏一处」即红
        for ((name, doc) in listOf("README.md" to readme, "功能总览.md" to overview)) {
            val stray = Regex("""常用字\s*([\d,]+)""").findAll(doc).map { it.groupValues[1] }
                .filter { it != commafy(tiers[0]) }.toSet()
            assertTrue(
                "$name 的「常用字」写着非当前值 ${stray.sorted().joinToString("/")}（源码 ${commafy(tiers[0])}）",
                stray.isEmpty(),
            )
        }
    }

    /**
     * 块编号必须**全册唯一**（2026-10-12 复核发现 30 处撞号：剪贴板族复用了音效族已占的
     * `X-295`…`X-323`，按编号检索会得到两条不同内容；那批已改号为 `X-470`…`X-499`）。
     *
     * 编号不复用是 `0.5` 的规则，但过去只有「取最大编号 +1」靠自觉 —— 撞号之后引用、
     * 同族簇、索引对拍全都会指不准。`L-` / `X-` / `B-` 三族各自独立编号。
     */
    @Test
    fun 块编号必须全册唯一() {
        val seen = HashMap<String, String>()
        val bad = ArrayList<String>()
        for ((name, text) in ledgerPartFiles()) {
            for (m in Regex("""(?m)^#{3,4} ([LXB]-\d+) ·""").findAll(text)) {
                val id = m.groupValues[1]
                val prev = seen.put(id, name)
                if (prev != null) bad.add("$id（$prev 与本册都用了）")
            }
        }
        assertEquals("台账块编号重复（新条目取未用过的号，见 0.5 ①）：$bad", 0, bad.size)
    }

    /** 台账**入口**（`BUG.md`：协议 + 待办 + 索引 + front-matter），工作目录不同时回退上一级。 */
    private fun ledger(): String =
        listOf("BUG.md", "../BUG.md").map(::File).firstOrNull { it.isFile }?.readText()
            ?: error("找不到 BUG.md（测试工作目录变了？）")

    /**
     * 台账**全文** = 入口 + 六个详情分册（`BUG.md` 的 0.6 详情文件地图）。
     *
     * 块计数与节标题查找都在全文上做：新增条目块无论落在哪个分册都算数，
     * 而入口自身的行数不影响计数（口径见 [台账计数须与正文一致]）。
     * 分册放在 `.wwlia-handoff/ledger/`（该目录已 gitignore）。
     */
    private fun ledgerAll(): String = ledgerPartFiles().values.joinToString("\n")

    /** 「入口 + 六个分册」的原文（键 = 分册基名 `medium` / `low` / …，`BUG.md` 用键 `entry`）。 */
    private fun ledgerPartFiles(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        (listOf("entry" to "BUG.md") + LEDGER_PARTS.map { it to ".wwlia-handoff/ledger/$it.md" })
            .forEach { (name, rel) -> readDoc(rel)?.let { m[name] = it } }
        // 入口 + 六个分册一个都不能少：只断言「至少 N 个」时，删掉几个分册仍会在更小的集合上
        // 自洽（计数、页眉、声明数全绿），BUG.md L-1152
        val expected = setOf("entry") + LEDGER_PARTS
        assertEquals(
            "台账分册缺失（结构见 BUG.md 的 0.6 详情文件地图）：缺 ${expected - m.keys}",
            expected, m.keys,
        )
        return m
    }

    /** 读一份本机文档（工作目录不同时回退上一级）；文件不在返回 null。 */
    private fun readDoc(rel: String): String? =
        listOf(File(rel), File("../$rel")).firstOrNull { it.isFile }?.readText()

    /**
     * 约定文件 + 明细分册（2026-09-29 起 工程约定 只留红线、入口与计数真值，
     * 排查方法 / 词库 / 冷启动 / 测试逐类清单等整体外移到 `.wwlia-handoff/memory/agents-extras*.md`）。
     */
    private fun conventionWithDetails(): String {
        val extras = listOf(
            ".wwlia-handoff/memory/agents-extras.md",
            ".wwlia-handoff/memory/agents-extras-2.md",
        ).mapNotNull(::readDoc)
        assertTrue("明细分册缺失（工程约定 的 `已外移的细节` 指的就是它们）", extras.size >= 2)
        return convention() + "\n" + extras.joinToString("\n")
    }

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

    /** 台账详情六册（顺序与 `BUG.md` 的 0.6 详情文件地图一致）。 */
    /**
     * 台账分册（顺序 = 读取顺序）：`index` 是 2026-09-29 从入口外移的索引表，其余六册是详情。
 */
    private val LEDGER_PARTS = listOf("index", "medium", "low", "excluded", "fixed", "history", "verify")

    private fun testFile(name: String): File =
        listOf(
            File("src/test/java/com/jinn/inputmethod/$name.kt"),
            File("app/src/test/java/com/jinn/inputmethod/$name.kt"),
        ).first()
}
