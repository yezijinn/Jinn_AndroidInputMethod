package com.jinn.inputmethod

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 剪贴板历史管理页：从「剪贴板自定义」页底部按钮进入。
 *
 * 与键盘内嵌面板的区别：这是完整管理页 —— 搜索 / 分类筛选 / 多选批量删除 /
 * 分类删除 / 清空非收藏 / 点行复制回系统剪贴板。
 *
 * 数据口径与面板一致：keyset 分页、读侧解密、搜索驻留预算（24MB + 条数上限），
 * 删除走 [ClipboardDb.deleteAnyByIds] / [ClipboardDb.deleteByCategory]。
 */
class ClipboardHistoryActivity : Activity() {

    private lateinit var db: ClipboardDb
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var searchEdit: EditText
    private lateinit var actionsRow: LinearLayout
    private lateinit var filterRow: LinearLayout

    private val allItems = ArrayList<ClipboardDb.Item>()
    private val items = ArrayList<ClipboardDb.Item>()
    private val numberById = HashMap<Long, Int>()
    private val checkedIds = HashSet<Long>()
    private var multiSelect = false
    private var categoryFilter: String? = null // null=全部, "URL", "NUMBER", "FAVORITE"
    private var keyword: String = ""
    private var pageIndex = 0

    /** 确认框已弹（防重入：连点「删除所选」/「清空」会堆叠多个框，BUG.md L-1165） */
    private var confirming = false

    private fun pageSize(): Int = ClipboardPrefs.of(this).historyPageSize
    private fun pageItems(): List<ClipboardDb.Item> {
        val from = (pageIndex * pageSize()).coerceAtMost(items.size)
        val to = (from + pageSize()).coerceAtMost(items.size)
        return items.subList(from, to)
    }

    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    private lateinit var adapter: BaseAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard_history)
        db = ClipboardDb.get(this)
        findViewById<TextView>(R.id.text_hist_title).text = TEXT_TITLE
        findViewById<Button>(R.id.btn_hist_close).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            textSize = 18f
            setOnClickListener { finish() }
        }
        list = findViewById(R.id.hist_list)
        emptyText = findViewById<TextView>(R.id.text_hist_empty).apply {
            // 空态文案的样式随主题色走，写在这里（XML 只留布局，文案与外观都由代码下发）
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(8), dp(24), dp(8), dp(24))
        }
        searchEdit = findViewById<EditText>(R.id.edit_hist_search).apply { hint = TEXT_SEARCH_HINT }
        actionsRow = findViewById(R.id.hist_actions)
        filterRow = findViewById(R.id.hist_filter_row)

        adapter = object : BaseAdapter() {
            override fun getCount() = pageItems().size
            override fun getItem(position: Int) = pageItems()[position]
            override fun getItemId(position: Int) = pageItems()[position].id

            /**
             * 行结构固定（多选框 / 序号 / 正文 / 元信息），`multiSelect` 只切多选框的**可见性**，
             * 因此可以安全复用 [convertView]（每页最多 100 行，不复用会每帧重建全部行）。
             * ⚠ 复用前先摘掉多选框的旧监听再写 `isChecked`，否则会把上一条的勾选状态写回 `checkedIds`。
             */
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = pageItems()[position]
                val row: LinearLayout
                val holder: RowHolder
                if (convertView is LinearLayout && convertView.tag is RowHolder) {
                    row = convertView
                    holder = convertView.tag as RowHolder
                } else {
                    row = LinearLayout(this@ClipboardHistoryActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                    }
                    val check = CheckBox(this@ClipboardHistoryActivity)
                    val number = TextView(this@ClipboardHistoryActivity).apply {
                        textSize = 13f
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(getColor(R.color.text_secondary))
                        minWidth = dp(24)
                    }
                    val content = TextView(this@ClipboardHistoryActivity).apply {
                        textSize = 13f
                        setTextColor(getColor(R.color.text_primary))
                        maxLines = 4
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    val meta = TextView(this@ClipboardHistoryActivity).apply {
                        textSize = 11f
                        setTextColor(getColor(R.color.text_secondary))
                    }
                    val body = LinearLayout(this@ClipboardHistoryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(content, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        addView(meta, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    row.addView(check)
                    row.addView(number, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
                    row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    holder = RowHolder(check, number, content, meta)
                    row.tag = holder
                }

                holder.check.visibility = if (multiSelect) View.VISIBLE else View.GONE
                holder.check.setOnCheckedChangeListener(null)
                holder.check.isChecked = checkedIds.contains(item.id)
                holder.check.setOnCheckedChangeListener { _, checked ->
                    if (checked) checkedIds.add(item.id) else checkedIds.remove(item.id)
                }
                holder.number.text = (numberById[item.id] ?: "").toString()
                holder.content.text = item.content
                holder.meta.text = buildString {
                    append(timeFmt.format(Date(item.createdAt)))
                    append("  ")
                    append(item.content.length).append(" 字")
                    append("  ").append(item.category)
                    if (item.isFavorite) append("  ★")
                }
                row.setOnClickListener {
                    if (multiSelect) {
                        if (checkedIds.contains(item.id)) checkedIds.remove(item.id) else checkedIds.add(item.id)
                        notifyDataSetChanged()
                    } else {
                        showContentDialog(item)
                    }
                }
                row.setOnLongClickListener {
                    val favText = if (item.isFavorite) TEXT_UNFAVORITE else TEXT_FAVORITE
                    AlertDialog.Builder(this@ClipboardHistoryActivity)
                        .setItems(arrayOf(TEXT_DELETE, favText)) { _, which ->
                            when (which) {
                                0 -> confirm(TEXT_CONFIRM_DELETE_ONE) {
                                    BackgroundIo.run {
                                        db.deleteAnyByIds(listOf(item.id))
                                        loadAsync()
                                    }
                                }
                                1 -> BackgroundIo.run {
                                    db.setFavorite(item.id, !item.isFavorite)
                                    loadAsync()
                                }
                            }
                        }
                        .show()
                    true
                }
                return row
            }
        }
        list.adapter = adapter
        list.divider = android.graphics.drawable.ColorDrawable(getColor(R.color.card_stroke))
        list.dividerHeight = dp(1)

        // 重建（旋转 / 定时换色）后恢复筛选与多选：这几个字段都是实例态，页面又注册了
        // 主题节拍器，不恢复就会出现「多选到一半旋转即丢选择」（BUG.md L-1165）
        savedInstanceState?.let { st ->
            categoryFilter = st.getString(STATE_FILTER)
            keyword = st.getString(STATE_KEYWORD) ?: ""
            pageIndex = st.getInt(STATE_PAGE, 0)
            st.getLongArray(STATE_CHECKED)?.forEach { checkedIds.add(it) }
            multiSelect = st.getBoolean(STATE_MULTI, false)
            searchEdit.setText(keyword)
        }
        buildFilterChips()
        buildActionRow()
        buildPagerRow()

        searchEdit.setOnEditorActionListener { _, _, _ ->
            keyword = searchEdit.text.toString().trim()
            loadAsync()
            true
        }
        findViewById<Button>(R.id.btn_hist_search).apply {
            text = TEXT_SEARCH
            setOnClickListener {
                keyword = searchEdit.text.toString().trim()
                loadAsync()
            }
        }
        findViewById<Button>(R.id.btn_hist_reset).apply {
            text = TEXT_RESET
            setOnClickListener {
                searchEdit.setText("")
                keyword = ""
                categoryFilter = null
                pageIndex = 0
                buildFilterChips()
                loadAsync()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
        // 不归零：页码可能是刚恢复出来的（BUG.md L-1172），回到前台时也应当停在原页
        loadAsync(resetPage = false)
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /** 跨重建保存筛选 / 搜索 / 页码 / 多选（BUG.md L-1165）。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, categoryFilter)
        // 搜索词按上限截断再进实例状态：搜索框没有长度上限，整份进 Bundle 会撑到系统侧事务上限
        // （BUG.md L-1169）；超过上限的词本来就匹配不到任何条目
        outState.putString(STATE_KEYWORD, keyword.take(MAX_STATE_KEYWORD))
        outState.putInt(STATE_PAGE, pageIndex)
        outState.putLongArray(STATE_CHECKED, checkedIds.toLongArray())
        outState.putBoolean(STATE_MULTI, multiSelect)
    }


    /** 统一按钮样式：与 XML 侧 SettingsButton / TapSound 试听按钮同款（极光次级背景 + 固定最小高 + 规范内边距） */
    private fun compactView(): TextView = TextView(this).apply {
        textSize = 15f
        setTextColor(getColor(R.color.text_primary))
        background = getDrawable(R.drawable.btn_aurora_secondary)
        setPadding(dp(3), dp(1), dp(3), dp(1))
        gravity = Gravity.CENTER
    }

    private fun buildFilterChips() {
        filterRow.removeAllViews()
        filterRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val defs = listOf(null to "全部", "URL" to "网址", "NUMBER" to "数字", "FAVORITE" to "收藏")
        filterRow.gravity = Gravity.CENTER
        for ((key, label) in defs) {
            filterRow.addView(compactView().apply {
                text = label
                setTextColor(if (categoryFilter == key) getColor(R.color.accent) else getColor(R.color.text_primary))
                setOnClickListener {
                    categoryFilter = key
                    buildFilterChips()
                    loadAsync()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
    }

    private fun buildActionRow() {
        actionsRow.removeAllViews()
        actionsRow.addView(compactView().apply {
            text = if (multiSelect) "删除所选" else "多选"
            setOnClickListener {
                if (multiSelect) {
                    if (checkedIds.isEmpty()) return@setOnClickListener
                    confirm("删除选中的 ${checkedIds.size} 条？") {
                        BackgroundIo.run {
                            db.deleteAnyByIds(checkedIds.toList())
                            checkedIds.clear()
                            multiSelect = false
                            // 同上：删完留在当前页（BUG.md L-1175）
                            loadAsync(resetPage = false)
                        }
                    }
                } else {
                    multiSelect = true
                    checkedIds.clear()
                    buildActionRow()
                    adapter.notifyDataSetChanged()
                }
            }
        })
        if (multiSelect) {
            actionsRow.addView(compactView().apply {
                text = "全选本页"
                setOnClickListener {
                    checkedIds.addAll(pageItems().map { it.id })
                    adapter.notifyDataSetChanged()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actionsRow.addView(compactView().apply {
                text = "取消"
                setOnClickListener {
                    multiSelect = false
                    checkedIds.clear()
                    buildActionRow()
                    adapter.notifyDataSetChanged()
                }
            })
        } else {
            actionsRow.addView(compactView().apply {
                text = "删网址"
                setOnClickListener {
                    confirm("删除所有「网址」分类条目？") {
                        BackgroundIo.run { db.deleteByCategory("URL"); loadAsync() }
                    }
                }
            })
            actionsRow.addView(compactView().apply {
                text = "删数字"
                setOnClickListener {
                    confirm("删除所有「数字」分类条目？") {
                        BackgroundIo.run { db.deleteByCategory("NUMBER"); loadAsync() }
                    }
                }
            })
            actionsRow.addView(compactView().apply {
                text = "清空"
                setOnClickListener {
                    confirm("清空全部非收藏历史？") {
                        BackgroundIo.run { db.deleteByCategory(null); loadAsync() }
                    }
                }
            })
            actionsRow.addView(compactView().apply {
                text = "刷新"
                setOnClickListener { loadAsync() }
            })
        }
        // 同一行按钮等宽（不按文字长度撑开）
        // 不回顶铺满：每个按钮紧贴文字，整行水平居中、相邻 4dp
        actionsRow.gravity = Gravity.CENTER
        for (i in 0 until actionsRow.childCount) {
            val c = actionsRow.getChildAt(i)
            c.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }
    }

    private fun buildPagerRow() {
        val pager = findViewById<LinearLayout>(R.id.hist_pager)
        pager.removeAllViews()
        val totalPages = ((items.size + pageSize() - 1) / pageSize()).coerceAtLeast(1)
        pageIndex = pageIndex.coerceIn(0, totalPages - 1)
        // 不回顶铺满：每个按钮紧贴文字，整行水平居中、相邻 4dp
        pager.gravity = Gravity.CENTER
        pager.addView(compactView().apply {
            text = "上一页"
            isEnabled = pageIndex > 0
            setOnClickListener { pageIndex--; adapter.notifyDataSetChanged(); buildPagerRow() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        pager.addView(compactView().apply {
            // 拼串先落变量（lint SetTextI18n），文案仍由代码下发
            val label = "$TEXT_PAGE_SIZE_PREFIX${pageSize()}$TEXT_PAGE_SIZE_SUFFIX"
            text = label
            setOnClickListener {
                val opts = ClipboardPrefs.PAGE_SIZE_OPTIONS
                val next = opts[(opts.indexOf(pageSize()) + 1) % opts.size]
                ClipboardPrefs.of(this@ClipboardHistoryActivity).historyPageSize = next
                pageIndex = 0
                adapter.notifyDataSetChanged()
                buildPagerRow()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        pager.addView(compactView().apply {
            val pageLabel = "${pageIndex + 1}/$totalPages"
            text = pageLabel
            isEnabled = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        pager.addView(compactView().apply {
            text = "下一页"
            isEnabled = pageIndex < totalPages - 1
            setOnClickListener { pageIndex++; adapter.notifyDataSetChanged(); buildPagerRow() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
    }

    /**
     * 重新加载列表。
     *
     * [resetPage] = false 供「重建 / 回到前台」用：页码可能是从实例状态恢复的，
     * 无条件归零会把恢复结果当场覆盖（BUG.md L-1172）。筛选与搜索改条件时必须归零。
     */
    private fun loadAsync(resetPage: Boolean = true) {
        val category = categoryFilter
        val keywordNow = keyword
        val favoritesOnly = category == "FAVORITE"
        val catKey = if (category == "FAVORITE") null else category
        val maxResults = ClipboardPrefs.of(this).maxSearchResults
        BackgroundIo.run {
            val out = ArrayList<ClipboardDb.Item>()
            var cursor: ClipboardCursor? = null
            var retainedBytes = 0L
            var capped = false
            var cappedByCount = false
            while (true) {
                val page = db.recentPageAfter(cursor, LOAD_PAGE_ITEMS, catKey, favoritesOnly)
                if (page.scanned == 0) break
                cursor = page.last
                for (item in page.items) {
                    out.add(item)
                    retainedBytes += ClipboardStore.utf8ByteSize(item.content)
                    if (ClipboardStore.searchRetainLimitReached(out.size, retainedBytes,
                            maxResults = maxResults,
                            maxBytes = ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES)) {
                        capped = true
                        // 只有条数触顶才够资格报「最多 N 条」；字节预算先触发时实际远小于 N（L-995）
                        cappedByCount = out.size >= maxResults
                        break
                    }
                }
                if (capped) break
            }
            val total = db.count(catKey, favoritesOnly)
            runOnUiThread {
                allItems.clear()
                allItems.addAll(out)
                items.clear()
                // 与面板搜索同口径：忽略大小写（先前用 contains 区分大小写，搜小写英文搜不到大写内容）
                items.addAll(
                    if (keywordNow.isEmpty()) allItems
                    else allItems.filter { it.content.contains(keywordNow, ignoreCase = true) }
                )
                numberById.clear()
                for ((idx, item) in allItems.withIndex()) numberById[item.id] = total - idx
                // 勾选集跨重建恢复，而重建期间库可能在变（采集在线、库到上限时插入与淘汰成对发生）⇒
                // 按这次加载到的条目裁剪，免得确认文案的条数与实际勾着的行数不一致（BUG.md L-1171）
                checkedIds.retainAll(allItems.mapTo(HashSet()) { it.id })
                if (resetPage) pageIndex = 0
                adapter.notifyDataSetChanged()
                // 加载被上限截断时要留一行说明（面板搜索的 resultsCapped 同款），
                // 否则「列表里就这些」与「库里还有很多没加载」在界面上无从区分
                val hint = when {
                    cappedByCount -> "$TEXT_TRUNCATED_PREFIX$maxResults$TEXT_TRUNCATED_SUFFIX"
                    capped -> TEXT_TRUNCATED_GENERIC
                    items.isEmpty() -> TEXT_EMPTY
                    else -> ""
                }
                emptyText.text = hint
                emptyText.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
                buildActionRow()
                buildPagerRow()
            }
        }
    }

    /** 单击历史：弹层展示完整内容，上方 复制 / 收藏 / 删除 / 关闭，两侧距屏幕边各 10% */
    private fun showContentDialog(item: ClipboardDb.Item) {
        val dlg = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addBtn(text: String, onClick: () -> Unit) {
            btnRow.addView(TextView(this@ClipboardHistoryActivity).apply {
                this.text = text
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.text_primary))
                background = getDrawable(R.drawable.table_cell_border)
                setPadding(dp(3), dp(2), dp(3), dp(2))
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        addBtn("复制") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("clip", item.content))
            android.widget.Toast.makeText(this, TEXT_COPIED, android.widget.Toast.LENGTH_SHORT).show()
        }
        addBtn(if (item.isFavorite) "取消收藏" else "收藏") {
            // 不归零：单条收藏切换 / 删除不该把用户从第 N 页弹回第 1 页（BUG.md L-1175），
            // 列表收缩由分页控件的钳位收尾
            BackgroundIo.run { db.setFavorite(item.id, !item.isFavorite); loadAsync(resetPage = false) }
            dlg.dismiss()
        }
        addBtn("删除") {
            dlg.dismiss()
            confirm("删除这条记录？") {
                BackgroundIo.run { db.deleteAnyByIds(listOf(item.id)); loadAsync(resetPage = false) }
            }
        }
        addBtn("关闭") { dlg.dismiss() }
        root.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(TextView(this@ClipboardHistoryActivity).apply {
                text = item.content
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val w = (resources.displayMetrics.widthPixels * 0.96).toInt()
        val h = (resources.displayMetrics.heightPixels * 0.96).toInt()
        root.layoutParams = android.widget.FrameLayout.LayoutParams(w, h)
        dlg.setView(root)
        dlg.show()
        dlg.window?.setLayout(w, h)
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        // 防重入（BUG.md L-1165）：连点「删除所选」/「清空」会堆叠多个确认框，确定后重复执行；
        // 三处回调都复位（确定 / 取消 / 关掉），与同族剪贴板自定义页同款
        if (confirming) return
        confirming = true
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("确定") { _, _ ->
                confirming = false
                onYes()
            }
            .setNegativeButton("取消") { _, _ -> confirming = false }
            .setOnDismissListener { confirming = false }
            .show()
    }

    private fun dp(v: Int): Int = PageStyle.dp(this, v)

    /** 复用行的子视图引用（见 [adapter] 的 `getView`） */
    private class RowHolder(
        val check: CheckBox,
        val number: TextView,
        val content: TextView,
        val meta: TextView,
    )

    private companion object {
        const val TAG = "ClipboardHistory"

        /** 跨重建保存的实例态（BUG.md L-1165） */
        private const val STATE_FILTER = "hist_filter"

        /** 进实例状态的搜索词上限（字符）：更长的词匹配不到任何条目（BUG.md L-1169） */
        private const val MAX_STATE_KEYWORD = 256
        private const val STATE_KEYWORD = "hist_keyword"
        private const val STATE_PAGE = "hist_page"
        private const val STATE_CHECKED = "hist_checked"
        private const val STATE_MULTI = "hist_multi"
        const val TEXT_TITLE = "剪贴板历史管理"
        const val TEXT_COPIED = "已复制"
        const val TEXT_DELETE = "删除"
        const val TEXT_FAVORITE = "收藏"
        const val TEXT_UNFAVORITE = "取消收藏"
        const val TEXT_CONFIRM_DELETE_ONE = "删除这条记录？"
        const val TEXT_SEARCH_HINT = "搜索关键词"
        const val TEXT_SEARCH = "搜索"
        const val TEXT_RESET = "重置"
        const val TEXT_EMPTY = "暂无记录"
        const val TEXT_TRUNCATED_PREFIX = "只加载最多 "
        const val TEXT_TRUNCATED_SUFFIX = " 条（超出请用搜索缩小范围）"
        const val TEXT_TRUNCATED_GENERIC = "只加载了部分记录（受容量预算限制），可用搜索缩小范围"
        const val TEXT_PAGE_SIZE_PREFIX = "每页 "
        const val TEXT_PAGE_SIZE_SUFFIX = " 条"

        /** 加载分页的取页宽度（与面板页宽无关：驻留上限才是内存护栏） */
        const val LOAD_PAGE_ITEMS = 50
    }
}
