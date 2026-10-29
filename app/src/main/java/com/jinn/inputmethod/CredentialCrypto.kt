package com.jinn.inputmethod

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 翻译凭据的加密落盘：Android Keystore + AES-256-GCM（与 [ClipboardCrypto] 同构，**独立密钥别名**）。
 *
 * 为什么独立别名：凭据与剪贴板的密钥轮换 / 失效必须互不影响 —— 清一次剪贴板密钥不能连带把翻译凭据弄丢。
 *
 * **防的是什么**（威胁模型，2026-09-30 加固）：
 *  · 明文 `SharedPreferences` 在「设备文件被取走 / 取证工具 / 工程模式 / 未 root 的文件浏览」下
 *    就是一段可直接读的文本；换成 Keystore 密文后，解密所需的密钥材料**不出安全硬件**（TEE/StrongBox），
 *    拿到文件也解不开。
 *
 * **不防什么**（如实说明，避免给出虚假的安全感）：
 *  · root / 恶意 ROM / 系统级调试 —— 它们能以本应用身份调用 Keystore，应用层无解；
 *  · 已在内存中的明文 —— Java `String` 不可擦除，只能做到「不额外复制、不留 UI 层副本」。
 *
 * 存储格式：`base64(iv):base64(ciphertext)`（IV 12 字节 ⇒ base64 恒为 16 字符，见 [looksEncrypted]）；
 * GCM 的 auth tag 含在密文尾部（128-bit），解密即校验完整性。
 */
object CredentialCrypto {

    private const val TAG = "CredentialCrypto"
    private const val KEY_ALIAS = "jinn_credentials_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** GCM 标准 IV 12 字节 → base64(NO_WRAP) 恒为 16 字符，这是 [looksEncrypted] 的判据 */
    private const val IV_B64_LENGTH = 16

    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * 缓存 Keystore 里的密钥句柄（与 [ClipboardCrypto] 同一取舍）：`getKey` 每次要走 binder/TEE
     * （5~20ms），而凭据读取分布在「组装 Provider」「设置页摘要」等多处；句柄只是对托管密钥的
     * 不透明引用，缓存它不削弱安全性。
     *
     * ⚠ **不能**用 `by lazy`（与 `ClipboardCrypto` 的 BUG.md L-1095 同型）：句柄是可失效资源，
     * 用户在系统设置里清除凭据 / Keystore 服务异常后 `Cipher.init` 恒抛，而 `by lazy` 算一次
     * 即固定 ⇒ 本进程内凭据永久写不进，用户重填也存不住（且 `Prefs` 会先删掉盘上旧密文）。
     * 改为可清引用 + 按需重建，语义等价于「构造失败不缓存」。
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    /** 取密钥句柄（首次或失效后重建） */
    private fun key(): SecretKey = cachedKey ?: synchronized(this) {
        cachedKey ?: createOrLoadKey().also { cachedKey = it }
    }

    /** 丢弃缓存的句柄：下次 [key] 重新 getKey（不存在才生成） */
    private fun invalidateKey() {
        synchronized(this) { cachedKey = null }
    }

    /**
     * 是否为「密钥本身失效」类异常（值得重建一次再试）；口径与 [ClipboardCrypto] 一致。
     */
    private fun isKeyFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.security.UnrecoverableKeyException) return true
            if (t is java.security.KeyStoreException) return true
            if (t is android.security.keystore.KeyPermanentlyInvalidatedException) return true
            // 同 [ClipboardCrypto.isKeyFailure]：API 33 才公开的类，低版本必须短路与，否则 `is` 现场
            // 解析失败抛 NoClassDefFoundError（lint NewApi）
            if (android.os.Build.VERSION.SDK_INT >= 33 && t is android.security.KeyStoreException) return true
            if (t is java.security.InvalidKeyException) return true
            t = t.cause
        }
        return false
    }

    /** 带一次失效重建的加解密：密钥类异常时清句柄、重建后重试一次 */
    private fun <T> withKey(block: (SecretKey) -> T): T = try {
        block(key())
    } catch (e: Throwable) {
        if (!isKeyFailure(e)) throw e
        Diagnostics.w(TAG, "凭据密钥疑似失效，重建后重试: ${e.javaClass.simpleName}")
        invalidateKey()
        block(key())
    }

    private fun createOrLoadKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        // 锁屏时不可解密（API 28+）：防「设备锁屏状态下的取证读取」。
        // 翻译在锁屏时本来就不可用（键盘不显示），且进程内的缓存已覆盖解锁后的正常使用 ⇒ 无体验损失。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    /** 加密凭据；失败返回 null（调用方**不得**回退成明文落盘，见 `Prefs` 的写入路径） */
    fun encrypt(plaintext: String): String? = runCatching {
        if (plaintext.isEmpty()) return null
        withKey { k ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            VERSION_PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }
    }.getOrElse {
        Diagnostics.e(TAG, "凭据加密失败: ${it.javaClass.simpleName}")
        null
    }

    /**
     * 解密结论：**必须**区分「暂时解不开」与「永远解不开」。
     *
     * 只回 `String?` 时两者长得一样，调用方只能按同一种处置 —— 而它们的正确处置相反：
     * 永久失败等价「未配置」且可以缓存；暂时失败（锁屏时 Keystore 让密钥暂不可用）
     * 一定要允许下次重试，否则「一次锁屏 = 本进程内凭据永久未配置」，
     * 用户解锁后打开凭据页看到 9 个 Key 全空、以为凭据丢了（AI2 A-672 / 台账 L-1462）。
     */
    sealed interface DecryptOutcome {
        class Ok(val plain: String) : DecryptOutcome

        /** 暂时不可用（锁屏 / Keystore 抖动）：调用方**不得**把结论缓存成「未配置」 */
        object Transient : DecryptOutcome

        /** 永久不可用（换机 / 密钥失效 / 密文损坏）：等价「未配置」，可缓存 */
        object Permanent : DecryptOutcome
    }

    /** 解密 [encrypt] 的产物，并给出失败的性质（见 [DecryptOutcome]） */
    fun decryptDetailed(stored: String): DecryptOutcome = runCatching {
        // 版本前缀可省：兼容本次改动之前落盘的密文（无前缀）
        val body = stored.removePrefix(VERSION_PREFIX)
        val sep = body.indexOf(':')
        // 形态不对 = 密文本身坏了，重试一万次也是同一个结论（与「锁屏」不是一回事）
        if (sep != IV_B64_LENGTH) return@runCatching DecryptOutcome.Permanent
        val iv = Base64.decode(body.substring(0, sep), Base64.NO_WRAP)
        val data = Base64.decode(body.substring(sep + 1), Base64.NO_WRAP)
        if (iv.size < 12) return@runCatching DecryptOutcome.Permanent
        DecryptOutcome.Ok(
            withKey { k ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(data), Charsets.UTF_8)
            },
        )
    }.getOrElse { e ->
        Diagnostics.e(TAG, "凭据解密失败: ${e.javaClass.simpleName}")
        failureOutcome(e)
    }

    /**
     * 异常链 → 失败态（[DecryptOutcome.Transient] / [DecryptOutcome.Permanent]）。
     *
     * 链上**任一**环是永久成因就判永久（外层多为 `ProviderException` / `KeyStoreException`
     * 之类包装，真正的成因在 cause 里）。整条链都不是永久成因 ⇒ 暂时：宁可按「下次再试」处理，
     * 也不能把「锁屏解不开」写成「未配置」。
     */
    internal fun failureOutcome(e: Throwable): DecryptOutcome {
        var t: Throwable? = e
        while (t != null) {
            if (isPermanentFailure(t.javaClass.name, t.message)) return DecryptOutcome.Permanent
            t = t.cause
        }
        return DecryptOutcome.Transient
    }

    /**
     * 失败成因是否为**永久**（纯判据：只吃类名与 message，JVM 单测可直接跑）。
     *
     * 永久 = 密钥被系统失效（换机 / 重置 / 改锁屏）/ GCM 校验不过 / 分块或填充坏掉：
     * 这几类重试一万次也是同一个结果。
     *
     * 其余一律按**暂时**处理 —— 这是刻意的方向：锁屏时 `setUnlockedDeviceRequired` 让密钥
     * 不可用，各家 ROM 抛出的异常形态并不统一（`UserNotAuthenticatedException`、
     * `KeyStoreException: Key user not authenticated`、甚至包了一层 `ProviderException`）。
     * 判错成永久的代价是「用户以为凭据丢了」，判错成暂时的代价只是「下次再试一次」。
     */
    internal fun isPermanentFailure(typeName: String, message: String?): Boolean {
        if (typeName.substringAfterLast('.') in PERMANENT_FAILURES) return true
        val m = message.orEmpty().lowercase(java.util.Locale.ROOT)
        return "permanently invalidated" in m || "invalidated" in m || "tag mismatch" in m
    }

    /** 密钥材料被作废 / 密文自身坏了（重试无意义）的异常类简名 */
    private val PERMANENT_FAILURES = setOf(
        "KeyPermanentlyInvalidatedException",
        "AEADBadTagException",
        "BadPaddingException",
        "IllegalBlockSizeException",
        "UnrecoverableKeyException",
    )

    /**
     * 是否为「本模块的密文」——用于区分**旧版明文**（要迁移）与**解不开的密文**（换机，视作未配置）。
     *
     * 判据取「IV 段恰 16 个 base64 字符 + 冒号 + base64 密文段」：这是格式的硬特征。
     * 真实凭据里只有 DeepL 带冒号（`uuid:fx`，首段 36 字符）⇒ 不会被误判；
     * 极端情况下用户手输 `16个base64字符:...` 形态的凭据会被当成密文（解密失败 ⇒ 视作未配置），
     * 这是刻意选的方向：**宁可让用户重填，也不能把密文原文当 Key 发出去**。
     */
    fun looksEncrypted(raw: String): Boolean {
        // 带版本前缀的一律是自家密文（新写入都带，见 encrypt）
        if (raw.startsWith(VERSION_PREFIX)) return true
        // 无前缀的存量密文：`base64段:base64段`，首段 16 或 24 字符、密文段 ≥ 24 字符
        // （GCM tag 16 字节的 base64 长度）。判据取**宽**不取窄（fail-closed）——
        // 判成密文最多让用户重填一次；判成明文则会把密文当 Key 发出去、并覆盖写回原始密文
        // （2026-10-01 审查 L-219）。
        val sep = raw.indexOf(':')
        if (sep != IV_B64_LENGTH && sep != IV_B64_LENGTH_ALT) return false
        if (raw.length <= sep + 24) return false
        for (i in 0 until sep) if (raw[i] !in BASE64_CHARS) return false
        return raw.substring(sep + 1).all { it in BASE64_CHARS }
    }

    /** 密文版本前缀：新写入带它；读取侧按「可省」处理以兼容存量密文 */
    private const val VERSION_PREFIX = "v1:"

    /** 另一种可能的 IV 段长度（base64 无填充 / 其它编码口径），按密文处理以避免 fail-open */
    private const val IV_B64_LENGTH_ALT = 24

    private const val BASE64_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
}
