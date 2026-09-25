package com.jinn.inputmethod

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 配置备份包的读写与落地（Android 侧）。
 *
 * 两层结构（**外层永远加密**）：
 *  1. 外层 = [ConfigCrypto] 的加密壳（`.jinn` 文件：magic/版本/salt/IV + AES-256-GCM 密文），
 *     密码由用户在导出时自设、导入时输入，本机不保存；
 *  2. 内层 = zip（格式定义见 [ConfigBackup]）：manifest / prefs / 词频 / 可选剪贴板明文 / 可选词库。
 *
 * 为什么整包加密：包里有剪贴板明文（身份证、手机号、地址、银行卡、账号口令都会出现），
 * 落到网盘或聊天记录就等于泄露；连 manifest 里的设备信息也不该明文暴露。
 *
 * 三个入口（导出 / 解锁 / 导入）都做 DB、解密与文件 IO，**必须在后台线程调用**；
 * PBKDF2 按 OWASP 量级取 600k 迭代，手机上一次约 1~2 秒，正是要放到后台的原因。
 */
internal object ConfigBackupManager {

    /** 与类名一致（全项目惯例；`ConfigBackup` 对象本身不写日志，故不会撞标签） */
    private const val TAG = "ConfigBackupManager"

    /** 与 [Diagnostics] 共用缓存目录（条目前缀不同，互不干扰清理） */
    private const val CACHE_DIR = "JinnIme"

    /** 词频文件名（与 [UserFrequency] 一致） */
    private const val USER_FREQ_FILE = "user_freq.txt"

    /** 可选词库目录名（与 [PinyinEngine] 一致） */
    private const val DICT_DIR = "dicts"

    /** 加密备份文件后缀（不是 zip：整包被加密壳包住） */
    const val FILE_SUFFIX = ".jinn"

    /** 解密到缓存目录的临时 zip 前缀（导入流程用完即删） */
    private const val DECRYPT_PREFIX = "jinn-import-"

    /** 词库恢复的中间件后缀（`<文件名>.restore`，校验通过才改名成正式文件） */
    private const val RESTORE_SUFFIX = ".restore"

    /** 单个文本节读入内存的上限：超出即视为不可用，宁可拒绝也不硬撑（String 常驻是字节数的 2 倍） */
    internal const val MAX_TEXT_SECTION_BYTES = 16L * 1024 * 1024

    /**
     * 导出侧的单节字节闸：读入上限留一成余量（序列化的字段名与转义还会让结果再涨一点）。
     *
     * 提成常量而不是就地算 `* 9 / 10`：比例只此一处，测试也直接断言它，
     * 避免「代码改了比例、测试里复刻的算式没跟着改」这种假绿。
     */
    internal const val EXPORT_SECTION_LIMIT_BYTES = MAX_TEXT_SECTION_BYTES * 9 / 10

    /**
     * 剪贴板明文导出的字节预算（UTF-8，按内容算）。
     *
     * 刻意比 [MAX_TEXT_SECTION_BYTES]（读入上限）低一档：序列化成 jsonl 后还要加上字段名与转义，
     * 若两边取同一个数，接近上限的包会在导入端被判「节不可用」而**整包拒绝** —— 用户刚导出的
     * 备份自己导不回来。注意转义最坏可达 2 倍（换行/引号密集的文本），所以预算不是硬保证：
     * 序列化结果一旦越过单节上限，导出会**中止并说明原因**（`lastError`），不会静默丢数据。
     */
    internal const val CLIP_EXPORT_BYTE_BUDGET = 12L * 1024 * 1024

    /** 剪贴板导出/预览的分页步长 */
    private const val CLIP_PAGE = 200

    // 以下几组上限与 [readSection] / [zipEntryNames] 一样对测试可见（internal）：
    // 「打包 → 读回 → 校验」是主路径，此前只能靠真机人工验证（ADB 打不了中文密码）

    /** 加密包体积上限：配置包不该有几百 MB，误选大文件时在拷进缓存之前就拦住 */
    internal const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024

    /** 词库节：条目数与**总量**上限（旧写法按单文件放行，16 个条目最多能先写 1GB 再校验，磁盘会被写满） */
    internal const val MAX_DICT_FILES = 16
    internal const val MAX_DICT_BYTES = 64L * 1024 * 1024

    /** [zipEntryNames] 的收集上限：见该函数说明（zip 本地头很小，无闸就是 OOM 通道） */
    internal const val MAX_ZIP_ENTRIES = 512

    /**
     * [readSections] / [hasDictEntry] 的**遍历**条目数上限，与 [MAX_ZIP_ENTRIES] 不是一回事。
     *
     * 收集上限防的是内存（只留前 N 个名字）；遍历上限防的是空转 —— 零字节条目一次 `read`
     * 即 EOF、不吃 [ScanBudget] 的预算，缺这道闸时数百万条目的包能把按名查找拖到分钟级。
     * 取值必须大于收集上限：词库条目允许排在 [MAX_ZIP_ENTRIES] 之后，存在性判定仍要发现它
     * （见 [hasDictEntry]）。4096 容得下该形态，同时把对抗包限制在毫秒级的条目解析上。
     */
    internal const val MAX_SCAN_ENTRIES = 4096

    /**
     * 一次检查/导入允许在「按名查找」上消耗的解压后字节总量（见 [ScanBudget]）。
     *
     * 合法包一次操作最多需要约 300MB（三节各 ≤16MB + 词库 ≤64MB，且每个节都要从包首重新顺序扫一遍），
     * 取 512MB 留一倍余量；超限整包拒收 —— 宁可明确失败，也不能让一份对抗包把导入卡成假死。
     */
    internal const val MAX_SCAN_INFLATED_BYTES = 512L * 1024 * 1024

    /**
     * 从 SAF 读取加密包的时长上限。
     *
     * 进度框不可取消（把「取消」做成可中断要改动导出/解密/写入三处的状态机），
     * 所以这里必须有时间预算：否则一个极慢的恶意 provider 就能让设置页
     * （与输入法同进程）长时间无响应，用户只能杀进程。
     */
    private const val UNLOCK_READ_BUDGET_MS = 90_000L

    /** 读取头部允许的连续「空读」次数（`InputStream.read` 允许返回 0，见 [unlockLocked]） */
    private const val MAX_EMPTY_READS = 64

    /**
     * 认定「可以清理」的最小年龄。
     *
     * 主保险是 [tempInFlight]（本进程正在用的文件不删），这里只兜住两类：漏登记的中间件、
     * 以及崩溃后重启时的残留（那时集合是空的，60 秒后即可回收）。
     */
    private const val STALE_MIN_AGE_MS = 60 * 1000L

    /**
     * 本进程正在使用的临时件名（导出的 zip / 解密产物）。
     *
     * 清理跑在全进程共享的后台执行器上、可能被前序任务推迟到流程进行中才执行，
     * 而导出/解密用的正是同一批文件名 —— 只按名字与时间判断会误删正在用的文件，
     * 用户看到的是「导入失败，包已损坏」这种完全不沾边的原因。
     */
    private val tempInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 上一次导出失败的可读原因（null = 没有已知的具体原因）。
     *
     * 存在的意义：导出失败的原因对用户是有价值的信息（「剪贴板历史过大」与「磁盘满」的处置
     * 完全不同），而 `export` 只返回 null。UI 在拿到 null 时读它，为空则退回通用文案。
     */
    @Volatile
    var lastError: String? = null
        private set

    /** 导出互斥（见 [export]）：进程级，跨 Activity 重建也有效 */
    private val exportInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 导入互斥（见 [import]）：同为进程级，页面重建后的新实例也拦得住 */
    private val importInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 是否已有导入在进行中：页面重建后导入按钮会重新可点，据此拒绝第二次 */
    val importing: Boolean get() = importInFlight.get()

    /** 占住导入位；返回 false = 已有导入在跑（见 [import]） */
    internal fun beginImport(): Boolean = importInFlight.compareAndSet(false, true)

    /** 释放导入位（与 [beginImport] 成对，异常路径也必须走到） */
    internal fun endImport() {
        importInFlight.set(false)
    }

    /**
     * 导入时给系统文件选择器的 MIME 过滤。
     *
     * 加密壳不是标准类型，多数文件管理器会把它判成 `application/octet-stream`，
     * 也有判成 `application/zip`（后缀曾用过 .zip）的；两种都列上，但不写全匹配通配——
     * 那等于不过滤，用户很容易误选一个无关文件。
     */
    val OPEN_MIME_TYPES = arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed")

    /**
     * 缓存目录里最近一次生成的加密备份包；没有则返回 null。
     *
     * 用途与诊断包同理：导出流程横跨「应用 → 文件选择器 → 应用」，进程被系统回收再恢复时
     * 调用方持有的文件引用会丢，用它按最后修改时间兜底定位（同一次导出只存在一个包）。
     */
    fun latestBundle(context: Context): File? =
        File(context.cacheDir, CACHE_DIR)
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith(ConfigBackup.FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }

    /**
     * 清理上次异常中断留下的**明文**中间件（导出用的 `.zip.plain`、导入解密出的 `jinn-import-*`、
     * 以及各类 `.zip.tmp`）。
     *
     * 为什么需要它：进程被杀（OOM / 用户划掉）不会跑任何收尾代码，明文 zip 会一直躺在缓存目录里；
     * 而原来的自愈只发生在「下一次导出/导入」时，用户若不再用该功能就永不清。设置页启动时调用一次，
     * 让残留最多只活到下一次打开设置页。**不动** `jinn-config-*.jinn`（那是待交给用户的加密包，
     * 由 [latestBundle] 兜底定位）。
     */
    fun cleanupStaleCache(context: Context) {
        val dir = File(context.cacheDir, CACHE_DIR)
        val now = System.currentTimeMillis()
        for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile || tempInFlight.contains(f.name)) continue
            val name = f.name
            // 只认本流程的中间件（配置包前缀 / 解密前缀）：裸 `.zip.tmp` 会命中「导出诊断包」的
            // 临时件，把它连带清掉（那是另一条流程正在写的文件）
            val stale = name.startsWith(DECRYPT_PREFIX) ||
                (name.startsWith(ConfigBackup.FILE_PREFIX) &&
                    (name.endsWith(".plain") || name.endsWith(".zip.tmp") || name.endsWith(".tmp")))
            if (stale && now - f.lastModified() >= STALE_MIN_AGE_MS && f.delete()) {
                Diagnostics.i(TAG, "清理残留中间文件: $name")
            }
        }
        // 词库恢复的中间件（`*.xz.restore`）在 filesDir：正常路径会自己改名或删除，
        // 只有进程被杀才残留，顺手收掉（该目录只有词库文件，不会误伤）
        val dictDir = File(context.filesDir, DICT_DIR)
        dictDir.listFiles()?.forEach { f ->
            if (!f.isFile || !f.name.endsWith(".restore")) return@forEach
            // 正在写的中间件不删：in-flight 集合是主保险，年龄只是「漏登记/跨进程残留」的兜底
            if (tempInFlight.contains(f.name)) return@forEach
            if (now - f.lastModified() < STALE_MIN_AGE_MS) return@forEach
            // 删除失败也要留痕：清理逻辑静默失败的话，残留会永远堆着而没人知道
            if (f.delete()) {
                Diagnostics.i(TAG, "清理词库恢复中间件: ${f.name}")
            } else {
                Diagnostics.w(
                    TAG,
                    "词库恢复中间件删除失败: ${f.name} 目录可写=${dictDir.canWrite()}",
                )
            }
        }
    }

    // ── 导出 ────────────────────────────────────────────────

    class ExportOptions(
        val includeClipboard: Boolean,
        val includeDicts: Boolean,
        /** 备份密码（用户自设，只能用汉字；本机不保存）。用后由调用方清零 */
        val password: CharArray,
    )

    class ExportOutcome(
        val file: File,
        /** 未导出的剪贴板条数（0 = 全部导出）；要求见 [collectClipboard]：条数上限/单条上限/字节预算三类 */
        val clipDropped: Int,
    )

    fun export(context: Context, options: ExportOptions): ExportOutcome? {
        lastError = null
        // 进程级互斥：页面被重建后，旧实例的导出线程可能仍在跑，而导出一开始就会清掉同前缀的
        // 遗留包（见 [exportLocked] 内）——两次并行必然互相删中间件，让后来者明确失败更安全
        if (!exportInFlight.compareAndSet(false, true)) {
            lastError = "已有一次导出正在进行，请稍候再试"
            Diagnostics.w(TAG, "导出中止: 上一次导出仍在进行")
            return null
        }
        try {
            return exportLocked(context, options)
        } finally {
            exportInFlight.set(false)
            // 前缀批量注销：互斥保证同前缀只有一个流程，不必逐个记名字
            tempInFlight.removeIf { it.startsWith(ConfigBackup.FILE_PREFIX) }
        }
    }

    private fun exportLocked(context: Context, options: ExportOptions): ExportOutcome? {
        val prefs = Prefs(context)
        val clipPrefs = ClipboardPrefs.of(context)

        val prefsText = ConfigBackup.encodePrefs(
            linkedMapOf(
                ConfigBackup.PREFS_MAIN to prefs.exportForBackup(),
                ConfigBackup.PREFS_CLIPBOARD to clipPrefs.exportForBackup(),
            )
        )
        // 词频是「内存态经 2 秒防抖落盘」的：不先 flush 就会漏掉窗口内的最后一次学习
        // （导入侧在同一处显式 flush，导出侧此前漏了）
        runCatching { UserFrequency.flush() }
        val freqText = runCatching {
            File(context.filesDir, USER_FREQ_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull().orEmpty()
        val clip = if (options.includeClipboard) collectClipboard(context) else null
        val dicts = if (options.includeDicts) dictFiles(context) else emptyList()
        // 词库与导入侧同一组上限：超了必然导不回来（导入端限 16 文件 / 64MB），宁可在这里说清
        if (dicts.size > MAX_DICT_FILES) {
            lastError = "已下载词库过多（${dicts.size} 个，备份包最多 $MAX_DICT_FILES 个）：" +
                "请取消勾选「包含已下载词库」"
            Diagnostics.w(TAG, "导出中止: 词库文件过多 ${dicts.size}")
            return null
        }
        val dictTotalBytes = dicts.sumOf { it.length() }
        if (dictTotalBytes > MAX_DICT_BYTES) {
            lastError = "已下载词库过大（${dictTotalBytes / 1024 / 1024}MB，上限 " +
                "${MAX_DICT_BYTES / 1024 / 1024}MB）：请取消勾选「包含已下载词库」"
            Diagnostics.w(TAG, "导出中止: 词库总量过大 $dictTotalBytes")
            return null
        }

        // 字节闸：词频 / prefs / 剪贴板都必须在备份包单节上限之内，超限一律**中止导出并说清原因**。
        // 绝不静默省略 —— 省略等于「用户以为备份完整，换机后这部分数据全丢」，是这类功能最坏的失败方式
        // （导入侧对超限节的处理也是拒绝整包，所以超限包连自己都导不回来）。留一成余量是因为
        // jsonl 的字段名与字符串转义还会让落盘体积再涨一点。
        // 三节各只做**一次** UTF-8 转换，字节数组在「字节闸 → 摘要 → 写 zip」之间复用：
        // 12MB 的剪贴板节此前要被转换 4 次（约 48MB 瞬时垃圾），现在只留 1 份
        val sectionLimit = EXPORT_SECTION_LIMIT_BYTES
        val freqData = freqText.toByteArray(Charsets.UTF_8)
        if (freqText.isNotEmpty() && freqData.size.toLong() > sectionLimit) {
            lastError = "用户词频文件过大（${freqData.size / 1024 / 1024}MB），超出备份包单节上限，无法导出"
            Diagnostics.w(TAG, "导出中止: 词频节过大 ${freqData.size}")
            return null
        }
        val prefsData = prefsText.toByteArray(Charsets.UTF_8)
        if (prefsData.size.toLong() > sectionLimit) {
            lastError = "设置项内容过大（${prefsData.size / 1024 / 1024}MB，通常是有超长的语音提示词），无法导出"
            Diagnostics.w(TAG, "导出中止: prefs 节过大 ${prefsData.size}")
            return null
        }
        val clipSection = clip?.takeIf { it.text.isNotEmpty() }
        val clipData = clipSection?.text?.toByteArray(Charsets.UTF_8)
        if (clipData != null && clipData.size.toLong() > sectionLimit) {
            lastError = "剪贴板历史过大（${clipData.size / 1024 / 1024}MB）：请取消勾选「包含剪贴板历史」，" +
                "或先在剪贴板面板里清理条目后重试"
            Diagnostics.w(TAG, "导出中止: 剪贴板节过大 ${clipData.size}")
            return null
        }

        val sections = LinkedHashMap<String, ConfigBackup.Section>()
        sections[ConfigBackup.SEC_PREFS] = sectionOfBytes(prefsData)
        if (freqText.isNotEmpty()) sections[ConfigBackup.SEC_USER_FREQ] = sectionOfBytes(freqData)
        clipData?.let { sections[ConfigBackup.SEC_CLIPBOARD] = sectionOfBytes(it) }
        if (dicts.isNotEmpty()) {
            val perFile = dicts.map { it.name to fileSha256(it) }
            sections[ConfigBackup.SEC_DICTS] = ConfigBackup.Section(
                sha256 = dictsDigest(perFile),
                bytes = dicts.sumOf { it.length() },
            )
        }

        // 精确到毫秒：秒级精度下「同一秒内两次导出」会算出同一个文件名，
        // 后一次的前置删除会删掉前一次正在复制的包
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)
            .format(java.time.LocalDateTime.now())
        val outDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        // 只保留本次导出：清掉上次遗留的包（前缀与诊断包不同，不会互相误删）。
        // 年龄闸不可省：上一次导出的包可能正被 `copyConfigZipTo` 的复制线程读着
        // （云盘类 SAF 目标能跑好几秒），无条件删会让那次复制失败，而目标文件已被
        // `openTruncatingOutput` 先截断成 0 字节（与诊断包侧同一处修正）
        val nowMs = System.currentTimeMillis()
        outDir.listFiles()?.forEach {
            if (it.isFile && it.name.startsWith(ConfigBackup.FILE_PREFIX) &&
                nowMs - it.lastModified() >= STALE_MIN_AGE_MS
            ) {
                it.delete()
            }
        }
        val plainZip = File(outDir, ConfigBackup.FILE_PREFIX + stamp + ".zip.plain")
        val dest = File(outDir, ConfigBackup.FILE_PREFIX + stamp + FILE_SUFFIX)
        tempInFlight.add(plainZip.name)
        tempInFlight.add(dest.name)
        val manifestData = ConfigBackup.encodeManifest(
            ConfigBackup.Manifest(
                formatVersion = ConfigBackup.FORMAT_VERSION,
                appVersionCode = BuildConfig.VERSION_CODE,
                createdAt = System.currentTimeMillis(),
                device = deviceLabel(),
                sections = sections,
            )
        ).toByteArray(Charsets.UTF_8)
        try {
            ZipOutputStream(FileOutputStream(plainZip)).use { zos ->
                // manifest 放**第一个**：摘要在打包前就算好了，与写入顺序无关；而读取端每次
                // 按名查找都要从 zip 头顺序解压（ZipInputStream 不能跳转）—— 放最后意味着
                // 「扫一遍 manifest」要把全部节解压丢弃一次，一次导入要扫它两遍（最坏包近百 MB）
                putBytes(zos, ConfigBackup.ENTRY_MANIFEST, manifestData)
                putBytes(zos, ConfigBackup.ENTRY_PREFS, prefsData)
                if (freqText.isNotEmpty()) putBytes(zos, ConfigBackup.ENTRY_USER_FREQ, freqData)
                clipData?.let { putBytes(zos, ConfigBackup.ENTRY_CLIPBOARD, it) }
                for (f in dicts) putFile(zos, ConfigBackup.DICT_DIR + f.name, f)
            }
        } catch (t: Throwable) {
            plainZip.delete()
            Diagnostics.w(TAG, "导出失败（打包）: ${t.message}")
            return null
        }

        // 立即加密并删掉明文中间件：明文 zip 一刻都不该在磁盘上多留
        val encrypted = ConfigCrypto.encrypt(plainZip, dest, options.password)
        plainZip.delete()
        if (!encrypted) {
            dest.delete()
            Diagnostics.w(TAG, "导出失败（加密）")
            return null
        }

        // 与导入端的 256MB 硬限对齐：自产的包必须自己导得回来（否则用户拿到的是「读不到文件」）
        if (dest.length() > MAX_PACKAGE_BYTES) {
            dest.delete()
            lastError = "备份包超过 ${MAX_PACKAGE_BYTES / 1024 / 1024}MB 上限（多为词库过大）：" +
                "请取消勾选词库后重试"
            Diagnostics.w(TAG, "导出中止: 包体过大 ${dest.length()}")
            return null
        }

        // 统计只服务这一行日志：此前会对词频做全量解析、对 12MB 字符串逐字符数行（纯白开销），
        // 现在只做行数统计（惰性、无中间对象）
        Diagnostics.i(
            TAG,
            "导出完成: ${dest.name} 节=${sections.keys} 词频=${lineCount(freqText)} 条 " +
                "剪贴板=${clipSection?.let { s -> lineCount(s.text) } ?: 0} 条 词库=${dicts.size} 个 " +
                "共 ${dest.length()} 字节（已加密）",
        )
        return ExportOutcome(dest, clip?.dropped ?: 0)
    }

    private class ClipPayload(val text: String, val dropped: Int)

    private fun collectClipboard(context: Context): ClipPayload {
        val db = ClipboardDb.get(context)
        val entries = ArrayList<ConfigBackup.ClipEntry>()
        var bytes = 0L
        var dropped = 0
        var offset = 0
        var taken = 0
        while (true) {
            val page = db.recentPageWithOffset(offset, CLIP_PAGE, null, false)
            // 以「游标有没有前进」判结束：若用 `items.size < limit`，本页只要有解密失败的行
            // （密钥轮换 / 单行损坏）就会被误判成「已经到底」，其后所有更旧的条目被静默漏导
            // （nextOffset 是扫描过的原始行数标准，items 是解密成功的条数，两者不能混用）
            if (page.nextOffset <= offset) break
            for (item in page.items) {
                // 与导入侧一致的条数上限：导入端只收前 N 条，多带的会在那边被静默截断
                if (taken >= ConfigBackup.MAX_CLIP_IMPORT_ITEMS) {
                    dropped++
                    continue
                }
                // 与导入侧一致的单条上限：超限条目导入时必然被丢掉，带上它只会造成
                // 「导出说有、导入却没有」的不对称（用户无从诊断）
                if (ClipboardStore.exceedsItemLimit(item.content)) {
                    dropped++
                    continue
                }
                // 按 UTF-8 明文字节算预算：String.length 是 UTF-16 字符数，中文下会低估到 1/3
                val size = item.content.toByteArray(Charsets.UTF_8).size.toLong()
                if (bytes + size > CLIP_EXPORT_BYTE_BUDGET) {
                    dropped++
                    continue
                }
                bytes += size
                taken++
                entries.add(
                    ConfigBackup.ClipEntry(
                        content = item.content,
                        createdAt = item.createdAt,
                        sourcePackage = item.sourcePackage,
                        sourceAppName = item.sourceAppName,
                        category = item.category,
                        favorite = item.isFavorite,
                    )
                )
            }
            offset = page.nextOffset
        }
        return ClipPayload(ConfigBackup.encodeClipboard(entries), dropped)
    }

    private fun dictFiles(context: Context): List<File> =
        File(context.filesDir, DICT_DIR).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xz") }
            ?.sortedBy { it.name }
            .orEmpty()

    // ── 解锁（解密到缓存 zip） ───────────────────────────────

    /**
     * 解锁结果。
     *
     * 必须把「读不到」与「解不开」分开：把「文件已移走 / 授权失效 / 选错文件」也报成
     * 「密码错误或文件已损坏」，用户会以为密码记错了而反复重输，真正的原因却被掩盖。
     * 「解不开」内部仍不区分密码错与内容被改 —— 那是安全取舍（见 [ConfigCrypto.decrypt]）。
     */
    sealed interface UnlockResult {
        class Ok(val zip: File) : UnlockResult
        object ReadFailed : UnlockResult

        /** 读取超时（预算见 [UNLOCK_READ_BUDGET_MS]）：与「读不到」分开，否则慢速网盘会被说成文件失效 */
        object ReadTimeout : UnlockResult
        object DecryptFailed : UnlockResult
    }

    /**
     * 用密码把选中的备份文件解密成缓存目录里的临时 zip。
     *
     * 解密前先清掉上一次导入遗留的临时 zip（只清超过时间窗的，避免误删并发流程正在用的那份）：
     * 跨对话框 / Activity 重建都能自愈，不会让明文 zip 在缓存目录里越堆越多。
     */
    fun unlock(context: Context, uri: Uri, password: CharArray): UnlockResult {
        try {
            return unlockLocked(context, uri, password)
        } finally {
            tempInFlight.removeIf { it.startsWith(DECRYPT_PREFIX) }
        }
    }

    private fun unlockLocked(context: Context, uri: Uri, password: CharArray): UnlockResult {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach {
            // 只清「空闲很久」且没在用的残留（in-flight 集合是主保险）
            if (it.isFile && !tempInFlight.contains(it.name) &&
                it.name.startsWith(DECRYPT_PREFIX) &&
                now - it.lastModified() >= STALE_MIN_AGE_MS
            ) {
                it.delete()
            }
        }
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
            .format(java.time.LocalDateTime.now())
        val dest = File(dir, DECRYPT_PREFIX + stamp + "-" + System.nanoTime() + ".zip")
        val staged = File(dir, DECRYPT_PREFIX + "in-" + System.nanoTime() + FILE_SUFFIX)
        tempInFlight.add(dest.name)
        tempInFlight.add(staged.name)
        // 一次流读做三件事：认 magic（顺手挡住误选的大文件，不认就不往下拷）、边拷边限流、
        // 拷完交给解密。先整份拷贝再校验格式，会让缓存被一个误选的 GB 级文件写满。
        var readTimedOut = false
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use fileStream@ { input ->
                // 计时从**第一个字节**开始：头几个字节与后面的正文共用同一个预算 ——
                // 否则一个每次只给 1 字节、间隔很久的恶意 provider 能把握手拖到无限长
                val startedAt = System.currentTimeMillis()
                val head = ByteArray(ConfigCrypto.MAGIC.length)
                var read = 0
                var emptyReads = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) break
                    // 允许 read 返回 0（非阻塞流/恶意 provider）：不处理就是空转死循环
                    if (n == 0) {
                        if (++emptyReads > MAX_EMPTY_READS) {
                            Diagnostics.w(TAG, "解锁: 读取头部无进展，已放弃")
                            return@fileStream false
                        }
                        continue
                    }
                    emptyReads = 0
                    read += n
                    if (System.currentTimeMillis() - startedAt > UNLOCK_READ_BUDGET_MS) {
                        readTimedOut = true
                        Diagnostics.w(TAG, "解锁: 读取头部超时")
                        return@fileStream false
                    }
                }
                if (read < head.size || String(head, Charsets.US_ASCII) != ConfigCrypto.MAGIC) {
                    Diagnostics.w(TAG, "解锁: 不是加密备份包（magic 不符）")
                    return@fileStream false
                }
                var total = head.size.toLong()
                staged.outputStream().buffered(64 * 1024).use { out ->
                    out.write(head)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PACKAGE_BYTES) {
                            Diagnostics.w(TAG, "解锁: 所选文件超过 $MAX_PACKAGE_BYTES 字节上限")
                            return@fileStream false
                        }
                        // 恶意/故障 provider 可以返回极慢的流，而这次的进度框不可取消 ——
                        // 没有时间预算就等于把界面（与同进程的输入法）交给对方处置
                        if (System.currentTimeMillis() - startedAt > UNLOCK_READ_BUDGET_MS) {
                            readTimedOut = true
                            Diagnostics.w(TAG, "解锁: 读取超时（超过 $UNLOCK_READ_BUDGET_MS ms）")
                            return@fileStream false
                        }
                        out.write(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!ok) {
            staged.delete()
            if (readTimedOut) {
                Diagnostics.w(TAG, "解锁: 读取超时（预算 $UNLOCK_READ_BUDGET_MS ms）")
                return UnlockResult.ReadTimeout
            }
            Diagnostics.w(TAG, "解锁: 无法读取所选文件（已移走/授权失效/不是本程序的包/超过体积上限）")
            return UnlockResult.ReadFailed
        }
        val decrypted = ConfigCrypto.decrypt(staged, dest, password)
        staged.delete()
        if (!decrypted) {
            dest.delete()
            return UnlockResult.DecryptFailed
        }
        Diagnostics.i(TAG, "解锁成功: ${dest.name} (${dest.length()} 字节)")
        return UnlockResult.Ok(dest)
    }

    // ── 检查（导入前预览） ───────────────────────────────────

    class BackupInfo(
        val manifest: ConfigBackup.Manifest,
        val prefsKeys: Int,
        val freqCount: Int,
        val clipCount: Int,
        val dictNames: List<String>,
        /** 读不出来的节（超过单节读入上限）：导入端会拒绝整包，界面要提前说清 */
        val oversizedSections: List<String>,
    ) {
        /** 包由更新版 App 生成：可查看，但拒绝导入（新格式可能有本版不认识的语义） */
        val tooNew: Boolean
            get() = ConfigBackup.checkVersion(manifest.formatVersion) == ConfigBackup.VersionCompat.TOO_NEW

        /** 包内有过大的节：同样不许导入（否则用户白输一次密码才失败） */
        val hasOversized: Boolean get() = oversizedSections.isNotEmpty()
    }

    fun inspect(zip: File): BackupInfo? {
        val budget = ScanBudget()
        // 四个节一次遍历读完（见 [readSections]）：按名查找要从包首顺序扫，分开查等于把包反复解压
        val sections = readSections(
            zip,
            listOf(
                ConfigBackup.ENTRY_MANIFEST,
                ConfigBackup.ENTRY_PREFS,
                ConfigBackup.ENTRY_USER_FREQ,
                ConfigBackup.ENTRY_CLIPBOARD,
            ),
            budget = budget,
        )
        val manifestText = (sections[ConfigBackup.ENTRY_MANIFEST] as? SectionRead.Ok)?.text ?: return null
        val manifest = ConfigBackup.parseManifest(manifestText) ?: run {
            Diagnostics.w(TAG, "检查: manifest 无法识别（不是本程序导出的包）")
            return null
        }
        // 三态读取：超限的节要单独标出来（若当成「没有」，界面会显示 0 条，用户以为包里没内容）
        val oversized = ArrayList<String>()
        fun textOf(entry: String, section: String): String? =
            when (val r = sections[entry] ?: SectionRead.Missing) {
                is SectionRead.Ok -> r.text
                SectionRead.TooLarge, SectionRead.Failed -> {
                    oversized.add(section)
                    null
                }
                SectionRead.Missing -> null
            }

        val prefsKeys = textOf(ConfigBackup.ENTRY_PREFS, ConfigBackup.SEC_PREFS)
            ?.let { text -> ConfigBackup.decodePrefs(text).values.sumOf { it.size } } ?: 0
        val freqCount = textOf(ConfigBackup.ENTRY_USER_FREQ, ConfigBackup.SEC_USER_FREQ)
            ?.let { UserFrequency.countEntries(it) } ?: 0
        val clipCount = textOf(ConfigBackup.ENTRY_CLIPBOARD, ConfigBackup.SEC_CLIPBOARD)
            ?.let { ConfigBackup.decodeClipboard(it).size } ?: 0
        val dictNames = zipEntryNames(zip, budget = budget)
            .filter { it.startsWith(ConfigBackup.DICT_DIR) }
            .map { it.removePrefix(ConfigBackup.DICT_DIR) }
            .filter { it.isNotEmpty() }
        // 预算耗尽时上面的段数已经不可信（词库名可能只数到一半），按「包不可用」处理
        if (budget.exhausted) {
            Diagnostics.w(TAG, "检查: 包内解压量超过预算，按不可用处理")
            return null
        }
        return BackupInfo(manifest, prefsKeys, freqCount, clipCount, dictNames, oversized)
    }

    // ── 导入 ────────────────────────────────────────────────

    /** 覆盖还原 = 用备份覆盖全部设置；仅并入数据 = 只合并词频/剪贴板（词库按勾选照常恢复），不动本机设置 */
    enum class ImportMode { RESTORE, MERGE_DATA }

    class ImportOutcome(
        val prefsApplied: Int,
        val prefsIgnored: Int,
        val freqBefore: Int,
        val freqAfter: Int,
        val clipAdded: Int,
        val clipSkipped: Int,
        val dictsWritten: Int,
        val dictsSkipped: Int,
        /**
         * 非 null = 该阶段失败并中止了后续写入。
         *
         * 导入是顺序写入、**不回滚**：前面几段（词库/设置/词频）可能已经生效。按「整包失败」报
         * 会让用户以为什么都没变，按「全部成功」报则是撒谎 —— 如实说明阶段，并提示可重试
         * （词库同名跳过、词频取较大者、剪贴板按 hash 跳过，重试都是幂等的）。
         */
        val failedStage: String?,
    )

    fun import(
        context: Context,
        zip: File,
        mode: ImportMode,
        includeClipboard: Boolean,
        includeDicts: Boolean,
    ): ImportOutcome? {
        // 进程级互斥：导入跑在设置页的裸线程上，页面重建后按钮会重新可点 —— 不拦的话
        // 两路流程会并发写 prefs / 词频 / 剪贴板（各有锁不会损坏，但报数会失真，
        // 来自不同包时还会各阶段各留一份）
        if (!beginImport()) {
            Diagnostics.w(TAG, "导入: 已有导入在进行中，忽略本次")
            return null
        }
        try {
            return importLocked(context, zip, mode, includeClipboard, includeDicts)
        } finally {
            endImport()
        }
    }

    private fun importLocked(
        context: Context,
        zip: File,
        mode: ImportMode,
        includeClipboard: Boolean,
        includeDicts: Boolean,
    ): ImportOutcome? {
        // 一次导入共用一份解压预算（见 [ScanBudget]）：每个节都要从包首顺序扫一遍
        val budget = ScanBudget()
        // 这两个早退必须留痕：同函数其它拒绝分支都有 W，只有这里静默的话，
        // 用户拿一个截断/损坏包求助时，诊断包里分不清「manifest 条目读不到」与「清单解析失败」
        val manifestText = readEntry(zip, ConfigBackup.ENTRY_MANIFEST, budget = budget) ?: run {
            Diagnostics.w(TAG, "导入: manifest 条目读不到（包被截断或损坏）")
            return null
        }
        val manifest = ConfigBackup.parseManifest(manifestText) ?: run {
            Diagnostics.w(TAG, "导入: manifest 无法解析（不是本程序导出的包）")
            return null
        }
        if (ConfigBackup.checkVersion(manifest.formatVersion) == ConfigBackup.VersionCompat.TOO_NEW) {
            Diagnostics.w(TAG, "导入: 包格式版本 ${manifest.formatVersion} 高于本版 ${ConfigBackup.FORMAT_VERSION}，已拒绝")
            return null
        }

        // 逐节读取 + 完整性校验：**存在的**每一节都必须通过摘要校验（prefs 为必备节；词频与剪贴板
        // 是可选节，缺节合法，但「读不出来」必须拒绝）。词库节例外：聚合摘要只在勾选「导入词库」
        // 时校验，且白名单内单个文件内容不符只算跳过。任何一处不符即整体拒绝
        // （半截包 / 被编辑过的包都不该进用户环境，宁可让用户重导）
        // prefs 是必备节（导出必写）。缺节、或「节存在但 manifest 里没有它的摘要」都判包不完整：
        // 后者正是「manifest 被裁剪得只剩 format 字段」的形态，旧写法会因此跳过校验直接导入
        // 三个节一次遍历读完（见 [readSections]）；manifest 仍单独先读，版本不兼容的包要在读完前就拒掉
        val sections = readSections(
            zip,
            listOf(
                ConfigBackup.ENTRY_PREFS,
                ConfigBackup.ENTRY_USER_FREQ,
                ConfigBackup.ENTRY_CLIPBOARD,
            ),
            budget = budget,
        )
        val prefsText = when (val r = sections[ConfigBackup.ENTRY_PREFS] ?: SectionRead.Missing) {
            is SectionRead.Ok -> r.text
            SectionRead.Missing -> {
                Diagnostics.w(TAG, "导入: 缺少 prefs 节")
                return null
            }
            else -> {
                // TooLarge / Failed：读不出来就不许假装成功
                Diagnostics.w(TAG, "导入: prefs 节不可用（$r）")
                return null
            }
        }
        if (!verify(manifest, ConfigBackup.SEC_PREFS, prefsText)) {
            Diagnostics.w(TAG, "导入: prefs 节校验失败")
            return null
        }
        // 词频是可选节；存在即摘要必须对得上，且「读不出来（超限）」必须拒绝而不是当成「没有」
        val freqText = when (val r = sections[ConfigBackup.ENTRY_USER_FREQ] ?: SectionRead.Missing) {
            is SectionRead.Ok -> r.text
            SectionRead.Missing -> null
            else -> {
                Diagnostics.w(TAG, "导入: 词频节不可用（$r）")
                return null
            }
        }
        if (freqText != null && !verify(manifest, ConfigBackup.SEC_USER_FREQ, freqText)) {
            Diagnostics.w(TAG, "导入: 词频节校验失败")
            return null
        }
        // 剪贴板同为可选节；同上
        val clipText = when (val r = sections[ConfigBackup.ENTRY_CLIPBOARD] ?: SectionRead.Missing) {
            is SectionRead.Ok -> r.text
            SectionRead.Missing -> null
            else -> {
                Diagnostics.w(TAG, "导入: 剪贴板节不可用（$r）")
                return null
            }
        }
        if (clipText != null && !verify(manifest, ConfigBackup.SEC_CLIPBOARD, clipText)) {
            Diagnostics.w(TAG, "导入: 剪贴板节校验失败")
            return null
        }
        // manifest 登记了摘要却没有对应节 ⇒ 包被裁剪过，按不完整拒绝（见 [firstMissingDeclaredSection]）
        val missingDeclared = firstMissingDeclaredSection(manifest, sections)
        if (missingDeclared != null) {
            Diagnostics.w(TAG, "导入: manifest 登记了 $missingDeclared 但没有对应节，判包不完整")
            return null
        }

        // 词库先落盘：它写入失败会让整包作废，越早发现越好（且失败时不会留下半套配置）
        var dictsWritten = 0
        var dictsSkipped = 0
        val dictSection = manifest.sections[ConfigBackup.SEC_DICTS]
        if (includeDicts) {
            // 包里有词库条目、manifest 却没登记摘要 ⇒ 与其它节一致：判包不完整。
            // 旧写法会静默跳过，用户勾了「导入词库」却什么都没发生（而清单里明明显示着词库数量）
            if (dictSection == null && hasDictEntry(zip, budget)) {
                Diagnostics.w(TAG, "导入: 包内有词库条目但 manifest 未登记摘要，判包不完整")
                return null
            }
            if (dictSection != null) {
                // 词库是最先写入的一段，失败时还没动别的东西，保持「整包拒绝」语义
                val r = runCatching {
                    restoreDicts(File(context.filesDir, DICT_DIR), zip, dictSection.sha256, budget = budget)
                }
                    .onFailure { Diagnostics.w(TAG, "导入: 词库恢复异常 ${it.javaClass.simpleName}") }
                    .getOrNull() ?: return null
                dictsWritten = r.first
                dictsSkipped = r.second
            }
        }

        // 预算耗尽后 hasDictEntry 会退化成 false（读成「包里没有词库」），不在这里拦就会静默漏导入；
        // 位置必须在写入之前：要么整包收下，要么什么都不动
        if (budget.exhausted) {
            Diagnostics.w(TAG, "导入: 包内解压量超过预算，按不可用处理")
            return null
        }

        val prefs = Prefs(context)
        val clipPrefs = ClipboardPrefs.of(context)

        var prefsApplied = 0
        var prefsIgnored = 0
        var failedStage: String? = null
        if (mode == ImportMode.RESTORE) {
            val decoded = ConfigBackup.decodePrefs(prefsText.orEmpty())
            val main = decoded[ConfigBackup.PREFS_MAIN].orEmpty()
            val clip = decoded[ConfigBackup.PREFS_CLIPBOARD].orEmpty()
            val r1 = prefs.importFromBackup(main)
            val appliedClip = clipPrefs.importFromBackup(clip)
            prefsApplied = r1.applied + appliedClip
            // 未知键（更高版本写入）+ 类型不符 + 剪贴板侧未识别的键，统一计入忽略数
            prefsIgnored = r1.unknown.size + r1.mismatch.size + (clip.size - appliedClip)
            // 写入走 apply()（没有失败信号），必须主动 flush 才知道是否真的落盘：
            // 磁盘满时静默丢一半而弹窗仍报「设置 26 项」，这种虚高必须变成如实的忽略数
            if (prefsApplied > 0 && !prefs.flush()) {
                Diagnostics.w(TAG, "导入: 设置项落盘失败，计入忽略")
                prefsIgnored += prefsApplied
                prefsApplied = 0
                failedStage = "设置项写盘失败"
            }
            // 模糊音掩码在引擎里另有一份运行期副本（Prefs + `PinyinEngine.fuzzyMask`）：
            // 导入改了它就必须同步一次，否则设置页显示与候选行为不一致，直到 IME 进程重建
            PinyinEngine.setFuzzyMask(prefs.fuzzyPinyinMask)
            // 上限被导入改小时，既有库不会自己收敛（只有下次复制入库才 trim）：
            // 这里补一次，否则「导入成功」后历史仍超限，用户会以为上限没生效
            runCatching { ClipboardDb.get(context).trimTo(clipPrefs.maxItems) }
        }

        val freqFile = File(context.filesDir, USER_FREQ_FILE)
        val freqBefore = runCatching {
            freqFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let { UserFrequency.countEntries(it) }
        }.getOrNull() ?: 0
        var freqAfter = freqBefore
        if (failedStage == null && freqText != null) {
            val mergedCount = runCatching {
                // 「仅并入」要先把内存里还没落盘的学习写下去：否则合并基准是旧文件，
                // 防抖窗口内那几次学习会随替换被静默丢掉
                if (mode != ImportMode.RESTORE) UserFrequency.flush()
                val localText = if (mode == ImportMode.RESTORE) "" else runCatching {
                    freqFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)
                }.getOrNull().orEmpty()
                val merged = UserFrequency.mergeForBackup(localText, freqText, nowDay())
                // 走 replaceFromBackup：它在写盘锁内完成「落盘 + 换内存态」，
                // 避免运行期的防抖写盘把刚导入的词频用旧快照覆盖回去
                if (UserFrequency.replaceFromBackup(freqFile, merged.text, prefs.userLearning)) {
                    merged.mergedCount
                } else {
                    null
                }
            }.getOrNull()
            if (mergedCount == null) {
                Diagnostics.w(TAG, "导入: 词频写入失败")
                failedStage = "词频写入失败"
            } else {
                freqAfter = mergedCount
            }
        }

        var clipAdded = 0
        var clipSkipped = 0
        if (failedStage == null && includeClipboard && ConfigBackup.SEC_CLIPBOARD in manifest.sections) {
            val ok = runCatching {
                val incoming = ConfigBackup.decodeClipboard(clipText.orEmpty())
                val db = ClipboardDb.get(context)
                val before = db.count()
                val planned = ConfigBackup.planClipboardImport(db.allHashes(), incoming)
                clipSkipped = incoming.size - planned.size
                for (e in planned) {
                    val id = db.insertRestored(
                        content = e.content,
                        createdAt = e.createdAt,
                        sourcePackage = e.sourcePackage,
                        sourceAppName = e.sourceAppName,
                        category = e.category,
                        favorite = e.favorite,
                    )
                    if (id <= 0) clipSkipped++
                }
                db.trimTo(clipPrefs.maxItems)
                // 报「实际进库多少」而不是「插入成功多少」：导入条目带的是旧设备时间戳，
                // 本机库满时它们恰好最旧，会刚插入就被 trimTo 裁掉（此时报 +N 是虚高的）
                val net = db.count() - before
                clipAdded = net.coerceAtLeast(0)
                if (net < 0) clipSkipped += -net
                val trimmedAway = (planned.size - clipSkipped) - clipAdded
                if (trimmedAway > 0) clipSkipped += trimmedAway
                true
            }.getOrDefault(false)
            if (!ok) {
                Diagnostics.w(TAG, "导入: 剪贴板写入失败")
                failedStage = "剪贴板写入失败"
            }
        }

        Diagnostics.i(
            TAG,
            "导入完成 mode=$mode 设置=$prefsApplied 忽略=$prefsIgnored 词频=$freqBefore→$freqAfter " +
                "剪贴板=+$clipAdded(跳过 $clipSkipped) 词库=+$dictsWritten(跳过 $dictsSkipped) " +
                "失败阶段=${failedStage ?: "无"}",
        )
        return ImportOutcome(
            prefsApplied = prefsApplied,
            prefsIgnored = prefsIgnored,
            freqBefore = freqBefore,
            freqAfter = freqAfter,
            clipAdded = clipAdded,
            clipSkipped = clipSkipped,
            dictsWritten = dictsWritten,
            dictsSkipped = dictsSkipped,
            failedStage = failedStage,
        )
    }

    /**
     * 恢复词库文件：先全部提取到 `*.restore` 临时名并逐文件算 sha256，
     * 聚合摘要与 manifest 对得上才改名生效；对不上就把临时文件全部删掉。
     *
     * 之所以不让单文件「边校验边改名」：聚合校验失败时已经改名的文件无法回滚，
     * 会留下「一部分是备份的、一部分是原来的」的混合状态。
     *
     * @return 写入数 to 跳过数（跳过的**四种**情形：文件名不在清单内、内容与官方校验值不符、
     *   目标文件已存在（不覆盖用户现有词库，即使内容与包里的不同）、改名落盘失败（磁盘/权限问题））；
     *   失败返回 null
     */
    internal fun restoreDicts(
        dir: File,
        zip: File,
        expectedDigest: String,
        budget: ScanBudget = ScanBudget(),
        /** 文件名 → 官方 sha256（null = 不在清单内）；测试注入合成表以覆盖成功落盘路径。
         *  保持在参数表末尾：既有调用点把它写成尾随 lambda，追加在它之后的参数会改变绑定目标 */
        checksumOf: (String) -> String? = { OptionalDicts.byFileName(it)?.checksum },
    ): Pair<Int, Int>? = try {
        restoreDictsLocked(dir, zip, expectedDigest, budget, checksumOf)
    } finally {
        // 临时件在流程内已经改名或删除，在册登记到此为止：不注销的话集合每导入一次涨一批，
        // 清扫器还会一直跳过同名的残留文件（见 [tempInFlight]）
        tempInFlight.removeIf { it.endsWith(RESTORE_SUFFIX) }
    }

    /** [restoreDicts] 的实现体：`*.restore` 的在册登记在这一层，注销统一由外层包装负责 */
    private fun restoreDictsLocked(
        dir: File,
        zip: File,
        expectedDigest: String,
        budget: ScanBudget,
        checksumOf: (String) -> String?,
    ): Pair<Int, Int>? {
        dir.mkdirs()
        val pending = ArrayList<Pair<File, File>>()
        val hashes = ArrayList<Pair<String, String>>()
        var skippedByName = 0
        var totalBytes = 0L
        val readOk = runCatching {
            ZipInputStream(BufferedInputStream(zip.inputStream())).use zipStream@ { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // 条目数闸：零字节条目不吃预算（drain 一次读到 EOF）
                    if (!budget.stepEntry()) {
                        Diagnostics.w(TAG, "词库恢复: 条目数超过上限，已中止")
                        return@zipStream false
                    }
                    val name = entry.name
                    if (!entry.isDirectory && name.startsWith(ConfigBackup.DICT_DIR)) {
                        val fileName = name.removePrefix(ConfigBackup.DICT_DIR)
                        // 目录穿越防护：条目名只允许一层普通文件名
                        if (fileName.isEmpty() || fileName.contains('/') || fileName.contains('\\')) {
                            return@zipStream false
                        }
                        if (pending.size + skippedByName >= MAX_DICT_FILES) {
                            Diagnostics.w(TAG, "词库恢复: 条目数超过 $MAX_DICT_FILES 上限")
                            return@zipStream false
                        }
                        // 白名单（可选词库清单）之外的条目：**参与摘要校验但不落盘**。
                        // 否则任意 .xz 都能被塞进 dicts/，而引擎启动会无条件加载并全量解压它
                        // —— xz 压缩比轻松 >100:1，几百 KB 的包就能让输入法启动 OOM 或写满磁盘
                        val official = checksumOf(fileName)
                        val allowed = official != null
                        val tmp = File(dir, fileName + RESTORE_SUFFIX)
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        var tooBig = false
                        val out = if (allowed) tmp.outputStream() else null
                        try {
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                size += n
                                // 总量闸（不是单文件）：16 个 64MB 条目 = 1GB 先落盘再校验，磁盘会被写满
                                if (totalBytes + size > MAX_DICT_BYTES) {
                                    tooBig = true
                                    break
                                }
                                md.update(buf, 0, n)
                                out?.write(buf, 0, n)
                            }
                        } finally {
                            out?.close()
                        }
                        if (tooBig) {
                            tmp.delete()
                            Diagnostics.w(TAG, "词库恢复: 词库总量超过 $MAX_DICT_BYTES 字节上限")
                            return@zipStream false
                        }
                        totalBytes += size
                        val digest = md.digest().joinToString("") { "%02x".format(it) }
                        hashes.add(fileName to digest)
                        when {
                            official == null -> {
                                skippedByName++
                                Diagnostics.w(TAG, "词库恢复: 跳过不在可选词库清单内的 $fileName")
                            }
                            // 白名单只保证「文件名合法」；内容还必须与官方 sha256 一致 —— 否则
                            // 「白名单名 + 攻击者内容」照样会落盘并被引擎加载（xz 解压炸弹）。
                            // 内容不符按跳过处理（不整包拒绝）：同名词库改版后，旧备份仍能导入其余部分
                            !digest.equals(official, ignoreCase = true) -> {
                                tmp.delete()
                                skippedByName++
                                Diagnostics.w(TAG, "词库恢复: $fileName 内容与官方校验值不符，已跳过")
                            }
                            else -> {
                                pending.add(tmp to File(dir, fileName))
                                // 登记为 in-flight：清理器跑在别的线程/执行器上，不能删正在写的中间件
                                tempInFlight.add(tmp.name)
                            }
                        }
                    }
                    // 非词库条目也要读空并记账：对抗包可以把巨大条目混在 dicts/ 前后
                    if (!budget.drain(zis)) {
                        Diagnostics.w(TAG, "词库恢复: 包内解压量超过预算，已中止")
                        return@zipStream false
                    }
                    entry = zis.nextEntry
                }
                true
            }
        }.getOrDefault(false)

        if (!readOk) {
            pending.forEach { it.first.delete() }
            // 解压中途抛异常（deflate 损坏 / 磁盘满）时，当前条目既不在 pending 也没登记 in-flight
            // （两者都在循环之后才赋值），半截 `*.restore` 只能等下次清扫回收 —— 这里按后缀全清
            // （该目录只放词库文件，不会误伤）
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(RESTORE_SUFFIX)) f.delete()
            }
            Diagnostics.w(TAG, "词库恢复: 读取失败")
            return null
        }
        if (dictsDigest(hashes) != expectedDigest) {
            pending.forEach { it.first.delete() }
            Diagnostics.w(TAG, "词库恢复: 摘要不符，已丢弃临时文件")
            return null
        }
        var written = 0
        var skipped = skippedByName
        for ((tmp, dest) in pending) {
            when {
                dest.isFile -> {
                    tmp.delete()
                    skipped++
                }
                tmp.renameTo(dest) -> written++
                else -> {
                    tmp.delete()
                    skipped++
                }
            }
        }
        return written to skipped
    }

    // ── zip / 文件工具 ───────────────────────────────────────

    internal fun verify(manifest: ConfigBackup.Manifest, section: String, text: String): Boolean =
        manifest.sections[section]?.sha256 == ConfigBackup.sha256(text)

    /** 文本重载：主要供测试与便捷调用；导出路径统一走 [sectionOfBytes]（同一份内容只转换一次） */
    internal fun sectionOf(text: String): ConfigBackup.Section =
        sectionOfBytes(text.toByteArray(Charsets.UTF_8))

    /** 与 [sectionOf] 一致，但直接吃算好的字节数组（导出路径复用它，避免同一份内容被转换多次） */
    internal fun sectionOfBytes(bytes: ByteArray): ConfigBackup.Section =
        ConfigBackup.Section(ConfigBackup.sha256(bytes), bytes.size.toLong())

    /** 文本的非空行数（只给日志用：避免为一行统计做全量解析） */
    private fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        var n = 0
        for (line in text.lineSequence()) if (line.isNotBlank()) n++
        return n
    }

    /** dicts 节的聚合摘要：`<文件名>:<sha256>` 按文件名排序拼接后再取 sha256（导入可复算） */
    internal fun dictsDigest(items: List<Pair<String, String>>): String =
        ConfigBackup.sha256(items.sortedBy { it.first }.joinToString("\n") { "${it.first}:${it.second}" })

    private fun fileSha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun putBytes(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun putFile(zos: ZipOutputStream, name: String, file: File) {
        zos.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /**
     * 节的读取结果：把「没有这一节」与「有但读不出来」分开。
     *
     * `TooLarge` 与 `Failed` 必须区别于 `Missing`：前者当成后者就是「静默跳过一节还报成功」，
     * 恰是三态设计要堵的洞（zip 是解密产物、校验过 GCM，但仍可能因磁盘/内存问题读失败）。
     */
    internal sealed interface SectionRead {
        class Ok(val text: String) : SectionRead
        object Missing : SectionRead
        object TooLarge : SectionRead
        object Failed : SectionRead
    }

    /**
     * 按名查找的解压量预算（**解压后**字节）。
     *
     * `ZipInputStream.nextEntry` 会先把当前条目读完（`closeEntry` 解压丢弃）才走到下一条，
     * 于是「跳过条目」的代价按解压后体积计；而 [MAX_PACKAGE_BYTES] 管的是压缩后的字节 ——
     * 不另设这道闸，几百 KB 的对抗包就能让按名查找跑上几十分钟，而导入跑在不可取消的进度框后面，
     * 用户只能杀进程。
     *
     * 检查与导入各自建一份、内部逐节共用：每个节都要从包首重新顺序扫一遍，
     * 分开记账等于把上限乘上节数。超限后 [exhausted] 置位，由调用方整包拒收。
     */
    internal class ScanBudget(
        private var remaining: Long = MAX_SCAN_INFLATED_BYTES,
        private val maxEntries: Int = MAX_SCAN_ENTRIES,
    ) {

        /** 是否已因超限中止过（解压量超预算 / 条目数超上限；调用方据此拒收整包，而不是把半截结果当好结果） */
        var exhausted = false
            private set

        /** 已走过的 zip 条目数（见 [stepEntry]） */
        private var entries = 0

        /**
         * 读空 [input] 当前条目的剩余内容并记账。
         *
         * 返回 false = 超限，调用方**必须停止遍历**（继续 `nextEntry` 等于放任它把剩下的条目
         * 解压完）。读异常照旧向上抛：那属于「包损坏」，与超限是两种不同的失败。
         */
        fun drain(input: InputStream): Boolean {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return true
                remaining -= n
                if (remaining < 0) {
                    exhausted = true
                    return false
                }
            }
        }

        /**
         * 登记「又走过一个 zip 条目」；返回 false = 条目数超过 [maxEntries]（已置 [exhausted]），
         * 调用方**必须停止遍历**并按整包不可用处理（与预算耗尽同一出口）。
         *
         * 零字节条目一次 `read` 即 EOF、不吃 [drain] 的预算，条目数只能单独计：
         * zip 本地头只有几十字节，256MB 的包能塞数百万条。
         */
        fun stepEntry(): Boolean {
            if (++entries > maxEntries) {
                exhausted = true
                return false
            }
            return true
        }
    }

    /**
     * 三态读取节。
     *
     * 为什么不能只返回 `String?`：「节超限读不出来」与「包里本来就没有这一节」都得到 null，
     * 前者会被当成后者**静默跳过** —— 用户看到「词频 100→100 条」以为成功，实际一条都没导入。
     * 分开之后，超限一律判包不可用（宁可明确失败，也不能假装成功）。
     */
    internal fun readSection(
        zip: File,
        name: String,
        maxBytes: Long = MAX_TEXT_SECTION_BYTES,
        budget: ScanBudget = ScanBudget(),
    ): SectionRead = runCatching {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == name) {
                    val text = readLimited(zis, maxBytes) ?: return@use SectionRead.TooLarge
                    return@use SectionRead.Ok(text)
                }
                // 条目数闸：零字节条目不吃预算（drain 一次读到 EOF）
                if (!budget.stepEntry()) {
                    Diagnostics.w(TAG, "读取条目 $name: 条目数超过上限，已中止")
                    return@use SectionRead.Failed
                }
                if (!budget.drain(zis)) {
                    Diagnostics.w(TAG, "读取条目 $name: 包内解压量超过预算，已中止")
                    return@use SectionRead.Failed
                }
                entry = zis.nextEntry
            }
            SectionRead.Missing
        }
    }.getOrElse {
        // 读失败 ≠ 没有这一节：前者必须让导入明确失败，否则又是「词频静默没导入、界面还报成功」
        Diagnostics.w(TAG, "读取条目 $name 失败: ${it.message}")
        SectionRead.Failed
    }

    /** 便捷封装：只关心文本的场合（manifest 这类必然很小的节） */
    internal fun readEntry(
        zip: File,
        name: String,
        maxBytes: Long = MAX_TEXT_SECTION_BYTES,
        budget: ScanBudget = ScanBudget(),
    ): String? = (readSection(zip, name, maxBytes, budget) as? SectionRead.Ok)?.text

    /**
     * 一次遍历读出多个节，键为节名；包里没有的名字不回键，语义同 [SectionRead.Missing]。
     *
     * 按名查找必须从包首顺序扫（`ZipInputStream` 不能跳转），而检查与导入要读的正是同一批节 ——
     * 分散调用等于把同一个包反复解压（此前检查 5 遍 + 导入 6 遍）。
     *
     * 严格性与分开调用一致：读不出来的节记 [SectionRead.TooLarge]；遍历中途出错或预算耗尽时，
     * **没走到的节一律记 [SectionRead.Failed]**，调用方据此整包拒收，而不是把「没走到」当成「没有这一节」。
     */
    internal fun readSections(
        zip: File,
        names: List<String>,
        maxBytes: Long = MAX_TEXT_SECTION_BYTES,
        budget: ScanBudget = ScanBudget(),
    ): Map<String, SectionRead> {
        val found = HashMap<String, SectionRead>(names.size)
        val wanted = names.toHashSet()
        val scanned = runCatching {
            ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null && found.size < wanted.size) {
                    // 条目数闸：零字节条目不吃预算（drain 一次读到 EOF），不设闸就是 O(条目数) 空转；
                    // 超限后未走到的节一律记 Failed
                    if (!budget.stepEntry()) {
                        Diagnostics.w(TAG, "读取节: 条目数超过上限，停止遍历")
                        return@use false
                    }
                    // 同名条目重复（手改过的包可能有）时首条定案：与逐节读取「命中即返回」一致，
                    // 重复的那条按跳读记账，不再解压进内存
                    if (!entry.isDirectory && wanted.contains(entry.name) && !found.containsKey(entry.name)) {
                        val text = readLimited(zis, maxBytes)
                        if (text == null) {
                            found[entry.name] = SectionRead.TooLarge
                            // readLimited 触到上限就返回，条目还剩一大截没读：跳读照样要记账，
                            // 否则一个超限节就能把后面的解压量免费送出去
                            if (!budget.drain(zis)) return@use false
                        } else {
                            found[entry.name] = SectionRead.Ok(text)
                        }
                    } else if (!budget.drain(zis)) {
                        return@use false
                    }
                    entry = zis.nextEntry
                }
                true
            }
        }.getOrElse {
            Diagnostics.w(TAG, "读取节失败: ${it.message}")
            false
        }
        if (!scanned) {
            for (name in wanted) found.putIfAbsent(name, SectionRead.Failed)
        }
        return found
    }

    /**
     * manifest 声明了摘要、实际却没有对应节（或该节读不出来）的**节名**；null = 没有这种节。
     *
     * [verify] 挡的是反方向（节存在、manifest 未登记摘要）。缺了这一半，「把节删掉、manifest 留着摘要」
     * 的形态会被 [readSections] 当成 [SectionRead.Missing]（不回键）而静默跳过 —— 用户看到「导入完成」，
     * 实际整节数据没进来。
     */
    internal fun firstMissingDeclaredSection(
        manifest: ConfigBackup.Manifest,
        sections: Map<String, SectionRead>,
        /** 节名 to 条目名（manifest 用节名，[readSections] 的键是条目名） */
        pairs: List<Pair<String, String>> = listOf(
            ConfigBackup.SEC_PREFS to ConfigBackup.ENTRY_PREFS,
            ConfigBackup.SEC_USER_FREQ to ConfigBackup.ENTRY_USER_FREQ,
            ConfigBackup.SEC_CLIPBOARD to ConfigBackup.ENTRY_CLIPBOARD,
        ),
    ): String? = pairs.firstOrNull { (section, entry) ->
        manifest.sections.containsKey(section) && sections[entry] !is SectionRead.Ok
    }?.first

    private fun readLimited(input: InputStream, maxBytes: Long): String? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                Diagnostics.w(TAG, "条目超过 $maxBytes 字节上限，按不可用处理")
                return null
            }
            buf.write(chunk, 0, n)
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 列出 zip 内条目名（非目录），最多 [maxEntries] 条。
     *
     * 上限是必须的：zip 本地头只有几十字节，256MB 的包能塞数百万条，而这里要建
     * `List<String>`、调用方还会再复制几份 —— 不设闸就是一条 OOM 通道。
     */
    internal fun zipEntryNames(
        zip: File,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        budget: ScanBudget = ScanBudget(),
    ): List<String> = runCatching {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            val out = ArrayList<String>()
            var entry = zis.nextEntry
            while (entry != null && out.size < maxEntries) {
                if (!entry.isDirectory) out.add(entry.name)
                // 超限就停下并回已收集的名字，由调用方按 [ScanBudget.exhausted] 拒收整包
                if (!budget.drain(zis)) return@use out
                entry = zis.nextEntry
            }
            out
        }
    }.getOrNull().orEmpty()

    /**
     * 包内是否存在 `dicts/` 前缀的条目（读到即返回）。
     *
     * 不能用 [zipEntryNames] 判断：它有收集上限，而（被改过的）包可以把词库条目排在上限之后 ——
     * 那样「有词库条目但 manifest 未登记」会被漏判成「没有词库」，用户勾了「导入词库」却什么都没发生。
     */
    internal fun hasDictEntry(
        zip: File,
        budget: ScanBudget = ScanBudget(),
    ): Boolean = runCatching {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith(ConfigBackup.DICT_DIR)) return@use true
                // 同 [readSections] 的条目数闸：零字节条目不吃预算，数百万条能把判定拖成分钟级空转。
                // 上限取值须容得下「词库条目排在收集上限之后」的形态（见 [MAX_SCAN_ENTRIES]）
                if (!budget.stepEntry()) {
                    Diagnostics.w(TAG, "词库条目判定: 条目数超过上限，停止遍历")
                    return@use false
                }
                if (!budget.drain(zis)) return@use false
                entry = zis.nextEntry
            }
            false
        }
    }.getOrNull() ?: false

    private fun deviceLabel(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}"

    private fun nowDay(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()
}
