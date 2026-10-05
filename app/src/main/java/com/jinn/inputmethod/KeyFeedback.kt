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
import android.view.View
import android.view.ViewGroup

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

    /**
     * 响铃模式是否普通档（缓存值）。
     *
     * 与上面两道闸门放在同一处缓存：[fire] 原本每次都读 `AudioManager.ringerMode`，而
     * `getRingerMode()` 是到音频服务的跨进程调用 —— 连打时每次按键一次 IPC，与「按键路径零 IPC」
     * 的承诺不符。用户改响铃模式必然先离开键盘界面，随 [refreshSystemGates] 一起刷新足够新鲜。
     */
    private var ringerNormal = true

    /** 节流：普通键与删除组各一份时间戳（见 [TapSound.Throttle]） */
    private val throttle = TapSound.Throttle(MIN_GAP_MS, ERASE_MIN_GAP_MS)

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
        throttle.reset()
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
        // 音频服务按需取：设置页会在本进程还没挂载 IME 时调用本方法，那时 audioManager 还是 null。
        // ⚠ 不能写成 `audioManager?.ringerMode == RINGER_MODE_NORMAL` —— 左侧为 null 时整个表达式
        // 求值为 false，被静默当成「手机静音」，闸门永远关着（真机实测：三项系统设置全开而试听恒置灰）。
        // 取不到音频服务时按「放行」处理，与另外两道闸门的兜底口径一致。
        val am = audioManager
            ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.also { audioManager = it }
        ringerNormal = runCatching {
            am?.ringerMode?.let { it == AudioManager.RINGER_MODE_NORMAL } ?: true
        }.getOrDefault(true)
    }

    /**
     * 触发一次按键反馈（**按下时**调用，与键盘的按压视觉同频）。
     *
     * 全部判据走缓存字段，无 IO、无 IPC；总开关都关时立即返回（最常见的情形）。
     *
     * @param force 跳过节流。给「清空全部」这类一次性、不可撤销的动作用：它紧跟在连删循环后面，
     *              走普通节流会被上一次连删反馈挡掉，而那正是最需要给出「已经清干净了」的时刻。
     */
    fun fire(group: Int, force: Boolean = false) {
        val sound = soundGatesOpen()
        val vibrate = vibrateGatesOpen()
        if (!sound && !vibrate) return
        if (!force && !throttle.allow(group, SystemClock.uptimeMillis())) return
        if (sound) play(group)
        if (vibrate) vibrate(group)
    }

    /**
     * 发声的三道闸门是否全开：用户开关 + 系统「触摸提示音」+ 响铃模式放行。
     *
     * 设置页试听复用这一条 —— 试听与真实按键必须同一套判据。此前试听一条闸门都不过，
     * 于是「关了音效仍能听见试听声」，用户据此会判断音效是开着的。
     */
    fun soundGatesOpen(): Boolean = soundEnabled && systemSoundAllowed && ringerAllows()

    /** 震动的两道闸门是否全开：用户开关 + 系统「触摸时振动」 */
    fun vibrateGatesOpen(): Boolean = vibrateEnabled && systemHapticAllowed

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

    /** 响铃模式放行：默认跟随系统（静音 / 仅震动都不出声），用户可显式覆盖 */
    private fun ringerAllows(): Boolean = soundOnSilent || ringerNormal

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

/**
 * 按需关掉平台自带的点击音效与长按触觉（递归作用于整棵子树）。
 *
 * 为什么需要：`View.performClick()` 在存在 `OnClickListener` 时会向系统播一次 CLICK 音、
 * 长按被消费后会发 LONG_PRESS 触觉。键盘自带引擎之后那两份是重复的，而且只作用于
 * **有 OnClickListener 的键** —— 字母键走自定义触摸、不触发平台音，于是同一屏里
 * 功能键响两声、字母键响一声。
 *
 * 反过来，引擎没开时**不能**关：那时平台那份是用户唯一的反馈来源。所以两个参数都每帧显式赋值
 * （而不是「只置 false」），关掉引擎后能把开关还原回去 —— 这两个标记在 `View` 上不继承，
 * 必须逐个子视图设置，故走整树递归。
 */
internal fun View.applyPlatformFeedbackPolicy(suppressSound: Boolean, suppressHaptic: Boolean) {
    isSoundEffectsEnabled = !suppressSound
    isHapticFeedbackEnabled = !suppressHaptic
    if (this is ViewGroup) {
        // 每个容器都挂一次入树回调：新子树无论挂到哪一层，都会由「它进入的那个容器」按当前策略补一次。
        // 只在安装那一刻递归一遍盖不住运行期新建的视图 —— 候选词、功能面板按钮、密码数字条、
        // 符号分组标签、方向键、面板按钮都是按需建的，而这两个标记在 `View` 上不继承、
        // 新视图一律回到平台默认开，于是它们会「平台一声 + 引擎一声」。
        // 关闭引擎时本回调同样按新策略还原（参数每帧显式赋值，不是「只置 false」）。
        setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child?.applyPlatformFeedbackPolicy(suppressSound, suppressHaptic)
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        for (i in 0 until childCount) getChildAt(i).applyPlatformFeedbackPolicy(suppressSound, suppressHaptic)
    }
}
