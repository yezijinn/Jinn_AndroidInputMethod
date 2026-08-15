package com.jinn.voiceinput

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
    }

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

    // ── 删除键三态：单击删一个 / 按住连续删 / 快速三击全删 ──
    private val backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceHeld = false
    private var backspacePressStart = 0L
    private val backspaceTapTimes = LongArray(3)
    private var backspaceTapCount = 0

    /** 按住退格超过该时长进入连续删除 */
    private val backspaceRepeatDelayMs = 380L

    /** 连续删除的间隔 */
    private val backspaceRepeatIntervalMs = 55L

    /** 三击判定的最大间隔（从第一次按下到第三次按下） */
    private val tripleTapWindowMs = 700L

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

        btnSymbol = root.findViewById(R.id.key_symbol)
        btnDigit = root.findViewById(R.id.key_digit)
        btnLang = root.findViewById(R.id.key_lang)
        btnSpace = root.findViewById(R.id.key_space)
        btnBackspace = root.findViewById(R.id.key_backspace)
        btnEnter = root.findViewById(R.id.key_enter)
        btnShift = root.findViewById(R.id.key_shift)
        btnComma = root.findViewById(R.id.key_comma)
        btnPeriod = root.findViewById(R.id.key_period)

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

    /** 设置输入方案（全拼/双拼）与初始中英文状态 */
    fun configure(shuangpin: Boolean, english: Boolean) {
        shuangpinMode = shuangpin
        englishMode = english
        refreshKeyLabels()
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
        if (composing.isEmpty()) return
        val candidates = lastCandidates
        if (candidates.isNotEmpty()) {
            listener?.onCommitText(candidates[0])
        } else if (!englishMode) {
            // 无候选（如未加载词库），直接丢拼音串
            Diagnostics.w(TAG, "commitComposing: 无候选，丢弃拼音 ${composing}")
        }
        composing.clear()
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
            refreshCandidateBar()
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
     * 删除键三态触摸：
     *  - 按下即删一个字符；按住超过 [backspaceRepeatDelayMs] 进入连续删除，松开停止
     *  - 快速三击（[tripleTapWindowMs] 内）触发 [onTripleBackspace] 全部清空
     */
    private fun handleBackspaceTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                backspaceHeld = true
                backspacePressStart = System.currentTimeMillis()
                deleteOne()
                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                backspaceHandler.postDelayed(backspaceRepeatRunnable, backspaceRepeatDelayMs)
                btnBackspace.isPressed = true
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                btnBackspace.isPressed = false
                if (!backspaceHeld) return true
                backspaceHeld = false
                val held = System.currentTimeMillis() - backspacePressStart
                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                // 短按（未进入连续删除）才算一次点击，用于三击判定
                if (held < backspaceRepeatDelayMs) {
                    recordBackspaceTap()
                }
                return true
            }
        }
        return false
    }

    /** 记录一次退格点击；三击（窗口内）触发全部清空 */
    private fun recordBackspaceTap() {
        val now = System.currentTimeMillis()
        if (backspaceTapCount > 0 && now - backspaceTapTimes[backspaceTapCount - 1] > tripleTapWindowMs) {
            backspaceTapCount = 0
        }
        backspaceTapTimes[backspaceTapCount] = now
        backspaceTapCount++
        if (backspaceTapCount >= 3) {
            backspaceTapCount = 0
            Diagnostics.i(TAG, "退格三击：清空全部")
            onTripleBackspace()
        }
    }

    /** 三击清空：先清拼音串，再通知 IME 删除已上屏文本 */
    private fun onTripleBackspace() {
        if (composing.isNotEmpty()) {
            composing.clear()
            refreshCandidateBar()
        }
        listener?.onDeleteAll()
    }

    /** 删除一个字符（有拼音串删拼音，否则删已上屏） */
    private fun deleteOne() {
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            refreshCandidateBar()
            Diagnostics.v(TAG, "退格删拼音: ${composing}")
        } else {
            listener?.onBackspace()
        }
    }

    // ── 候选渲染 ───────────────────────────────────────────

    private fun refreshCandidateBar() {
        val input = composing.toString()
        if (input.isEmpty()) {
            lastCandidates = emptyList()
            viewCandidatePinyin.text = ""
            viewCandidateList.removeAllViews()
            return
        }

        // 双拼：先转全拼再查询；显示仍保留双拼原文
        val queryInput = if (shuangpinMode) Shuangpin.toQuanpin(input) else input
        val result = PinyinEngine.query(queryInput)
        lastCandidates = result.candidates
        viewCandidatePinyin.text = queryInput
        Diagnostics.v(TAG, "候选: ${if (shuangpinMode) "双拼[$input]→" else ""}$queryInput → ${result.candidates.take(3)}")

        viewCandidateList.removeAllViews()
        for ((index, candidate) in result.candidates.withIndex()) {
            val item = TextView(context).apply {
                text = candidate
                textSize = 16f
                setTextColor(resources.getColor(R.color.text_primary, context.theme))
                setPadding(dp(10), 0, dp(10), 0)
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
        refreshCandidateBar()
    }

    // ── 键面显示 ───────────────────────────────────────────

    private fun refreshKeyLabels() {
        val showUpper = capsMode && !englishMode && layer == LAYER_LETTER
        for (c in 'a'..'z') {
            val key = keyViews[c] ?: continue
            key.label = when (layer) {
                LAYER_SYMBOL -> symbolMap[c] ?: c.toString()
                LAYER_DIGIT -> digitMap[c] ?: c.toString()
                else ->
                    if (showUpper) c.uppercaseChar().toString() else c.toString()
            }
            // 双拼模式下显示自然码键位小字提示（字母层）
            key.subLabel = if (layer == LAYER_LETTER && !englishMode && shuangpinMode) {
                shuangpinHint(c)
            } else {
                ""
            }
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

    /** 自然码双拼键位小字提示：zh/ch/sh 标在 v/i/u 上 */
    private fun shuangpinHint(c: Char): String = when (c) {
        'v' -> "zh"
        'i' -> "ch"
        'u' -> "sh"
        else -> ""
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "PinyinKeyboard"
        const val LAYER_LETTER = 0
        const val LAYER_SYMBOL = 1
        const val LAYER_DIGIT = 2
    }
}
