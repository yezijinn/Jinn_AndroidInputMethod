package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 软件密钥兜底（`v2:` 密文）的往返与失败语义。
 *
 * 这条是「无锁屏设备也能保存凭据」的实现基础：本机 `keystore2` 报 `User ECDH key missing`，
 * `CredentialCrypto.encrypt` 恒失败，凭据只能留在内存、重启就丢。加密件以 [File] 为参数，
 * 所以这里能在纯 JVM 上真跑「写入 → 另起一次解密」的重启语义。
 */
class LocalCredentialCipherTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("cred-soft").toFile()

    private fun keyFile(dir: File): File = File(dir, LocalCredentialCipher.FILE_NAME)

    @Test
    fun `软件密钥往返且密钥件落盘后可反复解密`() {
        val dir = tempDir()
        val key = keyFile(dir)
        val plain = "b86f2d9a4c1e7f03"
        val stored = LocalCredentialCipher.encrypt(key, plain)
        assertTrue("加密必须成功（这是无锁屏设备的唯一出路）", stored != null)
        assertTrue("必须带 v2: 前缀（解密侧据此分流）", stored!!.startsWith(LocalCredentialCipher.PREFIX))
        assertEquals(plain, LocalCredentialCipher.decrypt(key, stored))
        // 重启语义：换一个 File 实例指向同一路径再解一次 —— 密钥来自磁盘，不是内存里的那一份
        assertEquals(plain, LocalCredentialCipher.decrypt(File(key.path), stored))
        // 密钥件恰 32 字节（AES-256），不是明文、也不在 APK 里
        assertEquals(LocalCredentialCipher.KEY_BYTES.toLong(), key.length())
    }

    @Test
    fun `同一明文两次加密密文不同且都能解开`() {
        val key = keyFile(tempDir())
        val plain = "LTAI5tSecretValue"
        val a = LocalCredentialCipher.encrypt(key, plain)!!
        val b = LocalCredentialCipher.encrypt(key, plain)!!
        // 每次随机 IV：密文不同（否则「同一凭据」在磁盘上就是可识别的指纹）
        assertNotEquals(a, b)
        assertEquals(plain, LocalCredentialCipher.decrypt(key, a))
        assertEquals(plain, LocalCredentialCipher.decrypt(key, b))
    }

    @Test
    fun `v2 前缀只认软件密钥密文`() {
        assertTrue(LocalCredentialCipher.isLocal("v2:AAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse("Keystore 密文是 v1", LocalCredentialCipher.isLocal("v1:AAAAAAAAAAAAAAAA:AAAA"))
        assertFalse("明文凭据不是密文", LocalCredentialCipher.isLocal("b86f2d9a4c1e7f03"))
        assertFalse(LocalCredentialCipher.isLocal(""))
    }

    @Test
    fun `缺密钥件或密文被改都解不开且不新建密钥`() {
        val dir = tempDir()
        val key = keyFile(dir)
        val stored = LocalCredentialCipher.encrypt(key, "LTAIsecret")!!

        // 另一把密钥（文件不存在）：解不开，且**不许**顺手生成新密钥（那会让旧密文永远读不回来）
        val other = File(dir, "other.key")
        assertNull(LocalCredentialCipher.decrypt(other, stored))
        assertFalse("解密路径不许生成密钥件", other.exists())

        // 密文被改一个字符：GCM 校验不过 ⇒ null（调用方按 Permanent 处理，需重填）
        //
        // ⚠ **末位不能用来做这条判据**：base64 末尾字符的低比特是「未使用位」，换掉它可能解出
        // 完全相同的字节（`A` 与 `B` 的差别只在那两位上）—— 原先只改末位，有约 6% 的概率
        // 密文「改」了却仍能解开，守卫假绿（2026-11-01 实测撞到一次）。
        // 现在逐位改**密文体里除末位之外**的每个字符：每一位都是有效位，改了必须解不开。
        val bodyAt = stored.indexOf(':', LocalCredentialCipher.PREFIX.length) + 1
        for (at in bodyAt until stored.lastIndex) {
            val tampered = stored.substring(0, at) +
                (if (stored[at] == 'A') 'B' else 'A') + stored.substring(at + 1)
            assertNull("密文体第 ${at - bodyAt} 位被改仍解出明文（GCM 完整性失效）", LocalCredentialCipher.decrypt(key, tampered))
        }

        // 密钥件被删：已写入的密文永久解不开，且不重建
        assertTrue(key.delete())
        assertNull(LocalCredentialCipher.decrypt(key, stored))
        assertFalse("密钥件缺失时解密不许重建", key.exists())

        // 形态不对 / 无前缀：同样 null
        assertNull(LocalCredentialCipher.decrypt(key, "v2:只有一段"))
        assertNull(LocalCredentialCipher.decrypt(key, "plain-text"))
    }

    @Test
    fun `空串不产生密文`() {
        val key = keyFile(tempDir())
        assertNull(LocalCredentialCipher.encrypt(key, ""))
        assertFalse("空入参连密钥件都不该生成", key.exists())
    }

    @Test
    fun `密钥件损坏时必须重建以恢复写入，旧密文仍解不开`() {
        val dir = tempDir()
        val key = keyFile(dir)
        val stored = LocalCredentialCipher.encrypt(key, "LTAIsecret")!!
        // 外部损坏（半截密钥件）：这类文件永远解不出任何密文
        key.writeBytes(ByteArray(LocalCredentialCipher.KEY_BYTES - 1))
        assertNull("长度不对的密钥件不许解出明文", LocalCredentialCipher.decrypt(key, stored))

        // 关键：写入不能被它永久卡死。否则用户重填凭据只会得到「未落盘（重启后需重填）」，
        // 而且没有任何自愈路径 —— 只能清应用数据。
        val fresh = LocalCredentialCipher.encrypt(key, "b86f2d9a4c1e7f03")
        assertTrue("损坏的密钥件必须能自愈重建（否则凭据永远存不进去）", fresh != null)
        assertEquals("b86f2d9a4c1e7f03", LocalCredentialCipher.decrypt(key, fresh!!))
        // 新密钥下旧密文依然解不开 —— 绝不假装成功（调用方按 Permanent 处理，用户重填）
        assertNull(LocalCredentialCipher.decrypt(key, stored))
        // 重建后的密钥件仍是 32 字节，且只重建了这一次（没有留下 `.tmp` 残渣）
        assertEquals(LocalCredentialCipher.KEY_BYTES.toLong(), key.length())
        assertFalse("原子写的中间件不许残留", File(dir, LocalCredentialCipher.FILE_NAME + ".tmp").exists())
    }

    /**
     * 读路径**只读**（Stage 3 复核补）。
     *
     * 「长度不对就删」只在写侧 [LocalCredentialCipher.encrypt] 里成立；一旦它被「顺手」搬进共用的
     * `load()`，`decrypt` 也会删 —— 而 `decrypt` 是**每次进设置页都会走**的路径，
     * 真正的危险是「暂时性读失败」落进同一分支：一次 IO 抖动 = 销毁全部凭据。
     * 原先没有用例断言这件事（变异实测：搬进 `load()` 后全套照绿）。
     */
    @Test
    fun `读路径损坏的密钥件也不许删`() {
        val dir = tempDir()
        val key = keyFile(dir)
        val stored = LocalCredentialCipher.encrypt(key, "LTAIsecret")!!
        key.writeBytes(ByteArray(LocalCredentialCipher.KEY_BYTES - 1))

        assertNull(LocalCredentialCipher.decrypt(key, stored))
        assertTrue("解密路径把密钥件删了（读一次毁一把钥匙）", key.exists())
        assertEquals(
            "解密不许改文件（长度应保持被破坏时的样子）",
            (LocalCredentialCipher.KEY_BYTES - 1).toLong(),
            key.length(),
        )
        // 每次读都判一次，不是只在第一次：再来一次同样不许动它
        assertNull(LocalCredentialCipher.decrypt(key, stored))
        assertTrue("第二次读又把密钥件删了", key.exists())
    }

    /**
     * 密钥件的四种状态（Stage 3 复核补）：读侧据此区分「永久解不开」与「这次读不出来」。
     *
     * 前三种能在 JVM 里真跑；`UNREADABLE`（文件在、读抛异常）在 Windows/JVM 上造不出来
     * （没有 POSIX 权限），故改用源码钉住它的归类方向 —— 那条分支的后果是「一次抖动 = 本进程凭据全空」，
     * 必须承认它是**测不到的**，而不是假装覆盖了。
     */
    @Test
    fun `密钥件状态区分缺失损坏与可用`() {
        val dir = tempDir()
        val key = keyFile(dir)
        assertEquals("没有密钥件 = MISSING", LocalCredentialCipher.KeyState.MISSING, LocalCredentialCipher.keyState(key))

        LocalCredentialCipher.encrypt(key, "LTAIsecret")
        assertEquals("32 字节可读 = OK", LocalCredentialCipher.KeyState.OK, LocalCredentialCipher.keyState(key))

        key.writeBytes(ByteArray(LocalCredentialCipher.KEY_BYTES - 1))
        assertEquals("长度不对 = CORRUPT", LocalCredentialCipher.KeyState.CORRUPT, LocalCredentialCipher.keyState(key))
        assertTrue("keyState 是只读的（不许顺手删/重建）", key.exists())

        // 读失败必须归成 UNREADABLE（暂时），不许并进 MISSING / CORRUPT
        val ks = TestSources.blockAfter(
            TestSources.codeSource("LocalCredentialCipher.kt"),
            "fun keyState(keyFile: File)",
        )
        assertTrue("读失败没有归成 UNREADABLE", "KeyState.UNREADABLE" in ks)
        assertTrue("长度判据不见了", "bytes.size == KEY_BYTES" in ks)
        // 写侧的自愈仍只认「长度不对」，不许扩大到「读不出就删」
        val create = TestSources.blockAfter(
            TestSources.codeSource("LocalCredentialCipher.kt"),
            "private fun loadOrCreate(keyFile: File)",
        )
        assertTrue("自愈必须只发生在长度不对时", "existing.size == KEY_BYTES" in create)
        assertTrue("重建前必须确认「读不出来就别动它」", "readBytes() }.getOrNull()" in create)
    }
}
