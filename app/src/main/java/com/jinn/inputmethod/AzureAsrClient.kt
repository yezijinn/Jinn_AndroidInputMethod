package com.jinn.inputmethod

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Azure AI Speech 短音频识别（REST，`{base}/stt/speech/recognition/conversation/cognitiveservices/v1`），
 * [SpeechClient] 的第六个实现。
 *
 * 传输模型与 [OpenAiAsrClient] 同类（本地缓冲整段 → 松手一次性上传），缓冲 / 线程 / 取消 / 超时
 * 全在基类 [BufferedBatchAsrClient]；本类只负责端点拼接、请求头与回包解析。
 *
 * `base` 由用户填（形如 `https://<资源名>.cognitiveservices.azure.com`），策略与另几家一致：
 * https 放行、http 仅回环 / 私网（[AzureAsrEndpoint.normalize] 直接复用 `OpenAiEndpoint` 那套，
 * 拒绝原因也要能原样显示给用户）。
 *
 * ⚠ 订阅密钥只从 [Prefs] 读，且**只进 `Ocp-Apim-Subscription-Key` 头**：日志里只有长度。
 */
class AzureAsrClient(
    private val prefs: Prefs,
    onState: (LinkState, String?) -> Unit,
    onResult: (RecognitionMessage) -> Unit,
) : BufferedBatchAsrClient(onState, onResult) {

    override val tag: String get() = TAG

    /** [prepare] 校验通过后缓存的取值（上传线程用快照，避免中途改配置） */
    @Volatile
    private var base: String = ""

    @Volatile
    private var language: String = ""

    @Volatile
    private var apiKey: String = ""

    override fun prepare(): String? {
        val normalized = AzureAsrEndpoint.normalize(prefs.azureBaseUrl)
        val bad = normalized.exceptionOrNull()?.message
        if (bad != null) {
            Diagnostics.w(TAG, "connect: 地址不合法 reason=$bad")
            return bad
        }
        val key = prefs.azureSpeechKey
        if (key.isEmpty()) {
            Diagnostics.w(TAG, "connect: 订阅密钥未配置")
            return TEXT_NEED_KEY
        }
        base = normalized.getOrThrow()
        language = prefs.azureLanguage
        apiKey = key
        Diagnostics.i(TAG, "connect: 就绪 endpoint=$base language=$language keyLen=${key.length}")
        return null
    }

    override fun recognize(wav: ByteArray, pcm16: ByteArray): BatchOutcome {
        val url = AzureAsrEndpoint.url(base, language)
        val reply = exchange(AzureAsrRequest.request(url, apiKey, wav))
        return AzureResponse.parse(reply.code, reply.body)
    }

    private companion object {
        const val TAG = "AzureAsrClient"

        const val TEXT_NEED_KEY = "未填写订阅密钥"
    }
}

/**
 * 端点策略与拼接（纯函数，JVM 单测直接跑）。
 *
 * `base` 归一（去尾斜杠、scheme 小写、http 仅回环 / 私网）**直接复用** [OpenAiEndpoint]：
 * 两家的链路性质一样（整段录音 + 头里的密钥走明文协议），策略与提示文案都不该有两套。
 */
internal object AzureAsrEndpoint {

    const val PATH = "/stt/speech/recognition/conversation/cognitiveservices/v1"

    const val LANGUAGE_PARAM = "language"

    /** 语言码默认值：与 `Prefs.azureLanguage` 同源（这里只给纯函数一个可复算的默认） */
    const val DEFAULT_LANGUAGE = "zh-CN"

    fun normalize(base: String): Result<String> = OpenAiEndpoint.normalize(base)

    /**
     * `{base}{PATH}?language=<lang>`：`base` 已在 [normalize] 里去掉尾部 `/`，
     * 语言码必须**转义**（`zh-CN` 原样；带 `&` / 空格的值不转义就是拼出一个非法地址）。
     */
    fun url(normalizedBase: String, language: String): String {
        val lang = language.trim().ifEmpty { DEFAULT_LANGUAGE }
        return normalizedBase.trimEnd('/') + PATH + "?" + LANGUAGE_PARAM + "=" + queryEscape(lang)
    }

    fun queryEscape(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * 请求组装（纯函数）。
 *
 * `Content-Type` 里带 `codecs=audio/pcm`：Azure 认，但 OkHttp 的 `MediaType` 解析器不认
 * （`/` 不是 token 字符），故请求体自己报 `contentType() = null`，头由 [AzureAsrRequest] 原样下发
 * —— 不这么做会在建请求时抛 `IllegalArgumentException`（只在真机 / 真请求上冒出来）。
 */
internal object AzureAsrRequest {

    const val HEADER_KEY = "Ocp-Apim-Subscription-Key"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_ACCEPT = "Accept"

    const val CONTENT_TYPE_WAV = "audio/wav; codecs=audio/pcm; samplerate=16000"
    const val ACCEPT_JSON = "application/json"

    fun request(url: String, apiKey: String, wav: ByteArray): Request =
        Request.Builder()
            .url(url)
            .header(HEADER_KEY, apiKey)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_WAV)
            .header(HEADER_ACCEPT, ACCEPT_JSON)
            .post(RawWavBody(wav))
            .build()
}

/** 裸音频请求体：`Content-Type` 由 [AzureAsrRequest] 用 header 下发（见那里的说明） */
internal class RawWavBody(private val bytes: ByteArray) : RequestBody() {

    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

/**
 * 回包解析（纯函数）：`RecognitionStatus == "Success"` 取 `DisplayText`，其余给**可读**原因。
 *
 * 为什么非 Success 也必须说清：`InitialSilenceTimeout`（麦克风没声音）与 `NoMatch`（说了但没听懂）
 * 是两种完全不同的处置，只说「识别失败」会让用户去改一个没坏的东西。
 */
internal object AzureResponse {

    const val STATUS_FIELD = "RecognitionStatus"
    const val TEXT_FIELD = "DisplayText"
    const val STATUS_SUCCESS = "Success"

    const val TEXT_NO_STATUS = "响应里没有 RecognitionStatus"
    const val TEXT_FAILED = "识别失败"

    fun parse(code: Int, body: String?): BatchOutcome {
        val raw = body.orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BatchOutcome.Fail(errorText(code, raw))
        val status = runCatching { jsonText(json, STATUS_FIELD) }.getOrNull()
        if (status == null) {
            // 鉴权失败 / 地址错是「没有 RecognitionStatus 的错误报文」，状态码才是唯一线索
            return BatchOutcome.Fail(if (code in 200..299) TEXT_NO_STATUS else errorText(code, raw))
        }
        if (status != STATUS_SUCCESS) return BatchOutcome.Fail(readable(status))
        val text = runCatching { jsonText(json, TEXT_FIELD) }.getOrNull().orEmpty()
        return BatchOutcome.Ok(text)
    }

    /** 状态名 → 中文可读原因（原文状态名保留在括号里：排障与官方文档对得上） */
    fun readable(status: String): String = when (status) {
        "NoMatch" -> "没有识别到内容（NoMatch）"
        "InitialSilenceTimeout" -> "开始时没听到声音（InitialSilenceTimeout）"
        "BabbleTimeout" -> "只有噪音、没有语音（BabbleTimeout）"
        "Error" -> "服务端识别出错（Error）"
        else -> "识别未成功（$status）"
    }

    /** 拿不到状态字段时的兜底：状态码 + 报文体前 60 字（仍截断） */
    fun errorText(code: Int, raw: String): String {
        val detail = raw.trim().take(60).takeIf { it.isNotEmpty() }
        return if (detail == null) "HTTP $code" else "HTTP $code $detail"
    }
}
