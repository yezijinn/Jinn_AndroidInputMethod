package com.jinn.inputmethod

/**
 * 候选栏行数与「上偶下奇」排版规则。
 *
 * 双行形态（视觉）：
 * ```
 *   2   4   6      ← 上排 = 第 2 / 4 / 6 … 个候选
 *   1   3   5      ← 下排 = 第 1 / 3 / 5 … 个候选
 * ```
 * 高频候选落在离键盘更近的下排；相邻序号上下成列。横滑由外层单个
 * `HorizontalScrollView` 承担，两排天然同步，不存在只有一行滚动导致的错位。
 *
 * 纯函数（无 Android 依赖），渲染侧与 JVM 单测共用同一份规则。
 */
internal object CandidateRows {

    /** 单行档 */
    const val SINGLE = 1

    /** 双行档 */
    const val DOUBLE = 2

    /** 单行档的**整栏**高度（dp，= keyboard_pinyin.xml 的历史值） */
    const val SINGLE_BAR_HEIGHT_DP = 48

    /** 双行档**每排**的行高（dp）：两排合计即 [DOUBLE_BAR_HEIGHT_DP] */
    const val DOUBLE_ROW_EACH_DP = 36

    /** 双行档的**整栏**高度（dp）：2 × 36 */
    const val DOUBLE_BAR_HEIGHT_DP = DOUBLE_ROW_EACH_DP * 2

    /** 单行 / 双行档对应的候选栏高度（dp） */
    fun heightDp(rows: Int): Int =
        if (rows == DOUBLE) DOUBLE_BAR_HEIGHT_DP else SINGLE_BAR_HEIGHT_DP

    /** 单排文字的行高系数：字号的 1.4 倍（含行距与降部余量） */
    private const val TEXT_LINE_HEIGHT_RATIO = 1.4f

    /**
     * 双行档**单排**的实际高度（px）。
     *
     * 字号是 sp，会随系统「字体大小」放大，而固定 36dp 在约 150% 时就会把文字下半裁掉。
     * 故取「紧凑档 36dp」与「文字行高」的较大者：默认字体下恒等于 36dp（双排 72dp，
     * 与 [heightDp] 一致），字体放大时整体长高 —— 宁可键盘高一点，也不裁字。
     *
     * @param density [android.util.DisplayMetrics.density]
     * @param textPx 单字实际像素高（sp 经 `TypedValue.applyDimension` 换算，已含系统字体缩放）
     */
    fun rowHeightPx(density: Float, textPx: Float): Int {
        val compact = (DOUBLE_ROW_EACH_DP * density).toInt()
        return maxOf(compact, (textPx * TEXT_LINE_HEIGHT_RATIO).toInt())
    }

    /** 候选文字字号（sp）：双行行高更矮，字号同步收一档 */
    fun textSizeSp(rows: Int): Float = if (rows == DOUBLE) 20f else 21f

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
