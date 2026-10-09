package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板搜索的纯匹配层护栏（BUG.md L-1134 / L-1135 / L-984）。
 *
 * 覆盖三件事：查询词的等价形态（全半角 / 零宽 / 词内空白）、行内容的命中判据（含只在必要时才做的
 * 折叠兜底）、以及收尾的截断状态（命中截断 vs 扫描止损）。
 *
 * 不覆盖：真正的取库与分块扫描（要 Android SQLite，走真机验证）、视图上的提示行渲染。
 */
class ClipboardSearchTest {

    // ── 查询词的等价形态 ────────────────────────────────────────────────────

    @Test
    fun 普通查询只有原形且折成小写() {
        assertEquals(listOf("hello"), ClipboardSearch.queryForms("Hello"))
        // 多词查询：原形之外还给「去掉分隔符」的一种写法（库里存的常常是没空格的那种形态）
        assertEquals(listOf("hi there", "hithere"), ClipboardSearch.queryForms("hi there"))
    }

    @Test
    fun 全角标点会多出一种半角形态() {
        val forms = ClipboardSearch.queryForms("你好，世界")
        assertTrue("原形在", "你好，世界" in forms)
        assertTrue("半角形态在：符号层打的全角逗号要能搜到库里存半角的条目", "你好,世界" in forms)
    }

    @Test
    fun 查询词里的零宽字符会被剥掉() {
        // 从网页 / 聊天记录粘进来的查询词可能夹着零宽空格与 BOM
        val forms = ClipboardSearch.queryForms("关键\u200B词\uFEFF")
        assertTrue("剥零宽后的形态在", "关键词" in forms)
    }

    @Test
    fun 表意空格与普通空格互认() {
        val forms = ClipboardSearch.queryForms("a\u3000b")
        assertTrue("全角空格叠出的普通空格形态在", "a b" in forms)
        assertTrue("去掉分隔符的形态也在", "ab" in forms)
    }

    @Test
    fun 纯空白查询没有任何形态() {
        // contains("") 恒真：空形态一旦留下，空白查询会命中整个库
        assertTrue(ClipboardSearch.queryForms("").isEmpty())
        assertTrue(ClipboardSearch.queryForms("   ").isEmpty())
        assertTrue(ClipboardSearch.queryForms("\u200B").isEmpty())
    }

    // ── 命中判据 ────────────────────────────────────────────────────────────

    @Test
    fun 原形命中与大小写不敏感() {
        assertTrue(ClipboardSearch.matches("你好世界", ClipboardSearch.queryForms("你好")))
        assertTrue(ClipboardSearch.matches("Hello World", ClipboardSearch.queryForms("hello world")))
    }

    @Test
    fun 全角查询命中半角内容() {
        assertTrue(ClipboardSearch.matches("1,234,567", ClipboardSearch.queryForms("1，234")))
    }

    @Test
    fun 半角查询命中全角内容() {
        // 反向：内容里存的是全角，查询打的是半角 —— 靠内容的折叠兜底
        assertTrue(ClipboardSearch.matches("１，２３４", ClipboardSearch.queryForms("1,234")))
    }

    @Test
    fun 含零宽的查询与内容都能互认() {
        assertTrue("查询带零宽", ClipboardSearch.matches("关键词", ClipboardSearch.queryForms("关键\u200B词")))
        assertTrue("内容带零宽", ClipboardSearch.matches("关键\uFEFF词", ClipboardSearch.queryForms("关键词")))
    }

    @Test
    fun 不匹配时不误报() {
        assertFalse(ClipboardSearch.matches("abc", ClipboardSearch.queryForms("abd")))
        // 内容里含全角字符（会走到折叠兜底），但折叠后仍不匹配
        assertFalse(ClipboardSearch.matches("１２３４５", ClipboardSearch.queryForms("99")))
    }

    @Test
    fun 空形态集永不命中() {
        assertFalse(ClipboardSearch.matches("任意内容", emptyList()))
    }

    // ── 收尾的截断状态 ──────────────────────────────────────────────────────

    @Test
    fun 扫完且没触顶时不算截断() {
        assertEquals(
            ClipboardSearch.Cap.NONE,
            ClipboardSearch.capState(retainReached = false, exhausted = true, dbTotal = 500, limit = 20_000),
        )
        // 库刚好等于上限：这些行全扫过了，不算「更旧的没查」
        assertEquals(
            ClipboardSearch.Cap.NONE,
            ClipboardSearch.capState(retainReached = false, exhausted = true, dbTotal = 20_000, limit = 20_000),
        )
    }

    @Test
    fun 库超上限且被掐停时是扫描止损() {
        assertEquals(
            ClipboardSearch.Cap.SCAN,
            ClipboardSearch.capState(retainReached = false, exhausted = false, dbTotal = 50_000, limit = 20_000),
        )
        // 库比上限大，但这一轮扫到没数据才停（库在扫描期间被清空一类）⇒ 不算止损
        assertEquals(
            ClipboardSearch.Cap.NONE,
            ClipboardSearch.capState(retainReached = false, exhausted = true, dbTotal = 50_000, limit = 20_000),
        )
    }

    @Test
    fun 命中截断优先于扫描止损() {
        assertEquals(
            ClipboardSearch.Cap.RETAIN,
            ClipboardSearch.capState(retainReached = true, exhausted = false, dbTotal = 50_000, limit = 20_000),
        )
        assertEquals(
            ClipboardSearch.Cap.RETAIN,
            ClipboardSearch.capState(retainReached = true, exhausted = true, dbTotal = 10, limit = 20_000),
        )
    }
}
