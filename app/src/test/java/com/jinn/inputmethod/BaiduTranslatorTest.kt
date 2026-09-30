package com.jinn.inputmethod

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * 百度翻译的纯函数守卫：MD5 签名、请求形态、业务错误码分类。
 *
 * 百度的两个坑必须在这里钉死：
 *  1. `sign` 用的是**未编码原文**（URL 编码由 OkHttp 负责）—— 拿编码后的串签名是最常见的 54001；
 *  2. 业务错误码放在 **HTTP 200 的响应体**里（`error_code`）—— 不先看它就会把「签名错误」显示成「结果为空」。
 */
class BaiduTranslatorTest {

    private val translator = BaiduTranslator(APP_ID, SECRET, saltSource = { SALT })

    /**
     * 独立复算 MD5（`BigInteger` 无符号口径），与被测实现的 `"%02x"` 写法互为交叉验证：
     * 用同一个实现自证的话，负字节补零写错也照样绿。
     */
    private fun md5Reference(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    // ── 签名 ────────────────────────────────────────────────

    @Test
    fun `md5Hex 与标准向量一致`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", BaiduTranslator.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", BaiduTranslator.md5Hex("abc"))
    }

    @Test
    fun `md5Hex 对中文、特殊字符与多行按 UTF-8 无符号十六进制`() {
        for (input in listOf("中文", "你好，世界！", "a&b=c?d#e 空格", "第一行\n第二行", "emoji 🙂")) {
            assertEquals(input, md5Reference(input), BaiduTranslator.md5Hex(input))
        }
    }

    @Test
    fun `sign = MD5(appid + q + salt + secret)，q 用未编码原文`() {
        val text = "你好\n世界 & more"
        val request = translator.buildRequest(text, TranslationLanguage.ENGLISH)
        assertEquals(md5Reference(APP_ID + text + SALT + SECRET), request.url.queryParameter("sign"))
    }

    @Test
    fun `percentEncode 只放过 unreserved，其余按 UTF-8 字节编码`() {
        assertEquals("abcXYZ019-_.~", BaiduTranslator.percentEncode("abcXYZ019-_.~"))
        assertEquals("C%2B%2B", BaiduTranslator.percentEncode("C++"))
        assertEquals("50%25", BaiduTranslator.percentEncode("50%"))
        assertEquals("%E4%B8%AD", BaiduTranslator.percentEncode("中"))
        assertEquals("%20", BaiduTranslator.percentEncode(" "))
        assertEquals("%F0%9F%99%82", BaiduTranslator.percentEncode("🙂"))
        assertEquals("", BaiduTranslator.percentEncode(""))
    }

    /**
     * 编码回环：服务端解出的 q 必须**逐字等于原文**。
     *
     * `+` 是这条链路的真雷（2026-09-30 审查发现）：OkHttp 的 `addQueryParameter` 不编码它，
     * 而网关按 form-urlencoded 把 `+` 解回空格 ⇒ 服务端重建的 q 与签名用的不一致（54001，
     * 会被归成 AUTH，用户怎么检查凭据都没问题）。
     */
    @Test
    fun `q 的传输编码解码后必须逐字等于原文`() {
        for (text in listOf("C++", "1+1=2", "50% off", "%41", "a b", "中文 空格", "emoji 🙂", "a&b=c?d#e")) {
            val url = translator.buildRequest(text, TranslationLanguage.ENGLISH).url
            assertEquals("原文：$text", text, url.queryParameter("q"))
        }
    }

    @Test
    fun `加号编成百分号2B（原样保留或按空格处理都会让服务端的 q 与签名不一致）`() {
        val query = translator.buildRequest("C++", TranslationLanguage.ENGLISH).url.encodedQuery!!
        assertTrue("q 里应出现 %2B，实际：$query", query.contains("q=C%2B%2B"))
        assertFalse("q 里不应出现未编码的 +，实际：$query", query.contains("q=C++"))
    }

    // ── 请求形态 ─────────────────────────────────────────────

    @Test
    fun `请求形态：GET + HTTPS + 全部查询参数`() {
        val request = translator.buildRequest("你好", TranslationLanguage.JAPANESE)

        assertEquals("GET", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("fanyi-api.baidu.com", request.url.host)
        assertEquals("/api/trans/vip/translate", request.url.encodedPath)
        assertEquals("你好", request.url.queryParameter("q"))
        assertEquals("auto", request.url.queryParameter("from"))
        assertEquals("jp", request.url.queryParameter("to"))
        assertEquals(APP_ID, request.url.queryParameter("appid"))
        assertEquals(SALT, request.url.queryParameter("salt"))
    }

    @Test
    fun `目标语言代码只从应用层映射取`() {
        fun to(language: TranslationLanguage): String =
            translator.buildRequest("x", language).url.queryParameter("to")!!

        assertEquals("zh", to(TranslationLanguage.CHINESE))
        assertEquals("en", to(TranslationLanguage.ENGLISH))
        assertEquals("jp", to(TranslationLanguage.JAPANESE))
        assertEquals("kor", to(TranslationLanguage.KOREAN))
    }

    @Test
    fun `默认 salt 每次取新值（防重放，两次请求不共用同一签名）`() {
        val random = BaiduTranslator(APP_ID, SECRET)
        val first = random.buildRequest("x", TranslationLanguage.ENGLISH).url.queryParameter("salt")
        val second = random.buildRequest("x", TranslationLanguage.ENGLISH).url.queryParameter("salt")
        assertNotEquals(first, second)
    }

    // ── 响应 ────────────────────────────────────────────────

    @Test
    fun `200 正常解析 dst`() {
        val body = """{"from":"zh","to":"en","trans_result":[{"src":"你好","dst":"Hello"}]}"""
        assertEquals(TranslationOutcome.Ok("Hello"), translator.parseResponse(200, body))
    }

    @Test
    fun `200 + error_code 走错误分类（不能当成空结果）`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            translator.parseResponse(200, """{"error_code":"54001","error_msg":"Invalid Sign"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            translator.parseResponse(200, """{"error_code":"52003","error_msg":"UNAUTHORIZED USER"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            translator.parseResponse(200, """{"error_code":"54004"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            translator.parseResponse(200, """{"error_code":"54003"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.PARAM),
            translator.parseResponse(200, """{"error_code":"58001"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.TIMEOUT),
            translator.parseResponse(200, """{"error_code":"52001"}"""),
        )
    }

    @Test
    fun `非 2xx 也先读业务错误码（网关用 403-5xx 承载 54003 时不能归成认证失败）`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            translator.parseResponse(403, """{"error_code":"54003","error_msg":"frequency limit"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            translator.parseResponse(503, """{"error_code":"54004"}"""),
        )
        // 体里没有业务码时仍按 HTTP 状态归类
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            translator.parseResponse(403, """{"error_msg":"forbidden"}"""),
        )
    }

    @Test
    fun `dst 为 JSON null 时是空结果，而不是把字面量 null 当译文`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"trans_result":[{"src":"你好","dst":null}]}"""),
        )
    }

    @Test
    fun `未知错误码与非法 JSON 一律归服务端异常（不透传 error_msg）`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, """{"error_code":"99999","error_msg":"weird"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, "not json"),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, null),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(502, "<html>bad gateway</html>"),
        )
    }

    @Test
    fun `空译文与空结果列表归为空结果`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"trans_result":[]}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"trans_result":[{"src":"你好","dst":""}]}"""),
        )
    }

    private companion object {
        const val APP_ID = "20250101000000001"
        const val SECRET = "abcdefghijklmnopqrstuvwxyz123456"
        const val SALT = "1234567890"
    }
}
