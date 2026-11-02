package com.jinn.inputmethod

import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenAI 兼容语音后端（[OpenAiAsrClient] 及其两侧）的守卫。
 *
 * 分两半：
 *  - **能真跑的纯函数**：WAV 头、multipart 字段 / 文件名、地址策略、响应解析、语言码、前置校验 ——
 *    这些错了真机上只表现为「服务端 400 / 识别出乱码」，日志里一点线索都没有；
 *  - **只能在源码上对拍的接线**：缓冲式上传、取消要打断在飞请求、凭据不进日志、工厂分发、
 *    掩码不被写回磁盘（客户端依赖 Handler / OkHttp，进不了 JVM 单测）。
 *
 * 反向钉一律走 [TestSources]（剥注释后再判）。
 */
class OpenAiAsrClientTest {

    private val client = TestSources.codeSource("OpenAiAsrClient.kt")

    /** 缓冲 / 上传 / 取消那一套已抽到基类（2026-11-02），对拍点在它身上 */
    private val base = TestSources.codeSource("BufferedBatchAsrClient.kt")

    // ── WAV ──────────────────────────────────────────────────────

    @Test
    fun `WAV 头逐字段是标准的 44 字节 RIFF`() {
        val pcm = ByteArray(320) { (it % 127).toByte() }
        val wav = WavWriter.wrap(pcm)
        assertEquals("长度必须是 44 字节头 + PCM", WavWriter.HEADER_BYTES + pcm.size, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))

        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF 块长 = 文件长 - 8", wav.size - 8, buf.getInt(4))
        assertEquals("fmt 块长固定 16", 16, buf.getInt(16))
        assertEquals("PCM 编码 = 1", 1, buf.getShort(20).toInt())
        assertEquals("单声道", 1, buf.getShort(22).toInt())
        assertEquals("16kHz", 16_000, buf.getInt(24))
        assertEquals("字节率 = 采样率 × 声道 × 2", 32_000, buf.getInt(28))
        assertEquals("块对齐 = 声道 × 2", 2, buf.getShort(32).toInt())
        assertEquals("16 bit", 16, buf.getShort(34).toInt())
        assertEquals("data 块长 = PCM 字节数", pcm.size, buf.getInt(40))
        // 采样数据必须原样跟在头后面（错位一个字节 = 整段音频变成噪声）
        assertEquals(pcm.toList(), wav.copyOfRange(WavWriter.HEADER_BYTES, wav.size).toList())

        // 空输入也要是合法的 44 字节头（服务端解析器不会因为长度为 0 而崩）
        val empty = WavWriter.wrap(ByteArray(0))
        assertEquals(WavWriter.HEADER_BYTES, empty.size)
        assertEquals(0, ByteBuffer.wrap(empty).order(ByteOrder.LITTLE_ENDIAN).getInt(40))

        // 采样率必须与采集侧同一个数：头里写 16k 而实际采 8k，服务端不会报错，只会给出一段听不懂的字
        assertEquals("WAV 头采样率与采集侧脱钩", Protocol.SAMPLE_RATE, WavWriter.SAMPLE_RATE)
    }

    // ── multipart ───────────────────────────────────────────────

    @Test
    fun `multipart 字段与文件名符合转写接口`() {
        val wav = WavWriter.wrap(ByteArray(32))
        val body = OpenAiRequest.multipart(wav, "whisper-1", "zh")
        val parts = body.parts
        assertEquals("model / response_format / file / language 四段", 4, parts.size)

        assertEquals("model 字段名不对", "model", partName(parts[0]))
        assertEquals("model 取值不对", "whisper-1", partText(parts[0]))
        assertEquals("response_format 字段名不对", "response_format", partName(parts[1]))
        assertEquals("必须要求 json（否则拿不到 text 字段）", "json", partText(parts[1]))
        assertEquals("file 字段名不对", "file", partName(parts[2]))
        val disposition = parts[2].headers?.get("Content-Disposition").orEmpty()
        assertTrue("文件名必须是 audio.wav：$disposition", disposition.contains("filename=\"audio.wav\""))
        assertEquals("WAV 必须按 audio/wav 上传", "audio/wav", parts[2].body.contentType().toString())
        assertEquals("language 字段名不对", "language", partName(parts[3]))
        assertEquals("zh 原样带", "zh", partText(parts[3]))

        // auto / 认不出的取值一律**不带** language（乱填会让服务商 400）
        assertEquals(3, OpenAiRequest.multipart(wav, "whisper-1", null).parts.size)
        assertNull(OpenAiRequest.languageOf("auto"))
        assertNull(OpenAiRequest.languageOf(""))
        assertNull(OpenAiRequest.languageOf("klingon"))
        assertEquals("zh", OpenAiRequest.languageOf("chinese"))
        assertEquals("en", OpenAiRequest.languageOf("english"))
        assertEquals("ja", OpenAiRequest.languageOf("japanese"))
        assertEquals("ko", OpenAiRequest.languageOf("korean"))

        // 请求头：Bearer + Key，且 URL 就是 base + 固定路径
        val request = OpenAiRequest.request(
            "https://api.groq.com/openai/v1" + OpenAiEndpoint.PATH,
            "sk-test",
            wav,
            "whisper-1",
            "zh",
        )
        assertEquals("Bearer sk-test", request.header(OpenAiRequest.HEADER_AUTH))
        assertEquals(
            "https://api.groq.com/openai/v1/audio/transcriptions",
            request.url.toString(),
        )

        // 线上形态（OkHttp 真正写出去的字节）：边界必须在 Content-Type 里，正文必须以
        // `--boundary--` 收尾。服务端靠这两条切段，缺一条就是「整包当一个字段」或
        // 「少最后一段」—— 只会换来一个没有解释的 400。
        val contentType = request.body?.contentType().toString()
        val sent = request.body as MultipartBody
        val boundary = sent.boundary
        assertTrue("请求必须声明 multipart/form-data：$contentType", contentType.startsWith("multipart/form-data"))
        assertTrue("Content-Type 里没有 boundary：$contentType", contentType.contains("boundary=$boundary"))
        val wire = okio.Buffer().also { sent.writeTo(it) }.readUtf8()
        assertTrue("正文必须以首边界开头", wire.startsWith("--$boundary\r\n"))
        assertTrue("正文必须以结束边界收尾（少它就是少最后一段）", wire.endsWith("--$boundary--\r\n"))
        assertTrue(
            "file 段头必须同时有 name 与 filename",
            wire.contains("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\""),
        )
        assertTrue("file 段必须声明 audio/wav", wire.contains("Content-Type: audio/wav"))
        assertTrue("file 段后面必须紧接 WAV 头", wire.contains("RIFF"))
    }

    // ── 地址策略 ─────────────────────────────────────────────────

    @Test
    fun `地址策略只放行 https 与本机局域网明文`() {
        // 1) https 一律通过，尾部斜杠去掉
        assertEquals(
            "https://api.groq.com/openai/v1",
            OpenAiEndpoint.normalize(" https://api.groq.com/openai/v1/ ").getOrThrow(),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1",
            OpenAiEndpoint.normalize("http://127.0.0.1:8080/v1").getOrThrow(),
        )
        // 2) http + 私网 / 回环 / mDNS 通过（与 CapsWriter 的 ws:// 局域网同一条理由）
        for (ok in listOf(
            "http://localhost:8080/v1",
            "http://10.1.2.3:6016",
            "http://192.168.1.9:6016/v1",
            "http://172.16.0.5:8080",
            "http://172.31.255.254:8080",
            "http://nas.local:8080/v1",
            "http://[::1]:8080/v1",
        )) {
            assertTrue("局域网明文应放行：$ok", OpenAiEndpoint.normalize(ok).isSuccess)
        }
        // 3) http + 公网：拒绝，且原因能直接显示给用户（要告诉他改成 https）
        assertEquals(
            OpenAiEndpoint.TEXT_PUBLIC_HTTP,
            OpenAiEndpoint.normalize("http://api.openai.com/v1").exceptionOrNull()?.message,
        )
        assertEquals(
            OpenAiEndpoint.TEXT_PUBLIC_HTTP,
            OpenAiEndpoint.normalize("http://172.32.0.1:8080").exceptionOrNull()?.message,
        )
        // 4) 其余（空 / 无 scheme / 缺主机）各自给一句明确的拒绝原因
        assertEquals(
            OpenAiEndpoint.TEXT_EMPTY,
            OpenAiEndpoint.normalize("   ").exceptionOrNull()?.message,
        )
        assertEquals(
            OpenAiEndpoint.TEXT_NO_SCHEME,
            OpenAiEndpoint.normalize("api.groq.com/openai/v1").exceptionOrNull()?.message,
        )
        assertEquals(
            OpenAiEndpoint.TEXT_NO_HOST,
            OpenAiEndpoint.normalize("https:///audio/transcriptions").exceptionOrNull()?.message,
        )
        assertEquals(
            OpenAiEndpoint.TEXT_BLANK,
            OpenAiEndpoint.normalize("https://api.groq.com/openai /v1").exceptionOrNull()?.message,
        )
        assertEquals(
            "https://a.example/v1/audio/transcriptions",
            OpenAiEndpoint.transcriptionsUrl("https://a.example/v1"),
        )

        // 5) scheme 大小写归一：`HTTPS://` 手写很常见，归一后下游才不必赌解析器的大小写容忍度
        assertEquals(
            "https://API.X.COM/v1",
            OpenAiEndpoint.normalize("HTTPS://API.X.COM/v1").getOrThrow(),
        )
        // 6) 回环伪装：`127.0.0.1.evil.com` / `localhost.evil.com` 是公网域名，不是回环
        for (fake in listOf(
            "http://127.0.0.1.evil.com/v1",
            "http://localhost.evil.com/v1",
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",
        )) {
            assertEquals(
                "回环/私网伪装必须按公网拒绝：$fake",
                OpenAiEndpoint.TEXT_PUBLIC_HTTP,
                OpenAiEndpoint.normalize(fake).exceptionOrNull()?.message,
            )
        }
        // 7) 用户名密码 / 查询串 / 锚点：`@` 会随地址进日志，`?` `#` 会把固定路径吃成别的语义
        for (bad in listOf(
            "https://user:pass@host/v1",
            "http://user:pass@127.0.0.1/v1",
            "https://api.x.com/v1?key=1",
            "https://api.x.com/v1#frag",
        )) {
            assertEquals(
                "必须拒绝：$bad",
                OpenAiEndpoint.TEXT_BAD_CHARS,
                OpenAiEndpoint.normalize(bad).exceptionOrNull()?.message,
            )
        }
        // 8) 无路径的 base 拼出来不能是双斜杠
        assertEquals(
            "http://127.0.0.1/audio/transcriptions",
            OpenAiEndpoint.transcriptionsUrl(OpenAiEndpoint.normalize("http://127.0.0.1/").getOrThrow()),
        )
        // 9) `@` 只在该拒的地方拒：authority 里是 user:pass（凭据会进日志 + 解析器取值不一）⇒ 拒；
        //    路径里的 `@`（Cloudflare 这类 `/ai/run/@cf/…`）不带凭据、也不参与取主机 ⇒ 放行。
        //    一刀切拒掉路径 `@` 的后果不是「更安全」，是给用户一句「删掉 @」的错误处方。
        val pathAt = "https://api.cloudflare.com/client/v4/accounts/9/ai/run/@cf/openai/whisper"
        assertEquals(pathAt, OpenAiEndpoint.normalize(pathAt).getOrThrow())
        assertEquals(
            "authority 里的 @ 必须拒（userinfo 会随 endpoint 进日志）",
            OpenAiEndpoint.TEXT_BAD_CHARS,
            OpenAiEndpoint.normalize("https://key:secret@api.x.com/v1").exceptionOrNull()?.message,
        )
        assertEquals(
            "authority 里的 @ 必须拒（混杂写会让地址策略失效）",
            OpenAiEndpoint.TEXT_BAD_CHARS,
            OpenAiEndpoint.normalize("https://evil.com\\@127.0.0.1/v1").exceptionOrNull()?.message,
        )
    }

    // ── 响应解析 ─────────────────────────────────────────────────

    @Test
    fun `响应解析取 text 且错误带状态码与 message`() {
        assertEquals("今天天气", OpenAiResponse.parse(200, """{"text":"今天天气"}""").getOrThrow())
        // 空文本是**成功**（链路通、只是没听到声音），不能当失败
        assertEquals("", OpenAiResponse.parse(200, """{"text":""}""").getOrThrow())
        assertEquals(
            OpenAiResponse.TEXT_NO_TEXT,
            OpenAiResponse.parse(200, "{}").exceptionOrNull()?.message,
        )
        assertEquals(
            "JSON null 同样算「没有 text」，不能把字符串 \"null\" 落进输入框",
            OpenAiResponse.TEXT_NO_TEXT,
            OpenAiResponse.parse(200, """{"text":null}""").exceptionOrNull()?.message,
        )
        // 非 2xx：状态码 + error.message（鉴权失败 / 模型不存在 / 余额不足全靠它区分）
        assertEquals(
            "HTTP 401 invalid api key",
            OpenAiResponse.parse(401, """{"error":{"message":"invalid api key"}}""")
                .exceptionOrNull()?.message,
        )
        assertEquals(
            "HTTP 400 model not found",
            OpenAiResponse.parse(400, """{"error":{"message":"model not found"}}""")
                .exceptionOrNull()?.message,
        )
        // message 缺失时退回报文体（截断），至少能看见服务端说了什么
        assertTrue(
            OpenAiResponse.parse(502, "bad gateway").exceptionOrNull()?.message!!.startsWith("HTTP 502"),
        )
        assertEquals("HTTP 500", OpenAiResponse.parse(500, "").exceptionOrNull()?.message)
    }

    // ── 客户端接线 ───────────────────────────────────────────────

    @Test
    fun `音频只入队且收尾才一次性上传`() {
        val send = TestSources.blockAfter(base, "override fun sendChunk")
        assertTrue("sendChunk 必须做 float32→PCM16 转换", "XfIatProtocol.float32ToPcm16(" in send)
        assertTrue("sendChunk 只入队", "pcm.write(" in send)
        assertFalse("sendChunk 不许发起请求（松手前上传等于把录音拆段）", "execute" in send)

        val end = TestSources.blockAfter(base, "override fun endTask")
        assertTrue("endTask 必须走统一上传入口", "submitUpload(" in end)
        assertTrue("endTask 没有音频时不许上传（空 WAV 只会换来 400）", "data.isEmpty()" in end)
        val submit = TestSources.blockAfter(base, "private fun submitUpload")
        assertTrue("上传前必须组 WAV", "WavWriter.wrap(" in submit)
        assertTrue("上传必须在后台线程池里跑", "uploads.execute" in submit)
        assertTrue("回调必须切回主线程", "main.post" in submit)
        assertTrue("成功必须发 isFinal 结果", "isFinal = true" in submit)
        // 在飞请求由基类的 exchange 登记（取消 / 关闭都靠它打断）
        assertTrue("上传要能被取消（OkHttp Call.cancel）", "http.newCall(request)" in TestSources.blockAfter(base, "protected fun exchange"))

        val connect = TestSources.blockAfter(base, "override fun connect")
        assertTrue("配置齐全才算 ONLINE", "updateState(LinkState.ONLINE, null)" in connect)
        assertFalse("connect 里不许发请求", "newCall(" in connect)
        // 各家后端的「配置齐不齐」在 prepare 里（connect 只调它并落状态条）
        val prep = TestSources.blockAfter(client, "override fun prepare")
        assertTrue("connect 只校验配置，不建长连接", "OpenAiEndpoint.normalize(" in prep)
        assertTrue("Key 未配置必须当场落到状态条", "TEXT_NEED_KEY" in prep)
        assertTrue("模型名为空必须当场落到状态条", "TEXT_NEED_MODEL" in prep)
    }

    @Test
    fun `取消与关闭都必须打断在飞请求`() {
        val cancel = TestSources.blockAfter(base, "override fun cancelTask")
        assertTrue("cancelTask 必须丢弃缓冲", "pcm.reset()" in cancel)
        assertTrue("cancelTask 必须取消在飞请求", "inFlight?.cancel()" in cancel)
        assertTrue(
            "必须先关采纳闸门再 cancel（回包与 cancel 有竞态）",
            cancel.indexOf("acceptingTask = null") in 0 until cancel.indexOf("inFlight?.cancel()"),
        )
        val close = TestSources.blockAfter(base, "override fun close")
        assertTrue("close 必须取消在飞请求", "inFlight?.cancel()" in close)
        assertTrue("close 必须置 IDLE", "updateState(LinkState.IDLE, null)" in close)
        // endTask 之后（taskId 已空、上传排队 / 在飞）取消也要生效：只判 taskId 与 call 会退回 false，
        // 于是采纳闸门不关、结果照常落字
        assertTrue(
            "cancelTask 的早退判据漏了 acceptingTask（松手后再取消会被当成「没活可取消」）",
            "acceptingTask == null) return false" in cancel,
        )
        // 后台线程里也要按「任务还是不是我们那个」丢弃晚到结果
        val submit = TestSources.blockAfter(base, "private fun submitUpload")
        assertTrue("上传完成前必须复核任务归属", "acceptingTask != id" in submit)
    }

    @Test
    fun `失败原因带状态码且凭据绝不进日志或异常文本`() {
        // 凭据只允许以「长度」形式出现在日志里
        assertTrue("connect 日志只记 Key 长度", "keyLen=" in client)
        for (name in listOf("apiKey", "key")) {
            assertFalse(
                "凭据变量被插进字符串（多半是日志）：$name",
                Regex("""\$\{[^}]*\b$name\b(?!\.length)""").containsMatchIn(client),
            )
        }
        assertFalse("Authorization 头不许整条落日志", "\${OpenAiRequest.HEADER_AUTH}" in client)
        // 只许在请求头里拼 Bearer
        assertTrue("Authorization 只在请求组装处构造", "AUTH_SCHEME + apiKey" in client)
        assertTrue(
            "失败分支必须带状态码与服务端 message（见 OpenAiResponse）",
            "OpenAiResponse.parse(" in client,
        )
        // 兜底文案沿用抽基类之前那句「转写失败」：基类默认是「识别失败」，重构不许顺手改措辞
        assertTrue(
            "兜底失败文案没覆盖基类默认值（用户看到的会从「转写失败」变成「识别失败」）",
            "override val textFailed: String get() = TEXT_FAILED" in client,
        )
        assertTrue("TEXT_FAILED 不是「转写失败」", "const val TEXT_FAILED = \"转写失败\"" in client)
    }

    @Test
    fun `工厂按后端分发且两处都不再各写一份 if-else`() {
        val factory = TestSources.codeSource("SpeechClients.kt")
        assertTrue("工厂必须按 voiceBackend 分发", "when (prefs.voiceBackend)" in factory)
        for (impl in listOf(
            "XfIatClient(prefs, onState, onMessage)",
            "OpenAiAsrClient(prefs, onState, onMessage)",
            "AsrClient(prefs, onState, onMessage)",
        )) {
            assertTrue("工厂缺少实现：$impl", impl in factory)
        }
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue("ensureVoiceReady 没走工厂", "SpeechClients.create(" in ime)
        assertFalse("JinnIme 里还有后端 if/else 分叉", "if (backend == VoiceBackend.XF)" in ime)
        val selfTest = TestSources.codeSource("VoiceSelfTest.kt")
        assertTrue("语音测试没走工厂", "SpeechClients.create(prefs, ::onState, ::onMessage)" in selfTest)
        assertFalse("语音测试里还有后端 if/else 分叉", "XfIatClient(prefs, ::onState" in selfTest)
    }

    // ── Prefs / 页面 ─────────────────────────────────────────────

    @Test
    fun `OpenAI 的 Key 走加密通道且空串导入不删本机凭据`() {
        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("读侧没走加密通道", "get() = readCredential(KEY_OAI_API_KEY)" in prefs)
        assertTrue("写侧没走加密通道", "set(value) = writeCredential(KEY_OAI_API_KEY, value)" in prefs)
        assertTrue("后端没有 oai 取值", "const val OAI = \"oai\"" in prefs)
        val export = TestSources.blockAfter(prefs, "internal fun exportForBackup")
        assertTrue("Key 没进导出白名单", "putCredential(KEY_OAI_API_KEY)" in export)
        assertTrue("地址没进导出白名单", "put(KEY_OAI_BASE_URL, oaiBaseUrl)" in export)
        val import = TestSources.blockAfter(prefs, "internal fun importFromBackup")
        assertTrue(
            "Key 空串必须按忽略处理（否则会删掉本机可用凭据）",
            "KEY_OAI_API_KEY -> asString(v)?.takeIf { it.isNotEmpty() }" in import,
        )
        assertTrue("地址没进导入白名单", "KEY_OAI_BASE_URL -> asString(v)" in import)
        assertTrue("模型名没进导入白名单", "KEY_OAI_MODEL -> asString(v)" in import)
    }

    @Test
    fun `页面 Key 掩码不被写回且只在该后端下写盘`() {
        val page = TestSources.codeSource("VoiceSettingsActivity.kt")
        val load = TestSources.blockAfter(page, "private fun loadPrefs")
        assertTrue("Key 回填没走掩码", "setCredentialText(editOaiApiKey, prefs.oaiApiKey)" in load)
        assertTrue("地址没回填", "editOaiBase.setText(prefs.oaiBaseUrl)" in load)
        assertTrue("模型名没回填", "editOaiModel.setText(prefs.oaiModel)" in load)

        val save = TestSources.blockAfter(page, "private fun saveAndRestart")
        assertTrue("Key 有效取值没走 credentialText", "val oaiKeyValue = credentialText(editOaiApiKey)" in save)
        assertFalse(
            "保存直接写回了输入框文本（掩码会被写坏）",
            "prefs.oaiApiKey = editOaiApiKey.text.toString()" in save,
        )
        assertTrue("Key 没写回 Prefs", "prefs.oaiApiKey = oaiKeyValue" in save)
        assertTrue("地址没写回 Prefs", "prefs.oaiBaseUrl = oaiBase" in save)
        assertTrue("模型名没写回 Prefs", "prefs.oaiModel = oaiModelValue" in save)
        // 只在该后端下写：另两张卡的字段此刻是隐藏的旧值，写回等于覆盖
        assertTrue("写盘必须只发生在 oai 分支", "if (backend == VoiceBackend.OAI) {" in save)

        // 地址策略的拒绝原因必须落到 edit_oai_base.error（用户要看到「为什么不能用」）
        assertTrue("地址非法没有落到输入框", "editOaiBase.error = addressError" in save)
        assertTrue("保存校验没走地址策略", "OpenAiEndpoint.normalize(oaiBase)" in save)
        for (field in listOf("editOaiBase", "editOaiModel", "editOaiApiKey")) {
            assertTrue("$field 缺少必填校验", "$field.error = TEXT_OAI_REQUIRED" in save)
        }
        // 秘密不进日志：只允许长度
        assertTrue("保存日志必须只记 Key 长度", "oaiKeyLen=" in save)
        assertFalse(
            "日志里出现了 Key 原文",
            Regex("""\$\{prefs\.oaiApiKey\}""").containsMatchIn(page),
        )
    }

    @Test
    fun `语音测试前置校验点名 OpenAI 缺件并复用地址策略`() {
        assertEquals(
            "OpenAI 兼容配置不全，缺少 Base URL / API Key / 模型名",
            VoiceTestPreflight.check(VoiceBackend.OAI, "", "", "", "192.168.1.3"),
        )
        assertEquals(
            "OpenAI 兼容配置不全，缺少 API Key",
            VoiceTestPreflight.check(
                VoiceBackend.OAI, "", "", "", "192.168.1.3",
                oaiBaseUrl = "https://api.groq.com/openai/v1", oaiApiKey = "", oaiModel = "whisper-1",
            ),
        )
        assertNull(
            VoiceTestPreflight.check(
                VoiceBackend.OAI, "", "", "", "192.168.1.3",
                oaiBaseUrl = "http://127.0.0.1:8080/v1", oaiApiKey = "k", oaiModel = "whisper-1",
            ),
        )
        assertEquals(
            "公网明文必须被前置校验拦下，且原因与页面保存时同一句",
            OpenAiEndpoint.TEXT_PUBLIC_HTTP,
            VoiceTestPreflight.check(
                VoiceBackend.OAI, "", "", "", "192.168.1.3",
                oaiBaseUrl = "http://api.openai.com/v1", oaiApiKey = "k", oaiModel = "whisper-1",
            ),
        )
    }

    // ── 小工具 ───────────────────────────────────────────────────

    private fun ascii(bytes: ByteArray, from: Int, length: Int): String =
        String(bytes, from, length, Charsets.US_ASCII)

    private fun partName(part: MultipartBody.Part): String =
        Regex("""name="([^"]+)"""").find(part.headers?.get("Content-Disposition").orEmpty())
            ?.groupValues?.get(1)
            ?: error("part 没有 name")

    private fun partText(part: MultipartBody.Part): String {
        val buffer = okio.Buffer()
        part.body.writeTo(buffer)
        return buffer.readUtf8()
    }
}
