package com.jinn.inputmethod

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择台账（[SelectionLedger]）：只追加的「词 \t 读音」流水。
 *
 * 三条钉住的口径：一行一次选择、只认以 `\n` 结尾的完整行、文档词与读音相同的行才合并计数。
 */
class SelectionLedgerTest {

    @Test
    fun `拼行_词与读音用制表符分隔`() {
        assertEquals("五湖\twu hu", SelectionLedger.formatLine("五湖", "wu hu"))
    }

    @Test
    fun `拼行_没有读音时只写词`() {
        assertEquals("五湖", SelectionLedger.formatLine("五湖", ""))
        assertEquals("五湖", SelectionLedger.formatLine("五湖", "   "))
    }

    @Test
    fun `拼行_词里的制表符与换行压成空格`() {
        assertEquals("五湖 四海\twu hu", SelectionLedger.formatLine("五湖\t四海\n", "wu hu"))
    }

    @Test
    fun `拼行_空词返回null`() {
        assertNull(SelectionLedger.formatLine("", "wu hu"))
        assertNull(SelectionLedger.formatLine("  \t ", "wu hu"))
    }

    @Test
    fun `解析_只认完整行_末尾残缺行丢弃`() {
        val text = "五湖\twu hu\n四海\tsi hai\n五湖四海\twu hu si hai\n你\tni\n五湖\twu"
        val picks = SelectionLedger.parse(text)
        assertEquals(4, picks.size)
        assertEquals("五湖" to "wu hu", picks[0])
        assertEquals("你" to "ni", picks[3])
    }

    @Test
    fun `解析_空行注释与缺列都容错`() {
        val text = "# jinn picks v1\n\n五湖\n四海\tsi hai\r\n"
        val picks = SelectionLedger.parse(text)
        assertEquals(listOf("五湖" to "", "四海" to "si hai"), picks)
    }

    @Test
    fun `合并_同词同音计数_异读分开`() {
        val picks = listOf(
            "长大" to "zhang da",
            "长大" to "chang da",
            "长大" to "zhang da",
            "五湖" to "wu hu",
        )
        val merged = SelectionLedger.aggregate(picks)
        assertEquals(2, merged["长大" to "zhang da"])
        assertEquals(1, merged["长大" to "chang da"])
        assertEquals(1, merged["五湖" to "wu hu"])
        // 首次出现次序：异读的两条互不覆盖
        assertEquals(listOf("长大" to "zhang da", "长大" to "chang da", "五湖" to "wu hu"), merged.keys.toList())
    }

    @Test
    fun `挂点_候选与追加候选都要进台账`() {
        val view = TestSources.codeOf(
            TestSources.rawSourceOfShortName("PinyinKeyboardView.kt"),
        )
        assertTrue(
            "候选选择必须记台账（按简体归一）",
            "SelectionLedger.record(PinyinEngine.toSimplified(word), pinyin)" in view,
        )
        assertTrue(
            "追加候选要记被点的那一节与合并整词",
            "SelectionLedger.record(PinyinEngine.toSimplified(pred), predPinyin)" in view &&
                "SelectionLedger.record(PinyinEngine.toSimplified(fullWord), fullPinyin)" in view,
        )
        assertTrue("敏感输入框仍由 suppressLearning 挡住", "if (suppressLearning) return" in view)
    }

    @Test
    fun `导出_yaml带表头_tsv带头_txt按词去重`() {
        val merged = linkedMapOf(
            ("五湖" to "wu hu") to 2,
            ("四海" to "si hai") to 1,
            ("五湖" to "wu4 hu") to 1,
        )
        val yaml = SelectionLedger.exportText(merged, SelectionLedger.ExportFormat.RIME_YAML)
        assertTrue("Rime 头", yaml.startsWith("---\nname: user_dict"))
        assertTrue("权重是次数", "五湖\twu hu\t2" in yaml)

        val tsv = SelectionLedger.exportText(merged, SelectionLedger.ExportFormat.TSV)
        assertTrue("TSV 表头", tsv.startsWith("词\t拼音\t次数\n"))
        assertTrue("按次数降序", tsv.indexOf("五湖\twu hu") < tsv.indexOf("四海\tsi hai"))

        val txt = SelectionLedger.exportText(merged, SelectionLedger.ExportFormat.TXT)
        assertEquals("纯词表按词去重", listOf("五湖", "四海"), txt.split("\n").filter { it.isNotEmpty() })
    }

    @Test
    fun `导入_三种格式自适应_按次数展开`() {
        val tsv = "词\t拼音\t次数\n五湖\twu hu\t3\n"
        val expanded = SelectionLedger.parseImport(tsv)
        assertEquals(3, expanded.size)
        assertEquals("五湖" to "wu hu", expanded[0])

        val yaml = "---\nname: x\nversion: \"1\"\n...\n四海\tsi hai\t2\n"
        assertEquals(2, SelectionLedger.parseImport(yaml).size)

        // 只有词的行按 1 次；次数上限与总行数上限都要能兜住
        assertEquals(1, SelectionLedger.parseImport("五湖\n").size)
        assertEquals(
            SelectionLedger.IMPORT_MAX_PER_WORD,
            SelectionLedger.parseImport("五湖\twu hu\t999999\n").size,
        )
    }

    @Test
    fun `词库页_读合并导出导入都接在台账上`() {
        val page = TestSources.codeOf(TestSources.rawSourceOfShortName("UserDictActivity.kt"))
        assertTrue("页面要读全量流水", "SelectionLedger.readAll(applicationContext)" in page)
        assertTrue("导出走同一次合并", "SelectionLedger.exportText(merged, format)" in page)
        assertTrue(
            "导入先解析再追加",
            "SelectionLedger.parseImport(read.text)" in page && "SelectionLedger.appendAll(picked)" in page,
        )
        val settings = TestSources.codeOf(TestSources.rawSourceOfShortName("SettingsActivity.kt"))
        assertTrue("设置页要有入口", "R.id.btn_user_dict" in settings && "UserDictActivity::class.java" in settings)
        // 入口文案钉的是**行为契约**：它是静态动作名，不显示「未开启」状态（作者 2026-10-20 拍板）。
        // 「`某串 in settings`」那种写法对行为零保护 —— 改文案时顺着改一句就恒绿（AI2 A-552 实证），
        // 所以钉反向：入口不得再读开关去改文案 / 显示状态。
        assertFalse(
            "设置页入口不得读 userDictEnabled 显示状态（静态动作名是有意为之，AI2 A-552）",
            "userDictEnabled" in settings || "updateUserDictEntry" in settings,
        )
        // 正向保留一处：文案常量还在，且入口文字确实取自它（不是硬编码第二份）
        assertTrue("入口文字取自 TEXT_USER_DICT", "text = TEXT_USER_DICT" in settings)
    }

    @Test
    fun `关闭确认只认我明白`() {
        assertTrue(isConfirmText("我明白"))
        assertTrue(isConfirmText("  我明白  "))
        assertTrue("少一个字不算", !isConfirmText("我明白".dropLast(1)))
        assertTrue("多一个字不算", !isConfirmText("我明白了"))
        assertTrue("空串不算", !isConfirmText(""))
        assertTrue("别的不算", !isConfirmText("OK"))
    }

    @Test
    fun `关闭时删文件_不存在也算成功`() {
        val f = java.io.File.createTempFile("picks", ".txt")
        assertTrue("已有文件要删掉", SelectionLedger.deleteQuietly(f))
        assertTrue("删完就不存在", !f.exists())
        assertTrue("本来不存在也算成功", SelectionLedger.deleteQuietly(f))
    }

    @Test
    fun `关闭后不得再记录_且不进系统备份`() {
        val src = TestSources.codeOf(TestSources.rawSourceOfShortName("SelectionLedger.kt"))
        assertTrue("record 首行必须挡住关闭态", "if (!enabled) return" in src)
        val backup = TestSources.rawSource("src/main/res/xml/backup_rules.xml", "app/src/main/res/xml/backup_rules.xml")
        val extract = TestSources.rawSource("src/main/res/xml/data_extraction_rules.xml", "app/src/main/res/xml/data_extraction_rules.xml")
        assertTrue("备份规则要排除台账", "user_picks.txt" in backup)
        assertEquals("云备份与设备迁移两段都要排除", 2, Regex("user_picks\\.txt").findAll(extract).count())
    }

    /**
     * 代际号必须**原子**（AI2 A-601）。
     *
     * `@Volatile Int` 的 `++` / `--` 是读-改-写三步：只保证可见性，不保证原子性。删除失败的回滚
     * （`-1`）若与另一趟递增交错，代际守卫会失效 —— 在途批次就会写进「用户明确要求删除」的文件
     * （A-596 那条隐私后果）。**当前**所有调用点都在主线程（串行），所以这是加固而不是现存竞态
     * （与 A-30 / A-634 那类「有窗口但无并发」的判定同口径，区别是这里只花一行原语）；
     * 但它一旦被搬进后台线程就是静默失效，守卫把这条不变式钉在类型上。
     */
    @Test
    fun `代际号必须原子`() {
        val src = TestSources.codeOf(TestSources.rawSourceOfShortName("SelectionLedger.kt"))
        assertTrue("代际必须是 AtomicInteger（并发下 ++ / -- 会丢递增）", "AtomicInteger(" in src)
        assertFalse(
            "代际不得再用非原子的 ++ / --",
            Regex("""generation(\+\+|--)""").containsMatchIn(src) ||
                Regex("""(\+\+|--)generation""").containsMatchIn(src),
        )
        // 非原子递增换个写法照样不是原子：`set(get() + 1)` 与 `++` 同为三步
        assertFalse(
            "代际不得用 set(get() + 1) 拼出非原子递增",
            Regex("""generation\.set\(""").containsMatchIn(src),
        )
        assertTrue("代际必须走 incrementAndGet", "generation.incrementAndGet()" in src)
        assertTrue("代际回滚必须走 decrementAndGet", "generation.decrementAndGet()" in src)
        assertTrue("代际比对要读当前值", "gen != generation.get()" in src)
    }

    /**
     * 残行必须被截掉，而不是留给下一次追加去拼。
     *
     * 背景（2026-10-19）：写入是 FileOutputStream(append) + 逐行 write(line + "\n")，
     * 行与行之间没有任何帧标记。若某次写入中断（掉电 / 进程在 syscall 中途被杀），文件末尾
     * 会留下没有换行的半行；而 parse 只丢弃「末尾没有换行」的那一段 —— 一旦下一次追加把
     * 半行补成了完整行，它就会被当成真数据解析出一个拼接脏词。
     */
    @Test
    fun `残行_截断后不与后续追加拼接`() {
        val f = File.createTempFile("ledger", ".txt")
        try {
            // 一条完整行 + 一条半行（模拟写入中断）
            f.writeText("你好\tni hao\n你", Charsets.UTF_8)
            SelectionLedger.truncateTrailingPartialLine(f)
            // 半行必须消失，且不留多余字节
            assertEquals("你好\tni hao\n", f.readText(Charsets.UTF_8))

            // 之后再追加一行，不能与残行拼成脏词
            f.appendText("好\tni hao\n", Charsets.UTF_8)
            val parsed = SelectionLedger.parse(f.readText(Charsets.UTF_8))
            assertEquals(2, parsed.size)
            assertEquals("你好" to "ni hao", parsed[0])
            assertEquals("好" to "ni hao", parsed[1])
            // 关键不变式：绝不能出现「你」与「好」被残行拼成的脏词
            assertTrue(parsed.none { it.first == "你好" && it.second == "hao" })
        } finally {
            f.delete()
        }
    }

    /** 正常文件（以换行结尾）不该被改动：截断是零写入的快检查 */
    @Test
    fun `完整文件_截断不动`() {
        val f = File.createTempFile("ledger", ".txt")
        try {
            val good = "甲\tjia\n乙\tyi\n"
            f.writeText(good, Charsets.UTF_8)
            SelectionLedger.truncateTrailingPartialLine(f)
            assertEquals(good, f.readText(Charsets.UTF_8))
        } finally {
            f.delete()
        }
    }

    /** 整个文件都没有换行（首行就没写完整）⇒ 整体清掉，不留半行 */
    @Test
    fun `无换行文件_整体清掉`() {
        val f = File.createTempFile("ledger", ".txt")
        try {
            f.writeText("半行", Charsets.UTF_8)
            SelectionLedger.truncateTrailingPartialLine(f)
            assertEquals("", f.readText(Charsets.UTF_8))
        } finally {
            f.delete()
        }
    }

    /** 空文件 / 不存在的文件不能抛（flush 在冷启动就会走到） */
    @Test
    fun `空文件与缺失文件_截断不抛`() {
        val f = File.createTempFile("ledger", ".txt")
        try {
            assertEquals(0L, f.length())
            SelectionLedger.truncateTrailingPartialLine(f)
            SelectionLedger.truncateTrailingPartialLine(File(f.absolutePath + ".nope"))
        } finally {
            f.delete()
        }
    }

    /**
     * `flush(timeoutMs)` 超时只影响「等多久」，**绝不影响数据**（BUG.md L-1451）。
     *
     * `onDestroy` 原先调无参 `flush()`，内部 `Future.get(2s)` 卡在**主线程**；改成限时之后，
     * 必须证明「提前返回 ≠ 丢行」：写盘在台账的 writer 线程上照旧跑完，失败的行留队。
     * 这条用例用 0ms 上限把「立即返回」逼出来，再等一次正常上限，断言行仍在盘上。
     */
    @Test
    fun `限时flush超时不得丢行`() {
        val dir = File.createTempFile("ledgerdir", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, SelectionLedger.FILE_NAME)
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SelectionLedger.clearQueueForTest()
        SelectionLedger.setKeyForTest(key)
        SelectionLedger.setTargetForTest(file, enabled = true)
        try {
            assertTrue("入队失败", SelectionLedger.appendAll(listOf("五湖" to "wu hu")))
            // 0ms：这一等立刻返回（结果 true/false 取决于线程调度，不作断言）
            SelectionLedger.flush(0)
            // 再给足上限：行必须在盘上、队列必须清空
            assertTrue("限时后重试必须成功", SelectionLedger.flush(2_000))
            assertEquals("队列必须清空", 0, SelectionLedger.pendingLinesForTest())
            val plain = SelectionLedger.decodeRecords(file.readText(Charsets.UTF_8), key)
            assertEquals("超时返回不得丢掉那一行", listOf("五湖" to "wu hu"), SelectionLedger.parse(plain))
        } finally {
            SelectionLedger.clearQueueForTest()
            SelectionLedger.setTargetForTest(null, enabled = false)
            SelectionLedger.setKeyForTest(null)
            file.delete()
            dir.delete()
        }
    }
}
