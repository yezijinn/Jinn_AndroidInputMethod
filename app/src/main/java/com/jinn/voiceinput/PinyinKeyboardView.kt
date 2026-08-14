package com.jinn.voiceinput

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 拼音键盘视图：候选栏 + 26 键 + 底部功能行。
 *
 * 职责：
 *  - 收集字母输入（全拼或自然码双拼），实时查询 [PinyinEngine] 显示候选
 *  - 候选点选 / 空格取首候选 / 退格回删拼音
 *  - 中文候选上屏、英文直通上屏、符号层
 *  - 「语音」键切回语音模式（通过 [OnVoiceRequested] 回调给 IME）
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

    /** 是否处于符号层 */
    private var symbolMode = false

    /** 全拼还是双拼（来自设置） */
    private var shuangpinMode = false

    private lateinit var viewCandidatePinyin: TextView
    private lateinit var viewCandidateList: LinearLayout
    private lateinit var viewLetters: LinearLayout
    private lateinit var btnSymbol: TextView
    private lateinit var btnLang: TextView
    private lateinit var btnVoice: TextView
    private lateinit var btnSpace: View
    private lateinit var btnBackspace: View
    private lateinit var btnEnter: View

    private val keyViews = HashMap<Char, PinyinKey>()

    /** 候选缓存：空格取第一个 */
    private var lastCandidates: List<String> = emptyList()

    // ── 字母行定义（标准 QWERTY） ──────────────────────────
    private val rows = arrayOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
    )

    /** 符号层定义：键位 → 主符号 / 长按符号（简化只给主符号） */
    private val symbolMap = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "!", 's' to "@", 'd' to "#", 'f' to "$", 'g' to "%",
        'h' to "^", 'j' to "&", 'k' to "*", 'l' to "(",
        'z' to ")", 'x' to "-", 'c' to "_", 'v' to "=", 'b' to "+",
        'n' to "[", 'm' to "]",
    )

    init {
        orientation = VERTICAL
        val root = LayoutInflater.from(context).inflate(R.layout.keyboard_pinyin, this, true)

        viewCandidatePinyin = root.findViewById(R.id.candidate_pinyin)
        viewCandidateList = root.findViewById(R.id.candidate_list)
        viewLetters = root.findViewById(R.id.keyboard_letters)

        btnSymbol = root.findViewById(R.id.key_symbol)
        btnLang = root.findViewById(R.id.key_lang)
        btnVoice = root.findViewById(R.id.key_voice)
        btnSpace = root.findViewById(R.id.key_space)
        btnBackspace = root.findViewById(R.id.key_backspace)
        btnEnter = root.findViewById(R.id.key_enter)

        bindLetterKeys(root)
        bindFunctionKeys()
        refreshKeyLabels()
        refreshCandidateBar()
        // 调试：字母区在 onLayout 后记录实际屏幕坐标，便于真机定位按键
        viewLetters.post {
            val loc = IntArray(2)
            viewLetters.getLocationOnScreen(loc)
            Diagnostics.i(TAG, "字母区位置: x=${loc[0]} y=${loc[1]} w=${viewLetters.width} h=${viewLetters.height}")
            for (c in "nihao") {
                val k = keyViews[c] ?: continue
                val kl = IntArray(2)
                k.getLocationOnScreen(kl)
                Diagnostics.i(TAG, "键 $c: x=${kl[0]} y=${kl[1]} w=${k.width} h=${k.height}")
            }
        }
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
            symbolMode = !symbolMode
            refreshKeyLabels()
            Diagnostics.i(TAG, "symbol 层: $symbolMode")
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
        btnVoice.setOnClickListener { listener?.onVoiceRequested() }
        btnSpace.setOnClickListener { onSpacePressed() }
        btnBackspace.setOnClickListener { onBackspacePressed() }
        btnBackspace.setOnLongClickListener {
            // 长按退格：清空整个拼音串
            if (composing.isNotEmpty()) {
                composing.clear()
                refreshCandidateBar()
            } else {
                listener?.onBackspace()
            }
            true
        }
        btnEnter.setOnClickListener { listener?.onEnter() }
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
            "symbol" to btnSymbol, "lang" to btnLang, "voice" to btnVoice,
            "space" to btnSpace, "backspace" to btnBackspace, "enter" to btnEnter,
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
        if (symbolMode) {
            // 符号层：数字/符号直接上屏
            val symbol = symbolMap[c] ?: return
            listener?.onCommitText(symbol)
            return
        }
        if (englishMode) {
            // 英文模式：字母直通
            listener?.onCommitText(c.toString())
            return
        }
        // 中文模式：追加到拼音串并查候选
        composing.append(c)
        refreshCandidateBar()
        Diagnostics.v(TAG, "拼音输入: ${composing}")
    }

    private fun onSpacePressed() {
        if (symbolMode) {
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
        Diagnostics.v(TAG, "候选: $queryInput → ${result.candidates.take(3)}")

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
        listener?.onCommitText(candidate)
        composing.clear()
        refreshCandidateBar()
    }

    // ── 键面显示 ───────────────────────────────────────────

    private fun refreshKeyLabels() {
        for (c in 'a'..'z') {
            val key = keyViews[c] ?: continue
            key.label = if (symbolMode) {
                symbolMap[c] ?: c.toString()
            } else {
                c.toString()
            }
            // 中文模式下显示自然码双拼的键位小字提示（可选）
            key.subLabel = if (!symbolMode && !englishMode && shuangpinMode) {
                shuangpinHint(c)
            } else {
                ""
            }
        }
        btnLang.text = context.getString(if (englishMode) R.string.key_en else R.string.key_cn)
        btnSymbol.text = if (symbolMode) context.getString(R.string.key_abc) else "123"
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
    }
}
