package com.jinn.inputmethod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 腾讯云一句话识别（[TencentAsrClient] 及其两侧）的守卫。
 *
 * 分两半：
 *  - **能真跑的纯函数**：TC3 签名（固定输入复算整条 Authorization）、UTC 日期、请求体字段、
 *    请求头、回包解析、前置校验 —— 这些错了真机上只有一句「鉴权失败」或「格式错误」，查不出所以然；
 *  - **只能在源码上对拍的接线**：工厂分发、备份白名单、页面掩码与写盘分支。
 */
class TencentAsrClientTest {

    private val client = TestSources.codeSource("TencentAsrClient.kt")

    /**
     * 签名向量用的固定请求体（真实请求体由 [TencentAsrRequest.body] 生成，形状与此一致）。
     *
     * 写死一份字面量而不是「先组 body 再验签名」：签名是**对字节**算的，
     * JSON 序列化顺序一变签名就变；这里要的是「公式本身可复算」，不该被序列化细节绑架。
     */
    private val fixedBody =
        """{"EngSerViceType":"16k_zh","SourceType":1,"VoiceFormat":"wav","Data":"AAAA","DataLen":3}"""

    private val fixedSecretId = "AKIDEXAMPLE0000000000"

    private val fixedSecretKey = "SECRETEXAMPLE0000000000"

    /** 1700000000 = 2023-11-14T22:13:20Z（东八区此刻已是 11-15 06:13） */
    private val fixedTimestamp = 1_700_000_000L

    // ── TC3 签名 ─────────────────────────────────────────────────

    @Test
    fun `TC3 签名用固定输入可复算`() {
        val auth = TencentTc3.authorization(
            fixedSecretId, fixedSecretKey, fixedTimestamp, TencentTc3.HOST, fixedBody,
        )
        // 期望值由官方公式独立算得（HMAC-SHA256 链 + CanonicalRequest 逐段拼接），
        // 改任何一段（日期位置 / SignedHeaders / 算法名）这条都会红。
        assertEquals(
            "TC3-HMAC-SHA256 Credential=AKIDEXAMPLE0000000000/2023-11-14/asr/tc3_request, " +
                "SignedHeaders=content-type;host, " +
                "Signature=b4034dd56b7c3a3e7d3eefad2586a5b4ccc5436378a55a9fea09f19ba5b83dca",
            auth,
        )

        // CanonicalRequest 的每一段都必须逐字对（少一个换行签名就整体错位）
        val canonical = TencentTc3.canonicalRequest(TencentTc3.HOST, fixedBody)
        assertTrue(
            "CanonicalRequest 必须以 POST 方法行 + 空查询串开头：$canonical",
            canonical.startsWith("POST\n/\n\n"),
        )
        assertTrue("CanonicalRequest 内容类型行不对", "content-type:application/json; charset=utf-8" in canonical)
        assertTrue("CanonicalRequest 主机行不对", "host:asr.tencentcloudapi.com" in canonical)
        assertTrue("SignedHeaders 行不对", "\n\ncontent-type;host\n" in canonical)
        assertEquals(
            "CanonicalRequest 末行必须是 body 的 sha256",
            TencentTc3.sha256Hex(fixedBody.toByteArray(Charsets.UTF_8)),
            canonical.substringAfterLast('\n'),
        )

        // StringToSign：算法、时间戳、日期/服务/请求类型、CanonicalRequest 的 sha256
        val sts = TencentTc3.stringToSign(fixedTimestamp, "2023-11-14", canonical)
        val lines = sts.split("\n")
        assertEquals("TC3-HMAC-SHA256", lines[0])
        assertEquals("1700000000", lines[1])
        assertEquals("2023-11-14/asr/tc3_request", lines[2])
        assertEquals(TencentTc3.sha256Hex(canonical.toByteArray(Charsets.UTF_8)), lines[3])

        // 签名对输入敏感：换 key / 换时刻都必须产出不同结果（防止「实现成常量」也能过）
        assertNotEquals(
            auth,
            TencentTc3.authorization(fixedSecretId, "OTHER", fixedTimestamp, TencentTc3.HOST, fixedBody),
        )
        assertNotEquals(
            auth,
            TencentTc3.authorization(fixedSecretId, fixedSecretKey, fixedTimestamp + 1, TencentTc3.HOST, fixedBody),
        )
        assertEquals(64, TencentTc3.signature(fixedSecretKey, "2023-11-14", sts).length)
    }

    @Test
    fun `签名日期按 timestamp 的 UTC 算而不是本地时区`() {
        // 东八区此刻是 2023-11-15 06:13 —— 用本地时区会算出 15 日，签名当场 InvalidParameter
        assertEquals("2023-11-14", TencentTc3.utcDate(fixedTimestamp))
        // 同一个 UTC 日内的两端
        assertEquals("2023-11-14", TencentTc3.utcDate(1_699_920_000L))   // 2023-11-14T00:00:00Z
        assertEquals("2023-11-14", TencentTc3.utcDate(1_700_006_399L))   // 2023-11-14T23:59:59Z
        assertEquals("2023-11-15", TencentTc3.utcDate(1_700_006_400L))   // 2023-11-15T00:00:00Z
    }

    // ── 请求体 / 请求头 ──────────────────────────────────────────

    @Test
    fun `请求体字段齐全且 DataLen 是原始字节数`() {
        val wav = WavWriter.wrap(ByteArray(300) { (it % 251).toByte() })
        val json = JSONObject(TencentAsrRequest.body(wav, "16k_zh"))

        assertEquals("引擎型号字段名必须是官方原文 EngSerViceType", "16k_zh", json.getString("EngSerViceType"))
        assertEquals("SourceType 必须为 1（音频数据）", 1, json.getInt("SourceType"))
        assertEquals("VoiceFormat 必须声明 wav", "wav", json.getString("VoiceFormat"))

        val data = json.getString("Data")
        assertEquals("Data 必须是 WAV 的 base64", Base64.getEncoder().encodeToString(wav), data)
        assertEquals("Data 解出来必须逐字节是原 WAV", wav.toList(), Base64.getDecoder().decode(data).toList())

        // ⚠ DataLen 是**未编码**的字节数：填 base64 长度会被服务端判格式错（长音频才暴露）
        assertEquals("DataLen 必须是原始字节数", wav.size, json.getInt("DataLen"))
        assertNotEquals("DataLen 不许填成 base64 长度", data.length, json.getInt("DataLen"))
        assertEquals(wav.size, TencentAsrRequest.dataLen(wav))

        // 引擎型号留空时回落到默认（空型号必然失败，不能原样发出去）
        assertEquals(
            TencentAsrRequest.DEFAULT_ENGINE,
            JSONObject(TencentAsrRequest.body(wav, "  ")).getString("EngSerViceType"),
        )
    }

    @Test
    fun `请求头带签名动作版本与时间戳`() {
        val wav = WavWriter.wrap(ByteArray(64))
        val request = TencentAsrRequest.request(fixedBody, fixedSecretId, fixedSecretKey, fixedTimestamp)

        assertEquals("https://asr.tencentcloudapi.com/", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("SentenceRecognition", request.header("X-TC-Action"))
        assertEquals("2019-06-14", request.header("X-TC-Version"))
        assertEquals("时间戳必须与签名用的是同一个值", "1700000000", request.header("X-TC-Timestamp"))
        assertEquals("application/json; charset=utf-8", request.header("Content-Type"))
        val auth = request.header("Authorization").orEmpty()
        assertTrue("Authorization 必须是 TC3-HMAC-SHA256：$auth", auth.startsWith("TC3-HMAC-SHA256 Credential="))
        assertTrue(auth.contains("SignedHeaders=content-type;host"))
        assertTrue("SignedHeaders 必须与签名里的一致（漏 host 会被服务端拒）", "Signature=" in auth)

        // 真实请求体也走同一条组装路径
        val real = TencentAsrRequest.request(TencentAsrRequest.body(wav, "16k_zh"), fixedSecretId, fixedSecretKey, fixedTimestamp)
        val sent = okio.Buffer().also { real.body!!.writeTo(it) }.readUtf8()
        assertTrue("请求体必须是 wav 的 base64 数据", "\"VoiceFormat\":\"wav\"" in sent)
    }

    // ── 回包 ─────────────────────────────────────────────────────

    @Test
    fun `回包取 Result 且 Error 原样带出`() {
        assertEquals(
            "腾讯测试通过",
            (TencentResponse.parse(200, """{"Response":{"Result":"腾讯测试通过","RequestId":"x"}}""")
                as BatchOutcome.Ok).text,
        )
        // 空文本也是成功（链路通、只是没听到声音）
        assertEquals(
            "",
            (TencentResponse.parse(200, """{"Response":{"Result":""}}""") as BatchOutcome.Ok).text,
        )
        // 鉴权失败 / 未开通 / 时长超限**常是 HTTP 200 + Error 块**：只判状态码会被当成「没听到语音」
        assertEquals(
            "AuthFailure.SignatureFailure: 签名错误",
            (TencentResponse.parse(
                200,
                """{"Response":{"Error":{"Code":"AuthFailure.SignatureFailure","Message":"签名错误"}}}""",
            ) as BatchOutcome.Fail).reason,
        )
        assertEquals(
            "FailedOperation.NoSpeechDetected",
            (TencentResponse.parse(
                200,
                """{"Response":{"Error":{"Code":"FailedOperation.NoSpeechDetected"}}}""",
            ) as BatchOutcome.Fail).reason,
        )
        // Result 缺失（只有 RequestId）当失败：说「没听到语音」会让用户去查麦克风
        assertEquals(
            TencentResponse.TEXT_NO_RESULT,
            (TencentResponse.parse(200, """{"Response":{"RequestId":"x"}}""") as BatchOutcome.Fail).reason,
        )
        // 报文体不是 JSON / 没有 Response 块：状态码 + 截断正文
        assertTrue(
            (TencentResponse.parse(502, "bad gateway") as BatchOutcome.Fail).reason.startsWith("HTTP 502"),
        )
        assertEquals("HTTP 500", (TencentResponse.parse(500, "") as BatchOutcome.Fail).reason)
    }

    // ── 前置校验 / 工厂 / 备份 / 页面 ──────────────────────────────

    @Test
    fun `语音测试前置校验点名腾讯缺件`() {
        assertEquals(
            "腾讯云凭据不全，缺少 SecretId / SecretKey",
            VoiceTestPreflight.check(VoiceBackend.TENCENT, "", "", "", "192.168.1.3"),
        )
        assertEquals(
            "腾讯云凭据不全，缺少 SecretKey",
            VoiceTestPreflight.check(
                VoiceBackend.TENCENT, "", "", "", "192.168.1.3",
                tencentSecretId = "id", tencentSecretKey = "",
            ),
        )
        assertNull(
            VoiceTestPreflight.check(
                VoiceBackend.TENCENT, "", "", "", "192.168.1.3",
                tencentSecretId = "id", tencentSecretKey = "key",
            ),
        )
    }

    @Test
    fun `工厂与备份白名单都认腾讯三键`() {
        val factory = TestSources.codeSource("SpeechClients.kt")
        assertTrue("工厂没有腾讯分支", "VoiceBackend.TENCENT -> TencentAsrClient(prefs, onState, onMessage)" in factory)

        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("后端没有 tencent 取值", "const val TENCENT = \"tencent\"" in prefs)
        assertEquals(VoiceBackend.TENCENT, VoiceBackend.of("tencent"))
        assertTrue("腾讯云是在线后端（单次音频有上限，要 58s 收尾）", VoiceBackend.isOnline(VoiceBackend.TENCENT))
        assertTrue("读侧没走加密通道", "get() = readCredential(KEY_TENCENT_SECRET_ID)" in prefs)
        assertTrue("写侧没走加密通道", "set(value) = writeCredential(KEY_TENCENT_SECRET_KEY, value)" in prefs)
        assertEquals("16k_zh", Prefs.DEFAULT_TENCENT_ENGINE)

        val export = TestSources.blockAfter(prefs, "internal fun exportForBackup")
        assertTrue("SecretId 没进导出白名单", "putCredential(KEY_TENCENT_SECRET_ID)" in export)
        assertTrue("SecretKey 没进导出白名单", "putCredential(KEY_TENCENT_SECRET_KEY)" in export)
        assertTrue("引擎型号没进导出白名单", "put(KEY_TENCENT_ENGINE, tencentEngine)" in export)

        val import = TestSources.blockAfter(prefs, "internal fun importFromBackup")
        assertTrue("引擎型号没进导入白名单", "KEY_TENCENT_ENGINE -> asString(v)" in import)
        assertTrue(
            "SecretId 空串必须按忽略处理（否则会删掉本机可用凭据）",
            "KEY_TENCENT_SECRET_ID ->" in import && "takeIf { it.isNotEmpty() }" in import,
        )
        // 配置齐不齐也要认第五家（否则「语音测试」会拿自建那套判据）
        val configured = TestSources.blockAfter(prefs, "fun voiceConfigured()")
        assertTrue(
            "voiceConfigured 没认腾讯云（两件凭据缺一不可）",
            "VoiceBackend.TENCENT -> tencentSecretId.cleanCredential().isNotEmpty()" in configured,
        )
    }

    @Test
    fun `状态条两处标签都认腾讯云`() {
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue(
            "在线标签没认腾讯云（会拿自建服务端的 host:port 拼「已连接」）",
            "VoiceBackend.TENCENT -> TEXT_TENCENT_ONLINE" in TestSources.blockAfter(ime, "private fun renderLink"),
        )
        assertTrue(
            "离线提示没认腾讯云",
            "VoiceBackend.TENCENT -> TEXT_TENCENT_OFFLINE" in
                TestSources.blockAfter(ime, "private fun switchToVoiceKeyboard"),
        )
        assertTrue("在线标签常量缺失", "const val TEXT_TENCENT_ONLINE" in ime)
        assertTrue("离线提示常量缺失", "const val TEXT_TENCENT_OFFLINE" in ime)
    }

    @Test
    fun `页面腾讯卡走掩码且只在该后端下写盘`() {
        val page = TestSources.codeSource("VoiceSettingsActivity.kt")
        val load = TestSources.blockAfter(page, "private fun loadPrefs")
        assertTrue("SecretId 回填没走掩码", "setCredentialText(editTencentSecretId, prefs.tencentSecretId)" in load)
        assertTrue("SecretKey 回填没走掩码", "setCredentialText(editTencentSecretKey, prefs.tencentSecretKey)" in load)
        assertTrue("引擎型号没回填", "editTencentEngine.setText(prefs.tencentEngine)" in load)

        val save = TestSources.blockAfter(page, "private fun persistInputs")
        assertTrue("SecretKey 有效取值没走 credentialText", "val tencentKeyValue = credentialText(editTencentSecretKey)" in save)
        assertFalse(
            "保存直接写回了输入框文本（掩码会被写坏）",
            "prefs.tencentSecretKey = editTencentSecretKey.text.toString()" in save,
        )
        assertTrue("引擎型号没写回", "prefs.tencentEngine = tencentEngineValue" in save)
        assertTrue("写盘必须只发生在 tencent 分支", "if (backend == VoiceBackend.TENCENT) {" in save)
        for (field in listOf("editTencentSecretId", "editTencentSecretKey")) {
            assertTrue("$field 缺少必填校验", "$field.error = TEXT_TENCENT_REQUIRED" in save)
        }
        // 秘密不进日志：只允许长度
        assertTrue("保存日志必须只记长度", "tencentKeyLen=" in save)
        assertFalse(
            "日志里出现了 SecretKey 原文",
            Regex("""\$\{prefs\.tencentSecretKey\}""").containsMatchIn(page),
        )
        // 掩码机制覆盖到这两个框（回填 / 揭示 / 掩码三条路径共用一份清单）
        assertTrue(
            "凭据框清单漏了腾讯两件",
            "editTencentSecretId, editTencentSecretKey" in
                TestSources.window(page, "private fun credentialFields", "private fun diskCredentialOf"),
        )
        // 卡片默认隐藏（出厂后端是自建服务端）：否则首屏会并排显示两张卡的字段
        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_voice_settings.xml",
            "app/src/main/res/layout/activity_voice_settings.xml",
        )
        assertTrue(
            "card_tencent_credentials 必须默认 GONE",
            Regex("""android:id="@\+id/card_tencent_credentials"\s+android:visibility="gone"""")
                .containsMatchIn(layout),
        )
        assertTrue(
            "腾讯卡的 SecretKey 必须是 password 型输入框",
            Regex("""android:id="@\+id/edit_tencent_secret_key"[^>]*android:inputType="textPassword"""")
                .containsMatchIn(layout),
        )
    }
}
