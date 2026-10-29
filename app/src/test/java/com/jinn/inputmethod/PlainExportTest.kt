package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class PlainExportTest {

    /** 明文导出的内容 = SelectionLedger.exportText 的直接输出（不经 zip、不经加密） */
    @Test
    fun `明文导出产物是纯文本且与加密包内层一致`() {
        val merged = linkedMapOf(
            ("你好" to "ni hao") to 12,
            ("一" to "yi") to 10,
            ("机" to "ji") to 6,
        )
        val text = SelectionLedger.exportText(merged, SelectionLedger.ExportFormat.TSV)
        // TSV 是纯文本：行首就是词，不是 base64、不是二进制壳、没有 magic
        assertTrue("TSV 首行应是表头或词条", text.lineSequence().first().isNotBlank())
        assertFalse("不得含加密包 magic", text.contains("JINNENC1"))
        assertFalse("不得含行级加密前缀", Regex("""(?m)^1:""").containsMatchIn(text))
        // 加密包的内层 zip 解出来必须与明文导出同一份文本
        val zip = UserDictExport.zipOf(UserDictExport.entryName(SelectionLedger.ExportFormat.TSV), text)
        var name = ""
        var inner = ""
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            val e = zis.nextEntry
            name = e?.name ?: ""
            inner = String(zis.readBytes(), Charsets.UTF_8)
        }
        assertEquals("条目名一致", UserDictExport.entryName(SelectionLedger.ExportFormat.TSV), name)
        assertEquals("包内层与明文导出逐字节同源", text, inner)
    }

    @Test
    fun `明文与加密的文件名只差外壳后缀`() {
        for (f in SelectionLedger.ExportFormat.values()) {
            val plain = UserDictExport.plainName(f)
            val enc = UserDictExport.suggestedName(f)
            assertEquals("明文名 = 条目名", UserDictExport.entryName(f), plain)
            assertEquals("加密名 = 明文名 + .jinn", plain + ".jinn", enc)
            assertFalse("明文不得带外壳后缀（那是「要密码」的提示）", plain.endsWith(".jinn"))
        }
    }

    /**
     * 保存对话框登记的类型必须跟着导出格式走（AI2 A-533）。
     *
     * `ExportFormat.mime` 早先声明了却零使用（导出写死 `application/octet-stream`）⇒ 用户导出的
     * `.txt` / `.dict.yaml` 在文件管理器里是「未知类型」，点开也不走文本编辑器。
     * 这里钉两条：① 三档格式各自带具体文本类型；② 页面用的是「按格式设类型」的契约，
     * 明文导出传 `format.mime`、加密壳才传通用二进制。
     */
    @Test
    fun `导出目标类型必须跟随格式`() {
        for (f in SelectionLedger.ExportFormat.values()) {
            assertTrue("$f 的类型不能是通用二进制（那等于没声明）", f.mime != "application/octet-stream")
            assertTrue("$f 的类型必须是具体子类型", "/" in f.mime && !f.mime.endsWith("/*"))
        }
        assertEquals(
            "三档文本格式的类型必须互不相同（否则区分不了）",
            SelectionLedger.ExportFormat.values().size,
            SelectionLedger.ExportFormat.values().map { it.mime }.toSet().size,
        )
        val page = TestSources.codeSource("UserDictActivity.kt")
        assertTrue("明文导出的登记类型必须取 format.mime", "createDocContract.mimeType = format.mime" in page)
        assertTrue(
            "加密壳才用通用二进制",
            "createDocContract.mimeType = CreateDocumentTyped.MIME_ENCRYPTED_SHELL" in page,
        )
    }

    @Test
    fun `确认词三处共用同一个判据`() {
        val page = TestSources.codeSource("UserDictActivity.kt")
        // 开启 / 关闭删除 / 导出明文：三处都调 isConfirmText，且只有这一个确认词定义
        assertEquals(
            "页内 isConfirmText 调用点应为 3 处（开启、关闭、明文导出）",
            3,
            Regex("""isConfirmText\(edit\.text\.toString\(\)\)""").findAll(page).count(),
        )
        assertEquals(
            "确认词只允许一个定义",
            1,
            Regex("""const val CONFIRM_WORD""").findAll(page).count(),
        )
    }

    /**
     * 导出回执的条数必须等于件内条目数（BUG.md L-1491）。
     *
     * 旧实现按入参表长报「已导出 N 条」：`RIME_YAML` 跳过没有读音的行（写不出合法音节，
     * librime 整条丢弃）、`TXT` 按词去重（同词不同读音只出一行）⇒ 件内条目少于表长，
     * 回执比文件多报，用户拿一个对不上的数字去找原因。
     */
    @Test
    fun `导出回执条数必须等于件内条目数`() {
        val merged = linkedMapOf(
            ("你好" to "ni hao") to 12,
            ("一" to "yi") to 10,
            ("外文" to "") to 3,
            ("你好吗" to "ni hao ma") to 5,
            ("你好" to "") to 2,
        )
        for (f in SelectionLedger.ExportFormat.values()) {
            val text = SelectionLedger.exportText(merged, f)
            val inFile = when (f) {
                // 数据行都带制表符；YAML 的六行头都不带
                SelectionLedger.ExportFormat.RIME_YAML -> text.lines().count { it.contains('\t') }
                // 表头 `词\t拼音\t次数` 也带制表符，减掉它
                SelectionLedger.ExportFormat.TSV -> text.lines().count { it.contains('\t') } - 1
                SelectionLedger.ExportFormat.TXT -> text.lines().count { it.isNotBlank() }
            }
            assertEquals("$f 的回执条数与件内条目数不一致", inFile, SelectionLedger.exportEntryCount(merged, f))
            if (f != SelectionLedger.ExportFormat.TSV) {
                assertTrue("$f 的件内条目必须少于表长（否则本条用例判不出旧写法）", inFile < merged.size)
            }
        }
    }
}
