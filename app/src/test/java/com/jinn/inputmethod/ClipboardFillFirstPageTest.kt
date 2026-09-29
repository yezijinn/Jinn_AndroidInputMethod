package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首屏填页守卫（L-09）。
 *
 * 背景：解密失败的行在读取路径上被跳过（用户看不见），若最新的一整页恰好都是坏行，
 * 「只取一页」会让列表拿到空结果 —— 面板显示空态并隐藏列表，而预取闸门要求列表非空
 * 才继续翻页（`totalItemCount > 0`）⇒ 后面还能解密的历史**永久翻不到**（功能死路）。
 *
 * [ClipboardDb.fillFirstPage] 把「连续填页」做成纯函数（取页器注入），这里用假的取页器
 * 模拟坏行分布，钉住七条契约：跨页补齐 / 止损上限 / 不裁剪末页 / 游标不前进即退出 /
 * 止损可分辨（空结果且游标未到末尾，面板据此给「继续查找」入口，L-76）/ 续扫从既有游标起（L-76）/
 * 空库与非法参数。
 */
class ClipboardFillFirstPageTest {

    private fun item(id: Long) = ClipboardDb.Item(
        id = id,
        content = "内容$id",
        contentType = "text",
        createdAt = id,
        sourcePackage = "com.test",
        sourceAppName = "测试",
        contentHash = "hash$id",
    )

    /**
     * 假取页器：`rows` 是最新→旧的原始行，false 表示该行解密失败（不进结果、但仍占游标）。
     * 返回值第二项是每次调用的 offset 记录（断言调用次数与推进位置用）。
     */
    private fun fetcher(rows: List<Boolean>): Pair<(Int, Int) -> ClipboardDb.Page, MutableList<Int>> {
        val calls = ArrayList<Int>()
        val fetch: (Int, Int) -> ClipboardDb.Page = { offset, limit ->
            calls.add(offset)
            val window = rows.drop(offset).take(limit)
            val items = window.mapIndexedNotNull { i, ok -> if (ok) item((offset + i).toLong()) else null }
            ClipboardDb.Page(items, offset + window.size)
        }
        return fetch to calls
    }

    @Test
    fun 健康库只取一页且正好填满() {
        val (fetch, calls) = fetcher(List(10) { true })
        val page = ClipboardDb.fillFirstPage(total = 10, limit = 3, fetch = fetch)
        assertEquals(listOf(0), calls)
        assertEquals(listOf(0L, 1L, 2L), page.items.map { it.id })
        assertEquals(3, page.nextOffset)
    }

    @Test
    fun 首屏整页坏行时必须继续填页() {
        // 前 3 行（正好一页）全坏：只取一页会得到空列表 ⇒ 旧行为是死路
        val (fetch, calls) = fetcher(listOf(false, false, false, true, true, true, true, true, true, true))
        val page = ClipboardDb.fillFirstPage(total = 10, limit = 3, fetch = fetch)
        assertEquals(listOf(0, 3), calls)
        assertEquals(listOf(3L, 4L, 5L), page.items.map { it.id })
        assertEquals(6, page.nextOffset)
    }

    @Test
    fun 稀疏坏行跨页补齐且不重复不跳过() {
        // 坏行夹在中间：第 1 页只捞出 1 条，第 2 页补到 3 条即停
        val rows = listOf(false, false, true, true, false, true, true, true, true)
        val (fetch, calls) = fetcher(rows)
        val page = ClipboardDb.fillFirstPage(total = rows.size, limit = 3, fetch = fetch)
        assertEquals(listOf(0, 3), calls)
        assertEquals(listOf(2L, 3L, 5L), page.items.map { it.id })
        assertEquals(6, page.nextOffset)
    }

    @Test
    fun 坏行密集时按扫描上限止损并如实回报游标() {
        // 全表坏（如密钥整体失效）：不能扫全表，达上限即返回空结果 + 已扫游标，
        // 由面板据此显示「无法读取」而不是「暂无历史」
        val total = 500
        val (fetch, calls) = fetcher(List(total) { false })
        val page = ClipboardDb.fillFirstPage(total = total, limit = 3, fetch = fetch)
        assertEquals(ClipboardDb.FIRST_PAGE_MAX_SCAN_PAGES, calls.size)
        assertTrue(page.items.isEmpty())
        assertEquals(ClipboardDb.FIRST_PAGE_MAX_SCAN_PAGES * 3, page.nextOffset)
        assertTrue("上限必须远小于全表行数，否则等于每开一次面板就白解密全库",
            ClipboardDb.FIRST_PAGE_MAX_SCAN_PAGES * 3 < total)
    }

    @Test
    fun 末页整页收下不裁剪() {
        // 补最后几页时可能超出 limit：绝不能裁剪 —— 游标已越过它们，裁掉就再没人看得到
        val (fetch, _) = fetcher(listOf(false, false, true, true, true, true))
        val page = ClipboardDb.fillFirstPage(total = 6, limit = 3, fetch = fetch)
        assertEquals(listOf(2L, 3L, 4L, 5L), page.items.map { it.id })
        assertEquals(6, page.nextOffset)
    }

    @Test
    fun 总量不足一页时扫到末尾即停() {
        val (fetch, calls) = fetcher(listOf(true, true))
        val page = ClipboardDb.fillFirstPage(total = 2, limit = 3, fetch = fetch)
        assertEquals(listOf(0), calls)
        assertEquals(listOf(0L, 1L), page.items.map { it.id })
        assertEquals(2, page.nextOffset)
    }

    @Test
    fun 游标不前进时立即退出防死循环() {
        var calls = 0
        val page = ClipboardDb.fillFirstPage(total = 10, limit = 3) { offset, _ ->
            calls++
            ClipboardDb.Page(emptyList(), offset)   // 坏实现：游标原地不动
        }
        assertEquals(1, calls)
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.nextOffset)
    }

    @Test
    fun 空库或非正页长直接返回空结果() {
        val (fetch, calls) = fetcher(List(10) { true })
        assertEquals(0, ClipboardDb.fillFirstPage(total = 0, limit = 3, fetch = fetch).items.size)
        assertEquals(0, ClipboardDb.fillFirstPage(total = 10, limit = 0, fetch = fetch).items.size)
        assertTrue("不应调用取页器", calls.isEmpty())
    }

    @Test
    fun 止损命中要能从返回值分辨出来() {
        // 面板的空态文案能否给出「继续查找」入口，全靠这组特征（BUG.md L-76）：
        // 扫到上限提前收工 = 空结果 + 游标还没到末尾；整表扫完仍为空 = 游标 == 总行数（没得找了）
        val (capped, _) = fetcher(List(ClipboardDb.FIRST_PAGE_MAX_SCAN_PAGES * 3) { false })
        val early = ClipboardDb.fillFirstPage(total = 99, limit = 3, fetch = capped)
        assertTrue(early.items.isEmpty())
        assertTrue("提前收工：游标必须小于总行数（否则面板不会给继续查找入口）", early.nextOffset < 99)

        val (whole, _) = fetcher(List(6) { false })
        val done = ClipboardDb.fillFirstPage(total = 6, limit = 3, fetch = whole)
        assertTrue(done.items.isEmpty())
        assertEquals("整表扫完：游标必须等于总行数", 6, done.nextOffset)
    }

    @Test
    fun 续扫从既有游标开始不重扫前面() {
        // 「继续查找更早的记录」= 从 nextPageOffset 接着扫；重扫前面既白解密又可能重复显示
        val (fetch, calls) = fetcher(List(100) { true })
        val page = ClipboardDb.fillFirstPage(total = 100, limit = 3, startOffset = 40, fetch = fetch)
        assertEquals(listOf(40), calls)
        assertEquals(listOf(40L, 41L, 42L), page.items.map { it.id })
        assertEquals(43, page.nextOffset)

        val (fetch2, calls2) = fetcher(List(100) { true })
        val clamped = ClipboardDb.fillFirstPage(total = 100, limit = 3, startOffset = 500, fetch = fetch2)
        assertTrue("起点越界必须钳到末尾且不调取页器", calls2.isEmpty())
        assertEquals(100, clamped.nextOffset)
    }
}
