package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 剪贴板自动分类单测（纯 JVM）。
 */
class ClipboardClassifierTest {

    // ── 网址分类 ──────────────────────────────────────────

    @Test
    fun classifiesHttpUrl() {
        assertEquals(ClipboardClassifier.CATEGORY_URL, ClipboardClassifier.classify("https://example.com"))
        assertEquals(ClipboardClassifier.CATEGORY_URL, ClipboardClassifier.classify("http://192.168.1.1"))
    }

    @Test
    fun classifiesDeepLink() {
        assertEquals(ClipboardClassifier.CATEGORY_URL, ClipboardClassifier.classify("myapp://abc/def"))
        assertEquals(ClipboardClassifier.CATEGORY_URL, ClipboardClassifier.classify("weixin://dl/chat"))
    }

    @Test
    fun classifiesWwwAndDomain() {
        assertEquals(ClipboardClassifier.CATEGORY_URL, ClipboardClassifier.classify("www.baidu.com"))
        assertEquals(ClipboardClassifier.CATEGORY_URL, ClipboardClassifier.classify("example.com/path"))
    }

    // ── 数字分类 ──────────────────────────────────────────

    @Test
    fun classifiesPhoneNumber() {
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("13800138000"))
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("手机号 138-0013-8000"))
    }

    @Test
    fun classifiesVerifyCodeAndOrder() {
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("验证码 482913"))
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("订单号 20260815001"))
        // 4~5 位验证码带标签时同样属于数字（下界原先卡在 6 位，与「验证码 4-8 位」的标准不一致）
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("验证码：1234"))
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("验证码 12345"))
        // 没有关键词的短数字仍归 OTHER：放宽下界不能把普通正文拉进数字分类
        assertEquals(ClipboardClassifier.CATEGORY_OTHER, ClipboardClassifier.classify("第 1234 章"))
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("888666"))
    }

    @Test
    fun classifiesAmount() {
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("¥128.50"))
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("价格：$99"))
    }

    @Test
    fun doesNotClassifySentenceWithNumber() {
        // "我有123个苹果" 不应因为含数字被分类为数字
        assertNotEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("我有123个苹果"))
        assertNotEquals(ClipboardClassifier.CATEGORY_NUMBER, ClipboardClassifier.classify("今天天气很好"))
    }

    // ── 其他分类 ──────────────────────────────────────────

    @Test
    fun classifiesOtherText() {
        assertEquals(ClipboardClassifier.CATEGORY_OTHER, ClipboardClassifier.classify("Hello World"))
        assertEquals(ClipboardClassifier.CATEGORY_OTHER, ClipboardClassifier.classify("这是一个测试文本"))
        assertEquals(ClipboardClassifier.CATEGORY_OTHER, ClipboardClassifier.classify(""))
    }

    // ── 隐私分类绝不自动 ──────────────────────────────────

    @Test
    fun neverAutoClassifiesPrivate() {
        // 密码/验证码/身份证等即使内容敏感，也不自动归为隐私分类
        assertNotEquals("PRIVATE", ClipboardClassifier.classify("password=MyP@ssw0rd"))
        assertNotEquals("PRIVATE", ClipboardClassifier.classify("验证码 123456"))
        assertNotEquals("PRIVATE", ClipboardClassifier.classify("110105199003078915"))
    }
}
