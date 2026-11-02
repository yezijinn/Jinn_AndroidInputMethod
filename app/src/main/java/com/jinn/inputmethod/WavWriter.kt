package com.jinn.inputmethod

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 给 PCM16 裸流套一个标准 WAV 头（[OpenAiAsrClient] 上传 `audio.wav` 用）。
 *
 * 严格按 RIFF/WAVE：44 字节头 + `data` 块，PCM 16-bit **小端**、16kHz、单声道。
 * 这些字段是 `/audio/transcriptions` 的硬要求 —— 采样率或位深写错，服务端不会报错，
 * 只会给出一段听不懂的识别结果，从日志里完全看不出原因，所以做成纯函数在 JVM 里逐个字段对拍。
 *
 * 纯计算、无 Android 依赖，可直接单测。
 */
internal object WavWriter {

    /** 采样率：与 [MicRecorder] / 讯飞同一口径（16k 单声道） */
    const val SAMPLE_RATE = 16_000

    const val CHANNELS = 1

    const val BITS_PER_SAMPLE = 16

    /** 标准 WAV 头长度（RIFF 12 + fmt 24 + data 8） */
    const val HEADER_BYTES = 44

    /** PCM 编码编号（`fmt` 块里的 audioFormat） */
    private const val FORMAT_PCM = 1

    /** `fmt ` 块长度（PCM 固定 16） */
    private const val FMT_CHUNK_BYTES = 16

    /** 字节率 = 采样率 × 声道 × 位深/8 */
    private const val BYTE_RATE = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

    /** 块对齐 = 声道 × 位深/8 */
    private const val BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8

    /**
     * @param pcm16 PCM 16-bit 小端、16kHz、单声道裸流（[XfIatProtocol.float32ToPcm16] 的输出）
     * @return 完整 wav 字节；空输入也产出合法的 44 字节头（`data` 长 0）
     */
    fun wrap(pcm16: ByteArray): ByteArray {
        val dataSize = pcm16.size
        val out = ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put(ascii("RIFF"))
        // RIFF 块长 = 文件总长 - 8（"RIFF" + 本字段之后的全部内容）
        out.putInt(HEADER_BYTES - 8 + dataSize)
        out.put(ascii("WAVE"))
        out.put(ascii("fmt "))
        out.putInt(FMT_CHUNK_BYTES)
        out.putShort(FORMAT_PCM.toShort())
        out.putShort(CHANNELS.toShort())
        out.putInt(SAMPLE_RATE)
        out.putInt(BYTE_RATE)
        out.putShort(BLOCK_ALIGN.toShort())
        out.putShort(BITS_PER_SAMPLE.toShort())
        out.put(ascii("data"))
        out.putInt(dataSize)
        out.put(pcm16)
        return out.array()
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
