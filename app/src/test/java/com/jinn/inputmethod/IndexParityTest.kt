package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * **索引加载与文本加载的逐条对拍**（P1 Stage 1 的核心护栏）。
 *
 * 词库从「文本 + HashMap」改成「二进制索引 + 二分查找」后，最容易出的问题不是崩，
 * 而是**候选悄悄变了**（顺序、去重、变体回退、生僻字过滤）。因此这里用一对
 * 同源 fixture（`dict_index_fixture.txt` 与 `dict_index_fixture.bin.xz`，由
 * `tools/dict_builder/build_dict_index.py --fixture` 同时产出）分别喂给两条加载路径，
 * 对同一批输入逐条比对候选。
 *
 * 单字表/音节表用真实 asset（`src/main/assets/`）——它们不参与本次重构，两条路径共用，
 * 因此比对结果只反映「短语表」这条链路的差异。
 */
class IndexParityTest {

    private fun fixtureDir(): File {
        for (p in listOf("src/test/resources", "app/src/test/resources")) {
            val d = File(p)
            if (d.isDirectory) return d
        }
        throw AssertionError("未找到测试资源目录")
    }

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("未找到 asset $name")
    }

    private fun fixtureText(): String =
        File(fixtureDir(), "dict_index_fixture.txt").readText().trim('\n')

    private fun fixtureIndexBytes(): ByteArray =
        XZInputStream(File(fixtureDir(), "dict_index_fixture.bin.xz").inputStream()).use { it.readBytes() }

    /** 从 fixture 文本里取所有键（每行第一列），作为对拍输入 */
    private fun fixtureKeys(): List<String> =
        fixtureText().lineSequence().mapNotNull { it.substringBefore('\t').ifEmpty { null } }.toList()

    @Test
    fun 文本加载与索引加载_候选完全一致() {
        val text = fixtureText()
        val keys = fixtureKeys()
        assertTrue("fixture 键数异常: ${keys.size}", keys.size >= 100)

        val chars = asset("pinyin_chars.txt")
        val syllables = asset("pinyin_syllables.txt")
        val commonChars = asset("common_chars.txt")

        // 路径 A：文本加载
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(chars, text, syllables, commonChars)
        val byText = keys.associateWith { PinyinEngine.query(it).candidates.toList() }

        // 路径 B：索引加载
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromIndexBytes(fixtureIndexBytes(), chars, syllables, commonChars)
        val byIndex = keys.associateWith { PinyinEngine.query(it).candidates.toList() }

        val diffs = keys.filter { byText[it] != byIndex[it] }
        assertEquals("候选不一致的输入（前 5 个）: ${diffs.take(5)}", emptyList<String>(), diffs)
    }

    @Test
    fun 索引加载_单键与部分输入同样一致() {
        val text = fixtureText()
        val chars = asset("pinyin_chars.txt")
        val syllables = asset("pinyin_syllables.txt")
        val commonChars = asset("common_chars.txt")
        // 取若干键的前缀作为「打到一半」的输入，覆盖部分音节路径
        val inputs = fixtureKeys().take(40).flatMap { listOf(it.take(2), it.take(3)) }.distinct()

        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(chars, text, syllables, commonChars)
        val byText = inputs.associateWith { PinyinEngine.query(it).candidates.toList() }

        PinyinEngine.resetForTest()
        PinyinEngine.loadFromIndexBytes(fixtureIndexBytes(), chars, syllables, commonChars)
        val byIndex = inputs.associateWith { PinyinEngine.query(it).candidates.toList() }

        val diffs = inputs.filter { byText[it] != byIndex[it] }
        assertEquals("部分输入候选不一致（前 5 个）: ${diffs.take(5)}", emptyList<String>(), diffs)
    }

    @Test
    fun 索引加载_词表与文本逐键等价() {
        val text = fixtureText()
        val expected = HashMap<String, List<String>>()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab > 0) expected[line.substring(0, tab)] = line.substring(tab + 1).split('|')
        }
        val index = PhraseIndex.of(fixtureIndexBytes()) ?: throw AssertionError("索引解析失败")
        assertEquals("索引键数应与文本一致", expected.size, index.size)
        for ((key, words) in expected) {
            val got = index.wordsFor(key)?.toList()
            assertEquals("键 $key 的词表不一致", words, got)
        }
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
