package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 简繁资产与**加载端口径**的一致性守卫（2026-09-27）。
 *
 * 三张简繁表都是生成物，判据必须与 `PinyinEngine` 的加载代码逐条对齐，否则会出现
 * 「生成端收下了、运行期静默丢弃」的隐性错配（`BUG.md` L-52 / L-58）：
 *
 *  - `simp_trad.txt` / `simplify.txt` 是**字级**表，加载端判据是 `String.length == 1`（UTF-16 语义）——
 *    非 BMP 字符是代理对（长度 2）⇒ 生成进去也永远用不到，只是白占资产体积；
 *  - `simp_trad_words.txt` 是**词级**表，OpenCC 右侧允许多候选（空格分隔、按优先序）：
 *    必须**先取首候选再判等长**，否则那一整条被丢 —— 繁体模式下这些词退化成逐字而**转出别字**
 *    （反复→「反復」应作「反覆」）。
 */
class SimpTradAssetTest {

    private fun assetText(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (!f.isFile) continue
            // ⚠ `.xz` 必须解压后再当文本读：直接 readText() 拿到的是压缩字节（守卫会在垃圾数据上
            // 「全都不含」地通过/失败 —— 上一版就这么写的，跑门禁才发现）
            return if (name.endsWith(".xz")) {
                XZInputStream(f.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
            } else {
                f.readText()
            }
        }
        throw AssertionError("未找到 asset $name")
    }

    /** `简<TAB>繁` 词条（跳过注释行与格式不合的行） */
    private fun pairs(name: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in assetText(name).lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.count { it == '\t' } != 1) continue
            val (a, b) = t.split("\t")
            out[a] = b
        }
        return out
    }

    /**
     * 词级消歧表必须收下「多候选」条目里逐字映射会出错的那批。
     *
     * 判据取自 OpenCC `STPhrases`（右侧形如 `不準 不准`）：这些词的**首候选**既不等于原词、
     * 也不等于逐字映射结果；漏收就会走逐字而转错。
     */
    @Test
    fun 词级消歧表必须收下多候选首项() {
        val words = pairs("simp_trad_words.txt.xz")
        val golden = mapOf(
            "反复" to "反覆", "发卡" to "髮卡", "白干" to "白乾", "回复" to "回覆", "干点" to "乾點",
            "借词" to "藉詞", "冲垮" to "沖垮", "冷面" to "冷麪", "谷风" to "穀風", "金杯" to "金盃",
        )
        for ((simp, trad) in golden) {
            assertEquals("词级表应含「$simp → $trad」（右侧多候选取首项）", trad, words[simp])
        }
    }

    /** 端到端：用**真实资产**装载后，「反复」必须整体作「反覆」（逐字路径只会给「反復」） */
    @Test
    fun 真实资产下反复应作反覆() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts("fa\t发\nfu\t复\n", "fafu\t反复\n", "fa\nfu\n")
        PinyinEngine.setSimpTradText(assetText("simp_trad.txt.xz"))
        PinyinEngine.setSimplifyText(assetText("simplify.txt.xz"))
        PinyinEngine.setSimpTradWordsText(assetText("simp_trad_words.txt.xz"))
        PinyinEngine.setTraditional(true)

        val c = PinyinEngine.query("fafu").candidates
        assertTrue("繁体下「反复」应作「反覆」: $c", c.contains("反覆"))
        assertFalse("不得出现逐字错配的「反復」: $c", c.contains("反復"))
    }

    /**
     * 字级表不得含非 BMP 项 —— 加载端 `length == 1` 是 UTF-16 语义，代理对必被丢弃。
     *
     * 生成端（`tools/dict_builder/build_dicts.py` 的 `_bmp()`）与这里必须同口径：
     * 一边收、一边丢就是「白占体积 + 口径不一致」。
     */
    @Test
    fun 字级表不得含非BMP项() {
        for (name in listOf("simp_trad.txt.xz", "simplify.txt.xz")) {
            val text = assetText(name)
            val surrogates = text.count { Character.isSurrogate(it) }
            assertEquals("$name 含非 BMP 字符（加载端会静默丢弃，生成端必须按 _bmp 过滤）", 0, surrogates)
        }
    }

    /**
     * 字级表必须是 **OpenCC STCharacters 口径**，而不是只剩人工那 1,948 对（`BUG.md` L-37）。
     *
     * 人工表 `单字/简繁对照.txt` 与 STCharacters 首候选零冲突，但它只覆盖 2,714 个里的一部分：
     * 少了的那批在繁体模式下**原地不动**（于→於 / 征→徵 / 托→託 / 佥→僉 / 䲢→鰧），
     * 由人工表独占**必然**漏。这条守卫同时钉住「旧对零丢失」与「新对已补齐」两侧。
     */
    @Test
    fun 字级表须覆盖OpenCC默认映射() {
        val chars = pairs("simp_trad.txt.xz")
        val golden = mapOf(
            // 人工表原有（不得因换口径而丢）
            "里" to "裏", "干" to "幹", "台" to "臺", "发" to "發", "头" to "頭", "准" to "準",
            // OpenCC 补齐的（档 1 的 4 个 + 档 2 / 档 3 的扩展 A 字）
            "于" to "於", "征" to "徵", "托" to "託", "咤" to "吒",
            "佥" to "僉", "䲢" to "鰧", "㧟" to "擓",
        )
        for ((simp, trad) in golden) {
            assertEquals("字级表应含「$simp → $trad」（OpenCC STCharacters 首候选）", trad, chars[simp])
        }
        assertTrue("字级表条目数异常（${chars.size}）：口径退回人工表？", chars.size >= 2700)
        val selfMapped = chars.filter { (k, v) -> k == v }.keys
        assertTrue("字级表不得含自映射项: $selfMapped", selfMapped.isEmpty())
    }

    /**
     * 字级补齐后，「词级结果 == 简体原形」那 7 条（天台 / 天后 / 海里…）必须**仍进词级表**。
     *
     * 生成端曾按 `s == t` 提前跳过 —— 字级表变全后，这类词正是需要覆盖的：逐字会把「天台」
     * 转成「天臺」。这条守卫用**真实资产**跑端到端，锁住「词级优先于逐字」在补齐后依然成立。
     */
    @Test
    fun 词级结果等于简体原形的词也要覆盖字级() {
        val words = pairs("simp_trad_words.txt.xz")
        for (w in listOf("天台", "天后", "海里", "丰度", "出戏", "占城", "小丑")) {
            assertEquals("词级表应含「$w → $w」（逐字会转错）", w, words[w])
        }

        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts(
            "tian\t天\ntai\t台\nzheng\t征\nzhan\t战\n",
            "tiantai\t天台\nzhengzhan\t征战\n",
            "tian\ntai\nzheng\nzhan\n",
        )
        PinyinEngine.setSimpTradText(assetText("simp_trad.txt.xz"))
        PinyinEngine.setSimplifyText(assetText("simplify.txt.xz"))
        PinyinEngine.setSimpTradWordsText(assetText("simp_trad_words.txt.xz"))
        PinyinEngine.setTraditional(true)

        val tai = PinyinEngine.query("tiantai").candidates
        assertTrue("繁体下「天台」应整体作「天台」（词级覆盖字级）: $tai", tai.contains("天台"))
        assertFalse("不得出现逐字结果「天臺」: $tai", tai.contains("天臺"))

        val zhan = PinyinEngine.query("zhengzhan").candidates
        assertTrue("繁体下「征战」应作「征戰」: $zhan", zhan.contains("征戰"))
        assertFalse("不得出现逐字结果「徵戰」: $zhan", zhan.contains("徵戰"))
    }

    /** 端到端：字级补齐的单字（于→於）必须真的出现在候选里 —— 这才是用户能看见的那一半 */
    @Test
    fun 补齐的字级映射在候选里生效() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts("yu\t于\n", "", "yu\n")
        PinyinEngine.setSimpTradText(assetText("simp_trad.txt.xz"))
        PinyinEngine.setSimplifyText(assetText("simplify.txt.xz"))
        PinyinEngine.setSimpTradWordsText(assetText("simp_trad_words.txt.xz"))
        PinyinEngine.setTraditional(true)

        val c = PinyinEngine.query("yu").candidates
        assertTrue("繁体下单字「于」应作「於」: $c", c.contains("於"))
        assertFalse("不得再出现未转换的「于」: $c", c.contains("于"))
    }

    /**
     * 词级表要**同时**兜住正推与反查（`BUG.md` L-71 / L-93 / L-94）。
     *
     * 生成端曾只按正推判「冗余」（逐字 == 词级结果就丢）—— 对**异体字**词条会出错：
     * `万锺 → 萬鍾` 丢掉后，`toSimplified("萬鍾")` 走逐字兜底会得「万**钟**」（另一个词），
     * 繁体模式下学过的词，词频键 / 消费区间 / 预测全都落到别的词上。
     * 判据改成「两个方向都能靠字级表还原才丢」，实测为反查保留 25 条（正推方向必须留在表里）。
     * ⚠ 反查的**胜者**另由词频定（同一显示形取词频最高的词典词，见 L-93 / L-94）⇒ 折回结果不是
     * 「表里那个异体词」，而是该显示形下词典里最常用的词；条目（正推方向）仍然必须在表里。
     */
    @Test
    fun 词级表要同时兜住正推与反查() {
        val words = pairs("simp_trad_words.txt.xz")
        val golden = listOf(
            "万锺" to "萬鍾", "茶馀饭后" to "茶餘飯後", "锺馗" to "鍾馗",
            "讬了" to "託了", "游刃有馀" to "遊刃有餘", "盈馀加征" to "盈餘加徵",
        )
        for ((simp, trad) in golden) {
            assertEquals("词级表应含「$simp → $trad」（反查要靠它兜住，别按单方向判冗余）", trad, words[simp])
        }

        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts("wan\t万\n", "", "wan\n")
        PinyinEngine.setSimpTradText(assetText("simp_trad.txt.xz"))
        PinyinEngine.setSimplifyText(assetText("simplify.txt.xz"))
        PinyinEngine.setSimpTradWordsText(assetText("simp_trad_words.txt.xz"))

        assertEquals("「萬鍾」应折回「万钟」（该显示形下词典里最常用的词）",
            "万钟", PinyinEngine.toSimplified("萬鍾"))
        assertEquals("「茶餘飯後」应折回「茶余饭后」（余 是规范形）",
            "茶余饭后", PinyinEngine.toSimplified("茶餘飯後"))
        assertEquals("「萬餘」应折回「万余」（同上）",
            "万余", PinyinEngine.toSimplified("萬餘"))
        assertEquals("词级表仍优先于逐字兜底（逐字会给「盈余加征」）",
            "盈馀加征", PinyinEngine.toSimplified("盈餘加徵"))
    }

    /**
     * 往返一致性：词典里能选到的词，在两种模式下的**词频键必须相同**（`BUG.md` L-93 / L-94）。
     *
     * 视角：传统模式下候选是「转换后去重」的 —— 同一显示形下只有词频最高的那个词可见，
     * 用户选中的就是它；折回落到别的词上，繁体模式学过的词切回简体后命中不了
     * （词频键 / 消费区间 / 预测三处都不认）。金样例覆盖三类机理：
     *  - 显示形有表项、但原胜者是别的词（覆蓋 ← 复盖）：L-93；
     *  - 显示形没有表项、只能逐字兜底（乾隆 → 干隆）：L-94；
     *  - 显示形 == 本词（拚命）：L-94 的恒等条目那一类。
     * 生成侧的收口判据（补收后「可观测失败」只允许剩词频并列）写在 `build_dicts.py`，
     * 这里钉的是**用户可见的那一段**：真实资产装载后的折回结果。
     */
    @Test
    fun 往返一致_显示形的最高频词必须折回自己() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts("gai\t盖\n", "", "gai\n")
        PinyinEngine.setSimpTradText(assetText("simp_trad.txt.xz"))
        PinyinEngine.setSimplifyText(assetText("simplify.txt.xz"))
        PinyinEngine.setSimpTradWordsText(assetText("simp_trad_words.txt.xz"))

        val golden = mapOf(
            "覆蓋" to "覆盖",
            "剩餘價值" to "剩余价值",
            "全軍覆沒" to "全军覆没",
            "萬餘" to "万余",
            "乾隆" to "乾隆",
            "承乾宮" to "承乾宫",
            "拚命" to "拚命",
            "反覆" to "反复",
        )
        for ((trad, simp) in golden) {
            assertEquals("「$trad」应折回「$simp」（往返一致性：两种模式的词频键必须相同）",
                simp, PinyinEngine.toSimplified(trad))
        }

        val words = pairs("simp_trad_words.txt.xz")
        assertEquals("词级表应含「覆盖 → 覆蓋」（正推补收）", "覆蓋", words["覆盖"])
        assertEquals("词级表应含恒等条目「乾隆 → 乾隆」（反查补收）", "乾隆", words["乾隆"])
    }

    /**
     * 用**真实单字表**跑一遍 `yu`：繁体转换后不得出现重复候选。
     *
     * 字表里本来就有「于」「於」两个字（§简繁同形的字对），转换后两者都会显示成「於」——
     * 候选出口若不在转换**之后**去重，用户会看到两个一模一样的候选（`displayTake` 的契约是
     * 「转换后入列」，此例正是它的回归位）。
     */
    @Test
    fun 真实字表下转换后不得出现重复候选() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts(assetText("pinyin_chars.txt.xz"), "", "yu\n")
        PinyinEngine.setSimpTradText(assetText("simp_trad.txt.xz"))
        PinyinEngine.setSimplifyText(assetText("simplify.txt.xz"))
        PinyinEngine.setSimpTradWordsText(assetText("simp_trad_words.txt.xz"))
        PinyinEngine.setTraditional(true)

        val c = PinyinEngine.query("yu").candidates
        assertFalse("繁体下不得残留未转换的「于」", c.contains("于"))
        assertTrue("繁体下应出现「於」: ${c.take(6)}", c.contains("於"))
        assertEquals("转换后不得出现重复候选: ${c.take(8)}", c.size, c.toSet().size)
    }

    /**
     * 测试注入 API 必须复位简繁表（`BUG.md` L-55）。
     *
     * 四张表是全局单例状态：`loadFromTexts` 只复位档位位图时，上一个用例设过的映射会渗进下一个用例，
     * 而**漏掉复位不会报错** —— 断言只是在错误的数据上通过。
     */
    @Test
    fun 测试注入必须复位简繁表() {
        PinyinEngine.resetForTest()
        PinyinEngine.setSimpTradText("头\t頭\n")
        assertEquals("前置条件：表已生效", "头", PinyinEngine.toSimplified("頭"))

        PinyinEngine.loadFromTexts("tou\t头\n", "", "tou\n")
        assertEquals(
            "loadFromTexts 必须复位简繁表（否则上一用例的映射渗进来）",
            "頭",
            PinyinEngine.toSimplified("頭"),
        )
    }
}
