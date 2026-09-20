package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 智能预测的生僻字过滤与「过滤后回填」护栏（纯 JVM，走**索引注入**）。
 *
 * 背景：索引路径（`PhraseIndex.collectLongerSuffixes`）直接解码后缀、不过滤，
 * 曾与运行时路径（`phrasesFor` 内的 `filterRareChars`）以及 `query` 的口径不一致 ——
 * 开启「隐藏生僻字」（默认）时预测仍可能带出生僻词并可上屏。
 *
 * 修复采用「先收集、出口统一过滤」，因此**收集上限必须给过滤留出回填空间**：
 * 若收集就卡在 MAX_PREDICTIONS(6)，一旦前几个后缀全被过滤，剩余名额无法由后续候选
 * 补上（预测会无故变少甚至为空）。本测试用「6 个生僻后缀 + 1 个常用后缀」构造该场景：
 * 收集上限回到 6 时第一条断言必红（过滤后为空），放宽后应回填出常用后缀。
 */
class PredictRareCharsTest {

    private val syllables = """
        ni
        hao
    """.trimIndent()

    /** 单字表：只给 `ni` 一个常用单字，保证查询链路可用 */
    private val chars = """
        ni	你
    """.trimIndent()

    /**
     * 索引文本（按键升序）：前 6 个键的后缀为「生僻」（甲~己 不在常用字表），
     * 最后一个 `nihaoz` 的后缀「铁」是常用字 —— 字典序上它在最后，只有放宽收集上限
     * 才能被扫到再过滤回填。
     */
    private val phrases = buildString {
        append("nihao\t你好\n")
        append("nihaob\t你好甲\n")
        append("nihaoc\t你好乙\n")
        append("nihaod\t你好丙\n")
        append("nihaoe\t你好丁\n")
        append("nihaof\t你好戊\n")
        append("nihaog\t你好己\n")
        append("nihaoz\t你好铁\n")
    }

    /** 常用字表：刻意不含「甲~己」（它们即视为生僻字） */
    private val commonChars = "你好铁"

    @After
    fun tearDown() = PinyinEngine.resetForTest()

    private fun load(withFilter: Boolean) {
        PinyinEngine.resetForTest()
        val index = PhraseIndex.build(phrases.trimEnd('\n').lineSequence(), 1L)
        PinyinEngine.loadFromIndexBytes(
            index, chars, syllables, if (withFilter) commonChars else null,
        )
        // 让 predict 拿到「词 → 键」映射（真实链路里由 query 时记录）
        PinyinEngine.query("nihao")
    }

    @Test
    fun 过滤后应由后续候选回填_而不是静默变空() {
        load(withFilter = true)
        val got = PinyinEngine.predict("你好")
        // 生僻后缀（甲~己）全部剔除；常用后缀「铁」必须被回填（旧收集上限下这里是空）
        assertEquals("应只剩常用后缀，且必须回填成功: $got", listOf("铁"), got)
        assertFalse("生僻后缀不得出现: $got", got.any { it in "甲乙丙丁戊己" })
    }

    @Test
    fun 未启用过滤时行为与旧实现一致_取字典序前六个() {
        load(withFilter = false)
        val got = PinyinEngine.predict("你好")
        // 不过滤：按扫描顺序（字典序）取前 MAX_PREDICTIONS(6) 个后缀
        assertEquals(listOf("甲", "乙", "丙", "丁", "戊", "己"), got)
    }
}
