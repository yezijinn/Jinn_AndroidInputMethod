package com.jinn.inputmethod

import org.junit.Assert.assertEquals
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
 * 2026-11-01：语音四块迁到 `VoiceSettingsActivity` 后，本页只剩 5 个开关（词频学习 / 预测 /
 * 自动唤起 / 繁体字 / 翻译），全部是「开关自带文字」的标准行，不再有例外口径。
 * 清单用 [expected] 做**子集**判据：本守卫管的是「每个开关都够 40dp / 两行要省略号」，
 * 不是给本页做控件盘点 —— 将来**正常新增**一个开关时，只要它满足同一套尺寸规则就不该红
 * （写死「集合相等」会把新增开关也判成失败，逼着后来人先把这条断言改软，正是它上一版
 * 从 `>= 6` 退成 `>= 5` 的成因）；而清单里的某个开关**消失**（改名 / 被搬走 / 写法变了导致
 * 解析不到）必须红，那才是覆盖范围静默缩小。
 */
class SettingsSwitchLayoutTest {

    /** 本页**不能无声消失**的开关 id（新增开关不必登记；删掉或改名要同步这里） */
    private val expected = setOf(
        "switch_user_learning",
        "switch_predict",
        "switch_auto_show_keyboard",
        "switch_use_traditional",
        "switch_translate",
    )

    @Test
    fun 每个开关都必须达到四十dp触摸基线_两行文本要加省略号() {
        val xml = layout("activity_settings.xml")
        val switches = Regex("<Switch\\b.*?/>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.value }.toList()
        assertTrue("没解析到开关（布局结构变了？）", switches.isNotEmpty())

        val ids = switches.map {
            Regex("""android:id="@\+id/(\w+)"""").find(it)?.groupValues?.get(1) ?: "?"
        }
        assertEquals("每个开关都必须带 android:id", switches.size, ids.count { it != "?" })
        assertEquals("本页开关不得重名", ids.size, ids.toSet().size)
        // 子集判据：清单里的开关一个都不许消失（少一个 = 少一行被本守卫覆盖的控件），
        // 但**新增**开关不在本条拦下 —— 它照样走下面的逐条尺寸 / 省略号检查。
        val missing = expected - ids.toSet()
        assertEquals("本页原有开关解析不到了（被删 / 改名 / 换了标签名）：$missing", emptySet<String>(), missing)

        for (sw in switches) {
            val id = Regex("""android:id="([^"]+)"""").find(sw)?.groupValues?.get(1) ?: "?"
            val minH = Regex("""android:minHeight="(\d+)dp"""").find(sw)?.groupValues?.get(1)?.toInt()
            assertTrue("$id 的触摸高度 ${minH}dp 低于 40dp 基线", minH != null && minH >= 40)
            // 开关自带文字（maxLines=2）时超长会被静默裁掉，必须给省略号
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
