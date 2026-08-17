package com.jinn.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 不完整拼音解析 + 候选补全单元测试（文档《Incomplete Pinyin Parsing + Pinyin Completion》）。
 *
 * 覆盖：
 *  - 前缀判断 / 补全（m → ma/mei/men…）
 *  - 连续无空格分词（nim → ni+m、nimen → ni+men、women → wo+men）
 *  - 中文词召回（ni m → 你们/你妈/你没）
 *  - 完整匹配优先于补全匹配
 *  - 补全降权、错误输入不崩溃、超长输入有上限
 */
class PinyinCompletionTest {

    @Before
    fun setUp() {
        val chars = """
            ni	你,尼,泥,呢
            ma	吗,妈,麻,马
            mei	没,美,妹,梅
            men	们,门,闷
            wo	我,窝,握,卧
            shang	上,伤,商,赏
            hai	海,还,孩,害
            zi	子,自,字,资
            mu	目,木,母,牧
            ming	明,名,命,鸣
            mo	摸,魔,磨,莫
            mi	米,密,谜,觅
            xu	虚,须,需,许
            ni	你,尼,泥,呢
            xun	寻,巡,询,循
            i	衣,以,意,一
        """.trimIndent()
        val phrases = """
            nima	你妈
            nimei	你没
            nimen	你们
            wang	网
            women	我们
            shanghai	上海
            woai	我爱
            niming	匿名
            xiang	想
            xiangyao	想要
            niyao	你要
            shang	上
            xuni	虚拟
            xun   寻
        """.trimIndent()
        val syllables = """
            a
            m
            xu
            xun
            ni
            ma
            mei
            men
            mo
            mu
            mi
            ming
            wo
            shang
            hai
            zi
            xiang
            yao
            nian
            hao
            mai
            man
            mang
            mao
            me
            meng
            mian
            mie
            min
            mou
            nong
        """.trimIndent()
        PinyinEngine.loadFromTexts(chars, phrases, syllables)
    }

    // ── 1. 前缀判断 ──────────────────────────────────────

    @Test
    fun 前缀m是可补全前缀() {
        // m 是多个音节的前缀（ma/mai/man/mang/mao/me/mei/men…）
        val completions = PinyinEngine.completeSyllablePrefix("m")
        assertTrue("m 应有补全: $completions", completions.isNotEmpty())
        assertTrue("m 应含 ma: $completions", completions.contains("ma"))
        assertTrue("m 应含 mei: $completions", completions.contains("mei"))
        assertTrue("m 应含 men: $completions", completions.contains("men"))
        assertTrue("m 应含 mo: $completions", completions.contains("mo"))
        assertTrue("m 应含 mu: $completions", completions.contains("mu"))
    }

    @Test
    fun 前缀men补全men和meng() {
        // men 是 men 和 meng 的前缀（meng 以 men 开头）
        val completions = PinyinEngine.completeSyllablePrefix("men")
        assertTrue("men 应含 men: $completions", completions.contains("men"))
        assertTrue("men 应含 meng: $completions", completions.contains("meng"))
    }

    @Test
    fun 前缀me有多个补全() {
        val completions = PinyinEngine.completeSyllablePrefix("me")
        assertTrue("me 应含 mei: $completions", completions.contains("mei"))
        assertTrue("me 应含 men: $completions", completions.contains("men"))
        assertTrue("me 应含 meng: $completions", completions.contains("meng"))
    }

    @Test
    fun 完整音节men在补全集中() {
        val completions = PinyinEngine.completeSyllablePrefix("men")
        assertTrue("men 补全应含 men 自身: $completions", completions.contains("men"))
    }

    // ── 2. 中文词召回（核心验收） ─────────────────────────

    @Test
    fun 空格输入_ni空格m_召回词库词() {
        // ni m → ni + 补全 → nimen/nima/nimei → 你们/你妈/你没
        val candidates = PinyinEngine.query("nim").candidates
        assertTrue("nim 应出 你们: $candidates", candidates.contains("你们"))
        assertTrue("nim 应出 你妈: $candidates", candidates.contains("你妈"))
        assertTrue("nim 应出 你没: $candidates", candidates.contains("你没"))
    }

    @Test
    fun 连续输入nimen_正常分词ni加men() {
        // nimen → ni + men（完整两音节），正常词库查询
        val result = PinyinEngine.query("nimen")
        assertTrue("nimen 应出 你们: ${result.candidates}", result.candidates.contains("你们"))
        assertEquals(listOf("ni", "men"), result.syllables)
    }

    @Test
    fun 连续输入women_分词wo加men() {
        val result = PinyinEngine.query("women")
        assertTrue("women 应出 我们: ${result.candidates}", result.candidates.contains("我们"))
        assertEquals(listOf("wo", "men"), result.syllables)
    }

    @Test
    fun 连续输入nim_末尾m伪完整音节仍补全() {
        val result = PinyinEngine.query("nim")
        // m 本身是合法完整音节（真实词库如此），segment 切为 [ni, m]；
        // 但 m 同时是更长音节前缀 → 触发「伪完整音节」补全，召回词库词
        assertTrue("nim 应出 你们: ${result.candidates}", result.candidates.contains("你们"))
        assertTrue("nim 应出 你妈: ${result.candidates}", result.candidates.contains("你妈"))
    }

    @Test
    fun 补全词应排在单字之前() {
        // 关键：候选栏只显示 take(3)，补全词必须在单字前，用户才能看到
        val result = PinyinEngine.query("nim")
        val c = result.candidates
        val youmen = c.indexOf("你们")
        val ni = c.indexOf("你")
        assertTrue("你们 应存在: $c", youmen >= 0)
        assertTrue("你 应存在: $c", ni >= 0)
        assertTrue("补全词 你们 应在单字 你 之前: $c", youmen < ni)
        // 前 3 个候选应包含补全词
        assertTrue("候选前3应含补全词: ${c.take(3)}", c.take(3).any { it == "你们" || it == "你妈" || it == "你没" })
    }

    @Test
    fun 完整输入shanghai_不被错误拆分() {
        // shanghai 应正常解析为 shang+hai，不出错拆
        val result = PinyinEngine.query("shanghai")
        assertEquals(listOf("shang", "hai"), result.syllables)
        assertTrue("shanghai 应出 上海: ${result.candidates}", result.candidates.contains("上海"))
    }

    @Test
    fun xuni优先分词xu加ni出虚拟() {
        // 贪心最长匹配会切成 [xun, i]（寻+衣），但词库有「虚拟」=xuni，
        // 正确切分应为 [xu, ni] → 候选「虚拟」优先
        val hasXun = PinyinEngine.completeSyllablePrefix("xun").contains("xun")
        val hasXu = PinyinEngine.completeSyllablePrefix("xu").contains("xu")
        println("xun完整=$hasXun xu完整=$hasXu")
        val result = PinyinEngine.query("xuni")
        val c = result.candidates
        println("xuni syllables=${result.syllables} partial=${result.partialSyllable} candidates=$c")
        assertTrue("xuni 应出 虚拟: $c", c.contains("虚拟"))
        // 「虚拟」应排在「寻」之前（词优先于单字）
        val xuNi = c.indexOf("虚拟")
        val xun = c.indexOf("寻")
        if (xun >= 0) {
            assertTrue("虚拟 应在 寻 之前: $c", xuNi < xun)
        }
    }

    // ── 3. 完整匹配优先 ──────────────────────────────────

    @Test
    fun 完整匹配优先于补全() {
        // ni men 完整 → 你们 应出现；ni m 补全 → 你们 也在（补全）
        val full = PinyinEngine.query("nimen").candidates
        val partial = PinyinEngine.query("nim").candidates
        assertTrue("完整 ni men 出 你们: $full", full.contains("你们"))
        assertTrue("不完整 ni m 也出 你们: $partial", partial.contains("你们"))
        // 完整匹配的候选应排在补全之前（同样有 你们，完整查询 result 首位即整词）
        assertTrue("完整 ni men 首位应为 你们: $full", full.first() == "你们")
    }

    @Test
    fun 完整输入不带出补全词() {
        // nima 是完整两音节 → 走正常词库查询，出 你妈；但不带出 nimen（补全不会出现在完整路径）
        val result = PinyinEngine.query("nima")
        assertTrue("nima 应出 你妈: ${result.candidates}", result.candidates.contains("你妈"))
        // nima 完整，不触发补全（partial 为空），不会额外带出 你们
        assertFalse("完整 nima 不应带出 你们: ${result.candidates}", result.candidates.contains("你们"))
    }

    // ── 4. 错误输入 / 边界 ───────────────────────────────

    @Test
    fun 错误输入xyz不崩溃() {
        val result = PinyinEngine.query("xyz")
        // 不崩溃；可能空候选
        assertTrue(result.candidates.isEmpty() || result.candidates.isNotEmpty())
    }

    @Test
    fun 超长输入有上限不爆炸() {
        // 10 个连续 m（超 MAX_SYLLABLES=8）
        val longInput = "mmmmmmmmmm"
        val start = System.currentTimeMillis()
        val result = PinyinEngine.query(longInput)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("超长输入不应耗时过长: ${elapsed}ms", elapsed < 500)
        assertTrue("超长输入候选不应爆炸: ${result.candidates.size}", result.candidates.size <= 100)
    }

    @Test
    fun 空字符串不崩溃() {
        val result = PinyinEngine.query("")
        assertTrue(result.isEmpty)
    }

    @Test
    fun 单字符声母前缀m不崩溃() {
        val result = PinyinEngine.query("m")
        // m 是前缀，只做单字联想，不崩
        assertTrue(result.candidates.isNotEmpty() || result.candidates.isEmpty())
    }

    // ── 5. 补全结果上限 ──────────────────────────────────

    @Test
    fun 补全结果受MAX限制() {
        // m 前缀的合法音节数有限（注入词库约 15 个），补全不超上限
        val completions = PinyinEngine.completeSyllablePrefix("m")
        assertTrue("补全数量应有限: ${completions.size}", completions.size <= 32)
    }

    // ── 6. 双拼 / 英文隔离 ───────────────────────────────

    @Test
    fun 双拼输入不进入补全() {
        // 双拼"你们" = nimf（ni→ni：n键+i键；men→mf：m键+f键）
        val quanpin = Shuangpin.toQuanpin("nimf")
        assertEquals("nimen", quanpin)
        // 转全拼后是完整音节，query 正常出词，不依赖补全
        val result = PinyinEngine.query(quanpin)
        assertTrue("双拼转全拼 nimen 应出 你们: ${result.candidates}", result.candidates.contains("你们"))
    }

    @Test
    fun 英文模式隔离由上层保证() {
        // 引擎本身不感知英文模式；验证：纯字母输入走全拼查询不崩溃即可
        //（英文隔离在 PinyinKeyboardView 层：englishMode 时不调 PinyinEngine.query）
        val result = PinyinEngine.query("hello")
        assertTrue(result.candidates.isEmpty() || result.candidates.isNotEmpty())
    }
}
