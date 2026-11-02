package com.jinn.inputmethod

/**
 * 语音客户端工厂：**唯一**的后端分叉点。
 *
 * 为什么要有它：`JinnIme.ensureVoiceReady()` 与 [VoiceSelfTest] 原先各写一份
 * 「xf 用 XfIatClient / 否则 AsrClient」的 if/else。加第三个后端时两份要同时改，
 * 漏一处就是「键盘能用、语音测试不能用」这种只在真机上才发现的漂移。
 * 现在两边都只调 [create]，新增后端只需改这一个 when。
 *
 * 回调线程语义由各实现自己保证，调用方不必区分后端。
 */
object SpeechClients {

    /** 按 [Prefs.voiceBackend] 装配；未知取值由 `VoiceBackend.of` 归一到自建后端 */
    fun create(
        prefs: Prefs,
        onState: (LinkState, String?) -> Unit,
        onMessage: (RecognitionMessage) -> Unit,
    ): SpeechClient = when (prefs.voiceBackend) {
        VoiceBackend.XF -> XfIatClient(prefs, onState, onMessage)
        VoiceBackend.OAI -> OpenAiAsrClient(prefs, onState, onMessage)
        VoiceBackend.ALI -> AliAsrClient(prefs, onState, onMessage)
        VoiceBackend.TENCENT -> TencentAsrClient(prefs, onState, onMessage)
        VoiceBackend.AZURE -> AzureAsrClient(prefs, onState, onMessage)
        VoiceBackend.BAIDU -> BaiduAsrClient(prefs, onState, onMessage)
        VoiceBackend.DEEPGRAM -> DeepgramAsrClient(prefs, onState, onMessage)
        else -> AsrClient(prefs, onState, onMessage)
    }
}
