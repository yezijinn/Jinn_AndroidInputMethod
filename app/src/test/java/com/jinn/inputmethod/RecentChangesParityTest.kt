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

    // ── ③ 长按退格清拼音的判据边界矩阵 ───────────────────────────
    @Test
    fun 长按清拼音判据的边界() {
        // 阈值：1200ms / 12 字符
        assertFalse("差 1ms 不该清", shouldClearComposingOnHold(1199, 100))
        assertTrue("正好到阈值该清", shouldClearComposingOnHold(1200, 12))
        assertTrue("超过阈值该清", shouldClearComposingOnHold(1201, 12))
        assertFalse("差 1 字符不该清", shouldClearComposingOnHold(5000, 11))
        assertTrue("正好 12 字符该清", shouldClearComposingOnHold(HOLD_TO_CLEAR_COMPOSING_MS, LONG_COMPOSING_TO_CLEAR))
        // 组合矩阵：任一条件不满足都不清
        for (held in listOf(0L, 380L, 1199L)) {
            assertFalse("held=$held 不该清", shouldClearComposingOnHold(held, 999))
        }
        for (len in listOf(0, 1, 11)) {
            assertFalse("len=$len 不该清", shouldClearComposingOnHold(99999L, len))
        }
        // 常见场景：按着删几个字（不该触发）/ 长垃圾串（该触发）
        assertFalse("按 0.8 秒删 6 个字的拼音：不该清", shouldClearComposingOnHold(800, 6))
        assertTrue("长按 1.5 秒、还剩 30 字符：该清", shouldClearComposingOnHold(1500, 30))
        println("PARITY 长按清拼音判据边界矩阵通过（阈值 1200ms / 12 字符）")
    }

    /**
     * 判据的第二个参数必须是**按下时**的长度，不能是「当前」长度。
     *
     * 回归：连删循环每个 tick 都先删一位再重排（0.38s 起每 55ms 一次），等到 1200ms
     * 门槛时拼音串已被它自己删短十几位。按「当前长度」判，12 字符的串永远清不掉——
     * 它会先被逐字删空，之后继续删**已上屏正文**，与该手势「只整串清拼音、不动已上屏」
     * 的约定相反；实测算下来初始串要 ≥28 字符才可能命中。
     */
    @Test
    fun 长按清空必须以按下时的长度判据() {
        // 模拟 12 字符拼音串：DOWN 删 1 位 → 380ms 起每 55ms 删 1 位
        var currentLen = 12 - 1
        var held = 380L
        var cleared = false
        while (!cleared && currentLen > 0 && held <= 3_000L) {
            cleared = shouldClearComposingOnHold(held, currentLen)   // 旧口径：当前长度
            if (!cleared) {
                currentLen--
                held += 55L
            }
        }
        assertFalse("按当前长度判：12 字符的串清不掉（走到 len=$currentLen 仍为 false）", cleared)
        // 新口径：传按下时的长度，同一时刻即可命中
        assertTrue("传按下时长度应在 1200ms 命中", shouldClearComposingOnHold(1_200L, 12))
        println("PARITY 长按清空判据基准（按下时长度）通过")
    }

    /**
     * 连删循环必须在「拼音串被自己删空」时停手，不能接着删已上屏正文。
     *
     * 时序（与视图里的实现同参）：ACTION_DOWN 立即删 1 位并把循环排在 380ms 后，
     * 之后每 55ms 一个 tick；门槛 tick 落在 1205ms，因此门槛到达前共 16 次删除。
     * 按下长度 L 的串在第 L 次删除后即为空 ⇒ L ≤ 16 都会在门槛前被删空，
     * 其中 12 ≤ L ≤ 15 正是「本该只清拼音」的区间：没有闸门时，剩余 16−L 个 tick
     * 会落到 listener.onBackspace()，把用户已上屏的正文删掉 1~4 个字符。
     */
    @Test
    fun 长按连删在拼音删空后不得继续删已上屏() {
        /** 模拟一次完整长按：返回落到「已上屏正文」的删除次数 */
        fun hostDeletes(composingLenAtDown: Int, withGuard: Boolean): Int {
            var composing = composingLenAtDown - 1   // ACTION_DOWN 先删一位
            var host = 0
            var held = BACKSPACE_FIRST_DELAY_MS
            repeat(200) {
                if (shouldClearComposingOnHold(held, composingLenAtDown)) return host
                if (withGuard &&
                    shouldStopRepeatOnExhaustedComposing(composing == 0, composingLenAtDown)
                ) {
                    return host
                }
                if (composing > 0) composing-- else host++
                held += BACKSPACE_INTERVAL_MS
            }
            return host
        }

        // 修复前：12~15 字符的串会把已上屏正文删掉 4/3/2/1 个字符
        assertEquals(4, hostDeletes(12, withGuard = false))
        assertEquals(1, hostDeletes(15, withGuard = false))
        // 修复后：这四档一律不碰已上屏
        for (len in 12..15) {
            assertEquals("按下长度 $len 不得删已上屏", 0, hostDeletes(len, withGuard = true))
        }
        // ≥16 字符：门槛 tick 到达时拼音串还在，走的是「整串清拼音」，同样不碰已上屏
        assertEquals(0, hostDeletes(16, withGuard = true))
        assertEquals(0, hostDeletes(40, withGuard = true))
        // <12 字符：语义本就是「先清拼音、再删已上屏」，闸门不得把它一起挡掉
        assertTrue("11 字符的串仍应继续删已上屏", hostDeletes(11, withGuard = true) > 0)
        println("PARITY 连删循环「拼音删空即停」通过")
    }

    private companion object {
        /** 与视图里的 backspaceRepeatDelayMs 同参 */
        const val BACKSPACE_FIRST_DELAY_MS = 380L

        /** 与视图里的 backspaceRepeatIntervalMs 同参 */
        const val BACKSPACE_INTERVAL_MS = 55L
    }
}
