package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * 本轮缺陷修复的回归护栏。
 *
 * 每条断言对应一个已交叉验证过的真实缺陷，回归即复现：
 *  - [fieldKeyOf] 守住「暂存粘贴只回原输入框」（跨字段/跨应用注入）；
 *  - [searchRetainLimitReached] 守住「搜索命中集合有驻留上限」（内存护栏）；
 *  - [readCapped] 守住「更新检查响应体限长读取」；
 *  - [isInsideKeyBounds] 守住「按键抬起必须落在键内（含外扩）才算命中」，
 *    分号键曾漏掉该判定，按下后滑到相邻键抬起仍会把 `;` 追加进拼音串。
 *
 * 这些都是曾经没有、被补上的约束，断言失败意味着缺陷回归，
 * 不要通过放宽阈值让它们变绿。
 */
class RecentFixesRegressionTest {

    // ── 暂存粘贴的字段身份 ────────────────────────────────

    @Test
    fun `fieldKeyOf 空包名返回 null 表示身份未知`() {
        assertNull(fieldKeyOf(null, 0))
        assertNull(fieldKeyOf("", 12))
    }

    @Test
    fun `fieldKeyOf 同包同 fieldId 才相等`() {
        assertEquals("com.tencent.mm#7", fieldKeyOf("com.tencent.mm", 7))
        assertEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.tencent.mm", 7))
    }

    @Test
    fun `fieldKeyOf 跨应用同 fieldId 必须不相等`() {
        // 只比对 fieldId 会让微信的粘贴落进备忘录，这正是缺陷本身
        assertNotEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.notes.app", 7))
    }

    @Test
    fun `fieldKeyOf 同应用不同 fieldId 必须不相等`() {
        assertNotEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.tencent.mm", 8))
    }

    // ── 搜索结果的驻留上限 ────────────────────────────────

    @Test
    fun `搜索驻留未触限时继续累积`() {
        assertFalse(ClipboardStore.searchRetainLimitReached(0, 0L))
        assertFalse(
            ClipboardStore.searchRetainLimitReached(
                ClipboardStore.MAX_SEARCH_RESULTS - 1, 0L
            )
        )
    }

    @Test
    fun `搜索驻留条数触顶即停`() {
        assertTrue(
            ClipboardStore.searchRetainLimitReached(ClipboardStore.MAX_SEARCH_RESULTS, 0L)
        )
    }

    @Test
    fun `搜索驻留字节触顶即停_少量巨文本也要拦住`() {
        // 只限条数挡不住 3 条 256KB 的巨文本：字节预算必须独立生效
        assertTrue(
            ClipboardStore.searchRetainLimitReached(
                3, ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES + 1
            )
        )
    }

    @Test
    fun `搜索驻留预算不超过解密窗口预算`() {
        // 驻留集合活得比瞬时解密窗口久，预算只能更小，不能反过来
        assertTrue(
            ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES
        )
    }

    // ── 更新检查响应体限长 ────────────────────────────────

    private fun reader(text: String): BufferedReader = BufferedReader(StringReader(text))

    @Test
    fun `readCapped 未超限返回原文`() {
        val body = "line1\nline2\nline3\n"
        assertEquals(body, UpdateChecker.readCapped(reader(body), 1000))
    }

    @Test
    fun `readCapped 超限时截断且不抛异常`() {
        val long = "x".repeat(50)
        val out = UpdateChecker.readCapped(reader("$long\n$long\n$long\n"), 60)
        assertTrue("超限必须截断: $out", out.length <= 60)
    }

    @Test
    fun `readCapped 空响应返回空串`() {
        assertEquals("", UpdateChecker.readCapped(reader(""), 100))
    }

    // ── 按键命中的几何判定（分号键滑出不得上字）────────────

    @Test
    fun `命中判定 键内与边界外扩内都算命中`() {
        // 键 (100,200) 100x100，外扩 8px
        assertTrue(isInsideKeyBounds(110f, 210f, 100, 200, 100, 100, 8f))   // 正中
        assertTrue(isInsideKeyBounds(93f, 210f, 100, 200, 100, 100, 8f))    // 左边界外扩内
        assertTrue(isInsideKeyBounds(207f, 210f, 100, 200, 100, 100, 8f))   // 右边界外扩内
        assertTrue(isInsideKeyBounds(110f, 193f, 100, 200, 100, 100, 8f))   // 上边界外扩内
        assertTrue(isInsideKeyBounds(110f, 307f, 100, 200, 100, 100, 8f))   // 下边界外扩内
    }

    @Test
    fun `命中判定 滑出到相邻键必须算不命中`() {
        // 分号键的缺陷场景：按下 `;` 后滑到相邻键再抬起，不应把 `;` 追加进拼音串
        assertFalse(isInsideKeyBounds(91f, 210f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(209f, 210f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(110f, 191f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(110f, 309f, 100, 200, 100, 100, 8f))
    }

    @Test
    fun `命中判定 取不到布局信息时保守算命中`() {
        // width/height ≤ 0（未测量/已分离）：宁可保留旧行为，也不能让用户按不出字
        assertTrue(isInsideKeyBounds(0f, 0f, 0, 0, 0, 0, 8f))
        assertTrue(isInsideKeyBounds(9999f, 9999f, 100, 200, 0, 100, 8f))
    }
}
