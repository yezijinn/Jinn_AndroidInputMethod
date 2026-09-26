package com.jinn.inputmethod

import org.junit.Assert.assertEquals
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
}
