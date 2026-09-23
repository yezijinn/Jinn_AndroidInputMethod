package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/**
 * 备份包加密（`ConfigCrypto`）：密码规则、往返一致性，以及「改一个字节就解不开」。
 *
 * 这些用例就是安全承诺的守卫：
 *  - 密码只用汉字（用户要求），长度与「同字重复」的边界必须钉住；
 *  - 正确的密码必须能原样还原；错的密码、被改过的密文、被截断的文件一律打不开，
 *    且**不留下任何半成品文件**（否则用户会拿到一份「看着有内容其实是垃圾」的备份）。
 *
 * 迭代数在测试里降到 [FAST_ITERATIONS]：PBKDF2 的强度由参数决定、与逻辑无关，
 * 用 600k 跑十几个用例会让单测慢到没人愿意跑（真实参数由
 * [ConfigCryptoTest 加密参数不得被削弱] 一条守住）。
 */
class ConfigCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 6 个不同汉字，符合「只能用中文」的规则 */
    private val goodPassword = "甲乙丙丁戊己".toCharArray()

    private fun plainFile(bytes: ByteArray): File =
        tmp.newFile().apply { writeBytes(bytes) }

    private fun encFile(): File = File(tmp.newFolder(), "backup.jinn")

    // ── 密码规则 ─────────────────────────────────────────────

    @Test
    fun `只能用汉字 字母数字标点空格 emoji 一律拒绝`() {
        val bad = listOf(
            "abc甲乙丙丁",
            "123456",
            "甲 乙丙丁戊",
            "甲乙丙，丁戊",
            "甲乙丙丁戊己!",   // 半角标点
            "甲乙丙丁🙂戊",
            "ｑｗｅｒｔｙ",       // 全角字母
        )
        for (text in bad) {
            val check = ConfigCrypto.validatePassword(text)
            assertTrue("应拒绝：$text", check is ConfigCrypto.PasswordCheck.Invalid)
        }
        assertTrue(ConfigCrypto.validatePassword("") is ConfigCrypto.PasswordCheck.Invalid)
    }

    @Test
    fun `长度边界 4 到 64 个汉字`() {
        assertTrue(ConfigCrypto.validatePassword("甲乙丙") is ConfigCrypto.PasswordCheck.Invalid)
        assertEquals(ConfigCrypto.PasswordCheck.Ok, ConfigCrypto.validatePassword("甲乙丙丁"))
        assertEquals(ConfigCrypto.PasswordCheck.Ok, ConfigCrypto.validatePassword("备份".repeat(32)))
        assertTrue(
            ConfigCrypto.validatePassword("备份".repeat(33)) is ConfigCrypto.PasswordCheck.Invalid
        )
    }

    @Test
    fun `同一个字重复不算密码`() {
        assertTrue(ConfigCrypto.validatePassword("啊啊啊啊") is ConfigCrypto.PasswordCheck.Invalid)
        assertEquals(ConfigCrypto.PasswordCheck.Ok, ConfigCrypto.validatePassword("啊啊啊啊乙"))
    }

    @Test
    fun `加密参数不得被削弱`() {
        // 用户明确要求「防破解」：这几个数字是安全承诺的一部分，改动必须是有意为之
        assertTrue(ConfigCrypto.ITERATIONS >= 600_000)
        assertEquals(256, ConfigCrypto.KEY_BITS)
        assertEquals(128, ConfigCrypto.TAG_BITS)
        assertTrue(ConfigCrypto.SALT_BYTES >= 16)
        assertTrue(ConfigCrypto.IV_BYTES >= 12)
        assertEquals(
            ConfigCrypto.MAGIC.length + 1 + 4 + ConfigCrypto.SALT_BYTES + ConfigCrypto.IV_BYTES,
            ConfigCrypto.HEADER_BYTES,
        )
    }

    // ── 往返 ────────────────────────────────────────────────

    @Test
    fun `正确密码往返 内容逐字节一致`() {
        val payload = "PK\u0003\u0004 假装这是 zip".toByteArray(Charsets.UTF_8) +
            ByteArray(4096) { (it % 251).toByte() }
        val src = plainFile(payload)
        val dest = encFile()
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, FAST_ITERATIONS))
        assertTrue(dest.length() > payload.size.toLong())   // 头部 + GCM tag

        val restored = File(tmp.newFolder(), "plain.bin")
        assertTrue(ConfigCrypto.decrypt(dest, restored, goodPassword))
        assertTrue(payload.contentEquals(restored.readBytes()))
    }

    @Test
    fun `空文件与单字节文件也能往返`() {
        for (payload in listOf(ByteArray(0), byteArrayOf(7))) {
            val src = plainFile(payload)
            val dest = encFile()
            assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, FAST_ITERATIONS))
            val restored = File(tmp.newFolder(), "restored.bin")
            assertTrue(ConfigCrypto.decrypt(dest, restored, goodPassword))
            assertTrue(payload.contentEquals(restored.readBytes()))
        }
    }

    @Test
    fun `同一内容两次加密产出不同密文（随机 salt 与 IV）`() {
        val src = plainFile("内容".toByteArray(Charsets.UTF_8))
        val a = encFile()
        val b = encFile()
        assertTrue(ConfigCrypto.encrypt(src, a, goodPassword, FAST_ITERATIONS))
        assertTrue(ConfigCrypto.encrypt(src, b, goodPassword, FAST_ITERATIONS))
        assertFalse(a.readBytes().contentEquals(b.readBytes()))
    }

    @Test
    fun `头部写入 magic 版本与迭代数`() {
        val src = plainFile("x".toByteArray(Charsets.UTF_8))
        val dest = encFile()
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, 4321))
        val head = dest.readBytes()
        assertEquals(ConfigCrypto.MAGIC, String(head.copyOfRange(0, ConfigCrypto.MAGIC.length), Charsets.US_ASCII))
        assertEquals(ConfigCrypto.VERSION.toByte(), head[ConfigCrypto.MAGIC.length])
        val iterations = ByteBuffer.wrap(head, ConfigCrypto.MAGIC.length + 1, 4).int
        assertEquals(4321, iterations)
    }

    @Test
    fun `用加密时写入的迭代数解密 与默认值无关`() {
        val src = plainFile("iteration 参数生效".toByteArray(Charsets.UTF_8))
        val dest = encFile()
        // 用「非默认、且在下限之上」的迭代数：解密必须按文件头里写的值复算。
        // （2500 这类低值现在会被下限直接拒绝，见「头部迭代数低于下限的包直接拒绝」一条）
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, FAST_ITERATIONS))
        val restored = File(tmp.newFolder("out"), "restored.bin")
        assertTrue(ConfigCrypto.decrypt(dest, restored, goodPassword))
        assertEquals("iteration 参数生效", restored.readText(Charsets.UTF_8))
    }

    // ── 防篡改 / 错误口令 ────────────────────────────────────

    @Test
    fun `错误密码解不开 且不留下半成品文件`() {
        val src = plainFile("机密内容".toByteArray(Charsets.UTF_8))
        val dest = encFile()
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, FAST_ITERATIONS))
        val restored = File(tmp.newFolder("out"), "restored.bin")
        assertFalse(ConfigCrypto.decrypt(dest, restored, "庚辛壬癸子丑".toCharArray()))
        assertFalse(restored.exists())
    }

    @Test
    fun `密文任意位置被改一个字节都解不开`() {
        val src = plainFile("被篡改的内容".repeat(64).toByteArray(Charsets.UTF_8))
        val dest = encFile()
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, FAST_ITERATIONS))
        val bytes = dest.readBytes()

        // 改最后一个字节（落在 GCM 认证标签里）与改正文中间各一例
        for (index in listOf(bytes.size - 1, ConfigCrypto.HEADER_BYTES + 8)) {
            val tampered = bytes.copyOf()
            tampered[index] = (tampered[index] + 1).toByte()
            val broken = File(tmp.newFolder(), "t.jinn").apply { writeBytes(tampered) }
            val out = File(broken.parentFile, "out.bin")
            assertFalse("改第 $index 字节后仍能解开", ConfigCrypto.decrypt(broken, out, goodPassword))
            assertFalse(out.exists())
        }
    }

    @Test
    fun `头部参数被改（salt IV 迭代数）一律解不开`() {
        val src = plainFile("头部保护".toByteArray(Charsets.UTF_8))
        val dest = encFile()
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, 2048))
        val bytes = dest.readBytes()

        val saltStart = ConfigCrypto.MAGIC.length + 1 + 4
        val ivStart = saltStart + ConfigCrypto.SALT_BYTES
        val targets = listOf(
            ConfigCrypto.MAGIC.length,       // version
            ConfigCrypto.MAGIC.length + 1,   // iterations
            saltStart,
            ivStart,
        )
        for (index in targets) {
            val tampered = bytes.copyOf()
            tampered[index] = (tampered[index] + 1).toByte()
            val broken = File(tmp.newFolder(), "h.jinn").apply { writeBytes(tampered) }
            val out = File(broken.parentFile, "out.bin")
            assertFalse("改头部第 $index 字节后仍能解开", ConfigCrypto.decrypt(broken, out, goodPassword))
            assertFalse(out.exists())
        }
    }

    @Test
    fun `截断的文件解不开`() {
        val src = plainFile("截断测试".repeat(128).toByteArray(Charsets.UTF_8))
        val dest = encFile()
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, FAST_ITERATIONS))
        val full = dest.readBytes()
        // 两种截断：正文被切一半、只剩头部
        for (keep in listOf(full.size / 2, ConfigCrypto.HEADER_BYTES - 3)) {
            val cut = File(tmp.newFolder(), "c.jinn").apply { writeBytes(full.copyOfRange(0, keep)) }
            val out = File(cut.parentFile, "out.bin")
            assertFalse(ConfigCrypto.decrypt(cut, out, goodPassword))
            assertFalse(out.exists())
        }
    }

    @Test
    fun `不是加密包的文件（普通 zip 明文）拒绝解密`() {
        val fake = plainFile("PK\u0003\u0004普通zip".toByteArray(Charsets.UTF_8))
        val out = File(tmp.newFolder(), "out.bin")
        assertFalse(ConfigCrypto.decrypt(fake, out, goodPassword))
        assertFalse(out.exists())
    }

    @Test
    fun `头部迭代数超过上限的包直接拒绝（防构造包把解密拖成假死）`() {
        val src = plainFile("x".toByteArray(Charsets.UTF_8))
        val dest = encFile()
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, FAST_ITERATIONS))
        val bytes = dest.readBytes()
        // 把头部里的迭代数改成 3,000,000（超过 2,000,000 的护栏）
        System.arraycopy(
            ByteBuffer.allocate(4).putInt(3_000_000).array(), 0, bytes,
            ConfigCrypto.MAGIC.length + 1, 4,
        )
        val tampered = File(tmp.newFolder(), "big-iter.jinn").apply { writeBytes(bytes) }
        val out = File(tampered.parentFile, "out.bin")
        assertFalse(ConfigCrypto.decrypt(tampered, out, goodPassword))
        assertFalse(out.exists())
    }

    @Test
    fun `头部迭代数低于下限的包直接拒绝（强度不由文件自己声明）`() {
        val src = plainFile("低强度包".toByteArray(Charsets.UTF_8))
        val dest = encFile()
        // 构造一个 iterations=1000 的包：没有下限时它照样能解开，等于把密文强度交给文件头决定
        assertTrue(ConfigCrypto.encrypt(src, dest, goodPassword, 1_000))
        val restored = File(tmp.newFolder(), "low.bin")
        assertFalse(ConfigCrypto.decrypt(dest, restored, goodPassword))
        assertFalse(restored.exists())
    }

    @Test
    fun `源文件读不到时加密失败 且不留目标与临时文件`() {
        val missing = File(tmp.root, "no-such-source.bin")
        val dir = tmp.newFolder()
        val dest = File(dir, "backup.jinn")
        assertFalse(ConfigCrypto.encrypt(missing, dest, goodPassword, FAST_ITERATIONS))
        assertFalse(dest.exists())
        assertNull(dir.listFiles()?.firstOrNull { it.name.endsWith(".tmp") })
    }

    private companion object {
        /**
         * 测试用迭代数。
         *
         * 取 10 万而不是几百：解密端有迭代数**下限**（挡住「密文强度由文件自己声明」的构造包），
         * 低于下限的包会被直接拒绝。真实参数见 [ConfigCrypto.ITERATIONS]，由参数守卫用例钉住。
         */
        const val FAST_ITERATIONS = 100_000
    }
}
