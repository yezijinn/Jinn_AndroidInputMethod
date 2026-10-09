package com.jinn.inputmethod

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 26 键区（3 行 28 键）的统一外观参数：按键圆角半径 + 按键间隙 + 按键高度。
 *
 * 「26 键区」在布局上是完整连在一起的一整块区域，共 28 个按键：
 *  - 第一行 10 个字母（qwertyuiop）
 *  - 第二行 9 个字母（asdfghjkl）
 *  - 第三行 切换大写 + 7 个字母（zxcvbnm）+ 删除键
 *
 * 这 28 个键的绘制路径并不相同：26 个字母键由 [PinyinKey] 自绘圆角矩形，
 * 大写键/删除键是 XML 里的 ImageButton（外观由 drawable 背景 + 布局边距决定）。
 * 本对象把各项参数的定义域、默认值与 SeekBar 进度换算收敛到一处，
 * 让 [Prefs]、[PinyinKey]、[PinyinKeyboardView] 与键盘外观页共用同一套边界，
 * 不允许任何一处再写死圆角、间距或键高数值。
 *
 * 「间隙」的语义是相邻两键之间的空隙（左右与上下一致）：每个键四边各内缩
 * 半个间隙，两个相邻键的内缩量相加正好等于间隙值。0f 即无缝（键面连成一片）。
 *
 * 纯 JVM 逻辑，不依赖任何 Android API，可直接单测（见 KeyAppearanceTest）。
 */
object KeyAppearance {

    // ── 按键圆角半径（dp）──────────────────────────────────────

    /** 圆角下界：0 为直角 */
    const val MIN_CORNER_DP = 0f

    /**
     * 圆角上界：48dp（2026-10-09 由 24dp 扩两倍 —— 用户指定外观页所有滑杆定义域 ×2）。
     *
     * 超过键高一半后按键会自然收成胶囊形（绘制层对半径有钳位，不会画出畸形图形，也不会崩），
     * 所以这里的安全性只与「形状是否还认得出来」有关，属用户自选的观感档。
     */
    const val MAX_CORNER_DP = 48f

    /** 圆角步进：键盘外观页 SeekBar 逐格 1dp */
    const val CORNER_STEP_DP = 1f

    /** 默认圆角：5dp（2026-09-23 定；此前的 1dp 已被取代） */
    const val DEFAULT_CORNER_DP = 5f

    // ── 按键间隙（dp）──────────────────────────────────────────

    /** 间隙下界：0 为无缝 */
    const val MIN_GAP_DP = 0f

    /**
     * 间隙上界：16dp（2026-10-09 由 8dp 扩两倍）。
     *
     * 代价说清楚：360dp 宽屏上最窄的字母键约 36dp，最大档每键四边各内缩 8dp，仍有 20dp 可点
     * （等于项目触控基线）；320dp 小屏的最窄键约 31dp，最大档只剩 15dp —— 那时需要用户自己拉回滑杆。
     * 越界不会崩、不会夹到负数（有钳位），属自选极端档。
     */
    const val MAX_GAP_DP = 16f

    /** 间隙步进：0.5dp（够细，且 0.5 在二进制里可精确表示，往返换算不会漂） */
    const val GAP_STEP_DP = 0.5f

    /** 默认间隙：2dp（2026-09-23 定；4 个步进，此前的 0.5dp 已被取代） */
    const val DEFAULT_GAP_DP = 2f

    /** SeekBar 最大进度（圆角：见 [MIN_CORNER_DP]~[MAX_CORNER_DP]，步进 [CORNER_STEP_DP]）。由边界与步进推导，测试守卫一致性 */
    val CORNER_PROGRESS_MAX: Int = progressSteps(MIN_CORNER_DP, MAX_CORNER_DP, CORNER_STEP_DP)

    /** SeekBar 最大进度（间隙：见 [MIN_GAP_DP]~[MAX_GAP_DP]，步进 [GAP_STEP_DP]） */
    val GAP_PROGRESS_MAX: Int = progressSteps(MIN_GAP_DP, MAX_GAP_DP, GAP_STEP_DP)

    // ── 按键高度（dp）──────────────────────────────────────────

    /**
     * 三行字母键的**单行高度**（26 键区 28 个键同高：10 + 9 + 9）。
     *
     * 语义就一条：它是 `keyboard_pinyin.xml` 里三个行容器的 `layout_height`。
     * 键自己都是 `match_parent`，所以行高定全行；三行同时套同一个值，键盘不会出现「行高参差」。
     */

    /** 高度下界：再矮就放不下「字母 + 韵母提示」两行字，触控目标也低于常用基线 */
    const val MIN_KEY_HEIGHT_DP = 40f

    /**
     * 高度上界（dp）。挑值的约束是「三行加附件后仍给宿主留出输入区」：
     * 3 × 本值 + 候选栏（44dp）+ 拼音条（16dp）+ 底栏（56dp）必须落在测试守卫的屏幕预算内
     * （见 `KeyAppearanceTest` 的留白断言，改这里先跑测试）。
     * 剪贴板 / 图库面板的高度不跟随键高（固定 162dp×2），所以本项放大不会把面板顶出屏幕。
     */
    const val MAX_KEY_HEIGHT_DP = 90f

    /** 高度步进：2dp（观感可辨的最小变化；再细会让 SeekBar 格数过多而难以点准） */
    const val KEY_HEIGHT_STEP_DP = 2f

    /**
     * 默认键高：54dp。
     *
     * 与 `keyboard_pinyin.xml` 里三行的 54dp **刻意取同一个数**：默认值即「用户没动过滑杆时的原始观感」，
     * 两者一旦分叉，从没打开过外观页的用户会在首次 `configure()` 后看到键盘莫名变高或变矮。
     */
    const val DEFAULT_KEY_HEIGHT_DP = 54f

    /** SeekBar 最大进度（高度：见 [MIN_KEY_HEIGHT_DP]~[MAX_KEY_HEIGHT_DP]，步进 [KEY_HEIGHT_STEP_DP]） */
    val KEY_HEIGHT_PROGRESS_MAX: Int = progressSteps(MIN_KEY_HEIGHT_DP, MAX_KEY_HEIGHT_DP, KEY_HEIGHT_STEP_DP)

    /** 把任意输入钳到高度定义域内，并对齐到 [KEY_HEIGHT_STEP_DP] 的整数倍 */
    fun clampKeyHeightDp(dp: Float): Float =
        snap(dp, MIN_KEY_HEIGHT_DP, MAX_KEY_HEIGHT_DP, DEFAULT_KEY_HEIGHT_DP, KEY_HEIGHT_STEP_DP)

    /** 高度 dp → SeekBar 进度 */
    fun keyHeightDpToProgress(dp: Float): Int =
        ((clampKeyHeightDp(dp) - MIN_KEY_HEIGHT_DP) / KEY_HEIGHT_STEP_DP).roundToInt()
            .coerceIn(0, KEY_HEIGHT_PROGRESS_MAX)

    /** SeekBar 进度 → 高度 dp */
    fun keyHeightProgressToDp(progress: Int): Float =
        clampKeyHeightDp(MIN_KEY_HEIGHT_DP + progress.coerceIn(0, KEY_HEIGHT_PROGRESS_MAX) * KEY_HEIGHT_STEP_DP)

    // ── 候选栏字距（dp）────────────────────────────────────────

    /**
     * 候选词之间的**水平间隔**（2026-10-02 定：只调水平方向，垂直行高不动）。
     *
     * 语义与「按键间隙」一致：[DEFAULT_SPACING_DP] 指的是**相邻两个候选之间**的空隙 ——
     * 实现上每个候选左右各内缩「字距的一半」，两个相邻候选的内缩相加正好等于字距。
     */
    const val MIN_SPACING_DP = 5f

    /**
     * 字距上界：55dp（2026-10-09 由 30dp 扩两倍）。
     *
     * 只影响候选之间的水平间隔：越大候选越疏、越依赖横向滚动，行高与栏高都不变，
     * 也不会把候选挤出屏幕外（容器是横向滚动的）。
     */
    const val MAX_SPACING_DP = 55f

    /** 字距步进：1dp（间隔是观感量，半格在候选栏上看不出来） */
    const val SPACING_STEP_DP = 1f

    /** 默认字距：10dp（2026-10-02 定；此前的 0~20dp 定义域与 4dp 默认值已被取代） */
    const val DEFAULT_SPACING_DP = 10f

    /** SeekBar 最大进度（字距：5~30dp，每格 1dp ⇒ 25 格） */
    val SPACING_PROGRESS_MAX: Int = progressSteps(MIN_SPACING_DP, MAX_SPACING_DP, SPACING_STEP_DP)

    /** 把任意输入钳到字距定义域内，并对齐到 [SPACING_STEP_DP] 的整数倍 */
    fun clampSpacingDp(dp: Float): Float =
        snap(dp, MIN_SPACING_DP, MAX_SPACING_DP, DEFAULT_SPACING_DP, SPACING_STEP_DP)

    /** 字距 dp → SeekBar 进度 */
    fun spacingDpToProgress(dp: Float): Int =
        ((clampSpacingDp(dp) - MIN_SPACING_DP) / SPACING_STEP_DP).roundToInt()
            .coerceIn(0, SPACING_PROGRESS_MAX)

    /** SeekBar 进度 → 字距 dp */
    fun spacingProgressToDp(progress: Int): Float =
        clampSpacingDp(MIN_SPACING_DP + progress.coerceIn(0, SPACING_PROGRESS_MAX) * SPACING_STEP_DP)

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
     * 键盘外观页数值文案：整数值不带小数（"6 dp"），半格值保留一位（"1.5 dp"）。
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
