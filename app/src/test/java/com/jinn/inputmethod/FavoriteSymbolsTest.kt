package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 旧名「编程」= 现在的「变量」：改名后它在用户顺序里的位置必须保住（不能掉到尾巴）
        val got = SymbolOrder.parse("标点,特殊,全角,半角,编程")
        assertEquals(listOf("标点", "特殊", "全角", "半角", "收藏", "变量"), got.take(6))
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

    @Test
    fun 大批收藏的序列化长度落在导入上限内() {
        // 20 页 × 26 项、每项 8 字符（顶到单项上限）是最坏形态的真实收藏：序列化后必须仍在上限内，
        // 否则用户自己的备份会因为该键被整条拒收而导不回来（上限为 4096 时约三百项即触顶）。
        val pages = (0 until 20).map { p -> (0 until 26).map { i -> "符%02d%02d０００".format(p, i) } }
        val raw = FavoriteSymbols.serialize(pages)

        assertTrue(
            "序列化长度 ${raw.length} 应落在导入上限 ${Prefs.MAX_FAVORITE_SYMBOLS_CHARS} 内",
            raw.length <= Prefs.MAX_FAVORITE_SYMBOLS_CHARS,
        )
        assertEquals(pages.flatten(), FavoriteSymbols.parse(raw).flatten())
    }

    @Test
    fun 归一_空白与超长项被过滤_与写入侧同口径() {
        // 数据可能来自被外部改写的备份或旧版本：写入侧 append 有两道闸（空、> MAX_CHARS），
        // parse 不过滤就会出现「键面空白、按下无声」的空槽键（BUG.md 第 15 批 L7）。
        assertEquals(
            listOf(listOf("a", "b")),
            FavoriteSymbols.parse("""[["a","","   ","abcdefghijkl","b"]]"""),
        )
        // 边界：恰好等于单项上限的保留；前后空白被归一
        val max = "x".repeat(FavoriteSymbols.MAX_CHARS)
        assertEquals(listOf(listOf(max, "c")), FavoriteSymbols.parse("""[["$max"," c "]]"""))
        // 全是垃圾 ⇒ 空组（与「用户删光」同语义，不回退预置）
        assertEquals(emptyList<List<String>>(), FavoriteSymbols.parse("""[["","  "]]"""))
    }

    @Test
    fun 单页损坏只丢该页_整份读不出来才回退预置() {
        // 一页不是数组：其余页原样保留、顺序不变（BUG.md L-1167）
        assertEquals(
            listOf(listOf("a", "b")),
            FavoriteSymbols.parse("""[["a"],{"x":1},["b"]]"""),
        )
        // 非空数组里一页都读不出来 = 结构整体不符 ⇒ 与「整份损坏」同等：回退出厂预置
        assertEquals(listOf(FavoriteSymbols.DEFAULT_ITEMS), FavoriteSymbols.parse("[1,2,3]"))
        // 整份不是 JSON ⇒ 回退预置（既有语义不变）
        assertEquals(listOf(FavoriteSymbols.DEFAULT_ITEMS), FavoriteSymbols.parse("坏的{{{"))
        // 用户删光仍是空组，不回退
        assertEquals(emptyList<List<String>>(), FavoriteSymbols.parse("[]"))
    }

    @Test
    fun 容量判据_与写入上限同源() {
        assertFalse("出厂预置远未达上限", FavoriteSymbols.overCapacity(FavoriteSymbols.parse(null)))
        // 每项 8 字符 × 4000 项：序列化约 44KB，必超 32K 上限（上限本身单独断言，防测例自己失效）
        val many = List(4000) { "abcdefgh" }.chunked(FavoriteSymbols.PER_PAGE)
        assertTrue(
            "序列化长度应超上限（否则测例无效）",
            FavoriteSymbols.serialize(many).length > Prefs.MAX_FAVORITE_SYMBOLS_CHARS,
        )
        assertTrue("必须判为超容量", FavoriteSymbols.overCapacity(many))
    }

    /**
     * BUG-35：长度闸必须比**归一后**的长度。
     *
     * 运行期调用方（收藏页）传的就是 `serialize(parse(...))` 的归一串，而导入侧原先比备份原文 ⇒
     * 一份「含冗余空白、原文超限、归一后不超限」的备份会被整键拒收，同一份内容在运行期却存得下。
     */
    @Test
    fun 原文超限但归一不超限的收藏必须能通过长度闸() {
        // 合法 JSON：一大段空白 + 一页一个符号；原文必超 32K，归一后是 [["D"]]
        val raw = "[\n" + " ".repeat(Prefs.MAX_FAVORITE_SYMBOLS_CHARS) + "[\"D\"]\n]"
        assertTrue(
            "原文应超上限（否则测例无效）",
            raw.length > Prefs.MAX_FAVORITE_SYMBOLS_CHARS,
        )
        val normalized = FavoriteSymbols.serialize(FavoriteSymbols.parse(raw))
        assertTrue(
            "归一后必须在闸内（比原文长度的旧口径会把它整键拒收）",
            normalized.length <= Prefs.MAX_FAVORITE_SYMBOLS_CHARS,
        )
        assertEquals(listOf(listOf("D")), FavoriteSymbols.parse(normalized))
        assertFalse("归一后不得判为超容量", FavoriteSymbols.overCapacity(FavoriteSymbols.parse(raw)))
    }

    /**
     * 清洗必须覆盖 Unicode 空白与零宽字符（BUG-36）。
     *
     * `String.trim()` 只去 ASCII ≤ 0x20 的空白：不换行空格 U+00A0、表意空格 U+3000、零宽空格 U+200B、
     * BOM U+FEFF、方向控制 U+200F 都会留下 —— 它们在 26 键键面上**看不见**却占格子，
     * 用户看到「空槽键」、按下去没反应、也找不到那个字符去删。
     */
    @Test
    fun 清洗必须覆盖Unicode空白与零宽字符() {
        assertEquals("不换行空格清空", "", FavoriteSymbols.clean("\u00A0"))
        assertEquals("表意空格清空", "", FavoriteSymbols.clean("\u3000"))
        assertEquals("零宽空格清空", "", FavoriteSymbols.clean("\u200B"))
        assertEquals("BOM 清空", "", FavoriteSymbols.clean("\uFEFF"))
        assertEquals("方向控制符清空", "", FavoriteSymbols.clean("\u200F"))
        assertEquals("普通空格照旧清", "", FavoriteSymbols.clean("   "))
        assertEquals("可见字符保留（两端与中间一并清）", "A", FavoriteSymbols.clean("\u200BA\u00A0"))
        assertEquals("多字符符号的内部空白也清", "ab", FavoriteSymbols.clean("a b"))
        // 三条路径同口径：脏字符不得留下「看不见的空槽键」
        assertTrue("parse 必须把纯不可见项丢掉", FavoriteSymbols.parse("[[\"\u200B\"]]").isEmpty())
        assertFalse("append 必须拒绝纯不可见项", FavoriteSymbols.append(emptyList(), "\u00A0\u200B").second)
        assertEquals(
            "parse 必须清掉可见项周围的不可见字符",
            listOf(listOf("A")),
            FavoriteSymbols.parse("[[\"\u200BA\u3000\"]]"),
        )
    }
}
