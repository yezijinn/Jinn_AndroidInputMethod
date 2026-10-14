package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 单音节候选「词频混合序」（`pinyin_mix.txt.xz`）的行为守卫。
 *
 * 单音节键（qie / xian / zuan…）下同时有单字与连写双音词（企鹅 = qi+e、西安 = xi+an）。
 * 运行时不设混合表时是历史行为「词先于字」；设了之后按表交错。多音节输入一律不重排。
 *
 * 表由 `PinyinEngine.setMixOrderForTest` 注入；[PinyinEngine.loadFromTexts] 与 `@Before`
 * 都会把它复位为 null，故各用例互不污染。
 */
class PinyinMixOrderTest {

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
    }

    @Test
    fun 单音节按混合序交错词与字() {
        PinyinEngine.loadFromTexts(
            chars = "qie\t且,切,窃,怯,妾,茄",
            phrases = "qie\t企鹅",
            syllables = "qie",
        )
        // 未设混合表：历史行为「词先于字」
        assertEquals(
            listOf("企鹅", "且", "切", "窃", "怯", "妾", "茄"),
            PinyinEngine.query("qie").candidates,
        )
        // 设混合表：按表交错（企鹅落在「怯」与「妾」之间）
        PinyinEngine.setMixOrderForTest("qie\t且|切|窃|怯|企鹅|妾|茄")
        assertEquals(
            listOf("且", "切", "窃", "怯", "企鹅", "妾", "茄"),
            PinyinEngine.query("qie").candidates,
        )
    }

    @Test
    fun 有候选不在混合表内则整体不排() {
        PinyinEngine.loadFromTexts(
            chars = "qie\t且,切",
            phrases = "qie\t企鹅",
            syllables = "qie",
        )
        // 表里没有「切」⇒ 不能把未登记的候选乱序，整体保持原序（词先于字）
        PinyinEngine.setMixOrderForTest("qie\t且|企鹅")
        assertEquals(
            listOf("企鹅", "且", "切"),
            PinyinEngine.query("qie").candidates,
        )
    }

    @Test
    fun 多音节不受混合表影响() {
        PinyinEngine.loadFromTexts(
            chars = "ni\ta,b\nhao\tc,d",
            phrases = "nihao\t你好",
            syllables = "ni\nhao",
        )
        // 即便对首个音节设了表，多音节查询也不重排（混合表只服务单音节）
        PinyinEngine.setMixOrderForTest("ni\ta|b")
        assertEquals(
            listOf("你好", "a", "b", "c", "d"),
            PinyinEngine.query("nihao").candidates,
        )
    }
}
