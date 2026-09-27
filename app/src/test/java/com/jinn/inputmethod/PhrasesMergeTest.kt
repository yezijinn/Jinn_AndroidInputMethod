package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时并入（可选词库包）与基础索引的一致性测试。
 *
 * 内置基础包在 [PinyinEngine.baseIndex]（二进制索引），可选包下载后走
 * `loadPhrasesText(merge = true)` 并入运行时表 —— 两条来源必须在 [PinyinEngine.query] 里
 * 合并成「运行时在前、索引在后且去重」，否则用户会看到「装了词库包后候选比索引里的还少」
 * 或「同一个词出现两次」。
 *
 * 2026-09-27 起原「高频子集 + 两段式加载」退场（索引体积减半后单段加载，见 `更新日志.md`），
 * 本类只保留对**合并语义**与**合并缓存失效**的守卫；原 `HotDictAssetTest` 随资产一并删除。
 */
class PhrasesMergeTest {

    private val chars = "ni\t你,尼,泥,拟\nhao\t好,号,毫,豪\n"

    private val syllables = "ni\nhao\nma\n"

    /** 基础包（与内置索引同源）：同一键下 4 个词（按词频降序） */
    private val basePhrases = "nihao\t你好|你号|尼豪|泥毫\nma\t吗|嘛\n"

    /** 可选包：追加同键词（模拟第 2/3/4 部分里那些不在内置包中的词） */
    private val optionalPhrases = "nihao\t你毫|你豪\n"

    private fun prepare() {
        PinyinEngine.resetForTest()
    }

    @Test
    fun 并入后候选与一次性加载完全一致() {
        prepare()
        // 一次性加载用「已合并好的文本」：合并语义是「已有在前 + 去重追加」，
        // 直接把两段文本首尾相接会导致同键后者覆盖前者（那是覆盖语义，不是本用例要测的）
        val mergedPhrases = "nihao\t你好|你号|尼豪|泥毫|你毫|你豪\nma\t吗|嘛\n"
        PinyinEngine.loadFromTexts(chars, mergedPhrases, syllables)
        val byFull = PinyinEngine.query("nihao").candidates.toList()
        val byFullMa = PinyinEngine.query("ma").candidates.toList()

        prepare()
        PinyinEngine.loadFromTexts(chars, basePhrases, syllables)      // 基础包
        PinyinEngine.loadPhrasesText(optionalPhrases, merge = true)    // 可选包并入
        val byHot = PinyinEngine.query("nihao").candidates.toList()
        val byHotMa = PinyinEngine.query("ma").candidates.toList()

        assertEquals("并入后候选顺序必须与一次性加载一致", byFull, byHot)
        assertEquals(byFullMa, byHotMa)
        assertTrue("并入应补齐基础包没有的词", byHot.contains("你毫"))
    }

    @Test
    fun 并入不产生重复候选() {
        prepare()
        PinyinEngine.loadFromTexts(chars, basePhrases, syllables)
        // 二次并入同一份（模拟 merge 被重复触发 / 重复下载）
        PinyinEngine.loadPhrasesText(optionalPhrases, merge = true)
        PinyinEngine.loadPhrasesText(optionalPhrases, merge = true)
        val list = PinyinEngine.query("nihao").candidates.toList()
        assertEquals("候选不应有重复项：$list", list.size, list.toSet().size)
    }

    /**
     * 基础索引到位后，此前查过的键必须能重新查到（合并缓存要失效）。
     *
     * 真机路径：可选包 / 单字先就绪（`loaded=true`），随后 `loadIndex()` 才把 `baseIndex` 装上，
     * 它不经过 `loadPhrasesReader`，因此必须自己让 `mergedCache` 失效。
     * 否则这段时间查过的键会一直返回旧答案：查不到的继续查不到（缓存了「确实没有」），
     * 查得到的停在截断列表上，直到下一次词库变更。
     */
    @Test
    fun 基础索引到位后_此前查过的键必须重新查得到() {
        val syl = "ni\nhao\nqie\n"
        val ch = "ni\t你,尼\nhao\t好,号\nqie\t切,且\n"
        val runtime = "nihao\t你好|你号\n"                              // 运行时表里没有 qie 键
        val full = "nihao\t你好|你号|尼豪|泥毫\nqie\t切|企鹅\n"              // 基础包（索引）

        prepare()
        PinyinEngine.loadFromTexts(ch, runtime, syl)
        val early = PinyinEngine.query("qie").candidates.toList()
        assertTrue("索引未到位时 qie 只应有单字候选: $early", !early.contains("企鹅"))

        // 基础包以索引形式到位（与真机 loadIndex 同一条链路）
        PinyinEngine.loadFromIndexBytes(PhraseIndex.build(full.lineSequence(), 1L), ch, syl, null)
        val late = PinyinEngine.query("qie").candidates.toList()
        assertTrue(
            "索引到位后 qie 必须能查到「企鹅」（合并缓存未失效会一直沿用「没有」）: $late",
            late.contains("企鹅"),
        )
    }
}
