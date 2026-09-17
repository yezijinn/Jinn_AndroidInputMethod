package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高频子集 + 全量并入 的**一致性测试**。
 *
 * 冷启动优化把词库拆成两段加载：先装高频子集（秒级可打字），再后台把全量基础包 merge 进来。
 * 这个设计成立的前提是：**并入后的候选与「一次性全量加载」完全一致**——否则用户会看到
 * 「刚开机打出来的候选和过几秒后不一样」这种漂移。
 *
 * 该前提由本文件钉住：子集取自同一份源、且是每个键的高频前缀，
 * 而 [PinyinEngine] 的 merge 语义是「已有在前 + 去重追加」，因此结果必然一致。
 */
class HotDictMergeTest {

    private val chars = "ni\t你,尼,泥,拟\nhao\t好,号,毫,豪\n"

    private val syllables = "ni\nhao\nma\n"

    /** 全量词库：同一键下 4 个词（按词频降序） */
    private val fullPhrases = "nihao\t你好|你号|尼豪|泥毫\nma\t吗|嘛\n"

    /** 高频子集：取全量里每个键的前 2 个词（模拟「按词频取前 N 条」的产物） */
    private val hotPhrases = "nihao\t你好|你号\n"

    private fun prepare() {
        PinyinEngine.resetForTest()
    }

    @Test
    fun 子集先行再并入全量_候选与全量单载完全一致() {
        prepare()
        PinyinEngine.loadFromTexts(chars, fullPhrases, syllables)
        val byFull = PinyinEngine.query("nihao").candidates.toList()
        val byFullMa = PinyinEngine.query("ma").candidates.toList()

        prepare()
        PinyinEngine.loadFromTexts(chars, hotPhrases, syllables)   // 高频子集（第一段）
        PinyinEngine.loadPhrasesText(fullPhrases, merge = true)    // 全量并入（第二段）
        val byHot = PinyinEngine.query("nihao").candidates.toList()
        val byHotMa = PinyinEngine.query("ma").candidates.toList()

        assertEquals("并入后候选顺序必须与全量单载一致", byFull, byHot)
        assertEquals(byFullMa, byHotMa)
        assertTrue("全量并入应补齐子集没有的词", byHot.contains("尼豪"))
    }

    @Test
    fun 子集阶段只给子集里的候选() {
        prepare()
        PinyinEngine.loadFromTexts(chars, hotPhrases, syllables)
        val hot = PinyinEngine.query("nihao").candidates.toList()
        assertTrue(hot.contains("你好"))
        assertTrue("子集阶段不应凭空出现全量词", !hot.contains("尼豪"))
    }

    @Test
    fun 并入不产生重复候选() {
        prepare()
        PinyinEngine.loadFromTexts(chars, hotPhrases, syllables)
        // 二次并入同一份全量（模拟 merge 被重复触发）
        PinyinEngine.loadPhrasesText(fullPhrases, merge = true)
        PinyinEngine.loadPhrasesText(fullPhrases, merge = true)
        val list = PinyinEngine.query("nihao").candidates.toList()
        assertEquals("候选不应有重复项：$list", list.size, list.toSet().size)
    }

    /**
     * 全量基础包以**索引**形式到位后，子集窗口内查过的键必须能重新查到（合并缓存要失效）。
     *
     * 真机路径：高频子集先就绪（`loaded=true`），1~2 秒后 `loadIndex()` 才把 `baseIndex` 装上
     * ——**它不经过 `loadPhrasesReader`**，因此必须自己让 `mergedCache` 失效。
     * 否则这段时间查过的键会一直返回旧答案：查不到的继续查不到（缓存了「确实没有」），
     * 查得到的停在截断列表上，直到可选包加载才被清掉（20~180s 之后）。
     */
    @Test
    fun 全量索引到位后_子集窗口内查过的键必须重新查得到() {
        val syl = "ni\nhao\nqie\n"
        val ch = "ni\t你,尼\nhao\t好,号\nqie\t切,且\n"
        val hot = "nihao\t你好|你号\n"                                  // 子集里没有 qie 键
        val full = "nihao\t你好|你号|尼豪|泥毫\nqie\t切|企鹅\n"              // 全量（索引）

        prepare()
        // 第一段：子集就绪、全量仍在后台加载 —— 此刻用户打了 qie（子集没有这个键）
        PinyinEngine.loadFromTexts(ch, hot, syl)
        val early = PinyinEngine.query("qie").candidates.toList()
        assertTrue("子集阶段 qie 只应有单字候选: $early", !early.contains("企鹅"))

        // 第二段：全量以索引形式到位（与真机 loadIndex 同一条链路）
        PinyinEngine.loadFromIndexBytes(PhraseIndex.build(full.lineSequence(), 1L), ch, syl, null)
        val late = PinyinEngine.query("qie").candidates.toList()
        assertTrue(
            "索引到位后 qie 必须能查到「企鹅」（合并缓存未失效会一直沿用「没有」）: $late",
            late.contains("企鹅"),
        )
    }
}
