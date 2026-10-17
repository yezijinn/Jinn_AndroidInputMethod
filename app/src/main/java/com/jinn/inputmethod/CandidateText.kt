package com.jinn.inputmethod

import kotlin.math.roundToInt

/**
 * 候选文字字号（sp）：键盘外观页可调，**整个候选栏按它派生**。
 *
 * 语义：本值是**候选词**的字号（默认 20sp）；拼音条字号默认按 [PINYIN_RATIO] 派生
 * （默认档 20sp → 14sp，与历史观感一致）；栏高、行高、拼音条高度一律由 [CandidateRows]
 * 按本值算 —— 字号与栏高同源，调大不裁字、调小栏高跟着收。
 *
 * 定义域 [MIN_SP]~[MAX_SP]sp、步进 [STEP_SP]sp、默认 [DEFAULT_SP]sp。下界是「再小就点不中」；
 * 上界按**双行档**定：栏高由 [CandidateRows] 按字号派生（行高 = 字号 × 系数），两行加拼音条
 * 即候选栏总高，键盘整体相应长高（键区高度不变）。数字一律不写死（BUG.md L-1291：
 * 定义域 2026-10-09 翻倍过，注释里的旧数字会变成后续估预算的错依据）。
 *
 * 与系统「字体大小」的关系：本值单位是 sp，系统放大时两者相乘（20sp × 1.5 = 30sp），
 * 高度由 [CandidateRows] 的行高规则兜住，不会裁字。
 *
 * 纯 JVM 逻辑，不依赖任何 Android API，可直接单测（见 CandidateTextTest）。
 */
object CandidateText {

    /** 字号下界（sp）：比 14sp 更小的候选字在候选栏里点不中 */
    const val MIN_SP = 14f

    /**
     * 字号上界（sp）：42sp（2026-10-09 由 28sp 扩两倍）。
     *
     * 候选栏高度由本值派生（行高 = 字号 × 1.4），42sp ⇒ 双行档约 118dp + 拼音条，
     * 与三行字母键上限（120dp×3）叠加仍在常见可视高度内；宿主输入区会被挤窄，属用户自选档。
     */
    const val MAX_SP = 42f

    /** 字号步进（sp）：滑杆逐格 1sp */
    const val STEP_SP = 1f

    /** 默认字号（sp）：20sp，与历史观感一致 */
    const val DEFAULT_SP = 20f

    /** 拼音字号与候选字号的比例：20sp → 14sp（与「拼音 14sp、候选 20sp」的历史口径一致） */
    const val PINYIN_RATIO = 0.7f

    /** 拼音字号下界（sp）：拼音只是提示行，太小会看不清残码 */
    const val MIN_PINYIN_SP = 12f

    /**
     * 拼音字号上界（sp）：30 覆盖候选 42sp 时的派生值 29.4（[PINYIN_RATIO]），
     * 用户手工调大也不会越过「拼音行比候选行矮」的观感。
     */
    const val MAX_PINYIN_SP = 30f

    /**
     * 拼音字号「自动」哨兵值（sp）：0 = 沿用 [pinyinSpOf] 的派生口径（老用户观感不变）。
     *
     * 用户动过滑杆后写具体值；本值同时是 [Prefs.pinyinTextSp] 的默认值。
     */
    const val PINYIN_DEFAULT_AUTO = 0f

    /** 拼音字号步进（sp）：滑杆逐格 1sp */
    const val STEP_PINYIN_SP = 1f

    /** SeekBar 最大进度：[MIN_SP]~[MAX_SP]、每格 [STEP_SP]（数字从常量推导，见 BUG.md L-1291） */
    val PROGRESS_MAX: Int = ((MAX_SP - MIN_SP) / STEP_SP).roundToInt()

    /** 拼音字号 SeekBar 最大进度（[MIN_PINYIN_SP]~[MAX_PINYIN_SP]），同样从常量推导 */
    val PINYIN_PROGRESS_MAX: Int =
        ((MAX_PINYIN_SP - MIN_PINYIN_SP) / STEP_PINYIN_SP).roundToInt()

    /** 把任意输入钳到定义域内，并对齐到 [STEP_SP] 的整数倍 */
    fun clampSp(sp: Float): Float {
        // NaN / Infinity 一律退回默认值：coerceIn 对 NaN 的比较恒为 false，放行会让 NaN 一路
        // 传到 setTextSize 与布局高度（文字或整栏画不出来）
        if (sp.isNaN() || sp.isInfinite()) return DEFAULT_SP
        val snapped = (sp / STEP_SP).roundToInt() * STEP_SP
        return snapped.coerceIn(MIN_SP, MAX_SP)
    }

    /** 字号 sp → SeekBar 进度 */
    fun spToProgress(sp: Float): Int =
        ((clampSp(sp) - MIN_SP) / STEP_SP).roundToInt().coerceIn(0, PROGRESS_MAX)

    /** SeekBar 进度 → 字号 sp */
    fun progressToSp(progress: Int): Float =
        clampSp(MIN_SP + progress.coerceIn(0, PROGRESS_MAX) * STEP_SP)

    /** 候选字号 → 拼音条字号（按 [PINYIN_RATIO] 派生，不低于 [MIN_PINYIN_SP]） */
    fun pinyinSpOf(candidateSp: Float): Float =
        maxOf(MIN_PINYIN_SP, clampSp(candidateSp) * PINYIN_RATIO)

    /**
     * 拼音字号归一：**0 保留为「自动」**（[PINYIN_DEFAULT_AUTO]），其余对齐 [STEP_PINYIN_SP]
     * 并钳到 [MIN_PINYIN_SP]~[MAX_PINYIN_SP]。
     *
     * NaN / Inf 退回 0（等价「自动」）：放行会让脏值一路传到 `setTextSize` 与布局高度。
     */
    fun clampPinyinSp(sp: Float): Float {
        if (sp.isNaN() || sp.isInfinite() || sp == PINYIN_DEFAULT_AUTO) return PINYIN_DEFAULT_AUTO
        val snapped = (sp / STEP_PINYIN_SP).roundToInt() * STEP_PINYIN_SP
        return snapped.coerceIn(MIN_PINYIN_SP, MAX_PINYIN_SP)
    }

    /** 拼音字号 sp → SeekBar 进度（[PINYIN_DEFAULT_AUTO] 先按 [MIN_PINYIN_SP] 落格） */
    fun pinyinSpToProgress(sp: Float): Int =
        ((maxOf(clampPinyinSp(sp), MIN_PINYIN_SP) - MIN_PINYIN_SP) / STEP_PINYIN_SP)
            .roundToInt().coerceIn(0, PINYIN_PROGRESS_MAX)

    /** SeekBar 进度 → 拼音字号 sp */
    fun pinyinProgressToSp(progress: Int): Float =
        clampPinyinSp(MIN_PINYIN_SP + progress.coerceIn(0, PINYIN_PROGRESS_MAX) * STEP_PINYIN_SP)

    /**
     * 拼音字号文案：**自动档显示派生值**（[autoValue] 的整数 + " sp"），否则显示用户的具体值。
     *
     * @param sp 用户参数（0 = 自动）
     * @param autoValue 自动档实际生效的拼音字号（通常传 [pinyinSpOf] 的结果）
     */
    fun formatPinyinSp(sp: Float, autoValue: Float): String {
        val shown = if (clampPinyinSp(sp) == PINYIN_DEFAULT_AUTO) autoValue else sp
        return "${clampPinyinSp(shown).roundToInt()} sp"
    }

    /** 数值文案（键盘外观页）：整数 sp，不带小数 */
    fun formatSp(sp: Float): String = "${clampSp(sp).roundToInt()} sp"
}
