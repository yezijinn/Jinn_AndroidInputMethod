package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份包 zip 层的主路径守卫：打包 → 三态读取 → 摘要校验 → 恶意形态。
 *
 * 为什么需要它：这一层此前只能靠真机人工验证（ADB 打不出中文密码，端到端跑不起来），
 * 而它正是「包能不能被读回来、坏包会不会被当成好包、恶意包能不能塞东西」的判定处。
 * 需要真机才能测的部分（Context / SharedPreferences / SQLite / Keystore）仍由人工验证覆盖。
 */
class ConfigBackupZipTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "jinn-zip-" + System.nanoTime())

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    /** 造一个 zip（条目顺序即传入顺序；生产者在 `exportLocked` 里是 manifest 放最前） */
    private fun makeZip(vararg entries: Pair<String, ByteArray>): File {
        tmp.mkdirs()
        val f = File(tmp, "p-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `打包后各节按预期读回且缺失的节是Missing`() {
        val prefs = "{\"jinn_inputmethod\": {\"host\": {\"t\": \"s\", \"v\": \"nas\"}}}"
        val freq = "# jinn user_freq v1\n测试词\t1.000\t20000\n"
        val zip = makeZip(
            ConfigBackup.ENTRY_PREFS to bytes(prefs),
            ConfigBackup.ENTRY_USER_FREQ to bytes(freq),
            ConfigBackup.ENTRY_MANIFEST to bytes("{}"),
        )

        val prefsRead = ConfigBackupManager.readSection(zip, ConfigBackup.ENTRY_PREFS)
        assertTrue("prefs 应读到", prefsRead is ConfigBackupManager.SectionRead.Ok)
        assertEquals(prefs, (prefsRead as ConfigBackupManager.SectionRead.Ok).text)
        assertEquals(freq, ConfigBackupManager.readEntry(zip, ConfigBackup.ENTRY_USER_FREQ))
        // 包里没有的节必须是 Missing，而不是空串或 Failed
        assertEquals(
            ConfigBackupManager.SectionRead.Missing,
            ConfigBackupManager.readSection(zip, ConfigBackup.ENTRY_CLIPBOARD),
        )
    }

    @Test
    fun `节大小恰好等于上限时通过 超过一个字节即TooLarge`() {
        val exact = ByteArray(4096) { 'a'.code.toByte() }
        val zip = makeZip(ConfigBackup.ENTRY_PREFS to exact)

        assertTrue(
            ConfigBackupManager.readSection(zip, ConfigBackup.ENTRY_PREFS, 4096)
                is ConfigBackupManager.SectionRead.Ok,
        )
        assertEquals(
            ConfigBackupManager.SectionRead.TooLarge,
            ConfigBackupManager.readSection(zip, ConfigBackup.ENTRY_PREFS, 4095),
        )
    }

    @Test
    fun `条目内容被改动时是Failed（读不出来不能被当成没有）`() {
        val zip = makeZip(ConfigBackup.ENTRY_PREFS to ByteArray(65536) { (it % 251).toByte() })
        val raw = zip.readBytes()
        // 改数据区的一个字节、不动 CRC：deflate 流被破坏，读取该条目时会抛异常
        val nameLen = ConfigBackup.ENTRY_PREFS.toByteArray(Charsets.US_ASCII).size
        val dataStart = 30 + nameLen     // 本地文件头固定 30 字节 + 文件名
        raw[dataStart + 16] = (raw[dataStart + 16] + 1).toByte()
        zip.writeBytes(raw)

        // 必须是 Failed（不是 Missing、也不是 Ok）：读失败被当成「没有这一节」的话，
        // 导入会静默跳过它、界面还报成功
        assertEquals(
            "被破坏的条目必须判 Failed",
            ConfigBackupManager.SectionRead.Failed,
            ConfigBackupManager.readSection(zip, ConfigBackup.ENTRY_PREFS),
        )
    }

    @Test
    fun `根本不是zip的文件表现为Missing（由 manifest 缺失拒绝整包）`() {
        tmp.mkdirs()
        val notZip = File(tmp, "not-a-zip.jinn")
        notZip.writeBytes(ByteArray(256) { 0x37 })

        // ZipInputStream 对纯垃圾只返回「没有条目」：所以「随便选个文件」不是靠 Failed 拦截，
        // 而是靠 manifest 读不到 → 清单不可识别（这条钉住该行为，避免误以为 Failed 会兜住）
        assertEquals(
            ConfigBackupManager.SectionRead.Missing,
            ConfigBackupManager.readSection(notZip, ConfigBackup.ENTRY_PREFS),
        )
    }

    @Test
    fun `摘要校验与内容严格绑定`() {
        val text = "{\"jinn_inputmethod\": {}}"
        val manifest = ConfigBackup.Manifest(
            formatVersion = ConfigBackup.FORMAT_VERSION,
            appVersionCode = 1,
            createdAt = 0L,
            device = "d",
            sections = linkedMapOf(ConfigBackup.SEC_PREFS to ConfigBackupManager.sectionOf(text)),
        )

        assertTrue(ConfigBackupManager.verify(manifest, ConfigBackup.SEC_PREFS, text))
        assertFalse("多一个空格就该失败", ConfigBackupManager.verify(manifest, ConfigBackup.SEC_PREFS, "$text "))
        assertFalse("未登记的节必须失败", ConfigBackupManager.verify(manifest, ConfigBackup.SEC_USER_FREQ, text))
    }

    @Test
    fun `条目名收集有上限且词库条目判定不受上限影响`() {
        val entries = ArrayList<Pair<String, ByteArray>>()
        for (i in 1..600) entries.add("filler/$i.txt" to ByteArray(1))
        // 词库条目排在上限（512）之后：收集列表看不到它，但存在性判定必须仍能发现
        entries.add((ConfigBackup.DICT_DIR + "ext.xz") to ByteArray(2))
        val zip = makeZip(*entries.toTypedArray())

        val names = ConfigBackupManager.zipEntryNames(zip)
        // 精确断言：既钉住「确实截断」，也钉住「被截掉的正是排在后面的词库条目」
        assertEquals(ConfigBackupManager.MAX_ZIP_ENTRIES, names.size)
        assertFalse(
            "词库条目排在上限之后，不该出现在收集结果里",
            names.any { it.startsWith(ConfigBackup.DICT_DIR) },
        )
        assertTrue("存在性判定不能受收集上限影响", ConfigBackupManager.hasDictEntry(zip))
    }

    @Test
    fun `剪贴板分类字段归一到白名单`() {
        val jsonl = listOf(
            """{"content":"https://a","category":"URL"}""",
            """{"content":"123","category":"NUMBER"}""",
            """{"content":"来自恶意包","category":"<img src=x>"}""",
            """{"content":"空白分类","category":"   "}""",
        ).joinToString("\n")

        val list = ConfigBackup.decodeClipboard(jsonl)

        assertEquals(4, list.size)
        assertEquals(ClipboardClassifier.CATEGORY_URL, list[0].category)
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, list[1].category)
        assertEquals(ClipboardClassifier.CATEGORY_OTHER, list[2].category)
        assertEquals(ClipboardClassifier.CATEGORY_OTHER, list[3].category)
    }

    // ── 词库节（restoreDicts）：白名单 / 内容校验 / 穿越 / 上限 / 成功落盘 ──

    /** 第 i 个词库条目的内容（唯一真源：包生成与摘要复算都用它） */
    private fun dictContent(i: Int) = ByteArray(64) { (i * 31 + it).toByte() }

    /** 造一个只含词库条目的包；每个条目内容不同 */
    private fun dictZip(vararg names: String): File {
        val entries = ArrayList<Pair<String, ByteArray>>()
        for ((i, n) in names.withIndex()) {
            entries.add((ConfigBackup.DICT_DIR + n) to dictContent(i))
        }
        return makeZip(*entries.toTypedArray())
    }

    /**
     * `dictsDigest` 的**独立**实现（不复用被测函数：复用的话算法写错时两边一起错，测试照样绿）。
     * 算法 = 按文件名排序 → 拼 `<名>:<sha256>`（换行分隔）→ 再取 sha256。
     */
    private fun dictsDigestOf(vararg items: Pair<String, ByteArray>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val joined = items
            .map { it.first to ConfigBackup.sha256(it.second) }
            .sortedBy { it.first }
            .joinToString("\n") { "${it.first}:${it.second}" }
        return md.digest(joined.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun dictDir(): File = File(tmp, "dicts").apply { mkdirs() }

    /** 只认 [name] 的合成清单（checksum = 给定内容的 sha256），用来覆盖「内容可信 → 落盘」路径 */
    private fun onlySpec(name: String, content: ByteArray): (String) -> String? =
        { n -> if (n == name) ConfigBackup.sha256(content) else null }

    @Test
    fun `清单外与内容不符的词库都只跳过不落盘`() {
        // ① 文件名不在清单内
        val dir1 = dictDir()
        val r1 = ConfigBackupManager.restoreDicts(
            dir1, dictZip("evil.xz"), dictsDigestOf("evil.xz" to dictContent(0)),
        ) { null }
        assertNotNull("摘要对上就不该整包失败", r1)
        assertEquals(0, r1!!.first)
        assertEquals(1, r1.second)
        assertTrue("清单外的文件绝不能落盘", dir1.listFiles().orEmpty().isEmpty())

        // ② 文件名在清单内、但内容与官方校验值不符
        val dir2 = dictDir()
        val r2 = ConfigBackupManager.restoreDicts(
            dir2, dictZip("ext.xz"), dictsDigestOf("ext.xz" to dictContent(0)),
        ) { "0".repeat(64) }
        assertNotNull(r2)
        assertEquals("内容不可信就不能算写入成功", 0, r2!!.first)
        assertEquals(1, r2.second)
        assertTrue(dir2.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `清单内且内容一致时成功落盘且不留临时件`() {
        val dir = dictDir()

        val r = ConfigBackupManager.restoreDicts(
            dir,
            dictZip("fake.xz"),
            dictsDigestOf("fake.xz" to dictContent(0)),
            checksumOf = onlySpec("fake.xz", dictContent(0)),
        )

        assertNotNull(r)
        assertEquals(1, r!!.first)
        assertEquals(0, r.second)
        assertTrue("文件要真的落地", File(dir, "fake.xz").isFile)
        assertFalse("临时件必须清掉", dir.listFiles().orEmpty().any { it.name.endsWith(".restore") })
    }

    @Test
    fun `目标已有同名文件时跳过且不覆盖`() {
        val dir = dictDir()
        val existing = File(dir, "fake.xz").apply { writeBytes(ByteArray(4) { 9 }) }

        val r = ConfigBackupManager.restoreDicts(
            dir,
            dictZip("fake.xz"),
            dictsDigestOf("fake.xz" to dictContent(0)),
            checksumOf = onlySpec("fake.xz", dictContent(0)),
        )

        assertEquals(0, r!!.first)
        assertEquals(1, r.second)
        assertEquals("不能覆盖用户现有词库", 4, existing.readBytes().size)
    }

    @Test
    fun `zip 读取中途失败时半截临时件也会被清掉`() {
        val dir = dictDir()
        // 造一个「条目头完整、内容被截断」的包：restoreDicts 会先建出 `.restore` 并写入一部分，
        // 随后 read 抛异常 → readOk=false。这个半截件既不在 pending 也没登记 in-flight
        // （两者都在读循环**之后**才赋值），必须由读取失败分支按后缀清掉 ——
        // 否则它会一直躺在 filesDir/dicts 里等下 60 秒的启动清扫
        val big = ByteArray(256 * 1024).also { java.util.Random(7).nextBytes(it) } // 随机字节不可压缩
        val full = makeZip((ConfigBackup.DICT_DIR + "fake.xz") to big).readBytes()
        val broken = File(tmp, "broken.zip").apply { writeBytes(full.copyOf(full.size / 2)) }

        val r = ConfigBackupManager.restoreDicts(
            dir,
            broken,
            "0".repeat(64),
            checksumOf = onlySpec("fake.xz", big),
        )

        assertNull("读取失败必须整包拒绝", r)
        assertTrue(
            "半截临时件必须清掉：${dir.listFiles().orEmpty().map { it.name }}",
            dir.listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun `摘要不符时已提取的临时文件会被清掉`() {
        val dir = dictDir()

        // 清单命中 → 会真的提取出 .restore；摘要对不上 → 必须清理干净（此前这条路径没有任何测试能触发）
        val r = ConfigBackupManager.restoreDicts(
            dir,
            dictZip("fake.xz"),
            "0".repeat(64),
            checksumOf = onlySpec("fake.xz", dictContent(0)),
        )

        assertNull(r)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `词库条目名带路径分隔符时拒绝整包`() {
        val name = "../evil.xz"
        val content = ByteArray(8)
        val zip = makeZip((ConfigBackup.DICT_DIR + name) to content)
        val dir = dictDir()

        // 摘要按「该条目若被正常记录」的值复算、清单也认得它：删掉穿越检查时会走到「内容不符 → 跳过」
        // 得到 (0,1) 而不是 null，断言才会失败（此前传 "0"*64，摘要闸单独就能拒绝 → 是假测试）
        val r = ConfigBackupManager.restoreDicts(dir, zip, dictsDigestOf(name to content)) { n ->
            if (n == name) "0".repeat(64) else null
        }

        assertNull(r)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `词库条目数超过上限时拒绝整包并清掉已提取的临时件`() {
        val names = (1..(ConfigBackupManager.MAX_DICT_FILES + 1)).map { "d$it.xz" }.toTypedArray()
        val zip = dictZip(*names)
        val dir = dictDir()
        // 清单全部认得（内容也一致）→ 前 16 条会被真的提取，第 17 条才触发上限；
        // 若删掉上限判定，会返回 (17,0) 而不是 null，断言才会失败（此前的写法摘要先失败 → 假测试）
        val specs = HashMap<String, String>()
        names.forEachIndexed { i, n -> specs[n] = ConfigBackup.sha256(dictContent(i)) }
        val expected = dictsDigestOf(*names.mapIndexed { i, n -> n to dictContent(i) }.toTypedArray())

        val r = ConfigBackupManager.restoreDicts(dir, zip, expected) { specs[it] }

        assertNull(r)
        assertTrue("超限时已提取的临时件也要清掉", dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `预算链关系成立`() {
        // 导出预算 ≤ 导出单节闸（留转义余量）；单节上限 < 包体上限 < 词库总量上限的关系
        assertTrue(
            ConfigBackupManager.CLIP_EXPORT_BYTE_BUDGET <= ConfigBackupManager.EXPORT_SECTION_LIMIT_BYTES,
        )
        assertTrue(ConfigBackupManager.EXPORT_SECTION_LIMIT_BYTES < ConfigBackupManager.MAX_TEXT_SECTION_BYTES)
        assertTrue(ConfigBackupManager.MAX_TEXT_SECTION_BYTES < ConfigBackupManager.MAX_PACKAGE_BYTES)
        assertTrue(ConfigBackupManager.MAX_DICT_BYTES < ConfigBackupManager.MAX_PACKAGE_BYTES)
        // 按名查找的解压量上限要大于包体上限：合法包解压后比压缩后大，取等会把自产包拒掉
        assertTrue(ConfigBackupManager.MAX_PACKAGE_BYTES < ConfigBackupManager.MAX_SCAN_INFLATED_BYTES)
    }

    @Test
    fun `跳过条目的解压量超过预算时判Failed而不是继续解压`() {
        // 前一条目解压后 4MB、预算只给 1MB：必须停在 Failed。
        // 这条钉住「跳过的条目也要记账」—— 没有这道闸，几百 KB 的对抗包能让按名查找解压 GB 级数据
        val zip = makeZip(
            "a_junk.bin" to ByteArray(4 * 1024 * 1024),
            ConfigBackup.ENTRY_PREFS to bytes("{}"),
        )
        val tight = ConfigBackupManager.ScanBudget(1024L * 1024)

        assertEquals(
            ConfigBackupManager.SectionRead.Failed,
            ConfigBackupManager.readSection(zip, ConfigBackup.ENTRY_PREFS, budget = tight),
        )
        assertTrue("预算耗尽必须留痕（调用方据此整包拒收）", tight.exhausted)
        // 同一个包在默认预算下读得到：证明拒收来自预算，而不是包本身有问题
        assertTrue(
            ConfigBackupManager.readSection(zip, ConfigBackup.ENTRY_PREFS)
                is ConfigBackupManager.SectionRead.Ok,
        )
    }

    @Test
    fun `条目名收集与词库判定在预算耗尽时停下并留痕`() {
        val zip = makeZip(
            "a_junk.bin" to ByteArray(2 * 1024 * 1024),
            ConfigBackup.DICT_DIR + "x.xz" to dictContent(1),
        )
        val tight = ConfigBackupManager.ScanBudget(512L * 1024)

        val names = ConfigBackupManager.zipEntryNames(zip, budget = tight)
        assertTrue("预算耗尽必须留痕", tight.exhausted)
        assertTrue("停在预算处：后面的条目没有被收集", names.none { it.endsWith("x.xz") })

        // 词库判定同样不能把「超限没读完」当成「包里没有词库」
        val tight2 = ConfigBackupManager.ScanBudget(512L * 1024)
        assertFalse(ConfigBackupManager.hasDictEntry(zip, tight2))
        assertTrue(tight2.exhausted)

        // 默认预算下两者都正常
        assertTrue(ConfigBackupManager.zipEntryNames(zip).any { it.endsWith("x.xz") })
        assertTrue(ConfigBackupManager.hasDictEntry(zip))
    }

    @Test
    fun `条目数超限时停止遍历并判包不可用`() {
        // 零字节条目一次 read 即 EOF、不消耗预算：只靠解压量预算拦不住 O(条目数) 空转
        val zip = makeZip(
            "a.bin" to ByteArray(0),
            "b.bin" to ByteArray(0),
            "c.bin" to ByteArray(0),
            "d.bin" to ByteArray(0),
            "e.bin" to ByteArray(0),
            ConfigBackup.ENTRY_PREFS to bytes("{\"p\":1}"),
        )
        val names = listOf(ConfigBackup.ENTRY_PREFS)

        val budget = ConfigBackupManager.ScanBudget()
        val got = ConfigBackupManager.readSections(zip, names, budget = budget, maxEntries = 3)
        assertTrue("条目数超限必须留痕（调用方据此拒收整包）", budget.exhausted)
        assertEquals(
            "超限没走到的节不能当成「没有」",
            ConfigBackupManager.SectionRead.Failed,
            got[ConfigBackup.ENTRY_PREFS],
        )

        val budget2 = ConfigBackupManager.ScanBudget()
        assertFalse(ConfigBackupManager.hasDictEntry(zip, budget2, maxEntries = 3))
        assertTrue(budget2.exhausted)

        // 放开门槛后同一个包读得出：证明失败来自条目数闸，而不是包本身有问题
        val relaxed = ConfigBackupManager.readSections(zip, names)
        assertEquals(
            "{\"p\":1}",
            (relaxed[ConfigBackup.ENTRY_PREFS] as ConfigBackupManager.SectionRead.Ok).text,
        )
    }

    @Test
    fun `词库恢复在预算耗尽时中止且不留临时件`() {
        // 在册词库排在巨型条目**之前**：它会先落成 `*.restore`，随后预算被巨型条目耗尽。
        // 顺序反过来（词库在后）时中止发生在临时件创建之前，清理分支根本执行不到。
        val zip = makeZip(
            ConfigBackup.DICT_DIR + "fake.xz" to dictContent(0),
            "a_junk.bin" to ByteArray(2 * 1024 * 1024),
        )
        val dir = dictDir()
        val tight = ConfigBackupManager.ScanBudget(512L * 1024)

        val r = ConfigBackupManager.restoreDicts(
            dir,
            zip,
            dictsDigestOf("fake.xz" to dictContent(0)),
            budget = tight,
            checksumOf = onlySpec("fake.xz", dictContent(0)),
        )

        assertTrue("预算耗尽必须留痕", tight.exhausted)
        assertNull("预算耗尽必须整包失败（不能把没读完的包当成功）", r)
        assertTrue("不得留下半截临时件", dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `多节一次遍历读出_包里没有的节不回键`() {
        val zip = makeZip(
            ConfigBackup.ENTRY_PREFS to bytes("{\"p\":1}"),
            ConfigBackup.ENTRY_USER_FREQ to bytes("# freq\n"),
        )

        val got = ConfigBackupManager.readSections(
            zip,
            listOf(ConfigBackup.ENTRY_PREFS, ConfigBackup.ENTRY_USER_FREQ, ConfigBackup.ENTRY_CLIPBOARD),
        )

        assertEquals(
            "{\"p\":1}",
            (got[ConfigBackup.ENTRY_PREFS] as ConfigBackupManager.SectionRead.Ok).text,
        )
        assertEquals(
            "# freq\n",
            (got[ConfigBackup.ENTRY_USER_FREQ] as ConfigBackupManager.SectionRead.Ok).text,
        )
        assertNull("包里没有的节不回键（调用方按 Missing 处理）", got[ConfigBackup.ENTRY_CLIPBOARD])
    }

    @Test
    fun `多节一次遍历_超限节记TooLarge_没走到的节记Failed`() {
        val zip = makeZip(
            "a_junk.bin" to ByteArray(2 * 1024 * 1024),
            ConfigBackup.ENTRY_PREFS to ByteArray(4096) { 'a'.code.toByte() },
            ConfigBackup.ENTRY_USER_FREQ to bytes("# freq\n"),
        )
        val names = listOf(ConfigBackup.ENTRY_PREFS, ConfigBackup.ENTRY_USER_FREQ)

        // 预算在扫到节之前就耗尽：没走到的节必须记 Failed（当成「没有」的话导入会静默漏节）
        val tight = ConfigBackupManager.ScanBudget(512L * 1024)
        val starved = ConfigBackupManager.readSections(zip, names, budget = tight)
        assertTrue("预算耗尽必须留痕", tight.exhausted)
        assertEquals(
            ConfigBackupManager.SectionRead.Failed,
            starved[ConfigBackup.ENTRY_USER_FREQ],
        )

        // 上限卡在边界：prefs 超一个字节即 TooLarge。该节必须远大于读块（readLimited 一次读 64KB），
        // 否则超限时条目已读完，「超限节的剩余部分要读空并记账」这条分支执行不到。
        val bigZip = makeZip(
            ConfigBackup.ENTRY_PREFS to ByteArray(4 * 1024 * 1024) { 'a'.code.toByte() },
            ConfigBackup.ENTRY_USER_FREQ to bytes("# freq\n"),
        )
        val small = ConfigBackupManager.ScanBudget(256L * 1024)
        val byOversize = ConfigBackupManager.readSections(bigZip, names, 4095, budget = small)
        assertEquals(
            ConfigBackupManager.SectionRead.TooLarge,
            byOversize[ConfigBackup.ENTRY_PREFS],
        )
        assertTrue("超限节的剩余部分必须计入预算", small.exhausted)
        assertEquals(
            "预算被超限节耗尽 ⇒ 后面的节记 Failed（不记账的话它会照常读出）",
            ConfigBackupManager.SectionRead.Failed,
            byOversize[ConfigBackup.ENTRY_USER_FREQ],
        )
    }

    /**
     * 手写 zip：允许**重名条目**（`ZipOutputStream` 会报 `duplicate entry`），stored 方式写入，
     * 不带中央目录 —— `ZipInputStream` 只按局部头顺序读，这样才造得出该形态的包。
     */
    private fun rawZip(vararg entries: Pair<String, String>): File {
        tmp.mkdirs()
        val out = File(tmp, "raw-${System.nanoTime()}.zip")
        out.outputStream().use { os ->
            for ((name, content) in entries) {
                val data = bytes(content)
                val nameBytes = name.toByteArray(Charsets.US_ASCII)
                val crc = java.util.zip.CRC32().apply { update(data) }.value
                val h = java.io.ByteArrayOutputStream()
                fun le16(v: Int) = h.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
                fun le32(v: Int) = h.write(
                    byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()),
                )
                le32(0x04034b50)                  // 局部头签名
                le16(20)                          // 解压所需版本
                le16(0)                           // 通用标志
                le16(0)                           // 压缩方法 = stored
                le16(0); le16(0)                  // 修改时间、日期
                le32(crc.toInt())
                le32(data.size); le32(data.size)
                le16(nameBytes.size); le16(0)     // 文件名长度、扩展字段长度
                h.write(nameBytes)
                h.write(data)
                os.write(h.toByteArray())
            }
        }
        return out
    }

    @Test
    fun `同名条目重复时取第一条_与逐节读取一致`() {
        // 手改过或按工具生成的包可能把同一节写两遍：读侧要与 readSection「命中即返回」一致取首条，
        // 否则「首条有效、末条损坏」的包会从导入成功变成整包拒收
        val zip = rawZip(
            ConfigBackup.ENTRY_PREFS to "{\"first\":1}",
            ConfigBackup.ENTRY_PREFS to "{\"second\":2}",
            ConfigBackup.ENTRY_USER_FREQ to "# freq\n",
        )

        assertEquals("{\"first\":1}", ConfigBackupManager.readEntry(zip, ConfigBackup.ENTRY_PREFS))

        val got = ConfigBackupManager.readSections(
            zip,
            listOf(ConfigBackup.ENTRY_PREFS, ConfigBackup.ENTRY_USER_FREQ),
        )
        assertEquals(
            "同一节重复时也要取首条",
            "{\"first\":1}",
            (got[ConfigBackup.ENTRY_PREFS] as ConfigBackupManager.SectionRead.Ok).text,
        )
        assertEquals(
            "重复条目之后的节仍要读到",
            "# freq\n",
            (got[ConfigBackup.ENTRY_USER_FREQ] as ConfigBackupManager.SectionRead.Ok).text,
        )
    }

    @Test
    fun `导入互斥门不可重入且释放后可再次进入`() {
        assertTrue("首次应拿到导入位", ConfigBackupManager.beginImport())
        assertTrue("进行中对外可见（页面重建后据此拒绝第二次）", ConfigBackupManager.importing)
        assertFalse("重入必须被拒", ConfigBackupManager.beginImport())
        ConfigBackupManager.endImport()
        assertFalse(ConfigBackupManager.importing)
        assertTrue("释放后可以再导入", ConfigBackupManager.beginImport())
        ConfigBackupManager.endImport()
    }

    @Test
    fun `摘要算法固定`() {
        // 空串的 SHA-256 是公开已知值：同时钉住「按 UTF-8 编码」与「hex 小写」两个要点。
        // 没有 golden 的哈希测试都是自洽式验证（实现改成别的算法也照样绿）。
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ConfigBackup.sha256(""),
        )
        // 聚合摘要必须与独立实现（见 [dictsDigestOf]）一致
        val contentA = ByteArray(8) { it.toByte() }
        val contentB = ByteArray(8) { (it + 5).toByte() }
        assertEquals(
            dictsDigestOf("a.xz" to contentA, "b.xz" to contentB),
            ConfigBackupManager.dictsDigest(
                listOf("b.xz" to ConfigBackup.sha256(contentB), "a.xz" to ConfigBackup.sha256(contentA)),
            ),
        )
    }
}
