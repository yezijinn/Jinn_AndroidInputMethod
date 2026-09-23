package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置备份包：格式编解码、版本兼容与合并语义的守卫（纯 JVM，无需设备）。
 *
 * 这些不变量是「跨设备迁移」能成立的前提：类型标记错一个，导入后 SharedPreferences
 * 会按错误类型取值（Int 写进 Float 键 → 运行期 ClassCastException）；合并语义错一个，
 * 用户要么丢词频、要么看到权重翻倍。
 */
class ConfigBackupTest {

    private fun bv(raw: Any?): ConfigBackup.BackupValue =
        ConfigBackup.BackupValue.of(raw) ?: error("不支持的类型: $raw")

    private fun prefsFiles(vararg entries: Pair<String, ConfigBackup.BackupValue>) =
        linkedMapOf(ConfigBackup.PREFS_MAIN to linkedMapOf(*entries))

    // ── 往返：双文件、数值边界、畸形输入 ─────────────────────

    @Test
    fun `两个prefs文件都能往返`() {
        // 生产路径恒为两个文件（主设置 + 剪贴板），文件之间的拼接此前没有任何守卫
        val files = linkedMapOf(
            ConfigBackup.PREFS_MAIN to linkedMapOf(
                "host" to bv("192.168.1.10"),
                "port" to bv(8080),
                "use_shuangpin" to bv(true),
            ),
            ConfigBackup.PREFS_CLIPBOARD to linkedMapOf(
                "enabled" to bv(true),
                "max_items" to bv(500),
            ),
        )

        val decoded = ConfigBackup.decodePrefs(ConfigBackup.encodePrefs(files))

        assertEquals(
            setOf(ConfigBackup.PREFS_MAIN, ConfigBackup.PREFS_CLIPBOARD),
            decoded.keys,
        )
        assertEquals("192.168.1.10", decoded.getValue(ConfigBackup.PREFS_MAIN).getValue("host").value)
        assertEquals(500, decoded.getValue(ConfigBackup.PREFS_CLIPBOARD).getValue("max_items").value)
    }

    @Test
    fun `数值边界逐位往返Int与Long的极值不失真`() {
        val files = prefsFiles(
            "int_min" to bv(Int.MIN_VALUE),
            "int_max" to bv(Int.MAX_VALUE),
            "long_min" to bv(Long.MIN_VALUE),
            "long_max" to bv(Long.MAX_VALUE),
            "float_half" to bv(0.5f),
            "float_step" to bv(0.05f),
        )

        val m = ConfigBackup.decodePrefs(ConfigBackup.encodePrefs(files))
            .getValue(ConfigBackup.PREFS_MAIN)

        assertEquals(Int.MIN_VALUE, m.getValue("int_min").value)
        assertEquals(Int.MAX_VALUE, m.getValue("int_max").value)
        assertEquals(Long.MIN_VALUE, m.getValue("long_min").value)
        assertEquals(Long.MAX_VALUE, m.getValue("long_max").value)
        // Float 以 %.6f 落盘：0.5 与两位小数都能精确往返（定义域内的取值都远大于该精度）
        assertEquals(0.5f, m.getValue("float_half").value as Float, 0f)
        assertEquals(0.05f, m.getValue("float_step").value as Float, 1e-6f)
    }

    @Test
    fun `字符串里的引号反斜杠换行制表与emoji都能往返`() {
        val tricky = "a\"b\\c\nd\te\u0001f 中文 😀 𝄞"
        val files = prefsFiles("prompt" to bv(tricky))

        val decoded = ConfigBackup.decodePrefs(ConfigBackup.encodePrefs(files))

        assertEquals(tricky, decoded.getValue(ConfigBackup.PREFS_MAIN).getValue("prompt").value)
    }

    @Test
    fun `畸形输入不抛异常且类型不符的项被标成不支持`() {
        val cases = listOf(
            "{\"jinn_inputmethod\": {\"k\": {\"t\": \"s\"",                     // 半截 JSON
            "{\"jinn_inputmethod\": {\"k\": {\"t\": \"i\", \"v\": \"abc\"}}}", // 类型标记与值不符
            "{\"\": {\"\": {\"t\": \"s\", \"v\": \"x\"}}}",                     // 空键名
            "{\"jinn_inputmethod\": {\"k\": {\"t\": \"int\", \"v\": 7}}}",     // 多字符类型标记
        )
        for (text in cases) {
            val decoded = runCatching { ConfigBackup.decodePrefs(text) }.getOrNull()
            assertNotNull("decodePrefs 不该抛异常：$text", decoded)
        }
        // 类型不符必须被标成「不支持」，否则上层会按错误类型写入（运行期 ClassCastException）
        val mismatch = ConfigBackup.decodePrefs(
            "{\"jinn_inputmethod\": {\"k\": {\"t\": \"i\", \"v\": \"abc\"}}}"
        )
        val v = mismatch[ConfigBackup.PREFS_MAIN]?.get("k")
        assertTrue("类型不符的项必须不被支持", v == null || !v.isSupported)
    }

    @Test
    fun `剪贴板导入有条数上限`() {
        // 单节 16MB 挡不住「极短条目」的洪流：没有条数上限时，构造包会让导入卡上几分钟
        val incoming = (1..(ConfigBackup.MAX_CLIP_IMPORT_ITEMS + 25)).map {
            ConfigBackup.ClipEntry(
                content = "内容$it",
                createdAt = 1_000L + it,
                sourcePackage = "pkg",
                sourceAppName = "App",
                category = "text",
                favorite = false,
            )
        }

        val planned = ConfigBackup.planClipboardImport(emptySet(), incoming)

        assertEquals(ConfigBackup.MAX_CLIP_IMPORT_ITEMS, planned.size)
    }

    // ── manifest ────────────────────────────────────────────

    @Test
    fun `manifest 往返保留版本 时间 设备与各节摘要`() {
        val m = ConfigBackup.Manifest(
            formatVersion = ConfigBackup.FORMAT_VERSION,
            appVersionCode = 20260923,
            createdAt = 1_758_600_000_000L,
            device = "PACM00 测试机 / Android 10",
            sections = linkedMapOf(
                ConfigBackup.SEC_PREFS to ConfigBackup.Section("aa", 12L),
                ConfigBackup.SEC_USER_FREQ to ConfigBackup.Section("bb", 34L),
            ),
        )
        val parsed = ConfigBackup.parseManifest(ConfigBackup.encodeManifest(m))
        assertNotNull(parsed)
        assertEquals(m.formatVersion, parsed!!.formatVersion)
        assertEquals(m.appVersionCode, parsed.appVersionCode)
        assertEquals(m.createdAt, parsed.createdAt)
        assertEquals(m.device, parsed.device)
        assertEquals(m.sections.keys, parsed.sections.keys)
        assertEquals("bb", parsed.sections[ConfigBackup.SEC_USER_FREQ]?.sha256)
        assertEquals(34L, parsed.sections[ConfigBackup.SEC_USER_FREQ]?.bytes)
    }

    @Test
    fun `不是本程序的包一律解析失败`() {
        assertNull(ConfigBackup.parseManifest("{\"format\":\"other\",\"formatVersion\":1}"))
        assertNull(ConfigBackup.parseManifest("{\"format\":\"jinn-config\"}"))
        assertNull(ConfigBackup.parseManifest("not json at all"))
        assertNull(ConfigBackup.parseManifest(""))
    }

    @Test
    fun `版本判据 高于本版拒绝 不高于本版放行`() {
        assertEquals(
            ConfigBackup.VersionCompat.TOO_NEW,
            ConfigBackup.checkVersion(ConfigBackup.FORMAT_VERSION + 1),
        )
        assertEquals(ConfigBackup.VersionCompat.OK, ConfigBackup.checkVersion(ConfigBackup.FORMAT_VERSION))
        assertEquals(ConfigBackup.VersionCompat.OK, ConfigBackup.checkVersion(1))
    }

    // ── prefs 编解码 ─────────────────────────────────────────

    @Test
    fun `prefs 往返保留六种类型与 null 语义`() {
        val src = prefsFiles(
            "host" to bv("192.168.1.3"),
            "port" to bv(6016),
            "update_at" to bv(1_700_000_000_000L),
            "corner" to bv(1.5f),
            "enabled" to bv(true),
            "favorite_symbols" to ConfigBackup.BackupValue.of(null)!!,
        )
        val parsed = ConfigBackup.decodePrefs(ConfigBackup.encodePrefs(src))
        val main = parsed[ConfigBackup.PREFS_MAIN]
        assertNotNull(main)
        assertEquals("192.168.1.3", main!!["host"]?.value)
        assertEquals(6016, main["port"]?.value as Int)
        assertEquals(1_700_000_000_000L, main["update_at"]?.value as Long)
        assertEquals(1.5f, main["corner"]?.value as Float)
        assertEquals(true, main["enabled"]?.value as Boolean)
        assertEquals(ConfigBackup.BackupValue.KIND_NULL, main["favorite_symbols"]?.kind)
        assertNull(main["favorite_symbols"]?.value)
        // 类型标记必须原样带回：Int / Long / Float 在 SharedPreferences 里是三种类型
        assertEquals(ConfigBackup.BackupValue.KIND_INT, main["port"]?.kind)
        assertEquals(ConfigBackup.BackupValue.KIND_LONG, main["update_at"]?.kind)
        assertEquals(ConfigBackup.BackupValue.KIND_FLOAT, main["corner"]?.kind)
    }

    @Test
    fun `prefs 编码键序稳定（可用于 diff 与内容核对）`() {
        val src = prefsFiles("b" to bv(1), "a" to bv(2), "c" to bv(3))
        assertEquals(ConfigBackup.encodePrefs(src), ConfigBackup.encodePrefs(src))
        val text = ConfigBackup.encodePrefs(src)
        assertTrue(text.indexOf("\"b\"") < text.indexOf("\"a\""))
    }

    @Test
    fun `字符串值里的引号 反斜杠 换行与控制字符都能往返`() {
        val tricky = "含\"引号\" \\ 反斜杠\n换行\t制表\u0001控制符 中文"
        val src = prefsFiles("prompt" to bv(tricky))
        val parsed = ConfigBackup.decodePrefs(ConfigBackup.encodePrefs(src))
        assertEquals(tricky, parsed[ConfigBackup.PREFS_MAIN]?.get("prompt")?.value)
    }

    @Test
    fun `未知类型标记的键被丢弃 结构非法返回空表`() {
        val bad = "{\"jinn_inputmethod\": {\"x\": {\"t\": \"z\", \"v\": 1}}}"
        assertTrue(ConfigBackup.decodePrefs(bad)[ConfigBackup.PREFS_MAIN]?.isEmpty() ?: true)
        assertTrue(ConfigBackup.decodePrefs("[]").isEmpty())
        assertTrue(ConfigBackup.decodePrefs("乱码").isEmpty())
    }

    @Test
    fun `摘要与内容一致（导入前校验的判据）`() {
        val text = ConfigBackup.encodePrefs(prefsFiles("host" to bv("1.2.3.4")))
        assertEquals(
            ConfigBackup.sha256(text),
            ConfigBackup.sha256(text.toByteArray(Charsets.UTF_8)),
        )
        assertTrue(ConfigBackup.sha256(text).length == 64)
    }

    // ── 词频合并 ─────────────────────────────────────────────

    private fun freqText(vararg rows: Triple<String, Double, Int>): String = buildString {
        append("# jinn user_freq v1\n")
        for ((word, weight, day) in rows) {
            append(word).append('\t')
                .append(String.format(java.util.Locale.US, "%.3f", weight)).append('\t')
                .append(day).append('\n')
        }
    }

    @Test
    fun `词频合并 同词取较大权重与较新天 不叠加`() {
        val now = 1000
        val local = freqText(Triple("你好", 1.0, now), Triple("世界", 0.5, now))
        val incoming = freqText(Triple("你好", 0.8, now), Triple("输入法", 2.0, now))
        val merged = UserFrequency.mergeForBackup(local, incoming, now)
        val parsed = UserFrequency.parse(merged.text, now)
        assertEquals(1.0, parsed["你好"]!!.first, 1e-6)
        assertEquals(0.5, parsed["世界"]!!.first, 1e-6)
        assertEquals(2.0, parsed["输入法"]!!.first, 1e-6)
        assertEquals(3, merged.mergedCount)
        assertEquals(2, merged.localCount)
        assertEquals(2, merged.incomingCount)
    }

    @Test
    fun `词频合并 保留较新的天计数`() {
        val local = freqText(Triple("词", 1.0, 900))
        val incoming = freqText(Triple("词", 0.9, 1000))
        val merged = UserFrequency.mergeForBackup(local, incoming, 1000)
        val day = merged.text.lineSequence().first { it.startsWith("词\t") }.split('\t')[2].toInt()
        assertEquals(1000, day)
    }

    @Test
    fun `词频合并 覆盖模式（本机传空）只留备份内容`() {
        val now = 1000
        val local = freqText(Triple("本机词", 5.0, now))
        val incoming = freqText(Triple("备份词", 1.0, now))
        val merged = UserFrequency.mergeForBackup("", incoming, now)
        val parsed = UserFrequency.parse(merged.text, now)
        assertNull(parsed["本机词"])
        assertNotNull(parsed["备份词"])
        assertEquals(0, merged.localCount)
        assertEquals(1, merged.mergedCount)
        // 传入的 localText 只影响结果，不影响本机文件（本方法不改任何持久化状态）
        assertTrue(local.isNotEmpty())
    }

    @Test
    fun `词频合并 低于阈值与坏行都不进结果`() {
        val now = 1000
        val incoming = buildString {
            append("# jinn user_freq v1\n")
            append("太弱\t0.100\t").append(now).append('\n')
            append("够强\t0.200\t").append(now).append('\n')
            append("字段不够\n")
            append("权重非法\tabc\t").append(now).append('\n')
            append("NaN 权重\tNaN\t").append(now).append('\n')
        }
        val merged = UserFrequency.mergeForBackup("", incoming, now)
        val parsed = UserFrequency.parse(merged.text, now)
        assertNull(parsed["太弱"])
        assertNull(parsed["字段不够"])
        assertNull(parsed["权重非法"])
        assertNull(parsed["NaN 权重"])
        assertNotNull(parsed["够强"])
    }

    @Test
    fun `词频合并 结果按权重降序且不超过上限`() {
        val now = 1000
        val rows = StringBuilder("# jinn user_freq v1\n")
        // 权重统一抬到 MIN_WEIGHT(0.15) 以上，否则低权重词会先被阈值过滤掉，
        // 测的就不是「上限截断」而是「阈值过滤」
        for (i in 1..3005) {
            rows.append("词$i").append('\t')
                .append(String.format(java.util.Locale.US, "%.3f", 1.0 + i / 100.0)).append('\t')
                .append(now).append('\n')
        }
        val merged = UserFrequency.mergeForBackup("", rows.toString(), now)
        assertEquals(3000, merged.mergedCount)
        val lines = merged.text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        assertEquals(3000, lines.size)
        // 降序：第一条是权重最大的「词3005」，被截掉的是小权重
        assertTrue(lines.first().startsWith("词3005\t"))
        assertTrue(lines.none { it.startsWith("词1\t") })
    }

    @Test
    fun `词频合并 幂等（对结果再合并一次不变）`() {
        val now = 1000
        val local = freqText(Triple("甲", 1.0, now), Triple("乙", 0.6, now))
        val incoming = freqText(Triple("乙", 0.9, now), Triple("丙", 0.3, now))
        val once = UserFrequency.mergeForBackup(local, incoming, now).text
        val twice = UserFrequency.mergeForBackup(once, "", now).text
        assertEquals(once, twice)
    }

    // ── 剪贴板 ───────────────────────────────────────────────

    private fun clip(content: String, at: Long, fav: Boolean = false) = ConfigBackup.ClipEntry(
        content = content,
        createdAt = at,
        sourcePackage = "com.example",
        sourceAppName = "示例应用",
        category = "OTHER",
        favorite = fav,
    )

    @Test
    fun `剪贴板 jsonl 往返保留全部字段与多行内容`() {
        val src = listOf(
            clip("单行文本", 1_700_000_000_000L, fav = true),
            clip("多行\n内容\t带制表与\"引号\"", 1_700_000_001_000L),
            clip("https://example.com/a?b=1&c=2", 1_700_000_002_000L),
        )
        val parsed = ConfigBackup.decodeClipboard(ConfigBackup.encodeClipboard(src))
        assertEquals(3, parsed.size)
        assertEquals(src.map { it.content }, parsed.map { it.content })
        assertEquals(src.map { it.createdAt }, parsed.map { it.createdAt })
        assertEquals(src.map { it.favorite }, parsed.map { it.favorite })
        assertEquals("示例应用", parsed[0].sourceAppName)
    }

    @Test
    fun `剪贴板 jsonl 坏行跳过而不抛异常`() {
        val text = buildString {
            append(ConfigBackup.encodeClipboard(listOf(clip("好的一行", 1L))))
            append("这不是 json\n")
            append("{}\n")
            append("{\"content\": \"\", \"createdAt\": 5}\n")
            append("{\"createdAt\": 9}\n")
        }
        val parsed = ConfigBackup.decodeClipboard(text)
        assertEquals(1, parsed.size)
        assertEquals("好的一行", parsed[0].content)
    }

    @Test
    fun `剪贴板导入规划 跳过本机已有 批内去重 按时间升序`() {
        val existing = setOf(ConfigBackup.clipboardHash("已存在"))
        val incoming = listOf(
            clip("新词 B", 300L),
            clip("已存在", 100L),
            clip("新词 A", 100L),
            clip("新词 A", 200L),   // 批内重复（内容相同）
        )
        val planned = ConfigBackup.planClipboardImport(existing, incoming)
        assertEquals(2, planned.size)
        assertEquals(listOf("新词 A", "新词 B"), planned.map { it.content })
        // 时间升序：入库后天然保持「早的在下、晚的在上」
        assertTrue(planned[0].createdAt <= planned[1].createdAt)
    }

    @Test
    fun `剪贴板哈希与库内容哈希一致`() {
        val text = "跨设备一致性"
        assertEquals(ClipboardDb.stableHash(text), ConfigBackup.clipboardHash(text))
        assertEquals(64, ConfigBackup.clipboardHash(text).length)
    }

    @Test
    fun `非有限浮点值不进包（否则写出非法 JSON 会让整节解析失败）`() {
        assertNull(ConfigBackup.BackupValue.of(Float.NaN))
        assertNull(ConfigBackup.BackupValue.of(Float.POSITIVE_INFINITY))
        assertNull(ConfigBackup.BackupValue.of(Float.NEGATIVE_INFINITY))
        assertNotNull(ConfigBackup.BackupValue.of(0.5f))
        assertNotNull(ConfigBackup.BackupValue.of(0f))
    }
}
