package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃日志快照的隐私过滤护栏。
 *
 * 背景：V 级是「不落盘」的通道，用户正文（搜索关键词、拼音串、候选词、测试框文本）
 * 都走它；但崩溃时 `Diagnostics.dumpLogcat` 抓的是**整个 logcat 缓冲区**，V 行照样在里面 ——
 * 快照一落盘，「V 不落盘」就被崩溃路径绕过了，导出诊断包还会把它带到共享存储。
 * 这里把「只剔本进程的 V 行」这条判据钉成纯函数测试。
 */
class DiagnosticsLogcatFilterTest {

    private val pid = 12345

    private fun line(owner: Int, level: Char, tag: String, msg: String): String =
        "09-20 04:20:30.123  $owner  ${owner + 1} $level $tag: $msg"

    @Test
    fun 本进程的V行被剔除而同级的E行保留() {
        val raw = listOf(
            line(pid, 'V', "PinyinKeyboard", "拼音输入[全拼]: nihao"),
            line(pid, 'V', "SearchPanel", "搜索完成: \"口令\""),
            line(pid, 'E', "JinnDiag", "Crash 未捕获异常"),
        ).joinToString("\n") + "\n"

        val out = Diagnostics.filterOwnVerboseLines(raw, pid)

        assertFalse("本进程 V 行必须被剔除（拼音串）", out.contains("nihao"))
        assertFalse("本进程 V 行必须被剔除（搜索关键词）", out.contains("口令"))
        assertTrue("崩溃本身（E 级）必须保留", out.contains("Crash 未捕获异常"))
    }

    @Test
    fun 其它进程的V行不受影响() {
        val raw = line(pid + 7, 'V', "OtherApp", "别人的日志") + "\n"
        assertEquals(raw, Diagnostics.filterOwnVerboseLines(raw, pid))
    }

    @Test
    fun 被剔除行的续行一并剔除() {
        val raw = buildString {
            appendLine(line(pid, 'V', "PinyinKeyboard", "拼音输入: nihao"))
            appendLine("java.lang.RuntimeException: boom")      // 续行不含行首字段
            appendLine("    at com.jinn.inputmethod.Foo.bar(Foo.kt:1)")
            appendLine(line(pid, 'E', "JinnDiag", "Crash"))
        }

        val out = Diagnostics.filterOwnVerboseLines(raw, pid)

        assertFalse(out.contains("nihao"))
        assertFalse("续行会让被剔除的上下文露出来，必须一起剔", out.contains("boom"))
        assertTrue(out.contains("Crash"))
    }

    @Test
    fun 无行首字段的裸文本按续行处理不误删() {
        // 整段都没有 threadtime 行首：判不出归属就原样保留（宁可多留，不可误删崩溃信息）
        val raw = "随便一段没有行首的文本\n第二行\n"
        assertEquals(raw, Diagnostics.filterOwnVerboseLines(raw, pid))
    }
}
