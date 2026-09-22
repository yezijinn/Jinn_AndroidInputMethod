package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 生僻字过滤测试（纯 JVM，用 [PinyinEngine.loadFromTexts] 注入）。
 *
 * 判定标准：《通用规范汉字表》一级(3500) + 二级(3000) 为常用字，
 * 三级(1605) 及表外字为生僻字。默认不加载生僻字：
 *  - 生僻单字不进单字表，也就不进候选；
 *  - 含生僻字的词整条丢弃。
 *
 * 关键点是「加载时过滤」而非「加载后过滤」，被跳过的数据从未进入 HashMap，
 * 这才是真正降低内存占用的原因，本测试守住该行为。
 */
class RareCharsFilterTest {

    private val syllables = """
        qi
        e
        qie
        tao
        tie
        taotie
    """.trimIndent()

    /** 单字表：常用字与生僻字混排 */
    private val chars = """
        qi	企,乞,起
        e	鹅,额,饿,噩
        qie	且,切
        tao	饕,涛,掏
        tie	餮,铁
    """.trimIndent()

    /** 词表：「企鹅」全常用字；「饕餮」全生僻字；「涛涛」与饕餮同键但全常用字 */
    private val phrases = """
        qie	企鹅|切
        taotie	饕餮|涛涛
    """.trimIndent()

    /** 常用字表：刻意不含「饕 / 餮 / 噩」 */
    private val commonChars = "企乞起鹅额饿且切涛掏铁"

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
    }

    @Test
    fun withoutFilter_rareContentIsAvailable() {
        PinyinEngine.loadFromTexts(chars, phrases, syllables)
        assertTrue(
            "未启用过滤时「企鹅」应可用",
            PinyinEngine.query("qie").candidates.contains("企鹅"),
        )
        assertTrue(
            "未启用过滤时「饕餮」应可用",
            PinyinEngine.query("taotie").candidates.contains("饕餮"),
        )
    }

    @Test
    fun withFilter_rareWordIsDropped() {
        PinyinEngine.loadFromTexts(chars, phrases, syllables, commonChars)
        assertTrue(
            "常用字构成的「企鹅」应保留",
            PinyinEngine.query("qie").candidates.contains("企鹅"),
        )
        val got = PinyinEngine.query("taotie").candidates
        assertFalse("含生僻字的「饕餮」应被过滤: $got", got.contains("饕餮"))
        assertTrue("同键的常用词「涛涛」应保留: $got", got.contains("涛涛"))
    }

    @Test
    fun withFilter_rareSingleCharIsDropped() {
        PinyinEngine.loadFromTexts(chars, phrases, syllables, commonChars)
        val got = PinyinEngine.query("tao").candidates
        assertFalse("生僻单字「饕」应被过滤: $got", got.contains("饕"))
        assertTrue("常用字「涛」应保留: $got", got.contains("涛"))
    }
}
