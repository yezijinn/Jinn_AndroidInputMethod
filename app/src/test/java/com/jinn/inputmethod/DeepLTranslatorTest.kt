package com.jinn.inputmethod

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DeepL 的纯函数守卫：域名自动选择、认证头、请求体与错误码分类。
 *
 * 最要紧的是**域名**：Free 密钥打到 Pro 域（或反过来）会直接 403，
 * 而用户只填一个 Key，选域只能靠 `:fx` 后缀这条官方约定。
 */
class DeepLTranslatorTest {

    private val proTranslator = DeepLTranslator(PRO_KEY)
    private val freeTranslator = DeepLTranslator(FREE_KEY)

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `Free 密钥以 fx 结尾时自动走 api-free 域`() {
        assertEquals("api-free.deepl.com", freeTranslator.endpoint().host)
        assertEquals("api.deepl.com", proTranslator.endpoint().host)
    }

    @Test
    fun `显式指定档位可覆盖后缀判定`() {
        assertEquals("api.deepl.com", DeepLTranslator(FREE_KEY, freeApi = false).endpoint().host)
        assertEquals("api-free.deepl.com", DeepLTranslator(PRO_KEY, freeApi = true).endpoint().host)
    }

    @Test
    fun `请求形态：POST + HTTPS + 认证头 + JSON 体（不传源语言）`() {
        val request = proTranslator.buildRequest("你好", TranslationLanguage.CHINESE)
        val json = JSONObject(bodyOf(request))

        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("/v2/translate", request.url.encodedPath)
        assertEquals("DeepL-Auth-Key $PRO_KEY", request.header("Authorization"))
        assertEquals("你好", json.getJSONArray("text").getString(0))
        assertEquals("ZH-HANS", json.getString("target_lang"))
        // 不传 source_lang = 交给 DeepL 自动检测
        assertEquals(false, json.has("source_lang"))
    }

    @Test
    fun `目标语言用 DeepL 的大写代码`() {
        fun to(language: TranslationLanguage): String =
            JSONObject(bodyOf(proTranslator.buildRequest("x", language))).getString("target_lang")

        // 中文用正名 ZH-HANS 而不是别名 ZH（2026-10-02 修复 L-487）：官方已标 ZH 为 deprecated
        assertEquals("ZH-HANS", to(TranslationLanguage.CHINESE))
        assertEquals("EN", to(TranslationLanguage.ENGLISH))
        assertEquals("JA", to(TranslationLanguage.JAPANESE))
        assertEquals("KO", to(TranslationLanguage.KOREAN))
    }

    @Test
    fun `200 正常解析译文`() {
        val body = """{"translations":[{"detected_source_language":"ZH","text":"Hello, World!"}]}"""
        assertEquals(
            TranslationOutcome.Ok("Hello, World!"),
            proTranslator.parseResponse(200, body),
        )
    }

    @Test
    fun `空译文、缺字段与非法 JSON`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            proTranslator.parseResponse(200, """{"translations":[]}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            proTranslator.parseResponse(200, """{"translations":[{"text":""}]}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            proTranslator.parseResponse(200, "not json"),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            proTranslator.parseResponse(200, null),
        )
    }

    @Test
    fun `HTTP 错误码：403 认证、429-456 额度、400-413-414 参数、5xx 服务端`() {
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), proTranslator.parseResponse(403, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.QUOTA), proTranslator.parseResponse(429, null))
        // 456 = 本月额度用尽（DeepL 特有），必须归额度而不是服务端异常
        assertEquals(TranslationOutcome.Fail(TranslationError.QUOTA), proTranslator.parseResponse(456, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), proTranslator.parseResponse(400, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), proTranslator.parseResponse(413, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), proTranslator.parseResponse(414, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), proTranslator.parseResponse(500, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), proTranslator.parseResponse(529, null))
    }

    @Test
    fun `Key 带首尾空格时判域与发送同源（都用 trim 后的值）`() {
        // 判域用 trim、发送用原值，会出现「判成 Free 域、发出的却是不合法 Key」
        // （403→AUTH，用户看不出错在哪一步 —— 2026-09-30 审查发现）
        val padded = DeepLTranslator("  $FREE_KEY  ")
        assertEquals("api-free.deepl.com", padded.endpoint().host)
        assertEquals(
            "DeepL-Auth-Key $FREE_KEY",
            padded.buildRequest("x", TranslationLanguage.ENGLISH).header("Authorization"),
        )
    }

    @Test
    fun `译文为 JSON null 时是空结果，而不是把字面量 null 当译文`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            proTranslator.parseResponse(200, """{"translations":[{"text":null}]}"""),
        )
    }

    private companion object {
        const val PRO_KEY = "0a1b2c3d-4e5f-6789-abcd-ef0123456789"
        const val FREE_KEY = "0a1b2c3d-4e5f-6789-abcd-ef0123456789:fx"
    }
}
