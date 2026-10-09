package com.jinn.inputmethod

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云机器翻译的纯函数守卫：ROA 风格签名、Content-MD5、请求头与响应分类。
 *
 * 这里的每一条都对着官方「签名机制」的逐字规则：签名串少一个换行、时间不是 GMT、
 * Content-MD5 与 body 不一致，服务端一律回 `SignatureDoesNotMatch` —— 真机上只表现为
 * 「认证失败」，看不出是哪一步错，所以必须在 JVM 层逐字段钉住。
 */
class AliyunTranslatorTest {

    /** 2015-08-26 17:01:00 GMT（官方示例里的时刻，用来对齐 GMT 文本形态） */
    private val fixedDate = Date(1440608460000L)

    private val translator = AliyunTranslator(
        accessKeyId = AK_ID,
        accessKeySecret = AK_SECRET,
        now = { fixedDate },
        nonceSource = { NONCE },
    )

    private fun bodyOf(request: Request): ByteArray {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readByteArray()
    }

    /** 独立复算：Base64(MD5(bytes))，与被测实现的写法互为交叉验证 */
    private fun md5Base64Reference(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))

    /** 独立复算：Base64(HMAC-SHA1(secret, data)) */
    private fun hmacSha1Reference(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    // ── 签名原件 ────────────────────────────────────────────

    @Test
    fun `GMT 时间固定为 RFC1123 形态（不随系统语言与时区变化）`() {
        assertEquals("Wed, 26 Aug 2015 17:01:00 GMT", AliyunTranslator.gmtDate(fixedDate))
    }

    @Test
    fun `HMAC-SHA1 与标准测试向量一致`() {
        // RFC 2202 的经典向量，用来钉住算法本身（用 Base64 输出）
        assertEquals(
            "3nybhbi3iqa8ino29wqQcBydtNk=",
            AliyunTranslator.hmacSha1Base64("key", "The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun `待签名串的字段顺序与换行逐个固定`() {
        val expected = "POST\n" +
            "application/json\n" +
            "MD5VALUE\n" +
            // 用常量：它的具体形态（带不带空格）由 OkHttp 实发头决定，见 L-354 的守卫
            AliyunTranslator.CONTENT_TYPE + "\n" +
            "Wed, 26 Aug 2015 17:01:00 GMT\n" +
            "x-acs-signature-method:HMAC-SHA1\n" +
            "x-acs-signature-nonce:$NONCE\n" +
            "x-acs-version:2019-01-02\n" +
            "/api/translate/web/general"
        assertEquals(
            expected,
            AliyunTranslator.stringToSign(
                httpVerb = "POST",
                accept = "application/json",
                contentMd5 = "MD5VALUE",
                // 引用常量而非硬编码（2026-10-02）：签名串必须与 OkHttp 实发头同源（L-354）
                contentType = AliyunTranslator.CONTENT_TYPE,
                date = AliyunTranslator.gmtDate(fixedDate),
                nonce = NONCE,
                path = "/api/translate/web/general",
            ),
        )
    }

    // ── 请求形态 ────────────────────────────────────────────

    @Test
    fun `请求形态：POST + HTTPS + 全部签名头 + JSON 体`() {
        val request = translator.buildRequest("你好", TranslationLanguage.ENGLISH)
        val bodyBytes = bodyOf(request)

        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("mt.cn-hangzhou.aliyuncs.com", request.url.host)
        assertEquals("/api/translate/web/general", request.url.encodedPath)
        assertEquals("application/json", request.header("Accept"))
        // 不再显式设 Content-Type 头（2026-10-02 修复 L-486）：实发头由 OkHttp 的 BridgeInterceptor
        // 用 `body.contentType().toString()` 决定，显式设的同名头会被它覆盖 ⇒ 那一行是死代码，
        // 留着还会与签名侧的注释互相矛盾（误导后人以为「必须显式设头」）。
        assertNull(request.header("Content-Type"))
        assertEquals(
            "签名串必须等于实发头（显式头会被 body 的 MediaType 覆盖）",
            AliyunTranslator.CONTENT_TYPE,
            request.body?.contentType().toString(),
        )
        assertEquals("HMAC-SHA1", request.header("x-acs-signature-method"))
        assertEquals("2019-01-02", request.header("x-acs-version"))
        assertEquals(NONCE, request.header("x-acs-signature-nonce"))
        assertEquals(AliyunTranslator.gmtDate(fixedDate), request.header("Date"))

        // Content-MD5 必须与实际发送的字节一致（不一致服务端直接拒）
        assertEquals(md5Base64Reference(bodyBytes), request.header("Content-MD5"))

        // Authorization = "acs " + AccessKeyId + ":" + Base64(HMAC-SHA1(secret, stringToSign))
        val expectedSign = hmacSha1Reference(
            AK_SECRET,
            AliyunTranslator.stringToSign(
                httpVerb = "POST",
                accept = "application/json",
                contentMd5 = md5Base64Reference(bodyBytes),
                contentType = AliyunTranslator.CONTENT_TYPE,
                date = AliyunTranslator.gmtDate(fixedDate),
                nonce = NONCE,
                path = "/api/translate/web/general",
            ),
        )
        assertEquals("acs $AK_ID:$expectedSign", request.header("Authorization"))

        // 请求体：官方字段名，源语言交给自动检测
        val json = JSONObject(String(bodyBytes, Charsets.UTF_8))
        assertEquals("text", json.getString("FormatType"))
        assertEquals("auto", json.getString("SourceLanguage"))
        assertEquals("en", json.getString("TargetLanguage"))
        assertEquals("你好", json.getString("SourceText"))
        // Scene 必填：实测缺它返回 {"Message":"参数出错","Code":"10004"}
        assertEquals(AliyunTranslator.SCENE_GENERAL, json.getString("Scene"))
    }

    @Test
    fun `目标语言代码只从应用层映射取`() {
        fun to(language: TranslationLanguage): String {
            val request = translator.buildRequest("x", language)
            return JSONObject(String(bodyOf(request), Charsets.UTF_8)).getString("TargetLanguage")
        }

        assertEquals("zh", to(TranslationLanguage.CHINESE))
        assertEquals("en", to(TranslationLanguage.ENGLISH))
        assertEquals("ja", to(TranslationLanguage.JAPANESE))
        assertEquals("ko", to(TranslationLanguage.KOREAN))
    }

    @Test
    fun `默认 nonce 每次请求都不同（防重放）`() {
        val random = AliyunTranslator(AK_ID, AK_SECRET)
        val first = random.buildRequest("x", TranslationLanguage.ENGLISH).header("x-acs-signature-nonce")
        val second = random.buildRequest("x", TranslationLanguage.ENGLISH).header("x-acs-signature-nonce")
        assertNotEquals(first, second)
    }

    // ── 响应 ────────────────────────────────────────────────

    @Test
    fun `200 正常解析译文（实测结构：Code=200 + Data_Translated）`() {
        // 2026-09-30 线上实测（真实 AK）：{"RequestId":"…","Data":{"WordCount":"11","Translated":"你好世界"},"Code":"200"}
        assertEquals(
            TranslationOutcome.Ok("你好世界"),
            translator.parseResponse(
                200,
                """{"RequestId":"01A0F1A6-E24D-5C6B-B64D-43F9287D25F9",""" +
                    """"Data":{"WordCount":"11","Translated":"你好世界"},"Code":"200"}""",
            ),
        )
        // 其余写法留作容错（改版 / 老接口）
        assertEquals(
            TranslationOutcome.Ok("Hello"),
            translator.parseResponse(200, """{"code":200,"success":true,"data":{"translateText":"Hello"}}"""),
        )
        assertEquals(
            TranslationOutcome.Ok("Hello"),
            translator.parseResponse(200, """{"translateText":"Hello"}"""),
        )
        assertEquals(
            TranslationOutcome.Ok("Hello"),
            translator.parseResponse(200, """{"data":{"translated":"Hello"}}"""),
        )
    }

    @Test
    fun `成功响应里的 code 0 或 JSON null 不算错误码`() {
        // `code:0` 是这类 OpenAPI 常见的成功约定，`code:null` 是显式空值 —— 两者都不能把
        // 已经译好的正文丢掉（2026-09-30 审查：optString 会给出 "0" / "null"）
        assertEquals(
            TranslationOutcome.Ok("你好"),
            translator.parseResponse(200, """{"code":0,"Data":{"Translated":"你好"}}"""),
        )
        assertEquals(
            TranslationOutcome.Ok("你好"),
            translator.parseResponse(200, """{"Code":"200","code":null,"Data":{"Translated":"你好"}}"""),
        )
    }

    @Test
    fun `实测的参数出错（HTTP 200 + Code 10004）判为参数问题`() {
        // 2026-09-30 实测：body 缺 Scene 时返回 HTTP 200 + {"Message":"参数出错","Code":"10004"}
        assertEquals(
            TranslationOutcome.Fail(TranslationError.PARAM),
            translator.parseResponse(200, """{"Message":"参数出错","Code":"10004"}"""),
        )
    }

    @Test
    fun `业务错误：errorCode 优先于取译文`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            translator.parseResponse(200, """{"errorCode":"SignatureDoesNotMatch","errorMsg":"签名不匹配"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            translator.parseResponse(200, """{"errorCode":"InvalidAccessKeyId.NotFound"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            translator.parseResponse(200, """{"errorCode":"Throttling.User"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.PARAM),
            translator.parseResponse(200, """{"errorCode":"InvalidParameter"}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, """{"errorCode":"SomethingElse"}"""),
        )
    }

    @Test
    fun `code 非 200 或 success=false 归服务端异常`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, """{"code":500,"data":{"translateText":"x"}}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, """{"success":false,"data":{"translateText":"x"}}"""),
        )
    }

    @Test
    fun `空译文与非法 JSON`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"code":200,"data":{}}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, "<html>502</html>"),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, null),
        )
    }

    @Test
    fun `HTTP 错误码分类（404 是阿里云的认证失败通道）`() {
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(401, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(403, null))
        // 实测：AK 不存在 / 签名不匹配时阿里云回 **404**（body 里带 Code），无 body 时同样按认证失败处理
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(404, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.QUOTA), translator.parseResponse(429, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), translator.parseResponse(400, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.TIMEOUT), translator.parseResponse(408, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), translator.parseResponse(503, null))
    }

    @Test
    fun `真实认证失败响应（逐字取自线上）判为认证失败而不是服务端异常`() {
        // 2026-09-30 用测试 AK 实测：HTTP 404 + 网关风格 body（`Code` 大写）
        val notFound = """{"RequestId":"01A0F19D-9870-5E78-BF8D-F5CE8D550A2B",""" +
            """"Message":"Specified access key is not found.",""" +
            """"Recommend":"https://api.aliyun.com/troubleshoot?q=InvalidAccessKeyId.NotFound",""" +
            """"HostId":"mt.cn-hangzhou.aliyuncs.com","Code":"InvalidAccessKeyId.NotFound"}"""
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(404, notFound))

        val expired = """{"Code":"InvalidTimeStamp.Expired",""" +
            """"Message":"Specified time stamp or date value is expired."}"""
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(400, expired))

        // 缺 Date 这类协议级错误是本实现的 bug（不该出现），归服务端异常以便暴露
        val missingDate = """{"Code":"MissingDate","Message":"Date is mandatory for this action."}"""
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), translator.parseResponse(400, missingDate))
    }

    /**
     * 业务码表按官方文档分类（`BUG.md` L-1125）。
     *
     * 表取自官方「机器翻译通用版调用指南」错误码一节（2026-10-09 逐条核对）。此前的实现只认
     * 10004 / 10008 两个数字码，其余全部落 `else -> SERVER` ⇒ 提示「稍后重试」，而参数 / 语言方向 /
     * 额度 / 欠费这几类重试必然再失败、每次都真计费。这条把每一档都钉住，防止再退回「只收两个码」。
     */
    @Test
    fun `业务码表按官方文档分类`() {
        val expected = mapOf(
            "10001" to TranslationError.TIMEOUT,   // 请求超时
            "10002" to TranslationError.SERVER,    // 系统错误
            "10003" to TranslationError.PARAM,     // 原文解码失败
            "10004" to TranslationError.PARAM,     // 参数缺失
            "10005" to TranslationError.PARAM,     // 语项不支持
            "10006" to TranslationError.PARAM,     // 语种识别失败
            "10007" to TranslationError.SERVER,    // 翻译失败
            "10008" to TranslationError.PARAM,     // 字符长度过长
            "10009" to TranslationError.AUTH,      // 子账号没有权限
            "10010" to TranslationError.AUTH,      // 账号没有开通服务
            "10011" to TranslationError.SERVER,    // 子账号服务失败
            "10012" to TranslationError.SERVER,    // 翻译服务调用失败
            "10013" to TranslationError.QUOTA,     // 没有开通服务或欠费
            "19999" to TranslationError.SERVER,    // 未知异常
        )
        for ((code, want) in expected) {
            assertEquals(
                "业务码 $code 的分类与官方错误码表不符",
                want,
                AliyunTranslator.aliyunErrorOf(code),
            )
        }
        // 表外的码仍归服务端异常（这条同时防止「随手把 else 改成 PARAM」这类反向改动）
        assertEquals(TranslationError.SERVER, AliyunTranslator.aliyunErrorOf("99999"))
    }

    @Test
    fun `请求不带 Region 之外的冗余头（Host 与 Content-Length 交给 OkHttp）`() {
        val request = translator.buildRequest("x", TranslationLanguage.ENGLISH)
        assertNull(request.header("Host"))
        assertNull(request.header("Content-Length"))
    }

    private companion object {
        const val AK_ID = "LTAI5tExampleAccessKeyId"
        const val AK_SECRET = "ExampleAccessKeySecret1234567890abcdef"
        const val NONCE = "b924c8f1-3f70-4a5b-9f2e-2b6b0f0a1c2d"
    }
}
