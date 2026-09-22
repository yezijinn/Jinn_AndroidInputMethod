package com.jinn.inputmethod

/**
 * 符号分组顺序：用户在排序页（`SymbolOrderActivity`）调整后写入，键盘视图重建即生效。
 *
 * 持久化按 label 而不是下标：分组数据（[SYMBOL_GROUPS] + 「收藏」）会随版本增删，
 * 下标会把「第 3 个」指到另一个分组上，label 则天然跟随内容（只有改分组名才失效）。
 *
 * 归一规则（[normalize]）：去重 → 丢掉未知 label → 未提及的分组按默认次序追加到末尾
 *，版本新增的分组因此总是落在「用户没动过的尾巴」上，不会打乱既有顺序。
 * 例外：「收藏」加入前的旧顺序串里没有它 → 插回「半角」之后（默认第三位），
 * 既保住用户已调的顺序，又不让收藏落到尾巴上。
 *
 * 全部为纯函数，可直接 JVM 单测（见 `SymbolOrderTest`）。
 */
object SymbolOrder {

    /** 内置分组的默认顺序（不含「收藏」， 收藏是动态组，见 [KeyboardLayouts.favoriteGroup]） */
    private val BUILTIN: List<String> = SYMBOL_GROUPS.map { it.label }

    /** 默认顺序 = 全角 / 半角 / 收藏 / 编程 / 标点 / 特殊 / 序号 / 数学 / …（收藏固定第三） */
    val DEFAULT: List<String> = BUILTIN.toMutableList().apply { add(2, FavoriteSymbols.LABEL) }

    /** 持久化串 → 归一后的顺序（空串 = 默认） */
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return DEFAULT
        return normalize(raw.split(','))
    }

    /** 顺序 → 持久化串 */
    fun serialize(order: List<String>): String = normalize(order).joinToString(",")

    /** 去重 + 过滤未知 + 补缺失；结果恒为 [DEFAULT] 的一个排列 */
    fun normalize(saved: List<String>): List<String> {
        val known = saved.map { it.trim() }.filter { it in DEFAULT }.distinct()
        val result = (known + DEFAULT.filter { it !in known }).toMutableList()
        // 旧版本顺序串不含「收藏」：插到「半角」之后（默认第三位），不打乱用户已调的其余顺序
        if (FavoriteSymbols.LABEL !in known) {
            result.remove(FavoriteSymbols.LABEL)
            val i = result.indexOf("半角")
            if (i >= 0) result.add(i + 1, FavoriteSymbols.LABEL)
        }
        return result
    }

    /** 把第 [from] 个分组移到第 [to] 位（越界自动钳位；同位置原样返回） */
    fun move(order: List<String>, from: Int, to: Int): List<String> {
        val list = normalize(order).toMutableList()
        if (list.size < 2) return list
        val f = from.coerceIn(0, list.lastIndex)
        val t = to.coerceIn(0, list.lastIndex)
        if (f == t) return list
        list.add(t, list.removeAt(f))
        return list
    }

    /**
     * 按持久化串取分组对象（顺序即用户自定义顺序）。
     * [favorite] 是从用户数据构建的「收藏」组（[KeyboardLayouts.favoriteGroup]），缺省不带入；
     * internal：返回 internal 类型的 [SymbolGroup]。
     */
    internal fun groupsInOrder(raw: String, favorite: SymbolGroup? = null): List<SymbolGroup> {
        val byLabel = SYMBOL_GROUPS.associateBy { it.label }.let {
            if (favorite == null) it else it + (favorite.label to favorite)
        }
        return parse(raw).mapNotNull { byLabel[it] }
    }
}
