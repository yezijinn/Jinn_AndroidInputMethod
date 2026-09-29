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
        // 「两页是复刻关系，改动必须同步」是页面里写下的契约，这里只钉最容易分叉的两处：
        // 关闭键的键面文字与 contentDescription（无障碍读出来的名字）。
        val fuzzy = codeOf("FuzzyPinyinActivity.kt")
        val rare = codeOf("RareCharsActivity.kt")
        assertTrue("模糊音页的关闭键缺少可听键名", "contentDescription = TEXT_CLOSE_DESC" in fuzzy)
        assertTrue("生僻字页的关闭键缺少可听键名", "contentDescription = TEXT_CLOSE_DESC" in rare)
        assertTrue("两页关闭键的可听键名必须同名同值", "\"关闭\"" in fuzzy && "\"关闭\"" in rare)
    }

    /** 取方法体（从签名到下一个 4 空格缩进的右花括号；两页的方法体都不到这一层嵌套之外） */
    private fun bodyOf(src: String, signature: String): String {
        val idx = src.indexOf(signature)
        assertTrue("源码里找不到 $signature（签名变了？）", idx >= 0)
        return src.substring(idx).substringBefore("\n    }")
    }

    private fun sourceOf(name: String): String = TestSources.rawSourceOfShortName(name)

    /** 判据一律在**剥注释后**的代码上做（BUG.md L-116）：注释里提到 `renderRows()` 不算实现 */
    private fun codeOf(name: String): String = TestSources.codeOf(sourceOf(name))
}
