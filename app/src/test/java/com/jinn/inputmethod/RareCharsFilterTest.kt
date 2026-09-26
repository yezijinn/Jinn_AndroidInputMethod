package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 生僻字过滤测试（纯 JVM，用 [PinyinEngine.loadFromTexts] 注入）。
 *
 * 判定标准：《通用规范汉字表》一级(3500) + 二级(3000) + 三级(1407) 为默认加载档，
 * 表外字（繁体 / 异体 / 日韩 / 扩展区）只在「加更多生僻字」开启时加载。默认：
 *  - 表外单字不进单字表，也就不进候选；含表外字的词整条丢弃；
 *  - 三级字（囧 / 淼 / 喆 / 昇 一类人名地名用字）与一二级同样可用。
 *
 * 关键点是「加载时过滤」而非「加载后过滤」，被跳过的数据从未进入 HashMap，
 * 这才是真正降低内存占用的原因，本测试守住该行为。
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

    /** 一二级字表：刻意不含「饕 / 餮 / 噩」（后两个分属三级与表外） */
    private val commonChars = "企乞起鹅额饿且切涛掏铁"

    /** 三级字表：只收「噩」 —— 它进默认档，「饕 / 餮」仍属表外 */
    private val tier3Chars = "噩"

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

    /** 三级字是规范汉字，与一二级同档默认加载（2026-09-26 起）；表外字仍在默认档之外 */
    @Test
    fun 三级字进默认档而表外字仍被拦下() {
        PinyinEngine.loadFromTexts(chars, phrases, syllables, commonChars, tier3Chars)
        val got = PinyinEngine.query("e").candidates
        assertTrue("三级字「噩」应默认可用: $got", got.contains("噩"))
        assertFalse("表外字「餮」不得出现", PinyinEngine.query("tie").candidates.contains("餮"))
    }

    /** 「加更多生僻字」开启：表外字与表外词一并放行 */
    @Test
    fun 加更多生僻字开启后表外字可用() {
        PinyinEngine.loadFromTexts(chars, phrases, syllables, commonChars, tier3Chars, rareChars = true)
        assertTrue("表外单字「饕」应可用", PinyinEngine.query("tao").candidates.contains("饕"))
        assertTrue("含表外字的词「饕餮」应可用", PinyinEngine.query("taotie").candidates.contains("饕餮"))
    }

    /**
     * 随包的默认档字表：三级字表必须覆盖用户报告的那批人名地名用字。
     *
     * 判据错成「一二级一刀切」时，这批字默认打不出（用户 2026-09-25 报告）；
     * 换了一张不覆盖它们的字表（例如误用某个更窄的字频表）同样会在这里变红。
     */
    @Test
    fun 默认档字表覆盖常用人名地名用字() {
        val common = charsOf(asset("common_chars.txt"))
        val tier3 = charsOf(asset("tier3_chars.txt"))
        // 一二级表按规范表 6500 字维护（表内另有极少数增补字，故不钉死）
        assertTrue("一二级表规模异常: ${common.size}", common.size >= 6500)
        assertTrue("三级表规模异常: ${tier3.size}", tier3.size > 1000)
        assertEquals("两张表不该有交集", emptySet<Char>(), common intersect tier3)
        assertTrue(
            "三级表必须落在基本区（位图只覆盖 0x4E00~0x9FFF）",
            tier3.all { it.code in 0x4E00..0x9FFF },
        )
        val missing = "囧欻扽挼覅淼喆昇堃".filterNot { it in common || it in tier3 }
        assertEquals("默认档打不出的字: $missing", "", missing)
    }

    private fun charsOf(text: String): Set<Char> {
        val out = HashSet<Char>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            out.addAll(t.toList())
        }
        return out
    }
}
