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
import kotlin.concurrent.withLock

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
    internal const val LOG_DIR_NAME = "logs"
    private const val KEEP_DAYS = 7L

/**
 * 日志目录的**体积**预算（BUG.md L-170）：**100MB**。
 *
 * 年龄闸（[KEEP_DAYS]）保证「不超 7 天」，不保证「不超多少 MB」：崩溃循环下每次崩溃都会新增一份
 * 3000 行快照（文件名带毫秒），一天就能写出几百 MB。这里补一条总量闸：超预算时按**最旧先删**
 * 我们自己的文件（当天的文件不动 —— 那是正在追加的目标）。
 *
 * 上限取 100MB 而不是更小：整份日志目录都会进导出诊断包，预算同时是「一份包最多多大」。
 * 打包侧另有 [BUNDLE_INMEM_MAX_BYTES] 限制单个文件读进内存的量 —— 预算放大后，
 * 一个跑飞的当日日志足以吃掉整个堆。
 */
private const val LOG_DIR_BUDGET_BYTES = 100L * 1024 * 1024

/**
 * 打包诊断包时单个文件读进内存的上限。
 *
 * 条目必须**先读进内存再写 zip**：边读边写时读失败会留下半截条目（见 [exportBundleLocked]）。
 * 超过本上限的文件只取**末段**并在首行标出截断 —— 排障要的是最近的事件，而整份读进来
 * 会在大日志上把进程 OOM 掉（比丢一段日志更糟）。
 */
private const val BUNDLE_INMEM_MAX_BYTES = 16L * 1024 * 1024

/**
 * 单个日志文件的上限：超了就把当日文件滚成 `jinn-<日期>-N.log`（BUG.md L-969）。
 *
 * 体积预算 [LOG_DIR_BUDGET_BYTES] 对**当日文件**是豁免的（年龄闸嫌它新、体积闸把它当保留项），
 * 于是一个跑飞的当日日志能一路长到把同分区写满。闸门因此落在写盘路径上：单文件到顶就换一段，
 * 滚出来的段落受既有的年龄闸与体积预算正常管。16MB 与 [BUNDLE_INMEM_MAX_BYTES] 同量级 ——
 * 滚出来的每一段都能整份进诊断包。
 */
private const val LOG_FILE_MAX_BYTES = 16L * 1024 * 1024

/** 同日滚动段的保留个数（当日文件 + 2 段 ≈ 48MB 封顶，再多的段由体积预算与年龄闸收走） */
private const val LOG_SEGMENTS_KEPT = 2

    /** 旧日志清理的最小间隔：进程常驻时靠它在落盘路径上按月反复补清（见 [maybeCleanupOldLogs]） */
    private const val CLEANUP_INTERVAL_MS = 24 * 3600 * 1000L

    /**
     * 清理因「目录正忙」跳过后的退避间隔（BUG.md L-972①）。
     *
     * 跳过时不推进时间闸的话，导出 / 快照占锁期间**每一条**日志都会再试一次并打一条 W ——
     * 十秒级的抓取能刷出几十条同样的告警；退避一分钟既压掉噪音，又保证导出结束后很快补上。
     */
    private const val CLEANUP_SKIP_BACKOFF_MS = 60 * 1000L

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
     * V 级主要记录音频包收发这类每秒可达 10 条的高频噪音，全量落盘对输入法毫无收益。
     * 落盘走**常驻文件句柄**（见 [streamFor]：一条日志 ≈ 一次 `write()` 进页缓存，不 fsync），
     * 成本可忽略；关掉它的理由是**让文件只留可排障的事件**：高频噪音既淹诊断包，
     * 也让「一天几百行」的现状退化成一秒十几行。
     *
     * V 级仍会输出到 logcat（`adb logcat -s PinyinKeyboard MicRecorder` 等随时可看），
     * 需要完整落盘排查时把这里改成 true 即可，其它级别不受影响。
     */
    private const val VERBOSE_TO_FILE = false

    /**
     * 按键 / 候选这类**高频热路径** V 级日志的编译期开关（跨批接口 C-2）。
     *
     * 为什么必须由**调用点**使用：Kotlin 的字符串模板在实参处就已求值 ——
     * `Diagnostics.v(TAG, "键触摸 DOWN $key")` 即使内部不落盘，字符串与拼接也已经建好，
     * 且 `Log.v` 是一次跨进程 logd 调用。只在 Diagnostics 内部判断无法省掉这两笔。
     * 正确写法（放在调用点，包住整个调用）：
     *
     * ```kotlin
     * if (Diagnostics.KEY_TRACE) Diagnostics.v(TAG, "键触摸 DOWN $key")
     * ```
     *
     * `const` 会被编译期内联；为 `false` 时整段（含字符串构造与 logd 调用）被编译器消除，零成本；
     * 需要抓现场时改成 `true` 重新编译即可。**注意**：不要用 `VERBOSE_TO_FILE` 的逻辑去掐掉
     * 整个 V 级 —— 那会把非热路径的 V 级现场（排障凭据）一起关掉，这里只关「按键级」噪音。
     */
    const val KEY_TRACE = false

    /**
     * 单条落盘正文的长度上限（字符）。
     *
     * 正文写什么由调用点自撰（约定只写事件与计数），但约定没有机械约束 ——
     * 一旦某处把剪贴板正文 / 搜索词 / 整篇文本写进 d/i/w 级日志，它会绕过 V 级闸
     * 直接落盘并随导出诊断包外传。这里在唯一的落盘入口兜住长度与常见隐私模式，
     * logcat 镜像仍输出原文，现场调试不受影响。
     */
    private const val MAX_FILE_BODY_CHARS = 512

    /** 脱敏用的掩码 */
    private const val MASK = "****"

    /**
     * 数字字符类：ASCII 之外再收**全角**（`１`）与**阿拉伯-印度数字**（`١`）。
     *
     * 本 App 自己是中文输入法，用户打得出全角数字；`\d` 只认 ASCII ⇒ 全角写的手机号 / 卡号
     * 整条漏过脱敏（BUG.md L-169）。这里只用于**号码内部**的数字位；前后边界另用逐字形版本
     * （见 [PHONE_HEAD_EDGE] / [DIGIT_TAIL_EDGE]），两者并集当边界会把混排写法整条否掉（L-971）。
     */
    private const val DIGIT = "[0-9０-９٠-٩]"

    /**
     * 手机号的**次位**：`3-9`，三种数字形态各列一份。
     *
     * 不能写成 ASCII 字面量 —— 全角写的 `１３８…` 第二位就匹配不上，整条号码照样漏过脱敏
     * （BUG.md L-169）。首位放在 [PHONE_HEAD_EDGE] 里，因为它要决定用哪一条逐字形边界。
     */
    private const val PHONE_SECOND = "[3-9３-９٣-٩]"

    /**
     * 号码里的分隔符：半角空格与连字符之外，再收**全角空格 / 全角连字符 / 短破折号**。
     *
     * 本 App 是中文输入法，用户打得出全角数字，也打得出全角分隔
     * （`１３８　１２３４　５６７８`）—— 只认半角等于漏掉这一整类（BUG.md L-968）。
     */
    private const val PHONE_SEP = "[- \u3000\uFF0D\u2013]"

    /** 可选国际前缀（含可选分隔符）：`+86` / `86` / `0086` */
    private const val PHONE_PREFIX = "(?:\\+?86$PHONE_SEP?|0086$PHONE_SEP?)?"

    /**
     * 手机号的**逐字形**前边界 + 首位（BUG.md L-971）。
     *
     * 边界原先是「三种数字形态的并集」（`(?<!$DIGIT)`）：`１13812345678` 这种「全角一位紧贴半角
     * 号码」会因为前一位是 `１` 而整条否掉 —— 号码原样落盘（放宽数字类之前用 ASCII 边界时反而是遮的，
     * 属回退）。拆成三选一分支后：**同形**数字照旧挡得住（`138123456789` 不会被只遮前 11 位），
     * 异形紧贴不再算「更长的同一个号」。前缀放进各分支里，`１+8613812345678` 也认得出。
     */
    private const val PHONE_HEAD_EDGE =
        "(?:(?<![0-9])${PHONE_PREFIX}1|(?<![０-９])${PHONE_PREFIX}１|(?<![٠-٩])${PHONE_PREFIX}١)"

    /** 逐字形前边界 + 一位任意形态数字（分组长串用，首位形态决定用哪条边界） */
    private const val DIGIT_EDGE = "(?:(?<![0-9])[0-9]|(?<![０-９])[０-９]|(?<![٠-٩])[٠-٩])"

    /**
     * 逐字形后边界：末位之后不得紧跟**同形**数字。
     *
     * 与 [PHONE_HEAD_EDGE] 成对：只看同形，`13812345678１` 这类「号码后面粘一个全角数字」仍算
     * 独立号码（遮），而 `138123456789` 是同形串，整条不匹配、留给 [LONG_DIGITS_RE] 或原样。
     */
    private const val DIGIT_TAIL_EDGE = "(?:[0-9](?![0-9])|[０-９](?![０-９])|[٠-٩](?![٠-٩]))"

    /**
     * 11 位大陆手机号（逐字形边界避免切开更长的同形数字串），带可选国际前缀。
     *
     * 分隔写法（`138-1234-5678` / `138 1234 5678`）同样要遮：从通讯录、网页、聊天记录复制时
     * 常带分隔符，只认连写形态等于漏掉一半（BUG.md L-169）。前缀不认的话，`+8613812345678`
     * 里 `1` 的前一位落在数字上，整条会被边界直接否掉（BUG.md L-968）。
     */
    private val PHONE_RE = Regex(
        "$PHONE_HEAD_EDGE$PHONE_SECOND$DIGIT(?:$PHONE_SEP?$DIGIT{4})$PHONE_SEP?$DIGIT{3}$DIGIT_TAIL_EDGE",
    )

    /**
     * 分组写法（每 4 位一组、至少 4 组）的长数字串：银行卡 / 证件号最常见的写法。
     *
     * 连续形态由 [LONG_DIGITS_RE] 兜（≥15 位），而 `6222 0212 3456 7890` 逐段都不足 15 位、
     * 头一位也不是 `1[3-9]`，两条规则都够不着（BUG.md L-968）。要求**分隔符在场**且 4 组以上，
     * 是为了不把 `2026-10-06`（两组、每组 ≤4 位）与普通计数串卷进来。
     */
    private val GROUPED_DIGITS_RE = Regex(
        "$DIGIT_EDGE$DIGIT{3}(?:$PHONE_SEP$DIGIT{4}){2,}$PHONE_SEP$DIGIT{3}$DIGIT_TAIL_EDGE",
    )

    /** 邮箱：只遮本地部分，保留域名便于辨认来源 */
    private val EMAIL_RE = Regex("[\\w.+-]+@([\\w-]+\\.[\\w.]+)")

    /** ≥15 位纯数字串（银行卡 / 证件号一类的量级）；数字类同上，含全角与阿拉伯-印度数字 */
    private val LONG_DIGITS_RE = Regex("(?<!$DIGIT)$DIGIT{15,}(?!$DIGIT)")

    /**
     * 常见 API Key / 令牌形态（2026-09-30 审查）：`sk-…`（OpenAI 系）、`…:fx`（DeepL Free）、
     * `Bearer <token>`。
     *
     * 这是**兜底**：凭据本身严禁进日志（各调用点自律 + 失败响应体只记结构化摘要）。但只要有人
     * 写下一行 `"… $url"`、或把异常 message 全文记下来，这一层就能挡住「Key 进落盘日志 →
     * 随导出诊断包外发」；整段替换、不保留片段（Key 的任意片段都有价值）。
     *
     * ⚠ 刻意**不含**「32 位纯 hex」形态：它与哈希 / 摘要同形，误伤面大（`DiagnosticsSanitizeTest`
     * 就钉着一条「十六进制串不受影响」），而凭据进日志的前提本身已被多处自律挡住 —— 精确优先。
     */
    private val API_KEY_RES = listOf(
        Regex("\\bsk-[A-Za-z0-9_\\-]{12,}"),
        Regex("[A-Za-z0-9\\-]{16,}:fx\\b"),
        Regex("(?i)\\bBearer\\s+\\S{12,}"),
        // 阿里云 AccessKeyId 固定以 `LTAI` 开头（如 `LTAI5t…`）：前缀独特、误伤面极小
        // （2026-10-02 修复 L-312 未覆盖的第三种形态）。AccessKey**Secret** 是高熵随机串，
        // 与哈希同形，仍按上面的原口径不覆盖 —— 靠调用点自律 + 失败体只记结构化摘要挡住。
        Regex("\\bLTAI[A-Za-z0-9]{8,}"),
        // URL query 里的签名 / 凭据参数形态（2026-10-03 修复 L-497）：百度系把 `appid` / `sign`
        // 直接放在 query 上（同一条 query 里的 `q` 还是**用户正文**），阿里云用 `AccessKeyId` ——
        // 脱敏表不认这些形态时，任何一处打印 URL（或异常 message 内嵌 URL）都会把签名与正文带出去。
        // 约定不变：打印一律用 `host + encodedPath`（现有调用点已是这个口径），这里再加一道网。
        Regex("(?i)[?&](sign|signature|sig|appid|app_id|accesskeyid|access_key_id|secret|secretkey|api_key|apikey|key|token|access_token|password|pwd)=[^&\\s]{4,}"),
        // 「Authorization: <token>」的**非 Bearer** 形态（部分网关用 `Authorization: <key>` 或
        // `X-Api-Key: <key>`，压根不带 Bearer 前缀）—— 原表只认 `Bearer\s+\S{12,}`（2026-10-03 修复 L-668）
        Regex("(?i)\\b(authorization|proxy-authorization|x-api-key)\\s*[:=]\\s*(?!bearer\\s)\\S{8,}"),
        // URL 的 userinfo 形态（`https://user:pass@host/`）—— 凭据藏在 URL 里，任何一处打印完整 URL 都会带走它
        Regex("(?i)https?://[^/\\s:@]+:[^/\\s@]+@"),
        )

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
     * 常驻落盘句柄（[appendToFile]）：
     * 每行日志原先要 open/write/close 三个系统调用，现在只留一次 write。
     *
     * 刻意**不做应用层缓冲**（不套 BufferedOutputStream、不排队）：日志最有用的时刻正是进程
     * 被系统杀掉或崩溃的那一刻，攒在堆里的尾巴会连原因一起丢。直写内核页缓存既拿到句柄复用，
     * 又保持「写出去就是写出去」。
     *
     * [openBytes] 是**本句柄自己**已写入的字节数（开句柄时以现有文件长度起算）：跨过单文件上限
     * 说明下一行会触发滚动，那时文件会被改名换 inode，必须先放掉旧句柄，否则后续写入落进被
     * 改名的那一份里（见 [rollDailyLogIfNeeded]）。
     */
    private var openFile: File? = null
    private var openStream: FileOutputStream? = null
    private var openBytes = 0L

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

    /**
     * 时间戳的**秒级部分**格式器。
     *
     * 整串格式（含 `.SSS`）原先是 `yyyy-MM-dd HH:mm:ss.SSS` 一次 format —— 每条日志一行也就
     * 一次模板解析 + 一个 `LocalDateTime` + 一个结果串。这里把「秒以内不变」的前半段按秒缓存，
     * 毫秒由 [now] 手拼，输出与旧格式**逐位相同**（同秒的多行各有各的毫秒，可读性不变）。
     */
    private val secondFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * `(epochSecond, 该秒的 "yyyy-MM-dd HH:mm:ss" 前缀)`。
     *
     * 必须是**单个 volatile 引用**：两个字段分别写会读到「新秒值 + 旧前缀」的错位组合；
     * 成对更换则要么整套旧的、要么整套新的。每秒钟只写一次。
     */
    @Volatile
    private var secondPrefix: Pair<Long, String>? = null

    /** 当前日期（文件名用） */
    private fun today(): String = dateFormat.format(java.time.LocalDate.now())

    /**
     * 当前时刻（日志行首用），格式 `yyyy-MM-dd HH:mm:ss.SSS`（本机时区）。
     *
     * 秒级前缀每秒只格式化一次（[secondPrefix]）；毫秒用 `ms % 1000` 手拼为三位。
     * 前缀按**同一个毫秒基准**推导（`Instant.ofEpochMilli`），避免「前缀取自下一秒、
     * 毫秒取自这一秒」的错位；`Locale.US` 与 [dateFormat] 同口径（固定 ASCII 数字，
     * 不随系统地区变成本地数字）。
     */
    private fun now(): String {
        val ms = System.currentTimeMillis()
        val second = ms / 1000
        val cached = secondPrefix
        val prefix = if (cached != null && cached.first == second) {
            cached.second
        } else {
            val p = secondFormat.format(
                java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(ms),
                    java.time.ZoneId.systemDefault(),
                ),
            )
            secondPrefix = second to p
            p
        }
        // (millis + 1000) 的十进制串去掉首位即三位补零：0→"000"、5→"005"、123→"123"
        return prefix + '.' + (ms % 1000 + 1000).toString().substring(1)
    }

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
            i(TAG, "日志目录: ${logDir?.absolutePath ?: "不可用"}")
            i(TAG, "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
            // 档位写诊断头（契约 C-3）：大缓冲上限按此折算，事后才知道某次 OOM / 拒绝大文件是不是低配档位所致
            i(TAG, "内存档位: ${DeviceTier.describe(context)}")
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
                    // 崩溃快照要能从日志里认出来（BUG-33）：诊断行头的线程名此前是 `[Thread-n]`
                    snapshot.name = "jinn-crash-logcat"
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

    /**
     * 隐私模式脱敏：手机号 / 邮箱 / 长数字串各留首尾便于对照（纯函数，便于单测）。
     * 堆栈与正文共用；只改敏感片段，不做长度处理。
     */
    internal fun redactSensitive(body: String): String {
        var s = EMAIL_RE.replace(body) { m -> MASK + "@" + m.groupValues[1] }
        // 凭据形态**先跑**（2026-10-02 修复 L-440）：数字规则会把 `LTAI0123456789012345`
        // 遮成 `LTAI0123****45`，插进去的 `****` 截断了字母数字串 ⇒ `LTAI` 规则再也匹配不上，
        // 本该整段替换的值反而露出前 4 位与末 2 位（fail-open 的方向）
        for (re in API_KEY_RES) s = re.replace(s, MASK)
        // 长数字串**先于**手机号 / 分组规则（2026-10-10 修复 L-971）：边界改成逐字形之后，手机号规则
        // 会在 `１２３４５６１３８１２３４５６７８` 这类混排串里只认后 11 位，插进去的 `****` 把整段
        // 切开 ⇒ 长数字规则再也拼不回完整的一段（前 6 位裸奔）。先整段遮掉再落到真正的 11 位号码上。
        // 代价一处：`008613812345678`（0086 + 11 位 = 15 位）现按长数字串遮（留 `0086` 与末 2 位），
        // 不再按手机号留末 4 位 —— 遮得更多，`DiagnosticsSanitizeTest` 里那条期望已随之更新。
        s = LONG_DIGITS_RE.replace(s) { m -> m.value.replaceRange(4, m.value.length - 2, MASK) }
        // 留前 3 位与末 4 位：带分隔符的号码长度不定，按**下标**切会把分隔符算进去（保留段错位），
        // 按首尾取则连写与分隔写法得到同一结果
        s = PHONE_RE.replace(s) { m -> m.value.take(3) + MASK + m.value.takeLast(4) }
        // 分组写法与连续形态不重叠（一个要求分隔符在场、一个要求全连写），谁先谁后都一样
        s = GROUPED_DIGITS_RE.replace(s) { m -> m.value.take(4) + MASK + m.value.takeLast(4) }
        return s
    }

    /**
     * 落盘正文护栏：脱敏 + **剔除换行/控制字符** + 超长截断（纯函数，便于单测）。
     * 只作用于文件，logcat 用原文。
     *
     * 为什么剔控制字符（2026-09-30 审查）：日志行以换行分界，而日志里会出现用户可控串
     * （Base URL、host、导入包里的设备名…）—— 带一个换行就能伪造出完整的假日志行（含时间戳与
     * 级别），排障时会被彻底带偏。
     */
    internal fun sanitizeForFile(body: String): String {
        // 单趟拼装：原先 `map { … }.joinToString("")` 每行要建一个装箱的 List<Char> 再加一个中间
        // String，而这是每行日志都走的路径（正文字符与结果字符 1:1，长度口径不变）
        val src = redactSensitive(body)
        val sb = StringBuilder(minOf(src.length, MAX_FILE_BODY_CHARS + 16))
        var truncated = false
        for (c in src) {
            if (sb.length >= MAX_FILE_BODY_CHARS) {
                truncated = true
                break
            }
            sb.append(
                when {
                    c == '\n' -> '⏎'
                    c == '\r' -> '␍'
                    c.isISOControl() -> ' '
                    else -> c
                },
            )
        }
        // 截断提示里报的是**原文长度**（含被截掉的部分）；未截断时两个数相等
        val total = if (truncated) src.length else sb.length
        return if (total <= MAX_FILE_BODY_CHARS) sb.toString() else sb.toString() + "…(截断，共 $total 字)"
    }

    private fun log(level: Char, tag: String, msg: String, tr: Throwable?) {
        // 同步镜像到 logcat：无文件权限时仍有 adb 可读
        when (level) {
            'V' -> Log.v(tag, msg)
            'D' -> Log.d(tag, msg)
            'I' -> Log.i(tag, msg)
            'W' -> Log.w(tag, msg)
            else -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        }
        // V 级默认不落盘：音频包这类日志每秒可达 10 条，落盘会把诊断文件淹成噪音流
        // （成本本身很低 —— 常驻句柄 + 页缓存，见 [streamFor]）；逐键级别的 V 级更是连字符串
        // 构造都在调用点用 [KEY_TRACE] 编译期消除了，不欠这两笔。
        if (level == 'V' && !VERBOSE_TO_FILE) return
        val dir = logDir ?: return
        maybeCleanupOldLogs()
        val sb = StringBuilder(160)
        sb.append(now())
            .append(' ').append(level)
            .append('/').append(tag)
            .append(" [").append(Thread.currentThread().name).append("] ")
            // 落盘走护栏（脱敏 + 截断）；logcat 上面已用原文输出
            .append(sanitizeForFile(msg)).append('\n')
        // 堆栈：脱敏 + **首行平坦化**（2026-10-02 修复）。首行是「异常类名 + message」，而 message
        // 里可能嵌用户可控串（Base URL、host、导入包里的设备名、JSON 片段）—— 带一个换行就能伪造出
        // 一条完整假日志行（含时间戳与级别），与正文那条护栏的动机**逐字相同**，此前只护了正文。
        // 帧行（`at …`）由编译器生成、不含用户内容，保持原样才可读；整体仍不截断（截断会砍掉关键帧）。
        tr?.let {
            val stack = redactSensitive(stackTraceOf(it))
            val nl = stack.indexOf('\n')
            sb.append(
                if (nl < 0) {
                    sanitizeForFile(stack)
                } else {
                    sanitizeForFile(stack.substring(0, nl)) + stack.substring(nl)
                },
            ).append('\n')
        }
        appendToFile(dir, sb.toString())
    }

    private fun stackTraceOf(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun appendToFile(dir: File, text: String) {
        synchronized(lock) {
            try {
                // 本句柄已写满一份日志 ⇒ 这一行会触发滚动（改名换 inode），先放掉旧句柄
                if (openStream != null && openBytes > LOG_FILE_MAX_BYTES) closeStream()
                val file = rollDailyLogIfNeeded(dir, today(), LOG_FILE_MAX_BYTES, LOG_SEGMENTS_KEPT)
                val bytes = text.toByteArray(Charsets.UTF_8)
                streamFor(file).write(bytes)
                openBytes += bytes.size
            } catch (e: Exception) {
                // 句柄可能已进入坏状态（磁盘满 / 文件被删）：丢掉它，下一次写自然会重开
                closeStream()
                Log.w(TAG, "写日志文件失败: ${e.message}")
            }
        }
    }

    /** 取当前可写的常驻句柄；文件换了（跨天或滚动后的新文件）就换句柄 */
    private fun streamFor(file: File): FileOutputStream {
        openStream?.let { if (openFile?.path == file.path) return it }
        closeStream()
        val stream = FileOutputStream(file, true)
        openFile = file
        openStream = stream
        openBytes = runCatching { file.length() }.getOrDefault(0L)
        return stream
    }

    private fun closeStream() {
        runCatching { openStream?.close() }
        openStream = null
        openFile = null
        openBytes = 0L
    }

    /**
     * 当日日志超 [maxBytes] 时滚动一段：改名成 `jinn-<日期>-N.log`，并只留编号最大的 [keep] 段
     * （BUG.md L-969）。返回到该写入的文件。
     *
     * 改名失败就继续往原文件追加 —— 少一次滚动好过丢这一条日志。
     *
     * 这里不取 [dirLock]：写日志在业务路径上，等一次导出会把调用方一起拖住。改名对正在读该文件的
     * 导出是安全的（读侧握的是 inode；按名字重开的那些走既有的「跳过 + 记 W」分支）。
     */
    internal fun rollDailyLogIfNeeded(dir: File, day: String, maxBytes: Long, keep: Int): File {
        val current = File(dir, "$LOG_FILE_PREFIX$day.log")
        if (!current.isFile || current.length() <= maxBytes) return current
        var n = 1
        // 编号上限只为防呆：段名撞车才往后找，正常路径一次就命中
        while (n < 1_000 && File(dir, segmentName(day, n)).exists()) n++
        val rolled = File(dir, segmentName(day, n))
        if (!runCatching { current.renameTo(rolled) }.getOrDefault(false)) return current
        Log.i(TAG, "日志文件超单文件上限，已滚动: ${rolled.name}")
        for (name in segmentsToDelete(dir.listFiles()?.map { it.name }.orEmpty(), day, keep)) {
            runCatching { File(dir, name).delete() }
        }
        return current
    }

    /** 同日滚动段的文件名：`jinn-<日期>-<编号>.log` —— 前缀与当日文件一致，年龄闸与体积闸照旧管得到 */
    private fun segmentName(day: String, n: Int) = "$LOG_FILE_PREFIX$day-$n.log"

    /**
     * 滚动后该删的同日段（纯函数，便于单测）：按编号从大到小保留 [keep] 个，其余删。
     *
     * 判据是文件名里的编号（不是 mtime —— 改名与复制会带走 mtime），编号缺口不影响顺序。
     */
    internal fun segmentsToDelete(names: List<String>, day: String, keep: Int): List<String> {
        val head = "$LOG_FILE_PREFIX$day-"
        val re = Regex("^$head(\\d+)\\.log$")
        return names
            .mapNotNull { name -> re.matchEntire(name)?.groupValues?.get(1)?.toInt()?.let { name to it } }
            .sortedByDescending { it.second }
            .drop(keep)
            .map { it.first }
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
     * @param foreignVerboseToo 缓冲可能来自**全系统**（抓取退回不带 `--pid` 的那一次）时置真：
     *   连**其它进程**的 V 级行一起剔 —— V 是各应用自由填的文本（浏览器、其它输入法都可能写用户可见内容），
     *   而这份快照会进要外传的诊断包（BUG.md L-193）。别的进程的 verbose 对本输入法的崩溃排查没有价值。
     */
    internal fun filterOwnVerboseLines(
        raw: String,
        ownPid: Int,
        foreignVerboseToo: Boolean = false,
    ): String {
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
                val pid = m.groupValues[1].toIntOrNull()
                drop = m.groupValues[3] == "V" && (pid == ownPid || (foreignVerboseToo && pid != null))
            }
            if (!drop) kept.add(line)
        }
        return kept.joinToString("\n")
    }

    // ── logcat 快照 / 清理 ─────────────────────────────────

    /**
     * 抓取当前 logcat 到日志目录，返回生成的文件；失败返回 null。
     *
     * **来源与覆盖面**（实现见 [dumpLogcatLocked]；这段此前写的是更早的形态，BUG.md L-202）：
     *  - **默认**带 `--pid=<本进程>` 抓：本机开发设备是 root，不带这条的话 logcat 会给**全系统**的行，
     *    而这份快照会被打进**要外传的**诊断包；
     *  - 按 pid 抓**失败**、或抓回**空文件**时，退回不带 `--pid` 的全量再抓一次（BUG.md L-188 / L-196）——
     *    宁可多抓一次，也别把「抓不到」当成「没有日志」；
     *  - 落盘前剔 V 级行（见 [filterOwnVerboseLines]）：只抓本进程时剔本进程的，退回全量时**连其它进程的
     *    V 级一起剔** —— V 是各应用自由填的文本（浏览器、其它输入法都可能写用户可见内容）；
     *  - 首行写明这次的来源（`pid` / `full(含其它进程，V 级已剔)`），单个文件就能看出覆盖面
     *    （BUG.md L-194）。
     *
     * [suffix] 要防文件名与命令两条路：
     *  - 文件名：`File(dir, "$PREFIX$suffix.log")` 里带上 `../` 就能把写入引到日志目录之外；
     *  - 命令：原实现把整个目标路径拼进 `sh -c "logcat … > '…'"`，一个单引号即可改写命令。
     * 前者用字符白名单过滤，后者改为不经 shell 的 [ProcessBuilder] + redirectOutput，
     * 这样连白名单漏掉的字符也不可能被解释成命令。
     *
     * 为什么要剔 V 级（也解释了上面第三条的动机）：V 是用户正文通道（搜索关键词 / 拼音串 / 候选词 /
     * 测试框文本），而 logcat 缓冲区里什么级别都有，不剔就等于崩溃路径绕过了「日志禁出正文」，
     * 导出诊断包还会把它带走。
     *
     * [waitMs] 是子进程等待上限（默认 10s；崩溃路径传 2s，见 [installCrashHandler]）。
     */
    fun dumpLogcat(suffix: String = "", waitMs: Long = 10_000L): File? =
        withDirLockBusy { dumpLogcatLocked(suffix, waitMs) }

    /**
     * 跑一次 logcat（不经 shell，见 [dumpLogcat] 的命令注入说明）；返回退出码。
     *
     * 未在 [waitMs] 内结束（已 destroy）或抛异常都返回 **-1** —— 调用方按「这次没成功」处理
     * （BUG.md L-188：失败要有退路，也要有痕迹）。
     */
    private fun runLogcat(args: List<String>, out: File, waitMs: Long): Int = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(out).start()
        val finished = process.waitFor(waitMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            return@runCatching -1
        }
        process.exitValue()
    }.getOrDefault(-1)

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
            // `--pid` 只取本进程：本机开发设备是 root，不加这一条 logcat 会给**全系统**的行
            // （旧 KDoc 曾写「有 root 时是系统全量」，2026-10-09 已按实现同步，见 BUG.md L-202），
            // 而这份快照会被打进**要外传的**诊断包 ⇒ 等于把别家的日志一起交出去（BUG.md L-169）。
            // 代价说清：系统侧（lowmemorykiller 之类）的旁证不再进包，排查这类问题要看 `dumpsys`。
            // 先按「只取本进程」抓；**失败就退回不带 `--pid`** 再抓一次（BUG.md L-188）：
            // 隐私优先是默认，但不能因为一个参数不被支持（裁剪 ROM / 旧 toybox / 将来行为变化）
            // 就丢掉整份现场 —— 原先的 `if (!ok) return null` 正是这样，而且那条路径连日志都没有。
            // 退回时抓的是全系统（那份快照会被脱敏，但**含其它进程的行**）⇒ 必须留一条 W 说明。
            val baseArgs = listOf("logcat", "-d", "-v", "threadtime", "-t", "3000")
            var fullDevice = false
            var code = runLogcat(baseArgs + "--pid=${Process.myPid()}", raw, waitMs)
            // 「成功但为空」同样需要退回：崩溃刚发生过，本进程至少有一条 E 级（崩溃本身），
            // 按 pid 抓到空是反常信号（logd 缓冲区异常 / 权限收紧都可能），而空文件对排查无用
            // ⇒ 丢弃前先试一次全量（BUG.md L-196）。
            val emptyAfterPid = code == 0 && (!raw.exists() || raw.length() == 0L)
            if (code != 0 || emptyAfterPid) {
                fullDevice = true
                Diagnostics.w(
                    TAG,
                    if (emptyAfterPid) "logcat 快照: 按 pid 抓取为空，退回全量再试一次"
                    else "logcat 快照: 按 pid 抓取失败（exit=$code），退回全量再试一次",
                )
                raw.delete()
                code = runLogcat(baseArgs, raw, waitMs)
            }
            if (code != 0) {
                Diagnostics.w(TAG, "logcat 快照失败: exit=$code（两次都未成功）")
                return null
            }
            if (!raw.exists() || raw.length() == 0L) {
                Diagnostics.w(TAG, "logcat 快照为空（本次未产出内容）")
                return null
            }
            // 落盘后再过一遍：把本进程的 V 级行（用户正文通道）剔掉。
            // 保留「先落盘、后处理」的顺序：读取管道再 waitFor 会在输出超过管道缓冲时互相等死。
            val filtered = filterOwnVerboseLines(raw.readText(), Process.myPid(), foreignVerboseToo = fullDevice)
            if (filtered.isEmpty()) return null
            // 与日文件同一句脱敏（BUG.md L-169：原先这条通道整体绕过护栏）。**不做截断** ——
            // `sanitizeForFile` 的截断是按「一条消息」设计的，套到整份快照上会把堆栈拦腰砍断。
            val safe = redactSensitive(filtered)
            // 首行自证来源：单个文件就能看出「这次是不是全量」，不必回头翻日文件里的那条 W（BUG.md L-194）。
            // 也标出 V 级行的处理口径，避免读者误以为「没有 V 行 = 一定只抓了本进程」。
            val label = if (fullDevice) "full(含其它进程，V 级已剔)" else "pid"
            dest.writeText("# snapshot source=$label\n" + safe)
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
        if (System.currentTimeMillis() - lastCleanupAt < CLEANUP_INTERVAL_MS) return
        // 兜底：清理是尽力而为，异常冒到写日志的业务调用点就违背了类 KDoc 的「绝不让日志异常
        // 影响业务逻辑」（BUG.md L-973）。裸 Log 记异常类名 —— 走 Diagnostics 会再进一次清理判定。
        runCatching { cleanupOldLogs() }.onFailure {
            runCatching { Log.w(TAG, "日志清理失败: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * 跳过一轮后该写回的「上次清理时间」（纯函数，便于单测）。
     *
     * 目标是让下一次尝试落在 `now + retryMs`：既不立刻重试（导出 / 快照期间的每一条日志都会来问
     * 一次），也不等满一个 [intervalMs]（导出结束后要很快补上这一轮）。存进去的值 + [intervalMs]
     * 正好等于 `now + retryMs`。
     */
    internal fun cleanupRetryAt(now: Long, intervalMs: Long, retryMs: Long): Long =
        now - intervalMs + retryMs

    /**
     * 体积清理的结果文案（纯函数，便于单测）。
     *
     * 条数按**成功删除**计：删除可能整批失败（文件被占用 / 权限被撤），此时按候选数报「已删 N 个」
     * 与事实相反（BUG.md L-972③）。失败个数分开说，别把它混进释放量里。
     */
    internal fun cleanupResultMessage(target: Int, failed: Int, freedBytes: Long): String {
        val deleted = target - failed
        val head = "日志体积超预算，已按最旧先删 $deleted 个（释放 ${freedBytes / 1024}KB）"
        return if (failed > 0) "$head，$failed 个删除失败" else head
    }

    /**
     * 超体积预算时该删哪些文件（纯函数，便于单测）：跳过 [keepName]，最旧先删，删到预算内为止。
     *
     * @param entries `(文件名, 修改时间, 字节数)`，顺序无关（内部按时间升序）
     */
    internal fun overBudgetVictims(
        entries: List<Triple<String, Long, Long>>,
        budget: Long,
        keepName: String?,
    ): List<String> {
        var total = entries.sumOf { it.third }
        if (total <= budget) return emptyList()
        val victims = ArrayList<String>()
        for (e in entries.sortedBy { it.second }) {
            if (total <= budget) break
            if (e.first == keepName) continue
            victims.add(e.first)
            total -= e.third
        }
        return victims
    }

    /**
     * 删除 KEEP_DAYS 天前的诊断日志（每日文件、logcat 快照与导出用的设备信息临时件），
     * 之后按体积预算再裁一遍。
     *
     * 全程持 [dirLock]（BUG.md L-174），但**拿不到就跳过**这一轮：本函数在写日志的路径上被调用，
     * 等一次进行中的导出/快照会把业务调用一起拖住。下面三种情况都跳过：目录正忙（[dirBusy]）、
     * 锁被别的线程拿着、以及快照 / 导出在自己内部重入（那时 `tryLock` 会成功）。跳过一律做一次
     * [CLEANUP_SKIP_BACKOFF_MS] 的短退避（BUG.md L-972①）—— 不推进闸的话，占锁期间每条日志
     * 都会重试一次并打一条 W。
     */
    private fun cleanupOldLogs() {
        val dir = logDir ?: return
        // 先看 busy 再看锁：快照 / 导出自己写日志时会**重入**本函数，那时 tryLock 照样成功，
        // 只靠锁挡不住它们（BUG.md L-972②）
        if (dirBusy) {
            skipCleanupRound("诊断包正在读写日志目录")
            return
        }
        if (!dirLock.tryLock()) {
            skipCleanupRound("诊断包正在读写日志目录")
            return
        }
        try {
            // 先推进时间闸：下面的日志调用会重进 [maybeCleanupOldLogs]，不推进就会递归
            lastCleanupAt = System.currentTimeMillis()
            cleanupOldLogsLocked(dir)
        } finally {
            dirLock.unlock()
        }
    }

    /** 跳过这一轮并做一次短退避（BUG.md L-972①）：裸 Log，走 Diagnostics 会再进一次清理判定 */
    private fun skipCleanupRound(reason: String) {
        lastCleanupAt = cleanupRetryAt(
            System.currentTimeMillis(), CLEANUP_INTERVAL_MS, CLEANUP_SKIP_BACKOFF_MS,
        )
        runCatching { Log.w(TAG, "日志清理跳过: $reason") }
    }

    private fun cleanupOldLogsLocked(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000L
        // 年龄闸整段兜异常：`listFiles()` 自身会抛（目录权限被撤 / 不可读），原先只兜住了
        // `delete()`（BUG.md L-973）。走裸 Log，异常类名足够定位，不带路径。
        runCatching {
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
        }.onFailure {
            runCatching { Log.w(TAG, "日志年龄清理失败: ${it.javaClass.simpleName}") }
        }
        // 年龄闸之后再过一遍**体积**闸（BUG.md L-170）：崩溃循环能在一天内写出几百 MB，
        // 只按年龄删拦不住「当天写满」——那时导出会失败、同分区别的应用也跟着遭殃。
        runCatching {
            val nowName = "$LOG_FILE_PREFIX${today()}.log"
            val entries = dir.listFiles()
                ?.filter { it.isFile && (
                    it.name.startsWith(LOG_FILE_PREFIX) || it.name.startsWith(LOGCAT_FILE_PREFIX) ||
                        it.name == DEVICE_INFO_FILE) }
                ?.map { Triple(it.name, it.lastModified(), it.length()) }
                .orEmpty()
            val victims = overBudgetVictims(entries, LOG_DIR_BUDGET_BYTES, nowName)
            if (victims.isNotEmpty()) {
                var freed = 0L
                var failed = 0
                for (v in victims) {
                    val f = File(dir, v)
                    val len = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) freed += len else failed++
                }
                Diagnostics.i(TAG, cleanupResultMessage(victims.size, failed, freed))
            }
        }.onFailure {
            // 清理是尽力而为，不该让日志写入路径因它抛异常；但也**不能静默**
            // （仓库自定底线：空 onFailure 等于吞异常，见 BUG.md L-189）。只记异常类名。
            Diagnostics.w(TAG, "日志体积清理失败: ${it.javaClass.simpleName}")
        }
    }

    /** 当前日志目录；尚未初始化（或未授权）时为 null */
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
     * 日志目录的读写互斥锁：**快照、导出包与旧日志清理**共用这一把（BUG.md L-174）。
     *
     * 三者碰的是同一批文件：快照是**就地写**的（删除 → 写入 → 过滤回写，非原子），导出正在逐个读，
     * 而清理正在删。只把前两者串起来时，清理能在导出中途删掉文件（少打几个文件，最坏是把刚写好的
     * 设备信息删掉）、也能在快照的「删旧 → 写新」之间插进来。
     *
     * 用可重入锁而不是 `synchronized`：清理走 [ReentrantLock.tryLock]，拿不到就**跳过这一轮**
     * —— 它由写日志的路径调用，等一次十秒级的 logcat 抓取会把业务调用一起拖住；快照与导出则照旧
     * 等待（都要完整跑完，崩溃路径多等一次进行中的导出，量级是秒级，可以接受）。
     *
     * 锁序：持本锁时才会去拿日志追加用的 `lock`（本对象内部的日志调用），反向没有 —— 清理拿不到锁
     * 就直接返回，不在持 `lock` 时等待本锁。
     */
    private val dirLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * 「日志目录正被整段流程占用」标志：快照 / 导出包在持锁期间置真，清理见到就整轮跳过。
     *
     * 为什么不能只靠 [dirLock]：它是**可重入**的，而快照与导出内部自己会写日志 —— 那些日志调用
     * 进清理判定时再 `tryLock` 照样成功，于是清理会在导出**进行中**删文件（最坏把刚写好的
     * `device-info.txt` 删掉，包内缺设备信息；BUG.md L-972②）。标志只在持锁的临界区里改，
     * 与锁同生命周期。
     */
    @Volatile
    private var dirBusy = false

    /**
     * 持 [dirLock] 跑一段读写日志目录的流程（快照 / 导出包），期间 [cleanupOldLogs] 整轮跳过。
     *
     * 与直接 `dirLock.withLock { … }` 的区别只有 [dirBusy] 的置位与复位 —— 复位放 `finally`，
     * 流程抛异常也不会把标志留在原地（留下就等于**永久**停掉清理）。
     */
    private inline fun <T> withDirLockBusy(block: () -> T): T = dirLock.withLock {
        dirBusy = true
        try {
            block()
        } finally {
            dirBusy = false
        }
    }

    /**
     * 读一个文件用于打包：返回 null 表示读不出来（调用方跳过它并留一条 W）。
     *
     * 不超过 [BUNDLE_INMEM_MAX_BYTES] 时整份读进内存 —— 条目必须先读后写，边读边写时读失败会留下
     * 半截条目。超过上限只取**末段**并在首行标出截断（预算 100MB 下，一个跑飞的当日日志足以在
     * 整份读入时把进程 OOM 掉，而排障要的本来就是最近的事件）。
     */
    private fun readForBundle(f: File): ByteArray? {
        val len = f.length()
        if (len <= BUNDLE_INMEM_MAX_BYTES) return runCatching { f.readBytes() }.getOrNull()
        val mark = (
            "[诊断包] 本文件 ${len / 1024}KB 超过打包上限 ${BUNDLE_INMEM_MAX_BYTES / 1024 / 1024}MB，" +
                "仅保留末段（末段自中间某行开始）\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(len - BUNDLE_INMEM_MAX_BYTES)
                ByteArray(BUNDLE_INMEM_MAX_BYTES.toInt()).also { buf -> raf.readFully(buf) }
            }
        }.getOrNull() ?: return null
        return mark + tail
    }

    // ── 导出诊断包 ─────────────────────────────────────────

    /**
     * 把日志目录打包成 zip，写到应用缓存目录并返回该文件（零权限）。
     *
     * 调用方（设置页）随后经系统文件选择器把内容交给用户自选位置，写完即删临时包。
     * 返回 null 表示无可导出内容或写入失败。包含：全部日志、最近的 logcat 快照、设备信息文本。
     */
    fun exportBundle(context: Context): File? =
        withDirLockBusy { exportBundleLocked(context) }

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
                var written = 0
                java.util.zip.ZipOutputStream(FileOutputStream(t)).use { zos ->
                    for (f in files) {
                        if (!f.isFile) continue
                        // 先把内容读进内存再建条目：读失败（文件正被清理删掉 —— BUG.md L-170/L-174
                        // 的两条通道）时**跳过这一个**，而不是留下半截 zip 或让整包失败。
                        // 单个文件的**上限**见 [BUNDLE_INMEM_MAX_BYTES]：超限只打末段。
                        val bytes = readForBundle(f)
                        if (bytes == null) {
                            Diagnostics.w(TAG, "导出诊断包: 跳过读不出的文件 ${f.name}")
                            continue
                        }
                        zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                        zos.write(bytes)
                        zos.closeEntry()
                        written++
                    }
                }
                // 一个条目都没写进去时**不要交出空包**（2026-10-02 修复 L-449）：此前只看 `renameTo`
                // 成功就记「导出成功」并经 SAF 交给用户 —— 用户把空包发给维护者，双方都要多一轮往返
                // 才知道里面什么都没有（日志里的 `files.size` 还是目录条目数，看着更像成功了）。
                if (written == 0) {
                    Diagnostics.w(TAG, "导出诊断包: 所有文件都读不出（目录条目 ${files.size} 个），放弃导出")
                    return@runCatching null
                }
                if (dest.exists()) dest.delete()
                if (!t.renameTo(dest)) {
                    Diagnostics.w(TAG, "导出诊断包: 改名失败 ${dest.absolutePath}")
                    return@runCatching null
                }
                // 记**实际写入**的条目数（此前是目录条目数 `files.size`：有文件被跳过时数字偏大，
                // 排障时看不出来 —— 2026-10-02 修复 L-449）
                i(TAG, "导出诊断包: ${dest.absolutePath} ($written 个文件)")
                dest
            }.getOrNull()
        } finally {
            meta.delete()
            tmp?.delete()
        }
    }
}
