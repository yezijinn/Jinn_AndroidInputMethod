package com.jinn.inputmethod

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 换肤延后判据必须覆盖全部「视图内临时态」（`BUG.md` L-87）。
 *
 * 定时换肤（`MODE_SCHEDULED` 的 `themeTick`）在键盘可见期间准点触发 `applyThemeIfNeeded()`，
 * 到点即 `recreateKeyboardView()`；而面板与密码模式的状态都挂在**旧视图实例**上，重建等于静默丢弃：
 * 面板会毫无预告地关闭；密码模式的复原三元组（`passwordPadRestore`）只活在旧视图上 ⇒
 * 用户正输密码时键盘变回普通键盘，且**回不去**。
 *
 * 判据 `hasActiveOverlay` 原先只含三种面板、漏了密码模式（2026-09-28 修）。本用例把它钉成契约：
 * 四类状态一个都不能少。断言在**剥掉注释后**做 —— 注释里提到名字不算实现（BUG.md L-116）。
 */
class ThemeDeferralOverlayTest {

    private fun sourceOf(name: String): String = TestSources.rawSourceOfShortName(name)

    /** 只留代码：共用 [TestSources.codeOf]（行尾注释、块注释、XML 注释一起剥，见 BUG.md L-116） */
    private fun codeOnly(text: String): String = TestSources.codeOf(text)

    @Test
    fun `换肤延后判据必须覆盖密码模式与三种面板`() {
        val getter = codeOnly(sourceOf("PinyinKeyboardView.kt"))
            .substringAfter("val hasActiveOverlay: Boolean")
            .take(300)
        for ((state, why) in listOf(
            "clipboardActive" to "剪贴板面板",
            "searchPanel.isActive()" to "顶部搜索面板",
            "directionPanelVisible" to "方向面板",
            "passwordPad" to "密码模式（复原三元组只活在旧视图上，重建后回不去）",
        )) {
            assertTrue("hasActiveOverlay 漏了 $why ⇒ 重建会静默丢弃该状态", state in getter)
        }
        assertTrue(
            "getter 里出现了赋值/重置（只允许读状态，不允许在这里改状态）",
            "=" !in getter.replace("get() =", ""),
        )

        // 使用侧契约：重建前必须同时判「未上屏输入」与「临时态」，且延后要留日志（真机回归靠它取证）
        val use = codeOnly(sourceOf("JinnIme.kt"))
            .substringAfter("private fun applyThemeIfNeeded()")
            .take(900)
        assertTrue(
            "换肤重建前必须判「未上屏输入 || 临时态」",
            "it.hasPendingInput || it.hasActiveOverlay" in use,
        )
        assertTrue("延后分支必须留下日志（真机取证判据）", "延后到下次弹出换肤" in use)
    }
}
