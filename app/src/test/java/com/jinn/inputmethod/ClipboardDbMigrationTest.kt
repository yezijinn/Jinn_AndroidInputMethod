package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板库「重建表」迁移分支的列搬运（BUG.md L-1421）。
 *
 * 缺陷形态：v3 / v5 两个分支各有一份 v5 时代的 9 列 `INSERT … SELECT`，v8 加 `image_*` 后没同步 ⇒
 * 「装回 DB_VERSION≤4 的旧包（`onDowngrade` 只回写版本号）再升回来」时，图片行的尺寸 / 体积 / MIME
 * 被建表默认值覆盖：历史页显示「尺寸读不出 · 0 KB」，`image_bytes=0` 还让图片字节预算退化成只按张数裁。
 *
 * 为什么这样验证：复现需要一台装着旧包的设备（**手头没有，真机侧未验**），而迁移逻辑跑在
 * `SQLiteDatabase` 上、项目又不引 Robolectric ⇒ 纯 JVM 开不了库。于是拆两层钉：
 *  - **列选择**抽成纯函数 [ClipboardDb.preservedColumns]，直接断言交界结果（下面两条）；
 *  - **接线**用源码钉（第三条）：两个分支都必须走共用入口，文件里不许再有写死的列清单。
 *
 * 两侧都是必要而不充分的：纯函数绿不代表 `PRAGMA` 那一侧接对，源码钉绿不代表 SQL 能跑通。
 * 残余风险与收口方式见该台账条目的「收口」行。
 */
class ClipboardDbMigrationTest {

    /**
     * 当前 schema 的列（顺序同 `createTableSql` / `selectCols`）。
     *
     * 刻意**照抄一份**而不是从生产代码读：判据要独立于实现，否则「白名单漏一列」两边一起漏、恒绿。
     */
    private val schemaColumns = listOf(
        "id", "encrypted_content", "content_type", "created_at", "source_package",
        "source_app_name", "content_hash", "category", "is_favorite",
        "image_bytes", "image_w", "image_h", "image_mime",
    )

    @Test
    fun `重建表必须搬满当前 schema 的全部列`() {
        assertEquals(
            "重建表少搬一列，那一列就被建表 DEFAULT 覆盖 —— image_* 少搬即 L-1421",
            schemaColumns,
            ClipboardDb.preservedColumns(schemaColumns),
        )
    }

    @Test
    fun `旧表没有的列不得出现在搬运清单里`() {
        val v4 = schemaColumns - listOf("image_bytes", "image_w", "image_h", "image_mime")
        assertEquals(
            "真 v4 库的旧表没有 image_*：照搬会 no such column ⇒ 升级抛异常、整库打不开（比丢元数据更糟）",
            v4,
            ClipboardDb.preservedColumns(v4),
        )
        // 反过来的一头：v4 时代已删的列不许被「顺手补回来」（那是把隐私字段搬回库里）
        val ancient = v4 + listOf("is_private", "is_sensitive", "expire_at")
        val out = ClipboardDb.preservedColumns(ancient)
        assertEquals("已删列只该被过滤掉，其余列一个不少", v4, out)
        assertTrue(
            "is_private / is_sensitive / expire_at 不得被搬回来",
            out.none { it in setOf("is_private", "is_sensitive", "expire_at") },
        )
    }

    @Test
    fun `重建分支必须走共用列清单`() {
        val src = TestSources.codeSource("ClipboardDb.kt")
        // 写死的 9 列就是 L-1421 的成因：加列时没人会回来把两个分支各数一遍
        assertFalse(
            "重建分支又在写死列清单 —— 少一列就静默丢数据（L-1421）",
            "INSERT INTO \$TABLE_ITEMS (id, encrypted_content" in src,
        )
        // v3 / v5 两个分支都必须调共用入口。判据写 >=2 而不是 ==2：将来再加重建分支不必回来改这条
        val calls = Regex("""rebuildKeepingData\(db\)""").findAll(src).count()
        assertTrue("v3 / v5 两个重建分支都要走 rebuildKeepingData，现只有 $calls 处", calls >= 2)
        // 白名单必须逐列齐（只看整文件「有 image_bytes」会被本文件其它上百处满足 —— 假绿）
        val whitelist = TestSources.window(
            src, "REBUILD_COLUMNS = listOf(", "internal fun preservedColumns(",
        )
        for (col in schemaColumns) {
            assertTrue("REBUILD_COLUMNS 缺列 $col（L-1421 的成因就在这份清单）", "\"$col\"" in whitelist)
        }
        // PRAGMA 的返回值只能当集合用：列名拼进 SQL 的必须来自白名单常量（无注入面、不受 PRAGMA 顺序影响）
        assertTrue(
            "搬运清单必须由 preservedColumns 从 PRAGMA 结果里筛出来",
            "preservedColumns(tableColumns(db, old))" in src,
        )
    }
}
