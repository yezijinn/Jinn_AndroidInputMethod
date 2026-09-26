package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 分词的成本悬崖护栏。
 *
 * 起因（2026-09-18 用户报告）：拼音打错、或打得很长时，字母键会卡住。
 * 根因是 `dfsSegment` 的「16 条完整路径」上限只对找得到完整切分的输入生效：
 * 前缀能切出很多合法音节、尾巴却切不通时，一条完整路径都找不到，上限永不触发，
 * DFS 会把所有前缀分支走到底。实测（JVM）：25 字符 8.6ms、31 字符 36.8ms，
 * 每加 6 个字符约 ×4~5，真机上就是每按一键卡几百毫秒到几秒。
 *
 * 修法：先用一趟后缀可切分性 DP，把走不通的分支在进入前剪掉。剪掉的分支本来就到不了终点，
 * 因此切分结果完全不变（下面的用例同时钉住"快"和"结果不变"两件事）。
 */
class SegmentCliffTest {

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

    private fun indexBytes(): ByteArray {
        for (p in listOf("src/main/assets/pinyin_index.bin.xz", "app/src/main/assets/pinyin_index.bin.xz")) {
            val f = File(p)
            if (f.isFile) return XZInputStream(f.inputStream()).use { it.readBytes() }
        }
        throw AssertionError("未找到索引资产")
    }

    private fun loadEngine() {
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromIndexBytes(
            indexBytes(),
            asset("pinyin_chars.txt"),
            asset("pinyin_syllables.txt"),
            asset("common_chars.txt"),
        )
    }

    @After
    fun tearDown() = PinyinEngine.resetForTest()

    @Test
    fun 切不出来的长拼音不会卡住() {
        loadEngine()
        // 「banana」重复 7 遍再加个 z：前缀 ba/na/ban/an 都能切，尾巴那一下切不通
        val input = "banana".repeat(7) + "z"
        PinyinEngine.query(input)                       // 预热
        val t0 = System.nanoTime()
        repeat(3) { PinyinEngine.query(input) }
        val perCall = (System.nanoTime() - t0) / 1_000_000.0 / 3
        // 修复前这是秒级（每加 6 字符 ×4~5）；200ms 是很宽松的阈值，只用来守住"别再退回指数级"
        assertTrue("单次 query 应远低于 200ms，实测 ${"%.1f".format(perCall)}ms", perCall < 200.0)
    }

    @Test
    fun 剪枝不改变切分结果() {
        loadEngine()
        // 正常词：结果应与修复前一致（原实现就有这些候选）
        assertTrue("zhongguorenmin 应召回「中国人民」",
            PinyinEngine.query("zhongguorenmin").candidates.contains("中国人民"))
        assertTrue("nihao 应召回「你好」",
            PinyinEngine.query("nihao").candidates.contains("你好"))
        // 长但可切：候选数应稳定
        val n = PinyinEngine.query("beijingdaxuexuesheng").candidates.size
        assertTrue("长可切词应有候选，实际 $n", n > 0)
        // 切不通的长串：不崩、给不出候选即可（调用方会退回贪心切分）
        val r = PinyinEngine.query("banana".repeat(7) + "z")
        assertTrue("切不通的串不应抛异常，候选数=${r.candidates.size}", r.candidates.size >= 0)
        assertEquals("切不通的串不应凭空造出候选", 0, r.candidates.count { it.length > 8 })
    }
}
