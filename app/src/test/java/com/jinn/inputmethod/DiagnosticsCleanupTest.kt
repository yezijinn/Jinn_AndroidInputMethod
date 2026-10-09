package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧日志清理的纯逻辑护栏（BUG.md L-972 / L-973）。
 *
 * 清理只在落盘路径上顺带跑（拿不到目录锁就跳过这一轮），真正的删文件要 Android 的文件系统，
 * 走真机验证；这里钉三件在 JVM 上就能判的事：跳过后的短退避、体积闸挑谁删、结果文案的数与事实一致。
 */
class DiagnosticsCleanupTest {

    // ── 跳过后的短退避（L-972①） ───────────────────────────────────────────

    @Test
    fun `跳过后的下一次尝试落在退避之后而不是立刻`() {
        val now = 1_000_000L
        val interval = 86_400_000L // 24 小时
        val retry = 60_000L // 1 分钟
        val last = Diagnostics.cleanupRetryAt(now, interval, retry)

        // 判据与 maybeCleanupOldLogs 相同：now' - lastCleanupAt >= interval 才跑
        assertFalse("刚跳过时不重试（不推进闸的话这里会立刻重试）", now + 1 - last >= interval)
        assertFalse("退避没到不重试", now + retry - 1 - last >= interval)
        assertTrue("退避到点就要补上这一轮", now + retry - last >= interval)
    }

    @Test
    fun `退避值本身等于下次尝试时刻减去一个间隔`() {
        assertEquals(930L, Diagnostics.cleanupRetryAt(now = 1_000L, intervalMs = 100L, retryMs = 30L))
        // 退避短于间隔 ⇒ 结果落在「过去」（下一次尝试很快到）；这是本函数的前提
        assertTrue(Diagnostics.cleanupRetryAt(1_000L, 100L, 30L) < 1_000L)
    }

    // ── 体积闸挑谁删（L-970 / L-170 家族） ─────────────────────────────────

    @Test
    fun `体积没超时不删任何文件`() {
        val entries = listOf(
            Triple("jinn-2026-01-01.log", 100L, 300L),
            Triple("logcat-1.txt", 200L, 300L),
        )
        assertTrue(Diagnostics.overBudgetVictims(entries, budget = 1_000L, keepName = null).isEmpty())
    }

    @Test
    fun `超预算从最旧删起`() {
        val entries = listOf(
            Triple("新.log", 300L, 400L),
            Triple("旧.log", 100L, 400L),
            Triple("中.log", 200L, 400L),
        )
        assertEquals(
            listOf("旧.log", "中.log"),
            Diagnostics.overBudgetVictims(entries, budget = 500L, keepName = null),
        )
    }

    @Test
    fun `当日文件不参与删除但计入总量`() {
        val entries = listOf(
            Triple("jinn-2026-10-10.log", 900L, 800L), // 当日：体积最大，必须是最后一个
            Triple("logcat-1.txt", 100L, 500L),
        )
        // 总量 1300 > 600：先删最旧的 logcat，删完 800 仍超预算，但当日文件不能动
        assertEquals(
            listOf("logcat-1.txt"),
            Diagnostics.overBudgetVictims(entries, budget = 600L, keepName = "jinn-2026-10-10.log"),
        )
    }

    // ── 结果文案（L-972③） ─────────────────────────────────────────────────

    @Test
    fun `结果文案按成功删除计数`() {
        assertEquals(
            "日志体积超预算，已按最旧先删 3 个（释放 2KB）",
            Diagnostics.cleanupResultMessage(target = 3, failed = 0, freedBytes = 2048L),
        )
    }

    @Test
    fun `删除全失败时不说成已删`() {
        // 原先按候选数报「已删 3 个」，与实际相反（BUG.md L-972③）
        assertEquals(
            "日志体积超预算，已按最旧先删 0 个（释放 0KB），3 个删除失败",
            Diagnostics.cleanupResultMessage(target = 3, failed = 3, freedBytes = 0L),
        )
    }

    @Test
    fun `部分失败时数与事实对得上`() {
        assertEquals(
            "日志体积超预算，已按最旧先删 3 个（释放 1KB），2 个删除失败",
            Diagnostics.cleanupResultMessage(target = 5, failed = 2, freedBytes = 1024L),
        )
    }
}
