package com.jinn.voiceinput

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 麦克风采集器。
 *
 * 服务端只认 16kHz / 单声道 / float32 小端裸样本（core/constants.py 的 AudioFormat），
 * 这里用兼容性最好的 PCM_16BIT 采集再手动转 float32，避免部分机型不支持 ENCODING_PCM_FLOAT。
 *
 * 回调都发生在采集线程上，[onLevel] 和 [onError] 的接收方需要自己切回主线程。
 */
class MicRecorder(
    private val onChunk: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
    private val onSilence: (() -> Unit)? = null,
) {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // 权限由 IME 在 startRecording 前校验
    fun start(): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            Protocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "start: 设备不支持 16kHz 单声道采集, minBuffer=$minBuffer")
            onError("设备不支持 16kHz 录音")
            return false
        }

        // 缓冲区放大一倍，输入法主线程偶发卡顿时不至于丢帧
        val bufferSize = maxOf(minBuffer * 2, CHUNK_SAMPLES * BYTES_PER_SHORT * 4)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "start: AudioRecord 构造失败", e)
            onError("录音初始化失败：${e.message}")
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "start: AudioRecord 未初始化, state=${audioRecord.state}")
            Diagnostics.e(TAG, "start: AudioRecord 未初始化, state=${audioRecord.state}")
            audioRecord.release()
            onError("麦克风被占用或权限被拒绝")
            return false
        }

        record = audioRecord
        running = true
        thread = Thread({ loop(audioRecord) }, "jinn-mic").apply { start() }
        Diagnostics.i(
            TAG,
            "start: 采集已启动, minBuffer=$minBuffer bufferSize=$bufferSize, " +
                "chunk=${CHUNK_SAMPLES}样本/${CHUNK_MS}ms"
        )
        Log.i(TAG, "start: 采集已启动, bufferSize=$bufferSize")
        return true
    }

    fun stop() {
        if (!running && thread == null) return
        running = false

        val audioRecord = record
        record = null
        // 先 stop 让阻塞中的 read() 立刻返回，再 join，否则最长要等一个 chunk 的时长
        runCatching { audioRecord?.stop() }
            .onFailure {
                Log.w(TAG, "stop: AudioRecord.stop 异常: ${it.message}")
                Diagnostics.w(TAG, "stop: AudioRecord.stop 异常: ${it.message}")
            }

        thread?.join(JOIN_TIMEOUT_MS)
        // 超时仍未退出：打断阻塞中的 read，避免孤儿线程持有 AudioRecord
        thread?.let {
            if (it.isAlive) {
                Diagnostics.w(TAG, "stop: join 超时，中断采集线程")
                it.interrupt()
            }
        }
        thread = null

        runCatching { audioRecord?.release() }
            .onFailure {
                Log.w(TAG, "stop: AudioRecord.release 异常: ${it.message}")
                Diagnostics.w(TAG, "stop: AudioRecord.release 异常: ${it.message}")
            }
        Diagnostics.i(TAG, "stop: 采集已停止")
        Log.i(TAG, "stop: 采集已停止")
    }

    private fun loop(audioRecord: AudioRecord) {
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "loop: startRecording 失败", e)
            Diagnostics.e(TAG, "loop: startRecording 失败", e)
            onError("无法开始录音：${e.message}")
            return
        }

        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            Log.e(TAG, "loop: recordingState=${audioRecord.recordingState}")
            Diagnostics.e(TAG, "loop: recordingState=${audioRecord.recordingState}")
            onError("录音启动失败，请检查麦克风权限")
            return
        }
        Diagnostics.i(TAG, "loop: 进入采集循环")

        val buffer = ShortArray(CHUNK_SAMPLES)
        var silenceMs = 0L
        var silenceFired = false
        var chunkCount = 0L
        while (running) {
            val count = audioRecord.read(buffer, 0, buffer.size)
            if (count > 0) {
                chunkCount++
                // 回调抛异常不能让采集线程静默死亡（running 会卡 true，状态机失效），兜底收尾
                try {
                    onChunk(toFloat32LittleEndian(buffer, count))
                } catch (e: Exception) {
                    Log.e(TAG, "loop: onChunk 异常", e)
                    Diagnostics.e(TAG, "loop: onChunk 异常", e)
                    running = false
                    onError("音频发送失败：${e.message}")
                    break
                }
                val raw = rmsRaw(buffer, count)
                onLevel((raw * LEVEL_GAIN).toFloat().coerceIn(0f, 1f))
                // 本地 VAD：长段静音回调一次，由 IME 决定是否自动收尾以减少无效上传
                if (raw < SILENCE_RMS) {
                    silenceMs += CHUNK_MS
                    if (silenceMs >= SILENCE_TIMEOUT_MS && !silenceFired) {
                        silenceFired = true
                        onSilence?.invoke()
                        Diagnostics.w(TAG, "VAD: 连续静音 $silenceMs ms 触发 onSilence")
                    }
                } else {
                    if (silenceMs >= SILENCE_TIMEOUT_MS) {
                        Diagnostics.i(TAG, "VAD: 静音结束（持续 $silenceMs ms），重置计时")
                    }
                    silenceMs = 0
                    silenceFired = false
                }
                continue
            }
            if (count < 0) {
                // running 已被 stop() 置否时的负返回值属于正常收尾，不必报错
                if (running) {
                    Log.e(TAG, "loop: read 返回 $count")
                    Diagnostics.e(TAG, "loop: read 返回 $count (running=$running)")
                    onError("录音读取错误（$count）")
                }
                break
            }
            // count == 0 极其罕见，多半是 AudioRecord 处于异常态；稍歇让 CPU 不烫并给系统时间恢复
            if (count == 0) {
                Log.w(TAG, "loop: read 返回 0，短暂等待")
                Thread.sleep(CHUNK_MS)
            }
        }
        Diagnostics.i(TAG, "采集线程退出: 共 $chunkCount 包 (silenceMs=$silenceMs, running=$running)")
    }

    /** PCM16 -> float32 小端，与 numpy float32 的 tobytes() 完全一致 */
    private fun toFloat32LittleEndian(source: ShortArray, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count * BYTES_PER_FLOAT).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            buffer.putFloat(source[i] / PCM16_FULL_SCALE)
        }
        return buffer.array()
    }

    /** 归一化 RMS（未经增益），用于 VAD 与电平动画 */
    private fun rmsRaw(source: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = source[i] / PCM16_FULL_SCALE.toDouble()
            sum += sample * sample
        }
        // 语音 RMS 普遍在 0.02~0.2；安静环境约 0.003~0.008
        return sqrt(sum / count).toFloat()
    }

    private companion object {
        const val TAG = "MicRecorder"

        /** 每包 100ms 音频：延迟低、松手响应快，网络开销也可接受 */
        const val CHUNK_SAMPLES = Protocol.SAMPLE_RATE / 10
        const val CHUNK_MS = 100L

        /** 低于该 RMS 视为静音 */
        const val SILENCE_RMS = 0.012f
        /** 连续静音超过该时长触发一次 onSilence（连续录音模式容忍 120 秒静音，避免说话停顿几秒就被自动停止） */
        const val SILENCE_TIMEOUT_MS = 120_000L

        const val BYTES_PER_SHORT = 2
        const val BYTES_PER_FLOAT = 4
        const val PCM16_FULL_SCALE = 32768f
        const val LEVEL_GAIN = 4.0
        const val JOIN_TIMEOUT_MS = 300L
    }
}
