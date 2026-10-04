package com.jinn.inputmethod

import kotlin.math.roundToInt

/**
 * 候选栏的「拼音条 + 候选行」几何，与「上偶下奇」排版规则。
 *
 * 布局（2026-09-25 定的分布；字号由用户参数 [CandidateText] 决定，默认档是拼音 14sp、汉字 20sp）：
 * ```
 *   单行档：拼音条（默认 16dp）              ← 顶部一条
 *          候选 1 3 5 …（横向滚动，默认 28dp）
 *
 *   双行档：上排 2 4 6 …（横向滚动，默认 28dp）
 *          拼音条（**叠在两排之间的中缝上**）
 *          下排 1 3 5 …（横向滚动，默认 28dp）
 * ```
 * 拼音条是**叠放层**：有拼音串时出现（单行档贴顶、双行档垂直居中），无拼音时（功能面板 /
 * 符号分组 / 预测 / 内联提示）隐藏、候选区占满整栏。双行档两排各贴上下边，中缝高度恰好
 * 等于拼音条高度，因此拼音条不遮候选。
 *
 * 高度一律是**字号的函数**（2026-10-03 起字号可调）：候选行 = 字号 × [LINE_HEIGHT_RATIO]、
 * 拼音条 = 拼音字号 × [PINYIN_GLYPH_RATIO]，且都不小于「字号 × 系数」本身 —— 系统字体放大
 * （fontScale > 1）时按同一系数长高，宁可键盘高一点也不裁字；字体缩小时不跟着缩到系数以下
 * （那是栏高的紧凑底线）。所有高度都由 [barHeightPx] 派生，渲染侧不得各算各的。
 *
 * 纯函数（无 Android 依赖），渲染侧与 JVM 单测共用同一份规则。
 */
internal object CandidateRows {

    /** 单行档 */
    const val SINGLE = 1

    /** 双行档 */
    const val DOUBLE = 2

    /** 候选行高系数：字号的 1.4 倍（含行距与降部余量，整行框） */
    const val LINE_HEIGHT_RATIO = 1.4f

    /** 拼音条系数：拼音字号的 1.15 倍（只保证字形，行框余量允许溢出条外） */
    const val PINYIN_GLYPH_RATIO = 1.15f

    /** 候选区左右内边距（dp）：与拼音条内边距同值（`keyboard_pinyin.xml` 与代码各一份，对拍守卫钉住） */
    const val SIDE_PAD_DP = 8

    /** 「✕ 清空候选」按钮宽度（dp）：叠在**拼音条内右端**（不遮候选），拼音文字为它让出同样宽度 */
    const val CLEAR_BUTTON_WIDTH_DP = 40

    /** 单排候选行高（dp，默认字体下）：字号 × [LINE_HEIGHT_RATIO]（默认档 20sp → 28dp） */
    fun rowHeightDp(candidateSp: Float): Float =
        CandidateText.clampSp(candidateSp) * LINE_HEIGHT_RATIO

    /**
     * 拼音条高度（dp，默认字体下）：拼音字号 × [PINYIN_GLYPH_RATIO]（默认档 14sp → 16.1dp）。
     *
     * 比候选行口径更紧：字形之外的字体行框余量可以直接溢出条外（被候选栏裁掉的是空白，不是笔画），
     * 因此条高不再按行框系数算 —— 否则拼音行会「比自己的字高出一圈」（用户 2026-09-25 明确要求
     * 拼音与候选的留白为 0）。
     */
    fun pinyinBarHeightDp(candidateSp: Float): Float =
        CandidateText.pinyinSpOf(candidateSp) * PINYIN_GLYPH_RATIO

    /** 档位对应的候选栏高度（dp，默认字体下；默认档单行 44 / 双行 72） */
    fun heightDp(rows: Int, candidateSp: Float): Int {
        val perRow = rowHeightDp(candidateSp)
        val count = if (rows == DOUBLE) 2 else 1
        return (pinyinBarHeightDp(candidateSp) + perRow * count).roundToInt()
    }

    /** 复用块（功能按钮 / 符号分组标签）的最小高度（dp）：与单行档整栏高度同值 */
    fun singleBarHeightDp(candidateSp: Float): Int = heightDp(SINGLE, candidateSp)

    /**
     * 单排候选的实际高度（px）：字号行高与系统字体放大后的文字行高取大。
     *
     * @param density [android.util.DisplayMetrics.density]
     * @param fontScale 系统字体缩放（`Configuration.fontScale`；1.0 为默认）
     * @param candidateSp 用户设定的候选字号（[CandidateText]）
     */
    fun rowHeightPx(density: Float, fontScale: Float, candidateSp: Float): Int =
        (rowHeightDp(candidateSp) * density * maxOf(1f, fontScale)).toInt()

    /** 拼音条的实际高度（px）：口径同 [rowHeightPx]（只保字形，系数更小） */
    fun pinyinBarHeightPx(density: Float, fontScale: Float, candidateSp: Float): Int =
        (pinyinBarHeightDp(candidateSp) * density * maxOf(1f, fontScale)).toInt()

    /**
     * 候选栏总高度（px）：拼音条 + 档位排数 × 单排 —— 高度的**唯一来源**。
     *
     * 候选栏高度、拼音条避让与列行高必须由它派生：分开算会出现「栏高按旧档、行高按新档」的
     * 一帧错配（列底被裁或留缝）。
     */
    fun barHeightPx(rows: Int, density: Float, fontScale: Float, candidateSp: Float): Int =
        pinyinBarHeightPx(density, fontScale, candidateSp) +
            rowHeightPx(density, fontScale, candidateSp) * if (rows == DOUBLE) 2 else 1

    /**
     * 把候选序列按「上偶下奇」切成若干列。
     *
     * @return 每列一对 `(上排项, 下排项)`，顺序即从左到右；`上排项` 为 `null`
     *         表示候选总数为奇数、该列只有下排（渲染侧须用等高占位补齐，
     *         否则这一列的候选会被父容器的垂直居中拉到中线，与其它列错位）。
     */
    fun columnsOf(candidates: List<String>): List<Pair<String?, String>> {
        val columns = ArrayList<Pair<String?, String>>((candidates.size + 1) / 2)
        var i = 0
        while (i < candidates.size) {
            columns.add(candidates.getOrNull(i + 1) to candidates[i])
            i += 2
        }
        return columns
    }
}
