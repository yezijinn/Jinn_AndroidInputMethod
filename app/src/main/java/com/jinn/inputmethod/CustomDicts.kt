package com.jinn.inputmethod

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream

/**
 * 用户自定义补充词库：把「人写的 .txt」转成引擎已在用的可选词库包，
 * 落在 `filesDir/dicts/custom_user.txt.xz`，与下载的可选包走同一条装载 / 备份通道。
 *
 * 人写的文件（UTF-8，一行一条；第三列及以后忽略 —— 与 `tools/dict_builder/add_words.py`
 * 的补词表格式一致，那一列是插入位置，不是词频）：
 * ```
 * # 注释
 * 张三<TAB>zhang san
 * 机器学习<TAB>ji qi xue xi
 * ```
 *
 * 打包 raw（`PhraseIndex.build` / `loadExtensionDict` 读的形态，与 `build_dicts.py` 产出同格式）：
 * ```
 * jiqixuexi<TAB>机器学习
 * zhangsan<TAB>张三
 * ```
 *
 * 职责边界：本层只做「校验 + 归一 + 打包 + 原子落盘」；装载复用 `PinyinEngine.loadExtensionDict`
 * 扫 `dicts/` 目录下全部 `.xz` 的既有路径，备份复用 `ConfigBackup` 的 dicts 节（导出按目录收，
 * 导入对这一件额外做**结构校验**后放行，见 [isValidPackFile]）。
 */
internal object CustomDicts {

    /** 落地文件名；放在 `dicts/` 下才会被引擎扫描与备份收录 */
    const val PACK_NAME = "custom_user.txt.xz"

    /** 单次导入的**解压后文本**上限（8MB）：防误选巨型文件把内存与解析拖死 */
    const val MAX_INPUT_BYTES = 8 * 1024 * 1024

    /** 词条数上限：个人补充足够用，也保证索引构建是秒级 */
    const val MAX_ENTRIES = 50_000

    /** 单键词数上限：`PhraseIndex.build` 对同键词表有 64KB 硬闸，超了整包拒收 */
    const val MAX_WORDS_PER_KEY = 100

    /** 单个词的长度上限（字符）：防超长行混进来 */
    const val MAX_TEXT_CHARS = 64

    /** 引擎侧音节表资产名（与 `PinyinEngine.SYLLABLES_ASSET` 同名，那边是 private 常量） */
    private const val SYLLABLES_ASSET = "pinyin_syllables.txt.xz"

    private val KEY_SHAPE = Regex("[a-z]+")
    private val TOKEN_SPLIT = Regex("[\\s']+")

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
            // TAB 优先（工具格式）；没有 TAB 时接受「两个以上空格」这种从表格粘贴的形态
            val tabbed = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            val pair = if (tabbed.size >= 2) {
                tabbed[0] to tabbed[1]
            } else {
                val cols = line.split(Regex("\\s{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
                if (cols.size < 2) {
                    skipped++
                    continue
                }
                cols[0] to cols[1]
            }
            val word = pair.first
            val normalized = normalizeSyllables(pair.second, syllables)
            if (word.length > MAX_TEXT_CHARS || normalized == null) {
                skipped++
                continue
            }
            val key = normalized.replace(" ", "")
            if (!seen.add(word + "\u0000" + key)) {
                skipped++
                continue
            }
            entries += Entry(word, normalized)
        }
        return Result(entries, skipped, truncated)
    }

    /** 音节归一：小写、`ü`→`v`、剥声调数字、逐节形状与音节表校验；不合法返回 null */
    private fun normalizeSyllables(token: String, syllables: Set<String>?): String? {
        val parts = token.lowercase().replace("ü", "v").split(TOKEN_SPLIT).filter { it.isNotEmpty() }
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
            val set = byKey.getOrPut(e.pinyin.replace(" ", "").lowercase()) { LinkedHashSet() }
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
            if (!KEY_SHAPE.matches(key)) return false
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

    fun deletePack(context: Context): Boolean = runCatching { packFile(context).delete() }.getOrDefault(false)

    fun isPackName(fileName: String): Boolean = fileName == PACK_NAME

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
