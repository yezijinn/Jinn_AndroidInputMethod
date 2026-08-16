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
 *  - 分类栏：全部 / 网址 / 隐私 / 数字 / 收藏（收藏为独立标签）
 *  - 动态 UI 序号（最新=最大，删除/去重后重排，非数据库 ID）
 *  - 点击记录粘贴 + 成功关闭面板（失败不关闭，快速点击去重）
 *  - 实时搜索（输入即搜，保留原始序号）
 *  - 清理重复（严格字符串比较，保留收藏/隐私标记）
 *  - 长按：收藏 / 隐私 / 删除
 *  - 隐私内容默认隐藏明文，点击一次才显示
 *  - 空分类显示空态，绝不触发返回
 *
 * 身份不变量：点击用稳定 itemId 查找，不依赖 position；
 * 越界/已删除/已去重一律安全忽略。
 * 隐私：不输出任何剪贴板正文日志。
 */
class ClipboardPanelView(context: Context) : LinearLayout(context) {

    /** 面板回调（全部主线程） */
    interface Listener {
        /** 点击记录：IME 用当前 InputConnection 粘贴文本。返回是否成功提交。 */
        fun onPaste(text: String): Boolean
        /** 关闭面板，恢复原输入法键盘 */
        fun onClose()
    }

    var listener: Listener? = null

    private val db by lazy { ClipboardDb.get(context) }
    private val prefs by lazy { ClipboardPrefs.of(context) }

    private lateinit var listView: ListView
    private lateinit var textEmpty: TextView
    private lateinit var btnCategoryAll: TextView
    private lateinit var btnCategoryUrl: TextView
    private lateinit var btnCategoryPrivate: TextView
    private lateinit var btnCategoryNumber: TextView
    private lateinit var btnCategoryFavorite: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnClear: TextView

    /** 内联操作条（长按条目时显示，替代 AlertDialog——IME 内嵌面板无窗口 token） */
    private lateinit var actionBar: LinearLayout
    private lateinit var actionFavorite: TextView
    private lateinit var actionPrivate: TextView
    private lateinit var actionDelete: TextView
    private var longPressItem: ClipboardDb.Item? = null

    private var currentCategory: String? = null
    private var currentItems: List<ClipboardDb.Item> = emptyList()
    private var revealedPrivateIds = HashSet<Long>()

    /** 快速点击去重：一次粘贴完成前忽略后续点击 */
    private var isPasting = false

    /** 防滚动残留：切分类/去重后 post 滚回顶部，旧 post 任务无效化 */
    private var scrollToken = 0

    /** 本次打开的 Trace ID（onPanelShown 生成，刷新链路共享） */
    private var currentTraceId: String = ""

    // ── 适配器（稳定 ID 绑定）──────────────────────────────
    // 重要：必须声明在 init 块之前！Kotlin 属性按声明顺序初始化，
    // init{ buildUi() } 里 listView.adapter = this.adapter 若 adapter 声明在后面，
    // 访问到的是未初始化的 null（12:48 日志实证：adapter=null）。
    private val adapter = object : BaseAdapter() {
        override fun getCount() = currentItems.size
        override fun getItem(pos: Int) = currentItems[pos]
        override fun getItemId(pos: Int) = currentItems[pos].id
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = currentItems[pos]
            val holder = convertView?.tag as? Holder
            val isReused = convertView != null
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
                    setMaxLines(3)          // 长文本限高，避免单条无限扩大
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                }
                val meta = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding(0, dp(4), 0, 0)
                }
                row.addView(num, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                v.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.tag = Holder(num, content, meta)
                v
            }
            val h = holder ?: return root
            h.itemId = item.id  // 身份绑定：每次渲染写稳定 ID，复用 View 时更新
            h.num.text = (currentItems.size - pos).toString()
            // 隐私内容：未揭示时只显示前 3 字符（保留隐私，提示"隐私"标记）；
            // 已揭示（点击过）或非隐私显示全文。
            h.content.text = if (item.isPrivate && !revealedPrivateIds.contains(item.id)) {
                item.content.take(3) + "…"
            } else {
                item.content
            }
            h.meta.text = buildString {
                append(SDF.format(Date(item.createdAt)))
                if (item.category != "OTHER") append(" · ").append(item.category)
                if (item.isFavorite) append(" · 收藏")
                if (item.isPrivate) append(" · 隐私")
            }
            return root
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0B1020"))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        buildUi()
    }

    private fun buildUi() {
        // ── 顶栏：返回 + 5 分类 + 清空，全部平均等分（weight=1）──
        val topRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        btnBack = tabButton("返回") { listener?.onClose() }
        btnCategoryAll = tabButton("全部") { selectCategory(null) }
        btnCategoryUrl = tabButton("网址") { selectCategory(ClipboardClassifier.CATEGORY_URL) }
        btnCategoryPrivate = tabButton("隐私") { selectCategory(CATEGORY_PRIVATE) }
        btnCategoryNumber = tabButton("数字") { selectCategory(ClipboardClassifier.CATEGORY_NUMBER) }
        btnCategoryFavorite = tabButton("收藏") { selectCategory(CATEGORY_FAVORITE) }
        btnClear = tabButton("清空") {
            db.deleteAll()  // 数据层保护：只删普通记录，收藏与隐私保留
            refresh(resetScroll = true)
        }.apply { setTextColor(Color.parseColor("#E5484D")) }
        // 7 个按钮统一 weight=1 均分整行宽度，所有按钮必然可见
        val cells = listOf(btnBack, btnCategoryAll, btnCategoryUrl, btnCategoryPrivate, btnCategoryNumber, btnCategoryFavorite, btnClear)
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
        // 决定性验证：确认 ListView 持有的 adapter 与类属性 adapter 是同一个对象
        Diagnostics.i(
            TAG,
            "BUILD listView.adapter===classAdapter: ${listView.adapter === this@ClipboardPanelView.adapter} " +
                "adapter=$adapter",
        )
        listView.setOnItemClickListener { _, _, pos, _ ->
            // 标准 ListView 分发；身份用 getOrNull 防御越界（异步刷新可能错位）
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
            text = "暂无剪贴板历史\n复制内容后将自动保存（敏感内容除外）"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            visibility = GONE
        }
        addView(textEmpty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 内联操作条：长按条目时显示（收藏/隐私/删除），替代 AlertDialog ──
        actionBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        actionFavorite = tabButton("收藏") { toggleFavorite() }
        actionPrivate = tabButton("隐私") { togglePrivate() }
        actionDelete = tabButton("删除") { deleteItem() }
        actionDelete.setTextColor(Color.parseColor("#E5484D"))
        actionBar.addView(actionFavorite, LinearLayout.LayoutParams(0, dp(36), 1f))
        actionBar.addView(actionPrivate, LinearLayout.LayoutParams(0, dp(36), 1f))
        actionBar.addView(actionDelete, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(actionBar, lp())
    }

    /** 面板显示时刷新（每次打开自动清理重复 + 重新加载全部） */
    fun onPanelShown() {
        isPasting = false
        hideActionBar()  // 重置长按操作条状态
        // 本次打开生成 Trace ID，整条刷新链路共享（日志搜 traceId 可还原全链路）
        currentTraceId = Diagnostics.traceId("CLIP")
        Diagnostics.i(
            TAG,
            "[$currentTraceId] OPEN onPanelShown thread=${Thread.currentThread().name} " +
                "prevCategory=${currentCategory} prevItems=${currentItems.size}",
        )
        // 显式初始化分类为「全部」（null），绝不依赖上次状态
        if (currentCategory != null) {
            Diagnostics.w(TAG, "[$currentTraceId] OPEN 发现分类残留 ${currentCategory}，重置为 null")
        }
        // 每次打开自动触发清理重复（dedupe 内含后台 DB 清理 + post 延迟 refresh，
        // 与用户手动点「清理重复」的路径一致，保证冷启动后完整历史正确显示）。
        post {
            dedupe()
        }
    }

    // ── 分类 ──────────────────────────────────────────────

    private fun selectCategory(category: String?) {
        currentCategory = category
        val selected = currentCategory
        for (tab in listOf(btnCategoryAll, btnCategoryUrl, btnCategoryPrivate, btnCategoryNumber, btnCategoryFavorite)) {
            val isSel = when (tab) {
                btnCategoryAll -> selected == null
                btnCategoryUrl -> selected == ClipboardClassifier.CATEGORY_URL
                btnCategoryPrivate -> selected == CATEGORY_PRIVATE
                btnCategoryNumber -> selected == ClipboardClassifier.CATEGORY_NUMBER
                else -> selected == CATEGORY_FAVORITE
            }
            tab.setBackgroundResource(if (isSel) R.drawable.key_bg_active else R.drawable.key_bg)
        }
        refresh(resetScroll = true)
    }

    // ── 刷新 / 序号 ──────────────────────────────────────

    /**
     * 重新加载当前分类的列表。
     * 单一数据源：每次从 db 全量查询后按分类派生，绝不二次过滤。
     * @param resetScroll true 时滚回顶部（分类切换/去重/删除后）。
     */
    private fun refresh(resetScroll: Boolean = false) {
        val t0 = System.currentTimeMillis()
        val tid = currentTraceId.ifEmpty { Diagnostics.traceId("CLIP") }
        currentTraceId = tid
        val category = currentCategory
        val maxItems = prefs.maxItems
        // [DB] 全量查询（同步，主线程）
        val all = db.recent(maxItems)
        val t1 = System.currentTimeMillis()
        Diagnostics.i(TAG, "[$tid] DB all=${all.size} db=${t1 - t0}ms thread=${Thread.currentThread().name}")

        // [FILTER] 分类过滤（全部不经过任何条件）
        val filtered = when {
            category == CATEGORY_PRIVATE -> db.recentPrivate(maxItems)
            category == CATEGORY_FAVORITE -> db.recentFavorites(maxItems)
            category != null -> db.recent(maxItems, category)
            else -> all
        }
        val t2 = System.currentTimeMillis()
        currentItems = filtered
        Diagnostics.i(
            TAG,
            "[$tid] FILTER category=${category ?: "ALL"} " +
                "filtered=${filtered.size} final=${currentItems.size} " +
                "filter=${t2 - t1}ms",
        )

        // [ADAPTER] 通知刷新
        adapter.notifyDataSetChanged()
        // 决定性验证：ListView 侧看到的 adapter 状态
        Diagnostics.i(
            TAG,
            "[$tid] ADAPTER-SIDE count=${adapter.count} " +
                "listView.adapter==classAdapter: ${listView.adapter === adapter} " +
                "listViewAdapterCount=${listView.adapter?.count}",
        )
        updateEmpty()
        val t3 = System.currentTimeMillis()
        Diagnostics.i(
            TAG,
            "[$tid] ADAPTER count=${adapter.count} adapter=${t3 - t2}ms",
        )
        Diagnostics.event("Clipboard", "REFRESH", "trace=$tid cat=${category ?: "ALL"} all=${all.size} vis=${currentItems.size}")
        if (resetScroll && currentItems.isNotEmpty()) {
            // 防滚动残留：等 ListView 布局完成后滚回顶部；
            // 旧 post 任务通过 token 失效，避免覆盖新状态。
            val token = ++scrollToken
            listView.post { if (token == scrollToken) listView.setSelection(0) }
        }
    }

    private fun dedupe() {
        Diagnostics.i(TAG, "[$currentTraceId] DEDUPE 开始（当前 category=${currentCategory} items=${currentItems.size}）")
        Thread {
            val removed = db.deduplicate()  // 数据层已合并收藏/隐私标记
            post {
                Diagnostics.i(TAG, "[$currentTraceId] DEDUPE 完成 removed=$removed（静默，执行统一 refresh）")
                refresh(resetScroll = true)
            }
        }.start()
    }

    private fun updateEmpty() {
        val empty = currentItems.isEmpty()
        textEmpty.visibility = if (empty) VISIBLE else GONE
        listView.visibility = if (empty) GONE else VISIBLE
        // G 类根因修复：ListView 在 GONE→VISIBLE 切换后必须强制重新布局，
        // 否则 notifyDataSetChanged 在 GONE 期间调用，item 永不创建（有高有数但空白）。
        // 实测证据：listView.height=301 / adapterCount=33 但 getView 从未调用。
        listView.requestLayout()
        // 渲染层诊断：post 布局后确认 ListView 实际可见性与尺寸
        post {
            Diagnostics.i(
                TAG,
                "[$currentTraceId] RENDER empty=$empty " +
                    "listView.vis=${listView.visibility} h=${listView.height} " +
                    "attached=${listView.isAttachedToWindow} shown=${listView.isShown} " +
                    "panel.h=$height panelAttached=${isAttachedToWindow} panelShown=$isShown " +
                    "adapterCount=${adapter.count} childCount=${listView.childCount}",
            )
        }
    }

    private class Holder(val num: TextView, val content: TextView, val meta: TextView) {
        /** 当前绑定的稳定 Item ID（每次渲染更新） */
        var itemId: Long = -1L
    }

    // ── 点击粘贴 / 长按菜单 ────────────────────────────────

    /**
     * 处理 item 点击（隐私揭示 / 粘贴）。
     * 快速点击去重：isPasting 期间忽略后续点击。
     * 粘贴成功才关闭面板；失败保持面板（不返回不切换键盘）。
     */
    private fun handleItemClick(item: ClipboardDb.Item) {
        if (isPasting) return
        // 隐私内容：第一次点击揭示全文（前 3 字符 → 全文），第二次点击才粘贴
        if (item.isPrivate && !revealedPrivateIds.contains(item.id)) {
            revealedPrivateIds.add(item.id)
            adapter.notifyDataSetChanged()
            Diagnostics.i(TAG, "[$currentTraceId] 隐私揭示: id=${item.id}")
            Toast.makeText(context, "已显示内容，再次点击粘贴", Toast.LENGTH_SHORT).show()
            return
        }
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

    /**
     * 长按条目：显示内联操作条（收藏/隐私/删除）。
     * 不用 AlertDialog——IME 内嵌面板无 Activity 窗口 token，
     * AlertDialog 弹窗会抛 BadTokenException 导致 IME 崩溃回桌面（已实测）。
     */
    private fun showItemMenu(item: ClipboardDb.Item) {
        longPressItem = item
        actionFavorite.text = if (item.isFavorite) "取消收藏" else "收藏"
        actionPrivate.text = if (item.isPrivate) "移出隐私" else "加入隐私"
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

    private fun togglePrivate() {
        val item = longPressItem ?: return
        db.setPrivate(item.id, !item.isPrivate)
        Diagnostics.i(TAG, "[$currentTraceId] 长按操作: 隐私切换 id=${item.id}")
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
        const val CATEGORY_PRIVATE = "PRIVATE"
        const val CATEGORY_FAVORITE = "FAVORITE"
        val SDF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
