package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 八家语音后端的**跨实现契约**守卫：取消语义、建连互斥、失败归因、会话边界的归属快照。
 *
 * 为什么不放进某一家的测试类：这四条都属于「同一件事在四份实现里口径一致」，
 * 放在任何一家名下都会被读成「只对那一家成立」。历史上正因为没有这一层，
 * 「松手后再取消」在 [BufferedBatchAsrClient] 修过一次（并写了注释与用例），
 * 而三家流式实现（[AsrClient] / [XfIatClient] / [AliAsrClient]）留着同款缺陷、没人发现 ——
 * 它们各自的用例只钉了「收尾包已发出之后」那一半路径。
 *
 * 这些类都依赖 OkHttp / Handler，进不了 JVM 单测，只能读源码钉结构与关键判据；
 * 反向钉一律走 [TestSources]（剥注释后再判）。
 */
class SpeechClientLifecycleTest {

    private val asr = TestSources.codeSource("AsrClient.kt")
    private val xf = TestSources.codeSource("XfIatClient.kt")
    private val ali = TestSources.codeSource("AliAsrClient.kt")
    private val batch = TestSources.codeSource("BufferedBatchAsrClient.kt")

    /**
     * `endTask()` 之后（`sendingTask` / `taskId` 已交出去、只剩等最终结果）取消必须照样生效。
     *
     * 反例（修之前）：三家流式实现都以「没有在发的任务」为由直接 `return false`，采纳闸门
     * （`acceptingTask`）根本没关 ⇒ 松手后再取消，晚到的那条 final 仍会 `commitText` 进输入框。
     * 用户看到的是「我明明取消了，字还是上屏了」。
     */
    @Test
    fun `收尾之后再取消必须关掉采纳闸门`() {
        // 自建后端：判据两半都要，缺一半就退回「没活可取消」
        val asrCancel = TestSources.blockAfter(asr, "override fun cancelTask")
        assertTrue(
            "自建的取消判据漏了 acceptingTask（收尾后取消会被当成「没活可取消」）",
            "if (taskId == null && acceptingTask == null) return false" in asrCancel,
        )
        assertTrue(
            "自建的取消必须在没有收尾包可发时也关掉闸门并返回 true",
            "收尾包已发过，只丢弃在途结果" in asrCancel,
        )
        assertTrue(
            "自建的取消必须先关闸门再发收尾包（回包与取消有竞态）",
            asrCancel.indexOf("acceptingTask = null") in 0 until asrCancel.indexOf("send(taskId, \"\", isFinal = true)"),
        )
        // 另半边：正常松手路径的 final 不许被这道闸吃掉 —— endTask 不得动采纳闸门
        assertFalse(
            "自建的正常收尾把采纳闸门也清了（正常松手的 final 会被丢）",
            "acceptingTask" in TestSources.blockAfter(asr, "override fun endTask"),
        )

        // 讯飞 / 阿里：收尾走同一个 finishTask，判据是「取消 + 还有在途结果」
        for ((name, src) in listOf("讯飞" to xf, "阿里" to ali)) {
            val finish = TestSources.blockAfter(src, "private fun finishTask")
            assertTrue(
                "$name：收尾之后再取消没有关掉采纳闸门（晚到的定稿仍会上屏）",
                "if (!acceptResult && acceptingTask != null)" in finish,
            )
            assertTrue(
                "$name：收尾之后再取消必须当成功（闸门已关，这次取消生效了）",
                "只丢弃在途结果" in finish,
            )
            // 正常收尾（endTask）不许被这一支吃掉：否则一次重复 endTask 就吞掉了结果
            assertFalse(
                "$name：收尾分支不许对 endTask 也生效",
                "if (id == null && acceptingTask != null)" in finish,
            )
            // 正常路径也必须真的有闸门：accept=true 时采纳闸门保持，final 才能上屏
            assertTrue(
                "$name：finishTask 没按 acceptResult 决定采纳闸门（正常松手会丢结果）",
                "acceptingTask = if (acceptResult) id else null" in finish,
            )
            assertTrue(
                "$name：endTask 没走「采结果」那一支",
                "override fun endTask(): Boolean = finishTask(acceptResult = true)" in src,
            )
        }

        // 基类那一家（五家批量后端）是这三条口径的来源，别在重构里丢
        assertTrue(
            "批量基类的取消判据漏了 acceptingTask",
            "acceptingTask == null) return false" in TestSources.blockAfter(batch, "override fun cancelTask"),
        )
    }

    /**
     * 建连与拆连必须互斥（讯飞侧）。
     *
     * 两个线程会同时建连 —— 主线程的 `beginTask`（旧连接用过 / 未就绪时强制重连）与 OkHttp IO
     * 线程的 `releaseAndPreconnect`（收到最终结果后预连下一条），而「用户恰好在收尾那一瞬按键」
     * 正是 `sessionStarted` 专门要挡的场景。不串行化时「关旧的 → 建新的」可以交错：
     * 刚建好的连接被对方的 `closeSocket()` 关掉，`socket` 停在 null、state 停在 CONNECTING。
     * [AliAsrClient] 已按这一条修过并对拍着；讯飞那份是同款时序，必须同款处理。
     */
    @Test
    fun `讯飞与阿里的建连拆连都互斥`() {
        for ((name, src) in listOf("讯飞" to xf, "阿里" to ali)) {
            assertTrue(
                "$name：connect 没有 @Synchronized（两次建连可以交错成一条没人持有的连接）",
                "@Synchronized\n    override fun connect(" in src,
            )
            assertTrue(
                "$name：closeSocket 没有 @Synchronized（关闭可以插进「关旧的—建新的」中间）",
                "@Synchronized\n    private fun closeSocket(" in src,
            )
        }
    }

    /**
     * 握手被拒（401 / 403 / 404…）必须带上状态码说人话，而不是把 OkHttp 那句英文截断了事。
     *
     * 讯飞的 apiKey / APISecret 签名错就是握手阶段的 401：只交异常 message 时状态条上是
     * 「Expected HTTP 101 response but was '401…」—— 用户看不出该改凭据，日志也拼不出结论。
     * 自建后端最常见的配错（把 ws:// 指到普通 HTTP 端口）同理只回一句 `was '404 Not Found'`。
     * 这是「鉴权失败被显示成连接失败」那一类，`VoiceSelfTest` 的结论行直接拿它拼。
     */
    @Test
    fun `握手被拒要用状态码归因`() {
        val describe = TestSources.blockAfter(xf, "private fun describe")
        assertTrue("讯飞失败原因没认 401 / 403", "code == 401 || code == 403" in describe)
        assertTrue("没有给出「鉴权失败」那句话", "鉴权失败（HTTP \$code）" in describe)
        assertTrue(
            "状态码必须真的传进 describe（否则上面那条永远不成立）",
            "describe(t, response?.code)" in TestSources.blockAfter(xf, "override fun onFailure"),
        )
        // 非鉴权的带码失败也要说人话：404（厂商改路径 / 中间层挡了）只留英文原文的话，
        // 状态条上是「Expected HTTP 101 response but was '404 Not…」，用户认不出是地址问题
        assertTrue(
            "讯飞的非鉴权带码失败没有落到「连接失败（HTTP 码）」",
            "code != null -> \"连接失败（HTTP \$code）\"" in describe,
        )
        // 自建后端：非鉴权的握手失败也要带码（404 / 502 才是「地址指错了」的指路牌）
        val asrDescribe = TestSources.blockAfter(asr, "private fun describe")
        assertTrue("自建后端的握手失败丢了状态码", "连接失败（HTTP \$code）" in asrDescribe)
        assertTrue(
            "自建后端的状态码没传进 describe",
            "describe(t, response?.code)" in TestSources.blockAfter(asr, "override fun onFailure"),
        )
        // 两家口径必须一致：同一句文案、同一个判据形态
        assertTrue(
            "阿里的鉴权归因被改掉了（几家要同一句话）",
            "鉴权失败（HTTP \$code）" in TestSources.blockAfter(ali, "private fun describe"),
        )
    }

    /**
     * 会话边界不得把在途结果的归属快照清掉。
     *
     * `onStartInputView` 是「换输入框 / 换应用」的入口，而松手后结果还要 1~3 秒才回 ——
     * 快照在那里被置空，`handleResult` 的跨应用校验就失去比对基准，上一段的识别正文会落进
     * 新输入框。这条与「暂存粘贴不带进新会话」是同一条隐私口径。
     */
    @Test
    fun `会话切换不得丢掉在途结果的归属快照`() {
        val ime = TestSources.codeSource("JinnIme.kt")
        val startView = TestSources.blockAfter(ime, "override fun onStartInputView")
        assertTrue("会话开始必须复位等待态", "awaitingResult = false" in startView)
        assertTrue("会话开始必须撤掉兜底超时", "ui.removeCallbacks(recognizeTimeout)" in startView)
        assertFalse(
            "会话开始把归属快照清掉了（在途结果会落进新输入框）",
            "voiceResultPackage = null" in startView,
        )
        // 快照仍要有真正的复位点，否则它会滞留（两处：结果到达 / 新一段录音开始）
        assertTrue(
            "handleResult 没复位归属快照",
            "voiceResultPackage = null" in TestSources.blockAfter(ime, "private fun handleResult"),
        )
        assertTrue(
            "startRecording 没复位归属快照",
            "voiceResultPackage = null" in TestSources.blockAfter(ime, "private fun startRecording"),
        )
        // 校验本身还在（快照没了这条就形同虚设）
        assertTrue(
            "handleResult 的跨应用校验丢了",
            "origin != current" in TestSources.blockAfter(ime, "private fun handleResult"),
        )
        // 兜底超时到点 = 这一段放弃：任务必须一起作废，否则 61 秒后才回的 final 仍会落字
        val timeout = TestSources.blockAfter(ime, "private val recognizeTimeout")
        assertTrue("兜底超时没作废任务（晚到的 final 仍会上屏）", "asr?.cancelTask()" in timeout)
        assertTrue("兜底超时没清归属快照（会滞留到下一段）", "voiceResultPackage = null" in timeout)
        // 但作废只对在线后端成立：自建后端单次可跑 180 秒（MAX_TOGGLE_MS），弱 NAS 上识别超过
        // 60 秒属正常量级；把「慢」当作废处理 = 白扔一段听写，而且快照一清，晚到正文的跨应用
        // 校验就没了基准（origin 为空时判据直接放行）
        assertTrue(
            "兜底超时的作废必须按 isOnline 分开（自建后端不该 60 秒作废）",
            timeout.indexOf("VoiceBackend.isOnline(prefs.voiceBackend)") in 0 until timeout.indexOf("asr?.cancelTask()"),
        )
    }

    /**
     * 失败文案里的凭据必须刮掉 —— 这条是**真跑**的（纯函数），不是源码对拍。
     *
     * 八家的失败文案兜底都是「异常 message 截若干字」，而异常正文可能整条带上请求 URL：百度换
     * token 的地址里就写着 `client_id=` / `client_secret=`，OkHttp 的 `unexpected url: …`
     * 会把地址原样抄进 message。一旦哪天真从这条路出去，凭据就随状态条上屏、随诊断包外发。
     */
    @Test
    fun `失败文案要刮掉凭据`() {
        // 百度换 token 的地址（AK/SK 都在 query 里）
        val url = "unexpected url: https://aip.baidubce.com/oauth/2.0/token" +
            "?grant_type=client_credentials&client_id=AKPLACEHOLDER01&client_secret=SKPLACEHOLDER01"
        val scrubbed = SpeechErrorText.scrub(url).orEmpty()
        assertFalse("AK 原文漏出来了", "AKPLACEHOLDER01" in scrubbed)
        assertFalse("SK 原文漏出来了", "SKPLACEHOLDER01" in scrubbed)
        assertTrue("刮完不该整句消失（排障还要看得懂）", scrubbed.contains("aip.baidubce.com"))

        // 请求头形态（Deepgram 用 Token、OpenAI 兼容用 Bearer）
        for (head in listOf("Authorization: Token dg-PLACEHOLDER01", "authorization: Bearer sk-PLACEHOLDER01")) {
            val out = SpeechErrorText.scrub(head).orEmpty()
            assertFalse("鉴权头原文漏出来了：$head", "dg-PLACEHOLDER01" in out || "sk-PLACEHOLDER01" in out)
        }

        // 腾讯那两处：SecretId 是配对的账号标识（`SecretId=…`），TC3 头里是 `Credential=AKID…`
        // —— 裸 `secret` 那条既名过不了 `SecretId`（后面紧跟的是 `I` 不是 `=`），会整段漏刮
        val tencent = "SecretId=AKIDPLACEHOLDER0001 " +
            "Authorization: TC3-HMAC-SHA256 Credential=AKIDPLACEHOLDER0001/2026-11-03/asr/tc3_request"
        val tencentOut = SpeechErrorText.scrub(tencent).orEmpty()
        assertFalse("腾讯 SecretId 漏出来了：$tencentOut", "AKIDPLACEHOLDER0001" in tencentOut)
        assertTrue("SecretId 这个键名该留着（看得出刮的是什么）", tencentOut.contains("SecretId=***"))

        // 讯飞签名地址里的 authorization=<两级 base64>：签名由 APISecret 派生，与凭据等价
        val xfUrl = "ws://iat.cn-huabei-1.xf-yun.com/v1?authorization=SIGNATUREPLACEHOLDER01"
        val xfOut = SpeechErrorText.scrub(xfUrl).orEmpty()
        assertFalse("讯飞签名漏出来了：$xfOut", "SIGNATUREPLACEHOLDER01" in xfOut)
        assertTrue("地址主机该留着（排障要看得懂）", xfOut.contains("iat.cn-huabei-1.xf-yun.com"))

        // 反向：不许把**有用的**判据刮掉 —— 状态码与普通字段名都不在凭据边界内
        for (keep in listOf(
            "HTTP 400 code=400 message 参数不合法 id=42",
            "Expected HTTP 101 response but was '401 Unauthorized'",
        )) {
            assertTrue("正常判据被刮掉了：$keep -> ${SpeechErrorText.scrub(keep)}", SpeechErrorText.scrub(keep) == keep)
        }

        // 幂等：状态回调处与 describe 各刮一次时，第二遍不得再改动（否则文案会二次变形）
        val twice = SpeechErrorText.scrub(SpeechErrorText.scrub(url))
        assertEquals("刮两遍与刮一遍必须一致", scrubbed, twice)

        // 普通报文一字不动：这几句就是用户要看的服务端原话
        for (plain in listOf(
            "Expected HTTP 101 response but was '401 Unauthorized'",
            "code 10163 服务端校验失败",
            "3302: 鉴权失败，token 无效或已过期",
            "HTTP 401 API Key 无效 · INVALID_AUTH",
        )) {
            assertEquals("正常报文被改写了：$plain", plain, SpeechErrorText.scrub(plain))
        }
        assertNull("空文案要交回 null（由调用方给兜底句）", SpeechErrorText.scrub("   "))
    }

    /**
     * 状态条文案的**唯一出口**必须过一道凭据刮除。
     *
     * 四家的失败原因来源不同：流式那三家兜底取异常正文（`describe`），五家批量后端的失败
     * 大多来自**回包正文**（`errorText(code, raw)` 直接取正文前 60 字）—— 后者根本不经过
     * `describe`。只在 `describe` 里刮，等于漏掉「网关 / 反向代理把请求地址整条回显」这一整类
     * （百度换 token 的地址里就有 `client_secret=`）。出口收在一处，漏一个分支就等于没刮。
     */
    @Test
    fun `状态条文案的出口必须刮凭据`() {
        for ((name, src) in listOf(
            "自建" to asr, "讯飞" to xf, "阿里" to ali, "批量基类" to batch,
        )) {
            val sink = TestSources.blockAfter(src, "private fun updateState")
            assertTrue(
                "$name：状态回调直接交原始 detail（回包正文里的凭据会随状态条上屏）",
                "SpeechErrorText.scrub(detail)" in sink,
            )
        }
    }

    /**
     * 三家流式的异常兜底必须**先刮再截**。
     *
     * 反过来（`scrub(t.message?.take(40))`）会先把长的异常正文切成 40 字，
     * 再拿切剩的片段去匹配 —— `AUTH_PREFIX` 要求 ≥6 个字符才算一个鉴权值，
     * 「…Authorization: Bearer sk-pr」这种半截值匹配不上，原样上屏。
     * 批量基类早就是先刮后截（`:309`），三家流式漏了同一处顺序。
     */
    @Test
    fun `异常兜底必须先刮再截`() {
        for ((name, src) in listOf("自建" to asr, "讯飞" to xf, "阿里" to ali, "批量基类" to batch)) {
            // 批量基类那份是 `protected fun describe`（子类共用），另三家是 private
            val anchor = if (src === batch) "protected fun describe" else "private fun describe"
            val describe = TestSources.blockAfter(src, anchor)
            assertTrue(
                "$name：没有先刮再截（半截鉴权值会漏过 ≥6 字符的门槛）",
                "SpeechErrorText.scrub(t.message)?.take(" in describe,
            )
            assertFalse(
                "$name：先截后刮（长正文里的鉴权头会被切成半截值）",
                "scrub(t.message?.take(" in describe,
            )
        }
    }

    /**
     * 批量基类的缓冲必须有上限。
     *
     * 58 秒自动收尾是唯一让它停下来的东西；收尾定时器一没（视图销毁 / 采集停不下来），
     * 32 KB/s 就是一路涨到 OOM —— 而死的是整个输入法进程，不是一段识别。
     */
    @Test
    fun `批量基类的缓冲要有上限`() {
        val send = TestSources.blockAfter(batch, "override fun sendChunk")
        assertTrue("sendChunk 无上限地往缓冲里写（长跑必 OOM）", "MAX_PCM_BYTES" in send)
        assertTrue("上限判据必须在写入之前", send.indexOf("MAX_PCM_BYTES") in 0 until send.indexOf("pcm.write("))
        assertTrue("上限常量缺失", "const val MAX_PCM_BYTES" in batch)
        // 每段任务复位告警闸，否则第二段再也看不到这条 W
        assertTrue("beginTask 没复位溢出告警闸", "overflowLogged = false" in TestSources.blockAfter(batch, "override fun beginTask"))
    }

    /**
     * 百度换 AK / SK 必须同时作废 token 缓存。
     *
     * `access_token` 是「某一对 AK/SK」的派生凭据、有效期 30 天，识别请求只按它认账号：
     * 不作废的话，换完凭据识别继续跑在旧账号的配额上（用户以为新凭据生效），
     * 把 AK/SK 清空时那个账号也还在替它干活。
     */
    @Test
    fun `百度换凭据要作废 token 缓存`() {
        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue(
            "AK 写入没走「比较 + 清 token」的入口",
            "set(value) = writeBaiduSpeechCredential(KEY_BAIDU_ASR_API_KEY, value)" in prefs,
        )
        assertTrue(
            "SK 写入没走「比较 + 清 token」的入口",
            "set(value) = writeBaiduSpeechCredential(KEY_BAIDU_ASR_SECRET_KEY, value)" in prefs,
        )
        val helper = TestSources.blockAfter(prefs, "private fun writeBaiduSpeechCredential")
        assertTrue("写入本身仍须加密落盘", "writeCredential(key, value)" in helper)
        assertTrue("值变了没作废 token", "writeCredential(KEY_BAIDU_TOKEN, \"\")" in helper)
        assertTrue("值变了没清过期时刻（留着会被当成「还有效」）", "putLong(KEY_BAIDU_TOKEN_EXPIRE_AT, 0L)" in helper)
        assertTrue("值没变也清 token（每次保存都要白取一次）", "if (!changed) return" in helper)
        // 作废必须发生在比较之后：先清再比永远「没变」
        assertTrue(
            "比较顺序反了（先清 token 再比较等于永不作废）",
            helper.indexOf("val changed =") in 0 until helper.indexOf("writeCredential(key, value)"),
        )
    }
}
