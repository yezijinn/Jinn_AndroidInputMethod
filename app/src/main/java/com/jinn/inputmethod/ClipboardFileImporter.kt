package com.jinn.inputmethod

import android.content.Context
import android.net.Uri
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.io.Reader

/**
 * 外部文本文件导入剪贴板历史（2026-10-10 用户拍板）。
 *
 * 口径：**按行拆分** —— 每个非空行一条，行首尾空白裁掉，空行跳过。
 * 用途：批量常用短语 / 账号 / 地址类 txt，导入后可在面板逐条粘贴。
 *
 * 边界与闸门（全部与既有路径同源，不新增「只有导入才有的」宽松口径）：
 *  - 单条上限与采集 / 粘贴同源（[ClipboardPrefs.effectiveMaxItemBytes]）：超限行**整行丢弃**，
 *    不截断入库 ——「半截比不记更糟」；
 *  - 入库走 [ClipboardStore.save]（全应用唯一入库入口）：加密 / 自动分类 / 条数裁剪 / 单条闸一次不落；
 *  - 去重与备份导入同源（[ClipboardDb.stableHash] + [ClipboardDb.allHashes]）：命中即跳过，
 *    **不覆盖**既有条目的来源与时间 —— 导入是幂等的，同一文件可重复导入；
 *  - 文件级两道闸：总字节 ≤ [MAX_FILE_BYTES]、物理行数 ≤ [MAX_IMPORT_LINES]，超限**中止**，
 *    已导入的部分保留（回执如实说明，绝不静默）；
 *  - 「像不像文本」按**文件级**累计判定（含 NUL 或替换字符占比 >1% 即中止）：判据与采集侧
 *    同源，但按整份聚合 —— 单行太短，逐行判定时一个替换字符就能超过 1% 而误杀正常文件；
 *  - 日志只记条数类统计，**禁出正文**（与全模块同一口径）。
 *
 * 不触碰系统剪贴板：只进历史库，不写 setPrimaryClip（导入的条目在面板里点击即可粘贴）。
 */
internal object ClipboardFileImporter {

    private const val TAG = "ClipboardImport"

    /**
     * 导入文件的总字节上限（按底层已读原始字节计）。
     *
     * 取 8MB：低于备份包剪贴板节的 12MB 导出预算一档，且远小于单次导入的行数闸对应体量
     * （20k 行 × 256KB 单条上限的极端组合不可能出现 —— 两道闸同时管）。
     */
    internal const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 单次导入的物理行数上限（含空行）。取 [ClipboardPrefs.MAX_ITEMS_CAP]：再多也留不住 */
    internal const val MAX_IMPORT_LINES = 20_000

    /** 导入条目的来源显示名（面板「来源」列用；包名用真实包名，不伪造来源） */
    internal const val SOURCE_APP_NAME = "文件导入"

    /** 中止原因（回执按此给用户一句话说明） */
    internal enum class Abort { FILE_TOO_LARGE, TOO_MANY_LINES, NOT_TEXT }

    /** 流级统计（纯数据，便于单测断言） */
    internal data class StreamResult(
        val lines: Int,
        val added: Int,
        val duplicate: Int,
        val tooLong: Int,
        val blank: Int,
        val failed: Int,
        val abort: Abort?,
    )

    /** 导入结果：打不开文件为 [openFailed]，否则 [stream] 非空（可能带 [Abort]） */
    internal data class Result(val stream: StreamResult?, val openFailed: Boolean = false)

    /**
     * 从 SAF 选中的文件导入（无存储权限）。
     *
     * 调用前必须已确认剪贴板总开关为开：关着导入没有意义（面板 / 搜索都不工作），
     * 由界面侧先拦并提示（[ClipboardPrefs.enabled]）。
     *
     * **任何异常都收成 [Result]，不向上抛**：本函数在后台线程跑，线程级兜底（`BackgroundIo.wrapped`）
     * 只记日志、不回界面 —— 异常逃出去会让调用方的「导入中」标志永不复位，按钮此后连点毫无反馈地
     * 失效。异常在这里自己记一条 E（带栈，线程兜底不会再看到它）；已入库的部分保留
     * （判重按内容哈希，重导安全且幂等）。
     */
    internal fun importFromUri(context: Context, uri: Uri, db: ClipboardDb, prefs: ClipboardPrefs): Result =
        runCatching { runImport(context, uri, db, prefs) }.getOrElse { e ->
            Diagnostics.e(TAG, "文件导入异常: ${e.javaClass.simpleName}", e)
            Result(stream = null)
        }

    private fun runImport(context: Context, uri: Uri, db: ClipboardDb, prefs: ClipboardPrefs): Result {
        val limitBytes = prefs.effectiveMaxItemBytes().toInt()
        val maxItems = prefs.maxItems
        // 全库哈希一次性取回（只查一列、不解密，备份导入同款做法）：导入期间本线程持有的
        // 集合是「判重 + 统计重复数」的唯一依据
        val known = db.allHashes().toMutableSet()
        val input = runCatching { context.contentResolver.openInputStream(uri) }.onFailure {
            // 只记异常类名：日志禁出用户内容与文件路径（本模块统一口径）
            Diagnostics.w(TAG, "打开导入文件失败: ${it.javaClass.simpleName}")
        }.getOrNull() ?: return Result(stream = null, openFailed = true)

        val stream = input.use {
            importStream(
                input = it,
                limitBytes = limitBytes,
                knownHashes = known,
                onAccepted = { text ->
                    ClipboardStore.save(
                        context = context,
                        db = db,
                        text = text,
                        sourcePackage = context.packageName,
                        sourceAppName = SOURCE_APP_NAME,
                        maxItems = maxItems,
                        // 逐条 V 日志合并为下面一条汇总 I：两万行导入逐条落盘会淹掉诊断
                        logPerItem = false,
                    ) != null
                },
            )
        }
        Diagnostics.i(
            TAG,
            "文件导入完成: 行=${stream.lines} 新增=${stream.added} 重复=${stream.duplicate} " +
                "超限=${stream.tooLong} 空行=${stream.blank} 失败=${stream.failed} " +
                "中止=${stream.abort?.name ?: "无"}",
        )
        return Result(stream)
    }

    /**
     * 流式导入（纯 JVM，可单测）：逐行有界读取 → 去重 → [onAccepted] 入库。
     *
     * [knownHashes] 会被**就地更新**（库内已有 + 本批已接受）；[onAccepted] 返回 false 时把该
     * hash 从集合移除，让同文件后续重复行还能再试一次（入库失败的原因通常是加密 / 写库异常，
     * 不是内容问题 —— 不该把它永久判为「已存在」）。
     */
    internal fun importStream(
        input: InputStream,
        limitBytes: Int,
        knownHashes: MutableSet<String>,
        onAccepted: (String) -> Boolean,
        maxLines: Int = MAX_IMPORT_LINES,
        maxFileBytes: Long = MAX_FILE_BYTES,
    ): StreamResult {
        val counted = CountingInputStream(input)
        // 行内字节闸：外层闸只在「两行之间」判，一条没有换行的巨行会绕开它把整份文件读完
        // （纯消耗，几秒到几十秒）。预算检查下沉给读行器，每 4K 字符抽样一次
        val reader = BoundedLineReader(
            InputStreamReader(counted, Charsets.UTF_8),
            maxLineBytes = limitBytes,
            overBudget = { counted.bytesRead > maxFileBytes },
        )
        var lines = 0
        var added = 0
        var duplicate = 0
        var tooLong = 0
        var blank = 0
        var failed = 0
        var totalChars = 0L
        var badChars = 0L
        var abort: Abort? = null

        while (true) {
            // 字节闸按底层已读字节计（含解码器预读）：只保证「文件级拦截」，不保证恰好卡在第 N 行
            // （多读的那点到不了入库路径）；行内另有抽样早退（见 BoundedLineReader.overBudget），
            // 无换行的巨行也不会把整份文件读完
            if (counted.bytesRead > maxFileBytes) {
                abort = Abort.FILE_TOO_LARGE
                break
            }
            val line = reader.nextLine() ?: break
            lines++
            // 行数闸放在读行之后判：正好 [maxLines] 行的文件读完即正常结束，不算超限
            if (lines > maxLines) {
                abort = Abort.TOO_MANY_LINES
                break
            }
            when (line) {
                is BoundedLineReader.Line.TooLong -> tooLong++

                is BoundedLineReader.Line.Text -> {
                    val text = line.value.trim()
                    if (text.isEmpty()) {
                        blank++
                        continue
                    }
                    // 文件级「像不像文本」：NUL 立即中止；替换字符占比 >1% 中止
                    totalChars += text.length
                    badChars += text.count { it == '\uFFFD' }
                    if (text.indexOf('\u0000') >= 0 || badChars * 100 > totalChars) {
                        abort = Abort.NOT_TEXT
                        break
                    }
                    val hash = ClipboardDb.stableHash(text)
                    if (!knownHashes.add(hash)) {
                        duplicate++
                        continue
                    }
                    if (onAccepted(text)) {
                        added++
                    } else {
                        failed++
                        knownHashes.remove(hash)
                    }
                }
            }
        }
        // 早退（无换行巨行越预算）在读行器里表现为 EOF：这里把中止原因认回来，
        // 否则会伪装成「正常读完」（用户看不到任何中止说明）
        if (abort == null && counted.bytesRead > maxFileBytes) abort = Abort.FILE_TOO_LARGE
        return StreamResult(lines, added, duplicate, tooLong, blank, failed, abort)
    }

    // ── 行级有界读取 ────────────────────────────────────────

    /**
     * 逐行读取；单行超 [maxLineBytes] 时**丢弃该行内容**并继续消耗到行尾（不返回半截）。
     *
     * 行分隔与 `BufferedReader.readLine` 同口径（`\n` / `\r` / `\r\n`）；文件首字符若是
     * BOM（U+FEFF）则剥离，不计内容也不算字节。
     */
    internal class BoundedLineReader(
        reader: Reader,
        private val maxLineBytes: Int,
        /**
         * 文件级字节预算是否已超（每 4096 字符抽样一次；默认不查）。
         *
         * 为什么需要它：外层字节闸只在两行之间判，整份文件没有换行时（二进制 / 单行 JSON）
         * `nextLine` 会一直消耗到 EOF 才返回 —— 预算拦不住它。命中即返回 null（对调用方表现为
         * EOF；调用方随后按「已读字节」把中止原因认回来）。
         */
        private val overBudget: () -> Boolean = { false },
    ) {

        internal sealed interface Line {
            class Text(val value: String) : Line

            /** 单行超过上限：内容已丢弃，行已消耗 */
            object TooLong : Line
        }

        private val reader = PushbackReader(reader, 1)

        /** 是否还在文件最开头（BOM 只在首字符位置剥离） */
        private var atStart = true

        /** 距上次预算抽样已读的字符数（按字符计，不必每字符都回调） */
        private var sinceBudgetCheck = 0

        /** 返回下一行；null = 文件结束 */
        fun nextLine(): Line? {
            val sb = StringBuilder()
            var bytes = 0
            var tooLong = false
            var any = false
            while (true) {
                val ci = reader.read()
                if (ci < 0) {
                    if (!any) return null
                    break
                }
                any = true
                if (++sinceBudgetCheck >= BUDGET_CHECK_EVERY_CHARS) {
                    sinceBudgetCheck = 0
                    if (overBudget()) return null
                }
                val ch = ci.toChar()
                if (atStart) {
                    atStart = false
                    if (ch == '\uFEFF') continue
                }
                if (ch == '\n') break
                if (ch == '\r') {
                    // \r\n 一起消耗；单独的 \r 也分行（老式纯 \r 文本）
                    val next = reader.read()
                    if (next >= 0 && next.toChar() != '\n') reader.unread(next)
                    break
                }
                if (tooLong) continue
                bytes += utf8Len(ch)
                if (bytes > maxLineBytes) {
                    tooLong = true
                    sb.setLength(0)
                } else {
                    sb.append(ch)
                }
            }
            return if (tooLong) Line.TooLong else Line.Text(sb.toString())
        }

        /** 单个 UTF-16 字符的 UTF-8 字节数（代理对两半各记 2：合起来 4，与真实编码一致） */
        private fun utf8Len(ch: Char): Int {
            val c = ch.code
            return when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDFFF -> 2
                else -> 3
            }
        }

        private companion object {
            /** 预算抽样间隔（字符）：约 4K 字符一次，超读上界也就一个抽样窗口 */
            const val BUDGET_CHECK_EVERY_CHARS = 4096
        }
    }

    /** 统计底层已读原始字节（解码器预读也算：它是「这个文件读了多少」的真实计量） */
    private class CountingInputStream(src: InputStream) : FilterInputStream(src) {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }
}
