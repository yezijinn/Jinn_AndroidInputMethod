package com.jinn.inputmethod

/**
 * 文字拖选核心逻辑（纯函数，可 JVM 单测）。
 *
 * 设计（对齐电脑鼠标拖选）：
 *  - Anchor：进入拖选时固定一次，绝不变；
 *  - Focus：方向键/行首/行末控制的对象；
 *  - 选区始终 = setSelection(min(Anchor,Focus), max(Anchor,Focus))，
 *    不依赖 Android 当前的 selectionStart/End（避免归一化导致 Anchor 漂移）。
 *
 * 绝不把 Android 的 selectionStart 当 Anchor，setSelection 后
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
        return when (action) {
            // 左右按**码点**移动（L-120）：emoji 等非 BMP 字符在 UTF-16 下是长度 2 的代理对，
            // 按单元 ±1 会停在**一个字符的中间** —— 复制出来就是孤立代理（实测 `U+D83D`），
            // 在中间输入更会把一个 emoji 撕成两半；BMP 文本下按码点与按单元完全等价（行为不变）。
            PinyinKeyboardView.DirectionAction.LEFT -> stepByCodePoint(range.text, focus, -1)
            PinyinKeyboardView.DirectionAction.RIGHT -> stepByCodePoint(range.text, focus, +1)
            PinyinKeyboardView.DirectionAction.UP -> moveLine(range.text, focus, up = true)
            PinyinKeyboardView.DirectionAction.DOWN -> moveLine(range.text, focus, up = false)
            // 行首 / 行末按当前行计算，与光标态（JinnIme 里用 lineStart/lineEnd）保持一致。
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
        // 行首 / 行末 / 文本端点本身都是码点边界（`'\n'` 与端点不可能落在代理对里），
        // 但**列位置**可能落在代理对中间（非 BMP 字符占两列）⇒ 结果统一吸附到码点边界（L-120）。
        if (!found) return snapToBoundary(text, focus.coerceIn(0, text.length))
        val targetLine = lineIndex + (if (up) -1 else 1)
        if (targetLine < 0) return 0 // 首行向上 → 文本开头
        if (targetLine >= lines.size) return text.length // 末行向下 → 文本末尾
        // 目标行起点 + 保持列位置
        val targetStart = lines.take(targetLine).sumOf { it.length + 1 } // +1 每个 \n
        val col = focus - lineStart
        val targetEnd = targetStart + lines[targetLine].length
        return snapToBoundary(text, (targetStart + col).coerceAtMost(targetEnd))
    }

    /**
     * 当前行的行首（cursor 所在行的起点，非文本绝对起点）。
     *
     * cursor == 0 必须直接返回 0。搜索起点要用 `cursor - 1` 表达「严格在光标之前」，
     * 而 `(cursor - 1).coerceAtLeast(0)` 在 cursor == 0 时会把起点钳回 0，连下标 0 本身
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

    /**
     * 把 `getExtractedText` 的全文绝对下标换算成窗口内下标（纯函数，便于单测）。
     *
     * `ExtractedText.selectionStart/End` 是全文绝对下标，而 `text` 在长文档下可能只是光标
     * 附近的一段窗口（`startOffset` 才是窗口在全文里的起点）。拿绝对下标直接索引窗口文本，
     * 行移动 / 行首行末会算出完全无关的位置（越界时连窗口长度的那个绝对下标都会被当成结果，
     * 光标表现为「跳」到别处）；而算出来的窗口内位置若直接交给 `setSelection`，宿主又会当成
     * 绝对下标。
     *
     * @return 窗口内下标；落在窗口之外返回 null（此时无从计算，调用方应放弃本次操作）
     */
    internal fun toWindowOffset(absolute: Int, startOffset: Int, windowLength: Int): Int? {
        if (startOffset < 0 || windowLength < 0) return null
        val rel = absolute - startOffset
        return if (rel in 0..windowLength) rel else null
    }

    /**
     * 把下标吸附到**码点边界**（L-120）：位置落在代理对中间时回退一位（退到该字符的高代理处）。
     *
     * 为什么光「移动时按码点走」不够：焦点也可能**从外面**带进来一个中间下标（上一次会话记下的
     * Focus、宿主报回的选区端点），那时再按码点走一步仍可能停在中间。非 BMP 字符（emoji、
     * 部分生僻字）在 UTF-16 下是长度 2 的代理对，它的边界就是高代理所在的下标。
     */
    internal fun snapToBoundary(text: String, pos: Int): Int {
        if (pos <= 0 || pos >= text.length) return pos.coerceIn(0, text.length)
        val midPair = Character.isLowSurrogate(text[pos]) && Character.isHighSurrogate(text[pos - 1])
        return if (midPair) pos - 1 else pos
    }

    /**
     * 按**码点**移动一格（L-120）：`delta` 只取 ±1。BMP 文本下与 `pos ± 1` 完全等价，
     * 非 BMP 字符处则一次跨过整个代理对。
     *
     * 越界就停在端点：`offsetByCodePoints` 在端点附近会抛，故先用 `if` 挡住。
     */
    internal fun stepByCodePoint(text: String, pos: Int, delta: Int): Int {
        val p = snapToBoundary(text, pos)
        if (delta < 0 && p <= 0) return 0
        if (delta > 0 && p >= text.length) return text.length
        return text.offsetByCodePoints(p, delta)
    }
}

/**
 * 我们自己发起的 `setSelection` 期望值（有界队列，便于单测）。
 *
 * 为什么不是一个「最后一次期望值」：方向键连着按两下会发两次 `setSelection`，而宿主回的
 * `onUpdateSelection` 是跨进程回调（目标 App 主线程一卡就可能**乱序投递**）—— 后一次的回调先到时
 * 会把唯一的期望值消费掉，前一次的回调随后到达就被误判成「宿主改的选区」，拖选被静默退出
 * （用户看到的是方向键拖到一半突然不动了）。这里保留最近几次期望值，回调只要对上**任意一个**
 * 就算是我们自己的。
 *
 * 取舍：**对不上一律当外部变化** —— 宁可多退出一次拖选（用户再点一次 ◉），也别拿着错位的
 * Anchor 继续拖选（那会把后续复制 / 输入作用在错误的位置）。因此每个期望值只放行一次
 * （回调重复投递时第二次按外部处理），容量满了挤掉最旧的（被挤掉的期望若迟到，同样按外部处理，
 * 仍在安全侧）。
 *
 * 只在主线程访问：`onUpdateSelection`、方向键回调与会话边界都在主线程。
 */
internal class SelectionExpectations(private val cap: Int = 4) {
    private val pending = ArrayDeque<Long>()

    /** 记下一次我们请求的选区（必须在 `setSelection` 之前调用） */
    fun note(start: Int, end: Int) {
        if (cap <= 0) return
        if (pending.size >= cap) pending.removeFirst()
        pending.addLast(pack(start, end))
    }

    /** 这次回调是不是我们自己造的？命中即消费（一个期望只对一次回调负责） */
    fun consumeIfOurs(start: Int, end: Int): Boolean {
        val i = pending.indexOf(pack(start, end))
        if (i < 0) return false
        pending.removeAt(i)
        return true
    }

    /** 会话边界清空：上个会话的期望在本会话里毫无意义 */
    fun clear() = pending.clear()

    /**
     * (start, end) 打包成一个 Long：两下标都在 int 范围内，掩码后不会互相串味
     * （`(1,0)` 与 `(0,1)`、负值与正数都不能相撞）。
     */
    private fun pack(start: Int, end: Int): Long =
        (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
}
