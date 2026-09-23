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

    /**
     * 当前输入框是否应暂停用户词频学习（密码框 / 显式不联想的框）。
     *
     * 由 IME 在每次 [android.inputmethodservice.InputMethodService.onStartInputView]
     * 时按 EditorInfo 重设；跨线程读写（主线程输入 + 后台无读取），故用 @Volatile。
     * 判据见 [InputFieldPrivacy.suppressLearning]。
     */
    @Volatile
    private var suppressLearning = false

    /** 符号层 / 数字层 / 字母层 */
    private var layer = LAYER_LETTER

    /** 当前输入方案（全拼 / 自然码 / 小鹤 / 搜狗 / 微软 / 紫光 / ABC / 加加），来自设置 */
    private var scheme: ShuangpinScheme = ShuangpinScheme.QUANPIN

    /** 是否双拼方案（派生值：键位/提示/文案都由 [scheme] 决定） */
    private val shuangpinMode: Boolean get() = scheme.isShuangpin

    private val viewCandidatePinyin: TextView
    private val viewCandidateList: LinearLayout

    /** 候选栏最右侧的「✕」清空候选按钮（2026-09-20 起，绑定见 init） */
    private val btnClearCandidates: TextView

    private val viewLetters: LinearLayout
    private val contentArea: FrameLayout

    /** 剪贴板面板（与字母区互斥显示，见 init 挂载） */
    private val clipboardPanel: ClipboardPanelView

    /** 顶部搜索面板（候选栏上方，见 init 挂载） */
    private val searchPanel: SearchPanelView

    /** 剪贴板面板是否激活 */
    private var clipboardActive = false
    private val btnSymbol: TextView
    private val btnDigit: TextView
    private val btnLang: TextView
    private val btnSpace: View
    /** 空格键顶部的小字提示（当前输入类型：小写英文/大写英文/中文全拼/中文双拼） */
    private val btnSpaceHint: TextView
    private val btnBackspace: View
    private val btnEnter: View
    private val btnShift: ImageButton
    private val btnComma: View
    private val btnPeriod: View

    /**
     * 分号键（第三行 m 右侧）。
     *
     * 键盘只有 26 个字母键，而搜狗/微软/紫光三套方案的 `ing` 落在分号上，没有它，
     * 应/听/明/定 这类音节的字完全打不出来。只在当前方案确实需要时才显示，
     * 其余方案与符号层/数字层一律 GONE，对现有 26 键布局零影响。
     */
    private val keySemicolon: PinyinKey

    private val keyViews = HashMap<Char, PinyinKey>()

    /**
     * 26 键区（3 行 28 键）统一外观参数，单位为像素，由 [applyKeyAppearance] 刷新。
     *
     * [keyInsetPx] 是四边各自的内缩量，等于用户设置的「间隙」的一半，相邻两键
     * 各缩一半，合起来正好是间隙宽度。大写键/删除键要用它做 LayoutParams 边距，
     * 字母键则由 [PinyinKey.setKeyAppearance] 带进自绘流程。
     */
    private var keyCornerPx = KeyAppearance.DEFAULT_CORNER_DP * dpFloat(1f)
    private var keyInsetPx = KeyAppearance.DEFAULT_GAP_DP * dpFloat(1f) / 2f

    /** 上次打印的外观参数（仅在变化时打日志，避免每次弹键盘都刷屏） */
    private var lastAppearanceDesc = ""

    /** 半透明键盘：键盘底色所在的根布局（keyboard_pinyin.xml 的根，背景 @color/kb_bg） */
    private val keyboardRoot: View

    /** 半透明键盘：候选栏容器（背景 @color/kb_candidate_bg） */
    private val candidateBar: View

    /** 当前键面不透明度（1f = 不透明），见 [applyKeyTransparency]；功能键背景的缓存判据也用它 */
    private var keyFaceAlpha = 1f

    /**
     * 背板当前档位（[applyKeyTransparency] 里随 [keyFaceAlpha] 一起更新；供候选栏选档复用）。
     *
     * 必须声明在 `init` 之前：Kotlin 属性按声明顺序初始化，而 `init` 里会调用
     * [applyKeyTransparency] 写入该值；声明在 `init` 之后会被属性初始化器重置回 `1f`，
     * 只靠当次会话再跑一遍 `configure()` 掩盖（2026-09-23 审查发现的隐患写法）。
     */
    private var plateFaceAlpha = 1f

    /** 上次打印的透明度（仅在变化时打日志，避免每次弹键盘都刷屏） */
    private var lastTransparencyDesc = ""

    /** 候选栏底当前生效的档位（NaN 保证首次一定设置），判据与更新见 [updateCandidateBarBackground] */
    private var candidateBarAlpha = Float.NaN

    /** 底部功能行背景的构建缓存：记下上次用过的面不透明度（NaN 保证首次一定构建） */
    private var functionBgAlpha = Float.NaN

    /**
     * 大写键/删除键背景的构建缓存：记下上次用过的（圆角, 大写锁定, 面不透明度）组合。
     * NaN 初值保证首次一定构建；之后参数不变就跳过，避免切层/翻页时反复分配 Drawable。
     */
    private var shiftBgCornerPx = Float.NaN
    private var shiftBgCaps: Boolean? = null
    private var shiftBgAlpha = Float.NaN
    private var backspaceBgCornerPx = Float.NaN
    private var backspaceBgAlpha = Float.NaN

    /**
     * 当前键盘皮肤（见 [KeyboardSkins]；默认皮肤 = 全空覆盖，走历史 `R.color` 路径）。
     *
     * 皮肤只决定键面 / 功能键 / 候选栏 / 背板的基色与质感参数，面 alpha 仍由
     * [applyKeyTransparency] 统一注入；两者在 [applySkinToKeys] 里合成到每个键。
     */
    private var skin: KeyboardSkin = KeyboardSkins.DEFAULT

    /** 候选缓存：空格取第一个 */
    private var lastCandidates: List<String> = emptyList()

    /** 智能预测缓存：选中词后 PinyinEngine.predict 的结果 */
    private var lastPredictions: List<String> = emptyList()

    /** 上次上屏的词：退格回到预测态时用它重新查询预测 */
    private var lastCommittedWord: String = ""

    // ── 删除键三态：单击删一个 / 按住连续删 / 双击+长按清空输入框 ──
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

    /** 双击窗口：两次短按间隔小于该值视为双击（双击后长按触发清空输入框） */
    private val doubleTapWindowMs = 280L

    /** 双击后第三次按住超过该时长触发全部清空（长按确认，杜绝误触） */
    private val longPressClearMs = 800L

    /**
     * 「双击 → 长按」清空手势的有效窗口：双击完成后，必须在该时长内再次按下
     * 退格，才会挂上清空检测。
     *
     * 没有这个窗口时双击态会一直挂着：用户快速点两下退格（删两个字，高频操作）
     * 之后，哪怕过了几分钟，任何一次长按退格都会在 [longPressClearMs] 后
     * 清空整个输入框，且不可撤销。
     */
    private val clearGestureWindowMs = 500L

    /** 方向面板的识别标记：恢复字母区时按它清理残留面板（不依赖子视图下标） */
    private val DIRECTION_PANEL_TAG = "jinn_direction_panel"

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (!backspaceHeld) return
            // 标准的逐字连删：拼音删空后继续删已上屏正文，直到松手。
            // （2026-09-20 起原先「按住 ≥1.2s 且拼音 ≥12 字符就整串清空」的捷径已按用户要求
            //  移除，清空候选改由候选栏右侧 ✕ 按钮显式触发，见 [btnClearCandidates]；
            //  「要连输入框一起清」仍是 [clearOnLongPressRunnable] 的「双击 + 长按」手势。）
            deleteOne()
            backspaceHandler.postDelayed(this, backspaceRepeatIntervalMs)
        }
    }


    /** 当前符号分组索引（候选栏标签，仅点击切换）， 语义是在 [symbolGroups] 中的位置 */
    private var symbolGroupIndex = 0

    /**
     * 分组顺序：用户在排序页调整（持久化在 [Prefs.symbolGroupOrder]），收藏内容在 [Prefs.favoriteSymbols]。
     *
     * 视图里的所有分组索引都指这里的下标，顺序/内容变化通过重建键盘视图生效，不碰 [SYMBOL_GROUPS]。
     */
    private var symbolGroups: List<SymbolGroup> = run {
        val prefs = Prefs(context)
        SymbolOrder.groupsInOrder(
            prefs.symbolGroupOrder,
            favoriteGroup(FavoriteSymbols.parse(prefs.favoriteSymbols)),
        )
    }

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
        // 必须显式 `attachToRoot = false`：attachToRoot=true 时 inflate 返回的是调用方 this
        // （不是 XML 根），直接拿它当背板引用会把背板铺成两层：视图自身一层 + XML 根一层；
        // 两层同 alpha 叠加令背板等效不透明度翻倍（真机实测 85,86,90 = 0.2×237 + 0.8×(0.2×237)，
        // 单层应只有 47；屏幕最底那条没有按键覆盖的背板带最明显）。
        // 这里显式 addView 并保存返回的 XML 根：语义清晰，也不依赖「根布局不能改成 <merge>」这类隐含前提。
        val root = LayoutInflater.from(context).inflate(R.layout.keyboard_pinyin, this, false)
        addView(root)
        keyboardRoot = root
        candidateBar = root.findViewById(R.id.candidate_bar)

        viewCandidatePinyin = root.findViewById(R.id.candidate_pinyin)
        viewCandidateList = root.findViewById(R.id.candidate_list)
        btnClearCandidates = root.findViewById(R.id.btn_clear_candidates)
        // 清空候选：完全清掉拼音串 / 候选 / 预测，候选栏回到默认的 6 按钮功能面板。
        // （2026-09-20 起取代「长按退格整串清空拼音」的隐式手势；✕ 仅在候选/预测/拼音串
        //  展示时可见，功能面板与符号层为 GONE，见 refreshCandidateBar 与 renderFunctionPanel。）
        btnClearCandidates.setOnClickListener {
            Diagnostics.i(
                TAG,
                "候选栏 ✕：清空候选（拼音=${composing.length} 候选=${lastCandidates.size} " +
                    "预测=${lastPredictions.size}）",
            )
            clearComposingState()
        }
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
        keySemicolon = root.findViewById(R.id.key_semicolon)
        keySemicolon.label = SEMICOLON_KEY.toString()
        keySemicolon.setOnTouchListener { view, event ->
            val consumed = handleSemicolonTouch(event)
            // 无障碍：抬手时补 performClick（与字母键同款处理）
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            consumed
        }

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
        // 半透明键盘：首帧就按用户设置的面透明度绘制（不必等到第一次 configure）
        applyKeyTransparency()
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
                    // 必须同用 rawX：keyTouchStartX 记录的是 rawX，拿 event.x（键内坐标）
                    // 相减值约等于该键的屏幕左偏移，恒大于 touchSlop，除最左一列外永不触发
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        abs(event.rawX - keyTouchStartX) < touchSlop
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
                // V 级埋点（不落盘）：排查"整块键失灵"时，用它区分
                // 「触摸压根没送到键上（被上层吃掉）」与「送到了但抬起判定失败」
                Diagnostics.v(TAG, "键触摸 DOWN: $c")
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
            // 多指：第二根手指按在同一键上是 POINTER_DOWN，先抬起的那根是 POINTER_UP。
            // 两者都不处理的话，POINTER_UP 分支既不复位按压态也不上字，键会一直高亮
            // 到下次被触摸，且该次输入被静默丢掉。
            MotionEvent.ACTION_POINTER_DOWN -> {
                keyViews[c]?.setPressedVisual(true)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                keyViews[c]?.setPressedVisual(false)
                // 不复位 keyTouchConsumed：它是「本次手势已经做过事（在符号层翻了页）、
                // 抬起时不许上字」的判据，而第二根手指抬起时手势还没结束，复位后剩下的
                // 那根手指一抬就会把新页上的符号上屏（用户只想翻页）。该标记在
                // ACTION_DOWN / ACTION_UP / ACTION_CANCEL 处复位，生命周期足够。
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
     * 几何判定抽到文件级 [isInsideKeyBounds]（纯函数、可 JVM 单测）；
     * 这里只负责取视图坐标与 dp 换算。
     */
    private fun isInsideKey(key: View?, event: MotionEvent): Boolean {
        if (key == null) return true
        val loc = IntArray(2)
        key.getLocationOnScreen(loc)
        return isInsideKeyBounds(
            rawX = event.rawX,
            rawY = event.rawY,
            left = loc[0],
            top = loc[1],
            width = key.width,
            height = key.height,
            pad = dpFloat(KEY_HIT_PADDING_DP),
        )
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
            // 符号层键面全是符号，没有大小写概念：大写键在此层无效（用户要求）
            if (layer == LAYER_SYMBOL) {
                Diagnostics.i(TAG, "符号层: 大写键无操作")
                return@setOnClickListener
            }
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
            // 无障碍：抬手时补 performClick，退格键只有「点击 / 长按连删」，
            // 没有滑动手势，所以 ACTION_UP 必定对应一次真实操作结束。
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            consumed
        }
        // 搜索模式：这三个键必须只作用于搜索框，否则回车会把原始拼音串或宿主动作
        // （输入框声明的是「发送」时就直接把消息发出去）落到宿主，逗号句号把全角标点写进
        // 用户正在编辑的正文里。搜索态下回车等价于面板自己的「退出搜索」按钮，不碰宿主。
        btnEnter.setOnClickListener {
            if (isPanelSearch()) {
                hideSearchPanel()
                return@setOnClickListener
            }
            listener?.onEnter()
        }
        // 逗号/句号：英文模式上 ASCII，中文模式上全角
        btnComma.setOnClickListener {
            val text = if (englishMode) "," else "，"
            if (isPanelSearch()) searchPanel.appendSearch(text) else listener?.onCommitText(text)
        }
        btnPeriod.setOnClickListener {
            val text = if (englishMode) "." else "。"
            if (isPanelSearch()) searchPanel.appendSearch(text) else listener?.onCommitText(text)
        }
    }

    // ── 对外控制 ───────────────────────────────────────────

    /** 当前是否英文模式（供 IME 每次聚焦时同步，避免覆盖用户手动切换） */
    fun isEnglishMode(): Boolean = englishMode

    /**
     * 设置「敏感输入框」标记：为 true 时不再把用户选过的候选写进用户词频。
     *
     * 密码框 / 声明 NO_SUGGESTIONS 的框里敲进去的东西属于凭据片段，学进
     * `user_freq.txt` 会变成长期明文，且在之后的普通输入框里被优先推荐出来。
     */
    fun setSuppressLearning(suppress: Boolean) {
        suppressLearning = suppress
    }

    /**
     * 学习「用户明确选过」的候选（点候选 / 空格首候选 / 预测词）。
     *
     * 敏感输入框直接跳过：输入行为与候选展示都不变，只是不落词频。
     */
    private fun learnChoice(word: String) {
        if (suppressLearning) return
        PinyinEngine.rememberChoice(word)
    }

    /** 设置输入方案（全拼 / 七种双拼）与初始中英文状态 */
    fun configure(scheme: ShuangpinScheme, english: Boolean) {
        this.scheme = scheme
        englishMode = english
        // 方案切换时清掉残留的拼音与预测
        composing.clear()
        lastPredictions = emptyList()
        // 外观参数在这里一起重套：IME 每次输入框聚焦都会调用本方法（onStartInputView），
        // 所以在键盘外观页（设置页 →「按钮圆角间隙」）改完圆角/间隙/透明度，收起键盘再弹出即生效，不必重启进程。
        // 键盘正显示时不走这里，外观页松手会直接调 [refreshAppearance]（见 JinnIme.onKeyAppearanceChanged）。
        // 顺序固定：① 同步皮肤（换皮肤时复位各「面」缓存）→ ② 定面透明度（用皮肤色重建各面）
        // → ③ 逐键下发皮肤视觉（依赖 ② 算出的面 alpha）→ ④ 套圆角/间隙。
        // ④ 会重建 shift/删除键背景，放在 ② 之后可命中其 alpha 缓存，不重复构建。
        syncKeyboardSkin()
        applyKeyTransparency()
        applySkinToKeys()
        // 必须先于 refreshKeyLabels，后者会重建 shift 键背景，用的是本次刷新的圆角值。
        applyKeyAppearance()
        refreshKeyLabels()
        refreshCandidateBar()
    }

    /**
     * 键盘外观页松手后即时重套外观（透明度 / 圆角 / 间隙）。
     *
     * 与 [configure] 的区别：只重套外观，不动拼音串与候选/预测 ，
     * 改外观不该把用户正在打的字吞掉（configure 会清 composing 与预测）。
     */
    fun refreshAppearance() {
        syncKeyboardSkin()
        applyKeyTransparency()
        applySkinToKeys()
        applyKeyAppearance()
        // 候选栏里「已构建」的面（6 个功能按钮 / 符号分组标签 / 候选词容器）读的是构建时刻的
        // alpha，不重建就保持旧值，拖滑杆松手后会「只生效一半」（真机实测：候选栏底已透、
        // 6 个按钮仍是旧档）。方向面板展开态时跳过：重建会把它收回默认布局，
        // 其内部面的 alpha 由下次打开时取当前值。
        if (!directionPanelVisible) refreshCandidateBar()
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
        keySemicolon.setKeyAppearance(keyCornerPx, keyInsetPx)
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
     * 半透明键盘：把用户设置的透明度套到键盘与内嵌面板的各层「面」（定义域见 [KeyTransparency]）。
     *
     *  - 背板类（键盘底色 kb_bg、面板底 app_bg）：面上没有文字，走 plate 档，可以做得最透；
     *  - 内容面类（条目卡 card_bg、搜索框底 surface_hi）：带文字/内容，走 surface 档；
     *  - 候选栏底按当前是否有内容选档（见 [updateCandidateBarBackground]）；
     *  - 键面与面板里的按钮同上走 surface 档（键面走 [PinyinKey.setFaceAlpha]，面板按钮走各自
     *    面板的 `applySurfaceAlpha`，它们的背景是 drawable，颜色识别扫不到）；
     *  - 文字一律不变：这里只给「面」的颜色套 alpha（[KeyTransparency.withAlpha]），本功能的
     *    绘制路径不使用整键 `View.setAlpha`，那会把文字一起淡掉
     *    （例外：符号层禁用态的大写键用整键 alpha 置灰，属与透明度无关的既有视觉，见 [refreshKeyLabels]）。
     *
     * 0%（默认）时两档 alpha 都是 1f，与历史观感逐像素一致。
     */
    private fun applyKeyTransparency() {
        val percent = Prefs(context).keyTransparencyPercent
        keyFaceAlpha = KeyTransparency.surfaceAlpha(percent)
        val plateAlpha = KeyTransparency.plateAlpha(percent)
        plateFaceAlpha = plateAlpha

        // 背板只铺一层：[keyboardRoot] 必须是 XML 根（见 init 的说明，attachToRoot=true 时 inflate
        // 返回的是 this）。两层同 alpha 叠加会令背板等效不透明度翻倍（80% → 等效 36%），屏幕最底那条
        // 没有按键覆盖的背板带最明显（真机实测 85,86,90 = 0.2×237 + 0.8×47，单层应只有 47）。
        keyboardRoot.setBackgroundColor(
            KeyTransparency.withAlpha(skinToken(skin.plate, R.color.kb_bg), plateAlpha)
        )
        updateCandidateBarBackground()
        // 兜底：本视图树（含剪贴板 / 搜索面板）里所有「纯色面」统一按档位套 alpha ，
        // 面可能有多份（XML 根 / 各层容器 / 面板底 / 条目卡 / 搜索框），逐个引用容易漏；
        // 旧版只认「RGB == kb_bg」一种色，导致两个面板整块实心（与透明键盘形成割裂）。
        // 皮肤会改背板/键面的色值：皮肤色一并并入识别色集，否则这些面匹配不到、透明度对它们失效。
        alphaFaces(
            this,
            // 候选栏色（skin.candidateBar）**不并入**背板集：它的档位随内容切换（有候选走 surface、
            // 空白走 plate），由 updateCandidateBarBackground 专管；并进来会被统一压到 plate 档
            // （100% 时候选词直接叠在宿主内容上），且其档位缓存会因此不再更新、要清空一次内容才自愈。
            plateRgb = faceRgb(
                context.getColor(R.color.kb_bg), context.getColor(R.color.app_bg),
                skin.plate,
            ),
            surfaceRgb = faceRgb(
                context.getColor(R.color.card_bg), context.getColor(R.color.surface_hi),
                skin.functionFill,
            ),
            plateAlpha = plateAlpha,
            surfaceAlpha = keyFaceAlpha,
        )
        for (key in keyViews.values) key.setFaceAlpha(keyFaceAlpha)
        keySemicolon.setFaceAlpha(keyFaceAlpha)
        // 功能键面：shift/删除键走既有动态背景（alpha 已并进其构建缓存），其余六个重建一次
        refreshShiftBackground()
        refreshBackspaceBackground()
        rebuildFunctionKeyBackgrounds()
        // 面板里的「键面」按钮（key_bg 是 drawable，颜色识别扫不到）：交给面板按当前档重建
        clipboardPanel.applySurfaceAlpha(keyFaceAlpha)
        searchPanel.applySurfaceAlpha(keyFaceAlpha)

        // 诊断（排查「透明度不生效」）：只在档位真正变化时打印一次并做树扫描 ，
        // 原实现每次弹键盘都打日志、并 postDelayed 扫一遍全树（500ms 后），纯属刷屏与白扫。
        val desc = KeyTransparency.formatPercent(percent)
        if (desc != lastTransparencyDesc) {
            lastTransparencyDesc = desc
            val kbBg = context.getColor(R.color.kb_bg)
            val rootBg = (keyboardRoot.background as? android.graphics.drawable.ColorDrawable)?.color
            Diagnostics.i(
                TAG,
                "键盘透明度: $desc plate=$plateAlpha surface=$keyFaceAlpha " +
                    "kbBg=${String.format("#%08X", kbBg)} " +
                    "rootBg=${rootBg?.let { String.format("#%08X", it) }}",
            )
            // 布局完成后再诊断（否则尺寸全是 0）
            postDelayed({ logBackgrounds() }, 500L)
        }
    }

    /**
     * ① 同步键盘皮肤并在皮肤变化时复位各「面」的构建缓存。
     *
     * 必须在 [applyKeyTransparency] **之前**调用：功能键 / 大写键 / 删除键 / 候选栏底的背景
     * 都是带缓存的（参数不变就跳过重建），缓存判据只含 alpha 与圆角，不含皮肤；
     * 皮肤换色时先复位缓存，随后的 [applyKeyTransparency] 才会用新皮肤色重建它们。
     */
    private fun syncKeyboardSkin() {
        val s = KeyboardSkins.byId(Prefs(context).keyboardSkinId)
        if (s.id == skin.id) return
        val from = skin.id
        skin = s
        functionBgAlpha = Float.NaN
        shiftBgAlpha = Float.NaN
        backspaceBgAlpha = Float.NaN
        candidateBarAlpha = Float.NaN
        // 面板（剪贴板历史 / 搜索）与键盘同属一层皮肤：一并换色。必须在这里（早于
        // applyKeyTransparency）下发，面板的纯色面才会被本轮的透明度套档扫到。
        clipboardPanel.applySkin(s)
        searchPanel.applySkin(s)
        Diagnostics.i(TAG, "键盘皮肤: $from -> ${s.id}（${s.label}）")
    }

    /**
     * ③ 把皮肤视觉逐键下发（必须在 [applyKeyTransparency] **之后**：最终色 = 皮肤基色 × 面不透明度）。
     *
     * 默认皮肤下发 null，键面回到历史路径（`R.color.kb_key` + [PinyinKey.setFaceAlpha]），
     * 与半透明键盘的既有实测观感逐像素一致。
     */
    private fun applySkinToKeys() {
        // 26 键按字母序取索引：彩虹皮肤按序取色相，而 keyViews 是 HashMap，遍历顺序不稳定
        val ordered = ('a'..'z').mapNotNull { keyViews[it] }
        if (skin.isDefault) {
            // 默认皮肤：键面走 R.color 令牌路径，功能键文字 / 图标回落到主题令牌
            for (k in ordered) k.applySkin(null)
            keySemicolon.applySkin(null)
        } else {
            val density = resources.displayMetrics.density
            val face = context.getColor(R.color.kb_key)
            val pressed = context.getColor(R.color.kb_key_pressed)
            val glyph = context.getColor(R.color.kb_key_text)
            val hintDefault = KeyTransparency.withAlpha(glyph, 160f / 255f)
            val hintRed = context.getColor(R.color.kb_key_hint_red)
            for ((i, k) in ordered.withIndex()) {
                k.applySkin(
                    KeyboardSkins.visualFor(
                        skin, i, keyFaceAlpha, density, face, pressed, glyph, hintDefault, hintRed,
                    )
                )
            }
            // 分号键（搜狗 / 微软 / 紫光的 ing）不参与彩虹色序，取起始色相
            keySemicolon.applySkin(
                KeyboardSkins.visualFor(
                    skin, -1, keyFaceAlpha, density, face, pressed, glyph, hintDefault, hintRed,
                )
            )
        }
        applySkinToTexts()
    }

    /**
     * 把皮肤的「功能键文字 / 图标色」套到键盘内的静态控件上。
     *
     * 功能键的「面」由皮肤接管（见 [applyKeyTransparency]），文字与图标必须同源：
     * 亮白主题下 `text_primary` 是深色，压在皮肤的深色功能键上会撞色（真机实测：
     * 磨砂 / 极光皮肤下空格、回车、大写、删除、候选栏按钮文字不可读）。
     *
     * 默认皮肤的两个覆盖为 null ⇒ 回落到 `text_primary` / `text_secondary`，与历史一致。
     * 动态构建的部分（候选词 / 预测词 / 符号分组标签 / 候选项 / 候选栏按钮 / 方向键）
     * 在各 render / build 方法里读同一组皮肤色，见 [KeyboardSkins.functionGlyph]。
     */
    private fun applySkinToTexts() {
        val glyph = skinToken(skin.functionGlyph, R.color.text_primary)
        val hint = skinToken(skin.functionHint, R.color.text_secondary)
        // 空格键：主文字「空格」+ 顶部输入类型小字
        (btnSpace as? ViewGroup)?.let { space ->
            (space.getChildAt(0) as? TextView)?.setTextColor(hint)
            (space.getChildAt(1) as? TextView)?.setTextColor(glyph)
        }
        btnSpaceHint.setTextColor(hint)
        // 候选栏里的拼音回显与「✕ 清空候选」：候选栏底已随皮肤变深 / 变浅，这两处必须同源
        // （2026-09-23 审查发现的遗漏：二者在布局里写死 text_secondary，深色皮肤下几乎看不见）
        viewCandidatePinyin.setTextColor(hint)
        btnClearCandidates.setTextColor(hint)
        // 图标键：回车 / 删除（布局里是 ImageButton，字段声明为 View，故用 as?）；
        // 大写键单独处理 —— 锁定态底色会换成强调色，图标色需随之切换（见 [refreshShiftTint]）
        for (v in listOf(btnEnter, btnBackspace)) {
            (v as? ImageButton)?.imageTintList = ColorStateList.valueOf(glyph)
        }
        refreshShiftTint()
        // 底部次级功能键（符号 / 数字 / 逗号 / 句号 / 中英）：面随皮肤变深 / 变浅（见
        // rebuildFunctionKeyBackgrounds），文字必须同源，否则默认的深色字会落在皮肤的深色面上
        for (v in listOf(btnSymbol, btnDigit, btnComma, btnPeriod, btnLang)) {
            (v as? TextView)?.setTextColor(glyph)
        }
        // 方向按钮：未激活态用皮肤主文字色；激活态用皮肤的红色提示色（见 refreshDirectionButton）
        refreshDirectionButton()
    }

    /**
     * 大写键图标色：普通态用皮肤主文字色；大写锁定态底是皮肤的强调色（各皮肤从深蓝到浅银都有），
     * 图标改用由强调色亮度推出的对比色 —— 否则浅色 accent 上的近白图标几乎看不见
     * （2026-09-23 审查实测：钛金约 1.5:1、极光约 2.3:1）。
     */
    private fun refreshShiftTint() {
        val tint = if (capsMode) {
            KeyboardSkins.accentOnColor(skinToken(skin.accent, R.color.accent))
        } else {
            skinToken(skin.functionGlyph, R.color.text_primary)
        }
        btnShift.imageTintList = ColorStateList.valueOf(tint)
    }

    /**
     * 皮肤色令牌：皮肤未覆盖（null）时回退到 `R.color` 令牌色。
     *
     * 默认皮肤与「未覆盖项」都走这个回退，保证皮肤机制不改变历史配色。
     * 面板（剪贴板 / 搜索）复用文件级的 [skinColor]。
     */
    private fun skinToken(override: Int?, tokenRes: Int): Int = skinColor(context, override, tokenRes)

    /** 组装「面」的 RGB 识别色集（默认令牌 + 可选皮肤覆盖色，null 项跳过），供 [alphaFaces] 匹配用 */
    private fun faceRgb(vararg colors: Int?): IntArray {
        val out = LinkedHashSet<Int>()
        for (c in colors) c?.let { out += it }
        return out.toIntArray()
    }

    /**
     * 候选栏底色按当前是否有内容选档：
     *  - 有拼音串 / 候选 / 预测时：面上叠着文字（候选词没有自己的面）→ 必须走 surface 档，
     *    否则文字会直接糊在宿主内容上（与 [KeyTransparency] 的可读性约定冲突）；
     *  - 空白铺底（功能面板 / 符号层 / 搜索态，文字都在按钮自己的面上）→ 走 plate 档，可做最透。
     *
     * 带缓存：档位没变就不重设，避免每次按键都白重绘一次候选栏；
     * 档位取自 [plateFaceAlpha] / [keyFaceAlpha]，不读 Prefs，本函数挂在按键热路径上。
     */
    private fun updateCandidateBarBackground() {
        val hasContent = composing.isNotEmpty() || lastCandidates.isNotEmpty() || lastPredictions.isNotEmpty()
        val alpha = if (hasContent) keyFaceAlpha else plateFaceAlpha
        if (alpha == candidateBarAlpha) return
        candidateBarAlpha = alpha
        candidateBar.setBackgroundColor(
            KeyTransparency.withAlpha(skinToken(skin.candidateBar, R.color.kb_candidate_bg), alpha)
        )
    }

    /**
     * 把 [v] 及其整棵子树里所有「纯色面」按档位套 alpha：
     *  - RGB ∈ [plateRgb]（背板类：无文字）→ [plateAlpha]；
     *  - RGB ∈ [surfaceRgb]（内容面类：带文字/内容）→ [surfaceAlpha]。
     *
     * 其余背景（drawable / 渐变 / ripple）不动，它们要么自带纹理，要么在别处按档重建
     * （键面走 [PinyinKey.setFaceAlpha]、面板按钮走各自面板的 `applySurfaceAlpha`）。
     */
    private fun alphaFaces(
        v: View,
        plateRgb: IntArray,
        surfaceRgb: IntArray,
        plateAlpha: Float,
        surfaceAlpha: Float,
    ) {
        val c = (v.background as? android.graphics.drawable.ColorDrawable)?.color
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
     * 诊断（布局后调用）：找出屏幕上实际在铺底的视图，屏幕下半部、可见、有面积、
     * 背景是实心色的，全部列出来并标出 alpha 与「RGB 是否等于 kb_bg」。
     * 用途：透明度不生效时，一眼看出是哪一份视图在不透明地画。
     */
    private fun logBackgrounds() {
        val kbBg = context.getColor(R.color.kb_bg)
        val sb = StringBuilder()
        fun walk(v: View, depth: Int) {
            if (depth > 9 || sb.length > 700) return
            val c = (v.background as? android.graphics.drawable.ColorDrawable)?.color
            if (c != null && v.visibility == View.VISIBLE && v.width > 0 && v.height > 40) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (loc[1] > 1000) {
                    sb.append(' ').append(v.javaClass.simpleName)
                    if (v.id != View.NO_ID) {
                        sb.append('#').append(runCatching { resources.getResourceEntryName(v.id) }.getOrDefault("?"))
                    }
                    sb.append("=#").append(String.format("%08X", c))
                        .append("(a=").append((c ushr 24) and 0xFF)
                        .append(" kbBgRgb=").append(c and 0x00FFFFFF == kbBg and 0x00FFFFFF)
                        .append(' ').append(v.width).append('x').append(v.height)
                        .append('@').append(loc[1]).append(')')
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(rootView ?: this, 0)
        Diagnostics.i(TAG, "铺底诊断:$sb")
    }

    /**
     * 重建底部功能行（符号 / 数字 / 逗号 / 空格 / 句号 / 中英 / 回车）的背景。
     *
     * 这几个键的背景来自 XML（`key_bg.xml` / `btn_aurora_secondary.xml`），XML 里的颜色无法
     * 动态带 alpha，所以在运行时按同款几何重建：圆角与描边宽度与 XML 保持一致
     * （改 XML 时必须同步 [KEY_BG_CORNER_DP] / [BUTTON_CORNER_DP] / [BUTTON_STROKE_DP]），
     * 只把填充色与描边色换成带 alpha 的版本；水波纹颜色沿用原配色。
     *
     * 带缓存：透明度没变就不重建，避免每次弹键盘都分配 Drawable。
     */
    private fun rebuildFunctionKeyBackgrounds() {
        if (functionBgAlpha == keyFaceAlpha) return
        functionBgAlpha = keyFaceAlpha
        val keyFill = KeyTransparency.withAlpha(skinToken(skin.functionFill, R.color.key_bg), keyFaceAlpha)
        btnSpace.background = buildKeyBackground(keyFill, dpFloat(KEY_BG_CORNER_DP))
        btnEnter.background = buildKeyBackground(keyFill, dpFloat(KEY_BG_CORNER_DP))
        // 次级功能键（符号 / 数字 / 逗号 / 句号 / 中英）的「面」：默认皮肤回落 btn_secondary_bg
        // （历史观感），其余皮肤用功能面色 —— 磨砂等深色皮肤下若仍是白底，与周围深色键面形成刺眼对比
        val fill = KeyTransparency.withAlpha(
            skinToken(skin.functionFill, R.color.btn_secondary_bg),
            keyFaceAlpha,
        )
        val stroke = KeyTransparency.withAlpha(skinToken(skin.functionStroke, R.color.card_stroke), keyFaceAlpha)
        for (v in listOf<View>(btnSymbol, btnDigit, btnComma, btnPeriod, btnLang)) {
            v.background = buildButtonBackground(fill, stroke)
        }
    }

    /**
     * 重建大写键背景：普通深色 / 大写锁定强调色二态，圆角跟设置页参数走。
     *
     * 原先直接引用 key_bg.xml / key_bg_active.xml（后者随后续改造失去引用已删除），圆角固定 10dp，与字母键自绘的
     * 圆角对不上（同一行两种圆角），故改为运行时按同一参数生成。
     *
     * 带缓存：本方法由 [refreshKeyLabels] 间接调用（切层、翻符号页、切大小写都会走到），
     * 参数没变就不重建，避免无谓的 Drawable 分配。
     */
    private fun refreshShiftBackground() {
        if (shiftBgCornerPx == keyCornerPx && shiftBgCaps == capsMode && shiftBgAlpha == keyFaceAlpha) return
        shiftBgCornerPx = keyCornerPx
        shiftBgCaps = capsMode
        shiftBgAlpha = keyFaceAlpha
        btnShift.background = buildKeyBackground(
            KeyTransparency.withAlpha(
                if (capsMode) skinToken(skin.accent, R.color.accent)
                else skinToken(skin.functionFill, R.color.key_bg),
                keyFaceAlpha,
            )
        )
        // 底色在锁定态换成了强调色，图标色必须同步切换（普通态回皮肤主文字色）
        refreshShiftTint()
    }

    /** 重建删除键背景（无二态，始终深色）。同样带缓存，参数没变不重建 */
    private fun refreshBackspaceBackground() {
        if (backspaceBgCornerPx == keyCornerPx && backspaceBgAlpha == keyFaceAlpha) return
        backspaceBgCornerPx = keyCornerPx
        backspaceBgAlpha = keyFaceAlpha
        btnBackspace.background = buildKeyBackground(
            KeyTransparency.withAlpha(skinToken(skin.functionFill, R.color.key_bg), keyFaceAlpha)
        )
    }

    /**
     * 生成与 key_bg.xml 同款（实心圆角 + 水波纹按压反馈）但圆角可调的按键背景。
     *
     * mask 必须单独再造一个不透明的同圆角矩形：RippleDrawable 按 mask 的 alpha
     * 裁剪波纹，拿一个无色 Drawable 当 mask 会把波纹整个裁掉（按压就没有反馈了）。
     */
    private fun buildKeyBackground(fillColor: Int, cornerPx: Float = keyCornerPx): Drawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerPx
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = cornerPx
        }
        return RippleDrawable(
            ColorStateList.valueOf(context.getColor(R.color.key_ripple)), content, mask,
        )
    }

    /**
     * 生成与 `btn_aurora_secondary.xml` 同款（实心 + 1dp 描边 + 水波纹）的按钮背景。
     * 几何与 XML 对齐（圆角 [BUTTON_CORNER_DP] / 描边 [BUTTON_STROKE_DP]），颜色由调用方带 alpha 传入。
     */
    private fun buildButtonBackground(fillColor: Int, strokeColor: Int): Drawable {
        val corner = dpFloat(BUTTON_CORNER_DP)
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = corner
            setStroke(dpFloat(BUTTON_STROKE_DP).toInt().coerceAtLeast(1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = corner
        }
        return RippleDrawable(
            ColorStateList.valueOf(context.getColor(R.color.ripple_on_surface)), content, mask,
        )
    }

    /**
     * 候选栏 / 面板里仍以 XML 静态背景（`key_bg.xml`；`key_bg_active.xml` 随后续改造失去引用已删除）
     * 构建的按钮：按同款几何在运行时重建，只把填充色换成带「面不透明度」的版本，
     * 否则这些控件会一直是不透明的，把整条候选栏/面板压成实心（透明度拉满也看不出变化）。
     *
     * [cornerDp] 与 XML 对齐：`key_bg` 为 10dp（`key_bg_active` 同值，已删除）；符号分组标签用 0（纯矩形，
     * 原先由 `key_bg_rect.xml` 提供，随本次静态背景统一改造后该 drawable 已删除）。
     */
    private fun xmlKeyBackground(cornerDp: Float = KEY_BG_CORNER_DP, useAccent: Boolean = false): Drawable =
        buildKeyBackground(
            KeyTransparency.withAlpha(
                skinToken(
                    if (useAccent) skin.accent else skin.functionFill,
                    if (useAccent) R.color.accent else R.color.key_bg,
                ),
                keyFaceAlpha,
            ),
            dpFloat(cornerDp),
        )

    /**
     * 把字母键的「四边内缩」等效成 ImageButton 的外边距。
     *
     * 字母键的内缩画在自己的 canvas 里，普通 View 只能改外边距。不跟着改的话，
     * 大写键/删除键会贴满单元格，比字母键高一圈、左右也多出一截，一眼就错位。
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

    /**
     * 提交当前拼音串的首候选（IME 收起键盘 / 切换中英文等场景调用）。
     *
     * 搜索模式只丢弃、不上屏：此时拼音串是搜索框的输入，宿主输入框不在用户的输入意图内。
     * 语言键（切英文时会调它）与 IME 收起都会走到这里，紧接着的 listener.onCommitText
     * 会把搜索词（或它的首候选）写进用户正在编辑的正文里。
     */
    /**
     * 是否有未上屏的内容（拼音串或预测词）。
     *
     * IME 换主题（含定时到点）与符号布局变更都会整块重建键盘视图：若有未上屏内容，
     * 重建等同于静默丢弃它们（宿主输入框毫无变化，用户却看到候选栏被清空）
     *，所以重建前必须先问这里。
     */
    val hasPendingInput: Boolean
        get() = composing.isNotEmpty() || lastPredictions.isNotEmpty()

    /**
     * 视图上是否有正在使用的面板（剪贴板面板 / 顶部搜索面板）。
     *
     * 面板状态挂在视图上，重建会把它们直接关掉：用户正翻剪贴板历史时到点换肤，
     * 面板会毫无预告地消失（搜索态同）， 换肤延后判据因此要带上这一项。
     */
    val hasActiveOverlay: Boolean
        get() = clipboardActive || searchPanel.isActive()

    fun commitComposing() {
        if (isPanelSearch()) {
            clearComposingState()
            return
        }
        if (composing.isNotEmpty()) {
            val candidates = lastCandidates
            if (candidates.isNotEmpty()) {
                listener?.onCommitText(candidates[0])
            } else if (!englishMode) {
                // 无候选（如未加载词库），直接丢拼音串
                Diagnostics.v(TAG, "commitComposing: 无候选，丢弃拼音 ${composing}")
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
     * 返回的是用户实际按下的那些键，绝不做双拼→全拼转换：
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
        // 原始按键串是用户输入正文：走 V 级（默认只进 logcat 不落盘）
        Diagnostics.v(TAG, "回车输出英文原文: $raw")
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
        // 日志带上当前模式：排查时经常要先回答"这会儿到底是全拼还是双拼、哪一套"，
        // 光看 composing 判不出来（2026-09-18 连续三次在错误模式下做验证后才补上）。
        val modeTag = when {
            englishMode -> "英文"
            shuangpinMode -> "双拼·${scheme.shortName}"
            else -> "全拼"
        }
        Diagnostics.v(TAG, "拼音输入[$modeTag]: ${composing}")
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
                Diagnostics.v(TAG, "空格取首候选: \"$first\" (拼音=${composing})")
                learnChoice(first)                      // 空格取首候选同样是明确选择
                listener?.onCommitText(first)
                if (consumePinyin(first)) {
                    // 空格上屏同样触发智能预测（与点选候选一致）
                    lastCommittedWord = first
                    lastPredictions = if (predictionsEnabled()) PinyinEngine.predict(first) else emptyList()
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
        } else if (lastPredictions.isNotEmpty() && predictionsEnabled()) {
            // 预测态：空格取第一个预测词（onPredictionSelected 内部会学习「完整词」）
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
                // 必须校验双击是否刚发生，双击态会一直残留，不校验时间的话，
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

    /**
     * 双击+长按清空：先清拼音串与预测，再通知 IME 删除已上屏文本。
     *
     * 搜索模式下只清搜索框：该手势清的是「输入框」，而此刻用户的输入意图在搜索框上；
     * 落到 [Listener.onDeleteAll] 会把宿主（正在编辑的聊天 / 文档）内容整个删掉。
     */
    private fun onTripleBackspace() {
        clearComposingState()
        if (isPanelSearch()) {
            searchPanel.clearSearch()
            Diagnostics.i(TAG, "退格双击+长按：清空搜索框（不动宿主输入框）")
            return
        }
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
        } else if (lastPredictions.isNotEmpty() && predictionsEnabled()) {
            // 预测态退格：清除预测，回到拼音态
            lastPredictions = emptyList()
            refreshCandidateBar()
        } else {
            listener?.onBackspace()
        }
    }

    // ── 候选渲染 ───────────────────────────────────────────

    /** 设置页开关：关掉后不再产生预测，已显示的也会在下次刷新时清掉 */
    private fun predictionsEnabled(): Boolean = Prefs(context).predictEnabled

    private fun refreshCandidateBar() {
        // 候选栏底色按「当前是否有内容」选档（有候选/预测/拼音串 → surface，空白 → plate）
        updateCandidateBarBackground()
        // 开关刚被关掉时，把上一次留下的预测清掉，否则已显示的预测会一直挂在候选栏
        if (!predictionsEnabled() && lastPredictions.isNotEmpty()) {
            lastPredictions = emptyList()
        }
        // 符号层：候选栏显示符号分组标签（可横向滚动切换）；「✕ 清空候选」不适用 → 隐藏
        if (layer == LAYER_SYMBOL) {
            btnClearCandidates.visibility = View.GONE
            renderSymbolGroups()
            return
        }
        val input = composing.toString()
        if (input.isEmpty() && lastPredictions.isEmpty()) {
            lastCandidates = emptyList()
            viewCandidatePinyin.text = ""
            viewCandidateList.removeAllViews()
            // 内容刚全部清空：上面那次选档读到的 lastCandidates 还是旧值，这里清空后再判一次
            updateCandidateBarBackground()
            // 无候选、无拼音串、无预测：候选栏展示功能面板按钮（✕ 在 renderFunctionPanel 里隐藏）
            renderFunctionPanel()
            return
        }
        // 以下各分支都会展示「候选 / 预测 / 拼音串」：显示 ✕ 清空按钮（用户要求），
        // 有内容可清时才出现，功能面板与符号层都不显示。
        btnClearCandidates.visibility = View.VISIBLE

        if (input.isEmpty()) {
            // 智能预测模式：候选栏显示预测词（如选「你好」后显示 吗/像/不好…）
            viewCandidatePinyin.text = ""
            viewCandidateList.removeAllViews()
            for (pred in lastPredictions) {
                val item = TextView(context).apply {
                    text = pred
                    textSize = 21f
                    setTextColor(skinToken(skin.accent, R.color.kb_candidate_sel_text))
                    setPadding(dp(2), 0, dp(2), 0)
                    isClickable = true
                    setOnClickListener { onPredictionSelected(pred) }
                }
                viewCandidateList.addView(item)
            }
            Diagnostics.v(TAG, "智能预测: ${lastPredictions.take(4)}")
            return
        }

        // 词库尚未就绪（冷启动时高频子集约 0.4s，无子集的旧包则要 6~10.7s）：
        // 明确提示，而不是给一个「看起来像坏了」的空白候选栏。IME 内禁弹窗，改用内联提示。
        if (!PinyinEngine.isLoaded) {
            viewCandidatePinyin.text = input
            renderCandidateHint(context.getString(R.string.engine_dict_loading))
            return
        }

        // 双拼：先转全拼再查询；显示仍保留双拼原文
        val queryInput = if (shuangpinMode) Shuangpin.toQuanpin(input, scheme) else input
        val result = PinyinEngine.query(queryInput)
        // 补全诊断：只在末尾存在不完整音节时记录。
        // 绝不在这里再调一次 queryWithCompletion，PinyinEngine.query() 内部
        // 已经跑过补全召回，重复调用等于每次按键双倍查询，纯粹为了打日志。
        if (!shuangpinMode && result.partialSyllable.isNotEmpty()) {
            Diagnostics.v(
                TAG,
                "补全诊断: input=$queryInput syllables=${result.syllables} " +
                    "partial=${result.partialSyllable} candidates=${result.candidates.take(3)}",
            )
        }
        // 全量基础包还在后台 merge：此刻查不到候选的词，几秒后就会出现，明确告知，
        // 否则用户会以为「这个字打不出来」。
        if (result.candidates.isEmpty() && !PinyinEngine.isFullyLoaded) {
            lastCandidates = emptyList()
            viewCandidatePinyin.text = input          // 显示原始按键，不是转换后的全拼
            renderCandidateHint(context.getString(R.string.engine_dict_filling))
            return
        }
        lastCandidates = result.candidates
        // 拼音行显示用户实际按下的键（input），不是转换后的全拼（queryInput）。
        // 双拼下两者常常不同：`jg` 转全拼会被吞成 `j`，若显示 queryInput，
        // 用户按下 g/h 后拼音行毫无变化，看起来就像"按键没反应/卡住了"（2026-09-18 用户报告）。
        viewCandidatePinyin.text = input
        Diagnostics.v(TAG, "候选: ${if (shuangpinMode) "双拼[$input]→" else ""}$queryInput → ${result.candidates.take(3)}")

        viewCandidateList.removeAllViews()
        // 只渲染前若干条：单字候选可达 MAX_CHARS(60) 条（真实单字表里 `yi` 有 326 字、
        // 93 个音节超过 60 字），而这里是「每条一个 TextView」且每次按键全量重建，
        // 一次按键创建 60 个 View 在低端机上会明显掉帧。用户实际只点最前面几个
        // （单字候选按常用度排序），因此截断渲染量。
        // 这只影响渲染，[lastCandidates] 仍保存完整候选，空格/回车取首候选不受影响。
        for ((index, candidate) in result.candidates.withIndex()) {
            if (index >= MAX_RENDERED_CANDIDATES) break
            val item = TextView(context).apply {
                text = candidate
                textSize = 21f
                setTextColor(skinToken(skin.functionGlyph, R.color.text_primary))
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
                // 纯矩形（无圆角）+ 填充色带面 alpha（原 key_bg_rect.xml 的几何）
                background = xmlKeyBackground(0f)
                setPadding(dpFloat(8f).toInt(), dpFloat(2f).toInt(), dpFloat(8f).toInt(), dpFloat(2f).toInt())
                isClickable = true
            }
            item.setOnClickListener { selectGroup(idx) }
            // 主文本（矩形样式，字体放大 30%）
            val label = TextView(context).apply {
                text = group.label
                textSize = labelSize
                setTextColor(
                    if (sel) skinToken(skin.hintRed, R.color.kb_key_hint_red)
                    else skinToken(skin.functionGlyph, R.color.text_primary)
                )
                gravity = android.view.Gravity.CENTER
            }
            item.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // 选中组：右下角显示「当前页/总页数」小字
            if (sel) {
                val pageText = TextView(context).apply {
                    text = "${symbolPageInGroup.coerceIn(0, group.pages.lastIndex) + 1}/${group.pages.size}"
                    textSize = 9f // sp
                    setTextColor(skinToken(skin.functionHint, R.color.text_secondary))
                    gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
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

    // ── 符号分组：点击切换（顺序在设置页「符号分组顺序」卡片调整，见 [SymbolOrder]） ─────

    /** 切换当前分组（并回到该组第一页） */
    private fun selectGroup(idx: Int) {
        if (symbolGroupIndex == idx || idx !in symbolGroups.indices) return
        symbolGroupIndex = idx
        symbolPageInGroup = 0
        refreshKeyLabels()
        refreshCandidateBar()
        Diagnostics.i(TAG, "符号分组切换: ${symbolGroups[idx].label} (${idx + 1}/${symbolGroups.size})")
    }

    private fun onCandidateSelected(candidate: String) {
        // 搜索模式：候选上屏路由到剪贴板搜索词（不 commit 宿主）
        if (isPanelSearch()) {
            Diagnostics.v(TAG, "搜索候选: \"$candidate\" (拼音=${composing})")
            searchPanel.appendSearch(candidate)
            consumePinyin(candidate)
            refreshCandidateBar()
            return
        }
        Diagnostics.v(TAG, "候选上屏: \"$candidate\" (拼音=${composing})")
        learnChoice(candidate)          // 用户词频：这是**明确选择**，学习它
        listener?.onCommitText(candidate)
        // 残码重匹配：全部消费才进入智能预测态，否则候选栏立即显示残码的新候选
        if (consumePinyin(candidate)) {
            lastCommittedWord = candidate
            lastPredictions = if (predictionsEnabled()) PinyinEngine.predict(candidate) else emptyList()
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
        val fullInput = if (shuangpinMode) Shuangpin.toQuanpin(composing.toString(), scheme) else composing.toString()
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
        Diagnostics.v(
            TAG,
            "残码保留: 消费=\"${fullInput.take(consumption.quanpinChars)}\" " +
                "剩余拼音=${if (shuangpinMode) Shuangpin.toQuanpin(composing.toString(), scheme) else composing}",
        )
        return false
    }
    /**
     * 候选栏内联提示（词库加载中 / 补全中）。
     *
     * 只是候选栏里的一条文本：不改键盘高度、不弹窗（IME 内禁用 AlertDialog）。
     */
    private fun renderCandidateHint(text: String) {
        viewCandidateList.removeAllViews()
        viewCandidateList.addView(
            TextView(context).apply {
                this.text = text
                textSize = 15f
                setTextColor(skinToken(skin.functionHint, R.color.text_secondary))
                setPadding(dp(6), 0, dp(6), 0)
            },
        )
    }

    private fun onPredictionSelected(pred: String) {
        // 与 onCandidateSelected 保持一致：搜索模式下路由到搜索框。
        // 漏掉这个分支的话，搜索态里点预测词会把文本直接提交到宿主输入框（串到聊天内容里）
        if (isPanelSearch()) {
            Diagnostics.v(TAG, "搜索预测: \"$pred\"")
            searchPanel.appendSearch(pred)
            lastPredictions = emptyList()
            refreshCandidateBar()
            return
        }
        Diagnostics.v(TAG, "预测上屏: \"$pred\" (基于 ${lastCommittedWord})")
        // 用户词频：学习完整词（librime 的 UserDictionary 也是按整条 entry 记），
        // 这样「你好」+「吗」→ 记「你好吗」，下次打 nihaoma 它就在前面
        val fullWord = lastCommittedWord + pred
        learnChoice(fullWord.ifEmpty { pred })
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
        refreshSemicolonKey()
        // 中英切换键：上下两行「中文 / 英文」，把当前语言那一行染成主题紫并加粗。
        // 必须用 SpannableString 做部分着色，拆成两个 TextView 会各自居中，看起来像两个按钮。
        btnLang.textSize = 12f
        btnLang.maxLines = 2
        btnLang.setLineSpacing(0f, 0.9f)
        btnLang.text = buildLangLabel()
        btnSymbol.text = if (layer == LAYER_SYMBOL) {
            // 已进入符号层，此键的作用是回到字母页，用「返回」比「ABC」更直白。
            // 红色粗体（用户要求）：提示这一键现在切换的是整层（键面已全是符号），
            // 用 SpannableString 而不是 setTextColor，退出符号层时文本换回普通串，样式自动还原。
            android.text.SpannableString(context.getString(R.string.key_back)).apply {
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    android.text.style.ForegroundColorSpan(
                        skinToken(skin.hintRed, R.color.kb_key_hint_red)
                    ),
                    0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } else {
            context.getString(R.string.key_symbol)
        }
        btnDigit.text = if (layer == LAYER_DIGIT) {
            context.getString(R.string.key_abc)
        } else {
            context.getString(R.string.key_digit)
        }
        // 大写锁定：shift 键高亮（背景按设置页的圆角参数动态重建）；
        // 符号层大写键不参与操作：isEnabled=false（无障碍也报「不可用」）+ 置灰，
        // 点击路径另有层守卫（见 bindFunctionKeys），二者互为兜底。
        refreshShiftBackground()
        btnShift.isEnabled = layer != LAYER_SYMBOL
        btnShift.alpha = if (layer == LAYER_SYMBOL) 0.4f else 1f
        // 空格键顶部小字：同步当前输入类型
        updateSpaceHint()
    }

    /**
     * 中英切换键的富文本标签。
     *
     * 上下两行「中文 / 英文」，把当前生效的那一行染成主题紫并加粗，另一行保持普通样式
     * ，一眼就能看出当前处于哪种输入状态。
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
                    skinToken(skin.accent, R.color.aurora_purple)),
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

    /** 刷新空格键顶部小字（输入类型）。[btnSpaceHint] 是构造期赋值的非空 val，无需 isInitialized 守卫 */
    private fun updateSpaceHint() {
        btnSpaceHint.text = currentInputTypeLabel()
    }

    /**
     * 自然码双拼键位提示：键面下方显示该键对应的韵母。
     *  - Y/S/D 双韵母分行（`\n` 分隔，一行一个）
     *  - u/i/v 键下方韵母（v 键的 ui 在这里，zh 走红色 [shuangpinRedHint]）
     *  - e/a/u/i 键不显示韵母提示
     */
    /**
     * 分号键触摸：按下即把一个分号补进拼音串（它是搜狗/微软/紫光方案里 `ing` 的韵母键）。
     *
     * 与字母键一样由外部触摸驱动按压态（OnTouchListener 返回 true 后 PinyinKey.onTouchEvent
     * 不再执行），因此这里显式调 setPressedVisual。
     */
    private fun handleSemicolonTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                keySemicolon.setPressedVisual(true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                keySemicolon.setPressedVisual(false)
                // 命中判定不可省（与字母键同口径，见 isInsideKey 的 KDoc）：
                // 手指从 `;` 滑到相邻字母键再抬起时，不应把用户并未按下的分号追加进拼音串。
                if (!isInsideKey(keySemicolon, event)) {
                    Diagnostics.v(TAG, "分号键: 抬起在键外，忽略")
                    return true
                }
                composing.append(SEMICOLON_KEY)
                refreshCandidateBar()
                Diagnostics.v(TAG, "分号键(ing): 拼音串=${composing}")
                return true
            }

            // 与字母键同口径：多指场景下不复位会让分号键卡在高亮态
            MotionEvent.ACTION_POINTER_DOWN -> {
                keySemicolon.setPressedVisual(true)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                keySemicolon.setPressedVisual(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                keySemicolon.setPressedVisual(false)
                return true
            }
        }
        return false
    }

    /**
     * 刷新分号键：只在「当前方案用到分号键 + 字母层 + 非英文 + 非大写锁定」时显示。
     *
     * 键面与字母键同款：主文本 `;`、下方韵母提示（同样取自方案表，因此显示的就是该方案的
     * 键位含义）。不需要它的方案与符号层/数字层一律 GONE，GONE 不参与测量，
     * 26 键布局与已调好的圆角/间隙参数完全不受影响。
     */
    private fun refreshSemicolonKey() {
        val visible = layer == LAYER_LETTER && !englishMode && !capsMode &&
            Shuangpin.needsSemicolon(scheme)
        keySemicolon.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        keySemicolon.fullPinyinStyle = false
        keySemicolon.centeredStyle = false
        keySemicolon.label = SEMICOLON_KEY.toString()
        keySemicolon.subLabel = scheme.table?.finalHint(SEMICOLON_KEY).orEmpty()
        keySemicolon.subLabelRed = ""
    }

    /**
     * 键面韵母提示（字母键下方的小字）。
     *
     * 不手写键位表，直接从当前方案的码表反推（见 [ShuangpinTable.finalHint]），
     * 因此提示与引擎永远一致，切换方案时自动跟着变。
     * 旧实现是另手写一份 `when` 表，已经漂移出错（'o' 键写成 "ou"，实际应为 o / uo）。
     */
    private fun shuangpinHint(c: Char): String = scheme.table?.finalHint(c).orEmpty()

    /** 键面红色提示：该键承担 zh/ch/sh 中的哪一个（各方案不同：ABC 是 a/e/v、加加是 v/u/i） */
    private fun shuangpinRedHint(c: Char): String =
        scheme.table?.initialOf(c)?.takeIf { it.length > 1 }.orEmpty()

    // ── 功能面板（无候选时展示）──────────────────────────────

    /**
     * 无候选 / 无拼音串 / 无预测时，候选栏切换为功能面板（共 6 个按钮）：
     *  - 剪贴板：打开安全剪贴板历史页
     *  - 方向：打开方向控制面板（上下左右/空格/回车/行首/行末）
     *  - 全选：选中输入框全部文本
     *  - 复制：复制选中文本到系统剪贴板
     *  - 粘贴：粘贴剪贴板最新内容
     *  - 收起：隐藏输入法面板（重新点击输入框再唤醒）
     *
     * 「全拼 / 双拼」切换按钮已于 2026-09-20 按用户要求移除：输入方案统一在设置页
     * 「输入方案」下拉里改（全拼 + 7 套双拼，全局生效），面板不再承担方案切换。
     *
     * 复用候选栏的 [candidate_list] 区域，高度与候选栏一致（48dp），
     * 不改变键盘整体高度；按钮横向排列，小屏自动可横向滚动。
     */
    private fun renderFunctionPanel() {
        viewCandidateList.removeAllViews()
        // 「✕ 清空候选」只在有候选时出现：功能面板（含搜索态「退出搜索」）一律隐藏
        btnClearCandidates.visibility = View.GONE
        // 搜索态：功能面板只保留「退出搜索」。
        // 其余按钮都不能出现，历史/收起会打断搜索；而 全选/复制/方向/粘贴 都是
        // 作用于宿主输入框的动作：搜索态下 26 键只作用于搜索框（见 isPanelSearch 的各路由），
        // 面板动作必须同口径。「全选」会让退出搜索后的下一次输入替换整段正文，
        // 「粘贴」会把剪贴板正文注入宿主，「方向」会移动宿主光标。
        if (isPanelSearch()) {
            viewCandidateList.addView(buildFunctionButton(
                label = "退出",
                hint = "搜索",
                onClick = {
                    Diagnostics.i(TAG, "功能面板: 退出搜索")
                    hideSearchPanel()
                },
            ))
            Diagnostics.v(TAG, "功能面板(搜索态): 退出")
            return
        }
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
        // 面板可能是重建的（候选栏刷新），而方向面板状态仍为激活，立即同步一次文案
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
        Diagnostics.v(
            TAG,
            "功能面板(6 按钮): 历史/方向/全选/复制/粘贴/收起（当前方案=${scheme.displayName}）",
        )
    }

    /** 构建单个功能按钮：候选栏同高，现有键盘风格（深色圆角 + 主文字） */
    private fun buildFunctionButton(
        label: String,
        hint: String,
        onClick: () -> Unit,
    ): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            // key_bg 同款（10dp 圆角），但填充色带面 alpha，这 6 个按钮占满候选栏
            background = xmlKeyBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        // 百分比均分：每个按钮 weight=1，均分候选栏宽度（6 个按钮各占 1/6）
        val lp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        box.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(skinToken(skin.functionGlyph, R.color.text_primary))
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(TextView(context).apply {
            text = hint
            textSize = 9f
            setTextColor(skinToken(skin.functionHint, R.color.text_secondary))
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
            if (active) skinToken(skin.hintRed, R.color.kb_key_hint_red)
            else skinToken(skin.functionGlyph, R.color.text_primary)
        )
        labelView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        hintView?.text = "控制"
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
        // key_bg 同款（10dp 圆角；原 key_bg_active 同值、已删除），填充色带面 alpha（激活态用强调色）
        key?.background = xmlKeyBackground(useAccent = active)
        // 激活态底色是各皮肤 accent（浅到钛金 #B8C4D6、深到雪原 #3A6EA5）：字色按该色亮度取深/浅，
        // 与 shift 键同口径；否则近白字压浅 accent 仅约 1.5:1，等于看不见（与 D4 同类）
        key?.setTextColor(
            if (active) KeyboardSkins.accentOnColor(skinToken(skin.accent, R.color.accent))
            else skinToken(skin.functionGlyph, R.color.text_primary),
        )
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
        // 而 directionPanelVisible 已置 true，用户点方向键毫无反应。
        if (clipboardActive) hideClipboardPanel()
        // 字母区隐藏，方向面板显示
        Diagnostics.i(TAG, "字母区: 隐藏三行（原因=方向面板显示）")
        for (i in 0 until viewLetters.childCount) {
            viewLetters.getChildAt(i).visibility = View.GONE
        }
        panel.tag = DIRECTION_PANEL_TAG
        viewLetters.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        panel.visibility = View.VISIBLE
        directionPanelVisible = true
        // 进入方向面板时重置拖选状态。
        // 必须同时回传 IME：否则视图认为未拖选（中心键画 ●）、IME 仍以为在拖选，
        // 用户点中心键会先「取消」再「激活」，出现"点一次没反应、要点两次"的错位。
        // 当前只有 hideDirectionPanel 会回传，这条路径虽然暂时不可达（面板只能经它隐藏），
        // 但两处不对称迟早会被将来的改动踩到，这里补齐成对称实现。
        selectionActive = false
        listener?.onSelectionModeChanged(false)
        refreshDirectionButton()
        Diagnostics.i(TAG, "方向面板: 显示（候选栏/底部栏保持）")
    }

    /**
     * 恢复字母三行（把 viewLetters 的子视图全部设回 VISIBLE）。
     *
     * 单独抽出来是必须的：原先这段恢复只写在 [hideDirectionPanel] 里，且被
     * `if (!directionPanelVisible) return` 挡在前面，也就是说"字母三行的可见性"完全被那个 flag 托管。
     * 只要有任何路径把三行设成 GONE 而 flag 没置位（或 flag 被清掉却跳过恢复），
     * 三行就会永久失活：键面看着还在，但整块不再响应触摸，而底栏是另一个容器照常可用。
     * 所以这里改成"谁把子视图藏了，恢复时一律无条件恢复"。
     */
    private fun restoreLetterRows() {
        for (i in 0 until viewLetters.childCount) {
            viewLetters.getChildAt(i).visibility = View.VISIBLE
        }
    }

    /**
     * 清掉残留在字母区里的方向面板。
     *
     * 按 tag 识别而不是按下标：字母行在 XML 里其实正好 3 个，但"按下标 ≥3 一律删"
     * 这种写法一旦布局加了第 4 个子视图就会误删真键，属于没必要背的风险。
     * 面板是运行时 addView 进去的，一旦出现"面板还挂着但 directionPanel 引用已丢"，
     * 它就永远留在那儿盖住键区（它排在字母行之后，会先吃到触摸）。
     */
    private fun dropStaleDirectionPanel() {
        for (i in viewLetters.childCount - 1 downTo 0) {
            if (viewLetters.getChildAt(i).tag == DIRECTION_PANEL_TAG) {
                viewLetters.removeViewAt(i)
            }
        }
    }

    /** 恢复 26 键字母布局 */
    fun hideDirectionPanel() {
        if (!directionPanelVisible) {
            // flag 已为 false 时也要把子视图恢复一遍：不能再假设"flag 为 false ⇒ 字母区是好的"。
            // 同时清掉可能残留的方向面板，它 addView 在字母行之后，会盖在键区上面吃触摸。
            directionPanel = null
            centerSelectionKey = null
            dropStaleDirectionPanel()
            restoreLetterRows()
            return
        }
        directionPanel = null
        centerSelectionKey = null
        dropStaleDirectionPanel()
        restoreLetterRows()
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
     * - 会修改 layoutParams：contentArea 被改成固定高度 162dp×2（约 850px），
     *   面板 MATCH_PARENT 填满它。固定高度让面板不受 IME 窗口初始测量影响（冷启动稳定）。
     * - viewLetters 实际用 GONE 而非 INVISIBLE：contentArea 已是固定高度，
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
            // 与方向面板、顶部搜索面板互斥：搜索面板挂在根布局 index 0（显示时 IME 变高），
            // 剪贴板面板替换字母区，两者同屏时上下两个列表都在，而底栏的空格/退格/回车
            // 仍会被 isPanelSearch() 路由到搜索框，按键实际作用的对象与用户看到的不一致。
            if (directionPanelVisible) hideDirectionPanel()
            hideSearchPanel()

            // contentArea 高度 = 字母区 2 倍（用户验证过的 850px 方案）。
            // 关键：contentArea 的父是 LinearLayout（PinyinKeyboardView 根），
            // 必须用 LinearLayout.LayoutParams，早期用 FrameLayout.LayoutParams
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
        restoreLetterRows()
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
     * 进入或退出搜索模式时必须调用：残留的拼音串虽不可见却仍然生效，
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
        // 与方向面板互斥：两者同屏时方向键（箭头/复制/粘贴）会落到宿主输入框上，
        // 与「搜索态只作用于搜索框」的口径冲突（同 showClipboardPanel 的对称处理）。
        if (directionPanelVisible) hideDirectionPanel()
        // 必须在面板可见之后再刷一次候选栏：clearComposingState 内部那次刷新发生在
        // visibility 置位之前，isPanelSearch() 仍为 false，会渲染出宿主功能面板并残留。
        refreshCandidateBar()
        Diagnostics.i(TAG, "顶部搜索面板: 显示（IME 高度增高）")
    }

    /** 隐藏顶部搜索面板，恢复正常 26 键键盘 */
    fun hideSearchPanel() {
        if (!searchPanel.isActive()) return
        clearComposingState()
        searchPanel.visibility = View.GONE
        searchPanel.onHidden()
        // 对称于显示：置 GONE 之后再刷一次，候选栏从「退出搜索」恢复为常规功能面板
        refreshCandidateBar()
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
            setTextColor(skinToken(skin.functionGlyph, R.color.text_primary))
            // key_bg 同款（10dp 圆角），填充色带面 alpha
            background = xmlKeyBackground()
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

    /**
     * 视图被移除时的收尾。
     *
     * 必须做：连删是一个自我重排的 55ms 循环（[backspaceRepeatRunnable]），唯一的
     * 停止条件是 ACTION_UP / ACTION_CANCEL 把 [backspaceHeld] 复位，而视图被 detach 时
     * Android 不保证补发 ACTION_CANCEL（移除视图不派发取消事件是已知行为）。
     * 一旦在按住删除键期间视图被销毁（IME 重建输入视图 / 服务销毁 / 宿主收起），循环就
     * 再也停不下来：它永久持有已销毁的视图，并每 55ms 回调一次 [Listener.onBackspace]，
     * 也就是持续给当前输入框发 DEL，用户没碰键盘，字却一直在被删。
     */
    override fun onDetachedFromWindow() {
        backspaceHandler.removeCallbacksAndMessages(null)
        backspaceHeld = false
        backspaceTapCount = 0
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "PinyinKeyboard"

        /**
         * 底部功能行的背景几何：与 XML 对齐，改 XML 时必须同步这里 ，
         * 空格/回车走 `key_bg.xml`（圆角 10dp）；
         * 其余五个走 `btn_aurora_secondary.xml`（12dp 圆角 + 1dp 描边）。
         * 半透明模式下这几个背景要在运行时重建（XML 颜色带不了动态 alpha）。
         */
        const val KEY_BG_CORNER_DP = 10f
        const val BUTTON_CORNER_DP = 12f
        const val BUTTON_STROKE_DP = 1f

        /**
         * 候选栏最多渲染多少个候选条目。
         *
         * 候选数由引擎的 MAX_CHARS(60) 决定上限，而真实单字表里 `yi` 有 326 字、
         * 93 个音节超过 60 字，即常用音节经常给出满额候选。渲染是「每条一个
         * TextView」且每次按键全量重建，60 个 View 的创建在低端机上会明显掉帧。
         * 用户实际只点最前面几个（单字候选已按常用度排序），故截断渲染量。
         * 仅影响渲染，不影响 [lastCandidates] 中保存的完整候选与上屏行为。
         */
        const val MAX_RENDERED_CANDIDATES = 24

        /** 按键命中判定的边界外扩（dp）：贴边点击时手指会有小幅抖动 */
        const val KEY_HIT_PADDING_DP = 8f

        /** 分号键：搜狗 / 微软 / 紫光 方案里 `ing` 的韵母键 */
        const val SEMICOLON_KEY = ';'

        const val LAYER_LETTER = 0
        const val LAYER_SYMBOL = 1
        const val LAYER_DIGIT = 2
    }
}

/**
 * 构建与 `R.drawable.key_bg` 同款（圆角 [cornerDp] + `R.color.key_bg` 填充 + `R.color.key_ripple`
 * 涟漪）的键面背景；[alpha] 只作用于填充色，文字/图标不动（与 [KeyTransparency] 的
 * 「只淡面不淡文字」一致）。
 *
 * 用途：面板里的分类按钮用的是 `key_bg` drawable，带不了动态 alpha，键盘侧的「纯色面」
 * 识别也扫不到它；透明度 > 0 时由面板按当前档重建（见 `ClipboardPanelView.applySurfaceAlpha`）。
 * [cornerDp] 默认值与 `key_bg.xml` 的圆角对齐（改动 XML 时必须同步，与键盘侧的
 * `KEY_BG_CORNER_DP` 是同一口径）。
 */
/**
 * 皮肤覆盖色优先，未定义（null）时回落到 `R.color` 令牌色。键盘视图与面板共用同一口径，
 * 使默认皮肤（全部覆盖为 null）与历史配色逐像素一致。
 */
internal fun skinColor(context: android.content.Context, override: Int?, tokenRes: Int): Int =
    override ?: context.getColor(tokenRes)

internal fun buildKeyFaceBackground(
    context: android.content.Context,
    alpha: Float,
    cornerDp: Float = 10f,
    fillColor: Int = context.getColor(R.color.key_bg),
): android.graphics.drawable.Drawable {
    val corner = cornerDp * context.resources.displayMetrics.density
    val content = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(KeyTransparency.withAlpha(fillColor, alpha))
        cornerRadius = corner
    }
    val mask = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(android.graphics.Color.WHITE)
        cornerRadius = corner
    }
    return android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(context.getColor(R.color.key_ripple)),
        content,
        mask,
    )
}

/**
 * 圆角纯色面（如 `mic_area_bg`：16dp 圆角的 `surface_hi` 底盘）；[alpha] 只作用于填充色。
 * 与 [buildKeyFaceBackground] 同类，差别是没有涟漪，对应 XML shape 的重建。
 * [cornerDp] 必须与对应 XML 的圆角对齐（改 XML 时必须同步）。
 */
internal fun buildRoundedFaceBackground(context: android.content.Context, colorRes: Int, alpha: Float, cornerDp: Float): android.graphics.drawable.Drawable =
    android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = cornerDp * context.resources.displayMetrics.density
        setColor(KeyTransparency.withAlpha(context.getColor(colorRes), alpha))
    }

/**
 * 按键命中判定的几何部分（文件级纯函数，便于 JVM 单测；调用方见 `PinyinKeyboardView.isInsideKey`）。
 *
 * - 边界外扩 [pad]（dp 换算后的像素）：贴边点击时手指常有 1~2 像素抖动，
 *   完全不放宽会让边缘键变得难点中；
 * - 取不到布局信息（未测量/已分离，width/height ≤ 0）时返回 true，保守退回
 *   「照常输入」：宁可保留旧行为，也不能因为拿不到坐标而让用户按不出字。
 *
 * 字母键与分号键的 `ACTION_UP` 命中判定共用本函数（分号键曾漏掉该判定，
 * 按下后滑到相邻键抬起仍会上 `;`）。
 */
internal fun isInsideKeyBounds(
    rawX: Float,
    rawY: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    pad: Float,
): Boolean {
    if (width <= 0 || height <= 0) return true
    return rawX >= left - pad &&
        rawX <= left + width + pad &&
        rawY >= top - pad &&
        rawY <= top + height + pad
}
