package com.jinn.inputmethod

import kotlin.math.roundToInt

/**
 * 「半透明键盘」的定义域与换算（纯 JVM 逻辑，见 KeyTransparencyTest）。
 *
 * 一个旋钮（[Prefs.keyTransparencyPercent]）控制整块键盘四层面板的透明度：
 *  - **背板**（键盘底色 `kb_bg`、面板底 `app_bg`）：[plateAlpha] —— 面上没有文字，可以做得最透；
 *  - **内容面**（键面、按钮，以及**有内容时的候选栏底**）：[surfaceAlpha] —— 必须比背板实，
 *    最透时仍保留 [MIN_SURFACE_ALPHA] 的不透明度，否则文字会糊在应用内容上。
 *    候选栏底按**是否有内容**动态选档（有拼音/候选/预测 → surface；空白铺底 → plate），
 *    见 `PinyinKeyboardView.updateCandidateBarBackground` —— **别改回固定 plate**。
 *
 * 0%（默认）= 完全不透明，与历史观感逐像素一致；[MAX_PERCENT] = 背板可全透（[MIN_PLATE_ALPHA] = 0），
 * 内容面（含有内容时的候选栏底）仍保留 [MIN_SURFACE_ALPHA] —— 再透文字就糊在应用内容上了。
 *
 * 透明度只淡「面」不淡文字 —— 整键 `View.setAlpha` 会把文字一起变淡，
 * 因此**本功能的绘制路径**一律走 [withAlpha] 给面颜色套 alpha，文字/提示色原样保留
 * （例外：符号层禁用态的大写键用整键 alpha 置灰 —— 那是与透明度无关的既有视觉，
 * 见 `PinyinKeyboardView` 符号层分支）。
 *
 * 注：只做「透出」，不做背景模糊（跨窗口模糊要求 API 31+ 且系统开关允许，
 * 本机 Android 10 不支持）—— 这是与「毛玻璃」在观感上的差别。
 */
object KeyTransparency {

    /** 下界：0% = 完全不透明 */
    const val MIN_PERCENT = 0

    /** 上界：100% —— 背板全透，键面/候选栏在最透时仍保留 [MIN_SURFACE_ALPHA]（可读性下限） */
    const val MAX_PERCENT = 100

    /** 默认值：不透明（不改动历史观感） */
    const val DEFAULT_PERCENT = MIN_PERCENT

    /** SeekBar 上界：进度即百分比（1% 一格，用户可在定义域内自由取值） */
    val PROGRESS_MAX: Int = MAX_PERCENT - MIN_PERCENT

    /** 键面（带文字的面）最透时的不透明度：60% 是文字可读性的经验下限 */
    const val MIN_SURFACE_ALPHA = 0.6f

    /** 背板最透时的不透明度（= 1 - MAX_PERCENT / 100，测试守卫用）：100% 时完全透出应用内容 */
    const val MIN_PLATE_ALPHA = 0f

    /** 把任意输入钳到定义域内（脏配置/外部写入一律收敛到合法值） */
    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** 百分比 → SeekBar 进度（口径与 [KeyAppearance] 的换算一致：进度 = 值 − 下界） */
    fun percentToProgress(percent: Int): Int = clampPercent(percent) - MIN_PERCENT

    /** SeekBar 进度 → 百分比 */
    fun progressToPercent(progress: Int): Int = clampPercent(progress + MIN_PERCENT)

    /** 背板（键盘底色）不透明度：0% → 1f，[MAX_PERCENT] → [MIN_PLATE_ALPHA] */
    fun plateAlpha(percent: Int): Float = 1f - clampPercent(percent) / 100f

    /** 键面/候选栏不透明度：0% → 1f，[MAX_PERCENT] → [MIN_SURFACE_ALPHA] */
    fun surfaceAlpha(percent: Int): Float =
        1f - clampPercent(percent) / MAX_PERCENT.toFloat() * (1f - MIN_SURFACE_ALPHA)

    /** 数值文案（与 [KeyAppearance.formatDp] 同为固定格式，整数百分比） */
    fun formatPercent(percent: Int): String = "${clampPercent(percent)}%"

    /** 给颜色套上 0..1 的不透明度：只改 alpha 通道，RGB 原样保留 */
    fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
