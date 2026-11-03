package com.jinn.inputmethod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阿里云百炼实时语音识别的接线守卫（2026-11-01，第四个后端）。
 *
 * 只能对拍两端：协议层与文本累积器是**纯函数**，可以直接跑；客户端本身依赖 OkHttp 与 Handler，
 * 进不了 JVM 单测，只能读源码钉结构与关键判据（同 `XfIatClientTest` / `OpenAiAsrClientTest` 的做法）。
 *
 * ⚠ 反向钉一律走 `TestSources`（剥注释后再判），别自己写「按整行剥注释」。
 */
class AliAsrClientTest {

    // ── 协议层 ───────────────────────────────────────────────────

    @Test
    fun `run-task 帧字段齐全`() {
        val json = JSONObject(AliAsrProtocol.runTaskMessage("tid-1", "paraformer-realtime-v2"))
        assertEquals(setOf("header", "payload"), json.keys().asSequence().toSet())

        val header = json.getJSONObject("header")
        assertEquals("run-task", header.getString("action"))
        assertEquals("tid-1", header.getString("task_id"))
        assertEquals("duplex", header.getString("streaming"))

        val payload = json.getJSONObject("payload")
        assertEquals("audio", payload.getString("task_group"))
        assertEquals("asr", payload.getString("task"))
        assertEquals("recognition", payload.getString("function"))
        assertEquals("paraformer-realtime-v2", payload.getString("model"))
        assertEquals("input 必须是空对象", 0, payload.getJSONObject("input").length())

        val p = payload.getJSONObject("parameters")
        assertEquals("pcm", p.getString("format"))
        assertEquals(16000, p.getInt("sample_rate"))
        // 去口语顺滑关、标点预测开：中文短句要标点，去口语会把「那个」删掉
        assertFalse(p.getBoolean("disfluency_removal_enabled"))
        assertTrue(p.getBoolean("punctuation_prediction_enabled"))
        assertEquals(800, p.getInt("max_sentence_silence"))
        assertEquals("zh", p.getJSONArray("language_hints").getString(0))
    }

    @Test
    fun `finish-task 帧复用同一个任务号`() {
        val json = JSONObject(AliAsrProtocol.finishTaskMessage("tid-42"))
        val header = json.getJSONObject("header")
        assertEquals("finish-task", header.getString("action"))
        assertEquals("tid-42", header.getString("task_id"))
        assertEquals("duplex", header.getString("streaming"))
        assertEquals("payload.input 必须是空对象", 0, json.getJSONObject("payload").getJSONObject("input").length())
    }

    @Test
    fun `result-generated 解析句子三字段`() {
        val event = AliAsrProtocol.parseEvent(
            """{"header":{"event":"result-generated","task_id":"t"},
               "payload":{"output":{"sentence":{"text":"你好世界","sentence_end":true,
               "heartbeat":false,"begin_time":0}}},
               "context":{"request_id":"r"}}"""
        )
        assertNotNull("合法事件必须解析出对象", event)
        assertEquals(AliAsrProtocol.EVENT_RESULT, event!!.event)
        val sentence = event.sentence
        assertNotNull("result-generated 必须带 sentence", sentence)
        assertEquals("你好世界", sentence!!.text)
        assertTrue("定句标志丢了", sentence.sentenceEnd)
        assertFalse(sentence.heartbeat)
    }

    @Test
    fun `task-failed 取错误码与错误信息`() {
        val event = AliAsrProtocol.parseEvent(
            """{"header":{"event":"task-failed","task_id":"t",
               "error_code":"InvalidApiKey","error_message":"Invalid API-key provided."}}"""
        )
        assertNotNull(event)
        assertEquals(AliAsrProtocol.EVENT_FAILED, event!!.event)
        assertEquals("InvalidApiKey", event.errorCode)
        assertEquals("Invalid API-key provided.", event.errorMessage)
        assertNull("失败帧没有句子", event.sentence)
    }

    @Test
    fun `不是本协议的事件解析成 null`() {
        assertNull(AliAsrProtocol.parseEvent(""))
        assertNull(AliAsrProtocol.parseEvent("not json"))
        // 没有 header.event：不猜语义（调用方只记长度）
        assertNull(AliAsrProtocol.parseEvent("""{"payload":{"output":{}}}"""))
        assertNull(AliAsrProtocol.parseEvent("""{"header":{"task_id":"t"}}"""))
    }

    // ── 文本累积 ─────────────────────────────────────────────────

    @Test
    fun `定句追加而非定句覆盖当前句`() {
        val acc = AliTextAccumulator()
        assertEquals("你好", acc.accept(AliSentence("你好", sentenceEnd = false, heartbeat = false)))
        // 同一句的预览反复下发：必须是**覆盖**，不是追加（否则同一句会重复出现）
        assertEquals("你好世", acc.accept(AliSentence("你好世", sentenceEnd = false, heartbeat = false)))
        assertEquals("你好世界。", acc.accept(AliSentence("你好世界。", sentenceEnd = true, heartbeat = false)))
        // 定句之后开始新的一句：预览只能覆盖「当前句」，前半句必须留着
        assertEquals("你好世界。再", acc.accept(AliSentence("再", sentenceEnd = false, heartbeat = false)))
        assertEquals(
            "你好世界。再见。",
            acc.accept(AliSentence("再见。", sentenceEnd = true, heartbeat = false)),
        )
    }

    @Test
    fun `heartbeat 帧不改文本`() {
        val acc = AliTextAccumulator()
        acc.accept(AliSentence("你好", sentenceEnd = true, heartbeat = false))
        assertEquals("你好", acc.accept(AliSentence("心跳", sentenceEnd = false, heartbeat = true)))
        // 心跳带的 text 再离谱也不许动状态
        assertEquals("你好", acc.accept(AliSentence("", sentenceEnd = false, heartbeat = true)))
        assertEquals("你好", acc.text())
    }

    // ── 音频队列 ─────────────────────────────────────────────────

    @Test
    fun `音频按 3200 字节切帧且不丢字节`() {
        val queue = AliFrameQueue(AliAsrProtocol.FRAME_BYTES)
        val first = ByteArray(AliAsrProtocol.FRAME_BYTES) { 1 }
        val rest = ByteArray(100) { 2 }
        queue.append(first + rest)

        val frame = queue.takeFrame()
        assertNotNull("满一帧必须能取出", frame)
        assertEquals(AliAsrProtocol.FRAME_BYTES, frame!!.size)
        assertTrue("取出的帧不是队首那段", frame.all { it == 1.toByte() })
        assertNull("不足一帧时不许凑数", queue.takeFrame())
        assertTrue("余量必须还在（丢字节就是丢音频）", queue.takeAll().contentEquals(rest))
        assertTrue("取空后再取应当是空数组", queue.takeAll().isEmpty())
    }

    @Test
    fun `3200 字节正好是 100 毫秒`() {
        // 16k / 单声道 / 16bit ⇒ 32000 字节每秒 ⇒ 100ms = 3200 字节
        assertEquals(3200, AliAsrProtocol.FRAME_BYTES)
        assertEquals(100L, AliAsrProtocol.FRAME_MS)
        assertEquals(3200, AliAsrProtocol.SAMPLE_RATE * 2 / 10)
    }

    /**
     * 待发缓冲必须有上限（2026-11-03 复审）。
     *
     * 泵在 `send` 返回 false 时停拍且不重排，而 `taskId` 仍非空 ⇒ `sendChunk` 继续入队、
     * 再没人取走（一分钟就是 1.9 MB，最终 OOM，死的是整个输入法进程）。
     * 判据：超限那一包真的被丢；上限内的字节一个不少、不乱序。
     */
    @Test
    fun `待发缓冲有上限且超限只丢尾巴`() {
        val queue = AliFrameQueue(AliAsrProtocol.FRAME_BYTES, maxBytes = AliAsrProtocol.FRAME_BYTES * 2)
        val keep = ByteArray(AliAsrProtocol.FRAME_BYTES * 2) { (it % 251).toByte() }
        assertFalse("没到上限不许丢", queue.append(keep))
        assertTrue("满了以后那一包必须被丢（否则缓冲无上限）", queue.append(ByteArray(1)))
        val rest = queue.takeAll()
        assertEquals("上限内的字节必须原样留着", keep.size, rest.size)
        assertTrue("留下的必须是先到的那一段", rest.contentEquals(keep))
        assertFalse("取空之后必须能继续收（上限是水位，不是关闸）", queue.append(ByteArray(8)))
        assertEquals(8, queue.takeAll().size)
    }

    // ── 地址策略 ─────────────────────────────────────────────────

    @Test
    fun `地址只放行 wss 与本机 ws`() {
        assertEquals(
            Prefs.DEFAULT_ALI_WS_URL,
            AliEndpoint.normalize(Prefs.DEFAULT_ALI_WS_URL).getOrThrow(),
        )
        // scheme 大小写不影响判定，尾部 `/` 归一掉
        assertEquals(
            Prefs.DEFAULT_ALI_WS_URL,
            AliEndpoint.normalize("WSS://dashscope.aliyuncs.com/api-ws/v1/inference/").getOrThrow(),
        )
        // 专属域名原样保留（Workspace 端点就是这条路径）
        val dedicated = "wss://ws-1234.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"
        assertEquals(dedicated, AliEndpoint.normalize(dedicated).getOrThrow())

        // 公网明文一律拒：上行是整段录音
        assertEquals(
            AliEndpoint.TEXT_PUBLIC_WS,
            AliEndpoint.normalize("ws://dashscope.aliyuncs.com/api-ws/v1/inference").exceptionOrNull()?.message,
        )
        // 本机 / 局域网明文放行（自建代理）
        assertEquals(
            "ws://127.0.0.1:8765/api-ws",
            AliEndpoint.normalize("ws://127.0.0.1:8765/api-ws").getOrThrow(),
        )
        assertEquals(
            "ws://192.168.1.9:8765/api-ws/v1/inference",
            AliEndpoint.normalize("ws://192.168.1.9:8765/api-ws/v1/inference").getOrThrow(),
        )
        // 其余拒绝原因要能直接给用户看
        assertEquals(AliEndpoint.TEXT_EMPTY, AliEndpoint.normalize("  ").exceptionOrNull()?.message)
        assertEquals(AliEndpoint.TEXT_NO_SCHEME, AliEndpoint.normalize("https://x/y").exceptionOrNull()?.message)
        assertEquals(AliEndpoint.TEXT_BLANK, AliEndpoint.normalize("wss://a b/c").exceptionOrNull()?.message)
        assertEquals(AliEndpoint.TEXT_BAD_CHARS, AliEndpoint.normalize("wss://h/p?x=1").exceptionOrNull()?.message)
        assertEquals(AliEndpoint.TEXT_BAD_CHARS, AliEndpoint.normalize("wss://u:p@h/p").exceptionOrNull()?.message)
        assertEquals(AliEndpoint.TEXT_NO_HOST, AliEndpoint.normalize("wss:///p").exceptionOrNull()?.message)
    }

    // ── 客户端接线（源码对拍） ────────────────────────────────────

    @Test
    fun `客户端按官方时序收发`() {
        val client = TestSources.codeSource("AliAsrClient.kt")
        // 握手头带 Bearer（Key 只走这里）
        assertTrue("没带 Authorization 头", "AliAsrProtocol.HEADER_AUTH" in client)
        assertTrue("Bearer 拼接丢了", "AliAsrProtocol.AUTH_SCHEME + key" in client)
        // 先 run-task → 等 task-started → 才开泵
        assertTrue("beginTask 没发 run-task", "AliAsrProtocol.runTaskMessage(id, sessionModel)" in client)
        val begin = TestSources.blockAfter(client, "override fun beginTask")
        assertTrue("beginTask 里没有 task-started 超时兜底", "START_TIMEOUT_MS" in begin)
        val started = TestSources.blockAfter(client, "private fun startPump")
        assertTrue("task-started 到达才启动泵", "pumpHandler.postDelayed(pumpRunnable" in started)
        assertTrue("泵基准必须在 task-started 重置", "pumpStartMs = System.currentTimeMillis()" in started)
        // 收尾：冲余量 → finish-task → 等 task-finished
        val finish = TestSources.blockAfter(client, "private fun finishTask")
        assertTrue("收尾没冲队列余量", "flushQueue(current)" in finish)
        assertTrue("收尾没发 finish-task", "AliAsrProtocol.finishTaskMessage(id)" in finish)
        assertTrue("等 task-finished 的超时兜底丢了", "finishTimeoutRunnable" in finish)
        val finished = TestSources.blockAfter(client, "private fun onFinished")
        assertTrue("收了 task-finished 却没定稿", "isFinal = true" in finished)
        // 心跳跳过 + 定句/非定句都由累积器处理
        val sentence = TestSources.blockAfter(client, "private fun onSentence")
        assertTrue("心跳帧没跳过", "s.heartbeat" in sentence)
        // 失败分支必须带 error_code / error_message
        val failed = TestSources.blockAfter(client, "private fun onFailed")
        assertTrue("失败分支没取错误码", "event.errorCode" in failed)
        assertTrue("失败分支没取错误信息", "event.errorMessage" in failed)
        // 3200 字节 / 100ms 的节拍
        assertTrue("帧长不是 3200", "const val FRAME_BYTES = 3200" in client)
        assertTrue("节拍不是 100ms", "const val FRAME_MS = 100L" in client)
        // 凭据不进日志：只允许长度
        assertFalse(
            "日志里出现了 Key 原文",
            Regex("""\$\{[^}]*\bkey\b(?!\.length)""").containsMatchIn(client),
        )
        assertTrue("connect 日志只记 Key 长度", "keyLen=" in client)
    }

    @Test
    fun `工厂分发装配阿里实现`() {
        val factory = TestSources.codeSource("SpeechClients.kt")
        assertTrue("工厂缺少阿里实现", "AliAsrClient(prefs, onState, onMessage)" in factory)
        assertTrue("工厂没按后端分发", "when (prefs.voiceBackend)" in factory)
        // 统一工厂已覆盖「语音测试」，不必再在页面里 if/else
        val page = TestSources.codeSource("VoiceSettingsActivity.kt")
        assertTrue("语音测试没走工厂", "VoiceSelfTest(prefs)" in page)
    }

    @Test
    fun `后端取值归一含 ali 且算在线后端`() {
        assertEquals(VoiceBackend.ALI, VoiceBackend.of("ali"))
        assertEquals(VoiceBackend.ALI, VoiceBackend.of(VoiceBackend.ALI))
        assertTrue("阿里百炼是在线后端（单次音频有上限，要 58s 收尾）", VoiceBackend.isOnline(VoiceBackend.ALI))
        // 脏值仍旧回落自建
        assertEquals(VoiceBackend.CAPS, VoiceBackend.of("Ali"))
        assertEquals(VoiceBackend.CAPS, VoiceBackend.of("dashscope"))
    }

    @Test
    fun `一条连接只跑一个任务且取消也等 task-finished`() {
        val client = TestSources.codeSource("AliAsrClient.kt")
        // 阿里一条连接只承载一个任务：上一段的 task-finished 还没回来就又发 run-task 时，
        // 服务端按协议错处理，而且旧任务的 task-finished 会把新任务的泵 / 累积器 / 连接一起收掉
        assertTrue("缺少「本条连接已用过」标记", "private var sessionStarted = false" in client)
        val begin = TestSources.blockAfter(client, "override fun beginTask")
        assertTrue(
            "beginTask 没拦住已用过的连接（同一连接会发第二个 run-task）",
            "state != LinkState.ONLINE || sessionStarted" in begin,
        )
        assertTrue("发过 run-task 后没标记连接已用过", "sessionStarted = true" in begin)
        assertTrue(
            "新连接上线时没复位「已用过」",
            "sessionStarted = false" in TestSources.blockAfter(client, "override fun onOpen"),
        )
        // 取消路径把 acceptingTask 置空了，收尾等待必须另有判据；否则超时分支直接 return，
        // 连接与「在线」状态一直挂着
        assertTrue("缺少独立的收尾等待判据", "private var awaitingFinish = false" in client)
        assertTrue(
            "发完 finish-task 没进入收尾等待",
            "awaitingFinish = true" in TestSources.blockAfter(client, "private fun finishTask"),
        )
        assertTrue(
            "等 task-finished 的超时仍拿 acceptingTask 当判据",
            "if (!awaitingFinish) return" in TestSources.blockAfter(client, "private fun onFinishTimeout"),
        )
        assertTrue(
            "拆连接没有清掉收尾等待",
            "awaitingFinish = false" in TestSources.blockAfter(client, "private fun closeSocket"),
        )
        // 连接断开的两个回调（onFailure / onClosed）不经过 closeSocket：若那条路径不清，
        // 判据会留在「还在等 task-finished」而 socket 已是 null
        val clear = TestSources.blockAfter(client, "private fun clearTask")
        assertTrue("任务状态收尾没清「还在等 task-finished」", "awaitingFinish = false" in clear)
        // ⚠ 反向钉：`sessionStarted` 是**连接**状态，不许跟着任务一起复位 ——
        // 那样用过的连接又能跑第二个 run-task，正是本轮修掉的那个缺陷
        assertFalse(
            "clearTask 里复位 sessionStarted ⇒ 用过的连接会被复用（第二个 run-task 会把两段一起撞毁）",
            "sessionStarted = false" in clear,
        )
    }

    @Test
    fun `第四家后端在主界面两处标签里各有一支`() {
        val ime = TestSources.codeSource("JinnIme.kt")
        // renderLink 的 else 会拿**自建服务端**的 host:port 拼「已连接 …」：第四家落到那里，
        // 用户会看到「已连接 192.168.x.x:6016」而这台 NAS 此刻根本没参与
        assertTrue(
            "状态条在线标签没认阿里百炼（会报自建服务端的地址）",
            "VoiceBackend.ALI -> TEXT_ALI_ONLINE" in TestSources.blockAfter(ime, "private fun renderLink"),
        )
        // switchToVoiceKeyboard 的 else 同理（「自建服务端未连接（host:port）」）
        assertTrue(
            "离线提示没认阿里百炼（会报自建服务端的地址）",
            "VoiceBackend.ALI -> TEXT_ALI_OFFLINE" in
                TestSources.blockAfter(ime, "private fun switchToVoiceKeyboard"),
        )
        assertTrue("在线标签常量缺失", "const val TEXT_ALI_ONLINE" in ime)
        assertTrue("离线提示常量缺失", "const val TEXT_ALI_OFFLINE" in ime)
    }

    // ── Prefs / 备份 / 页面 ──────────────────────────────────────

    @Test
    fun `阿里 Key 走加密通道且三键进备份白名单`() {
        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("读侧没走加密通道", "get() = readCredential(KEY_ALI_API_KEY)" in prefs)
        assertTrue("写侧没走加密通道", "set(value) = writeCredential(KEY_ALI_API_KEY, value)" in prefs)
        assertTrue("后端没有 ali 取值", "const val ALI = \"ali\"" in prefs)
        assertTrue("默认端点被改了", "const val DEFAULT_ALI_WS_URL =" in prefs)
        assertTrue("默认模型被改了", "const val DEFAULT_ALI_MODEL = \"paraformer-realtime-v2\"" in prefs)

        val export = TestSources.blockAfter(prefs, "internal fun exportForBackup")
        assertTrue("Key 没进导出白名单", "putCredential(KEY_ALI_API_KEY)" in export)
        assertTrue("WS 地址没进导出白名单", "put(KEY_ALI_WS_URL, aliWsUrl)" in export)
        assertTrue("模型名没进导出白名单", "put(KEY_ALI_MODEL, aliModel)" in export)

        val import = TestSources.blockAfter(prefs, "internal fun importFromBackup")
        assertTrue("地址没进导入白名单", "KEY_ALI_WS_URL -> asString(v)" in import)
        assertTrue("模型名没进导入白名单", "KEY_ALI_MODEL -> asString(v)" in import)
        assertTrue(
            "Key 空串必须按忽略处理（否则会删掉本机可用凭据）",
            "KEY_ALI_API_KEY -> asString(v)?.takeIf { it.isNotEmpty() }" in import,
        )
        // 配置齐不齐也要认第四家（否则「语音测试」会拿自建那套判据）
        val configured = TestSources.blockAfter(prefs, "fun voiceConfigured()")
        assertTrue("voiceConfigured 没认阿里百炼", "VoiceBackend.ALI -> AliEndpoint.normalize(aliWsUrl).isSuccess" in configured)
    }

    @Test
    fun `页面 Key 掩码不被写回且只在该后端下写盘`() {
        val page = TestSources.codeSource("VoiceSettingsActivity.kt")
        val load = TestSources.blockAfter(page, "private fun loadPrefs")
        assertTrue("Key 回填没走掩码", "setCredentialText(editAliApiKey, prefs.aliApiKey)" in load)
        assertTrue("WS 地址没回填", "editAliWsUrl.setText(prefs.aliWsUrl)" in load)
        assertTrue("模型名没回填", "editAliModel.setText(prefs.aliModel)" in load)

        val save = TestSources.blockAfter(page, "private fun saveAndRestart")
        assertTrue("Key 有效取值没走 credentialText", "val aliKeyValue = credentialText(editAliApiKey)" in save)
        assertFalse(
            "保存直接写回了输入框文本（掩码会被写坏）",
            "prefs.aliApiKey = editAliApiKey.text.toString()" in save,
        )
        assertTrue("Key 没写回 Prefs", "prefs.aliApiKey = aliKeyValue" in save)
        assertTrue("地址没写回 Prefs", "prefs.aliWsUrl = aliWsUrlValue" in save)
        assertTrue("模型名没写回 Prefs", "prefs.aliModel = aliModelValue" in save)
        assertTrue("写盘必须只发生在 ali 分支", "if (backend == VoiceBackend.ALI) {" in save)
        // 校验：地址过策略、模型名与 Key 必填
        assertTrue("保存校验没走地址策略", "AliEndpoint.normalize(aliWsUrlValue)" in save)
        assertTrue("地址非法没有落到输入框", "editAliWsUrl.error = addressError" in save)
        for (field in listOf("editAliWsUrl", "editAliModel", "editAliApiKey")) {
            assertTrue("$field 缺少必填校验", "$field.error = TEXT_ALI_REQUIRED" in save)
        }
        // 秘密不进日志：只允许长度
        assertTrue("保存日志必须只记 Key 长度", "aliKeyLen=" in save)
        assertFalse(
            "日志里出现了 Key 原文",
            Regex("""\$\{prefs\.aliApiKey\}""").containsMatchIn(page),
        )
        // 显示 / 隐藏与凭据清单都要认这第三张凭据卡
        assertTrue("凭据框清单没算阿里 Key", "editAliApiKey" in TestSources.blockAfter(page, "private fun credentialFields"))
        assertTrue("显示勾选没算阿里卡", "checkAliShow.isChecked" in TestSources.lineOf(page, "private fun revealAll"))
        assertTrue("Key 没挂聚焦揭示", "wireCredentialMask(editAliApiKey)" in page)
    }

    @Test
    fun `语音测试前置校验点名阿里缺件并复用地址策略`() {
        assertEquals(
            "阿里云百炼配置不全，缺少 WS 地址 / API Key / 模型名",
            VoiceTestPreflight.check(VoiceBackend.ALI, "", "", "", "192.168.1.3"),
        )
        assertEquals(
            "阿里云百炼配置不全，缺少 API Key",
            VoiceTestPreflight.check(
                VoiceBackend.ALI, "", "", "", "192.168.1.3",
                aliWsUrl = Prefs.DEFAULT_ALI_WS_URL, aliApiKey = "", aliModel = "paraformer-realtime-v2",
            ),
        )
        assertNull(
            VoiceTestPreflight.check(
                VoiceBackend.ALI, "", "", "", "192.168.1.3",
                aliWsUrl = Prefs.DEFAULT_ALI_WS_URL, aliApiKey = "k", aliModel = "paraformer-realtime-v2",
            ),
        )
        assertEquals(
            "公网明文必须被前置校验拦下，且原因与页面保存时同一句",
            AliEndpoint.TEXT_PUBLIC_WS,
            VoiceTestPreflight.check(
                VoiceBackend.ALI, "", "", "", "192.168.1.3",
                aliWsUrl = "ws://dashscope.aliyuncs.com/api-ws/v1/inference",
                aliApiKey = "k", aliModel = "paraformer-realtime-v2",
            ),
        )
    }
}
