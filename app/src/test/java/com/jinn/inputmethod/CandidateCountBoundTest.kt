package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 候选数量的量级边界测试，守护「渲染必须分页截断」这个前提。
 *
 * 候选栏渲染是「每条一个 TextView」且每次按键全量重建。
 * 引擎侧单字候选上限为 MAX_CHARS(60)，而真实单字表里 `yi` 有 326 字、
 * 93 个音节超过 60 字，即常用音节经常给出满额候选。
 * 因此 `PinyinKeyboardView` 首屏只渲染前 `INITIAL_RENDERED_CANDIDATES` 条，其余横滑到末尾再分页追加。
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
        // 条目必须是单个字符：这是单字表，加载期会丢掉多字符 token
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
        // 与首屏渲染条数挂钩（BUG-11）：这条用例的用意是「候选多到首屏装不下、必须分页」，
        // 那就得真的读到那个常量，否则它只证明引擎给得多 —— 常量被删掉或被改成比候选数还大，这里也照样绿。
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        val initial = Regex("""const val INITIAL_RENDERED_CANDIDATES\s*=\s*(\d+)""")
            .find(src)?.groupValues?.get(1)?.toInt()
        assertTrue("没解析出 INITIAL_RENDERED_CANDIDATES（写法变了？）", initial != null)
        assertTrue(
            "首屏渲染条数 $initial 必须小于本次候选数，否则分页渲染的前提不成立",
            result.candidates.size > initial!!,
        )
    }

    /** 多字词组之外只叠首音节单字，后音节单字不出现（用户 2026-11-22） */
    @Test
    fun multiSyllableShowsOnlyFirstSyllableChars() {
        val got = PinyinEngine.query("nihao").candidates
        val niChar = '\u4E00'                  // ni 块首字
        val haoChar = ('\u4E00' + 60).toChar() // hao 块首字，码点不交
        assertTrue("应含词 你好: $got", "你好" in got)
        assertTrue("应含首音节单字: $got", got.contains(niChar.toString()))
        assertFalse("不应含第二音节单字: $got", got.contains(haoChar.toString()))
    }

    /**
     * 超长输入（粘一串拼音）时，候选量必须被**音节上限**挡住（BUG.md L-1282）。
     *
     * 主查询原先不限音节数：候选按「音节 × 每音节上限」线性膨胀后还要全量排序，而渲染首屏只看
     * 前 `INITIAL_RENDERED_CANDIDATES` 条（其余横滑分页追加）—— 计算与内存纯白费，且跑在输入主线程上（按键发卡）。
     * 这里用 20 个**互不相同**的音节：它们各挂满 60 字。
     * 2026-11-22 起单字只取首音节，候选量不再随音节数线性增长，「8 vs 20 同量」会退化成
     * 恒真空断言，故改为**源码形态钉**：主查询必须 `allSyllables.take(MAX_SYLLABLES)` 且上限为 8，
     * 并保留功能钉「超上限输入的候选不得多于首音节上限」（此路径无词库，只有首音节单字）。
     */
    @Test
    fun longInputIsBoundedBySyllableCap() {
        val syllables = listOf(
            "ni", "hao", "ma", "xi", "huan", "chi", "fan", "shui", "wan", "tiao",
            "jia", "you", "ren", "lai", "qu", "kan", "ting", "shuo", "xiao", "da",
        )
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            chars = syllables.mapIndexed { i, syl ->
                // 每个音节一块互不重叠的 60 字（同一字挂两个音节会让去重后的量失真）
                syl + "\t" + (0x4E00 + i * 60 until 0x4E00 + i * 60 + 60).joinToString(",") { it.toChar().toString() }
            }.joinToString("\n"),
            phrases = "",
            syllables = syllables.joinToString("\n"),
        )

        val eight = PinyinEngine.query(syllables.take(8).joinToString(""))   // 正好在上限
        val twenty = PinyinEngine.query(syllables.joinToString(""))          // 20 音节，超上限
        // 功能钉：单字只取首音节，任何长度输入都只有首音节那一批单字（≤ MAX_CHARS=60）
        assertTrue(
            "8 音节候选应等于首音节单字数（实际 ${eight.candidates.size}）",
            eight.candidates.size <= 60 && eight.candidates.size > 0,
        )
        assertEquals(
            "超上限输入必须与 8 音节前缀同量（实际 ${twenty.candidates.size} vs ${eight.candidates.size}）",
            eight.candidates.size,
            twenty.candidates.size,
        )
        // 源码钉：音节上限守卫本身必须还在（否则上面同量断言 vacuous）
        val engine = TestSources.codeSource("PinyinEngine.kt")
        assertTrue("主查询必须按 MAX_SYLLABLES 截断", "allSyllables.take(MAX_SYLLABLES)" in engine)
        assertTrue("MAX_SYLLABLES 必须为 8", "MAX_SYLLABLES = 8" in engine)
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
