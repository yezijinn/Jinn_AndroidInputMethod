package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 键盘皮肤（[KeyboardSkin] / [KeyboardSkins]）的定义域、分组与档位规则守卫。
 *
 * 关注点：
 *  1. 两套令牌皮肤（原黑 / 原白）必须是「全空覆盖」—— 绘制仍走 `R.color` 令牌，
 *     皮肤机制不得改变历史观感；且各自固定吃自己那一档色板（[KeyboardSkins.tokenPaletteDark]）；
 *  2. 亮色 / 暗色分组是**显式声明**，且声明与键面亮度方向不矛盾；分组总数与用户定的规模一致；
 *  3. 展示顺序把原黑 / 原白固定在头两位，其余按键面亮度从亮到暗；
 *  4. 档位取皮肤（[KeyboardSkins.byId]）与旧单值皮肤迁移（[KeyboardSkins.migrateLegacySkin]）的规则；
 *  5. 「皮肤 × 面透明度」的合成方式：只改 alpha、RGB 原样，字色/提示色不被透明度影响；
 *  6. 彩虹皮肤按 26 键序取色两两不同，无索引（分号键）收敛到起始色相。
 */
class KeyboardSkinTest {

    private fun skin(id: String): KeyboardSkin =
        KeyboardSkins.find(id) ?: error("清单里没有皮肤 $id")

    @Test
    fun 令牌皮肤是全空覆盖() {
        for (d in listOf(KeyboardSkins.LEGACY_DARK, KeyboardSkins.LEGACY_LIGHT)) {
            assertTrue("${d.id} 必须走历史令牌路径", d.isToken)
            assertNull(d.keyFill)
            assertNull(d.keyFill2)
            assertNull(d.glyph)
            assertNull(d.functionFill)
            assertNull(d.candidateBar)
            assertNull(d.plate)
            assertNull(d.rainbowHue)
        }
    }

    @Test
    fun 令牌皮肤按档位吃色板() {
        assertEquals("原黑固定暗色板", true, KeyboardSkins.tokenPaletteDark(KeyboardSkins.LEGACY_DARK))
        assertEquals("原白固定亮色板", false, KeyboardSkins.tokenPaletteDark(KeyboardSkins.LEGACY_LIGHT))
        for (s in KeyboardSkins.ALL) {
            if (s.isToken) continue
            assertNull(
                "配色皮肤自带色值，与色板无关（不该让调用方去包一层 Context）",
                KeyboardSkins.tokenPaletteDark(s),
            )
        }
    }

    @Test
    fun byId_未知空值与跨档脏值回退本档基线() {
        assertEquals(KeyboardSkins.LEGACY_LIGHT_ID, KeyboardSkins.byId(null, SkinTone.LIGHT).id)
        assertEquals(KeyboardSkins.LEGACY_DARK_ID, KeyboardSkins.byId("", SkinTone.DARK).id)
        assertEquals(
            KeyboardSkins.LEGACY_LIGHT_ID,
            KeyboardSkins.byId("no_such_skin", SkinTone.LIGHT).id,
        )
        // 跨档脏值（备份可被外部构造）：暗色皮肤塞进亮色槽 → 回退原白，否则浅色页面配深色键盘
        assertEquals(KeyboardSkins.LEGACY_LIGHT_ID, KeyboardSkins.byId("midnight", SkinTone.LIGHT).id)
        assertEquals(KeyboardSkins.LEGACY_DARK_ID, KeyboardSkins.byId("snow", SkinTone.DARK).id)
        // 同档取值必须原样返回（别把合规配置吞掉）
        for (s in KeyboardSkins.ALL) assertEquals(s.id, KeyboardSkins.byId(s.id, s.tone).id)
    }

    /**
     * 两档初始皮肤（新用户缺省值）必须是清单里真实存在的配色皮肤：
     * 指向令牌基线、或指向一个不存在的 id（会被 `byId` 静默回退），都会让「开箱观感」落空。
     */
    @Test
    fun 两档初始皮肤存在且档位正确() {
        val light = KeyboardSkins.find(KeyboardSkins.INITIAL_LIGHT_ID)
            ?: error("亮色档初始皮肤不在清单里：${KeyboardSkins.INITIAL_LIGHT_ID}")
        val dark = KeyboardSkins.find(KeyboardSkins.INITIAL_DARK_ID)
            ?: error("暗色档初始皮肤不在清单里：${KeyboardSkins.INITIAL_DARK_ID}")
        assertEquals("亮色档初始皮肤必须是亮色系", SkinTone.LIGHT, light.tone)
        assertEquals("暗色档初始皮肤必须是暗色系", SkinTone.DARK, dark.tone)
        assertTrue("初始皮肤应当是真实配色皮肤", !light.isToken && !dark.isToken)
        assertNotNull("初始皮肤必须给出键面填充色", light.keyFill)
        assertNotNull("初始皮肤必须给出功能键文字色", light.functionGlyph)
    }

    @Test
    fun 清单共三十二套且id唯一() {
        assertEquals(
            "用户 2026-09-24 定的规模：32 套 = 原黑 / 原白 + 30 套配色皮肤",
            32,
            KeyboardSkins.ALL.size,
        )
        assertEquals(
            "id 必须唯一",
            KeyboardSkins.ALL.size,
            KeyboardSkins.ALL.map { it.id }.toSet().size,
        )
    }

    /** 分组规模：亮色 16（含原白）、暗色 16（含原黑）—— 两档数量对等，槽位才不会缺货 */
    @Test
    fun 亮色与暗色各十六套() {
        assertEquals("亮色系应含原白在内共 16 套", 16, KeyboardSkins.ALL.count { it.tone == SkinTone.LIGHT })
        assertEquals("暗色系应含原黑在内共 16 套", 16, KeyboardSkins.ALL.count { it.tone == SkinTone.DARK })
        assertEquals(KeyboardSkins.LEGACY_LIGHT_ID, KeyboardSkins.legacyOf(SkinTone.LIGHT).id)
        assertEquals(KeyboardSkins.LEGACY_DARK_ID, KeyboardSkins.legacyOf(SkinTone.DARK).id)
    }

    /**
     * 分组是显式声明（彩虹首键亮度 0.543，靠 0.55 阈值推导会判错），这里只守
     * 「声明与观感方向不矛盾」：亮色皮肤必须真的偏亮、暗色皮肤必须真的偏暗。
     * 令牌皮肤不覆盖键面色（亮度随色板变化），跳过。
     */
    @Test
    fun 亮暗分组与键面亮度方向一致() {
        for (s in KeyboardSkins.ALL) {
            if (s.isToken) continue
            val brightness = KeyboardSkins.faceBrightness(s)
            if (s.tone == SkinTone.LIGHT) {
                assertTrue("亮色皮肤 ${s.id} 的键面亮度 $brightness 应 > 0.5", brightness > 0.5)
            } else {
                assertTrue("暗色皮肤 ${s.id} 的键面亮度 $brightness 应 < 0.5", brightness < 0.5)
            }
        }
    }

    @Test
    fun 全部皮肤参数合法() {
        for (s in KeyboardSkins.ALL) {
            val issues = KeyboardSkins.issues(s)
            assertEquals("皮肤 ${s.id} 参数越界: $issues", emptyList<String>(), issues)
        }
    }

    @Test
    fun 配色皮肤的填充色均不透明() {
        for (s in KeyboardSkins.ALL) {
            if (s.isToken) continue
            val fills = listOfNotNull(
                s.keyFill, s.keyFill2, s.keyPressed, s.functionFill,
                s.candidateBar, s.plate, s.accent,
            )
            for (c in fills) {
                assertEquals(
                    "皮肤 ${s.id} 的填充色应不透明: ${String.format("#%08X", c)}",
                    0xFF,
                    (c ushr 24) and 0xFF,
                )
            }
            // 描边与字色允许带 alpha（磨砂高光、半透明提示色）
            s.strokeColor?.let { assertTrue("描边色不得全透明", ((it ushr 24) and 0xFF) > 0) }
        }
    }

    @Test
    fun 彩虹按索引取色_两两不同且落在同一起始色相之外() {
        val skin = skin("rainbow")
        val colors = (0 until 26).map { KeyboardSkins.keyFill(skin, it, 0) }
        assertTrue("26 键取色应互不相同（去重后 ${colors.toSet().size}）", colors.toSet().size >= 24)
        for (c in colors) assertEquals("彩虹键面必须不透明", 0xFF, (c ushr 24) and 0xFF)
        // 无索引（分号键）取起始色相，与第 0 键同色
        assertEquals(colors[0], KeyboardSkins.keyFill(skin, -1, 0))
    }

    @Test
    fun 非彩虹皮肤取色与索引无关() {
        val frost = skin("frost")
        assertEquals(KeyboardSkins.keyFill(frost, 0, 0), KeyboardSkins.keyFill(frost, 25, 0))
        assertNotEquals(
            "磨砂键面必须与令牌皮肤不同",
            KeyboardSkins.keyFill(KeyboardSkins.LEGACY_LIGHT, 0, 0x123456),
            KeyboardSkins.keyFill(frost, 0, 0x123456),
        )
    }

    @Test
    fun 未给次色时键面退化为纯色() {
        val plain = KeyboardSkins.LEGACY_LIGHT.copy(
            id = "plain",
            label = "纯色",
            keyFill = 0xFF112233.toInt(),
        )
        val first = KeyboardSkins.keyFill(plain, 0, 0)
        assertEquals(0xFF112233.toInt(), first)
        assertEquals("无次色时次色等于首色", first, KeyboardSkins.keyFill2(plain, 0, first))
        // 渐变皮肤则必须给出不同次色
        val frost = skin("frost")
        val frostFirst = KeyboardSkins.keyFill(frost, 0, 0)
        assertNotEquals("磨砂应有渐变次色", frostFirst, KeyboardSkins.keyFill2(frost, 0, frostFirst))
    }

    @Test
    fun 按压色未覆盖时由首色压暗() {
        val plain = KeyboardSkins.LEGACY_LIGHT.copy(
            id = "x",
            label = "x",
            keyFill = 0xFF808080.toInt(),
        )
        val first = KeyboardSkins.keyFill(plain, 0, 0)
        val pressed = KeyboardSkins.keyPressed(plain, first)
        assertTrue(
            "按压色应比首色暗",
            (pressed and 0x00FFFFFF) < (first and 0x00FFFFFF),
        )
        assertEquals("压暗不得改变 alpha", (first ushr 24) and 0xFF, (pressed ushr 24) and 0xFF)
    }

    @Test
    fun 按压色分支不因缺keyFill而回落主题色() {
        val fallbackPressed = 0xFFD5D8E0.toInt()
        // 彩虹皮肤：既无 keyFill 也无 keyPressed → 必须由首色压暗，不得取主题的 fallbackPressed
        // （否则按键一按就从彩色闪成主题深块）
        val rainbow = skin("rainbow")
        val rv = KeyboardSkins.visualFor(
            rainbow, 0, 1f, 2.75f, 0xFFEFF1F5.toInt(), fallbackPressed,
            0xFF1A1F2B.toInt(), 0xFF5A6474.toInt(), 0xFFC62828.toInt(),
        )
        assertNotEquals("彩虹按压态不得回落为主题按压色", fallbackPressed, rv.pressed)
        assertEquals("彩虹按压态 = 首色压暗", KeyboardSkins.keyPressed(rainbow, rv.face), rv.pressed)
        // 令牌皮肤：同样无 keyFill / 无 keyPressed，但必须沿用历史令牌色（与上方互补，防判据写反）
        val dv = KeyboardSkins.visualFor(
            KeyboardSkins.LEGACY_DARK, 0, 1f, 2.75f, 0xFFEFF1F5.toInt(), fallbackPressed,
            0xFF1A1F2B.toInt(), 0xFF5A6474.toInt(), 0xFFC62828.toInt(),
        )
        assertEquals("令牌皮肤沿用令牌按压色", fallbackPressed, dv.pressed)
        // 皮肤显式声明 keyPressed 时直接使用，不推导
        val frost = skin("frost")
        val explicit = frost.keyPressed
        if (explicit != null) {
            val fv = KeyboardSkins.visualFor(
                frost, 0, 1f, 2.75f, 0xFFEFF1F5.toInt(), fallbackPressed,
                0xFF1A1F2B.toInt(), 0xFF5A6474.toInt(), 0xFFC62828.toInt(),
            )
            assertEquals("皮肤显式按压色优先", explicit, fv.pressed)
        }
    }

    @Test
    fun withAlpha_只改alpha通道() {
        val c = 0xFF3366CC.toInt()
        assertEquals(0x003366CC, KeyboardSkins.withAlpha(c, 0f))
        assertEquals(0x803366CC.toInt(), KeyboardSkins.withAlpha(c, 128f / 255f))
        assertEquals(c, KeyboardSkins.withAlpha(c, 1f))
        assertEquals(c and 0x00FFFFFF, KeyboardSkins.withAlpha(c, 0.37f) and 0x00FFFFFF)
        assertEquals("越界 alpha 收敛到 0", 0x003366CC, KeyboardSkins.withAlpha(c, -1f))
        assertEquals("越界 alpha 收敛到 255", c, KeyboardSkins.withAlpha(c, 2f))
    }

    @Test
    fun visualFor_按密度换算描边与厚度() {
        val retro = skin("retro")
        val v = KeyboardSkins.visualFor(
            retro, 0, 1f, 2f, 0, 0, 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(),
        )
        assertEquals("1dp 描边 × 密度 2", 2f, v.strokeWidthPx, 0.001f)
        assertEquals("2dp 底边厚度 × 密度 2", 4f, v.thicknessPx, 0.001f)
        assertEquals(retro.glyph, v.glyph)
    }

    @Test
    fun visualFor_令牌皮肤合成结果等于历史令牌() {
        val face = 0xFF2A2E38.toInt()
        val pressed = 0xFF1F222A.toInt()
        val glyph = 0xFFE6E9F0.toInt()
        val hintRed = 0xFFFF5A5F.toInt()
        val v = KeyboardSkins.visualFor(
            KeyboardSkins.LEGACY_DARK, 3, 0.6f, 2.75f,
            face, pressed, glyph, KeyboardSkins.withAlpha(glyph, 160f / 255f), hintRed,
        )
        assertEquals(KeyboardSkins.withAlpha(face, 0.6f), v.face)
        assertEquals("令牌皮肤必须为纯色（不启用渐变）", v.face, v.face2)
        assertEquals(KeyboardSkins.withAlpha(pressed, 0.6f), v.pressed)
        assertEquals("令牌皮肤不得有描边", 0f, v.strokeWidthPx, 0.001f)
        assertEquals("令牌皮肤不得有底边厚度", 0f, v.thicknessPx, 0.001f)
        assertEquals("字色不受面透明度影响", glyph, v.glyph)
        assertEquals("提示色 = 字色 + alpha160（与历史绘制一致）", KeyboardSkins.withAlpha(glyph, 160f / 255f), v.hint)
        assertEquals(hintRed, v.hintRed)
    }

    @Test
    fun visualFor_皮肤路径下字色不随面透明度变化() {
        val frost = skin("frost")
        val a = KeyboardSkins.visualFor(
            frost, 0, 1f, 2.75f, 0, 0, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(),
        )
        val b = KeyboardSkins.visualFor(
            frost, 0, 0.6f, 2.75f, 0, 0, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(),
        )
        assertEquals("字色与面透明度无关", a.glyph, b.glyph)
        assertEquals("提示色与面透明度无关", a.hint, b.hint)
        assertNotEquals("键面 alpha 必须随面透明度变化", a.face, b.face)
        assertEquals("最透时键面 alpha = 0.6 × 255", 153, (b.face ushr 24) and 0xFF)
    }

    @Test
    fun 配色皮肤必须给出功能键文字色() {
        for (s in KeyboardSkins.ALL) {
            if (s.isToken) continue
            val glyph = s.functionGlyph
            assertNotNull("皮肤 ${s.id} 必须定义 functionGlyph：功能键背景由皮肤接管，缺它会深底深字撞色", glyph)
            assertNotNull("皮肤 ${s.id} 必须定义 functionHint（空格键小字 / 页码 / 候选提示）", s.functionHint)
            assertEquals("functionGlyph 必须不透明", 0xFF, (glyph!! ushr 24) and 0xFF)
        }
        assertNull("令牌皮肤不得覆盖功能键文字色", KeyboardSkins.LEGACY_LIGHT.functionGlyph)
        assertNull("令牌皮肤不得覆盖功能键次要文字色", KeyboardSkins.LEGACY_LIGHT.functionHint)
        assertNull("令牌皮肤不得覆盖功能键文字色", KeyboardSkins.LEGACY_DARK.functionGlyph)
        assertNull("令牌皮肤不得覆盖功能键次要文字色", KeyboardSkins.LEGACY_DARK.functionHint)
    }

    /**
     * 展示顺序是**用户逐项指定的固定顺序**（2026-09-24），不再按键面亮度重排。
     *
     * 这里逐项对拍 label 必须与指定的一模一样：外观页每行八个 ⇒ 每段恰两整行，
     * 改动顺序（含新增皮肤插位置）都要同步这份期望值 —— 这是「顺序不漂」的唯一守卫。
     */
    @Test
    fun 展示顺序两段固定() {
        val expected = listOf(
            // 亮色段（16）：原白 柠檬 奶油 雪原 樱花 蜜桃 青瓷 雾灰 · 天青 抹茶 沙丘 薄荷 藕荷 珊瑚 复古 彩虹
            "原白", "柠檬", "奶油", "雪原", "樱花", "蜜桃", "青瓷", "雾灰",
            "天青", "抹茶", "沙丘", "薄荷", "藕荷", "珊瑚", "复古", "彩虹",
            // 暗色段（16）：原黑 钛金 日落 皮革 磨砂 石墨 紫晶 海洋 · 岩浆 夜蓝 墨玉 极光 夜空 暗黑 午夜 霓虹
            "原黑", "钛金", "日落", "皮革", "磨砂", "石墨", "紫晶", "海洋",
            "岩浆", "夜蓝", "墨玉", "极光", "夜空", "暗黑", "午夜", "霓虹",
        )
        assertEquals("展示顺序必须与用户指定的 32 项逐项一致", expected, KeyboardSkins.ALL.map { it.label })
        // 两段各 16 套且段首是令牌皮肤（原白 / 原黑）—— 每段恰两整行（每行八个）
        assertEquals("亮色段", 16, KeyboardSkins.ofTone(SkinTone.LIGHT).size)
        assertEquals("暗色段", 16, KeyboardSkins.ofTone(SkinTone.DARK).size)
        assertEquals("亮色段首必须是原白", KeyboardSkins.LEGACY_LIGHT_ID, KeyboardSkins.ofTone(SkinTone.LIGHT).first().id)
        assertEquals("暗色段首必须是原黑", KeyboardSkins.LEGACY_DARK_ID, KeyboardSkins.ofTone(SkinTone.DARK).first().id)
    }

    @Test
    fun 强调色上的图标按亮度取深或浅() {
        // 浅色 accent（钛金 / 极光）→ 深色图标；深色 accent（雪原 / 樱花）→ 近白图标
        assertEquals("浅银 accent", 0xFF1A1F2B.toInt(), KeyboardSkins.accentOnColor(0xFFB8C4D6.toInt()))
        assertEquals("亮青 accent", 0xFF1A1F2B.toInt(), KeyboardSkins.accentOnColor(0xFF22D3EE.toInt()))
        assertEquals("深蓝 accent", 0xFFF2F5FA.toInt(), KeyboardSkins.accentOnColor(0xFF3A6EA5.toInt()))
        assertEquals("玫红 accent", 0xFFF2F5FA.toInt(), KeyboardSkins.accentOnColor(0xFFE04A78.toInt()))
        // 两个极端必须落在相反两侧，避免阈值失效后"两边都取中间色"
        assertNotEquals(
            "纯白与纯黑的取值必须相反",
            KeyboardSkins.accentOnColor(0xFFFFFFFF.toInt()),
            KeyboardSkins.accentOnColor(0xFF000000.toInt()),
        )
    }

    @Test
    fun 皮肤标签一律两字() {
        for (s in KeyboardSkins.ALL) {
            assertEquals(
                "皮肤 ${s.id} 的标签应为两个字（外观页选项每行八个、等分宽度，" +
                    "三字以上会挤压或换行）",
                2,
                s.label.length,
            )
        }
    }

    @Test
    fun 候选栏色不得与背板或功能键面撞色() {
        // 候选栏档位由 updateCandidateBarBackground 专管（有内容走 surface、空白走 plate）。
        // 一旦某个皮肤的 candidateBar 与 alphaFaces 的识别色集（plate / functionFill）同值，
        // 候选栏就会被统一压到 plate 档：透明度 100% 时候选词直接叠在宿主内容上，
        // 且档位缓存不再更新（要清空一次内容才自愈）。新增皮肤必须避开这两个取值。
        for (s in KeyboardSkins.ALL) {
            val cb = s.candidateBar ?: continue
            assertNotEquals("皮肤 ${s.id}: candidateBar 与 plate 撞色", s.plate, cb)
            assertNotEquals("皮肤 ${s.id}: candidateBar 与 functionFill 撞色", s.functionFill, cb)
        }
    }

    @Test
    fun 未声明按压色时令牌皮肤走令牌_皮肤按首色推导() {
        // 判据必须是 isToken：彩虹皮肤同样没有 keyFill，用「keyFill == null」判会把它的按压态
        // 取成主题深灰，按键一按就从彩色闪成暗块。
        val fallbackFace = 0xFFF2F4F8.toInt()
        val fallbackPressed = 0xFFD0D5E0.toInt()
        fun visual(s: KeyboardSkin) = KeyboardSkins.visualFor(
            s, 0, 1f, 1f, fallbackFace, fallbackPressed,
            0xFF101010.toInt(), 0xFF666666.toInt(), 0xFFFF0000.toInt(),
        )

        assertEquals("令牌皮肤必须沿用历史令牌按压色", fallbackPressed, visual(KeyboardSkins.LEGACY_DARK).pressed)

        val rainbow = skin("rainbow")
        assertTrue("取到的应是彩虹皮肤", !rainbow.isToken && rainbow.keyPressed == null)
        val r = visual(rainbow)
        assertNotEquals("未声明 keyPressed 的皮肤不得回落主题深灰", fallbackPressed, r.pressed)
        assertEquals("按压色应由首色推导（darken 12%）", KeyboardSkins.darken(r.face, 0.88f), r.pressed)
    }

    @Test
    fun 涟漪色按底面明暗取相反侧() {
        // 亮面（如雪原 #EDF3FC）→ 10% 黑；暗面 → 30% 白：取值与两套主题令牌 `key_ripple` 同值，
        // 皮肤下的按压反馈才与键面明暗一致
        assertEquals(0x1A000000, KeyboardSkins.rippleOn(0xFFEDF3FC.toInt()))
        assertEquals(0x4DFFFFFF.toInt(), KeyboardSkins.rippleOn(0xFF14171E.toInt()))
        // 两个极端必须落在相反两侧，避免阈值失效后「两边都取同一侧」
        assertNotEquals(
            "纯白与纯黑的涟漪必须相反",
            KeyboardSkins.rippleOn(0xFFFFFFFF.toInt()),
            KeyboardSkins.rippleOn(0xFF000000.toInt()),
        )
    }

    /**
     * 键盘选皮肤的档位判据：必须按**视图自己的色板快照**（`ThemeManager.paletteIsDark`），
     * 不能按此刻现算的决策（`ThemeManager.isDark`）。
     *
     * 为什么：视图的 `R.color.*` 是创建时刻定死的，而换主题的重建会被「未上屏输入 / 面板打开」
     * 延后（`JinnIme.applyThemeIfNeeded` 只退出自己，随后 `configure()` 照常跑）。那一刻按现算
     * 决策取皮肤，令牌皮肤就会拿着旧档色板画 —— 暗档的原黑被画成白键面，日志却写着「原黑」。
     *
     * 这条不变量只在 Android 上跑得到（JVM 单测没有 Context），故用源码对拍钉住。
     */
    @Test
    fun 键盘选皮肤按视图色板快照而不是现算决策() {
        val sourceFile = listOf(
            File("src/main/java/com/jinn/inputmethod/PinyinKeyboardView.kt"),
            File("app/src/main/java/com/jinn/inputmethod/PinyinKeyboardView.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 PinyinKeyboardView.kt")
        val body = sourceFile.readText()
            .substringAfter("private fun syncKeyboardSkin()")
            .substringBefore("\n    /**")
        assertTrue(
            "syncKeyboardSkin 必须用 paletteIsDark(context) 选档",
            body.contains("ThemeManager.paletteIsDark(context)"),
        )
        assertTrue(
            "syncKeyboardSkin 不得按现算决策选档：延后重建窗口里会与视图色板错档",
            !body.contains("ThemeManager.isDark("),
        )
    }

    /**
     * 旧单值皮肤（`prefs.keyboard_skin`）→ 双槽位的一次性迁移规则。
     *
     * 关键点是「按旧皮肤自己的档位入槽」：旧暗色皮肤进暗色槽、旧亮色皮肤进亮色槽，
     * 另一槽取该档令牌基线；旧版「默认」（跟随主题、id = `default`）在清单里已不存在，
     * 双槽都落令牌基线 —— 观感与升级前等价，用户不会看到键盘突然换色。
     */
    @Test
    fun 旧单值皮肤迁移到双槽位() {
        val lightBase = KeyboardSkins.LEGACY_LIGHT_ID
        val darkBase = KeyboardSkins.LEGACY_DARK_ID
        assertEquals("旧默认（跟随主题）→ 双槽令牌基线", lightBase to darkBase, KeyboardSkins.migrateLegacySkin("default"))
        assertEquals("无旧配置 → 双槽令牌基线", lightBase to darkBase, KeyboardSkins.migrateLegacySkin(null))
        assertEquals("脏值 → 双槽令牌基线", lightBase to darkBase, KeyboardSkins.migrateLegacySkin("no_such_skin"))
        assertEquals("旧暗色皮肤 → 暗色槽", lightBase to "amethyst", KeyboardSkins.migrateLegacySkin("amethyst"))
        assertEquals("旧亮色皮肤 → 亮色槽", "snow" to darkBase, KeyboardSkins.migrateLegacySkin("snow"))
    }
}
