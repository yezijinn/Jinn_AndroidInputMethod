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
        // 上限从源码取（BUG-11）：原先写死 36，常量改成 8 也全绿 —— 守的其实是「按上限算列数」这件事
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        val cap = Regex("""const val MAX_RENDERED_CANDIDATES\s*=\s*(\d+)""").find(src)?.groupValues?.get(1)?.toInt()
        assertTrue("没解析出 MAX_RENDERED_CANDIDATES（写法变了？）", cap != null)
        assertTrue("渲染路径必须按该常量截断候选", "take(MAX_RENDERED_CANDIDATES)" in src)
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
        // 单行 = 拼音条 + 一排候选（拼音与候选之间不留间隔，用户 2026-09-25 要求「间距缩小为 0」）
        assertEquals(44, CandidateRows.heightDp(CandidateRows.SINGLE, sp))
        assertEquals(72, CandidateRows.heightDp(CandidateRows.DOUBLE, sp))
        // 默认档：候选 20sp、拼音 14sp（20 × 0.7，字号两档统一的历史口径）
        assertEquals(20f, sp, 0f)
        assertEquals(14f, CandidateText.pinyinSpOf(sp), 0f)
        // 拼音条比候选行矮：只给字形、不给行框余量（默认字体下两者 px 高度见 `默认字体下行高与拼音条就是紧凑档`）
        assertTrue(CandidateRows.pinyinBarHeightDp(sp) < CandidateRows.rowHeightDp(sp))
        // 候选行高就是字号的行框本身（1.4 系数），不留额外留白
        assertEquals(28f, CandidateRows.rowHeightDp(sp), 0.01f)
        // 字号是栏高的自变量：调大跟着涨、调小跟着收（这是本参数的意义所在）
        assertTrue(CandidateRows.heightDp(CandidateRows.SINGLE, 28f) > CandidateRows.heightDp(CandidateRows.SINGLE, sp))
        assertTrue(CandidateRows.heightDp(CandidateRows.SINGLE, 14f) < CandidateRows.heightDp(CandidateRows.SINGLE, sp))
        // 复用块（功能面板 / 符号标签）的最小高度取单行档整栏高度，同样跟着字号走
        assertEquals(CandidateRows.heightDp(CandidateRows.SINGLE, sp), CandidateRows.singleBarHeightDp(sp))
    }

    @Test
    fun 栏高恒等于拼音条加档位排数() {
        val d = 3f
        val sp = CandidateText.DEFAULT_SP
        val perRow = CandidateRows.rowHeightPx(d, fontScale = 1f, candidateSp = sp)
        val bar = CandidateRows.pinyinBarHeightPx(d, fontScale = 1f, candidateSp = sp)
        assertEquals(bar + perRow, CandidateRows.barHeightPx(CandidateRows.SINGLE, d, 1f, sp))
        assertEquals(bar + perRow * 2, CandidateRows.barHeightPx(CandidateRows.DOUBLE, d, 1f, sp))
    }

    @Test
    fun 默认字体下行高与拼音条就是紧凑档() {
        // density=3 / 20sp ⇒ 候选行框 84px；拼音 14sp ⇒ 字形 48px
        assertEquals(84, CandidateRows.rowHeightPx(density = 3f, fontScale = 1f, candidateSp = 20f))
        assertEquals(48, CandidateRows.pinyinBarHeightPx(density = 3f, fontScale = 1f, candidateSp = 20f))
        // 拼音条必须比候选行矮（只给字形不给行框），否则「拼音行比自己的字高出一圈」
        assertTrue(CandidateRows.pinyinBarHeightDp(20f) < CandidateRows.rowHeightDp(20f))
    }

    @Test
    fun 系统字体放大时行高与拼音条随之长高不裁字() {
        val normalRow = CandidateRows.rowHeightPx(3f, fontScale = 1f, candidateSp = 20f)
        val largeRow = CandidateRows.rowHeightPx(3f, fontScale = 1.5f, candidateSp = 20f)
        assertTrue("字体放大后行高必须变高，否则固定 28dp 会裁掉文字下半", largeRow > normalRow)
        assertTrue("行高至少要盖得住文字本身", largeRow >= 90)

        val normalBar = CandidateRows.pinyinBarHeightPx(3f, fontScale = 1f, candidateSp = 20f)
        val largeBar = CandidateRows.pinyinBarHeightPx(3f, fontScale = 1.5f, candidateSp = 20f)
        assertTrue("字体放大后拼音条必须变高，否则字形会被裁", largeBar > normalBar)
        assertTrue("拼音条至少要盖得住字形", largeBar >= 63)
    }

    @Test
    fun 字体缩小也不低于紧凑档() {
        assertEquals("行高不得低于 28dp", 84, CandidateRows.rowHeightPx(3f, fontScale = 0.85f, candidateSp = 20f))
        assertEquals("拼音条不得低于 16dp", 48, CandidateRows.pinyinBarHeightPx(3f, fontScale = 0.85f, candidateSp = 20f))
    }

    @Test
    fun 越界行数落回单行档() {
        // Prefs 已做归一，这里守的是兜底：任何非 2 的值都不得算成双行
        val sp = CandidateText.DEFAULT_SP
        assertEquals(44, CandidateRows.heightDp(0, sp))
        assertEquals(44, CandidateRows.heightDp(3, sp))
        assertEquals(44, CandidateRows.heightDp(-1, sp))
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
        val xml = listOf(
            File("src/main/res/layout/keyboard_pinyin.xml"),
            File("app/src/main/res/layout/keyboard_pinyin.xml"),
        ).firstOrNull { it.isFile } ?: error("找不到 keyboard_pinyin.xml")

        val text = xml.readText()
        fun attr(id: String, name: String): String {
            val block = text.substringAfter("""android:id="@+id/$id"""").substringBefore("/>")
            return Regex("""android:$name="([^"]+)"""").find(block)?.groupValues?.get(1)
                ?: error("$id 上找不到 android:$name")
        }

        // XML 里写的是**默认参数档**的几何：首帧（代码落位之前）与刷新后长得一样。
        // 尺寸真源在 CandidateRows（按用户字号派生），改了默认字号这里会一起红。
        assertEquals(
            "${CandidateRows.heightDp(CandidateRows.SINGLE, CandidateText.DEFAULT_SP)}dp",
            attr("candidate_bar", "layout_height"),
        )
        assertEquals(
            "${CandidateRows.pinyinBarHeightDp(CandidateText.DEFAULT_SP).roundToInt()}dp",
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

        // FrameLayout 里后加的子项绘制 / 触摸都在上层：拼音条（内含 ✕）必须排在候选滚动区**之后**，
        // 否则铺满整栏的候选区会把 ✕ 挡到点不动（2026-09-25 真机复现的 BUG，别为无障碍顺序再调回去）。
        assertTrue(
            "pinyin_bar 必须排在 candidate_scroll 之后（否则 ✕ 被候选区挡住点击）",
            text.indexOf("@+id/pinyin_bar") > text.indexOf("@+id/candidate_scroll"),
        )
    }

    /**
     * 键盘顶边与字母区都不得有 `paddingTop`（源码守卫）。
     *
     * 2026-09-25 真机逐像素扫过：键盘最顶那 4dp（density 3 下 12px）是背板色 plate，往下才是候选栏色
     * surface —— 用户打字时看到「候选栏上方一条色带」。两处 `paddingTop` 都已删除（根布局的由项目早期
     * 引入、字母区的跟着一起收掉），加回来就会重现。
     *
     * 只查这两处：底部功能行的 `paddingTop` / `paddingBottom` 是 56dp 行内的垂直留白，属正常设计。
     */
    @Test
    fun 键盘顶边与字母区不得有内边距() {
        val xml = listOf(
            File("src/main/res/layout/keyboard_pinyin.xml"),
            File("app/src/main/res/layout/keyboard_pinyin.xml"),
        ).firstOrNull { it.isFile } ?: error("找不到 keyboard_pinyin.xml")
        val text = xml.readText()

        val rootTag = text.substringAfter("<LinearLayout").substringBefore(">")
        assertFalse(
            "根布局不得有 android:paddingTop（候选栏底色要铺到键盘最顶，否则顶边露一条背板色）: $rootTag",
            rootTag.contains("paddingTop"),
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
}
