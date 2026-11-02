package com.jinn.inputmethod

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 讯飞在线听写（语音听写大模型 V2.0）客户端，[SpeechClient] 的第二个实现。
 *
 * 与 [AsrClient] 的差别只在**连接的生命周期**：讯飞一次会话一条连接，服务端不接受跨任务的
 * 音频缓冲，所以收尾（拿到最终结果 / 出错）就断掉旧连接、**随即预连下一条**
 * （见 [releaseAndPreconnect]：下一次按键到真开始录音只有 ~500ms，现等握手必然赶不上；
 * 预连的那条按空闲窗口关闭，没人再说话就归零）。
 *
 * 音频节奏按官方要求 40ms 一帧（[XfIatProtocol.FRAME_BYTES] = 1280 字节）：
 * [sendChunk] 只把 float32 转 PCM16 入队，真正的发送由 40ms 心跳决定 —— 麦克风是 100ms 一批，
 * 按批直接发送会让服务端收到 100ms 的突发帧，实时率判定与中间结果节奏都会乱。
 * 泵按「已到期的帧数」计算，发送偶尔落后时能连发补齐，**不丢音频**。
 *
 * 线程模型：泵与空闲关闭跑在主线程 Handler（每拍只做一次 `WebSocket.send`，不阻塞），
 * 回调（`onState` / `onResult`）在 OkHttp 的 IO 线程触发，接收方自行切主线程。
 *
 * ⚠ 凭据只从 [Prefs] 读，日志里**只记长度与「已配置 / 未配置」**，绝不输出原文。
 */
class XfIatClient(
    private val prefs: Prefs,
    private val onState: (LinkState, String?) -> Unit,
    private val onResult: (RecognitionMessage) -> Unit,
) : SpeechClient {

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    override var state: LinkState = LinkState.IDLE
        private set

    /** 正在收音频的任务 */
    @Volatile
    private var taskId: String? = null

    /** 允许把结果落字的任务；取消后置空，结果回来直接丢弃 */
    @Volatile
    private var acceptingTask: String? = null

    /** 本次任务的文本累积器（每个任务一份，换任务即丢） */
    @Volatile
    private var accumulator: XfTextAccumulator? = null

    /**
     * 当前连接上是否已经开过一次会话。
     *
     * 讯飞**一次会话一条连接**：末帧发出后同一条 WebSocket 不能再开新任务（服务端已按
     * 「这段音频结束」处理过）。上一段识别没等到最终结果（超时 / 结果被丢弃）时连接可能还挂着，
     * 这时 [beginTask] 必须强制重连，而不是复用那条已经用过的连接。
     */
    @Volatile
    private var sessionStarted = false

    @Volatile
    private var taskStartMs = 0L

    // ── 音频泵 ──────────────────────────────────────────────

    private val pumpHandler = Handler(Looper.getMainLooper())

    /** 未发出的 PCM16 余量（按 1280 字节切帧；不足一帧的留到下一拍） */
    private val queue = PcmQueue()

    /** 已送出的音频帧数，用来按实时率算「这一拍该发几帧」 */
    private var framesSent = 0L

    /** 任务开始时刻（泵的基准时间） */
    private var pumpStartMs = 0L

    /** 报文序号：首帧 0，之后每帧 +1（中间帧与末帧共用同一个计数） */
    private var seq = 0

    private val pumpRunnable = Runnable { pumpTick() }

    // ── 空闲关闭 ────────────────────────────────────────────

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { onIdleCloseDue() }

    /**
     * 本次连接使用的 APPID：在 [connect] 解析凭据时缓存。
     *
     * 每一帧都要带 `header.app_id`（服务端校验，缺了就是 10163），而 `beginTask` / 音频泵 / 末帧
     * 三处都要它。不在各调用点现读 [Prefs.xfAppId]：凭据读取可能因锁屏/Keystore 暂时返回空串，
     * 那样会在会话中途发出没有 app_id 的帧、被服务端当场断开。
     */
    @Volatile
    private var sessionAppId: String = ""

    /**
     * 建连。
     *
     * `@Synchronized`：「关掉旧的 → 建新的并赋值给 [socket]」这一段必须原子（与 [AliAsrClient.connect]
     * 同一取舍）。两个线程会同时进来 —— 主线程的 [beginTask]（旧连接用过 / 未就绪时强制重连）与
     * OkHttp IO 线程的 [releaseAndPreconnect]（收到最终结果后预连下一条），而「用户恰好在收尾那一瞬
     * 按键」正是 [sessionStarted] 要挡的场景。不串行化时两次 `newWebSocket` 可以交错到
     * 「两个都先 closeSocket、再各自赋值」：先建那条被后写的 `socket = …` 覆盖，它的回调从此因
     * `webSocket !== socket` 全部返回，**没有任何人再持有它**；更糟的是其中一方刚建好就被另一方的
     * `closeSocket()` 关掉，`socket` 停在 null 而 state 停在 CONNECTING，这一次按键必然落空。
     */
    @Synchronized
    override fun connect(force: Boolean, userInitiated: Boolean) {
        if (userInitiated) cancelIdleClose()
        if (!force && (state == LinkState.ONLINE || state == LinkState.CONNECTING) && socket != null) return

        // 凭据不全会拼出必然 401 的地址，白等一次握手；在这里就落到状态条
        val appId = prefs.xfAppId
        val apiKey = prefs.xfApiKey
        val apiSecret = prefs.xfApiSecret
        if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
            Diagnostics.w(
                TAG,
                "connect: 讯飞凭据不完整 appIdLen=${appId.length} apiKeyLen=${apiKey.length} " +
                    "apiSecretLen=${apiSecret.length}",
            )
            updateState(LinkState.OFFLINE, TEXT_NEED_CREDENTIALS)
            return
        }

        closeSocket()
        updateState(LinkState.CONNECTING, null)
        sessionAppId = appId
        val url = XfIatProtocol.buildAuthUrl(
            appId = appId,
            apiKey = apiKey,
            apiSecret = apiSecret,
            date = XfIatProtocol.rfc1123Gmt(System.currentTimeMillis()),
        )
        // 地址里带 authorization（等同于凭据），日志只记主机与长度
        Diagnostics.i(TAG, "connect: ${XfIatProtocol.HOST}${XfIatProtocol.PATH} urlLen=${url.length}")
        Log.i(TAG, "connect: 讯飞在线")
        val request = runCatching { Request.Builder().url(url).build() }.getOrElse {
            // 不记异常正文：OkHttp 的 "unexpected url: …" 会把带 authorization 的地址写进日志
            Diagnostics.e(TAG, "connect: 地址构造失败 ${it.javaClass.simpleName}")
            updateState(LinkState.OFFLINE, TEXT_BAD_URL)
            return
        }
        socket = http.newWebSocket(request, listener)
    }

    override fun beginTask(): String? {
        cancelIdleClose()
        // 用 connect 时缓存的 APPID（见 [sessionAppId]）：每次现读凭据可能撞上锁屏/Keystore 的
        // 空串窗口，首帧就成了没有 header.app_id 的必败帧
        val appId = sessionAppId
        if (appId.isEmpty()) {
            Diagnostics.w(TAG, "beginTask: 讯飞凭据未配置")
            updateState(LinkState.OFFLINE, TEXT_NEED_CREDENTIALS)
            return null
        }
        val current = socket
        if (current == null || state != LinkState.ONLINE || sessionStarted) {
            Diagnostics.w(
                TAG,
                "beginTask: 连接未就绪或已用过 state=$state used=$sessionStarted（重建连接）",
            )
            connect(force = true, userInitiated = true)
            return null
        }

        val id = UUID.randomUUID().toString()
        taskId = id
        acceptingTask = id
        accumulator = XfTextAccumulator()
        taskStartMs = System.currentTimeMillis()
        pumpStartMs = taskStartMs
        framesSent = 0
        seq = 0
        queue.clear()

        // 首帧：status=0，音频可以留空（后续靠泵送），业务参数全在这一帧里
        val ok = current.send(XfIatProtocol.firstFrame(appId, ByteArray(0), seq = nextSeq()))
        if (!ok) {
            Diagnostics.w(TAG, "beginTask: 首帧发送失败，任务作废")
            clearTask()
            connect(force = true, userInitiated = true)
            return null
        }
        sessionStarted = true
        Diagnostics.i(TAG, "beginTask: $id")
        pumpHandler.removeCallbacks(pumpRunnable)
        pumpHandler.postDelayed(pumpRunnable, FRAME_MS)
        return id
    }

    override fun sendChunk(pcmFloat32LittleEndian: ByteArray) {
        if (taskId == null) return
        // 转换在采集线程上做（纯 CPU、无锁），入队是瞬时的，不会卡住麦克风
        queue.append(XfIatProtocol.float32ToPcm16(pcmFloat32LittleEndian))
    }

    override fun endTask(): Boolean = finishTask(acceptResult = true)

    override fun cancelTask(): Boolean = finishTask(acceptResult = false)

    /**
     * 收尾：把队列余量按序发完 → 发末帧 → 置等待态。
     *
     * 余量不按 40ms 节拍等：用户已经松手，多等只是白延迟；讯飞按收到的总字节数解码，
     * 连发不会丢音频。`audio=""` 的末帧才是「到此为止」的信号。
     *
     * `taskId == null` 有两种含义，**必须分开**：一是从来没开过任务（返回 false）；
     * 二是 [endTask] 已经发过末帧、只剩等最终结果 —— 那时取消仍要能关掉采纳闸门，
     * 否则「松手后再取消」形同虚设，晚到的 final 照常上屏（与 [BufferedBatchAsrClient] 同一口径）。
     */
    private fun finishTask(acceptResult: Boolean): Boolean {
        val id = taskId
        if (id == null) {
            if (!acceptResult && acceptingTask != null) {
                acceptingTask = null
                Diagnostics.i(TAG, "cancelTask: 末帧已发过，只丢弃在途结果")
                return true
            }
            return false
        }
        cancelPump()
        val current = socket
        if (current == null || state != LinkState.ONLINE) {
            Diagnostics.w(TAG, "finishTask: 连接已断开，本次拿不到最终结果")
            clearTask()
            return false
        }
        // 先切「是否采纳结果」再发末帧：末帧一发出服务端就可能抢答，
        // 取消路径若等到发送之后才置空，那一两条回包会漏过闸门被当成正常结果显示。
        acceptingTask = if (acceptResult) id else null
        flushQueue(current)
        val ok = current.send(XfIatProtocol.lastFrame(sessionAppId, seq = nextSeq()))
        taskId = null
        if (!ok) {
            Diagnostics.w(TAG, "finishTask: 末帧发送失败 taskId=$id")
            clearTask()
            return false
        }
        Diagnostics.i(TAG, "finishTask: taskId=$id accept=$acceptResult")
        return true
    }

    override fun close() {
        cancelIdleClose()
        cancelPump()
        closeSocket()
        clearTask()
        updateState(LinkState.IDLE, null)
        Diagnostics.i(TAG, "close: 主动关闭连接")
    }

    /**
     * 一段识别结束：断掉本条连接（讯飞一次会话一条连接，末帧之后同一条不能再开新任务），
     * 并**立刻预连下一条**。
     *
     * 为什么要预连：下一次「长按空格」从 ACTION_DOWN 到真开始录音只有约 500ms（框架长按阈值），
     * 语音面板里单击麦克风更是零等待 —— 现等 TLS + WS 握手必然赶不上，表现是第二段稳定提示
     * 「正在连接语音服务，请再说一次」，连续录音每一段都要按两次。预连把握手挪到用户松手后的
     * 空档，并挂上常规空闲窗口（[scheduleIdleClose]）：没人再说话就归零，不常驻。
     *
     * 预连失败（没网 / 凭据被删）由 [connect] 自己落到状态条，这里不额外兜。
     */
    private fun releaseAndPreconnect() {
        cancelIdleClose()
        cancelPump()
        closeSocket()
        clearTask()
        // 空闲窗口先挂上：下面的早退分支也不能让一条没人用的连接永久挂着
        scheduleIdleClose(SpeechClient.DEFAULT_IDLE_CLOSE_MS)
        // 已经在连（用户恰好此刻按了空格）就不重复握手：`socket = http.newWebSocket(...)`
        // 会把上一条覆盖成孤儿
        if (socket != null || state == LinkState.CONNECTING) return
        connect(force = false, userInitiated = false)
        Diagnostics.i(
            TAG,
            "一段结束：旧连接已断并预连下一条（${SpeechClient.DEFAULT_IDLE_CLOSE_MS}ms 内无人用则关）",
        )
    }

    override fun scheduleIdleClose(delayMs: Long) {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, delayMs)
        Diagnostics.i(TAG, "scheduleIdleClose: ${delayMs}ms 无活动则断开")
    }

    override fun cancelIdleClose() {
        idleHandler.removeCallbacks(idleRunnable)
    }

    private fun onIdleCloseDue() {
        if (taskId != null || acceptingTask != null) {
            // 在途任务：此刻断开就是把结果丢掉。讯飞一次会话一条连接，任务结束后本来就会断，
            // 这里只需跳过（不再重排，避免无活动时反复唤醒 —— 与 AsrClient 的取舍一致）。
            Diagnostics.i(TAG, "空闲关闭跳过：仍有在途任务")
            return
        }
        closeSocket()
        updateState(LinkState.IDLE, null)
        Diagnostics.i(TAG, "空闲关闭：连接已释放")
    }

    // ── 音频泵 ──────────────────────────────────────────────

    /**
     * 一拍（40ms）：按「已到期的帧数」发，落后就一次多发几帧补齐。
     *
     * 上限 [MAX_CATCH_UP_FRAMES]：网络长时间卡顿后攒了一堆音频，一次灌几百帧会把上行打成突发
     * （服务端实时率判定反而更容易掉线），分几拍追上更稳。
     */
    private fun pumpTick() {
        val current = socket
        val id = taskId
        if (current == null || id == null || state != LinkState.ONLINE) return
        val elapsed = System.currentTimeMillis() - pumpStartMs
        val due = elapsed / FRAME_MS - framesSent
        if (due > 0) {
            var sent = 0L
            while (sent < due && sent < MAX_CATCH_UP_FRAMES) {
                val frame = queue.takeFrame() ?: break
                if (!current.send(XfIatProtocol.audioFrame(sessionAppId, frame, seq = nextSeq()))) {
                    Diagnostics.w(TAG, "pumpTick: 发送失败，停止本拍")
                    return
                }
                sent++
                framesSent++
            }
        }
        pumpHandler.postDelayed(pumpRunnable, FRAME_MS)
    }

    private fun cancelPump() {
        pumpHandler.removeCallbacks(pumpRunnable)
    }

    /** 把余量（不足一帧的尾巴也算）一次发完；空队列不发空帧 */
    private fun flushQueue(current: WebSocket) {
        val rest = queue.takeAll()
        if (rest.isEmpty()) return
        if (!current.send(XfIatProtocol.audioFrame(sessionAppId, rest, seq = nextSeq()))) {
            Diagnostics.w(TAG, "flushQueue: 尾部音频发送失败 len=${rest.size}")
        }
    }

    // ── WebSocket 回调 ──────────────────────────────────────

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            Diagnostics.i(TAG, "onOpen: 讯飞在线已连线")
            Log.i(TAG, "onOpen: 讯飞在线已连线")
            sessionStarted = false
            updateState(LinkState.ONLINE, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            // 只记异常类型与网络码，不记 URL（带 authorization）
            Diagnostics.w(TAG, "onFailure: ${t.javaClass.simpleName} response=${response?.code}")
            Log.w(TAG, "onFailure: ${t.javaClass.simpleName}")
            cancelPump()
            socket = null
            clearTask()
            updateState(LinkState.OFFLINE, describe(t, response?.code))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            Diagnostics.i(TAG, "onClosed: code=$code")
            cancelPump()
            socket = null
            clearTask()
            updateState(LinkState.IDLE, null)
        }
    }

    /**
     * 回包分派：`code != 0` 视为失败落到状态条（不静默）；否则按 `ls` / `header.status=2`
     * 判定最终结果，最终结果**送达后立刻断连并预连下一条**（讯飞一次会话一条连接，
     * 见 [releaseAndPreconnect]）。
     */
    private fun handleMessage(raw: String) {
        val response = XfIatProtocol.parseResponse(raw)
        if (response == null) {
            // 只记长度不记原文：报文体里可能就是用户语音识别出的正文
            Diagnostics.w(TAG, "onMessage: 报文解析失败 len=${raw.length}")
            return
        }
        if (response.code != 0) {
            Diagnostics.w(
                TAG,
                "onMessage: 服务端拒绝 code=${response.code} message=${response.message.take(40)}",
            )
            Log.w(TAG, "onMessage: 服务端拒绝 code=${response.code}")
            cancelPump()
            clearTask()
            // code 与 message 都要带上：message 为空时只报「识别失败」，用户与日志都无从下手；
            // 这两样正是「鉴权失败 / appid 错 / 超额」唯一的指路牌（code 是服务端给的定性依据）
            val detail = "code ${response.code} " +
                response.message.take(40).ifBlank { TEXT_RECOGNIZE_FAILED }
            updateState(LinkState.OFFLINE, detail)
            closeSocket()
            return
        }
        val result = response.result ?: return
        val accumulated = accumulator?.accept(response) ?: ""
        if (Diagnostics.KEY_TRACE) {
            Diagnostics.v(
                TAG,
                "onMessage: sid=${response.sid} status=${response.status} ls=${result.ls} " +
                    "pgs=${result.pgs} rg=${result.rg} textLen=${accumulated.length}",
            )
        }
        val isFinal = result.ls || response.status == 2
        val task = acceptingTask
        if (task == null) {
            // 已取消或已收尾的任务：结果丢弃，但连接仍然要收掉（并按需预连下一条）
            if (isFinal) releaseAndPreconnect()
            return
        }
        val duration = (System.currentTimeMillis() - taskStartMs) / 1000.0
        onResult(RecognitionMessage(taskId = task, isFinal = isFinal, duration = duration, text = accumulated))
        if (isFinal) {
            Diagnostics.i(TAG, "onMessage: 最终结果 sid=${response.sid} 共${accumulated.length}字")
            acceptingTask = null
            releaseAndPreconnect()
        }
    }

    /** 状态条文案的唯一出口：异常正文 / 服务端 `message` 都过一遍凭据刮除（见 [SpeechErrorText]） */
    private fun updateState(next: LinkState, detail: String?) {
        state = next
        onState(next, SpeechErrorText.scrub(detail))
    }

    private fun nextSeq(): Int = seq++

    private fun clearTask() {
        taskId = null
        acceptingTask = null
        accumulator = null
        queue.clear()
    }

    /** 拆连接（幂等）。与 [connect] 同一个监视器：关闭不许插进「关旧的—建新的」中间。 */
    @Synchronized
    private fun closeSocket() {
        val current = socket
        socket = null
        // 先置 null：旧 listener 的 onClosed / onFailure 会因 webSocket !== socket 提前返回
        runCatching { current?.close(NORMAL_CLOSURE, null) }
            .onFailure { Log.w(TAG, "closeSocket: ${it.message}") }
    }

    /**
     * 失败原因：网络异常按类型给中文，握手被拒时**状态码优先**。
     *
     * 讯飞把 apiKey / APISecret 签名错、appid 与 key 不配对都报成握手阶段的 401，而 OkHttp 交给我们
     * 的只是一句 `Expected HTTP 101 response but was '401 Unauthorized'` —— 截 40 字后状态条上只剩
     * 半句英文，用户看不出「该改凭据」，日志里也拼不出结论。状态码是这条路径上唯一可靠的定性依据
     * （与 [AliAsrClient.describe] 同一口径）。
     */
    private fun describe(t: Throwable, code: Int?): String = when {
        t is ConnectException -> "连不上讯飞服务"
        t is SocketTimeoutException -> "连接超时"
        t is UnknownHostException -> "地址无法解析"
        code == 401 || code == 403 -> "鉴权失败（HTTP $code）"
        code != null && code >= 500 -> "服务端出错（HTTP $code）"
        // 其余带码的一律带上码：404（厂商改路径 / 中间层挡了）也只留这句英文的话，
        // 状态条上是「Expected HTTP 101 response but was '404 Not…」，用户认不出是地址问题
        code != null -> "连接失败（HTTP $code）"
        // 先刮再截：反过来会把长正文里的鉴权头切成半截值，`AUTH_PREFIX` 的 ≥6 字符门槛就认不出它了
        else -> SpeechErrorText.scrub(t.message)?.take(40) ?: "连接失败"
    }

    /**
     * PCM16 待发缓冲：按帧切、不足一帧留到下一拍。
     *
     * 手写而不是用 `ByteArrayOutputStream`：需要**按帧消费**（取走前 1280 字节、其余留在原地），
     * 流式容器取不出这个语义。写入在采集线程、取帧在主线程，故全方法 `@Synchronized`。
     *
     * `internal` 是为了能在 JVM 单测里直接验「不丢字节 / 不乱序」（本类整体依赖 Handler 与
     * OkHttp，进不了单测；这一段是纯逻辑，是唯一能真跑起来的部分）。
     */
    internal class PcmQueue {
        private var buffer = ByteArray(XfIatProtocol.FRAME_BYTES * 8)
        private var length = 0

        @Synchronized
        fun append(data: ByteArray) {
            if (data.isEmpty()) return
            if (length + data.size > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, length + data.size))
            }
            System.arraycopy(data, 0, buffer, length, data.size)
            length += data.size
        }

        @Synchronized
        fun takeFrame(): ByteArray? {
            if (length < XfIatProtocol.FRAME_BYTES) return null
            val frame = buffer.copyOfRange(0, XfIatProtocol.FRAME_BYTES)
            System.arraycopy(
                buffer,
                XfIatProtocol.FRAME_BYTES,
                buffer,
                0,
                length - XfIatProtocol.FRAME_BYTES,
            )
            length -= XfIatProtocol.FRAME_BYTES
            return frame
        }

        @Synchronized
        fun takeAll(): ByteArray {
            if (length == 0) return ByteArray(0)
            val rest = buffer.copyOfRange(0, length)
            length = 0
            return rest
        }

        @Synchronized
        fun clear() {
            length = 0
        }
    }

    private companion object {
        const val TAG = "XfIatClient"

        /** 40ms 一帧（官方节拍） */
        const val FRAME_MS = 40L

        /** 一拍最多补发多少帧（≈1 秒音频），避免卡顿后一次性灌成突发 */
        const val MAX_CATCH_UP_FRAMES = 25L

        const val NORMAL_CLOSURE = 1000

        /** 建连超时（秒）：讯飞在国内，5 秒足够，再长就是网络本身有问题 */
        const val CONNECT_TIMEOUT_SEC = 5L

        /** 状态条文案（都不含任何凭据原文） */
        const val TEXT_NEED_CREDENTIALS = "讯飞凭据未配置"
        const val TEXT_BAD_URL = "地址构造失败"
        const val TEXT_RECOGNIZE_FAILED = "识别失败"

        /**
         * 单独的 OkHttp 客户端：讯飞一次会话一条连接、结束即断，不需要 ping 保活
         * （`pingInterval=0` 关闭），与 [AsrClient] 那条常驻长连接的参数刻意不同。
         */
        val http = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
