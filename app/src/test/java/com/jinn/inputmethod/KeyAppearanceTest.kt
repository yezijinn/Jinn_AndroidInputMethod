package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘外观参数（圆角 / 间隙）定义域测试，纯 JVM。
 *
 * 这些值会被三方共用：设置页滑杆、[Prefs] 持久化、[PinyinKeyboardView] 换算成像素，
 * 任何一处越界都会直接进到 `drawRoundRect`（圆角为 NaN 时整个键面会画不出来，
 * 内缩过大则把按键压没）。因此把定义域、步进与进度往返钉死在测试里。
 */
class KeyAppearanceTest {

    @Test
    fun corner_clampsToDomain() {
        assertEquals(KeyAppearance.MIN_CORNER_DP, KeyAppearance.clampCornerDp(-5f), 0f)
        assertEquals(KeyAppearance.MAX_CORNER_DP, KeyAppearance.clampCornerDp(99f), 0f)
        assertEquals(6f, KeyAppearance.clampCornerDp(6f), 0f)
    }

    @Test
    fun gap_clampsToDomain() {
        assertEquals(KeyAppearance.MIN_GAP_DP, KeyAppearance.clampGapDp(-0.5f), 0f)
        assertEquals(KeyAppearance.MAX_GAP_DP, KeyAppearance.clampGapDp(12f), 0f)
    }

    @Test
    fun gap_snapsToHalfStep() {
        assertEquals(3.5f, KeyAppearance.clampGapDp(3.3f), 0f)
        assertEquals(3.5f, KeyAppearance.clampGapDp(3.7f), 0f)
        assertEquals(3.0f, KeyAppearance.clampGapDp(3.1f), 0f)
    }

    /**
     * NaN / Infinity 必须退回默认值：`coerceIn` 对 NaN 的比较恒为 false，
     * 直接放行会让 NaN 一路传到绘制层。
     */
    @Test
    fun nonFiniteValues_fallBackToDefault() {
        assertEquals(KeyAppearance.DEFAULT_CORNER_DP, KeyAppearance.clampCornerDp(Float.NaN), 0f)
        assertEquals(KeyAppearance.DEFAULT_CORNER_DP, KeyAppearance.clampCornerDp(Float.POSITIVE_INFINITY), 0f)
        assertEquals(KeyAppearance.DEFAULT_GAP_DP, KeyAppearance.clampGapDp(Float.NaN), 0f)
        assertEquals(KeyAppearance.DEFAULT_GAP_DP, KeyAppearance.clampGapDp(Float.NEGATIVE_INFINITY), 0f)
    }

    /** 默认值（用户 2026-09-23 指定：圆角 5dp / 间隙 2dp）落在定义域内且恰在步进网格上 */
    @Test
    fun defaults_areInsideDomainAndOnStepGrid() {
        assertEquals(5f, KeyAppearance.DEFAULT_CORNER_DP, 0f)
        assertEquals(2f, KeyAppearance.DEFAULT_GAP_DP, 0f)
        assertTrue(KeyAppearance.DEFAULT_CORNER_DP in KeyAppearance.MIN_CORNER_DP..KeyAppearance.MAX_CORNER_DP)
        assertTrue(KeyAppearance.DEFAULT_GAP_DP in KeyAppearance.MIN_GAP_DP..KeyAppearance.MAX_GAP_DP)
        // 默认值必须能被钳位原样保留：否则「默认值」在读取时就会被改写，设置页显示与实际不一致
        assertEquals(KeyAppearance.DEFAULT_CORNER_DP, KeyAppearance.clampCornerDp(KeyAppearance.DEFAULT_CORNER_DP), 0f)
        assertEquals(KeyAppearance.DEFAULT_GAP_DP, KeyAppearance.clampGapDp(KeyAppearance.DEFAULT_GAP_DP), 0f)
    }

    /** SeekBar 上限 = 定义域跨度 / 步进（圆角 24 格 × 1dp，间隙 16 格 × 0.5dp） */
    @Test
    fun progressMax_matchesDomainAndStep() {
        assertEquals(24, KeyAppearance.CORNER_PROGRESS_MAX)
        assertEquals(16, KeyAppearance.GAP_PROGRESS_MAX)
        assertEquals(
            KeyAppearance.MAX_CORNER_DP,
            KeyAppearance.cornerProgressToDp(KeyAppearance.CORNER_PROGRESS_MAX),
            0f,
        )
        assertEquals(
            KeyAppearance.MAX_GAP_DP,
            KeyAppearance.gapProgressToDp(KeyAppearance.GAP_PROGRESS_MAX),
            0f,
        )
    }

    /** 圆角：进度 ↔ dp 全量往返不漂移 */
    @Test
    fun cornerProgress_roundTripIsStable() {
        for (p in 0..KeyAppearance.CORNER_PROGRESS_MAX) {
            val dp = KeyAppearance.cornerProgressToDp(p)
            assertEquals("进度 $p 往返", p, KeyAppearance.cornerDpToProgress(dp))
        }
    }

    /** 间隙：进度 ↔ dp 全量往返不漂移（0.5 步进在二进制里可精确表示） */
    @Test
    fun gapProgress_roundTripIsStable() {
        for (p in 0..KeyAppearance.GAP_PROGRESS_MAX) {
            val dp = KeyAppearance.gapProgressToDp(p)
            assertEquals("进度 $p 往返", p, KeyAppearance.gapDpToProgress(dp))
        }
    }

    @Test
    fun progress_outOfRangeIsClamped() {
        assertEquals(KeyAppearance.MAX_CORNER_DP, KeyAppearance.cornerProgressToDp(999), 0f)
        assertEquals(KeyAppearance.MIN_CORNER_DP, KeyAppearance.cornerProgressToDp(-3), 0f)
        assertEquals(KeyAppearance.MAX_GAP_DP, KeyAppearance.gapProgressToDp(999), 0f)
        assertEquals(KeyAppearance.MIN_GAP_DP, KeyAppearance.gapProgressToDp(-1), 0f)
    }

    @Test
    fun formatDp_keepsIntegerAndHalfForms() {
        assertEquals("0 dp", KeyAppearance.formatDp(0f))
        assertEquals("6 dp", KeyAppearance.formatDp(6f))
        assertEquals("24 dp", KeyAppearance.formatDp(24f))
        assertEquals("1.5 dp", KeyAppearance.formatDp(1.5f))
        assertEquals("0 dp", KeyAppearance.formatDp(Float.NaN))
    }

    /**
     * 间隙上界必须仍然「按得动」：每键四边各内缩一半，8dp 间隙时最窄的键
     * （10 键一行的字母键，320dp 屏幕上约 31dp 宽）内缩 4dp 后仍有 23dp 可点。
     */
    @Test
    fun maxGap_keepsNarrowestKeyTappable() {
        val insetDp = KeyAppearance.MAX_GAP_DP / 2f
        assertEquals(4f, insetDp, 0f)
        val narrowestKeyDp = 31f
        assertTrue("最窄键被间隙压没", narrowestKeyDp - insetDp * 2f > 20f)
    }
}
