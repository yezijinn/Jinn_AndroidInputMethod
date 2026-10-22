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
}
