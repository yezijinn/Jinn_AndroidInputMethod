package com.jinn.inputmethod

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 增强模式：JinnIme 数据目录安全审计。
 *
 * 职责：使用 root 权限检查 JinnIme 私有数据目录的安全状态，确保剪贴板历史
 * 不被其他 APP 通过文件系统漏洞（错误权限/symlink/外部存储泄露等）读取。
 *
 * **不干预 Android System Clipboard**——网盘、购物、分享类 APP 正常读取
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
     *  - **两路都要后台排空**：只排 stderr、把 stdout 放在当前线程读，一旦 stdout 管道写满
     *    子进程就会阻塞，而父进程正等着它退出 —— 直接死锁（`find` 这类大量输出的命令必踩）；
     *  - **先 `waitFor(超时)` 再取文本**：读流若放在 `waitFor` 之前，命令不退出就会永久阻塞，
     *    超时分支永远不可达 —— 超时形同虚设（原实现即如此，见 `isRootAvailable` 的正确写法）；
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
     * 真查 Backup 配置：解析 backup_rules.xml，确认剪贴板数据库被排除。
     *
     * 原实现硬编码返回 ✓ —— 无论规则文件怎么改都显示"安全"，其实是个假检查。
     * 这里改为运行时读取实际生效的规则文件。
     */
    private fun checkBackupConfig(context: Context): Pair<String, String> {
        val excluded = runCatching {
            val parser = context.resources.getXml(R.xml.backup_rules)
            var found = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    val path = parser.getAttributeValue(null, "path").orEmpty()
                    if (path.contains(DB_NAME)) found = true
                }
                parser.next()
            }
            found
        }.getOrDefault(false)
        return "Backup 配置" to if (excluded) {
            "✓ backup_rules 已排除剪贴板数据库"
        } else {
            "✗ backup_rules 未排除剪贴板数据库（备份存在泄露风险）"
        }
    }

    private const val TAG = "ClipboardFirewall"

    /** 剪贴板数据库文件名（Backup 规则检查用） */
    private const val DB_NAME = "jinn_clipboard.db"

    /** 单条 su 命令超时（秒）：全盘 find 可能很慢，不能无限等待 */
    private const val SU_TIMEOUT_SEC = 10L

    /** 等待排空线程收尾的上限（毫秒）：进程已退出，排空线程随后即结束，不应久等 */
    private const val DRAIN_JOIN_MS = 200L
}