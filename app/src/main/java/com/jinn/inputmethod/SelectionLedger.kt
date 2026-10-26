package com.jinn.inputmethod

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.crypto.SecretKey

/**
 * 选择台账：只追加的「词 \t 读音」流水，一行 = 一次明确选择。
 *
 * 用途是给用户攒一份定制词库的原料 —— 因此**只增不删**：记录级永不淘汰、不衰减、不改写既有行。
 * 频次由行数体现（同一个词出现 N 行即选了 N 次），加载/合并时汇总。
 *
 * 「只增」的边界（按实现，2026-10-26 订正）：整份**重写**只有明文迁移与补首行标识两处
 * （[migrateIfNeeded]，内容一条不丢、次序不变）；整份**删除**只有用户在词库页关掉记录时的
 * 「关闭并删除」（[deleteFile]）那一次；残行截尾（[truncateTrailingPartialLine]）只切上次写
 * 中断的半行。除此之外系统不会删减任何已记录的选择。
 *
 * 三条格式约定（改动前先读 `SelectionLedgerTest`）：
 * ① 一行一词：`词` 或 `词\t拼音`，`\n` 结尾、UTF-8；词与读音里的制表符 / 换行一律替换成空格；
 * ② 只认以 `\n` 结尾的完整行：崩溃可能留下半行，解析时整行丢弃（[parse]）；
 * ③ 写入走专用后台线程：按键路径不碰磁盘。
 *
 * **落盘是逐行加密的**（[LedgerCrypto]）：磁盘上每行是 `1:base64(iv||密文)`，明文只在内存里。
 * 之所以不整文件加密：只增流水整表重写会违背「记录不影响性能」；行级 AEAD 仍是 O(1) 追加。
 * 旧版明文文件在首次读写时就地迁移（[migrateIfNeeded]），数据一条不丢。
 *
 * 记录范围由调用方收口（[PinyinKeyboardView.learnChoice] 与追加候选中转）：
 * 只有 26 键候选真正上屏才算；粘贴、语音、翻译、图库、符号都不经过那条链。
 */
object SelectionLedger {

    const val FILE_NAME = "user_picks.txt"

    /** 台账数据密钥（被 Keystore 包裹，独立别名；见 [LedgerCrypto.KEY_FILE]） */
    const val KEY_FILE_NAME = LedgerCrypto.KEY_FILE

    private const val TAG = "SelectionLedger"

    @Volatile
    private var target: File? = null

    /** 待写队列：只为把「拼行」这一步留在主线程（顺序即用户选择顺序），落盘仍在线程里 */
    private val queue = ConcurrentLinkedQueue<String>()

    /**
     * 上一轮落盘是否成功（N4）：[flush] 的返回值必须同时看「队列空了」与它。
     *
     * `writeLines` 的失败原先只在写线程里打一条 W，而 `flush()` 排在同一个 executor 后面，
     * 只能知道「队列空了」⇒ 密钥不可用 / 磁盘满时界面照报「已追加 N 条」。
     */
    @Volatile
    private var lastWriteOk = true

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

    /**
     * 明文 → 加密的就地迁移锁。
     *
     * 读（页面加载，`io` 线程）与写（台账写线程）都会先走到迁移：没有这把锁时两条线程可能同时
     * 读到明文、各写一份迁移结果，后改名的那份覆盖前一份 —— 迁移本身是幂等的，但中间态会丢写入。
     */
    private val migrateLock = Any()

    /** 绑定应用私有目录并下发开关（多次调用幂等；换 context 后重绑） */
    fun init(context: Context, enabled: Boolean) {
        target = File(context.filesDir, FILE_NAME)
        this.enabled = enabled
    }

    /** 运行时切换：关掉后不再写新行（已在队列里的仍会落盘；要连文件一起清走的是 [deleteFile]） */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * 测试注入的数据密钥：`null` = 走真实路径（[LedgerCrypto.dataKey]）。
     *
     * JVM 单测没有 Android Keystore，而「写失败不丢批」这条必须真跑一遍写线程才看得出（[flush]）。
     * 与 [UserFrequency.setKeyForTest] 同一口径，生产路径恒为 null。
     */
    @Volatile
    private var keyForTest: SecretKey? = null

    /** 测试用：注入数据密钥（null 复位成真实路径） */
    internal fun setKeyForTest(key: SecretKey?) {
        keyForTest = key
    }

    /** 测试用：绑定目标文件与开关（JVM 上没有 Context；null = 解绑） */
    internal fun setTargetForTest(file: File?, enabled: Boolean = true) {
        target = file
        this.enabled = enabled
    }

    /** 测试用：当前待写的行数（「写失败不丢批」的判据：失败后必须还在这里） */
    internal fun pendingLinesForTest(): Int = queue.size

    /**
     * 测试用：清掉待写队列。
     *
     * 队列是进程级单例，而「写失败」用例故意留下未落盘的行 —— 不清就会串到下一个用例
     * （实测：本类两条失败用例互相把对方的行数抬高，断言全错）。
     */
    internal fun clearQueueForTest() {
        queue.clear()
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
     * 与 [record] 共用**同一条队列 + 同一个落盘入口**（[drainTo]）：提交顺序即落盘顺序，
     * 写失败时批次仍留在队列里等下次重试。
     *
     * @return false = **没有排进写入**（功能已关闭 / 未绑定 / 没有有效行）。
     *   调用方必须据此改回执 —— 早期写法静默返回，界面照报「已追加 N 条」（2026-10-21 修）。
     *   排进队列 ≠ 写成功：真落盘结果由随后的 [flush] 回传（[lastWriteOk]）。
     */
    fun appendAll(picks: List<Pair<String, String>>): Boolean {
        if (!enabled) return false
        val file = target ?: return false
        val gen = generation
        val lines = ArrayList<String>(picks.size)
        for ((w, p) in picks) formatLine(w, p)?.let { lines.add(it) }
        if (lines.isEmpty()) return false
        // ⚠ 必须入队（2026-10-22，L-1410）。早期写法在这里直接 `writeLines`：失败只置
        // `lastWriteOk` 并写「已丢弃这一批」，而队列里什么也没有 ⇒ drainTo 无从重试，整批消失；
        // 同一缺陷的另一面是 flush 那句复位 —— 复位一旦落在失败写之后，旗标被抹平、队列本来就是
        // 空的 ⇒ 界面报「已追加 N 条」而台账一行没多（时序相关，不是必现）。
        // 入队后与 record 同一条路径：失败留队、成功才出队，批量仍是一次 open + 一次 write。
        return runCatching {
            queue.addAll(lines)
            writer.execute { drainTo(file, gen) }
            true
        }.getOrElse {
            // 提交失败（executor 已关）：行已在队列里，留给下一次 record / flush 带走 ——
            // 回执此时必须说「没排进去」，绝不假装成功
            Diagnostics.w(TAG, "台账批量写入提交失败: ${it.javaClass.simpleName}")
            false
        }
    }

    /**
     * 把已排队的内容全部写盘（收键盘 / 退出时调用；不抛异常）。
     *
     * @return 队列已空**且**这一轮写入确实成功。超时 / 被打断 / 磁盘或密钥失败都返回 false。
     *
     * 判据必须含 [lastWriteOk]：只看「队列空了」的话，[writeLines] 返回 false（密钥不可用 /
     * 磁盘满）照样算写空 ⇒ 导入路径会把「0 条入库」报成「已追加 N 条」（N4）。
     */
    fun flush(): Boolean {
        val file = target ?: return true
        val gen = generation
        // 提交前复位：本次提交没有写失败才算成功。
        // ⚠ 这条复位只有在「**任何写失败都不丢行**」的前提下才成立 —— record / appendAll 都走
        // [drainTo]，失败只置位、不出队，所以失败的那批会由下面的 drainTo 重写并**重新**置位。
        // 早期 appendAll 不入队时，失败的行根本不在队列里，这条复位于是把一次真实的丢失
        // 抹成了「成功」，界面谎报「已追加 N 条」（L-1410 的直接成因）。
        lastWriteOk = true
        // 带超时：队列里可能压着一次上万行的批量导入，主线程不该无限等
        val drained = runCatching {
            writer.submit { drainTo(file, gen) }
                .get(FLUSH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            queue.isEmpty()
        }.getOrElse { false }
        val ok = flushVerdict(drained, lastWriteOk)
        if (!ok) Diagnostics.w(TAG, "台账 flush 未写成功（超时或写入失败）")
        return ok
    }

    /**
     * [flush] 的判据（纯函数，便于单测）：队列排空**且**这一轮没有写失败。
     *
     * 只判「队列空了」正是 N4 的根因 —— 密钥不可用 / 磁盘满时 `writeLines` 返回 false，
     * 队列照样是空的，界面于是报「已追加 N 条」而实际 0 条入库。
     */
    internal fun flushVerdict(queueEmpty: Boolean, writeOk: Boolean): Boolean = queueEmpty && writeOk

    /**
     * 关闭功能：先停记录、丢弃待写队列，再删文件。
     *
     * 顺序不能反 —— 先删文件再 flush，会把队列里刚写的几行重新建出来。
     */
    fun deleteFile(context: Context): Boolean {
        enabled = false
        val file = File(context.filesDir, FILE_NAME)
        // 关闭即丢弃未落盘的队列：用户的意图就是「这份记录不要了」。不丢的话，写不进去的行会留在
        // 队列里（N3 收口后失败不再丢批）⇒ flush 永远不成功 ⇒ 密钥库失效的机器上**删不掉台账**，
        // 而恰恰是那种机器最需要删干净
        queue.clear()
        if (!flush()) {
            // 写线程还没停（超时 / 代际未同步）就删，正在进行的 drain 会把文件重建出来（回执却是「已删除」）
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
        // 只取不删：写成功才把写过的那一段从队首移除。
        // 原写法「先整队 poll 出队再写」在失败时**整批消失**，只留一条 W —— 而失败的现实成因
        // （密钥库抖动 / `LedgerCrypto` 的 30s 负缓存 / 磁盘满）都是瞬态的，把瞬态故障升级成
        // 永久丢数据，且与同库的 UserFrequency（写失败保留 dirty 重试）口径相反。
        val batch = ArrayList<String>(queue.size)
        for (line in queue) batch.add(line)
        if (batch.isEmpty()) return
        if (!writeLines(file, batch)) {
            lastWriteOk = false
            Diagnostics.w(TAG, "台账写入失败（${batch.size} 行留在队列里，下次落盘重试）")
            return
        }
        // 只移除确实写过的那一段：写线程是唯一的 poll 方，顺序即入队顺序
        repeat(batch.size) { queue.poll() }
    }

    /**
     * 一次追加：迁移明文 → 截残行 → 逐行加密写盘。
     *
     * 三条都必须在这里做，缺一处就有一条数据损坏路径：
     *  - 迁移：旧版明文文件不先迁移，加密记录会与明文行混在同一个文件里（读侧按记录解，明文行全丢）；
     *  - 截残行：行与行之间没有帧标记，残行会与下一次追加的首行拼成一条完整行；
     *  - 加密：写盘内容里不得出现词面（`su 0 cat` 级的要求）。
     *
     * @return false = 没写（密钥不可用 / IO 失败），调用方按失败处理，**绝不回退明文**
     */
    internal fun writeLines(file: File, lines: List<String>): Boolean {
        val dir = file.parentFile ?: return false
        val key = keyForTest ?: LedgerCrypto.dataKey(dir) ?: return false
        return writeLinesEncrypted(file, lines, key)
    }

    /**
     * [writeLines] 的实现体（密钥由调用方给，JVM 单测可直接注入）。
     *
     * **迁移 / 截残行 / 追加三件事共用一个临界区**（[migrateLock]）：读侧 [readAll] 的迁移是
     * 「整份重写 + `Files.move`」，它一换掉目录项，落在旧 inode 上的追加就随原件一起消失。
     * 截残行与追加原先在锁外（2026-10-22 收口）—— 那条交错要靠「`needsMigration` 一转过假值就不再
     * 为真」这条**隐含不变式**才不成立，而读侧一旦多出第二条整份重写路径（修复 / 压缩 / 外部改写）
     * 不变式即破，丢的是一整批选择。进锁后不再有这个前提，代价是读侧的迁移 / 整份读最多多等一次追加
     * （单行写是几十微秒；只有批量导入会明显变长，而那条路径本来就不在按键热路径上）。
     */
    internal fun writeLinesEncrypted(file: File, lines: List<String>, key: SecretKey): Boolean =
        synchronized(migrateLock) {
            runCatching {
                file.parentFile?.mkdirs()
                // 迁移失败（磁盘满 / 无权限）时**必须放弃这次写入**：继续追加会把加密记录写进仍是明文
                // 格式的文件里，读侧按一个口径解析，另一批就整片丢（N3 同源）
                if (migrateIfNeeded(file, key) == MigrateOutcome.FAILED) {
                    Diagnostics.w(TAG, "台账格式未就绪（迁移失败），本次不写入")
                    return@synchronized false
                }
                // 残行口径不变：记录同样是「一行一条、以 \n 结尾」，缺 \n 的就是上次写中断的半条
                truncateTrailingPartialLine(file)
                appendEncrypted(file, lines, key)
            }.onFailure { e ->
                Diagnostics.w(TAG, "台账写入失败: ${e.javaClass.simpleName}")
            }.getOrDefault(false)
        }

    /**
     * 追加一批加密记录（调用方须持 [migrateLock]，且已完成迁移与截残行）。
     *
     * 失败时把文件长度**退回追加前**：失败可能在写了一半之后才发生（磁盘满），退回后重试同一个
     * 批次才不会把已落地的行再写一遍 —— 台账的「次数」就是用户的选择次数，多记一次没有修正入口。
     */
    private fun appendEncrypted(file: File, lines: List<String>, key: SecretKey): Boolean {
        val before = file.length()
        return try {
            val sb = StringBuilder(lines.size * 64 + 16)
            for (line in lines) sb.append(LedgerCrypto.encryptLine(line, key)).append('\n')
            FileOutputStream(file, true).use { out ->
                out.write(sb.toString().toByteArray(Charsets.US_ASCII))
                out.flush()
            }
            true
        } catch (t: Throwable) {
            rollbackTo(file, before)
            Diagnostics.w(TAG, "台账追加失败（已退回 $before 字节，可安全重试）: ${t.javaClass.simpleName}")
            false
        }
    }

    /** 把文件截回追加前的长度（[appendEncrypted] 的失败路径；失败只留痕，不再抛） */
    private fun rollbackTo(file: File, length: Long) {
        runCatching { RandomAccessFile(file, "rw").use { it.setLength(length) } }
            .onFailure { Diagnostics.w(TAG, "台账回滚失败: ${it.javaClass.simpleName}") }
    }

    /** [migrateIfNeeded] 的结果：写侧据此决定「能不能接着追加」 */
    internal enum class MigrateOutcome {
        /** 已经是加密格式（只读了 12 字节文件头） */
        UP_TO_DATE,

        /** 本次确实改写了文件（明文迁移 / 补首行标识） */
        REWRITTEN,

        /** 该迁移却没能写成（磁盘满 / 无权限）：写侧必须放弃本次追加 */
        FAILED,
    }

    /**
     * 旧版明文台账就地迁移成加密台账（数据一条不丢，次序不变）；缺首行标识的密文台账只补首行。
     *
     * 幂等：已是加密格式时**只读文件头**（O(1)，不进热路径的整份读）。调用方须持有 [migrateLock]。
     */
    internal fun migrateIfNeeded(file: File, key: SecretKey): MigrateOutcome {
        if (!needsMigration(file)) return MigrateOutcome.UP_TO_DATE
        val text = if (file.isFile && file.length() > 0L) file.readText(Charsets.UTF_8) else ""
        // ① 首行标识不见了（外部改写 / 部分恢复 / 手工替换），但内容是逐行密文 ⇒ **只重写首行**。
        //    这条分支不修的话：下面的明文 parse 会把 "1:…" 当成「没有读音的词」，
        //    再用「加密后的乱码」原子覆盖原件 —— 页面与导出件全变 base64 乱码（N3）。
        if (LedgerCrypto.looksEncrypted(text) || LedgerCrypto.looksLikeRecords(text)) {
            return if (reheader(file, text)) MigrateOutcome.REWRITTEN else MigrateOutcome.FAILED
        }
        // ② 首行在、只是不在偏移 0（前导空行 / BOM）：`looksEncrypted` 认「首个非空行」，
        //    所以这里也归到补首行；不处理它会让 `needsMigration` 恒 true ⇒ 每次按键都整份读一次
        val pairs = parse(text)
        val sb = StringBuilder(pairs.size * 64 + 32)
        sb.append(LedgerCrypto.HEADER).append('\n')
        for ((w, p) in pairs) {
            val line = formatLine(w, p) ?: continue
            sb.append(LedgerCrypto.encryptLine(line, key)).append('\n')
        }
        return writeAtomicWithSync(file, sb.toString(), ".mig")
            .also { ok ->
                if (ok) Diagnostics.i(TAG, "台账已迁移为加密格式: ${pairs.size} 行")
                else Diagnostics.w(TAG, "台账迁移失败（保留原文，下次重试）")
            }
            .let { if (it) MigrateOutcome.REWRITTEN else MigrateOutcome.FAILED }
    }

    /**
     * 只重写首行标识：保留全部密文记录行，丢掉前置垃圾（BOM / 空行 / 半截注释）。
     *
     * @return 是否写成。没有任何记录行且原文也没有标识时返回 false（交给明文迁移判定）。
     */
    private fun reheader(file: File, text: String): Boolean {
        val kept = StringBuilder(text.length)
        for (line in text.lineSequence()) {
            if (LedgerCrypto.isRecordLine(line)) kept.append(line.trim()).append('\n')
        }
        if (kept.isEmpty() && !LedgerCrypto.looksEncrypted(text)) return false
        val body = LedgerCrypto.HEADER + "\n" + kept
        val ok = writeAtomicWithSync(file, body, ".reheader")
        if (ok) Diagnostics.i(TAG, "台账首行标识缺失，已补回（保留 ${kept.count { it == '\n' }} 条记录）")
        return ok
    }

    /**
     * 「先 fsync 再原子改名」写整份文件（迁移 / 补首行共用）。
     *
     * 少这一步就是掉电窗口：`rename` 只保证目录项落盘，数据块可能还在页缓存 —— 掉电后文件在、
     * 内容是空/半截，而明文原件已被 unlink ⇒ **全部台账归零且不可回滚**（N6）。
     */
    private fun writeAtomicWithSync(file: File, body: String, suffix: String): Boolean {
        val tmp = File(file.parentFile, file.name + suffix)
        // 上一次中断留下的中间件先删：它的内容只会是一份过期的中间态
        if (tmp.exists() && !tmp.delete()) {
            Diagnostics.w(TAG, "台账中间件无法删除: ${tmp.name}")
            return false
        }
        return runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(body.toByteArray(Charsets.US_ASCII))
                out.flush()
                out.fd.sync()
            }
            // 原子替换目录项：中途被杀时旧文件仍在，下次启动重试（幂等）。
            // 用 `Files.move(REPLACE_EXISTING)` 而不是 `File.renameTo` —— 后者在目标已存在时
            // 于 Windows 上会**失败**（单测跑在 Windows，Android 上是 rename(2) 覆盖），
            // 一旦失败就是「明文台账里混进加密记录」，读侧会把明文行整片丢掉。
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.onFailure {
            tmp.delete()
            Diagnostics.w(TAG, "台账写入中间件失败（$suffix）: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /**
     * 是否需要迁移：只读文件头 [LedgerCrypto.HEADER].length 个字节，**不整份读**。
     *
     * 为什么必须 O(1)：每次落盘都会先问一次「要不要迁移」，整份读会把一次按键的选择放大成
     * 「读几 MB」——那正是本功能要求「不影响性能」的反面。
     */
    internal fun needsMigration(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return true
        val head = ByteArray(LedgerCrypto.HEADER.length)
        val read = runCatching {
            file.inputStream().use { input ->
                var got = 0
                // 单次 read 不保证读满（流实现允许短读），循环到够长或 EOF
                while (got < head.size) {
                    val n = input.read(head, got, head.size - got)
                    if (n <= 0) break
                    got += n
                }
                got
            }
        }.getOrDefault(0)
        if (read < head.size) return true
        return String(head, Charsets.US_ASCII) != LedgerCrypto.HEADER
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

    /**
     * [readAll] 的结果：**必须**把「确实没有记录」与「解不开 / 读不出」分开。
     *
     * 压成一个空表就是 L-1411：密钥库不可用时页面显示「共 0 次选择 · 0 个词条」，用户以为
     * 台账空了，顺手点「关闭用户词库并删除记录」把真数据删掉 —— 「解不开」被显示成「没有」，
     * 而本类的 KDoc 明确禁止这样统计。
     */
    sealed class LedgerRead {
        /** 读成功（含「文件不存在」= 确实没有记录） */
        class Ok(val picks: List<Pair<String, String>>) : LedgerRead()

        /** 读不出：数据密钥不可用 / 读取或迁移抛出。盘上可能有数据，界面不得按 0 条统计 */
        object Unavailable : LedgerRead()
    }

    /**
     * 读整份流水（文件不存在返回空表）；解析口径与 [parse] 一致。
     *
     * 密钥不可用时返回 [LedgerRead.Unavailable] 并留痕：**不能**把「解不开」当成「没有数据」
     * 写进统计，但也绝不回退明文读（明文只是旧版遗留的一种形态，迁移那一刻起就不该再被读）。
     */
    fun readAll(context: Context): LedgerRead = readAllIn(context.filesDir)

    /** [readAll] 的纯文件版本（JVM 可测：「密钥不可用不得当成 0 条」的真跑路径） */
    internal fun readAllIn(dir: File): LedgerRead {
        val f = File(dir, FILE_NAME)
        if (!f.isFile) return LedgerRead.Ok(emptyList())
        val key = LedgerCrypto.dataKey(dir)
        if (key == null) {
            Diagnostics.w(TAG, "台账读取跳过：数据密钥不可用")
            return LedgerRead.Unavailable
        }
        return runCatching {
            // 只读入口也要迁移：用户可能升级后再没选过词，此时没有任何写入会触发迁移
            val text = synchronized(migrateLock) {
                if (migrateIfNeeded(f, key) == MigrateOutcome.FAILED) {
                    Diagnostics.w(TAG, "台账格式迁移失败，按现有内容尽力读取")
                }
                f.readText(Charsets.UTF_8)
            }
            // 判据是「首行是标识」**或**「内容形态就是逐行密文」：后者兜住首行标识被删的情况，
            // 绝不给明文 parse 出口 —— 那把 "1:…" 读成 base64 乱码词，用户视角就是台账全毁（N3）
            val picks = if (LedgerCrypto.looksEncrypted(text) || LedgerCrypto.looksLikeRecords(text)) {
                parse(decodeRecords(text, key))
            } else {
                // 迁移失败（磁盘满 / 无权限）时仍按旧格式读，数据至少可见
                Diagnostics.w(TAG, "台账仍是明文格式（迁移未完成），按明文读取")
                parse(text)
            }
            LedgerRead.Ok(picks)
        }.onFailure {
            Diagnostics.w(TAG, "台账读取失败: ${it.javaClass.simpleName}")
        }.getOrElse { LedgerRead.Unavailable }
    }

    /**
     * 逐行解密整份台账，拼回 [parse] 认的明文流水。
     *
     * 单行解不开（残行 / 被改 / 换过密钥）只丢那一行：一条脏记录不能把整份台账清空。
     */
    internal fun decodeRecords(text: String, key: SecretKey): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        var broken = 0
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith(LedgerCrypto.HEADER)) continue
            val plain = LedgerCrypto.decryptLine(line, key)
            if (plain == null) {
                broken++
                continue
            }
            sb.append(plain).append('\n')
        }
        if (broken > 0) Diagnostics.w(TAG, "台账跳过 $broken 行无法解密的记录（残行或密钥不符）")
        return sb.toString()
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
