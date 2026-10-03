package com.jinn.inputmethod

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /** 整体预算：覆盖连接 + 读写，防止「慢响应」把按钮的「翻译中」挂死（重定向已关，见 http 的 followRedirects） */
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
            // 翻译端点是**用户自填**的（中转 / 聚合网关是常见用法）：跟随重定向意味着一个不太可信
            // 的网关回 307/308 时，能把 POST 与**用户正文**原样重发到 `Location` 指定的任意主机，
            // 响应还会被当成正常译文插进输入框。翻译端点没有合理的跳转需求（2026-10-01 修复 L-306）
            // —— 词库下载那个客户端早已出于同样的理由关掉了跨协议跳转。
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 按当前选择的 Provider 组装实现；**凭据不全时返回 null**（调用方据此提示「先配置」）。
     *
     * 纯函数（不碰 Android API 与网络），JVM 单测直接覆盖「未配置 / 六种 Provider」。
     * 六家凭据互不串用：选了哪家就只看那家的键。
     */
    /**
     * 按 [id] 组装 Provider。
     *
     * ⚠ 非目标家的凭据参数**留默认空值**即可（2026-10-03 修复 L-530 / L-531）：Kotlin 的参数在
     * 调用前就会求值，调用方若把关凭据的 getter 全列上，冷缓存下一次翻译就要在主线程做最多 8 次
     * TEE 解密（5~20ms/次 = 40~160ms 停顿），而本次只用其中一家。给默认值后，调用方按 `id`
     * **只传该家的凭据**，其余保持空。
     */
    fun providerOf(
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
        // 一次点击的关联 ID（2026-10-03 修复 L-615）：形参带默认值 ⇒ 既有调用点与测试
        // 一个都不用改；IME 侧传 `Diagnostics.traceId("TR")`，响应日志据此前缀，
        // 诊断包里就能把「发起 → 响应 → 分类 → 提交/拒绝」按**一次点击**归并。
        traceId: String = "",
        onDone: (TranslationOutcome) -> Unit,
    ): Call? {
        val request = runCatching { provider.buildRequest(text, target) }.getOrElse {
            Diagnostics.w(TAG, "翻译请求构造失败: ${it.javaClass.simpleName}")
            // 凭据含 Header 非法字符（`IllegalArgumentException`）单独归因（2026-10-02 修复 L-429）：
            // 归 PARAM 会把用户引向「语言方向 / 模型名 / 路径」——那是他刚检查过的东西；
            // 真因在 Key 里混了不可见字符（与 fetchModels 的 -3 / TEXT_KEY_INVALID 同源）。
            // 其余构造期异常（URL 非法等）仍归 PARAM。
            val error = if (it is IllegalArgumentException) {
                TranslationError.CREDENTIAL
            } else {
                TranslationError.PARAM
            }
            onDone(TranslationOutcome.Fail(error))
            return null
        }
        // HTTPS 硬判据（与 UpdateChecker 同款）：地址被改成明文一律拒发。
        // 归 INSECURE 而不是 SERVER：这是**用户可修的配置问题**（自定义 Base URL 写成 http://），
        // 提示「翻译服务异常」会让用户无从下手（2026-09-30 审查发现）。
        if (!request.url.isHttps) {
            Diagnostics.w(TAG, "翻译地址不是 HTTPS，拒绝发送")
            onDone(TranslationOutcome.Fail(TranslationError.INSECURE))
            return null
        }
        // 补端点 host（2026-10-03 修复 L-619）：六家里 `OpenAiTranslator` **一个类**覆盖无数
        // baseUrl / model 组合（中转、自建网关是常见用法），只记类名分不清是哪个端点。
        // ⚠ 只记 `host + encodedPath`（项目统一口径）——**绝不记 query**：百度系把 appid/sign
        // 直接放在 query 上，`q` 还是用户正文。
        Diagnostics.i(
            TAG,
            "${traceTag(traceId)}翻译请求: ${provider.javaClass.simpleName} → ${target.name} " +
                "len=${text.length} @${request.url.host}${request.url.encodedPath}",
        )
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
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 主动取消不是故障（2026-10-03 修复 L-604）：会话边界（切框 / 收起键盘 / 旋转 /
                // 销毁）会调 `call.cancel()`，OkHttp 必然回调这里一次，异常是
                // `IOException("Canceled")`（**不是** InterruptedIOException）⇒ 原先会被记成
                // `翻译失败: IOException → NETWORK`。用户报「翻译老是失败」时，诊断包里
                // 无法区分「他自己切了框」与「真断网」。调用方那边靠代际丢弃、不受影响。
                if (call.isCanceled()) {
                    Diagnostics.i(TAG, "翻译: 请求已被取消（会话边界，不是网络故障）")
                    return
                }
                // SocketTimeoutException 与 callTimeout 的 InterruptedIOException 同族，一并归超时
                val error = if (e is InterruptedIOException) {
                    TranslationError.TIMEOUT
                } else {
                    TranslationError.NETWORK
                }
                Diagnostics.w(TAG, traceTag(traceId) + "翻译失败: ${e.javaClass.simpleName} → $error")
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
                    val capped = isBodyCapped(body)
                    Diagnostics.i(
                        TAG,
                        traceTag(traceId) + "翻译响应: HTTP ${it.code} ${it.protocol} chars=${body?.length ?: 0}" +
                            " bytes=${bodyBytes(body)}" + (if (capped) "(可能已截断)" else ""),
                    )
                    // 3xx：重定向是**刻意关闭**的（L-306，防不可信网关把用户正文转投到
                    // `Location` 指定的任意主机），所以这里不会自动跟随 —— 但原先 3xx 与 5xx
                    // 同归 SERVER ⇒ 「网关尾斜杠归一 / http→https 升级 / 路径补全」这类
                    // **用户可修**的问题被显示成「翻译服务异常」，用户只会反复重试（每次真计费）。
                    // 单列一条指向真因（2026-10-03 修复 L-600）。
                    if (it.code in 300..399) {
                        // 只记 Location 的 **host**：全 URL 可能带 query（凭据形态），不能落盘
                        val locHost = it.header("Location")
                            ?.let { loc -> runCatching { loc.toHttpUrlOrNull()?.host }.getOrNull() }
                        Diagnostics.w(TAG, traceTag(traceId) + "翻译端点返回重定向: HTTP ${it.code} → ${locHost ?: "无可解析 Location"}")
                        onDone(TranslationOutcome.Fail(TranslationError.REDIRECT))
                        return@use
                    }
                    // 2xx 但**不是 JSON**：公共 WiFi 的门户页 / 企业 MITM 代理会回 200 + text/html
                    // 登录页 ⇒ 原先归 SERVER（「请稍后重试」），而真因是「网络还没认证」，
                    // 重试一百次也没用（2026-10-03 修复 L-601）。归 NETWORK 与 DNS / 私有 CA
                    // 失败（已正确归 NETWORK 并提示「检查网络」）口径一致。
                    // 空 body 不算：那是「网关返回空」的既有分支，交给各 Provider 处理。
                    if (it.code in 200..299 && body != null && body.isNotBlank() && !looksLikeJson(body)) {
                        Diagnostics.w(
                            TAG,
                            "翻译响应不是 JSON（可能被登录门户 / 代理拦截）: HTTP ${it.code} " +
                                "ct=${headerBrief(it.header("Content-Type"))}",
                        )
                        onDone(TranslationOutcome.Fail(TranslationError.NETWORK))
                        return@use
                    }
                    // 失败时只记**结构化摘要**（长度 + 白名单错误码），绝不记响应体原文：
                    // 错误体是服务端可控文本，OpenAI 兼容网关的 401 常规形态就是回显提交的 Key
                    // （`Incorrect API key provided: sk-…`），而日志会落盘并随「导出诊断包」外发，
                    // Diagnostics 的脱敏只挡手机号/邮箱/纯数字，挡不住 sk- 形态的凭据。
                    if (it.code !in 200..299) {
                        // 401/403 补 UA 与 `Server:` 摘要（2026-10-03 修复 L-606）：翻译请求不带
                        // 自定义 UA（用的是 `okhttp/4.x`），Cloudflare / WAF 类网关按 UA 拦时回 403，
                        // 而那会被归成 AUTH（「请检查凭据」）⇒ 用户反复核对正确的 Key 也无效。
                        // 有了这两项，「网关拦 UA」与「Key 无效」在诊断包里才分得开。
                        // ⚠ 两者都是服务端可控文本：只取头部、截断 64 字符，且绝不记 URL / query。
                        val gateway = if (it.code == 401 || it.code == 403) {
                            // ⚠ 走 headerBrief 而不是 take(64)（2026-10-03 修复 L-620）：
                            // 这两个头是服务端可控的，可能回显我们发出去的凭据（含 32 位纯 hex 形态，
                            // 而脱敏表刻意不含该形态）⇒ 命中凭据即**完全不记**。
                            " ua=${headerBrief(it.header("User-Agent"))} " +
                                "server=${headerBrief(it.header("Server"))}"
                        } else {
                            ""
                        }
                        Diagnostics.w(TAG, traceTag(traceId) + "翻译失败响应: HTTP ${it.code} ${errorSummary(body)}$gateway")
                    }
                    // 解析也要兜底（2026-10-02 加固）：此刻 OkHttp 已置 `signalledCallback=true`，
                    // 从这里抛出的 Throwable 不会被回落到 onFailure，而是冒到 dispatcher 线程 ⇒
                    // 未捕获处理器接手 ⇒ **整个 IME 进程终止**（键盘消失、未上屏拼音与在途翻译一起丢）。
                    // 六家 Provider 的契约是「自己兜住非法 JSON」，但那是**约定**不是机械约束 ——
                    // 这里再兜一层：第七家写漏时最多丢一次结果，不会杀进程。
                    val outcome = runCatching { provider.parseResponse(it.code, body) }
                        .getOrElse {
                            Diagnostics.w(TAG, traceTag(traceId) + "翻译响应解析异常: ${it.javaClass.simpleName}")
                            TranslationOutcome.Fail(TranslationError.SERVER)
                        }
                    // 2xx 也可能是错误（网关常用 200 承载限额 / 欠费；截断后的半截 JSON 同样解析失败）：
                    // 只留一行 len 的话，用户看到「翻译服务异常」而日志里查不出为什么（2026-10-01 修复 L-243）
                    if (it.code in 200..299 &&
                        // 不再限定 SERVER（2026-10-01 审查 L-274）：原条件把「2xx 承载额度 / 认证类
                        // 失败」挡在门外，而这段逻辑的目标恰恰是网关用 200 报限额与欠费 —— 用户报
                        // 「额度不足」时诊断包里只剩 `翻译响应: HTTP 200 len=…`，分不清限流与欠费。
                        outcome is TranslationOutcome.Fail
                    ) {
                        Diagnostics.w(TAG, traceTag(traceId) + "翻译失败响应(2xx): HTTP ${it.code} ${errorSummary(body)}")
                    }
                    // 唯一出口做一次包装剥离（六家共用；2026-10-02 修复 L-321）：
                    // 模型的 ``` 围栏与「译文：」前缀不能原样插进用户的输入框
                    val final: TranslationOutcome = if (outcome is TranslationOutcome.Ok) {
                        val translated = TranslationText.stripWrapper(outcome.text)
                        // 「少给行」对拍（2026-10-02 修复 L-483）：按行对齐的 Provider（百度两家）靠**顺序**
                        // 把 `trans_result` 对应到输入行；服务端对空行 / 被跳过的行若不返回元素，其后所有行
                        // 上移一位 ⇒ 译文与原文**逐行错位**，界面照报 Ok 并把整段错位译文追加进用户正文。
                        // 解析侧手里只有响应体、无法自证；出口手上有**实际送出的原文**（`text` 参数本身就是
                        // 送出的内容，含截断后的形态）⇒ 在这里对拍。
                        // 只在**少**的时候拒绝：多出行可能只是译文自带换行，拒绝反而误伤。
                        // ⚠ 空行**不参与**对拍（2026-10-02 第五轮审查的回归修正）：服务端对空行
                        // 根本不返回元素（百度系 `trans_result` 只给有内容的行），把空行算进 expected
                        // 会让「原文含空行」的整篇 / 整行翻译**必然被拒** —— 而段落之间有空行是常态，
                        // 等于百度家在默认场景下 100% 失败。两边都按**非空行**计数，口径一致。
                        val expected = text.split('\n').count { it.isNotBlank() }
                        val actual = translated.split('\n').count { it.isNotBlank() }
                        if (provider.alignsPerLine && actual < expected) {
                            Diagnostics.w(
                                TAG,
                                "翻译: 服务端少给行（译文 $actual 行 < 原文 $expected 行）—— 逐行会错位，" +
                                    "不写入正文（L-483）",
                            )
                            // 专用错误项：复用 PARAM 会把用户引去向「语言方向 / 模型名 / 路径 /
                            // 原文长度」核对，而真因是服务端少给了行（2026-10-02 第五轮审查）
                            TranslationOutcome.Fail(TranslationError.LINE_MISMATCH)
                        } else {
                            TranslationOutcome.Ok(translated)
                        }
                    } else {
                        outcome
                    }
                    onDone(final)
                }
            }
        })
        // 返回句柄给调用方（IME 侧据此**真取消**，见 JinnIme.finishTranslate；2026-10-03 修复 L-494）
        return call
    }

    /**
     * 「获取模型 / 测试连接」：`GET {Base}{ModelsPath}` + Bearer。
     *
     * [onDone] 可能在 **OkHttp 的 IO 线程**触发，但两条早退（非 HTTPS / 凭据含非法字符）在
     * **调用方线程同步触发** —— 与 [translate] 同款（2026-10-02 修复 L-437：此前这里只写了
     * 「在 IO 线程触发」，照它省掉 `runOnUiThread` 的后来者会踩到）。
     * 成功 `(models, code)`；HTTP 失败 `(null, code)`；网络失败 `(null, -1)`；
     * **地址非 HTTPS `(null, -2)`**；**凭据含非法字符 `(null, -3)`**。
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
                        // 与 `translate` 同口径记长度与截断标记（2026-10-02 修复 L-457）：
                        // 响应超上限被截后只剩一句「共 0 个」，用户看到的是
                        // 「服务端返回了空列表（不代表配置错误）」，而日志里查不出为什么。
                        Diagnostics.i(
                            TAG,
                            "获取模型: HTTP ${it.code} 共${models.size}个 chars=${body?.length ?: 0}" +
                                " bytes=${bodyBytes(body)}" +
                                (if (isBodyCapped(body)) "(可能已截断)" else ""),
                        )
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
    /** 日志前缀：有 traceId 时是 `[TR-xxxx] `，没有时是空串（2026-10-03 修复 L-615） */
    private fun traceTag(traceId: String): String = if (traceId.isEmpty()) "" else "[$traceId] "

    /**
     * 32~64 位纯 hex —— Azure 订阅密钥 / 百度系 SecretKey 的形态。
     *
     * `Diagnostics.API_KEY_RES` **刻意不含**这一形态（避免误伤哈希与摘要），所以它挡住「打印响应头」
     * 这条通道的唯一办法是在调用点判（2026-10-03 修复 L-620）。
     */
    private val HEX_CREDENTIAL_RE = Regex("\\b[0-9a-fA-F]{32,64}\\b")

    /**
     * 响应头的**安全摘要**（2026-10-03 修复 L-620）。
     *
     * `Content-Type` / `User-Agent` / `Server` 都是**服务端可控**文本：不可信网关可以把收到的
     * `Authorization: Bearer <key>` 回显在里面，而日志会落盘并随「导出诊断包」外发。
     * `Diagnostics` 的脱敏表对 `sk-` / `:fx` / `Bearer …` / `LTAI…` 有效，但**不认识裸的
     * 32 位纯 hex**（正是订阅密钥的形态）。
     *
     * 所以这里做两层：① 命中凭据形态（含纯 hex 长串）⇒ **完全不记**；
     * ② 其余只留可见 ASCII 并截断（顺带挡掉控制字符 / 换行注入进日志）。
     */
    private fun headerBrief(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return "无"
        if (v.looksLikeCredential() || HEX_CREDENTIAL_RE.containsMatchIn(v)) return "已隐去(疑似凭据)"
        val safe = v.filter { it in ' '..'~' }.take(48)
        return safe.ifEmpty { "无" }
    }

    /**
     * 响应体是否**看起来像 JSON**（跳过前导空白后首字符是 `{` 或 `[`）。
     *
     * 只用于「门户页判别」（2026-10-03 修复 L-601）：六家的合法响应无一例外都是 JSON 对象/数组，
     * 而门户页/代理的拦截页是 HTML。**不解析**、不做严格校验 —— 真正的 JSON 合法性仍由各
     * Provider 的 `parseResponse` 负责（它有自己的错误分支与文案），这里只挡「明显不是 JSON」那一类。
     */
    private fun looksLikeJson(body: String): Boolean {
        for (ch in body) {
            if (ch.isWhitespace()) continue
            return ch == '{' || ch == '['
        }
        return false
    }

    private fun readBodyCapped(response: Response): String? =
        runCatching { response.peekBody(MAX_BODY_BYTES).string() }.getOrNull()

    /**
     * 响应体的 UTF-8 字节数 —— 截断判据与日志**共用这一个换算**。
     *
     * 别处不要再用 `body.length` 当「长度」打印：那是 UTF-16 字符数，与字节口径相差 2~3 倍
     * （中文响应），两个数并排出现在一行日志里会让人按字符数去解释截断标记，方向正好相反
     *（2026-10-02 修复 L-467）。
     */
    private fun bodyBytes(body: String?): Long =
        body?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L

    /**
     * 响应体是否顶到了读取上限（**因此可能已被 `peekBody` 截断**）。
     *
     * 按 **UTF-8 字节**比（`peekBody` 也是按字节截断）：用字符数会在多字节译文下恒小于上限，
     * 标记永远不亮 —— 正好抵消了它要消除的那类误导。
     *
     * 两条读取链（`translate` 与 `fetchModels`）共用同一判据：原先只有前者记这个标记，
     * 后者在响应被截断时只剩一句「共 0 个」，与「服务端真的回了空列表」无法分辨
     *（2026-10-02 修复 L-457）。
     */
    private fun isBodyCapped(body: String?): Boolean = bodyBytes(body) >= MAX_BODY_BYTES

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
            // ⚠ 先判凭据形态、**后**截断（2026-10-02 修复）：`take(24)` 会把 DeepL 的识别特征
            // 后缀 `:fx` 截掉，于是 `looksLikeCredential()` 漏判（它只看前缀与高熵形态），
            // `Diagnostics` 的 `[A-Za-z0-9\-]{16,}:fx\b` 兜底也因后缀已丢而失效 ⇒
            // 该 Key 的前 24 字符会落盘并随「导出诊断包」外发（24 这个上限来自 L-214，
            // 从 40 收到 24 时正好把 `:fx` 从窗口里挤了出去）。
            ?.takeIf { !it.looksLikeCredential() }
            ?.take(MAX_ERROR_CODE_CHARS)
            ?.takeIf { s ->
                // 白名单必须是**ASCII** 显式区间：`isLetterOrDigit()` 对非 ASCII 也为真，
                // 一串连续中文（≤24 字、无空格）会被当成「错误码」写进日志（2026-10-01 修复 L-243）
                s.isNotEmpty() &&
                    s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.-"}
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
    internal fun String.looksLikeCredential(): Boolean {
        val lower = lowercase(Locale.US)
        if (CREDENTIAL_PREFIXES.any { lower.startsWith(it) }) return true
        // 后缀型（2026-10-02 修复）：DeepL 的 Free 密钥以 `:fx` 结尾，本体是 UUID 形态
        // （全小写 + 数字，**没有大写字母**）⇒ 「前缀 + 高熵」两条判据都不命中；
        // 若照抄进网关的错误码字段就会被当普通错误码记下并外发。
        if (lower.endsWith(":fx") || lower.endsWith("-fx")) return true
        // 高熵：长且大小写与数字混排 —— 正常错误码（invalid_api_key / SignatureDoesNotMatch /
        // 54001 / 10004）极少三条同时满足
        if (length >= 20 &&
            any { it.isUpperCase() } &&
            any { it.isLowerCase() } &&
            any { it.isDigit() }
        ) {
            return true
        }
        // 纯十六进制长串（2026-10-02 修复 L-479）：Azure 订阅密钥与百度系 SecretKey 的典型形态是
        // **全小写**（或全大写）的 32 位 hex，不含大小写混排 ⇒ 上面那条「高熵」判据整个漏判，
        // 网关把凭据回显在 `code` / `error_code` 字段时前 24 字符就会落盘并随诊断包外发。
        // 32~64 位、只有 hex 字符、无分隔符 —— 正常错误码极少长这样（宁可少记一条错误码）。
        return length in 32..64 && all { it in "0123456789abcdefABCDEF" }
    }
}
