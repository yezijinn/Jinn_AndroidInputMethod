package com.jinn.inputmethod

import android.content.res.Resources
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

/**
 * 动态符号：输出「随当前时间（或设备）变化」的内容，供「变量」符号组使用。
 *
 * 数据表里的取值形如 `[MARK]名称`（标记是私用区字符，真实符号里不可能出现）：
 *  - 键面显示短名：[labelOf] 去掉标记（如 `年月日`）
 *  - 上屏内容：[expand] 按**点击那一刻**展开（如 `2026年9月24日`）
 *
 * 纯函数、零资源：格式与展开都可 JVM 单测；静态符号的取值原样返回，
 * 因此 [KeyboardLayouts] 其余分组的取值不需要任何适配。
 */
object DynamicSymbols {

    /** 动态取值前缀（Unicode 私用区，正常符号不会用到） */
    const val MARK = "\uE000"

    /** 键面短名 → 上屏内容的口径统一在这里，改格式只动这一个文件 */
    private val FMT_DATE_CN = DateTimeFormatter.ofPattern("yyyy年M月d日")
    private val FMT_DATE_COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val FMT_DATE_DASHED = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val FMT_MONTH_CN = DateTimeFormatter.ofPattern("yyyy年M月")
    private val FMT_TIME_ONLY_CN = DateTimeFormatter.ofPattern("HH点mm分ss秒")
    private val FMT_TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val FMT_TIME_ONLY_COMPACT = DateTimeFormatter.ofPattern("HHmmss")
    private val FMT_TIME_CN = DateTimeFormatter.ofPattern("yyyy年M月d日HH点mm分ss秒")
    private val FMT_TIME_COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val FMT_TIME_DASHED = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss")

    /** 财年起始月：4 月（需求样例 2026-09 为 FY2026-Q2，即 4 月起算）；换口径只改这里 */
    private const val FISCAL_START_MONTH = 4

    /** 星期名：固定中文（不取系统 Locale，界面语言为中文） */
    private val WEEKDAYS = mapOf(
        DayOfWeek.MONDAY to "星期一", DayOfWeek.TUESDAY to "星期二", DayOfWeek.WEDNESDAY to "星期三",
        DayOfWeek.THURSDAY to "星期四", DayOfWeek.FRIDAY to "星期五",
        DayOfWeek.SATURDAY to "星期六", DayOfWeek.SUNDAY to "星期日",
    )

    /**
     * 屏幕分辨率取值（宽×高像素）。
     *
     * 用 `Resources.getSystem()`，不需要 Context；单测里把它替换成固定串 ——
     * JVM 下拿不到系统资源，「变量组全部可展开」的护栏否则会把这里当成没有分支的名字。
     */
    var screenSize: () -> String = {
        val dm = Resources.getSystem().displayMetrics
        "${dm.widthPixels}×${dm.heightPixels}"
    }

    /** 数据表写法：把一个短名标成动态取值 */
    fun token(name: String): String = MARK + name

    /** 是否动态取值（数据表护栏用） */
    fun isDynamic(value: String): Boolean = value.startsWith(MARK)

    /** 键面显示用的短名：动态取值去掉标记，静态取值原样返回 */
    fun labelOf(value: String): String = if (isDynamic(value)) value.removePrefix(MARK) else value

    /**
     * 展开成实际上屏内容。
     *
     * @param at 生成时间（默认此刻）：键盘在**点击时**调用，因此拿到的永远是最新时间
     */
    fun expand(value: String, at: LocalDateTime = LocalDateTime.now()): String {
        if (!isDynamic(value)) return value
        return when (val name = value.removePrefix(MARK)) {
            // 时间类：中 = 中文式、数 = 纯数字、符 = 带分隔符（用户 2026-09-24 第三次修订的命名）
            "年日中" -> at.format(FMT_DATE_CN)
            "年日数" -> at.format(FMT_DATE_COMPACT)
            "年日符" -> at.format(FMT_DATE_DASHED)
            "时秒中" -> at.format(FMT_TIME_ONLY_CN)
            "时秒数" -> at.format(FMT_TIME_ONLY_COMPACT)
            "时秒符" -> at.format(FMT_TIME_ONLY)
            "长时中" -> at.format(FMT_TIME_CN)
            "长时数" -> at.format(FMT_TIME_COMPACT)
            "长时符" -> at.format(FMT_TIME_DASHED)
            "时间戳" -> at.atZone(ZoneId.systemDefault()).toEpochSecond().toString()
            "毫秒戳" -> at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toString()
            "星期" -> WEEKDAYS.getValue(at.dayOfWeek)
            "农历" -> LunarDate.text(at.toLocalDate())?.let { "农历$it" } ?: at.format(FMT_DATE_CN)
            "生肖" -> LunarDate.zodiac(at.toLocalDate()) ?: LunarDate.zodiacOf(at.year)
            "季度" -> quarterText(at)
            "财年" -> fiscalText(at)
            "天数" -> dayCountText(at)
            "周数" -> weekCountText(at)
            "分辨率" -> screenSize()
            // 旧短名（上一版键面名 + 已发版的那一版）全部保留：用户收藏里存的是旧取值，
            // 删掉分支会把「定长码」这种字面量打进输入框。同名冲突时以**已发版**语义为准
            // （如「年月日」是完整中文时间、「时分秒」是 13:50:28 的冒号式）
            "年月日" -> at.format(FMT_TIME_CN)
            "定长码" -> at.format(FMT_TIME_COMPACT)
            "连字符" -> at.format(FMT_TIME_DASHED)
            "日期" -> at.format(FMT_DATE_CN)
            "日期码" -> at.format(FMT_DATE_COMPACT)
            "日期连字" -> at.format(FMT_DATE_DASHED)
            "年月" -> at.format(FMT_MONTH_CN)
            "时分秒" -> at.format(FMT_TIME_ONLY)
            "年月日码" -> at.format(FMT_DATE_COMPACT)
            "时分秒码" -> at.format(FMT_TIME_ONLY_COMPACT)
            "长时间" -> at.format(FMT_TIME_CN)
            "长时间码" -> at.format(FMT_TIME_COMPACT)
            "长时间线" -> at.format(FMT_TIME_DASHED)
            // 没有分支的名字：返回去掉标记的名字本身（绝不把私用区字符上屏），并有单测守着数据表
            else -> name
        }
    }

    /** 「2026年第3季度 (Q3)」 */
    private fun quarterText(at: LocalDateTime): String {
        val quarter = (at.monthValue - 1) / 3 + 1
        return "${at.year}年第${quarter}季度 (Q$quarter)"
    }

    /** 「FY2026-Q2」：财年从 [FISCAL_START_MONTH] 月起算，跨年时年份取起始年 */
    private fun fiscalText(at: LocalDateTime): String {
        val startYear = if (at.monthValue >= FISCAL_START_MONTH) at.year else at.year - 1
        val quarter = ((at.monthValue - FISCAL_START_MONTH + 12) % 12) / 3 + 1
        return "FY$startYear-Q$quarter"
    }

    /** 「已过267天/剩98天」：已过 = 今天是今年第几天，剩余按当年总天数算 */
    private fun dayCountText(at: LocalDateTime): String {
        val passed = at.dayOfYear
        val total = if (at.toLocalDate().isLeapYear) 366 else 365
        return "已过${passed}天/剩${total - passed}天"
    }

    /**
     * 「已过39周/剩14周」：**按日历年**（1 月 1 日起每 7 天算一周，末尾不足一周也算一周）。
     *
     * 不用 ISO 周年：ISO 与日历年在年初/年末会错开（2027-01-01 属 2026 年第 53 周），
     * 于是元旦会显示成「已过53周/剩0周」——与「今年已过周数」的语义相矛盾。
     */
    private fun weekCountText(at: LocalDateTime): String {
        val passed = (at.dayOfYear - 1) / 7 + 1
        val total = (at.toLocalDate().lengthOfYear() - 1) / 7 + 1
        return "已过${passed}周/剩${total - passed}周"
    }

}
