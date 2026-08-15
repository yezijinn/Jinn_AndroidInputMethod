package com.jinn.voiceinput

import android.content.Context
import android.os.Environment
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 诊断日志系统。
 *
 * 把应用全链路运行信息写入手机内部存储，便于在真机上直接读取排查问题：
 *   - 默认目录：`/storage/emulated/0/JinnIme/logs/`
 *   - 按天滚动一个文件 `jinn-YYYY-MM-dd.log`，线程安全追加写
 *   - 崩溃时把 stack trace 写入日志并抓取 logcat 快照
 *   - 有 root（KernelSU / Magisk）时用 chown 让应用获得共享存储写权限；
 *     无 root 则退回应用专属目录（getExternalFilesDir），日志不丢
 *   - 自动清理 7 天前的旧日志
 *
 * 所有方法幂等、线程安全、绝不让日志异常影响业务逻辑。
 */
object Diagnostics {

    const val TAG = "JinnDiag"

    private const val DIR_NAME = "JinnIme"
    private const val LOG_DIR_NAME = "logs"
    private const val KEEP_DAYS = 7L
    private const val LOG_FILE_PREFIX = "jinn-"
    private const val LOGCAT_FILE_PREFIX = "logcat-"

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var inited = false

    private val lock = Any()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // ── 初始化 ──────────────────────────────────────────────

    /** 由各组件入口调用，幂等。必须在首次写日志前调用。 */
    fun init(context: Context) {
        if (inited) return
        synchronized(lock) {
            if (inited) return
            logDir = resolveLogDir(context.applicationContext)
            inited = true
            installCrashHandler()
            cleanupOldLogs()
            i(TAG, "日志目录: ${logDir?.absolutePath ?: "不可用"}")
            i(TAG, "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
        }
    }

    /** 依次尝试：共享存储直写 → root 授权 → 应用专属目录 */
    private fun resolveLogDir(context: Context): File? {
        val publicDir = File(Environment.getExternalStorageDirectory(), "$DIR_NAME/$LOG_DIR_NAME")
        if (makeWritable(publicDir)) {
            return publicDir
        }
        if (grantPublicDirViaRoot(context, publicDir)) {
            return publicDir
        }
        return context.getExternalFilesDir(null)
            ?.let { File(it, LOG_DIR_NAME) }
            ?.also { it.mkdirs() }
            ?.takeIf { it.isDirectory }
    }

    /** mkdir + 写探针验证可写 */
    private fun makeWritable(dir: File): Boolean = runCatching {
        dir.mkdirs()
        if (!dir.isDirectory) return false
        val probe = File(dir, ".probe-${Process.myPid()}")
        probe.writeText("ok")
        val writable = probe.exists() && probe.delete()
        writable
    }.getOrDefault(false)

    /**
     * 用 root 授权本应用直写共享存储：
     *  1. `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` —— scoped storage 下
     *     让应用获得"所有文件访问"能力，可直写 /storage/emulated/0/ 任意路径
     *     （需 Manifest 声明 MANAGE_EXTERNAL_STORAGE 权限）
     *  2. 兜底 chown 目标目录给本应用 uid（部分 ROM 的 FUSE 不认，仅作补充）
     */
    private fun grantPublicDirViaRoot(context: Context, dir: File): Boolean = runCatching {
        val uid = Process.myUid()
        val pkg = context.packageName
        val commands = arrayOf(
            "appops set $pkg MANAGE_EXTERNAL_STORAGE allow",
            "mkdir -p '${dir.absolutePath}' && chown $uid:$uid '${dir.absolutePath}' && chmod 700 '${dir.absolutePath}'",
        )
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", commands.joinToString(" && ")))
        val ok = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        if (ok) makeWritable(dir) else false
    }.getOrDefault(false)

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("Crash", "未捕获异常 @ ${thread.name}", throwable)
                dumpLogcat("crash-${System.currentTimeMillis()}")
            } finally {
                // 崩溃本身不再拦截，交给原处理器（或直接终止），保证行为与默认一致
                if (prev != null) prev.uncaughtException(thread, throwable)
                else Runtime.getRuntime().halt(1)
            }
        }
    }

    // ── 写日志 ──────────────────────────────────────────────

    fun v(tag: String, msg: String) = log('V', tag, msg, null)
    fun d(tag: String, msg: String) = log('D', tag, msg, null)
    fun i(tag: String, msg: String) = log('I', tag, msg, null)
    fun w(tag: String, msg: String) = log('W', tag, msg, null)
    fun e(tag: String, msg: String) = log('E', tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable?) = log('E', tag, msg, tr)

    private fun log(level: Char, tag: String, msg: String, tr: Throwable?) {
        // 同步镜像到 logcat：无文件权限时仍有 adb 可读
        when (level) {
            'V' -> Log.v(tag, msg)
            'D' -> Log.d(tag, msg)
            'I' -> Log.i(tag, msg)
            'W' -> Log.w(tag, msg)
            else -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        }
        val dir = logDir ?: return
        val sb = StringBuilder(160)
        sb.append(timeFormat.format(Date()))
            .append(' ').append(level)
            .append('/').append(tag)
            .append(" [").append(Thread.currentThread().name).append("] ")
            .append(msg).append('\n')
        tr?.let { sb.append(stackTraceOf(it)).append('\n') }
        appendToFile(dir, sb.toString())
    }

    private fun stackTraceOf(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun appendToFile(dir: File, text: String) {
        val file = File(dir, "$LOG_FILE_PREFIX${dayFormat.format(Date())}.log")
        synchronized(lock) {
            try {
                FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                Log.w(TAG, "写日志文件失败: ${e.message}")
            }
        }
    }

    // ── logcat 快照 / 清理 ─────────────────────────────────

    /**
     * 抓取当前 logcat 到日志目录，返回生成的文件；失败返回 null。
     * 无 root 时 logd 只会给出本应用进程的日志，有 root 时是系统全量。
     */
    fun dumpLogcat(suffix: String = ""): File? {
        val dir = logDir ?: return null
        val name = "$LOGCAT_FILE_PREFIX$suffix.log"
        val dest = File(dir, name)
        return runCatching {
            val cmd = "logcat -d -v threadtime -t 3000 > '${dest.absolutePath}' 2>&1"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val ok = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
            if (ok && dest.exists() && dest.length() > 0) dest else null
        }.getOrNull()
    }

    /** 删除 KEEP_DAYS 天前的诊断日志（每日文件与 logcat 快照） */
    private fun cleanupOldLogs() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000L
        dir.listFiles()?.forEach { file ->
            val name = file.name
            if ((name.startsWith(LOG_FILE_PREFIX) || name.startsWith(LOGCAT_FILE_PREFIX)) &&
                file.lastModified() < cutoff
            ) {
                runCatching { file.delete() }
            }
        }
    }

    val currentLogDir: File? get() = logDir

    /** 今天的日志文件（可能尚未创建） */
    fun todayLogFile(): File? = logDir?.let { File(it, "$LOG_FILE_PREFIX${dayFormat.format(Date())}.log") }

    // ── 导出诊断包 ─────────────────────────────────────────

    /**
     * 把日志目录打包成一个 zip 导出到共享存储（/storage/emulated/0/JinnIme/），
     * 供用户用文件管理器直接取出。返回导出文件路径；失败返回 null。
     * 包含：全部日志、最近的 logcat 快照、设备信息文本。
     */
    fun exportBundle(context: Context): File? {
        val srcDir = logDir ?: return null
        return runCatching {
            // 先补一个最新的 logcat 快照和设备信息
            val meta = File(srcDir, "device-info.txt")
            meta.writeText(
                buildString {
                    appendLine("时间: ${timeFormat.format(Date())}")
                    appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("版本: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
                    appendLine("进程: uid=${Process.myUid()} pid=${Process.myPid()}")
                }
            )
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val outDir = File(Environment.getExternalStorageDirectory(), DIR_NAME)
            outDir.mkdirs()
            val dest = File(outDir, "jinn-diagnostics-$stamp.zip")
            val files = srcDir.listFiles()?.toList().orEmpty()
            if (files.isEmpty()) return null
            java.util.zip.ZipOutputStream(FileOutputStream(dest)).use { zos ->
                for (f in files) {
                    if (!f.isFile) continue
                    zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            i(TAG, "导出诊断包: ${dest.absolutePath} (${files.size} 个文件)")
            dest
        }.getOrNull()
    }
}
