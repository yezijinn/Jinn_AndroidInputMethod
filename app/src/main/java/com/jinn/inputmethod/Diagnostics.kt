package com.jinn.inputmethod

import android.content.Context
import android.os.Environment
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
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

    /**
     * V 级（Verbose）日志是否写入文件，默认关闭。
     *
     * 本类不持有持久输出流，每条日志都是「open → write → close」。
     * V 级主要记录音频包收发这类**每秒可达 10 条**的高频噪音，全量落盘
     * 等于持续做小文件 IO，对输入法毫无收益。
     *
     * V 级仍会输出到 logcat（`adb logcat -s PinyinKeyboard MicRecorder` 等随时可看），
     * 需要完整落盘排查时把这里改成 true 即可，其它级别不受影响。
     */
    private const val VERBOSE_TO_FILE = false

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var inited = false

    private val lock = Any()

    /**
     * 时间戳格式器。
     *
     * 必须用 `java.time` 而不是 `SimpleDateFormat`：后者**非线程安全**，而本类的
     * 日志格式化在 `log()` 里是**锁外**执行的（锁只保护文件追加），采集线程、
     * BackgroundIo 线程、下载线程与主线程会同时进来，共用实例会互相踩状态
     * ——表现为时间戳串号/乱码，极端情况在 `format()` 内部抛异常，把业务路径一起带崩。
     * `DateTimeFormatter` 不可变、天然线程安全（minSdk 26 已支持 java.time）。
     */
    private val dateFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val timeFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 当前日期（文件名用） */
    private fun today(): String = dateFormat.format(java.time.LocalDate.now())

    /** 当前时刻（日志行首用） */
    private fun now(): String = timeFormat.format(java.time.LocalDateTime.now())

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
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        // 超时必须销毁：`su` 等用户点授权时 waitFor 会一直超时，不销毁就留下一个挂起进程
        // 和它的三个管道句柄（同文件的 [dumpLogcat] 与 ClipboardFirewall.su 都是这么收尾的）。
        if (!finished) {
            process.destroy()
            Diagnostics.w(TAG, "root 授权超时（5s），已销毁 su 进程")
        }
        val ok = finished && process.exitValue() == 0
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

    // ── 事件序列 Ring Buffer（时序 BUG 定位核心）────────────────

    /**
     * 最近事件环形缓冲：保留最近 [EVENT_RING_SIZE] 个带时间戳的事件。
     * 时序类 BUG（如 IME 收起/唤醒循环）发生后，即使没有实时看 logcat，
     * 也能在日志里回溯 BUG 前几秒的完整事件序列。
     * 线程安全：所有访问走 synchronized。
     */
    private const val EVENT_RING_SIZE = 500
    private val eventRing = ArrayDeque<String>(EVENT_RING_SIZE)
    private val eventSeq = IntArray(1)

    /**
     * 记录一个时序事件（同时写入文件日志与环形缓冲）。
     * 与 [i] 的区别：带单调递增序号，回溯时能还原严格先后顺序。
     * 格式：`[EV#序号] 模块:事件 参数`
     */
    fun event(module: String, event: String, params: String = "") {
        val seq = synchronized(lock) {
            val n = eventSeq[0] + 1
            eventSeq[0] = n
            val line = "${now()} [EV#$n] $module:$event${if (params.isNotEmpty()) " $params" else ""}"
            eventRing.addLast(line)
            if (eventRing.size > EVENT_RING_SIZE) eventRing.removeFirst()
            line
        }
        log('I', "EVENT", seq, null)
    }

    /** 返回最近的事件序列（新→旧或旧→新由 reversed 控制），用于崩溃/异常时落盘 */
    fun eventSnapshot(reversed: Boolean = true): List<String> = synchronized(lock) {
        val list = eventRing.toList()
        if (reversed) list.asReversed() else list
    }

    /** 把事件序列写入日志文件（调试时手动触发，如诊断快照） */
    fun dumpEventRing() {
        val lines = eventSnapshot()
        if (lines.isEmpty()) return
        val sb = StringBuilder("── 最近事件序列（新→旧，共 ${lines.size} 条）──\n")
        for (l in lines) sb.append(l).append('\n')
        appendToFile(logDir ?: return, sb.toString())
    }

    // ── Trace ID（一次操作的完整调用链）──────────────────────

    private val traceSeq = IntArray(1)

    /**
     * 生成一次操作的 Trace ID，例如 `CLIP-8F31`。
     * 一次用户操作（打开剪贴板/开始听写/切换键盘）生成一个 ID，
     * 后续所有相关日志携带同一 ID，从日志里搜 ID 即可还原完整调用链。
     * @param module 模块前缀，如 CLIP / IME / ASR / PINYIN
     */
    fun traceId(module: String): String {
        val n = synchronized(lock) {
            traceSeq[0] += 1
            traceSeq[0]
        }
        val rand = (0x1000 + kotlin.random.Random.nextInt(0xEFFF)).toString(16).uppercase()
        return "$module-${(n % 0xFFFF).toString(16).uppercase().padStart(4, '0')}-$rand"
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
        // V 级默认不落盘：音频包这类日志每秒可达 10 条，而本类不持有持久流，
        // 每次写日志都要 open/write/close 一次文件，累积开销不小（见 VERBOSE_TO_FILE）。
        if (level == 'V' && !VERBOSE_TO_FILE) return
        val dir = logDir ?: return
        val sb = StringBuilder(160)
        sb.append(now())
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
        val file = File(dir, "$LOG_FILE_PREFIX${today()}.log")
        synchronized(lock) {
            try {
                FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                Log.w(TAG, "写日志文件失败: ${e.message}")
            }
        }
    }

    /**
     * 剔除 logcat 快照里**本进程的 V 级行**（**纯函数**，便于单测）。
     *
     * 行格式（`-v threadtime`）：`MM-DD HH:MM:SS.mmm  PID  TID V Tag: msg`；
     * 不含该前缀的行是上一条的续行（堆栈等），**跟随被剔除的那条一起剔**，
     * 否则被丢掉的正文会在续行里露出来。
     *
     * 只剔本进程的 V：别的进程本来就没有我们的正文，删它们只会削弱排查能力；
     * 崩溃本身是 E/F，一律保留。
     *
     * 注意：按 `'\n'` 切分再原样 join —— Kotlin 的 `split` **保留末尾空串**，
     * 所以「一条都没剔」时输出与输入逐字节相同；换用 `lineSequence()` 会给
     * 以换行结尾的输入多补一个换行，测试里就是这么栽的。
     *
     * @param raw logcat 原始输出
     * @param ownPid 本进程 pid（`Process.myPid()`）；无法判定的行原样保留
     */
    internal fun filterOwnVerboseLines(raw: String, ownPid: Int): String {
        val header = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s")
        val lines = raw.split('\n')
        val kept = ArrayList<String>(lines.size)
        var drop = false
        for (line in lines) {
            val m = header.find(line)
            if (m != null) {
                drop = m.groupValues[3] == "V" && m.groupValues[1].toIntOrNull() == ownPid
            }
            if (!drop) kept.add(line)
        }
        return kept.joinToString("\n")
    }

    // ── logcat 快照 / 清理 ─────────────────────────────────

    /**
     * 抓取当前 logcat 到日志目录，返回生成的文件；失败返回 null。
     * 无 root 时 logd 只会给出本应用进程的日志，有 root 时是系统全量。
     *
     * [suffix] 是本方法唯一的可变入参，两条路都要防：
     *  - **文件名**：`File(dir, "$PREFIX$suffix.log")` 里带上 `../` 就能把写入引到日志目录之外；
     *  - **命令**：原实现把整个目标路径拼进 `sh -c "logcat … > '…'"`，一个单引号即可改写命令。
     * 前者用字符白名单过滤，后者改为**不经 shell** 的 [ProcessBuilder] + redirectOutput——
     * 这样连白名单漏掉的字符也不可能被解释成命令。
     *
     * 另外：快照落盘前会剔掉**本进程的 V 级行**（见 [filterOwnVerboseLines]）。
     * V 是用户正文通道（搜索关键词 / 拼音串 / 候选词 / 测试框文本），而 logcat 缓冲区里
     * 什么级别都有 —— 不剔就等于崩溃路径绕过了「日志禁出正文」，导出诊断包还会把它带走。
     */
    fun dumpLogcat(suffix: String = ""): File? {
        val dir = logDir ?: return null
        val safeSuffix = suffix.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val name = "$LOGCAT_FILE_PREFIX$safeSuffix.log"
        val dest = File(dir, name)
        return runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000")
                .redirectErrorStream(true)   // 等价原来的 2>&1
                .redirectOutput(dest)
                .start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            val ok = finished && process.exitValue() == 0
            if (!ok) {
                // 抓取失败的半截快照既不可用、也没过隐私过滤（里面可能仍有 V 级正文）：
                // 直接删掉 —— 留着等 7 天再清、或被导出诊断包带走都不行。
                dest.delete()
                return@runCatching null
            }
            if (!dest.exists() || dest.length() == 0L) return@runCatching null
            // 落盘后再过一遍：把本进程的 V 级行（用户正文通道）剔掉。
            // 保留「先落盘、后处理」的顺序：读取管道再 waitFor 会在输出超过管道缓冲时互相等死。
            val filtered = filterOwnVerboseLines(dest.readText(), Process.myPid())
            if (filtered.isEmpty()) {
                dest.delete()
                return@runCatching null
            }
            dest.writeText(filtered)
            dest
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
    fun todayLogFile(): File? = logDir?.let { File(it, "$LOG_FILE_PREFIX${today()}.log") }

    // ── 导出诊断包 ─────────────────────────────────────────

    /**
     * 把日志目录打包成一个 zip 导出到共享存储（/storage/emulated/0/JinnIme/），
     * 供用户用文件管理器直接取出。返回导出文件路径；失败返回 null。
     * 包含：全部日志、最近的 logcat 快照、设备信息文本。
     */
    fun exportBundle(context: Context): File? {
        val srcDir = logDir ?: return null
        // 设备信息是**临时**给本次导出用的：用完必须删 —— 它会永远留在日志目录里
        // （[cleanupOldLogs] 只按 jinn- / logcat- 前缀清理），并混进之后每一次导出包。
        val meta = File(srcDir, "device-info.txt")
        var tmp: File? = null
        try {
            return runCatching {
                // 先补一份最新的设备信息
                meta.writeText(
                    buildString {
                        appendLine("时间: ${now()}")
                        appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                        appendLine("版本: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
                        appendLine("进程: uid=${Process.myUid()} pid=${Process.myPid()}")
                    }
                )
                val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
                    .format(java.time.LocalDateTime.now())
                val outDir = File(Environment.getExternalStorageDirectory(), DIR_NAME)
                outDir.mkdirs()
                val dest = File(outDir, "jinn-diagnostics-$stamp.zip")
                val files = srcDir.listFiles()?.toList().orEmpty()
                if (files.isEmpty()) return@runCatching null
                // 原子写：先写临时文件再改名。导出包是要交给别人排查的，半截 zip（进程被杀 /
                // 并发导出撞同一路径）等于白导；临时名带纳秒，两次导出各写各的、改名原子生效。
                val t = File(outDir, "jinn-diagnostics-$stamp-${System.nanoTime()}.zip.tmp")
                tmp = t
                java.util.zip.ZipOutputStream(FileOutputStream(t)).use { zos ->
                    for (f in files) {
                        if (!f.isFile) continue
                        zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                if (dest.exists()) dest.delete()
                if (!t.renameTo(dest)) {
                    Diagnostics.w(TAG, "导出诊断包: 改名失败 ${dest.absolutePath}")
                    return@runCatching null
                }
                i(TAG, "导出诊断包: ${dest.absolutePath} (${files.size} 个文件)")
                dest
            }.getOrNull()
        } finally {
            meta.delete()
            tmp?.delete()
        }
    }
}
