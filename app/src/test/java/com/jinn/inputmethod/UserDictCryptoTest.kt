package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.crypto.spec.SecretKeySpec

/**
 * 台账加密（[LedgerCrypto] / [SelectionLedger]）与加密导出包（[UserDictExport]）的守卫。
 *
 * 对着七条不变式钉：
 *  ① 落盘内容不含明文词条（`su 0 cat` 口径的单测化）；
 *  ② 行级加密往返一致；
 *  ③ 残行截断在加密格式下仍成立（半行不与下一次追加拼成脏词）；
 *  ④ 密钥别名独立于剪贴板 / 凭据；
 *  ⑤ 导出密码规则复用 `ConfigCrypto.validatePassword`（不自写一套）；
 *  ⑥ 导出包往返：加密 → 解密 → 内层条目是明文、内容与导出文本一致；
 *  ⑦ 每个审查发现的缺陷各有一条回归守卫（写入路径单一、密码不落 Bundle、空读上限、暂存件必清）。
 *
 * 密钥在测试里直接给（[SecretKeySpec]）：行级 AEAD 是纯 JCE，真正的 Keystore 包裹
 * （[LedgerCrypto.dataKey]）只在设备上走，JVM 侧用注入密钥覆盖全部格式与口径。
 */
class UserDictCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 固定 32 字节密钥：只为复现格式，不含任何真实密钥 */
    private val key = SecretKeySpec(ByteArray(32) { (it * 7 + 3).toByte() }, "AES")

    private fun ledgerFile(): File = File(tmp.newFolder(), SelectionLedger.FILE_NAME)

    private fun decoded(f: File): List<String> =
        SelectionLedger.decodeRecords(f.readText(Charsets.UTF_8), key)
            .split("\n")
            .filter { it.isNotEmpty() }

    // ── ① 落盘不含明文 ────────────────────────────────────────

    @Test
    fun `台账落盘不得含明文词条`() {
        val f = ledgerFile()
        assertTrue(SelectionLedger.writeLinesEncrypted(f, listOf("五湖\twu hu", "四海\tsi hai"), key))
        val raw = f.readText(Charsets.UTF_8)
        assertFalse("词面不得出现在磁盘上", "五湖" in raw || "四海" in raw)
        assertFalse("读音不得出现在磁盘上", "wu hu" in raw || "si hai" in raw)
        assertTrue("首行是加密格式标识", raw.startsWith(LedgerCrypto.HEADER + "\n"))
        // 每行都是「前缀 + base64」，不含任何非 ASCII 字节
        for (line in raw.lines().filter { it.isNotEmpty() && !it.startsWith("#") }) {
            assertTrue("记录行必须是 $ 前缀 + ASCII", line.startsWith(LedgerCrypto.RECORD_PREFIX))
            assertTrue("记录行必须全是 ASCII", line.all { it.code in 0x20..0x7e })
        }
    }

    // ── ② 往返 ──────────────────────────────────────────────

    @Test
    fun `行级加密往返_读回与写入逐行一致`() {
        val f = ledgerFile()
        val lines = listOf("五湖\twu hu", "长大\tzhang da", "长大\tchang da", "你")
        assertTrue(SelectionLedger.writeLinesEncrypted(f, lines, key))
        assertEquals(lines, decoded(f))
        // 解析层口径不变：词 + 读音、缺读音记空串
        assertEquals(
            listOf("五湖" to "wu hu", "长大" to "zhang da", "长大" to "chang da", "你" to ""),
            SelectionLedger.parse(SelectionLedger.decodeRecords(f.readText(Charsets.UTF_8), key)),
        )
    }

    @Test
    fun `同一条明文两次加密产出不同记录（随机IV）`() {
        val a = LedgerCrypto.encryptLine("五湖\twu hu", key)
        val b = LedgerCrypto.encryptLine("五湖\twu hu", key)
        assertFalse("GCM 的 IV 必须每次随机", a == b)
        assertEquals("五湖\twu hu", LedgerCrypto.decryptLine(a, key))
        assertEquals("五湖\twu hu", LedgerCrypto.decryptLine(b, key))
    }

    // ── ③ 残行 ──────────────────────────────────────────────

    /**
     * 加密格式的残行口径与明文完全一致：记录同样「一行一条、`\n` 结尾」，
     * 缺 `\n` 的半条被按字节截掉，不会与下一次追加拼成一条可解密的脏记录。
     */
    @Test
    fun `残行_加密格式下不与后续追加拼接`() {
        val f = ledgerFile()
        assertTrue(SelectionLedger.writeLinesEncrypted(f, listOf("你好\tni hao"), key))
        // 模拟写入中断：再追加一条记录但只落一半字节（且没有换行）
        val half = LedgerCrypto.encryptLine("四海\tsi hai", key)
        f.appendBytes(half.substring(0, half.length / 2).toByteArray(Charsets.US_ASCII))
        assertEquals("半条记录不得被读成词条", listOf("你好\tni hao"), decoded(f))

        // 下一次正常追加：先截残行
        assertTrue(SelectionLedger.writeLinesEncrypted(f, listOf("好\tni hao"), key))
        assertEquals(listOf("你好\tni hao", "好\tni hao"), decoded(f))
        // 关键不变式：绝不能出现残行与首行拼出来的脏词
        val picks = SelectionLedger.parse(SelectionLedger.decodeRecords(f.readText(Charsets.UTF_8), key))
        assertTrue(picks.none { it.first.contains("si") || it.first == "你好四" })
    }

    @Test
    fun `整文件没有换行时整体清掉_加密格式同款`() {
        val f = ledgerFile()
        f.writeText(LedgerCrypto.RECORD_PREFIX + "AAAA", Charsets.US_ASCII)
        SelectionLedger.truncateTrailingPartialLine(f)
        assertEquals("", f.readText(Charsets.UTF_8))
    }

    // ── 旧版明文迁移 ─────────────────────────────────────────

    @Test
    fun `明文台账首次写入时就地迁移_一条不丢且次序不变`() {
        val f = ledgerFile()
        f.writeText("# jinn picks v1\n五湖\twu hu\n四海\tsi hai\n", Charsets.UTF_8)
        assertTrue(SelectionLedger.writeLinesEncrypted(f, listOf("你\tni"), key))
        assertEquals(listOf("五湖\twu hu", "四海\tsi hai", "你\tni"), decoded(f))
        assertFalse("迁移后磁盘上不得再有明文", "五湖" in f.readText(Charsets.UTF_8))
    }

    @Test
    fun `迁移是幂等的_已是加密格式时不再改写`() {
        val f = ledgerFile()
        assertTrue(SelectionLedger.writeLinesEncrypted(f, listOf("五湖\twu hu"), key))
        val before = f.readBytes()
        assertEquals(
            "已是加密格式 ⇒ 连整份读都不做",
            SelectionLedger.MigrateOutcome.UP_TO_DATE,
            SelectionLedger.migrateIfNeeded(f, key),
        )
        assertTrue("文件不得被改动", before.contentEquals(f.readBytes()))
    }

    // ── 单行损坏只丢该行 ─────────────────────────────────────

    @Test
    fun `单行被改只丢该行_其余照读`() {
        val f = ledgerFile()
        val good1 = LedgerCrypto.encryptLine("五湖\twu hu", key)
        val good2 = LedgerCrypto.encryptLine("四海\tsi hai", key)
        val broken = LedgerCrypto.encryptLine("脏记录\tzang", key).let {
            // 翻掉密文中部一个字符：GCM 认证必然失败
            val at = it.length / 2
            val c = if (it[at] == 'A') 'B' else 'A'
            it.substring(0, at) + c + it.substring(at + 1)
        }
        f.writeText(
            LedgerCrypto.HEADER + "\n" + good1 + "\n" + broken + "\n" + good2 + "\n",
            Charsets.US_ASCII,
        )
        assertEquals(listOf("五湖\twu hu", "四海\tsi hai"), decoded(f))
    }

    @Test
    fun `不认识的记录前缀与空文本都不炸`() {
        assertNull(LedgerCrypto.decryptLine("2:AAAA", key))
        assertNull(LedgerCrypto.decryptLine("1:!!!!", key))
        assertNull(LedgerCrypto.decryptLine("", key))
        assertEquals("", SelectionLedger.decodeRecords("", key))
        assertFalse("空文件不算加密台账", LedgerCrypto.looksEncrypted(""))
        assertFalse("明文台账不算加密台账", LedgerCrypto.looksEncrypted("五湖\twu hu\n"))
        assertTrue(LedgerCrypto.looksEncrypted(LedgerCrypto.HEADER + "\n"))
    }

    // ── ④ 密钥别名独立 ───────────────────────────────────────

    @Test
    fun `密钥别名必须独立于剪贴板与凭据`() {
        fun aliasOf(file: String): String =
            Regex("""KEY_ALIAS\s*=\s*"([^"]+)"""")
                .find(TestSources.codeSource(file))?.groupValues?.get(1)
                ?: error("$file 里找不到 KEY_ALIAS（改名了？本守卫要跟着改）")
        val ledger = aliasOf("LedgerCrypto.kt")
        val clipboard = aliasOf("ClipboardCrypto.kt")
        val credential = aliasOf("CredentialCrypto.kt")
        assertEquals("jinn_ledger_key", ledger)
        assertEquals("三把密钥必须各用各的别名（共用一个 ⇒ 一侧轮换会连带另一侧全解不开）",
            3, setOf(ledger, clipboard, credential).size)
    }

    // ── ⑤ 密码规则复用 ───────────────────────────────────────

    @Test
    fun `导出密码规则必须复用ConfigCrypto`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        // 导出与导入两处都要按同一套规则先校验（错密码不进 PBKDF2 那 1~2 秒）
        assertEquals(
            "导出与导入都要走 ConfigCrypto.validatePassword",
            2,
            Regex("""ConfigCrypto\.validatePassword\(""").findAll(page).count(),
        )
        assertTrue("必须按 Invalid 分支给红字", "ConfigCrypto.PasswordCheck.Invalid" in page)
        // 不得在别处另造一套汉字判定（`Character.UnicodeScript` 只有 ConfigCrypto 该用）
        for (f in listOf("UserDictActivity.kt", "UserDictExport.kt", "LedgerCrypto.kt")) {
            assertFalse("$f 不得自写密码规则（复用 ConfigCrypto.validatePassword）",
                "UnicodeScript" in TestSources.codeSource(f))
        }
        // 加密必须走同一个壳：不另造容器格式
        assertTrue("导出用 ConfigCrypto.encrypt",
            "ConfigCrypto.encrypt(plain, enc, password)" in TestSources.codeSource("UserDictActivity.kt"))
    }

    // ── ⑥ 导出包往返 ─────────────────────────────────────────

    @Test
    fun `导出包往返_解密后条目是明文且内容与导出一致`() {
        val merged = linkedMapOf(("五湖" to "wu hu") to 2, ("四海" to "si hai") to 1)
        val text = SelectionLedger.exportText(merged, SelectionLedger.ExportFormat.TSV)
        val zipBytes = UserDictExport.zipOf(
            UserDictExport.entryName(SelectionLedger.ExportFormat.TSV),
            text,
        )

        // 「压缩包本体加密、包内文件明文」的直接判据：**不带任何密码**的标准 ZipInputStream 就能读出条目
        val plainEntry = readZip(zipBytes)
        assertEquals("user_dict.tsv", plainEntry.first)
        assertEquals(text, plainEntry.second)

        // 整包往返：加密 → 解密 → 同一份 zip → 同一条目
        val password = "甲乙丙丁戊己".toCharArray()
        val plain = File(tmp.newFolder(), "u.zip").apply { writeBytes(zipBytes) }
        val enc = File(tmp.root, "u.zip" + UserDictExport.FILE_SUFFIX)
        assertTrue(ConfigCrypto.encrypt(plain, enc, password, FAST_ITERATIONS))
        assertTrue("加密包必须能被 ConfigCrypto 解密（同一套壳）",
            UserDictExport.looksEncrypted(UserDictExport.readHead(enc)))
        val restored = File(tmp.newFolder(), "back.zip")
        assertTrue(ConfigCrypto.decrypt(enc, restored, password))
        val entry = UserDictExport.readSingleEntry(restored)
        assertNotNull("包内必须有一个词表条目", entry)
        assertEquals("user_dict.tsv", entry!!.first)
        assertEquals(text, entry.second)
        password.fill('\u0000')
    }

    @Test
    fun `加密包识别_旧版明文导出件不算加密包`() {
        val magic = ConfigCrypto.MAGIC.toByteArray(Charsets.US_ASCII)
        assertTrue(UserDictExport.looksEncrypted(magic))
        assertTrue(UserDictExport.looksEncrypted(magic + ByteArray(16)))
        assertFalse("短的读不到 magic", UserDictExport.looksEncrypted(magic.copyOf(3)))
        assertFalse(
            "旧版明文导出件（词\\t拼音）不得被当成加密包",
            UserDictExport.looksEncrypted("词\t拼音\t次数\n五湖\twu hu\t2\n".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `包内条目数异常按不可用处理`() {
        // 两个条目：自产包只有一个，多出来只可能来自被改过的文件
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zos ->
            for (name in listOf("a.txt", "b.txt")) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        val f = tmp.newFile("two.zip").apply { writeBytes(bytes.toByteArray()) }
        assertNull("多条目包一律拒收", UserDictExport.readSingleEntry(f))
    }

    // ── ⑦ 审查修复的回归守卫 ─────────────────────────────────

    @Test
    fun `审查修复_台账写入只有一条路径且都截残行`() {
        val ledger = TestSources.codeSource("SelectionLedger.kt")
        // 批量导入曾经自开 FileOutputStream：既绕过截残行（残行会与首条导入拼成脏词），
        // 又绕过加密（明文直写）。现在两条入口都收敛到 writeLines。
        // 判据不钉局部变量名（改名即误红）：只钉「入口调用 writeLines(file, …)」+「整个文件里
        // 只有一处按追加模式打开文件」+「那一处就在唯一写入口里」
        val writeCall = Regex("""writeLines\(\s*file\s*,""")
        assertTrue("record 的写线程必须经 writeLines", writeCall.containsMatchIn(
            TestSources.blockAfter(ledger, "private fun drainTo("),
        ))
        val appendAll = TestSources.blockAfter(ledger, "fun appendAll(")
        assertTrue("批量导入必须经 writeLines", writeCall.containsMatchIn(appendAll))
        assertEquals(
            "追加模式打开文件只允许一处（多于一处就有一条绕过截残行 / 加密的写路径）",
            1,
            Regex("""FileOutputStream\(file, true\)""").findAll(ledger).count(),
        )
        assertTrue(
            "那一处必须在唯一写入口里",
            "FileOutputStream(file, true)" in TestSources.blockAfter(ledger, "internal fun writeLinesEncrypted("),
        )
        assertTrue(
            "appendAll 要如实回报是否排入写入（Boolean，而不是 Unit）",
            Regex("""fun appendAll\([^)]*\): Boolean""").containsMatchIn(ledger) && "return false" in appendAll,
        )
    }

    @Test
    fun `审查修复_密码不落Bundle且密码框防截屏`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        val saved = TestSources.blockAfter(page, "override fun onSaveInstanceState(")
        assertFalse(
            "密码不得进 onSaveInstanceState（Bundle 会被系统落盘）",
            "pendingPassword" in saved,
        )
        assertTrue("导出格式仍要能跨重建恢复", "KEY_PENDING_FORMAT" in saved)
        // 导出与导入两个密码框都要 FLAG_SECURE。判据不钉赋值形式（`= secureDialog(`），只数调用点
        assertTrue(
            "两个密码框必须都过 secureDialog",
            Regex("""secureDialog\(\s*AlertDialog""").findAll(page).count() >= 2,
        )
        // 清零要覆盖**每一条持密码的分支**：待发密码、导出 finally、导入解密 finally、输入框
        assertTrue(
            "CharArray 至少要三处清零（待发 / 导出 finally / 解密 finally）",
            Regex("""\.fill\('\\u0000'\)""").findAll(page).count() >= 3,
        )
        assertTrue("输入框里的副本也要清（Editable.clear）", "pwd1.text.clear()" in page && "pwd.text.clear()" in page)
    }

    @Test
    fun `审查修复_导入暂存件与空读上限`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        // `InputStream.read` 允许返回 0：两处读流（SAF→缓存、8MB 文本）都必须有进展闸
        assertTrue("空读闸至少两处", Regex("MAX_EMPTY_READS").findAll(page).count() >= 2)
        val destroy = TestSources.blockAfter(page, "override fun onDestroy()")
        assertTrue("页面销毁必须清暂存件（可能是明文）", "clearStaged()" in destroy)
        assertTrue("每个出口都要删暂存件", "dropStaged(" in page)
        assertTrue("导入回执必须区分「未开启」", "TEXT_IMPORT_NOT_ENABLED" in page)
        assertTrue("导入回执必须区分「未写完」", "TEXT_IMPORT_PENDING" in page)
    }

    @Test
    fun `审查修复_SAF截断写两级兜住`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        // provider 不认 "wt" 时可能**抛异常**而不是返回 null：先各自 runCatching，再退回默认 mode。
        // 旧写法一个 runCatching 包住两行，异常直接落到「导出失败」，fallback 永远走不到。
        assertTrue(
            "两级各自兜住",
            "runCatching { contentResolver.openOutputStream(uri, \"wt\") }" in page &&
                "runCatching { contentResolver.openOutputStream(uri) }" in page,
        )
    }

    @Test
    fun `审查修复_导出临时件用完即删`() {
        val src = TestSources.codeSource("UserDictExport.kt")
        assertTrue("必须保证只用一次就回收", "STALE_MIN_AGE_MS" in src)
        assertTrue("清理入口", "fun cleanup(" in src)
        val page = TestSources.codeSource("UserDictActivity.kt")
        val build = TestSources.blockAfter(page, "private fun buildAndWrite(")
        assertTrue("明文 zip 与密文都要删", "plain.delete()" in build && "enc.delete()" in build)
    }

    @Test
    fun `审查修复_密钥生成被ROM拒绝时按阶梯退档`() {
        val src = TestSources.codeSource("LedgerCrypto.kt")
        // 真机实测（MuMu / API 32）：`setUnlockedDeviceRequired(true)` 直接抛 ProviderException，
        // 不退档的话台账整个不可用（写不进、读不出、页面显示 0 条）。
        assertTrue("① 别名已存在直接用", "getAliasKey(KEY_ALIAS)" in src)
        assertTrue("② 严口径仍要尝试", "generate(unlockedRequired = true)" in src)
        assertTrue("② 失败先删半建别名再试一次", "dropAlias(KEY_ALIAS)" in src && "重试一次" in src)
        assertTrue("③ 退一档用独立别名", "generate(unlockedRequired = false, alias = NOLOCK_ALIAS)" in src)
        assertTrue("③ 退档要留一条 W", "本机密钥库不支持锁屏隔离" in src)
        assertTrue("④ 进程内只升级一次", "upgradeTried" in src)
        assertTrue("④ 顺序是先解包再重包裹", "rewrap" in src || "重包裹" in src)
        // 降级状态必须可观测（页面据此如实写文案），而不是只存在于一句日志里
        assertTrue("必须能查出当前是不是退一档", "fun isDegraded(" in src)
        assertTrue("包裹件版本字节要区分两种口径", "WRAP_VERSION_NOLOCK" in src)
    }

    @Test
    fun `判断是否需要迁移时不得读整份文件`() {
        // 每次落盘都会先问一句「要不要迁移」：整份读就把一次按键放大成读几 MB（R2 的反面）
        val body = TestSources.blockAfter(
            TestSources.codeSource("SelectionLedger.kt"),
            "internal fun needsMigration(",
        )
        assertFalse("needsMigration 不得整份读", "readText" in body || "readBytes" in body)
        assertTrue("只读文件头那么长", "ByteArray(LedgerCrypto.HEADER.length)" in body)
    }

    // ── N2：密钥件缺失不得重建 DEK ─────────────────────────────

    @Test
    fun `密钥件缺失时绝不重建DEK_旧台账改名保留`() {
        LedgerCrypto.invalidate()
        val dir = tmp.newFolder()
        val ledger = File(dir, SelectionLedger.FILE_NAME)
        ledger.writeText(
            LedgerCrypto.HEADER + "\n" + LedgerCrypto.encryptLine("五湖\twu hu", key) + "\n",
            Charsets.US_ASCII,
        )

        // 没有 user_picks.key：重建 DEK 会让旧台账永久失读，而新记录又用新密钥写进同一份文件
        assertNull("密钥件缺失且台账非空 ⇒ 必须 fail-closed", LedgerCrypto.dataKey(dir))
        assertFalse("绝不能新建密钥件", File(dir, LedgerCrypto.KEY_FILE).exists())
        assertTrue(
            "旧台账必须原样保留（改名 .orphan，内容还是密文）",
            File(dir, SelectionLedger.FILE_NAME + ".orphan").isFile,
        )
        assertFalse("原路径不能再有台账（否则新密钥会往旧文件里追加）", ledger.exists())
        assertTrue("页面要能拿到一次性提示", LedgerCrypto.consumeOrphanNotice())
        assertFalse("提示只报一次", LedgerCrypto.consumeOrphanNotice())
        LedgerCrypto.invalidate()
    }

    // ── N3：首行标识被删不得当明文迁移 ─────────────────────────

    @Test
    fun `首行标识被删_只重写首行且词条一条不丢`() {
        val f = ledgerFile()
        assertTrue(SelectionLedger.writeLinesEncrypted(f, listOf("五湖\twu hu", "四海\tsi hai"), key))
        // 模拟首行标识被删（外部改写 / 部分恢复 / 手工替换）
        val body = f.readText(Charsets.UTF_8).lineSequence()
            .filterNot { it.startsWith("#") }
            .joinToString("\n") + "\n"
        f.writeText(body, Charsets.US_ASCII)

        assertFalse("首行标识没了", LedgerCrypto.looksEncrypted(f.readText(Charsets.UTF_8)))
        assertTrue("但形态仍是逐行密文", LedgerCrypto.looksLikeRecords(f.readText(Charsets.UTF_8)))

        assertEquals(
            "缺头台账只补首行",
            SelectionLedger.MigrateOutcome.REWRITTEN,
            SelectionLedger.migrateIfNeeded(f, key),
        )
        assertTrue("首行标识必须补回", f.readText(Charsets.UTF_8).startsWith(LedgerCrypto.HEADER + "\n"))
        assertEquals("词条一条不丢、次序不变", listOf("五湖\twu hu", "四海\tsi hai"), decoded(f))
        assertEquals(
            "补完即幂等",
            SelectionLedger.MigrateOutcome.UP_TO_DATE,
            SelectionLedger.migrateIfNeeded(f, key),
        )

        // 读侧判据必须是「标识 or 形态」双判据：只认标识的话，上面这种文件会被明文 parse 成
        // 一堆 base64「词」，用户视角就是台账全毁
        assertTrue(
            "readAll 必须用双判据",
            "looksLikeRecords(text)" in TestSources.codeSource("SelectionLedger.kt"),
        )
    }

    @Test
    fun `缺头台账的形态判据不会把明文误判成密文`() {
        assertFalse(LedgerCrypto.looksLikeRecords("五湖\twu hu\n四海\tsi hai\n"))
        assertFalse(LedgerCrypto.looksLikeRecords(""))
        assertFalse(LedgerCrypto.looksLikeRecords(LedgerCrypto.HEADER + "\n"))
        // 单行密文即成立（一首行就够）
        assertTrue(LedgerCrypto.looksLikeRecords(LedgerCrypto.encryptLine("五湖\twu hu", key) + "\n"))
        // ≥90% 的判据：混一行垃圾仍认得出
        val good = LedgerCrypto.encryptLine("五湖\twu hu", key)
        assertTrue(LedgerCrypto.looksLikeRecords("$good\n$good\n$good\n$good\n$good\n$good\n$good\n$good\n$good\njunk\n"))
    }

    // ── N4：写入失败必须回报 ───────────────────────────────────

    @Test
    fun `写入失败时flush不得报成功`() {
        // 只判「队列空了」正是缺陷：密钥不可用 / 磁盘满时队列照样是空的
        assertTrue(SelectionLedger.flushVerdict(queueEmpty = true, writeOk = true))
        assertFalse("队列空了但写失败 ⇒ 必须 false", SelectionLedger.flushVerdict(queueEmpty = true, writeOk = false))
        assertFalse("没写空 ⇒ false", SelectionLedger.flushVerdict(queueEmpty = false, writeOk = true))
        // 写线程必须把失败写进 lastWriteOk（否则上面那条判据永远拿到 true）
        val ledger = TestSources.codeSource("SelectionLedger.kt")
        assertTrue("drainTo 失败要置位", "lastWriteOk = false" in TestSources.blockAfter(ledger, "private fun drainTo("))
        assertTrue(
            "appendAll 失败也要置位",
            "lastWriteOk = false" in TestSources.blockAfter(ledger, "fun appendAll("),
        )
        assertTrue(
            "flush 提交前复位，才能反映本次结果",
            "lastWriteOk = true" in TestSources.blockAfter(ledger, "fun flush("),
        )
    }

    // ── N5：密钥不可用后不得每键重走 Keystore ──────────────────

    @Test
    fun `密钥不可用后三十秒内不得重复解析`() {
        LedgerCrypto.invalidate()
        val dir = tmp.newFolder()
        // 台账在、密钥件不在 ⇒ 走 fail-closed 分支（第一次会解析并改名保留）
        File(dir, SelectionLedger.FILE_NAME)
            .writeText(LedgerCrypto.HEADER + "\n1:AAAA\n", Charsets.US_ASCII)
        val before = LedgerCrypto.resolveCountForTest
        repeat(3) { assertNull(LedgerCrypto.dataKey(dir)) }
        assertEquals(
            "负缓存生效：三次调用只解析一次（每键都走一遍 binder 正是「不耗电」要防的形态）",
            before + 1,
            LedgerCrypto.resolveCountForTest,
        )
        LedgerCrypto.invalidate()
    }

    // ── N6：迁移要先 fsync 再原子改名 ──────────────────────────

    @Test
    fun `迁移写盘必须先fsync再原子改名`() {
        val ledger = TestSources.codeSource("SelectionLedger.kt")
        assertTrue(
            "迁移要走带 fsync 的原子写",
            "writeAtomicWithSync(" in TestSources.blockAfter(ledger, "internal fun migrateIfNeeded("),
        )
        val writer = TestSources.blockAfter(ledger, "private fun writeAtomicWithSync(")
        assertTrue("必须 fsync 数据块（只改名的话掉电后是「文件在、内容空」）", "fd.sync()" in writer)
        assertTrue("必须原子改名覆盖", "StandardCopyOption.REPLACE_EXISTING" in writer)
        assertTrue("上次中断留下的中间件先删", "tmp.exists() && !tmp.delete()" in writer)
    }

    // ── N7：密码不得留不可擦除的 String 副本 ───────────────────

    @Test
    fun `密码不再产生String副本`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        assertFalse("不得把密码框内容转成 String", "pwd.text.toString()" in page)
        assertFalse("不得把密码框内容转成 String", "pwd1.text.toString()" in page)
        assertTrue("解密收 CharArray", Regex("""private fun decryptImport\(staged: File, password: CharArray\)""").containsMatchIn(page))
        assertTrue("校验直接吃 Editable", "ConfigCrypto.validatePassword(pwd1.text)" in page)
        assertTrue("两次输入用 TextUtils 比对（不建 String）", "TextUtils.equals(pwd1.text, pwd2.text)" in page)
        // 校验函数本身收 CharSequence，才可能不产生 String
        assertTrue(
            "validatePassword 收 CharSequence",
            Regex("""fun validatePassword\(password: CharSequence\)""").containsMatchIn(TestSources.codeSource("ConfigCrypto.kt")),
        )
    }

    // ── N8：配置包导出的 MIME ──────────────────────────────────

    @Test
    fun `配置包导出的MIME必须是二进制`() {
        val settings = TestSources.codeSource("SettingsActivity.kt")
        assertEquals(
            "只有诊断包（真正的 zip）可以用 application/zip",
            1,
            Regex("""CreateDocument\("application/zip"\)""").findAll(settings).count(),
        )
        assertTrue(
            "配置包产物是 JINNENC1 加密壳 ⇒ octet-stream",
            "CreateDocument(\"application/octet-stream\")" in settings,
        )
    }

    // ── N9：清理不得删在途中间件 ───────────────────────────────

    @Test
    fun `残留清理不得删在途中间件`() {
        val src = TestSources.codeSource("UserDictExport.kt")
        assertTrue("必须有在途名册", "fun markInFlight(" in src && "fun release(" in src)
        assertTrue("cleanup 要跳过在途文件", "inFlight" in TestSources.blockAfter(src, "fun cleanup("))
        val page = TestSources.codeSource("UserDictActivity.kt")
        assertTrue("导出中间件登记", "UserDictExport.markInFlight(plain)" in page)
        assertTrue("导入暂存件登记", "UserDictExport.markInFlight(dest)" in page)
        assertTrue("注销登记", "UserDictExport.release(" in page)
        assertTrue(
            "设置页清理要覆盖 userdict/ 目录（否则明文 zip 能活到下次打开用户词库页）",
            "UserDictExport.cleanup" in TestSources.codeSource("SettingsActivity.kt"),
        )
    }

    // ── N10：包内条目名白名单 ──────────────────────────────────

    @Test
    fun `词表包只认白名单条目名`() {
        val good = tmp.newFile("good.zip").apply {
            writeBytes(UserDictExport.zipOf("user_dict.tsv", "五湖\twu hu\t1\n"))
        }
        assertEquals("user_dict.tsv", UserDictExport.readSingleEntry(good)!!.first)
        // 手搓的单条目包（只含 prefs.json）：原先只判条目数，会被当词表 parse 出一堆垃圾词
        val wrong = tmp.newFile("wrong.zip").apply {
            writeBytes(zipOfEntries(listOf("prefs.json" to "{}")))
        }
        assertNull("条目名不在白名单 ⇒ 按不可用处理", UserDictExport.readSingleEntry(wrong))
        val nested = tmp.newFile("nested.zip").apply {
            writeBytes(zipOfEntries(listOf("user_dict.dict.yaml.bak" to "x")))
        }
        assertNull("近似名也不行", UserDictExport.readSingleEntry(nested))
        // 死常量 MAX_ENTRIES 已删（判据换成白名单，靠条目名收口）
        assertFalse(
            "不得再留「条目数上限」这种半截判据",
            "MAX_ENTRIES" in TestSources.codeSource("UserDictExport.kt"),
        )
    }

    // ── N11：IV 长度 ───────────────────────────────────────────

    @Test
    fun `记录格式的IV必须是十二字节`() {
        assertEquals(12, LedgerCrypto.IV_BYTES)
        val record = LedgerCrypto.encryptLine("五湖\twu hu", key)
        val raw = java.util.Base64.getDecoder().decode(record.removePrefix(LedgerCrypto.RECORD_PREFIX))
        assertTrue("记录 = 12B IV + 密文 + 16B tag", raw.size > LedgerCrypto.IV_BYTES + 16)
        // 手工造一条 16 字节 IV 的记录：读侧按 12 字节切，必须解不开（而不是解出错词）
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE, key,
            javax.crypto.spec.GCMParameterSpec(128, ByteArray(16)),
        )
        val ct = cipher.doFinal("五湖\twu hu".toByteArray(Charsets.UTF_8))
        val forged = LedgerCrypto.RECORD_PREFIX +
            java.util.Base64.getEncoder().withoutPadding().encodeToString(cipher.iv + ct)
        assertNull("IV 长度不符必须解不开", LedgerCrypto.decryptLine(forged, key))
        // 写侧要挡住 Provider 返回别的长度，别让它「写得进、读不出」
        assertTrue(
            "写侧必须校验 IV 长度",
            "require(iv.size == IV_BYTES)" in TestSources.codeSource("LedgerCrypto.kt"),
        )
    }

    // ── N12：读流空转触顶必须明确失败 ──────────────────────────

    @Test
    fun `读流无进展时必须明确失败而不是当读完`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        val fn = TestSources.blockAfter(page, "private fun readCapped(")
        assertTrue("必须有空读闸", "MAX_EMPTY_READS" in fn)
        assertTrue(
            "触顶必须 return null（与 stageImport 同口径）",
            Regex("""MAX_EMPTY_READS\)\s*\{[^}]{0,200}?return null""").containsMatchIn(fn),
        )
        assertFalse(
            "不得只 break 出循环（会把半份文件当「未截断的完整文件」导入）",
            Regex("""MAX_EMPTY_READS\)\s*break""").containsMatchIn(fn),
        )
    }

    // ── N13：文案与事实相符 ───────────────────────────────────

    @Test
    fun `页面文案不得声称root也读不到`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        assertFalse(
            "已 root 的运行中设备能以本应用身份调 Keystore，绝对措辞不成立",
            "root 或取证镜像也读不到" in page,
        )
        assertTrue("要如实限定为离线取证镜像", "离线取证镜像读不到" in page)
        assertTrue("退一档机型要如实说明", "本机密钥库不支持锁屏隔离" in page)
        assertTrue("词频也是同一批词，文案要点到", "user_freq.txt" in page)
        assertTrue("密钥件缺失要提示用户", "TEXT_ORPHANED" in page)
    }

    @Test
    fun `审查修复_导出失败只删确实为空的空壳`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        // provider 在重名时可能返回既有文档的 uri：无条件 delete 会毁掉用户上一份备份
        assertTrue("空壳判定", "private fun deleteEmptyTarget(uri: Uri)" in page)
        assertTrue("只删 0 字节", "if (size != 0L) return" in page)
        assertTrue("失败路径走空壳判定", "deleteEmptyTarget(uri)" in page)
        // 无条件删除只允许出现在 `deleteEmptyTarget` 里（且前面必须有 0 字节判定）
        val deleteFn = TestSources.blockAfter(page, "private fun deleteEmptyTarget(uri: Uri)")
        assertTrue("删除只在空壳判定里发生", "contentResolver.delete(uri, null, null)" in deleteFn)
        assertEquals(
            "页面里只允许这一处删除（别处一律走 deleteEmptyTarget）",
            1,
            Regex("""contentResolver\.delete\(uri, null, null\)""").findAll(page).count(),
        )
    }

    // ── 明文导出（外部可读）──────────────────────────────────

    @Test
    fun `对话框按钮与确认框尺寸必须与整页一致`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        // 按钮：八个对话框（开启 / 关闭两步 / 格式选择 / 明文警告 / 导出密码 / 导入密码 / 导入预览）
        // 都要走统一样式，不能用系统默认
        assertTrue("要有统一的按钮刷法", "private fun styleDialogButtons(dialog: AlertDialog)" in page)
        assertEquals(
            "每个对话框 show 之后都要刷一次（共 8 处）",
            8,
            Regex("""styleDialogButtons\((?:dialog|dlg)\)""").findAll(page).count(),
        )
        assertTrue(
            "格式选择的自定义按钮也要用页面规格",
            "private fun settingsButton(label: String): Button" in page,
        )
        assertTrue("自定义按钮取页面同一个 drawable", "R.drawable.btn_aurora_secondary" in page)
        // 两个自定义按钮都必须显式给 40dp：`addView(child)` 只沿用 child 的 lp，
        // 而 Button 的 minHeight 会把没传 params 的那个顶回系统 48dp（实测 168px vs 140px）
        assertTrue("间距与高度走同一个 lp", "private fun btnLp(topDp: Int): LinearLayout.LayoutParams" in page)
        assertTrue("加密包按钮显式传 lp", "box.addView(btnEnc, btnLp(6))" in page)
        assertTrue("明文按钮显式传 lp", "box.addView(btnPlain, btnLp(6))" in page)
        assertFalse(
            "settingsButton 不得再自带 layoutParams（会被 addView 覆盖成死代码）",
            "layoutParams = LinearLayout.LayoutParams(" in
                TestSources.blockAfter(page, "private fun settingsButton("),
        )
        // 尺寸：文字区必须先量后建，不许再按屏高比例占位（那会把窗口撑到近满屏）
        assertFalse("不得按屏高比例占位", "heightPixels * 0.58" in page)
        assertTrue("文字区高度有上限常量", "const val TEXT_MAX_DP = " in page)
        assertTrue(
            "高度按量出的文字高度给",
            "minOf(text.measuredHeight + (4 * density).toInt(), maxTextPx)" in page,
        )
        // 宽度估算：对话框自身的左右占用必须按**每侧**扣，且只能估窄
        // （估宽 ⇒ 行数偏少 ⇒ 视口偏矮 ⇒ 末行落到视口外，真机复现过）
        assertTrue("对话框左右占用按每侧扣", "2 * DIALOG_SIDE_DP * density" in page)
        assertTrue("每侧留量够大", "private const val DIALOG_SIDE_DP = 32" in page)
        assertFalse("不得再把 24dp 当成两侧合计", "(24 * density)" in page)
        val confirmFn = TestSources.blockAfter(page, "private fun confirmView(")
        assertFalse("正文不许设 maxHeight（会切断滚动）", "maxHeight" in confirmFn)
        // 明文导出：页面重建后 UI 缓存为空，必须以磁盘为准；磁盘也空则不留空壳、不谎报成功
        val plainFn = TestSources.blockAfter(page, "private fun writePlainExportOnIo(")
        assertTrue("空快照要回磁盘", "reloadRows()" in plainFn)
        assertTrue("磁盘也空要报没词", "TEXT_NOTHING_TO_EXPORT" in plainFn)
        // 对话框生命周期：八个创建点都要登记，onDestroy 才能统一 dismiss（否则必留 WindowLeaked）
        assertEquals(
            "八个对话框都要登记",
            8,
            Regex("""val (?:dialog|dlg) = tracked\(""").findAll(page).count(),
        )
        assertTrue("销毁时统一关闭对话框", "shownDialog?.dismiss()" in page)
        // 旧的那套「show 之后再收缩」已删除
        assertFalse("不得再留 show 后收缩", "fitConfirmText" in page)
    }

    @Test
    fun `明文导出必须先确认我明白`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        assertTrue("警告文案要说明这是隐私数据", "这份词库，记录你打过的汉字，涉及个人隐私，当心泄密" in page)
        assertTrue("确认词只有一个定义", "internal const val CONFIRM_WORD = \"我明白\"" in page)
        assertTrue(
            "明文分支必须走统一判据 isConfirmText",
            "if (!isConfirmText(edit.text.toString()))" in page,
        )
        val confirmFn = TestSources.blockAfter(page, "private fun askPlainExportConfirm(")
        assertTrue("对不上时必须挡住并提示", "TEXT_NEED_CONFIRM" in confirmFn)
        // 三处危险操作（开启 / 关闭删除 / 导出明文）共用同一个确认词与判据
        val wordDefs = Regex("""const val (\w+) = "我明白"""").findAll(page).toList()
        assertEquals(
            "「我明白」只允许有一个定义，且必须叫 CONFIRM_WORD",
            listOf("CONFIRM_WORD"),
            wordDefs.map { it.groupValues[1] },
        )
        assertTrue("提示语唯一定义", "const val TEXT_CONFIRM_HINT = " in page)
        assertTrue("提示语用户指定原文", "现在输入三个汉字" in page)
        assertTrue("缺输入提示也统一", "const val TEXT_NEED_CONFIRM = " in page)
        // 旧的「大写 OK」口径必须彻底消失（文案、判据、注释都不能留）
        assertFalse("不得再提大写 OK", "大写 OK" in page)
        assertFalse("不得再留 isConfirmOkText", "isConfirmOkText" in page)
        assertFalse("不得再留 CONFIRM_PLAIN", "CONFIRM_PLAIN" in page)
    }

    @Test
    fun `明文导出不得加密且不得复用密码链路`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        val plainFn = TestSources.blockAfter(page, "private fun writePlainExportOnIo(")
        assertFalse("明文导出不过 ConfigCrypto.encrypt", "ConfigCrypto.encrypt" in plainFn)
        assertFalse("明文导出不写密码相关字段", "pendingPassword" in plainFn)
        assertTrue("明文直接写词表文本", "o.write(text.toByteArray(Charsets.UTF_8))" in plainFn)
        // 明文文件名不得带 .jinn 外壳后缀（那是「要输入密码」的提示）
        assertTrue(
            "明文文件名无外壳后缀",
            "fun plainName(format: SelectionLedger.ExportFormat): String = entryName(format)" in
                TestSources.codeSource("UserDictExport.kt"),
        )
    }

    @Test
    fun `明文导出失败同样删空壳_不留半份隐私文件`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        val plainFn = TestSources.blockAfter(page, "private fun writePlainExportOnIo(")
        assertTrue("失败走空壳判定", "if (!ok) deleteEmptyTarget(uri)" in plainFn)
    }

    /** 用标准 zip 读回单条目（不涉及任何密码 —— 这就是「包内明文」的判据） */
    private fun readZip(bytes: ByteArray): Pair<String, String> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            val entry = zis.nextEntry ?: error("zip 里没有条目")
            entry.name to String(zis.readBytes(), Charsets.UTF_8)
        }

    /** 造一个任意条目名的 zip（模拟「手搓的包 / 选错了包」） */
    private fun zipOfEntries(entries: List<Pair<String, String>>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private companion object {
        /** 与 `ConfigCryptoTest` 同口径：低于解密端下限（10 万）的包会被直接拒收 */
        const val FAST_ITERATIONS = 100_000
    }
}
