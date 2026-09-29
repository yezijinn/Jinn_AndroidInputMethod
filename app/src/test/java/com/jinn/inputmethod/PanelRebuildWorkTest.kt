package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 视图重建时的后台任务作废与诊断标记复位的源码对拍守卫（BUG.md L-17 / L-18）。
 *
 * 两个面板的后台任务都靠各自的 `refreshToken` 判停，而**令牌从哪里递增两个面板并不一样**：
 *  - 搜索面板：`onHidden()`（收起入口）与新请求起点都会递增 —— 收起即作废在飞任务；
 *  - 剪贴板面板：只有**新请求起点**（`refresh()`）与下面这条重建路径（`stopBackgroundWork()`）——
 *    它**没有**收起钩子（`onPanelHidden` 不存在），所以「正常收起面板」并不会作废在飞任务。
 * 重建键盘视图（换肤、符号分组顺序变更）两个面板的收起 / 打开入口都不走。
 * 不作废的话，旧面板会把任务跑完并把结果 post 到已经脱离视图树的列表上，同时占着单线程的
 * `BackgroundIo`，让剪贴板入库排在后面。
 *
 * 面板和 IME 都进不了 JVM 单测，只能做源码对拍；断言失败说明「重建前先收工」这条契约被拆掉了。
 */
class PanelRebuildWorkTest {

    @Test
    fun 两个面板都必须在视图重建时作废在飞的任务() {
        for ((name, tag) in listOf(
            "SearchPanelView.kt" to "搜索面板",
            "ClipboardPanelView.kt" to "剪贴板面板",
        )) {
            val src = codeOnly(sourceOf(name))
            assertTrue("$tag 缺少 stopBackgroundWork（视图重建时不会作废任务）", "fun stopBackgroundWork()" in src)
            val body = src.substringAfter("fun stopBackgroundWork()").substringBefore("\n    }")
            assertTrue("$tag 的 stopBackgroundWork 没有递增 refreshToken：任务不会被判停", "refreshToken++" in body)
        }
    }

    /**
     * 长按操作条只在**用户真的滚了列表**时才收起（`BUG.md` L-127）。
     *
     * 背景：`onScroll` 在**每一次布局**都会回调（面板打开、数据到达、乃至操作条自己出现把列表压矮），
     * 原判据「回调即收起」于是让操作条刚显示就被自己收掉 ⇒ 长按后「收藏 / 删除」按钮与文字全都看不见
     * （用户报告；真机取证：`15:06:53.007 长按菜单: 显示操作条` → 20ms 后 `15:06:53.027` 的布局回调
     * 又把它收起；把该判据关掉后同一操作条能正常画出：按钮面 + 删除的红字都在）。
     * 判据只能是滚动状态（`onScrollStateChanged`），不能用「onScroll 被调用」。
     */
    @Test
    fun 长按操作条不得被布局回调收起() {
        val src = codeOnly(sourceOf("ClipboardPanelView.kt"))
        assertTrue(
            "收起必须挂在「用户真的滚了」的判据上（listScrolling）",
            "if (listScrolling && ::actionBar.isInitialized && actionBar.visibility == View.VISIBLE) hideActionBar()" in src,
        )
        assertTrue(
            "onScrollStateChanged 必须把滚动状态写进 listScrolling",
            "listScrolling = scrollState != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE" in src,
        )
        assertFalse(
            "不得再用「onScroll 一到就收起」：布局每次变化都会回调 ⇒ 操作条刚显示就被自己收掉（BUG.md L-127）",
            "if (::actionBar.isInitialized && actionBar.visibility == View.VISIBLE) hideActionBar()" in src,
        )
    }

    @Test
    fun `键盘视图要暴露一个「两个面板一起收工」的入口`() {
        val src = codeOnly(sourceOf("PinyinKeyboardView.kt"))
        val body = src.substringAfter("fun stopPanelBackgroundWork()").substringBefore("\n    }")
        assertTrue("未让剪贴板面板收工", "clipboardPanel.stopBackgroundWork()" in body)
        assertTrue("未让搜索面板收工", "searchPanel.stopBackgroundWork()" in body)
    }

    @Test
    fun 重建视图必须先让旧面板收工并在之后复位诊断标记() {
        val src = codeOnly(sourceOf("JinnIme.kt"))
        val body = src.substringAfter("private fun recreateKeyboardView()").substringBefore("\n    }")
        val stop = body.indexOf("stopPanelBackgroundWork()")
        val swap = body.indexOf("setInputView(onCreateInputView())")
        assertTrue("重建前没有让旧面板收工", stop >= 0)
        assertTrue("收工必须发生在换视图之前，否则旧面板已脱离、任务照跑", swap >= 0 && stop < swap)
        assertTrue("新视图的面板是收起态，IME 侧标记必须复位（否则日志一直报已打开）", "clipboardPanelOpen = false" in body)
    }

    /**
     * 搜索面板**收起时就作废**在飞任务（`BUG.md` L-75）。
     *
     * 这条契约只对搜索面板成立（上面 KDoc 已写明两个面板的差别）：把 `refreshToken++` 从
     * `onHidden()` 里删掉，旧 worker 会在面板已隐藏后回填结果（列表已清空，等于白跑一轮整库扫描）。
     */
    @Test
    fun 搜索面板收起时必须作废在飞任务() {
        val src = codeOnly(sourceOf("SearchPanelView.kt"))
        val body = src.substringAfter("fun onHidden()").substringBefore("\n    }")
        assertTrue(
            "搜索面板 onHidden 必须递增 refreshToken（收起即作废在飞任务）",
            "refreshToken++" in body,
        )
    }

    /**
     * 剪贴板面板必须在**会话边界**收起（`BUG.md` L-110）。
     *
     * 它是字母区上的「视图内临时态」（与方向面板同类），而视图实例跨输入框复用：
     * `configure`（每次聚焦都调用）与 `onFinishInputView`（键盘收起）都不收时，新输入框会以
     * 「面板」弹出 —— 字母区被历史条目接管，误点一下就把上一个输入框的历史内容粘进新字段（隐私面）。
     * 搜索面板在这两处早已收掉，剪贴板面板原先两处都没有（不对称）。
     *
     * 两处都必须走 `hideClipboardPanel()`：它恢复字母区布局并把状态回传 IME；裸置
     * `clipboardActive = false` 会把字母区留在面板的 2 行高度上（键盘变形）。
     */
    @Test
    fun `会话边界必须收起剪贴板面板`() {
        val view = codeOnly(sourceOf("PinyinKeyboardView.kt"))
        val configure = codeOnly(
            view.substringAfter("fun configure(scheme: ShuangpinScheme").substringBefore("\n    }"),
        )
        assertTrue(
            "configure 必须收起剪贴板面板（否则新输入框以面板弹出）",
            "if (clipboardActive) hideClipboardPanel()" in configure,
        )
        assertFalse(
            "不得裸置 clipboardActive = false（字母区会停在面板的 2 行高度）",
            "clipboardActive = false" in configure,
        )
        val ime = sourceOf("JinnIme.kt")
        val finish = codeOnly(
            ime.substringAfter("override fun onFinishInputView(").substringBefore("\n    }"),
        )
        assertTrue("键盘收起时必须收起剪贴板面板", "hideClipboardPanel()" in finish)
        assertTrue("搜索面板的收起不得被删（同族既有契约）", "hideSearchPanel()" in finish)
    }

    /**
     * 只留代码行：**注释里提到**某个写法（例如「裸置 `clipboardActive = false` 会让…」）
     * 不能算命中 —— 本文件是源码对拍，判据必须是真代码（这条坑踩过一次，守卫把说明注释判红）。
     */
    /**
     * 操作条出现 / 收起时必须**复位**滚动标志（`BUG.md` L-132）。
     *
     * `listScrolling` 只在 `onScrollStateChanged` 里写，而触摸流被截断时（一指按住列表、
     * 另一指把键盘收掉）`ACTION_CANCEL` 可能永远不来 ⇒ 标志粘在 `true`，之后操作条**自身**
     * 引起的布局回调（`onScroll`）就会立刻把它收掉 —— 那正是 L-127 的原症状。
     * 判据：显示点与收起点都必须复位（不靠上一版手势的残余）。
     */
    @Test
    fun 长按操作条出现时必须复位滚动标志() {
        val src = codeOnly(sourceOf("ClipboardPanelView.kt"))
        val show = src.substringAfter("private fun showItemMenu(item: ClipboardDb.Item) {")
            .substringBefore("private fun hideActionBar()")
        assertTrue(
            "显示操作条前必须复位 listScrolling（否则粘性标志会把它立刻收掉）",
            "listScrolling = false" in show,
        )
        val hide = src.substringAfter("private fun hideActionBar() {").substringBefore("\n    }")
        assertTrue("收起操作条时也要复位 listScrolling", "listScrolling = false" in hide)
    }

    /**
     * 刷新失败必须把**视图状态**也归位（`BUG.md` L-144）。
     *
     * 只打日志的话：列表还留着**上一个分类**的条目，而顶栏分类标签已经切走 ⇒ 用户点一条粘贴出来的
     * 是另一个分类的正文（与 `handleItemClick` 注释「粘的与看到的是同一份」相反）；首次加载即失败时
     * 还会是「空白面板、连空态文案都没有」。空态也要说真话（读取失败 ≠ 暂无历史）。
     */
    @Test
    fun 刷新失败必须把视图状态归位() {
        val src = codeOnly(sourceOf("ClipboardPanelView.kt"))
        val fail = src.substringAfter("}.onFailure {").substringBefore("Diagnostics.e(")
        assertTrue("失败分支必须清空列表快照", "currentItems = mutableListOf()" in fail)
        assertTrue("失败分支必须归位分页状态", "hasMorePages = false" in fail)
        assertTrue("失败分支必须让空态说真话（loadFailed）", "loadFailed = true" in fail)
        assertTrue("失败分支必须重绘空态", "updateEmpty()" in fail)
        assertTrue("空态要区分「读取失败」与「暂无历史」", "loadFailed -> TEXT_EMPTY_LOAD_FAILED" in src)
        assertTrue("成功分支要清掉失败标记（否则下次成功仍显示「读取失败」）",
            src.substringAfter("scanStoppedEarly = currentItems.isEmpty() && nextPageOffset < total")
                .substringBefore("adapter.notifyDataSetChanged()").contains("loadFailed = false"))
    }

    /**
     * 长按操作的异步回调必须带**令牌**（`BUG.md` L-144）。
     *
     * 写库走 `BackgroundIo`，回调回来时用户可能已经长按了另一条、或切了分类 ——
     * 旧回调若照样 `hideActionBar()`，用户刚打开的那条会被收掉（与 L-127 同一症状）；
     * `refresh(resetScroll = true)` 还会把列表不讲道理地拉回顶部。
     */
    @Test
    fun 长按操作的回调必须带令牌() {
        val src = codeOnly(sourceOf("ClipboardPanelView.kt"))
        for (name in listOf("private fun toggleFavorite()", "private fun deleteItem()")) {
            val body = src.substringAfter(name).substringBefore("private fun ")
            assertTrue("$name 必须在进后台前抓令牌", "val token = actionToken" in body)
            assertTrue("$name 的回调必须按令牌决定要不要动界面", "token == actionToken" in body)
        }
        val hide = src.substringAfter("private fun hideActionBar() {").substringBefore("\n    }")
        assertTrue("收起操作条要作废在飞操作（切分类 / 收起后旧回调不得再动界面）", "actionToken++" in hide)
        val show = src.substringAfter("private fun showItemMenu(item: ClipboardDb.Item) {")
            .substringBefore("private fun hideActionBar()")
        assertTrue("每次显示操作条都要换令牌", "actionToken++" in show)
    }

    private fun codeOnly(text: String): String = TestSources.codeOf(text)

    private fun sourceOf(name: String): String = TestSources.rawSourceOfShortName(name)
}
