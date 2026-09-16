package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板筛选条件解析测试（纯 JVM，无需 Android / SQLite）。
 *
 * 覆盖本项目最容易踩的坑：收藏是**独立的标签列**（is_favorite），不是 category 列取值。
 * 若把 "FAVORITE" 当 category 传给 SQL，条件会变成 `WHERE category = 'FAVORITE'`（恒不成立），
 * 列表永远是空的。翻译规则收敛在 [ClipboardFilter.of] 后，用测试把它钉死。
 *
 * 分类栏共 4 个 Tab：全部 / 网址 / 数字 / 收藏。
 */
class ClipboardFilterTest {

    @Test
    fun allCategory_passesThroughNull() {
        val f = ClipboardFilter.of(null)
        assertNull(f.category)
        assertFalse(f.favoritesOnly)
    }

    @Test
    fun urlCategory_isRealCategoryColumn() {
        val f = ClipboardFilter.of(ClipboardClassifier.CATEGORY_URL)
        assertEquals("URL", f.category)
        assertFalse(f.favoritesOnly)
    }

    @Test
    fun numberCategory_isRealCategoryColumn() {
        val f = ClipboardFilter.of(ClipboardClassifier.CATEGORY_NUMBER)
        assertEquals("NUMBER", f.category)
        assertFalse(f.favoritesOnly)
    }

    @Test
    fun favorite_isPseudoCategory_notCategoryColumn() {
        val f = ClipboardFilter.of(ClipboardFilter.PSEUDO_FAVORITE)
        // 关键：category 必须为 null，否则 SQL 一行都查不到
        assertNull(f.category)
        assertTrue(f.favoritesOnly)
    }

    @Test
    fun unknownValue_isPassedThroughAsCategory() {
        // 未知取值原样透传，不做静默丢弃（与历史行为一致）
        val f = ClipboardFilter.of("SOMETHING")
        assertEquals("SOMETHING", f.category)
        assertFalse(f.favoritesOnly)
    }
}
