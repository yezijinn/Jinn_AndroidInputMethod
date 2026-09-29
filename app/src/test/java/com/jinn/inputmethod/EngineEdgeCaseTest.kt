package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 引擎边界的四条守卫（2026-09-27 修复轮，`BUG.md` L-65 / L-66 / L-67 / L-51）。
 *
 * 四条都属「静态看代码看不出来、只有对着数据/顺序才暴露」的类型，因此每条都写明
 * **失败时会看到什么**，改这三处代码前先读本类：
 *
 *  - [繁体模式下补全候选不得被二次转换] —— 候选出口有五个，补全那个出口的契约是「已经转过一次」；
 *  - [ue型输入的单字消费区间必须与v型一致] —— 字表只有 v 型键，而输入可能是 ue 型；
 *  - [扩展A区按档位放行] —— `isLoadableChar` 的 ASCII 放行阈值卡错一个常数就整段绕过位图；
 *  - [简化表先到也不丢字级反推项] —— 两张简繁表的调用顺序过去是有隐含依赖的。
 */
class EngineEdgeCaseTest {

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
    }

    /**
     * 繁体模式下**补全候选**只能转换一次（L-65）。
     *
     * `queryWithCompletion` 的出口契约是「返回**显示词**」（它自己过了一次 `displayTake`
     * 并按显示词登记候选→键），而 `query` 步骤 3a 又对返回值转了一次 ⇒ 第二次拿繁体形去查
     * 词级消歧表（键是简体）必然落空，字级 1:1 映射把消歧过的字又改坏：
     * 本用例的「萬里」会被改成「**萬裏**」（真库同理：干扰→幹擾、万里→萬裏，共 360 条）。
     *
     * 失败时会看到：候选里没有「萬里」、只有「萬裏」。
     */
    @Test
    fun 繁体模式下补全候选不得被二次转换() {
        PinyinEngine.loadFromTexts(
            "wan\t万\nli\t里,理\n",
            "wanli\t万里\n",
            "wan\nli\n",
        )
        PinyinEngine.setSimpTradText("万\t萬\n里\t裏\n")     // 字级：里 → 裏
        PinyinEngine.setSimpTradWordsText("万里\t萬里\n")   // 词级消歧：万里 → 萬里
        PinyinEngine.setTraditional(true)

        val r = PinyinEngine.query("wanl").candidates  // 末尾残码 l ⇒ 走补全召回
        assertTrue("补全候选应是词级消歧结果「萬里」: $r", r.contains("萬里"))
        assertFalse("补全候选被二次转换改坏了（字级 里→裏 又盖了一层）: $r", r.contains("萬裏"))

        // 对照组：非补全路径（完整输入）本来就只转一次，它一直是正确的
        val full = PinyinEngine.query("wanli").candidates
        assertTrue("完整输入应出「萬里」: $full", full.contains("萬里"))
        assertFalse("完整输入不得出现「萬裏」: $full", full.contains("萬裏"))
    }

    /**
     * ue 型输入选单字时，消费区间必须与 v 型一致（L-66）。
     *
     * 单字表统一是 v 型键（`lve → 略,掠,圙,锊`、`nve → 虐,疟`），而全拼按键与双拼
     * （`lt` / `nt`）给出的都是 ue 型。`consumption` 2a 只查「用户实际输入的音节」，
     * ue 型查不到 ⇒ 落到步骤 3 兜底「消费全部」，把 `luema` 的残码 `ma` 一起清掉。
     *
     * 失败时会看到：`luema` 选「略」返回 `(5, 2)` 而不是 `(3, 1)`。
     */
    @Test
    fun ue型输入的单字消费区间必须与v型一致() {
        PinyinEngine.loadFromTexts("lve\t略,掠\nma\t吗\n", "", "lve\nlue\nma\n")

        assertEquals(
            "v 型输入（基准行为）: ",
            PinyinEngine.Consumption(3, 1),
            PinyinEngine.consumption("lvema", "略"),
        )
        assertEquals(
            "ue 型输入打同一候选，消费区间必须与 v 型一致（否则残码 ma 被一起清掉）",
            PinyinEngine.Consumption(3, 1),
            PinyinEngine.consumption("luema", "略"),
        )
    }

    /**
     * 关闭档 2 / 档 3 时，两档里的**扩展 A 区**字也必须被拦下（L-67）。
     *
     * `isLoadableChar` 原判据是 `code < 0x4E00 → true`（本意「放行 ASCII / 标点」），
     * 把 0x3400–0x4DBF 的扩展 A 整段划进了放行分支 —— 与它自己 KDoc 的
     * 「扩展区一律不放行」矛盾。三档表里共有 39 个扩展 A 字（档 1 = 7、档 2 = 11、档 3 = 21），
     * 于是档 2 / 档 3 的 32 个字无论开关状态都会出现。
     *
     * 判据改成 `< 0x3400` 对**单字表**零丢字：9,373 字全部落在三档并集内。
     * ⚠ 词库正文里另有 31 个扩展 A 字（档内 11 / 档外 20，牵涉 37 条词条），
     * 阈值修正后它们随档位过滤 —— 与基本区档外词条同口径的附带收敛，不是丢字（BUG.md L-70）。
     * 档 1 的扩展 A 字（本用例的「㹴」）仍默认放行。
     *
     * 失败时会看到：关档时「㺀」（档 2 的扩展 A 字）照样出现在候选里。
     */
    @Test
    fun 扩展A区按档位放行() {
        PinyinEngine.loadFromTexts(
            "chan\t产,㹴,㺀,䞍\n",
            "",
            "chan\n",
            commonCharsText = "产㹴",      // 档 1 自带一个扩展 A 字（真实档 1 有 7 个）
            tier2CharsText = "㺀",          // 档 2 的扩展 A 字
            tier3CharsText = "䞍",          // 档 3 的扩展 A 字
        )

        assertTrue("档 1 的「产」应放行", PinyinEngine.query("chan").candidates.contains("产"))
        assertTrue(
            "档 1 的扩展 A 字「㹴」应放行（改判据不得把档 1 一起拦下）: " +
                "${PinyinEngine.query("chan").candidates}",
            PinyinEngine.query("chan").candidates.contains("㹴"),
        )
        assertFalse(
            "关档时档 2 的扩展 A 字「㺀」不得出现: ${PinyinEngine.query("chan").candidates}",
            PinyinEngine.query("chan").candidates.contains("㺀"),
        )
        assertFalse(
            "关档时档 3 的扩展 A 字「䞍」不得出现: ${PinyinEngine.query("chan").candidates}",
            PinyinEngine.query("chan").candidates.contains("䞍"),
        )

        PinyinEngine.setRareTiers(true, false)
        assertTrue(
            "开档 2 后「㺀」应放行: ${PinyinEngine.query("chan").candidates}",
            PinyinEngine.query("chan").candidates.contains("㺀"),
        )
        assertFalse(
            "档 3 未开时「䞍」仍不得出现: ${PinyinEngine.query("chan").candidates}",
            PinyinEngine.query("chan").candidates.contains("䞍"),
        )

        PinyinEngine.setRareTiers(true, true)
        assertTrue(
            "两档齐开后「䞍」应放行: ${PinyinEngine.query("chan").candidates}",
            PinyinEngine.query("chan").candidates.contains("䞍"),
        )
    }

    /**
     * 词条里含**档外**扩展 A 字时，整条按档位过滤（L-67 / L-70 的连带口径）。
     *
     * 判据改成 `< 0x3400` 之后，词库正文里的扩展 A 字（实测 part1-4 共 31 个，其中 20 个档外、
     * 牵涉 37 条词条）与基本区档外字同口径：档外即整条不出现 —— 这是**设计**（档外字不随包），
     * 不是丢字；`build_dicts.py` 生成时会打印这类字的条数，避免再出现「词库不含扩展 A 字」
     * 那种未经实测的结论（BUG.md L-70）。
     */
    @Test
    fun 档外扩展A字的词条要整条过滤() {
        PinyinEngine.loadFromTexts(
            "ba\t爸,㞎\n",                   // 㞎 = 扩展 A 字，且不在任何档位表里（真实数据同款）
            "baba\t㞎㞎\n",                  // part1 真实词条（「㞎㞎」）
            "ba\n",
            commonCharsText = "爸",          // 档 1 只放行「爸」
        )
        val c = PinyinEngine.query("baba").candidates
        assertFalse("含档外扩展 A 字的词条不得进候选: $c", c.contains("㞎㞎"))
        assertFalse("档外扩展 A 字本身也不得进单字候选: $c", c.contains("㞎"))
    }

    /**
     * 判据只按 `char` 判档 ⇒ 非 BMP 字（代理对）永远进不了候选，资产里混不得（L-67 附带）。
     *
     * 引擎对代理项先命中 `code > 0x9FFF → false`，所以非 BMP 字**开了档也打不出来**且零告警；
     * 当前四份资产业已实测为 0 个（`tools/dict_builder/build_dicts.py` 生成时同样按 BMP 过滤）。
     */
    @Test
    fun 三张档表与单字表都不含非BMP字() {
        for (name in listOf("common_chars.txt", "tier2_chars.txt", "tier3_chars.txt", "pinyin_chars.txt")) {
            val bad = charsOf(asset(name)).filter { it > 0xFFFF }
            assertTrue(
                "$name 混入了非 BMP 字（引擎只按 char 判档、开档也永远打不出）: " +
                    bad.joinToString("") { String(Character.toChars(it)) },
                bad.isEmpty(),
            )
        }
    }

    /**
     * 简化表**先于**字级简繁表送达时，字级反推出来的那批（1,948 项异体对）不得丢（L-51）。
     *
     * `setSimplifyText` 原实现是「并入**当前**反查表」（`tradSimp ?: CharArray(...)`），
     * 而 `setSimpTradText` 会整份**重建**反查表 ⇒ 先调 `setSimplifyText` 再调 `setSimpTradText`，
     * 简化表的值全部蒸发。现调用点（`loadCharTiers`）顺序恰好正确，但 API 本身不设防 ——
     * 换个加载顺序、加个新资产、写个新测试都会踩到，用户症状是「繁体模式下有些字折不回简体」。
     *
     * 失败时会看到：「暱」折不回「昵」（简化表的「髮→发」也可能还在、也可能没了，取决于顺序）。
     */
    @Test
    fun 简化表先到也不丢字级反推项() {
        // 反序调用：先简化表、后字级表（现调用点是相反顺序，据此不设防的实现在这里就红）
        PinyinEngine.setSimplifyText("髮\t发\n")
        PinyinEngine.setSimpTradText("昵\t暱\n")

        assertEquals("字级反推的异体对「暱 → 昵」必须保留", "昵", PinyinEngine.toSimplified("暱"))
        assertEquals("先到的简化表条目「髮 → 发」也必须保留", "发", PinyinEngine.toSimplified("髮"))

        // 正序调用是既有路径，两种顺序结果必须一致
        PinyinEngine.setSimpTradText("昵\t暱\n")
        PinyinEngine.setSimplifyText("髮\t发\n")
        assertEquals("正序：暱 → 昵", "昵", PinyinEngine.toSimplified("暱"))
        assertEquals("正序：髮 → 发", "发", PinyinEngine.toSimplified("髮"))
    }

    /** 按码点收集汉字（与 `RareCharsFilterTest` 同口径：只认汉字区，剔除行首拉丁音节） */
    private fun charsOf(text: String): Set<Int> {
        val out = HashSet<Int>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                i += Character.charCount(cp)
                if (cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0x20000..0x3FFFF) out.add(cp)
            }
        }
        return out
    }

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
            val xz = File("$p.xz")
            if (xz.isFile) {
                return org.tukaani.xz.XZInputStream(xz.inputStream())
                    .use { String(it.readBytes(), Charsets.UTF_8) }
            }
        }
        throw AssertionError("未找到 asset $name")
    }
}
