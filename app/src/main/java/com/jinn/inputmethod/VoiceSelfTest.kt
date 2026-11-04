package com.jinn.inputmethod

import android.os.Handler
import android.os.Looper

/**
 * 「语音测试」：一次**自包含**的识别，用来回答「这套配置到底有没有效果」。
 *
 * 与 IME 的语音链路完全隔离 —— 自己新建 [SpeechClient] 与 [MicRecorder]，不碰 [JinnIme] 的
 * `asr` / `recorder` 实例，因此**不在输入状态**也能跑，且测完即断（不改变键盘的连线状态）。
 *
 * 流程固定为：`connect → beginTask → 喂 3 秒音频 → endTask → 等 final（超时 10 秒）`，
 * 全程由 30 秒的整体兜底收口（回调丢失也不会永远停在「正在录音…」）。
 * 结论三态（[VoiceTestVerdict]）：
 *  · 有文本 ⇒ `成功：识别到 N 字`（正文同时落进页面输入框，用户可直接核对）；
 *  · 连上但空结果 ⇒ `已连通，但没听到语音`（链路 OK、只是没声音/静音设备）；
 *  · 出错 ⇒ `失败：<服务端 code/message 或异常>`。
 *
 * 「空文本」与「失败」的界线是硬的：**空文本只有在这一段会话正常收尾（收到 final）时才算连通**；
 * 链路中途结束（`OFFLINE` / `IDLE`：连不上、`code != 0` 被服务端拒绝、对方关连接）一律按失败报，
 * 原因是客户端交出来的那句原话 —— 把「鉴权失败」说成「没听到语音」会让用户去查一个没坏的东西。
 *
 * 总开关关着也允许跑（测试是**诊断手段**），结论里注明「当前总开关关闭」，
 * 免得用户把「开关没开」误读成「链路坏了」。
 *
 * 线程模型：本类的方法都在主线程调用；[SpeechClient] 的回调在 OkHttp 的 IO 线程、
 * [MicRecorder] 的在采集线程，内部一律 `post` 回主线程再改状态。
 * 生命周期：[cancel] 必须由页面在 `onDestroy` 调用 —— 停录 + 断连 + 清空回调，否则会漏线程与麦克风占用。
 */
class VoiceSelfTest(
    private val prefs: Prefs,
    private val onResult: (Result) -> Unit,
) {

    /** 一次测试的结局：识别到的文本（可能为空）+ 给用户看的结论 */
    class Result(val text: String, val verdict: String)

    private val handler = Handler(Looper.getMainLooper())

    private var client: SpeechClient? = null
    private var recorder: MicRecorder? = null

    /** 只允许 finish 一次（超时与迟到结果会撞车） */
    private var finished = false

    /** 已进入「录音中」：防止 `ONLINE` 重复回调（重连成功）把采集起第二次 */
    private var captureStarted = false

    /**
     * [MicRecorder] 交出来的最近一次错误原因（采集线程写、主线程读）。
     *
     * 为什么必须留住：`MicRecorder.start()` 失败时**同步**调 `onError`，而 [beginCapture] 紧接着
     * 直接 `finish` —— 那条 `post` 排在后面，会被「已定稿」闸挡住，更具体的「麦克风被占用 / 权限被拒 /
     * 初始化失败」就被泛泛的 [TEXT_MIC_FAILED] 盖掉了。
     */
    @Volatile
    private var micError: String? = null

    private val stopRunnable = Runnable { stopAndAwaitFinal() }

    /**
     * 整体兜底：从点下按钮到出结论最多这么久。
     *
     * 为什么不能只靠分段超时：`connect` 与 `endTask` 都依赖客户端回调，回调一旦丢（ROM 吞状态变更、
     * 建连卡在握手、异常被 OkHttp 内部吞掉），页面就永远停在「正在录音…」、按钮永远禁用 ——
     * 用户只能退出页面。正常路径最长约 18 秒（建连 5 + 采集 3 + 等 final 10），30 秒是纯兜底。
     */
    private val overallRunnable = Runnable {
        finish(Result("", VoiceTestVerdict.of(null, TEXT_OVERALL_TIMEOUT, prefs.voiceInputEnabled)))
    }

    /** 末帧发出后最多等这么久（与 IME 侧的收尾等待同量级，够服务端跑完一段 3 秒音频） */
    private val timeoutRunnable = Runnable {
        finish(Result("", VoiceTestVerdict.of(null, TEXT_TIMEOUT, prefs.voiceInputEnabled)))
    }

    /** 开始测试（主线程）。前置校验不通过时**不发起任何请求**，直接给结论。 */
    fun start() {
        if (client != null || finished) return
        val backend = prefs.voiceBackend
        val denied = VoiceTestPreflight.check(
            backend = backend,
            appId = prefs.xfAppId,
            apiKey = prefs.xfApiKey,
            apiSecret = prefs.xfApiSecret,
            host = prefs.host,
            oaiBaseUrl = prefs.oaiBaseUrl,
            oaiApiKey = prefs.oaiApiKey,
            oaiModel = prefs.oaiModel,
            aliWsUrl = prefs.aliWsUrl,
            aliApiKey = prefs.aliApiKey,
            aliModel = prefs.aliModel,
            tencentSecretId = prefs.tencentSecretId,
            tencentSecretKey = prefs.tencentSecretKey,
            azureBaseUrl = prefs.azureBaseUrl,
            azureApiKey = prefs.azureSpeechKey,
            baiduApiKey = prefs.baiduSpeechApiKey,
            baiduSecretKey = prefs.baiduSpeechSecretKey,
            deepgramApiKey = prefs.deepgramApiKey,
        )
        if (denied != null) {
            finish(Result("", VoiceTestVerdict.of(null, denied, prefs.voiceInputEnabled)))
            return
        }
        // 后端分叉统一走 [SpeechClients]（与 JinnIme 同一处），这里不再自己 if/else
        val created: SpeechClient = SpeechClients.create(prefs, ::onState, ::onMessage)
        client = created
        Diagnostics.i(TAG, "开始语音测试 backend=$backend")
        handler.postDelayed(overallRunnable, TOTAL_TIMEOUT_MS)
        created.connect(force = true, userInitiated = true)
    }

    /** 页面销毁时必须调：停录 + 断连 + 撤掉所有待跑的回调 */
    fun cancel() {
        if (finished) return
        finished = true
        cleanup()
        Diagnostics.i(TAG, "语音测试取消（页面销毁）")
    }

    /** 连接状态回调（OkHttp IO 线程） */
    private fun onState(state: LinkState, detail: String?) {
        handler.post {
            if (finished) return@post
            when (state) {
                LinkState.ONLINE -> beginCapture()
                // OFFLINE / IDLE 都是「这条链路已经结束」，两种情况都要立刻给结论：
                //  · 还没开始录音 ⇒ 建连失败（连不上 / 凭据不全 / 地址非法）；
                //  · 已经在录音 ⇒ 服务端拒绝（`code != 0`：鉴权失败、appid 错、超额）、中途掉线、
                //    对方关连接。这些**绝不能算「已连通」**（那会写成「没听到语音」，让用户去查麦克风），
                //    也不能干等 10 秒超时 —— 超时会把 `detail` 里那句真正的原因（服务端 message）吞掉。
                // 正常收尾不会走到这里：final 消息一定先于 onClosed 送达（WebSocket 按序回调），
                // 而那时 [finished] 已经置位。
                // CONNECTING 只是过程态，不给结论（真正结果来自 ONLINE 后的采集或 OFFLINE/IDLE）
                LinkState.CONNECTING -> Unit
                // 开录之后同样要立刻出结论：批量后端的「上传失败」正是在这一步回来的
                // （Deepgram 401 / 腾讯 AuthFailure 都走 OFFLINE + detail）。挂到超时就只剩「等待识别结果超时」，
                // 把服务端真正的原因吞掉 —— 而这正是用户要的「是不是真的有效果」。
                LinkState.OFFLINE, LinkState.IDLE -> finish(
                    Result("", VoiceTestVerdict.of(null, VoiceTestVerdict.linkLost(detail), prefs.voiceInputEnabled)),
                )
            }
        }
    }

    /** 识别结果回调（OkHttp IO 线程）；[RecognitionMessage.text] 是整段累积文本 */
    private fun onMessage(message: RecognitionMessage) {
        if (!message.isFinal) return
        handler.post {
            if (finished) return@post
            finish(Result(message.text, VoiceTestVerdict.of(message.text, null, prefs.voiceInputEnabled)))
        }
    }

    private fun beginCapture() {
        if (finished || captureStarted) return
        val c = client ?: return
        if (c.beginTask() == null) {
            finish(Result("", VoiceTestVerdict.of(null, TEXT_NOT_READY, prefs.voiceInputEnabled)))
            return
        }
        captureStarted = true
        val r = MicRecorder(
            onChunk = { data -> c.sendChunk(data) },
            onLevel = { _ -> Unit },
            onError = { message ->
                micError = message
                handler.post {
                    if (!finished) {
                        finish(Result("", VoiceTestVerdict.of(null, message, prefs.voiceInputEnabled)))
                    }
                }
            },
        )
        recorder = r
        if (!r.start()) {
            // start() 失败时已同步调过 onError（原因更具体），这里优先用它；
            // 只兜住「一个原因都没给」的情况，别再拿泛泛文案盖掉真因
            finish(Result("", VoiceTestVerdict.of(null, micError ?: TEXT_MIC_FAILED, prefs.voiceInputEnabled)))
            return
        }
        handler.postDelayed(stopRunnable, CAPTURE_MS)
    }

    /** 3 秒到：停录 → 发末帧 → 等 final */
    private fun stopAndAwaitFinal() {
        if (finished) return
        recorder?.stop()
        recorder = null
        val sent = client?.endTask() ?: false
        if (!sent) {
            // 批量后端的 endTask 在「一字节音频都没收到」时也返回 false（连接正常）——
            // 那条要说「没听到」，不是「收尾包未送出」（两者指向完全不同的排查方向）
            val reason = if (client?.state == LinkState.ONLINE) {
                VoiceTestVerdict.TEXT_NO_SPEECH
            } else {
                TEXT_END_FAILED
            }
            finish(Result("", VoiceTestVerdict.of(null, reason, prefs.voiceInputEnabled)))
            return
        }
        handler.postDelayed(timeoutRunnable, FINAL_TIMEOUT_MS)
    }

    private fun finish(result: Result) {
        if (finished) return
        finished = true
        cleanup()
        Diagnostics.i(TAG, "语音测试结束 verdictLen=${result.verdict.length} textLen=${result.text.length}")
        onResult(result)
    }

    /** 收尾三件事：撤回调 → 停录（释放麦克风）→ 断连。任一步失败都不影响其余两步。 */
    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        recorder?.stop()
        recorder = null
        client?.close()
        client = null
    }

    private companion object {
        const val TAG = "VoiceSelfTest"

        /** 采集时长（用户要求的 3 秒） */
        const val CAPTURE_MS = 3_000L

        /** 末帧之后等最终结果的超时 */
        const val FINAL_TIMEOUT_MS = 10_000L

        /** 整体兜底（建连 5s + 采集 3s + 等 final 10s ≈ 18s，留余量） */
        const val TOTAL_TIMEOUT_MS = 30_000L

        const val TEXT_TIMEOUT = "等待识别结果超时"
        const val TEXT_OVERALL_TIMEOUT = "测试超时（未拿到任何识别状态）"
        const val TEXT_NOT_READY = "连接未就绪"
        const val TEXT_MIC_FAILED = "麦克风启动失败（可能被其它录音占用）"
        const val TEXT_END_FAILED = "收尾包未送出"
    }
}

/**
 * 结论文案（纯函数，JVM 单测直接跑）。
 *
 * 三态的判据只看「有没有文本 / 有没有失败原因」，不猜服务端语义：
 * 空文本 + 无异常 = 链路通、只是没听到声音，这与「失败」必须分开说 ——
 * 前者说明配置是对的（用户只需说话），后者才要去查地址与凭据。
 */
internal object VoiceTestVerdict {

    const val TEXT_SUCCESS = "成功：识别到 "
    const val TEXT_SUCCESS_TAIL = " 字"
    const val TEXT_NO_SPEECH = "已连通，但没听到语音"
    const val TEXT_FAIL_HEAD = "失败："

    /** 总开关关闭时的注解（测试照跑，但要说明结论不代表 IME 现在会识别） */
    const val TEXT_VOICE_OFF_NOTE = "（当前总开关关闭）"

    /** 链路在测试期间结束、且客户端没给出原因时的兜底（对方直接关连接 / 回调丢失） */
    const val TEXT_LINK_LOST = "连接已断开，未收到识别结果"

    /**
     * 链路提前结束的失败原因：**优先用客户端给的原话**。
     *
     * 这句原话正是「code≠0」那条路唯一的载体 —— 讯飞把 `code` / `message`（appid 错、鉴权失败）
     * 通过状态回调交出来，服务端 `message` 比我们自己编的文案更能指路；丢掉它只报「超时」，
     * 用户会去查网络与麦克风，而真正的问题在凭据。
     */
    fun linkLost(detail: String?): String =
        detail?.trim()?.takeIf { it.isNotEmpty() } ?: TEXT_LINK_LOST

    fun of(text: String?, failure: String?, voiceEnabled: Boolean): String {
        val base = when {
            failure != null -> TEXT_FAIL_HEAD + failure
            text.isNullOrBlank() -> TEXT_NO_SPEECH
            else -> TEXT_SUCCESS + text.length + TEXT_SUCCESS_TAIL
        }
        return if (voiceEnabled) base else base + TEXT_VOICE_OFF_NOTE
    }
}

/**
 * 发起请求前的可用性校验（纯函数）：缺什么就直说，**不浪费一次必然失败的请求**。
 *
 * 失败原因都不含凭据原文（只有字段名）。
 */
internal object VoiceTestPreflight {

    const val TEXT_MISSING_XF = "讯飞凭据不全，缺少 "
    const val TEXT_MISSING_OAI = "OpenAI 兼容配置不全，缺少 "
    const val TEXT_MISSING_ALI = "阿里云百炼配置不全，缺少 "
    const val TEXT_MISSING_TENCENT = "腾讯云凭据不全，缺少 "
    const val TEXT_MISSING_AZURE = "Azure 配置不全，缺少 "
    const val TEXT_MISSING_BAIDU = "百度凭据不全，缺少 "
    const val TEXT_MISSING_DEEPGRAM = "Deepgram 配置不全，缺少 "
    const val TEXT_BAD_HOST = "自建服务端地址非法"
    const val TEXT_SEP = " / "

    fun check(
        backend: String,
        appId: String,
        apiKey: String,
        apiSecret: String,
        host: String,
        oaiBaseUrl: String = "",
        oaiApiKey: String = "",
        oaiModel: String = "",
        aliWsUrl: String = "",
        aliApiKey: String = "",
        aliModel: String = "",
        tencentSecretId: String = "",
        tencentSecretKey: String = "",
        azureBaseUrl: String = "",
        azureApiKey: String = "",
        baiduApiKey: String = "",
        baiduSecretKey: String = "",
        deepgramApiKey: String = "",
    ): String? {
        if (backend == VoiceBackend.OAI) return checkOai(oaiBaseUrl, oaiApiKey, oaiModel)
        if (backend == VoiceBackend.ALI) return checkAli(aliWsUrl, aliApiKey, aliModel)
        if (backend == VoiceBackend.TENCENT) return checkTencent(tencentSecretId, tencentSecretKey)
        if (backend == VoiceBackend.AZURE) return checkAzure(azureBaseUrl, azureApiKey)
        if (backend == VoiceBackend.BAIDU) return checkBaidu(baiduApiKey, baiduSecretKey)
        if (backend == VoiceBackend.DEEPGRAM) return checkDeepgram(deepgramApiKey)
        if (backend != VoiceBackend.XF) {
            return if (Prefs.isValidHost(host)) null else TEXT_BAD_HOST
        }
        val missing = ArrayList<String>(3)
        if (appId.isBlank()) missing += "APPID"
        if (apiKey.isBlank()) missing += "APIKey"
        if (apiSecret.isBlank()) missing += "APISecret"
        return if (missing.isEmpty()) null else TEXT_MISSING_XF + missing.joinToString(TEXT_SEP)
    }

    /**
     * OpenAI 兼容：先点名缺哪项，再交给地址策略说清为什么不能用
     * （`http://` 公网那句提示是唯一能告诉用户「该改成 https」的载体）。
     */
    private fun checkOai(baseUrl: String, key: String, model: String): String? {
        val missing = ArrayList<String>(3)
        if (baseUrl.isBlank()) missing += "Base URL"
        if (key.isBlank()) missing += "API Key"
        if (model.isBlank()) missing += "模型名"
        if (missing.isNotEmpty()) return TEXT_MISSING_OAI + missing.joinToString(TEXT_SEP)
        return OpenAiEndpoint.normalize(baseUrl).exceptionOrNull()?.message
    }

    /** 阿里云百炼：同一顺序（缺件 → 地址策略），拒绝原因直接用 [AliEndpoint] 那句 */
    private fun checkAli(wsUrl: String, key: String, model: String): String? {
        val missing = ArrayList<String>(3)
        if (wsUrl.isBlank()) missing += "WS 地址"
        if (key.isBlank()) missing += "API Key"
        if (model.isBlank()) missing += "模型名"
        if (missing.isNotEmpty()) return TEXT_MISSING_ALI + missing.joinToString(TEXT_SEP)
        return AliEndpoint.normalize(wsUrl).exceptionOrNull()?.message
    }

    /** 腾讯云：两件凭据（引擎型号有默认值，不必前置拦） */
    private fun checkTencent(secretId: String, secretKey: String): String? {
        val missing = ArrayList<String>(2)
        if (secretId.isBlank()) missing += "SecretId"
        if (secretKey.isBlank()) missing += "SecretKey"
        return if (missing.isEmpty()) null else TEXT_MISSING_TENCENT + missing.joinToString(TEXT_SEP)
    }

    /** Azure：先点名缺件，再交给地址策略说清为什么不能用（复用 [AzureAsrEndpoint] 那句） */
    private fun checkAzure(baseUrl: String, key: String): String? {
        val missing = ArrayList<String>(2)
        if (baseUrl.isBlank()) missing += "Base URL"
        if (key.isBlank()) missing += "API Key"
        if (missing.isNotEmpty()) return TEXT_MISSING_AZURE + missing.joinToString(TEXT_SEP)
        return AzureAsrEndpoint.normalize(baseUrl).exceptionOrNull()?.message
    }

    /** 百度：两件凭据（dev_pid 有默认值，不必前置拦；填错由服务端 3300 说清） */
    private fun checkBaidu(apiKey: String, secretKey: String): String? {
        val missing = ArrayList<String>(2)
        if (apiKey.isBlank()) missing += "API Key"
        if (secretKey.isBlank()) missing += "Secret Key"
        return if (missing.isEmpty()) null else TEXT_MISSING_BAIDU + missing.joinToString(TEXT_SEP)
    }

    /** Deepgram：只有 Key 是必需（模型 / 语言码有默认值，保存页才拦空值） */
    private fun checkDeepgram(apiKey: String): String? =
        if (apiKey.isBlank()) TEXT_MISSING_DEEPGRAM + "API Key" else null
}
