package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选字号参数（[CandidateText]）：定义域、滑杆换算与拼音字号的派生口径。
 *
 * 这是「整个候选栏跟着变」的入口参数：它一越界，[CandidateRows] 算出的栏高就会偏出屏幕或
 * 低于可点尺寸，所以读写两侧都必须收敛（[Prefs.candidateTextSp] 与这里的纯函数同一套边界）。
 */
class CandidateTextTest {

    @Test
    fun 默认档与历史口径一致() {
        assertEquals(20f, CandidateText.DEFAULT_SP, 0f)
        assertEquals(14f, CandidateText.MIN_SP, 1f)
        assertEquals(28f, CandidateText.MAX_SP, 0f)
        // 默认档拼音字号 = 20 × 0.7 = 14sp（改造前写死的 14sp，观感逐像素不变）
        assertEquals(14f, CandidateText.pinyinSpOf(CandidateText.DEFAULT_SP), 0.001f)
        // 滑杆格数 = 定义域跨度 / 步进
        assertEquals(14, CandidateText.PROGRESS_MAX)
    }

    @Test
    fun 越界与脏值一律收敛() {
        assertEquals(14f, CandidateText.clampSp(13f), 0f)
        assertEquals(14f, CandidateText.clampSp(-5f), 0f)
        assertEquals(28f, CandidateText.clampSp(40f), 0f)
        // 步进对齐：19.4 → 19、19.6 → 20
        assertEquals(19f, CandidateText.clampSp(19.4f), 0f)
        assertEquals(20f, CandidateText.clampSp(19.6f), 0f)
        // NaN / Infinity 退回默认值：放行会让 NaN 一路传到 setTextSize 与布局高度
        assertEquals(20f, CandidateText.clampSp(Float.NaN), 0f)
        assertEquals(20f, CandidateText.clampSp(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun 滑杆进度往返不漂() {
        for (sp in 14..28) {
            val progress = CandidateText.spToProgress(sp.toFloat())
            assertEquals("${sp}sp 的滑杆进度", sp - 14, progress)
            assertEquals("${sp}sp 往返", sp.toFloat(), CandidateText.progressToSp(progress), 0f)
        }
        // 越界进度也落在定义域内（滑杆重建、外部写入都可能给越界值）
        assertEquals(14f, CandidateText.progressToSp(-3), 0f)
        assertEquals(28f, CandidateText.progressToSp(99), 0f)
    }

    @Test
    fun 拼音字号按比例派生且不低于下限() {
        assertEquals(19.6f, CandidateText.pinyinSpOf(28f), 0.001f)
        assertEquals("越界字号先钳到 28sp 再派生", 19.6f, CandidateText.pinyinSpOf(30f), 0.001f)
        // 下限：14sp × 0.7 = 9.8sp 太小，抬到 12sp
        assertEquals(12f, CandidateText.pinyinSpOf(14f), 0.001f)
        assertEquals(12f, CandidateText.pinyinSpOf(0f), 0.001f)
        assertTrue("拼音字号必须始终小于候选字号", CandidateText.pinyinSpOf(28f) < 28f)
    }

    @Test
    fun 数值文案固定格式() {
        assertEquals("20 sp", CandidateText.formatSp(20f))
        assertEquals("14 sp", CandidateText.formatSp(9f)) // 越界 → 钳到 14
    }
}
