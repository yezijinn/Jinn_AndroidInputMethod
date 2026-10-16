package com.jinn.inputmethod

/**
 * 底栏「逗号 / 句号」键的可自定义输出（中文态与英文态各一套）。
 *
 * 硬约束：输出**只能是一个字符**（用户 2026-10-16 指定）—— 底部固定键位排布紧凑，
 * 多字符会溢出键面、也失去「标点键」的语义。这条约束只在这一个地方实现（[sanitize] /
 * [sanitizeField]），[Prefs] 的读写两侧与设置页输入框都走它，不各自写一份裁剪逻辑。
 *
 * 默认值与原硬编码逐字节相同（中文全角 `，` / `。`、英文半角 `,` / `.`）：
 * 用户没设过时行为与改造前完全一致。
 *
 * 全部为纯函数，可直接 JVM 单测（见 `PunctOutputTest`）。
 */
object PunctOutput {

    /** 中文态默认逗号（全角），与原硬编码一致 */
    const val DEFAULT_COMMA_CN = "，"

    /** 英文态默认逗号（半角），与原硬编码一致 */
    const val DEFAULT_COMMA_EN = ","

    /** 中文态默认句号（全角），与原硬编码一致 */
    const val DEFAULT_PERIOD_CN = "。"

    /** 英文态默认句号（半角），与原硬编码一致 */
    const val DEFAULT_PERIOD_EN = "."

    /**
     * 程序化写入 / 备份导入的归一：空串与纯空白回落到 [fallback]，
     * 其余取**第一个码点**（按 Unicode 码点切，不按 UTF-16 单元 —— emoji / 代理对不会被切半）。
     *
     * 取第一个码点是为了**确定性**：同一份脏值（如外部备份里的 `"，,"`）反复导入结果恒定。
     */
    fun sanitize(raw: String?, fallback: String): String {
        val t = raw.orEmpty()
        if (t.isBlank()) return fallback
        return String(Character.toChars(t.codePointAt(0)))
    }

    /**
     * 设置页输入框的归一：空 / 空白回落 [fallback]，多字符时保留**最后一个**码点。
     *
     * 与 [sanitize] 刻意分开：输入框里「退格清空」会先回落成默认字符，用户接着敲的那个字
     * 就拼在它后面（得到两字符）—— 此时取末尾才是用户刚敲下的那个；取首位会把他敲的字吞掉。
     */
    fun sanitizeField(raw: String?, fallback: String): String {
        val t = raw.orEmpty()
        if (t.isBlank()) return fallback
        return String(Character.toChars(t.codePointBefore(t.length)))
    }
}
