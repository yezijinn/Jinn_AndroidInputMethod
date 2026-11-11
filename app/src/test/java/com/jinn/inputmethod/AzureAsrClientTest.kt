package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Azure AI Speech 短音频识别（[AzureAsrClient] 及其两侧）的守卫。
 *
 * 纯函数部分能真跑：端点拼接（去尾斜杠 / 语言参数转义）、地址策略复用、请求头、回包按
 * `RecognitionStatus` 分流；接线部分按源码对拍：工厂、备份白名单、页面掩码与写盘分支。
 */
class AzureAsrClientTest {

    private val resourceBase = "https://jinn-speech.cognitiveservices.azure.com"

    // ── 端点 ─────────────────────────────────────────────────────

    @Test
    fun `端点拼接去尾斜杠并带上语言参数`() {
        val expected = resourceBase +
            "/stt/speech/recognition/conversation/cognitiveservices/v1?language=zh-CN"
        assertEquals(expected, AzureAsrEndpoint.url(resourceBase, "zh-CN"))
        // 用户把尾部斜杠抄进地址是常态：拼出来不能是 `//stt/...`
        assertEquals(expected, AzureAsrEndpoint.url("$resourceBase/", "zh-CN"))
        // 语言码留空回落默认（空 language= 会被服务端 400）
        assertEquals(expected, AzureAsrEndpoint.url(resourceBase, "   "))
        assertEquals(
            "zh-CN",
            AzureAsrEndpoint.DEFAULT_LANGUAGE,
        )
        // 查询串必须转义：语言码里带 `&` / 空格时不转义就是拼出一个非法地址
        assertEquals(
            resourceBase + "/stt/speech/recognition/conversation/cognitiveservices/v1?language=zh+CN",
            AzureAsrEndpoint.url(resourceBase, "zh CN"),
        )
        assertTrue(
            "语言码里的 & 必须转义（否则多出一个参数）",
            AzureAsrEndpoint.url(resourceBase, "zh&x=1").endsWith("?language=zh%26x%3D1"),
        )
        assertEquals("zh-CN", AzureAsrEndpoint.queryEscape("zh-CN"))
    }

    @Test
    fun `地址策略与另几家同一条主线`() {
        // 复用 OpenAiEndpoint 那套：https 放行、去尾斜杠、拒绝原因同一句
        assertEquals(resourceBase, AzureAsrEndpoint.normalize(" $resourceBase/ ").getOrThrow())
        assertEquals(
            OpenAiEndpoint.TEXT_PUBLIC_HTTP,
            AzureAsrEndpoint.normalize("http://speech.azure.com").exceptionOrNull()?.message,
        )
        assertEquals(
            OpenAiEndpoint.TEXT_EMPTY,
            AzureAsrEndpoint.normalize("   ").exceptionOrNull()?.message,
        )
        assertTrue("本机明文放行（与另几家一致）", AzureAsrEndpoint.normalize("http://127.0.0.1:8080").isSuccess)
        assertTrue("局域网明文放行", AzureAsrEndpoint.normalize("http://192.168.1.9:8080").isSuccess)
    }

    @Test
    fun `请求头带订阅密钥与音频类型`() {
        val url = AzureAsrEndpoint.url(resourceBase, "zh-CN")
        val wav = WavWriter.wrap(ByteArray(128))
        val request = AzureAsrRequest.request(url, "sk-test", wav)

        assertEquals("POST", request.method)
        assertEquals(url, request.url.toString())
        assertEquals("订阅密钥必须走 Ocp-Apim-Subscription-Key", "sk-test", request.header("Ocp-Apim-Subscription-Key"))
        assertEquals("application/json", request.header("Accept"))
        // Content-Type 带 codecs 参数：OkHttp 的 MediaType 解析器不认 `codecs=audio/pcm`，
        // 所以请求体自己报 contentType()=null、头由请求侧原样下发（否则建请求就抛异常）
        assertEquals(
            "audio/wav; codecs=audio/pcm; samplerate=16000",
            request.header("Content-Type"),
        )
        assertNull("请求体不许再声明 contentType（会覆盖上面那行、且解析必失败）", request.body!!.contentType())
        assertEquals("长度必须等于 WAV 字节数", wav.size.toLong(), request.body!!.contentLength())
    }

    // ── 回包 ─────────────────────────────────────────────────────

    @Test
    fun `响应按状态分流且非成功给可读原因`() {
        assertEquals(
            "微软测试通过",
            (AzureResponse.parse(
                200,
                """{"RecognitionStatus":"Success","DisplayText":"微软测试通过","Offset":0,"Duration":0}""",
            ) as BatchOutcome.Ok).text,
        )
        // 成功但正文缺失：空文本也算成功（链路通、只是没听到声音）
        assertEquals(
            "",
            (AzureResponse.parse(200, """{"RecognitionStatus":"Success"}""") as BatchOutcome.Ok).text,
        )
        // 非 Success 必须给出**可读**原因（只说「识别失败」会让用户去改一个没坏的东西）
        val noMatch = (AzureResponse.parse(200, """{"RecognitionStatus":"NoMatch"}""") as BatchOutcome.Fail).reason
        assertTrue("NoMatch 要说明「没识别到内容」：$noMatch", "没有识别到内容" in noMatch && "NoMatch" in noMatch)
        val silence = (AzureResponse.parse(
            200,
            """{"RecognitionStatus":"InitialSilenceTimeout"}""",
        ) as BatchOutcome.Fail).reason
        assertTrue("InitialSilenceTimeout 要指向「没听到声音」：$silence", "没听到声音" in silence)
        val babble = (AzureResponse.parse(200, """{"RecognitionStatus":"BabbleTimeout"}""") as BatchOutcome.Fail).reason
        assertTrue("BabbleTimeout 要指向「只有噪音」：$babble", "噪音" in babble)
        val error = (AzureResponse.parse(200, """{"RecognitionStatus":"Error"}""") as BatchOutcome.Fail).reason
        assertTrue("Error 要指向「服务端出错」：$error", "服务端" in error)
        // 认不出的状态名原样带出（排障与官方文档对得上）
        assertTrue(
            (AzureResponse.parse(200, """{"RecognitionStatus":"SomethingNew"}""") as BatchOutcome.Fail)
                .reason.contains("SomethingNew"),
        )
        // 鉴权失败是「有 HTTP 状态码、没有 RecognitionStatus」的错误报文：状态码才是唯一线索
        val unauthorized = (AzureResponse.parse(
            401,
            """{"error":{"code":"401","message":"Access denied due to invalid subscription key."}}""",
        ) as BatchOutcome.Fail).reason
        assertTrue("401 必须带状态码：$unauthorized", unauthorized.startsWith("HTTP 401"))
        assertEquals(
            AzureResponse.TEXT_NO_STATUS,
            (AzureResponse.parse(200, """{"foo":1}""") as BatchOutcome.Fail).reason,
        )
    }

    // ── 前置校验 / 工厂 / 备份 / 页面 ──────────────────────────────

    @Test
    fun `Azure 客户端读的是语音密钥而不是翻译那把`() {
        // 翻译页的 Azure Key 与本页的订阅密钥是两个键、两套配额：读错必然 401，
        // 而「页面上填了密钥却认不出」在真机上只表现为鉴权失败。
        val src = TestSources.codeSource("AzureAsrClient.kt")
        val prep = TestSources.blockAfter(src, "override fun prepare")
        assertTrue("订阅密钥必须取自 prefs.azureSpeechKey", "prefs.azureSpeechKey" in prep)
        assertFalse("不许读翻译的 azureApiKey", "prefs.azureApiKey" in src)
        assertTrue("地址必须取自 prefs.azureBaseUrl", "prefs.azureBaseUrl" in prep)
        assertTrue("语言码必须取自 prefs.azureLanguage", "prefs.azureLanguage" in prep)
    }

    @Test
    fun `语音测试前置校验点名 Azure 缺件与地址非法`() {
        assertEquals(
            "Azure 配置不全，缺少 Base URL / API Key",
            VoiceTestPreflight.check(VoiceBackend.AZURE, "", "", "", "192.168.1.3"),
        )
        assertEquals(
            "Azure 配置不全，缺少 API Key",
            VoiceTestPreflight.check(
                VoiceBackend.AZURE, "", "", "", "192.168.1.3",
                azureBaseUrl = resourceBase, azureApiKey = "",
            ),
        )
        assertNull(
            VoiceTestPreflight.check(
                VoiceBackend.AZURE, "", "", "", "192.168.1.3",
                azureBaseUrl = resourceBase, azureApiKey = "k",
            ),
        )
        assertEquals(
            "公网明文必须被前置校验拦下，且原因与页面保存时同一句",
            OpenAiEndpoint.TEXT_PUBLIC_HTTP,
            VoiceTestPreflight.check(
                VoiceBackend.AZURE, "", "", "", "192.168.1.3",
                azureBaseUrl = "http://speech.azure.com", azureApiKey = "k",
            ),
        )
    }

    @Test
    fun `工厂六分支齐全且备份白名单含 Azure 三键`() {
        val factory = TestSources.codeSource("SpeechClients.kt")
        for (impl in listOf(
            "VoiceBackend.XF -> XfIatClient(prefs, onState, onMessage)",
            "VoiceBackend.OAI -> OpenAiAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.ALI -> AliAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.TENCENT -> TencentAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.AZURE -> AzureAsrClient(prefs, onState, onMessage)",
            "else -> AsrClient(prefs, onState, onMessage)",
        )) {
            assertTrue("工厂缺少分支：$impl", impl in factory)
        }

        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("后端没有 azure 取值", "const val AZURE = \"azure\"" in prefs)
        assertEquals(VoiceBackend.AZURE, VoiceBackend.of("azure"))
        assertTrue("Azure 是在线后端（单次音频有上限，要 58s 收尾）", VoiceBackend.isOnline(VoiceBackend.AZURE))
        assertTrue("读侧没走加密通道", "get() = readCredential(KEY_AZURE_ASR_API_KEY)" in prefs)
        assertTrue("写侧没走加密通道", "set(value) = writeCredential(KEY_AZURE_ASR_API_KEY, value)" in prefs)
        assertEquals("zh-CN", Prefs.DEFAULT_AZURE_LANGUAGE)
        // 订阅密钥与翻译那把 Azure Key 必须**不是同一个键**（两家服务、两套配额）
        assertTrue(
            "Azure 语音密钥与翻译密钥用了同一个键",
            "\"azure_speech_api_key\"" in prefs && "\"azure_api_key\"" in prefs,
        )

        val export = TestSources.blockAfter(prefs, "internal fun exportForBackup")
        assertTrue("地址没进导出白名单", "put(KEY_AZURE_BASE_URL, azureBaseUrl)" in export)
        assertTrue("语言码没进导出白名单", "put(KEY_AZURE_LANGUAGE, azureLanguage)" in export)
        assertTrue("订阅密钥没进导出白名单", "putCredential(KEY_AZURE_ASR_API_KEY)" in export)

        val import = TestSources.blockAfter(prefs, "internal fun importFromBackup")
        assertTrue("地址没进导入白名单", "KEY_AZURE_BASE_URL -> asString(v)" in import)
        assertTrue("语言码没进导入白名单", "KEY_AZURE_LANGUAGE -> asString(v)" in import)
        assertTrue(
            "订阅密钥空串必须按忽略处理（否则会删掉本机可用凭据）",
            "KEY_AZURE_ASR_API_KEY ->" in import,
        )
        val configured = TestSources.blockAfter(prefs, "fun voiceConfigured()")
        assertTrue(
            "voiceConfigured 没认 Azure（地址策略 + 密钥）",
            "VoiceBackend.AZURE -> AzureAsrEndpoint.normalize(azureBaseUrl).isSuccess" in configured,
        )
    }

    @Test
    fun `状态条两处标签都认 Azure`() {
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue(
            "在线标签没认 Azure（会拿自建服务端的 host:port 拼「已连接」）",
            "VoiceBackend.AZURE -> TEXT_AZURE_ONLINE" in TestSources.blockAfter(ime, "private fun renderLink"),
        )
        assertTrue(
            "离线提示没认 Azure",
            "VoiceBackend.AZURE -> TEXT_AZURE_OFFLINE" in
                TestSources.blockAfter(ime, "private fun switchToVoiceKeyboard"),
        )
        assertTrue("在线标签常量缺失", "const val TEXT_AZURE_ONLINE" in ime)
        assertTrue("离线提示常量缺失", "const val TEXT_AZURE_OFFLINE" in ime)
    }

    @Test
    fun `页面 Azure 卡走掩码且只在该后端下写盘`() {
        val page = TestSources.codeSource("VoiceSettingsActivity.kt")
        val load = TestSources.blockAfter(page, "private fun loadPrefs")
        assertTrue("Key 回填没走掩码", "setCredentialText(editAzureApiKey, prefs.azureSpeechKey)" in load)
        assertTrue("地址没回填", "editAzureBaseUrl.setText(prefs.azureBaseUrl)" in load)
        assertTrue("语言码没回填", "editAzureLanguage.setText(prefs.azureLanguage)" in load)

        val save = TestSources.blockAfter(page, "private fun persistInputs")
        assertTrue("Key 有效取值没走 credentialText", "val azureKeyValue = credentialText(editAzureApiKey)" in save)
        assertFalse(
            "保存直接写回了输入框文本（掩码会被写坏）",
            "prefs.azureSpeechKey = editAzureApiKey.text.toString()" in save,
        )
        assertTrue("地址没写回", "prefs.azureBaseUrl = azureBaseValue" in save)
        assertTrue("语言码没写回", "prefs.azureLanguage = azureLanguageValue" in save)
        assertTrue("写盘必须只发生在 azure 分支", "if (backend == VoiceBackend.AZURE) {" in save)
        // 地址策略的拒绝原因必须落到 edit_azure_base_url.error（用户要看到「为什么不能用」）
        assertTrue("地址非法没有落到输入框", "editAzureBaseUrl.error = addressError" in save)
        assertTrue("保存校验没走地址策略", "AzureAsrEndpoint.normalize(azureBaseValue)" in save)
        for (field in listOf("editAzureBaseUrl", "editAzureApiKey")) {
            assertTrue("$field 缺少必填校验", "$field.error = TEXT_AZURE_REQUIRED" in save)
        }
        // 秘密不进日志：只允许长度
        assertTrue("保存日志必须只记长度", "azureKeyLen=" in save)
        assertFalse(
            "日志里出现了密钥原文",
            Regex("""\$\{prefs\.azureSpeechKey\}""").containsMatchIn(page),
        )

        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_voice_settings.xml",
            "app/src/main/res/layout/activity_voice_settings.xml",
        )
        assertTrue(
            "card_azure_credentials 必须默认 GONE",
            Regex("""android:id="@\+id/card_azure_credentials"\s+android:visibility="gone"""")
                .containsMatchIn(layout),
        )
        assertTrue(
            "订阅密钥必须是 password 型输入框",
            Regex("""android:id="@\+id/edit_azure_api_key"[^>]*android:inputType="textPassword"""")
                .containsMatchIn(layout),
        )
    }
}
