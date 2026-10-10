package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ByteSize] 的口径护栏（2026-10-10 与存储占用页一起引入）。
 *
 * 钉两件事：**分档阈值**（B / KB / MB / GB 的边界值）与**进制**（十进制 1000 —— 与 Android
 * `Formatter.formatFileSize` 及 `OptionalDicts.sizeMb` 同口径，用户拿系统「存储」页对账时量级一致）。
 * 词库页的 `formatSize` 已改为复用本实现，这里的边界用例就是那条保证。
 */
class ByteSizeTest {

    @Test
    fun 分档阈值与格式() {
        assertEquals("0 B", ByteSize.text(0))
        assertEquals("999 B", ByteSize.text(999))
        assertEquals("1 KB", ByteSize.text(1000))
        assertEquals("999 KB", ByteSize.text(1_000_000L - 1))
        assertEquals("1.0 MB", ByteSize.text(1_000_000L))
        assertEquals("1.0 GB", ByteSize.text(1_000_000_000L))
    }

    @Test
    fun 十进制而非1024() {
        // 1,048,576 字节（= 1024 KiB）：十进制下是 1.0 MB，若按 1024 进制会被写成 1.0 MiB 的量级
        assertEquals("1.0 MB", ByteSize.text(1_048_576L))
        // 5,893,252 字节（2 级词库包实测值）：十进制 5.9 MB —— 与清单 `sizeMb = 5.89` 对齐
        assertEquals("5.9 MB", ByteSize.text(5_893_252L))
        // 90,790,000,000 字节：十进制 90.8 GB —— 与系统「存储」页的「可用空间 90.79 GB」同量级
        assertEquals("90.8 GB", ByteSize.text(90_790_000_000L))
    }

    @Test
    fun 小数点保留一位() {
        assertEquals("1.5 MB", ByteSize.text(1_500_000L))
        assertEquals("2.5 GB", ByteSize.text(2_500_000_000L))
    }
}
