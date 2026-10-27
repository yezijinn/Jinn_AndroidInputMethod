package com.jinn.inputmethod

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
     * 默认映射：六组统一用 kbd_10（索引 9）。
     *
     * 需求是「分组音色全部用音效 10」—— 一条声音对所有按键，分组只留在震动波形上。
     * 清单里 kbd_10 那一行的「建议角色」是**人工同步**的：发布对账只覆盖「文件名 ↔ SOUND_COUNT」
     * 与时长表项数，它既不读清单、也不碰 [TapSound.DEFAULT_MAP]。
     */
    @Test
    fun `六组默认统一用 kbd_10`() {
        for (g in 0 until TapSound.GROUP_COUNT) {
            assertEquals("组 $g 的默认音", 9, TapSound.DEFAULT_MAP[g])
        }
        assertEquals("kbd_10.ogg", TapSound.assetName(TapSound.DEFAULT_MAP[0]))
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
    fun `震动默认档与三档规格都被钉住`() {
        // 上面那条钳位是自指的（两边引同一个常量），把默认档改掉它也通过；三档的时长与振幅
        // 同样只有序关系断言 —— 改数值全绿。这里按字面量钉住，改动必须同时改这条。
        assertEquals(2, TapSound.VIB_DEFAULT)
        assertEquals(10L to 60, TapSound.vibrationSpec(TapSound.VIB_WEAK))
        assertEquals(15L to 120, TapSound.vibrationSpec(TapSound.VIB_MEDIUM))
        assertEquals(20L to 200, TapSound.vibrationSpec(TapSound.VIB_STRONG))
    }

    @Test
    fun `空字段与多余字段并存时整体回落，不错位`() {
        // ",7,3,0,14,13,2" 按位读会解出 9,7,3,0,14,13（六个值整体后移一位）。
        // 项数超长且有空字段只可能来自外部串，宁可整体回落，也不静默错配。
        assertArrayEquals(TapSound.DEFAULT_MAP, TapSound.parseMap(",7,3,0,14,13,2"))
        assertArrayEquals(TapSound.DEFAULT_MAP, TapSound.parseMap("7,,0,14,13,2,9"))
        // 项数不超长时按位回落不受影响：空位留默认，其余按原值
        assertArrayEquals(
            intArrayOf(7, TapSound.DEFAULT_MAP[1], 0, 14, 13, 2),
            TapSound.parseMap("7,,0,14,13,2"),
        )
        // 尾随的空字段只是「多余字段」：六个值必须原样留下（先剔尾再判，别把尾巴当错位）
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), TapSound.parseMap("0,1,2,3,4,5,"))
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), TapSound.parseMap("0,1,2,3,4,5, , "))
        assertArrayEquals(
            intArrayOf(7, 3, 0, 14, 13, 2),
            TapSound.parseMap(" 7 , 3 , 0 , 14 , 13 , 2 , "),
        )
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

    @Test
    fun `默认音量是 50`() {
        // 「音量被钳位」那条里 assertEquals(VOLUME_DEFAULT, clampVolume(VOLUME_DEFAULT)) 是**自指**的，
        // 把常量改成任何值都通过。默认值要单独钉住，否则「默认 50」没有任何回归保护。
        assertEquals(50, TapSound.VOLUME_DEFAULT)
        // 滑杆初值走 getter 回落，缺键时拿到的就是这个默认值
        assertEquals(50, TapSound.clampVolume(TapSound.VOLUME_DEFAULT))
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
    fun `试听音全不播放时为 NONE、本组有音时取本组`() {
        val allNone = IntArray(TapSound.GROUP_COUNT) { TapSound.NONE }
        assertEquals("六组全不播放时确实没有可试听的音", TapSound.NONE, TapSound.previewSound(allNone))

        val map = TapSound.DEFAULT_MAP.copyOf()
        assertEquals("本组有音就用本组", map[TapSound.G_TEXT], TapSound.previewSound(map))
    }

    /**
     * 回退的具体取音（上面那条钉不到它：两条断言都不进回退分支；而原先这里还有一条
     * `map.first { it != NONE }` 当期望值的断言，与实现同源 —— 把 `firstOrNull` 换成
     * `lastOrNull` 也照样绿，已删）。
     *
     * 真实判据是「序号最小的可听组」—— 组序号即打字常用度序（文字 → 数字 → 符号 → …），
     * 所以「只设了符号与功能」时应当取符号，而不是功能。
     */
    @Test
    fun `试听回退取序号最小的可听组`() {
        val map = IntArray(TapSound.GROUP_COUNT) { TapSound.NONE }
        map[TapSound.G_SYMBOL] = 5
        map[TapSound.G_FUNC] = 2
        assertEquals("符号与功能两组有音时取符号（序号更小）", 5, TapSound.previewSound(map))

        map[TapSound.G_DIGIT] = 9
        assertEquals("补上数字组后改取数字", 9, TapSound.previewSound(map))

        map[TapSound.G_TEXT] = 3
        assertEquals("文字组有音时一律用文字组", 3, TapSound.previewSound(map))

        assertEquals("空数组不崩也不返回越界值", TapSound.NONE, TapSound.previewSound(IntArray(0)))
    }

    // ── 索引归一（唯一钳位出口）────────────────────────────────

    /**
     * 归一函数的定义域两端。
     *
     * 变异：把 `coerceIn` 去掉 / 改成只钳上界 ⇒ 本用例立刻红 —— 而「默认映射目前都是 9」
     * 这件事让**任何**只看 `parseMap("")` 的断言都发现不了归一函数被掏空。
     * 注意它只钉函数本身：四条回落出口**有没有调它**看不出来（9 合法、钳不钳都是 9），
     * 那一半由 `RecentFixesRegressionTest.默认映射的回落出口必须过归一` 按源码形态钉住。
     */
    @Test
    fun `索引归一钳到定义域两端`() {
        assertEquals("负无穷侧钳到「不播放」", TapSound.NONE, TapSound.clampIndex(Int.MIN_VALUE))
        assertEquals(TapSound.NONE, TapSound.clampIndex(-2))
        assertEquals(TapSound.NONE, TapSound.clampIndex(TapSound.NONE))
        assertEquals("合法值原样通过", 0, TapSound.clampIndex(0))
        assertEquals(TapSound.SOUND_COUNT - 1, TapSound.clampIndex(TapSound.SOUND_COUNT - 1))
        assertEquals("上界外钳到末位", TapSound.SOUND_COUNT - 1, TapSound.clampIndex(TapSound.SOUND_COUNT))
        assertEquals(TapSound.SOUND_COUNT - 1, TapSound.clampIndex(Int.MAX_VALUE))
    }

    /**
     * 任意脏串解析后都落在定义域内，且「读 → 写 → 再读」是固定点。
     *
     * 这条替代不了针对具体分支的用例，但它守的是**归纳性质**：[Prefs.tapSoundMap] 的
     * getter 就是 `formatMap(parseMap(raw))`，任何一条出口漏出越界值，这里都会红。
     *
     * ⚠ 别指望它守住「默认映射有没有过归一」：`SOUND_COUNT = 16` 时 `DEFAULT_MAP` 的 9
     * 本来就合法，四条出口即使全都不归一也仍是 `9` —— 实测把 `defaults()` 改回
     * `DEFAULT_MAP.copyOf()`、`v >= SOUND_COUNT` 改回 `DEFAULT_MAP[i].coerceIn(...)`，
     * 本条**照旧绿**（38 例全绿）。那条不变式由 `RecentFixesRegressionTest` 里的
     * `默认映射的回落出口必须过归一` 按源码形态钉住，并用「临时把 SOUND_COUNT 改成 8」实跑过。
     */
    @Test
    fun `任何脏串解析后都落在定义域内且往返稳定`() {
        val corpus = listOf(
            "", " ", ",", ",,", ",,,,,,",
            "0,1,2,3,4,5",
            "-1,-1,-1,-1,-1,-1",
            "16,17,99,2147483647,0,-2147483648",
            "99999999999999999999,x,,3,4,5,6,7",
            "0,1,2,3,4,5,", " 7 , 3 , 0 , 14 , 13 , 2 , ",
            ",7,3,0,14,13,2", "7,,0,14,13,2,9",
        )
        // 三个字段的笛卡尔积（空 / 负数 / 边界 / 越界 / 非数字）
        val atoms = listOf("", "-1", "-5", "0", "9", "15", "16", "99", "x", " ")
        val generated = atoms.flatMap { a -> atoms.flatMap { b -> atoms.map { c -> "$a,$b,$c" } } }
        for (raw in corpus + generated) {
            val map = TapSound.parseMap(raw)
            assertEquals("组数必须恒为 GROUP_COUNT：$raw", TapSound.GROUP_COUNT, map.size)
            for ((i, v) in map.withIndex()) {
                assertTrue(
                    "越界值漏出定义域：\"$raw\" → 组 $i = $v（合法域 ${TapSound.NONE}..${TapSound.SOUND_COUNT - 1}）",
                    v in TapSound.NONE until TapSound.SOUND_COUNT,
                )
            }
            assertArrayEquals(
                "读 → 写 → 再读必须是固定点：\"$raw\"",
                map,
                TapSound.parseMap(TapSound.formatMap(map)),
            )
        }
    }

    /**
     * 越界组在「读 → 解析 → 写」链路上**固化为该组默认音**，且不牵连其余五组。
     *
     * 链路确实存在（`TapSoundActivity.showSoundPicker` 读整串 → 改一组 → 写整串；
     * `Prefs.tapSoundMap` 读写两侧都过 parse/format），所以「用户盘上的越界值会被静默改写」
     * 是真的 —— 判它不构成缺陷的依据只有两条：① 那个值本来就不可能播出（`soundIds.getOrElse`
     * 取到 0 = 静音）；② 改法是明确规则（该组默认音），不是钳到末位那种「换成另一个用户没挑过的
     * 音色」。这两条一旦被改坏，本用例会红。
     */
    @Test
    fun `越界组在读写链路上固化为该组默认音且不牵连其余组`() {
        val raw = "3,7,0,14,13,99"
        val read = TapSound.parseMap(raw)
        assertEquals("越界组回落该组默认音", TapSound.DEFAULT_MAP[5], read[5])
        assertEquals("其余五组原样保留", listOf(3, 7, 0, 14, 13), read.take(5).toList())

        val persisted = TapSound.formatMap(read)
        assertArrayEquals("写出去的串再读回来必须不变（否则每次保存都会漂移）", read, TapSound.parseMap(persisted))

        // 用户随后改第 1 组：整串重写，另外五组仍是刚才那五个值（越界组保持该组默认音）
        val edited = TapSound.parseMap(persisted)
        edited[0] = 5
        assertArrayEquals(
            intArrayOf(5, 7, 0, 14, 13, TapSound.DEFAULT_MAP[5]),
            TapSound.parseMap(TapSound.formatMap(edited)),
        )
    }

    // ── 选择列表与强度滑杆 ─────────────────────────────────────

    /**
     * 音色选择列表的序号 ↔ 映射值必须互为逆（`showSoundPicker` 的 `checked` 与返回的 `idx`）。
     *
     * 写错一个 `+1` 不会崩：弹窗会把「不播放」标到别的音上，或者在末位选中时把 `SOUND_COUNT`
     * 当成音效索引写进映射（下游 `getOrElse` 兜底 ⇒ 静默无声）。列表长度是 `SOUND_COUNT + 1`。
     */
    @Test
    fun `音色选择列表的序号与映射值互为逆`() {
        // 全区间（含定义域两侧的坏值 -2 / 17 / 18）：序号恒落在 0..SOUND_COUNT，两个方向都还原
        for (index in -2..TapSound.SOUND_COUNT + 2) {
            val slot = TapSound.pickerIndex(index)
            assertTrue("列表序号越出 0..${TapSound.SOUND_COUNT}：$index → $slot", slot in 0..TapSound.SOUND_COUNT)
            assertEquals(
                "映射值 → 序号 → 映射值必须还原成钳位值：$index",
                TapSound.clampIndex(index),
                TapSound.pickerChoice(slot),
            )
            assertEquals("序号 → 映射值 → 序号必须还原：$slot", slot, TapSound.pickerIndex(TapSound.pickerChoice(slot)))
        }
        assertEquals("「不播放」排在列表末位", TapSound.SOUND_COUNT, TapSound.pickerIndex(TapSound.NONE))
        assertEquals("末位选回不播放", TapSound.NONE, TapSound.pickerChoice(TapSound.SOUND_COUNT))
        assertEquals("越界映射值不产生越界序号", TapSound.SOUND_COUNT - 1, TapSound.pickerIndex(99))
        assertEquals("越界序号钳在定义域内", TapSound.SOUND_COUNT - 1, TapSound.pickerChoice(99))
    }

    /**
     * 强度滑杆的量程、进度与写盘值必须首尾闭合。
     *
     * 三处算式分散在页面里（`SeekBar.max = VOLUME_MAX - VOLUME_MIN`、
     * `progress = clampVolume(v) - VOLUME_MIN`、`clampVolume(VOLUME_MIN + progress)`），
     * 只要有一处少一个 `- VOLUME_MIN`，两端就会差一格或越界；而页面本身跑不了 JVM 单测，
     * 所以闭合关系在这里钉住，**页面有没有按它算**由
     * `RecentFixesRegressionTest.音效强度滑杆的三段算式必须首尾同源` 按源码形态钉住。
     */
    @Test
    fun `音量滑杆的进度与音量互为逆`() {
        val span = TapSound.VOLUME_MAX - TapSound.VOLUME_MIN
        assertTrue("滑杆量程必须为正", span > 0)
        assertEquals("左端可达最小值", TapSound.VOLUME_MIN, TapSound.clampVolume(TapSound.VOLUME_MIN))
        assertEquals("右端可达最大值", TapSound.VOLUME_MAX, TapSound.clampVolume(TapSound.VOLUME_MIN + span))
        for (v in TapSound.VOLUME_MIN - 20..TapSound.VOLUME_MAX + 20) {
            val clamped = TapSound.clampVolume(v)
            val progress = clamped - TapSound.VOLUME_MIN
            assertTrue("progress 越出滑杆量程：v=$v → $progress / $span", progress in 0..span)
            assertEquals("拖动条回到音量必须闭合：v=$v", clamped, TapSound.clampVolume(TapSound.VOLUME_MIN + progress))
        }
    }

    // ── 资产与清单（数据一致性）────────────────────────────────

    /**
     * 音效资产必须齐备：`assetName(i)` 的下标与真实文件一一对上。
     *
     * 少一个文件不会崩、不会报错 —— 只是该组按下去静音，而设置页里它照样列在清单上
     * （`soundLabel` 只读时长表）。多出来的文件则是死资产（随包发出去没人能选）。
     */
    @Test
    fun `音效资产必须齐备且与音效个数一致`() {
        val dir = repoFile("app/src/main/assets/${TapSound.ASSET_DIR}")
        assertTrue("资产目录不存在：$dir", dir.isDirectory)
        val actual = dir.listFiles { f -> f.isFile && f.name.endsWith(".ogg") }?.map { it.name }?.toSet() ?: emptySet()
        val want = (0 until TapSound.SOUND_COUNT).map { TapSound.assetName(it) }.toSet()
        assertEquals("资产与 SOUND_COUNT 对不上（缺的会静音，多的则是死资产）", want, actual)
    }

    /**
     * 时长表必须与**资产实测**一致。
     *
     * 原用例只钉「条数 == SOUND_COUNT 且全为正」，具体数值靠人工复核 —— 而这张表有三份手抄
     * （`SOUND_DURATION_MS` / `gen_manifest.py` 的 `DUR` / 清单表体），换资产时忘同步都不会红，
     * 只会让设置页把「音效 08」标成别的时长。这里直接读 OGG 的 granule position 算真实时长
     * （Vorbis 的采样率在首包的 identification header 里），把「只能人工复核」变成机械对拍。
     */
    @Test
    fun `时长表必须与资产实测一致`() {
        for (i in 0 until TapSound.SOUND_COUNT) {
            val file = repoFile("app/src/main/assets/${TapSound.ASSET_DIR}/${TapSound.assetName(i)}")
            val measured = oggDurationMs(file)
            assertEquals(
                "${file.name} 的实测时长与 SOUND_DURATION_MS[$i] 不一致（换资产后要重算并重跑 gen_manifest.py）",
                TapSound.SOUND_DURATION_MS[i],
                measured,
            )
        }
    }

    /** 清单（`tools/sound_preview/keyboard_sounds_manifest.md`）的时长列必须与时长表同源 */
    @Test
    fun `清单的时长列必须与时长表一致`() {
        val md = repoFile("tools/sound_preview/keyboard_sounds_manifest.md").readText()
        val rows = Regex("""(?m)^\| `(kbd_\d+\.ogg)` \|[^|]*\|[^|]*\| (\d+)ms \|""")
            .findAll(md).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals("清单行数必须与音效个数一致", TapSound.SOUND_COUNT, rows.size)
        for (i in 0 until TapSound.SOUND_COUNT) {
            assertEquals(
                "${TapSound.assetName(i)} 的时长在清单与 SOUND_DURATION_MS 里不一致",
                TapSound.SOUND_DURATION_MS[i],
                rows[TapSound.assetName(i)],
            )
        }
    }

    /**
     * 清单生成器的 `DUR` 也必须与时长表同源。
     *
     * `gen_manifest.py` 只拿 `DUR` 渲染表体再去和清单比，键集校验也不碰时长 —— 只要没人手动跑它，
     * 「生成器里的手抄数漂了」这件事既不红也不响，清单与时长表都还是对的。这条把那第三份手抄
     * 也纳入机械对拍（走 `TestSources.pyCode`，`#` 注释剥掉后再取）。
     */
    @Test
    fun `清单生成器的时长表必须与时长表同源`() {
        val py = TestSources.pyCode(repoFile("tools/sound_preview/gen_manifest.py").readText())
        assertEquals("生成器里应当只有一处 DUR 表", 1, Regex("""DUR\s*=\s*\{""").findAll(py).count())
        val block = TestSources.window(py, "DUR = {", "}")
        val dur = Regex(""""kbd_(\d+)":\s*(\d+)""")
            .findAll(block)
            .associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        assertEquals("生成器的时长表条数必须与音效个数一致", TapSound.SOUND_COUNT, dur.size)
        for (i in 0 until TapSound.SOUND_COUNT) {
            assertEquals(
                "${TapSound.assetName(i)} 的时长在 gen_manifest.py 与 SOUND_DURATION_MS 里不一致",
                TapSound.SOUND_DURATION_MS[i],
                dur[i + 1],
            )
        }
    }

    /** 从测试工作目录（模块目录或仓库根都行）向上找仓库内文件 */
    private fun repoFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("仓库里找不到 $rel（测试工作目录变了？）")
    }

    /** OGG Vorbis 时长（毫秒）：取最后一个 granule position ÷ 首包里的采样率 */
    private fun oggDurationMs(file: File): Int {
        val data = file.readBytes()
        var off = 0
        var lastGranule = 0L
        var rate = 0
        while (off + 27 <= data.size) {
            val i = (off until data.size - 3).firstOrNull {
                data[it] == 'O'.code.toByte() && data[it + 1] == 'g'.code.toByte() &&
                    data[it + 2] == 'g'.code.toByte() && data[it + 3] == 'S'.code.toByte()
            } ?: break
            if (i + 27 > data.size) break
            var granule = 0L
            for (k in 0 until 8) granule = granule or ((data[i + 6 + k].toLong() and 0xFF) shl (8 * k))
            val nseg = data[i + 26].toInt() and 0xFF
            var body = i + 27 + nseg
            var payload = 0
            for (k in 0 until nseg) payload += data[i + 27 + k].toInt() and 0xFF
            if (granule > 0) lastGranule = granule
            if (rate == 0 && payload > 16 && body + 16 < data.size && data[body] == 1.toByte() &&
                String(data, body + 1, 6, Charsets.US_ASCII) == "vorbis"
            ) {
                var r = 0
                for (k in 0 until 4) r = r or ((data[body + 12 + k].toInt() and 0xFF) shl (8 * k))
                rate = r
            }
            off = body + payload
        }
        assertTrue("${file.name} 不是可解析的 OGG Vorbis（采样率 $rate）", rate > 0)
        return Math.round(lastGranule * 1000.0 / rate).toInt()
    }
}
