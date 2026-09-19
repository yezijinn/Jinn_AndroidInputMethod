package com.jinn.inputmethod

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 搜索的一次解密窗口条数。
 *
 * 分块查询会把**整个窗口**逐条解密后才返回，故单次明文峰值 = 窗口条数 ×
 * [ClipboardStore.MAX_ITEM_BYTES]（单条上限 256KB）。50 条 → 12.8MB，落在
 * [ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES]（16MB）内；原值 300 会到 76.8MB。
 * 调整本值后必须让 `ClipboardLimitsTest` 的窗口预算护栏通过。
 */
internal const val SEARCH_WINDOW_ITEMS = 50

/**
 * 顶部搜索面板（挂在候选栏上方，独立于剪贴板面板与 26 键区）。
 *
 * 进入搜索时：搜索面板显示在 IME 最顶部（整体高度增加），
 * 剪贴板面板退出、下方恢复正常 26 键；26 键与候选输入经
 * [appendSearch] 路由到搜索框，TextWatcher debounce 后在后台解密过滤剪贴板历史。
 * 布局（自上而下）：命中结果列表 → 搜索输入框 → 候选栏（键盘自身） → 26 键 → 空格底栏。
 *
 * 性能：
 *  - 输入只做 debounce 合并后的最终一次查询（[Debounce_MS]）；
 *  - 查库与加密字段解密均在 [BackgroundIo] 线程，主线程零阻塞；
 *  - 结果用 [ListView] 复用 item，支持独立滚动。
 *
 * 注意：[ClipboardPanelView] 是本类的**平行实现**（适配器 / 分页 / 空态 / 刷新令牌 / 首帧兜底
 * 各写一份），改这里必须同步那边，否则两个入口的列表行为会漂移。
 *
 * 安全：不输出任何剪贴板正文日志。
 *       （隐私标记与「掩码 + 点击展开」那套展示逻辑已随 v5 迁移整体移除，
 *       本类里不存在掩码分支——阅读时不要按「有掩码」假设。）
 */
class SearchPanelView(context: Context) : LinearLayout(context) {
    interface Listener {
        /** 点击结果：IME 用当前 InputConnection commitText。返回是否成功提交。 */
        fun onPaste(text: String): Boolean
        /** 关闭搜索面板（恢复正常 26 键） */
        fun onClose()
    }

    var listener: Listener? = null

    private val db by lazy { ClipboardDb.get(context) }

    private lateinit var editSearch: EditText
    private lateinit var listView: ListView
    private lateinit var textEmpty: TextView

    private var currentItems: List<ClipboardDb.Item> = emptyList()

    /** 快速点击去重：一次粘贴完成前忽略后续点击 */
    private var isPasting = false

    /** debounce + 刷新令牌：合并连续输入，丢弃过期回调 */
    private val searchHandler = Handler(Looper.getMainLooper())
    private var refreshToken = 0

    private val adapter = object : BaseAdapter() {
        override fun getCount() = currentItems.size
        override fun getItem(pos: Int) = currentItems[pos]
        override fun getItemId(pos: Int) = currentItems[pos].id
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = currentItems[pos]
            val recycledHolder = convertView?.tag as? Holder
            val (root, holder) = if (convertView != null && recycledHolder != null) {
                convertView to recycledHolder
            } else {
                val v = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = android.graphics.drawable.ColorDrawable(context.getColor(R.color.card_bg))
                }
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                val content = TextView(context).apply {
                    textSize = 15f
                    setTextColor(context.getColor(R.color.text_primary))
                    setMaxLines(1)
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                }
                val meta = TextView(context).apply {
                    textSize = 11f
                    setTextColor(context.getColor(R.color.text_secondary))
                    setPadding(0, dp(3), 0, 0)
                }
                row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                v.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val newHolder = Holder(content, meta)
                v.tag = newHolder
                v to newHolder
            }
            holder.itemId = item.id
            holder.content.text = item.content
            holder.meta.text = buildString {
                append(SDF.format(Date(item.createdAt)))
                if (item.category != "OTHER") append(" · ").append(item.category)
                if (item.isFavorite) append(" · 收藏")
            }
            return root
        }
    }

    private class Holder(val content: TextView, val meta: TextView) {
        var itemId: Long = -1L
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.app_bg))
        setPadding(dp(10), dp(8), dp(10), dp(8))

        // 结果列表（可滚动，固定高度保证在 wrap_content 父下可滚动）
        listView = ListView(context).apply {
            divider = null
            setBackgroundColor(context.getColor(R.color.app_bg))
            adapter = this@SearchPanelView.adapter
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            val item = currentItems.getOrNull(pos)
            if (item != null) handleItemClick(item)
        }
        addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(RESULT_HEIGHT_DP)))

        // 空态
        textEmpty = TextView(context).apply {
            text = "输入关键词搜索剪贴板历史"
            gravity = android.view.Gravity.CENTER
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
        }
        addView(textEmpty, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(RESULT_EMPTY_HEIGHT_DP)))

        // 搜索输入框 + 退出搜索按钮（同一行；输入框 weight=1，退出按钮右侧固定）。
        // 关键：baselineAligned 默认 true，会按文本 baseline 对齐（字体/padding 不同即偏移），
        // 关闭后改按顶部对齐，各方 gravity 用 CENTER_VERTICAL + includeFontPadding=false
        // 保证文字几何垂直居中，输入框与按钮外观严格对齐。
        val searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = false
        }
        editSearch = EditText(context).apply {
            hint = "搜索剪贴板历史"
            setSingleLine(true)
            setIncludeFontPadding(false)
            textSize = 13f
            isFocusableInTouchMode = true
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setBackgroundColor(context.getColor(R.color.surface_hi))
            setPadding(dp(10), 0, dp(10), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // debounce：取消未执行的上一次查询，合并连续输入只执行最终一次
                    searchHandler.removeCallbacksAndMessages(null)
                    searchHandler.postDelayed({ runSearch() }, DEBOUNCE_MS)
                }
            })
        }
        searchRow.addView(editSearch, LinearLayout.LayoutParams(0, dp(46), 1f))
        // 退出搜索：矩形、与搜索框同高同色、水平居中对齐，视觉浑然一体
        val btnExit = TextView(context).apply {
            // 严格两行排版：退出 / 搜索（按钮窄，单行会挤压或省略）
            text = "退出\n搜索"
            gravity = android.view.Gravity.CENTER
            setIncludeFontPadding(false)
            setLineSpacing(0f, 0.95f)
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 12f
            setBackgroundColor(context.getColor(R.color.surface_hi))
            isClickable = true
            setOnClickListener { listener?.onClose() }
        }
        searchRow.addView(btnExit, LinearLayout.LayoutParams(WRAP_EXIT_DP, dp(46)).apply { marginStart = dp(6) })
        addView(searchRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 显示搜索面板：清空输入、对齐结果列表/空态可见性、聚焦输入框 */
    fun onShown() {
        isPasting = false
        // 每次重新打开都清空「已展开」集合，避免上次的状态残留
        searchHandler.removeCallbacksAndMessages(null)
        // Invalidate a search that may still be decrypting after the previous session closed.
        refreshToken++
        editSearch.setText("")
        searchHandler.removeCallbacksAndMessages(null)
        textEmpty.visibility = View.VISIBLE
        listView.visibility = View.GONE
        currentItems = emptyList()
        // 布局稳定后聚焦输入框；IME 内不强拨系统软键盘
        post {
            editSearch.requestFocus()
            hideSystemSoftKeyboard()
        }
        Diagnostics.i(TAG, "搜索面板: 显示并聚焦")
    }

    fun onHidden() {
        isPasting = false
        searchHandler.removeCallbacksAndMessages(null)
        // Prevent an old worker from repopulating the list after the panel is hidden.
        refreshToken++
        editSearch.clearFocus()
        editSearch.setText("")
        searchHandler.removeCallbacksAndMessages(null)
        currentItems = emptyList()
        Diagnostics.i(TAG, "搜索面板: 隐藏")
    }

    /** 是否激活（PinyinKeyboardView 据此把 26 键输入路由到搜索框） */
    fun isActive(): Boolean = visibility == View.VISIBLE

    /** 键盘输入路由：向搜索框追加文本（走 TextWatcher → debounce 查询） */
    fun appendSearch(text: String) {
        val cur = editSearch.text ?: return
        cur.append(text)
        editSearch.setSelection(cur.length)
    }

    /** 键盘输入路由：删搜索框末尾一个字符 */
    fun backspaceSearch() {
        val cur = editSearch.text ?: return
        if (cur.isNotEmpty()) {
            cur.delete(cur.length - 1, cur.length)
        }
    }

    private fun handleItemClick(item: ClipboardDb.Item) {
        if (isPasting) return
        isPasting = true
        val ok = listener?.onPaste(item.content) ?: false
        isPasting = false
        if (ok) {
            listener?.onClose()
        } else {
            Diagnostics.w(TAG, "搜索结果粘贴: id=${item.id} 失败")
        }
    }

    /**
     * 分块渐进搜索：按块解密扫描（新→旧），每块命中立即发布到 UI。
     * 首块结果即刻可见，大库下无需等全部解密完成；令牌失效即中止。
     */
    private fun runSearch() {
        val q = editSearch.text?.toString()?.trim() ?: ""
        val reqToken = ++refreshToken
        BackgroundIo.run {
            if (q.isEmpty()) {
                post {
                    if (reqToken != refreshToken) return@post
                    currentItems = emptyList()
                    adapter.notifyDataSetChanged()
                    updateEmpty()
                }
                return@run
            }
            val lower = q.lowercase()
            val matches = ArrayList<ClipboardDb.Item>()
            // 扫描上限取**真实行数**：maxItems 只是配置项，而 trimTo 只裁非收藏，
            // 收藏多时实际行数会超过它 —— 拿配置值当上限会漏搜尾部条目。
            // count 是纯 SQL 计数、不解密，成本可忽略；再叠一个硬保护防超大库拖慢。
            val total = db.count().coerceAtMost(SEARCH_SCAN_LIMIT)
            var offset = 0
            while (offset < total) {
                val chunk = db.recentPage(offset, SEARCH_WINDOW_ITEMS)
                if (chunk.isEmpty()) break
                for (item in chunk) {
                    if (item.content.lowercase().contains(lower)) matches.add(item)
                }
                offset += chunk.size
                if (reqToken != refreshToken) return@run
                // 首帧兜底的判据必须在 post 之前固化成**值**：lambda 捕获的是变量本身，
                // 等它延迟执行时 offset 早已推进，「首块」永远判不成立。
                val isFirstChunk = offset <= SEARCH_WINDOW_ITEMS
                val snapshot = matches.toList()
                post {
                    if (reqToken != refreshToken) return@post
                    currentItems = snapshot
                    adapter.notifyDataSetChanged()
                    updateEmpty()
                    // 首帧布局竞态兜底（与历史页一致）
                    if (isFirstChunk) listView.post { forceRelayout(reqToken) }
                }
            }
            Diagnostics.i(TAG, "搜索完成: \"$q\" 命中=${matches.size}")
        }
    }

    /** 首帧兜底用的强制重绘：等布局稳定后再刷一次，令牌过期则跳过 */
    private fun forceRelayout(reqToken: Int) {
        if (reqToken != refreshToken) return
        adapter.notifyDataSetChanged()
        listView.requestLayout()
        listView.invalidate()
    }

    private fun updateEmpty() {
        val empty = currentItems.isEmpty()
        // 非空时列表可见、空态 GONE——「找到 N 条」根本无处显示，
        // 原实现在这里给它赋了值却随即隐藏，属无效逻辑，只保留空态文案。
        textEmpty.text = "未找到匹配内容\n换个关键词试试"
        textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        listView.requestLayout()
        listView.invalidate()
    }

    private fun hideSystemSoftKeyboard() {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(editSearch.windowToken, 0)
        }.onFailure { }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "SearchPanel"
        const val DEBOUNCE_MS = 150L
        /** 单次搜索最多扫描的行数（硬保护，防超大库把搜索拖成秒级） */
        const val SEARCH_SCAN_LIMIT = 20_000
        /** 结果列表固定高度（wrap_content 父下保证可滚动） */
        const val RESULT_HEIGHT_DP = 220
        /** 空态占位高度 */
        const val RESULT_EMPTY_HEIGHT_DP = 120
        /** 退出搜索按钮宽度 */
        const val WRAP_EXIT_DP = 88
        val SDF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
