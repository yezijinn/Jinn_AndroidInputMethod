package com.jinn.inputmethod

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 换肤延后判据必须覆盖全部「视图内临时态」（`BUG.md` L-87）。
 *
 * 定时换肤（`MODE_SCHEDULED` 的 [ThemeManager.ScheduledThemeTicker]）在键盘可见期间准点触发
 * `applyThemeIfNeeded()`，
 * 到点即 `recreateKeyboardView()`；而面板与密码模式的状态都挂在**旧视图实例**上，重建等于静默丢弃：
 * 面板会毫无预告地关闭；密码模式的复原三元组（`passwordPadRestore`）只活在旧视图上 ⇒
 * 用户正输密码时键盘变回普通键盘，且**回不去**。
 *
 * 判据 `hasActiveOverlay` 原先只含三种面板、漏了密码模式（2026-09-28 修）。本用例把它钉成契约：
 * **全部五类状态**一个都不能少。断言在**剥掉注释后**做 —— 注释里提到名字不算实现（BUG.md L-116）。
 *
 * 2026-10-23（L-1434）：图库面板加入 getter 后本用例没跟上 —— 删掉 `galleryActive` 仍然全绿
 * （变异实证，见台账）。清单是穷举契约，新增临时态必须同步到这里。
 */
class ThemeDeferralOverlayTest {

    private fun sourceOf(name: String): String = TestSources.rawSourceOfShortName(name)

    /** 只留代码：共用 [TestSources.codeOf]（行尾注释、块注释、XML 注释一起剥，见 BUG.md L-116） */
    private fun codeOnly(text: String): String = TestSources.codeOf(text)

    @Test
    fun `换肤延后判据必须覆盖密码模式与四类面板`() {
        // 取整条属性：锚点先断言存在，再按空行边界收口并校验长度（BUG-10）。
        // 原先只有 300 字符窗：合法重排布尔子句就能让某个状态滑到窗口外，而测试仍绿。
        val viewSrc = codeOnly(sourceOf("PinyinKeyboardView.kt"))
        val at = viewSrc.indexOf("val hasActiveOverlay: Boolean")
        assertTrue("PinyinKeyboardView 里找不到 hasActiveOverlay（改名/搬迁后要同步本用例）", at >= 0)
        val getter = viewSrc.substring(at).substringBefore("\n\n")
        assertTrue("hasActiveOverlay 没取到整条属性（长度 ${getter.length}）", getter.length in 20..600)
        for ((state, why) in listOf(
            "clipboardActive" to "剪贴板面板",
            "galleryActive" to "图库面板（宿主可收图时才有，但面板状态同样只活在旧视图上）",
            "isSearchPanelActive()" to "顶部搜索面板（MEM-05 起按需创建，判定函数先判存在）",
            "directionPanelVisible" to "方向面板",
            "passwordPad" to "密码模式（复原三元组只活在旧视图上，重建后回不去）",
        )) {
            assertTrue("hasActiveOverlay 漏了 $why ⇒ 重建会静默丢弃该状态", state in getter)
        }
        assertTrue(
            "getter 里出现了赋值/重置（只允许读状态，不允许在这里改状态）",
            "=" !in getter.replace("get() =", ""),
        )

        // 使用侧契约：重建前必须同时判「未上屏输入」与「临时态」，且延后要留日志（真机回归靠它取证）。
        // 整函数按花括号配对取（BUG-10）：原先 900 字符窗，分支一挪出窗户判据就形同没有。
        val use = TestSources.blockAfter(codeOnly(sourceOf("JinnIme.kt")), "private fun applyThemeIfNeeded()")
        assertTrue(
            "换肤重建前必须判「未上屏输入 || 临时态 || 按住键」",
            "it.hasPendingInput || it.hasActiveOverlay || it.hasPressedKey" in use,
        )
        assertTrue("延后分支必须留下日志（真机取证判据）", "延后到下次弹出换肤" in use)
        // 按住态（BUG.md L-1442）：判据之外还要有记账，只有判据没有记账等于恒 false
        assertTrue("PinyinKeyboardView 必须暴露 hasPressedKey", "val hasPressedKey: Boolean" in viewSrc)
        // 记账必须**逐分支**钉（2026-10-27 独立审计）：原先三条断言都只在**整个文件**里找
        // `pressedKeyPointers++` / `pressedKeyPointers = 0`，而共享清理点本身就有一句归零 ⇒
        // 把 ACTION_CANCEL 分支里的归零删掉、把 ACTION_DOWN 的加一删掉，两条断言照样绿
        // （变异实证：只剩 POINTER_DOWN 的加一与清理点的归零时，本用例全绿）。
        val touch = TestSources.blockAfter(viewSrc, "private fun handleKeyTouch(c: Char, event: MotionEvent)")
        val downBranch = TestSources.blockAfter(touch, "MotionEvent.ACTION_DOWN -> {")
        assertTrue("按下要记账", "pressedKeyPointers++" in downBranch)
        val pDownBranch = TestSources.blockAfter(touch, "MotionEvent.ACTION_POINTER_DOWN -> {")
        assertTrue("第二根手指按下也要记账", "pressedKeyPointers++" in pDownBranch)
        val pUpBranch = TestSources.blockAfter(touch, "MotionEvent.ACTION_POINTER_UP -> {")
        assertTrue(
            "抬手要扣账",
            "pressedKeyPointers = (pressedKeyPointers - 1).coerceAtLeast(0)" in pUpBranch,
        )
        val upBranch = TestSources.blockAfter(touch, "MotionEvent.ACTION_UP -> {")
        assertTrue(
            "最后一根手指抬起要扣账",
            "pressedKeyPointers = (pressedKeyPointers - 1).coerceAtLeast(0)" in upBranch,
        )
        val cancelBranch = TestSources.blockAfter(touch, "MotionEvent.ACTION_CANCEL -> {")
        assertTrue("取消要归零（整手作废，不许只减一）", "pressedKeyPointers = 0" in cancelBranch)
        // ⚠ 归零必须落在**共享清理点**（L-1442 的第二面，本轮审查补）：键盘收起时平台不保证补发
        // ACTION_CANCEL，而视图会被跨会话复用（不 detach）—— 只写在 onDetachedFromWindow 里的话，
        // 计数停在非零 ⇒ 定时换肤到点判「用户按着键」静默延后，且单指就能踩到。
        val cleanup = TestSources.blockAfter(viewSrc, "fun stopBackspaceRepeat()")
        assertTrue(
            "共享清理点必须把字母键按住态归零（隐藏键盘不 detach 的路径全靠它）",
            "pressedKeyPointers = 0" in cleanup,
        )
        val detach = TestSources.blockAfter(viewSrc, "override fun onDetachedFromWindow()")
        assertTrue("detach 必须走同一条清理（不许另写一份归零）", "stopBackspaceRepeat()" in detach)
        // 隐藏路径同理：`onFinishInputView` 不一定派发，`onWindowHidden` 是最后一道还能动的时机
        val imeSrc = codeOnly(sourceOf("JinnIme.kt"))
        val hidden = TestSources.blockAfter(imeSrc, "override fun onWindowHidden()")
        assertTrue(
            "窗口隐藏必须走同一条清理（否则换肤在隐藏后永久延后）",
            "pinyinKeyboard?.stopBackspaceRepeat()" in hidden,
        )
        // 这条是「漂移最多活到下一次弹出」的**唯一依据**：`onStartInputView` 里的 applyThemeIfNeeded
        // 不加任何复位就再判一次，靠的是会话边界那两处（finish / hidden）已经把计数清了。
        // 少了它，一次漏账就会把后续每一次弹出都判成「按着键」—— 那才是真正的永久卡死。
        val finish = TestSources.blockAfter(imeSrc, "override fun onFinishInputView(finishingInput: Boolean)")
        assertTrue(
            "会话结束必须走同一条清理（否则漏账会跨会话活到永久）",
            "pinyinKeyboard?.stopBackspaceRepeat()" in finish,
        )
        // 同族的第二条重建路径（符号分组顺序 / 收藏符号变更）也必须判按住态：
        // 悬浮键盘可以盖在编辑页上，一手按住键、一手点排序，重建就把那次按键换掉
        val rebuild = TestSources.blockAfter(imeSrc, "fun rebuildInputViewForSymbolLayout()")
        assertTrue(
            "符号布局重建同样不得换掉按住的键（L-1442 的同族路径）",
            "keyboard.hasPendingInput || keyboard.hasPressedKey" in rebuild,
        )
    }
}
