package com.jinn.voiceinput

import java.util.regex.Pattern

/**
 * 敏感内容检测（方案第四阶段）。
 *
 * 第一阶段用规则匹配（正则），识别剪贴板中可能泄露的凭证类内容：
 * 密码、短信验证码、身份证、银行卡、API Key / Token / JWT、Cookie、私钥等。
 *
 * 检测到敏感内容时，按设置决定：不保存 / 临时保存 / 按普通内容保存。
 * 检测是启发式的：宁可误报（保守不保存），不可漏报（凭证进长期历史）。
 *
 * 纯 JVM 可测（不依赖 Android），正则常量收敛于此。
 */
object SensitiveDetector {

    /** 检测结果：命中类型（多类命中取第一个） */
    data class Match(val type: String, val sample: String)

    /** 完整检测：返回命中的敏感类型；未命中返回 null */
    fun detect(text: String): Match? {
        if (text.isBlank()) return null
        // 逐规则匹配，命中即返回（顺序即优先级）
        RULES.forEach { rule ->
            val m = rule.pattern.matcher(text)
            if (m.find()) {
                val sample = m.group().take(12)
                return Match(rule.name, sample)
            }
        }
        return null
    }

    /** 是否为验证码/密码类（短时凭证，临时保存 30s 或 5min 的典型） */
    fun isShortLivedCredential(match: Match): Boolean =
        match.type == TYPE_VERIFY_CODE || match.type == TYPE_PASSWORD

    private data class Rule(val name: String, val pattern: Pattern)

    private const val TYPE_VERIFY_CODE = "verify_code"
    private const val TYPE_PASSWORD = "password"
    private const val TYPE_ID_CARD = "id_card"
    private const val TYPE_BANK_CARD = "bank_card"
    private const val TYPE_API_KEY = "api_key"
    private const val TYPE_JWT = "jwt"
    private const val TYPE_PRIVATE_KEY = "private_key"
    private const val TYPE_COOKIE = "cookie"

    private val RULES = listOf(
        // 验证码：紧跟「验证码/校验码/动态码」之后的 4~8 位数字，或独立成行的 6 位数字
        Rule(TYPE_VERIFY_CODE, Pattern.compile(
            "(?:验证码|校验码|动态码|验证短信|安全码)[是为:：\\s]*[（(]?\\d{4,8}[)）]?"
        )),
        Rule(TYPE_VERIFY_CODE, Pattern.compile(
            "\\b\\d{6}\\b(?=.*(?:验证码|登录|注册|校验))"
        )),
        // 身份证：18 位（末位可为 X），或 15 位
        Rule(TYPE_ID_CARD, Pattern.compile(
            "\\b[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]\\b"
        )),
        Rule(TYPE_ID_CARD, Pattern.compile("\\b[1-9]\\d{7}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}\\b")),
        // 银行卡：16~19 位（可能带空格/连字符分组）
        Rule(TYPE_BANK_CARD, Pattern.compile(
            "\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?:[ -]?\\d{3})?\\b"
        )),
        // 通用 API Key / Token：常见前缀
        Rule(TYPE_API_KEY, Pattern.compile(
            "(?i)\\b(?:api[_-]?key|access[_-]?token|secret[_-]?key|app[_-]?secret|bearer)\\s*[=:：]\\s*[A-Za-z0-9_\\-]{16,}"
        )),
        // JWT：三段 base64url
        Rule(TYPE_JWT, Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"
        )),
        // 私钥 PEM
        Rule(TYPE_PRIVATE_KEY, Pattern.compile(
            "-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"
        )),
        // Cookie 会话标识
        Rule(TYPE_COOKIE, Pattern.compile(
            "(?i)\\b(?:sessionid|jsessionid|auth[_-]?token|connect\\.sid)\\s*=\\s*[A-Za-z0-9%._\\-]{10,}"
        )),
        // 密码字段（明文的 password= 或「密码：xxx」）
        Rule(TYPE_PASSWORD, Pattern.compile(
            "(?i)(?:password|passwd|pwd|密码|口令)\\s*[=:：]\\s*\\S{6,}"
        )),
    )
}
