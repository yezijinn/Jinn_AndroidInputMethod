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

    /** 删除 / 清空：退格键 + 候选栏清空 ✕。声音与其余五组相同，辨识靠 [isDoublePulse] 的双段触感 */
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
    // 六组统一用音效 10（`kbd_10.ogg`，138ms，onsets 2 = click + 触底双瞬态）。选它而不是
    // 「每组配一个贴合角色的音」：一条声音对全部按键，敲起来更一致，也不会出现某一组偏尖、
    // 某一组偏闷的分层感。分组信息只留在震动波形上 —— 删除 / 清空用双段触感（[isDoublePulse]），
    // 静音场景下那是删除唯一的辨识线索。**已改过音色的用户各组不同**，此时声音本身就分得开，双段是第二重线索。
    val DEFAULT_MAP = intArrayOf(9, 9, 9, 9, 9, 9)

    // ── 音效强度 ───────────────────────────────────────────────

    const val VOLUME_MIN = 0
    const val VOLUME_MAX = 100

    /** 默认 50%：系统音效（STREAM_SYSTEM）本身不可由键盘调节，靠它做独立音量 */
    const val VOLUME_DEFAULT = 50

    fun clampVolume(value: Int): Int = value.coerceIn(VOLUME_MIN, VOLUME_MAX)

    /**
     * 强度显示文案（`50%`）。
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
     * 是否为「双段触感」的分组：删除 / 清空用两下短震。
     *
     * 未改过音色的用户六组同音，此时**双段是删除唯一的辨识线索** —— 静音（不播声）场景下它就是
     * 「刚才那一下是删除」的唯一依据。改这个判据前先想清楚这一点。
     */
    fun isDoublePulse(group: Int): Boolean = group == G_ERASE

    /**
     * 双段触感两段之间的停顿（弱 30ms / 中 30ms / 强 40ms）。
     *
     * 两条约束在这里互相拉扯，改动前先看清：
     *
     * ① 停顿要**够大**才能听出是两下。20ms 配 10ms 脉冲已在人手指可辨的边缘（弱档因此
     *    退化成「一次带凹陷的震动」），所以下限取 30ms —— 这一条是本次修掉的实际问题。
     * ② 停顿**不能太大**。`Vibrator.vibrate(effect)` 是替换语义，后一次调用取消在途波形，
     *    而删除键不占用普通通道的时间戳（`Throttle` 对删除组只写 `lastErase`），下一按键
     *    可以在 +0ms 就触发 ⇒ 间隔越大，后半段落在越靠后的位置、被削掉的窗口就越大。
     *
     * 两条一起看就是：给一个够用的下限，再按脉冲宽度微调，且**总时长封顶**在能保住第二段
     * 的范围内。强档脉冲本身最宽（20ms），停顿反而只比下限多 10ms。
     *
     * 弱档能否听出两下要实测波形才能定论，这里只保证下限不再被比例吃掉。
     */
    fun erasePulseGap(pulseMs: Long): Long =
        ERASE_PULSE_GAP_MIN.coerceAtLeast((pulseMs * 2).coerceAtMost(ERASE_PULSE_GAP_MAX))

    /** 停顿下限：低于它弱档听不出两下 */
    private const val ERASE_PULSE_GAP_MIN = 30L

    /** 停顿上限：再大后半段就容易被下一键截断（替换语义，见上） */
    private const val ERASE_PULSE_GAP_MAX = 40L

    // ── 映射串解析（脏数据防护）──────────────────────────────────

    /**
     * 解析 `"7,3,0,14,13,2"` 形式的映射串。**不抛异常**，任何脏数据都收敛到合法值：
     *
     * - 串为空 → 整体回落 [DEFAULT_MAP]
     * - 多余字段忽略、缺失字段留默认（不会因为多一个逗号把六组自定义一起清空）
     * - 项数超长**且**出现空字段 → 整体回落 [DEFAULT_MAP]：空位右侧的字段无法判断是对齐在
     *   分组序号上还是顺延过来的，按位读会把六个值整体后移一位
     * - 单项非数字 → 该项留默认，**不牵连其余五项**
     * - 单项负数（连 [NONE] 之外的值）→「不播放」
     * - 单项上界外 → 回落该项默认音
     *
     * 越界为什么不钳到末位：音效下架会让 [SOUND_COUNT] 变小，于是**旧版本里合法的索引变成
     * 越界**。钳到末位会把用户精挑的音色静默换成另一个（而读写两侧都过本函数，落盘串随即被
     * 覆写、原值不可恢复）；回落默认音至少是条明确规则，且该组没被改过时它的默认音本就是
     * 用户自己的选择。
     *
     * 末尾的 `coerceIn` 不是多余的：将来改小 [SOUND_COUNT] 而忘了同步 [DEFAULT_MAP] 时，
     * 越界值会从这里原样漏出去，而调用方对越界索引的处理并不一致（有的静音、有的显示空白）。
     */
    fun parseMap(raw: String?): IntArray {
        if (raw.isNullOrBlank()) return DEFAULT_MAP.copyOf()
        val parts = raw.split(',')
        // 串形如 ",7,3,0,14,13,2" 时按位读会解出 9,7,3,0,14,13 —— 六个值整体后移一位，
        // 而页面照常显示「音效 XX · NNNms」，看不出错。本应用写不出这种串（formatMap 永远写
        // 6 项），来源只能是外部，与其猜不如整体回落。
        if (parts.size > GROUP_COUNT && parts.any { it.isBlank() }) return DEFAULT_MAP.copyOf()
        val out = DEFAULT_MAP.copyOf()
        for (i in 0 until minOf(parts.size, GROUP_COUNT)) {
            val v = parts[i].trim().toIntOrNull() ?: continue
            out[i] = when {
                v in 0 until SOUND_COUNT -> v
                v >= SOUND_COUNT -> DEFAULT_MAP[i].coerceIn(NONE, SOUND_COUNT - 1)
                else -> NONE
            }
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
     * 上一个普通键挡掉，而删除是唯一有专属波形的一档（双段触感），也是最不该被吞的一档。
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
