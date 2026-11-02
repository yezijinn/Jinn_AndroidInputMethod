package com.jinn.inputmethod

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * OpenAI 兼容语音转写客户端（`POST {base}/audio/transcriptions`），[SpeechClient] 的第三个实现。
 *
 * 覆盖 Groq / 硅基流动 / Together / Cloudflare AI Gateway / OpenAI 官方等**同一套**接口的服务商
 * （Cloudflare **Workers AI** 的 `/ai/run/@cf/…` 是另一套路径，不在本实现覆盖范围内）：
 * 填 Base URL + API Key + 模型名即可用。
 *
 * **与实时流式的几个实现（[AsrClient] / [XfIatClient] / [AliAsrClient]）的根本差别是传输模型**：
 * 没有长连接，服务端也不收流式音频，所以先**本地缓冲**整段音频，[endTask] 时一次性组 WAV 上传，
 * 拿回最终文本就结束。缓冲 / 线程 / 取消 / 超时那套在基类 [BufferedBatchAsrClient] 里，
 * 本类只负责「怎么校验配置」与「怎么发一次请求、怎么解析回包」。
 *
 * ⚠ 凭据只从 [Prefs] 读，且**只进 Authorization 头**：日志与异常文本里只有长度与状态码，
 * 绝不出现原文（异常正文可能回显请求，故一律只取 `message` 的前 60 字）。
 */
class OpenAiAsrClient(
    private val prefs: Prefs,
    onState: (LinkState, String?) -> Unit,
    onResult: (RecognitionMessage) -> Unit,
) : BufferedBatchAsrClient(onState, onResult) {

    override val tag: String get() = TAG

    /**
     * 兜底失败文案沿用抽基类之前那句「转写失败」（转写 ≠ 听写：这是文件转写接口）。
     *
     * 基类默认是「识别失败」，别让这次重构顺手改掉用户已经看惯的措辞。
     */
    override val textFailed: String get() = TEXT_FAILED

    /** [prepare] 校验通过后缓存的三个取值（上传线程直接用快照，避免中途改配置） */
    @Volatile
    private var endpoint: String = ""

    @Volatile
    private var model: String = ""

    /** 只存内存、只进 Authorization 头；日志里永远只记长度 */
    @Volatile
    private var apiKey: String = ""

    override fun prepare(): String? {
        // 配置不齐当场落到状态条：拖到上传时才失败，用户已经白说了一段
        val normalized = OpenAiEndpoint.normalize(prefs.oaiBaseUrl)
        val bad = normalized.exceptionOrNull()?.message
        if (bad != null) {
            Diagnostics.w(TAG, "connect: 地址不合法 reason=$bad")
            return bad
        }
        val key = prefs.oaiApiKey
        if (key.isEmpty()) {
            Diagnostics.w(TAG, "connect: API Key 未配置")
            return TEXT_NEED_KEY
        }
        val name = prefs.oaiModel.trim()
        if (name.isEmpty()) {
            Diagnostics.w(TAG, "connect: 模型名为空")
            return TEXT_NEED_MODEL
        }
        endpoint = normalized.getOrThrow()
        model = name
        apiKey = key
        Diagnostics.i(TAG, "connect: 就绪 endpoint=$endpoint model=$name keyLen=${key.length}")
        return null
    }

    /** 组 multipart 上传 → 解析；判据只有「任务还是不是我们那个」（在基类里） */
    override fun recognize(wav: ByteArray, pcm16: ByteArray): BatchOutcome {
        val request = OpenAiRequest.request(
            OpenAiEndpoint.transcriptionsUrl(endpoint),
            apiKey,
            wav,
            model,
            OpenAiRequest.languageOf(prefs.language),
        )
        val reply = exchange(request)
        val text = OpenAiResponse.parse(reply.code, reply.body).getOrElse { t ->
            // 只取类型与 message：异常正文可能回显请求细节（凭据在头里，不进 message）
            return BatchOutcome.Fail(describe(t))
        }
        return BatchOutcome.Ok(text)
    }

    private companion object {
        const val TAG = "OpenAiAsrClient"

        const val TEXT_NEED_KEY = "未填写 API Key"
        const val TEXT_NEED_MODEL = "未填写模型名"

        /** 兜底失败文案（抽基类前就在这句上；见 override val textFailed） */
        const val TEXT_FAILED = "转写失败"
    }
}

/**
 * 请求组装（纯函数，JVM 单测能直接验 multipart 的字段名与文件名）。
 *
 * 字段按 OpenAI 官方 `/audio/transcriptions` 的要求：
 * `file`（`audio.wav`）+ `model` + `response_format=json`，`language` 可选。
 */
internal object OpenAiRequest {

    const val FILE_PART = "file"
    const val FILE_NAME = "audio.wav"
    const val FIELD_MODEL = "model"
    const val FIELD_FORMAT = "response_format"
    const val FIELD_LANGUAGE = "language"
    const val FORMAT_JSON = "json"
    const val MEDIA_TYPE_WAV = "audio/wav"
    const val HEADER_AUTH = "Authorization"
    const val AUTH_SCHEME = "Bearer "

    /**
     * 语言码映射：本应用的取值（`auto` / `chinese` / `english` …）不是服务端认的码。
     *
     * `auto`（以及认不出的值）返回 null ⇒ **不带** `language` 字段，让服务端自己判定；
     * 乱填一个码比不带更糟（部分服务商直接 400）。
     */
    fun languageOf(raw: String): String? = when (raw.trim().lowercase()) {
        "", "auto" -> null
        "chinese", "zh", "zh-cn", "zh-hans" -> "zh"
        "english", "en" -> "en"
        "cantonese", "yue" -> "yue"
        "japanese", "ja" -> "ja"
        "korean", "ko" -> "ko"
        else -> null
    }

    fun multipart(wav: ByteArray, model: String, language: String?): MultipartBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(FIELD_MODEL, model)
            .addFormDataPart(FIELD_FORMAT, FORMAT_JSON)
            .addFormDataPart(FILE_PART, FILE_NAME, wav.toRequestBody(MEDIA_TYPE_WAV.toMediaType()))
        if (language != null) builder.addFormDataPart(FIELD_LANGUAGE, language)
        return builder.build()
    }

    fun request(url: String, apiKey: String, wav: ByteArray, model: String, language: String?): Request =
        Request.Builder()
            .url(url)
            .header(HEADER_AUTH, AUTH_SCHEME + apiKey)
            .post(multipart(wav, model, language))
            .build()
}

/**
 * 响应解析（纯函数）：`{"text":"…"}` 取文本；非 2xx 把**状态码 + `error.message`** 如实带出。
 *
 * 为什么错误分支必须带这两样：鉴权失败 / 模型不存在 / 余额不足都只能靠它们区分，
 * 只报「转写失败」用户与我们都没法下手（与讯飞 `code` 那条同一个取舍）。
 */
internal object OpenAiResponse {

    const val TEXT_NO_TEXT = "响应里没有 text 字段"

    fun parse(code: Int, body: String?): Result<String> {
        val raw = body.orEmpty()
        if (code !in 200..299) {
            return Result.failure(IllegalStateException(errorText(code, raw)))
        }
        val text = runCatching { jsonText(JSONObject(raw), "text") }.getOrNull()
            ?: return Result.failure(IllegalStateException(TEXT_NO_TEXT))
        return Result.success(text)
    }

    /** `HTTP 401 invalid api key`；`message` 缺失时退回报文体前 60 字（便于排障，仍截断） */
    fun errorText(code: Int, raw: String): String {
        val message = runCatching {
            JSONObject(raw).optJSONObject("error")?.let { jsonText(it, "message") }
        }.getOrNull()
        val detail = message?.trim()?.takeIf { it.isNotEmpty() }
            ?: raw.trim().take(60).takeIf { it.isNotEmpty() }
        return if (detail == null) "HTTP $code" else "HTTP $code $detail"
    }
}
