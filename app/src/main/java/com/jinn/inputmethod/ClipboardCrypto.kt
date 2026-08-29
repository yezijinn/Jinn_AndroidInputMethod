package com.jinn.inputmethod

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 剪贴板历史加密：Android Keystore + AES-256-GCM。
 *
 * 密钥由 Keystore 生成并托管，私钥材料不离开安全硬件/系统，应用只拿得到
 * [Cipher] 句柄做加解密；禁止硬编码密钥或自研算法（方案第三阶段要求）。
 *
 * 存储格式：`base64(iv) : base64(ciphertext)`，IV 与密文一起入库；
 * GCM 的 auth tag 已含在密文尾部（标准 128-bit），解密即校验完整性。
 *
 * 线程模型：Keystore 操作无主线程限制，但调用方按惯例在后台线程执行。
 */
object ClipboardCrypto {

    private const val KEY_ALIAS = "jinn_clipboard_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * 密钥内存缓存：Keystore getKey 每次都要走 binder/TEE 调用（5-20ms），
     * 200 条批量解密时占绝对耗时大头。SecretKey 仅是对 Keystore 托管密钥的
     * 不透明句柄（私钥材料不离开安全硬件），缓存句柄不降低安全性。
     */
    private val cachedKey: SecretKey by lazy { createOrLoadKey() }

    private fun createOrLoadKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密明文，返回 `base64(iv):base64(ciphertext)`；失败返回 null（调用方记日志但不外抛） */
    fun encrypt(plaintext: String): String? = runCatching {
        if (plaintext.isEmpty()) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, cachedKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        "$ivB64:$dataB64"
    }.getOrElse {
        Diagnostics.e(TAG, "加密失败: ${it.message}")
        null
    }

    /** 解密 [encrypt] 的产物；失败返回 null（密文被篡改/密钥轮换都会导致解密失败） */
    fun decrypt(stored: String): String? = runCatching {
        val sep = stored.indexOf(':')
        if (sep <= 0) return null
        val iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)
        val data = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)
        if (iv.size < 12) return null // GCM 标准 IV 为 12 字节
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, cachedKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrElse {
        Diagnostics.e(TAG, "解密失败: ${it.message}")
        null
    }

    private const val TAG = "ClipboardCrypto"
}
