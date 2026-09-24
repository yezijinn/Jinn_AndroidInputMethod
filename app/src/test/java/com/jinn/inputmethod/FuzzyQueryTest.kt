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
        // 引擎与词频都是单例：三者都必须复位，否则会污染同一 JVM 内的其它测试类
        PinyinEngine.setFuzzyMask(FuzzyPinyin.NONE)
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
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

    @Test
    fun `变体单字只消费对应音节_残码保留`() {
        PinyinEngine.setFuzzyMask(FuzzyPinyin.Z_ZH)
        // 「张」不在 zang 的精确单字表里，来自变体 zhang：仍只消费 zang 这一段
        val fuzzy = PinyinEngine.consumption("zangguo", "张")
        assertEquals(4, fuzzy.quanpinChars)
        assertEquals(1, fuzzy.syllables)
        // 精确单字照旧
        assertEquals(4, PinyinEngine.consumption("zangguo", "脏").quanpinChars)

        // 关闭模糊音后「张」没有归属音节 → 退回「消费全部」（历史兜底行为）
        PinyinEngine.setFuzzyMask(FuzzyPinyin.NONE)
        assertEquals(7, PinyinEngine.consumption("zangguo", "张").quanpinChars)
    }

    @Test
    fun `变体单字数量受 MAX_FUZZY_CHARS 限制`() {
        // 变体音节的单字表刻意超过上限，验证裁剪生效且精确字一个不少
        val many = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥天地玄"
        assertEquals(25, many.length)
        assertEquals(20, PinyinEngine.MAX_FUZZY_CHARS)
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            // 单字表是「音节<TAB>字,字,字」格式：整串没分隔符会被当成一个词条丢掉
            chars = "zang\t脏\nzhang\t" + many.toList().joinToString(","),
            phrases = "",
            syllables = "zang\nzhang",
        )
        PinyinEngine.setFuzzyMask(FuzzyPinyin.Z_ZH)
        val candidates = PinyinEngine.query("zang").candidates
        assertEquals("精确字在前且只 1 个", "脏", candidates.first())
        assertEquals("精确 1 字 + 变体截到上限 20 字", 1 + 20, candidates.size)
        assertEquals(many.take(20).map { it.toString() }, candidates.drop(1))
    }

    @Test
    fun `变体单字按所在音节定界_第二个音节同样只消费自己`() {
        PinyinEngine.setFuzzyMask(FuzzyPinyin.Z_ZH)
        // 「张」来自第二个音节 zang 的变体 zhang：只消费 guo+zang（7 字符 / 2 音节）。
        // 输入末尾再缀一段未完成音节，使「消费到该音节」与「全清兜底」的结果可区分
        // （(7,2) vs (10,2)）——否则「只查首音节」的错误实现也能通过
        val c = PinyinEngine.consumption("guozanghao", "张")
        assertEquals(7, c.quanpinChars)
        assertEquals(2, c.syllables)
    }

    @Test
    fun `变体归属优先于末尾残码的全清兜底`() {
        // 输入 fanh：末尾 h 是未完成音节，「汉」既在前缀联想结果里、也是 fan 的变体（f⇄h）。
        // 变体归属优先 ⇒ 只消费 fan，把用户刚敲的 h 留作残码
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            chars = "fan\t帆\nhan\t汉\nhao\t好",
            phrases = "",
            syllables = "fan\nhan\nhao",
        )
        PinyinEngine.setFuzzyMask(FuzzyPinyin.F_H)
        val han = PinyinEngine.consumption("fanh", "汉")
        assertEquals(3, han.quanpinChars)
        assertEquals(1, han.syllables)
        // 「好」只是 h 的前缀联想字、不属于任何变体：照旧消费全部
        assertEquals(4, PinyinEngine.consumption("fanh", "好").quanpinChars)

        // 关掉该组后「汉」不再有变体归属 → 回落到前缀联想的全清兜底
        PinyinEngine.setFuzzyMask(FuzzyPinyin.NONE)
        assertEquals(4, PinyinEngine.consumption("fanh", "汉").quanpinChars)
    }

    @Test
    fun `模糊音命中的词按词库真实键参与预测`() {
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            chars = "",
            phrases = "zangguo\t赃果\nzhangguo\t张国\nzhangguorong\t张国荣",
            syllables = "zang\nzhang\nguo\nrong",
        )
        PinyinEngine.setFuzzyMask(FuzzyPinyin.Z_ZH)
        assertTrue(PinyinEngine.query("zangguo").candidates.contains("张国"))
        // 「张国」的真实键是 zhangguo：延续词「张国荣」要被扫到（登记输入键 zangguo 会扫空）
        assertEquals(listOf("荣"), PinyinEngine.predict("张国"))
        // 消费区间仍按用户输入算（typedKey 那份映射不受影响）
        assertEquals(7, PinyinEngine.consumption("zangguo", "张国").quanpinChars)
    }
}
