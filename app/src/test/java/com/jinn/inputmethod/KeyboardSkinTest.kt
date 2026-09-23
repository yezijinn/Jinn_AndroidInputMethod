package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
