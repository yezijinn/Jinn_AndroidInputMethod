package com.jinn.inputmethod

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/**
 * 用户词频学习：记下用户实际选过的候选，下次把它们排到前面。
 *
 * 规则（对齐 librime `UserDictionary`，见 `docs/librime-master/src/rime/dict/user_dictionary.cc`）：
 *  - 只在明确选择时记一次：点候选栏、空格取首候选、点补全项；
 *    收起键盘的自动提交、语音结果、剪贴板粘贴都不算；
 *  - 权重按 `algo::formula_d` 累加：`dee = commits + dee_old * exp((tick_old - tick_now) / 200)`，
 *    tick 是天计数（`now / 86400000`），也就是旧值按天衰减、半衰期约 139 天，选中一次加 1；
 *  - [rank] 是稳定排序，权重相同就保持词库原顺序，没学过的候选完全不受影响。
 *
 * 存储：`filesDir/user_freq.txt`（明文形态是 `词<TAB>权重<TAB>天`），落盘走 BackgroundIo + 防抖 2s，
 * 窗口内会把「最后一次」排成尾沿任务补写（只做前沿丢弃的话，进程被 LMK 直杀时
 * 最近几次学习会丢，而 `flush` 只在服务收尾时才跑）；原子落盘（临时文件 + 改名）；
 * 退出时 [flush] 同步补一次。上限 [MAX_ENTRIES] 条，权重低于 [MIN_WEIGHT] 丢弃，文件稳定在几十 KB。
 *
 * **落盘是整份加密的**（[LedgerCrypto] 的行级 AEAD，密钥与台账共用）：
 * `#jinn-freq1` + 一行 `1:base64(iv||密文)`，密文里才是上面那份明文。为什么这里能整份加密、
 * 台账不能：这里上限 3000 行、写盘本身就有 2 秒防抖，一次「解密 → 改 → 加密 → 原子替换」
 * 只有 0.2ms 量级；台账只增不删、可能几万行，整表重写会把一次按键放大成几 MB 的读写。
 * 旧版明文首行 `# jinn user_freq v1` 在首次读取时一次性迁移成密文。
 *
 * **包内条目仍是明文**：配置备份导出走 [readTextForBackup]，导入走 [writeFromBackup] ——
 * 备份包里放的是解密后的明文（解压即得明文），加密只发生在**本机落盘**这一层。
 *
 * 密钥不可用时 **fail-closed**：不写明文、不把「解不开」当成「没有词频」；已学内容留在
 * 内存里等下次落盘（[dirty] 不清），进程退出时 [flush] 如实返回 false。
 *
 * **读失败 = 盘上内容未知**（[loadBlocked]）：这时内存里的表只是一份空壳，任何落盘都先补读一次
 * （[clearBlockedIfReadable]）—— 否则「开机预加载时设备还没解锁」这一次读失败，会在解锁后
 * 第一个词落盘时把用户几年的词频整份覆盖掉（L-1409，真机 27 条 → 1 条）。
 */
internal object UserFrequency {

    private const val TAG = "UserFrequency"
    internal const val FILE_NAME = "user_freq.txt"

    /** 旧版明文表头（也是加密后明文正文的首行；备份包里的形态没变） */
    private const val HEADER = "# jinn user_freq v1"

    /** 加密文件的首行标识（迁移判据；与台账的 `#jinn-picks1` 各自成一份格式） */
    internal const val ENC_HEADER = "#jinn-freq1"
    private const val MAX_ENTRIES = 3000
    private const val MIN_WEIGHT = 0.15
    private const val SAVE_DEBOUNCE_MS = 2000L

    /** 词 → (权重, 最后更新日) */
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    @Volatile
    private var enabled = true

    @Volatile
    private var dirty = false

    /**
     * 「盘上内容未知」（L-1409）：[loadFromFile] / [readPlainOrMigrate] 读失败时置位。
     *
     * 读失败的现实成因是**设备还没解锁**（开机预加载早于用户解锁，`setUnlockedDeviceRequired`
     * 的密钥此时解不开）—— 这是瞬态的，几秒后同一个密钥就可用了。而此前的写法只打一条 W 就
     * `return`，内存表留在初始的空态；解锁后第一个词落盘，[render] 把「1 条」整份写回文件，
     * 用户的历史词频**当场清零且不可恢复**（真机复现：27 条 → 1 条）。
     *
     * 置位期间的规则：`remember` 照常更新内存（本次使用仍能排序），但任何落盘都先补读
     * （[clearBlockedIfReadable]）；补读仍失败就**不写**，让 [dirty] 留着等下一次机会。
     */
    @Volatile
    private var loadBlocked = false

    /**
     * 测试注入的密钥（JVM 上走不了 Android Keystore）。
     *
     * 生产路径一律 [LedgerCrypto.dataKey]（与台账同一把 DEK），注入只为让纯 JVM 单测覆盖
     * 「整份加解密 + 迁移」的格式与口径。
     */
    @Volatile
    private var keyForTest: SecretKey? = null

    @Volatile
    private var lastSaveAt = 0L

    /** 尾沿补写调度器：守护线程 + 纯 JVM 实现，不依赖 Android Looper（JVM 单测里也能跑） */
    private val saveScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jinn-userfreq-save").apply { isDaemon = true }
    }

    /** 是否已排入一个尾沿补写：窗口内多次 remember 只排一次 */
    @Volatile
    private var savePending = false

    /** 文件写入互斥：尾沿补写（后台线程）与 [flush]（主线程）可能同时到达 */
    private val saveLock = Any()

    /**
     * 存储文件（加载线程在 [load] 里赋值，主线程在 [remember]/[flush] 里读）。
     * 必须 @Volatile：否则主线程可能一直看到 null，首批学习不落盘、退出时 flush 也直接返回，
     * 结果就是白记，还不报错。
     */
    @Volatile
    private var file: File? = null

    private class Entry(var weight: Double, var day: Int)

    /** 当天 tick（天计数），与 librime 的 tick 语义一致 */
    private fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

    // ── 生命周期 ────────────────────────────────────────────────────────────

    /**
     * 启动加载（必须在后台线程、且显式降优先级）：读文件 + 按天衰减。
     * @param enabled 用户设置里的开关；关掉时不加载也不学习
     */
    fun load(context: Context, enabled: Boolean) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        loadFromFile(File(context.filesDir, FILE_NAME), enabled)
    }

    /**
     * [load] 的文件部分（拆出来是为了让纯 JVM 单测能真跑一遍「读失败」路径 —— `load` 要 Context）。
     *
     * 读失败（密钥不可用 / 密文损坏）时置 [loadBlocked]：**盘上的内容未知**。⚠ 这里只置标志、
     * **绝不清 [entries] 冒充「没有词频」**，也绝不在随后落盘时把空表写回文件。
     */
    internal fun loadFromFile(f: File, enabled: Boolean) {
        this.enabled = enabled
        // 存储文件绑定放在这里（而不是只在 [load] 里）：这两个入口任何一个被直接调用，
        // `remember` / `flush` 都必须落到同一个文件上，否则「学了但写不出去」且不报错
        file = f
        // 每次都是完整的一次读取尝试：先按「未受阻」开始，失败会在下面重新置位
        loadBlocked = false
        if (!enabled) {
            // 关掉学习时也要把内存态清干净：否则运行期的 flush() / 防抖落盘会把上一份内存快照
            // 写回文件，把「刚导入的词频」覆盖掉（导入还原 userLearning=false 就是这条路径）。
            entries.clear()
            dirty = false
            Diagnostics.i(TAG, "用户词频学习已关闭（设置项 userLearning=false）")
            return
        }
        if (!f.isFile) {
            Diagnostics.i(TAG, "用户词频: 暂无历史（首次使用）")
            return
        }
        runCatching {
            val now = today()
            val text = readPlainOrMigrate(f)
            if (text == null) {
                // 密钥不可用 / 密文损坏：不覆盖文件、不当成「没有词频」，本次按未学习处理。
                // 必须置 loadBlocked —— 否则解锁后第一个词落盘就用「1 条」覆盖整份历史（L-1409）
                loadBlocked = true
                Diagnostics.w(TAG, "用户词频读取跳过（密钥不可用或密文损坏），不覆盖原文件")
                return
            }
            val parsed = parse(text, now, PinyinEngine::toSimplified)
            entries.clear()
            for ((word, v) in parsed) entries[word] = Entry(v.first, v.second)
            Diagnostics.i(TAG, "用户词频: 已载入 ${parsed.size} 条（含按天衰减）")
        }.onFailure {
            // 读取过程抛出（IO 错误 / 解析异常）同样意味着「盘上内容未知」
            loadBlocked = true
            Diagnostics.w(TAG, "用户词频读取失败（忽略，按未学习处理）: ${it.message}")
        }
    }

    /**
     * 实时切换开关（设置页用）：只改标志位，不读盘。
     * 关闭后不再学习、也不参与排序，既有记录留在文件里。
     *
     * 打开时若内存里还是空的（启动时学习为关，[load] 提前返回、历史没载入），
     * 会异步补载一次；否则用户开了开关也要等进程重启才生效。
     */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        if (!value) {
            // 关之前先把「还没落盘的那一次学习」写下去（BUG.md L-1286，与 L-1274 的对偶）：
            // 直接清内存会让它既进不了文件、重开也无从补写（内存表已空）。先 flush 再清，
            // 「关闭后既有记录留在文件里」这句话才包含刚刚学的那一次。
            // 注意顺序：必须在 enabled 置 false **之前**调，否则会被落盘入口的开关判据拦掉。
            //
            // 失败要留痕（BUG.md L-1414）：这里的 false 意味着「关闭前那次学习没写成」，
            // 而紧接着的 entries.clear() 会让它再无补写机会 —— 静默就等于丢数据，不能只吞返回值
            if (!flush()) Diagnostics.w(TAG, "关闭学习前落盘失败：最后一次学习未写入文件")
        }
        enabled = value
        Diagnostics.i(TAG, "用户词频学习: ${if (value) "开启" else "关闭"}")
        if (value && entries.isEmpty()) {
            val f = file ?: return
            BackgroundIo.run { reload(f) }
        } else if (!value) {
            // 关闭即清内存：否则关闭期间最后一次 flush 仍会把旧学习写回文件、
            // 重开后被 reload 读回，等于「关掉了又偷偷学」。
            synchronized(saveLock) {
                entries.clear()
                dirty = false
            }
        }
    }

    /** 从文件补载历史（开关从关切到开时用；[entries] 已有内容则不动） */
    private fun reload(f: File) {
        if (!f.isFile) return
        runCatching {
            // 读盘**前**记下版本号：读与写之间可能正好跨过一次备份导入替换（见 [applyReload]）
            val versionBeforeRead = version
            val text = readPlainOrMigrate(f) ?: return
            val parsed = parse(text, today(), PinyinEngine::toSimplified)
            if (parsed.isEmpty()) {
                // 读成功、只是表空 ⇒ 盘上内容已确知（原来的 `return` 连「解除阻塞」都跳过，
                // L-1409 那条路于是永远写不回去）
                loadBlocked = false
                return
            }
            if (applyReload(f, versionBeforeRead, parsed)) {
                // 读成功且内容已并入 ⇒ 盘上内容已知，解除 [loadBlocked]
                loadBlocked = false
            } else {
                Diagnostics.i(TAG, "用户词频: 补载结果已作废（期间词频被替换），丢弃 ${parsed.size} 条")
            }
        }.onFailure {
            Diagnostics.w(TAG, "用户词频补载失败（按未学习处理）: ${it.message}")
        }
    }

    /**
     * 补载的「入内存」一步（拆出来是为了能单测「读到旧文本、应用时已换代」的时序，BUG.md L-1281）。
     *
     * 必须进写锁、且核对两件事，否则会把旧记录复活：
     *  - `file` 还是本次读的那个文件；
     *  - 内存态没换过代（[version] 未变）——
     *    备份导入的「清空 → 重填」在锁内完成，而补载的读盘在锁外，读到的可能是**替换之前**
     *    的旧文件；照旧 `putIfAbsent` 就会把已删掉的词填回内存表，用户看到的是新旧两份合一
     *    （下次落盘把混合结果写死）。换过代就整份作废，等下一次补载或重启。
     *
     * @return 是否真的并入
     */
    internal fun applyReload(f: File, versionBeforeRead: Long, parsed: Map<String, Pair<Double, Int>>): Boolean {
        synchronized(saveLock) {
            if (file !== f || version != versionBeforeRead) return false
            for ((word, v) in parsed) entries.putIfAbsent(word, Entry(v.first, v.second))
            Diagnostics.i(TAG, "用户词频: 开关打开后补载 ${parsed.size} 条")
            return true
        }
    }

    /** 测试用：当前内存态版本号（[applyReload] 的换代校验依据） */
    internal fun versionForTest(): Long = version

    /** 测试用：当前是否处于「盘上内容未知」状态（L-1409） */
    internal fun isLoadBlockedForTest(): Boolean = loadBlocked

    /** 测试/复位用：清空内存态（不删文件；注入的密钥由 `setKeyForTest(null)` 单独复位） */
    internal fun resetForTest() {
        entries.clear()
        dirty = false
        enabled = true
        loadBlocked = false
        file = null
        lastSaveAt = 0L
        savePending = false
    }

    /** 测试用：注入落盘密钥（JVM 上没有 Android Keystore；生产一律走 [LedgerCrypto.dataKey]） */
    internal fun setKeyForTest(key: SecretKey?) {
        keyForTest = key
    }

    /** 测试用：直接注入（绕过文件） */
    internal fun putForTest(word: String, weight: Double, day: Int) {
        entries[word] = Entry(weight, day)
    }

    internal fun weightForTest(word: String): Double? = entries[word]?.weight

    /** 测试用：只切换开关（不走文件） */
    internal fun loadForTest(enabled: Boolean) {
        this.enabled = enabled
    }

    /** 测试用：指定存储文件（正常路径由 [load] 赋值；用于「写盘失败」路径） */
    internal fun setFileForTest(f: File?) {
        file = f
    }

    /** 测试用：dirty 状态（验证写失败后保留、写成功后清除） */
    /**
     * 内存态版本号：每次 [remember] 自增。落盘成功后只有「版本没变」时才清 [dirty] ——
     * 渲染与清标记之间若有并发学习写进内存，无条件清 dirty 会把它标成「已落盘」，
     * 此后 `flush` 直接因 `!dirty` 返回，那次学习就静默丢了。
     */
    @Volatile
    private var version = 0L

    internal fun isDirtyForTest(): Boolean = dirty

    internal fun sizeForTest(): Int = entries.size

    // ── 学习 ────────────────────────────────────────────────────────────────

    /**
     * 记一次「用户选择了 [word]」。主线程调用，只做一次哈希更新 + 防抖落盘，绝不阻塞输入。
     */
    fun remember(word: String) {
        if (!enabled || word.isEmpty()) return
        // 行格式是 `词<TAB>权重<TAB>天`，含制表符/换行的词无法表示：直接不学（否则会写坏一行，
        // 下次 parse 会整行跳过，静默丢失且难以察觉）。正常候选来自词库，不会命中这条。
        if (word.indexOf('\t') >= 0 || word.indexOf('\n') >= 0 || word.indexOf('\r') >= 0) {
            return
        }
        val now = today()
        // 内存态更新放进写盘锁：导入的 writeFromBackup 会在同一把锁内做「clear + 重填 + 清脏」，
        // 不加锁就可能出现「刚学到的词被 clear 抹掉」或「内存里有、脏标记被清掉而永不落盘」
        synchronized(saveLock) {
            entries.compute(word) { _, old ->
                // librime formula_d：dee_new = commits + dee_old * exp((tick_old - tick_now) / 200)
                // 与 parse 一致地对「未来 day」钳位：用户系统时钟回拨时 old.day > now，
                // 不钳位的话 exp(正数) 会把权重放大到天文数字（回拨 2000 天 = 22 万倍），此后永久霸榜。
                val decayed = (old?.weight ?: 0.0) * Math.exp(dayDiff(old?.day ?: now, now) / 200.0)
                Entry(decayed + 1.0, now)
            }
            trimIfNeeded()
            dirty = true
            version++
        }
        scheduleSave()
    }

    /** 超出上限时淘汰权重最低的条目（保持文件与内存都有界） */
    private fun trimIfNeeded() {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries
            .sortedBy { it.value.weight }
            .take(entries.size - MAX_ENTRIES)
            .forEach { entries.remove(it.key) }
    }

    /**
     * 防抖落盘：距上次写盘不足窗口时不丢，改为排一个尾沿补写，
     * 窗口到点后写一次（连续选择只在最后写一次，且进程被直杀时也只丢窗口内这一小段）。
     */
    private fun scheduleSave() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        val wait = saveDelayMs(now, lastSaveAt, SAVE_DEBOUNCE_MS)
        if (wait <= 0L) {
            lastSaveAt = now
            saveNow(f)
            return
        }
        if (savePending) return
        savePending = true
        runCatching {
            saveScheduler.schedule({ savePending = false; saveNow(f) }, wait, TimeUnit.MILLISECONDS)
        }.onFailure {
            savePending = false
            Diagnostics.w(TAG, "用户词频尾沿落盘排程失败: ${it.message}")
        }
    }

    /**
     * 距下次允许落盘还需等多少毫秒（纯函数，便于单测）；0 表示当前即可写。
     *
     * 上限钳到 [window]：系统时钟回拨会让 `lastSaveAt` 落在「未来」，差值可达小时级 ，
     * 不钳的话等于这段时间内完全不再落盘。
     */
    internal fun saveDelayMs(now: Long, lastSaveAt: Long, window: Long): Long =
        (lastSaveAt + window - now).coerceIn(0L, window)

    /**
     * 真正落盘：走 BackgroundIo。
     *
     * 渲染与写盘放在同一把锁内：若先渲染再抢锁，会出现
     * 「尾沿任务渲染旧快照 → `flush` 渲染新快照并写入 → 尾沿任务后拿到锁再写旧快照」
     * 的交替，把较新的内容覆盖成旧的。锁内渲染则保证最后落盘的一定是最新快照。
     */
    private fun saveNow(f: File) {
        // 每次真正落盘都刷新时间戳：尾沿补写此前不刷新，紧接着的下一次学习会立刻再同步写一次
        lastSaveAt = System.currentTimeMillis()
        BackgroundIo.run {
            synchronized(saveLock) {
                // 陈旧任务校验：任务里捕获的是排程当时的 File。若此后目标文件已变
                // （测试的 setFileForTest、未来可能的重新 load），旧任务既不该写到旧路径，
                // 更不该把新文件的 `dirty` 清掉（那会让新内容白白跳过一轮落盘）。
                if (file !== f) return@synchronized
                // 关闭学习后一律不写（BUG.md L-1274）：选词后的防抖尾沿任务可能在「关开关」之后才到，
                // 而关闭时内存表已被清空 ⇒ 它会把**空表**写成文件（只剩表头），历史学习成果全丢，
                // 进程重启也回不来 —— 与「关闭后既有记录留在文件里」的口径直接冲突
                if (!enabled) return@synchronized
                // 读失败过（盘上内容未知）就不许拿内存里的空壳覆盖整份历史（L-1409）
                if (!clearBlockedIfReadable(f)) return@synchronized
                // 清 dirty 前核对版本（见 [version]）：渲染与清标记之间可能有并发学习进内存
                val v = version
                val key = dataKey(f) ?: run {
                    Diagnostics.w(TAG, "词频落盘跳过：密钥不可用（不写明文）")
                    return@synchronized
                }
                if (writeEncryptedAtomically(f, render(), key) && version == v) dirty = false
            }
        }
    }

    /**
     * [loadBlocked] 期间的「补读 + 并入」：任何落盘之前先试一次，读得出来才允许写（L-1409）。
     *
     * 为什么是补读而不是干脆不写：不写的代价是本次进程学到的词重启即丢（内存态没人收）；
     * 而「拿空表覆盖」丢的是用户**全部历史**。补读成功就把盘上的词按「权重取大者」并进内存
     * （内存里那几条是解不开期间学的，盘上是旧的完整表），然后照常整份落盘。
     *
     * 调用方须持 [saveLock]。
     *
     * @return true = 盘上内容已知（或文件已不存在），可以落盘；false = 仍读不出，本次不许写
     */
    private fun clearBlockedIfReadable(f: File): Boolean {
        if (!loadBlocked) return true
        // 文件已经不在了（读取时判定密文损坏并改名为 .bad / 被外部删掉）：没有可覆盖的内容，
        // 把内存态写出去严格优于什么都不写
        if (!f.isFile) {
            loadBlocked = false
            return true
        }
        val text = readPlainOrMigrate(f)
        if (text == null) {
            Diagnostics.w(TAG, "词频仍读不出，本次不落盘（避免用空表覆盖原文件）")
            return false
        }
        val parsed = parse(text, today(), PinyinEngine::toSimplified)
        mergeFromDisk(parsed)
        loadBlocked = false
        Diagnostics.i(TAG, "词频补读成功：并入 ${parsed.size} 条后落盘")
        return true
    }

    /**
     * 盘上的词并进内存：同词取**权重较大者**（与 [mergeForBackup] 同口径）。
     *
     * 不能直接 `putIfAbsent`：解不开期间学会的词在内存里只有一个 1.0，而盘上是它攒了几十次的
     * 旧权重 —— 让内存值胜出等于把那个词的积累悄悄改小。
     */
    private fun mergeFromDisk(parsed: Map<String, Pair<Double, Int>>) {
        for ((word, v) in parsed) {
            entries.compute(word) { _, old ->
                if (old == null || v.first > old.weight) Entry(v.first, v.second) else old
            }
        }
        trimIfNeeded()
    }

    /**
     * 退出/切后台时调用（主线程），保证最后一次学习不丢。
     *
     * @return 盘上内容是否已经是最新的：**没有待写内容**（未装载 / 已关闭 / 不脏）也算 true ——
     *   那几种情形下文件本来就是当前真值；只有「确实要写而写失败」才 false。
     *   调用方（配置导出 / 导入）据此如实说明，而不是把失败当成「已落盘」（BUG.md L-1319）
     */
    fun flush(): Boolean {
        val f = file ?: return true
        // 关闭学习后不写（BUG.md L-1274）：关闭时内存表已清空，此时写盘会把文件清成空表
        if (!enabled) return true
        if (!dirty) return true
        // 这里同步写：只在 onDestroy / 切后台这种一次性收尾时调用，丢给 BackgroundIo 的话，
        // 服务销毁后进程可能马上被杀、任务来不及跑，最后几次学习就白记了。
        // 文件最多 3000 行，主线程写一次 1~3ms，可以接受。渲染同样放在锁内（见 [saveNow]）。
        //
        // 只有写盘成功才清 dirty：若先清再写，写失败（磁盘满 / IO 错误）后这次学习
        // 再没有任何路径会重试（下一次 flush 直接因 `!dirty` 返回），等于静默丢失，
        // 恰是 flush 本要避免的事。判据与 [saveNow] 保持一致。
        return synchronized(saveLock) {
            val v = version
            // 读失败过（盘上内容未知）：先补读，读不出来就不写 —— 否则退出时的这次 flush
            // 正是 L-1409 的现场（用「1 条」覆盖整份历史），且它比防抖写更「权威」
            if (!clearBlockedIfReadable(f)) return@synchronized false
            // 密钥不可用时如实返回 false（不写明文）：调用方（导出 / 导入）据此说明，而不是
            // 把「没写成」当成「已落盘」
            val key = dataKey(f)
            if (key == null) {
                Diagnostics.w(TAG, "词频落盘失败：密钥不可用")
                return@synchronized false
            }
            val written = writeEncryptedAtomically(f, render(), key)
            if (written && version == v) dirty = false
            written
        }
    }

    // ── 排序 ────────────────────────────────────────────────────────────────

    /**
     * 按用户权重稳定重排候选：权重降序，未学过的按原顺序排在后面。
     *
     * 未学习时（[entries] 为空）直接返回原数组，零开销、零行为变化。
     */
    fun rank(words: Array<String>): Array<String> = rank(words) { it }

    /**
     * 带 key 映射的重载：「只使用繁体字」模式下候选是繁体、词频表按简体存储（2026-09-27），
     * 于是按 [keyOf] 转换后的简体形式查权重，返回的仍是候选原样（顺序变了、内容不变）。
     */
    fun rank(words: Array<String>, keyOf: (String) -> String): Array<String> {
        if (!enabled || entries.isEmpty() || words.size < 2) return words
        // 一趟算完权重（MEM-43）：原先 anyKnown 循环与排序各调一次 keyOf，同一批词被转换两遍 ——
        // 繁体模式下 keyOf 是 toSimplified，实打实的逐字查表（候选一批几十条，每次查询都要走）。
        // 顺带保留「一个都没学过 ⇒ 原样返回」的零开销短路：权重全为 0 时不必排序。
        val weights = DoubleArray(words.size)
        var anyKnown = false
        for (i in words.indices) {
            val w = entries[keyOf(words[i])]?.weight
            if (w != null) {
                weights[i] = w
                anyKnown = true
            }
        }
        if (!anyKnown) return words
        // 按下标做稳定排序（权重相同保持原顺序），再按序取出：与 sortedByDescending 的稳定性一致
        val order = words.indices.sortedByDescending { weights[it] }
        return Array(words.size) { words[order[it]] }
    }

    // ── 序列化（纯函数，便于单测）──────────────────────────────────────────

    /**
     * 把内存态渲染成文件文本（纯函数）。权重降序，便于人肉排查。
     */
    internal fun render(): String {
        val sb = StringBuilder(entries.size * 24 + 32)
        sb.append(HEADER).append('\n')
        entries.entries
            .sortedByDescending { it.value.weight }
            .forEach { (word, e) ->
                sb.append(word).append('\t')
                    .append(String.format(java.util.Locale.US, "%.3f", e.weight)).append('\t')
                    .append(e.day).append('\n')
            }
        return sb.toString()
    }

    /**
     * 天差值（≤0），供衰减指数使用。
     *
     * 必须用 Long 相减：`day` 来自外部文本（可被手工编辑，也会随导入进来），
     * `Int.MIN_VALUE` 附近的值减 `nowDay` 会在 Int 域里**回绕成大正数**，被 `coerceAtMost(0)`
     * 归零后权重完全不衰减 —— 这类脏行会永久霸占该拼音的首候选，并被 [render] 原样写回文件，
     * 每次启动都复现，无法自愈。
     */
    private fun dayDiff(day: Int, nowDay: Int): Long =
        (day.toLong() - nowDay.toLong()).coerceAtMost(0L)

    /**
     * 解析文件文本并按天衰减（纯函数，便于单测）。
     * 容错：跳过表头/空行/字段数不对/权重非法/当天权重低于 [MIN_WEIGHT] 的行。
     */
    internal fun parse(
        text: String,
        nowDay: Int,
        normalize: (String) -> String = { it },
    ): Map<String, Pair<Double, Int>> {
        val out = HashMap<String, Pair<Double, Int>>()
        for (raw in text.split('\n')) {
            val line = raw.trim('\r', ' ', '\t')
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val word = parts[0]
            // 键必须已是「规范化」形式（调用方传 `PinyinEngine::toSimplified`；BUG.md L-54）：
            // 修复 toSimplified（2026-09-27）之前，繁体模式下学过的词存的是「头髮」这类折不回简体的键
            // ⇒ 升级后它们不再被任何候选匹配（无害但占空间），顺手丢弃即为清洗路径。
            // 默认参数是恒等函数：纯函数单测与「没有归一化器」的调用方行为不变。
            if (normalize(word) != word) continue
            val weight = parts[1].toDoubleOrNull() ?: continue
            val day = parts[2].toIntOrNull() ?: continue
            // 非有限值必须挡掉：`"NaN".toDoubleOrNull()` 与 `"Infinity".toDoubleOrNull()` 都不是
            // null（Kotlin 的筛选用正则显式放行了这两个字面量），而 NaN 与任何数比较恒为 false，
            // 下面的 `weight <= 0.0` 与 `decayed < MIN_WEIGHT` 两条判据会同时失效。脏值进到
            // [rank] 后更麻烦：`Double.compare` 把 NaN 当最大值，这条权重坏掉的词会永久霸占该
            // 拼音的首候选（空格取首候选就等于一直上屏它），并被 [render] 原样写回文件、无法自愈。
            if (word.isEmpty() || !weight.isFinite() || weight <= 0.0) continue
            val decayed = weight * Math.exp(dayDiff(day, nowDay) / 200.0)
            if (decayed < MIN_WEIGHT) continue
            val prev = out[word]
            if (prev == null || decayed > prev.first) {
                // `day` 必须**跟到解析日**（BUG.md L-111）：这里的权重是「折算到今天之后的值」，
                // 若把原 day 一起留下，文件里就成了「已折算的权重 + 原 day」——下次加载会按
                // `原 day → 那时的今天` 再折算一遍，同一天的衰减被反复计息（实测 5 天多衰减 8%，
                // 30 天残余只剩 0.10：学习成果静默流失，且落盘后不可逆）。
                // 不变量：**权重 = 该 day 上的值**，两者必须一起推进（`remember` 也是这样做的）。
                out[word] = Pair(decayed, nowDay)
            }
        }
        return out
    }

    /**
     * 备份导入合并（纯函数，便于单测）：把备份的词频文本并入本机文本。
     *
     * 语义：
     *  - 两边都先按 [parse] 衰减到 `nowDay` 再合并，与运行期加载一致（否则导入的是
     *    「旧机器上的绝对权重」，换机后衰减基准不同，会出现导入即失真）；
     *  - 同一词取「权重较大者、天较新者」而不是相加：两台设备都学过的词不该因导入翻倍；
     *  - 结果按权重降序、截断 [MAX_ENTRIES]，并过滤低于 [MIN_WEIGHT] 的词
     *    （否则导入的弱词会在下次加载时被静默丢掉，等于「导入了却没生效」）。
     *
     * @param localText 本机当前文件文本（「仅并入」模式传原文；「覆盖还原」模式传空串）
     */
    internal class MergeResult(
        val text: String,
        val localCount: Int,
        val incomingCount: Int,
        val mergedCount: Int,
    )

    /**
     * 导入时合并本地与入站的用户词频：同词取权重、学习日各自较大者；结果按权重降序保留最多 [MAX_ENTRIES] 条。
     *
     * [nowDay] 仅用于解析两端的日期字段（学习日），不参与取舍。
     */
    internal fun mergeForBackup(localText: String, incomingText: String, nowDay: Int): MergeResult {
        val local = parse(localText, nowDay, PinyinEngine::toSimplified)
        val incoming = parse(incomingText, nowDay, PinyinEngine::toSimplified)
        val merged = HashMap<String, Pair<Double, Int>>(local)
        for ((word, v) in incoming) {
            val prev = merged[word]
            merged[word] = if (prev == null) v
            else Pair(maxOf(prev.first, v.first), maxOf(prev.second, v.second))
        }
        val kept = merged.entries.sortedByDescending { it.value.first }.take(MAX_ENTRIES)
        val sb = StringBuilder(kept.size * 24 + 32)
        sb.append(HEADER).append('\n')
        for (entry in kept) {
            sb.append(entry.key).append('\t')
                .append(String.format(java.util.Locale.US, "%.3f", entry.value.first)).append('\t')
                .append(entry.value.second).append('\n')
        }
        return MergeResult(sb.toString(), local.size, incoming.size, kept.size)
    }

    /**
     * 备份导入专用：把合并后的文本**在同一把写盘锁内**加密落盘并同步内存态。
     *
     * 必须走这里，而不是「[writeEncryptedAtomically] + [load]」两步：运行期的防抖落盘
     * （[scheduleSave]）与 [flush] 会在任意时刻把**内存里的旧快照**写回同一个文件，两步之间被插一脚，
     * 就等于刚导入的词频被静默覆盖。[saveLock] 由本模块独占，导入与运行期写盘因此严格串行。
     *
     * @param enabled 与 `Prefs.userLearning` 一致；关着也照样填内存 —— `rank()` 自会挡掉，
     *   而用户随后打开开关时内存里已是导入后的表，不必等下次启动补载
     * @return 是否写盘成功（密钥不可用 / IO 失败一律 false，原文件与内存态都保持不动）
     */
    internal fun writeFromBackup(target: File, mergedText: String, enabled: Boolean): Boolean {
        this.enabled = enabled
        // 同步内部文件引用：导入之后运行期的防抖写盘 / flush 要落到同一个文件上
        file = target
        // 密钥解析放在锁外（会走一次 Keystore），与写盘的临界区分开
        val key = dataKey(target) ?: run {
            Diagnostics.w(TAG, "备份导入: 词面密钥不可用，词频未写入")
            return false
        }
        val written = synchronized(saveLock) {
            val v = version
            if (!writeEncryptedAtomically(target, mergedText, key)) {
                return@synchronized false
            }
            entries.clear()
            for ((word, e) in parse(mergedText, today(), PinyinEngine::toSimplified)) entries[word] = Entry(e.first, e.second)
            // 换代（BUG.md L-1281）：并发的补载（[applyReload]）若读的是替换前的文件，其内容已作废，
            // 靠这里自增的版本号把它挡在内存之外。放在重填**之后**：重填期间进入内存的并发学习
            // 也要算进 version 变化（下面的 dirty 判据与它同一口径）
            version++
            // 重填期间若有并发学习进入内存（version 变了，见 [remember] 的锁），就保持 dirty，
            // 让后续落盘把这份内存态（含那次学习）写回文件；无条件清掉等于把它永久跳过
            dirty = version != v + 1
            // 整份替换后盘上内容与内存一致 ⇒ 「盘上内容未知」不再成立（L-1409）
            loadBlocked = false
            true
        }
        if (written && dirty) scheduleSave()
        if (written) {
            Diagnostics.i(TAG, "备份导入: 词频已替换 ${entries.size} 条（学习开关=$enabled）")
        } else {
            Diagnostics.w(TAG, "备份导入: 词频写盘失败，保留原文件")
        }
        return written
    }

    /** 词频文件文本的行数统计（备份页预览用；坏行不计） */
    internal fun countEntries(text: String): Int = parse(text, today(), PinyinEngine::toSimplified).size

    // ── 落盘加密（整份 AEAD，密钥与台账共用）────────────────────────────────

    /**
     * 明文正文 → 加密文件文本：`#jinn-freq1` + 一行 `1:base64(iv||密文)`。
     *
     * 直接复用 [LedgerCrypto.encryptLine]：IV 随机、`require` 校验长度、解不开只返回 null 的口径
     * 与台账完全一致，不再维护第二套 AEAD 代码。
     */
    internal fun encryptDoc(plain: String, key: SecretKey): String =
        ENC_HEADER + "\n" + LedgerCrypto.encryptLine(plain, key) + "\n"

    /**
     * 加密文件文本 → 明文正文；解不开返回 null。
     *
     * 判据是「**找到第一条能解开的记录行**」而不是「首行必须是 [ENC_HEADER]」：首行标识一旦被删
     * （外部改写 / 部分恢复），仍然读得出来 —— 与台账缺头台账同一种处置（N3）。
     */
    internal fun decryptDoc(text: String, key: SecretKey): String? {
        if (text.isEmpty()) return null
        for (line in text.lineSequence()) {
            if (!line.startsWith(LedgerCrypto.RECORD_PREFIX)) continue
            LedgerCrypto.decryptLine(line, key)?.let { return it }
        }
        return null
    }

    /** 加密后原子落盘（fsync 后改名：掉电窗口里不会留下半截文件） */
    internal fun writeEncryptedAtomically(target: File, plain: String, key: SecretKey): Boolean =
        writeAtomically(target, encryptDoc(plain, key))

    /**
     * 读盘并就地迁移（旧明文 / 缺首行标识两种形态各一条路径）。
     *
     * internal 便于单测直接对一份文件跑迁移（`load` 需要 Context，JVM 侧拿不到）。
     *
     * @return 明文正文；null = **现在读不了**（密钥不可用 / 密文被改坏）——
     *   调用方必须按「不覆盖文件、不当成没有词频」处理，绝不能拿空表去覆盖。
     */
    internal fun readPlainOrMigrate(f: File): String? {
        val raw = runCatching { f.readText() }.getOrNull() ?: return null
        if (raw.isEmpty()) return ""
        val key = dataKey(f)
        val looksCipher = raw.startsWith(ENC_HEADER) || LedgerCrypto.looksLikeRecords(raw)
        if (key == null) {
            if (looksCipher) {
                Diagnostics.w(TAG, "用户词频读取跳过：词面密钥不可用")
                return null
            }
            // 旧版明文：密钥暂时不可用也能读，只是这次迁不了
            return raw
        }
        val plain = decryptDoc(raw, key)
        if (plain != null) {
            if (!raw.startsWith(ENC_HEADER)) {
                // 首行标识丢了（N3 同型）：补齐，别让下一次落盘把它写成「看起来像明文」的东西
                if (writeEncryptedAtomically(f, plain, key)) {
                    Diagnostics.i(TAG, "用户词频首行标识缺失，已补回")
                }
            }
            return plain
        }
        if (looksCipher) {
            // 密钥可用却解不开 ⇒ 密文被改坏 / 换了密钥。留一份原件再往下走，
            // 免得 2 秒后的防抖落盘把它整份覆盖（与 N2 的 fail-closed 同口径）
            quarantine(f)
            return null
        }
        // 旧版明文首行 `# jinn user_freq v1`：一次性迁移成密文（保留原文内容不变）
        if (writeEncryptedAtomically(f, raw, key)) {
            Diagnostics.i(TAG, "用户词频已迁移为加密格式")
        }
        return raw
    }

    /** 把解不开的密文文件改名保留（`user_freq.txt.bad`），避免随后被空表覆盖 */
    private fun quarantine(f: File) {
        val base = File(f.parentFile, f.name + ".bad")
        val dest = if (base.exists()) File(f.parentFile, f.name + ".bad-" + System.currentTimeMillis()) else base
        val ok = runCatching { java.nio.file.Files.move(f.toPath(), dest.toPath()) }.isSuccess
        if (ok) {
            Diagnostics.e(TAG, "用户词频密文无法解密，已改名为 ${dest.name}（原样保留）")
        } else {
            Diagnostics.w(TAG, "用户词频密文无法解密（改名保留失败，本次不落盘）")
        }
    }

    /**
     * 配置备份**导出**专用：把词频文件读成**明文**（包内条目必须是明文）。
     *
     * @return 明文；文件不存在返回空串；**读不出 / 解不开返回 null** ——
     *   调用方必须据此中止导出，静默按「空节」导出会让用户以为备份完整，换机后这部分全丢
     *   （与既有的 BUG-06 修复同一口径）。
     */
    internal fun readTextForBackup(file: File): String? {
        if (!file.isFile) return ""
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        if (raw.isEmpty()) return ""
        if (!raw.startsWith(ENC_HEADER) && !LedgerCrypto.looksLikeRecords(raw)) return raw
        val key = dataKey(file) ?: return null
        return decryptDoc(raw, key)
    }

    /** 落盘 / 读盘用的数据密钥：测试注入优先，生产走与台账共用的 DEK */
    private fun dataKey(target: File): SecretKey? =
        keyForTest ?: run {
            val dir = target.parentFile ?: return null
            LedgerCrypto.dataKey(dir)
        }

    /** 原子写：临时文件 + fsync + 改名（与索引缓存同一套写法，避免半截文件被当成有效数据） */
    internal fun writeAtomically(target: File, text: String): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            // 只对数据块 fsync：改名只保证目录项落盘，掉电后「文件在、内容是空/半截」比没写更糟
            out.fd.sync()
        }
        // 直接改名覆盖（Linux 的 rename 是原子的）：先删目标会制造一个「目标不存在」的窗口，
        // 恰好此时进程被杀，用户的词频文件就凭空消失了
        if (!tmp.renameTo(target)) {
            // 少数文件系统不允许覆盖式改名，才退回「删了再改名」
            if (target.exists() && !target.delete()) {
                tmp.delete()
                return@runCatching false
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@runCatching false
            }
        }
        true
    }.getOrElse {
        Diagnostics.w(TAG, "用户词频写盘失败（不影响本次使用）: ${it.message}")
        false
    }
}
