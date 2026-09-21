package com.jinn.inputmethod

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 26 键区（3 行 28 键）的统一外观参数：按键圆角半径 + 按键间隙。
 *
 * 「26 键区」在布局上是完整连在一起的一整块区域，共 28 个按键：
 *  - 第一行 10 个字母（qwertyuiop）
 *  - 第二行 9 个字母（asdfghjkl）
 *  - 第三行 切换大写 + 7 个字母（zxcvbnm）+ 删除键
 *
 * 这 28 个键的绘制路径并不相同：26 个字母键由 [PinyinKey] 自绘圆角矩形，
 * 大写键/删除键是 XML 里的 ImageButton（外观由 drawable 背景 + 布局边距决定）。
 * 本对象把两组参数的定义域、默认值与 SeekBar 进度换算收敛到一处，
 * 让 [Prefs]、[PinyinKey]、[PinyinKeyboardView] 与设置页共用同一套边界，
 * 不允许任何一处再写死圆角或间距数值。
 *
 * 「间隙」的语义是**相邻两键之间的空隙**（左右与上下一致）：每个键四边各内缩
 * 半个间隙，两个相邻键的内缩量相加正好等于间隙值。0f 即无缝（键面连成一片）。
 *
 * 纯 JVM 逻辑，不依赖任何 Android API，可直接单测（见 KeyAppearanceTest）。
 */
object KeyAppearance {

    // ── 按键圆角半径（dp）──────────────────────────────────────

    /** 圆角下界：0 为直角 */
    const val MIN_CORNER_DP = 0f

    /** 圆角上界：再大就会把按键削成胶囊形，失去按键辨识度 */
    const val MAX_CORNER_DP = 24f

    /** 圆角步进：设置页 SeekBar 逐格 1dp */
    const val CORNER_STEP_DP = 1f

    /** 默认圆角：1dp（用户 2026-09-21 指定的默认观感：微微圆角） */
    const val DEFAULT_CORNER_DP = 1f

    // ── 按键间隙（dp）──────────────────────────────────────────

    /** 间隙下界：0 为无缝 */
    const val MIN_GAP_DP = 0f

    /** 间隙上界：8dp 时键面已明显收窄，继续加大只会让按键更难点 */
    const val MAX_GAP_DP = 8f

    /** 间隙步进：0.5dp（够细，且 0.5 在二进制里可精确表示，往返换算不会漂） */
    const val GAP_STEP_DP = 0.5f

    /** 默认间隙：0.5dp（用户 2026-09-21 指定的默认观感，恰好一个步进） */
    const val DEFAULT_GAP_DP = 0.5f

    /** SeekBar 最大进度（圆角：0~24dp，每格 1dp）。由边界与步进推导，测试守卫一致性 */
    val CORNER_PROGRESS_MAX: Int = progressSteps(MIN_CORNER_DP, MAX_CORNER_DP, CORNER_STEP_DP)

    /** SeekBar 最大进度（间隙：0~8dp，每格 0.5dp） */
    val GAP_PROGRESS_MAX: Int = progressSteps(MIN_GAP_DP, MAX_GAP_DP, GAP_STEP_DP)

    /** 把任意输入钳到圆角定义域内，并对齐到 [CORNER_STEP_DP] 的整数倍 */
    fun clampCornerDp(dp: Float): Float = snap(dp, MIN_CORNER_DP, MAX_CORNER_DP, DEFAULT_CORNER_DP, CORNER_STEP_DP)

    /** 把任意输入钳到间隙定义域内，并对齐到 [GAP_STEP_DP] 的整数倍 */
    fun clampGapDp(dp: Float): Float = snap(dp, MIN_GAP_DP, MAX_GAP_DP, DEFAULT_GAP_DP, GAP_STEP_DP)

    /** 圆角 dp → SeekBar 进度 */
    fun cornerDpToProgress(dp: Float): Int =
        ((clampCornerDp(dp) - MIN_CORNER_DP) / CORNER_STEP_DP).roundToInt().coerceIn(0, CORNER_PROGRESS_MAX)

    /** SeekBar 进度 → 圆角 dp */
    fun cornerProgressToDp(progress: Int): Float =
        clampCornerDp(MIN_CORNER_DP + progress.coerceIn(0, CORNER_PROGRESS_MAX) * CORNER_STEP_DP)

    /** 间隙 dp → SeekBar 进度 */
    fun gapDpToProgress(dp: Float): Int =
        ((clampGapDp(dp) - MIN_GAP_DP) / GAP_STEP_DP).roundToInt().coerceIn(0, GAP_PROGRESS_MAX)

    /** SeekBar 进度 → 间隙 dp */
    fun gapProgressToDp(progress: Int): Float =
        clampGapDp(MIN_GAP_DP + progress.coerceIn(0, GAP_PROGRESS_MAX) * GAP_STEP_DP)

    /**
     * 设置页数值文案：整数值不带小数（"6 dp"），半格值保留一位（"1.5 dp"）。
     *
     * 固定 Locale.US：与项目其他数值展示一致，避免某些区域设置把小数点渲染成逗号。
     */
    fun formatDp(dp: Float): String {
        // 非有限值退回 0：调用方理应传钳位后的值，这里只做最后一道防线
        val v = if (dp.isFinite()) dp else 0f
        return if (v == v.roundToInt().toFloat()) "${v.roundToInt()} dp"
        else String.format(Locale.US, "%.1f dp", v)
    }

    /** 进度格数 = 定义域跨度 / 步进（跨度不足一格时至少 1 格，避免 SeekBar 无意义） */
    private fun progressSteps(min: Float, max: Float, step: Float): Int {
        if (step <= 0f) return 0
        return ((max - min) / step).roundToInt().coerceAtLeast(1)
    }

    /**
     * 钳位 + 对齐步进。
     *
     * NaN / Infinity 一律退回 [fallback]：`coerceIn` 对 NaN 的比较恒为 false，
     * 直接放行会让 NaN 一路传到 `drawRoundRect`（把整个键面画空），必须在这里拦住。
     */
    private fun snap(value: Float, min: Float, max: Float, fallback: Float, step: Float): Float {
        if (value.isNaN() || value.isInfinite()) return fallback
        val snapped = (value / step).roundToInt() * step
        return snapped.coerceIn(min, max)
    }
}
