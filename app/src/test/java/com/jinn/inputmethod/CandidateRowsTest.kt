package com.jinn.inputmethod

import org.junit.Assert.assertEquals
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
        // 24 = PinyinKeyboardView.MAX_RENDERED_CANDIDATES，双行时正好 12 列
        val items = (1..24).map { it.toString() }
        val cols = CandidateRows.columnsOf(items)
        assertEquals(12, cols.size)
        assertEquals(items.filterIndexed { i, _ -> i % 2 == 1 }, cols.mapNotNull { it.first })
        assertEquals(items.filterIndexed { i, _ -> i % 2 == 0 }, cols.map { it.second })
        assertEquals(
            items.toSet(),
            (cols.mapNotNull { it.first } + cols.map { it.second }).toSet(),
        )
    }

    @Test
    fun 高度与字号按档位() {
        // 单行 = 拼音条 + 一排候选；双行 = 拼音条 + 两排（拼音条叠在两排之间的中缝上）
        // 单行 = 拼音条 + 间隔 + 一排候选（间隔让拼音与候选分得开，双行由中缝充当间隔、不另加）
        assertEquals(
            CandidateRows.PINYIN_BAR_HEIGHT_DP + CandidateRows.SINGLE_PINYIN_GAP_DP + CandidateRows.ROW_HEIGHT_DP,
            CandidateRows.heightDp(CandidateRows.SINGLE),
        )
        assertEquals(58, CandidateRows.heightDp(CandidateRows.SINGLE))
        assertEquals(
            CandidateRows.PINYIN_BAR_HEIGHT_DP + CandidateRows.ROW_HEIGHT_DP * 2,
            CandidateRows.heightDp(CandidateRows.DOUBLE),
        )
        assertEquals(84, CandidateRows.heightDp(CandidateRows.DOUBLE))
        // 字号两档统一（用户 2026-09-25 指定）：拼音 14sp / 汉字 20sp
        assertEquals(14f, CandidateRows.PINYIN_TEXT_SP, 0f)
        assertEquals(20f, CandidateRows.CANDIDATE_TEXT_SP, 0f)
        // 拼音条至少要盖得住 14sp 的文字行高（14 × 1.4 = 19.6dp ≤ 20dp）
        assertTrue(CandidateRows.PINYIN_BAR_HEIGHT_DP >= 14f * 1.4f)
    }

    @Test
    fun 栏高恒等于拼音区加档位排数() {
        val d = 3f
        val cand = 60f // 20sp @ 默认字体
        val pin = 42f // 14sp @ 默认字体
        val perRow = CandidateRows.rowHeightPx(d, cand)
        val bar = CandidateRows.pinyinBarHeightPx(d, pin)
        val areaSingle = CandidateRows.pinyinAreaHeightPx(CandidateRows.SINGLE, d, pin)
        val areaDouble = CandidateRows.pinyinAreaHeightPx(CandidateRows.DOUBLE, d, pin)

        // 双行的拼音区就是拼音条本身（间隔是中缝）；单行要多一条间隔
        assertEquals(bar, areaDouble)
        assertEquals(bar + CandidateRows.SINGLE_PINYIN_GAP_DP * d.toInt(), areaSingle)
        assertEquals(areaSingle + perRow, CandidateRows.barHeightPx(CandidateRows.SINGLE, d, cand, pin))
        assertEquals(areaDouble + perRow * 2, CandidateRows.barHeightPx(CandidateRows.DOUBLE, d, cand, pin))
    }

    @Test
    fun 默认字体下行高与拼音条就是紧凑档() {
        // density=3 / 20sp ⇒ 候选文字 60px（行高 84px < 32dp=96px）、拼音 42px（行高 59px < 20dp=60px）
        assertEquals(96, CandidateRows.rowHeightPx(density = 3f, textPx = 60f))
        assertEquals(60, CandidateRows.pinyinBarHeightPx(density = 3f, textPx = 42f))
    }

    @Test
    fun 系统字体放大时行高与拼音条随之长高不裁字() {
        val normalRow = CandidateRows.rowHeightPx(3f, textPx = 60f)
        val largeRow = CandidateRows.rowHeightPx(3f, textPx = 90f) // 20sp @ fontScale 1.5
        assertTrue("字体放大后行高必须变高，否则固定 32dp 会裁掉文字下半", largeRow > normalRow)
        assertTrue("行高至少要盖得住文字本身", largeRow >= 90)

        val normalBar = CandidateRows.pinyinBarHeightPx(3f, textPx = 42f)
        val largeBar = CandidateRows.pinyinBarHeightPx(3f, textPx = 63f) // 14sp @ fontScale 1.5
        assertTrue("字体放大后拼音条必须变高，否则固定 20dp 会裁掉拼音", largeBar > normalBar)
        assertTrue("拼音条至少要盖得住文字本身", largeBar >= 63)
    }

    @Test
    fun 字体缩小也不低于紧凑档() {
        assertEquals("行高不得低于 32dp", 96, CandidateRows.rowHeightPx(3f, textPx = 51f))
        assertEquals("拼音条不得低于 20dp", 60, CandidateRows.pinyinBarHeightPx(3f, textPx = 36f))
    }

    @Test
    fun 越界行数落回单行档() {
        // Prefs 已做归一，这里守的是兜底：任何非 2 的值都不得算成双行
        assertEquals(58, CandidateRows.heightDp(0))
        assertEquals(58, CandidateRows.heightDp(3))
        assertEquals(58, CandidateRows.heightDp(-1))
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
    }
}
