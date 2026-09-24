package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 模糊音在查询链路上的行为（不依赖 Android 资源，走 [PinyinEngine.loadFromTexts] 注入）。
 *
 * 两条最要紧的契约：
 *  1. 掩码 0（出厂默认）时不产生任何变体候选 —— 关着就必须与历史行为一致；
 *  2. 打开后变体**只补不抢**：精确词 → 变体词 → 精确单字 → 变体单字（顺序见 [PinyinEngine.query]）。
 */
class FuzzyQueryTest {

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
        val chars = """
            zang	脏,藏,赃
            zhang	张,章,掌
            na	那,拿,哪
            la	拉,啦,辣
        """.trimIndent()
        val phrases = """
            zangguo	赃果
            zhangguo	张国
        """.trimIndent()
        val syllables = """
            zang
            zhang
            guo
            na
            la
        """.trimIndent()
        PinyinEngine.loadFromTexts(chars, phrases, syllables)
    }

    @After
    fun tearDown() {
        // 引擎是单例：掩码与词库都必须复位，否则会污染同一 JVM 内的其它测试类
        PinyinEngine.setFuzzyMask(FuzzyPinyin.NONE)
        PinyinEngine.resetForTest()
    }

    @Test
    fun `关闭时不产生变体候选`() {
        val candidates = PinyinEngine.query("zang").candidates
        assertEquals(listOf("脏", "藏", "赃"), candidates)
        assertFalse("关闭时不得出现变体音的字：$candidates", candidates.contains("张"))
    }

    @Test
    fun `打开后变体单字补在精确单字之后`() {
        PinyinEngine.setFuzzyMask(FuzzyPinyin.Z_ZH)
        assertEquals(
            listOf("脏", "藏", "赃", "张", "章", "掌"),
            PinyinEngine.query("zang").candidates,
        )
    }

    @Test
    fun `只对勾选的组生效`() {
        // la 与平翘舌无关：勾了 zh/z 也不得凭空多出候选
        PinyinEngine.setFuzzyMask(FuzzyPinyin.Z_ZH)
        assertEquals(listOf("拉", "啦", "辣"), PinyinEngine.query("la").candidates)

        // 换成鼻边音组才补出 na 的字
        PinyinEngine.setFuzzyMask(FuzzyPinyin.N_L)
        assertEquals(listOf("拉", "啦", "辣", "那", "拿", "哪"), PinyinEngine.query("la").candidates)
    }

    @Test
    fun `变体词插在精确词之后_精确单字之前`() {
        PinyinEngine.setFuzzyMask(FuzzyPinyin.Z_ZH)
        val candidates = PinyinEngine.query("zangguo").candidates
        val exactWord = candidates.indexOf("赃果")
        val fuzzyWord = candidates.indexOf("张国")
        val exactChar = candidates.indexOf("脏")
        assertTrue("精确词与变体词都应在候选里：$candidates", exactWord >= 0 && fuzzyWord >= 0)
        assertTrue("变体词必须排在精确词之后：$candidates", exactWord < fuzzyWord)
        assertTrue("变体词必须排在精确单字之前：$candidates", fuzzyWord < exactChar)
    }
}
