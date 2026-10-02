package com.jinn.inputmethod

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 百度翻译开放平台（通用文本翻译 API），直接用 OkHttp 发 HTTPS 请求，不引百度 SDK。
 *
 * 请求形态（对齐官方文档）：
 * ```
 * GET https://fanyi-api.baidu.com/api/trans/vip/translate
 *     ?q=<原文>&from=auto&to=<代码>&appid=<AppID>&salt=<随机数>&sign=<MD5>
 * ```
 * 签名规则：`sign = MD5(appid + q + salt + secretKey)`，**小写十六进制、UTF-8**；
 * `q` 参与签名的是**未编码原文**（编码由 OkHttp 的 `addQueryParameter` 负责）——
 * 拿编码后的串去签名是这类接入最常见的错。
 *
 * `from=auto` = 自动检测源语言（与 Azure 侧对齐，用户不需要填源语言）。
 *
 * ⚠ 百度把**业务错误码放在 HTTP 200 的响应体里**（`error_code`），必须先看它再看译文，
 * 否则「签名错误」会被当成「译文为空」。
 */
internal class BaiduTranslator(
    private val appId: String,
    private val secretKey: String,
    /** salt 源：生产用随机数；单测注入定值以断言签名（签名是纯函数） */
    private val saltSource: () -> String = { Random.nextLong().toString() },
) : TranslationProvider {

    /** 通用版按输入行对齐返回（`trans_result` 靠顺序对应原文行）⇒ 参与出口的「少给行」对拍（L-483） */
    override val alignsPerLine: Boolean = true


    override fun buildRequest(text: String, target: TranslationLanguage): Request {
        val salt = saltSource()
        // 签名用**未编码原文**（官方要求），发送用**自定义全量百分号编码**：
        // `addQueryParameter` 不编码 `+`（OkHttp 的保留集里没有它），而多数网关按
        // form-urlencoded 口径把 query 里的 `+` 解回空格 ⇒ 服务端重建的 q 与签名用的 q
        // 不一致 ⇒ `error_code=54001`（被归成 AUTH，用户怎么检查凭据都没问题）。
        // 全量编码后 `+`→`%2B`、`%`→`%25`、空格→`%20`，服务端解出的 q 必等于原文
        // （2026-09-30 审查发现）。
        val sign = md5Hex(appId + text + salt + secretKey)
        val url = ENDPOINT_URL.newBuilder()
            .addEncodedQueryParameter("q", percentEncode(text))
            .addQueryParameter("from", SOURCE_LANGUAGE_AUTO)
            .addQueryParameter("to", target.baiduCode)
            .addQueryParameter("appid", appId)
            .addQueryParameter("salt", salt)
            .addQueryParameter("sign", sign)
            .build()
        return Request.Builder().url(url).get().build()
    }

    override fun parseResponse(code: Int, body: String?): TranslationOutcome {
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        // 业务错误码优先，且**非 2xx 也先取**：百度把频率/额度类错误码（54003/54004/54005）也放在
        // 响应体里，网关用 403/5xx 承载它时若先看状态，就会被归成 AUTH/SERVER、提示「检查凭据」，
        // 而真实原因是限流/欠费（2026-09-30 审查发现）。
        // 走 jsonCode 而不是 jsonText：错误码可能是 JSON 数字（2026-10-01 审查 L-226）
        val errorCode = jsonCode(json, "error_code")
        // `"0"` 是百度的**成功**码：数字兼容之后 `"error_code":0` 也会被取到，不豁免就会把一次
        // 正常翻译判成服务端错误（2026-10-01 复审 L-233）
        if (!errorCode.isNullOrEmpty() && errorCode != "0") {
            return TranslationOutcome.Fail(baiduErrorOf(errorCode))
        }
        if (json == null || code !in 200..299) return TranslationOutcome.Fail(httpErrorOf(code))
        val text = transResultText(json)
        return if (text.isNullOrBlank()) {
            TranslationOutcome.Fail(TranslationError.EMPTY)
        } else {
            TranslationOutcome.Ok(text)
        }
    }

    companion object {
        /**
         * 取 `trans_result` 里的译文。
         *
         * 百度的这个字段是**按输入行对齐的数组** —— 多行原文返回多个元素，只读 `[0]` 会丢掉
         * 第 2..N 行的译文，而界面仍报成功（2026-10-01 审查 L-268）。这里按顺序用 `\n` 连起来；
         * 服务端不拆行时数组长度恒为 1，行为与原来一致。
         *
         * 取值仍走 [jsonText]：JSON null / 类型不符都返回 null，不会变成字面量 "null" 当译文。
         */
        internal fun transResultText(json: JSONObject?): String? {
            val arr = json?.optJSONArray("trans_result") ?: return null
            val parts = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                // ⚠ 空 dst 必须**占位保留**（原文该行为空行、或服务端对某行不给译文）：
                // `trans_result` 是**按输入行对齐**的数组，丢掉一个元素会让其后所有行上移一行，
                // 译文与原文逐行错位，而界面仍报成功（2026-10-02 修复 L-310；百度通用与大模型两家共用本函数）。
                parts.add(jsonText(arr.optJSONObject(i), "dst").orEmpty())
            }
            // 用 ifBlank 而不是 ifEmpty：全是空行时仍要按「无译文」处理（EMPTY），不能把一堆换行当结果
            return parts.joinToString("\n").ifBlank { null }
        }

        const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"
        const val SOURCE_LANGUAGE_AUTO = "auto"

        /** 常量 URL 必然可解析；解析失败属于字面量写错，构建期就该发现 */
        private val ENDPOINT_URL: HttpUrl = ENDPOINT.toHttpUrlOrNull()!!

        /**
         * 签名用的 MD5（小写十六进制）。
         *
         * `"%02x".format(byte)` 对负字节按无符号输出（Java Formatter 对 byte 先加 2^8），
         * 与项目里 SHA-256 的 hex 写法同口径（见 OptionalDicts）。
         */
        internal fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
        }

        /**
         * 全量百分号编码（unreserved 之外的 UTF-8 字节一律 `%XX`，大写十六进制）。
         *
         * 与 OkHttp 的编码集刻意不同，唯一要求是「服务端解码后 == 原文」：OkHttp 保留 `+`，
         * 而网关常按 form-urlencoded 把它解成空格，签名就对不上了（见 [buildRequest]）。
         */
        internal fun percentEncode(raw: String): String = buildString {
            for (b in raw.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                // A-Z a-z 0-9 - _ . ~ （RFC 3986 unreserved）
                val unreserved = c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 ||
                    c == 0x2D || c == 0x5F || c == 0x2E || c == 0x7E
                if (unreserved) {
                    append(c.toChar())
                } else {
                    append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"

        /** HTTP 状态码 → 归一错误分类（业务错误码走 [baiduErrorOf]） */
        internal fun httpErrorOf(code: Int): TranslationError = when (code) {
            400, 413, 414 -> TranslationError.PARAM      // 413 请求体过大；414 URL 过长 —— 百度通用版是 **GET 家**（原文在 query 里，
                  // 非 ASCII 一字节膨胀成 3 个字符），真正撞上的会是 414。原先只认 413
                  //（对 GET 不可达）会把「原文过长」显示成「服务异常」，按提示重试永远失败
                  //（2026-10-01 修复 L-308）
            401, 403 -> TranslationError.AUTH
            408 -> TranslationError.TIMEOUT
            429 -> TranslationError.QUOTA
            in 500..599 -> TranslationError.SERVER
            else -> TranslationError.SERVER
        }

        /**
         * 百度业务错误码 → 归一错误分类（官方错误码表）。
         *
         * 未在表内的（含自定义错误码、百分比进度串）一律归 SERVER：宁可提示「服务异常」，
         * 也不要把原始 error_msg 直接甩给用户。
         */
        internal fun baiduErrorOf(code: String): TranslationError = when (code) {
            "52001" -> TranslationError.TIMEOUT          // 请求超时
            "52002" -> TranslationError.SERVER           // 系统错误
            "52003" -> TranslationError.AUTH             // 未授权用户（appid 不存在或未授权）
            "54000" -> TranslationError.PARAM            // 必填参数为空
            "54001" -> TranslationError.AUTH             // 签名错误
            "54003" -> TranslationError.QUOTA            // 访问频率受限
            "54004" -> TranslationError.QUOTA            // 账户余额不足
            "54005" -> TranslationError.QUOTA            // 长 query 请求频繁
            "58000" -> TranslationError.AUTH             // 客户端 IP 非法
            "58001" -> TranslationError.PARAM            // 译文语言方向不支持
            "58002" -> TranslationError.SERVER           // 服务当前已关闭
            // 90107 = 认证未通过或未生效（2026-10-01 审查 L-275）：账号实名 / 认证没走完时百度回
            // 这个码，落进 else 会让用户看到「服务异常，请稍后重试」—— 重试与换 Key 都无效。
            "90107" -> TranslationError.AUTH
            else -> TranslationError.SERVER
        }
    }
}
