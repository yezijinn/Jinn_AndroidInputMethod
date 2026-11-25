package com.jinn.inputmethod

/**
 * 语音客户端工厂：**唯一**的后端分叉点。
 *
 * `JinnIme.ensureVoiceReady()` 与 [VoiceSelfTest] 都只调 [create]，
 * 分叉收敛在这一处 when：七个在线实现（讯飞 / OpenAI 兼容 / 阿里百炼 /
 * 腾讯云 / Azure / 百度 / Deepgram）加一个自建兜底，共八个分支。
 * 两处若各写一份 if/else，新增后端时漏改一处就会出现
 * 「键盘能用、语音测试不能用」这类漂移。现在新增后端只需改这里。
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
