package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板筛选条件解析测试（纯 JVM，无需 Android / SQLite）。
 *
 * 覆盖本项目最容易踩的坑：收藏与隐私是**独立的标签列**（is_favorite / is_private），
 * 不是 category 列取值。若把 "FAVORITE" 当 category 传给 SQL，
 * 条件会变成 `WHERE category = 'FAVORITE'`（写起来像这样，恒不成立），
 * 列表永远是空的。翻译规则收敛在 [ClipboardFilter.of] 后，用测试把它钉死。
 */
class ClipboardFilterTest {

    @Test
    fun allCategory_passesThroughNull() {
        val f = ClipboardFilter.of(null)
        assertNull(f.category)
        assertFalse(f.favoritesOnly)
        assertFalse(f.privateOnly)
    }

    @Test
    fun urlCategory_isRealCategoryColumn() {
        val f = ClipboardFilter.of(ClipboardClassifier.CATEGORY_URL)
        assertEquals("URL", f.category)
        assertFalse(f.favoritesOnly)
        assertFalse(f.privateOnly)
    }

    @Test
    fun numberCategory_isRealCategoryColumn() {
        val f = ClipboardFilter.of(ClipboardClassifier.CATEGORY_NUMBER)
        assertEquals("NUMBER", f.category)
        assertFalse(f.favoritesOnly)
        assertFalse(f.privateOnly)
    }

    @Test
    fun favorite_isPseudoCategory_notCategoryColumn() {
        val f = ClipboardFilter.of(ClipboardFilter.PSEUDO_FAVORITE)
        // 关键：category 必须为 null，否则 SQL 一行都查不到
        assertNull(f.category)
        assertTrue(f.favoritesOnly)
        assertFalse(f.privateOnly)
    }

    @Test
    fun private_isPseudoCategory_notCategoryColumn() {
        val f = ClipboardFilter.of(ClipboardFilter.PSEUDO_PRIVATE)
        assertNull(f.category)
        assertFalse(f.favoritesOnly)
        assertTrue(f.privateOnly)
    }

    @Test
    fun favoriteAndPrivate_areMutuallyExclusive() {
        val all = listOf(
            null,
            ClipboardClassifier.CATEGORY_URL,
            ClipboardClassifier.CATEGORY_NUMBER,
            ClipboardFilter.PSEUDO_FAVORITE,
            ClipboardFilter.PSEUDO_PRIVATE,
        )
        for (raw in all) {
            val f = ClipboardFilter.of(raw)
            assertFalse("raw=$raw 不应同时置两个标记", f.favoritesOnly && f.privateOnly)
        }
    }

    @Test
    fun unknownValue_isPassedThroughAsCategory() {
        // 未知取值原样透传，不做静默丢弃（与历史行为一致）
        val f = ClipboardFilter.of("SOMETHING")
        assertEquals("SOMETHING", f.category)
        assertFalse(f.favoritesOnly)
        assertFalse(f.privateOnly)
    }
}
