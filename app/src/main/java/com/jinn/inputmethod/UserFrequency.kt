package com.jinn.inputmethod

import android.content.Context
import java.io.File

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
 * 原子落盘（临时文件 + 改名）；退出时 [flush] 同步补一次。上限 [MAX_ENTRIES] 条，
 * 权重低于 [MIN_WEIGHT] 丢弃，文件稳定在几十 KB。
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
     * 启动加载（**必须在后台线程、且显式降优先级**）：读文件 + 按天衰减。
     * @param enabled 用户设置里的开关；关掉时不加载也不学习
     */
    fun load(context: Context, enabled: Boolean) {
        this.enabled = enabled
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val f = File(context.filesDir, FILE_NAME)
        file = f
        if (!enabled) {
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
     * 关闭后不再学习、也不参与排序——既有记录留在文件里，重新打开即恢复。
     */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        Diagnostics.i(TAG, "用户词频学习: ${if (value) "开启" else "关闭"}")
    }

    /** 测试/复位用：清空内存态（不删文件） */
    internal fun resetForTest() {
        entries.clear()
        dirty = false
        enabled = true
        file = null
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

    internal fun sizeForTest(): Int = entries.size

    // ── 学习 ────────────────────────────────────────────────────────────────

    /**
     * 记一次「用户选择了 [word]」。主线程调用，只做一次哈希更新 + 防抖落盘，绝不阻塞输入。
     */
    fun remember(word: String) {
        if (!enabled || word.isEmpty()) return
        // 行格式是 `词<TAB>权重<TAB>天`，含制表符/换行的词无法表示：直接不学（否则会写坏一行，
        // 下次 parse 会整行跳过 —— 静默丢失且难以察觉）。正常候选来自词库，不会命中这条。
        if (word.indexOf('\t') >= 0 || word.indexOf('\n') >= 0 || word.indexOf('\r') >= 0) {
            return
        }
        val now = today()
        entries.compute(word) { _, old ->
            // librime formula_d：dee_new = commits + dee_old * exp((tick_old - tick_now) / 200)
            // 与 parse 一致地对「未来 day」钳位：用户系统时钟回拨时 old.day > now，
            // 不钳位的话 exp(正数) 会把权重放大到天文数字（回拨 2000 天 = 22 万倍），此后永久霸榜。
            val elapsed = ((old?.day ?: now) - now).coerceAtMost(0)
            val decayed = (old?.weight ?: 0.0) * Math.exp(elapsed / 200.0)
            Entry(decayed + 1.0, now)
        }
        trimIfNeeded()
        dirty = true
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

    private fun scheduleSave() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < SAVE_DEBOUNCE_MS) return      // 防抖：连续选择只落盘一次
        lastSaveAt = now
        BackgroundIo.run {
            val text = render()
            if (writeAtomically(f, text)) dirty = false
        }
    }

    /** 退出/切后台时调用（主线程），保证最后一次学习不丢 */
    fun flush() {
        val f = file ?: return
        if (!dirty) return
        val text = render()
        dirty = false
        // 这里同步写：只在 onDestroy / 切后台这种一次性收尾时调用，丢给 BackgroundIo 的话，
        // 服务销毁后进程可能马上被杀、任务来不及跑，最后几次学习就白记了。
        // 文件最多 3000 行，主线程写一次 1~3ms，可以接受。
        writeAtomically(f, text)
    }

    // ── 排序 ────────────────────────────────────────────────────────────────

    /**
     * 按用户权重**稳定**重排候选：权重降序，未学过的按原顺序排在后面。
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
     * 把内存态渲染成文件文本（**纯函数**）。权重降序，便于人肉排查。
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
     * 解析文件文本并**按天衰减**（**纯函数**，便于单测）。
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
            if (word.isEmpty() || weight <= 0.0) continue
            val decayed = weight * Math.exp(((day - nowDay).coerceAtMost(0)) / 200.0)
            if (decayed < MIN_WEIGHT) continue
            val prev = out[word]
            if (prev == null || decayed > prev.first) {
                out[word] = Pair(decayed, maxOf(day, prev?.second ?: day))
            }
        }
        return out
    }

    /** 原子写：临时文件 + 改名（与索引缓存同一套写法，避免半截文件被当成有效数据） */
    internal fun writeAtomically(target: File, text: String): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        if (target.exists() && !target.delete()) {
            tmp.delete()
            return@runCatching false
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return@runCatching false
        }
        true
    }.getOrElse {
        Diagnostics.w(TAG, "用户词频写盘失败（不影响本次使用）: ${it.message}")
        false
    }
}
