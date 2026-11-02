package com.jinn.inputmethod

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云一句话识别（`asr.tencentcloudapi.com`，`SentenceRecognition`），[SpeechClient] 的第五个实现。
 *
 * 传输模型与 [OpenAiAsrClient] 同类（本地缓冲整段 → 松手一次性上传），缓冲 / 线程 / 取消 / 超时
 * 全在基类 [BufferedBatchAsrClient]；本类只负责 TC3 签名、请求体与回包解析。
 *
 * 鉴权是 **TC3-HMAC-SHA256**（[TencentTc3]，纯函数）：`Authorization` 头里的日期必须由
 * `X-TC-Timestamp` 按 **UTC** 换算 —— 用本地时区在东八区凌晨会算出前一天，签名当场 InvalidParameter。
 *
 * 服务端限制：单次 ≤60 秒、≤3MB（16k / 单声道 / 16bit 的 60 秒正好 1.92MB，留有余量），
 * 本应用按既有 58 秒收尾（[Prefs.voiceBackend] 属在线后端，见 `JinnIme.autoStopDelayMs`）。
 *
 * ⚠ 凭据（SecretId / SecretKey）只从 [Prefs] 读，SecretKey 只参与本地 HMAC，**绝不进请求体**；
 * 日志里只有长度。
 */
class TencentAsrClient(
    private val prefs: Prefs,
    onState: (LinkState, String?) -> Unit,
    onResult: (RecognitionMessage) -> Unit,
) : BufferedBatchAsrClient(onState, onResult) {

    override val tag: String get() = TAG

    /** [prepare] 校验通过后缓存的取值（上传线程用快照，避免中途改配置） */
    @Volatile
    private var secretId: String = ""

    @Volatile
    private var secretKey: String = ""

    @Volatile
    private var engine: String = TencentAsrRequest.DEFAULT_ENGINE

    override fun prepare(): String? {
        val id = prefs.tencentSecretId.trim()
        if (id.isEmpty()) {
            Diagnostics.w(TAG, "connect: SecretId 未配置")
            return TEXT_NEED_ID
        }
        val key = prefs.tencentSecretKey
        if (key.isEmpty()) {
            Diagnostics.w(TAG, "connect: SecretKey 未配置")
            return TEXT_NEED_KEY
        }
        secretId = id
        secretKey = key
        engine = prefs.tencentEngine
        // SecretKey 只参与本地签名：日志里只允许长度（与另几家同一条纪律）
        Diagnostics.i(TAG, "connect: 就绪 engine=$engine idLen=${id.length} keyLen=${key.length}")
        return null
    }

    override fun recognize(wav: ByteArray, pcm16: ByteArray): BatchOutcome {
        val timestamp = System.currentTimeMillis() / 1000
        val body = TencentAsrRequest.body(wav, engine)
        val reply = exchange(TencentAsrRequest.request(body, secretId, secretKey, timestamp))
        return TencentResponse.parse(reply.code, reply.body)
    }

    private companion object {
        const val TAG = "TencentAsrClient"

        const val TEXT_NEED_ID = "未填写 SecretId"
        const val TEXT_NEED_KEY = "未填写 SecretKey"
    }
}

/**
 * TC3-HMAC-SHA256 签名（纯函数，JVM 单测用固定输入复算整条 `Authorization`）。
 *
 * 计算链（官方文档《签名方法 v3》）：
 * ```
 * CanonicalRequest = POST\n/\n\ncontent-type:<ct>\nhost:<host>\n\ncontent-type;host\n<sha256hex(body)>
 * StringToSign     = TC3-HMAC-SHA256\n<ts>\n<yyyy-MM-dd>/asr/tc3_request\n<sha256hex(CanonicalRequest)>
 * SecretDate       = HMAC("TC3"+SecretKey, date)
 * SecretService    = HMAC(SecretDate, "asr")
 * SecretSigning    = HMAC(SecretService, "tc3_request")
 * Signature        = hex(HMAC(SecretSigning, StringToSign))
 * ```
 * ⚠ 日期取 **UTC**（东八区凌晨的 UTC 日期是前一天，用本地时区算必错）。
 */
internal object TencentTc3 {

    const val ALGORITHM = "TC3-HMAC-SHA256"
    const val SERVICE = "asr"
    const val REQUEST = "tc3_request"
    const val HOST = "asr.tencentcloudapi.com"
    const val CONTENT_TYPE = "application/json; charset=utf-8"
    const val SIGNED_HEADERS = "content-type;host"

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SHA256 = "SHA-256"
    private const val HEX = "0123456789abcdef"

    /** `X-TC-Timestamp`（unix 秒）→ UTC 日期 `yyyy-MM-dd` */
    fun utcDate(timestampSec: Long): String =
        Instant.ofEpochSecond(timestampSec).atZone(ZoneOffset.UTC).toLocalDate().toString()

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA256).digest(data)
        return hex(digest)
    }

    fun hmacSha256(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    fun canonicalRequest(host: String, body: String): String =
        "POST\n/\n\n" +
            "content-type:" + CONTENT_TYPE + "\n" +
            "host:" + host + "\n\n" +
            SIGNED_HEADERS + "\n" +
            sha256Hex(body.toByteArray(Charsets.UTF_8))

    fun stringToSign(timestampSec: Long, date: String, canonicalRequest: String): String =
        ALGORITHM + "\n" +
            timestampSec + "\n" +
            date + "/" + SERVICE + "/" + REQUEST + "\n" +
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))

    fun signature(secretKey: String, date: String, stringToSign: String): String {
        val secretDate = hmacSha256(("TC3" + secretKey).toByteArray(Charsets.UTF_8), date)
        val secretService = hmacSha256(secretDate, SERVICE)
        val secretSigning = hmacSha256(secretService, REQUEST)
        return hex(hmacSha256(secretSigning, stringToSign))
    }

    /**
     * 完整 `Authorization` 头。
     *
     * @param timestampSec 与 `X-TC-Timestamp` 头**同一个值**（两处取不同时刻 = 签名对不上）
     */
    fun authorization(
        secretId: String,
        secretKey: String,
        timestampSec: Long,
        host: String,
        body: String,
    ): String {
        val date = utcDate(timestampSec)
        val signature = signature(secretKey, date, stringToSign(timestampSec, date, canonicalRequest(host, body)))
        return ALGORITHM +
            " Credential=" + secretId + "/" + date + "/" + SERVICE + "/" + REQUEST +
            ", SignedHeaders=" + SIGNED_HEADERS +
            ", Signature=" + signature
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }
}

/**
 * 一句话识别的请求层（纯函数，JVM 单测直接跑）。
 *
 * `Data` 是 base64 的 WAV，`DataLen` 是 **未编码**的原始字节数 —— 填成 base64 长度会被服务端
 * 判格式错（而且错得很隐蔽：只在长音频上冒出来）。
 */
internal object TencentAsrRequest {

    const val ENDPOINT = "https://asr.tencentcloudapi.com/"

    const val ACTION = "SentenceRecognition"
    const val VERSION = "2019-06-14"

    /** 引擎型号：`16k_zh` 是中文 16k 采样率的通用模型（服务端按它选声学模型） */
    const val DEFAULT_ENGINE = "16k_zh"

    const val SOURCE_TYPE = 1
    const val VOICE_FORMAT = "wav"

    const val HEADER_AUTH = "Authorization"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_ACTION = "X-TC-Action"
    const val HEADER_VERSION = "X-TC-Version"
    const val HEADER_TIMESTAMP = "X-TC-Timestamp"

    const val FIELD_ENGINE = "EngSerViceType"
    const val FIELD_SOURCE_TYPE = "SourceType"
    const val FIELD_VOICE_FORMAT = "VoiceFormat"
    const val FIELD_DATA = "Data"
    const val FIELD_DATA_LEN = "DataLen"

    /** 请求体：字段名逐字对齐官方（`EngSerViceType` 里那个大写 S 是官方原文，别「修正」） */
    fun body(wav: ByteArray, engine: String): String {
        val effective = engine.trim().ifEmpty { DEFAULT_ENGINE }
        return JSONObject()
            .put(FIELD_ENGINE, effective)
            .put(FIELD_SOURCE_TYPE, SOURCE_TYPE)
            .put(FIELD_VOICE_FORMAT, VOICE_FORMAT)
            .put(FIELD_DATA, Base64.getEncoder().encodeToString(wav))
            .put(FIELD_DATA_LEN, wav.size)
            .toString()
    }

    /** `DataLen`：**原始字节数**（不是 base64 长度） */
    fun dataLen(wav: ByteArray): Int = wav.size

    fun request(body: String, secretId: String, secretKey: String, timestampSec: Long): Request =
        Request.Builder()
            .url(ENDPOINT)
            .header(
                HEADER_AUTH,
                TencentTc3.authorization(secretId, secretKey, timestampSec, TencentTc3.HOST, body),
            )
            .header(HEADER_CONTENT_TYPE, TencentTc3.CONTENT_TYPE)
            .header(HEADER_ACTION, ACTION)
            .header(HEADER_VERSION, VERSION)
            .header(HEADER_TIMESTAMP, timestampSec.toString())
            .post(body.toRequestBody(TencentTc3.CONTENT_TYPE.toMediaType()))
            .build()
}

/**
 * 回包解析（纯函数）：`Response.Result` 是文本；`Response.Error.Code/Message` 是失败原因。
 *
 * ⚠ 腾讯的鉴权失败 / 未开通 / 时长超限**常是 HTTP 200 + Error 块**，只判状态码会把它们
 * 当成「识别到空文本」（用户看到「没听到语音」而去查麦克风）。故先看 Error，再看 Result。
 */
internal object TencentResponse {

    const val TEXT_NO_RESULT = "响应里没有 Result"
    const val TEXT_FAILED = "识别失败"

    fun parse(code: Int, body: String?): BatchOutcome {
        val raw = body.orEmpty()
        val response = runCatching { JSONObject(raw).optJSONObject("Response") }.getOrNull()
            ?: return BatchOutcome.Fail(errorText(code, raw))
        response.optJSONObject("Error")?.let { err ->
            val errorCode = runCatching { jsonText(err, "Code") }.getOrNull().orEmpty().trim()
            val message = runCatching { jsonText(err, "Message") }.getOrNull().orEmpty().trim()
            // 「Code: Message」原样带出：鉴权 / 未开通 / 超限只能靠它区分
            val detail = listOf(errorCode, message).filter { it.isNotEmpty() }.joinToString(": ")
            return BatchOutcome.Fail(detail.ifEmpty { TEXT_FAILED })
        }
        val result = runCatching { jsonText(response, "Result") }.getOrNull()
            ?: return BatchOutcome.Fail(TEXT_NO_RESULT)
        return BatchOutcome.Ok(result)
    }

    /** 拿不到 `Response` 块时的兜底：状态码 + 报文体前 60 字（仍截断，便于排障） */
    fun errorText(code: Int, raw: String): String {
        val detail = raw.trim().take(60).takeIf { it.isNotEmpty() }
        return if (detail == null) "HTTP $code" else "HTTP $code $detail"
    }
}
