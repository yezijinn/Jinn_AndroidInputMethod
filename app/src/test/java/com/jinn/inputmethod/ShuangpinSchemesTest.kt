package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 七套双拼方案（自然码 / 小鹤 / 搜狗 / 微软 / 紫光 / 智能ABC / 加加）的键位测试。
 *
 * 这些键位不是手抄的：`ShuangpinSchemes.kt` 由
 * `tools/dict_builder/gen_shuangpin_tables.py` 按 librime 的代数语义，从
 * `docs/rime-ice/double_pinyin*.schema.yaml` 的 `speller/algebra` 生成。
 * 本文件的职责是「钉住生成结果」：
 *
 *  1. 各方案的代表性键位与 rime 一致（含只有该方案才有的分号键）；
 *  2. 键面提示（韵母 / zh-ch-sh 红字）随方案变化，且与码表一致；
 *  3. 全表往返自洽：码表里每一条「码 → 音节」都能被 [Shuangpin.toQuanpin] 还原；
 *  4. 覆盖完整：`assets/pinyin_syllables.txt` 里每个可编码音节在七套方案下都有码。
 *
 * 纯 JVM，不需要设备。
 */
class ShuangpinSchemesTest {

    private fun q(code: String, scheme: ShuangpinScheme): String =
        Shuangpin.toQuanpin(code, scheme)

    // ── 一、各方案代表性键位（期望值取自 rime-ice schema 的生成结果）──────

    @Test
    fun 自然码_基本键位() {
        assertEquals("zhong", q("vs", ShuangpinScheme.ZIRANMA))
        assertEquals("hao", q("hk", ShuangpinScheme.ZIRANMA))
        assertEquals("shi", q("ui", ShuangpinScheme.ZIRANMA))
        assertEquals("ang", q("ah", ShuangpinScheme.ZIRANMA))
        // libime 风格兼容零声母（rime 无此规则，项目历史行为，保留）
        assertEquals("an", q("oj", ShuangpinScheme.ZIRANMA))
    }

    @Test
    fun 小鹤_韵母差异键位() {
        assertEquals("xiao", q("xn", ShuangpinScheme.FLYPY))
        assertEquals("shuang", q("ul", ShuangpinScheme.FLYPY))
        assertEquals("pin", q("pb", ShuangpinScheme.FLYPY))
        assertEquals("kuai", q("kk", ShuangpinScheme.FLYPY))
        assertEquals("he", q("he", ShuangpinScheme.FLYPY))
    }

    @Test
    fun 小鹤_连续输入() {
        // 「小鹤」= xiao(xn) + he(he)
        assertEquals("xiaohe", q("xnhe", ShuangpinScheme.FLYPY))
        // 「小鹤双拼」= xn + he + ul + pb（注意 双=shuang 的 iang/uang 在 l 键，不是 h）
        assertEquals("xiaoheshuangpin", q("xnheulpb", ShuangpinScheme.FLYPY))
    }

    @Test
    fun 搜狗_分号键为ing() {
        assertEquals("ting", q("t;", ShuangpinScheme.SOGOU))
        assertEquals("ying", q("y;", ShuangpinScheme.SOGOU))
        assertEquals("zhao", q("vk", ShuangpinScheme.SOGOU))
        assertEquals("huang", q("hd", ShuangpinScheme.SOGOU))
    }

    @Test
    fun 微软_分号键与ue双写() {
        assertEquals("ting", q("t;", ShuangpinScheme.MSPY))
        // 微软特有：ue 也可写在 v 键（derive/Ⓣ$/Ⓥ/）
        assertEquals("tui", q("tv", ShuangpinScheme.MSPY))
    }

    @Test
    fun 紫光_键位与零声母前缀() {
        assertEquals("bing", q("b;", ShuangpinScheme.ZIGUANG))
        assertEquals("bie", q("bd", ShuangpinScheme.ZIGUANG))
        assertEquals("bao", q("bq", ShuangpinScheme.ZIGUANG))
        assertEquals("hun", q("hm", ShuangpinScheme.ZIGUANG))
    }

    @Test
    fun 智能ABC_声母为aev() {
        // ABC 的 zh/ch/sh 分别是 a / e / v
        assertEquals("zhe", q("ae", ShuangpinScheme.ABC))
        assertEquals("hai", q("hl", ShuangpinScheme.ABC))
        assertEquals("zhuang", q("at", ShuangpinScheme.ABC))
        assertEquals("juan", q("jp", ShuangpinScheme.ABC))
    }

    @Test
    fun 加加_声母为你u我i() {
        // 加加的 sh / ch / zh 分别是 i / u / v
        assertEquals("chi", q("ui", ShuangpinScheme.JIAJIA))
        assertEquals("shou", q("ip", ShuangpinScheme.JIAJIA))
        assertEquals("zhong", q("vy", ShuangpinScheme.JIAJIA))
    }

    @Test
    fun 全拼方案_原样返回() {
        assertEquals("nihao", q("nihao", ShuangpinScheme.QUANPIN))
        assertEquals("", q("", ShuangpinScheme.QUANPIN))
    }

    // ── 一之二、设置页「输入方案」下拉：全拼 + 七套双拼（2026-09-20 起为全局方案选择）──

    /**
     * 下拉数据源 = [ShuangpinScheme.ALL]（全拼在最前 + 七套双拼，共 8 项）。
     *
     * 历史：该下拉原为「只选哪套双拼」（不含全拼）、全拼/双拼由键盘面板按钮切换；
     * 2026-09-20 按用户要求改为全局输入方案并移除面板按钮，本用例随之更新。
     * 旧的「不含全拼」断言是当时的正确行为，不要照搬回来。
     */
    @Test
    fun 输入方案列表_全拼加七套双拼共八项() {
        val all = ShuangpinScheme.ALL
        assertEquals("应为全拼 + 7 套双拼", 8, all.size)
        assertEquals("全拼必须排在最前（下拉首项）", ShuangpinScheme.QUANPIN, all.first())
        assertEquals(
            "其余七项即 SHUANGPIN_ONLY（顺序一致）",
            ShuangpinScheme.SHUANGPIN_ONLY,
            all.drop(1),
        )
        // 每套双拼都必须有键位表，否则下拉里会出现选不了的空方案
        assertTrue("存在缺键位表的方案", ShuangpinScheme.SHUANGPIN_ONLY.all { it.table != null })
        // 双拼集合本身仍不含全拼（键位提示遍历等场景依赖这一点）
        assertTrue("SHUANGPIN_ONLY 应全部是双拼方案", ShuangpinScheme.SHUANGPIN_ONLY.all { it.isShuangpin })
    }

    // ── 二、分号键：只有搜狗 / 微软 / 紫光需要 ──────────────────────────────

    @Test
    fun 分号键_仅三套方案需要() {
        val need = ShuangpinScheme.SHUANGPIN_ONLY.filter { Shuangpin.needsSemicolon(it) }
        assertEquals(
            listOf(ShuangpinScheme.SOGOU, ShuangpinScheme.MSPY, ShuangpinScheme.ZIGUANG),
            need,
        )
        assertFalse(Shuangpin.needsSemicolon(ShuangpinScheme.QUANPIN))
    }

    // ── 三、键面提示随方案变化（且与码表同源）─────────────────────────────

    @Test
    fun 键面提示_随方案变化() {
        val zr = ShuangpinScheme.ZIRANMA.table!!
        val flypy = ShuangpinScheme.FLYPY.table!!
        // 同一个 k 键：自然码是 ao，小鹤是 ing/uai
        assertEquals("ao", zr.finalHint('k'))
        assertTrue(flypy.finalHint('k').contains("ing"))
        assertTrue(flypy.finalHint('k').contains("uai"))
        // 小鹤 d 键是 ai（自然码是 iang/uang）
        assertEquals("ai", flypy.finalHint('d'))
        assertTrue(zr.finalHint('d').contains("iang"))
        // 分号键的提示就是 ing
        assertEquals("ing", ShuangpinScheme.MSPY.table!!.finalHint(';'))
    }

    @Test
    fun 键面提示_自然码o键为o与uo() {
        // 回归：旧实现把 o 键的提示手写成 "ou"（ou 实际在 b 键），与引擎不符
        val hint = ShuangpinScheme.ZIRANMA.table!!.finalHint('o')
        assertTrue("o 键提示应含 uo，实际=$hint", hint.contains("uo"))
        assertFalse("o 键提示不应是 ou，实际=$hint", hint == "ou")
        assertEquals("ou", ShuangpinScheme.ZIRANMA.table!!.finalHint('b'))
    }

    @Test
    fun 键面红字_zhchsh各方案不同() {
        val zr = ShuangpinScheme.ZIRANMA.table!!
        assertEquals("zh", zr.initialOf('v'))
        assertEquals("ch", zr.initialOf('i'))
        assertEquals("sh", zr.initialOf('u'))
        // ABC：zh/ch/sh = a/e/v；加加：sh/ch/zh = i/u/v
        assertEquals("zh", ShuangpinScheme.ABC.table!!.initialOf('a'))
        assertEquals("ch", ShuangpinScheme.ABC.table!!.initialOf('e'))
        assertEquals("sh", ShuangpinScheme.JIAJIA.table!!.initialOf('i'))
        assertEquals("ch", ShuangpinScheme.JIAJIA.table!!.initialOf('u'))
    }

    // ── 四、全表往返自洽 + 覆盖完整 ────────────────────────────────────────

    /** 码表里每条「码 → 音节」都必须能被还原（防生成器/表结构出现漏项） */
    @Test
    fun 全表往返自洽() {
        for (scheme in ShuangpinScheme.SHUANGPIN_ONLY) {
            val table = scheme.table ?: throw AssertionError("${scheme.displayName} 缺键位表")
            for ((code, syllable) in table.codes) {
                assertEquals("${scheme.displayName} 码 $code", syllable, q(code, scheme))
            }
        }
    }

    /** 每个可编码音节在七套方案下都要有码（双拼无法编码的叹词/脏数据除外） */
    @Test
    fun 七套方案覆盖同一套音节() {
        val syllables = readSyllables()
        assertTrue("音节表异常，仅 ${syllables.size} 条", syllables.size > 400)
        for (scheme in ShuangpinScheme.SHUANGPIN_ONLY) {
            val covered = scheme.table!!.codes.values.toSet()
            val missing = syllables.filterNot { it in UNENCODABLE }.filterNot { it in covered }
            // 允许的缺口只有「同码让位」的 4 个写法，其它任何缺口都算失败
            val unexpected = missing.filterNot { it in HOMOPHONE_SACRIFICE }
            assertTrue("${scheme.displayName} 未覆盖音节: $unexpected", unexpected.isEmpty())
        }
    }

    /** 键位表规模下限：任何一次重新生成若大幅丢数据，立刻失败 */
    @Test
    fun 键位表规模_无异常缩水() {
        for (scheme in ShuangpinScheme.SHUANGPIN_ONLY) {
            val codes = scheme.table!!.codes.size
            assertTrue("${scheme.displayName} 码数仅 $codes", codes >= 400)
        }
    }

    private fun readSyllables(): List<String> {
        val candidates = listOf(
            "src/main/assets/pinyin_syllables.txt",
            "app/src/main/assets/pinyin_syllables.txt",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.isFile) return f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            // 文本资产自 2026-09-26 起以 .xz 存进 APK（体积余量），测试按短名取、自动解压
            val xz = File("$path.xz")
            if (xz.isFile) {
                val text = org.tukaani.xz.XZInputStream(xz.inputStream())
                    .use { String(it.readBytes(), Charsets.UTF_8) }
                return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        throw AssertionError("未找到音节表，尝试过: $candidates")
    }

    private companion object {
        /**
         * 双拼编不出来的音节（rime 同样编不出，项目也未收入词库）：
         * `hng` / `hm` / `m` / `n` 是鼻音叹词（词库 0 条，紫光方案里 `hm` 还与 `hun` 同码）；
         * `junding` 是词库音节表里的一条脏数据（7 字母，非音节）。
         */
        val UNENCODABLE = setOf("hng", "hm", "m", "n", "junding")

        /**
         * 「两键一音节」模型下同码让位的写法（一码只能映射一个音节，必须牺牲一个）：
         *  - `lo` 让给 `luo`（罗/落远比 咯/啰 常用；全拼下 lo 仍可输入）；
         *  - `lve` / `nve` 让给 `lue` / `nue`：词库里的 lve/nve 词条依旧能命中，
         *    因为查询链路有 ue↔ve 变体回退（见 PinyinEngine.variants），只是不走「码」这条入口；
         *  - `ng` 让给 `neng` / `nang` / `niang`（词库 0 条）。
         */
        val HOMOPHONE_SACRIFICE = setOf("lo", "lve", "nve", "ng")
    }
}
