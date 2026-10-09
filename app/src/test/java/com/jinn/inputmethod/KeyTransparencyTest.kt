package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun 描边缩放_按原alpha比例而非替换() {
        // 描边色**刻意自带 alpha**（磨砂 0x33FFFFFF = 20%、石墨 12%、极光 40%）：滑杆必须按比例
        // 缩放它。若有人把它改回「替换」语义（看起来像是统一化），默认档就会把半透明高光描边抹成
        // 全不透明实色，24 套配色皮肤全部受影响（BUG.md 第 15 批 M1）。
        assertEquals("默认档原样返回", 0x33FFFFFF, KeyTransparency.scaleAlpha(0x33FFFFFF, 1f))
        assertEquals("比例减半：51 → 26", 0x1AFFFFFF, KeyTransparency.scaleAlpha(0x33FFFFFF, 0.5f))
        assertEquals("factor = 0 → 全透", 0x00FFFFFF, KeyTransparency.scaleAlpha(0x33FFFFFF, 0f))
        assertEquals("已成不透明时与替换等价", 0xFF123456.toInt(), KeyTransparency.scaleAlpha(0xFF123456.toInt(), 1f))
        assertEquals("factor 上界钳位到 1", 0xFFFFFFFF.toInt(), KeyTransparency.scaleAlpha(0x80FFFFFF.toInt(), 2f))
        // 对照：替换语义会把 20% 高光直接变成全不透明（原缺陷形态）
        assertEquals(0xFFFFFFFF.toInt(), KeyTransparency.withAlpha(0x33FFFFFF, 1f))
    }

    @Test
    fun 重扫身份键_档位与皮肤都必须进判据() {
        // MEM-26：整树重扫面透明度的早退判据漏掉 skin.id 时，「仅换肤」会命中早退，
        // 键面/面板 alpha 不再刷新 —— 键面还带着上一套皮肤的底，是**静默**外观错误。
        assertEquals(
            "两者都没变：键相同（可早退）",
            KeyTransparency.appearanceKey(20, "MOKA"),
            KeyTransparency.appearanceKey(20, "MOKA"),
        )
        assertNotEquals(
            "只换肤：键必须不同（不许早退）",
            KeyTransparency.appearanceKey(20, "MOKA"),
            KeyTransparency.appearanceKey(20, "GRAPHITE"),
        )
        assertNotEquals(
            "只改档位：键必须不同",
            KeyTransparency.appearanceKey(20, "MOKA"),
            KeyTransparency.appearanceKey(21, "MOKA"),
        )
        // 原始数值进键：将来文案改成「关 / 弱 / 强」这类档名，也不会把相邻两档混成一个键
        assertNotEquals(
            "相邻档位不得撞键",
            KeyTransparency.appearanceKey(0, "MOKA"),
            KeyTransparency.appearanceKey(1, "MOKA"),
        )
    }
}
