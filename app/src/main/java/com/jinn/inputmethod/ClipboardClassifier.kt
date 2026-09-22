package com.jinn.inputmethod

import java.util.regex.Pattern

/**
 * 剪贴板内容自动分类。
 *
 * - URL：http/https、常见网址、深链接（myapp://）、IP 地址
 * - NUMBER：手机号、验证码、订单号、金额、纯数字串等
 * - OTHER：其余内容，只分这三类，余者一律 OTHER
 *
 * 数字分类不能只看「是否含数字」，否则 "我有123个苹果" 会误判成数字。
 *
 * 纯 JVM 可测，不依赖 Android，正则常量都收在这里。
 */
object ClipboardClassifier {

    /** 分类取值（与数据库 category 列一致） */
    const val CATEGORY_URL = "URL"
    const val CATEGORY_NUMBER = "NUMBER"
    const val CATEGORY_OTHER = "OTHER"

    /** 自动分类：URL 优先，其次数字，否则 OTHER。不输出明文日志。 */
    fun classify(text: String): String = when {
        isUrl(text) -> CATEGORY_URL
        isNumber(text) -> CATEGORY_NUMBER
        else -> CATEGORY_OTHER
    }

    // ── 网址识别 ──────────────────────────────────────────

    private val URL_PATTERN = Pattern.compile(
        "(?i)\\b(?:https?|ftp)://[^\\s]+"          // http/https/ftp
    )
    private val SCHEME_PATTERN = Pattern.compile(
        "\\b[a-z][a-z0-9+.-]{1,15}://[^\\s]+"       // 深链接 myapp://abc
    )
    private val IP_PATTERN = Pattern.compile(
        "\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b" // IP 地址
    )
    private val WWW_PATTERN = Pattern.compile(
        "(?i)\\bwww\\.[a-z0-9-]+\\.[a-z]{2,}[^\\s]*"       // www.example.com
    )
    private val DOMAIN_PATTERN = Pattern.compile(
        "(?i)\\b[a-z0-9-]+\\.(?:com|cn|net|org|io|dev|me|app|xyz|top|info|biz|edu|gov|tv|cc)(?:/[^\\s]*)?\\b"
    )

    private fun isUrl(text: String): Boolean {
        if (text.isBlank()) return false
        return URL_PATTERN.matcher(text).find() ||
            SCHEME_PATTERN.matcher(text).find() ||
            IP_PATTERN.matcher(text).find() ||
            WWW_PATTERN.matcher(text).find() ||
            DOMAIN_PATTERN.matcher(text).find()
    }

    // ── 数字识别 ──────────────────────────────────────────

    private val PHONE_PATTERN = Pattern.compile(
        "\\b1[3-9]\\d{9}\\b"                        // 大陆手机号
    )
    private val PHONE_ALT_PATTERN = Pattern.compile(
        "\\b(?:\\+?86[ -]?)?1[3-9]\\d{1}[ -]?\\d{4}[ -]?\\d{4}\\b"
    )
    private val VERIFY_CODE_PATTERN = Pattern.compile(
        "\\b\\d{4,8}\\b"                            // 4-8 位数字（验证码/订单号常见）
    )
    private val AMOUNT_PATTERN = Pattern.compile(
        "[¥￥$€]\\s*\\d+(?:\\.\\d{1,2})?\\b"         // 金额
    )
    private val NUMBER_ONLY_PATTERN = Pattern.compile(
        "^\\d{6,20}$"                               // 纯数字串（订单号/卡号等）
    )
    private val LONG_NUMBER_CTX = Pattern.compile(
        // 下界与 VERIFY_CODE_PATTERN（4-8 位验证码）对齐：原先要求 ≥6 位，导致同一串
        // 验证码「1234」不带标签进数字、带标签「验证码：1234」反而进 OTHER，用户按
        // 「数字」筛选时看不到它。关键词表本身足够窄（订单号/编号/验证码/金额…），
        // 放宽到 4 位不会把「第 1234 章」这类无关文本拉进来（没有关键词就不匹配）。
        "(?i)(?:订单号|单号|编号|流水号|验证码|交易号|卡号|订单|金额)[:：\\s]*\\d{4,}"
    )

    private fun isNumber(text: String): Boolean {
        if (text.isBlank()) return false
        // 金额 / 手机号 / 纯数字串 / 数字上下文直接判定
        if (AMOUNT_PATTERN.matcher(text).find()) return true
        if (PHONE_PATTERN.matcher(text).find() || PHONE_ALT_PATTERN.matcher(text).find()) return true
        if (NUMBER_ONLY_PATTERN.matcher(text).find()) return true
        if (LONG_NUMBER_CTX.matcher(text).find()) return true
        // 短数字（验证码）要求整段基本是数字，避免 "我有123个苹果" 误判
        val trimmed = text.trim()
        if (VERIFY_CODE_PATTERN.matcher(trimmed).matches()) return true
        return false
    }
}
