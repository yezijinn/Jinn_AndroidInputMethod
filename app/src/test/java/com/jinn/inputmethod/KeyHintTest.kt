package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键面韵母提示开关（用户 2026-09-25：「不喜欢 26 键上显示韵母，改成可选的开关」）的硬性规则：
 *  1. 只有「中文 + 双拼 + 字母层 + 未大写锁定 + 开关开」才画提示；
 *  2. 关掉提示后字母必须铺满居中 —— 否则键面下方会空出一块没有内容的提示区；
 *  3. 全拼 / 大写锁定铺满、小写英文小字顶置，均与开关无关（历史观感）；
 *  4. 画着提示的档位绝不铺满（两者互斥，否则提示无处可画）。
 */
class KeyHintTest {

    private val bools = listOf(true, false)

    @Test
    fun 双拼字母层_开关开画提示_开关关不画() {
        assertTrue(KeyHint.visible(letterLayer = true, english = false, caps = false, shuangpin = true, enabled = true))
        assertFalse(KeyHint.visible(letterLayer = true, english = false, caps = false, shuangpin = true, enabled = false))
    }

    @Test
    fun 关掉提示后_双拼字母铺满居中() {
        assertTrue(KeyHint.fillLetter(english = false, caps = false, shuangpin = true, enabled = false))
        // 开关打开时保持小字顶置（下方留给提示）
        assertFalse(KeyHint.fillLetter(english = false, caps = false, shuangpin = true, enabled = true))
    }

    @Test
    fun 全拼与开关无关_键面始终铺满且无提示() {
        for (enabled in bools) {
            assertFalse(KeyHint.visible(letterLayer = true, english = false, caps = false, shuangpin = false, enabled = enabled))
            assertTrue(KeyHint.fillLetter(english = false, caps = false, shuangpin = false, enabled = enabled))
        }
    }

    @Test
    fun 英文态与大写锁定_不画提示且与开关无关() {
        for (enabled in bools) {
            assertFalse(KeyHint.visible(letterLayer = true, english = true, caps = false, shuangpin = true, enabled = enabled))
            assertFalse(KeyHint.visible(letterLayer = true, english = false, caps = true, shuangpin = true, enabled = enabled))
        }
        // 大写锁定铺满（全拼大写英文）；小写英文保持小字顶置
        assertTrue(KeyHint.fillLetter(english = false, caps = true, shuangpin = true, enabled = true))
        assertFalse(KeyHint.fillLetter(english = true, caps = false, shuangpin = true, enabled = true))
    }

    @Test
    fun 符号层与数字层_不画提示() {
        assertFalse(KeyHint.visible(letterLayer = false, english = false, caps = false, shuangpin = true, enabled = true))
    }

    @Test
    fun 全组合下_画提示的档位绝不铺满() {
        for (letterLayer in bools) {
            for (english in bools) {
                for (caps in bools) {
                    for (shuangpin in bools) {
                        for (enabled in bools) {
                            if (KeyHint.visible(letterLayer, english, caps, shuangpin, enabled)) {
                                assertEquals(
                                    "双拼提示可见时必须小字顶置（$letterLayer/$english/$caps/$shuangpin/$enabled）",
                                    false,
                                    KeyHint.fillLetter(english, caps, shuangpin, enabled),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
