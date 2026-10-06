package com.jinn.inputmethod

import java.util.Locale

/**
 * 敲击音效反馈的**纯 JVM 定义**：按键分组、asset 清单、默认映射、震动档位与解析钳位。
 *
 * 为什么单独成一个文件：分组编号、音效个数、默认映射、震动档定义域这些边界会被
 * [KeyFeedback]（播放/震动）、[TapSoundActivity]（设置页）、[Prefs]（持久化）与单测共用。
 * 任何一处写死数字，改一个音效就要全仓找；集中在这里之后边界只有一处定义。
 *
 * 不依赖任何 Android API，可直接单测（见 TapSoundTest）—— 尤其是 [parseMap]，
 * 它要挡住**导入配置文件**带来的脏数据（越界索引会让数组访问直接崩）。
 */
internal object TapSound {

    // ── 按键分组（2026-10-05 定，6 组）────────────────────────────
    //
    // ⚠ 分组维度是「按下瞬间解析出的**角色**」，不是物理键位：26 个键在字母 / 数字 / 符号 /
    // 密码四态下是同一批 View，同一个键在数字层是「1」、在符号层是「，」。
    // 若按物理键分组，打字与输入数字会响同一个音，分组就失去意义。

    /** 文字输入：26 键在字母层（含中/英）、分号键、搜索面板输入 */
    const val G_TEXT = 0

    /** 数字：数字层 26 键、密码模式数字条 */
    const val G_DIGIT = 1

    /** 符号 / 标点：符号层 26 键、逗号键、句号键 */
    const val G_SYMBOL = 2

    /** 删除 / 清空：退格键 + 候选栏清空 ✕（默认 kbd_15，警报感，2026-10-05 定） */
    const val G_ERASE = 3

    /** 确认：空格键、回车键 */
    const val G_CONFIRM = 4

    /** 功能 / 切换：中英、大写、符号层入口、数字层入口、面板与功能键 */
    const val G_FUNC = 5

    /** 分组总数（[Prefs.tapSoundMap] 的串长度与它必须一致） */
    const val GROUP_COUNT = 6

    /** 设置页逐行显示的分组名（顺序即 [G_TEXT]…[G_FUNC]） */
    val GROUP_LABELS = arrayOf("文字输入", "数字", "符号标点", "删除清空", "确认键", "功能切换")

    // ── 音效清单（真实录音，放 app/src/main/assets/sounds/keyboard/）────────

    /** asset 目录（相对 assets 根） */
    const val ASSET_DIR = "sounds/keyboard"

    /**
     * 音效个数（kbd_01.ogg … kbd_16.ogg，按时长升序命名）。
     *
     * 来源与授权见 `tools/sound_preview/keyboard_sounds_manifest.md`：
     * Freesound 条目为 CC0，Mixkit 条目为免费商用音效。
     * ⚠ 增删资产后必须同步三处：本常量、[SOUND_DURATION_MS]、清单里的文件名 ——
     * 编号必须连续（[assetName] 靠序号拼文件名），少一个就要整体重编号。
     */
    const val SOUND_COUNT = 16

    /** 「这一组不播放声音」的哨兵值（与合法索引 0..SOUND_COUNT-1 区分开） */
    const val NONE = -1

    /** 索引 → asset 名（0 → `kbd_01.ogg`）。固定 Locale.US，避免区域设置影响数字格式 */
    fun assetName(index: Int): String =
        String.format(Locale.US, "kbd_%02d.ogg", index + 1)

    /**
     * 每个音效的时长（毫秒），索引与 [assetName] 同源。
     *
     * 只用于设置页把「音效 8」显示成「音效 08 · 122ms」—— 用户在列表里不点开也能分辨长短
     * （短 tick 与长 thock 的用途完全不同）。
     *
     * ⚠ 这是**描述性元数据**：真值在 `tools/sound_preview/keyboard_sounds_manifest.md` 与资产本身，
     * 替换资产时必须回来重算。条数由 `TapSoundTest` 钉住（少一条/多一条会红），
     * 但**内容**对不上只能人工复核 —— 别把它当成音效的事实来源。
     */
    val SOUND_DURATION_MS = intArrayOf(
        60, 66, 67, 73, 79, 109, 114, 122, 135, 138, 152, 159, 171, 183, 205, 210,
    )

    /**
     * 设置页显示名：`音效 08 · 122ms`。
     *
     * 序号从 1 开始（内部索引从 0 开始），两位补零让同一列的数字对齐；
     * [NONE] 由调用方自行显示成「不播放」，这里返回空串。
     */
    fun soundLabel(index: Int): String {
        // 只接受合法索引：越界一律回空串，绝不拼出一个「看起来合法」的假标签（NONE 与越界都走这里）
        if (index !in 0 until SOUND_COUNT) return ""
        val ms = SOUND_DURATION_MS.getOrElse(index) { 0 }
        return String.format(Locale.US, "音效 %02d · %dms", index + 1, ms)
    }

    // ── 默认映射（用户未改动时各组用哪个音）────────────────────────
    //
    // 挑选依据：时长与角色匹配（数字/符号要短促、确认要偏重），音色统一在机械键盘范畴内。
    //  文字=08(122ms) 数字=04(73ms) 符号=01(60ms) 删除=15(185ms,警报) 确认=14(183ms) 功能=03(67ms)
    val DEFAULT_MAP = intArrayOf(7, 3, 0, 14, 13, 2)

    // ── 音效强度 ───────────────────────────────────────────────

    const val VOLUME_MIN = 0
    const val VOLUME_MAX = 100

    /** 默认 70%：系统音效（STREAM_SYSTEM）本身不可由键盘调节，靠它做独立音量 */
    const val VOLUME_DEFAULT = 70

    fun clampVolume(value: Int): Int = value.coerceIn(VOLUME_MIN, VOLUME_MAX)

    /**
     * 强度显示文案（`70%`）。
     *
     * 单独成函数而不是让页面拼字符串：拼接写法会触发 `SetTextI18n` 告警，
     * 而本仓的文案刻意走代码下发（`strings.xml` 默认不改动），所以给它一个唯一的格式化出口 ——
     * 与 [KeyAppearance.formatDp] 同款做法。
     */
    fun volumeLabel(percent: Int): String = String.format(Locale.US, "%d%%", clampVolume(percent))

    // ── 震动档位（4 档，2026-10-05 定）────────────────────────────

    const val VIB_OFF = 0
    const val VIB_WEAK = 1
    const val VIB_MEDIUM = 2
    const val VIB_STRONG = 3

    const val VIB_MAX = VIB_STRONG
    const val VIB_DEFAULT = VIB_MEDIUM

    /** 设置页逐档显示的名（顺序即 [VIB_OFF]…[VIB_STRONG]） */
    val VIB_LABELS = arrayOf("关闭", "弱", "中", "强")

    fun clampVibrationTier(tier: Int): Int = tier.coerceIn(VIB_OFF, VIB_MAX)

    /**
     * 档位 →（时长 ms, 振幅 0..255）。
     *
     * 振幅只有 `Vibrator.hasAmplitudeControl()` 为真时才有意义，
     * 不支持振幅的设备会退化成「只用时长区分」——[KeyFeedback] 负责这个分支。
     */
    fun vibrationSpec(tier: Int): Pair<Long, Int> = when (clampVibrationTier(tier)) {
        VIB_WEAK -> 10L to 60
        VIB_MEDIUM -> 15L to 120
        VIB_STRONG -> 20L to 200
        else -> 0L to 0
    }

    /**
     * 是否为「双段触感」的分组：删除 / 清空用两下短震，
     * 与它的警报音对齐，让「删除」在纯震动（静音）场景下也能被听出来是删除。
     */
    fun isDoublePulse(group: Int): Boolean = group == G_ERASE

    // ── 映射串解析（脏数据防护）──────────────────────────────────

    /**
     * 解析 `"7,3,0,14,13,2"` 形式的映射串。
     *
     * 任何异常输入都**不抛异常**：长度不符 / 非数字 → 整体回落 [DEFAULT_MAP]；
     * 单项越界 → 钳到 `[NONE, SOUND_COUNT-1]`（负数变「不播放」）。
     * 备份导入会把字符串原样写回来，没有这层防护就是一次越界崩溃。
     */
    fun parseMap(raw: String?): IntArray {
        if (raw.isNullOrBlank()) return DEFAULT_MAP.copyOf()
        val parts = raw.split(',')
        if (parts.size != GROUP_COUNT) return DEFAULT_MAP.copyOf()
        val out = IntArray(GROUP_COUNT)
        for (i in 0 until GROUP_COUNT) {
            val v = parts[i].trim().toIntOrNull() ?: return DEFAULT_MAP.copyOf()
            out[i] = v.coerceIn(NONE, SOUND_COUNT - 1)
        }
        return out
    }

    /** 映射数组 → 落盘串（越界值同样被钳位，保证写出去的永远合法） */
    fun formatMap(map: IntArray): String = (0 until GROUP_COUNT).joinToString(",") { i ->
        val v = map.getOrElse(i) { DEFAULT_MAP[i] }
        v.coerceIn(NONE, SOUND_COUNT - 1).toString()
    }

    /**
     * 试听该用哪个音：优先 [G_TEXT] 当前的音，该组是「不播放」时改用手上第一个可用的。
     *
     * 为什么回退：强度滑杆松手时试听的就是「文字输入」组的音。若这一组被设成「不播放」，
     * 试听会完全没有声音，而用户此刻的意图是「听一下现在多大声」—— 把无声当成
     * 「强度坏了」是最容易发生的误判。六组全部设成「不播放」时才真的没有可听的音。
     */
    fun previewSound(map: IntArray): Int {
        if (map.getOrElse(G_TEXT) { NONE } != NONE) return map[G_TEXT]
        return map.firstOrNull { it != NONE } ?: NONE
    }

    /**
     * 反馈节流：普通键共用一份时间戳，删除 / 清空单独一份。
     *
     * 为什么删除组要单独记：它的间隔阈值比普通键大一个量级，两份共用一个时间戳时，
     * 它的基准会变成「上一次**任意**分组」的触发时刻 —— 「打字后马上按退格」这一下会被
     * 上一个普通键挡掉，而删除档用的正是辨识度最高的警报音，也是最不该被吞的一档。
     *
     * 纯逻辑、无 Android 依赖，可直接单测（见 TapSoundTest）。时间戳以 `-1` 表示「从未触发」，
     * 不取 `Long.MIN_VALUE` 是为了避开 `now - last` 的溢出。
     */
    class Throttle(private val genericGapMs: Long, private val eraseGapMs: Long) {

        private var lastGeneric = -1L
        private var lastErase = -1L

        /** 放行返回 true 并记下时刻；被节流返回 false，且不动任何状态 */
        fun allow(group: Int, now: Long): Boolean {
            val erase = group == G_ERASE
            val last = if (erase) lastErase else lastGeneric
            val gap = if (erase) eraseGapMs else genericGapMs
            if (last >= 0L && now - last < gap) return false
            if (erase) lastErase = now else lastGeneric = now
            return true
        }

        /** 释放时复位：重建后的第一次反馈不该被上一轮的残值挡掉 */
        fun reset() {
            lastGeneric = -1L
            lastErase = -1L
        }
    }
}
