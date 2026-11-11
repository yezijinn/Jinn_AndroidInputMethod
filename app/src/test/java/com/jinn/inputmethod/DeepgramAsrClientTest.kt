package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deepgram Pre-recorded（[DeepgramAsrClient] 及其两侧）的守卫。
 *
 * 纯函数部分能真跑：查询串（模型 / 语言 / `smart_format`，值与默认值都要转义）、请求头
 * （`Authorization: Token` + `audio/wav` + 裸字节体）、回包的 transcript 解析与
 * 三种错误体（`err_code` / `category` / 纯状态码）的可读原因；
 * 接线部分按源码对拍：工厂八分支、备份白名单、页面掩码与写盘分支。
 */
class DeepgramAsrClientTest {

    private val wav = WavWriter.wrap(ByteArray(128))

    // ── 查询串与请求 ──────────────────────────────────────────────

    @Test
    fun `查询串按官方组合拼接且值必须转义`() {
        assertEquals(
            "https://api.deepgram.com/v1/listen?model=nova-3&language=zh&smart_format=true",
            DeepgramRequest.url("nova-3", "zh"),
        )
        // 空值回落默认（空 model= / language= 会被服务端 400）
        assertEquals(DeepgramRequest.url("nova-3", "zh"), DeepgramRequest.url("  ", "  "))
        assertEquals("nova-3", DeepgramRequest.DEFAULT_MODEL)
        assertEquals("zh", DeepgramRequest.DEFAULT_LANGUAGE)
        // smart_format 不是装饰：不开的话数字与标点按英文原样回来，中文落字会出现怪结果
        assertTrue("必须带 smart_format=true", "smart_format=true" in DeepgramRequest.url("nova-3", "zh"))
        // 用户多打一个 `&` / 空格：不转义就是拼出一个非法地址（OkHttp 当场抛，用户只看到「识别失败」）
        assertEquals(
            "https://api.deepgram.com/v1/listen?model=nova+3&language=zh&smart_format=true",
            DeepgramRequest.url("nova 3", "zh"),
        )
        assertTrue(
            "语言码里的 & 必须转义（否则多出一个参数）",
            DeepgramRequest.url("nova-3", "zh&x=1").endsWith("&language=zh%26x%3D1&smart_format=true"),
        )
        assertEquals("zh-CN", DeepgramRequest.queryEscape("zh-CN"))
    }

    @Test
    fun `请求头是 Token 鉴权且体是裸 WAV`() {
        val request = DeepgramRequest.request("nova-3", "zh", "dg-key", wav)
        assertEquals("POST", request.method)
        assertEquals(
            "鉴权方案前缀必须是 Token（写 Bearer 必然 401）",
            "Token dg-key",
            request.header("Authorization"),
        )
        assertTrue("前缀不许是 Bearer", request.header("Authorization")!!.startsWith(DeepgramRequest.AUTH_PREFIX))
        assertEquals("audio/wav", request.header("Content-Type"))
        assertEquals("请求体必须是裸 WAV 字节（不是 multipart）", wav.size.toLong(), request.body!!.contentLength())
        assertEquals("audio/wav", request.body!!.contentType().toString())
        assertTrue(
            "端点必须 https",
            request.url.toString().startsWith("https://api.deepgram.com/v1/listen?"),
        )
    }

    // ── 回包 ─────────────────────────────────────────────────────

    @Test
    fun `回包取第一路 transcript`() {
        val body = """
            {"metadata":{"request_id":"x"},
             "results":{"channels":[{"alternatives":[{"transcript":"今天天气不错","confidence":0.99}],
             "detected_language":"zh"}]}}
        """.trimIndent()
        assertEquals("今天天气不错", (DeepgramResponse.parse(200, body) as BatchOutcome.Ok).text)
        // 没听到声音：空 transcript 也是 Ok（链路通，用户只要说话）
        val silent = """{"results":{"channels":[{"alternatives":[{"transcript":""}]}]}}"""
        assertEquals("", (DeepgramResponse.parse(200, silent) as BatchOutcome.Ok).text)
        // 结构不对（没有 results）：如实说清是回包问题，而不是「识别失败」
        assertEquals(
            DeepgramResponse.TEXT_NO_RESULT,
            (DeepgramResponse.parse(200, """{"metadata":{}}""") as BatchOutcome.Fail).reason,
        )
    }

    @Test
    fun `错误体 err_code 与 category 都要带出可读原因`() {
        // 形态一：{"err_code","err_msg"}
        val auth = (
            DeepgramResponse.parse(200, """{"err_code":"INVALID_AUTH","err_msg":"Invalid credentials."}""")
                as BatchOutcome.Fail
            ).reason
        assertTrue("必须带 err_code：$auth", "INVALID_AUTH" in auth)
        assertTrue("必须带 err_msg：$auth", "Invalid credentials." in auth)
        assertTrue("必须指出改 Key：$auth", "API Key" in auth)
        // 形态二：{"category","message"}
        val bad = (
            DeepgramResponse.parse(
                400,
                """{"category":"INVALID_REQUEST","message":"Model nova-9 does not exist."}""",
            ) as BatchOutcome.Fail
            ).reason
        assertTrue("必须带 category：$bad", "INVALID_REQUEST" in bad)
        assertTrue("必须带服务端 message：$bad", "nova-9" in bad)
        assertTrue("必须带状态码：$bad", bad.startsWith("HTTP 400"))
    }

    @Test
    fun `非二非状态码单独给可读原因`() {
        fun reason(code: Int, body: String? = ""): String =
            (DeepgramResponse.parse(code, body) as BatchOutcome.Fail).reason

        val unauthorized = reason(401)
        assertTrue("401 必须带状态码：$unauthorized", unauthorized.startsWith("HTTP 401"))
        assertTrue("401 必须指向 API Key：$unauthorized", "API Key" in unauthorized)
        assertTrue("402 必须指向额度", "额度" in reason(402))
        assertTrue("403 必须指向权限 / 项目", "无权" in reason(403))
        assertTrue("404 必须指向模型", "模型" in reason(404))
        assertTrue("429 必须指向频率 / 配额", "频繁" in reason(429))
        assertTrue("5xx 必须指向服务端", "服务端" in reason(503))
        // 认不出的码只报状态码，不编原因
        assertEquals("HTTP 418", reason(418))
        // 空体也不能吞掉状态码
        assertTrue("空体的 5xx 也要带状态码", reason(500, null).startsWith("HTTP 500"))
        assertTrue("非 JSON 的正文要截断带出（仍是排障线索）", "upstream" in reason(502, "upstream connect error"))
    }

    // ── Prefs / 工厂 / 备份 / 状态条 / 页面 ───────────────────────

    @Test
    fun `Deepgram 三键与默认值与请求层同源`() {
        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("后端没有 deepgram 取值", "const val DEEPGRAM = \"deepgram\"" in prefs)
        assertEquals(VoiceBackend.DEEPGRAM, VoiceBackend.of("deepgram"))
        assertTrue("Deepgram 是在线后端（单次音频有上限，要 58s 收尾）", VoiceBackend.isOnline(VoiceBackend.DEEPGRAM))
        assertTrue("Key 读侧没走加密通道", "get() = readCredential(KEY_DEEPGRAM_API_KEY)" in prefs)
        assertTrue("Key 写侧没走加密通道", "set(value) = writeCredential(KEY_DEEPGRAM_API_KEY, value)" in prefs)
        // 默认值必须与请求层逐字一致：两处各写一份时，改一处就会「页面显示 nova-3、请求发 nova-2」
        assertEquals(DeepgramRequest.DEFAULT_MODEL, Prefs.DEFAULT_DEEPGRAM_MODEL)
        assertEquals(DeepgramRequest.DEFAULT_LANGUAGE, Prefs.DEFAULT_DEEPGRAM_LANGUAGE)
        assertEquals("nova-3", Prefs.DEFAULT_DEEPGRAM_MODEL)
        assertEquals("zh", Prefs.DEFAULT_DEEPGRAM_LANGUAGE)

        val src = TestSources.codeSource("DeepgramAsrClient.kt")
        val prep = TestSources.blockAfter(src, "override fun prepare")
        assertTrue("Key 必须取自 prefs.deepgramApiKey", "prefs.deepgramApiKey" in prep)
        assertTrue("模型必须取自 prefs.deepgramModel", "prefs.deepgramModel" in prep)
        assertTrue("语言必须取自 prefs.deepgramLanguage", "prefs.deepgramLanguage" in prep)
        assertTrue("请求体必须是 WAV（基类交上来的那份）", "DeepgramRequest.request(model, language, apiKey, wav)" in src)
        assertTrue("日志必须只记长度", "keyLen=" in prep)
        assertFalse(
            "日志里出现了 Key 原文",
            Regex("""\$\{prefs\.deepgramApiKey\}""").containsMatchIn(src),
        )
    }

    @Test
    fun `工厂八分支齐全且备份白名单含 Deepgram 三键`() {
        val factory = TestSources.codeSource("SpeechClients.kt")
        for (impl in listOf(
            "VoiceBackend.XF -> XfIatClient(prefs, onState, onMessage)",
            "VoiceBackend.OAI -> OpenAiAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.ALI -> AliAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.TENCENT -> TencentAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.AZURE -> AzureAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.BAIDU -> BaiduAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.DEEPGRAM -> DeepgramAsrClient(prefs, onState, onMessage)",
            "else -> AsrClient(prefs, onState, onMessage)",
        )) {
            assertTrue("工厂缺少分支：$impl", impl in factory)
        }

        val prefs = TestSources.codeSource("Prefs.kt")
        val export = TestSources.blockAfter(prefs, "internal fun exportForBackup")
        assertTrue("Key 没进导出白名单", "putCredential(KEY_DEEPGRAM_API_KEY)" in export)
        assertTrue("模型名没进导出白名单", "put(KEY_DEEPGRAM_MODEL, deepgramModel)" in export)
        assertTrue("语言码没进导出白名单", "put(KEY_DEEPGRAM_LANGUAGE, deepgramLanguage)" in export)

        val import = TestSources.blockAfter(prefs, "internal fun importFromBackup")
        assertTrue("模型名没进导入白名单", "KEY_DEEPGRAM_MODEL -> asString(v)" in import)
        assertTrue("语言码没进导入白名单", "KEY_DEEPGRAM_LANGUAGE -> asString(v)" in import)
        assertTrue(
            "Key 空串必须按忽略处理（否则会删掉本机可用凭据）",
            "KEY_DEEPGRAM_API_KEY ->" in import,
        )
        val configured = TestSources.blockAfter(prefs, "fun voiceConfigured()")
        assertTrue(
            "voiceConfigured 没认 Deepgram（Key）",
            "VoiceBackend.DEEPGRAM -> deepgramApiKey.cleanCredential().isNotEmpty()" in configured,
        )
    }

    @Test
    fun `状态条两处标签都认 Deepgram`() {
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue(
            "在线标签没认 Deepgram（会拿自建服务端的 host:port 拼「已连接」）",
            "VoiceBackend.DEEPGRAM -> TEXT_DEEPGRAM_ONLINE" in
                TestSources.blockAfter(ime, "private fun renderLink"),
        )
        assertTrue(
            "离线提示没认 Deepgram",
            "VoiceBackend.DEEPGRAM -> TEXT_DEEPGRAM_OFFLINE" in
                TestSources.blockAfter(ime, "private fun switchToVoiceKeyboard"),
        )
        assertTrue("在线标签常量缺失", "const val TEXT_DEEPGRAM_ONLINE" in ime)
        assertTrue("离线提示常量缺失", "const val TEXT_DEEPGRAM_OFFLINE" in ime)
    }

    @Test
    fun `语音测试前置校验点名 Deepgram 缺件`() {
        assertEquals(
            "Deepgram 配置不全，缺少 API Key",
            VoiceTestPreflight.check(VoiceBackend.DEEPGRAM, "", "", "", "192.168.1.3"),
        )
        assertNull(
            "Key 有了就放行（模型 / 语言有默认值）",
            VoiceTestPreflight.check(
                VoiceBackend.DEEPGRAM, "", "", "", "192.168.1.3",
                deepgramApiKey = "dg",
            ),
        )
        val selfTest = TestSources.codeSource("VoiceSelfTest.kt")
        assertTrue(
            "Deepgram Key 没传进前置校验",
            "deepgramApiKey = prefs.deepgramApiKey" in TestSources.blockAfter(selfTest, "fun start()"),
        )
    }

    @Test
    fun `页面 Deepgram 卡走掩码且只在该后端下写盘`() {
        val page = TestSources.codeSource("VoiceSettingsActivity.kt")
        val load = TestSources.blockAfter(page, "private fun loadPrefs")
        assertTrue("Key 回填没走掩码", "setCredentialText(editDeepgramApiKey, prefs.deepgramApiKey)" in load)
        assertTrue("模型名没回填", "editDeepgramModel.setText(prefs.deepgramModel)" in load)
        assertTrue("语言码没回填", "editDeepgramLanguage.setText(prefs.deepgramLanguage)" in load)

        val save = TestSources.blockAfter(page, "private fun persistInputs")
        assertTrue("Key 有效取值没走 credentialText", "val deepgramKeyValue = credentialText(editDeepgramApiKey)" in save)
        assertFalse(
            "保存直接写回了输入框文本（掩码会被写坏）",
            "prefs.deepgramApiKey = editDeepgramApiKey.text.toString()" in save,
        )
        assertTrue("Key 没写回", "prefs.deepgramApiKey = deepgramKeyValue" in save)
        assertTrue("模型名没写回", "prefs.deepgramModel = deepgramModelValue" in save)
        assertTrue("语言码没写回", "prefs.deepgramLanguage = deepgramLanguageValue" in save)
        assertTrue("写盘必须只发生在 deepgram 分支", "if (backend == VoiceBackend.DEEPGRAM) {" in save)
        for (field in listOf("editDeepgramApiKey", "editDeepgramModel", "editDeepgramLanguage")) {
            assertTrue("$field 缺少必填校验", "$field.error = TEXT_DEEPGRAM_REQUIRED" in save)
        }
        assertTrue("保存日志必须只记长度", "deepgramKeyLen=" in save)
        assertFalse(
            "日志里出现了 Key 原文",
            Regex("""\$\{prefs\.deepgramApiKey\}""").containsMatchIn(page),
        )

        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_voice_settings.xml",
            "app/src/main/res/layout/activity_voice_settings.xml",
        )
        assertTrue(
            "card_deepgram_credentials 必须默认 GONE",
            Regex("""android:id="@\+id/card_deepgram_credentials"\s+android:visibility="gone"""")
                .containsMatchIn(layout),
        )
        assertTrue(
            "Deepgram Key 必须是 password 型输入框",
            Regex("""android:id="@\+id/edit_deepgram_api_key"[^>]*android:inputType="textPassword"""")
                .containsMatchIn(layout),
        )
    }
}
