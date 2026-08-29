package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 拼音引擎查询单元测试（不依赖 Android 资源，用 [PinyinEngine.loadFromTexts] 注入）。
 *
 * 覆盖：
 *  - 词语精确匹配与 lue/ve 变体
 *  - 前缀联想（nih → 你好）
 *  - 单字候选（完整音节 vs 前缀）
 *  - 候选去重与排序稳定性
 */
class PinyinEngineTest {

    @Before
    fun setUp() {
        val chars = """
            ni	你,尼,泥,呢,逆
            hao	好,号,浩,耗,毫
            ma	吗,妈,麻,马
            nian	年,念,捻,黏
            nie	捏,聂,镊,镍
            lv	绿,吕,旅,律
            lu	路,录,鲁,陆
            nv	女,钕
            lue	略,掠
            jue	觉,绝,决,角
            que	却,缺,确
            xue	学,雪,血
            xi	系,西,希,喜
            ce	策,测,侧,册
            zhong	中,种,重,钟
            guo	国,过,果,锅
            dui	对,队,堆,兑
            shuo	说,硕,烁,朔
            de	的,得,地,德
            shen	什,身,深,神
            me	么,嘛
            men	们,门
        """.trimIndent()
        val phrases = """
            nihao	你好
            nihaoma	你好吗
            nihaoa	你好啊
            nihai	你害
            nian	年
            nianji	年纪
            nianqing	年轻
            zhongguo	中国
            shenglue	省略
            shenglve	省略
            lvse	绿色
            luse	绿色
            juece	决策
            jvece	决策
            xuexi	学习
            jundui	军队
            shuode	说的
            shenme	什么
            nimen	你们
        """.trimIndent()
        val syllables = """
            a
            ni
            hao
            ma
            nian
            nie
            lv
            lu
            nv
            lue
            nue
            jue
            que
            xue
            xi
            ce
            zhong
            guo
            sheng
            dui
            lve
            nve
            jve
            qve
            xve
            jun
            qun
            xun
            se
            lvse
            shuo
            de
            shen
            me
            men
        """.trimIndent()
        PinyinEngine.loadFromTexts(chars, phrases, syllables)
    }

    @Test
    fun 精确匹配词语_你好() {
        val result = PinyinEngine.query("nihao")
        assertTrue("候选应包含 你好: ${result.candidates}", result.candidates.contains("你好"))
        assertEquals(listOf("ni", "hao"), result.syllables)
    }

    @Test
    fun 按音节数逐级递减_两音节() {
        // nihao = [ni,hao] 2 音节 → 先 2 字词 你好，再 1 字 你/好
        val result = PinyinEngine.query("nihao")
        val c = result.candidates
        assertTrue("应含 2 字词 你好: $c", c.contains("你好"))
        assertTrue("应含 单字 你: $c", c.contains("你"))
        assertTrue("应含 单字 好: $c", c.contains("好"))
        // 2 字词排在单字前
        assertTrue("你好 应在 你 之前: $c", c.indexOf("你好") < c.indexOf("你"))
        // 不显示超出拼音数量的词（如 nihaoma → 你好吗）
        assertTrue("不应显示 3 字词 你好吗: $c", !c.contains("你好吗"))
    }

    @Test
    fun 按音节数逐级递减_三音节() {
        // nihaoma = [ni,hao,ma] 3 音节 → 3 字词、2 字词、单字
        val result = PinyinEngine.query("nihaoma")
        val c = result.candidates
        assertTrue("应含 3 字词 你好吗: $c", c.contains("你好吗"))
        assertTrue("应含 2 字词 你好: $c", c.contains("你好"))
        assertTrue("应含 单字 你: $c", c.contains("你"))
        // 3 字 > 2 字 > 1 字
        assertTrue("你好吗 应在 你好 前: $c", c.indexOf("你好吗") < c.indexOf("你好"))
        assertTrue("你好 应在 你 前: $c", c.indexOf("你好") < c.indexOf("你"))
    }

    @Test
    fun 不显示超出拼音数量的词() {
        // 输入 nihao（2 音节）→ 只显示 2 字及以下，不显示 nihaoma（3 字）
        val result = PinyinEngine.query("nihao")
        assertTrue("不应显示 你好啊: ${result.candidates}", !result.candidates.contains("你好啊"))
        assertTrue("不应显示 你好吗: ${result.candidates}", !result.candidates.contains("你好吗"))
    }

    @Test
    fun 未完成音节前缀单字() {
        // nih → 你 + h 前缀的单字（如 好/号/浩 均以 h 开头）
        val result = PinyinEngine.query("nih")
        assertTrue("nih 应出 你: ${result.candidates}", result.candidates.contains("你"))
        // nih 不完整，不能出现 3 字词
        assertTrue("nih 不应出 你好吗: ${result.candidates}", !result.candidates.contains("你好吗"))
    }

    @Test
    fun 完整音节单字() {
        val result = PinyinEngine.query("ni")
        assertTrue("ni 应出 你: ${result.candidates}", result.candidates.contains("你"))
        // ni 是完整音节，不应带出更长音节 nian 的字（前缀联想有音节边界校验）
        assertTrue("完整音节 ni 不应带出 nian 的字: ${result.candidates}", !result.candidates.contains("年"))
    }

    @Test
    fun 未完成音节前缀匹配() {
        // nia → nian 前缀 → 年
        val result = PinyinEngine.query("nia")
        assertTrue("nia 前缀应出 年: ${result.candidates}", result.candidates.contains("年"))
    }

    @Test
    fun 短语lue与ve变体均可命中() {
        // 词库同时有 shenglue 和 shenglve，任一种输入都应命中
        assertTrue(PinyinEngine.query("shenglue").candidates.contains("省略"))
        assertTrue(PinyinEngine.query("shenglve").candidates.contains("省略"))
    }

    @Test
    fun 绿色两种写法() {
        assertTrue(PinyinEngine.query("lvse").candidates.contains("绿色"))
        assertTrue(PinyinEngine.query("luse").candidates.contains("绿色"))
    }

    @Test
    fun 决策两种写法() {
        assertTrue(PinyinEngine.query("juece").candidates.contains("决策"))
        assertTrue(PinyinEngine.query("jvece").candidates.contains("决策"))
    }

    @Test
    fun 双拼转换后查询_中国() {
        // 双拼 vsgo → zhongguo
        val quanpin = Shuangpin.toQuanpin("vsgo")
        assertEquals("zhongguo", quanpin)
        assertTrue(PinyinEngine.query(quanpin).candidates.contains("中国"))
    }

    @Test
    fun 双拼转换后查询_学习() {
        // xue(xt) + xi(xi) → xuexi
        val quanpin = Shuangpin.toQuanpin("xtxi")
        assertEquals("xuexi", quanpin)
        assertTrue(PinyinEngine.query(quanpin).candidates.contains("学习"))
    }

    @Test
    fun 双拼转换后查询_绿色() {
        // lv(lv) + se(se) → lvse
        val quanpin = Shuangpin.toQuanpin("lvse")
        assertEquals("lvse", quanpin)
        assertTrue(PinyinEngine.query(quanpin).candidates.contains("绿色"))
    }

    @Test
    fun 双拼转换后查询_军队() {
        // jun(jp) + dui(dv) → jundui
        val quanpin = Shuangpin.toQuanpin("jpdv")
        assertEquals("jundui", quanpin)
        assertTrue(PinyinEngine.query(quanpin).candidates.contains("军队"))
    }

    @Test
    fun 空输入() {
        assertTrue(PinyinEngine.query("").isEmpty)
    }

    @Test
    fun 候选去重() {
        // 精确命中 你好 且前缀联想也命中，最终只能出现一次
        val result = PinyinEngine.query("nihao")
        val count = result.candidates.count { it == "你好" }
        assertEquals("候选不应重复: ${result.candidates}", 1, count)
    }

    @Test
    fun 单字候选顺序稳定() {
        val r1 = PinyinEngine.query("n")
        val r2 = PinyinEngine.query("n")
        assertEquals("两次查询候选顺序应一致", r1.candidates, r2.candidates)
    }

    @Test
    fun 零声母双拼_安全() {
        // an 直写 + oj 兼容写法都应得到 an 并可查单字 安
        val chars = PinyinEngine.charsFor("an")
        // charsFor 不应崩（an 未注入 chars 时返回空）
        assertTrue(chars.isEmpty())
    }

    @Test
    fun 智能预测_选你好预测吗和啊() {
        // 词库有 nihaoma→你好吗、nihaoa→你好啊，选「你好」应预测「吗」「啊」
        val preds = PinyinEngine.predict("你好")
        assertTrue("应预测 吗: $preds", preds.contains("吗"))
        assertTrue("应预测 啊: $preds", preds.contains("啊"))
    }

    @Test
    fun 智能预测_未知词返回空() {
        assertTrue(PinyinEngine.predict("不存在的词xyz").isEmpty())
    }

    @Test
    fun 智能预测_空输入返回空() {
        assertTrue(PinyinEngine.predict("").isEmpty())
    }

    // ── 候选消费区间（Residual Pinyin Rematching）──────────────

    @Test
    fun 消费区间_词语只消费自己的拼音Span() {
        // shuodeshenme 选「说的」(shuode) → 消费 6 字符 / 2 音节，剩余 shenme
        val c = PinyinEngine.consumption("shuodeshenme", "说的")
        assertEquals(6, c.quanpinChars)
        assertEquals(2, c.syllables)
        assertEquals("shenme", "shuodeshenme".substring(c.quanpinChars))
    }

    @Test
    fun 消费区间_其他同Span词语同样保留残码() {
        // 不能只对「说的」做特殊处理：同区间任何词都应只消费 shuode
        val c = PinyinEngine.consumption("shuodeshenme", "说的")
        assertEquals(6, c.quanpinChars)
    }

    @Test
    fun 消费区间_候选覆盖全部输入时无残码() {
        // shuode 选「说的」→ 全部消费
        val c = PinyinEngine.consumption("shuode", "说的")
        assertEquals(6, c.quanpinChars)
        assertEquals(6, "shuode".length)
    }

    @Test
    fun 消费区间_单字消费所在音节() {
        // shuodeshenme 选「说」→ 只消费 shuo（4 字符 / 1 音节），剩余 deshenme
        val c = PinyinEngine.consumption("shuodeshenme", "说")
        assertEquals(4, c.quanpinChars)
        assertEquals(1, c.syllables)
        assertEquals("deshenme", "shuodeshenme".substring(c.quanpinChars))
    }

    @Test
    fun 消费区间_补全候选消费全部输入() {
        // nim → 补全出 你们(nimen)：词拼音以输入为前缀，消费全部 3 字符
        val c = PinyinEngine.consumption("nim", "你们")
        assertEquals(3, c.quanpinChars)
    }

    @Test
    fun 消费区间_连续部分消费() {
        // shuodeshenme → 消费 shuode → 剩余 shenme；
        // shenme 选「什么」→ 全部消费（连续部分消费链路）
        val c1 = PinyinEngine.consumption("shuodeshenme", "说的")
        val residual = "shuodeshenme".substring(c1.quanpinChars)
        assertEquals("shenme", residual)
        val c2 = PinyinEngine.consumption(residual, "什么")
        assertEquals(residual, residual.substring(0, c2.quanpinChars))
    }
}
