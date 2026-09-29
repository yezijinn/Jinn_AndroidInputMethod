package com.jinn.inputmethod

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置页开关尺寸的源码对拍守卫（BUG.md L-35 的两项）。
 *
 *  - **触摸高度**：标准行的开关原先 `minHeight=36dp`，低于本项目 40dp 基线；开关本身是
 *    带文字的 `Switch`，行高由它撑起来，改小就是缩小可点区域。
 *  - **文本截断**：这些开关的文字最长两句（`maxLines=2`），却不带 `ellipsize` ——
 *    系统字体放大或文案变长时，第二行会被**静默裁掉**而不是显示省略号。
 *
 * 语音卡片的开关（`switch_voice_input`）没有 `maxLines`：它自成一套卡片尺寸（L-35 另记），
 * 本守卫只覆盖标准行，避免把两套口径混成一条。
 */
class SettingsSwitchLayoutTest {

    @Test
    fun 每个开关都必须达到四十dp触摸基线_两行文本要加省略号() {
        val xml = layout("activity_settings.xml")
        val switches = Regex("<Switch\\b.*?/>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.value }.toList()
        assertTrue("没解析到开关（布局结构变了？）", switches.size >= 6)

        for (sw in switches) {
            val id = Regex("""android:id="([^"]+)"""").find(sw)?.groupValues?.get(1) ?: "?"
            val minH = Regex("""android:minHeight="(\d+)dp"""").find(sw)?.groupValues?.get(1)?.toInt()
            assertTrue("$id 的触摸高度 ${minH}dp 低于 40dp 基线", minH != null && minH >= 40)
            // 开关自带文字（maxLines=2）时超长会被静默裁掉，必须给省略号；
            // 语音卡片里的开关文字在隔壁 TextView 上，不需要这一条
            if (sw.contains("android:maxLines=")) {
                assertTrue("$id 的两行文本没有 ellipsize，超长会被静默裁掉", sw.contains("android:ellipsize="))
            }
        }
    }

    private fun layout(name: String): String {
        val path = listOf(File("src/main/res/layout/$name"), File("app/src/main/res/layout/$name"))
            .firstOrNull { it.isFile } ?: error("找不到 $name")
        return path.readText()
    }
}
