package com.jinn.voiceinput

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 输入法内剪贴板面板（重构版：替换 26 键字母区，候选栏/底部栏保持）。
 *
 * 作为 PinyinKeyboardView 内容区的一个子 view，激活时与字母区互斥显示，
 * IME 全程持有 InputConnection——点击记录经 [listener.onPaste] 回传 IME
 * 用当前连接 commitText，成功后才关闭面板。
 *
 * 功能：
 *  - 分类栏：全部 / 网址 / 数字 / 收藏（收藏为独立标签）
 *  - 动态 UI 序号（最新=最大，删除/去重后重排，非数据库 ID）
 *  - 点击记录粘贴 + 成功关闭面板（失败不关闭，快速点击去重）
 *  - 清理重复（严格字符串比较，只保留最新）
 *  - 长按：收藏 / 删除
 *  - 搜索按钮：回调 [Listener.onSearch]，由 PinyinKeyboardView 关闭本面板并
 *    显示顶部搜索面板（搜索面板独立于剪贴板面板，见 SearchPanelView）
 *  - 空分类显示空态，绝不触发返回
 *
 * 身份不变量：点击用稳定 itemId 查找，不依赖 position；
 * 越界/已删除/已去重一律安全忽略。
 * 安全：不输出任何剪贴板正文日志。
 */
class ClipboardPanelView(context: Context) : LinearLayout(context) {

    /** 面板回调（全部主线程） */
    interface Listener {
        /** 点击记录：IME 用当前 InputConnection 粘贴文本。返回是否成功提交。 */
        fun onPaste(text: String): Boolean
        /** 关闭面板，恢复原输入法键盘 */
        fun onClose()
        /** 请求搜索：关闭剪贴板面板，显示顶部搜索面板（恢复正常 26 键） */
        fun onSearch()
    }

    var listener: Listener? = null

    private val db by lazy { ClipboardDb.get(context) }
    private val prefs by lazy { ClipboardPrefs.of(context) }

    private lateinit var listView: ListView
    private lateinit var textEmpty: TextView
    private lateinit var btnCategoryAll: TextView
    private lateinit var btnCategoryUrl: TextView
    private lateinit var btnCategoryNumber: TextView
    private lateinit var btnCategoryFavorite: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnSearch: TextView
    private lateinit var btnClear: TextView

    /** 内联操作条（长按条目时显示，替代 AlertDialog——IME 内嵌面板无窗口 token） */
    private lateinit var actionBar: LinearLayout
    private lateinit var actionFavorite: TextView
    private lateinit var actionDelete: TextView
    private var longPressItem: ClipboardDb.Item? = null

    /** 清空二次确认条（IME 内无窗口 token，用内联确认替代 AlertDialog） */
    private lateinit var confirmBar: LinearLayout

    private var currentCategory: String? = null
    private var currentItems: List<ClipboardDb.Item> = emptyList()

    /** 快速点击去重：一次粘贴完成前忽略后续点击 */
    private var isPasting = false

    /** 防滚动残留：切分类/去重后 post 滚回顶部，旧 post 任务无效化 */
    private var scrollToken = 0

    /** 刷新请求令牌：异步查询完成时若已过期（又有新请求）则丢弃，防止旧结果覆盖新状态 */
    private var refreshToken = 0

    /** 本次打开的 Trace ID（onPanelShown 生成，刷新链路共享） */
    private var currentTraceId: String = ""

    // ── 适配器（稳定 ID 绑定）──────────────────────────────
    // 必须声明在 init 块之前！Kotlin 属性按声明顺序初始化，
    // init{ buildUi() } 里 listView.adapter 若声明在后面会取到 null。
    private val adapter = object : BaseAdapter() {
        override fun getCount() = currentItems.size
        override fun getItem(pos: Int) = currentItems[pos]
        override fun getItemId(pos: Int) = currentItems[pos].id
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = currentItems[pos]
            val holder = convertView?.tag as? Holder
            val root = convertView ?: run {
                val v = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = android.graphics.drawable.ColorDrawable(Color.parseColor("#141C33"))
                }
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                val num = TextView(context).apply {
                    textSize = 16f
                    setTextColor(Color.parseColor("#4C8DFF"))
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    setPadding(0, 0, dp(10), 0)
                }
                val content = TextView(context).apply {
                    textSize = 16f
                    setTextColor(Color.parseColor("#ECEEF2"))
                    setMaxLines(1)          // 统一单行显示，超出一行用省略号
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                }
                val meta = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding(0, dp(4), 0, 0)
                }
                row.addView(num, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                v.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.addView(meta, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.tag = Holder(num, content, meta)
                v
            }
            val h = holder ?: return root
            h.itemId = item.id  // 身份绑定：每次渲染写稳定 ID，复用 View 时更新
            h.num.text = (currentItems.size - pos).toString()
            h.content.text = item.content
            h.meta.text = buildString {
                append(SDF.format(Date(item.createdAt)))
                if (item.category != "OTHER") append(" · ").append(item.category)
                if (item.isFavorite) append(" · 收藏")
            }
            return root
        }
    }

    private class Holder(val num: TextView, val content: TextView, val meta: TextView) {
        /** 当前绑定的稳定 Item ID（每次渲染更新） */
        var itemId: Long = -1L
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0B1020"))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        buildUi()
    }

    private fun buildUi() {
        // ── 顶栏：返回 + 全部/网址/数字/收藏 + 搜索 + 清空，均分（weight=1）──
        val topRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        btnBack = tabButton("返回") { listener?.onClose() }
        btnCategoryAll = tabButton("全部") { selectCategory(null) }
        btnCategoryUrl = tabButton("网址") { selectCategory(ClipboardClassifier.CATEGORY_URL) }
        btnCategoryNumber = tabButton("数字") { selectCategory(ClipboardClassifier.CATEGORY_NUMBER) }
        btnCategoryFavorite = tabButton("收藏") { selectCategory(CATEGORY_FAVORITE) }
        btnSearch = tabButton("搜索") { listener?.onSearch() }
        btnClear = tabButton("清空") { showClearConfirm() }
            .apply { setTextColor(Color.parseColor("#E5484D")) }
        val cells = listOf(btnBack, btnCategoryAll, btnCategoryUrl, btnCategoryNumber, btnCategoryFavorite, btnSearch, btnClear)
        for (cell in cells) {
            topRow.addView(cell, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        addView(topRow, lp())

        // ── 列表（占据面板主要空间，可滚动） ──
        listView = ListView(context).apply {
            divider = null
            setBackgroundColor(Color.parseColor("#0B1020"))
            adapter = this@ClipboardPanelView.adapter
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            val item = currentItems.getOrNull(pos)
            if (item != null) handleItemClick(item)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val item = currentItems.getOrNull(pos)
            if (item != null) showItemMenu(item)
            true
        }
        addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 空状态 ──
        textEmpty = TextView(context).apply {
            text = "暂无剪贴板历史\n复制内容后将自动保存"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            visibility = GONE
        }
        addView(textEmpty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 长按操作条（收藏/删除） ──
        actionBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        actionFavorite = tabButton("收藏") { toggleFavorite() }
        actionDelete = tabButton("删除") { deleteItem() }
        actionDelete.setTextColor(Color.parseColor("#E5484D"))
        actionBar.addView(actionFavorite, LinearLayout.LayoutParams(0, dp(36), 1f))
        actionBar.addView(actionDelete, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(actionBar, lp())

        // ── 清空二次确认条 ──
        confirmBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val confirmText = tabButton("确认清空全部历史？", onClick = { }).apply {
            textSize = 12f
        }
        val confirmOk = tabButton("确定") {
            hideConfirmBar()
            BackgroundIo.run {
                db.deleteAll()  // 数据层保护：只删普通记录，收藏保留
                post { refresh(resetScroll = true) }
            }
        }.apply { setTextColor(Color.parseColor("#E5484D")) }
        val confirmCancel = tabButton("取消") { hideConfirmBar() }
        confirmBar.addView(confirmText, LinearLayout.LayoutParams(0, dp(36), 2f))
        confirmBar.addView(confirmOk, LinearLayout.LayoutParams(0, dp(36), 1f))
        confirmBar.addView(confirmCancel, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(confirmBar, lp())
    }

    /** 面板显示时刷新（每次打开立即读取 DB 生成快照，不做去重维护）。 */
    fun onPanelShown() {
        isPasting = false
        hideActionBar()
        hideConfirmBar()
        currentTraceId = Diagnostics.traceId("CLIP")
        Diagnostics.i(TAG, "[$currentTraceId] OPEN onPanelShown thread=${Thread.currentThread().name}")
        // 规范：每次打开重置分类为「全部」，绝不残留上次状态
        selectCategory(null)
    }

    // ── 分类 ──────────────────────────────────────────────

    private fun selectCategory(category: String?) {
        currentCategory = category
        val selected = currentCategory
        for (tab in listOf(btnCategoryAll, btnCategoryUrl, btnCategoryNumber, btnCategoryFavorite)) {
            val isSel = when (tab) {
                btnCategoryAll -> selected == null
                btnCategoryUrl -> selected == ClipboardClassifier.CATEGORY_URL
                btnCategoryNumber -> selected == ClipboardClassifier.CATEGORY_NUMBER
                else -> selected == CATEGORY_FAVORITE
            }
            tab.setBackgroundResource(if (isSel) R.drawable.key_bg_active else R.drawable.key_bg)
        }
        refresh(resetScroll = true)
    }

    // ── 刷新 / 序号 ──────────────────────────────────────

    /** 重新加载当前分类的列表。查询与加解密在后台线程，主线程只提交不可变快照。 */
    private fun refresh(resetScroll: Boolean = false) {
        val tid = currentTraceId.ifEmpty { Diagnostics.traceId("CLIP") }
        currentTraceId = tid
        val category = currentCategory
        val maxItems = prefs.maxItems
        val reqToken = ++refreshToken
        BackgroundIo.run {
            val all = db.recent(maxItems)
            val filtered = when {
                category == CATEGORY_FAVORITE -> db.recentFavorites(maxItems)
                category != null -> db.recent(maxItems, category)
                else -> all
            }
            Diagnostics.i(
                TAG,
                "[$tid] DB category=${category ?: "ALL"} all=${all.size} vis=${filtered.size} " +
                    "thread=${Thread.currentThread().name}",
            )
            post {
                if (reqToken != refreshToken) return@post
                currentItems = filtered
                adapter.notifyDataSetChanged()
                updateEmpty()
                // 首帧布局竞态兜底：异步回填可能发生在 ListView 首次布局完成前，
                // 单次 notify 不足以让 item 创建；等布局稳定后二次强制重绘。
                listView.post {
                    if (reqToken != refreshToken) return@post
                    adapter.notifyDataSetChanged()
                    listView.requestLayout()
                    listView.invalidate()
                }
                if (resetScroll && currentItems.isNotEmpty()) {
                    val token = ++scrollToken
                    listView.post { if (token == scrollToken) listView.setSelection(0) }
                }
            }
        }
    }

    /** 空态与列表可见性切换（GONE→VISIBLE 后强制重布局，杜绝有高有数但空白） */
    private fun updateEmpty() {
        val empty = currentItems.isEmpty()
        textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        listView.requestLayout()
        listView.invalidate()
    }

    // ── 点击粘贴 / 长按菜单 ────────────────────────────────

    private fun handleItemClick(item: ClipboardDb.Item) {
        if (isPasting) return
        isPasting = true
        val ok = listener?.onPaste(item.content) ?: false
        isPasting = false
        if (ok) {
            listener?.onClose()
        } else {
            Diagnostics.w(TAG, "点击粘贴: id=${item.id} 失败，保持面板")
            Toast.makeText(context, "粘贴失败，请确认输入框可用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 长按条目：显示内联操作条（收藏/删除）。IME 内无窗口 token，不用 AlertDialog。 */
    private fun showItemMenu(item: ClipboardDb.Item) {
        longPressItem = item
        actionFavorite.text = if (item.isFavorite) "取消收藏" else "收藏"
        actionBar.visibility = View.VISIBLE
        Diagnostics.i(TAG, "[$currentTraceId] 长按菜单: 显示操作条 id=${item.id}")
    }

    private fun hideActionBar() {
        actionBar.visibility = View.GONE
        longPressItem = null
    }

    private fun toggleFavorite() {
        val item = longPressItem ?: return
        db.setFavorite(item.id, !item.isFavorite)
        Diagnostics.i(TAG, "[$currentTraceId] 长按操作: 收藏切换 id=${item.id}")
        hideActionBar()
        refresh()
    }

    private fun deleteItem() {
        val item = longPressItem ?: return
        db.delete(item.id)
        Diagnostics.i(TAG, "[$currentTraceId] 长按操作: 删除 id=${item.id}")
        hideActionBar()
        refresh(resetScroll = true)
    }

    // ── 清空二次确认 ──────────────────────────────────────

    private fun showClearConfirm() {
        confirmBar.visibility = View.VISIBLE
        hideActionBar()
    }

    private fun hideConfirmBar() {
        confirmBar.visibility = View.GONE
    }

    // ── UI 辅助 ───────────────────────────────────────────

    private fun tabButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#ECEEF2"))
            textSize = 12f
            setBackgroundResource(R.drawable.key_bg)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ClipboardPanel"
        const val CATEGORY_FAVORITE = "FAVORITE"
        val SDF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}