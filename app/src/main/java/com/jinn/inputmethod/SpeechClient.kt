package com.jinn.inputmethod

/** 连接状态，用来驱动键盘顶部的状态条 */
enum class LinkState { IDLE, CONNECTING, ONLINE, OFFLINE }

/**
 * 语音识别客户端的统一对外面。
 *
 * 一次听写固定是 [beginTask] → 若干 [sendChunk] → [endTask]（取消走 [cancelTask]）；
 * [RecognitionMessage.text] 恒为**整段累积文本**，调用方整体覆盖显示，不要自己再拼。
 *
 * 八个实现：
 *  - [AsrClient]：自建 CapsWriter（本机 NAS，长连接常驻，空闲关闭见 [scheduleIdleClose]）；
 *  - [XfIatClient]：讯飞在线听写 V2（一次会话一条 WebSocket，收尾即断）；
 *  - [OpenAiAsrClient]：OpenAI 兼容转写（本地缓冲整段，松手一次性上传）；
 *  - [AliAsrClient]：阿里云百炼实时识别（双向流式，一次会话一条 WebSocket）；
 *  - [TencentAsrClient]：腾讯云一句话识别（本地缓冲整段，TC3 签名，松手一次性上传）；
 *  - [AzureAsrClient]：Azure AI Speech 短音频识别 REST（本地缓冲整段，松手一次性上传）；
 *  - [BaiduAsrClient]：百度智能云短语音识别（先取 access_token，再 POST JSON）；
 *  - [DeepgramAsrClient]：Deepgram Pre-recorded（裸 WAV + `Authorization: Token`）。
 *
 * 缓冲式整段上传的五家（[OpenAiAsrClient] / [TencentAsrClient] / [AzureAsrClient] /
 * [BaiduAsrClient] / [DeepgramAsrClient]）共用基类 [BufferedBatchAsrClient]：
 * 状态机、缓冲、WAV、线程、取消与超时只有一份，子类只管请求与回包。
 *
 * 选哪个由 [Prefs.voiceBackend] 决定（见 `SpeechClients.create`）；调用方只认本接口，
 * 各家的状态条 / 录音 / 结果落字链路共用一套。
 *
 * 回调在 OkHttp 的 IO 线程触发，接收方需自行切回主线程。
 */
interface SpeechClient {

    val state: LinkState

    /**
     * 建立连接。[force] = 忽略既有连接重建；[userInitiated] = 是否撤销已排程的空闲关闭
     * （只有「用户又要用了」才撤销，内部自动重连不许续命，见 [AsrClient.connect]）。
     */
    fun connect(force: Boolean = false, userInitiated: Boolean = true)

    /** 开始一次听写。连接未就绪时返回 null（并顺手触发一次建连）。 */
    fun beginTask(): String?

    /** 送入一段麦克风音频：float32 小端 / 16kHz / 单声道（[MicRecorder] 的原始输出）。 */
    fun sendChunk(pcmFloat32LittleEndian: ByteArray)

    /** 正常收尾。@return 收尾包是否真的送出（false = 拿不到最终结果，调用方不要挂等待态） */
    fun endTask(): Boolean

    /** 取消：照样收尾以释放服务端资源，但结果丢弃。@return 同 [endTask] */
    fun cancelTask(): Boolean

    /** 主动关闭连接（幂等）。 */
    fun close()

    /** 排一次「空闲关闭」：`delayMs` 后若仍无在途任务就断开。 */
    fun scheduleIdleClose(delayMs: Long = DEFAULT_IDLE_CLOSE_MS)

    /** 撤销待触发的空闲关闭（幂等、廉价）。 */
    fun cancelIdleClose()

    companion object {
        /**
         * 空闲关闭默认窗口（毫秒）。
         *
         * 必须 ≥ 识别兜底超时（60s）：松手后服务端要 1~3 秒才回 final，
         * 窗口比它短就会在用户还等结果时把连接关掉。90s 是留了余量的取值。
         */
        const val DEFAULT_IDLE_CLOSE_MS = 90_000L
    }
}

/**
 * 交到状态条 / 语音测试结论行 / 诊断日志之前的**凭据刮除**（纯函数，JVM 单测直接跑）。
 *
 * 为什么需要：八家的失败文案兜底都是「异常 message 截若干字」，而异常正文可能整条带上请求 URL
 * 或请求头。百度换 token 的地址里就写着 `client_id=` / `client_secret=`；OkHttp 的
 * `unexpected url: …` / `Expected URL scheme …` 会把地址原样抄进 message。
 *
 * 今天这些路径都**不可达**（主机名是常量、参数过 `URLEncoder`、地址先过各家的 normalize），
 * 但「不可达」是靠调用方的先后顺序维持的 —— 下一处改请求就可能变成可达。刮一道比事后追日志便宜。
 *
 * 只刮两种形态（鉴权前缀 / `键=值`），普通报文（`Expected HTTP 101 … '401 Unauthorized'`、
 * `code 10163`、`3302: 鉴权失败，token 无效或已过期`）一字不动。
 */
internal object SpeechErrorText {

    /** `Bearer sk-…` / `Token abcd1234…`：鉴权方案前缀后跟一串够长的裸值 */
    private val AUTH_PREFIX = Regex("(?i)\\b(bearer|token)\\s+[A-Za-z0-9._~+/=-]{6,}")

    /**
     * `client_secret=…` / `api_key: …`：键名在**值之前**，故排在 [AUTH_PREFIX] 之后刮。
     *
     * `secret_?id` 与 `credential` 补的是腾讯那两处（`SecretId=AKID…`、
     * `TC3-HMAC-SHA256 Credential=AKID….`）—— SecretId 是配对的账号标识，与 SecretKey 同一条边界。
     * ⚠ `secret_?id` 必须排在裸 `secret` **之前**：`secret` 先命中时后面紧跟的是 `Id` 而不是 `=`，
     * 整段会被判「不是键值对」而漏刮；反过来不会（`client_secret=` 里 `client_secret` 排在更前）。
     */
    private val KEYED = Regex(
        "(?i)\\b(client_id|client_secret|secret_?id|credential|access_token|api[-_]?key|apikey" +
            "|secret|token|password|passwd|key)\\s*[=:]\\s*[^\\s&,;)\"']+"
    )

    /**
     * 讯飞签名地址里的 `authorization=<两级 base64 签名>`：签名由 APISecret 派生，与凭据等价。
     *
     * 只认 `=`，不动头部的 `Authorization: Bearer …`（那是 [AUTH_PREFIX] 的地盘）——
     * 两者都管的话，头会被刮成 `Authorization=*** ***`，状态条上读不出「被刮的是什么」。
     */
    private val AUTH_PARAM = Regex("(?i)\\bauthorization\\s*=\\s*[^\\s&,;)\"']+")

    /**
     * @return 刮过的文案；入参为空时返回 null（调用方用各自的兜底句）。
     *         幂等（刮过的文案再刮一遍不变）—— 调用点可能既在 `describe` 又在状态回调处刮一次。
     */
    fun scrub(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val noPrefix = AUTH_PREFIX.replace(text, "\$1 ***")
        return KEYED.replace(AUTH_PARAM.replace(noPrefix, "authorization=***"), "\$1=***")
    }
}
