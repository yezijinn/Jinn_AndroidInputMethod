package com.jinn.voiceinput

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 拼音键盘视图：候选栏 + 26 键 + 底部功能行。
 *
 * 布局（对齐主流输入法）：
 *  - 26 键：第一行 10 字母、第二行 9 字母、第三行「切换大写 + 7 字母 + 删除键」
 *  - 第四行：符号、数字、逗号、空格（长按语音）、句号、中英切换、回车确定
 *
 * 职责：
 *  - 收集字母输入（全拼或自然码双拼），实时查询 [PinyinEngine] 显示候选
 *  - 候选点选 / 空格取首候选 / 退格回删拼音
 *  - 中英文切换（大写状态影响英文字母大小写）
 *  - 符号层 / 数字层
 *  - 空格长按请求切回语音模式（通过 [OnVoiceRequested] 回调给 IME）
 *
 * 输入法层调用 [commitComposing] 主动结束当前拼音串。
 */
class PinyinKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** 回调接口，全部在主线程调用 */
    interface Listener {
        /** 上屏文本（中文字符或英文字母） */
        fun onCommitText(text: String)
        /** 上屏空格 */
        fun onCommitSpace()
        /** 执行回车（按输入框声明的动作） */
        fun onEnter()
        /** 退格（无拼音串时删除已上屏文本） */
        fun onBackspace()
        /** 删除键三击：清空已上屏的全部文本 */
        fun onDeleteAll()
        /** 请求切回语音模式 */
        fun onVoiceRequested()
        /** 功能面板：打开剪贴板历史页 */
        fun onOpenClipboard()
        /** 剪贴板面板：点击记录请求粘贴（IME 用当前 InputConnection commitText）。返回是否成功。 */
        fun onPasteText(text: String): Boolean
        /** 剪贴板面板状态变化（开/关，IME 侧同步拖选等状态） */
        fun onClipboardStateChanged(active: Boolean)
        /** 方向面板：执行方向控制动作（光标移动/拖选/复制/粘贴） */
        fun onDirectionAction(action: DirectionAction)
        /** 方向面板：拖选模式状态变化通知（IME 侧切换后同步 UI） */
        fun onSelectionModeChanged(active: Boolean)
        /** 功能面板：粘贴剪贴板最新内容（剪贴板为空则无反应） */
        fun onPasteClipboard()
        /** 功能面板：收起键盘（隐藏面板，非停止服务） */
        fun onHideKeyboard()
    }

    /**
     * 方向控制动作（对齐 IME 侧执行逻辑）。
     * 底部栏已有的空格/回车不在此列（不重复），由 IME 内部处理拖选状态。
     */
    enum class DirectionAction { UP, DOWN, LEFT, RIGHT, LINE_START, LINE_END, TOGGLE_SELECTION, COPY, PASTE }

    var listener: Listener? = null

    /** 当前输入框的 IME 动作（用于回车键行为） */
    var imeOptions: Int = 0

    /** 当前拼音串（全拼或双拼原文） */
    private var composing = StringBuilder()

    /** 是否处于英文模式（字母直接上屏，不查候选） */
    private var englishMode = false

    /** 是否大写锁定（影响英文模式字母大小写与键面显示） */
    private var capsMode = false

    /** 符号层 / 数字层 / 字母层 */
    private var layer = LAYER_LETTER

    /** 全拼还是双拼（来自设置） */
    private var shuangpinMode = false

    private lateinit var viewCandidatePinyin: TextView
    private lateinit var viewCandidateList: LinearLayout
    private lateinit var viewLetters: LinearLayout
    private lateinit var contentArea: FrameLayout

    /** 剪贴板面板（与字母区互斥显示，见 init 挂载） */
    private lateinit var clipboardPanel: ClipboardPanelView

    /** 剪贴板面板是否激活 */
    private var clipboardActive = false
    private lateinit var btnSymbol: TextView
    private lateinit var btnDigit: TextView
    private lateinit var btnLang: TextView
    private lateinit var btnSpace: View
    private lateinit var btnBackspace: View
    private lateinit var btnEnter: View
    private lateinit var btnShift: ImageButton
    private lateinit var btnComma: View
    private lateinit var btnPeriod: View

    private val keyViews = HashMap<Char, PinyinKey>()

    /** 候选缓存：空格取第一个 */
    private var lastCandidates: List<String> = emptyList()

    /** 智能预测缓存：选中词后 PinyinEngine.predict 的结果 */
    private var lastPredictions: List<String> = emptyList()

    /** 上次上屏的词：退格回到预测态时用它重新查询预测 */
    private var lastCommittedWord: String = ""

    // ── 删除键三态：单击删一个 / 按住连续删 / 快速三击全删 ──
    private val backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceHeld = false
    private var backspacePressStart = 0L
    /** 双击计数（双击后长按触发清空） */
    private var backspaceTapCount = 0
    private var backspaceLastTapAt = 0L

    /** 按住退格超过该时长进入连续删除 */
    private val backspaceRepeatDelayMs = 380L

    /** 连续删除的间隔 */
    private val backspaceRepeatIntervalMs = 55L

    /** 双击窗口：两次短按间隔小于该值视为双击（双击后长按触发清空） */
    private val doubleTapWindowMs = 280L

    /** 双击后第三次按住超过该时长触发全部清空（长按确认，杜绝误触） */
    private val longPressClearMs = 800L

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (!backspaceHeld) return
            deleteOne()
            backspaceHandler.postDelayed(this, backspaceRepeatIntervalMs)
        }
    }

    // ── 字母行定义（标准 QWERTY） ──────────────────────────
    private val rows = arrayOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
    )

    /** 符号层定义：键位 → 符号 */
    private val symbolMap = mapOf(
        'q' to "！", 'w' to "？", 'e' to "、", 'r' to "。", 't' to "；",
        'y' to "：", 'u' to "“", 'i' to "”", 'o' to "…", 'p' to "（",
        'a' to "）", 's' to "【", 'd' to "】", 'f' to "《", 'g' to "》",
        'h' to "·", 'j' to "—", 'k' to "~", 'l' to "`",
        'z' to "@", 'x' to "#", 'c' to "$", 'v' to "%", 'b' to "^",
        'n' to "&", 'm' to "*",
    )

    /** 数字层定义：键位 → 数字/符号 */
    private val digitMap = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "-", 's' to "/", 'd' to ":", 'f' to ";", 'g' to "(",
        'h' to ")", 'j' to "¥", 'k' to "@", 'l' to "&",
        'z' to ".", 'x' to ",", 'c' to "?", 'v' to "!", 'b' to "'",
        'n' to "\"", 'm' to "%",
    )

    init {
        orientation = VERTICAL
        val root = LayoutInflater.from(context).inflate(R.layout.keyboard_pinyin, this, true)

        viewCandidatePinyin = root.findViewById(R.id.candidate_pinyin)
        viewCandidateList = root.findViewById(R.id.candidate_list)
        viewLetters = root.findViewById(R.id.keyboard_letters)
        contentArea = root.findViewById(R.id.keyboard_content_area)

        btnSymbol = root.findViewById(R.id.key_symbol)
        btnDigit = root.findViewById(R.id.key_digit)
        btnLang = root.findViewById(R.id.key_lang)
        btnSpace = root.findViewById(R.id.key_space)
        btnBackspace = root.findViewById(R.id.key_backspace)
        btnEnter = root.findViewById(R.id.key_enter)
        btnShift = root.findViewById(R.id.key_shift)
        btnComma = root.findViewById(R.id.key_comma)
        btnPeriod = root.findViewById(R.id.key_period)

        // 剪贴板面板：预挂载到 contentArea（GONE），打开/关闭仅切 visibility。
        // 高度 = MATCH_PARENT。关键：contentArea 是 FrameLayout，viewLetters 用
        // INVISIBLE（保留布局空间）而非 GONE，因此 contentArea 高度始终由
        // viewLetters 撑起、恒定不变 → 面板 MATCH_PARENT = contentArea 高度
        // = 「候选栏与底部栏之间全部现有空间」，且全程零 layoutParams 修改，
        // 绝不触发 MIUI IME relayout。
        clipboardPanel = ClipboardPanelView(context).apply {
            listener = object : ClipboardPanelView.Listener {
                override fun onPaste(text: String): Boolean =
                    this@PinyinKeyboardView.listener?.onPasteText(text) ?: false
                override fun onClose() {
                    hideClipboardPanel()
                }
            }
            visibility = View.GONE
        }
        contentArea.addView(
            clipboardPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        bindLetterKeys(root)
        bindFunctionKeys()
        refreshKeyLabels()
        refreshCandidateBar()
        Log.i(TAG, "PinyinKeyboardView 初始化完成")
    }

    // ── 初始化绑定 ─────────────────────────────────────────

    private fun bindLetterKeys(root: View) {
        for (row in rows) {
            for (c in row) {
                val key = root.findViewById<PinyinKey>(letterKeyId(c)) ?: continue
                key.label = c.toString()
                keyViews[c] = key
                key.setOnClickListener { onLetterPressed(c) }
            }
        }
    }

    private fun letterKeyId(c: Char): Int = when (c) {
        'q' -> R.id.key_q
        'w' -> R.id.key_w
        'e' -> R.id.key_e
        'r' -> R.id.key_r
        't' -> R.id.key_t
        'y' -> R.id.key_y
        'u' -> R.id.key_u
        'i' -> R.id.key_i
        'o' -> R.id.key_o
        'p' -> R.id.key_p
        'a' -> R.id.key_a
        's' -> R.id.key_s
        'd' -> R.id.key_d
        'f' -> R.id.key_f
        'g' -> R.id.key_g
        'h' -> R.id.key_h
        'j' -> R.id.key_j
        'k' -> R.id.key_k
        'l' -> R.id.key_l
        'z' -> R.id.key_z
        'x' -> R.id.key_x
        'c' -> R.id.key_c
        'v' -> R.id.key_v
        'b' -> R.id.key_b
        'n' -> R.id.key_n
        'm' -> R.id.key_m
        else -> 0
    }

    private fun bindFunctionKeys() {
        btnSymbol.setOnClickListener {
            layer = if (layer == LAYER_SYMBOL) LAYER_LETTER else LAYER_SYMBOL
            refreshKeyLabels()
            Diagnostics.i(TAG, "符号层: ${layer == LAYER_SYMBOL}")
        }
        btnDigit.setOnClickListener {
            layer = if (layer == LAYER_DIGIT) LAYER_LETTER else LAYER_DIGIT
            refreshKeyLabels()
            Diagnostics.i(TAG, "数字层: ${layer == LAYER_DIGIT}")
        }
        btnLang.setOnClickListener {
            englishMode = !englishMode
            if (englishMode) {
                // 切英文时清掉未上屏的拼音
                commitComposing()
            } else {
                // 切回中文时清掉英文态残留的预测
                lastPredictions = emptyList()
            }
            refreshKeyLabels()
            Diagnostics.i(TAG, "中英切换: ${if (englishMode) "英文" else "中文"}")
        }
        btnShift.setOnClickListener {
            capsMode = !capsMode
            refreshKeyLabels()
            Diagnostics.i(TAG, "大写锁定: $capsMode")
        }
        btnSpace.setOnClickListener { onSpacePressed() }
        btnSpace.setOnLongClickListener {
            // 空格长按：切回语音模式（不触发短按）
            listener?.onVoiceRequested()
            true
        }
        btnBackspace.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }
        btnEnter.setOnClickListener { listener?.onEnter() }
        // 逗号/句号：英文模式上 ASCII，中文模式上全角
        btnComma.setOnClickListener {
            listener?.onCommitText(if (englishMode) "," else "，")
        }
        btnPeriod.setOnClickListener {
            listener?.onCommitText(if (englishMode) "." else "。")
        }
    }

    // ── 对外控制 ───────────────────────────────────────────

    /** 当前是否英文模式（供 IME 每次聚焦时同步，避免覆盖用户手动切换） */
    fun isEnglishMode(): Boolean = englishMode

    /** 设置输入方案（全拼/双拼）与初始中英文状态 */
    fun configure(shuangpin: Boolean, english: Boolean) {
        shuangpinMode = shuangpin
        englishMode = english
        // 方案切换时清掉残留的拼音与预测
        composing.clear()
        lastPredictions = emptyList()
        refreshKeyLabels()
        refreshCandidateBar()
    }

    fun updateImeOptions(options: Int) {
        imeOptions = options
    }

    /** 中文输入时是否还有未上屏内容 */
    fun hasComposing(): Boolean = composing.isNotEmpty()

    /** 供 IME 调试/测试：获取某字母键的视图 */
    fun keyView(c: Char): PinyinKey? = keyViews[c]

    /** 供 IME 调试：返回功能键屏幕坐标 */
    fun getFunctionKeyPositions(): String {
        val sb = StringBuilder()
        for ((name, v) in listOf(
            "symbol" to btnSymbol, "digit" to btnDigit, "lang" to btnLang,
            "space" to btnSpace, "backspace" to btnBackspace, "enter" to btnEnter,
            "shift" to btnShift, "comma" to btnComma, "period" to btnPeriod,
        )) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            sb.append("$name(${loc[0]},${loc[1]} ${v.width}x${v.height}) ")
        }
        return sb.toString()
    }

    /** 供 IME 调试：候选栏位置与候选数量 */
    fun getCandidateBarPosition(): String {
        val loc = IntArray(2)
        viewCandidateList.getLocationOnScreen(loc)
        return "候选栏(${loc[0]},${loc[1]} ${viewCandidateList.width}x${viewCandidateList.height}) 候选数=${viewCandidateList.childCount}"
    }

    /** 提交当前拼音串的首候选（IME 收起键盘等场景调用） */
    fun commitComposing() {
        if (composing.isNotEmpty()) {
            val candidates = lastCandidates
            if (candidates.isNotEmpty()) {
                listener?.onCommitText(candidates[0])
            } else if (!englishMode) {
                // 无候选（如未加载词库），直接丢拼音串
                Diagnostics.w(TAG, "commitComposing: 无候选，丢弃拼音 ${composing}")
            }
            composing.clear()
        }
        // 收起键盘时预测态不自动上屏，仅清空回到拼音态
        lastPredictions = emptyList()
        refreshCandidateBar()
    }

    // ── 按键处理 ───────────────────────────────────────────

    private fun onLetterPressed(c: Char) {
        // 符号层 / 数字层：直接上屏对应字符
        when (layer) {
            LAYER_SYMBOL -> {
                val symbol = symbolMap[c] ?: return
                listener?.onCommitText(symbol)
                return
            }

            LAYER_DIGIT -> {
                val digit = digitMap[c] ?: return
                listener?.onCommitText(digit)
                return
            }

            else -> Unit
        }
        if (englishMode) {
            // 英文模式：按大写状态上屏
            listener?.onCommitText(if (capsMode) c.uppercaseChar().toString() else c.toString())
            return
        }
        // 中文模式 + 大写锁定：直接上屏大写字母（等价于临时英文）
        if (capsMode) {
            listener?.onCommitText(c.uppercaseChar().toString())
            return
        }
        // 中文模式：追加到拼音串并查候选
        composing.append(c)
        refreshCandidateBar()
        Diagnostics.v(TAG, "拼音输入: ${composing}")
    }

    private fun onSpacePressed() {
        if (layer != LAYER_LETTER) {
            listener?.onCommitSpace()
            return
        }
        if (englishMode) {
            listener?.onCommitSpace()
            return
        }
        if (composing.isNotEmpty()) {
            // 有候选取第一个，否则直接丢拼音上空格
            if (lastCandidates.isNotEmpty()) {
                listener?.onCommitText(lastCandidates[0])
            } else {
                listener?.onCommitSpace()
            }
            composing.clear()
            // 空格上屏同样触发智能预测（与点选候选一致）
            lastCommittedWord = lastCandidates.firstOrNull().orEmpty()
            lastPredictions = PinyinEngine.predict(lastCommittedWord)
            refreshCandidateBar()
        } else if (lastPredictions.isNotEmpty()) {
            // 预测态：空格取第一个预测词
            onPredictionSelected(lastPredictions[0])
        } else {
            listener?.onCommitSpace()
        }
    }

    private fun onBackspacePressed() {
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            refreshCandidateBar()
        } else {
            listener?.onBackspace()
        }
    }

    /**
     * 删除键触摸：
     *  - 按下即删一个字符；按住超过 [backspaceRepeatDelayMs] 进入连续删除，松开停止
     *  - 「双击 + 长按」触发 [onTripleBackspace] 全部清空：
     *    连续两次短按（间隔 < [doubleTapWindowMs]）后，第三次按住超过 [longPressClearMs]。
     *    正常快速删除（点 3 下）第三次是短按即松，不会误触。
     */
    private fun handleBackspaceTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                backspaceHeld = true
                backspacePressStart = System.currentTimeMillis()
                deleteOne()
                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                backspaceHandler.postDelayed(backspaceRepeatRunnable, backspaceRepeatDelayMs)
                // 已处于「双击后」状态：本次长按到阈值触发清空
                if (backspaceTapCount == 2) {
                    backspaceHandler.removeCallbacks(clearOnLongPressRunnable)
                    backspaceHandler.postDelayed(clearOnLongPressRunnable, longPressClearMs)
                }
                btnBackspace.isPressed = true
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                btnBackspace.isPressed = false
                if (!backspaceHeld) return true
                backspaceHeld = false
                val held = System.currentTimeMillis() - backspacePressStart
                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                // 取消未触发的长按清空检测（第三次短按即松 → 不触发）
                backspaceHandler.removeCallbacks(clearOnLongPressRunnable)
                // 短按（未进入连续删除）才算一次点击，用于双击判定
                if (held < backspaceRepeatDelayMs) {
                    recordBackspaceTap()
                } else {
                    // 长按（进入连删或触发清空）后重置双击计数
                    backspaceTapCount = 0
                }
                return true
            }
        }
        return false
    }

    /**
     * 记录一次退格点击，维护双击状态：
     *  - 连续两次短按间隔 < [doubleTapWindowMs] → 进入「双击后」状态（count=2）
     *  - 双击后下一次长按 → 触发全部清空
     *  - 双击间隔超时或第三击为短按 → 计数重置，永不误触
     */
    private fun recordBackspaceTap() {
        val now = System.currentTimeMillis()
        if (backspaceTapCount == 0) {
            // 第一击
            backspaceTapCount = 1
            backspaceLastTapAt = now
        } else if (backspaceTapCount == 1) {
            // 第二击：与第一击间隔在窗口内 → 进入双击状态
            if (now - backspaceLastTapAt < doubleTapWindowMs) {
                backspaceTapCount = 2
                Diagnostics.v(TAG, "退格双击已就绪，长按触发清空")
            } else {
                backspaceTapCount = 1
                backspaceLastTapAt = now
            }
        } else {
            // 双击后第三击为短按（快速点 3 下）→ 不是长按，重置
            backspaceTapCount = 0
            backspaceLastTapAt = now
        }
    }

    /** 双击后长按达到阈值：全部清空 */
    private val clearOnLongPressRunnable = Runnable {
        if (!backspaceHeld) return@Runnable
        backspaceTapCount = 0
        Diagnostics.i(TAG, "退格双击+长按：清空全部")
        onTripleBackspace()
    }

    /** 双击+长按清空：先清拼音串与预测，再通知 IME 删除已上屏文本 */
    private fun onTripleBackspace() {
        if (composing.isNotEmpty()) {
            composing.clear()
        }
        lastPredictions = emptyList()
        refreshCandidateBar()
        listener?.onDeleteAll()
    }

    /** 删除一个字符（有拼音串删拼音，否则删已上屏） */
    private fun deleteOne() {
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            refreshCandidateBar()
            Diagnostics.v(TAG, "退格删拼音: ${composing}")
        } else if (lastPredictions.isNotEmpty()) {
            // 预测态退格：清除预测，回到拼音态
            lastPredictions = emptyList()
            refreshCandidateBar()
        } else {
            listener?.onBackspace()
        }
    }

    // ── 候选渲染 ───────────────────────────────────────────

    private fun refreshCandidateBar() {
        val input = composing.toString()
        if (input.isEmpty() && lastPredictions.isEmpty()) {
            lastCandidates = emptyList()
            viewCandidatePinyin.text = ""
            viewCandidateList.removeAllViews()
            // 无候选、无拼音串、无预测：候选栏展示功能面板按钮
            renderFunctionPanel()
            return
        }

        if (input.isEmpty()) {
            // 智能预测模式：候选栏显示预测词（如选「你好」后显示 吗/像/不好…）
            viewCandidatePinyin.text = ""
            viewCandidateList.removeAllViews()
            for (pred in lastPredictions) {
                val item = TextView(context).apply {
                    text = pred
                    textSize = 21f
                    setTextColor(resources.getColor(R.color.kb_candidate_sel_text, context.theme))
                    setPadding(dp(2), 0, dp(2), 0)
                    isClickable = true
                    setOnClickListener { onPredictionSelected(pred) }
                }
                viewCandidateList.addView(item)
            }
            Diagnostics.v(TAG, "智能预测: ${lastPredictions.take(4)}")
            return
        }

        // 双拼：先转全拼再查询；显示仍保留双拼原文
        val queryInput = if (shuangpinMode) Shuangpin.toQuanpin(input) else input
        val result = PinyinEngine.query(queryInput)
        // 补全诊断：全拼且末尾不完整时，输出补全召回与直接查询对比
        if (!shuangpinMode) {
            val completion = PinyinEngine.queryWithCompletion(queryInput)
            if (completion.isNotEmpty() || result.partialSyllable.isNotEmpty()) {
                Diagnostics.i(
                    TAG,
                    "补全诊断: input=$queryInput syllables=${result.syllables} " +
                        "partial=${result.partialSyllable} completion=$completion",
                )
            }
        }
        lastCandidates = result.candidates
        viewCandidatePinyin.text = queryInput
        Diagnostics.v(TAG, "候选: ${if (shuangpinMode) "双拼[$input]→" else ""}$queryInput → ${result.candidates.take(3)}")

        viewCandidateList.removeAllViews()
        for ((index, candidate) in result.candidates.withIndex()) {
            val item = TextView(context).apply {
                text = candidate
                textSize = 21f
                setTextColor(resources.getColor(R.color.text_primary, context.theme))
                setPadding(dp(2), 0, dp(2), 0)
                isClickable = true
                setOnClickListener { onCandidateSelected(candidate) }
            }
            viewCandidateList.addView(item)
        }
    }

    private fun onCandidateSelected(candidate: String) {
        Diagnostics.i(TAG, "候选上屏: \"$candidate\" (拼音=${composing})")
        listener?.onCommitText(candidate)
        composing.clear()
        // 智能预测：基于已上屏词预测下一个词（libime matchWordsPrefix 思路）
        lastCommittedWord = candidate
        lastPredictions = PinyinEngine.predict(candidate)
        refreshCandidateBar()
    }
    private fun onPredictionSelected(pred: String) {
        Diagnostics.i(TAG, "预测上屏: \"$pred\" (基于 ${lastCommittedWord})")
        listener?.onCommitText(pred)
        lastPredictions = emptyList()
        refreshCandidateBar()
    }

    // ── 键面显示 ───────────────────────────────────────────

    private fun refreshKeyLabels() {
        val showUpper = capsMode && !englishMode && layer == LAYER_LETTER
        // 全拼模式：字母大字铺满；双拼模式：小字顶置 + 韵母提示
        val fullPinyin = !shuangpinMode
        for (c in 'a'..'z') {
            val key = keyViews[c] ?: continue
            key.fullPinyinStyle = fullPinyin && layer == LAYER_LETTER
            key.label = when (layer) {
                LAYER_SYMBOL -> symbolMap[c] ?: c.toString()
                LAYER_DIGIT -> digitMap[c] ?: c.toString()
                else ->
                    if (showUpper) c.uppercaseChar().toString() else c.toString()
            }
            // 双拼模式下显示自然码键位提示（字母层）
            val showHint = layer == LAYER_LETTER && !englishMode && shuangpinMode
            key.subLabel = if (showHint) shuangpinHint(c) else ""
            // u/i/v 键的 sh/ch/zh 用红色显示在下方（与韵母同区域，追加在后）
            key.subLabelRed = if (showHint) shuangpinRedHint(c) else ""
        }
        btnLang.text = context.getString(if (englishMode) R.string.key_en else R.string.key_cn)
        btnSymbol.text = if (layer == LAYER_SYMBOL) {
            context.getString(R.string.key_abc)
        } else {
            context.getString(R.string.key_symbol)
        }
        btnDigit.text = if (layer == LAYER_DIGIT) {
            context.getString(R.string.key_abc)
        } else {
            context.getString(R.string.key_digit)
        }
        // 大写锁定：shift 键高亮
        btnShift.setBackgroundResource(if (capsMode) R.drawable.key_bg_active else R.drawable.key_bg)
        btnShift.alpha = 1f
    }

    /**
     * 自然码双拼键位提示：键面下方显示该键对应的韵母。
     *  - Y/S/D 双韵母分行（`\n` 分隔，一行一个）
     *  - u/i/v 键下方韵母（v 键的 ui 在这里，zh 走红色 [shuangpinRedHint]）
     *  - e/a/u/i 键不显示韵母提示
     */
    private fun shuangpinHint(c: Char): String = when (c) {
        'q' -> "iu"
        'w' -> "ia ua"
        'r' -> "uan"
        't' -> "ue"
        'y' -> "uai\ning"
        'o' -> "ou"
        'p' -> "un"
        's' -> "ong\niong"
        'd' -> "iang\nuang"
        'f' -> "en"
        'g' -> "eng"
        'h' -> "ang"
        'j' -> "an"
        'k' -> "ao"
        'l' -> "ai"
        'z' -> "ei"
        'x' -> "ie"
        'c' -> "iao"
        'v' -> "ui"
        'b' -> "ou"
        'n' -> "in"
        'm' -> "ian"
        // e/a/u/i 不显示韵母提示
        else -> ""
    }

    /** 红色提示（键下方，追加在韵母之后）：u/i/v 键的 sh/ch/zh */
    private fun shuangpinRedHint(c: Char): String = when (c) {
        'u' -> "sh"
        'i' -> "ch"
        'v' -> "zh"
        else -> ""
    }

    // ── 功能面板（无候选时展示）──────────────────────────────

    /**
     * 无候选 / 无拼音串 / 无预测时，候选栏切换为功能面板：
     *  - 剪贴板：打开安全剪贴板历史页
     *  - 方向：打开方向控制面板（上下左右/空格/回车/行首/行末）
     *  - 双拼/全拼：切换输入方案（键盘内部状态翻转）
     *  - 收起键盘：隐藏输入法面板（重新点击输入框再唤醒）
     *
     * 复用候选栏的 [candidate_list] 区域，高度与候选栏一致（48dp），
     * 不改变键盘整体高度；按钮横向排列，小屏自动可横向滚动。
     */
    private fun renderFunctionPanel() {
        viewCandidateList.removeAllViews()
        viewCandidateList.addView(buildFunctionButton(
            label = if (shuangpinMode) "双拼" else "全拼",
            hint = "输入方案",
            onClick = { togglePinyinScheme() },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "剪贴板",
            hint = "历史",
            onClick = {
                Diagnostics.i(TAG, "功能面板: 点击剪贴板按钮")
                listener?.onOpenClipboard()
            },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "方向",
            hint = "控制",
            onClick = {
                if (directionPanelVisible) hideDirectionPanel() else showDirectionPanel()
            },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "粘贴",
            hint = "剪贴板",
            onClick = { listener?.onPasteClipboard() },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "收起",
            hint = "键盘",
            onClick = { listener?.onHideKeyboard() },
        ))
        Diagnostics.v(TAG, "功能面板: ${if (shuangpinMode) "双拼" else "全拼"}/剪贴板/方向/粘贴/收起")
    }

    /** 构建单个功能按钮：候选栏同高，现有键盘风格（深色圆角 + 主文字） */
    private fun buildFunctionButton(label: String, hint: String, onClick: () -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundResource(R.drawable.key_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        // 五等分：每个按钮 weight=1，均分候选栏宽度（5 个按钮各占 1/5）
        val lp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        box.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(resources.getColor(R.color.text_primary, context.theme))
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(TextView(context).apply {
            text = hint
            textSize = 9f
            setTextColor(resources.getColor(R.color.text_secondary, context.theme))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.layoutParams = lp
        return box
    }

    /** 双拼/全拼切换：翻转方案 + 刷新键面提示 + 更新偏好（下次唤起保持） */
    private fun togglePinyinScheme() {
        shuangpinMode = !shuangpinMode
        // 持久化：切换结果写入设置，输入法重建后保持本次选择
        runCatching { Prefs(context).useShuangpin = shuangpinMode }
            .onFailure { Diagnostics.w(TAG, "切换方案时保存偏好失败: ${it.message}") }
        refreshKeyLabels()
        refreshCandidateBar()
        Diagnostics.i(TAG, "输入方案切换: ${if (shuangpinMode) "自然码双拼" else "全拼"}")
    }

    // ── 键盘内方向面板（占 26 键的字母区域）────────────────

    /** 方向面板根视图（懒加载）；null 表示未构建 */
    private var directionPanel: LinearLayout? = null

    /** 是否处于方向面板模式（字母区被替换） */
    private var directionPanelVisible = false

    /** 中心拖选开关按钮（●/◉）与当前状态 */
    private var centerSelectionKey: TextView? = null
    private var selectionActive = false

    /** 拖选模式是否激活（由 IME 侧状态机驱动） */
    fun isSelectionActive(): Boolean = selectionActive

    /**
     * IME 侧拖选状态变化时同步中心按钮视觉：
     * ●（普通）→ ◉（激活，加边框高亮），不依赖颜色作为唯一标识。
     */
    fun setSelectionActive(active: Boolean) {
        selectionActive = active
        val key = centerSelectionKey
        key?.text = if (active) "◉" else "●"
        key?.setBackgroundResource(if (active) R.drawable.key_bg_active else R.drawable.key_bg)
        // 激活态下副文字提示（放在按钮文字下方）
        key?.contentDescription = if (active) "文字拖选模式开启" else "文字拖选模式关闭"
    }

    /**
     * 显示方向控制面板：复用 26 键字母区域，候选栏与底部栏保持不变。
     * 面板含：行首/上/行末、左/●拖选开关/右、下、复制/粘贴。
     */
    private fun showDirectionPanel() {
        if (directionPanelVisible) {
            Diagnostics.v(TAG, "showDirectionPanel: 已显示，跳过")
            return
        }
        ensureDirectionPanel()
        val panel = directionPanel ?: run {
            Diagnostics.e(TAG, "showDirectionPanel: panel 构建失败")
            return
        }
        // 字母区隐藏，方向面板显示
        for (i in 0 until viewLetters.childCount) {
            viewLetters.getChildAt(i).visibility = View.GONE
        }
        viewLetters.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        panel.visibility = View.VISIBLE
        directionPanelVisible = true
        // 进入方向面板时重置拖选状态
        selectionActive = false
        Diagnostics.i(TAG, "方向面板: 显示（候选栏/底部栏保持）")
    }

    /** 恢复 26 键字母布局 */
    fun hideDirectionPanel() {
        if (!directionPanelVisible) return
        directionPanel?.let { viewLetters.removeView(it) }
        directionPanel = null
        centerSelectionKey = null
        // 明确恢复全部子 view（前 3 个是字母行）
        for (i in 0 until viewLetters.childCount) {
            viewLetters.getChildAt(i).visibility = View.VISIBLE
        }
        directionPanelVisible = false
        selectionActive = false
        // 通知 IME 清除拖选状态（JinnIme 的 anchor/focus 同步重置）
        listener?.onSelectionModeChanged(false)
        Diagnostics.i(TAG, "方向面板: 隐藏，恢复字母键盘")
    }

    // ── 键盘内剪贴板面板（占内容区，候选栏/底部栏保持）────────────

    /** 当前是否处于剪贴板面板模式（字母区被替换） */
    fun isClipboardActive(): Boolean = clipboardActive

    /**
     * 显示剪贴板面板：候选栏与底部功能行固定不动，面板占用二者之间全部空间。
     *
     * 布局机制（关键，多轮真机验证结论）：
     * - **IME 总高度恒定，零 layoutParams 修改，绝不 relayout**。
     * - viewLetters 用 **INVISIBLE** 而非 GONE：INVISIBLE 保留布局空间，
     *   contentArea（wrap_content FrameLayout）高度始终由 viewLetters 撑起、
     *   恒定不变；若用 GONE 会塌缩 contentArea → 面板高度为 0。
     * - 面板 MATCH_PARENT 预挂在 contentArea 内（覆盖在 INVISIBLE 的 viewLetters
     *   之上）→ 高度 = contentArea 高度 = 「候选栏与底部栏之间全部现有空间」。
     * - 打开/关闭只切 visibility，是纯 View 切换，不触发 MIUI IME relayout。
     */
    fun showClipboardPanel() {
        Diagnostics.i(TAG, "showClipboardPanel called, clipboardActive=$clipboardActive")
        if (clipboardActive) {
            Diagnostics.v(TAG, "showClipboardPanel: 已显示，跳过")
            return
        }
        try {
            // 与方向面板互斥
            if (directionPanelVisible) hideDirectionPanel()

            // contentArea 高度 = 字母区 2 倍（用户验证过的 850px 方案）。
            // 关键：contentArea 的父是 LinearLayout（PinyinKeyboardView 根），
            // 必须用 LinearLayout.LayoutParams——早期用 FrameLayout.LayoutParams
            // 导致 ClassCastException（键盘收起循环），本次是正确类型。
            // 固定确定高度：面板不受 IME 窗口初始测量影响（冷启动稳定）。
            val density = resources.displayMetrics.density
            val panelH = (162 * 2 * density).toInt()   // 162dp × 2 ≈ 850px
            contentArea.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                panelH,
            )
            // 面板 MATCH_PARENT 填满 contentArea 固定高度
            clipboardPanel.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // viewLetters GONE（contentArea 已是固定高度，不塌缩）
            viewLetters.visibility = View.GONE
            clipboardPanel.visibility = View.VISIBLE
            clipboardPanel.onPanelShown()
            clipboardActive = true
            listener?.onClipboardStateChanged(true)
            Diagnostics.i(
                TAG,
                "剪贴板面板: 显示 panelH=$panelH contentArea=${contentArea.height}",
            )
        } catch (t: Throwable) {
            Diagnostics.e(TAG, "showClipboardPanel 异常: ${t.message}")
            clipboardActive = false
        }
    }

    /** 关闭剪贴板面板，恢复 26 键字母布局（contentArea 恢复字母区高度） */
    fun hideClipboardPanel() {
        if (!clipboardActive) return
        // 恢复字母区，隐藏面板
        viewLetters.visibility = View.VISIBLE
        clipboardPanel.visibility = View.GONE
        // contentArea 恢复 wrap_content（字母区自身高度）——父是 LinearLayout，用 LinearLayout.LayoutParams
        contentArea.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        clipboardActive = false
        listener?.onClipboardStateChanged(false)
        Diagnostics.i(TAG, "剪贴板面板: 隐藏，恢复字母键盘")
    }

    /** 构建方向面板（3×3 九宫格，与字母区总高一致） */
    private fun ensureDirectionPanel() {
        if (directionPanel != null) {
            Diagnostics.v(TAG, "ensureDirectionPanel: 已存在，跳过")
            return
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 3 行均分字母区总高（原 3 行字母 54dp×3=162dp）
        // 每行按钮高 = (162dp - 行间距)/3，行间距 2dp×2
        val totalDp = 162
        val gapDp = 2
        val rowH = (resources.displayMetrics.density * (totalDp - gapDp * 2 * 3) / 3).toInt()
        Diagnostics.v(TAG, "ensureDirectionPanel: rowH=$rowH")

        // 行1：行首 上 行末
        panel.addView(directionRow(rowH, listOf(
            directionKey("│←", DirectionAction.LINE_START) to 1f,
            directionKey("↑", DirectionAction.UP) to 1f,
            directionKey("→│", DirectionAction.LINE_END) to 1f,
        )))
        // 行2：左 ●(拖选开关) 右
        val center = directionKey("●", DirectionAction.TOGGLE_SELECTION, selectionKey = true)
        centerSelectionKey = center
        panel.addView(directionRow(rowH, listOf(
            directionKey("←", DirectionAction.LEFT) to 1f,
            center to 1f,
            directionKey("→", DirectionAction.RIGHT) to 1f,
        )))
        // 行3：复制 下 粘贴
        panel.addView(directionRow(rowH, listOf(
            directionKey("复制", DirectionAction.COPY) to 1f,
            directionKey("↓", DirectionAction.DOWN) to 1f,
            directionKey("粘贴", DirectionAction.PASTE) to 1f,
        )))
        directionPanel = panel
        Diagnostics.v(TAG, "ensureDirectionPanel: 构建完成 childCount=${panel.childCount}")
    }

    /** 构建一行方向键 */
    private fun directionRow(rowH: Int, items: List<Pair<View, Float>>): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        for ((key, weight) in items) {
            row.addView(key, LinearLayout.LayoutParams(0, rowH, weight).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        return row
    }

    /**
     * 构建单个方向键：深色圆角，居中文字。
     * [selectionKey] 为中心拖选开关（●/◉ 双状态），由 [setSelectionActive] 驱动。
     */
    private fun directionKey(
        label: String,
        action: DirectionAction,
        selectionKey: Boolean = false,
    ): TextView {
        val key = TextView(context).apply {
            text = label
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(R.color.text_primary, context.theme))
            setBackgroundResource(R.drawable.key_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Diagnostics.i(TAG, "方向按键: $action")
                listener?.onDirectionAction(action)
            }
        }
        if (selectionKey) key.text = if (selectionActive) "◉" else "●"
        return key
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "PinyinKeyboard"
        const val LAYER_LETTER = 0
        const val LAYER_SYMBOL = 1
        const val LAYER_DIGIT = 2
    }
}
