package com.jinn.inputmethod

import android.content.Context
import android.content.res.Configuration
import java.util.Calendar

/**
 * 亮白 / 暗黑主题的决策与应用。
 *
 * 设计要点（详见 AGENTS.md「主题」章节）：
 *  - 色板走资源限定符（`values/` 亮白、`values-night/` 暗黑），因此**「跟随系统」不需要任何代码**；
 *    只有「亮白 / 暗黑 / 定时」三种强制模式才需要把 uiMode 覆盖进 Context。
 *  - 本项目不引入 AppCompat，用不了 `AppCompatDelegate.setDefaultNightMode`；
 *    统一走 [themedContext]，由 Activity 的 `attachBaseContext` 与 IME 的键盘视图创建点调用。
 *  - 判定全是纯函数（[isDarkNow] / [minutesUntilSwitch]），可直接 JVM 单测。
 */
object ThemeManager {

    /** 跟随系统深浅色（不覆盖 uiMode） */
    const val MODE_SYSTEM = 0

    /** 强制亮白 */
    const val MODE_LIGHT = 1

    /** 强制暗黑 */
    const val MODE_DARK = 2

    /** 按用户设定的两个时刻自动切换 */
    const val MODE_SCHEDULED = 3

    /** 一天的总分钟数：时刻统一按「当天第几分钟」（0..1439）参与运算 */
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * 纯函数：此刻是否应使用**深色**主题。
     *
     * @param mode 主题模式；未知取值按「跟随系统」处理（配置损坏时不得悄悄落到某个强制值上）
     * @param systemIsDark 系统当前是否深色（调用方从 Configuration 读取）
     * @param lightAtMin 定时模式：切到亮色的时刻（当天第几分钟）
     * @param darkAtMin 定时模式：切到暗色的时刻
     * @param nowMin 当前时刻（当天第几分钟）
     *
     * 定时语义：**亮起时刻进入亮色、暗起时刻进入暗色** —— 亮色区间为 `[light, dark)`；
     * 若 `light > dark`（例：20:00 亮 / 08:00 暗，作息跨零点）则亮色区间为 `[light, 24h) ∪ [0, dark)`；
     * 两者相等属无效配置，按**全天亮色**处理（宁可保留白天观感，也不让判定无解）。
     * 时刻一律 `floorMod` 归一，负数或超过 24h 的脏值不会越界。
     */
    fun isDarkNow(
        mode: Int,
        systemIsDark: Boolean,
        lightAtMin: Int,
        darkAtMin: Int,
        nowMin: Int,
    ): Boolean = when (mode) {
        MODE_LIGHT -> false
        MODE_DARK -> true
        MODE_SCHEDULED -> !inLightWindow(lightAtMin, darkAtMin, nowMin)
        else -> systemIsDark
    }

    /** 亮色区间判定（含跨零点与无效配置） */
    private fun inLightWindow(lightAtMin: Int, darkAtMin: Int, nowMin: Int): Boolean {
        val light = Math.floorMod(lightAtMin, MINUTES_PER_DAY)
        val dark = Math.floorMod(darkAtMin, MINUTES_PER_DAY)
        val now = Math.floorMod(nowMin, MINUTES_PER_DAY)
        if (light == dark) return true // 无效配置：全天亮色
        return if (light < dark) now >= light && now < dark
        else now >= light || now < dark
    }

    /**
     * 纯函数：距离下一次切换还有多少分钟（停在设置页时用它安排准点刷新）。
     *
     * 返回 **-1** 表示「模式与时刻无关」（非定时 / 无效配置）；其余情况恒为 `1..1440`
     * —— 恰好站在切换点上时给的是**下一次**（1440 分钟），避免 0 值被当成「立即切换」而反复重建页面。
     */
    fun minutesUntilSwitch(mode: Int, lightAtMin: Int, darkAtMin: Int, nowMin: Int): Int {
        if (mode != MODE_SCHEDULED) return -1
        val light = Math.floorMod(lightAtMin, MINUTES_PER_DAY)
        val dark = Math.floorMod(darkAtMin, MINUTES_PER_DAY)
        if (light == dark) return -1
        val now = Math.floorMod(nowMin, MINUTES_PER_DAY)
        val candidates = listOf(
            Math.floorMod(light - now, MINUTES_PER_DAY),
            Math.floorMod(dark - now, MINUTES_PER_DAY),
        ).filter { it > 0 }
        return candidates.minOrNull() ?: MINUTES_PER_DAY
    }

    /** 当前时刻（当天第几分钟），按本机时区 */
    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** 系统当前是否深色（读 Configuration 的 uiMode 掩码位） */
    fun isSystemDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /** 按 Prefs 解析「此刻是否深色」：强制模式看配置时刻，跟随系统看系统 */
    fun isDark(context: Context, prefs: Prefs = Prefs(context)): Boolean = isDarkNow(
        mode = prefs.themeMode,
        systemIsDark = isSystemDark(context),
        lightAtMin = prefs.themeLightAtMinutes,
        darkAtMin = prefs.themeDarkAtMinutes,
        nowMin = nowMinutes(),
    )

    /**
     * 返回把 uiMode 固定为指定深浅的 Context：Activity 的 `attachBaseContext` 与 IME 键盘视图的
     * 创建点都走这里 —— 资源解析（`@color` / drawable / 系统控件配色）随之下沉到目标色板。
     *
     * ⚠ 只动 `UI_MODE_NIGHT_*` 两个位，其余配置（方向 / 密度 / 语言 / 字体缩放）原样保留。
     */
    fun wrapContext(base: Context, isDark: Boolean): Context {
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return base.createConfigurationContext(config)
    }

    /**
     * 按 Prefs 决策后的 Context：**跟随系统时原样返回** —— 让系统深浅色变化由系统自己驱动
     * （也避免无谓地再造一个 Context）；其余模式返回固定 uiMode 的覆盖 Context。
     */
    fun themedContext(base: Context, prefs: Prefs = Prefs(base)): Context {
        if (prefs.themeMode == MODE_SYSTEM) return base
        return wrapContext(base, isDark(base, prefs))
    }
}
