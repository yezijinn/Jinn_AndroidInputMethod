package com.jinn.inputmethod

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeepL（Text Translation API v2），直接用 OkHttp 发 HTTPS 请求，不引 SDK。
 *
 * ```
 * POST https://api.deepl.com/v2/translate          （Pro）
 * POST https://api-free.deepl.com/v2/translate     （Free）
 * Authorization: DeepL-Auth-Key <key>
 * {"text":["…"],"target_lang":"EN"}
 * ```
 * 不传 `source_lang` = DeepL 自动检测源语言（与其它三家口径一致）。
 *
 * **域名按 Key 自动选择**：DeepL 的 Free 订阅密钥固定以 `:fx` 结尾，Pro 密钥不带后缀
 * （官方约定）——用户只填一个 Key，不必再选「我买的是哪档」。
 */
internal class DeepLTranslator(
    rawApiKey: String,
    /** 是否走 Free 域；默认按 DeepL 官方约定判定 `:fx` 后缀（用 trim 后的 Key 判定） */
    private val freeApi: Boolean = rawApiKey.trim().endsWith(FREE_KEY_SUFFIX),
) : TranslationProvider {

    /**
     * Key 在构造期 trim 一次，判域与发请求用**同一个值**。
     *
     * 判 Free/Pro 用 trim 后的 Key、发请求却用未 trim 的原值，会让带空格的 Key 出现
     * 「判成 Free 域、发出的却是不合法 Key」（403→AUTH，用户看不出错在哪一步）。
     * 生产入口 `providerOf` 已 trim，这里是类自身的自洽性（2026-09-30 审查发现）。
     */
    private val apiKey: String = rawApiKey.trim()

    override fun buildRequest(text: String, target: TranslationLanguage): Request {
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("target_lang", target.deeplCode)
            .toString()
        return Request.Builder()
            .url(endpoint())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "DeepL-Auth-Key $apiKey")
            .build()
    }

    override fun parseResponse(code: Int, body: String?): TranslationOutcome {
        if (code !in 200..299) return TranslationOutcome.Fail(httpErrorOf(code))
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return TranslationOutcome.Fail(TranslationError.SERVER)
        // 译文按请求顺序返回，单条请求只取第一条；取值走 jsonText（JSON null 不会被当成 "null"）
        val text = jsonText(json.optJSONArray("translations")?.optJSONObject(0), "text")
        return if (text.isNullOrBlank()) {
            TranslationOutcome.Fail(TranslationError.EMPTY)
        } else {
            TranslationOutcome.Ok(text)
        }
    }

    /** 域名选择（纯函数，可单测）：Free 走 api-free.deepl.com，Pro 走 api.deepl.com */
    internal fun endpoint(): HttpUrl = if (freeApi) FREE_ENDPOINT_URL else PRO_ENDPOINT_URL

    companion object {
        const val PRO_ENDPOINT = "https://api.deepl.com/v2/translate"
        const val FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate"

        /** Free 订阅密钥的后缀（官方约定） */
        const val FREE_KEY_SUFFIX = ":fx"

        private val PRO_ENDPOINT_URL: HttpUrl = PRO_ENDPOINT.toHttpUrlOrNull()!!
        private val FREE_ENDPOINT_URL: HttpUrl = FREE_ENDPOINT.toHttpUrlOrNull()!!

        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

        /**
         * HTTP 状态码 → 归一分类（DeepL 官方错误码表）。
         *
         * `403` 授权失败；`429` 请求过于频繁；**`456` = 本月额度用尽**（DeepL 特有）；
         * `413` / `414` 是请求体或 URI 超长，归参数问题（用户改短原文即可）。
         */
        internal fun httpErrorOf(code: Int): TranslationError = when (code) {
            400, 413, 414 -> TranslationError.PARAM
            // 401 / 408 官方错误表里没有（DeepL 用 403 表达密钥无效），但前置 CDN / 代理会回它们。
            // 其余五家都认这两个码，只有这家落 else ⇒ 同一家的凭据错在 403 说「认证失败」、
            // 在 401 却说「服务异常」，用户无从判断该查凭据还是该重试（2026-10-01 修复 L-309）。
            401, 403 -> TranslationError.AUTH
            408 -> TranslationError.TIMEOUT
            429, 456 -> TranslationError.QUOTA
            in 500..599 -> TranslationError.SERVER
            else -> TranslationError.SERVER
        }
    }
}
