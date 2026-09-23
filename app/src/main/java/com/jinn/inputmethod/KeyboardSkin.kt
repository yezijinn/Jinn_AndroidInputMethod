package com.jinn.inputmethod

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 键盘皮肤定义与解析（第一期：默认 / 磨砂 / 彩虹 / 极光 / 复古机械）。
 *
 * 约束（改动前先读）：
 *  - **零位图**：皮肤只用色值与渐变参数表达，绘制由 [PinyinKey] 自绘完成 ——
 *    release APK 距 5MiB 上限仅约 21KB 余量，任何贴图/字体都会直接爆表；
 *  - **默认皮肤是全空覆盖**：字段为 null 表示沿用 `R.color.*` 令牌，`KeyTransparency` 0%
 *    时的历史观感必须逐像素保持（本类的 [isDefault] 与 `KeyboardSkinTest` 守卫这一点）；
 *  - **与半透明 / 圆角 / 间隙正交**：皮肤只提供基色与质感参数，面 alpha 仍由
 *    [KeyTransparency] 统一注入（[visualFor] 负责把 alpha 合成进最终色）；
 *  - 皮肤自带色值，不随亮白 / 暗黑主题切换（与键盘外观页的固定极光同思路）；
 *  - 色值一律 8 位 ARGB，且列表中的非默认皮肤要求 alpha = 255（不透明度交给透明度滑杆统一控制）。
 */
data class KeyboardSkin(
    val id: String,
    val label: String,
    /** 键面填充首色；null = `R.color.kb_key` */
    val keyFill: Int? = null,
    /** 键面填充次色：与首色不同即绘制线性渐变；null = 纯色 */
    val keyFill2: Int? = null,
    /** 渐变角度（度，0 = 左→右，90 = 上→下） */
    val gradientAngle: Float = 90f,
    /** 按压态填充；null = 由首色加深推导（第一期末启用渐变时等价于压暗） */
    val keyPressed: Int? = null,
    /** 键面描边宽度（dp，0 = 无描边） */
    val strokeWidthDp: Float = 0f,
    /** 键面描边色；null = 无描边 */
    val strokeColor: Int? = null,
    /** 底边「键帽厚度」（dp，0 = 无）—— 复古机械键的立体感来源 */
    val bottomThicknessDp: Float = 0f,
    /** 底边厚度色；null = 描边色，缺省再退化为首色加深 */
    val bottomThicknessColor: Int? = null,
    /** 字色；null = `R.color.kb_key_text` */
    val glyph: Int? = null,
    /** 双拼提示字色；null = 字色 + alpha 160（现状） */
    val hint: Int? = null,
    /** 红色提示色；null = `R.color.kb_key_hint_red` */
    val hintRed: Int? = null,
    /** 彩虹皮肤：26 键按序取色的起始色相（0~360）；null = 不用逐键取色 */
    val rainbowHue: Float? = null,
    /** 彩虹皮肤：相邻键的色相步长（度） */
    val rainbowStep: Float = 360f / 26f,
    /** 功能键（空格 / 回车 / 符号…）填充；null = `R.color.key_bg` */
    val functionFill: Int? = null,
    /** 功能键描边；null = `R.color.card_stroke` */
    val functionStroke: Int? = null,
    /** 候选栏底色；null = `R.color.kb_candidate_bg` */
    val candidateBar: Int? = null,
    /** 键盘背板底色；null = `R.color.kb_bg` */
    val plate: Int? = null,
    /** 强调色（大写锁定 / 分组标签选中）；null = `R.color.accent` */
    val accent: Int? = null,
) {

    /**
     * 是否为默认皮肤：全部色覆盖为空即沿用历史令牌。
     *
     * 只要有一个覆盖（或启用彩虹取色）就不再是默认 —— 绘制路径据此在
     * 「历史纯色路径」与「皮肤路径」之间二选一。
     */
    val isDefault: Boolean
        get() = keyFill == null && keyFill2 == null && keyPressed == null &&
            strokeColor == null && bottomThicknessColor == null && glyph == null &&
            hint == null && hintRed == null && functionFill == null &&
            functionStroke == null && candidateBar == null && plate == null &&
            accent == null && rainbowHue == null
}

/**
 * 单个键的运行时视觉参数（已按当前面透明度合成完毕）。
 *
 * [PinyinKey] 只读本结构，不再自己读 Prefs 或算 alpha：颜色来源单点收敛在
 * [KeyboardSkins.visualFor]，便于单测与「透明度 × 皮肤」正交性的验证。
 */
data class KeyVisual(
    /** 键面首色（已含 alpha） */
    val face: Int,
    /** 键面次色（已含 alpha）；与 [face] 相等表示纯色 */
    val face2: Int,
    /** 按压态填充（已含 alpha） */
    val pressed: Int,
    /** 渐变角度（度） */
    val gradientAngle: Float,
    /** 描边宽度（px，0 = 无） */
    val strokeWidthPx: Float,
    /** 描边色（已含 alpha） */
    val strokeColor: Int,
    /** 底边厚度（px，0 = 无） */
    val thicknessPx: Float,
    /** 底边厚度色 */
    val thicknessColor: Int,
    /** 字色 */
    val glyph: Int,
    /** 双拼提示字色 */
    val hint: Int,
    /** 红色提示色 */
    val hintRed: Int,
)

/**
 * 皮肤清单与解析（纯函数，见 `KeyboardSkinTest`）。
 *
 * 解析职责：把「皮肤 + 键序号 + 面透明度 + 密度」换算成 [KeyVisual]；
 * 未覆盖的项由调用方传入的默认令牌色兜底（默认皮肤因此与历史逐像素一致）。
 */
object KeyboardSkins {

    const val DEFAULT_ID = "default"

    /** 彩虹皮肤：HSV 饱和度与明度（固定值，保证键面文字对比度达标） */
    private const val RAINBOW_SATURATION = 0.62f
    private const val RAINBOW_VALUE = 0.96f

    /** 默认皮肤：全空覆盖 = 沿用 `R.color.*`（历史观感基线） */
    val DEFAULT = KeyboardSkin(id = DEFAULT_ID, label = "默认")

    /** 磨砂：深灰蓝渐变键面 + 半透明高光描边（模拟磨砂面受光） */
    val FROST = KeyboardSkin(
        id = "frost",
        label = "磨砂",
        keyFill = 0xFF2B3140.toInt(),
        keyFill2 = 0xFF3C455A.toInt(),
        keyPressed = 0xFF20252F.toInt(),
        strokeWidthDp = 0.6f,
        strokeColor = 0x33FFFFFF,
        glyph = 0xFFE8ECF5.toInt(),
        hint = 0x8CE8ECF5.toInt(),
        hintRed = 0xFFFF8A80.toInt(),
        functionFill = 0xFF232833.toInt(),
        functionStroke = 0x33FFFFFF,
        candidateBar = 0xFF1C2029.toInt(),
        plate = 0xFF14171E.toInt(),
        accent = 0xFF5AA9FF.toInt(),
    )

    /** 彩虹：26 键按字母序取色相，整体成谱；字色用深墨以适配高明度键面 */
    val RAINBOW = KeyboardSkin(
        id = "rainbow",
        label = "彩虹",
        rainbowHue = 0f,
        glyph = 0xFF1B1B1F.toInt(),
        hint = 0xB31B1B1F.toInt(),
        hintRed = 0xFFB3141A.toInt(),
        functionFill = 0xFFF2F3F7.toInt(),
        functionStroke = 0x33000000,
        candidateBar = 0xFFF7F8FB.toInt(),
        plate = 0xFFEDEEF3.toInt(),
        accent = 0xFF7C4DFF.toInt(),
    )

    /** 极光：青绿→紫的冷色渐变 + 青色描边（呼应外观页的极光背景） */
    val AURORA = KeyboardSkin(
        id = "aurora",
        label = "极光",
        keyFill = 0xFF14243A.toInt(),
        keyFill2 = 0xFF2A1B4D.toInt(),
        gradientAngle = 45f,
        keyPressed = 0xFF0F1B2C.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x6622D3EE.toInt(),
        glyph = 0xFFE6FBFF.toInt(),
        hint = 0x99E6FBFF.toInt(),
        hintRed = 0xFFFF7AB6.toInt(),
        functionFill = 0xFF1B2A3E.toInt(),
        functionStroke = 0x4D22D3EE,
        candidateBar = 0xFF16223A.toInt(),
        plate = 0xFF0C1424.toInt(),
        accent = 0xFF22D3EE.toInt(),
    )

    /** 复古机械：米白键帽 + 深棕描边 + 2dp 底边厚度 + 深墨字（仿打字机键） */
    val RETRO = KeyboardSkin(
        id = "retro",
        label = "复古机械",
        keyFill = 0xFFEDE6D6.toInt(),
        keyFill2 = 0xFFDFD7C4.toInt(),
        keyPressed = 0xFFCFC6B0.toInt(),
        strokeWidthDp = 1f,
        strokeColor = 0xFF8C8471.toInt(),
        bottomThicknessDp = 2f,
        bottomThicknessColor = 0xFFB3A98F.toInt(),
        glyph = 0xFF2B2A26.toInt(),
        hint = 0x992B2A26.toInt(),
        hintRed = 0xFFA3352B.toInt(),
        functionFill = 0xFFE4DCC9.toInt(),
        functionStroke = 0xFF8C8471.toInt(),
        candidateBar = 0xFFF4EFE2.toInt(),
        plate = 0xFFC9C1AB.toInt(),
        accent = 0xFFB07D2B.toInt(),
    )

    /** 全部皮肤（顺序即选择器展示顺序） */
    val ALL: List<KeyboardSkin> = listOf(DEFAULT, FROST, RAINBOW, AURORA, RETRO)

    /** 按 id 取皮肤：未知 / 空值一律回退默认（脏配置不抛异常、不改变观感） */
    fun byId(id: String?): KeyboardSkin = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /**
     * 键面首色：彩虹皮肤按 [keyIndex] 取色相，其余皮肤用 [keyFill]，未覆盖时回退 [fallback]。
     *
     * [keyIndex] 为 26 键字母序（0 = a），负数表示无索引（如分号键），此时取起始色相。
     */
    fun keyFill(skin: KeyboardSkin, keyIndex: Int, fallback: Int): Int {
        val hue = skin.rainbowHue ?: return skin.keyFill ?: fallback
        return rainbowColor(hue, keyIndex)
    }

    /** 键面次色：彩虹皮肤用相邻色相（形成逐键微渐变），其余同上；等于首色即纯色 */
    fun keyFill2(skin: KeyboardSkin, keyIndex: Int, first: Int): Int {
        val hue = skin.rainbowHue ?: return skin.keyFill2 ?: first
        return rainbowColor(hue + skin.rainbowStep * 0.5f, keyIndex)
    }

    /** 按压态：未覆盖时由首色压暗约 12%（保持皮肤观感一致） */
    fun keyPressed(skin: KeyboardSkin, first: Int): Int = skin.keyPressed ?: darken(first, 0.88f)

    /** 底边厚度色：未覆盖时依次退化到描边色、首色压暗 */
    fun thicknessColor(skin: KeyboardSkin, first: Int): Int =
        skin.bottomThicknessColor ?: skin.strokeColor ?: darken(first, 0.78f)

    /** 色相取色（HSV → ARGB） */
    private fun rainbowColor(startHue: Float, keyIndex: Int): Int {
        val idx = keyIndex.coerceAtLeast(0)
        return hsvToColor(startHue + idx * (360f / 26f), RAINBOW_SATURATION, RAINBOW_VALUE)
    }

    /**
     * HSV → ARGB（纯 Kotlin 实现）。
     *
     * 刻意不用 `android.graphics.Color.HSVToColor`：本对象是纯逻辑，单测跑在 JVM 上，
     * 引用 Android 图形类会在测试里抛未实现异常。
     */
    private fun hsvToColor(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f + 360f) % 360f) / 60f
        val c = value * saturation.coerceIn(0f, 1f)
        val x = c * (1f - abs(h % 2f - 1f))
        val m = value.coerceIn(0f, 1f) - c
        val (r1, g1, b1) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float): Int = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
    }

    /** 按比例压暗（保持 alpha 不变） */
    fun darken(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val r = ((color shr 16) and 0xFF) * f
        val g = ((color shr 8) and 0xFF) * f
        val b = (color and 0xFF) * f
        return (color and 0xFF000000.toInt()) or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)
    }

    /** 给颜色套 0..1 不透明度：只改 alpha 通道（与 [KeyTransparency.withAlpha] 同口径） */
    fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * 解析一个键的运行时视觉参数。
     *
     * @param faceAlpha 当前面不透明度（来自 [KeyTransparency.surfaceAlpha]，只作用于「面」，
     *                  字色与提示色原样保留 —— 与半透明键盘的既有约定一致）
     * @param density   屏幕密度（dp → px）
     */
    fun visualFor(
        skin: KeyboardSkin,
        keyIndex: Int,
        faceAlpha: Float,
        density: Float,
        fallbackFace: Int,
        fallbackPressed: Int,
        fallbackGlyph: Int,
        fallbackHint: Int,
        fallbackHintRed: Int,
    ): KeyVisual {
        val first = keyFill(skin, keyIndex, fallbackFace)
        val second = keyFill2(skin, keyIndex, first)
        // 未覆盖按压/提示色时：默认皮肤沿用历史令牌色，皮肤路径由首色/字色推导
        val pressed = skin.keyPressed
            ?: if (skin.keyFill == null) fallbackPressed else darken(first, 0.88f)
        val glyph = skin.glyph ?: fallbackGlyph
        val hint = skin.hint
            ?: if (skin.glyph == null) fallbackHint else withAlpha(glyph, 160f / 255f)
        return KeyVisual(
            face = withAlpha(first, faceAlpha),
            face2 = withAlpha(second, faceAlpha),
            pressed = withAlpha(pressed, faceAlpha),
            gradientAngle = skin.gradientAngle,
            strokeWidthPx = skin.strokeWidthDp * density,
            strokeColor = skin.strokeColor?.let { withAlpha(it, faceAlpha) } ?: 0,
            thicknessPx = skin.bottomThicknessDp * density,
            thicknessColor = withAlpha(thicknessColor(skin, first), faceAlpha),
            glyph = glyph,
            hint = hint,
            hintRed = skin.hintRed ?: fallbackHintRed,
        )
    }

    /**
     * 定义域自检（单测守卫，不在运行期调用）。
     *
     * 返回违规描述列表，空列表表示合法。判据：
     *  - id / label 非空，且非默认皮肤必须提供 alpha = 255 的色值（不透明度由滑杆统一控制）；
     *  - 渐变角度在 [0, 360)，描边与厚度在 [0, 4]dp；
     *  - 彩虹起始色相在 [0, 360) 且步长 > 0；
     *  - 首色与次色不同（相同则「渐变」实际是纯色，属配置错误）。
     */
    fun issues(skin: KeyboardSkin): List<String> {
        val out = mutableListOf<String>()
        if (skin.id.isBlank()) out += "id 为空"
        if (skin.label.isBlank()) out += "label 为空"
        if (skin.gradientAngle < 0f || skin.gradientAngle >= 360f) out += "gradientAngle 越界: ${skin.gradientAngle}"
        if (skin.strokeWidthDp < 0f || skin.strokeWidthDp > 4f) out += "strokeWidthDp 越界: ${skin.strokeWidthDp}"
        if (skin.bottomThicknessDp < 0f || skin.bottomThicknessDp > 4f) out += "bottomThicknessDp 越界: ${skin.bottomThicknessDp}"
        skin.rainbowHue?.let {
            if (it < 0f || it >= 360f) out += "rainbowHue 越界: $it"
            if (skin.rainbowStep <= 0f) out += "rainbowStep 必须为正: ${skin.rainbowStep}"
        }
        if (!skin.isDefault) {
            // 填充/底色（面上没有叠加语义）必须不透明：不透明度统一交给透明度滑杆控制；
            // 描边与字色允许自带 alpha（磨砂高光描边、半透明提示色是有意设计）。
            listOf(
                "keyFill" to skin.keyFill, "keyFill2" to skin.keyFill2,
                "keyPressed" to skin.keyPressed, "bottomThicknessColor" to skin.bottomThicknessColor,
                "functionFill" to skin.functionFill, "candidateBar" to skin.candidateBar,
                "plate" to skin.plate, "accent" to skin.accent,
            ).forEach { (name, color) ->
                color?.let { if ((it ushr 24) and 0xFF != 0xFF) out += "$name 需为不透明色: ${String.format("#%08X", it)}" }
            }
            if (skin.keyFill != null && skin.keyFill2 == skin.keyFill) {
                out += "keyFill 与 keyFill2 相同（渐变退化为纯色，应留空 keyFill2）"
            }
            if (skin.strokeColor != null && skin.strokeWidthDp <= 0f) out += "有描边色但 strokeWidthDp = 0"
            if (skin.strokeColor == null && skin.strokeWidthDp > 0f) out += "有描边宽度但未给 strokeColor"
        }
        return out
    }
}
