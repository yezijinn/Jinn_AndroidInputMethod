package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选栏「拼音显示为声韵」（[Shuangpin.displayQuanpin]，2026-09-25 新增的可选显示方式）的硬性规则：
 *  1. 合法输入与查询用的 [Shuangpin.toQuanpin] 逐字节一致 —— 显示不许与引擎理解漂移；
 *  2. 残码 / 非法组合同样**不丢键**：按下的每个键都必须在显示串里留下痕迹，
 *     保证「输入变长 ⇒ 显示一定变化」（2026-09-18「按键没反应」报告的根因就是显示串被吞）；
 *  3. 全拼方案原样返回；空串返回空串；大写入参按小写处理。
 */
class PinyinDisplayTest {

    private val scheme = ShuangpinScheme.ZIRANMA

    private fun d(input: String, s: ShuangpinScheme = scheme): String = Shuangpin.displayQuanpin(input, s)

    @Test
    fun 用户示例_自然码vsgo显示zhongguo() {
        assertEquals("zhongguo", d("vsgo"))
        // 相同输入下与查询串一致
        assertEquals(Shuangpin.toQuanpin("vsgo", scheme), d("vsgo"))
    }

    @Test
    fun 合法输入_七套方案全码表对拍() {
        // 遍历各方案码表：每条「码 → 音节」在 display 与 toQuanpin 下必须一致
        for (s in ShuangpinScheme.SHUANGPIN_ONLY) {
            val table = s.table ?: continue
            for (code in table.codes.keys) {
                assertEquals(
                    "${s.displayName} 的 $code 两种转换不一致",
                    Shuangpin.toQuanpin(code, s),
                    Shuangpin.displayQuanpin(code, s),
                )
            }
        }
    }

    @Test
    fun 合法输入_两码拼接后仍与查询串一致() {
        for (s in ShuangpinScheme.SHUANGPIN_ONLY) {
            val table = s.table ?: continue
            // 取前 12 个码两两拼接（12×12 = 144/方案），验证多音节输入
            for ((a, b) in table.codes.keys.take(12).flatMap { a -> table.codes.keys.take(12).map { b -> a to b } }) {
                val input = a + b
                assertEquals(
                    "${s.displayName} 的 $input 两种转换不一致",
                    Shuangpin.toQuanpin(input, s),
                    Shuangpin.displayQuanpin(input, s),
                )
            }
        }
    }

    @Test
    fun 残码逐键展开_绝不丢键() {
        assertEquals("zhong", d("vs"))
        assertEquals("zhongg", d("vsg"))
        assertEquals("zh", d("v"))
        // 坏码之后的按键不许被吞：两边都只丢这一键、游标继续（BUG.md L-1121 起查询串同款，
        // 此前查询串在坏码处截断、丢掉后面所有键）。字母输入下两个函数必须逐字节一致。
        for (s in ShuangpinScheme.SHUANGPIN_ONLY) {
            val table = s.table ?: continue
            val missing = missingPair(table)
            for (illegal in listOf(missing, missing + "x", "x$missing", missing + "vs", "$missing$missing")) {
                val display = Shuangpin.displayQuanpin(illegal, s)
                val query = Shuangpin.toQuanpin(illegal, s)
                assertEquals(
                    "${s.displayName} 的 $illegal 两种转换不一致：display=$display query=$query",
                    query,
                    display,
                )
            }
        }
    }

    /**
     * 查询串必须随按键变化（BUG.md L-1121）。
     *
     * 这是一条**用户可见**的回归：坏码处原先 `break` 并把游标留在原地，之后每按一键都在同一处再断，
     * 查询串从此不动 —— 候选栏停在坏码前那几个音节的候选上，用户以为键盘坏了。
     * 判据与显示侧的 `逐键推进_显示串一定变化` 同款，只换成查询用的 [Shuangpin.toQuanpin]。
     */
    @Test
    fun 逐键推进_查询串也必须变化() {
        for (s in ShuangpinScheme.SHUANGPIN_ONLY) {
            val table = s.table ?: continue
            val missing = missingPair(table)
            for (seq in listOf(missing, missing + "x", "x$missing", "$missing$missing", "$missing" + "vsgo", "vs$missing")) {
                var prev = ""
                for (n in 1..seq.length) {
                    val cur = Shuangpin.toQuanpin(seq.take(n), s)
                    assertNotEquals(
                        "${s.displayName} 的序列 $seq 第 $n 键后查询串未变化（prev=$prev）",
                        prev,
                        cur,
                    )
                    prev = cur
                }
            }
        }
    }

    /** 该方案里第一个成不了音节的字母组合（坏码样本，各方案不同） */
    private fun missingPair(table: ShuangpinTable): String =
        ('a'..'z').flatMap { a -> ('a'..'z').map { b -> "$a$b" } }.first { it !in table.codes }

    @Test
    fun 逐键推进_显示串一定变化() {
        for (seq in listOf("vsgo", "nimf", "vsgx", "jpdv")) {
            var prev = ""
            for (n in 1..seq.length) {
                val cur = d(seq.take(n))
                assertNotEquals("序列 $seq 第 $n 键后显示串未变化（prev=$prev）", prev, cur)
                prev = cur
            }
        }
    }

    @Test
    fun 非字母键_混在音节里正常展开_单独按下也保留() {
        // 搜狗方案 ing 在分号键：b; = bing
        assertEquals("bing", d("b;", ShuangpinScheme.SOGOU))
        // 单独按分号（非法位置）：显示串里保留该键，否则用户会觉得没反应
        assertEquals(";", d(";", ShuangpinScheme.SOGOU))
    }

    @Test
    fun 全拼方案与空串_原样返回() {
        assertEquals("vsgo", d("vsgo", ShuangpinScheme.QUANPIN))
        assertEquals("", d(""))
        // 大写入参按小写处理（键盘输入本是小写，兜住手工构造的调用）
        assertEquals("zhongguo", d("VSGO"))
    }
}
