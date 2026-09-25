package com.jinn.inputmethod

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 面板条目的时间戳格式（剪贴板历史面板与搜索面板共用）。
 *
 * 即时构造：静态缓存会在系统语言切换后继续沿用旧 Locale（lint `ConstantLocale`），
 * 而每条目构造一次的开销可忽略。
 *
 * Locale 固定 US 而非默认值：默认历法地区（如泰国）会把年份渲染成佛历（2025 → 2568），
 * 与设置页里备份包时间的写法（`Locale.US`）也不一致。
 */
internal object PanelTimes {

    /** 条目时间戳，格式 `yyyy-MM-dd HH:mm` */
    fun entryStamp(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
}
