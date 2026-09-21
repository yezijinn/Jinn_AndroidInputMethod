package com.jinn.inputmethod

import org.json.JSONArray

/**
 * 「收藏」分组：用户自由 DIY 的符号集（默认第三位：全角/半角/收藏/…）。
 *
 * 持久化在 [Prefs.favoriteSymbols]，格式为 JSON 的二维数组（页的数组，页内是符号的数组）
 * —— 页内符号按序铺 26 键位（KeyboardLayouts.favoriteGroup）。
 *
 * 结构规则（全部由本对象纯函数保证）：
 * - 每页最多 [PER_PAGE]（26）个；追加时末页满 26 自动开新页；
 * - 追加**全局去重**（已存在返回 false，由调用方提示「已存在」）；
 * - 删除后后续符号前移补位（重排即天然满足「非末页恒 26 键」的全表规范），删空的页自动收起；
 * - 单项限长 [MAX_CHARS]（键面长文本会自动缩字号，但过长的可读性差）。
 *
 * 序列化容错：`null`（从未编辑过）→ 出厂预置 [DEFAULT_ITEMS]；损坏 JSON → 回退预置；
 * `"[]"`（用户删光）→ 空组 —— 删光是用户的明确意愿，不回退预置。
 *
 * [parse] 的输出**恒为规范结构**（每页 ≤ [PER_PAGE]、非末页满页、无重复、无空页）：
 * 数据可能来自旧版本或被外部改写，不归一就会出现「编辑页看得到、键盘上看不到」
 * （[KeyboardLayouts.favoriteGroup] 只铺 26 个键位，超出的部分静默丢弃）。
 * 页边界属实现细节：归一可能**合并**非规范数据的页（如 `[[3 项],[1 项]]` → `[[4 项]]`），
 * 但符号集合与顺序不变；规范结构（非末页恒 26）下重新分页是恒等变换，用户无感。
 *
 * 全部为纯函数，可直接 JVM 单测（见 `FavoriteSymbolsTest`）。
 */
object FavoriteSymbols {

    /** 每页符号数（= 键盘 26 键位） */
    const val PER_PAGE = 26

    /** 分组标签（进入 [SymbolOrder.DEFAULT] 的第 3 位） */
    const val LABEL = "收藏"

    /** 单个符号的最大字符数 */
    const val MAX_CHARS = 8

    /** 出厂预置：D I Y 三个字符（用户可自由删改） */
    val DEFAULT_ITEMS = listOf("D", "I", "Y")

    /**
     * 持久化串 → 页结构（恒为规范结构，见类注释）；[raw] 为 null（从未编辑）或损坏 → 出厂预置；
     * `"[]"` → 空组。
     */
    fun parse(raw: String?): List<List<String>> {
        if (raw == null) return listOf(DEFAULT_ITEMS)
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            // 一步完成「拉平 + 全局去重（保序）」；空页与超页在重新分页时自然消解
            val flat = LinkedHashSet<String>()
            for (p in 0 until arr.length()) {
                val page = arr.getJSONArray(p)
                for (i in 0 until page.length()) flat.add(page.getString(i))
            }
            flat.toList().chunked(PER_PAGE)
        } catch (_: Exception) {
            listOf(DEFAULT_ITEMS)
        }
    }

    /** 页结构 → 持久化串（空组序列化为 `[]`） */
    fun serialize(pages: List<List<String>>): String {
        val arr = JSONArray()
        pages.forEach { page ->
            val p = JSONArray()
            page.forEach { p.put(it) }
            arr.put(p)
        }
        return arr.toString()
    }

    /**
     * 追加一个符号：全局去重 + 末页满 [PER_PAGE] 自动开新页。
     * 返回 (新页结构, 是否成功) —— 空串/超长/已存在均为 false。
     */
    fun append(pages: List<List<String>>, item: String): Pair<List<List<String>>, Boolean> {
        val s = item.trim()
        if (s.isEmpty() || s.length > MAX_CHARS) return pages to false
        if (pages.any { it.contains(s) }) return pages to false
        val last = pages.lastOrNull()
        val next = when {
            last == null -> listOf(listOf(s))
            last.size >= PER_PAGE -> pages + listOf(listOf(s))
            else -> pages.dropLast(1) + listOf(last + s)
        }
        return next to true
    }

    /** 删除全局扁平下标处的符号：后续前移补位，删空的页自动收起 */
    fun removeAt(pages: List<List<String>>, flatIndex: Int): List<List<String>> {
        val flat = pages.flatten()
        if (flatIndex !in flat.indices) return pages
        val rest = flat.toMutableList().apply { removeAt(flatIndex) }
        return rest.chunked(PER_PAGE)
    }
}
