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
 * **防的是什么**（威胁模型，用户 2026-09-30 要求增强保护）：
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
     */
    private val cachedKey: SecretKey by lazy { createOrLoadKey() }

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
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, cachedKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }.getOrElse {
        Diagnostics.e(TAG, "凭据加密失败: ${it.javaClass.simpleName}")
        null
    }

    /** 解密 [encrypt] 的产物；失败（密文被篡改 / 换机 / Keystore 失效）返回 null */
    fun decrypt(stored: String): String? = runCatching {
        val sep = stored.indexOf(':')
        if (sep != IV_B64_LENGTH) return null
        val iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)
        val data = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)
        if (iv.size < 12) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, cachedKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrElse {
        Diagnostics.e(TAG, "凭据解密失败: ${it.javaClass.simpleName}")
        null
    }

    /**
     * 是否为「本模块的密文」——用于区分**旧版明文**（要迁移）与**解不开的密文**（换机，视作未配置）。
     *
     * 判据取「IV 段恰 16 个 base64 字符 + 冒号 + base64 密文段」：这是格式的硬特征。
     * 真实凭据里只有 DeepL 带冒号（`uuid:fx`，首段 36 字符）⇒ 不会被误判；
     * 极端情况下用户手输 `16个base64字符:...` 形态的凭据会被当成密文（解密失败 ⇒ 视作未配置），
     * 这是刻意选的方向：**宁可让用户重填，也不能把密文原文当 Key 发出去**。
     */
    fun looksEncrypted(raw: String): Boolean {
        if (raw.length <= IV_B64_LENGTH + 1) return false
        if (raw[IV_B64_LENGTH] != ':') return false
        for (i in 0 until IV_B64_LENGTH) if (raw[i] !in BASE64_CHARS) return false
        val body = raw.substring(IV_B64_LENGTH + 1)
        // 密文段：GCM tag 16 字节 ⇒ base64 ≥ 24 字符；全 base64 字符
        return body.length >= 24 && body.all { it in BASE64_CHARS }
    }

    private const val BASE64_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
}
