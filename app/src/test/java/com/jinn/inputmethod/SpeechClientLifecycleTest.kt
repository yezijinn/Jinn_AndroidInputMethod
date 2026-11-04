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
     * 建连必须在**握手期间**也早退（2026-11-03 复审发现）。
     *
     * [XfIatClient.connect] 的早退判据含 `CONNECTING`，[AliAsrClient] 那份只认 `ONLINE` ——
     * 而预连（`releaseAndPreconnect`）与「进入语音面板」（`JinnIme.onStartInputView`）都会在
     * 握手窗口里再调一次 `connect()`：阿里这条会把**正在握手的 socket 关掉重建**，白做一次
     * TLS + WS 握手，状态条还要再闪一次「连接中」。阿里自己的 `releaseAndPreconnect` 里已经
     * 用 `state == LinkState.CONNECTING` 防了同一件事（否则「上一条被覆盖成孤儿」），
     * 说明这个判据在本类里本就是必需的，只是 `connect` 入口漏了一份。
     */
    @Test
    fun `阿里在握手期间也要早退不重建`() {
        val connect = TestSources.blockAfter(ali, "override fun connect")
        assertTrue(
            "阿里 connect 的早退判据漏了 CONNECTING（预连窗口里再调一次会把正在握手的连接拆掉重来）",
            "(state == LinkState.ONLINE || state == LinkState.CONNECTING)" in connect,
        )
        // 反向：force=true 必须还能重建（改地址 / 改凭据 / 旧连接用过的路径都靠它）
        assertTrue("force 不再重建连接（改地址后永远用旧连接）", "if (!force &&" in connect)
    }

    /**
     * 收尾之后必须留一条**空闲关闭**兜底（2026-11-03 复审）。
     *
     * 讯飞一次会话一条连接，正常路径由 `releaseAndPreconnect` 拆除；但那条路径的前提是
     * 「服务端回了 final / `ls=true`」。服务端静默不回（丢包、中间层断开、`ls` 丢失）时，
     * 这条连接既没有 `readTimeout`（=0）也没有 ping，`sessionStarted` 还是 true ——
     * **没有任何计时器再持有它**，只能等用户下一次按键强制重连才消失。
     * `finishTask` 是「末帧已发出、等结果」的唯一入口，兜底挂在这里才覆盖全部收尾路径。
     */
    @Test
    fun `讯飞收尾之后要挂空闲关闭兜底`() {
        val finish = TestSources.blockAfter(xf, "private fun finishTask")
        assertTrue(
            "finishTask 没挂空闲关闭兜底（服务端不回末帧时连接永久挂着）",
            "scheduleIdleClose()" in finish,
        )
        // 反向：兜底必须排在**末帧发送成功之后** —— 发送失败的分支已经 clearTask 并返回 false，
        // 在那里排程只会给一条已被丢弃的连接留个定时器（判据锚在 lastFrame 上，不是随便哪个
        // clearTask：那个函数在本方法里有三处，锚错就等于不判）
        val lastFrame = finish.indexOf("lastFrame(")
        assertTrue("finishTask 里找不到末帧发送点（锚点漂移）", lastFrame > 0)
        assertTrue(
            "空闲关闭兜底排在末帧发送之前（发送失败时也会被排上）",
            finish.indexOf("scheduleIdleClose()") > lastFrame,
        )
    }

    /**
     * 空闲关闭的「后延」不许把自己的计数清零（2026-11-03 复审发现）。
     *
     * 两家的后延都是有上限的（超过就强制关闭，免得标记残留把连接留成永生）。但原实现的后延
     * 调的是**对外那个** `scheduleIdleClose()`，而它第一件事就是 `idleCloseDeferrals = 0` ——
     * 计数器永远停在 1，`>= MAX` 那道判据成了死代码，连接于是被每 90s 后延一次、无限续命。
     * 判据只能钉结构：后延分支必须走不动计数的入口（`armIdleClose`），对外入口才清零。
     */
    @Test
    fun `空闲关闭的后延不许把自己的计数清零`() {
        for ((name, src) in listOf("自建" to asr, "讯飞" to xf)) {
            val idle = TestSources.blockAfter(src, "private fun onIdleCloseDue")
            assertTrue(
                "$name：后延走的是对外入口（会把自己的计数清零，上限永远到不了）",
                "armIdleClose(" in idle,
            )
            assertFalse(
                "$name：后延分支里又调了 scheduleIdleClose（计数被清零，连接无限续命）",
                "scheduleIdleClose(" in idle,
            )
            assertTrue(
                "$name：没有「后延次数到上限」的判据",
                "idleCloseDeferrals >= IDLE_CLOSE_MAX_DEFERRALS" in idle,
            )
            // 清零必须只发生在这一个地方：对外排程入口
            val schedule = TestSources.blockAfter(src, "override fun scheduleIdleClose")
            assertTrue("$name：对外排程入口没复位后延计数", "idleCloseDeferrals = 0" in schedule)
            assertTrue("$name：对外排程入口没真正排程", "armIdleClose(" in schedule)
            // 上限本身要有值（被删成 0 时「强制关闭」等于第一次就关，会把正常等结果的连接掐掉）
            assertTrue("$name：后延上限常量丢了", "const val IDLE_CLOSE_MAX_DEFERRALS = 3" in src)
        }
    }

    /**
     * 主动关闭之后不许再预连（2026-11-03 复审）。
     *
     * `releaseAndPreconnect` 跑在 OkHttp 的 IO 线程，而 `close()`（页面销毁 / 语音测试收尾）
     * 跑在主线程：两者交错时，IO 线程可能在 `close()` 之后又把连接建起来 —— 而那条连接的
     * 空闲窗口刚被 `close()` 撤掉，于是它既没有计时器、也没有读超时与 ping，只能等服务端自己断。
     */
    @Test
    fun `主动关闭之后不许再预连`() {
        for ((name, src) in listOf("讯飞" to xf, "阿里" to ali)) {
            val release = TestSources.blockAfter(src, "private fun releaseAndPreconnect")
            assertTrue(
                "$name：close() 之后仍会预连（新连接没有任何计时器持有）",
                "state == LinkState.IDLE" in release,
            )
            assertTrue(
                "$name：预连的空闲窗口丢了（没人用也一直挂着）",
                "scheduleIdleClose(" in release,
            )
        }
    }

    /**
     * 断连回调同样要撤掉空闲关闭兜底（2026-11-03 复审发现）。
     *
     * N7 只补了「服务端明确拒绝」那一条（`code != 0` / `task-failed`），**断连那两条没补**。
     * 而它才是最常见的一条：收尾时 `finishTask` 挂上 90s 兜底，服务端不回末帧、中间层掐线、
     * NAT 超时 —— [onFailure] 把真因（`连不上…` / `连接失败（HTTP 502）` / `连接超时`）写到状态条，
     * 90 秒后那条兜底照样到点，`updateState(IDLE, null)` 把它换成「未连接」：
     * 用户看着看着提示就没了，诊断包里也只剩前半段。
     *
     * 撤掉它不丢任何功能：连接已经断了，兜底本来要做的「拆除」已经无事可做 —— 留着它唯一还能
     * 发生的事就是抹掉那句话。
     */
    @Test
    fun `断连分支也要撤掉空闲关闭兜底`() {
        for ((name, src) in listOf("讯飞" to xf, "阿里" to ali)) {
            for (anchor in listOf("override fun onFailure", "override fun onClosed")) {
                val block = TestSources.blockAfter(src, anchor)
                assertTrue(
                    "$name：$anchor 没撤空闲关闭兜底（90s 后它会把刚写好的失败原因换成「未连接」）",
                    "cancelIdleClose()" in block,
                )
            }
        }
        // 阿里另两条失败出口（A-732）：两个超时分支同样先写 OFFLINE + 具体原因，
        // 不撤空闲关闭的话，90s 到点那次 updateState(IDLE) 会把原因换成「未连接」
        for (anchor in listOf("private fun onStartTimeout", "private fun onFinishTimeout")) {
            assertTrue(
                "阿里：$anchor 没撤空闲关闭兜底（超时原因会被「未连接」覆盖）",
                "cancelIdleClose()" in TestSources.blockAfter(ali, anchor),
            )
        }
    }

    /**
     * 握手被**明确拒绝**时不许无限自动重连（2026-11-03 复审发现）。
     *
     * 自建后端原先对每一次 `onFailure` 都排退避重连（1s→30s）：把 `ws://` 指到普通 HTTP 端口
     * （404）、反代加了鉴权（401）、凭据错（403）都是**确定性拒绝**，退避到 30s 之后重试一万次
     * 也是同一个结果。而每次重试都会把 state 推回 `CONNECTING`，`JinnIme.renderLink` 于是把
     * 刚写好的失败原因换成「连接中」再换回来 —— 语音面板开着时没有空闲关闭兜底，这条循环
     * 每 30 秒一次、永不停止（状态条来回闪 + 无线电唤醒，正是 MEM-13 要消掉的那份）。
     *
     * 判据是**真跑**的纯函数（不是源码字面量对拍），另钉一次接线：`onFailure` 必须真的走它。
     */
    @Test
    fun `握手被明确拒绝时不许无限重连`() {
        for (code in listOf(400, 401, 403, 404)) {
            assertFalse(
                "HTTP $code 是确定性拒绝，重试一万次也是同一个结果",
                AsrReconnect.shouldRetry(code),
            )
        }
        for (code in listOf(null, 408, 429, 500, 502, 503)) {
            assertTrue("HTTP $code 属临时故障，必须照旧重连", AsrReconnect.shouldRetry(code))
        }
        // 接线：不接这道判据的话，上面那组断言与实现无关（删掉整只 object 也不影响行为）
        val failure = TestSources.blockAfter(asr, "override fun onFailure")
        assertTrue(
            "onFailure 没按状态码决定要不要重连（401 / 404 会每 30s 一次永远重试）",
            "AsrReconnect.shouldRetry(response?.code)" in failure,
        )
        assertTrue(
            "连接已断开这条路径不许丢掉重连能力（临时故障必须还能自愈）",
            "scheduleReconnect()" in failure,
        )
    }

    /**
     * 自建后端的空闲关闭不许抹掉状态条上的失败原因（2026-11-03 复审）。
     *
     * 它与讯飞 / 阿里那两家的处置不同：这里的空闲关闭**兼管「停自动重连」**（网络故障走的是
     * 重连分支，`close()` 的 `userClosed = true` 是唯一的停止点），所以不能整体撤掉。
     * 能撤的只是「连接已经不在」那一种：`close()` 此时唯一还会做的事是把 `onFailure` 刚写好
     * 的失败原因换成「未连接」（`updateState(IDLE, null)`），而拆连早就没对象了。
     */
    @Test
    fun `自建后端空闲关闭不能抹掉失败原因`() {
        val idle = TestSources.blockAfter(asr, "private fun onIdleCloseDue")
        assertTrue(
            "空闲关闭没分「连接已不在」这一支（onFailure 写好的失败原因会在 90s 后被换成「未连接」）",
            "if (socket == null)" in idle,
        )
        assertTrue(
            "「连接已不在」必须在 close() 之前判（close() 第一件事就是 updateState(IDLE)）",
            idle.indexOf("if (socket == null)") in 0 until idle.indexOf("close()"),
        )
        assertTrue(
            "连接已不在时仍要停自动重连（否则退避定时器继续空转）",
            "reconnectHandler.removeCallbacks(reconnectRunnable)" in idle,
        )
    }

    /**
     * 批量后端的空闲关闭不许把失败结论改写成「未连接」（2026-11-03 复审）。
     *
     * 五家整段上传后端没有长连接可拆，这条兜底唯一的作用是「在线 → 空闲」的回落。
     * 但它原先无条件 `updateState(IDLE, null)`：上传失败（`HTTP 401` / `invalid_client`）
     * 把原因写到状态条之后，只要用户收起过键盘（`onFinishInputView` 排了 90s 窗口），
     * 到点就把那句话换成「未连接」—— 与讯飞 / 阿里在服务端拒绝分支上的处置正好相反。
     */
    @Test
    fun `批量后端的空闲关闭不许改写失败结论`() {
        val idle = TestSources.blockAfter(batch, "private fun onIdleCloseDue")
        assertTrue(
            "批量基类的空闲关闭无条件回落 IDLE（上传失败的原因会在 90s 后变成「未连接」）",
            "state != LinkState.ONLINE" in idle,
        )
        assertTrue(
            "回落判据必须排在 updateState 之前（否则那句 IDLE 照样会执行）",
            idle.indexOf("state != LinkState.ONLINE") in 0 until idle.indexOf("updateState(LinkState.IDLE"),
        )
        assertTrue(
            "在线态仍要能回落（没人用时状态条不该一直显示「已连接」）",
            "updateState(LinkState.IDLE" in idle,
        )
    }

    /**
     * 批量后端的结果投递必须**在主线程那一个 Runnable 里**再判一次归属（2026-11-03 复审）。
     *
     * 原实现先在后台线程 `acceptingTask = null`，再 `main.post` 投递：两者之间被取消或
     * 「新一段听写」插进来时，排队的 Runnable 照样把**旧任务**的正文交上去 ——
     * `JinnIme.handleResult` 只比包名、不看 taskId，于是新一段正在录时旧文本被 commit 进输入框，
     * 新结果随后追加（上一段的话重复出现）。
     */
    @Test
    fun `批量结果投递前要再判一次归属`() {
        val upload = TestSources.blockAfter(batch, "private fun submitUpload")
        val post = TestSources.blockAfter(upload, "main.post {")
        assertTrue("投递块里没有再判归属", "acceptingTask != id" in post)
        assertTrue(
            "闸门必须在投递块内、归属判定之后才清",
            post.indexOf("acceptingTask != id") in 0 until post.indexOf("acceptingTask = null"),
        )
        assertTrue("结果投递必须在同一个块里", "onResultCallback(" in post)
    }

    /**
     * 建连不许把已经 ONLINE 的状态压回 CONNECTING（2026-11-03 复审）。
     *
     * 自建后端原先写的是「先建连、再置状态」：`onOpen` 若在主线程被抢占的那几毫秒里先跑到
     * （局域网 / 本机 NAS 的握手是亚毫秒级），它置的 ONLINE 会被随后那行压回 CONNECTING ——
     * 而 `connect()` 的早退判据含 CONNECTING 且 socket 非空 ⇒ 之后再也回不到 ONLINE，
     * 长连接永久停在「连接中」。判据是顺序：置状态必须排在 `newWebSocket` **之前**
     * （`newWebSocket` 只入队，回调不可能同步返回，所以这个顺序没有反向风险）。
     */
    @Test
    fun `建连必须先置连接中再建连`() {
        val connect = TestSources.blockAfter(asr, "override fun connect")
        val setState = connect.indexOf("updateState(LinkState.CONNECTING, null)")
        val create = connect.indexOf("http.newWebSocket(")
        assertTrue("connect 里找不到置状态或建连（锚点漂移）", setState >= 0 && create > 0)
        assertTrue(
            "置状态排在建连之后（onOpen 先到时会把它压回 CONNECTING，永远回不到 ONLINE）",
            setState < create,
        )
        // 另一条路必须仍由 listener 置 ONLINE —— 别把两处都改成条件式
        assertTrue(
            "onOpen 不再置 ONLINE",
            "updateState(LinkState.ONLINE, null)" in TestSources.blockAfter(asr, "override fun onOpen"),
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
        // 原因还要留得住（A-734）：`onStartInputView` 每次聚焦都用 detail=null 重绘，
        // 客户端不保存 detail ⇒ 不缓一份，「鉴权失败（HTTP 401）」会被换成「未连接」
        val ime = TestSources.codeSource("JinnIme.kt")
        val render = TestSources.blockAfter(ime, "private fun renderLink")
        assertTrue("renderLink 必须缓存失败原因", "lastLinkDetail = detail" in render)
        assertTrue("ONLINE / CONNECTING 时必须清空缓存", "lastLinkDetail = null" in render)
        assertTrue("标签要读缓存后的值", "label = shownDetail ?:" in render)
        assertTrue(
            "聚焦重绘仍传 null（原因由 lastLinkDetail 兜住，别改成另存一份）",
            "renderLink(asr?.state ?: LinkState.OFFLINE, null)" in ime,
        )
    }

    /**
     * 日志出口同样要刮凭据（2026-11-03 复审发现）。
     *
     * 状态条那条出口早有 `SpeechErrorText.scrub`（见上一条用例），但**落盘日志那条没有**：
     * `Diagnostics.w(tag, "上传失败 reason=${reason}")` 之类直接拼服务端原文，而
     * 「回包正文 / 异常 message 整条带请求 URL」正是 `SpeechErrorText` KDoc 写明的那条路径。
     * 日志文件会打进诊断包外发，刮漏一处等于没刮 —— 两个出口必须同一口径。
     */
    @Test
    fun `日志出口也要刮凭据`() {
        // 批量基类：失败原因可能整条来自回包正文（网关把请求地址回显时连 client_secret 一起回来）
        val upload = TestSources.blockAfter(batch, "private fun submitUpload")
        assertTrue(
            "批量基类的上传失败日志直接拼了未刮的 reason",
            "上传失败 reason=\${SpeechErrorText.scrub(outcome.reason)}" in upload,
        )
        // 讯飞：code≠0 时把服务端 message 原样写进日志
        val xfMessage = TestSources.blockAfter(xf, "private fun handleMessage")
        assertTrue(
            "讯飞把服务端 message 原文写进日志（应先刮再落盘）",
            "SpeechErrorText.scrub(response.message)" in xfMessage,
        )
        // 阿里：task-failed 的 error_code / error_message 同理
        val aliFailed = TestSources.blockAfter(ali, "private fun onFailed")
        assertTrue(
            "阿里把 task-failed 原文写进日志",
            "SpeechErrorText.scrub(detail)" in aliFailed,
        )
        // 自建：onFailure 把异常 message 原样写进日志（SpeechErrorText 的 KDoc 举的就是这条）
        val asrFailure = TestSources.blockAfter(asr, "override fun onFailure")
        assertTrue(
            "自建把异常 message 原文写进日志",
            "SpeechErrorText.scrub(t.message)" in asrFailure,
        )
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
