package com.jinn.inputmethod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-02 修复批的**行为守卫**（L-338 / L-419 / L-259 / L-344 / L-421 / L-347 / L-339 / L-343）。
 *
 * 这一批的共同点是「回退之后 JVM 单测依然全绿」或者「本来就该有行为用例却没有」——
 * 所以每条都钉**可判定的输入输出**，而不是源码子串（那是 L-341 / L-369 的起因）。
 */
class TranslationFixBatchTest {

    // ── L-338 / L-419：模板变量判据与容错写法 ─────────────────────

    @Test
    fun `容错写法（空格 大小写 全角空格）都算带 text 占位符`() {
        assertTrue(OpenAiTranslator.hasTextVar("请翻译：{{text}}"))
        assertTrue("带半角空格", OpenAiTranslator.hasTextVar("请翻译：{{ text }}"))
        assertTrue("大写", OpenAiTranslator.hasTextVar("请翻译：{{TEXT}}"))
        assertTrue("全角空格（中文输入法默认）", OpenAiTranslator.hasTextVar("请翻译：{{\u3000text\u3000}}"))
        assertTrue("NBSP", OpenAiTranslator.hasTextVar("请翻译：{{\u00A0text}}"))
        // 花括号本身的全角形态（2026-10-02 修复）：本 App 自己是中文 IME，`｛｝`（U+FF5B/U+FF5D）
        // 是打花括号的常见结果 —— 只认半角等于把 L-419 那个洞「开一半」
        assertTrue("全角花括号", OpenAiTranslator.hasTextVar("请翻译：｛｛text｝｝"))
        assertTrue("半角全角混用", OpenAiTranslator.hasTextVar("请翻译：{｛text}}"))
        assertFalse("不认识的变量不算", OpenAiTranslator.hasTextVar("请翻译：{{myvar}}"))
        assertFalse(OpenAiTranslator.hasTextVar("请翻译：把上面的内容译成英文"))
    }

    @Test
    fun `容错写法下原文只注入一次（兜底不再重复追加）`() {
        // 旧实现：展开走归一化、判据走原串 ⇒ 这里会得到「原文 + 原文」，并打一条假的「缺少 {{text}}」
        assertEquals(
            "翻译：HELLO",
            OpenAiTranslator.applyTemplateEnsuringText("翻译：{{ text }}", "", "HELLO", "中文"),
        )
        assertEquals(
            "翻译：HELLO",
            OpenAiTranslator.applyTemplateEnsuringText("翻译：{{\u3000text\u3000}}", "", "HELLO", "中文"),
        )
    }

    @Test
    fun `两个模板都没有占位符时仍然兜底追加原文`() {
        assertEquals(
            "把上面的内容译成中文\n\nHELLO",
            OpenAiTranslator.applyTemplateEnsuringText("把上面的内容译成中文", "", "HELLO", "中文"),
        )
    }

    // ── L-259 / L-344 / L-421：自定义 JSON 的键规则 ──────────────

    @Test
    fun `stream 按值判定：真才拦，false 与 null 放行`() {
        assertNotNull("stream:true 破坏客户端", OpenAiTranslator.extraKeyRejection("stream", true))
        assertNull("stream:false 无害（等价于不发）", OpenAiTranslator.extraKeyRejection("stream", false))
        assertNull("stream:null（删键语义）无害", OpenAiTranslator.extraKeyRejection("stream", JSONObject.NULL))
        assertNotNull("stream_options 同样", OpenAiTranslator.extraKeyRejection("stream_options", true))
        assertTrue(OpenAiTranslator.isValidExtraJson("""{"stream":false}"""))
        assertTrue(OpenAiTranslator.isValidExtraJson("""{"stream":null}"""))
        assertFalse(OpenAiTranslator.isValidExtraJson("""{"stream":true}"""))
    }

    @Test
    fun `必需键不可删、messages 不可覆盖`() {
        assertNotNull("删 model 会让请求体非法", OpenAiTranslator.extraKeyRejection("model", JSONObject.NULL))
        assertNotNull(OpenAiTranslator.extraKeyRejection("messages", JSONObject.NULL))
        assertNotNull("覆盖 messages 会丢掉原文", OpenAiTranslator.extraKeyRejection("messages", "x"))
        assertNull("model 允许覆盖（换模型是正当用法）", OpenAiTranslator.extraKeyRejection("model", "gpt-4o"))
        assertNull("max_tokens 允许删", OpenAiTranslator.extraKeyRejection("max_tokens", JSONObject.NULL))
        assertFalse(OpenAiTranslator.isValidExtraJson("""{"model":null}"""))
        assertFalse(OpenAiTranslator.isValidExtraJson("""{"messages":[{"role":"user","content":"x"}]}"""))
    }

    @Test
    fun `合并时被拒的键被跳过而不是整份丢弃`() {
        val body = JSONObject().put("model", "m").put("messages", "kept")
        val ok = OpenAiTranslator.mergeExtraJson(body, """{"stream":true,"messages":"evil","temperature":0.3}""")
        assertTrue("解析成功就该返回 true（拒绝单键不阻断其余合并）", ok)
        assertEquals("messages 未被覆盖", "kept", body.getString("messages"))
        assertFalse("stream 未被写入", body.has("stream"))
        assertEquals(0.3, body.getDouble("temperature"), 1e-9)
    }

    @Test
    fun `null 删键对普通参数仍然生效`() {
        val body = JSONObject().put("model", "m").put("max_tokens", 1024)
        OpenAiTranslator.mergeExtraJson(body, """{"max_tokens":null}""")
        assertFalse("max_tokens 已删", body.has("max_tokens"))
        assertEquals("model 未动", "m", body.getString("model"))
    }

    // ── L-347：Azure 区域归一 ────────────────────────────────────

    @Test
    fun `Azure 区域按门户形态归一（East Asia → eastasia）`() {
        assertEquals("eastasia", normalizeAzureRegion("East Asia"))
        assertEquals("eastasia", normalizeAzureRegion("EastAsia"))
        assertEquals("eastasia", normalizeAzureRegion("east-asia"))
        assertEquals("eastasia", normalizeAzureRegion("  EAST ASIA  "))
        assertEquals("global", normalizeAzureRegion("Global"))
        assertEquals("带 NBSP 的复制值", "eastasia", normalizeAzureRegion("East\u00A0Asia"))
        assertEquals("", normalizeAzureRegion(""))
    }

    // ── L-354：签名串必须等于 OkHttp 实发头 ──────────────────────

    @Test
    fun `阿里云签名串必须等于 OkHttp 实际发出的 Content-Type`() {
        // L-354：显式 `.header("Content-Type", …)` 会被 BridgeInterceptor 用
        // `body.contentType().toString()` **覆盖**（归一成带空格的 `; charset=utf-8`），
        // 两者必须逐字一致 —— 差一个空格即 SignatureDoesNotMatch，而它被归成 AUTH。
        val request = AliyunTranslator("ak", "sk").buildRequest("hi", TranslationLanguage.ENGLISH)
        assertEquals(
            "body 的 MediaType 形态就是实发头，签名串必须与它相同（L-354 会复现）",
            AliyunTranslator.CONTENT_TYPE,
            request.body?.contentType().toString(),
        )
        assertEquals(
            "2026-10-02 修复 L-486：不再显式设 Content-Type —— 它会被 BridgeInterceptor 覆盖（死代码），" +
                "签名串现在直接取自 body 的 MediaType（单一真相），两边再没有分叉的机会",
            null,
            request.header("Content-Type"),
        )
    }

    // ── L-299 / L-358：OpenAI 兼容的错误分类 ─────────────────────

    @Test
    fun `OpenAI 数字型错误码走与 HTTP 同一张表`() {
        val provider = OpenAiTranslator(OpenAiConfig(baseUrl = "https://x.test/v1", model = "m", apiKey = "k"))
        // 429 限流 / 401 认证 / 400 参数：此前一律归 SERVER（L-299）
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            provider.parseResponse(200, """{"error":{"code":429}}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            provider.parseResponse(200, """{"error":{"code":401}}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.PARAM),
            provider.parseResponse(200, """{"error":{"code":400}}"""),
        )
        // 非 2xx 也先读体内业务码（L-358）：欠费不该被压成「服务异常」
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            provider.parseResponse(429, """{"error":{"code":"insufficient_quota"}}"""),
        )
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            provider.parseResponse(403, """{"error":{"code":"invalid_api_key"}}"""),
        )
        // 非 2xx 且没有可识别业务码时仍回落状态码表
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            provider.parseResponse(429, "not json"),
        )
    }

    // ── 自己引入的回归与未达成 ──────────────────────────────────

    @Test
    fun `业务码只在更具体时覆盖状态码分类`() {
        val provider = OpenAiTranslator(OpenAiConfig(baseUrl = "https://x.test/v1", model = "m", apiKey = "k"))
        // 认不出的业务码不得挤掉状态码给出的更具体分类（此前会从 PARAM 退回 SERVER）
        assertEquals(
            TranslationOutcome.Fail(TranslationError.PARAM),
            provider.parseResponse(404, """{"error":{"message":"Invalid URL"}}"""),
        )
        // OpenAI 429 的真实形态：type = requests，code 为 null
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            provider.parseResponse(429, """{"error":{"type":"requests"}}"""),
        )
        // 空串的 code 不能盖掉可用的 type
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            provider.parseResponse(200, """{"error":{"code":"","type":"insufficient_quota"}}"""),
        )
        // 能认出的业务码仍然优先于状态码
        assertEquals(
            TranslationOutcome.Fail(TranslationError.AUTH),
            provider.parseResponse(500, """{"error":{"code":"invalid_api_key"}}"""),
        )
    }

    @Test
    fun `区域归一把全角空格等一切非字母数字剥掉`() {
        assertEquals("eastasia", normalizeAzureRegion("East\u3000Asia"))
        assertEquals("eastasia", normalizeAzureRegion("East\u2028Asia"))
        assertEquals("eastasia", normalizeAzureRegion("East\u2011Asia"))
        assertEquals("eastasia", normalizeAzureRegion("East—Asia"))
        assertEquals("westus2", normalizeAzureRegion("West US 2"))
        assertEquals("", normalizeAzureRegion("东亚"))  // 非 ASCII 字母被剥空 = 未填
    }

    @Test
    fun `错误摘要不记后缀型凭据（先判形态后截断）`() {
        val key = "01234567-89ab-cdef-0123-456789abcdef:fx"
        val summary = TranslationClient.errorSummary("""{"error":{"code":"$key"}}""")
        assertFalse("不该把 :fx 后缀的密钥片段记进摘要：$summary", summary.contains("01234567"))
        assertTrue("但仍要给出长度便于排障：$summary", summary.contains("body=len"))
    }

    // ── 原文范围 / 上限 / 自定义 JSON ─────────────────────────

    @Test
    fun `数字错误码带空白与浮点形态都能识别，非错误语义不误判`() {
        val provider = OpenAiTranslator(OpenAiConfig(baseUrl = "https://x.test/v1", model = "m", apiKey = "k"))
        // 带空白的数字码（L-430）：此前 toIntOrNull 失败 ⇒ 落 else ⇒ 限流报成「服务异常」
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            provider.parseResponse(200, """{"error":{"code":" 429 "}}"""),
        )
        // 浮点形态
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            provider.parseResponse(200, """{"error":{"code":429.0}}"""),
        )
        // `0` = 非错误语义（不少网关的成功约定）⇒ 不当作错误码，回落状态码表
        assertEquals(
            TranslationOutcome.Fail(TranslationError.QUOTA),
            provider.parseResponse(429, """{"error":{"code":0}}"""),
        )
    }

    @Test
    fun `自定义 JSON 的键规则大小写不敏感且拦 stream-1`() {
        assertNotNull("大写 Stream 也要拦（用户照抄文档）", OpenAiTranslator.extraKeyRejection("Stream", true))
        assertNotNull("stream:1 同样会开流式", OpenAiTranslator.extraKeyRejection("stream", 1))
        assertNotNull("大写 Messages 不可覆盖", OpenAiTranslator.extraKeyRejection("Messages", "x"))
        assertNotNull("大写 Model 不可删", OpenAiTranslator.extraKeyRejection("Model", JSONObject.NULL))
        assertNull("小写字面量仍按原规则放行", OpenAiTranslator.extraKeyRejection("temperature", 0.3))
        assertFalse(OpenAiTranslator.isValidExtraJson("""{"Stream":true}"""))
    }

    @Test
    fun `先 trim 再截断：首尾空白不吃字节配额`() {
        // "   abc" = 6 字节；上限 5：若先截断得 "   ab"→trim→"ab"；先 trim 则完整保留 "abc"
        val slice = TranslationText.extract("   abc", "", TranslationScope.BEFORE_ALL, 5)
        assertEquals("abc", slice.text)
        assertFalse("三字节装得下，不该标记截断", slice.truncated)
    }

    // ── L-339 / L-343：判空按码位 ────────────────────────────────

    @Test
    fun `TAG 与其它不可见码位整段判空`() {
        assertFalse("纯 TAG 文本（代理对，低代理此前漏网）", "\uDB40\uDC00\uDB40\uDC01".hasVisibleContent())
        assertFalse("盲文空白", "\u2800".hasVisibleContent())
        assertFalse("Hangul 填充符", "\u115F\uFFA0".hasVisibleContent())
        assertFalse("ALM", "\u061C".hasVisibleContent())
        assertTrue("末尾零宽但有可见字", "a\u200B".hasVisibleContent())
        assertTrue("正常文字", "好".hasVisibleContent())
    }
}
