package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「收藏」分组纯函数护栏：预置 D I Y、null→预置、"[]"→空组不回退、去重、满 26 翻页、
 * parse 归一（去重 / 重排 / 空页过滤）、删除补位收页。
 */
class FavoriteSymbolsTest {

    @Test
    fun 出厂预置与空态语义() {
        assertEquals(listOf(FavoriteSymbols.DEFAULT_ITEMS), FavoriteSymbols.parse(null))
        assertEquals(emptyList<List<String>>(), FavoriteSymbols.parse(""))
        assertEquals(emptyList<List<String>>(), FavoriteSymbols.parse("[]"))
        assertEquals(listOf(FavoriteSymbols.DEFAULT_ITEMS), FavoriteSymbols.parse("坏的{{{"))
    }

    @Test
    fun 序列化往返_内容顺序稳定_页结构归一() {
        // 非规范结构（非末页不满 26）：归一会合并页，符号集合与顺序不变
        val loose = listOf(listOf("，", "。", "℃"), listOf("★"))
        assertEquals(listOf(loose.flatten()), FavoriteSymbols.parse(FavoriteSymbols.serialize(loose)))
        // 规范结构（26 + 2）：往返恒等
        val normal = listOf(List(26) { "a$it" }, listOf("x", "y"))
        assertEquals(normal, FavoriteSymbols.parse(FavoriteSymbols.serialize(normal)))
    }

    @Test
    fun 归一_超页重排不丢符号_重复与空页被清理() {
        // 外部脏数据：一页 27 项 → 重排为 26 + 1，符号一个不少
        val over = List(27) { "s$it" }
        assertEquals(
            listOf(over.take(26), over.drop(26)),
            FavoriteSymbols.parse(FavoriteSymbols.serialize(listOf(over))),
        )
        // 全局去重保序 + 空页过滤
        assertEquals(
            listOf(listOf("a", "b", "c")),
            FavoriteSymbols.parse("""[["a","a","b"],[],["b","c"]]"""),
        )
    }

    @Test
    fun 满页26项恰好铺满26键位() {
        // PER_PAGE 与键盘键位数的耦合护栏：归一后每页 ≤26，favoriteGroup 不得静默丢符号
        val page = List(FavoriteSymbols.PER_PAGE) { "s$it" }
        val group = favoriteGroup(listOf(page))
        assertEquals(FavoriteSymbols.PER_PAGE, group.pages[0].size)
        assertEquals(page.toSet(), group.pages[0].values.toSet())
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

    @Test
    fun 删除下标按实际累计长度计算_不依赖每页满26() {
        // 编辑页用「前面各页实际长度之和 + 页内偏移」算扁平下标：
        // 即使数据被外部破坏（非末页不满 26），也应删到正确的符号。
        val pages = listOf(listOf("a", "b", "c"), listOf("d", "e", "f", "g", "h"))
        val flat = pages.take(1).sumOf { it.size } + 3 // 第 2 页第 4 个 = "g"
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "h"),
            FavoriteSymbols.removeAt(pages, flat).flatten())
        // 对照：旧写法 pageIndex*PER_PAGE+i = 1*26+3 = 29 在变长数据下越界 → 原样返回（删不中）
        assertEquals(pages, FavoriteSymbols.removeAt(pages, 1 * FavoriteSymbols.PER_PAGE + 3))
    }
}
