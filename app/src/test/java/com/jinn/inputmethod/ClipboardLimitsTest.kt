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

    // ── 解密窗口内存预算护栏 ────────────────────────────────────────────────
    //
    // 分页/分块查询会把**整个窗口**逐条解密后才返回，故单次明文峰值 = 窗口条数 × 单条上限。
    // 这组护栏把「窗口 × 单条上限 ≤ 预算」钉住：调大任何一侧都会被拦下。

    @Test
    fun 搜索窗口的最坏内存不超过预算() {
        val peak = ClipboardStore.decryptWindowPeakBytes(SEARCH_WINDOW_ITEMS)
        assertTrue(
            "搜索窗口峰值 ${peak / 1024 / 1024}MB 超预算 ${ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES / 1024 / 1024}MB",
            peak <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES,
        )
    }

    @Test
    fun 面板分页窗口同样在预算内() {
        val peak = ClipboardStore.decryptWindowPeakBytes(PANEL_PAGE_ITEMS)
        assertTrue("面板分页峰值 $peak 超预算", peak <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES)
    }

    @Test
    fun 护栏能拦下旧的300条窗口() {
        // 证明护栏有效：旧值 300 × 256KB ≈ 262MB（含放大），必然越界（若哪天有人调回去，这条会先失败）
        assertTrue(
            "300 条窗口应被判超预算",
            ClipboardStore.decryptWindowPeakBytes(300) > ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES,
        )
    }

    @Test
    fun 峰值估算含密文与String放大而非只算明文() {
        // 只算明文会低估：单条还要算 base64 密文串（≈4/3）与解出的明文串（1.0），实测合计 2.33 倍
        val plainOnly = 10L * ClipboardStore.MAX_ITEM_BYTES
        val estimate = ClipboardStore.decryptWindowPeakBytes(10)
        assertTrue("估算应显著高于纯明文：plain=$plainOnly estimate=$estimate", estimate > plainOnly * 2)
    }

    @Test
    fun 窗口峰值的边界取值() {
        assertEquals(0L, ClipboardStore.decryptWindowPeakBytes(0))
        assertEquals(0L, ClipboardStore.decryptWindowPeakBytes(-5))
        assertEquals(0L, ClipboardStore.decryptWindowPeakBytes(10, maxItemBytes = 0))
    }

    // ── 搜索驻留预算的口径 ──────────────────────────────────────────────────
    //
    // 命中集合要一直活到用户改关键词，所以有独立的驻留预算。预算按 **UTF-8 字节** 算，
    // 而 String.length 是 UTF-16 字符数 —— 中文下 1 字符 = 3 字节，字符数当字节用会把
    // 预算低估到 1/3（200 条顶格中文实际能驻留 ~52MB 而账上只有 17MB）。

    @Test
    fun UTF8字节数与字符数在中英文下不同() {
        assertEquals(3L, ClipboardStore.utf8ByteSize("汉"))
        assertEquals(1L, ClipboardStore.utf8ByteSize("a"))
        assertEquals(0L, ClipboardStore.utf8ByteSize(""))
    }

    @Test
    fun 驻留预算按字节判定时中文不会低估() {
        // 同一批中文内容：按字节判已经触顶，按字符判还差得远 —— 后者正是护栏失效的原因
        val cjk = "汉".repeat(1000)          // 1000 字符 / 3000 字节
        val byBytes = ClipboardStore.utf8ByteSize(cjk)
        assertEquals(3000L, byBytes)
        assertTrue("按字节应触顶", ClipboardStore.searchRetainLimitReached(1, byBytes, maxBytes = 3000))
        assertFalse("按字符数会漏判", ClipboardStore.searchRetainLimitReached(1, cjk.length.toLong(), maxBytes = 3000))
    }

    @Test
    fun 驻留预算条数与字节任一触顶即停() {
        assertFalse(ClipboardStore.searchRetainLimitReached(0, 0))
        assertTrue(ClipboardStore.searchRetainLimitReached(ClipboardStore.MAX_SEARCH_RESULTS, 0))
        assertTrue(ClipboardStore.searchRetainLimitReached(0, ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES))
    }
}
