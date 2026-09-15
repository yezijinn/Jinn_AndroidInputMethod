package com.jinn.inputmethod

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.TextView

/**
 * Jinn 安卓输入法。
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

    private lateinit var prefs: Prefs
    private lateinit var asr: AsrClient
    private lateinit var recorder: MicRecorder

    /** 剪贴板控制器：监听系统剪贴板 → 按策略加密保存历史（普通模式核心） */
    private var clipboardController: ClipboardController? = null

    private var micButton: MicButton? = null
    private var statusDot: View? = null
    private var statusLabel: TextView? = null
    private var hintLabel: TextView? = null

    private var pinyinKeyboard: PinyinKeyboardView? = null
    private var keyboardMode = KeyboardMode.VOICE
    private var keyboardContainer: FrameLayout? = null

    /** 剪贴板页点击记录时若连接无效，暂存待粘贴文本，编辑框聚焦后自动粘贴 */
    private var pendingPasteText: String? = null
    /**
     * [pendingPasteText] 的暂存时刻。
     *
     * 暂存文本只在短时间内有效（[PENDING_PASTE_TTL_MS]）：用户点完剪贴板条目后
     * 若很久才切回输入框，或中途换了别的输入框，过期的暂存文本会被静默丢弃——
     * 否则一次 onStartInputView 就会把旧内容粘到完全无关的位置。
     */
    private var pendingPasteAt = 0L

    /** 剪贴板面板打开标记：跨键盘视图实例持久（IME relayout 重建视图后自动恢复） */
    private var clipboardPanelOpen = false

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

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
        prefs = Prefs(this)
        cancelSlidePx = CANCEL_SLIDE_DP * resources.displayMetrics.density
        // 按设置页配置的默认模式初始化键盘（语音 / 26键中文 / 26键英文）
        keyboardMode = when (prefs.defaultKeyboardMode) {
            DefaultKeyboardMode.PINYIN_CN,
            DefaultKeyboardMode.PINYIN_EN -> KeyboardMode.PINYIN
            else -> KeyboardMode.VOICE
        }
        Diagnostics.i(TAG, "onCreate: IME 服务创建（默认模式=$keyboardMode）")

        asr = AsrClient(
            prefs = prefs,
            onState = { state, detail -> ui.post { renderLink(state, detail) } },
            onResult = { message -> ui.post { handleResult(message) } },
        )
        recorder = MicRecorder(
            onChunk = { chunk -> asr.sendChunk(chunk) },
            onLevel = { level -> ui.post { micButton?.updateLevel(level) } },
            onError = { message -> ui.post { onRecorderError(message) } },
            onSilence = { ui.post { onSilenceDetected() } },
        )

        // 预热连接：首次弹键盘时 WebSocket 往往还没建好，
        // 不加这一步用户第一次点麦克风会因 beginTask() 返回 null 而"没反应"，得再点一次
        asr.connect()

        // 预加载拼音词库（约 1MB 文本，后台线程避免主线程卡顿）
        Thread {
            Diagnostics.i(TAG, "onCreate: 开始预加载拼音词库")
            val start = System.currentTimeMillis()
            PinyinEngine.load(this)
            Diagnostics.i(TAG, "onCreate: 词库加载完成，耗时 ${System.currentTimeMillis() - start}ms")
        }.start()

        // 剪贴板历史：启用时监听系统剪贴板，按策略加密保存
        if (ClipboardPrefs.of(this).enabled) {
            clipboardController = ClipboardController(this).also { it.start() }
        }

        // 监听网络恢复：WiFi↔热点切换导致 IP 变化时主动重连
        registerNetwork()

        // 监听设置页「保存配置」广播：参数改动立即生效，无需重启输入法进程。
        // Android 13+ 动态注册必须显式声明导出标志：同进程应用内广播用 NOT_EXPORTED。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(configReceiver, configFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(configReceiver, configFilter)
            }
        }.onFailure {
            Diagnostics.e(TAG, "onCreate: 注册配置广播失败", it)
        }

        // 监听剪贴板页面「点击记录 → 粘贴」广播：Activity 无法直接拿 InputConnection，
        // 通过广播把内容交给本服务用当前连接插入编辑框。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(clipboardPasteReceiver, clipboardPasteFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(clipboardPasteReceiver, clipboardPasteFilter)
            }
        }.onFailure {
            Diagnostics.e(TAG, "onCreate: 注册剪贴板粘贴广播失败", it)
        }
    }

    /** 剪贴板页面「点击记录粘贴」广播接收器 */
    private val clipboardPasteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(EXTRA_CLIPBOARD_PASTE_TEXT).orEmpty()
            val itemId = intent.getLongExtra(EXTRA_CLIPBOARD_PASTE_ITEM_ID, -1L)
            if (text.isEmpty()) {
                Diagnostics.w(TAG, "剪贴板粘贴广播: 内容为空")
                notifyPasteResult(itemId, false)
                return
            }
            Diagnostics.i(TAG, "剪贴板粘贴广播: len=${text.length}")
            val ok = pasteClipboardText(text)
            // 回传结果给剪贴板页：成功才允许它关闭，失败保持页面等待
            notifyPasteResult(itemId, ok)
        }
    }

    private val clipboardPasteFilter = IntentFilter().apply { addAction(ACTION_CLIPBOARD_PASTE) }

    /**
     * 把粘贴结果回传给剪贴板页（成功才允许自动关闭页面）。
     * @param itemId 关联点击的剪贴板记录（用于防串线）
     */
    private fun notifyPasteResult(itemId: Long, success: Boolean) {
        runCatching {
            sendBroadcast(
                Intent(ACTION_CLIPBOARD_PASTE_RESULT)
                    .setPackage(packageName)
                    .putExtra(EXTRA_CLIPBOARD_PASTE_ITEM_ID, itemId)
                    .putExtra(EXTRA_CLIPBOARD_PASTE_SUCCESS, success)
            )
        }.onFailure {
            Diagnostics.w(TAG, "回传粘贴结果广播失败: ${it.message}")
        }
    }

    /** 通过当前 InputConnection 粘贴文本（无效连接不崩溃）。返回是否成功提交。 */
    private fun pasteClipboardText(text: String): Boolean {
        val connection = currentInputConnection
        if (connection == null) {
            // 剪贴板页在前台时 IME 可能无有效连接：暂存，并主动唤起键盘，
            // 触发 onStartInputView → flushPendingPaste 自动提交。
            // 此处未真正提交，返回 false（剪贴板页不关闭，用户可等待或返回）。
            Diagnostics.w(TAG, "粘贴: 当前无有效 InputConnection，暂存并唤起键盘")
            pendingPasteText = text
            pendingPasteAt = System.currentTimeMillis()
            // 自动唤起键盘关闭时不主动 requestShowSelf（即使调用了也会被
            // onShowInputRequested 拒绝并再次触发隐藏逻辑，这里直接不发起）。
            if (!prefs.autoShowKeyboard) {
                Diagnostics.i(TAG, "自动唤起键盘：关闭，暂存粘贴文本但不唤起 IME")
                return false
            }
            // requestShowSelf 是 API 28 才有的方法（minSdk 26）：低版本直接跳过，
            // 否则会抛 NoSuchMethodError——虽被 runCatching 兜住不崩溃，
            // 但功能静默失效且空 onFailure 违反「绝不静默吞异常」的约定。
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
        if (ok) {
            // 自身粘贴产生剪贴板变化，标记避免被历史保存
            clipboardController?.onOwnCommit()
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
     * 执行方向控制动作（通过当前 InputConnection）。
     *
     * 拖选状态机（NORMAL_CURSOR ↔ TEXT_SELECTION_ACTIVE）：
     *  - 点击中心 ●：未激活 → 固定当前光标为 Anchor 并激活；已激活 → 清除选区、
     *    退出拖选、光标停在 Focus 位置（不跳回 Anchor）
     *  - 激活时方向键/行首/行末：只移动 **Focus**，Anchor 固定不变，
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
            connection.setSelection(end, end)
            Diagnostics.i(TAG, "拖选取消: 光标停在 Focus=$end")
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
     * **绝不发送 DPAD / MOVE_HOME / MOVE_END KeyEvent**——那些会触发
     * 目标 APP 的 View Focus Navigation（按钮/输入框/控件获得焦点）。
     * 正确行为：读取当前光标位置，计算新位置，setSelection 更新光标，
     * View Focus 保持不变。
     */
    private fun moveCursor(connection: android.view.inputmethod.InputConnection, action: PinyinKeyboardView.DirectionAction) {
        val range = currentSelectionRange(connection) ?: return
        val cursor = range.start
        val newPos = when (action) {
            PinyinKeyboardView.DirectionAction.LEFT -> (cursor - 1).coerceAtLeast(0)
            PinyinKeyboardView.DirectionAction.RIGHT -> (cursor + 1).coerceAtMost(range.textLength)
            PinyinKeyboardView.DirectionAction.UP -> TextSelection.moveLine(range.text, cursor, up = true)
            PinyinKeyboardView.DirectionAction.DOWN -> TextSelection.moveLine(range.text, cursor, up = false)
            PinyinKeyboardView.DirectionAction.LINE_START -> TextSelection.lineStart(range.text, cursor)
            PinyinKeyboardView.DirectionAction.LINE_END -> TextSelection.lineEnd(range.text, cursor)
            else -> cursor
        }
        if (newPos != cursor) {
            connection.setSelection(newPos, newPos)
            Diagnostics.i(TAG, "光标移动: $cursor → $newPos")
        }
    }

    /**
     * 拖选扩展：**只移动 Focus**，Anchor 固定不变。
     * 选区始终 = setSelection(min(Anchor,Focus), max(Anchor,Focus))，
     * 不依赖 Android 当前的 selectionStart/End（避免归一化导致 Anchor/Focus 漂移）。
     * 行首/行末把 Focus 跳到行首/行末；上/下按行移动并保持列位置。
     */
    private fun extendSelection(connection: android.view.inputmethod.InputConnection, action: PinyinKeyboardView.DirectionAction) {
        val range = currentSelectionRange(connection) ?: return
        val textRange = TextSelection.Range(range.start, range.end, range.textLength, range.text)
        val focus = TextSelection.nextFocus(
            textRange,
            selectionFocus,
            action,
        )
        selectionFocus = focus
        // Anchor 固定，Focus 可移动：选区两端取 min/max
        val (start, end) = TextSelection.normalizedSelection(selectionAnchor, selectionFocus)
        connection.setSelection(start, end)
        Diagnostics.i(TAG, "拖选扩展: Anchor=${selectionAnchor} Focus=$focus → 选区[$start,$end]")
    }

    /** 读取当前选区范围（未选中时 start == end == 光标位置） */
    private fun currentSelectionRange(connection: android.view.inputmethod.InputConnection): SelectionRange? {
        val extracted = connection.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest(), 0
        ) ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        val start = extracted.selectionStart.coerceAtLeast(0)
        val end = extracted.selectionEnd.coerceAtLeast(start)
        val text = extracted.text?.toString().orEmpty()
        return SelectionRange(start, end, text.length, text)
    }

    /** 复制选中文字到系统剪贴板（保持选区与拖选模式） */
    private fun copySelection() {
        val connection = currentInputConnection ?: return
        val extracted = connection.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest(), 0
        )
        val text = extracted?.text?.toString().orEmpty()
        if (extracted == null || extracted.selectionStart < 0 || extracted.selectionEnd <= extracted.selectionStart) {
            Diagnostics.w(TAG, "复制: 无选中文字")
            return
        }
        val sel = text.substring(extracted.selectionStart, extracted.selectionEnd)
        val clip = android.content.ClipData.newPlainText("jinn_selection", sel)
        clipboardManager.setPrimaryClip(clip)
        // 同时写入安全剪贴板历史（统一走 BackgroundIo 单线程，避免并发写库）
        val cpPrefs = ClipboardPrefs.of(this)
        if (cpPrefs.enabled) {
            BackgroundIo.run {
                ClipboardStore.save(this, ClipboardDb.get(this), sel, packageName, "本输入法")
            }
        }
        Diagnostics.i(TAG, "复制: 选中 ${sel.length} 字（保持选区与拖选模式）")
    }

    /** 粘贴：有选区替换，无选区在光标处插入 */
    private fun pasteClipboard() {
        val connection = currentInputConnection ?: return
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            Diagnostics.w(TAG, "粘贴: 剪贴板为空")
            return
        }
        connection.commitText(text, 1)
        Diagnostics.i(TAG, "粘贴: len=${text.length}")
        // 自身粘贴产生剪贴板变化，标记避免被历史保存
        clipboardController?.onOwnCommit()
    }

    /** 选区信息（start/end/textLength/text 全文，text 用于行级移动计算） */
    private data class SelectionRange(val start: Int, val end: Int, val textLength: Int, val text: String)

    /** 系统剪贴板 */
    private val clipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    /** 立即生效：强制按新地址重连 WebSocket + 重新同步键盘输入方案 */
    fun refreshConfig() {
        // 地址/端口变化：旧连接指向旧服务器，强制断开重连
        Diagnostics.i(TAG, "refreshConfig: 强制重连 ${prefs.wsUrl}")
        asr.connect(force = true)
        // 双拼/中英文方案变化：立即重新套用，键盘无需重建
        pinyinKeyboard?.configure(
            shuangpin = prefs.useShuangpin,
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

    override fun onCreateInputView(): View {
        Diagnostics.i(TAG, "onCreateInputView: 键盘视图创建")

        // 语音键盘
        val voice = LayoutInflater.from(this).inflate(R.layout.keyboard, null)
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

        // 拼音键盘
        val pinyin = PinyinKeyboardView(this).apply {
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
                        // 面板打开/关闭时清除拖选状态，避免 anchor/focus 残留
                        selectionActive = false
                        selectionAnchor = -1
                        selectionFocus = -1
                    }
                }
                override fun onDirectionAction(action: PinyinKeyboardView.DirectionAction) {
                    Diagnostics.i(TAG, "方向按键: $action")
                    executeDirection(action)
                }
                override fun onSelectionModeChanged(active: Boolean) {
                    Diagnostics.i(TAG, "拖选模式变化: $active")
                    // 面板关闭/键盘重置时同步清除拖选状态，避免 anchor/focus 残留
                    if (!active) {
                        selectionActive = false
                        selectionAnchor = -1
                        selectionFocus = -1
                    }
                    pinyinKeyboard?.setSelectionActive(active)
                }
                override fun onPasteClipboard() {
                    Diagnostics.i(TAG, "功能面板: 粘贴剪贴板")
                    pasteClipboard()
                }
                override fun onSelectAll() {
                    Diagnostics.i(TAG, "功能面板: 全选")
                    selectAllText()
                }
                override fun onCopy() {
                    Diagnostics.i(TAG, "功能面板: 复制")
                    copySelection()
                }
                override fun onHideKeyboard() {
                    Diagnostics.i(TAG, "功能面板: 收起键盘")
                    // 只隐藏输入面板，服务保持运行，点击输入框再次唤醒
                    runCatching { requestHideSelf(0) }
                        .onFailure { Diagnostics.w(TAG, "收起键盘失败: ${it.message}") }
                }
            }
            configure(
                shuangpin = prefs.useShuangpin,
                // 26键英文模式：启动时强制英文；其余模式沿用键盘英文偏好
                english = if (prefs.defaultKeyboardMode == DefaultKeyboardMode.PINYIN_EN) {
                    true
                } else {
                    prefs.keyboardEnglish
                },
            )
            updateImeOptions(currentInputEditorInfo?.imeOptions ?: 0)
        }
        pinyinKeyboard = pinyin

        // 双模式容器：默认语音键盘，键盘模式时切到拼音键盘
        val container = FrameLayout(this)
        keyboardContainer = container
        container.addView(voice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(pinyin, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 键盘视图每次创建都重新套用默认模式（InputMethodService 可能复用实例，
        // 仅靠 onCreate 设置 keyboardMode 在复用场景下不生效）
        keyboardMode = when (prefs.defaultKeyboardMode) {
            DefaultKeyboardMode.PINYIN_CN,
            DefaultKeyboardMode.PINYIN_EN -> KeyboardMode.PINYIN
            else -> KeyboardMode.VOICE
        }
        applyKeyboardMode()
        // 注意：不在这里恢复剪贴板面板（见 onStartInputView 注释——恢复会诱发
        // IME 窗口反复 relayout 循环，反而导致面板抖动/空白）。
        return container
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
     * 进入语音前**先判断识别服务是否在线**，离线就直接拒绝并提示：
     * 否则用户长按空格进了语音面板才发现连不上，白等一次 WebSocket 握手，
     * 还会连带触发录音权限检查、录音器初始化等一整套语音链路负担。
     * 语音是自用功能，宁可在这里提前拦掉，也不让用户进到一个用不了的面板。
     */
    private fun switchToVoiceKeyboard() {
        if (asr.state != LinkState.ONLINE) {
            Diagnostics.i(TAG, "语音服务未连通(${asr.state})，拒绝进入语音功能")
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
        keyboardMode = KeyboardMode.VOICE
        applyKeyboardMode()
    }

    /**
     * 输入会话结束（编辑器失焦/切换到别的输入框）：
     * 停止候选计算、清理拼音缓冲、取消录音与延时任务，降到最低功耗。
     * 不关闭 WebSocket（语音输入需要随时可用，且关闭会丢在途结果）。
     */
    override fun onFinishInput() {
        super.onFinishInput()
        Diagnostics.i(TAG, "onFinishInput: 会话结束 mode=$mode")
        Diagnostics.event("IME", "FinishInput", "mode=$mode")
        // 停掉可能仍在运行的录音/延时任务
        if (mode != Mode.NONE) stopRecording(commit = false)
        ui.removeCallbacks(backspaceRunnable)
        ui.removeCallbacks(autoStopRunnable)
        ui.removeCallbacks(startHoldRunnable)
    }

    /**
     * 系统每次请求显示 IME 窗口的闸门（InputMethodService 内部所有显示路径都经它判定：
     * 编辑框聚焦自动唤起 / [requestShowSelf] / 配置变化后的自动恢复显示）。
     *
     * 「自动唤起键盘」关闭时返回 false，从源头拒绝一切显示请求，窗口永不显示，
     * 系统端 mShowInputRequested 保持 false——不会形成「唤起 → 隐藏 → 系统重新唤起」循环。
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (!prefs.autoShowKeyboard) {
            Diagnostics.i(TAG, "自动唤起键盘：关闭，禁止主动显示 IME")
            return false
        }
        return super.onShowInputRequested(flags, configChange)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
        asr.connect()
        renderLink(asr.state, null)
        micButton?.recording = false
        micButton?.cancelArmed = false
        setHint(getString(R.string.hint_idle))
        pinyinKeyboard?.updateImeOptions(info?.imeOptions ?: 0)
        // 每次输入框聚焦时重新同步输入方案（全拼/双拼、中英文）：
        // 设置页改动后无需重启输入法，下次弹键盘即生效。
        // 英文态取键盘当前状态（保留用户手动切换结果，不强制覆盖）
        pinyinKeyboard?.configure(
            shuangpin = prefs.useShuangpin,
            english = pinyinKeyboard?.isEnglishMode() ?: prefs.keyboardEnglish,
        )
        // 剪贴板页粘贴时连接无效会暂存文本，编辑框重新聚焦时自动提交
        flushPendingPaste()
        // 注意：不再在这里恢复剪贴板面板——恢复逻辑会触发 onPanelShown→refresh
        // （主线程 DB 查询数百毫秒）→ 诱发 IME 窗口反复 relayout（12:20 循环日志实证），
        // 反而让面板抖动/空白。INVISIBLE 方案下键盘视图实例不重建，面板状态天然保留。
    }

    /** 提交暂存的剪贴板粘贴文本（编辑框重新可用时调用；无暂存则空操作） */
    private fun flushPendingPaste() {
        val text = pendingPasteText ?: return
        // 时效保护：暂存文本只在短时间窗口内有效，过期直接丢弃。
        // 否则用户换输入框、或隔很久才回到键盘，旧文本会被粘到完全无关的位置。
        val age = System.currentTimeMillis() - pendingPasteAt
        if (age > PENDING_PASTE_TTL_MS) {
            pendingPasteText = null
            Diagnostics.w(TAG, "flushPendingPaste: 暂存已过期(${age}ms)，丢弃 len=${text.length}")
            return
        }
        pendingPasteText = null
        val connection = currentInputConnection
        if (connection == null) {
            // 仍无有效连接：放回暂存（保留原时刻），等待下次 onStartInputView
            pendingPasteText = text
            Diagnostics.w(TAG, "flushPendingPaste: 连接仍无效，继续暂存 len=${text.length}")
            return
        }
        Diagnostics.i(TAG, "flushPendingPaste: 提交暂存粘贴 len=${text.length}")
        connection.commitText(text, 1)
        // 自身粘贴产生剪贴板变化，标记避免被历史保存
        clipboardController?.onOwnCommit()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Diagnostics.i(TAG, "onFinishInputView: finishingInput=$finishingInput mode=$mode clipboardPanelOpen=$clipboardPanelOpen")
        Diagnostics.event("IME", "FinishInputView", "finish=$finishingInput clipboard=$clipboardPanelOpen")
        // 键盘收起时还在录音，直接丢弃：否则文本可能落到别的输入框里
        if (mode != Mode.NONE) stopRecording(commit = false)
        // 拼音键盘若有未上屏内容，提交首候选
        pinyinKeyboard?.commitComposing()
        ui.removeCallbacks(backspaceRunnable)
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
        Diagnostics.i(TAG, "onDestroy: IME 服务销毁, mode=$mode")
        ui.removeCallbacksAndMessages(null)
        unregisterNetwork()
        runCatching { unregisterReceiver(configReceiver) }
        runCatching { unregisterReceiver(clipboardPasteReceiver) }
        abandonAudioFocus()
        // 采集线程的 stop 需要 join(300ms) + release；放在主线程阻塞会触发 ANR，
        // 抛到后台线程让它自己收尾，asr 的 socket close 也是异步发送 close 帧
        Thread {
            recorder.stop()
            asr.close()
            Diagnostics.i(TAG, "onDestroy: 录音与连接已释放")
        }.start()
        super.onDestroy()
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
        if (mode != Mode.NONE) {
            Diagnostics.w(TAG, "startRecording: 已在录音中 mode=$mode，忽略")
            return
        }

        if (!hasMicPermission()) {
            Log.w(TAG, "startRecording: 缺少 RECORD_AUDIO 权限")
            Diagnostics.w(TAG, "startRecording: 缺少 RECORD_AUDIO 权限")
            setStatusText(getString(R.string.status_no_permission))
            setHint(getString(R.string.hint_grant_permission))
            return
        }

        // 请求音频焦点：被抢占时自动停止录音，避免与其他音频源冲突
        requestAudioFocus()

        if (asr.beginTask() == null) {
            // 未连接：已请求的焦点要释放，避免占用却不录音
            Diagnostics.w(TAG, "startRecording: beginTask 返回 null（未连接）")
            abandonAudioFocus()
            setHint(getString(R.string.hint_not_connected))
            return
        }

        if (!recorder.start()) {
            Diagnostics.e(TAG, "startRecording: recorder.start 失败，取消任务")
            asr.cancelTask()
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

        recorder.stop()
        abandonAudioFocus()
        micButton?.recording = false
        micButton?.cancelArmed = false

        if (commit) {
            asr.endTask()
            Diagnostics.i(TAG, "stopRecording: 收尾发送（识别中）")
            setHint(getString(R.string.hint_recognizing))
        } else {
            asr.cancelTask()
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
                ui.post { if (asr.state == LinkState.OFFLINE) asr.connect() }
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

        Diagnostics.i(TAG, "handleResult: 最终结果 \"${text.take(60)}\" (共${message.text.length}字)")
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

    // ── 编辑键 ────────────────────────────────────────────────

    private fun commit(text: String) {
        // 诊断：记录上屏内容（键盘/语音两种来源都走这里），方便核对输入链路
        Diagnostics.v(TAG, "commit: \"${text.take(40)}\" (键盘模式=$keyboardMode)")
        // 注意：打字/语音上屏走 commitText，不写系统剪贴板，不会触发剪贴板监听。
        // 这里【不能】调用 onOwnCommit()——否则标记会残留到下一次真实复制，
        // 导致用户复制的内容被误判为「自身操作」而跳过保存。
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * 删除键三击：清空输入框全部文本。
     * 通过 Ctrl+A 全选 + 删除实现，兼容大多数输入框。
     */
    private fun deleteAllText() {
        Diagnostics.i(TAG, "deleteAllText: 三击删除键，清空全部文本")
        val connection = currentInputConnection ?: return
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        // Ctrl+A 全选（用 sendDownUpKeyEvents 的变体 + meta 不可行，改走 sendKeyEvent 手工构造）
        connection.sendKeyEvent(makeCtrlKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, meta))
        connection.sendKeyEvent(makeCtrlKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, meta))
        // 删除选中内容（deleteSurroundingText 在 API 34 是 Int 签名）
        connection.deleteSurroundingText(Int.MAX_VALUE, 0)
        Diagnostics.i(TAG, "deleteAllText: 已发送全选删除")
    }

    /**
     * 全选：选中输入框全部文本（Ctrl+A）。
     * 与 [deleteAllText] 不同，本方法只选中不删除，配合「复制」使用。
     */
    private fun selectAllText() {
        Diagnostics.i(TAG, "selectAllText: 全选")
        val connection = currentInputConnection ?: return
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        connection.sendKeyEvent(makeCtrlKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, meta))
        connection.sendKeyEvent(makeCtrlKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, meta))
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
        // 此时回车 = 把**实际按下的键**原样上屏（全拼 but→but；双拼 budv→budv，
        // 不做双拼→全拼转换），而不是选中候选、也不是换行。
        val rawComposing = pinyinKeyboard?.takeRawComposing().orEmpty()
        if (rawComposing.isNotEmpty()) {
            connection.commitText(rawComposing, 1)
            return
        }

        val editorInfo = currentInputEditorInfo
        val imeOptions = editorInfo?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val actionDisabled = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        val hasAction = !actionDisabled &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED

        if (hasAction) {
            connection.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
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
                }
            }
        }
        statusDot?.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
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

        /** 设置页「保存配置」广播 action：收到后立即刷新连接与键盘配置 */
        const val ACTION_CONFIG_UPDATED = "com.jinn.inputmethod.action.CONFIG_UPDATED"

        /**
         * 暂存粘贴文本的有效期：剪贴板页点击粘贴但 IME 无连接时暂存，
         * 编辑框重新聚焦（onStartInputView）时提交。超过该时长视为过期丢弃，
         * 防止旧内容被粘到用户当前无关的其它输入框。
         */
        const val PENDING_PASTE_TTL_MS = 10_000L

        /** 剪贴板页面「点击记录粘贴」广播 action + extra */
        const val ACTION_CLIPBOARD_PASTE = "com.jinn.inputmethod.action.CLIPBOARD_PASTE"
        const val EXTRA_CLIPBOARD_PASTE_TEXT = "clipboard_paste_text"
        const val EXTRA_CLIPBOARD_PASTE_ITEM_ID = "clipboard_paste_item_id"

        /** IME 向剪贴板页回传粘贴结果：成功才允许关闭页面 */
        const val ACTION_CLIPBOARD_PASTE_RESULT = "com.jinn.inputmethod.action.CLIPBOARD_PASTE_RESULT"
        const val EXTRA_CLIPBOARD_PASTE_SUCCESS = "clipboard_paste_success"

        /** 超过这个时长判定为"按住说话" */
        const val HOLD_THRESHOLD_MS = 260L

        /** 按住时上滑超过这个距离即进入取消状态 */
        const val CANCEL_SLIDE_DP = 64f

        /** 连续录音的最长时长，3 分钟 */
        const val MAX_TOGGLE_MS = 180_000L

        const val BACKSPACE_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_MS = 55L

        /** 对齐 config_client.py 的 trash_punc */
        val TRAILING_PUNC = charArrayOf('，', '。', ',', '.')
    }
}
