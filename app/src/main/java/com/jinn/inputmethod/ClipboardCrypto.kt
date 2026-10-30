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
 * [Cipher] 句柄做加解密；禁止硬编码密钥或自研算法。
 *
 * 存储格式：`base64(iv) : base64(ciphertext)`，IV 与密文一起入库；
 * GCM 的 auth tag 已含在密文尾部（标准 128-bit），解密即校验完整性。
 *
 * 线程模型：Keystore 操作无主线程限制，但调用方按惯例在后台线程执行。
 */
object ClipboardCrypto {

    private const val TAG = "ClipboardCrypto"

    private const val KEY_ALIAS = "jinn_clipboard_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** GCM 标准 IV 长度（字节） */
    private const val GCM_IV_BYTES = 12

    /** 字节壳（[encryptBytes]/[decryptBytes]）的格式版本，占首字节 */
    private const val BYTES_FORMAT_VERSION: Byte = 1

    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * 缓存 Keystore 里的密钥句柄（**可失效**，见 [withKey]）。
     *
     * `getKey` 只走一次 binder，**实测 0.21~0.33 ms**（慢机型更高，换机须重测），
     * 而且它**不是**单次加解密的大头：单次 1.1~1.3 ms 里 `init` 占约六成（0.63~0.76 ms，
     * 在 TEE 里建操作会话）、`doFinal` 0.51~0.62 ms，`getInstance` / base64 各只 0.004~0.006 ms；
     * `getKey` 命中缓存后为 0。缓存它值得（省一次 binder），但**别把它当加速入口**：
     * 第 68 轮正是照着旧版注释里的「5-20ms 是主要耗时」去找杠杆，扑空。
     *
     * SecretKey 只是对托管密钥的不透明句柄（私钥不出安全硬件），缓存它不会削弱安全性。
     *
     * ⚠ **一次 `init` 只准一次 `doFinal`**：第二次 `doFinal` 抛
     * `IllegalStateException: IV has already been used`，且此时 `getIV()` 返回的仍是
     * 上一次的 IV（不会重生成）⇒ 复用就是**确定性的 nonce reuse**；平台的拒绝来自
     * keymaster 的 IV 唯一性检查（栈顶 `addAlgorithmSpecificParametersToBegin`），
     * 某个 OEM 降级这条检查就会**静默**重放 nonce ⇒ 不能把这条兜底当设计前提。
     * `doFinal` 之后**重新 `init`** 是合法的（实测连续 `init` 6/6 拿到不同 IV），
     * 但那样只省一次 `Cipher.getInstance`（0.005 ms，占单次 0.4%），不值得动状态机。
     * 结论：每条密文 = 一次 `init` + 一次 `doFinal`，想靠复用省掉 `init` 那六成走不通。
     *
     * ⚠ **不能**用 `by lazy`（BUG.md L-1095）：句柄是可失效资源，Keystore 服务异常或
     * 用户在系统设置里清除凭据后，`Cipher.init` 会恒抛，而 `by lazy` 算一次即固定
     * ⇒ 本进程内采集/解密永久失效（写入一律 null、面板变空），只能靠重启恢复。
     * 改为可清引用 + 按需重建，语义等价于「构造失败不缓存」。
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    /** 取密钥句柄（首次或失效后重建）；重建失败不缓存，下次调用再试 */
    private fun key(): SecretKey = cachedKey ?: synchronized(this) {
        cachedKey ?: createOrLoadKey().also { cachedKey = it }
    }

    /** 丢弃缓存的句柄：下次 [key] 会重新 getKey（不存在才生成） */
    private fun invalidateKey() {
        synchronized(this) { cachedKey = null }
    }

    /**
     * 是否为「密钥本身失效」类异常（值得重建一次再试）。
     *
     * 只认这几类：句柄被系统作废 / 密钥不存在 / KeyStore 服务故障。**不含**
     * `AEADBadTagException`（密文损坏或换机）—— 那类重试必然再失败，而批量解密
     * 200 条时每次多跑一次 Keystore（本机实测单次约 1.1 ms）纯属白烧。
     */
    private fun isKeyFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.security.UnrecoverableKeyException) return true
            if (t is java.security.KeyStoreException) return true
            if (t is android.security.keystore.KeyPermanentlyInvalidatedException) return true
            // ⚠ `android.security.KeyStoreException` 是 API 33 才公开的类：minSdk 26 上直接 `is` 会让
            // ART 现场解析不到的类抛 NoClassDefFoundError（就在 catch 分支里，等于把「密钥失效」
            // 变成崩溃）。短路与 —— 低版本连这条字节码都不执行（lint NewApi 也认这个写法）
            if (android.os.Build.VERSION.SDK_INT >= 33 && t is android.security.KeyStoreException) return true
            // 部分 OEM 在句柄被作废后只抛裸 InvalidKeyException（不带 Keystore cause）
            if (t is java.security.InvalidKeyException) return true
            t = t.cause
        }
        return false
    }

    /**
     * 带一次失效重建的加解密：密钥类异常时清句柄、重建后重试一次。
     *
     * 第二次仍失败则原样抛出，由各入口的 `runCatching` 记日志并返回 null（行为与修复前一致）。
     */
    private fun <T> withKey(block: (SecretKey) -> T): T = try {
        block(key())
    } catch (e: Throwable) {
        if (!isKeyFailure(e)) throw e
        Diagnostics.w(TAG, "Keystore 密钥疑似失效，重建后重试: ${e.javaClass.simpleName}")
        invalidateKey()
        block(key())
    }

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
        withKey { k ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "$ivB64:$dataB64"
        }
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
        withKey { k ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }
    }.getOrElse {
        Diagnostics.e(TAG, "解密失败: ${it.message}")
        null
    }

    /**
     * 字节版加密（图片原字节 / 缩略图，落盘用）：**二进制壳** `[1B ver][12B iv][ct]`。
     *
     * 为什么不复用字符串版（`base64(iv):base64(ct)`）：base64 让文件体积 +33%，而图片是本模块
     * 体积最大的数据。两者**同一密钥、同一 AES-GCM 参数**，只是外层编码不同（互不兼容，
     * 由首字节版本号兜底：读到不认识的首字节即判失败，不会把字符串密文当字节壳解）。
     *
     * @return 密文；空输入或加密失败返回 null（调用方记日志但不外抛）
     */
    fun encryptBytes(plain: ByteArray): ByteArray? = runCatching {
        if (plain.isEmpty()) return null
        withKey { k ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            // 写侧按实际长度拼壳，读侧却按 GCM_IV_BYTES 固定切分 ⇒ 长度不一致就是「写得进、读不出」
            // （解密走 AEADBadTagException ⇒ 返回 null ⇒ 图片静默丢失）。Keystore 的 GCM 恒给 12 字节，
            // 这里当场钉住：换 provider 时宁可加密失败并留日志，也不要写出解不开的壳
            require(iv.size == GCM_IV_BYTES) { "GCM IV 必须是 $GCM_IV_BYTES 字节（当前 ${iv.size}）" }
            val ct = cipher.doFinal(plain)
            val out = ByteArray(1 + iv.size + ct.size)
            out[0] = BYTES_FORMAT_VERSION
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
            out
        }
    }.getOrElse {
        Diagnostics.e(TAG, "字节加密失败: ${it.message}")
        null
    }

    /**
     * 解密 [encryptBytes] 的产物；失败返回 null。
     *
     * 用 `doFinal(input, offset, len)` 而不是先 `copyOfRange` 出明文副本：单张图片最大 20MB，
     * 少一次整份复制就少一份峰值内存（采集侧本就跑在内存敏感的解码链路上）。
     */
    fun decryptBytes(stored: ByteArray): ByteArray? = runCatching {
        if (stored.size <= 1 + GCM_IV_BYTES) return null
        if (stored[0] != BYTES_FORMAT_VERSION) return null
        val iv = stored.copyOfRange(1, 1 + GCM_IV_BYTES)
        withKey { k ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(stored, 1 + GCM_IV_BYTES, stored.size - 1 - GCM_IV_BYTES)
        }
    }.getOrElse {
        Diagnostics.e(TAG, "字节解密失败: ${it.message}")
        null
    }
}
