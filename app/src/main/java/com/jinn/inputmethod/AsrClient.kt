package com.jinn.inputmethod

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Jinn 服务端的 WebSocket 客户端（[SpeechClient] 的自建后端实现）。
 *
 * - 一次听写：[beginTask] -> 若干次 [sendChunk] -> [endTask]
 * - 上滑取消走 [cancelTask]，同样发 is_final 收尾包：服务端 ws_recv.py 的
 *   音频缓冲按连接持有而非按任务，不收尾会让残留音频串到下一段听写
 * - 识别结果靠 taskId 比对，取消或过期的任务结果直接丢弃
 * - 回调在 OkHttp 的 IO 线程触发，接收方需自行切回主线程
 *
 * cancelTask 也要发收尾包，否则残留音频会污染下一次听写。
 */
class AsrClient(
    private val prefs: Prefs,
    private val onState: (LinkState, String?) -> Unit,
    private val onResult: (RecognitionMessage) -> Unit,
) : SpeechClient {

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    override var state: LinkState = LinkState.IDLE
        private set

    /** 正在发送音频的任务 */
    @Volatile
    private var sendingTask: String? = null

    /** 允许把结果落到输入框的任务；取消后置空，结果就被丢弃 */
    @Volatile
    private var acceptingTask: String? = null

    @Volatile
    private var timeStart = 0.0

    /**
     * 发送字段快照（MEM-42）：`prompt` / `language` 在**连接建立时**取一次，之后每包复用。
     *
     * 原先每包现读 `prefs.prompt` / `prefs.language`（两次 SharedPreferences 内存读），
     * 而音频包每秒可达 10 条。更要紧的是**一致性**：用户在录音途中改配置，
     * 若逐包现读，同一段听写会带着新旧两套 prompt 发出，服务端按最后一包的值处理整段 —— 结果错配却无迹可查。
     *
     * 刷新点放在 [connect] 与 [beginTask]（不是每包）：前者覆盖地址变更重连、`refreshConfig` 强重连与
     * 配置导入三条改配置路径；后者兜住「已连接状态下改了 prompt」这一次听写。
     * 采集线程读、主线程写，故 `@Volatile`。
     */
    @Volatile
    private var snapPrompt: String = prefs.prompt

    /** 见 [snapPrompt] */
    @Volatile
    private var snapLanguage: String = prefs.language

    @Volatile
    private var connectedUrl = ""

    /** 断线自动重连：指数退避在主线程调度，主动 close() 取消 */
    private val reconnectHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var reconnectAttempt = 0
    @Volatile
    private var userClosed = false
    private val reconnectRunnable = Runnable { tryReconnect() }

    /** 空闲关闭是否已排程（仅用于日志与幂等判断，真正的仲裁是 runnable 自身） */
    @Volatile
    private var idleCloseScheduled = false

    /**
     * 空闲关闭因「在途任务未清」而后延的次数（上限见 [onIdleCloseDue]）。
     *
     * ⚠ 只有 [scheduleIdleClose]（外部重新排程 = 有新的使用意图）才清零：[onIdleCloseDue] 的
     * 后延必须走 [armIdleClose]。两处混用会让计数器被自己的后延清零 ⇒ 上限永远是 1、
     * 「超过上限强制关闭」变成死代码，连接于是被无限后延（= 永生，正是这条兜底要消掉的）。
     */
    private var idleCloseDeferrals = 0

    private val idleCloseRunnable = Runnable { onIdleCloseDue() }

    val isOnline: Boolean get() = state == LinkState.ONLINE

    /**
     * 建立连接。地址变化或 [force] 时重连，其余情况幂等。
     *
     * [userInitiated] 决定要不要撤销已排程的**空闲关闭**：只有「用户又要用了」才撤销它。
     * 内部自动重连（[tryReconnect] / 网络恢复回调）撤销会带来一串后果 —— 那次排程永久失效，
     * 而重连成功后没有任何地方会补排（排程点全在用户操作路径上），连接于是被复活并一直保活：
     * 20s 一次的 ping 在息屏后继续唤醒，正是空闲关闭要消掉的那份耗电（MEM-13）。
     * 自动重连不撤也不重排：原来的排程还在，到点照样关。
     */
    override fun connect(force: Boolean, userInitiated: Boolean) {
        userClosed = false
        if (userInitiated) cancelIdleClose()
        refreshSendSnapshot()
        reconnectHandler.removeCallbacks(reconnectRunnable)
        val target = prefs.wsUrl
        if (!force && socket != null && target == connectedUrl &&
            (state == LinkState.ONLINE || state == LinkState.CONNECTING)
        ) {
            return
        }

        closeSocket()
        connectedUrl = target
        // 顺序是「先置状态、再建连」（与 [XfIatClient] / [AliAsrClient] 一致）。
        // `newWebSocket` 只是把请求**入队**，握手在 OkHttp 的调度线程上异步完成，回调不可能
        // 同步返回；反过来（先建连再置状态）会留一个真实竞态：`onOpen` 在主线程被抢占的那几
        // 毫秒里抢先置了 ONLINE，随后那行把它压回 CONNECTING —— 而本函数开头的早退判据恰好是
        // 「CONNECTING 且 socket 非空」⇒ 之后再也回不到 ONLINE，长连接永久停在「连接中」，
        // 语音一直提示「正在连接」直到进程重启。
        updateState(LinkState.CONNECTING, null)
        val request = Request.Builder()
            .url(target)
            // 服务端 websockets.serve(subprotocols=["binary"])，与桌面端保持一致
            .addHeader("Sec-WebSocket-Protocol", "binary")
            .build()
        socket = http.newWebSocket(request, listener)
        Diagnostics.i(TAG, "connect: $target (force=$force)")
        Log.i(TAG, "connect: $target")
    }

    /**
     * 开始一次听写。
     *
     * @return 任务 ID；连接未就绪时返回 null，并顺手触发一次重连
     */
    override fun beginTask(): String? {
        // 本次听写的字段快照：录音期间改配置不影响这一段（见 snapPrompt 注释）
        refreshSendSnapshot()
        cancelIdleClose()
        val target = prefs.wsUrl
        // 用户在设置页改了地址后保存，旧连接仍指向旧地址：检测到变化后强制重连，
        // 避免录音被发到旧服务器又收不到结果
        if (target != connectedUrl) {
            Log.i(TAG, "beginTask: 检测到地址变更 $connectedUrl → $target，强制重连")
            connect(force = true, userInitiated = true)
            return null
        }
        if (socket == null || state != LinkState.ONLINE) {
            Log.w(TAG, "beginTask: 连接未就绪, state=$state")
            connect(force = false, userInitiated = true)
            return null
        }
        val taskId = UUID.randomUUID().toString()
        sendingTask = taskId
        acceptingTask = taskId
        timeStart = System.currentTimeMillis() / 1000.0
        Diagnostics.i(TAG, "beginTask: $taskId url=$target")
        Log.i(TAG, "beginTask: $taskId")
        return taskId
    }

    override fun sendChunk(pcmFloat32LittleEndian: ByteArray) {
        val taskId = sendingTask ?: return
        val data = Base64.encodeToString(pcmFloat32LittleEndian, Base64.NO_WRAP)
        send(taskId, data, isFinal = false)
    }

    /**
     * 正常收尾：服务端据此输出最终文本。
     *
     * @return 收尾包是否真的送出。false（断线 / 无在途任务 / 队列满）时服务端不会给最终结果，
     *         调用方不应再挂等待态，否则状态条要等 60s 兜底才复位
     */
    override fun endTask(): Boolean {
        val taskId = sendingTask ?: return false
        sendingTask = null
        val ok = send(taskId, "", isFinal = true)
        Diagnostics.i(TAG, "endTask: $taskId ok=$ok")
        Log.i(TAG, "endTask: $taskId")
        return ok
    }

    /**
     * 取消：照样收尾以清空服务端缓冲，但结果不再采用。
     *
     * 判据必须连 `acceptingTask` 一起看（与 [BufferedBatchAsrClient.cancelTask] 同一口径）：
     * [endTask] 已经把 `sendingTask` 交出去、只剩等最终结果时，若这里拿 `sendingTask == null`
     * 就退回 false，那道采纳闸门根本不会关 —— 松手之后再取消，晚到的 final 照常上屏。
     *
     * @return 收尾包是否真的送出；已经发过收尾包时返回 true（闸门已关，这次取消照样生效）
     */
    override fun cancelTask(): Boolean {
        val taskId = sendingTask
        if (taskId == null && acceptingTask == null) return false
        sendingTask = null
        // 先关采纳闸门再发收尾包：回包与取消之间存在竞态，闸门是唯一可靠的判据
        acceptingTask = null
        if (taskId == null) {
            Diagnostics.i(TAG, "cancelTask: 收尾包已发过，只丢弃在途结果")
            Log.i(TAG, "cancelTask: 收尾包已发过，只丢弃在途结果")
            return true
        }
        val ok = send(taskId, "", isFinal = true)
        Diagnostics.i(TAG, "cancelTask: $taskId ok=$ok")
        Log.i(TAG, "cancelTask: $taskId")
        return ok
    }

    override fun close() {
        userClosed = true
        cancelIdleClose()
        reconnectHandler.removeCallbacks(reconnectRunnable)
        sendingTask = null
        acceptingTask = null
        closeSocket()
        updateState(LinkState.IDLE, null)
        Diagnostics.i(TAG, "close: 主动关闭连接")
    }

    /**
     * 排一次「空闲关闭」：`delayMs` 后若仍无活动任务就主动 [close]，把待机唤醒降到零（MEM-13）。
     *
     * 为什么需要它：连接一旦建立就一直挂着，OkHttp 按 [PING_INTERVAL_SEC] 周期发 ping，
     * 服务端（`websockets.serve` 未设 ping 参数）也按默认 20s 回 ping —— 键盘收起、息屏静置时
     * 每 20 秒被唤醒一次，是输入法唯一的持续耗电源。只放宽客户端 ping 治不了（服务端那侧照旧），
     * 唯有把连接**关掉**才能归零。
     *
     * 由调用方在「键盘收起 / 切回拼音」时排；[connect] 与 [beginTask] 会自动撤销（用户又要用了）。
     * 关闭窗口必须 ≥ 识别兜底超时（60s），且在途任务未清时不关（见 [onIdleCloseDue]）——
     * 这两条保证「松手后 1~3 秒才回的 final 结果」不会被关连接丢掉。
     */
    override fun scheduleIdleClose(delayMs: Long) {
        idleCloseScheduled = true
        // 外部重新排程 = 「又有新的使用意图」：后延计数从零算（后延自身走 [armIdleClose]）
        idleCloseDeferrals = 0
        armIdleClose(delayMs)
        Diagnostics.i(TAG, "scheduleIdleClose: ${delayMs}ms 无活动则关闭连接")
    }

    /**
     * 只排程、**不动后延计数**：[onIdleCloseDue] 的后延专用。
     *
     * 与 [scheduleIdleClose] 分开是必需的 —— 合在一起时后延会把自己的计数清零，
     * [IDLE_CLOSE_MAX_DEFERRALS] 这道上限永远走不到（见 [idleCloseDeferrals] 的说明）。
     */
    private fun armIdleClose(delayMs: Long) {
        reconnectHandler.removeCallbacks(idleCloseRunnable)
        reconnectHandler.postDelayed(idleCloseRunnable, delayMs)
    }

    /** 撤销待触发的空闲关闭（正常使用路径一到就调；removeCallbacks 幂等且廉价） */
    override fun cancelIdleClose() {
        idleCloseScheduled = false
        reconnectHandler.removeCallbacks(idleCloseRunnable)
    }

    private fun onIdleCloseDue() {
        idleCloseScheduled = false
        if (userClosed) return
        // 在途任务还没清：此刻关连接会把 final 结果丢掉。先延后重排，但**只延后有限次** ——
        // `acceptingTask` 的清除权在调用方（它有 60s 兜底），若那条兜底没走到
        // （例如结果被丢弃且没人复位），无限重排等于连接永生、待机唤醒照旧。
        if (sendingTask != null || acceptingTask != null) {
            if (idleCloseDeferrals >= IDLE_CLOSE_MAX_DEFERRALS) {
                Diagnostics.w(
                    TAG,
                    "空闲关闭：在途标记残留（sending=$sendingTask accepting=$acceptingTask），强制关闭",
                )
            } else {
                idleCloseDeferrals++
                Diagnostics.i(TAG, "空闲关闭跳过：仍有在途任务，稍后再试（第 $idleCloseDeferrals 次）")
                // 后延**不许**走 scheduleIdleClose：那会把刚累加的计数清零，上限永远到不了
                armIdleClose(IDLE_CLOSE_MS)
                return
            }
        }
        // 连接已经不在（[onFailure] / [onClosed] 走过）：[close] 此时唯一还会发生的事，就是把它
        // 刚写到状态条上的失败原因换成「未连接」—— 排障线索自己消失（与讯飞 / 阿里在服务端拒绝
        // 分支上撤空闲关闭同一条口径）。要做的只剩两件：停自动重连、置 [userClosed]。
        if (socket == null) {
            userClosed = true
            reconnectHandler.removeCallbacks(reconnectRunnable)
            Diagnostics.i(TAG, "空闲关闭：连接已不在，只停自动重连（保留状态条上的失败原因）")
            return
        }
        close()
        Diagnostics.i(TAG, "空闲关闭：连接已释放，待机不再有 ping 唤醒")
    }

    /** 见 [snapPrompt] */
    private fun refreshSendSnapshot() {
        snapPrompt = prefs.prompt
        snapLanguage = prefs.language
    }

    private fun tryReconnect() {
        if (userClosed) return
        if (state == LinkState.ONLINE || state == LinkState.CONNECTING) return
        Diagnostics.i(TAG, "tryReconnect: attempt=$reconnectAttempt")
        Log.i(TAG, "tryReconnect: attempt=$reconnectAttempt")
        // 自动重连不是用户发起：不许撤销空闲关闭（见 [connect] 的说明）
        connect(force = true, userInitiated = false)
    }

    private fun scheduleReconnect() {
        if (userClosed) return
        reconnectHandler.removeCallbacks(reconnectRunnable)
        val attempt = reconnectAttempt.coerceAtMost(RECONNECT_MAX_EXP)
        val delay = (RECONNECT_BASE_MS shl attempt).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt++
        reconnectHandler.postDelayed(reconnectRunnable, delay)
        Diagnostics.i(TAG, "scheduleReconnect: 第 $reconnectAttempt 次重连 ${delay}ms 后")
        Log.i(TAG, "scheduleReconnect: 第 $reconnectAttempt 次重连 ${delay}ms 后")
    }

    /**
     * 发送一条报文。
     *
     * @return 是否真的送出 —— 断线或发送队列满都会返回 false；收尾包（isFinal）拿到 false 意味着
     *         本次识别拿不到最终结果，调用方据此决定是否还等回调
     */
    private fun send(taskId: String, data: String, isFinal: Boolean): Boolean {
        val current = socket
        if (current == null) {
            Diagnostics.w(TAG, "send: 连接已断开，丢弃报文 taskId=$taskId isFinal=$isFinal")
            Log.w(TAG, "send: 连接已断开，丢弃报文")
            return false
        }
        val message = AudioMessage(
            taskId = taskId,
            data = data,
            isFinal = isFinal,
            timeStart = timeStart,
            prompt = snapPrompt,
            language = snapLanguage,
        ).toJson()
        if (!current.send(message)) {
            Diagnostics.w(TAG, "send: 发送队列已满或连接关闭 taskId=$taskId size=${data.length}")
            Log.w(TAG, "send: 发送队列已满或连接关闭")
            return false
        }
        // 音频包很频繁，只记录 isFinal 收尾包避免刷屏；音频用 V 级别
        if (isFinal) {
            Diagnostics.i(TAG, "send: 收尾包已发送 taskId=$taskId")
        } else {
            Diagnostics.v(TAG, "send: 音频包 taskId=$taskId size=${data.length}B")
        }
        return true
    }

    private fun closeSocket() {
        val current = socket
        socket = null
        // 先置 null：旧 listener 的 onClosed/onFailure 会因 webSocket !== socket(null) 提前 return，
        // 不会重复 scheduleReconnect；新连接的 listener 才是当前 socket，其失败会正常重连
        runCatching { current?.close(NORMAL_CLOSURE, null) }
            .onFailure { Log.w(TAG, "closeSocket: ${it.message}") }
    }

    /** 状态条文案的唯一出口：异常正文 / 握手英文原话都过一遍凭据刮除（见 [SpeechErrorText]） */
    private fun updateState(next: LinkState, detail: String?) {
        state = next
        onState(next, SpeechErrorText.scrub(detail))
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            Diagnostics.i(TAG, "onOpen: $connectedUrl")
            Log.i(TAG, "onOpen: $connectedUrl")
            reconnectAttempt = 0
            reconnectHandler.removeCallbacks(reconnectRunnable)
            updateState(LinkState.ONLINE, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val message = RecognitionMessage.parse(text)
            if (message == null) {
                // 只记长度不记原文：报文体里可能就是用户语音识别出的正文
                Diagnostics.w(TAG, "onMessage: 报文解析失败 len=${text.length}")
                Log.w(TAG, "onMessage: 报文解析失败")
                return
            }
            // 已取消或过期的任务结果直接丢掉，避免旧文本乱入
            if (message.taskId != acceptingTask) {
                Diagnostics.w(TAG, "onMessage: 丢弃过期任务结果 taskId=${message.taskId} accepting=${acceptingTask}")
                Log.w(TAG, "onMessage: 丢弃过期任务结果 ${message.taskId}")
                return
            }
            if (message.isFinal) acceptingTask = null
            Diagnostics.i(
                TAG,
                "onMessage: final=${message.isFinal} dur=${message.duration}s textLen=${message.text.length}"
            )
            onResult(message)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            // 网络异常照样进日志：`t.message` 先刮再拼 —— `unexpected url: …` / `Expected URL scheme …`
            // 会把用户填的地址（乃至地址里的凭据）原样抄进 message，而日志会随诊断包外发
            Diagnostics.e(
                TAG,
                "onFailure: ${t.javaClass.simpleName} ${SpeechErrorText.scrub(t.message)} " +
                    "response=${response?.code}",
                t,
            )
            Log.w(TAG, "onFailure: ${t.javaClass.simpleName} ${SpeechErrorText.scrub(t.message)}")
            socket = null
            sendingTask = null
            acceptingTask = null
            updateState(LinkState.OFFLINE, describe(t, response?.code))
            // 重连**要分能不能成**（2026-11-03 复审）：401 / 403 / 404 是握手被明确拒绝（凭据错、
            // 反代挡了、ws:// 指到了普通 HTTP 端口），退避到 30s 之后重试一万次也是同一个结果。
            // 而每次重试都会把 state 推回 CONNECTING，`renderLink` 于是把刚写好的失败原因换成
            // 「连接中」，再换回来 —— 语音面板开着（没有空闲关闭兜底）时每 30 秒一次，永不停止：
            // 状态条在「连接失败（HTTP 404）」与「连接中」之间来回闪，无线电也跟着醒。
            if (AsrReconnect.shouldRetry(response?.code)) {
                scheduleReconnect()
            } else {
                Diagnostics.w(TAG, "onFailure: 握手被明确拒绝（HTTP ${response?.code}），不自动重连")
                Log.w(TAG, "onFailure: 握手被明确拒绝，不自动重连")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            Diagnostics.i(TAG, "onClosed: code=$code reason=$reason")
            Log.i(TAG, "onClosed: code=$code reason=$reason")
            socket = null
            sendingTask = null
            acceptingTask = null
            updateState(LinkState.OFFLINE, null)
            scheduleReconnect()
        }
    }

    /**
     * 失败原因：网络异常按类型给中文，其余带上握手状态码。
     *
     * 状态码在这里是唯一能定性的一句话：把 `ws://` 指到普通 HTTP 端口（最常见的一次配错）
     * 时 OkHttp 只会抛 `Expected HTTP 101 response but was '404 Not Found'`，截 40 字后状态条上
     * 只剩半句英文；反代加了鉴权则是 401 / 403。四家的判据与文案保持一致（见 [AliAsrClient.describe]）。
     */
    private fun describe(t: Throwable, code: Int?): String = when {
        t is ConnectException -> "连不上服务端"
        t is SocketTimeoutException -> "连接超时"
        t is UnknownHostException -> "地址无法解析"
        code == 401 || code == 403 -> "鉴权失败（HTTP $code）"
        code != null -> "连接失败（HTTP $code）"
        // 先刮再截：反过来（`scrub(t.message?.take(40))`）会把长正文里的鉴权头切成
        // 「…: Bearer sk-pr」这种半截值 —— `AUTH_PREFIX` 要 ≥6 个字符才算,半截值不被识别，照原样上屏
        else -> SpeechErrorText.scrub(t.message)?.take(40) ?: "连接失败"
    }

    private companion object {
        const val TAG = "AsrClient"

        /** 建连超时（秒） */
        const val CONNECT_TIMEOUT_SEC = 5L

        /**
         * 空闲关闭窗口（毫秒）：键盘收起后这么久没有活动就释放连接。
         *
         * 必须 ≥ `JinnIme.RECOGNIZE_TIMEOUT_MS`（60s）—— 松手后服务端 final 结果要 1~3 秒才回，
         * 60s 是「等结果」的兜底上限；窗口比它短就会在用户还等着结果时把连接关掉。
         * 90s 是留了余量的取值（多出来的 30 秒不影响「待机唤醒归零」这个目标：只在息屏静置时才关）。
         */
        const val IDLE_CLOSE_MS = 90_000L

        /**
         * 空闲关闭最多因「在途任务未清」后延几次（每次一个 [IDLE_CLOSE_MS] 窗口）。
         *
         * 3 次 ≈ 4.5 分钟：调用方对「等结果」有 60s 兜底，正常情况下第一二次就会让开；
         * 超过上限仍不清（标记残留）就强制关闭 —— 否则连接永生、待机唤醒照旧，那等于白改。
         * 强制关闭会丢一个在途结果，故记 W 级。
         */
        const val IDLE_CLOSE_MAX_DEFERRALS = 3
        /** WebSocket 心跳间隔（秒），长连接保鲜防 NAT 掐线 */
        const val PING_INTERVAL_SEC = 20L
        const val NORMAL_CLOSURE = 1000

        /** 所有实例共享一个 OkHttp 客户端，避免每次 new 都新建后台线程池造成泄漏 */
        val http = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)      // 长连接不要读超时
            .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
            .proxy(Proxy.NO_PROXY)                      // 局域网直连，绕开系统代理
            .build()

        /** 断线自动重连：初始 1s，指数退避，上限 30s */
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 30_000L
        const val RECONNECT_MAX_EXP = 8
    }
}

/**
 * 断线之后**该不该自动重连**（纯函数，JVM 单测直接跑）。
 *
 * 判据只剩「这次失败是临时的还是定性的」这一条：`code` 是 WebSocket 握手阶段拿到的 HTTP 状态码
 * （连接被拒 / 超时 / DNS 失败时没有码 ⇒ null）。
 *
 * - **不重试**：400 / 401 / 403 / 404 一类 4xx 中的确定性拒绝 —— 凭据错、反代加了鉴权、
 *   `ws://` 指到了普通 HTTP 端口。重试一万次也是同一个结果，而每次重试都会把 state 推回
 *   `CONNECTING`（`JinnIme.renderLink` 于是把失败原因换成「连接中」再换回来），语音面板开着时
 *   没有空闲关闭兜底 ⇒ 每 30 秒一次、永不停止的闪烁 + 无线电唤醒。
 * - **重试**：无码（连不上 / 超时 / DNS）、408（请求超时）、429（限流）、5xx（服务端 / 网关
 *   临时故障）。这几类过一会儿确实可能自己好。
 *
 * 不重试**不等于**放弃：`JinnIme` 进语音面板 / 长按空格预热 / 网络恢复回调都会再调
 * [AsrClient.connect]，用户改完配置也是走杀进程重建，哪一条都比空转的定时器快。
 */
internal object AsrReconnect {

    /** @param code 握手阶段的 HTTP 状态码；null = 没拿到响应 */
    fun shouldRetry(code: Int?): Boolean = code == null || code == 408 || code == 429 || code >= 500
}
