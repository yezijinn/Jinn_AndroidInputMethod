package com.jinn.inputmethod

/**
 * 模糊音容错：把「口音上不分的声母 / 韵母」做成音节级等价类，供 [PinyinEngine.query]
 * 派生变体候选键。
 *
 * 设计约束（改动前先读，几条都是踩过的坑）：
 *  - 纯函数、零数据文件：规则全写在代码里（Release APK 上限 5MB，词表类数据一律走可选下载包）。
 *  - 只做「单个音节被整体替换」的派生（[keyVariants]）：多音节同时模糊会让键组合成倍增长，
 *    而本引擎没有语言模型兜底，候选一多就是噪声而不是帮助。
 *  - 变体必须仍是合法音节（调用方传 [isLegal]，实际是 `validSyllables.contains`：不带 `loaded`
 *    门，加载期也能派生；`PinyinEngine.isValidSyllable` 比它多一个 `loaded &&`，两者不等价）：
 *    否则会凭空造出词库里不可能存在的键（例如 r/y 混淆派生出的 `yen`）。
 *  - 掩码 [NONE]（0）表示关闭：此时两个派生函数恒返回空，历史行为逐候选不变。
 *  - 结果顺序 = [GROUPS] 声明顺序：同一输入每次得到同一批变体（可单测、可回归对拍）。
 *
 * 绝不在这里读配置：开关由 [PinyinEngine] 持有（掩码进 [Prefs] 与备份白名单），本类保持可 JVM 单测。
 */
object FuzzyPinyin {

    /** 关闭：不派生任何变体（也是出厂默认） */
    const val NONE = 0

    const val Z_ZH = 1 shl 0
    const val C_CH = 1 shl 1
    const val S_SH = 1 shl 2
    const val N_L = 1 shl 3
    const val F_H = 1 shl 4
    const val R_L = 1 shl 5
    const val R_Y = 1 shl 6
    const val K_G = 1 shl 7
    const val EN_ENG = 1 shl 8
    const val IN_ING = 1 shl 9
    const val AN_ANG = 1 shl 10

    /** 全部组：设置页「全开」与掩码归一都用它，别在各处重复拼位 */
    const val MASK_ALL: Int =
        Z_ZH or C_CH or S_SH or N_L or F_H or R_L or R_Y or K_G or EN_ENG or IN_ING or AN_ANG

    /** 单音节最多派生几个变体（超出按 [GROUPS] 顺序截断） */
    const val MAX_VARIANTS_PER_SYLLABLE = 3

    /** 整串最多派生几个变体键（防多音节候选膨胀） */
    const val MAX_KEY_VARIANTS = 8

    /** 派生位置：[INITIAL] 声母位（音节开头）、[FINAL] 韵尾位（音节结尾） */
    enum class Kind { INITIAL, FINAL }

    /**
     * 一组模糊音：[bit] 掩码位，[a] / [b] 互相混淆的两个串，[kind] 决定替换位置。
     *
     * [label] 供设置页多选对话框直接显示（文案在代码里，`strings.xml` 默认禁改）。
     */
    class Group(
        val bit: Int,
        val a: String,
        val b: String,
        val kind: Kind,
        val label: String,
    )

    /**
     * 全部模糊音组（顺序即派生顺序，也是设置页条目的顺序）。
     *
     * 声母组按「长串优先」匹配：`zh` 必须比 `z` 先判，否则 `zhang` 会被当成 `z` 开头而派生出
     * `zhhang` 这类垃圾（见 [derive]）。
     */
    val GROUPS: List<Group> = listOf(
        Group(Z_ZH, "z", "zh", Kind.INITIAL, "zh ⇄ z（平翘舌）"),
        Group(C_CH, "c", "ch", Kind.INITIAL, "ch ⇄ c（平翘舌）"),
        Group(S_SH, "s", "sh", Kind.INITIAL, "sh ⇄ s（平翘舌）"),
        Group(N_L, "n", "l", Kind.INITIAL, "n ⇄ l（鼻边音）"),
        Group(F_H, "f", "h", Kind.INITIAL, "f ⇄ h（唇齿/舌根）"),
        Group(R_L, "r", "l", Kind.INITIAL, "r ⇄ l（声母）"),
        Group(R_Y, "r", "y", Kind.INITIAL, "r ⇄ y（声母）"),
        Group(K_G, "k", "g", Kind.INITIAL, "k ⇄ g（声母）"),
        Group(EN_ENG, "en", "eng", Kind.FINAL, "en ⇄ eng（前后鼻音）"),
        Group(IN_ING, "in", "ing", Kind.FINAL, "in ⇄ ing（前后鼻音）"),
        Group(AN_ANG, "an", "ang", Kind.FINAL, "an ⇄ ang（前后鼻音）"),
    )

    /** 掩码归一：只保留已定义的组，越界位（旧版本配置 / 外部写入）一律丢弃 */
    fun clampMask(mask: Int): Int = mask and MASK_ALL

    /**
     * 单音节的模糊变体（**不含**原音节）。
     *
     * @param isLegal 合法音节判定；不合法或与原音节相同的派生结果直接丢弃
     * @return 去重后按 [GROUPS] 顺序排列，最多 [MAX_VARIANTS_PER_SYLLABLE] 个；掩码为 0 时恒为空
     */
    fun variantsOf(syllable: String, mask: Int, isLegal: (String) -> Boolean): List<String> {
        if (syllable.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (group in GROUPS) {
            // 掩码为 0 时这里逐组全部跳过：只留这一处判据，避免「先短路、再逐组」两处各判一次
            if (mask and group.bit == 0) continue
            val variant = derive(group, syllable) ?: continue
            if (variant == syllable || !isLegal(variant)) continue
            out.add(variant)
            if (out.size >= MAX_VARIANTS_PER_SYLLABLE) break
        }
        return out.toList()
    }

    /**
     * 整串拼音键的模糊变体（**不含**原键）。
     *
     * 只做「替换其中一个音节」的派生：既覆盖绝大多数口音场景（一次只对不上一个音），
     * 又让变体数随音节数线性增长而不是指数增长。上限 [MAX_KEY_VARIANTS] 截断。
     *
     * @return 去重后按「音节下标 → 变体顺序」排列；掩码为 0 或音节为空时恒为空
     */
    fun keyVariants(syllables: List<String>, mask: Int, isLegal: (String) -> Boolean): List<String> {
        if (mask == NONE || syllables.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val buf = ArrayList(syllables)
        for (i in syllables.indices) {
            val variants = variantsOf(syllables[i], mask, isLegal)
            for (variant in variants) {
                buf[i] = variant
                out.add(buf.joinToString(""))
                if (out.size >= MAX_KEY_VARIANTS) return out.toList()
            }
            buf[i] = syllables[i]
        }
        return out.toList()
    }

    /**
     * 单组派生：命中返回替换后的音节，否则 null。
     *
     * 声母组先判长串（[Group.b] 或 [Group.a] 中较长者），再判另一个 —— `zh` / `ch` / `sh`
     * 的首字母同时也是独立声母，判反了会把 `zhang` 变成 `zhhang`。
     */
    private fun derive(group: Group, syllable: String): String? = when (group.kind) {
        Kind.INITIAL -> {
            val (long, short) = if (group.a.length >= group.b.length) {
                group.a to group.b
            } else {
                group.b to group.a
            }
            when {
                syllable.startsWith(long) -> short + syllable.substring(long.length)
                syllable.startsWith(short) -> long + syllable.substring(short.length)
                else -> null
            }
        }
        Kind.FINAL -> {
            val (long, short) = if (group.a.length >= group.b.length) {
                group.a to group.b
            } else {
                group.b to group.a
            }
            when {
                syllable.endsWith(long) -> syllable.dropLast(long.length) + short
                syllable.endsWith(short) -> syllable.dropLast(short.length) + long
                else -> null
            }
        }
    }
}
