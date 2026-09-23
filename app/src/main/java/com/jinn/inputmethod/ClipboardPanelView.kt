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
 * IME 全程持有 InputConnection，点击记录经 [listener.onPaste] 回传 IME
 * 用当前连接 commitText，成功后才关闭面板。
 *
 * 功能：
 *  - 分类栏：全部 / 网址 / 数字 / 收藏（收藏为独立标签，可与分类并存）
 *  - 隐私标记随 v5 迁移移除（2026-09-16），条目不再走掩码逻辑
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
 *
 * [SearchPanelView] 是本类的平行实现（适配器 / 分页 / 空态 / 刷新令牌 / 首帧兜底
 * 各写一份），改这里必须同步那边，否则两个入口的列表行为会漂移。
 *
 * 安全：不输出任何剪贴板正文日志。
 */
/**
 * 剪贴板面板的一页条数。
 *
 * 与搜索窗口同理：一页会整页解密后才发布，单次内存峰值 ≈ 本值 × 单条上限 × 放大系数
 * （见 [ClipboardStore.decryptWindowPeakBytes]）。50 条 ≈ 32.8MB（最坏情形），
 * 在 [ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES]（48MB）内，由 `ClipboardLimitsTest` 守卫。
 */
internal const val PANEL_PAGE_ITEMS = 50

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

    /**
     * 当前键盘皮肤（默认皮肤 ⇒ 全部走 `R.color` 令牌，配色与历史一致）。
     *
     * 必须在 [init] 之前声明：`buildUi()` 会读它取面板配色。
     */
    private var skin: KeyboardSkin = KeyboardSkins.DEFAULT

    /** [tabButton] 创建的全部按钮（顶栏 / 操作条 / 确认条）：换皮肤时统一重设文字与面 */
    private val tabButtons = mutableListOf<TextView>()

    /** 危险语义按钮（清空 / 删除 / 确定）：文字保持 danger 红，不随皮肤换色 */
    private val dangerButtons = mutableListOf<TextView>()

    /**
     * 半透明键盘：本面板「键面」按钮的当前档位（1f = 不透明，与键盘键面一致）。
     *
     * 必须声明在 [init] 之前：`buildUi()` → `tabButton()` 会在构造期读它建面，
     * 声明晚了会读到 JVM 默认 `0f`，把按钮的面设成全透明（2026-09-23 审查发现的存量缺陷，
     * 表现为长按操作条 / 清空确认条的按钮没有键面）。
     */
    private var surfaceAlpha = 1f

    private lateinit var listView: ListView
    private lateinit var textEmpty: TextView
    private lateinit var btnCategoryAll: TextView
    private lateinit var btnCategoryUrl: TextView
    private lateinit var btnCategoryNumber: TextView
    private lateinit var btnCategoryFavorite: TextView
    private lateinit var btnBack: TextView

    private lateinit var btnSearch: TextView
    private lateinit var btnClear: TextView

    /** 内联操作条（长按条目时显示，替代 AlertDialog，IME 内嵌面板无窗口 token） */
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

    /**
     * 下一页的 SQL OFFSET。
     *
     * 必须取查询回带的原始行游标，不能拿 [currentItems] 的条数顶替，解密失败的行
     * 不进列表但仍占游标位，用条数当偏移会让下一页重复取到已显示的行、并把尾部行跳过。
     */
    private var nextPageOffset = 0

    /** 是否还有下一页 */
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
                    // 分类字段来自数据库（备份包可写入）：单行 + 省略号，防超长文本拖垮主线程布局
                    setMaxLines(1)
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
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
            // 条目卡按当前透明度档 + 皮肤面色设色：ListView 会复用 convertView，不每次重设的话，
            // 改档（拖滑杆）或换皮肤后再滚动列表会混着新旧两档的配色
            root.background = android.graphics.drawable.ColorDrawable(
                KeyTransparency.withAlpha(
                    skinColor(context, skin.functionFill, R.color.card_bg), surfaceAlpha,
                )
            )
            // 条目内文字同理：换皮肤后已渲染的行必须跟着变，不能只在首次构建时设一次
            holder.num.setTextColor(skinColor(context, skin.accent, R.color.accent))
            holder.content.setTextColor(skinColor(context, skin.functionGlyph, R.color.text_primary))
            holder.meta.setTextColor(skinColor(context, skin.functionHint, R.color.text_secondary))
            holder.itemId = item.id  // 身份绑定：每次渲染写稳定 ID，复用 View 时更新
            holder.num.text = (categoryTotal - pos).toString()
            holder.content.text = item.content
            holder.meta.text = buildString {
                append(formatTime(item.createdAt))
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
        setBackgroundColor(skinColor(context, skin.plate, R.color.app_bg))
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
            .apply { setTextColor(context.getColor(R.color.danger)) }
            .also { dangerButtons += it }
        val cells = listOf(btnBack, btnCategoryAll, btnCategoryUrl, btnCategoryNumber,
            btnCategoryFavorite, btnSearch, btnClear)
        for (cell in cells) {
            topRow.addView(cell, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        addView(topRow, lp())

        // ── 列表（占据面板主要空间，可滚动） ──
        listView = ListView(context).apply {
            divider = null
            setBackgroundColor(skinColor(context, skin.plate, R.color.app_bg))
            adapter = this@ClipboardPanelView.adapter
        }
        // 身份取值：优先用被点中那一行渲染时写入的稳定 id，取不到才退回当前列表下标。
        // 只按下标取，会在「列表已被刷新整体替换、这一帧还没重绘」的窗口里粘错条目 ，
        // 用户看到的仍是旧行的文字，取到的却是新列表同下标的条目（类头的身份不变量即此）。
        fun itemAt(pos: Int, view: View?): ClipboardDb.Item? {
            val renderedId = (view?.tag as? Holder)?.itemId ?: -1L
            return currentItems.firstOrNull { it.id == renderedId } ?: currentItems.getOrNull(pos)
        }
        listView.setOnItemClickListener { _, view, pos, _ ->
            itemAt(pos, view)?.let { handleItemClick(it) }
        }
        listView.setOnItemLongClickListener { _, view, pos, _ ->
            itemAt(pos, view)?.let { showItemMenu(it) }
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
            setTextColor(skinColor(context, skin.functionHint, R.color.text_secondary))
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
        dangerButtons += actionDelete
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
            .also { dangerButtons += it }
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
        // 每次重新打开都清空「已展开」集合，避免上次的状态跨会话残留
        currentTraceId = Diagnostics.traceId("CLIP")
        Diagnostics.i(TAG, "[$currentTraceId] OPEN onPanelShown thread=${Thread.currentThread().name}")
        // 规范：每次打开重置分类为「全部」，绝不残留上次状态
        selectCategory(null)
    }

    // ── 分类 ──────────────────────────────────────────────

    private fun selectCategory(category: String?) {
        // 切分类必须收起操作条：longPressItem 指向的条目可能不在新分类里（列表里再也看不到它），
        // 此时点「删除 / 收藏」作用的是看不见的条目，删除后用户不知道丢的是哪一条。
        hideActionBar()
        currentCategory = category
        applyTabFaces()
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
            // 分页加载：COUNT 不解密，解密只覆盖第一页（PANEL_PAGE_ITEMS）
            val filter = ClipboardFilter.of(category)
            // 查询异常（数据库损坏、磁盘满等）绝不能把 loadingPage 永远留在 true：
            // 那会让 loadNextPage() 的第一道闸门永久拦住后续分页（静默"没有更多了"）。
            val loaded = runCatching {
                val total = db.count(filter.category, filter.favoritesOnly)
                val page = db.recentPageWithOffset(0, PANEL_PAGE_ITEMS, filter.category, filter.favoritesOnly)
                Diagnostics.i(
                    TAG,
                    "[$tid] DB category=${category ?: "ALL"} total=$total page=${page.items.size} " +
                        "thread=${Thread.currentThread().name}",
                )
                total to page
            }
            post {
                if (reqToken != refreshToken) return@post
                loadingPage = false
                loaded.onSuccess { (total, page) ->
                    categoryTotal = total
                    currentItems = page.items.toMutableList()
                    nextPageOffset = page.nextOffset
                    hasMorePages = page.nextOffset < total
                    adapter.notifyDataSetChanged()
                    updateEmpty()
                    // 首帧布局竞态兜底：异步回填可能发生在 ListView 首次布局完成前，
                    // 单次 notify 不足以让 item 创建；等布局稳定后二次强制重绘。
                    listView.post { forceRelayout(reqToken) }
                    if (resetScroll && currentItems.isNotEmpty()) {
                        val token = ++scrollToken
                        listView.post { if (token == scrollToken) listView.setSelection(0) }
                    }
                }.onFailure {
                    Diagnostics.e(TAG, "[$tid] 列表刷新失败（loadingPage 已复位）: ${it.message}", it)
                }
            }
        }
    }

    /** 首帧兜底用的强制重绘：等布局稳定后再刷一次，令牌过期则跳过 */
    private fun forceRelayout(reqToken: Int) {
        if (reqToken != refreshToken) return
        adapter.notifyDataSetChanged()
        listView.requestLayout()
        listView.invalidate()
    }

    /** 滚动接近底部时加载下一页并追加（解密只覆盖本页） */
    private fun loadNextPage() {
        if (loadingPage || !hasMorePages) return
        val category = currentCategory
        val offset = nextPageOffset
        val reqToken = refreshToken
        loadingPage = true
        BackgroundIo.run {
            val filter = ClipboardFilter.of(category)
            // 与 refresh 一致：查询异常也要复位 loadingPage（否则分页永久停摆）
            val loaded = runCatching {
                db.recentPageWithOffset(offset, PANEL_PAGE_ITEMS, filter.category, filter.favoritesOnly)
            }
            post {
                if (reqToken != refreshToken) return@post
                loadingPage = false
                loaded.onSuccess { page ->
                    // 判停用游标而非本页条数：整页解密失败时 items 为空但后面仍有内容，
                    // 以空页判停会让用户再也翻不到后面的条目。
                    if (page.nextOffset > offset) {
                        currentItems.addAll(page.items)
                        nextPageOffset = page.nextOffset
                    }
                    hasMorePages = page.nextOffset > offset && page.nextOffset < categoryTotal
                    adapter.notifyDataSetChanged()
                    Diagnostics.i(TAG, "分页加载: offset=$offset +${page.items.size} next=${page.nextOffset} hasMore=$hasMorePages")
                }.onFailure {
                    Diagnostics.e(TAG, "分页加载失败（loadingPage 已复位）: ${it.message}", it)
                }
            }
        }
    }

    /** 空态与列表可见性切换（GONE→VISIBLE 后强制重布局，避免有高度有数据却显示空白） */
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
        // 与「清空」确认条互斥：两条都可见时按钮紧挨着，容易按到另一条上的操作
        hideConfirmBar()
        longPressItem = item
        actionFavorite.text = if (item.isFavorite) "取消收藏" else "收藏"
        actionBar.visibility = View.VISIBLE
        Diagnostics.i(TAG, "[$currentTraceId] 长按菜单: 显示操作条 id=${item.id}")
    }

    private fun hideActionBar() {
        actionBar.visibility = View.GONE
        longPressItem = null
    }

    /**
     * 长按操作：写库一律走 [BackgroundIo]。
     *
     * `setFavorite`/`delete` 内部 `getWritableDatabase` 会做磁盘 IO 与锁竞争，
     * 在大库或 checkpoint 触发时可达秒级，放在触摸回调里就是主线程 IO，
     * 与同文件「清空」走后台的处理方式不一致（那是漏实现，不是有意）。
     */
    private fun toggleFavorite() {
        val item = longPressItem ?: return
        val favorite = !item.isFavorite
        val tid = currentTraceId   // 主线程取值后再进后台，避免跨线程读视图字段
        BackgroundIo.run {
            db.setFavorite(item.id, favorite)
            Diagnostics.i(TAG, "[$tid] 长按操作: 收藏切换 id=${item.id}")
            post {
                hideActionBar()
                // 与删除一致走 resetScroll：refresh 只取第一页，不重置滚动的话已加载的多页被
                // 整体截回、ListView 的 firstPosition 又被钳到末尾，用户既不在原位置、
                // 也找不到刚操作的那一条。回顶至少是明确、可预期的行为。
                refresh(resetScroll = true)
            }
        }
    }

    private fun deleteItem() {
        val item = longPressItem ?: return
        val tid = currentTraceId   // 主线程取值后再进后台，避免跨线程读视图字段
        BackgroundIo.run {
            db.delete(item.id)
            Diagnostics.i(TAG, "[$tid] 长按操作: 删除 id=${item.id}")
            post {
                hideActionBar()
                refresh(resetScroll = true)
            }
        }
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

    /**
     * 半透明键盘：按当前档重建分类栏按钮的键面背景。
     *
     * 为什么需要：这些按钮的背景是 `R.drawable.key_bg`（drawable），键盘侧按「纯色底」
     * 识别透明度的兜底扫描扫不到它们，不重建的话透明度拉满时面板会「底已透、按钮仍实心」。
     * 操作条 / 确认条里的按钮是动态创建的，它们经 [tabButton] 构建时直接取最新档位。
     */
    fun applySurfaceAlpha(alpha: Float) {
        if (alpha == surfaceAlpha) return
        surfaceAlpha = alpha
        if (!::btnBack.isInitialized) return
        // 按当前分类重设「面」：原先一律按未选中面重建，会让选中分类的强调底在拖过
        // 透明度滑杆后丢失（视觉上「当前分类」没了标记）
        applyTabFaces()
        // 列表条目卡在 getView 里按档设色：通知重建，让已渲染的行立即换到新档
        adapter.notifyDataSetChanged()
    }

    /**
     * 切换键盘皮肤：面板底、分类栏 / 操作条按钮、条目卡与文字一并换色。
     *
     * 默认皮肤（覆盖项全为 null）回落 `R.color` 令牌，配色与历史一致；危险色按钮保持语义红。
     * 条目卡与条目文字在 [adapter] 的 getView 里逐次读皮肤色，这里只需通知重建。
     */
    fun applySkin(newSkin: KeyboardSkin) {
        if (newSkin.id == skin.id) return
        skin = newSkin
        applyPanelColors()
    }

    /** 按当前皮肤重设面板内的静态配色 */
    private fun applyPanelColors() {
        if (!::listView.isInitialized) return
        val plate = skinColor(context, skin.plate, R.color.app_bg)
        setBackgroundColor(plate)
        listView.setBackgroundColor(plate)
        textEmpty.setTextColor(skinColor(context, skin.functionHint, R.color.text_secondary))
        for (b in tabButtons) {
            b.setTextColor(
                if (b in dangerButtons) context.getColor(R.color.danger)
                else skinColor(context, skin.functionGlyph, R.color.text_primary)
            )
        }
        applyTabFaces()
        adapter.notifyDataSetChanged()
    }

    /**
     * 给面板按钮铺「面」：当前选中分类用 accent 实心（原 `key_bg_active` 的同款几何，该 drawable 已删除），
     * 其余按当前透明度档用皮肤面色。换皮肤或改透明度后都要重跑，选中态才不会丢。
     */
    private fun applyTabFaces() {
        // 遍历全部 tabButton（顶栏 + 长按操作条 + 清空确认条）：先前只覆盖顶栏，导致操作条 /
        // 确认条的按钮永远没有键面（2026-09-23 审查发现的存量缺陷，真机截图确认）
        for (b in tabButtons) {
            val selected = isSelectedCategoryButton(b)
            val base = if (selected) {
                skinColor(context, skin.accent, R.color.accent)
            } else {
                skinColor(context, skin.functionFill, R.color.key_bg)
            }
            b.background = buildKeyFaceBackground(
                context, if (selected) 1f else surfaceAlpha, KEY_FACE_CORNER_DP, base, faceRipple(base),
            )
        }
    }

    /**
     * 面板按钮的涟漪：默认皮肤走令牌（随主题明暗），其余皮肤按按钮底色明暗推导 ——
     * 与键盘侧 `rippleColor` 一致（亮底 10% 黑 / 暗底 30% 白）。
     */
    private fun faceRipple(base: Int): Int =
        if (skin.isDefault) context.getColor(R.color.key_ripple) else KeyboardSkins.rippleOn(base)

    /** [b] 是否为「当前选中分类」的按钮（返回 / 搜索 / 清空等非分类按钮恒为 false） */
    private fun isSelectedCategoryButton(b: TextView): Boolean {
        val selected = currentCategory
        return when (b) {
            btnCategoryAll -> selected == null
            btnCategoryUrl -> selected == ClipboardClassifier.CATEGORY_URL
            btnCategoryNumber -> selected == ClipboardClassifier.CATEGORY_NUMBER
            btnCategoryFavorite -> selected == CATEGORY_FAVORITE
            else -> false
        }
    }

    private fun tabButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            setTextColor(skinColor(context, skin.functionGlyph, R.color.text_primary))
            textSize = 12f
            // 键面背景按当前透明度档 + 皮肤面色运行时构建（XML 的 key_bg 带不了动态 alpha；几何与其一致）
            val fill = skinColor(context, skin.functionFill, R.color.key_bg)
            background = buildKeyFaceBackground(
                context, surfaceAlpha, KEY_FACE_CORNER_DP, fill, faceRipple(fill),
            )
            isClickable = true
            setOnClickListener { onClick() }
            // 注册到统一列表：换皮肤时由 applyPanelColors 重设文字与面
            tabButtons += this
        }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ClipboardPanel"
        /** 面板按钮的圆角（与 `key_bg.xml` 的 10dp 对齐，改 XML 时必须同步） */
        const val KEY_FACE_CORNER_DP = 10f
        // 常量统一取自 ClipboardFilter，避免 UI 与数据层各定义一份而漂移
        const val CATEGORY_FAVORITE = ClipboardFilter.PSEUDO_FAVORITE
        /** 距底部还有多少条时预取下一页 */
        const val LOAD_AHEAD = 10
        /**
         * 条目时间格式。按当前 Locale 即时构造：静态缓存会在系统语言切换后继续沿用旧 Locale
         * （lint ConstantLocale），而每条目构造一次的开销可忽略。
         */
        fun formatTime(ts: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
