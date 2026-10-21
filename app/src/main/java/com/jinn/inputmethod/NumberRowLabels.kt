package com.jinn.inputmethod

import org.json.JSONArray

/**
 * 数字行 10 个按钮的文本（下标即键位，0 = 最左）。
 *
 * 持久化在 [Prefs.numberRowLabels]：JSON 数组，**恒 10 项**，`""` = 回落默认数字。
 * 「显示即上屏」：键面显示什么，点它就上屏什么 —— 上屏侧直接读 `PinyinKey.label`，
 * 不另存一份「原数字」，因此显示与上屏**结构上无法分叉**。
 *
 * 结构规则（全部由本对象保证）：
 * - [parse] 输出恒为 10 项，空串合法（= 默认）；非 JSON / 非数组 / 长度≠10 → **整份**回退全空；
 * - 单项清洗走 [FavoriteSymbols.clean]（零宽 / U+3000 / NBSP 一律清掉，防「空槽键」）；
 * - 单项限长走 [FavoriteSymbols.withinLimit] 的**码点**口径（emoji 不被误拒）。
 *   注意：与收藏的 8 不同，这里 [MAX_CHARS] = 4：数字行键宽只有字母键的一半（10 列 vs 9/10 列
 *   但同行更窄），4 个汉字已被宽度收束到约 10sp，再长就认不出（见 PinyinKey 的
 *   `fitTextSize`：它只兜「不溢出」，不兜可读性）。
 * - 默认值是**常量**（[DEFAULT_KEYS]），不与用户值共用一套存储：用户没改过时 prefs 里
 *   连键都不会有，将来调默认顺序即对所有未自定义的用户生效。
 *
 * 序列化容错与 [FavoriteSymbols] 同思路但更严：那边「单页坏只丢该页」，这里**位置即语义**，
 * 少一项就整体失效，所以长度不符一律整份回退 —— 少一个状态比多一个状态便宜。
 */
object NumberRowLabels {

    /** 槽位数（= 数字行键数） */
    const val COUNT = 10

    /**
     * 单槽上限（码点）。
     *
     * 不是布局约束（8 也不会溢出），是**可读性政策**：键宽约 48dp，可用约 40dp，
     * 4 个汉字每字约 10sp 已是下限，8 个会被 `fitTextSize` 压成 5sp 的糊字。
     */
    const val MAX_CHARS = 4

    /** 出厂顺序：电话键盘自然序（1 左 0 右） */
    private const val KEYS = "1234567890"

    /** 默认键面文本（恒 10 项，永不为空） */
    val DEFAULT_KEYS: List<String> = KEYS.map { it.toString() }

    /**
     * 持久化串 → 10 个**原始槽位**（`""` = 该槽未自定义）。
     *
     * 与 [effective] 分开是刻意的：前者保「用户改过什么」，后者出「键面该显示什么」。
     * 只用一个函数就会把默认值字面量写进 prefs（用户没改过也存满 10 个数字），
     * 将来调默认就对老用户永久失效。
     *
     * 结构不符（非 JSON / 非数组 / 长度≠10）→ 整份全空（= 全默认），不抛。
     */
    fun parse(raw: String?): List<String> {
        val empty = List(COUNT) { "" }
        if (raw == null || raw.isBlank()) return empty
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return empty
        if (arr.length() != COUNT) return empty
        return (0 until COUNT).map { i ->
            val s = runCatching { arr.getString(i) }.getOrNull() ?: return@map ""
            normalize(s)
        }
    }

    /**
     * 生效标签（恒 10 项，**永不为空**）：键面与上屏的唯一取值口。
     *
     * 空槽在这里被填回默认数字 ⇒ 键面不会出现「按了没反应」的空槽键
     * （BUG.md 第 15 批 L7 在收藏侧记过同一形态）。
     */
    fun effective(raw: String?): List<String> =
        parse(raw).mapIndexed { i, s -> s.ifEmpty { DEFAULT_KEYS[i] } }

    /**
     * 槽位列表 → 持久化串（恒 10 项；越界下标按空槽，脏值在此被压成 `""`）。
     *
     * 超长项压成 `""` 而不是保留原文：位置语义下「该槽回默认」是可见、可修的状态；
     * 悄悄留下超长项只会在键面上变成糊字。
     */
    fun serialize(labels: List<String>): String {
        val arr = JSONArray()
        for (i in 0 until COUNT) {
            arr.put(normalize(labels.getOrElse(i) { "" }))
        }
        return arr.toString()
    }

    /** 单项归一：清洗（[FavoriteSymbols.clean] 同口径）+ 限长（码点）不过关 → `""` */
    private fun normalize(text: String): String {
        val c = FavoriteSymbols.clean(text)
        return if (c.isEmpty() || !FavoriteSymbols.withinLimit(c, MAX_CHARS)) "" else c
    }
}
