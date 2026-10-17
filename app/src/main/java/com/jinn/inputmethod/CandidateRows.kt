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
 * 拼音条 = 拼音字号 × [PINYIN_GLYPH_RATIO]（拼音字号可独立于候选字号设定），且都不小于「字号 × 系数」本身 —— 系统字体放大
 * （fontScale > 1）时按同一系数长高，宁可键盘高一点也不裁字；字体缩小时不跟着缩到系数以下
 * （那是栏高的紧凑底线）。拼音行间隔（[PINYIN_GAP_MIN_DP]~[PINYIN_GAP_MAX_DP]dp）计入栏高：
 * `拼音条高 = 拼音字号 × 系数 + 行间隔`。所有高度都由 [barHeightPx] 派生，渲染侧不得各算各的。
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

    /** 拼音行间隔下界（dp）：0 = 拼音条与候选行之间不留空隙（历史观感） */
    const val PINYIN_GAP_MIN_DP = 0f

    /** 拼音行间隔上界（dp）：再大就吃掉候选行，属自选极端档 */
    const val PINYIN_GAP_MAX_DP = 16f

    /** 候选区左右内边距（dp）：与拼音条内边距同值（`keyboard_pinyin.xml` 与代码各一份，对拍守卫钉住） */
    const val SIDE_PAD_DP = 8

    /** 「✕ 清空候选」按钮宽度（dp）：叠在**拼音条内右端**（不遮候选），拼音文字为它让出同样宽度 */
    const val CLEAR_BUTTON_WIDTH_DP = 40

    /** 单排候选行高（dp，默认字体下）：字号 × [LINE_HEIGHT_RATIO]（默认档 20sp → 28dp） */
    fun rowHeightDp(candidateSp: Float): Float =
        CandidateText.clampSp(candidateSp) * LINE_HEIGHT_RATIO

    /**
     * 拼音条高度（dp，默认字体下）：**拼音字号** × [PINYIN_GLYPH_RATIO]（默认档 14sp → 16.1dp）。
     *
     * @param pinyinSp 拼音字号（sp，用户参数经 [CandidateText.clampPinyinSp] 归一后的**有效值**）；
     *   传 0 / 负数时回落 [CandidateText.pinyinSpOf] 的默认派生值，避免首帧算成 0 高。
     *
     * 比候选行口径更紧：字形之外的字体行框余量可以直接溢出条外（被候选栏裁掉的是空白，不是笔画），
     * 因此条高不再按行框系数算 —— 否则拼音行会「比自己的字高出一圈」（用户 2026-09-25 明确要求
     * 拼音与候选的留白为 0）。
     */
    fun pinyinBarHeightDp(pinyinSp: Float): Float =
        effectivePinyinSp(pinyinSp) * PINYIN_GLYPH_RATIO

    /** 拼音行间隔（dp）：钳到 [PINYIN_GAP_MIN_DP]~[PINYIN_GAP_MAX_DP] */
    fun clampPinyinGapDp(gapDp: Float): Float {
        if (gapDp.isNaN() || gapDp.isInfinite()) return PINYIN_GAP_MIN_DP
        return gapDp.coerceIn(PINYIN_GAP_MIN_DP, PINYIN_GAP_MAX_DP)
    }

    /** 拼音行间隔（px）：计入候选栏总高，也让单行档候选区顶部避让同步增加 */
    fun pinyinGapPx(density: Float, gapDp: Float): Int =
        (clampPinyinGapDp(gapDp) * density).toInt()

    /** 有效拼音字号：显式值归一，缺省（≤0）回落默认派生值 */
    private fun effectivePinyinSp(pinyinSp: Float): Float =
        if (pinyinSp > 0f) CandidateText.clampPinyinSp(pinyinSp)
        else CandidateText.pinyinSpOf(CandidateText.DEFAULT_SP)

    /** 档位对应的候选栏高度（dp，默认字体下；默认档单行 44 / 双行 72） */
    fun heightDp(
        rows: Int,
        candidateSp: Float,
        pinyinSp: Float = CandidateText.pinyinSpOf(candidateSp),
        gapDp: Float = PINYIN_GAP_MIN_DP,
    ): Int {
        val perRow = rowHeightDp(candidateSp)
        val count = if (rows == DOUBLE) 2 else 1
        return (pinyinBarHeightDp(pinyinSp) + clampPinyinGapDp(gapDp) + perRow * count).roundToInt()
    }

    /** 复用块（功能按钮 / 符号分组标签）的最小高度（dp）：与单行档整栏高度同值 */
    fun singleBarHeightDp(
        candidateSp: Float,
        pinyinSp: Float = CandidateText.pinyinSpOf(candidateSp),
        gapDp: Float = PINYIN_GAP_MIN_DP,
    ): Int = heightDp(SINGLE, candidateSp, pinyinSp, gapDp)

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
    fun pinyinBarHeightPx(density: Float, fontScale: Float, pinyinSp: Float): Int =
        (pinyinBarHeightDp(pinyinSp) * density * maxOf(1f, fontScale)).toInt()

    /**
     * 展开态一屏最多显示的行数（作者 2026-10-17 指定上限为四行）
     */
    const val MAX_EXPANDED_ROWS = 4

    /**
     * 候选栏总高度（px）：拼音条（含拼音行间隔）+ 排数 × 单排 —— 高度的**唯一来源**。
     *
     * 候选栏高度、拼音条避让与列行高必须由它派生：分开算会出现「栏高按旧档、行高按新档」的
     * 一帧错配（列底被裁或留缝）。
     *
     * 排数扩展到 1..[MAX_EXPANDED_ROWS]（原只认 1 / 2）：展开态最多四排。
     *
     * @param pinyinSp 有效拼音字号（缺省沿用 [CandidateText.pinyinSpOf] 的派生口径）
     * @param gapDp 拼音行间隔（缺省 0，保持历史观感）
     */
    fun barHeightPx(
        rows: Int,
        density: Float,
        fontScale: Float,
        candidateSp: Float,
        pinyinSp: Float = CandidateText.pinyinSpOf(candidateSp),
        gapDp: Float = PINYIN_GAP_MIN_DP,
    ): Int =
        pinyinBarHeightPx(density, fontScale, pinyinSp) +
            pinyinGapPx(density, gapDp) +
            rowHeightPx(density, fontScale, candidateSp) * clampRows(rows)

    /** 把排数夹到合法区间 [1, MAX_EXPANDED_ROWS]（纯函数，供各处复用同一口径） */
    fun clampRows(rows: Int): Int = rows.coerceIn(1, MAX_EXPANDED_ROWS)

    /**
     * 展开态**可见**排数：按实际候选量算，再被上限与可用高度夹取（BUG.md L-1393）。
     *
     * @param count 本帧候选条数
     * @param perColumn 一列能放几条（= 该排能显示几列 ⇒ 由栏宽 ÷ 列宽得出；≤1 时按 1 处理）
     * @param maxRowsByHeight 可用高度允许的最大排数（屏幕不够时由调用方给出；传 ≥上限表示不夹）
     * @return 实际可见排数，恒在 1..[MAX_EXPANDED_ROWS]
     */
    fun visibleRows(count: Int, perColumn: Int, maxRowsByHeight: Int = MAX_EXPANDED_ROWS): Int {
        if (count <= 0) return 1
        val cols = perColumn.coerceAtLeast(1)
        val needed = (count + cols - 1) / cols // 向上取整
        return clampRows(minOf(needed, maxRowsByHeight))
    }

    /**
     * 把候选切成 N 排一列的若干列（展开态用；N = [rows]）。
     *
     * 与 [columnsOf] 同思路但支持任意排数：**列内自上而下 = 候选原序**，故「第一列第一条」就是
     * 最高词频；列数不足时补 null 占位（渲染侧用等高占位补齐，否则该列会塌缩、位置错乱）。
     *
     * @return 每列一个长度固定为 [rows] 的列表，顺序从左到右；列内自上而下。
     */
    fun columnsOfN(candidates: List<String>, rows: Int): List<List<String?>> {
        val n = clampRows(rows)
        if (candidates.isEmpty()) return emptyList()
        val columnCount = (candidates.size + n - 1) / n
        val out = ArrayList<List<String?>>(columnCount)
        for (c in 0 until columnCount) {
            val column = ArrayList<String?>(n)
            for (r in 0 until n) column.add(candidates.getOrNull(c * n + r))
            out.add(column)
        }
        return out
    }

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
