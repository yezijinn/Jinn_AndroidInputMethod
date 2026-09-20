package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 符号表数据护栏。
 *
 * 符号层是纯数据表（[SYMBOL_GROUPS]），此前没有任何测试引用它 —— 这类「人工录入」的表
 * 恰恰最容易出静默错误：历史上「标点」组第 2 页的 g 键被误录成 `"〈~"`，上屏会多出一个孤立波浪号。
 *
 * 注意（踩过的坑）：**不要给取值加「长度 1~2」这类看似合理的规则** —— 本表里
 * 「编程」组有 149 条合法代码片段（`#include`、`int main`）、「数学」组有 `∫∫∫`，
 * 长度规则会把正确数据判成错误。护栏只锁「由设计保证、且与录入事故直接相关」的性质。
 */
class SymbolLayoutTest {

    @Test
    fun 取值不得包含换行或制表符() {
        val bad = mutableListOf<String>()
        for (group in SYMBOL_GROUPS) {
            for ((pageIndex, page) in group.pages.withIndex()) {
                for ((key, value) in page) {
                    if (value.any { it == '\n' || it == '\r' || it == '\t' }) {
                        bad.add("${group.label}组第${pageIndex + 1}页 $key")
                    }
                }
            }
        }
        assertEquals("符号取值含换行/制表符（录入事故或转义写错）: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 每个分组至少有一页且每页非空() {
        for (group in SYMBOL_GROUPS) {
            assertTrue("分组「${group.label}」没有页", group.pages.isNotEmpty())
            for ((i, page) in group.pages.withIndex()) {
                assertTrue("分组「${group.label}」第${i + 1}页为空", page.isNotEmpty())
            }
        }
    }

    @Test
    fun 分组标签唯一() {
        val labels = SYMBOL_GROUPS.map { it.label }
        assertEquals("分组标签有重复: $labels", labels.size, labels.toSet().size)
    }

    @Test
    fun 标点组第二页g键是单个左尖括号() {
        // 回归：该键曾误录为 "〈~"（多一个波浪号）
        val punct = SYMBOL_GROUPS.first { it.label == "标点" }
        assertTrue("标点组至少应有 2 页", punct.pages.size >= 2)
        assertEquals("〈", punct.pages[1]['g'])
    }

    @Test
    fun 特殊组天气页不得混入汉字() {
        // 回归：「特殊」组第 2 页（天气 / 天文 / 行星符号）的 k、l 键曾被误录成汉字
        // 「由」「白」—— 与本页其余条目（☀ ☁ ❄ ☿ ♄ ♁ ☛ …）完全不是一类。
        // 字符集错位不会引发任何编译或运行错误，只有真机上屏才会暴露，故用护栏钉住。
        val special = SYMBOL_GROUPS.first { it.label == "特殊" }
        assertTrue("特殊组至少应有 2 页", special.pages.size >= 2)
        val page = special.pages[1]
        val cjk = page.filterValues { v -> v.any { it.code in 0x4E00..0x9FFF } }
        assertEquals("特殊组第 2 页混入了汉字（应为本页同类的符号）: $cjk", emptyMap<Char, String>(), cjk)
        assertEquals("♃", page['k'])
        assertEquals("♅", page['l'])
    }
}
