package com.jinn.inputmethod

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Deepgram Pre-recorded（`POST /v1/listen`），[SpeechClient] 的第八个实现。
 *
 * 传输模型与另几家整段上传同类（本地缓冲 → 松手一次性上传），缓冲 / 线程 / 取消 / 超时全在基类
 * [BufferedBatchAsrClient]；本类只负责查询串、请求头与回包解析。
 *
 * 请求体的形态是**裸 WAV 字节**（不是 multipart，也不是 JSON 里塞 base64）：官方只按
 * `Content-Type: audio/wav` 认，套一层 multipart 会被当成坏音频（400 / 空结果）。
 * 鉴权是 `Authorization: Token <API Key>`（**不是** `Bearer`）。
 *
 * 服务端限制：单次音频 ≤ 2GB / 时长按账户档位，本应用按既有 58 秒收尾
 * （[Prefs.voiceBackend] 属在线后端，见 `JinnIme.autoStopDelayMs`），远在限内。
 *
 * ⚠ API Key 只从 [Prefs] 读，且**只进 `Authorization` 头**：日志里只有长度。
 */
class DeepgramAsrClient(
    private val prefs: Prefs,
    onState: (LinkState, String?) -> Unit,
    onResult: (RecognitionMessage) -> Unit,
) : BufferedBatchAsrClient(onState, onResult) {

    override val tag: String get() = TAG

    /** [prepare] 校验通过后缓存的取值（上传线程用快照，避免中途改配置） */
    @Volatile
    private var apiKey: String = ""

    @Volatile
    private var model: String = DeepgramRequest.DEFAULT_MODEL

    @Volatile
    private var language: String = DeepgramRequest.DEFAULT_LANGUAGE

    override fun prepare(): String? {
        val key = prefs.deepgramApiKey.trim()
        if (key.isEmpty()) {
            Diagnostics.w(TAG, "connect: API Key 未配置")
            return TEXT_NEED_KEY
        }
        apiKey = key
        model = prefs.deepgramModel
        language = prefs.deepgramLanguage
        Diagnostics.i(TAG, "connect: 就绪 model=$model language=$language keyLen=${key.length}")
        return null
    }

    override fun recognize(wav: ByteArray, pcm16: ByteArray): BatchOutcome {
        val reply = exchange(DeepgramRequest.request(model, language, apiKey, wav))
        return DeepgramResponse.parse(reply.code, reply.body)
    }

    private companion object {
        const val TAG = "DeepgramAsrClient"

        const val TEXT_NEED_KEY = "未填写 Deepgram API Key"
    }
}

/**
 * 请求层（纯函数）：查询串逐字对齐官方 SDK 的默认组合。
 *
 * 三个参数都过 [queryEscape]：模型名允许带版本后缀（`nova-3`）、语言码允许 `zh-CN` / `multi`，
 * 但只要用户在框里多打一个 `&` 或空格，不转义就是拼出一个非法地址（OkHttp 当场抛，
 * 而用户看到的只是「识别失败」）。
 *
 * `smart_format=true` 不是可选装饰：不开的话数字与标点按英文原样回来，中文输入法落字会出现
 * 「二逗号」这类怪结果。
 */
internal object DeepgramRequest {

    const val ENDPOINT = "https://api.deepgram.com/v1/listen"

    const val HEADER_AUTH = "Authorization"
    const val HEADER_CONTENT_TYPE = "Content-Type"

    /** 鉴权方案前缀是 `Token `（不是 `Bearer `，写错必然 401） */
    const val AUTH_PREFIX = "Token "

    const val CONTENT_TYPE_WAV = "audio/wav"

    const val PARAM_MODEL = "model"
    const val PARAM_LANGUAGE = "language"
    const val PARAM_SMART_FORMAT = "smart_format"
    const val SMART_FORMAT_ON = "true"

    /** 默认模型：官方当前主推的多语种档（`nova-3` 支持中文） */
    const val DEFAULT_MODEL = "nova-3"

    /** 默认语言码：`zh` 是 Deepgram 的中文通用码（`zh-CN` 也认，但 `zh` 覆盖面更宽） */
    const val DEFAULT_LANGUAGE = "zh"

    /** `?model=…&language=…&smart_format=true`（顺序固定，单测逐字对拍） */
    fun url(model: String, language: String): String =
        ENDPOINT +
            "?" + PARAM_MODEL + "=" + queryEscape(model.trim().ifEmpty { DEFAULT_MODEL }) +
            "&" + PARAM_LANGUAGE + "=" + queryEscape(language.trim().ifEmpty { DEFAULT_LANGUAGE }) +
            "&" + PARAM_SMART_FORMAT + "=" + SMART_FORMAT_ON

    /** 请求体是**裸 WAV 字节**：`Content-Type` 挂在请求体上（OkHttp 会写成同名头） */
    fun request(model: String, language: String, apiKey: String, wav: ByteArray): Request =
        Request.Builder()
            .url(url(model, language))
            .header(HEADER_AUTH, AUTH_PREFIX + apiKey)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_WAV)
            .post(wav.toRequestBody(CONTENT_TYPE_WAV.toMediaType()))
            .build()

    fun queryEscape(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * 回包解析（纯函数）：`results.channels[0].alternatives[0].transcript`。
 *
 * 错误有三种形态，**都不能只报「识别失败」**：
 *  · `{"err_code":"INVALID_AUTH","err_msg":"…"}`：鉴权类（Key 错 / 过期）；
 *  · `{"category":"…","message":"…"}`：参数与配额类（模型不存在、账户额度不足）；
 *  · 纯状态码（401 / 402 / 403 / 429，正文可能是空）—— 这是 Key 填错与「没钱了」的唯一线索。
 */
internal object DeepgramResponse {

    const val FIELD_RESULTS = "results"
    const val FIELD_CHANNELS = "channels"
    const val FIELD_ALTERNATIVES = "alternatives"
    const val FIELD_TRANSCRIPT = "transcript"
    const val FIELD_ERR_CODE = "err_code"
    const val FIELD_ERR_MSG = "err_msg"
    const val FIELD_CATEGORY = "category"
    const val FIELD_MESSAGE = "message"

    const val TEXT_NO_RESULT = "响应里没有 transcript"
    const val TEXT_FAILED = "识别失败"

    fun parse(code: Int, body: String?): BatchOutcome {
        val raw = body.orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BatchOutcome.Fail(errorText(code, raw, ""))
        val detail = errorDetail(json)
        // 错误体优先于状态码：同一句 err_msg（模型不存在 / 额度不足）比 400 有用得多
        if (detail.isNotEmpty()) return BatchOutcome.Fail(errorText(code, raw, detail))
        if (code !in 200..299) return BatchOutcome.Fail(errorText(code, raw, ""))
        val transcript = runCatching {
            json.getJSONObject(FIELD_RESULTS)
                .getJSONArray(FIELD_CHANNELS)
                .getJSONObject(0)
                .getJSONArray(FIELD_ALTERNATIVES)
                .getJSONObject(0)
                .getString(FIELD_TRANSCRIPT)
        }.getOrNull() ?: return BatchOutcome.Fail(TEXT_NO_RESULT)
        return BatchOutcome.Ok(transcript)
    }

    /** 两种错误体的可读摘要（`err_code: err_msg` 或 `category: message`）；正常回包返回空串 */
    fun errorDetail(json: JSONObject): String {
        val code = runCatching { jsonText(json, FIELD_ERR_CODE) }.getOrNull().orEmpty().trim()
        val message = runCatching { jsonText(json, FIELD_ERR_MSG) }.getOrNull().orEmpty().trim()
        if (code.isNotEmpty() || message.isNotEmpty()) {
            return listOf(code, message).filter { it.isNotEmpty() }.joinToString(": ")
        }
        val category = runCatching { jsonText(json, FIELD_CATEGORY) }.getOrNull().orEmpty().trim()
        val detail = runCatching { jsonText(json, FIELD_MESSAGE) }.getOrNull().orEmpty().trim()
        return listOf(category, detail).filter { it.isNotEmpty() }.joinToString(": ")
    }

    /**
     * `HTTP <码> <中文原因> · <服务端原文>`。
     *
     * 中文原因覆盖 401 / 402 / 403 / 429 这四个「用户自己就能改」的状态：只说 `HTTP 401` 的话
     * 用户不知道该改 Key 还是该改模型；服务端原文（`INVALID_AUTH`）保留在后面，便于与官方文档对照。
     * `err_code` 单独出现时（HTTP 200 + `INVALID_AUTH`）也按语义补一句 —— 否则用户只看到一串英文代码。
     */
    fun errorText(code: Int, raw: String, detail: String): String {
        val hint = readable(code) ?: readableByDetail(detail)
        val head = "HTTP $code" + (hint?.let { " $it" } ?: "")
        val tail = detail.ifEmpty { raw.trim().take(60) }
        return if (tail.isEmpty()) head else "$head · $tail"
    }

    /** 状态码 → 中文原因；未覆盖的返回 null（只报状态码，不编原因） */
    fun readable(code: Int): String? = when (code) {
        400 -> "请求不合法（模型 / 参数不对）"
        401 -> "API Key 无效"
        402 -> "账户额度不足"
        403 -> "无权访问（Key 与项目不匹配）"
        404 -> "模型或地址不存在"
        413 -> "音频过大"
        429 -> "请求过于频繁（超出配额）"
        in 500..599 -> "服务端出错"
        else -> null
    }

    /** 回包里的 `err_code` → 中文原因（状态码无从判断时用；认不出的返回 null） */
    fun readableByDetail(detail: String): String? = when {
        detail.contains("INVALID_AUTH") -> "API Key 无效"
        detail.contains("INSUFFICIENT_FUNDS") -> "账户额度不足"
        detail.contains("TOO_MANY_REQUESTS") -> "请求过于频繁（超出配额）"
        else -> null
    }
}
