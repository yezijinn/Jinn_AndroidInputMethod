package com.jinn.inputmethod

import kotlin.math.roundToInt

/**
 * 候选文字字号（sp）：键盘外观页可调，**整个候选栏按它派生**。
 *
 * 语义：本值是**候选词**的字号（默认 20sp）；拼音条字号按 [PINYIN_RATIO] 派生
 * （默认档 20sp → 14sp，与历史观感一致）；栏高、行高、拼音条高度一律由 [CandidateRows]
 * 按本值算 —— 字号与栏高同源，调大不裁字、调小栏高跟着收。
 *
 * 定义域 14~28sp、步进 1sp、默认 20sp。下界是「再小就点不中」；上界按**双行档**定：
 * 28sp 时双行栏高 ≈ 89dp，键盘整体相应长高（键区高度不变）。
 *
 * 与系统「字体大小」的关系：本值单位是 sp，系统放大时两者相乘（20sp × 1.5 = 30sp），
 * 高度由 [CandidateRows] 的行高规则兜住，不会裁字。
 *
 * 纯 JVM 逻辑，不依赖任何 Android API，可直接单测（见 CandidateTextTest）。
 */
object CandidateText {

    /** 字号下界（sp）：比 14sp 更小的候选字在候选栏里点不中 */
    const val MIN_SP = 14f

    /** 字号上界（sp）：双行档下再大就会把宿主输入区挤得太窄 */
    const val MAX_SP = 28f

    /** 字号步进（sp）：滑杆逐格 1sp */
    const val STEP_SP = 1f

    /** 默认字号（sp）：20sp，与历史观感一致 */
    const val DEFAULT_SP = 20f

    /** 拼音字号与候选字号的比例：20sp → 14sp（与「拼音 14sp、候选 20sp」的历史口径一致） */
    const val PINYIN_RATIO = 0.7f

    /** 拼音字号下界（sp）：拼音只是提示行，太小会看不清残码 */
    const val MIN_PINYIN_SP = 12f

    /** SeekBar 最大进度（14~28sp，每格 1sp ⇒ 14 格） */
    val PROGRESS_MAX: Int = ((MAX_SP - MIN_SP) / STEP_SP).roundToInt()

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

    /** 数值文案（键盘外观页）：整数 sp，不带小数 */
    fun formatSp(sp: Float): String = "${clampSp(sp).roundToInt()} sp"
}
