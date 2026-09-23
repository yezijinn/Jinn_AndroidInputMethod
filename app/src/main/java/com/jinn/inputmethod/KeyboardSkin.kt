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
    /**
     * 功能键与候选区的主文字 / 图标色；null = `R.color.text_primary`。
     *
     * 功能键（空格 / 回车 / 大写 / 删除）、候选词、符号分组标签、候选项、候选栏按钮、方向键
     * 的「面」都由本皮肤接管；若文字仍用主题色，亮白主题下会出现「深底 + 深字」撞色
     * （真机实测：磨砂 / 极光的深色功能键上文字不可读），因此必须一并覆盖。
     */
    val functionGlyph: Int? = null,
    /** 功能键次要文字（空格键顶部小字、页码、候选提示）；null = `R.color.text_secondary`，允许带 alpha */
    val functionHint: Int? = null,
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
            accent == null && rainbowHue == null &&
            functionGlyph == null && functionHint == null
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

    /**
     * 新用户的初始皮肤（用户 2026-09-23 指定「紫晶」）。
     *
     * 与 [DEFAULT_ID] 刻意分开，两者语义不同：
     *  - [DEFAULT_ID] 是「全空覆盖 + 沿用历史令牌」的基线皮肤（[DEFAULT]），也是 `byId` 对未知值的回退点；
     *  - [INITIAL_ID] 只是 `Prefs.keyboardSkinId` 的缺省值，决定**从未选过皮肤**的用户看到哪套皮肤。
     * 两者合并会让「坏值回退」也落到紫晶上，并破坏清单的 id 唯一性（[DEFAULT] 与紫晶会同 id）。
     */
    const val INITIAL_ID = "amethyst"

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
        functionGlyph = 0xFFE8ECF5.toInt(),
        functionHint = 0x99E8ECF5.toInt(),
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
        functionGlyph = 0xFF1B1B1F.toInt(),
        functionHint = 0xB31B1B1F.toInt(),
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
        functionGlyph = 0xFFE6FBFF.toInt(),
        functionHint = 0x99E6FBFF.toInt(),
        functionFill = 0xFF1B2A3E.toInt(),
        functionStroke = 0x4D22D3EE,
        candidateBar = 0xFF16223A.toInt(),
        plate = 0xFF0C1424.toInt(),
        accent = 0xFF22D3EE.toInt(),
    )

    /** 复古机械：米白键帽 + 深棕描边 + 2dp 底边厚度 + 深墨字（仿打字机键） */
    val RETRO = KeyboardSkin(
        id = "retro",
        label = "复古",
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
        functionGlyph = 0xFF2B2A26.toInt(),
        functionHint = 0x992B2A26.toInt(),
        functionFill = 0xFFE4DCC9.toInt(),
        functionStroke = 0xFF8C8471.toInt(),
        candidateBar = 0xFFF4EFE2.toInt(),
        plate = 0xFFC9C1AB.toInt(),
        accent = 0xFFB07D2B.toInt(),
    )

    /** 暗黑：近黑键面 + 冷白字（夜间低亮度；不用纯黑，避免键面与背板糊成一片） */
    val DARK = KeyboardSkin(
        id = "dark",
        label = "暗黑",
        keyFill = 0xFF1A1A1F.toInt(),
        keyFill2 = 0xFF26262E.toInt(),
        keyPressed = 0xFF121216.toInt(),
        strokeWidthDp = 0.5f,
        strokeColor = 0x22FFFFFF,
        glyph = 0xFFEDEDF2.toInt(),
        hint = 0x8CEDEDF2.toInt(),
        hintRed = 0xFFFF6B6B.toInt(),
        functionGlyph = 0xFFEDEDF2.toInt(),
        functionHint = 0x8CEDEDF2.toInt(),
        functionFill = 0xFF141418.toInt(),
        functionStroke = 0x22FFFFFF,
        candidateBar = 0xFF0E0E12.toInt(),
        plate = 0xFF050506.toInt(),
        accent = 0xFF8AB4F8.toInt(),
    )

    /** 霓虹：黑底 + 洋红发光描边，功能键换青色描边区分主次（夜间街景气质） */
    val NEON = KeyboardSkin(
        id = "neon",
        label = "霓虹",
        keyFill = 0xFF0B0B14.toInt(),
        keyFill2 = 0xFF1A1030.toInt(),
        gradientAngle = 45f,
        keyPressed = 0xFF07070E.toInt(),
        strokeWidthDp = 1f,
        strokeColor = 0x99FF2ED1.toInt(),
        bottomThicknessDp = 1.5f,
        bottomThicknessColor = 0xFFFF2ED1.toInt(),
        glyph = 0xFFF5EFFF.toInt(),
        hint = 0x99F5EFFF.toInt(),
        hintRed = 0xFFFF3B6B.toInt(),
        functionGlyph = 0xFFEAF9FF.toInt(),
        functionHint = 0x99EAF9FF.toInt(),
        functionFill = 0xFF120B1E.toInt(),
        functionStroke = 0x6600E5FF,
        candidateBar = 0xFF0D0818.toInt(),
        plate = 0xFF06040C.toInt(),
        accent = 0xFFFF2ED1.toInt(),
    )

    /** 樱花：浅粉渐变 + 深枣字（浅色系，日光下也保持可读） */
    val SAKURA = KeyboardSkin(
        id = "sakura",
        label = "樱花",
        keyFill = 0xFFFFF3F6.toInt(),
        keyFill2 = 0xFFFFE1EA.toInt(),
        keyPressed = 0xFFF6CFDA.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x33B4636F,
        glyph = 0xFF4A2B33.toInt(),
        hint = 0x994A2B33.toInt(),
        hintRed = 0xFFC2185B.toInt(),
        functionGlyph = 0xFF4A2B33.toInt(),
        functionHint = 0x994A2B33.toInt(),
        functionFill = 0xFFFCE4EC.toInt(),
        functionStroke = 0x33B4636F,
        candidateBar = 0xFFFFF7F9.toInt(),
        plate = 0xFFF7DCE4.toInt(),
        accent = 0xFFE04A78.toInt(),
    )

    /** 海洋：深青蓝渐变 + 浅水蓝字（冷色深色系，介于极光与暗黑之间的低饱和选择） */
    val OCEAN = KeyboardSkin(
        id = "ocean",
        label = "海洋",
        keyFill = 0xFF0E2A3F.toInt(),
        keyFill2 = 0xFF0F3D57.toInt(),
        keyPressed = 0xFF0A2132.toInt(),
        strokeWidthDp = 0.6f,
        strokeColor = 0x4D4FC3F7,
        glyph = 0xFFE1F5FE.toInt(),
        hint = 0x99E1F5FE.toInt(),
        hintRed = 0xFFFF8A65.toInt(),
        functionGlyph = 0xFFE1F5FE.toInt(),
        functionHint = 0x99E1F5FE.toInt(),
        functionFill = 0xFF0B2434.toInt(),
        functionStroke = 0x334FC3F7,
        candidateBar = 0xFF0A1F2E.toInt(),
        plate = 0xFF061520.toInt(),
        accent = 0xFF4FC3F7.toInt(),
    )

    /** 薄荷：浅薄荷渐变 + 墨绿字（浅色系，绿色调） */
    val MINT = KeyboardSkin(
        id = "mint",
        label = "薄荷",
        keyFill = 0xFFE8F7EF.toInt(),
        keyFill2 = 0xFFD2EFE0.toInt(),
        keyPressed = 0xFFBFE3D0.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x33528A6E,
        glyph = 0xFF1F4636.toInt(),
        hint = 0x991F4636.toInt(),
        hintRed = 0xFFC0392B.toInt(),
        functionGlyph = 0xFF1F4636.toInt(),
        functionHint = 0x991F4636.toInt(),
        functionFill = 0xFFDCF2E6.toInt(),
        functionStroke = 0x33528A6E,
        candidateBar = 0xFFF1FAF4.toInt(),
        plate = 0xFFC8E6D4.toInt(),
        accent = 0xFF2E9E6B.toInt(),
    )

    /** 石墨：中性深灰 + 银灰蓝强调（商务风，低饱和、不挑背景） */
    val GRAPHITE = KeyboardSkin(
        id = "graphite",
        label = "石墨",
        keyFill = 0xFF2A2D33.toInt(),
        keyFill2 = 0xFF383C44.toInt(),
        keyPressed = 0xFF22252A.toInt(),
        strokeWidthDp = 0.5f,
        strokeColor = 0x1FFFFFFF,
        glyph = 0xFFE6E8EC.toInt(),
        hint = 0x99E6E8EC.toInt(),
        hintRed = 0xFFFF7A6B.toInt(),
        functionGlyph = 0xFFE6E8EC.toInt(),
        functionHint = 0x99E6E8EC.toInt(),
        functionFill = 0xFF23262B.toInt(),
        functionStroke = 0x1FFFFFFF,
        candidateBar = 0xFF1E2126.toInt(),
        plate = 0xFF131519.toInt(),
        accent = 0xFF9AA7B8.toInt(),
    )

    /** 日落：深紫→暖橙渐变 + 暖白字（黄昏色调） */
    val SUNSET = KeyboardSkin(
        id = "sunset",
        label = "日落",
        keyFill = 0xFF4A2148.toInt(),
        keyFill2 = 0xFF8A4A2A.toInt(),
        gradientAngle = 45f,
        keyPressed = 0xFF361A3E.toInt(),
        strokeWidthDp = 0.6f,
        strokeColor = 0x66FFA45C,
        glyph = 0xFFFFF4EB.toInt(),
        hint = 0x99FFF4EB.toInt(),
        hintRed = 0xFFFF6B8A.toInt(),
        functionGlyph = 0xFFFFF4EB.toInt(),
        functionHint = 0x99FFF4EB.toInt(),
        functionFill = 0xFF341A42.toInt(),
        functionStroke = 0x4DFFA45C,
        candidateBar = 0xFF2C1638.toInt(),
        plate = 0xFF1E0F27.toInt(),
        accent = 0xFFFFA45C.toInt(),
    )

    /** 岩浆：深红渐变 + 暖黄字，橙色底边厚度（熔岩感）；卷舌提示用亮黄而非红，红底上才看得清 */
    val MAGMA = KeyboardSkin(
        id = "magma",
        label = "岩浆",
        keyFill = 0xFF3B1210.toInt(),
        keyFill2 = 0xFF6A1F12.toInt(),
        keyPressed = 0xFF2E0D0B.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x66FF7043,
        bottomThicknessDp = 1f,
        bottomThicknessColor = 0xFFFF7043.toInt(),
        glyph = 0xFFFFE9D6.toInt(),
        hint = 0x99FFE9D6.toInt(),
        hintRed = 0xFFFFD54F.toInt(),
        functionGlyph = 0xFFFFE9D6.toInt(),
        functionHint = 0x99FFE9D6.toInt(),
        functionFill = 0xFF2E0F0C.toInt(),
        functionStroke = 0x4DFF7043,
        candidateBar = 0xFF260B09.toInt(),
        plate = 0xFF170605.toInt(),
        accent = 0xFFFF7043.toInt(),
    )

    /** 墨玉：墨绿渐变 + 玉白字 + 玉色微光描边（低亮度绿，久看不刺眼） */
    val JADE = KeyboardSkin(
        id = "jade",
        label = "墨玉",
        keyFill = 0xFF12241E.toInt(),
        keyFill2 = 0xFF1B3A2E.toInt(),
        keyPressed = 0xFF0E1D18.toInt(),
        strokeWidthDp = 0.6f,
        strokeColor = 0x4D7FD1A8,
        glyph = 0xFFE4F5EC.toInt(),
        hint = 0x99E4F5EC.toInt(),
        hintRed = 0xFFFF9E80.toInt(),
        functionGlyph = 0xFFE4F5EC.toInt(),
        functionHint = 0x99E4F5EC.toInt(),
        functionFill = 0xFF0F1F1A.toInt(),
        functionStroke = 0x337FD1A8,
        candidateBar = 0xFF0D1B16.toInt(),
        plate = 0xFF071210.toInt(),
        accent = 0xFF7FD1A8.toInt(),
    )

    /** 皮革：棕调键帽 + 暖色缝线描边 + 2dp 底边厚度 + 米白字（仿皮面按键） */
    val LEATHER = KeyboardSkin(
        id = "leather",
        label = "皮革",
        keyFill = 0xFF4A3428.toInt(),
        keyFill2 = 0xFF5C4232.toInt(),
        keyPressed = 0xFF3C2A20.toInt(),
        strokeWidthDp = 1f,
        strokeColor = 0x66D9B08C,
        bottomThicknessDp = 2f,
        bottomThicknessColor = 0xFF7A5C3E.toInt(),
        glyph = 0xFFF3E4D3.toInt(),
        hint = 0x99F3E4D3.toInt(),
        hintRed = 0xFFFFAB91.toInt(),
        functionGlyph = 0xFFF3E4D3.toInt(),
        functionHint = 0x99F3E4D3.toInt(),
        functionFill = 0xFF3E2C22.toInt(),
        functionStroke = 0x66D9B08C,
        candidateBar = 0xFF33241C.toInt(),
        plate = 0xFF241A14.toInt(),
        accent = 0xFFD9A05B.toInt(),
    )

    /** 钛金：冷银灰 + 高光描边 + 银色底边厚度（金属质感） */
    val TITANIUM = KeyboardSkin(
        id = "titanium",
        label = "钛金",
        keyFill = 0xFF3A3E46.toInt(),
        keyFill2 = 0xFF4C525C.toInt(),
        keyPressed = 0xFF2F333A.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x66D8DEE8,
        bottomThicknessDp = 1f,
        bottomThicknessColor = 0xFF8A93A0.toInt(),
        glyph = 0xFFF2F5FA.toInt(),
        hint = 0x99F2F5FA.toInt(),
        hintRed = 0xFFFF8A80.toInt(),
        functionGlyph = 0xFFF2F5FA.toInt(),
        functionHint = 0x99F2F5FA.toInt(),
        functionFill = 0xFF303439.toInt(),
        functionStroke = 0x4DD8DEE8,
        candidateBar = 0xFF282C32.toInt(),
        plate = 0xFF1B1E22.toInt(),
        accent = 0xFFB8C4D6.toInt(),
    )

    /** 夜蓝：深靛蓝 + 淡蓝字（低亮度蓝，比海洋更冷更沉） */
    val NAVY = KeyboardSkin(
        id = "navy",
        label = "夜蓝",
        keyFill = 0xFF16233F.toInt(),
        keyFill2 = 0xFF1E3157.toInt(),
        keyPressed = 0xFF111A30.toInt(),
        strokeWidthDp = 0.5f,
        strokeColor = 0x337C9BE8,
        glyph = 0xFFDCE7FF.toInt(),
        hint = 0x99DCE7FF.toInt(),
        hintRed = 0xFFFF8A9B.toInt(),
        functionGlyph = 0xFFDCE7FF.toInt(),
        functionHint = 0x99DCE7FF.toInt(),
        functionFill = 0xFF131E36.toInt(),
        functionStroke = 0x337C9BE8,
        candidateBar = 0xFF101A2E.toInt(),
        plate = 0xFF0A1120.toInt(),
        accent = 0xFF7C9BE8.toInt(),
    )

    /** 夜空：深蓝紫渐变 + 星白字 + 紫色微光描边 */
    val NIGHT = KeyboardSkin(
        id = "night",
        label = "夜空",
        keyFill = 0xFF1A1733.toInt(),
        keyFill2 = 0xFF2A2350.toInt(),
        gradientAngle = 45f,
        keyPressed = 0xFF141126.toInt(),
        strokeWidthDp = 0.6f,
        strokeColor = 0x66A78BFA,
        glyph = 0xFFEDE9FF.toInt(),
        hint = 0x99EDE9FF.toInt(),
        hintRed = 0xFFFF9BB0.toInt(),
        functionGlyph = 0xFFEDE9FF.toInt(),
        functionHint = 0x99EDE9FF.toInt(),
        functionFill = 0xFF161327.toInt(),
        functionStroke = 0x4DA78BFA,
        candidateBar = 0xFF12102A.toInt(),
        plate = 0xFF0B0918.toInt(),
        accent = 0xFFA78BFA.toInt(),
    )

    /** 紫晶：紫渐变 + 浅紫字（比夜空更亮的紫，偏晶体感） */
    val AMETHYST = KeyboardSkin(
        id = "amethyst",
        label = "紫晶",
        keyFill = 0xFF2E1B47.toInt(),
        keyFill2 = 0xFF472A6B.toInt(),
        keyPressed = 0xFF251539.toInt(),
        strokeWidthDp = 0.6f,
        strokeColor = 0x66C4A0FF.toInt(),
        glyph = 0xFFF3E9FF.toInt(),
        hint = 0x99F3E9FF.toInt(),
        hintRed = 0xFFFF9BB0.toInt(),
        functionGlyph = 0xFFF3E9FF.toInt(),
        functionHint = 0x99F3E9FF.toInt(),
        functionFill = 0xFF271640.toInt(),
        functionStroke = 0x4DC4A0FF,
        candidateBar = 0xFF221338.toInt(),
        plate = 0xFF150C24.toInt(),
        accent = 0xFFC4A0FF.toInt(),
    )

    /** 雪原：极冷白 + 深蓝字（最亮的浅色系，冷调） */
    val SNOW = KeyboardSkin(
        id = "snow",
        label = "雪原",
        keyFill = 0xFFF7FAFF.toInt(),
        keyFill2 = 0xFFE6EFFB.toInt(),
        keyPressed = 0xFFD8E4F5.toInt(),
        strokeWidthDp = 0.6f,
        strokeColor = 0x336B87B8,
        glyph = 0xFF1E3350.toInt(),
        hint = 0x991E3350.toInt(),
        hintRed = 0xFFC62828.toInt(),
        functionGlyph = 0xFF1E3350.toInt(),
        functionHint = 0x991E3350.toInt(),
        functionFill = 0xFFEDF3FC.toInt(),
        functionStroke = 0x336B87B8,
        candidateBar = 0xFFFAFCFF.toInt(),
        plate = 0xFFDCE7F5.toInt(),
        accent = 0xFF3A6EA5.toInt(),
    )

    /** 青瓷：浅青白 + 墨青字 + 青瓷描边（浅色系的冷绿过渡） */
    val CELADON = KeyboardSkin(
        id = "celadon",
        label = "青瓷",
        keyFill = 0xFFF0F7F5.toInt(),
        keyFill2 = 0xFFDCEDE9.toInt(),
        keyPressed = 0xFFCBE3DE.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x335E8C82,
        glyph = 0xFF20403A.toInt(),
        hint = 0x9920403A.toInt(),
        hintRed = 0xFFC0392B.toInt(),
        functionGlyph = 0xFF20403A.toInt(),
        functionHint = 0x9920403A.toInt(),
        functionFill = 0xFFE4F2EE.toInt(),
        functionStroke = 0x335E8C82,
        candidateBar = 0xFFF6FBFA.toInt(),
        plate = 0xFFCDE3DD.toInt(),
        accent = 0xFF3E8C7A.toInt(),
    )

    /** 蜜桃：浅粉橙 + 深棕字（暖浅色系） */
    val PEACH = KeyboardSkin(
        id = "peach",
        label = "蜜桃",
        keyFill = 0xFFFFF4EE.toInt(),
        keyFill2 = 0xFFFFE3D4.toInt(),
        keyPressed = 0xFFFBD2BD.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x33B87355,
        glyph = 0xFF5A2E1E.toInt(),
        hint = 0x995A2E1E.toInt(),
        hintRed = 0xFFC2410C.toInt(),
        functionGlyph = 0xFF5A2E1E.toInt(),
        functionHint = 0x995A2E1E.toInt(),
        functionFill = 0xFFFDE7DA.toInt(),
        functionStroke = 0x33B87355,
        candidateBar = 0xFFFFF8F4.toInt(),
        plate = 0xFFF6D9C6.toInt(),
        accent = 0xFFE8804C.toInt(),
    )

    /** 抹茶：浅抹茶绿 + 深茶字 */
    val MATCHA = KeyboardSkin(
        id = "matcha",
        label = "抹茶",
        keyFill = 0xFFF3F7E9.toInt(),
        keyFill2 = 0xFFE2EDCB.toInt(),
        keyPressed = 0xFFD3E2B4.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x336C8A3C,
        glyph = 0xFF31421C.toInt(),
        hint = 0x9931421C.toInt(),
        hintRed = 0xFFC0392B.toInt(),
        functionGlyph = 0xFF31421C.toInt(),
        functionHint = 0x9931421C.toInt(),
        functionFill = 0xFFEAF2D8.toInt(),
        functionStroke = 0x336C8A3C,
        candidateBar = 0xFFF8FBF0.toInt(),
        plate = 0xFFD8E6BC.toInt(),
        accent = 0xFF7A9A38.toInt(),
    )

    /** 珊瑚：珊瑚粉橙 + 深红棕字 */
    val CORAL = KeyboardSkin(
        id = "coral",
        label = "珊瑚",
        keyFill = 0xFFFFEFEA.toInt(),
        keyFill2 = 0xFFFFD6C9.toInt(),
        keyPressed = 0xFFFFBFAE.toInt(),
        strokeWidthDp = 0.8f,
        strokeColor = 0x33A85445,
        glyph = 0xFF5C2620.toInt(),
        hint = 0x995C2620.toInt(),
        hintRed = 0xFFB71C1C.toInt(),
        functionGlyph = 0xFF5C2620.toInt(),
        functionHint = 0x995C2620.toInt(),
        functionFill = 0xFFFFDCD0.toInt(),
        functionStroke = 0x33A85445,
        candidateBar = 0xFFFFF5F2.toInt(),
        plate = 0xFFF7C9BC.toInt(),
        accent = 0xFFE85A4F.toInt(),
    )

    /**
     * 全部皮肤（顺序即外观页展示顺序：深色 / 中性系 → 深色彩色系 → 浅色 / 暖色系）。
     *
     * 这里是**定义清单**，顺序不是展示顺序：外观页用 [orderedForDisplay] 按键面亮度重排
     * （用户 2026-09-23 要求从亮到暗排，不许按分组直觉乱排）。
     * 标签一律两字（守卫见 `KeyboardSkinTest.皮肤标签一律两字`）。
     */
    val ALL: List<KeyboardSkin> = listOf(
        DEFAULT, FROST, DARK, GRAPHITE, TITANIUM, LEATHER, OCEAN, NAVY,
        NEON, MAGMA, SUNSET, NIGHT, AMETHYST, AURORA, JADE, CELADON,
        RAINBOW, SAKURA, PEACH, CORAL, MATCHA, MINT, SNOW, RETRO,
    )

    /** 按 id 取皮肤：未知 / 空值一律回退默认（脏配置不抛异常、不改变观感） */
    fun byId(id: String?): KeyboardSkin = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /**
     * 外观页的展示顺序：**默认固定第一位且不参与排序**，其余按键面亮度从亮到暗
     * （两点均为用户 2026-09-23 指定，不允许按分组直觉乱排）。
     *
     * 默认皮肤不参与排序是必须的：它不覆盖键面色，亮度由当前主题令牌算出 —— 主题一换名次就变
     * （亮白主题下排最前、暗黑主题下跌到末尾），位置不稳定。
     *
     * 排序键是纯函数（[faceBrightness]），新增皮肤不必手工找位置；亮度相同时保持 [ALL] 的
     * 定义顺序（`sortedByDescending` 是稳定排序）。
     *
     * [fallbackKeyFill] 由调用方传当前主题的 `R.color.kb_key`：默认皮肤不覆盖键面色，
     * 亮度只能由主题令牌算出来。
     */
    fun orderedForDisplay(fallbackKeyFill: Int): List<KeyboardSkin> =
        listOf(DEFAULT) +
            ALL.filterNot { it.isDefault }.sortedByDescending { faceBrightness(it, fallbackKeyFill) }

    /**
     * 键面感知亮度（0~1）：取键面首色与次色的均值。
     *
     * 彩虹皮肤没有 `keyFill`（键面按色相逐键生成），按其起始色相的实际生成色计算 ——
     * 否则会被当成「未覆盖」而拿主题色，排到最亮的位置上。
     */
    fun faceBrightness(skin: KeyboardSkin, fallbackKeyFill: Int): Double {
        val hue = skin.rainbowHue
        if (hue != null) return perceivedBrightness(rainbowColor(hue, 0))
        val first = skin.keyFill ?: fallbackKeyFill
        val second = skin.keyFill2 ?: first
        return (perceivedBrightness(first) + perceivedBrightness(second)) / 2.0
    }

    /** 感知亮度（BT.601 加权，与人眼对「深浅」的直觉一致，比 RGB 算术平均更接近观感） */
    private fun perceivedBrightness(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }

    /**
     * 压在强调色（[KeyboardSkin.accent]）上的图标 / 文字色：按该色的感知亮度自动取深或浅。
     *
     * 用途是大写锁定态的大写键 —— 它的底色是各皮肤的 accent，从深蓝（雪原 `#3A6EA5`）到浅银
     * （钛金 `#B8C4D6`）都有，单一固定色无法在 24 套皮肤上都保住对比（浅 accent 配近白图标
     * 实测约 1.5:1，等于看不见）。阈值 0.55 与 [faceBrightness] 同一套 BT.601 口径，即
     * 「这块颜色在人眼里算亮还是暗」的分界。
     */
    internal fun accentOnColor(accent: Int): Int =
        if (perceivedBrightness(accent) > BRIGHT_THRESHOLD) ON_LIGHT_FG else ON_DARK_FG

    /**
     * 涟漪色：亮底 → 10% 黑、暗底 → 30% 白（与两套主题令牌 `key_ripple` 同值）。
     *
     * 皮肤只覆盖面色与文字，涟漪若仍读主题令牌：「亮白主题 + 深色皮肤」会给深键面压 10% 黑
     * （几乎看不见按压反馈）、「暗黑主题 + 浅色皮肤」则在浅键面上泛白 —— 与皮肤观感割裂
     * （2026-09-23 审查确认的唯一可见不搭配）。这里按 [baseColor] 的明暗取相反侧。
     */
    internal fun rippleOn(baseColor: Int): Int =
        if (perceivedBrightness(baseColor) > BRIGHT_THRESHOLD) withAlpha(0xFF000000.toInt(), 0.10f)
        else withAlpha(0xFFFFFFFF.toInt(), 0.30f)

    /** 亮底上的前景色（深墨），见 [accentOnColor] 与 [rippleOn] */
    val ON_LIGHT_FG = 0xFF1A1F2B.toInt()

    /** 暗底上的前景色（近白），见 [accentOnColor] 与 [rippleOn] */
    val ON_DARK_FG = 0xFFF2F5FA.toInt()

    /** 「亮底」分界（BT.601 感知亮度，与 [faceBrightness] 同一口径） */
    private const val BRIGHT_THRESHOLD = 0.55

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
        // 未覆盖按压/提示色时：默认皮肤沿用历史令牌色，皮肤路径由首色/字色推导。
        // 判据必须用 isDefault 而非 keyFill == null：彩虹皮肤同样没有 keyFill，
        // 用后者会把按压态取成主题深灰（按键一按就从彩色闪成暗块）。
        val pressed = skin.keyPressed
            ?: if (skin.isDefault) fallbackPressed else darken(first, 0.88f)
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
                "functionGlyph" to skin.functionGlyph,
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
