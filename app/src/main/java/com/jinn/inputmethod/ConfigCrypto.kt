package com.jinn.inputmethod

import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置备份包的密码加密（导出前加密、导入前解密）。
 *
 * 威胁模型：备份包含剪贴板明文（身份证号、手机号、地址、银行卡、账号口令都在其中），
 * 文件一旦落到网盘 / 聊天记录就等于泄露。所以**整包加密**，且必须能扛住离线暴力破解。
 *
 * 算法与参数（全部走平台自带的 JCE，不引第三方库）：
 *  - 密钥派生：PBKDF2-HMAC-SHA256，**600,000 次迭代**（OWASP 对 PBKDF2-SHA256 的建议量级），
 *    每包独立 16 字节随机 salt —— 同一密码在两次导出中产出不同密钥，彩虹表与「撞包」都无效；
 *  - 内容加密：AES-256-GCM，每包独立 12 字节随机 IV，**认证标签 128 位**。
 *    GCM 自带完整性校验，密文被改一个字节就解密失败，顺带成为「密码是否正确」的判据
 *    （伪造一个能通过校验的包需要 2^128 次尝试，不现实）；
 *  - 头部只放公开参数（magic / 版本 / 迭代数 / salt / IV），不含任何用户数据。
 *
 * 迭代数写在文件头：将来调高默认值时，旧包仍按旧参数解开（可平滑升级）。
 *
 * 密码规则（用户要求：只能用中文）：全部码点必须是 Unicode HAN 汉字、长度 4~64 个汉字、
 * 不能是同一个字重复。中文密码的可用字符空间远大于英文按键组合，配合 600k 次迭代，
 * 离线爆破成本极高。
 *
 * 线程模型：加密解密是纯计算 + 流式文件 IO，**必须在后台线程调用**（600k 次迭代在手机上
 * 约需 1~2 秒）。密码以 CharArray 传入，调用方用完后负责清零（String 无法可靠擦除）。
 */
internal object ConfigCrypto {

    /** 文件头标识（8 字节 ASCII）：用于区分「加密备份」与任意文件 */
    const val MAGIC = "JINNENC1"

    /** 外壳格式版本（与 [ConfigBackup.FORMAT_VERSION] 独立：一个管加密壳，一个管 zip 内容） */
    const val VERSION = 1

    /** PBKDF2 迭代次数（OWASP 建议量级；写进文件头，将来可调高） */
    const val ITERATIONS = 600_000

    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val KEY_BITS = 256
    const val TAG_BITS = 128

    /** 头部字节数：magic(8) + version(1) + iterations(4) + salt(16) + iv(12) */
    const val HEADER_BYTES = MAGIC.length + 1 + 4 + SALT_BYTES + IV_BYTES

    /**
     * 密码长度定义域（按汉字个数计）。
     *
     * 下限 4 是用户 2026-09-23 定的下限（6 个字嫌多）。4 个汉字的组合空间已远大于常见弱口令，
     * 叠加 600k 次 PBKDF2 后离线爆破成本依然极高；界面会建议用 6 个字以上。
     */
    const val MIN_PASSWORD_CHARS = 4
    const val MAX_PASSWORD_CHARS = 64

    /**
     * 迭代数上限：解析外部文件时的护栏。
     *
     * 取 200 万（约本机默认值的三倍多）：给将来调高强度留足空间，同时挡住「构造一个超大迭代数的包」
     * 把解密拖成几十秒到几分钟的假死（界面只会显示「正在解密…」，用户会以为卡死）。
     */
    private const val MAX_ITERATIONS = 2_000_000

    /**
     * 迭代数下限。
     *
     * 文件头里的迭代数是**外部输入**：不设下限等于把密文强度交给文件自己声明 ——
     * 一个 `iterations=1` 的包照样能解开，与「600k 迭代」的安全承诺不一致。10 万是默认值的
     * 六分之一，给将来下调留了空间，同时排除掉「形同虚设」的强度。
     */
    private const val MIN_ITERATIONS = 100_000

    private const val BUFFER_BYTES = 64 * 1024

    /** 头部读取允许的连续「空读」次数：`InputStream.read` 允许返回 0（见 [readFully]） */
    private const val MAX_EMPTY_READS = 64

    /** 密码校验结果（[Invalid] 的文案直接给用户看） */
    sealed interface PasswordCheck {
        object Ok : PasswordCheck
        class Invalid(val reason: String) : PasswordCheck
    }

    /**
     * 密码规则校验（纯函数，可单测）。
     *
     * 只认汉字：字母 / 数字 / 标点 / 空格 / emoji 一律拒绝 —— 用户明确要求「只能用中文」，
     * 而且汉字做密码比英文键盘组合更容易记住、熵更高。
     */
    fun validatePassword(password: String): PasswordCheck {
        if (password.isEmpty()) return PasswordCheck.Invalid("请输入备份密码")
        var codePoints = 0
        var i = 0
        while (i < password.length) {
            val cp = password.codePointAt(i)
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) {
                return PasswordCheck.Invalid("备份密码只能用中文汉字（不含字母、数字、标点或空格）")
            }
            codePoints++
            i += Character.charCount(cp)
        }
        if (codePoints < MIN_PASSWORD_CHARS) {
            return PasswordCheck.Invalid("备份密码至少 $MIN_PASSWORD_CHARS 个汉字")
        }
        if (codePoints > MAX_PASSWORD_CHARS) {
            return PasswordCheck.Invalid("备份密码最多 $MAX_PASSWORD_CHARS 个汉字")
        }
        // 同一个字重复（啊啊啊啊啊啊）没有熵，直接拒绝
        if (password.codePoints().distinct().count() == 1L) {
            return PasswordCheck.Invalid("备份密码不能是同一个字重复")
        }
        return PasswordCheck.Ok
    }

    /**
     * 加密：[src]（zip 备份）→ [dest]（加密外壳文件），流式读写，先写临时文件再原子改名。
     *
     * @param iterations 仅供测试注入低值；生产调用用默认 [ITERATIONS]
     * @return 是否成功（失败时不会留下半截目标文件）
     */
    fun encrypt(
        src: File,
        dest: File,
        password: CharArray,
        iterations: Int = ITERATIONS,
    ): Boolean = runCatching {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        }
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            tmp.outputStream().buffered(BUFFER_BYTES).use { raw ->
                raw.write(MAGIC.toByteArray(Charsets.US_ASCII))
                raw.write(VERSION)
                raw.write(ByteBuffer.allocate(4).putInt(iterations).array())
                raw.write(salt)
                raw.write(iv)
                src.inputStream().buffered(BUFFER_BYTES).use { input ->
                    val buf = ByteArray(BUFFER_BYTES)
                    val out = ByteArray(BUFFER_BYTES + 32)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val m = cipher.update(buf, 0, n, out)
                        if (m > 0) raw.write(out, 0, m)
                    }
                    val tail = cipher.doFinal()
                    if (tail.isNotEmpty()) raw.write(tail)
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            Diagnostics.w("ConfigCrypto", "加密失败: ${t.javaClass.simpleName}")
            return false
        }
        // 直接改名覆盖（rename 替换目录项）：先删目标会制造一个「目标不存在」的窗口，
        // 改名一旦失败，旧包与新包同时失去。与 `UserFrequency.writeAtomically`、索引缓存
        // 同一条口径（那两处都把「先删目标」写成反例）。
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            return false
        }
        true
    }.getOrElse {
        Diagnostics.w("ConfigCrypto", "加密异常: ${it.javaClass.simpleName}")
        false
    }

    /**
     * 解密：[src]（加密外壳）→ [dest]（还原出的 zip），失败返回 false。
     *
     * 失败一律当作「密码错误或文件已损坏」：不区分二者是**有意的**——
     * 区分开等于给攻击者一个验证密码是否正确的免费判据。
     *
     * 注意没有用 `CipherInputStream`：它在认证失败时可能把 AEADBadTagException 吞掉、
     * 让人以为解出了一份「短一截但看着正常」的备份。这里手动 update/doFinal，
     * 让 GCM 的校验失败必定抛出并中断（内容不落盘，直接删临时文件）。
     */
    fun decrypt(src: File, dest: File, password: CharArray): Boolean = runCatching {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            src.inputStream().buffered(BUFFER_BYTES).use { raw ->
                val magic = ByteArray(MAGIC.length)
                if (!readFully(raw, magic) || String(magic, Charsets.US_ASCII) != MAGIC) return false
                if (raw.read() != VERSION) return false
                val iterBytes = ByteArray(4)
                if (!readFully(raw, iterBytes)) return false
                val iterations = ByteBuffer.wrap(iterBytes).int
                if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return false
                val salt = ByteArray(SALT_BYTES)
                if (!readFully(raw, salt)) return false
                val iv = ByteArray(IV_BYTES)
                if (!readFully(raw, iv)) return false

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        deriveKey(password, salt, iterations),
                        GCMParameterSpec(TAG_BITS, iv),
                    )
                }
                tmp.outputStream().buffered(BUFFER_BYTES).use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    val plain = ByteArray(BUFFER_BYTES + 32)
                    while (true) {
                        val n = raw.read(buf)
                        if (n < 0) break
                        val m = cipher.update(buf, 0, n, plain)
                        if (m > 0) out.write(plain, 0, m)
                    }
                    // 认证标签在这里校验：密码不对 / 内容被改 / 文件截断都会抛异常
                    val tail = cipher.doFinal()
                    if (tail.isNotEmpty()) out.write(tail)
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            Diagnostics.w("ConfigCrypto", "解密失败（密码错误或文件损坏）: ${t.javaClass.simpleName}")
            return false
        }
        // 同上：直接改名覆盖，不先删目标
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            return false
        }
        true
    }.getOrElse {
        Diagnostics.w("ConfigCrypto", "解密异常: ${it.javaClass.simpleName}")
        false
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray): Boolean {
        var offset = 0
        var emptyReads = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) return false
            // 允许 read 返回 0（非阻塞流、恶意 provider）：不处理就是空转死循环
            if (n == 0) {
                if (++emptyReads > MAX_EMPTY_READS) return false
                continue
            }
            emptyReads = 0
            offset += n
        }
        return true
    }
}
