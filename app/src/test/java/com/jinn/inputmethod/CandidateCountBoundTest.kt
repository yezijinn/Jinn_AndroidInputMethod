package com.jinn.inputmethod

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 候选数量的量级边界测试 —— 守护「渲染必须截断」这个前提。
 *
 * 候选栏渲染是「每条一个 TextView」且每次按键全量重建。
 * 引擎侧单字候选上限为 MAX_CHARS(60)，而真实单字表里 `yi` 有 326 字、
 * 93 个音节超过 60 字，即常用音节经常给出满额候选。
 * 因此 `PinyinKeyboardView` 只渲染前 `MAX_RENDERED_CANDIDATES` 条。
 *
 * 这里锁定「候选可达数十条」：若将来有人以为候选总是个位数、
 * 把渲染截断当冗余代码删掉，测试会先挂。
 */
class CandidateCountBoundTest {

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
        // 模拟真实单字表的量级：常用音节下挂着大量单字
        // （真实数据：yi 326 字、ni 数十字，93 个音节超过引擎的 MAX_CHARS=60）
        // ⚠ 条目必须是**单个字符**：这是单字表，加载期会丢掉多字符 token
        // （早先用 `字1` 这类两字符串条目，与真实表不符，长度判据补齐后被挡下）。
        val manyChars = (0x4E00..0x4E45).joinToString(",") { it.toChar().toString() }
        PinyinEngine.loadFromTexts(
            chars = """
                ni	$manyChars
                hao	$manyChars
            """.trimIndent(),
            phrases = "nihao\t你好|拟好",
            syllables = """
                ni
                hao
            """.trimIndent(),
        )
    }

    /** 单音节的单字候选可达引擎上限（60），证明渲染层必须截断 */
    @Test
    fun singleSyllableCanReachEngineCharLimit() {
        val result = PinyinEngine.query("ni")
        assertTrue(
            "单字候选应可达数十条量级，实际=${result.candidates.size}",
            result.candidates.size >= 60,
        )
    }

    /** 多音节输入时，词候选之外还会叠加各音节单字，总量更大 */
    @Test
    fun multiSyllableAccumulatesCandidates() {
        val single = PinyinEngine.query("ni").candidates.size
        val multi = PinyinEngine.query("nihao").candidates.size
        assertTrue(
            "多音节候选($multi)不应少于单音节($single)",
            multi >= single,
        )
    }

    /** 候选里的词条应排在单字之前（用户实际点的是前面几条） */
    @Test
    fun phraseCandidatesComeBeforeChars() {
        val result = PinyinEngine.query("nihao")
        val phraseIdx = result.candidates.indexOf("你好")
        assertTrue("词候选应存在，实际=${result.candidates.take(5)}", phraseIdx >= 0)
        assertTrue(
            "词候选应排在前部（否则渲染截断会把它切掉），实际 index=$phraseIdx",
            phraseIdx < MAX_REASONABLE_HEAD,
        )
    }

    private companion object {
        /** 渲染截断后用户仍能看到的位置范围（词候选必须落在这里面） */
        const val MAX_REASONABLE_HEAD = 10
    }
}
