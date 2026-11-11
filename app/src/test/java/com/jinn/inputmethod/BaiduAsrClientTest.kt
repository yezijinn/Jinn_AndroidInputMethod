package com.jinn.inputmethod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 百度智能云短语音识别（[BaiduAsrClient] 及其两侧）的守卫。
 *
 * 纯函数部分能真跑：请求体（`len` 是**未编码**字节数、base64 不带换行、dev_pid / format / rate / channel）、
 * token 缓存三态（未过期不重取 / 过期重取 / 3302 重试**一次**）、返回码映射、token 回包的
 * `error` + `error_description`；接线部分按源码对拍：工厂八分支、备份白名单、页面掩码与写盘分支。
 */
class BaiduAsrClientTest {

    // ── 请求体 ───────────────────────────────────────────────────

    @Test
    fun `请求体的 len 是未编码字节数且 base64 不带换行`() {
        // 3200 字节 = 16k/单声道 100ms：不是 3 的整数倍，base64 会带一个 `=` 填充，
        // 正好能验证「len 不等于 base64 长度」这件事不是巧合
        val pcm = ByteArray(3200) { (it % 128).toByte() }
        val json = JSONObject(BaiduAsrRequest.body(pcm, "token-abc", 1537))

        assertEquals("音频形态必须是裸 pcm（无 WAV 头）", "pcm", json.getString("format"))
        assertEquals("采样率必须与录音口径一致", 16_000, json.getInt("rate"))
        assertEquals("必须单声道", 1, json.getInt("channel"))
        assertEquals("dev_pid 必须原样进请求体", 1537, json.getInt("dev_pid"))
        assertEquals("len 必须是原始字节数", pcm.size, json.getInt("len"))
        assertEquals("lenOf 与请求体口径必须同源", pcm.size, BaiduAsrRequest.lenOf(pcm))
        assertEquals("token 必须原样进请求体", "token-abc", json.getString("token"))
        assertEquals("cuid 必须有值（官方必填）", BaiduAsrRequest.DEFAULT_CUID, json.getString("cuid"))
        assertTrue("cuid 不许是设备标识（IMEI / MAC / 机型）", BaiduAsrRequest.DEFAULT_CUID.startsWith("jinn-ime"))

        val speech = json.getString("speech")
        assertFalse("base64 里出现换行 ⇒ 服务端判非法", speech.contains("\n"))
        assertFalse("base64 里出现回车 ⇒ 服务端判非法", speech.contains("\r"))
        // 最关键的一条：把 base64 长度填进 len 是隐蔽缺陷（短音频看不出，长音频变成 3309）
        assertNotEquals("len 不许是 base64 长度", speech.length, json.getInt("len"))
        assertTrue("speech 必须能解回原始字节", pcm.contentEquals(Base64.getDecoder().decode(speech)))
        assertEquals("基本编码器不带 MIME 折行（4/3 倍率 + 填充）", (pcm.size + 2) / 3 * 4, speech.length)
    }

    @Test
    fun `token 地址的两个凭据都必须转义`() {
        // AK/SK 是 base64 形态时 `+` / `/` / `=` 很常见：不转义会被当空格或分隔符
        assertEquals(
            "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials" +
                "&client_id=ak%2F1&client_secret=sk%2B2",
            BaiduAsrRequest.tokenUrl("ak/1", "sk+2"),
        )
        val tokenRequest = BaiduAsrRequest.tokenRequest("ak", "sk")
        assertEquals("POST", tokenRequest.method)
        assertTrue("token 请求不许是 GET（凭据留在 URL 之外）", tokenRequest.url.toString().startsWith("https://"))
        assertTrue("token 请求必须打到官方鉴权端点", tokenRequest.url.toString().contains("aip.baidubce.com/oauth/2.0/token"))
        assertEquals("空体 POST", 0L, tokenRequest.body!!.contentLength())
    }

    @Test
    fun `识别请求走 https 的 vop 端点且是 JSON`() {
        val request = BaiduAsrRequest.recognizeRequest("""{"err_no":0}""")
        assertEquals(
            "必须用 https 的 vop 端点",
            "https://vop.baidu.com/server_api",
            request.url.toString(),
        )
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals("application/json", request.body!!.contentType().toString())
    }

    // ── token 缓存 ───────────────────────────────────────────────

    @Test
    fun `token 未过期直接用缓存不重取`() {
        var writes = 0
        val cache = BaiduTokenCache(
            readToken = { "tk-1" },
            writeToken = { writes++ },
            readExpireAt = { 2_000L },
            writeExpireAt = { writes++ },
            nowSec = { 1_000L },
        )
        assertEquals("缓存有效期内必须直接复用（每次识别都取一次 token 会白烧配额）", "tk-1", cache.fresh())
        assertEquals("命中缓存不许写盘", 0, writes)
    }

    @Test
    fun `token 过期或快到点都要重取`() {
        fun cacheOf(expireAt: Long, now: Long) = BaiduTokenCache(
            readToken = { "tk-1" },
            writeToken = { },
            readExpireAt = { expireAt },
            writeExpireAt = { },
            nowSec = { now },
        )
        // 已过期
        assertNull(cacheOf(expireAt = 1_000L, now = 1_000L).fresh())
        // 还差正好 5 分钟到期：按「提前 5 分钟过期」必须重取（上传途中被踢掉就是一次白说）
        assertEquals(
            BaiduTokenPolicy.EARLY_EXPIRE_SEC,
            300L,
        )
        assertNull("提前量边界必须算过期", cacheOf(expireAt = 1_300L, now = 1_000L).fresh())
        assertEquals("超出提前量一秒即可用", "tk-1", cacheOf(expireAt = 1_301L, now = 1_000L).fresh())
        // 过期时刻未知（0）一律当过期：旧版本 / 手改 prefs 留下的 token 不能当有效
        assertNull(cacheOf(expireAt = 0L, now = 1_000L).fresh())
        // 空 token 也是「没有」
        assertNull(
            BaiduTokenCache(
                readToken = { "  " },
                writeToken = { },
                readExpireAt = { 9_999_999L },
                writeExpireAt = { },
                nowSec = { 1_000L },
            ).fresh(),
        )
    }

    @Test
    fun `取到 token 后落盘的是真实过期时刻`() {
        var savedToken = ""
        var savedExpire = -1L
        val cache = BaiduTokenCache(
            readToken = { "" },
            writeToken = { savedToken = it },
            readExpireAt = { 0L },
            writeExpireAt = { savedExpire = it },
            nowSec = { 1_000L },
        )
        cache.save("tk-new", 2_592_000L)
        assertEquals("tk-new", savedToken)
        assertEquals("落盘的是 now + expires_in（提前量在判定侧算）", 1_000L + 2_592_000L, savedExpire)
    }

    // ── 重试 ─────────────────────────────────────────────────────

    @Test
    fun `3302 会重取 token 并重试一次`() {
        val ensures = ArrayList<Boolean>()
        var attempts = 0
        val outcome = BaiduRecognizeFlow.run(
            ensureToken = { force ->
                ensures += force
                BaiduToken.Ready(if (force) "tk-2" else "tk-1", 3_600L)
            },
            attempt = { token ->
                attempts++
                if (token == "tk-1") {
                    BaiduReply(BaiduErr.TOKEN_INVALID, BatchOutcome.Fail("3302: 鉴权失败"))
                } else {
                    BaiduReply(BaiduErr.OK, BatchOutcome.Ok("今天天气不错"))
                }
            },
        )
        assertEquals("必须先吃缓存、失效后才强制重取", listOf(false, true), ensures)
        assertEquals("只重试一次（多试几次会把配额烧光还让用户干等）", 2, attempts)
        assertEquals("今天天气不错", (outcome as BatchOutcome.Ok).text)
    }

    @Test
    fun `重试一次仍失败就收手不死循环`() {
        val ensures = ArrayList<Boolean>()
        var attempts = 0
        val outcome = BaiduRecognizeFlow.run(
            ensureToken = { force ->
                ensures += force
                BaiduToken.Ready("tk", 3_600L)
            },
            attempt = {
                attempts++
                BaiduReply(BaiduErr.TOKEN_INVALID, BatchOutcome.Fail("3302: 鉴权失败"))
            },
        )
        assertEquals(2, attempts)
        assertEquals("只允许一次强制重取", listOf(false, true), ensures)
        assertTrue("第二次的失败原因必须原样交出去", (outcome as BatchOutcome.Fail).reason.contains("3302"))
    }

    @Test
    fun `非 token 类错误不重取也不重试`() {
        var ensures = 0
        var attempts = 0
        val outcome = BaiduRecognizeFlow.run(
            ensureToken = { ensures++; BaiduToken.Ready("tk", 3_600L) },
            attempt = { attempts++; BaiduReply(3309, BatchOutcome.Fail("3309: 音频数据有问题")) },
        )
        assertEquals("音频本身有问题时重取 token 毫无意义", 1, ensures)
        assertEquals(1, attempts)
        assertTrue((outcome as BatchOutcome.Fail).reason.contains("音频数据"))
        // 110 / 111 是「access_token 无效 / 已过期」，同属 token 类
        assertTrue(BaiduErr.isTokenInvalid(110))
        assertTrue(BaiduErr.isTokenInvalid(111))
        assertTrue(BaiduErr.isTokenInvalid(3302))
        assertFalse(BaiduErr.isTokenInvalid(BaiduErr.OK))
        assertFalse("3307 是识别出错，换 token 也不会好", BaiduErr.isTokenInvalid(3307))
    }

    @Test
    fun `取不到 token 时直接用那句原因收场`() {
        var attempts = 0
        val outcome = BaiduRecognizeFlow.run(
            ensureToken = { BaiduToken.Failed(BatchOutcome.Fail("token 获取失败：invalid_client（unknown client id）")) },
            attempt = { attempts++; BaiduReply(BaiduErr.OK, BatchOutcome.Ok("不该到这里")) },
        )
        assertEquals("没 token 就不许发识别请求", 0, attempts)
        val reason = (outcome as BatchOutcome.Fail).reason
        assertTrue("必须原样带出 invalid_client：$reason", "invalid_client" in reason)
        assertTrue("必须原样带出 unknown client id：$reason", "unknown client id" in reason)
    }

    // ── 回包 ─────────────────────────────────────────────────────

    @Test
    fun `识别回包 err_no 为 0 取 result 首项`() {
        val ok = BaiduResponse.parse(
            200,
            """{"err_no":0,"err_msg":"success.","sn":"1","result":["今天天气不错"]}""",
        )
        assertEquals(BaiduErr.OK, ok.errNo)
        assertEquals("今天天气不错", (ok.outcome as BatchOutcome.Ok).text)
        // 成功但没听到声音：空文本也是 Ok（链路通、只是没声音）
        val empty = BaiduResponse.parse(200, """{"err_no":0,"err_msg":"success."}""")
        assertEquals("", (empty.outcome as BatchOutcome.Ok).text)
    }

    @Test
    fun `识别回包的 err_no 映射到可读原因`() {
        fun reason(errNo: Int, msg: String = "err"): String {
            val reply = BaiduResponse.parse(200, """{"err_no":$errNo,"err_msg":"$msg"}""")
            assertEquals("err_no 必须原样带出来（重试判据靠它）", errNo, reply.errNo)
            return (reply.outcome as BatchOutcome.Fail).reason
        }
        // 每条都要：返回码 + 用户能照着改的那句话
        assertTrue("3307 是识别出错：${reason(3307)}", "3307" in reason(3307) && "识别出错" in reason(3307))
        assertTrue("3308 是音频过长：${reason(3308)}", "3308" in reason(3308) && "过长" in reason(3308))
        assertTrue("3309 是音频数据问题：${reason(3309)}", "3309" in reason(3309) && "音频数据" in reason(3309))
        assertTrue("3311 是采样率：${reason(3311)}", "3311" in reason(3311) && "采样率" in reason(3311))
        assertTrue("3312 是格式：${reason(3312)}", "3312" in reason(3312) && "格式" in reason(3312))
        assertTrue("3314 是过短：${reason(3314)}", "3314" in reason(3314) && "过短" in reason(3314))
        assertTrue("3302 是鉴权：${reason(3302)}", "3302" in reason(3302) && "token" in reason(3302))
        assertTrue(
            "认不出的返回码也要原样带出 err_msg",
            "9999" in reason(9999, "unknown failure") && "unknown failure" in reason(9999, "unknown failure"),
        )
        assertTrue("服务端原文必须保留（排障与文档对得上）", "speech quality error." in reason(3301, "speech quality error."))
    }

    @Test
    fun `回包不是 JSON 时兜底状态码`() {
        val fail = BaiduResponse.parse(502, "<html>bad gateway</html>").outcome as BatchOutcome.Fail
        assertTrue("必须带状态码：${fail.reason}", fail.reason.startsWith("HTTP 502"))
        val empty = BaiduResponse.parse(500, null).outcome as BatchOutcome.Fail
        assertEquals("HTTP 500", empty.reason)
    }

    @Test
    fun `token 回包解析与失败原因`() {
        val ok = BaiduTokenResponse.parse(200, """{"access_token":"24.abc","expires_in":2592000}""")
        assertTrue(ok is BaiduToken.Ready)
        assertEquals("24.abc", (ok as BaiduToken.Ready).value)
        assertEquals(2_592_000L, ok.expiresInSec)
        // 失败是 HTTP 200 + error 块：只报「获取 token 失败」的话用户不知道该改哪一件凭据
        val bad = BaiduTokenResponse.parse(
            200,
            """{"error":"invalid_client","error_description":"unknown client id"}""",
        )
        val reason = (bad as BaiduToken.Failed).outcome.reason
        assertTrue("必须带 error：$reason", "invalid_client" in reason)
        assertTrue("必须带 error_description：$reason", "unknown client id" in reason)
        // 没有 access_token 也没有 error：如实说清是回包不对
        val noToken = BaiduTokenResponse.parse(200, """{"scope":"audio_voice_assistant_get"}""")
        assertEquals(BaiduTokenResponse.TEXT_NO_TOKEN, (noToken as BaiduToken.Failed).outcome.reason)
        // 不是 JSON（网关 HTML / 空体）：状态码是唯一线索
        val garbage = BaiduTokenResponse.parse(403, "")
        assertTrue((garbage as BaiduToken.Failed).outcome.reason.startsWith("token 获取失败：HTTP 403"))
    }

    // ── Prefs / 工厂 / 备份 / 状态条 / 页面 ───────────────────────

    @Test
    fun `百度读的是语音那两件凭据而不是翻译那把`() {
        // 翻译页的百度 AppID 与本页的 AK 是两个键、两套配额：读错必然 invalid_client，
        // 而「页面上填了凭据却认不出」在真机上只表现为鉴权失败。
        val src = TestSources.codeSource("BaiduAsrClient.kt")
        val prep = TestSources.blockAfter(src, "override fun prepare")
        assertTrue("AK 必须取自 prefs.baiduSpeechApiKey", "prefs.baiduSpeechApiKey" in prep)
        assertTrue("SK 必须取自 prefs.baiduSpeechSecretKey", "prefs.baiduSpeechSecretKey" in prep)
        assertTrue("dev_pid 必须取自 prefs.baiduDevPid", "prefs.baiduDevPid" in prep)
        assertFalse("不许读翻译的 baiduAppId / baiduSecretKey（同名不同键）", "prefs.baiduAppId" in src)
        // token 缓存必须走加密通道的属性（不是裸 prefs 字符串）
        assertTrue("token 必须走加密属性", "prefs.baiduToken" in src)
        assertTrue("token 过期时刻必须落盘", "prefs.baiduTokenExpireAt" in src)
        // 秘密不进日志：只允许长度
        assertTrue("日志必须只记长度", "keyLen=" in prep && "secretLen=" in prep)
        assertFalse(
            "日志里出现了凭据原文",
            Regex("""\$\{prefs\.baidu(?:ApiKey|SecretKey)\}""").containsMatchIn(src),
        )
    }

    @Test
    fun `百度三键走加密落盘且 dev_pid 有默认值`() {
        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("后端没有 baidu 取值", "const val BAIDU = \"baidu\"" in prefs)
        assertEquals(VoiceBackend.BAIDU, VoiceBackend.of("baidu"))
        assertTrue("百度是在线后端（单次音频有上限，要 58s 收尾）", VoiceBackend.isOnline(VoiceBackend.BAIDU))
        assertTrue("AK 读侧没走加密通道", "get() = readCredential(KEY_BAIDU_ASR_API_KEY)" in prefs)
        // 写侧入口从 writeCredential 换成了 writeBaiduSpeechCredential（值变了要连带作废 token），
        // 加密落盘仍在它里面 —— 判据改成「走那个入口 + 那个入口真的调 writeCredential」，
        // 而不是钉死调用形态（钉形态会把「加一道作废」判成「没走加密通道」）
        assertTrue(
            "AK 写侧没走「比较 + 作废 token + 加密落盘」的入口",
            "set(value) = writeBaiduSpeechCredential(KEY_BAIDU_ASR_API_KEY, value)" in prefs,
        )
        assertTrue("SK 读侧没走加密通道", "get() = readCredential(KEY_BAIDU_ASR_SECRET_KEY)" in prefs)
        assertTrue(
            "SK 写侧没走「比较 + 作废 token + 加密落盘」的入口",
            "set(value) = writeBaiduSpeechCredential(KEY_BAIDU_ASR_SECRET_KEY, value)" in prefs,
        )
        assertTrue(
            "写入口没有加密落盘（凭据会被写成明文）",
            "writeCredential(key, value)" in
                TestSources.blockAfter(prefs, "private fun writeBaiduSpeechCredential"),
        )
        assertTrue("token 也必须走加密通道", "get() = readCredential(KEY_BAIDU_TOKEN)" in prefs)
        assertEquals("dev_pid 默认 1537（普通话）", 1537, Prefs.DEFAULT_BAIDU_DEV_PID)
        assertTrue(
            "dev_pid 读侧必须走 intOr（脏配置不许带着字符串去发请求）",
            "intOr(KEY_BAIDU_DEV_PID, DEFAULT_BAIDU_DEV_PID)" in prefs,
        )
        // 与翻译那把必须**不是同一个键**（两家服务、两套配额）
        assertTrue(
            "百度语音 AK 与翻译 AppID 用了同一个键",
            "\"baidu_speech_api_key\"" in prefs && "\"baidu_app_id\"" in prefs,
        )
        assertTrue(
            "百度语音 SK 与翻译密钥用了同一个键",
            "\"baidu_speech_secret_key\"" in prefs && "\"baidu_secret_key\"" in prefs,
        )
    }

    @Test
    fun `工厂八分支齐全且备份白名单含百度键`() {
        val factory = TestSources.codeSource("SpeechClients.kt")
        for (impl in listOf(
            "VoiceBackend.XF -> XfIatClient(prefs, onState, onMessage)",
            "VoiceBackend.OAI -> OpenAiAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.ALI -> AliAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.TENCENT -> TencentAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.AZURE -> AzureAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.BAIDU -> BaiduAsrClient(prefs, onState, onMessage)",
            "VoiceBackend.DEEPGRAM -> DeepgramAsrClient(prefs, onState, onMessage)",
            "else -> AsrClient(prefs, onState, onMessage)",
        )) {
            assertTrue("工厂缺少分支：$impl", impl in factory)
        }

        val prefs = TestSources.codeSource("Prefs.kt")
        val export = TestSources.blockAfter(prefs, "internal fun exportForBackup")
        assertTrue("AK 没进导出白名单", "putCredential(KEY_BAIDU_ASR_API_KEY)" in export)
        assertTrue("SK 没进导出白名单", "putCredential(KEY_BAIDU_ASR_SECRET_KEY)" in export)
        assertTrue("dev_pid 没进导出白名单", "put(KEY_BAIDU_DEV_PID, baiduDevPid)" in export)
        // token 是派生缓存：进包没意义（换机后可能因源 IP / 账户变更失效）
        assertFalse("token 不许进备份", "put(KEY_BAIDU_TOKEN," in export)
        assertFalse("token 过期时刻不许进备份", "put(KEY_BAIDU_TOKEN_EXPIRE_AT," in export)

        val import = TestSources.blockAfter(prefs, "internal fun importFromBackup")
        assertTrue("dev_pid 没进导入白名单", "KEY_BAIDU_DEV_PID -> asInt(v)" in import)
        assertTrue(
            "AK 空串必须按忽略处理（否则会删掉本机可用凭据）",
            "KEY_BAIDU_ASR_API_KEY ->" in import,
        )
        assertTrue(
            "SK 空串必须按忽略处理",
            "KEY_BAIDU_ASR_SECRET_KEY ->" in import,
        )
        val configured = TestSources.blockAfter(prefs, "fun voiceConfigured()")
        assertTrue(
            "voiceConfigured 没认百度（AK + SK）",
            "VoiceBackend.BAIDU -> baiduSpeechApiKey.cleanCredential().isNotEmpty()" in configured,
        )
    }

    @Test
    fun `状态条两处标签都认百度`() {
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue(
            "在线标签没认百度（会拿自建服务端的 host:port 拼「已连接」）",
            "VoiceBackend.BAIDU -> TEXT_BAIDU_ONLINE" in TestSources.blockAfter(ime, "private fun renderLink"),
        )
        assertTrue(
            "离线提示没认百度",
            "VoiceBackend.BAIDU -> TEXT_BAIDU_OFFLINE" in
                TestSources.blockAfter(ime, "private fun switchToVoiceKeyboard"),
        )
        assertTrue("在线标签常量缺失", "const val TEXT_BAIDU_ONLINE" in ime)
        assertTrue("离线提示常量缺失", "const val TEXT_BAIDU_OFFLINE" in ime)
    }

    @Test
    fun `语音测试前置校验点名百度缺件`() {
        assertEquals(
            "百度凭据不全，缺少 API Key / Secret Key",
            VoiceTestPreflight.check(VoiceBackend.BAIDU, "", "", "", "192.168.1.3"),
        )
        assertEquals(
            "百度凭据不全，缺少 Secret Key",
            VoiceTestPreflight.check(
                VoiceBackend.BAIDU, "", "", "", "192.168.1.3",
                baiduApiKey = "ak",
            ),
        )
        assertNull(
            "两件齐了就该放行（dev_pid 有默认值）",
            VoiceTestPreflight.check(
                VoiceBackend.BAIDU, "", "", "", "192.168.1.3",
                baiduApiKey = "ak", baiduSecretKey = "sk",
            ),
        )
        // 页面把这三个字段真的传进了前置校验（漏传 = 永远报「缺少」）
        val selfTest = TestSources.codeSource("VoiceSelfTest.kt")
        val start = TestSources.blockAfter(selfTest, "fun start()")
        assertTrue("百度 AK 没传进前置校验", "baiduApiKey = prefs.baiduSpeechApiKey" in start)
        assertTrue("百度 SK 没传进前置校验", "baiduSecretKey = prefs.baiduSpeechSecretKey" in start)
    }

    @Test
    fun `页面百度卡走掩码且只在该后端下写盘`() {
        val page = TestSources.codeSource("VoiceSettingsActivity.kt")
        val load = TestSources.blockAfter(page, "private fun loadPrefs")
        assertTrue("AK 回填没走掩码", "setCredentialText(editBaiduApiKey, prefs.baiduSpeechApiKey)" in load)
        assertTrue("SK 回填没走掩码", "setCredentialText(editBaiduSecretKey, prefs.baiduSpeechSecretKey)" in load)
        assertTrue("dev_pid 没回填", "editBaiduDevPid.setText(prefs.baiduDevPid.toString())" in load)

        val save = TestSources.blockAfter(page, "private fun persistInputs")
        assertTrue("AK 有效取值没走 credentialText", "val baiduKeyValue = credentialText(editBaiduApiKey)" in save)
        assertTrue("SK 有效取值没走 credentialText", "val baiduSecretValue = credentialText(editBaiduSecretKey)" in save)
        assertFalse(
            "保存直接写回了输入框文本（掩码会被写坏）",
            "prefs.baiduSpeechSecretKey = editBaiduSecretKey.text.toString()" in save,
        )
        assertTrue("AK 没写回", "prefs.baiduSpeechApiKey = baiduKeyValue" in save)
        assertTrue("SK 没写回", "prefs.baiduSpeechSecretKey = baiduSecretValue" in save)
        assertTrue("dev_pid 没写回", "prefs.baiduDevPid = it" in save)
        assertTrue("写盘必须只发生在 baidu 分支", "if (backend == VoiceBackend.BAIDU) {" in save)
        for (field in listOf("editBaiduApiKey", "editBaiduSecretKey")) {
            assertTrue("$field 缺少必填校验", "$field.error = TEXT_BAIDU_REQUIRED" in save)
        }
        assertTrue("dev_pid 缺少数字校验", "editBaiduDevPid.error = TEXT_BAIDU_PID_REQUIRED" in save)
        assertTrue(
            "dev_pid 必须真去判数字（只写 error 文案不算校验）",
            "baiduDevPidValue.toIntOrNull() == null" in save,
        )
        // 秘密不进日志：只允许长度
        assertTrue("保存日志必须只记长度", "baiduSecretLen=" in save)
        assertFalse(
            "日志里出现了凭据原文",
            Regex("""\$\{prefs\.baidu(?:ApiKey|SecretKey)\}""").containsMatchIn(page),
        )

        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_voice_settings.xml",
            "app/src/main/res/layout/activity_voice_settings.xml",
        )
        assertTrue(
            "card_baidu_credentials 必须默认 GONE",
            Regex("""android:id="@\+id/card_baidu_credentials"\s+android:visibility="gone"""")
                .containsMatchIn(layout),
        )
        assertTrue(
            "Secret Key 必须是 password 型输入框",
            Regex("""android:id="@\+id/edit_baidu_secret_key"[^>]*android:inputType="textPassword"""")
                .containsMatchIn(layout),
        )
        assertTrue(
            "dev_pid 必须是数字键盘",
            Regex("""android:id="@\+id/edit_baidu_dev_pid"[^>]*android:inputType="number"""")
                .containsMatchIn(layout),
        )
    }
}
