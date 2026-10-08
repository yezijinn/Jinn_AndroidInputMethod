package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val sec = docs.substringAfter("## 冷启动与加载顺序").substringBefore("\n## ")
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
     * 对外写「约 2.6MB」而实际 2.71 MiB，用户按体积预估下载量会判错；这也是发布后最容易腐烂的一处数字
     * （每次改动都在长，而 README 没人回头改）。只在仓库根存在构建产物时判，干净检出 / CI 里没有这个包就跳过。
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
        val mib = apk.length() / 1048576.0
        for (name in listOf("README.md", "README_EN.md")) {
            val doc = readDoc(name) ?: continue
            val m = Regex("""(?i)APK[^\n]{0,12}?约?\s*~?\s*([0-9]+(?:\.[0-9]+)?)\s*MB""").find(doc)
                ?: continue
            val claimed = m.groupValues[1].toDouble()
            val actual = "%.1f".format(mib)      // 对外写的是「约 X.YMB」：取一位小数比
            assertEquals(
                "$name 写「$claimed MB」与实际 ${"%.2f".format(mib)} MiB 不符（守卫要求 X.Y 位一致；" +
                    "体积变大时请同步改 README）",
                actual, "%.1f".format(claimed),
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
            val header = Regex("""(?m)^blocks: (\d+)（""").find(text)?.groupValues?.get(1)?.toInt()
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
        val prefs = readDoc("app/src/main/java/com/jinn/inputmethod/Prefs.kt")
            ?: error("找不到 Prefs.kt")
        val settings = readDoc("app/src/main/java/com/jinn/inputmethod/SettingsActivity.kt")
            ?: error("找不到 SettingsActivity.kt")

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
        val appearance = readDoc("app/src/main/java/com/jinn/inputmethod/KeyAppearanceActivity.kt")
            ?: error("找不到 KeyAppearanceActivity.kt")
        assertTrue("KeyAppearanceActivity 里找不到「键盘内显韵母」", "键盘内显韵母" in appearance)
        assertFalse("功能总览仍在用旧控件名「键盘内嵌韵母」", "键盘内嵌韵母" in doc)

        // ③ 功能面板的清单与序号按**源码**推导（此前拿文档自己的清单对文档自己的序号 ——
        //    挪按钮、改序号都不会红，BUG.md L-1146）
        val panelSrc = TestSources.codeSource("PinyinKeyboardView.kt")
        val translateLabel = Regex("const val LABEL_TRANSLATE = \"([^\"]+)\"")
            .find(panelSrc)?.groupValues?.get(1) ?: error("PinyinKeyboardView 里找不到 LABEL_TRANSLATE")
        // 面板体：renderFunctionPanel 的 `label = …` 按出现顺序即按钮顺序（条件键也在内）
        val panelBody = panelSrc.substringAfter("private fun renderFunctionPanel()")
            .substringBefore("private fun buildFunctionButton(")
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
