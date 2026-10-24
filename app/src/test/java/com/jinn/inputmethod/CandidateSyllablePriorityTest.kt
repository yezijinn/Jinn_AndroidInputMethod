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
                nihaopengyou	你好朋友
            """.trimIndent(),
            syllables = """
                ni
                hao
                ma
                peng
                you
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

    @Test
    fun `单音节输入的补全词不得被学过的单字顶掉`() {
        // 旧判据只覆盖「输入音节数 ≥ 2」：那时单字在更低音节数组，天然排在补全词之后。
        // 单音节输入（yiq / zhongg 这类「一个完整音节 + 一截残码」）里单字与补全词同组，
        // 组内词频重排会把学过一次的单字顶到组首 —— 空格即误上屏（用户 2026-10-18 同类报告）。
        PinyinEngine.loadFromTexts(
            chars = """
                yi	一,已,以
                qi	起,齐,器
                zhong	中,种,重
                guo	国,过,果
            """.trimIndent(),
            phrases = """
                yiqi	一起|一齐
                zhongguo	中国|种过
            """.trimIndent(),
            syllables = """
                yi
                qi
                zhong
                guo
            """.trimIndent(),
        )
        repeat(1) { UserFrequency.remember("已") }
        val q = PinyinEngine.query("yiq")
        assertEquals("1 音节 + 残码：补全词必须是首候选（syl=${q.syllables} part=${q.partialSyllable}）：${q.candidates}", "一起", q.candidates.first())
        assertTrue("学过的单字不得越过补全词：${q.candidates}", q.candidates.indexOf("已") > 0)

        repeat(1) { UserFrequency.remember("中") }
        val z = PinyinEngine.query("zhongg")
        assertEquals("补全词必须是首候选：${z.candidates}", "中国", z.candidates.first())
        assertTrue("学过的单字不得越过补全词：${z.candidates}", z.candidates.indexOf("中") > 0)
        // 补全块是**整段**提到组首（不是只提第一条）：块内两条都要在学过的单字之前。
        // 只把首条提前的实现会让「中」夹在 中国 与 种过 之间，空格取首候选虽然仍对，
        // 但块被拆开、学过的单字混进块里 —— 与「补全词连续插在头部」的拼装事实不一致。
        assertTrue("补全块必须整段在学过的单字之前：${z.candidates}", z.candidates.indexOf("种过") < z.candidates.indexOf("中"))
    }

    @Test
    fun `预测词也要有读音`() {
        PinyinEngine.query("nihao")
        val preds = PinyinEngine.predict("你好")
        assertTrue("预测应含 朋友：$preds", "朋友" in preds)
        // 后缀词的读音来自「区间键 − 已选词键前缀」（nihaopengyou − nihao = pengyou）
        assertEquals("peng you", PinyinEngine.truePinyinOf("朋友"))
    }

    @Test
    fun `读音_候选用真实键_单字用字表兜底`() {
        PinyinEngine.query("nihao")
        assertEquals("词候选取候选→键", "ni hao", PinyinEngine.truePinyinOf("你好"))
        // 预测词常是长词后缀、不在候选映射里：单字必须能从字表反查出音节
        assertEquals("单字走字表兜底", "ma", PinyinEngine.truePinyinOf("吗"))
        assertEquals("查不到就空着", null, PinyinEngine.truePinyinOf("龘"))
    }
}
