package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只使用繁体字」候选替换测试（纯 JVM，用 [PinyinEngine.loadFromTexts] 注入）。
 *
 * 开关只改**显示与上屏文本**（[PinyinEngine.setTraditional] → 候选出口 `displayTake`），
 * 拼音键、消费区间、词频存储都不跟着换口径 —— 这三条各自都有踩过的坑：
 *
 *  - **候选出口有五处**（词 / 单字 / 模糊变体 / 补全 / 预测），漏一处就是简体混排；
 *  - **消费区间**要按候选折回简体再查：`wordToPinyin` / `candidatePinyin` / 单字表都按简体建，
 *    拿繁体的「愛好」去查会落空、退化成兜底「消费全部」，把 `aihaoma` 的残码 `ma` 一起清掉
 *    （简体模式下同样的操作却能保留残码）；
 *  - **用户词频恒按简体存**：存繁体会让「切回简体后同一个词的学习结果失效」，反之亦然。
 */
class TraditionalModeTest {

    /** 「号 / 毫」两组同音字：前者有繁体形（號），后者同形，用来验证只换命中项 */
    private val chars = "ai\t爱,艾,碍\nhao\t号,毫\nma\t吗,马\n"
    private val phrases = "aihao\t爱好\nhaoma\t好吗\naihaoma\t爱好吗\n"
    private val syllables = "ai\nhao\nma\n"
    private val map = "爱\t愛\n碍\t礙\n号\t號\n吗\t嗎\n马\t馬\n"

    private fun load(traditional: Boolean = false) {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts(chars, phrases, syllables)
        PinyinEngine.setSimpTradText(map)
        PinyinEngine.setTraditional(traditional)
    }

    /** 关着时必须逐候选与历史一致（列表相同、顺序相同） */
    @Test
    fun 关闭时候选保持简体() {
        load(traditional = false)
        val r = PinyinEngine.query("aihao").candidates
        assertTrue("词候选应是简体「爱好」: $r", r.contains("爱好"))
        assertFalse("不得出现繁体「愛好」: $r", r.contains("愛好"))
        val single = PinyinEngine.query("ai").candidates
        assertTrue("单字候选应是简体「爱」: $single", single.contains("爱"))
        assertFalse("不得出现繁体「愛」: $single", single.contains("愛"))
    }

    /** 开启后词与单字都换成繁体 */
    @Test
    fun 开启后词与单字候选都变繁体() {
        load(traditional = true)
        val r = PinyinEngine.query("aihao").candidates
        assertTrue("词候选应变为「愛好」: $r", r.contains("愛好"))
        assertFalse("不应再出现简体「爱好」: $r", r.contains("爱好"))
        val single = PinyinEngine.query("ai").candidates
        assertTrue("单字「爱」应变「愛」: $single", single.contains("愛"))
        assertTrue("单字「碍」应变「礙」: $single", single.contains("礙"))
    }

    /**
     * 只换**命中简繁表**的字：`hao` 组里「号」有繁体形（號）而「毫」同形，
     * 两者必须在同一次查询里一个变、一个不变。
     *
     * ⚠ 原先这条写成 `r.contains("毫") || !r.contains("毫")`（**恒真**，等于没断言），见 `BUG.md` L-61。
     * 此处用 `hao` 走**单音节**路径；`query("aihao")` 那条**本来也可以**写强断言 ——
     * 它会给出各音节的单字（实测 `[爱好, 爱, 艾, 碍, 号, 毫]`），
     * 两条路径等价，都是「有映射的变、无映射的不变」。
     */
    @Test
    fun 无繁体形的字原样保留() {
        load(traditional = true)
        val one = PinyinEngine.query("hao").candidates
        assertTrue("有繁体形的「号」应变为「號」: $one", one.contains("號"))
        assertFalse("不应再出现简体「号」: $one", one.contains("号"))
        assertTrue("无繁体形的「毫」应原样保留: $one", one.contains("毫"))
    }

    /** 开关是即时生效的：同一进程内来回切换，两侧结果各自稳定 */
    @Test
    fun 开关即时切换() {
        load(traditional = false)
        assertTrue(PinyinEngine.query("aihao").candidates.contains("爱好"))
        PinyinEngine.setTraditional(true)
        assertTrue(PinyinEngine.query("aihao").candidates.contains("愛好"))
        PinyinEngine.setTraditional(false)
        assertTrue("切回简体后必须还原: ${PinyinEngine.query("aihao").candidates}",
            PinyinEngine.query("aihao").candidates.contains("爱好"))
    }

    /**
     * 消费区间：繁体候选必须与简体候选算出同一区间（都只消费 `aihao`、保留残码 `ma`）。
     *
     * 判据错成「拿繁体去查简体表」时这里会变成「消费全部 7 个字符」—— 用户在上屏「愛好」后
     * 发现已输入的 `ma` 被一起清掉，而切回简体模式同样的操作却正常。
     */
    @Test
    fun 消费区间两种模式下一致且保留残码() {
        load(traditional = false)
        val simple = PinyinEngine.consumption("aihaoma", "爱好")
        PinyinEngine.setTraditional(true)
        val trad = PinyinEngine.consumption("aihaoma", "愛好")
        assertEquals("繁体候选的消费区间必须与简体一致", simple, trad)
        assertEquals("应只消费 aihao 五个字符", 5, trad.quanpinChars)
        assertEquals("应消费两个音节", 2, trad.syllables)
    }

    /** 单字候选同理：`aihaoma` 选「愛」只消费 `ai`，残码 `haoma` 保留 */
    @Test
    fun 单字候选在繁体模式下也只消费本音节() {
        load(traditional = true)
        val c = PinyinEngine.consumption("aihaoma", "愛")
        assertEquals("应只消费 ai 两个字符", 2, c.quanpinChars)
        assertEquals(1, c.syllables)
    }

    /**
     * 用户词频恒按简体存：繁体模式下选「愛好」，权重落在简体键「爱好」上。
     *
     * 存繁体会让两种模式各学一份、互不可见 —— 用户切回简体后「愛好」的权重查不到，
     * 刚养成的习惯立刻失效。
     */
    @Test
    fun 用户词频恒按简体存储() {
        load(traditional = true)
        PinyinEngine.rememberChoice("愛好")
        assertNotNull("简体键应有学习结果", UserFrequency.weightForTest("爱好"))
        assertNull("繁体形式不得另存一份", UserFrequency.weightForTest("愛好"))
    }

    /** 排序：`rank(words, keyOf)` 用简体键取权重，但返回的仍是传入的繁体候选 */
    @Test
    fun 排序按简体权重且不改候选内容() {
        UserFrequency.resetForTest()
        UserFrequency.loadForTest(enabled = true)
        UserFrequency.putForTest("爱好", 1.0, today())
        val sorted = UserFrequency.rank(arrayOf("艾号", "愛好", "爱你")) { PinyinEngine.toSimplified(it) }
        assertEquals("学过的「愛好」应排到最前", "愛好", sorted.first())
        assertEquals("候选内容不得被改写", 3, sorted.size)
    }

    /**
     * 查询路径的排序恒走 `keyOf` 重载（见 `query` 内的注释），两侧都要成立：
     * 关繁体时是恒等映射、行为与历史一致；开繁体时用简体键仍能命中同一份学习结果。
     */
    @Test
    fun 关闭时学习排序照常生效() {
        load(traditional = false)
        UserFrequency.putForTest("爱好", 5.0, today())
        assertEquals("学过的「爱好」应排到最前",
            "爱好", PinyinEngine.query("aihao").candidates.first())
    }

    @Test
    fun 开启时简体学习结果仍能提升繁体候选() {
        load(traditional = true)
        UserFrequency.putForTest("爱好", 5.0, today())
        assertEquals("繁体「愛好」应排到最前",
            "愛好", PinyinEngine.query("aihao").candidates.first())
    }

    /**
     * 词级消歧优先于逐字映射：`头发` 在词表里给「頭髮」，而逐字映射会得「頭發」（發/髮 同源不同义）；
     * 不在词表里的 `发财` 照常走逐字兜底 →「發財」。
     *
     * 词表只收「逐字映射会出错」的词条，所以「未命中」是常态路径，两条都要钉住。
     */
    @Test
    fun 词级消歧优先于逐字映射() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts(
            "tou\t头\nfa\t发\n",
            "toufa\t头发\nfacai\t发财\n",
            "tou\nfa\ncai\n",
        )
        PinyinEngine.setSimpTradText("头\t頭\n发\t發\n财\t財\n")
        PinyinEngine.setSimpTradWordsText("头发\t頭髮\n")
        PinyinEngine.setTraditional(true)

        val c = PinyinEngine.query("toufa").candidates
        assertTrue("「头发」应命中词表得「頭髮」: $c", c.contains("頭髮"))
        assertFalse("不得出现逐字错配的「頭發」: $c", c.contains("頭發"))

        val c2 = PinyinEngine.query("facai").candidates
        assertTrue("未进词表的「发财」应逐字兜底得「發財」: $c2", c2.contains("發財"))
    }

    /**
     * 词级消歧的**反向**转换：繁体候选「頭髮」折回简体必须是「头发」。
     *
     * 字级反查只会得「头髮」——「髮」不在字级映射的繁体侧（字级里是 发→發）。折不回简体时，
     * 用户词频会存下一个谁也用不到的键：简体模式下养成的习惯在繁体模式里立刻失效（反之亦然）；
     * 消费区间与预测同样按「候选→简体」查表，会一起退化成兜底「消费全部」。
     */
    @Test
    fun 词级候选折回简体必须命中词表() {
        loadTouFa()
        PinyinEngine.setTraditional(true)

        PinyinEngine.rememberChoice("頭髮")
        assertNotNull("应存到简体键「头发」", UserFrequency.weightForTest("头发"))
        assertNull("不得存成折半的「头髮」", UserFrequency.weightForTest("头髮"))

        val c = PinyinEngine.consumption("toufama", "頭髮")
        assertEquals("应只消费 toufa 五个字符（tou+fa）", 5, c.quanpinChars)
        assertEquals("应消费两个音节", 2, c.syllables)
    }

    /**
     * 繁→简单字表补上**字级反推**的缺口。
     *
     * `setSimpTradText` 只能从字级正向表（1,948 对）反推出反查表，而词级消歧表引入的繁体字
     * 大多不在其中（髮 / 乾 / 淨 / 鬚…）。整词命中词级反查时看不出问题，**逐字**路径却会折成
     * 半简半繁的「髮丝 / 擦干淨」—— 词频键、消费区间、预测一起跑偏（且简体模式下毫无征兆）。
     * 补上 TSCharacters 生成的简化表后才折得回「发丝 / 擦干净」。
     */
    @Test
    fun 繁简表补齐字级反推的缺口() {
        loadTouFa()

        assertEquals("词级反查必须能折回「头发」", "头发", PinyinEngine.toSimplified("頭髮"))
        assertEquals("字级反推表里没有「髮」，逐字路径折不回", "髮丝", PinyinEngine.toSimplified("髮丝"))

        PinyinEngine.setSimplifyText("髮\t发\n乾\t干\n淨\t净\n")

        assertEquals("补表后「髮丝」应折回「发丝」", "发丝", PinyinEngine.toSimplified("髮丝"))
        assertEquals("「擦乾淨」应折回「擦干净」", "擦干净", PinyinEngine.toSimplified("擦乾淨"))
    }

    /** 注入「头发」所需的最小词库（含 `toufama` 用的残码音节） */
    private fun loadTouFa() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts(
            "tou\t头\nfa\t发\nma\t吗\n",
            "toufa\t头发\n",
            "tou\nfa\nma\n",
        )
        PinyinEngine.setSimpTradText("头\t頭\n发\t發\n吗\t嗎\n")
        PinyinEngine.setSimpTradWordsText("头发\t頭髮\n")
    }

    /**
     * **基础索引词**（`PhraseIndex`，没有 `wordToPinyin` 兜底）在繁体模式下的消费区间。
     *
     * 「候选→键」表 `candidatePinyin` 登记的是**显示词**（繁体「頭髮」），而词库侧一律简体。
     * 只按折返后的简体键查表时，基础索引词两条路径都落空 → 退化成兜底「消费全部」：
     * `toufama` 选「頭髮」会把残码 `ma` 一起清掉（运行时并入的词有 `wordToPinyin` 兜底，测不出来）。
     */
    @Test
    fun 基础索引词在繁体模式下同样只消费本词() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromIndexBytes(
            PhraseIndex.build("toufa\t头发\n".lineSequence(), 1L),
            "tou\t头\nfa\t发\nma\t吗\n",
            "tou\nfa\nma\n",
            null,
        )
        PinyinEngine.setSimpTradText("头\t頭\n发\t發\n吗\t嗎\n")
        PinyinEngine.setSimpTradWordsText("头发\t頭髮\n")
        PinyinEngine.setTraditional(true)

        val c = PinyinEngine.query("toufa").candidates
        assertTrue("基础索引词应出繁体「頭髮」: $c", c.contains("頭髮"))

        val cons = PinyinEngine.consumption("toufama", "頭髮")
        assertEquals("应只消费 toufa 五个字符（基础索引词无 wordToPinyin 兜底）", 5, cons.quanpinChars)
        assertEquals(2, cons.syllables)
    }

    /** 补全出口：部分输入（`touf`）带出的补全候选同样要转繁体 */
    @Test
    fun 补全候选同样转繁体() {
        loadTouFa()
        PinyinEngine.setTraditional(true)
        val c = PinyinEngine.query("touf").candidates
        assertTrue("补全候选应出「頭髮」: $c", c.contains("頭髮"))
        assertFalse("不得残留简体「头发」: $c", c.contains("头发"))
    }

    /**
     * 补全候选出口的「候选→键」登记必须发生在**显示转换之后**（2026-09-27 复现，BUG.md L-42）。
     *
     * 五处候选出口里只有补全那一处在 `displayTake` **之前**登记（登记的是简体词），而 `predict` /
     * `consumption` 都按**显示词**查 `candidateTruePinyin` / `candidatePinyin`，落空后才折回简体查
     * `wordToPinyin` —— 而**基础索引的词不在那张表里**（它只装运行时并入的词），三级查表全空：
     * 选完补全候选后不再预测。简体模式同一操作正常，所以这条只在繁体下可见。
     *
     * 用 `loadFromIndexBytes` 而不是 `loadFromTexts`：运行时并入的词有 `wordToPinyin` 兜底，
     * 会把这条缺陷**掩盖**掉（与「基础索引词在繁体模式下同样只消费本词」同一坑）。
     */
    @Test
    fun 繁体模式下补全候选选完后仍能预测() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromIndexBytes(
            PhraseIndex.build("toufa\t头发\ntoufachang\t头发长\n".lineSequence(), 1L),
            "tou\t头\nfa\t发\nchang\t长\n",
            "tou\nfa\nchang\n",
            null,
        )
        PinyinEngine.setSimpTradText("头\t頭\n发\t發\n长\t長\n")
        PinyinEngine.setSimpTradWordsText("头发\t頭髮\n")

        val simp = PinyinEngine.query("touf").candidates
        assertTrue("简体下应有补全候选「头发」: $simp", simp.contains("头发"))
        assertTrue(
            "简体下选完补全候选应预测出后缀「长」: ${PinyinEngine.predict("头发")}",
            PinyinEngine.predict("头发").contains("长"),
        )

        PinyinEngine.setTraditional(true)
        val trad = PinyinEngine.query("touf").candidates
        assertTrue("繁体下补全候选应为「頭髮」: $trad", trad.contains("頭髮"))
        assertTrue(
            "繁体下同一操作仍应预测出后缀「長」（登记简体词会让两级查表全落空）: " +
                "${PinyinEngine.predict("頭髮")}",
            PinyinEngine.predict("頭髮").contains("長"),
        )
    }

    /**
     * 预测出口：上屏「頭髮」后的延续候选同样要转繁体。
     *
     * 注意预测返回的是**后缀**（接下来要输入的那部分，来自「头发长」去掉「头发」），
     * 不是完整词 —— 断言按后缀写。
     */
    @Test
    fun 预测候选同样转繁体() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts(
            "tou\t头\nfa\t发\nchang\t长\n",
            "toufa\t头发\ntoufachang\t头发长\n",
            "tou\nfa\nchang\n",
        )
        PinyinEngine.setSimpTradText("头\t頭\n发\t發\n长\t長\n")
        PinyinEngine.setSimpTradWordsText("头发\t頭髮\n")
        PinyinEngine.setTraditional(true)

        val p = PinyinEngine.predict("頭髮")
        assertTrue("延续后缀「长」应转成「長」: $p", p.contains("長"))
        assertFalse("不得残留简体「长」: $p", p.contains("长"))
    }

    /** 模糊变体出口：变体命中的候选同样要转繁体（变体只在精确结果之后追加） */
    @Test
    fun 模糊变体候选同样转繁体() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts("zang\t脏\nzhang\t张\n", "", "zang\nzhang\n")
        PinyinEngine.setSimpTradText("张\t張\n脏\t臟\n")
        PinyinEngine.setFuzzyMask(FuzzyPinyin.Z_ZH)
        PinyinEngine.setTraditional(true)

        val c = PinyinEngine.query("zang").candidates
        assertTrue("变体单字「张」应转成「張」: $c", c.contains("張"))
    }

    /** 与 UserFrequency 内部一致的天计数：测试数据用很旧的天会被按天衰减过滤掉 */
    private fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()
}
