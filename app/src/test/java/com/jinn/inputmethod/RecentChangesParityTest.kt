package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 近期改动的模拟测试（针对"改完可能悄悄改变行为"的风险，用对拍/边界矩阵验证）。
 *
 * 四组：
 *  1. 分词剪枝 ≡ 未剪枝参考实现（逐例对拍切分结果与顺序）；
 *  2. 分片解压 ≡ readBytes()（含空/1 字节/跨分片边界/随机内容）；
 *  3. 长按退格清拼音的判据边界矩阵；
 *  4. 长按连删在「拼音被自己删空」后必须停手，不得落到删已上屏正文那一步。
 */
class RecentChangesParityTest {

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
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
            indexBytes(), asset("pinyin_chars.txt"), asset("pinyin_syllables.txt"), asset("common_chars.txt"),
        )
    }

    @After
    fun tearDown() = PinyinEngine.resetForTest()

    // ── ① 分词剪枝 vs 未剪枝参考实现 ─────────────────────────────
    // 参考实现刻意照抄修复前的 DFS（只有「已完成路径数」上限、没有后缀可行性剪枝）。
    // 合法音节表从引擎的合法音节全集取，保证两边用同一份数据。
    private fun referencePaths(input: String, syllables: Set<String>, maxPaths: Int = 16): List<List<String>> {
        val out = ArrayList<List<String>>()
        fun dfs(pos: Int, cur: MutableList<String>) {
            if (out.size >= maxPaths) return
            if (pos == input.length) {
                out.add(cur.toList())
                return
            }
            var end = (pos + 6).coerceAtMost(input.length)
            while (end > pos) {
                val cand = input.substring(pos, end)
                if (syllables.contains(cand)) {
                    cur.add(cand)
                    dfs(end, cur)
                    cur.removeAt(cur.size - 1)
                }
                end--
            }
        }
        dfs(0, ArrayList())
        return out
    }

    @Test
    fun 剪枝后的切分与未剪枝完全一致() {
        loadEngine()
        val syllables = asset("pinyin_syllables.txt").lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertTrue("合法音节表不该是空的", syllables.size > 300)

        val rnd = kotlin.random.Random(20260918)
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val cases = ArrayList<String>()
        // 边界：空、单字符、最长音节（6 字符）、跨长度
        cases += listOf("", "a", "z", "zh", "zhuang", "zhuanga", "j", "g", "h", "jgh")
        // 真实拼音与超长拼接
        val real = "zhongguorenminzhengzhixieshanghuiyi"
        cases += listOf(real, real.dropLast(1) + "z", real + "z", "nihao", "xian", "fangan")
        for (len in listOf(2, 3, 5, 7, 12, 20, 40, 120, 240)) {
            cases += real.repeat(12).substring(0, len)
            cases += "a".repeat(len)
        }
        // 随机：前缀能切但尾巴切不通的形态，正是当初炸掉 DFS 的那类
        repeat(300) {
            val n = 1 + rnd.nextInt(28)
            cases += (1..n).map { letters[rnd.nextInt(26)] }.joinToString("")
        }
        repeat(200) {
            val n = 1 + rnd.nextInt(10)
            val syls = listOf("ba", "na", "lan", "zhong", "guo", "ren", "min", "zhi", "xie", "shang")
            cases += (1..n).map { syls[rnd.nextInt(syls.size)] }.joinToString("") + "z"
        }

        var checked = 0
        for (input in cases) {
            val got = PinyinEngine.enumerateSegmentPaths(input)
            val want = referencePaths(input, syllables)
            assertEquals("切分结果不一致，input=\"$input\"", want, got)
            checked++
        }
        println("PARITY 分词对拍 $checked 例全部一致（含长度 0/1/240、随机 300、坏尾 200）")
    }

    // ── ② 分片解压 vs readBytes() ────────────────────────────────
    private fun xz(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        XZOutputStream(bos, org.tukaani.xz.LZMA2Options()).use { it.write(data) }
        return bos.toByteArray()
    }

    @Test
    fun 分片解压与readBytes逐字节一致() {
        val rnd = kotlin.random.Random(7)
        val sizes = listOf(0, 1, 2, 255, 256 * 1024 - 1, 256 * 1024, 256 * 1024 + 1, 1024 * 1024, 1024 * 1024 + 7, 3 * 1024 * 1024)
        for (size in sizes) {
            val data = ByteArray(size).also { rnd.nextBytes(it) }
            val compressed = xz(data)
            // 生产调用是 XZInputStream(raw).use { readWithYields(it) } —— XZ 包装在**外面**
            val viaChunks = XZInputStream(ByteArrayInputStream(compressed)).use {
                PinyinEngine.readWithYields(it)
            }
            val viaReadBytes = XZInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            assertEquals("长度不一致 size=$size", viaReadBytes.size, viaChunks.size)
            assertTrue("内容不一致 size=$size", viaReadBytes.contentEquals(viaChunks))
        }
        // 真实索引字节也过一遍分片读取器（约 14.7MB，跨很多个 256KB 分片）。
        // 注意 indexBytes() 返回的是**已解压**的索引，这里直接比"分片读出来的 == 原始字节"。
        val real = indexBytes()
        val realChunks = PinyinEngine.readWithYields(ByteArrayInputStream(real))
        assertEquals("真实索引长度不一致", real.size, realChunks.size)
        assertTrue("真实索引内容不一致", real.contentEquals(realChunks))
        println("PARITY 分片解压 ${sizes.size} 个尺寸 + 真实资产(14.7MB) 逐字节一致")
    }

    // ── ③ 已于 2026-09-20 移除 ─────────────────────────────────────
    // 原「长按退格整串清空拼音」判据（shouldClearComposingOnHold）及其配套闸门
    // （shouldStopRepeatOnExhaustedComposing）随该手势一并删除：清空候选改由候选栏
    // 右侧的 ✕ 按钮显式触发，删除键长按恢复为标准的「逐字连删、删空拼音后继续删已上屏」。
    // 相关历史用例（长按清拼音判据的边界 / 必须以按下时长度判据 / 连删不得继续删已上屏）
    // 已随功能移除，不再保留。
}
