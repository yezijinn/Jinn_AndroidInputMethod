package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断落盘护栏（[Diagnostics.sanitizeForFile] / [Diagnostics.redactSensitive]）的纯函数用例。
 *
 * 背景：日志正文由调用点自撰，一旦把用户内容（手机号 / 邮箱 / 整篇文本）写进 d/i/w 级日志，
 * 会绕过 V 级闸落盘并随导出诊断包外传 —— 这两道处理是唯一的机械防线，改回去必须变红。
 */
class DiagnosticsSanitizeTest {

    @Test
    fun `手机号只遮中间四位`() {
        assertEquals("联系 138****5678 谢谢", Diagnostics.sanitizeForFile("联系 13812345678 谢谢"))
    }

    @Test
    fun `邮箱只遮本地部分保留域名`() {
        assertEquals("发给 ****@example.com", Diagnostics.sanitizeForFile("发给 user.name+tag@example.com"))
    }

    @Test
    fun `十五位以上纯数字只留前四后二`() {
        assertEquals("卡号 6222****23", Diagnostics.sanitizeForFile("卡号 6222021234567890123"))
        assertEquals("1234****45", Diagnostics.sanitizeForFile("123456789012345"))
    }

    @Test
    fun `短数字与十六进制串不受影响`() {
        // 13 位时间戳：长度不足 15，且 11 位手机号规则被数字边界挡住
        assertEquals("ts=1730000000000", Diagnostics.sanitizeForFile("ts=1730000000000"))
        val sha = "a".repeat(16) + "0123456789abcdef"
        assertEquals("hash=$sha", Diagnostics.sanitizeForFile("hash=$sha"))
    }

    @Test
    fun `超长正文截断并标注原长`() {
        val out = Diagnostics.sanitizeForFile("x".repeat(600))
        assertTrue("保留前 512 字", out.startsWith("x".repeat(512)))
        assertTrue("标注被截断与原长", out.contains("截断") && out.contains("600"))
        assertTrue("总长明显小于原文", out.length < 560)
    }

    @Test
    fun `常规计数日志原样保留`() {
        val line = "粘贴: len=15 success=true"
        assertEquals(line, Diagnostics.sanitizeForFile(line))
    }

    /**
     * `BUG.md` L-170：日志目录只有年龄闸（7 天）时，崩溃循环能在一天内写出几百 MB
     * —— 这里验「超体积预算时按最旧先删、当天的文件不动」这条纯函数。
     */
    @Test
    fun 日志体积超预算时必须按最旧先删() {
        val keep = "jinn-today.log"
        assertTrue(
            "预算内不删",
            Diagnostics.overBudgetVictims(listOf(Triple("a", 1L, 10L)), 100L, keep).isEmpty(),
        )
        val victims = Diagnostics.overBudgetVictims(
            listOf(
                Triple("new.log", 300L, 60L),
                Triple("old.log", 100L, 60L),
                Triple(keep, 400L, 60L),
            ),
            100L,
            keep,
        )
        // 180 > 100 ⇒ 删最旧的 old.log(60) 后仍 120 > 100 ⇒ 再删 new.log(60)，留下当天的 keep(60)
        assertEquals("最旧先删、删到预算内、当天文件不动", listOf("old.log", "new.log"), victims)
        assertTrue(
            "只剩当天的文件时不动它（那是正在追加的目标）",
            Diagnostics.overBudgetVictims(listOf(Triple(keep, 9L, 999L)), 10L, keep).isEmpty(),
        )
        // 手算一遍：keep(999B, 时间 9) + x(1B, 时间 1)，预算 10B ⇒ 总 1000 ⇒ 先跳过 keep、删最旧的 x
        // ⇒ 剩 999 仍超、但没有更多可删 ⇒ 恰好 listOf("x")（BUG.md L-190：弱断言用 || 并起两个互斥期望
        // 等于没约束；这条改成精确期望）
        assertEquals(
            "没有更多可删时返回已挑出的那些（不返回 keepName）",
            listOf("x"),
            Diagnostics.overBudgetVictims(listOf(Triple(keep, 9L, 999L), Triple("x", 1L, 1L)), 10L, keep),
        )
    }

    /**
     * `BUG.md` L-169：logcat 快照是「第二条日志通道」，它的护栏必须与日文件同一句，
     * 且只能取**本进程**的行（root 下 logcat 默认给全系统，而这份快照会进要外传的诊断包）。
     */
    @Test
    fun 快照通道必须脱敏且只取本进程() {
        val src = TestSources.codeSource("Diagnostics.kt")
        assertTrue("快照落盘前必须过同一句脱敏", "val safe = redactSensitive(filtered)" in src)
        assertFalse("不得再直接把未脱敏的快照写盘", "dest.writeText(filtered)" in src)
        assertTrue("抓取必须限定本进程", "--pid=\${Process.myPid()}" in src)
        // BUG.md L-188：按 pid 抓失败时必须有**退路**（退回全量）且失败要留痕；
        // 反向钉：单次抓取 + 静默 null 的老形状不得回来
        assertTrue("必须有退回全量的兜底", "退回全量再试一次" in src)
        assertTrue("两次都失败必须留日志", "logcat 快照失败: exit=" in src)
        assertFalse("不得回到「单次抓取 + 静默返回 null」", "val ok = finished && process.exitValue() == 0" in src)
        // BUG.md L-189：清理是尽力而为，但失败不能静默
        assertTrue("体积清理失败必须留痕", "日志体积清理失败" in src)
    }

    /**
     * `BUG.md` L-174（最小部分）：导出诊断包时单个文件读不出来，只该跳过它 ——
     * 原先一个失败就是整包失败、且只返回 null（用户看到「导出失败」却看不到原因）。
     */
    @Test
    fun 导出必须对单文件失败容错() {
        val src = TestSources.codeSource("Diagnostics.kt")
        assertTrue("导出必须先读后写、可跳过", "导出诊断包: 跳过读不出的文件" in src)
        assertFalse("不得再边读边写（失败会留下半截条目）", "f.inputStream().use { it.copyTo(zos) }" in src)
        assertTrue("体积预算必须接在清理路径上", "overBudgetVictims(entries, LOG_DIR_BUDGET_BYTES, nowName)" in src)
    }

}
