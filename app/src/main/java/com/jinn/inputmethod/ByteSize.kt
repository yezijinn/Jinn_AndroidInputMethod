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
 * 口径：`>= 1GB → %.1f GB`；`>= 1MB → %.1f MB`；`>= 1KB → N KB`（整数）；否则 `N B`。
 */
internal object ByteSize {

    fun text(bytes: Long): String = when {
        bytes >= 1000L * 1000 * 1000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1000L * 1000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        bytes >= 1000L -> "${bytes / 1000} KB"
        else -> "$bytes B"
    }
}
