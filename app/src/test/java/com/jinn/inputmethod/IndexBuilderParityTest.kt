package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * Stage 2 护栏：
 *  1. **构建器对拍** —— 设备端 Kotlin 构建器产出的索引字节，必须与构建脚本（Python）产出的
 *     **完全一致**（同一格式、同一顺序），否则两类索引将无法互换读取；
 *  2. **逐段合并语义** —— 「运行时 ∪ 基础索引 ∪ 可选索引」的结果必须与旧实现一致：
 *     先到先得 + 去重，基础在前、可选包追加。
 */
class IndexBuilderParityTest {

    private fun res(name: String): File {
        for (p in listOf("src/test/resources/$name", "app/src/test/resources/$name")) {
            val f = File(p)
            if (f.isFile) return f
        }
        throw AssertionError("未找到测试资源 $name")
    }

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("未找到 asset $name")
    }

    private fun fixtureText(): String = res("dict_index_fixture.txt").readText().trim('\n')

    private fun fixtureBytes(): ByteArray =
        XZInputStream(res("dict_index_fixture.bin.xz").inputStream()).use { it.readBytes() }

    @Test
    fun 设备端构建器与构建脚本产出完全一致() {
        val pythonBuilt = fixtureBytes()
        val stamp = PhraseIndex.of(pythonBuilt)!!.sourceStamp      // 用同一摘要才能逐字节比对
        val kotlinBuilt = PhraseIndex.build(fixtureText().lineSequence(), stamp)
        assertArrayEquals("Kotlin 构建器与 Python 构建脚本产出的索引必须逐字节一致",
            pythonBuilt, kotlinBuilt)
    }

    @Test
    fun 索引可被两端构建器互相读取() {
        val stamp = 123456789L
        val mine = PhraseIndex.build(fixtureText().lineSequence(), stamp)
        val parsed = PhraseIndex.of(mine) ?: throw AssertionError("自建索引无法解析")
        assertEquals(stamp, parsed.sourceStamp)
        for (line in fixtureText().lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            assertEquals(
                "键 ${line.substring(0, tab)} 的词表读回不一致",
                line.substring(tab + 1).split('|'),
                parsed.wordsFor(line.substring(0, tab))?.toList(),
            )
        }
    }

    @Test
    fun 可选索引按旧语义合并_基础在前去重追加() {
        val chars = asset("pinyin_chars.txt")
        val syllables = asset("pinyin_syllables.txt")

        // 基础：nihao → 你好|你号 ；可选包：nihao → 你好|拟好|尼豪（含重复项与非重复项）
        val baseText = "nihao\t你好|你号\n"
        val optionalText = "nihao\t你好|拟好|尼豪\n"

        PinyinEngine.resetForTest()
        PinyinEngine.loadFromIndexBytes(
            PhraseIndex.build(baseText.lineSequence(), 1L), chars, syllables, null,
        )
        val optionalIndex = PhraseIndex.of(PhraseIndex.build(optionalText.lineSequence(), 2L))!!
        PinyinEngine.setOptionalIndexesForTest(listOf(optionalIndex))

        val words = PinyinEngine.query("nihao").candidates.toList()
        // 期望：基础在前、可选包中未出现过的追加（「你好」重复项不重复出现）
        assertTrue("应包含基础词与可选包追加词: $words", words.contains("你号") && words.contains("尼豪"))
        assertEquals("重复词不应出现两次", 1, words.count { it == "你好" })
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
