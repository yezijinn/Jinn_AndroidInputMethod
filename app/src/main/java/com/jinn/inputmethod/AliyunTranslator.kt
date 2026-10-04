package com.jinn.inputmethod

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云机器翻译（通用版 HTTP 接口），直接用 OkHttp 发 HTTPS 请求，不引阿里云 SDK。
 *
 * 接口（官方「机器翻译调用方式」）：
 * ```
 * POST https://mt.cn-hangzhou.aliyuncs.com/api/translate/web/general
 * {"FormatType":"text","SourceLanguage":"auto","TargetLanguage":"en","SourceText":"…"}
 * ```
 * `SourceLanguage=auto` 即自动检测源语言（与其它三家口径一致）。
 *
 * 认证是阿里云 **ROA 风格签名**（官方「签名机制」逐字实现，顺序与换行都不能改）：
 * ```
 * stringToSign = HTTP-Verb \n Accept \n Content-MD5 \n Content-Type \n Date \n
 *                x-acs-signature-method:HMAC-SHA1 \n
 *                x-acs-signature-nonce:<nonce> \n
 *                x-acs-version:2019-01-02 \n
 *                <URI>
 * Signature   = Base64(HMAC-SHA1(AccessKeySecret, stringToSign))
 * Authorization = "acs " + AccessKeyId + ":" + Signature
 * ```
 * `Content-MD5` = Base64(MD5(body 原始字节))，`Date` = GMT 时间，`nonce` = 每次请求唯一随机数。
 * `Host` 与 `Content-Length` 由 OkHttp 按 URL / body 自动带上，签名串里不含它们。
 */
internal class AliyunTranslator(
    private val accessKeyId: String,
    private val accessKeySecret: String,
    /** 时钟：单测注入固定时刻以断言 Date 头与签名 */
    private val now: () -> Date = { Date() },
    /** nonce 源：单测注入定值；生产为每次请求唯一的随机 UUID */
    private val nonceSource: () -> String = { UUID.randomUUID().toString() },
) : TranslationProvider {

    /** 构造阿里云机器翻译的 HTTP 请求：表单字段 + 基于实际发送字节的 Content-MD5 签名（Scene 为实测必填项） */
    override fun buildRequest(text: String, target: TranslationLanguage): Request {
        val body = JSONObject()
            .put("FormatType", FORMAT_TYPE)
            .put("SourceLanguage", SOURCE_AUTO)
            .put("TargetLanguage", target.aliyunCode)
            .put("SourceText", text)
            // ⚠ `Scene` 是实测必填：缺它返回 `{"Message":"参数出错","Code":"10004"}`（HTTP 200）
            .put("Scene", SCENE_GENERAL)
            .toString()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        // Content-MD5 必须与**实际发送的字节**一致：同一份 bodyBytes 既进请求体也进签名
        val contentMd5 = base64Md5(bodyBytes)
        val date = gmtDate(now())
        val nonce = nonceSource()
        val signature = sign(
            accessKeySecret = accessKeySecret,
            httpVerb = HTTP_VERB,
            accept = ACCEPT,
            contentMd5 = contentMd5,
            // ⚠ 参与签名的 Content-Type 取自 **body 实际用的那个 MediaType**（单一真相，2026-10-02 修复 L-486）。
            // 实发头由 OkHttp 的 BridgeInterceptor 用 `body.contentType().toString()` 决定，签名串若另用
            // 一个常量，两者只是**恰好同源**（同值不同源）；将来只改一处就会出现 `SignatureDoesNotMatch`，
            // 而它走 404 通道被归成 AUTH —— 用户反复核对正确的 AK/SK 也无效。
            contentType = JSON_MEDIA_TYPE.toString(),
            date = date,
            nonce = nonce,
            path = ENDPOINT_URL.encodedPath,
        )
        return Request.Builder()
            .url(ENDPOINT_URL)
            .post(bodyBytes.toRequestBody(JSON_MEDIA_TYPE))
            // ⚠ 这里**不再**设 `Content-Type` 头（2026-10-02 修复 L-486）：实发头由 OkHttp 的
            // BridgeInterceptor 用 `body.contentType().toString()` 决定，显式设的同名头会被它覆盖 ⇒
            // 那一行是死代码，还会与 `sign()` 上方的注释互相矛盾（误导后人以为「必须显式设头」）。
            // 现在签名串取自同一个 `JSON_MEDIA_TYPE.toString()`（单一真相）：要改 Content-Type
            // 只改 `CONTENT_TYPE` 一处，两边再没有漂移的机会。
            .header("Accept", ACCEPT)
            .header("Content-MD5", contentMd5)
            .header("Date", date)
            .header("x-acs-signature-method", SIGNATURE_METHOD)
            .header("x-acs-signature-nonce", nonce)
            .header("x-acs-version", API_VERSION)
            .header("Authorization", "acs $accessKeyId:$signature")
            .build()
    }

    /** 解析响应：先判业务错误码（如 10004 缺 Scene、404 认证失败）再退判 HTTP 状态，避免把认证/参数错误误报成「翻译为空」 */
    override fun parseResponse(code: Int, body: String?): TranslationOutcome {
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return TranslationOutcome.Fail(httpErrorOf(code))
        // ⚠ 业务错误码必须先判，再看 HTTP 状态：
        //  · 认证失败时阿里云的 HTTP 状态是 **404**（`InvalidAccessKeyId.NotFound` / `SignatureDoesNotMatch`）；
        //  · 参数出错时是 **HTTP 200 + `{"Code":"10004"}`**（实测：缺 Scene）。
        // 只看状态会把这两种情况分别显示成「服务异常」与「翻译结果为空」，用户都无从下手。
        // 成功码是 "200"（大写 Code 与老写法 code 都认，数字 200 经 optString 也是 "200"）。
        // 判存在性要**排除 JSON null**（2026-09-30 审查）：`optString` 对 `"code":null` 返回
        // 字面量 `"null"`、对 `"code":0` 返回 `"0"` —— 两者都会被当成业务错误码（归 SERVER），
        // 而正文其实已经译好躺在 `Data.Translated` 里（`code:0` 是这类 OpenAPI 常见的成功约定）。
        // 仍用 `opt(...).toString()` 而不是 jsonText：数字码（10004）必须保留，jsonText 只认字符串会漏判。
        val errorCode = listOf("Code", "code", "errorCode")
            .mapNotNull { key -> json.opt(key)?.takeIf { it != JSONObject.NULL }?.toString() }
            .firstOrNull { it.isNotEmpty() && it != "200" && it != "0" }
        if (errorCode != null) return TranslationOutcome.Fail(aliyunErrorOf(errorCode))
        if (code !in 200..299) return TranslationOutcome.Fail(httpErrorOf(code))
        if (!json.optBoolean("success", true)) return TranslationOutcome.Fail(TranslationError.SERVER)
        // 译文取值：实测成功响应是 `{"Code":"200","Data":{"Translated":"…","WordCount":"11"}}`；
        // 其余写法（data.translateText / 平铺）留作容错，改版时不至于翻车。
        val data = json.optJSONObject("Data") ?: json.optJSONObject("data")
        // 取值统一走 jsonText：显式 JSON null / 类型不符都返回 null —— `optString` 会把
        // JSON null 变成字面量 "null" 当译文提交进用户输入框（2026-09-30 审查发现，六家统一口径）
        val text = firstNonBlank(
            jsonText(data, "Translated"),
            jsonText(data, "translateText"),
            jsonText(data, "translated"),
            jsonText(data, "translation"),
            jsonText(json, "Translated"),
            jsonText(json, "translateText"),
            jsonText(json, "translated"),
        )
        return if (text.isNullOrBlank()) {
            TranslationOutcome.Fail(TranslationError.EMPTY)
        } else {
            TranslationOutcome.Ok(text.trim())
        }
    }

    companion object {
        const val ENDPOINT = "https://mt.cn-hangzhou.aliyuncs.com/api/translate/web/general"
        const val API_VERSION = "2019-01-02"
        const val SIGNATURE_METHOD = "HMAC-SHA1"
        const val ACCEPT = "application/json"
        /**
         * 参与签名的 Content-Type —— 必须是 **OkHttp 实际发出的那个形态**。
         *
         * ⚠ 显式 `.header("Content-Type", …)` 会被 OkHttp 的 BridgeInterceptor **覆盖**：
         * 它执行时用 `body.contentType().toString()` 重设该头，而 `MediaType.toString()` 把参数
         * 归一成 `; charset=utf-8`（**带空格**）。所以签名串必须用带空格的写法，否则
         * 「签名的串 ≠ 服务端按收到的头重建的串」—— 差一个空格即 `SignatureDoesNotMatch`，
         * 而它走 404/Code 通道被归成 AUTH（2026-10-02 修复 L-354：此前显式设头无效、
         * 签名串与实发头不同源，只是服务端做了归一化才没炸）。
         */
        const val CONTENT_TYPE = "application/json; charset=utf-8"
        const val FORMAT_TYPE = "text"

        /** 通用版场景（专业版用 `title` / `ecommerce` 一类，见官方「机器翻译调用方式」） */
        const val SCENE_GENERAL = "general"

        /** 自动检测源语言（官方：`SourceLanguage=auto`） */
        const val SOURCE_AUTO = "auto"

        private const val HTTP_VERB = "POST"

        /** 常量 URL 必然可解析；解析失败属于字面量写错，构建期就该发现 */
        private val ENDPOINT_URL: HttpUrl = ENDPOINT.toHttpUrlOrNull()!!

        private val JSON_MEDIA_TYPE = CONTENT_TYPE.toMediaType()

        /** body 的 MD5 → Base64（`Content-MD5` 头；用 JDK 的 Base64，minSdk 26 起可用） */
        internal fun base64Md5(body: ByteArray): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body))

        /**
         * `Date` 头的 GMT 形态（`Wed, 26 Aug 2015 17:01:00 GMT`）。
         *
         * 固定 `Locale.US` 与 GMT 时区：系统语言/时区变化不能让签名串与头不一致。
         */
        internal fun gmtDate(date: Date): String =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .apply { timeZone = SimpleTimeZone(0, "GMT") }
                .format(date)

        /** HMAC-SHA1 → Base64（签名主体） */
        internal fun hmacSha1Base64(secret: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
        }

        /** 待签名串：顺序与换行由官方「签名机制」固定，任何一处不同都会被服务端判签名不匹配 */
        internal fun stringToSign(
            httpVerb: String,
            accept: String,
            contentMd5: String,
            contentType: String,
            date: String,
            nonce: String,
            path: String,
        ): String = httpVerb + "\n" + accept + "\n" + contentMd5 + "\n" + contentType + "\n" +
            date + "\n" +
            "x-acs-signature-method:$SIGNATURE_METHOD\n" +
            "x-acs-signature-nonce:$nonce\n" +
            "x-acs-version:$API_VERSION\n" +
            path

        /** 完整签名（`Authorization` 头里 `acs AccessKeyId:` 之后的那一段） */
        internal fun sign(
            accessKeySecret: String,
            httpVerb: String,
            accept: String,
            contentMd5: String,
            contentType: String,
            date: String,
            nonce: String,
            path: String,
        ): String = hmacSha1Base64(
            accessKeySecret,
            stringToSign(httpVerb, accept, contentMd5, contentType, date, nonce, path),
        )

        internal fun httpErrorOf(code: Int): TranslationError = when (code) {
            400, 413 -> TranslationError.PARAM      // 413 = 请求体过大（2026-10-01 审查 L-273）
            401, 403 -> TranslationError.AUTH
            // 实测：阿里云网关把「AK 不存在 / 签名不匹配」报成 404（带 Code 字段）；
            // 走到这里说明响应体没给出错误码，仍按认证失败处理（该接口路径是常量，不会是「路径不存在」）
            404 -> TranslationError.AUTH
            408 -> TranslationError.TIMEOUT
            429 -> TranslationError.QUOTA
            in 500..599 -> TranslationError.SERVER
            else -> TranslationError.SERVER
        }

        /**
         * 阿里云错误码 → 归一分类（按前缀匹配，实测见 `InvalidAccessKeyId.NotFound`）。
         *
         * `InvalidTimeStamp.Expired` 归 AUTH：它是设备时钟偏差导致的签名被拒，
         * 提示「检查凭据」同样有指向性（用户会顺带发现时间不对），归服务端异常则无从下手。
         */
        internal fun aliyunErrorOf(code: String): TranslationError = when {
            // 通用版数字错误码（实测 10004 = 参数出错：缺 Scene / 参数不合法时 HTTP 200 返回它）
            code == "10004" -> TranslationError.PARAM
            // 超出单次字符上限 —— 设置页的说明里点名的就是它（「超出报错 10008」）。落进 else 会让
            // 「原文过长」显示成「服务异常」，用户按提示重试永远失败，而说明与行为互相矛盾
            //（2026-10-01 修复 L-328）。
            code == "10008" -> TranslationError.PARAM
            code.startsWith("InvalidAccessKeyId") -> TranslationError.AUTH
            code.startsWith("SignatureDoesNotMatch") -> TranslationError.AUTH
            code.startsWith("InvalidTimeStamp") -> TranslationError.AUTH
            code.startsWith("Forbidden") -> TranslationError.AUTH
            code.startsWith("Throttling") -> TranslationError.QUOTA
            code.startsWith("Quota") -> TranslationError.QUOTA
            code.startsWith("InvalidParameter") -> TranslationError.PARAM
            code.startsWith("MissingParameter") -> TranslationError.PARAM
            else -> TranslationError.SERVER
        }

        private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }
    }
}
