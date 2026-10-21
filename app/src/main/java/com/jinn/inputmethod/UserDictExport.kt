package com.jinn.inputmethod

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 用户词库的**加密导出包**（导出 / 导入共用的容器逻辑）。
 *
 * ## 形态（两层，与「导出配置文件」同源）
 *  - 外壳：`ConfigCrypto` 的 AES-256-GCM 壳（`JINNENC1` + PBKDF2 600k，密码只能用汉字）。
 *    密码规则**直接复用** [ConfigCrypto.validatePassword]，不另写一套；
 *  - 内层：**普通 zip**，包内是**明文**词表条目（`user_dict.dict.yaml` / `user_dict.tsv` /
 *    `user_dict.txt`）。「解压之后拿出来的是明文」说的就是这一层：条目本身不加密，
 *    加密的是整个包体。
 *
 * 为什么不写成「标准 zip 自带密码」：Java 标准库不支持 WinZip AES，传统 ZipCrypto 又已被
 * 已知明文攻击破掉（与项目「AES-256 起步」的安全口径冲突），而引第三方库违背依赖极简。
 * 因此采用与配置包完全一致的外壳 —— 代价是**必须用本 App 输入密码导入**，通用解压软件打不开。
 *
 * ## 中间件口径
 * 打包过程中会有一份明文 zip 落在缓存目录（`cache/userdict/`）：写完密文**立即删除**；
 * 进程被杀留下的残件由 [cleanup] 按时间窗回收（与 `ConfigBackupManager.cleanupStaleCache` 同款）。
 */
internal object UserDictExport {

    private const val TAG = "UserDictExport"

    /** 加密包后缀：与配置包同用一个（都是 `ConfigCrypto` 壳，导入侧靠 magic 判定） */
    const val FILE_SUFFIX = ".jinn"

    /** 中间件目录（缓存里独立一层，不与配置包的清理规则互相干扰） */
    internal const val CACHE_DIR = "userdict"

    /** 加密包体积上限：导入侧先按它限流（防误选巨型文件把缓存写满） */
    internal const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024

    /** 包内词表条目的大小上限（明文；8MB 导入上限之外再留一倍余量给 Rime 表头与次数列） */
    internal const val MAX_ENTRY_BYTES = 16L * 1024 * 1024

    /**
     * 包内条目名白名单（N10）。
     *
     * 原判据只有「条目数 ≤ 8」，于是**手搓的单条目包**（例如只含 `prefs.json` 的配置包）
     * 会被当成词表 parse 出一堆垃圾词。白名单把启发式换成固定口径：三个自产名字之一。
     */
    private val ALLOWED_ENTRY = Regex("""^user_dict\.(dict\.yaml|tsv|txt)$""")

    /** 残留中间件的可清理年龄（与配置包同口径：主保险是「用完即删」） */
    private const val STALE_MIN_AGE_MS = 60 * 1000L

    /**
     * 在途中间件名册（N9）：[cleanup] 不得删正在用的临时件。
     *
     * 现在 cleanup 只在页面 `onCreate` 跑一次，看似删不到在途件 —— 但配置包侧（L-1086）已经踩过
     * 同型：谁把 cleanup 挪到 `onResume` / 后台定时，就会删掉用户正输着密码的那份暂存件。
     * 与 `ConfigBackupManager.tempInFlight` 同一套写法，删之前先看名册。
     */
    private val inFlight: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet<String>())

    /** 登记一个正在用的中间件（用完必须 [release]） */
    fun markInFlight(file: File) {
        inFlight.add(file.name)
    }

    /** 注销在途登记 */
    fun release(file: File) {
        inFlight.remove(file.name)
    }

    /** 包内条目名（= 解压后拿到的明文文件名）：与导出格式的扩展名一致 */
    fun entryName(format: SelectionLedger.ExportFormat): String = "user_dict.${format.ext}"

    /** 建议的保存文件名：`user_dict.<ext>.jinn`（后缀即提示「要输入密码」） */
    fun suggestedName(format: SelectionLedger.ExportFormat): String = entryName(format) + FILE_SUFFIX

    /** 造单条目 zip（内存内完成；词表最大也就十几 MB） */
    fun zipOf(name: String, text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length / 2 + 512)
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry(name))
            zos.write(text.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * 读回条目：返回「条目名 to 明文」。
     *
     * 只接受**一个**白名单内的词表条目（多条目 / 名字不符 / 超出体积一律 null）：
     * 本包是自产的，形态不符只可能来自被改过的文件或「选错了包」，按不可用处理比猜更安全。
     */
    fun readSingleEntry(zip: File, maxBytes: Long = MAX_ENTRY_BYTES): Pair<String, String>? =
        runCatching {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var name: String? = null
                var text: String? = null
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (name != null) {
                        Diagnostics.w(TAG, "读取词表条目: 包内不止一个条目，按不可用处理")
                        return@use null
                    }
                    if (!ALLOWED_ENTRY.matches(entry.name)) {
                        Diagnostics.w(TAG, "读取词表条目: 条目名不在白名单内（${entry.name}）")
                        return@use null
                    }
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zis.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            Diagnostics.w(TAG, "读取词表条目: 超过 $maxBytes 字节上限")
                            return@use null
                        }
                        buf.write(chunk, 0, n)
                    }
                    name = entry.name
                    text = String(buf.toByteArray(), Charsets.UTF_8)
                }
                if (name == null || text == null) null else name to text
            }
        }.onFailure {
            Diagnostics.w(TAG, "读取词表条目失败: ${it.javaClass.simpleName}")
        }.getOrNull()

    /** 文件头是否为加密壳（[ConfigCrypto.MAGIC]）—— 导入侧据此分流「加密包 / 旧版明文」 */
    fun looksEncrypted(head: ByteArray): Boolean {
        if (head.size < ConfigCrypto.MAGIC.length) return false
        for (i in ConfigCrypto.MAGIC.indices) {
            if (head[i] != ConfigCrypto.MAGIC[i].code.toByte()) return false
        }
        return true
    }

    /** 读文件头（不足则返回读到的部分） */
    fun readHead(file: File, size: Int = ConfigCrypto.MAGIC.length): ByteArray = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n <= 0) break
                read += n
            }
            buf.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    /** 导出中间件的目录 */
    fun cacheDir(context: Context): File = File(File(context.cacheDir, ConfigBackupManager.CACHE_DIR), CACHE_DIR)

    /**
     * 清掉上次异常中断留下的中间件（明文 zip / 半截密文 / 导入暂存件）。
     *
     * 只按年龄回收：主保险是「写完即删」，这里兜住进程被杀那条路径。**在途名册里的文件一律跳过**
     * （见 [inFlight]），否则页面上正在用的暂存件会被自己这次清理删掉。
     */
    fun cleanup(context: Context) {
        val dir = cacheDir(context)
        val now = System.currentTimeMillis()
        for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile || now - f.lastModified() < STALE_MIN_AGE_MS) continue
            if (f.name in inFlight) continue
            if (f.delete()) Diagnostics.i(TAG, "清理残留中间文件: ${f.name}")
        }
    }
}
