package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 词级反查表「同繁体多简体」的胜者守卫（`BUG.md` L-114 / L-93 / L-94）。
 *
 * 背景：`simp_trad_words.txt` 是 `简词<TAB>繁词`，而**多个简词可能对应同一个繁词**。运行期
 * [PinyinEngine.buildWordBackMap] 只能给每个繁词留一个简词（恒等条目优先，其余**首次写入者胜**）
 * ⇒ **文件顺序就是语义**：繁体模式下选中那个候选后，折简体折到谁头上，取决于它在文件里排第几。
 *
 * 判据（2026-09-29 起）：胜者取「该简词在短语词库里的**最大词频**」最高的那个 —— 传统模式下
 * 候选转换后**去重**，同一显示形下只有排在最前（≈词频最高）的那个可见，其余副本用户根本选不到，
 * 它们的往返不可观测（`BUG.md` L-93 / L-94 的立论）。词频由 `tools/dict_builder/build_dicts.py`
 * 的 `phrase_max_freq()` 算、并写进文件顺序（组内**槽位旋转**，见该脚本注释）。
 * 之前的顺序 = STPhrases 的文件顺序（偶然量），实测 6 组里 2 组与词库口径相悖：
 *  - `六鬚鮎`：六须鲇（词频 **0**）胜 / 六须鲶（**2,855**）负 ⇒ 该词在繁体模式下学习**永不生效**；
 *  - `傢俱`：家俱（**17,050**）胜 / 家具（**18,970**）负 ⇒ 同上（词典词是「家具」）。
 *
 * ⚠ 本用例是**金表**：失败说明胜者变了（重跑生成脚本、或有人手改了资产/生成规则）。
 * 组数变化同样报红 —— 出现新的「同繁体多简体」时必须显式定一次胜者，不能让它由文件顺序偶然决定。
 */
class SimpTradWordBackTest {

    /** 词级表按**文件顺序**取出（顺序即语义，不能排序） */
    private fun orderedEntries(): List<Pair<String, String>> {
        val file = listOf("src/main/assets", "app/src/main/assets")
            .map { File("$it/simp_trad_words.txt.xz") }
            .firstOrNull { it.isFile } ?: error("未找到 simp_trad_words.txt.xz")
        // ⚠ `.xz` 必须解压后再当文本读（直接 readText() 拿到的是压缩字节）
        val text = XZInputStream(file.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        val out = ArrayList<Pair<String, String>>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.count { it == '\t' } != 1) continue
            val (s, f) = t.split("\t")
            if (s.isNotEmpty() && f.isNotEmpty()) out.add(s to f)
        }
        return out
    }

    @Test
    fun 同繁体多简体的胜者必须与词库词频一致() {
        val entries = orderedEntries()
        val back = PinyinEngine.buildWordBackMap(entries)

        // 需要显式定胜者的组（= 同繁词出现两次以上），顺序即文件里的出现顺序
        val groups = LinkedHashMap<String, MutableList<String>>()
        for ((s, f) in entries) groups.getOrPut(f) { ArrayList() }.add(s)
        val ambiguous = groups.filterValues { it.size > 1 }

        assertEquals(
            "同繁体多简体的组数变了（新增/减少 ⇒ 必须显式定一次胜者，别让文件顺序偶然决定）：$ambiguous",
            184, ambiguous.size,
        )

        // 金表：繁词 → 期望的胜者（= 词库词频最高者；括号内为实测最大词频）
        val golden = mapOf(
            "于餘曲折" to "于余曲折",   // 11,948 / 0
            "六鬚鮎" to "六须鲶",        // 0 / 2,855（原胜者六须鲇词频为 0）
            "利慾薰心" to "利欲熏心",    // 17,442 / 11,853
            "傢俱" to "家具",            // 17,050 / 18,970（原胜者家俱）
            "鉅萬" to "巨万",            // 14,097 / 0
            "李鍾郁" to "李钟郁",        // 13,822 / 0
            // 2026-09-29 往返一致补收后新增的组（每组都是「词典词 vs 异体 / 变体」）：
            "覆蓋" to "覆盖",            // 18,973 / 8,447（原胜者复盖 ⇒ 覆盖折回落到别的词上）
            "萬餘" to "万余",            // 18,914 / 0（余 是规范形，馀 是异体）
            "萬鍾" to "万钟",            // 15,939 / 0（万锺 只在表里、词典无此词）
            "茶餘飯後" to "茶余饭后",     // 18,179 / 0
        )
        for ((trad, expected) in golden) {
            val candidates = ambiguous[trad] ?: error("词表里没有「$trad」这组（生成规则变了？）")
            assertTrue("「$trad」的候选变了：$candidates", candidates.contains(expected))
            assertEquals(
                "「$trad」的反查胜者不是「$expected」（词库词频更高的那个）：$candidates ⇒ " +
                    "繁体模式下折简体折到别的词上，学习键与简体模式不一致",
                expected, back[trad],
            )
        }

        // 胜者必须**唯一**：反查表里每个繁词只有一个归属（与 buildWordBackMap 的契约一致）
        assertEquals("反查表条目数应等于繁词去重数", groups.size, back.size)
    }

    /**
     * 资产条数与**公开文档**必须一致（`BUG.md` L-81）。
     *
     * 背景：词级表从 8,075 条重建为 8,100 条时，散落在 README / AGENTS / 生成脚本说明里的旧数字没跟着改
     * （本仓是公开仓，README 的数字是外部读者唯一依据）；覆盖度那处同理（1,738 → 1,744）。
     * 这里把「以资产实测为准」钉成机械判据：**先在测试里数资产**，再要求文档里出现同一个数。
     *
     * 范围只含「描述当前状态」的四份文件；`BUG.md` / `更新日志.md` 保留历史值，不在此列。
     */
    @Test
    fun 资产条数必须与公开文档一致() {
        val count = orderedEntries().size
        assertEquals("词级表条数变了（改文档前先确认真值）：$count", 9_139, count)

        val docs = listOf(
            "README.md", "README_EN.md", "AGENTS.md", "tools/dict_builder/export_dicts.py",
        )
        for (name in docs) {
            val text = listOf(File(name), File("../$name")).firstOrNull { it.isFile }?.readText()
                ?: error("找不到文档 $name")
            assertFalse("$name 仍写着旧的 8,075 条（资产实测 $count 条）", "8,075" in text)
            assertFalse("$name 仍写着旧的 8,100 条（资产实测 $count 条）", "8,100" in text)
            assertTrue("$name 缺少当前条数 9,139（资产实测 $count 条）", "9,139" in text)
        }
        val agents = listOf(File("AGENTS.md"), File("../AGENTS.md")).first { it.isFile }.readText()
        assertFalse("AGENTS.md 仍写着旧的覆盖度 1,738", "1,738" in agents)
        assertFalse("工程约定 仍写着旧的覆盖度 1,744", "1,744" in agents)
        assertTrue("工程约定 缺少当前覆盖度 1,843", "1,843" in agents)
    }
}
