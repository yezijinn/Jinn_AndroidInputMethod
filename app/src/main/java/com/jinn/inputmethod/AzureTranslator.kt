package com.jinn.inputmethod

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Microsoft Azure Translator（Translator v3 REST），直接用 OkHttp 发 HTTPS 请求，不引 Azure SDK。
 *
 * 请求形态（对齐官方文档）：
 * ```
 * POST https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=<代码>
 * Ocp-Apim-Subscription-Key: <Key>
 * Ocp-Apim-Subscription-Region: <Region>        ← 多服务/区域资源要求，单区域资源可不填
 * Content-Type: application/json; charset=UTF-8
 * [{"Text":"待译原文"}]
 * ```
 * **不传 `from`**：让 Azure 自动检测源语言，用户不需要（也不应该）填源语言。
 *
 * 响应 `[{"detectedLanguage":{…},"translations":[{"text":"译文","to":"en"}]}]`；
 * 失败时给 HTTP 状态码（错误详情在 body 的 error 对象里，本项目只取状态码分类，
 * 原始 JSON 不透传给用户，也不进日志）。
 */
internal class AzureTranslator(
    private val apiKey: String,
    private val region: String,
) : TranslationProvider {

    override fun buildRequest(text: String, target: TranslationLanguage): Request {
        val url = ENDPOINT_URL.newBuilder()
            .addQueryParameter("api-version", API_VERSION)
            .addQueryParameter("to", target.azureCode)
            .build()
        val body = JSONArray().put(JSONObject().put("Text", text)).toString()
        return Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .apply {
                // Region 为空表示资源不需要（单区域全局资源）：此时带空 Header 反而会被拒
                if (region.isNotBlank()) header("Ocp-Apim-Subscription-Region", region)
            }
            .build()
    }

    override fun parseResponse(code: Int, body: String?): TranslationOutcome {
        if (code !in 200..299) return TranslationOutcome.Fail(errorOf(code))
        // 解析全程 runCatching：org.json 的异常绝不能冒泡到 IME 主线程。
        // 「不是 JSON 数组」= 服务端返回不可识别 → SERVER；「合法但没译文」→ EMPTY，
        // 两者对用户的含义不同（重试 vs 换内容），不合并。
        val json = runCatching { JSONArray(body.orEmpty()) }.getOrNull()
            ?: return TranslationOutcome.Fail(TranslationError.SERVER)
        // 取值走 jsonText：JSON null / 类型不符都返回 null（optString 会给出字面量 "null" 当译文）
        val text = jsonText(
            json.optJSONObject(0)?.optJSONArray("translations")?.optJSONObject(0),
            "text",
        )
        return if (text.isNullOrBlank()) {
            TranslationOutcome.Fail(TranslationError.EMPTY)
        } else {
            TranslationOutcome.Ok(text)
        }
    }

    companion object {
        const val ENDPOINT = "https://api.cognitive.microsofttranslator.com/translate"
        const val API_VERSION = "3.0"

        /** 常量 URL 必然可解析；解析失败属于字面量写错，构建期就该发现 */
        private val ENDPOINT_URL: HttpUrl = ENDPOINT.toHttpUrlOrNull()!!

        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

        /**
         * HTTP 状态码 → 归一错误分类。
         *
         * 401 = 密钥无效；403 = Azure 的**额度用尽**（免费层月度额度耗尽走 403，
         * 不是 401），所以 403 归 QUOTA；429 = 请求过于频繁，同样归 QUOTA。
         *
         * 404 = 区域 / 终结点填错（区域写成多服务资源名或别的档位时 Azure 返回 404）：
         * 认证文案本就点着「密钥与 Azure 区域」，归 AUTH 才指向真正要改的字段 ——
         * 落进 else 只给「服务异常」，用户按提示重试会反复失败（BUG.md L-1126；
         * 同仓阿里云的 HTTP 表也是 404 → AUTH）。
         */
        internal fun errorOf(code: Int): TranslationError = when (code) {
            400, 413 -> TranslationError.PARAM      // 413 = 请求体过大（2026-10-01 审查 L-273）
            401, 404 -> TranslationError.AUTH
            403, 429 -> TranslationError.QUOTA
            408 -> TranslationError.TIMEOUT
            in 500..599 -> TranslationError.SERVER
            else -> TranslationError.SERVER
        }
    }
}
