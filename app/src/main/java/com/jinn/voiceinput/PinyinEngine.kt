package com.jinn.voiceinput

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

    private const val MAX_CHARS = 12
    private const val MAX_PHRASES = 12

    @Volatile
    private var loaded = false

    /** 音节 → 单字（按频率降序） */
    private val charsBySyllable = HashMap<String, Array<String>>()

    /** 拼音串 → 词语（按频率降序） */
    private val phrasesByPinyin = HashMap<String, Array<String>>()

    /** 合法音节集合（不含声调） */
    private val validSyllables = HashSet<String>()

    /** 有序音节（字典序）：保证单字候选输出顺序稳定 */
    private val sortedSyllables = ArrayList<String>()

    /** 加载词库；幂等，可在后台线程调用 */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadChars(context)
            loadPhrases(context)
            loadSyllables(context)
            finalizeLoad()
            loaded = true
            Log.i(TAG, "词库加载完成: 音节=${charsBySyllable.size} 词语键=${phrasesByPinyin.size} 合法音节=${validSyllables.size}")
            Diagnostics.i(TAG, "词库加载完成: 音节=${charsBySyllable.size} 词语键=${phrasesByPinyin.size}")
        }
    }

    /** 测试注入：直接用字符串字典加载（跳过 Android assets） */
    internal fun loadFromTexts(chars: String, phrases: String, syllables: String) {
        synchronized(this) {
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
    }

    private fun loadChars(context: Context) {
        context.assets.open("pinyin_chars.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            loadCharsText(reader.readText())
        }
    }

    private fun loadCharsText(text: String) {
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val syllable = line.substring(0, tab)
            val chars = line.substring(tab + 1).split(',')
            if (chars.isNotEmpty()) charsBySyllable[syllable] = chars.toTypedArray()
        }
    }

    private fun loadPhrases(context: Context) {
        context.assets.open("pinyin_phrases.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            loadPhrasesText(reader.readText())
        }
    }

    private fun loadPhrasesText(text: String) {
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val pinyin = line.substring(0, tab)
            val phrases = line.substring(tab + 1).split('|')
            if (phrases.isNotEmpty()) phrasesByPinyin[pinyin] = phrases.toTypedArray()
        }
    }

    private fun loadSyllables(context: Context) {
        context.assets.open("pinyin_syllables.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            loadSyllablesText(reader.readText())
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
        if (partial.isNotEmpty()) {
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

    /**
     * 将拼音串切分为合法音节序列，最后一个音节可能不完整。
     *
     * 单字候选专用：在每一步先检查「剩余整体是否某个更长音节的真前缀」——
     * 若是（如 nia 是 nian/niang/niao 的前缀、ha 是 hai/han/hang 的前缀），
     * 说明用户还没打完这个音节，直接把它整体作为未完成部分返回，
     * 避免把 nia 误切为 ni|a 而丢掉「年」的联想。
     *
     * 注意：剩余串本身是完整音节时（如 xuexi 的末尾 xi）不触发，照常切出。
     */
    private fun segment(input: String): Pair<List<String>, String> {
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

    /** 是否存在比 s 更长的合法音节以 s 开头（s 是未完成音节前缀） */
    private fun isTruePrefixOfSyllable(s: String): Boolean {
        if (s.isEmpty() || s.length >= 6) return false
        for (syllable in validSyllables) {
            if (syllable.length > s.length && syllable.startsWith(s)) return true
        }
        return false
    }

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
