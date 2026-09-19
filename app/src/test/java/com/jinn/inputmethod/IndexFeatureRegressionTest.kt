package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 索引化之后的**功能回归护栏**：智能预测与「残码保留」。
 *
 * 这两项原来都依赖一张 69 万条的「词 → 拼音」全量反向索引；基础词库改走二进制索引后，
 * 反向索引换成「查询时记录候选 → 键」的短期映射（省约 50MB），因此必须钉住它们的**行为**：
 *  - 「残码保留」要**精确**消费到词尾（不是退化成消费整串）；
 *  - 智能预测的产出必须与文本加载路径**逐条一致**。
 */
class IndexFeatureRegressionTest {

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("未找到 asset $name")
    }

    private fun res(name: String): File {
        for (p in listOf("src/test/resources/$name", "app/src/test/resources/$name")) {
            val f = File(p)
            if (f.isFile) return f
        }
        throw AssertionError("未找到测试资源 $name")
    }

    private fun fixtureText(): String = res("dict_index_fixture.txt").readText().trim('\n')

    private fun loadText() {
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            asset("pinyin_chars.txt"), fixtureText(), asset("pinyin_syllables.txt"),
            asset("common_chars.txt"),
        )
    }

    private fun loadIndex() {
        val bytes = XZInputStream(res("dict_index_fixture.bin.xz").inputStream()).use { it.readBytes() }
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromIndexBytes(
            bytes, asset("pinyin_chars.txt"), asset("pinyin_syllables.txt"),
            asset("common_chars.txt"),
        )
    }

    /** fixture 里「键 → 词表」 */
    private fun entries(): List<Pair<String, List<String>>> =
        fixtureText().lineSequence().mapNotNull {
            val tab = it.indexOf('\t')
            if (tab > 0) it.substring(0, tab) to it.substring(tab + 1).split('|') else null
        }.toList()

    @Test
    fun 索引加载后_残码保留精确消费到词尾() {
        // 找一个「键 K + 更长键 K2（以 K 开头）」的组合：输入 K2、选 K 下的词，应当只消费 K
        val all = entries()
        val multi = all.firstOrNull { (_, words) -> words.any { w -> w.length >= 2 } }
            ?: throw AssertionError("fixture 中没有多字词")
        val (key, words) = multi
        val word = words.first { it.length >= 2 }

        loadIndex()
        val candidates = PinyinEngine.query(key).candidates
        assertTrue("索引加载下应能查到「$key」", candidates.isNotEmpty())

        // 输入 = 该键 + 一个额外音节（模拟连打），选该词 → 只应消费该键的长度
        val extra = "ma"
        val input = key + extra
        val c = PinyinEngine.consumption(input, word)
        assertEquals("应精确消费到词尾（$key 的长度）", key.length, c.quanpinChars)
        assertTrue("音节数应为正", c.syllables > 0)
    }

    @Test
    fun 索引加载后_智能预测与文本加载逐条一致() {
        // 探针只取真实会出现的情形：查某个键、从展示出来的候选里挑词做预测。
        // 索引路径只记录「展示过的候选 → 键」，这是有意为之的内存取舍；
        // 预测永远发生在刚选中的候选上，所以不影响功能。
        // 探针要在已加载状态下才能查到候选，所以先用索引路径取一遍。
        val multiKeys = entries().take(60).map { it.first }
        loadIndex()
        val probes = multiKeys.mapNotNull { key ->
            PinyinEngine.query(key).candidates.firstOrNull()?.let { key to it }
        }.ifEmpty { throw AssertionError("fixture 未产生任何候选") }

        loadText()
        val byText = probes.map { (key, word) ->
            PinyinEngine.query(key)
            PinyinEngine.predict(word)
        }

        loadIndex()
        val byIndex = probes.map { (key, word) ->
            PinyinEngine.query(key)
            PinyinEngine.predict(word)
        }

        val diffs = probes.indices.filter { byText[it] != byIndex[it] }
        assertEquals(
            "预测不一致（键/词 → 文本 vs 索引）: " +
                diffs.take(5).joinToString { "「${probes[it].first}」${probes[it].second} → ${byText[it]} vs ${byIndex[it]}" },
            emptyList<Int>(), diffs,
        )
        assertTrue("预测应至少有一个非空样本（否则对拍无意义）", byText.any { it.isNotEmpty() })
    }

    /**
     * 收尾复位：PinyinEngine 是单例，本文件会用真实 asset 注入词库；
     * 不复位会把「真实单字表/音节表」留给后续测试类，破坏它们的既有假设。
     */
    @After
    fun tearDown() {
        PinyinEngine.resetForTest()
    }
}
