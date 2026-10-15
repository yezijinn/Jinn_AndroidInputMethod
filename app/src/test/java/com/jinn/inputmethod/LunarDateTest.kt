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
                val advanced = lunar.day == previous.day + 1 ||
                    (lunar.day == 1 && previous.day >= 29)
                assertTrue("$date 与前一天不连续: $previous → $lunar", advanced)
            }
            previous = lunar
            date = date.plusDays(1)
            count++
        }
        assertTrue("遍历天数不对: $count", count > 18_000)
    }

    // 生肖降级口径（BUG.md L-1376）：zodiacOf 的契约是**农历年**，表外年份没有春节锚点，
    // 调用方拿公历年硬算会在春节前多进一位（错位一年）。近似口径收在模块内，这里钉住
    // 「1 月 20 日及以前 = 农历年还没翻篇」，并反向钉住源码里不许再有 zodiacOf(公历年) 式硬算。
    @Test
    fun 生肖降级按春节区间修正农历年() {
        // 春节最早 1/21 ⇒ 1/20 及更早必定仍属上一农历年
        assertEquals("蛇年", LunarDate.zodiacApprox(d(2026, 1, 20)))
        assertEquals("蛇年", LunarDate.zodiacApprox(d(2026, 1, 10)))
        assertEquals("蛇年", LunarDate.zodiacApprox(d(2026, 1, 1)))
        // 跨春节可能位置的日期无从判定 ⇒ 维持公历年（不劣于修正前）
        assertEquals("马年", LunarDate.zodiacApprox(d(2026, 1, 25)))
        assertEquals("马年", LunarDate.zodiacApprox(d(2026, 3, 1)))
        // 非 1 月（春节不可能落到的月份）不修正；2025-12-31 在 2025 春节（1/29）之后 ⇒ 仍是乙巳蛇年
        assertEquals("蛇年", LunarDate.zodiacApprox(d(2025, 12, 31)))
        assertEquals("蛇年", LunarDate.zodiacApprox(d(2025, 6, 15)))
    }

    @Test
    fun 生肖降级不得再用公历年硬算() {
        val src = TestSources.codeSource("DynamicSymbols.kt")
        assertFalse(
            "DynamicSymbols 里仍有 zodiacOf(公历年) 式硬算 —— 春节前会错位一年（L-1376）",
            Regex("""zodiacOf\s*\(\s*[^\n]*\.year""").containsMatchIn(src),
        )
        assertTrue("DynamicSymbols 应改用 zodiacApprox", "zodiacApprox" in src)
    }
}
