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
     * 打码的 `toString`（2026-09-30 第二轮审查）：data class 的默认实现会把 **apiKey 明文**带出去 ——
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

    override fun parseResponse(code: Int, body: String?): TranslationOutcome {
        if (code !in 200..299) return TranslationOutcome.Fail(httpErrorOf(code))
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return TranslationOutcome.Fail(TranslationError.SERVER)
        // 部分网关把错误塞在 HTTP 200 的 body 里（{"error":{…}}）
        if (json.has("error")) return TranslationOutcome.Fail(TranslationError.SERVER)
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
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_CHAT_PATH = "/chat/completions"
        const val DEFAULT_MODELS_PATH = "/models"

        /** 默认目标语言（文档给定：简体中文） */
        const val DEFAULT_TARGET_LANGUAGE = "简体中文"

        /** 默认响应解析路径 */
        const val DEFAULT_RESPONSE_PATH = "choices[0].message.content"

        /** 默认整体超时（大模型首字延迟不可控，给足 60s） */
        const val DEFAULT_TIMEOUT_SEC = 60

        /** system 提示词（逐字取自接入文档） */
        const val DEFAULT_SYSTEM_PROMPT =
            "你是专业翻译引擎。只输出译文，不解释、不分析、不添加额外内容。保持原文语气、格式和专有名词。"

        /** user 提示词模板（逐字取自接入文档，`{目标语言}` 变成可替换变量） */
        const val DEFAULT_USER_PROMPT = "请将以下文本翻译成{{target_language}}，只返回译文：\n{{text}}"

        const val VAR_TEXT = "{{text}}"
        const val VAR_TARGET = "{{target_language}}"
        const val VAR_SOURCE = "{{source_language}}"
        const val VAR_DATE = "{{date}}"

        /** 源语言变量的取值：四家都由服务端自动识别，本地不做检测 */
        const val SOURCE_AUTO_LABEL = "自动检测"

        /** 自定义 JSON 解析失败时的返回标记（UI 用它提示用户，但不阻止翻译） */
        const val EXTRA_JSON_INVALID = "extra_json_invalid"

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
            val base = s.trimEnd('/').removeSuffix(tail).trimEnd('/')
            val url = base.toHttpUrlOrNull() ?: return null
            return url.newBuilder().addPathSegments(tail.trimStart('/')).build()
        }

        /** 模型列表端点：`{BaseURL}{ModelsPath}`（默认 `/models`） */
        internal fun modelsUrl(baseUrl: String, modelsPath: String): HttpUrl? =
            joinUrl(baseUrl, modelsPath.trim().ifEmpty { DEFAULT_MODELS_PATH })

        /**
         * 自定义 Headers 解析：一行一条 `Key: Value`；空行与 `#` 注释行忽略；
         * **`Authorization` 一律跳过** —— 它由 API Key 字段独占，两处配置只会互相打架（文档要求）。
         */
        internal fun parseHeaders(raw: String): List<Pair<String, String>> =
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(':') }
                .mapNotNull { line ->
                    val name = line.substringBefore(':').trim()
                    val value = line.substringAfter(':').trim()
                    // 名字必须是合法 HTTP token（RFC 7230）：中文名 / 含空格的名字会让
                    // `Request.Builder.header` 抛 IllegalArgumentException，被 TranslationClient
                    // 兜成 PARAM → 提示「请检查语言设置」，与真实原因（自定义 Headers 写错）
                    // 完全无关（2026-09-30 审查发现）。
                    // **值同样要校验**（第二轮审查，探针实测）：OkHttp 对值只允许 `\t` 与 U+0020..U+007E，
                    // 中文值、或从网页复制来的 NBSP / ZWSP（`trim()` 不删它们！）都会抛同一个异常、
                    // 得到同一个误导提示。
                    val valueOk = value.all { it == '\t' || it in '\u0020'..'\u007e' }
                    if (name.isEmpty() || name.equals("Authorization", ignoreCase = true) ||
                        !name.all { it in HEADER_TOKEN_CHARS } || !valueOk
                    ) {
                        null
                    } else {
                        name to value
                    }
                }
                .toList()

        /** HTTP header 名的合法字符集（RFC 7230 `token`）：字母数字与 `!#$%&'*+-.^_\`|~` */
        private const val HEADER_TOKEN_CHARS =
            "!#\$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /**
         * 提示词变量替换：`{{text}}` / `{{target_language}}` / `{{source_language}}` / `{{date}}`。
         *
         * 认不出的变量**原样保留**：用户可能自己引入业务变量（配合 Custom JSON 用），不该被吃掉。
         */
        internal fun applyTemplate(
            template: String,
            text: String,
            target: String,
            now: Date = Date(),
        ): String = template
            .replace(VAR_TARGET, target)
            .replace(VAR_SOURCE, SOURCE_AUTO_LABEL)
            .replace(VAR_DATE, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
            // ⚠ 正文必须**最后**注入（2026-09-30 审查发现）：先注入的话，用户正文里恰好含
            //    `{{target_language}}` 这类占位符（提示词模板、配置文档、代码片段都很常见）
            //    会被后续 replace 二次改写，发给模型的原文与屏幕上的原文不再一致。
            .replace(VAR_TEXT, text)

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
            val target = config.targetLanguage.trim().ifEmpty { DEFAULT_TARGET_LANGUAGE }
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
                                .put("content", applyTemplate(user, text, target)),
                        ),
                )
            putNumber(body, "temperature", config.temperature)
            putNumber(body, "top_p", config.topP)
            putNumber(body, "max_tokens", config.maxTokens)
            mergeExtraJson(body, config.extraJson)
            return body
        }

        /** 数字参数入体：null（未填/非法）直接跳过，绝不默认发送 */
        private fun putNumber(body: JSONObject, key: String, raw: String) {
            val d = numberOrNull(raw) ?: return
            val value: Any = if (d == Math.floor(d)) d.toLong() else d
            body.put(key, value)
        }

        /**
         * 合并用户自定义 JSON：**同名键覆盖**标准参数，新键追加。
         *
         * 解析失败返回 `false`（UI 据此提示格式问题），但**不阻止翻译** —— JSON 写错不该把功能打死。
         */
        internal fun mergeExtraJson(body: JSONObject, extraJson: String): Boolean {
            val raw = extraJson.trim()
            if (raw.isEmpty()) return true
            val extra = runCatching { JSONObject(raw) }.getOrNull() ?: return false
            for (key in extra.keys()) body.put(key, extra.get(key))
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
                // 于是下拉里多出一项 `null`，用户选中就写进 model（2026-09-30 第二轮审查，同类漏网）
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
