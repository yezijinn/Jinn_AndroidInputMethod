package com.jinn.inputmethod

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenAI 兼容 Provider 的纯函数守卫：URL 规范化、Prompt 模板、可选参数、Custom JSON 覆盖、
 * 自定义 Headers 与多路径响应解析。
 *
 * 这一家的自由度全部落在「配置 → 请求」的组装上，而组装一旦错了，真机上只会看到一个 4xx/5xx
 * 或者「结果为空」，从现象完全看不出是哪一项配错 —— 所以每一层都在这里钉死。
 */
class OpenAiTranslatorTest {

    private val config = OpenAiConfig(
        baseUrl = "https://api.openai.com/v1",
        apiKey = API_KEY,
        model = MODEL,
    )
    private val translator = OpenAiTranslator(config)

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun bodyJson(config: OpenAiConfig, text: String = "你好"): JSONObject =
        OpenAiTranslator.buildBody(config, text)

    // ── URL 规范化 ──────────────────────────────────────────

    @Test
    fun `joinUrl：v1 与 v1斜杠与无路径三种写法`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiTranslator.joinUrl("https://api.openai.com/v1", "/chat/completions")?.toString(),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiTranslator.joinUrl("https://api.openai.com/v1/", "/chat/completions")?.toString(),
        )
        // 不带 /v1 的服务：按用户填的路径拼，不擅自补 /v1
        assertEquals(
            "https://api.openai.com/chat/completions",
            OpenAiTranslator.joinUrl("https://api.openai.com", "/chat/completions")?.toString(),
        )
    }

    @Test
    fun `joinUrl：自定义路径与自定义端点路径都保留`() {
        assertEquals(
            "https://gw.example.com/api/openai/v1/chat/completions",
            OpenAiTranslator.joinUrl("https://gw.example.com/api/openai/v1", "/chat/completions")?.toString(),
        )
        // 协议骨架可换：路径整段可配（Responses 之类只需改配置，不改代码）
        assertEquals(
            "https://gw.example.com/v1/responses",
            OpenAiTranslator.joinUrl("https://gw.example.com/v1", "/responses")?.toString(),
        )
    }

    @Test
    fun `joinUrl：用户把端点整段粘进 Base URL 时不会重复拼接`() {
        assertEquals(
            "https://host/v1/chat/completions",
            OpenAiTranslator.joinUrl("https://host/v1/chat/completions", "/chat/completions")?.toString(),
        )
        assertEquals(
            "https://host/v1/chat/completions",
            OpenAiTranslator.joinUrl("https://host/v1/chat/completions/", "/chat/completions")?.toString(),
        )
    }

    /**
     * L-218 回归守卫：端点整段粘贴**且带 query** 时（Azure OpenAI 的 `?api-version=…` 是必填），
     * 老实现用字符串后缀匹配剥尾部 —— query 让后缀对不上，拼成 `…/chat/completions/chat/completions`。
     */
    @Test
    fun `joinUrl：整段粘贴且带 query 时不重复拼接、query 保留`() {
        assertEquals(
            "https://x.openai.azure.com/openai/deployments/d/chat/completions?api-version=2024-02-01",
            OpenAiTranslator.joinUrl(
                "https://x.openai.azure.com/openai/deployments/d/chat/completions?api-version=2024-02-01",
                "",
            )?.toString(),
        )
        assertEquals(
            "https://host/v1/chat/completions?api-version=2024-02-01",
            OpenAiTranslator.joinUrl(
                "https://host/v1/chat/completions?api-version=2024-02-01",
                "/chat/completions",
            )?.toString(),
        )
    }

    @Test
    fun `joinUrl：自定义 path 自带 query 时走 query 而不是被编码进路径`() {
        val url = OpenAiTranslator.joinUrl("https://host/v1", "/chat/completions?api-version=2024-02-01")!!
        assertEquals("/v1/chat/completions", url.encodedPath)
        assertEquals("api-version=2024-02-01", url.encodedQuery)
        // `?` 不能被编码进路径（那会 404）
        assertFalse("路径里不能出现 %3F：${url.encodedPath}", url.encodedPath.contains("%3F"))
    }

    @Test
    fun `joinUrl：缺 scheme 补 https；空串与乱填返回 null`() {
        val url = OpenAiTranslator.joinUrl("api.openai.com/v1", "/chat/completions")!!
        assertEquals("https", url.scheme)
        assertEquals("api.openai.com", url.host)
        assertEquals("/v1/chat/completions", url.encodedPath)

        assertNull(OpenAiTranslator.joinUrl("", "/chat/completions"))
        assertNull(OpenAiTranslator.joinUrl("   ", "/chat/completions"))
        assertNull(OpenAiTranslator.joinUrl("http://", "/chat/completions"))
    }

    @Test
    fun `modelsUrl：默认 models 路径，也可自定义`() {
        assertEquals(
            "https://host/v1/models",
            OpenAiTranslator.modelsUrl("https://host/v1", "/models")?.toString(),
        )
        assertEquals(
            "https://host/api/model-list",
            OpenAiTranslator.modelsUrl("https://host/api", "/model-list")?.toString(),
        )
    }

    // ── Headers ─────────────────────────────────────────────

    @Test
    fun `parseHeaders：一行一条，忽略注释与空行`() {
        val raw = "# 说明行\nX-Custom: 1\n\n  X-Other :two words  \n没有冒号的行"
        assertEquals(
            listOf("X-Custom" to "1", "X-Other" to "two words"),
            OpenAiTranslator.parseHeaders(raw),
        )
    }

    @Test
    fun `parseHeaders：Authorization 一律跳过（它由 API Key 字段独占）`() {
        val raw = "Authorization: Bearer forge-me\nAUTHORIZATION: also-me\nX-Ok: 1"
        assertEquals(listOf("X-Ok" to "1"), OpenAiTranslator.parseHeaders(raw))
    }

    @Test
    fun `parseHeaders：值含中文或不可见字符时丢弃（OkHttp 对值同样校验）`() {
        // 探针实测：值里出现 U+00A0（NBSP）/ U+200B（ZWSP）时 `header()` 抛 IllegalArgumentException，
        // trim() 拦不住它们（trim 只删 ≤ U+0020），而这类字符从网页复制时极常见
        val raw = "X-A: ok\nX-B: 你好\nX-C: has\u00A0nbsp\nX-D: has\u200Bzwsp"
        assertEquals(listOf("X-A" to "ok"), OpenAiTranslator.parseHeaders(raw))
    }

    @Test
    fun `OpenAiConfig 的 toString 不含 Key`() {
        // data class 的默认 toString 会带出 apiKey：一行日志就能把它写进可外传的诊断包
        val s = config.toString()
        assertFalse("toString 不能带出 Key：$s", s.contains(API_KEY))
        assertTrue("应显示打码标记：$s", s.contains("apiKey=***"))
    }

    @Test
    fun `parseHeaders：非法头名被丢弃（中文名-含空格名会让 header() 抛异常）`() {
        // 非法名会让 `Request.Builder.header` 抛 IllegalArgumentException，被 TranslationClient
        // 兜成 PARAM → 提示「请检查语言设置」，与真实原因（自定义 Headers 写错）完全无关
        // （2026-09-30 审查发现）
        val raw = "令牌: v\nX Foo: v\nX-Good-Name_1: v\nX-Bad@: v"
        assertEquals(listOf("X-Good-Name_1" to "v"), OpenAiTranslator.parseHeaders(raw))
    }

    // ── Prompt 模板 ─────────────────────────────────────────

    @Test
    fun `applyTemplate：四个变量都替换`() {
        val now = Date(1759218600000L) // 2026-09-30 前后，仅用于固定日期文本
        val out = OpenAiTranslator.applyTemplate(
            "把 {{text}} 翻成 {{target_language}}（源：{{source_language}}，日期 {{date}}）",
            text = "hello",
            target = "简体中文",
            now = now,
        )
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        assertEquals("把 hello 翻成 简体中文（源：自动检测，日期 $date）", out)
    }

    @Test
    fun `applyTemplate：认不出的变量原样保留`() {
        assertEquals(
            "值={{unknown}}",
            OpenAiTranslator.applyTemplate("值={{unknown}}", text = "x", target = "中文"),
        )
    }

    @Test
    fun `applyTemplate：正文里的同名占位符不会被二次替换`() {
        // 正文**最后**注入（2026-09-30 审查发现）：否则用户正文里的 {{target_language}} /
        // {{date}} 会被后续 replace 改写，发给模型的原文与屏幕上的原文不再一致
        // —— 提示词模板、配置文档、代码片段都很常见这种正文
        val text = "把 {{target_language}} 与 {{date}} 这两个占位符留着"
        assertEquals(
            "翻译：$text",
            OpenAiTranslator.applyTemplate("翻译：{{text}}", text = text, target = "English"),
        )
    }

    // ── 请求体组装 ──────────────────────────────────────────

    @Test
    fun `buildBody：model + system + user（默认提示词逐字展开）`() {
        val json = bodyJson(config, "你好世界")
        assertEquals(MODEL, json.getString("model"))
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(OpenAiTranslator.DEFAULT_SYSTEM_PROMPT, messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(
            "请将以下文本翻译成简体中文，只返回译文：\n你好世界",
            messages.getJSONObject(1).getString("content"),
        )
    }

    @Test
    fun `buildBody：可选参数留空则完全不发送`() {
        val json = bodyJson(config)
        assertFalse("temperature 不该出现", json.has("temperature"))
        assertFalse("top_p 不该出现", json.has("top_p"))
        assertFalse("max_tokens 不该出现", json.has("max_tokens"))
    }

    @Test
    fun `buildBody：填了才发送，整数写成整数形态`() {
        val json = bodyJson(config.copy(temperature = "0", topP = "1.0", maxTokens = "1024"))
        assertEquals(0.0, json.getDouble("temperature"), 0.0001)
        assertEquals(1.0, json.getDouble("top_p"), 0.0001)
        assertEquals(1024L, json.getLong("max_tokens"))
        // 文本形态必须是 1024 而不是 1024.0（部分网关对数字形态挑剔）
        assertTrue(json.toString().contains("\"max_tokens\":1024"))
    }

    @Test
    fun `buildBody：超大整数不得饱和成 Long_MAX_VALUE`() {
        // 收窄成 Long 只对精确可表示的整数范围做：`toLong()` 对 1e30 会饱和成
        // 9223372036854775807，把用户填的数悄悄改掉（2026-10-01 审查 L-226）
        val json = bodyJson(config.copy(maxTokens = "1e30"))
        val text = json.toString()
        assertFalse("不得写成饱和后的 Long.MAX_VALUE：$text", "9223372036854775807" in text)
        assertEquals(1.0e30, json.getDouble("max_tokens"), 1.0e24)
    }

    @Test
    fun `buildBody：非法数值等同留空（不发送）`() {
        val json = bodyJson(config.copy(temperature = "abc", topP = "NaN", maxTokens = "  "))
        assertFalse(json.has("temperature"))
        assertFalse(json.has("top_p"))
        assertFalse(json.has("max_tokens"))
        assertNull(OpenAiTranslator.numberOrNull("NaN"))
        assertNull(OpenAiTranslator.numberOrNull("Infinity"))
    }

    @Test
    fun `buildBody：自定义 JSON 同名覆盖、异名追加（最高优先级）`() {
        val json = bodyJson(
            config.copy(
                temperature = "0",
                extraJson = """{"temperature":0.7,"enable_thinking":false,"reasoning_effort":"low"}""",
            ),
        )
        // 覆盖标准参数
        assertEquals(0.7, json.getDouble("temperature"), 0.0001)
        // 追加厂商私有参数（App 不为它写分支）
        assertFalse(json.getBoolean("enable_thinking"))
        assertEquals("low", json.getString("reasoning_effort"))
    }

    @Test
    fun `mergeExtraJson：JSON 写错只返回 false，不破坏已有字段`() {
        val body = JSONObject().put("model", "m")
        assertFalse(OpenAiTranslator.mergeExtraJson(body, "{不是 JSON"))
        assertEquals("m", body.getString("model"))

        assertTrue(OpenAiTranslator.mergeExtraJson(body, ""))
        assertTrue(OpenAiTranslator.mergeExtraJson(body, """{"a":1}"""))
        assertEquals(1, body.getInt("a"))
    }

    // ── 请求形态 ────────────────────────────────────────────

    @Test
    fun `请求形态：POST + Bearer + 自定义路径与自定义头`() {
        val request = OpenAiTranslator(
            config.copy(
                baseUrl = "https://gw.example.com",
                chatPath = "/v2/chat/completions",
                extraHeaders = "X-Title: Jinn\nAuthorization: Bearer should-be-ignored",
            ),
        ).buildRequest("你好", TranslationLanguage.CHINESE)

        assertEquals("POST", request.method)
        assertEquals("gw.example.com", request.url.host)
        assertEquals("/v2/chat/completions", request.url.encodedPath)
        // API Key 独占 Authorization，自定义头里的同名项被忽略
        assertEquals("Bearer $API_KEY", request.header("Authorization"))
        assertEquals("Jinn", request.header("X-Title"))
    }

    @Test
    fun `callTimeoutSec 取自配置（大模型首字慢）`() {
        assertEquals(60, OpenAiTranslator(config).callTimeoutSec)
        assertEquals(120, OpenAiTranslator(config.copy(timeoutSec = 120)).callTimeoutSec)
    }

    // ── 响应解析 ────────────────────────────────────────────

    @Test
    fun `extractByPath：默认路径与常见变体`() {
        val chat = JSONObject("""{"choices":[{"message":{"content":"  Hello  "}}]}""")
        assertEquals("  Hello  ", OpenAiTranslator.extractByPath(chat, "choices[0].message.content"))
        assertEquals("  Hello  ", OpenAiTranslator.extractByPath(chat, "$.choices[0].message.content"))

        val legacy = JSONObject("""{"choices":[{"text":"old-style"}]}""")
        assertEquals("old-style", OpenAiTranslator.extractByPath(legacy, "choices[0].text"))

        val responses = JSONObject("""{"output":[{"content":[{"text":"resp-style"}]}]}""")
        assertEquals(
            "resp-style",
            OpenAiTranslator.extractByPath(responses, "output[0].content[0].text"),
        )

        val flat = JSONObject("""{"output_text":"flat-style"}""")
        assertEquals("flat-style", OpenAiTranslator.extractByPath(flat, "output_text"))

        // `[]` = 首元素
        assertEquals("Hello", OpenAiTranslator.extractByPath(chat, "choices[].message.content")?.trim())
    }

    @Test
    fun `extractByPath：content 为数组时取第一段文本；路径不存在给 null`() {
        val json = JSONObject("""{"choices":[{"message":{"content":[{"type":"text","text":"段落"}]}}]}""")
        assertEquals("段落", OpenAiTranslator.extractByPath(json, "choices[0].message.content"))
        assertNull(OpenAiTranslator.extractByPath(json, "choices[0].message.missing"))
        assertNull(OpenAiTranslator.extractByPath(json, "nope[2].text"))
    }

    @Test
    fun `parseResponse：200 正常解析并按配置路径取值`() {
        val body = """{"choices":[{"message":{"content":"  Hello World  "}}]}"""
        assertEquals(TranslationOutcome.Ok("Hello World"), translator.parseResponse(200, body))

        // 换协议只改配置：同一份响应换个路径就能取到
        val flat = OpenAiTranslator(config.copy(responsePath = "output_text"))
        assertEquals(
            TranslationOutcome.Ok("flat"),
            flat.parseResponse(200, """{"output_text":"flat"}"""),
        )
    }

    @Test
    fun `parseResponse：空结果、error 对象、非 JSON`() {
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"choices":[]}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"choices":[{"message":{"content":"   "}}]}"""),
        )
        // content 是显式 JSON null：不能变成字面量 "null" 提交（asText 用 `opt(...) as? String` 的原因）
        assertEquals(
            TranslationOutcome.Fail(TranslationError.EMPTY),
            translator.parseResponse(200, """{"choices":[{"message":{"content":null}}]}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, """{"error":{"message":"rate limited"}}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, "<html>oops</html>"),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.SERVER),
            translator.parseResponse(200, null),
        )
    }

    @Test
    fun `parseResponse：HTTP 错误码（404 归参数）`() {
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(401, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.AUTH), translator.parseResponse(403, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), translator.parseResponse(400, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), translator.parseResponse(404, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.PARAM), translator.parseResponse(422, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.QUOTA), translator.parseResponse(429, null))
        // 402 = 余额不足：相当多兼容网关用它表达欠费，落到 else 会显示「服务异常」，用户无从下手
        assertEquals(TranslationOutcome.Fail(TranslationError.QUOTA), translator.parseResponse(402, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.TIMEOUT), translator.parseResponse(408, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), translator.parseResponse(500, null))
        assertEquals(TranslationOutcome.Fail(TranslationError.SERVER), translator.parseResponse(503, null))
    }

    @Test
    fun `parseModels：data 里的 id，缺失时回落 name`() {
        val body = """{"object":"list","data":[""" +
            """{"id":"gpt-5.6"},{"id":"qwen3.8-flash-free"},{"name":"deepseek-v4"}]}"""
        assertEquals(
            listOf("gpt-5.6", "qwen3.8-flash-free", "deepseek-v4"),
            OpenAiTranslator.parseModels(body),
        )
        assertTrue(OpenAiTranslator.parseModels("not json").isEmpty())
        assertTrue(OpenAiTranslator.parseModels(null).isEmpty())
    }

    @Test
    fun `parseModels：id 为 JSON null 时回落 name，而不是字面量 null`() {
        val body = """{"data":[{"id":null,"name":"gpt-4o-mini"},{"id":"m2"}]}"""
        assertEquals(listOf("gpt-4o-mini", "m2"), OpenAiTranslator.parseModels(body))
    }

    @Test
    fun `finish_reason 为 length 时不算成功（半截译文不得上屏）`() {
        // 模型输出被 max_tokens（或网关自带上限）砍断时，响应仍是 2xx + 有 content，
        // 旧实现会把它当完整译文提交 —— 用户拿到半句话且毫无提示（2026-10-02 修复 L-484）
        val truncated = """{"choices":[{"message":{"content":"这是一句被砍断的译"},"finish_reason":"length"}]}"""
        assertEquals(TranslationOutcome.Fail(TranslationError.TRUNCATED), translator.parseResponse(200, truncated))
        // finish_reason=stop（正常结束）与缺失该字段的老网关都不能受影响
        val normal = """{"choices":[{"message":{"content":"完整译文"},"finish_reason":"stop"}]}"""
        assertEquals(TranslationOutcome.Ok("完整译文"), translator.parseResponse(200, normal))
        val noField = """{"choices":[{"message":{"content":"老网关译文"}}]}"""
        assertEquals(TranslationOutcome.Ok("老网关译文"), translator.parseResponse(200, noField))
    }

    private companion object {
        const val API_KEY = "sk-test-0123456789abcdef"
        const val MODEL = "gpt-4o-mini"
    }
}
