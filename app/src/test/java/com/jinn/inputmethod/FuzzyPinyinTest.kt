package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模糊音纯逻辑测试（不依赖 Android 资源）。
 *
 * 合法音节判定用一份真实音节子集当 oracle：**「不合法就丢弃」是这套规则的关键约束** ——
 * r/y、r/l 混淆会派生 `yen`、`ring` 这类词库里不存在的键，靠词库撞空不如在这里挡掉。
 */
class FuzzyPinyinTest {

    /** 真实合法音节子集（故意不含 `yen` / `ring` / `zhhang` 这类非法形式） */
    private val legal = setOf(
        "zang", "zhang", "zan", "zhan", "sen", "shen", "seng", "sheng", "sang", "shang", "shan",
        "la", "na", "li", "ni", "lin", "nin", "ling", "ning", "leng", "neng",
        "jin", "jing", "yin", "ying", "xian", "xiang", "ri", "yi", "ren", "rao", "yao",
        "cang", "chang", "ci", "chi", "si", "shi", "gen", "geng", "ken", "keng",
        "fan", "han", "fang", "hang", "rang", "lang", "yang", "ran",
    )

    private val isLegal = { s: String -> legal.contains(s) }

    @Test
    fun `掩码为 0 时不派生任何变体`() {
        assertTrue(FuzzyPinyin.variantsOf("zhang", FuzzyPinyin.NONE, isLegal).isEmpty())
        assertTrue(FuzzyPinyin.keyVariants(listOf("zhang", "guo"), FuzzyPinyin.NONE, isLegal).isEmpty())
    }

    @Test
    fun `声母组双向派生且长声母优先`() {
        // zh → z 与 z → zh 两个方向都要有
        assertEquals(listOf("zang"), FuzzyPinyin.variantsOf("zhang", FuzzyPinyin.Z_ZH, isLegal))
        assertEquals(listOf("zhang"), FuzzyPinyin.variantsOf("zang", FuzzyPinyin.Z_ZH, isLegal))
        // 判反了会派生出 zhhang（zh 的首字母 z 本身就是独立声母）
        assertFalse(FuzzyPinyin.variantsOf("zhang", FuzzyPinyin.Z_ZH, isLegal).contains("zhhang"))
        assertEquals(listOf("chang"), FuzzyPinyin.variantsOf("cang", FuzzyPinyin.C_CH, isLegal))
        assertEquals(listOf("sang"), FuzzyPinyin.variantsOf("shang", FuzzyPinyin.S_SH, isLegal))
        assertEquals(listOf("na"), FuzzyPinyin.variantsOf("la", FuzzyPinyin.N_L, isLegal))
        assertEquals(listOf("la"), FuzzyPinyin.variantsOf("na", FuzzyPinyin.N_L, isLegal))
        assertEquals(listOf("han"), FuzzyPinyin.variantsOf("fan", FuzzyPinyin.F_H, isLegal))
        assertEquals(listOf("keng"), FuzzyPinyin.variantsOf("geng", FuzzyPinyin.K_G, isLegal))
    }

    @Test
    fun `韵尾组双向派生且只动韵尾`() {
        assertEquals(listOf("shen"), FuzzyPinyin.variantsOf("sheng", FuzzyPinyin.EN_ENG, isLegal))
        assertEquals(listOf("sheng"), FuzzyPinyin.variantsOf("shen", FuzzyPinyin.EN_ENG, isLegal))
        assertEquals(listOf("jin"), FuzzyPinyin.variantsOf("jing", FuzzyPinyin.IN_ING, isLegal))
        assertEquals(listOf("ying"), FuzzyPinyin.variantsOf("yin", FuzzyPinyin.IN_ING, isLegal))
        assertEquals(listOf("xian"), FuzzyPinyin.variantsOf("xiang", FuzzyPinyin.AN_ANG, isLegal))
        // 不含该韵尾的音节不受影响：`jin` 的韵尾是 in，与 an/ang 无关
        assertTrue(FuzzyPinyin.variantsOf("jin", FuzzyPinyin.AN_ANG, isLegal).isEmpty())
    }

    @Test
    fun `派生出的非法音节一律丢弃`() {
        // ren → yen（y 拼不出 en 韵）在真实音节表里不存在，必须挡掉
        assertTrue(FuzzyPinyin.variantsOf("ren", FuzzyPinyin.R_Y, isLegal).isEmpty())
        // 合法的那部分照常派生
        assertEquals(listOf("yi"), FuzzyPinyin.variantsOf("ri", FuzzyPinyin.R_Y, isLegal))
        assertEquals(listOf("yao"), FuzzyPinyin.variantsOf("rao", FuzzyPinyin.R_Y, isLegal))
    }

    @Test
    fun `一个音节最多派生 MAX_VARIANTS_PER_SYLLABLE 个变体`() {
        for (syllable in legal) {
            val variants = FuzzyPinyin.variantsOf(syllable, FuzzyPinyin.MASK_ALL, isLegal)
            assertTrue(
                "$syllable 派生数量越界：$variants",
                variants.size <= FuzzyPinyin.MAX_VARIANTS_PER_SYLLABLE,
            )
            assertFalse("变体不得等于原音节：$syllable", variants.contains(syllable))
        }
    }

    @Test
    fun `整串只替换一个音节且不含原键`() {
        val keys = FuzzyPinyin.keyVariants(listOf("zang", "zang"), FuzzyPinyin.Z_ZH, isLegal)
        // 两个音节各自替换一次，而不是同时替换（后者会随音节数指数增长）
        assertEquals(listOf("zhangzang", "zangzhang"), keys)
        assertFalse("原键不得出现在变体列表里", keys.contains("zangzang"))
    }

    @Test
    fun `整串变体键数量有上限`() {
        val syllables = listOf("zhang", "chang", "shang", "na", "la", "fan", "han", "gen", "ken")
        val mask = FuzzyPinyin.Z_ZH or FuzzyPinyin.C_CH or FuzzyPinyin.S_SH or
            FuzzyPinyin.N_L or FuzzyPinyin.F_H or FuzzyPinyin.K_G
        val keys = FuzzyPinyin.keyVariants(syllables, mask) { true }
        assertEquals(FuzzyPinyin.MAX_KEY_VARIANTS, keys.size)
        assertEquals("去重后不应有重复键：$keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `派生顺序与组声明顺序一致且可重复`() {
        val mask = FuzzyPinyin.Z_ZH or FuzzyPinyin.AN_ANG
        val first = FuzzyPinyin.variantsOf("zhang", mask, isLegal)
        assertEquals(listOf("zang", "zhan"), first)
        assertEquals(first, FuzzyPinyin.variantsOf("zhang", mask, isLegal))
    }

    @Test
    fun `掩码归一丢弃未定义的位`() {
        assertEquals(FuzzyPinyin.MASK_ALL, FuzzyPinyin.clampMask(-1))
        assertEquals(FuzzyPinyin.MASK_ALL, FuzzyPinyin.clampMask(FuzzyPinyin.MASK_ALL))
        assertEquals(FuzzyPinyin.Z_ZH, FuzzyPinyin.clampMask(FuzzyPinyin.Z_ZH or (1 shl 20)))
        assertEquals(FuzzyPinyin.NONE, FuzzyPinyin.clampMask(FuzzyPinyin.NONE))
    }

    @Test
    fun `组位互不重复且 MASK_ALL 覆盖全部组`() {
        val bits = FuzzyPinyin.GROUPS.map { it.bit }
        assertEquals("组位有重复：$bits", bits.size, bits.toSet().size)
        assertEquals("MASK_ALL 未覆盖全部组", FuzzyPinyin.MASK_ALL, bits.reduce { a, b -> a or b })
        assertEquals("每组都必须能在 MASK_ALL 里找到", bits.size, bits.count { FuzzyPinyin.MASK_ALL and it != 0 })
        assertTrue("标签不能为空（设置页直接显示）", FuzzyPinyin.GROUPS.all { it.label.isNotBlank() })
    }

    @Test
    fun `声母与韵尾同时命中时恰好三个变体_上限必须容得下`() {
        // r 同时命中 r⇄l 与 r⇄y，rang 再命中 an⇄ang：派生数量的理论最大值就是 3，
        // 上限若被调小（或 break 提前返回）会把最后一个变体静默吃掉
        assertEquals(
            listOf("lang", "yang", "ran"),
            FuzzyPinyin.variantsOf("rang", FuzzyPinyin.MASK_ALL, isLegal),
        )
        assertEquals(
            FuzzyPinyin.MAX_VARIANTS_PER_SYLLABLE,
            FuzzyPinyin.variantsOf("rang", FuzzyPinyin.MASK_ALL, isLegal).size,
        )
    }
}
