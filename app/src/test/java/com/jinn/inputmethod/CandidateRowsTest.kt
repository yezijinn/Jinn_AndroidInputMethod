package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选栏双行排版（[CandidateRows]）：上排偶数项、下排奇数项，上下成列。
 *
 * 排布契约（用户 2026-09-25 指定）：下排恒为第 1/3/5… 个候选，即「离键盘最近的
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
        // 单行 48dp 是 keyboard_pinyin.xml 的历史值，改它等于改所有单行用户的外观
        assertEquals(48, CandidateRows.heightDp(CandidateRows.SINGLE))
        // 双行：两排合计必须正好等于候选栏高度，否则列底被裁（多）或留白（少）
        assertEquals(CandidateRows.ROW_HEIGHT_DP * 2, CandidateRows.heightDp(CandidateRows.DOUBLE))
        assertEquals(72, CandidateRows.heightDp(CandidateRows.DOUBLE))
        // 行高变矮必须同步收字号（两边都是 21sp 会在 36dp 行里贴边）
        assertTrue(CandidateRows.textSizeSp(CandidateRows.DOUBLE) < CandidateRows.textSizeSp(CandidateRows.SINGLE))
    }

    @Test
    fun 双排行高在默认字体下就是紧凑档() {
        // density=3 / 20sp 默认字体 ⇒ 文字 60px，36dp=108px 更大 ⇒ 取紧凑档
        assertEquals(108, CandidateRows.rowHeightPx(density = 3f, textPx = 60f))
    }

    @Test
    fun 系统字体放大时行高随之长高不裁字() {
        val normal = CandidateRows.rowHeightPx(3f, textPx = 60f)
        val large = CandidateRows.rowHeightPx(3f, textPx = 90f) // 20sp @ fontScale 1.5
        assertTrue("字体放大后行高必须变高，否则固定 36dp 会裁掉文字下半", large > normal)
        assertTrue("行高至少要盖得住文字本身", large >= 90)
    }

    @Test
    fun 字体缩小也不低于紧凑档() {
        val small = CandidateRows.rowHeightPx(3f, textPx = 51f) // 20sp @ fontScale 0.85
        assertEquals("行高不得低于 36dp，否则 候选栏高度 = 2 × 行高 的关系被破坏", 108, small)
    }

    @Test
    fun 越界行数落回单行档() {
        // Prefs 已做归一，这里守的是兜底：任何非 2 的值都不得算成双行
        assertEquals(48, CandidateRows.heightDp(0))
        assertEquals(48, CandidateRows.heightDp(3))
        assertEquals(48, CandidateRows.heightDp(-1))
    }
}
