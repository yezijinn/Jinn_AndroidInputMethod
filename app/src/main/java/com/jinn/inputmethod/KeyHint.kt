package com.jinn.inputmethod

/**
 * 键面提示的显示规则（纯函数，渲染与单测同一来源）。
 *
 * 「键面提示」= 双拼字母键下方的韵母 / 红色声母小字（见 [PinyinKey.subLabel]）。
 * 用户 2026-09-25 要求把它做成可选开关（[Prefs.showKeyHint]）：
 *  - 开（默认）：保持历史观感 —— 字母小字顶置、下方一行提示；
 *  - 关：不画提示，字母按铺满居中绘制（与全拼键面一致），不留空出的提示区。
 *
 * 提示只出现在**中文双拼**的字母层：全拼没有提示，英文态与大写锁定一律不显示。
 */
internal object KeyHint {

    /**
     * 是否绘制键面提示。
     *
     * @param letterLayer 当前是字母层（符号层 / 数字层没有提示）
     * @param english 英文态（含「双拼切英文」的小写英文）
     * @param caps 大写锁定（优先级最高，此时键面是全拼大写英文）
     * @param shuangpin 当前为双拼方案
     * @param enabled 设置项 [Prefs.showKeyHint]
     */
    fun visible(
        letterLayer: Boolean,
        english: Boolean,
        caps: Boolean,
        shuangpin: Boolean,
        enabled: Boolean,
    ): Boolean = letterLayer && shuangpin && enabled && !english && !caps

    /**
     * 字母是否按「铺满居中」样式绘制（false = 小字顶置，下方给提示留位）。
     *
     * 全拼、大写锁定、以及双拼关掉提示时铺满；小写英文保持小字顶置（历史观感，与开关无关）。
     */
    fun fillLetter(english: Boolean, caps: Boolean, shuangpin: Boolean, enabled: Boolean): Boolean = when {
        caps -> true
        english -> false
        else -> !shuangpin || !enabled
    }
}
