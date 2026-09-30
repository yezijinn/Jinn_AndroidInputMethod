package com.jinn.inputmethod

import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Azure Translator 的纯函数守卫：请求形态（URL / 认证头 / JSON 体）与响应分类。
 *
 * `buildRequest` / `parseResponse` 都不碰网络与 Android API，所以这里直接断言真实请求对象 ——
 * 认证头拼错、`from` 误加、错误码归类错这类问题在真机上只会表现为「翻译失败」，
 * 看不出原因，必须在 JVM 层钉死。
 */
class AzureTranslatorTest {

    private val translator = AzureTranslator(API_KEY, REGION)

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `请求形态：POST + HTTPS + 查询参数 + 认证头 + JSON 体`() {
        val request = translator.buildRequest("你好", TranslationLanguage.ENGLISH)

        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("api.cognitive.microsofttranslator.com", request.url.host)
        assertEquals("/translate", request.url.encodedPath)
        assertEquals("3.0", request.url.queryParameter("api-version"))
        assertEquals("en", request.url.queryParameter("to"))
        assertEquals(API_KEY, request.header("Ocp-Apim-Subscription-Key"))
        assertEquals(REGION, request.header("Ocp-Apim-Subscription-Region"))
        // 不传 from：源语言交给 Azure 自动检测（用户不需要也不应该填）
        assertNull(request.url.queryParameter("from"))

        val json = JSONArray(bodyOf(request))
        assertEquals("你好", json.getJSONObject(0).getString("Text"))
    }

    @Test
    fun `不填 Region 时不带区域头（单区域全局资源会拒绝多余头）`() {
        val request = AzureTranslator(API_KEY, "").buildRequest("hi", TranslationLanguage.CHINESE)
        assertNull(request.header("Ocp-Apim-Subscription-Region"))
        assertEquals("zh-Hans", request.url.queryParameter("to"))
    }

    @Test
    fun `目标语言代码只从应用层映射取（UI 里不出现 Provider 语言码）`() {
        fun to(language: TranslationLanguage): String =
            translator.buildRequest("x", language).url.queryParameter("to")!!

        assertEquals("zh-Hans", to(TranslationLanguage.CHINESE))
        assertEquals("en", to(TranslationLanguage.ENGLISH))
        assertEquals("ja", to(TranslationLanguage.JAPANESE))
        assertEquals("ko", to(TranslationLanguage.KOREAN))
    }

    @Test
    fun `200 正常解析译文`() {
        val body = """[{"detectedLanguage":{"language":"zh","score":1.0},""" +
            """"translations":[{"text":"Hello","to":"en"}]}]"""
        assertEquals(TranslationOutcome.Ok("Hello"), translator.parseResponse(200, body))
    }

    @Test
    fun `200 空数组或空译文归为空结果`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, "[]"),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """[{"translations":[{"text":"","to":"en"}]}]"""),
        )
        // text 是显式 JSON null：不能变成字面量 "null" 提交（取值走 jsonText 的原因）
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """[{"translations":[{"text":null,"to":"en"}]}]"""),
        )
        // 读体失败（200 但拿不到 body）不是「结果为空」：服务端没给出可解析内容，归服务端异常
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, null),
        )
    }

    @Test
    fun `非法 JSON 归服务端异常（不抛异常、不当作空结果）`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, "not json"),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, """{"error":{"code":"400036"}}"""),
        )
    }

    @Test
    fun `HTTP 错误码分类：401 认证、403-429 额度、400 参数、5xx 服务端`() {
        // 403 是 Azure 免费层额度耗尽的通道（不是 401），归类必须与 429 一致
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(401, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.QUOTA), translator.parseResponse(403, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.QUOTA), translator.parseResponse(429, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), translator.parseResponse(400, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.TIMEOUT), translator.parseResponse(408, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), translator.parseResponse(500, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), translator.parseResponse(503, null))
    }

    private companion object {
        const val API_KEY = "0123456789abcdef0123456789abcdef"
        const val REGION = "eastasia"
    }
}
