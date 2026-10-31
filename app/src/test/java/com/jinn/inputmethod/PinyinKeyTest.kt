package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键面绘制护栏（第 64 轮）。
 *
 * [PinyinKey] 是 `View` 子类，JVM 里实例化不了（构造时就会 `context.getColor`），
 * 单测只能覆盖两块：
 *  1. **纯逻辑**：提示文本拆行（已提成顶层 `splitHintLines`）；
 *  2. **接线不变式**：布局后补算字号、每个视觉属性的 setter 必须重绘、绘制路径不许重建列表 ——
 *     这几条在 JVM 里没有渲染可断言，只能钉源码形态（形态钉在用例名里写明）。
 *
 * 键面本身（圆角 / 内缩 / 透明度 / 大写字母 / 提示小字）靠真机截图核对，不在这里。
 */
class PinyinKeyTest {

    // ── 纯逻辑：提示文本拆行（疑点 3）─────────────────────────

    @Test
    fun `提示拆行_空串与纯空白行都被丢弃`() {
        assertEquals(emptyList<String>(), splitHintLines(""))
        // 单行全空白 / 多行全空白：一个字都不该留下（否则会画出一条看不见的行、白占高度）
        assertEquals(emptyList<String>(), splitHintLines("   "))
        assertEquals(emptyList<String>(), splitHintLines("\n"))
        assertEquals(emptyList<String>(), splitHintLines("  \n\t\n "))
        // 只有一个换行符时 split 得到两个空串，两者都被滤掉
        assertEquals(emptyList<String>(), splitHintLines("\n\n"))
    }

    @Test
    fun `提示拆行_去首尾空白且保留行序`() {
        assertEquals(listOf("uai", "ing"), splitHintLines("uai\ning"))
        assertEquals(listOf("uai", "ing"), splitHintLines("  uai \n\ting\n "))
        // 中间的空行被丢掉，前后行序不动（行序错 = 韵母提示张冠李戴）
        assertEquals(listOf("a", "b"), splitHintLines("a\n\n\nb"))
        // 行内空白原样保留：`ia ua` 是同一行的两个韵母（行内以空格分隔）
        assertEquals(listOf("ia ua"), splitHintLines(" ia ua "))
    }

    @Test
    fun `提示拆行_CRLF 的换行符残渣被收掉`() {
        // 只按 \n 拆，`\r` 留在行尾；trim() 必须把它一起去掉，否则会画成一个看不见的方块
        assertEquals(listOf("a", "b"), splitHintLines("a\r\nb"))
        assertEquals(listOf("a", "b"), splitHintLines("a\r\nb\r\n"))
    }

    @Test
    fun `提示拆行_不换行空格与全角空格都不算内容`() {
        // NBSP（U+00A0）与全角空格（U+3000）在 Kotlin 的 isWhitespace 里都算空白：
        // 判空与去空白两处都覆盖它们，才不会留下一行「看着是空的」提示。
        assertEquals(emptyList<String>(), splitHintLines("\u00A0"))
        assertEquals(emptyList<String>(), splitHintLines("\u3000"))
        assertEquals(listOf("a"), splitHintLines("\u00A0a\u3000"))
        // 两者混在两个真实行之间：空行丢掉、真实行按序留下
        assertEquals(listOf("uai", "ing"), splitHintLines("uai\n\u00A0\n\u3000\ning"))
    }

    @Test
    fun `提示拆行_不截断行数`() {
        // 本函数**不做**行数上限（静默丢行等于把韵母藏起来）：上限由上游 finalHint 保证 ≤2 行，
        // 见下一条用例。这里只钉「给几行就是几行」。
        assertEquals(listOf("a", "b", "c"), splitHintLines("a\nb\nc"))
    }

    @Test
    fun `双拼键面提示含红色声母后不超过两行_任何方案任何键`() {
        // 绘制端按 total 行垂直均分下半区（PinyinKey.onDraw）；三行会画到字母上。
        // 上限由 finalHint 的规则 3 保证：承担 zh/ch/sh 的键把韵母并成一行，红色行占第二行。
        var checked = 0
        // 分号键也是 [PinyinKey]（`keySemicolon`），提示同样走 `finalHint(SEMICOLON_KEY)`，
        // 漏掉它就等于漏掉一个会画提示行的键。常量在 `PinyinKeyboardView` 的 **private** companion 里
        // 取不到，这里写死字符 + 一行源码漂移钉（改了常量本用例自己会红）。
        val semicolonKey = ';'
        assertTrue(
            "分号键常量漂移了（本用例写死 ';'，见 PinyinKeyboardView.SEMICOLON_KEY）",
            "const val SEMICOLON_KEY = ';'" in TestSources.codeSource("PinyinKeyboardView.kt"),
        )
        for (scheme in ShuangpinScheme.SHUANGPIN_ONLY) {
            val table = scheme.table ?: error("${scheme.shortName} 缺键位表")
            for (c in ('a'..'z').toList() + semicolonKey) {
                val normal = splitHintLines(table.finalHint(c))
                val red = table.initialOf(c)?.takeIf { it.length > 1 }.orEmpty()
                val redLines = splitHintLines(red)
                val total = normal.size + redLines.size
                assertTrue(
                    "${scheme.shortName} 的 $c 键提示共 $total 行（>2 会压到字母上）：$normal + $redLines",
                    total <= 2,
                )
                assertEquals("${scheme.shortName} 的 $c 键红色提示必须是单行", minOf(1, redLines.size), redLines.size)
                // 有红色声母行时韵母最多一行（否则两行韵母 + 一行红 = 三行）；
                // 也可以是 0 行 —— 该键的韵母被规则 1 全滤掉时（自然码 i 键只有红 ch，无韵母可示）
                if (redLines.isNotEmpty()) {
                    assertTrue(
                        "${scheme.shortName} 的 $c 键有红色声母时韵母最多留一行",
                        normal.size <= 1,
                    )
                }
                checked++
            }
        }
        assertTrue("没扫到 8 套方案 × 27 键（表为空？）: $checked", checked >= 8 * 27)
    }

    // ── 接线不变式（形态钉）──────────────────────────────────

    /**
     * 布局完成后必须按**真实键宽**再算一次键面字号（第 64 轮修）。
     *
     * 首次刷新发生在新视图 `configure()` 里（还没测量，`keyViews['q'].width == 0`）⇒ 下发的
     * `PinyinKey.uniformMeasureWidth` 是 0 ⇒ 字母按**各键自宽**收字号。触顶判据是
     * 「前进宽 > 0.8 ×（键宽 − 间隙）」：本机悬浮 80% 宽（键宽 112px）要渲染键高 > 208px
     * 才触顶（折设置页约 65dp；停靠全宽约 74dp），现档 52dp 远未到；拉到 90dp 时 W 只剩
     * 约 0.7 倍、同排另有 4 个宽字母（Q/O/U/P）也收。而 `refreshKeyLabels` 打字过程中不调 ⇒ 那次结论
     * 会**贯穿整场会话**，直到切一次层。原先只有符号层在 `onLayout` 里重算，字母层一直停在
     * 这份首帧结论上。
     */
    @Test
    fun `布局完成后必须按真实键宽补算键面字号`() {
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        val body = TestSources.blockAfter(src, "override fun onLayout(")
        assertTrue("取到的 onLayout 体太短（锚点变了？）: ${body.length}", body.length > 80)
        assertTrue("onLayout 必须按基准键宽是否变化决定重算：\n$body", "labelBaseWidth" in body)
        assertFalse(
            "不得只剩符号层重算（字母层会停在「按各键自宽收字号」的首帧结论上）：\n$body",
            "(changed && layer == LAYER_SYMBOL) refreshKeyLabels()" in body,
        )
        // 标记必须在刷新里写回，否则每轮布局都重算（或永远不重算）
        assertTrue(
            "refreshKeyLabels 必须写回基准键宽标记",
            "labelBaseWidth = (keyViews['q']?.width ?: 0).toFloat()" in src,
        )
    }

    /**
     * 每个视觉属性的 setter 都必须 `invalidate()`。
     *
     * 本类给 `label` 的 KDoc 定下了这条不变式（「别让它成为『改了不刷新』的例外」），
     * 实际却有属性漏了（`fullPinyinStyle` 此前是裸字段）：它靠同一循环里紧跟着的
     * `label = …` 兜底重绘，一旦有人单独改这一个属性，键面就停在旧样式上。
     */
    @Test
    fun `视觉属性的 setter 都必须重绘`() {
        val src = TestSources.codeSource("PinyinKey.kt")
        val visualProps = listOf(
            "label", "centeredStyle", "uniformMeasureText", "uniformMeasureWidth",
            "subLabel", "subLabelRed", "glyphSizeDeltaPx", "fullPinyinStyle",
        )
        for (name in visualProps) {
            val at = src.indexOf("var $name:")
            assertTrue("PinyinKey 里找不到视觉属性 $name", at >= 0)
            val setterAt = src.indexOf("set(value)", at)
            assertTrue("$name 没有自定义 setter（改了不重绘）", setterAt > at)
            // 先确认「marker 之后、第一个花括号之前」是本属性自己的 set(value)：setter 被整个删掉时，
            // blockAfter 会取到**下一个**属性的 setter 体，缺重绘的属性就永远绿（本轮实测过这条假绿：
            // 只判 `"set(value)" in between` 会被下一个属性的 setter 头部满足）
            val firstBrace = src.indexOf("{", at)
            val between = if (firstBrace > at) src.substring(at, firstBrace) else ""
            assertTrue(
                "$name 的声明后面不是它自己的 set(value)（改成裸字段了？）：\n$between",
                "set(value)" in between &&
                    Regex("""(?m)^\s*var \w+:""").findAll(between).count() == 1,
            )
            // 按花括号配平取 setter 体：不按固定长度截窗 —— 截窗会把**下一个**属性的
            // invalidate() 也算进来（本轮实测过这个假绿）
            val body = TestSources.blockAfter(src, "var $name:")
            assertTrue("$name 的 setter 里缺 invalidate()：\n$body", "invalidate()" in body)
        }
        // 上面是**白名单**，只覆盖写进列表的 8 个属性：本轮变异实测 —— 加一个裸字段
        // `var fakeVisualProp: Boolean = false`，这里一条都不会红。所以再补一层**闭集**判据：
        // 文件里每个属性声明都必须落在「视觉属性（要有 setter + 重绘）」或「非视觉属性（写明由谁负责）」
        // 之一，多一个、少一个都红 —— 逼着后来者做一次决定，而不是悄悄漏掉。
        //
        // 口径：属性声明缩进 ≤ 8 空格（本文件成员属性在 4~8），方法体里的局部变量缩进 ≥ 12。
        // 修饰符可省（公开属性没有），**注解前缀与 `lateinit` 必须穿透** —— 只写
        // `^ {0,8}(private)?var` 时，`@Suppress("x") var a` 与 `lateinit var a` 会整条溜过去
        // （本轮变异实证：两种写法都全绿）。私有 `val` 也要计入：一套「只认 var」的闭集
        // 换个 `private val` 就又是假绿。
        val memberVar = Regex(
            """(?m)^ {0,8}(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:private|internal|protected|public)\s+)?""" +
                """(?:lateinit\s+)?var (\w+)\s*[:=]""",
        ).findAll(src).map { it.groupValues[1] }.toSet()
        val memberVal = Regex(
            """(?m)^ {0,8}(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:private|internal|protected|public)\s+""" +
                """(?:lateinit\s+)?val (\w+)\s*[:=]""",
        ).findAll(src).map { it.groupValues[1] }.toSet()
        // 只读成员（Paint / FontMetrics / 构造期取到的颜色快照 / 复用矩形）：不由外部改，
        // 所以没有 setter 可漏；但多出一个也必须先在这里登记一次。
        val passive = setOf(
            "keyPaint", "textPaint", "subPaint", "glyphFm", "subFm",
            "colorKey", "colorKeyPressed", "colorText", "colorCorner",
            "faceRect", "strokePaint", "rect",
        )
        val nonVisual = mapOf(
            "visual" to "applySkin",
            "faceColor" to "setFaceAlpha",
            "faceColorPressed" to "setFaceAlpha",
            "faceAlpha" to "setFaceAlpha",
            "cornerPx" to "setKeyAppearance",
            "insetPx" to "setKeyAppearance",
            "skinShader" to "渐变缓存（onDraw 内按四元组判据复用）",
            "shaderFace" to "渐变缓存",
            "shaderFace2" to "渐变缓存",
            "shaderAngle" to "渐变缓存",
            "shaderW" to "渐变缓存",
            "shaderH" to "渐变缓存",
            "pressed" to "setPressedVisual",
            "cachedNormalLines" to "subLabel 的派生缓存",
            "cachedRedLines" to "subLabelRed 的派生缓存",
            "onActivate" to "无障碍回调",
            "canActivate" to "无障碍判据",
        )
        assertEquals(
            "PinyinKey 的属性清单变了：新视觉属性要补 setter + 进上面的白名单；" +
                "新的非视觉属性要在这里写明「由谁负责刷新」。口径：属性缩进 ≤8 空格（局部变量 ≥12）",
            visualProps.toSet() + nonVisual.keys + passive,
            memberVar + memberVal,
        )
        // 伴生对象是闭集的另一个漏口：`private companion object { var x = 0 }` 缩进 12，
        // 上面两条正则都取不到（本轮变异实证：全绿）。那里只许放 `const val` 标量常量。
        val companion = TestSources.blockAfter(src, "private companion object {")
        val companionDecls = companion.lines().map { it.trim() }
            .filter { it.isNotEmpty() && it != "{" && it != "}" }
        for (line in companionDecls) {
            assertTrue("伴生对象只放 const 常量（可变状态会绕开属性闭集）：$line", line.startsWith("const val "))
        }
    }

    /**
     * 提示拆行只在 setter 里做一次，绘制路径只读缓存（MEM-21）。
     *
     * 原先 `onDraw` 每次重绘都为每个键重建两个 List 与若干临时 String
     * （26 键 × 每帧），被缓存到 `cachedNormalLines` / `cachedRedLines`。
     */
    @Test
    fun `绘制路径不得重建提示行列表`() {
        val src = TestSources.codeSource("PinyinKey.kt")
        val body = TestSources.blockAfter(src, "override fun onDraw(canvas: Canvas)")
        assertTrue("取到的 onDraw 体太短（锚点变了？）: ${body.length}", body.length > 800)
        assertFalse("onDraw 不得再调用 splitHintLines", "splitHintLines(" in body)
        assertTrue("onDraw 必须只读缓存列表", "cachedNormalLines" in body && "cachedRedLines" in body)
        // 缓存只在两个 setter 里更新（写点集中，泄漏一处就是「改了提示不刷新」）
        assertEquals(
            "splitHintLines 的调用点必须只有两个 setter",
            2,
            Regex("splitHintLines\\(").findAll(src).count() - 1, // 减去顶层函数自身的声明
        )
    }
}
