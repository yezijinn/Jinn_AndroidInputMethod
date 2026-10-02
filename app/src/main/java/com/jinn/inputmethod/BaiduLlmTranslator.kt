package com.jinn.inputmethod

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 百度**大模型**文本翻译 API（与「通用文本翻译」是两个服务，凭据与鉴权都不同）。
 *
 * ```
 * POST https://fanyi-api.baidu.com/ait/api/aiTextTranslate
 * Authorization: Bearer <API Key>
 * {"appid":"…","from":"auto","to":"en","q":"你好"}
 * ```
 * 官方推荐用 `Bearer` **API Key** 鉴权（该接口也兼容通用平台那套 `appid+q+salt+密钥` 的 sign，
 * 本实现只走 API Key —— 用户填一个 Key，签名不落本机）。`from=auto` 自动检测源语言，`to` 不可为 auto。
 *
 * 响应与通用平台同形：`{"from":"en","to":"zh","trans_result":[{"src":"apple","dst":"苹果"}]}`；
 * 失败 `{"error_code":"54001","error_msg":"Invalid Sign"}`（HTTP 200 体内）。
 * 语言代码沿用同一套（zh / en / jp / kor），故复用 [TranslationLanguage.baiduCode]。
 */
internal class BaiduLlmTranslator(
    private val appId: String,
    private val apiKey: String,
) : TranslationProvider {

    /**
     * 大模型的整体预算 60s（与 OpenAI 兼容同口径）：首字延迟不可控、服务端会排队，
     * 用默认的 20s 读超时 / 25s 整体预算会把长句打成假「翻译请求超时」
     * （2026-09-30 审查发现：同一批里只有 OpenAI 拿到了 60s）。
     */
    override val callTimeoutSec: Int = LLM_CALL_TIMEOUT_SEC

    override fun buildRequest(text: String, target: TranslationLanguage): Request {
        val body = JSONObject()
            .put("appid", appId)
            .put("from", SOURCE_AUTO)
            .put("to", target.baiduCode)
            .put("q", text)
            .toString()
        return Request.Builder()
            .url(ENDPOINT_URL)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .build()
    }

    override fun parseResponse(code: Int, body: String?): TranslationOutcome {
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        // 与通用平台同款：业务错误码在响应体里（HTTP 200 是常规通道），且**非 2xx 也先取** ——
        // 网关用 403/5xx 承载 54003/54004 时若先看状态，会被归成 AUTH/SERVER（2026-09-30 审查发现）
        // 走 jsonCode 而不是 jsonText：错误码可能是 JSON 数字（2026-10-01 审查 L-226）
        val errorCode = jsonCode(json, "error_code")
        // `"0"` 是百度的**成功**码（2026-10-01 复审 L-233）：不豁免时 `"error_code":0` 会把一次
        // 正常翻译判成服务端错误
        if (!errorCode.isNullOrEmpty() && errorCode != "0") {
            return TranslationOutcome.Fail(BaiduTranslator.baiduErrorOf(errorCode))
        }
        if (json == null || code !in 200..299) {
            return TranslationOutcome.Fail(BaiduTranslator.httpErrorOf(code))
        }
        // 与通用平台同款：数组按行对齐，多行原文要合并（2026-10-01 审查 L-268）
        val text = BaiduTranslator.transResultText(json)
        return if (text.isNullOrBlank()) {
            TranslationOutcome.Fail(TranslationError.EMPTY)
        } else {
            TranslationOutcome.Ok(text)
        }
    }

    companion object {
        const val ENDPOINT = "https://fanyi-api.baidu.com/ait/api/aiTextTranslate"

        /** 大模型接口的整体超时（秒）：与 OpenAI 兼容同口径 */
        private const val LLM_CALL_TIMEOUT_SEC = 60

        /** 自动检测源语言（官方：`from=auto`） */
        const val SOURCE_AUTO = "auto"

        /** 常量 URL 必然可解析；解析失败属于字面量写错，构建期就该发现 */
        private val ENDPOINT_URL: HttpUrl = ENDPOINT.toHttpUrlOrNull()!!

        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    }
}
