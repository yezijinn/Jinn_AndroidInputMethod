package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底栏标点输出的归一（`PunctOutput`）。
 *
 * 关键不变量：任何输入都归一到**恰好一个码点**（或回落到默认）——
 * 底栏键位是固定字符位，多字符会溢出键面、也让「这是什么键」失去意义；
 * 而空值必须回落默认，否则清空输入框会得到一个空键面。
 */
class PunctOutputTest {

    @Test
    fun 空与空白回落默认值() {
        assertEquals("，", PunctOutput.sanitize("", PunctOutput.DEFAULT_COMMA_CN))
        assertEquals("，", PunctOutput.sanitize("   ", PunctOutput.DEFAULT_COMMA_CN))
        assertEquals(",", PunctOutput.sanitize(null, PunctOutput.DEFAULT_COMMA_EN))
    }

    @Test
    fun 多字符只保留第一个码点() {
        assertEquals("：", PunctOutput.sanitize("：:|", "，"))
        assertEquals("。", PunctOutput.sanitize("。。。", "，"))
    }

    /**
     * 代理对（非 BMP 字符）不能被按 UTF-16 单元切半：切半得到孤立代理项，
     * 上屏就是一个乱码方块。取一个**码点**才是对的。
     */
    @Test
    fun 代理对按码点整体保留() {
        val emoji = "\uD83D\uDD25"        // 🔥
        assertEquals(emoji, PunctOutput.sanitize("$emoji$emoji", "，"))
        assertEquals(2, PunctOutput.sanitize("$emoji$emoji", "，").length)   // 两个 UTF-16 单元
    }

    /**
     * 输入框归一取**最后一个**码点：退格清空会先回落默认字符，用户接着敲的字拼在其后 ——
     * 取末尾才是他刚敲下的那个（取首位会把他敲的字吞掉）。
     */
    @Test
    fun 输入框归一取最后一个码点() {
        assertEquals("，", PunctOutput.sanitizeField("", "，"))
        assertEquals("，", PunctOutput.sanitizeField("。，", "。"))
        assertEquals("。", PunctOutput.sanitizeField("，。", "，"))
    }
}
