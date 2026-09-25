package com.jinn.inputmethod

/**
 * 候选栏的「拼音条 + 候选行」几何，与「上偶下奇」排版规则。
 *
 * 布局（2026-09-25 用户定的分布，拼音统一 14sp、汉字统一 20sp）：
 * ```
 *   单行档：拼音条 20dp                      ← 顶部一条
 *          候选 1 3 5 …（横向滚动）32dp
 *
 *   双行档：上排 2 4 6 …（横向滚动）32dp
 *          拼音条 20dp（**叠在两排之间的中缝上**）
 *          下排 1 3 5 …（横向滚动）32dp
 * ```
 * 拼音条是**叠放层**：有拼音串时出现（单行档贴顶、双行档垂直居中），无拼音时（功能面板 /
 * 符号分组 / 预测 / 内联提示）隐藏、候选区占满整栏。双行档两排各贴上下边，中缝高度恰好
 * 等于拼音条高度，因此拼音条不遮候选。
 *
 * 紧凑值（20 / 32dp）只是**下限**：字号是 sp，会随系统「字体大小」放大，固定 dp 在约 150%
 * 时就会裁字；高度一律取「紧凑值与文字行高」的较大者 ⇒ 字体放大时整体长高，宁可键盘高
 * 一点也不裁字。所有高度都由 [barHeightPx] 派生，渲染侧不得各算各的。
 *
 * 纯函数（无 Android 依赖），渲染侧与 JVM 单测共用同一份规则。
 */
internal object CandidateRows {

    /** 单行档 */
    const val SINGLE = 1

    /** 双行档 */
    const val DOUBLE = 2

    /** 候选文字字号（sp）：两档统一（用户 2026-09-25 指定） */
    const val CANDIDATE_TEXT_SP = 20f

    /** 拼音文字字号（sp）：两档统一 */
    const val PINYIN_TEXT_SP = 14f

    /**
     * 拼音条高度（dp）：只保证 14sp 的**字形**放得下（14 × 1.15 ≈ 16）。
     *
     * 比候选行口径更紧：字形之外的字体行框余量可以直接溢出条外（被候选栏裁掉的是空白，不是笔画），
     * 因此条高不再按行框 1.4 系数算 —— 否则拼音行会「比自己的字高出一圈」（用户 2026-09-25 明确要求
     * 拼音与候选的留白为 0）。
     */
    const val PINYIN_BAR_HEIGHT_DP = 16

    /** 单排候选行高（dp）：= 20sp 的文字行框（20 × 1.4 = 28），不含额外留白 */
    const val ROW_HEIGHT_DP = 28

    /** 候选区左右内边距（dp）：与拼音条内边距同值（`keyboard_pinyin.xml` 与代码各一份，对拍守卫钉住） */
    const val SIDE_PAD_DP = 8

    /** 「✕ 清空候选」按钮宽度（dp，叠在整栏右侧）：可见时候选区右内边距让出该宽度 */
    const val CLEAR_BUTTON_WIDTH_DP = 40

    /** 单行档候选栏总高（dp）= 拼音条 + 一排候选（拼音与候选之间不留间隔） */
    const val SINGLE_BAR_HEIGHT_DP = PINYIN_BAR_HEIGHT_DP + ROW_HEIGHT_DP

    /** 双行档候选栏总高（dp）= 拼音条 + 两排候选（拼音条叠在两排之间的中缝上） */
    const val DOUBLE_BAR_HEIGHT_DP = PINYIN_BAR_HEIGHT_DP + ROW_HEIGHT_DP * 2

    /** 单行 / 双行档对应的候选栏高度（dp，默认字体下的紧凑值） */
    fun heightDp(rows: Int): Int =
        if (rows == DOUBLE) DOUBLE_BAR_HEIGHT_DP else SINGLE_BAR_HEIGHT_DP

    /** 候选行高系数：字号的 1.4 倍（含行距与降部余量，整行框） */
    private const val TEXT_LINE_HEIGHT_RATIO = 1.4f

    /** 拼音条系数：字号的 1.15 倍（只保证字形，行框余量允许溢出条外） */
    private const val PINYIN_GLYPH_RATIO = 1.15f

    /**
     * 单排候选的实际高度（px）：紧凑档 [ROW_HEIGHT_DP] 与文字行高的较大者。
     *
     * @param density [android.util.DisplayMetrics.density]
     * @param textPx 候选文字实际像素高（sp 经 `TypedValue.applyDimension` 换算，已含系统字体缩放）
     */
    fun rowHeightPx(density: Float, textPx: Float): Int =
        maxOf((ROW_HEIGHT_DP * density).toInt(), (textPx * TEXT_LINE_HEIGHT_RATIO).toInt())

    /** 拼音条的实际高度（px）：紧凑档 [PINYIN_BAR_HEIGHT_DP] 与字形高度（[PINYIN_GLYPH_RATIO]）取大 */
    fun pinyinBarHeightPx(density: Float, textPx: Float): Int =
        maxOf((PINYIN_BAR_HEIGHT_DP * density).toInt(), (textPx * PINYIN_GLYPH_RATIO).toInt())

    /**
     * 候选栏总高度（px）：拼音条 + 档位排数 × 单排 —— 高度的**唯一来源**。
     *
     * 候选栏高度、拼音条避让与列行高必须由它派生：分开算会出现「栏高按旧档、行高按新档」的
     * 一帧错配（列底被裁或留缝）。
     *
     * @param candidateTextPx 候选文字像素高；@param pinyinTextPx 拼音文字像素高
     */
    fun barHeightPx(
        rows: Int,
        density: Float,
        candidateTextPx: Float,
        pinyinTextPx: Float,
    ): Int {
        val perRow = rowHeightPx(density, candidateTextPx)
        val count = if (rows == DOUBLE) 2 else 1
        return pinyinBarHeightPx(density, pinyinTextPx) + perRow * count
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
