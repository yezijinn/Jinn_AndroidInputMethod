package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「语音测试」的结论与前置校验（纯函数部分）。
 *
 * 这三条决定用户看到的那一句话，而它正是本轮的核心诉求 ——「真实可信、能看出有没有效果」：
 *  · 有文本 = 成功（正文就在框里，用户可核对）；
 *  · 连上但空结果 = **链路是通的**，只是没听到声音（不能报成失败，否则用户会去改对的配置）；
 *  · 有失败原因 = 失败，原因原样带出（服务端 code / 异常）。
 */
class VoiceSelfTestTest {

    @Test
    fun `结论三态按文本与失败原因分流`() {
        assertEquals("成功：识别到 4 字", VoiceTestVerdict.of("今天天气", null, true))
        assertEquals("已连通，但没听到语音", VoiceTestVerdict.of("", null, true))
        assertEquals("已连通，但没听到语音", VoiceTestVerdict.of(null, null, true))
        assertEquals("失败：连接超时", VoiceTestVerdict.of(null, "连接超时", true))
        // 有半截文本但同时有失败原因：按失败报，别把半截结果当成成功
        assertEquals("失败：code 10163", VoiceTestVerdict.of("半截", "code 10163", true))
    }

    @Test
    fun `总开关关闭时结论必须注明`() {
        assertTrue(
            "关着总开关跑测试要说明结论不代表 IME 现在会识别",
            VoiceTestVerdict.of("你好", null, false).endsWith(VoiceTestVerdict.TEXT_VOICE_OFF_NOTE),
        )
        assertTrue(VoiceTestVerdict.of(null, "连接超时", false).contains("当前总开关关闭"))
        // 开着的时候不许画蛇添足
        assertEquals("成功：识别到 2 字", VoiceTestVerdict.of("你好", null, true))
    }

    @Test
    fun `前置校验点名缺什么且地址非法时直接拦下`() {
        // 自建后端：地址合法就放行（不联网探测），非法就地拒绝
        assertNull(VoiceTestPreflight.check(VoiceBackend.CAPS, "", "", "", "192.168.1.3"))
        assertNull(VoiceTestPreflight.check(VoiceBackend.CAPS, "", "", "", "[::1]"))
        assertEquals(
            VoiceTestPreflight.TEXT_BAD_HOST,
            VoiceTestPreflight.check(VoiceBackend.CAPS, "", "", "", "http://192.168.1.3"),
        )

        // 讯飞：缺哪件说哪件（字段名而已，不含凭据原文）
        assertNull(VoiceTestPreflight.check(VoiceBackend.XF, "app", "key", "secret", "192.168.1.3"))
        assertEquals(
            "讯飞凭据不全，缺少 APPID / APIKey / APISecret",
            VoiceTestPreflight.check(VoiceBackend.XF, "", "", "", "192.168.1.3"),
        )
        assertEquals(
            "讯飞凭据不全，缺少 APISecret",
            VoiceTestPreflight.check(VoiceBackend.XF, "app", "key", "", "192.168.1.3"),
        )
    }

    @Test
    fun `链路中途结束必须点名原因而不是等超时`() {
        // 服务端 code≠0（appid 错 / 鉴权失败）时客户端只交出 message：必须原样透出，
        // 否则页面只会说「等待识别结果超时」，用户去查网络与麦克风 —— 而问题在凭据。
        assertEquals(
            "失败：invalid appid",
            VoiceTestVerdict.of(null, VoiceTestVerdict.linkLost("invalid appid"), true),
        )
        // 没有原因（对方直接关连接 / 回调丢失）：给一句确定的失败，不能落到「已连通」那一态
        assertEquals(VoiceTestVerdict.TEXT_LINK_LOST, VoiceTestVerdict.linkLost(null))
        assertEquals(VoiceTestVerdict.TEXT_LINK_LOST, VoiceTestVerdict.linkLost("   "))
        // 三态互斥：空文本 + 无失败原因才是「已连通没声音」；有失败原因就是失败
        val connectedButSilent = VoiceTestVerdict.of("", null, true)
        val linkLost = VoiceTestVerdict.of(null, VoiceTestVerdict.linkLost(null), true)
        assertNotEquals("「链路断了」绝不能被说成「已连通，但没听到语音」", connectedButSilent, linkLost)
        assertTrue(linkLost.startsWith(VoiceTestVerdict.TEXT_FAIL_HEAD))
    }

    @Test
    fun `语音测试必须有整体超时兜底且链路结束即收尾`() {
        val src = TestSources.codeSource("VoiceSelfTest.kt")
        assertTrue(
            "缺整体超时：状态回调一丢，页面就永远停在「测试中…」、按钮永远禁用",
            "handler.postDelayed(overallRunnable, TOTAL_TIMEOUT_MS)" in src,
        )
        // 超时必须挂在真正发起请求之前（前置校验拦下的那次不该排超时，否则 30 秒后又撞一次）
        val start = TestSources.blockAfter(src, "fun start()")
        val post = start.indexOf("handler.postDelayed(overallRunnable, TOTAL_TIMEOUT_MS)")
        val connect = start.indexOf("created.connect(")
        assertTrue("整体超时必须排在 connect 之前（且两者都在 start 里）", post in 0 until connect)
        // 收尾唯一入口必须撤掉所有待跑回调（含整体超时），否则 finish 之后还会撞一次
        assertTrue("cleanup 必须撤掉全部回调", "removeCallbacksAndMessages(null)" in src)
        // OFFLINE 与 IDLE 都要出结论：不许再有「已开录就忽略」的分支（那会让 code≠0 变成超时）
        val state = TestSources.blockAfter(src, "private fun onState")
        assertTrue("OFFLINE 必须给结论", "LinkState.OFFLINE" in state)
        assertTrue("IDLE（对方关连接）也必须给结论", "LinkState.IDLE" in state)
        assertTrue("原因必须走 linkLost（服务端 message 优先）", "VoiceTestVerdict.linkLost(detail)" in state)
        assertFalse("已开录后掉线不许被静默忽略", "else -> Unit" in state)
    }

    /**
     * 收尾闸（Stage 3 复核补）：`finish` 只能生效一次。
     *
     * 为什么这是**行为**问题而不是风格问题：收到 final ⇒ 判成功 ⇒ `finish` 里先 `cleanup()`
     * ⇒ `client.close()` **同步**把状态回调成 `IDLE` ⇒ 若 [VoiceSelfTest.onState] 或 `finish`
     * 少一道 `if (finished) return`，那句「连接已断开」会把「成功：识别到 N 字」覆盖成失败 ——
     * 用户看到的是「明明识别出来了却报失败」。
     *
     * 原先没有任何用例钉这两道闸：删掉它们，全套 1850 例照样绿（2026-11-01 变异实测）。
     */
    @Test
    fun `成功定稿后迟到的状态回调不许改写结论`() {
        val src = TestSources.codeSource("VoiceSelfTest.kt")
        val finish = TestSources.blockAfter(src, "private fun finish")
        val gate = finish.indexOf("if (finished) return")
        val flag = finish.indexOf("finished = true")
        val cleanup = finish.indexOf("cleanup()")
        val deliver = finish.indexOf("onResult(")
        assertTrue("finish 缺 `if (finished) return`（成功结论会被后到的 IDLE 覆盖）", gate >= 0)
        assertTrue("定稿标记必须在 cleanup 之前（close() 会立刻回调 IDLE）", 0 <= flag && flag < cleanup)
        assertTrue("闸必须排在发结论之前", gate < deliver)

        // 状态回调侧同样要先闸：迟到回调与 final 的顺序由队列决定，不闸就会被改写
        val state = TestSources.blockAfter(src, "private fun onState")
        val stateGate = state.indexOf("if (finished) return")
        assertTrue("onState 缺闸（迟到回调会改写结论）", stateGate >= 0)
        assertTrue("onState 的闸必须排在 when (state) 之前", stateGate < state.indexOf("when (state)"))
    }

    /**
     * 麦克风启动失败必须留住具体原因（Stage 3 复核补）。
     *
     * `MicRecorder.start()` 失败时**同步**调 `onError`（原因可能是「麦克风被占用或权限被拒绝」/
     * 「录音初始化失败：…」），而 [VoiceSelfTest.beginCapture] 紧接着直接 `finish` ——
     * 那条 `post` 排在后面、被「已定稿」闸挡掉，真因就被泛泛文案盖了。
     */
    @Test
    fun `麦克风启动失败要保留具体原因`() {
        val src = TestSources.codeSource("VoiceSelfTest.kt")
        assertTrue("onError 没记录原因（同步失败时真因会被丢掉）", "micError = message" in src)
        val begin = TestSources.blockAfter(src, "private fun beginCapture")
        assertTrue("启动失败分支必须优先用 micError", "micError ?: TEXT_MIC_FAILED" in begin)
        // 兜底文案仍在（一个原因都没给时不能让结论空着）
        assertTrue("缺少兜底文案", "TEXT_MIC_FAILED" in src)
        // 收尾失败也要按连接状态分流（A-736）：批量后端「零音频」时 endTask 也返回 false，
        // 一律报「收尾包未送出」会把「你没说话」说成「发不出去」。
        val stop = TestSources.blockAfter(src, "private fun stopAndAwaitFinal")
        assertTrue("收尾失败必须先看连接状态", "client?.state == LinkState.ONLINE" in stop)
        assertTrue("连接正常时要报「没听到语音」", "VoiceTestVerdict.TEXT_NO_SPEECH" in stop)
        // IME 侧同一个判据（26 键按住说话这条路）
        val ime = TestSources.codeSource("JinnIme.kt")
        val anchor = ime.indexOf("asr?.endTask() != true")
        assertTrue("找不到 endTask 收尾失败的分支", anchor >= 0)
        val tail = ime.substring(anchor, minOf(ime.length, anchor + 700))
        assertTrue("IME 收尾失败也要按连接状态分流", "asr?.state != LinkState.ONLINE" in tail)
        assertTrue("连接正常时 IME 提示「没听到语音」", "VoiceTestVerdict.TEXT_NO_SPEECH" in tail)
    }
}
