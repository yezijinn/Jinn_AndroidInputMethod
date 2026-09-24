package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 农历表与换算护栏。
 *
 * 表是「生成物」（由万象拼音的 LUNAR_DATA 裁到 2000–2050 后重编码），所以这里钉的是**外部锚点**：
 * 春节、中秋（八月十五）、闰月首日 —— 只要有人动表改错一格，这些公开已知的日期立刻对不上。
 */
class LunarDateTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun `春节是正月初一`() {
        for (date in listOf(d(2000, 2, 5), d(2020, 1, 25), d(2023, 1, 22), d(2024, 2, 10), d(2025, 1, 29), d(2026, 2, 17), d(2050, 1, 23))) {
            val lunar = LunarDate.of(date)
            assertEquals("春节当天应是正月初一: $date", 1, lunar?.month)
            assertEquals("春节当天应是初一: $date", 1, lunar?.day)
            assertFalse("春节不是闰月: $date", lunar?.isLeapMonth ?: true)
        }
    }

    @Test
    fun `中秋是八月十五`() {
        assertEquals(LunarDate.Date(2024, 8, 15, false), LunarDate.of(d(2024, 9, 17)))
        assertEquals(LunarDate.Date(2025, 8, 15, false), LunarDate.of(d(2025, 10, 6)))
        assertEquals(LunarDate.Date(2026, 8, 15, false), LunarDate.of(d(2026, 9, 25)))
    }

    @Test
    fun `闰月首日带闰标记`() {
        assertEquals(LunarDate.Date(2020, 4, 1, true), LunarDate.of(d(2020, 5, 23))) // 闰四月初一
        assertEquals(LunarDate.Date(2023, 2, 1, true), LunarDate.of(d(2023, 3, 22))) // 闰二月初一
        assertEquals(LunarDate.Date(2025, 6, 1, true), LunarDate.of(d(2025, 7, 25))) // 闰六月初一
    }

    @Test
    fun `春节前算上一农历年与上一生肖`() {
        // 2024 春节是 2 月 10 日：元旦还在癸卯兔年
        assertEquals("兔年", LunarDate.zodiac(d(2024, 1, 1)))
        assertEquals("龙年", LunarDate.zodiac(d(2024, 2, 10)))
        assertEquals(2023, LunarDate.of(d(2024, 1, 1))?.year)
    }

    @Test
    fun `今天与相邻日期的农历文本`() {
        assertEquals("八月十四", LunarDate.text(d(2026, 9, 24)))
        assertEquals("八月十五", LunarDate.text(d(2026, 9, 25)))
        assertEquals("八月十六", LunarDate.text(d(2026, 9, 26)))
        assertEquals("马年", LunarDate.zodiac(d(2026, 9, 24)))
    }

    @Test
    fun `表覆盖边界`() {
        assertEquals(LunarDate.MIN_YEAR, 2000)
        assertEquals(LunarDate.MAX_YEAR, 2050)
        // 下界：2000-01-01 早于 2000 年春节（2 月 5 日），落在表外 → null
        assertNull("2000 年春节前算不出", LunarDate.of(d(2000, 1, 1)))
        assertNull("2000 年以前不在表内", LunarDate.of(d(1999, 12, 31)))
        assertNull("2051 年起不在表内", LunarDate.of(d(2051, 1, 1)))
        assertNull(LunarDate.text(d(1999, 12, 31)))
        assertNull(LunarDate.zodiac(d(2051, 1, 1)))
        // 两端可算的边界日
        assertTrue(LunarDate.of(d(2000, 2, 5)) != null)
        assertTrue(LunarDate.of(d(2050, 12, 31)) != null)
    }

    @Test
    fun `表内逐日连续不跳号`() {
        // 表内每天都必须算得出来，且日号只在 1..30、跨月只前进一天。
        // 起点是 2000 年春节（表的下界），不是 1 月 1 日：那天属于上一农历年，在表外。
        var date = d(2000, 2, 5)
        val end = d(2050, 12, 31)
        var previous: LunarDate.Date? = null
        var count = 0
        while (!date.isAfter(end)) {
            val lunar = LunarDate.of(date)
            assertTrue("$date 算不出农历", lunar != null)
            assertTrue("$date 日号越界: $lunar", lunar!!.day in 1..30)
            assertTrue("$date 月号越界: $lunar", lunar.month in 1..12)
            if (previous != null) {
                val advanced = lunar.day == previous!!.day + 1 ||
                    (lunar.day == 1 && previous!!.day >= 29)
                assertTrue("$date 与前一天不连续: $previous → $lunar", advanced)
            }
            previous = lunar
            date = date.plusDays(1)
            count++
        }
        assertTrue("遍历天数不对: $count", count > 18_000)
    }
}
