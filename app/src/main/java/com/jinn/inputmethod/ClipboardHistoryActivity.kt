package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 剪贴板历史页（输入法内部功能页）。
 *
 * 功能（文档剪贴板优化）：
 *  - 分类栏：全部 / 网址 / 数字 / 收藏 / 隐私（收藏、隐私为独立标签，可与分类并存）
 *  - 隐私条目默认隐藏明文：点击一次展开，再点一次才粘贴；隐私只能用户长按主动标记
 *  - 每条记录显示**动态 UI 序号**（最新=当前，删除去重后重新连续编号，非数据库 ID）
 *  - 点击记录 → 广播回传 IME 粘贴 + 关闭本页返回输入法上一页面（带超时兜底防卡死）
 *  - 实时搜索（输入即搜，删除原搜索按钮，大小写不敏感任意位置匹配，保留原始序号）
 *  - 清理重复（严格字符串比较，只保留最新）
 *  - 长按：收藏 / 取消收藏 / 隐私标记 / 删除
 *
 * 安全设计：
 *  - FLAG_SECURE 禁止截图；
 *  - 不输出任何剪贴板正文日志。
 */
class ClipboardHistoryActivity : Activity() {

    private val db by lazy { ClipboardDb.get(this) }
    private val prefs by lazy { ClipboardPrefs.of(this) }

    private lateinit var listView: ListView
    private lateinit var editSearch: EditText
    private lateinit var textEmpty: TextView
    private lateinit var categoryBar: HorizontalScrollView
    private lateinit var btnCategoryAll: TextView
    private lateinit var btnCategoryUrl: TextView
    private lateinit var btnCategoryNumber: TextView
    private lateinit var btnCategoryFavorite: TextView
    private lateinit var btnCategoryPrivate: TextView
    private lateinit var btnDedupe: TextView
    private lateinit var btnDeleteAll: TextView

    /**
     * 已「点击显示明文」的隐私条目 ID。
     *
     * 隐私条目默认只显示掩码，第一次点击加入本集合并展开明文，第二次点击才真正粘贴
     * （文档约定：隐私内容绝不默认明文可见）。仅内存态，页面关闭即失效。
     */
    private val revealedIds = HashSet<Long>()

    /** 当前分类（null=全部，其他=分类名） */
    private var currentCategory: String? = null

    /** 当前展示的记录 */
    private var currentItems: MutableList<ClipboardDb.Item> = mutableListOf()

    /** 当前分类总条数（编号用：序号 = 分类总数 − 位置） */
    private var categoryTotal = 0

    /** 分页状态：是否还有下一页 / 是否加载中 */
    private var hasMorePages = false
    private var loadingPage = false

    /** 当前列表的「最新序号基准」：用于搜索结果保留原始序号 */
    private var latestNumber: Int = 0

    /** 当前是否处于搜索状态 */
    private var searchQuery: String = ""

    /** 刷新请求令牌：异步查询完成时若已过期则丢弃，防乱序覆盖 */
    private var refreshToken = 0

    /** 滚动令牌：失效化旧的「滚回顶部」post，防快速切分类/输入时的滚动残留 */
    private var scrollToken = 0

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
                val v: LinearLayout = LinearLayout(this@ClipboardHistoryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = android.graphics.drawable.ColorDrawable(Color.parseColor("#141C33"))
                    // 点击/长按统一走 ListView 标准分发（onItemClick / onItemLongClick）：
                    // item 不设 isClickable，避免干扰 ListView 的 item 触摸判定。
                    // 数据身份在回调侧用 getOrNull(pos) 解析（防越界），
                    // 渲染时把稳定 itemId 存进 Holder，供防御性校验。
                }
                val row = LinearLayout(this@ClipboardHistoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val num = TextView(this@ClipboardHistoryActivity).apply {
                    textSize = 16f
                    setTextColor(Color.parseColor("#4C8DFF"))
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    setPadding(0, 0, dp(10), 0)
                }
                val content = TextView(this@ClipboardHistoryActivity).apply {
                    textSize = 16f
                    setTextColor(Color.parseColor("#ECEEF2"))
                    // 统一单行显示，超出一行用省略号
                    setMaxLines(1)
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                }
                val meta = TextView(this@ClipboardHistoryActivity).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding(0, dp(4), 0, 0)
                }
                row.addView(num, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(content, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                v.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.addView(meta, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val newHolder = Holder(num, content, meta)
                v.tag = newHolder
                v to newHolder
            }
            // 身份绑定：每次渲染把当前 Item 的稳定 ID 写进 Holder，
            // 复用 convertView 时旧 itemId 被覆盖，点击永远命中最新数据身份
            holder.itemId = item.id
            // UI 序号：搜索时保留原始序号，否则按当前位置倒序
            holder.num.text = if (searchQuery.isNotEmpty()) {
                // 搜索结果保留原始序号（在全库「新→旧」序列中的动态编号）
                val originalIndex = searchIndexById[item.id]
                if (originalIndex != null) (latestNumber - originalIndex).toString()
                else "-"
            } else {
                (categoryTotal - pos).toString()
            }
            // 隐私条目默认掩码：点一次显示明文，再点一次才粘贴（绝不默认明文可见）
            holder.content.text = if (item.isPrivate && item.id !in revealedIds) {
                getString(R.string.clipboard_private_masked)
            } else {
                item.content
            }
            holder.meta.text = buildString {
                append(SDF.format(Date(item.createdAt)))
                if (item.sourcePackage.isNotBlank()) append(" · ").append(item.sourceAppName.ifBlank { item.sourcePackage.substringAfterLast('.') })
                if (item.category != "OTHER") append(" · ").append(item.category)
                if (item.isFavorite) append(" · 收藏")
                if (item.isPrivate) append(" · 隐私")
            }
            return root
        }
    }

    private class Holder(val num: TextView, val content: TextView, val meta: TextView) {
        /** 当前绑定的稳定 Item ID（每次渲染更新，点击据此查找，不依赖 position） */
        var itemId: Long = -1L
    }

    /** 搜索命中的记录在全库序列中的原始偏移（搜索时渐进构建，仅含命中项） */
    private var searchIndexById: Map<Long, Int> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Diagnostics.i(TAG, "onCreate: 剪贴板历史页启动")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1020"))
            setPadding(dp(12), dp(16), dp(12), dp(12))
        }

        // ── 分类栏：全部 | 网址 | 数字 | 收藏 ──
        categoryBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val catRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnCategoryAll = categoryTab(R.string.clipboard_cat_all, onClick = { selectCategory(null) })
        btnCategoryUrl = categoryTab(R.string.clipboard_cat_url, onClick = { selectCategory(ClipboardClassifier.CATEGORY_URL) })
        btnCategoryNumber = categoryTab(R.string.clipboard_cat_number, onClick = { selectCategory(ClipboardClassifier.CATEGORY_NUMBER) })
        btnCategoryFavorite = categoryTab(R.string.clipboard_cat_favorite, onClick = { selectCategory(CATEGORY_FAVORITE) })
        btnCategoryPrivate = categoryTab(R.string.clipboard_cat_private, onClick = { selectCategory(CATEGORY_PRIVATE) })
        for (tab in listOf(btnCategoryAll, btnCategoryUrl, btnCategoryNumber, btnCategoryFavorite, btnCategoryPrivate)) {
            catRow.addView(tab, LinearLayout.LayoutParams(
                dp(76), dp(40)).apply { marginEnd = dp(6) })
        }
        categoryBar.addView(catRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(categoryBar, lp())

        // ── 搜索框（实时搜索，无搜索按钮） ──
        editSearch = EditText(this).apply {
            hint = getString(R.string.clipboard_search_hint)
            setTextColor(Color.parseColor("#ECEEF2"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#1C1F26"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val newQuery = s?.toString()?.trim() ?: ""
                if (newQuery == searchQuery) return
                val hadSearch = searchQuery.isNotEmpty()
                searchQuery = newQuery
                debounceHadSearch = hadSearch
                // 防抖 300ms：每次 refresh 都要查库+解密，逐字符触发会在
                // 快速输入时排队刷屏。清空搜索词时滚回顶部，输入中保持位置。
                uiHandler.removeCallbacks(searchDebouncer)
                uiHandler.postDelayed(searchDebouncer, SEARCH_DEBOUNCE_MS)
            }
        })
        root.addView(editSearch, lp())

        // ── 列表 ──
        listView = ListView(this).apply {
            divider = null
            setBackgroundColor(Color.parseColor("#0B1020"))
            adapter = this@ClipboardHistoryActivity.adapter
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            // 标准 ListView 分发：item 渲染时与 currentItems 对齐，pos 有效；
            // getOrNull 防御异步刷新（onResume 分时刷新/去重）导致的越界。
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
                // 非搜索态：距底部不足 LOAD_AHEAD 条时预取下一页
                if (searchQuery.isEmpty() && hasMorePages && totalItemCount > 0 &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - LOAD_AHEAD) {
                    loadNextPage()
                }
            }
        })
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 空状态 ──
        textEmpty = TextView(this).apply {
            text = getString(R.string.clipboard_empty)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            visibility = View.GONE
        }
        root.addView(textEmpty, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 底部操作栏：清理重复 | 删除全部 ──
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnDedupe = actionButton(R.string.clipboard_dedupe, Color.parseColor("#4C8DFF")) {
            dedupe()
        }
        btnDeleteAll = actionButton(R.string.clipboard_delete_all, Color.parseColor("#E5484D")) {
            confirmDeleteAll()
        }
        actionRow.addView(btnDedupe, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(btnDeleteAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow, lp())

        setContentView(root)
        // 监听 IME 粘贴结果：成功才允许关闭页面（同进程 NOT_EXPORTED 广播）。
        //
        // 用 ContextCompat.registerReceiver 而不是手写 `if (SDK_INT >= TIRAMISU)` 分支：
        // RECEIVER_NOT_EXPORTED 是 API 33 引入、API 34 起强制，手写分支容易被 Lint
        // 判为「未声明导出标志」（静态无法证明完备），ContextCompat 会按版本自动适配。
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                pasteResultReceiver,
                android.content.IntentFilter().apply { addAction(JinnIme.ACTION_CLIPBOARD_PASTE_RESULT) },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            Diagnostics.e(TAG, "注册粘贴结果接收器失败: ${it.message}")
        }
        selectCategory(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(searchDebouncer)
        uiHandler.removeCallbacks(pasteTimeoutRunnable)
        runCatching { unregisterReceiver(pasteResultReceiver) }.onFailure { }
    }

    /**
     * 每次回到前台重新加载数据：用户复制后立即打开页面时，保存线程可能尚未完成，
     * 首次 onCreate 的查询会读到旧状态。onResume + 分时延迟确保拿到最新数据。
     */
    override fun onResume() {
        super.onResume()
        // 延迟刷新（覆盖 ClipboardController 后台保存线程的写入窗口）：
        // 单条保存通常在几百 ms 内完成，多条连续复制时保存线程可能排队，
        // 分三档延迟覆盖最坏情况；重复调用 refresh 幂等（相同查询结果）。
        for (delay in longArrayOf(300L, 1000L, 2500L)) {
            uiHandler.postDelayed({
                if (!isFinishing) refresh()
            }, delay)
        }
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 粘贴超时兜底：IME 可能因进程被杀、未处于输入态等原因永不回传结果，
     * 若没有超时复位，[pasting] 会永久为 true 导致粘贴功能彻底卡死（点任何条目都无响应）。
     */
    private val pasteTimeoutRunnable = Runnable {
        if (!pasting) return@Runnable
        val id = pendingPasteItemId
        Diagnostics.w(TAG, "粘贴超时: id=$id 未收到 IME 回执，复位状态")
        resetPasteState()
        Toast.makeText(this, R.string.clipboard_paste_failed, Toast.LENGTH_SHORT).show()
    }

    /** 复位粘贴状态并取消超时（成功/失败/广播发送失败三处共用） */
    private fun resetPasteState() {
        uiHandler.removeCallbacks(pasteTimeoutRunnable)
        pasting = false
        pendingPasteItemId = -1L
    }

    /** 搜索防抖 Runnable：执行时按当前 query 决定刷新与滚回策略 */
    private val searchDebouncer = Runnable {
        // 防抖窗口结束时 query 为空说明刚从搜索切回全量，滚回顶部；
        // 非空则是输入过程，保持滚动位置不打断阅读
        refresh(resetScroll = searchQuery.isEmpty() && debounceHadSearch)
    }

    /** 防抖窗口起始时是否处于搜索状态（用于空查询时决定是否滚回顶部） */
    private var debounceHadSearch = false

    // ── 分类栏 ────────────────────────────────────────────

    private fun categoryTab(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = getString(labelRes)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#ECEEF2"))
            textSize = 13f
            setBackgroundResource(R.drawable.key_bg)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun selectCategory(category: String?) {
        currentCategory = category
        refreshCategoryTabs()
        // 数据源变化（分类切换）后滚回顶部，避免 ListView 停留在旧分类的滚动偏移
        refresh(resetScroll = true)
    }

    private fun refreshCategoryTabs() {
        val selected = currentCategory
        for (tab in listOf(btnCategoryAll, btnCategoryUrl, btnCategoryNumber, btnCategoryFavorite, btnCategoryPrivate)) {
            val isSelected = when (tab) {
                btnCategoryAll -> selected == null
                btnCategoryUrl -> selected == ClipboardClassifier.CATEGORY_URL
                btnCategoryNumber -> selected == ClipboardClassifier.CATEGORY_NUMBER
                btnCategoryFavorite -> selected == CATEGORY_FAVORITE
                else -> selected == CATEGORY_PRIVATE
            }
            tab.setBackgroundResource(if (isSelected) R.drawable.key_bg_active else R.drawable.key_bg)
        }
    }

    // ── 刷新 / 搜索 / 序号 ────────────────────────────────

    /**
     * 重新加载当前分类 + 搜索关键词的列表。
     * @param resetScroll true 时列表滚回顶部（分类切换 / 清理重复 / 清空删除后，
     *        数据源变化应回到最新记录；纯搜索输入时不打断阅读，保持 false）。
     */
    private fun refresh(resetScroll: Boolean = false) {
        val category = currentCategory
        val q = searchQuery
        val reqToken = ++refreshToken
        loadingPage = true
        hasMorePages = false
        Diagnostics.i(TAG, "refresh-begin: category=$category search=${q.isNotEmpty()} resetScroll=$resetScroll")
        BackgroundIo.run {
            if (q.isEmpty()) {
                loadFirstPage(category, reqToken, resetScroll)
            } else {
                runChunkedSearch(category, q, reqToken)
            }
        }
    }

    /** 非搜索路径：COUNT + 第一页，解密只覆盖 PAGE_SIZE 条 */
    private fun loadFirstPage(category: String?, reqToken: Int, resetScroll: Boolean) {
        // 伪分类（收藏/隐私）→ SQL 参数的翻译统一由 ClipboardFilter 负责，
        // 这里不再各写一遍，避免漏改导致「隐私 Tab 永远空列表」这类问题
        val filter = ClipboardFilter.of(category)
        val total = db.count(filter.category, filter.favoritesOnly, filter.privateOnly)
        val page = db.recentPage(0, PAGE_SIZE, filter.category, filter.favoritesOnly, filter.privateOnly)
        runOnUiThread {
            if (reqToken != refreshToken) return@runOnUiThread
            loadingPage = false
            categoryTotal = total
            currentItems = page.toMutableList()
            hasMorePages = page.size < total
            Diagnostics.i(TAG, "refresh-end: category=$category total=$total page=${page.size}")
            adapter.notifyDataSetChanged()
            updateEmpty()
            // 首帧布局竞态兜底（与内嵌面板一致）：异步回填可能发生在 ListView
            // 首次布局完成前，等布局稳定后二次通知重绘，确保 item 真正创建。
            listView.post {
                if (reqToken != refreshToken) return@post
                adapter.notifyDataSetChanged()
                listView.requestLayout()
                listView.invalidate()
            }
            if (resetScroll && currentItems.isNotEmpty()) {
                // token 失效化：快速输入/连续切分类时，旧的「滚回顶部」post
                // 会在新数据回填后执行，把列表拽到错误位置（滚动残留）
                val token = ++scrollToken
                listView.post { if (token == scrollToken) listView.setSelection(0) }
            }
        }
    }

    /** 滚动接近底部时加载下一页并追加（解密只覆盖本页） */
    private fun loadNextPage() {
        if (loadingPage || !hasMorePages || searchQuery.isNotEmpty()) return
        val category = currentCategory
        val offset = currentItems.size
        val reqToken = refreshToken
        loadingPage = true
        val filter = ClipboardFilter.of(category)
        BackgroundIo.run {
            val page = db.recentPage(offset, PAGE_SIZE, filter.category, filter.favoritesOnly, filter.privateOnly)
            runOnUiThread {
                if (reqToken != refreshToken) return@runOnUiThread
                loadingPage = false
                if (page.isEmpty()) {
                    hasMorePages = false
                    return@runOnUiThread
                }
                currentItems.addAll(page)
                hasMorePages = currentItems.size < categoryTotal
                adapter.notifyDataSetChanged()
                Diagnostics.i(TAG, "分页加载: offset=$offset +${page.size} hasMore=$hasMorePages")
            }
        }
    }

    /**
     * 分块渐进搜索：按 SEARCH_CHUNK 分块扫描（新→旧），命中项携带其原始偏移
     * （保持搜索序号语义）；每块扫描完立即发布到 UI，首块结果即刻可见，
     * 无需等全量解密完成。
     *
     * 分类/收藏/隐私会下推到 SQL 先过滤再解密，因此这里扫描的是**当前分类内**
     * 的序列，序号基准同样是分类内总数（与非搜索态一致）。
     *
     * 大库上限保护：扫描超过 maxItems 条即停（库裁剪上限即 maxItems，
     * 正常不会超出；此判断覆盖手工删配置等边角情况）。
     */
    private fun runChunkedSearch(category: String?, q: String, reqToken: Int) {
        val maxScan = prefs.maxItems
        val matches = ArrayList<ClipboardDb.Item>()
        val indexById = HashMap<Long, Int>()
        val lower = q.lowercase()
        var offset = 0
        var globalIndex = 0
        // 把分类/收藏/隐私下推到 SQL：先过滤再解密，避免在后台线程解密大量本就被
        // 过滤掉的条目（解密是这条路径上最贵的操作）。
        val filter = ClipboardFilter.of(category)
        // 序号基准：SQL 已按分类过滤，globalIndex 是「分类内偏移」而非全库偏移，
        // 所以这里必须用分类内总数——用全库总数会把序号整体算大，
        // 也与非搜索态（categoryTotal 为分类内总数）的语义不一致。
        val numberBase = db.count(filter.category, filter.favoritesOnly, filter.privateOnly)
        while (offset < maxScan) {
            val chunk = db.recentPage(
                offset, SEARCH_CHUNK,
                filter.category, filter.favoritesOnly, filter.privateOnly,
            )
            if (chunk.isEmpty()) break
            for (item in chunk) {
                val inCategory = when {
                    category == CATEGORY_FAVORITE -> item.isFavorite
                    category == CATEGORY_PRIVATE -> item.isPrivate
                    category != null -> item.category == category
                    else -> true
                }
                if (inCategory && item.content.lowercase().contains(lower)) {
                    matches.add(item)
                    indexById[item.id] = globalIndex
                }
                globalIndex++
            }
            offset += chunk.size
            // 令牌失效（输入已变/页面重刷）立即中止，不再浪费时间解密
            if (reqToken != refreshToken) return
            // 渐进发布：首块及后续每块结束都刷新列表
            val snapshot = matches.toList()
            val indexSnapshot = HashMap(indexById)
            runOnUiThread {
                if (reqToken != refreshToken) return@runOnUiThread
                latestNumber = numberBase
                searchIndexById = indexSnapshot
                currentItems = snapshot.toMutableList()
                adapter.notifyDataSetChanged()
                updateEmpty()
            }
        }
        runOnUiThread {
            if (reqToken != refreshToken) return@runOnUiThread
            loadingPage = false
            Diagnostics.i(TAG, "refresh-end: 搜索 \"$q\" 命中=${matches.size} 扫描=$globalIndex")
        }
    }

    /** 清理重复：严格字符串比较，只保留最新；完成后重载当前分类和搜索 */
    private fun dedupe() {
        BackgroundIo.run {
            val removed = db.deduplicate()
            runOnUiThread {
                Diagnostics.i(TAG, "清理重复: 删除 $removed 条")
                Toast.makeText(this, getString(R.string.clipboard_dedupe_done, removed), Toast.LENGTH_SHORT).show()
                refresh(resetScroll = true)
            }
        }
    }

    /**
     * 删除全部：确认对话框防误触。
     * 收藏记录不受影响（bug 审查计划 §2：收藏是永久存储）。
     */
    private fun confirmDeleteAll() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_delete_all)
            .setMessage(R.string.clipboard_delete_all_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // 删除在后台执行，避免主线程阻塞；完成后刷新
                BackgroundIo.run {
                    db.deleteAll()
                    runOnUiThread {
                        refresh(resetScroll = true)
                        Toast.makeText(this, R.string.clipboard_deleted_all, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateEmpty() {
        val empty = currentItems.isEmpty()
        textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        // 与内嵌面板一致的 G 类根因修复：GONE→VISIBLE 切换后强制重新布局，
        // 否则 notifyDataSetChanged 在 GONE 期间调用，item 永不创建（有高有数但空白）。
        listView.requestLayout()
        listView.invalidate()
    }

    // ── 点击粘贴 / 长按菜单 ───────────────────────────────

    /** 快速点击去重：一次粘贴操作完成前忽略后续点击（bug 审查计划 §9） */
    private var pasting = false

    /** 等待 IME 回传结果的剪贴板记录 id（防串线） */
    private var pendingPasteItemId = -1L

    /** IME 粘贴结果广播接收器：成功才关闭页面，失败保持页面等待 */
    private val pasteResultReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val itemId = intent.getLongExtra(JinnIme.EXTRA_CLIPBOARD_PASTE_ITEM_ID, -1L)
            // 只处理本次点击对应记录的反馈（防串线）
            if (itemId != pendingPasteItemId) return
            val success = intent.getBooleanExtra(JinnIme.EXTRA_CLIPBOARD_PASTE_SUCCESS, false)
            Diagnostics.i(TAG, "粘贴结果: id=$itemId success=$success")
            resetPasteState()
            if (success) {
                finish()
            } else {
                Toast.makeText(this@ClipboardHistoryActivity, R.string.clipboard_paste_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 处理 item 点击：请求 IME 粘贴。
     * 导航不变量（bug 审查计划 §34）：只有「有效 Item + commitText 成功」才触发返回。
     */
    private fun handleItemClick(item: ClipboardDb.Item) {
        // 快速点击去重：上次粘贴未完成时忽略新点击
        if (pasting) return
        // 隐私条目：第一次点击只展开明文，第二次点击才真正粘贴
        if (item.isPrivate && item.id !in revealedIds) {
            revealedIds.add(item.id)
            adapter.notifyDataSetChanged()
            return
        }
        requestPaste(item)
    }

    /**
     * 请求 IME 粘贴：发广播携带稳定 itemId + 内容，等待 IME 回传结果。
     * 成功（commitText=true）才关闭页面；失败/无连接则保持页面并提示。
     */
    private fun requestPaste(item: ClipboardDb.Item) {
        Diagnostics.i(TAG, "点击记录粘贴: id=${item.id}")
        pasting = true
        pendingPasteItemId = item.id
        val sent = runCatching {
            // 只传 id，**不传正文**：广播走 Binder，事务上限约 1MB，
            // 长文本（长文章/日志/大段代码）会让 sendBroadcast 抛
            // TransactionTooLargeException 导致崩溃。正文由 IME 按 id 从库里读。
            sendBroadcast(Intent(JinnIme.ACTION_CLIPBOARD_PASTE)
                .setPackage(packageName)
                .putExtra(JinnIme.EXTRA_CLIPBOARD_PASTE_ITEM_ID, item.id))
            true
        }.onFailure {
            Diagnostics.w(TAG, "发送粘贴广播失败: ${it.message}")
        }.getOrDefault(false)
        if (!sent) {
            // 广播都发不出：复位状态，停留本页，绝不误关
            resetPasteState()
            Toast.makeText(this, R.string.clipboard_paste_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // 超时兜底：IME 不回执时复位，避免 pasting 永久为 true 导致粘贴功能卡死
        uiHandler.postDelayed(pasteTimeoutRunnable, PASTE_TIMEOUT_MS)
    }

    /** 长按菜单：收藏 / 删除 */
    private fun showItemMenu(item: ClipboardDb.Item) {
        // 用简洁的自定义弹窗避免引入新依赖
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        options.add(getString(if (item.isFavorite) R.string.clipboard_unfavorite else R.string.clipboard_favorite))
        actions.add { db.setFavorite(item.id, !item.isFavorite); refresh(resetScroll = true) }
        // 隐私只能由用户主动标记（分类器与入库链路绝不自动判定）
        options.add(getString(if (item.isPrivate) R.string.clipboard_unmark_private else R.string.clipboard_mark_private))
        actions.add {
            val next = !item.isPrivate
            BackgroundIo.run {
                db.setPrivate(item.id, next)
                runOnUiThread {
                    // 取消隐私标记后同步移出「已展开」集合，重新标记后再次默认掩码
                    if (!next) revealedIds.remove(item.id)
                    refresh(resetScroll = true)
                }
            }
        }
        options.add(getString(R.string.clipboard_delete))
        actions.add { db.delete(item.id); refresh(resetScroll = true) }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_item_menu)
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun actionButton(labelRes: Int, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = getString(labelRes)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 14f
            setBackgroundColor(color)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ClipboardHistory"
        // 常量统一取自 ClipboardFilter，避免 UI 与数据层各定义一份而漂移
        const val CATEGORY_FAVORITE = ClipboardFilter.PSEUDO_FAVORITE
        const val CATEGORY_PRIVATE = ClipboardFilter.PSEUDO_PRIVATE
        const val SEARCH_DEBOUNCE_MS = 300L
        /** 等待 IME 回传粘贴结果的超时：超时复位 [pasting]，避免粘贴功能永久卡死 */
        const val PASTE_TIMEOUT_MS = 3_000L
        /** 每页条数（解密只覆盖可见窗口） */
        const val PAGE_SIZE = 50
        /** 距底部还有多少条时预取下一页 */
        const val LOAD_AHEAD = 10
        /** 搜索分块大小（每块解密后立即渐进发布） */
        const val SEARCH_CHUNK = 300
        val SDF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
