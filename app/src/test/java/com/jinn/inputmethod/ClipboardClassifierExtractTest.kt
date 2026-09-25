package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板分组「提取干净片段」单测（纯 JVM）。
 *
 * 覆盖 2026-09-25 的需求：分组不再按「整条内容属于哪类」筛选，而是从内容里提取该类的片段，
 * 展示与粘贴都用这个片段（`firstUrl` / `firstNumber`）；多标签分类保证「进了某分组
 * ⇒ 一定能提取到片段」。
 */
class ClipboardClassifierExtractTest {

    private val classifier = ClipboardClassifier

    /** 用户给的原始例子：一条内容同时进两个分组，各自只显示自己的片段 */
    @Test
    fun userExampleSplitsIntoBothGroups() {
        val text = "我的电话是123456,你记住了吗?记得访问www.baidu.com"
        assertEquals(
            "既含网址又含数字时应同时带两个标签",
            "URL,NUMBER",
            classifier.classify(text),
        )
        assertEquals("网址分组只显示网址片段", "www.baidu.com", classifier.firstUrl(text))
        assertEquals("数字分组只显示数字片段", "123456", classifier.firstNumber(text))
    }

    // ── 网址片段 ──────────────────────────────────────────

    @Test
    fun urlExtractionStripsTrailingPunctuation() {
        assertEquals("www.baidu.com", classifier.firstUrl("记得访问www.baidu.com。"))
        assertEquals("https://example.com/a", classifier.firstUrl("见 https://example.com/a，谢谢"))
        assertEquals("https://example.com/a", classifier.firstUrl("（https://example.com/a）"))
        assertEquals("example.com/path", classifier.firstUrl("example.com/path。"))
    }

    @Test
    fun urlExtractionCoversAllForms() {
        assertEquals("https://example.com/x", classifier.firstUrl("https://example.com/x"))
        assertEquals("myapp://abc/def", classifier.firstUrl("打开 myapp://abc/def"))
        assertEquals("192.168.1.1", classifier.firstUrl("路由器 192.168.1.1"))
        assertEquals("www.baidu.com", classifier.firstUrl("www.baidu.com"))
    }

    /** 只取最先出现的那个：句子前面的优先，不按规则种类优先级 */
    @Test
    fun urlExtractionTakesEarliestMatch() {
        assertEquals(
            "www.first.com",
            classifier.firstUrl("先看 www.first.com 再看 https://second.com/x"),
        )
    }

    // ── 数字片段 ──────────────────────────────────────────

    @Test
    fun numberExtractionNormalizesForms() {
        // 手机号变体去空格/连字符
        assertEquals("13800138000", classifier.firstNumber("手机号 138-0013-8000"))
        assertEquals("13800138000", classifier.firstNumber("手机号 +86 138 0013 8000"))
        // 金额只留数字（分组名就是「数字」）
        assertEquals("128.50", classifier.firstNumber("¥128.50"))
        assertEquals("99", classifier.firstNumber("价格：$99"))
        // 关键词后只取号码
        assertEquals("1234", classifier.firstNumber("验证码：1234"))
        assertEquals("20260815001", classifier.firstNumber("订单号 20260815001"))
        // 整段短数字
        assertEquals("1234", classifier.firstNumber("1234"))
        // 句中长数字串
        assertEquals("123456", classifier.firstNumber("我的电话是123456，记住了吗"))
        assertEquals("1234567890123456", classifier.firstNumber("卡 1234567890123456 已到账"))
    }

    @Test
    fun numberExtractionTakesEarliestMatch() {
        assertEquals(
            "12345678",
            classifier.firstNumber("先 12345678 再 87654321"),
        )
    }

    /** 负例：含数字但不是「数字片段」的正文不得被提取（避免数字分组被噪音撑满） */
    @Test
    fun shortDigitsInSentenceAreNotExtracted() {
        assertNull("句中 3 位数字不算片段", classifier.firstNumber("我有123个苹果"))
        assertNull("句中 4 位数字无关键词不算片段", classifier.firstNumber("第 1234 章"))
        assertNull("版本号逐段都太短", classifier.firstNumber("版本 2.0.1 发布说明"))
        assertNull("纯文本", classifier.firstNumber("今天天气很好"))
    }

    /** 超过 20 位的连续数字不是片段（与分类器的既有口径一致） */
    @Test
    fun overTwentyDigitsNotExtracted() {
        assertNull(classifier.firstNumber("123456789012345678901"))
    }

    // ── 多标签 ────────────────────────────────────────────

    @Test
    fun singleLabelCases() {
        assertEquals("URL", classifier.classify("https://example.com/no-digits"))
        assertEquals("NUMBER", classifier.classify("13800138000"))
        assertEquals("OTHER", classifier.classify("Hello World"))
        assertEquals("OTHER", classifier.classify(""))
    }

    /** 不变式：分组标签与实际提取结果必须一致（筛选进来一定能显示片段） */
    @Test
    fun labelsAlwaysImplyExtractablePiece() {
        val samples = listOf(
            "我的电话是123456,你记住了吗?记得访问www.baidu.com",
            "https://example.com/order/123456789",
            "手机号 138-0013-8000",
            "验证码：1234",
            "www.baidu.com",
            "192.168.1.1",
            "第1234567章读后感",
            "Hello World",
            "我有123个苹果",
        )
        for (s in samples) {
            val labels = classifier.classify(s)
            if (classifier.hasLabel(labels, ClipboardClassifier.CATEGORY_URL)) {
                assertTrue("带 URL 标签却提取不到网址：$s", classifier.firstUrl(s) != null)
            }
            if (classifier.hasLabel(labels, ClipboardClassifier.CATEGORY_NUMBER)) {
                assertTrue("带 NUMBER 标签却提取不到数字：$s", classifier.firstNumber(s) != null)
            }
            if (labels == ClipboardClassifier.CATEGORY_OTHER) {
                assertNull("判为 OTHER 却提取到网址：$s", classifier.firstUrl(s))
                assertNull("判为 OTHER 却提取到数字：$s", classifier.firstNumber(s))
            }
        }
    }

    @Test
    fun normalizeLabelsFromExternalSource() {
        assertEquals("URL,NUMBER", classifier.normalizeLabels("URL,NUMBER"))
        assertEquals("URL,NUMBER", classifier.normalizeLabels("NUMBER,URL")) // 顺序归一
        assertEquals("URL", classifier.normalizeLabels("URL,URL"))           // 去重
        assertEquals("NUMBER", classifier.normalizeLabels("NUMBER"))
        assertEquals("OTHER", classifier.normalizeLabels("OTHER"))
        assertEquals("OTHER", classifier.normalizeLabels("FAVORITE"))        // 伪分类不得进列
        assertEquals("OTHER", classifier.normalizeLabels("乱写的值"))
        assertEquals("OTHER", classifier.normalizeLabels(null))
    }

    // ── 列表展示 / 粘贴取值 ───────────────────────────────

    @Test
    fun pieceForSelectsByGroup() {
        val text = "我的电话是123456,你记住了吗?记得访问www.baidu.com"
        assertEquals("网址组给网址片段", "www.baidu.com", classifier.pieceFor("URL", text))
        assertEquals("数字组给数字片段", "123456", classifier.pieceFor("NUMBER", text))
        assertEquals("全部分组给原文", text, classifier.pieceFor(null, text))
        assertEquals("收藏分组给原文", text, classifier.pieceFor("FAVORITE", text))
        assertEquals(
            "提取不到时回退原文（不留空白行）",
            "没有片段", classifier.pieceFor("URL", "没有片段"),
        )
    }

    @Test
    fun labelTextIsHumanReadable() {
        assertEquals("网址", classifier.labelText("URL"))
        assertEquals("数字", classifier.labelText("NUMBER"))
        assertEquals("网址·数字", classifier.labelText("URL,NUMBER"))
        assertEquals("OTHER 不显示标签", "", classifier.labelText("OTHER"))
        assertEquals("", classifier.labelText(null))
    }

    // ── 平台语义守卫 ──────────────────────────────────────

    /**
     * 源码守卫：分类器里**不准出现单词边界断言 `\b` / `\B`**。
     *
     * Android 的 `Pattern` 是 ICU 实现（`\b` = Unicode 单词边界，汉字算单词字符），
     * JVM 的是 ASCII 语义 —— 同一个正则在两端行为不同，而单测跑在 JVM 上**永远看不出来**。
     * 2026-09-25 真机踩坑：「我的电话是123456…」在设备上只拿到 URL 标签（数字边界没成立，
     * URL 是裸域名 `baidu.com` 命中的）。边界只能用显式前后瞻（见 `NOT_DIGIT_BEFORE`）。
     */
    @Test
    fun sourceMustNotUseWordBoundary() {
        val f = listOf(
            java.io.File("src/main/java/com/jinn/inputmethod/ClipboardClassifier.kt"),
            java.io.File("app/src/main/java/com/jinn/inputmethod/ClipboardClassifier.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 ClipboardClassifier.kt")
        // 注释里可能引用这两个记号（说明规则），只看代码行
        val code = f.readLines()
            .filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } }
            .joinToString("\n")
        assertTrue("分类器代码里禁止用单词边界 b", !code.contains("\\b"))
        assertTrue("分类器代码里禁止用单词边界 B", !code.contains("\\B"))
    }

    @Test
    fun hasLabelChecksSingleLabels() {
        assertTrue(classifier.hasLabel("URL,NUMBER", ClipboardClassifier.CATEGORY_URL))
        assertTrue(classifier.hasLabel("URL,NUMBER", ClipboardClassifier.CATEGORY_NUMBER))
        assertTrue(classifier.hasLabel("URL", ClipboardClassifier.CATEGORY_URL))
        assertTrue(!classifier.hasLabel("OTHER", ClipboardClassifier.CATEGORY_URL))
        assertTrue(!classifier.hasLabel(null, ClipboardClassifier.CATEGORY_URL))
    }
}
