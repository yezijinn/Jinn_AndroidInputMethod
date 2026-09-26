package com.jinn.inputmethod

import android.content.Context
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
 * 把应用全链路运行信息写入应用专属目录，便于真机排查：
 *   - 目录：`<应用专属外部目录>/logs/`（`getExternalFilesDir`，零权限即可读写）
 *   - 按天滚动一个文件 `jinn-YYYY-MM-dd.log`，线程安全追加写
 *   - 崩溃时把 stack trace 写入日志并抓取 logcat 快照
 *   - 导出的诊断包由设置页经系统文件选择器（SAF）交给用户自选位置，全程不申请存储权限
 *   - 自动清理 7 天前的旧日志（进程常驻时也生效：落盘路径上按天闸补清）
 *
 * 所有方法幂等、线程安全、绝不让日志异常影响业务逻辑。
 */
object Diagnostics {

    const val TAG = "JinnDiag"

    private const val DIR_NAME = "JinnIme"
    private const val LOG_DIR_NAME = "logs"
    private const val KEEP_DAYS = 7L

    /** 旧日志清理的最小间隔：进程常驻时靠它在落盘路径上按月反复补清（见 [maybeCleanupOldLogs]） */
    private const val CLEANUP_INTERVAL_MS = 24 * 3600 * 1000L

    /**
     * 打包时清理旧诊断包的最小年龄。
     *
     * 上一次导出的包可能正被 SAF 复制线程读着（云盘目标上能跑好几秒），而本函数一进来就会
     * 清掉同前缀的旧包 —— 年龄闸让「刚生成的」那份不会被并发流程删掉（与配置导出侧的
     * `STALE_MIN_AGE_MS` 同一思路）。
     */
    private const val EXPORT_BUNDLE_KEEP_MS = 60 * 1000L
    private const val LOG_FILE_PREFIX = "jinn-"
    private const val LOGCAT_FILE_PREFIX = "logcat-"

    /** 导出诊断包时临时补写的设备信息（正常路径在导出结束时删除） */
    private const val DEVICE_INFO_FILE = "device-info.txt"

    /**
     * V 级（Verbose）日志是否写入文件，默认关闭。
     *
     * 本类不持有持久输出流，每条日志都是「open → write → close」。
     * V 级主要记录音频包收发这类每秒可达 10 条的高频噪音，全量落盘
     * 等于持续做小文件 IO，对输入法毫无收益。
     *
     * V 级仍会输出到 logcat（`adb logcat -s PinyinKeyboard MicRecorder` 等随时可看），
     * 需要完整落盘排查时把这里改成 true 即可，其它级别不受影响。
     */
    private const val VERBOSE_TO_FILE = false

    @Volatile
    private var logDir: File? = null

    /**
     * 应用缓存目录：logcat 快照的「原始中转件」放这里。
     *
     * 日志目录里的任何文件都会被 [exportBundle] 打进诊断包，而原始快照是**未过滤**的
     * （含本进程 V 级正文 —— 拼音串 / 候选 / 搜索词）。中转件放缓存目录，进程在
     * 「落盘 → 过滤」之间被杀时留下的也只是不外传、且系统会自行回收的缓存件。
     *
     * 赋值必须早于 [logDir]：快照以「logDir 非空」判定可用，不能出现 logDir 已可见
     * 而 cacheDir 还为空的一帧。
     */
    @Volatile
    private var cacheDir: File? = null

    @Volatile
    private var inited = false

    private val lock = Any()

    /**
     * 时间戳格式器。
     *
     * 必须用 `java.time` 而不是 `SimpleDateFormat`：后者非线程安全，而本类的
     * 日志格式化在 `log()` 里是锁外执行的（锁只保护文件追加），采集线程、
     * BackgroundIo 线程、下载线程与主线程会同时进来，共用实例会互相踩状态
     * ，表现为时间戳串号/乱码，极端情况在 `format()` 内部抛异常，把业务路径一起带崩。
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
        if (inited) {
            retryLogDirIfNeeded(context.applicationContext)
            return
        }
        synchronized(lock) {
            if (inited) return
            cacheDir = context.applicationContext.cacheDir
            logDir = resolveLogDir(context.applicationContext)
            inited = true
            installCrashHandler()
            cleanupOldLogs()
            lastCleanupAt = System.currentTimeMillis()
            i(TAG, "日志目录: ${logDir?.absolutePath ?: "不可用"}")
            i(TAG, "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
        }
    }

    /**
     * 日志目录解析失败后补一次。
     *
     * 存储未挂载时（首次解锁前 / 存储异常）`getExternalFilesDir` 返回 null，而本对象是
     * 进程级单例、IME 进程又长期存活：只在 [init] 里解析一次、失败即放弃的写法，会让这个
     * 进程此后**一条文件日志都不写**（导出诊断包恒为空），恰恰发生在最需要日志的场景。
     */
    private fun retryLogDirIfNeeded(context: Context) {
        if (logDir != null) return
        synchronized(lock) {
            if (logDir != null) return
            cacheDir = context.applicationContext.cacheDir
            val dir = resolveLogDir(context.applicationContext) ?: return
            logDir = dir
            cleanupOldLogs()
            lastCleanupAt = System.currentTimeMillis()
            i(TAG, "日志目录: ${dir.absolutePath}（延迟解析成功）")
        }
    }

    /** 日志目录：一律用应用专属外部目录（零权限），不申请共享存储写入能力 */
    private fun resolveLogDir(context: Context): File? =
        context.getExternalFilesDir(null)
            ?.let { File(it, LOG_DIR_NAME) }
            ?.also { it.mkdirs() }
            ?.takeIf { it.isDirectory }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 崩溃处理自身绝不能再抛：Thread.start()/join() 在极端情况（OOM、
                // 线程受限）会抛异常，一旦冒出 try 块就会取代原始崩溃异常继续传播，
                // 还会把 finally 的收尾语义搅乱。整段包 runCatching，失败只记一笔。
                runCatching {
                    e("Crash", "未捕获异常 @ ${thread.name}", throwable)
                    // 快照放独立线程、崩溃线程最多等 2.5s：崩溃线程（常是主线程）在
                    // 「已崩溃但进程未死」状态下长时间阻塞，会把 ANR 弹窗与 tombstone 上报
                    // 一并推迟，原实现同步等 logcat 最长 10s。dumpLogcat 内部同步写盘，
                    // 等它把文件写完（或自身 2s 超时）即可，崩溃收尾不依赖快照完成。
                    val snapshot = Thread {
                        runCatching { dumpLogcat("crash-${System.currentTimeMillis()}", waitMs = 2_000L) }
                    }
                    snapshot.isDaemon = true
                    snapshot.start()
                    snapshot.join(2_500L)
                }.onFailure {
                    // 用裸 Log 而不是 Diagnostics：此刻写日志的链路本身可能已不可靠
                    runCatching { Log.e(TAG, "崩溃处理失败（已忽略，继续走原处理器）: ${it.message}") }
                }
            } finally {
                // 崩溃本身不再拦截，交给原处理器（或直接终止），保证行为与默认一致
                if (prev != null) prev.uncaughtException(thread, throwable)
                else Runtime.getRuntime().halt(1)
            }
        }
    }

    // ── 时序事件（回溯用）─────────────────────────────────

    private val eventSeq = IntArray(1)

    /**
     * 记录一个时序事件（同时写入文件日志与 logcat）。
     * 与 [i] 的区别：带单调递增序号，回溯时能还原严格先后顺序。
     * 格式：`[EV#序号] 模块:事件 参数`
     *
     * 正文里不带时间戳：`log()` 会自己补（含线程名与 tag）。此前把带时间戳的整行
     * 传给 `log()`，落盘行出现双时间戳、模块名被挤进正文。
     * 事件行随普通日志落盘，崩溃快照（[dumpLogcat]）也会带走 logcat 里的这部分。
     *
     * 注：早先还有一个 500 条的环形缓冲 + [eventSnapshot]/[dumpEventRing]，但两者除彼此外
     * 没有任何调用方（KDoc 声称的「崩溃时落盘事件序列」从未实现），已删除，事件回溯依赖
     * 日常日志文件与 logcat 快照即可。
     */
    fun event(module: String, event: String, params: String = "") {
        val suffix = if (params.isNotEmpty()) " $params" else ""
        val n = synchronized(lock) {
            val seq = eventSeq[0] + 1
            eventSeq[0] = seq
            seq
        }
        log('I', "EVENT", "[EV#$n] $module:$event$suffix", null)
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
        maybeCleanupOldLogs()
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
     * 剔除 logcat 快照里本进程的 V 级行（纯函数，便于单测）。
     *
     * 行格式（`-v threadtime`）：`MM-DD HH:MM:SS.mmm  PID  TID V Tag: msg`；
     * 不含该前缀的行是上一条的续行（堆栈等），跟随被剔除的那条一起剔，
     * 否则被丢掉的正文会在续行里露出来。
     *
     * 只剔本进程的 V：别的进程本来就没有我们的正文，删它们只会削弱排查能力；
     * 崩溃本身是 E/F，一律保留。
     *
     * 按 `'\n'` 切分再原样 join，Kotlin 的 `split` 保留末尾空串，
     * 所以「一条都没剔」时输出与输入逐字节相同；换用 `lineSequence()` 会给
     * 以换行结尾的输入多补一个换行，测试里就是这么栽的。
     *
     * @param raw logcat 原始输出
     * @param ownPid 本进程 pid（`Process.myPid()`）；无法判定的行原样保留
     */
    internal fun filterOwnVerboseLines(raw: String, ownPid: Int): String {
        // 年份前缀可选：logcat 在条目时间戳与「当前年」不同年时会输出 `yyyy-` 前缀
        // （跨年缓冲区 / `-v threadtime` 的两种形态）。不识别它，这些行会因正则失配而
        // 沿用上一行的 drop 状态，本进程的 V 行可能被原样保留（正文进快照）。
        // 用非捕获组 `(?:…)` 包裹，保持 pid/tid/level 三个捕获组编号不变。
        val header = Regex(
            "^(?:\\d{4}-)?\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s",
        )
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
     * [suffix] 要防文件名与命令两条路：
     *  - 文件名：`File(dir, "$PREFIX$suffix.log")` 里带上 `../` 就能把写入引到日志目录之外；
     *  - 命令：原实现把整个目标路径拼进 `sh -c "logcat … > '…'"`，一个单引号即可改写命令。
     * 前者用字符白名单过滤，后者改为不经 shell 的 [ProcessBuilder] + redirectOutput，
     * 这样连白名单漏掉的字符也不可能被解释成命令。
     *
     * 另外：快照落盘前会剔掉本进程的 V 级行（见 [filterOwnVerboseLines]）。
     * V 是用户正文通道（搜索关键词 / 拼音串 / 候选词 / 测试框文本），而 logcat 缓冲区里
     * 什么级别都有，不剔就等于崩溃路径绕过了「日志禁出正文」，导出诊断包还会把它带走。
     *
     * [waitMs] 是子进程等待上限（默认 10s；崩溃路径传 2s，见 [installCrashHandler]）。
     */
    fun dumpLogcat(suffix: String = "", waitMs: Long = 10_000L): File? =
        synchronized(snapshotLock) { dumpLogcatLocked(suffix, waitMs) }

    private fun dumpLogcatLocked(suffix: String, waitMs: Long): File? {
        val dir = logDir ?: return null
        val safeSuffix = suffix.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val name = "$LOGCAT_FILE_PREFIX$safeSuffix.log"
        val dest = File(dir, name)
        // 子进程先写「原始中转件」，过滤后的内容才是产物。中转件放缓存目录而不是日志目录：
        // 日志目录里的文件会被导出诊断包整个打包，而中转件是**未过滤**的（含本进程 V 级正文）。
        // 进程在「落盘 → 过滤」之间被杀（崩溃路径只等 2s）时，留下的也只是不外传的缓存件。
        val raw = File(cacheDir ?: dir, "$name.raw")
        // 只有「过滤后的内容成功写回」才算产出：其它任何路径（进程失败、空文件、过滤为空、
        // 读取/写入抛异常、进程被杀）都要把残留文件删掉，半截快照既不可用、又没经过滤
        // （里面可能仍有本进程 V 级正文），留着等 7 天再清、或被导出诊断包带走都不行。
        // 统一收口在 finally：原实现的删除只覆盖"进程失败"一种路径，读取/过滤阶段抛异常时
        // 会把未过滤的快照留在磁盘上（正是 filterOwnVerboseLines 要防住的东西）。
        var produced = false
        try {
            // 先删同名旧件，让「文件存在 ⇔ 本次产物」成为不变量：dest / raw 都是固定名，若下面在
            // 「启动子进程」阶段就失败（logcat 不存在 / fork 失败 / OOM），文件里仍是上一次
            // 的可用产物，会被 finally 的清理一并删掉（丢的是排查材料）。先删则至多删到自己的残留。
            dest.delete()
            raw.delete()
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000")
                .redirectErrorStream(true)   // 等价原来的 2>&1
                .redirectOutput(raw)
                .start()
            val finished = process.waitFor(waitMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
            val ok = finished && process.exitValue() == 0
            if (!ok) return null
            if (!raw.exists() || raw.length() == 0L) return null
            // 落盘后再过一遍：把本进程的 V 级行（用户正文通道）剔掉。
            // 保留「先落盘、后处理」的顺序：读取管道再 waitFor 会在输出超过管道缓冲时互相等死。
            val filtered = filterOwnVerboseLines(raw.readText(), Process.myPid())
            if (filtered.isEmpty()) return null
            dest.writeText(filtered)
            produced = true
            return dest
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "logcat 快照失败: ${t.message}")
            return null
        } finally {
            // 中转件成功 / 失败都要删：它是唯一一份未过滤的 logcat
            runCatching { raw.delete() }
            if (!produced) runCatching { dest.delete() }
        }
    }

    /**
     * 上次清理时间与最小间隔。
     *
     * 「自动清理 7 天前的旧日志」原先只在 [init] 跑一次，而 IME 是常驻进程：连续存活
     * 超过 7 天就再也不会清理（日志目录无界增长、导出诊断包随之越来越大）。
     * 改为在落盘路径上按天闸补清 —— 幂等操作，多线程同时触发也无害。
     */
    @Volatile
    private var lastCleanupAt = 0L

    private fun maybeCleanupOldLogs() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return
        lastCleanupAt = now
        cleanupOldLogs()
    }

    /** 删除 KEEP_DAYS 天前的诊断日志（每日文件、logcat 快照与导出用的设备信息临时件） */
    private fun cleanupOldLogs() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000L
        dir.listFiles()?.forEach { file ->
            val name = file.name
            // device-info.txt 是导出时临时写的，正常路径在 finally 里删掉；进程中途被杀会留下，
            // 而它不匹配上面两个前缀 —— 不在这里兜底就是永久垃圾，还会混进之后每次导出包
            val ours = name.startsWith(LOG_FILE_PREFIX) ||
                name.startsWith(LOGCAT_FILE_PREFIX) ||
                name == DEVICE_INFO_FILE
            if (ours && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    val currentLogDir: File? get() = logDir

    /**
     * 缓存目录里最近一次生成的诊断包；没有则返回 null。
     *
     * 用途：导出流程横跨「应用 → 系统文件选择器 → 应用」，进程被系统回收再恢复时
     * 调用方持有的文件引用会丢，用它按最后一次生成时间兜底定位。
     * 同一次导出最多只存在一个包（[exportBundle] 生成前会清掉旧包）。
     */
    fun latestBundle(context: Context): File? =
        File(context.cacheDir, DIR_NAME)
            .listFiles()
            // 排除 `.tmp`：打包中断留下的半截件 mtime 最新，兜底会把它当成品写给用户
            // （提示还是「已导出」，用户拿着一个打不开的包去找原因）
            ?.filter { it.isFile && it.name.startsWith("jinn-diagnostics-") && !it.name.endsWith(".tmp") }
            ?.maxByOrNull { it.lastModified() }

    /** 今天的日志文件（可能尚未创建） */
    fun todayLogFile(): File? = logDir?.let { File(it, "$LOG_FILE_PREFIX${today()}.log") }

    /**
     * 快照与导出包的互斥锁。
     *
     * 两者都读写同一个日志目录，而快照是**就地写**的（删除 → 写入 → 过滤回写，非原子）：
     * 并发时同名快照会互相删掉对方的产物，导出包也可能把半截快照打进去。串行化之后，
     * 崩溃路径要多等一次进行中的导出，量级是秒级，且两者都由用户主动触发，可以接受。
     */
    private val snapshotLock = Any()

    // ── 导出诊断包 ─────────────────────────────────────────

    /**
     * 把日志目录打包成 zip，写到应用缓存目录并返回该文件（零权限）。
     *
     * 调用方（设置页）随后经系统文件选择器把内容交给用户自选位置，写完即删临时包。
     * 返回 null 表示无可导出内容或写入失败。包含：全部日志、最近的 logcat 快照、设备信息文本。
     */
    fun exportBundle(context: Context): File? =
        synchronized(snapshotLock) { exportBundleLocked(context) }

    private fun exportBundleLocked(context: Context): File? {
        val srcDir = logDir ?: return null
        // 设备信息是临时给本次导出用的：用完必须删，否则会留在日志目录里并混进之后每一次导出包
        // （进程被杀留下的残件由 [cleanupOldLogs] 按年龄兜底）
        val meta = File(srcDir, DEVICE_INFO_FILE)
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
                // 毫秒精度：秒级精度下同一秒的两次导出会同名，`dest.exists()` 的删除会撞上
                // 前一次正在复制的包
                val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)
                    .format(java.time.LocalDateTime.now())
                // 生成到缓存目录：不申请存储权限，随后由调用方（系统文件选择器）复制到用户选定位置
                val outDir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
                // 只保留本次导出：清掉上次遗留的包，避免缓存目录堆积。必须带年龄闸 ——
                // 上一次导出的包可能正被 `copyDiagZipTo` 复制（SAF 目标是云盘时能跑好几秒），
                // 无条件删会让那次复制失败，而目标文件已被先截断成 0 字节
                val nowMs = System.currentTimeMillis()
                outDir.listFiles()?.forEach {
                    if (it.name.startsWith("jinn-diagnostics-") &&
                        nowMs - it.lastModified() >= EXPORT_BUNDLE_KEEP_MS
                    ) {
                        it.delete()
                    }
                }
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
