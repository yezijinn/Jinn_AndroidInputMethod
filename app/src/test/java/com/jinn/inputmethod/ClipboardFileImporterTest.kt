package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 剪贴板「外部文件导入」的纯逻辑护栏（[ClipboardFileImporter]）。
 *
 * 钉住的口径：按行拆分（BOM 剥离 / CRLF 与单独 `\r` 都分行 / 行首尾 trim）、空行跳过、
 * 单行超上限**整行丢弃**（不截断）、稳定哈希去重（库内已有与批内重复都跳过且不覆盖既有）、
 * 文件级文本判定（NUL 或替换字符占比 >1% 中止）、字节与行数两道文件闸（超限中止但保留已导入）、
 * 入库失败不把该内容永久判为「已存在」。
 *
 * 真正的写库（加密 / 分类 / 裁剪）走真机验证 —— 这里只钉纯 JVM 的解析与统计。
 */
class ClipboardFileImporterTest {

    private fun run(
        content: String,
        limitBytes: Int = 1024,
        known: MutableSet<String> = mutableSetOf(),
        maxLines: Int = ClipboardFileImporter.MAX_IMPORT_LINES,
        maxFileBytes: Long = ClipboardFileImporter.MAX_FILE_BYTES,
        accept: (String) -> Boolean = { true },
    ): Pair<ClipboardFileImporter.StreamResult, List<String>> {
        val accepted = ArrayList<String>()
        val result = ClipboardFileImporter.importStream(
            input = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
            limitBytes = limitBytes,
            knownHashes = known,
            onAccepted = { text ->
                if (accept(text)) {
                    accepted.add(text)
                    true
                } else {
                    false
                }
            },
            maxLines = maxLines,
            maxFileBytes = maxFileBytes,
        )
        return result to accepted
    }

    @Test
    fun 按行拆分并剥离BOM与CRLF() {
        val (r, got) = run("\uFEFF第一行\r\n第二行\n最后一行")
        assertEquals(listOf("第一行", "第二行", "最后一行"), got)
        assertEquals(3, r.lines)
        assertEquals(3, r.added)
        assertNull(r.abort)
    }

    @Test
    fun 空行与纯空白行跳过() {
        val (r, got) = run("甲\n\n   \n\t\n乙\n")
        assertEquals(listOf("甲", "乙"), got)
        assertEquals(3, r.blank)
        assertEquals(5, r.lines)
    }

    @Test
    fun 行首尾空白裁掉后再入库() {
        val (_, got) = run("  甲  \n\t乙\t\n")
        assertEquals(listOf("甲", "乙"), got)
    }

    @Test
    fun 单行超上限整行丢弃且不影响后续行() {
        // 上限 4 字节：「abcd」恰好等于上限保留，「abcde」超 1 字节整行丢弃
        val (r, got) = run("abcd\nabcde\nok\n", limitBytes = 4)
        assertEquals(listOf("abcd", "ok"), got)
        assertEquals(1, r.tooLong)
        assertNull(r.abort)
    }

    @Test
    fun 单行上限按UTF8字节而非字符计() {
        // 「汉」3 字节：3 个汉字 = 9 字节恰好保留，4 个 = 12 字节超限丢弃
        val (_, kept) = run("汉语词\n", limitBytes = 9)
        assertEquals(listOf("汉语词"), kept)
        val (r2, dropped) = run("汉语言文\n", limitBytes = 9)
        assertEquals(emptyList<String>(), dropped)
        assertEquals(1, r2.tooLong)
    }

    @Test
    fun 库内已有内容判重跳过() {
        val known = mutableSetOf(ClipboardDb.stableHash("甲"))
        val (r, got) = run("甲\n乙\n", known = known)
        assertEquals(listOf("乙"), got)
        assertEquals(1, r.duplicate)
    }

    @Test
    fun 同文件重复行只入第一条() {
        val (r, got) = run("甲\n甲\n乙\n")
        assertEquals(listOf("甲", "乙"), got)
        assertEquals(1, r.duplicate)
    }

    @Test
    fun 含NUL的内容整份中止() {
        // 第一行正常入库；遇到 NUL 立即中止（后面的行不再读）—— 已导入的部分保留
        val (r, got) = run("ok\n坏\u0000行\n后面\n")
        assertEquals(listOf("ok"), got)
        assertEquals(ClipboardFileImporter.Abort.NOT_TEXT, r.abort)
    }

    @Test
    fun 替换字符占比过高的内容整份中止() {
        // 整份 4 个字符且全是替换字符 ⇒ 占比 100% > 1%，判非文本（与采集侧同判据）
        val (r, got) = run("\uFFFD\uFFFD\uFFFD\uFFFD\n")
        assertEquals(emptyList<String>(), got)
        assertEquals(ClipboardFileImporter.Abort.NOT_TEXT, r.abort)
    }

    @Test
    fun 少量替换字符不误杀正常文本() {
        // 1001 个字符里混 1 个替换字符：占比 0.1% ≤ 1%，照常入库（文件级聚合，不按单行判）
        val (r, got) = run("正".repeat(1000) + "\uFFFD\n", limitBytes = 4096)
        assertEquals(1, r.added)
        assertEquals(1, got.size)
        assertNull(r.abort)
    }

    @Test
    fun 行数超限中止且保留已导入() {
        val (r, got) = run("甲\n乙\n丙\n丁\n戊\n", maxLines = 3)
        assertEquals(listOf("甲", "乙", "丙"), got)
        assertEquals(4, r.lines)
        assertEquals(ClipboardFileImporter.Abort.TOO_MANY_LINES, r.abort)
    }

    @Test
    fun 正好等于行数上限的文件正常结束() {
        val (r, got) = run("甲\n乙\n丙\n", maxLines = 3)
        assertEquals(listOf("甲", "乙", "丙"), got)
        assertNull(r.abort)
    }

    @Test
    fun 文件字节超限中止() {
        // 字节闸按底层已读字节计（含解码器预读），判定在「读下一行之前」：读到的行会先处理，
        // 再判超限 ⇒ 最坏多入库一行，但必然中止（不假定恰好零条）
        val (r, got) = run("甲甲甲甲甲甲甲甲\n", maxFileBytes = 8)
        assertEquals(ClipboardFileImporter.Abort.FILE_TOO_LARGE, r.abort)
        assertTrue("超限中止前最多只应处理一行（实际 ${got.size}）", got.size <= 1)
    }

    @Test
    fun 空文件零条无中止() {
        val (r, got) = run("")
        assertEquals(0, r.lines)
        assertEquals(0, r.added)
        assertNull(r.abort)
        assertTrue(got.isEmpty())
    }

    @Test
    fun 单独回车也分行() {
        val (r, got) = run("甲\r乙\r\n丙")
        assertEquals(listOf("甲", "乙", "丙"), got)
        assertEquals(3, r.added)
    }

    @Test
    fun 入库失败计数且不永久占用去重() {
        // 「甲」两次入库都失败：第二次仍要再试（不能因第一次失败就被当成重复跳过）
        val (r, got) = run("甲\n甲\n乙\n", accept = { it != "甲" })
        assertEquals(listOf("乙"), got)
        assertEquals(2, r.failed)
        assertEquals(1, r.added)
        assertEquals(0, r.duplicate)
    }

    @Test
    fun 无换行巨行超文件预算时早退且如实报中止() {
        // 行闸只在「两行之间」判：旧实现在这种文件上会一路耗到 EOF（1MB 全读完）才中止。
        // 现在读行器每 4K 字符抽样一次预算，命中即早退，并由循环后的终检如实报 FILE_TOO_LARGE
        val totalBytes = 1 shl 20
        var readBytes = 0
        val input = object : java.io.InputStream() {
            override fun read(): Int {
                if (readBytes >= totalBytes) return -1
                readBytes++
                return 'a'.code
            }
        }
        val r = ClipboardFileImporter.importStream(
            input = input,
            limitBytes = 1 shl 16,
            knownHashes = mutableSetOf(),
            onAccepted = { true },
            maxFileBytes = 64L * 1024,
        )
        assertEquals(ClipboardFileImporter.Abort.FILE_TOO_LARGE, r.abort)
        assertEquals(0, r.added)
        assertTrue(
            "实读 $readBytes 字节应接近预算（64KB）而非整份（$totalBytes）",
            readBytes < 256 * 1024,
        )
    }
}
