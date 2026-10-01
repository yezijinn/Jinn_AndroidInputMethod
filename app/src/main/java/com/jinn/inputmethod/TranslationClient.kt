package com.jinn.inputmethod

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 翻译请求的执行器：Provider 选择、HTTPS 判据、超时、错误归一，一处收口。
 *
 * 与语音链路的关系：**不复用** [AsrClient.http]（那是局域网 WebSocket 专用：`Proxy.NO_PROXY`
 * 且 `readTimeout(0)` 无读超时），翻译走公网 REST，自建一个 `by lazy` 客户端即可；
 * 也不进 [BackgroundIo]（那是剪贴板单线程队列，一次 10~25s 的请求会把面板查询堵死）。
 *
 * 线程约定：`onDone` 可能在 **OkHttp 的 IO 线程**触发，但「请求构造失败 / 非 HTTPS」两条早退
 * 路径在**调用方线程同步触发** —— 调用方一律按「线程不确定」处理（IME 侧统一 `ui.post` 回主线程）。
 * 项目没有引入 kotlinx-coroutines，异步统一走 OkHttp 回调 + 主线程 Handler（与 AsrClient 一致）。
 */
internal object TranslationClient {

    private const val TAG = "TranslationClient"

    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC = 20L

    /** 整体预算：覆盖连接 + 读写 + 重定向，防止「慢响应」把按钮的「翻译中」挂死 */
    private const val CALL_TIMEOUT_SEC = 25L

    /**
     * 结构化错误摘要里错误码的最大长度。
     *
     * 2026-10-01 审查 L-214 从 40 收到 24：40 恰好装得下一把完整的 API Key（`sk-…` 常见
     * 30~50 字符），与下面 [looksLikeCredential] 的负判据一起收紧。
     */
    private const val MAX_ERROR_CODE_CHARS = 24

    /** 错误码里出现这些**前缀**即视为「凭据被回显」（大小写不敏感） */
    private val CREDENTIAL_PREFIXES = listOf("sk-", "sk_", "pk-", "ak-", "ltai", "gsk_", "hf_")

    /**
     * 响应体读取上限（字节）：正常译文响应只有几百字、错误体也就几 KB，256KB 足够宽松，
     * 同时挡住「Base URL 指到一个返回大文件/大 HTML 的站点时整段读进内存」的内存尖峰
     * （2026-09-30 第二轮审查：`body.string()` 会让 10MB 响应变成 20MB 的 char[]，
     * 非 JSON 时 org.json 还会把输入串拼进异常消息再复制一份）。
     */
    private const val MAX_BODY_BYTES = 256L * 1024

    /**
     * 只走 TLS 1.2+，不跟随跨协议跳转：翻译地址是常量 HTTPS，任何降级都是异常。
     * 不设 `Proxy.NO_PROXY`（用户可能处在本机代理环境，与局域网直连的语音链路不同）。
     *
     * 协议不锁定：曾疑某网关的 503 与 h2 有关，实测把协议固定成 HTTP/1.1 后**依然是 503**
     * （真机日志 `HTTP 503 http/1.1`），遂回滚该假设 —— h2 是 OkHttp 默认，性能更好，
     * 没有证据就不动它。响应日志里保留协议名，便于下次遇到同类现象时一眼分辨。
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followSslRedirects(false)
            .build()
    }

    /**
     * 按当前选择的 Provider 组装实现；**凭据不全时返回 null**（调用方据此提示「先配置」）。
     *
     * 纯函数（不碰 Android API 与网络），JVM 单测直接覆盖「未配置 / 六种 Provider」。
     * 六家凭据互不串用：选了哪家就只看那家的键。
     */
    fun providerOf(
        id: TranslationProviderId,
        azureKey: String,
        azureRegion: String,
        baiduAppId: String,
        baiduSecret: String,
        aliyunKeyId: String,
        aliyunKeySecret: String,
        deeplKey: String,
        baiduLlmAppId: String,
        baiduLlmApiKey: String,
        openAi: OpenAiConfig,
    ): TranslationProvider? = when (id) {
        // 所有凭据统一走 cleanCredential()：**先剥不可见字符（NBSP / 零宽 / BOM）再 trim**。
        // 从网页/文档复制 Key 时这些字符极常见，而 trim 不删它们；带进 OkHttp 的 header 会抛
        // IllegalArgumentException（探针实测 `Unexpected char 0xa0`），被本文件的 runCatching 兜成
        // PARAM → 用户看到「请检查语言设置」，与真实原因（肉眼不可见的字符）完全无关
        // （2026-09-30 第二轮审查发现）。
        TranslationProviderId.ALIYUN -> {
            val keyId = aliyunKeyId.cleanCredential()
            val secret = aliyunKeySecret.cleanCredential()
            if (keyId.isEmpty() || secret.isEmpty()) null else AliyunTranslator(keyId, secret)
        }

        TranslationProviderId.AZURE -> azureKey.cleanCredential()
            .takeIf { it.isNotEmpty() }
            ?.let { AzureTranslator(it, azureRegion.cleanCredential()) }

        TranslationProviderId.BAIDU -> {
            val appId = baiduAppId.cleanCredential()
            val secret = baiduSecret.cleanCredential()
            if (appId.isEmpty() || secret.isEmpty()) null else BaiduTranslator(appId, secret)
        }

        TranslationProviderId.BAIDU_LLM -> {
            val appId = baiduLlmAppId.cleanCredential()
            val key = baiduLlmApiKey.cleanCredential()
            if (appId.isEmpty() || key.isEmpty()) null else BaiduLlmTranslator(appId, key)
        }

        TranslationProviderId.DEEPL -> deeplKey.cleanCredential()
            .takeIf { it.isNotEmpty() }
            ?.let { DeepLTranslator(it) }

        TranslationProviderId.OPENAI -> {
            // config 是从 Prefs 原样组装的：清洗后再交给 Provider（否则 Key 带 NBSP 进 header 会抛）
            val cleaned = openAi.copy(
                apiKey = openAi.apiKey.cleanCredential(),
                model = openAi.model.cleanCredential(),
                baseUrl = openAi.baseUrl.cleanCredential(),
            )
            // 端点解析不出来（Base URL 乱填）等同「未配置」：不把非法地址交给网络层
            val endpoint = OpenAiTranslator.joinUrl(cleaned.baseUrl, cleaned.chatPath)
            if (cleaned.apiKey.isEmpty() || cleaned.model.isEmpty() || endpoint == null) {
                null
            } else {
                OpenAiTranslator(cleaned)
            }
        }
    }

    /**
     * 发起一次翻译（异步）。
     *
     * 只在这里做三件事：构造请求（失败即归 PARAM）、钉死 HTTPS、执行并归一错误；
     * 响应解析交给 Provider（纯函数）。日志只记 Provider 类名、语言、字数与响应码，
     * **凭据与正文一个字都不进日志**。
     */
    fun translate(
        provider: TranslationProvider,
        text: String,
        target: TranslationLanguage,
        onDone: (TranslationOutcome) -> Unit,
    ) {
        val request = runCatching { provider.buildRequest(text, target) }.getOrElse {
            Diagnostics.w(TAG, "翻译请求构造失败: ${it.javaClass.simpleName}")
            onDone(TranslationOutcome.Fail(TranslationError.PARAM))
            return
        }
        // HTTPS 硬判据（与 UpdateChecker 同款）：地址被改成明文一律拒发。
        // 归 INSECURE 而不是 SERVER：这是**用户可修的配置问题**（自定义 Base URL 写成 http://），
        // 提示「翻译服务异常」会让用户无从下手（2026-09-30 审查发现）。
        if (!request.url.isHttps) {
            Diagnostics.w(TAG, "翻译地址不是 HTTPS，拒绝发送")
            onDone(TranslationOutcome.Fail(TranslationError.INSECURE))
            return
        }
        Diagnostics.i(TAG, "翻译请求: ${provider.javaClass.simpleName} → ${target.name} len=${text.length}")
        // Provider 可以要求更宽的时间预算（大模型首字延迟不可控）：0 = 用客户端默认。
        // ⚠ 必须**派生 client** 同时放宽 readTimeout —— 只设 `Call.timeout()`（整体预算）时，
        // client 级的 20s 读超时依旧先生效（真机实测：20s 到点抛 SocketTimeoutException）。
        // `newBuilder()` 复用连接池与线程池，成本可忽略。
        val client = if (provider.callTimeoutSec > 0) {
            http.newBuilder()
                .readTimeout(provider.callTimeoutSec.toLong(), TimeUnit.SECONDS)
                .callTimeout(provider.callTimeoutSec.toLong(), TimeUnit.SECONDS)
                .build()
        } else {
            http
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // SocketTimeoutException 与 callTimeout 的 InterruptedIOException 同族，一并归超时
                val error = if (e is InterruptedIOException) {
                    TranslationError.TIMEOUT
                } else {
                    TranslationError.NETWORK
                }
                Diagnostics.w(TAG, "翻译失败: ${e.javaClass.simpleName} → $error")
                onDone(TranslationOutcome.Fail(error))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = readBodyCapped(it)
                    // 记协议：排障时「走的哪个协议」是第一个要分清的事（曾疑 503 与 h2 有关，实测排除）
                    // len 恰好顶到上限时**可能是被截断的**（peekBody 的语义）：标出来，免得把
                    // 「半截 JSON 解析失败」当成服务端问题（2026-10-01 修复 L-243）
                    // 按 **UTF-8 字节**比上限（`peekBody` 也是按字节截断）：用字符数会在多字节译文下
                    // 恒小于上限，标记永远不亮 —— 正好抵消了它要消除的那类误导（2026-10-01 修复 L-257）
                    val capped = body != null &&
                        body.toByteArray(Charsets.UTF_8).size.toLong() >= MAX_BODY_BYTES
                    Diagnostics.i(
                        TAG,
                        "翻译响应: HTTP ${it.code} ${it.protocol} len=${body?.length ?: 0}" +
                            (if (capped) "(可能已截断)" else ""),
                    )
                    // 失败时只记**结构化摘要**（长度 + 白名单错误码），绝不记响应体原文：
                    // 错误体是服务端可控文本，OpenAI 兼容网关的 401 常规形态就是回显提交的 Key
                    // （`Incorrect API key provided: sk-…`），而日志会落盘并随「导出诊断包」外发，
                    // Diagnostics 的脱敏只挡手机号/邮箱/纯数字，挡不住 sk- 形态的凭据。
                    if (it.code !in 200..299) {
                        Diagnostics.w(TAG, "翻译失败响应: HTTP ${it.code} ${errorSummary(body)}")
                    }
                    val outcome = provider.parseResponse(it.code, body)
                    // 2xx 也可能是错误（网关常用 200 承载限额 / 欠费；截断后的半截 JSON 同样解析失败）：
                    // 只留一行 len 的话，用户看到「翻译服务异常」而日志里查不出为什么（2026-10-01 修复 L-243）
                    if (it.code in 200..299 &&
                        outcome is TranslationOutcome.Fail &&
                        outcome.error == TranslationError.SERVER
                    ) {
                        Diagnostics.w(TAG, "翻译失败响应(2xx): HTTP ${it.code} ${errorSummary(body)}")
                    }
                    onDone(outcome)
                }
            }
        })
    }

    /**
     * 「获取模型 / 测试连接」：`GET {Base}{ModelsPath}` + Bearer。
     *
     * [onDone] 在 OkHttp 的 IO 线程触发（调用方自行切主线程）：成功 `(models, code)`；
     * HTTP 失败 `(null, code)`；网络失败 `(null, -1)`；**地址非 HTTPS `(null, -2)`**；
     * **凭据含非法字符 `(null, -3)`**。
     * 服务端未实现 `/models` 属常态，调用方不得据此判定配置错误（文档要求）。
     *
     * 返回 `Call` 供调用方取消（2026-09-30 第二轮审查）：超时上限可到 300s，页面在响应回来前
     * 就被关掉时，不取消会让回调闭包**持有 Activity 最多 5 分钟**；早退路径返回 null。
     */
    fun fetchModels(
        url: HttpUrl,
        apiKey: String,
        timeoutSec: Int,
        onDone: (List<String>?, Int) -> Unit,
    ): Call? {
        // HTTPS 硬判据与 translate 同款（2026-09-30 审查发现：这里曾漏掉）：Base URL 填成
        // `http://…` 时 Bearer Key 会**明文上网**，而平台给语音局域网放行了明文（base-config），
        // 不会拦这次请求 —— 判据必须在代码里。code = -2 专指「地址不是 HTTPS」，与 -1（网络失败）
        // 区分开，调用方才能给出「可修」的提示。
        if (!url.isHttps) {
            Diagnostics.w(TAG, "获取模型: 地址不是 HTTPS，拒绝发送")
            onDone(null, -2)
            return null
        }
        val builder = Request.Builder().url(url).get()
        // Key 里混入 NBSP / 零宽字符时 `header()` 会抛 IllegalArgumentException（同步、在调用方
        // 线程，会直接冒到设置页）—— 与 translate 侧同款兜底，归「凭据不可用」（2026-10-01 审查 L-228）
        val headerOk = runCatching {
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        }.isSuccess
        if (!headerOk) {
            Diagnostics.w(TAG, "获取模型: 凭据含非法字符，拒绝发送")
            onDone(null, -3)
            return null
        }
        Diagnostics.i(TAG, "获取模型: ${url.host}${url.encodedPath}")
        val client = if (timeoutSec > 0) {
            http.newBuilder()
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .build()
        } else {
            http
        }
        val call = client.newCall(builder.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Diagnostics.w(TAG, "获取模型失败: ${e.javaClass.simpleName}")
                onDone(null, -1)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = readBodyCapped(it)
                    if (it.code in 200..299) {
                        val models = OpenAiTranslator.parseModels(body)
                        Diagnostics.i(TAG, "获取模型: HTTP ${it.code} 共${models.size}个")
                        onDone(models, it.code)
                    } else {
                        // 与 translate 同口径：只记结构化摘要，响应体原文绝不进日志（可能回显 Key）
                        Diagnostics.w(TAG, "获取模型失败: HTTP ${it.code} ${errorSummary(body)}")
                        onDone(null, it.code)
                    }
                }
            }
        })
        return call
    }

    /**
     * 读取响应体，**带上限**（[MAX_BODY_BYTES]）：用 `peekBody` 截断而不是直接 `string()`。
     *
     * 为什么要有闸（2026-09-30 第二轮审查）：Base URL 填错指向一个返回大文件/大 HTML 的站点时，
     * `body.string()` 会把整段内容读进内存，非 JSON 时 org.json 还会把输入串拼进异常消息再复制一份；
     * 内存尖峰最坏会 OOM —— 设置页与 IME 同进程，一起带走。正常译文响应只有几百字，不会误截。
     */
    private fun readBodyCapped(response: Response): String? =
        runCatching { response.peekBody(MAX_BODY_BYTES).string() }.getOrNull()

    /**
     * 失败响应体的**结构化摘要**（只进日志）：长度 + 白名单错误码，**永不记自由文本**。
     *
     * 为什么不能记原文：错误体是**服务端可控文本** —— OpenAI 兼容网关的 401 常规形态就是回显
     * 提交的 Key（`Incorrect API key provided: sk-…`）、百度系会回显请求字段，而日志会落盘并随
     * 「导出诊断包」外发；[Diagnostics] 的脱敏只挡手机号/邮箱/纯数字串，挡不住 `sk-` / hex 形态的
     * 凭据（2026-09-30 审查发现）。
     *
     * 错误码本身也只收「短标识符形态」（字母数字与 `_.-`，≤[MAX_ERROR_CODE_CHARS]）：含空格、
     * 引号、换行的内容一律丢弃 —— 那些正是自由文本的特征，也正是回显 Key 时会出现的东西。
     */
    internal fun errorSummary(body: String?): String {
        if (body.isNullOrBlank()) return "body=空"
        val json = runCatching { org.json.JSONObject(body) }.getOrNull()
            ?: return "body=非JSON(len=${body.length})"
        val code = listOf(
            json.optJSONObject("error")?.opt("code"),
            json.opt("error_code"),
            json.opt("Code"),
            json.opt("code"),
        ).firstOrNull { it != null && it != org.json.JSONObject.NULL }
            ?.toString()
            ?.take(MAX_ERROR_CODE_CHARS)
            ?.takeIf { s ->
                // 白名单必须是**ASCII** 显式区间：`isLetterOrDigit()` 对非 ASCII 也为真，
                // 一串连续中文（≤24 字、无空格）会被当成「错误码」写进日志（2026-10-01 修复 L-243）
                s.isNotEmpty() &&
                    s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.-" } &&
                    !s.looksLikeCredential()
            }
        return if (code == null) "body=len${body.length}" else "body=len${body.length} code=$code"
    }

    /**
     * 凭据形态的负判据（2026-10-01 审查 L-214）。
     *
     * 白名单字符集 `[A-Za-z0-9_.-]` 恰好覆盖 API Key 的典型形态 —— `sk-proj-Ab12Cd34Ef56Gh78Ij90Kl.`
     * 逐字通过它；网关把提交的 Key 回显在 `code` / `error_code` 字段时（不是 `message`），
     * 这段凭据就会落盘并随「导出诊断包」外发。这里按「已知前缀 + 高熵形态」两道判据拦截：
     * 宁可少记一条错误码，也不让凭据出网。
     */
    private fun String.looksLikeCredential(): Boolean {
        val lower = lowercase(Locale.US)
        if (CREDENTIAL_PREFIXES.any { lower.startsWith(it) }) return true
        // 高熵：长且大小写与数字混排 —— 正常错误码（invalid_api_key / SignatureDoesNotMatch /
        // 54001 / 10004）极少三条同时满足
        return length >= 20 &&
            any { it.isUpperCase() } &&
            any { it.isLowerCase() } &&
            any { it.isDigit() }
    }
}
