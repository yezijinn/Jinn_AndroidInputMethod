package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ── 真实音节表（`assets/pinyin_syllables.txt.xz`，421 个）上的穷举守卫 ──────────────
    //
    // 上面几条用的是手挑的 50 个音节子集：跑得动，但覆盖不到表外音节
    // （`lan` 恰好是变体数顶到上限的那个，它就不在子集里）。下面这批直接吃打包进 APK 的那份表，
    // 把「规则对整张表都成立」钉死 —— 规则本身没有「只在部分音节上正确」的余地。

    private val realSyllables: Set<String> by lazy { readSyllables() }

    private val realIsLegal = { s: String -> realSyllables.contains(s) }

    private fun readSyllables(): Set<String> {
        val candidates = listOf(
            "src/main/assets/pinyin_syllables.txt",
            "app/src/main/assets/pinyin_syllables.txt",
        )
        for (path in candidates) {
            val plain = File(path)
            if (plain.isFile) {
                return plain.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            // 文本资产自 2026-09-26 起以 .xz 存进 APK（体积余量），测试按短名取、自动解压
            val xz = File("$path.xz")
            if (xz.isFile) {
                val text = org.tukaani.xz.XZInputStream(xz.inputStream())
                    .use { String(it.readBytes(), Charsets.UTF_8) }
                return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        }
        throw AssertionError("未找到音节表，尝试过: $candidates")
    }

    /** 声母表（长声母在前）：音节剥掉它，剩下的就是韵母 */
    private val INITIALS = listOf(
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w",
    )

    private fun finalOf(syllable: String): String =
        INITIALS.firstOrNull { syllable.startsWith(it) && syllable.length > it.length }
            ?.let { syllable.substring(it.length) }
            ?: syllable

    /** [key] 由 [syllables] 里第几个音节的哪个合法变体得来（可能为空：不是「只换一个音节」得来的） */
    private fun replacementsOf(key: String, syllables: List<String>, mask: Int): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (i in syllables.indices) {
            for (variant in FuzzyPinyin.variantsOf(syllables[i], mask, realIsLegal)) {
                val built = buildString {
                    for (j in syllables.indices) append(if (j == i) variant else syllables[j])
                }
                if (built == key) out.add(i to variant)
            }
        }
        return out
    }

    @Test
    fun `真实音节表_韵尾派生只动鼻音尾巴_liang 的 i 必须留着`() {
        // 「前后鼻音」替换的是**韵母**的鼻音尾巴：`liang` = 声母 l + 韵母 iang，换掉的是 iang 的 -ng，
        // 得 `lian`（l + ian），韵腹 i 必须保留。这条同时钉住「被替换的尾串不越过声母边界」
        // 与「派生的韵母对只能是标准的前后鼻音对」——多出任何一对（例如 iang→an 这种丢了韵腹的）
        // 都是把声母尾巴当成了韵尾。
        val pairs = sortedSetOf<String>()
        for (syllable in realSyllables) {
            val fin = finalOf(syllable)
            for (group in FuzzyPinyin.GROUPS) {
                if (group.kind != FuzzyPinyin.Kind.FINAL) continue
                for (variant in FuzzyPinyin.variantsOf(syllable, group.bit, realIsLegal)) {
                    val tail = listOf(group.a, group.b)
                        .sortedByDescending { it.length }
                        .firstOrNull { syllable.endsWith(it) }
                        ?: continue
                    assertTrue(
                        "「$syllable」的尾串「$tail」越过了声母边界（韵母 $fin）",
                        tail.length <= fin.length,
                    )
                    pairs.add("$fin→${finalOf(variant)}")
                }
            }
        }
        assertEquals(
            "派生的韵母对超出前后鼻音的五对（多出来的都是丢了韵腹的错误派生）",
            sortedSetOf(
                "an→ang", "ang→an", "en→eng", "eng→en", "in→ing", "ing→in",
                "ian→iang", "iang→ian", "uan→uang", "uang→uan",
            ),
            pairs,
        )

        assertEquals(listOf("lian"), FuzzyPinyin.variantsOf("liang", FuzzyPinyin.AN_ANG, realIsLegal))
        assertEquals(listOf("niang", "lian"), FuzzyPinyin.variantsOf("liang", FuzzyPinyin.MASK_ALL, realIsLegal))
        assertEquals(listOf("liang"), FuzzyPinyin.variantsOf("lian", FuzzyPinyin.AN_ANG, realIsLegal))
        // 丢了韵腹的形态（lang）绝不能出现
        assertFalse(FuzzyPinyin.variantsOf("liang", FuzzyPinyin.MASK_ALL, realIsLegal).contains("lang"))
        // 复韵尾同类：uang→uan / uan→uang
        assertEquals(listOf("huan"), FuzzyPinyin.variantsOf("huang", FuzzyPinyin.AN_ANG, realIsLegal))
        assertEquals(listOf("huang"), FuzzyPinyin.variantsOf("huan", FuzzyPinyin.AN_ANG, realIsLegal))
    }

    @Test
    fun `真实音节表_单音节上限从不静默丢掉命中组`() {
        // 逐组单独派生再合并 = 「不设上限时的全量结果」；它必须与全开掩码的结果逐项相同，
        // 否则就是 MAX_VARIANTS_PER_SYLLABLE 把用户勾了的组吃掉了（静默失效，没有报错）。
        val saturated = sortedSetOf<String>()
        var max = 0
        for (syllable in realSyllables) {
            val all = LinkedHashSet<String>()
            for (group in FuzzyPinyin.GROUPS) {
                all.addAll(FuzzyPinyin.variantsOf(syllable, group.bit, realIsLegal))
            }
            val got = FuzzyPinyin.variantsOf(syllable, FuzzyPinyin.MASK_ALL, realIsLegal)
            assertEquals("「$syllable」全量 ${all.size} 个，截断后只剩 ${got.size} 个", all.toList(), got)
            assertFalse("变体不得等于原音节：$syllable", got.contains(syllable))
            assertTrue("「$syllable」的变体不合法", got.all(realIsLegal))
            max = maxOf(max, got.size)
            if (got.size == FuzzyPinyin.MAX_VARIANTS_PER_SYLLABLE) saturated.add(syllable)
        }
        assertEquals("单音节变体数已越过上限", FuzzyPinyin.MAX_VARIANTS_PER_SYLLABLE, max)
        assertEquals(
            "顶到上限的音节变了 ⇒ 上限已到临界，再加一组就会静默丢变体",
            setOf("lan", "lang", "ran", "rang"),
            saturated,
        )
    }

    @Test
    fun `真实音节表_等长声母组互换必须对称`() {
        // 长短相等时 `derive` 走 long = a 分支，只有「互换」一个方向 —— 必须是对合变换，
        // 否则某一侧会派生不出来（r/l、r/y、n/l、f/h、k/g 全是等长组）
        val equal = FuzzyPinyin.GROUPS.filter {
            it.kind == FuzzyPinyin.Kind.INITIAL && it.a.length == it.b.length
        }
        assertTrue("等长声母组一个都没取到", equal.isNotEmpty())
        var checked = 0
        for (group in equal) {
            for (syllable in realSyllables) {
                val forward = FuzzyPinyin.variantsOf(syllable, group.bit, realIsLegal)
                for (variant in forward) {
                    assertEquals(
                        "${group.a}⇄${group.b} 不是对称变换：$syllable→$variant 派不回去",
                        listOf(syllable),
                        FuzzyPinyin.variantsOf(variant, group.bit, realIsLegal),
                    )
                    checked++
                }
            }
        }
        assertTrue("等长声母组一对都没验证到", checked > 50)
    }

    @Test
    fun `真实音节表_整串变体只换一个音节且换上去的合法`() {
        val inputs = listOf(
            listOf("lan", "lang"),
            listOf("zang", "cang", "sang"),
            listOf("zhuan", "chuang"),
        )
        for (syllables in inputs) {
            assertTrue("用例前提：音节都得在表里 $syllables", syllables.all(realIsLegal))
            val keys = FuzzyPinyin.keyVariants(syllables, FuzzyPinyin.MASK_ALL, realIsLegal)
            assertTrue("$syllables 一个变体都没派生", keys.isNotEmpty())
            for (key in keys) {
                val hits = replacementsOf(key, syllables, FuzzyPinyin.MASK_ALL)
                assertEquals("「$key」不是「只换一个音节」得来的：$hits", 1, hits.size)
                assertTrue("「$key」换上去的音节不合法", realIsLegal(hits[0].second))
            }
            assertFalse("原键不得出现在变体里：$keys", keys.contains(syllables.joinToString("")))
        }
    }

    @Test
    fun `只开一组时不得借用其它组的派生`() {
        assertEquals(listOf("zang"), FuzzyPinyin.variantsOf("zhang", FuzzyPinyin.Z_ZH, realIsLegal))
        assertEquals(listOf("nang"), FuzzyPinyin.variantsOf("lang", FuzzyPinyin.N_L, realIsLegal))
        assertEquals(listOf("lan"), FuzzyPinyin.variantsOf("lang", FuzzyPinyin.AN_ANG, realIsLegal))
        assertEquals(listOf("zuan"), FuzzyPinyin.variantsOf("zhuan", FuzzyPinyin.Z_ZH, realIsLegal))
        assertEquals(listOf("zhuang"), FuzzyPinyin.variantsOf("zhuan", FuzzyPinyin.AN_ANG, realIsLegal))
        for (group in FuzzyPinyin.GROUPS) {
            assertEquals("组位必须是单个二进制位：${group.bit}", 1, Integer.bitCount(group.bit))
        }
    }

    @Test
    fun `空音节与空列表不派生也不崩`() {
        // 空串派生为空本来就是 derive 的自然结果（startsWith / endsWith 对空串恒 false），
        // 不是 `variantsOf` 里那条 `isEmpty` 短路挡出来的 —— 把短路删掉照样恒空（第 61 轮变异实测）。
        // 这里钉的是**对外契约**：调用方（残码 / 空音节）不必自己先过滤一轮。
        assertTrue(FuzzyPinyin.variantsOf("", FuzzyPinyin.MASK_ALL, realIsLegal).isEmpty())
        assertTrue(FuzzyPinyin.keyVariants(emptyList(), FuzzyPinyin.MASK_ALL, realIsLegal).isEmpty())
        assertTrue(FuzzyPinyin.keyVariants(listOf(""), FuzzyPinyin.MASK_ALL, realIsLegal).isEmpty())
        // 末尾未完成音节（残码）会让列表里出现空串：其余音节照常派生
        assertEquals(listOf("zhang"), FuzzyPinyin.keyVariants(listOf("", "zang"), FuzzyPinyin.Z_ZH, realIsLegal))
    }

    @Test
    fun `整串上限八条时名额只够覆盖靠前的音节`() {
        // 已知取舍，不是缺陷：`keyVariants` 按「音节下标 → 变体顺序」产出，8 条名额被前几个音节吃光，
        // 第 4 个音节（rang）一条都排不进来。要改成按变体轮转分配会动到候选次序 ⇒
        // 必须有意识地连同本用例一起改，别让顺序悄悄变。
        val syllables = listOf("lan", "lang", "ran", "rang")
        assertTrue("用例前提：这 4 个音节都顶到单音节上限", syllables.all {
            FuzzyPinyin.variantsOf(it, FuzzyPinyin.MASK_ALL, realIsLegal).size ==
                FuzzyPinyin.MAX_VARIANTS_PER_SYLLABLE
        })
        val keys = FuzzyPinyin.keyVariants(syllables, FuzzyPinyin.MASK_ALL, realIsLegal)
        assertEquals("12 个原始变体应被截到上限", FuzzyPinyin.MAX_KEY_VARIANTS, keys.size)
        val covered = keys.flatMap { replacementsOf(it, syllables, FuzzyPinyin.MASK_ALL) }
            .map { it.first }.toSet()
        assertEquals("名额分配变了（末位音节居然被覆盖）：$keys", setOf(0, 1, 2), covered)
    }

    @Test
    fun `真实音节表_派生出的非法音节一律丢弃`() {
        // 表里没有 len / din / tin / yuang / zuang：这些方向必须整个消失
        assertTrue(FuzzyPinyin.variantsOf("leng", FuzzyPinyin.EN_ENG, realIsLegal).isEmpty())
        assertTrue(FuzzyPinyin.variantsOf("ding", FuzzyPinyin.IN_ING, realIsLegal).isEmpty())
        assertTrue(FuzzyPinyin.variantsOf("ting", FuzzyPinyin.IN_ING, realIsLegal).isEmpty())
        assertTrue(FuzzyPinyin.variantsOf("yuan", FuzzyPinyin.AN_ANG, realIsLegal).isEmpty())
        assertEquals(listOf("zuan", "zhuang"), FuzzyPinyin.variantsOf("zhuan", FuzzyPinyin.MASK_ALL, realIsLegal))
        // 正向必须保留：neng → nen、jin → jing 这些是这条规则存在的理由
        assertEquals(listOf("nen"), FuzzyPinyin.variantsOf("neng", FuzzyPinyin.EN_ENG, realIsLegal))
        assertEquals(listOf("jing"), FuzzyPinyin.variantsOf("jin", FuzzyPinyin.IN_ING, realIsLegal))
    }
}
