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

/** 连接状态，用来驱动键盘顶部的状态条 */
enum class LinkState { IDLE, CONNECTING, ONLINE, OFFLINE }

/**
 * Jinn 服务端的 WebSocket 客户端。
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
) {

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var state: LinkState = LinkState.IDLE
        private set

    /** 正在发送音频的任务 */
    @Volatile
    private var sendingTask: String? = null

    /** 允许把结果落到输入框的任务；取消后置空，结果就被丢弃 */
    @Volatile
    private var acceptingTask: String? = null

    @Volatile
    private var timeStart = 0.0

    @Volatile
    private var connectedUrl = ""

    /** 断线自动重连：指数退避在主线程调度，主动 close() 取消 */
    private val reconnectHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var reconnectAttempt = 0
    @Volatile
    private var userClosed = false
    private val reconnectRunnable = Runnable { tryReconnect() }

    val isOnline: Boolean get() = state == LinkState.ONLINE

    /**
     * 建立连接。地址变化或 [force] 时重连，其余情况幂等。
     */
    fun connect(force: Boolean = false) {
        userClosed = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        val target = prefs.wsUrl
        if (!force && socket != null && target == connectedUrl &&
            (state == LinkState.ONLINE || state == LinkState.CONNECTING)
        ) {
            return
        }

        closeSocket()
        connectedUrl = target
        // 先建连再置状态：避免 newWebSocket 同步触发 onFailure 时
        // listener 因 socket 仍为 null 被吞，导致状态卡在 CONNECTING 且永不重连
        val request = Request.Builder()
            .url(target)
            // 服务端 websockets.serve(subprotocols=["binary"])，与桌面端保持一致
            .addHeader("Sec-WebSocket-Protocol", "binary")
            .build()
        socket = http.newWebSocket(request, listener)
        updateState(LinkState.CONNECTING, null)
        Diagnostics.i(TAG, "connect: $target (force=$force)")
        Log.i(TAG, "connect: $target")
    }

    /**
     * 开始一次听写。
     *
     * @return 任务 ID；连接未就绪时返回 null，并顺手触发一次重连
     */
    fun beginTask(): String? {
        val target = prefs.wsUrl
        // 用户在设置页改了地址后保存，旧连接仍指向旧地址：检测到变化后强制重连，
        // 避免录音被发到旧服务器又收不到结果
        if (target != connectedUrl) {
            Log.i(TAG, "beginTask: 检测到地址变更 $connectedUrl → $target，强制重连")
            connect(force = true)
            return null
        }
        if (socket == null || state != LinkState.ONLINE) {
            Log.w(TAG, "beginTask: 连接未就绪, state=$state")
            connect()
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

    fun sendChunk(pcmFloat32LittleEndian: ByteArray) {
        val taskId = sendingTask ?: return
        val data = Base64.encodeToString(pcmFloat32LittleEndian, Base64.NO_WRAP)
        send(taskId, data, isFinal = false)
    }

    /** 正常收尾：服务端据此输出最终文本 */
    fun endTask() {
        val taskId = sendingTask ?: return
        sendingTask = null
        send(taskId, "", isFinal = true)
        Diagnostics.i(TAG, "endTask: $taskId")
        Log.i(TAG, "endTask: $taskId")
    }

    /** 取消：照样收尾以清空服务端缓冲，但结果不再采用 */
    fun cancelTask() {
        val taskId = sendingTask ?: return
        sendingTask = null
        acceptingTask = null
        send(taskId, "", isFinal = true)
        Diagnostics.i(TAG, "cancelTask: $taskId")
        Log.i(TAG, "cancelTask: $taskId")
    }

    fun close() {
        userClosed = true
        reconnectHandler.removeCallbacks(reconnectRunnable)
        sendingTask = null
        acceptingTask = null
        closeSocket()
        updateState(LinkState.IDLE, null)
        Diagnostics.i(TAG, "close: 主动关闭连接")
    }

    private fun tryReconnect() {
        if (userClosed) return
        if (state == LinkState.ONLINE || state == LinkState.CONNECTING) return
        Diagnostics.i(TAG, "tryReconnect: attempt=$reconnectAttempt")
        Log.i(TAG, "tryReconnect: attempt=$reconnectAttempt")
        connect(force = true)
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

    private fun send(taskId: String, data: String, isFinal: Boolean) {
        val current = socket
        if (current == null) {
            Diagnostics.w(TAG, "send: 连接已断开，丢弃报文 taskId=$taskId isFinal=$isFinal")
            Log.w(TAG, "send: 连接已断开，丢弃报文")
            return
        }
        val message = AudioMessage(
            taskId = taskId,
            data = data,
            isFinal = isFinal,
            timeStart = timeStart,
            prompt = prefs.prompt,
            language = prefs.language,
        ).toJson()
        if (!current.send(message)) {
            Diagnostics.w(TAG, "send: 发送队列已满或连接关闭 taskId=$taskId size=${data.length}")
            Log.w(TAG, "send: 发送队列已满或连接关闭")
        } else {
            // 音频包很频繁，只记录 isFinal 收尾包避免刷屏；音频用 V 级别
            if (isFinal) {
                Diagnostics.i(TAG, "send: 收尾包已发送 taskId=$taskId")
            } else {
                Diagnostics.v(TAG, "send: 音频包 taskId=$taskId size=${data.length}B")
            }
        }
    }

    private fun closeSocket() {
        val current = socket
        socket = null
        // 先置 null：旧 listener 的 onClosed/onFailure 会因 webSocket !== socket(null) 提前 return，
        // 不会重复 scheduleReconnect；新连接的 listener 才是当前 socket，其失败会正常重连
        runCatching { current?.close(NORMAL_CLOSURE, null) }
            .onFailure { Log.w(TAG, "closeSocket: ${it.message}") }
    }

    private fun updateState(next: LinkState, detail: String?) {
        state = next
        onState(next, detail)
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
            Diagnostics.e(TAG, "onFailure: ${t.javaClass.simpleName} ${t.message} response=${response?.code}", t)
            Log.w(TAG, "onFailure: ${t.javaClass.simpleName} ${t.message}")
            socket = null
            sendingTask = null
            acceptingTask = null
            updateState(LinkState.OFFLINE, describe(t))
            scheduleReconnect()
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

    private fun describe(t: Throwable): String = when (t) {
        is ConnectException -> "连不上服务端"
        is SocketTimeoutException -> "连接超时"
        is UnknownHostException -> "地址无法解析"
        else -> t.message?.take(40) ?: "连接失败"
    }

    private companion object {
        const val TAG = "AsrClient"

        /** 建连超时（秒） */
        const val CONNECT_TIMEOUT_SEC = 5L
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
