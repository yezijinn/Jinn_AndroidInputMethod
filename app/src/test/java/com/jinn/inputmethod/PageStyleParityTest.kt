package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 页面样式必须**统一**（用户 2026-10-02：「页面的样式风格配色应该统一 / 完全一致 / 不各自独立 ——
 * 包括按钮、X 关闭；每种类型都统一用同类型的 UI」，见 `BUG.md` L-477）。
 *
 * 为什么要有它：同一件事此前在 9 个页面各写一份，而且常常互不相同 ——
 * 关闭按钮的键面文字有 `✕`（`strings.xml`）/ `"X"`（5 个页面的 `TEXT_CLOSE` 常量）/ `"关闭"`
 * （原文范围页）三种；说明文字 11sp / 12sp；按钮高度 40dp / 44dp；单行输入框 40dp / 44dp /
 * `minHeight 44dp` 三种；三个翻译页没有卡片分组。这些**只在真机上肉眼可见**，
 * 编译、lint、既有守卫全都不会红。
 *
 * 视觉规格现在集中在 `values/styles.xml`（`PageTitle` / `PageDesc` / `PageCloseButton` /
 * `SettingsCard` / `SettingsCardRow` / `SectionTitle` / `SettingsField` / `SettingsFieldMultiline`），
 * 文案的**唯一**定义在 `PageChrome`。这条守卫的做法是**反向钉**：
 * 页面里不许再出现「自己那一套」（自带尺寸/字号、直接引用卡片与输入框的 drawable）。
 *
 * ⚠ 键盘配色皮肤页（`activity_key_appearance.xml`）按用户要求**保持独立**，不在本类的检查范围。
 */
class PageStyleParityTest {

    private val pages = listOf(
        "activity_favorite_symbols.xml",
        "activity_fuzzy_pinyin.xml",
        "activity_openai_settings.xml",
        "activity_rare_chars.xml",
        "activity_symbol_order.xml",
        "activity_settings.xml",
        "activity_translation_settings.xml",
        "activity_translation_source.xml",
    )

    @Test
    fun 关闭按钮必须用统一样式且不再自写尺寸文案() {
        val bad = ArrayList<String>()
        for (name in pages) {
            val xml = layout(name)
            val ids = Regex("""@\+id/(\w*_close)""").findAll(xml).map { it.groupValues[1] }.toList()
            for (id in ids) {
                val el = elementById(xml, id)
                if (!el.contains("style=\"@style/PageCloseButton\"")) {
                    bad += "$name 的 $id 没用 @style/PageCloseButton"
                    continue
                }
                // 尺寸与文案都只在样式 / PageChrome 里定义，按钮上再写一遍就是又分叉了
                val extra = Regex("""android:(textSize|layout_width|layout_height|minWidth|padding\w*|text|contentDescription)=""")
                if (extra.containsMatchIn(el)) {
                    bad += "$name 的 $id 自写了尺寸或文案：${el.replace(Regex("\\s+"), " ").take(160)}"
                }
            }
        }
        assertEquals("关闭按钮必须与其它页面完全一致（样式 + 文案都只有一处定义）", emptyList<String>(), bad)
    }

    @Test
    fun 页面标题与说明必须用统一样式() {
        val bad = ArrayList<String>()
        for (name in pages) {
            val xml = layout(name)
            if (Regex("""@\+id/\w*_title""").containsMatchIn(xml) &&
                !xml.contains("style=\"@style/PageTitle\"")
            ) {
                bad += "$name 有标题控件但没用 @style/PageTitle"
            }
            // 只查**页面级**说明（直接挂在根下的那个）：卡片内还有一堆 `text_*_desc`（主题说明、
            // 开关说明…），它们是「卡片内说明」这一另一个类型，不该拿页面说明的样式去套
            for (id in pageLevelIds(xml, "_desc")) {
                if (!elementById(xml, id).contains("style=\"@style/PageDesc\"")) {
                    bad += "$name 的页面说明 $id 没用 @style/PageDesc"
                }
            }
        }
        assertEquals("标题/说明的字号与颜色只在样式里定义", emptyList<String>(), bad)
    }

    @Test
    fun 输入控件必须用共享字段样式() {
        val bad = ArrayList<String>()
        for (name in pages) {
            val xml = stripComments(layout(name))
            for (el in elementsOf(xml, "EditText") + elementsOf(xml, "Spinner")) {
                if (!el.contains("""style="@style/SettingsField""")) {
                    bad += "$name 里有个输入控件没用 SettingsField：${el.replace(Regex("\\s+"), " ").take(140)}"
                }
            }
            // 反向：输入框背景只在样式里引用（页面直接写 drawable 就等于又分叉了）
            if ("@drawable/field_aurora" in xml) bad += "$name 直接引用了 @drawable/field_aurora（应走样式）"
        }
        assertEquals("单行框 / 下拉的高度与内边距只有一处定义", emptyList<String>(), bad)
    }

    @Test
    fun 卡片外观只有一处定义且次按钮高度交回样式() {
        val bad = ArrayList<String>()
        for (name in pages) {
            val xml = stripComments(layout(name))
            if ("@drawable/card_aurora" in xml) bad += "$name 直接引用了 @drawable/card_aurora（应走 SettingsCard）"
            for (el in elementsOf(xml, "Button")) {
                if (el.contains("style=\"@style/SettingsButton\"") &&
                    Regex("""android:layout_height="\d+dp"""").containsMatchIn(el)
                ) {
                    bad += "$name 的次按钮又写了一遍高度：${el.replace(Regex("\\s+"), " ").take(140)}"
                }
            }
        }
        assertEquals("卡片与次按钮的规格只在 styles.xml 里定义", emptyList<String>(), bad)
    }

    @Test
    fun 页面根容器必须用统一样式() {
        val bad = pages.filter { !layout(it).contains("style=\"@style/PageRoot\"") }
        assertEquals("页面根必须用 @style/PageRoot（底色 / 系统栏留位 / 内边距只有一处定义）",
            emptyList<String>(), bad)
    }

    /**
     * 页面根**底部不许留白**（用户 2026-10-02 报的真机现象：设置页唤起键盘时，候选栏正上方
     * 有一条纯黑色（暗色）/ 纯白色（亮色）色带）。
     *
     * 成因：设置页的根就是 `ScrollView`，`clipToPadding` 默认 true ⇒ 根部 `paddingBottom`
     * 是**裁剪区**，滚动内容画不进去，键盘弹出时可见区最下 8dp 永远只画极光底色端点色
     * （暗色 `#0B1020` / 亮色 `#FBFCFE`），看着像键盘顶部多出来的一条。
     * 它对功能毫无影响，编译 / lint / 既有守卫全都不会红，只能反向钉住。
     */
    @Test
    fun 页面根底部不得留白() {
        val styles = res("values/styles.xml")
        val root = styles.substringAfter("<style name=\"PageRoot\">").substringBefore("</style>")
        assertTrue("PageRoot 不许写整体 padding（会把底部一起带上）", "android:padding\"" !in root)
        assertTrue("PageRoot 不许写 paddingBottom（键盘弹出时会在候选栏上方露出底色带）",
            "paddingBottom" !in root)
        for (edge in listOf("paddingStart", "paddingEnd", "paddingTop")) {
            assertTrue("PageRoot 缺少 android:$edge=8dp", "<item name=\"android:$edge\">8dp</item>" in root)
        }

        val bad = ArrayList<String>()
        for (name in pages) {
            for (el in elementsOf(stripComments(layout(name)), "ScrollView")) {
                if (Regex("""android:padding(Bottom|=")""").containsMatchIn(el)) {
                    bad += "$name 的 ScrollView 自写了 padding：" + el.replace(Regex("\\s+"), " ").take(140)
                }
            }
        }
        assertEquals("ScrollView 页的底部留白必须为 0（否则键盘上方会露出一条底色带）",
            emptyList<String>(), bad)
    }

    @Test
    fun 卡片内提示小字必须用统一样式() {
        val bad = ArrayList<String>()
        for (name in pages) {
            val xml = stripComments(layout(name))
            for (el in elementsOf(xml, "TextView")) {
                if ("style=\"@style/" in el) continue
                if ("android:textSize=\"11sp\"" in el && "android:textColor=\"@color/text_secondary\"" in el) {
                    bad += "$name 有个提示小字没用 @style/CardHint：${el.replace(Regex("\\s+"), " ").take(120)}"
                }
            }
        }
        assertEquals("卡片内的提示小字只在 CardHint 里定义（原先 11sp / 12sp 两种并存）",
            emptyList<String>(), bad)
    }

    /**
     * 这五个页面的列表项是**代码造**的（`LinearLayout(this)` + 手写内边距 / 背景），
     * XML 侧的统一管不到它们 —— 原先四页的行根本没有外框，词库页自造
     * `surface_hi + 10dp` 圆角。外观现在只在 `PageStyle` 里定义。
     */
    @Test
    fun 代码生成的列表项必须用共享卡片() {
        val bad = ArrayList<String>()
        for (name in listOf("RareCharsActivity.kt", "FuzzyPinyinActivity.kt", "SymbolOrderActivity.kt",
                            "FavoriteSymbolsActivity.kt", "DictManagerActivity.kt")) {
            val code = TestSources.codeOf(TestSources.rawSourceOfShortName(name))
            if ("PageStyle.addCard(" !in code && "PageStyle.dressAsCard(" !in code) {
                bad += "$name 的列表项没有走 PageStyle 的共享卡片"
            }
            if ("R.color.surface_hi" in code) bad += "$name 仍自造卡片外观（surface_hi + 手写圆角）"
        }
        assertEquals("代码生成的列表项必须与 XML 侧的 SettingsCard 同款", emptyList<String>(), bad)
    }

    /**
     * 行间距必须保持紧凑（用户 2026-10-02：「不同行之间的行间距尽量接近 0，成为紧凑视图；
     * 尽量一页展示完，而不是让用户上下滑动去观看」）。
     *
     * 反向钉：卡片间距与页面元素的纵向 margin 都不许再涨回两位数 —— 这类值一涨就回到「要滑半天」，
     * 而它对功能毫无影响，人工 review 最容易放过。
     */
    @Test
    fun 行间距必须保持紧凑() {
        val bad = ArrayList<String>()
        val styles = res("values/styles.xml")
        val card = styles.substringAfter("<style name=\"SettingsCard\"").substringBefore("</style>")
        // ⚠ 样式表里是 `<item name="…">2dp</item>` 形式，不是 XML 属性形式
        val gap = Regex("<item name=\"android:layout_marginBottom\">(\\d+)dp</item>")
            .find(card)?.groupValues?.get(1)?.toInt()
        if (gap == null || gap > 2) bad += "SettingsCard 的卡片间距应为 ≤2dp（当前 $gap）"

        val pageStyle = TestSources.codeOf(TestSources.rawSourceOfShortName("PageStyle.kt"))
        val codeGap = Regex("CARD_GAP_DP = (\\d+)").find(pageStyle)?.groupValues?.get(1)?.toInt()
        if (codeGap == null || codeGap > 2) bad += "PageStyle 的卡片间距应为 ≤2dp（当前 $codeGap）"

        val margin = Regex("android:layout_margin(Top|Bottom)=\"(\\d+)dp\"")
        for (name in pages) {
            for (m in margin.findAll(layout(name))) {
                if (m.groupValues[2].toInt() > 6) bad += "$name 里有 ${m.value}（纵向间距上限 6dp）"
            }
        }
        assertEquals("行间距必须保持紧凑（用户要求：尽量接近 0、一页展示完）", emptyList<String>(), bad)
    }

    @Test
    fun 关闭文案只有一处定义() {
        // 反向钉：这几样都是统一前的「各页自己那一套」，不许再出现
        // ⚠ 只钉 `@string/…` 引用形态：元素 id（`btn_favorite_close`）里本来就含这个词，
        // 按裸词判会把 id 一起算进来（第一版就是这么误报的）
        val legacy = listOf("TEXT_CLOSE", "@string/favorite_close", "@string/symbol_order_close")
        val found = ArrayList<String>()
        for (name in pages) {
            val xml = layout(name)
            for (token in legacy) if (token in xml) found += "$name 引用了 $token"
        }
        for (token in listOf("TEXT_CLOSE")) {
            for (page in listOf("FavoriteSymbolsActivity.kt", "FuzzyPinyinActivity.kt",
                                "OpenAiSettingsActivity.kt", "RareCharsActivity.kt",
                                "SymbolOrderActivity.kt", "TranslationSettingsActivity.kt",
                                "TranslationSourceActivity.kt")) {
                if (token in TestSources.codeOf(TestSources.rawSourceOfShortName(page))) {
                    found += "$page 仍留着 $token"
                }
            }
        }
        assertEquals("关闭按钮的键面文字与可听名只允许在 PageChrome 里定义", emptyList<String>(), found)
    }

    // ---------------- 助手 ----------------

    private fun stripComments(xml: String): String = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /** 取某个标签名开头的全部元素原文（到该元素闭合为止） */
    private fun elementsOf(xml: String, tag: String): List<String> {
        val out = ArrayList<String>()
        var i = xml.indexOf("<$tag")
        while (i >= 0) {
            val end = Regex("""(/>|>)""").find(xml, i) ?: break
            out += xml.substring(i, end.range.last + 1)
            i = xml.indexOf("<$tag", end.range.last)
        }
        return out
    }

    /**
     * **页面级**控件：直接挂在根布局下的子元素（本仓布局缩进规范：根 0、其子 4、属性再 +4）。
     *
     * 用缩进而不是「文件里出现了 id」来区分层级：卡片内部也有大量 `*_desc` / `*_title` 命名，
     * 它们是**另一个类型**（卡片内说明），不该被页面级样式的要求扫到。
     */
    private fun pageLevelIds(xml: String, suffix: String): List<String> {
        val lines = xml.replace("\r\n", "\n").split("\n")
        val out = ArrayList<String>()
        for (i in lines.indices) {
            if (!lines[i].trimEnd().matches(Regex(""" {4}<(TextView|Button|Spinner|EditText)$"""))) continue
            val end = (i + 1 until lines.size).firstOrNull {
                lines[it].trimEnd().endsWith("/>") || lines[it].trimEnd().endsWith(">")
            } ?: continue
            val block = lines.subList(i, end + 1).joinToString("\n")
            Regex("""@\+id/(\w*$suffix)""").find(block)?.let { out += it.groupValues[1] }
        }
        return out
    }

    /**
     * 下拉框（Spinner）必须用共享的项布局。
     *
     * 统一前 9 处适配器全用 `android.R.layout.simple_spinner_item` /
     * `simple_spinner_dropdown_item`：文字的字号与内边距由**系统** textAppearance 决定，
     * 与同页字段（`@style/SettingsField`：14sp / 12dp / text_primary）不同 ——
     * 一个页面两种字号就是「各自独立」；下拉项的按压反馈与触摸高度此前也不由我们控制。
     *
     * 规格只在 `item_spinner.xml` / `item_spinner_dropdown.xml` 两处定义，折叠态必须与字段同字号。
     */
    @Test
    fun 下拉框必须用共享的项布局() {
        val bad = ArrayList<String>()
        var folded = 0
        var dropped = 0
        for (file in kotlinSources()) {
            val text = file.readText()
            if ("android.R.layout.simple_spinner" in text) {
                bad += "${file.name} 仍在用系统默认的下拉项布局"
            }
            folded += Regex("""R\.layout\.item_spinner\b""").findAll(text).count()
            dropped += Regex("""R\.layout\.item_spinner_dropdown""").findAll(text).count()
        }
        assertEquals("每一处适配器都要同时设置折叠态与展开项（只设一半就是新的分叉）", folded, dropped)
        assertTrue("全仓至少要有一处下拉框（否则本守卫形同虚设）", folded > 0)

        // 折叠态与字段同字号：两处各写一个字面量可以，但不能只有一边改
        val styles = res("values/styles.xml")
        val fieldSp = Regex(
            "<style name=\"SettingsField\">.*?<item name=\"android:textSize\">([\\d.]+sp)</item>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(styles)?.groupValues?.get(1) ?: error("styles.xml 里找不到 SettingsField 的 textSize")
        val itemSp = Regex("android:textSize=\"([\\d.]+sp)\"")
            .find(res("layout/item_spinner.xml"))?.groupValues?.get(1)
        assertEquals("折叠态下拉项要与输入框同字号（SettingsField）", fieldSp, itemSp)

        assertEquals("下拉框必须用共享的项布局，不许再各自一套", emptyList<String>(), bad)
    }

    /**
     * 页面布局的属性顺序与冗余属性（2026-10-02 全库统一为 `style → id → layout_* → 其余`）：
     *
     * ① `style` 必须在 `android:id` 之前 —— 属性顺序不影响渲染，但两种写法混用会让 diff 噪音大、
     *    review 时要额外辨认「这一行是不是新属性」；全库那次重排改了 69 处。
     * ② 不写 `android:singleLine="false"` —— 它等于默认值（且该属性本身已 deprecated），全库已清。
     */
    @Test
    fun 页面属性顺序与冗余属性() {
        val bad = ArrayList<String>()
        for (name in pages + "activity_key_appearance.xml") {
            val xml = layout(name)
            val lines = xml.lines()
            for (i in 1 until lines.size) {
                if (lines[i].trimStart().startsWith("style=") &&
                    lines[i - 1].trimStart().startsWith("android:id=")
                ) {
                    bad += "$name:${i + 1} 的 style 出现在 android:id 之后"
                }
            }
            if ("android:singleLine=\"false\"" in xml) {
                bad += "$name 里还有 android:singleLine=\"false\"（默认值，不必写）"
            }
        }
        assertEquals("属性顺序必须统一（style 在 id 之前），且不写等于默认值的冗余属性", emptyList<String>(), bad)
    }

    /**
     * 页面里的粗体标题必须走样式（`PageHeroTitle` / `SettingsFieldTitle` / `PageTitle` / `SettingsButtonPrimary`）。
     *
     * 反向钉：除键盘皮肤页外，`activity_*.xml` 里**不许再手写 `android:textStyle="bold"`**。
     * 此前首页品牌标题（22sp）与 4 个双列栏标题（13sp）各自把「字号 + 颜色 + 粗体」写在布局里 ——
     * 值恰好相同，但**没有单一出处**：改一处另外几处不会跟着变，就是「各自一套」的种子。
     */
    @Test
    fun 页面粗体标题必须走样式() {
        val bad = ArrayList<String>()
        for (name in pages) {
            val n = Regex("android:textStyle=\"bold\"").findAll(layout(name)).count()
            if (n > 0) {
                bad += "$name 里还有 $n 处手写 android:textStyle=\"bold\"（应改用 PageHeroTitle / SettingsFieldTitle 等样式）"
            }
        }
        assertEquals("页面粗体标题必须走样式，不许手写", emptyList<String>(), bad)

        // 两个新样式必须存在、且在页面里真的被用上（防「样式留着、页面又手写回去」）
        val styles = res("values/styles.xml")
        for (s in listOf("PageHeroTitle", "SettingsFieldTitle")) {
            assertTrue("styles.xml 缺少 $s", "<style name=\"$s\">" in styles)
            assertTrue("$s 定义了但没有任何页面在用", pages.any { "style=\"@style/$s\"" in layout(it) })
        }
    }

    /** Kotlin 主源码（反向钉「页面代码里不许再自己那一套」） */
    private fun kotlinSources(): List<File> =
        listOf(
            File("src/main/java/com/jinn/inputmethod"),
            File("app/src/main/java/com/jinn/inputmethod"),
        ).firstOrNull { it.isDirectory }?.listFiles { f -> f.extension == "kt" }?.toList().orEmpty()

    /** 取 `id` 那个元素的原文本 */
    private fun elementById(xml: String, id: String): String {
        val at = xml.indexOf("""android:id="@+id/$id"""")
        assertTrue("布局里找不到 $id", at >= 0)
        val open = xml.lastIndexOf('<', at)
        val end = Regex("""(/>|>)""").find(xml, at) ?: error("元素未闭合：$id")
        return xml.substring(open, end.range.last + 1)
    }

    /** 读资源文件（与 [layout] 同款的双前缀容忍） */
    private fun res(relative: String): String =
        listOf(File("src/main/res/$relative"), File("app/src/main/res/$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到资源：$relative（cwd=${File("").absolutePath}）")

    private fun layout(name: String): String =
        listOf(File("src/main/res/layout/$name"), File("app/src/main/res/layout/$name"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到布局：$name（cwd=${File("").absolutePath}）")
}
