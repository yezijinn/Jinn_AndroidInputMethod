package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 符号表数据护栏。
 *
 * 符号层是纯数据表（[SYMBOL_GROUPS]），此前没有任何测试引用它，这类「人工录入」的表
 * 恰恰最容易出静默错误：历史上「标点」组第 2 页的 g 键被误录成 `"〈~"`，上屏会多出一个孤立波浪号。
 *
 * 注意（踩过的坑）：不要给取值加「长度 1~2」这类看似合理的规则，本表里
 * 「数学」组有 `∫∫∫`、动态「变量」组上屏的是整条时间串（如 `2026年9月24日13点50分28秒`），
 * 长度规则会把正确数据判成错误。护栏只锁「由设计保证、且与录入事故直接相关」的性质。
 *
 * 「全角 / 半角」两组由原「常用」组拆分而来：全角组只放全角符号、半角组只放半角符号，
 * 宽度归属由本测试按「是否 ASCII」钉死（`·—…` 这类通用标点非 ASCII，计入全角组）；
 * 两组还都禁数字（数字走数字层）且不得混入字母/汉字。
 * 「变量」组是动态取值（见 [DynamicSymbols]），其数据护栏在 `DynamicSymbolsTest`。
 */
class SymbolLayoutTest {

    @Test
    fun 取值不得包含换行或制表符() {
        val bad = mutableListOf<String>()
        for (group in SYMBOL_GROUPS) {
            for ((pageIndex, page) in group.pages.withIndex()) {
                for ((key, value) in page) {
                    if (value.any { it == '\n' || it == '\r' || it == '\t' }) {
                        bad.add("${group.label}组第${pageIndex + 1}页 $key")
                    }
                }
            }
        }
        assertEquals("符号取值含换行/制表符（录入事故或转义写错）: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 每个分组至少有一页且每页非空() {
        for (group in SYMBOL_GROUPS) {
            assertTrue("分组「${group.label}」没有页", group.pages.isNotEmpty())
            for ((i, page) in group.pages.withIndex()) {
                assertTrue("分组「${group.label}」第${i + 1}页为空", page.isNotEmpty())
            }
        }
    }

    @Test
    fun 分组标签唯一() {
        val labels = SYMBOL_GROUPS.map { it.label }
        assertEquals("分组标签有重复: $labels", labels.size, labels.toSet().size)
    }

    @Test
    fun 全角半角紧随其后于变量之前() {
        // 用户要求的固定顺序：全角 半角 变量 ……（原来只有一个混装的「常用」组；
        // 第三组原为「编程」，2026-09-24 按用户要求改为「变量」）
        assertEquals(listOf("全角", "半角", "变量"), SYMBOL_GROUPS.take(3).map { it.label })
    }

    @Test
    fun 全角组不得混入半角字符() {
        // 「全角」与「半角」是按字符宽度拆开的两个组：ASCII 属于半角组，混进来等于同一字符
        // 在两组各有一份，用户按宽度选组就失去意义（且旧「常用」组正是这么混装的）。
        val full = SYMBOL_GROUPS.first { it.label == "全角" }
        val bad = full.pages.withIndex().flatMap { (i, page) ->
            page.filterValues { v -> v.any { it.code < 0x80 } }.map { (k, v) -> "第${i + 1}页 $k=$v" }
        }
        assertEquals("「全角」组混入了半角字符（ASCII，应归「半角」组）: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 半角组不得混入全角字符() {
        val half = SYMBOL_GROUPS.first { it.label == "半角" }
        val bad = half.pages.withIndex().flatMap { (i, page) ->
            page.filterValues { v -> v.any { it.code >= 0x80 } }.map { (k, v) -> "第${i + 1}页 $k=$v" }
        }
        assertEquals("「半角」组混入了非 ASCII 字符（应归「全角」组）: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 全角半角两组不得含数字() {
        // 用户要求：两组都全局禁用数字（含全角数字 ０-９），数字走数字层/数字键盘。
        // 原先「半角」组第 1 页带着 1~0 十个数字，就是这条规则要清掉的历史遗留。
        val bad = listOf("全角", "半角").flatMap { label ->
            val group = SYMBOL_GROUPS.first { it.label == label }
            group.pages.withIndex().flatMap { (i, page) ->
                page.filterValues { v -> v.any { it.isDigit() } }
                    .map { (k, v) -> "$label 第${i + 1}页 $k=$v" }
            }
        }
        assertEquals("「全角 / 半角」组混入了数字（数字不在符号层）: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 全角半角两组不得混入字母或汉字() {
        // 用户要求：两组「只放符号，不得混入任何其他类型」。全角字母（Ａ）与汉字都算混入。
        val bad = listOf("全角", "半角").flatMap { label ->
            val group = SYMBOL_GROUPS.first { it.label == label }
            group.pages.withIndex().flatMap { (i, page) ->
                page.filterValues { v -> v.any { it.isLetter() } }
                    .map { (k, v) -> "$label 第${i + 1}页 $k=$v" }
            }
        }
        assertEquals("「全角 / 半角」组混入了字母或汉字: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 符号页必须先放满26键再翻页() {
        // 用户要求（2026-09-21）：一页 26 个符号，第 1 页必须先放满，剩余的才进下一页
        //，不允许「16+16」这种半空页。这也是全表既有规范（其余分组的非末页都是 26 键），
        // 此前只有「半角」组违反（两页各 16 键），本次修正后用本用例守住。
        val bad = SYMBOL_GROUPS.flatMap { group ->
            group.pages.withIndex().mapNotNull { (i, page) ->
                when {
                    page.size > 26 -> "${group.label} 第${i + 1}页超 26 键（${page.size}）"
                    i < group.pages.lastIndex && page.size != 26 ->
                        "${group.label} 第${i + 1}页未满 26 键（${page.size}）"
                    else -> null
                }
            }
        }
        assertEquals("符号页违反「先放满 26 键再翻页」: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 全角与半角两组取值零重合() {
        // 用户策略（2026-09-21）：半角组只留 ASCII，同一个符号不得在两组各有一份，
        // 否则「按宽度选组」失去意义（`° ± × ÷ ℃` 这类无全/半角之分的符号统一放「全角」组）。
        val full = SYMBOL_GROUPS.first { it.label == "全角" }.pages.flatMap { it.values }.toSet()
        val half = SYMBOL_GROUPS.first { it.label == "半角" }.pages.flatMap { it.values }.toSet()
        assertEquals("「全角 / 半角」两组出现重合取值: ${full intersect half}", emptySet<String>(), full intersect half)
    }

    @Test
    fun 半角组顺序与全角组逐项对应() {
        // 用户要求（2026-09-21）：半角组「尽量按与全角对应的顺序」排，全角第 1 个是逗号，
        // 半角第 1 个也必须是逗号。判据：每个半角符号的全角对应（取全角组首次出现者）
        // 在全角序列里的下标单调递增；没有全角对应的（`$`）跳过，且只允许排在末尾。
        val full = SYMBOL_GROUPS.first { it.label == "全角" }.pages.flatMap { it.values }
        val half = SYMBOL_GROUPS.first { it.label == "半角" }.pages.flatMap { it.values }
        val toFull = mapOf(
            ',' to "，", '.' to "。", ';' to "；", ':' to "：", '?' to "？", '!' to "！",
            '"' to "“", '\'' to "‘", '(' to "（", ')' to "）", '<' to "《", '>' to "》",
            '[' to "【", ']' to "】", '~' to "～", '+' to "＋", '-' to "－", '=' to "＝",
            '%' to "％", '&' to "＆", '*' to "＊", '@' to "＠", '#' to "＃", '/' to "／",
            '\\' to "＼", '|' to "｜", '{' to "｛", '}' to "｝", '`' to "｀", '_' to "＿",
            '^' to "＾",
        )
        var lastFullIndex = -1
        val bad = mutableListOf<String>()
        for ((i, item) in half.withIndex()) {
            val fullChar = toFull[item.single()]
            if (fullChar == null) {
                if (i != half.lastIndex) bad += "「$item」无全角对应却不在末尾（第 ${i + 1} 位）"
                continue
            }
            val idx = full.indexOf(fullChar)
            if (idx < 0) bad += "「$item」的全角对应「$fullChar」不在全角组"
            else if (idx <= lastFullIndex) bad += "「$item」全角下标 $idx 未递增（上一个 $lastFullIndex）"
            else lastFullIndex = idx
        }
        assertEquals("半角组顺序未与全角对应: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 变量组按用户清单给全且取值全为动态() {
        // 用户要求（2026-09-24 第二次修订）：变量组按指定清单排列，去掉 IPV4 / IPV6。
        // 钉住「组内每个取值都是动态标记」：漏标记会把短名当符号直接上屏；
        // 键面短名与展开逻辑的护栏见 `DynamicSymbolsTest`。
        val group = SYMBOL_GROUPS.first { it.label == "变量" }
        val bad = group.pages.withIndex().flatMap { (i, page) ->
            page.filterValues { !DynamicSymbols.isDynamic(it) }.map { (k, v) -> "第${i + 1}页 $k=$v" }
        }
        assertEquals("「变量」组出现了静态取值: $bad", emptyList<String>(), bad)
        val labels = group.pages.flatMap { it.values }.map(DynamicSymbols::labelOf)
        assertEquals(
            "变量组应与用户清单逐项一致（键面文本与顺序都按清单）",
            listOf(
                "星期", "农历", "季度", "财年", "生肖", "天数", "周数", "分辨率", "时间戳", "毫秒戳",
                "年日中", "年日数", "年日符", "时秒中", "时秒数", "时秒符", "长时中", "长时数", "长时符",
            ),
            labels,
        )
    }

    @Test
    fun 页内统一字号取最宽标签() {
        // 符号层按本页最宽标签算字号（`PinyinKey.uniformMeasureText`）⇒ 同页所有键同号，
        // 不会出现用户反馈的「2 字大、4 字小」。宽度模型：ASCII ≈0.5em（记 1）、其余 ≈1em（记 2）。
        assertEquals("日期连字", widestSymbolLabel(listOf("日期", "日期连字", "年月")))
        assertEquals("年月日", widestSymbolLabel(listOf("日期", "年月日")))
        assertEquals("return", widestSymbolLabel(listOf("if", "return", "int")))
        assertEquals("", widestSymbolLabel(emptyList()))
        // 回归：`\`（2 个 ASCII≈1em）与 `……`（2 个全角≈2em）曾按旧模型判成同宽，
        // 基准落到窄的一条 ⇒ 全角/标点页的 `……`、`——` 被键面裁掉两端
        assertEquals("……", widestSymbolLabel(listOf("\\", "……")))
        assertEquals("……", widestSymbolLabel(listOf("，", "……", "——")))
        // 每页都得能取到基准，否则该页字号退化成按各键自身标签算（又回到一大一小）
        val bad = SYMBOL_GROUPS.filter { group ->
            group.pages.any { page ->
                widestSymbolLabel(page.values.map(DynamicSymbols::labelOf)).isEmpty()
            }
        }
        assertEquals("有符号页取不到统一字号基准: ${bad.map { it.label }}", emptyList<String>(), bad.map { it.label })
        // 逐页属性：基准不得比本页任何标签更窄（估偏大只是字号略小，估偏小会裁切文字）
        val tooNarrow = SYMBOL_GROUPS.flatMap { group ->
            group.pages.mapIndexedNotNull { index, page ->
                val labels = page.values.map(DynamicSymbols::labelOf)
                val pick = widestSymbolLabel(labels)
                val widest = labels.maxByOrNull(::fontUnits)
                if (widest != null && fontUnits(pick) < fontUnits(widest)) {
                    "${group.label}第${index + 1}页 基准=$pick < 最宽=$widest"
                } else {
                    null
                }
            }
        }
        assertEquals("基准比最宽标签窄（该页文字会被裁切）: $tooNarrow", emptyList<String>(), tooNarrow)
    }

    /** 字体宽度模型：ASCII ≈0.5em（记 1）、其余 ≈1em（记 2）；与 `widestSymbolLabel` 同源 */
    private fun fontUnits(s: String): Int =
        s.count { it.code < 0x80 } + 2 * s.count { it.code >= 0x80 }

    @Test
    fun 标点组第二页g键是单个左尖括号() {
        // 回归：该键曾误录为 "〈~"（多一个波浪号）
        val punct = SYMBOL_GROUPS.first { it.label == "标点" }
        assertTrue("标点组至少应有 2 页", punct.pages.size >= 2)
        assertEquals("〈", punct.pages[1]['g'])
    }

    @Test
    fun 特殊组天气页不得混入汉字() {
        // 回归：「特殊」组第 2 页（天气 / 天文 / 行星符号）的 k、l 键曾被误录成汉字
        // 「由」「白」， 与本页其余条目（☀ ☁ ❄ ☿ ♄ ♁ ☛ …）完全不是一类。
        // 字符集错位不会引发任何编译或运行错误，只有真机上屏才会暴露，故用护栏钉住。
        val special = SYMBOL_GROUPS.first { it.label == "特殊" }
        assertTrue("特殊组至少应有 2 页", special.pages.size >= 2)
        val page = special.pages[1]
        val cjk = page.filterValues { v -> v.any { it.code in 0x4E00..0x9FFF } }
        assertEquals("特殊组第 2 页混入了汉字（应为本页同类的符号）: $cjk", emptyMap<Char, String>(), cjk)
        assertEquals("♃", page['k'])
        assertEquals("♅", page['l'])
    }
}
