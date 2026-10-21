package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * 候选栏双行排版（[CandidateRows]）：上排偶数项、下排奇数项，上下成列。
 *
 * 排布契约：下排恒为第 1/3/5… 个候选，即「离键盘最近的
 * 那排、最左一条」必须是候选列表首项 —— 空格 / 回车取的就是首项，两处若不一致，
 * 用户看到的第一个候选与按下空格上屏的词就不是同一个。
 *
 * 另一条不变量：双行时各列上排必须**同高占位**（缺项用 null 标记，渲染侧补空白），
 * 否则末列的下排会被父容器垂直居中拉到中线，与其它列错位。
 */
class CandidateRowsTest {

    @Test
    fun 空候选不出列() {
        assertTrue(CandidateRows.columnsOf(emptyList()).isEmpty())
    }

    @Test
    fun 单候选时上排缺位() {
        assertEquals(
            listOf<Pair<String?, String>>(null to "甲"),
            CandidateRows.columnsOf(listOf("甲")),
        )
    }

    @Test
    fun 两条候选同列_上偶下奇() {
        assertEquals(
            listOf<Pair<String?, String>>("乙" to "甲"),
            CandidateRows.columnsOf(listOf("甲", "乙")),
        )
    }

    @Test
    fun 奇数条时候末列只有下排() {
        val cols = CandidateRows.columnsOf(listOf("1", "2", "3", "4", "5"))
        assertEquals(3, cols.size)
        assertEquals(listOf("2", "4", null), cols.map { it.first })
        assertEquals(listOf("1", "3", "5"), cols.map { it.second })
    }

    @Test
    fun 渲染上限条数下无重无漏() {
        // 条数从源码取（BUG-11）：原先写死 36，常量改成 8 也全绿 —— 守的其实是「按窗口条数算列数」这件事
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        val cap = Regex("""const val INITIAL_RENDERED_CANDIDATES\s*=\s*(\d+)""").find(src)?.groupValues?.get(1)?.toInt()
        assertTrue("没解析出 INITIAL_RENDERED_CANDIDATES（写法变了？）", cap != null)
        assertTrue(
            "渲染路径必须按窗口条数截断候选（candidateRenderLimit）",
            "subList(0, candidateRenderLimit)" in src,
        )
        val items = (1..cap!!).map { it.toString() }
        val cols = CandidateRows.columnsOf(items)
        assertEquals((cap + 1) / 2, cols.size)
        assertEquals(items.filterIndexed { i, _ -> i % 2 == 1 }, cols.mapNotNull { it.first })
        assertEquals(items.filterIndexed { i, _ -> i % 2 == 0 }, cols.map { it.second })
        assertEquals(
            items.toSet(),
            (cols.mapNotNull { it.first } + cols.map { it.second }).toSet(),
        )
    }

    @Test
    fun 高度与字号按档位() {
        val sp = CandidateText.DEFAULT_SP
        // 单行 = 拼音条 + 一排候选（拼音与候选之间不留间隔，间距为 0）
        assertEquals(65, CandidateRows.heightDp(CandidateRows.SINGLE, sp))
        assertEquals(104, CandidateRows.heightDp(CandidateRows.DOUBLE, sp))
        // 默认档：候选 28sp、拼音 19.6sp（28 × 0.7）
        assertEquals(28f, sp, 0f)
        assertEquals(19.6f, CandidateText.pinyinSpOf(sp), 0.01f)
        // 拼音条比候选行矮：只给字形、不给行框余量（默认字体下两者 px 高度见 `默认字体下行高与拼音条就是紧凑档`）
        assertTrue(CandidateRows.pinyinBarHeightDp(CandidateText.pinyinSpOf(sp)) < CandidateRows.rowHeightDp(sp))
        // 候选行高就是字号的行框本身（1.4 系数），不留额外留白
        assertEquals(39.2f, CandidateRows.rowHeightDp(sp), 0.01f)
        // 字号是栏高的自变量：调大跟着涨、调小跟着收（这是本参数的意义所在）
        assertTrue(CandidateRows.heightDp(CandidateRows.SINGLE, 42f) > CandidateRows.heightDp(CandidateRows.SINGLE, sp))
        assertTrue(CandidateRows.heightDp(CandidateRows.SINGLE, 14f) < CandidateRows.heightDp(CandidateRows.SINGLE, sp))
        // 复用块（功能面板 / 符号标签）的最小高度取单行档整栏高度，同样跟着字号走
        assertEquals(CandidateRows.heightDp(CandidateRows.SINGLE, sp), CandidateRows.singleBarHeightDp(sp))
    }

    @Test
    fun 栏高恒等于拼音条加档位排数() {
        val d = 3f
        val sp = CandidateText.DEFAULT_SP
        val perRow = CandidateRows.rowHeightPx(d, fontScale = 1f, candidateSp = sp)
        val bar = CandidateRows.pinyinBarHeightPx(d, fontScale = 1f, pinyinSp = CandidateText.pinyinSpOf(sp))
        assertEquals(bar + perRow, CandidateRows.barHeightPx(CandidateRows.SINGLE, d, 1f, sp))
        assertEquals(bar + perRow * 2, CandidateRows.barHeightPx(CandidateRows.DOUBLE, d, 1f, sp))
        // 默认档具体 px（density 3）：拼音条 78 + 单排 117 = 195；双排 78 + 234 = 312
        assertEquals(195, CandidateRows.barHeightPx(CandidateRows.SINGLE, d, 1f, sp))
        assertEquals(312, CandidateRows.barHeightPx(CandidateRows.DOUBLE, d, 1f, sp))

        // 展开态栏高（2026-10-21）：向下覆盖键区，按**整行字母键**向上取整 ——
        // 栏底落在行缝上，不会把某一行键拦腰截断。默认档：四排网格 = 78 + 117×4 = 546，
        // 增量 351px 要盖到两行键（231×2 = 462）⇒ 栏高 195 + 462 = 657。
        val base = CandidateRows.barHeightPx(CandidateRows.SINGLE, d, 1f, sp)
        val keyRow = 231 // 66dp × 3.5（KeyAppearance.DEFAULT_KEY_HEIGHT_DP × density）
        val grid4 = bar + perRow * CandidateRows.MAX_EXPANDED_ROWS
        assertEquals(657, CandidateRows.expandedBarHeightPx(grid4, base, keyRow))
        // 增量不足一行也取整到一行：栏底同样落在行缝上
        assertEquals(base + keyRow, CandidateRows.expandedBarHeightPx(base + 1, base, keyRow))
        // 网格不比收起档高 ⇒ 栏高不变（展开两排但候选少时不能凭空长高）
        assertEquals(base, CandidateRows.expandedBarHeightPx(base, base, keyRow))
        // 覆盖行数有上限：再多也不许把整片键区盖光
        assertEquals(
            base + keyRow * CandidateRows.MAX_COVERED_KEY_ROWS,
            CandidateRows.expandedBarHeightPx(base + keyRow * 10, base, keyRow),
        )
        // 取不到键高（首帧 / 键高未下发）时退回网格高度，绝不返回比网格矮的值
        assertEquals(grid4, CandidateRows.expandedBarHeightPx(grid4, base, 0))
    }

    @Test
    fun 默认字体下行高与拼音条就是紧凑档() {
        // density=3 / 20sp ⇒ 候选行框 84px；拼音 14sp ⇒ 字形 54px（条高参数是**拼音字号**）
        assertEquals(84, CandidateRows.rowHeightPx(density = 3f, fontScale = 1f, candidateSp = 20f))
        assertEquals(54, CandidateRows.pinyinBarHeightPx(density = 3f, fontScale = 1f, pinyinSp = 14f))
        // 拼音条必须比候选行矮（只给字形不给行框），否则「拼音行比自己的字高出一圈」
        assertTrue(CandidateRows.pinyinBarHeightDp(14f) < CandidateRows.rowHeightDp(20f))
    }

    @Test
    fun 系统字体放大时行高与拼音条随之长高不裁字() {
        val normalRow = CandidateRows.rowHeightPx(3f, fontScale = 1f, candidateSp = 20f)
        val largeRow = CandidateRows.rowHeightPx(3f, fontScale = 1.5f, candidateSp = 20f)
        assertTrue("字体放大后行高必须变高，否则固定 28dp 会裁掉文字下半", largeRow > normalRow)
        assertTrue("行高至少要盖得住文字本身", largeRow >= 90)

        val normalBar = CandidateRows.pinyinBarHeightPx(3f, fontScale = 1f, pinyinSp = 14f)
        val largeBar = CandidateRows.pinyinBarHeightPx(3f, fontScale = 1.5f, pinyinSp = 14f)
        assertTrue("字体放大后拼音条必须变高，否则字形会被裁", largeBar > normalBar)
        assertTrue("拼音条至少要盖得住字形", largeBar >= 63)
    }

    @Test
    fun 字体缩小也不低于紧凑档() {
        assertEquals("行高不得低于 28dp", 84, CandidateRows.rowHeightPx(3f, fontScale = 0.85f, candidateSp = 20f))
        assertEquals("拼音条不得低于 18dp", 54, CandidateRows.pinyinBarHeightPx(3f, fontScale = 0.85f, pinyinSp = 14f))
    }

    /** 拼音字号是独立参数：它变则栏高变，与候选字号无关 */
    @Test
    fun 拼音字号改变栏高() {
        val big = CandidateRows.barHeightPx(
            CandidateRows.SINGLE, 3f, 1f, 20f, 28f, 0f,
        )
        val base = CandidateRows.barHeightPx(
            CandidateRows.SINGLE, 3f, 1f, 20f, 14f, 0f,
        )
        assertTrue("拼音字号调到 28sp 后栏高必须变高（$big vs $base）", big > base)
    }

    /** 拼音行间隔计入栏高：8dp × density 3 = 24px 的净增量 */
    @Test
    fun 拼音行间隔计入栏高() {
        val withGap = CandidateRows.barHeightPx(
            CandidateRows.SINGLE, 3f, 1f, 20f, 14f, 8f,
        )
        val noGap = CandidateRows.barHeightPx(
            CandidateRows.SINGLE, 3f, 1f, 20f, 14f, 0f,
        )
        assertEquals(24, withGap - noGap)
    }

    @Test
    fun 越界行数落回单行档() {
        // Prefs 已做归一，这里守的是兜底：任何非 2 的值都不得算成双行
        val sp = CandidateText.DEFAULT_SP
        assertEquals(65, CandidateRows.heightDp(0, sp))
        assertEquals(65, CandidateRows.heightDp(3, sp))
        assertEquals(65, CandidateRows.heightDp(-1, sp))
    }

    /**
     * `keyboard_pinyin.xml` 的原文（两条候选路径：单测工作目录是模块根或仓库根）。
     *
     * Android 侧跑不了 JVM 布局断言，涉及「XML 默认档 vs 代码常量」的对拍一律读原文核对
     * （同 `PrefsBackupCoverageTest` 的做法）。
     */
    private fun layoutText(): String {
        val xml = listOf(
            File("src/main/res/layout/keyboard_pinyin.xml"),
            File("app/src/main/res/layout/keyboard_pinyin.xml"),
        ).firstOrNull { it.isFile } ?: error("找不到 keyboard_pinyin.xml")
        return xml.readText()
    }

    /** 取某个 id 所在**起始标签**的属性值（自闭合标签按 `/>` 截窗，容器标签按下一个 `>`） */
    private fun layoutAttr(id: String, name: String): String {
        val text = layoutText()
        val block = TestSources.window(text, """android:id="@+id/$id"""", "/>")
        return Regex("""android:$name="([^"]+)"""").find(block)?.groupValues?.get(1)
            ?: error("$id 上找不到 android:$name")
    }

    /**
     * 取 `keyboard_letters` 的**子树**文本（按 `<LinearLayout` / `</LinearLayout>` 配对）。
     *
     * 不用 `TestSources.window(text, "@+id/keyboard_letters", "</LinearLayout>")`：那会在**第一行**
     * 结束处截断，行高断言只覆盖一行，漏改另外两行也照样绿（判据静默放宽，BUG.md L-1145 同型）。
     */
    private fun lettersSubtree(text: String): String {
        val idAt = text.indexOf("@+id/keyboard_letters")
        require(idAt > 0) { "布局里找不到 @+id/keyboard_letters" }
        val openEnd = text.indexOf('>', idAt)
        require(openEnd > idAt) { "keyboard_letters 标签未闭合" }
        var depth = 1
        var cursor = openEnd + 1
        while (depth > 0) {
            val nextOpen = text.indexOf("<LinearLayout", cursor)
            val nextClose = text.indexOf("</LinearLayout>", cursor)
            require(nextClose >= 0) { "keyboard_letters 的 </LinearLayout> 不配对" }
            if (nextOpen in 0 until nextClose) {
                depth++
                cursor = nextOpen + "<LinearLayout".length
            } else {
                depth--
                cursor = nextClose + "</LinearLayout>".length
            }
        }
        return text.substring(openEnd, cursor)
    }

    /**
     * 布局尺寸与代码常量对拍（源码守卫）。
     *
     * 栏高 / 拼音条高 / ✕ 按钮宽度 / 拼音条内边距在 XML 与 Kotlin 里各写一份：只改一边会让
     * 首帧与刷新后长得不一样（栏高）或点击热区与让位错位（✕ 宽度）。Android 侧跑不了 JVM
     * 布局断言，所以直接读 XML 文本核对（同 `PrefsBackupCoverageTest` 的做法）。
     */
    @Test
    fun 布局尺寸与代码常量逐项一致() {
        val text = layoutText()
        val attr = ::layoutAttr

        // XML 里写的是**默认参数档**的几何：首帧（代码落位之前）与刷新后长得一样。
        // 尺寸真源在 CandidateRows（按用户字号派生），改了默认字号这里会一起红。
        assertEquals(
            "${CandidateRows.heightDp(CandidateRows.SINGLE, CandidateText.DEFAULT_SP)}dp",
            attr("candidate_bar", "layout_height"),
        )
        assertEquals(
            "${CandidateRows.pinyinBarHeightDp(CandidateText.pinyinSpOf(CandidateText.DEFAULT_SP)).roundToInt()}dp",
            attr("pinyin_bar", "layout_height"),
        )
        assertEquals(
            "${CandidateRows.CLEAR_BUTTON_WIDTH_DP}dp",
            attr("btn_clear_candidates", "layout_width"),
        )
        // 拼音条右端给 ✕ 留位，必须与按钮宽度同值（否则按钮压字或提前留白）
        assertEquals(
            "${CandidateRows.CLEAR_BUTTON_WIDTH_DP}dp",
            attr("candidate_pinyin", "paddingEnd"),
        )
        assertEquals("${CandidateRows.SIDE_PAD_DP}dp", attr("pinyin_bar", "paddingStart"))
        assertEquals("${CandidateRows.SIDE_PAD_DP}dp", attr("pinyin_bar", "paddingEnd"))
        // 字号占位也要钉（BUG.md L-825）：拼音条与 ✕ 的 XML 值必须等于派生值 ——
        // 否则改了 CandidateText.PINYIN_RATIO 会静默漂移，首帧（代码落位前）与刷新后字号不一致
        val pinyinSp = "${CandidateText.pinyinSpOf(CandidateText.DEFAULT_SP).roundToInt()}sp"
        assertEquals(pinyinSp, attr("candidate_pinyin", "textSize"))
        assertEquals(pinyinSp, attr("btn_clear_candidates", "textSize"))

        // 字母行行高：三个行容器是 XML 里唯一写死「键高」的地方，必须等于 KeyAppearance 的默认键高。
        // 真源在代码（applyLetterRowHeight 逐帧按 prefs.keyHeightDp 落位），XML 只是默认档占位 ——
        // 但默认值从 54dp 改成 66dp 时这三行漏改过一次，占位就与真源分叉了。
        // 判据落在 keyboard_letters **子树**内：数字行（代码里建）与底部功能行的行高不在这个子树里，
        // 将来别处出现同值 66dp 也不会误红。
        assertEquals(
            "字母区三行容器行高必须等于 KeyAppearance.DEFAULT_KEY_HEIGHT_DP",
            3,
            Regex("""android:layout_height="${KeyAppearance.DEFAULT_KEY_HEIGHT_DP.roundToInt()}dp"""")
                .findAll(lettersSubtree(text)).count(),
        )

        // FrameLayout 里后加的子项绘制 / 触摸都在上层：拼音条（内含 ✕）必须排在候选滚动区**之后**，
        // 否则铺满整栏的候选区会把 ✕ 挡到点不动（2026-09-25 真机复现的 BUG，别为无障碍顺序再调回去）。
        // 先各自取下标并断言非负：indexOf 未命中返回 -1，只写「indexOf(A) > indexOf(B)」
        // 在 B 缺失时是 x > -1 ⇒ 恒真（假绿）
        val pinyinBarAt = text.indexOf("@+id/pinyin_bar")
        val scrollAt = text.indexOf("@+id/candidate_scroll")
        assertTrue("布局里必须有 @+id/pinyin_bar", pinyinBarAt >= 0)
        assertTrue("布局里必须有 @+id/candidate_scroll", scrollAt >= 0)
        assertTrue(
            "pinyin_bar 必须排在 candidate_scroll 之后（否则 ✕ 被候选区挡住点击）",
            pinyinBarAt > scrollAt,
        )
    }

    /**
     * 拼音文字必须为**可见**的 ▲ 让出宽度（源码 + XML 双向守卫）。
     *
     * 事实（真机 1440×3200 / density 3.5）：✕ 占右端 40dp（140px，文字到 1272），▲ 后加、紧挨在
     * ✕ 左侧（盒 1132..1272）⇒ 只给 ✕ 预留时，长拼音串的尾部与省略号画进 ▲ 盒，被三角字形压住
     * （文字暗像素实测 1239..1245，正落在盒内）。修法是「▲ 可见时文字再退一格」。
     *
     * 为什么钉在 [updateExpandButton] 一处：它是 ▲ 显隐的唯一判据。让位散成第二个写者时，
     * 迟早出现「某个形态多让 40dp（拼音条莫名早省略）/ 少让 40dp（又压字）」而无人发现 —— 这里
     * 三面一起钉：XML 的两格几何、默认档的预留值、以及代码里唯一的写入点与它的算式。
     */
    @Test
    fun 拼音文字必须为可见的展开按钮让出宽度() {
        // 两格几何：▲ 的宽 = 它自己那格；marginEnd 跳过的是 ✕ 那格（不是 ▲ 那格 —— 两者只是
        // 当前恰好同值，各钉各自的语义才不会在「✕ 改宽」时错位）
        assertEquals(
            "${CandidateRows.EXPAND_BUTTON_WIDTH_DP}dp",
            layoutAttr("btn_expand_candidates", "layout_width"),
        )
        assertEquals(
            "${CandidateRows.CLEAR_BUTTON_WIDTH_DP}dp",
            layoutAttr("btn_expand_candidates", "layout_marginEnd"),
        )
        // 默认档（▲ 隐藏）只给 ✕ 让位；代码在 ▲ 出现时补上第二格 ⇒ 首帧与刷新后同值，不抖
        assertEquals(
            "${CandidateRows.CLEAR_BUTTON_WIDTH_DP}dp",
            layoutAttr("candidate_pinyin", "paddingEnd"),
        )

        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        // 写法钉：默认档 + 可选第二格（跨行改写也算，只要算式还是这条）
        assertTrue(
            "▲ 可见时拼音文字须退到「✕ + ▲」两格之后",
            Regex(
                """dpExact\(\s*CandidateRows\.CLEAR_BUTTON_WIDTH_DP \+\s*""" +
                    """if \(show\) CandidateRows\.EXPAND_BUTTON_WIDTH_DP else 0,?\s*\)""",
            ).containsMatchIn(src),
        )
        assertTrue(
            "仅在 paddingEnd 真的变化时才 setPaddingRelative（否则每次按键都多一次 requestLayout）",
            "if (viewCandidatePinyin.paddingEnd != pinyinEndPadPx)" in src,
        )
        // 唯一写者：本文件里 viewCandidatePinyin 的内边距只允许一处写入
        assertEquals(
            "拼音文字内边距只允许一个写者（updateExpandButton）",
            1,
            Regex("""viewCandidatePinyin\.setPaddingRelative""").findAll(src).count(),
        )
    }

    /**
     * 键盘顶边与字母区都不得有 `paddingTop`（源码守卫）。
     *
     * 2026-09-25 真机逐像素扫过：键盘最顶那 4dp（density 3 下 12px）是背板色 plate，往下才是候选栏色
     * surface —— 用户打字时看到「候选栏上方一条色带」。两处 `paddingTop` 都已删除（根布局的由项目早期
     * 引入、字母区的跟着一起收掉），加回来就会重现。
     *
     * 根在 2026-10-21 由 LinearLayout 改为 FrameLayout（候选栏改成叠在键区之上），
     * 背板仍在根上 ⇒ 根与键区容器（keyboard_body）都不许有内边距：根有则顶边露背板色，
     * 键区有则字母区整体下移、把候选栏的下缘露出来。
     *
     * 只查这几处：底部功能行的 `paddingTop` / `paddingBottom` 是 56dp 行内的垂直留白，属正常设计。
     */
    @Test
    fun 键盘顶边与字母区不得有内边距() {
        val xml = listOf(
            File("src/main/res/layout/keyboard_pinyin.xml"),
            File("app/src/main/res/layout/keyboard_pinyin.xml"),
        ).firstOrNull { it.isFile } ?: error("找不到 keyboard_pinyin.xml")
        val text = xml.readText()

        val rootTag = TestSources.window(text, "<FrameLayout", ">")
        assertFalse(
            "根布局不得有 android:paddingTop（候选栏底色要铺到键盘最顶，否则顶边露一条背板色）: $rootTag",
            rootTag.contains("paddingTop"),
        )
        val bodyTag = TestSources.window(text, """android:id="@+id/keyboard_body"""", ">")
        assertFalse(
            "键区容器不得有 android:paddingTop（字母区顶边要直接接候选栏底边）: $bodyTag",
            bodyTag.contains("paddingTop"),
        )

        val lettersAt = text.indexOf("@+id/keyboard_letters")
        assertTrue("布局里应能找到 keyboard_letters", lettersAt > 0)
        val lettersTag = text.substring(text.lastIndexOf('<', lettersAt), text.indexOf('>', lettersAt))
        assertFalse(
            "字母区不得有 android:paddingTop（第一行键的顶边直接接候选栏底边）: $lettersTag",
            lettersTag.contains("paddingTop"),
        )
    }

    /**
     * 候选字号是**用户参数**（[CandidateText]）、几何是**单一真源**（[CandidateRows] 派生）—— 源码守卫。
     *
     * 反向钉：渲染侧不许再出现写死的候选 / 拼音字号，也不许自己算行高。改造前的形态正是那样
     * （20sp 与 28dp 各写一份）：字号调到 14sp 时栏高不缩（白改），调到 28sp 时栏高不涨（裁字）。
     *
     * ⚠ 本守卫**只管候选与拼音两处字号**：候选栏里另有各自语义的固定档（功能面板主/次行 13f/9f、
     * 符号分组主文本与页码 13f/9f、密码数字条 `PASSWORD_DIGIT_TEXT_SP` = 18f、引擎内联提示 15f），
     * 它们的**高度**跟着候选字号走（`reuseBlockMinPx`）、字号各自独立 —— 属有意设计，见 BUG.md X-322。
     */
    @Test
    fun 渲染侧不得写死候选字号与行高() {
        val code = TestSources.codeOf(TestSources.rawSourceOfShortName("PinyinKeyboardView.kt"))
        assertFalse("候选字号不得写死（应走 Prefs.candidateTextSp）", "CANDIDATE_TEXT_SP" in code)
        assertFalse("拼音字号不得写死（应走 CandidateText.pinyinSpOf）", "PINYIN_TEXT_SP" in code)
        assertTrue("行高必须走 CandidateRows.rowHeightPx", "CandidateRows.rowHeightPx(" in code)
        assertTrue("栏高必须走 applyCandidateRows", "applyCandidateRows(" in code)
        assertTrue("复用块最小高度必须跟着当前字号（reuseBlockMinPx）", "reuseBlockMinPx" in code)
        // 指纹必须覆盖系统字体缩放（BUG.md L-824）：双行档两排行高是写死的 px（由 fontScale 派生），
        // 漏掉它时 fontScale 变了而候选没变 ⇒ 命中旧指纹、保留旧行高，与已重算的栏高错配（放大即裁字）
        assertTrue("renderKey 必须把 fontScale 算进去（L-824）", "fontScaleToken * 23" in code)
    }

    // 展开态（BUG.md L-1393，作者 2026-10-17 指定）

    @Test
    fun 展开态排数按候选量与上限四排夹取() {
        assertEquals(4, CandidateRows.MAX_EXPANDED_ROWS)
        assertEquals(1, CandidateRows.visibleRows(1, perColumn = 1))
        assertEquals(3, CandidateRows.visibleRows(3, perColumn = 1))
        assertEquals(4, CandidateRows.visibleRows(4, perColumn = 1))
        assertEquals(4, CandidateRows.visibleRows(9, perColumn = 1))
        assertEquals(3, CandidateRows.visibleRows(9, perColumn = 3))
        assertEquals(4, CandidateRows.visibleRows(10, perColumn = 3))
        assertEquals(4, CandidateRows.visibleRows(13, perColumn = 3))
        assertEquals(1, CandidateRows.visibleRows(2, perColumn = 3))
        assertEquals(2, CandidateRows.visibleRows(4, perColumn = 3))
        assertEquals(2, CandidateRows.visibleRows(20, perColumn = 3, maxRowsByHeight = 2))
        assertEquals(1, CandidateRows.visibleRows(20, perColumn = 3, maxRowsByHeight = 1))
        assertEquals(1, CandidateRows.visibleRows(0, perColumn = 3))
    }

    /**
     * 不变式（P2 的机械守卫，补 G1）：**展开态栏高恒 ≥ 候选网格**，任何字号 / 系统字体缩放 /
     * 密度 / 键高 / 拼音行间隔组合下都不裁字。
     *
     * 依据：`maxRowsByCoveredKeys` = `1 + floor(覆盖行数 × 键高 ÷ 行高)` ⇒ 网格高 ≤ 收起档栏高 +
     * 覆盖行数 × 键高，而 [CandidateRows.expandedBarHeightPx] 正好按整行键向上取整并夹在同一覆盖
     * 行数上 ⇒ `栏高 ≥ 网格` 代数上恒成立。改坏任何一侧（除法取整方向、覆盖行数、向上取整）当场红。
     *
     * 第 12 轮 P2（展开中调大字号裁末排）就是这条不变式在真机被破坏：排数来自旧字号 ⇒ 网格超栏高。
     */
    @Test
    fun 展开态栏高恒盖住网格_字号乘字体缩放乘密度乘键高() {
        for (candidateSp in 14..42) {
            for (fontScale in listOf(1f, 1.15f, 1.3f)) {
                for (density in listOf(2.625f, 3.5f)) {
                    for (keyHeightDp in listOf(40, 50, 66, 75, 90)) {
                        for (gapDp in listOf(0f, 8f, 16f)) {
                            val keyRow = (keyHeightDp * density).roundToInt()
                            val r = CandidateRows.rowHeightPx(density, fontScale, candidateSp.toFloat())
                            val p = CandidateRows.pinyinBarHeightPx(
                                density,
                                fontScale,
                                CandidateText.pinyinSpOf(candidateSp.toFloat()),
                            ) + CandidateRows.pinyinGapPx(density, gapDp)
                            val base = p + r
                            val rows = CandidateRows.maxRowsByCoveredKeys(r, keyRow)
                                .coerceAtMost(CandidateRows.MAX_EXPANDED_ROWS)
                            val grid = p + rows * r
                            val bar = CandidateRows.expandedBarHeightPx(grid, base, keyRow)
                            assertTrue(
                                "裁字：sp=$candidateSp fs=$fontScale d=$density 键高=${keyHeightDp}dp 间隔=$gapDp " +
                                    "rows=$rows grid=$grid bar=$bar",
                                bar >= grid,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun 覆盖行数换算与旧算式等价() {
        // 默认档：行高 28sp×1.4×3.5 = 137，键高 66dp×3.5 = 231 ⇒ 1 + 462/137 = 4
        assertEquals(4, CandidateRows.maxRowsByCoveredKeys(137, 231))
        // fontScale 1.3：行高 178 ⇒ 1 + 462/178 = 3（真机实测就是 3 排）
        assertEquals(3, CandidateRows.maxRowsByCoveredKeys(178, 231))
        // 几何未取到：退回上限（与旧实现的 else 分支同口径）
        assertEquals(CandidateRows.MAX_EXPANDED_ROWS, CandidateRows.maxRowsByCoveredKeys(0, 231))
        assertEquals(CandidateRows.MAX_EXPANDED_ROWS, CandidateRows.maxRowsByCoveredKeys(137, 0))
        // 键高极矮时至少给 1 排（不得算出 0 排把候选吞掉）
        assertEquals(1, CandidateRows.maxRowsByCoveredKeys(1000, 1))
    }

    @Test
    fun 一屏列数按栏宽整除本帧单条宽() {
        // 真机实测（1440×3200 / density 3.5 / 候选字距 12dp ⇒ 半字距 21px，`ou` 首条为单字）：
        // 14sp ⇒ 字宽 49 + 42 = 91 ⇒ 1440/91 = 15；42sp ⇒ 147 + 42 = 189 ⇒ 1440/189 = 7
        assertEquals(15, CandidateRows.columnsPerRow(1440, 91))
        assertEquals(7, CandidateRows.columnsPerRow(1440, 189))
        // 单条比栏宽还宽：仍算 1 列（不得算出 0 列把候选吞掉）
        assertEquals(1, CandidateRows.columnsPerRow(1440, 2000))
        assertEquals(1, CandidateRows.columnsPerRow(189, 189))
        // 几何未取到（首帧 / 空候选）：按 1，与「排数 = 候选数、仍被上限夹住」的旧口径一致
        assertEquals(1, CandidateRows.columnsPerRow(0, 91))
        assertEquals(1, CandidateRows.columnsPerRow(1440, 0))
        assertEquals(1, CandidateRows.columnsPerRow(-5, -9))
    }

    @Test
    fun 展开三角判据必须取本帧字号而不是已布局子视图宽度() {
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        // 排数换算只能走「本帧字号量字宽」的估计（L-1397）
        assertTrue(
            "expandedRowsFor 必须调 columnsPerRowForFrame(candidateSp, spacingHalfPx, sample)",
            "columnsPerRowForFrame(candidateSp, spacingHalfPx, sample)" in src,
        )
        assertFalse(
            "expandedRowsFor 不得再调 estimateColumnsPerRow（那读的是上一帧子视图宽度）",
            "val perColumn = estimateColumnsPerRow()" in src,
        )
        assertTrue(
            "本帧估计必须按字号量字宽（measureText + scaledDensity 与 setTextSize(SP) 同换算）",
            "candidateMeasurePaint.measureText(text)" in src &&
                "sizeSp * resources.displayMetrics.scaledDensity" in src,
        )
        // 分页预取仍走旧口径（单位是已布局的列宽，展开态是列容器）：调用点只留一个
        assertEquals(
            "estimateColumnsPerRow() 只应剩「定义 + 分页预取」两处",
            2,
            Regex("""estimateColumnsPerRow\(\)""").findAll(src).count(),
        )
        // 单条宽度的另一半（字距）本帧只读一次，渲染与判据共用
        assertFalse(
            "字距不得在渲染路径里再读一遍 Prefs（会与判据用到不同的值）",
            "dpFloat(prefs.candidateSpacingDp / 2f)" in src,
        )
        assertTrue("字距统一由 candidateSpacingHalfPx 派生", "candidateSpacingHalfPx(prefs.candidateSpacingDp)" in src)
    }

    @Test
    fun 展开态排数必须走覆盖边界纯函数() {
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        assertTrue(
            "展开排数必须走 CandidateRows.maxRowsByCoveredKeys（写回内联算式就没人守这条不变式了）",
            "CandidateRows.maxRowsByCoveredKeys(rowPx, letterRowPx())" in src,
        )
        assertFalse("不得再自己算 usable 再整除（代数等价但脱离了单测）", "val usable =" in src)
    }

    @Test
    fun 展开态按N排一列且列内自上而下保持词频顺序() {
        val items = listOf("a", "b", "c", "d", "e")
        val cols = CandidateRows.columnsOfN(items, 4)
        assertEquals(2, cols.size)
        assertEquals(listOf("a", "b", "c", "d"), cols[0])
        assertEquals(listOf("e", null, null, null), cols[1])
        // 排数被夹到 [1, 4]：传 0 ⇒ 按 1 排 ⇒ 每列 1 条 ⇒ 5 列
        assertEquals(5, CandidateRows.columnsOfN(items, 0).size)
        // 传超上限 ⇒ 按 4 排 ⇒ 5 条只需 2 列，首列自上而下是前 4 条
        val capped = CandidateRows.columnsOfN(items, 99)
        assertEquals(2, capped.size)
        assertEquals(listOf("a", "b", "c", "d"), capped[0])
        val grid = CandidateRows.columnsOfN((1..12).map { it.toString() }, 4)
        assertEquals(3, grid.size)
        assertEquals("1", grid[0][0])
        assertEquals("12", grid[2][3])
    }

    @Test
    fun 展开态拼音条必须置顶() {
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        assertTrue(
            "拼音居中只属于「双行档未展开」",
            "currentDoubleRow = !candidatesExpanded && rows == CandidateRows.DOUBLE" in src,
        )
        assertTrue("gravity 按形态判断而不是按行数", "val gravity = if (currentDoubleRow)" in src)
        assertTrue(
            "候选让位同理（展开两排也要给拼音条让出位置）",
            "pinyinBar.visibility == View.VISIBLE && !currentDoubleRow" in src,
        )
        assertTrue(
            "不得再按 rows == DOUBLE 判断（展开两排会撞上该常量）",
            "currentRows != CandidateRows.DOUBLE" !in src,
        )
    }

    @Test
    fun 展开态四处收起与只单行档显示() {
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        assertTrue("再点三角要能收起", "toggleCandidateExpanded()" in src)
        assertTrue(
            "选中候选要收起",
            Regex("""fun onCandidateSelected[\s\S]{0,200}collapseCandidates\(\)""").containsMatchIn(src),
        )
        assertTrue(
            "按 ✕ 要收起",
            Regex("""btnClearCandidates\.setOnClickListener[\s\S]{0,900}collapseCandidates\(\)""").containsMatchIn(src),
        )
        // 收起判据：只看输入串（纯函数），不得再回到「候选集指纹 + 输入串」的错位口径
        // （指纹在查询之前取，配的是上一次查询的列表 ⇒ 收起按奇偶漏判，2026-10-21 实测）
        assertTrue(
            "收起判据必须走纯函数 shouldCollapseExpanded",
            "CandidateRows.shouldCollapseExpanded(" in src,
        )
        assertFalse(
            "不得再有候选集指纹字段（会错位一帧导致收起按奇偶漏判）",
            "lastCandidatesToken" in src,
        )
        assertTrue("输入串每帧更新", "lastCandidatesInput = inputNow" in src)
        assertTrue(
            "无内容（会话结束）要收起",
            Regex("""input\.isEmpty\(\) && lastPredictions\.isEmpty\(\)[\s\S]{0,400}collapseCandidates(?:AndRelayout)?\(""")
                .containsMatchIn(src),
        )
        // 栏高唯一写入者：applyCandidateRows 直接写终值（0 帧，作者 2026-10-20）
        assertTrue(
            "栏高只在 applyCandidateRows 里写终值",
            "if (lp.height != barHeight) {" in src,
        )
        // 渲染态与意图态合一：收起只翻一个标志
        assertTrue(
            "收起无参、无补间",
            "private fun collapseCandidates() {" in src,
        )
        assertFalse("不得再有渲染态双轨", "candidateRenderExpanded" in src)
        // 容器默认 center_vertical；展开态必须顶对齐，否则内容半速滑、首帧显示网格中部。
        // 判据按「本帧是否按展开态渲染」而非「动画器是否存在」：toggle 是「先刷新、后建动画器」，
        // 按后者判会让补间起始帧落回 CENTER。
        assertTrue(
            "展开态候选容器必须顶对齐（按意图态判定）",
            "val wantGravity = if (candidatesExpanded)" in src &&
                "android.view.Gravity.TOP" in src,
        )
        assertTrue(
            "三角只在单行档出现",
            Regex("""rowsSetting == CandidateRows\.SINGLE && wouldGrow &&""").containsMatchIn(src),
        )
        assertTrue(
            "展开态横向恒跟手（翻页模式已移除，展开态没有第二种浏览方式）",
            "candidateScroll.expanded = candidatesExpanded" in src,
        )
        // 展开态绝不能走增量追加（BUG.md L-1394）：`appendCandidateViews` 只认逐条 / 两排一列，
        // 3/4 排的列会被拼成竖向错位的裸 TextView 块 —— 实测第 9 列「溢咦倚矣」整体下移。
        assertTrue(
            "展开态要整栏重建、不得增量追加",
            Regex("""if \(candidatesExpanded\) \{\s*val keepY = candidateRowsScroll\.scrollY""").containsMatchIn(src),
        )
        assertTrue(
            "展开态一次性放大渲染窗口",
            "candidateRenderLimit < target" in src,
        )
    }

    @Test
    fun 展开态是否收起只看输入串() {
        // 首次（无上一次输入串）：不得误收
        assertFalse(
            "首次无上一次输入串时不得收起",
            CandidateRows.shouldCollapseExpanded(null, "ji"),
        )
        // 同输入下的词库合并 / 学习重排：不得收起（作者 2026-10-17 指定语义）
        assertFalse(
            "输入没变时不得收起（词库合并 / 学习重排）",
            CandidateRows.shouldCollapseExpanded("ji", "ji"),
        )
        // 输字：输入变了 ⇒ 收起
        assertTrue(
            "输入变了必须收起展开态",
            CandidateRows.shouldCollapseExpanded("ji", "jin"),
        )
        // 选词上屏 / 清空：输入清空 ⇒ 收起
        assertTrue(
            "输入清空必须收起展开态",
            CandidateRows.shouldCollapseExpanded("ji", ""),
        )
    }
}
