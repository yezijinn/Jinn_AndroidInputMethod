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
 *  - 入库走 [ClipboardStore.saveChunk]（[ClipboardStore] 的唯一入库入口，一块一个事务）：
 *    加密 / 自动分类 / 判重 / 置顶 / 单条闸一条不落，只是把**提交**从每行一次合成每
 *    [IMPORT_CHUNK_ROWS] 行一次（BUG.md L-1536）；
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

    /**
     * 每多少条插入裁一次（BUG.md L-1226）：裁剪要跑四条全表聚合，逐条裁在两万行导入时是分钟级；
     * 攒批的代价是中间态最多多占「本值 × 单条上限」的字节，末尾还有一次收尾裁剪兜住最终状态。
     * 实际峰值由文件级闸封顶：整份文件 ≤ [MAX_FILE_BYTES] ⇒ 中间态 ≤ 8MB + 一行（≤ 260KB）。
     */
    internal const val TRIM_EVERY = 100

    /**
     * 一块多少条写进**一个事务**（BUG.md L-1536）。
     *
     * 取 [TRIM_EVERY] 同值只是让「一块 = 一次裁剪」可预测；**裁剪落在事务之外靠的是调用点顺序**
     * （`ClipboardStore.saveChunk` 返回之后才 `trimTo`），与本值与块大小是否相等无关 ——
     * 裁剪的计数在 `runImport` 里按**成功条数**累加，所以两者解绑也不会把裁剪塞进事务，
     * 也不会多裁几次（总次数恒为 ⌊新增数 / TRIM_EVERY⌋）。
     * 一万行 = 100 次提交，而不是 10 000 次。
     *
     * 为什么不取更大：本机（MuMu · x86_64）10 000 行实测 chunk=1 中位 42.5 s（2 样本）、
     * chunk=100 中位 18.2 s（16.99 / 18.21 / 18.62）、chunk=500 中位 16.8 s（16.71 / 16.78 / 17.49）。
     * 100→500 只省 1.4 s（7.8%），且**两个区间重叠**（100 的最小值 16.99 < 500 的最大值 17.49）；
     * 而回滚窗口与单块持锁时间按块线性放大（100 条 ≈180 ms ⇒ 500 条 ≈0.9 s，这期间用户的复制
     * 与面板查询都排在锁外）⇒ 取 100（最坏丢 100 行 = 导入上限的 0.5%）。
     *
     * 内存上界：待写明文 ≤ 文件级闸 [MAX_FILE_BYTES]（8MB）+ 一行（≤ [ClipboardPrefs.MAX_ITEM_KB_CAP]KB），
     * **不是**「本值 × 单条上限」（100 × 256KB = 25MB 那种组合被 8MB 文件闸挡在外面，两个闸不会同时顶满）。
     */
    internal const val IMPORT_CHUNK_ROWS = TRIM_EVERY

    /** 中止原因（回执按此给用户一句话说明） */
    internal enum class Abort {
        FILE_TOO_LARGE,
        TOO_MANY_LINES,
        NOT_TEXT,

        /** 中途抛异常（断流 / 解码 / 写库）：前面的行已经入库，回执要说「部分保留」（BUG.md L-1325） */
        INTERRUPTED,
    }

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

    /**
     * 导入结果：打不开文件为 [openFailed]，否则 [stream] 非空（可能带 [Abort]）。
     *
     * [retained]＝本批导入的条目在**收尾裁剪之后**仍留在库里的条数（BUG.md L-1227）：
     * 容量上限（[ClipboardPrefs.maxItems]）会在导入过程中裁掉最早插入的那批，`added` 只表示
     * 「插入成功」，两者不等时回执不能只报前者 —— 用户会据此删掉原文件。
     */
    internal data class Result(
        val stream: StreamResult?,
        val openFailed: Boolean = false,
        val retained: Int? = null,
    )

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
        // 体积上限必须一并传给裁剪：只传条数会按库里的默认值（100MB）裁，
        // 把设了更大体积上限的用户的旧历史成批删掉（不可恢复）
        val maxTotalBytes = prefs.maxTotalBytes
        // 全库哈希一次性取回（只查一列、不解密，备份导入同款做法）：导入期间本线程持有的
        // 集合是「判重 + 统计重复数」的唯一依据
        val known = db.allHashes().toMutableSet()
        val input = runCatching { context.contentResolver.openInputStream(uri) }.onFailure {
            // 只记异常类名：日志禁出用户内容与文件路径（本模块统一口径）
            Diagnostics.w(TAG, "打开导入文件失败: ${it.javaClass.simpleName}")
        }.getOrNull() ?: return Result(stream = null, openFailed = true)

        // 本批插入成功的哈希与「距上次裁剪的条数」（L-1226 / L-1227）
        val imported = ArrayList<String>()
        var sinceTrim = 0
        val stream = input.use {
            importStream(
                input = it,
                limitBytes = limitBytes,
                knownHashes = known,
                writeChunk = { texts ->
                    // 一块一个事务（BUG.md L-1536）：入库仍走 ClipboardStore 这个唯一入口，
                    // 加密 / 分类 / 判重 / 置顶 / 坏行自愈 / 单条闸一条不落，只是把提交合并了
                    val ok = ClipboardStore.saveChunk(
                        context = context,
                        db = db,
                        texts = texts,
                        sourcePackage = context.packageName,
                        sourceAppName = SOURCE_APP_NAME,
                    )
                    for (i in texts.indices) {
                        if (!ok[i]) continue
                        imported.add(ClipboardDb.stableHash(texts[i]))
                        // 每 TRIM_EVERY 条裁一次（按**成功条数**计，与块边界无关）：既省掉绝大多数
                        // 全表聚合，又把中间态的增长限制在上限 + 一块以内；裁剪发生在 saveChunk
                        // 返回之后，所以它永远在事务之外（不是「块大小取同值」换来的）
                        if (++sinceTrim >= TRIM_EVERY) {
                            sinceTrim = 0
                            // 两个上限都要传用户值：只传条数的话体积会按库里的默认值（100MB）裁，
                            // 把设了更大体积上限的用户的旧历史成批删掉（且不可恢复）
                            db.trimTo(maxItems, maxTotalBytes)
                        }
                    }
                    ok
                },
            )
        }
        // 收尾必裁一次：批边界之后的尾巴也要落回上限内（L-1226）；这一步同时决定回执报多少（L-1227）
        db.trimTo(maxItems, maxTotalBytes)
        val present = db.allHashes()
        val retained = imported.count { it in present }
        Diagnostics.i(
            TAG,
            "文件导入完成: 行=${stream.lines} 新增=${stream.added} 重复=${stream.duplicate} " +
                "超限=${stream.tooLong} 空行=${stream.blank} 失败=${stream.failed} " +
                "保留=$retained 中止=${stream.abort?.name ?: "无"}",
        )
        return Result(stream, retained = retained)
    }

    /**
     * 流式导入（纯 JVM，可单测）：逐行有界读取 → 去重 → 攒块交给 [writeChunk] 入库。
     *
     * [knownHashes] 会被**就地更新**（库内已有 + 本批已接受）；某个块里有行入库失败时，那些
     * hash 在块末从集合移除，让同文件后续（下一块）的重复行还能再试一次（入库失败的原因通常是
     * 加密 / 写库异常，不是内容问题 —— 不该把它永久判为「已存在」）。块内紧跟的重复行在块末
     * 之前就已按重复跳过 —— 它的成败要到块末才知道，这条差异见 [IMPORT_CHUNK_ROWS] 与
     * `更新日志.md`（2026-10-31）。
     */
    internal fun importStream(
        input: InputStream,
        limitBytes: Int,
        knownHashes: MutableSet<String>,
        /**
         * 一行的入库动作改成「攒够 [IMPORT_CHUNK_ROWS] 行写一次」（BUG.md L-1536）：
         * 入参是**同序**的行文本，返回**同序**的「是否入库成功」（长度不足按失败算）。
         *
         * 为什么不是「一行一个回调」：回调返回布尔值意味着**写库已经在回调里做完了**，
         * 那样每条仍是一个隐式事务（一万行 = 一万次提交）。攒块之后每条的处理顺序、
         * 判重依据（[knownHashes] 仍逐行即时更新）、中止条件的位置都**原封不动**，
         * 变的只是提交粒度。
         */
        writeChunk: (List<String>) -> BooleanArray,
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
        // 待写块（不足一块的部分在流末或中止时补写）
        val pending = ArrayList<String>(IMPORT_CHUNK_ROWS)

        /**
         * 把攒下的行写掉并按结果记账。
         *
         * 失败的行**从 [knownHashes] 移除**：入库失败的原因通常是加密 / 写库异常，不是内容问题，
         * 不该把它永久判为「已存在」（同文件后面的重复行还能再试一次）。这条规则现在按**块**生效 ——
         * 同一块内、且同内容出现在失败行**之后**的重复行，会被当作重复跳过（那一行的成败要到块末才知道）。
         * 块只有 [IMPORT_CHUNK_ROWS] 行，且触发条件要求「前一条真的写库失败」，实测路径里
         * 只有 Keystore / 磁盘级故障；回执的三项之和（`added + duplicate + tooLong + blank + failed`）
         * 仍恒等于 `lines`，不受影响。
         */
        fun flushPending() {
            if (pending.isEmpty()) return
            val rows = ArrayList(pending)
            pending.clear()
            val results = try {
                writeChunk(rows)
            } catch (t: Throwable) {
                // 写库自己抛了：整批按失败计（与逐条版「onAccepted 抛异常」同口径），再把异常交给
                // 外层的中止判定 —— 记账不能因为异常而少一行
                failed += rows.size
                rows.forEach { knownHashes.remove(ClipboardDb.stableHash(it)) }
                throw t
            }
            for (i in rows.indices) {
                if (i < results.size && results[i]) {
                    added++
                } else {
                    failed++
                    knownHashes.remove(ClipboardDb.stableHash(rows[i]))
                }
            }
        }

        // 半途异常（断流 / 解码抛 / 写库抛）也要留下已累计的统计（BUG.md L-1325）：库里已经
        // 逐条提交了 added 条，回执若只报一句「导入失败」，用户会以为一条都没进、白导一轮
        try {
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
                        // 判重仍是**逐行即时**（库内已有 + 本批已接受）：攒批不能让块内重复漏判
                        if (!knownHashes.add(hash)) {
                            duplicate++
                            continue
                        }
                        pending.add(text)
                        if (pending.size >= IMPORT_CHUNK_ROWS) flushPending()
                    }
                }
            }
        } catch (t: Throwable) {
            abort = Abort.INTERRUPTED
            Diagnostics.w(TAG, "导入中途异常: ${t.javaClass.simpleName}")
        }
        // 收尾把不足一块的行写掉：中止（字节 / 行数 / 非文本）也是「已接受」的行 ——
        // 逐条写的旧口径里它们同样是立刻入库的，不能因为攒批而丢掉
        if (pending.isNotEmpty()) {
            runCatching { flushPending() }.onFailure {
                abort = Abort.INTERRUPTED
                Diagnostics.w(TAG, "导入收尾写库异常: ${it.javaClass.simpleName}")
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
