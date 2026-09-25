package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * 面板时间戳格式守卫（`PanelTimes.entryStamp` + 全项目 `SimpleDateFormat` 的 Locale 固定）。
 *
 * 为什么需要：
 *  - 2026-09-25 真机踩过一次 —— 默认历法地区（泰历）下 `SimpleDateFormat` 用默认 Locale 会把年份渲染成
 *    佛历（2025 → 2568），剪贴板面板与搜索面板的条目时间都会错；当时散在两处的 `formatTime` 已收敛到
 *    [PanelTimes]，但**没有测试钉住 Locale**，一次「顺手简化」就能把它改回去。
 *  - `java.time` 的 `DateTimeFormatter.ofPattern` 不受影响（数字固定 ASCII、历法固定公历），
 *    所以本测试只管 `SimpleDateFormat` 这条线；`DynamicSymbols` 那 10 处由
 *    `DynamicSymbolsTest.输出数字与历法不随系统 Locale 变化` 守。
 */
class PanelTimesTest {

    /**
     * 用默认时区构造一个时刻：断言只关心「格式与历法」，与时区无关。
     *
     * ⚠ 必须显式给 Locale：`Calendar.getInstance()` 走**默认 Locale**，在泰历地区 `set(2026, …)` 设的是
     * 佛历 2026 年（= 公历 1483 年），换算出的毫秒根本不是要测的那一刻 —— 本测试第一版就踩了这个坑
     * （失败信息 `expected 2026 but was 1483`），是**测试自己**错，不是被测代码错。
     */
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        GregorianCalendar(TimeZone.getDefault(), Locale.US).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    @Test
    fun 格式为年月日时分且补零() {
        assertEquals("2026-01-05 09:07", PanelTimes.entryStamp(ts(2026, 1, 5, 9, 7)))
        assertEquals("2026-09-26 13:50", PanelTimes.entryStamp(ts(2026, 9, 26, 13, 50)))
    }

    @Test
    fun 固定公历与ASCII数字不随系统地区变化() {
        val saved = Locale.getDefault()
        try {
            // 显式带上历法 / 数字系统扩展，避免依赖各 JDK 对 th-TH、ar-EG 的默认取舍
            for (tag in listOf("th-TH-u-ca-buddhist", "ar-EG-u-nu-arab", "fa-IR-u-ca-persian")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(
                    "默认地区 $tag 下时间戳必须仍是公历 + ASCII 数字",
                    "2026-09-26 13:50",
                    PanelTimes.entryStamp(ts(2026, 9, 26, 13, 50)),
                )
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun 源码里的_SimpleDateFormat_都必须显式指定_Locale() {
        // 行为测试只覆盖 PanelTimes 一处；这条把面铺到整个项目：日期格式化一旦用默认 Locale，
        // 在默认历法地区就会渲染错年份（与平台语义有关，单看代码很容易漏）
        val dir = listOf(
            File("src/main/java/com/jinn/inputmethod"),
            File("app/src/main/java/com/jinn/inputmethod"),
        ).firstOrNull { it.isDirectory } ?: error("找不到生产源码目录（cwd=${File("").absolutePath}）")
        val offenders = dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".kt") }
            .flatMap { f ->
                f.readLines().withIndex().map { (i, line) -> Triple(f.name, i + 1, line) }
            }
            .filter { (_, _, line) ->
                val t = line.trim()
                !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*") &&
                    line.contains("SimpleDateFormat(") && !line.contains("Locale.US")
            }
            .map { (name, line, text) -> "$name:$line ${text.trim()}" }
        assertEquals(
            "SimpleDateFormat 必须显式指定 Locale.US（默认 Locale 在泰历等地区会渲染成佛历年份）: $offenders",
            emptyList<String>(),
            offenders,
        )
        assertTrue("源码目录里应至少扫到 1 个文件", dir.listFiles().orEmpty().any { it.name.endsWith(".kt") })
    }

    @Test
    fun 时刻换算与格式化一致() {
        // 反向核对：用同一个时间戳自己造字符串，确保上面钉的格式确实是「本地时间」而不是 UTC
        val stamp = ts(2026, 9, 26, 13, 50)
        val expect = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(stamp))
        assertEquals(expect, PanelTimes.entryStamp(stamp))
    }
}
