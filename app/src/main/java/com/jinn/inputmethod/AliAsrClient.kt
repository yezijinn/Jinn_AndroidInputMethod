package com.jinn.inputmethod

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 阿里云百炼实时语音识别（Paraformer / Qwen-ASR）客户端，[SpeechClient] 的第四个实现。
 *
 * 端点由用户填写完整 WS 地址（默认 `wss://dashscope.aliyuncs.com/api-ws/v1/inference`，
 * 华北专属域名形如 `wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference`），
 * 鉴权是握手头 `Authorization: Bearer <API Key>` —— Key 无效时握手直接 401/403。
 *
 * 一次识别的时序（官方双向流式）：
 *  1. 建连 → 文本帧 `run-task`（`streaming=duplex`）；
 *  2. **等 `header.event == "task-started"`**，此前不发送任何音频（早发的二进制帧会被判协议错）；
 *  3. 二进制裸帧上行：16k / 单声道 / 16bit PCM，3200 字节 = 100ms；泵按已到期的帧数发送，
 *     落后就连发补齐，**不丢音频**；
 *  4. 松手 → 冲掉队列余量 → 文本帧 `finish-task`；
 *  5. **收到 `task-finished` 才关连接**（等不到按超时失败）。
 *
 * 一条连接只承载一个任务：上一段还在等 `task-finished` 时用户又按了一次，[beginTask] 直接
 * 重建连接并返回 null（这一次按键落空，下次即用新连接），绝不在旧连接上发第二个 `run-task`。
 *
 * 下行 `result-generated` 的 `payload.output.sentence.{text, sentence_end, heartbeat}`：
 * `heartbeat=true` 只是保活，跳过；`sentence_end=true` 是定句（整句文本，追加到全文），
 * `false` 是当前句的预览（**覆盖**当前句）。对外仍旧交整段累积文本（[RecognitionMessage.text]），
 * 调用方整体覆盖显示，不要自己再拼。
 *
 * `task-failed` 取 `header.error_code` / `error_message` 落到状态条 —— 这是「Key 无效 / 余额不足 /
 * 模型不存在」唯一的指路牌，只说「识别失败」用户与我们都没法下手。
 *
 * 与 [XfIatClient] 同款的生命周期取舍：一次会话一条连接，收尾即断，**随即预连下一条**
 * （下一次长按空格从 ACTION_DOWN 到真开始录音只有约 500ms，现等握手必然赶不上）；
 * 预连那条挂空闲窗口，没人再说话就归零。单次时长服务端不限，本应用仍按既有 58 秒收尾
 * （[Prefs.voiceBackend] 属在线后端，见 `JinnIme.autoStopDelayMs`）。
 *
 * 线程模型：音频转换在采集线程（纯 CPU、无锁）→ 入队；泵与超时跑在主线程 Handler
 * （每拍只做一次 `WebSocket.send`，不阻塞）；回调（`onState` / `onResult`）在 OkHttp 的 IO 线程触发，
 * 接收方自行切主线程。
 *
 * ⚠ 凭据只从 [Prefs] 读并**只进 Authorization 头**：日志里只有长度与「已配置 / 未配置」，绝不输出原文。
 */
class AliAsrClient(
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
    private var accumulator: AliTextAccumulator? = null

    @Volatile
    private var taskStartMs = 0L

    /** 缓冲溢出告警只打一次（每次 [beginTask] 复位）：避免采集线程每 100ms 刷一条 W */
    @Volatile
    private var overflowLogged = false

    /**
     * 本次连接使用的模型名：在 [connect] 校验时缓存。
     *
     * 不在各调用点现读 [Prefs.aliModel]：凭据读取可能因锁屏 / Keystore 暂时返回空串，
     * 那样会在会话中途发出没有 model 的 run-task，被服务端当场拒绝。
     */
    @Volatile
    private var sessionModel: String = ""

    /**
     * 本条连接上是否已经发过 `run-task`。
     *
     * 阿里**一条连接只承载一个任务**：`finish-task` 之后同一条 WebSocket 不能再开新任务。
     * 上一段还在等 `task-finished`（用户松手后立刻又按了一次）时，若在旧连接上再发一次
     * `run-task`，服务端按协议错处理；更要紧的是旧任务的 `task-finished` 回来时会调用
     * [onFinished]，把**新任务**的音频泵、累积器与连接一并收掉 —— 表现是「两段都没了」。
     * 这时必须强制重连（与 [XfIatClient.sessionStarted] 同一取舍：这一次按键落空，
     * 提示「正在连接」，而不是把手里的任务撞毁）。
     */
    @Volatile
    private var sessionStarted = false

    /**
     * 已发 `finish-task`、仍在等 `task-finished`。
     *
     * 与 [acceptingTask] **刻意分开**：取消路径把 acceptingTask 置空（结果丢弃），但它同样要等
     * 到 `task-finished` 才关连接。拿 acceptingTask 当「在收尾」的判据，取消之后超时分支会
     * 提前 return —— 连接与「在线」状态一直挂着，下一次按键还会去复用它。
     */
    @Volatile
    private var awaitingFinish = false

    // ── 音频泵 ──────────────────────────────────────────────

    private val pumpHandler = Handler(Looper.getMainLooper())

    /** 未发出的 PCM16 余量（按 3200 字节切帧；不足一帧的留到下一拍） */
    private val queue = AliFrameQueue(AliAsrProtocol.FRAME_BYTES)

    /** 已送出的音频帧数，用来按实时率算「这一拍该发几帧」 */
    private var framesSent = 0L

    /** 泵的基准时刻（`task-started` 到达时重置，音频节拍从那一刻算） */
    private var pumpStartMs = 0L

    private val pumpRunnable = Runnable { pumpTick() }

    /** `task-started` 是否已到（决定泵能不能开始发） */
    @Volatile
    private var pumpRunning = false

    // ── 超时与空闲关闭 ──────────────────────────────────────

    private val timeoutHandler = Handler(Looper.getMainLooper())

    private val startTimeoutRunnable = Runnable { onStartTimeout() }

    private val finishTimeoutRunnable = Runnable { onFinishTimeout() }

    private val idleRunnable = Runnable { onIdleCloseDue() }

    /**
     * 建连。
     *
     * `@Synchronized`：「关掉旧的 → 建新的并赋值给 [socket]」这一段必须原子。两个线程会同时
     * 进来 —— 主线程的 [beginTask]（旧连接被判「已用过」后强制重连）与 OkHttp IO 线程的
     * [releaseAndPreconnect]（收到 `task-finished` 后预连下一条），而「用户刚好在收尾那一瞬按键」
     * 正是 [sessionStarted] 专门要挡的场景。不串行化时两次 `newWebSocket` 可以交错到
     * 「两个都先 closeSocket、再各自赋值」：先建那条被后写的 `socket = …` 覆盖，它的回调从此
     * 因 `webSocket !== socket` 全部返回，**没有任何人再持有它** —— 一条没有读超时、没有 ping 的
     * 连接就这么白挂着，直到服务端自己断。
     */
    @Synchronized
    override fun connect(force: Boolean, userInitiated: Boolean) {
        if (userInitiated) cancelIdleClose()
        // 早退判据必须含 CONNECTING（与 [XfIatClient.connect] 同一条）：预连
        // （[releaseAndPreconnect]）与「进入语音面板」（`JinnIme.onStartInputView`）都会在握手窗口里
        // 再调一次 connect —— 只认 ONLINE 的话，这里会把**正在握手的 socket 关掉重建**，
        // 白做一次 TLS + WS 握手（状态条还要再闪一次「连接中」）。
        if (!force && (state == LinkState.ONLINE || state == LinkState.CONNECTING) && socket != null) return

        // 地址 / 凭据不齐会白等一次握手（甚至必然 401），在入口就落到状态条
        val normalized = AliEndpoint.normalize(prefs.aliWsUrl)
        val bad = normalized.exceptionOrNull()?.message
        if (bad != null) {
            Diagnostics.w(TAG, "connect: 地址不合法 reason=$bad")
            updateState(LinkState.OFFLINE, bad)
            return
        }
        val key = prefs.aliApiKey
        if (key.isEmpty()) {
            Diagnostics.w(TAG, "connect: API Key 未配置")
            updateState(LinkState.OFFLINE, TEXT_NEED_KEY)
            return
        }
        val model = prefs.aliModel.trim()
        if (model.isEmpty()) {
            Diagnostics.w(TAG, "connect: 模型名为空")
            updateState(LinkState.OFFLINE, TEXT_NEED_MODEL)
            return
        }

        closeSocket()
        updateState(LinkState.CONNECTING, null)
        sessionModel = model
        val url = normalized.getOrThrow()
        // 地址本身不含凭据（Key 只在头里），日志只记端点与 Key 长度
        Diagnostics.i(TAG, "connect: 阿里百炼在线 endpoint=$url model=$model keyLen=${key.length}")
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header(AliAsrProtocol.HEADER_AUTH, AliAsrProtocol.AUTH_SCHEME + key)
                .build()
        }.getOrElse {
            Diagnostics.e(TAG, "connect: 地址构造失败 ${it.javaClass.simpleName}")
            updateState(LinkState.OFFLINE, TEXT_BAD_URL)
            return
        }
        socket = http.newWebSocket(request, listener)
    }

    override fun beginTask(): String? {
        cancelIdleClose()
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
        accumulator = AliTextAccumulator()
        taskStartMs = System.currentTimeMillis()
        pumpStartMs = taskStartMs
        framesSent = 0
        pumpRunning = false
        queue.clear()
        overflowLogged = false

        if (!current.send(AliAsrProtocol.runTaskMessage(id, sessionModel))) {
            Diagnostics.w(TAG, "beginTask: run-task 发送失败，任务作废")
            clearTask()
            connect(force = true, userInitiated = true)
            return null
        }
        // 标记「这条连接已经用过」：在它等到 task-finished 之前不许再发第二个 run-task
        sessionStarted = true
        // 音频泵等 task-started 再启动；等不到就当场失败，别让状态条永远停在「识别中」
        timeoutHandler.removeCallbacks(startTimeoutRunnable)
        timeoutHandler.postDelayed(startTimeoutRunnable, START_TIMEOUT_MS)
        Diagnostics.i(TAG, "beginTask: $id")
        return id
    }

    override fun sendChunk(pcmFloat32LittleEndian: ByteArray) {
        if (taskId == null) return
        // 转换在采集线程上做（纯 CPU、无锁），入队是瞬时的，不会卡住麦克风
        if (queue.append(XfIatProtocol.float32ToPcm16(pcmFloat32LittleEndian)) && !overflowLogged) {
            // 每段任务只报一次（采集线程每 100ms 一包，逐包打会把诊断文件淹掉）
            overflowLogged = true
            Diagnostics.w(TAG, "待发缓冲到上限(${AliFrameQueue.MAX_QUEUE_BYTES} 字节≈60s)，后续音频丢弃")
        }
    }

    override fun endTask(): Boolean = finishTask(acceptResult = true)

    override fun cancelTask(): Boolean = finishTask(acceptResult = false)

    /**
     * 收尾：冲掉队列余量 → 发 finish-task → 等 `task-finished`。
     *
     * 余量不按 100ms 节拍等：用户已经松手，多等只是白延迟；服务端按收到的总字节数解码，
     * 连发不会丢音频。`task-finished` 才是「这段结果到此为止」的信号。
     *
     * `taskId == null` 的两种含义必须分开：从没开过任务 → false；[endTask] 已经发过 finish-task、
     * 只剩等 `task-finished` → 取消仍要能关掉采纳闸门，否则「松手后再取消」形同虚设，
     * 晚到的定稿照常上屏（[BufferedBatchAsrClient] 与另两家流式实现同一口径）。
     */
    private fun finishTask(acceptResult: Boolean): Boolean {
        val id = taskId
        if (id == null) {
            if (!acceptResult && acceptingTask != null) {
                acceptingTask = null
                Diagnostics.i(TAG, "cancelTask: finish-task 已发过，只丢弃在途结果")
                return true
            }
            return false
        }
        val started = pumpRunning
        cancelPump()
        val current = socket
        if (current == null || state != LinkState.ONLINE) {
            Diagnostics.w(TAG, "finishTask: 连接已断开，拿不到最终结果")
            clearTask()
            return false
        }
        // 先切「是否采纳结果」再发末帧：末帧一发出服务端就可能抢答，
        // 取消路径若等到发送之后才置空，那一两条回包会漏过闸门被当成正常结果显示。
        acceptingTask = if (acceptResult) id else null
        // 「还在等 task-finished」同样必须在发帧**之前**置位：回包可能比下一行执行得还快，
        // 反过来的话 task-finished 会先把它清掉、这里又置回 true —— 超时回调随后把刚成功的那一段
        // 覆盖成「等待识别结果超时」。发送失败时 clearTask() 会把它一起清掉。
        awaitingFinish = true
        // 极短的一次按键可能还没等到 task-started：那时发二进制帧就是协议错，
        // 只发 finish-task 让服务端正常结束这一段（结果为空 → 「没听到语音」，不是「连不上」）
        if (started) flushQueue(current)
        val ok = current.send(AliAsrProtocol.finishTaskMessage(id))
        taskId = null
        if (!ok) {
            Diagnostics.w(TAG, "finishTask: finish-task 发送失败 taskId=$id")
            clearTask()
            return false
        }
        timeoutHandler.removeCallbacks(finishTimeoutRunnable)
        timeoutHandler.postDelayed(finishTimeoutRunnable, FINISH_TIMEOUT_MS)
        Diagnostics.i(TAG, "finishTask: taskId=$id accept=$acceptResult")
        return true
    }

    override fun close() {
        cancelIdleClose()
        cancelPump()
        cancelTimeouts()
        closeSocket()
        clearTask()
        updateState(LinkState.IDLE, null)
        Diagnostics.i(TAG, "close: 主动关闭连接")
    }

    override fun scheduleIdleClose(delayMs: Long) {
        timeoutHandler.removeCallbacks(idleRunnable)
        timeoutHandler.postDelayed(idleRunnable, delayMs)
        Diagnostics.i(TAG, "scheduleIdleClose: ${delayMs}ms 无活动则断开")
    }

    override fun cancelIdleClose() {
        timeoutHandler.removeCallbacks(idleRunnable)
    }

    private fun onIdleCloseDue() {
        if (taskId != null || acceptingTask != null) {
            // 在途任务：此刻断开就是把结果丢掉。任务结束后本来就会断，这里只需跳过
            Diagnostics.i(TAG, "空闲关闭跳过：仍有在途任务")
            return
        }
        closeSocket()
        updateState(LinkState.IDLE, null)
        Diagnostics.i(TAG, "空闲关闭：连接已释放")
    }

    /**
     * 一段识别结束：断掉本条连接并**立刻预连下一条**（与 [XfIatClient.releaseAndPreconnect] 同一个取舍）。
     *
     * 下一次「长按空格」从 ACTION_DOWN 到真开始录音只有约 500ms，现等 TLS + WS 握手必然赶不上；
     * 预连把握手挪到用户松手后的空档，并挂上常规空闲窗口：没人再说话就归零，不常驻。
     */
    private fun releaseAndPreconnect() {
        cancelIdleClose()
        cancelPump()
        cancelTimeouts()
        closeSocket()
        clearTask()
        scheduleIdleClose(SpeechClient.DEFAULT_IDLE_CLOSE_MS)
        // 已经在连就不重复握手（`socket = http.newWebSocket(...)` 会把上一条覆盖成孤儿）；
        // 已被主动关闭（页面销毁 / `close()`）同样不许再建：那条新连接的空闲窗口刚被 close() 撤掉，
        // 建出来就是一条没有任何计时器持有它的连接
        if (socket != null || state == LinkState.CONNECTING || state == LinkState.IDLE) return
        connect(force = false, userInitiated = false)
    }

    // ── 音频泵 ──────────────────────────────────────────────

    /** `task-started` 到达：从此刻起按 100ms 节拍发音频（此前攒的音频一并补上，不丢） */
    private fun startPump() {
        if (taskId == null) return
        pumpRunning = true
        timeoutHandler.removeCallbacks(startTimeoutRunnable)
        pumpStartMs = System.currentTimeMillis()
        framesSent = 0
        pumpHandler.removeCallbacks(pumpRunnable)
        pumpHandler.postDelayed(pumpRunnable, AliAsrProtocol.FRAME_MS)
        Diagnostics.i(TAG, "task-started: 音频泵启动 taskId=$taskId")
    }

    /**
     * 一拍（100ms）：按「已到期的帧数」发，落后就一次多发几帧补齐。
     *
     * 上限 [MAX_CATCH_UP_FRAMES]：网络长时间卡顿后攒了一堆音频，一次灌几百帧会把上行打成突发
     * （服务端实时率判定反而更容易掉线），分几拍追上更稳。
     */
    private fun pumpTick() {
        val current = socket
        if (current == null || taskId == null || state != LinkState.ONLINE) return
        val elapsed = System.currentTimeMillis() - pumpStartMs
        val due = elapsed / AliAsrProtocol.FRAME_MS - framesSent
        var sent = 0L
        while (sent < due && sent < MAX_CATCH_UP_FRAMES) {
            val frame = queue.takeFrame() ?: break
            if (!current.send(frame.toByteString())) {
                Diagnostics.w(TAG, "pumpTick: 发送失败，停止本拍")
                return
            }
            sent++
            framesSent++
        }
        pumpHandler.postDelayed(pumpRunnable, AliAsrProtocol.FRAME_MS)
    }

    private fun cancelPump() {
        pumpRunning = false
        pumpHandler.removeCallbacks(pumpRunnable)
    }

    /** 把余量（不足一帧的尾巴也算）一次发完；空队列不发空帧 */
    private fun flushQueue(current: WebSocket) {
        val rest = queue.takeAll()
        if (rest.isEmpty()) return
        if (!current.send(rest.toByteString())) {
            Diagnostics.w(TAG, "flushQueue: 尾部音频发送失败 len=${rest.size}")
        }
    }

    // ── 超时 ────────────────────────────────────────────────

    private fun onStartTimeout() {
        if (taskId == null || pumpRunning) return
        Diagnostics.w(TAG, "等 task-started 超时")
        updateState(LinkState.OFFLINE, TEXT_START_TIMEOUT)
        cancelPump()
        clearTask()
        closeSocket()
    }

    private fun onFinishTimeout() {
        // 判据是「已发 finish-task 还在等」，不是 acceptingTask：取消路径早就把它置空了，
        // 那样超时分支会直接退出，连接再也关不掉
        if (!awaitingFinish) return
        Diagnostics.w(TAG, "等 task-finished 超时")
        acceptingTask = null
        updateState(LinkState.OFFLINE, TEXT_FINISH_TIMEOUT)
        cancelPump()
        closeSocket()
    }

    private fun cancelTimeouts() {
        timeoutHandler.removeCallbacks(startTimeoutRunnable)
        timeoutHandler.removeCallbacks(finishTimeoutRunnable)
    }

    // ── WebSocket 回调 ──────────────────────────────────────

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            Diagnostics.i(TAG, "onOpen: 阿里百炼已连线")
            // 新连接从「没用过」开始（一条连接只承载一个任务）
            sessionStarted = false
            updateState(LinkState.ONLINE, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            // 下行只有文本帧；二进制帧一律忽略（不把它当识别文本）
            if (webSocket !== socket) return
            if (Diagnostics.KEY_TRACE) Diagnostics.v(TAG, "onMessage: 忽略二进制帧 len=${bytes.size}")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            Diagnostics.w(TAG, "onFailure: ${t.javaClass.simpleName} response=${response?.code}")
            cancelPump()
            cancelTimeouts()
            // 连接已经断了，空闲兜底无事可做：留着它只会在 90s 后把刚写好的失败原因
            // 覆盖成「未连接」（与 [onFailed] 的服务端拒绝分支同一条）
            cancelIdleClose()
            socket = null
            clearTask()
            updateState(LinkState.OFFLINE, describe(t, response?.code))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            Diagnostics.i(TAG, "onClosed: code=$code")
            cancelPump()
            cancelTimeouts()
            cancelIdleClose()
            socket = null
            clearTask()
            updateState(LinkState.IDLE, null)
        }
    }

    /** 回包分派：`header.event` 决定动作，其余事件（心跳一类的杂项）只记 trace */
    private fun handleMessage(raw: String) {
        val event = AliAsrProtocol.parseEvent(raw)
        if (event == null) {
            // 只记长度不记原文：报文体里可能就是用户语音识别出的正文
            Diagnostics.w(TAG, "onMessage: 报文解析失败 len=${raw.length}")
            return
        }
        when (event.event) {
            AliAsrProtocol.EVENT_STARTED -> startPump()
            AliAsrProtocol.EVENT_RESULT -> onSentence(event.sentence)
            AliAsrProtocol.EVENT_FINISHED -> onFinished()
            AliAsrProtocol.EVENT_FAILED -> onFailed(event)
            else -> if (Diagnostics.KEY_TRACE) {
                Diagnostics.v(TAG, "onMessage: 未处理事件 ${event.event}")
            }
        }
    }

    /** 一句：`heartbeat` 跳过；定句追加、非定句覆盖当前句（对外仍是整段累积文本） */
    private fun onSentence(sentence: AliSentence?) {
        val s = sentence ?: return
        if (s.heartbeat) return
        val acc = accumulator ?: return
        val text = acc.accept(s)
        val task = acceptingTask ?: return
        val duration = (System.currentTimeMillis() - taskStartMs) / 1000.0
        onResult(RecognitionMessage(taskId = task, isFinal = false, duration = duration, text = text))
    }

    /** `task-finished`：交出定稿（空文本也交 —— 它代表链路正常走完），然后断连并预连下一条 */
    private fun onFinished() {
        cancelPump()
        cancelTimeouts()
        val task = acceptingTask
        acceptingTask = null
        val text = accumulator?.text().orEmpty()
        if (task != null) {
            val duration = (System.currentTimeMillis() - taskStartMs) / 1000.0
            onResult(RecognitionMessage(taskId = task, isFinal = true, duration = duration, text = text))
            Diagnostics.i(TAG, "task-finished: 最终结果 共${text.length}字")
        } else {
            // 取消路径：结果丢弃，但连接仍旧要收掉
            Diagnostics.i(TAG, "task-finished: 任务已作废，结果丢弃")
        }
        releaseAndPreconnect()
    }

    /** `task-failed`：`error_code` / `error_message` 落状态条，连接不可复用 */
    private fun onFailed(event: AliEvent) {
        val detail = listOfNotNull(
            event.errorCode?.take(32)?.takeIf { it.isNotEmpty() },
            event.errorMessage?.trim()?.take(60)?.takeIf { it.isNotEmpty() },
        ).joinToString(" ").ifEmpty { TEXT_RECOGNIZE_FAILED }
        // 先刮再落盘：`error_message` 可能整条来自网关回显（见 [SpeechErrorText]）；
        // 状态条那条出口在 [updateState] 里刮（幂等，两道都留着）
        Diagnostics.w(TAG, "onMessage: 服务端拒绝 ${SpeechErrorText.scrub(detail)}")
        cancelPump()
        cancelTimeouts()
        // 连接马上要断，空闲兜底无需再留着：真让它到点，90s 后那次 updateState(IDLE)
        // 会把刚写好的 error_code / error_message 覆盖成「未连接」（排障线索被自己抹掉）
        cancelIdleClose()
        clearTask()
        updateState(LinkState.OFFLINE, detail)
        closeSocket()
    }

    /** 状态条文案的唯一出口：异常正文 / `task-failed` 的 `error_message` 都过一遍凭据刮除 */
    private fun updateState(next: LinkState, detail: String?) {
        state = next
        onState(next, SpeechErrorText.scrub(detail))
    }

    private fun clearTask() {
        taskId = null
        acceptingTask = null
        accumulator = null
        pumpRunning = false
        queue.clear()
        // 任务状态一起收：连接断开（`onFailure` / `onClosed`）也要把「还在等 task-finished」清掉，
        // 否则 `awaitingFinish` 会留在 true 而 socket 已是 null —— 判据与连接状态脱节。
        // ⚠ 只清这一项：`sessionStarted` 是**连接**状态，不是任务状态，绝不许搬到这里复位
        // （在 clearTask 里把它复位 = 用过的连接又能跑第二个 run-task，正是本轮修掉的那个缺陷）。
        awaitingFinish = false
    }

    /** 拆连接（幂等）。与 [connect] 同一个监视器：`close` 不许插进「关旧的—建新的」中间。 */
    @Synchronized
    private fun closeSocket() {
        val current = socket
        socket = null
        // 连接一拆就不再等 task-finished 了：所有收尾路径（失败 / 超时 / 主动关闭 / 预连）都经过这里
        awaitingFinish = false
        // 先置 null：旧 listener 的 onClosed / onFailure 会因 webSocket !== socket 提前返回
        runCatching { current?.close(NORMAL_CLOSURE, null) }
            .onFailure { Diagnostics.w(TAG, "closeSocket: ${it.message}") }
    }

    private fun describe(t: Throwable, code: Int?): String = when {
        t is ConnectException -> "连不上阿里百炼"
        t is SocketTimeoutException -> "连接超时"
        t is UnknownHostException -> "地址无法解析"
        // 握手被拒（Key 无效 / 过期）时 OkHttp 给的是 ProtocolException + 401/403，状态码最指路
        code == 401 || code == 403 -> "鉴权失败（HTTP $code）"
        code != null -> "连接失败（HTTP $code）"
        // 先刮再截：反过来会把长正文里的鉴权头切成半截值，`AUTH_PREFIX` 的 ≥6 字符门槛就认不出它了
        else -> SpeechErrorText.scrub(t.message)?.take(40) ?: "连接失败"
    }

    private companion object {
        const val TAG = "AliAsrClient"

        /** 等 `task-started` 的上限：握手后服务端通常 100ms 内回 */
        const val START_TIMEOUT_MS = 5_000L

        /** 等 `task-finished` 的上限：自己定稿的兜底，别让状态条永久停在「识别中」 */
        const val FINISH_TIMEOUT_MS = 10_000L

        /** 一拍最多补发多少帧（≈0.8 秒音频），避免卡顿后一次性灌成突发 */
        const val MAX_CATCH_UP_FRAMES = 8L

        const val NORMAL_CLOSURE = 1000

        const val CONNECT_TIMEOUT_SEC = 8L

        /** 状态条文案（都不含任何凭据原文） */
        const val TEXT_NEED_KEY = "未填写 API Key"
        const val TEXT_NEED_MODEL = "未填写模型名"
        const val TEXT_BAD_URL = "地址构造失败"
        const val TEXT_RECOGNIZE_FAILED = "识别失败"
        const val TEXT_START_TIMEOUT = "服务端未开始识别（task-started 超时）"
        const val TEXT_FINISH_TIMEOUT = "等待识别结果超时"

        /**
         * 单独一条 OkHttp 客户端：一次会话一条连接、结束即断，不需要 ping 保活
         * （`pingInterval=0` 关闭），与 [AsrClient] 那条常驻长连接的参数刻意不同。
         */
        val http = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

/**
 * 百炼实时识别的报文层（纯函数，JVM 单测直接跑）。
 *
 * 字段名与官方文档逐字一致，**不要改名**：服务端按 `header.action` / `header.event` 严格分派，
 * 写错一个键就是「连上了但一句结果都没有」。
 */
internal object AliAsrProtocol {

    /** 一帧音频的字节数：16k / 单声道 / 16bit ⇒ 3200 字节 = 100ms */
    const val FRAME_BYTES = 3200

    const val FRAME_MS = 100L

    const val HEADER_AUTH = "Authorization"
    const val AUTH_SCHEME = "Bearer "

    const val ACTION_RUN = "run-task"
    const val ACTION_FINISH = "finish-task"
    const val STREAMING_DUPLEX = "duplex"

    const val EVENT_STARTED = "task-started"
    const val EVENT_RESULT = "result-generated"
    const val EVENT_FINISHED = "task-finished"
    const val EVENT_FAILED = "task-failed"

    const val TASK_GROUP = "audio"
    const val TASK = "asr"
    const val FUNCTION = "recognition"
    const val FORMAT = "pcm"
    const val SAMPLE_RATE = 16000

    /** 断句静音阈值（毫秒）：800 与官方示例一致 */
    const val MAX_SENTENCE_SILENCE = 800

    /**
     * 开任务帧。
     *
     * `parameters` 里三项是「中文可用」的最小集合：标点预测开、去口语顺滑关、
     * 语言提示锁中文（不锁会让模型在中英混说时来回切，短句误判明显）。
     */
    fun runTaskMessage(taskId: String, model: String): String {
        val parameters = JSONObject()
            .put("format", FORMAT)
            .put("sample_rate", SAMPLE_RATE)
            .put("disfluency_removal_enabled", false)
            .put("punctuation_prediction_enabled", true)
            .put("max_sentence_silence", MAX_SENTENCE_SILENCE)
            .put("language_hints", org.json.JSONArray().put("zh"))
        val payload = JSONObject()
            .put("task_group", TASK_GROUP)
            .put("task", TASK)
            .put("function", FUNCTION)
            .put("model", model)
            .put("parameters", parameters)
            .put("input", JSONObject())
        val header = JSONObject()
            .put("action", ACTION_RUN)
            .put("task_id", taskId)
            .put("streaming", STREAMING_DUPLEX)
        return JSONObject().put("header", header).put("payload", payload).toString()
    }

    /** 收尾帧：同一个 `task_id`，`payload.input` 为空对象（官方示例就是这样） */
    fun finishTaskMessage(taskId: String): String {
        val header = JSONObject()
            .put("action", ACTION_FINISH)
            .put("task_id", taskId)
            .put("streaming", STREAMING_DUPLEX)
        return JSONObject()
            .put("header", header)
            .put("payload", JSONObject().put("input", JSONObject()))
            .toString()
    }

    /**
     * 解析一个下行文本帧。
     *
     * 取不到 `header.event` 一律返回 null（不是本协议的事件，或报文体被截断）——
     * 调用方只记长度，不猜语义。
     */
    fun parseEvent(raw: String): AliEvent? = runCatching {
        val json = JSONObject(raw)
        val header = json.optJSONObject("header") ?: return null
        val event = header.optString("event").orEmpty()
        if (event.isEmpty()) return null
        val sentence = json.optJSONObject("payload")
            ?.optJSONObject("output")
            ?.optJSONObject("sentence")
            ?.let {
                AliSentence(
                    text = it.optString("text").orEmpty(),
                    sentenceEnd = it.optBoolean("sentence_end", false),
                    heartbeat = it.optBoolean("heartbeat", false),
                )
            }
        AliEvent(
            event = event,
            errorCode = header.optString("error_code").takeIf { it.isNotEmpty() },
            errorMessage = header.optString("error_message").takeIf { it.isNotEmpty() },
            sentence = sentence,
        )
    }.getOrNull()
}

/** 一个下行事件：`event` 必填，错误码与句子按事件类型出现 */
internal data class AliEvent(
    val event: String,
    val errorCode: String?,
    val errorMessage: String?,
    val sentence: AliSentence?,
)

/** `payload.output.sentence`：`sentence_end` 定句 / `heartbeat` 保活 */
internal data class AliSentence(
    val text: String,
    val sentenceEnd: Boolean,
    val heartbeat: Boolean,
)

/**
 * 文本累积器（纯逻辑，JVM 单测直接跑）。
 *
 * 规则只有两条，但**不能合成一条**：
 *  - `sentence_end=true` ⇒ 整句已定，**追加**到全文；
 *  - `sentence_end=false` ⇒ 只是当前句的预览，**覆盖**当前句（同一句会被服务端反复下发）；
 *  - `heartbeat=true` ⇒ 保活帧，既不带文本也不该动状态。
 *
 * 拼错的表现是「同一句重复出现」或「说着说着前半句没了」，两者都只有在真机上才看得出来。
 */
internal class AliTextAccumulator {

    private val committed = StringBuilder()

    private var current = ""

    fun accept(sentence: AliSentence): String {
        if (sentence.heartbeat) return text()
        if (sentence.sentenceEnd) {
            committed.append(sentence.text)
            current = ""
        } else {
            current = sentence.text
        }
        return text()
    }

    /** 对外恒为整段累积文本（定句全文 + 当前句预览） */
    fun text(): String = committed.toString() + current
}

/**
 * PCM16 待发缓冲：按 [frameBytes] 切、不足一帧留到下一拍。
 *
 * 手写而不是用 `ByteArrayOutputStream`：需要**按帧消费**（取走前 N 字节、其余留在原地），
 * 流式容器取不出这个语义。写入在采集线程、取帧在主线程，故全方法 `@Synchronized`。
 *
 * `internal` 是为了能在 JVM 单测里直接验「不丢字节 / 不乱序」。
 */
internal class AliFrameQueue(
    private val frameBytes: Int,
    private val maxBytes: Int = MAX_QUEUE_BYTES,
) {

    private var buffer = ByteArray(frameBytes * 8)
    private var length = 0

    /**
     * 入队；返回 true = **这一包被丢弃**（缓冲已到上限）。
     *
     * 泵在发送失败时会停拍（`pumpTick` 见到 `send` 返回 false 就 `return`，不重排）而
     * `taskId` 仍非空 ⇒ [AliAsrClient.sendChunk] 继续入队、再没人取走。8 秒的 3200B 帧只是
     * 25 KB，一分钟就是 1.9 MB，一路涨到 OOM 死的是整个输入法进程。口径与
     * [BufferedBatchAsrClient] 的 `MAX_PCM_BYTES` 一致：丢尾巴保住进程。
     */
    @Synchronized
    fun append(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        if (length + data.size > maxBytes) return true
        if (length + data.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, length + data.size))
        }
        System.arraycopy(data, 0, buffer, length, data.size)
        length += data.size
        return false
    }

    @Synchronized
    fun takeFrame(): ByteArray? {
        if (length < frameBytes) return null
        val frame = buffer.copyOfRange(0, frameBytes)
        System.arraycopy(buffer, frameBytes, buffer, 0, length - frameBytes)
        length -= frameBytes
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

    internal companion object {
        /** 待发缓冲上限：60 秒 × 16000 Hz × 2 字节（≈1.8 MB），与批量基类同一个口径 */
        const val MAX_QUEUE_BYTES = 60 * 16_000 * 2
    }
}
