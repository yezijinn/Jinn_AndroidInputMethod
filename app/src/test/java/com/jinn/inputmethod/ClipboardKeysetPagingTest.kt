package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 剪贴板分页的**键集游标**守卫（`BUG.md` L-92）。
 *
 * 缺陷：分页游标原本是 SQL `OFFSET`。面板打开期间外部改一次剪贴板就是「头部插一条 + 尾部裁一条」
 * （库到上限时 `insertItem` → `trimTo` 成对发生，**总条数刚好不变**）⇒ 行的物理位置整体位移，
 * 下一页会重复一行或跳过一行；而当时唯一的兜底是「重算 count 与基准比对」，数量不变时它拦不住。
 *
 * 修法：游标改成 `(created_at, id)` 键集（`ClipboardDb.recentPageAfter`），位置位移免疫。
 * 这里三件事一起钉住：
 *  1. 游标比较器（生产代码 [ClipboardCursor.isOlderThan]）与 SQL 同口径 —— 等值组（同毫秒）按 id 兜序；
 *  2. 用**生产的**填页器 `ClipboardDb.fillFirstPage` + 键集取页器模拟「等量增删」：不重复、不跳过；
 *  3. 源码对拍：三处调用方（面板 / 导出 / 搜索）必须走键集 API，且**不得**再用 OFFSET 分页器。
 */
class ClipboardKeysetPagingTest {

    // ── 假库：一串 (游标, 内容) 行，用生产比较器做键集取页 ──────────────

    private class FakeDb(rows: List<Pair<ClipboardCursor, String>>) {
        /** 新→旧（与 SQL 的 `ORDER BY created_at DESC, id DESC` 同序） */
        val rows: MutableList<Pair<ClipboardCursor, String>> = rows.sortedWith(
            compareByDescending<Pair<ClipboardCursor, String>> { it.first.createdAt }
                .thenByDescending { it.first.id },
        ).toMutableList()

        /** 记录每行被「扫描」了几次（重复扫描 = 取页器把同一行喂了两遍） */
        val scanCount = HashMap<Long, Int>()

        /**
         * 键集取页器：返回从 [cursor] 之后（更旧）的 [limit] 行。
         * [unreadable] 里的 id 模拟「解密失败」：不进 items（对用户不可见），但仍占扫描位与游标。
         */
        fun page(cursor: ClipboardCursor?, limit: Int, unreadable: Set<Long> = emptySet()): ClipboardDb.KeyedPage {
            val older = rows.filter { row -> cursor == null || row.first.isOlderThan(cursor) }
            val chunk = older.take(limit)
            var last: ClipboardCursor? = null
            val items = ArrayList<ClipboardDb.Item>(chunk.size)
            for ((key, text) in chunk) {
                last = key
                scanCount[key.id] = (scanCount[key.id] ?: 0) + 1
                if (key.id in unreadable) continue
                items.add(fakeItem(key, text))
            }
            return ClipboardDb.KeyedPage(items, chunk.size, if (chunk.isEmpty()) cursor else last)
        }

        /** 一次「入库 + 裁剪」：头插一条更新的、尾删一条最旧的（总条数不变）。返回被删掉的内容。 */
        fun churn(newId: Long, newText: String): String {
            val newest = rows.first().first
            rows.add(0, ClipboardCursor(newest.createdAt + 1, newId) to newText)
            return rows.removeAt(rows.size - 1).second
        }
    }

    private fun rowsFrom(start: Long, count: Int): List<Pair<ClipboardCursor, String>> =
        (0 until count).map { i -> ClipboardCursor(start - i, 1000L - i) to "行$i" }

    // ── 1. 比较器：与 SQL 的 `(created_at < ? OR (created_at = ? AND id < ?))` 同口径 ──

    @Test
    fun 游标比较器_先比时间再比id() {
        assertTrue(ClipboardCursor(100, 9).isOlderThan(ClipboardCursor(101, 1)))
        assertFalse(ClipboardCursor(101, 9).isOlderThan(ClipboardCursor(100, 1)))
        // 等值组（同毫秒）：由 id 兜序 —— 少了这条，跨页边界就会重复 / 漏行（与 L-97 同因）
        assertTrue(ClipboardCursor(100, 8).isOlderThan(ClipboardCursor(100, 9)))
        assertFalse(ClipboardCursor(100, 9).isOlderThan(ClipboardCursor(100, 8)))
        assertFalse(ClipboardCursor(100, 9).isOlderThan(ClipboardCursor(100, 9)))
    }

    // ── 2. 等量增删（+1 / -1）下翻页不重复、不跳过 ──────────────────

    @Test
    fun 等量增删后翻页不重复不跳过() {
        val fake = FakeDb(rowsFrom(5_000, 120))
        val seen = ArrayList<String>()
        val expected = fake.rows.map { it.second }.toMutableList()   // 期间一直存在的行
        val inserted = ArrayList<String>()

        var cursor: ClipboardCursor? = null
        var guard = 0
        while (guard++ < 100) {
            val page = fake.page(cursor, limit = 10)
            if (page.scanned == 0) break
            seen.addAll(page.items.map { it.content })
            cursor = page.last
            // 每翻一页就来一次「入库 + 裁剪」：总条数不变，行位置整体位移 1
            val text = "新插${guard}"
            inserted.add(text)
            // 被裁掉的那条永远看不到了（它已不在库里）⇒ 从期望集里摘掉；
            // 旧实现（OFFSET 位移）在这里的典型症状是**重复**上一页的行。
            expected.remove(fake.churn(newId = 20_000L + guard, newText = text))
        }

        assertEquals("重复：同一行被返回两次（OFFSET 被位移后的典型症状）", seen.size, seen.toSet().size)
        for (text in inserted) {
            assertFalse("已显示过的行不得在后续页里再出现：$text", seen.count { it == text } > 1)
        }
        // 新插入的行在游标之前（更新）⇒ 不参与本次翻页；但已翻过的部分必须一条不漏
        val missed = expected.filter { it !in seen }
        assertTrue("漏行：$missed", missed.isEmpty())
    }

    // ── 3. 整页解密失败也要能前进（游标取「扫描行」，不是「解密成功条数」） ──────

    @Test
    fun 整页解密失败时游标仍前进_不原地打转() {
        val fake = FakeDb(rowsFrom(900, 40))
        // 前 10 行（最新的一页）全部解密失败
        val unreadable = fake.rows.take(10).map { it.first.id }.toSet()
        val seen = ArrayList<String>()
        var cursor: ClipboardCursor? = null
        var pages = 0
        while (pages++ < 20) {
            val page = fake.page(cursor, limit = 10, unreadable = unreadable)
            if (page.scanned == 0) break
            seen.addAll(page.items.map { it.content })
            cursor = page.last
        }
        assertEquals("坏页之后的 30 行必须都读到", 30, seen.size)
        assertEquals("不得重复扫描（原地打转）", 40, fake.scanCount.size)
        assertTrue("每行只应被扫描一次：${fake.scanCount.values.filter { it > 1 }}", fake.scanCount.values.all { it == 1 })
    }

    // ── 4. 生产的填页器 + 键集取页器：进度（已扫描行数）与游标口径一致 ──────────

    @Test
    fun 填页器的进度与键集游标口径一致() {
        val fake = FakeDb(rowsFrom(300, 50))
        var cursor: ClipboardCursor? = null
        val page = ClipboardDb.fillFirstPage(total = fake.rows.size, limit = 12) { off, lim ->
            val keyed = fake.page(cursor, lim)
            cursor = keyed.last
            ClipboardDb.Page(keyed.items, off + keyed.scanned)
        }
        assertEquals("填页器应连续填满一页", 12, page.items.size)
        assertEquals("进度 = 已扫描行数", 12, page.nextOffset)
        assertEquals("游标应停在最后一个扫描行", fake.rows[11].first, cursor)
    }

    // ── 5. 源码对拍：调用方必须走键集 API，且不得再用 OFFSET 分页器 ──────────────

    private fun codeOnly(name: String): String = TestSources.codeSource(name)

    private fun sourceOf(name: String): String = TestSources.rawSource(
        "src/main/java/com/jinn/inputmethod/$name", "app/src/main/java/com/jinn/inputmethod/$name",
        "src/test/java/com/jinn/inputmethod/$name", "app/src/test/java/com/jinn/inputmethod/$name",
    )

    @Test
    fun 剪贴板库必须只提供键集分页且排序键补全() {
        val code = codeOnly("ClipboardDb.kt")
        assertTrue("必须存在键集分页 API", "fun recentPageAfter(" in code)
        assertTrue(
            "游标判据必须带等值组的 id 兜序（与 `, id DESC` 同一件事的两端）",
            "(created_at < ? OR (created_at = ? AND id < ?))" in code,
        )
        assertTrue("排序键必须补全", "ORDER BY created_at DESC, id DESC" in code)
        assertFalse("不得再保留 OFFSET 分页器（它正是 L-92 的根因）", "recentPageWithOffset" in code)
        assertFalse("分页 SQL 里不得再出现 OFFSET", "OFFSET" in code)
    }

    @Test
    fun 三处调用方必须走键集分页() {
        for (name in listOf("ClipboardPanelView.kt", "ConfigBackupManager.kt", "SearchPanelView.kt")) {
            val code = codeOnly(name)
            assertTrue("$name 必须用键集分页", "recentPageAfter(" in code)
            assertFalse("$name 不得再用 OFFSET 分页器", "recentPageWithOffset" in code)
        }
        // 代码口径之外还要查**原始文本**（BUG.md L-118）：删了函数、只在注释里提它同样会误导读者
        // （`@param` 里的失效链接、解释句里的幽灵函数名）。守卫文件自身要排除 —— 它必须写下这个串名。
        for (name in listOf("ClipboardDb.kt", "ConfigBackupManager.kt", "ClipboardLimitsTest.kt")) {
            assertFalse(
                "$name 不得再（连注释一起）提到已删除的 recentPageWithOffset",
                "recentPageWithOffset" in sourceOf(name),
            )
        }
        val panel = codeOnly("ClipboardPanelView.kt")
        assertTrue("面板必须持有下一页游标", "private var nextCursor" in panel)
        // 三条加载路径（refresh / loadNextPage / continueScan）都必须推进游标。
        // ⚠ 只钉「nextCursor = cursor」一种写法是不够的：loadNextPage 用的是 `nextCursor = page.last`
        // —— 变体当场漏判（第一版守卫就在这里被变异抓出假绿），所以按**出现次数**钉住三条路径。
        val advances = Regex("nextCursor = ").findAll(panel).count()
        assertTrue("三条加载路径都要推进游标，实测只有 $advances 处", advances >= 3)
        assertTrue("refresh / 续扫 用取页器交回的游标", "nextCursor = cursor" in panel)
        assertTrue("下一页用键集页自带的 last", "nextCursor = page.last" in panel)
        // 键集游标必须「被接受才提交」（BUG.md L-117）：两处取页器闭包都要用 pageAccepted 收尾。
        // 只钉「存在一处」不够（上一轮正是这样漏判的，见 L-116 补例），所以按条数钉。
        val acceptedChecks = Regex("ClipboardDb\\.pageAccepted\\(").findAll(panel).count()
        assertTrue("两处取页器都要用 pageAccepted 收尾，实测 $acceptedChecks 处", acceptedChecks >= 2)

    }

    // ── 6. 被字节预算拒收的末页：游标必须停在它之前（BUG.md L-117） ──────────────

    @Test
    fun 接受判据的三种情形() {
        // 接受：fillFirstPage 把游标推进到「取回页的末尾」
        assertTrue(ClipboardDb.pageAccepted(nextOffsetAfterFill = 30, lastFetchOffset = 20, lastFetchedScanned = 10))
        // 拒收（字节预算）：游标留在取回之前
        assertFalse(ClipboardDb.pageAccepted(nextOffsetAfterFill = 20, lastFetchOffset = 20, lastFetchedScanned = 10))
        // 取页器原地没动（scanned = 0）：对应「游标不前进即退出」那一支，不算接受
        assertFalse(ClipboardDb.pageAccepted(nextOffsetAfterFill = 20, lastFetchOffset = 20, lastFetchedScanned = 0))
    }

    @Test
    fun 末页被字节预算拒收时游标必须停在被拒页之前() {
        // 6 行 × 64KB；limit=5 且前 5 行里 2 行「解密失败」⇒ 第一页只收 3 条（192KB）仍不足 limit，
        // 于是取第二页（1 行 64KB）：192+64=256KB > 预算 200KB ⇒ 第二页被整页拒收。
        val rowBytes = 64 * 1024
        val fake = FakeDb((0 until 6).map { i -> ClipboardCursor(600L - i, 60L - i) to "x".repeat(rowBytes) })
        val unreadable = setOf(fake.rows[1].first.id, fake.rows[2].first.id)

        var accepted: ClipboardCursor? = null
        var pending: ClipboardCursor? = null
        var fetchOff = 0
        var pendingScanned = 0
        val page = ClipboardDb.fillFirstPage(
            total = fake.rows.size,
            limit = 5,
            maxPlainBytes = 200L * 1024,
        ) { off, lim ->
            if (pending != null) accepted = pending            // 又被调用 = 上一页已被接受
            val keyed = fake.page(accepted, lim, unreadable)
            pending = keyed.last
            fetchOff = off
            pendingScanned = keyed.scanned
            ClipboardDb.Page(keyed.items, off + keyed.scanned)
        }

        assertEquals("第一页 3 条应被收下", 3, page.items.size)
        assertFalse(
            "第二页被预算拒收 ⇒ pageAccepted 必须为 false（若为 true，该页条目会被永久跳过）",
            ClipboardDb.pageAccepted(page.nextOffset, fetchOff, pendingScanned),
        )
        assertEquals("已接受的仍是第一页的末键", fake.rows[4].first, accepted)
        // 被拒页必须能在下一次取页时重新取到（不跳页）：从 accepted 之后开始 = 第二页那一行
        val retry = fake.page(accepted, 5, unreadable)
        assertEquals("被拒的那一页必须能被重新取到", fake.rows[5].first, retry.last)
        assertEquals("重取时内容应到齐", 1, retry.items.size)
    }


}

/** 造假条目（顶层：嵌套的 FakeDb 类取不到外层实例方法） */
private fun fakeItem(key: ClipboardCursor, text: String) = ClipboardDb.Item(
    id = key.id,
    content = text,
    contentType = "text/plain",
    createdAt = key.createdAt,
    sourcePackage = "com.example",
    sourceAppName = "示例",
    contentHash = "h${key.id}",
)
