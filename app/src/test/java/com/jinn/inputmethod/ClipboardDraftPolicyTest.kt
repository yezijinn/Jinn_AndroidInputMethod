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
        // 反向钉：任何 `prefs.<参数> =` 形式的直接赋值都意味着绕过草稿（读侧是 getter，不受影响）
        val directWrite = Regex(
            """prefs\.(enabled|maxItems|maxTotalBytesMb|favoriteMaxItems|favoriteMaxBytesMb|""" +
                """maxItemBytesKb|panelPageItems|maxSearchResults|historyPageSize|""" +
                // 2026-10-10：图片四项（记录图片 / 张数 / 体积 / 单张）同样只能留在草稿里
                """imageCaptureEnabled|imageMaxItems|imageMaxTotalMb|imageMaxItemMb)\s*="""
        )
        assertFalse("参数不得直接写盘（必须留在草稿，点保存才落盘）", directWrite.containsMatchIn(src))
        assertTrue("保存必须走原子入口 applyDraft", "prefs.applyDraft(" in src)
        assertTrue("渲染必须读草稿", "draft = draft.copy(" in src)
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
}
