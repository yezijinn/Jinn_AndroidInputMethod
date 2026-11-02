package com.jinn.inputmethod

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * 百度智能云「短语音识别标准版」（先取 token、再 POST JSON），[SpeechClient] 的第七个实现。
 *
 * 传输模型与另三家整段上传同类（本地缓冲 → 松手一次性上传），缓冲 / 线程 / 取消 / 超时全在基类
 * [BufferedBatchAsrClient]；本类只负责 token 缓存、请求体与回包解析。
 *
 * **音频格式固定为 `pcm`**：上传**裸 PCM16**（16k / 单声道 / 小端，44 字节 WAV 头都不带），
 * 请求体里的 `len` 因此等于 `pcm16.size`（**未编码**字节数，不是 base64 长度）。选 `pcm` 而不是
 * `wav` 是因为基类本来就交给我们裸流，少套一层头就少一个「头里长度与 len 不一致」的出错面；
 * 代价是 `format` / `rate` / `channel` 三个字段必须与录音口径逐字对齐（[BaiduAsrRequest]）。
 *
 * token 缓存（[BaiduTokenCache]）：`baiduToken` 走凭据加密落盘，`baiduTokenExpireAt` 记 epoch 秒；
 * 本地判过期（提前 5 分钟）与回包 `err_no` 为 3302 / 110 / 111 时都**重取一次并重试一次**
 * （[BaiduRecognizeFlow] 把「只重试一次」写成结构，不是靠计数变量）。
 *
 * ⚠ 两件凭据只从 [Prefs] 读：API Key 进 token 请求的 `client_id`，Secret Key 进 `client_secret`，
 * 识别请求里只出现 token。日志只记长度与 err_no。
 */
class BaiduAsrClient(
    private val prefs: Prefs,
    onState: (LinkState, String?) -> Unit,
    onResult: (RecognitionMessage) -> Unit,
) : BufferedBatchAsrClient(onState, onResult) {

    override val tag: String get() = TAG

    /** [prepare] 校验通过后缓存的取值（上传线程用快照，避免中途改配置） */
    @Volatile
    private var apiKey: String = ""

    @Volatile
    private var secretKey: String = ""

    @Volatile
    private var devPid: Int = Prefs.DEFAULT_BAIDU_DEV_PID

    /** token 落盘缓存：读写都走 [Prefs] 的加密通道（token 也算凭据，绝不进日志） */
    private val tokens = BaiduTokenCache(
        readToken = { prefs.baiduToken },
        writeToken = { prefs.baiduToken = it },
        readExpireAt = { prefs.baiduTokenExpireAt },
        writeExpireAt = { prefs.baiduTokenExpireAt = it },
    )

    override fun prepare(): String? {
        val key = prefs.baiduSpeechApiKey.trim()
        if (key.isEmpty()) {
            Diagnostics.w(TAG, "connect: API Key 未配置")
            return TEXT_NEED_KEY
        }
        val secret = prefs.baiduSpeechSecretKey
        if (secret.isEmpty()) {
            Diagnostics.w(TAG, "connect: Secret Key 未配置")
            return TEXT_NEED_SECRET
        }
        apiKey = key
        secretKey = secret
        devPid = prefs.baiduDevPid
        // 日志只许长度与 dev_pid：凭据原文进日志等于把账号交出去
        Diagnostics.i(TAG, "connect: 就绪 devPid=$devPid keyLen=${key.length} secretLen=${secret.length}")
        return null
    }

    override fun recognize(wav: ByteArray, pcm16: ByteArray): BatchOutcome =
        BaiduRecognizeFlow.run(
            ensureToken = ::ensureToken,
            attempt = { token ->
                val reply = exchange(
                    BaiduAsrRequest.recognizeRequest(
                        BaiduAsrRequest.body(pcm16, token, devPid),
                    ),
                )
                BaiduResponse.parse(reply.code, reply.body)
            },
        )

    /**
     * 取 token：`force=false` 先吃本地缓存（未过期就不再打一次鉴权接口），
     * `force=true` 或缓存过期才真发请求。
     *
     * 失败原因原样包成 [BaiduToken.Failed] 交回 [BaiduRecognizeFlow] —— 用户要看到
     * `invalid_client` / `unknown client id`，而不是一句「获取 token 失败」。
     */
    private fun ensureToken(force: Boolean): BaiduToken {
        if (!force) {
            tokens.fresh()?.let {
                Diagnostics.i(TAG, "token 缓存命中（未过期）")
                return BaiduToken.Ready(it, 0)
            }
        }
        val reply = exchange(BaiduAsrRequest.tokenRequest(apiKey, secretKey))
        val parsed = BaiduTokenResponse.parse(reply.code, reply.body)
        if (parsed is BaiduToken.Ready) {
            tokens.save(parsed.value, parsed.expiresInSec)
            Diagnostics.i(TAG, "token 已获取 expiresIn=${parsed.expiresInSec}")
        } else {
            Diagnostics.w(TAG, "token 获取失败")
        }
        return parsed
    }

    private companion object {
        const val TAG = "BaiduAsrClient"

        const val TEXT_NEED_KEY = "未填写百度 API Key"
        const val TEXT_NEED_SECRET = "未填写百度 Secret Key"
    }
}

/**
 * token 缓存（纯函数 + 两条读写 lambda，JVM 单测直接喂假存取）。
 *
 * 落盘的是**真实过期时刻**（`now + expires_in`），「提前 5 分钟算过期」的余量放在
 * [BaiduTokenPolicy.isFresh] 里判：把偏移写进落盘值的话，读侧再也分不清「这串数字是到期时刻
 * 还是到期时刻减五分钟」，换个后端复用同一份缓存就会早/晚过期。
 */
internal class BaiduTokenCache(
    private val readToken: () -> String,
    private val writeToken: (String) -> Unit,
    private val readExpireAt: () -> Long,
    private val writeExpireAt: (Long) -> Unit,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** 可用的缓存 token；没有 / 已过期（含提前量）返回 null ⇒ 调用方去真取一次 */
    fun fresh(): String? {
        val token = readToken().trim()
        if (token.isEmpty()) return null
        return if (BaiduTokenPolicy.isFresh(token, readExpireAt(), nowSec())) token else null
    }

    /** 记下新 token 与它的过期时刻（`expires_in` 非法的服务端回包按 0 处理 ⇒ 下次直接重取） */
    fun save(token: String, expiresInSec: Long) {
        writeToken(token)
        writeExpireAt(BaiduTokenPolicy.expireAt(nowSec(), expiresInSec))
    }
}

/** token 时效判据（纯函数）：提前 [EARLY_EXPIRE_SEC] 视为过期，够一次上传跑完不被中途踢掉 */
internal object BaiduTokenPolicy {

    const val EARLY_EXPIRE_SEC = 300L

    fun expireAt(nowSec: Long, expiresInSec: Long): Long = nowSec + expiresInSec.coerceAtLeast(0)

    fun isFresh(token: String, expireAtSec: Long, nowSec: Long): Boolean =
        token.isNotEmpty() && expireAtSec - nowSec > EARLY_EXPIRE_SEC
}

/** 一次取 token 的结局：拿到就带 `expires_in`，没拿到就是**能直接给用户看**的那句话 */
internal sealed class BaiduToken {

    class Ready(val value: String, val expiresInSec: Long) : BaiduToken()

    class Failed(val outcome: BatchOutcome.Fail) : BaiduToken()
}

/** 一次识别回包的结局 + 原始 `err_no`（0 = 成功）：`err_no` 决定要不要换 token 重试 */
internal data class BaiduReply(val errNo: Int, val outcome: BatchOutcome)

/**
 * 「取 token → 识别 → token 失效就重取重试一次」的**全部**执行结构（纯函数）。
 *
 * 写成结构而不是在 `recognize` 里数次数：`err_no=3302` 时最怕的是「重试再失败再重试」，
 * 死循环会把配额烧光还让用户一直等。这里第二次的结果无论成败都直接返回。
 */
internal object BaiduRecognizeFlow {

    fun run(ensureToken: (Boolean) -> BaiduToken, attempt: (String) -> BaiduReply): BatchOutcome {
        val first = when (val token = ensureToken(false)) {
            is BaiduToken.Failed -> return token.outcome
            is BaiduToken.Ready -> attempt(token.value)
        }
        if (!BaiduErr.isTokenInvalid(first.errNo)) return first.outcome
        Diagnostics.i("BaiduAsrClient", "token 失效 errNo=${first.errNo}，重取后重试一次")
        val second = when (val token = ensureToken(true)) {
            is BaiduToken.Failed -> return token.outcome
            is BaiduToken.Ready -> attempt(token.value)
        }
        return second.outcome
    }
}

/** 百度返回码（纯函数）：只有 token 类错误值得重试，其余（音频太长 / 格式错）重试一百次也一样 */
internal object BaiduErr {

    const val OK = 0

    /** token 校验失败（最常见的「AK/SK 换了、缓存还是旧的」） */
    const val TOKEN_INVALID = 3302

    /** 通用鉴权失败：access token 无效 / 已过期 */
    const val ACCESS_TOKEN_INVALID = 110
    const val ACCESS_TOKEN_EXPIRED = 111

    fun isTokenInvalid(errNo: Int): Boolean =
        errNo == TOKEN_INVALID || errNo == ACCESS_TOKEN_INVALID || errNo == ACCESS_TOKEN_EXPIRED

    /** 返回码 → 中文可读原因；认不出的返回 null（由调用方原样带出 `err_msg`） */
    fun readable(errNo: Int): String? = when (errNo) {
        3300 -> "请求参数不对"
        3301 -> "音频质量太差，请重新录制"
        TOKEN_INVALID -> "鉴权失败，token 无效或已过期"
        3303 -> "服务端出错"
        3304 -> "请求过于频繁（QPS 超限）"
        3305 -> "当日配额已用完"
        3307 -> "识别出错"
        3308 -> "音频过长"
        3309 -> "音频数据有问题"
        3310 -> "音频文件过大"
        3311 -> "采样率不对（要求 16000）"
        3312 -> "音频格式不对（本端发 pcm）"
        3313 -> "音频声道数不对（要求单声道）"
        3314 -> "音频过短"
        3315 -> "音频为空"
        ACCESS_TOKEN_INVALID, ACCESS_TOKEN_EXPIRED -> "鉴权失败，token 无效或已过期"
        else -> null
    }
}

/**
 * 请求层（纯函数）：token 接口 + 识别接口，字段名逐字对齐官方文档。
 *
 * ⚠ `len` 是**未编码**字节数（`pcm16.size`）：填 base64 长度在短音频上也可能「看起来正常」，
 * 只在长音频上变成 `err_no=3309`，从日志里完全看不出是长度算错了。
 * ⚠ `speech` 用 [Base64.getEncoder]（基本编码，不带换行）；MIME 编码器每 76 字符插 `\r\n`，
 * 服务端会当成非法 base64。
 * ⚠ `cuid` 是固定串 [DEFAULT_CUID]，**不取** IMEI / MAC / 机型：本输入法不采集设备标识，
 * 官方只要求「用户唯一标识」，一个常量对本应用（单用户自用）足够。
 */
internal object BaiduAsrRequest {

    const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
    const val RECOGNIZE_URL = "https://vop.baidu.com/server_api"

    const val CONTENT_TYPE_JSON = "application/json"

    /** 音频形态：裸 PCM16（无 WAV 头），与 [LEN_FIELD] 的语义绑定 */
    const val FORMAT_PCM = "pcm"

    /** 采样率 / 声道：必须与 [MicRecorder] 的输出、[WavWriter] 的头一致（16k 单声道） */
    const val RATE = 16_000
    const val CHANNEL = 1

    const val DEFAULT_CUID = "jinn-ime-android"

    const val FIELD_FORMAT = "format"
    const val FIELD_RATE = "rate"
    const val FIELD_CHANNEL = "channel"
    const val FIELD_CUID = "cuid"
    const val FIELD_TOKEN = "token"
    const val FIELD_DEV_PID = "dev_pid"
    const val FIELD_LEN = "len"
    const val FIELD_SPEECH = "speech"

    const val PARAM_GRANT_TYPE = "grant_type=client_credentials"
    const val PARAM_CLIENT_ID = "client_id="
    const val PARAM_CLIENT_SECRET = "client_secret="

    /**
     * token 地址：`grant_type` / `client_id` / `client_secret` 三个参数。
     *
     * 两个凭据都过 [queryEscape]：AK/SK 里出现 `+` / `/` / `=`（base64 形态很常见）时
     * 不转义会被当成空格或分隔符，服务端只回 `invalid_client`，看不出是编码问题。
     */
    fun tokenUrl(apiKey: String, secretKey: String): String =
        TOKEN_URL + "?" + PARAM_GRANT_TYPE +
            "&" + PARAM_CLIENT_ID + queryEscape(apiKey) +
            "&" + PARAM_CLIENT_SECRET + queryEscape(secretKey)

    /** POST 空体（官方两种方法都收；POST 不会把凭据留在 URL 里被中间层记下来） */
    fun tokenRequest(apiKey: String, secretKey: String): Request =
        Request.Builder()
            .url(tokenUrl(apiKey, secretKey))
            .post(ByteArray(0).toRequestBody(null))
            .build()

    /**
     * 识别请求体。
     *
     * @param pcm16 裸 PCM16；`len` 与 `speech` 都按它算（同一份字节，不可能对不上）
     */
    fun body(pcm16: ByteArray, token: String, devPid: Int, cuid: String = DEFAULT_CUID): String =
        JSONObject()
            .put(FIELD_FORMAT, FORMAT_PCM)
            .put(FIELD_RATE, RATE)
            .put(FIELD_CHANNEL, CHANNEL)
            .put(FIELD_CUID, cuid)
            .put(FIELD_TOKEN, token)
            .put(FIELD_DEV_PID, devPid)
            .put(FIELD_LEN, pcm16.size)
            .put(FIELD_SPEECH, Base64.getEncoder().encodeToString(pcm16))
            .toString()

    /** `len`：**原始字节数**（不是 base64 长度） */
    fun lenOf(pcm16: ByteArray): Int = pcm16.size

    fun recognizeRequest(json: String): Request =
        Request.Builder()
            .url(RECOGNIZE_URL)
            .header("Content-Type", CONTENT_TYPE_JSON)
            // 用 ByteArray 而不是 String.toRequestBody：后者会把 `charset=utf-8` 拼进 Content-Type，
            // 百度文档写的是裸 `application/json`（2026-11-02 复核）
            .post(json.toByteArray(Charsets.UTF_8).toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .build()

    fun queryEscape(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** 取 token 的回包解析（纯函数）：`access_token` / `expires_in`，失败给 `error` + `error_description` */
internal object BaiduTokenResponse {

    const val FIELD_ACCESS_TOKEN = "access_token"
    const val FIELD_EXPIRES_IN = "expires_in"
    const val FIELD_ERROR = "error"
    const val FIELD_ERROR_DESCRIPTION = "error_description"

    const val TEXT_NO_TOKEN = "token 响应里没有 access_token"
    const val TEXT_BAD_BODY = "token 响应无法解析"

    fun parse(code: Int, body: String?): BaiduToken {
        val raw = body.orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BaiduToken.Failed(BatchOutcome.Fail(errorText(code, raw)))
        // 失败是 HTTP 200 + {"error":"invalid_client","error_description":"unknown client id"}
        val error = runCatching { jsonText(json, FIELD_ERROR) }.getOrNull().orEmpty().trim()
        if (error.isNotEmpty()) {
            val description = runCatching { jsonText(json, FIELD_ERROR_DESCRIPTION) }.getOrNull()
                .orEmpty().trim()
            return BaiduToken.Failed(BatchOutcome.Fail(describe(error, description)))
        }
        val token = runCatching { jsonText(json, FIELD_ACCESS_TOKEN) }.getOrNull().orEmpty().trim()
        if (token.isEmpty()) {
            return BaiduToken.Failed(BatchOutcome.Fail(if (code in 200..299) TEXT_NO_TOKEN else errorText(code, raw)))
        }
        val expiresIn = runCatching { json.optLong(FIELD_EXPIRES_IN, 0L) }.getOrDefault(0L)
        return BaiduToken.Ready(token, expiresIn)
    }

    /** `error: error_description` 原样带出：`invalid_client` / `unknown client id` 才是用户要改的东西 */
    fun describe(error: String, description: String): String =
        if (description.isEmpty()) "token 获取失败：$error" else "token 获取失败：$error（$description）"

    /** 拿不到 JSON 时的兜底：状态码 + 报文体前 60 字（截断，便于排障） */
    fun errorText(code: Int, raw: String): String {
        val detail = raw.trim().take(60).takeIf { it.isNotEmpty() }
        return if (detail == null) "token 获取失败：HTTP $code" else "token 获取失败：HTTP $code $detail"
    }
}

/**
 * 识别回包解析（纯函数）：`err_no == 0` 取 `result[0]`，其余 `err_no: 可读原因`。
 *
 * `err_no` 单独带出来（[BaiduReply.errNo]）：换 token 重试的判据只能看返回码，
 * 从已经拼成人话的失败文案里再匹配「3302」等于把判据建在文案上。
 */
internal object BaiduResponse {

    const val FIELD_ERR_NO = "err_no"
    const val FIELD_ERR_MSG = "err_msg"
    const val FIELD_RESULT = "result"

    const val TEXT_FAILED = "识别失败"

    fun parse(code: Int, body: String?): BaiduReply {
        val raw = body.orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BaiduReply(-1, BatchOutcome.Fail(errorText(code, raw)))
        val errNo = runCatching { json.optInt(FIELD_ERR_NO, -1) }.getOrDefault(-1)
        if (errNo == BaiduErr.OK) {
            val result = runCatching { json.optJSONArray(FIELD_RESULT) }.getOrNull()
            // 成功但没听到声音：空文本也是 Ok（链路通，用户只要说话）
            val text = result?.optString(0).orEmpty()
            return BaiduReply(BaiduErr.OK, BatchOutcome.Ok(text))
        }
        if (errNo < 0) return BaiduReply(-1, BatchOutcome.Fail(errorText(code, raw)))
        val message = runCatching { jsonText(json, FIELD_ERR_MSG) }.getOrNull().orEmpty().trim()
        return BaiduReply(errNo, BatchOutcome.Fail(describe(errNo, message)))
    }

    /** `err_no: 中文原因（服务端 err_msg）`；认不出的返回码原样带出 err_msg */
    fun describe(errNo: Int, errMsg: String): String {
        val readable = BaiduErr.readable(errNo)
        val message = errMsg.trim()
        return when {
            readable != null && message.isNotEmpty() -> "$errNo: $readable（$message）"
            readable != null -> "$errNo: $readable"
            message.isNotEmpty() -> "$errNo: $message"
            else -> "$errNo: $TEXT_FAILED"
        }
    }

    /** 拿不到 `err_no` 时的兜底（HTML 错误页 / 网关拦截）：状态码 + 报文体前 60 字 */
    fun errorText(code: Int, raw: String): String {
        val detail = raw.trim().take(60).takeIf { it.isNotEmpty() }
        return when {
            detail == null -> "HTTP $code"
            else -> "HTTP $code $detail"
        }
    }
}
