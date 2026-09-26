package com.jinn.inputmethod

import org.json.JSONObject

/**
 * 「配置导出 / 导入」备份包的格式定义与纯逻辑。
 *
 * 落盘形态是一个 zip 容器（读写见 [ConfigBackupManager]），内部条目：
 *  - `manifest.json`   包元信息（格式标识 / 格式版本 / 来源版本 / 时间 / 设备）+ 各节 sha256
 *  - `prefs.json`      两个 SharedPreferences 的键值，带类型标记（见 [BackupValue]）
 *  - `user_freq.txt`   用户词频，沿用运行期文件格式（人可读；合并语义在 [UserFrequency]）
 *  - `clipboard.jsonl` （可选）剪贴板明文，每行一条 JSON
 *  - `dicts/<name>.xz` （可选）已下载的可选词库原文件
 *
 * 为什么剪贴板要落成明文：密文由 Android Keystore 里的本机密钥保护，换机后**无法解密**
 * （见 [ClipboardCrypto]），密文导出等于导出一堆废数据；因此要么明文迁移、要么不带。
 * 明文的隐私代价由调用方（设置页）用显式勾选项告知用户，默认不勾。
 *
 * ## 包的完整形态（跨版本兼容的规则）
 *
 * 外层（[ConfigCrypto] 加密壳，41 字节明文头）：
 * `MAGIC(8) | version(1) | iterations(4) | salt(16) | iv(12)` + AES-256-GCM 密文；
 * `iterations` 有 10 万~200 万护栏（强度不由文件自己声明）。
 *
 * 内层 zip：生产者固定把 **manifest 写为第一个条目**（读取端按名查找、不依赖顺序；但
 * `ZipInputStream` 不能跳转，放后面会让每次「扫 manifest」都把前面全部节解压丢弃一遍、
 * 一次导入要扫它两趟 —— 所以这是**生产者的规则**，不是读取端的校验项）。
 *  - `manifest.json`：`format`（固定 `jinn-config`）、`formatVersion`、`appVersionCode`、
 *    `createdAt`、`device`（≤64 字）、`sections{<节名>:{sha256, bytes}}`
 *  - `prefs.json`：两个 SharedPreferences 文件 → 键 → `{t: 类型标记, v: 值}`
 *  - `user_freq.txt`：与运行期词频文件同格式（`词\t权重\t天`）
 *  - `clipboard.jsonl`（可选）：每行一条 JSON
 *  - `dicts/<文件名>.xz`（可选）：已下载词库原文件（导入只接受可选词库清单内的名字）
 *
 * 节名 ↔ 条目名：`prefs`→`prefs.json`、`user_freq`→`user_freq.txt`、`clipboard`→`clipboard.jsonl`、
 * `dicts`→`dicts/` 前缀；dicts 的聚合摘要 = 按文件名排序的 `<名>:<sha256>` 换行拼接后再取 sha256。
 * 三个**文本节**的 sha256 都算在原始 UTF-8 字节上（不是某种规范化形式），hex 小写；
 * 词库文件的 sha256 算在**压缩文件的原始字节**上（`dicts/` 目录下的 `*.xz`）。
 * 版本规则：`formatVersion` 高于本版 → 拒绝导入（[checkVersion]）；低于本版 → 走迁移。
 *
 * 本对象不引用 Android API（`org.json` 由 Android 平台与 JVM 测试依赖共同提供），
 * 因此编解码往返、版本校验、类型不符降级、哈希规划都直接跑 JVM 单测。
 */
internal object ConfigBackup {

    /** 包标识：解析时用它挡住「随便一个 zip」 */
    const val FORMAT = "jinn-config"

    /**
     * 包格式版本（不是 App 版本）。
     *
     * 兼容规则（[checkVersion]）：高于本值 = 包由更新版 App 生成，拒绝导入；
     * 低于本值 = 走迁移（当前只有 v1，尚无迁移链）。
     */
    const val FORMAT_VERSION = 1

    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_PREFS = "prefs.json"
    const val ENTRY_USER_FREQ = "user_freq.txt"
    const val ENTRY_CLIPBOARD = "clipboard.jsonl"
    const val DICT_DIR = "dicts/"

    /** 两个 SharedPreferences 文件名（与 [Prefs] / [ClipboardPrefs] 一致） */
    const val PREFS_MAIN = "jinn_inputmethod"
    const val PREFS_CLIPBOARD = "jinn_clipboard"

    /** 备份包文件名前缀（生成到缓存目录时用） */
    const val FILE_PREFIX = "jinn-config-"

    /**
     * 单次导入的剪贴板条数上限。
     *
     * 正常用户最多几百条，这个数只用来挡住构造/畸形包：导入要为每条做一次 Keystore 加密与落库，
     * 而进度框不可取消 —— 没有上限时「十几万条极短条目」的包会变成几分钟假死。
     */
    const val MAX_CLIP_IMPORT_ITEMS = 10_000

    /**
     * 解析层的条数闸相对入库层的倍数。
     *
     * 为什么要两级：解析（[decodeClipboard]）只做「读文本 → 对象」，而入库
     * （[planClipboardImport]）还要逐条加密 + 写库。解析层放宽是为了让导入清单能显示真实条数，
     * 入库层才卡死上限。
     */
    private const val CLIP_PARSE_ITEM_FACTOR = 4

    /**
     * 导入条目的时间戳下限（2000-01-01 UTC，毫秒）。
     *
     * 早于剪贴板功能存在的时间，不会误伤合法数据；只用来挡「缺字段 = 0（1970）」
     * 与明显异常的远古时间。
     */
    const val MIN_CLIP_TIMESTAMP_MS = 946_684_800_000L

    /**
     * 备份包里的时间戳钳位（纯函数）。
     *
     * 外部包的时间戳不能直接采信：缺字段（0 = 1970）会让条目一入库就成「最旧」，
     * 库满时刚导入就被 trimTo 裁掉；而未来时间（时钟回拨 / 手改包）会永久钉在列表顶部、
     * 两种裁剪都不会淘汰它。钳到 [MIN_CLIP_TIMESTAMP_MS, now]。
     */
    fun clampClipTimestamp(raw: Long, now: Long = System.currentTimeMillis()): Long =
        raw.coerceIn(MIN_CLIP_TIMESTAMP_MS, now)

    /** `manifest.device` 的展示长度上限：它是外部输入，会原样进导入清单的对话框 */
    private const val MAX_DEVICE_CHARS = 64

    /**
     * 一个配置值 + 它的原始类型标记。
     *
     * 必须带标记：SharedPreferences 里 Int / Long / Float 是三种不同类型，
     * 而 JSON 数字只有一种——靠猜会让 `getFloat` 拿到 Integer 直接 ClassCastException。
     */
    class BackupValue(val kind: Char, val value: Any?) {

        /** 该值能否按备份里的类型标记还原（编码期用它兜住非法类型） */
        val isSupported: Boolean
            get() = when (kind) {
                KIND_STRING -> value is String
                KIND_INT -> value is Int
                KIND_LONG -> value is Long
                KIND_FLOAT -> value is Float
                KIND_BOOL -> value is Boolean
                KIND_NULL -> value == null
                else -> false
            }

        companion object {
            const val KIND_STRING = 's'
            const val KIND_INT = 'i'
            const val KIND_LONG = 'l'
            const val KIND_FLOAT = 'f'
            const val KIND_BOOL = 'b'

            /** 键存在但值为 null（如 favoriteSymbols 的「从未编辑」态） */
            const val KIND_NULL = 'n'

            /** 由运行期值推断类型标记；不支持的类型返回 null（调用方跳过该键） */
            fun of(raw: Any?): BackupValue? = when (raw) {
                null -> BackupValue(KIND_NULL, null)
                is String -> BackupValue(KIND_STRING, raw)
                is Int -> BackupValue(KIND_INT, raw)
                is Long -> BackupValue(KIND_LONG, raw)
                // 非有限 Float 不能进包：`%.6f` 会写出 `NaN` / `Infinity`，那不是合法 JSON，
                // 导入端整节解析失败 → 变成静默的「设置 0 项」。这类值只可能来自被外部改过的存档，跳过
                is Float -> if (raw.isFinite()) BackupValue(KIND_FLOAT, raw) else null
                is Boolean -> BackupValue(KIND_BOOL, raw)
                else -> null
            }
        }
    }

    /**
     * 一节内容的摘要（sha256 + 字节数），用于导入前校验完整性。
     *
     * `bytes` 只作登记与诊断（导入端只校验 sha256）。注意两类标准：三个文本节是 **UTF-8 明文字节**，
     * 词库节的 `bytes` 是各 `.xz` **压缩字节**之和。
     */
    class Section(val sha256: String, val bytes: Long)

    /** 包元信息 */
    class Manifest(
        val formatVersion: Int,
        val appVersionCode: Int,
        val createdAt: Long,
        val device: String,
        val sections: Map<String, Section>,
    )

    /** 版本兼容判定（TOO_NEW 必须拒绝：新格式可能含本版不认识的语义） */
    enum class VersionCompat { OK, TOO_NEW }

    /** 节名（manifest 的 sections 键） */
    const val SEC_PREFS = "prefs"
    const val SEC_USER_FREQ = "user_freq"
    const val SEC_CLIPBOARD = "clipboard"
    const val SEC_DICTS = "dicts"

    // ── 版本 ────────────────────────────────────────────────

    fun checkVersion(found: Int, supported: Int = FORMAT_VERSION): VersionCompat =
        if (found > supported) VersionCompat.TOO_NEW else VersionCompat.OK

    // ── 摘要 ────────────────────────────────────────────────

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ── manifest ─────────────────────────────────────────────

    fun encodeManifest(m: Manifest): String {
        val sb = StringBuilder(256)
        sb.append("{\n")
        sb.append("  \"format\": \"").append(FORMAT).append("\",\n")
        sb.append("  \"formatVersion\": ").append(m.formatVersion).append(",\n")
        sb.append("  \"appVersionCode\": ").append(m.appVersionCode).append(",\n")
        sb.append("  \"createdAt\": ").append(m.createdAt).append(",\n")
        sb.append("  \"device\": \"").append(escape(m.device)).append("\",\n")
        sb.append("  \"sections\": {")
        var first = true
        for ((name, sec) in m.sections) {
            if (!first) sb.append(',')
            first = false
            sb.append("\n    \"").append(escape(name)).append("\": {\"sha256\": \"")
                .append(sec.sha256).append("\", \"bytes\": ").append(sec.bytes).append('}')
        }
        if (!first) sb.append('\n').append("  ")
        sb.append("}\n}\n")
        return sb.toString()
    }

    /** 解析 manifest；不是本程序的包或字段缺失/非法一律返回 null（调用方按「无法识别」提示） */
    fun parseManifest(text: String): Manifest? = runCatching {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT) return null
        val ver = o.optInt("formatVersion", -1)
        if (ver <= 0) return null
        val sectionsObj = o.optJSONObject("sections") ?: JSONObject()
        val sections = LinkedHashMap<String, Section>()
        for (name in sectionsObj.keys()) {
            val s = sectionsObj.optJSONObject(name) ?: continue
            val hash = s.optString("sha256")
            if (hash.isEmpty()) continue
            sections[name] = Section(hash, s.optLong("bytes", 0L))
        }
        Manifest(
            formatVersion = ver,
            appVersionCode = o.optInt("appVersionCode", 0),
            createdAt = o.optLong("createdAt", 0L),
            // 外部输入：截断并压平（它会原样进导入清单的对话框，超长/多行会拖垮布局）
            device = o.optString("device").take(MAX_DEVICE_CHARS).replace('\n', ' ').replace('\r', ' '),
            sections = sections,
        )
    }.getOrNull()

    // ── prefs ────────────────────────────────────────────────

    /**
     * 编码 prefs：`{"<prefs 文件名>": {"<key>": {"t":"s","v":...}}}`。
     *
     * 手工拼接而非 `JSONObject.toString()`：JSONObject 基于 HashMap，输出顺序不稳定，
     * 导出包就没法 diff、也没法用哈希做内容核对。
     */
    fun encodePrefs(files: Map<String, Map<String, BackupValue>>): String {
        val sb = StringBuilder(1024)
        sb.append("{\n")
        var firstFile = true
        for ((fileName, entries) in files) {
            if (!firstFile) sb.append(",\n")
            firstFile = false
            sb.append("  \"").append(escape(fileName)).append("\": {")
            var firstEntry = true
            for ((key, v) in entries) {
                if (!v.isSupported) continue
                if (!firstEntry) sb.append(',')
                firstEntry = false
                sb.append("\n    \"").append(escape(key)).append("\": {\"t\": \"")
                    .append(v.kind).append("\", \"v\": ")
                sb.append(encodeScalar(v))
                sb.append('}')
            }
            if (!firstEntry) sb.append('\n').append("  ")
            sb.append('}')
        }
        sb.append("\n}\n")
        return sb.toString()
    }

    /**
     * 解析 prefs；结构非法返回空表（调用方按「缺失」处理）。
     *
     * 键与类型标记的合法性校验在 [Prefs.importFromBackup] 内做（只有它知道白名单），
     * 这里只保证「值取出来时已经是正确的 Kotlin 类型」。
     */
    fun decodePrefs(text: String): Map<String, Map<String, BackupValue>> = runCatching {
        val root = JSONObject(text)
        val out = LinkedHashMap<String, Map<String, BackupValue>>()
        for (fileName in root.keys()) {
            val entries = root.optJSONObject(fileName) ?: continue
            val values = LinkedHashMap<String, BackupValue>()
            for (key in entries.keys()) {
                val item = entries.optJSONObject(key) ?: continue
                val kind = item.optString("t").firstOrNull() ?: continue
                val v = item.opt("v")
                val value: Any? = when (kind) {
                    BackupValue.KIND_STRING -> v as? String
                    BackupValue.KIND_INT -> (v as? Number)?.toInt()
                    BackupValue.KIND_LONG -> (v as? Number)?.toLong()
                    BackupValue.KIND_FLOAT -> (v as? Number)?.toFloat()
                    BackupValue.KIND_BOOL -> v as? Boolean
                    BackupValue.KIND_NULL -> null
                    else -> null
                }
                val restored = BackupValue(kind, value)
                if (restored.isSupported) values[key] = restored
            }
            out[fileName] = values
        }
        out
    }.getOrDefault(emptyMap())

    private fun encodeScalar(v: BackupValue): String = when (v.kind) {
        BackupValue.KIND_STRING -> "\"" + escape(v.value as String) + "\""
        BackupValue.KIND_INT, BackupValue.KIND_LONG -> v.value.toString()
        // Float 走 Double 写法：`1.0` 而非 `1`，且不用 toString() 的科学计数法（1.0E-5）
        BackupValue.KIND_FLOAT -> java.lang.String.format(java.util.Locale.US, "%.6f", v.value as Float)
        BackupValue.KIND_BOOL -> (v.value as Boolean).toString()
        else -> "null"
    }

    // ── 剪贴板 ────────────────────────────────────────────────

    /** 剪贴板明文条目（迁移用；字段与 [ClipboardDb.Item] 对齐，但不含自增 id） */
    class ClipEntry(
        val content: String,
        val createdAt: Long,
        val sourcePackage: String,
        val sourceAppName: String,
        val category: String,
        val favorite: Boolean,
    )

    /** 与 [ClipboardDb.stableHash] 一致（SHA-256 hex / UTF-8），去重判据必须一致 */
    fun clipboardHash(content: String): String = sha256(content)

    /** 编码成 jsonl：一行一条，内容里的换行由 JSON 转义承载 */
    fun encodeClipboard(entries: List<ClipEntry>): String {
        val sb = StringBuilder(entries.size * 96 + 16)
        for (e in entries) {
            sb.append('{')
            sb.append("\"content\": \"").append(escape(e.content)).append("\", ")
            sb.append("\"createdAt\": ").append(e.createdAt).append(", ")
            sb.append("\"sourcePackage\": \"").append(escape(e.sourcePackage)).append("\", ")
            sb.append("\"sourceAppName\": \"").append(escape(e.sourceAppName)).append("\", ")
            sb.append("\"category\": \"").append(escape(e.category)).append("\", ")
            sb.append("\"favorite\": ").append(e.favorite)
            sb.append("}\n")
        }
        return sb.toString()
    }

    /**
     * 解析剪贴板节：坏行跳过（备份是外部文件，半截/被编辑都要能降级）。
     *
     * [maxItems] 是硬闸：单节字节上限挡不住「极短行」的洪流（16MB 可含近百万行），
     * 每行都要构造一个 `ClipEntry` —— 不设闸就是一条堆峰值 >100MB 的 OOM 通道。
     * 自产包受导出侧条数上限约束，超限只可能来自被改过的包。
     */
    fun decodeClipboard(
        text: String,
        maxItems: Int = MAX_CLIP_IMPORT_ITEMS * CLIP_PARSE_ITEM_FACTOR,
    ): List<ClipEntry> {
        val out = ArrayList<ClipEntry>()
        for (raw in text.lineSequence()) {
            if (out.size >= maxItems) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            val e = runCatching {
                val o = JSONObject(line)
                val content = o.optString("content")
                if (content.isEmpty()) return@runCatching null
                ClipEntry(
                    content = content,
                    createdAt = clampClipTimestamp(o.optLong("createdAt", 0L)),
                    sourcePackage = o.optString("sourcePackage"),
                    sourceAppName = o.optString("sourceAppName"),
                    // 归一到分类标签白名单：它会进剪贴板面板的 meta 文本（主线程布局），
                    // 不归一的话一个包就能塞进任意长/任意内容的"分类"去卡界面。
                    // 多标签（2026-09-25）：合法的 `URL,NUMBER` 要保留两个标签，故走 normalizeLabels
                    category = ClipboardClassifier.normalizeLabels(o.optString("category")),
                    favorite = o.optBoolean("favorite", false),
                )
            }.getOrNull() ?: continue
            out.add(e)
        }
        return out
    }

    /**
     * 规划导入哪些条目：按内容哈希去重（含与本机已有内容去重），批内同 hash 只留一条，
     * 按时间升序落库（保证入库后的时间序）。
     *
     * [maxItems] 是条数上限：单节 16MB 的字节闸挡不住「极短条目」的洪流（可含十几万条），
     * 而导入要为每条做一次 Keystore 加密 + 落库、模态框又不可取消 —— 超大包会变成几分钟假死。
     * 超出的条目计入跳过数（如实告知用户），而不是让它卡住。
     */
    fun planClipboardImport(
        existingHashes: Set<String>,
        incoming: List<ClipEntry>,
        maxItems: Int = MAX_CLIP_IMPORT_ITEMS,
    ): List<ClipEntry> {
        val seen = HashSet<String>(existingHashes)
        val out = ArrayList<ClipEntry>(minOf(incoming.size, maxItems))
        for (e in incoming) {
            if (out.size >= maxItems) break
            if (!seen.add(clipboardHash(e.content))) continue
            out.add(e)
        }
        return out.sortedBy { it.createdAt }
    }

    // ── 工具 ────────────────────────────────────────────────

    /** JSON 字符串转义：只处理必须转义的字符（控制字符走 \u 形式） */
    private fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format(java.util.Locale.US, "\\u%04x", c.code))
                else sb.append(c)
            }
        }
        return sb.toString()
    }
}
