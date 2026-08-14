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

    /** 加载词库；幂等，可在后台线程调用 */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadChars(context)
            loadPhrases(context)
            loadSyllables(context)
            loaded = true
            Log.i(TAG, "词库加载完成: 音节=${charsBySyllable.size} 词语键=${phrasesByPinyin.size} 合法音节=${validSyllables.size}")
            Diagnostics.i(TAG, "词库加载完成: 音节=${charsBySyllable.size} 词语键=${phrasesByPinyin.size}")
        }
    }

    private fun loadChars(context: Context) {
        context.assets.open("pinyin_chars.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            for (line in reader.lineSequence()) {
                if (line.isBlank()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val syllable = line.substring(0, tab)
                val chars = line.substring(tab + 1).split(',')
                if (chars.isNotEmpty()) charsBySyllable[syllable] = chars.toTypedArray()
            }
        }
    }

    private fun loadPhrases(context: Context) {
        context.assets.open("pinyin_phrases.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            for (line in reader.lineSequence()) {
                if (line.isBlank()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val pinyin = line.substring(0, tab)
                val phrases = line.substring(tab + 1).split('|')
                if (phrases.isNotEmpty()) phrasesByPinyin[pinyin] = phrases.toTypedArray()
            }
        }
    }

    private fun loadSyllables(context: Context) {
        context.assets.open("pinyin_syllables.txt").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            for (line in reader.lineSequence()) {
                if (line.isNotBlank()) validSyllables.add(line.trim())
            }
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
     */
    fun query(input: String): Result {
        if (!loaded) return Result(emptyList(), emptyList(), "")
        val raw = input.lowercase()
        if (raw.isEmpty()) return Result(emptyList(), emptyList(), "")

        val result = mutableListOf<String>()

        // 1. 词语候选：整串精确匹配
        phrasesByPinyin[raw]?.let { result.addAll(it.take(MAX_PHRASES)) }

        // 2. 单字候选：切分音节，对最后一个音节前缀匹配
        val (syllables, partial) = segment(raw)
        val lastKey = partial.ifEmpty { syllables.lastOrNull() }.orEmpty()
        if (lastKey.isNotEmpty()) {
            result.addAll(matchCharsByPrefix(lastKey).take(MAX_CHARS))
        }

        return Result(result, syllables, partial)
    }

    /** 将拼音串切分为合法音节序列，最后一个音节可能不完整 */
    private fun segment(input: String): Pair<List<String>, String> {
        val syllables = ArrayList<String>()
        var i = 0
        while (i < input.length) {
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

    /** 以指定串为前缀匹配音节，返回合并后的单字候选（保持频率序） */
    private fun matchCharsByPrefix(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for ((syllable, chars) in charsBySyllable) {
            if (syllable.startsWith(prefix)) {
                for (c in chars) {
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
 *    （j/q/x 后取 iong/üan/üe/ün/ü，l/n 后取 ü，其余取 ong/uan/ue/un/ui）
 *  - 零声母：`o` 作零声母键 + 韵母键（如 an→oj、ai→ol、ou→ob）；单韵母 a/o/e 双写（aa/oo/ee）
 *
 * 本类完全自包含（不依赖词库），转换规则确定，可脱离 Android 资源单测。
 */
object Shuangpin {

    /** 双字母声母 → 单键 */
    private val INITIAL_KEY = mapOf("zh" to 'v', "ch" to 'i', "sh" to 'u')

    /** 韵母 → 键（自然码）。多值按 [KEY_FINALS] 与声母消歧 */
    private val FINAL_KEY = mapOf(
        "a" to 'a', "ai" to 'l', "an" to 'j', "ang" to 'h', "ao" to 'k',
        "e" to 'e', "ei" to 'z', "en" to 'f', "eng" to 'g', "er" to 'r',
        "i" to 'i', "ia" to 'w', "ian" to 'm', "iang" to 'd', "iao" to 'c',
        "ie" to 'x', "in" to 'n', "ing" to 'y', "iong" to 's', "iu" to 'q',
        "o" to 'o', "ong" to 's', "ou" to 'b',
        "u" to 'u', "ua" to 'w', "uai" to 'y', "uan" to 'r', "uang" to 'd',
        "ue" to 't', "ui" to 'v', "un" to 'p', "uo" to 'o',
        "v" to 'v', "ve" to 't',
    )

    /** 声母 j/q/x：与其搭配的撮口呼韵母 */
    private val JQX = setOf("j", "q", "x")

    /** 键 → 韵母列表（同键多个韵母，如 s→ong/iong、r→er/uan/üan） */
    private val KEY_FINALS: Map<Char, List<String>> by lazy {
        val map = HashMap<Char, MutableList<String>>()
        for ((final_, key) in FINAL_KEY) {
            map.getOrPut(key) { mutableListOf() }.add(final_)
        }
        map
    }

    /**
     * 把自然码双拼输入串转换成全拼音节串。
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
        // 单韵母双写：aa/oo/ee → a/o/e
        if (k1 == k2 && k1 in setOf('a', 'e', 'o')) return k1.toString()
        // 零声母：o + 韵母键
        if (k1 == 'o') {
            if (k2 == 'a' || k2 == 'e') return k2.toString() // oa/oe 罕见，按单韵母
            return zeroInitialFinal(k2)
        }
        val initial = initialFor(k1)
        if (initial.isEmpty()) return null
        val final_ = finalFor(initial, k2) ?: return null
        return initial + final_
    }

    /** 零声母韵母：o + 韵母键（an→oj、ai→ol、ou→ob、er→or…） */
    private fun zeroInitialFinal(key: Char): String? = when (key) {
        'a' -> "a"
        'o' -> "o"
        'e' -> "e"
        'l' -> "ai"
        'j' -> "an"
        'h' -> "ang"
        'k' -> "ao"
        'z' -> "ei"
        'f' -> "en"
        'g' -> "eng"
        'r' -> "er"
        'b' -> "ou"
        else -> null
    }

    /**
     * 根据声母消歧，返回该声母 + 韵母键对应的韵母。
     * 同键多韵母的键位：s→ong/iong、r→uan/üan、t→ue/üe、p→un/ün、v→ui/ü，
     * j/q/x 配撮口呼（iong/üan/üe/ün/ü），l/n 配 ü（lv/nv），其余配一般韵母。
     */
    private fun finalFor(initial: String, key: Char): String? = when (key) {
        's' -> if (initial in JQX) "iong" else "ong"
        'r' -> if (initial in JQX) "üan" else "uan"
        't' -> if (initial in JQX) "üe" else "ue"
        'p' -> if (initial in JQX) "ün" else "un"
        'v' -> if (initial in JQX || initial == "l" || initial == "n") "ü" else "ui"
        // o 键：b/p/m/f/w 后是 o（bo/po/mo/fo/wo），其他声母是 uo（guo/cuo/zuo）；零声母才是 o
        'o' -> if (initial in setOf("b", "p", "m", "f", "w")) "o" else "uo"
        else -> KEY_FINALS[key]?.firstOrNull()
    }

    private fun initialFor(key: Char): String = when (key) {
        'v' -> "zh"
        'i' -> "ch"
        'u' -> "sh"
        else -> if (key in 'a'..'z') key.toString() else ""
    }
}
