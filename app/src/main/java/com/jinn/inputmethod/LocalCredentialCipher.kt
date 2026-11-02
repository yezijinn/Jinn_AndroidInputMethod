package com.jinn.inputmethod

import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 凭据的**软件密钥**兜底：应用私有目录里一份随机 256 位密钥 + AES-256-GCM。
 *
 * 为什么必须有这条路（本机实测）：无锁屏的设备上 `keystore2` 报 `User ECDH key missing`，
 * [CredentialCrypto] 的建键 / 加密恒失败。旧口径是「加密失败绝不回退明文」⇒ 凭据只留在内存、
 * 保存后重启即丢，用户每次开机都要重填 APPID / APIKey / APISecret。
 * 「没给手机设密码」不是用户错误，不能因此让功能不可用（用户第 5 条要求）。
 *
 * 密文带独立前缀 [PREFIX]（`v2:`），与 Keystore 的 `v1:` 分开：
 * 解密侧据此分流，Keystore 恢复可用后仍能读回软件密钥写下的旧值。
 *
 * **防的是什么**（威胁模型，如实说明）：
 *  · 拷走 prefs 文件直接读 —— 没有密钥件就是一段 base64，读不出凭据；
 *  · `adb backup` / 第三方克隆工具只拿到 prefs 而没拿到 `files/` 下的密钥件时同样解不开。
 *
 * **不防什么**（给出虚假的安全感比不加密更糟）：
 *  · root / 取证镜像 —— 密钥件与密文同在应用沙箱里，拿到整个 `/data/data` 即可解密；
 *  · 恶意 ROM / 系统级调试 —— 它们能以本应用身份读文件；
 *  · 已在内存中的明文 —— Java `String` 不可擦除。
 *
 * 与 Keystore 路径的分工：Keystore 可用时**永远**走 [CredentialCrypto]（密钥材料不出安全硬件），
 * 本类只在它失败时兜底（见 `Prefs.writeCredential`）。性能上软件 GCM 比 Keystore 快一个量级，
 * 但安全等级低一档，所以不抢主路。
 *
 * 以 [File] 为参数（而不是 `Context` / `filesDir`）：纯 JVM 单测能真跑往返与「重启语义」。
 */
object LocalCredentialCipher {

    private const val TAG = "LocalCredentialCipher"

    /** 密钥件文件名（放在 `filesDir` 下，随应用沙箱；卸载即随之消失） */
    const val FILE_NAME = "credential_soft.key"

    /** 密文版本前缀：软件密钥写下的值以它开头，解密侧据此分流 */
    const val PREFIX = "v2:"

    /** AES-256 的密钥长度（字节） */
    internal const val KEY_BYTES = 32

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 是否为软件密钥写下的密文（只看前缀，不解密） */
    fun isLocal(stored: String): Boolean = stored.startsWith(PREFIX)

    /**
     * 密钥件的当前状态：供**读侧**区分「永久解不开」与「这次读不出来」。
     *
     * 为什么需要它：`decrypt` 只回 `String?`，密钥件缺失 / 损坏 / 这次读失败长得一样，
     * 调用方只能按同一种处置 —— 而正确处置相反：前两者重试一万次也是 null（可缓存「未配置」），
     * 读失败却只是 IO 抖动（缓存就等于「一次抖动 = 本进程内凭据全消失」，
     * 与 `CredentialCrypto.DecryptOutcome.Transient` 是同一件事）。
     *
     * 本方法**只读**：不新建、不删除（删除只在写侧 [loadOrCreate] 的「长度不对」分支里发生）。
     */
    fun keyState(keyFile: File): KeyState {
        if (!keyFile.isFile) return KeyState.MISSING
        val bytes = runCatching { keyFile.readBytes() }.getOrNull() ?: return KeyState.UNREADABLE
        return if (bytes.size == KEY_BYTES) KeyState.OK else KeyState.CORRUPT
    }

    /** 密钥件的四种状态（见 [keyState]） */
    enum class KeyState {
        /** 32 字节、可读 —— 唯一的可用态 */
        OK,

        /** 文件不在（卸载 / 首次使用前 / 被清理） */
        MISSING,

        /** 文件在但长度不对（半截 / 被改）—— 永久解不开，写侧会删掉重建 */
        CORRUPT,

        /** 文件在、这次读不出来（IO 抖动）—— **暂时**，调用方不许缓存结论 */
        UNREADABLE,
    }

    /**
     * 加密 [plain]，密钥取 [keyFile]（不存在则首次生成 32 字节随机）。
     *
     * @return `v2:base64(iv):base64(密文+tag)`；密钥件读不出 / 写不下 / 入参为空时返回 null
     *         （调用方**不得**回退成明文落盘）。
     */
    fun encrypt(keyFile: File, plain: String): String? {
        if (plain.isEmpty()) return null
        return runCatching {
            val key = loadOrCreate(keyFile) ?: return@runCatching null
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + b64(iv) + ":" + b64(body)
        }.getOrElse {
            Diagnostics.e(TAG, "软件密钥加密失败: ${it.javaClass.simpleName}")
            null
        }
    }

    /**
     * 解密 [stored]（必须带 [PREFIX]）。
     *
     * @return 明文；**密钥件缺失 / 密文被改 / 形态不对一律 null** —— 这些重试一万次也是同一结论，
     *         调用方按 `DecryptOutcome.Permanent` 处理（等价「未配置」，需重填），不是崩溃。
     *         ⚠ 唯一的例外是「密钥件在、这次读失败」（IO 抖动）：同样回 null，
     *         但调用方必须先用 [keyState] 判成 [KeyState.UNREADABLE] 再按**暂时**处理（不缓存）。
     *
     * 解密**不新建**密钥件：缺文件时新建一把只会让磁盘上的旧密文永远读不回来，
     * 而新密文又用另一把钥匙写进同一批键 —— 一份配置两种密钥，比明确失败更难查。
     */
    fun decrypt(keyFile: File, stored: String): String? {
        if (!isLocal(stored)) return null
        return runCatching {
            val body = stored.removePrefix(PREFIX)
            val sep = body.indexOf(':')
            if (sep <= 0) return@runCatching null
            val iv = unb64(body.substring(0, sep))
            val data = unb64(body.substring(sep + 1))
            // GCM 的 tag 固定 16 字节：比它短说明密文本身坏了，不必再试
            if (iv.size != IV_BYTES || data.size <= TAG_BITS / 8) return@runCatching null
            val key = load(keyFile) ?: return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrElse {
            // 只记异常类名：异常正文不含凭据，但也没必要冒这个风险
            Diagnostics.w(TAG, "软件密钥解密失败: ${it.javaClass.simpleName}")
            null
        }
    }

    /** 读密钥件；不存在 / 长度不对（= 文件损坏）返回 null，**不新建** */
    @Synchronized
    private fun load(keyFile: File): SecretKey? {
        if (!keyFile.isFile) return null
        val bytes = runCatching { keyFile.readBytes() }.getOrElse {
            Diagnostics.w(TAG, "密钥件读取失败: ${it.javaClass.simpleName}")
            return null
        }
        if (bytes.size != KEY_BYTES) {
            // 半截 / 被改过：重建会把已有密文变成永久读不回来，故只报不改
            Diagnostics.w(TAG, "密钥件长度异常 size=${bytes.size}（应为 $KEY_BYTES）")
            return null
        }
        return SecretKeySpec(bytes, "AES")
    }

    /**
     * 读密钥件；不存在则首次生成并原子落盘。
     *
     * 密钥件**存在但长度不对**（外部损坏 / 半截）时删掉重建，而不是一直返回 null：
     * 那种文件解不出任何旧密文（解密只认 32 字节），留着它唯一的后果是**之后每一次加密都失败** ——
     * 用户重填凭据也永远落不了盘，只能清应用数据（写侧会被判成「两支都不可用」）。
     * 删它是安全方向：磁盘上的旧密文本来就已经永久解不开，删除后重填即可恢复。
     *
     * 读文件本身失败（`IOException`）则**不动**它，只报这一轮失败 —— 那可能是暂时性错误，
     * 删掉一把完好的密钥是不可逆的损失。
     */
    @Synchronized
    private fun loadOrCreate(keyFile: File): SecretKey? {
        load(keyFile)?.let { return it }
        if (keyFile.isFile) {
            val existing = runCatching { keyFile.readBytes() }.getOrNull()
                ?: return null   // 读不出来：可能是暂时性错误，不动它
            if (existing.size == KEY_BYTES) return null  // 32 字节却读不成密钥：同样不动
            Diagnostics.w(
                TAG,
                "密钥件内容已损坏 size=${existing.size}（应为 $KEY_BYTES），删除后重建；旧密文按未配置处理",
            )
            if (!keyFile.delete()) {
                Diagnostics.e(TAG, "损坏的密钥件删不掉，本次不写凭据")
                return null
            }
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        if (!writeAtomically(keyFile, fresh)) {
            Diagnostics.e(TAG, "软件密钥落盘失败，本次不写凭据")
            return null
        }
        Diagnostics.i(TAG, "软件密钥已生成（本机无锁屏，凭据改用软件密钥加密）")
        return SecretKeySpec(fresh, "AES")
    }

    /**
     * 先写 `.tmp`、`fsync` 再改名（与 `LedgerCrypto.writeAtomically` 同构）：
     * 掉电 / 进程被杀时不会留下半截密钥件 —— 半截等于已写入的凭据永久解不开。
     */
    private fun writeAtomically(target: File, bytes: ByteArray): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (tmp.renameTo(target)) {
            true
        } else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)

    private fun b64(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
