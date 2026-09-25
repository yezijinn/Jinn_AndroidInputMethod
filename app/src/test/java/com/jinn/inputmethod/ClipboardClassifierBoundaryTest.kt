package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 剪贴板自动分类的边界回归测试。
 *
 * 单独建这个文件：剪贴板正文常从网页/文档复制而来，普遍带尾随换行，
 * 而分类器里两条数字规则的处理方式并不统一：
 *  - `NUMBER_ONLY_PATTERN`（`^\d{6,20}$`）用 `matcher(text).find()`，未 trim；
 *  - `VERIFY_CODE_PATTERN`（`\b\d{4,8}\b`）先 `trim()` 再用 `matches()`。
 *
 * 实测两者都能正确处理 `\n` / `\r\n`（Java 的 `$` 默认匹配多种行终止符，
 * 所以未 trim 也不受影响）；但这些行为依赖正则的默认语义，容易在
 * 「优化正则」时被无意改坏，所以用测试钉住。
 *
 * 覆盖：
 *  - 纯数字串在各种首尾空白下的分类
 *  - 长度边界（验证码 4-8 位 / 纯数字串 6-20 位 / 超上限）
 *  - 17 位与 19 位银行卡号（超出验证码范围，只能靠纯数字串规则命中）
 *  - 「含数字但不是数字」的负例（避免误分类）
 */
class ClipboardClassifierBoundaryTest {

    private fun classify(text: String) = ClipboardClassifier.classify(text)

    /** 各种首尾空白包裹的 6 位数字，都应判为 NUMBER */
    @Test
    fun sixDigitWithSurroundingWhitespaceIsNumber() {
        val cases = listOf(
            "123456", "123456\n", "123456\r\n", "123456\r", "123456 ",
            " 123456", "\n123456\n", " 123456 ",
        )
        for (c in cases) {
            assertEquals(
                "含空白的纯数字应判为 NUMBER：[$c]",
                ClipboardClassifier.CATEGORY_NUMBER, classify(c),
            )
        }
    }

    /**
     * 长数字（超出验证码 4-8 位范围）只能靠 `^\d{6,20}$` 命中 ，
     * 这条路径未做 trim，因此必须显式守护带换行的场景。
     */
    @Test
    fun longNumberWithTrailingNewlineIsNumber() {
        val cases = listOf(
            "12345678901234567890" to "20 位（上限）",
            "12345678901234567890\n" to "20 位 + NL",
            "12345678901234567890\r\n" to "20 位 + CRLF",
            "6222021234567890123" to "19 位银行卡",
            "6222021234567890123\r\n" to "19 位银行卡 + CRLF",
            "1234567890123456789" to "19 位",
            "1234567890123456789\r\n" to "19 位 + CRLF",
        )
        for ((text, desc) in cases) {
            assertEquals(
                "长数字应判为 NUMBER（$desc）",
                ClipboardClassifier.CATEGORY_NUMBER, classify(text),
            )
        }
    }

    /** 超过 20 位的数字不在纯数字串规则范围内，回落 OTHER（当前设计如此） */
    @Test
    fun overTwentyDigitsFallsBackToOther() {
        assertEquals(
            ClipboardClassifier.CATEGORY_OTHER,
            classify("123456789012345678901"), // 21 位
        )
    }

    /** 负例：正文含短数字但语义不是数字，不得误判（文档明确要求的边界） */
    @Test
    fun textContainingShortDigitsIsNotNumber() {
        val cases = listOf(
            "我有123个苹果", "版本 2.0.1 发布说明",
        )
        for (c in cases) {
            assertEquals(
                "含短数字的普通文本不应判为 NUMBER：$c",
                ClipboardClassifier.CATEGORY_OTHER, classify(c),
            )
        }
    }

    /**
     * 语义变更（2026-09-25）：数字分组改为「提取内容里的数字片段」，因此**句中长数字串
     * （≥6 位）也算数字**——「第1234567章读后感」进数字组并只显示 1234567。
     * 短数字（<6 位）仍要求整段数字或带关键词，避免「我有123个苹果」被拉进来。
     */
    @Test
    fun longDigitsInsideSentenceCountAsNumber() {
        assertEquals(ClipboardClassifier.CATEGORY_NUMBER, classify("第1234567章读后感"))
        assertEquals("1234567", ClipboardClassifier.firstNumber("第1234567章读后感"))
    }

    /** 空与纯空白：不得崩溃，且不判为数字 */
    @Test
    fun blankInputIsOtherAndDoesNotCrash() {
        for (c in listOf("", " ", "\n", "\t", "  \r\n  ")) {
            assertEquals(
                "空白输入应判为 OTHER", ClipboardClassifier.CATEGORY_OTHER, classify(c),
            )
        }
    }

    /**
     * 多标签（2026-09-25 起）：分组按「含哪类片段」划分，URL 里的长数字同样要给数字标签 ——
     * 该条既要能在「网址」组看到链接，也要能在「数字」组看到订单号。
     */
    @Test
    fun urlWithLongDigitsCarriesBothLabels() {
        assertEquals("URL,NUMBER", classify("https://example.com/order/123456789"))
    }
}
