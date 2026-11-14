package com.jinn.inputmethod

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * KeyStore 实例（**可失效**，与 [cachedKey] 同锁同生命周期）。
     *
     * ⚠ 不能 `by lazy`：Keystore 服务端故障 / 重启后，旧实例取不到密钥。实例只在
     * `synchronized(this)` 内写（`@Volatile` 只服务无锁快路径），清字段不影响已取出的局部句柄。
     */
    @Volatile
    private var keyStore: KeyStore? = null

    /** 解密失败计数（**仅归因**，不参与任何控制流；每 50 次落一条 E，避免坏库刷屏） */
    private val decryptFails = AtomicInteger(0)

    /** 本进程内「密钥疑似失效后重建」的次数（仅归因） */
    private val keyRebuilds = AtomicInteger(0)

    /** 「库内仍有密文却要新建密钥」的拒绝次数（仅归因；E 日志按 [REFUSAL_LOG_LIMIT] 节流） */
    private val createRefusals = AtomicInteger(0)

    /**
     * 加密失败次数（仅归因）。拒绝建钥后**每一次复制**都会走到这里 —— 不节流会把日志刷满，
     * 而刷屏本身又会拖慢采集线程。
     */
    private val encryptFails = AtomicInteger(0)

    private const val REFUSAL_LOG_LIMIT = 3

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

    /**
     * 取密钥句柄（首次或失效后重建）。
     *
     * @param allowCreate **只有加密（写新密文）才允许 true**。解密路径必须 false：
     *   读的时候新建密钥会覆盖同别名，把库里已有密文变成**永久**不可解（本仓对台账的口径是
     *   「密钥件缺失而数据还在 ⇒ 绝不新建」，剪贴板同理）。
     *
     * 写路径还有第二道闸（[keyDecision]）：**别名不存在但库内仍有密文时也拒绝建钥** ——
     * 覆盖同别名会让全部旧密文永久失配，而新行照常可解，用户看到的是「历史全没了、新复制却正常」。
     */
    private fun keyOrNull(allowCreate: Boolean): SecretKey? {
        cachedKey?.let { return it }
        var readFailed = false
        val existing = synchronized(this) {
            cachedKey ?: probeStoredKey().also { probe ->
                readFailed = probe.readFailed
                probe.key?.let { cachedKey = it }
            }.key
        }
        if (existing != null) return existing
        // ⚠ 探针在监视器**外**：行数探针要 DB 连接，而写路径的既有锁序是「DB 监视器 → Crypto 监视器」
        // （见 ClipboardDb.upsertImportChunk 的锁序说明，非 WAL 库只有一个连接）⇒ 锁内探测会死锁。
        val known = allowCreate && !readFailed && ClipboardKeyPresence.isKnown()
        val hasRows = known && ClipboardKeyPresence.hasCiphertextRows()
        when (
            keyDecision(
                keyFound = false,
                keyReadFailed = readFailed,
                allowCreate = allowCreate,
                presenceKnown = known,
                hasCiphertextRows = hasRows,
            )
        ) {
            KeyDecision.UseExisting -> Unit
            KeyDecision.Refuse -> {
                // 只给「别名不存在而库内仍有密文」这一支留痕：这是唯一会毁库的形态（节流防刷屏）
                if (known && hasRows && createRefusals.incrementAndGet() <= REFUSAL_LOG_LIMIT) {
                    Diagnostics.e(TAG, "拒绝新建剪贴板密钥：别名不存在但库内仍有密文行（旧数据保留待处理）")
                }
                return null
            }
            KeyDecision.Create -> Unit
        }
        // 二次检查：探针判定与真正建钥之间，别的线程可能已经建好（复用而不是再覆盖一次）
        return synchronized(this) {
            cachedKey ?: createKey().also {
                cachedKey = it
                Diagnostics.w(TAG, "新建剪贴板密钥（别名不存在且库内无密文行）")
            }
        }
    }

    /** `getKey` 的结果：两者都为假 = 别名确实不存在；`readFailed` = 此刻取不到（抛 ≠ 不存在） */
    private class KeyProbe(val key: SecretKey?, val readFailed: Boolean)

    private fun probeStoredKey(): KeyProbe {
        val ks = keyStore ?: KeyStore.getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }.also { keyStore = it }
        return try {
            KeyProbe(ks.getKey(KEY_ALIAS, null) as? SecretKey, readFailed = false)
        } catch (e: Throwable) {
            if (!isKeyFailure(e)) throw e
            Diagnostics.w(TAG, "取密钥失败: ${e.javaClass.simpleName}")
            KeyProbe(null, readFailed = true)
        }
    }

    /** 生成并落一个新密钥（同别名覆盖）。**只允许在「库内无密文」时走到这里**，见 [keyDecision] */
    private fun createKey(): SecretKey {
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

    /** 丢弃密钥句柄**与** KeyStore 实例：下次 [keyOrNull] 从零重建（两者同锁，无拆半状态） */
    private fun invalidateKey() {
        synchronized(this) {
            cachedKey = null
            keyStore = null
        }
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
     * 带一次失效重建的加解密：密钥类异常时清句柄与 KeyStore 实例、重建后重试一次。
     *
     * 第二次仍失败则原样抛出，由各入口的 `runCatching` 记日志并返回 null。
     * [allowCreate] 语义见 [keyOrNull]：解密传 false（绝不建钥）。
     */
    private fun <T> withKey(allowCreate: Boolean, block: (SecretKey) -> T): T = try {
        block(keyOrNull(allowCreate) ?: throw IllegalStateException("Keystore 密钥不可用"))
    } catch (e: Throwable) {
        if (!isKeyFailure(e)) throw e
        Diagnostics.w(TAG, "Keystore 密钥疑似失效，重建后重试: ${e.javaClass.simpleName}")
        keyRebuilds.incrementAndGet()
        invalidateKey()
        block(keyOrNull(allowCreate) ?: throw e)
    }

    /** 加密明文，返回 `base64(iv):base64(ciphertext)`；失败返回 null（调用方记日志但不外抛） */
    fun encrypt(plaintext: String): String? = runCatching {
        if (plaintext.isEmpty()) return null
        withKey(allowCreate = true) { k ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "$ivB64:$dataB64"
        }
    }.getOrElse {
        val n = encryptFails.incrementAndGet()
        if (n == 1 || n % 50 == 0) Diagnostics.e(TAG, "加密失败累计 $n 次: ${it.message}")
        null
    }

    /** 解密 [encrypt] 的产物；失败返回 null（密文被篡改/密钥轮换都会导致解密失败） */
    fun decrypt(stored: String): String? = runCatching {
        val sep = stored.indexOf(':')
        if (sep <= 0) return null
        val iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)
        val data = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)
        if (iv.size < 12) return null // GCM 标准 IV 为 12 字节
        withKey(allowCreate = false) { k ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }
    }.getOrElse {
        val n = decryptFails.incrementAndGet()
        if (n == 1 || n % 50 == 0) {
            Diagnostics.e(TAG, "解密失败累计 $n 次（本进程密钥重建 ${keyRebuilds.get()} 次）: ${it.message}")
        }
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
        withKey(allowCreate = true) { k ->
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
        val n = encryptFails.incrementAndGet()
        if (n == 1 || n % 50 == 0) Diagnostics.e(TAG, "字节加密失败累计 $n 次: ${it.message}")
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
        withKey(allowCreate = false) { k ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(stored, 1 + GCM_IV_BYTES, stored.size - 1 - GCM_IV_BYTES)
        }
    }.getOrElse {
        val n = decryptFails.incrementAndGet()
        if (n == 1 || n % 50 == 0) {
            Diagnostics.e(TAG, "字节解密失败累计 $n 次（本进程密钥重建 ${keyRebuilds.get()} 次）: ${it.message}")
        }
        null
    }
}
