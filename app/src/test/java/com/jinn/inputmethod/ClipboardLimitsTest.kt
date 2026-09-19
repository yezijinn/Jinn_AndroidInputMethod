package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板容量两条上限的纯逻辑护栏。
 *
 * 采集侧只拦条数是不够的：单条巨文本（大段代码/日志）能让库无界增长，
 * 因此补两道闸 —— **单条 256KB 直接不入库** + **总量 100MB 按最旧非收藏淘汰**。
 * 这里只测纯函数部分（真正的入库/裁剪要 Android SQLite，走真机验证）。
 */
class ClipboardLimitsTest {

    // ── 单条上限 ────────────────────────────────────────────────────────────

    @Test
    fun 单条按UTF8字节判定() {
        val limit = 1024
        assertFalse("空串不超", ClipboardStore.exceedsItemLimit("", limit))
        assertFalse("恰好等于上限不超", ClipboardStore.exceedsItemLimit("a".repeat(limit), limit))
        assertTrue("超 1 字节即超", ClipboardStore.exceedsItemLimit("a".repeat(limit + 1), limit))
    }

    @Test
    fun 多字节字符按字节而非字符计() {
        // 「汉」UTF-8 占 3 字节
        assertFalse(ClipboardStore.exceedsItemLimit("汉".repeat(3), limit = 9))
        assertTrue(ClipboardStore.exceedsItemLimit("汉".repeat(4), limit = 11))
    }

    @Test
    fun 超长串走字符数快筛且结果一致() {
        // 字符数 > 上限时不必再编码：字节数恒 ≥ 字符数，结论必然为「超」
        val huge = "a".repeat(ClipboardStore.MAX_ITEM_BYTES + 1)
        assertTrue(ClipboardStore.exceedsItemLimit(huge))
    }

    @Test
    fun 非正上限视为不限制() {
        assertFalse(ClipboardStore.exceedsItemLimit("任意内容", limit = 0))
        assertFalse(ClipboardStore.exceedsItemLimit("任意内容", limit = -1))
    }

    // ── 总体积预算淘汰 ──────────────────────────────────────────────────────

    private fun row(id: Long, bytes: Long, fav: Boolean = false) = ClipboardRowSize(id, bytes, fav)

    @Test
    fun 总量未超预算时不删任何条目() {
        assertEquals(emptyList<Long>(), ClipboardDb.overflowIdsForByteBudget(listOf(row(1, 100), row(2, 100)), 200))
        assertEquals(emptyList<Long>(), ClipboardDb.overflowIdsForByteBudget(emptyList(), 100))
    }

    @Test
    fun 超预算时从最旧开始删到落回预算内() {
        // 三档按最旧在前：1→2→3
        val rows = listOf(row(1, 100), row(2, 100), row(3, 100))
        // 总量 300，预算 250 → 删最旧的 1（剩 200 ≤ 250）即停，不多删
        assertEquals(listOf(1L), ClipboardDb.overflowIdsForByteBudget(rows, 250))
        // 预算 150 → 需删 1、2（剩 100）
        assertEquals(listOf(1L, 2L), ClipboardDb.overflowIdsForByteBudget(rows, 150))
    }

    @Test
    fun 收藏计入总量但不参与淘汰() {
        // 最旧的 1 是收藏：跳过它，去删后面的非收藏
        val rows = listOf(
            row(1, 300, fav = true),
            row(2, 100),
            row(3, 100),
        )
        // 总量 500，预算 350 → 跳过收藏 1，删 2（剩 400 仍超）→ 再删 3（剩 300 ≤ 350）
        assertEquals(listOf(2L, 3L), ClipboardDb.overflowIdsForByteBudget(rows, 350))
    }

    @Test
    fun 收藏自身超预算时删完非收藏即停手() {
        // 收藏 300 已超预算 250，非收藏仅 10：全删也回不到预算内，只能停手（不删收藏）
        val rows = listOf(row(1, 300, fav = true), row(2, 10))
        assertEquals(listOf(2L), ClipboardDb.overflowIdsForByteBudget(rows, 250))
    }

    @Test
    fun 非正预算视为不限制() {
        assertEquals(emptyList<Long>(), ClipboardDb.overflowIdsForByteBudget(listOf(row(1, 999)), 0))
        assertEquals(emptyList<Long>(), ClipboardDb.overflowIdsForByteBudget(listOf(row(1, 999)), -1))
    }

    @Test
    fun 默认总量预算为100MB() {
        assertEquals(100L * 1024 * 1024, ClipboardDb.DEFAULT_MAX_TOTAL_BYTES)
    }
}
