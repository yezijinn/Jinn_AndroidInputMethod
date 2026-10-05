package com.jinn.inputmethod

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * 敲击反馈引擎（音效 + 震动）：IME 进程内唯一实例。
 *
 * 为什么用 object 而不是每个键盘各持一份：IME 是**进程级单例**，两套键盘（拼音 / 语音）与
 * 未来任何面板共用同一套音效资源、同一份系统闸门缓存与同一个节流器。做成实例就得把
 * 「谁持有、谁释放、谁刷新」全部重新回答一遍，而答案本来就是唯一的那一个。
 * 生命周期由 [JinnIme] 单点管理（`onCreate` 挂载 / `onDestroy` 释放）。
 *
 * 三条设计底线（2026-10-05 二次审查结论）：
 *  1. **不碰录音**：本类不读也不改任何录音状态；[JinnIme] 只把「非麦克风键」接进来。
 *  2. **系统优先**：用户意愿（[Prefs]）与系统闸门（静音 / 触摸提示音 / 触摸时振动）是 AND ——
 *     任一为关都不出声、不震动。系统闸门在 [refreshSystemGates] 缓存，按键路径**零 IPC**。
 *  3. **不申请音频焦点**：走 `USAGE_ASSISTANCE_SONIFICATION`，只占用系统音效音量，
 *     不打断正在播放的音乐、也不会影响录音的音频焦点。
 */
internal object KeyFeedback {

    private const val TAG = "KeyFeedback"

    /** 普通键的最小反馈间隔：极速连打时避免音效叠成噪声、震动糊成一片 */
    private const val MIN_GAP_MS = 30L

    /** 删除 / 清空的最小反馈间隔：退格连删是 55ms 一次，不节流会变成「机关枪」 */
    private const val ERASE_MIN_GAP_MS = 150L

    /** 删除 / 清空「双段触感」的两段间隔 */
    private const val ERASE_PULSE_GAP_MS = 55L

    /** 同时播放的最大流数：快速连打时旧的让位给新的，而不是排队堆积 */
    private const val MAX_STREAMS = 4

    private var soundPool: SoundPool? = null

    /** 索引 → SoundPool 句柄（0 = 未加载完成，播放时跳过） */
    private val soundIds = IntArray(TapSound.SOUND_COUNT)

    private var vibrator: Vibrator? = null
    private var hasAmplitudeControl = false
    private var audioManager: AudioManager? = null

    // ── 用户意愿（来自 Prefs，[refresh] 写入）────────────────────
    private var soundEnabled = false
    private var vibrateEnabled = false
    private var volume = TapSound.VOLUME_DEFAULT / 100f
    private var soundOnSilent = false
    private var vibrationTier = TapSound.VIB_DEFAULT
    private var map: IntArray = TapSound.DEFAULT_MAP.copyOf()

    // ── 系统闸门（缓存，不在按键路径上查 IPC）──────────────────
    private var systemSoundAllowed = true
    private var systemHapticAllowed = true

    private var lastFireAt = 0L

    /**
     * 挂载：建 SoundPool、预载全部音效、解析系统闸门。**幂等**——系统重建 IME 时会再次
     * `onCreate`，重复挂载必须是无害的（否则会多出一份 SoundPool，旧的永不释放）。
     */
    fun attach(context: Context) {
        if (soundPool != null) return
        val app = context.applicationContext
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        vibrator = obtainVibrator(app)?.takeIf { it.hasVibrator() }
        hasAmplitudeControl = vibrator?.hasAmplitudeControl() == true
        soundPool = buildPool()
        val loaded = loadSounds(app)
        refreshSystemGates(app)
        Diagnostics.i(
            TAG,
            "挂载: 音效 $loaded/${TapSound.SOUND_COUNT} 震动=${vibrator != null} 振幅=$hasAmplitudeControl",
        )
    }

    /** 释放：`onDestroy` 里调用，避免 IME 被反复重建时泄漏 native 资源 */
    fun release() {
        soundPool?.release()
        soundPool = null
        soundIds.fill(0)
        runCatching { vibrator?.cancel() }
        vibrator = null
        hasAmplitudeControl = false
        lastFireAt = 0L
    }

    /**
     * 刷新用户意愿（设置页改动后调用；[JinnIme.onKeyFeedbackChanged] 是唯一入口）。
     *
     * 只读缓存字段，不做 IO：映射串已被 [Prefs.tapSoundMap] 归一过，这里再解析一次是防御。
     */
    fun refresh(prefs: Prefs) {
        soundEnabled = prefs.tapSoundEnabled
        vibrateEnabled = prefs.tapVibrateEnabled
        volume = TapSound.clampVolume(prefs.tapSoundVolume) / 100f
        soundOnSilent = prefs.tapSoundOnSilent
        vibrationTier = prefs.tapVibrateStrength
        map = TapSound.parseMap(prefs.tapSoundMap)
    }

    /**
     * 重读系统闸门。调用点：挂载时 + 每次键盘显示（[JinnIme.onStartInputView]）——
     * 用户去系统设置改完开关再回来必然生效，而按键路径上一次 IPC 都不做。
     */
    fun refreshSystemGates(context: Context) {
        systemSoundAllowed = systemFlag(context, Settings.System.SOUND_EFFECTS_ENABLED)
        systemHapticAllowed = systemFlag(context, Settings.System.HAPTIC_FEEDBACK_ENABLED)
    }

    /**
     * 触发一次按键反馈（**按下时**调用，与键盘的按压视觉同频）。
     *
     * 全部判据走缓存字段，无 IO、无 IPC；总开关都关时立即返回（最常见的情形）。
     */
    fun fire(group: Int) {
        if (!soundEnabled && !vibrateEnabled) return
        val now = SystemClock.uptimeMillis()
        val minGap = if (group == TapSound.G_ERASE) ERASE_MIN_GAP_MS else MIN_GAP_MS
        if (now - lastFireAt < minGap) return
        lastFireAt = now
        if (soundEnabled && systemSoundAllowed && ringerAllowsSound()) play(group)
        if (vibrateEnabled && systemHapticAllowed) vibrate(group)
    }

    // ── 音效 ─────────────────────────────────────────────────

    private fun buildPool(): SoundPool =
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            // 不用 STREAM_MUSIC：按键音属于「系统音效」，音量与静音策略都应与系统一致
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()

    /**
     * 预载全部音效，返回成功拿到句柄的数量。
     *
     * 为什么用 `openFd`：`SoundPool` 需要未压缩的 asset 才能拿到偏移量，而 aapt 默认把
     * `.ogg` 列入不压缩名单（build.gradle.kts 另有一条显式 `noCompress += "ogg"` 兜底）。
     * 原生侧会 `dup()` 这个 fd，所以 `load` 之后立刻关闭 AssetFileDescriptor 是安全的。
     */
    private fun loadSounds(context: Context): Int {
        val pool = soundPool ?: return 0
        var ok = 0
        for (i in 0 until TapSound.SOUND_COUNT) {
            val path = "${TapSound.ASSET_DIR}/${TapSound.assetName(i)}"
            val id = runCatching {
                context.assets.openFd(path).use { pool.load(it, 1) }
            }.onFailure {
                Diagnostics.w(TAG, "音效加载失败: $path (${it.message})")
            }.getOrDefault(0)
            soundIds[i] = id
            if (id != 0) ok++
        }
        return ok
    }

    private fun play(group: Int) {
        val pool = soundPool ?: return
        val index = map.getOrElse(group) { TapSound.NONE }
        if (index == TapSound.NONE) return
        val id = soundIds.getOrElse(index) { 0 }
        // 0 = 尚未加载完成（load 是异步的）：静默跳过即可，绝不能在主线程等待
        if (id == 0) return
        runCatching { pool.play(id, volume, volume, 1, 0, 1.0f) }
    }

    /** 静音（含仅震动）时是否放行音效：默认严格跟随系统响铃模式，用户可显式覆盖 */
    private fun ringerAllowsSound(): Boolean {
        if (soundOnSilent) return true
        val am = audioManager ?: return true
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    // ── 震动 ─────────────────────────────────────────────────

    private fun vibrate(group: Int) {
        val vib = vibrator ?: return
        val (ms, amp) = TapSound.vibrationSpec(vibrationTier)
        if (ms <= 0L) return
        runCatching { vib.vibrate(buildEffect(group, ms, amp)) }
    }

    /**
     * 删除 / 清空用「双段」波形（与它的警报音对齐）；其余单段。
     *
     * 振幅只有设备支持时才用得上，否则退回系统默认振幅 —— 档位仍由时长拉开差距。
     */
    private fun buildEffect(group: Int, ms: Long, amp: Int): VibrationEffect =
        if (TapSound.isDoublePulse(group)) {
            val timings = longArrayOf(0L, ms, ERASE_PULSE_GAP_MS, ms)
            if (hasAmplitudeControl) {
                VibrationEffect.createWaveform(timings, intArrayOf(0, amp, 0, amp), -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
        } else if (hasAmplitudeControl) {
            VibrationEffect.createOneShot(ms, amp)
        } else {
            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        }

    /** API 31 起走 VibratorManager（`VIBRATOR_SERVICE` 已废弃），26–30 仍用旧入口 */
    @Suppress("DEPRECATION")
    private fun obtainVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** 系统开关（0 = 关）。读不到时按「开」处理：宁可多一次反馈，也不要静默失效 */
    private fun systemFlag(context: Context, key: String): Boolean =
        runCatching { Settings.System.getInt(context.contentResolver, key, 1) != 0 }.getOrDefault(true)
}
