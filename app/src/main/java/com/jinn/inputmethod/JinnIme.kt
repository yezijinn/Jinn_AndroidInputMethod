package com.jinn.inputmethod

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Jinn 拼音输入法。
 *
 * 双模式：
 *  - 语音模式：麦克风 + 最小编辑键（长按说话 / 短按连续录音，实时推给服务端识别）
 *  - 键盘模式：26 键拼音键盘（全拼 / 自然码双拼），候选上屏
 *
 * 两种模式通过键盘上的「键盘 / 语音」键互相切换，互不干扰。
 */
class JinnIme : InputMethodService() {

    private enum class Mode { NONE, HOLD, TOGGLE }

    /** 当前键盘模式 */
    private enum class KeyboardMode { VOICE, PINYIN }

    private val ui = Handler(Looper.getMainLooper())

    /** 可选词库包是否已触发加载（三种空闲信号只生效一次） */


    /** 息屏接收器：锁屏即视为空闲，可安全做 21~34s 的后台重活 */
    private var screenOffReceiver: BroadcastReceiver? = null

    /** 键盘收起后的空闲检查（见 [maybeLoadOptionalDict]） */
    private val optionalIdleCheck = Runnable { maybeLoadOptionalDict("键盘收起后闲置") }

    private lateinit var prefs: Prefs

    /**
     * 上次创建键盘视图时生效的深浅色（null = 还没有键盘视图）。
     *
     * `onStartInputView` 用它判断主题是否被改过（设置页改了模式、或定时模式跨过切换点）：
     * 只有决策结果变了才重建键盘，常规弹出路径零额外开销。
     *
     * ⚠ **不要**为了「统一」把它换成 `ThemeManager.paletteIsDark(this)`：页面能用那个是因为
     * 页面的 Context 被 `attachBaseContext` 换过、`Resources` 里就是它**画出来**的那一档；
     * 而 IME 是 Service，它的 Context 没有被换（键盘视图是拿 `themeCtx` 现造的）⇒ 在 IME 里读
     * `paletteIsDark` 得到的是**系统**深浅，与「视图用的是哪一档」是两件事。
     * （页面对应的字段确实被删了，那是因为页面场景下两者等价 —— 见
     * `ThemeManager.recreateIfPaletteStale` 的 KDoc，2026-10-02 L-476。）
     */
    private var appliedThemeDark: Boolean? = null

    /**
     * 符号布局变更被「未上屏输入」延后，等待下次弹键盘（onStartInputView）时补重建。
     * 见 [rebuildInputViewForSymbolLayout]；换肤/符号重建共用的 [recreateKeyboardView] 会清掉它。
     */
    private var pendingSymbolLayoutRebuild = false

    /**
     * 本次会话的「敏感输入框不学习」判定（由 [onStartInputView] 按 EditorInfo 算出）。
     *
     * 抑制标记本体是**视图上的字段**（`PinyinKeyboardView.suppressLearning`，声明处默认 false），
     * 而视图重建（换肤 / 符号布局变更）会整只换掉它 ⇒ 重建后必须重放这个值，
     * 否则本会话余下时间在密码框里选候选会被学进词频（BUG.md L-96）。
     */
    private var suppressLearningForSession = false

    /**
     * 键盘视图创建时用的主题覆盖 Context（见 [ThemeManager.themedContext]）。
     *
     * 服务自身的 `getColor()` 走的是系统配置：强制亮白/暗黑时，服务里直接取色会拿到另一套色板
     * （状态点曾因此用错主题的 dot 色）。凡在服务层取色的地方，一律用这个 Context，未创建时退回自身。
     */
    private var keyboardThemeCtx: Context? = null
    /**
     * 语音组件。仅在「语音输入」开关为开时才实例化（见 [ensureVoiceReady]）。
     * 开关关闭时两者恒为 null，语音功能完全沉寂，不占用任何语音相关内存，
     * 因此所有使用点都必须走空安全（`?.`）。
     */
    private var asr: AsrClient? = null
    private var recorder: MicRecorder? = null

    /** 剪贴板控制器：监听系统剪贴板 → 按策略加密保存历史（普通模式核心） */
    private var clipboardController: ClipboardController? = null

    private var micButton: MicButton? = null
    private var statusDot: View? = null
    private var statusLabel: TextView? = null
    private var hintLabel: TextView? = null

    private var pinyinKeyboard: PinyinKeyboardView? = null
    /** 当前键盘模式。默认拼音，语音默认禁用，不能以语音键盘起步 */
    private var keyboardMode = KeyboardMode.PINYIN
    private var keyboardContainer: FrameLayout? = null

    /** 剪贴板页点击记录时若连接无效，暂存待粘贴文本，编辑框聚焦后自动粘贴 */
    private var pendingPasteText: String? = null
    /**
     * [pendingPasteText] 的暂存时刻。
     *
     * 暂存文本只在短时间内有效（[PENDING_PASTE_TTL_MS]）：用户点完剪贴板条目后
     * 若很久才切回输入框，或中途换了别的输入框，过期的暂存文本会被静默丢弃，
     * 否则一次 onStartInputView 就会把旧内容粘到完全无关的位置。
     */
    private var pendingPasteAt = 0L

    /**
     * [pendingPasteText] 的目标输入框标识（[pendingPasteFieldKey] 的格式）。
     *
     * 时效窗口（10s）远长于「切到另一个 App 的输入框」所需时间，只靠时间约束
     * 会把上一段剪贴板内容粘进无关的输入框甚至无关应用。暂存时锁定当时的输入框，
     * 提交前比对 [onStartInputView] 传来的 EditorInfo，不一致即丢弃。
     */
    private var pendingPasteFieldKey: String? = null

    /**
     * 是否在等最终识别结果（松手后、服务端回 final 之前）。
     *
     * 服务端丢任务或卡住时不会触发任何回调，状态条会永远停在「识别中…」；
     * 由 [recognizeTimeout] 兜底，连接断开时（[renderLink]）也立即复位。
     */
    private var awaitingResult = false

    /**
     * 发起本次听写时所在应用的包名。
     *
     * 松手后服务端还要 1~3 秒才回结果，这期间用户可能切到别的应用；
     * 结果落到新应用是隐私问题，与暂存粘贴同一条口径做归属比对。
     * 只比包名不比 fieldId：部分宿主的 fieldId 不稳定，按它比会误丢正常结果。
     */
    private var voiceResultPackage: String? = null

    /** 松手后等最终结果的兜底：超时即复位状态条，不让「识别中…」永驻 */
    private val recognizeTimeout = Runnable {
        if (!awaitingResult) return@Runnable
        awaitingResult = false
        Diagnostics.w(TAG, "识别超时: ${RECOGNIZE_TIMEOUT_MS}ms 内未收到最终结果，复位状态条")
        setHint(getString(R.string.hint_not_connected))
    }

    /** 剪贴板面板打开标记：仅供诊断日志（视图侧的真实状态在 PinyinKeyboardView 内，重建后不恢复） */
    private var clipboardPanelOpen = false

    // ── 在线翻译（BYOK，2026-09-30 起）─────────────────────────

    /** 翻译请求代际：新会话 / 新请求都递增，回调只认最新一代（旧结果直接丢弃） */
    private var translateGeneration = 0

    /** 是否有在途翻译请求（防连点；键盘侧「翻译」按钮同步置灰） */
    private var translateInFlight = false

    /**
     * 翻译看门狗（2026-10-01 审查 L-217）。
     *
     * OkHttp 在调用 `onResponse` **之前**就把 `signalledCallback` 置位 ⇒ 回调内部抛出的异常
     * **不会**回落到 `onFailure`（只被平台记一条 INFO）。少了这道兜底，`translateInFlight`
     * 会永久为真、按钮永远显示「翻译中」，唯一复位路径只剩会话边界。
     */
    private val translateWatchdog = Runnable {
        // 只在**确实还在途**时收尾：定时器可能是上一次请求留下的（正常路径都会撤，万一漏撤，
        // 这里也不能误伤后来者 —— 收尾会作废代际，2026-10-01 复审 L-229）
        if (translateInFlight) {
            Diagnostics.w(TAG, "翻译: 看门狗超时，强制收尾（回调未到达）")
            finishTranslate()
        }
    }

    /**
     * 结束一次翻译会话：撤看门狗 + 复位按钮态 + **作废代际**（2026-10-01 复审 L-229/L-231）。
     *
     * 四个入口（看门狗超时 / 会话边界取消 / 回调到达 / 请求发起失败）必须共用这一套收尾：
     * 少了「作废代际」，看门狗复位之后到达的旧回调仍会被当成当前代际，把译文插进已经变化过的
     * 输入框；少了「撤看门狗」，正常完成的请求会白挂一个定时器到 330s（虽然被
     * `if (translateInFlight)` 守卫兜住，但那是巧合不是设计）。
     */
    private fun finishTranslate() {
        ui.removeCallbacks(translateWatchdog)
        translateGeneration++
        if (translateInFlight) {
            translateInFlight = false
            pinyinKeyboard?.setTranslating(false)
        }
    }

    /** 音频焦点：录音期间持有，避免被来电/其他 App 抢占麦克风 */
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        ui.post {
            // 焦点被抢占（来电/其他应用播音）：停止当前录音，避免冲突与串音
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                if (mode != Mode.NONE) stopRecording(commit = false)
            }
        }
    }

    /** 网络恢复时主动重连，覆盖 WiFi↔热点切换导致 IP 变化的场景 */
    private val connectivity by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var mode = Mode.NONE

    /** 连续录音中再次按下：抬手即结束 */
    private var stopOnUp = false

    /** 长按已触发过（可能因未连接而没真正录起来），抬手就不要再当短按处理 */
    private var holdAttempted = false

    private var cancelArmed = false
    private var downY = 0f
    private var cancelSlidePx = 0f

    private val startHoldRunnable = Runnable {
        holdAttempted = true
        startRecording(Mode.HOLD)
    }

    /** 连续录音兜底，防止用户忘了关 */
    private val autoStopRunnable = Runnable {
        if (mode == Mode.TOGGLE) stopRecording(commit = true)
    }

    private val backspaceRunnable = object : Runnable {
        override fun run() {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            ui.postDelayed(this, BACKSPACE_REPEAT_MS)
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────

    /**
     * 「分辨率」取值器：真实屏幕物理尺寸（宽×高像素），点击时才调。
     *
     * 不用 `DynamicSymbols` 里的 `Resources.getSystem()` 默认值：它在真机上按**应用区域**报
     * （PACM00 实测 1080×2200，比物理 1080×2280 少 80px 系统栏）。捕获 applicationContext
     * 是为了不牵住 Service 实例；`getRealMetrics` 在新版本标记废弃但仍可用，故显式压制告警。
     */
    private fun screenSizeProvider(): () -> String {
        val ctx = applicationContext
        return {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            "${dm.widthPixels}×${dm.heightPixels}"
        }
    }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
        // 半透明键盘：IME 窗口的默认格式是 OPAQUE(-1)/TRANSPARENT(-2)，合成器按「不透明窗口」
        // 处理，键面的 alpha 只会与窗口内部底色混合、透不出后面的应用（真机症状：改透明度毫无变化）。
        // 必须在窗口首次显示前把它改成 TRANSLUCENT(-3)，窗口才具备真正的 alpha 通道。
        runCatching {
            val w = window?.window
            Diagnostics.i(TAG, "IME 窗口: 初始 fmt=${w?.attributes?.format}")
            w?.setFormat(PixelFormat.TRANSLUCENT)
            Diagnostics.i(TAG, "IME 窗口: 已请求 TRANSLUCENT, fmt=${w?.attributes?.format}")
        }.onFailure { Diagnostics.w(TAG, "IME 窗口格式设置失败: ${it.message}") }
        prefs = Prefs(this)
        instance = WeakReference(this)
        // 「变量」组的「分辨率」：交给 IME 提供真实屏幕尺寸（见 screenSizeProvider）
        DynamicSymbols.screenSize = screenSizeProvider()
        cancelSlidePx = CANCEL_SLIDE_DP * resources.displayMetrics.density
        // 按设置页配置的默认模式初始化键盘（语音 / 26键中文 / 26键英文）
        keyboardMode = when {
            // 语音关闭时一律进拼音键盘，绝不进语音面板
            !prefs.voiceInputEnabled -> KeyboardMode.PINYIN
            prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_CN ||
                prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_EN -> KeyboardMode.PINYIN
            else -> KeyboardMode.VOICE
        }
        Diagnostics.i(TAG, "onCreate: IME 服务创建（默认模式=$keyboardMode）")

        // 可选词库包的三种「空闲」触发（见 maybeLoadOptionalDict）：
        // 息屏（用户锁屏）：最可靠的空闲信号
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    maybeLoadOptionalDict("息屏")
                }
            }
        }.also {
            runCatching {
                registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF))
            }.onFailure { e ->
                // 注册失败不影响功能：还有键盘收起与兜底两条路
                Diagnostics.w(TAG, "息屏接收器注册失败: ${e.message}")
            }
        }
        // 兜底：用户一直开着键盘打字，既不息屏也不收起，也要保证最终加载
        ui.postDelayed({ maybeLoadOptionalDict("兜底超时") }, OPTIONAL_FALLBACK_DELAY_MS)
        // 双拼键位表预热：7 套表按需构建（实测单套首次使用约 1~1.7ms，7 套合计约 8ms）；而键盘视图是在
        // onCreateInputView（首次弹出）里创建的，若在那里同步建表会拖慢首次弹出。
        // 故这里用独立守护线程预热（不占用 BackgroundIo，那是剪贴板 DB 的单线程队列，
        // 不该被 CPU 预热阻塞）。失败也不影响功能：首次访问会按需同步构建。
        Thread({
            // 后台优先级：此刻词库正在加载、用户可能已在打字，预热不该与之抢 CPU
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val costMs = runCatching { Shuangpin.warmUpAll() }.getOrDefault(-1L)
            Diagnostics.i(TAG, "双拼键位表预热完成: ${SHUANGPIN_TABLES.size} 套 / ${costMs}ms")
        }, "jinn-shuangpin-warmup").apply { isDaemon = true }.start()

        // 语音关闭时不创建任何语音组件：不 new AsrClient/MicRecorder，也不发起 WebSocket 连接。
        ensureVoiceReady()

        // 预加载拼音词库（约 1MB 文本，后台线程避免主线程卡顿）
        Thread {
            val start = System.currentTimeMillis()
            // 必须兜住异常：本线程是裸 Thread，load() 内部也没有 try/catch。
            // 一旦基础包解压失败（APK 安装不完整、存储故障等），异常会直接穿透到线程外，
            // 线程静默死亡：loaded 永远为 false -> 打字没有任何候选，
            // 而且「加载完成」「内存采样」这些日志全都执行不到，排查时毫无线索。
            runCatching {
                Diagnostics.i(TAG, "onCreate: 开始预加载拼音词库")
                PinyinEngine.load(this)
            }.onSuccess {
                Diagnostics.i(TAG, "onCreate: 词库加载完成，耗时 ${System.currentTimeMillis() - start}ms")
            }.onFailure {
                Diagnostics.e(TAG, "onCreate: 词库加载失败，候选将不可用: ${it.message}", it)
            }

            // 可选词库包（分类词库页下载的那些，可达 1.14M 词条、实测解析 21~34s）不再定时加载：
            // 原来固定「基础包就绪后 5 秒」开始，而那正是用户开始打字的时间点，重活抢 CPU/内存带宽
            // → 冷启动后「很卡」。改为等空闲信号（见 maybeLoadOptionalDict）。
            Diagnostics.i(TAG, "可选词库: 已改为空闲时加载（息屏 / 键盘收起后 / 兜底超时）")
        }.apply { isDaemon = true }.start()

        // 剪贴板历史：启用时监听系统剪贴板，按策略加密保存
        if (ClipboardPrefs.of(this).enabled) {
            clipboardController = ClipboardController(this).also { it.start() }
        }

        // 监听网络恢复：WiFi↔热点切换导致 IP 变化时主动重连
        registerNetwork()

        // 监听设置页「保存配置」广播：参数改动立即生效，无需重启输入法进程。
        // Android 13+ 动态注册必须显式声明导出标志：同进程应用内广播用 NOT_EXPORTED。
        // 只在 Android 13+ 注册：低版本没有 RECEIVER_NOT_EXPORTED 标志，
        // 动态注册默认导出且无权限保护，任意第三方 App 都能发这条 action 触发
        // refreshConfig（强制重连 + 反复启停剪贴板监听）。本 action 在应用内
        // 没有发送方（设置页走 killProcess 重启生效），低版本不注册不损失任何功能。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                registerReceiver(configReceiver, configFilter, Context.RECEIVER_NOT_EXPORTED)
            }.onFailure {
                Diagnostics.e(TAG, "onCreate: 注册配置广播失败", it)
            }
        }

    }

    /** 通过当前 InputConnection 粘贴文本（无效连接不崩溃）。返回是否成功提交。 */
    private fun pasteClipboardText(text: String): Boolean {
        // 采集侧的单条上限只拦新写入：库里可能有上限生效前留下的旧行（见 ClipboardStore.MAX_ITEM_BYTES
        // 的说明），那种量级直接 commitText 会让宿主卡住或提交失败 —— 在这里按同一上限拒收
        if (ClipboardStore.exceedsItemLimit(text)) {
            Diagnostics.w(
                TAG,
                "粘贴: 单条超过 ${ClipboardStore.MAX_ITEM_BYTES} 字节上限，已跳过 len=${text.length}",
            )
            android.widget.Toast.makeText(this, "内容过大，未粘贴", android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        val connection = currentInputConnection
        if (connection == null) {
            // 剪贴板面板在前台时 IME 可能无有效连接：暂存，并主动唤起键盘，
            // 触发 onStartInputView → flushPendingPaste 自动提交。
            // 此处未真正提交，返回 false（剪贴板面板不关闭，用户可等待或返回）。
            Diagnostics.w(TAG, "粘贴: 当前无有效 InputConnection，暂存并唤起键盘")
            pendingPasteText = text
            pendingPasteAt = System.currentTimeMillis()
            // 锁定目标输入框：暂存只在「同一个输入框重新聚焦」时提交
            pendingPasteFieldKey = pendingPasteFieldKey(getCurrentInputEditorInfo())
            // 自动唤起键盘关闭时不主动 requestShowSelf（即使调用了也会被
            // onShowInputRequested 拒绝并再次触发隐藏逻辑，这里直接不发起）。
            if (!prefs.autoShowKeyboard) {
                Diagnostics.i(TAG, "自动唤起键盘：关闭，暂存粘贴文本但不唤起 IME")
                return false
            }
            // requestShowSelf 是 API 28 才有的方法（minSdk 26）：低版本直接跳过，
            // 否则会抛 NoSuchMethodError，虽被 runCatching 兜住不崩溃，
            // 但功能静默失效且空 onFailure 违反「绝不静默吞异常」的底线。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { requestShowSelf(0) }.onFailure {
                    Diagnostics.w(TAG, "requestShowSelf 失败: ${it.message}")
                }
            } else {
                Diagnostics.i(TAG, "API < 28，不支持 requestShowSelf，跳过主动唤起")
            }
            return false
        }
        val ok = runCatching { connection.commitText(text, 1) }.getOrDefault(false)
        Diagnostics.i(TAG, "粘贴: len=${text.length} success=$ok")
        // 失败提示统一由 IME 出：面板只拿到 true/false，分不清「连接为空 → 已暂存、重聚焦时
        // 自动提交」与「真提交失败」，在那里弹提示会与上面的「内容过大」叠成两条、也会冤枉暂存态
        if (!ok) {
            android.widget.Toast.makeText(this, "粘贴失败", android.widget.Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    /**
     * 内嵌剪贴板面板点击记录：直接用当前 InputConnection 粘贴。
     * 与 [pasteClipboardText] 同逻辑；面板在 IME 内，连接必然有效。
     */
    private fun pasteClipboardTextInternal(text: String): Boolean = pasteClipboardText(text)

    /**
     * 设置页保存后广播：强制刷新连接与键盘配置。
     * 广播由 [SettingsActivity.saveAndTest] 发出，同进程内即时送达。
     */
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Diagnostics.i(TAG, "onReceive: 收到配置更新广播")
            refreshConfig()
        }
    }

    private val configFilter = IntentFilter().apply { addAction(ACTION_CONFIG_UPDATED) }

    // ── 方向控制 + 文字拖选状态机 ──────────────────────────

    /** 拖选模式是否激活 */
    @Volatile
    private var selectionActive = false

    /** 拖选起始锚点（进入拖选时固定，绝不变），-1 表示未设置 */
    private var selectionAnchor = -1

    /** 拖选焦点（方向键控制的对象，独立于 Android 的 selectionStart/End） */
    private var selectionFocus = -1

    /**
     * 我们请求设置过的选区（`InputConnection.setSelection`，保留最近几次）。
     *
     * 只用于在 [onUpdateSelection] 里区分「这次变化是我们自己造的」与「宿主改的」，后者必须让拖选状态失效。
     * 用队列而不是单个值：回调可能**乱序投递**（见 [SelectionExpectations] 的 KDoc），
     * 单值会被后一次的回调消费掉、让前一次的回调被误判成外部变化。
     */
    private val selectionExpectations = SelectionExpectations()

    /**
     * 执行方向控制动作（通过当前 InputConnection）。
     *
     * 拖选状态机（NORMAL_CURSOR ↔ TEXT_SELECTION_ACTIVE）：
     *  - 点击中心 ●：未激活 → 固定当前光标为 Anchor 并激活；已激活 → 清除选区、
     *    退出拖选、光标停在 Focus 位置（不跳回 Anchor）
     *  - 激活时方向键/行首/行末：只移动 Focus，Anchor 固定不变，
     *    选区 = setSelection(min(Anchor,Focus), max(Anchor,Focus))
     *  - 复制：复制选中文字，保持选区与拖选模式
     *  - 粘贴：有选区 → 替换；无选区 → 光标处粘贴
     */
    private fun executeDirection(action: PinyinKeyboardView.DirectionAction) {
        when (action) {
            PinyinKeyboardView.DirectionAction.TOGGLE_SELECTION -> toggleSelection()
            PinyinKeyboardView.DirectionAction.COPY -> copySelection()
            PinyinKeyboardView.DirectionAction.PASTE -> pasteClipboard()
            PinyinKeyboardView.DirectionAction.UP,
            PinyinKeyboardView.DirectionAction.DOWN,
            PinyinKeyboardView.DirectionAction.LEFT,
            PinyinKeyboardView.DirectionAction.RIGHT,
            PinyinKeyboardView.DirectionAction.LINE_START,
            PinyinKeyboardView.DirectionAction.LINE_END -> moveOrExtend(action)
        }
    }

    /**
     * 清空拖选状态（IME 侧 + 同步键盘侧），供「会话切换 / 面板关闭 / 面板重开」共用。
     *
 * 三个入口共用一份实现：原先三处各自实现、容易漏改（见 onClipboardStateChanged 的注释）。
     */
    private fun clearSelectionState() {
        selectionActive = false
        selectionAnchor = -1
        selectionFocus = -1
        selectionExpectations.clear()
        pinyinKeyboard?.setSelectionActive(false)
    }

    /** 请求宿主设置选区，并记下这次期望值（供 [onUpdateSelection] 辨认自身动作） */
    /**
     * 请求宿主移动光标 / 选区。
     *
     * `setSelection` 是**同步 Binder 调用**：连接在请求期间失效（宿主进程死亡、编辑器重建）会抛
     * DeadObjectException，未捕获时会从提交路径一路冒到主线程把 IME 进程带走（2026-10-01 复审 L-238）。
     * 失败与「宿主没接受」同路处理 —— 调用方（[moveCursorByOffset]）本来就要复核结果。
     */
    private fun applySelection(
        connection: android.view.inputmethod.InputConnection,
        start: Int,
        end: Int,
    ): Boolean {
        val ok = runCatching { connection.setSelection(start, end) }
            .onFailure { Diagnostics.w(TAG, "选区/光标移动调用失败 ${it.javaClass.simpleName}") }
            .isSuccess
        // 期望值只在**调用真的落地**后才登记：失败那次若也登记，之后遇到同坐标的外部改动会被
        // 当成自有动作（拖选不退出、Anchor 陈旧）（2026-10-01 修复 L-242）
        if (ok) selectionExpectations.note(start, end)
        return ok
    }

    /** 中心 ●/◉ 开关：切换拖选模式 */
    private fun toggleSelection() {
        val connection = currentInputConnection ?: return
        if (!selectionActive) {
            // 激活：固定当前光标位置为 Anchor，Focus 初始等于 Anchor
            val sel = currentSelectionRange(connection) ?: return
            selectionActive = true
            selectionAnchor = sel.start
            selectionFocus = sel.end
            Diagnostics.i(TAG, "拖选激活: Anchor=$selectionAnchor Focus=$selectionFocus")
        } else {
            // 取消：清除选区 + 退出拖选 + 光标停在当前 Focus（不跳回 Anchor）
            val end = selectionFocus.coerceAtLeast(0)
            selectionActive = false
            selectionAnchor = -1
            selectionFocus = -1
            if (applySelection(connection, end, end)) {
                Diagnostics.i(TAG, "拖选取消: 光标停在 Focus=$end")
            } else {
                Diagnostics.w(TAG, "拖选取消: 宿主未接受（目标 $end）")
            }
        }
        pinyinKeyboard?.setSelectionActive(selectionActive)
    }

    /** 方向键 / 行首 / 行末：拖选激活时移动 Focus，否则普通光标移动 */
    private fun moveOrExtend(action: PinyinKeyboardView.DirectionAction) {
        val connection = currentInputConnection ?: return
        if (selectionActive) {
            extendSelection(connection, action)
        } else {
            moveCursor(connection, action)
        }
    }

    /**
     * 普通光标模式：只通过 InputConnection.setSelection 移动文本光标。
     *
     * 绝不发送 DPAD / MOVE_HOME / MOVE_END KeyEvent，那些会触发
     * 目标 APP 的 View Focus Navigation（按钮/输入框/控件获得焦点）。
     * 正确行为：读取当前光标位置，计算新位置，setSelection 更新光标，
     * View Focus 保持不变。
     */
    private fun moveCursor(connection: android.view.inputmethod.InputConnection, action: PinyinKeyboardView.DirectionAction) {
        val range = currentSelectionRange(connection) ?: return
        // 全程按窗口内下标算，最后再加回 startOffset 交给宿主：selectionStart/End 是全文
        // 绝对下标，而 range.text 可能只是光标附近的窗口（见 [currentSelectionRange]）。
        val cursor = range.start - range.startOffset
        val newRel = when (action) {
            // 与拖选同一套口径（L-120）：左右必须按**码点**走。此前这里另抄了一份 `±1`，
            // 遇 emoji（代理对）会停在字符中间，光标处输入就把一个 emoji 撕成两半。
            PinyinKeyboardView.DirectionAction.LEFT -> TextSelection.stepByCodePoint(range.text, cursor, -1)
            PinyinKeyboardView.DirectionAction.RIGHT -> TextSelection.stepByCodePoint(range.text, cursor, +1)
            PinyinKeyboardView.DirectionAction.UP -> TextSelection.moveLine(range.text, cursor, up = true)
            PinyinKeyboardView.DirectionAction.DOWN -> TextSelection.moveLine(range.text, cursor, up = false)
            PinyinKeyboardView.DirectionAction.LINE_START -> TextSelection.lineStart(range.text, cursor)
            PinyinKeyboardView.DirectionAction.LINE_END -> TextSelection.lineEnd(range.text, cursor)
            else -> cursor
        }
        if (newRel != cursor) {
            val newPos = newRel + range.startOffset
            // 按返回值决定说什么：宿主没接受还报「光标移动」就是假日志（2026-10-01 修复 L-242）
            if (applySelection(connection, newPos, newPos)) {
                Diagnostics.i(TAG, "光标移动: 窗口内 $cursor → $newRel（offset=${range.startOffset}）")
            } else {
                Diagnostics.w(TAG, "光标移动: 宿主未接受（目标 $newPos）")
            }
        }
    }

    /**
     * 拖选扩展：只移动 Focus，Anchor 固定不变。
     * 选区始终 = setSelection(min(Anchor,Focus), max(Anchor,Focus))，
     * 不依赖 Android 当前的 selectionStart/End（避免归一化导致 Anchor/Focus 漂移）。
     * 行首/行末把 Focus 跳到行首/行末；上/下按行移动并保持列位置。
     */
    private fun extendSelection(connection: android.view.inputmethod.InputConnection, action: PinyinKeyboardView.DirectionAction) {
        val range = currentSelectionRange(connection) ?: return
        // Anchor/Focus 必须都已建立：任一为负（键盘侧在 IME 未记 Anchor 时置了拖选态）
        // 会让下面的归一化算出负下标，而宿主 Editable 收到越界的 setSelection 会抛异常。
        if (selectionAnchor < 0 || selectionFocus < 0) {
            Diagnostics.w(TAG, "拖选扩展: Anchor/Focus 无效（$selectionAnchor/$selectionFocus），退出拖选")
            clearSelectionState()
            return
        }
        // 与 [moveCursor] 同一套坐标：端点先换算到窗口内，算完再加回 startOffset
        val focusRel = TextSelection.toWindowOffset(selectionFocus, range.startOffset, range.textLength)
        val anchorRel = TextSelection.toWindowOffset(selectionAnchor, range.startOffset, range.textLength)
        if (focusRel == null || anchorRel == null) {
            Diagnostics.w(TAG, "拖选端点不在当前文本窗口内（offset=${range.startOffset}），退出拖选")
            clearSelectionState()
            return
        }
        val textRange = TextSelection.Range(anchorRel, focusRel, range.textLength, range.text)
        val newFocusRel = TextSelection.nextFocus(textRange, focusRel, action)
        selectionFocus = newFocusRel + range.startOffset
        // Anchor 固定，Focus 可移动：选区两端取 min/max
        val (start, end) = TextSelection.normalizedSelection(selectionAnchor, selectionFocus)
        if (applySelection(connection, start, end)) {
            Diagnostics.i(TAG, "拖选扩展: Anchor=${selectionAnchor} Focus=$selectionFocus → 选区[$start,$end]")
        } else {
            // 宿主没接受就别再维持拖选状态：Anchor 与实际选区已经脱钩（2026-10-01 修复 L-242）
            Diagnostics.w(TAG, "拖选扩展: 宿主未接受（选区[$start,$end]），退出拖选")
            clearSelectionState()
        }
    }

    /**
     * 读取当前选区范围（未选中时 start == end == 光标位置）。
     *
     * 两类下标必须分清：`selectionStart/End` 是全文绝对下标，而 `text` 在长文档下可能
     * 只是光标附近的一段窗口（`startOffset` 是它在全文里的起点）。端点落在窗口之外时无从
     * 计算，返回 null 让调用方放弃本次操作。旧实现直接拿绝对下标去索引窗口文本，
     * 行移动 / 行首行末会算出无关位置，甚至把光标挪到「窗口长度」那个绝对下标上。
     */
    private fun currentSelectionRange(connection: android.view.inputmethod.InputConnection): SelectionRange? {
        // 同步 Binder 调用可能抛（与 readTextBeforeCursor 同源，2026-09-30 第二轮审查）：这里是编辑键
        // （全选 / 复制 / 拖选）与「译文提交前的选区检查」的公共入口，异常未捕获会崩 IME 进程。
        // null 是**三态里的一态**（2026-10-01 复审 L-230）：既可能是「确实没有选区」，也可能是
        // 「宿主不支持 `getExtractedText` / 大文档下抛事务异常」。调用方必须区分对待 ——
        // 编辑键（全选 / 复制）按「无选区」处理无碍，**译文提交**绝不能（fail-closed）。
        val extracted = runCatching {
            // 与读取路径同口径设 hintMaxChars（2026-10-01 审查 L-263）：空构造意味着不限长，
            // 长文档下每次选区探测都会让宿主回包整篇窗口、落在主线程；事务过大时抛异常被吞成
            // null，又会落进「按无选区放行」那条路。这里只要位置，给个明确上限即可。
            connection.getExtractedText(
                android.view.inputmethod.ExtractedTextRequest().apply {
                    hintMaxChars = TranslationText.MAX_READ_CHARS
                },
                0,
            )
        }.getOrNull() ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        val start = extracted.selectionStart.coerceAtLeast(0)
        val end = extracted.selectionEnd.coerceAtLeast(start)
        val text = extracted.text?.toString().orEmpty()
        val startOffset = extracted.startOffset.coerceAtLeast(0)
        if (TextSelection.toWindowOffset(start, startOffset, text.length) == null ||
            TextSelection.toWindowOffset(end, startOffset, text.length) == null
        ) {
            Diagnostics.w(
                TAG,
                "光标/选区不在当前文本窗口内（offset=$startOffset len=${text.length} sel=[$start,$end]），跳过本次操作",
            )
            return null
        }
        return SelectionRange(start, end, text.length, text, startOffset)
    }

    /**
     * [canAppendTranslation] 的结论。
     *
     * 不能只回 Boolean（2026-10-01 修复 L-284）：「有选区」与「宿主答不出有没有选区」是两种要给
     * **不同提示**的拒绝 —— 前者用户取消选中就好，后者他做什么都没用。合并成一句「请先取消选中的
     * 内容再翻译」，等于把后者的锅扣在前者头上。
     */
    /**
     * 「选区已经读过」的标记（2026-10-01 修复 L-325）。
     *
     * 光用 `SelectionRange?` 表达不了「读过、结论是无法确定」—— 传 null 会被 `?:` 当成「没读过」而重读
     * 一次，而 `currentSelectionRange` 恒返回 null 的宿主（WebView 类）**每次**都是这个结果，
     * 正是 L-315 最想省下读取的那一类。包一层就能把两种含义分开。
     */
    private class SelectionProbe(val range: SelectionRange?)

    private enum class AppendCheck { OK, SELECTION_PRESENT, HOST_UNREADABLE }

    /**
     * 「此刻能否安全追加译文」的判据。
     *
     * 与 [currentSelectionRange] 的差别在**取不到精确选区时怎么取舍**：那个函数只有
     * `getExtractedText` 一条路径，而 WebView 类宿主（Via / 系统浏览器内核的搜索框）对它恒返回
     * null —— 据此整体拒绝会让翻译在这些宿主上**完全不生效**，用户看到的只是「点了没反应」。
     *
     * 判据分两层：
     * 1. `getExtractedText` 成功 ⇒ 以它的 start/end 为准（最准）；start != end 即有选区；
     * 2. 取不到 ⇒ 用 `getSelectedText` 问「有没有选中内容」：非空 = 有选区（拒绝追加），
     *    **明确为空 = 放行**，**抛异常 = 问不出来**（[AppendCheck.HOST_UNREADABLE]，不给结论）。
     *
     * 放宽的代价由提交路径兜住：[appendTranslation] 仍逐字比对请求时刻的原文，用户在请求期间
     * 选择内容会改变 `getTextBeforeCursor` 的读数并失配，结果被丢弃而不是覆盖选中文本。
     */
    private fun canAppendTranslation(
        connection: android.view.inputmethod.InputConnection,
        known: SelectionProbe? = null,
    ): AppendCheck {
        // [known] 由调用方传入时直接用 —— 同一次点击里 `readSelectedText` 刚读过同一份事实，
        // 再读一遍等于白付一次整窗口的 `getExtractedText`（最多 10 万字符，2026-10-01 修复 L-315）。
        // ⚠ 判的必须是 `known` **本身**、不是 `known?.range`（2026-10-01 修复 L-325）：后者在
        // 「读过、但结论是无法确定」时会退化成「没读过」又去读一次 —— 恰好是 WebView 类宿主。
        val range = if (known != null) known.range else currentSelectionRange(connection)
        if (range != null) {
            return if (range.start == range.end) AppendCheck.OK else AppendCheck.SELECTION_PRESENT
        }
        val selected = runCatching { connection.getSelectedText(0)?.toString() }.getOrElse {
            // **抛异常 ≠ 没有选区**（2026-10-01 审查 L-262）：宿主没实现该 API、或文档过大导致
            // 事务异常，都会走到这里。此前一律放行，于是"宿主有实时选区但两个 API 都不报"时，
            // 提交前的原文比对会通过（选区全程没动），`commitText` 按替换语义删掉用户选中的文本。
            // 现在按危险处理 —— 只有**明确返回空**才算「确实没有选中内容」。
            Diagnostics.w(TAG, "翻译: getSelectedText 抛异常，无法确认选区，拒绝追加")
            return AppendCheck.HOST_UNREADABLE
        }
        if (!selected.isNullOrEmpty()) {
            Diagnostics.w(TAG, "翻译: 宿主不提供精确选区，但 getSelectedText 报有选中内容，拒绝追加")
            return AppendCheck.SELECTION_PRESENT
        }
        // 正常路径（WebView 类宿主每次翻译都会走到这里），用 V 级，别把诊断包的 W 段占满
        Diagnostics.v(TAG, "翻译: 宿主不提供精确选区，按「无选区」放行（提交时仍逐字比对原文）")
        return AppendCheck.OK
    }

    /**
     * 读当前选中的文本。
     *
     * 三态：**null** = 宿主读不到（调用方按「读不到」处理）；**空串** = 确实没有选中内容；
     * **非空** = 选中的那一段。
     *
     * 精确选区可用时直接从窗口文本里切 —— `getSelectedText` 在部分宿主上只对 URI / 富文本有实现，
     * 拿它当唯一来源会对普通文本框漏判；两者都取不到才返回 null。
     */
    private fun readSelectedText(
        connection: android.view.inputmethod.InputConnection,
        known: SelectionProbe? = null,
    ): String? {
        val range = if (known != null) known.range else currentSelectionRange(connection)
        if (range != null) {
            if (range.start >= range.end) return ""
            val s = TextSelection.toWindowOffset(range.start, range.startOffset, range.text.length)
                ?: return null
            val e = TextSelection.toWindowOffset(range.end, range.startOffset, range.text.length)
                ?: return null
            return range.text.substring(s, e.coerceIn(s, range.text.length))
        }
        return runCatching { connection.getSelectedText(0)?.toString() ?: "" }.getOrNull()
    }

    /**
     * 搜索态的「作用于宿主」面板动作一律拒绝（纵深防御）。
     *
     * 主守卫在 `PinyinKeyboardView.renderFunctionPanel`：搜索态候选栏只渲染「退出搜索」，
     * 全选/复制/方向/粘贴都不出现。这里再挡一道，保证任何将来新增的调用路径
     * （无障碍、外部触发、新的面板入口）都不会让它们落到宿主输入框上 ，
     * 搜索态下 26 键只作用于搜索框，面板动作必须一致。
     */
    private fun rejectedBySearchPanel(action: String): Boolean {
        if (pinyinKeyboard?.isSearchActive() != true) return false
        Diagnostics.w(TAG, "搜索态下忽略「$action」（避免作用于宿主输入框）")
        return true
    }

    /** 复制选中文字到系统剪贴板（保持选区与拖选模式） */
    private fun copySelection() {
        val connection = currentInputConnection ?: return
        // 与 moveCursor/extendSelection 同一套坐标：currentSelectionRange 已把
        // 「绝对下标 vs 窗口文本」的错位挡在外面（端点不在窗口内时返回 null）。
        // 旧实现只做 coerceIn 钳位，窗口化时会把绝对下标当窗口下标用，
        // 复制到与选区无关的内容（静默错误）或截出半截选区。
        val range = currentSelectionRange(connection) ?: return
        if (range.end <= range.start) {
            Diagnostics.w(TAG, "复制: 无选中文字")
            return
        }
        // 两个端点都已由 currentSelectionRange 校验过「在窗口内」，这里仍按可空处理，
        // 防御两次读取之间窗口发生变化（null 即放弃本次操作）。
        val from = TextSelection.toWindowOffset(range.start, range.startOffset, range.textLength)
        val to = TextSelection.toWindowOffset(range.end, range.startOffset, range.textLength)
        if (from == null || to == null || to <= from) {
            Diagnostics.w(TAG, "复制: 选区不在当前文本窗口内，跳过")
            return
        }
        val sel = range.text.substring(from, to)
        // 与粘贴链路同款的单条上限闸（2026-10-02 修复 L-406）：这是全仓**唯一**写系统剪贴板的
        // 地方，此前既无尺寸闸也无兜底 —— 选中整篇长文时 `setPrimaryClip` 会把整段塞进 Binder
        // 事务（宿主与监听器还要各读一遍），超限即抛异常、直接崩 IME 进程。
        if (ClipboardStore.exceedsItemLimit(sel)) {
            Diagnostics.w(TAG, "复制: 选区超过单条上限，未复制 len=${sel.length}")
            toast(TEXT_COPY_TOO_LONG)
            return
        }
        val clip = android.content.ClipData.newPlainText("jinn_selection", sel)
        runCatching { clipboardManager.setPrimaryClip(clip) }
            .onFailure {
                Diagnostics.w(TAG, "复制失败: ${it.javaClass.simpleName}")
                // 用户按了「复制」不能毫无反应（2026-10-02 修复 L-439）：与「超长」那条提示同款，
                // 与粘贴侧的分层提示口径一致
                toast(TEXT_COPY_FAILED)
            }
        // 不再手动入库：setPrimaryClip 会触发 ClipboardController 的剪贴板监听，
        // 由它统一保存。此前这里也写一次库，导致同一条内容 upsert 两次，
        // 且手动传入的 sourceAppName（"本输入法"）会被监听路径按包名推断的值覆盖。
        // 统一入口后，分类逻辑只存在于 ClipboardStore.save 一处。
        Diagnostics.i(TAG, "复制: 选中 ${sel.length} 字（保持选区与拖选模式）")
    }

    /**
     * 粘贴（功能面板「粘贴」键）：有选区替换，无选区在光标处插入。
     *
     * 内容来自**系统剪贴板**（别的应用复制过来的整篇文档可能几 MB），必须与剪贴板面板
     * 走同一条实现：单条上限闸 + `runCatching` 包住 `commitText`。裸提交会撞 Binder
     * 事务上限抛 `TransactionTooLargeException`（本项目 2026-09-16 已在同类链路上实测过），
     * 未捕获时直接崩掉整个 IME 进程。
     */
    /** 提示（粘贴失败的几种原因要让人看见；文案在代码里下发，与文件内既有 Toast 写法一致） */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun pasteClipboard() {
        val clip = clipboardManager.primaryClip
        // 0 条目守卫不能省：`ClipData(label, mimeTypes, emptyArray())` 是合法构造（部分应用与
        // 厂商定制会写出来），`getItemAt(0)` 会抛 IndexOutOfBoundsException，而这里不在任何
        // runCatching 内 —— 异常落在主线程点击回调上会崩掉整个 IME 进程。
        // 与 ClipboardController.extractAndSave 的守卫同一口径。
        if ((clip?.itemCount ?: 0) == 0) {
            Diagnostics.w(TAG, "粘贴: 剪贴板无条目（itemCount=0）")
            return
        }
        // 与采集侧**共用**取文入口：URI 档带字节预算 + MIME 三态判定 + 失败日志（BUG.md L-177 / L-178）。
        // 并且**必须带时间预算**：粘贴要在主线程上拿到正文（`commitText` 要用当前 InputConnection），
        // 字节预算管不住「慢 provider」——一次 IPC 读 256KB 也能拖出 ANR。
        val result = ClipboardStore.itemTextResult(
            clip!!.getItemAt(0),
            this,
            deadlineNanos = System.nanoTime() + ClipboardStore.PASTE_READ_BUDGET_MS * 1_000_000L,
        )
        // 失败**分层**给提示（BUG.md L-183）：先前四种失败都落进「剪贴板为空」，
        // 用户按了没反应、日志还说「空」；「内容过大，未粘贴」这句原先由 pasteClipboardText 弹出，
        // 但读入即止之后那一步已经到不了 —— 必须在这里补回来。
        val text = when (result) {
            is ClipboardStore.ItemText.Ok -> result.text
            ClipboardStore.ItemText.TooLarge -> {
                Diagnostics.w(TAG, "粘贴: 单条超过 ${ClipboardStore.MAX_ITEM_BYTES} 字节上限，读入即止")
                toast("内容过大，未粘贴")
                return
            }
            ClipboardStore.ItemText.NotTextual -> {
                Diagnostics.w(TAG, "粘贴: 剪贴板不是文本内容（未读流或解码像二进制）")
                toast("剪贴板不是文本内容")
                return
            }
            ClipboardStore.ItemText.TimedOut -> {
                Diagnostics.w(TAG, "粘贴: 读取剪贴板超时（预算 ${ClipboardStore.PASTE_READ_BUDGET_MS}ms）")
                toast("读取剪贴板超时")
                return
            }
            ClipboardStore.ItemText.Failed -> {
                Diagnostics.w(TAG, "粘贴: 读不到剪贴板内容（打不开流或读失败）")
                toast("读不到剪贴板内容")
                return
            }
        }
        if (text.isEmpty()) {
            Diagnostics.w(TAG, "粘贴: 剪贴板为空")
            return
        }
        pasteClipboardText(text)
    }

    /**
     * `getExtractedText` 的结果。
     *
     * 两类下标：`start`/`end` 是全文绝对下标（宿主给的就是这个），而 `text` 可能只是
     * 光标附近的一段窗口、`startOffset` 是它在全文里的起点，拿 `text` 算行移动前必须
     * 先减 `startOffset`，算完再加回去（见 [moveCursor] / [extendSelection]）。
     * `textLength` 是窗口长度（窗口内的上界），不是全文长度。
     */
    private data class SelectionRange(
        val start: Int,
        val end: Int,
        val textLength: Int,
        val text: String,
        val startOffset: Int,
    )

    /** 系统剪贴板 */
    private val clipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    /** 立即生效：强制按新地址重连 WebSocket + 重新同步键盘输入方案 */
    fun refreshConfig() {
        // 地址/端口变化：旧连接指向旧服务器，强制断开重连
        Diagnostics.i(TAG, "refreshConfig: 强制重连 ${prefs.wsUrl}")
        asr?.connect(force = true)
        // 双拼/中英文方案变化：立即重新套用，键盘无需重建
        pinyinKeyboard?.configure(
            scheme = prefs.effectiveShuangpinScheme,
            english = prefs.keyboardEnglish,
        )
        // 剪贴板历史开关变化：按最新偏好启停监听
        val cpEnabled = ClipboardPrefs.of(this).enabled
        if (cpEnabled && clipboardController == null) {
            clipboardController = ClipboardController(this).also { it.start() }
        } else if (!cpEnabled) {
            clipboardController?.stop()
            clipboardController = null
        }
    }

    /**
     * 半透明键盘的最后一层：系统导航栏（SystemUI 的 NavigationBar0 铺在屏幕最底、盖在键盘之上）。
     *
     * 它不属于键盘视图树，底色由「提供系统栏颜色的窗口」给出，键盘可见时这个窗口就是 IME 窗口。
     * 真机实证：同一张纯黑页面上，键盘收起时屏幕最底纯黑，弹出后变成一条固定的浅色实心带，
     * 且不随键盘透明度滑块变化（键盘里的铺底面早已全部带 alpha）。
     * IME 窗口继承应用主题的 `android:navigationBarColor`（不透明 `app_bg`），键盘窗口本身铺满到屏幕底，
     * 因此把它改透明后，透出的正是键盘背板。
     *
     * 每次 [onStartInputView] 都要设一遍：框架显示窗口时会用主题属性重写窗口参数。
     */
    private fun applyNavBarTransparency() {
        val w = window?.window ?: return
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            w.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 系统默认给非不透明导航栏叠一层对比度纱罩，会把透明底又压灰，关掉
            w.isNavigationBarContrastEnforced = false
        }
        Diagnostics.i(TAG, "导航栏: navigationBarColor=${String.format("#%08X", w.navigationBarColor)}")
    }

    override fun onCreateInputView(): View {
        Diagnostics.i(TAG, "onCreateInputView: 键盘视图创建")

        // 半透明键盘：窗口背景（主题 windowBackground）与框架容器的不透明底色会把 alpha
        // 全吃在窗口内部，这里先清窗口背景；祖先链（含框架容器/DecorView）上的背景
        // 等视图真正挂到窗口后再清（见 clearOpaqueAncestorBackgrounds）。
        // 0% 透明度时键盘面完全不透明，观感与历史一致。
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        applyNavBarTransparency()

        // 主题：键盘视图一律用「按 Prefs 决策后的 Context」创建，亮白 / 暗黑 / 定时模式下
        // uiMode 在这里被覆盖、色板随之锁定；「跟随系统」时该方法是恒等返回，
        // 系统深浅色变化由系统重建 IME 自动生效。
        val themeCtx = ThemeManager.themedContext(this, prefs)
        keyboardThemeCtx = themeCtx
        appliedThemeDark = ThemeManager.isDark(this, prefs)

        // 语音键盘
        val voice = LayoutInflater.from(themeCtx).inflate(R.layout.keyboard, null)
        micButton = voice.findViewById(R.id.mic_button)
        statusDot = voice.findViewById(R.id.status_dot)
        statusLabel = voice.findViewById(R.id.status_label)
        hintLabel = voice.findViewById(R.id.hint_label)
        micButton?.setOnTouchListener { view, event ->
            val consumed = onMicTouch(event)
            // 无障碍：抬手时补 performClick。麦克风按钮没有 OnClickListener，
            // performClick 只向无障碍服务补发一次点击事件，不影响手势判定与录音逻辑。
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            consumed
        }
        voice.findViewById<View>(R.id.status_bar).setOnClickListener { openSettings() }
        voice.findViewById<View>(R.id.key_comma).setOnClickListener { commit("，") }
        voice.findViewById<View>(R.id.key_period).setOnClickListener { commit("。") }
        voice.findViewById<View>(R.id.key_space).setOnClickListener { commit(" ") }
        voice.findViewById<View>(R.id.key_enter).setOnClickListener { performEnter() }
        // 语音键盘的「键盘」键：切到拼音键盘；长按仍切输入法
        voice.findViewById<View>(R.id.key_switch).setOnClickListener { switchToPinyinKeyboard() }
        voice.findViewById<View>(R.id.key_switch).setOnLongClickListener {
            showImePicker()
            true
        }
        bindBackspace(voice.findViewById(R.id.key_backspace))

        // 拼音键盘（同样用决策后的 Context，见上）
        val pinyin = PinyinKeyboardView(themeCtx).apply {
            listener = object : PinyinKeyboardView.Listener {
                override fun onCommitText(text: String) = commit(text)
                override fun onCommitSpace() = commit(" ")
                override fun onEnter() = performEnter()
                override fun onBackspace() {
                    Diagnostics.v(TAG, "退格删已上屏文本")
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                override fun onDeleteAll() = deleteAllText()
                override fun onVoiceRequested() = switchToVoiceKeyboard()
                override fun onOpenClipboard() {
                    Diagnostics.i(TAG, "功能面板: 打开剪贴板")
                    // 内嵌面板：替换 26 键字母区（候选栏/底部栏/InputConnection 保持）
                    clipboardPanelOpen = true
                    pinyinKeyboard?.showClipboardPanel()
                }
                override fun onPasteText(text: String): Boolean {
                    Diagnostics.i(TAG, "剪贴板面板: 请求粘贴 len=${text.length}")
                    return pasteClipboardTextInternal(text)
                }
                override fun onClipboardStateChanged(active: Boolean) {
                    Diagnostics.i(TAG, "剪贴板面板状态: active=$active")
                    if (!active) {
                        clipboardPanelOpen = false
                        // 面板打开/关闭时清除拖选状态（IME + 键盘两侧一起清，见 clearSelectionState）
                        clearSelectionState()
                    }
                }
                override fun onDirectionAction(action: PinyinKeyboardView.DirectionAction) {
                    if (rejectedBySearchPanel("方向")) return
                    Diagnostics.i(TAG, "方向按键: $action")
                    executeDirection(action)
                }
                override fun onSelectionModeChanged(active: Boolean) {
                    Diagnostics.i(TAG, "拖选模式变化: $active")
                    // 面板关闭/键盘重置时同步清除拖选状态，避免 anchor/focus 残留
                    if (!active) {
                        clearSelectionState()
                    } else {
                        pinyinKeyboard?.setSelectionActive(true)
                    }
                }
                override fun onPasteClipboard() {
                    if (rejectedBySearchPanel("粘贴")) return
                    Diagnostics.i(TAG, "功能面板: 粘贴剪贴板")
                    pasteClipboard()
                }
                override fun onSelectAll() {
                    if (rejectedBySearchPanel("全选")) return
                    Diagnostics.i(TAG, "功能面板: 全选")
                    selectAllText()
                }
                override fun onCopy() {
                    if (rejectedBySearchPanel("复制")) return
                    Diagnostics.i(TAG, "功能面板: 复制")
                    copySelection()
                }
                override fun onHideKeyboard() {
                    Diagnostics.i(TAG, "功能面板: 收起键盘")
                    // 只隐藏输入面板，服务保持运行，点击输入框再次唤醒
                    runCatching { requestHideSelf(0) }
                        .onFailure { Diagnostics.w(TAG, "收起键盘失败: ${it.message}") }
                }
                override fun onTranslate() {
                    if (rejectedBySearchPanel("翻译")) return
                    // 剪贴板面板打开时也拦（2026-10-02 修复）：翻译读的是**宿主输入框**的原文，
                    // 而面板里任点一条历史就是 `commitText` 改宿主文本 ⇒ 这次已经发出的（已计费的）
                    // 请求回来必然被判「输入已变化」丢弃，提示还会说成用户自己改了输入（他并没有打字）。
                    // 与搜索态同款：宁可当场拦下，也不发无谓请求。面板上本来就有「返回」键可退出。
                    if (pinyinKeyboard?.isClipboardActive() == true) {
                        Diagnostics.w(TAG, "剪贴板面板打开时忽略「翻译」（避免请求被随后的粘贴作废）")
                        toast(TEXT_TRANSLATE_CLIPBOARD_OPEN)
                        return
                    }
                    Diagnostics.i(TAG, "功能面板: 翻译")
                    startTranslate()
                }
            }
            configure(
                scheme = prefs.effectiveShuangpinScheme,
                // 26键英文模式：启动时强制英文；其余模式沿用键盘英文偏好
                english = if (prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_EN) {
                    true
                } else {
                    prefs.keyboardEnglish
                },
            )
        }
        pinyinKeyboard = pinyin

        // 双模式容器：默认语音键盘，键盘模式时切到拼音键盘
        val container = FrameLayout(this)
        keyboardContainer = container
        container.addView(voice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(pinyin, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 键盘视图每次创建都重新套用默认模式（InputMethodService 可能复用实例，
        // 仅靠 onCreate 设置 keyboardMode 在复用场景下不生效）
        keyboardMode = when {
            !prefs.voiceInputEnabled -> KeyboardMode.PINYIN
            prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_CN ||
                prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_EN -> KeyboardMode.PINYIN
            else -> KeyboardMode.VOICE
        }
        applyKeyboardMode()
        // 不在这里恢复剪贴板面板（见 onStartInputView 注释，恢复会诱发
        // IME 窗口反复 relayout 循环，反而导致面板抖动/空白）。
        // 半透明键盘：语音面板同一套底色参数；此刻视图还没挂到窗口上，
        // 等挂上后再清祖先的不透明底色
        applyTransparencyToVoicePanel()
        container.post { clearOpaqueAncestorBackgrounds(container) }
        return container
    }

    /** 语音面板最小编辑键的 id（半透明键盘按档重建它们的键面背景；改 keyboard.xml 时同步） */
    private val voiceEditKeyIds = intArrayOf(
        R.id.key_switch, R.id.key_comma, R.id.key_period,
        R.id.key_space, R.id.key_backspace, R.id.key_enter,
    )

    /**
     * 半透明键盘：语音面板与 26 键面板同属「键盘面」，底色与键面一起跟透明度走
     * （面板底若是实心，切到语音面板就会跳色）。
     *
     *  - 纯色面（`kb_bg` 背板 / `kb_divider` 分隔线）走 plate 档，按色值识别；
     *  - 键面（6 个 `key_bg` 编辑键）与麦克风底盘（`mic_area_bg`）走 surface 档 ，
     *    它们是 drawable，色值识别扫不到，按当前档重建；
     *  - `mic_button`（MicButton）不参与（自绘）；其底盘已随 `mic_area` 一起处理。
     */
    private fun applyTransparencyToVoicePanel() {
        val container = keyboardContainer ?: return
        if (container.childCount < 1) return
        val ctx = keyboardThemeCtx ?: this
        val percent = prefs.keyTransparencyPercent
        val plateAlpha = KeyTransparency.plateAlpha(percent)
        val surfaceAlpha = KeyTransparency.surfaceAlpha(percent)
        val voice = container.getChildAt(0)
        // 纯色面按档统一淡（与 26 键面板同一套识别规则）
        alphaFaces(
            voice,
            plateRgb = intArrayOf(ctx.getColor(R.color.kb_bg), ctx.getColor(R.color.kb_divider)),
            surfaceRgb = intArrayOf(),
            plateAlpha = plateAlpha,
            surfaceAlpha = surfaceAlpha,
        )
        voice.findViewById<View>(R.id.status_bar)?.setBackgroundColor(
            KeyTransparency.withAlpha(ctx.getColor(R.color.kb_bg), surfaceAlpha),
        )
        // drawable 面（颜色识别扫不到）：麦克风底盘与 6 个编辑键按档重建。
        // 底盘必须重建为同款圆角 shape（mic_area_bg 是 16dp 圆角），
        // 直接 setBackgroundColor 会丢掉圆角，0% 时也看得出来。
        voice.findViewById<View>(R.id.mic_area)?.background =
            buildRoundedFaceBackground(ctx, R.color.surface_hi, surfaceAlpha, 16f)
        for (id in voiceEditKeyIds) {
            voice.findViewById<View>(id)?.background = buildKeyFaceBackground(ctx, surfaceAlpha)
        }
    }

    /**
     * 把 [v] 及其整棵子树里所有「纯色面」按档位套 alpha
     * （与 `PinyinKeyboardView.alphaFaces` 是同一套规则的两份实现，改一处必须同步另一处）：
     *  - RGB ∈ [plateRgb]（背板类：无文字）→ [plateAlpha]；
     *  - RGB ∈ [surfaceRgb]（内容面类：带文字/内容）→ [surfaceAlpha]。
     */
    private fun alphaFaces(
        v: View,
        plateRgb: IntArray,
        surfaceRgb: IntArray,
        plateAlpha: Float,
        surfaceAlpha: Float,
    ) {
        val c = (v.background as? ColorDrawable)?.color
        if (c != null) {
            val rgb = c and 0x00FFFFFF
            val target = when {
                plateRgb.any { (it and 0x00FFFFFF) == rgb } -> plateAlpha
                surfaceRgb.any { (it and 0x00FFFFFF) == rgb } -> surfaceAlpha
                else -> -1f
            }
            if (target >= 0f) v.setBackgroundColor(KeyTransparency.withAlpha(c, target))
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                alphaFaces(v.getChildAt(i), plateRgb, surfaceRgb, plateAlpha, surfaceAlpha)
            }
        }
    }

    /**
     * 半透明键盘：把键盘视图上方（窗口内）所有祖先的不透明背景清掉，并打印诊断。
     *
     * 为什么需要：窗口或框架容器可能自带不透明底色（主题 windowBackground、框架的输入视图
     * 容器），只要它们还在，键面/背板的 alpha 就只会与这层底色混合，永远透不出后面的应用
     * （真机症状：透明度调了但毫无变化）。只清祖先，不动键盘自己的各层。
     *
     * 必须在视图真正挂到窗口之后调用（onCreateInputView 返回时还没有 parent），故走 post。
     */
    private fun clearOpaqueAncestorBackgrounds(view: View) {
        var parent: ViewGroup? = view.parent as? ViewGroup
        var depth = 0
        while (parent != null && depth++ < 8) {
            val bg = parent.background
            if (bg != null) {
                val color = (bg as? ColorDrawable)?.color
                Diagnostics.i(
                    TAG,
                    "半透明: 清掉祖先背景 ${parent.javaClass.simpleName} " +
                        (color?.let { String.format("#%08X", it) } ?: bg.javaClass.simpleName),
                )
                parent.background = null
            }
            parent = parent.parent as? ViewGroup
        }
        val decorBg = window?.window?.decorView?.background
        Diagnostics.i(
            TAG,
            "半透明: 窗口 fmt=${window?.window?.attributes?.format} " +
                "decorBg=${(decorBg as? ColorDrawable)?.let { String.format("#%08X", it.color) } ?: "无"}",
        )
    }

    private fun applyKeyboardMode() {
        val container = keyboardContainer ?: return
        if (container.childCount < 2) return
        val voiceView = container.getChildAt(0)
        val pinyinView = container.getChildAt(1)
        when (keyboardMode) {
            KeyboardMode.VOICE -> {
                voiceView.visibility = View.VISIBLE
                pinyinView.visibility = View.GONE
            }
            KeyboardMode.PINYIN -> {
                voiceView.visibility = View.GONE
                pinyinView.visibility = View.VISIBLE
                // 调试：拼音键盘刚变为可见，等一帧布局完成后输出按键坐标供自动化定位
                ui.postDelayed({
                    if (pinyinKeyboard?.width != 0) {
                        Diagnostics.i(TAG, "拼音键盘坐标: ${pinyinKeyboard?.getFunctionKeyPositions()}")
                        Diagnostics.i(TAG, "拼音键盘候选栏: ${pinyinKeyboard?.getCandidateBarPosition()}")
                    }
                }, 150L)
            }
        }
        Diagnostics.i(TAG, "applyKeyboardMode: $keyboardMode")
    }

    private fun switchToPinyinKeyboard() {
        // 从语音切走时若还在录音，直接丢弃（防止结果落到别的输入框）
        if (mode != Mode.NONE) stopRecording(commit = false)
        keyboardMode = KeyboardMode.PINYIN
        applyKeyboardMode()
    }

    /**
     * 切到语音键盘。
     *
     * 进入语音前先判断识别服务是否在线，离线就直接拒绝并提示：
     * 否则用户长按空格进了语音面板才发现连不上，白等一次 WebSocket 握手，
     * 还会连带触发录音权限检查、录音器初始化等一整套语音链路负担。
     * 语音是自用功能，宁可在这里提前拦掉，也不让用户进到一个用不了的面板。
     */
    /**
     * 按需装配语音组件。只有「语音输入」开关为开时才真正创建。
     *
     * 幂等：已创建则直接返回。开关关闭时本方法不做任何事，
     * 于是 AsrClient / MicRecorder 始终为 null，语音完全沉寂、零内存占用。
     * 设置页保存会重启 IME 进程，所以开关变更后重新执行 onCreate 即自动生效。
     */
    private fun ensureVoiceReady() {
        if (!prefs.voiceInputEnabled) {
            Diagnostics.i(TAG, "语音输入已禁用：不创建语音组件、不连接服务端")
            return
        }
        if (asr != null) return
        asr = AsrClient(
            prefs = prefs,
            onState = { state, detail -> ui.post { renderLink(state, detail) } },
            onResult = { message -> ui.post { handleResult(message) } },
        )
        recorder = MicRecorder(
            onChunk = { chunk -> asr?.sendChunk(chunk) },
            onLevel = { level -> ui.post { micButton?.updateLevel(level) } },
            onError = { message -> ui.post { onRecorderError(message) } },
            onSilence = { ui.post { onSilenceDetected() } },
        )
        // 预热连接：首次弹键盘时 WebSocket 往往还没建好，
        // 不加这一步用户第一次点麦克风会因 beginTask() 返回 null 而"没反应"，得再点一次
        asr?.connect()
        Diagnostics.i(TAG, "语音输入已启用：语音组件已装配并开始预热连接")
    }

    private fun switchToVoiceKeyboard() {
        // 总开关优先：语音关闭时静默拒绝（不提示、不建实例），入口彻底置空
        if (!prefs.voiceInputEnabled) {
            Diagnostics.i(TAG, "语音输入已禁用，忽略进入语音键盘的请求")
            return
        }
        val client = asr ?: run {
            Diagnostics.w(TAG, "语音输入已启用但语音组件未装配，拒绝进入语音功能")
            return
        }
        if (client.state != LinkState.ONLINE) {
            Diagnostics.i(TAG, "语音服务未连通(${client.state})，拒绝进入语音功能")
            runCatching {
                Toast.makeText(
                    this,
                    getString(R.string.voice_offline_blocked, prefs.host, prefs.port),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { Diagnostics.w(TAG, "提示语音离线失败: ${it.message}") }
            return
        }
        // 从拼音切走时若有未上屏内容，先提交首候选
        pinyinKeyboard?.commitComposing()
        // 搜索态必须一并退出：面板在语音键盘下不可见却仍处激活态，
        // 切回拼音后 26 键输入会被「隐形路由」进搜索框（用户以为在打字）。
        pinyinKeyboard?.hideSearchPanel()
        // 剪贴板面板同理（2026-10-02 修复）：上面那条理由对它**逐字成立** —— 它也挂在被 GONE 的
        // 拼音视图上、`clipboardActive` 仍为 true。语音期间它既不可见、又把 `hasActiveOverlay`
        // 一直顶成真（定时换肤被无限延后），切回拼音后还会带着上一条历史原样弹出。
        // 与 `onFinishInputView` 的两行处置对齐（那两个面板一起收）。
        pinyinKeyboard?.hideClipboardPanel()
        keyboardMode = KeyboardMode.VOICE
        applyKeyboardMode()
    }

    /**
     * 输入会话结束（编辑器失焦/切换到别的输入框）：
     * 停止候选计算、清理拼音缓冲、取消录音与延时任务，降到最低功耗。
     * 不关闭 WebSocket（语音输入需要随时可用，且关闭会丢在途结果）。
     */
    /**
     * 宿主改了光标/选区时（用户点了别处、宿主程序自己改了）让拖选状态失效。
     *
     * [selectionAnchor]/[selectionFocus] 是我们记下的绝对下标，只在拖选会话内有意义；
     * 宿主把光标移到别处后它们指向的区间与用户意图无关，再按方向键会以旧 Anchor 重新
     * `setSelection`，选区整个错位，接下来的复制 / 输入都作用在错误的位置上（原实现没有
     * 实现本回调，平台提供的这个同步点被完全漏掉）。
     *
     * 采用「退出拖选」而不是「把 Anchor 挪到新位置」：后者要求回调与我们的 setSelection
     * 严格配对，做不到时就会以错位的 Anchor 继续拖选；退出最坏只是用户再点一次 ◉。
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        // 先认领：命中任意一个在途期望值就是我们自己造的（乱序回调也能认出，见 SelectionExpectations）。
        // 认领要在 selectionActive 判断之前做 —— 不在拖选时同样要把期望消费掉，不能留着垫给外部变化。
        val ours = selectionExpectations.consumeIfOurs(newSelStart, newSelEnd)
        if (!selectionActive) return
        if (ours) return
        Diagnostics.i(TAG, "宿主改动选区[$newSelStart,$newSelEnd]，退出拖选（Anchor/Focus 已失效）")
        clearSelectionState()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Diagnostics.i(TAG, "onFinishInput: 会话结束 mode=$mode")
        Diagnostics.event("IME", "FinishInput", "mode=$mode")
        // 停掉可能仍在运行的录音/延时任务
        if (mode != Mode.NONE) stopRecording(commit = false)
        ui.removeCallbacks(backspaceRunnable)
        ui.removeCallbacks(autoStopRunnable)
        ui.removeCallbacks(startHoldRunnable)
        // 会话边界：在途翻译作废（2026-09-30 审查发现）。onFinishInput 早于下一个输入框的
        // onStartInputView，而译文回调走 `ui.post` —— 不在**这里**作废的话，结果可能在
        // 「新框已开始、onStartInputView 尚未跑到」的窗口里，经回调闭包里的**旧 connection**
        // 提交进用户已经离开的那个输入框。
        cancelTranslate()
    }

    /**
     * 系统每次请求显示 IME 窗口的闸门（InputMethodService 内部所有显示路径都经它判定：
     * 编辑框聚焦自动唤起 / [requestShowSelf] / 配置变化后的自动恢复显示）。
     *
     * 「自动唤起键盘」关闭时返回 false，从源头拒绝一切显示请求，窗口永不显示，
     * 系统端 mShowInputRequested 保持 false，不会形成「唤起 → 隐藏 → 系统重新唤起」循环。
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (!prefs.autoShowKeyboard) {
            Diagnostics.i(TAG, "自动唤起键盘：关闭，禁止主动显示 IME")
            return false
        }
        return super.onShowInputRequested(flags, configChange)
    }

    /**
     * 主题决策变了就用新色板重建键盘。
     *
     * 两个触发源：① 设置页改主题，同进程直接调 [notifyThemeChanged]（键盘正显示时立即换肤）；
     * ② [onStartInputView]，覆盖「定时模式在键盘收起期间跨过了切换点」。
     * 键盘视图尚未创建（appliedThemeDark 为 null）时无需处理：onCreateInputView 会读到新值。
     */
    private fun applyThemeIfNeeded() {
        val wantDark = ThemeManager.isDark(this, prefs)
        if (appliedThemeDark != null && appliedThemeDark != wantDark) {
            // 视图上有用户正在进行的状态（未上屏拼音/预测、剪贴板 / 搜索 / 方向面板、密码模式）时不动视图：
            // 重建会把它们静默丢弃/关闭（宿主输入框毫无变化）。延后到下次弹出 ，
            // onStartInputView 会再判一次。密码模式的复原三元组只活在旧视图上 ⇒ 漏掉它就是
            // 「打字期间键盘变回普通键盘且回不去」（BUG.md L-87）。
            if (pinyinKeyboard?.let { it.hasPendingInput || it.hasActiveOverlay } == true) {
                Diagnostics.i(TAG, "主题变更: 视图有未完成操作（输入或面板），延后到下次弹出换肤")
                return
            }
            Diagnostics.i(TAG, "主题变更: 重建键盘（${if (wantDark) "暗色" else "亮色"}）")
            recreateKeyboardView()
        }
    }

    /**
     * 用最新配置重建键盘视图（换肤与符号布局变更共用）。
     * 新视图创建时读取全部最新数据，所以顺手清掉「符号布局待重建」标记。
     */
    private fun recreateKeyboardView() {
        pendingSymbolLayoutRebuild = false
        // 旧视图连同它的两个面板一起被换掉：面板的后台扫描靠各自的刷新令牌判停，而重建不走
        // 面板的「打开 / 收起」入口（令牌不会自己失效）⇒ 先明确收工，否则旧面板会把任务跑完、
        // 把结果 post 到已脱离视图树的列表上，还占着单线程的 BackgroundIo（BUG.md L-17）。
        pinyinKeyboard?.stopPanelBackgroundWork()
        // 面板状态挂在视图上：新视图的面板一律是收起态，IME 侧这个标记（仅供诊断日志）要跟着复位，
        // 否则后续日志会一直报 clipboardPanelOpen=true（BUG.md L-18）
        clipboardPanelOpen = false
        setInputView(onCreateInputView())
        // 换视图后重放敏感框抑制（BUG.md L-96）：新视图的 `suppressLearning` 默认 false，
        // 不重放则本会话余下时间在密码框里选候选会被学进词频；顺序必须是「先换视图、再重放」
        // （setInputView 同步更新 pinyinKeyboard，重放才落在新视图上）。
        pinyinKeyboard?.setSuppressLearning(suppressLearningForSession)
        // 换视图后重放「翻译中」（2026-09-30 审查发现）：新视图的 translateInFlight 默认 false，
        // 不重放会让按钮渲染成**可点的「翻译」**，而 IME 侧闸门还关着 —— 点击被静默吞掉。
        // 与上面同款：必须在 setInputView 之后重放，才落在新视图上。
        pinyinKeyboard?.setTranslating(translateInFlight)
    }

    /** 排序页/收藏编辑页改动符号数据后重建键盘视图（companion 的 [onSymbolLayoutChanged] 转发到这里） */
    fun rebuildInputViewForSymbolLayout() {
        val keyboard = pinyinKeyboard ?: return
        // 与换肤一致地不打断未上屏输入：重建会清空拼音串/预测词，用户以为输入被吞。
        // 该路径真实可达：在编辑页「添加符号」对话框里打了一半拼音就按 Home / 锁屏，
        // Activity.onPause 会先于 IME 提交触发（真机日志实证 2026-09-22）。
        // 这里不看面板态：编辑页场景下面板不可能正被使用，而推迟会造成「改了符号不生效」。
        if (keyboard.hasPendingInput) {
            // 记下待重建：框架跨会话复用同一个键盘视图，「下次创建」不会自然到来 ，
            // 靠 onStartInputView 的补重建兜底，否则用户会一直看到旧符号（真机已复现）。
            pendingSymbolLayoutRebuild = true
            Diagnostics.i(TAG, "符号分组顺序/收藏变更: 视图有未上屏输入，延后到下次弹键盘重建")
            return
        }
        Diagnostics.i(TAG, "符号分组顺序/收藏变更: 重建键盘视图")
        recreateKeyboardView()
    }

    /**
     * 定时换色的准点定时器：与页面**共用同一份实现**（[ThemeManager.ScheduledThemeTicker]，`BUG.md` L-476）。
     *
     * 弹出时排下一次（[onStartInputView] 里紧挨着的 [applyThemeIfNeeded] 已经先对过表），
     * 收起时撤掉 —— 键盘不显示的那段时间到点检查交给下次弹出。
     * 到点做什么由这个 lambda 定：直接调 [applyThemeIfNeeded]，它自己会跳过「档位没变」与
     * 「视图上有未完成操作（未上屏输入 / 面板 / 密码模式）」两种情形（后者延后到下次弹出）。
     */
    private val themeTicker by lazy {
        ThemeManager.ScheduledThemeTicker(prefs) { applyThemeIfNeeded() }
    }

    /** 词库加载的重试上限（含 onCreate 那次预加载）：失败后不能无限重试 */
    private val MAX_PINYIN_LOAD_ATTEMPTS = 3

    /** 已发起的加载尝试次数（主线程写、后台日志线程读，故 @Volatile） */
    @Volatile
    private var pinyinLoadAttempts = 1

    /**
     * 词库加载失败后的补试（每次键盘弹出最多发起一次）。
     *
     * onCreate 的预加载失败只留一条日志，而 `PinyinEngine.load` 带守卫 —— 不补试的话，
     * 本次进程内会一直是「没有任何候选」，用户只能靠系统重建输入法进程恢复。
     *
     * 判据必须是 `isFullyLoaded`（全量就绪）而不是 `isLoaded`：后者在「只有单字表」时已为 true，
     * 拿它当闸门会让「索引那一段失败」的进程永远得不到补试（只剩单字候选，
     * 「词库补全中」提示也永不消失）。加载正在进行时不占用重试次数（`isLoading` 守卫），
     * 冷启动那 0.5~0.7s 里用户可能反复弹键盘。
     *
     * `load` 幂等且加锁：已全量就绪时这里只做一次 volatile 读；补试走独立守护线程，
     * 绝不占用主线程（全量加载实测 6~10.7s，放主线程必定 ANR）。
     */
    private fun retryPinyinLoadIfNeeded() {
        if (PinyinEngine.isFullyLoaded || PinyinEngine.isLoading) return
        if (pinyinLoadAttempts >= MAX_PINYIN_LOAD_ATTEMPTS) return
        val attempt = ++pinyinLoadAttempts
        Thread({
            runCatching { PinyinEngine.load(this) }
                .onSuccess { Diagnostics.i(TAG, "词库补试加载成功（第 $attempt 次尝试）") }
                .onFailure { Diagnostics.e(TAG, "词库补试加载失败（第 $attempt 次尝试）: ${it.message}", it) }
        }, "jinn-pinyin-retry").apply { isDaemon = true }.start()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 词库在 onCreate 预加载失败时这里补试（已就绪时开销为一次 volatile 读）
        retryPinyinLoadIfNeeded()
        // 防御拦截：关闭开关时键盘可能正处于显示状态（窗口可见时才回调本方法），
        // 立即收起；此后一切显示请求都被 onShowInputRequested 拒绝，不会重新唤起。
        // 不显示输入视图，不初始化输入，键盘在本输入会话内完全不可用。
        if (!prefs.autoShowKeyboard) {
            Diagnostics.i(TAG, "自动唤起键盘：关闭，收起当前显示的 IME 窗口")
            runCatching { hideWindow() }.onFailure { }
            return
        }
        Diagnostics.i(TAG, "onStartInputView: restarting=$restarting package=${info?.packageName} fieldId=${info?.fieldId}")
        Diagnostics.event("IME", "StartInputView", "restart=$restarting pkg=${info?.packageName}")
        // 会话边界：上一个输入框的在途翻译作废（结果回来时代际不符，直接丢弃）。
        // ⚠ 必须在 applyThemeIfNeeded **之前**（2026-10-01 修复 L-336）：换肤的延后判据里含视图侧
        // `translateInFlight`，顺序反过来时，被翻译态挡下的换肤会在**同一个回调**里失去补做机会，
        // 键盘继续用旧皮、要再等一次弹出。
        cancelTranslate()
        // 主题变更（设置页改了模式，或定时模式跨过切换点）：用新色板重建键盘
        applyThemeIfNeeded()
        // 系统导航栏透明：框架在显示窗口时可能已用主题属性重写过窗口参数，这里每会话补一次
        applyNavBarTransparency()
        // 上次因「未上屏输入」被延后的符号布局重建：新会话开始，补一次
        // （真机实证：框架跨会话复用同一视图，不补则一直显示旧符号）。
        // 此刻 composing 已由 onFinishInputView 提交清空；仍以防万一看一眼未上屏态。
        if (pendingSymbolLayoutRebuild && pinyinKeyboard?.let { !it.hasPendingInput } == true) {
            Diagnostics.i(TAG, "符号分组顺序/收藏变更: 补重建（上次延后）")
            recreateKeyboardView()
        }
        // 半透明键盘：语音面板底色的透明度也在这里刷新（用户可能在键盘外观页改过）
        applyTransparencyToVoicePanel()
        themeTicker.schedule() // 定时模式：键盘可见期间也准点换肤
        // 用户回来了（开始输入）：取消待触发的可选词库加载。该任务解析耗时 21~34s，
        // 砸在打字期正是本机制要避免的「后台重活抢 CPU」，兜底 180s 仍能保证最终加载。
        ui.removeCallbacks(optionalIdleCheck)
        asr?.connect()
        renderLink(asr?.state ?: LinkState.OFFLINE, null)
        micButton?.recording = false
        micButton?.cancelArmed = false
        // 会话开始：上一次听写的等待态与归属快照不带进来（结果要么已到，要么已由超时/掉线复位）
        awaitingResult = false
        voiceResultPackage = null
        ui.removeCallbacks(recognizeTimeout)
        setHint(getString(R.string.hint_idle))
        // 敏感输入框（密码框 / 声明 NO_SUGGESTIONS / imeOptions 声明不要个性化学习）
        // 不学用户词频：否则口令片段会被写进本地词频文件，之后在普通输入框里被优先推荐出来。
        // 同时记在 IME 侧：视图重建（换肤 / 符号布局变更）会换掉整个视图，标记是视图字段 ⇒ 重建后要重放（L-96）
        suppressLearningForSession = InputFieldPrivacy.suppressLearning(info?.inputType, info?.imeOptions ?: 0)
        pinyinKeyboard?.setSuppressLearning(suppressLearningForSession)
        // 每次输入框聚焦时重新同步双拼方案（全拼/双拼、中英文）：
        // 设置页改动后无需重启输入法，下次弹键盘即生效。
        // 英文态取键盘当前状态（保留用户手动切换结果，不强制覆盖）
        pinyinKeyboard?.configure(
            scheme = prefs.effectiveShuangpinScheme,
            english = pinyinKeyboard?.isEnglishMode() ?: prefs.keyboardEnglish,
        )
        // 剪贴板面板粘贴时连接无效会暂存文本，编辑框重新聚焦时自动提交
        flushPendingPaste(info)
        // 不再在这里恢复剪贴板面板，恢复逻辑会触发 onPanelShown→refresh
        // （主线程 DB 查询数百毫秒）→ 诱发 IME 窗口反复 relayout（12:20 循环日志实证），
        // 反而让面板抖动/空白。INVISIBLE 方案下键盘视图实例不重建，面板状态天然保留。
    }

    /**
     * 输入框身份键：包名 + fieldId。
     *
     * 用于把暂存粘贴绑定到发起粘贴时的那个输入框，只比对 fieldId 不够
     * （不同 App 的 fieldId 会撞），必须带上包名。取不到 EditorInfo 时返回 null，
     * 表示「身份未知」，此时由时效窗口与 [onFinishInputView] 的会话边界兜底。
     */
    private fun pendingPasteFieldKey(info: EditorInfo?): String? = fieldKeyOf(info?.packageName, info?.fieldId ?: 0)

    /** 提交暂存的剪贴板粘贴文本（编辑框重新可用时调用；无暂存则空操作） */
    private fun flushPendingPaste(info: EditorInfo? = null) {
        // 会话边界：拖选状态必须复位。anchor/focus 是上一个输入框的坐标，
        // 跨字段残留会让后续 extendSelection 用旧下标去 setSelection（被系统钳位，
        // 表现为选区莫名跳动），且中心键图标与 IME 状态可能不一致。
        clearSelectionState()

        val text = pendingPasteText ?: return
        // 时效保护：暂存文本只在短时间窗口内有效，过期直接丢弃。
        // 否则用户换输入框、或隔很久才回到键盘，旧文本会被粘到完全无关的位置。
        val age = System.currentTimeMillis() - pendingPasteAt
        val target = pendingPasteFieldKey
        val current = pendingPasteFieldKey(info)
        // 身份保护：换到另一个输入框（哪怕同 App 的另一个 fieldId）就不再提交。
        // 10s 的时效窗口远比「切到微信再切回来」长，没有这层比对，剪贴板正文会被
        // 静默注入到用户当前正在输入的、完全无关的位置。
        if (target != null && current != null && target != current) {
            pendingPasteText = null
            pendingPasteFieldKey = null
            Diagnostics.w(TAG, "flushPendingPaste: 目标输入框已变更($target -> $current)，丢弃 len=${text.length}")
            return
        }
        if (age > PENDING_PASTE_TTL_MS) {
            pendingPasteText = null
            pendingPasteFieldKey = null
            Diagnostics.w(TAG, "flushPendingPaste: 暂存已过期(${age}ms)，丢弃 len=${text.length}")
            return
        }
        pendingPasteText = null
        pendingPasteFieldKey = null
        val connection = currentInputConnection
        if (connection == null) {
            // 仍无有效连接：放回暂存（保留原时刻），等待下次 onStartInputView。
            // 输入框身份必须一并放回：原先只恢复了正文、把 fieldKey 留在 null，
            // 于是下一次进入时 target 为 null、上面的「目标已变更」判据直接短路，
            // 这 10 秒里的暂存正文就可能被提交到一个完全无关的输入框里。
            pendingPasteText = text
            pendingPasteFieldKey = target
            Diagnostics.w(TAG, "flushPendingPaste: 连接仍无效，继续暂存 len=${text.length}")
            return
        }
        Diagnostics.i(TAG, "flushPendingPaste: 提交暂存粘贴 len=${text.length}")
        // 暂存粘贴落地也是宿主写调用：宿主刚死时这里会抛，裸调就从主线程带走 IME 进程（L-404）
        guardHostCall("flushPendingPaste") { connection.commitText(text, 1) }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Diagnostics.i(TAG, "onFinishInputView: finishingInput=$finishingInput mode=$mode clipboardPanelOpen=$clipboardPanelOpen")
        Diagnostics.event("IME", "FinishInputView", "finish=$finishingInput clipboard=$clipboardPanelOpen")
        // 键盘收起时还在录音，直接丢弃：否则文本可能落到别的输入框里
        if (mode != Mode.NONE) stopRecording(commit = false)
        // 拼音键盘若有未上屏内容，提交首候选
        pinyinKeyboard?.commitComposing()
        // 会话边界：搜索面板必须退出，跨输入框残留会让下一次输入被路由进搜索框
        pinyinKeyboard?.hideSearchPanel()
        // 剪贴板面板同理（BUG.md L-110）：键盘收起后再弹出，它会带着上一个输入框的历史条目
        // 一起回来，误点即把历史内容粘进新字段。上面那行是同族的既有处理，这条原先漏了。
        pinyinKeyboard?.hideClipboardPanel()
        // 会话边界：在途翻译作废（旧结果不得落到新输入框里）
        cancelTranslate()
        ui.removeCallbacks(backspaceRunnable)
        themeTicker.stop() // 键盘已收起：到点检查交给下次弹出
        // 会话边界：暂存的剪贴板文本属于上一个输入框，新输入框聚焦时不得自动提交
        // （配合 flushPendingPaste 的 fieldId 比对，双重拦截跨字段注入）
        if (pendingPasteText != null) {
            Diagnostics.i(TAG, "onFinishInputView: 输入会话结束，丢弃暂存粘贴")
            pendingPasteText = null
            pendingPasteFieldKey = null
        }
        // 键盘收起 = 用户大概率停止输入了；再等一小段（避免只是切了个应用马上回来）后加载可选词库
        ui.removeCallbacks(optionalIdleCheck)
        ui.postDelayed(optionalIdleCheck, OPTIONAL_IDLE_DELAY_MS)
        // 不在这里关闭连接：输入法是常驻服务，WebSocket 应跨输入会话复用。
        // 松手后服务端 final 结果往往还要 1~3 秒才回来，此刻关连接会把在途
        // 结果丢掉，识别文本无法落地；连接统一在 onDestroy 里释放。
        super.onFinishInputView(finishingInput)
    }

    /** IME 窗口显示完成回调：用于确认面板打开后窗口状态 */
    override fun onWindowShown() {
        super.onWindowShown()
        Diagnostics.i(TAG, "onWindowShown: 窗口显示 clipboardPanelOpen=$clipboardPanelOpen keyboardMode=$keyboardMode")
        Diagnostics.event("IME", "WindowShown", "clipboard=$clipboardPanelOpen mode=$keyboardMode")
    }

    /** IME 窗口隐藏回调：排查「面板打开后约 0.5 秒窗口被收起」的直接证据 */
    override fun onWindowHidden() {
        super.onWindowHidden()
        Diagnostics.i(TAG, "onWindowHidden: 窗口隐藏 clipboardPanelOpen=$clipboardPanelOpen keyboardMode=$keyboardMode")
        Diagnostics.event("IME", "WindowHidden", "clipboard=$clipboardPanelOpen mode=$keyboardMode")
    }

    override fun onDestroy() {
        instance = null
        // 用户词频：把未落盘的最后几次学习刷出去（内部走 BackgroundIo，不阻塞）
        runCatching { PinyinEngine.flushUserFrequency() }
        Diagnostics.i(TAG, "onDestroy: IME 服务销毁, mode=$mode")
        ui.removeCallbacksAndMessages(null)
        // 在途翻译作废：服务销毁后即使回调到达，也没有视图可改（代际已不符）
        cancelTranslate()
        // 视图引用断开（与下面 screenOffReceiver 同款口径）：视图持 listener 强引用本服务、
        // 服务也持视图，不置 null 就要等整对引用一起被回收才断（2026-09-30 审查发现）。
        // 顺序必须在 cancelTranslate 之后 —— 它内部要调 pinyinKeyboard.setTranslating。
        pinyinKeyboard = null
        unregisterNetwork()
        // 系统剪贴板监听挂在 ClipboardManager 上，不注销会随服务一起泄漏；
        // 这里不置 null：后续 refreshConfig 仍可能重新 start（stop 幂等）。
        runCatching { clipboardController?.stop() }
        runCatching { unregisterReceiver(configReceiver) }
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        abandonAudioFocus()
        // 采集线程的 stop 需要 join(300ms) + release；放在主线程阻塞会触发 ANR，
        // 抛到后台线程让它自己收尾，asr 的 socket close 也是异步发送 close 帧
        Thread {
            recorder?.stop()
            asr?.close()
            Diagnostics.i(TAG, "onDestroy: 录音与连接已释放")
        }.apply { isDaemon = true }.start()
        super.onDestroy()
    }

    /**
     * 触发可选词库包的后台加载（单次）。
     *
     * 不用固定延迟：实测那 1.14M 词条的解析要 21~34s，而「基础包就绪后 5 秒」
     * 恰好是用户开始打字的时刻，后台重活与前台输入抢 CPU/内存带宽，主观感受就是
     * 「刚开机很卡」。改为等空闲信号，三选一（先到先得）：
     *  · 息屏：用户锁屏，最可靠的空闲信号；
     *  · 键盘收起后再闲置 [OPTIONAL_IDLE_DELAY_MS]：用户停止输入；
     *  · 兜底 [OPTIONAL_FALLBACK_DELAY_MS]：一直没出现上面两种情况时也必须加载。
     *
     * 加载线程本身已设 [android.os.Process.THREAD_PRIORITY_BACKGROUND]（见 PinyinEngine）。
     */
    private fun maybeLoadOptionalDict(reason: String) {
        // 这里**不再**放一次性旗标：装了包但那次没读进来（损坏 / 解压失败）时，
        // 旗标会让本进程**永远没有第二次触发**，而词库页写着「空闲时自动装载」——
        // 与用户看到的界面矛盾（BUG.md L-154 / L-167）。要不要真装载由引擎的闸门决定：
        // 它按包身份 + 失败次数（有界重试）判定，未变过的包在闸门处就返回 false、几乎不花时间。
        runCatching {
            val started = PinyinEngine.loadOptionalAsync(this, delayMs = 0L) {
                Diagnostics.i(TAG, "可选词库已在后台就绪")
            }
            // 只在**真的开始装载**时打日志：否则每次息屏 / 收键盘都会多一行噪音
            if (started) Diagnostics.i(TAG, "可选词库: 开始后台加载（触发: $reason）")
        }.onFailure {
            Diagnostics.e(TAG, "启动可选词库加载失败: ${it.message}", it)
        }
    }

    /** 横屏时不要进全屏抽取模式，这个键盘很矮，没必要遮住宿主界面 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    // ── 麦克风手势 ────────────────────────────────────────────

    private fun onMicTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                holdAttempted = false
                setCancelArmed(false)
                if (mode == Mode.TOGGLE) {
                    stopOnUp = true
                    Diagnostics.i(TAG, "onMicTouch: DOWN 连续录音中，抬手即停")
                } else {
                    stopOnUp = false
                    ui.postDelayed(startHoldRunnable, HOLD_THRESHOLD_MS)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.HOLD) {
                    setCancelArmed(downY - event.rawY > cancelSlidePx)
                }
            }

            MotionEvent.ACTION_UP -> {
                ui.removeCallbacks(startHoldRunnable)
                when {
                    mode == Mode.HOLD -> {
                        Diagnostics.i(TAG, "onMicTouch: UP 长按结束 cancelArmed=$cancelArmed")
                        stopRecording(commit = !cancelArmed)
                    }
                    stopOnUp -> {
                        Diagnostics.i(TAG, "onMicTouch: UP 连续录音停止")
                        stopRecording(commit = true)
                    }
                    holdAttempted -> Unit // 长按没起来（未连接 / 无权限），别再当短按重试
                    else -> {
                        Diagnostics.i(TAG, "onMicTouch: UP 判定为短按 → 连续录音")
                        startRecording(Mode.TOGGLE)
                    }
                }
                stopOnUp = false
                setCancelArmed(false)
            }

            MotionEvent.ACTION_CANCEL -> {
                Diagnostics.i(TAG, "onMicTouch: CANCEL mode=$mode")
                ui.removeCallbacks(startHoldRunnable)
                if (mode == Mode.HOLD) stopRecording(commit = false)
                stopOnUp = false
                setCancelArmed(false)
            }
        }
        return true
    }

    private fun setCancelArmed(armed: Boolean) {
        if (cancelArmed == armed) return
        cancelArmed = armed
        micButton?.cancelArmed = armed
        if (mode == Mode.HOLD) {
            setHint(
                getString(
                    if (armed) R.string.hint_release_cancel else R.string.hint_release_send
                )
            )
        }
    }

    // ── 录音控制 ──────────────────────────────────────────────

    private fun startRecording(target: Mode) {
        // 语音输入关闭时 asr/recorder 为 null：入口直接置空，不做任何语音动作
        if (asr == null || recorder == null) {
            Diagnostics.i(TAG, "startRecording: 语音输入已禁用，忽略")
            return
        }
        if (mode != Mode.NONE) {
            Diagnostics.w(TAG, "startRecording: 已在录音中 mode=$mode，忽略")
            return
        }

        // 上一段的等待态到这里结束：新录音开始时它的最终结果已经拿不回来了
        // （AsrClient 会用新 taskId 覆盖归属，旧结果回来会被当过期任务丢弃），
        // 而那个 60s 兜底若留着，会在这次录音中途把状态条改成「未连接」，误导用户。
        if (awaitingResult) {
            Diagnostics.i(TAG, "startRecording: 上一段识别结果未回，本次录音开始后不再等待")
        }
        awaitingResult = false
        voiceResultPackage = null
        ui.removeCallbacks(recognizeTimeout)

        if (!hasMicPermission()) {
            Log.w(TAG, "startRecording: 缺少 RECORD_AUDIO 权限")
            Diagnostics.w(TAG, "startRecording: 缺少 RECORD_AUDIO 权限")
            setStatusText(getString(R.string.status_no_permission))
            setHint(getString(R.string.hint_grant_permission))
            return
        }

        // 请求音频焦点：被抢占时自动停止录音，避免与其他音频源冲突
        requestAudioFocus()

        if (asr?.beginTask() == null) {
            // 未连接：已请求的焦点要释放，避免占用却不录音
            Diagnostics.w(TAG, "startRecording: beginTask 返回 null（未连接）")
            abandonAudioFocus()
            setHint(getString(R.string.hint_not_connected))
            return
        }

        if (recorder?.start() != true) {
            Diagnostics.e(TAG, "startRecording: recorder.start 失败，取消任务")
            asr?.cancelTask()
            // 录音启动失败：同样释放焦点
            abandonAudioFocus()
            return
        }

        // 上一段还挂着预编辑文本的话先落地，避免被新结果覆盖掉
        if (prefs.useComposing) currentInputConnection?.finishComposingText()

        mode = target
        micButton?.recording = true
        Diagnostics.i(TAG, "startRecording: 开始录音 target=$target")
        setHint(
            getString(
                if (target == Mode.HOLD) R.string.hint_release_send else R.string.hint_tap_stop
            )
        )
        if (target == Mode.TOGGLE) ui.postDelayed(autoStopRunnable, MAX_TOGGLE_MS)
    }

    private fun stopRecording(commit: Boolean) {
        if (mode == Mode.NONE) return
        mode = Mode.NONE
        ui.removeCallbacks(autoStopRunnable)

        recorder?.stop()
        abandonAudioFocus()
        micButton?.recording = false
        micButton?.cancelArmed = false

        if (commit) {
            // 收尾包没送出去（断线 / 队列满）时服务端不会给最终结果：直接提示未连接、不挂等待态。
            // 挂上就要等 60s 兜底超时才复位状态条，期间用户以为还在识别
            if (asr?.endTask() != true) {
                Diagnostics.w(TAG, "stopRecording: 收尾包未送出，放弃等待结果")
                awaitingResult = false
                voiceResultPackage = null
                ui.removeCallbacks(recognizeTimeout)
                setHint(getString(R.string.hint_not_connected))
            } else {
                Diagnostics.i(TAG, "stopRecording: 收尾发送（识别中）")
                setHint(getString(R.string.hint_recognizing))
                // 等结果期间要两样东西：兜底超时（服务端不回 final 时状态条不能被永久卡住）
                // 与归属快照（结果回来时判「输入框是否已经换了应用」）
                awaitingResult = true
                voiceResultPackage = currentInputEditorInfo?.packageName
                ui.removeCallbacks(recognizeTimeout)
                ui.postDelayed(recognizeTimeout, RECOGNIZE_TIMEOUT_MS)
            }
        } else {
            asr?.cancelTask()
            awaitingResult = false
            voiceResultPackage = null
            ui.removeCallbacks(recognizeTimeout)
            Diagnostics.i(TAG, "stopRecording: 取消（不采用结果）")
            // 取消时把已经回显的预编辑文本一起撤掉
            if (prefs.useComposing) {
                currentInputConnection?.setComposingText("", 1)
                currentInputConnection?.finishComposingText()
            }
            setHint(getString(R.string.hint_cancelled))
        }
    }

    private fun onRecorderError(message: String) {
        Diagnostics.e(TAG, "onRecorderError: $message")
        stopRecording(commit = false)
        setHint(message)
    }

    // ── 音频焦点 / 网络 / VAD ───────────────────────────────

    /** 本地 VAD：连续录音模式静音过久自动收尾出结果，长按模式只提示 */
    private fun onSilenceDetected() {
        Diagnostics.i(TAG, "onSilenceDetected: mode=$mode")
        if (mode == Mode.TOGGLE) {
            stopRecording(commit = true)
        } else {
            setHint(getString(R.string.hint_silence))
        }
    }

    private fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        focusRequest = req
        val result = runCatching { audioManager.requestAudioFocus(req) }
        result.onFailure {
            Log.w(TAG, "requestAudioFocus: ${it.message}")
            Diagnostics.w(TAG, "requestAudioFocus: ${it.message}")
        }.onSuccess {
            Diagnostics.i(TAG, "requestAudioFocus: result=$it")
        }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            runCatching { audioManager.abandonAudioFocusRequest(it) }
                .onFailure {
                    Log.w(TAG, "abandonAudioFocus: ${it.message}")
                    Diagnostics.w(TAG, "abandonAudioFocus: ${it.message}")
                }
        }
        focusRequest = null
    }

    private fun registerNetwork() {
        // 无需再做 SDK_INT 判断：NetworkCallback 是 API 24 引入，而本应用 minSdk = 26，
        // 原先的 `if (SDK_INT < N) return` 恒为 false（Lint ObsoleteSdkInt）。
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Diagnostics.i(TAG, "network: 网络恢复可用")
                ui.post { if (asr?.state == LinkState.OFFLINE) asr?.connect() }
            }

            override fun onLost(network: Network) {
                Diagnostics.w(TAG, "network: 网络断开")
            }
        }
        networkCallback = cb
        runCatching { connectivity.registerDefaultNetworkCallback(cb) }
            .onFailure {
                Log.w(TAG, "registerNetwork: ${it.message}")
                Diagnostics.w(TAG, "registerNetwork: ${it.message}")
            }
    }

    private fun unregisterNetwork() {
        networkCallback?.let {
            runCatching { connectivity.unregisterNetworkCallback(it) }
                .onFailure {
                    Log.w(TAG, "unregisterNetwork: ${it.message}")
                    Diagnostics.w(TAG, "unregisterNetwork: ${it.message}")
                }
        }
        networkCallback = null
    }

    // ── 识别结果 ──────────────────────────────────────────────

    private fun handleResult(message: RecognitionMessage) {
        // 归属校验：松手后服务端还要 1~3 秒才回结果，期间用户可能已切到别的应用。
        // 跨应用落字属隐私问题（用户没打算往那个输入框写字），与暂存粘贴同一条口径。
        // 只比包名不比 fieldId：部分宿主的 fieldId 不稳定，按它比会误丢正常结果。
        val origin = voiceResultPackage
        val current = currentInputEditorInfo?.packageName
        if (origin != null && current != null && origin != current) {
            awaitingResult = false
            voiceResultPackage = null
            ui.removeCallbacks(recognizeTimeout)
            Diagnostics.w(TAG, "handleResult: 输入框已切到其他应用（$origin -> $current），丢弃本次识别结果")
            setHint(getString(R.string.hint_cancelled))
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            Log.w(TAG, "handleResult: InputConnection 已失效")
            Diagnostics.w(TAG, "handleResult: InputConnection 已失效 taskId=${message.taskId}")
            return
        }

        // 服务端给的是整段累积文本，直接整体覆盖，不要自己再拼
        val text = if (prefs.stripTrailingPunc) stripTrailingPunc(message.text) else message.text

        if (!message.isFinal) {
            if (prefs.useComposing) connection.setComposingText(text, 1)
            Diagnostics.v(TAG, "handleResult: 预编辑回显 ${message.duration.toInt()}s \"${text.take(40)}\"")
            setHint(getString(R.string.hint_progress, message.duration.toInt()))
            return
        }

        // 结果已到：清掉等待态与兜底任务（否则 60s 后还会改一次状态条文字）
        awaitingResult = false
        voiceResultPackage = null
        ui.removeCallbacks(recognizeTimeout)
        // 只记字数不记正文：日志文件落在外部存储，且会随「导出诊断包」整体外发
        Diagnostics.i(TAG, "handleResult: 最终结果 共${message.text.length}字")
        if (prefs.useComposing) {
            connection.setComposingText(text, 1)
            connection.finishComposingText()
        } else {
            connection.commitText(text, 1)
        }
        setHint(getString(if (text.isBlank()) R.string.hint_empty else R.string.hint_done))
    }

    /** 对齐桌面端 trash_punc：句尾的逗号句号在聊天场景里更像噪音 */
    private fun stripTrailingPunc(text: String): String =
        text.trimEnd().trimEnd(*TRAILING_PUNC)

    // ── 在线翻译（BYOK）────────────────────────────────────

    /**
     * 翻译「原文范围」设置里取到的那段文本（默认：光标所在行、光标前面的内容），
     * 译文插在光标处（通常即原文之后）并另起一行，原文一个字不动。
     *
     * 取值只读、不复制：主路径 `getTextBeforeCursor`，宿主不支持时兜底 `getExtractedText`；
     * 全程不碰系统剪贴板、不改输入框内容、不动光标。失败路径一个字都不写。
     */
    private fun startTranslate() {
        if (translateInFlight) return
        // 总开关是「关掉即不可能发请求」的闸门（2026-09-30 审查发现）：功能面板只按它渲染按钮，
        // 而关开关**不是**面板重绘入口 —— 面板还带着旧的第 7 键时，点下去照样会发起真实请求。
        if (!prefs.translateEnabled) return
        // 密码框不翻译：不能把口令发到云端。判据在**点击这一刻**按当前 EditorInfo 现跑 —— 会话缓存
        // 只在 onStartInputView 刷新，宿主在键盘已显示时把焦点从普通框切到密码框可能不重发该回调。
        //
        // 判据是 [InputFieldPrivacy.blocksTranslation]（密码类变体 + 宿主声明「不要个性化学习」），
        // **不能**复用 `suppressLearning`（2026-10-01 真机实测修复）：后者服务本地词频学习，
        // 把 `TYPE_NULL` / `NO_SUGGESTIONS` 也算敏感 —— 这两个标志在浏览器里是常规做法
        // （Chrome 内核的搜索框全带，WebView 输入框常报 TYPE_NULL），拿它们拦翻译会让整类宿主
        // 点了没反应。翻译是用户主动点击发起的，本身就是「同意把这段发出去」。
        //
        // ⚠ `IME_FLAG_NO_PERSONALIZED_LEARNING` **要保持拦住**（2026-10-01 修复 L-330）：设置页对用户
        //    明写承诺过「声明『不要个性化学习』的输入框不会翻译」，删掉那行判据会让承诺静默失效。
        // EditorInfo 缺失时也不拦：读得到原文就说明这个框能翻译，真读不到后面自然会报「读不到」。
        val info = currentInputEditorInfo
        if (InputFieldPrivacy.blocksTranslation(info?.inputType, info?.imeOptions ?: 0)) {
            // 带上原始标志位：将来再有宿主被拦，日志里直接能看出是哪一位命中的
            Diagnostics.w(
                TAG,
                "翻译: 输入框被拒绝（inputType=0x${Integer.toHexString(info?.inputType ?: 0)}" +
                    " imeOptions=0x${Integer.toHexString(info?.imeOptions ?: 0)}）",
            )
            toast(TEXT_TRANSLATE_PRIVATE_FIELD)
            return
        }
        val provider = prefs.translationProvider()
        if (provider == null) {
            Diagnostics.w(TAG, "翻译: 未配置凭据")
            toast(TranslationError.NOT_CONFIGURED.message)
            return
        }
        // 静默 return 是「点了没反应」的另一个来源（2026-10-01）：连接拿不到时用户无从判断
        // 是没生效还是宿主不支持，至少要说一句。
        val connection = currentInputConnection ?: run {
            Diagnostics.w(TAG, "翻译: 当前没有活动的输入连接，未发起")
            toast(TEXT_TRANSLATE_BEFORE_UNREADABLE)
            return
        }
        // 选区与光标是**两条独立的路**（2026-10-01 放宽）：
        //
        // - 有选中内容 ⇒ 翻译选中的那一段，提交时用它替换选区。`commitText` 在宿主存在选区时
        //   本就是**替换**语义 —— 与其拒绝，不如把它用对。这是最自然的用法（在浏览器里选一段
        //   外文再翻），此前被整体拒绝，用户只能看到「请先取消选中的内容再翻译」。
        // - 没有选中内容 ⇒ 走光标模式：原文按「翻译原文范围」取，译文追加在原文后面、一个字不动。
        //   这条路仍要求能确认「没有选区」，判据见 [canAppendTranslation]（WebView 类宿主取不到
        //   精确选区时靠 `getSelectedText` + 原文逐字比对兜底）。
        // 读一次选区、喂给下面两个判据（2026-10-01 修复 L-315）：它们原先各自调一次
        // `currentSelectionRange`（= 一次整窗口的 `getExtractedText`，最多 10 万字符），
        // 而同一次点击里这就是同一份事实。
        val selectionRange = SelectionProbe(currentSelectionRange(connection))
        val selected = readSelectedText(connection, selectionRange)
        if (selected == null) {
            // 三态里的「读不到」：可能是宿主不支持，也可能是大文档下窗口换算失败。能不能继续由下面的
            // [canAppendTranslation] 定 —— 这里以前写「按光标模式继续」，紧接着又被它拒绝，两条日志
            // 自相矛盾（2026-10-01 修复 L-284）。
            Diagnostics.w(TAG, "翻译: 读不到选中内容，改按「无选区」处理")
        }
        val replaceSelection = !selected.isNullOrEmpty()
        if (!replaceSelection) {
            when (canAppendTranslation(connection, selectionRange)) {
                AppendCheck.OK -> Unit
                AppendCheck.SELECTION_PRESENT -> {
                    // 读选中文本说「没有选中内容」，而精确判据说「有」—— 两者冲突时按危险处理
                    Diagnostics.w(TAG, "翻译: 选区状态不一致，拒绝发起")
                    toast(TEXT_TRANSLATE_SELECTION_ACTIVE)
                    return
                }
                AppendCheck.HOST_UNREADABLE -> {
                    // 宿主既不给精确选区、又答不出有没有选中内容 —— 与「你有选区」是两回事，
                    // 提示必须分开，否则用户会去取消一个并不存在的选中（2026-10-01 修复 L-284）
                    Diagnostics.w(TAG, "翻译: 宿主不支持选区检测，无法确认能否安全追加，拒绝发起")
                    toast(TEXT_TRANSLATE_SELECTION_UNREADABLE)
                    return
                }
            }
        }
        // 原文范围与字节上限都按**当前 Provider**取（每家独立配置）
        val id = TranslationProviderId.of(prefs.translateProvider)
        val scope = TranslationScope.of(prefs.translateScopeOf(id))
        val maxBytes = prefs.translateMaxBytesOf(id)
        // 只有「整行 / 整篇」两种范围需要光标后的文本：其余范围不读，省一次 Binder 往返；
        // 选区模式整段就是选中的内容，与光标位置无关，同样不读
        val needAfter = !replaceSelection &&
            (scope == TranslationScope.LINE_FULL || scope == TranslationScope.ALL)
        // 读取窗口按范围**分档**取（2026-10-02 修复 L-427 的过度修正）：
        //  · 需要确认区间末尾的两档（整行 / 整篇）：必须读满 [MAX_READ_CHARS]。否则 `afterTruncated`
        //    恒真、`appendOffsetExact` 恒假 ⇒ 这两档变成「上限调小就永远翻译不了」，而提示还说成
        //    「范围过大」——用户在「原文范围」页学到的规则是「超限就截断」，这两档却直接失败；
        //  · 「光标前本行」不需要确认末尾：它的内容随后就会被 `maxBytes` 截断，读满 10 万字符纯属
        //    浪费 —— 在有 10 万字符文档的输入框里，每点一次翻译都要为主线程的 Binder 回包 +
        //    `toString` 拷贝付 ≈200 KB，而默认档（阿里云上限 5000 字节）根本用不到，是 20× 的无谓放大。
        // 一个字符至少占 1 字节 ⇒ 读 maxBytes 个字符一定覆盖得住字节上限内的原文；再与 Binder
        // 安全上限取小（超大文档整篇回包会撞宿主的事务上限）。
        val limit = if (needAfter) {
            TranslationText.MAX_READ_CHARS
        } else {
            minOf(maxBytes.coerceAtLeast(TranslationText.MIN_MAX_BYTES), TranslationText.MAX_READ_CHARS)
        }
        // 读取时刻的**原始**文本（快照）与截取结果分开保存：截取会 trim + 按字节截断，
        // 拿它当快照判据会让「原文本身带首尾空白 / 超限被截」这类**原样未变**的情形
        // 被误判成「输入已变化」（2026-09-30 修过同类缺陷：用截取结果当判据）。
        // 读失败（null）与「光标前确实没有内容」（空串）必须分开：把前者说成后者会把
        // 「宿主读不到」报成「你没写字」（2026-10-01 修复 L-240）
        // ⚠ `before` 是**尾部对齐**的：`readTextBeforeCursor` 主路径 `getTextBeforeCursor(limit, 0)`
        // 返回紧邻光标的那 limit 个字符 —— 取不满时拿到的是靠近光标的一段，不是文首那段。
        // 「这一段是不是从文首开始」由读取路径**如实给出**，不再靠长度反推（2026-10-01 修复 L-286）
        val beforeRead = if (replaceSelection) null else readTextBeforeCursor(connection, limit)
        if (!replaceSelection && beforeRead == null) {
            Diagnostics.w(TAG, "翻译: 读不到光标前内容，拒绝发起")
            toast(TEXT_TRANSLATE_BEFORE_UNREADABLE)
            return
        }
        val before = if (replaceSelection) selected.orEmpty() else beforeRead?.text.orEmpty()
        val beforeFromDocStart = replaceSelection || beforeRead?.fromDocStart == true
        // 读失败（null）与「光标后确实没有内容」（空串）必须分开：整行 / 整篇把失败当空串会
        // 静默退化成「只翻光标前」，且 appendOffsetExact 会误判成「末尾已确认」
        // （2026-10-01 复审 L-237）
        val after = if (needAfter) readTextAfterCursor(connection, limit) else ""
        if (after == null) {
            Diagnostics.w(TAG, "翻译: 读不到光标后内容，拒绝发起（范围=${scope.id}）")
            toast(TEXT_TRANSLATE_AFTER_UNREADABLE)
            return
        }
        // 读满窗口 = 可能还有内容（分不清「恰好读完」与「被截断」⇒ 按后者处理，见 L-213）
        val afterTruncated = needAfter && after.length >= limit
        val slice = if (replaceSelection) {
            TranslationText.extractSelection(before, maxBytes)
        } else {
            TranslationText.extract(before, after, scope, maxBytes, afterTruncated)
        }
        // 选区模式**不能**在被截断时替换（2026-10-01 审查 L-261）：截断只作用于上传的那一份，
        // 服务端返回的译文也只覆盖选中的前一段，拿它替换**整个选区**会把后半段原文删掉，
        // 而 `commitText` 不保证可撤销。这里在发请求之前就拒绝 —— 既不花钱，也能把原因说清。
        if (replaceSelection && slice.truncated) {
            Diagnostics.w(
                TAG,
                "翻译: 选中内容超单次上限（${before.length} 字 > $maxBytes 字节），拒绝替换",
            )
            toast(TEXT_TRANSLATE_SELECTION_TOO_LONG)
            return
        }
        // ② 追加位置不可确认 —— 原先排在 `appendTranslation` 里，用户要等完整一轮（大模型可能几十秒）
        //    并**为它付费**，最后才被告知「未追加译文」。同一句提示，提到发请求之前零成本（L-278）。
        if (!replaceSelection && slice.appendOffset > 0 && !slice.appendOffsetExact) {
            Diagnostics.w(
                TAG,
                "翻译: 原文范围超出读取窗口，末尾无法确认（offset=${slice.appendOffset}）",
            )
            toast(TEXT_TRANSLATE_RANGE_TOO_LONG)
            return
        }
        // ②.b 与上一条是**两回事**：这条查的是「宿主能不能给出精确选区」。
        //    放行侧（canAppendTranslation）把「读不到精确选区」当成「明确无选区」而放行，
        //    但提交侧 `moveCursorByOffset` 依赖同一能力、此时**必然**失败
        //    （WebView 类宿主对 getExtractedText 恒返回 null，那条注释自己写明了）⇒
        //    每次点击都是「真计费 + 等一轮（大模型几十秒） + 丢译文」。
        //    在发请求前用同一事实拦下，成本为零（2026-10-02 修复 L-482）。
        //    判据取**本帧已读的探针**（`selectionRange.range`，L-325/L-315 那次为此专门包的），
        //    不再裸调一次 `currentSelectionRange` —— 那是主线程上的整窗口 Binder 往返
        //    （`getExtractedText` 上限 10 万字符），而这里要的正是「同一次点击里同一份事实」
        //    （2026-10-02 第二轮审查）。
        if (!replaceSelection && slice.appendOffset > 0 && selectionRange.range == null) {
            Diagnostics.w(
                TAG,
                "翻译: 宿主读不到精确选区，追加位置无法确认（offset=${slice.appendOffset}）",
            )
            toast(TEXT_TRANSLATE_APPEND_UNKNOWN)
            return
        }
        // ③ 光标前这段取不到**开头**时，上传的不是用户以为的那一段 —— `readTextBeforeCursor` 主路径
        //    取的是紧邻光标的那一段（L-277）。判据用读取路径给出的事实（`beforeFromDocStart`），不再用
        //    「读满窗口」反推：兜底路径返回的是「窗口起点 → 光标」，长度天然小于窗口，窗口起点非零时
        //    照样取不到开头（2026-10-01 修复 L-286）。
        if (!replaceSelection && !beforeFromDocStart) {
            // 「从文首」与「整篇」两个范围档承诺的就是从头开始 ⇒ 内容口径已违背，拒绝发起。
            // 此前只拦 BEFORE_ALL，`ALL` 会把「光标前末段 + 光标后一段」当成全篇（2026-10-01 修复 L-285）。
            val needsDocStart = scope == TranslationScope.BEFORE_ALL || scope == TranslationScope.ALL
            if (needsDocStart) {
                Diagnostics.w(
                    TAG,
                    "翻译: 光标前内容取不到文首（范围=${scope.id} 读得 ${before.length} 字），拒绝发起",
                )
                toast(TEXT_TRANSLATE_BEFORE_TOO_LONG)
                return
            }
            // 行内两个范围：窗口里只要出现过换行，最后一个换行就是真实行首，仍可信；一个换行都没有时
            // 行首同样定位不到（上传的是末段）。位置仍正确、只是内容偏多，所以只记日志不拦（L-285）。
            if ((scope == TranslationScope.LINE_BEFORE || scope == TranslationScope.LINE_FULL) &&
                !before.contains('\n')
            ) {
                Diagnostics.w(
                    TAG,
                    "翻译: 行首不可定位（范围=${scope.id} 窗口内无换行），上传的是光标前末段",
                )
            }
        }
        // 用 hasVisibleContent 而不是 isBlank：零宽字符（ZWSP / BOM / NBSP）不算内容 —— 否则
        // 「光标前只有一个从网页复制来的不可见字符」也会发出一次真实请求（2026-09-30 第二轮审查）
        if (!slice.text.hasVisibleContent()) {
            // 同函数其余每个早退都有 W + toast，这一条此前只有 toast ⇒ 用户报「弹了『没有可翻译的文字』」
            // 时诊断包里查不到这次点击（2026-10-01 修复 L-327）
            Diagnostics.w(TAG, "翻译: 光标前没有可见内容，未发起")
            toast(TEXT_TRANSLATE_EMPTY)
            return
        }
        val target = TranslationLanguage.of(prefs.translateTarget)
        translateInFlight = true
        val generation = ++translateGeneration
        pinyinKeyboard?.setTranslating(true)
        // 只记 provider / 语言 / 范围 / 字数与截断：待译正文与凭据都不进日志
        Diagnostics.i(
            TAG,
            "翻译: ${provider.javaClass.simpleName} → ${target.name} 范围=${scope.id} " +
                "共${slice.text.length}字" + (if (slice.truncated) "（超 $maxBytes 字节已截断）" else ""),
        )
        // 插入模式下的截断必须说出来（2026-10-02 修复 L-485）：同一件事（原文超单次上限）此前
        // 两条路径口径相反 —— 选区模式发请求前就拒绝并说明（替换会丢掉尾部原文，不可逆），
        // 插入模式只写日志。插入模式只翻了前半段、译文追加在区间末尾（原文一个字不动），
        // 拒绝反而更糟；但不告知的话用户会以为服务端漏译，往往重试 —— 每次都真计费。
        if (slice.truncated) {
            toast(TEXT_TRANSLATE_TRUNCATED)
        }
        val snapshot = TranslateSnapshot(
            before,
            after,
            limit,
            slice.text,
            needAfter,
            slice.appendOffset,
            replaceSelection,
        )
        // 看门狗（2026-10-01 审查 L-217）：OkHttp 在调用 onResponse **之前**就置了
        // `signalledCallback`，回调内部抛出的异常**不会**回落到 onFailure ⇒ 少了这道兜底，
        // `translateInFlight` 会永久为真、按钮永远「翻译中」。预算 = 超时上限 + 余量。
        ui.removeCallbacks(translateWatchdog)
        ui.postDelayed(translateWatchdog, TRANSLATE_WATCHDOG_MS)
        runCatching {
            TranslationClient.translate(provider, slice.text, target) { outcome ->
                // 回调在 OkHttp 的 IO 线程：切回主线程再碰视图与 InputConnection
                ui.post {
                    if (generation != translateGeneration) {
                        Diagnostics.w(TAG, "翻译: 丢弃过期结果 gen=$generation 当前=$translateGeneration")
                        return@post
                    }
                    when (outcome) {
                        is TranslationOutcome.Ok -> appendTranslation(connection, snapshot, outcome.text)
                        is TranslationOutcome.Fail -> {
                            Diagnostics.w(TAG, "翻译失败: ${outcome.error}")
                            toast(outcome.error.message)
                        }
                    }
                    // 收尾放在业务之后：本代际此刻仍是「当前」，提前作废会让上面的分支过期
                    finishTranslate()
                }
            }
        }.onFailure {
            // 请求装配/入队本身抛异常（client 初始化失败、URL 非法……）绝不能把 in-flight 留在
            // true：按钮会永久置灰、再没有复位路径（2026-09-30 审查发现）。
            Diagnostics.w(TAG, "翻译: 请求发起失败 ${it.javaClass.simpleName}")
            finishTranslate()
            toast(TranslationError.SERVER.message)
        }
    }

    /**
     * 会话边界（切换输入框 / 收起键盘 / 服务销毁）：作废在途翻译。
     *
     * 不取消网络请求本身（OkHttp 的 call 让它在后台自然结束），回调按代际丢弃 ——
     * 旧请求的译文绝不会落到用户已经切换后的新输入内容上。
     */
    private fun cancelTranslate() {
        finishTranslate()
    }

    /**
     * 一次翻译请求的输入快照：提交译文前用它校验「原始文本一个字符都没变」。
     *
     * 保存的是**读取时刻的原始读数**（未 trim、未按字节截断）与当时的读取长度、追加偏移：
     * 截取结果只用于上传，校验必须逐字比对原始文本 —— 2026-09-30 修过「拿截取结果当判据」
     * 造成的误判（原文本身带首尾空白时永远提示「输入已变化」）。
     */
    private class TranslateSnapshot(
        val before: String,
        val after: String,
        val limit: Int,
        /** 本次**实际发出去**的那一段（`slice.text`）：识别「服务端把原文当译文回显」用（L-319） */
        val sent: String,
        /** 该范围模式是否需要光标后文本（整行 / 整篇两种需要） */
        val needAfter: Boolean,
        /** 译文追加偏移：0 = 光标处；N = 光标后第 N 个单元（原文区间末尾） */
        val appendOffset: Int,
        /**
         * 选区模式：[before] 是用户选中的那一段，提交时用它替换选区（而非追加到光标处）。
         *
         * ⚠ 快照里**没有** `appendOffsetExact`（2026-10-02 清理 L-435）：那道「末尾不可确认」
         * 的闸在**发请求之前**（`startTranslate` 的 ②），`false` 的情形根本走不到构造这里。
         * 留一个恒为 true 的字段只会让后来者以为提交阶段另有守卫、从而漏掉真正要判的东西
         * （`Slice.appendOffsetExact` 本身仍是活的，供发请求前的拒绝使用）。
         */
        val replaceSelection: Boolean = false,
    )

    /**
     * 提交译文：前导换行 + 译文（原文一个字不动）。
     *
     * 提交前四道闸：有选区不提交（`commitText` 在选区上是替换语义）、输入快照逐字校验、
     * 追加位置必须确认移到位、译文超单条上限拒绝。
     */
    private fun appendTranslation(
        connection: android.view.inputmethod.InputConnection,
        snapshot: TranslateSnapshot,
        translated: String,
    ) {
        // ⓪ 提交前复查敏感框（2026-10-02 修复 L-337）：判据只在点翻译那一刻查过一次，而译文
        //    到达可能在几十秒后 —— 同一会话里宿主仍可能把输入框切成密码（WebView 动态改
        //    `type=password` 一类的做法很常见），此刻提交等于把译文落进敏感框。
        val info = currentInputEditorInfo
        if (info == null) {
            // 「读不到输入框属性」与「这是密码框」是两件事（2026-10-02 修复 L-438）：
            // 前者是宿主/框架没给属性（换框或重试即可），后者换框也没用 —— 提示要分开，
            // 别把前者的锅扣在敏感框上（同文件别处已按这个口径分文案）
            Diagnostics.w(TAG, "翻译: 提交前读不到 EditorInfo，丢弃译文")
            toast(TEXT_TRANSLATE_DISCARDED_UNREADABLE)
            return
        }
        if (InputFieldPrivacy.blocksTranslation(info.inputType, info.imeOptions)) {
            Diagnostics.w(TAG, "翻译: 提交前复查发现输入框已敏感，丢弃译文")
            toast(TEXT_TRANSLATE_PRIVATE_FIELD)
            return
        }
        // ① 两条路分别把关：
        //
        //    选区模式 —— 校验选中的那一段没变，然后**替换**它（`commitText` 在宿主存在选区时本就是
        //    替换语义，这里正是要用它）。用户选中的内容变了说明他改过输入，丢弃。
        //
        //    光标模式 —— 仍要求能确认「没有选区」：那里 `commitText` 是**插入**，用户请求期间长按
        //    选中了什么，译文就会把选中的内容顶掉。判据与发起前同源（[canAppendTranslation]）：
        //    WebView 类宿主拿不到精确选区，改由 `getSelectedText` 判定；判不出时放行，此时下面的
        //    原文逐字比对是最后一道网。
        if (snapshot.replaceSelection) {
            val nowSelected = readSelectedText(connection)
            if (nowSelected == null) {
                Diagnostics.w(TAG, "翻译: 提交时读不到选中内容，丢弃结果")
                toast(TEXT_TRANSLATE_SELECTION_STALE)
                return
            }
            if (nowSelected != snapshot.before) {
                Diagnostics.w(TAG, "翻译: 选中内容已变化，丢弃旧结果（len=${translated.length}）")
                toast(TEXT_TRANSLATE_SELECTION_STALE)
                return
            }
        } else {
            // 两种拒绝要给不同提示（2026-10-01 修复 L-284）：「刚出现了选区」用户取消选中即可；
            // 「宿主答不出有没有选区」与他做了什么无关 —— 此前一律说「输入已变化」，把它算到用户头上。
            when (canAppendTranslation(connection)) {
                AppendCheck.OK -> Unit
                AppendCheck.SELECTION_PRESENT -> {
                    Diagnostics.w(TAG, "翻译: 存在选区，丢弃结果以免覆盖选中文本")
                    toast(TEXT_TRANSLATE_STALE)
                    return
                }
                AppendCheck.HOST_UNREADABLE -> {
                    Diagnostics.w(TAG, "翻译: 宿主不支持选区检测，丢弃结果以免覆盖选中文本")
                    toast(TEXT_TRANSLATE_SELECTION_UNREADABLE)
                    return
                }
            }
        }
        // ② 输入快照校验（光标模式）：与请求时刻的**原始文本逐字相等**（用户续打 / 挪光标 / 宿主
        //    改动都会失配）。选区模式不走这里 —— 它的原文是选中的那一段，比对已在 ① 用
        //    `getSelectedText` 做过，再读「光标前」比的是另一段文本。
        //
        // ⚠ 判据必须是原始读数：截取结果被 trim 与字节截断加工过，拿它比对会把
        // 「原样未变」误判成「输入已变化」（2026-09-30 修过同类缺陷）。
        // 这次读回的「光标前文本」两用：① 与快照逐字比对；② 取它的末字符决定要不要补前导换行
        //（原先为那 1 个字符单独开一次 Binder 往返，2026-10-01 修复 L-315）。
        var beforeNow: String? = null
        if (!snapshot.replaceSelection) {
            beforeNow = readTextBeforeCursor(connection, snapshot.limit)?.text
            if (beforeNow == null) {
                // 「读不到」与「变了」是两回事：前者是宿主问题，别把原因指向用户（2026-10-01 修复 L-240）
                Diagnostics.w(TAG, "翻译: 提交时读不到光标前文本，丢弃结果")
                // 这里是**提交阶段**：请求早已发出、原文早已上传，而原本文案写着「未发起翻译」
                // ⇒ 用户会以为什么都没外发（2026-10-01 修复 L-327）
                toast(TEXT_TRANSLATE_DISCARDED_UNREADABLE)
                return
            }
            if (beforeNow != snapshot.before) {
                Diagnostics.w(TAG, "翻译: 输入已变化，丢弃旧结果（len=${translated.length}）")
                toast(TEXT_TRANSLATE_STALE)
                return
            }
        }
        // 光标后的文本同样要比：整行 / 整篇模式的原文含着它，请求期间改了下一行也要丢弃
        if (snapshot.needAfter) {
            val afterNow = readTextAfterCursor(connection, snapshot.limit)
            if (afterNow == null) {
                // 同上：读不到不是「用户改了输入」（2026-10-01 修复 L-242）
                Diagnostics.w(TAG, "翻译: 提交时读不到光标后文本，丢弃结果")
                toast(TEXT_TRANSLATE_DISCARDED_UNREADABLE)
                return
            }
            if (afterNow != snapshot.after) {
                Diagnostics.w(TAG, "翻译: 光标后文本已变化，丢弃旧结果（len=${translated.length}）")
                toast(TEXT_TRANSLATE_STALE)
                return
            }
        }
        // ③ 「追加位置不确定」与「before 被截断」这两条已提到发请求之前（见 startTranslate 内的
        //    前置判据，L-278 / L-277）—— 它们与快照内容无关，放在这里只会让用户白等一轮并付费。
        // ④ 译文的可见性与长度**先判**（2026-10-01 审查 L-221 / L-222）：这两道闸原先排在移动
        //    光标之后，拒绝时光标已被移走而正文一个字没加 —— 用户接着打字就打在原文区间末尾。
        // 净化在判空**之前**：`U+202E` 这类双向控制符既非空白也不在零宽表里，「有可见内容」成立，
        // 可它写进输入框会改变**用户自己那段文字**的显示顺序（2026-10-01 修复 L-314）。
        val body = translated.sanitizedForOutput().trim()
        if (!body.hasVisibleContent()) {
            Diagnostics.w(TAG, "翻译: 译文没有可见内容，不提交")
            // 空的是**译文**，而 `TEXT_TRANSLATE_EMPTY` 讲的是「光标前没有可翻译的文字」——
            // 此刻原文早已上传、且它确实有文字。同一真因在别处已有正确文案（2026-10-01 修复 L-327）。
            toast(TranslationError.EMPTY.message)
            return
        }
        // 服务端把原文当译文回显（网关回显请求字段 / 提示词被改坏 / 模型照抄输入）时，写回去等于把
        // 用户的话复制一遍。**不拒绝** —— 原文本身已经是目标语言时「译文与原文相同」是完全正常的
        // 结果（单词、专有名词、数字尤其常见），按失败处理会误伤。只记一条可辨认的便于事后归因
        //（2026-10-01 修复 L-319）。
        if (body == snapshot.sent.trim() && snapshot.sent.isNotBlank()) {
            Diagnostics.w(TAG, "翻译: 译文与所发原文逐字相同（可能是服务端回显，或原文本身已是目标语言）")
        }
        // 预留一个前导换行的余量。译文是**服务端返回的内容**（不可信输入），必须有与粘贴链路同款
        // 的单条上限闸 + runCatching：裸提交超长文本会撞 Binder 事务上限抛
        // TransactionTooLargeException，未捕获时直接崩掉整个 IME 进程（2026-09-16 实测过）。
        if (ClipboardStore.exceedsItemLimit(body + "\n")) {
            Diagnostics.w(TAG, "翻译: 译文超单条上限，拒绝提交 len=${body.length}")
            toast(TEXT_TRANSLATE_TOO_LONG)
            return
        }
        // ⑤ 追加位置：整行 / 整篇模式要把光标移到原文区间末尾，译文才落在整段之后。
        //    移不到位一律放弃 —— 否则译文插在光标处（原文中间），把用户的内容割开。
        if (snapshot.appendOffset > 0 && !moveCursorByOffset(connection, snapshot.appendOffset)) {
            Diagnostics.w(TAG, "翻译: 追加位置未能确认，放弃提交（避免插进原文中间）")
            // 这是**宿主问题**（不支持 getExtractedText，或忽略了光标移动），而 `TEXT_TRANSLATE_STALE`
            // 讲的是「输入已变化」—— 把宿主问题算到用户头上，而前一步的逐字比对刚证明输入没变
            //（2026-10-01 修复 L-327）。
            toast(TEXT_TRANSLATE_APPEND_UNKNOWN)
            return
        }
        // ⑤.b 移到「请求时的区间末尾」≠ 落点正确：宿主可能**少给** `getTextAfterCursor`
        //     （返回长度 < 请求长度、但文中仍有内容），此时 `appendOffset` 只到句子 / 文档中间，
        //     移过去也就在中间 ⇒ commitText 把译文插进**原文中间**（数据破坏，且不保证可撤销）。
        //     确认方式：落点之后要么没有内容，要么就是换行；读不到同样放弃（保守方向与
        //     「移不到位一律放弃」一致 —— 这类宿主已被发请求前的 ②.b 拦过一道）。
        //     （2026-10-02 修复 L-480）
        if (snapshot.appendOffset > 0) {
            val tail = runCatching { connection.getTextAfterCursor(1, 0) }.getOrNull()
            val atRangeEnd = tail != null && (tail.isEmpty() || tail[0] == '\n')
            if (!atRangeEnd) {
                Diagnostics.w(
                    TAG,
                    "翻译: 追加落点不是区间末尾（" +
                        (if (tail == null) "读不到光标后内容" else "后一个字符=${tail[0]}") +
                        "），放弃提交并回滚光标",
                )
                // ⚠ 拒绝前必须把光标**移回去**（2026-10-02 第二轮审查）：这一步排在 ⑤（移动）之后，
                // 直接 return 会把用户的光标留在「移过去但没确认」的位置，接着打字就插进句子中间 ——
                // 与 ④ 段立下的「拒绝前不得移动光标」原则相冲突（L-221 的唯一反例）。
                // 反向移回复用同一实现；移不回去也不再改文案（用户看到的仍是「未写入」，
                // 光标能否精确复位属宿主能力问题，记日志即可）。
                if (!moveCursorByOffset(connection, -snapshot.appendOffset)) {
                    Diagnostics.w(TAG, "翻译: 光标回滚失败（宿主忽略反向移动或越界），放弃继续处理")
                }
                toast(TEXT_TRANSLATE_APPEND_UNKNOWN)
                return
            }
        }
        // ⑥ 选区模式直接替换选中内容，**不加前导换行** —— 替换是原地操作，补换行会把用户的段落
        //    切碎。光标模式走到这里光标已在最终插入点，取它的前一个字符决定要不要补前导换行。
        val submit = if (snapshot.replaceSelection) {
            body
        } else {
            // 契约是「**插入点**前一个字符」，而 `beforeNow` 取自**原光标**：`appendOffset > 0`
            // （整行 / 整篇）时上面刚把光标移到原文区间末尾，两者的末字符往往不是同一个 ——
            // 照旧复用会出现「译文黏在原文行尾」或「多一个空行」（2026-10-01 修复 L-323）。
            // 因此只在光标没动过时才复用；其余情形仍读一次（L-315 的省读覆盖默认档与「光标前全部」）。
            val prev = if (snapshot.appendOffset == 0 && !beforeNow.isNullOrEmpty()) {
                beforeNow.last()
            } else {
                charBeforeCursor(connection)
            }
            TranslationText.appendText(prev, body)
        }
        // 提交**先做、日志后写**（2026-10-01 修复 L-320）：原先「已追加译文」写在 `commitText`
        // 之前，提交失败时诊断包里会先出现一句成功、再出现一句失败，拿它排障会得到相反的结论。
        // 失败也不再静默 —— 用户端至少要说一句，否则「点了没反应」只有他自己知道。
        // `commitText` **返回 false 不抛异常**（宿主连接已失效时的常见表现），只看异常会把「一个字都没
        // 写进去」记成成功（2026-10-01 修复 L-326）—— 仓库别处（编辑键那条）早就是这么写的。
        val outcome = runCatching { connection.commitText(submit, 1) }
        if (outcome.getOrDefault(false)) {
            // 译文属于用户内容：日志只记字数不记正文（与语音链路同口径）。
            // ⚠ 措辞不能写「已追加译文」—— `commitText` 返回 true 只代表**调用被接受**：
            // 宿主的 InputFilter（数字框的 `DigitsKeyListener` 是最常见的一种）会把正文整段
            // 丢掉而**不报错**，此时字段里一个字都没有（2026-10-02 修复 L-456）。
            // 写成「已写入」会让排障时把「没写进去」读成成功 —— 正好是这里唯一要避免的事。
            Diagnostics.i(
                TAG,
                "翻译: " + (if (snapshot.replaceSelection) "已提交替换" else "已提交追加") +
                    "（commitText 返回 true，未验落地）共${translated.length}字",
            )
        } else {
            // 失败时也带上长度：提交长度闸（256 KiB）与本机 Binder 事务上限不是同一把尺子，
            // 少了这个数字两者无法互相解释（2026-10-01 修复 L-326）
            Diagnostics.w(
                TAG,
                "翻译: 提交未成功（${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "返回 false"}）" +
                    " len=${submit.length}",
            )
            toast(TEXT_TRANSLATE_COMMIT_FAILED)
        }
    }

    /**
     * 把光标右移 [offset] 个单元（整行 / 整篇模式要把译文追加到原文区间末尾）。
     *
     * 返回是否**确认**移到位：`setSelection` 是异步提交，宿主可能忽略或压根不支持；移不到就
     * 不能提交 —— 否则译文会落在光标处的原文中间。二审失败即返回 false，调用方放弃本次提交。
     */
    private fun moveCursorByOffset(
        connection: android.view.inputmethod.InputConnection,
        offset: Int,
    ): Boolean {
        val range = currentSelectionRange(connection) ?: return false
        if (range.start != range.end) return false
        val to = range.end + offset
        // 下界校验：反向移动（回滚光标，见 ⑤.b）可能算出负下标 —— 负下标传给 setSelection
        // 属未定义行为（宿主可能抛异常或把光标丢到文首），直接拒绝（2026-10-02 第二轮审查）。
        if (to < 0) {
            Diagnostics.w(TAG, "翻译: 目标下标为负（$to），放弃移动")
            return false
        }
        if (to > range.startOffset + range.textLength) {
            Diagnostics.w(
                TAG,
                "翻译: 追加位置越出宿主文本窗口（$to > ${range.startOffset + range.textLength}）",
            )
            return false
        }
        applySelection(connection, to, to)
        val now = currentSelectionRange(connection)
        val moved = now != null && now.start == to && now.end == to
        if (!moved) Diagnostics.w(TAG, "翻译: 宿主未接受光标移动（目标 $to）")
        return moved
    }

    /**
     * 取「插入点前面那一个字符」，供 `TranslationText.appendText` 决定要不要补前导换行。
     *
     * 三态语义（2026-10-02 修复 L-481）：
     *  - **空串** = 光标确实在文首 ⇒ 返回 null（不补换行）✓
     *  - **抛异常 / 宿主返回 null** = *读不到*，与「文首」不是一回事 ⇒ 返回 `'\n'`（按「需要补换行」处理）。
     *    原先两者都塌缩成 null，于是读失败时译文直接**黏在原文行末**；而本函数的 KDoc 却写着
     *    「按需补处理」—— 文档与实现相反，属同一类静默失效。
     */
    private fun charBeforeCursor(connection: android.view.inputmethod.InputConnection): Char? {
        val s = runCatching { connection.getTextBeforeCursor(1, 0) }.getOrNull()
        // 三态，占位符必须**既不是 null 也不是 '\n'**（2026-10-02 修复 L-481，第二轮纠正）：
        //  - 空串（真文首）⇒ null ⇒ 不补前导换行；
        //  - 读不到（抛异常 / 宿主返回 null）⇒ 替换字符占位 ⇒ 让 `appendText` 走「补前导换行」。
        // ⚠ 上一版把读不到映射成 '\n'，而 `appendText` 里 `prev == null || prev == '\n'` 是**同一个
        // 分支** ⇒ 那次改动等价于没改（译文照样黏在原文行末），注释与守卫却都写成了「按需补换行」。
        // 无法区分时就按「前面有内容」处理：宁可在行间多一个换行，也不要把译文接在句尾。
        return if (s == null) '\uFFFD' else s.lastOrNull()
    }

    /**
     * 只读地取「光标前」的文本（**不复制、不写系统剪贴板**）。
     *
     * 主路径是 `getTextBeforeCursor`（IME 框架的只读查询，不改内容、不触发宿主回调）；
     * 宿主不支持时（返回 null）兜底 `getExtractedText` + 窗口下标换算，
     * 与 [currentSelectionRange] 同一套坐标口径。
     *
     * 返回 **null 表示读取失败**（宿主不支持 / 连接失效 / 事务过大），与「光标前确实没有内容」
     * （空串，光标在文首）严格区分 —— 把前者报成后者会把「宿主读不到」说成「你没写字」
     * （2026-10-01 修复 L-240）。
     */
    /**
     * 光标前的文本，外加「它是不是从文首开始的」。
     *
     * 后者**不能**由长度反推（2026-10-01 修复 L-286）：兜底路径返回的是「窗口起点 → 光标」，
     * 长度天然小于窗口长度，窗口起点非零时它照样不是从文首开始的。
     */
    private class BeforeText(val text: String, val fromDocStart: Boolean)

    private fun readTextBeforeCursor(
        connection: android.view.inputmethod.InputConnection,
        limit: Int,
    ): BeforeText? {
        // 整段包 runCatching（2026-09-30 第二轮审查）：两个 API 都是**同步 Binder 调用**，宿主可能抛
        // TransactionTooLargeException（窗口太大）/ DeadObjectException（宿主进程已死）/ SecurityException；
        // 未捕获时异常落在主线程消息里 ⇒ IME 进程直接崩（键盘消失、用户输入丢失）。
        // 读不到就返回 null，让上层按「读不到」处理 —— 与「写」侧 commitText 同口径。
        return runCatching {
            // `getTextBeforeCursor` 返回「紧邻光标的至多 limit 个字符」：取不满就说明光标前一共只有这么长
            // ⇒ 确实是从文首开始的（2026-10-01 修复 L-286）
            connection.getTextBeforeCursor(limit, 0)?.let {
                val s = it.toString()
                return@runCatching BeforeText(s, s.length < limit)
            }
            // hintMaxChars **不能留 0**：兜底路径拿到的会是整个窗口，不限长就让大文档整篇走 Binder
            // 回包 —— 正是上面那条 TransactionTooLargeException 的触发源。限制到读取上限即可
            // （取哪一段由 TranslationText.extract 按范围模式决定，多读的会被字节上限截掉）。
            val request = android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = limit }
            val extracted = connection.getExtractedText(request, 0) ?: return@runCatching null
            val text = extracted.text?.toString().orEmpty()
            // 与 [currentSelectionRange]（:591）同一口径：负下标 = 宿主没给出选区，按「读不到」处理。
            // 不能 `coerceAtLeast(0)` 成 0 —— 那会被读成「光标在文首」，用户看到的是
            // 「光标前没有可翻译的文字」，而真实原因是宿主不支持（2026-10-01 修复 L-240）。
            if (extracted.selectionEnd < 0) return@runCatching null
            val startOffset = extracted.startOffset.coerceAtLeast(0)
            val endRel = TextSelection.toWindowOffset(
                extracted.selectionEnd,
                startOffset,
                text.length,
            ) ?: return@runCatching null
            // 兜底返回的是「**窗口起点** → 光标」：只有当窗口本身就始于文首时，它才是从文首开始的
            BeforeText(text.substring(0, endRel.coerceIn(0, text.length)), startOffset == 0)
        }.getOrElse {
            Diagnostics.w(TAG, "翻译: 读取光标前文本失败 ${it.javaClass.simpleName}")
            null
        }
    }

    /**
     * 只读地取「光标后」的文本（「整行 / 整篇」两种原文范围需要）。
     *
     * 与 [readTextBeforeCursor] 完全同款：主路径 `getTextAfterCursor`，宿主不支持时兜底
     * `getExtractedText` + 窗口下标换算。
     *
     * 返回 **null 表示读取失败**，与「光标后确实没有内容」（空串，光标在文末）严格区分：调用方
     * （整行 / 整篇模式）拿到 null 必须拒绝发起 —— 把失败当空串会让这两种范围静默退化成
     * 「只翻光标前」，且「末尾不可确认」那道闸不会触发（2026-10-01 修复 L-237 与 L-242）。
     */
    private fun readTextAfterCursor(
        connection: android.view.inputmethod.InputConnection,
        limit: Int,
    ): String? {
        return runCatching {
            connection.getTextAfterCursor(limit, 0)?.let { return@runCatching it.toString() }
            val request = android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = limit }
            val extracted = connection.getExtractedText(request, 0) ?: return@runCatching null
            val text = extracted.text?.toString().orEmpty()
            if (extracted.selectionEnd < 0) return@runCatching null
            val startRel = TextSelection.toWindowOffset(
                extracted.selectionEnd,
                extracted.startOffset.coerceAtLeast(0),
                text.length,
            ) ?: return@runCatching null
            text.substring(startRel.coerceIn(0, text.length))
        }.getOrElse {
            Diagnostics.w(TAG, "翻译: 读取光标后文本失败 ${it.javaClass.simpleName}")
            null
        }
    }

    // ── 编辑键 ────────────────────────────────────────────────

    private fun commit(text: String) {
        // 诊断：记录上屏内容（键盘/语音两种来源都走这里），方便核对输入链路
        Diagnostics.v(TAG, "commit: \"${text.take(40)}\" (键盘模式=$keyboardMode)")
        // 打字/语音上屏走 commitText，不写系统剪贴板，不会触发剪贴板监听。
        // 这里【不能】调用 onOwnCommit()，否则标记会残留到下一次真实复制，
        // 导致用户复制的内容被误判为「自身操作」而跳过保存。
        // 兜底：宿主连接失效时裸调 commitText 会从主线程带走 IME 进程。**必须留痕** ——
        // 打字路径失败时用户只会看到「字没上去」，日志里得留下原因（2026-10-01 修复 L-241）
        runCatching { currentInputConnection?.commitText(text, 1) }
            .onFailure { Diagnostics.w(TAG, "commit: 上屏失败 ${it.javaClass.simpleName}") }
    }

    /**
     * 删除键三击（双击+长按）：清空输入框全部文本。
     *
     * 实现：光标折叠到 0 后删「光标之后」的全部内容。不再用 Ctrl+A + 删除：
     * 按 Android 的 `InputConnection.deleteSurroundingText` 规则，它只作用于选区前后、
     * 无法影响选区内容，AOSP `BaseInputConnection` 里全选后 `a=0` ⇒ 删除量为 0，
     * 旧写法在标准宿主上一个字符都删不掉，只在「宿主不接受 Ctrl+A」时误删光标前的半截文本。
     * 新写法不依赖宿主对快捷键的支持：`afterLength` 会被实现钳到文本末尾。
     */
    /**
     * 包住**同步的宿主调用**（`InputConnection` 的写操作与 `sendDownUpKeyEvents`）。
     *
     * 宿主进程被强停 / 崩溃后这些调用会抛 `DeadObjectException`（`sendDownUpKeyEvents` 尤其要注意：
     * AOSP 内部就是**裸调** `sendKeyEvent`，异常会直接冒到我们这里）。从主线程的点击回调上抛，
     * 就会被 `Diagnostics.installCrashHandler` 接手 ⇒ **整个 IME 进程终止**：键盘消失，未上屏的
     * 组合串、暂存粘贴、在途翻译一起丢，用户只看到「键盘没了」。
     *
     * 项目里 `pasteClipboard` / `deleteAllText` / `appendTranslation` 早已各自兜底，本条
     * （2026-10-02 修复 L-404）把**编辑与面板路径**上剩下的裸调用统一收口。⚠ **只覆盖这三类路径**：
     * 语音链路的调用点按红线只记录不改（那几处的异常面另有记录）。
     *
     * 失败一律降级为「这次操作没生效」+ 一条 W（类名），不记正文、不再往上抛。
     */
    private inline fun guardHostCall(what: String, block: () -> Unit): Boolean =
        runCatching(block).onFailure {
            Diagnostics.w(TAG, "$what: 宿主调用失败 ${it.javaClass.simpleName}")
        }.isSuccess

    private fun deleteAllText() {
        Diagnostics.i(TAG, "deleteAllText: 双击+长按退格，清空全部文本")
        val connection = currentInputConnection ?: return
        // 不登记期望值：清空后光标归零，此时若还挂着拖选，让它退出才是对的（见 SelectionExpectations）
        // 两个同步 Binder 调用都要兜底：宿主连接失效时裸调会从主线程带走 IME 进程
        // （2026-10-01 修复 L-241）
        // 走统一的 [guardHostCall]（2026-10-02 收口）：此前是这里内联一份 `runCatching`，
        // 形态与别处不同 ⇒ 守卫没法用同一判据扫全部路径
        val ok = guardHostCall("deleteAllText") {
            connection.setSelection(0, 0)
            connection.deleteSurroundingText(0, Int.MAX_VALUE)
        }
        if (ok) Diagnostics.i(TAG, "deleteAllText: 已发送清空（光标归零 + 删至末尾）")
    }

    /**
     * 全选：选中输入框全部文本（Ctrl+A）。
     * 与 [deleteAllText] 不同，本方法只选中不删除，配合「复制」使用。
     */
    private fun selectAllText() {
        Diagnostics.i(TAG, "selectAllText: 全选")
        val connection = currentInputConnection ?: return
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        // 两个同步 Binder 调用都要兜底（L-404）：宿主被杀后按「全选」是**最小复现路径**
        guardHostCall("selectAllText") {
            connection.sendKeyEvent(makeCtrlKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, meta))
            connection.sendKeyEvent(makeCtrlKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, meta))
        }
    }

    private fun makeCtrlKeyEvent(keyCode: Int, action: Int, meta: Int): KeyEvent =
        KeyEvent(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            action, keyCode, 0, meta,
        )

    /** 退格支持长按连删，纯语音输入改错字全靠它 */
    private fun bindBackspace(key: View) {
        key.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    ui.postDelayed(backspaceRunnable, BACKSPACE_DELAY_MS)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    ui.removeCallbacks(backspaceRunnable)
                    // 无障碍：抬手时补 performClick（CANCEL 不算点击，不补）
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 回车键：有拼音串时先按「原始按键」上屏英文，否则执行输入框声明的动作、再退化为换行。
     */
    private fun performEnter() {
        val connection = currentInputConnection ?: return

        // 拼音串非空说明用户已经打了字、候选栏也在显示：
        // 此时回车 = 把实际按下的键原样上屏（全拼 but→but；双拼 budv→budv，
        // 不做双拼→全拼转换），而不是选中候选、也不是换行。
        val rawComposing = pinyinKeyboard?.takeRawComposing().orEmpty()
        if (rawComposing.isNotEmpty()) {
            guardHostCall("performEnter(原始按键上屏)") { connection.commitText(rawComposing, 1) }
            return
        }

        val editorInfo = currentInputEditorInfo
        val imeOptions = editorInfo?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val actionDisabled = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        // 宿主用 `setImeActionLabel(label, customActionId)` 时会带一个**自己的**动作 id
        // （`SearchView` 与大量「搜索 / 提交」按钮的标准写法），而它的 `OnEditorActionListener`
        // 只认这个 id —— 只发标准动作码会让搜索 / 提交不触发；更糟的是下面 `hasAction` 为真，
        // 于是也不会退回发回车键，用户按回车「完全没反应」且日志里没有痕迹
        //（2026-10-02 修复 L-455）。`IME_FLAG_NO_ENTER_ACTION` 仍然优先：宿主声明了就别动作。
        val customActionId = editorInfo?.actionId ?: 0

        val hasAction = !actionDisabled &&
            (customActionId != 0 ||
                (action != EditorInfo.IME_ACTION_NONE &&
                    action != EditorInfo.IME_ACTION_UNSPECIFIED))

        if (hasAction) {
            if (customActionId != 0) {
                // 只记事实，不写归因：宿主认哪个码我们无从得知（曾写「不是它认的码」，那是未证实的断言）
                Diagnostics.i(TAG, "performEnter: 走宿主自定义动作 id=$customActionId（imeOptions 动作位=$action）")
                guardHostCall("performEnter(输入框自定义动作)") {
                    connection.performEditorAction(customActionId)
                }
            } else {
                guardHostCall("performEnter(输入框动作)") { connection.performEditorAction(action) }
            }
        } else {
            // `sendDownUpKeyEvents` 是 InputMethodService 的方法，AOSP 内部**裸调** `sendKeyEvent`
            // ⇒ 宿主死亡时异常会原样冒到我们这里，同样要包（L-404）
            guardHostCall("performEnter(回车键)") { sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) }
        }
    }

    private fun showImePicker() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showInputMethodPicker()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "openSettings: ${it.message}") }
    }

    // ── 状态渲染 ──────────────────────────────────────────────

    private fun renderLink(state: LinkState, detail: String?) {
        Diagnostics.v(TAG, "renderLink: state=$state detail=$detail mode=$mode")
        val colorRes: Int
        val label: String
        when (state) {
            LinkState.ONLINE -> {
                colorRes = R.color.dot_online
                label = getString(R.string.status_online, prefs.host, prefs.port)
            }

            LinkState.CONNECTING -> {
                colorRes = R.color.dot_connecting
                label = getString(R.string.status_connecting)
            }

            else -> {
                colorRes = R.color.dot_offline
                label = detail ?: getString(R.string.status_offline)
                // 录音中突然掉线：服务端永远收不到收尾包，主动放弃本次听写
                // 避免用户白白说话等不到结果，优于让 VAD 或松手自欺"识别中…"
                if (mode != Mode.NONE) {
                    stopRecording(commit = false)
                    setHint(getString(R.string.hint_not_connected))
                } else if (awaitingResult) {
                    // 松手后在途时掉线：服务端不可能再回结果，把「识别中…」复位。
                    // 唯一的复位点原本是下次弹键盘，用户会把这条提示读成「还在识别」
                    awaitingResult = false
                    voiceResultPackage = null
                    ui.removeCallbacks(recognizeTimeout)
                    setHint(getString(R.string.hint_not_connected))
                }
            }
        }
        statusDot?.backgroundTintList = ColorStateList.valueOf((keyboardThemeCtx ?: this).getColor(colorRes))
        statusLabel?.text = label
    }

    private fun setStatusText(text: String) {
        statusLabel?.text = text
    }

    private fun setHint(text: String) {
        hintLabel?.text = text
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val TAG = "JinnIme"

        /** 在线翻译（BYOK）的本机提示文案（文案在代码里下发，与文件内既有 Toast 写法一致） */
        const val TEXT_TRANSLATE_EMPTY = "光标前没有可翻译的文字"
        const val TEXT_TRANSLATE_PRIVATE_FIELD = "密码框不翻译（避免口令外发）"

        /** 选区超单条上限时的复制提示（2026-10-02 修复 L-406：与粘贴链路同一道闸） */
        const val TEXT_COPY_TOO_LONG = "内容过大，未复制"

        /** 写系统剪贴板失败（2026-10-02 修复 L-439：此前只记日志，用户按了没反应） */
        const val TEXT_COPY_FAILED = "复制失败，请重试"

        /** 结果回来时输入已变（续打 / 挪光标 / 有选区）：丢弃必须说话，否则用户以为「翻译坏了」 */
        const val TEXT_TRANSLATE_STALE = "输入已变化，未追加译文"

        /** 译文超单条上限（与粘贴链路同一道闸，见 ClipboardStore.MAX_ITEM_BYTES） */
        const val TEXT_TRANSLATE_TOO_LONG = "译文过大，未追加"

        /** 原文范围超出读取窗口、末尾位置无法确认（L-213）：丢弃必须说清原因 */
        const val TEXT_TRANSLATE_RANGE_TOO_LONG = "范围过大，未确认原文末尾，未追加译文"

        /**
         * 宿主不提供选区信息（`getExtractedText` 不可用）：无法确认提交会不会覆盖选中内容。
         *
         * 独立文案是硬要求（2026-10-01 复审 L-230）：复用「输入已变化」会把「输入框不支持」
         * 说成「用户自己的操作」，排障方向整个带偏。
         */

        /** 请求发起时已有选区（2026-10-01 复审 L-236）：选中的正文会随原文上传，且提交必被拒 */
        const val TEXT_TRANSLATE_SELECTION_ACTIVE = "请先取消选中的内容再翻译"

        /**
         * 宿主既不提供精确选区、又答不出「有没有选中内容」（`getSelectedText` 抛异常）——
         * WebView 类宿主上常见。与上面那条是**两种不同的拒绝**，不能共用文案（2026-10-01 修复 L-284）：
         * 用户并没有选中什么，让他「先取消选中的内容」是无解的。提交阶段也用这一条 —— 它只说
         * 「无法确认能否安全写入」，不暗示「没发起过请求」。
         */
        const val TEXT_TRANSLATE_SELECTION_UNREADABLE =
            "当前输入框不支持选区检测，无法确认能否安全写入（换一个输入框再试）"

        /** 译文没能写进输入框（宿主拒绝 / 连接已失效）；此前这条路径只落日志、用户端静默（L-320） */
        const val TEXT_TRANSLATE_COMMIT_FAILED = "译文未能写入输入框（当前输入框可能已失效）"

        /**
         * 提交阶段读不到文本、只能丢弃结果（2026-10-01 修复 L-327）。
         *
         * 不能复用发起前那两条 —— 它们写着「**未发起**翻译」，而此刻请求早已发出、原文早已上传，
         * 会让用户误以为什么都没外发（L-264 为选区模式立下的同一判据）。
         */
        const val TEXT_TRANSLATE_DISCARDED_UNREADABLE = "当前输入框读不到内容，译文未写入"

        /** 剪贴板面板打开时不许翻译（2026-10-02 修复）：随后的粘贴必然让这次请求作废 */
        const val TEXT_TRANSLATE_CLIPBOARD_OPEN = "剪贴板面板打开时不能翻译，请先按「返回」"

        /** 追加位置无法确认（宿主不支持 getExtractedText，或忽略了光标移动）：宿主问题，别说成「输入已变化」（L-327） */
        const val TEXT_TRANSLATE_APPEND_UNKNOWN = "无法确认追加位置，译文未写入"

        /** 光标后内容读不到（2026-10-01 复审 L-237）：整行 / 整篇无法确定原文末尾，不发起 */
        const val TEXT_TRANSLATE_AFTER_UNREADABLE = "当前输入框读不到光标后内容，未发起翻译"

        /**
         * 选区模式专用：提交阶段读不到或读不回选中的内容（2026-10-01 审查 L-264）。
         *
         * 不能复用光标模式的两条 —— `TEXT_TRANSLATE_STALE` 说的是「未**追加**译文」（选区模式是替换），
         * `TEXT_TRANSLATE_BEFORE_UNREADABLE` 更写着「**未发起翻译**」，而此刻请求早已发出、原文早已上传，
         * 会让用户误以为什么都没外发。
         */
        const val TEXT_TRANSLATE_SELECTION_STALE = "选中内容已变化或读不到，未替换"

        /** 「光标前全部」在读取窗口被截断时取不到文首（2026-10-01 审查 L-277） */
        const val TEXT_TRANSLATE_BEFORE_TOO_LONG =
            "光标前内容超出读取上限，无法翻「从文首」的全部（可改用「光标前本行」）"

        /** 选区模式专用：选中内容超上限时替换会丢掉尾部原文，宁可拒绝（2026-10-01 审查 L-261） */
        const val TEXT_TRANSLATE_SELECTION_TOO_LONG =
            "选中内容超出单次上限，未翻译（缩短选择，或在 翻译设置 → 翻译原文范围 里调大字节上限）"

        /**
         * 插入模式专用：原文超单次上限、已按上限截断（2026-10-02 修复 L-485）。
         *
         * 与选区模式两套口径是**故意的**：选区模式的替换会丢掉尾部原文（不可逆）⇒ 宁可拒绝；
         * 插入模式只翻了前半段、译文追加在区间末尾（原文一个字不动）⇒ 拒绝反而更糟。
         * 但不告知的话，用户会以为服务端漏译并反复重试 —— 每次都真计费。
         */
        const val TEXT_TRANSLATE_TRUNCATED =
            "原文超出单次上限，已按上限截断（仅翻译前半段；可在 翻译设置 → 翻译原文范围 里调大字节上限）"

        /** 光标前内容读不到（2026-10-01 修复 L-240）：与「光标前没有文字」区分，不发起 */
        const val TEXT_TRANSLATE_BEFORE_UNREADABLE = "当前输入框读不到光标前内容，未发起翻译"

        /**
         * 看门狗预算（毫秒）：覆盖最慢一家的整体超时上限（300s）再留 30s 余量（L-217）。
         *
         * 不按 Provider 分别取值 —— 早复位晚复位都不影响正确性：真超时的话 OkHttp 会先按
         * `callTimeout` 结束并走正常失败回调，看门狗只兜「回调根本没到达」那一种。
         */
        private const val TRANSLATE_WATCHDOG_MS = 330_000L

        /**
         * 同进程的 IME 实例：设置页改主题时直接通知它换肤（设置页与 IME 同进程，无需跨进程通信）。
         *
         * 用弱引用持有：静态强引用 Service 会被 lint 判为 `StaticFieldLeak`，且语义上
         * 不需要延长其生命周期，服务存活期间系统自有强引用，`onDestroy` 亦会置空。
         */
        @Volatile
        private var instance: WeakReference<JinnIme>? = null

        /** 设置页改主题后调用（主线程）：键盘正显示时立即按新色板重建；尚未创建则等下次弹出自然读取 */
        fun notifyThemeChanged() {
            val ime = instance?.get() ?: return
            ime.ui.post { ime.applyThemeIfNeeded() }
        }

        /**
         * 设置页调整符号分组顺序 / 编辑收藏符号后调用（主线程）：键盘视图已创建则重建，立即生效 ，
         * 有未上屏输入时延后，由下次弹键盘（[onStartInputView]）补一次重建
         * （见 [JinnIme.rebuildInputViewForSymbolLayout]）；尚未创建（inputView == null）时不做事：
         * 下次创建自然读到新数据。
         */
        fun onSymbolLayoutChanged() {
            val ime = instance?.get() ?: return
            ime.ui.post { ime.rebuildInputViewForSymbolLayout() }
        }

        /**
         * 键盘外观页拖动松手后调用（主线程）：键盘正显示时即时套用新的透明度/圆角/间隙。
         *
         * 为什么需要：外观参数原先只在 `PinyinKeyboardView.configure()`（每次输入框聚焦 / 弹键盘）时读取，
         * 于是在本应用的页面里边拖边看时键盘毫无变化，看上去像「透明度不生效」，
         * 而切到别的应用（键盘会重新走一次会话）就见效，真机实证 2026-09-22：
         * 同一次会话内改滑杆，键盘像素完全不变；收起键盘再弹出才变。
         */
        fun onKeyAppearanceChanged() {
            val ime = instance?.get() ?: return
            ime.ui.post {
                ime.pinyinKeyboard?.refreshAppearance()
                // 语音面板与 26 键面板同属「键盘面」：不同步刷新的话，
                // 默认语音模式下拖滑杆将毫无反应（只能重弹键盘才生效）。
                ime.applyTransparencyToVoicePanel()
            }
        }

        /** 键盘收起后多久视为「用户空闲」（太短会把「切个应用马上回来」也算空闲） */
        private const val OPTIONAL_IDLE_DELAY_MS = 20_000L

        /** 兜底等待：既不息屏也不收键盘时的最晚加载时间 */
        private const val OPTIONAL_FALLBACK_DELAY_MS = 180_000L

        /** 设置页「保存配置」广播 action：收到后立即刷新连接与键盘配置 */
        const val ACTION_CONFIG_UPDATED = "com.jinn.inputmethod.action.CONFIG_UPDATED"

        /**
         * 暂存粘贴文本的有效期：剪贴板面板点击粘贴但 IME 无连接时暂存，
         * 编辑框重新聚焦（onStartInputView）时提交。超过该时长视为过期丢弃，
         * 防止旧内容被粘到用户当前无关的其它输入框。
         */
        const val PENDING_PASTE_TTL_MS = 10_000L

        /** 超过这个时长判定为"按住说话" */
        const val HOLD_THRESHOLD_MS = 260L

        /** 按住时上滑超过这个距离即进入取消状态 */
        const val CANCEL_SLIDE_DP = 64f

        /** 连续录音的最长时长，3 分钟 */
        const val MAX_TOGGLE_MS = 180_000L

        /**
         * 松手后等最终识别结果的兜底超时。
         *
         * 取 60s：3 分钟上限的长语音，服务端识别十几秒属正常量级，留足余量再判超时；
         * 超时只复位状态条，不会影响后续听写。
         */
        const val RECOGNIZE_TIMEOUT_MS = 60_000L

        const val BACKSPACE_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_MS = 55L

        /** 对齐 config_client.py 的 trash_punc */
        val TRAILING_PUNC = charArrayOf('，', '。', ',', '.')
    }
}

/**
 * 输入框身份键：包名 + `#` + fieldId（纯函数，便于单测）。
 *
 * 规则：
 *  - 包名缺失/为空时返回 null，身份未知，调用方退回「时效窗口 + 会话边界」兜底；
 *  - fieldId 必须带上包名：不同 App 的 fieldId 会撞号，只比对 fieldId 会放行跨应用粘贴。
 *
 * 本函数是「暂存粘贴只允许提交回原输入框」这条安全约束的唯一判据，
 * 改动会直接影响剪贴板正文是否会被注入到无关输入框。
 */
internal fun fieldKeyOf(packageName: String?, fieldId: Int): String? =
    if (packageName.isNullOrEmpty()) null else "$packageName#$fieldId"
