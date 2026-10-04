package com.jinn.inputmethod

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenAI 兼容 Provider 的**完整配置**（UI / Prefs / 请求组装共用这一处定义）。
 *
 * 「留空」在每一层都有明确含义，这正是能兼容 OpenRouter / 各家国内 API / 中转 / 本地模型的关键：
 *  · [temperature] / [topP] / [maxTokens] 留空 ⇒ **完全不发送**该参数（部分推理模型不接受它们）；
 *  · [extraJson] 非空 ⇒ **最后合并**，同名键覆盖标准参数（厂商私有参数不必等 App 更新）；
 *  · [chatPath] / [modelsPath] 留空 ⇒ 用默认 `/chat/completions` 与 `/models`。
 */
internal data class OpenAiConfig(
    val baseUrl: String = OpenAiTranslator.DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = "",
    val chatPath: String = OpenAiTranslator.DEFAULT_CHAT_PATH,
    val modelsPath: String = OpenAiTranslator.DEFAULT_MODELS_PATH,
    val targetLanguage: String = OpenAiTranslator.DEFAULT_TARGET_LANGUAGE,
    val systemPrompt: String = OpenAiTranslator.DEFAULT_SYSTEM_PROMPT,
    val userPrompt: String = OpenAiTranslator.DEFAULT_USER_PROMPT,
    val temperature: String = "",
    val topP: String = "",
    val maxTokens: String = "",
    val extraHeaders: String = "",
    val extraJson: String = "",
    val responsePath: String = OpenAiTranslator.DEFAULT_RESPONSE_PATH,
    val timeoutSec: Int = OpenAiTranslator.DEFAULT_TIMEOUT_SEC,
) {
    /**
     * 打码的 `toString`（2026-09-30 审查）：data class 的默认实现会把 **apiKey 明文**带出去 ——
     * 一行 `Diagnostics.i(TAG, "cfg=$config")`、一条把 config 塞进异常的写法、或一次失败的
     * `assertEquals(config, …)`（JUnit 会打印两边 toString）就足以把 Key 写进可外传的日志。
     */
    override fun toString(): String =
        "OpenAiConfig(baseUrl=$baseUrl, apiKey=${if (apiKey.isEmpty()) "（空）" else "***"}, " +
            "model=$model, chatPath=$chatPath, targetLanguage=$targetLanguage, timeoutSec=$timeoutSec)"
}

/**
 * 通用 OpenAI-Compatible Provider：Endpoint + Key + Model + Prompt + 生成参数 + Headers +
 * 自定义 JSON + 可配响应路径，全部由 [OpenAiConfig] 驱动，App 不为某家厂商写分支。
 *
 * 请求骨架（文档给定）：
 * ```
 * POST {BaseURL}{ChatPath}
 * Authorization: Bearer <API Key>
 * {"model":…, "messages":[system, user], ← 可选 temperature / top_p / max_tokens ← 用户 Custom JSON 覆盖}
 * ```
 * 组装与解析的每一步都是纯函数（[buildBody] / [joinUrl] / [applyTemplate] / [extractByPath]），
 * JVM 单测直接覆盖，不需要真网络。
 */
internal class OpenAiTranslator(private val config: OpenAiConfig) : TranslationProvider {

    override val callTimeoutSec: Int get() = config.timeoutSec

    override fun buildRequest(text: String, target: TranslationLanguage): Request {
        val url = joinUrl(config.baseUrl, config.chatPath)
            ?: error("OpenAI 兼容 Base URL 无效")
        val builder = Request.Builder()
            .url(url)
            .post(buildBody(config, text).toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${config.apiKey}")
        // 自定义头逐行 `Key: Value`；Authorization 由 API Key 字段独占（[parseHeaders] 里跳过）
        for ((name, value) in parseHeaders(config.extraHeaders)) builder.header(name, value)
        return builder.build()
    }

    /**
     * OpenAI 兼容的 `error.code` / `error.type` → 归一分类（2026-10-01 审查 L-270）。
     *
     * 词表取 OpenAI 官方与常见网关（OpenRouter / 自建中转）的取值；认不出的仍归 SERVER ——
     * 宁可提示「服务异常」，也不要把它猜成「检查凭据」。
     */
    private fun openAiErrorOf(code: String?): TranslationError {
        // trim 不能省（2026-10-02 修复 L-430）：网关回 `" 429 "` 这类带空白的错误码时，
        // `toIntOrNull()` 会失败并落进 else ⇒ 限流被报成「服务异常」
        val c = code.orEmpty().trim().lowercase()
        return when {
            c.isEmpty() -> TranslationError.SERVER
            "invalid_api_key" in c || "authentication" in c || "unauthorized" in c ||
                "permission" in c -> TranslationError.AUTH
            "quota" in c || "rate_limit" in c || "insufficient" in c || "billing" in c ||
                "credit" in c -> TranslationError.QUOTA
            "context_length" in c || "too_long" in c || "max_tokens" in c ||
                "invalid_request" in c || "bad_request" in c -> TranslationError.PARAM
            // 纯数字错误码（2026-10-02 修复 L-299 / L-430）：`jsonCode` 已能把
            // `{"error":{"code":429}}` 取成 "429"，但此前这里只认关键词 ⇒ 又落回 SERVER，
            // L-287 的修复等于白做。数字分支还要处理两件事：
            //  · `429.0` 这类浮点形态（`toIntOrNull()` 失败）⇒ 用 `toDoubleOrNull` 兜；
            //  · `0` 与 `2xx` 是**非错误语义**（不少网关用 0 表示成功）⇒ 返回 SERVER，
            //    交给调用方的「业务码只在更具体时才覆盖」吸收，不会被当成一次失败。
            else -> {
                val n = c.toIntOrNull()
                    ?: c.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
                when {
                    n == null -> TranslationError.SERVER
                    n == 0 || n in 200..299 -> TranslationError.SERVER
                    else -> httpErrorOf(n)
                }
            }
        }
    }

    override fun parseResponse(code: Int, body: String?): TranslationOutcome {
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        // 业务错误码优先，且**非 2xx 也先读**（2026-10-02 修复 L-358）：与百度 / 阿里云同一口径 ——
        // 网关用 4xx/5xx 承载 `insufficient_quota` / `invalid_api_key` 时，先看 HTTP 状态会把
        // 「欠费」「Key 无效」压成「服务异常」/「认证失败」，用户查不出真因。
        // 过滤 JSON null（2026-10-01 修复 L-257）：`has` 对显式 `"error": null` 也返回 true，
        // 会把正常响应判成服务端错误；口径与本文件其它取值的 `JSONObject.NULL` 过滤一致
        val error = json?.opt("error")
        if (error != null && error !== org.json.JSONObject.NULL) {
            // 按 `error.code` / `error.type` 分类（2026-10-01 审查 L-270）：不少网关与中转用 200
            // 承载 `insufficient_quota` / `invalid_api_key` / `context_length_exceeded` 与限流，
            // 一律报「服务异常」会让用户无从下手 —— 换 Key、充值、缩短原文被压成同一句提示。
            // ⚠ 用 jsonCode 而不是 jsonText（2026-10-01 修复 L-287）：`{"error":{"code":429}}`
            // 这类**数字**错误码（不少网关如此）经 `as? String` 会被丢成 null ⇒ 一律归 SERVER，
            // 于是欠费 / 限流 / 超上下文又被压成「服务异常，请稍后重试」。`jsonCode` 就是为
            // 「可能是数字的错误码」准备的（见它在 Translation.kt 的 KDoc）。
            val errorCode = when (error) {
                is org.json.JSONObject ->
                    // 空串要跳过，否则会盖掉可用的 `type`：`jsonCode` 对 "" 返回 ""（只过滤 NULL）
                    jsonCode(error, "code")?.takeIf { it.isNotBlank() } ?: jsonCode(error, "type")
                else -> error.toString()
            }
            // ⚠ 业务码只在**更具体**时才覆盖状态码分类（2026-10-02 修复：上一轮引入的回归）：
            // `openAiErrorOf` 认不出时返回 SERVER，直接 return 会**挤掉** `httpErrorOf` 的结论 ——
            // `404 + {"error":{"message":…}}` 会从 PARAM 退回 SERVER、
            // `429 + {"error":{"type":"requests"}}`（OpenAI 429 的真实形态，`code` 为 null）会
            // 从 QUOTA 退回 SERVER，恰好把 L-287 / L-299 那条线要服务的场景打回原形。
            val byBody = errorCode?.let { openAiErrorOf(it) }
            val byHttp = if (code in 200..299) null else httpErrorOf(code)
            return TranslationOutcome.Fail(
                byBody?.takeIf { it != TranslationError.SERVER } ?: byHttp ?: TranslationError.SERVER,
            )
        }
        if (json == null || code !in 200..299) return TranslationOutcome.Fail(httpErrorOf(code))
        // 输出被上限截断时**不能算成功**（2026-10-02 修复 L-484）：
        // `finish_reason == "length"` 表示模型输出被 `max_tokens`（或网关自带上限）砍断 ——
        // 拿它上屏等于把**半句话**写进用户正文，且毫无提示（用户只会以为模型翻得烂）。
        // 归 PARAM 而不是 SERVER：这是「调用方参数（输出上限）太小」，不是服务端故障。
        val finishReason = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optString("finish_reason")
            .orEmpty()
        if (finishReason.equals("length", ignoreCase = true)) {
            Diagnostics.w("OpenAiTranslator", "翻译: 模型输出被上限截断（finish_reason=length），不当作成功")
            // 用专有的 TRUNCATED 而不是 PARAM：后者让用户去核对「语言方向 / 模型名 / 路径 /
            // 原文长度」，而真因是**输出上限** —— 按 PARAM 的提示核对必然无果（2026-10-02 审查）
            return TranslationOutcome.Fail(TranslationError.TRUNCATED)
        }
        val text = extractByPath(json, config.responsePath)
        return if (text.isNullOrBlank()) {
            TranslationOutcome.Fail(TranslationError.EMPTY)
        } else {
            TranslationOutcome.Ok(text.trim())
        }
    }

    companion object {
        /** 默认配置名称（仅用于显示；多配置档在下一轮） */
        const val DEFAULT_PROFILE_NAME = "OpenAI 兼容"

        /** 默认 Base URL（OpenAI 官方） */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_CHAT_PATH = "/chat/completions"
        const val DEFAULT_MODELS_PATH = "/models"

        /** 默认目标语言 */
        const val DEFAULT_TARGET_LANGUAGE = "English"

        /** 默认响应解析路径 */
        const val DEFAULT_RESPONSE_PATH = "choices[0].message.content"

        /** 默认整体超时（大模型首字延迟不可控，给足 60s） */
        const val DEFAULT_TIMEOUT_SEC = 60

        /** system 提示词（逐字取自接入文档） */
        const val DEFAULT_SYSTEM_PROMPT =
            "你是专业翻译引擎.只输出译文,不解释/不分析/不加额外内容.保持原文语气/格式/专有名词,保证译文完整,无语法错误."

        /** user 提示词模板（逐字取自接入文档，`{目标语言}` 变成可替换变量） */
        const val DEFAULT_USER_PROMPT = "请将以下文本翻译成{{target_language}}，只返回译文：\n{{text}}"

        /** 专业方向预设：界面里「提示词模板」下拉可直接套用，不含 {{text}} 的模板由调用方兜底追加 */
        data class PromptPreset(val name: String, val system: String, val user: String)

        val PROMPT_PRESETS: List<PromptPreset> = listOf(
            PromptPreset("默认翻译", DEFAULT_SYSTEM_PROMPT, DEFAULT_USER_PROMPT),
            PromptPreset(
                "闲聊社交",
                "你是日常社交口语翻译者。只输出译文，自然口语、语气贴合上下文，语句要完整且语法正确。禁止加解释/前缀/多余换行。",
                "请把下面这段短句/聊天翻译成{{target_language}}。原文可能是不完整碎片，仍要输出完整、通顺、语法正确的译文：\n{{text}}",
            ),
            PromptPreset(
                "工作书面",
                "你是正式书面翻译者。只输出译文，用书面语、标准格式、规范用词，保证语法与术语准确。禁止加解释/前缀。",
                "请将以下文本以规范书面用词、标准格式译为{{target_language}}，只返回译文：\n{{text}}",
            ),
            PromptPreset(
                "编程代码",
                "你是技术内容翻译者。只输出译文。区分需要翻译的描述性文字与不应翻译的编程专名：变量名/方法名/类名/类型名/包名/命令/配置项/路径/正则/代码片段必须原样保留，且不得被翻译链接或结构改写。",
                "请将以下文本译成{{target_language}}：其中自然语言部分正常翻译，编程标识符/代码片段保持原样不动，不加解释：\n{{text}}",
            ),
        )

        const val VAR_TEXT = "{{text}}"

/**
 * 应用模板并**保证原文一定在其中**（2026-10-01 修复 L-246）。
 *
 * 用户可以把提示词改写成自然语言（例如「把上面的内容翻译成中文」），此时模板里不再有
 * [VAR_TEXT] —— 请求里也就**没有任何原文**，模型只能凭空编，而界面与日志都显示「成功」。
 * 这里把原文追加到消息末尾，保证「翻译」这件事仍然成立；并留一条 W 便于发现配置问题。
 */
internal fun applyTemplateEnsuringText(
    template: String,
    otherTemplate: String,
    text: String,
    target: String,
): String {
    val filled = applyTemplate(template, text, target)
    // 只在**两个模板都没有占位符**时兜底：system 提示词本来就不该带 {{text}}（正文由 user 带），
    // 只看单个模板会在 system 里重复塞一遍原文。
    // ⚠ 判据用 [OpenAiTranslator.hasTextVar]（归一化后）而不是原串字面量（2026-10-02 修复 L-338）：
    // 展开走归一化、判据走原串时，容错写法（`{{ text }}` / `{{TEXT}}`）会被误判成「没有占位符」
    // ⇒ 原文进请求两次，用户看到重复译文。
    if (!hasTextVar(template) && !hasTextVar(otherTemplate)) {
        Diagnostics.w(
            "OpenAiTranslator",
            "提示词模板缺少 $VAR_TEXT，已把原文追加到消息末尾（否则请求里没有原文）",
        )
        return "$filled\n\n$text"
    }
    return filled
}
        const val VAR_TARGET = "{{target_language}}"
        const val VAR_SOURCE = "{{source_language}}"
        const val VAR_DATE = "{{date}}"

        /** 源语言变量的取值：四家都由服务端自动识别，本地不做检测 */
        const val SOURCE_AUTO_LABEL = "自动检测"

        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

        // ── 请求组装（纯函数） ─────────────────────────────────

        /**
         * Base URL + Path → 完整端点。
         *
         *  · 缺 scheme 时补 `https://`（明文会被 [TranslationClient] 的 HTTPS 判据拒发）；
         *  · 用户把端点整段粘进 Base URL 时先剥掉尾部，避免 `…/chat/completions/chat/completions`；
         *  · **不擅自补 `/v1`** —— 有人把 base 配成 `https://host/api/openai`，替用户补路径只会打错地址。
         */
        internal fun joinUrl(baseUrl: String, path: String): HttpUrl? {
            var s = baseUrl.trim()
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
                s = "https://$s"
            }
            var tail = path.trim()
            if (tail.isEmpty()) tail = DEFAULT_CHAT_PATH
            tail = "/" + tail.trimStart('/')
            // path 允许带 query（Azure OpenAI 的 `?api-version=…` 是必填）：拆出来单独设，
            // 否则 `addPathSegments` 会把 `?` 编码成 `%3F`
            val q = tail.indexOf('?')
            val tailPath = if (q >= 0) tail.substring(0, q) else tail
            val tailQuery = if (q >= 0) tail.substring(q + 1) else ""
            val parsed = s.trimEnd('/').toHttpUrlOrNull() ?: return null
            // 用户把端点整段粘进 Base URL 时剥掉尾部：按**路径**比较，不看 query ——
            // base 带 query 时（Azure OpenAI 的端点整段粘贴必带 `?api-version=…`）字符串后缀
            // 匹配必然失败，会拼成 `…/chat/completions/chat/completions` → 404 被归成 PARAM，
            // 把用户引向「检查语言设置」（2026-10-01 审查 L-218）。
            val basePath = parsed.encodedPath
            val stripped = if (basePath.endsWith(tailPath)) basePath.dropLast(tailPath.length) else basePath
            val withPath = parsed.newBuilder()
                .encodedPath(stripped.ifEmpty { "/" })
                .addPathSegments(tailPath.trimStart('/'))
                .build()
            return if (tailQuery.isEmpty()) withPath else withPath.newBuilder().encodedQuery(tailQuery).build()
        }

        /**
         * 「端点可用」的**唯一判据**：能解析出 URL **且是 https**（2026-10-03 修复 L-811）。
         *
         * 只判「能解析」时 `http://` 会一路放行到界面说「已配置」，直到第一次点翻译才被 `INSECURE`
         * 拒掉 —— 而平台层对用户自填的域名没有兜底（见 `network_security_config` 的说明）。
         */
        internal fun endpointReady(baseUrl: String, chatPath: String): Boolean =
            joinUrl(baseUrl, chatPath)?.isHttps == true

        /**
         * 「OpenAI 兼容是否**已配置**」的**唯一判据**（2026-10-03 修复 L-811）。
         *
         * 此前这份事实散在**四处**：`Prefs.hasCredentialFor` / `OpenAiSettingsActivity` 的保存校验 /
         * 翻译设置页的状态行都要求端点是 https，而 `TranslationClient.providerOf` 只判「端点能解析」
         * ⇒ 用户把 Base URL 填成 `http://` 时，翻译设置页与设置页都显示「已配置 · 模型名」，
         * 真正点翻译却被 `INSECURE` 拒掉：界面说配好了、一用就报错，且每次点击前都以为要花钱。
         *
         * 四处全调它 —— **同一份事实只允许有一份判据**（本仓已因判据分叉翻车多次，见 L-557）。
         */
        internal fun isReady(apiKey: String, model: String, baseUrl: String, chatPath: String): Boolean =
            apiKey.cleanCredential().isNotEmpty() &&
                model.cleanCredential().isNotEmpty() &&
                endpointReady(baseUrl, chatPath)

        /** 模型列表端点：`{BaseURL}{ModelsPath}`（默认 `/models`） */
        internal fun modelsUrl(baseUrl: String, modelsPath: String): HttpUrl? =
            joinUrl(baseUrl, modelsPath.trim().ifEmpty { DEFAULT_MODELS_PATH })

        /**
         * 自定义 Headers 解析：一行一条 `Key: Value`；空行与 `#` 注释行忽略；
         * **`Authorization` 一律跳过** —— 它由 API Key 字段独占，两处配置只会互相打架（文档要求）。
         *
         * 被丢弃的头**记一条 W 日志**（2026-10-04 修复 L-589 / L-590 的残余）：此前是静默丢弃，
         * 用户按网关文档填 `Accept-Encoding: gzip` / `Content-Type: …json-patch+json` 时，
         * 界面看着配置生效、实际一个字节都没发出去，排查只能靠猜（值不进日志，可能含密钥）。
         */
        internal fun parseHeaders(raw: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            val dropped = ArrayList<String>()
            for (line in raw.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(':')) continue
                val name = trimmed.substringBefore(':').trim()
                val value = trimmed.substringAfter(':').trim()
                // 名字必须是合法 HTTP token（RFC 7230）：中文名 / 含空格的名字会让
                // `Request.Builder.header` 抛 IllegalArgumentException，被 TranslationClient
                // 兜成 PARAM → 提示「请检查语言设置」，与真实原因（自定义 Headers 写错）
                // 完全无关（2026-09-30 审查发现）。
                // **值同样要校验**（探针实测）：OkHttp 对值只允许 `\t` 与 U+0020..U+007E，
                // 中文值、或从网页复制来的 NBSP / ZWSP（`trim()` 不删它们！）都会抛同一个异常、
                // 得到同一个误导提示。
                val valueOk = value.all { it == '\t' || it in '\u0020'..'\u007e' }
                when {
                    name.isEmpty() || !name.all { it in HEADER_TOKEN_CHARS } || !valueOk ->
                        dropped.add(name.ifEmpty { "(无名)" })
                    name.equals("Authorization", ignoreCase = true) -> dropped.add("Authorization")
                    // ⚠ 协议级头一律不接纳（2026-10-03 修复 L-705 / L-590；2026-10-04 补 content-type）：
                    // OkHttp 的 `transparentGzip` 只在**调用方未设** `Accept-Encoding` 时才透明解压 ⇒ 用户
                    // 照抄网关文档填 `Accept-Encoding: gzip`，body 变成原始 gzip 字节，被门户判别
                    // 归成「不是 JSON（被登录门户 / 代理拦截）」，归因完全误导；`Range` 会让响应被截断成
                    // 解析失败；`Host` / `Content-Length` / `Transfer-Encoding` / `Connection` 由客户端与
                    // BridgeInterceptor 掌管，留着会让请求本身变形（`Host` 还能把凭据改道到别的主机）；
                    // `Content-Type` 会被 BridgeInterceptor 用 body 的 MediaType **覆盖** ⇒ 用户设了也不生效。
                    name.lowercase(Locale.US) in CLIENT_OWNED_HEADERS -> dropped.add(name)
                    else -> out.add(name to value)
                }
            }
            if (dropped.isNotEmpty()) {
                Diagnostics.w(
                    "OpenAiTranslator",
                    "自定义请求头被丢弃（协议级 / 保留名 / 非法）：${dropped.joinToString("、")}",
                )
            }
            return out
        }

        /**
         * 由客户端（或 OkHttp 的 `BridgeInterceptor`）掌管的协议级头名（小写）：**一律不接纳用户的自定义头**。
         *
         * 判据是「这个头由传输层决定语义」：`accept-encoding` 关掉透明解压、`content-length` 与
         * `transfer-encoding` 决定 body 形态、`connection` 决定连接复用、`host` 决定寻址与凭据去向、
         * `range` 让服务端只回一段、`content-type` 会被 `BridgeInterceptor` 用 body 的 MediaType 覆盖
         * （2026-10-04 补，见 L-590）。
         */
        private val CLIENT_OWNED_HEADERS = setOf(
            "accept-encoding", "content-length", "transfer-encoding",
            "connection", "host", "range", "expect", "upgrade", "content-type",
        )

        /** HTTP header 名的合法字符集（RFC 7230 `token`）：字母数字与 `!#$%&'*+-.^_\`|~` */
        private const val HEADER_TOKEN_CHARS =
            "!#\$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /**
         * 提示词变量替换：`{{text}}` / `{{target_language}}` / `{{source_language}}` / `{{date}}`。
         *
         * 认不出的变量**原样保留**：将来加新变量时不至于吃掉旧模板；但本客户端**不会替换它们**
         * ——`messages` 不可被自定义 JSON 覆盖（原文必须由提示词模板携带，见 L-428），
         * 所以自定义变量只会字面进请求。
         *
         * **单趟扫描**（2026-10-01 修复 L-332）：链式 `replace` 会让被代入的**值**再被后续 replace 扫描，
         * 而 `target` 是用户自由文本 —— 把它写成 `{{text}}` 就会让原文出现两次、目标语言消失。
         * 单趟扫描里值只是被 append，不会再被当成模板。
         */
        internal fun applyTemplate(
            template: String,
            text: String,
            target: String,
            now: Date = Date(),
        ): String {
            val normalized = normalizeKnownVars(template)
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val sb = StringBuilder(normalized.length + text.length)
            var i = 0
            while (i < normalized.length) {
                val hit = when {
                    normalized.startsWith(VAR_TEXT, i) -> VAR_TEXT to text
                    normalized.startsWith(VAR_TARGET, i) -> VAR_TARGET to target
                    normalized.startsWith(VAR_SOURCE, i) -> VAR_SOURCE to SOURCE_AUTO_LABEL
                    normalized.startsWith(VAR_DATE, i) -> VAR_DATE to date
                    else -> null
                }
                if (hit == null) {
                    sb.append(normalized[i])
                    i++
                } else {
                    sb.append(hit.second)
                    i += hit.first.length
                }
            }
            return sb.toString()
        }

        /**
         * 已知变量的书写容错：`{{ text }}` / `{{TEXT}}` 这类写法也认（2026-10-01 修复 L-334）。
         *
         * 只归一这四个名字，其余 `{{…}}` **原样保留**（不替换：2026-10-02 修复 L-428 —— `messages`
         * 不可被覆盖，原文只能由**提示词模板**携带，自定义变量没有用武之地）。
         * 此前链式 `replace` 只认精确字面量，用户写 `{{ text }}` 时提示词里会原样留着它、
         * 兜底再把原文追加到末尾，而三处反馈都没说清是空格导致的。
         */
        // ⚠ 空白类必须带 `\p{Zs}`（2026-10-02 修复 L-419）：Java 的 `\s` 是 `[ \t\n\x0B\f\r]`，
        // **不含 U+3000 全角空格** —— 而中文输入法下空格键的默认输出正是它，`cleanCredential`
        // 的剥除表也不含它 ⇒ `{{　text　}}` 归一化失败、占位符原样泄进请求。
        // ⚠ 花括号**本身**同样要容错（2026-10-02 修复）：`｛｝`（U+FF5B / U+FF5D 全角）是中文
        // 输入法下打花括号的常见形态，而本 App 自己就是中文 IME —— 只补内侧空白不补括号本身，
        // 等于把同一个漏洞从「半开」改成「开一半」：`｛｛text｝｝` 依旧归一化失败、占位符原样
        // 进请求，兜底再把原文追加一遍。允许半角/全角混用（`{｛text}}` 也认）。
        private val KNOWN_VAR_PATTERN = Regex(
            "[{｛]{2}[\\s\\p{Zs}]*(text|target_language|source_language|date)[\\s\\p{Zs}]*[}｝]{2}",
            RegexOption.IGNORE_CASE,
        )

        /** 归一化：容错写法统一成精确字面量；判据与展开**共用**（见 [hasTextVar]） */
        internal fun normalizeKnownVars(template: String): String =
            KNOWN_VAR_PATTERN.replace(template) { "{{${it.groupValues[1].lowercase()}}}" }

        /**
         * 模板里是否带 `{{text}}`（含容错写法）。
         *
         * 判据必须与展开同源（2026-10-02 修复 L-338）：`applyTemplate` 展开的是**归一化后**的串，
         * 而兜底判据原来看的是原串 —— 用户写 `{{ text }}` 或 `{{TEXT}}` 时展开生效、判据却看不见
         * ⇒ 原文被追加第二遍（模型可能译两份、token 翻倍），同时日志与设置页报「模板缺少 {{text}}」。
         * 展开、兜底、设置页三处现在共用这一个判据。
         */
        internal fun hasTextVar(template: String): Boolean = VAR_TEXT in normalizeKnownVars(template)

        /**
         * 「生效目标语言」的唯一归一：留空 / 全空白退回默认（BUG.md L-787）。
         *
         * 请求组装（[buildBody]）与去重指纹（`JinnIme.effectiveTargetLabel`）必须同源 ——
         * 两处各写一份「trim + ifEmpty」时，任何一处漏改都会让指纹与真实请求再次错位。
         */
        internal fun effectiveTarget(raw: String): String =
            raw.trim().ifEmpty { DEFAULT_TARGET_LANGUAGE }

        /** 数字解析：空串 / 非数字 / NaN / 无穷 → null（null = 该参数不发送） */
        internal fun numberOrNull(raw: String): Double? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val d = s.toDoubleOrNull() ?: return null
            return if (d.isFinite()) d else null
        }

        /**
         * 组装请求体（顺序即优先级）：
         * `model` + `messages` → 可选标准参数（temperature / top_p / max_tokens）→ **Custom JSON 覆盖**。
         *
         * 整数值写成整数（`1024` 而不是 `1024.0`）：部分网关对数字形态挑剔。
         */
        internal fun buildBody(config: OpenAiConfig, text: String): JSONObject {
            val target = effectiveTarget(config.targetLanguage)
            val system = config.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
            val user = config.userPrompt.ifBlank { DEFAULT_USER_PROMPT }
            val body = JSONObject()
                .put("model", config.model)
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", applyTemplate(system, text, target)),
                        )
                        .put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", applyTemplateEnsuringText(user, system, text, target)),
                        ),
                )
            putNumber(body, "temperature", config.temperature)
            putNumber(body, "top_p", config.topP)
            putNumber(body, "max_tokens", config.maxTokens)
            // 返回值此前被丢弃（2026-10-02 修复 L-424）：解析失败时静默跳过合并，排障
            // （尤其配置来自**备份导入**、绕过设置页校验时）看不到「写了但没生效」
            if (!mergeExtraJson(body, config.extraJson)) {
                Diagnostics.w("OpenAiTranslator", "自定义 JSON 解析失败，本次未合并（设置页保存时会提示）")
            }
            return body
        }

        /**
         * 数值参数的**定义域**（2026-10-03 修复 L-500）。
         *
         * 越界值此前原样发出 ⇒ 服务端 400 ⇒ 归 `PARAM` ⇒ 提示让用户去核「语言方向 / 模型名 /
         * 路径 / 原文长度」，而真因就是这个数字。备份导入路径绕过设置页校验，
         * 组装期是**唯一**能兜住三条路径的地方。
         */
        private val NUMBER_RANGES = mapOf(
            "temperature" to 0.0..2.0,
            "top_p" to 0.0..1.0,
        )

        /** `max_tokens` 的下界（上界不设：各家上限不同，钳死反而不让用） */
        private const val MIN_MAX_TOKENS = 1.0

        /**
         * 数字参数入体：null（未填 / 非法）直接跳过，绝不默认发送；越界值**钳位并记 W**。
         *
         * `max_tokens` 还要取整（服务端只认整数，`1024.5` 会被拒）。
         */
        private fun putNumber(body: JSONObject, key: String, raw: String) {
            val d = numberOrNull(raw) ?: return
            val ranged = NUMBER_RANGES[key]
            val clamped = when {
                ranged != null -> d.coerceIn(ranged.start, ranged.endInclusive)
                key == "max_tokens" -> Math.floor(d).coerceAtLeast(MIN_MAX_TOKENS)
                else -> d
            }
            if (clamped != d) {
                Diagnostics.w(
                    "OpenAiTranslator",
                    "参数 $key=$d 超出可用范围，已按 $clamped 发送（设置页与备份导入同源钳位）",
                )
            }
            // 只在**精确可表示**的整数范围内收窄成 Long：`toLong()` 对 1e30 这类超范围值会
            // 饱和成 Long.MAX_VALUE，把用户填的数悄悄改掉（JSON 本身支持 1e30 这种写法，
            // 2026-10-01 审查 L-226）
            val value: Any = if (clamped == Math.floor(clamped) && Math.abs(clamped) < 9.0e15) {
                clamped.toLong()
            } else {
                clamped
            }
            body.put(key, value)
        }

        /**
         * 合并用户自定义 JSON：**同名键覆盖**标准参数，新键追加。
         *
         * 解析失败返回 `false`（UI 据此提示格式问题），但**不阻止翻译** —— JSON 写错不该把功能打死。
         */
        /**
     * 自定义 JSON 是否可解析（2026-10-01 修复 L-257）。
     *
     * UI 用它给用户**看得见**的提示：此前这个校验的返回值在 `buildBody` 里被丢弃，而
     * `EXTRA_JSON_INVALID` 常量的注释写着「UI 用它提示用户」却全仓无人引用 —— 用户把 JSON 写错时
     * 参数被静默丢弃、翻译照常「成功」。
     */
    internal fun isValidExtraJson(extraJson: String): Boolean {
        val raw = extraJson.trim()
        if (raw.isEmpty()) return true
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        // 让用户在**设置页**就看见问题（2026-10-01 L-333 / 2026-10-02 L-259·L-344·L-421）：
        // 判据与 [mergeExtraJson] 共用 [extraKeyRejection] —— 两处各写一套就会判出两种结果。
        return obj.keys().asSequence().none { extraKeyRejection(it, obj.opt(it)) != null }
    }

    /** 会破坏本客户端的键（2026-10-01 修复 L-333）：响应按非流式解析，开了 SSE 就解析不了 */
    private val UNSUPPORTED_EXTRA_KEYS = listOf("stream", "stream_options")

    /** 删掉会让请求体非法的必需键（2026-10-02 修复 L-421） */
    private val REQUIRED_EXTRA_KEYS = listOf("model", "messages")

    /** 覆盖即失效的键（2026-10-02 修复 L-259）：本客户端组装的原文就在 `messages` 里 */
    private val NO_OVERWRITE_KEYS = listOf("messages")

    /**
     * 该键在当前取值下会被拒绝的原因（null = 可用）。**设置页判据与运行期合并共用这一处**
     * （2026-10-02：此前两处各写一套，`{"stream": null}` 在运行期被"忽略"、在设置页却判"不可用"）。
     *
     * 三条规则都按**值**判，不按"键在不在"：
     *  · 破坏客户端：只有 `stream` 类键**为真**才算（`false` / `null` 等价于"不发这个键"，无害）；
     *  · 必需键：`null`（删键语义）一律拒绝 —— `{"model": null}` 会让请求体非法、错误却被归成
     *    `PARAM`、提示指向语言设置，与真因（自己删了 model）无关；
     *  · 关键键：`messages` **不可覆盖** —— 用户抄网关文档的完整示例时，含原文的消息被整体换掉，
     *    模型凭空编、界面仍显示「成功」（与 L-246 同类失效，入口不同）。
     */
    /**
     * 自定义 JSON 里的「真值」判据（2026-10-02 补完 L-431）：`true` / `"true"`（不分大小写）/
     * 任意**非零数值**（含 `1.0`、`2`）。
     *
     * 此前只认 `toBoolean()` 与字面量 `"1"` ⇒ `{"stream": 2}` / `{"stream": 1.0}` 会被放行、
     * 请求开成流式、响应按非流式解析必然失败 —— 正是这条规则要拦的场景（部分网关按「非零即真」
     * 解释该参数，而 JSON 里的 `1.0` 经 `toString()` 是 `"1.0"`，两个字面量判据都不命中）。
     */
    private fun isTruthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        else -> value.toString().toBoolean() || value.toString() == "1"
    }

    internal fun extraKeyRejection(key: String, value: Any?): String? {
        val isDelete = value == null || value === JSONObject.NULL
        // 键名按**大小写不敏感**比较（2026-10-02 修复 L-431）：规则要防的是「用户照抄网关文档」，
        // 而文档里的 `Stream` / `Messages` 这类大写键真实存在 —— 精确比较会被整片绕过。
        val k = key.lowercase()
        // 真值判据还要认 `1`（部分网关接受 1/0 写法）：`"1".toBoolean()` 是 false，
        // 漏掉它就会放行 `{"stream":1}` ⇒ 响应变 SSE ⇒ 解析必然失败
        val streaming = k in UNSUPPORTED_EXTRA_KEYS && !isDelete && isTruthy(value)
        return when {
            streaming -> "开启流式会破坏本客户端的响应解析（本客户端按非流式解析）"
            isDelete && k in REQUIRED_EXTRA_KEYS ->
                "必需键（${REQUIRED_EXTRA_KEYS.joinToString(" / ")}）不可删"
            !isDelete && k in NO_OVERWRITE_KEYS ->
                "原文就在这个键里（不可覆盖）：请把要翻译的内容放进「提示词」的 {{text}}，而不是自定义 messages"
            else -> null
        }
    }

    /**
     * 把用户填的自定义 JSON 合并进请求体 [body]。
     *
     * 返回 `false` 仅当整串不是合法 JSON（直接拒绝）；单键命中协议级拒绝名单（如 header/url 类）
     * 时**忽略该键但继续合并其余键**，不让一处写错把功能打死。
     */
    internal fun mergeExtraJson(body: JSONObject, extraJson: String): Boolean {
        val raw = extraJson.trim()
        if (raw.isEmpty()) return true
        val extra = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        for (key in extra.keys()) {
            val value = extra.opt(key)
            val rejection = extraKeyRejection(key, value)
            if (rejection != null) {
                // 拒绝该键但**不中断**：其余键照常合并（JSON 写错不该把功能打死，与解析失败的既有口径一致）
                Diagnostics.w("OpenAiTranslator", "自定义 JSON 的 $key 已忽略（$rejection）")
                continue
            }
            if (value == null || value === JSONObject.NULL) {
                // 写成 JSON `null` = **删掉这个标准参数**（2026-10-01 修复 L-331）。标准参数里有已经
                // 过时的键名 —— `max_tokens` 就是：新一代推理模型只认 `max_completion_tokens`，且
                // 不兼容旧名。而「留空即不发」只对空字段生效，用户必须能只替换掉其中一个键。
                // 注意 `body.put(key, null)` 是另一回事：那会写进 JSONObject.NULL 并序列化成 `null` 发出去。
                body.remove(key)
            } else {
                body.put(key, value)
            }
        }
        return true
    }

        // ── 响应解析（纯函数） ─────────────────────────────────

        /**
         * 按路径取译文，支持 `a.b[0].c`、`choices[].message.content`（`[]` = 首元素）、
         * `$.` 前缀与 `output_text` 这类平铺键。
         *
         * 终值归一：字符串直接用；`content` 为数组（多段 content）时取第一条跑得出文本的片段；
         * 对象则取 `text` / `content`。取不到返回 null（调用方按「结果为空」处理）。
         */
        internal fun extractByPath(root: JSONObject, path: String): String? {
            val normalized = path.trim().removePrefix("$.").ifEmpty { DEFAULT_RESPONSE_PATH }
            var current: Any? = root
            var i = 0
            while (i < normalized.length) {
                when (normalized[i]) {
                    '.' -> i++
                    '[' -> {
                        val end = normalized.indexOf(']', i)
                        if (end < 0) return null
                        val token = normalized.substring(i + 1, end).trim()
                        current = when {
                            token.isEmpty() || token == "*" -> elementAt(current, 0)
                            token.toIntOrNull() != null -> elementAt(current, token.toInt())
                            else -> keyOf(current, token)
                        }
                        i = end + 1
                    }

                    else -> {
                        var end = i
                        while (end < normalized.length && normalized[end] != '.' && normalized[end] != '[') end++
                        current = keyOf(current, normalized.substring(i, end))
                        i = end
                    }
                }
                if (current == null) return null
            }
            return asText(current)
        }

        private fun keyOf(node: Any?, key: String): Any? =
            (node as? JSONObject)?.opt(key)?.takeIf { it != JSONObject.NULL }

        private fun elementAt(node: Any?, index: Int): Any? {
            val arr = node as? JSONArray ?: return null
            return if (index in 0 until arr.length()) arr.opt(index) else null
        }

        private fun asText(node: Any?): String? = when (node) {
            is String -> node
            // `optString` 对显式 JSON null 返回字面量 "null"、对对象/数组返回序列化文本，都会被当
            // 假译文提交；这里用 `opt(...) as? String` 与同文件 keyOf 过滤 JSONObject.NULL 的口径
            // 对齐（2026-09-30 审查发现：作者已知道该坑，只是 asText 漏了）。
            is JSONObject -> (node.opt("text") as? String).orEmpty()
                .ifEmpty { (node.opt("content") as? String).orEmpty() }
                .ifEmpty { null }
            is JSONArray -> (0 until node.length())
                .asSequence()
                .map { asText(node.opt(it)) }
                .firstOrNull { !it.isNullOrBlank() }

            else -> null
        }

        /** 解析 `GET /models` 的响应：`data[].id`（缺失时回落 `data[].name`） */
        internal fun parseModels(body: String?): List<String> {
            val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return emptyList()
            val data = json.optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                // 走 jsonText：JSON null / 类型不符都返回 null —— `optString` 会给出字面量 "null"，
                // 于是下拉里多出一项 `null`，用户选中就写进 model（2026-09-30 审查，同类漏网）
                (jsonText(item, "id") ?: jsonText(item, "name"))?.takeIf { it.isNotBlank() }
            }
        }

        /**
         * HTTP 状态码 → 归一分类。
         *
         * `404` 归 PARAM：OpenAI 兼容服务的 404 几乎都是「模型名写错 / 路径不对」，
         * 提示用户检查配置比笼统报「服务异常」有用得多。
         */
        internal fun httpErrorOf(code: Int): TranslationError = when (code) {
            400, 404, 413, 422 -> TranslationError.PARAM
            401, 403 -> TranslationError.AUTH
            408 -> TranslationError.TIMEOUT
            // 402 = 余额不足：相当多 OpenAI 兼容网关用它表达欠费（与 Azure 把 403 归额度同源）。
            // 落到 else 会显示「服务异常」，用户无从得知是账户问题（2026-09-30 审查发现）。
            402 -> TranslationError.QUOTA
            429 -> TranslationError.QUOTA
            in 500..599 -> TranslationError.SERVER
            else -> TranslationError.SERVER
        }
    }
}
