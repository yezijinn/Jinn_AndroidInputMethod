package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键面韵母提示的硬性规则（用户明示，不得违反）：
 *  1. 绝不显示与该键字母相同的韵母（e 键不显示 e、v 键不显示 v、a/i/u 同理）；
 *  2. ü 的两种写法（u / v）同时出现时只留 v；
 *  3. 总行数强制 ≤ 2（含红色 zh/ch/sh 那一行）。
 *
 * 另把用户逐条点名的期望钉死：自然码/小鹤/加加 v = `ui` + 红 `zh`；微软 v = 一行 `ui ue` + 红 `zh`；
 * 搜狗/微软 y = `uai` / `v`。
 */
class HintRuleTest {

    private val schemes = ShuangpinScheme.SHUANGPIN_ONLY

    @Test
    fun 任何方案任何键_都不显示与键位相同的韵母() {
        for (scheme in schemes) {
            val table = scheme.table ?: continue
            for (c in 'a'..'z') {
                val hint = table.finalHint(c)
                val tokens = hint.split(' ', '\n').filter { it.isNotEmpty() }
                assertFalse(
                    "${scheme.displayName} 的 $c 键不应显示韵母「$c」（实际提示=$hint）",
                    tokens.contains(c.toString()),
                )
            }
        }
    }

    @Test
    fun 任何方案任何键_提示总行数不超过两行() {
        for (scheme in schemes) {
            val table = scheme.table ?: continue
            for (c in 'a'..'z') {
                val finals = table.finalHint(c).split('\n').filter { it.isNotBlank() }.size
                val redLines = if ((table.initialOf(c)?.length ?: 0) > 1) 1 else 0
                assertTrue(
                    "${scheme.displayName} 的 $c 键提示共 ${finals + redLines} 行（禁止 >2）",
                    finals + redLines <= 2,
                )
            }
        }
    }

    @Test
    fun 用户点名项逐个核对() {
        val zr = ShuangpinScheme.ZIRANMA.table!!
        val flypy = ShuangpinScheme.FLYPY.table!!
        val sogou = ShuangpinScheme.SOGOU.table!!
        val mspy = ShuangpinScheme.MSPY.table!!
        val jiajia = ShuangpinScheme.JIAJIA.table!!

        // v 键：只留 ui（v 被规则 1 去掉、u 被规则 2 去掉），红字是 zh
        assertEquals("ui", zr.finalHint('v'))
        assertEquals("ui", flypy.finalHint('v'))
        assertEquals("ui", jiajia.finalHint('v'))
        assertEquals("zh", zr.initialOf('v'))
        assertEquals("zh", flypy.initialOf('v'))
        assertEquals("zh", jiajia.initialOf('v'))

        // 微软 v：两个韵母挤一行（红字 zh 占第 2 行），顺序按韵母表（ui 在 ue 之前）
        assertEquals("ui ue", mspy.finalHint('v'))
        assertEquals("zh", mspy.initialOf('v'))

        // 搜狗 / 微软 y：uai 与 v（ü），无红字 → 各占一行
        assertEquals(listOf("uai", "v"), sogou.finalHint('y').split('\n'))
        assertEquals(listOf("uai", "v"), mspy.finalHint('y').split('\n'))

        // e / a：自身字母一律不显示
        for (table in listOf(zr, flypy, sogou, mspy, jiajia)) {
            assertEquals("", table.finalHint('e'))
            assertEquals("", table.finalHint('a'))
        }
        // o 键保留 uo（o 自身去掉）
        assertEquals("uo", zr.finalHint('o'))
    }
}
