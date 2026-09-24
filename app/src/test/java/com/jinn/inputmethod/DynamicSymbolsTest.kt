package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 动态符号（「变量」组）纯逻辑与数据表护栏。
 *
 * 关键契约：每个变量的输出与需求样例逐字符一致；键面显示短名、上屏才展开；
 * **静态符号的取值原样返回**（其余分组的取值不需要任何适配）。
 */
class DynamicSymbolsTest {

    private val fixed = LocalDateTime.of(2026, 9, 24, 13, 50, 28)
    private val t = { name: String -> DynamicSymbols.token(name) }

    @Before
    fun setUp() {
        // 分辨率只能从系统资源取，JVM 下没有 → 换成固定串，让「变量组全部可展开」的护栏也能覆盖它
        DynamicSymbols.screenSize = { "1080×2280" }
    }

    @Test
    fun `日期三种写法`() {
        assertEquals("2026年9月24日", DynamicSymbols.expand(t("年日中"), fixed))
        assertEquals("20260924", DynamicSymbols.expand(t("年日数"), fixed))
        assertEquals("2026-09-24", DynamicSymbols.expand(t("年日符"), fixed))
    }

    @Test
    fun `时刻三种写法`() {
        assertEquals("13点50分28秒", DynamicSymbols.expand(t("时秒中"), fixed))
        assertEquals("135028", DynamicSymbols.expand(t("时秒数"), fixed))
        assertEquals("13:50:28", DynamicSymbols.expand(t("时秒符"), fixed))
    }

    @Test
    fun `长时间三种写法`() {
        assertEquals("2026年9月24日13点50分28秒", DynamicSymbols.expand(t("长时中"), fixed))
        assertEquals("20260924135028", DynamicSymbols.expand(t("长时数"), fixed))
        assertEquals("2026-09-24-13:50:28", DynamicSymbols.expand(t("长时符"), fixed))
    }

    @Test
    fun `时分秒补零_月日不补零`() {
        val at = LocalDateTime.of(2026, 1, 5, 9, 8, 7)
        assertEquals("2026年1月5日", DynamicSymbols.expand(t("年日中"), at))
        assertEquals("2026-01-05", DynamicSymbols.expand(t("年日符"), at))
        assertEquals("2026年1月5日09点08分07秒", DynamicSymbols.expand(t("长时中"), at))
        assertEquals("09点08分07秒", DynamicSymbols.expand(t("时秒中"), at))
        assertEquals("090807", DynamicSymbols.expand(t("时秒数"), at))
        assertEquals("09:08:07", DynamicSymbols.expand(t("时秒符"), at))
        assertEquals("2026-01-05-09:08:07", DynamicSymbols.expand(t("长时符"), at))
    }

    @Test
    fun `星期固定中文_不随系统语言`() {
        // 2026-09-24 是星期四；整周都过一遍，避免映射表写错一格
        val names = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        for (i in 0..6) {
            val at = LocalDateTime.of(2026, 9, 21, 10, 0, 0).plusDays(i.toLong())
            assertEquals(names[i], DynamicSymbols.expand(t("星期"), at))
            assertEquals(DayOfWeek.MONDAY.plus(i.toLong()), at.dayOfWeek)
        }
    }

    @Test
    fun `时间戳与毫秒戳和当前时区一致`() {
        val expectedSecond = fixed.atZone(ZoneId.systemDefault()).toEpochSecond().toString()
        val expectedMilli = fixed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toString()
        assertEquals(expectedSecond, DynamicSymbols.expand(t("时间戳"), fixed))
        assertEquals(expectedMilli, DynamicSymbols.expand(t("毫秒戳"), fixed))
    }

    @Test
    fun `农历与生肖`() {
        assertEquals("农历八月十四", DynamicSymbols.expand(t("农历"), fixed))
        assertEquals("马年", DynamicSymbols.expand(t("生肖"), fixed))
        // 春节前仍是上一年的生肖（2026 春节是 2 月 17 日）
        val beforeNewYear = LocalDateTime.of(2026, 1, 1, 8, 0, 0)
        assertEquals("蛇年", DynamicSymbols.expand(t("生肖"), beforeNewYear))
    }

    @Test
    fun `季度与财年`() {
        assertEquals("2026年第3季度 (Q3)", DynamicSymbols.expand(t("季度"), fixed))
        assertEquals("FY2026-Q2", DynamicSymbols.expand(t("财年"), fixed)) // 财年从 4 月起算
        assertEquals("2026年第1季度 (Q1)", DynamicSymbols.expand(t("季度"), LocalDateTime.of(2026, 1, 1, 0, 0, 0)))
        assertEquals("2026年第4季度 (Q4)", DynamicSymbols.expand(t("季度"), LocalDateTime.of(2026, 12, 31, 23, 59, 59)))
        // 3 月仍在上一财年（FY2025 的 Q4）
        assertEquals("FY2025-Q4", DynamicSymbols.expand(t("财年"), LocalDateTime.of(2026, 3, 31, 0, 0, 0)))
        assertEquals("FY2026-Q1", DynamicSymbols.expand(t("财年"), LocalDateTime.of(2026, 4, 1, 0, 0, 0)))
    }

    @Test
    fun `天数与周数`() {
        assertEquals("已过267天/剩98天", DynamicSymbols.expand(t("天数"), fixed))
        assertEquals("已过39周/剩14周", DynamicSymbols.expand(t("周数"), fixed))
        // 跨年：1 月 1 日是今年的第 1 天、第 1 周（周数按日历年，不用 ISO 周年）
        assertEquals("已过1天/剩364天", DynamicSymbols.expand(t("天数"), LocalDateTime.of(2027, 1, 1, 0, 0, 0)))
        assertEquals("已过1周/剩52周", DynamicSymbols.expand(t("周数"), LocalDateTime.of(2027, 1, 1, 0, 0, 0)))
        // 一年按 7 天一段：365 天 = 53 段（末段只剩 1 天），所以 12-31 是「已过53周/剩0周」
        assertEquals("已过53周/剩0周", DynamicSymbols.expand(t("周数"), LocalDateTime.of(2026, 12, 31, 0, 0, 0)))
    }

    @Test
    fun `分辨率取设备值`() {
        assertEquals("1080×2280", DynamicSymbols.expand(t("分辨率"), fixed))
    }

    @Test
    fun `旧短名仍可展开_收藏里存的老取值不会变成字面量`() {
        // 已发版那一版的命名（同名冲突时以这一版语义为准）
        assertEquals("2026年9月24日13点50分28秒", DynamicSymbols.expand(t("年月日"), fixed))
        assertEquals("20260924135028", DynamicSymbols.expand(t("定长码"), fixed))
        assertEquals("2026-09-24-13:50:28", DynamicSymbols.expand(t("连字符"), fixed))
        assertEquals("2026年9月24日", DynamicSymbols.expand(t("日期"), fixed))
        assertEquals("20260924", DynamicSymbols.expand(t("日期码"), fixed))
        assertEquals("2026-09-24", DynamicSymbols.expand(t("日期连字"), fixed))
        assertEquals("2026年9月", DynamicSymbols.expand(t("年月"), fixed))
        assertEquals("13:50:28", DynamicSymbols.expand(t("时分秒"), fixed))
        // 同日上一版改名留下的名字（也可能已进过用户收藏）
        assertEquals("20260924", DynamicSymbols.expand(t("年月日码"), fixed))
        assertEquals("135028", DynamicSymbols.expand(t("时分秒码"), fixed))
        assertEquals("2026年9月24日13点50分28秒", DynamicSymbols.expand(t("长时间"), fixed))
        assertEquals("20260924135028", DynamicSymbols.expand(t("长时间码"), fixed))
        assertEquals("2026-09-24-13:50:28", DynamicSymbols.expand(t("长时间线"), fixed))
    }

    @Test
    fun `键面显示短名_标记不上键面`() {
        assertEquals("年日中", DynamicSymbols.labelOf(t("年日中")))
        assertEquals("长时符", DynamicSymbols.labelOf(t("长时符")))
        assertFalse("短名里不该出现标记", DynamicSymbols.labelOf(t("农历")).contains(DynamicSymbols.MARK))
        // 静态符号原样返回（键盘其余分组全靠这条）
        assertEquals("，", DynamicSymbols.labelOf("，"))
        assertEquals("return", DynamicSymbols.labelOf("return"))
    }

    @Test
    fun `静态取值原样上屏`() {
        assertEquals("，", DynamicSymbols.expand("，", fixed))
        assertEquals("∫∫∫", DynamicSymbols.expand("∫∫∫", fixed))
        assertEquals("#include", DynamicSymbols.expand("#include", fixed))
        assertFalse(DynamicSymbols.isDynamic("，"))
    }

    @Test
    fun `没有对应分支的动态名去掉标记后上屏_不会把私用区字符打进输入框`() {
        val out = DynamicSymbols.expand(t("没有这个名"), fixed)
        assertEquals("没有这个名", out)
        assertFalse(out.contains(DynamicSymbols.MARK))
    }

    @Test
    fun `变量组取值全部可展开且键面名唯一可读`() {
        val group = SYMBOL_GROUPS.first { it.label == "变量" }
        val values = group.pages.flatMap { it.values }
        assertTrue("变量组不该为空", values.isNotEmpty())
        val bad = values.filter { !DynamicSymbols.isDynamic(it) }
        assertEquals("变量组出现静态取值（键面会直接露出长字符串）: $bad", emptyList<String>(), bad)
        // 每个短名都必须在 expand 里有同名分支：没有分支的名字展开后仍等于自身
        val unregistered = values.filter { DynamicSymbols.expand(it, fixed) == DynamicSymbols.labelOf(it) }
        assertEquals("变量组有展开不出来的动态名: $unregistered", emptyList<String>(), unregistered)
        val labels = values.map { DynamicSymbols.labelOf(it) }
        assertEquals("键面短名重复: $labels", labels.size, labels.toSet().size)
        assertTrue("键面短名过长会挤在键面上: $labels", labels.all { it.length <= 4 })
    }
}
