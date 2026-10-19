package com.jinn.inputmethod

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * 选择台账：只追加的「词 \t 读音」流水，一行 = 一次明确选择。
 *
 * 用途是给用户攒一份定制词库的原料 —— 因此**只增不删**：不淘汰、不衰减、不重写整表。
 * 频次由行数体现（同一个词出现 N 行即选了 N 次），加载/合并时汇总。
 *
 * 三条格式约定（改动前先读 `SelectionLedgerTest`）：
 * ① 一行一词：`词` 或 `词\t拼音`，`\n` 结尾、UTF-8；词与读音里的制表符 / 换行一律替换成空格；
 * ② 只认以 `\n` 结尾的完整行：崩溃可能留下半行，解析时整行丢弃（[parse]）；
 * ③ 写入走专用后台线程：按键路径不碰磁盘。
 *
 * 记录范围由调用方收口（[PinyinKeyboardView.learnChoice] 与追加候选中转）：
 * 只有 26 键候选真正上屏才算；粘贴、语音、翻译、图库、符号都不经过那条链。
 */
object SelectionLedger {

    const val FILE_NAME = "user_picks.txt"

    private const val TAG = "SelectionLedger"

    @Volatile
    private var target: File? = null

    /** 待写队列：只为把「拼行」这一步留在主线程（顺序即用户选择顺序），落盘仍在线程里 */
    private val queue = ConcurrentLinkedQueue<String>()

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jinn-selection-ledger").apply { isDaemon = true }
    }

    /** 是否记录（默认关；由用户在说明弹框里手输 OK 打开） */
    @Volatile
    private var enabled = false

    /**
     * 代际号：关闭并删除时 +1。
     *
     * 早先靠「`target !== file`」判换绑，而 [init] 每次都新建同路径的 File —— 重新绑定会被
     * 误判成换绑，直接把整队选择清掉。改用递增代际，只有真正删除才作废在途写入。
     */
    @Volatile
    private var generation = 0

    /** `\n` 的字节值：[truncateTrailingPartialLine] 按字节回找行尾用 */
    private const val NL_BYTE = '\n'.code.toByte()

    /** 绑定应用私有目录并下发开关（多次调用幂等；换 context 后重绑） */
    fun init(context: Context, enabled: Boolean) {
        target = File(context.filesDir, FILE_NAME)
        this.enabled = enabled
    }

    /** 运行时切换：关掉后不再写新行（已在队列里的仍会落盘，见 [deleteFile]） */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * 记一次选择。
     *
     * @param word 真正上屏的词（单字 / 词组 / 追加候选 / 合并后的整词）
     * @param pinyin 读音（音节空格分隔，可空：查不到读音时只记词）
     */
    fun record(word: String, pinyin: String) {
        if (!enabled) return
        val line = formatLine(word, pinyin) ?: return
        val file = target ?: return
        queue.add(line)
        val gen = generation
        writer.execute { drainTo(file, gen) }
    }

    /**
     * 批量追加（导入专用）：一次 open、一次 write，别把上万行拆成上万个任务。
     *
     * 与 [record] 共用同一条后台线程，提交顺序即落盘顺序。
     */
    fun appendAll(picks: List<Pair<String, String>>) {
        if (!enabled) return
        val file = target ?: return
        val gen = generation
        val lines = ArrayList<String>(picks.size)
        for ((w, p) in picks) formatLine(w, p)?.let { lines.add(it) }
        if (lines.isEmpty()) return
        writer.execute {
            if (gen != generation) return@execute
            runCatching {
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { out ->
                    for (line in lines) {
                        out.write(line)
                        out.newLine()
                    }
                }
            }.onFailure { e ->
                Diagnostics.w(TAG, "台账批量写入失败: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 把已排队的内容全部写盘（收键盘 / 退出时调用；不抛异常）。
     *
     * @return 是否确实写空（超时 / 被打断都返回 false）
     */
    fun flush(): Boolean {
        val file = target ?: return true
        val gen = generation
        // 带超时：队列里可能压着一次上万行的批量导入，主线程不该无限等
        val done = runCatching {
            writer.submit { drainTo(file, gen) }
                .get(FLUSH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            queue.isEmpty()
        }.getOrElse { false }
        if (!done) Diagnostics.w(TAG, "台账 flush 未在 ${FLUSH_TIMEOUT_MS}ms 内写空")
        return done
    }

    /**
     * 关闭功能：先停记录、把队列写空，再删文件。
     *
     * 顺序不能反 —— 先删文件再 flush，会把队列里刚写的几行重新建出来。
     */
    fun deleteFile(context: Context): Boolean {
        enabled = false
        val file = File(context.filesDir, FILE_NAME)
        if (!flush()) {
            // 队列还没写空就删，正在进行的 drain 会把文件重建出来（回执却是「已删除」）
            return false
        }
        // 先升代际再删：在途 / 排队的写入会自行放弃，不会把文件建回来
        generation++
        val ok = deleteQuietly(file)
        if (!ok) {
            // 删除失败：退回原代际并保留绑定，调用方写回 pref 之后记录仍能继续
            generation--
            target = file
        }
        return ok
    }

    /** 删除单文件（JVM 可测）：文件本来就不存在也算成功 */
    internal fun deleteQuietly(file: File): Boolean {
        if (!file.exists()) return true
        return runCatching { file.delete() }
            .onFailure { Diagnostics.w(TAG, "台账删除失败: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }

    private fun drainTo(file: File, gen: Int) {
        // 已被关闭删除（代际已升）：这一批不再写，否则「已删除」之后磁盘上又出现文件
        if (gen != generation) {
            queue.clear()
            return
        }
        if (queue.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            // 先截掉上一次可能留下的残行（掉电 / 进程在 write 中途被杀）：行与行之间没有任何
            // 帧标记，残行会与下一次追加的首行**拼成一条完整行**，而 parse 只丢弃「末尾没有 \n」
            // 的那一段 —— 拼上之后它就有了 \n，会被当成真数据解析出脏词（2026-10-19 修）。
            // 补一个 "\n" 是错的：那会把残行原样留成一条完整行。必须按字节截断。
            truncateTrailingPartialLine(file)
            FileOutputStream(file, true).use { out ->
                while (true) {
                    val line = queue.poll() ?: break
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
                out.flush()
            }
        }.onFailure { e ->
            // 写失败不能拖累输入：丢掉这一批并留痕（文件是「只增」的，重试会把顺序打乱）
            queue.clear()
            Diagnostics.w(TAG, "台账写入失败: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 截掉文件末尾的残行（最后一个 `\n` 之后的内容）。
     *
     * 只在「确实有残行」时才动文件：正常路径下文件以 `\n` 结尾，这里是零写入的快检查，
     * 不会给每次追加都加一次随机写。
     */
    internal fun truncateTrailingPartialLine(file: File) {
        if (!file.isFile || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { raf ->
            val len = raf.length()
            // 从尾部往回找最后一个 \n（上限 64KB：残行再长也不该超过一行合理长度）
            val window = minOf(len, 64L * 1024)
            val buf = ByteArray(window.toInt())
            raf.seek(len - window)
            raf.readFully(buf)
            var cut = -1
            for (i in buf.indices.reversed()) {
                if (buf[i] == NL_BYTE) {
                    cut = i
                    break
                }
            }
            // 整个窗口都没有 \n ⇒ 这个文件本身就是一条残行（或首行就没写完整），整体清掉
            val keep = if (cut < 0) len - window else len - window + cut + 1
            if (keep < len) {
                raf.setLength(keep)
                Diagnostics.w(TAG, "台账截掉残行: ${len - keep} 字节（上次写入中断）")
            }
        }
    }

    /** 拼一行；词为空返回 null。制表符 / 换行会破坏格式，统一压成空格。 */
    internal fun formatLine(word: String, pinyin: String): String? {
        val w = word.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        if (w.isEmpty()) return null
        val p = pinyin.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        return if (p.isEmpty()) w else "$w\t$p"
    }

    /**
     * 解析流水：**只认以 `\n` 结尾的完整行**（末尾残缺行丢弃），空行与 `#` 注释跳过。
     *
     * 第二列缺失时读音记空串（导入的词可能只有词没有读音）。
     */
    internal fun parse(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) break
            val line = text.substring(start, nl).trim('\r')
            start = nl + 1
            if (line.isEmpty() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            val w = (if (tab < 0) line else line.substring(0, tab)).trim()
            if (w.isEmpty()) continue
            val p = if (tab < 0) "" else line.substring(tab + 1).trim()
            out.add(w to p)
        }
        return out
    }

    /**
     * 同词同音合并计数，保持首次出现次序（供手动合并 / 导出 / 导入预览用）。
     *
     * 分组键是「词 + 读音」：同一个词用不同拼音打出算两条，互不覆盖。
     */
    internal fun aggregate(picks: List<Pair<String, String>>): Map<Pair<String, String>, Int> {
        val out = LinkedHashMap<Pair<String, String>, Int>()
        for (pick in picks) out[pick] = (out[pick] ?: 0) + 1
        return out
    }

    // ── 词库查看 / 导出 / 导入（手动操作，不参与输入热路径）────────────────────

    /** 导出格式：三档通用格式，覆盖「导入别的输入法」「表格查看」「纯词表备份」 */
    enum class ExportFormat(val label: String, val ext: String, val mime: String) {
        RIME_YAML("Rime 词库", "dict.yaml", "text/yaml"),
        TSV("制表符表格", "tsv", "text/tab-separated-values"),
        TXT("纯词表", "txt", "text/plain"),
    }

    /** flush 的等待上限（毫秒）：宁可丢最后几行，也不让主线程卡在批量写盘上 */
    private const val FLUSH_TIMEOUT_MS = 2_000L

    /** 单次导入上限：防手滑选到巨型文件把内存与文件一起撑爆 */
    internal const val IMPORT_MAX_LINES = 50_000

    /** 单个词导入的次数上限：导出再导入时按次数补行，但别让一个词独占整个文件 */
    internal const val IMPORT_MAX_PER_WORD = 1_000

    /** 读整份流水（文件不存在返回空表）；解析口径与 [parse] 一致 */
    fun readAll(context: Context): List<Pair<String, String>> {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.isFile) return emptyList()
        return runCatching { parse(f.readText(Charsets.UTF_8)) }
            .onFailure { Diagnostics.w(TAG, "台账读取失败: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
    }

    /**
     * 渲染导出文本。
     *
     * 次序固定为「次数降序、同次数按词」——导出件本身可读，也便于对比两次导出。
     */
    internal fun exportText(
        merged: Map<Pair<String, String>, Int>,
        format: ExportFormat,
    ): String {
        val rows = merged.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy { it.key.first })
        val sb = StringBuilder(rows.size * 24 + 128)
        when (format) {
            ExportFormat.RIME_YAML -> {
                // name 必须与落盘文件名（user_dict.dict.yaml）一致，否则 Rime 找不到
                sb.append("---\nname: user_dict\nversion: \"1\"\nsort: by_weight\n")
                sb.append("use_preset_vocabulary: false\n...\n")
                for ((key, count) in rows) {
                    // 没有读音的行写不出合法音节，librime 会整条丢弃：直接不写
                    if (key.second.isEmpty()) continue
                    sb.append(key.first).append('\t').append(key.second)
                        .append('\t').append(count).append('\n')
                }
            }

            ExportFormat.TSV -> {
                sb.append("词\t拼音\t次数\n")
                for ((key, count) in rows) sb.append(key.first).append('\t').append(key.second)
                    .append('\t').append(count).append('\n')
            }

            ExportFormat.TXT -> {
                // 纯词表按词去重（读音不同的同词只出现一次），次数降序
                val seen = LinkedHashSet<String>()
                for ((key, _) in rows) seen.add(key.first)
                for (w in seen) sb.append(w).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 解析导入件，返回「按次数展开」后的选择流。
     *
     * 三种格式按行自适应（不需要用户选格式）：`词\t拼音\t次数` / `词\t拼音` / 一行一词；
     * YAML 的头（`---`、`...`、`key: value`）与 `#` 注释跳过。次数缺省按 1，单词语上限
     * [IMPORT_MAX_PER_WORD]，总行数上限 [IMPORT_MAX_LINES]（超出即截断，不报错）。
     */
    internal fun parseImport(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        // Windows / 部分导出工具会带 UTF-8 BOM：不剥的话第一条恒为「\uFEFF词」
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            if (out.size >= IMPORT_MAX_LINES) break
            val line = raw.trim('\r', ' ', '\t')
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line == "---" || line == "...") continue
            // YAML 头：`name: xxx`（值里没有制表符）——不是词条
            if (line.indexOf('\t') < 0 && line.contains(": ")) continue
            val cols = line.split('\t')
            val word = cols[0].trim()
            if (word.isEmpty()) continue
            // 自家 TSV 的表头行不是词条
            if (word == "词" && cols.getOrNull(1)?.trim() == "拼音") continue
            val pinyin = cols.getOrNull(1)?.trim().orEmpty().let { if (it == "-") "" else it }
            val count = cols.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(1, IMPORT_MAX_PER_WORD) ?: 1
            var n = count
            while (n-- > 0 && out.size < IMPORT_MAX_LINES) out.add(word to pinyin)
        }
        return out
    }

}
