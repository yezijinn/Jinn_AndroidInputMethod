package com.jinn.inputmethod

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import kotlin.math.abs
import kotlin.math.roundToInt

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

    /**
     * 26 键区（3 行 28 键）统一外观参数，单位为像素，由 [applyKeyAppearance] 刷新。
     *
     * [keyInsetPx] 是四边各自的内缩量，等于用户设置的「间隙」的一半——相邻两键
     * 各缩一半，合起来正好是间隙宽度。大写键/删除键要用它做 LayoutParams 边距，
     * 字母键则由 [PinyinKey.setKeyAppearance] 带进自绘流程。
     */
    private var keyCornerPx = KeyAppearance.DEFAULT_CORNER_DP * dpFloat(1f)
    private var keyInsetPx = KeyAppearance.DEFAULT_GAP_DP * dpFloat(1f) / 2f

    /** 上次打印的外观参数（仅在变化时打日志，避免每次弹键盘都刷屏） */
    private var lastAppearanceDesc = ""

    /**
     * 大写键/删除键背景的构建缓存：记下上次用的（圆角, 大写锁定）与圆角。
     *
     * NaN 初值保证首次一定构建；之后参数不变就跳过，避免切层/翻页时反复分配 Drawable。
     */
    private var shiftBgCornerPx = Float.NaN
    private var shiftBgCaps: Boolean? = null
    private var backspaceBgCornerPx = Float.NaN

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

    /**
     * 「双击 → 长按」清空手势的有效窗口：双击完成后，必须在该时长内**再次按下**
     * 退格，才会挂上清空检测。
     *
     * 没有这个窗口时双击态会一直挂着：用户快速点两下退格（删两个字，高频操作）
     * 之后，哪怕过了几分钟，任何一次长按退格都会在 [longPressClearMs] 后
     * 清空整个输入框——且不可撤销。
     */
    private val clearGestureWindowMs = 500L

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (!backspaceHeld) return
            deleteOne()
            backspaceHandler.postDelayed(this, backspaceRepeatIntervalMs)
        }
    }


    /** 当前符号分组索引（候选栏标签，仅点击切换） */
    private var symbolGroupIndex = 0

    /** 当前组内的符号页偏移（左滑下一页/右滑上一页，不跨组） */
    private var symbolPageInGroup = 0

    /** 当前显示的分组（供标签高亮判断） */
    private fun currentSymbolGroup(): SymbolGroup =
        SYMBOL_GROUPS[symbolGroupIndex.coerceIn(0, SYMBOL_GROUPS.lastIndex)]

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

        // 剪贴板面板：预挂载到 contentArea（GONE），打开/关闭切 visibility + 高度。
        // 显示时 viewLetters 置 GONE、contentArea 改为固定 162dp×2 高度（详见
        // showClipboardPanel）。contentArea 是 FrameLayout，其父是 LinearLayout，
        // 改 layoutParams 时类型必须匹配，用错会 ClassCastException。
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
        for (row in KEYBOARD_ROWS) {
            for (c in row) {
                val key = root.findViewById<PinyinKey>(letterKeyId(c)) ?: continue
                key.label = c.toString()
                keyViews[c] = key
                // 用触摸监听统一处理「点击输入」与「符号层左右滑动翻页」
                key.setOnTouchListener { view, event ->
                    val consumed = handleKeyTouch(c, event)
                    // 无障碍：仅在确认是「点击」而非滑动时补 performClick，
                    // 否则滑动翻页结束时也会发出点击事件，反而误导 TalkBack。
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        abs(event.x - keyTouchStartX) < touchSlop
                    ) {
                        view.performClick()
                    }
                    consumed
                }
            }
        }
    }

    /** 字母键手势状态 */
    private var keyTouchStartX = 0f
    private var keyTouchConsumed = false

    /** 系统触摸斜率阈值：位移小于它才算「点击」而非滑动（用于无障碍 performClick 判定） */
    private val touchSlop: Float =
        android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

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
                // 本监听器返回 true 会消费掉事件，PinyinKey.onTouchEvent 不再执行，
                // 必须显式驱动按压态，否则按键没有任何视觉反馈（按压高亮是死代码）
                keyViews[c]?.setPressedVisual(true)
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
                keyViews[c]?.setPressedVisual(false)
                // 未滑动（或非符号层）且抬起点仍落在本键内才输入。
                // 命中判定不可省：手指从 Q 滑到 W 抬起时，UP 依然回调到 Q 的监听器，
                // 不做判定就会把 Q 上屏（用户看到的是自己按了 W）。
                if (!keyTouchConsumed && isInsideKey(keyViews[c], event)) {
                    onLetterPressed(c)
                }
                keyTouchConsumed = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                keyViews[c]?.setPressedVisual(false)
                keyTouchConsumed = false
                return true
            }
        }
        return false
    }

    /**
     * 抬起点是否仍落在该键范围内（防相邻键误触）。
     *
     * 用 raw 坐标比对，与 DOWN 时记录的 [MotionEvent.getRawX] 同源。
     * 边界外扩 [KEY_HIT_PADDING_DP]：贴边点击时手指常有 1~2 像素抖动，
     * 完全不放宽会让边缘键变得难点中。
     *
     * 取不到布局信息（未测量/已分离）时返回 true——保守退回「照常输入」，
     * 宁可保留旧行为，也不能因为拿不到坐标而让用户按不出字。
     */
    private fun isInsideKey(key: View?, event: MotionEvent): Boolean {
        if (key == null || key.width <= 0 || key.height <= 0) return true
        val loc = IntArray(2)
        key.getLocationOnScreen(loc)
        val pad = dpFloat(KEY_HIT_PADDING_DP)
        return event.rawX >= loc[0] - pad &&
            event.rawX <= loc[0] + key.width + pad &&
            event.rawY >= loc[1] - pad &&
            event.rawY <= loc[1] + key.height + pad
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
            // 大写锁定切换的是「字母直通」模式，与切中英文同理：
            // 不清残留拼音的话，切回小写后按退格会先删这些看不见的拼音，
            // 而用户想删的是刚打出来的大写字母。
            if (composing.isNotEmpty() || lastPredictions.isNotEmpty()) {
                composing.clear()
                lastPredictions = emptyList()
                refreshCandidateBar()
            }
            refreshKeyLabels()
            Diagnostics.i(TAG, "大写锁定: $capsMode")
        }
        btnSpace.setOnClickListener { onSpacePressed() }
        btnSpace.setOnLongClickListener {
            // 空格长按：切回语音模式（不触发短按）
            listener?.onVoiceRequested()
            true
        }
        btnBackspace.setOnTouchListener { view, event ->
            val consumed = handleBackspaceTouch(event)
            // 无障碍：抬手时补 performClick——退格键只有「点击 / 长按连删」，
            // 没有滑动手势，所以 ACTION_UP 必定对应一次真实操作结束。
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            consumed
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
        // 外观参数在这里一起重套：IME 每次输入框聚焦都会调用本方法（onStartInputView），
        // 所以在设置页改完圆角/间隙，收起键盘再弹出即生效，不必重启进程。
        // 必须先于 refreshKeyLabels——后者会重建 shift 键背景，用的是本次刷新的圆角值。
        applyKeyAppearance()
        refreshKeyLabels()
        refreshCandidateBar()
    }

    // ── 26 键区统一外观（按键圆角 / 按键间隙）──────────────────────

    /**
     * 按设置页参数套用 26 键区（3 行 28 键：10 + 9 + 大写/7 字母/删除）的统一外观。
     *
     * 这 28 个键是一条连续的键区，但绘制路径分成两类，必须在这里对齐：
     *  - 26 个字母键：[PinyinKey] 自绘圆角矩形 → 把圆角与内缩值推给它们
     *  - 大写键 / 删除键：XML 里的 ImageButton，背景由 drawable 决定 → 动态生成
     *    同圆角的背景，并把内缩等效成四周外边距
     *
     * 参数定义域与换算见 [KeyAppearance]；本方法不校验取值，[Prefs] 读取时已钳位。
     */
    private fun applyKeyAppearance() {
        val p = Prefs(context)
        val density = resources.displayMetrics.density
        keyCornerPx = p.keyCornerDp * density
        // 内缩 = 间隙的一半：相邻两键各缩一半，合起来正好是用户设置的间隙
        keyInsetPx = p.keyGapDp * density / 2f
        for (key in keyViews.values) key.setKeyAppearance(keyCornerPx, keyInsetPx)
        refreshShiftBackground()
        refreshBackspaceBackground()
        applyKeyInsets(btnShift)
        applyKeyInsets(btnBackspace)
        val desc = "圆角=${KeyAppearance.formatDp(p.keyCornerDp)} 间隙=${KeyAppearance.formatDp(p.keyGapDp)}"
        if (desc != lastAppearanceDesc) {
            lastAppearanceDesc = desc
            Diagnostics.i(TAG, "键盘外观: $desc")
        }
    }

    /**
     * 重建大写键背景：普通深色 / 大写锁定强调色二态，圆角跟设置页参数走。
     *
     * 原先直接引用 key_bg.xml / key_bg_active.xml，圆角固定 10dp，与字母键自绘的
     * 圆角对不上（同一行两种圆角），故改为运行时按同一参数生成。
     *
     * 带缓存：本方法由 [refreshKeyLabels] 间接调用（切层、翻符号页、切大小写都会走到），
     * 参数没变就不重建，避免无谓的 Drawable 分配。
     */
    private fun refreshShiftBackground() {
        if (shiftBgCornerPx == keyCornerPx && shiftBgCaps == capsMode) return
        shiftBgCornerPx = keyCornerPx
        shiftBgCaps = capsMode
        btnShift.background = buildKeyBackground(
            if (capsMode) context.getColor(R.color.accent) else context.getColor(R.color.key_bg)
        )
    }

    /** 重建删除键背景（无二态，始终深色）。同样带缓存，参数没变不重建 */
    private fun refreshBackspaceBackground() {
        if (backspaceBgCornerPx == keyCornerPx) return
        backspaceBgCornerPx = keyCornerPx
        btnBackspace.background = buildKeyBackground(context.getColor(R.color.key_bg))
    }

    /**
     * 生成与 key_bg.xml 同款（实心圆角 + 水波纹按压反馈）但圆角可调的按键背景。
     *
     * mask 必须单独再造一个**不透明**的同圆角矩形：RippleDrawable 按 mask 的 alpha
     * 裁剪波纹，拿一个无色 Drawable 当 mask 会把波纹整个裁掉（按压就没有反馈了）。
     */
    private fun buildKeyBackground(fillColor: Int): Drawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = keyCornerPx
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = keyCornerPx
        }
        return RippleDrawable(
            ColorStateList.valueOf(context.getColor(R.color.key_ripple)), content, mask,
        )
    }

    /**
     * 把字母键的「四边内缩」等效成 ImageButton 的外边距。
     *
     * 字母键的内缩画在自己的 canvas 里，普通 View 只能改外边距。不跟着改的话，
     * 大写键/删除键会贴满单元格——比字母键高一圈、左右也多出一截，一眼就错位。
     */
    private fun applyKeyInsets(view: View) {
        val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return
        val inset = keyInsetPx.roundToInt()
        if (lp.leftMargin == inset && lp.topMargin == inset &&
            lp.rightMargin == inset && lp.bottomMargin == inset
        ) {
            return
        }
        lp.setMargins(inset, inset, inset, inset)
        view.layoutParams = lp
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

    /**
     * 取出当前拼音串的「原始按键序列」并清空输入状态（供回车键输出英文用）。
     *
     * 返回的是用户**实际按下的那些键**，绝不做双拼→全拼转换：
     * 全拼按 `but` 返回 `"but"`；双拼按 `budv` 返回 `"budv"`（不是转换后的 `"budui"`）。
     * 这正是「打了几个键就输出几个英文字母」的语义。
     *
     * 清空 `lastCandidates` / `lastPredictions`，避免下一轮输入出现陈旧候选。
     */
    fun takeRawComposing(): String {
        if (composing.isEmpty()) return ""
        val raw = composing.toString()
        composing.clear()
        lastCandidates = emptyList()
        lastPredictions = emptyList()
        refreshCandidateBar()
        Diagnostics.i(TAG, "回车输出英文原文: $raw")
        return raw
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
                    searchPanel.appendSearch(DIGIT_MAP[c] ?: return)
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
                val digit = DIGIT_MAP[c] ?: return
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
                // 已处于「双击后」状态：本次长按到阈值触发清空。
                // 必须校验双击是否刚发生——双击态会一直残留，不校验时间的话，
                // 用户点两下退格（删两个字，高频操作）之后任何一次长按都会清空输入框。
                if (backspaceTapCount == 2 &&
                    System.currentTimeMillis() - backspaceLastTapAt <= clearGestureWindowMs
                ) {
                    backspaceHandler.removeCallbacks(clearOnLongPressRunnable)
                    backspaceHandler.postDelayed(clearOnLongPressRunnable, longPressClearMs)
                } else if (backspaceTapCount == 2) {
                    // 双击态已过期（过了窗口才按下）：立即失效，杜绝后续误触
                    backspaceTapCount = 0
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
                // 记录第二击时刻：清空手势的时间窗口由此起算。
                // 不更新的话双击态永远「新鲜」，长按清空会在很久以后被误触发。
                backspaceLastTapAt = now
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
        // 内容已全部清空：停掉仍在跑的连续删除。否则会以 55ms 间隔继续发 DEL，
        // 在部分宿主（如 WebView）里可能被解释成「返回」等其它动作。
        backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
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
        // 补全诊断：只在末尾存在不完整音节时记录。
        // 注意：绝不在这里再调一次 queryWithCompletion——PinyinEngine.query() 内部
        // 已经跑过补全召回，重复调用等于每次按键双倍查询，纯粹为了打日志。
        if (!shuangpinMode && result.partialSyllable.isNotEmpty()) {
            Diagnostics.v(
                TAG,
                "补全诊断: input=$queryInput syllables=${result.syllables} " +
                    "partial=${result.partialSyllable} candidates=${result.candidates.take(3)}",
            )
        }
        lastCandidates = result.candidates
        viewCandidatePinyin.text = queryInput
        Diagnostics.v(TAG, "候选: ${if (shuangpinMode) "双拼[$input]→" else ""}$queryInput → ${result.candidates.take(3)}")

        viewCandidateList.removeAllViews()
        // 只渲染前若干条：单字候选可达 MAX_CHARS(60) 条（真实单字表里 `yi` 有 326 字、
        // 93 个音节超过 60 字），而这里是「每条一个 TextView」且每次按键全量重建，
        // 一次按键创建 60 个 View 在低端机上会明显掉帧。用户实际只点最前面几个
        // （单字候选按常用度排序），因此截断渲染量。
        // 注意：这只影响渲染，[lastCandidates] 仍保存完整候选，空格/回车取首候选不受影响。
        for ((index, candidate) in result.candidates.withIndex()) {
            if (index >= MAX_RENDERED_CANDIDATES) break
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
        for ((idx, group) in SYMBOL_GROUPS.withIndex()) {
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
                        Diagnostics.i(TAG, "符号分组切换: ${group.label} (${idx + 1}/${SYMBOL_GROUPS.size})")
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
        // 与 onCandidateSelected 保持一致：搜索模式下路由到搜索框。
        // 漏掉这个分支的话，搜索态里点预测词会把文本直接提交到宿主输入框（串到聊天内容里）
        if (isPanelSearch()) {
            Diagnostics.i(TAG, "搜索预测: \"$pred\"")
            searchPanel.appendSearch(pred)
            lastPredictions = emptyList()
            refreshCandidateBar()
            return
        }
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
            // 符号层一律水平 + 垂直居中（不用字母层的小字顶置样式）
            key.centeredStyle = layer == LAYER_SYMBOL
            key.label = when (layer) {
                LAYER_SYMBOL -> currentSymbolMap()[c] ?: "" // 未映射的键显示空文本
                LAYER_DIGIT -> DIGIT_MAP[c] ?: c.toString()
                else ->
                    if (showUpper) c.uppercaseChar().toString() else c.toString()
            }
            // 双拼模式下显示自然码键位提示（字母层）；大写激活时隐藏，统一用全拼大写键盘
            val showHint = layer == LAYER_LETTER && !englishMode && shuangpinMode && !capsMode
            key.subLabel = if (showHint) shuangpinHint(c) else ""
            // u/i/v 键的 sh/ch/zh 用红色显示在下方（与韵母同区域，追加在后）
            key.subLabelRed = if (showHint) shuangpinRedHint(c) else ""
        }
        // 中英切换键：上下两行「中文 / 英文」，把当前语言那一行染成主题紫并加粗。
        // 必须用 SpannableString 做部分着色——拆成两个 TextView 会各自居中，看起来像两个按钮。
        btnLang.textSize = 12f
        btnLang.maxLines = 2
        btnLang.setLineSpacing(0f, 0.9f)
        btnLang.text = buildLangLabel()
        btnSymbol.text = if (layer == LAYER_SYMBOL) {
            // 已进入符号层，此键的作用是回到字母页 —— 用「返回」比「ABC」更直白
            context.getString(R.string.key_back)
        } else {
            context.getString(R.string.key_symbol)
        }
        btnDigit.text = if (layer == LAYER_DIGIT) {
            context.getString(R.string.key_abc)
        } else {
            context.getString(R.string.key_digit)
        }
        // 大写锁定：shift 键高亮（背景按设置页的圆角参数动态重建）
        refreshShiftBackground()
        btnShift.alpha = 1f
        // 空格键顶部小字：同步当前输入类型
        updateSpaceHint()
    }

    /**
     * 中英切换键的富文本标签。
     *
     * 上下两行「中文 / 英文」，把**当前生效的那一行**染成主题紫并加粗，另一行保持普通样式
     * ——一眼就能看出当前处于哪种输入状态。
     */
    private fun buildLangLabel(): CharSequence {
        val cn = "中文"
        val en = "英文"
        val full = "$cn\n$en"
        val active = if (englishMode) en else cn
        val start = full.indexOf(active)
        if (start < 0) return full
        return android.text.SpannableString(full).apply {
            val end = start + active.length
            setSpan(
                android.text.style.ForegroundColorSpan(
                    resources.getColor(R.color.aurora_purple, context.theme)),
                start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
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
            hint = if (shuangpinMode) "换全拼" else "换双拼",
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
        directionButtonBox = buildFunctionButton(
            label = "方向",
            hint = "控制",
            onClick = {
                if (directionPanelVisible) hideDirectionPanel() else showDirectionPanel()
            },
        )
        viewCandidateList.addView(directionButtonBox)
        // 面板可能是重建的（候选栏刷新），而方向面板状态仍为激活 —— 立即同步一次文案
        refreshDirectionButton()
        viewCandidateList.addView(buildFunctionButton(
            label = "全选",
            hint = "文本",
            onClick = { listener?.onSelectAll() },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "复制",
            hint = "编辑",
            onClick = { listener?.onCopy() },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "粘贴",
            hint = "文本",
            onClick = { listener?.onPasteClipboard() },
        ))
        viewCandidateList.addView(buildFunctionButton(
            label = "收起",
            hint = "键盘",
            onClick = { listener?.onHideKeyboard() },
        ))
        Diagnostics.v(TAG, "功能面板: ${if (shuangpinMode) "双拼" else "全拼"}/历史/方向/全选/复制/粘贴/收起")
    }

    /** 构建单个功能按钮：候选栏同高，现有键盘风格（深色圆角 + 主文字） */
    private fun buildFunctionButton(
        label: String,
        hint: String,
        labelColorRes: Int = 0,
        onClick: () -> Unit,
    ): View {
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
            setTextColor(
                if (labelColorRes != 0) resources.getColor(labelColorRes, context.theme)
                else resources.getColor(R.color.text_primary, context.theme)
            )
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

    /**
     * 就地更新「方向」按钮的文案与颜色。
     *
     * 未进入方向面板时显示「方向 / 控制」；进入后改为红色粗体「返回 / 退出控制」，
     * 让用户一眼看到退出口（该按钮此时的作用就是关闭面板）。
     * 只改两个 TextView 的文本与颜色，不重建整个功能面板。
     */
    private fun refreshDirectionButton() {
        val box = directionButtonBox as? android.view.ViewGroup ?: return
        val active = directionPanelVisible
        val labelView = box.getChildAt(0) as? TextView ?: return
        val hintView = box.getChildAt(1) as? TextView
        labelView.text = if (active) "返回" else "方向"
        labelView.setTextColor(
            resources.getColor(
                if (active) R.color.kb_key_hint_red else R.color.text_primary,
                context.theme,
            )
        )
        labelView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        hintView?.text = "控制"
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

    /**
     * 候选栏「方向」按钮的引用。
     *
     * 该按钮本身是开关（进入/退出方向面板），但用户进入后往往找不到退出口，
     * 所以进入时把它改成红色粗体的「返回」提示。这里保存引用以便就地更新文案，
     * 不必重建整个功能面板。
     */
    private var directionButtonBox: View? = null

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
        // 与剪贴板面板互斥（反向也要做，showClipboardPanel 里已有对称处理）：
        // 剪贴板打开时 viewLetters 整体是 GONE，方向面板加进去根本看不见，
        // 而 directionPanelVisible 已置 true —— 用户点方向键毫无反应。
        if (clipboardActive) hideClipboardPanel()
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
        refreshDirectionButton()
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
        refreshDirectionButton()
        Diagnostics.i(TAG, "方向面板: 隐藏，恢复字母键盘")
    }

    // ── 键盘内剪贴板面板（占内容区，候选栏/底部栏保持）────────────

    /** 当前是否处于剪贴板面板模式（字母区被替换） */
    fun isClipboardActive(): Boolean = clipboardActive

    /**
     * 显示剪贴板面板：候选栏与底部功能行固定不动，面板占用二者之间全部空间。
     *
     * 布局机制（多轮真机验证结论；旧注释曾与实现完全相反，勿再照抄）：
     * - **会修改 layoutParams**：contentArea 被改成固定高度 162dp×2（约 850px），
     *   面板 MATCH_PARENT 填满它。固定高度让面板不受 IME 窗口初始测量影响（冷启动稳定）。
     * - viewLetters 实际用 **GONE** 而非 INVISIBLE：contentArea 已是固定高度，
     *   GONE 不会导致塌缩。（旧注释写的「INVISIBLE + 零 layoutParams 修改」
     *   与实现相反，已更正。）
     * - layoutParams 类型必须匹配父容器：contentArea 的父是 LinearLayout
     *   （PinyinKeyboardView 根），面板的父 contentArea 是 FrameLayout。用错
     *   FrameLayout.LayoutParams 会 ClassCastException，曾导致键盘收起循环。
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
            // 异常回滚：上面的代码可能已经隐藏了字母区、显示了面板。
            // 不恢复的话，hideClipboardPanel 又会因 clipboardActive 仍为 false 直接 return，
            // 26 键就永久消失了（只能重启 IME 才能恢复）。
            runCatching { restoreLettersLayout() }
                .onFailure { Diagnostics.e(TAG, "showClipboardPanel 回滚失败: ${it.message}") }
            clipboardActive = false
            listener?.onClipboardStateChanged(false)
        }
    }

    /**
     * 恢复 26 键字母区布局（关闭面板与异常回滚共用）。
     *
     * contentArea 的父容器是 LinearLayout，layoutParams 必须匹配，
     * 用 FrameLayout.LayoutParams 会 ClassCastException。
     */
    private fun restoreLettersLayout() {
        viewLetters.visibility = View.VISIBLE
        clipboardPanel.visibility = View.GONE
        contentArea.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /** 关闭剪贴板面板，恢复 26 键字母布局（contentArea 恢复字母区高度） */
    fun hideClipboardPanel() {
        if (!clipboardActive) return
        restoreLettersLayout()
        clipboardActive = false
        listener?.onClipboardStateChanged(false)
        Diagnostics.i(TAG, "剪贴板面板: 隐藏，恢复字母键盘")
    }

    // ── 顶部搜索面板（挂在根布局候选栏上方）────────────────

    /** 是否处于顶部搜索模式 */
    fun isSearchActive(): Boolean = searchPanel.isActive()

    /**
     * 清空拼音输入缓冲与候选 / 预测 / 已上屏词残留。
     *
     * 进入或退出搜索模式时必须调用：残留的拼音串**虽不可见却仍然生效**——
     * 按退格会先删这些看不见的拼音（要按 N 次才轮到搜索框），
     * 按空格则会把上一次的候选词直接塞进搜索框。
     */
    private fun clearComposingState() {
        if (composing.isEmpty() && lastCandidates.isEmpty() &&
            lastPredictions.isEmpty() && lastCommittedWord.isEmpty()
        ) return
        composing.clear()
        lastCandidates = emptyList()
        lastPredictions = emptyList()
        lastCommittedWord = ""
        refreshCandidateBar()
    }

    /** 显示顶部搜索面板：候选栏上方整体高度增加，下方 26 键恢复为可用输入 */
    fun showSearchPanel() {
        if (searchPanel.isActive()) return
        clearComposingState()
        searchPanel.visibility = View.VISIBLE
        searchPanel.onShown()
        Diagnostics.i(TAG, "顶部搜索面板: 显示（IME 高度增高）")
    }

    /** 隐藏顶部搜索面板，恢复正常 26 键键盘 */
    fun hideSearchPanel() {
        if (!searchPanel.isActive()) return
        clearComposingState()
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

        /**
         * 候选栏最多渲染多少个候选条目。
         *
         * 候选数由引擎的 MAX_CHARS(60) 决定上限，而真实单字表里 `yi` 有 326 字、
         * 93 个音节超过 60 字 —— 即常用音节经常给出满额候选。渲染是「每条一个
         * TextView」且每次按键全量重建，60 个 View 的创建在低端机上会明显掉帧。
         * 用户实际只点最前面几个（单字候选已按常用度排序），故截断渲染量。
         * 仅影响渲染，不影响 [lastCandidates] 中保存的完整候选与上屏行为。
         */
        const val MAX_RENDERED_CANDIDATES = 24

        /** 按键命中判定的边界外扩（dp）：贴边点击时手指会有小幅抖动 */
        const val KEY_HIT_PADDING_DP = 8f

        const val LAYER_LETTER = 0
        const val LAYER_SYMBOL = 1
        const val LAYER_DIGIT = 2
    }
}
