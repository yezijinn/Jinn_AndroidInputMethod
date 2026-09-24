package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题解析的纯函数护栏（[ThemeManager]）。
 *
 * 主题判定一天只错两次，且只在切换点那一刻暴露，正是"白天突然变黑"这类体验事故的来源。
 * 所以把边界全部钉死：四种模式、跨零点窗口、无效配置、脏时刻、下一次切换的分钟数。
 */
class ThemeManagerTest {

    private val light = 7 * 60  // 07:00
    private val dark = 19 * 60  // 19:00

    private fun darkAt(
        mode: Int,
        systemIsDark: Boolean = false,
        lightAt: Int = light,
        darkAt: Int = dark,
        nowMin: Int,
    ): Boolean = ThemeManager.isDarkNow(mode, systemIsDark, lightAt, darkAt, nowMin)

    @Test
    fun 强制模式与跟随系统() {
        assertFalse("亮白模式恒亮", darkAt(ThemeManager.MODE_LIGHT, systemIsDark = true, nowMin = 0))
        assertTrue("暗黑模式恒暗", darkAt(ThemeManager.MODE_DARK, systemIsDark = false, nowMin = 0))
        assertTrue(darkAt(ThemeManager.MODE_SYSTEM, systemIsDark = true, nowMin = 0))
        assertFalse(darkAt(ThemeManager.MODE_SYSTEM, systemIsDark = false, nowMin = 0))
    }

    @Test
    fun 未知模式退回跟随系统() {
        // 配置损坏（越界值）时不能悄悄落到某个强制值上：Prefs 已钳位，这里再兜一层
        assertTrue(darkAt(99, systemIsDark = true, nowMin = 0))
        assertFalse(darkAt(-1, systemIsDark = false, nowMin = 0))
    }

    @Test
    fun 定时模式常规窗口边界() {
        val mode = ThemeManager.MODE_SCHEDULED
        assertTrue("06:59 仍是暗色", darkAt(mode, nowMin = 6 * 60 + 59))
        assertFalse("07:00 起亮", darkAt(mode, nowMin = 7 * 60))
        assertFalse("18:59 仍亮", darkAt(mode, nowMin = 18 * 60 + 59))
        assertTrue("19:00 起暗", darkAt(mode, nowMin = 19 * 60))
        assertTrue("23:59 暗", darkAt(mode, nowMin = 23 * 60 + 59))
    }

    @Test
    fun 定时模式跨零点窗口() {
        // 夜班作息：20:00 亮起 / 08:00 暗起，亮色区间 [20:00, 24h) ∪ [00:00, 08:00)
        val mode = ThemeManager.MODE_SCHEDULED
        val l = 20 * 60
        val d = 8 * 60
        assertTrue("19:59 暗", darkAt(mode, lightAt = l, darkAt = d, nowMin = 19 * 60 + 59))
        assertFalse("20:00 亮", darkAt(mode, lightAt = l, darkAt = d, nowMin = 20 * 60))
        assertFalse("00:30 亮", darkAt(mode, lightAt = l, darkAt = d, nowMin = 30))
        assertFalse("07:59 亮", darkAt(mode, lightAt = l, darkAt = d, nowMin = 7 * 60 + 59))
        assertTrue("08:00 暗", darkAt(mode, lightAt = l, darkAt = d, nowMin = 8 * 60))
        assertTrue("12:00 暗", darkAt(mode, lightAt = l, darkAt = d, nowMin = 12 * 60))
    }

    @Test
    fun 两个时刻相同视为全天亮色() {
        val mode = ThemeManager.MODE_SCHEDULED
        assertFalse(darkAt(mode, lightAt = 600, darkAt = 600, nowMin = 600))
        assertFalse(darkAt(mode, lightAt = 600, darkAt = 600, nowMin = 0))
        assertFalse(darkAt(mode, lightAt = 600, darkAt = 600, nowMin = 1439))
    }

    @Test
    fun 脏时刻按一天归一() {
        val mode = ThemeManager.MODE_SCHEDULED
        // 1440（= 次日 0 点）与 0 点等价；负数按前一天归一
        assertFalse(
            "24:00 等价 0 点（亮色窗口内）",
            darkAt(mode, lightAt = 0, darkAt = 12 * 60, nowMin = ThemeManager.MINUTES_PER_DAY),
        )
        assertTrue(
            "-1 分钟等价前一天 23:59（暗色窗口内）",
            darkAt(mode, lightAt = 0, darkAt = 12 * 60, nowMin = -1),
        )
    }

    @Test
    fun 下一次切换的分钟数() {
        val mode = ThemeManager.MODE_SCHEDULED
        assertEquals("06:00 → 07:00", 60, ThemeManager.minutesUntilSwitch(mode, light, dark, 6 * 60))
        assertEquals("18:00 → 19:00", 60, ThemeManager.minutesUntilSwitch(mode, light, dark, 18 * 60))
        assertEquals("19:30 → 次日 07:00", 690, ThemeManager.minutesUntilSwitch(mode, light, dark, 19 * 60 + 30))
    }

    @Test
    fun 站在切换点上给下一次而不是零() {
        val mode = ThemeManager.MODE_SCHEDULED
        // 这一刻已经切好，下一次是 12 小时后的另一个端点：给 0 会让调用方以为"马上还要再切一次"
        assertEquals(12 * 60, ThemeManager.minutesUntilSwitch(mode, light, dark, 7 * 60))
        assertEquals(12 * 60, ThemeManager.minutesUntilSwitch(mode, light, dark, 19 * 60))
    }

    @Test
    fun 非定时模式与无效配置不安排刷新() {
        assertEquals(-1, ThemeManager.minutesUntilSwitch(ThemeManager.MODE_SYSTEM, light, dark, 600))
        assertEquals(-1, ThemeManager.minutesUntilSwitch(ThemeManager.MODE_LIGHT, light, dark, 600))
        assertEquals(-1, ThemeManager.minutesUntilSwitch(ThemeManager.MODE_DARK, light, dark, 600))
        assertEquals(
            "两时刻相等的无效配置永不切换",
            -1,
            ThemeManager.minutesUntilSwitch(ThemeManager.MODE_SCHEDULED, 600, 600, 600),
        )
    }

    @Test
    fun 跨零点窗口的下一次切换() {
        val mode = ThemeManager.MODE_SCHEDULED
        val l = 20 * 60
        val d = 8 * 60
        assertEquals("21:00（亮色中）→ 08:00", 11 * 60, ThemeManager.minutesUntilSwitch(mode, l, d, 21 * 60))
        assertEquals("07:00（亮色中）→ 08:00", 60, ThemeManager.minutesUntilSwitch(mode, l, d, 7 * 60))
    }

    @Test
    fun 常量与默认时刻() {
        assertEquals(1440, ThemeManager.MINUTES_PER_DAY)
        assertEquals(0, ThemeManager.MODE_SYSTEM)
        assertEquals(3, ThemeManager.MODE_SCHEDULED)
        assertEquals("默认亮起 07:00", 7 * 60, Prefs.DEFAULT_THEME_LIGHT_AT_MIN)
        assertEquals("默认暗起 19:00", 19 * 60, Prefs.DEFAULT_THEME_DARK_AT_MIN)
    }

    /**
     * 明暗决策 → 皮肤槽位：亮色阶段用亮色槽、暗色阶段用暗色槽（用户 2026-09-24 定的语义）。
     *
     * 两个槽位各自独立，换一档的皮肤不得影响另一档 —— 否则「亮色 / 暗色各选一套」就退化成单值了。
     */
    @Test
    fun 明暗档位对应各自的皮肤槽位() {
        val lightSkin = KeyboardSkins.INITIAL_LIGHT_ID
        val darkSkin = KeyboardSkins.INITIAL_DARK_ID
        assertEquals("亮色阶段用亮色槽", lightSkin, ThemeManager.skinIdFor(false, lightSkin, darkSkin))
        assertEquals("暗色阶段用暗色槽", darkSkin, ThemeManager.skinIdFor(true, lightSkin, darkSkin))
        assertEquals("换亮色槽不影响暗色档", "cream", ThemeManager.skinIdFor(false, "cream", darkSkin))
        assertEquals("换暗色槽不影响亮色档", "midnight", ThemeManager.skinIdFor(true, lightSkin, "midnight"))
    }
}
