package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 候选的「音节数优先」：与输入音节数**相同**的候选必须整组排在前面（用户 2026-10-18 报告）。
 *
 * 用户词频只允许在**同音节数内部**重排；跨音节数提升会把单字（你）压到整词（你好）前面 ——
 * 这正是「学过的词反而更乱」的来源。
 */
class CandidateSyllablePriorityTest {

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts(
            chars = """
                ni	你,尼,拟
                hao	好,号,毫
                ma	吗,嘛,马
            """.trimIndent(),
            phrases = """
                nihao	你好|你号|拟好
                nihaoma	你好吗|你好美
            """.trimIndent(),
            syllables = """
                ni
                hao
                ma
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        UserFrequency.resetForTest()
    }

    @Test
    fun `学过的单字不得越过同音节数的整词`() {
        repeat(5) { UserFrequency.remember("你") }
        val got = PinyinEngine.query("nihao").candidates
        assertEquals("2 音节输入的首候选必须是 2 音节词：$got", "你好", got.first())
        assertTrue(
            "学过的单字「你」不得排在任何 2 音节词之前：$got",
            got.indexOf("你") > got.indexOf("拟好"),
        )
    }

    @Test
    fun `学过的整词仍在同音节数组内`() {
        repeat(5) { UserFrequency.remember("拟好") }
        val got = PinyinEngine.query("nihao").candidates
        assertEquals("学过的同音节词应排到组首", "拟好", got.first())
    }

    @Test
    fun `补全词与输入同组并保住首位`() {
        // 补全词的真实音节数比输入多一个（nihaom → 你好吗 是 3，输入只切出 2）。
        // 它仍要排首位：末尾残码用户还没敲完，不该按音节数被惩罚到后面去。
        repeat(5) { UserFrequency.remember("你") }
        val got = PinyinEngine.query("nihaom").candidates
        assertEquals("补全出的整词必须排在首位", "你好吗", got.first())
        assertTrue(
            "单字不得越过补全出的整词：$got",
            got.indexOf("你") > got.indexOf("你好吗"),
        )
    }
}
