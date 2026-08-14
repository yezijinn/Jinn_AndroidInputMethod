package com.jinn.voiceinput

import org.json.JSONObject

/**
 * Jinn Offline 通信协议。
 *
 * 字段名与服务端 core/protocol.py 一一对应，多一个少一个都会被
 * AudioMessage.from_dict 拒掉，所以这里手写 JSON 而不是靠反射序列化。
 */
object Protocol {

    /** 服务端 core/constants.py: AudioFormat.SAMPLE_RATE */
    const val SAMPLE_RATE = 16000

    /** 麦克风分段长度，对齐 config_client.py 的 mic_seg_duration */
    const val SEG_DURATION = 60.0

    /** 麦克风分段重叠，对齐 config_client.py 的 mic_seg_overlap */
    const val SEG_OVERLAP = 4.0
}

/**
 * 客户端 -> 服务端的音频包。
 *
 * 一次听写由若干 [isFinal] = false 的音频包，加一个 data 为空、
 * [isFinal] = true 的收尾包组成；服务端收到收尾包才会输出最终文本。
 *
 * @param data Base64(float32 小端裸样本, 16kHz, 单声道)
 * @param timeStart 录音起始的 Unix 秒级时间戳（带小数）
 * @param prompt 识别提示词，序列化后对应协议里的 context 字段
 */
data class AudioMessage(
    val taskId: String,
    val data: String,
    val isFinal: Boolean,
    val timeStart: Double,
    val prompt: String = "",
    val language: String = "auto",
) {
    fun toJson(): String = JSONObject().apply {
        put("task_id", taskId)
        put("source", "mic")
        put("data", data)
        put("is_final", isFinal)
        put("time_start", timeStart)
        put("seg_duration", Protocol.SEG_DURATION)
        put("seg_overlap", Protocol.SEG_OVERLAP)
        put("context", prompt)
        put("language", language)
    }.toString()
}

/**
 * 服务端 -> 客户端的识别结果。
 *
 * 注意 [text] 是该 task 从开头到当前的**累积**文本，不是增量片段
 * （服务端 worker/pipeline.py 把结果挂在 session 上逐段拼接），
 * 所以客户端每次直接整体覆盖显示即可，不要自己再拼一遍。
 */
data class RecognitionMessage(
    val taskId: String,
    val isFinal: Boolean,
    val duration: Double,
    val text: String,
) {
    companion object {
        fun parse(raw: String): RecognitionMessage? = runCatching {
            val json = JSONObject(raw)
            RecognitionMessage(
                taskId = json.optString("task_id"),
                isFinal = json.optBoolean("is_final", false),
                duration = json.optDouble("duration", 0.0),
                text = json.optString("text", ""),
            )
        }.getOrNull()
    }
}
