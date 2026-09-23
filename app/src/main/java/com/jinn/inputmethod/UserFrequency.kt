package com.jinn.inputmethod

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 * 存储：`filesDir/user_freq.txt`（`词<TAB>权重<TAB>天`），落盘走 BackgroundIo + 防抖 2s，
 * 窗口内会把「最后一次」排成尾沿任务补写（只做前沿丢弃的话，进程被 LMK 直杀时
 * 最近几次学习会丢，而 `flush` 只在服务收尾时才跑）；原子落盘（临时文件 + 改名）；
 * 退出时 [flush] 同步补一次。上限 [MAX_ENTRIES] 条，权重低于 [MIN_WEIGHT] 丢弃，文件稳定在几十 KB。
 */
internal object UserFrequency {

    private const val TAG = "UserFrequency"
    private const val FILE_NAME = "user_freq.txt"
    private const val HEADER = "# jinn user_freq v1"
    private const val MAX_ENTRIES = 3000
    private const val MIN_WEIGHT = 0.15
    private const val SAVE_DEBOUNCE_MS = 2000L

    /** 词 → (权重, 最后更新日) */
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    @Volatile
    private var enabled = true

    @Volatile
    private var dirty = false

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
        this.enabled = enabled
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val f = File(context.filesDir, FILE_NAME)
        file = f
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
            val parsed = parse(f.readText(), now)
            entries.clear()
            for ((word, v) in parsed) entries[word] = Entry(v.first, v.second)
            Diagnostics.i(TAG, "用户词频: 已载入 ${parsed.size} 条（含按天衰减）")
        }.onFailure {
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
        enabled = value
        Diagnostics.i(TAG, "用户词频学习: ${if (value) "开启" else "关闭"}")
        if (value && entries.isEmpty()) {
            val f = file ?: return
            BackgroundIo.run { reload(f) }
        }
    }

    /** 从文件补载历史（开关从关切到开时用；[entries] 已有内容则不动） */
    private fun reload(f: File) {
        if (!f.isFile) return
        runCatching {
            val parsed = parse(f.readText(), today())
            if (parsed.isEmpty()) return
            for ((word, v) in parsed) entries.putIfAbsent(word, Entry(v.first, v.second))
            Diagnostics.i(TAG, "用户词频: 开关打开后补载 ${parsed.size} 条")
        }.onFailure {
            Diagnostics.w(TAG, "用户词频补载失败（按未学习处理）: ${it.message}")
        }
    }

    /** 测试/复位用：清空内存态（不删文件） */
    internal fun resetForTest() {
        entries.clear()
        dirty = false
        enabled = true
        file = null
        lastSaveAt = 0L
        savePending = false
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
        // 内存态更新放进写盘锁：导入的 replaceFromBackup 会在同一把锁内做「clear + 重填 + 清脏」，
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
        BackgroundIo.run {
            synchronized(saveLock) {
                // 陈旧任务校验：任务里捕获的是排程当时的 File。若此后目标文件已变
                // （测试的 setFileForTest、未来可能的重新 load），旧任务既不该写到旧路径，
                // 更不该把新文件的 `dirty` 清掉（那会让新内容白白跳过一轮落盘）。
                if (file !== f) return@synchronized
                // 清 dirty 前核对版本（见 [version]）：渲染与清标记之间可能有并发学习进内存
                val v = version
                if (writeAtomically(f, render()) && version == v) dirty = false
            }
        }
    }

    /** 退出/切后台时调用（主线程），保证最后一次学习不丢 */
    fun flush() {
        val f = file ?: return
        if (!dirty) return
        // 这里同步写：只在 onDestroy / 切后台这种一次性收尾时调用，丢给 BackgroundIo 的话，
        // 服务销毁后进程可能马上被杀、任务来不及跑，最后几次学习就白记了。
        // 文件最多 3000 行，主线程写一次 1~3ms，可以接受。渲染同样放在锁内（见 [saveNow]）。
        //
        // 只有写盘成功才清 dirty：若先清再写，写失败（磁盘满 / IO 错误）后这次学习
        // 再没有任何路径会重试（下一次 flush 直接因 `!dirty` 返回），等于静默丢失，
        // 恰是 flush 本要避免的事。判据与 [saveNow] 保持一致。
        synchronized(saveLock) {
            val v = version
            if (writeAtomically(f, render()) && version == v) dirty = false
        }
    }

    // ── 排序 ────────────────────────────────────────────────────────────────

    /**
     * 按用户权重稳定重排候选：权重降序，未学过的按原顺序排在后面。
     *
     * 未学习时（[entries] 为空）直接返回原数组，零开销、零行为变化。
     */
    fun rank(words: Array<String>): Array<String> {
        if (!enabled || entries.isEmpty() || words.size < 2) return words
        var anyKnown = false
        for (w in words) if (entries.containsKey(w)) { anyKnown = true; break }
        if (!anyKnown) return words
        return words.sortedByDescending { entries[it]?.weight ?: 0.0 }.toTypedArray()
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
    internal fun parse(text: String, nowDay: Int): Map<String, Pair<Double, Int>> {
        val out = HashMap<String, Pair<Double, Int>>()
        for (raw in text.split('\n')) {
            val line = raw.trim('\r', ' ', '\t')
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val word = parts[0]
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
                out[word] = Pair(decayed, maxOf(day, prev?.second ?: day))
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

    internal fun mergeForBackup(localText: String, incomingText: String, nowDay: Int): MergeResult {
        val local = parse(localText, nowDay)
        val incoming = parse(incomingText, nowDay)
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
     * 备份导入专用：把合并后的文本**在同一把写盘锁内**落盘并同步内存态。
     *
     * 必须走这里，而不是「[writeAtomically] + [load]」两步：运行期的防抖落盘（[scheduleSave]）
     * 与 [flush] 会在任意时刻把**内存里的旧快照**写回同一个文件，两步之间被插一脚，就等于
     * 刚导入的词频被静默覆盖。[saveLock] 由本模块独占，导入与运行期写盘因此严格串行。
     *
     * @param enabled 与 `Prefs.userLearning` 一致；关着也照样填内存 —— `rank()` 自会挡掉，
     *   而用户随后打开开关时内存里已是导入后的表，不必等下次启动补载
     * @return 是否写盘成功
     */
    internal fun replaceFromBackup(target: File, mergedText: String, enabled: Boolean): Boolean {
        this.enabled = enabled
        // 同步内部文件引用：导入之后运行期的防抖写盘 / flush 要落到同一个文件上
        file = target
        val written = synchronized(saveLock) {
            val v = version
            if (!writeAtomically(target, mergedText)) {
                return@synchronized false
            }
            entries.clear()
            for ((word, e) in parse(mergedText, today())) entries[word] = Entry(e.first, e.second)
            // 重填期间若有并发学习进入内存（version 变了，见 [remember] 的锁），就保持 dirty，
            // 让后续落盘把这份内存态（含那次学习）写回文件；无条件清掉等于把它永久跳过
            dirty = version != v
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
    internal fun countEntries(text: String): Int = parse(text, today()).size

    /** 原子写：临时文件 + 改名（与索引缓存同一套写法，避免半截文件被当成有效数据） */
    internal fun writeAtomically(target: File, text: String): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
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
