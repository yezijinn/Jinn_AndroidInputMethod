package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「复刻页」对的渲染时机守卫（`BUG.md` L-48 / L-74）。
 *
 * 模糊音页与生僻字页是**复刻关系**（同一套结构、同一套文案下发方式，页面里也互相注明「改动必须同步」），
 * 但两页的勾选态渲染时机曾经各自漂移：都只在 `onCreate` 画一次 —— 页面还在栈里时，
 * 从设置页导入配置 / 改过值、再返回本页，看到的是**旧快照**（再拨一下才纠正；生僻字页更糟，
 * 会把另一档按旧显示写回，等于回滚导入结果）。
 *
 * 两页都进不了 JVM 单测（Activity + 视图），只能源码对拍：**渲染必须挂在 `onStart`**，
 * 且**不得同时留在 `onCreate`**（只画一次就会读到过期状态）。
 */
class MirroredSettingsPagesTest {

    @Test
    fun 两个复刻页都必须在onStart重画勾选态() {
        for (page in listOf("FuzzyPinyinActivity.kt", "RareCharsActivity.kt")) {
            val src = codeOf(page)
            assertTrue(
                "$page 必须在 onStart 里渲染（每次回前台重读 prefs）",
                bodyOf(src, "override fun onStart()").contains("renderRows()"),
            )
            assertFalse(
                "$page 的渲染不得留在 onCreate（只画一次就会停在旧快照上）",
                bodyOf(src, "override fun onCreate(").contains("renderRows()"),
            )
        }
    }

    @Test
    fun 两页的关闭键文案与可听键名保持一致() {
        // 「两页是复刻关系，改动必须同步」是页面里写下的契约。原先这里钉的是两页各自的
        // `TEXT_CLOSE` / `TEXT_CLOSE_DESC` 常量；2026-10-02 全仓统一后（BUG.md L-477），
        // 键面文字与可听名只有**一个**定义（`PageChrome`），于是判据改成「两页都必须走那个唯一定义」
        // —— 这比原先两条常量各自相等更强（原来两页可以各自漂移成同一个错值）。
        for (page in listOf("FuzzyPinyinActivity.kt", "RareCharsActivity.kt")) {
            val src = codeOf(page)
            assertTrue("$page 的关闭键缺少可听键名", "contentDescription = PageChrome.CLOSE_DESC" in src)
            assertTrue("$page 的关闭键键面文字必须来自 PageChrome", "text = PageChrome.CLOSE" in src)
        }
    }

    /** 取方法体：花括号配对到对应收尾（锚点缺失或括号不配对本用例即红，见 BUG.md L-1145） */
    private fun bodyOf(src: String, signature: String): String = TestSources.blockAfter(src, signature)

    private fun sourceOf(name: String): String = TestSources.rawSourceOfShortName(name)

    /** 判据一律在**剥注释后**的代码上做（BUG.md L-116）：注释里提到 `renderRows()` 不算实现 */
    private fun codeOf(name: String): String = TestSources.codeOf(sourceOf(name))
}
