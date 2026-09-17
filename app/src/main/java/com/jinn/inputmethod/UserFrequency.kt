package com.jinn.inputmethod

import android.content.Context
import java.io.File

/**
 * 用户词频学习（吸收 librime `UserDictionary` / `UserDb` 的设计）。
 *
 * 为什么需要
 * ----------
 * 词库的候选顺序是**构建期**按通用词频排好的，但每个人常用的词不同：你天天打「虚拟」「编译」，
 * 它们在通用词频里可能排在几十名开外。这里记录「用户**实际选过**的候选」，并把它们提到前面。
 *
 * 语义（对齐 librime，见 `docs/librime-master/src/rime/dict/user_dictionary.cc`）
 * ---------------------------------------------------------------------------
 *  · 条目 = (词, 权重 dee, 最后更新日 tick)。tick 是**天计数**（`now / 86400000`）。
 *  · 累加公式取自 librime `algo::formula_d`（`algo/dynamics.h`）：
 *      `dee_new = commits + dee_old * exp((tick_old - tick_now) / 200)`
 *    即**旧值按天指数衰减**（半衰期 ≈ 139 天），新选中一次加 1。这样很久不打的选择会自然让位，
 *    而持续使用的词会稳定排在前面。
 *  · 只在**明确选择候选**时记一次（点候选栏 / 空格取首候选 / 点补全项），
 *    自动提交（收起键盘时的 `commitComposing`）与语音/剪贴板粘贴**不计**——那些不是用户的选择。
 *
 * 排序规则
 * --------
 * [rank] 对候选做**稳定排序**：权重降序，权重相同时**保持原顺序**（即词库顺序）。
 * 因此它对没学过的候选零影响，不会打乱通用词频的既有手感。
 *
 * 存储与线程
 * ----------
 *  · 文件 `filesDir/user_freq.txt`，制表符分隔，**写盘走 `BackgroundIo`** 且做防抖（最多 2s 一次），
 *    **原子落盘**（临时文件 + 改名）——与索引缓存同一套写法。
 *  · 内存里只有一张 [java.util.concurrent.ConcurrentHashMap]：查询线程读、学习线程写；
 *    启动加载在后台线程且显式降优先级（不抢词库加载的 CPU）。
 *  · 上限 [MAX_ENTRIES]（超出淘汰权重最低者）、[MIN_WEIGHT] 以下丢弃，文件恒定在几十 KB 量级。
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
        val now = today()
        entries.compute(word) { _, old ->
            // librime formula_d：dee_new = commits + dee_old * exp((tick_old - tick_now) / 200)
            val decayed = (old?.weight ?: 0.0) * Math.exp(((old?.day ?: now) - now) / 200.0)
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
        BackgroundIo.run { writeAtomically(f, text) }
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
