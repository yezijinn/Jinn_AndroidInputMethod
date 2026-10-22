package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

/**
 * 用户词频学习的纯逻辑护栏：排序稳定（学过的提前、其余保持词库原序、不学就零变化）、
 * 衰减跟 `algo::formula_d` 一致、文件读写往返一致且能扛脏数据、关掉开关后不学也不改排序。
 *
 * 落盘自 2026-10-21 起是**整份加密**的，所以凡是要读文件内容的用例都先解密
 * （[UserFrequency.decryptDoc]）；密钥由 [key] 注入 —— JVM 上没有 Android Keystore。
 */
class UserFrequencyTest {

    /** 固定 32 字节密钥：只复现格式，不含任何真实密钥 */
    private val key = SecretKeySpec(ByteArray(32) { (it * 5 + 11).toByte() }, "AES")

    /** 每个用例前注入密钥（生产路径走 [LedgerCrypto.dataKey]，这里是纯 JVM 替身） */
    @Before
    fun setUp() = UserFrequency.setKeyForTest(key)

    @After
    fun tearDown() {
        UserFrequency.setKeyForTest(null)
        UserFrequency.resetForTest()
    }

    /** 读回磁盘上的明文正文（加密用例的唯一读法） */
    private fun plainOf(f: File): String? = UserFrequency.decryptDoc(f.readText(), key)

    /** 当天 tick（与 UserFrequency 内部一致）：测试数据若用很旧的天计数，会被按天衰减过滤掉 */
    private fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

    // ── 补载与替换的时序（BUG.md L-1281）────────────────────────────────────

    /**
     * 补载的读盘在锁外、入内存才进锁：读与写之间可能正好跨过一次备份导入的整份替换。
     * 那时补载读到的是**替换之前**的旧文件，若照旧并入，就把已删掉的词填回了内存表 ——
     * 用户以为「还原成备份里的样子」，实际是新旧两份合一，下次落盘还把混合结果写死。
     *
     * 时序是直接构造的：拿到换代前的版本号 + 旧文本的解析结果，再让替换先跑，最后才走
     * 「入内存」那一步（[UserFrequency.applyReload]）。
     */
    @Test
    fun 补载读到替换前的文本时不得填回内存() {
        val f = File.createTempFile("jinn-user-freq", ".txt").apply { deleteOnExit() }
        f.writeText("# jinn user_freq v1\n旧词\t9.000\t${today()}\n")
        UserFrequency.loadForTest(true)
        UserFrequency.setFileForTest(f)

        // ① 补载阶段：读盘（此时文件里还有旧词），记下当时的版本号
        val staleVersion = UserFrequency.versionForTest()
        val stale = UserFrequency.parse(f.readText(), today(), PinyinEngine::toSimplified)
        assertEquals(1, stale.size)

        // ② 期间备份导入把词频整份替换（内存换代、文件也被覆盖）
        val merged = "# jinn user_freq v1\n新词\t2.500\t${today()}\n"
        assertTrue(UserFrequency.writeFromBackup(f, merged, true))

        // ③ 补载现在才走到「入内存」：整份作废，旧词不得复活
        assertFalse("换代后的补载必须作废", UserFrequency.applyReload(f, staleVersion, stale))
        assertNull("旧词不得被填回", UserFrequency.weightForTest("旧词"))
        assertEquals(1, UserFrequency.sizeForTest())

        // 对照：版本号没变时照常并入（否则「开关打开后补载」就再也不工作了）。
        // 这里往文件里补一个内存中没有的词，能真正证明并入发生过
        f.writeText("# jinn user_freq v1\n新词\t2.500\t${today()}\n补载词\t1.500\t${today()}\n")
        val fresh = UserFrequency.parse(f.readText(), today(), PinyinEngine::toSimplified)
        assertTrue("未换代时补载正常并入", UserFrequency.applyReload(f, UserFrequency.versionForTest(), fresh))
        assertEquals(1.5, UserFrequency.weightForTest("补载词")!!, 1e-6)
    }

    /** 没有待写内容时 [UserFrequency.flush] 也算成功（盘上本来就是当前真值） */
    @Test
    fun 无待写内容时flush算成功() {
        UserFrequency.resetForTest()
        assertTrue("未装载文件", UserFrequency.flush())
        UserFrequency.loadForTest(false)
        UserFrequency.setFileForTest(File.createTempFile("jinn-user-freq", ".txt").apply { deleteOnExit() })
        assertTrue("学习已关闭", UserFrequency.flush())
    }

    // ── 备份导入：加锁替换 ──────────────────────────────────────────────────

    @Test
    fun 备份导入替换会落盘并同步内存态() {
        val f = File.createTempFile("jinn-user-freq", ".txt").apply { deleteOnExit() }
        UserFrequency.loadForTest(true)
        UserFrequency.setFileForTest(f)
        UserFrequency.putForTest("旧词", 9.0, 20000)
        val merged = "# jinn user_freq v1\n新词\t2.500\t${today()}\n"

        assertTrue(UserFrequency.writeFromBackup(f, merged, true))

        // 磁盘上是密文：解出来必须逐字等于入参（额外钉一次「包内明文、落盘密文」）
        assertEquals(merged, plainOf(f))
        assertFalse("磁盘上不得出现词面", "新词" in f.readText())
        assertEquals(2.5, UserFrequency.weightForTest("新词")!!, 1e-6)
        assertNull(UserFrequency.weightForTest("旧词"))
        assertEquals(1, UserFrequency.sizeForTest())
        // 替换后不能还是「脏」的：否则运行期的防抖写盘会立刻用内存快照覆写一遍
        assertFalse(UserFrequency.isDirtyForTest())
    }

    @Test
    fun 备份导入在开关关闭时也同步内存态但不参与排序() {
        val f = File.createTempFile("jinn-user-freq", ".txt").apply { deleteOnExit() }
        UserFrequency.loadForTest(false)
        UserFrequency.setFileForTest(f)
        val merged = "# jinn user_freq v1\n新词\t2.500\t${today()}\n"

        assertTrue(UserFrequency.writeFromBackup(f, merged, false))

        // 内存里有表（用户随后打开开关即可用），关闭状态排序不受影响
        assertEquals(2.5, UserFrequency.weightForTest("新词")!!, 1e-6)
        // 把学过的词放进入参数组：否则 rank 无论是否受开关影响都会原样返回（断言永真）
        assertArrayEquals(arrayOf("甲", "新词"), UserFrequency.rank(arrayOf("甲", "新词")))
    }

    @Test
    fun 极端天计数不会因整型回绕而逃过衰减() {
        val now = 20000
        // day = Int.MIN_VALUE：在 Int 域里 (day - now) 会回绕成大正数，被 coerceAtMost(0) 归零后
        // 权重完全不衰减 —— 那样这类脏行会永久霸占首候选并被原样写回文件，每次启动都复现
        val text = "# jinn user_freq v1\n脏行\t99999.000\t-2147483648\n正常\t1.000\t$now\n"
        val parsed = UserFrequency.parse(text, now)
        assertNull(parsed["脏行"])
        assertNotNull(parsed["正常"])
    }

    @Test
    fun 原子写会覆盖已存在的目标且不留临时文件() {
        val f = File.createTempFile("jinn-freq-atomic", ".txt").apply { deleteOnExit() }
        f.writeText("旧内容", Charsets.UTF_8)

        assertTrue(UserFrequency.writeAtomically(f, "新内容"))

        assertEquals("新内容", f.readText())
        assertFalse("不该留下临时文件", File(f.parentFile, f.name + ".tmp").exists())
    }

    @Test
    fun 备份导入写盘失败时不动内存态() {
        // 父目录不存在 → 写盘必然失败
        val bad = File(File(System.getProperty("java.io.tmpdir"), "jinn-no-such-dir"), "user_freq.txt")
        UserFrequency.loadForTest(true)
        UserFrequency.setFileForTest(bad)
        UserFrequency.putForTest("旧词", 9.0, 20000)

        assertFalse(
            UserFrequency.writeFromBackup(bad, "# jinn user_freq v1\n新词\t2.500\t20000\n", true)
        )

        // 失败必须保留原内存态：不能「导入没成功」却看起来像成功
        assertEquals(9.0, UserFrequency.weightForTest("旧词")!!, 1e-6)
        assertNull(UserFrequency.weightForTest("新词"))
    }

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
        // 天一律跟到**解析日**（BUG.md L-111）：权重已被折算到 20000，天也必须记 20000 ——
        // 否则 (权重, 天) 这对值不再自洽，下次加载会再折算一次
        // （「拟好」原为 20001 = 未来天，过去会被原样写回 ⇒ 复利衰减）
        assertEquals(20000, back["你好"]!!.second)
        assertEquals(20000, back["拟好"]!!.second)
    }

    @Test
    fun 跨天加载不得重复衰减() {
        // BUG.md L-111：`parse` 曾把权重折算到解析日却**原样保留 day**，而 `render` 写的也是这个 day
        // ⇒ 文件里成了「已折算的权重 + 原 day」，下次加载按 `原 day → 那时的今天` 再折算一遍
        // （同一天的衰减被反复计息：实测 5 天多衰减 8%、30 天残余只剩 0.10）。现值把 day 一并推进
        // 到解析日，「权重 = 该 day 上的值」自洽 ⇒ 往返幂等。
        UserFrequency.resetForTest()
        val now = today()
        UserFrequency.putForTest("旧词", 5.0, now - 100)

        // ① 第一次跨天加载：按 100 天折算一次（这是设计行为），天推进到今天
        val first = UserFrequency.parse(UserFrequency.render(), now)["旧词"]!!
        assertEquals(5.0 * Math.exp(-100.0 / 200.0), first.first, 1e-9)
        assertEquals("权重折算到哪天，天就要记在哪天", now, first.second)

        // ② 把这次的内存态落盘（= 这天学习过触发的整表落盘）后再加载同一天：不得再折算
        UserFrequency.resetForTest()
        UserFrequency.putForTest("旧词", first.first, first.second)
        val second = UserFrequency.parse(UserFrequency.render(), now)["旧词"]!!
        // 容差 = 文件格式精度（权重按 `%.3f` 写盘，往返只能精确到 1e-3）；
        // 历史缺陷在这里会再乘一个 exp(-100/200) ≈ 0.61（3.033 → 1.84），差三个数量级，拦得住。
        assertEquals("同一天再次加载不得再乘一次衰减因子（往返幂等）", first.first, second.first, 1e-3)
        assertEquals("天保持今天", now, second.second)
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
        // BUG.md L-1319：失败必须能被调用方看见（导出 / 导入据此如实说明）
        assertFalse("写盘失败必须返回 false", UserFrequency.flush())
        assertTrue("写盘失败后必须保留 dirty，否则这次学习永久丢失", UserFrequency.isDirtyForTest())

        // 可写路径：flush 成功后 dirty 清除、文件写出
        val dir = File(System.getProperty("java.io.tmpdir"), "jinn-uf-flush-ok")
        dir.mkdirs()
        val ok = File(dir, "user_freq.txt")
        UserFrequency.setFileForTest(ok)
        assertTrue("写盘成功要返回 true", UserFrequency.flush())
        assertTrue("写盘成功后 dirty 应清除", !UserFrequency.isDirtyForTest())
        assertTrue("文件应已写出", ok.isFile)
        dir.deleteRecursively()
        // 收尾复位：本用例把 file 指到了临时目录，不复位会跨测试类残留（其它用例在 @Before 各自 reset）
        UserFrequency.resetForTest()
    }

    // ── 读失败（盘上内容未知）：L-1409 ──────────────────────────────────────

    /**
     * L-1409：读失败（密钥不可用 / 密文损坏）之后，`remember` 的落盘**不得**用内存里的空表
     * 覆盖盘上的历史；密钥恢复后要把盘上的词并回来（权重取大者）再整份写出。
     *
     * 现场：开机预加载早于用户解锁 ⇒ `setUnlockedDeviceRequired` 的密钥此时解不开 ⇒ 读跳过；
     * 解锁后打第一个词，`saveNow` 拿内存里的「1 条」整份重写 ⇒ 27 条词频当场变 1 条，不可恢复。
     *
     * 走的是真实路径：`loadFromFile`（`load` 只是它加一层 Context）+ 真实 `flush`，密钥靠
     * [UserFrequency.setKeyForTest] 注 null 模拟「解不开」（JVM 上没有 Keystore）。
     */
    @Test
    fun 读失败后不得用空表覆盖词频() {
        UserFrequency.resetForTest()
        val f = File.createTempFile("jinn-freq-blocked", ".txt").apply { deleteOnExit() }
        val body = "# jinn user_freq v1\n旧词\t4.000\t${today()}\n另一个\t2.000\t${today()}\n"
        f.writeText(UserFrequency.encryptDoc(body, key))
        val before = f.readText()

        // ① 设备未解锁：密钥不可用 ⇒ 读取跳过，且必须记下「盘上内容未知」
        UserFrequency.setKeyForTest(null)
        UserFrequency.loadFromFile(f, true)
        assertTrue("读失败必须置「盘上内容未知」", UserFrequency.isLoadBlockedForTest())
        assertEquals("解不开不得当成没有词频", 0, UserFrequency.sizeForTest())

        // ② 解锁后学了第一个词：落盘必须被拦住，「1 条」不许写出去
        UserFrequency.remember("新词")
        assertFalse("盘上内容未知时 flush 必须如实报失败", UserFrequency.flush())
        assertEquals("原文件一个字节都不许变", before, f.readText())
        assertTrue("这次学习仍待落盘（不许丢弃）", UserFrequency.isDirtyForTest())

        // ③ 密钥恢复：补读 → 并入旧词（权重原样）→ 整份写出
        UserFrequency.setKeyForTest(key)
        assertTrue("密钥恢复后 flush 应成功", UserFrequency.flush())
        val plain = UserFrequency.decryptDoc(f.readText(), key)!!
        val parsed = UserFrequency.parse(plain, today())
        assertEquals("旧词一条都不许丢", setOf("旧词", "另一个", "新词"), parsed.keys)
        assertEquals("旧词权重必须原样保留", 4.0, parsed["旧词"]!!.first, 1e-3)
        assertEquals("新词按学到的一次计", 1.0, parsed["新词"]!!.first, 1e-3)
        assertFalse("补读成功后不再阻塞", UserFrequency.isLoadBlockedForTest())
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

    @Test
    fun 旧版遗留的半简半繁键在加载时被清洗() {
        // 修复 toSimplified（2026-09-27）之前，繁体模式学过的词存的是「头髮」这类折不回简体的键：
        // 升级后它们不再被任何候选匹配（无害但白占文件）。传归一化器时丢弃（BUG.md L-54）；
        // 不传（默认恒等）时行为与老口径一致 —— 纯函数单测与不带归一化的调用方都不受影响。
        val text = "头髮\t2.0\t100\n头发\t1.0\t100\n"
        val normalized = UserFrequency.parse(text, nowDay = 100) { if (it == "头髮") "头发" else it }
        assertEquals("已规范化的键保留、折不回简体的键丢弃", setOf("头发"), normalized.keys)
        assertEquals("不传归一化器时两条都保留（老口径）", setOf("头髮", "头发"),
            UserFrequency.parse(text, nowDay = 100).keys)
    }

    // ── 落盘加密（N1：同一批词不能明文躺在另一个文件里）───────────────────

    /** 半开：词频记的是同一批「选过的词」，还多了权重与时间 —— 磁盘上不得有词面 */
    @Test
    fun 词频落盘不得含明文词面() {
        val f = File.createTempFile("jinn-freq-enc", ".txt").apply { deleteOnExit() }
        UserFrequency.loadForTest(true)
        UserFrequency.setFileForTest(f)
        // 走真实的 learning 入口（`putForTest` 不置 dirty，flush 会直接返回）
        UserFrequency.remember("五湖")
        UserFrequency.remember("五湖")
        assertTrue("写盘应成功", UserFrequency.flush())

        val raw = f.readText()
        assertTrue("首行必须是加密标识", raw.startsWith(UserFrequency.ENC_HEADER))
        assertFalse("词面不得出现在磁盘上", "五湖" in raw)
        assertFalse("权重不得出现在磁盘上", "2.000" in raw)
        // 往返：解出来的正文与内存渲染逐字一致
        assertEquals(UserFrequency.render(), UserFrequency.decryptDoc(raw, key))
        assertEquals(2.0, UserFrequency.parse(plainOf(f)!!, today())["五湖"]!!.first, 1e-9)
    }

    /** 旧版明文（首行 `# jinn user_freq v1`）首次读取时一次性迁移成密文，内容一条不丢 */
    @Test
    fun 旧版明文词频一次性迁移成密文且一条不丢() {
        val dir = File(System.getProperty("java.io.tmpdir"), "jinn-freq-migrate").apply { mkdirs() }
        val f = File(dir, "user_freq.txt")
        f.writeText("# jinn user_freq v1\n旧词\t3.000\t${today()}\n另一个\t1.500\t${today()}\n")
        UserFrequency.loadForTest(true)
        UserFrequency.setFileForTest(f)

        val plain = UserFrequency.readPlainOrMigrate(f)
        assertNotNull("迁移后仍应能读出明文", plain)
        assertEquals("两条都要在", setOf("旧词", "另一个"), UserFrequency.parse(plain!!, today()).keys)
        assertTrue("迁移后磁盘上是密文", f.readText().startsWith(UserFrequency.ENC_HEADER))
        assertFalse("迁移后不得再有词面", "旧词" in f.readText())
        dir.deleteRecursively()
    }

    /** 首行标识被删的密文词频仍读得出来，且读时顺手补回标识（与台账 N3 同型） */
    @Test
    fun 首行标识被删的密文词频仍可读并补回标识() {
        val f = File.createTempFile("jinn-freq-nohead", ".txt").apply { deleteOnExit() }
        val body = "# jinn user_freq v1\n词\t2.000\t${today()}\n"
        // 只留记录行，去掉 #jinn-freq1
        f.writeText(UserFrequency.encryptDoc(body, key).lineSequence().filter { it.startsWith("1:") }.joinToString("\n") + "\n")
        UserFrequency.loadForTest(true)
        UserFrequency.setFileForTest(f)

        val plain = UserFrequency.readPlainOrMigrate(f)
        assertEquals("缺头也必须解得出原文", body, plain)
        assertTrue("读时补回首行标识", f.readText().startsWith(UserFrequency.ENC_HEADER))
    }

    /** 密钥不可用 ⇒ 不写明文、不丢 dirty、如实回报失败 */
    @Test
    fun 密钥不可用时词频不写明文并保留dirty() {
        UserFrequency.resetForTest()
        UserFrequency.setKeyForTest(null)
        val dir = File(System.getProperty("java.io.tmpdir"), "jinn-freq-nokey").apply { mkdirs() }
        val f = File(dir, "user_freq.txt")
        UserFrequency.setFileForTest(f)
        UserFrequency.loadForTest(true)

        UserFrequency.remember("五湖")
        assertFalse("密钥不可用时 flush 必须报失败", UserFrequency.flush())
        assertFalse("绝不能回退写明文", f.exists() && "五湖" in f.readText())
        assertTrue("这次学习仍待落盘（下次有机会再写）", UserFrequency.isDirtyForTest())
        dir.deleteRecursively()
    }

    /** 配置备份这一层：**包内是明文**（解压之后拿出来的是明文） */
    @Test
    fun 备份导出取到明文_导入落盘为密文() {
        val f = File.createTempFile("jinn-freq-backup", ".txt").apply { deleteOnExit() }
        val body = "# jinn user_freq v1\n词\t2.000\t${today()}\n"
        f.writeText(UserFrequency.encryptDoc(body, key))
        assertEquals("导出的包内条目必须是明文", body, UserFrequency.readTextForBackup(f))

        // 导入侧：写回磁盘是密文
        assertTrue(UserFrequency.writeFromBackup(f, body, true))
        assertTrue(f.readText().startsWith(UserFrequency.ENC_HEADER))
        assertEquals(body, UserFrequency.readTextForBackup(f))
        // 文件不存在 ⇒ 空串（不是 null，「没有词频」与「读不出」必须分开）
        assertEquals("", UserFrequency.readTextForBackup(File(f.parentFile, "nope.txt")))
    }
}
