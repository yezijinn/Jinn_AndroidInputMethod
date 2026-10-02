package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 生僻字档位过滤测试（纯 JVM，用 [PinyinEngine.loadFromTexts] 注入）。
 *
 * 机制（2026-09-27 起重构）：三档字表 —— 档 1（`common_chars`，默认放行）、
 * 档 2（`tier2_chars`，设置页开关）、档 3（`tier3_chars`，开关且**依赖档 2**）；
 * 档外字（不在任何档）**不随包，任何档位下都不放行**。
 *
 * 过滤在**查询期**做（`isLoadableChar` / `filterRareChars`），所以开关一改即时生效、
 * 不需要重载词库 —— 这与旧的「加载期过滤 + 重启生效」不同，本类同时守住这两条。
 *
 * 资产侧：`pinyin_chars` 是**三档并集**（`tools/dict_builder/build_dicts.py` 产出），
 * 与三张档位表的口径一致性由 [三档字表并集等于单字表字集] 钉住。
 */
class RareCharsFilterTest {

    private val syllables = """
        qi
        e
        qie
        tao
        tie
        taotie
    """.trimIndent()

    /** 单字表：常用字、三级字与表外字混排 */
    private val chars = """
        qi	企,乞,起
        e	鹅,额,饿,噩
        qie	且,切
        tao	饕,涛,掏
        tie	餮,铁
    """.trimIndent()

    /** 词表：「企鹅」全常用字；「饕餮」全表外字；「涛涛」与饕餮同键但全常用字 */
    private val phrases = """
        qie	企鹅|切
        taotie	饕餮|涛涛
    """.trimIndent()

    // ── 三档字表（2026-09-27 起重构：档 1 默认放行，档 2 / 档 3 按开关放行，档外字不随包）──

    /** 档 1（默认）：常用字 */
    private val commonChars = "企乞起鹅额饿且切涛掏铁"

    /** 档 2（可选）：只收「噩」，模拟二级档 */
    private val tier2Chars = "噩"

    /** 档 3（可选，依赖档 2）：只收「餮」，模拟三级档 */
    private val tier3Chars = "餮"

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
    }

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
            // 文本资产自 2026-09-26 起以 .xz 存进 APK（体积余量），测试按短名取、自动解压
            val xz = File("$p.xz")
            if (xz.isFile) {
                return org.tukaani.xz.XZInputStream(xz.inputStream())
                    .use { String(it.readBytes(), Charsets.UTF_8) }
            }
        }
        throw AssertionError("未找到 asset $name")
    }

    @Test
    fun withoutFilter_rareContentIsAvailable() {
        PinyinEngine.loadFromTexts(chars, phrases, syllables)
        assertTrue(
            "未启用过滤时「企鹅」应可用",
            PinyinEngine.query("qie").candidates.contains("企鹅"),
        )
        assertTrue(
            "未启用过滤时「饕餮」应可用",
            PinyinEngine.query("taotie").candidates.contains("饕餮"),
        )
    }

    @Test
    fun withFilter_rareWordIsDropped() {
        PinyinEngine.loadFromTexts(chars, phrases, syllables, commonChars, tier3Chars)
        assertTrue(
            "常用字构成的「企鹅」应保留",
            PinyinEngine.query("qie").candidates.contains("企鹅"),
        )
        val got = PinyinEngine.query("taotie").candidates
        assertFalse("含表外字的「饕餮」应被过滤: $got", got.contains("饕餮"))
        assertTrue("同键的常用词「涛涛」应保留: $got", got.contains("涛涛"))
    }

    @Test
    fun withFilter_rareSingleCharIsDropped() {
        PinyinEngine.loadFromTexts(chars, phrases, syllables, commonChars, tier3Chars)
        val got = PinyinEngine.query("tao").candidates
        assertFalse("表外单字「饕」应被过滤: $got", got.contains("饕"))
        assertTrue("常用字「涛」应保留: $got", got.contains("涛"))
    }

    /** 默认只放行档 1：档 2 / 档 3 / 档外字全部拦下 */
    @Test
    fun 默认只放行档1() {
        PinyinEngine.loadFromTexts(
            chars, phrases, syllables, commonChars, tier3Chars,
            tier2CharsText = tier2Chars,
        )
        assertFalse("档 2 字「噩」默认不出现", PinyinEngine.query("e").candidates.contains("噩"))
        assertFalse("档 3 字「餮」默认不出现", PinyinEngine.query("tie").candidates.contains("餮"))
        assertFalse("档外字「饕」默认不出现", PinyinEngine.query("tao").candidates.contains("饕"))
        assertTrue("档 1 字「涛」应保留", PinyinEngine.query("tao").candidates.contains("涛"))
    }

    /** 开档 2：档 2 字放行；档 3 与档外字仍拦下 */
    @Test
    fun 开档2后二级字可用而三级与档外仍拦下() {
        PinyinEngine.loadFromTexts(
            chars, phrases, syllables, commonChars, tier3Chars,
            tier2CharsText = tier2Chars, rareTier2 = true,
        )
        assertTrue("档 2 字「噩」应可用", PinyinEngine.query("e").candidates.contains("噩"))
        assertFalse("档 3 字「餮」仍需先开档 3", PinyinEngine.query("tie").candidates.contains("餮"))
        assertFalse("档外字「饕」不可用", PinyinEngine.query("tao").candidates.contains("饕"))
    }

    /** 两档齐开：档 2 / 档 3 字都放行；档外字（含含它的词）仍不随包 */
    @Test
    fun 两档齐开后三级字可用而档外字仍拦下() {
        PinyinEngine.loadFromTexts(
            chars, phrases, syllables, commonChars, tier3Chars,
            tier2CharsText = tier2Chars, rareTier2 = true, rareTier3 = true,
        )
        assertTrue("档 2 字「噩」应可用", PinyinEngine.query("e").candidates.contains("噩"))
        assertTrue("档 3 字「餮」应可用", PinyinEngine.query("tie").candidates.contains("餮"))
        assertFalse(
            "含档外字的词「饕餮」仍应被过滤",
            PinyinEngine.query("taotie").candidates.contains("饕餮"),
        )
    }

    /**
     * 非法组合「只开档 3」被引擎兜掉。
     *
     * 设置页（`RareCharsActivity`）已强制「档 2 关则档 3 不可点」，这里的入口是导入的备份 /
     * 手工改过的 prefs —— 引擎侧的 `setRareTiers` 必须再兜一次，否则会出现
     * 「三级字能用、二级字反而不能用」的倒挂。
     */
    @Test
    fun 只开档3不生效_引擎强制依赖档2() {
        PinyinEngine.loadFromTexts(
            chars, phrases, syllables, commonChars, tier3Chars,
            tier2CharsText = tier2Chars, rareTier3 = true,
        )
        assertFalse("未开档 2 时档 3 字不得出现", PinyinEngine.query("tie").candidates.contains("餮"))
        assertFalse("档 2 字同样不出现", PinyinEngine.query("e").candidates.contains("噩"))
    }

    /**
     * 随包三档字表与单字表同口径：并集 == 单字表字集，且三档两两互斥。
     *
     * 档 1 / 档 2 / 档 3 分别由设置页开关放行（`RareCharsActivity`），单字表是它们的并集 ——
     * 任何一边漏字都会让「开了开关却打不出」复现（用户 2026-09-25 报告过同型问题）。
     * `囧淼喆昇` 是用户报告的人名地名用字，都在档 1。
     */
    @Test
    fun 三档字表并集等于单字表字集() {
        val common = charsOf(asset("common_chars.txt"))
        val tier2 = charsOf(asset("tier2_chars.txt"))
        val tier3 = charsOf(asset("tier3_chars.txt"))
        val chars = charsOf(asset("pinyin_chars.txt"))
        assertTrue("档 1 规模异常: ${common.size}", common.size >= 5000)
        assertTrue("档 2 规模异常: ${tier2.size}", tier2.size >= 800)
        assertTrue("档 3 规模异常: ${tier3.size}", tier3.size >= 2000)
        assertEquals("三档并集必须等于单字表字集", common + tier2 + tier3, chars)
        assertEquals("档 1 与档 2 不得重叠", emptySet<Int>(), common intersect tier2)
        assertEquals("档 2 与档 3 不得重叠", emptySet<Int>(), tier2 intersect tier3)
        val missing = "囧淼喆昇".filterNot { it.code in common }
        assertEquals("档 1 打不出的字: $missing", "", missing)
    }

    /**
     * 档位开关必须让「合并结果缓存」失效（2026-09-27 复现后固化，BUG.md L-57）。
     *
     * `mergedCache` 存的是 `filterRareChars` **之后**的词表：关档时查过的键会把它「档外词已被剔除」
     * 的结果（整条被过滤时是 `EMPTY_WORDS` 哨兵）一直返回 ⇒ 用户「先打一次没打出来 → 去开档 →
     * 回来再打同一拼音」时**词还是不出现**，而单字走 `charsFor`（不经缓存）当场就出得来，
     * 看起来像「开关只对单字生效」。本条**两个方向**都要成立（开档要放行、关档要收回）。
     *
     * ⚠ 时序必须是「先按关档状态查询 → 再 setRareTiers」：加载时就带上档位的话，
     * 每次过滤都是新算的，缓存缺陷会被完全掩盖（与 [开关打开后档2与档3的单字候选必须出现] 同一教训）。
     */
    @Test
    fun 开关档位后此前查过的拼音键必须重新查得到() {
        val syl = "chan\nshi\nshuo\n"
        val ch = "chan\t产,谶\nshi\t事\nshuo\t说\n"
        // 两个词**都含档 2 字「谶」**：关档时整词被过滤 ⇒ 这两个键都会拿到「确实没有」
        val ph = "chanshuo\t谶说\nchanshi\t谶事\n"
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            ch, ph, syl,
            commonCharsText = "产事说", tier2CharsText = "谶",
            rareTier2 = false, rareTier3 = false,
        )
        val before = PinyinEngine.query("chanshuo").candidates
        assertFalse("关档时含档 2 字的词不得出现: $before", before.contains("谶说"))

        PinyinEngine.setRareTiers(true, false)
        assertTrue(
            "开档后单字「谶」应放行（单字不经合并缓存，用于与下面的键路径对照）: " +
                "${PinyinEngine.query("chan").candidates}",
            PinyinEngine.query("chan").candidates.contains("谶"),
        )
        assertTrue(
            "开档后**没查过**的键应放行「谶事」: ${PinyinEngine.query("chanshi").candidates}",
            PinyinEngine.query("chanshi").candidates.contains("谶事"),
        )
        assertTrue(
            "开档后**先前查过的**键也必须放行「谶说」（缓存要随档位失效）: " +
                "${PinyinEngine.query("chanshuo").candidates}",
            PinyinEngine.query("chanshuo").candidates.contains("谶说"),
        )

        PinyinEngine.setRareTiers(false, false)
        assertFalse(
            "关档后先前查过的键不得再返回档外词「谶说」: ${PinyinEngine.query("chanshuo").candidates}",
            PinyinEngine.query("chanshuo").candidates.contains("谶说"),
        )
    }

    /**
     * 按码点收集字表里的汉字。
     *
     * 单字表行首是拉丁音节（`a\t啊,阿`），必须剔除，否则「字集一致」永远不成立；
     * 扩展区字按码点比对，避免代理对劈裂（`㹴` / `䍁` 在扩展 A 区）。
     */
    private fun charsOf(text: String): Set<Int> {
        val out = HashSet<Int>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                i += Character.charCount(cp)
                if (isHan(cp)) out.add(cp)
            }
        }
        return out
    }

    /**
     * 档 2 / 档 3 的字在**开关打开后**必须真的出现在单字候选里（2026-09-27 真机抓到）。
     *
     * 原实现在**加载期**就把档外字丢掉了（`loadCharsReader` 里的 `isLoadableChar`），
     * 而放行是「查询期」语义 —— 于是开关打开时字根本不在内存里，二级 / 三级字的**单字候选
     * 永远打不出**（词语候选因为走 `filterRareChars` 反而正常，掩盖了这条）。
     * 真机取证：开档 2 + 档 3 后输入 `lt`(lve)，候选里没有三级字「锊」。
     */
    @Test
    fun 开关打开后档2与档3的单字候选必须出现() {
        // **_真实的时序**：开档位发生在词库加载**之后**（用户进了设置页才点开关）；
        // 所以测试必须「先按关档状态加载，再 setRareTiers」，而不是加载时就带上档位 ——
        // 后者每次重新过滤，会把这条缺陷完全掩盖（第一版测试就是这么写的，跑出来是绿的）。
        val syl = "lt\nlve\n"
        val ch = "lve\t绿,圙,锊\n"
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            ch, "", syl,
            commonCharsText = "绿", tier2CharsText = "圙", tier3CharsText = "锊",
            rareTier2 = false, rareTier3 = false,
        )
        assertEquals("关档时只应有档 1 的字", listOf("绿"), PinyinEngine.charsFor("lve"))

        PinyinEngine.setRareTiers(true, false)
        val t2 = PinyinEngine.charsFor("lve").toList()
        assertTrue("开档 2 后应有「圙」: $t2", t2.contains("圙"))
        assertFalse("档 3 未开时不该有「锊」: $t2", t2.contains("锊"))

        PinyinEngine.setRareTiers(true, true)
        val t3 = PinyinEngine.charsFor("lve").toList()
        assertTrue("开档 3 后应有「锊」: $t3", t3.contains("锊"))
    }

    /**
     * 档位页的例字必须落在对应档里（2026-09-27）。
     *
     * 页面上「增加二级生僻字…」那句小字一度写着「亟 谌 髯」—— 它们后来被移进默认档，举例就成了
     * 「默认档的字」；三级的「圙」其实在档 2。文案与数据脱节肉眼很难发现，这里从**源码常量**里取出
     * 例字、逐个核档：改文案或改档位表都会把它逼出来。
     */
    @Test
    fun 档位页例字必须落在对应档里() {
        val path = listOf(
            File("src/main/java/com/jinn/inputmethod/RareCharsActivity.kt"),
            File("app/src/main/java/com/jinn/inputmethod/RareCharsActivity.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 RareCharsActivity.kt")
        val src = path.readText()
        val t2 = charsOf(asset("tier2_chars.txt"))
        val t3 = charsOf(asset("tier3_chars.txt"))
        for ((key, allow, which) in listOf(
            Triple("TIER2_DESC", t2, "档 2"),
            Triple("TIER3_DESC", t3, "档 3"),
        )) {
            val examples = examplesOf(src, key)
            assertTrue("$key 里没解析出例字（文案格式变了？）", examples.isNotEmpty())
            for (c in examples) {
                assertTrue(
                    "档位页例字「$c」不在$which 里 —— 举例与实际档位脱节，请换成该档的字",
                    c.codePointAt(0) in allow,
                )
            }
        }
    }

    /** 取 `const val KEY = "...：甲 乙 丙 一类..."` 里「：」与「 一类」之间的例字 */
    private fun examplesOf(src: String, key: String): List<String> {
        val line = src.lineSequence().firstOrNull { it.contains("const val $key =") } ?: return emptyList()
        return line.substringAfter("：").substringBefore(" 一类")
            .split(" ").filter { it.isNotBlank() }
    }

    /**
     * 档位页写的「默认只收常用字（5,613 字）」必须等于档 1 资产的**实际字数**（BUG.md L-64）。
     *
     * 那句小字是三档方案的说明书，字表一旦重建（换频率口径、增删字），这个数字就成了假话，
     * 而它既不在 test 里、也不在 lint 的视野内。同类的「第一次约 N 秒」已踩过一次（L-46），
     * 所以这里从**源码常量**里取值、与资产实数对拍：改数据不改文案就会被这条逼出来。
     */
    @Test
    fun 档位页写的常用字数必须等于档1资产字数() {
        val src = codeOf("RareCharsActivity.kt")
        val m = Regex("""（([\d,]+) 字）""").find(src)
            ?: error("TEXT_DESC 里没有「（N 字）」（文案格式变了？）")
        val declared = m.groupValues[1].replace(",", "").toInt()
        val actual = charsOf(asset("common_chars.txt")).size
        assertEquals("档位页写的常用字数与档 1 资产不符", actual, declared)
    }

    /**
     * 两个复刻页的关闭键都必须给出可听键名（BUG.md L-64）。
     *
     * 键面只是一个字形，辅助服务照字面朗读等于没读 ⇒ 必须给出可听名；两页是复刻关系，
     * 只改一页会让结构分叉（本类另一条守卫 [档位页例字必须落在对应档里] 同样依赖这两页一一对应）。
     *
     * 键面与名字自 2026-10-02 起是**全仓唯一定义**（`PageChrome.CLOSE` / `CLOSE_DESC`，BUG.md L-477），
     * 所以除了两页都接线，还要钉住那个定义本身没被改空（`✕` 是有形无名的字形）。
     */
    @Test
    fun 两个复刻页的关闭键都必须给出可听键名() {
        val chrome = codeOf("PageChrome.kt")
        assertTrue("PageChrome 必须定义可听键名", chrome.contains("const val CLOSE_DESC = \"关闭\""))
        assertTrue("PageChrome 必须定义键面字形", chrome.contains("const val CLOSE = \"✕\""))
        for (name in listOf("RareCharsActivity.kt", "FuzzyPinyinActivity.kt")) {
            val src = codeOf(name)
            assertTrue(
                "$name 的关闭键缺少 contentDescription（辅助服务只会读到一个字形）",
                src.contains("contentDescription = PageChrome.CLOSE_DESC"),
            )
        }
    }

    /**
     * 关档 2 时的「连带关档 3」必须在挂起态里做（BUG.md L-63）。
     *
     * `checkTier3.isChecked = false` 会**同步**触发档 3 自己的监听器；不挂起的话，同一次拨动
     * 会写两遍 prefs、调两次 `setRareTiers`、打两条日志、让合并缓存失效两次 —— 结果幂等，
     * 但真机排查时会误以为用户操作了两次。Activity 进不了 JVM 单测，这条只能做**源码对拍**：
     * 从 tier2 监听器的函数体里看「bulk = true → 改勾选 → bulk = false」这个形状还在不在。
     */
    @Test
    fun 关档2的级联关档3必须在挂起态里做() {
        val src = codeOf("RareCharsActivity.kt")
        val body = src.substringAfter("checkTier2.setOnCheckedChangeListener")
            .substringBefore("checkTier3.setOnCheckedChangeListener")
        assertTrue("tier2 监听器里找不到级联关档 3 的代码（重构后请同步这条守卫）", "checkTier3.isChecked = false" in body)
        assertTrue(
            "级联关档 3 没有在挂起态里做：同一次拨动会写两遍盘、打两条日志",
            Regex("""bulk = true\s*\n\s*checkTier3\.isChecked = false\s*\n\s*bulk = false""").containsMatchIn(body),
        )
    }

    /** 按短名找源码文件（测试既可能在仓库根、也可能在 app/ 下跑） */
    private fun sourceOf(name: String): String = TestSources.rawSourceOfShortName(name)

    /** 判据一律在**剥注释后**的代码上做（BUG.md L-116） */
    private fun codeOf(name: String): String = TestSources.codeOf(sourceOf(name))

    private fun isHan(cp: Int): Boolean =
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0x20000..0x3FFFF
}
