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
        assertEquals(28f, CandidateText.DEFAULT_SP, 0f)
        assertEquals(14f, CandidateText.MIN_SP, 1f)
        assertEquals(42f, CandidateText.MAX_SP, 0f)   // 2026-10-09：28 → 42（外观页定义域 ×2）
        // 默认档拼音字号 = 28 × 0.7 = 19.6sp
        assertEquals(19.6f, CandidateText.pinyinSpOf(CandidateText.DEFAULT_SP), 0.001f)
        // 滑杆格数 = 定义域跨度 / 步进（42 − 14 = 28 格；2026-10-09 定义域 ×2 前是 14 格）
        assertEquals(28, CandidateText.PROGRESS_MAX)
    }

    @Test
    fun 越界与脏值一律收敛() {
        assertEquals(14f, CandidateText.clampSp(13f), 0f)
        assertEquals(14f, CandidateText.clampSp(-5f), 0f)
        // 上界随定义域扩到 42（40 现在是合法值，不再是上界）
        assertEquals(40f, CandidateText.clampSp(40f), 0f)
        assertEquals(42f, CandidateText.clampSp(999f), 0f)
        // 步进对齐：27.4 → 27、27.6 → 28
        assertEquals(27f, CandidateText.clampSp(27.4f), 0f)
        assertEquals(28f, CandidateText.clampSp(27.6f), 0f)
        // NaN / Infinity 退回默认值：放行会让 NaN 一路传到 setTextSize 与布局高度
        assertEquals(28f, CandidateText.clampSp(Float.NaN), 0f)
        assertEquals(28f, CandidateText.clampSp(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun 滑杆进度往返不漂() {
        for (sp in 14..42) {
            val progress = CandidateText.spToProgress(sp.toFloat())
            assertEquals("${sp}sp 的滑杆进度", sp - 14, progress)
            assertEquals("${sp}sp 往返", sp.toFloat(), CandidateText.progressToSp(progress), 0f)
        }
        // 越界进度也落在定义域内（滑杆重建、外部写入都可能给越界值）
        assertEquals(14f, CandidateText.progressToSp(-3), 0f)
        assertEquals(42f, CandidateText.progressToSp(99), 0f)
    }

    @Test
    fun 拼音字号按比例派生且不低于下限() {
        assertEquals(19.6f, CandidateText.pinyinSpOf(28f), 0.001f)
        // 30sp 现在是合法值（定义域 14~42），先钳位再按 0.7 派生 ⇒ 21sp
        assertEquals(21f, CandidateText.pinyinSpOf(30f), 0.001f)
        assertEquals("越界字号先钳到上界再派生", 29.4f, CandidateText.pinyinSpOf(99f), 0.001f)
        // 下限：14sp × 0.7 = 9.8sp 太小，抬到 12sp
        assertEquals(12f, CandidateText.pinyinSpOf(14f), 0.001f)
        assertEquals(12f, CandidateText.pinyinSpOf(0f), 0.001f)
        assertTrue("拼音字号必须始终小于候选字号", CandidateText.pinyinSpOf(28f) < 28f)
        assertTrue("上界处也必须小于候选字号", CandidateText.pinyinSpOf(42f) < 42f)
    }

    @Test
    fun 数值文案固定格式() {
        assertEquals("20 sp", CandidateText.formatSp(20f))
        assertEquals("14 sp", CandidateText.formatSp(9f)) // 越界 → 钳到 14
    }

    @Test
    fun 拼音字号归一与滑杆往返() {
        // 0 = 自动，必须原样保留（NaN / Inf 同样退回自动，不放进布局）
        assertEquals(CandidateText.PINYIN_DEFAULT_AUTO, CandidateText.clampPinyinSp(0f), 0f)
        assertEquals(CandidateText.PINYIN_DEFAULT_AUTO, CandidateText.clampPinyinSp(Float.NaN), 0f)
        assertEquals(CandidateText.PINYIN_DEFAULT_AUTO, CandidateText.clampPinyinSp(Float.NEGATIVE_INFINITY), 0f)
        // 定义域 12~30sp，越界钳位、步进 1sp
        assertEquals(CandidateText.MIN_PINYIN_SP, CandidateText.clampPinyinSp(5f), 0f)
        assertEquals(CandidateText.MIN_PINYIN_SP, CandidateText.clampPinyinSp(12f), 0f)
        assertEquals(CandidateText.MAX_PINYIN_SP, CandidateText.clampPinyinSp(99f), 0f)
        assertEquals(22f, CandidateText.clampPinyinSp(21.6f), 0f)
        assertEquals(18, CandidateText.PINYIN_PROGRESS_MAX)
        // 进度往返不漂
        for (sp in 12..30) {
            val progress = CandidateText.pinyinSpToProgress(sp.toFloat())
            assertEquals("${sp}sp 的滑杆进度", sp - 12, progress)
            assertEquals("${sp}sp 往返", sp.toFloat(), CandidateText.pinyinProgressToSp(progress), 0f)
        }
        assertEquals(CandidateText.MIN_PINYIN_SP, CandidateText.pinyinProgressToSp(-3), 0f)
        assertEquals(CandidateText.MAX_PINYIN_SP, CandidateText.pinyinProgressToSp(99), 0f)
        // 文案：自动档显示派生值，显式档显示具体值
        assertEquals("14 sp", CandidateText.formatPinyinSp(0f, 14f))
        assertEquals("22 sp", CandidateText.formatPinyinSp(22f, 14f))
    }
}
