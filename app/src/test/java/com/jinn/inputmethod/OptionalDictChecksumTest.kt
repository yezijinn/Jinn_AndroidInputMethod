package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 可选词库下载完整性校验的护栏。
 *
 * 词库内容会直接变成候选词上屏到任意输入框，所以「下载后必须校验 SHA-256」是
 * 一道安全闸门，不是一个可选项。这里的断言覆盖三件事：
 *  - [OptionalDict.checksum] 每一条都填了合法摘要（漏填 = 闸门失效）；
 *  - [OptionalDicts.sha256Of] 的取值与标准 SHA-256 一致（自己实现错就没有意义）；
 *  - [OptionalDicts.matchesChecksum] 对空摘要、错摘要都拒绝（不能「没校验」也算过）。
 *
 * 注意：断言失败意味着完整性校验被绕过，不要通过放宽判据让它变绿。
 */
class OptionalDictChecksumTest {

    @Test
    fun `每个词库包都必须带 64 位十六进制摘要`() {
        assertTrue(OptionalDicts.ALL.isNotEmpty())
        for (dict in OptionalDicts.ALL) {
            assertEquals("${dict.fileName} 摘要必须是 64 位十六进制", 64, dict.checksum.length)
            assertTrue(
                "${dict.fileName} 摘要只能含 0-9a-f",
                dict.checksum.all { it.isDigit() || it in 'a'..'f' },
            )
        }
    }

    @Test
    fun `sha256Of 取值与标准 SHA-256 一致`() {
        val f = File.createTempFile("jinn-dict", ".xz")
        try {
            f.writeBytes("abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                OptionalDicts.sha256Of(f),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun `sha256Of 对大文件分块读取结果不变`() {
        val f = File.createTempFile("jinn-dict-big", ".xz")
        try {
            // 远超内部缓冲区（DEFAULT_BUFFER_SIZE），确保分块累加正确
            val body = "jinn-dict-".repeat(200_000)
            f.writeBytes(body.toByteArray(Charsets.UTF_8))
            assertEquals(sha256Reference(body.toByteArray(Charsets.UTF_8)), OptionalDicts.sha256Of(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `sha256Of 读不到文件时返回 null 而不是假装通过`() {
        val missing = File.createTempFile("jinn-dict-gone", ".xz").also { it.delete() }
        assertEquals(null, OptionalDicts.sha256Of(missing))
        assertFalse(OptionalDicts.matchesChecksum(OptionalDicts.sha256Of(missing), "0".repeat(64)))
    }

    @Test
    fun `文件被改一个字节必须校验失败`() {
        val f = File.createTempFile("jinn-dict-tampered", ".xz")
        try {
            f.writeBytes("jinn dict payload".toByteArray())
            val expected = OptionalDicts.sha256Of(f)!!
            f.writeBytes("jinn dict payloaD".toByteArray())
            assertNotEquals(expected, OptionalDicts.sha256Of(f))
            assertFalse(OptionalDicts.matchesChecksum(OptionalDicts.sha256Of(f), expected))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `matchesChecksum 一致才通过且不区分大小写`() {
        val digest = "f831f41101b555d2f7a54648b3cd3507b55333a7637914a65882c678cce8e5b1"
        assertTrue(OptionalDicts.matchesChecksum(digest, digest))
        assertTrue(OptionalDicts.matchesChecksum(digest.uppercase(), digest))
    }

    @Test
    fun `matchesChecksum 拒绝空摘要与非法摘要`() {
        val digest = "f831f41101b555d2f7a54648b3cd3507b55333a7637914a65882c678cce8e5b1"
        assertFalse(OptionalDicts.matchesChecksum(digest, ""))            // 漏填 checksum
        assertFalse(OptionalDicts.matchesChecksum(digest, "zzzz"))        // 非法字符
        assertFalse(OptionalDicts.matchesChecksum(null, digest))          // 没读到文件
        assertFalse(OptionalDicts.matchesChecksum(null, ""))
    }

    private fun sha256Reference(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
}
