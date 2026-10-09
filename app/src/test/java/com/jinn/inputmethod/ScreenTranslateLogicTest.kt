package com.jinn.inputmethod

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScreenTranslateLogic] 的纯 JVM 单测（与 Translation 系列同一口径，不依赖 Robolectric）。
 *
 * 这里钉的都是**会让用户看到错东西或白花钱**的判据：
 *  · 触发面：什么样的文本算「可采」；
 *  · 付费面：勾选为空 / 全是空白 ⇒ 绝不发请求；
 *  · 写回面：什么样的段才允许整节点替换；
 *  · 采集面：哪些窗口不该采（系统遮罩 / 键盘 / 自己）。
 */
class ScreenTranslateLogicTest {

    // ── 过滤规则 ───────────────────────────────────────

    @Test
    fun 单字符与纯符号不可采集() {
        assertFalse(ScreenTranslateLogic.isCollectable("+"))
        assertFalse(ScreenTranslateLogic.isCollectable("·—"))
        assertFalse(ScreenTranslateLogic.isCollectable("》"))
    }

    @Test
    fun 纯零宽与纯空白不可采集() {
        assertFalse(ScreenTranslateLogic.isCollectable("   "))
        assertFalse(ScreenTranslateLogic.isCollectable("\u200B\u2060"))
        assertFalse(ScreenTranslateLogic.isCollectable("\u00A0\u200B"))
    }

    @Test
    fun 中日韩与字母数字可采集() {
        assertTrue(ScreenTranslateLogic.isCollectable("中文"))
        assertTrue(ScreenTranslateLogic.isCollectable("hi"))
        assertTrue(ScreenTranslateLogic.isCollectable("12"))
        assertTrue(ScreenTranslateLogic.isCollectable("こんにちは"))
        assertTrue(ScreenTranslateLogic.isCollectable("안녕"))
    }

    @Test
    fun 含零宽但确有可见字符仍可采集() {
        assertTrue(ScreenTranslateLogic.isCollectable("a\u200B"))
    }

    // ── 整节点判据（替换安全阀） ─────────────────────────

    @Test
    fun 整节点判定要求与节点全文逐字相等() {
        assertTrue(ScreenTranslateLogic.isWholeNode("abc", "abc"))
        assertTrue(ScreenTranslateLogic.isWholeNode("abc", "abc\n"))
        assertTrue(ScreenTranslateLogic.isWholeNode("abc", "  abc  "))
        assertFalse(ScreenTranslateLogic.isWholeNode("abc", "xabc"))
        assertFalse(ScreenTranslateLogic.isWholeNode("abc", null))
    }

    // ── 勾选归并（付费闸门） ─────────────────────────────

    @Test
    fun 未勾选不发请求() {
        assertNull(ScreenTranslateLogic.composeSource(emptyList(), 5000))
    }

    @Test
    fun 勾选全是空白不发请求() {
        assertNull(ScreenTranslateLogic.composeSource(listOf(" ", "", "\u200B"), 5000))
    }

    @Test
    fun 多段按顺序以换行合并() {
        val out = ScreenTranslateLogic.composeSource(listOf("a", "b", "c"), 5000)
        assertEquals("a\nb\nc", out!!.first)
        assertFalse(out.second)
    }

    @Test
    fun 合并后仍按字节上限截断() {
        // "abc" 上限 2 字节 ⇒ 只发 "ab"，并如实标记截断（截断后不给替换）
        val out = ScreenTranslateLogic.composeSource(listOf("abc"), 2)
        assertEquals("ab", out!!.first)
        assertTrue(out.second)
    }

    @Test
    fun 上限吃不下一个字符时视为无内容() {
        // maxBytes = 0 ⇒ takeHeadBytes 返回 "" to true ⇒ composeSource 归 null（不发请求）
        assertNull(ScreenTranslateLogic.composeSource(listOf("abc"), 0))
    }

    @Test
    fun 按码位截断不切坏汉字() {
        // "中文" = 6 字节，上限 4 ⇒ 只能取到「中」（3 字节），不会出现半个字
        val out = ScreenTranslateLogic.composeSource(listOf("中文"), 4)
        assertEquals("中", out!!.first)
        assertTrue(out.second)
    }

    // ── 目标语言（与 IME 侧同口径） ──────────────────────

    @Test
    fun 英文文本配英文目标时互换为中文() {
        assertEquals(
            TranslateTarget.To(TranslationLanguage.CHINESE),
            ScreenTranslateLogic.decideTarget("hello world", TranslationProviderId.ALIYUN, "ENGLISH"),
        )
    }

    @Test
    fun 中文文本配中文目标时互换为英文() {
        assertEquals(
            TranslateTarget.To(TranslationLanguage.ENGLISH),
            ScreenTranslateLogic.decideTarget("你好世界", TranslationProviderId.DEEPL, "CHINESE"),
        )
    }

    @Test
    fun OpenAI自由文本不互换() {
        assertEquals(
            TranslateTarget.To(TranslationLanguage.ENGLISH),
            ScreenTranslateLogic.decideTarget("hello world", TranslationProviderId.OPENAI, "ENGLISH"),
        )
    }

    @Test
    fun 无法判定源语言时保持目标不变() {
        assertEquals(
            TranslateTarget.To(TranslationLanguage.JAPANESE),
            ScreenTranslateLogic.decideTarget("123", TranslationProviderId.ALIYUN, "JAPANESE"),
        )
    }

    @Test
    fun 日文文本配日文目标时不发请求() {
        // 日 / 韩没有对称对调：旧口径下会照发一次请求，服务端原样返回原文且真计费（L-1131）
        assertEquals(
            TranslateTarget.AlreadyTarget(TranslationLanguage.JAPANESE),
            ScreenTranslateLogic.decideTarget("こんにちは世界", TranslationProviderId.ALIYUN, "JAPANESE"),
        )
    }

    @Test
    fun 韩文文本配韩文目标时不发请求() {
        assertEquals(
            TranslateTarget.AlreadyTarget(TranslationLanguage.KOREAN),
            ScreenTranslateLogic.decideTarget("안녕하세요", TranslationProviderId.BAIDU, "KOREAN"),
        )
    }

    @Test
    fun 日文文本配英文目标时照发不误拦() {
        // 只有「源 == 目标」才拦；判定出的是别的语言时一律照发，不能把正常请求挡掉
        assertEquals(
            TranslateTarget.To(TranslationLanguage.ENGLISH),
            ScreenTranslateLogic.decideTarget("こんにちは世界", TranslationProviderId.ALIYUN, "ENGLISH"),
        )
    }

    @Test
    fun 已经是目标语言的提示带目标语言名() {
        assertEquals(
            "这段文字已经是「日本語」，未发送请求（换个目标语言再试）",
            alreadyTargetMessage(TranslationLanguage.JAPANESE),
        )
    }

    // ── 排序键 ─────────────────────────────────────────

    @Test
    fun 行带归并按阈值分组() {
        assertEquals(0, ScreenTranslateLogic.bandOf(0, 24))
        assertEquals(0, ScreenTranslateLogic.bandOf(23, 24))
        assertEquals(1, ScreenTranslateLogic.bandOf(24, 24))
        assertEquals(1, ScreenTranslateLogic.bandOf(30, 24))
        assertEquals(8, ScreenTranslateLogic.bandOf(200, 24))
    }

    @Test
    fun 行带阈值非法时退化为按top精确分组() {
        assertEquals(37, ScreenTranslateLogic.bandOf(37, 0))
        assertEquals(37, ScreenTranslateLogic.bandOf(37, -8))
    }

    // ── 可替换（静态三闸） ───────────────────────────────

    @Test
    fun 只有单段可编辑整节点且未截断才可替换() {
        assertTrue(ScreenTranslateLogic.replaceable(1, editable = true, wholeNode = true, truncated = false))
        assertFalse(ScreenTranslateLogic.replaceable(2, editable = true, wholeNode = true, truncated = false))
        assertFalse(ScreenTranslateLogic.replaceable(0, editable = true, wholeNode = true, truncated = false))
        assertFalse(ScreenTranslateLogic.replaceable(1, editable = false, wholeNode = true, truncated = false))
        assertFalse(ScreenTranslateLogic.replaceable(1, editable = true, wholeNode = false, truncated = false))
        assertFalse(ScreenTranslateLogic.replaceable(1, editable = true, wholeNode = true, truncated = true))
    }

    // ── 窗口过滤 ───────────────────────────────────────

    @Test
    fun 只采非本应用的应用窗口() {
        val self = "com.jinn.inputmethod"
        assertTrue(ScreenTranslateLogic.isCapturableWindow(AccessibilityWindowInfo.TYPE_APPLICATION, "com.tencent.mm", self))
        assertFalse(ScreenTranslateLogic.isCapturableWindow(AccessibilityWindowInfo.TYPE_SYSTEM, "com.android.systemui", self))
        assertFalse(ScreenTranslateLogic.isCapturableWindow(AccessibilityWindowInfo.TYPE_INPUT_METHOD, "com.jinn.inputmethod", self))
        assertFalse(ScreenTranslateLogic.isCapturableWindow(AccessibilityWindowInfo.TYPE_APPLICATION, self, self))
        assertFalse(ScreenTranslateLogic.isCapturableWindow(AccessibilityWindowInfo.TYPE_APPLICATION, null, self))
    }

    // ── 钳制 ───────────────────────────────────────────

    @Test
    fun 钳制遇到非法区间回落下界() {
        assertEquals(5, ScreenTranslateLogic.clamp(5, 0, 10))
        assertEquals(0, ScreenTranslateLogic.clamp(-3, 0, 10))
        assertEquals(10, ScreenTranslateLogic.clamp(99, 0, 10))
        assertEquals(4, ScreenTranslateLogic.clamp(99, 4, 2))
    }
}
