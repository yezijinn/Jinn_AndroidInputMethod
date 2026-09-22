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

    /**
     * 非有限权重必须当脏数据丢掉。
     *
     * 回归：`"NaN".toDoubleOrNull()` / `"Infinity".toDoubleOrNull()` 都不是 null
     * （Kotlin 的取值筛选用正则显式放行这两个字面量），而 NaN 与任何数比较恒为 false，
     * 于是 `weight <= 0.0` 与 `decayed < MIN_WEIGHT` 两条判据同时失效、脏值一路进到
     * [UserFrequency.rank]；`Double.compare` 又把 NaN 视为最大值，这条坏词会永久
     * 霸占该拼音的首候选（空格取首候选等于一直上屏它），并被 render 原样写回文件。
     */
    @Test
    fun 非有限权重被当作脏数据丢弃() {
        UserFrequency.resetForTest()
        val text = buildString {
            append("# jinn user_freq v1\n")
            append("NaN词\tNaN\t100\n")
            append("无穷词\tInfinity\t100\n")
            append("负无穷词\t-Infinity\t100\n")
            append("正常词\t2.0\t100\n")
        }
        val parsed = UserFrequency.parse(text, 100)
        assertEquals(setOf("正常词"), parsed.keys)
        // 排序侧同样不受影响：坏词根本没进来
        assertArrayEquals(
            arrayOf("NaN词", "正常词"),
            UserFrequency.rank(arrayOf("NaN词", "正常词")),
        )
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

    // ── flush 的失败语义 ───────────────────────────────────────────────────

    /**
     * 回归：`flush` 曾先清 dirty 再写盘，写失败（磁盘满 / IO 错误）后这次学习
     * 再没有任何路径会重试（下一次 flush 直接因 `!dirty` 返回），而 flush 的职责
     * 正是「保证最后一次学习不丢」。现在与 `saveNow` 对齐：写盘成功才清 dirty。
     */
    @Test
    fun 写盘失败时保留dirty以便下次重试() {
        UserFrequency.resetForTest()
        // file 为 null 时 remember 不会触发尾沿落盘（scheduleSave 直接返回），只置 dirty
        UserFrequency.remember("词")
        assertTrue("学习后应为脏", UserFrequency.isDirtyForTest())

        // 目标父目录不存在 → writeAtomically 必然失败（不依赖平台的权限语义）
        val missing = File(System.getProperty("java.io.tmpdir"), "jinn-uf-missing-${System.nanoTime()}")
        UserFrequency.setFileForTest(File(missing, "user_freq.txt"))
        UserFrequency.flush()
        assertTrue("写盘失败后必须保留 dirty，否则这次学习永久丢失", UserFrequency.isDirtyForTest())

        // 可写路径：flush 成功后 dirty 清除、文件写出
        val dir = File(System.getProperty("java.io.tmpdir"), "jinn-uf-flush-ok")
        dir.mkdirs()
        val ok = File(dir, "user_freq.txt")
        UserFrequency.setFileForTest(ok)
        UserFrequency.flush()
        assertTrue("写盘成功后 dirty 应清除", !UserFrequency.isDirtyForTest())
        assertTrue("文件应已写出", ok.isFile)
        dir.deleteRecursively()
        // 收尾复位：本用例把 file 指到了临时目录，不复位会跨测试类残留（其它用例在 @Before 各自 reset）
        UserFrequency.resetForTest()
    }

    // ── 防抖尾沿判定（纯函数）──────────────────────────────────────────────

    private val WINDOW = 2_000L

    @Test
    fun 距上次写盘足够久时立即可写() {
        assertEquals(0L, UserFrequency.saveDelayMs(now = 10_000L, lastSaveAt = 7_000L, window = WINDOW))
        assertEquals(0L, UserFrequency.saveDelayMs(now = 9_000L, lastSaveAt = 7_000L, window = WINDOW))
    }

    @Test
    fun 窗口内返回剩余等待时间而非丢弃() {
        // 关键回归：旧实现窗口内直接 return（只做前沿丢弃），这次学习要等 flush 才落盘，
        // 进程被 LMK 直杀就丢了。现在改为返回剩余毫秒，由调度器补一次写。
        assertEquals(500L, UserFrequency.saveDelayMs(now = 8_500L, lastSaveAt = 7_000L, window = WINDOW))
        assertEquals(1_999L, UserFrequency.saveDelayMs(now = 7_001L, lastSaveAt = 7_000L, window = WINDOW))
    }

    @Test
    fun 首次写盘前立即落盘() {
        // lastSaveAt == 0 表示「从未写过」；真实调用传的是 epoch 毫秒，差值必然为负 → 0
        val epochNow = 1_700_000_000_123L
        assertEquals(0L, UserFrequency.saveDelayMs(now = epochNow, lastSaveAt = 0L, window = WINDOW))
    }

    @Test
    fun 时钟回拨时等待被钳到一个窗口() {
        // 回拨后 lastSaveAt 落在「未来」，原始差值是小时级；不钳就等于期间完全不再落盘
        assertEquals(WINDOW, UserFrequency.saveDelayMs(now = 1L, lastSaveAt = 7_000L, window = WINDOW))
        val epochNow = 1_600_000_000_000L
        assertEquals(
            WINDOW,
            UserFrequency.saveDelayMs(now = epochNow, lastSaveAt = epochNow + 3_600_000L, window = WINDOW),
        )
    }
}
