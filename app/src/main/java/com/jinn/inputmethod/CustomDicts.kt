package com.jinn.inputmethod

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream

/**
 * 用户自定义补充词库：把「人写的 .txt」转成引擎已在用的可选词库包，
 * 落在 `filesDir/dicts/custom_user.txt.xz`，与下载的可选包走同一条装载 / 备份通道。
 *
 * 人写的文件（UTF-8，一行一条；第三列及以后忽略）：
 * ```
 * # 注释
 * 黄霄雲 huang xiao yun      ← 手机写法：词 + 空格 + 拼音（拼音内部也用空格分音节）
 * 张三<TAB>zhang san         ← PC / 工具写法：TAB 分隔（与 tools/dict_builder/add_words.py 同口径）
 * ```
 *
 * 打包 raw（`PhraseIndex.build` / `loadExtensionDict` 读的形态，与 `build_dicts.py` 产出同格式）：
 * ```
 * jiqixuexi<TAB>机器学习
 * zhangsan<TAB>张三
 * ```
 *
 * 「快捷补充」页（[CustomDictEditActivity]）编辑的是同目录下的**源文本** `custom_user.src.txt`
 * （扩展名不是 `.xz`，引擎不装载它）：导入与快捷补充保存后都写这一份，两个入口因此共享同一内容
 * （见 [writeSource] / [readSource]）。
 *
 * 职责边界：本层只做「校验 + 归一 + 打包 + 原子落盘」；装载复用 `PinyinEngine.loadExtensionDict`
 * 扫 `dicts/` 目录下全部 `.xz` 的既有路径，备份复用 `ConfigBackup` 的 dicts 节（导出按目录收，
 * 导入对这一件额外做**结构校验**后放行，见 [isValidPackFile]）。
 */
internal object CustomDicts {

    /** 落地文件名；放在 `dicts/` 下才会被引擎扫描与备份收录 */
    const val PACK_NAME = "custom_user.txt.xz"

    /**
     * 人写的**源文本**文件名：与包同放 `dicts/`，供「快捷补充」页回显与再编辑。
     *
     * 扩展名不是 `.xz` ⇒ 引擎扫描（只认 `.xz`）不会装载它；备份按目录一起收（见
     * [ConfigBackupManager.dictFiles]），换机后编辑页仍能看到导入 / 保存过的内容。
     */
    const val SOURCE_NAME = "custom_user.src.txt"

    /** 单次导入的**解压后文本**上限（8MB）：防误选巨型文件把内存与解析拖死 */
    const val MAX_INPUT_BYTES = 8 * 1024 * 1024

    /** 词条数上限：个人补充足够用，也保证索引构建是秒级 */
    const val MAX_ENTRIES = 50_000

    /** 单键词数上限：`PhraseIndex.build` 对同键词表有 64KB 硬闸，超了整包拒收 */
    const val MAX_WORDS_PER_KEY = 100

    /** 单个词的长度上限（字符）：防超长行混进来 */
    const val MAX_TEXT_CHARS = 64

    /**
     * 单个键的长度上限（字符）：与 `PhraseIndex.build` 的 `require(kb.size <= 255)` 同源 ——
     * 键全是 `[a-z]`（见 [KEY_SHAPE]）⇒ 字符数 == UTF-8 字节数，直接比字符即可。
     * 超限的键会让索引构建抛错、整包转「回退并入」并被记账为加载失败（BUG.md L-828）。
     */
    const val MAX_KEY_CHARS = 255

    /** 引擎侧音节表资产名（与 `PinyinEngine.SYLLABLES_ASSET` 同名，那边是 private 常量） */
    private const val SYLLABLES_ASSET = "pinyin_syllables.txt.xz"

    private val KEY_SHAPE = Regex("[a-z]+")

    /**
     * 词与拼音、以及拼音音节之间的分隔符集合。
     *
     * 必须显式带上全角空格 U+3000 与不换行空格 U+00A0（BUG.md L-845）：Java 正则的 `\s`
     * 只含 ASCII 空白（`[ \t\n\x0B\f\r]`），而从微信 / 网页 / WPS 复制来的词条常用全角空格 ——
     * 少这两个字符时整行会被判成「没有可保存的合法词条」。
     */
    private val TOKEN_SPLIT = Regex("[\\s\\u3000\\u00A0']+")

    data class Entry(val text: String, val pinyin: String)

    /**
     * @property skipped   跳过的行数（缺拼音 / 音节不合法 / 重复 / 超长）
     * @property truncated 是否因达到 [MAX_ENTRIES] 而提前截断
     */
    data class Result(val entries: List<Entry>, val skipped: Int, val truncated: Boolean)

    /**
     * 解析人写的词库文本。
     *
     * @param syllables 合法音节表（小写、无声调）；传 null 表示表不可用，此时只做形状校验
     *   （资产读不出来不应让整个导入失败）。
     */
    fun parseHuman(text: String, syllables: Set<String>? = null): Result {
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        var skipped = 0
        var truncated = false
        for (rawLine in text.lineSequence()) {
            if (entries.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val pair = splitHumanLine(line)
            if (pair == null) {
                skipped++
                continue
            }
            val word = pair.first
            val normalized = normalizeSyllables(pair.second, syllables)
            if (normalized == null) {
                skipped++
                continue
            }
            val key = normalized.replace(" ", "")
            // 键长与 `PhraseIndex.build` 的 255B 硬闸同源（键全 [a-z] ⇒ 字符数 == 字节数，见 MAX_KEY_CHARS）；
            // 竖线符是词表分隔符（会被装载侧拆成多个候选）、控制字符会进候选文本 —— 都在解析期挡掉
            // 并计入跳过（BUG.md L-828 / L-829）
            if (word.length > MAX_TEXT_CHARS || key.length > MAX_KEY_CHARS ||
                word.any { it == '|' || it.isISOControl() }
            ) {
                skipped++
                continue
            }
            if (!seen.add(word + "\u0000" + key)) {
                skipped++
                continue
            }
            entries += Entry(word, normalized)
        }
        return Result(entries, skipped, truncated)
    }

    /**
     * 行切分：TAB 优先（工具格式）；没有 TAB 时按「**词 + 空格 + 拼音**」解析。
     *
     * 手机上打不出 TAB（2026-10-05 用户实测）：用户会写 `黄霄雲 huang xiao yun`（单空格），
     * 而旧口径要求「两个以上空格」才分列 ⇒ 同样的输入整行被跳过、只提示「没有可保存的合法词条」。
     * 现在从**行尾往前**剥离连续拼音段，剩下的一段才是词 —— 单空格、双空格、TAB 三种写法归一。
     *
     * 两类歧义行一律返回 null（调用侧计「跳过」，不猜）：
     *  - 全是拼音段（词是纯拼音形状，如误把拼音整行粘进来）；
     *  - 词含空格（拼音段前面还剩多段，词表里不该出现带空格的词——要这种词请用 TAB 写）。
     */
    private fun splitHumanLine(line: String): Pair<String, String>? {
        val tabbed = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (tabbed.size >= 2) return tabbed[0] to tabbed[1]
        val tokens = line.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        var cut = tokens.size
        while (cut > 1 && isPinyinShape(tokens[cut - 1])) cut--
        if (cut != 1) return null
        val word = tokens[0]
        if (KEY_SHAPE.matches(word)) return null
        return word to tokens.subList(1, tokens.size).joinToString(" ")
    }

    /** 单段是否呈拼音形状（小写、`ü`→`v`、可带声调数字）—— 只判形状，音节表校验在 [normalizeSyllables] */
    private fun isPinyinShape(token: String): Boolean {
        val syl = token.lowercase(Locale.US).replace("ü", "v").trimEnd { it in '1'..'5' }
        return syl.isNotEmpty() && KEY_SHAPE.matches(syl)
    }

    /**
     * 把用户写的文本整理成**标准格式**（导入 / 保存成功后写回源文本；2026-10-05 用户要求）。
     *
     * 规则：能解析出词条的行统一成「词 空格 拼音」（拼音小写、`ü`→`v`、剥声调数字、音节单空格），
     * 于是「几个空格 / 全角空格 / NBSP / TAB / 混合写法」在保存后都收敛成同一形态；注释、空行、
     * 解析不出词条的行、以及带**第三列**的行（备注 / 插入位置，与 `add_words.py` 同格式）都**原样保留**
     * —— 用户写的内容一行都不丢，下次打开还能看见并修正。
     *
     * 幂等：对已标准化的文本再跑一次结果不变（拆分与归一复用 [splitHumanLine] / [normalizeSyllables]，
     * 与 [parseHuman] 不会各写一套规则）。
     */
    fun formatHuman(text: String, syllables: Set<String>? = null): String {
        val out = text.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> raw
                line.split('\t').count { it.isNotBlank() } > 2 -> raw
                else -> {
                    val pair = splitHumanLine(line)
                    val pinyin = if (pair == null) null else normalizeSyllables(pair.second, syllables)
                    if (pair == null || pinyin == null) raw else pair.first + " " + pinyin
                }
            }
        }
        // lineSequence 会吞掉末尾空行：原文以换行结尾时补回（写回源文本后观感不变）
        return if (text.endsWith("\n") && !out.endsWith("\n")) out + "\n" else out
    }

    /** 音节归一：小写、`ü`→`v`、剥声调数字、逐节形状与音节表校验；不合法返回 null */
    private fun normalizeSyllables(token: String, syllables: Set<String>?): String? {
        val parts = token.lowercase(Locale.US).replace("ü", "v").split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ArrayList<String>(parts.size)
        for (raw in parts) {
            val syl = raw.trimEnd { it in '1'..'5' }
            if (!KEY_SHAPE.matches(syl)) return null
            if (syllables != null && syl !in syllables) return null
            out += syl
        }
        return out.joinToString(" ")
    }

    /**
     * 打包：按 compact 键升序输出 `键<TAB>词1|词2`。
     *
     * 键升序是 `PhraseIndex.build` 的硬要求（乱序即抛错、整包回退）；
     * 同键词表按输入顺序去重，超过 [MAX_WORDS_PER_KEY] 的词丢弃（防 64KB 硬闸）。
     */
    fun toRawPackLines(entries: List<Entry>): List<String> {
        val byKey = java.util.TreeMap<String, LinkedHashSet<String>>()
        for (e in entries) {
            val set = byKey.getOrPut(e.pinyin.replace(" ", "").lowercase(Locale.US)) { LinkedHashSet() }
            if (set.size < MAX_WORDS_PER_KEY) set.add(e.text)
        }
        return byKey.map { (k, words) -> k + "\t" + words.joinToString("|") }
    }

    /** 打包字节（xz） */
    fun encodePack(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { xz ->
            val w = xz.bufferedWriter(Charsets.UTF_8)
            for (line in toRawPackLines(entries)) w.write(line + "\n")
            w.flush()
        }
        return out.toByteArray()
    }

    /**
     * 备份导入侧的内容校验：解压（限 [MAX_INPUT_BYTES]）后必须是本应用能产出的 raw 形态。
     *
     * 白名单外的 `.xz` 一律不落盘（防「任意 xz 被引擎全量解压」）；自定义词库允许落盘，
     * 但只收**结构合法**的内容 —— 等价于「用户自己导进来的那份文件」。
     */
    fun isValidPackFile(file: File): Boolean = runCatching {
        // 底层流一并纳入 use：XZInputStream 构造失败时若只包了 bufferedReader，
        // 文件句柄会泄漏 —— Windows 上紧接着的 delete() 会失败，留下半截临时件
        file.inputStream().use { raw ->
            val text = XZInputStream(raw).bufferedReader(Charsets.UTF_8)
                .use { readCapped(it, MAX_INPUT_BYTES) } ?: return false
            validateRawPack(text)
        }
    }.getOrDefault(false)

    /** raw 形态校验：键升序且唯一、词非空不超长、键数与单键词数在上限内 */
    fun validateRawPack(text: String): Boolean {
        var prevKey: String? = null
        var keys = 0
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            if (++keys > MAX_ENTRIES) return false
            val tab = line.indexOf('\t')
            if (tab <= 0) return false
            val key = line.substring(0, tab)
            if (!KEY_SHAPE.matches(key) || key.length > MAX_KEY_CHARS) return false
            if (prevKey != null && key <= prevKey) return false
            prevKey = key
            val words = line.substring(tab + 1).split('|')
            if (words.isEmpty() || words.size > MAX_WORDS_PER_KEY) return false
            if (words.any { it.isEmpty() || it.length > MAX_TEXT_CHARS }) return false
        }
        return keys > 0
    }

    /** 读流上限长文本：超限返回 null（BOM 剥掉 —— Windows 记事本默认带 BOM） */
    fun readCapped(reader: java.io.Reader, limit: Int): String? {
        val sb = StringBuilder()
        val buf = CharArray(8 * 1024)
        var total = 0
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            sb.append(buf, 0, n)
        }
        return sb.toString().removePrefix("\uFEFF")
    }

    /** 读取少量字节并解成 UTF-8 文本（供导入线程用；超限抛 [TooLargeException]） */
    fun readUtf8Capped(input: InputStream, limit: Int): String {
        val text = InputStreamReader(input, Charsets.UTF_8).use { readCapped(it, limit) }
        if (text == null) throw TooLargeException()
        return text
    }

    class TooLargeException : Exception("文件超过 ${MAX_INPUT_BYTES / 1024 / 1024}MB 上限")

    /**
     * 原子落盘：写 `custom_user.txt.xz.tmp` 再改名。
     *
     * 直接覆盖目标会在失败时留下半截文件，而引擎下一次空闲加载会把坏包算进
     * 「可选词库加载失败」——旧的自定义词库也随之失效。
     */
    fun writePack(context: Context, entries: List<Entry>): File? {
        val dir = File(context.filesDir, PinyinEngine.OPT_DICT_DIR).apply { mkdirs() }
        val dest = File(dir, PACK_NAME)
        val tmp = File(dir, "$PACK_NAME.tmp")
        return runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(encodePack(entries))
                out.fd.sync()
            }
            if (!tmp.renameTo(dest)) error("改名失败")
            dest
        }.onFailure {
            runCatching { tmp.delete() }
            Diagnostics.w(TAG, "自定义词库写入失败: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** 已导入的自定义词库文件（不存在时返回目标路径，供卡片判空） */
    fun packFile(context: Context): File = File(File(context.filesDir, PinyinEngine.OPT_DICT_DIR), PACK_NAME)

    /** 「快捷补充」页回显用的源文本文件（不存在时返回目标路径） */
    fun sourceFile(context: Context): File = File(File(context.filesDir, PinyinEngine.OPT_DICT_DIR), SOURCE_NAME)

    /**
     * 删除自定义词库：**包与源文本一起清**。
     *
     * 只删包的话，「快捷补充」页下次打开仍会回显已删词库的内容，用户会以为没删掉。
     */
    fun deletePack(context: Context): Boolean = runCatching {
        sourceFile(context).delete()
        val ok = packFile(context).delete()
        ok || !packFile(context).exists()
    }.getOrDefault(false)

    /**
     * 源文本原子落盘（`*.tmp` → rename，与 [writePack] 同一套）。
     *
     * [dir] 即包所在目录（`filesDir/dicts`）；抽成 File 参数让纯 JVM 单测能直接验
     * 原子性与读写回环，不必依赖 Context（`writePack` 因要 Context 而测不到的那一层在这里补上）。
     */
    fun writeSource(dir: File, text: String): File? {
        // 与 [readSource] / [readCapped] 同一口径（**字符**数）：写进去的必须能读回来。
        // 不设上限时，超大草稿（注释行不占词条配额）能让源文本超过 8MB —— 之后编辑页读不回、
        // 备份导入侧 [isValidSourceFile] 也拒收，文件在而界面看不到（BUG.md L-846）。
        if (text.length > MAX_INPUT_BYTES) {
            Diagnostics.w(TAG, "自定义词库源文本超过 ${MAX_INPUT_BYTES / 1024 / 1024}MB 上限，拒绝写入")
            return null
        }
        dir.mkdirs()
        val dest = File(dir, SOURCE_NAME)
        val tmp = File(dir, "$SOURCE_NAME.tmp")
        return runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(dest)) error("改名失败")
            dest
        }.onFailure {
            runCatching { tmp.delete() }
            Diagnostics.w(TAG, "自定义词库源文本写入失败: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** 读源文本：不存在返回 `""`；读不出或超 [MAX_INPUT_BYTES] 返回 null（调用侧按读失败提示） */
    fun readSource(file: File): String? {
        if (!file.isFile) return ""
        return runCatching {
            file.inputStream().use { readUtf8Capped(it, MAX_INPUT_BYTES) }
        }.getOrNull()
    }

    fun isPackName(fileName: String): Boolean = fileName == PACK_NAME

    fun isSourceName(fileName: String): Boolean = fileName == SOURCE_NAME

    /**
     * 备份导入侧的源文本放行判据：能按 UTF-8 读出且不超上限。
     *
     * 它只是用户自己的草稿（可能是半成品），不要求词条合法；限长是为了防止
     * 「超大文件塞进备份包 → 恢复时被整份读进内存」。
     */
    fun isValidSourceFile(file: File): Boolean = runCatching {
        file.inputStream().use { readUtf8Capped(it, MAX_INPUT_BYTES) }
    }.isSuccess

    /**
     * 读随包音节表（421 条，解压约几毫秒）。
     *
     * 只在校验时用；读不出来返回 null，调用侧降级为形状校验，不让资产问题挡住导入。
     */
    fun loadSyllables(context: Context): Set<String>? = runCatching {
        context.assets.open(SYLLABLES_ASSET).use { raw ->
            XZInputStream(raw).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
            }
        }
    }.getOrNull()?.takeIf { it.size > 100 }

    private const val TAG = "CustomDicts"
}
