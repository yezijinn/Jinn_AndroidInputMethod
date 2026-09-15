package com.jinn.inputmethod

import android.content.Context
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * 拼音输入引擎：词库加载、候选查询、自然码双拼转换。
 *
 * 数据源（均为宽松开源许可，已预处理成紧凑文本 asset）：
 *  - `pinyin_chars.txt`：音节 → 单字候选（按频率降序）
 *  - `pinyin_phrases.txt`：拼音串（去空格）→ 词语候选（按频率降序）
 *  - `pinyin_syllables.txt`：合法音节全集（用于全拼切分与双拼校验）
 *
 * 查询模型（刻意保持简单）：
 *  1. 词语候选：整串拼音精确匹配短语表（如 `nihao` → 你好）
 *  2. 单字候选：把拼音串切分为合法音节序列，对最后一个音节做前缀匹配
 *
 * 双拼：自然码方案（键位映射见 [Shuangpin]，对齐 libime 的 Ziranma profile），
 * 输入先转换成全拼音节串，再走与全拼相同的查询逻辑。
 */
object PinyinEngine {

    private const val TAG = "PinyinEngine"

    /**
     * 单字候选上限（每个音节）。必须足够大以覆盖该音节的常用字——例如 ji 音的
     * 「基/寄」按词库频率排在第 19/20 位，原 12 会截断导致候选缺失。
     * 候选栏为横向滚动容器，可承载较多候选。
     */
    private const val MAX_CHARS = 60
    private const val MAX_PHRASES = 12

    /** 常用字表 asset 名（《通用规范汉字表》一级+二级，6500 字） */
    private const val COMMON_CHARS_ASSET = "common_chars.txt"

    /** 短语词库 asset 名（xz 压缩存放，加载时流式解压） */
    private const val PHRASES_ASSET_XZ = "pinyin_phrases.txt.xz"

    /** 扩展词库文件名（用户下载/导入后放在 filesDir 下，可选） */
    /** 可选词库目录名（filesDir 下）：每类词库一个 xz 文件，供「分类词库」页按需下载 */
    const val OPT_DICT_DIR = "dicts"

    /** 常用字位图大小：覆盖基本区汉字（0x4E00~0x9FFF） */
    private const val CHAR_TABLE_SIZE = 0x9FFF + 1

    @Volatile
    private var loaded = false

    /** 可选词库包是否已加载完成（延迟加载，见 loadOptionalAsync） */
    @Volatile
    private var optionalLoaded = false

    /** 可选词库包是否正在后台加载（防重复触发） */
    @Volatile
    private var optionalLoading = false

    /** 音节 → 单字（按频率降序） */
    private var charsBySyllable = HashMap<String, Array<String>>()

    /** 拼音串 → 词语（按频率降序） */
    private var phrasesByPinyin = HashMap<String, Array<String>>()

    /** 词 → 拼音键（智能预测用：取已选词的拼音作前缀查更长短语） */
    private var wordToPinyin = HashMap<String, String>()

    /** 合法音节集合（不含声调） */
    private val validSyllables = HashSet<String>()

    /**
     * 所有合法音节的「真前缀」集合（不含音节本身）。
     *
     * 供 [isTruePrefixOfSyllable] 做 O(1) 判断。原实现每调用一次就遍历整个音节表做
     * startsWith，而该方法在每次按键的查询路径上会被调用若干次（分词、伪完整音节判定、
     * 补全召回），累计是几百次字符串比较。预建集合后降为一次哈希查找。
     */
    private val syllablePrefixes = HashSet<String>()

    /**
     * 常用字位图（按 Char 码点直接索引，判定 O(1)）。
     *
     * null 表示**不过滤**（显示全部字，含生僻字）；非 null 时按位图过滤。
     *
     * 为什么用位图而不是 HashSet：词库加载要判定 123 万词条 / 450 万字符，
     * 位图是纯数组下标访问，比哈希查找快得多；65536 位的 BooleanArray 约 64KB，
     * 相比它省下的内存可以忽略。
     *
     * 判定标准：《通用规范汉字表》(2013) 一级(3500) + 二级(3000) = 6500 常用字；
     * 三级(1605) 及表外字（扩展区）视为生僻。见 assets/common_chars.txt。
     */
    private var commonChars: BooleanArray? = null

    /** 因生僻字被过滤掉的词条数（诊断用） */
    private var filteredWordCount = 0

    /** 扩展词库（长词包）是否已加载 */
    private var extensionLoaded = false

    /** 有序音节（字典序）：保证单字候选输出顺序稳定 */
    private val sortedSyllables = ArrayList<String>()

    /** 有序合法音节全集（字典序）：前缀补全扫描用，finalizeLoad 时构建一次 */
    private val sortedValidSyllables = ArrayList<String>()

    /** 有序拼音键（字典序）：智能预测的二分查找前缀用 */
    private val sortedPhraseKeys = ArrayList<String>()

    /** 加载词库；幂等，可在后台线程调用 */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            logMemory("词库加载前")
            val t0 = System.currentTimeMillis()
            // 生僻字过滤：默认不加载（用户几乎用不到，平白占内存与加载时间）。
            // 必须在读词库**之前**建立位图，否则过滤无从谈起——这也是它比
            // 「加载后过滤」更省内存的原因：跳过的词条从未进过 HashMap。
            val showRareChars = Prefs(context).showRareChars
            if (!showRareChars) loadCommonChars(context)
            loadChars(context)
            loadPhrases(context)
            // 可选词库包**不在这里加载** —— 见 loadOptionalAsync()。
            // 它们可达 97 万词条、加载十几秒，若在此一并加载会拖慢
            // 「开机后首次输入」的候选就绪时间；改为基础包就绪后在后台补齐。
            loadSyllables(context)
            finalizeLoad()
            loaded = true
            Log.i(TAG, "词库加载完成: 音节=${charsBySyllable.size} 词语键=${phrasesByPinyin.size} 合法音节=${validSyllables.size}")
            // 反向索引规模一并记录：它是内存占用的大头（百万级 HashMap，Node + 表数组），
            // 评估内存优化前必须先有这个数，不能靠猜。
            Diagnostics.i(
                TAG,
                "词库加载完成: 音节=${charsBySyllable.size} 词语键=${phrasesByPinyin.size} " +
                    "反向索引=${wordToPinyin.size} 合法音节=${validSyllables.size} " +
                    if (showRareChars) "生僻字=显示" else "生僻字=隐藏(已过滤 $filteredWordCount 条)",
            )
            Diagnostics.i(
                TAG,
                "可选词库: 将在基础词库就绪后延迟加载（分类词库包，加载期间不影响既有输入）",
            )
            // 29MB 词库常驻 IME 进程，是低端机被 LMK 杀的最大嫌疑。
            // 这里留采样点：真机排查时直接从日志看词库到底吃多少内存。
            Diagnostics.i(TAG, "词库加载耗时: ${System.currentTimeMillis() - t0}ms")
            logMemory("词库加载后")
        }
    }

    /**
     * 采样进程内存并写入诊断日志。
     *
     * 只做一次 `Runtime` 读取（无 GC 触发、无阻塞），用于评估词库常驻内存开销。
     * 「已用」= totalMemory − freeMemory，反映当前 Java 堆占用。
     */
    private fun logMemory(stage: String) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val maxMb = rt.maxMemory() / 1024 / 1024
        Diagnostics.i(TAG, "内存采样[$stage]: 已用=${usedMb}MB 堆上限=${maxMb}MB")
    }

    /**
     * 测试辅助：清空全部加载状态并复位生僻字过滤。
     *
     * PinyinEngine 是单例，且词库数据是累加的——同一 JVM 内多个测试类相继注入数据
     * 会互相污染（尤其「生僻字过滤」是全局状态，一旦置位会影响后续所有查询）。
     * 测试在 @Before 里调用本方法即可获得干净起点。
     */
    internal fun resetForTest() {
        synchronized(this) {
            charsBySyllable.clear()
            phrasesByPinyin.clear()
            wordToPinyin.clear()
            validSyllables.clear()
            syllablePrefixes.clear()
            sortedSyllables.clear()
            sortedValidSyllables.clear()
            sortedPhraseKeys.clear()
            completionCache.clear()
            commonChars = null
            filteredWordCount = 0
            loaded = false
        }
    }

    /**
     * 测试注入：直接用字符串字典加载（跳过 Android assets）。
     *
     * @param commonCharsText 常用字表文本；传 null 表示**不过滤**（加载全部字，
     *        与「显示生僻字」开关开启一致）。传入即启用生僻字过滤，用于验证过滤行为。
     */
    internal fun loadFromTexts(
        chars: String,
        phrases: String,
        syllables: String,
        commonCharsText: String? = null,
    ) {
        synchronized(this) {
            // 先复位过滤状态，避免同一个 JVM 内多次注入时相互污染
            commonChars = null
            filteredWordCount = 0
            if (commonCharsText != null) setCommonCharsText(commonCharsText)
            loadCharsText(chars)
            loadPhrasesText(phrases)
            loadSyllablesText(syllables)
            finalizeLoad()
            loaded = true
        }
    }

    private fun finalizeLoad() {
        sortedSyllables.clear()
        sortedSyllables.addAll(charsBySyllable.keys.sorted())
        sortedPhraseKeys.clear()
        sortedPhraseKeys.addAll(phrasesByPinyin.keys.sorted())
        sortedValidSyllables.clear()
        sortedValidSyllables.addAll(validSyllables.sorted())
        buildSyllablePrefixes()
    }

    /** 预建音节真前缀集合（加载时调用一次；合法音节最长 6 字符） */
    private fun buildSyllablePrefixes() {
        syllablePrefixes.clear()
        for (syllable in validSyllables) {
            for (len in 1 until syllable.length) {
                syllablePrefixes.add(syllable.substring(0, len))
            }
        }
    }

    // ── 生僻字过滤 ───────────────────────────────────────────

    /** 读取常用字表 asset 并建立过滤位图 */
    private fun loadCommonChars(context: Context) {
        context.assets.open(COMMON_CHARS_ASSET).bufferedReader(StandardCharsets.UTF_8).use { reader ->
            setCommonCharsText(reader.readText())
        }
    }

    /**
     * 解析常用字表文本并建立位图。
     *
     * 格式：`#` 开头为注释行，其余行里的字全部计入常用字（便于人工维护）。
     * assets 加载与单元测试注入共用本方法。
     */
    internal fun setCommonCharsText(text: String) {
        val bits = BooleanArray(CHAR_TABLE_SIZE)
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            for (c in trimmed) {
                val code = c.code
                if (code < CHAR_TABLE_SIZE) bits[code] = true
            }
        }
        commonChars = bits
    }

    /** 关闭过滤：加载全部字（含生僻字）。对应「显示生僻字」开关开启。 */
    internal fun clearCommonCharsFilter() {
        commonChars = null
    }

    /**
     * 单个字符是否允许载入。
     *
     *  - ASCII / 数字 / 标点（< 0x4E00）：不参与判定，一律放行；
     *  - 基本区汉字（0x4E00~0x9FFF）：查常用字位图；
     *  - 其它（含 BMP 外扩展区汉字的代理对）：视为生僻。
     */
    private fun isLoadableChar(c: Char): Boolean {
        val bits = commonChars ?: return true
        val code = c.code
        return when {
            code < 0x4E00 -> true
            code <= 0x9FFF -> bits[code]
            else -> false
        }
    }

    /** 整词是否允许载入：词中任一字符生僻即整条丢弃 */
    private fun isLoadableWord(word: String): Boolean {
        if (commonChars == null) return true
        for (c in word) {
            if (!isLoadableChar(c)) return false
        }
        return true
    }

    /**
     * 延迟加载可选词库包（分类词库页下载的那些）。
     *
     * **为什么与基础包分开**：可选包可达 97 万词条、单独加载耗时十几秒。
     * 若在 `onCreate` 里与基础包一起加载，「开机后首次使用输入法」时
     * 候选就绪时间会从 6 秒被拖到 25 秒。改为基础包就绪后调用本方法，
     * 用户此时已能正常打字，可选包在后台悄悄补齐。
     *
     * @param delayMs 基础包就绪后再等多久开始加载，给首屏输入让路
     * @param onReady 全部可选包加载完成后的回调（**不在主线程**，调用方自行切线程）
     */
    fun loadOptionalAsync(context: Context, delayMs: Long = 5000L, onReady: (() -> Unit)? = null) {
        if (optionalLoaded || optionalLoading) return
        optionalLoading = true
        Thread {
            runCatching {
                // load() 幂等：若基础包已就绪会立即返回
                load(context)
                if (delayMs > 0) Thread.sleep(delayMs)
                val t0 = System.currentTimeMillis()
                loadExtensionDict(context)
                // 可选包引入了新的拼音键，**必须重建有序键表**：
                // sortedPhraseKeys 是加载时的快照，不重建则新词不参与前缀补全。
                synchronized(this) { finalizeLoad() }
                optionalLoaded = true
                Diagnostics.i(
                    TAG,
                    "可选词库延迟加载完成，耗时 ${System.currentTimeMillis() - t0}ms，" +
                        "词语键=${phrasesByPinyin.size}",
                )
            }.onFailure {
                Diagnostics.e(TAG, "可选词库延迟加载失败（基础词库不受影响）: ${it.message}")
            }
            optionalLoading = false
            onReady?.invoke()
        }.start()
    }

    /** 可选词库是否已就绪（供 UI 显示状态） */
    fun isOptionalReady(): Boolean = optionalLoaded

    /**
     * 加载全部可选词库包。
     *
     * 只认一个位置：`filesDir/dicts/` 目录下的全部 xz 文件
     * （每类词库一个文件，由「分类词库」页按需下载写入）。
     * 按文件名排序加载，保证候选顺序可复现。
     *
     * 全部用 **merge 模式**加载（基础包词条在前，可选包追加，并去重）。
     * 未安装任何可选包不算错误：基础包已覆盖日常输入，仅记一条日志。
     */
    private fun loadExtensionDict(context: Context) {
        val dir = java.io.File(context.filesDir, OPT_DICT_DIR)
        val packs = dir.listFiles { f -> f.isFile && f.name.endsWith(".xz") }
            ?.sortedBy { it.name }
            .orEmpty()

        var loadedCount = 0
        var loadedBytes = 0L
        for (f in packs) {
            if (loadOneDict(f)) {
                loadedCount++
                loadedBytes += f.length()
            }
        }

        extensionLoaded = loadedCount > 0
        if (loadedCount == 0) {
            Diagnostics.i(TAG, "未安装可选词库：仅加载基础词库（长词不可用）")
        } else {
            Diagnostics.i(TAG, "可选词库已加载 $loadedCount 个包，共 ${loadedBytes / 1024}KB")
        }
    }

    /** 加载单个词库文件（merge 模式）。成功返回 true。 */
    private fun loadOneDict(file: java.io.File): Boolean = runCatching {
        org.tukaani.xz.XZInputStream(file.inputStream())
            .bufferedReader(StandardCharsets.UTF_8).use { reader ->
                // 必须用合并模式：可选包与基础包、可选包彼此之间都可能有相同拼音键
                loadPhrasesReader(reader, merge = true)
            }
        Diagnostics.i(TAG, "已加载词库包: ${file.name} (${file.length() / 1024}KB)")
        true
    }.onFailure {
        Diagnostics.e(TAG, "词库包加载失败（忽略，其余词库仍可用）: ${file.name} - ${it.message}")
    }.getOrDefault(false)

    /** 扩展词库（长词包）是否已加载（供设置页显示状态） */
    fun isExtensionLoaded(): Boolean = extensionLoaded

    private fun loadChars(context: Context) {
        context.assets.open("pinyin_chars.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            loadCharsReader(reader)
        }
    }

    private fun loadCharsReader(reader: java.io.BufferedReader) {
        if (charsBySyllable.isEmpty()) charsBySyllable = HashMap(1024)
        var line = reader.readLine()
        while (line != null) {
            if (line.isNotBlank()) {
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val syllable = line.substring(0, tab)
                    val chars = line.substring(tab + 1).split(',')
                    // 生僻字过滤：不载入（既不占内存，也不进候选）
                    val kept = if (commonChars == null) {
                        chars
                    } else {
                        chars.filter { it.length == 1 && isLoadableChar(it[0]) }
                    }
                    filteredWordCount += chars.size - kept.size
                    if (kept.isNotEmpty()) charsBySyllable[syllable] = kept.toTypedArray()
                }
            }
            line = reader.readLine()
        }
    }

    private fun loadCharsText(text: String) {
        loadCharsReader(java.io.BufferedReader(java.io.StringReader(text)))
    }

    private fun loadPhrases(context: Context) {
        // 词库以 xz 存放：28.0MB → 8.0MB，比 deflate 再省 22%，APK 体积随之下降约 20%。
        // 用流式解压直接读、不落地磁盘；仍逐行解析，避免一次性构造超大 String。
        // 解压实测约 0.6s，发生在后台加载线程上，不阻塞 UI、也不影响键盘显示。
        context.assets.open(PHRASES_ASSET_XZ).let { raw ->
            org.tukaani.xz.XZInputStream(raw).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                loadPhrasesReader(reader)
            }
        }
    }

    /**
     * 逐行解析短语表。
     *
     * @param merge true = 并入已有数据（加载扩展包时用），false = 覆盖（首次加载基础包）。
     *
     * **扩展包必须用合并模式**：扩展包与基础包存在相同的拼音键（如 `qie` 两边都有词），
     * 若直接赋值会把基础包的候选整体挤掉。合并时基础包词条在前（优先级更高），
     * 扩展包长词追加在后。
     */
    private fun loadPhrasesReader(reader: java.io.BufferedReader, merge: Boolean = false) {
        // 预分配按基础包规模（约 46 万键）：一次到位，避免反复扩容。
        // 扩展包会突破该容量并自然扩容，但那时数据已基本齐了。
        if (phrasesByPinyin.isEmpty()) phrasesByPinyin = HashMap(600_000)
        if (wordToPinyin.isEmpty()) wordToPinyin = HashMap(700_000)

        var line = reader.readLine()
        while (line != null) {
            if (line.isNotBlank()) {
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val pinyin = line.substring(0, tab)
                    val phrases = line.substring(tab + 1).split('|')
                    // 生僻字过滤：含生僻字的词整条丢弃
                    // （用户既然用不到生僻字，也就不会用到含生僻字的词）
                    val kept = if (commonChars == null) {
                        phrases
                    } else {
                        phrases.filter { isLoadableWord(it) }
                    }
                    filteredWordCount += phrases.size - kept.size
                    if (kept.isNotEmpty()) {
                        if (merge) {
                            // 基础包在前、扩展包追加：同键词条共存，基础候选优先。
                            //
                            // **必须去重**：扩展包与基础包可能收录同一个词
                            // （如「阿尔萨斯」两边都有），直接 `existing + kept`
                            // 会让候选栏出现两个完全相同的候选项。
                            val existing = phrasesByPinyin[pinyin]
                            phrasesByPinyin[pinyin] = if (existing != null) {
                                val seen = HashSet<String>(existing.size + kept.size)
                                existing.forEach { seen.add(it) }
                                existing + kept.filter { seen.add(it) }
                            } else {
                                kept.toTypedArray()
                            }
                        } else {
                            phrasesByPinyin[pinyin] = kept.toTypedArray()
                        }
                        // 构建 词→拼音 反向索引（首个出现的拼音为准，供智能预测）
                        for (word in kept) {
                            wordToPinyin.putIfAbsent(word, pinyin)
                        }
                    }
                }
            }
            line = reader.readLine()
        }
    }

    /** 测试注入用：按行文本加载短语表（与 [loadPhrasesReader] 逻辑一致） */
    private fun loadPhrasesText(text: String) {
        loadPhrasesReader(java.io.BufferedReader(java.io.StringReader(text)))
    }

    private fun loadSyllables(context: Context) {
        context.assets.open("pinyin_syllables.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) validSyllables.add(line.trim())
                line = reader.readLine()
            }
        }
    }

    private fun loadSyllablesText(text: String) {
        for (line in text.lineSequence()) {
            if (line.isNotBlank()) validSyllables.add(line.trim())
        }
    }

    /** 双拼校验用：某串是否为合法完整音节（词库未加载时返回 false） */
    fun isValidSyllable(s: String): Boolean =
        loaded && validSyllables.contains(s)

    // ── 查询 ──────────────────────────────────────────────

    data class Result(
        val candidates: List<String>,
        val syllables: List<String>,
        val partialSyllable: String,
    ) {
        val isEmpty: Boolean get() = candidates.isEmpty()
    }

    /**
     * 查询拼音串对应的候选。
     * @param input 全拼或双拼转换后的拼音串（小写字母，无空格）
     *
     * 候选按「音节数逐级递减」组织（对齐 AOSP PinyinIME prepare_candidates）：
     *  输入 N 个音节 → 先 N 字词、再 N-1 字词、…、最后 1 字单字。
     *  如输入 nihao（[ni,hao]）→ 2 字词「你好」+ 单字「你/尼/泥」「好/号/毫」；
     *  输入 nihaoma（[ni,hao,ma]）→ 3 字「你好吗」+ 2 字「你好」+ 各音节单字。
     *
     * 不做「整串前缀联想」（那会产生 nihaoa/nihaoma 等超出拼音数量的词）。
     */
    fun query(input: String): Result {
        if (!loaded) return Result(emptyList(), emptyList(), "")
        val raw = input.lowercase()
        if (raw.isEmpty()) return Result(emptyList(), emptyList(), "")

        val result = LinkedHashSet<String>()

        // 1. 切分音节（含 ue/ve 变体，兼容词库两种 üe 写法）
        val (syllables, partial) = segment(raw)

        // 2. 从最长音节数逐级递减：先整词，再逐级到单字（对齐 AOSP while(lma_size>0)）
        for (k in syllables.size downTo 1) {
            val key = syllables.take(k).joinToString("")
            for (k2 in phraseKeysOf(key)) {
                phrasesByPinyin[k2]?.let { result.addAll(it.take(MAX_PHRASES)) }
            }
            // 逐级递减：k>1 时只取整词；k==1 时再补该音节的单字
            if (k == 1) {
                for (syl in syllables) {
                    result.addAll(charsFor(syl).take(MAX_CHARS))
                }
            }
        }

        // 3. 未完成音节的前缀联想（如 nih → 你 + h 前缀字）
        //    partial 非空；或末尾音节是「伪完整音节」（如 nim 的 m，本身合法但也是
        //    更长音节前缀，用户意图是 ni+m… 补全）时也触发补全
        val lastIsFakeComplete = syllables.isNotEmpty() &&
            partial.isEmpty() && isTruePrefixOfSyllable(syllables.last())
        if (partial.isNotEmpty() || lastIsFakeComplete) {
            // 3a. 词库短语补全召回（如 ni m → ni+men → 你们）：
            //    补全词插入 result 头部（优先于第 2 步已加入的单字）
            val completionWords = queryWithCompletion(raw)
            Diagnostics.i(
                TAG,
                "query补全: input=$raw partial=$partial fake=$lastIsFakeComplete " +
                    "syl=$syllables completionCount=${completionWords.size}",
            )
            val asList = ArrayList(result)
            result.clear()
            result.addAll(completionWords)
            result.addAll(asList)
            // 3b. 单字前缀联想（原有逻辑，保持）
            if (syllables.isNotEmpty()) {
                result.addAll(charsFor(syllables.last()).take(MAX_CHARS))
            }
            result.addAll(matchCharsByPrefix(partial).take(MAX_CHARS))
        }

        return Result(result.toList(), syllables, partial)
    }

    /** 精确匹配 + ue/ve 变体：词库同时存在 shenglue/shenglve 两种写法 */
    private fun phraseKeysOf(raw: String): Set<String> {
        if (raw.contains("ue")) {
            val alt = raw.replace("ue", "ve")
            return if (alt != raw) setOf(raw, alt) else setOf(raw)
        }
        if (raw.contains("ve")) {
            val alt = raw.replace("ve", "ue")
            return if (alt != raw) setOf(raw, alt) else setOf(raw)
        }
        return setOf(raw)
    }

    /** 第一个 >= target 的下标（标准二分下界） */
    private fun lowerBound(list: List<String>, target: String): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // ── 智能预测 ──────────────────────────────────────────

    private const val MAX_PREDICTIONS = 6

    /**
     * 智能预测：用户选完一个词后，预测下一个要输入的字/词。
     *
     * 算法（对齐 libime `PinyinPredictionSource::Dictionary` 的 matchWordsPrefix 拆词法）：
     *  1. 取已选词 [lastWord] 的拼音作前缀（如 你好 → nihao）
     *  2. 在词库中二分查找所有「拼音以该前缀开头且更长」的短语键（如 nihaoma、nihaoa）
     *  3. 若短语文本以 [lastWord] 开头，截掉已选部分得到预测词（你好吗 → 吗、你好像 → 像）
     *
     * 示例：选「你好」→ 预测「吗」「像」「不好」等；选「谢谢」→ 预测「你」「大家」等。
     */
    fun predict(lastWord: String): List<String> {
        if (!loaded || lastWord.isEmpty()) return emptyList()
        val lastPinyin = wordToPinyin[lastWord] ?: return emptyList()
        val out = LinkedHashSet<String>()
        var lo = lowerBound(sortedPhraseKeys, lastPinyin)
        while (lo < sortedPhraseKeys.size) {
            val key = sortedPhraseKeys[lo]
            if (!key.startsWith(lastPinyin)) break
            if (key.length > lastPinyin.length) {
                for (phrase in phrasesByPinyin[key].orEmpty()) {
                    if (phrase.length > lastWord.length && phrase.startsWith(lastWord)) {
                        out.add(phrase.substring(lastWord.length))
                    }
                }
            }
            lo++
        }
        return out.take(MAX_PREDICTIONS)
    }

    /**
     * 将拼音串切分为合法音节序列，最后一个音节可能不完整。
     *
     * 先尝试「词库约束分词」：枚举所有合法切分路径，优先选择能拼出词库短语的切分
     * （如 xuni → [xu, ni] 拼「虚拟」，而非贪心的 [xun, i]）。
     * 无词库命中时退回贪心最长匹配（原逻辑）。
     */
    private fun segment(input: String): Pair<List<String>, String> {
        dictionarySegmentation(input)?.let { return Pair(it, "") }
        return greedySegment(input)
    }

    /**
     * 词库约束分词：枚举 [input] 的所有合法音节切分路径，用词库短语命中评分，
     * 返回能拼出最多词库短语的切分；无命中返回 null（调用方退回贪心切分）。
     *
     * 例：xuni → 路径 [xun, i]（无词）、[xu, ni]（拼「虚拟」）→ 选 [xu, ni]。
     *
     * @param input 必须全部由合法音节组成（否则部分无法切分，返回 null）
     */
    private fun dictionarySegmentation(input: String): List<String>? {
        if (input.isEmpty()) return null
        // 候选切分路径（DFS 枚举，上限防爆炸）
        val paths = ArrayList<List<String>>()
        dfsSegment(input, 0, ArrayList(), paths)
        if (paths.isEmpty()) return null
        // 评分：整串拼词库短语数（词命中优先），其次音节数（多音节更自然）
        var best: List<String>? = null
        var bestScore = 0
        for (path in paths) {
            val key = path.joinToString("")
            val hit = phrasesByPinyin[key]?.size ?: 0
            val score = hit * 1000 - path.size  // 词命中为主，音节少略优
            if (score > bestScore) {
                bestScore = score
                best = path
            }
        }
        // 只有真实命中词库的切分才采用（否则贪心）
        return best?.takeIf { phrasesByPinyin.containsKey(it.joinToString("")) }
    }

    /** DFS 枚举所有合法音节切分（最长音节 6 字符），路径上限 [MAX_SEGMENT_PATHS] 防爆炸 */
    private fun dfsSegment(input: String, pos: Int, cur: MutableList<String>, out: MutableList<List<String>>) {
        if (out.size >= MAX_SEGMENT_PATHS) return
        if (pos == input.length) {
            out.add(cur.toList())
            return
        }
        val maxEnd = (pos + 6).coerceAtMost(input.length)
        var end = maxEnd
        while (end > pos) {
            val candidate = input.substring(pos, end)
            if (validSyllables.contains(candidate)) {
                cur.add(candidate)
                dfsSegment(input, end, cur, out)
                cur.removeAt(cur.size - 1)
            }
            end--
        }
    }

    /** 贪心最长匹配切分（原逻辑）：剩余整体是更长音节前缀时作不完整返回 */
    private fun greedySegment(input: String): Pair<List<String>, String> {
        val syllables = ArrayList<String>()
        var i = 0
        while (i < input.length) {
            val rest = input.substring(i)
            // 剩余整体不是完整音节、但又是某个更长音节的真前缀 → 未完成音节，返回
            if (!validSyllables.contains(rest) && isTruePrefixOfSyllable(rest)) {
                return Pair(syllables, rest)
            }
            var matched: String? = null
            var end = (i + 6).coerceAtMost(input.length) // 最长音节 6 字符（zhuang）
            while (end > i) {
                val candidate = input.substring(i, end)
                if (validSyllables.contains(candidate)) {
                    matched = candidate
                    break
                }
                end--
            }
            if (matched != null) {
                syllables.add(matched)
                i += matched.length
            } else {
                break
            }
        }
        return Pair(syllables, input.substring(i))
    }

    /**
     * 是否存在比 s 更长的合法音节以 s 开头（即 s 是某个音节的真前缀）。
     *
     * O(1)：查 [syllablePrefixes] 预建集合。原实现遍历整个音节表做 startsWith，
     * 而本方法在每次按键的查询路径上会被调用若干次，是热路径上的无谓开销。
     * 长度上限由集合天然保证（合法音节最长 6 字符，故最长真前缀为 5 字符）。
     */
    private fun isTruePrefixOfSyllable(s: String): Boolean =
        s.isNotEmpty() && syllablePrefixes.contains(s)

    /** 以指定串为前缀匹配音节，返回合并后的单字候选；音节按字典序稳定输出 */
    private fun matchCharsByPrefix(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (syllable in sortedSyllables) {
            if (syllable.startsWith(prefix)) {
                for (c in charsBySyllable[syllable].orEmpty()) {
                    if (seen.add(c)) out.add(c)
                }
            }
        }
        return out
    }

    /** 按完整拼音直接查单字（供候选栏显示补全） */
    fun charsFor(syllable: String): List<String> =
        charsBySyllable[syllable]?.toList() ?: emptyList()

    // ── 候选拼音消费区间（Residual Pinyin Rematching）───────

    /**
     * 候选词在全拼输入串上消费的拼音区间（Pinyin Span）。
     * @property quanpinChars 消费的全拼字符数；等于输入全长表示全部消费
     * @property syllables    消费的完整音节数（双拼按键换算：两键一音节）
     */
    data class Consumption(val quanpinChars: Int, val syllables: Int)

    /**
     * 计算候选 [candidate] 在全拼输入串 [input] 上应消费的区间。
     *
     * 依据候选实际绑定的拼音确定消费范围，不按字符串长度猜测：
     *  1. 词语候选：词→拼音反向索引 [wordToPinyin] 与输入做音节对齐前缀匹配
     *     （ue/ve 变体兼容）；补全型候选（词拼音以整个输入为前缀，如 nim→你们）消费全部输入；
     *  2. 单字候选：在音节切分中定位首个含该字的音节，消费到该音节结束；
     *     来自末尾未完成音节前缀联想的单字消费全部输入；
     *  3. 兜底（无法确定区间）：消费全部输入，等价旧的清空行为，不产生错误残码。
     */
    fun consumption(input: String, candidate: String): Consumption {
        if (candidate.isEmpty()) return Consumption(input.length, 0)
        if (!loaded || input.isEmpty()) return Consumption(input.length, 0)
        val (syllables, partial) = segment(input)

        /** 覆盖 [chars] 个全拼字符所需的音节数（前缀求和） */
        fun syllablesCovering(chars: Int): Int {
            var acc = 0
            var k = 0
            while (k < syllables.size && acc < chars) {
                acc += syllables[k].length
                k++
            }
            return k
        }

        // 1) 词语候选：词→拼音反查（ue/ve 双写法兼容）
        if (candidate.length > 1) {
            wordToPinyin[candidate]?.let { wp0 ->
                for (wp in phraseKeysOf(wp0)) {
                    // 常规：候选拼音是输入的音节对齐前缀 → 只消费该 Span，残码保留
                    if (wp.length < input.length && input.startsWith(wp)) {
                        return Consumption(wp.length, syllablesCovering(wp.length))
                    }
                    // 完整覆盖 或 补全型（词拼音以输入为前缀，如 nim→nimen→你们）→ 消费全部
                    if (wp.startsWith(input)) {
                        return Consumption(input.length, syllables.size)
                    }
                }
            }
        }

        // 2) 单字候选：定位首个含该字的音节，消费到该音节结束
        if (candidate.length == 1) {
            var acc = 0
            for ((i, syl) in syllables.withIndex()) {
                acc += syl.length
                if (charsBySyllable[syl]?.contains(candidate) == true) {
                    return Consumption(acc, i + 1)
                }
            }
            // 来自末尾未完成音节的前缀联想 → 消费全部输入
            if (partial.isNotEmpty()) return Consumption(input.length, syllables.size)
        }

        // 3) 兜底：无法确定区间时消费全部（维持旧的清空行为）
        return Consumption(input.length, syllables.size)
    }

    // ── 不完整拼音补全（Pinyin Completion）──────────────────


    /** 补全结果数量上限（防候选爆炸，文档建议 32） */
    private const val MAX_COMPLETION_RESULTS = 32

    /** 词库约束分词：DFS 枚举路径上限（防超长输入组合爆炸） */
    private const val MAX_SEGMENT_PATHS = 16

    /** 完整音节序列最大长度（防超长输入组合爆炸） */
    private const val MAX_SYLLABLES = 8

    /** 前缀 → 完整音节列表缓存（补全查询热路径，避免重复扫描音节表） */
    private val completionCache = HashMap<String, List<String>>()

    /**
     * 返回以 [prefix] 开头的所有合法完整音节（字典序）。
     * 例：m → [ma, mai, man, mang, mao, me, mei, men, meng, mi, ...]
     * 遍历合法音节全集 [validSyllables]（完整音节表，非单字表）——
     * 补全的目的是拼词库短语键，音节必须合法即可，不要求有单字。
     * 带缓存：同一前缀只扫描一次。
     */
    fun completeSyllablePrefix(prefix: String): List<String> {
        if (prefix.isEmpty() || !loaded) return emptyList()
        val cached = completionCache[prefix]
        if (cached != null) return cached
        val out = ArrayList<String>()
        // 遍历有序合法音节全集（finalizeLoad 已排序），前缀匹配 + 字典序稳定
        for (syllable in sortedValidSyllables) {
            if (syllable.startsWith(prefix)) {
                out.add(syllable)
                if (out.size >= MAX_COMPLETION_RESULTS) break
            }
        }
        completionCache[prefix] = out
        // 缓存防无限增长：超限清空（前缀数量有限，正常不会触发，纯防御）
        if (completionCache.size > 512) completionCache.clear()
        return out
    }

    /**
     * 不完整拼音补全召回：把 [input] 切分为「完整音节 + 末尾不完整前缀」，
     * 将末尾前缀补全为完整音节后拼接，查词库短语。
     *
     * 例：input="nim" → 切分 [ni] + 前缀"m" → 补全 men/ma/mei…
     *    → 拼接 nimen/nima/nimei… → 查词库召回「你们/你妈/你没」
     *
     * 只返回词库中真实存在的短语；拼音补全只是内部召回机制，候选栏显示中文词。
     * @return 候选短语（按词库原有频率序），去重保序
     */
    fun queryWithCompletion(input: String): List<String> {
        if (!loaded || input.isEmpty()) return emptyList()
        val (syllables, partial) = segment(input)
        if (syllables.isEmpty()) return emptyList() // 首音节就不完整：只做单字前缀联想

        // 末尾不完整前缀判定：
        //  - partial 非空 → 直接用
        //  - partial 空但最后一个音节是「伪完整音节」（本身合法、同时是更长音节前缀，
        //    如 m/n/ng/a/e 等短音节）→ 把它从完整音节中剥出当作不完整前缀。
        //    例：nim 的 segment 返回 [ni, m]（m 是合法音节），但用户意图是 ni+m… 补全
        val last = syllables.last()
        val effPartial = if (partial.isNotEmpty()) partial
        else if (isTruePrefixOfSyllable(last)) last
        else return emptyList()   // 末尾真完整（如 nimen 的 men），不进补全
        val effSyllables = if (partial.isNotEmpty()) syllables else syllables.dropLast(1)
        if (effSyllables.isEmpty()) return emptyList()
        if (effSyllables.size + 1 > MAX_SYLLABLES) return emptyList()  // 防超长组合爆炸

        // 补全末尾前缀为合法完整音节，逐音节拼接查词库
        val base = effSyllables.joinToString("")
        val completions = completeSyllablePrefix(effPartial)
        if (completions.isEmpty()) return emptyList()

        val result = LinkedHashSet<String>()
        for (syl in completions) {
            for (key in phraseKeysOf(base + syl)) {
                phrasesByPinyin[key]?.let { result.addAll(it.take(MAX_PHRASES)) }
            }
            if (result.size >= MAX_COMPLETION_RESULTS) break
        }
        return result.toList()
    }
}

/**
 * 自然码双拼方案（对齐 libime `ShuangpinBuiltinProfile::Ziranma`）。
 *
 * 规则：
 *  - 声母：zh→v、ch→i、sh→u，其余单字母声母不变
 *  - 韵母：两键一音节，第二键查 [FINAL_KEY] 表；同键多韵母时按声母消歧
 *  - 撮口呼归一化：**输出全部为 ASCII**（词库键即 ASCII）——
 *    j/q/x/y 后 ü 系列写作 u（ju/qu/xu/jue/que/xue/juan/jun/yue/yuan/yun），
 *    l/n 后单 ü 写作 v（lv/nv），l/n 后 üe 写作 ve（lve/nve）
 *  - 零声母：双字母韵母直写全拼（an/ai/ei/ao/ou/en/er），三字母用「首字母+韵母键」
 *    （ang→ah、eng→eg），单韵母 a/o/e 双写（aa/oo/ee）；另兼容 `o` 作零声母键
 *    （an→oj、ai→ol、ou→ob），与 libime 的 `zeroS_ = "o*"` 语义一致
 *
 * 本类完全自包含（不依赖词库），转换规则确定，可脱离 Android 资源单测。
 */
object Shuangpin {

    /** 声母 j/q/x：s 键后取 iong（jiong/qiong/xiong）；yong 是 y+ong，不在内 */
    private val JQX = setOf("j", "q", "x")

    /** 声母 j/q/x/y：与其搭配的撮口呼韵母（ü 系写作 u） */
    private val JQX_Y = setOf("j", "q", "x", "y")

    /** 声母 l/n：单 ü 写作 v（lv/nv） */
    private val L_N = setOf("l", "n")

    /** 零声母两键输入 → 全拼（含双写、直写、首字符+键、o+键 四种形式） */
    private val ZERO_TABLE = mapOf(
        // 单韵母双写
        "aa" to "a", "ee" to "e", "oo" to "o",
        // 双字母零声母：直写全拼
        "an" to "an", "ai" to "ai", "ao" to "ao",
        "ei" to "ei", "en" to "en", "er" to "er", "ou" to "ou",
        // 三字母零声母：首字母 + 韵母键（ang 的键是 h，eng 的键是 g）
        "ah" to "ang", "eg" to "eng",
        // 首字符 + 韵母键（libime 的 special handling 生成形式）
        "aj" to "an", "al" to "ai", "ak" to "ao",
        "ez" to "ei", "ef" to "en", "or" to "er",
        // 兼容 o 作零声母键（libime zeroS_ 含 'o'）
        "oj" to "an", "ol" to "ai", "ok" to "ao", "oh" to "ang",
        "oz" to "ei", "of" to "en", "og" to "eng", "ob" to "ou",
        "oa" to "a", "oe" to "e",
    )

    /**
     * 把自然码双拼输入串转换成全拼音节串（纯 ASCII）。
     * 每两键一个音节；末尾若只剩一个键，按声母键前缀处理（如 `v`→`zh`）。
     * 非法组合返回 null 之前已转换的部分（尽量容错）。
     */
    fun toQuanpin(input: String): String {
        val raw = input.lowercase()
        if (raw.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < raw.length) {
            val syl = syllableFor(raw[i], raw[i + 1]) ?: break
            sb.append(syl)
            i += 2
        }
        if (i < raw.length) {
            sb.append(initialFor(raw[i]))
        }
        return sb.toString()
    }

    /** 两键一音节；返回全拼音节，无法组合返回 null */
    private fun syllableFor(k1: Char, k2: Char): String? {
        // 零声母表优先（双写 / 直写 / o+键），命中即返回
        ZERO_TABLE["$k1$k2"]?.let { return it }
        val initial = initialFor(k1)
        if (initial.isEmpty()) return null
        val final_ = finalFor(initial, k2) ?: return null
        return initial + final_
    }

    /**
     * 根据声母消歧，返回该声母 + 韵母键对应的韵母（纯 ASCII）。
     * 同键多韵母的键位及消歧规则：
     *  - s → ong/iong：j/q/x 后 iong（jiong/qiong/xiong）；yong 是 y+ong
     *  - r → uan（含 üan，j/q/x/y 后写作 uan：juan/quan/xuan/yuan）
     *  - t → ue（含 üe，j/q/x/y 后 jue/que/xue/yue，l/n 后 lue/nue，与词库 chars/syllables 表一致）
     *  - p → un（含 ün，j/q/x/y 后写作 un：jun/qun/xun/yun）
     *  - v → ui/ü：j/q/x/y 后 u（ju/qu/xu/yu），l/n 后 v（lv/nv），其余 ui
     *  - y → uai/ing：g/k/h/zh/ch/sh 后 uai（guai/kuai/huai/zhuai/chuai/shuai）
     *  - w → ua/ia：g/k/h/zh/ch/sh 后 ua（gua/kua/hua/zhua/chua/shua）
     *  - d → uang/iang：g/k/h/zh/ch/sh 后 uang（guang/kuang/huang/zhuang/chuang/shuang）
     */
    private fun finalFor(initial: String, key: Char): String? = when (key) {
        's' -> if (initial in JQX) "iong" else "ong"
        'r' -> "uan"
        't' -> "ue"
        'p' -> "un"
        'v' -> when {
            initial in L_N -> "v"
            initial in JQX_Y -> "u"
            else -> "ui"
        }
        // o 键：b/p/m/f/w 后是 o（bo/po/mo/fo/wo），其他声母是 uo（guo/cuo/zuo）
        'o' -> if (initial in setOf("b", "p", "m", "f", "w")) "o" else "uo"
        // y 键：uai/ing 消歧
        'y' -> if (initial in UAI_INITIALS) "uai" else "ing"
        // w 键：ua/ia 消歧
        'w' -> if (initial in UA_INITIALS) "ua" else "ia"
        // d 键：uang/iang 消歧
        'd' -> if (initial in UA_INITIALS) "uang" else "iang"
        else -> SINGLE_FINALS[key]
    }

    /** 可配 uai 的声母（guai/kuai/huai/zhuai/chuai/shuai） */
    private val UAI_INITIALS = setOf("g", "k", "h", "zh", "ch", "sh")

    /** 可配 ua/uang 的声母（gua/kua/hua/zhua/chua/shua …） */
    private val UA_INITIALS = setOf("g", "k", "h", "zh", "ch", "sh")

    /** 无歧义键：键 → 唯一韵母（s/r/t/p/v/o/y/w/d 已在 [finalFor] 特判） */
    private val SINGLE_FINALS = mapOf(
        'a' to "a", 'l' to "ai", 'j' to "an", 'h' to "ang", 'k' to "ao",
        'e' to "e", 'z' to "ei", 'f' to "en", 'g' to "eng",
        'i' to "i", 'm' to "ian", 'c' to "iao", 'x' to "ie",
        'n' to "in", 'q' to "iu", 'b' to "ou", 'u' to "u",
    )

    private fun initialFor(key: Char): String = when (key) {
        'v' -> "zh"
        'i' -> "ch"
        'u' -> "sh"
        else -> if (key in 'a'..'z') key.toString() else ""
    }
}
