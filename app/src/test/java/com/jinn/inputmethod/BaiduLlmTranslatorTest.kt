package com.jinn.inputmethod

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百度**大模型**文本翻译的纯函数守卫：Bearer 鉴权、请求体与响应分类。
 *
 * 这一家与「通用文本翻译」共用域名与响应形态，但**鉴权与请求体都不同**
 * （Bearer API Key + POST JSON，而不是 query 里的 MD5 sign）—— 两者最容易被实现者混在一起。
 */
class BaiduLlmTranslatorTest {

    private val translator = BaiduLlmTranslator(APP_ID, API_KEY)

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `请求形态：POST + HTTPS + Bearer 鉴权 + JSON 体`() {
        val request = translator.buildRequest("你好", TranslationLanguage.ENGLISH)
        val json = JSONObject(bodyOf(request))

        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("fanyi-api.baidu.com", request.url.host)
        assertEquals("/ait/api/aiTextTranslate", request.url.encodedPath)
        assertEquals("Bearer $API_KEY", request.header("Authorization"))
        assertEquals(APP_ID, json.getString("appid"))
        assertEquals("auto", json.getString("from"))
        assertEquals("en", json.getString("to"))
        assertEquals("你好", json.getString("q"))
        // 该接口的 sign 鉴权是可选兼容项：走 API Key 时不带 salt / sign
        assertEquals(false, json.has("salt"))
        assertEquals(false, json.has("sign"))
    }

    @Test
    fun `目标语言沿用百度语言代码`() {
        fun to(language: TranslationLanguage): String =
            JSONObject(bodyOf(translator.buildRequest("x", language))).getString("to")

        assertEquals("zh", to(TranslationLanguage.CHINESE))
        assertEquals("en", to(TranslationLanguage.ENGLISH))
        assertEquals("jp", to(TranslationLanguage.JAPANESE))
        assertEquals("kor", to(TranslationLanguage.KOREAN))
    }

    @Test
    fun `200 正常解析 dst（与通用平台同形）`() {
        val body = """{"from":"en","to":"zh","trans_result":[{"src":"apple","dst":"苹果"}]}"""
        assertEquals(TranslationOutcome.Ok("苹果"), translator.parseResponse(200, body))
    }

    @Test
    fun `error_code 为 0 视为成功（数字与字符串两种形态）`() {
        // 百度语义：0 = 成功。数字兼容之后 `"error_code":0` 会被取到 —— 不豁免就会把一次正常
        // 翻译判成服务端错误（2026-10-01 复审 L-233）
        val body = """{"error_code":0,"trans_result":[{"src":"apple","dst":"苹果"}]}"""
        assertEquals(TranslationOutcome.Ok("苹果"), translator.parseResponse(200, body))
        val asText = """{"error_code":"0","trans_result":[{"src":"apple","dst":"苹果"}]}"""
        assertEquals(TranslationOutcome.Ok("苹果"), translator.parseResponse(200, asText))
    }

    @Test
    fun `数字型 error_code 也归失败（不能落进「结果为空」）`() {
        // 官方示例把 error_code 写成字符串，数字形态同样存在；只认 String 的实现会跳过
        // 错误分支、在 trans_result 缺失时报「翻译结果为空」，把真因（配额用尽）藏起来
        // （2026-10-01 审查 L-226）
        val outcome = translator.parseResponse(200, """{"error_code":54003,"error_msg":"quota"}""")
        assertTrue("数字型 54003 必须归为失败，实际=$outcome", outcome is TranslationOutcome.Fail)
        val error = (outcome as TranslationOutcome.Fail).error
        assertEquals(
            "必须按服务端错误码归类，不能退化成「结果为空」",
            false,
            error === TranslationError.EMPTY,
        )
    }

    @Test
    fun `200 + error_code 走错误分类（不能当成空结果）`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            translator.parseResponse(200, """{"error_code":"54001","error_msg":"Invalid Sign"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            translator.parseResponse(200, """{"error_code":"52003"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            translator.parseResponse(200, """{"error_code":"54004"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.PARAM),
            translator.parseResponse(200, """{"error_code":"58001"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, """{"error_code":"99999"}"""),
        )
    }

    @Test
    fun `空译文与非法 JSON`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"trans_result":[]}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"trans_result":[{"src":"apple","dst":""}]}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, "not json"),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, null),
        )
    }

    @Test
    fun `HTTP 错误码分类与通用平台同口径`() {
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(401, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(403, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.QUOTA), translator.parseResponse(429, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), translator.parseResponse(400, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), translator.parseResponse(500, null))
    }

    @Test
    fun `大模型接口给足整体超时（与 OpenAI 兼容同口径）`() {
        // 首字延迟不可控、服务端会排队：用默认的 20s 读超时会把长句打成假「请求超时」
        // （2026-09-30 审查发现：同一批里只有 OpenAI 拿到了 60s）
        assertEquals(60, translator.callTimeoutSec)
    }

    @Test
    fun `非 2xx 也先读业务错误码（网关承载 54004 时不能归成服务端异常）`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            translator.parseResponse(503, """{"error_code":"54004"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            translator.parseResponse(403, """{"error_msg":"forbidden"}"""),
        )
    }

    @Test
    fun `dst 为 JSON null 时是空结果，而不是把字面量 null 当译文`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"trans_result":[{"src":"apple","dst":null}]}"""),
        )
    }

    private companion object {
        const val APP_ID = "20230804001769203"
        const val API_KEY = "nYp2_daudg30d0trke42kjvag"
    }
}
