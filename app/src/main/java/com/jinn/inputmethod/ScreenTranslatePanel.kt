package com.jinn.inputmethod

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 屏幕翻译的浮层面板（`TYPE_ACCESSIBILITY_OVERLAY`，由 [ScreenTranslateService] 添加与移除）。
 *
 * 为什么代码建 View 而不是引 layout XML：本面板挂在 **Service 上下文**上（没有 Activity 主题，
 * XML 里的 `?attr/...` 取不到值），且「界面文字不进 `strings.xml`」是仓内约定 ——
 * 代码建 View 让文案与结构都在一处，改起来不必动资源。
 *
 * 状态机（与服务侧一一对应，见方案 §9.2）：
 *  空态 [renderEmpty] → 勾选态 [renderPicking] → 加载态 [renderLoading] → 结果态 [renderResult]
 *                     ↘ 错误态 [renderError]（+ [showHint] 只改提示行，不动列表）
 *
 * 线程：**只在主线程使用**（服务侧保证）。跨异步的东西一件都不持有：勾选集合是本面板的状态副本，
 * 提交时通过 [Listener] 交给服务。
 *
 * 生命周期红线（INV-7）：[attach] / [detach] 严格成对，由 `added` 守卫；[destroy] 之后不得再 attach。
 */
internal class ScreenTranslatePanel(
    private val context: Context,
    private val listener: Listener,
) {

    /** 服务实现；面板只回调、不做判断（判断都在服务与纯层里）。 */
    internal interface Listener {
        fun onPickChanged(selected: Set<Int>)
        fun onTranslate()
        fun onCopyAll()
        fun onReplace()
        fun onOpenSettings()
        fun onRecapture()
        fun onClose()
    }

    private val root = FrameLayout(context)
    private val column = LinearLayout(context)
    private val sourceText = TextView(context)
    private val hintText = TextView(context)
    private val scroll = ScrollView(context)
    private val list = LinearLayout(context)
    private val countText = TextView(context)
    private val btnRecapture = smallButton(TEXT_RECAPTURE)
    private val btnSelectAll = smallButton(TEXT_SELECT_ALL)
    private val btnClear = smallButton(TEXT_CLEAR)
    private val btnTranslate = smallButton(TEXT_TRANSLATE, primary = true)
    private val btnCopyAll = smallButton(TEXT_COPY_ALL, primary = true)
    private val btnReplace = smallButton(TEXT_REPLACE, primary = true)
    private val btnSettings = smallButton(TEXT_OPEN_SETTINGS)

    /** 当前勾选（索引 → 段）。面板自己维护，`onPickChanged` 同步给服务。 */
    private var picked: Set<Int> = emptySet()
    private var capture: ScreenCapture? = null
    private var added = false
    private var destroyed = false

    init {
        root.background = GradientDrawable().apply {
            setColor(COLOR_BG)
            cornerRadius = dp(10).toFloat()
        }
        // 点面板外收起：需 FLAG_WATCH_OUTSIDE_TOUCH（见 windowParams），ACTION_OUTSIDE 会送到根视图
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                listener.onClose()
                true
            } else {
                false
            }
        }

        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(12), dp(10), dp(12), dp(10))
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // 头部：标题 + 来源 + 关闭
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label(TEXT_TITLE, 15f, COLOR_TEXT))
        sourceText.apply {
            setTextColor(COLOR_HINT)
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(sourceText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton(TEXT_CLOSE) { listener.onClose() })
        column.addView(header)

        // 提示行（按状态显示；无提示时 GONE）
        hintText.apply {
            setTextColor(COLOR_HINT)
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        column.addView(hintText)

        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        scroll.isFillViewport = false
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        // 底栏：计数 + 全选 / 清空 / 翻译（结果态换 复制全部 [+ 替换原文]；错误态给 去设置）
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        countText.apply {
            setTextColor(COLOR_HINT)
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        footer.addView(countText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(btnRecapture)
        footer.addView(btnSelectAll)
        footer.addView(btnClear)
        footer.addView(btnSettings)
        footer.addView(btnReplace)
        footer.addView(btnCopyAll)
        footer.addView(btnTranslate)
        column.addView(footer)

        btnRecapture.setOnClickListener { listener.onRecapture() }
        btnSelectAll.setOnClickListener { setAllPicked(true) }
        btnClear.setOnClickListener { setAllPicked(false) }
        btnTranslate.setOnClickListener { listener.onTranslate() }
        btnCopyAll.setOnClickListener { listener.onCopyAll() }
        btnReplace.setOnClickListener { listener.onReplace() }
        btnSettings.setOnClickListener { listener.onOpenSettings() }
    }

    // ── 渲染（五态） ────────────────────────────────────

    /** 空态：这个界面读不到可翻译的文字（自绘界面 / 图片里的文字读不到）。 */
    fun renderEmpty() {
        capture = null
        picked = emptySet()
        list.removeAllViews()
        showHint(TEXT_EMPTY)
        setFooter(selectAll = false, clear = false, translate = false, copyAll = false, replace = false, settings = false)
        countText.visibility = View.GONE
    }

    /** 勾选态：重建复选行（只在**进入**勾选态时调用；勾选变化由面板内部更新，不重建）。 */
    fun renderPicking(snapshot: ScreenCapture, selected: Set<Int>, imeAroundHint: Boolean) {
        capture = snapshot
        picked = selected.filter { it in snapshot.segments.indices }.toSet()
        sourceText.text = sourceLabel(snapshot.sourcePackage)
        list.removeAllViews()
        snapshot.segments.forEachIndexed { index, segment ->
            list.addView(pickRow(index, segment))
        }
        showHint(
            when {
                imeAroundHint -> TEXT_IME_AROUND
                snapshot.truncatedByLimit -> hintTruncated()
                else -> null
            },
        )
        countText.visibility = View.VISIBLE
        setFooter(selectAll = true, clear = true, translate = true, copyAll = false, replace = false, settings = false)
        syncPickFooter()
    }

    /** 加载态：原文预览 + 翻译中…（截断时补一句提示）。 */
    fun renderLoading(truncated: Boolean) {
        list.removeAllViews()
        list.addView(label(TEXT_TRANSLATING, 15f, COLOR_TEXT))
        showHint(if (truncated) TEXT_TRUNCATED_PICKED else null)
        countText.visibility = View.GONE
        setFooter(
            selectAll = false, clear = false, translate = false,
            copyAll = false, replace = false, settings = false, recapture = false,
        )
    }

    /** 结果态：原文（次级色，最多 6 行）+ 译文；`canReplace` 由服务的五闸判据给出。 */
    fun renderResult(original: String, translated: String, canReplace: Boolean) {
        list.removeAllViews()
        if (original.isNotBlank()) {
            list.addView(label(original, 11f, COLOR_HINT, maxLines = 6))
        }
        list.addView(label(TEXT_RESULT_LABEL, 11f, COLOR_HINT, maxLines = 1))
        list.addView(label(translated, 15f, COLOR_TEXT))
        if (canReplace) list.addView(label(TEXT_REPLACE_HINT, 11f, COLOR_HINT, maxLines = 2))
        showHint(if (capture?.truncatedByLimit == true) TEXT_TRUNCATED_PICKED else null)
        countText.visibility = View.GONE
        setFooter(selectAll = false, clear = false, translate = false, copyAll = true, replace = canReplace, settings = false)
    }

    /**
     * 错误态：归一文案（次级色警示）+ 「去设置」。
     *
     * 文案放列表区而不是 11sp 的提示行：错误文案本身较长（如
     * 「请先在『设置 → 翻译设置』里填写翻译 API 凭据」），11sp 在浮层上读不清。
     */
    fun renderError(message: String, showSettings: Boolean = false) {
        list.removeAllViews()
        list.addView(label(message, 14f, COLOR_DANGER))
        showHint(null)
        countText.visibility = View.GONE
        setFooter(selectAll = false, clear = false, translate = false, copyAll = false, replace = false, settings = showSettings)
    }

    /** 只改提示行（用于「勾选内容没有可翻译的文字」这类轻提示），不动列表与其他控件。 */
    fun showHint(message: String?) {
        hintText.text = message.orEmpty()
        hintText.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ── 窗口生命周期 ─────────────────────────────────────

    /** 显示 / 更新窗口。`screenH` 与 `imeTopPx` 用于把面板钳在屏内且不盖住键盘。 */
    fun attach(wm: WindowManager, screenH: Int, imeTopPx: Int) {
        if (destroyed) return
        applyContentCap(screenH, imeTopPx)
        val lp = windowParams(screenH, imeTopPx)
        if (added) {
            runCatching { wm.updateViewLayout(root, lp) }
                .onFailure { Diagnostics.w(TAG, "面板重排失败: ${it.javaClass.simpleName}") }
        } else {
            val ok = runCatching { wm.addView(root, lp) }
                .onFailure { Diagnostics.w(TAG, "面板添加失败: ${it.javaClass.simpleName}") }
                .isSuccess
            added = ok
        }
    }

    fun detach(wm: WindowManager) {
        if (!added) return
        runCatching { wm.removeView(root) }
            .onFailure { Diagnostics.w(TAG, "面板移除失败: ${it.javaClass.simpleName}") }
        added = false
    }

    /** 配置变更（旋转 / 分屏）：只重算尺寸与位置，不清内容。 */
    fun relayout(screenH: Int, imeTopPx: Int) {
        if (!added || destroyed) return
        applyContentCap(screenH, imeTopPx)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.updateViewLayout(root, windowParams(screenH, imeTopPx)) }
    }

    /** 服务销毁时释放全部视图引用（此后 attach 无效）。 */
    fun destroy() {
        destroyed = true
        list.removeAllViews()
        column.removeAllViews()
        root.removeAllViews()
        picked = emptySet()
        capture = null
    }

    // ── 内部工具 ─────────────────────────────────────────

    private fun pickRow(index: Int, segment: ScreenSegment): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val check = CheckBox(context)   // ⚠ 先设 isChecked 再挂监听：否则初值会触发一次回调
        check.isChecked = index in picked
        check.isFocusable = false
        check.setOnCheckedChangeListener { _, checked ->
            picked = if (checked) picked + index else picked - index
            syncPickFooter()
            listener.onPickChanged(picked)
        }
        val text = label(preview(segment.text), 13f, COLOR_TEXT, maxLines = 1)
        row.addView(check)
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 整行可点：复选框本身很小，正文那一大片也要能勾上
        row.setOnClickListener { check.isChecked = !check.isChecked }
        return row
    }

    private fun setAllPicked(value: Boolean) {
        val snapshot = capture ?: return
        picked = if (value) snapshot.segments.indices.toSet() else emptySet()
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? LinearLayout ?: continue
            val check = row.getChildAt(0) as? CheckBox ?: continue
            if (check.isChecked != value) check.isChecked = value
        }
        syncPickFooter()
        listener.onPickChanged(picked)
    }

    private fun syncPickFooter() {
        val snapshot = capture
        val chars = snapshot?.segments?.filterIndexed { i, _ -> i in picked }?.sumOf { it.text.length } ?: 0
        countText.text = String.format(java.util.Locale.US, TEXT_COUNT, picked.size, chars)
        btnTranslate.isEnabled = picked.isNotEmpty()
        btnTranslate.alpha = if (picked.isNotEmpty()) 1f else 0.4f
    }

    private fun setFooter(
        selectAll: Boolean,
        clear: Boolean,
        translate: Boolean,
        copyAll: Boolean,
        replace: Boolean,
        settings: Boolean,
        /** 「重新读取」：除加载态外都给（用户可能刚滚动了页面 / 换了界面，想按当前屏幕重来一遍） */
        recapture: Boolean = true,
    ) {
        btnRecapture.visibility = if (recapture) View.VISIBLE else View.GONE
        btnSelectAll.visibility = if (selectAll) View.VISIBLE else View.GONE
        btnClear.visibility = if (clear) View.VISIBLE else View.GONE
        btnTranslate.visibility = if (translate) View.VISIBLE else View.GONE
        btnCopyAll.visibility = if (copyAll) View.VISIBLE else View.GONE
        btnReplace.visibility = if (replace) View.VISIBLE else View.GONE
        btnSettings.visibility = if (settings) View.VISIBLE else View.GONE
    }

    private fun windowParams(screenH: Int, imeTopPx: Int): WindowManager.LayoutParams {
        val dm = context.resources.displayMetrics
        val maxPanel = maxPanelHeight(screenH, imeTopPx)
        // x 固定 16dp（面板宽度已钳在屏内），y 取屏高 1/8 —— 不与状态栏 / 键盘打架的保守位
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ScreenTranslateLogic.clamp(screenH / 8, dp(16), (screenH - maxPanel).coerceAtLeast(dp(16)))
            width = panelWidth()
            if (dm.heightPixels > 0 && y > dm.heightPixels) y = dp(16)
        }
    }

    /**
     * 面板可用高度：屏高 70% 与「输入法窗口顶边 - 16dp」取小。
     *
     * 无障碍浮层层级**高于输入法窗口**（v0-B 已实测）—— 不钳的话宿主键盘弹出时面板会盖住键盘，
     * 用户既看不到下半屏也没法收键盘。
     */
    private fun maxPanelHeight(screenH: Int, imeTopPx: Int): Int =
        ScreenTranslateLogic.clamp(
            (screenH * 0.7f).toInt(),
            dp(160),
            (imeTopPx - dp(16)).coerceAtLeast(dp(160)),
        )

    private fun panelWidth(): Int {
        val dm = context.resources.displayMetrics
        return ScreenTranslateLogic.clamp(minOf(dm.widthPixels - dp(32), dp(380)), dp(200), dm.widthPixels)
    }

    /**
     * 把滚动区钉在「可用高度 - 固定部分」上。
     *
     * `WRAP_CONTENT` 的窗口没法直接给 maxHeight（ScrollView 也没有该属性），所以先给滚动区一个
     * 临时小高度、量一遍得到「头部 + 提示行 + 底栏」的实际高度，再把余量给滚动区 ——
     * 译文再长也只在这块区域里滚，面板整体永不超出屏幕。
     */
    private fun applyContentCap(screenH: Int, imeTopPx: Int) {
        val width = panelWidth()
        val maxPanel = maxPanelHeight(screenH, imeTopPx)
        val probe = dp(40)
        (scroll.layoutParams as? LinearLayout.LayoutParams)?.height = probe
        root.layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        runCatching {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
        }
        val chrome = (root.measuredHeight - probe).coerceAtLeast(0)
        (scroll.layoutParams as? LinearLayout.LayoutParams)?.height = (maxPanel - chrome - dp(8)).coerceAtLeast(dp(48))
        root.requestLayout()
    }

    private fun preview(text: String): String =
        if (text.length > SOURCE_PREVIEW_MAX) text.take(SOURCE_PREVIEW_MAX) + "…" else text

    private fun sourceLabel(pkg: String?): String =
        if (pkg.isNullOrEmpty()) "" else TEXT_SOURCE_PREFIX + pkg

    private fun hintTruncated(): String =
        String.format(java.util.Locale.US, TEXT_TRUNCATED, ScreenTextCollector.MAX_SEGMENTS)

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun label(text: String, sizeSp: Float, color: Int, maxLines: Int = Int.MAX_VALUE): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            this.maxLines = maxLines
            if (maxLines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun smallButton(text: String, primary: Boolean = false, onClick: (() -> Unit)? = null): Button =
        Button(context).apply {
            this.text = text
            textSize = 13f
            transformationMethod = null          // 防主题把按钮文字自动转大写（各 ROM 主题差异大）
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (primary) COLOR_ACCENT else COLOR_BUTTON)
            }
            setTextColor(Color.WHITE)
            onClick?.let { setOnClickListener { it() } }
        }

    private companion object {
        val COLOR_BG: Int = Color.argb(0xF2, 0x1E, 0x1F, 0x24)
        val COLOR_TEXT: Int = Color.WHITE
        val COLOR_HINT: Int = Color.argb(0xFF, 0x9E, 0x9E, 0xA6)
        val COLOR_ACCENT: Int = Color.argb(0xFF, 0x3D, 0x7E, 0xFF)
        val COLOR_BUTTON: Int = Color.argb(0xFF, 0x3A, 0x3C, 0x44)
        val COLOR_DANGER: Int = Color.argb(0xFF, 0xFF, 0x6B, 0x6B)

        const val TAG = Diagnostics.TAG
        const val SOURCE_PREVIEW_MAX = 200

        const val TEXT_TITLE = "屏幕翻译"
        const val TEXT_SOURCE_PREFIX = "来源："
        const val TEXT_CLOSE = "关闭"
        const val TEXT_RECAPTURE = "重新读取"
        const val TEXT_SELECT_ALL = "全选"
        const val TEXT_CLEAR = "清空"
        const val TEXT_TRANSLATE = "翻译"
        const val TEXT_COPY_ALL = "复制全部"
        const val TEXT_REPLACE = "替换原文"
        const val TEXT_OPEN_SETTINGS = "去设置"
        const val TEXT_TRANSLATING = "翻译中…"
        const val TEXT_EMPTY = "这个界面读不到可翻译的文字（自绘界面 / 图片里的文字读不到）"
        const val TEXT_IME_AROUND = "此处选中翻译可直接用键盘上的翻译键"
        const val TEXT_RESULT_LABEL = "译文"
        const val TEXT_REPLACE_HINT = "「替换原文」会把该输入框内容整段换成译文"
        const val TEXT_TRUNCATED = "内容较多，仅列出前 %d 段"
        const val TEXT_TRUNCATED_PICKED = "所选内容超上限已截断，不提供替换"
        const val TEXT_COUNT = "已选 %d 段 · %d 字"
    }
}
