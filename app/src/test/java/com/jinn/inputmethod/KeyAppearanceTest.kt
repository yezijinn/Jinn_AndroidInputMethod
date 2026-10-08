package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘外观参数（圆角 / 间隙 / 键高）定义域测试，纯 JVM。
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
        assertEquals(KeyAppearance.MAX_GAP_DP, KeyAppearance.clampGapDp(20f), 0f)
    }

    @Test
    fun gap_snapsToHalfStep() {
        assertEquals(3.5f, KeyAppearance.clampGapDp(3.3f), 0f)
        assertEquals(3.5f, KeyAppearance.clampGapDp(3.7f), 0f)
        assertEquals(3.0f, KeyAppearance.clampGapDp(3.1f), 0f)
    }

    /**
     * 键高（「键高」滑动条）：定义域 40~80dp、步进 2dp。
     *
     * 上下界是「还能用」的边界而不是美观边界：下界以下装不下韵母提示、上界以上 3 行会把键盘顶出小屏。
     */
    @Test
    fun keyHeight_clampsToDomainAndSnapsToStep() {
        assertEquals(KeyAppearance.MIN_KEY_HEIGHT_DP, KeyAppearance.clampKeyHeightDp(-10f), 0f)
        assertEquals(KeyAppearance.MAX_KEY_HEIGHT_DP, KeyAppearance.clampKeyHeightDp(240f), 0f)
        // 奇数对齐到最近的一格（步进 2dp）：53 → 54，55 → 56
        assertEquals(54f, KeyAppearance.clampKeyHeightDp(53f), 0f)
        assertEquals(56f, KeyAppearance.clampKeyHeightDp(55f), 0f)
        // 越界优先于步进：39 落到下界 40（下界本身就在网格上）
        assertEquals(40f, KeyAppearance.clampKeyHeightDp(39f), 0f)
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
        assertEquals(KeyAppearance.DEFAULT_SPACING_DP, KeyAppearance.clampSpacingDp(Float.NaN), 0f)
        assertEquals(KeyAppearance.DEFAULT_KEY_HEIGHT_DP, KeyAppearance.clampKeyHeightDp(Float.NaN), 0f)
        assertEquals(KeyAppearance.DEFAULT_KEY_HEIGHT_DP, KeyAppearance.clampKeyHeightDp(Float.NEGATIVE_INFINITY), 0f)
    }

    /**
     * 候选字距（键盘外观页的「字距」滑杆，控制候选词之间的水平间隔）：
     * 与圆角 / 间隙同一套边界约定 —— 定义域 **5~55dp**（2026-10-02 定 5~30dp，2026-10-09 扩两倍）、整格 1dp。
     */
    @Test
    fun spacing_clampsToDomainAndSnapsToStep() {
        assertEquals(KeyAppearance.MIN_SPACING_DP, KeyAppearance.clampSpacingDp(-3f), 0f)
        // 下界外侧一律抬到下界：旧的 4dp（老定义域里的默认值）也要落到 5dp
        assertEquals(KeyAppearance.MIN_SPACING_DP, KeyAppearance.clampSpacingDp(4.4f), 0f)
        assertEquals(KeyAppearance.MAX_SPACING_DP, KeyAppearance.clampSpacingDp(99f), 0f)
        assertEquals(6f, KeyAppearance.clampSpacingDp(5.6f), 0f)
    }

    /** 默认值（圆角 5dp / 间隙 2dp / 字距 10dp）落在定义域内且恰在步进网格上 */
    @Test
    fun defaults_areInsideDomainAndOnStepGrid() {
        assertEquals(5f, KeyAppearance.DEFAULT_CORNER_DP, 0f)
        assertEquals(2f, KeyAppearance.DEFAULT_GAP_DP, 0f)
        // 字距默认 10dp（2026-10-02 定）
        assertEquals(10f, KeyAppearance.DEFAULT_SPACING_DP, 0f)
        // 键高默认 54dp：必须与 keyboard_pinyin.xml 三行容器的 54dp 一致，否则「没动过滑杆」的用户
        // 在首次 configure() 后键盘会莫名变高/变矮（这条把两者钉在一起）
        assertEquals(54f, KeyAppearance.DEFAULT_KEY_HEIGHT_DP, 0f)
        assertTrue(KeyAppearance.DEFAULT_KEY_HEIGHT_DP in KeyAppearance.MIN_KEY_HEIGHT_DP..KeyAppearance.MAX_KEY_HEIGHT_DP)
        assertTrue(KeyAppearance.DEFAULT_CORNER_DP in KeyAppearance.MIN_CORNER_DP..KeyAppearance.MAX_CORNER_DP)
        assertTrue(KeyAppearance.DEFAULT_GAP_DP in KeyAppearance.MIN_GAP_DP..KeyAppearance.MAX_GAP_DP)
        assertTrue(KeyAppearance.DEFAULT_SPACING_DP in KeyAppearance.MIN_SPACING_DP..KeyAppearance.MAX_SPACING_DP)
        // 默认值必须能被钳位原样保留：否则「默认值」在读取时就会被改写，设置页显示与实际不一致
        assertEquals(KeyAppearance.DEFAULT_CORNER_DP, KeyAppearance.clampCornerDp(KeyAppearance.DEFAULT_CORNER_DP), 0f)
        assertEquals(KeyAppearance.DEFAULT_GAP_DP, KeyAppearance.clampGapDp(KeyAppearance.DEFAULT_GAP_DP), 0f)
        assertEquals(KeyAppearance.DEFAULT_SPACING_DP, KeyAppearance.clampSpacingDp(KeyAppearance.DEFAULT_SPACING_DP), 0f)
        assertEquals(KeyAppearance.DEFAULT_KEY_HEIGHT_DP, KeyAppearance.clampKeyHeightDp(KeyAppearance.DEFAULT_KEY_HEIGHT_DP), 0f)
    }

    /**
     * SeekBar 上限 = 定义域跨度 / 步进。
     *
     * 2026-10-09（用户指定：外观页所有滑杆定义域 ×2）——圆角 24→48 格、间隙 16→32 格、
     * 字距 25→50 格、键高 20→40 格。数值变化必须同步这条，否则滑杆要么拖不到上界、要么越界。
     */
    @Test
    fun progressMax_matchesDomainAndStep() {
        assertEquals(48, KeyAppearance.CORNER_PROGRESS_MAX)
        assertEquals(32, KeyAppearance.GAP_PROGRESS_MAX)
        assertEquals(50, KeyAppearance.SPACING_PROGRESS_MAX)
        assertEquals(40, KeyAppearance.KEY_HEIGHT_PROGRESS_MAX)
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
        assertEquals(
            KeyAppearance.MAX_SPACING_DP,
            KeyAppearance.spacingProgressToDp(KeyAppearance.SPACING_PROGRESS_MAX),
            0f,
        )
        assertEquals(
            KeyAppearance.MAX_KEY_HEIGHT_DP,
            KeyAppearance.keyHeightProgressToDp(KeyAppearance.KEY_HEIGHT_PROGRESS_MAX),
            0f,
        )
        // 往返换算不漂：进度 → dp → 进度 必须回到原值（整数步进，不应有累积误差）
        for (p in 0..KeyAppearance.SPACING_PROGRESS_MAX) {
            assertEquals(p, KeyAppearance.spacingDpToProgress(KeyAppearance.spacingProgressToDp(p)))
        }
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

    /** 键高：进度 ↔ dp 全量往返不漂移（步进 2dp，整数运算不应有累积误差） */
    @Test
    fun keyHeightProgress_roundTripIsStable() {
        for (p in 0..KeyAppearance.KEY_HEIGHT_PROGRESS_MAX) {
            val dp = KeyAppearance.keyHeightProgressToDp(p)
            assertEquals("进度 $p 往返", p, KeyAppearance.keyHeightDpToProgress(dp))
        }
    }

    @Test
    fun progress_outOfRangeIsClamped() {
        assertEquals(KeyAppearance.MAX_CORNER_DP, KeyAppearance.cornerProgressToDp(999), 0f)
        assertEquals(KeyAppearance.MIN_CORNER_DP, KeyAppearance.cornerProgressToDp(-3), 0f)
        assertEquals(KeyAppearance.MAX_GAP_DP, KeyAppearance.gapProgressToDp(999), 0f)
        assertEquals(KeyAppearance.MIN_GAP_DP, KeyAppearance.gapProgressToDp(-1), 0f)
        assertEquals(KeyAppearance.MAX_KEY_HEIGHT_DP, KeyAppearance.keyHeightProgressToDp(999), 0f)
        assertEquals(KeyAppearance.MIN_KEY_HEIGHT_DP, KeyAppearance.keyHeightProgressToDp(-2), 0f)
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
     * 间隙上界要「按得动」：每键四边各内缩一半（= 间隙 / 2）。
     *
     * 2026-10-09 上界由 8dp 扩到 16dp（用户指定外观页滑杆定义域 ×2）。代价写清楚：
     * 360dp 宽屏上最窄的字母键约 36dp，最大档内缩 8dp 后仍有 **20dp**（等于项目触控基线）；
     * 而 320dp 宽的小屏（最窄键约 31dp）在最大档只剩 15dp —— 那时要用户自己把滑杆拉回。
     * 本用例保证的是「常见宽度下不缺斤少两」，不是「任意宽度下都好用」，后者靠钳位保证不出负数。
     */
    @Test
    fun maxGap_keepsNarrowestKeyTappable() {
        val insetDp = KeyAppearance.MAX_GAP_DP / 2f
        assertEquals(8f, insetDp, 0f)
        val narrowestKeyDp = 36f
        assertTrue("360dp 屏上最窄键被间隙压没", narrowestKeyDp - insetDp * 2f >= 20f)
        assertTrue("320dp 屏的最窄键宽度应为正（钳位不许出现负宽）", 31f - insetDp * 2f > 0f)
    }

    /** 键高上界 120dp 不能把键盘顶满屏：三行 + 候选栏 + 拼音条 + 底栏 ≤ 490dp */
    @Test
    fun maxKeyHeight_leavesRoomForHost() {
        val totalDp = KeyAppearance.MAX_KEY_HEIGHT_DP * 3 + 44 + 16 + 56
        assertTrue("键高上界会把键盘顶满屏：$totalDp dp", totalDp <= 490f)
    }
}
