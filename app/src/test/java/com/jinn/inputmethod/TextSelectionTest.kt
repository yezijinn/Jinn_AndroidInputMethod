package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文字拖选核心逻辑单测（纯 JVM，不依赖 Android）。
 *
 * 覆盖用户强调的关键点：
 *  1. Anchor 固定、Focus 移动（连续按 → 选区持续扩大）
 *  2. 左右反向（Focus < Anchor 时选区正确归一化）
 *  3. 行首/行末用 Focus（不重置 Anchor）
 *  4. 上下按行移动保持列位置
 *  5. 绝不把 Android selectionStart 当 Anchor
 */
class TextSelectionTest {

    private val text = "这是一个测试文本"
    private fun range(focus: Int) = TextSelection.Range(4, 4, text.length, text)

    // ── 核心：Anchor 固定，Focus 连续移动 ─────────────────

    @Test
    fun anchorFixesAndFocusMovesRight() {
        val anchor = 4
        var focus = 4
        // 第一次 →
        focus = TextSelection.nextFocus(range(focus), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals("第一次右移 Focus=5", 5, focus)
        var (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("这[一]个测试文本", 4 to 5, s to e)
        // 第二次 →（Anchor 必须不变）
        focus = TextSelection.nextFocus(range(focus), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals("第二次右移 Focus=6", 6, focus)
        val pair = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("这[一个]测试文本", 4 to 6, pair.first to pair.second)
        // 第三次 →
        focus = TextSelection.nextFocus(range(focus), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        val p3 = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("这[一个测]试文本", 4 to 7, p3.first to p3.second)
    }

    @Test
    fun focusNeverRebindsToAndroidSelectionStart() {
        val anchor = 4
        var focus = 6
        // 模拟 setSelection(4,6) 后 Android 归一化，若错误地重新取 selectionStart 会得 4
        // 正确做法：Focus 独立维护，从上次 focus=6 继续
        focus = TextSelection.nextFocus(range(6), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals("Focus 从 6 继续而非 4", 7, focus)
        val (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("这[一个测]试文本", 4 to 7, s to e)
    }

    // ── 左右反向 ──────────────────────────────────────────

    @Test
    fun leftDirectionCreatesSelectionBeforeAnchor() {
        val anchor = 4
        var focus = 4
        // ← ← ←
        focus = TextSelection.nextFocus(range(focus), focus, PinyinKeyboardView.DirectionAction.LEFT)
        focus = TextSelection.nextFocus(range(focus), focus, PinyinKeyboardView.DirectionAction.LEFT)
        focus = TextSelection.nextFocus(range(focus), focus, PinyinKeyboardView.DirectionAction.LEFT)
        assertEquals("三次左移 Focus=1", 1, focus)
        val (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("[是一个]测试文本", 1 to 4, s to e)
    }

    @Test
    fun leftRightToggleKeepsSelection() {
        val anchor = 4
        var focus = 2
        // → 从 2 到 3
        focus = TextSelection.nextFocus(range(focus), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals(3, focus)
        val (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals(3 to 4, s to e)
        // 再 → 到 4（选区缩为 0）
        focus = TextSelection.nextFocus(range(focus), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals(4, focus)
        val (s2, e2) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("Focus 回到 Anchor 时无选区", 4 to 4, s2 to e2)
    }

    // ── 行首/行末使用 Focus ──────────────────────────────

    @Test
    fun lineStartMovesFocusNotAnchor() {
        val anchor = 4
        val focus = TextSelection.nextFocus(range(4), 4, PinyinKeyboardView.DirectionAction.LINE_START)
        assertEquals("行首 Focus=0", 0, focus)
        val (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("[这是一个测试]文本", 0 to 4, s to e)
    }

    @Test
    fun lineEndMovesFocusToTextEnd() {
        val anchor = 4
        val focus = TextSelection.nextFocus(range(4), 4, PinyinKeyboardView.DirectionAction.LINE_END)
        assertEquals("行末 Focus=文本长", text.length, focus)
        val (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("[这是一个测试文本]", 4 to text.length, s to e)
    }

    // ── 上下按行移动，保持列位置 ─────────────────────────

    private val multiLine = "第一行文字\n第二行文字\n第三行文字"

    @Test
    fun lineStartEndInSelectionUseCurrentLine() {
        // 行结构：第1行(0-4)+\n(5) | 第2行(6-10)+\n(11) | 第3行(12-16)
        val r = TextSelection.Range(0, multiLine.length, multiLine.length, multiLine)
        // 焦点在第 2 行中部（索引 8）：「行首」应到 6、「行末」应到 11，
        // 而不是全文首尾 0 / 17（与光标态 lineStart/lineEnd 保持一致）
        assertEquals(
            6,
            TextSelection.nextFocus(r, 8, PinyinKeyboardView.DirectionAction.LINE_START),
        )
        assertEquals(
            11,
            TextSelection.nextFocus(r, 8, PinyinKeyboardView.DirectionAction.LINE_END),
        )
    }

    @Test
    fun moveDownKeepsColumn() {
        // 行结构：第1行(0-4)+\n(5) | 第2行(6-10)+\n(11) | 第3行(12-16)
        // 光标在第 1 行第 2 列（位置 2）
        val focus = 2
        val down = TextSelection.moveLine(multiLine, focus, up = false)
        // 第 2 行起点 = 6，同列 +2 → 8
        assertEquals("向下保持列位置", 8, down)
    }

    @Test
    fun moveUpKeepsColumn() {
        // 行结构：第1行(0-4)+\n(5) | 第2行(6-10)+\n(11) | 第3行(12-16)
        // 光标在第 3 行第 2 列（位置 14）
        val focus = 14
        val up = TextSelection.moveLine(multiLine, focus, up = true)
        // 第 2 行起点 = 6，同列 +2 → 8
        assertEquals("向上保持列位置", 8, up)
    }

    @Test
    fun moveDownShortLineClampsToLineEnd() {
        // 第 2 行较短：光标在第 1 行第 4 列（列 4 > 第 2 行长度 4-1）
        val shortText = "abcd\nxy\nzzzz"
        // focus 在第 1 行 col=3（"abcd" 的 d 之后位置 4）
        val focus = 4
        val down = TextSelection.moveLine(shortText, focus, up = false)
        // 第 2 行 "xy" 起点=5，长度 2，行尾 = 7
        assertEquals("目标行较短停在行尾", 7, down)
    }

    @Test
    fun moveUpFromFirstLineGoesToStart() {
        val focus = 2
        assertEquals("首行向上到文本开头", 0, TextSelection.moveLine(multiLine, focus, up = true))
    }

    @Test
    fun moveDownFromLastLineGoesToEnd() {
        val focus = multiLine.length
        assertEquals("末行向下到文本末尾", multiLine.length, TextSelection.moveLine(multiLine, focus, up = false))
    }

    // ── 边界 ──────────────────────────────────────────────

    @Test
    fun focusClampedToTextBounds() {
        assertEquals(0, TextSelection.nextFocus(range(0), 0, PinyinKeyboardView.DirectionAction.LEFT))
        val r = TextSelection.Range(0, 0, text.length, text)
        assertEquals(text.length, TextSelection.nextFocus(r, text.length, PinyinKeyboardView.DirectionAction.RIGHT))
    }

    @Test
    fun emptyTextHandled() {
        assertEquals(0, TextSelection.moveLine("", 0, up = false))
        assertEquals(0, TextSelection.moveLine("", 0, up = true))
    }

    // ── 行首/行末（普通模式光标移动）──────────────────────

    @Test
    fun lineStartOfCurrentLine() {
        val multi = "第一行\n第二行\n第三行"
        // 行结构：第一行(0-3)+\n(4) | 第二行(4-7)+\n(8) | 第三行(8-11)
        // 光标在第 2 行内（位置 7），行首应为 4
        assertEquals(4, TextSelection.lineStart(multi, 7))
        // 光标在第 1 行，行首为 0
        assertEquals(0, TextSelection.lineStart(multi, 2))
        // 光标在文本末尾（第 3 行），行首为 8
        assertEquals(8, TextSelection.lineStart(multi, multi.length))
    }

    @Test
    fun lineEndOfCurrentLine() {
        val multi = "第一行\n第二行\n第三行"
        // 行结构：第一行(0-2)+\n(3) | 第二行(4-6)+\n(7) | 第三行(8-11)
        // 光标在第 1 行内（位置 2），行末应为 3（不含 \n）
        assertEquals(3, TextSelection.lineEnd(multi, 2))
        // 光标在第 2 行（位置 5），行末应为 7
        assertEquals(7, TextSelection.lineEnd(multi, 5))
        // 光标在末尾行，行末为文本长度
        assertEquals(multi.length, TextSelection.lineEnd(multi, multi.length))
    }

    @Test
    fun lineBoundsEmptyText() {
        assertEquals(0, TextSelection.lineStart("", 0))
        assertEquals(0, TextSelection.lineEnd("", 0))
    }

    @Test
    fun lineStartAtTextBeginWithLeadingNewline() {
        // 回归：cursor == 0 时搜索起点不能写成 `(cursor - 1).coerceAtLeast(0)` ，
        // 那会把下标 0 本身纳入搜索，文本以换行开头时就命中它并返回 1，光标反而前进。
        assertEquals("光标在文首：行首就是 0", 0, TextSelection.lineStart("\nabc", 0))
        // 光标落在换行符之后即第二行行首
        assertEquals(1, TextSelection.lineStart("\nabc", 1))
        assertEquals(1, TextSelection.lineStart("\nabc", 2))
        assertEquals(1, TextSelection.lineStart("\nabc", 4))
    }

    @Test
    fun lineStartAtCursorZeroWithoutLeadingNewline() {
        assertEquals(0, TextSelection.lineStart("abc", 0))
        assertEquals(0, TextSelection.lineStart("abc", 1))
    }

    // ── 窗口文本：绝对下标 ↔ 窗口内下标 ────────────────────

    @Test
    fun windowOffsetIsIdentityWhenWholeTextReturned() {
        // 整篇返回（大多少数输入框）：startOffset == 0，换算必须恒等，这条保证本次
        // 修复对常见路径零行为变化。
        for (abs in 0..10) {
            assertEquals(abs, TextSelection.toWindowOffset(abs, startOffset = 0, windowLength = 10))
        }
    }

    @Test
    fun windowOffsetMapsIntoWindowAndRejectsOutside() {
        // 窗口 = 全文 [400, 800)
        assertEquals(0, TextSelection.toWindowOffset(400, 400, 400))
        assertEquals(200, TextSelection.toWindowOffset(600, 400, 400))
        assertEquals("窗口末端也算窗口内", 400, TextSelection.toWindowOffset(800, 400, 400))
        // 落在窗口之外：无从计算，必须返回 null（调用方据此放弃本次操作）
        assertEquals("窗口之前", null, TextSelection.toWindowOffset(399, 400, 400))
        assertEquals("窗口之后", null, TextSelection.toWindowOffset(801, 400, 400))
        // 回归：长文档里光标在窗口外时，旧实现会拿绝对下标去索引窗口文本 ，
        // 行末/行移动越界后返回「窗口长度」那个绝对下标，光标看起来是跳到别处
        assertEquals(null, TextSelection.toWindowOffset(900, 400, 400))
        assertEquals(null, TextSelection.toWindowOffset(5, startOffset = -1, windowLength = 10))
        assertEquals(null, TextSelection.toWindowOffset(5, 0, windowLength = -1))
    }

    @Test
    fun externalSelectionChangeOnlyWhenNotOurs() {
        // 我们自己刚设的选区：回调值与我们记的一致 → 不算外部变化（拖选要继续）
        assertEquals(false, TextSelection.isExternalSelectionChange(10, 20, 10 to 20))
        // 宿主改的：值不一致 → 外部变化（拖选 Anchor/Focus 失效）
        assertEquals(true, TextSelection.isExternalSelectionChange(30, 30, 10 to 20))
        assertEquals(true, TextSelection.isExternalSelectionChange(10, 21, 10 to 20))
        // 没有待放行的期望值 → 一律当外部变化
        assertEquals(true, TextSelection.isExternalSelectionChange(10, 20, null))
    }
}
