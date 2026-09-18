package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用户词频学习的纯逻辑护栏：排序稳定（学过的提前、其余保持词库原序、不学就零变化）、
 * 衰减跟 `algo::formula_d` 一致、文件读写往返一致且能扛脏数据、关掉开关后不学也不改排序。
 */
class UserFrequencyTest {

    @After
    fun tearDown() = UserFrequency.resetForTest()

    // ── 排序 ────────────────────────────────────────────────────────────────

    @Test
    fun 未学习时排序与原数组完全一致() {
        UserFrequency.resetForTest()
        val words = arrayOf("你好", "拟好", "你")
        assertArrayEquals(words, UserFrequency.rank(words))
    }

    @Test
    fun 学过的高权重提前且其余保持原序() {
        UserFrequency.resetForTest()
        UserFrequency.putForTest("拟好", 5.0, 20000)
        UserFrequency.putForTest("你", 2.0, 20000)
        val got = UserFrequency.rank(arrayOf("你好", "拟好", "你", "尼", "泥"))
        assertArrayEquals(arrayOf("拟好", "你", "你好", "尼", "泥"), got)
    }

    @Test
    fun 权重相同时保持词库原序_稳定排序() {
        UserFrequency.resetForTest()
        for (w in listOf("甲", "乙", "丙")) UserFrequency.putForTest(w, 3.0, 20000)
        assertArrayEquals(arrayOf("甲", "乙", "丙"), UserFrequency.rank(arrayOf("甲", "乙", "丙")))
    }

    // ── 衰减 ────────────────────────────────────────────────────────────────

    @Test
    fun 累加一次即加一并刷新tick() {
        UserFrequency.resetForTest()
        UserFrequency.remember("虚拟")
        assertEquals(1.0, UserFrequency.weightForTest("虚拟")!!, 1e-9)
        UserFrequency.remember("虚拟")
        assertEquals(2.0, UserFrequency.weightForTest("虚拟")!!, 1e-9)
    }

    @Test
    fun 解析时按天衰减_半衰期约139天() {
        UserFrequency.resetForTest()
        val now = 20_000
        // 139 天前记了 10 次 → 衰减到约 5（exp(-139/200) ≈ 0.5）
        val text = "# jinn user_freq v1\n久远词\t10.000\t${now - 139}\n"
        val parsed = UserFrequency.parse(text, now)
        assertEquals(5.0, parsed["久远词"]!!.first, 0.2)
        // 当天记的不会被衰减
        val today = UserFrequency.parse("# jinn user_freq v1\n今天词\t10.000\t$now\n", now)
        assertEquals(10.0, today["今天词"]!!.first, 1e-9)
    }

    @Test
    fun 衰减到阈值以下的条目被丢弃() {
        UserFrequency.resetForTest()
        val now = 20_000
        // 1.0 分、500 天前 → 1.0 * exp(-2.5) ≈ 0.082 < 0.15 → 丢弃
        val parsed = UserFrequency.parse("# jinn user_freq v1\n旧词\t1.000\t${now - 500}\n", now)
        assertTrue("应被丢弃: $parsed", parsed.isEmpty())
    }

    @Test
    fun 系统时钟回拨时权重不被放大() {
        UserFrequency.resetForTest()
        // 先记一次（day = 今天），再模拟「时钟回拨」：内存里塞一条来自"未来"的记录
        UserFrequency.remember("虚拟")
        val w0 = UserFrequency.weightForTest("虚拟")!!
        UserFrequency.putForTest("虚拟", w0, 99_999)      // 未来 day
        UserFrequency.remember("虚拟")
        val w1 = UserFrequency.weightForTest("虚拟")!!
        // 钳位后应只 +1（不衰减、也不放大）
        assertEquals("回拨后应只加 1，不应被 exp(正数) 放大", w0 + 1.0, w1, 1e-6)
    }

    @Test
    fun 含制表符或换行的词不学习() {
        UserFrequency.resetForTest()
        UserFrequency.remember("坏\t词")
        UserFrequency.remember("坏\n词")
        UserFrequency.remember("好词")
        assertEquals(1, UserFrequency.sizeForTest())
        assertEquals(null, UserFrequency.weightForTest("坏\t词"))
        assertEquals(1.0, UserFrequency.weightForTest("好词")!!, 1e-9)
    }

    // ── 序列化 ──────────────────────────────────────────────────────────────

    @Test
    fun 序列化往返一致() {
        UserFrequency.resetForTest()
        UserFrequency.putForTest("你好", 3.5, 20000)
        UserFrequency.putForTest("拟好", 1.25, 20001)
        val text = UserFrequency.render()
        assertTrue("应带表头", text.startsWith("# jinn user_freq v1"))
        // 同一天解析：值不变（跨天才会按天衰减，已有专门用例覆盖）
        val back = UserFrequency.parse(text, 20000)
        assertEquals(3.5, back["你好"]!!.first, 1e-9)
        assertEquals(1.25, back["拟好"]!!.first, 1e-9)
        assertEquals(20000, back["你好"]!!.second)
        assertEquals(20001, back["拟好"]!!.second)
    }

    @Test
    fun 脏数据被跳过而不是崩溃() {
        UserFrequency.resetForTest()
        val text = buildString {
            append("# jinn user_freq v1\n")
            append("\n")                       // 空行
            append("缺字段\n")                  // 字段不足
            append("权重坏\tabc\t100\n")        // 权重非法
            append("天数坏\t1.0\txx\n")         // 天数非法
            append("负权重\t-2.0\t100\n")       // 非法权重
            append("好词\t2.0\t100\n")          // 正常
        }
        val parsed = UserFrequency.parse(text, 100)
        assertEquals(setOf("好词"), parsed.keys)
    }

    // ── 开关 ────────────────────────────────────────────────────────────────

    @Test
    fun 关闭开关后不学习也不改排序() {
        UserFrequency.resetForTest()
        UserFrequency.putForTest("拟好", 9.0, 20000)
        // load 会设定开关；这里直接走内部开关路径：模拟关闭
        UserFrequency.loadForTest(enabled = false)
        val words = arrayOf("你好", "拟好")
        assertArrayEquals("关闭后排序不应变化", words, UserFrequency.rank(words))
        UserFrequency.remember("拟好")
        assertEquals("关闭后不应累加", 9.0, UserFrequency.weightForTest("拟好")!!, 1e-9)
    }

    // ── 原子写 ──────────────────────────────────────────────────────────────

    @Test
    fun 原子写落盘且不留临时文件() {
        val dir = File(System.getProperty("java.io.tmpdir"), "jinn-uf-test")
        dir.mkdirs()
        val f = File(dir, "user_freq.txt")
        assertTrue(UserFrequency.writeAtomically(f, "hello"))
        assertEquals("hello", f.readText())
        assertTrue("不应残留 .tmp", !File(dir, "user_freq.txt.tmp").exists())
        assertTrue("覆盖写也应成功", UserFrequency.writeAtomically(f, "world"))
        assertEquals("world", f.readText())
        dir.deleteRecursively()
    }
}
