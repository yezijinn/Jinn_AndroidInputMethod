package com.jinn.inputmethod

import java.util.Locale

/**
 * 人类可读的体积文案（全应用唯一实现）。
 *
 * 此前只有 `DictManagerActivity` 自带一份私有的 `formatSize`（KB / MB）；「存储占用」页要报
 * 设备剩余空间（GB 量级）与总账，于是把口径收成一处 —— 同一串字节在任何页面上得到同一句话，
 * 免得两个页面各写一套阈值、对同一个数给出两种说法（`PageChrome` / `PageStyle` 同一思路）。
 *
 * **进制取十进制（1000）**，与平台口径一致：
 *  - Android 自己的 `Formatter.formatFileSize`（系统「存储」页、文件选择器的「可用空间」）
 *    就是 1000 进制；用户拿手机设置里的数字来对账时，页面必须给出同一个量级
 *    （实测同一块分区：十进制 90.8 GB ↔ 二进制 84.5 GiB，写成 84.5 GB 会被当成算错）；
 *  - `OptionalDicts.sizeMb` 也是十进制（5.89 = 5,890,000 字节），词库页因此与清单一致。
 *
 * 口径：`>= 1GB → %.1f GB`；`>= 1MB → %.1f MB`；`>= 10KB → N KB`（**四舍五入**）；
 * `>= 1KB → %.1f KB`（10KB 以下保留一位小数，见 [text]）；否则 `N B`。
 */
internal object ByteSize {

    fun text(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        // 10KB 以下保留一位小数（BUG.md L-1235）：整数档会把 1499 B 显示成「1 KB」，
        // 而存储占用页正是靠这些数字让用户判断「谁占得多」，小项之间比大小就在这个量级
        bytes >= 10_000L -> "${(bytes + 500) / 1000} KB"   // 四舍五入：截断会把 999 999 B 写成「999 KB」
        bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1000.0)
        else -> "$bytes B"
    }
}
