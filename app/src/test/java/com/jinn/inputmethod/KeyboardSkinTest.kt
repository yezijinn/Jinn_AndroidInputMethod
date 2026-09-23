package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘皮肤（[KeyboardSkin] / [KeyboardSkins]）的定义域与兼容性守卫。
 *
 * 关注点：
 *  1. 默认皮肤必须是「全空覆盖」—— 绘制仍走 `R.color` 令牌，皮肤机制不得改变历史观感；
 *  2. 四套非默认皮肤的参数都在定义域内（填充色不透明、角度与描边/厚度范围、渐变不退化为纯色）；
 *  3. 「皮肤 × 面透明度」的合成口径：只改 alpha、RGB 原样，字色/提示色不被透明度影响；
 *  4. 彩虹皮肤按 26 键序取色两两不同，无索引（分号键）收敛到起始色相。
 */
class KeyboardSkinTest {

    @Test
    fun 默认皮肤是全空覆盖() {
        val d = KeyboardSkins.DEFAULT
        assertTrue("默认皮肤必须走历史令牌路径", d.isDefault)
        assertNull(d.keyFill)
        assertNull(d.keyFill2)
        assertNull(d.glyph)
        assertNull(d.functionFill)
        assertNull(d.candidateBar)
        assertNull(d.plate)
        assertNull(d.rainbowHue)
    }

    @Test
    fun byId_未知与空值回退默认() {
        assertEquals(KeyboardSkins.DEFAULT_ID, KeyboardSkins.byId(null).id)
        assertEquals(KeyboardSkins.DEFAULT_ID, KeyboardSkins.byId("").id)
        assertEquals(KeyboardSkins.DEFAULT_ID, KeyboardSkins.byId("no_such_skin").id)
        for (s in KeyboardSkins.ALL) assertEquals(s.id, KeyboardSkins.byId(s.id).id)
    }

    /**
     * 初始皮肤（新用户的缺省值）必须是清单里真实存在的配色皮肤：
     * 指向空覆盖基线、或指向一个不存在的 id（会被 `byId` 静默回退），都会让「默认观感」落空。
     */
    @Test
    fun 初始皮肤必须存在且不是空覆盖基线() {
        val s = KeyboardSkins.byId(KeyboardSkins.INITIAL_ID)
        assertEquals(KeyboardSkins.INITIAL_ID, s.id)
        assertTrue("初始皮肤应当是真实配色皮肤", !s.isDefault)
        assertNotNull("初始皮肤必须给出键面填充色", s.keyFill)
        assertNotNull("初始皮肤必须给出功能键文字色", s.functionGlyph)
    }

    @Test
    fun 清单_id唯一且首项为默认() {
        assertEquals(KeyboardSkins.DEFAULT_ID, KeyboardSkins.ALL.first().id)
        assertEquals(
            "id 必须唯一",
            KeyboardSkins.ALL.size,
            KeyboardSkins.ALL.map { it.id }.toSet().size,
        )
        assertTrue("清单应含默认 + 4 套皮肤", KeyboardSkins.ALL.size >= 5)
    }

    @Test
    fun 全部皮肤参数合法() {
        for (s in KeyboardSkins.ALL) {
            val issues = KeyboardSkins.issues(s)
            assertEquals("皮肤 ${s.id} 参数越界: $issues", emptyList<String>(), issues)
        }
    }

    @Test
    fun 非默认皮肤的填充色均不透明() {
        for (s in KeyboardSkins.ALL) {
            if (s.isDefault) continue
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
        val skin = KeyboardSkins.byId("rainbow")
        val colors = (0 until 26).map { KeyboardSkins.keyFill(skin, it, 0) }
        assertTrue("26 键取色应互不相同（去重后 ${colors.toSet().size}）", colors.toSet().size >= 24)
        for (c in colors) assertEquals("彩虹键面必须不透明", 0xFF, (c ushr 24) and 0xFF)
        // 无索引（分号键）取起始色相，与第 0 键同色
        assertEquals(colors[0], KeyboardSkins.keyFill(skin, -1, 0))
    }

    @Test
    fun 非彩虹皮肤取色与索引无关() {
        val frost = KeyboardSkins.byId("frost")
        assertEquals(KeyboardSkins.keyFill(frost, 0, 0), KeyboardSkins.keyFill(frost, 25, 0))
        assertNotEquals(
            "磨砂键面必须与默认令牌不同",
            KeyboardSkins.keyFill(KeyboardSkins.DEFAULT, 0, 0x123456),
            KeyboardSkins.keyFill(frost, 0, 0x123456),
        )
    }

    @Test
    fun 未给次色时键面退化为纯色() {
        val plain = KeyboardSkins.DEFAULT.copy(
            id = "plain",
            label = "纯色",
            keyFill = 0xFF112233.toInt(),
        )
        val first = KeyboardSkins.keyFill(plain, 0, 0)
        assertEquals(0xFF112233.toInt(), first)
        assertEquals("无次色时次色等于首色", first, KeyboardSkins.keyFill2(plain, 0, first))
        // 渐变皮肤则必须给出不同次色
        val frost = KeyboardSkins.byId("frost")
        val frostFirst = KeyboardSkins.keyFill(frost, 0, 0)
        assertNotEquals("磨砂应有渐变次色", frostFirst, KeyboardSkins.keyFill2(frost, 0, frostFirst))
    }

    @Test
    fun 按压色未覆盖时由首色压暗() {
        val skin = KeyboardSkins.DEFAULT.copy(id = "x", label = "x", keyFill = 0xFF808080.toInt())
        val first = KeyboardSkins.keyFill(skin, 0, 0)
        val pressed = KeyboardSkins.keyPressed(skin, first)
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
        val rainbow = KeyboardSkins.byId("rainbow")
        val rv = KeyboardSkins.visualFor(
            rainbow, 0, 1f, 2.75f, 0xFFEFF1F5.toInt(), fallbackPressed,
            0xFF1A1F2B.toInt(), 0xFF5A6474.toInt(), 0xFFC62828.toInt(),
        )
        assertNotEquals("彩虹按压态不得回落为主题按压色", fallbackPressed, rv.pressed)
        assertEquals("彩虹按压态 = 首色压暗", KeyboardSkins.keyPressed(rainbow, rv.face), rv.pressed)
        // 默认皮肤：同样无 keyFill / 无 keyPressed，但必须沿用历史令牌色（与上方互补，防判据写反）
        val dv = KeyboardSkins.visualFor(
            KeyboardSkins.DEFAULT, 0, 1f, 2.75f, 0xFFEFF1F5.toInt(), fallbackPressed,
            0xFF1A1F2B.toInt(), 0xFF5A6474.toInt(), 0xFFC62828.toInt(),
        )
        assertEquals("默认皮肤沿用令牌按压色", fallbackPressed, dv.pressed)
        // 皮肤显式声明 keyPressed 时直接使用，不推导
        val frost = KeyboardSkins.byId("frost")
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
        val retro = KeyboardSkins.byId("retro")
        val v = KeyboardSkins.visualFor(
            retro, 0, 1f, 2f, 0, 0, 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(),
        )
        assertEquals("1dp 描边 × 密度 2", 2f, v.strokeWidthPx, 0.001f)
        assertEquals("2dp 底边厚度 × 密度 2", 4f, v.thicknessPx, 0.001f)
        assertEquals(retro.glyph, v.glyph)
    }

    @Test
    fun visualFor_默认皮肤合成结果等于历史令牌() {
        val face = 0xFF2A2E38.toInt()
        val pressed = 0xFF1F222A.toInt()
        val glyph = 0xFFE6E9F0.toInt()
        val hintRed = 0xFFFF5A5F.toInt()
        val v = KeyboardSkins.visualFor(
            KeyboardSkins.DEFAULT, 3, 0.6f, 2.75f,
            face, pressed, glyph, KeyboardSkins.withAlpha(glyph, 160f / 255f), hintRed,
        )
        assertEquals(KeyboardSkins.withAlpha(face, 0.6f), v.face)
        assertEquals("默认皮肤必须为纯色（不启用渐变）", v.face, v.face2)
        assertEquals(KeyboardSkins.withAlpha(pressed, 0.6f), v.pressed)
        assertEquals("默认皮肤不得有描边", 0f, v.strokeWidthPx, 0.001f)
        assertEquals("默认皮肤不得有底边厚度", 0f, v.thicknessPx, 0.001f)
        assertEquals("字色不受面透明度影响", glyph, v.glyph)
        assertEquals("提示色 = 字色 + alpha160（与历史绘制一致）", KeyboardSkins.withAlpha(glyph, 160f / 255f), v.hint)
        assertEquals(hintRed, v.hintRed)
    }

    @Test
    fun visualFor_皮肤路径下字色不随面透明度变化() {
        val frost = KeyboardSkins.byId("frost")
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
    fun 非默认皮肤必须给出功能键文字色() {
        for (s in KeyboardSkins.ALL) {
            if (s.isDefault) continue
            val glyph = s.functionGlyph
            assertNotNull("皮肤 ${s.id} 必须定义 functionGlyph：功能键背景由皮肤接管，缺它会深底深字撞色", glyph)
            assertNotNull("皮肤 ${s.id} 必须定义 functionHint（空格键小字 / 页码 / 候选提示）", s.functionHint)
            assertEquals("functionGlyph 必须不透明", 0xFF, (glyph!! ushr 24) and 0xFF)
        }
        assertNull("默认皮肤不得覆盖功能键文字色", KeyboardSkins.DEFAULT.functionGlyph)
        assertNull("默认皮肤不得覆盖功能键次要文字色", KeyboardSkins.DEFAULT.functionHint)
    }

    @Test
    fun 展示顺序默认固定首位_其余按键面亮度从亮到暗() {
        // 亮白主题的键面令牌（默认皮肤不覆盖键面色，亮度由它兜底）
        val fallback = 0xFFFFFFFF.toInt()
        val ordered = KeyboardSkins.orderedForDisplay(fallback)
        assertEquals("展示清单必须与定义清单同量", KeyboardSkins.ALL.size, ordered.size)
        assertEquals(
            "默认必须固定在第一位且不参与排序（它的亮度随主题变化，排进去名次会漂）",
            KeyboardSkins.DEFAULT_ID,
            ordered.first().id,
        )
        // 其余按亮度从亮到暗；跑两套主题令牌，证明默认之外的名次不受主题影响
        for (base in listOf(0xFFFFFFFF.toInt(), 0xFF242831.toInt())) {
            val rest = KeyboardSkins.orderedForDisplay(base).drop(1)
            assertEquals("除默认外不得重复", rest.size, rest.map { it.id }.toSet().size)
            for (i in 0 until rest.size - 1) {
                val cur = KeyboardSkins.faceBrightness(rest[i], base)
                val next = KeyboardSkins.faceBrightness(rest[i + 1], base)
                assertTrue(
                    "展示顺序必须从亮到暗：第 ${i + 1} 位「${rest[i].label}」($cur) 比第 ${i + 2} 位" +
                        "「${rest[i + 1].label}」($next) 更暗",
                    cur >= next - 1e-9,
                )
            }
        }
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
                "皮肤 ${s.id} 的标签应为两个字（外观页选项每行五个、等分宽度，" +
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
    fun 未声明按压色时默认皮肤走令牌_皮肤按首色推导() {
        // 判据必须是 isDefault：彩虹皮肤同样没有 keyFill，用「keyFill == null」判会把它的按压态
        // 取成主题深灰，按键一按就从彩色闪成暗块。
        val fallbackFace = 0xFFF2F4F8.toInt()
        val fallbackPressed = 0xFFD0D5E0.toInt()
        fun visual(s: KeyboardSkin) = KeyboardSkins.visualFor(
            s, 0, 1f, 1f, fallbackFace, fallbackPressed,
            0xFF101010.toInt(), 0xFF666666.toInt(), 0xFFFF0000.toInt(),
        )

        assertEquals("默认皮肤必须沿用历史令牌按压色", fallbackPressed, visual(KeyboardSkins.DEFAULT).pressed)

        val rainbow = KeyboardSkins.byId("rainbow")
        assertTrue("取到的应是彩虹皮肤", !rainbow.isDefault && rainbow.keyPressed == null)
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
}
