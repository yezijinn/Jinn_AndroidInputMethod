package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据框「已填过」的显示形态：前 3 位 + 固定 8 个掩码点。
 *
 * 两条要求各自钉一次：① 掩码不泄露凭据长度（点数是常量）；② 保存路径必须能认出掩码形态
 * （认不出来就会把 `b86••••••••` 写回磁盘，凭据永久损坏 —— 这是用户第 4 条要求的硬性错误）。
 */
class CredentialMaskTest {

    @Test
    fun `前三位加固定掩码且不随长度变化`() {
        assertEquals("b86••••••••", CredentialMask.of("b86f2d9a4c1e7f03"))
        // 长凭据与短凭据点数相同：点数固定，不把真实长度说出去
        assertEquals(CredentialMask.of("abcdefgh"), CredentialMask.of("abcdefghijklmnopqrst"))
        // 不足 3 位：全显 + 掩码
        assertEquals("ab••••••••", CredentialMask.of("ab"))
        assertEquals("a••••••••", CredentialMask.of("a"))
        // 未配置：不显示掩码（留给 hint）
        assertEquals("", CredentialMask.of(""))
    }

    @Test
    fun `掩码形态可识别且真实凭据不会被误判`() {
        assertTrue(CredentialMask.looksMasked(CredentialMask.of("b86f2d9a")))
        assertTrue(CredentialMask.looksMasked("x••••"))
        // 真实凭据字符集里没有 U+2022：从输入框取出的真值一律放行
        for (real in listOf("b86f2d9a4c1e7f03", "LTAI5tSecret", "uuid:fx", "a1b2c3", "")) {
            assertFalse("真实凭据被误判成掩码：$real", CredentialMask.looksMasked(real))
        }
    }
}
