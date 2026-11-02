package com.jinn.inputmethod

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * 讯飞「语音听写大模型（中英）V2.0」WebApi 的**纯逻辑层**：鉴权 URL、首/中/末帧、响应解析、
 * 文本累积、float32→PCM16。
 *
 * **不含任何 Android 依赖**（Base64 用 `java.util.Base64`、HMAC 用 `javax.crypto.Mac`），
 * 因此协议细节可以在 JVM 单测里逐条钉住 —— 真机排障只看得到「识别到 0 个字」，
 * 分不出是签名错了还是帧字段错了。
 *
 * 协议出处：官方文档「四、接口鉴权」与官方 Demo 的首/中/末帧；`date` 必须 GMT 且与服务端
 * 偏差 ≤300 秒，故时间由调用方传入（[rfc1123Gmt] 只是默认取法）。
 *
 * 鉴权三段式（**逐字节照抄，不要自作聪明**）：
 * ```
 * signature_origin     = "host: {host}\ndate: {date}\nGET {path} HTTP/1.1"
 * signature            = Base64(HMAC-SHA256(apiSecret 原始字节, signature_origin))
 * authorization_origin = 'api_key="{apiKey}", algorithm="hmac-sha256",
 *                         headers="host date request-line", signature="{signature}"'
 * authorization        = Base64(authorization_origin)
 * url                  = "wss://{host}{path}?host={host}&date={date}&authorization={authorization}"
 * ```
 * `headers` 是**固定字符串**（列名），不是值；HMAC 输出是原始字节，先 Base64 一次再进
 * `authorization_origin`，整体再 Base64 一次 —— 两级 Base64。
 */
object XfIatProtocol {

    /** 听写端点主机（与 `wss://iat.cn-huabei-1.xf-yun.com/v1` 一致） */
    const val HOST = "iat.cn-huabei-1.xf-yun.com"

    /** 听写端点路径 */
    const val PATH = "/v1"

    /** 音频采样率（服务端只认 16k / 单声道 / 16bit） */
    const val SAMPLE_RATE = 16000

    /** 音频通道数 */
    const val CHANNELS = 1

    /** 位深 */
    const val BIT_DEPTH = 16

    /** iat 业务域：语音听写大模型（中英）V2.0 */
    const val DOMAIN = "spark_asr_v2"
    const val LANGUAGE = "zh_cn"
    const val ACCENT = "mandarin"

    /** 静音判定阈值（毫秒），服务端据此在没人说话时收尾 */
    const val EOS_MS = 60000

    /** 动态修正（wpgs）：开了才有中间结果与 `pgs` / `rg` */
    const val DWA = "wpgs"

    /** 一帧音频的实时量：40ms × 16000Hz × 2 字节 = 1280 字节（官方 Demo 的节拍） */
    const val FRAME_BYTES = 1280

    private val UTF8 = Charsets.UTF_8

    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * 生成 `EEE, dd MMM yyyy HH:mm:ss 'GMT'` 形式的日期串（Locale.US、GMT）。
     *
     * 必须是 GMT 而不是本机时区：服务端按 GMT 解析并与自己的时钟比，偏差 >300 秒直接 401。
     */
    fun rfc1123Gmt(nowMs: Long): String {
        // SimpleDateFormat 非线程安全 ⇒ 每次新建（调用频率是「一次会话一次」）
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date(nowMs))
    }

    /**
     * 拼鉴权后的 WebSocket 地址。
     *
     * @param appId 只用于首帧 `header.app_id`，**不参与 URL**（讯飞要求放在帧里）；
     *              参数留在这里是为了让调用方一处取齐三件凭据，不至于漏传。
     * @param date [rfc1123Gmt] 的产物；由调用方传入，便于用固定向量做单测。
     */
    fun buildAuthUrl(
        appId: String,
        apiKey: String,
        apiSecret: String,
        date: String,
        host: String = HOST,
        path: String = PATH,
    ): String {
        val signatureOrigin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        val signature = base64(hmacSha256(apiSecret, signatureOrigin))
        val authorizationOrigin = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line\", signature=\"$signature\""
        val authorization = base64(authorizationOrigin.toByteArray(UTF8))
        // date 里的空格与逗号必须编码：OkHttp 会把空格编成 %20，但**签名用的是原文**，
        // 服务端解码回来比对，所以先自己编码，避免「签名对、URL 被改写」这类对不上的情形。
        val encodedDate = java.net.URLEncoder.encode(date, "UTF-8").replace("+", "%20")
        return "wss://$host$path?host=$host&date=$encodedDate&authorization=$authorization"
    }

    /** 首帧：`header.status=0`、`audio.status=0`、带 iat 业务参数 */
    fun firstFrame(appId: String, pcm: ByteArray, seq: Int = 0): String = JSONObject().apply {
        put("header", JSONObject().put("app_id", appId).put("status", 0))
        put("parameter", JSONObject().put("iat", iatParameter()))
        put("payload", JSONObject().put("audio", audioPayload(pcm, seq, status = 0)))
    }.toString()

    /**
     * 中间帧：`header.status=1`、`audio.status=1`、seq 递增。
     *
     * ⚠ `header.app_id` **每一帧都要带**（2026-11-01 真机实测）：只在首帧带时，
     * 第一条音频帧就被服务端拒掉 —— `code=10163 $.header.app_id field is required`。
     * 用同一套参数在官方端点做过 A/B：音频帧带 app_id 全绿，不带必报 10163。
     */
    fun audioFrame(appId: String, pcm: ByteArray, seq: Int): String = JSONObject().apply {
        put("header", JSONObject().put("app_id", appId).put("status", 1))
        put("payload", JSONObject().put("audio", audioPayload(pcm, seq, status = 1)))
    }.toString()

    /** 末帧：`header.status=2`、`audio.status=2`、`audio=""`（空串，不是省略）；`app_id` 同上 */
    fun lastFrame(appId: String, seq: Int): String = JSONObject().apply {
        put("header", JSONObject().put("app_id", appId).put("status", 2))
        put("payload", JSONObject().put("audio", audioPayload(ByteArray(0), seq, status = 2)))
    }.toString()

    private fun iatParameter(): JSONObject = JSONObject().apply {
        put("language", LANGUAGE)
        put("accent", ACCENT)
        put("domain", DOMAIN)
        put("eos", EOS_MS)
        put("dwa", DWA)
        put("result", JSONObject().apply {
            put("encoding", "utf8")
            put("compress", "raw")
            put("format", "json")
        })
    }

    private fun audioPayload(pcm: ByteArray, seq: Int, status: Int): JSONObject = JSONObject().apply {
        put("encoding", "raw")
        put("sample_rate", SAMPLE_RATE)
        put("channels", CHANNELS)
        put("bit_depth", BIT_DEPTH)
        put("status", status)
        put("seq", seq)
        // 末帧是空串而不是缺字段：服务端按「收到的音频到此为止」收尾
        put("audio", if (pcm.isEmpty()) "" else base64(pcm))
    }

    /** 响应里 `payload.result` 的一段（`text` 已解码并按 `ws[].cw[].w` 拼好） */
    data class XfResult(
        val status: Int,
        val seq: Int,
        /**
         * 本片序号（`sn`，1 起）。
         *
         * wpgs 下**同一句内严格递增**：修正上一片的内容时给的是**新的 `sn`** + 指向旧的 `rg`，
         * 所以「`sn` 回到 1」只可能是 `vgap` 断句后的新一句 —— 累积器据此切分句子。
         */
        val sn: Int,
        val ls: Boolean,
        val pgs: String?,
        val rg: List<Int>,
        val text: String,
    )

    /** 服务端回包：`header` 三件 + `header.status` + 可选的 `payload.result` */
    data class XfResponse(
        val code: Int,
        val message: String,
        val sid: String,
        val status: Int,
        val result: XfResult?,
    )

    /**
     * 解析一条文本回包（二进制帧不用，`encoding=utf8` 已声明）。
     *
     * 判据全走 `opt*`：解析失败返回 null（调用方只记长度、不记原文 —— 报文里可能就是识别正文）。
     */
    fun parseResponse(raw: String): XfResponse? = runCatching {
        val json = JSONObject(raw)
        val header = json.optJSONObject("header") ?: return null
        val resultObj = json.optJSONObject("payload")?.optJSONObject("result")
        val textJson = resultObj?.let { decodeText(it.optString("text", "")) }
        val result = resultObj?.let {
            XfResult(
                status = it.optInt("status", 0),
                seq = it.optInt("seq", 0),
                sn = snOf(it, textJson),
                ls = it.optBoolean("ls", false) || textJson?.optBoolean("ls", false) == true,
                pgs = pgsOf(it, textJson),
                rg = rgOf(it, textJson),
                text = joinWords(textJson),
            )
        }
        XfResponse(
            code = header.optInt("code", -1),
            message = header.optString("message", "").orEmpty(),
            sid = header.optString("sid", "").orEmpty(),
            status = header.optInt("status", 0),
            result = result,
        )
    }.getOrNull()

    /** `pgs` 在解码后的 `text` 里（`apd` 追加 / `rpl` 替换）；个别版本放在 result 上，两处都认 */
    private fun pgsOf(result: JSONObject, textJson: JSONObject?): String? {
        val raw = (result.opt("pgs") as? String) ?: (textJson?.opt("pgs") as? String)
        return raw?.takeIf { it.isNotBlank() && it != "null" }
    }

    /** `rg` 是 1 基的闭区间 `[start, end]`，只对 `pgs=rpl` 有意义 */
    private fun rgOf(result: JSONObject, textJson: JSONObject?): List<Int> {
        val array = result.optJSONArray("rg") ?: textJson?.optJSONArray("rg") ?: return emptyList()
        return (0 until array.length()).map { array.optInt(it) }
    }

    /**
     * `sn` 在解码后的 `text` 里（与 `pgs` / `rg` 同处），个别版本放 result 上，两处都认。
     *
     * 取不到时返回 0：累积器只把「回到 1」当断句，0 不会误触发切分。
     */
    private fun snOf(result: JSONObject, textJson: JSONObject?): Int {
        val inner = textJson?.optInt("sn", 0) ?: 0
        return if (inner > 0) inner else result.optInt("sn", 0)
    }

    /** `text` → base64 → JSON；解不开就是空段（不抛，服务端偶尔给空串） */
    private fun decodeText(encoded: String): JSONObject? {
        if (encoded.isEmpty()) return null
        return runCatching {
            JSONObject(String(Base64.getDecoder().decode(encoded), UTF8))
        }.getOrNull()
    }

    /** 拼 `ws[].cw[].w`：一个 ws 是多候选，只取第一个 cw 的 w（官方 Demo 同款） */
    private fun joinWords(textJson: JSONObject?): String {
        val ws = textJson?.optJSONArray("ws") ?: return ""
        val out = StringBuilder()
        for (i in 0 until ws.length()) {
            val cw = ws.optJSONObject(i)?.optJSONArray("cw") ?: continue
            if (cw.length() == 0) continue
            out.append(cw.optJSONObject(0)?.optString("w", "").orEmpty())
        }
        return out.toString()
    }

    private fun hmacSha256(secret: String, origin: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(UTF8), HMAC_ALGORITHM))
        return mac.doFinal(origin.toByteArray(UTF8))
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * float32 小端 → PCM16 小端（16k / 单声道）。
     *
     * 服务端只要 16bit，而 [MicRecorder] 给的是 float32。**不乘增益**：音量交给系统的
     * `VOICE_RECOGNITION` 源处理，客户端再放大只会把削顶噪声一起放大。
     * 饱和钳位到 [-1, 1]：越界的浮点（部分机型偶发）不能让它回绕成反相爆音。
     */
    fun float32ToPcm16(src: ByteArray): ByteArray {
        val samples = src.size / 4
        val out = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val input = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val v = input.getFloat()
            out.putShort(toPcm16(v))
        }
        return out.array()
    }

    /** 单个样本：非负按 32767、负值按 32768 缩放，正好铺满 16bit 全量程 */
    private fun toPcm16(value: Float): Short {
        if (value.isNaN()) return 0
        val v = value.coerceIn(-1f, 1f)
        val scaled = if (v >= 0f) (v * 32767f).roundToInt() else (v * 32768f).roundToInt()
        return scaled.coerceIn(-32768, 32767).toShort()
    }
}

/**
 * 听写结果的**文本累积器**（`dwa=wpgs` 的动态修正）。
 *
 * 服务端不会一次给整段文本：每个回包是一段（`apd` 追加 / `rpl` 替换 `rg` 区间），
 * `ls=true` 才是最后一片。对外 [text] 恒为**整段累积文本** —— 调用方（IME）整体覆盖显示，
 * 自己再拼一遍就会把 `rpl` 的修正拼成重复。
 *
 * **实测形态**（一次真实会话，且 `pgs` / `rg` / `rst` 都在 `text` 解码后的内层 JSON 里，
 * `payload.result.pgs` 恒为 null）：
 * ```
 * sn=1  pgs=apd            ws=[1 个词]          ← 首字
 * sn=2  pgs=rpl rg=[1,1]   ws=[本句至今全部词]
 * sn=3  pgs=rpl rg=[1,2]   ws=[本句至今全部词]
 * …                                                每次 rpl 都是「rg=[1, sn-1] + 本句全文」
 * sn=15 rst=rlt pgs=rpl rg=[1,14] ws=[1 个词 = 整句]  ← 整句被归档成一个条目
 * sn=16 rst=rlt pgs=apd ls=true   ws=[{"w":""}]      ← 末帧文本是空串，**不许**把整句清空
 * ```
 * 所以 `rg` 必须按 **1 基闭区间**整体替换（`rg=[1,2]` 要清掉第 1、2 片），空文本一律不改动。
 *
 * 断句（`vgap`）：第二句的 `sn` 与它自己的 `rg` **重新从 1 开始**。若两句的片段共用一个下标空间，
 * 第二句的 `rg=[1,1]` 会把第一句整句替换掉 —— 故「`sn` 回到 1」时把当前句定稿进 [sentences]，
 * `rg` 只在当前句内定位。（wpgs 同一句内 `sn` 严格递增：修正旧片走的是**新 `sn`** + 指向旧的 `rg`。）
 *
 * ⚠ 当前请求参数（`domain=spark_asr_v2, eos=60000, dwa=wpgs`，**没有 `vgap`**）下服务端不会自己切句：
 * 7.1 秒、含 1.5 秒停顿的两句话实测**只有一句**（`sn` 一路增到末尾，从不回到 1）。所以断句分支
 * 在现参数下不触发，属防御；一旦以后把 `vgap` 加进首帧，它就是唯一的正确性依据 ——
 * 两句的 `rg` 各从 1 起算这一点不会因为去掉它而变。
 */
class XfTextAccumulator {

    /** 已定稿的**前几句**（`vgap` 断句后不再参与 `rg` 替换） */
    private val sentences = StringBuilder()

    /** 当前句的片段，下标 0 对应服务端的 1 号片段（`rg` 是 1 基） */
    private val pieces = mutableListOf<String>()

    /** 整段最终文本（非 wpgs 形态）；非空时优先于 [sentences] + [pieces] */
    private var finalText: String? = null

    /** 是否已吃过任何一条回包（首帧的 `sn=1` 不算断句） */
    private var sawFrame = false

    /** 整段累积文本（永远是「从头到现在」的全部内容） */
    val text: String
        get() = finalText ?: (sentences.toString() + pieces.joinToString(""))

    /**
     * 吃一条回包，返回累积后的整段文本。
     *
     * 没有 `payload.result`、或文本段为空时**一律不改动**：末帧就是 `ws=[{"w":""}]`，
     * 它与「把整句清空」在报文上区分不开，宁可留旧值（清空的代价是用户刚说的话整段消失）。
     */
    fun accept(resp: XfIatProtocol.XfResponse): String {
        val result = resp.result ?: return text
        if (result.sn == 1 && sawFrame) startNewSentence()
        sawFrame = true
        if (result.text.isEmpty()) return text

        when (result.pgs) {
            "apd" -> {
                pieces.add(result.text)
                finalText = null
            }

            "rpl" -> {
                replaceRange(result)
                finalText = null
            }

            else -> {
                if (!result.ls) {
                    // 没有 pgs 又未收尾：当普通增量处理（个别版本会这么回）
                    pieces.add(result.text)
                } else if (result.text.startsWith(text)) {
                    // 这一片包含前面全部内容 ⇒ 非 wpgs 形态的「整段最终文本」（先前为空也算）
                    finalText = result.text
                } else {
                    // wpgs 的最后一片：只补末尾，不能覆盖前文
                    pieces.add(result.text)
                }
            }
        }
        return text
    }

    /** 把当前句定稿进 [sentences]：断句后 `rg` 换一套下标空间，上一句不再被替换到 */
    private fun startNewSentence() {
        finalText?.let { sentences.append(it) }
        finalText = null
        if (pieces.isEmpty()) return
        pieces.forEach { sentences.append(it) }
        pieces.clear()
    }

    /**
     * `rpl`：把 `rg=[start, end]`（1 基、含端点）这几片整体换成本次文本。
     *
     * 区间越界一律夹到已有片段范围：服务端在丢包重传时给过 `rg` 上界大于本地下标的包，
     * 越界写会直接抛 `IndexOutOfBoundsException` 打到 OkHttp 的 IO 线程上。
     */
    private fun replaceRange(result: XfIatProtocol.XfResult) {
        val start = result.rg.getOrNull(0) ?: 1
        val end = result.rg.getOrNull(1) ?: start
        val from = (start - 1).coerceIn(0, pieces.size)
        val to = end.coerceIn(from, pieces.size) // 1 基闭区间的末尾 = 0 基的排他末尾
        repeat(to - from) { pieces.removeAt(from) }
        pieces.add(from, result.text)
    }
}
