package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两套色板必须**逐名对齐**（`values/` 亮白 vs `values-night/` 暗黑）。
 *
 * 少写一个名字不会编译报错，也不会崩：该令牌在另一套主题下**静默退回**默认目录的值，
 * 表现为「亮色下某个部件还是深色」这类只有肉眼能发现的问题（配色与对比度双输）。
 * 本测试把两边的 `<color name>` 集合钉死相等。
 */
class ThemeColorParityTest {

    @Test
    fun 亮白与暗黑色板逐名对齐() {
        val light = colorNames(resFile("values/colors.xml"))
        val dark = colorNames(resFile("values-night/colors.xml"))
        assertEquals("暗黑色板多出的令牌: ${dark - light}", emptySet<String>(), dark - light)
        assertEquals("暗黑色板缺少的令牌（会静默退回亮色值）: ${light - dark}", emptySet<String>(), light - dark)
    }

    @Test
    fun 两套色板都非空() {
        // 防呆：路径写错时上面那条会因为「两边都是空集」而假绿
        val light = colorNames(resFile("values/colors.xml"))
        assertTrue("亮白色板项数异常（路径可能写错）: ${light.size}", light.size > 30)
    }

    /**
     * 同一条 style 在两套主题里的 `<item name>` 必须一致。
     *
     * `values-night/themes.xml` 的同名 style 是**整条替换**（不是逐项合并）：少写一个 item，
     * 那一项在暗色下就退回框架默认值（例如 `textColorPrimary` 丢失 → 文字用系统色），
     * 编译、lint、运行都不报错，只能靠肉眼在深色下发现。
     */
    @Test
    fun 两套主题定义的item逐名对齐() {
        val light = itemNames(resFile("values/themes.xml"))
        val dark = itemNames(resFile("values-night/themes.xml"))
        assertEquals("暗色主题多出的 item: ${dark - light}", emptySet<String>(), dark - light)
        assertEquals("暗色主题缺少的 item（会退回框架默认值）: ${light - dark}", emptySet<String>(), light - dark)
    }

    /**
     * 主题下拉的 values 数组必须与 [ThemeManager] 常量一一对应（entries 与它同序同长）。
     *
     * 数组是 XML 里的裸数字：改错一位（例如把「亮白」写成 2）就会存成另一个模式，
     * 编译、lint 都不报错，只能在真机上发现「选了亮白却是暗黑」。
     */
    @Test
    fun 主题下拉取值与常量一一对应() {
        val xml = resFile("values/strings.xml").readText()
        val values = Regex(
            "<string-array name=\"theme_mode_values\">(.*?)</string-array>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.get(1)
            ?.let { block -> Regex("<item>(.*?)</item>").findAll(block).map { it.groupValues[1].trim() }.toList() }
            ?: emptyList()
        val expected = listOf(
            ThemeManager.MODE_SYSTEM, ThemeManager.MODE_LIGHT,
            ThemeManager.MODE_DARK, ThemeManager.MODE_SCHEDULED,
        ).map(Int::toString)
        assertEquals("主题下拉 values 与 ThemeManager 常量不一致", expected, values)
    }

    /**
     * 键盘透明度的标题文案必须与定义域（[KeyTransparency]）一致。
     *
     * 文案是写死的字符串：改了定义域忘改文案，编译 / lint / 运行都不报错，
     * 只有用户会看到「0% ~ 80%」这种过期描述（2026-09-22 就把上限从 80 提到 100）。
     */
    @Test
    fun 透明度标题文案与定义域一致() {
        val xml = resFile("values/strings.xml").readText()
        val title = Regex("<string name=\"key_transparency_title\">(.*?)</string>")
            .find(xml)?.groupValues?.get(1)
            ?: error("找不到 key_transparency_title")
        // ⚠ 断言「下界% ~ 上界%」连写形式，别写成 contains("0%")：
        // 那是 "100%" 的子串，上界存在时下界断言恒真（守卫形同虚设）。
        assertTrue(
            "标题未按下界~上界连写（${KeyTransparency.MIN_PERCENT}% ~ ${KeyTransparency.MAX_PERCENT}%）: $title",
            title.contains("${KeyTransparency.MIN_PERCENT}% ~ ${KeyTransparency.MAX_PERCENT}%"),
        )
    }

    /**
     * 参与透明度合成的色令牌**必须不透明**（6 位 `#RRGGBB`）。
     *
     * `KeyTransparency.withAlpha` 是「替换 alpha」语义：令牌若自带 alpha（如 `#33RRGGBB`），
     * 0%（历史观感档）会被强制拉回不透明，且全树扫描按 RGB 匹配会把它当作面。
     */
    @Test
    fun 参与合成的色令牌必须不透明() {
        val mustOpaque = listOf(
            "kb_bg", "kb_divider", "kb_candidate_bg", "kb_key", "kb_key_pressed",
            "key_bg", "app_bg", "card_bg", "surface_hi",
            "btn_secondary_bg", "card_stroke", "accent",
        )
        for (dir in listOf("values", "values-night")) {
            val xml = resFile("$dir/colors.xml").readText()
            for (name in mustOpaque) {
                val hex = Regex("<color name=\"$name\">#([0-9A-Fa-f]+)</color>")
                    .find(xml)?.groupValues?.get(1)
                    ?: error("$dir/colors.xml 找不到 $name（或写法不是一行 #hex）")
                assertEquals("$dir 的 $name 带 alpha（#${hex}）：withAlpha 会把它拉回不透明", 6, hex.length)
            }
        }
    }

    private fun itemNames(file: File): Set<String> =
        Regex("<item name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun colorNames(file: File): Set<String> =
        Regex("<color name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    /** 单测的 working dir 是模块目录（app/），但仍容忍从仓库根运行的情况 */
    private fun resFile(relative: String): File =
        listOf(File("src/main/res/$relative"), File("app/src/main/res/$relative"))
            .firstOrNull { it.isFile }
            ?: error("找不到资源文件: $relative")
}
