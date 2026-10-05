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

    /**
     * 单次导入 / 保存的**文本**上限：8M **字符**，不是字节（BUG.md L-853）。
     *
     * 口径是字符数 —— `readCapped` 按 `CharArray` 计数、`String.length` 同款，两处必须一致
     * （写进去的要能读回来）。中文在 UTF-8 里一行三字节，8M 字符最多约 24MB：旧名
     * `MAX_INPUT_BYTES` 与「8MB」文案都会让人按字节理解，评估内存与用户提示都会偏。
     */
    const val MAX_INPUT_CHARS = 8 * 1024 * 1024

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
        val collector = LineCollector(syllables)
        for (line in text.lineSequence()) collector.feed(line)
        return collector.result()
    }

    /**
     * 逐行解析的累积器：[parseHuman] 与 [scanFormatted] 共用同一套判定 —— 两边各写一份规则就会漂
     * （词库的解析口径被改过多次，重复实现是上一批缺陷的共同根因）。
     *
     * 截断后 [feed] 直接返回：与旧实现的 `break` 同语义（后面的行不再计入跳过数）。
     */
    private class LineCollector(private val syllables: Set<String>?) {
        private val entries = ArrayList<Entry>()
        private val seen = HashSet<String>()
        private var skipped = 0
        private var truncated = false

        fun feed(rawLine: String) {
            if (truncated) return
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return
            val pair = CustomDicts.splitHumanLine(line) ?: run {
                skipped++
                return
            }
            val word = pair.first
            val normalized = CustomDicts.normalizeSyllables(pair.second, syllables) ?: run {
                skipped++
                return
            }
            val key = normalized.replace(" ", "")
            // 键长与 `PhraseIndex.build` 的 255B 硬闸同源（键全 [a-z] ⇒ 字符数 == 字节数，见 MAX_KEY_CHARS）；
            // 竖线符是词表分隔符（会被装载侧拆成多个候选）、控制字符会进候选文本 —— 都在解析期挡掉
            // 并计入跳过（BUG.md L-828 / L-829）
            if (word.length > MAX_TEXT_CHARS || key.length > MAX_KEY_CHARS ||
                word.any { it == '|' || it.isISOControl() }
            ) {
                skipped++
                return
            }
            if (!seen.add(word + "\u0000" + key)) {
                skipped++
                return
            }
            // 配额判定放在「这条确认能收」之后（BUG.md L-863）：旧写法在循环头判，
            // 把**恰好** MAX_ENTRIES 条（后面只剩注释 / 空行）也判成超限 ——
            // 提示「超过 5 万条」且拒存，用户正好 5 万条的词库永远存不进去。
            // 现在只有「确实有合法词条被丢下」才算截断。
            if (entries.size >= MAX_ENTRIES) {
                truncated = true
                return
            }
            entries += Entry(word, normalized)
        }

        fun result() = Result(entries, skipped, truncated)
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
     * 单行格式化：[formatHuman] / [scanFormatted] / 流式落盘共用同一份规则。
     *
     * 能解析出词条的行统一成「词 空格 拼音」；注释、空行、解析不出词条的行、以及带**第三列**的行
     * （备注 / 插入位置，与 `add_words.py` 同格式）原样返回。
     */
    private fun formatLine(raw: String, line: String, syllables: Set<String>?): String {
        if (line.isEmpty() || line.startsWith("#")) return raw
        if (line.split('\t').count { it.isNotBlank() } > 2) return raw
        val pair = splitHumanLine(line) ?: return raw
        val pinyin = normalizeSyllables(pair.second, syllables) ?: return raw
        return pair.first + " " + pinyin
    }

    /**
     * 把用户写的文本整理成**标准格式**（导入 / 保存成功后写回源文本）。
     *
     * 规则：能解析出词条的行统一成「词 空格 拼音」（拼音小写、`ü`→`v`、剥声调数字、音节单空格），
     * 于是「几个空格 / 全角空格 / NBSP / TAB / 混合写法」在保存后都收敛成同一形态；注释、空行、
     * 解析不出词条的行、以及带**第三列**的行都**原样保留** —— 写过的内容一行都不丢，下次打开还能看见并修正。
     *
     * 幂等：对已标准化的文本再跑一次结果不变（拆分与归一复用 [splitHumanLine] / [normalizeSyllables]，
     * 与 [parseHuman] 不会各写一套规则）。
     *
     * 返回的是整份副本，适合小输入与单测；保存 / 导入路径走 [scanFormatted] + [writeSourceFormatted]
     * 的逐行形态，不为全文再留一份副本（BUG.md L-858 / L-862）。
     */
    fun formatHuman(text: String, syllables: Set<String>? = null): String {
        val out = text.lineSequence().joinToString("\n") { formatLine(it, it.trim(), syllables) }
        // lineSequence 会吞掉末尾空行：原文以换行结尾时补回（写回源文本后观感不变）
        return if (text.endsWith("\n") && !out.endsWith("\n")) out + "\n" else out
    }

    /**
     * 保存路径的第一遍：逐行格式化 + 解析，只累计**格式化后**的总长与词条，不构造整份副本。
     *
     * 与 `parseHuman(formatHuman(text))` 的解析结果与长度判定逐项一致（单测对拍），内存峰值却从
     * 「原文 + 整份格式化文本」降到「原文 + 单行」—— 入口保护放宽到 2× 上限后，那一份副本会让
     * 16M 字符的输入峰值逼近 64MB（BUG.md L-858 / L-862）。
     */
    private fun scanFormatted(text: String, syllables: Set<String>?): Scan {
        val collector = LineCollector(syllables)
        var chars = 0L
        var lines = 0
        var lastEmpty = false
        for (raw in text.lineSequence()) {
            val out = formatLine(raw, raw.trim(), syllables)
            if (lines > 0) chars++ // 行间换行
            chars += out.length
            lines++
            lastEmpty = out.isEmpty()
            collector.feed(out)
        }
        // 末尾补行与 [formatHuman] 同规则：原文以换行结尾、且拼接结果尚未以换行结尾时补一个
        if (text.endsWith("\n") && !(lines > 1 && lastEmpty)) chars++
        return Scan(collector.result(), chars)
    }

    /** [scanFormatted] 的产物：[result] 供判闸，[formattedChars] 即 [formatHuman] 之后的字符数 */
    private class Scan(val result: Result, val formattedChars: Long)

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
     * 打包结果：raw 行 + 因同键上限**被丢掉**的词数（BUG.md L-864）。
     *
     * @property lines   `键<TAB>词1|词2` 的行（键升序）
     * @property dropped 同键词表已满 [MAX_WORDS_PER_KEY] 后被迫丢弃的词条数（不含同键同词去重 —— 去重是设计）
     */
    data class PackLines(val lines: List<String>, val dropped: Int)

    /**
     * 打包：按 compact 键升序输出 `键<TAB>词1|词2`。
     *
     * 键升序是 `PhraseIndex.build` 的硬要求（乱序即抛错、整包回退）；
     * 同键词表按输入顺序去重，超过 [MAX_WORDS_PER_KEY] 的词丢弃（防 64KB 硬闸），
     * 丢弃数经 [PackLines.dropped] 回传给界面（BUG.md L-864）。
     */
    fun toRawPackLines(entries: List<Entry>): PackLines {
        val byKey = java.util.TreeMap<String, LinkedHashSet<String>>()
        var dropped = 0
        for (e in entries) {
            val set = byKey.getOrPut(e.pinyin.replace(" ", "").lowercase(Locale.US)) { LinkedHashSet() }
            if (set.size < MAX_WORDS_PER_KEY) {
                set.add(e.text)
            } else if (e.text !in set) {
                // 同键满了、且不是重复词 ⇒ 真被丢下。此前静默丢弃、也不回传计数（L-864），
                // 用户看到「已保存 N 条」会以为全都进了包
                dropped++
            }
        }
        return PackLines(byKey.map { (k, words) -> k + "\t" + words.joinToString("|") }, dropped)
    }

    /** 打包字节（xz） */
    fun encodePack(entries: List<Entry>): ByteArray = encodePackLines(toRawPackLines(entries).lines)

    /** 打包字节（xz）—— 已有行形态；[saveHuman] 路径用它避免重复算同键分组 */
    fun encodePackLines(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { xz ->
            val w = xz.bufferedWriter(Charsets.UTF_8)
            for (line in lines) w.write(line + "\n")
            w.flush()
        }
        return out.toByteArray()
    }

    /**
     * 备份导入侧的内容校验：解压（限 [MAX_INPUT_CHARS]）后必须是本应用能产出的 raw 形态。
     *
     * 白名单外的 `.xz` 一律不落盘（防「任意 xz 被引擎全量解压」）；自定义词库允许落盘，
     * 但只收**结构合法**的内容 —— 等价于「用户自己导进来的那份文件」。
     */
    fun isValidPackFile(file: File): Boolean = runCatching {
        // 底层流一并纳入 use：XZInputStream 构造失败时若只包了 bufferedReader，
        // 文件句柄会泄漏 —— Windows 上紧接着的 delete() 会失败，留下半截临时件
        file.inputStream().use { raw ->
            val text = XZInputStream(raw).bufferedReader(Charsets.UTF_8)
                .use { readCapped(it, MAX_INPUT_CHARS) } ?: return false
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

    /** 读流限长文本：超 [limit]（**字符**，与 [MAX_INPUT_CHARS] 同口径）返回 null（BOM 剥掉 —— Windows 记事本默认带 BOM） */
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

    /** 读取限长文本并解成 UTF-8（供导入线程用；超 [limit] **字符**抛 [TooLargeException]） */
    fun readUtf8Capped(input: InputStream, limit: Int): String {
        val text = InputStreamReader(input, Charsets.UTF_8).use { readCapped(it, limit) }
        if (text == null) throw TooLargeException()
        return text
    }

    class TooLargeException : Exception("文件超过 ${MAX_INPUT_CHARS / 1024 / 1024}M 字符上限")

    /**
     * 原子落盘：写 `custom_user.txt.xz.tmp` 再改名。
     *
     * 直接覆盖目标会在失败时留下半截文件，而引擎下一次空闲加载会把坏包算进
     * 「可选词库加载失败」——旧的自定义词库也随之失效。
     *
     * [dir] 即包所在目录（`filesDir/dicts`，调用侧传 `File(filesDir, OPT_DICT_DIR)`）；
     * 抽成 File 参数与 [writeSource] 同款，让 JVM 单测能直接验落盘与「不落盘」两条路径。
     */
    fun writePack(dir: File, pack: PackLines): File? {
        dir.mkdirs()
        val dest = File(dir, PACK_NAME)
        val tmp = File(dir, "$PACK_NAME.tmp")
        return runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(encodePackLines(pack.lines))
                out.fd.sync()
            }
            if (!tmp.renameTo(dest)) error("改名失败")
            dest
        }.onFailure {
            runCatching { tmp.delete() }
            Diagnostics.w(TAG, "自定义词库写入失败: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** 便利重载：直接给词条（内部先打包成 [PackLines]；同键丢弃数因此也被算出来） */
    fun writePack(dir: File, entries: List<Entry>): File? = writePack(dir, toRawPackLines(entries))

    /** 已导入的自定义词库文件（不存在时返回目标路径，供卡片判空） */
    fun packFile(context: Context): File = File(File(context.filesDir, PinyinEngine.OPT_DICT_DIR), PACK_NAME)

    /** 「快捷补充」页回显用的源文本文件（不存在时返回目标路径） */
    fun sourceFile(context: Context): File = File(File(context.filesDir, PinyinEngine.OPT_DICT_DIR), SOURCE_NAME)

    /**
     * 删除自定义词库：**包与源文本一起清**。
     *
     * 只删包的话，「快捷补充」页下次打开仍会回显已删词库的内容，用户会以为没删掉。
     *
     * 也走写盘互斥（BUG.md L-865）：删除与保存并发时，两次 unlink 若落在保存的
     * 「源文本已写、包未写」之间，会留下「包在、源文本没了」（或反过来「删了又被写回」）
     * 的半状态 —— 内容不损坏，但用户看到的与以为的不一致。
     */
    fun deletePack(context: Context): Boolean =
        deletePackIn(File(context.filesDir, PinyinEngine.OPT_DICT_DIR))

    /**
     * [deletePack] 的核心（[dir] = `filesDir/dicts`）。
     *
     * 抽成 File 参数与 [writeSource] / [saveHuman] 同一口径：JVM 单测能直接验「被占位时不删任何文件」。
     */
    internal fun deletePackIn(dir: File): Boolean {
        if (!beginWrite()) {
            Diagnostics.w(TAG, "另一次词库写入进行中，删除已拒绝（未删任何文件）")
            return false
        }
        return try {
            runCatching {
                File(dir, SOURCE_NAME).delete()
                val pack = File(dir, PACK_NAME)
                val ok = pack.delete()
                ok || !pack.exists()
            }.getOrDefault(false)
        } finally {
            endWrite()
        }
    }

    /**
     * 源文本原子落盘（`*.tmp` → rename，与 [writePack] 同一套）。
     *
     * [dir] 即包所在目录（`filesDir/dicts`）；抽成 File 参数让纯 JVM 单测能直接验
     * 原子性与读写回环，不必依赖 Context（`writePack` 因要 Context 而测不到的那一层在这里补上）。
     */
    fun writeSource(dir: File, text: String): File? {
        // 与 [readSource] / [readCapped] 同一口径（**字符**数）：写进去的必须能读回来。
        // 不设上限时，超大草稿（注释行不占词条配额）能让源文本超过 8M 字符 —— 之后编辑页读不回、
        // 备份导入侧 [isValidSourceFile] 也拒收，文件在而界面看不到（BUG.md L-846）。
        if (text.length > MAX_INPUT_CHARS) {
            Diagnostics.w(TAG, "自定义词库源文本超过 ${MAX_INPUT_CHARS / 1024 / 1024}M 字符上限，拒绝写入")
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

    /**
     * 源文本流式落盘：逐行格式化直写 `*.tmp` 再改名。语义与 `writeSource(dir, formatHuman(text))`
     * 相同，但不构造整份格式化副本（BUG.md L-858 / L-862）。保存路径用它，[writeSource] 留给
     * 小输入与单测。
     */
    private fun writeSourceFormatted(dir: File, text: String, syllables: Set<String>?): File? {
        dir.mkdirs()
        val dest = File(dir, SOURCE_NAME)
        val tmp = File(dir, "$SOURCE_NAME.tmp")
        return runCatching {
            FileOutputStream(tmp).use { out ->
                val w = java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8))
                var lines = 0
                var lastEmpty = false
                var written = 0L
                for (raw in text.lineSequence()) {
                    if (lines > 0) {
                        w.write("\n")
                        written++
                    }
                    val line = formatLine(raw, raw.trim(), syllables)
                    w.write(line)
                    written += line.length
                    // 上限已由 [scanFormatted] 判过（同一套逐行规则）；这里只兜住规则漂移
                    if (written > MAX_INPUT_CHARS) error("格式化后超过上限")
                    lastEmpty = line.isEmpty()
                    lines++
                }
                if (text.endsWith("\n") && !(lines > 1 && lastEmpty)) w.write("\n")
                w.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(dest)) error("改名失败")
            dest
        }.onFailure {
            runCatching { tmp.delete() }
            Diagnostics.w(TAG, "自定义词库源文本写入失败: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** 读源文本：不存在返回 `""`；读不出或超 [MAX_INPUT_CHARS] 返回 null（调用侧按读失败提示） */
    fun readSource(file: File): String? {
        if (!file.isFile) return ""
        return runCatching {
            file.inputStream().use { readUtf8Capped(it, MAX_INPUT_CHARS) }
        }.getOrNull()
    }

    fun isPackName(fileName: String): Boolean = fileName == PACK_NAME

    fun isSourceName(fileName: String): Boolean = fileName == SOURCE_NAME

    /**
     * 源文本的写入临时件名（[writeSource] 用固定名）。
     *
     * 词库页的残留清理器据此把它纳入判据 —— 此前只认下载包的 `*.xz.tmp`，
     * 源文本写到一半进程被杀就永久留下（BUG.md L-857）。
     */
    fun isSourceTempName(fileName: String): Boolean = fileName == "$SOURCE_NAME.tmp"

    /**
     * [saveHuman] 的闸位：三档数据闸 + 两档写盘失败（L-861 拆分） + 写入互斥（L-859）。
     *
     * 写盘失败拆两档不是为了好看：源文本失败时**磁盘上什么都没变**（原样重试即可），
     * 包失败时**源文本已是新内容**（词条只剩「再点一次保存」）—— 同一句「保存失败」会让
     * 用户在两种完全不同的处境里猜磁盘状态（BUG.md L-861）。
     */
    enum class SaveGate {
        OK,
        TOO_BIG,
        TOO_MANY,
        NO_VALID,

        /** 源文本没写进去：一个文件都没动，原样重试即可 */
        WRITE_FAIL_SOURCE,

        /** 包没生成：源文本已落新内容，用户再点一次保存就能补上 */
        WRITE_FAIL_PACK,

        /** 另一次词库写入正在进行，本次一个字都没动 */
        BUSY,
    }

    /**
     * 写盘互斥（**进程级**，BUG.md L-859）。
     *
     * 两处 UI 的防连点（编辑页 `saving` / 词库页 `importingCustom`）都是**实例级私有**的 ——
     * 换页面 / 换实例就挡不住（导入 8MB 期间重进词库页再导入、保存中点关闭再回来点导入），
     * 而两条路写的是**同一个固定临时名**（`custom_user.txt.xz.tmp` / `custom_user.src.txt.tmp`）：
     * 两个 `FileOutputStream` 指向同一 inode（默认截断模式），交错写会把包或源文本写坏 ——
     * 源文本是「唯一真相」，写坏等于用户写的词条丢失。
     */
    private val writeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 占写盘位；返回 false = 已有写入在跑（[saveHuman] 内部用，也供单测构造并发场景） */
    internal fun beginWrite(): Boolean = writeInFlight.compareAndSet(false, true)

    /**
     * 是否有词库写入正在进行（只读）。
     *
     * 供 UI 在动手前把「忙」与「失败」分开显示（BUG.md L-865）：删除 / 恢复拿不到锁时不该
     * 只报一句笼统的「删除失败」，用户会以为是权限或磁盘问题。
     */
    val writing: Boolean get() = writeInFlight.get()

    /** 释放写盘位（与 [beginWrite] 成对；[saveHuman] 的 finally 保证异常路径也走到） */
    internal fun endWrite() {
        writeInFlight.set(false)
    }

    /**
     * @property gate    闸位（[SaveGate.OK] 以外都不该重启输入法）
     * @property entries 解析出的词条数
     * @property skipped 跳过的行数
     * @property filtered 字表闸（繁体 / 生僻字）打不出的条数（仅在成功路径统计）
     * @property dropped 同键词表超 [MAX_WORDS_PER_KEY] 被丢下的条数（BUG.md L-864，只在走完打包时统计）
     */
    data class SaveReport(
        val gate: SaveGate,
        val entries: Int,
        val skipped: Int,
        val filtered: Int,
        val dropped: Int = 0,
    )

    /**
     * 人写文本 → 包（+ 源文本）的**完整保存流程**，编辑页与导入页共用一份实现。
     *
     * 两条不变量（2026-10-05 修复 BUG.md L-851 / L-856；此前两处各写一份、都在同一个位置漏）：
     * ① **先判闸、后写盘**：[MAX_ENTRIES] 截断或没有合法词条时**一个字都不落盘**。此前是
     *    「先 `writePack` 再 `when` 判闸」，界面报「词条超过 5 万条」失败、磁盘上却已被替换成截断版。
     * ② **真相先落、派生后落**：先写源文本（用户输入，回显与再编辑的依据）再写包；源文本写不进去
     *    就**中止**（包不动）。反过来（先写包）会出现「包新文本旧」—— 用户在原内容上再保存，
     *    就等于用旧文本把新包静默回滚。
     *
     * 顺序上包写失败时源文本已更新（文本新、包旧）是**有意为之**：文本才是用户的意图、词库是它的
     * 派生物，用户可以再点一次保存重试，而不会丢自己写的内容。
     *
     * 实现分两遍：第一遍 [scanFormatted] 只统计（格式化后的长度 / 词条 / 截断），过闸后才由
     * [writeSourceFormatted] 逐行写出源文本 —— 两遍都不持有整份格式化文本（BUG.md L-858 / L-862）。
     *
     * @param dir 词库目录（`filesDir/dicts`）；传 File 让单测能直接验「不落盘」这条路径
     */
    fun saveHuman(dir: File, text: String, syllables: Set<String>? = null): SaveReport {
        // 互斥占位放在最前：并发时立刻返回，连逐行扫描都不必跑（BUG.md L-859）
        if (!beginWrite()) {
            Diagnostics.w(TAG, "另一次词库写入进行中，本次保存已拒绝（未落盘）")
            return SaveReport(SaveGate.BUSY, 0, 0, 0)
        }
        try {
            // 第一遍：逐行格式化 + 解析（[scanFormatted]）。判定与「formatHuman 再 parseHuman」逐项相同，
            // 但不再为全文留副本，入口 2× 保护下的内存峰值因此降下来（BUG.md L-858 / L-862）
            val scan = scanFormatted(text, syllables)
            if (scan.formattedChars > MAX_INPUT_CHARS) {
                Diagnostics.w(TAG, "保存中止: 文本超过 ${MAX_INPUT_CHARS / 1024 / 1024}M 字符上限（未落盘）")
                return SaveReport(SaveGate.TOO_BIG, 0, 0, 0)
            }
            val parsed = scan.result
            val n = parsed.entries.size
            if (parsed.truncated) {
                Diagnostics.w(TAG, "保存中止: 词条数超过 $MAX_ENTRIES 上限（已读到 $n 条，未落盘）")
                return SaveReport(SaveGate.TOO_MANY, n, parsed.skipped, 0)
            }
            if (n == 0) {
                Diagnostics.w(TAG, "保存中止: 没有合法词条（跳过 ${parsed.skipped} 行，未落盘）")
                return SaveReport(SaveGate.NO_VALID, 0, parsed.skipped, 0)
            }
            // 先打包（顺带拿到同键丢弃数，L-864），再按「源文本失败 = 磁盘没变 / 包失败 = 文本已落」
            // 两档分别上报（L-861）；源文本由第二遍逐行写出
            val pack = toRawPackLines(parsed.entries)
            if (writeSourceFormatted(dir, text, syllables) == null) {
                return SaveReport(SaveGate.WRITE_FAIL_SOURCE, n, parsed.skipped, 0, pack.dropped)
            }
            if (writePack(dir, pack) == null) {
                return SaveReport(SaveGate.WRITE_FAIL_PACK, n, parsed.skipped, 0, pack.dropped)
            }
            val filtered = PinyinEngine.countUnloadableWords(parsed.entries.map { it.text })
            return SaveReport(SaveGate.OK, n, parsed.skipped, filtered, pack.dropped)
        } finally {
            endWrite()
        }
    }

    /**
     * 备份导入侧的源文本放行判据：能按 UTF-8 读出且不超上限。
     *
     * 它只是用户自己的草稿（可能是半成品），不要求词条合法；限长是为了防止
     * 「超大文件塞进备份包 → 恢复时被整份读进内存」。
     */
    fun isValidSourceFile(file: File): Boolean = runCatching {
        file.inputStream().use { readUtf8Capped(it, MAX_INPUT_CHARS) }
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
