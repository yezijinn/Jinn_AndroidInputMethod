package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底栏标点输出页的接线守卫（2026-10-16 纠正：该参数从键盘外观页移出，
 * 改由首页「空格左右按钮」**按钮**打开的独立页 [PunctOutputActivity] 承载）。
 *
 * 这条只能在源码上对拍（Activity + 视图进不了 JVM 单测）：入口必须仍是**按钮**（不是开关）、
 * 四个参数键落在新页、旧页不再残留、新页已在清单注册。
 */
class PunctOutputPageTest {

    @Test
    fun `首页入口是按钮且打开标点输出页`() {
        val code = TestSources.codeSource("SettingsActivity.kt")
        assertTrue(
            "首页「空格左右按钮」必须是按钮入口",
            "findViewById<Button>(R.id.btn_space_cursor)" in code,
        )
        assertTrue(
            "该按钮必须打开 PunctOutputActivity",
            "PunctOutputActivity::class.java" in code,
        )
        assertFalse(
            "首页不得再把它做成开关（switch_space_cursor 已迁出）",
            "R.id.switch_space_cursor" in code,
        )
    }

    @Test
    fun `标点页绑定四个输入项`() {
        val code = TestSources.codeSource("PunctOutputActivity.kt")
        for (id in listOf(
            "R.id.edit_punct_comma_cn", "R.id.edit_punct_period_cn",
            "R.id.edit_punct_comma_en", "R.id.edit_punct_period_en",
        )) {
            assertTrue("标点页缺少输入项 $id", id in code)
        }
        assertTrue("输入必须走 PunctOutput 的单字符归一", "PunctOutput.sanitizeField(" in code)
    }

    @Test
    fun `标点参数已移出键盘外观页`() {
        assertFalse(
            "键盘外观页仍在绑定标点输入框",
            "R.id.edit_punct_comma_cn" in TestSources.codeSource("KeyAppearanceActivity.kt"),
        )
        val layout = TestSources.codeOf(
            listOf(
                File("src/main/res/layout/activity_key_appearance.xml"),
                File("app/src/main/res/layout/activity_key_appearance.xml"),
            ).first { it.isFile }.readText(),
        )
        assertFalse("键盘外观页布局仍留着标点卡片", "edit_punct_comma_cn" in layout)
    }

    @Test
    fun `标点页已在清单注册`() {
        val manifest = TestSources.codeOf(
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("app/src/main/AndroidManifest.xml"),
            ).first { it.isFile }.readText(),
        )
        assertTrue(
            "AndroidManifest 未注册 PunctOutputActivity",
            "android:name=\".PunctOutputActivity\"" in manifest,
        )
    }
}
