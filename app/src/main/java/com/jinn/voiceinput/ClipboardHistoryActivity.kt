package com.jinn.voiceinput

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
 *  - 分类栏：全部 / 网址 / 数字 / 收藏（收藏为独立标签，可与分类并存）
 *  - 每条记录显示**动态 UI 序号**（最新=当前，删除去重后重新连续编号，非数据库 ID）
 *  - 点击记录 → 广播回传 IME 粘贴 + 关闭本页返回输入法上一页面
 *  - 实时搜索（输入即搜，删除原搜索按钮，大小写不敏感任意位置匹配，保留原始序号）
 *  - 清理重复（严格字符串比较，只保留最新）
 *  - 长按：收藏 / 取消收藏 / 删除
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
    private lateinit var btnDedupe: TextView
    private lateinit var btnDeleteAll: TextView

    /** 当前分类（null=全部，其他=分类名） */
    private var currentCategory: String? = null

    /** 当前展示的记录（含原始序号映射用） */
    private var currentItems: List<ClipboardDb.Item> = emptyList()

    /** 当前列表的「最新序号基准」：用于搜索结果保留原始序号 */
    private var latestNumber: Int = 0

    /** 当前是否处于搜索状态 */
    private var searchQuery: String = ""

    /** 刷新请求令牌：异步查询完成时若已过期则丢弃，防乱序覆盖 */
    private var refreshToken = 0

    private val adapter = object : BaseAdapter() {
        override fun getCount() = currentItems.size
        override fun getItem(pos: Int) = currentItems[pos]
        override fun getItemId(pos: Int) = currentItems[pos].id
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = currentItems[pos]
            val holder = convertView?.tag as? Holder
            val root = convertView ?: run {
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
                v.tag = Holder(num, content, meta)
                v
            }
            val h = holder ?: return root
            // 身份绑定：每次渲染把当前 Item 的稳定 ID 写进 Holder，
            // 复用 convertView 时旧 itemId 被覆盖，点击永远命中最新数据身份
            h.itemId = item.id
            // UI 序号：搜索时保留原始序号，否则按当前位置倒序
            h.num.text = if (searchQuery.isNotEmpty()) {
                // 搜索结果保留原始历史序号：列表按最新→旧排，序号 = latestNumber - 原始偏移
                val originalIndex = itemPositionInAll(item.id)
                if (originalIndex >= 0) (latestNumber - originalIndex).toString()
                else "-"
            } else {
                (currentItems.size - pos).toString()
            }
            h.content.text = item.content
            h.meta.text = buildString {
                append(SDF.format(Date(item.createdAt)))
                if (item.sourcePackage.isNotBlank()) append(" · ").append(item.sourceAppName.ifBlank { item.sourcePackage.substringAfterLast('.') })
                if (item.category != "OTHER") append(" · ").append(item.category)
                if (item.isFavorite) append(" · 收藏")
            }
            return root
        }
    }

    private class Holder(val num: TextView, val content: TextView, val meta: TextView) {
        /** 当前绑定的稳定 Item ID（每次渲染更新，点击据此查找，不依赖 position） */
        var itemId: Long = -1L
    }

    /** 在全部记录中的位置（新→旧偏移），用于搜索结果保留原始序号 */
    private var allItemsById: Map<Long, Int> = emptyMap()

    private fun itemPositionInAll(id: Long): Int = allItemsById[id] ?: -1

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
        for (tab in listOf(btnCategoryAll, btnCategoryUrl, btnCategoryNumber, btnCategoryFavorite)) {
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
                val hadSearch = searchQuery.isNotEmpty()
                searchQuery = newQuery
                // 清空搜索词时结果从「搜索子集」切回「当前分类全量」，滚回顶部；
                // 输入过程中保持滚动位置不打断阅读
                refresh(resetScroll = hadSearch && newQuery.isEmpty())
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
        // 监听 IME 粘贴结果：成功才允许关闭页面（同进程 NOT_EXPORTED 广播）
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    pasteResultReceiver,
                    android.content.IntentFilter().apply { addAction(JinnIme.ACTION_CLIPBOARD_PASTE_RESULT) },
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                registerReceiver(
                    pasteResultReceiver,
                    android.content.IntentFilter().apply { addAction(JinnIme.ACTION_CLIPBOARD_PASTE_RESULT) },
                )
            }
        }.onFailure {
            Diagnostics.e(TAG, "注册粘贴结果接收器失败: ${it.message}")
        }
        selectCategory(null)
    }

    override fun onDestroy() {
        super.onDestroy()
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
        for (tab in listOf(btnCategoryAll, btnCategoryUrl, btnCategoryNumber, btnCategoryFavorite)) {
            val isSelected = when (tab) {
                btnCategoryAll -> selected == null
                btnCategoryUrl -> selected == ClipboardClassifier.CATEGORY_URL
                btnCategoryNumber -> selected == ClipboardClassifier.CATEGORY_NUMBER
                else -> selected == CATEGORY_FAVORITE
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
        val max = prefs.maxItems
        val reqToken = ++refreshToken
        Diagnostics.i(TAG, "refresh-begin: category=$category search=${q.isNotEmpty()} resetScroll=$resetScroll")
        BackgroundIo.run {
            val all = db.recent(max)
            val current = when {
                category == CATEGORY_FAVORITE -> db.recentFavorites(max)
                category != null -> db.recent(max, category)
                else -> all
            }
            val filtered = if (q.isEmpty()) current
            else current.filter { it.content.lowercase().contains(q) }
            runOnUiThread {
                if (reqToken != refreshToken) return@runOnUiThread
                latestNumber = all.size
                allItemsById = all.mapIndexed { idx, it -> it.id to idx }.toMap()
                currentItems = filtered
                Diagnostics.i(TAG, "refresh-end: category=$category allSize=${all.size} visibleSize=${currentItems.size}")
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
                    listView.post { listView.setSelection(0) }
                }
            }
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
            pasting = false
            pendingPasteItemId = -1L
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
            sendBroadcast(Intent(JinnIme.ACTION_CLIPBOARD_PASTE)
                .setPackage(packageName)
                .putExtra(JinnIme.EXTRA_CLIPBOARD_PASTE_ITEM_ID, item.id)
                .putExtra(JinnIme.EXTRA_CLIPBOARD_PASTE_TEXT, item.content))
            true
        }.onFailure {
            Diagnostics.w(TAG, "发送粘贴广播失败: ${it.message}")
        }.getOrDefault(false)
        if (!sent) {
            // 广播都发不出：复位状态，停留本页，绝不误关
            pasting = false
            pendingPasteItemId = -1L
            Toast.makeText(this, R.string.clipboard_paste_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 长按菜单：收藏 / 删除 */
    private fun showItemMenu(item: ClipboardDb.Item) {
        // 用简洁的自定义弹窗避免引入新依赖
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        options.add(getString(if (item.isFavorite) R.string.clipboard_unfavorite else R.string.clipboard_favorite))
        actions.add { db.setFavorite(item.id, !item.isFavorite); refresh(resetScroll = true) }
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
        const val CATEGORY_FAVORITE = "FAVORITE"
        val SDF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
