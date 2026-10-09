package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private fun range() = TextSelection.Range(4, 4, text.length, text)

    // ── 核心：Anchor 固定，Focus 连续移动 ─────────────────

    @Test
    fun anchorFixesAndFocusMovesRight() {
        val anchor = 4
        var focus = 4
        // 第一次 →
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals("第一次右移 Focus=5", 5, focus)
        var (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("这[一]个测试文本", 4 to 5, s to e)
        // 第二次 →（Anchor 必须不变）
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals("第二次右移 Focus=6", 6, focus)
        val pair = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("这[一个]测试文本", 4 to 6, pair.first to pair.second)
        // 第三次 →
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        val p3 = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("这[一个测]试文本", 4 to 7, p3.first to p3.second)
    }

    @Test
    fun focusNeverRebindsToAndroidSelectionStart() {
        val anchor = 4
        var focus = 6
        // 模拟 setSelection(4,6) 后 Android 归一化，若错误地重新取 selectionStart 会得 4
        // 正确做法：Focus 独立维护，从上次 focus=6 继续
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.RIGHT)
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
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.LEFT)
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.LEFT)
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.LEFT)
        assertEquals("三次左移 Focus=1", 1, focus)
        val (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("[是一个]测试文本", 1 to 4, s to e)
    }

    @Test
    fun leftRightToggleKeepsSelection() {
        val anchor = 4
        var focus = 2
        // → 从 2 到 3
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals(3, focus)
        val (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals(3 to 4, s to e)
        // 再 → 到 4（选区缩为 0）
        focus = TextSelection.nextFocus(range(), focus, PinyinKeyboardView.DirectionAction.RIGHT)
        assertEquals(4, focus)
        val (s2, e2) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("Focus 回到 Anchor 时无选区", 4 to 4, s2 to e2)
    }

    // ── 行首/行末使用 Focus ──────────────────────────────

    @Test
    fun lineStartMovesFocusNotAnchor() {
        val anchor = 4
        val focus = TextSelection.nextFocus(range(), 4, PinyinKeyboardView.DirectionAction.LINE_START)
        assertEquals("行首 Focus=0", 0, focus)
        val (s, e) = TextSelection.normalizedSelection(anchor, focus)
        assertEquals("[这是一个测试]文本", 0 to 4, s to e)
    }

    @Test
    fun lineEndMovesFocusToTextEnd() {
        val anchor = 4
        val focus = TextSelection.nextFocus(range(), 4, PinyinKeyboardView.DirectionAction.LINE_END)
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
        assertEquals(0, TextSelection.nextFocus(range(), 0, PinyinKeyboardView.DirectionAction.LEFT))
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

    // ── 期望值队列：认出「我们自己造的选区变化」，把宿主改的判成外部（BUG.md L-14） ──

    @Test
    fun 顺序回调逐个放行() {
        val e = SelectionExpectations()
        e.note(10, 20)
        e.note(12, 22)
        assertTrue(e.consumeIfOurs(10, 20))
        assertTrue(e.consumeIfOurs(12, 22))
    }

    @Test
    fun 乱序回调也要认出早先那次() {
        // 核心回归：方向键连按两下，第二次的回调先到 —— 单值实现会把第一次的回调误判成
        // 「宿主改的选区」而静默退出拖选（用户看到方向键拖到一半突然不动了）
        val e = SelectionExpectations()
        e.note(10, 20)
        e.note(12, 22)
        assertTrue("后发先至：第二次 setSelection 的回调先到", e.consumeIfOurs(12, 22))
        assertTrue("迟到的第一次回调仍必须被认出", e.consumeIfOurs(10, 20))
    }

    @Test
    fun 没记过的选区一律当外部变化() {
        val e = SelectionExpectations()
        e.note(10, 20)
        assertFalse("宿主把光标挪到别处", e.consumeIfOurs(30, 30))
        assertFalse("只差一格也算外部", e.consumeIfOurs(10, 21))
        assertFalse("区间颠倒不算命中", e.consumeIfOurs(20, 10))
        e.clear()
        assertFalse("会话边界清空后，旧期望不再放行", e.consumeIfOurs(10, 20))
    }

    @Test
    fun 同一个期望只放行一次() {
        val e = SelectionExpectations()
        e.note(10, 20)
        assertTrue(e.consumeIfOurs(10, 20))
        assertFalse("重复投递按外部处理（安全侧：宁可多退一次拖选）", e.consumeIfOurs(10, 20))
    }

    @Test
    fun 期望值超出容量时挤掉最旧的() {
        val e = SelectionExpectations() // 默认容量 4
        for (i in 0 until 5) e.note(i, i + 1)
        assertFalse("最旧那次已被挤掉 ⇒ 按外部处理", e.consumeIfOurs(0, 1))
        for (i in 1 until 5) assertTrue(e.consumeIfOurs(i, i + 1))
    }

    @Test
    fun 打包编码不串味() {
        // 用「拼接字符串」或简单异或实现打包时，(1,0) 与 (0,1) 会相撞
        val a = SelectionExpectations()
        a.note(1, 0)
        assertFalse(a.consumeIfOurs(0, 1))
        assertTrue(a.consumeIfOurs(1, 0))

        val b = SelectionExpectations()
        b.note(Int.MAX_VALUE, Int.MIN_VALUE)
        assertTrue(b.consumeIfOurs(Int.MAX_VALUE, Int.MIN_VALUE))
        assertFalse(b.consumeIfOurs(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    // ── L-120：非 BMP 字符（emoji = 代理对）不得被拆开 ─────────────────────────
    // 本文件此前的夹具是「这是一个测试文本」（纯汉字）⇒ 非 BMP 情形 0 覆盖；
    // 实测（修复前）："😀ab" 下 LEFT(2)=1、RIGHT(0)=1，substring(0,1) 得到孤立代理 U+D83D。

    private val emojiText = "😀ab" // 😀 占 [0,1]
    private val emojiMulti = "abcd\n😀x" // 第二行起点 5，😀 占 [5,6]
    private val left = PinyinKeyboardView.DirectionAction.LEFT
    private val right = PinyinKeyboardView.DirectionAction.RIGHT

    /** 孤立代理检测：高代理后面必须紧跟低代理（成对字符不属于孤立代理） */
    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return true
            } else {
                i++
            }
        }
        return false
    }

    @Test
    fun 左右按码点跨过整个代理对() {
        val r = TextSelection.Range(0, 0, emojiText.length, emojiText)
        assertEquals("从 0 右移应跨过 emoji（到 2，而不是停在 1）", 2, TextSelection.nextFocus(r, 0, right))
        assertEquals("从 2 左移应跨过 emoji（到 0，而不是停在 1）", 0, TextSelection.nextFocus(r, 2, left))
        assertEquals("BMP 区间行为不变（2→3）", 3, TextSelection.nextFocus(r, 2, right))
    }

    @Test
    fun 外部带进来的中间下标会被吸附() {
        val r = TextSelection.Range(0, 0, emojiText.length, emojiText)
        assertEquals("吸附函数本身：1 是代理对中间 ⇒ 退到 0", 0, TextSelection.snapToBoundary(emojiText, 1))
        assertEquals("从中间下标左移不留在中间", 0, TextSelection.nextFocus(r, 1, left))
        assertEquals("从中间下标右移不留在中间", 2, TextSelection.nextFocus(r, 1, right))
    }

    @Test
    fun 六个方向动作都不会停在代理对中间() {
        // 元断言：任一入口（含中间下标）+ 任一动作，结果都必须是码点边界。
        // 用 snapToBoundary(pos) == pos 表达「pos 本身就在边界上」。
        val text = "😀a😀b\n😀😀c"
        val r = TextSelection.Range(0, 0, text.length, text)
        val actions = listOf(
            left, right,
            PinyinKeyboardView.DirectionAction.UP,
            PinyinKeyboardView.DirectionAction.DOWN,
            PinyinKeyboardView.DirectionAction.LINE_START,
            PinyinKeyboardView.DirectionAction.LINE_END,
        )
        for (from in 0..text.length) {
            for (a in actions) {
                val pos = TextSelection.nextFocus(r, from, a)
                assertTrue(
                    "动作 $a 从 $from 落到 $pos 会拆开代理对",
                    TextSelection.snapToBoundary(text, pos) == pos,
                )
            }
        }
    }

    @Test
    fun 行移动的列位置不落在代理对中间() {
        assertEquals("列 1 下移应停在 emoji 起点（修复前实测 6 = emoji 中间）", 5, TextSelection.moveLine(emojiMulti, 1, up = false))
        assertEquals("列 2 下移落在 emoji 之后（'x' 处，本就是边界）", 7, TextSelection.moveLine(emojiMulti, 2, up = false))
        assertEquals("从 emoji 中间上移，回到上一行同列", 1, TextSelection.moveLine(emojiMulti, 6, up = true))
    }

    @Test
    fun 拖选复制不会产出孤立代理() {
        // 与 JinnIme.copySelection 同一条路径：normalizedSelection 归一化后 substring。
        val anchor = 0
        var focus = 0
        val r = TextSelection.Range(0, 0, emojiText.length, emojiText)
        for (a in listOf(right, right, right, left, left, left, right)) {
            focus = TextSelection.nextFocus(r, focus, a)
            val (s, e) = TextSelection.normalizedSelection(anchor, focus)
            val sel = emojiText.substring(s, e)
            assertFalse(
                "选区 [$s,$e) 复制出孤立代理：${sel.map { "U+%04X".format(it.code) }}",
                hasLoneSurrogate(sel),
            )
        }
    }

    // ── 搜索框退格（SearchPanelView.backspaceSearch）────────────────

    /**
     * 搜索框退格必须按**码点**删：符号层会把 emoji 追加进搜索框（特殊组点一下即入框），
     * 按 UTF-16 码元删会留下孤立代理项 —— 框里渲染成豆腐块，此后每次退格只删一个码元，
     * 搜索词永远匹配不到任何条目、也删不干净（BUG.md 第 15 批 M4）。
     */
    @Test
    fun 搜索框退格按码点_不留孤立代理() {
        val emoji = "😀" // U+1F600，UTF-16 长度 2
        val cur = StringBuilder("a$emoji")
        cur.delete(TextSelection.stepByCodePoint(cur.toString(), cur.length, -1), cur.length)
        assertEquals("必须整枚 emoji 一起删", "a", cur.toString())
        assertFalse("不得留下孤立代理项", hasLoneSurrogate(cur.toString()))

        // 对照：旧的按码元删必留高代理（这就是原缺陷形态）
        val bad = StringBuilder("a$emoji").apply { delete(length - 1, length) }
        assertTrue("按码元删必留孤立代理项", hasLoneSurrogate(bad.toString()))
    }

    @Test
    fun 搜索框退格用的是码点安全删法() {
        // backspaceSearch 跑在 Android 的 EditText 上，JVM 够不到 ⇒ 源码对拍（剥注释走共用 TestSources）
        val body = TestSources.blockAfter(TestSources.codeSource("SearchPanelView.kt"), "fun backspaceSearch()")
        assertTrue("必须复用 TextSelection.stepByCodePoint: $body", body.contains("stepByCodePoint("))
        assertFalse("不得按 UTF-16 码元删（length - 1）", body.contains("length - 1"))
    }
}
