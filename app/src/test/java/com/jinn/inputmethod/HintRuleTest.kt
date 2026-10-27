package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键面韵母提示的硬性规则（不得违反）：
 *  1. 绝不显示与该键字母相同的韵母（e 键不显示 e、v 键不显示 v、a/i/u 同理）；
 *  2. ü 的两种写法（u / v）同时出现时只留 v；
 *  3. 总行数强制 ≤ 2（含红色 zh/ch/sh 那一行）。
 *
 * 另把用户逐条点名的期望钉死：自然码/小鹤/加加 v = `ui` + 红 `zh`；微软 v = 一行 `ui ue` + 红 `zh`；
 * 搜狗/微软 y = `uai` / `v`。
 */
class HintRuleTest {

    private val schemes = ShuangpinScheme.SHUANGPIN_ONLY

    /** 键面提示的作用域：26 个字母键 + 分号键（搜狗 / 微软 / 紫光的 `ing` 在 `;` 上）。 */
    private val hintKeys = ('a'..'z') + ';'

    @Test
    fun 任何方案任何键_都不显示与键位相同的韵母() {
        for (scheme in schemes) {
            val table = scheme.table ?: continue
            for (c in hintKeys) {
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
            for (c in hintKeys) {
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
    fun 任何方案任何键_ü的两种写法不得同屏() {
        // 规则 2：u（jqxy 后的 ü）与 v（l/n 后的 ü）同一键同时出现时只留 v。
        // 实测两种写法同键共 6 处：自然码 / 小鹤 / 加加的 v 键（{ui, u, v}）、
        // 搜狗 / 微软的 y 键（{uai, u, v}）、紫光的 v 键（{u, v}）。
        for (scheme in schemes) {
            val table = scheme.table ?: continue
            for (c in hintKeys) {
                val toks = table.finalsOf(c)
                assertFalse(
                    "${scheme.displayName} 的 $c 键同屏显示了 ü 的两种写法：$toks",
                    "u" in toks && "v" in toks,
                )
            }
        }
    }

    @Test
    fun 任何方案任何键_韵母必须在规范顺序表内() {
        // finalOrder 的承诺是「覆盖全部会出现的韵母」；出现表外项就意味着排序只剩字符序兜底，
        // `ui` / `ue` 这类同长项的次序会变成巧合。新增方案 / 新增韵母时这条会红：
        // 把它补进 ShuangpinTable.finalOrder 的合适位置（同长项次序即用户看到的次序）。
        // 键面含分号键（搜狗 / 微软 / 紫光的 `ing`），所以不比 `'a'..'z'`。
        for (scheme in schemes) {
            val table = scheme.table ?: continue
            for (c in hintKeys) {
                for (fin in table.finalsOf(c)) {
                    assertTrue(
                        "${scheme.displayName} 的 $c 键出现了韵母「$fin」，但它不在 finalOrder 里",
                        fin in table.finalOrder,
                    )
                }
            }
        }
    }

    @Test
    fun 任何方案任何键_过滤后不得静默截断() {
        // 规则 3 只显示 2 项：过滤后若超过 2 项，多出来的韵母用户永远看不到（静默丢信息）。
        // 同时钉住「提示文本 = 过滤结果按规则 3 拼接」，防止两条路径各自演化。
        for (scheme in schemes) {
            val table = scheme.table ?: continue
            for (c in hintKeys) {
                val finals = table.finalsOf(c)
                assertTrue(
                    "${scheme.displayName} 的 $c 键过滤后仍有 ${finals.size} 项，提示只画得下 2 项：$finals",
                    finals.size <= 2,
                )
                val hasRedInitial = (table.initialOf(c)?.length ?: 0) > 1
                val expected = when {
                    finals.isEmpty() -> ""
                    finals.size == 1 -> finals[0]
                    hasRedInitial -> finals.joinToString(" ")
                    else -> finals.joinToString("\n")
                }
                assertEquals("${scheme.displayName} 的 $c 键", expected, table.finalHint(c))
            }
        }
    }

    @Test
    fun 分号键_只有用到它的方案才有韵母提示() {
        // 搜狗 / 微软 / 紫光的 ing 落在分号键上；其余四套连分号键都不显示。
        assertEquals(
            listOf(ShuangpinScheme.SOGOU, ShuangpinScheme.MSPY, ShuangpinScheme.ZIGUANG),
            schemes.filter { it.table!!.needsSemicolon },
        )
        for (scheme in schemes) {
            val table = scheme.table ?: continue
            assertEquals(
                "${scheme.displayName} 的分号键提示",
                if (table.needsSemicolon) "ing" else "",
                table.finalHint(';'),
            )
        }
    }

    @Test
    fun 同长的_ue_必须排在_ui_之后() {
        // `ue`（jqxy 后的 üe 写法：jue/que/xue/yue）与 `ui` 同长，先后全靠 finalOrder；
        // 它一度不在表里（兜底成 Int.MAX_VALUE）——那时次序仍然对，只因兜底值恰好比 ui 的序号大。
        // 所以光比对键面文本抓不到这个缺陷，必须同时钉「表里有 ue 且排在 ui 之后」。
        val order = ShuangpinScheme.ZIRANMA.table!!.finalOrder
        assertTrue("finalOrder 里没有 ue：表外项会落到兜底值，次序只剩巧合", "ue" in order)
        assertTrue(
            "finalOrder 里 ui 必须排在 ue 之前（实际 ui=${order.indexOf("ui")} ue=${order.indexOf("ue")}）",
            order.indexOf("ui") < order.indexOf("ue"),
        )
        assertEquals("ue", ShuangpinScheme.ZIRANMA.table!!.finalHint('t'))
        assertEquals("ui\nue", ShuangpinScheme.ZIGUANG.table!!.finalHint('n'))
        assertEquals("ui\nue", ShuangpinScheme.ABC.table!!.finalHint('m'))
        assertEquals("uai\nue", ShuangpinScheme.JIAJIA.table!!.finalHint('x'))
        // 微软 v 是两个韵母挤一行（红字 zh 占第 2 行），ui 在前
        assertEquals("ui ue", ShuangpinScheme.MSPY.table!!.finalHint('v'))
    }

    /**
     * 同一键上两个**同长**韵母的先后 —— 表内项之间只有 [ShuangpinTable.finalOrder] 的序号在起作用
     * （`thenBy { it }` 只兜表外项，今日 0 命中）。
     *
     * 这是「序号」唯一能改变用户可见文本的地方：`finalOrder` 是手写常量，把某个韵母插到别处
     * （或漏项）时，覆盖度 / 不截断两条守卫都不会红，键面次序却已经变了。
     * 当前全网只有这 4 种组合（19 格），出现第五种即红 —— 先定期望次序，再往表里加。
     */
    @Test
    fun 同长韵母的同屏次序必须符合直觉() {
        val expected = mapOf(
            setOf("ia", "ua") to listOf("ia", "ua"),
            setOf("iang", "uang") to listOf("iang", "uang"),
            setOf("ing", "uai") to listOf("ing", "uai"),
            setOf("ui", "ue") to listOf("ui", "ue"),
        )
        var cells = 0
        for (scheme in schemes) {
            val table = scheme.table ?: continue
            for (c in hintKeys) {
                val finals = table.finalsOf(c)
                if (finals.size != 2 || finals[0].length != finals[1].length) continue
                cells++
                val want = expected[finals.toSet()]
                assertNotNull(
                    "${scheme.displayName} 的 $c 键出现新的同长组合 ${finals.toSet()}：先在这里定次序",
                    want,
                )
                assertEquals("${scheme.displayName} 的 $c 键同长韵母次序", want, finals)
            }
        }
        assertEquals("同长组合的格数变了（数据改动请一并复核 finalOrder）", 19, cells)
    }

    @Test
    fun 指定项逐个核对() {
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
