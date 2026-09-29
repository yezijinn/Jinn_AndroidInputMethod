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
            val src = sourceOf(name)
            assertTrue("$tag 缺少 stopBackgroundWork（视图重建时不会作废任务）", "fun stopBackgroundWork()" in src)
            val body = src.substringAfter("fun stopBackgroundWork()").substringBefore("\n    }")
            assertTrue("$tag 的 stopBackgroundWork 没有递增 refreshToken：任务不会被判停", "refreshToken++" in body)
        }
    }

    @Test
    fun `键盘视图要暴露一个「两个面板一起收工」的入口`() {
        val src = sourceOf("PinyinKeyboardView.kt")
        val body = src.substringAfter("fun stopPanelBackgroundWork()").substringBefore("\n    }")
        assertTrue("未让剪贴板面板收工", "clipboardPanel.stopBackgroundWork()" in body)
        assertTrue("未让搜索面板收工", "searchPanel.stopBackgroundWork()" in body)
    }

    @Test
    fun 重建视图必须先让旧面板收工并在之后复位诊断标记() {
        val src = sourceOf("JinnIme.kt")
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
        val src = sourceOf("SearchPanelView.kt")
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
        val view = sourceOf("PinyinKeyboardView.kt")
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
    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private fun sourceOf(name: String): String {
        val path = listOf(
            File("src/main/java/com/jinn/inputmethod/$name"),
            File("app/src/main/java/com/jinn/inputmethod/$name"),
        ).firstOrNull { it.isFile } ?: error("找不到 $name")
        return path.readText()
    }
}
