package com.jinn.inputmethod

/**
 * 文字拖选核心逻辑（纯函数，可 JVM 单测）。
 *
 * 设计（对齐电脑鼠标拖选）：
 *  - **Anchor**：进入拖选时固定一次，绝不变；
 *  - **Focus**：方向键/行首/行末控制的对象；
 *  - 选区始终 = setSelection(min(Anchor,Focus), max(Anchor,Focus))，
 *    不依赖 Android 当前的 selectionStart/End（避免归一化导致 Anchor 漂移）。
 *
 * 注意：**绝不把 Android 的 selectionStart 当 Anchor**——setSelection 后
 * selectionStart/End 会随选区变化，每次方向键若重新取它，Anchor 就漂移了。
 */
object TextSelection {

    /** 选区数据（start/end 为已归一化区间，textLength 全文长度，text 全文用于行移动） */
    data class Range(val start: Int, val end: Int, val textLength: Int, val text: String)

    /**
     * 根据方向动作计算新的 Focus（Anchor 不参与计算，保持固定）。
     * @return 新的 Focus 位置
     */
    fun nextFocus(range: Range, focus: Int, action: PinyinKeyboardView.DirectionAction): Int {
        val textLength = range.textLength
        return when (action) {
            PinyinKeyboardView.DirectionAction.LEFT -> (focus - 1).coerceAtLeast(0)
            PinyinKeyboardView.DirectionAction.RIGHT -> (focus + 1).coerceAtMost(textLength)
            PinyinKeyboardView.DirectionAction.UP -> moveLine(range.text, focus, up = true)
            PinyinKeyboardView.DirectionAction.DOWN -> moveLine(range.text, focus, up = false)
            // 行首 / 行末按**当前行**计算，与光标态（JinnIme 里用 lineStart/lineEnd）保持一致。
            // 早前这里直接返回 0 / textLength，拖选时按「行首」会选到全文开头，
            // 在多行文本里与用户预期（选到本行行首，对齐编辑器 Shift+Home）不符。
            PinyinKeyboardView.DirectionAction.LINE_START -> lineStart(range.text, focus)
            PinyinKeyboardView.DirectionAction.LINE_END -> lineEnd(range.text, focus)
            else -> focus
        }
    }

    /** 归一化选区：start <= end */
    fun normalizedSelection(anchor: Int, focus: Int): Pair<Int, Int> =
        minOf(anchor, focus) to maxOf(anchor, focus)

    /**
     * 行级移动 Focus：保持列位置。
     * 目标行较短时停在行尾（对齐编辑器光标行为）。
     * 已在首/末行时：向上→文本开头，向下→文本末尾。
     */
    fun moveLine(text: String, focus: Int, up: Boolean): Int {
        if (text.isEmpty()) return 0
        val lines = text.split("\n")
        // 定位 focus 所在行
        var pos = 0
        var lineIndex = 0
        var lineStart = 0
        var found = false
        for ((i, line) in lines.withIndex()) {
            val lineEnd = pos + line.length
            if (focus in pos..lineEnd) {
                lineIndex = i
                lineStart = pos
                found = true
                break
            }
            pos = lineEnd + 1 // 跳过 \n
        }
        if (!found) return focus.coerceIn(0, text.length)
        val targetLine = lineIndex + (if (up) -1 else 1)
        if (targetLine < 0) return 0 // 首行向上 → 文本开头
        if (targetLine >= lines.size) return text.length // 末行向下 → 文本末尾
        // 目标行起点 + 保持列位置
        val targetStart = lines.take(targetLine).sumOf { it.length + 1 } // +1 每个 \n
        val col = focus - lineStart
        val targetEnd = targetStart + lines[targetLine].length
        return (targetStart + col).coerceAtMost(targetEnd)
    }

    /**
     * 当前行的行首（cursor 所在行的起点，非文本绝对起点）。
     *
     * 注意：cursor == 0 必须直接返回 0。搜索起点要用 `cursor - 1` 表达「严格在光标之前」，
     * 而 `(cursor - 1).coerceAtLeast(0)` 在 cursor == 0 时会把起点钳回 0 —— 连**下标 0 本身**
     * 一起纳入搜索；若文本以换行开头就会命中它并返回 1，光标反而前进一步。
     */
    fun lineStart(text: String, cursor: Int): Int {
        if (text.isEmpty() || cursor <= 0) return 0
        val idx = text.lastIndexOf('\n', cursor - 1)
        return if (idx < 0) 0 else idx + 1
    }

    /** 当前行的行末（cursor 所在行的末尾，不含换行符） */
    fun lineEnd(text: String, cursor: Int): Int {
        if (text.isEmpty()) return 0
        val idx = text.indexOf('\n', cursor)
        return if (idx < 0) text.length else idx
    }
}
