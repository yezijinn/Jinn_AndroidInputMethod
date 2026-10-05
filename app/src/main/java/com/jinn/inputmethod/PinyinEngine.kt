package com.jinn.inputmethod

import android.content.Context
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * 拼音输入引擎：词库加载、候选查询、自然码双拼转换。
 *
 * 数据源（均为宽松开源许可，已预处理成紧凑 asset；2026-09-27 起重构，生成器
 * `tools/dict_builder/build_dicts.py`）：
 *  - `pinyin_index.bin.xz`：内置短语索引（`词库_第1部分.txt`，40 万条）
 *  - `pinyin_chars.txt.xz`：音节 → 单字候选（三档合并，按频率降序）
 *  - `common_chars.txt.xz` / `tier2_chars.txt.xz` / `tier3_chars.txt.xz`：档 1 / 档 2 / 档 3 字表
 *  - `simp_trad.txt.xz`：简繁映射（「只使用繁体字」）
 *  - `pinyin_syllables.txt.xz`：合法音节全集（用于全拼切分与双拼校验）
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
     * 单字候选上限（每个音节）。必须足够大以覆盖该音节的常用字，例如 ji 音的
     * 「基/寄」按词库频率排在第 19/20 位，原 12 会截断导致候选缺失。
     * 候选栏为横向滚动容器，可承载较多候选。
     */
    private const val MAX_CHARS = 60
    private const val MAX_PHRASES = 12

    /**
     * 模糊音变体的单字上限：比精确单字的 [MAX_CHARS] 小得多。
     *
     * 变体单字是「顺带给的兜底」，按 60 上限会把候选栏灌满、把精确单字推到更远；
     * 真要找的字通常在变体音节的高频前几个里。internal 供单测钉住裁剪行为。
     */
    internal const val MAX_FUZZY_CHARS = 20

    /** 合并缓存的「确实没有」哨兵（避免同一缺失键反复走逐段查找） */
    private val EMPTY_WORDS = emptyArray<String>()

    /**
     * 常用字表 asset 名（《通用规范汉字表》一级+二级，6500 字）。
     *
     * 4 个纯文本资产统一存 `.xz`：APK 内 deflate 后合计 63.8KB → xz 42.8KB（省约 21KB）。
     * 体积是硬约束（5MB 上限），这笔省下来的是余量。
     */
    private const val COMMON_CHARS_ASSET = "common_chars.txt.xz"

    /** 单字表 asset（`字<TAB>拼音`，`tools/dict_builder/export_dicts.py` 产出） */
    private const val CHARS_ASSET = "pinyin_chars.txt.xz"

    /** 合法音节表 asset（421 个音节；双拼表生成器脚本也读它。原 422，`junding` 脏项已剔除） */
    private const val SYLLABLES_ASSET = "pinyin_syllables.txt.xz"

    /**
     * 可选字档 asset（设置页「加更多生僻字」页内的两个开关，2026-09-27 起）。
     *
     * 档 2 = `docs/所有词库/单字/单字注音_二级简体.txt`（837 字 / 885 条）；档 3 = 三级档（2,923 字）。
     * 两档默认关、按开关在查询期放行；档 3 依赖档 2（设置页强制，见 `RareCharsActivity`）。
     * 档外字（繁体 / 异体 / 日韩 / 扩展区）不再随包。
     */
    private const val TIER2_CHARS_ASSET = "tier2_chars.txt.xz"

    /** 档 3 字表（可选，依赖档 2） */
    private const val TIER3_CHARS_ASSET = "tier3_chars.txt.xz"

    /**
     * 简繁映射 asset（`简体<TAB>繁体`，2,714 对；`tools/dict_builder/build_dicts.py` 生成）。
     *
     * 源是 OpenCC `STCharacters`（万象随包，**繁体优先**，右侧多候选取首项）＋人工覆盖层
     * `docs/所有词库/单字/简繁对照.txt`（两边零冲突，人工表只作覆盖；BUG.md L-37）。
     *
     * 「只使用繁体字」（[Prefs.useTraditional]）开启时，候选里的简体字按本表替换 ——
     * 查询期转换，开关即时生效（[setTraditional]），不改词库数据。
     */
    private const val SIMP_TRAD_ASSET = "simp_trad.txt.xz"

    /**
     * 简繁**词级**消歧 asset（`简词<TAB>繁词`，9,139 条；同上生成。条数口径 = 资产里通过加载器
     * 初筛的行数，另有 4 行 `#` 注释；2026-09-29 实测）。
     *
     * 字级映射是 1:1 的（发→發），但「头发」应作「頭髮」、「干净」应作「乾淨」—— 本表只收
     * 「逐字映射会出错」的词条，转换时整词优先命中、未命中再逐字兜底（见 [toDisplay]）。
     * 两类条目：
     *  ① `STPhrases` 里「逐字映射 != 词级结果」的词级消歧（同上）；
     *  ② 按**显示形**补收的往返一致条目（覆盖→覆蓋、乾隆→乾隆）：同一显示形下有多个词典词时
     *     候选转换后**去重**，只有词频最高的那个可见 ⇒ 反查胜者也必须是它，否则繁体模式学过的词
     *     切回简体后词频键落到别的词上（BUG.md L-93 / L-94）。
     * 其中 7 条（原有）是「词级结果 == 简体原形」（天台 / 天后 / 海里…）：字级表补齐后**正是这类**
     * 需要覆盖（逐字会把「天台」转成「天臺」），不能按「原形即相等」提前跳过。
     */
    private const val SIMP_TRAD_WORDS_ASSET = "simp_trad_words.txt.xz"

    /**
     * 繁→简**单字**映射 asset（`繁<TAB>简`，2,965 项；同上生成，只收 BMP 字 —— 与加载端 `length == 1` 同口径）。
     *
     * [setSimpTradText] 从字级正向表（2,714 对）反推出的反查表覆盖不全：词级表引入的
     * 繁体字（去重 3,087 个）里，只靠反推表有 **2,011 个**折不动；本表（2,965 项）覆盖后仍有
     * **1,843 个**看不到（髮 / 乾 / 淨 / 鬚…）——「頭髮」折回只得「头髮」，
     * 词频键就此跑偏（简体模式、消费区间、预测全都认不出）。本表由 OpenCC TSCharacters
     * 生成，加载时**覆盖**字级反推的结果（见 [setSimplifyText]）。
     * （三个数均为 2026-09-29 按加载器口径实测：反推表覆盖 2,700 个繁体字、与本表合并后 2,969 个；
     * 见 BUG.md L-113；词级表从 8,100 条扩到 9,139 条后这三个数随之变大。）
     */
    private const val SIMPLIFY_ASSET = "simplify.txt.xz"

    /**
     * 全量基础词库的二进制索引 asset（`tools/dict_builder/build_dict_index.py` 构建期产出）。
     *
     * 2026-09-27 起源改为 `docs/所有词库/短语/词库_第1部分.txt`（40 万条 / 29.9 万键，xz 2.1MB）：
     * 体积较旧的 4.4MB 索引减半，单段加载约 0.5~0.7s，因此**不再需要高频子集**（原 `hot_phrases.txt.xz`
     * 与两段式加载一并退场）。
     */
    private const val INDEX_ASSET_XZ = "pinyin_index.bin.xz"

    /** 扩展词库文件名（用户下载/导入后放在 filesDir 下，可选） */
    /** 可选词库目录名（filesDir 下）：每类词库一个 xz 文件，供「分类词库」页按需下载 */
    const val OPT_DICT_DIR = "dicts"

    /** 可选包的索引缓存目录（filesDir 下）：首次构建后落盘，后续启动直接读 */
    private const val INDEX_CACHE_DIR = "index"

    /** 索引缓存文件后缀（源文件 `ext.xz` → 缓存 `ext.xz.idx`） */
    private const val INDEX_SUFFIX = ".idx"

    /** 原子写的临时件后缀（`ext.xz.idx` → `ext.xz.idx.tmp`）；清扫侧按同一后缀识别残留 */
    private const val TMP_SUFFIX = ".tmp"

    /** 基础索引磁盘缓存前缀（文件名形如 `base.<APK mtime>.idx`） */
    private const val BASE_CACHE_PREFIX = "base."

    /** 分片大小：分片之间让出 CPU/IO，避免后台重活把前台打字挤成卡顿 */
    private const val CHUNK_BYTES = 256 * 1024

    /**
     * 分片读完整条解压流，每攒够 1MB 主动睡 1ms 让出 CPU。
     *
     * 不这么做的话，基础索引解压是一次 1.8 秒不给喘息的连续 CPU 冲击（还带一个 14.7MB 大分配）：
     * 真机实测 App 更新后首次启动、键盘刚弹出来就打字时，帧 p99 从 14ms 飙到 300ms（janky 12%）。
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
     * 已经尝试装过的可选包：**包名 → 源文件 stamp**（BUG.md L-155 / L-154 的另一半）。
     *
     * 只跳过「同一个字节的包」：重下 / 换包会换 stamp ⇒ 自动再试一次；失败的包 stamp 没变，
     * 不会进这个集合，因此也不会形成重试风暴。原先是个布尔闸（试过一次就再也不看）。
     */
    private val optionalAttempted = HashMap<String, Long>()

    /**
     * 上次尝试里**找到文件但没读进来**的包：包名 → 该身份下的失败次数（BUG.md L-155 / L-167）。
     *
     * 失败不能只记「试过」：那样闸门会永久早退，而词库页写着「空闲时自动装载」——等于骗用户。
     * 记次数就能**有界重试**：同一个字节的包最多试 [MAX_OPTIONAL_RETRIES] 次，
     * 换过文件（重下）则计数清零重来。
     */
    private val optionalFailed = HashMap<String, Int>()

    /** 同一个（未变过的）包最多尝试几次 —— 一次首发 + 一次重试；再多只是白烧 CPU（解压失败是确定性的） */
    internal const val MAX_OPTIONAL_RETRIES = 2

    /**
     * 这个包这次要不要尝试装载（纯函数，便于单测）：
     * 身份变了要试；身份没变但**上次失败且还没试满**也要试；成功过且没变则不必再试。
     */
    internal fun packShouldRetry(attemptedStamp: Long?, currentStamp: Long, failedTimes: Int?): Boolean =
        packNeedsAttempt(attemptedStamp, currentStamp) ||
            (failedTimes != null && failedTimes < MAX_OPTIONAL_RETRIES)

    /** 上次尝试里**找到文件但没读进来**的包名（原先只有 logcat 知道，见 BUG.md L-155） */
    @Volatile
    private var optionalFailedPacks: Set<String> = emptySet()

    /** 单个包是否需要（重新）尝试装载：身份变了就要（纯函数，便于单测） */
    internal fun packNeedsAttempt(attemptedStamp: Long?, currentStamp: Long): Boolean =
        attemptedStamp != currentStamp

    /**
     * 全量基础包是否已 merge 完成。
     *
     * 单段加载下它与 [loaded] 基本同时置位；保留独立标志是因为索引那一段可能失败 ——
     * 此时 `loaded=true, fullLoaded=false`，UI 据此提示「词库补全中」并允许补试。
     */
    @Volatile
    private var fullLoaded = false

    /** 是否已可输入（单字表 + 音节表就绪即为 true） */
    val isLoaded: Boolean get() = loaded

    /** 是否已全量就绪（false 且 [isLoaded] 为 true 时，UI 可提示「词库补全中」） */
    val isFullyLoaded: Boolean get() = fullLoaded

    /**
     * 是否正有一次加载在进行（两段式全程或索引补试）。
     *
     * 供 IME 侧的补试闸门使用：冷启动那 6~10.7s 里用户可能反复弹键盘，把「正在加载」
     * 也当成失败去补试会白耗重试次数（上限 3 次），真正失败时反而没有补试机会。
     */
    @Volatile
    var isLoading: Boolean = false
        private set

    /** 可选词库包是否正在后台加载（防重复触发） */
    @Volatile
    private var optionalLoading = false

    /**
     * 可选词库的「检查-置位」专用锁。
     *
     * 不能用 `this`：`load()` 全程持 `synchronized(this)`（含秒级的索引解压/落盘），
     * 而本标志的调用点全在主线程（息屏广播 / 键盘收起后闲置 / 兜底超时），
     * 首次加载期间触发空闲信号会把主线程阻塞在锁上数秒。
     * 专用锁只保护标志位的「检查-置位」原子性，两个标志仍是 @Volatile。
     */
    private val optionalLock = Any()

    /**
     * 以下容器必须是并发安全的。
     *
     * 可选词库由后台线程延迟加载（[loadOptionalAsync]，基础包就绪 5 秒后开始），
     * 而此刻用户通常正在打字，主线程在 [query] 等路径上并发读取同一批容器。
     * 用普通 HashMap 时，并发写入触发的扩容可能让桶链表成环（读方死循环、IME 卡死），
     * 或抛 ConcurrentModificationException。
     */
    private val charsBySyllable = ConcurrentHashMap<String, Array<String>>(1024)

    /**
     * 运行时并入的拼音串 → 词语（按频率降序）。
     *
     * 只装用户下载的可选词库包（2026-09-27 起：原「高频子集」已随两段式加载退场）。
     * 内置基础包不在这里，而是 [baseIndex]（二进制索引，只读）；两者由 [phrasesFor] 统一读取，
     * 因此「基础在前、运行时词在后且去重」的候选顺序与旧实现完全一致。
     */
    private val phrasesByPinyin = ConcurrentHashMap<String, Array<String>>(64_000)

    /**
     * 全量基础词库索引（构建期解析好的二进制，见 [PhraseIndex]）。
     *
     * 不可变、可多线程并发读；加载失败时为 null（此时仅单字候选 / 可选包可用，不影响打字）。
     */
    @Volatile
    private var baseIndex: PhraseIndex? = null

    /**
     * 可选词库包的索引（按加载顺序；原子整体替换，供查询并发读取）。
     *
     * Stage 2：可选包不再并入 [phrasesByPinyin]（114 万条 HashMap 是内存大头），
     * 改为各自建索引，首次加载时流式构建并缓存到 `filesDir/index/`，之后直接读缓存。
     */
    @Volatile
    private var optionalIndexes: List<PhraseIndex> = emptyList()

    /**
     * 合并结果小缓存：键 → 「运行时 ∪ 基础索引 ∪ 可选索引」去重后的词表（空数组表示确实没有）。
     *
     * 超限清空（与 `completionCache` 同一套防膨胀写法）。
     *
     * 必须是并发容器：读取方是主线程（查询/补全/预测），而失效点在 [finalizeLoad] ，
     * 词库加载线程（基础索引、可选包索引）都会走到那里 `clear()`。
     * 裸 HashMap 在「主线程 get 的同时后台 clear」下会出现丢更新、错值甚至桶链表成环卡死，
     * 与项目里 `charsBySyllable` 当初的坑完全同类（见其 KDoc）。
     */
    private val mergedCache = java.util.concurrent.ConcurrentHashMap<String, Array<String>>(256)

    /**
     * 词库内容的世代号：任何改动词库内容 / 索引引用的路径都要 +1（统一走 [invalidateMergedCache]）。
     *
     * 查询线程（主线程）拿它判断「算这个键的过程中词库有没有变过」，变过就不能把结果写回缓存。
     */
    @Volatile
    private var dataGeneration = 0

    /**
     * 词库内容变更后作废「合并结果缓存」：所有失效路径的唯一入口。
     *
     * 顺带推进 [dataGeneration]：查询线程可能正在用旧数据算某个键的结果，算完再回写就会把
     * 变更前的旧结果粘死在缓存里，失效点已经过去，之后没有任何东西会再清它
     * （只有缓存超 4096 条整体清空或进程重启才能自愈）。[phrasesFor] 靠世代号识别并放弃回写。
     */
    private fun invalidateMergedCache() {
        dataGeneration++
        mergedCache.clear()
    }

    /** 词 → 拼音键（智能预测用：取已选词的拼音作前缀查更长短语） */
    private val wordToPinyin = ConcurrentHashMap<String, String>(8_192)

    /**
     * 「候选词 → 它的拼音键」的短期映射（只保留最近若干次查询）。
     *
     * 旧实现靠一张 69 万条的「词 → 拼音」全量反向索引来支撑两件事：
     * - 选中候选后计算「消费了多少拼音」（残码保留）
     * - 智能预测
     * 基础词库改为二进制索引后不再建全量反向索引（省约 50MB），改由查询路径顺手记录：
     * 候选本来就是从某个键查出来的，记下来即可，语义还更准（记录的是真正产出该候选的键）。
     * 上限 4096 条，超出即清空（预测/消费只关心最近一次查询）。
     * 语义边界：只对「最近查询里展示过的候选」有效，而真实交互中预测与消费计算
     * 永远发生在用户刚选中的那个候选上，因此与实际需求一致（旧全量索引只是能力过剩）。
     */
    private val candidatePinyin = ConcurrentHashMap<String, String>(4_096)

    /**
     * 「候选词 → 词库里的真实拼音键」，**只在模糊音命中时**才有值。
     *
     * [candidatePinyin] 登记的必须是「用户实际输入的键」（[consumption] 按它算消费区间），
     * 但预测要按该词在词库里的真实键扫延续词：输入 `zangguo` 选到「张国」，它的延续词在
     * `zhangguo*` 键区下，用输入键去扫只会扫空。上限与清空策略同 [candidatePinyin]。
     */
    private val candidateTruePinyin = ConcurrentHashMap<String, String>(256)

    /** 合法音节集合（不含声调） */
    private val validSyllables = ConcurrentHashMap.newKeySet<String>()

    /**
     * 所有合法音节的「真前缀」集合（不含音节本身）。
     *
     * 供 [isTruePrefixOfSyllable] 做 O(1) 判断。原实现每调用一次就遍历整个音节表做
     * startsWith，而该方法在每次按键的查询路径上会被调用若干次（分词、伪完整音节判定、
     * 补全召回），累计是几百次字符串比较。预建集合后降为一次哈希查找。
     */
    /**
     * 必须整体替换引用的不可变快照，不能就地 `clear()` + 逐个 `add()`。
     *
     * 写方是加载线程（`load()` 与 `loadOptionalAsync()` 都会走 [finalizeLoad]），
     * 而 `loaded = true` 在第一段加载后就已发布，用户此时正在打字，读方
     * [isTruePrefixOfSyllable] 在打字热路径上（分词 / 伪完整音节判定 / 补全召回）
     * 读这个集合，且读路径不加锁。就地清空重建会让读方在窗口期内看到「已清空但还没
     * 填回」的中间态：判据返回 false → 分词改走贪心分支、补全召回整段跳过 →
     * 偶发候选变少/切分不同。与 [mergedCache] 的失效点同一类问题，处理方式也一致。
     */
    @Volatile
    private var syllablePrefixes: Set<String> = emptySet()

    /**
     * 默认档单字位图（《通用规范汉字表》一级+二级，6500 字；按 Char 码点直接索引，判定 O(1)）。
     *
     * null 表示位图未就绪（assets 缺失 / 测试未注入）：此时不过滤、一律放行 —— 过滤只是
     * 「省内存 + 精简候选」的优化，不能因为读不到表就让用户打不出字。
     *
     * 用位图而不是 HashSet：词库加载要判定 123 万词条 / 450 万字符，
     * 位图是纯数组下标访问，比哈希查找快得多；65536 位的 BooleanArray 约 64KB，
     * 相比它省下的内存可以忽略。
     *
     * 必须 @Volatile：加载线程在 `load()` 里写它，主线程在 [phrasesFor] 里读它。
     * 目前「碰巧安全」，[loadCharTiers] 在 `loaded = true`（volatile 写）之前完成，
     * 读方先在 `query()` 里读 `loaded`（volatile 读）建立了 happens-before。
     * 但这个保证是隐式的：任何「在 loaded 之后再改 commonChars」的路径都会让它失效
     * （读方拿到旧的 null → 过滤结果错乱）。这里显式声明，把不变量钉在字段上。
     */
    @Volatile
    private var commonChars: BooleanArray? = null

    /** 档 2 位图（837 字，可选档）；是否放行由 [rareTier2] 决定，判定见 [isLoadableChar] */
    @Volatile
    private var tier2Chars: BooleanArray? = null

    /** 档 3 位图（2,923 字，可选档，依赖档 2）；是否放行由 [rareTier3] 决定 */
    @Volatile
    private var tier3Chars: BooleanArray? = null

    /**
     * 可选字档开关（设置页「加更多生僻字」页内两个开关，[Prefs.rareTier2] / [Prefs.rareTier3]）。
     *
     * 位图**总是**加载（两档合计 3,760 字，成本可忽略），开关只决定查询期是否放行 ——
     * 打开 / 关闭即时生效（[setRareTiers]），不必重载词库。
     */
    @Volatile
    private var rareTier2: Boolean = false

    /** 档 3 开关；上层保证「开档 3 必先开档 2」，[setRareTiers] 内再兜一次 */
    @Volatile
    private var rareTier3: Boolean = false

    /**
     * 简繁映射（简体码点 → 繁体字；[SIMP_TRAD_ASSET]，2,714 对）。
     *
     * 用 CharArray 下标直查（65536 项，未映射为 `'\u0000'`）：转换在候选出口逐字符做，
     * 走 HashMap 会让每次查询多上万次装箱查找。
     */
    @Volatile
    private var simpTrad: CharArray? = null

    /** 繁体 → 简体反查（用于「用户词频按简体存储」与预测的真实词反查） */
    @Volatile
    private var tradSimp: CharArray? = null

    /**
     * 最近一次 [setSimplifyText] 的源文本，供 [setSimpTradText] 重建反查表后**重放**。
     *
     * 两张表（字级反推 + 简化表）过去靠调用顺序保证合并结果，单看 [setSimplifyText] 的
     * 实现看不出这个隐含依赖；记下源文本后两者顺序可互换（BUG.md L-51）。
     */
    @Volatile
    private var simplifySrc: String? = null

    /**
     * 简繁词级消歧（简词 → 繁词；[SIMP_TRAD_WORDS_ASSET]，9,139 条）。
     *
     * 只在**整词命中**时生效（未命中回落逐字映射），命中即直接返回 —— 1:1 的字级映射
     * 无法表达「头发→頭髮 vs 发财→發財」这类同字异词，逐字转换会把前者错成「頭發」。
     */
    @Volatile
    private var simpTradWords: HashMap<String, String>? = null

    /**
     * 词级消歧的**反查**（繁词 → 简词；由 [setSimpTradWordsText] 同表反转）。
     *
     * 与 [toDisplay] 对称：[toSimplified] 也要词级优先 —— 「頭髮」折回「头发」（字级反查只得
     * 「头髮」，「髮」不在字级映射的繁体侧）。折不回简体，用户词频就会存下一个谁也用不到的键，
     * 消费区间与预测也会一并退化成兜底「消费全部」。
     */
    @Volatile
    private var tradSimpWords: HashMap<String, String>? = null

    /** 「只使用繁体字」开关（[Prefs.useTraditional]）：候选出口把简体字替换为繁体字 */
    @Volatile
    private var useTraditional: Boolean = false

    /** 因生僻字被过滤掉的词条数（诊断用） */

    /** 扩展词库（「补充短语词库」下载的分片包）是否已加载。后台线程写、UI 线程经 [isExtensionLoaded] 读，需 volatile */
    @Volatile
    private var extensionLoaded = false

    /**
     * 以下有序表都是不可变快照：finalizeLoad 时整体替换引用，而不是原地 clear+addAll。
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

    /**
     * 模糊音容错开关（位掩码，见 [FuzzyPinyin]）：[FuzzyPinyin.NONE] = 关闭，也是出厂默认。
     *
     * @Volatile：设置页（同进程）写入、查询路径读取；[load] 时从 [Prefs] 取一次，
     * 之后由 [setFuzzyMask] 实时更新（不必重启输入法 —— 掩码只影响查询派生，不涉及词库加载）。
     */
    @Volatile
    private var fuzzyMask: Int = FuzzyPinyin.NONE

    /**
     * 实时切换模糊音分组（设置页多选对话框逐项调用）：立即生效。
     *
     * 掩码归一后写入：越界位（旧配置 / 外部写入）不会进查询路径。
     */
    fun setFuzzyMask(mask: Int) {
        val clamped = FuzzyPinyin.clampMask(mask)
        if (clamped == fuzzyMask) return
        fuzzyMask = clamped
        Diagnostics.i(TAG, "模糊音容错: 掩码=$clamped（${Integer.bitCount(clamped)} 组启用）")
    }

    /**
     * 加载词库；幂等，可在后台线程调用。
     *
     * 重入判据是「全量就绪」而不是「能打字了」：索引失败（OOM / 资产读取失败）时
     * [loaded] 已为 true，早先的 `if (loaded) return` 会让所有重试入口（含
     * [loadOptionalAsync] 内的同一次调用）全部失效 —— 用户在整个进程生命周期里只剩单字候选，
     * `isFullyLoaded` 恒 false 让「词库补全中」提示永不消失，只能等系统重建 IME 进程。
     * 这里允许「只补索引」重入，失败每次仍只留一条 E 级日志。
     */
    fun load(context: Context) {
        if (loaded && fullLoaded) return
        isLoading = true
        try {
            doLoad(context)
        } finally {
            // 复位必须在 finally：异常路径漏掉会让 `isLoading` 永久为 true，
            // 此后 IME 侧的补试闸门直接把所有重试挡回，与「静默地永不加载」等价
            isLoading = false
        }
    }

    /** [load] 的实现（完整两段式，或只补第二段）。全程持本对象锁，幂等。 */
    private fun doLoad(context: Context) {
        synchronized(this) {
            if (loaded && fullLoaded) return
            if (loaded) {
                // 上一次只跑到一半：单字表 / 音节表已在位，只补索引
                val indexMs = loadFullIndex(context)
                Diagnostics.i(
                    TAG,
                    "索引补试完成: indexMs=$indexMs 基础键=${baseIndex?.size ?: 0} fullLoaded=$fullLoaded",
                )
                logMemory("索引补试后")
                return
            }
            logMemory("词库加载前")
            val t0 = System.currentTimeMillis()
            val prefs = Prefs(context)
            // 三张档位字表 + 简繁映射一次建档。档 2 / 档 3 默认关、按开关在查询期放行
            // （[isLoadableChar] / [filterRareChars]），因此位图整份加载 —— 但「被过滤的词条
            // 从未进过 HashMap」这条仍然成立：过滤点没变，只是判据从「表外」换成了「档位未开」。
            loadCharTiers(context)
            setRareTiers(prefs.rareTier2, prefs.rareTier3)
            setTraditional(prefs.useTraditional)

            // 模糊音容错：开关走 Prefs（默认 0 = 关），这里取一次供本次进程使用；
            // 设置页改动走 setFuzzyMask 实时生效，不必重启输入法。
            setFuzzyMask(prefs.fuzzyPinyinMask)

            // 用户词频：独立文件 IO（几 ms），放在索引之前；排序在查询期做，
            // 第一键之前一定已就绪，也不占首字延迟预算。
            UserFrequency.load(context, prefs.userLearning)

            // 单段加载：2026-09-27 起源改为「第 1 部分」短语库（xz 2.1MB / 29.9 万键），
            // 解压 + 解析约 0.5~0.7s，原「高频子集先行」的两段式与 hot 资产一并退场。
            // 可选词库包不在这里加载，见 loadOptionalAsync()。
            val indexMs = loadFullIndex(context)
            // 真判据（BUG.md L-151）：「能解析但一行都没解析出来」必须当成**加载失败** ——
            // 只按异常判断的话，资产内容为空 / 格式变了（`loadCharsReader` 里列错位会被静默跳过）
            // 会走到这里置 `loaded = true`：候选栏永远空白，连「词库载入中」的提示都不出现
            // （那两条内联提示分别以 `!isLoaded` / `!isFullyLoaded` 为条件），也没有重试入口。
            // 保持 `loaded = false` ⇒ 界面提示照旧 + 上游最多 3 次补试照常生效。
            loaded = isDictionaryUsable(validSyllables.size, charsBySyllable.size)
            if (!loaded) {
                Diagnostics.e(
                    TAG,
                    "词库加载失败（资产为空或格式变了）：音节=${validSyllables.size} 单字=${charsBySyllable.size} " +
                        "⇒ 保持未加载，界面会提示「词库载入中」并等待补试",
                )
            }
            Log.i(
                TAG,
                "词库加载完成: 音节=${charsBySyllable.size} 基础键=${baseIndex?.size ?: 0} " +
                    "运行时键=${phrasesByPinyin.size} 合法音节=${validSyllables.size}",
            )
            // 反向索引规模一并记录：它是内存占用的大头（百万级 HashMap，Node + 表数组），
            // 评估内存优化前必须先有这个数，不能靠猜。
            Diagnostics.i(
                TAG,
                "词库加载完成: 音节=${charsBySyllable.size} 基础键=${baseIndex?.size ?: 0} " +
                    "运行时键=${phrasesByPinyin.size} 运行时反查=${wordToPinyin.size} " +
                    "合法音节=${validSyllables.size} 索引耗时=${indexMs}ms " +
                    "单字=档1(默认)+档2(${if (rareTier2) "开" else "关"})+档3(${if (rareTier3) "开" else "关"}) " +
                    "拼音=${if (useTraditional) "繁体" else "简体"}",
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
     * 基础包（二进制索引）加载。
     *
     * 单独抽出来是为了**可重试**：加载失败（OOM / 资产读取失败）在早先的实现里没有任何重试
     * 入口，用户会一直只剩单字候选。幂等：单字表 / 音节表只在缺失时补建，[finalizeLoad]
     * 可重复执行。2026-09-27 起索引体积减半（2.1MB xz），不再有「高频子集先行」的第一段。
     *
     * @return 索引加载耗时；失败为 -1（候选退化为单字 + 可选包）
     */
    private fun loadFullIndex(context: Context): Long {
        if (charsBySyllable.isEmpty()) loadChars(context)
        val indexMs = loadIndex(context)
        if (validSyllables.isEmpty()) loadSyllables(context)
        finalizeLoad()
        fullLoaded = indexMs >= 0
        return indexMs
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
     * PinyinEngine 是单例，且词库数据是累加的，同一 JVM 内多个测试类相继注入数据
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
            invalidateMergedCache()
            wordToPinyin.clear()
            candidatePinyin.clear()
            candidateTruePinyin.clear()
            validSyllables.clear()
            syllablePrefixes = emptySet()
            // 有序表是不可变快照，置空引用即可（没有 clear 方法）
            sortedSyllables = emptyList()
            sortedValidSyllables = emptyList()
            sortedPhraseKeys = emptyList()
            completionCache.clear()
            commonChars = null
            tier2Chars = null
            tier3Chars = null
            rareTier2 = false
            rareTier3 = false
            clearSimpTrad()
            useTraditional = false
            // 模糊音是全局开关，同样要复位：否则上一个测试类打开的组会影响后续所有查询
            fuzzyMask = FuzzyPinyin.NONE
            loaded = false
            // 可选词库状态一并复位，否则下一个测试类会误以为可选包已加载
            optionalLoaded = false
            optionalLoading = false
            extensionLoaded = false
        }
    }

    /**
     * 测试注入：从索引字节加载短语表（与 [loadFromTexts] 的文本路径对拍用）。
     *
     * 与真实加载一致：基础词走 [baseIndex]，因此生僻字过滤发生在查询期。
     *
     * @param commonCharsText 档 1 字表文本；传 null 表示位图未就绪（不过滤，加载全部字）
     * @param tier3CharsText 档 3 字表文本（可选档）
     * @param rareChars true = 放行档 2 与档 3（对应设置页两个开关都打开）
     * @param tier2CharsText 档 2 字表文本（可选档；放在末尾以保持既有位置参数调用不受影响）
     * @param rareTier2 / @param rareTier3 两档的放行开关（默认随 [rareChars]）
     *
     * 与 [loadFromTexts] 一样会**复位四张简繁表**（`clearSimpTrad()`）：表是全局单例状态，
     * 不复位就会让上一个用例设过的映射渗进来（BUG.md L-55）。
     */
    internal fun loadFromIndexBytes(
        indexBytes: ByteArray,
        chars: String,
        syllables: String,
        commonCharsText: String? = null,
        tier3CharsText: String? = null,
        rareChars: Boolean = false,
        tier2CharsText: String? = null,
        rareTier2: Boolean = rareChars,
        rareTier3: Boolean = rareChars,
    ) {
        synchronized(this) {
            commonChars = null
            tier2Chars = null
            tier3Chars = null
            clearSimpTrad()
            if (commonCharsText != null) setCommonCharsText(commonCharsText)
            if (tier2CharsText != null) setTier2CharsText(tier2CharsText)
            if (tier3CharsText != null) setTier3CharsText(tier3CharsText)
            setRareTiers(rareTier2, rareTier3)
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
     *
     * 与生产路径同款收尾（`loadOptionalAsync` 设置完索引后也调 [finalizeLoad]）：不重建有序键表的话，
     * **只在可选包里出现**的键查不到（基础包与可选包同键时反而看不出来，测试会假绿）。
     */
    internal fun setOptionalIndexesForTest(indexes: List<PhraseIndex>) {
        optionalIndexes = indexes
        invalidateMergedCache()
        synchronized(this) { finalizeLoad() }
    }

    /**
     * 从多份文本词表加载拼音引擎词典：汉字 / 词组 / 音节 / 各档稀有字。
     * 在 [synchronized] 内重建，避免与查询并发；`rareXxx` 开关控制各稀有档是否纳入。
     */
    internal fun loadFromTexts(
        chars: String,
        phrases: String,
        syllables: String,
        commonCharsText: String? = null,
        tier3CharsText: String? = null,
        rareChars: Boolean = false,
        tier2CharsText: String? = null,
        rareTier2: Boolean = rareChars,
        rareTier3: Boolean = rareChars,
    ) {
        synchronized(this) {
            // 先复位过滤状态，避免同一个 JVM 内多次注入时相互污染
            commonChars = null
            tier2Chars = null
            tier3Chars = null
            clearSimpTrad()
            if (commonCharsText != null) setCommonCharsText(commonCharsText)
            if (tier2CharsText != null) setTier2CharsText(tier2CharsText)
            if (tier3CharsText != null) setTier3CharsText(tier3CharsText)
            setRareTiers(rareTier2, rareTier3)
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
     * 测试注入：以并入语义追加短语表，对应扩展包/可选包与基础包的合并加载。
     *
     * 需要单独一个入口：[loadFromTexts] 内部固定用覆盖语义，
     * 无法验证合并行为，而合并必须去重（基础包与扩展包会收录同一个词，
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
        // 本方法会在可选词库延迟加载时由后台线程再次调用，
        // 而主线程可能正在遍历这些表做前缀补全。
        sortedSyllables = charsBySyllable.keys.sorted()
        sortedPhraseKeys = phrasesByPinyin.keys.sorted()
        sortedValidSyllables = validSyllables.sorted()
        buildSyllablePrefixes()
        // 合并结果缓存是「运行时 ∪ 基础索引 ∪ 可选索引」的派生视图，上述任一来源变了都必须失效。
        // 放在这里而不是只写在各个写入点：基础索引不走 loadPhrasesReader，索引补试 / 可选包
        // 延迟并入都要经过本方法，缓存漏失效会让某个键一直返回上一份候选。
        invalidateMergedCache()
    }

    /** 预建音节真前缀集合（加载时调用一次；合法音节最长 6 字符） */
    private fun buildSyllablePrefixes() {
        // 先建好再整体换引用：读方（主线程热路径）要么拿旧集合、要么拿新集合，
        // 不会看到「已清空但没填回」的中间态（见字段 KDoc）。
        val built = HashSet<String>(1024)
        for (syllable in validSyllables) {
            for (len in 1 until syllable.length) {
                built.add(syllable.substring(0, len))
            }
        }
        syllablePrefixes = built
    }

    // ── 生僻字过滤 ───────────────────────────────────────────

    /**
     * 打开文本资产（`.xz`），关闭 reader 会级联关掉解压流与 asset 流。
     *
     * 4 个纯文本资产的统一入口：字典大小在压缩时按输入规模给（1MB），解压端按流头分配，
     * 不会因为小文件也吃大资产那档的 8MB。
     */
    private fun openAssetText(context: Context, name: String): java.io.BufferedReader =
        org.tukaani.xz.XZInputStream(context.assets.open(name))
            .bufferedReader(StandardCharsets.UTF_8)

    /** 读取三张档位字表与简繁映射 asset（全部建档；放行与否由开关决定，见 [setRareTiers]） */
    private fun loadCharTiers(context: Context) {
        openAssetText(context, COMMON_CHARS_ASSET).use { setCommonCharsText(it.readText()) }
        openAssetText(context, TIER2_CHARS_ASSET).use { setTier2CharsText(it.readText()) }
        openAssetText(context, TIER3_CHARS_ASSET).use { setTier3CharsText(it.readText()) }
        openAssetText(context, SIMP_TRAD_ASSET).use { setSimpTradText(it.readText()) }
        openAssetText(context, SIMP_TRAD_WORDS_ASSET).use { setSimpTradWordsText(it.readText()) }
        // 顺序与结果无关（简化表的源文本会被重放，见 setSimplifyText 的 KDoc）：仍按
        // 「字级 → 词级 → 简化」的阅读顺序排列，别据此推断出依赖关系。
        openAssetText(context, SIMPLIFY_ASSET).use { setSimplifyText(it.readText()) }
    }

    /**
     * 设置可选字档开关（设置页「加更多生僻字」页内两个开关）。
     *
     * 即时生效、无需重载词库：位图早已就位，这里只改放行判据。档 3 依赖档 2 ——
     * 设置页已强制，这里再兜一次，避免「只开档 3」这种非法组合从导入 / 测试注入进来。
     *
     * **必须连带失效 [mergedCache]**：它缓存的是 [filterRareChars] **之后**的词表，而过滤结果依赖
     * 这两个开关。不清的话「关档时查过的拼音键」会一直返回旧词表（内容为空的键被钉死成
     * [EMPTY_WORDS]），用户看到的是「开了档位、词还是打不出来」—— 而单字走 [charsFor] 不经缓存、
     * 当场就能出来，症状很像「开关只对单字生效」（2026-09-27 复现，见 `RareCharsFilterTest`）。
     * 与 [finalizeLoad] / 词库变更同一入口：`invalidateMergedCache` 顺带推进 [dataGeneration]，
     * 让正在算这个键的查询放弃回写。
     */
    fun setRareTiers(tier2: Boolean, tier3: Boolean) {
        rareTier2 = tier2
        rareTier3 = tier2 && tier3
        invalidateMergedCache()
    }

    /** 当前档位开关（诊断 / 测试用） */
    fun rareTierState(): Pair<Boolean, Boolean> = rareTier2 to rareTier3

    /**
     * 统计这批词里有多少条会被**字表闸**丢下（判据与候选出口同一句，见 [isLoadableWord]）。
     *
     * 只可能命中**单字**词条：词组（两个字及以上）已一律放行（2026-10-05 用户要求），
     * 所以这个数通常为 0，只有用户写了档外单字（例如单独一行 `雲 yun`）时才会出现。
     *
     * 位图未就绪（引擎尚未加载）时返回 0：此时谈不上过滤，不误报。
     */
    internal fun countUnloadableWords(words: List<String>): Int {
        if (commonChars == null) return 0
        return words.count { !isLoadableWord(it) }
    }

    /**
     * 设置「只使用繁体字」（[Prefs.useTraditional]）：即时生效，候选出口按 [simpTrad] 替换。
     *
     * 只影响**显示与上屏**的候选文本，不改词库数据、不改拼音键（消费区间按原词登记，
     * 见 [noteCandidateKeys] 的调用时机）。
     */
    fun setTraditional(enabled: Boolean) {
        useTraditional = enabled
    }

    /**
     * 解析字表文本并建立位图。
     *
     * 格式：`#` 开头为注释行，其余行里的字全部计入该档（便于人工维护）。
     * assets 加载与单元测试注入共用本方法。
     */
    internal fun setCommonCharsText(text: String) {
        commonChars = charsToBitmap(text)
    }

    /** 档 2 字表：格式与常用字表一致（`#` 注释 + 汉字行） */
    internal fun setTier2CharsText(text: String) {
        tier2Chars = charsToBitmap(text)
    }

    /** 档 3 字表：格式同上 */
    internal fun setTier3CharsText(text: String) {
        tier3Chars = charsToBitmap(text)
    }

    /**
     * 复位四张简繁表（字级正/反查 + 词级正/反查）。
     *
     * 测试注入 API（[loadFromTexts] / [loadFromIndexBytes]）必须先调它：这四个字段与档位位图同性质
     * ——「上一个用例设过的表」会渗进下一个用例（表是全局单例状态），而漏掉它不会报错，
     * 只会让断言在错误的数据上通过（BUG.md L-55）。
     */
    private fun clearSimpTrad() {
        simpTrad = null
        tradSimp = null
        simpTradWords = null
        tradSimpWords = null
        simplifySrc = null
    }

    /**
     * 简繁映射文本（`简体<TAB>繁体`，`#` 注释行）→ 65536 项 CharArray。
     *
     * 与位图同一取舍：查表在候选出口的热路径上，数组下标比哈希便宜得多。
     *
     * **替换**语义：字级反查表整份重建，随后**重放**已收到的 [setSimplifyText] 源文本 ——
     * 于是本方法与 [setSimplifyText] 的调用顺序与结果无关（见后者的 KDoc）。
     */
    internal fun setSimpTradText(text: String) {
        val toTrad = CharArray(CHAR_TABLE_SIZE)
        val toSimp = CharArray(CHAR_TABLE_SIZE)
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.count { it == '\t' } != 1) continue
            val (j, f) = t.split("\t")
            if (j.length == 1 && f.length == 1 && j != f) {
                toTrad[j[0].code] = f[0]
                toSimp[f[0].code] = j[0]
            }
        }
        simpTrad = toTrad
        tradSimp = toSimp
        // 简化表可能先到：反查表重建后按同一覆盖口径补上，保证两种顺序结果一致（BUG.md L-51）
        reapplySimplifySource()
    }

    /**
     * 繁→简单字映射文本（`繁<TAB>简`，`#` 注释行）→ 并入 [tradSimp]。
     *
     * **合并**而非替换：字级正向表反推出来的那批（2,700 项）里有些是 TSCharacters 没有的
     * 异体对（4 项），打底保留；本表（2,965 项，另有 269 项为反推所无）覆盖并补充。生成侧
     * 规范化词级表时用的是**同一套合并口径**，两边必须一致，否则「折简体」与「生成时判干净」
     * 会得出不同结果。
     *
     * **与调用顺序无关**：源文本记在 [simplifySrc]，[setSimpTradText] 重建反查表后会重放它。
     * 原实现是「并入**当前**反查表」而对方整份重建 ⇒ 先调本方法再调 `setSimpTradText` 会让
     * 本表全部蒸发（字级反推的那批与简化表一起只剩一半，用户症状是「有些字折不回简体」；
     * BUG.md L-51）。顺序现已不设防，别把「必须先调 setSimpTradText」的注释当契约。
     */
    internal fun setSimplifyText(text: String) {
        simplifySrc = text
        val map = tradSimp ?: CharArray(CHAR_TABLE_SIZE)
        applySimplifyInto(map, text)
        tradSimp = map
    }

    /** 把简化表条目覆盖进 [map]（`繁<TAB>简`，逐条校验单字；[setSimplifyText] 与重放共用） */
    private fun applySimplifyInto(map: CharArray, text: String) {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.count { it == '\t' } != 1) continue
            val (f, j) = t.split("\t")
            if (f.length == 1 && j.length == 1 && f != j) map[f[0].code] = j[0]
        }
    }

    /** 反查表重建后重放 [simplifySrc]（简化表先到时靠它补上覆盖项） */
    private fun reapplySimplifySource() {
        val src = simplifySrc ?: return
        val map = tradSimp ?: CharArray(CHAR_TABLE_SIZE)
        applySimplifyInto(map, src)
        tradSimp = map
    }

    /**
     * 简繁词级消歧文本（`简词<TAB>繁词`，`#` 注释行）→ HashMap。
     *
     * 表只收「逐字映射会出错」的词条，所以未命中是常态（走逐字兜底），
     * 命中判定用 `get` 一次哈希，热路径开销可忽略。
     */
    internal fun setSimpTradWordsText(text: String) {
        val map = HashMap<String, String>(16384)
        val entries = ArrayList<Pair<String, String>>(16384)
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.count { it == '\t' } != 1) continue
            val (s, f) = t.split("\t")
            if (s.isNotEmpty() && f.isNotEmpty()) {
                map[s] = f
                entries.add(s to f)
            }
        }
        simpTradWords = map
        tradSimpWords = buildWordBackMap(entries)
    }

    /**
     * 词级**反查表**的构建规则（纯函数，便于单测；BUG.md L-59 / L-93）。
     *
     * 同一繁体形可能对应多个简体候选（词表 9,139 条里有 184 组「同繁体多简体」，如 覆蓋 ← 覆盖 /
     * 复盖），而反查表只能留一个。谁先出现谁胜 —— 这个顺序由**生成端**按「该显示形里词频最高的
     * 词典词在前」排定（`build_dicts.py` 的 `word_back_map` 复算同一判据）：传统模式下候选转换后
     * 会去重，只有词频最高的那个可见，反查胜者跟着它才自洽（BUG.md L-93 / L-94）。规则本身：
     *  - **恒等条目优先**（`s == f`，如「天台 / 天台」）：它们代表「两种模式下写法相同」，
     *    被普通条目按顺序覆盖掉时，`toSimplified` 会把该词折到**另一个词**上（学习键跑偏）；
     *  - 其余**首次写入者胜**：结果只与表内顺序绑定，后续重复行不会改变胜者
     *    （以前的 `back[f] = s` 是后写覆盖，加一行重复就会静默改语义）。
     *
     * 抽成独立函数是为了能在 JVM 单测里直接喂合成表 —— 反查规则曾只藏在加载流程里，改动无从验证。
     */
    internal fun buildWordBackMap(entries: List<Pair<String, String>>): HashMap<String, String> {
        val back = HashMap<String, String>(entries.size * 2)
        for ((s, f) in entries) {
            val old = back[f]
            if (old == null || (old != f && s == f)) back[f] = s
        }
        return back
    }

    /**
     * 显示文本 → 简体（「用户词频按简体存储」、预测反查、消费区间查表用）。
     *
     * 与 [toDisplay] 对称但**恒定生效**：它服务的是内部语义（词频表、`wordToPinyin`、
     * `candidatePinyin`、单字表都是简体），与「只使用繁体字」开关无关。表是 1:1 的，反查无歧义。
     * `internal` 而非 `private`：消费区间与排序的守卫测试直接用它做 keyOf。
     */
    internal fun toSimplified(word: String): String {
        // 词级优先：与 [toDisplay] 对称（「頭髮」折回「头发」，字级反查只会得「头髮」）
        tradSimpWords?.get(word)?.let { return it }
        val map = tradSimp ?: return word
        var changed = false
        val out = CharArray(word.length)
        for (i in word.indices) {
            val c = word[i]
            val t = if (c.code < CHAR_TABLE_SIZE) map[c.code] else '\u0000'
            if (t != '\u0000' && t != c) {
                out[i] = t
                changed = true
            } else {
                out[i] = c
            }
        }
        return if (changed) String(out) else word
    }

    /**
     * 候选出口的简繁转换（「只使用繁体字」开启时生效）。
     *
     * 逐字符映射，未命中或开关关闭时**原样返回同一个对象**（零分配、零分支开销之外的代价）。
     * 转换只改显示与上屏文本；拼音键、消费区间、用户词频都按调用方的时机各自处理。
     */
    private fun toDisplay(word: String): String {
        if (!useTraditional) return word
        // 词级优先：整词命中就返回专用繁体形（「头发」→「頭髮」）—— 字级映射是 1:1 的，
        // 逐字转会把「頭发」错配成「頭發」（發/髮 同源不同义）
        simpTradWords?.get(word)?.let { return it }
        val map = simpTrad ?: return word
        var changed = false
        val out = CharArray(word.length)
        for (i in word.indices) {
            val c = word[i]
            val t = if (c.code < CHAR_TABLE_SIZE) map[c.code] else '\u0000'
            if (t != '\u0000' && t != c) {
                out[i] = t
                changed = true
            } else {
                out[i] = c
            }
        }
        return if (changed) String(out) else word
    }

    /**
     * 取前 [limit] 条候选并应用「只使用繁体字」替换（开关关闭时返回子列表视图，零拷贝）。
     *
     * 所有候选出口（词 / 单字 / 模糊变体 / 补全 / 预测）都必须过这里：漏一处就会出现
     * 「繁体模式下某类候选还是简体」的混合结果。
     */
    private fun displayTake(words: List<String>, limit: Int): List<String> {
        val n = minOf(words.size, limit)
        if (!useTraditional) return words.subList(0, n)
        val out = ArrayList<String>(n)
        for (i in 0 until n) out.add(toDisplay(words[i]))
        return out
    }

    private fun charsToBitmap(text: String): BooleanArray {
        val bits = BooleanArray(CHAR_TABLE_SIZE)
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            for (c in trimmed) {
                val code = c.code
                if (code < CHAR_TABLE_SIZE) bits[code] = true
            }
        }
        return bits
    }

    /**
     * 单个字符是否允许载入。
     *
     *  - ASCII / 数字 / 标点（< 0x3400）：不参与判定，一律放行；
     *  - 汉字（扩展 A 区 0x3400~0x4DBF 与基本区 0x4E00~0x9FFF）：档 1 默认放行，
     *    档 2 / 档 3 按开关放行（[setRareTiers]）；
     *  - BMP 外汉字（代理对）：一律不放行（档外字不再随包，2026-09-27 起）。
     *
     * ⚠ 扩展 A 区**必须**走位图：三档表里共有 39 个扩展 A 字（档 1 = 7、档 2 = 11、档 3 = 21），
     * 若按 `< 0x4E00 → true` 处理，档 2 / 档 3 的 32 个字会绕过开关照常出现（与本节承诺「扩展区
     * 按档位」矛盾，BUG.md L-67）。**单字表零丢字**依据（2026-09-27 脚本实测）：9,373 字全部落在
     * 三档并集内、非 BMP 0 个。
     *
     * ⚠ **词库正文里另有扩展 A 字**（实测 part1-4 共 31 个：档内 11 / 档外 20），其中 20 个字牵涉
     * 37 条词条（「㞎㞎」「㓥房」「㸆大虾」一类）。阈值下调后这些词条随档位判据被过滤 ——
     * 与 1,854 条基本区档外词条同口径，属附带收敛；**不是**「词库不含扩展 A 字」（那是错的，
     * 见 BUG.md L-70；`build_dicts.py` 生成时会打印这类字的条数告警）。
     *
     * 位图未就绪（assets 缺失 / 测试未注入）时一律放行。
     */
    private fun isLoadableChar(c: Char): Boolean {
        val code = c.code
        if (code < 0x3400) return true
        val core = commonChars ?: return true
        if (code > 0x9FFF) return false
        return core[code] ||
            (rareTier2 && tier2Chars?.get(code) == true) ||
            (rareTier3 && tier3Chars?.get(code) == true)
    }

    /**
     * 整词是否允许进候选。
     *
     * **词组（两个字及以上）一律放行**：字表闸只管**单字**（2026-10-05 用户要求）——
     * 用户自己写的词条（如「黄霄雲」里的繁体「雲」）不该因为某个字不在档内就整条消失，
     * 否则自定义词库等于白写；能不能显示由系统字体决定，不由拼音库替用户拦。
     * 单字仍按 [isLoadableChar] 的档位规则放行（设置页档 2 / 档 3 开关继续只影响单字）。
     */
    private fun isLoadableWord(word: String): Boolean {
        if (commonChars == null) return true
        if (word.isEmpty()) return true
        // 按码点数判：扩展 B 区单字（代理对）仍属单字，不能被当成词组放行
        if (word.codePointCount(0, word.length) >= 2) return true
        return isLoadableChar(word[0])
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
     * @param onReady 全部可选包加载完成后的回调（不在主线程，调用方自行切线程）
     */
    fun loadOptionalAsync(context: Context, delayMs: Long = 5000L, onReady: (() -> Unit)? = null): Boolean {
        // 检查-置位必须在同一把锁内：两个线程同时抵达时，
        // 无锁写法会让两边都通过检查、各自启一个加载线程，重复把词库 merge 一遍。
        // 锁用 [optionalLock] 而非 `this`：`load()` 全程持 `this`（含秒级索引解压），
        // 本方法的调用点又全在主线程，首次加载期间的空闲信号会把主线程阻塞数秒。
        val packs = installedOptionalPacks(context.applicationContext)
        synchronized(optionalLock) {
            if (optionalLoading) return false
            // 两道判据（见 packShouldRetry）：包的**身份变过**（新装 / 重下 / 换包）要试；
            // 身份没变但**上次失败且没试满**也要试 —— 否则词库页那句「空闲时自动装载」就是空话。
            val pending = packs.any {
                packShouldRetry(optionalAttempted[it.name], packStamp(it), optionalFailed[it.name])
            }
            if (optionalLoaded && !pending) return false
            optionalLoading = true
        }
        // 线程要活 21~34s（真机实测），期间服务可能被系统销毁重建 ⇒ 线程只该持有**应用** Context，
        // 不该把 IME Service 的 Context 一直挂在后台线程上（BUG.md L-22）。
        val appContext = context.applicationContext
        val thread = Thread {
            try {
                // 后台优先级：可选包是 1.1M 词条级的重活（真机实测 21~34s），
                // 且通常发生在用户已经开始打字之后，必须让路给前台输入。
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                // 失败集合要在 lambda 外可见：onReady 的判据要用它（见下）
                var failed: Set<String> = emptySet()
                val ok = runCatching {
                    // load() 幂等：若基础包已就绪会立即返回
                    load(appContext)
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val t0 = System.currentTimeMillis()
                    failed = loadExtensionDict(appContext)
                    // 可选包引入了新的拼音键，必须重建有序键表：
                    // sortedPhraseKeys 是加载时的快照，不重建则新词不参与前缀补全。
                    synchronized(this) { finalizeLoad() }
                    // 记账：记的是**这次真正看到的**包身份；失败集合对外可见（BUG.md L-155）。
                    // 全部写都在同一把锁内，读侧（闸门）才看得到一致的组合。
                    synchronized(optionalLock) {
                        packs.forEach { f ->
                            val stamp = packStamp(f)
                            if (optionalAttempted[f.name] != stamp) {
                                // 换了文件：上一次身份的失败次数作废（重下后要从头算）
                                optionalFailed.remove(f.name)
                                optionalAttempted[f.name] = stamp
                            }
                            if (f.name in failed) {
                                optionalFailed[f.name] = (optionalFailed[f.name] ?: 0) + 1
                            } else {
                                optionalFailed.remove(f.name)
                            }
                        }
                        optionalFailedPacks = failed
                        optionalLoaded = true
                        optionalLoading = false
                    }
                    Diagnostics.i(
                        TAG,
                        "可选词库延迟加载完成，耗时 ${System.currentTimeMillis() - t0}ms，" +
                            "词语键=${phrasesByPinyin.size}",
                    )
                }.onFailure {
                    Diagnostics.e(TAG, "可选词库延迟加载失败（基础词库不受影响）: ${it.message}")
                }.isSuccess
                // onReady 的判据是「没抛异常 **且** 没有读不进来的包」：
                // 只看前者的话，一个包都没读进来时也会回调，调用方照样打出「可选词库已在后台就绪」，
                // 与同一批 W 级日志自相矛盾（BUG.md L-167）。失败集合非空即视为**未就绪** ——
                // 词库页会显示「索引未就绪」，重试由闸门按身份 + 失败次数推进。
                if (ok && failed.isEmpty()) {
                    runCatching { onReady?.invoke() }.onFailure {
                        Diagnostics.w(TAG, "可选词库就绪回调异常: ${it.message}")
                    }
                }
            } finally {
                // 复位必须在 finally：`setThreadPriority` 在 runCatching 之外，一旦它（或将来
                // 新增的语句）抛异常，optionalLoading 会永远停在 true，此后所有
                // loadOptionalAsync 直接返回，可选词库静默地永不加载且没有任何重试入口。
                // 复位与闸门的「检查-置位」用同一把锁：否则窄交错下第三次调用能再起一个加载线程。
                // （正常路径已在记账块里复位过一次，这里是异常路径的兜底。）
                synchronized(optionalLock) { optionalLoading = false }
            }
        }.apply { isDaemon = true }
        try {
            thread.start()
        } catch (t: Throwable) {
            // `start()` 也可能失败（OOM / 线程数受限），而它在子线程 finally 的保护范围之外：
            // 不复位的话 optionalLoading 永久为 true，同样是「静默地永不加载」。
            synchronized(optionalLock) { optionalLoading = false }
            Diagnostics.e(TAG, "可选词库加载线程启动失败: ${t.message}")
        }
        return true
    }

    /** 可选词库是否已就绪（供 UI 显示状态） */
    fun isOptionalReady(): Boolean = optionalLoaded

    /** 上次尝试里没读进来的可选包（供诊断与守卫读取；不再只活在 logcat 里） */
    internal fun failedOptionalPacks(): Set<String> = optionalFailedPacks

    /** 源文件身份：与索引缓存头里记的 [PhraseIndex.sourceStamp] 同口径 */
    private fun packStamp(f: java.io.File): Long = PhraseIndex.stampOfFile(f.length(), f.lastModified())

    /**
     * 这个**已安装**的可选包，索引是否已就绪（BUG.md L-155）。
     *
     * 判据与加载路径同一句（[PhraseIndex.cacheMatchesSource]）：缓存存在、能映射、且头里的
     * `sourceStamp` 与源文件当前 `length:mtime` 相等。界面上的「已安装」从此不再与
     * 「真的会出词」被显示成同一回事。
     */
    internal fun isOptionalIndexReady(context: Context, packName: String): Boolean =
        PhraseIndex.cacheMatchesSource(
            java.io.File(java.io.File(context.filesDir, INDEX_CACHE_DIR), packName + INDEX_SUFFIX),
            java.io.File(java.io.File(context.filesDir, OPT_DICT_DIR), packName),
        )

    /** 已安装的可选包（`filesDir/dicts` 下的 `*.xz`，按名排序）：装载与启动判定共用同一份口径 */
    internal fun installedOptionalPacks(context: Context): List<java.io.File> =
        java.io.File(context.filesDir, OPT_DICT_DIR)
            .listFiles { f -> f.isFile && f.name.endsWith(".xz") }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * 启动时是否该立刻装载可选包，不等空闲信号（BUG.md L-888 / L-892）。
     *
     * 判据：已装包里每个要么是自定义词库（几十条，重建也是毫秒级），要么索引已就绪
     * （映射复用，清单实测约 0.05s）。任一官方包需要重建时返回 `false` —— 首次构建实测
     * 4~14s，压在首屏就是「刚开机很卡」，那种情形仍旧等息屏 / 收键盘 / 兜底 180s。
     *
     * 拆成纯函数是为了让判定可以直接单测（[indexReady] 由调用侧注入）。
     */
    internal fun optionalShouldLoadNow(
        packNames: List<String>,
        indexReady: (String) -> Boolean,
    ): Boolean = packNames.isNotEmpty() && packNames.all {
        it == CustomDicts.PACK_NAME || indexReady(it)
    }

    /** [optionalShouldLoadNow] 的取用版：直接查已装包与各自的索引缓存 */
    internal fun optionalShouldLoadNow(context: Context): Boolean =
        optionalShouldLoadNow(installedOptionalPacks(context).map { it.name }) {
            isOptionalIndexReady(context, it)
        }

    /**
     * 加载全部可选词库包。
     *
     * 只认一个位置：`filesDir/dicts/` 目录下的全部 xz 文件
     * （每类词库一个文件，由「分类词库」页按需下载写入）。
     * 按文件名排序加载，保证候选顺序可复现。
     *
     * 全部用 merge 模式加载（基础包词条在前，可选包追加，并去重）。
     * 未安装任何可选包不算错误：基础包已覆盖日常输入，仅记一条日志。
     */
    private fun loadExtensionDict(context: Context): Set<String> {
        val packs = installedOptionalPacks(context)

        val indexDir = java.io.File(context.filesDir, INDEX_CACHE_DIR).apply { mkdirs() }

        // 清理：源包已被删除的索引缓存（用户可能只删了词库文件）
        runCatching {
            val alive = packs.map { it.name }.toSet()
            // 带上 mtime：源包仍在的 `.idx.tmp` 也要按保护窗回收（BUG.md L-869）
            val byName = indexDir.listFiles()?.filter { it.isFile }
                ?.associate { it.name to it.lastModified() }.orEmpty()
            val stale = staleOptionalCacheNames(
                byName.keys.toList(),
                alive,
                System.currentTimeMillis(),
            ) { byName[it] ?: 0L }
            stale.forEach { name ->
                if (java.io.File(indexDir, name).delete()) {
                    Diagnostics.i(TAG, "清理失效索引缓存: $name")
                }
            }
        }

        val loaded = ArrayList<PhraseIndex>(packs.size)
        // 「找到文件但没读进来」的包：返回给调用方记账（原先只留一条 W 级日志，UI 看不出区别）
        val failed = LinkedHashSet<String>()
        var loadedBytes = 0L
        for (f in packs) {
            val idx = loadOptionalIndex(f, indexDir)
            if (idx != null) {
                loaded.add(idx)
                loadedBytes += f.length()
            } else {
                failed.add(f.name)
            }
        }
        optionalIndexes = loaded            // 原子整体替换（查询侧并发读旧表安全）
        invalidateMergedCache()
        extensionLoaded = loaded.isNotEmpty()
        if (loaded.isEmpty()) {
            // 区分「没装」与「装了但一个都没读进来」：后者原先也打这句 I 级日志，
            // 「词库装了却不生效」的排查会被直接带偏（包损坏 / 解压失败只有上文一条 W 级日志）
            if (packs.isEmpty()) {
                Diagnostics.i(TAG, "未安装可选词库：仅加载基础词库（长词不可用）")
            } else {
                Diagnostics.w(TAG, "可选词库全部加载失败: 找到 ${packs.size} 个包，无一可用（长词不可用）")
            }
        } else {
            Diagnostics.i(TAG, "可选词库已加载 ${loaded.size} 个包，共 ${loadedBytes / 1024}KB（索引模式）")
        }
        return failed
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
                // 命中要求三处身份一致：缓存头记的、入口取到的、**此刻再取一次的**。入口取值与这次
                // 判定之间源包可能被换掉（保存 / 恢复都是改名落盘），那时旧缓存会被当成新包的索引
                // 复用，用户看到的是「改过的词库不生效」（BUG.md L-890）
                if (idx.sourceStamp == stamp && packStamp(src) == stamp) {
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
            // 读完再核一次身份：读取期间源包被换掉时，这份索引贴的身份已经不对（内容是本进程读到的
            // 那一份，自己用没问题），不落盘 —— 否则下次启动按身份判为不符，白重建一遍（BUG.md L-890）
            val stampNow = packStamp(src)
            // 原子落盘（写临时文件 + 改名）：缓存可能正被本进程 内存映射 使用，直接覆盖会截断映射
            val mapped = if (stampNow == stamp && writeIndexCacheAtomically(cache, bytes)) {
                PhraseIndex.ofMapped(cache)
            } else {
                null
            }
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
     * 判定「源包已被删除」的索引缓存文件名（纯函数，便于单测）。
     *
     * 基础索引缓存 `base.<APK mtime>.idx` 必须排除在外：它的"源"是 APK 内的资产，不在词库包列表里，
     * 一旦被这里当成失效缓存删掉，就会每次可选包加载都删一次基础缓存 ，
     * 症状是"内存映射永远命中不了、每次冷启动都要重新解压 1.8s"，而且日志上只看到一行
     * 「清理失效索引缓存」，极难联想到根因（2026-09-17 真机日志实锤）。
     */
    internal fun staleOptionalCacheNames(
        existing: List<String>,
        alivePacks: Set<String>,
        now: Long = 0L,
        modifiedAtOf: (String) -> Long = { 0L },
    ): List<String> {
        return existing.filter { name ->
            if (name.startsWith(BASE_CACHE_PREFIX)) return@filter false
            // 半截的原子写临时件（`<包名>.idx.tmp`）一并回收 —— 否则「装过包 → 写索引时被杀 → 删包」
            // 会留下一份永远没人清的空转文件（每包最多一份，可占十几 MB；BUG.md L-20）。
            if (name.endsWith(INDEX_SUFFIX + TMP_SUFFIX)) {
                val pack = name.removeSuffix(INDEX_SUFFIX + TMP_SUFFIX)
                if (pack !in alivePacks) return@filter true
                // 源包仍在：写临时件的与跑清理的是**同一条加载线程**（另有 optionalLoading 防重入），
                // 所以这里能遇到的只可能是**跨启动的陈旧残件**（上次构建索引时被杀留下的）——
                // 用 mtime 保护窗区分（BUG.md L-869）：窗口内可能是本线程正在写的那个，窗口外一律回收，
                // 否则它要等该源包被删才清。
                now - modifiedAtOf(name) > INDEX_TMP_PROTECT_MS
            } else {
                name.endsWith(INDEX_SUFFIX) && name.removeSuffix(INDEX_SUFFIX) !in alivePacks
            }
        }
    }

    /**
     * 索引缓存半截临时件的保护窗（与 `OptionalDicts.TMP_PROTECT_MS` 同思路，故取同值）。
     *
     * 见 [staleOptionalCacheNames] 与 BUG.md L-869。默认参数（`now = 0`）让不传 mtime 的旧调用
     * 保持「不清理」的保守行为，便于渐进接入。
     */
    internal const val INDEX_TMP_PROTECT_MS = 10 * 60 * 1000L

    /**
     * 基础索引缓存的有效性键（纯函数，便于单测；`BUG.md` L-153）。
     *
     * 优先用 APK 文件 mtime（App 一更新必变）；取不到时退 lastUpdateTime；
     * **两个时间都取不到时必须退到 versionCode**，不能退回常量 `"0"`：
     * 常量键会让所有版本共用同一个 `base.0.idx`（升级后仍复用旧索引，
     * 而日志只写「复用磁盘缓存」，用户只能清应用数据才恢复）。
     */
    internal fun baseCacheStampOf(apkMtime: Long, lastUpdateTime: Long, versionCode: Long): String = when {
        apkMtime > 0L -> apkMtime.toString()
        lastUpdateTime > 0L -> lastUpdateTime.toString()
        else -> "v$versionCode"
    }

    private fun baseCacheStamp(context: Context): String {
        val apkMtime = runCatching { java.io.File(context.packageCodePath).lastModified() }
            .getOrDefault(0L)
        val updated = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return baseCacheStampOf(apkMtime, updated, BuildConfig.VERSION_CODE.toLong())
    }

    /**
     * 词库是否**真的**加载好了（纯函数，便于单测；`BUG.md` L-151）。
     *
     * 判据：音节表与单字表都不能为空。资产空文件、格式变更（列错位被静默跳过）都属
     * 「解析成功但零行」—— 那不是「加载好了」，而是静默空词典。
     */
    internal fun isDictionaryUsable(syllables: Int, chars: Int): Boolean = syllables > 0 && chars > 0

    /**
     * 原子写索引缓存：先写同目录临时文件再 `rename`。
     *
     * 不能直接覆盖：缓存可能正被本进程 内存映射 使用（可选包重建缓存时会走到这里），
     * 截断已映射的文件会让读取方踩到空洞页，Linux 上直接 SIGBUS。rename 只替换目录项，
     * 旧映射仍指向旧 inode，安全。
     *
     * @return 是否成功落盘（失败只是让本次退回堆内，不影响可用性）
     */
    private fun writeIndexCacheAtomically(file: java.io.File, bytes: ByteArray): Boolean = runCatching {
        val tmp = java.io.File(file.parentFile, file.name + TMP_SUFFIX)
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
        // 不先删旧文件：POSIX rename 直接替换目录项，旧 inode 仍被已建立的映射持有（不会 SIGBUS），
        // 而「先删再改名」会制造一个「目标不存在」的窗口 —— 改名一旦失败，旧缓存与新缓存同时失去，
        // 下次启动只能整份重建。与 `DictManagerActivity` 的词库重装同一条口径（`tmp.renameTo(dst)`）。
        if (!tmp.renameTo(file)) {
            tmp.delete()
            Diagnostics.w(TAG, "写索引缓存失败：改名失败 ${tmp.name} -> ${file.name}（本次退回堆内）")
            return@runCatching false
        }
        true
    }.getOrElse { e ->
        // 写阶段抛异常时临时文件已经落地（可达十几 MB），必须清掉；
        // 只有「删旧失败 / 改名失败」那两条路径自己清了 tmp，这里漏了就会长期占空间。
        runCatching {
            val tmp = java.io.File(file.parentFile, file.name + TMP_SUFFIX)
            if (tmp.exists()) tmp.delete()
        }
        Diagnostics.w(TAG, "写入索引缓存失败（本次退回堆内，不影响可用性）: ${file.name} - ${e.message}")
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
    fun rememberChoice(word: String) {
        // 词频表恒按**简体**存（与 [query] 的 rank 同口径）：繁体模式下用户选的是繁体候选，
        // 存繁体会让「切回简体后同一个词的学习结果失效」，反之亦然。转换是无状态映射，无副作用。
        UserFrequency.remember(toSimplified(word))
    }

    /** 输入法退出/切后台时把未落盘的学习结果刷出去 */
    fun flushUserFrequency() = UserFrequency.flush()

    /** 扩展词库（「补充短语词库」下载的分片包）是否已加载（供设置页显示状态） */
    fun isExtensionLoaded(): Boolean = extensionLoaded

    private fun loadChars(context: Context) {
        openAssetText(context, CHARS_ASSET).use { loadCharsReader(it) }
    }

    private fun loadCharsReader(reader: java.io.BufferedReader) {
        var line = reader.readLine()
        while (line != null) {
            if (line.isNotBlank()) {
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val syllable = line.substring(0, tab)
                    val chars = line.substring(tab + 1).split(',')
                    // 只做长度过滤：非单字符 token 一律丢掉，否则表里混进的词条会进单字表、
                    // 被当成单字候选上屏（实测 pinyin_chars.txt 里唯一的非单字符就是 junding 行的「均订」）。
                    // **档位过滤不在这里做**：档 2 / 档 3 的字要整份载入内存，放行与否交给查询期
                    // （[charsFor] / [matchCharsByPrefix] → [filterRareChars]）—— 在这里过滤的话，
                    // 用户事后打开开关时字根本不在内存里，二级 / 三级字的单字候选永远打不出
                    // （2026-09-27 修；词语候选一直走查询期过滤，正常，反而掩盖了这条）。
                    val kept = chars.filter { it.length == 1 }
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
     * 失败不影响可用性：此时单字候选（与可选包）仍然可用，只是词语候选少一些，
     * 调用方据返回值决定 `isFullyLoaded`。异常只记日志、不抛出，绝不因为索引坏掉就让输入法打不出字。
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
                // 日志必须反映真实结果：之前无论落盘成败都打「已写入磁盘缓存」，
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
            Diagnostics.e(TAG, "基础词库索引加载失败，退化为仅用单字/可选包: ${it.message}", it)
            -1L
        }
    }

    /**
     * 逐行解析短语表。
     *
     * @param merge true = 并入已有数据（加载扩展包时用），false = 覆盖（首次加载基础包）。
     *
     * 扩展包必须用合并模式：扩展包与基础包存在相同的拼音键（如 `qie` 两边都有词），
     * 若直接赋值会把基础包的候选整体挤掉。合并时基础包词条在前（优先级更高），
     * 扩展包长词追加在后。
     */
    private fun loadPhrasesReader(reader: java.io.BufferedReader, merge: Boolean = false) {
        // 任何词库变更都必须让「合并结果缓存」失效，否则同一个进程内换词库后会读到旧候选
        invalidateMergedCache()
        // 容量已在声明处预分配（ConcurrentHashMap(600_000)），此处不再重建容器，
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
                            // 必须去重：扩展包与基础包可能收录同一个词
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
     * 统一读取入口：运行时并入的词优先（可选包加载时已与基础索引合并过），否则查基础索引。
     *
     * 档位过滤在这里做（查询期）而不是加载期：索引因此可以原样复用 —— 开关一改即时生效，
     * 不像加载期过滤那样必须重载词库。代价是每次命中多几次位图判断（微秒级）。
     */
    private fun phrasesFor(key: String): Array<String>? {
        mergedCache[key]?.let { return it.ifEmpty { null } }

        // 记下算这一份结果时的世代号：回写前要再比一次（见下）
        val gen = dataGeneration
        val optionals = optionalIndexes
        val raw: Array<String>? = if (optionals.isEmpty()) {
            phrasesByPinyin[key] ?: baseIndex?.wordsFor(key)
        } else {
            // 逐段查找并按旧语义合并：运行时（可选包）→ 基础索引 → 各可选索引按加载顺序，
            // 「先到先得 + 去重」，与上一版「基础在前、扩展追加」的合并结果完全一致。
            val out = LinkedHashSet<String>(32)
            phrasesByPinyin[key]?.let { out.addAll(it) }
            baseIndex?.wordsFor(key)?.let { out.addAll(it) }
            for (idx in optionals) idx.wordsFor(key)?.let { out.addAll(it) }
            if (out.isEmpty()) null else out.toTypedArray()
        }

        val filtered = raw?.let { filterRareChars(it) }
        if (mergedCache.size > 4096) mergedCache.clear()
        // 世代号没变才回写：算这个键的过程中词库可能已经被改（加载线程走了失效入口），
        // 此时回写的是变更前的旧结果，而失效点已经过去，这个键会一直返回错误的候选。
        if (gen == dataGeneration) mergedCache[key] = filtered ?: EMPTY_WORDS
        return filtered
    }

    /** 查询期生僻字过滤（索引与运行时词一视同仁；**只管单字**，词组一律放行，见 [isLoadableWord]） */
    private fun filterRareChars(raw: Array<String>): Array<String> {
        // 位图未就绪时整体不过滤，与 isLoadableWord 的判据保持一致
        if (commonChars == null) return raw
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
     *
     * ⚠ 登记的必须是**显示词**（「只使用繁体字」开启时即繁体）：`consumption` / `predict` 都按
     * 候选原文查这张表，登记简体词会让繁体模式下的两级查表全落空（补全出口因此改成转换后再登记）。
     *
     * @param clearTrueKey 是否顺带清掉 [candidateTruePinyin] 里的残留（补全出口传 false —— 它登记的是
     *   词库真实键、与模糊分支的「变体键」不是一回事，清掉会把模糊命中词的真实键抹平）。
     */
    private fun noteCandidateKeys(
        words: Collection<String>,
        key: String,
        clearTrueKey: Boolean = true,
    ) {
        if (candidatePinyin.size > 4_096) candidatePinyin.clear()
        // 最新写入者胜（原为 putIfAbsent）：同一个词可能挂在多个拼音键下
        // （如「朝阳」= chaoyang / zhaoyang、「不了」= bule / buliao）。putIfAbsent
        // 让第一次记录的那个键粘住：用户换一种拼法打到同一个词时，消费区间与预测都按旧键算，
        // 消费会落到「无法确定区间→消费全部」的兜底分支（残码被整段清掉），预测则去扫错的键区。
        for (w in words) {
            candidatePinyin[w] = key
            // 真实键只在模糊音分支登记（见 candidateTruePinyin）。这里必须一并清掉残留：
            // 否则用户关掉模糊音、改用另一个精确拼法打到同一个词时，预测仍按上一次的变体键扫延续词
            // （模糊分支在其后写入，顺序天然正确：先清、再登记本轮的变体键）
            if (clearTrueKey) candidateTruePinyin.remove(w)
        }
    }

        /**
     * 测试注入用：按行文本加载短语表（与 [loadPhrasesReader] 逻辑完全一致）。
     *
     * @param merge true 走并入语义，用于验证扩展包/可选包与基础包合并时的
     *              去重与顺序（合并必须去重，否则候选栏会出现两个相同的词）；
     *               false 走覆盖语义，对应基础包首次加载。
     *               默认 false，保持既有调用方行为不变。
     */
    internal fun loadPhrasesText(text: String, merge: Boolean = false) {
        loadPhrasesReader(java.io.BufferedReader(java.io.StringReader(text)), merge)
    }

    private fun loadSyllables(context: Context) {
        openAssetText(context, SYLLABLES_ASSET).use { reader ->
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

    /** 双拼切分校验用：判断某串是否为合法完整音节（词库未加载时返回 false） */
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
     *
     * 模糊音容错（默认关，见 [FuzzyPinyin]）：打开后额外派生变体键，顺序契约是
     * **精确词 → 变体词 → 精确单字 → 变体单字** —— 变体只作补充，永不挤占精确结果，
     * 所以关掉开关时结果与历史逐候选一致。
     */
    fun query(input: String): Result {
        if (!loaded) return Result(emptyList(), emptyList(), "")
        val raw = input.lowercase()
        if (raw.isEmpty()) return Result(emptyList(), emptyList(), "")

        // 1. 切分音节（含 ue/ve 变体，兼容词库两种 üe 写法）
        val (syllables, partial) = segment(raw)

        // 2. 从最长音节数逐级递减：先整词，再逐级到单字（对齐 AOSP while(lma_size>0)）
        //    词语与单字分成两个集合：模糊音的词要插在两者之间（见 2b）
        val words = LinkedHashSet<String>()
        for (k in syllables.size downTo 1) {
            val key = syllables.take(k).joinToString("")
            for (k2 in phraseKeysOf(key)) {
                phrasesFor(k2)?.let { found ->
                    val shown = displayTake(found.asList(), MAX_PHRASES)
                    words.addAll(shown)
                    noteCandidateKeys(shown, k2)
                }
            }
        }
        val chars = LinkedHashSet<String>()
        for (syl in syllables) {
            chars.addAll(displayTake(charsFor(syl), MAX_CHARS))
        }

        // 2b. 模糊音容错（默认关）：精确结果一个不动，变体一律排在其后
        val fuzzyWords = LinkedHashSet<String>()
        val fuzzyChars = LinkedHashSet<String>()
        if (fuzzyMask != FuzzyPinyin.NONE) {
            val isLegal = { s: String -> validSyllables.contains(s) }
            for (k in syllables.size downTo 1) {
                val typedKey = syllables.take(k).joinToString("")
                for (variantKey in FuzzyPinyin.keyVariants(syllables.take(k), fuzzyMask, isLegal)) {
                    for (k2 in phraseKeysOf(variantKey)) {
                        phrasesFor(k2)?.let { found ->
                            val shown = displayTake(found.asList(), MAX_PHRASES)
                            fuzzyWords.addAll(shown)
                            // 候选→拼音键登记的是「用户实际输入的键」而不是变体键：消费区间按输入算
                            noteCandidateKeys(shown, typedKey)
                            // 真实键另记一份供预测扫延续词（见 candidateTruePinyin 的 KDoc）
                            if (candidateTruePinyin.size > 4_096) candidateTruePinyin.clear()
                            for (w in shown) candidateTruePinyin[w] = k2
                        }
                    }
                }
            }
            // 变体单字跟在精确单字之后，且上限更小（见 MAX_FUZZY_CHARS）
            for (syl in syllables) {
                for (variant in FuzzyPinyin.variantsOf(syl, fuzzyMask, isLegal)) {
                    fuzzyChars.addAll(displayTake(charsFor(variant), MAX_FUZZY_CHARS))
                }
            }
        }

        val result = LinkedHashSet<String>()
        result.addAll(words)
        result.addAll(fuzzyWords)
        result.addAll(chars)
        result.addAll(fuzzyChars)

        // 3. 未完成音节的前缀联想（如 nih → 你 + h 前缀字）
        //    partial 非空；或末尾音节是「伪完整音节」（如 nim 的 m，本身合法但也是
        //    更长音节前缀，用户意图是 ni+m… 补全）时也触发补全
        val lastIsFakeComplete = syllables.isNotEmpty() &&
            partial.isEmpty() && isTruePrefixOfSyllable(syllables.last())
        if (partial.isNotEmpty() || lastIsFakeComplete) {
            // 3a. 词库短语补全召回（如 ni m → ni+men → 你们）：
            //    补全词插入 result 头部（优先于第 2 步已加入的单字）
            val completionWords = queryWithCompletion(raw)
            // 拼音串是用户输入正文：走 V 级（默认只进 logcat 不落盘）。
            // 用 i 级时每敲一键都要在主线程 open/write/close 一次日志文件，既泄露输入又掉帧。
            Diagnostics.v(
                TAG,
                "query补全: input=$raw partial=$partial fake=$lastIsFakeComplete " +
                    "syl=$syllables completionCount=${completionWords.size}",
            )
            val asList = ArrayList(result)
            result.clear()
            // [queryWithCompletion] 的**出口契约**是「已经转过一次的显示词」（它在自己出口过了一次
            // displayTake 并按显示词登记候选→键）⇒ 这里绝不能再转一次：繁体模式下二次 toDisplay
            // 会拿繁体形去查词级消歧表（键是简体）必然落空，字级 1:1 映射把消歧过的字又改坏
            // （万里 → 萬里（正）→ **萬裏**（错），真库逐条复算 360 条），且显示词与登记键不一致
            // 会让 consumption / predict 的一级查表落空（BUG.md L-65）。
            result.addAll(completionWords)
            result.addAll(asList)
            // 3b. 单字前缀联想（原有逻辑，保持）
            if (syllables.isNotEmpty()) {
                result.addAll(displayTake(charsFor(syllables.last()), MAX_CHARS))
            }
            result.addAll(displayTake(matchCharsByPrefix(partial), MAX_CHARS))
        }

        // 用户词频学习：把「用户实际选过」的词稳定提到前面（未学习时零开销、零行为变化）。
        // 繁体模式下候选是繁体、词频表按简体存储（[rememberChoice] 同口径），因此按简体形式打分、
        // 返回的仍是候选本身。恒走 keyOf 重载：关繁体时 toSimplified 是恒等映射
        // （`tradSimp ?: return word`），不按开关选重载 —— 那种分支正是「改一处忘另一处」的温床。
        val token = result.toTypedArray()
        val ordered = UserFrequency.rank(token) { toSimplified(it) }.toList()
        return Result(ordered, syllables, partial)
    }

    /**
     * üe 两种写法的对偶键（无对偶返回 null）：词库只收 `lue`/`nue` 或 `lve`/`nve` 其中一种。
     *
     * 只有 lue/nue 与 lve/nve 成对 —— `jue`/`que`/`xue`/`yue` 的对偶键（`jve` 等）不在词库里，
     * 查不到即视为无对偶，不产生副作用。
     */
    private fun ueVeVariant(raw: String): String? {
        if (raw.contains("ue")) {
            val alt = raw.replace("ue", "ve")
            return if (alt != raw) alt else null
        }
        if (raw.contains("ve")) {
            val alt = raw.replace("ve", "ue")
            return if (alt != raw) alt else null
        }
        return null
    }

    /** 精确匹配 + ue/ve 变体：词库同时存在 shenglue/shenglve 两种写法 */
    private fun phraseKeysOf(raw: String): Set<String> {
        val alt = ueVeVariant(raw) ?: return setOf(raw)
        return setOf(raw, alt)
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
     * 收集阶段的候选上限（过滤前）。
     *
     * 生僻字过滤发生在收集之后（出口统一 `filterRareChars`）：若收集时就卡在
     * [MAX_PREDICTIONS]，一旦前几个后缀全被过滤，剩余名额无法由后续候选补上 ，
     * 预测会无故变少甚至为空。放宽到 2 倍给过滤留出回填空间；
     * 未启用过滤（显示生僻字）时只是多收集 ≤6 个后缀再被 take(MAX) 裁掉，开销可忽略。
     */
    private const val PREDICT_COLLECT_LIMIT = MAX_PREDICTIONS * 2

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
        // 两套语义要分清（见 rememberChoice / displayTake）：`candidatePinyin` 登记的是**显示词**
        // （繁体模式下即繁体），而词库与 `wordToPinyin` 一律是简体 —— 查词库侧前先折回简体。
        val simpLast = toSimplified(lastWord)
        // 优先用查询时记录的「候选→键」（基础词走索引后没有全量反向索引）。
        // 模糊音命中的候选另有一份真实键：预测要按它扫延续词（输入 zangguo 选到「张国」，
        // 延续词在 zhangguo* 键区下），而 candidatePinyin 存的是用户实际输入的键。
        val lastPinyin = candidateTruePinyin[lastWord] ?: candidatePinyin[lastWord]
            ?: wordToPinyin[simpLast] ?: return emptyList()
        val out = LinkedHashSet<String>()
        val wordBytes = simpLast.toByteArray(Charsets.UTF_8)
        val pinyinBytes = lastPinyin.toByteArray(Charsets.UTF_8).size

        // 扫描顺序与旧的 keysStartingWith 保持一致（影响 take(N) 的先后）：
        // 基础索引 → 各可选索引 → 运行时表（可选包并入项）。
        //
        // 索引侧走「区间枚举 + 字节级后缀收集」（吸收 librime `Prism::ExpandSearch` 的 Match 思路）：
        // 不实例化键字符串，只对命中的词解码后缀。旧写法在短前缀下会一次性建出数千个 String
        // （实测 keysWithPrefix("ni") = 6185 键 / 2.18ms），这条路径将来若给单字候选开预测会直接踩到。
        for (idx in baseAndOptionalIndexes()) {
            if (out.size >= PREDICT_COLLECT_LIMIT) break
            idx.forEachRangeWithPrefix(lastPinyin) { keyByteLen, wordsFrom, wordsTo ->
                if (keyByteLen > pinyinBytes) {
                    idx.collectLongerSuffixes(wordsFrom, wordsTo, wordBytes, out)
                }
                out.size < PREDICT_COLLECT_LIMIT    // false = 提前结束扫描
            }
        }

        // 运行时表：键数量小（可选包并入项），沿用有序 List 二分定位前缀区
        var lo = lowerBound(sortedPhraseKeys, lastPinyin)
        while (lo < sortedPhraseKeys.size && out.size < PREDICT_COLLECT_LIMIT) {
            val key = sortedPhraseKeys[lo]
            if (!key.startsWith(lastPinyin)) break
            if (key.length > lastPinyin.length) {
                addLongerSuffixes(phrasesFor(key), simpLast, out)
            }
            lo++
        }
        // 出口统一过一遍档位过滤：索引路径（collectLongerSuffixes）此前绕过了过滤，
        // 与运行时路径（addLongerSuffixes → phrasesFor 内的 filterRareChars）以及 query 的
        // 不一致，档位关着时预测候选仍可能带出档外词并可上屏。
        // 收集上限用 PREDICT_COLLECT_LIMIT（2 倍）正是为此：前几个候选被过滤后仍有后续候选
        // 可回填，不会"无故变少/变空"；位图未就绪时原样返回，零开销。
        // 最后再过 displayTake：「只使用繁体字」开启时预测一样给繁体。
        return displayTake(filterRareChars(out.toTypedArray()).toList(), MAX_PREDICTIONS)
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
     * 词库约束分词：整串必须是词库键时，在合法切分里取音节数最少的那种；否则返回 null
     * （调用方退回贪心切分）。
     *
     * 说清它实际在做什么：
     *  - 打分里的 `key = path.joinToString("")`，而 path 是输入的一种划分，join 后恒等于输入本身，
     *    所以 `hit` 对所有路径都一样，分数只差 `-path.size`；末尾 `phrasesFor(整串) != null` 的门禁
     *    也是所有路径共有 ⇒ 实际只比音节数（先枚举者胜；DFS 按最长音节优先，多数情况下
     *    第一条完整路径即最优）。
     *  - 因此它不是「按词库短语数打分」的分词，也不要按那个思路去改：
     *    实测把打分改成「Σ 各前缀命中数」会退化（每个音节边界都加分 → 切得越碎分越高），
     *    真实词库下会切出 `tianqi → ti-a-n-qi`、`beijingdaxue → bei-ji-ng-da-xue` 这类坏结果。
     *    详见 `docs/从 librime 可吸收的设计.md` 第二节。
     *  - 原 KDoc 举的例子 `xuni → [xu, ni]` 本身不成立：`xun-i` 里的 `i` 不是合法音节，
     *    实测 `xuni` 只有一种合法切分（[xu, ni]）。
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
     * 上限的真实作用：因为 [dictionarySegmentation] 最终只比音节数、且这里按最长音节优先，
     * 第一条完整路径通常即最优，上限只是防御措施，不是「截断路径导致选错」的隐患所在
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
     * 抽成 internal 是为了能和应用"未剪枝的参考实现"逐例对拍，剪枝只允许砍掉
     * 到不了终点的分支，结果必须与不剪枝时完全一致，这一点用对拍比人工推演可靠。
     */
    internal fun enumerateSegmentPaths(input: String, out: MutableList<List<String>> = ArrayList()): List<List<String>> {
        dfsSegment(input, 0, ArrayList(), out, suffixParseable(input))
        return out
    }

    /**
     * 后缀能否切成合法音节序列：第 i 位表示 input 从 i 起的后缀切得通。自后向前一趟 DP，O(n·6)。
     *
     * [dfsSegment] 用它在进入分支前剪枝。不剪的话，「16 条完整路径」的上限管不住这种输入：
     * 前缀能切出很多合法音节、尾巴却切不通时，一条完整路径都找不到，上限永不触发，
     * DFS 会把所有前缀分支走到底。实测（JVM）：25 字符 8.6ms、31 字符 36.8ms，每加 6 字符约 ×4~5，
     * 真机上就是每按一键卡几百毫秒到几秒，用户看到的就是「拼音打错了/打太长，字母键卡住」。
     * 走不通的分支本来就到不了终点，剪掉它不改变任何切分结果，只是把代价压回 O(n·6)。
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
        // 与 [charsFor] 同口径：档位过滤在查询期，补全路径同样受开关约束
        return filterRareChars(out.toTypedArray()).toList()
    }

    /**
     * 按完整拼音直接查单字（供候选栏显示补全）。
     *
     * üe 两种写法与 [phraseKeysOf] 同口径：词库只收 `lve`/`nve` 时，输入 `lue`/`nue` 也要查得到 ——
     * 双拼转换输出的正是 **ue 型**（`Shuangpin.toQuanpin` 的 `lt` → `lue`），只认单键会让
     * 「略 掠 虐 疟」这类字在双拼下没有单字候选。两键合并去重、精确键在前。
     */
    fun charsFor(syllable: String): List<String> {
        val direct = charsBySyllable[syllable]
        val other = ueVeVariant(syllable)?.let { charsBySyllable[it] }
        // 档位过滤在**查询期**（与词路径的 filterRareChars 同源）：档 2 / 档 3 的字整份在内存里，
        // 开关一开即放行 —— 见 [loadCharsReader] 的注释（2026-09-27 修）。
        // 词库只收另一种写法时也必须出字：双拼输出 ue 型、词库键统一是 v 型，正落在这一支
        if (direct == null) return filterRareChars(other ?: return emptyList()).toList()
        if (other == null || other.isEmpty()) return filterRareChars(direct).toList()
        val out = ArrayList<String>(direct.size + other.size)
        val seen = HashSet<String>(direct.size + other.size)
        for (c in direct) if (seen.add(c)) out.add(c)
        for (c in other) if (seen.add(c)) out.add(c)
        return filterRareChars(out.toTypedArray()).toList()
    }

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
        // 反向索引（wordToPinyin / candidatePinyin）与单字表都按**简体**建：繁体模式下上屏的是
        // 繁体候选（「愛好」），先折回简体再查 —— 否则两条查表路径全落空，退化成兜底
        // 「消费全部」，输入 aihaoma 选「愛好」时把残码 ma 一起清掉（简体模式却能保留）
        val key = toSimplified(candidate)
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

        // 1) 词语候选：候选→键（查询时记录）优先 —— 该表登记的是**显示词**（繁体模式下即繁体），
        //    因此先用候选原文查，落空再折回简体查词库反向索引（与 [predict] 的两级顺序一致）。
        //    只按折返键查会让**基础索引词**（无 wordToPinyin 兜底）两条路径全落空 → 退化成
        //    兜底「消费全部」，残码被一起清掉。
        if (candidate.length > 1) {
            (candidatePinyin[candidate] ?: wordToPinyin[key])?.let { wp0 ->
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

        // 2) 单字候选：定位**展示序**里首个含该字的音节，消费到该音节结束（BUG.md L-12）
        //
        //    ⚠ 口径必须与展示侧一致：展示是「每音节取前 [MAX_CHARS] 条」（候选组装处的
        //    `displayTake(charsFor(syl), MAX_CHARS)`）。这里若按**全量**字表 `contains` 查找，会把
        //    「首现音节」定位到展示窗口**之外**的那个：同一字挂在两个音节、靠前那次被 60 条上限挡住时，
        //    渲染出来的那一条其实来自后一音节，而消费仍钉在靠前音节 ⇒ 点击后残码多留一个音节。
        //    也**不能**直接复用 [displayTake]：它会按「只使用繁体字」把字替换成繁体，而这里要比的是简体键。
        if (key.length == 1) {
            // 2a) 先按用户实际输入的精确音节找：与历史行为一致（找到的那个「展示序首个」不因截断而变）。
            //     字表统一是 v 型键（lve / nve），而全拼按键与双拼（lt / nt）给出的都是 ue 型
            //     ⇒ 与 [charsFor] 同口径地连对偶键一起查：只查实际输入的键时，`luema` 选「略」
            //     会落到步骤 3 兜底「消费全部」、把残码 ma 一起清掉（`lvema` 打同一候选却是 (3,1)，
            //     见 BUG.md L-66）。
            var acc = 0
            for ((i, syl) in syllables.withIndex()) {
                acc += syl.length
                if (charsFor(syl).take(MAX_CHARS).contains(key)) {
                    return Consumption(acc, i + 1)
                }
            }
            // 2b) 再看模糊音变体：变体字（输入 zang 时的「张」）同样只覆盖它由之派生的那个音节，
            //     落到兜底「消费全部」会把后面的残码一起清掉（精确字却能保留残码）。
            //     截断口径与展示侧的模糊变体出口一致（那里用的是 MAX_FUZZY_CHARS，见候选组装处）。
            if (fuzzyMask != FuzzyPinyin.NONE) {
                acc = 0
                for ((i, syl) in syllables.withIndex()) {
                    acc += syl.length
                    val hit = FuzzyPinyin.variantsOf(syl, fuzzyMask) { validSyllables.contains(it) }
                        .any { charsFor(it).take(MAX_FUZZY_CHARS).contains(key) }
                    if (hit) return Consumption(acc, i + 1)
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

    /**
     * 前缀 → 完整音节列表缓存（补全查询热路径，避免重复扫描音节表）。
     *
     * 用并发容器与 [mergedCache] / [candidatePinyin] 一致：当前调用链只在主线程
     * （[queryWithCompletion] ← [query] ← 键盘视图），普通 HashMap 还不会出事，
     * 但本类的读方本就与加载线程并发，缓存一旦被后台路径复用就是「读 get 撞写扩容
     * 成环卡死」那一类事故，声明处对齐，别留这颗雷。
     */
    private val completionCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * 返回以 [prefix] 开头的合法完整音节（字典序），最多 [MAX_COMPLETION_RESULTS] 个。
     * 例：m → [ma, mai, man, mang, mao, me, mei, men, meng, mi, ...]
     * 遍历合法音节全集 [validSyllables]（完整音节表，非单字表），
     * 补全的目的是拼词库短语键，音节必须合法即可，不要求有单字。
     * 带缓存：同一前缀只扫描一次。
     *
     * 截断是按字典序发生的，不是按相关性，以 z / c / s 开头的音节各有 35~37 个，
     * 超出的部分（zou / zuo / cuo / suo…）不会成为补全候选，末尾残码的短语召回因此少一截。
     * 这是「防候选爆炸」的既有取舍，别把这里当成「所有音节都已返回」。
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
        // 原始词 → 最后产出它的词库键：登记要等「候选 → 显示词」转换之后再做（见出口），
        // 这里先把归属记下来。
        val owner = LinkedHashMap<String, String>()
        for (syl in completions) {
            for (key in phraseKeysOf(base + syl)) {
                phrasesFor(key)?.let { words ->
                    for (w in words.take(MAX_PHRASES)) {
                        result.add(w)
                        owner[w] = key
                    }
                }
            }
            if (result.size >= MAX_COMPLETION_RESULTS) break
        }
        // 出口：先过 [displayTake]（与其余候选出口同口径），再用**显示词**登记候选→键。
        // 原实现在转换**之前**登记简体词 ⇒ 繁体模式下 `predict` / `consumption` 按显示词（繁体）
        // 查 `candidatePinyin` / `candidateTruePinyin` 双双落空，又因基础索引词不在 `wordToPinyin` 里，
        // 三级查表全空：选完补全候选后**不再预测**（简体模式同一操作正常，2026-09-27 复现，BUG.md L-42）。
        // `clearTrueKey = false`：补全登记的是词库真实键，不能抹掉模糊分支刚登记的真实键。
        val raw = result.toList()
        val shown = displayTake(raw, raw.size)
        val byKey = LinkedHashMap<String, MutableList<String>>()
        for (i in raw.indices) {
            owner[raw[i]]?.let { byKey.getOrPut(it) { ArrayList() }.add(shown[i]) }
        }
        for ((key, ws) in byKey) noteCandidateKeys(ws, key, clearTrueKey = false)
        return shown
    }
}

/**
 * 双拼双拼方案。
 *
 * 键位数据见 [SHUANGPIN_TABLES]（由 `tools/dict_builder/gen_shuangpin_tables.py` 从
 * `docs/rime-ice/double_pinyin*.schema.yaml` 的 `speller/algebra` 生成），本枚举只负责
 * 「方案身份 + 持久化取值 + 展示名」，不含任何键位逻辑。
 *
 * [prefsValue] 写入 `Prefs.shuangpinScheme`：只能追加，不能改动既有取值（老配置靠它迁移）。
 */
enum class ShuangpinScheme(
    val prefsValue: Int,
    /** 设置页与日志用的全名 */
    val displayName: String,
    /** 日志/排查用的短名（`拼音输入[双拼·自然码]` 的 modeTag；面板按钮已于 2026-09-20 移除） */
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
     * 表是惰性构建的（`Lazy.value`）：首次访问某方案时才建它那一张，避免键盘弹出路径
     * 一次性建 7 张（详见 ShuangpinSchemes.kt 文件头与 [Shuangpin.warmUpAll]）。
     */
    internal val table: ShuangpinTable? get() = tableKey?.let { SHUANGPIN_TABLES[it]?.value }

    companion object {
        /** 全部双拼方案（键位提示遍历、方案相关测试用它，保证顺序一致） */
        val SHUANGPIN_ONLY: List<ShuangpinScheme> = entries.filter { it.isShuangpin }

        /**
         * 设置页「输入方案」下拉的全部可选项：全拼 + 七套双拼（共 8 项，语音键盘不在此列）。
         *
         * 2026-09-20 起该下拉是全局输入方案（不再只选「哪套双拼」）：选全拼 = 关闭双拼，
         * 选某套双拼 = 记住并启用；按键面板不再提供「全拼 / 双拼」切换按钮。
         */
        val ALL: List<ShuangpinScheme> = listOf(QUANPIN) + SHUANGPIN_ONLY

        /** 由持久化取值还原方案；未知取值一律回落到自然码（老配置即 `useShuangpin = true`） */
        fun of(prefsValue: Int): ShuangpinScheme =
            entries.firstOrNull { it.prefsValue == prefsValue } ?: ZIRANMA
    }
}

/**
 * 双拼 ↔ 全拼转换（纯查表）。
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

    /**
     * 候选栏「声韵显示」用的全拼：与 [toQuanpin] 同一张表、同一套两键分组，但**绝不丢键**。
     *
     * 为什么要独立一份：[toQuanpin] 面向查询，遇到无法成音节的组合即停止（只给已转换的前缀）；
     * 而候选栏要求「每按一键都看得到变化」（2026-09-18 的「按键没反应」报告就是显示串被吞掉）。
     * 因此这里对残余按键逐个展开 —— 声母键给声母、其余原样保留（含分号键），
     * 保证追加按键时显示串一定变化。
     *
     * 合法输入的输出与 [toQuanpin] 逐字节一致，仅残码 / 非法组合的处理不同。
     * 例（自然码）：`vsgo` → `zhongguo`；残码 `vsg` → `zhongg`（不是 `zhong`）。
     */
    fun displayQuanpin(input: String, scheme: ShuangpinScheme): String {
        val table = scheme.table ?: return input.lowercase()
        val raw = input.lowercase()
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length * 2)
        var i = 0
        while (i < raw.length) {
            // 每两键整查一次码表（与 toQuanpin 的合法路径同源）
            val syllable = if (i + 1 < raw.length) table.codes[raw.substring(i, i + 2)] else null
            if (syllable != null) {
                sb.append(syllable)
                i += 2
                continue
            }
            // 残码 / 非法组合：逐键展开（声母键给声母，其余原样），绝不跳过按键
            val key = raw[i]
            sb.append(table.initialOf(key) ?: key.toString())
            i += 1
        }
        return sb.toString()
    }

    /** 该方案是否有键位落在分号键上（搜狗/微软/紫光的 `ing`），键面需要显示分号键 */
    fun needsSemicolon(scheme: ShuangpinScheme): Boolean = scheme.table?.needsSemicolon == true

    /**
     * 预热：把全部方案的键位表构建出来，返回耗时（毫秒）。
     *
     * 供 IME 服务创建时在后台线程调用，表总共约 3,000 条目，一次全建有几十毫秒量级开销，
     * 而键盘视图是在 `onCreateInputView`（键盘首次弹出）里创建的，在那里同步建表会拖慢首次弹出。
     *
     * 不预热也能正常工作（首次访问会按需同步构建该方案的表），预热只是把这笔开销挪到后台：
     * 最坏情况是预热还没跑完用户就弹出键盘，此时只构建当前方案那一张（约为全部开销的 1/7）。
     */
    fun warmUpAll(): Long {
        val startNs = System.nanoTime()
        SHUANGPIN_TABLES.values.forEach { it.value }
        return (System.nanoTime() - startNs) / 1_000_000
    }
}
