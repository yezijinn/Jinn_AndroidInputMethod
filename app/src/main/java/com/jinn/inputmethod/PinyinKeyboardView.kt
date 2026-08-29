package com.jinn.inputmethod

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
        /** 功能面板：全选当前输入框全部文本 */
        fun onSelectAll()
        /** 功能面板：复制选中文本到系统剪贴板 */
        fun onCopy()
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

    /** 顶部搜索面板（候选栏上方，见 init 挂载） */
    private lateinit var searchPanel: SearchPanelView

    /** 剪贴板面板是否激活 */
    private var clipboardActive = false
    private lateinit var btnSymbol: TextView
    private lateinit var btnDigit: TextView
    private lateinit var btnLang: TextView
    private lateinit var btnSpace: View
    /** 空格键顶部的小字提示（当前输入类型：小写英文/大写英文/中文全拼/中文双拼） */
    private lateinit var btnSpaceHint: TextView
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

    /** 数字层定义：键位 → 数字/符号 */
    private val digitMap = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "-", 's' to "/", 'd' to ":", 'f' to ";", 'g' to "(",
        'h' to ")", 'j' to "¥", 'k' to "@", 'l' to "&",
        'z' to ".", 'x' to ",", 'c' to "?", 'v' to "!", 'b' to "'",
        'n' to "\"", 'm' to "%",
    )

/** 符号分组：label 为候选栏标签；pages 为该组内的符号页（键盘滑动在此组内翻页），可自由添加页数不限 */
    private class SymbolGroup(val label: String, val pages: List<Map<Char, String>>)

    private val symbolGroups: List<SymbolGroup> = listOf(
        // 常用
        SymbolGroup("常用", listOf(
            mapOf(
                'q' to "！", 'w' to "？", 'e' to "。", 'r' to "，", 't' to "；",
                'y' to "：", 'u' to "“", 'i' to "”", 'o' to "（", 'p' to "）",
                'a' to "【", 's' to "】", 'd' to "《", 'f' to "》", 'g' to "·",
                'h' to "—", 'j' to "…", 'k' to "、", 'l' to "~",
                'z' to "@", 'x' to "#", 'c' to "$", 'v' to "%", 'b' to "^",
                'n' to "&", 'm' to "*",
            ),
            mapOf(
                'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
                'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
                'a' to "-", 's' to "/", 'd' to ":", 'f' to ";", 'g' to "(",
                'h' to ")", 'j' to "'", 'k' to "\"", 'l' to "\\",
                'z' to "=", 'x' to "+", 'c' to "_", 'v' to "|", 'b' to "`",
                'n' to "[", 'm' to "]",
            ),
        )),
        // 标点
        SymbolGroup("标点", listOf(
            mapOf(
                'q' to "‘", 'w' to "’", 'e' to "「", 'r' to "」", 't' to "『",
                'y' to "』", 'u' to "〔", 'i' to "〕", 'o' to "〈", 'p' to "〉",
                'a' to "…", 's' to "……", 'd' to "—", 'f' to "——", 'g' to "·",
                'h' to "、", 'j' to "，", 'k' to "､", 'l' to "｡",
                'z' to "！", 'x' to "？", 'c' to "；", 'v' to "：", 'b' to "。",
                'n' to "，", 'm' to "……",
            ),
            mapOf(
                'q' to "※", 'w' to "々", 'e' to "〆", 'r' to "〇", 't' to "〈",
                'y' to "〉", 'u' to "《", 'i' to "》", 'o' to "「", 'p' to "」",
                'a' to "『", 's' to "』", 'd' to "〔", 'f' to "〕", 'g' to "〈~",
                'h' to "＞", 'j' to "＜", 'k' to "＞", 'l' to "≪",
                'z' to "≫", 'x' to "〈", 'c' to "〉", 'v' to "「", 'b' to "」",
                'n' to "‖", 'm' to "……",
            ),
            // 排版/装饰符号
            mapOf(
                'q' to "＊", 'w' to "§", 'e' to "¶", 'r' to "†", 't' to "‡",
                'y' to "«", 'u' to "»", 'i' to "‹", 'o' to "›", 'p' to "≈",
                'a' to "「", 's' to "」", 'd' to "『", 'f' to "』", 'g' to "【",
                'h' to "】", 'j' to "〈", 'k' to "〉", 'l' to "⌒",
                'z' to "々", 'x' to "〆", 'c' to "△", 'v' to "◇", 'b' to "○",
                'n' to "□", 'm' to "☆",
            ),
        )),
        // 序号
        SymbolGroup("序号", listOf(
            mapOf(
                'q' to "①", 'w' to "②", 'e' to "③", 'r' to "④", 't' to "⑤",
                'y' to "⑥", 'u' to "⑦", 'i' to "⑧", 'o' to "⑨", 'p' to "⑩",
                'a' to "❶", 's' to "❷", 'd' to "❸", 'f' to "❹", 'g' to "❺",
                'h' to "❻", 'j' to "❼", 'k' to "❽", 'l' to "❾",
                'z' to "Ⅰ", 'x' to "Ⅱ", 'c' to "Ⅲ", 'v' to "Ⅳ", 'b' to "Ⅴ",
                'n' to "Ⅵ", 'm' to "Ⅶ",
            ),
            mapOf(
                'q' to "⑪", 'w' to "⑫", 'e' to "⑬", 'r' to "⑭", 't' to "⑮",
                'y' to "⑯", 'u' to "⑰", 'i' to "⑱", 'o' to "⑲", 'p' to "⑳",
                'a' to "㈠", 's' to "㈡", 'd' to "㈢", 'f' to "㈣", 'g' to "㈤",
                'h' to "⒈", 'j' to "⒉", 'k' to "3", 'l' to "⒋",
                'z' to "Ⅷ", 'x' to "Ⅸ", 'c' to "Ⅹ", 'v' to "Ⅺ", 'b' to "Ⅻ",
                'n' to "Ⅷ", 'm' to "Ⅻ",
            ),
            // 圈中文 + 字母序号
            mapOf(
                'q' to "㊀", 'w' to "㊁", 'e' to "㊂", 'r' to "㊃", 't' to "㊄",
                'y' to "㊅", 'u' to "㊆", 'i' to "㊇", 'o' to "㊈", 'p' to "㊉",
                'a' to "Ⓐ", 's' to "Ⓑ", 'd' to "Ⓒ", 'f' to "Ⓓ", 'g' to "Ⓔ",
                'h' to "Ⓕ", 'j' to "Ⓖ", 'k' to "Ⓗ", 'l' to "Ⓘ",
                'z' to "Ⓙ", 'x' to "Ⓚ", 'c' to "Ⓛ", 'v' to "Ⓜ", 'b' to "Ⓝ",
                'n' to "Ⓞ", 'm' to "Ⓟ",
            ),
            mapOf(
                'q' to "㈥", 'w' to "㈦", 'e' to "㈧", 'r' to "㈨", 't' to "㈩",
                'y' to "⒌", 'u' to "⒍", 'i' to "⒎", 'o' to "⒏", 'p' to "⒐",
                'a' to "⒑", 's' to "⒒", 'd' to "⒓", 'f' to "⒔", 'g' to "⒕",
                'h' to "⒖", 'j' to "⒗", 'k' to "⒘", 'l' to "⒙",
                'z' to "⒚", 'x' to "⒛", 'c' to "Ⓠ", 'v' to "Ⓡ", 'b' to "Ⓢ",
                'n' to "Ⓣ", 'm' to "Ⓤ",
            ),
        )),
        // 数学
        SymbolGroup("数学", listOf(
            mapOf(
                'q' to "＋", 'w' to "－", 'e' to "×", 'r' to "÷", 't' to "＝",
                'y' to "≠", 'u' to "≈", 'i' to "±", 'o' to "＜", 'p' to "＞",
                'a' to "≤", 's' to "≥", 'd' to "√", 'f' to "∞", 'g' to "∝",
                'h' to "∑", 'j' to "∏", 'k' to "∫", 'l' to "％",
                'z' to "∠", 'x' to "π", 'c' to "⊥", 'v' to "‖", 'b' to "∈",
                'n' to "∉", 'm' to "≈",
            ),
            mapOf(
                'q' to "＋", 'w' to "−", 'e' to "×", 'r' to "÷", 't' to "=",
                'y' to "≡", 'u' to "≠", 'i' to "≒", 'o' to "＜", 'p' to "＞",
                'a' to "≤", 's' to "≥", 'd' to "√", 'f' to "∛", 'g' to "∜",
                'h' to "∂", 'j' to "∇", 'k' to "∫∫∫", 'l' to "∭",
                'z' to "∞", 'x' to "㏕", 'c' to "⋂", 'v' to "⋃", 'b' to "∣",
                'n' to "∤", 'm' to "≈",
            ),
            // 希腊字母
            mapOf(
                'q' to "α", 'w' to "β", 'e' to "γ", 'r' to "δ", 't' to "ε",
                'y' to "ζ", 'u' to "η", 'i' to "θ", 'o' to "λ", 'p' to "μ",
                'a' to "ξ", 's' to "π", 'd' to "ρ", 'f' to "σ", 'g' to "τ",
                'h' to "φ", 'j' to "χ", 'k' to "ψ", 'l' to "ω",
                'z' to "Α", 'x' to "Β", 'c' to "Γ", 'v' to "Δ", 'b' to "Θ",
                'n' to "Λ", 'm' to "Ω",
            ),
            mapOf(
                'q' to "ι", 'w' to "κ", 'e' to "ν", 'r' to "ο", 't' to "υ",
                'y' to "Ε", 'u' to "Ζ", 'i' to "Η", 'o' to "Ι", 'p' to "Κ",
                'a' to "Μ", 's' to "Ν", 'd' to "Ξ", 'f' to "Ο", 'g' to "Π",
                'h' to "Ρ", 'j' to "Σ", 'k' to "Τ", 'l' to "Υ",
                'z' to "Φ", 'x' to "Χ", 'c' to "Ψ", 'v' to "Ω", 'b' to "Ϝ",
                'n' to "Ϟ", 'm' to "Ϡ",
            ),
        )),
        // 单位
        SymbolGroup("单位", listOf(
            mapOf(
                'q' to "￥", 'w' to "＄", 'e' to "€", 'r' to "£", 't' to "￡",
                'y' to "℃", 'u' to "℉", 'i' to "°", 'o' to "％", 'p' to "‰",
                'a' to "㎝", 's' to "㎜", 'd' to "㎞", 'f' to "㎡", 'g' to "㎥",
                'h' to "μ", 'j' to "Ω", 'k' to "Ｖ", 'l' to "Ａ",
                'z' to "²", 'x' to "³", 'c' to "＃", 'v' to "＆", 'b' to "＠",
                'n' to "¥", 'm' to "＄",
            ),
            mapOf(
                'q' to "㎡", 'w' to "㎞", 'e' to "ｇ", 'r' to "ｍ", 't' to "Ｌ",
                'y' to "℃", 'u' to "℉", 'i' to "°", 'o' to "％", 'p' to "‰",
                'a' to "㎎", 's' to "㎏", 'd' to "㏄", 'f' to "㏗", 'g' to "㎒",
                'h' to "㎓", 'j' to "㎑", 'k' to "ｋｍ", 'l' to "ｃｍ",
                'z' to "②", 'x' to "③", 'c' to "④", 'v' to "⑤", 'b' to "⑥",
                'n' to "⑦", 'm' to "⑧",
            ),
            mapOf(
                'q' to "㏑", 'w' to "㏒", 'e' to "㏈", 'r' to "㏉", 't' to "㏊",
                'y' to "㏋", 'u' to "㏌", 'i' to "㏍", 'o' to "㏎", 'p' to "㏏",
                'a' to "㏐", 's' to "㏓", 'd' to "㏔", 'f' to "㏕", 'g' to "㏖",
                'h' to "㏘", 'j' to "㏙", 'k' to "㏚", 'l' to "㏛",
                'z' to "㏜", 'x' to "㏝", 'c' to "㎖", 'v' to "㎗", 'b' to "㎘",
                'n' to "㎠", 'm' to "㎰",
            ),
        )),
        // 平假名
        SymbolGroup("平假名", listOf(
            mapOf(
                'q' to "あ", 'w' to "い", 'e' to "う", 'r' to "え", 't' to "お",
                'y' to "か", 'u' to "き", 'i' to "く", 'o' to "け", 'p' to "こ",
                'a' to "さ", 's' to "し", 'd' to "す", 'f' to "せ", 'g' to "そ",
                'h' to "た", 'j' to "ち", 'k' to "つ", 'l' to "て",
                'z' to "な", 'x' to "に", 'c' to "ぬ", 'v' to "ね", 'b' to "の",
                'n' to "は", 'm' to "ひ",
            ),
            mapOf(
                'q' to "ふ", 'w' to "へ", 'e' to "ほ", 'r' to "ま", 't' to "み",
                'y' to "む", 'u' to "め", 'i' to "も", 'o' to "や", 'p' to "ゆ",
                'a' to "よ", 's' to "ら", 'd' to "り", 'f' to "る", 'g' to "れ",
                'h' to "ろ", 'j' to "わ", 'k' to "を", 'l' to "ん",
                'z' to "が", 'x' to "ぎ", 'c' to "ぐ", 'v' to "げ", 'b' to "ご",
                'n' to "ぱ", 'm' to "ぴ",
            ),
            // 浊音/半浊音/小写
            mapOf(
                'q' to "ざ", 'w' to "じ", 'e' to "ず", 'r' to "ぜ", 't' to "ぞ",
                'y' to "だ", 'u' to "ぢ", 'i' to "づ", 'o' to "で", 'p' to "ど",
                'a' to "ば", 's' to "び", 'd' to "ぶ", 'f' to "べ", 'g' to "ぼ",
                'h' to "ぱ", 'j' to "ぴ", 'k' to "ぷ", 'l' to "ぺ",
                'z' to "ぽ", 'x' to "ぁ", 'c' to "ぃ", 'v' to "ぅ", 'b' to "ぇ",
                'n' to "ぉ", 'm' to "ゃ",
            ),
            mapOf(
                'q' to "ゅ", 'w' to "ょ", 'e' to "っ", 'r' to "ゎ", 't' to "ゐ",
                'y' to "ゑ", 'u' to "ゝ", 'i' to "ゞ", 'o' to "ゕ", 'p' to "ゖ",
            ),
        )),
        // 片假名
        SymbolGroup("片假名", listOf(
            mapOf(
                'q' to "ア", 'w' to "イ", 'e' to "ウ", 'r' to "エ", 't' to "オ",
                'y' to "カ", 'u' to "キ", 'i' to "ク", 'o' to "ケ", 'p' to "コ",
                'a' to "サ", 's' to "シ", 'd' to "ス", 'f' to "セ", 'g' to "ソ",
                'h' to "タ", 'j' to "チ", 'k' to "ツ", 'l' to "テ",
                'z' to "ナ", 'x' to "ニ", 'c' to "ヌ", 'v' to "ネ", 'b' to "ノ",
                'n' to "ハ", 'm' to "ヒ",
            ),
            mapOf(
                'q' to "フ", 'w' to "ヘ", 'e' to "ホ", 'r' to "マ", 't' to "ミ",
                'y' to "ム", 'u' to "メ", 'i' to "モ", 'o' to "ヤ", 'p' to "ユ",
                'a' to "ヨ", 's' to "ラ", 'd' to "リ", 'f' to "ル", 'g' to "レ",
                'h' to "ロ", 'j' to "ワ", 'k' to "ヲ", 'l' to "ン",
                'z' to "ガ", 'x' to "ギ", 'c' to "グ", 'v' to "ゲ", 'b' to "ゴ",
                'n' to "パ", 'm' to "ピ",
            ),
            // 浊音/半浊音/小写
            mapOf(
                'q' to "ザ", 'w' to "ジ", 'e' to "ズ", 'r' to "ゼ", 't' to "ゾ",
                'y' to "ダ", 'u' to "ヂ", 'i' to "ヅ", 'o' to "デ", 'p' to "ド",
                'a' to "バ", 's' to "ビ", 'd' to "ブ", 'f' to "ベ", 'g' to "ボ",
                'h' to "パ", 'j' to "ピ", 'k' to "プ", 'l' to "ペ",
                'z' to "ポ", 'x' to "ァ", 'c' to "ィ", 'v' to "ゥ", 'b' to "ェ",
                'n' to "ォ", 'm' to "ャ",
            ),
            mapOf(
                'q' to "ュ", 'w' to "ョ", 'e' to "ッ", 'r' to "ヮ", 't' to "ヰ",
                'y' to "ヱ", 'u' to "ヽ", 'i' to "ヾ", 'o' to "ヵ", 'p' to "ヶ",
            ),
        )),
        // 拉丁
        SymbolGroup("拉丁", listOf(
            mapOf(
                'q' to "á", 'w' to "à", 'e' to "ä", 'r' to "â", 't' to "é",
                'y' to "è", 'u' to "ë", 'i' to "ê", 'o' to "í", 'p' to "ì",
                'a' to "ï", 's' to "î", 'd' to "ó", 'f' to "ò", 'g' to "ö",
                'h' to "ô", 'j' to "ú", 'k' to "ù", 'l' to "ü",
                'z' to "û", 'x' to "ç", 'c' to "ñ", 'v' to "ß", 'b' to "œ",
                'n' to "ÿ", 'm' to "æ",
            ),
            mapOf(
                'q' to "Á", 'w' to "À", 'e' to "Ä", 'r' to "Â", 't' to "É",
                'y' to "È", 'u' to "Ë", 'i' to "Ê", 'o' to "Í", 'p' to "Ì",
                'a' to "Ï", 's' to "Î", 'd' to "Ó", 'f' to "Ò", 'g' to "Ö",
                'h' to "Ô", 'j' to "Ú", 'k' to "Ù", 'l' to "Ü",
                'z' to "Œ", 'x' to "Ÿ", 'c' to "Û", 'v' to "Ñ", 'b' to "Æ",
                'n' to "©", 'm' to "®",
            ),
            mapOf(
                'q' to "ø", 'w' to "å", 'e' to "Ø", 'r' to "Å", 't' to "þ",
                'y' to "ð", 'u' to "Đ", 'i' to "đ", 'o' to "ħ", 'p' to "ŋ",
                'a' to "ŧ", 's' to "Ŧ", 'd' to "ẞ", 'f' to "Š", 'g' to "š",
                'h' to "Ž", 'j' to "ž", 'k' to "Ə", 'l' to "ə",
                'z' to "Ɛ", 'x' to "ɛ", 'c' to "Ɔ", 'v' to "ɔ", 'b' to "ɐ",
                'n' to "ɑ", 'm' to "ɒ",
            ),
        )),
        // 特殊
        SymbolGroup("特殊", listOf(
            mapOf(
                'q' to "♥", 'w' to "♦", 'e' to "♣", 'r' to "♠", 't' to "★",
                'y' to "☆", 'u' to "♪", 'i' to "♫", 'o' to "☺", 'p' to "☹",
                'a' to "♡", 's' to "♢", 'd' to "♧", 'f' to "♤", 'g' to "☞",
                'h' to "☜", 'j' to "❀", 'k' to "☆", 'l' to "⚡",
                'z' to "✓", 'x' to "✗", 'c' to "☑", 'v' to "☐", 'b' to "①②",
                'n' to "③", 'm' to "④",
            ),
            mapOf(
                'q' to "☀", 'w' to "☁", 'e' to "☂", 'r' to "❄", 't' to "☃",
                'y' to "☎", 'u' to "✉", 'i' to "✈", 'o' to "⚓", 'p' to "⚑",
                'a' to "❤", 's' to "☻", 'd' to "☼", 'f' to "☽", 'g' to "♨",
                'h' to "☿", 'j' to "♄", 'k' to "由", 'l' to "白",
                'z' to "☘", 'x' to "♁", 'c' to "☛", 'v' to "☚", 'b' to "➜",
                'n' to "⏏", 'm' to "※",
            ),
            // 常用 emoji
            mapOf(
                'q' to "😀", 'w' to "😁", 'e' to "😂", 'r' to "🤣", 't' to "😊",
                'y' to "😉", 'u' to "😍", 'i' to "😘", 'o' to "😎", 'p' to "🤔",
                'a' to "😏", 's' to "😒", 'd' to "😢", 'f' to "😭", 'g' to "😅",
                'h' to "😳", 'j' to "🤗", 'k' to "💪", 'l' to "👌",
                'z' to "👍", 'x' to "👎", 'c' to "🙏", 'v' to "💯", 'b' to "❤️",
                'n' to "🔥", 'm' to "🎉",
            ),
        )),
        // 注音：第 1 页声调（带调拼音字母 + 声调符号），其后为完整注音符号
        SymbolGroup("注音", listOf(
            mapOf(
                'q' to "ā", 'w' to "á", 'e' to "ǎ", 'r' to "à", 't' to "ō",
                'y' to "ó", 'u' to "ǒ", 'i' to "ò", 'o' to "ē", 'p' to "é",
                'a' to "ě", 's' to "è", 'd' to "ī", 'f' to "í", 'g' to "ǐ",
                'h' to "ì", 'j' to "ū", 'k' to "ú", 'l' to "ǔ",
                'z' to "ù", 'x' to "ǖ", 'c' to "ǘ", 'v' to "ǚ", 'b' to "ǜ",
                'n' to "ˉ", 'm' to "ˊ",
            ),
            // 第 2 页：剩余声调符号 + 注音声母/介音
            mapOf(
                'q' to "ˇ", 'w' to "ˋ", 'e' to "˙", 'r' to "ㄅ", 't' to "ㄆ",
                'y' to "ㄇ", 'u' to "ㄈ", 'i' to "ㄉ", 'o' to "ㄊ", 'p' to "ㄋ",
                'a' to "ㄌ", 's' to "ㄍ", 'd' to "ㄎ", 'f' to "ㄏ", 'g' to "ㄐ",
                'h' to "ㄑ", 'j' to "ㄒ", 'k' to "ㄓ", 'l' to "ㄔ",
                'z' to "ㄕ", 'x' to "ㄖ", 'c' to "ㄗ", 'v' to "ㄘ", 'b' to "ㄙ",
                'n' to "ㄧ", 'm' to "ㄨ",
            ),
            // 第 3 页：注音韵母（不满 26 键，剩余按键显示空文本）
            mapOf(
                'q' to "ㄩ", 'w' to "ㄚ", 'e' to "ㄛ", 'r' to "ㄜ", 't' to "ㄝ",
                'y' to "ㄞ", 'u' to "ㄟ", 'i' to "ㄠ", 'o' to "ㄡ", 'p' to "ㄢ",
                'a' to "ㄣ", 's' to "ㄤ", 'd' to "ㄥ", 'f' to "ㄦ", 'g' to "ㄭ",
            ),
        )),
    )

    /** 当前符号分组索引（候选栏标签，仅点击切换） */
    private var symbolGroupIndex = 0

    /** 当前组内的符号页偏移（左滑下一页/右滑上一页，不跨组） */
    private var symbolPageInGroup = 0

    /** 当前显示的分组（供标签高亮判断） */
    private fun currentSymbolGroup(): SymbolGroup =
        symbolGroups[symbolGroupIndex.coerceIn(0, symbolGroups.lastIndex)]

    /** 当前符号分组当前页的键位映射 */
    private fun currentSymbolMap(): Map<Char, String> {
        val group = currentSymbolGroup()
        return group.pages[symbolPageInGroup.coerceIn(0, group.pages.lastIndex)]
    }

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
        btnSpaceHint = root.findViewById(R.id.key_space_hint)
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
                override fun onSearch() {
                    // 请求搜索：退出剪贴板（恢复下方 26 键），顶部显示搜索面板
                    hideClipboardPanel()
                    showSearchPanel()
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

        // 顶部搜索面板：挂在根布局候选栏上方（index 0 = 最顶部），
        // 显示时 IME 整体高度增加；隐藏 GONE 后不占空间，不影响 IME relayout 流程。
        searchPanel = SearchPanelView(context).apply {
            listener = object : SearchPanelView.Listener {
                override fun onPaste(text: String): Boolean =
                    this@PinyinKeyboardView.listener?.onPasteText(text) ?: false
                override fun onClose() {
                    hideSearchPanel()
                }
            }
            visibility = View.GONE
        }
        addView(
            searchPanel,
            0,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
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
                // 用触摸监听统一处理「点击输入」与「符号层左右滑动翻页」
                key.setOnTouchListener { _, event -> handleKeyTouch(c, event) }
            }
        }
    }

    /** 字母键手势状态 */
    private var keyTouchStartX = 0f
    private var keyTouchConsumed = false

    private fun dpFloat(v: Float): Float = v * resources.displayMetrics.density

    /**
     * 字母键触摸：正常点击上屏；符号层左右滑动切换符号页。
     * 左滑（dx<0）下一页、右滑（dx>0）上一页，滑动后本次按下不再触发点击。
     */
    private fun handleKeyTouch(c: Char, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                keyTouchStartX = event.rawX
                keyTouchConsumed = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 符号层：水平滑动在「当前分组内」翻页（不切换分组）
                if (layer == LAYER_SYMBOL && !keyTouchConsumed) {
                    val group = currentSymbolGroup()
                    if (group.pages.size > 1) {
                        val dx = event.rawX - keyTouchStartX
                        if (Math.abs(dx) >= dpFloat(24f)) {
                            keyTouchConsumed = true
                            if (dx < 0) {
                                symbolPageInGroup = (symbolPageInGroup + 1) % group.pages.size
                            } else {
                                symbolPageInGroup = (symbolPageInGroup - 1 + group.pages.size) % group.pages.size
                            }
                            refreshKeyLabels()
                            refreshCandidateBar() // 同步选中组右下角的页码小字
                            Diagnostics.i(
                                TAG,
                                "符号组内翻页: ${group.label} 第 ${symbolPageInGroup + 1}/${group.pages.size} 页",
                            )
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 未滑动（或非符号层）才当作点击输入
                if (!keyTouchConsumed) onLetterPressed(c)
                keyTouchConsumed = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                keyTouchConsumed = false
                return true
            }
        }
        return false
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
            // 符号层<->字母层切换：候选栏始终回到对应状态（进入显示分组 / 退出恢复正常）
            refreshCandidateBar()
            Diagnostics.i(TAG, "符号层: ${layer == LAYER_SYMBOL}")
        }
        btnDigit.setOnClickListener {
            layer = if (layer == LAYER_DIGIT) LAYER_LETTER else LAYER_DIGIT
            refreshKeyLabels()
            refreshCandidateBar()
            Diagnostics.i(TAG, "数字层: ${layer == LAYER_DIGIT}")
        }
        btnLang.setOnClickListener {
            // 大写键激活：强制锁定大写英文，任何中英切换无效
            if (capsMode) {
                Diagnostics.i(TAG, "中英切换: 大写锁定激活，切换无效")
                return@setOnClickListener
            }
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

    /** 是否处于顶部搜索模式（26 键输入需路由到搜索框，不 commit 宿主） */
    private fun isPanelSearch() = searchPanel.isActive()

    private fun onLetterPressed(c: Char) {
        // 搜索模式：符号/数字/英文/大写 → 直接追加到搜索词；中文进拼音，选词时路由
        if (isPanelSearch()) {
            when (layer) {
                LAYER_SYMBOL -> {
                    searchPanel.appendSearch(currentSymbolMap()[c]?.takeIf { it.isNotEmpty() } ?: return)
                    return
                }
                LAYER_DIGIT -> {
                    searchPanel.appendSearch(digitMap[c] ?: return)
                    return
                }
                else -> Unit
            }
            if (englishMode) {
                searchPanel.appendSearch(if (capsMode) c.uppercaseChar().toString() else c.toString())
                return
            }
            if (capsMode) {
                searchPanel.appendSearch(c.uppercaseChar().toString())
                return
            }
            composing.append(c)
            refreshCandidateBar()
            return
        }
        // 符号层 / 数字层：直接上屏对应字符
        when (layer) {
            LAYER_SYMBOL -> {
                val symbol = currentSymbolMap()[c]?.takeIf { it.isNotEmpty() } ?: return
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
        // 搜索模式：中文取首候选、英文追加空格，均路由到搜索词
        if (isPanelSearch()) {
            if (composing.isNotEmpty()) {
                if (lastCandidates.isNotEmpty()) {
                    searchPanel.appendSearch(lastCandidates[0])
                    // 残码保留：只消费首候选的 Pinyin Span
                    consumePinyin(lastCandidates[0])
                } else {
                    composing.clear()
                }
                refreshCandidateBar()
            } else {
                searchPanel.appendSearch(" ")
            }
            return
        }
        if (layer != LAYER_LETTER) {
            listener?.onCommitSpace()
            return
        }
        if (englishMode) {
            listener?.onCommitSpace()
            return
        }
        if (composing.isNotEmpty()) {
            // 有候选取第一个（只消费其 Pinyin Span，残码保留继续匹配），否则丢拼音上空格
            if (lastCandidates.isNotEmpty()) {
                val first = lastCandidates[0]
                Diagnostics.i(TAG, "空格取首候选: \"$first\" (拼音=${composing})")
                listener?.onCommitText(first)
                if (consumePinyin(first)) {
                    // 空格上屏同样触发智能预测（与点选候选一致）
                    lastCommittedWord = first
                    lastPredictions = PinyinEngine.predict(first)
                } else {
                    lastPredictions = emptyList()
                }
            } else {
                listener?.onCommitSpace()
                composing.clear()
                lastCommittedWord = ""
                lastPredictions = emptyList()
            }
            refreshCandidateBar()
        } else if (lastPredictions.isNotEmpty()) {
            // 预测态：空格取第一个预测词
            onPredictionSelected(lastPredictions[0])
        } else {
            listener?.onCommitSpace()
        }
    }

    private fun onBackspacePressed() {
        // 搜索模式：拼音串删末尾字符，否则删除搜索词
        if (isPanelSearch()) {
            if (composing.isNotEmpty()) {
                composing.deleteCharAt(composing.length - 1)
                refreshCandidateBar()
            } else {
                searchPanel.backspaceSearch()
            }
            return
        }
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
        // 搜索模式：优先删拼音串末尾，否则删搜索框文本
        if (isPanelSearch()) {
            if (composing.isNotEmpty()) {
                composing.deleteCharAt(composing.length - 1)
                refreshCandidateBar()
            } else {
                searchPanel.backspaceSearch()
            }
            return
        }
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
        // 符号层：候选栏显示符号分组标签（可横向滚动切换）
        if (layer == LAYER_SYMBOL) {
            renderSymbolGroups()
            return
        }
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

    /** 符号层：候选栏渲染符号分组标签（横向可滚动），点击切换当前符号分组（不滑动切组） */
    private fun renderSymbolGroups() {
        viewCandidatePinyin.text = ""
        viewCandidateList.removeAllViews()
        val labelSize = 13f // 分组主文本字号（sp）
        for ((idx, group) in symbolGroups.withIndex()) {
            val sel = idx == symbolGroupIndex
            // 矩形按钮 + 主文本 + 选中组的右下角页码小字
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.key_bg_rect)
                setPadding(dpFloat(8f).toInt(), dpFloat(2f).toInt(), dpFloat(8f).toInt(), dpFloat(2f).toInt())
                isClickable = true
                setOnClickListener {
                    if (symbolGroupIndex != idx) {
                        // 分组仅点击切换，且回到该组第一页
                        symbolGroupIndex = idx
                        symbolPageInGroup = 0
                        refreshKeyLabels()
                        refreshCandidateBar()
                        Diagnostics.i(TAG, "符号分组切换: ${group.label} (${idx + 1}/${symbolGroups.size})")
                    }
                }
            }
            // 主文本（矩形样式，字体放大 30%）
            val label = TextView(context).apply {
                text = group.label
                textSize = labelSize
                setTextColor(resources.getColor(
                    if (sel) R.color.kb_key_hint_red else R.color.text_primary, context.theme))
                gravity = android.view.Gravity.CENTER
            }
            item.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // 选中组：右下角显示「当前页/总页数」小字
            if (sel) {
                val pageText = TextView(context).apply {
                    text = "${symbolPageInGroup.coerceIn(0, group.pages.lastIndex) + 1}/${group.pages.size}"
                    textSize = 9f // sp
                    setTextColor(resources.getColor(R.color.text_secondary, context.theme))
                    gravity = android.view.Gravity.RIGHT or android.view.Gravity.BOTTOM
                }
                item.addView(pageText, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            viewCandidateList.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dpFloat(2f).toInt()
                marginEnd = dpFloat(2f).toInt()
            })
        }
    }

    private fun onCandidateSelected(candidate: String) {
        // 搜索模式：候选上屏路由到剪贴板搜索词（不 commit 宿主）
        if (isPanelSearch()) {
            Diagnostics.i(TAG, "搜索候选: \"$candidate\" (拼音=${composing})")
            searchPanel.appendSearch(candidate)
            consumePinyin(candidate)
            refreshCandidateBar()
            return
        }
        Diagnostics.i(TAG, "候选上屏: \"$candidate\" (拼音=${composing})")
        listener?.onCommitText(candidate)
        // 残码重匹配：全部消费才进入智能预测态，否则候选栏立即显示残码的新候选
        if (consumePinyin(candidate)) {
            lastCommittedWord = candidate
            lastPredictions = PinyinEngine.predict(candidate)
        } else {
            lastPredictions = emptyList()
        }
        refreshCandidateBar()
    }

    /**
     * 只消费候选词实际对应的拼音区间（Pinyin Span），剩余拼音保留在
     * [composing] 中继续参与候选匹配（Residual Pinyin Rematching）。
     *
     * 双拼按键换算：两键一音节，消费 k 个音节即删除前 2k 个按键。
     *
     * @return true 表示拼音已全部消费；false 表示存在残码，候选栏应立即重查
     */
    private fun consumePinyin(candidate: String): Boolean {
        val fullInput = if (shuangpinMode) Shuangpin.toQuanpin(composing.toString()) else composing.toString()
        val consumption = PinyinEngine.consumption(fullInput, candidate)
        if (consumption.quanpinChars >= fullInput.length) {
            composing.clear()
            return true
        }
        if (shuangpinMode) {
            val keys = (consumption.syllables * 2).coerceAtMost(composing.length)
            composing.delete(0, keys)
        } else {
            composing.delete(0, consumption.quanpinChars)
        }
        Diagnostics.i(
            TAG,
            "残码保留: 消费=\"${fullInput.take(consumption.quanpinChars)}\" " +
                "剩余拼音=${if (shuangpinMode) Shuangpin.toQuanpin(composing.toString()) else composing}",
        )
        return false
    }
    private fun onPredictionSelected(pred: String) {
        Diagnostics.i(TAG, "预测上屏: \"$pred\" (基于 ${lastCommittedWord})")
        listener?.onCommitText(pred)
        lastPredictions = emptyList()
        refreshCandidateBar()
    }

    // ── 键面显示 ───────────────────────────────────────────

    private fun refreshKeyLabels() {
        // 大写锁定激活时强制 26 键全大写英文（不依赖 englishMode），大写优先级最高
        val showUpper = capsMode && layer == LAYER_LETTER
        // 全拼模式：字母大字铺满；双拼模式：小字顶置 + 韵母提示。
        // 大写键激活：统一全拼大字铺满。
        // 小写英文：统一「双拼切英文」的小字顶置样式（不随来源全拼/双拼变化）。
        val fullPinyin = when {
            capsMode -> true
            englishMode -> false
            else -> !shuangpinMode
        }
        for (c in 'a'..'z') {
            val key = keyViews[c] ?: continue
            key.fullPinyinStyle = fullPinyin && layer == LAYER_LETTER
            key.label = when (layer) {
                LAYER_SYMBOL -> currentSymbolMap()[c] ?: "" // 未映射的键显示空文本
                LAYER_DIGIT -> digitMap[c] ?: c.toString()
                else ->
                    if (showUpper) c.uppercaseChar().toString() else c.toString()
            }
            // 双拼模式下显示自然码键位提示（字母层）；大写激活时隐藏，统一用全拼大写键盘
            val showHint = layer == LAYER_LETTER && !englishMode && shuangpinMode && !capsMode
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
        // 空格键顶部小字：同步当前输入类型
        updateSpaceHint()
    }

    /** 当前输入类型文案：大写锁定激活时强制「大写英文」，任何方案切换均无效 */
    private fun currentInputTypeLabel(): String = when {
        capsMode -> "大写英文"
        englishMode -> "小写英文"
        shuangpinMode -> "中文双拼"
        else -> "中文全拼"
    }

    /** 刷新空格键顶部小字（输入类型） */
    private fun updateSpaceHint() {
        if (::btnSpaceHint.isInitialized) btnSpaceHint.text = currentInputTypeLabel()
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
     *  - 双拼/全拼：切换输入方案（键盘内部状态翻转）
     *  - 剪贴板：打开安全剪贴板历史页
     *  - 方向：打开方向控制面板（上下左右/空格/回车/行首/行末）
     *  - 全选：选中输入框全部文本
     *  - 复制：复制选中文本到系统剪贴板
     *  - 粘贴：粘贴剪贴板最新内容
     *  - 收起：隐藏输入法面板（重新点击输入框再唤醒）
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
            label = "历史",
            hint = "剪贴板",
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
            label = "全选",
            hint = "文本",
            onClick = { listener?.onSelectAll() },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "复制",
            hint = "避免窃取",
            onClick = { listener?.onCopy() },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "粘贴",
            hint = "文本",
            onClick = { listener?.onPasteClipboard() },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "收起",
            hint = "隐藏键盘",
            onClick = { listener?.onHideKeyboard() },
        ))
        Diagnostics.v(TAG, "功能面板: ${if (shuangpinMode) "双拼" else "全拼"}/历史/方向/全选/复制/粘贴/收起")
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
        // 百分比均分：每个按钮 weight=1，均分候选栏宽度（7 个按钮各占 1/7）
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
        // 大写键激活：强制锁定大写英文，任何方案切换无效
        if (capsMode) {
            Diagnostics.i(TAG, "输入方案切换: 大写锁定激活，切换无效")
            return
        }
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

    // ── 顶部搜索面板（挂在根布局候选栏上方）────────────────

    /** 是否处于顶部搜索模式 */
    fun isSearchActive(): Boolean = searchPanel.isActive()

    /** 显示顶部搜索面板：候选栏上方整体高度增加，下方 26 键恢复为可用输入 */
    fun showSearchPanel() {
        if (searchPanel.isActive()) return
        searchPanel.visibility = View.VISIBLE
        searchPanel.onShown()
        Diagnostics.i(TAG, "顶部搜索面板: 显示（IME 高度增高）")
    }

    /** 隐藏顶部搜索面板，恢复正常 26 键键盘 */
    fun hideSearchPanel() {
        if (!searchPanel.isActive()) return
        searchPanel.visibility = View.GONE
        searchPanel.onHidden()
        Diagnostics.i(TAG, "顶部搜索面板: 隐藏")
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
