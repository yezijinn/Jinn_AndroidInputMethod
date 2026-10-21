package com.jinn.inputmethod

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 词面落盘加密的密钥与行级 AEAD（[SelectionLedger] 与 [UserFrequency] 共用）。
 *
 * **为什么台账不能整文件加密**：台账只增不删、可能几万行，整文件壳（[ConfigCrypto] 那一套）每追加
 * 一次都要「解密全量 → 追加 → 重新加密全量」，等于把一次按键的选择放大成整表重写 —— 既慢又费电。
 * 所以改成**行级 AEAD**：一行记录自成一个可独立解密的单元，追加仍是 `O_APPEND` 的 O(1) 写。
 * 词频则相反（上限 3000 行、2 秒防抖）：整份「解密 → 改 → 加密 → 原子替换」只要 0.2ms 量级，
 * 两种形态各自最省，才没有硬统一成一种。
 *
 * **两层密钥（信封）**：
 *  - 数据密钥 DEK = 32 字节随机数，只做行级 AES-256-GCM；它在内存里的 [SecretKeySpec] 不碰 Keystore，
 *    所以每行加解密是纯软件运算（微秒级）；
 *  - DEK 用 Android Keystore 的密钥加密后落 `user_picks.key`（别名 [KEY_ALIAS]，**独立于**剪贴板与
 *    凭据的密钥 —— 任一侧轮换 / 失效都不能牵连另一侧）。Keystore 每次进程只走一次，
 *    不会出现在热路径上。
 *  - **词频与台账共用这一把 DEK / 这一个包裹件**：两者记的是同一批「用户选过的词」，是同一个隐私
 *    边界；共用一个包裹件就只丢得起一次、只需要一次解包（多一把密钥只是多一次 binder 往返与
 *    多一个会丢的东西）。生命周期差异靠**文件**处理，不靠密钥：台账只增不删，词频可被引擎重算、
 *    可被「关闭学习」清空，删文件即可，彼此不影响。
 *
 * **威胁模型**（与 [ClipboardCrypto] 同口径，如实写）：
 *  - 防：应用数据目录被整体取走 / 离线取证镜像 / 未 root 的文件浏览 —— 拿到 `user_picks.txt`、
 *    `user_freq.txt` 与 `user_picks.key` 也解不开（密钥材料不出安全硬件）；
 *  - 不防：**已 root 的运行中设备** / 恶意 ROM / 系统级调试 —— 它们能以本应用身份调用 Keystore。
 *
 * **失败一律 fail-closed**：密钥不可用（Keystore 被清、锁屏、硬件异常）时**不写明文**、也不返回
 * 空数据冒充「没有数据」，由调用方记 W 后跳过本次写入。密钥件被人为删掉而数据还在的路径见
 * [orphanExistingData]（绝不用新密钥去覆盖旧数据的读法）。
 *
 * **格式**（纯 ASCII，逐行）：
 *  - 首行固定 [HEADER]，用于把「加密台账」与旧版明文台账区分开（迁移判据）；
 *  - 其后每行 `1:` + `base64(iv(12) || ciphertext+tag)`，一行的明文 = `词\t读音`（见
 *    `SelectionLedger.formatLine`）。行内不含 `\n`，所以「按字节回找最后一个 `\n` 截断残行」的
 *    口径原样成立：残行必然缺 `\n`，截掉即可，不会有半行与下一次追加拼成脏词。
 *
 * **为什么不用 AAD**：台账是统计流水，不是审计日志 —— 攻击者能改写这个文件就已经能以本应用身份
 * 调用 Keystore 了。不加 AAD 的代价是「行级重排 / 删除 / 复制」不可检测，但：行序对
 * `aggregate`（按词 + 读音汇总）与 `exportText`（按次数、词排序）**零影响**；删行 = 某词次数
 * 变小，与直接删整个文件同效；复制一行 = 次数 +1；拼接他机记录解不开（DEK 每装机一份）；
 * 伪造新词不可能（GCM tag 128 位）。加 AAD 要引入行序号，直接破坏 O(1) 追加 —— 不值得。
 *
 * **降级阶梯**（[KEY_ALIAS] 的 `setUnlockedDeviceRequired` 依赖厂商 Keymaster，实测 MuMu 直接抛
 * `ProviderException`）：
 *  1. 别名已存在 → 直接用；
 *  2. 严口径生成；失败 → 删掉可能半建的别名 → **再试一次**（挡瞬时失败：Keystore 启动中 / TEE busy）；
 *  3. 仍失败 → 独立别名 [NOLOCK_ALIAS] 生成，包裹件版本字节记 2（= 退一档），W 日志一条；
 *  4. 每次进程首次解包时若见到版本 2 → 重试一次严口径；成功则「解包旧 DEK → 新密钥重包裹 →
 *     原子替换包裹件 → 删旧别名」，失败则原样保留（包裹件是「先写 `.tmp` 再改名」，不存在半份）；
 *  5. 版本 2 的机器上，页面文案如实写「本机密钥库不支持锁屏隔离」（见 `UserDictActivity`）。
 *
 * 线程模型：行级加解密无锁（[SecretKeySpec] 只读）；DEK 的首次取用（Keystore 解包）加锁并缓存，
 * 由台账的写线程或页面的读线程触发，都是后台线程。
 */
internal object LedgerCrypto {

    private const val TAG = "LedgerCrypto"

    /** 加密台账的首行标识（迁移判据；改动即等于换格式，旧文件会被当成明文迁移） */
    const val HEADER = "#jinn-picks1"

    /** 行记录前缀（版本号）：读到不认识的版本即视为脏行丢弃 */
    const val RECORD_PREFIX = "1:"

    /** 数据密钥（被 Keystore 包裹）的文件名，落在 `filesDir` 下 */
    const val KEY_FILE = "user_picks.key"

    /**
     * 用本密钥加密的文件清单（[orphanExistingData] 的判据）。
     *
     * 增删按本密钥加密的文件时**必须**同步这张表：漏一个，就会在那张文件上重演「密钥件没了 ⇒
     * 静默换新密钥 ⇒ 旧数据永久失读」。
     */
    private val PROTECTED_FILES = listOf(SelectionLedger.FILE_NAME, UserFrequency.FILE_NAME)

    /**
     * Keystore 别名：**必须**与 `ClipboardCrypto` / `CredentialCrypto` 的别名不同。
     *
     * 密钥轮换 / 失效是按别名发生的：共用别名时清一次剪贴板密钥会连带让台账（以及反之）全部解不开。
     */
    private const val KEY_ALIAS = "jinn_ledger_key"

    /** 退一档用的独立别名（本机 Keymaster 不支持 `setUnlockedDeviceRequired` 时） */
    private const val NOLOCK_ALIAS = "jinn_ledger_key_nolock"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** GCM 标准 IV 长度；Keystore 生成的 IV 也是 12 字节，解包时按它切分 */
    internal const val IV_BYTES = 12

    /** 数据密钥长度（AES-256） */
    private const val DEK_BYTES = 32

    /** 包裹文件的版本字节：1 = 严口径（锁屏不可解密），2 = 退一档（本机不支持锁屏隔离） */
    private const val WRAP_VERSION: Byte = 1
    private const val WRAP_VERSION_NOLOCK: Byte = 2

    /**
     * 密钥不可用后的冷却窗口（N5）。
     *
     * 密钥永久失效（Keystore 被清 / ROM 不支持）时，原先**每次选词**都要读一遍包裹件、
     * 走一次 `KeyStore.load` + `getKey`（binder 5~20ms）再失败一次 —— 正是「不耗电」要防的形态。
     * 期内直接返回 null：既不去读文件，也不碰 Keystore。
     */
    internal const val FAIL_COOLDOWN_MS = 30_000L

    /** 一行密文的形态：`1:` + 至少 20 个 base64 字符（IV 12 + tag 16 + 最短明文也远超这个量） */
    private val RECORD_SHAPE = Regex("^1:[A-Za-z0-9+/]{20,}$")

    /** 解包后的 DEK 缓存：Keystore 只在首次取用时走一次（5~20ms），之后行级运算不碰它 */
    @Volatile
    private var cachedDek: SecretKey? = null

    /** 上次失败的时刻（0 = 没有失败）；配合 [FAIL_COOLDOWN_MS] 做负缓存 */
    @Volatile
    private var failedAt = 0L

    /** 失败日志每进程只写一条（否则密钥永久失效时日志会以每键 2 条的速率写盘） */
    @Volatile
    private var failLogged = false

    /** 本次进程是否已试过「退一档 → 严口径」的升级（只试一次，避免每次解包都试） */
    private val upgradeTried = AtomicBoolean(false)

    /** 密钥件缺失时是否已把旧数据改名保留（页面据此提示一次） */
    @Volatile
    private var orphaned = false

    /** 测试用：实际走到「读包裹件 / 走 Keystore」的次数（负缓存生效时不再增长） */
    @Volatile
    internal var resolveCountForTest = 0
        private set

    /**
     * 取数据密钥：`dir` 下的 [KEY_FILE] 存在则解包，不存在则生成并写回。
     *
     * @return null = 密钥不可用（Keystore 被清 / 锁屏 / 硬件异常 / 包裹文件损坏 / 旧数据待人工处理）。
     *   **不要**在 null 时回退明文，也不要把「解不开」当成「没有数据」。
     */
    fun dataKey(dir: File): SecretKey? {
        cachedDek?.let { return it }
        if (inCooldown()) return null
        return synchronized(this) {
            cachedDek?.let { return it }
            val dek = loadOrCreate(dir)
            if (dek == null) {
                failedAt = System.currentTimeMillis()
                if (!failLogged) {
                    failLogged = true
                    Diagnostics.w(
                        TAG,
                        "词面数据密钥不可用：${FAIL_COOLDOWN_MS / 1000}s 内不再重试（不写明文、不冒充空数据）",
                    )
                }
                return null
            }
            failedAt = 0L
            cachedDek = dek
            dek
        }
    }

    /** 丢弃缓存的 DEK 句柄（测试与「密钥疑似失效」时用；下次调用重新走 Keystore） */
    fun invalidate() {
        synchronized(this) {
            cachedDek = null
            failedAt = 0L
            failLogged = false
            upgradeTried.set(false)
            orphaned = false
        }
    }

    /** 取走「密钥件缺失、旧数据已改名保留」这条提示（只报一次） */
    fun consumeOrphanNotice(): Boolean {
        val had = orphaned
        orphaned = false
        return had
    }

    /**
     * 当前包裹口径：读包裹件首字节（不碰 Keystore）。
     *
     * 页面在「退一档」的机器上要如实告诉用户「本机密钥库不支持锁屏隔离」，这不能靠猜。
     */
    fun isDegraded(dir: File): Boolean = runCatching {
        val f = File(dir, KEY_FILE)
        if (!f.isFile || f.length() <= 1L) return false
        f.inputStream().use { it.read() == WRAP_VERSION_NOLOCK.toInt() }
    }.getOrDefault(false)

    private fun inCooldown(): Boolean {
        val at = failedAt
        return at != 0L && System.currentTimeMillis() - at < FAIL_COOLDOWN_MS
    }

    // ── 行级加解密（纯 JCE，JVM 可测）────────────────────────────

    /** 一行明文 → 一条记录（不含换行） */
    fun encryptLine(line: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        // 读出端按固定 12 字节切 IV：provider 若返回别的长度就是「写得进、读不出」的静默丢数据。
        // 真出现就当场失败（写侧返回 false），别让它悄悄落盘
        require(iv.size == IV_BYTES) { "GCM IV 必须是 $IV_BYTES 字节（当前 provider 返回 ${iv.size}）" }
        val ct = cipher.doFinal(line.toByteArray(Charsets.UTF_8))
        val buf = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, buf, 0, iv.size)
        System.arraycopy(ct, 0, buf, iv.size, ct.size)
        return RECORD_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(buf)
    }

    /**
     * 一条记录 → 一行明文；解不开（残行 / 被改 / 换了密钥 / 不是本格式）返回 null。
     *
     * 单行失败**只丢这一行**：整份台账不能因为一条脏记录而全空。
     */
    fun decryptLine(record: String, key: SecretKey): String? = runCatching {
        if (!record.startsWith(RECORD_PREFIX)) return null
        val raw = Base64.getDecoder().decode(record.substring(RECORD_PREFIX.length))
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    /** 文本是否为加密台账（首个非空行是 [HEADER]）；空文件 / 旧版明文一律 false */
    fun looksEncrypted(text: String): Boolean {
        if (text.isEmpty()) return false
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return first.trim() == HEADER
    }

    /** 单行是否为密文记录（供 [SelectionLedger] 重写首行时筛行） */
    fun isRecordLine(line: String): Boolean = RECORD_SHAPE.matches(line.trim())

    /**
     * 文本**看起来**是一份加密台账（哪怕首行标识丢了）。
     *
     * 判据与 [looksEncrypted] 正交：首行是记录形态，或 ≥90% 的非空非注释行都是记录形态。
     * 为什么必须有它 —— 首行标识一旦被删（外部改写 / 部分恢复 / 手工替换），
     * 只看首行的判定链会把密文当成**明文**迁移：
     *
     * ```
     * needsMigration=true → looksEncrypted=false → parse() 把 "1:…" 当成「没有读音的词」
     * → 用「加密后的乱码」原子覆盖原件 ⇒ 页面与导出件全变 base64 乱码
     * ```
     *
     * 这才是「一条代码路径把用户数据变成不可读」的洞。有了它，缺头台账会被**只重写首行**修好
     * （[SelectionLedger.migrateIfNeeded]），读侧也绝不落到明文 parse 出口。
     */
    fun looksLikeRecords(text: String): Boolean {
        if (text.isEmpty()) return false
        var total = 0
        var hits = 0
        var first: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (first == null) first = line
            total++
            if (RECORD_SHAPE.matches(line)) hits++
        }
        if (total == 0) return false
        if (first != null && RECORD_SHAPE.matches(first)) return true
        return hits * 10 >= total * 9
    }

    // ── DEK 的包裹与解包（Android Keystore）─────────────────────

    /**
     * 读包裹文件并解包；文件不存在则新建 DEK 并写回。
     *
     * 包裹文件**存在但解不开**时返回 null（而不是重建）：重建会让磁盘上已有的记录全部无法解密，
     * 而新记录又用新密钥写进同一个文件 —— 一份数据两种密钥，比明确失败更糟。
     */
    private fun loadOrCreate(dir: File): SecretKey? {
        resolveCountForTest++
        val file = File(dir, KEY_FILE)
        if (file.isFile && file.length() > 0L) {
            val stored = runCatching { file.readBytes() }.getOrElse {
                Diagnostics.w(TAG, "词面数据密钥读取失败: ${it.javaClass.simpleName}")
                return null
            }
            // 退一档的包裹件：每次进程首次解包时试一次换回严口径（阶梯第 4 步）
            if (stored.size > 1 + IV_BYTES && stored[0] == WRAP_VERSION_NOLOCK) {
                upgradeToStrict(file, stored)?.let { return it }
            }
            val dek = unwrap(stored)
            // 已经换回严口径、旧别名还在：顺手清掉（中断在「写回包裹件之后、删别名之前」就会这样）
            if (dek != null && stored[0] == WRAP_VERSION && getAliasKey(NOLOCK_ALIAS) != null) {
                dropAlias(NOLOCK_ALIAS)
            }
            if (dek == null) Diagnostics.w(TAG, "词面数据密钥解包失败（Keystore 被清 / 锁屏 / 文件损坏）")
            return dek
        }
        // 密钥件不在，但已有用它加密过的数据 ⇒ **绝不**新建 DEK
        if (orphanExistingData(dir)) return null
        val plain = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
        val session = loadKeystoreKey() ?: run {
            Diagnostics.w(TAG, "词面数据密钥生成失败（Keystore 不可用）")
            return null
        }
        val wrapped = wrapWith(plain, session.key, session.version) ?: run {
            Diagnostics.w(TAG, "词面数据密钥包裹失败")
            return null
        }
        if (!writeAtomically(file, wrapped)) {
            Diagnostics.w(TAG, "词面数据密钥落盘失败")
            return null
        }
        return SecretKeySpec(plain, "AES")
    }

    /**
     * N2：密钥件不见了、而用它加密的文件还在 ⇒ 把那些文件改名成 `.orphan` 原样保留，返回 true。
     *
     * 为什么不是「重建密钥继续用」：旧记录永久不可读，而新记录用新密钥写进同一个文件 ——
     * 一份文件两种密钥，用户看到的是「页面 0 条」，再选几次词就把旧数据越埋越深，**零提示**。
     * 改名之后，本进程这次写入明确失败（fail-closed），下一次调用就走「没有数据 ⇒ 允许新建」，
     * 功能自愈且旧数据留在原地可人工抢救。改名而非删除：`.orphan` 里就是原始密文。
     */
    private fun orphanExistingData(dir: File): Boolean {
        var moved = false
        for (name in PROTECTED_FILES) {
            val f = File(dir, name)
            if (!f.isFile || f.length() == 0L) continue
            val dest = orphanTarget(dir, f)
            val ok = runCatching { java.nio.file.Files.move(f.toPath(), dest.toPath()) }.isSuccess
            if (ok) {
                moved = true
                Diagnostics.e(
                    TAG,
                    "密钥件 $KEY_FILE 缺失：${f.name} 已改名为 ${dest.name}（旧记录无法解密，原样保留待人工处理）",
                )
            } else {
                Diagnostics.w(TAG, "密钥件 $KEY_FILE 缺失且 ${f.name} 改名失败（本次不写任何数据）")
            }
        }
        if (moved) orphaned = true
        return moved
    }

    private fun orphanTarget(dir: File, src: File): File {
        val base = File(dir, src.name + ".orphan")
        if (!base.exists()) return base
        return File(dir, src.name + ".orphan-" + System.currentTimeMillis())
    }

    /** 用 Keystore 密钥包裹 DEK：`[1B ver][12B iv][ct]` */
    private fun wrapWith(dek: ByteArray, key: SecretKey, version: Byte): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "包裹 IV 必须是 $IV_BYTES 字节" }
        val ct = cipher.doFinal(dek)
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = version
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        out
    }.getOrElse {
        Diagnostics.w(TAG, "词面数据密钥包裹失败: ${it.javaClass.simpleName} ${it.message}")
        null
    }

    /** 解包 DEK：按版本字节选别名；格式不认识 / 版本不符 / 认证失败一律 null */
    private fun unwrap(stored: ByteArray): SecretKey? {
        if (stored.size <= 1 + IV_BYTES) return null
        val alias = when (stored[0]) {
            WRAP_VERSION -> KEY_ALIAS
            WRAP_VERSION_NOLOCK -> NOLOCK_ALIAS
            else -> return null
        }
        val key = getAliasKey(alias) ?: return null
        return unwrapWith(stored, key)
    }

    private fun unwrapWith(stored: ByteArray, key: SecretKey): SecretKey? = runCatching {
        if (stored.size <= 1 + IV_BYTES) return null
        if (stored[0] != WRAP_VERSION && stored[0] != WRAP_VERSION_NOLOCK) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(TAG_BITS, stored, 1, IV_BYTES),
        )
        val dek = cipher.doFinal(stored, 1 + IV_BYTES, stored.size - 1 - IV_BYTES)
        if (dek.size != DEK_BYTES) return null
        SecretKeySpec(dek, "AES")
    }.getOrElse {
        Diagnostics.w(TAG, "词面数据密钥解包异常: ${it.javaClass.simpleName}")
        null
    }

    /**
     * 阶梯第 4 步：退一档的包裹件试着换回严口径。成功返回 DEK，任何一步不成立都返回 null（原样保留）。
     *
     * 顺序是「先解包 → 再生成严口径 → 再原子替换包裹件 → **最后**删旧别名」：
     * 包裹件是「先写 `.tmp` 再改名」，任何中断都只会停在「旧文件仍然有效」或「新文件有效 + 两个别名
     * 都在」，绝不会出现「文件指向已删的别名」。
     */
    private fun upgradeToStrict(file: File, stored: ByteArray): SecretKey? {
        if (!upgradeTried.compareAndSet(false, true)) return null
        val weak = getAliasKey(NOLOCK_ALIAS) ?: return null
        // 重包裹要的是 DEK 原始字节，不是 SecretKey 句柄
        val dek = unwrapWith(stored, weak)?.encoded ?: return null
        val strict = generateStrict() ?: return null
        val rewrapped = wrapWith(dek, strict, WRAP_VERSION)
        if (rewrapped == null || !writeAtomically(file, rewrapped)) {
            // 没换成功：新别名必须删掉，否则下次 `getKey(KEY_ALIAS)` 命中它，而包裹件还是 nolock
            // 版本 —— 读得出来纯属运气，一旦再换就整份失读
            dropAlias(KEY_ALIAS)
            return null
        }
        dropAlias(NOLOCK_ALIAS)
        Diagnostics.i(TAG, "词面密钥已升级为锁屏隔离口径（DEK 已重包裹）")
        return SecretKeySpec(dek, "AES")
    }

    /** 一次会话用的 Keystore 密钥 + 它对应的包裹版本字节 */
    private class SessionKey(val key: SecretKey, val version: Byte)

    /**
     * 按阶梯取包裹密钥（不存在则生成）。
     *
     * 严口径（`setUnlockedDeviceRequired`：锁屏时不可解密）挡的是锁屏状态下的活体取证；
     * 台账与词频只在用户打字 / 打开页面时读写，这两件事都要求已解锁，所以严口径对正常使用无影响。
     * 但它依赖厂商 Keymaster 实现，部分 ROM / 模拟器会直接抛 `ProviderException`（实测 MuMu 上如此）——
     * 那时按阶梯退一档：退档只损失「锁屏状态的取证防护」，而不退档的后果是**功能整个不可用**。
     */
    private fun loadKeystoreKey(): SessionKey? {
        getAliasKey(KEY_ALIAS)?.let { return SessionKey(it, WRAP_VERSION) }
        getAliasKey(NOLOCK_ALIAS)?.let { return SessionKey(it, WRAP_VERSION_NOLOCK) }
        generateStrict()?.let { return SessionKey(it, WRAP_VERSION) }
        val weak = runCatching { generate(unlockedRequired = false, alias = NOLOCK_ALIAS) }.getOrNull()
            ?: return null
        Diagnostics.w(TAG, "密钥退一档：本机密钥库不支持锁屏隔离（别名 $NOLOCK_ALIAS）")
        return SessionKey(weak, WRAP_VERSION_NOLOCK)
    }

    /** 阶梯第 2 步：严口径，失败先删可能半建的别名再试一次（挡「这一秒 TEE 忙」这类瞬时失败） */
    private fun generateStrict(): SecretKey? {
        val first = runCatching { generate(unlockedRequired = true) }
        first.getOrNull()?.let { return it }
        Diagnostics.w(
            TAG,
            "词面密钥按「锁屏不可解密」生成失败（${first.exceptionOrNull()?.javaClass?.simpleName}），" +
                "删掉半建别名后重试一次",
        )
        dropAlias(KEY_ALIAS)
        return runCatching { generate(unlockedRequired = true) }.getOrNull()
    }

    private fun generate(unlockedRequired: Boolean, alias: String = KEY_ALIAS): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (unlockedRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    private fun getAliasKey(alias: String): SecretKey? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            .getKey(alias, null) as? SecretKey
    }.getOrNull()

    private fun dropAlias(alias: String) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        }.onFailure {
            Diagnostics.w(TAG, "删除密钥别名失败（$alias）: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 先写 `.tmp`、`fsync` 再改名：进程被杀 / 掉电时不会留下半截密钥文件（半截等于永久解不开）。
     *
     * `fsync` 不能省：改名只保证**目录项**落盘，数据块可能还在页缓存里 —— 掉电后就是
     * 「文件在、内容是空的」，比没有这个文件更糟（会被当成「包裹件损坏」而整份失读）。
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
}
