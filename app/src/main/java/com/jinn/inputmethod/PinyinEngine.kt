package com.jinn.inputmethod

import android.content.Context
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * 拼音输入引擎：词库加载、候选查询、自然码双拼转换。
 *
 * 数据源（均为宽松开源许可，已预处理成紧凑 asset）：
 *  - `pinyin_index.bin.xz`：全量词库的二进制索引（原始文本与构建脚本见
 *    `tools/dict_builder/build_dict_index.py`）
 *  - `hot_phrases.txt.xz`：高频子集，先加载它保证键盘弹出即可打字
 *  - `pinyin_chars.txt`：音节 → 单字候选（按频率降序）
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

    /** 合并缓存的「确实没有」哨兵（避免同一缺失键反复走逐段查找） */
    private val EMPTY_WORDS = emptyArray<String>()

    /** 常用字表 asset 名（《通用规范汉字表》一级+二级，6500 字） */
    private const val COMMON_CHARS_ASSET = "common_chars.txt"

    /**
     * 全量基础词库的**二进制索引** asset（`tools/dict_builder/build_dict_index.py` 构建期产出）。
     *
     * 取代原来的 `pinyin_phrases.txt.xz`：运行时只需解压 + 顺序读入偏移数组，查询二分查找，
     * 不再逐行解析、不再建百万级 HashMap（真机 6~10.7s → 1s 内、内存 290MB → ~20MB）。
     */
    private const val INDEX_ASSET_XZ = "pinyin_index.bin.xz"

    /**
     * 高频子集词库（由 `tools/dict_builder/gen_hot_dict.py` 从同一份源按词频取前 4 万条生成）。
     *
     * 存在的唯一目的：把「键盘一弹出就能打字」从 6~10.7s 压到 1s 以内
     * （全量基础包解析完之前，[query] 因未就绪只能返回空）。
     */
    private const val HOT_PHRASES_ASSET_XZ = "hot_phrases.txt.xz"

    /** 扩展词库文件名（用户下载/导入后放在 filesDir 下，可选） */
    /** 可选词库目录名（filesDir 下）：每类词库一个 xz 文件，供「分类词库」页按需下载 */
    const val OPT_DICT_DIR = "dicts"

    /** 可选包的索引缓存目录（filesDir 下）：首次构建后落盘，后续启动直接读 */
    private const val INDEX_CACHE_DIR = "index"

    /** 索引缓存文件后缀（源文件 `ext.xz` → 缓存 `ext.xz.idx`） */
    private const val INDEX_SUFFIX = ".idx"

    /** 基础索引磁盘缓存前缀（文件名形如 `base.<APK mtime>.idx`） */
    private const val BASE_CACHE_PREFIX = "base."

    /** 分片大小：分片之间让出 CPU/IO，避免后台重活把前台打字挤成卡顿 */
    private const val CHUNK_BYTES = 256 * 1024

    /**
     * 分片读完整条解压流，每攒够 1MB 主动睡 1ms 让出 CPU。
     *
     * 不这么做的话，基础索引解压是一次 **1.8 秒不给喘息的连续 CPU 冲击**（还带一个 14.7MB 大分配）：
     * 真机实测 App 更新后首次启动、键盘刚弹出来就打字时，帧 p99 从 14ms 飙到 **300ms**（janky 12%）。
     * 让出 CPU 后总耗时只多几十毫秒（都在后台），但前台打字不再被挤。
     */
    internal fun readWithYields(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(16 * 1024 * 1024)
        val buf = ByteArray(CHUNK_BYTES)
        var sinceYield = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            sinceYield += n
            if (sinceYield >= 1 shl 20) {
                sinceYield = 0
                runCatching { Thread.sleep(1) }
            }
        }
        return out.toByteArray()
    }

    /** 常用字位图大小：覆盖基本区汉字（0x4E00~0x9FFF） */
    private const val CHAR_TABLE_SIZE = 0x9FFF + 1

    @Volatile
    private var loaded = false

    /** 可选词库包是否已加载完成（延迟加载，见 loadOptionalAsync） */
    @Volatile
    private var optionalLoaded = false

    /**
     * 全量基础包是否已 merge 完成。
     *
     * [loaded] 只表示「可以用来打字了」（高频子集就绪即可为 true）；
     * 本标志表示「候选已经全量」，供 UI 在补全窗口内给出提示。
     */
    @Volatile
    private var fullLoaded = false

    /** 是否已可输入（高频子集或全量基础包任一就绪即为 true） */
    val isLoaded: Boolean get() = loaded

    /** 是否已全量就绪（false 且 [isLoaded] 为 true 时，UI 可提示「词库补全中」） */
    val isFullyLoaded: Boolean get() = fullLoaded

    /** 可选词库包是否正在后台加载（防重复触发） */
    @Volatile
    private var optionalLoading = false

    /**
     * 以下容器**必须是并发安全的**。
     *
     * 可选词库由后台线程延迟加载（[loadOptionalAsync]，基础包就绪 5 秒后开始），
     * 而此刻用户通常正在打字——主线程在 [query] 等路径上并发读取同一批容器。
     * 用普通 HashMap 时，并发写入触发的扩容可能让桶链表成环（读方死循环、IME 卡死），
     * 或抛 ConcurrentModificationException。
     */
    private val charsBySyllable = ConcurrentHashMap<String, Array<String>>(1024)

    /**
     * **运行时并入**的拼音串 → 词语（按频率降序）。
     *
     * 只装两样东西：高频子集（首屏秒级可用），以及用户下载的可选词库包。
     * 全量基础包**不在**这里，而是 [baseIndex]（二进制索引，只读）；两者由 [phrasesFor] 统一读取，
     * 因此「基础在前、运行时词在后且去重」的候选顺序与旧实现完全一致。
     */
    private val phrasesByPinyin = ConcurrentHashMap<String, Array<String>>(64_000)

    /**
     * 全量基础词库索引（构建期解析好的二进制，见 [PhraseIndex]）。
     *
     * 不可变、可多线程并发读；加载失败时为 null（此时仅高频子集/可选包可用，不影响打字）。
     */
    @Volatile
    private var baseIndex: PhraseIndex? = null

    /**
     * 可选词库包的索引（按加载顺序；原子整体替换，供查询并发读取）。
     *
     * Stage 2：可选包不再并入 [phrasesByPinyin]（114 万条 HashMap 是内存大头），
     * 改为各自建索引 —— 首次加载时流式构建并缓存到 `filesDir/index/`，之后直接读缓存。
     */
    @Volatile
    private var optionalIndexes: List<PhraseIndex> = emptyList()

    /**
     * 合并结果小缓存：键 → 「运行时 ∪ 基础索引 ∪ 可选索引」去重后的词表（空数组表示确实没有）。
     *
     * 超限清空（与 `completionCache` 同一套防膨胀写法）。
     *
     * ⚠ **必须是并发容器**：读取方是主线程（查询/补全/预测），而失效点在 [finalizeLoad] ——
     * 词库加载线程（高频子集、基础索引、可选包索引）都会走到那里 `clear()`。
     * 裸 HashMap 在「主线程 get 的同时后台 clear」下会出现丢更新、错值甚至桶链表成环卡死，
     * 与项目里 `charsBySyllable` 当初的坑完全同类（见其 KDoc）。
     */
    private val mergedCache = java.util.concurrent.ConcurrentHashMap<String, Array<String>>(256)

    /** 词 → 拼音键（智能预测用：取已选词的拼音作前缀查更长短语） */
    private val wordToPinyin = ConcurrentHashMap<String, String>(8_192)

    /**
     * 「候选词 → 它的拼音键」的**短期**映射（只保留最近若干次查询）。
     *
     * 旧实现靠一张 69 万条的「词 → 拼音」全量反向索引来支撑两件事：
     * - 选中候选后计算「消费了多少拼音」（残码保留）
     * - 智能预测
     * 基础词库改为二进制索引后不再建全量反向索引（省约 50MB），改由**查询路径顺手记录**：
     * 候选本来就是从某个键查出来的，记下来即可，语义还更准（记录的是真正产出该候选的键）。
     * 上限 4096 条，超出即清空（预测/消费只关心最近一次查询）。
     * **语义边界**：只对「最近查询里展示过的候选」有效——而真实交互中预测与消费计算
     * 永远发生在用户刚选中的那个候选上，因此与实际需求一致（旧全量索引只是能力过剩）。
     */
    private val candidatePinyin = ConcurrentHashMap<String, String>(4_096)

    /** 合法音节集合（不含声调） */
    private val validSyllables = ConcurrentHashMap.newKeySet<String>()

    /**
     * 所有合法音节的「真前缀」集合（不含音节本身）。
     *
     * 供 [isTruePrefixOfSyllable] 做 O(1) 判断。原实现每调用一次就遍历整个音节表做
     * startsWith，而该方法在每次按键的查询路径上会被调用若干次（分词、伪完整音节判定、
     * 补全召回），累计是几百次字符串比较。预建集合后降为一次哈希查找。
     */
    private val syllablePrefixes = ConcurrentHashMap.newKeySet<String>()

    /**
     * 常用字位图（按 Char 码点直接索引，判定 O(1)）。
     *
     * null 表示**不过滤**（显示全部字，含生僻字）；非 null 时按位图过滤。
     *
     * 用位图而不是 HashSet：词库加载要判定 123 万词条 / 450 万字符，
     * 位图是纯数组下标访问，比哈希查找快得多；65536 位的 BooleanArray 约 64KB，
     * 相比它省下的内存可以忽略。
     *
     * 判定标准：《通用规范汉字表》(2013) 一级(3500) + 二级(3000) = 6500 常用字；
     * 三级(1605) 及表外字（扩展区）视为生僻。见 assets/common_chars.txt。
     */
    /**
     * 常用字位图（查询期生僻字过滤用）。
     *
     * ⚠ 必须 @Volatile：加载线程在 `load()` 里写它，主线程在 [phrasesFor] 里读它。
     * 目前「碰巧安全」——`loadCommonChars()` 在 `loaded = true`（volatile 写）之前完成，
     * 读方先在 `query()` 里读 `loaded`（volatile 读）建立了 happens-before。
     * 但这个保证是**隐式**的：任何「在 loaded 之后再改 commonChars」的路径都会让它失效
     * （读方拿到旧的 null → 生僻字/常用字过滤结果错乱）。这里显式声明，把不变量钉在字段上。
     */
    @Volatile
    private var commonChars: BooleanArray? = null

    /** 因生僻字被过滤掉的词条数（诊断用） */

    /** 扩展词库（长词包）是否已加载。后台线程写、UI 线程经 [isExtensionLoaded] 读，需 volatile */
    @Volatile
    private var extensionLoaded = false

    /**
     * 以下有序表都是**不可变快照**：finalizeLoad 时整体替换引用，而不是原地 clear+addAll。
     *
     * 原地改动会让并发读取方看到「已清空但还没填回」的中间态（候选突然空一片），
     * 或抛 ConcurrentModificationException；整体替换则读取方要么拿旧表、要么拿新表，
     * 两种都是完整可用的。
     */
    @Volatile
    private var sortedSyllables: List<String> = emptyList()

    /** 有序合法音节全集（字典序）：前缀补全扫描用，finalizeLoad 时构建一次 */
    @Volatile
    private var sortedValidSyllables: List<String> = emptyList()

    /** 有序拼音键（字典序）：智能预测的二分查找前缀用 */
    @Volatile
    private var sortedPhraseKeys: List<String> = emptyList()

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

            // ── 第一段：高频子集，先让用户能打字（真机实测 ~0.4s vs 全量 6~10.7s）──
            // 子集是同一份源里词频最高的那批词，且单字表/音节表都很小，一并先加载：
            // 单字表保证任何合法音节至少出单字候选（不会「什么都没有」），
            // 音节表是切分的前提（缺了它 query 直接不可用）。
            val hotMs = loadHotPhrases(context)
            if (hotMs >= 0) {
                if (charsBySyllable.isEmpty()) loadChars(context)
                if (validSyllables.isEmpty()) loadSyllables(context)
                finalizeLoad()
                loaded = true
                Diagnostics.i(
                    TAG,
                    "高频子库就绪（可开始输入）: 词语键=${phrasesByPinyin.size} 耗时=${hotMs}ms",
                )
            }

            // 用户词频：放在高频子集就绪**之后**。
            // 它是独立的文件 IO（几 ms），但混在"用户马上要打字"的关键路径里就会直接抬高
            // 可输入时间（实测 254~332ms → 337ms 这类抖动）；挪到第二段之前既不影响排序生效时机
            // （排序在查询期做，第一键之前一定已就绪），也不占用首字延迟预算。
            UserFrequency.load(context, Prefs(context).userLearning)

            // ── 第二段：全量基础包（二进制索引）──
            if (charsBySyllable.isEmpty()) loadChars(context)
            val indexMs = loadIndex(context)
            // 可选词库包**不在这里加载** —— 见 loadOptionalAsync()。
            // 它们可达 114 万词条、解析十几秒，改为基础包就绪后在**空闲时**补齐。
            if (validSyllables.isEmpty()) loadSyllables(context)
            if (indexMs >= 0) dropRedundantHotEntries()
            finalizeLoad()
            loaded = true
            fullLoaded = indexMs >= 0
            Log.i(
                TAG,
                "词库加载完成: 音节=${charsBySyllable.size} 基础键=${baseIndex?.size ?: 0} " +
                    "运行时键=${phrasesByPinyin.size} 合法音节=${validSyllables.size}",
            )
            if (hotMs >= 0) {
                Diagnostics.i(
                    TAG,
                    "两段式加载完成（高频子集 ${hotMs}ms + 索引 ${indexMs}ms，" +
                        "总计 ${System.currentTimeMillis() - t0}ms）",
                )
            }
            // 反向索引规模一并记录：它是内存占用的大头（百万级 HashMap，Node + 表数组），
            // 评估内存优化前必须先有这个数，不能靠猜。
            Diagnostics.i(
                TAG,
                "词库加载完成: 音节=${charsBySyllable.size} 基础键=${baseIndex?.size ?: 0} " +
                    "运行时键=${phrasesByPinyin.size} 运行时反查=${wordToPinyin.size} " +
                    "合法音节=${validSyllables.size} " +
                    if (showRareChars) "生僻字=显示" else "生僻字=隐藏(查询期过滤)",
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
        fullLoaded = false
        synchronized(this) {
            charsBySyllable.clear()
            phrasesByPinyin.clear()
            baseIndex = null
            optionalIndexes = emptyList()
            mergedCache.clear()
            wordToPinyin.clear()
            candidatePinyin.clear()
            validSyllables.clear()
            syllablePrefixes.clear()
            // 有序表是不可变快照，置空引用即可（没有 clear 方法）
            sortedSyllables = emptyList()
            sortedValidSyllables = emptyList()
            sortedPhraseKeys = emptyList()
            completionCache.clear()
            commonChars = null
            loaded = false
            // 可选词库状态一并复位，否则下一个测试类会误以为可选包已加载
            optionalLoaded = false
            optionalLoading = false
        }
    }

    /**
     * 测试注入：直接用字符串字典加载（跳过 Android assets）。
     *
     * @param commonCharsText 常用字表文本；传 null 表示**不过滤**（加载全部字，
     *        与「显示生僻字」开关开启一致）。传入即启用生僻字过滤，用于验证过滤行为。
     */
    /**
     * 测试注入：从**索引字节**加载短语表（与 [loadFromTexts] 的文本路径对拍用）。
     *
     * 与真实加载一致：基础词走 [baseIndex]，因此生僻字过滤发生在查询期。
     */
    internal fun loadFromIndexBytes(
        indexBytes: ByteArray,
        chars: String,
        syllables: String,
        commonCharsText: String? = null,
    ) {
        synchronized(this) {
            commonChars = null
            if (commonCharsText != null) setCommonCharsText(commonCharsText)
            loadCharsText(chars)
            baseIndex = PhraseIndex.of(indexBytes) ?: throw AssertionError("测试索引结构异常")
            loadSyllablesText(syllables)
            finalizeLoad()
            loaded = true
            fullLoaded = true
        }
    }

    /**
     * 测试注入：设置「可选包索引」列表（模拟 Stage 2 的可选包索引，验证逐段合并语义）。
     */
    internal fun setOptionalIndexesForTest(indexes: List<PhraseIndex>) {
        optionalIndexes = indexes
        mergedCache.clear()
    }

    internal fun loadFromTexts(
        chars: String,
        phrases: String,
        syllables: String,
        commonCharsText: String? = null,
    ) {
        synchronized(this) {
            // 先复位过滤状态，避免同一个 JVM 内多次注入时相互污染
            commonChars = null
            if (commonCharsText != null) setCommonCharsText(commonCharsText)
            loadCharsText(chars)
            loadPhrasesText(phrases)
            loadSyllablesText(syllables)
            finalizeLoad()
            loaded = true
            // 测试注入的是「全量」语义，故与真实全量加载保持同一状态（避免 UI 误判为补全中）
            fullLoaded = true
        }
    }

    /**
     * 测试注入：以**并入**语义追加短语表 —— 对应扩展包/可选包与基础包的合并加载。
     *
     * 需要单独一个入口：[loadFromTexts] 内部固定用覆盖语义，
     * 无法验证合并行为，而合并**必须去重**（基础包与扩展包会收录同一个词，
     * 不去重则候选栏出现两个完全相同的候选）。
     *
     * 用法：先用 [loadFromTexts] 注入基础包，再调用本方法注入扩展包。
     */
    internal fun mergePhrasesForTest(phrases: String) {
        synchronized(this) {
            loadPhrasesText(phrases, merge = true)
            // 可选包引入了新的拼音键，必须重建有序键表，否则新词不参与前缀补全
            finalizeLoad()
        }
    }

    private fun finalizeLoad() {
        // 整体替换引用，而不是原地 clear + addAll：
        // 本方法会在可选词库延迟加载时由后台线程**再次**调用，
        // 而主线程可能正在遍历这些表做前缀补全。
        sortedSyllables = charsBySyllable.keys.sorted()
        sortedPhraseKeys = phrasesByPinyin.keys.sorted()
        sortedValidSyllables = validSyllables.sorted()
        buildSyllablePrefixes()
        // 合并结果缓存是「运行时 ∪ 基础索引 ∪ 可选索引」的**派生视图**，上述任一来源变了都必须失效。
        // 放在这里而不是只写在各个写入点，是因为**基础索引不走 loadPhrasesReader**：
        // 高频子集窗口（键盘弹出后 ~0.2~2.3s）内用户查过的键，其「只有子集」的答案会一直粘住，
        // 直到可选包加载才被清掉——中间这几秒这些键的候选是**截断**的（少掉低频词）。
        mergedCache.clear()
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
     * 与基础包分开加载：可选包可达 97 万词条、单独加载耗时十几秒。
     * 若在 `onCreate` 里与基础包一起加载，「开机后首次使用输入法」时
     * 候选就绪时间会从 6 秒被拖到 25 秒。改为基础包就绪后调用本方法，
     * 用户此时已能正常打字，可选包在后台悄悄补齐。
     *
     * @param delayMs 基础包就绪后再等多久开始加载，给首屏输入让路
     * @param onReady 全部可选包加载完成后的回调（**不在主线程**，调用方自行切线程）
     */
    fun loadOptionalAsync(context: Context, delayMs: Long = 5000L, onReady: (() -> Unit)? = null) {
        // 检查-置位必须在同一把锁内：两个线程同时抵达时，
        // 无锁写法会让两边都通过检查、各自启一个加载线程，重复把词库 merge 一遍。
        synchronized(this) {
            if (optionalLoaded || optionalLoading) return
            optionalLoading = true
        }
        Thread {
            // 后台优先级：可选包是 1.1M 词条级的重活（真机实测 21~34s），
            // 且通常发生在用户已经开始打字之后，必须让路给前台输入。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
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

        val indexDir = java.io.File(context.filesDir, INDEX_CACHE_DIR).apply { mkdirs() }

        // 清理：源包已被删除的索引缓存（用户可能只删了词库文件）
        runCatching {
            val alive = packs.map { it.name }.toSet()
            val existing = indexDir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
            val stale = staleOptionalCacheNames(existing, alive)
            existing.filter { it in stale }.forEach { name ->
                if (java.io.File(indexDir, name).delete()) {
                    Diagnostics.i(TAG, "清理失效索引缓存: $name")
                }
            }
        }

        val loaded = ArrayList<PhraseIndex>(packs.size)
        var loadedBytes = 0L
        for (f in packs) {
            loadOptionalIndex(f, indexDir)?.let {
                loaded.add(it)
                loadedBytes += f.length()
            }
        }
        optionalIndexes = loaded            // 原子整体替换（查询侧并发读旧表安全）
        mergedCache.clear()
        extensionLoaded = loaded.isNotEmpty()
        if (loaded.isEmpty()) {
            Diagnostics.i(TAG, "未安装可选词库：仅加载基础词库（长词不可用）")
        } else {
            Diagnostics.i(TAG, "可选词库已加载 ${loaded.size} 个包，共 ${loadedBytes / 1024}KB（索引模式）")
        }
    }

    /**
     * 单个可选包 → 索引。
     *
     * - 优先复用缓存：源文件的 `length:mtime` 没变就直接映射
     * - 否则流式构建索引（单趟，不把百万行读进内存）并落盘
     * - 构建失败（键序异常等）就回退到旧的「运行时并入」路径，功能不丢
     */
    private fun loadOptionalIndex(src: java.io.File, indexDir: java.io.File): PhraseIndex? {
        val stamp = PhraseIndex.stampOfFile(src.length(), src.lastModified())
        val cache = java.io.File(indexDir, src.name + INDEX_SUFFIX)

        if (cache.isFile) {
            // 内存映射：不再把整块 .idx 读进堆（可选包两个缓存合计 34MB）
            PhraseIndex.ofMapped(cache)?.let { idx ->
                if (idx.sourceStamp == stamp) {
                    Diagnostics.i(TAG, "复用索引缓存(映射): ${src.name}（${idx.size} 键）")
                    return idx
                }
            }
        }

        val t0 = System.currentTimeMillis()
        return runCatching {
            val bytes = org.tukaani.xz.XZInputStream(src.inputStream())
                .bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    PhraseIndex.build(reader.lineSequence(), stamp)
                }
            // 原子落盘（写临时文件 + 改名）：缓存可能正被本进程 内存映射 使用，直接覆盖会截断映射
            val mapped = if (writeIndexCacheAtomically(cache, bytes)) PhraseIndex.ofMapped(cache) else null
            val idx = mapped ?: PhraseIndex.of(bytes) ?: error("构建出的索引结构异常")
            Diagnostics.i(
                TAG,
                "已构建词库包索引: ${src.name}（${idx.size} 键 / ${bytes.size / 1024}KB / " +
                    "${System.currentTimeMillis() - t0}ms）",
            )
            idx
        }.getOrElse { e ->
            Diagnostics.w(TAG, "索引构建失败，回退为运行时并入: ${src.name} - ${e.message}")
            runCatching {
                org.tukaani.xz.XZInputStream(src.inputStream())
                    .bufferedReader(StandardCharsets.UTF_8).use { loadPhrasesReader(it, merge = true) }
                Diagnostics.i(TAG, "已按旧路径并入词库包: ${src.name}")
            }.onFailure { Diagnostics.e(TAG, "词库包加载失败（忽略）: ${src.name} - ${it.message}") }
            null
        }
    }

    /**
     * 判定「源包已被删除」的索引缓存文件名（**纯函数**，便于单测）。
     *
     * 基础索引缓存 `base.<APK mtime>.idx` 必须排除在外：它的"源"是 APK 内的资产，不在词库包列表里，
     * 一旦被这里当成失效缓存删掉，就会**每次可选包加载都删一次基础缓存** ——
     * 症状是"内存映射永远命中不了、每次冷启动都要重新解压 1.8s"，而且日志上只看到一行
     * 「清理失效索引缓存」，极难联想到根因（2026-09-17 真机日志实锤）。
     */
    internal fun staleOptionalCacheNames(existing: List<String>, alivePacks: Set<String>): List<String> {
        return existing.filter { name ->
            name.endsWith(INDEX_SUFFIX) &&
                !name.startsWith(BASE_CACHE_PREFIX) &&
                name.removeSuffix(INDEX_SUFFIX) !in alivePacks
        }
    }

    /** APK 文件 mtime 作为基础索引缓存的有效性键；取不到时退化用 lastUpdateTime / versionCode */
    private fun baseCacheStamp(context: Context): String {
        val apkMtime = runCatching { java.io.File(context.packageCodePath).lastModified() }
            .getOrDefault(0L)
        if (apkMtime > 0L) return apkMtime.toString()
        val updated = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return if (updated > 0L) updated.toString() else "0"
    }

    /**
     * 原子写索引缓存：先写同目录临时文件再 `rename`。
     *
     * **不能直接覆盖**：缓存可能正被本进程 内存映射 使用（可选包重建缓存时会走到这里），
     * 截断已映射的文件会让读取方踩到空洞页，Linux 上直接 SIGBUS。rename 只替换目录项，
     * 旧映射仍指向旧 inode，安全。
     *
     * @return 是否成功落盘（失败只是让本次退回堆内，不影响可用性）
     */
    private fun writeIndexCacheAtomically(file: java.io.File, bytes: ByteArray): Boolean = runCatching {
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            var off = 0
            while (off < bytes.size) {
                val n = minOf(CHUNK_BYTES, bytes.size - off)
                out.write(bytes, off, n)
                off += n
                runCatching { Thread.sleep(1) }        // 让出 IO/CPU，别和前台打字抢
            }
            out.flush()
        }
        if (file.exists() && !file.delete()) {
            tmp.delete()
            Diagnostics.w(TAG, "写索引缓存失败：无法删除旧文件 ${file.name}（本次退回堆内）")
            return@runCatching false
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            Diagnostics.w(TAG, "写索引缓存失败：改名失败 ${tmp.name} -> ${file.name}（本次退回堆内）")
            return@runCatching false
        }
        true
    }.getOrElse {
        Diagnostics.w(TAG, "写入索引缓存失败（本次退回堆内，不影响可用性）: ${file.name} - ${it.message}")
        false
    }

    /** 清掉旧版本（旧 APK mtime）留下的基础索引缓存，避免每更新一次留一份 14.4MB */
    private fun sweepStaleBaseCache(dir: java.io.File, keepName: String) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(BASE_CACHE_PREFIX) && f.name != keepName) {
                    if (f.delete()) Diagnostics.i(TAG, "清理旧索引缓存: ${f.name}")
                }
            }
        }
    }

    /**
     * 记一次用户选择（供键盘在「点候选 / 空格取首候选 / 点补全项」时调用）。
     *
     * 语义与 librime `UserDictionary::UpdateEntry` 对齐：累加 1 次并更新 tick；
     * 具体存储、衰减与排序见 [UserFrequency]。
     */
    fun rememberChoice(word: String) = UserFrequency.remember(word)

    /** 输入法退出/切后台时把未落盘的学习结果刷出去 */
    fun flushUserFrequency() = UserFrequency.flush()

    /** 扩展词库（长词包）是否已加载（供设置页显示状态） */
    fun isExtensionLoaded(): Boolean = extensionLoaded

    private fun loadChars(context: Context) {
        context.assets.open("pinyin_chars.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            loadCharsReader(reader)
        }
    }

    private fun loadCharsReader(reader: java.io.BufferedReader) {
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
                    if (kept.isNotEmpty()) charsBySyllable[syllable] = kept.toTypedArray()
                }
            }
            line = reader.readLine()
        }
    }

    private fun loadCharsText(text: String) {
        loadCharsReader(java.io.BufferedReader(java.io.StringReader(text)))
    }

    /**
     * 加载全量基础词库的二进制索引；成功返回耗时（毫秒），失败返回 -1。
     *
     * 失败**不影响可用性**：此时高频子集（或可选包）仍然可用，只是候选少一些，
     * 调用方据返回值决定 `isFullyLoaded`。异常只记日志、不抛出——绝不因为索引坏掉就让输入法打不出字。
     */
    private fun loadIndex(context: Context): Long {
        val t0 = System.currentTimeMillis()
        return runCatching {
            val indexDir = java.io.File(context.filesDir, INDEX_CACHE_DIR).apply { mkdirs() }
            // 缓存以「APK 文件 mtime」为有效性键（App 一更新必变），故文件名即校验和
            val cache = java.io.File(
                indexDir,
                BASE_CACHE_PREFIX + baseCacheStamp(context) + INDEX_SUFFIX,
            )

            // 先看磁盘缓存：命中就直接内存映射，省掉 1.7s 的 xz 解压和 14.4MB 私有堆
            var idx = PhraseIndex.ofMapped(cache)
            if (idx != null) {
                Diagnostics.i(
                    TAG,
                    "基础索引: 复用磁盘缓存并内存映射（${idx.size} 键 / ${cache.length() / 1024}KB）",
                )
            } else {
                // 没命中（首次用，或 App 刚更新）：解压资产、原子落盘、再映射；映射失败就用堆内
                val bytes = context.assets.open(INDEX_ASSET_XZ).let { raw ->
                    org.tukaani.xz.XZInputStream(raw).use { xz -> readWithYields(xz) }
                }
                val written = writeIndexCacheAtomically(cache, bytes)
                val mapped = if (written) PhraseIndex.ofMapped(cache) else null
                idx = mapped
                    ?: PhraseIndex.of(bytes)
                    ?: error("索引结构异常（magic/版本/偏移不自洽）")
                // 日志必须反映**真实结果**：之前无论落盘成败都打「已写入磁盘缓存」，
                // 排查时会被彻底误导（本次就是因为这条日志，掩盖了缓存被误删的真实原因）。
                if (written && mapped != null) {
                    Diagnostics.i(
                        TAG,
                        "基础索引: 已解压并写入磁盘缓存（${idx.size} 键 / ${bytes.size / 1024}KB / " +
                            "${System.currentTimeMillis() - t0}ms）",
                    )
                } else {
                    Diagnostics.w(
                        TAG,
                        "基础索引: 磁盘缓存不可用（written=$written），本次使用堆内索引" +
                            "（${idx.size} 键 / ${System.currentTimeMillis() - t0}ms）",
                    )
                }
                sweepStaleBaseCache(indexDir, cache.name)
            }
            baseIndex = idx
            // 反向索引不再全量构建：改为查询时记录 recent 候选→键（见 candidatePinyin，省约 50MB）。
            System.currentTimeMillis() - t0
        }.getOrElse {
            Diagnostics.e(TAG, "基础词库索引加载失败，退化为仅用高频子集/可选包: ${it.message}", it)
            -1L
        }
    }

    /**
     * 索引就绪后，把「只是重复索引内容」的高频子集条目从运行时表里摘掉。
     *
     * 判据很直接：该键在索引里的词表以运行时词表开头（子集是全量的前缀）→ 运行时那份是冗余的。
     * 可选包合并过的键不会是前缀，因此不受影响。
     */
    private fun dropRedundantHotEntries() {
        val idx = baseIndex ?: return
        var dropped = 0
        for (key in phrasesByPinyin.keys.toList()) {
            val mine = phrasesByPinyin[key] ?: continue
            val full = idx.wordsFor(key) ?: continue
            if (full.size >= mine.size && full.copyOfRange(0, mine.size).contentEquals(mine)) {
                phrasesByPinyin.remove(key)
                dropped++
            }
        }
        if (dropped > 0) Diagnostics.i(TAG, "高频子集已并入索引，摘除冗余运行时条目 $dropped 条")
    }

    /**
     * 加载高频子集词库；成功返回耗时（毫秒），资产缺失/损坏返回 -1（调用方退回原行为）。
     *
     * 注意：**不加锁、不校验 loaded**——它只由 [load] 在持有锁时调用一次。
     */
    private fun loadHotPhrases(context: Context): Long {
        val t0 = System.currentTimeMillis()
        return runCatching {
            context.assets.open(HOT_PHRASES_ASSET_XZ).let { raw ->
                org.tukaani.xz.XZInputStream(raw).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    loadPhrasesReader(reader, merge = true)
                }
            }
            System.currentTimeMillis() - t0
        }.getOrElse {
            Diagnostics.w(TAG, "高频子集词库加载失败，退回全量加载: ${it.message}")
            -1L
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
        // 任何词库变更都必须让「合并结果缓存」失效，否则同一个进程内换词库后会读到旧候选
        mergedCache.clear()
        // 容量已在声明处预分配（ConcurrentHashMap(600_000)），此处不再重建容器——
        // 重建会让并发读取方拿到另一个实例，正在遍历的旧表被丢弃。
        var line = reader.readLine()
        while (line != null) {
            if (line.isNotBlank()) {
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val pinyin = line.substring(0, tab)
                    val phrases = line.substring(tab + 1).split('|')
                    // 生僻字过滤不在这里做（改到查询期，见 phrasesFor）：
                    // 索引与运行时表都保持原样，过滤只影响最终展示的候选。
                    val kept = phrases
                    if (kept.isNotEmpty()) {
                        if (merge) {
                            // 基础包在前、扩展包追加：同键词条共存，基础候选优先。
                            //
                            // **必须去重**：扩展包与基础包可能收录同一个词
                            // （如「阿尔萨斯」两边都有），直接 `existing + kept`
                            // 会让候选栏出现两个完全相同的候选。
                            // 基础包可能只在索引里（不在运行时表），故两者都要看
                            val existing = phrasesByPinyin[pinyin] ?: baseIndex?.wordsFor(pinyin)
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

    /**
     * 统一读取入口：运行时并入的词优先（高频子集/可选包已与基础包合并过），否则查基础索引。
     *
     * 生僻字过滤在这里做（查询期）而不是加载期：索引因此可以原样复用，
     * 不像旧实现那样「隐藏生僻字」与「显示生僻字」需要两份数据/两次解析。
     * 代价是每次命中多一次位图判断（微秒级）。
     */
    private fun phrasesFor(key: String): Array<String>? {
        mergedCache[key]?.let { return it.ifEmpty { null } }

        val optionals = optionalIndexes
        val raw: Array<String>? = if (optionals.isEmpty()) {
            phrasesByPinyin[key] ?: baseIndex?.wordsFor(key)
        } else {
            // 逐段查找并**按旧语义合并**：运行时（高频子集）→ 基础索引 → 各可选索引按加载顺序，
            // 「先到先得 + 去重」，与上一版「基础在前、扩展追加」的合并结果完全一致。
            val out = LinkedHashSet<String>(32)
            phrasesByPinyin[key]?.let { out.addAll(it) }
            baseIndex?.wordsFor(key)?.let { out.addAll(it) }
            for (idx in optionals) idx.wordsFor(key)?.let { out.addAll(it) }
            if (out.isEmpty()) null else out.toTypedArray()
        }

        val filtered = raw?.let { filterRareChars(it) }
        if (mergedCache.size > 4096) mergedCache.clear()
        mergedCache[key] = filtered ?: EMPTY_WORDS
        return filtered
    }

    /** 查询期生僻字过滤（索引与运行时词一视同仁） */
    private fun filterRareChars(raw: Array<String>): Array<String> {
        val filter = commonChars ?: return raw
        var needFilter = false
        for (w in raw) {
            if (!isLoadableWord(w)) {
                needFilter = true
                break
            }
        }
        return if (needFilter) raw.filter { isLoadableWord(it) }.toTypedArray() else raw
    }

    /**
     * 记录「候选词 → 产出它的拼音键」，供选中后的消费计算与智能预测使用。
     *
     * 只在查询路径调用（每次按键、每个键 ≤ 若干词），开销可忽略；上限清空避免无限增长。
     */
    private fun noteCandidateKeys(words: Array<String>, key: String) {
        if (candidatePinyin.size > 4_096) candidatePinyin.clear()
        for (w in words) candidatePinyin.putIfAbsent(w, key)
    }

        /**
     * 测试注入用：按行文本加载短语表（与 [loadPhrasesReader] 逻辑完全一致）。
     *
     * @param merge true 走**并入**语义 —— 用于验证扩展包/可选包与基础包合并时的
     *              去重与顺序（合并必须去重，否则候选栏会出现两个相同的词）；
     *               false 走**覆盖**语义 —— 对应基础包首次加载。
     *               默认 false，保持既有调用方行为不变。
     */
    internal fun loadPhrasesText(text: String, merge: Boolean = false) {
        loadPhrasesReader(java.io.BufferedReader(java.io.StringReader(text)), merge)
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
                phrasesFor(k2)?.let { words ->
                    result.addAll(words.take(MAX_PHRASES))
                    noteCandidateKeys(words, k2)
                }
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

        // 用户词频学习：把「用户实际选过」的词稳定提到前面（未学习时零开销、零行为变化）
        val ordered = result.toList()
        return Result(UserFrequency.rank(ordered.toTypedArray()).toList(), syllables, partial)
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
        // 优先用查询时记录的「候选→键」（基础词走索引后没有全量反向索引）
        val lastPinyin = candidatePinyin[lastWord] ?: wordToPinyin[lastWord] ?: return emptyList()
        val out = LinkedHashSet<String>()
        val wordBytes = lastWord.toByteArray(Charsets.UTF_8)
        val pinyinBytes = lastPinyin.toByteArray(Charsets.UTF_8).size

        // 扫描顺序与旧的 keysStartingWith 保持一致（影响 take(N) 的先后）：
        // 基础索引 → 各可选索引 → 运行时表（高频子集 / 未摘除的合并键）。
        //
        // 索引侧走「区间枚举 + 字节级后缀收集」（吸收 librime `Prism::ExpandSearch` 的 Match 思路）：
        // **不实例化键字符串**，只对命中的词解码后缀。旧写法在短前缀下会一次性建出数千个 String
        // （实测 keysWithPrefix("ni") = 6185 键 / 2.18ms）——这条路径将来若给单字候选开预测会直接踩到。
        for (idx in baseAndOptionalIndexes()) {
            if (out.size >= MAX_PREDICTIONS) break
            idx.forEachRangeWithPrefix(lastPinyin) { keyByteLen, wordsFrom, wordsTo ->
                if (keyByteLen > pinyinBytes) {
                    idx.collectLongerSuffixes(wordsFrom, wordsTo, wordBytes, out)
                }
                out.size < MAX_PREDICTIONS          // false = 提前结束扫描
            }
        }

        // 运行时表：键数量小（高频子集 + 可选包并入项），沿用有序 List 二分定位前缀区
        var lo = lowerBound(sortedPhraseKeys, lastPinyin)
        while (lo < sortedPhraseKeys.size && out.size < MAX_PREDICTIONS) {
            val key = sortedPhraseKeys[lo]
            if (!key.startsWith(lastPinyin)) break
            if (key.length > lastPinyin.length) {
                addLongerSuffixes(phrasesFor(key), lastWord, out)
            }
            lo++
        }
        return out.take(MAX_PREDICTIONS)
    }

    /** 参与预测的索引：基础索引在前、可选索引在后（与旧 keysStartingWith 的顺序一致） */
    private fun baseAndOptionalIndexes(): List<PhraseIndex> {
        val base = baseIndex
        if (optionalIndexes.isEmpty()) return if (base == null) emptyList() else listOf(base)
        val out = ArrayList<PhraseIndex>(1 + optionalIndexes.size)
        base?.let { out.add(it) }
        out.addAll(optionalIndexes)
        return out
    }

    /** 运行时表的「更长后缀」收集（与索引侧 collectLongerSuffixes 语义一致） */
    private fun addLongerSuffixes(
        words: Array<String>?,
        lastWord: String,
        out: MutableCollection<String>,
    ) {
        for (phrase in words.orEmpty()) {
            if (phrase.length > lastWord.length && phrase.startsWith(lastWord)) {
                out.add(phrase.substring(lastWord.length))
            }
        }
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
     * 词库约束分词：**整串必须是词库键**时，在合法切分里取**音节数最少**的那种；否则返回 null
     * （调用方退回贪心切分）。
     *
     * 说清它实际在做什么：
     *  - 打分里的 `key = path.joinToString("")`，而 path 是输入的**一种划分**，join 后**恒等于输入本身**，
     *    所以 `hit` 对所有路径都一样，分数只差 `-path.size`；末尾 `phrasesFor(整串) != null` 的门禁
     *    也是所有路径共有 ⇒ **实际只比音节数**（先枚举者胜；DFS 按最长音节优先，多数情况下
     *    第一条完整路径即最优）。
     *  - 因此它**不是**「按词库短语数打分」的分词，也不要按那个思路去改：
     *    实测把打分改成「Σ 各前缀命中数」会**退化**（每个音节边界都加分 → 切得越碎分越高），
     *    真实词库下会切出 `tianqi → ti-a-n-qi`、`beijingdaxue → bei-ji-ng-da-xue` 这类坏结果。
     *    详见 `docs/从 librime 可吸收的设计.md` 第二节。
     *  - 原 KDoc 举的例子 `xuni → [xu, ni]` 本身不成立：`xun-i` 里的 `i` 不是合法音节，
     *    实测 `xuni` **只有一种**合法切分（[xu, ni]）。
     *
     * @param input 必须全部由合法音节组成（否则部分无法切分，返回 null）
     */
    private fun dictionarySegmentation(input: String): List<String>? {
        if (input.isEmpty()) return null
        // 候选切分路径（DFS 枚举，上限防爆炸）
        val paths = ArrayList<List<String>>()
        enumerateSegmentPaths(input, paths)
        if (paths.isEmpty()) return null
        // 评分：整串拼词库短语数（词命中优先），其次音节数（多音节更自然）
        var best: List<String>? = null
        var bestScore = 0
        for (path in paths) {
            val key = path.joinToString("")
            val hit = phrasesFor(key)?.size ?: 0
            val score = hit * 1000 - path.size  // 词命中为主，音节少略优
            if (score > bestScore) {
                bestScore = score
                best = path
            }
        }
        // 只有真实命中词库的切分才采用（否则贪心）
        return best?.takeIf { phrasesFor(it.joinToString("")) != null }
    }

    /**
     * DFS 枚举合法音节切分（最长音节 6 字符），路径上限 [MAX_SEGMENT_PATHS] 防爆炸。
     *
     * 上限的**真实作用**：因为 [dictionarySegmentation] 最终只比音节数、且这里按最长音节优先，
     * 第一条完整路径通常即最优，上限只是防御措施，**不是**「截断路径导致选错」的隐患所在
     * （实测 14 个常见语料里仅 `jintiantianqi`（18 种切分）超过上限）。
     * 若将来真的要改成按词库分值的分词，必须同时换成「音节图 + 动态规划」（无限枚举路径），
     * 并先解决上面提到的打分退化问题。
     */
    private fun dfsSegment(
        input: String,
        pos: Int,
        cur: MutableList<String>,
        out: MutableList<List<String>>,
        parseable: BooleanArray,
    ) {
        if (out.size >= MAX_SEGMENT_PATHS) return
        if (pos == input.length) {
            out.add(cur.toList())
            return
        }
        val maxEnd = (pos + 6).coerceAtMost(input.length)
        var end = maxEnd
        while (end > pos) {
            val candidate = input.substring(pos, end)
            if (parseable[end] && validSyllables.contains(candidate)) {
                cur.add(candidate)
                dfsSegment(input, end, cur, out, parseable)
                cur.removeAt(cur.size - 1)
            }
            end--
        }
    }

    /**
     * 枚举合法音节切分（含 [MAX_SEGMENT_PATHS] 上限）。
     *
     * 抽成 internal 是为了能和应用"未剪枝的参考实现"逐例对拍——剪枝只允许砍掉
     * 到不了终点的分支，结果必须与不剪枝时**完全一致**，这一点用对拍比人工推演可靠。
     */
    internal fun enumerateSegmentPaths(input: String, out: MutableList<List<String>> = ArrayList()): List<List<String>> {
        dfsSegment(input, 0, ArrayList(), out, suffixParseable(input))
        return out
    }

    /**
     * 后缀能否切成合法音节序列：第 i 位表示 input 从 i 起的后缀切得通。自后向前一趟 DP，O(n·6)。
     *
     * [dfsSegment] 用它在**进入分支前**剪枝。不剪的话，「16 条完整路径」的上限管不住这种输入：
     * 前缀能切出很多合法音节、尾巴却切不通时，一条完整路径都找不到，上限永不触发，
     * DFS 会把所有前缀分支走到底。实测（JVM）：25 字符 8.6ms、31 字符 36.8ms，每加 6 字符约 ×4~5，
     * 真机上就是每按一键卡几百毫秒到几秒 —— 用户看到的就是「拼音打错了/打太长，字母键卡住」。
     * 走不通的分支本来就到不了终点，剪掉它**不改变任何切分结果**，只是把代价压回 O(n·6)。
     */
    private fun suffixParseable(input: String): BooleanArray {
        val n = input.length
        val ok = BooleanArray(n + 1)
        ok[n] = true
        for (i in n - 1 downTo 0) {
            var end = (i + 6).coerceAtMost(n)
            while (end > i) {
                if (ok[end] && validSyllables.contains(input.substring(i, end))) {
                    ok[i] = true
                    break
                }
                end--
            }
        }
        return ok
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

        // 1) 词语候选：候选→键（查询时记录）优先，兼容旧反向索引（运行时并入的词）
        if (candidate.length > 1) {
            (candidatePinyin[candidate] ?: wordToPinyin[candidate])?.let { wp0 ->
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
                phrasesFor(key)?.let { words ->
                    result.addAll(words.take(MAX_PHRASES))
                    noteCandidateKeys(words, key)
                }
            }
            if (result.size >= MAX_COMPLETION_RESULTS) break
        }
        return result.toList()
    }
}

/**
 * 双拼双拼方案。
 *
 * 键位数据见 [SHUANGPIN_TABLES]（由 `tools/dict_builder/gen_shuangpin_tables.py` 从
 * `docs/rime-ice/double_pinyin*.schema.yaml` 的 `speller/algebra` 生成），本枚举只负责
 * 「方案身份 + 持久化取值 + 展示名」，不含任何键位逻辑。
 *
 * [prefsValue] 写入 `Prefs.shuangpinScheme`：**只能追加，不能改动既有取值**（老配置靠它迁移）。
 */
enum class ShuangpinScheme(
    val prefsValue: Int,
    /** 设置页与日志用的全名 */
    val displayName: String,
    /** 键盘功能面板按钮上的短名（按钮文字随方案变化） */
    val shortName: String,
    private val tableKey: String?,
) {
    /** 26 键全拼（非双拼，输入即全拼） */
    QUANPIN(0, "26 键全拼", "全拼", null),
    ZIRANMA(1, "自然码双拼", "自然码", "ZIRANMA"),
    FLYPY(2, "小鹤双拼", "小鹤", "FLYPY"),
    SOGOU(3, "搜狗双拼", "搜狗", "SOGOU"),
    MSPY(4, "微软双拼", "微软", "MSPY"),
    ZIGUANG(5, "紫光双拼", "紫光", "ZIGUANG"),
    ABC(6, "智能 ABC 双拼", "ABC", "ABC"),
    JIAJIA(7, "拼音加加双拼", "加加", "JIAJIA"),
    ;

    /** 是否双拼方案（全拼无需转换） */
    val isShuangpin: Boolean get() = tableKey != null

    /**
     * 该方案的键位数据；全拼方案为 null（[ShuangpinTable] 是 internal，故此处同样 internal）。
     *
     * 表是**惰性构建**的（`Lazy.value`）：首次访问某方案时才建它那一张，避免键盘弹出路径
     * 一次性建 7 张（详见 ShuangpinSchemes.kt 文件头与 [Shuangpin.warmUpAll]）。
     */
    internal val table: ShuangpinTable? get() = tableKey?.let { SHUANGPIN_TABLES[it]?.value }

    companion object {
        /** 全部双拼方案（设置页下拉与键盘循环都用它，保证顺序一致） */
        val SHUANGPIN_ONLY: List<ShuangpinScheme> = entries.filter { it.isShuangpin }

        /** 由持久化取值还原方案；未知取值一律回落到自然码（老配置即 `useShuangpin = true`） */
        fun of(prefsValue: Int): ShuangpinScheme =
            entries.firstOrNull { it.prefsValue == prefsValue } ?: ZIRANMA

        /**
         * 键盘功能面板「全拼 / 双拼」按钮的二态切换。
         *
         * **面板只决定「用不用双拼」，不选具体方案**（用户要求：方案只能在设置页改）。
         * 切回双拼时取设置页选定的 [configured]（若它也是全拼则退回自然码，保证一定切得过去）。
         */
        fun toggle(current: ShuangpinScheme, configured: ShuangpinScheme): ShuangpinScheme =
            if (current.isShuangpin) {
                QUANPIN
            } else {
                configured.takeIf { it.isShuangpin } ?: ZIRANMA
            }
    }
}

/**
 * 双拼 ↔ 全拼转换（**纯查表**）。
 *
 * 键位数据来自 [SHUANGPIN_TABLES]（rime-ice schema 生成），本对象不再持有任何硬编码键位，
 * 因此新增方案只是多一张表（见生成器脚本）。全拼方案（[ShuangpinScheme.QUANPIN]）原样返回输入。
 *
 * 规则：
 *  - 每两键一个音节，查 [ShuangpinTable.codes]；
 *  - 末尾只剩一键时按「声母键」处理（`v` → `zh`，与旧实现一致）；非声母键原样保留
 *    （候选预显行为不变），非字母键（如分号）忽略；
 *  - 非法组合停止转换并返回已转换的前缀（容错，不抛异常）；
 *  - 输出全部为 ASCII：撮口呼 ü 写作 u（jqxy 后）或 v（l/n 后），与词库键一致。
 *
 * 本类完全自包含（不依赖词库与 Android 资源），可脱离设备单测。
 */
object Shuangpin {

    /** 把 [input] 按 [scheme] 转换成全拼音节串（纯 ASCII，小写） */
    fun toQuanpin(input: String, scheme: ShuangpinScheme): String {
        val table = scheme.table ?: return input.lowercase()
        val raw = input.lowercase()
        if (raw.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < raw.length) {
            val syllable = table.codes[raw.substring(i, i + 2)] ?: break
            sb.append(syllable)
            i += 2
        }
        if (i < raw.length) {
            val key = raw[i]
            sb.append(
                table.initialOf(key) ?: if (key in 'a'..'z') key.toString() else ""
            )
        }
        return sb.toString()
    }

    /** 该方案是否有键位落在分号键上（搜狗/微软/紫光的 `ing`），键面需要显示分号键 */
    fun needsSemicolon(scheme: ShuangpinScheme): Boolean = scheme.table?.needsSemicolon == true

    /**
     * 预热：把全部方案的键位表构建出来，返回耗时（毫秒）。
     *
     * 供 IME 服务创建时在**后台线程**调用——表总共约 3,000 条目，一次全建有几十毫秒量级开销，
     * 而键盘视图是在 `onCreateInputView`（键盘首次弹出）里创建的，在那里同步建表会拖慢首次弹出。
     *
     * 不预热也能正常工作（首次访问会按需同步构建该方案的表），预热只是把这笔开销挪到后台：
     * 最坏情况是预热还没跑完用户就弹出键盘，此时只构建**当前方案**那一张（约为全部开销的 1/7）。
     */
    fun warmUpAll(): Long {
        val startNs = System.nanoTime()
        SHUANGPIN_TABLES.values.forEach { it.value }
        return (System.nanoTime() - startNs) / 1_000_000
    }
}
