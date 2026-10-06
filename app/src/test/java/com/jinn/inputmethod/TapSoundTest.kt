package com.jinn.inputmethod

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TapSound] 的纯 JVM 单测：重点是**脏数据防护**。
 *
 * 为什么必须钉住 [TapSound.parseMap]：分组映射是从备份文件按字符串导回来的，外部文件可以被
 * 手工构造成任意内容。没有钳位时，一个 `"7,3,0,99,13,2"` 就会让 `soundIds[99]` 越界崩溃——
 * 而这条路径在真机上只在「导入配置」时才会走到，普通点击测试发现不了。
 */
class TapSoundTest {

    // ── 映射解析 ─────────────────────────────────────────────

    @Test
    fun `空值一律回落默认映射`() {
        assertArrayEquals(TapSound.DEFAULT_MAP, TapSound.parseMap(null))
        assertArrayEquals(TapSound.DEFAULT_MAP, TapSound.parseMap(""))
        assertArrayEquals(TapSound.DEFAULT_MAP, TapSound.parseMap("   "))
    }

    @Test
    fun `非数字按该项回落、长度不符按位补齐`() {
        // 旧实现对这两类脏数据整体回落六项，于是一个打错的字母 / 多一个逗号会把六组自定义
        // 一起清空，而用户之后改任意一组时就把这串默认值写盘固化。改成按位处理。
        assertArrayEquals(TapSound.DEFAULT_MAP, TapSound.parseMap("abc"))
        // 非数字只影响该项，其余按原值保留
        assertArrayEquals(
        intArrayOf(0, 1, 2, 3, 4, TapSound.DEFAULT_MAP[5]),
        TapSound.parseMap("0,1,2,3,4,x"),
        )
        // 多余字段忽略
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), TapSound.parseMap("0,1,2,3,4,5,6"))
        // 缺失字段留默认
        assertArrayEquals(
            intArrayOf(1, 2, TapSound.DEFAULT_MAP[2], TapSound.DEFAULT_MAP[3], TapSound.DEFAULT_MAP[4], TapSound.DEFAULT_MAP[5]),
            TapSound.parseMap("1,2"),
        )
    }

    @Test
    fun `合法串原样解析`() {
        val map = TapSound.parseMap("0,1,2,3,4,5")
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), map)
    }

    @Test
    fun `上界外的索引回落该组默认音而不是钳到末位`() {
        // 音效下架后 [SOUND_COUNT] 变小，旧版本里合法的索引就变成了上界外。钳到末位会静默换成一个
        // 用户没选过的音色，而读写两侧都过 parseMap，落盘串随即被覆写、原值不可恢复。
        val stale = TapSound.SOUND_COUNT            // 曾经合法的最后一个索引
        val map = TapSound.parseMap("$stale,3,0,14,13,2")
        assertEquals("文字组应回落到该组默认音", TapSound.DEFAULT_MAP[0], map[0])
        assertEquals("其余项不受影响", 3, map[1])
        // 负数（连 NONE 之外的值）仍落到「不播放」，不回落默认
        assertEquals(TapSound.NONE, TapSound.parseMap("-5,3,0,14,13,2")[0])
        assertEquals(TapSound.NONE, TapSound.parseMap("-1,3,0,14,13,2")[0])
        // 远超上界的脏数据与「旧索引」在语义上无法区分，统一按上界外处理，不猜用户想要哪一个
        assertEquals(TapSound.DEFAULT_MAP[0], TapSound.parseMap("99,3,0,14,13,2")[0])
    }

    @Test
    fun `减一表示不播放且必须原样保留`() {
        val map = TapSound.parseMap("-1,3,0,14,13,2")
        assertEquals(TapSound.NONE, map[0])
        assertEquals(3, map[1])
    }

    @Test
    fun `格式化后再解析必须等价（往返稳定）`() {
        for (raw in listOf("-1,3,0,14,13,2", "0,1,2,3,4,5", TapSound.formatMap(TapSound.DEFAULT_MAP))) {
            val once = TapSound.parseMap(raw)
            val twice = TapSound.parseMap(TapSound.formatMap(once))
            assertArrayEquals("往返后必须等价：$raw", once, twice)
        }
    }

    @Test
    fun `格式化越界数组同样钳位`() {
        val text = TapSound.formatMap(intArrayOf(99, TapSound.NONE, -7, 14, 13, 2))
        assertArrayEquals(intArrayOf(TapSound.SOUND_COUNT - 1, TapSound.NONE, TapSound.NONE, 14, 13, 2), TapSound.parseMap(text))
    }

    @Test
    fun `格式化长度不足的数组不抛异常`() {
        val text = TapSound.formatMap(intArrayOf(1))
        assertEquals(TapSound.GROUP_COUNT, text.split(',').size)
        // 缺失项回落默认值，而不是 0
        assertArrayEquals(
            intArrayOf(1, TapSound.DEFAULT_MAP[1], TapSound.DEFAULT_MAP[2], TapSound.DEFAULT_MAP[3], TapSound.DEFAULT_MAP[4], TapSound.DEFAULT_MAP[5]),
            TapSound.parseMap(text),
        )
    }

    // ── 分组与清单 ────────────────────────────────────────────

    @Test
    fun `分组标签数与分组总数一致`() {
        assertEquals(TapSound.GROUP_COUNT, TapSound.GROUP_LABELS.size)
        assertEquals(TapSound.GROUP_COUNT, TapSound.DEFAULT_MAP.size)
    }

    @Test
    fun `默认映射全部落在合法范围内`() {
        assertTrue(
            "默认映射含越界值：${TapSound.DEFAULT_MAP.toList()}",
            TapSound.DEFAULT_MAP.all { it in 0 until TapSound.SOUND_COUNT },
        )
    }

    @Test
    fun `asset 名按两位序号拼接`() {
        assertEquals("kbd_01.ogg", TapSound.assetName(0))
        assertEquals("kbd_16.ogg", TapSound.assetName(TapSound.SOUND_COUNT - 1))
    }

    /**
     * 时长表是**描述性元数据**（真值在 manifest 与资产本身），换资产时容易忘了同步。
     * 条数与正数性可以机械钉住；具体数值对不上只能人工复核，故这里只守这两条。
     */
    @Test
    fun `时长表条数与音效个数一致且全为正`() {
        assertEquals(
            "SOUND_DURATION_MS 与 SOUND_COUNT 不一致（换资产后忘记同步）",
            TapSound.SOUND_COUNT,
            TapSound.SOUND_DURATION_MS.size,
        )
        assertTrue(
            "时长必须是正数：${TapSound.SOUND_DURATION_MS.toList()}",
            TapSound.SOUND_DURATION_MS.all { it > 0 },
        )
    }

    @Test
    fun `设置页显示名带补零序号与时长`() {
        assertEquals("音效 01 · 60ms", TapSound.soundLabel(0))
        assertEquals("音效 16 · 210ms", TapSound.soundLabel(TapSound.SOUND_COUNT - 1))
        assertEquals("「不播放」由调用方显示，这里应是空串", "", TapSound.soundLabel(TapSound.NONE))
        // 越界索引不崩（映射串已被钳位，这里是最后一道防线）
        assertEquals("", TapSound.soundLabel(TapSound.SOUND_COUNT + 5))
    }

    /**
     * 默认映射里「删除 / 清空」必须是用户 2026-10-05 指定的 kbd_15（索引 14）。
     *
     * 它同时是 `tools/sound_preview/keyboard_sounds_manifest.md` 里标注的角色；
     * 谁改默认映射把这条改掉，两处就对不上了。
     */
    @Test
    fun `删除清空组默认用 kbd_15`() {
        assertEquals(14, TapSound.DEFAULT_MAP[TapSound.G_ERASE])
        assertEquals("kbd_15.ogg", TapSound.assetName(TapSound.DEFAULT_MAP[TapSound.G_ERASE]))
    }

    // ── 震动档位 ─────────────────────────────────────────────

    @Test
    fun `震动强度档位被钳到定义域内`() {
        assertEquals(TapSound.VIB_OFF, TapSound.clampVibrationTier(-3))
        assertEquals(TapSound.VIB_STRONG, TapSound.clampVibrationTier(99))
        assertEquals(TapSound.VIB_MEDIUM, TapSound.clampVibrationTier(TapSound.VIB_MEDIUM))
    }

    @Test
    fun `双段停顿有下限也有上限，弱档不再被比例吃掉`() {
        // 下限 30ms：20ms 配 10ms 脉冲听不出是两下（弱档退化成「一次带凹陷的震动」）。
        // 上限 40ms：间隔越大，后半段越靠后，越容易被下一键的 vibrate 替换掉。
        assertEquals(30L, TapSound.erasePulseGap(10L))   // 弱：不再是 20
        assertEquals(30L, TapSound.erasePulseGap(15L))   // 中
        assertEquals(40L, TapSound.erasePulseGap(20L))   // 强：封顶
        for (tier in TapSound.VIB_WEAK..TapSound.VIB_STRONG) {
            val (ms, _) = TapSound.vibrationSpec(tier)
            val gap = TapSound.erasePulseGap(ms)
            assertTrue("档位 $tier 的停顿 $gap 超出 [30,40]", gap in 30L..40L)
            assertTrue("档位 $tier 的总时长 ${ms * 2 + gap}ms 过长", ms * 2 + gap <= 80L)
        }
        // 档位为「关闭」时脉冲宽 0：停顿回到下限而不是 0（波形不能出现 0 长度段）
        assertEquals(30L, TapSound.erasePulseGap(0L))
        // 负值（脏数据）同样被下限抬起来，不会出现负的间隔
        assertEquals(30L, TapSound.erasePulseGap(-5L))
    }

    @Test
    fun `单个脏数据不牵连其余分组`() {
        // 旧实现对「非数字」整体回落六项，于是一个打错的字母会把六组自定义一起清空，
        // 且用户之后改任意一组时就把这串默认值写盘固化。
        val map = TapSound.parseMap("7,x,0,14,13,2")
        assertEquals(7, map[0])
        assertEquals("数字组应回落到默认", TapSound.DEFAULT_MAP[1], map[1])
        assertEquals("后面的分组不受牵连", 0, map[2])
        assertEquals(14, map[3])
        // 多余字段忽略、缺失字段留默认
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), TapSound.parseMap("1,2,3,4,5,6,99"))
        assertArrayEquals(
            intArrayOf(1, 2, 3, 4, TapSound.DEFAULT_MAP[4], TapSound.DEFAULT_MAP[5]),
            TapSound.parseMap("1,2,3,4"),
        )
    }

    @Test
    fun `关闭档不产生震动`() {
        val (ms, amp) = TapSound.vibrationSpec(TapSound.VIB_OFF)
        assertEquals(0L, ms)
        assertEquals(0, amp)
    }

    @Test
    fun `震动时长与振幅随档位递增`() {
        val weak = TapSound.vibrationSpec(TapSound.VIB_WEAK)
        val medium = TapSound.vibrationSpec(TapSound.VIB_MEDIUM)
        val strong = TapSound.vibrationSpec(TapSound.VIB_STRONG)
        assertTrue("弱 < 中", weak.first < medium.first && weak.second < medium.second)
        assertTrue("中 < 强", medium.first < strong.first && medium.second < strong.second)
        assertTrue("振幅必须在 0..255", strong.second in 0..255)
    }

    @Test
    fun `只有删除清空组用双段触感`() {
        assertTrue(TapSound.isDoublePulse(TapSound.G_ERASE))
        for (g in 0 until TapSound.GROUP_COUNT) {
            if (g != TapSound.G_ERASE) assertTrue("组 $g 不应是双段", !TapSound.isDoublePulse(g))
        }
    }

    @Test
    fun `音量被钳到 0 到 100`() {
        assertEquals(TapSound.VOLUME_MIN, TapSound.clampVolume(-20))
        assertEquals(TapSound.VOLUME_MAX, TapSound.clampVolume(500))
        assertEquals(TapSound.VOLUME_DEFAULT, TapSound.clampVolume(TapSound.VOLUME_DEFAULT))
    }

    // ── 节流 ─────────────────────────────────────────────────

    @Test
    fun `普通键挡掉 30ms 内的连击`() {
        val t = TapSound.Throttle(30L, 150L)
        assertTrue(t.allow(TapSound.G_TEXT, 1_000L))
        assertFalse("20ms 内的下一击应被挡", t.allow(TapSound.G_TEXT, 1_020L))
        assertTrue("越过阈值后放行", t.allow(TapSound.G_TEXT, 1_030L))
    }

    @Test
    fun `删除组不被紧邻的普通键吞掉`() {
        val t = TapSound.Throttle(30L, 150L)
        assertTrue(t.allow(TapSound.G_TEXT, 1_000L))
        // 打字后 10ms 按退格：两份时间戳共用时这一下会静默，而删除档正是辨识度最高的一档
        assertTrue("删除组只跟自己的上一次比", t.allow(TapSound.G_ERASE, 1_010L))
    }

    @Test
    fun `删除组把 55ms 的连删压到 150ms 一档`() {
        val t = TapSound.Throttle(30L, 150L)
        assertTrue(t.allow(TapSound.G_ERASE, 1_000L))
        assertFalse("55ms 后的下一次连删应被挡", t.allow(TapSound.G_ERASE, 1_055L))
        assertTrue("越过 150ms 后放行", t.allow(TapSound.G_ERASE, 1_150L))
    }

    @Test
    fun `节流复位后第一次放行`() {
        val t = TapSound.Throttle(30L, 150L)
        assertTrue(t.allow(TapSound.G_ERASE, 1_000L))
        assertFalse(t.allow(TapSound.G_ERASE, 1_010L))
        t.reset()
        assertTrue("复位后不该被上一轮的残值挡掉", t.allow(TapSound.G_ERASE, 1_020L))
    }

    // ── 试听音取值 ─────────────────────────────────────────────

    @Test
    fun `试听音在本组为不播放时回退到可用音`() {
        val allNone = IntArray(TapSound.GROUP_COUNT) { TapSound.NONE }
        assertEquals("六组全不播放时确实没有可试听的音", TapSound.NONE, TapSound.previewSound(allNone))

        val map = TapSound.DEFAULT_MAP.copyOf()
        assertEquals("本组有音就用本组", map[TapSound.G_TEXT], TapSound.previewSound(map))

        map[TapSound.G_TEXT] = TapSound.NONE
        assertEquals(
            "本组不播放时回退到手上第一个可用的音",
            map.first { it != TapSound.NONE },
            TapSound.previewSound(map),
        )
    }
}
