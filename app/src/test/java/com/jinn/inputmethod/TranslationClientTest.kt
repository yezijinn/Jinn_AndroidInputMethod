package com.jinn.inputmethod

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 选择与归一守卫。
 *
 * 「未配置」是用户第一次使用时的常态：判错（没填 Key 也当成已配置）会让第一次点击翻译变成
 * 一次必然失败的请求；归一错（未知语言码）会被服务端直接拒（Azure 400 / 百度 58001 / 阿里云 InvalidParameter）。
 * 默认服务方是**阿里云**（用户 2026-09-30 指定），未知取值必须回落到它。
 */
class TranslationClientTest {

    /** 只在被测参数上取值，其余留空 —— 避免每个用例都写 8 个位置参数 */
    private fun provider(
        id: TranslationProviderId,
        azureKey: String = "",
        azureRegion: String = "",
        baiduAppId: String = "",
        baiduSecret: String = "",
        aliyunKeyId: String = "",
        aliyunKeySecret: String = "",
        deeplKey: String = "",
        baiduLlmAppId: String = "",
        baiduLlmApiKey: String = "",
        openAi: OpenAiConfig = OpenAiConfig(),
    ): TranslationProvider? = TranslationClient.providerOf(
        id, azureKey, azureRegion, baiduAppId, baiduSecret, aliyunKeyId, aliyunKeySecret, deeplKey,
        baiduLlmAppId, baiduLlmApiKey, openAi,
    )

    @Test
    fun `阿里云必须 AccessKey ID 与 Secret 同时存在（默认服务方）`() {
        assertNull(provider(TranslationProviderId.ALIYUN))
        assertNull(provider(TranslationProviderId.ALIYUN, aliyunKeyId = "LTAI"))
        assertNull(provider(TranslationProviderId.ALIYUN, aliyunKeySecret = "secret"))
        assertNull(provider(TranslationProviderId.ALIYUN, aliyunKeyId = "  ", aliyunKeySecret = "  "))
        assertTrue(
            "ID 与 Secret 都有才组 Provider",
            provider(TranslationProviderId.ALIYUN, aliyunKeyId = "LTAI", aliyunKeySecret = "secret")
                is AliyunTranslator,
        )
    }

    @Test
    fun `Azure 未填 Key 视为未配置`() {
        assertNull(provider(TranslationProviderId.AZURE))
        assertNull(provider(TranslationProviderId.AZURE, azureKey = "   "))
        assertTrue(
            "填了 Key 就应组出 Azure Provider",
            provider(TranslationProviderId.AZURE, azureKey = "key") is AzureTranslator,
        )
    }

    @Test
    fun `百度必须 AppID 与 SecretKey 同时存在`() {
        assertNull(provider(TranslationProviderId.BAIDU, baiduAppId = "app"))
        assertNull(provider(TranslationProviderId.BAIDU, baiduSecret = "secret"))
        assertNull(provider(TranslationProviderId.BAIDU, baiduAppId = "  ", baiduSecret = "  "))
        assertTrue(
            "AppID 与 SecretKey 都有才组 Provider",
            provider(TranslationProviderId.BAIDU, baiduAppId = "app", baiduSecret = "secret") is BaiduTranslator,
        )
    }

    @Test
    fun `百度大模型必须 APPID 与 API Key 同时存在`() {
        assertNull(provider(TranslationProviderId.BAIDU_LLM))
        assertNull(provider(TranslationProviderId.BAIDU_LLM, baiduLlmAppId = "app"))
        assertNull(provider(TranslationProviderId.BAIDU_LLM, baiduLlmApiKey = "key"))
        assertNull(provider(TranslationProviderId.BAIDU_LLM, baiduLlmAppId = "  ", baiduLlmApiKey = "  "))
        assertTrue(
            "APPID 与 API Key 都有才组 Provider",
            provider(
                TranslationProviderId.BAIDU_LLM,
                baiduLlmAppId = "app",
                baiduLlmApiKey = "key",
            ) is BaiduLlmTranslator,
        )
    }

    @Test
    fun `DeepL 只需一个 API Key`() {
        assertNull(provider(TranslationProviderId.DEEPL))
        assertNull(provider(TranslationProviderId.DEEPL, deeplKey = "  "))
        assertTrue(
            "填了 Key 就应组出 DeepL Provider",
            provider(TranslationProviderId.DEEPL, deeplKey = "k:fx") is DeepLTranslator,
        )
    }

    @Test
    fun `OpenAI 兼容必须 API Key 与模型名同时存在`() {
        assertNull(provider(TranslationProviderId.OPENAI))
        assertNull(provider(TranslationProviderId.OPENAI, openAi = OpenAiConfig(apiKey = "sk-x")))
        assertNull(provider(TranslationProviderId.OPENAI, openAi = OpenAiConfig(model = "gpt-4o-mini")))
        assertNull(
            provider(
                TranslationProviderId.OPENAI,
                openAi = OpenAiConfig(apiKey = "  ", model = "  "),
            ),
        )
        assertTrue(
            "Key 与模型名都有才组 Provider",
            provider(
                TranslationProviderId.OPENAI,
                openAi = OpenAiConfig(apiKey = "sk-x", model = "gpt-4o-mini"),
            ) is OpenAiTranslator,
        )
    }

    @Test
    fun `OpenAI 兼容的 Base URL 乱填等同未配置（不把非法地址交给网络层）`() {
        assertNull(
            provider(
                TranslationProviderId.OPENAI,
                openAi = OpenAiConfig(baseUrl = "   ", apiKey = "sk-x", model = "m"),
            ),
        )
        assertTrue(
            "带自定义路径的网关 + 自定义对话路径也要能用",
            provider(
                TranslationProviderId.OPENAI,
                openAi = OpenAiConfig(
                    baseUrl = "https://gw.example.com/api/openai/v1",
                    apiKey = "sk-x",
                    model = "m",
                    chatPath = "/v2/chat",
                ),
            ) is OpenAiTranslator,
        )
    }

    @Test
    fun `四家的凭据互不串用`() {
        val others = "azure-key"
        assertNull(
            "选了阿里云时，不能拿别家的凭据当已配置",
            provider(
                TranslationProviderId.ALIYUN,
                azureKey = others, baiduAppId = others, baiduSecret = others, deeplKey = others,
            ),
        )
        assertNull(
            "选了百度时，不能拿阿里云的 AK 当已配置",
            provider(
                TranslationProviderId.BAIDU,
                aliyunKeyId = "LTAI", aliyunKeySecret = "secret", deeplKey = others,
            ),
        )
        assertNull(
            "选了 DeepL 时，不能拿百度凭据当已配置",
            provider(TranslationProviderId.DEEPL, baiduAppId = "app", baiduSecret = "secret"),
        )
        assertNull(
            "选了 Azure 时，不能拿 DeepL Key 当已配置",
            provider(TranslationProviderId.AZURE, deeplKey = "k:fx"),
        )
    }

    @Test
    fun `Provider 归一：未知 id 回落阿里云`() {
        assertEquals(TranslationProviderId.ALIYUN, TranslationProviderId.of(null))
        assertEquals(TranslationProviderId.ALIYUN, TranslationProviderId.of("google"))
        assertEquals(TranslationProviderId.ALIYUN, TranslationProviderId.DEFAULT)
        assertEquals(TranslationProviderId.ALIYUN, TranslationProviderId.of("aliyun"))
        // 旧配置取值必须继续有效（老用户升上来不该被改掉选择）
        assertEquals(TranslationProviderId.AZURE, TranslationProviderId.of("azure"))
        assertEquals(TranslationProviderId.BAIDU, TranslationProviderId.of("baidu"))
        assertEquals(TranslationProviderId.BAIDU_LLM, TranslationProviderId.of("baidu_llm"))
        assertEquals(TranslationProviderId.DEEPL, TranslationProviderId.of("deepl"))
        assertEquals(TranslationProviderId.OPENAI, TranslationProviderId.of("openai"))
    }

    @Test
    fun `语言归一：未知取值回落英文`() {
        assertEquals(TranslationLanguage.ENGLISH, TranslationLanguage.of(null))
        assertEquals(TranslationLanguage.ENGLISH, TranslationLanguage.DEFAULT)
        assertEquals(TranslationLanguage.ENGLISH, TranslationLanguage.of("klingon"))
        assertEquals(TranslationLanguage.CHINESE, TranslationLanguage.of("CHINESE"))
    }

    @Test
    fun `四套语言码都非空且互不重复`() {
        val sets = mapOf(
            "Azure" to TranslationLanguage.entries.map { it.azureCode },
            "百度" to TranslationLanguage.entries.map { it.baiduCode },
            "阿里云" to TranslationLanguage.entries.map { it.aliyunCode },
            "DeepL" to TranslationLanguage.entries.map { it.deeplCode },
        )
        for ((name, codes) in sets) {
            assertTrue("$name 语言码有空值", codes.none { it.isBlank() })
            assertEquals("$name 语言码重复", codes.size, codes.toSet().size)
        }
    }

    @Test
    fun `每个失败分类都有可展示的中文提示`() {
        for (error in TranslationError.entries) {
            assertTrue("$error 缺少提示文案", error.message.isNotBlank())
        }
    }

    // ── 失败体的结构化摘要（只进日志）─────────────────────────

    @Test
    fun `摘要只给长度与白名单错误码`() {
        assertEquals("body=空", TranslationClient.errorSummary(null))
        assertEquals("body=空", TranslationClient.errorSummary("   "))
        assertTrue(TranslationClient.errorSummary("<html>502</html>").startsWith("body=非JSON(len="))

        val openAi = TranslationClient.errorSummary("""{"error":{"code":"invalid_api_key"}}""")
        assertTrue(openAi, openAi.startsWith("body=len") && openAi.endsWith("code=invalid_api_key"))
        assertTrue(TranslationClient.errorSummary("""{"error_code":"54001"}""").endsWith("code=54001"))
        assertTrue(TranslationClient.errorSummary("""{"Code":"10004"}""").endsWith("code=10004"))
        // 没有错误码字段时只有长度
        assertTrue(TranslationClient.errorSummary("""{"a":"bbb"}""").startsWith("body=len"))
    }

    /**
     * 这条是隐私面守卫：网关常把提交的 Key **回显**在错误消息里（OpenAI 家族的标准形态），
     * 而日志会落盘并随「导出诊断包」整体外发 —— 摘要里绝不允许出现 message 自由文本。
     */
    @Test
    fun `摘要绝不含错误体里的自由文本（网关回显 Key 时不外泄）`() {
        val body = """{"error":{"code":"invalid_api_key",""" +
            """"message":"Incorrect API key provided: sk-proj-Ab12Cd34Ef56Gh78Ij90Kl."}}"""
        val summary = TranslationClient.errorSummary(body)
        assertFalse("摘要不能带出 Key：$summary", summary.contains("sk-proj"))
        assertFalse("摘要不能带出 message：$summary", summary.contains("Incorrect"))
        assertTrue("白名单错误码应保留：$summary", summary.contains("code=invalid_api_key"))
    }

    @Test
    fun `错误码字段本身含自由文本（空格-冒号）时一律丢弃，只留长度`() {
        val body = """{"error_code":"bad key: sk-abc def"}"""
        val summary = TranslationClient.errorSummary(body)
        assertFalse("含空格的错误码不是标识符，必须丢弃：$summary", summary.contains("sk-abc"))
        assertEquals("body=len${body.length}", summary)
    }

    // ── HTTPS 硬判据 ─────────────────────────────────────────

    /** 只用于「请求构造后被拦」的用例：返回一个明文 URL */
    private class InsecureProvider : TranslationProvider {
        override fun buildRequest(text: String, target: TranslationLanguage): Request =
            Request.Builder().url("http://example.com/translate").post("{}".toRequestBody()).build()

        override fun parseResponse(code: Int, body: String?): TranslationOutcome =
            TranslationOutcome.Fail(TranslationError.SERVER)
    }

    @Test
    fun `凭据里的不可见字符会被清洗（否则 header 抛异常 → 误报 PARAM）`() {
        // 探针实测：Key 里带 NBSP/ZWSP 时 OkHttp 的 header() 抛 IllegalArgumentException，
        // 被兜成 PARAM → 用户看到「请检查语言设置」，而真实原因肉眼不可见
        val deepl = provider(TranslationProviderId.DEEPL, deeplKey = "k\u00A0:fx\u200B") as DeepLTranslator
        assertEquals(
            "DeepL-Auth-Key k:fx",
            deepl.buildRequest("x", TranslationLanguage.ENGLISH).header("Authorization"),
        )
    }

    @Test
    fun `只含不可见字符的凭据等同未配置`() {
        assertNull(provider(TranslationProviderId.DEEPL, deeplKey = "\u00A0\u200B"))
        assertNull(provider(TranslationProviderId.ALIYUN, aliyunKeyId = "\u00A0", aliyunKeySecret = "s"))
        assertNull(provider(TranslationProviderId.OPENAI, openAi = OpenAiConfig(apiKey = "\u200B", model = "m")))
    }

    @Test
    fun `非 HTTPS 地址一律拒发（自定义 Base URL 写成 http 时）`() {
        var outcome: TranslationOutcome? = null
        TranslationClient.translate(InsecureProvider(), "hi", TranslationLanguage.ENGLISH) { outcome = it }
        // 早退路径在调用方线程**同步**回调（与 IO 线程路径不同，这条同时钉住线程契约）
        assertEquals(TranslationOutcome.Fail(TranslationError.INSECURE), outcome)
    }
}
