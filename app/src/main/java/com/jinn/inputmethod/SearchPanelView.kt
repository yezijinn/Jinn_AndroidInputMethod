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
 * 分块查询会把**整个窗口**逐条解密后才返回，故单次内存峰值 ≈ 窗口条数 × 单条上限 × 放大系数
 * （见 [ClipboardStore.decryptWindowPeakBytes]，放大是为了计入 base64 密文与 UTF-16 String）。
 * 50 条 ≈ 32.8MB（最坏情形，实测常驻约 29.8MB），原值 300 会到 ≈196MB。
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
            // 条目卡按**当前**透明度档设色（ListView 复用 convertView，不每次重设会混新旧两档）
            root.background = android.graphics.drawable.ColorDrawable(
                KeyTransparency.withAlpha(context.getColor(R.color.card_bg), surfaceAlpha)
            )
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

    /** 半透明键盘：本面板内容面（条目卡）的当前档位（1f = 不透明） */
    private var surfaceAlpha = 1f

    /**
     * 半透明键盘：本面板没有 drawable 键面按钮（根/列表 `app_bg`、搜索框 `surface_hi` 由键盘侧的
     * 纯色面扫描统一套档）；条目卡在 [adapter] 的 getView 里按档设色，故这里只记录档位并通知
     * 列表重建，与 [ClipboardPanelView.applySurfaceAlpha] 同款。
     */
    fun applySurfaceAlpha(alpha: Float) {
        if (alpha == surfaceAlpha) return
        surfaceAlpha = alpha
        adapter.notifyDataSetChanged()
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
        // 与 ClipboardPanelView 同款身份取值：优先用被点中那行渲染时的稳定 id，
        // 只按下标取会在「结果已被新一次搜索替换、这一帧还没重绘」的窗口里粘错条目。
        fun itemAt(pos: Int, view: View?): ClipboardDb.Item? {
            val renderedId = (view?.tag as? Holder)?.itemId ?: -1L
            return currentItems.firstOrNull { it.id == renderedId } ?: currentItems.getOrNull(pos)
        }
        listView.setOnItemClickListener { _, view, pos, _ ->
            itemAt(pos, view)?.let { handleItemClick(it) }
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

        // 搜索输入框（**独占一行**）。
        // 退出搜索的入口都在键盘侧，本面板内不放按钮：候选栏「退出」按钮 / 搜索态回车 /
        // BACK 收起键盘；粘贴结果成功后由 [handleItemClick] 走 [Listener.onClose] 自动关闭。
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
        addView(editSearch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
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

    /** 键盘输入路由：清空搜索框（顶部的清空手势落在搜索框上，绝不碰宿主输入框） */
    fun clearSearch() {
        val cur = editSearch.text ?: return
        if (cur.isNotEmpty()) cur.clear()
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
            var lastPublishAt = 0L
            var publishedCount = -1

            /** 发布一次结果快照（主线程） */
            fun publish(items: List<ClipboardDb.Item>, relayout: Boolean) {
                publishedCount = items.size
                post {
                    if (reqToken != refreshToken) return@post
                    currentItems = items
                    adapter.notifyDataSetChanged()
                    updateEmpty()
                    // 首帧布局竞态兜底（与历史页一致）
                    if (relayout) listView.post { forceRelayout(reqToken) }
                }
            }

            var retainedBytes = 0L
            var capped = false
            while (offset < total) {
                // 游标必须取回带的 nextOffset：解密失败的行不进结果但仍占游标位，
                // 用 chunk.size 推进会让下一块重复扫描已看过的行、并永久漏掉尾部行。
                val page = db.recentPageWithOffset(offset, SEARCH_WINDOW_ITEMS)
                for (item in page.items) {
                    if (item.content.lowercase().contains(lower)) {
                        if (ClipboardStore.searchRetainLimitReached(matches.size, retainedBytes)) {
                            capped = true
                            break
                        }
                        matches.add(item)
                        // 按 UTF-8 字节计（不是字符数）：中文 1 字符 = 3 字节，
                        // 用 length 会让 24MB 驻留预算在中文下被低估到 1/3，护栏失效。
                        retainedBytes += ClipboardStore.utf8ByteSize(item.content)
                    }
                }
                // 判停只看游标有没有前进：整窗解密失败时 items 为空、但游标仍在推进，
                // 用 items.isEmpty() 判停会静默漏掉后面的有效条目。
                val next = page.nextOffset
                if (next <= offset) break
                offset = next
                if (capped) break
                if (reqToken != refreshToken) return@run
                // 首帧兜底的判据必须在 post 之前固化成**值**：lambda 捕获的是变量本身，
                // 等它延迟执行时 offset 早已推进，「首块」永远判不成立。
                val isFirstChunk = offset <= SEARCH_WINDOW_ITEMS
                val now = System.currentTimeMillis()
                // 发布节流：窗口细化到 50 条后，逐块发布会让大库搜索产生数百次布局
                //（每次 updateEmpty 都会 requestLayout）。首块必发保首屏，其余按间隔合并。
                if (isFirstChunk || now - lastPublishAt >= PUBLISH_MIN_INTERVAL_MS) {
                    lastPublishAt = now
                    publish(matches.toList(), relayout = isFirstChunk)
                }
            }
            // 收尾无条件补发：节流可能吞掉最后一块，这里保证"最终结果一定落地"，
            // 否则用户会看到少于实际命中的结果（静默少给）。命中数没变时跳过，避免重复布局。
            if (publishedCount != matches.size) publish(matches.toList(), relayout = false)
            // 关键词是用户输入正文：走 V 级（默认只进 logcat 不落盘），与「日志禁出正文」一致
            Diagnostics.v(TAG, "搜索完成: \"$q\" 命中=${matches.size} capped=$capped")
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
        /** 结果发布的最小间隔：窗口细化后逐块发布会产生数百次布局，按此间隔合并 */
        const val PUBLISH_MIN_INTERVAL_MS = 120L
        /** 结果列表固定高度（wrap_content 父下保证可滚动） */
        const val RESULT_HEIGHT_DP = 220
        /** 空态占位高度 */
        const val RESULT_EMPTY_HEIGHT_DP = 120
        val SDF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
