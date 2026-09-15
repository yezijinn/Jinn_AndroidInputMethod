package com.jinn.inputmethod

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 增强模式：JinnIme 数据目录安全审计（方案第十一节）。
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
     * 两处必要防护（原实现都没有）：
     *  - **并发排空 stderr**：只读 stdout 时，一旦 stderr 管道写满子进程就会阻塞，
     *    find 这类可能大量输出的命令会直接挂死；
     *  - **超时销毁进程**：全盘 find 在极端情况下可达数十秒，不能无限等待。
     */
    private fun su(cmd: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        try {
            val errDrain = Thread {
                runCatching { process.errorStream.bufferedReader().forEachLine { } }
            }.apply { isDaemon = true; start() }
            val text = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                Diagnostics.w(TAG, "su 超时（${SU_TIMEOUT_SEC}s），已销毁进程")
                errDrain.join(200)
                return@runCatching null
            }
            text
        } finally {
            runCatching { process.destroy() }
        }
    }.getOrNull()

    private fun checkDirPerm(dataDir: String): Pair<String, String> {
        val out = su("ls -ld $dataDir") ?: return "数据目录权限" to "无法访问（su 失败）"
        val ok = out.startsWith("drwx------")
        return "数据目录权限" to if (ok) "✓ $out" else "✗ 期望 drwx------，实际: $out"
    }

    private fun checkDbPerm(dbPath: String): Pair<String, String> {
        if (!File(dbPath).exists()) return "数据库权限" to "✓ 数据库不存在（无历史记录，安全）"
        val out = su("ls -l $dbPath") ?: return "数据库权限" to "无法访问（su 失败）"
        val ok = out.startsWith("-rw-------")
        return "数据库权限" to if (ok) "✓ $out" else "✗ 期望 -rw-------，实际: $out"
    }

    private fun checkOwner(dataDir: String): Pair<String, String> {
        val out = su("stat -c '%U' $dataDir") ?: return "数据目录 Owner" to "无法访问（su 失败）"
        val ok = out.startsWith("u0_a") || out.startsWith("app_")
        return "数据目录 Owner" to if (ok) "✓ $out" else "✗ 非 App 用户: $out"
    }

    private fun checkSelinux(): Pair<String, String> {
        val out = su("getenforce") ?: return "SELinux 状态" to "无法检查（su 失败）"
        val ok = out.equals("Enforcing", ignoreCase = true)
        return "SELinux 状态" to if (ok) "✓ $out" else "△ $out（非强制模式，Sandbox 保护减弱）"
    }

    private fun checkSymlink(dataDir: String): Pair<String, String> {
        val out = su("find $dataDir -type l 2>/dev/null") ?: return "符号链接" to "无法检查（su 失败）"
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
     * 原实现硬编码返回 ✓ —— 无论规则文件被改成什么样都显示"安全"，属于假检查。
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
}