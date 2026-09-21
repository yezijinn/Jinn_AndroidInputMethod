package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「收藏」分组纯函数护栏：预置 D I Y、null→预置、"[]"→空组不回退、去重、满 26 翻页、删除补位收页。 */
class FavoriteSymbolsTest {

    @Test
    fun 出厂预置与空态语义() {
        assertEquals(listOf(FavoriteSymbols.DEFAULT_ITEMS), FavoriteSymbols.parse(null))
        assertEquals(emptyList<List<String>>(), FavoriteSymbols.parse(""))
        assertEquals(emptyList<List<String>>(), FavoriteSymbols.parse("[]"))
        assertEquals(listOf(FavoriteSymbols.DEFAULT_ITEMS), FavoriteSymbols.parse("坏的{{{"))
    }

    @Test
    fun 序列化往返稳定() {
        val pages = listOf(listOf("，", "。", "℃"), listOf("★"))
        assertEquals(pages, FavoriteSymbols.parse(FavoriteSymbols.serialize(pages)))
    }

    @Test
    fun 追加_去重与长度校验() {
        val start = FavoriteSymbols.parse(null) // D I Y
        assertEquals(start to false, FavoriteSymbols.append(start, "D"))
        assertEquals(start to false, FavoriteSymbols.append(start, ""))
        assertEquals(start to false, FavoriteSymbols.append(start, "123456789"))
        val (next, ok) = FavoriteSymbols.append(start, "★")
        assertTrue(ok)
        assertEquals(listOf(listOf("D", "I", "Y", "★")), next)
    }

    @Test
    fun 追加_末页满26自动开新页() {
        val full = listOf(List(26) { "${it + 1}" })
        val (next, ok) = FavoriteSymbols.append(full, "新")
        assertTrue(ok)
        assertEquals(2, next.size)
        assertEquals(listOf("新"), next[1])
    }

    @Test
    fun 删除_前移补位_空页收起_越界原样() {
        val pages = listOf(List(26) { "a$it" }, listOf("x", "y"))
        val after = FavoriteSymbols.removeAt(pages, 0)
        assertEquals(2, after.size)
        assertEquals(26, after[0].size)
        assertEquals(listOf("y"), after[1]) // x 前移补进第 1 页
        assertEquals(listOf(listOf("D", "Y")), FavoriteSymbols.removeAt(listOf(listOf("D", "I", "Y")), 1))
        assertEquals(emptyList<List<String>>(), FavoriteSymbols.removeAt(listOf(listOf("D")), 0))
        assertEquals(pages, FavoriteSymbols.removeAt(pages, 999))
    }

    @Test
    fun 收藏组进入默认顺序第三位且旧顺序串兼容() {
        assertEquals("收藏", SymbolOrder.DEFAULT[2])
        assertEquals(listOf("全角", "半角", "收藏"), SymbolOrder.DEFAULT.take(3))
        // 旧顺序串（不含收藏）：插回「半角」之后，其余保持用户自定义
        val got = SymbolOrder.parse("标点,特殊,全角,半角,编程")
        assertEquals(listOf("标点", "特殊", "全角", "半角", "收藏", "编程"), got.take(6))
    }

    @Test
    fun 收藏组页内按序铺键位() {
        val group = favoriteGroup(listOf(listOf("D", "I", "Y")))
        assertEquals("收藏", group.label)
        assertEquals("D", group.pages[0]['q'])
        assertEquals("I", group.pages[0]['w'])
        assertEquals("Y", group.pages[0]['e'])
        // 删光 → 单页全空键（组仍存在，可继续添加）
        assertEquals(1, favoriteGroup(emptyList()).pages.size)
        assertTrue(favoriteGroup(emptyList()).pages[0].isEmpty())
        // groupsInOrder 带入收藏组（键盘视图创建路径）
        val ordered = SymbolOrder.groupsInOrder("", favoriteGroup(listOf(listOf("D"))))
        assertEquals("收藏", ordered[2].label)
        assertTrue(ordered[2].pages[0].isNotEmpty())
    }
}
