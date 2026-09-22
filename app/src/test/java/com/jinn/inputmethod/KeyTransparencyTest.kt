package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 半透明键盘纯逻辑护栏：定义域钳位、两档 alpha 的边界/单调性、颜色 alpha 合成。 */
class KeyTransparencyTest {

    @Test
    fun 钳位_非法值一律收敛到定义域() {
        assertEquals(KeyTransparency.MIN_PERCENT, KeyTransparency.clampPercent(-10))
        assertEquals(0, KeyTransparency.clampPercent(0))
        assertEquals(55, KeyTransparency.clampPercent(55))
        assertEquals(KeyTransparency.MAX_PERCENT, KeyTransparency.clampPercent(999))
    }

    @Test
    fun 进度与百分比往返稳定_越界进度同样收敛() {
        for (p in KeyTransparency.MIN_PERCENT..KeyTransparency.MAX_PERCENT) {
            assertEquals(p, KeyTransparency.progressToPercent(KeyTransparency.percentToProgress(p)))
        }
        // SeekBar 的 max 由 PROGRESS_MAX 提供；这里防外部写入的越界进度
        assertEquals(KeyTransparency.MIN_PERCENT, KeyTransparency.progressToPercent(-5))
        assertEquals(KeyTransparency.MAX_PERCENT, KeyTransparency.progressToPercent(Int.MAX_VALUE))
    }

    @Test
    fun 默认值零透明_两档alpha都恰好是1() {
        assertEquals(0, KeyTransparency.DEFAULT_PERCENT)
        assertEquals(1f, KeyTransparency.plateAlpha(0), 0f)
        assertEquals(1f, KeyTransparency.surfaceAlpha(0), 0f)
    }

    @Test
    fun 透明度越高alpha越低_且单调不增不越界() {
        var lastPlate = 1f
        var lastSurface = 1f
        for (p in 0..KeyTransparency.MAX_PERCENT) {
            val plate = KeyTransparency.plateAlpha(p)
            val surface = KeyTransparency.surfaceAlpha(p)
            assertTrue("背板 alpha 必须单调不增: p=$p", plate <= lastPlate)
            assertTrue("键面 alpha 必须单调不增: p=$p", surface <= lastSurface)
            assertTrue("背板 alpha 越界: $plate", plate in 0f..1f)
            assertTrue("键面 alpha 越界: $surface", surface in 0f..1f)
            // 键面必须始终比背板实：否则按键会「陷」进背景、文字也读不清
            assertTrue("键面应不亚于背板: p=$p", surface >= plate)
            lastPlate = plate
            lastSurface = surface
        }
    }

    @Test
    fun 可读性红线_拉满时键面保留六成_背板全透() {
        assertEquals(
            KeyTransparency.MIN_SURFACE_ALPHA,
            KeyTransparency.surfaceAlpha(KeyTransparency.MAX_PERCENT),
            1e-6f,
        )
        // 上限 100%：背板完全透出应用内容（0f）。拉满后屏幕上只剩键面/候选栏这些带文字的面
        assertEquals(0f, KeyTransparency.plateAlpha(KeyTransparency.MAX_PERCENT), 1e-6f)
        assertEquals(
            KeyTransparency.MIN_PLATE_ALPHA,
            KeyTransparency.plateAlpha(KeyTransparency.MAX_PERCENT),
            1e-6f,
        )
        // 中段钉死：只断言两端 + 单调性挡不住曲线被换（任意单调曲线都能过，50% 的观感会悄悄变）
        assertEquals(0.5f, KeyTransparency.plateAlpha(50), 1e-6f)
        assertEquals(0.8f, KeyTransparency.surfaceAlpha(50), 1e-6f)
        // 上限自身必须是合法值（滑到顶不会越出定义域）
        assertEquals(KeyTransparency.MAX_PERCENT, KeyTransparency.clampPercent(KeyTransparency.MAX_PERCENT))
    }

    @Test
    fun 颜色合成_只改alpha不改RGB() {
        val base = 0xFF1A2B3C.toInt()
        // 不透明时与原色逐位相等（0% 透明度与历史观感一致的保证）
        assertEquals(base, KeyTransparency.withAlpha(base, 1f))
        val quarter = KeyTransparency.withAlpha(base, 0.25f) // 0.25 * 255 = 63.75 → 64
        assertEquals(0x40, (quarter ushr 24) and 0xFF)
        assertEquals(base and 0x00FFFFFF, quarter and 0x00FFFFFF)
        // 全透明 / 越界收敛
        assertEquals(0x00, (KeyTransparency.withAlpha(base, 0f) ushr 24) and 0xFF)
        assertEquals(0xFF, (KeyTransparency.withAlpha(base, 5f) ushr 24) and 0xFF)
        assertEquals(0x00, (KeyTransparency.withAlpha(base, -1f) ushr 24) and 0xFF)
        // 语义是「替换」alpha 而非「相乘」：带 alpha 的输入被拉回目标值 ，
        // 全树扫描靠它幂等（重复套用结果相同），并保证回到 0% 时能把淡过的面恢复为不透明。
        // 若有人把它改回「保留输入 alpha」（看名字很像 bug），本断言会拦住。
        assertEquals(0xFF1A2B3C.toInt(), KeyTransparency.withAlpha(0x801A2B3C.toInt(), 1f))
        assertEquals(0x001A2B3C, KeyTransparency.withAlpha(0xFF1A2B3C.toInt(), 0f))
    }

    @Test
    fun 文案格式_取整百分比且收敛到定义域() {
        assertEquals("0%", KeyTransparency.formatPercent(0))
        assertEquals("55%", KeyTransparency.formatPercent(55))
        assertEquals("100%", KeyTransparency.formatPercent(999))
    }
}
