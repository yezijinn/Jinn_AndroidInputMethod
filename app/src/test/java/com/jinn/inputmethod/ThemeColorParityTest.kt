package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两套色板必须逐名对齐（`values/` 亮白 vs `values-night/` 暗黑）。
 *
 * 少写一个名字不会编译报错，也不会崩：该令牌在另一套主题下静默退回默认目录的值，
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
     * `values-night/themes.xml` 的同名 style 是整条替换（不是逐项合并）：少写一个 item，
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
     * 键盘外观页的滑杆标题保持「两字、不带范围」的极简版式（2026-09-23 定）。
     *
     * 改版前这里守的是「透明度标题的范围与 [KeyTransparency] 定义域一致」，前提是标题本身
     * 写着 `0% ~ 100%`；版式改版后标题不再表达范围（由拖动条自身表达），改为守住新的版式要求，
     * 防止有人把范围描述写回标题。定义域本身的守卫仍在 `KeyTransparencyTest` / `KeyAppearanceTest`。
     */
    @Test
    fun 外观页滑杆标题保持两字且不带范围() {
        val xml = resFile("values/strings.xml").readText()
        for ((name, expected) in listOf(
            "key_corner_title" to "圆角",
            "key_gap_title" to "间隙",
            "key_transparency_title" to "透明",
        )) {
            val title = Regex("<string name=\"$name\">(.*?)</string>")
                .find(xml)?.groupValues?.get(1)
                ?: error("找不到 $name")
            assertEquals("外观页标题应为「$expected」（极简版式，不带范围描述）", expected, title)
        }
    }

    /**
     * 参与透明度合成的色令牌必须不透明（6 位 `#RRGGBB`）。
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

    /**
     * 允许「明暗同值」的令牌 —— 每一条都必须写明它**为什么就该两档同值**。
     *
     * 不许随便加：两档同值在多数情况下是**只改了一侧**留下的痕迹（见下一条测试的说明）。
     */
    private val sameValueAllowed = setOf(
        // 键盘外观页：压在固定的七彩极光上（`@drawable/key_appearance_bg` 两档都不变），改档反而与底不搭
        "key_appearance_fg",
        // 麦克风录音态：底盘两档都是深红（亮 4.7:1 / 暗 3.9:1），白图标在两档都成立
        "mic_glyph_active",
    )

    /**
     * 「明暗同值」的令牌必须逐条登记；反过来，白名单里也不许留已经**不再**同值的名字。
     *
     * 为什么值得一条测试：绝大多数色令牌都该随主题变，两边写成同一个值通常是漏改一侧 ——
     * 而它的**症状只出现在一档里**，另一档完全正常，肉眼与既有守卫都发现不了。
     * 2026-10-02 实证（台账 L-475）：`mic_glyph` 两档都是 `#FFFFFF`，暗色档压深底盘（10~13:1）完全正常，
     * 亮色档却要压近白的 `mic_idle` / `mic_idle_center` ⇒ 麦克风图标只有 1.2~1.4:1（需 ≥3:1），
     * 亮色下基本看不见，而当时的守卫（色板逐名对齐 / item 逐名对齐）全绿。
     */
    @Test
    fun 明暗同值的令牌必须逐条登记() {
        val light = colorValues(resFile("values/colors.xml"))
        val dark = colorValues(resFile("values-night/colors.xml"))
        // 防呆：解析失败会让下面的差集恒空而**假绿**
        assertTrue("亮白色板解析异常（正则或路径写错）: ${light.size}", light.size > 30)
        assertTrue("暗黑色板解析异常（正则或路径写错）: ${dark.size}", dark.size > 30)
        val same = light.filter { (name, v) -> dark[name]?.equals(v, ignoreCase = true) == true }
            .keys.toSet()
        assertEquals(
            "这些令牌两档同值：有意的请登记到 sameValueAllowed 并写明理由，" +
                "漏改一侧的请补上另一档（只在一档失效的令牌没有守卫能发现）",
            emptySet<String>(), same - sameValueAllowed,
        )
        assertEquals(
            "白名单里这些令牌已经不是同值了（说明已过期，请删掉）",
            emptySet<String>(), sameValueAllowed - same,
        )
    }

    /**
     * 每个设置页都必须在 `attachBaseContext` 里换掉 Context 的 uiMode，否则那页不跟随明暗模式。
     *
     * `@color/` 令牌的明暗靠资源限定符（`values/` vs `values-night/`），而限定符是按 **Context 的
     * uiMode** 解析的：只有把「跟随系统 / 亮白 / 暗黑 / 定时」这一决策灌进 Context，页面才会照做。
     * 漏掉这一处**不会有任何编译错误或运行报错** —— 该页只是按系统深浅走，于是「强制亮白」的手机
     * （系统本身是深色）上它整页仍是暗色，与相邻页面割裂。
     *
     * 2026-10-02 实证：`TranslationSourceActivity` 是十个页面里唯一漏掉的（台账 L-392），而当时
     * 全仓对 `themedContext` / `attachBaseContext` **零覆盖**、没有任何守卫能发现它 ——
     * 这条测试就是 L-392 的「修法」里要求的那个守卫（先撤掉覆写跑一次必须红，已实证）。
     *
     * ⚠ `KeyAppearanceActivity`（键盘外观页）**不是**例外，别拿「那页配色是固定的」当删掉它的理由：
     * 固定的是它的背景（`@drawable/key_appearance_bg` 七彩极光，画在窗口底色上）与压在极光上的
     * 标题色（`@color/key_appearance_fg`），而页面里的卡片与卡片文字仍走主题色。
     */
    @Test
    fun 每个设置页都必须应用明暗主题() {
        val missing = activitySources().filter { (_, code) ->
            val body = functionBody(code, "override fun attachBaseContext(")
            body == null || "ThemeManager.themedContext(" !in body
        }.map { it.first }
        assertEquals(
            "这些页面没在 attachBaseContext 里调 ThemeManager.themedContext：不跟随明暗模式",
            emptyList<String>(), missing,
        )
    }

    /**
     * 每个设置页都必须接入**同一套**定时换色：`onStart` 里 `themeTicker.start()`、`onStop` 里 `themeTicker.stop()`。
     *
     * 「到点」这件事与界面在不在前台无关 —— 定时模式下页面停在屏幕上就会跨过切换点，不到点对表的话
     * 它就停在旧配色上，用户看到的是「同一套设置，有的页面跟随、有的不跟随」。
     * 2026-10-02 实证（台账 L-476）：`SettingsActivity` / `KeyAppearanceActivity` / `JinnIme`
     * **各写了一份**定时器，而其余 **8 个页面干脆没有**；收归 `ThemeManager.ScheduledThemeTicker`
     * 一份之后，这条守卫钉住接入方式，免得将来新加的页面又漏。
     *
     * 断言取**函数体**（大括号配平）而不是「文件里出现过」：后者太松 —— 把 `start()` 写在 `onCreate` 里
     * （后台回来不补表、`onStop` 也撤不掉）照样能过。
     */
    @Test
    fun 每个设置页都必须接入定时换色() {
        val missing = activitySources().filter { (_, code) ->
            val startBody = functionBody(code, "override fun onStart(")
            val stopBody = functionBody(code, "override fun onStop(")
            "ThemeManager.scheduledRebuildTicker(this)" !in code ||
                startBody == null || "themeTicker.start()" !in startBody ||
                stopBody == null || "themeTicker.stop()" !in stopBody
        }.map { it.first }
        assertEquals(
            "这些页面没按统一方式接入定时换色（ThemeManager.scheduledRebuildTicker + onStart/onStop）：" +
                "定时模式下它们不会到点换色",
            emptyList<String>(), missing,
        )
    }

    /**
     * 全部页面源码（文件名 → 剥注释后的代码），供两条「页面必须…」的守卫共用。
     *
     * 应用了**剥注释**（[TestSources.codeOf]，BUG.md L-116）：注释里提到 `themedContext` / `themeTicker`
     * 不算实现 —— 否则把代码删掉、把名字留在注释里就能让守卫保持全绿。
     */
    private fun activitySources(): List<Pair<String, String>> {
        val dir = listOf(
            File("src/main/java/com/jinn/inputmethod"),
            File("app/src/main/java/com/jinn/inputmethod"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到主源码目录（cwd=${File("").absolutePath}）")
        val pages = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith("Activity.kt") }
            .sortedBy { it.name }
        // 防呆：路径写错时下面会因为「一个页面都没扫到」而假绿
        assertTrue("扫到的页面数异常（路径可能写错）: ${pages.size}", pages.size >= 10)
        return pages.map { it.name to TestSources.codeOf(it.readText()) }
    }

    /**
     * 取 `signature` 那个函数的**大括号配平**函数体（不含外层花括号）；找不到返回 null。
     *
     * 用配平而不是「取后面 N 个字符」：后者会顺手把**下一个**函数里的 `themedContext` 也算进来，
     * 于是「覆写体里其实没换 Context」这种半吊子写法能过闸。
     */
    private fun functionBody(code: String, signature: String): String? {
        val start = code.indexOf(signature)
        if (start < 0) return null
        val open = code.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private fun itemNames(file: File): Set<String> =
        Regex("<item name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun colorNames(file: File): Set<String> =
        Regex("<color name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    /** 令牌名 → 色值（只认一行式 `<color name="x">#hex</color>`，与 参与合成的色令牌必须不透明 同一口径） */
    private fun colorValues(file: File): Map<String, String> =
        Regex("<color name=\"([^\"]+)\">\\s*(#[0-9A-Fa-f]+)\\s*</color>")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** 单测的 working dir 是模块目录（app/），但仍容忍从仓库根运行的情况 */
    private fun resFile(relative: String): File =
        listOf(File("src/main/res/$relative"), File("app/src/main/res/$relative"))
            .firstOrNull { it.isFile }
            ?: error("找不到资源文件: $relative")
}
