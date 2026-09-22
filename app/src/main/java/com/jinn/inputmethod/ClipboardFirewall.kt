package com.jinn.inputmethod

import android.content.Context
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 增强模式：JinnIme 数据目录安全审计。
 *
 * 职责：使用 root 权限检查 JinnIme 私有数据目录的安全状态，确保剪贴板历史
 * 不被其他 APP 通过文件系统漏洞（错误权限/symlink/外部存储泄露等）读取。
 *
 * 不干预 Android System Clipboard，网盘、购物、分享类 APP 正常读取
 * 系统剪贴板口令不受影响。
 *
 * 安全边界：
 *  - 普通 APP → 无法读取 JinnIme History（Android App Sandbox 保证）
 *  - ROOT APP → Sandbox 保护不再是绝对边界
 *  - Kernel / Hook / Root Malware → Android App 层无法保证绝对防护
 *
 * 线程模型：后台线程执行 su 命令，不阻塞主线程。
 */
object ClipboardFirewall {

    /** root 是否可用（su 返回 0；带超时，su 弹窗未响应时不会永久挂起） */
    fun isRootAvailable(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
        try {
            // 用户未在 su 弹窗授权时进程可能一直挂着，必须限时
            if (!process.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroy()
                return@runCatching false
            }
            process.exitValue() == 0
        } finally {
            runCatching { process.destroy() }
        }
    }.getOrDefault(false)

    /**
     * 执行数据目录安全审计。每项审计返回「检查项名称 → 状态文本」。
     * 所有检查在后台线程执行（su 命令同步阻塞）。
     * @param context 用于获取包名与数据目录
     */
    fun audit(context: Context): List<Pair<String, String>> {
        val dataDir = context.dataDir.absolutePath  // /data/user/0/<pkg>/
        val dbPath = "$dataDir/databases/jinn_clipboard.db"
        val results = mutableListOf<Pair<String, String>>()

        // 1. 数据目录权限
        results.add(checkDirPerm(dataDir))

        // 2. 数据库文件权限
        results.add(checkDbPerm(dbPath))

        // 3. 数据目录 Owner
        results.add(checkOwner(dataDir))

        // 4. SELinux 状态
        results.add(checkSelinux())

        // 5. 符号链接检查
        results.add(checkSymlink(dataDir))

        // 6. 外部存储泄露
        results.add(checkExternalLeak())

        // 7. Backup 配置（读取实际生效的规则文件，非硬编码）
        results.add(checkBackupConfig(context))

        return results
    }

    /**
     * 执行 su 命令并取回 stdout；失败或超时返回 null。
     *
     * 必要防护（原实现缺了前两项）：
     *  - 两路都要后台排空：只排 stderr、把 stdout 放在当前线程读，一旦 stdout 管道写满
     *    子进程就会阻塞，而父进程正等着它退出，直接死锁（`find` 这类大量输出的命令必踩）；
     *  - 先 `waitFor(超时)` 再取文本：读流若放在 `waitFor` 之前，命令不退出就会永久阻塞，
     *    超时分支永远不可达，超时形同虚设（原实现即如此，见 `isRootAvailable` 的正确写法）；
     *  - 超时销毁进程，避免留下挂死的 su。
     */
    private fun su(cmd: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        try {
            // ByteArrayOutputStream 的写入/取字节都是 synchronized，跨线程累积安全
            val outBuf = java.io.ByteArrayOutputStream()
            val outDrain = Thread {
                runCatching {
                    process.inputStream.use { input ->
                        val buf = ByteArray(4096)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            outBuf.write(buf, 0, n)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val errDrain = Thread {
                runCatching { process.errorStream.bufferedReader().forEachLine { } }
            }.apply { isDaemon = true; start() }

            if (!process.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                Diagnostics.w(TAG, "su 超时（${SU_TIMEOUT_SEC}s），已销毁进程")
                outDrain.join(DRAIN_JOIN_MS)
                errDrain.join(DRAIN_JOIN_MS)
                return@runCatching null
            }
            outDrain.join(DRAIN_JOIN_MS)
            errDrain.join(DRAIN_JOIN_MS)
            String(outBuf.toByteArray(), Charsets.UTF_8).trim()
        } finally {
            runCatching { process.destroy() }
        }
    }.getOrNull()

    private fun checkDirPerm(dataDir: String): Pair<String, String> {
        val out = su("ls -ld '$dataDir'") ?: return "数据目录权限" to "无法访问（su 失败）"
        val ok = out.startsWith("drwx------")
        return "数据目录权限" to if (ok) "✓ $out" else "✗ 期望 drwx------，实际: $out"
    }

    private fun checkDbPerm(dbPath: String): Pair<String, String> {
        if (!File(dbPath).exists()) return "数据库权限" to "✓ 数据库不存在（无历史记录，安全）"
        val out = su("ls -l '$dbPath'") ?: return "数据库权限" to "无法访问（su 失败）"
        val ok = out.startsWith("-rw-------")
        return "数据库权限" to if (ok) "✓ $out" else "✗ 期望 -rw-------，实际: $out"
    }

    private fun checkOwner(dataDir: String): Pair<String, String> {
        val out = su("stat -c '%U' '$dataDir'") ?: return "数据目录 Owner" to "无法访问（su 失败）"
        val ok = out.startsWith("u0_a") || out.startsWith("app_")
        return "数据目录 Owner" to if (ok) "✓ $out" else "✗ 非 App 用户: $out"
    }

    private fun checkSelinux(): Pair<String, String> {
        val out = su("getenforce") ?: return "SELinux 状态" to "无法检查（su 失败）"
        val ok = out.equals("Enforcing", ignoreCase = true)
        return "SELinux 状态" to if (ok) "✓ $out" else "△ $out（非强制模式，Sandbox 保护减弱）"
    }

    private fun checkSymlink(dataDir: String): Pair<String, String> {
        val out = su("find '$dataDir' -type l 2>/dev/null") ?: return "符号链接" to "无法检查（su 失败）"
        return if (out.isBlank()) "符号链接" to "✓ 无危险符号链接"
        else "符号链接" to "✗ 发现符号链接:\n$out"
    }

    private fun checkExternalLeak(): Pair<String, String> {
        val out = su("find /storage/emulated/0 -maxdepth 4 -name 'jinn_clipboard*' 2>/dev/null")
            ?: return "外部存储泄露" to "无法检查（su 失败）"
        return if (out.isBlank()) "外部存储泄露" to "✓ 未在共享存储发现剪贴板数据库"
        else "外部存储泄露" to "✗ 共享存储发现剪贴板数据:\n$out"
    }

    /**
     * 真查 Backup 配置：解析当前系统实际生效的规则文件，确认剪贴板数据库被排除。
     *
     * 原实现硬编码返回 ✓，无论规则文件怎么改都显示"安全"，其实是个假检查。
     * 这里改为运行时读取规则文件；且必须按版本挑文件：
     *  - API ≥ 31 起 `fullBackupContent` 被 `dataExtractionRules` 取代，
     *    再查 backup_rules.xml 等于查一个不生效的文件，会给出错误的安全保证；
     *  - API < 31 才看 backup_rules.xml。
     *
     * 判据是「[REQUIRED_EXCLUDES] 里的每一条都被排除」，少一条都算不合格：
     * 除了库本体与用户词频，还必须覆盖 SQLite 的 `-journal/-wal/-shm` 三个侧车文件
     * （WAL 模式下最近的写入就在 `-wal` 里，只排除主库等于漏掉最新那批记录）。
     *
     * 分段文件（`cloud-backup` / `device-transfer`）要求每一段都排除齐：
     * 只查「有没有出现过这条 path」会让「仅在其中一段排除」也算通过。
     */
    private fun checkBackupConfig(context: Context): Pair<String, String> {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val resId = if (modern) R.xml.data_extraction_rules else R.xml.backup_rules
        val file = if (modern) "data_extraction_rules.xml" else "backup_rules.xml"
        val missing = missingBackupExclusions(context, resId)
        val detail = when {
            missing.isEmpty() -> "✓ $file 已排除剪贴板数据库（含 WAL 侧车）与用户词频"
            missing.none { it.startsWith(DB_NAME) } ->
                "✗ $file 已排除剪贴板数据库，但未排除用户词频（明文词表会随备份出炉）"
            else -> "✗ $file 未完整排除剪贴板数据（缺 ${missing.joinToString("、")}）"
        }
        return "Backup 配置" to detail
    }

    /**
     * 返回规则文件里没被排除的必需路径（解析失败时视为全部缺失）。
     *
     * `backup_rules.xml` 的 `exclude` 直接挂在根下，退化成单一空名段；
     * `data_extraction_rules.xml` 有两段，两段都得齐备才算排除成功。
     */
    private fun missingBackupExclusions(context: Context, resId: Int): List<String> = runCatching {
        val parser = context.resources.getXml(resId)
        val bySection = LinkedHashMap<String, MutableSet<String>>()
        var section = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "cloud-backup", "device-transfer" -> {
                        section = parser.name
                        bySection.getOrPut(section) { HashSet() }
                    }
                    "exclude" -> bySection.getOrPut(section) { HashSet() }
                        .add(parser.getAttributeValue(null, "path").orEmpty())
                }
                XmlPullParser.END_TAG -> if (parser.name == section) section = ""
            }
            parser.next()
        }
        // 段齐备性先判：按段写的规则文件（data_extraction_rules.xml）两段都必须存在 ，
        // 整段被删在 Android 里的语义是「该模式不做任何排除」，而只比对已存在的段会照样给 ✓
        // （与类注释「每一段都排除齐」相反）。backup_rules.xml 是扁平结构（exclude 直接挂根下），
        // 这时退化成单一空名段；bySection 整个为空（一条 exclude 都没有）也走这条分支。
        val needSections = if (bySection.keys.any { it == "cloud-backup" || it == "device-transfer" }) {
            listOf("cloud-backup", "device-transfer")
        } else {
            listOf("")
        }
        if (needSections.any { bySection[it] == null }) {
            REQUIRED_EXCLUDES
        } else {
            REQUIRED_EXCLUDES.filter { req -> bySection.values.any { req !in it } }
        }
    }.getOrDefault(REQUIRED_EXCLUDES)

    private const val TAG = "ClipboardFirewall"

    /** 剪贴板数据库文件名（Backup 规则检查用） */
    private const val DB_NAME = "jinn_clipboard.db"

    /** 用户词频文件名（明文，Backup 规则必须与库一起排除） */
    private const val FREQ_FILE = "user_freq.txt"

    /**
     * Backup 规则必须排除的路径：库本体 + WAL 侧车三件套 + 用户词频。
     *
     * 侧车文件不是可选项：SQLite 在 WAL 模式下把最近提交留在 `-wal` 里，
     * 只排除主库会让「刚复制的那批记录」随备份走。
     */
    private val REQUIRED_EXCLUDES = listOf(
        DB_NAME,
        "$DB_NAME-journal",
        "$DB_NAME-wal",
        "$DB_NAME-shm",
        FREQ_FILE,
    )

    /** 单条 su 命令超时（秒）：全盘 find 可能很慢，不能无限等待 */
    private const val SU_TIMEOUT_SEC = 10L

    /** 等待排空线程收尾的上限（毫秒）：进程已退出，排空线程随后即结束，不应久等 */
    private const val DRAIN_JOIN_MS = 200L
}