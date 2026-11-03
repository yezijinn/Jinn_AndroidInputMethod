package com.jinn.inputmethod

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讯飞客户端（[XfIatClient]）及其两侧（后端选择 / 凭据落盘）的接线守卫。
 *
 * 这个类依赖 `Handler` / `Looper` / OkHttp，进不了 JVM 单测，只能在源码上对拍。
 * 钉的全是**真机上出问题后无法从日志反推**的时序：
 *  - 一次会话一条连接：复用已用过的连接，服务端会把新音频接在上一段后面（结果串台）；
 *  - 收尾顺序：余量必须在末帧之前发出，反了就是最后不到 40ms 的音频丢失；
 *  - `ls` 之后必须断连且不再回调，`code != 0` 必须落到状态条（不许静默）；
 *  - 凭据只能出现在鉴权 URL 与首帧里，**任何日志都不许出现原文或整条 URL**。
 *
 * 反向钉一律走 [TestSources]（剥注释后再判）。
 */
class XfIatClientTest {

    private val client = TestSources.codeSource("XfIatClient.kt")

    @Test
    fun `连接未就绪时 beginTask 返回 null 并顺手建连`() {
        val begin = TestSources.blockAfter(client, "override fun beginTask")
        assertTrue("连接未就绪必须返回 null（调用方据此不挂等待态）", "return null" in begin)
        assertTrue("必须顺手触发一次建连", "connect(force = true, userInitiated = true)" in begin)
        assertTrue("可用性要连 state 一起判（只有 socket 非空不够）", "state != LinkState.ONLINE" in begin)
        // 凭据不全时不能拼出必然 401 的地址白等一次握手
        val connect = TestSources.blockAfter(client, "override fun connect")
        assertTrue("凭据不全必须就地落到状态条", "updateState(LinkState.OFFLINE, TEXT_NEED_CREDENTIALS)" in connect)
        assertTrue(
            "凭据不全必须在 buildAuthUrl 之前返回（不许发出半成品地址）",
            connect.indexOf("TEXT_NEED_CREDENTIALS") in 0 until connect.indexOf("XfIatProtocol.buildAuthUrl("),
        )
        // 日志只许记长度：三件凭据的原文绝不进字符串
        assertTrue("凭据长度日志丢了（排障时要知道是「没配」还是「配了空的」）", "apiKeyLen=" in connect)
    }

    @Test
    fun `一次会话一条连接且结果送达后断连`() {
        assertTrue("缺少 sessionStarted 复用闸门", "private var sessionStarted = false" in client)
        val begin = TestSources.blockAfter(client, "override fun beginTask")
        assertTrue("beginTask 必须检查会话是否已用过", "sessionStarted" in begin)
        assertTrue("首帧发成功后必须置位", "sessionStarted = true" in begin)
        val open = TestSources.blockAfter(client, "override fun onOpen")
        assertTrue("新连接握手后必须复位，否则新连接永远开不了任务", "sessionStarted = false" in open)

        val handle = TestSources.blockAfter(client, "private fun handleMessage")
        assertTrue("ls 或 header.status=2 都算收尾", "val isFinal = result.ls || response.status == 2" in handle)
        val final = handle.indexOf("if (isFinal) {")
        assertTrue("最终结果分支丢了", final >= 0)
        // 收尾必须断掉旧连接（一次会话一条连接），并按需预连下一条。
        // 少了预连：下一次按键到真开始录音只有 500ms（长按阈值）/ 0ms（语音面板单击），
        // 现等握手必然赶不上 —— 第二段稳定提示「正在连接，请再说一次」。
        assertTrue("最终结果送达后必须断旧连接并预连", "releaseAndPreconnect()" in handle.substring(final))
        assertTrue("已取消 / 已收尾的任务不许再回调结果", "if (task == null)" in handle)

        val release = TestSources.blockAfter(client, "private fun releaseAndPreconnect")
        val closeAt = release.indexOf("closeSocket()")
        val warmAt = release.indexOf("connect(force = false, userInitiated = false)")
        assertTrue("预连必须在旧连接关掉之后（否则会被 closeSocket 一并关掉）", closeAt >= 0 && warmAt > closeAt)
        assertTrue("预连完必须挂空闲窗口，否则没人用也一直连着", "scheduleIdleClose(" in release)
        assertTrue("已在连时不许重复握手（会把上一条 socket 覆盖成孤儿）", "state == LinkState.CONNECTING" in release)

        // 旧连接的回包必须丢弃：close() 之后 closeSocket 已把 socket 置空
        val onMessage = TestSources.blockAfter(client, "override fun onMessage")
        assertTrue("onMessage 必须比对当前 socket（断连后旧帧不许再进来）", "webSocket !== socket" in onMessage)
        val closeSocket = TestSources.blockAfter(client, "private fun closeSocket")
        assertTrue("closeSocket 必须先置空再关，否则旧 listener 的回调会打进新连接", closeSocket.indexOf("socket = null") in 0..closeSocket.indexOf(".close("))
    }

    @Test
    fun `收尾先冲余量再发末帧且取消走同一条收尾`() {
        assertTrue(
            "endTask 必须走 acceptResult=true 的收尾",
            "override fun endTask(): Boolean = finishTask(acceptResult = true)" in client,
        )
        assertTrue(
            "cancelTask 也必须发末帧（服务端要 status=2 才收尾）",
            "override fun cancelTask(): Boolean = finishTask(acceptResult = false)" in client,
        )
        val finish = TestSources.blockAfter(client, "private fun finishTask")
        val flush = finish.indexOf("flushQueue(current)")
        val last = finish.indexOf("lastFrame(")
        assertTrue("余量必须先发完", flush >= 0)
        assertTrue("末帧必须在余量之后（顺序反了就是尾巴丢音频）", last > flush)
        val gate = finish.indexOf("acceptingTask = if (acceptResult) id else null")
        assertTrue("取消必须在发末帧之前就关掉采纳闸门（末帧一发出服务端就可能抢答）", gate in 0 until last)

        // 40ms 泵：落后连发必须有上限，否则一次灌几百帧会被服务端按突发限流
        assertTrue("节拍必须是官方要求的 40ms", "const val FRAME_MS = 40L" in client)
        assertTrue("连发没有上限", "MAX_CATCH_UP_FRAMES" in client)
        val tick = TestSources.blockAfter(client, "private fun pumpTick")
        assertTrue("按「已到期的帧数」补发才算不丢音频", "framesSent" in tick)
        assertTrue("发送失败必须停拍并留痕（静默重试会把音频灌进已坏的连接）", "发送失败" in tick)
    }

    @Test
    fun `错误落状态条且空闲关闭真的实现`() {
        val handle = TestSources.blockAfter(client, "private fun handleMessage")
        assertTrue("code != 0 必须落到状态条", "updateState(LinkState.OFFLINE" in handle)
        assertTrue("code != 0 必须留日志（不许静默）", "Diagnostics.w(" in handle)
        assertTrue("code != 0 必须断连（半死连接留着只会让下一段复用错会话）", "closeSocket()" in handle)
        // 结论里必须带上服务端 code：message 可能为空，只剩「识别失败」时用户与我们都无从下手
        // （语音测试的结论正是拿这条 detail 拼的，见 VoiceTestVerdict.linkLost）
        assertTrue("code != 0 的结论丢了服务端 code", "code \${response.code}" in handle)

        assertTrue("空闲关闭没有排程入口", "idleHandler.postDelayed(idleRunnable" in client)
        val idle = TestSources.blockAfter(client, "private fun onIdleCloseDue")
        assertTrue("空闲关闭必须真的断连", "closeSocket()" in idle)
        assertTrue("关闭后状态必须回落（不许把 state 卡在 ONLINE / CONNECTING）", "updateState(LinkState.IDLE" in idle)
        assertTrue("在途任务时不许关（会把最终结果丢掉）", "taskId != null || acceptingTask != null" in idle)
        // 抛异常会把 Handler 的回调栈打穿（主线程直接崩）
        assertFalse("空闲关闭里不许出现 !!(空断言)", "!!" in idle)
    }

    @Test
    fun `凭据不进日志且鉴权 URL 不整条落盘`() {
        // 只有 `${…凭据…}` 形态才会把值带进字符串；`${apiKey.length}` 是允许的（只记长度），
        // 命名参数 `apiKey = apiKey` 同理不算
        for (name in listOf("xfAppId", "xfApiKey", "xfApiSecret", "apiKey", "apiSecret")) {
            assertFalse(
                "凭据变量被插进字符串（多半是日志）：$name",
                Regex("""\$\{[^}]*\b$name\b(?!\.length)""").containsMatchIn(client),
            )
        }
        // 整条 URL 里带 authorization（等同凭据），同样不许进日志
        assertFalse("鉴权 URL 被整条插进字符串", "\${url}" in client)
        assertTrue("只允许记主机 + 长度", "urlLen=" in client)
        // 异常正文可能回显 URL（OkHttp 的 "unexpected url: …"），只许记类型
        assertTrue("地址构造失败只许记异常类型", "connect: 地址构造失败 \${it.javaClass.simpleName}" in client)

        // 三件凭据走同一套 CredentialCrypto 读写助手（不是明文 sp）
        val prefs = TestSources.codeSource("Prefs.kt")
        for (key in listOf("KEY_XF_APP_ID", "KEY_XF_API_KEY", "KEY_XF_API_SECRET")) {
            assertTrue("$key 读侧没走加密通道", "get() = readCredential($key)" in prefs)
            assertTrue("$key 写侧没走加密通道", "set(value) = writeCredential($key, value)" in prefs)
        }
    }

    @Test
    fun `后端选择与时长上限只作用于讯飞`() {
        // 非法 / 缺失 / 大小写不符一律回落自建（在线后端要求自带凭据，不能默认开）
        assertEquals(VoiceBackend.CAPS, VoiceBackend.of(null))
        assertEquals(VoiceBackend.CAPS, VoiceBackend.of(""))
        assertEquals(VoiceBackend.CAPS, VoiceBackend.of("openai"))
        assertEquals(VoiceBackend.XF, VoiceBackend.of(VoiceBackend.XF))

        val ime = TestSources.codeSource("JinnIme.kt")
        // 后端分叉收敛到统一工厂（JinnIme 与 VoiceSelfTest 共用一处，见 SpeechClients）
        assertTrue("ensureVoiceReady 必须走统一工厂", "SpeechClients.create(" in ime)
        assertFalse("JinnIme 里不许再有后端 if/else 分叉", "if (backend == VoiceBackend.XF)" in ime)
        val factory = TestSources.codeSource("SpeechClients.kt")
        assertTrue("工厂没装配讯飞实现", "XfIatClient(prefs, onState, onMessage)" in factory)
        assertTrue("工厂没装配自建实现", "AsrClient(prefs, onState, onMessage)" in factory)
        assertTrue("工厂没装配 OpenAI 兼容实现", "OpenAiAsrClient(prefs, onState, onMessage)" in factory)
        // 58s 只对在线后端生效：CapsWriter 服务端按连接持有缓冲，可以长跑
        val auto = TestSources.window(ime, "private fun autoStopDelayMs", "private fun onSilenceDetected")
        assertTrue(
            "在线后端（讯飞 / OpenAI 兼容）必须 58s 收尾（单次音频硬上限 60s）",
            "VoiceBackend.isOnline(prefs.voiceBackend)" in auto,
        )
        assertTrue("自建后端必须保持原值", "else MAX_TOGGLE_MS" in auto)
        assertTrue("58s 常量丢了", "const val XF_MAX_TASK_MS = 58_000L" in ime)

        // 26 键长按（HOLD）同样会超过 60 秒：在线后端必须也排自动收尾，否则后半段被服务端截断。
        // 判据两半缺一不可：① HOLD 分支真的 postDelayed 了；② 收尾 runnable 不再按 mode 挑 TOGGLE
        // （否则 HOLD 那次收尾被静默吃掉，看着像「58s 上限没生效」）。
        val start = TestSources.blockAfter(ime, "private fun startRecording")
        assertTrue(
            "在线后端的 HOLD 没有 58s 收尾",
            "VoiceBackend.isOnline(prefs.voiceBackend)" in start,
        )
        assertTrue("HOLD 分支必须排 autoStopRunnable", "autoStopRunnable, XF_MAX_TASK_MS" in start)
        val autoStop = TestSources.blockAfter(ime, "private val autoStopRunnable")
        assertTrue("收尾 runnable 只认 TOGGLE，HOLD 到点会被吃掉", "if (mode != Mode.NONE)" in autoStop)
        // 接口默认窗口必须与自建实现的原值同值：Kotlin 不许覆盖函数再给默认值，
        // 两处取值一分叉，「只打空格的人」那条投机连接的空闲窗口就被静默改掉（MEM-13）
        val asr = TestSources.codeSource("AsrClient.kt")
        assertTrue("自建后端的空闲关闭窗口变了（90s）", "const val IDLE_CLOSE_MS = 90_000L" in asr)
        assertEquals(
            "SpeechClient 的默认空闲关闭窗口与 AsrClient 不一致",
            SpeechClient.DEFAULT_IDLE_CLOSE_MS,
            90_000L,
        )
        assertTrue("自建后端必须实现统一接口", ") : SpeechClient {" in asr)
    }

    // ── 音频待发缓冲（唯一能在 JVM 里真跑的一段）────────────────────

    /**
     * 切帧不丢字节：麦克风 100ms 一批喂进来，泵按 40ms 一帧取走。
     *
     * 丢了这一段真机看不到错误，只表现为「最后几个字没识别出来」—— 与识别准确率混淆，
     * 无法从日志反推，故在 JVM 里直接对拍（`PcmQueue` 无 Android 依赖）。
     */
    @Test
    fun `待发缓冲按帧切且一个字节都不丢`() {
        val q = XfIatClient.PcmQueue()
        val src = ByteArray(XfIatProtocol.FRAME_BYTES * 2 + 300) { (it % 251).toByte() }
        // 模拟采集线程：分两批 append，边界故意不落在 1280 上
        q.append(src.copyOfRange(0, 1000))
        q.append(src.copyOfRange(1000, src.size))

        val first = q.takeFrame()
        val second = q.takeFrame()
        assertNotNull("第一批攒够一帧就该产出", first)
        assertNotNull("跨批次的字节必须接着算（不能每批各自切帧）", second)
        assertNull("不足一帧不产出", q.takeFrame())

        val rest = q.takeAll()
        assertEquals("余量必须是那一截尾巴", 300, rest.size)
        assertArrayEquals("取出的帧拼回来必须与输入逐字节相同", src, first!! + second!! + rest)
    }

    @Test
    fun `待发缓冲收尾只吐余量不发空帧且可清空`() {
        val q = XfIatClient.PcmQueue()
        q.append(ByteArray(XfIatProtocol.FRAME_BYTES - 1))
        assertNull("不足一帧不许产出（服务端收到半帧 PCM 会解码成噪声）", q.takeFrame())
        assertEquals("收尾要把余量整段吐出", XfIatProtocol.FRAME_BYTES - 1, q.takeAll().size)
        assertEquals("吐完必须是空的（收尾不该再发一个空帧）", 0, q.takeAll().size)

        q.append(ByteArray(10))
        q.clear()
        assertEquals("clear 之后不许残留", 0, q.takeAll().size)
        q.append(ByteArray(0))
        assertEquals("空 append 不能改变长度", 0, q.takeAll().size)
    }

    /**
     * 待发缓冲必须有上限（2026-11-03 复审）。
     *
     * 泵在 `send` 返回 false 时会停拍且不重排（见 [XfIatClient.pumpTick]），而 `taskId` 仍非空
     * ⇒ `sendChunk` 继续入队、再没人取走：32 KB/s 一路涨到 OOM，死的是整个输入法进程。
     * 判据两半都要：超限那一包**真的被丢**（返回 true），已收下的字节**一个不少、不乱序**
     * （丢尾巴不能丢前半段）。
     */
    @Test
    fun `待发缓冲有上限且超限只丢尾巴`() {
        val cap = XfIatProtocol.FRAME_BYTES * 4
        val q = XfIatClient.PcmQueue(maxBytes = cap)
        val keep = ByteArray(cap - 1) { (it % 251).toByte() }
        assertFalse("没到上限不许丢", q.append(keep))
        // 上限是**字节水位**，不是整包粒度：还剩 1 字节时这一包照样进得来
        assertFalse("还剩 1 字节空间时不该丢", q.append(ByteArray(1)))
        assertTrue("满了以后那一包必须被丢（否则缓冲无上限）", q.append(ByteArray(1)))

        val rest = q.takeAll()
        assertEquals("上限内的字节必须原样留着（丢的是尾巴，不是前半段）", cap, rest.size)
        assertArrayEquals("留下的必须是先到的那一段且逐字节相同", keep + ByteArray(1), rest)

        // 取空之后又能收（上限是水位，不是一次性的关闸）
        assertFalse("腾出空间后必须能继续入队", q.append(ByteArray(8)))
        assertEquals(8, q.takeAll().size)
    }
}
