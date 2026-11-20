package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「剪贴板自定义」页草稿模型的护栏（2026-10-06 用户拍板：默认锁定只读 → 解锁 → 改草稿 → 保存才落盘）。
 *
 * 页面的交互不变式是最容易被后续维护改坏的一类东西：把滑块回调接回 `prefs.xxx = it`
 * 只需一行，界面表现几乎一样（值会立刻生效），但「点保存才生效」「重置可撤销」全部失效。
 * 这里用源码对拍把它钉住。
 */
class ClipboardDraftPolicyTest {

    @Test
    fun 参数改动必须留在草稿不得回退到实时落盘() {
        val src = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        // 反向钉：任何 `prefs.<参数> =` 形式的直接赋值都意味着绕过草稿（读侧是 getter，不受影响）。
        // 「缩略图布局」（每行张数 / 行高）同样走草稿，只是键空间在 Prefs（图库偏好）里 ——
        // 滑块回调只改草稿，落盘唯一出现在保存路径（下面四条断言分别钉住两侧）
        val directWrite = Regex(
            """prefs\.(enabled|maxItems|maxTotalBytesMb|favoriteMaxItems|favoriteMaxBytesMb|""" +
                """maxItemBytesKb|panelPageItems|maxSearchResults|historyPageSize|""" +
                // 2026-10-10：图片四项（记录图片 / 张数 / 体积 / 单张）同样只能留在草稿里
                """imageCaptureEnabled|imageMaxItems|imageMaxTotalMb|imageMaxItemMb)\s*="""
        )
        assertFalse("参数不得直接写盘（必须留在草稿，点保存才落盘）", directWrite.containsMatchIn(src))
        assertTrue("保存必须走原子入口 applyDraft", "prefs.applyDraft(" in src)
        assertTrue("渲染必须读草稿", "draft = draft.copy(" in src)
        // 布局两项：滑块回调只改草稿，落盘只允许在保存路径
        assertTrue(
            "布局滑块必须改草稿",
            "draft = draft.copy(galleryColumns = it)" in src &&
                "draft = draft.copy(galleryCellHeightDp = it)" in src,
        )
        assertTrue(
            "布局两项必须随保存落盘",
            "layoutPrefs.galleryColumns = target.galleryColumns" in src &&
                "layoutPrefs.galleryCellHeightDp = target.galleryCellHeightDp" in src,
        )
        assertTrue(
            "布局两项必须进改动清单",
            "TEXT_LAYOUT_COLUMNS：\${snapshot.galleryColumns}" in src &&
                "TEXT_LAYOUT_HEIGHT：\${snapshot.galleryCellHeightDp}" in src,
        )
    }

    @Test
    fun 保存必须单次提交避免半套参数() {
        val src = TestSources.codeSource("ClipboardPrefs.kt")
        assertTrue("必须有原子保存入口 applyDraft", "internal fun applyDraft(" in src)
        assertTrue("applyDraft 必须用单次 commit 落盘", "editor.commit()" in src)
        // 手写 editor 是「一次组装、一次提交」的形态特征：`sp.edit { }`（Kotlin 扩展）拿不到 commit 结果，
        // 用它就等于退回逐项异步 apply（中途被杀留半套参数）
        assertTrue("applyDraft 必须手写 editor 才能拿到 commit 结果", "val editor = sp.edit()" in src)
    }

    @Test
    fun 草稿必须跨重建存活且离开要拦截() {
        val src = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        // 旋转、分屏与 ThemeManager 定时换肤到点都会重建本页：草稿不随实例状态存取就会被静默清空
        assertTrue(
            "草稿必须写进 onSaveInstanceState",
            "outState.putInt(STATE_MAX_ITEMS" in src && "outState.putBoolean(STATE_UNLOCKED" in src,
        )
        assertTrue("必须能从实例状态恢复草稿", "private fun restoreDraft(b: Bundle?)" in src)
        // 草稿退出即丢：不拦的话用户会以为改动已生效
        assertTrue("关闭按钮与返回键都要经过离开拦截", "private fun tryFinish()" in src)
        assertTrue("返回键必须拦截", "override fun onBackPressed()" in src)
    }

    @Test
    fun 收藏体积上限的联动钳位不超过四分之一() {
        // 与保存路径（applyDraft 里对 favMaxMb 的同款钳位）共用这个纯函数：改口径只需改一处
        assertEquals(1, ClipboardPrefs.favoriteBytesCapMb(4))
        assertEquals(2, ClipboardPrefs.favoriteBytesCapMb(10))
        assertEquals(25, ClipboardPrefs.favoriteBytesCapMb(100))
        assertEquals(125, ClipboardPrefs.favoriteBytesCapMb(500))
    }

    /**
     * 回前台要重读盘上值，且**只同步本页没编辑过的键**（BUG.md L-1392）。
     *
     * 本页是「草稿 + 保存」模型，但布局两键（列数 / 行高）还有两个**即时落盘**的写方
     * （历史页网格、键盘面板「布局」键）。用户先在别处改布局 → 回本页 → 改别项并保存
     * ⇒ 旧草稿会把新布局**静默写回**。故 `onStart` 必须重读；同时不能冲掉本页未保存的编辑。
     */
    @Test
    fun 回前台要重读且只同步未编辑项() {
        val src = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        assertTrue(
            "回前台要重读盘上值（onStart 里取快照 + 同步 + 重渲染）",
            Regex("""override fun onStart\(\)[\s\S]{0,400}readPrefs\(\)[\s\S]{0,200}renderAll\(\)""")
                .containsMatchIn(src),
        )
        assertTrue(
            "同步必须走 syncUntouchedFrom（只动未编辑的键）",
            "syncUntouchedFrom(draft, before, fresh)" in src,
        )
        // 判据本身也要钉：草稿 == 旧快照 才跟随新值，否则保留草稿
        assertTrue(
            "判据是「等于旧快照才跟随」",
            Regex("""fun <T> pick\(cur: T, old: T, now: T\): T = if \(cur == old\) now else cur""")
                .containsMatchIn(src),
        )
    }

    @Test
    fun 锁定态不脏只有解锁才脏() {
        val d = ClipboardCustomizeActivity.Draft(
            enabled = true, maxItems = 1, maxTotalMb = 10, favMaxItems = 1, favMaxMb = 1,
            maxItemKb = 1, panelPage = 1, maxSearch = 1, imageEnabled = true, imageMaxItems = 1,
            imageMaxTotalMb = 1, imageMaxItemMb = 1, galleryColumns = 3, galleryCellHeightDp = 90,
            imageInAllGroup = true, favoriteImageMoveOut = false,
        )
        val o = d.copy(enabled = false)
        val c = d.copy(maxItems = 2)
        assertFalse(ClipboardCustomizeActivity.hasUnsavedChanges(o, d, false))
        assertTrue(ClipboardCustomizeActivity.hasUnsavedChanges(o, d, true))
        assertFalse(ClipboardCustomizeActivity.hasUnsavedChanges(c, d, false))
        assertTrue(ClipboardCustomizeActivity.hasUnsavedChanges(c, d, true))
        assertFalse(ClipboardCustomizeActivity.hasUnsavedChanges(d, d, true))
        val s = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        assertTrue(
            "isDirty 必须走统一判据",
            "private fun isDirty(): Boolean = hasUnsavedChanges(draft, snapshot, unlocked)" in s,
        )
        assertTrue(
            "按钮与返回仍须解锁后才脏",
            "val dirty = isDirty()" in s &&
                "saveButton.isEnabled = editable && dirty" in s &&
                "if (!isDirty())" in s,
        )
        assertTrue(
            "提示须覆盖回锁丢弃",
            "参数已锁定：改动先记草稿，解锁后可保存；锁定态（含回锁）返回将丢弃所有未保存改动" in s,
        )
        assertTrue("开关改动仍记草稿", "draft = draft.copy(enabled = v)" in s)
    }


    @Test
    fun 收紧告警必须按是否删收藏分流() {
        val d = ClipboardCustomizeActivity.Draft(
            enabled = true, maxItems = 10, maxTotalMb = 100, favMaxItems = 10, favMaxMb = 25,
            maxItemKb = 256, panelPage = 50, maxSearch = 100, imageEnabled = true,
            imageMaxItems = 100, imageMaxTotalMb = 500, imageMaxItemMb = 10,
            galleryColumns = 4, galleryCellHeightDp = 90, imageInAllGroup = true,
            favoriteImageMoveOut = false,
        )
        assertEquals(
            TightenScope.NONE,
            ClipboardCustomizeActivity.tightenScope(d, d),
        )
        assertEquals(
            TightenScope.NON_FAVORITE_ONLY,
            ClipboardCustomizeActivity.tightenScope(d.copy(maxItems = 9), d),
        )
        assertEquals(
            TightenScope.NON_FAVORITE_ONLY,
            ClipboardCustomizeActivity.tightenScope(d.copy(imageMaxItemMb = 9), d),
        )
        assertEquals(
            TightenScope.INCLUDING_FAVORITE,
            ClipboardCustomizeActivity.tightenScope(d.copy(favMaxItems = 9), d),
        )
        assertEquals(
            "压低历史总量会缩收藏生效上限，必须报含收藏",
            TightenScope.INCLUDING_FAVORITE,
            ClipboardCustomizeActivity.tightenScope(d.copy(maxTotalMb = 10), d),
        )
        val s = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        val nonFav = TestSources.window(
            s,
            "const val TEXT_SAVE_WARN_DELETE_NON_FAV =",
            "const val TEXT_SAVE_FAILED",
        )
        assertTrue(
            "非收藏档文案必须独立且语义正确",
            "含最旧的收藏" !in nonFav && "收藏不受影响" in nonFav,
        )
        assertTrue(
            "确认框必须按三档分流",
            "when (tightenScope(draft, snapshot))" in s &&
                "TightenScope.NON_FAVORITE_ONLY ->" in s,
        )
    }

}
