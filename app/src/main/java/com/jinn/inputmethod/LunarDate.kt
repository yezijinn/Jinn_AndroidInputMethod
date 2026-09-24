package com.jinn.inputmethod

import java.time.LocalDate

/**
 * 公历 → 农历（含生肖）。纯逻辑、零资源，可直接 JVM 单测。
 *
 * 数据只有 51 个 Int（2000–2050，共 204 字节）：裁剪自万象拼音 `lua/wanxiang/shijian.lua` 的
 * `LUNAR_DATA`（覆盖 1899–2113），逐项重编码成更省位的 26 位整数。生成脚本见
 * `tools/dict_builder/gen_lunar_table.py`（内含春节/中秋/闰月锚点自校验），运行期锚点由
 * LunarDateTest 钉住 —— 改表必须让这两处同时通过。
 * 覆盖范围外返回 null，由调用方决定降级方式（[DynamicSymbols] 里回退为公历日期）。
 */
object LunarDate {

    /** 表覆盖的年份范围（含端点），超出即无法换算 */
    const val MIN_YEAR = 2000
    const val MAX_YEAR = 2050

    // 每项 26 位，位布局：
    //   [25..22] 闰月（0 = 无闰月）
    //   [21..10] 12 个月的大小（正月在 bit21，逐月往低位走；1 = 30 天，0 = 29 天）
    //   [9]      闰月大小（1 = 30 天）
    //   [8..5]   春节的月份
    //   [4..0]   春节的日
    // 数组下标 0 对应 2000 年（即 [MIN_YEAR]）。改表必须同时复核 LunarDateTest 里的锚点。
    private val YEARS = intArrayOf(
        0x0325845, 0x1365438, 0x035284C, 0x0369441, 0x09D5436, 0x015A849,
        0x1EAEC3D, 0x0097452, 0x024B447, 0x172AC3A, 0x02A544E, 0x02D2843,
        0x12EA837, 0x02B544A, 0x255743F, 0x012E853, 0x0296C48, 0x1945E3C,
        0x014AC50, 0x02A4C45, 0x11E5439, 0x01AA84C, 0x02B5441, 0x096D436,
        0x012D84A, 0x1A9B83D, 0x0293851, 0x0349846, 0x17A983A, 0x0354C4D,
        0x016A843, 0x0DDA837, 0x025B44B, 0x2D2BC3F, 0x012B453, 0x0293448,
        0x1B42E3C, 0x034944F, 0x0354844, 0x1775038, 0x02D684C, 0x015B441,
        0x0956C36, 0x0126C4A, 0x1E95C3E, 0x0292C51, 0x02A9446, 0x16C963A,
        0x01B484E, 0x02B6842, 0x0D2DA37,
    )

    /** 月份名（正月起）；闰月由 [monthText] 加「闰」前缀 */
    private val MONTHS = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月",
    )

    /** 日名（初一…三十），下标 = 日 - 1 */
    private val DAYS = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    /** 十二生肖（子鼠起），下标 = (农历年 - 4) % 12 */
    private val ZODIAC = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    /** 农历日期：月 1–12、日 1–30；[isLeapMonth] 为闰月 */
    data class Date(val year: Int, val month: Int, val day: Int, val isLeapMonth: Boolean)

    /** 公历换农历；超出 [MIN_YEAR]–[MAX_YEAR] 返回 null */
    fun of(gregorian: LocalDate): Date? {
        val solarYear = gregorian.year
        if (solarYear < MIN_YEAR || solarYear > MAX_YEAR) return null
        // 春节前算上一农历年（如 1 月出生的人属相仍是前一年的）
        var lunarYear = solarYear
        var newYear = newYearOf(lunarYear) ?: return null
        if (gregorian.isBefore(newYear)) {
            lunarYear--
            newYear = newYearOf(lunarYear) ?: return null
        }
        var offset = (gregorian.toEpochDay() - newYear.toEpochDay()).toInt()
        val leapMonth = leapMonthOf(lunarYear)
        for (month in 1..12) {
            val days = daysOfMonth(lunarYear, month)
            if (offset < days) return Date(lunarYear, month, offset + 1, false)
            offset -= days
            if (month == leapMonth) {
                val leapDays = daysOfLeapMonth(lunarYear)
                if (offset < leapDays) return Date(lunarYear, month, offset + 1, true)
                offset -= leapDays
            }
        }
        return null // 表数据异常时的兜底，正常不会走到
    }

    /** 「八月十四」/「闰六月初三」；超出范围返回 null */
    fun text(gregorian: LocalDate): String? {
        val d = of(gregorian) ?: return null
        return (if (d.isLeapMonth) "闰" else "") + MONTHS[d.month - 1] + DAYS[d.day - 1]
    }

    /** 「马年」；超出范围返回 null。按农历年判定，春节前仍是上一年生肖 */
    fun zodiac(gregorian: LocalDate): String? {
        val d = of(gregorian) ?: return null
        return zodiacOf(d.year)
    }

    /** 「马年」：直接按年份取生肖（表覆盖不到时供调用方降级使用） */
    fun zodiacOf(lunarYear: Int): String = ZODIAC[(lunarYear - 4).mod(12)] + "年"

    /** 春节（该农历年的正月初一） */
    private fun newYearOf(lunarYear: Int): LocalDate? {
        val info = infoOf(lunarYear) ?: return null
        return LocalDate.of(lunarYear, (info ushr 5) and 0xF, info and 0x1F)
    }

    /** 闰月月份（0 = 无闰月） */
    private fun leapMonthOf(lunarYear: Int): Int {
        val info = infoOf(lunarYear) ?: return 0
        return (info ushr 22) and 0xF
    }

    /** 闰月天数：29 或 30 */
    private fun daysOfLeapMonth(lunarYear: Int): Int {
        val info = infoOf(lunarYear) ?: return 29
        return 29 + ((info ushr 9) and 1)
    }

    /** 农历月天数：29 或 30（正月在 bit21） */
    private fun daysOfMonth(lunarYear: Int, month: Int): Int {
        val info = infoOf(lunarYear) ?: return 29
        return 29 + ((info ushr (22 - month)) and 1)
    }

    private fun infoOf(lunarYear: Int): Int? {
        val index = lunarYear - MIN_YEAR
        return if (index in YEARS.indices) YEARS[index] else null
    }
}
