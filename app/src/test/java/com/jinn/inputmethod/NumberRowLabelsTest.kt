package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NumberRowLabels] 的纯函数守卫：结构归一、空槽回落、清洗与限长口径、序列化幂等。
 *
 * 关注点与 [FavoriteSymbolsTest] 分工：那边管「用户自由增删的符号集」，这边管
 * **固定 10 槽的位置语义** —— 长度不符整份回退、空槽回落默认、生效值恒非空。
 */
class NumberRowLabelsTest {

    @Test
    fun `空串与缺省回落默认数字`() {
        val def = NumberRowLabels.DEFAULT_KEYS
        assertEquals(10, def.size)
        for (raw in listOf(null, "", "   ", "[]")) {
            val eff = NumberRowLabels.effective(raw)
            assertEquals("raw=$raw 必须是 10 项", 10, eff.size)
            assertEquals("raw=$raw 必须全回落默认", def, eff)
            eff.forEach { assertTrue("生效值不得为空（空槽键按了没反应）", it.isNotEmpty()) }
        }
    }

    @Test
    fun `默认顺序是电话键盘自然序`() {
        assertEquals("1234567890", NumberRowLabels.DEFAULT_KEYS.joinToString(""))
        assertEquals(10, NumberRowLabels.COUNT)
    }

    @Test
    fun `部分槽位自定义其余回落`() {
        // 第 3 槽改成「甲」，其余留空 ⇒ 生效值只有该槽变
        val raw = NumberRowLabels.serialize(listOf("", "", "甲") + List(7) { "" })
        val eff = NumberRowLabels.effective(raw)
        assertEquals(listOf("1", "2", "甲", "4", "5", "6", "7", "8", "9", "0"), eff)
    }

    @Test
    fun `清洗与收藏同一口径`() {
        // 零宽空格 / NBSP / 表意空格在键面上看不见却占格子 ⇒ 必须清掉，清空后回落默认
        val dirty = listOf("1\u200B", "2\u00A0", "3\u3000", "\uFEFF", "\u200B\u200B") + List(5) { "" }
        val eff = NumberRowLabels.effective(NumberRowLabels.serialize(dirty))
        assertEquals(
            "不可见字符必须被清掉并回落默认（否则就是空槽键）",
            NumberRowLabels.DEFAULT_KEYS,
            eff,
        )
        // 与 FavoriteSymbols.clean 同源：清完的结果应与收藏侧一致
        assertEquals(FavoriteSymbols.clean("a\u200Bb\u00A0"), FavoriteSymbols.clean("ab"))
    }

    @Test
    fun `限长按码点计`() {
        // 4 个 emoji（各 2 个 UTF-16 码元）按码点只算 4 ⇒ 必须收；
        // 5 个汉字同样是 5 个码点 ⇒ 拒。UTF-16 长度口径会把两者都算成 8/10 而误拒前者。
        val four = "\uD83D\uDE00".repeat(4)
        assertTrue("4 个 emoji 按码点只算 4，必须收", FavoriteSymbols.withinLimit(four, NumberRowLabels.MAX_CHARS))
        assertTrue("4 个汉字必须收", FavoriteSymbols.withinLimit("甲乙丙丁", NumberRowLabels.MAX_CHARS))
        assertFalse("5 个汉字必须拒", FavoriteSymbols.withinLimit("甲乙丙丁戊", NumberRowLabels.MAX_CHARS))
        val five = "\uD83D\uDE00".repeat(5)
        assertFalse("5 个 emoji 必须拒", FavoriteSymbols.withinLimit(five, NumberRowLabels.MAX_CHARS))

        // 落盘侧同口径：4 emoji 存活、5 emoji 被压成空槽（回落默认）
        val ok = NumberRowLabels.effective(NumberRowLabels.serialize(listOf(four) + List(9) { "" }))
        assertEquals(four, ok[0])
        val tooLong = NumberRowLabels.effective(NumberRowLabels.serialize(listOf(five) + List(9) { "" }))
        assertEquals("超长项压成空槽而不是留糊字", "1", tooLong[0])
    }

    @Test
    fun `长度不是10整份回退默认`() {
        for (raw in listOf(
            """["甲"]""",
            """["1","2","3","4","5","6","7","8","9","0","x"]""",
            """["1","2","3","4","5","6","7","8","9"]""",
            """{"0":"1"}""",
            """[1,2,3,4,5,6,7,8,9,0]""",
            """not json""",
        )) {
            val eff = NumberRowLabels.effective(raw)
            assertEquals("raw=$raw 必须整份回退默认", NumberRowLabels.DEFAULT_KEYS, eff)
        }
    }

    @Test
    fun `非字符串元素当空槽`() {
        // 位置语义下「读不出该项」= 该槽未自定义，不得抛
        val raw = """["甲",1,"乙",null,"","","","","",""]"""
        val eff = NumberRowLabels.effective(raw)
        assertEquals(10, eff.size)
        assertEquals("甲", eff[0])
        assertEquals("2", eff[1])
        assertEquals("乙", eff[2])
        eff.forEach { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun `序列化恒10项且往返幂等`() {
        val samples = listOf(
            null,
            "",
            """["甲","","乙","","","","","","",""]""",
            """["1","2","3","4","5","6","7","8","9","0"]""",
            """garbage""",
        )
        for (raw in samples) {
            val once = NumberRowLabels.serialize(NumberRowLabels.parse(raw))
            assertEquals("序列化必须恒 10 项", 10, NumberRowLabels.parse(once).size)
            val twice = NumberRowLabels.serialize(NumberRowLabels.parse(once))
            assertEquals("raw=$raw 往返必须幂等", once, twice)
        }
    }

    @Test
    fun `越界下标按空槽`() {
        // serialize 只认前 10 个；调用方传短列表不得抛
        val s = NumberRowLabels.serialize(listOf("甲"))
        assertEquals(10, NumberRowLabels.parse(s).size)
        assertEquals("甲", NumberRowLabels.parse(s)[0])
    }

    /**
     * 值里的 JSON 特殊字符必须原样往返（本轮补测，2026-10-23）。
     *
     * 持久化走 `JSONArray`：谁把它换成手拼字符串（`joinToString` 一类），`"` 与 `\` 就会写出非法 JSON，
     * 解析侧按「结构不符 ⇒ 整份全空」处理 —— 症状是「改过的数字行键面全变回默认」，
     * 而现有一组断言（10 项、幂等、空槽回落）全都照样绿。
     */
    @Test
    fun `标注里的引号与反斜杠必须原样往返`() {
        val labels = listOf("\\\"", "\\\\", "😀😀", "#", "1", "2", "3", "4", "5", "6")
        val raw = NumberRowLabels.serialize(labels)
        assertEquals("引号 / 反斜杠 / emoji 必须原样返回", labels, NumberRowLabels.parse(raw))
        assertEquals("往返后再序列化必须逐字节相同", raw, NumberRowLabels.serialize(NumberRowLabels.parse(raw)))
    }
}
