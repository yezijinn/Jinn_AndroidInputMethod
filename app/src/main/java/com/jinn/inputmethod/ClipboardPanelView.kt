package com.jinn.inputmethod

import android.content.Context
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
 *  - 分类栏：全部 / 网址 / 数字 / 收藏（收藏为独立标签，可与分类并存）
 *  - 隐私标记入口已移除（2026-09-16）；历史隐私条目仍默认掩码，点击一次展开、再点才粘贴
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

    private lateinit var listView: ListView
    private lateinit var textEmpty: TextView
    private lateinit var btnCategoryAll: TextView
    private lateinit var btnCategoryUrl: TextView
    private lateinit var btnCategoryNumber: TextView
    private lateinit var btnCategoryFavorite: TextView
    private lateinit var btnBack: TextView

    /**
     * 已「点击显示明文」的隐私条目 ID（仅内存态，面板关闭即失效）。
     * 隐私条目默认掩码，第一次点击展开明文，第二次点击才真正粘贴。
     */
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
    private var currentItems: MutableList<ClipboardDb.Item> = mutableListOf()

    /** 当前分类的总条数（分页编号用：序号 = 分类总数 − 位置，与全局条目数无关） */
    private var categoryTotal = 0

    /** 已加载的偏移量（= currentItems.size）与是否还有下一页 */
    private var hasMorePages = false

    /** 分页加载是否进行中（滚动到底触发时防重入） */
    private var loadingPage = false

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
            val recycledHolder = convertView?.tag as? Holder
            val (root, holder) = if (convertView != null && recycledHolder != null) {
                convertView to recycledHolder
            } else {
                val v = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = android.graphics.drawable.ColorDrawable(context.getColor(R.color.card_bg))
                }
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                val num = TextView(context).apply {
                    textSize = 16f
                    setTextColor(context.getColor(R.color.accent))
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    setPadding(0, 0, dp(10), 0)
                }
                val content = TextView(context).apply {
                    textSize = 16f
                    setTextColor(context.getColor(R.color.text_primary))
                    setMaxLines(1)          // 统一单行显示，超出一行用省略号
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                }
                val meta = TextView(context).apply {
                    textSize = 11f
                    setTextColor(context.getColor(R.color.text_secondary))
                    setPadding(0, dp(4), 0, 0)
                }
                row.addView(num, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                v.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.addView(meta, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val newHolder = Holder(num, content, meta)
                v.tag = newHolder
                v to newHolder
            }
            holder.itemId = item.id  // 身份绑定：每次渲染写稳定 ID，复用 View 时更新
            holder.num.text = (categoryTotal - pos).toString()
            holder.content.text = item.content
            holder.meta.text = buildString {
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
        setBackgroundColor(context.getColor(R.color.app_bg))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        buildUi()
    }

    private fun buildUi() {
        // ── 顶栏：返回 + 全部/网址/数字/收藏/隐私 + 搜索 + 清空，均分（weight=1）──
        val topRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        btnBack = tabButton("返回") { listener?.onClose() }
        btnCategoryAll = tabButton("全部") { selectCategory(null) }
        btnCategoryUrl = tabButton("网址") { selectCategory(ClipboardClassifier.CATEGORY_URL) }
        btnCategoryNumber = tabButton("数字") { selectCategory(ClipboardClassifier.CATEGORY_NUMBER) }
        btnCategoryFavorite = tabButton("收藏") { selectCategory(CATEGORY_FAVORITE) }
        btnSearch = tabButton("搜索") { listener?.onSearch() }
        btnClear = tabButton("清空") { showClearConfirm() }
            .apply { setTextColor(context.getColor(R.color.danger)) }
        val cells = listOf(btnBack, btnCategoryAll, btnCategoryUrl, btnCategoryNumber,
            btnCategoryFavorite, btnSearch, btnClear)
        for (cell in cells) {
            topRow.addView(cell, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        addView(topRow, lp())

        // ── 列表（占据面板主要空间，可滚动） ──
        listView = ListView(context).apply {
            divider = null
            setBackgroundColor(context.getColor(R.color.app_bg))
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
        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {}
            override fun onScroll(view: android.widget.AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                // 距底部不足 LOAD_AHEAD 条时预取下一页
                if (hasMorePages && totalItemCount > 0 &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - LOAD_AHEAD) {
                    loadNextPage()
                }
            }
        })
        addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 空状态 ──
        textEmpty = TextView(context).apply {
            text = "暂无剪贴板历史\n复制内容后将自动保存"
            gravity = android.view.Gravity.CENTER
            setTextColor(context.getColor(R.color.text_secondary))
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
        actionDelete.setTextColor(context.getColor(R.color.danger))
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
        }.apply { setTextColor(context.getColor(R.color.danger)) }
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
        // 每次重新打开都清空「已展开明文」：隐私条目必须重新点一次才能看到内容，
        // 否则上次展开的状态会跨会话残留，等于隐私掩码形同虚设
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
                btnCategoryFavorite -> selected == CATEGORY_FAVORITE
                else -> false
            }
            tab.setBackgroundResource(if (isSel) R.drawable.key_bg_active else R.drawable.key_bg)
        }
        refresh(resetScroll = true)
    }

    // ── 分页加载 ─────────────────────────────────────────

    /** 重新加载当前分类的列表（第一页）。查询与加解密在后台线程，主线程只提交快照。 */
    private fun refresh(resetScroll: Boolean = false) {
        val tid = currentTraceId.ifEmpty { Diagnostics.traceId("CLIP") }
        currentTraceId = tid
        val category = currentCategory
        val reqToken = ++refreshToken
        loadingPage = true
        BackgroundIo.run {
            // 分页加载：COUNT 不解密，解密只覆盖第一页（PAGE_SIZE）
            val filter = ClipboardFilter.of(category)
            val total = db.count(filter.category, filter.favoritesOnly)
            val page = db.recentPage(0, PAGE_SIZE, filter.category, filter.favoritesOnly)
            Diagnostics.i(
                TAG,
                "[$tid] DB category=${category ?: "ALL"} total=$total page=${page.size} " +
                    "thread=${Thread.currentThread().name}",
            )
            post {
                if (reqToken != refreshToken) return@post
                loadingPage = false
                categoryTotal = total
                currentItems = page.toMutableList()
                hasMorePages = page.size < total
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

    /** 滚动接近底部时加载下一页并追加（解密只覆盖本页） */
    private fun loadNextPage() {
        if (loadingPage || !hasMorePages) return
        val category = currentCategory
        val offset = currentItems.size
        val reqToken = refreshToken
        loadingPage = true
        BackgroundIo.run {
            val filter = ClipboardFilter.of(category)
            val page = db.recentPage(offset, PAGE_SIZE, filter.category, filter.favoritesOnly)
            post {
                if (reqToken != refreshToken) return@post
                loadingPage = false
                if (page.isEmpty()) {
                    hasMorePages = false
                    return@post
                }
                currentItems.addAll(page)
                hasMorePages = currentItems.size < categoryTotal
                adapter.notifyDataSetChanged()
                Diagnostics.i(TAG, "分页加载: offset=$offset +${page.size} hasMore=$hasMorePages")
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
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 12f
            setBackgroundResource(R.drawable.key_bg)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ClipboardPanel"
        // 常量统一取自 ClipboardFilter，避免 UI 与数据层各定义一份而漂移
        const val CATEGORY_FAVORITE = ClipboardFilter.PSEUDO_FAVORITE
        /** 每页条数（解密只覆盖可见窗口） */
        const val PAGE_SIZE = 50
        /** 距底部还有多少条时预取下一页 */
        const val LOAD_AHEAD = 10
        val SDF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
