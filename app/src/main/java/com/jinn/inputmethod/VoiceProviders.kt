package com.jinn.inputmethod

/**
 * 一条在线语音识别（ASR / STT）服务商的目录项。
 *
 * 纯数据：不持有 Context、不发请求，唯一用途是在「语音转为文字」页里列出来、点开官方文档。
 * 本输入法**只内置**自建服务端（CapsWriter）协议，这些条目是「想换服务商时去哪儿找文档」的索引。
 */
internal data class VoiceApiProvider(
    /** 稳定小写标识（日志与测试用，不显示） */
    val id: String,
    /** 显示名 */
    val name: String,
    /** 分类，取值必须是 [VoiceProviders.CATEGORIES] 里的某一个 */
    val category: String,
    /** 官方文档（https，已核实可达） */
    val docUrl: String,
    /** 特点（≤20 字） */
    val note: String,
)

/**
 * 常见语音转文字在线服务的目录（2026-11-01 调研所得，逐条照抄 `.agent-workflow/voice-settings-page/00-research.md`）。
 *
 * 收录口径：**只收已核实可达的官方文档页**；`ALL` 的顺序 = 调研表顺序（按分类聚在一起，
 * 分类顺序取 [CATEGORIES]）。新增条目要走同一套测试（`VoiceSettingsPageTest`：全 https、id / URL 不重复、
 * 分类必须被 [CATEGORIES] 覆盖、每类 ≥1 条）。
 */
internal object VoiceProviders {

    /** 国内云服务 */
    const val CAT_CN = "国内云服务"

    /** 国际云服务 */
    const val CAT_INTL = "国际云服务"

    /** 聚合与托管推理（一个 token 转发多家模型） */
    const val CAT_AGG = "聚合与托管推理"

    /** 自建服务端（含本输入法当前使用的 CapsWriter 协议） */
    const val CAT_SELF = "自建服务端"

    /** 页面渲染顺序：按这个顺序分组，不在表里的分类不会显示 */
    val CATEGORIES = listOf(CAT_CN, CAT_INTL, CAT_AGG, CAT_SELF)

    val ALL: List<VoiceApiProvider> = listOf(
        VoiceApiProvider(
            "xfyun_iat", "讯飞语音听写（流式）", CAT_CN,
            "https://www.xfyun.cn/doc/asr/voicedictation/API.html",
            "流式听写，中文与方言强",
        ),
        VoiceApiProvider(
            "xfyun_rtasr", "讯飞实时语音转写", CAT_CN,
            "https://www.xfyun.cn/doc/asr/rtasr/API.html",
            "不限时长实时转写",
        ),
        VoiceApiProvider(
            "baidu_speech", "百度智能云语音", CAT_CN,
            "https://cloud.baidu.com/doc/SPEECH/index.html",
            "老牌中文识别，生态全",
        ),
        VoiceApiProvider(
            "dashscope_asr", "阿里云百炼语音识别", CAT_CN,
            "https://help.aliyun.com/zh/model-studio/asr-model",
            "Paraformer / Qwen，模型最全",
        ),
        VoiceApiProvider(
            "dashscope_asr_api", "阿里云百炼接口参考", CAT_CN,
            "https://help.aliyun.com/zh/model-studio/speech-recognition-api-reference",
            "请求参数总入口",
        ),
        VoiceApiProvider(
            "aliyun_isi_rt", "阿里云智能语音（实时）", CAT_CN,
            "https://help.aliyun.com/zh/isi/user-guide/api-reference",
            "不限时长流式，方言多",
        ),
        VoiceApiProvider(
            "aliyun_isi", "阿里云智能语音交互", CAT_CN,
            "https://help.aliyun.com/zh/isi/getting-started/what-is-intelligent-speech-interaction",
            "一句话 / 录音 / 合成总览",
        ),
        VoiceApiProvider(
            "tencent_asr", "腾讯云语音识别", CAT_CN,
            "https://cloud.tencent.com/document/product/1093",
            "31 种方言大模型引擎",
        ),
        VoiceApiProvider(
            "tencent_asr_file", "腾讯云录音文件识别", CAT_CN,
            "https://cloud.tencent.com/document/api/1093/37823",
            "5 小时内音频，可分说话人",
        ),
        VoiceApiProvider(
            "huawei_sis", "华为云语音交互 SIS", CAT_CN,
            "https://support.huaweicloud.com/productdesc-sis/sis_01_0001.html",
            "实时 / 一句话 / 长音频三类",
        ),
        VoiceApiProvider(
            "volc_asr", "火山引擎流式语音识别", CAT_CN,
            "https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1?lang=zh",
            "豆包大模型，25 语种",
        ),
        VoiceApiProvider(
            "youdao_asr", "有道智云语音", CAT_CN,
            "https://ai.youdao.com/doc.s",
            "需登录控制台按服务查看",
        ),
        VoiceApiProvider(
            "azure_speech", "Azure AI Speech", CAT_INTL,
            "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/index-speech-to-text",
            "实时 + 批量 + 自定义语音",
        ),
        VoiceApiProvider(
            "google_stt", "Google Cloud Speech-to-Text", CAT_INTL,
            "https://docs.cloud.google.com/speech-to-text/docs",
            "100+ 语言，识别稳",
        ),
        VoiceApiProvider(
            "aws_transcribe", "Amazon Transcribe", CAT_INTL,
            "https://aws.amazon.com/transcribe/",
            "100+ 语言，通话分析",
        ),
        VoiceApiProvider(
            "openai_transcribe", "OpenAI 语音转写", CAT_INTL,
            "https://developers.openai.com/api/docs/guides/speech-to-text",
            "新模型 gpt-transcribe",
        ),
        VoiceApiProvider(
            "deepgram", "Deepgram", CAT_INTL,
            "https://developers.deepgram.com/home",
            "Nova-3，实时延迟低",
        ),
        VoiceApiProvider(
            "assemblyai", "AssemblyAI", CAT_INTL,
            "https://www.assemblyai.com/docs",
            "99 语言，功能丰富",
        ),
        VoiceApiProvider(
            "speechmatics", "Speechmatics", CAT_INTL,
            "https://docs.speechmatics.com/",
            "可容器化私有部署",
        ),
        VoiceApiProvider(
            "revai", "Rev AI", CAT_INTL,
            "https://docs.rev.ai/",
            "人工转写 + ASR",
        ),
        VoiceApiProvider(
            "gladia", "Gladia", CAT_INTL,
            "https://docs.gladia.io/chapters/introduction",
            "Solaria 模型，音频分析",
        ),
        VoiceApiProvider(
            "elevenlabs_scribe", "ElevenLabs Scribe", CAT_INTL,
            "https://elevenlabs.io/docs/api-reference/speech-to-text/convert",
            "词级时间戳与事件标签",
        ),
        VoiceApiProvider(
            "groq_whisper", "Groq Whisper", CAT_INTL,
            "https://console.groq.com/docs/speech-to-text",
            "极速，Whisper-large-v3",
        ),
        VoiceApiProvider(
            "cf_whisper", "Cloudflare Workers AI", CAT_INTL,
            "https://developers.cloudflare.com/workers-ai/models/whisper-large-v3-turbo/",
            "按音频分钟计费",
        ),
        VoiceApiProvider(
            "replicate_whisper", "Replicate Whisper", CAT_INTL,
            "https://replicate.com/openai/whisper",
            "开源模型托管，可自跑",
        ),
        VoiceApiProvider(
            "siliconflow_asr", "硅基流动 SiliconFlow", CAT_AGG,
            "https://docs.siliconflow.com/en/api-reference/audio/create-audio-transcriptions",
            "SenseVoice / FunAudioLLM",
        ),
        VoiceApiProvider(
            "hf_inference", "Hugging Face 推理服务", CAT_AGG,
            "https://huggingface.co/docs/inference-providers/index",
            "一个 Token 切多家",
        ),
        VoiceApiProvider(
            "together_ai", "Together AI", CAT_AGG,
            "https://www.together.ai/models",
            "Whisper 系列，OpenAI 兼容",
        ),
        VoiceApiProvider(
            "capswriter", "CapsWriter Offline（本机协议）", CAT_SELF,
            "https://github.com/HaujetZhao/CapsWriter-Offline",
            "自建 NAS，本输入法内置协议",
        ),
    )
}
