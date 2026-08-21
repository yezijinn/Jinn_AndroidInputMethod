package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 剪贴板去重合并规则单测（纯 JVM，bug 审查计划 §23/§24）。
 *
 * 覆盖：收藏/隐私状态在去重时以「任一为 true」合并到保留记录，
 * 避免用户主动标记被静默丢失。
 */
class ClipboardDedupeTest {

    // ── 合并规则 ──────────────────────────────────────────

    @Test
    fun mergePreservesNoFlags() {
        // 保留无标记，旧记录也无标记 → 仍无标记
        val result = ClipboardDb.mergeDedupeFlags(false to false, listOf(false to false))
        assertEquals(false to false, result)
    }

    @Test
    fun mergeKeepsKeptFavorite() {
        // 保留记录已收藏，旧记录未收藏 → 保留收藏
        val result = ClipboardDb.mergeDedupeFlags(true to false, listOf(false to false))
        assertEquals(true to false, result)
    }

    @Test
    fun mergeOrsOldFavorite() {
        // 保留未收藏，旧记录已收藏 → 合并为收藏（关键场景：新复制内容覆盖旧收藏）
        val result = ClipboardDb.mergeDedupeFlags(false to false, listOf(true to false))
        assertEquals(true to false, result)
    }

    @Test
    fun mergeOrsOldPrivate() {
        // 保留未标隐私，旧记录已标隐私 → 合并为隐私
        val result = ClipboardDb.mergeDedupeFlags(false to false, listOf(false to true))
        assertEquals(false to true, result)
    }

    @Test
    fun mergeOrsAcrossMultipleDupes() {
        // 多条旧记录分散标记：一条收藏 + 一条隐私 → 最终收藏+隐私都保留
        val result = ClipboardDb.mergeDedupeFlags(
            false to false,
            listOf(true to false, false to true, false to false),
        )
        assertEquals(true to true, result)
    }

    @Test
    fun mergeEmptyDupes() {
        // 无旧记录（理论不会发生，防御）→ 保留原样
        val result = ClipboardDb.mergeDedupeFlags(true to true, emptyList())
        assertEquals(true to true, result)
    }

    // ── 收藏永久存储语义（§2）──────────────────────────────

    @Test
    fun stableHashIsStable() {
        // 相同内容哈希稳定（去重按内容精确匹配的前提）
        assertEquals(ClipboardDb.stableHash("Hello"), ClipboardDb.stableHash("Hello"))
        // 大小写/空格不同 → 哈希不同（严格字符串比较，不 trim/不转小写）
        assertNotEqualsForTest(ClipboardDb.stableHash("Hello"), ClipboardDb.stableHash("hello"))
        assertNotEqualsForTest(ClipboardDb.stableHash("Hello"), ClipboardDb.stableHash("Hello "))
    }

    private fun assertNotEqualsForTest(a: String, b: String) {
        if (a == b) throw AssertionError("expected different: $a")
    }
}
