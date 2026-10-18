package com.jinn.inputmethod

import org.junit.Assert.assertEquals
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
        assertTrue("入口文案", "生成用户词库" in settings)
    }

    @Test
    fun `关闭确认只认大写OK`() {
        assertTrue(isConfirmOkText("OK"))
        assertTrue(isConfirmOkText("  OK  "))
        assertTrue("小写不算", !isConfirmOkText("ok"))
        assertTrue("大小写混合不算", !isConfirmOkText("Ok"))
        assertTrue("多余字符不算", !isConfirmOkText("OK!"))
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
}
