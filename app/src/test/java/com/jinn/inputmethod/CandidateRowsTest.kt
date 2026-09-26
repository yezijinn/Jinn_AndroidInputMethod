package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        // 36 = PinyinKeyboardView.MAX_RENDERED_CANDIDATES，双行时正好 18 列
        val items = (1..36).map { it.toString() }
        val cols = CandidateRows.columnsOf(items)
        assertEquals(18, cols.size)
        assertEquals(items.filterIndexed { i, _ -> i % 2 == 1 }, cols.mapNotNull { it.first })
        assertEquals(items.filterIndexed { i, _ -> i % 2 == 0 }, cols.map { it.second })
        assertEquals(
            items.toSet(),
            (cols.mapNotNull { it.first } + cols.map { it.second }).toSet(),
        )
    }

    @Test
    fun 高度与字号按档位() {
        // 单行 = 拼音条 + 一排候选（拼音与候选之间不留间隔，用户 2026-09-25 要求「间距缩小为 0」）
        assertEquals(
            CandidateRows.PINYIN_BAR_HEIGHT_DP + CandidateRows.ROW_HEIGHT_DP,
            CandidateRows.heightDp(CandidateRows.SINGLE),
        )
        assertEquals(44, CandidateRows.heightDp(CandidateRows.SINGLE))
        assertEquals(
            CandidateRows.PINYIN_BAR_HEIGHT_DP + CandidateRows.ROW_HEIGHT_DP * 2,
            CandidateRows.heightDp(CandidateRows.DOUBLE),
        )
        assertEquals(72, CandidateRows.heightDp(CandidateRows.DOUBLE))
        // 字号两档统一（用户 2026-09-25 指定）：拼音 14sp / 汉字 20sp
        assertEquals(14f, CandidateRows.PINYIN_TEXT_SP, 0f)
        assertEquals(20f, CandidateRows.CANDIDATE_TEXT_SP, 0f)
        // 拼音条比候选行矮：只给字形、不给行框余量（默认字体下两者 px 高度见 `默认字体下行高与拼音条就是紧凑档`）
        assertTrue(CandidateRows.PINYIN_BAR_HEIGHT_DP < CandidateRows.ROW_HEIGHT_DP)
        // 候选行高就是 20sp 的行框本身（1.4 系数），不留额外留白
        assertEquals(28f, CandidateRows.CANDIDATE_TEXT_SP * 1.4f, 0.01f)
    }

    @Test
    fun 栏高恒等于拼音条加档位排数() {
        val d = 3f
        val cand = 60f // 20sp @ 默认字体
        val pin = 42f // 14sp @ 默认字体
        val perRow = CandidateRows.rowHeightPx(d, cand)
        val bar = CandidateRows.pinyinBarHeightPx(d, pin)
        assertEquals(bar + perRow, CandidateRows.barHeightPx(CandidateRows.SINGLE, d, cand, pin))
        assertEquals(bar + perRow * 2, CandidateRows.barHeightPx(CandidateRows.DOUBLE, d, cand, pin))
    }

    @Test
    fun 默认字体下行高与拼音条就是紧凑档() {
        // density=3 / 20sp ⇒ 候选文字 60px、行框 84px；14sp ⇒ 拼音 42px、字形 48px
        assertEquals(84, CandidateRows.rowHeightPx(density = 3f, textPx = 60f))
        assertEquals(48, CandidateRows.pinyinBarHeightPx(density = 3f, textPx = 42f))
        // 拼音条必须比候选行矮（只给字形不给行框），否则「拼音行比自己的字高出一圈」
        assertTrue(CandidateRows.PINYIN_BAR_HEIGHT_DP < CandidateRows.ROW_HEIGHT_DP)
    }

    @Test
    fun 系统字体放大时行高与拼音条随之长高不裁字() {
        val normalRow = CandidateRows.rowHeightPx(3f, textPx = 60f)
        val largeRow = CandidateRows.rowHeightPx(3f, textPx = 90f) // 20sp @ fontScale 1.5
        assertTrue("字体放大后行高必须变高，否则固定 28dp 会裁掉文字下半", largeRow > normalRow)
        assertTrue("行高至少要盖得住文字本身", largeRow >= 90)

        val normalBar = CandidateRows.pinyinBarHeightPx(3f, textPx = 42f)
        val largeBar = CandidateRows.pinyinBarHeightPx(3f, textPx = 63f) // 14sp @ fontScale 1.5
        assertTrue("字体放大后拼音条必须变高，否则字形会被裁", largeBar > normalBar)
        assertTrue("拼音条至少要盖得住字形", largeBar >= 63)
    }

    @Test
    fun 字体缩小也不低于紧凑档() {
        assertEquals("行高不得低于 28dp", 84, CandidateRows.rowHeightPx(3f, textPx = 51f))
        assertEquals("拼音条不得低于 16dp", 48, CandidateRows.pinyinBarHeightPx(3f, textPx = 36f))
    }

    @Test
    fun 越界行数落回单行档() {
        // Prefs 已做归一，这里守的是兜底：任何非 2 的值都不得算成双行
        assertEquals(44, CandidateRows.heightDp(0))
        assertEquals(44, CandidateRows.heightDp(3))
        assertEquals(44, CandidateRows.heightDp(-1))
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

        assertEquals("${CandidateRows.SINGLE_BAR_HEIGHT_DP}dp", attr("candidate_bar", "layout_height"))
        assertEquals("${CandidateRows.PINYIN_BAR_HEIGHT_DP}dp", attr("pinyin_bar", "layout_height"))
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
}
