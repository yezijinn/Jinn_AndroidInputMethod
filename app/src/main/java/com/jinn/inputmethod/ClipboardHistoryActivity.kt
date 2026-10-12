package com.jinn.inputmethod

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
 * 删除走 [ClipboardDb.deleteAnyByIds] / [ClipboardDb.deleteByCategory]；
 * 「导入文件」见 [ClipboardFileImporter]（逐条仍走 [ClipboardStore.save] 这条唯一入库入口）。
 */
class ClipboardHistoryActivity : ComponentActivity() {

    private lateinit var db: ClipboardDb
    private lateinit var list: ListView
    private lateinit var grid: GridView
    private lateinit var emptyText: TextView
    private lateinit var searchEdit: EditText
    private lateinit var searchRow: View
    private lateinit var actionsRow: LinearLayout
    private lateinit var filterRow: LinearLayout

    private val allItems = ArrayList<ClipboardDb.Item>()
    private val items = ArrayList<ClipboardDb.Item>()
    private val numberById = HashMap<Long, Int>()
    private val checkedIds = HashSet<Long>()
    private var multiSelect = false
    private var categoryFilter: String? = null // null=全部, "URL", "NUMBER", "FAVORITE", "IMAGE"
    private var keyword: String = ""
    private var pageIndex = 0

    /** 当前是否处于图片分类（网格模式）：列表的多选与行渲染在此时全部让位 */
    private fun inImageMode(): Boolean = categoryFilter == FILTER_IMAGE

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

    /**
     * 「导入文件」的 SAF 选择器（OpenDocument，不申请存储权限，与配置包导入同款）。
     *
     * 选定后立刻转后台线程开始读：Uri 的临时读授权随本 Activity 生命周期存在，
     * 在回调里就把它交出去，避免用户随即离开页面导致授权失效。
     */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) startImport(uri)
    }

    /**
     * 「导出全部图片」的目录选择器（SAF 目录树，与图库绑定同款）。
     *
     * 拿到结果先取**持久读写**授权再开导：只拿到读位的目录写不进去，宁可当场说清让用户换一个
     * （图库绑定那边是「回落只读 + 提示重绑」，这里没有只读的用途）。取消不改已记住的目录。
     */
    private val exportDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val granted = runCatching { contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
        if (!granted) {
            // 不记 uri 本身（目录路径会随诊断包外带，L-1036），只记提供方
            Diagnostics.w(TAG, "导出目录未取得写授权 provider=${uri.authority}")
            message(TEXT_IMAGE_EXPORT_DIR_READONLY)
            return@registerForActivityResult
        }
        Prefs(this).clipExportTreeUri = uri.toString()
        startExport(uri)
    }

    /**
     * 导入进行中（防连点重复触发）。
     *
     * 跨重建（旋转 / 定时换肤）会复位成 false，但后台任务不依赖界面：重建后任务照常跑完落库，
     * 只是那一次的回执框不再弹出（可接受边界，与「导入完成前离开页面」同一形态）。
     */
    private var importing = false

    /** 导出全部图片的进度框：`onStop` 里收起（BUG.md L-1297）；「进行中」的标志是进程级的 [EXPORTING] */
    private var exportDlg: AlertDialog? = null

    private lateinit var adapter: BaseAdapter

    /** 图片分类的网格适配器（与 [adapter] 同源取数：都读 [pageItems]） */
    private lateinit var gridAdapter: BaseAdapter

    /**
     * 图片网格当前显示的那一页（**快照**，BUG.md L-1294）。
     *
     * 不用 `pageItems()` 现取：`AbsListView` 只在收到 `notifyDataSetChanged()` 时才重跑
     * `getView`，而翻页这条路径原先只刷了列表的适配器 ⇒ 网格继续显示上一页，点击 / 长按却按
     * 新页下标取条目（打开的 / 删除的不是看到的那张图），新页更短时还会越界崩溃。
     * 渲染与点击同读快照，两个问题一起消掉。
     */
    private var gridPageItems: List<ClipboardDb.Item> = emptyList()

    /** 网格快照切到当前页并刷新（翻页 / 取数完成 / 改列数后都要调） */
    private fun syncGridSnapshot() {
        gridPageItems = pageItems()
        if (::gridAdapter.isInitialized) gridAdapter.notifyDataSetChanged()
    }

    /**
     * 页号钳进有效范围（BUG.md L-1323）：条目变少后停在越界页时，`pageItems()` 会取到空页。
     *
     * **必须在适配器刷新之前调**：`AbsListView` 只在收到 `notifyDataSetChanged()` 时才重跑
     * `getView`，刷新时若已越界，那一刻 `getCount() == 0` 就被记住 —— 之后 `buildPagerRow()`
     * 里再钳页号也不会有人重新取数，界面整页空白、分页行却显示「1/1」，而 `items` 非空
     * 连空态文案都不显示。
     */
    private fun clampPageIndex() {
        val totalPages = ((items.size + pageSize() - 1) / pageSize()).coerceAtLeast(1)
        pageIndex = pageIndex.coerceIn(0, totalPages - 1)
    }

    /**
     * 翻页类操作后的统一刷新（BUG.md L-1294）：列表与图片网格各有适配器与数据源，
     * 只刷列表会让网格停在旧页 —— 图片分类下翻页必须两个都刷。
     */
    private fun refreshPage() {
        adapter.notifyDataSetChanged()
        syncGridSnapshot()
    }

    /** 图片分类是否只看收藏：收藏分类不再有图片行后，这里是看「收藏过的图片」的入口（BUG.md L-1240） */
    private var imageFavOnly = false

    /**
     * 页面已停止（`onStop` 置位、`onStart` 复位）：停止后到达的取数与 UI 回调直接丢弃，
     * 否则「停止」形同虚设 —— 后到的写操作回调会把取数与解码重新拉起来（BUG.md L-1268）。
     */
    private var stopped = false

    /**
     * 「收藏」分组上方的收藏图片网格（BUG.md L-1250）：与键盘面板同款，用的是图库快贴那套网格组件。
     * 与「图片」分类的 [grid] 并存 —— 那个是全屏网格配翻页行，这个是收藏分组独有的两行条带。
     */
    private lateinit var favImageGrid: ClipboardImageGridView

    /** 条带（收藏 / 全部两个分组共用）的日志关联 ID（历史页没有面板那种「每次打开一个 trace」的口径） */
    private val favGridTraceId by lazy { Diagnostics.traceId("HISTFAV") }

    /**
     * 上方条带当前是否有图片（只在条带分组由网格的 onDataChanged 更新，非条带分组在 loadAsync 里复位）。
     * 文本列表为空时据此区分「库里什么都没有」与「有图片但一段文本都没有」——
     * 后者说「暂无记录」会与眼前的图片网格自相矛盾。
     */
    private var stripHasImages = false

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
        searchRow = findViewById(R.id.hist_search_row)
        actionsRow = findViewById(R.id.hist_actions)
        filterRow = findViewById(R.id.hist_filter_row)

        // 收藏分组上方的收藏图片网格（两行图库行高）：插在列表**之前** —— 收藏分组即
        // 「收藏图片网格在上、收藏文本列表在下」，与键盘面板的同一分组保持一套布局
        favImageGrid = ClipboardImageGridView(this).apply {
            visibility = View.GONE
            listener = object : ClipboardImageGridView.Listener {
                override fun onPaste(item: ClipboardDb.Item) = showImageDialog(item)
                override fun onMenu(item: ClipboardDb.Item) = showImageMenu(item)
            }
            // 没有收藏图片时收起自己：空态文案是给全屏网格写的，留在收藏列表上方会白占两行。
            // 但「空」且**没到底**时留着（BUG.md L-1299）：第一页整页不可读时 items 为空、又不是
            // 真没有图，收起会把视口高度置 0，网格的续页判据（按可见性）会跟着停摆
            onDataChanged = { empty, count, exhausted ->
                // 收藏与「全部」两个分组都用它（收藏分组只看收藏图片，全部分组看全部图片）
                if (categoryFilter == FILTER_FAVORITE || categoryFilter == null) {
                    // 「是否有图」由列表那次快照一并取（见 loadAsync）：放在这里异步更新会让
                    // 列表空态读到上一轮的旧值（删空图片后仍说「暂无文本记录」）
                    visibility = if (empty && exhausted) View.GONE else View.VISIBLE
                    // 高度按实际行数自适应（最多 [FAV_STRIP_ROWS] 行）：收藏图少时不再白占两行，
                    // 多时保持两行 + 「★ 只看收藏」全屏入口
                    if (!empty) applyFavStripHeight(count)
                }
            }
        }
        // 插在筛选行**之后**、列表与空态**之前**：收藏分组要「网格在上、列表（或空态）在下」
        (list.parent as ViewGroup).addView(
            favImageGrid, (list.parent as ViewGroup).indexOfChild(filterRow) + 1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(Prefs(this).galleryCellHeightDp * FAV_STRIP_ROWS),
            ),
        )

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
                // 图片行走**序号占位**（仅当「图片显示在「全部」分组」关闭时才会出现在列表里）：
                // 图片的浏览入口是「图片」分组（网格），混在文本流里点它只能贴文本，语义不通
                val img = item.image
                if (img != null) {
                    holder.content.text = TEXT_IMAGE_ROW_PLACEHOLDER
                    holder.content.setTextColor(getColor(R.color.text_secondary))
                } else {
                    holder.content.text = item.content
                    holder.content.setTextColor(getColor(R.color.text_primary))
                }
                holder.meta.text = buildString {
                    append(timeFmt.format(Date(item.createdAt)))
                    append("  ")
                    if (img != null) {
                        append(if (img.bytes > 0) "${img.bytes / 1024} KB" else TEXT_IMAGE_ROW_META)
                        append("  ").append(img.mime)
                    } else {
                        append(item.content.length).append(" 字")
                        append("  ").append(item.category)
                    }
                    if (item.isFavorite) append("  ★")
                }
                row.setOnClickListener {
                    // 「全部」列表里的图片行只是**序号占位**（仅当偏好关闭时出现）：点它不弹文本
                    // 对话框（content 恒空串，弹出来是个空框）；图片的浏览入口是「图片」分组网格。
                    // 长按菜单仍可用 —— 收藏 / 删除是占位行上依然说得通的动作
                    if (item.image != null) return@setOnClickListener
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

        // 图片分类的网格（与列表互斥；列数 / 行高读图库布局偏好 —— 与键盘面板网格同一对键）
        grid = findViewById(R.id.hist_grid)
        gridAdapter = object : BaseAdapter() {
            override fun getCount() = gridPageItems.size
            override fun getItem(position: Int) = gridPageItems[position]
            override fun getItemId(position: Int) = gridPageItems[position].id

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = gridPageItems[position]
                val prefs = Prefs(this@ClipboardHistoryActivity)
                val cellHeight = (prefs.galleryCellHeightDp * resources.displayMetrics.density).toInt()
                // cell = FrameLayout（缩略图 + 收藏角标）：网格里看不出哪张收藏过，只能靠长按菜单
                // 的文案反推 —— 2026-10-10 补角标；child[0]=图、child[1]=★
                val cell = (convertView as? android.widget.FrameLayout)
                    ?: android.widget.FrameLayout(this@ClipboardHistoryActivity).apply {
                        addView(ImageView(this@ClipboardHistoryActivity).apply {
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = TEXT_IMAGE_CELL
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                        })
                        addView(TextView(this@ClipboardHistoryActivity).apply {
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                gravity = Gravity.TOP or Gravity.END
                                topMargin = dp(2)
                                marginEnd = dp(4)
                            }
                            text = TEXT_IMAGE_FAVORITE
                            textSize = 14f
                            setTextColor(getColor(R.color.accent))
                        })
                    }
                cell.layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, cellHeight,
                )
                // 身份绑定：cell 会被 GridView 复用，回调里必须按 hash 比对（见 ClipboardThumbLoader.load）
                cell.tag = item.contentHash
                val thumb = cell.getChildAt(0) as ImageView
                thumb.setImageDrawable(null)
                (cell.getChildAt(1) as TextView).visibility = if (item.isFavorite) View.VISIBLE else View.GONE
                // 解码目标与面板网格同口径：min(列宽, 行高)（BUG.md L-1246）—— 只按列宽算会在
                // 「矮行高」时解出大于实际显示尺寸的位图，白付内存与解码时间（输入法常驻，会累加）
                val target = minOf(
                    resources.displayMetrics.widthPixels / prefs.galleryColumns.coerceAtLeast(1) - dp(8),
                    dp(prefs.galleryCellHeightDp),
                ).coerceAtLeast(MIN_IMAGE_TARGET_PX)
                ClipboardThumbLoader.load(this@ClipboardHistoryActivity, item.contentHash, target) { hash, bmp ->
                    if (cell.tag == hash) thumb.setImageBitmap(bmp)
                }
                // ⚠ 点击 / 长按走 GridView 的 item 监听，**不**在 cell 上挂 OnClickListener：
                // cell 自己 clickable=true 时 AbsListView 的手势分发在本机实测收不到事件
                // （2026-10-10 真机：点缩略图与长按均无响应），改回 GridView 的标准用法
                cell.isClickable = false
                cell.isLongClickable = false
                return cell
            }
        }
        grid.adapter = gridAdapter
        grid.numColumns = Prefs(this).galleryColumns.coerceAtLeast(MIN_GRID_COLUMNS)
        // 点击 / 长按按**快照**取条目（BUG.md L-1294）：与渲染同源 —— 若改回 `pageItems()` 现取，
        // 网格视图与下标会在翻页后错位，导致「打开 / 删除的图不是看到的那张」
        grid.setOnItemClickListener { _, _, position, _ ->
            gridPageItems.getOrNull(position)?.takeIf { it.image != null }?.let { showImageDialog(it) }
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            gridPageItems.getOrNull(position)?.takeIf { it.image != null }?.let { showImageMenu(it) }
            true
        }

        // 重建（旋转 / 定时换色）后恢复筛选与多选：这几个字段都是实例态，页面又注册了
        // 主题节拍器，不恢复就会出现「多选到一半旋转即丢选择」（BUG.md L-1165）
        savedInstanceState?.let { st ->
            categoryFilter = st.getString(STATE_FILTER)
            keyword = st.getString(STATE_KEYWORD) ?: ""
            pageIndex = st.getInt(STATE_PAGE, 0)
            st.getLongArray(STATE_CHECKED)?.forEach { checkedIds.add(it) }
            multiSelect = st.getBoolean(STATE_MULTI, false)
            imageFavOnly = st.getBoolean(STATE_IMAGE_FAV, false)
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
        stopped = false
        themeTicker.start()
        // 布局参数（列数 / 行高）可能刚在自定义页或键盘面板被改过：回到本页即时重排 ——
        // 否则列数还是旧的、行高已经是新的，同一处两个参数分裂（BUG.md L-1244）
        applyGridTuning()
        applyFavStripHeight()
        // 不归零：页码可能是刚恢复出来的（BUG.md L-1172），回到前台时也应当停在原页
        loadAsync(resetPage = false)
    }

    /**
     * 读回「图片」网格的列数并即时重排（BUG.md L-1244）：列数原先只在 `onCreate` 读一次，
     * 从自定义页 / 键盘面板改完布局回来不生效，而同一网格的行高是每次重读的 ⇒ 两参数分裂。
     */
    private fun applyGridTuning() {
        if (!::gridAdapter.isInitialized) return
        val columns = Prefs(this).galleryColumns.coerceAtLeast(MIN_GRID_COLUMNS)
        if (grid.numColumns != columns) {
            grid.numColumns = columns
            gridAdapter.notifyDataSetChanged()
        }
        // 收藏条带与主网格同源（BUG.md L-1261）：它自己读同一组偏好，但列数变化时要重建格子 ——
        // 只调主网格会让同一屏里的两个网格参数分裂
        if (::favImageGrid.isInitialized) favImageGrid.rerender()
    }

    /**
     * 历史页的布局调节入口（与键盘面板的「布局」键共用同一组参数）。
     *
     * 面板侧早就有这个键，历史页原先只能绕到「剪贴板自定义」页去调 —— 而用户此刻正看着这个网格，
     * 调不了它说不过去。每次 ± 立即落盘并重排（主网格 + 收藏条带一起，见 [applyGridTuning]）。
     */
    private fun showLayoutDialog() {
        val cols = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        val height = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        fun refresh() {
            cols.text = "$TEXT_LAYOUT_COLS  ${Prefs(this).galleryColumns}"
            height.text = "$TEXT_LAYOUT_HEIGHT  ${Prefs(this).galleryCellHeightDp} dp"
        }
        fun stepCols(delta: Int) {
            val cur = Prefs(this).galleryColumns
            val next = (cur + delta).coerceIn(Prefs.GALLERY_COLUMNS_MIN, Prefs.GALLERY_COLUMNS_MAX)
            if (next == cur) return
            Prefs(this).galleryColumns = next
            refresh()
            applyGridTuning()
        }
        fun stepHeight(delta: Int) {
            val cur = Prefs(this).galleryCellHeightDp
            val next = (cur + delta).coerceIn(Prefs.GALLERY_CELL_HEIGHT_MIN, Prefs.GALLERY_CELL_HEIGHT_MAX)
            if (next == cur) return
            Prefs(this).galleryCellHeightDp = next
            refresh()
            applyFavStripHeight()
            applyGridTuning()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        fun rowOf(label: TextView, onMinus: () -> Unit, onPlus: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(compactView().apply { text = "−"; setOnClickListener { onMinus() } })
            row.addView(compactView().apply { text = "＋"; setOnClickListener { onPlus() } })
            root.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        refresh()
        rowOf(cols, { stepCols(-1) }, { stepCols(1) })
        rowOf(height, { stepHeight(-LAYOUT_HEIGHT_STEP_DP) }, { stepHeight(LAYOUT_HEIGHT_STEP_DP) })
        root.addView(TextView(this).apply {
            text = TEXT_LAYOUT_HINT
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle(TEXT_IMAGE_LAYOUT)
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
    }

    /**
     * 收藏图片条带的高度 = 图库行高 × 行数。
     *
     * [imageCount] 给了就按实际行数取（1 ~ [FAV_STRIP_ROWS] 行，收藏图少时不白占空间）；
     * 没给（布局变化的重排路径）就按上限 [FAV_STRIP_ROWS] 行。
     */
    private fun applyFavStripHeight(imageCount: Int? = null) {
        if (!::favImageGrid.isInitialized) return
        val perRow = Prefs(this).galleryColumns.coerceAtLeast(MIN_GRID_COLUMNS)
        val rows = imageCount
            ?.let { ((it + perRow - 1) / perRow).coerceIn(1, FAV_STRIP_ROWS) }
            ?: FAV_STRIP_ROWS
        favImageGrid.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(Prefs(this).galleryCellHeightDp * rows),
        )
    }

    override fun onStop() {
        super.onStop()
        // 先置停止标记再收尾：此后到达的 UI 回调一律丢弃（BUG.md L-1268）—— 否则「停止」形同虚设，
        // 后到的写操作回调会把取数与解码重新拉起来
        stopped = true
        themeTicker.stop()
        // 收藏图片条带也要在页面停止时作废在飞任务并释放缓存（BUG.md L-1253）：不调的话，
        // 解码回调仍持有已 detach 的页面视图（轻则白跑，重则整页引用被任务持有），缓存也跨页累积；
        // 键盘内面板那侧（ClipboardPanelView 收起时）已有对应的停止路径，两处要对齐
        if (::favImageGrid.isInitialized) favImageGrid.stopWork()
        // 导出进度框是不可取消的，页面走了必须收起（BUG.md L-1297）：不收的话窗口无人 dismiss
        // （WindowLeaked，还会盖在新页面上、用户点不掉）。导出本身继续跑，完成回执走 message() 的闸
        runCatching { exportDlg?.dismiss() }
        exportDlg = null
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
        // 「只看收藏」也要存（BUG.md L-1267）：分类恢复了、筛选态却被静默重置，用户会发现自己
        // 又回到了「全部图片」，而重建后的标签与 chip 看起来自洽 —— 没人会告诉他是状态丢了
        outState.putBoolean(STATE_IMAGE_FAV, imageFavOnly)
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
        val defs = listOf(
            null to "全部", "URL" to "网址", "NUMBER" to "数字",
            FILTER_IMAGE to "图片", FILTER_FAVORITE to "收藏",
        )
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
        // 图片分类：操作行换成图片专用入口（多选 / 导入 / 删网址等文本功能不适用）
        if (inImageMode()) {
            // 「只看收藏」切换：收藏分类排除图片行后，这里是看「收藏过的图片」的入口（BUG.md L-1240）
            actionsRow.addView(compactView().apply {
                text = if (imageFavOnly) TEXT_IMAGE_FAV_ALL else TEXT_IMAGE_FAV_ONLY
                setOnClickListener {
                    imageFavOnly = !imageFavOnly
                    loadAsync()
                }
            })
            actionsRow.addView(compactView().apply {
                text = TEXT_IMAGE_LAYOUT
                setOnClickListener { showLayoutDialog() }
            })
            actionsRow.addView(compactView().apply {
                text = TEXT_IMAGE_EXPORT_ALL
                setOnClickListener { exportAllImages() }
            })
            // 「删图片」删的是**全部**图片（不走筛选）：只看收藏时先收起，免得被当成本次筛选的批量删除
            if (!imageFavOnly) {
                actionsRow.addView(compactView().apply {
                    text = TEXT_IMAGE_DELETE_ALL
                    setOnClickListener {
                        confirm(TEXT_IMAGE_DELETE_CONFIRM) {
                            BackgroundIo.run {
                                db.deleteByContentType(ClipboardDb.CONTENT_TYPE_IMAGE)
                                // 批量删除走 GC（多条目逐个 deleteFor 要先查 hash 表），用短保护窗立即回收
                                ClipboardImageFiles.gc(
                                    this@ClipboardHistoryActivity, db, ClipboardImageFiles.DELETE_GC_PROTECT_MS,
                                )
                                loadAsync()
                            }
                        }
                    }
                })
            }
            actionsRow.addView(compactView().apply {
                text = "刷新"
                setOnClickListener { loadAsync() }
            })
            layoutActionRow()
            return
        }
        actionsRow.addView(compactView().apply {
            text = if (multiSelect) "删除所选" else "多选"
            setOnClickListener {
                if (multiSelect) {
                    if (checkedIds.isEmpty()) return@setOnClickListener
                    confirm("删除选中的 ${checkedIds.size} 条？") {
                        BackgroundIo.run {
                            db.deleteAnyByIds(checkedIds.toList())
                            // 「全部」分类的勾选集可能含图片行（多选框在占位行上仍可勾）：行删了、
                            // 文件同步收一次，不必等下次启动 GC；批量删除走 GC 是因为逐条
                            // deleteFor 要先查 hash 表
                            ClipboardImageFiles.gc(
                                this@ClipboardHistoryActivity, db, ClipboardImageFiles.DELETE_GC_PROTECT_MS,
                            )
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
                        BackgroundIo.run {
                            db.deleteByCategory(null)
                            // 清空会删掉图片行（非收藏）：文件同步收一次（否则要等下次启动 GC）
                            // 批量删除走 GC（多条目逐个 deleteFor 要先查 hash 表），用短保护窗立即回收
                        ClipboardImageFiles.gc(
                            this@ClipboardHistoryActivity, db, ClipboardImageFiles.DELETE_GC_PROTECT_MS,
                        )
                            loadAsync()
                        }
                    }
                }
            })
            actionsRow.addView(compactView().apply {
                text = "刷新"
                setOnClickListener { loadAsync() }
            })
            // 外部文本文件按行导入（每行一条）：入口在管理页操作行 —— 用户找剪贴板管理功能的第一落点。
            // 点按钮先弹说明窗（支持格式 + 示例 + 触发键），确认后才拉文件选择器
            actionsRow.addView(compactView().apply {
                text = TEXT_IMPORT
                setOnClickListener {
                    if (!ClipboardPrefs.of(this@ClipboardHistoryActivity).enabled) {
                        message(TEXT_IMPORT_DISABLED)
                    } else {
                        showImportDialog()
                    }
                }
            })
        }
        layoutActionRow()
    }

    /**
     * 操作行排版：每个按钮紧贴文字，整行水平居中、相邻 4dp（图片分类与文本分类两条分支共用一处）。
     *
     * 不回顶铺满（不按文字长度撑开）：按钮多时由外层 HorizontalScrollView 兜住窄屏。
     */
    private fun layoutActionRow() {
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
        // 这里再钳一次是兜底（要紧的那次在 loadAsync 刷新适配器**之前**，见 [clampPageIndex]）
        clampPageIndex()
        // 不回顶铺满：每个按钮紧贴文字，整行水平居中、相邻 4dp
        pager.gravity = Gravity.CENTER
        pager.addView(compactView().apply {
            text = "上一页"
            isEnabled = pageIndex > 0
            setOnClickListener { pageIndex--; refreshPage(); buildPagerRow() }
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
                refreshPage()
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
            setOnClickListener { pageIndex++; refreshPage(); buildPagerRow() }
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
        // 页面已停止就不再启动新查询（BUG.md L-1268）：onStart 先复位该标记，正常流程不受影响
        if (stopped) return
        val category = categoryFilter
        // 图片分类与关键词互斥（D2「图片不进搜索」）：图片行的 content 恒为空串，任何关键词
        // 都会把图片全部滤掉 ⇒「在全部里搜着词再切图片」只看到「暂无图片」，像是图片丢了。
        // 切进图片分类时当场清掉残留关键词，界面上的搜索行也一并收起（见下方 imageMode 分支）。
        if (category == FILTER_IMAGE && keyword.isNotEmpty()) {
            keyword = ""
            searchEdit.setText("")
        }
        val keywordNow = keyword
        // 图片分类的「只看收藏」是叠加筛选（BUG.md L-1240）：收藏分类里不再有图片行，
        // 这里给收藏过的图片留一个入口
        val favoritesOnly = category == FILTER_FAVORITE || (category == FILTER_IMAGE && imageFavOnly)
        val catKey = if (category == FILTER_FAVORITE || category == FILTER_IMAGE) null else category
        // 内容类型维度：图片分类只看图片；「全部」分组按偏好决定列表是否纳进图片行
        // （渲染成序号占位，见「图片显示在「全部」分组」设置项）；其余分类一律只看文本
        val clipPrefs = ClipboardPrefs.of(this)
        val imageInAll = category == null && clipPrefs.imageInAllGroup
        val contentType = when {
            category == FILTER_IMAGE -> ClipboardDb.CONTENT_TYPE_IMAGE
            // 「全部」在偏好关闭（仅「图片」分组）时**要**把图片行纳进列表：它们渲染成序号占位
            // （见 TEXT_IMAGE_ROW_PLACEHOLDER），条带让位；开启时反过来 —— 图片走网格条带，
            // 列表只列文本
            category == null && !imageInAll -> null
            else -> ClipboardDb.CONTENT_TYPE_TEXT
        }
        val maxResults = clipPrefs.maxSearchResults
        BackgroundIo.run {
            val out = ArrayList<ClipboardDb.Item>()
            var cursor: ClipboardCursor? = null
            var retainedBytes = 0L
            var capped = false
            var cappedByCount = false
            while (true) {
                val page = db.recentPageAfter(cursor, LOAD_PAGE_ITEMS, catKey, favoritesOnly, contentType)
                if (page.scanned == 0) break
                cursor = page.last
                for (item in page.items) {
                    out.add(item)
                    retainedBytes += ClipboardStore.utf8ByteSize(item.content)
                    if (ClipboardStore.searchRetainLimitReached(out.size, retainedBytes,
                            maxResults = maxResults,
                            maxBytes = ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES)) {
                        capped = true
                        // 只有条数触顶才够资格报「最多 N 条」；字节预算先触发时实际远小于 N（L-995）。
                        // 且必须**库里的行真的比上限多**（BUG.md L-1327）：条目恰好等于上限时全部行
                        // 都已扫完，再报「只加载最多 N 条」是在暗示还有没显示的内容
                        cappedByCount = out.size >= maxResults
                        break
                    }
                }
                if (capped) break
            }
            val total = db.count(catKey, favoritesOnly, contentType)
            // 条带是否有图：跟着列表这次取数一起查（空态文案据此把「没有文本」与「什么都没有」分开；
            // 放在网格的异步回调里更新，列表会先读到上一轮的旧值）
            val stripImages = when (category) {
                // 「全部」分组仅在偏好开启时才有条带（关闭时图片行以序号占位留在列表里）
                null -> imageInAll && db.count(null, false, ClipboardDb.CONTENT_TYPE_IMAGE) > 0
                FILTER_FAVORITE -> db.count(null, true, ClipboardDb.CONTENT_TYPE_IMAGE) > 0
                else -> false
            }
            runOnUiThread {
                // 回调到达时页面可能已停止（BUG.md L-1268）：丢弃这一批，别把视图重新拉起来
                if (stopped) return@runOnUiThread
                stripHasImages = stripImages
                allItems.clear()
                allItems.addAll(out)
                items.clear()
                // 与面板搜索同口径：忽略大小写、全半角与零宽也互认 —— 判据只用 ClipboardSearch 一份
                // （先前这里是 `contains(keywordNow, ignoreCase = true)`：大小写对上了，符号层打的全角
                // 标点、粘来的零宽字符仍然搜不到，两处口径会越走越远，BUG.md L-1135）
                val forms = ClipboardSearch.queryForms(keywordNow)
                items.addAll(
                    if (keywordNow.isEmpty()) allItems
                    else allItems.filter { ClipboardSearch.matches(it.content, forms) }
                )
                numberById.clear()
                for ((idx, item) in allItems.withIndex()) numberById[item.id] = total - idx
                // 勾选集跨重建恢复，而重建期间库可能在变（采集在线、库到上限时插入与淘汰成对发生）⇒
                // 按这次加载到的条目裁剪，免得确认文案的条数与实际勾着的行数不一致（BUG.md L-1171）
                checkedIds.retainAll(allItems.mapTo(HashSet()) { it.id })
                if (resetPage) pageIndex = 0
                // 图片分类 = 网格模式：列表让位；多选在图片分类下不提供（批量入口有「删图片」「导出全部图片」）
                val imageMode = inImageMode()
                if (imageMode) {
                    multiSelect = false
                    checkedIds.clear()
                }
                list.visibility = if (imageMode) View.GONE else View.VISIBLE
                grid.visibility = if (imageMode) View.VISIBLE else View.GONE
                // 搜索行在图片分类下收起：搜索范围只有文本，留着输入框只会给出「暂无图片」
                searchRow.visibility = if (imageMode) View.GONE else View.VISIBLE
                // 收藏与「全部」分组：列表上方都显示图片网格（收藏分组显示收藏的图，全部分组显示全部图）。
                // 文本分类只列文本，图片不进列表 ⇒ 「全部」不给网格的话，图片在这一屏完全不可见。
                // 每次 loadAsync 都重取（BUG.md L-1260）：本方法只在切换筛选 / 搜索 / 清空 / **写操作**
                // （删除、收藏切换、转图库）/ 回前台时被调 —— 翻页是纯本地操作（只换 pageIndex），
                // 不经过这里。所以无条件重取不会带来翻页开销，而写操作后必须重取，
                // 否则条带上还留着已删 / 已取消收藏的图，点开只会得到「已删除」
                val favMode = category == FILTER_FAVORITE
                // 「全部」分组的条带只在偏好开启时显示（关闭 = 图片只在「图片」分组浏览，
                // 「全部」列表里以序号占位行代替，见设置项「图片显示在「全部」分组」）
                val stripMode = favMode || (category == null && imageInAll)
                favImageGrid.visibility = if (stripMode) View.VISIBLE else View.GONE
                if (stripMode) favImageGrid.show(favGridTraceId, favoritesOnly = favMode)
                // 页号先钳进有效范围，再刷新适配器（BUG.md L-1323）：条目变少（删除 / 裁剪 / 换筛选）
                // 后若正好停在越界页，刷新那一刻 `pageItems()` 取到空页 ⇒ `AbsListView` 记住
                // `getCount() == 0`，之后再改页号没人重新取数 ⇒ 整页空白而分页行显示 1/1
                clampPageIndex()
                adapter.notifyDataSetChanged()
                // 快照与取数同源（BUG.md L-1294）：翻页 / 改页大小 / 切筛选后网格与列表一起刷新
                syncGridSnapshot()
                // 加载被上限截断时要留一行说明（面板搜索的 resultsCapped 同款），
                // 否则「列表里就这些」与「库里还有很多没加载」在界面上无从区分。
                // 空态则要分真因（BUG.md L-1328）：库里有行却一条都解不出时不能说「暂无记录」
                val unreadable = items.isEmpty() && total > 0 && keywordNow.isEmpty()
                val hint = when {
                    imageMode && items.isEmpty() -> TEXT_IMAGE_EMPTY
                    cappedByCount && total > maxResults -> "$TEXT_TRUNCATED_PREFIX$maxResults$TEXT_TRUNCATED_SUFFIX"
                    capped -> TEXT_TRUNCATED_GENERIC
                    unreadable -> TEXT_UNREADABLE
                    // 条带有图而列表空 = 还没复制过文本，说「暂无记录」会与眼前的图片网格自相矛盾
                    // （搜索态另行判断：那是「没有匹配」，不是「没有文本」）
                    items.isEmpty() && stripHasImages && keywordNow.isEmpty() -> TEXT_EMPTY_TEXT_ONLY
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

    // ── 图片条目（实施计划 §3 Step 4/5/6；与键盘面板同语义，动作走同一组工具） ──

    /**
     * 图片分类：单击弹预览（缩略图放大 + 动作）。
     *
     * 预览用**缩略图**而不是原图：对话框宽度只有几百像素，解码 20MB 原图纯属白付；
     * 需要原图的动作（复制 / 保存 / 转移）各自解密。
     */
    private fun showImageDialog(item: ClipboardDb.Item) {
        val dlg = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = dp(96)
            // 竖长图（1080×2280 等）按比例会撑满整个对话框、把动作行挤出屏幕（真机实测 2026-10-10）：
            // 限高 360dp，图仍按 FIT_CENTER 缩放，按钮始终可见
            maxHeight = dp(360)
            contentDescription = TEXT_IMAGE_CELL
        }
        root.addView(iv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        item.image?.let { meta ->
            root.addView(TextView(this).apply {
                // 尺寸读不出的图（HEIC 一类，BUG.md L-1228 / L-1252）宽高记为 0：不显示「0×0」，
                // 但也不能只是少一截 —— 用户会不知道这张图有什么问题。写明状态与原图仍在的事实
                val dim = if (meta.width > 0 && meta.height > 0) {
                    "${meta.width}×${meta.height} · "
                } else {
                    TEXT_NO_DIM
                }
                text = "$dim${meta.bytes / 1024} KB · ${meta.mime}"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(4), 0, dp(6))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 动作分**两行**（2026-10-11 真机反馈）：一排放不下这些入口 —— 1080px 屏上第 4 个起被压成
        // 一列一列的字，删除 / 关闭 直接挤出屏幕。按语义分组：上排「出库」（复制 / 保存 / 转移），
        // 下排「管理」（收藏 / 删除 / 关闭）；每排最多 3 个，窄屏也能一眼读完
        fun makeBtnRow(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rowOut = makeBtnRow()
        val rowManage = makeBtnRow()
        fun addBtn(row: LinearLayout, text: String, onClick: () -> Unit) {
            row.addView(TextView(this@ClipboardHistoryActivity).apply {
                this.text = text
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.text_primary))
                background = getDrawable(R.drawable.table_cell_border)
                setPadding(dp(4), dp(3), dp(4), dp(3))
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        addBtn(rowOut, TEXT_IMAGE_COPY) { dlg.dismiss(); imageAction(item, ClipboardImageAction.Copy) }
        if (ClipboardImageExport.albumAvailable()) {
            addBtn(rowOut, TEXT_IMAGE_SAVE) { dlg.dismiss(); imageAction(item, ClipboardImageAction.Save) }
        }
        addBtn(rowOut, TEXT_IMAGE_MOVE) { dlg.dismiss(); imageAction(item, ClipboardImageAction.MoveToGallery) }
        addBtn(rowManage, if (item.isFavorite) TEXT_UNFAVORITE else TEXT_FAVORITE) {
            dlg.dismiss()
            BackgroundIo.run { db.setFavorite(item.id, !item.isFavorite); loadAsync(resetPage = false) }
        }
        addBtn(rowManage, TEXT_DELETE) {
            dlg.dismiss()
            confirm(TEXT_CONFIRM_DELETE_ONE) {
                BackgroundIo.run {
                    db.deleteAnyByIds(listOf(item.id))
                    // 精准删自己的两个文件（走 GC 的话保护窗会把它当「在途写入」、留在盘上 ≤10 分钟）
                    ClipboardImageFiles.deleteFor(this@ClipboardHistoryActivity, item.contentHash)
                    loadAsync(resetPage = false)
                }
            }
        }
        addBtn(rowManage, "关闭") { dlg.dismiss() }
        root.addView(rowOut, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(rowManage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            // 两排之间留一点缝：按钮自带 3dp 内边距，紧贴会看成一块
            topMargin = dp(6)
        })
        dlg.setView(root)
        dlg.show()
        iv.tag = item.contentHash
        if (ClipboardImageFiles.thumbFile(this, item.contentHash).isFile) {
            ClipboardThumbLoader.load(this, item.contentHash, dp(256)) { hash, bmp ->
                if (dlg.isShowing && hash == item.contentHash) iv.setImageBitmap(bmp)
            }
        } else {
            // 缩略图不存在（尺寸读不出的图生成失败，BUG.md L-1252）：兜底读原图，否则预览是空白框
            loadOriginalInto(iv, item)
        }
    }

    /**
     * 缩略图缺失时的预览兜底：读原图密文 → 解密 → 按预览尺寸采样解码（BUG.md L-1252）。
     *
     * 尺寸读不出的图生成不出缩略图（`ClipboardImageCodec` 拿不到宽高就产不出 JPEG），
     * 只走 [ClipboardThumbLoader] 的话预览是一个永远空白的框 —— 而图其实还在（保存 / 转移都能用）。
     * 原图可能很大（20MB 级），所以走长活池，且只在缩略图确实不存在时才走这条路。
     */
    private fun loadOriginalInto(iv: ImageView, item: ClipboardDb.Item) {
        val hash = item.contentHash
        val target = dp(256)
        BackgroundIo.runLong {
            val bmp = runCatching {
                val enc = ClipboardImageFiles.readBytes(
                    ClipboardImageFiles.encFile(this@ClipboardHistoryActivity, hash),
                ) ?: return@runCatching null
                val plain = ClipboardCrypto.decryptBytes(enc) ?: return@runCatching null
                ClipboardImageCodec.decodeForTarget(plain, target)
            }.getOrNull()
            runOnUiThread { if (iv.tag == hash) iv.setImageBitmap(bmp) }
        }
    }

    /** 图片分类：长按出菜单（复制 / 保存 / 转移 / 收藏 / 删除） */
    private fun showImageMenu(item: ClipboardDb.Item) {
        val favText = if (item.isFavorite) TEXT_UNFAVORITE else TEXT_FAVORITE
        val entries = ArrayList<String>()
        entries.add(TEXT_IMAGE_COPY)
        if (ClipboardImageExport.albumAvailable()) entries.add(TEXT_IMAGE_SAVE)
        entries.add(TEXT_IMAGE_MOVE)
        entries.add(favText)
        entries.add(TEXT_DELETE)
        AlertDialog.Builder(this)
            .setItems(entries.toTypedArray()) { _, which ->
                when (entries[which]) {
                    TEXT_IMAGE_COPY -> imageAction(item, ClipboardImageAction.Copy)
                    TEXT_IMAGE_SAVE -> imageAction(item, ClipboardImageAction.Save)
                    TEXT_IMAGE_MOVE -> imageAction(item, ClipboardImageAction.MoveToGallery)
                    TEXT_DELETE -> confirm(TEXT_CONFIRM_DELETE_ONE) {
                        BackgroundIo.run {
                            db.deleteAnyByIds(listOf(item.id))
                            // 单条删除：精准删自己的两个文件（GC 的保护窗会把它当「在途写入」留下）
                            ClipboardImageFiles.deleteFor(this@ClipboardHistoryActivity, item.contentHash)
                            loadAsync(resetPage = false)
                        }
                    }
                    else -> toggleImageFavorite(item)
                }
            }
            .show()
    }

    /**
     * 图片的收藏切换（长按菜单的「收藏 / 取消收藏」）。
     *
     * 收藏时按设置项「收藏图片时移到图库目录」决定是否把原图**移出**：开启且已绑定目录 →
     * 明文写进相册目录、删应用内密文（**行与缩略图保留** —— 收藏标记、网格缩略图都要靠
     * 这条行还在）；未绑定 → 弹框引导去绑定，本次按加密方式收藏。
     * 取消收藏只改标记：已经移进相册的图片移不回来（移动不可逆，设置项里写明了）。
     */
    private fun toggleImageFavorite(item: ClipboardDb.Item) {
        if (item.isFavorite) {
            BackgroundIo.run { db.setFavorite(item.id, false); loadAsync(resetPage = false) }
            return
        }
        if (!ClipboardPrefs.of(this).favoriteImageMoveOut) {
            BackgroundIo.run { db.setFavorite(item.id, true); loadAsync(resetPage = false) }
            return
        }
        BackgroundIo.runLong {
            val bytes = ClipboardImageExport.readOriginal(this, item)
            if (bytes == null) {
                // 原图读不出（异常情形）：照常加密收藏，别把收藏动作整个吞掉
                db.setFavorite(item.id, true)
                runOnUiThread { message(TEXT_IMAGE_UNAVAILABLE); loadAsync(resetPage = false) }
                return@runLong
            }
            val result = ClipboardImageExport.moveOutForFavorite(this, item, bytes)
            db.setFavorite(item.id, true)
            runOnUiThread {
                when (result) {
                    ClipboardImageExport.MoveResult.Ok -> message(TEXT_IMAGE_FAV_MOVED)
                    ClipboardImageExport.MoveResult.NeedBinding -> showFavBindDialog()
                    ClipboardImageExport.MoveResult.Denied -> message(TEXT_IMAGE_MOVE_DENIED)
                    ClipboardImageExport.MoveResult.Failed -> message(TEXT_IMAGE_FAV_MOVE_FAILED)
                }
                loadAsync(resetPage = false)
            }
        }
    }

    /** 收藏时未绑定图库目录的引导框（本次收藏已按加密方式完成） */
    private fun showFavBindDialog() {
        AlertDialog.Builder(this)
            .setTitle(TEXT_IMAGE_FAV_NEED_BIND_TITLE)
            .setMessage(TEXT_IMAGE_FAV_NEED_BIND)
            .setPositiveButton(TEXT_IMAGE_GO_BIND) { _, _ ->
                runCatching { startActivity(Intent(this, GallerySettingsActivity::class.java)) }
            }
            .setNegativeButton(TEXT_LATER, null)
            .show()
    }

    /** 图片动作的执行方（历史页）：与键盘面板的 IME 实现同语义，共用 `ClipboardImageExport` */
    private fun imageAction(item: ClipboardDb.Item, action: ClipboardImageAction) {
        BackgroundIo.runLong {
            val bytes = ClipboardImageExport.readOriginal(this, item)
            if (bytes == null) {
                // 「已移出到图库目录」是正常状态，与「损坏」分开说（ClipboardImageFiles.isMovedOut）
                val movedOut = ClipboardImageFiles.isMovedOut(this, item.contentHash)
                runOnUiThread { message(if (movedOut) TEXT_IMAGE_MOVED_OUT else TEXT_IMAGE_UNAVAILABLE) }
                return@runLong
            }
            var refresh = false
            val msg = when (action) {
                ClipboardImageAction.Copy ->
                    if (ClipboardImageExport.copyToSystemClipboard(this, item, bytes)) {
                        TEXT_IMAGE_COPIED
                    } else {
                        TEXT_IMAGE_COPY_FAILED
                    }
                ClipboardImageAction.Save ->
                    if (ClipboardImageExport.saveToAlbum(this, item, bytes)) TEXT_IMAGE_SAVED else TEXT_IMAGE_SAVE_FAILED
                ClipboardImageAction.MoveToGallery ->
                    when (ClipboardImageExport.moveToGalleryFolder(this, item, bytes)) {
                        ClipboardImageExport.MoveResult.Ok -> {
                            // 写成功才删历史（行 + 自己的两个文件；GC 的保护窗会留下 ≤10 分钟）
                            db.delete(item.id)
                            ClipboardImageFiles.deleteFor(this, item.contentHash)
                            refresh = true
                            TEXT_IMAGE_MOVED
                        }
                        ClipboardImageExport.MoveResult.NeedBinding -> TEXT_IMAGE_NEED_BIND
                        // 写授权缺失单独给话（BUG.md L-1322）：用户要的动作是重绑目录，不是「重试」
                        ClipboardImageExport.MoveResult.Denied -> TEXT_IMAGE_MOVE_DENIED
                        ClipboardImageExport.MoveResult.Failed -> TEXT_IMAGE_MOVE_FAILED
                    }
            }
            runOnUiThread {
                message(msg)
                if (refresh) loadAsync(resetPage = false)
            }
        }
    }

    /**
     * 一键把库内全部图片导出到**用户选定的目录**：逐张解密 → 写 SAF 文档，收尾用阻断式回执
     * 说明落点。这是图片**不进任何备份**（配置备份 / 云备份）之后的迁移出口。
     *
     * 没选过目录（或记住的那条已解析不出）就先弹系统选择器：选择器是异步的，等待期间**不算
     * 「导出中」**—— 否则用户再点一次会被答复「正在导出图片…」，而其实只是在等他选目录。
     */
    private fun exportAllImages() {
        if (!EXPORTING.compareAndSet(false, true)) {
            // 已有导出在跑（可能是重建前的实例发起的，BUG.md L-1297）：给一句反馈，不静默忽略
            message(TEXT_IMAGE_EXPORTING)
            return
        }
        val remembered = Prefs(this).clipExportTreeUri
        val uri = if (remembered.isEmpty()) null else runCatching { Uri.parse(remembered) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrEmpty()) {
            EXPORTING.set(false)
            pickExportDir()
            return
        }
        startExport(uri)
    }

    /** 打开目录选择器；设备上没有文件选择器时给一句明确答复（极少见，但静默失败最难查） */
    private fun pickExportDir() {
        runCatching { exportDirLauncher.launch(null) }.onFailure {
            Diagnostics.w(TAG, "导出目录选择器不可用: ${it.javaClass.simpleName}")
            message(TEXT_IMAGE_EXPORT_NO_PICKER)
        }
    }

    /**
     * 导出到指定目录树，收尾弹阻断式回执（路径要真的被看到）。
     *
     * **第一张当探针**：记住的目录可能已经写不进去（授权被系统收回 / 目录被删 / 存储卡拔出），
     * 那时后面每张都会以同一个原因失败，报「成功 0 张，失败 137 张」等于把「换个目录」说成了
     * 「图片都坏了」。第一张就写不进 ⇒ 中止、清掉记住的目录、请用户重选 —— 此时一个文件都没写进去。
     */
    private fun startExport(treeUri: Uri) {
        val dlg = AlertDialog.Builder(this)
            .setMessage(TEXT_IMAGE_EXPORTING)
            .setCancelable(false)
            .create()
        exportDlg = dlg
        dlg.show()
        BackgroundIo.runLong {
            var ok = 0
            var fail = 0
            var attempts = 0
            var dirBad = false
            var cursor: ClipboardCursor? = null
            while (true) {
                val page = runCatching {
                    db.recentPageAfter(cursor, 50, null, false, ClipboardDb.CONTENT_TYPE_IMAGE)
                }.getOrNull() ?: break
                if (page.scanned == 0) break
                cursor = page.last
                for (item in page.items) {
                    val bytes = ClipboardImageExport.readOriginal(this, item)
                    if (bytes == null) {
                        fail++
                        continue
                    }
                    val wrote = ClipboardImageExport.writeToTree(this, treeUri, item, bytes)
                    if (wrote == ClipboardImageExport.MoveResult.Ok) {
                        ok++
                        attempts++
                    } else {
                        if (attempts == 0) {
                            dirBad = true
                            break
                        }
                        fail++
                    }
                }
                if (dirBad) break
            }
            val path = ClipboardImageExport.displayPath(this, treeUri)
            runOnUiThread {
                EXPORTING.set(false)
                runCatching { dlg.dismiss() }
                exportDlg = null
                // 留痕（**不记路径**，L-1036）：这个功能没有其它日志，出问题（0 张 / 部分失败）时靠它分辨
                Diagnostics.i(TAG, "导出全部图片: 成功=$ok 失败=$fail 目录可用=${!dirBad}")
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    dirBad -> {
                        Prefs(this).clipExportTreeUri = ""
                        showExportDirProblem()
                    }

                    ok == 0 && fail == 0 -> message(TEXT_IMAGE_EXPORT_EMPTY)
                    else -> showExportReceipt(ok, fail, path)
                }
            }
        }
    }

    /** 导出回执（阻断式）：成功 / 失败分账 + **落点路径**，并给一个改目录的出口 */
    private fun showExportReceipt(ok: Int, fail: Int, path: String) {
        val text = buildString {
            append(String.format(Locale.US, TEXT_IMAGE_EXPORT_DONE, ok, fail))
            append('\n').append(String.format(Locale.US, TEXT_IMAGE_EXPORT_PATH, path))
            // 失败的仍留在历史里（导出不删历史），说清这一点用户才知道还能再导一次
            if (fail > 0) append('\n').append(TEXT_IMAGE_EXPORT_FAIL_HINT)
        }
        AlertDialog.Builder(this)
            .setTitle(TEXT_IMAGE_EXPORT_TITLE)
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(TEXT_IMAGE_EXPORT_ACK, null)
            .setNeutralButton(TEXT_IMAGE_EXPORT_CHANGE_DIR) { _, _ -> pickExportDir() }
            .show()
    }

    /** 目录写不进去时的阻断式说明：强调「一张没导、历史也没少」，再让用户重选 */
    private fun showExportDirProblem() {
        AlertDialog.Builder(this)
            .setTitle(TEXT_IMAGE_EXPORT_TITLE)
            .setMessage(TEXT_IMAGE_EXPORT_DIR_BAD)
            .setCancelable(false)
            .setPositiveButton(TEXT_IMAGE_EXPORT_REPICK) { _, _ -> pickExportDir() }
            .setNegativeButton(TEXT_IMAGE_EXPORT_LATER, null)
            .show()
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

    /**
     * 导入前置说明窗：支持格式 + 完整示例 + 真正触发 SAF 的「选择文件导入」。
     *
     * 为什么不直接拉开文件选择器：用户不知道「什么文件能导、怎么排版、导入后长什么样」，
     * 选错文件（二进制 / 整篇文档）只能靠失败回执事后解释。先把口径讲清、给一份可照抄的示例，
     * 再让用户去选文件。
     */
    private fun showImportDialog() {
        if (confirming) return
        confirming = true
        val limitKb = ClipboardPrefs.of(this).effectiveMaxItemBytes() / 1024
        val desc = buildString {
            append("支持纯文本文件（.txt / .md / .csv 等）；按行拆分，每行导入为一条历史记录：\n")
            append("· 空行跳过，行首尾空白自动裁掉\n")
            append("· 与库内重复的内容自动跳过（不改动原记录，可重复导入）\n")
            append("· 单行超 ").append(limitKb).append("KB 整行跳过；文件上限 ")
            append(ClipboardFileImporter.MAX_FILE_BYTES / 1024 / 1024).append("MB / ")
            append(ClipboardFileImporter.MAX_IMPORT_LINES).append(" 行")
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        root.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
        })
        // 完整示例：等宽字体 + 边框，视觉上就是一“份”文件内容（含一个空行，示范空行会被跳过）
        root.addView(TextView(this).apply {
            text = TEXT_IMPORT_SAMPLE
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_primary))
            background = getDrawable(R.drawable.table_cell_border)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        root.addView(TextView(this).apply {
            text = TEXT_IMPORT_SAMPLE_NOTE
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        val dlg = AlertDialog.Builder(this)
            .setTitle(TEXT_IMPORT)
            .setView(android.widget.ScrollView(this).apply {
                isFillViewport = true
                addView(root, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton(TEXT_IMPORT_PICK) { _, _ -> importLauncher.launch(arrayOf("*/*")) }
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnDismissListener { confirming = false }
        dlg.show()
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /**
     * 导入选中的文本文件（后台线程解析 + 入库；完成后回执并刷新列表）。
     *
     * 总开关关着直接拒绝：关着导入没有意义（面板 / 搜索都不工作），也让开关语义保持一致
     * （主拦截在入口按钮上，这里是兜底）。
     */
    private fun startImport(uri: android.net.Uri) {
        if (importing) return
        val prefs = ClipboardPrefs.of(this)
        if (!prefs.enabled) {
            message(TEXT_IMPORT_DISABLED)
            return
        }
        importing = true
        android.widget.Toast.makeText(this, TEXT_IMPORTING, android.widget.Toast.LENGTH_SHORT).show()
        // 独立线程（不占 BackgroundIo 的短活 / 长活队列）：几千行的导入不该把采集保存、面板查询
        // 这些交互短活排在后面等；与采集并发写库由 ClipboardDb 的 @Synchronized 兜底
        BackgroundIo.backgroundThread(THREAD_IMPORT) {
            val result = ClipboardFileImporter.importFromUri(applicationContext, uri, db, prefs)
            runOnUiThread {
                importing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 搜索态下导入的新条目会被旧关键词整批过滤掉（BUG.md L-1326）：回执说「新增 N 条」
                // 而列表里一条都看不到（没命中时还会同时显示「暂无记录」）。清掉搜索再刷新，
                // 并把这一步写进回执 —— 静默改掉用户的搜索状态同样不可取
                val hadKeyword = keyword.isNotEmpty()
                if (hadKeyword) {
                    keyword = ""
                    searchEdit.setText("")
                }
                val receipt = describeImport(result)
                message(if (hadKeyword) "$receipt\n（已清空搜索，便于看到刚导入的条目）" else receipt)
                // 与删除 / 收藏不同（那几处必须保留页码）：导入是往**列表头部**新增，
                // 回第 1 页才能让用户直接看到刚导入的条目
                loadAsync()
            }
        }
    }

    /** 导入回执文案：结构化统计逐项展示，任何一类跳过都不许静默 */
    private fun describeImport(result: ClipboardFileImporter.Result): String {
        val s = result.stream
            ?: return if (result.openFailed) TEXT_IMPORT_OPEN_FAILED else TEXT_IMPORT_FAILED
        if (s.lines == 0 && s.abort == null) return TEXT_IMPORT_EMPTY
        val sb = StringBuilder("新增 ").append(s.added).append(" 条")
        // 被容量上限裁掉的不能算「进了历史」（BUG.md L-1227）：用户会按回执判断「导完了」并删原文件
        val retained = result.retained
        if (retained != null && retained < s.added) {
            sb.append("\n受 ").append(ClipboardPrefs.of(this).maxItems).append(" 条上限，实际保留 ")
                .append(retained).append(" 条（最早导入的已被裁剪）")
        }
        if (s.duplicate > 0) sb.append("\n重复跳过 ").append(s.duplicate).append(" 条")
        if (s.tooLong > 0) {
            sb.append("\n超单条上限（").append(ClipboardPrefs.of(this).effectiveMaxItemBytes() / 1024)
                .append("KB）跳过 ").append(s.tooLong).append(" 条")
        }
        if (s.failed > 0) sb.append("\n入库失败 ").append(s.failed).append(" 条")
        if (s.blank > 0) sb.append("\n空行跳过 ").append(s.blank).append(" 行")
        when (s.abort) {
            ClipboardFileImporter.Abort.FILE_TOO_LARGE ->
                sb.append("\n文件超过 ").append(ClipboardFileImporter.MAX_FILE_BYTES / 1024 / 1024)
                    .append("MB，已中止（已导入的部分保留）")

            ClipboardFileImporter.Abort.TOO_MANY_LINES ->
                sb.append("\n超过 ").append(ClipboardFileImporter.MAX_IMPORT_LINES)
                    .append(" 行上限，已中止（已导入的部分保留）")

            ClipboardFileImporter.Abort.NOT_TEXT -> sb.append("\n").append(TEXT_IMPORT_NOT_TEXT)
            // 中途抛异常时上面的统计仍然有效（BUG.md L-1325）：要说清「已导入的部分保留」，
            // 否则用户按「导入失败」判断一条都没进，会再导一轮
            ClipboardFileImporter.Abort.INTERRUPTED ->
                sb.append("\n中途出错，已中止（已导入的部分保留）")

            null -> {}
        }
        return sb.toString()
    }

    /** 单按钮提示框（导入回执 / 拒绝导入）；复用 [confirming] 防重入 —— 连点会让对话框堆叠 */
    private fun message(msg: String) {
        // 页面已销毁就丢弃（BUG.md L-1296）：图片动作（保存 / 转移 / 复制）的回执来自长活池，
        // 用户在其间返回或旋转后对失效 token 的窗口 show() 会抛 BadTokenException ——
        // 未捕获即输入法进程崩溃（宿主当场失去输入法）。导入路径早就有这道闸，这里补齐
        if (isFinishing || isDestroyed) return
        if (confirming) return
        confirming = true
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("确定") { _, _ -> confirming = false }
            .setOnDismissListener { confirming = false }
            .show()
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

        /**
         * 导出全部图片进行中（BUG.md L-1297）：**进程级** —— 原先是实例字段，导出期间旋转 /
         * 定时换肤重建后新实例看到 false，同一批图会被再解一遍密、相册再多一份（文件名精确到
         * 毫秒，不会互相覆盖）。跨重建仍能识别「有导出在跑」。
         */
        private val EXPORTING = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 跨重建保存的实例态（BUG.md L-1165） */
        private const val STATE_FILTER = "hist_filter"

        /** 进实例状态的搜索词上限（字符）：更长的词匹配不到任何条目（BUG.md L-1169） */
        private const val MAX_STATE_KEYWORD = 256
        private const val STATE_KEYWORD = "hist_keyword"
        private const val STATE_PAGE = "hist_page"
        private const val STATE_CHECKED = "hist_checked"
        private const val STATE_MULTI = "hist_multi"
    private const val STATE_IMAGE_FAV = "hist_image_fav"
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

        /** 条带里有图片、但一段文本都没有时的列表空态（与「暂无记录」区分，见 stripHasImages） */
        const val TEXT_EMPTY_TEXT_ONLY = "暂无文本记录"

        /**
         * 库里有行、一条都解不出（BUG.md L-1328）：与「暂无记录」分开 —— 后者会让用户以为
         * 历史被清空了，而真相是密钥失效 / 密文损坏（新复制的内容仍会正常记录）。
         * 措辞与面板侧 [ClipboardPanelView.TEXT_EMPTY_UNREADABLE] 同源，同一场景两种入口说同样的话
         */
        const val TEXT_UNREADABLE = "历史内容无法读取\n（加密密钥失效或数据损坏；新的复制仍会正常记录）"
        const val TEXT_TRUNCATED_PREFIX = "只加载最多 "
        const val TEXT_TRUNCATED_SUFFIX = " 条（超出请用搜索缩小范围）"
        const val TEXT_TRUNCATED_GENERIC = "只加载了部分记录（受容量预算限制），可用搜索缩小范围"
        const val TEXT_PAGE_SIZE_PREFIX = "每页 "
        const val TEXT_PAGE_SIZE_SUFFIX = " 条"
        const val TEXT_IMPORT = "导入文件"
        const val TEXT_IMPORTING = "正在导入…"
        const val TEXT_IMPORT_DISABLED = "剪贴板历史已关闭：请先在「剪贴板自定义」页开启"
        const val TEXT_IMPORT_OPEN_FAILED = "打不开所选文件"
        const val TEXT_IMPORT_FAILED = "导入失败"
        const val TEXT_IMPORT_EMPTY = "文件里没有可导入的文本"
        const val TEXT_IMPORT_NOT_TEXT = "文件内容不是文本（疑似二进制），已中止导入"
        const val TEXT_IMPORT_PICK = "选择文件导入"

        /** 说明窗里的完整示例：含一个空行，示范「空行会跳过」（等宽字体 + 边框展示） */
        const val TEXT_IMPORT_SAMPLE =
            "北京南站南广场A口\n13800138000\n\n取件码 8848\nhttps://example.com/order/2026"
        const val TEXT_IMPORT_SAMPLE_NOTE = "示例文件 5 行（含 1 空行）→ 导入 4 条；数字、网址会自动归入对应分类"

        /** 导入线程名（BackgroundIo.backgroundThread 用：诊断行头能回溯到功能） */
        const val THREAD_IMPORT = "jinn-clip-import"

        /** 加载分页的取页宽度（与面板页宽无关：驻留上限才是内存护栏） */
        const val LOAD_PAGE_ITEMS = 50

        /**
         * 分类栏的伪分类：定义只在 [ClipboardFilter]，这里取同一份常量 ——
         * 三处（面板 / 历史页 / 数据层）各写一遍字符串是漂移的温床（`WHERE category = 'IMAGE'` 恒不成立的坑）
         */
        val FILTER_FAVORITE = ClipboardFilter.PSEUDO_FAVORITE
        val FILTER_IMAGE = ClipboardFilter.PSEUDO_IMAGE

        /** 图片网格的列数下限（读图库布局偏好；见 ClipboardImageGridView 的类注释） */
        const val MIN_GRID_COLUMNS = 1

        /** 图片网格：无图时的空态 */
        const val TEXT_IMAGE_EMPTY = "暂无图片"

        /**
         * 「全部」列表里图片行的占位正文（见设置项「图片显示在「全部」分组」）。
         *
         * 图片行只在偏好**关闭**（默认）时进列表：序号照常占位、正文换成这句，点它不弹对话框。
         */
        const val TEXT_IMAGE_ROW_PLACEHOLDER = "图片仅显示在「图片」分组"

        /** 占位行的元信息里体积读不出时（`bytes == 0`）显示的类别词 */
        const val TEXT_IMAGE_ROW_META = "图片"

        /** 「只看收藏」切换键的两种文案（显示的是**下一个动作**） */
        const val TEXT_IMAGE_FAV_ONLY = "★ 只看收藏"
        const val TEXT_IMAGE_FAV_ALL = "★ 看全部图片"

        /** 收藏分组里图片网格占几行（下方还要放收藏的文本列表） */
        const val FAV_STRIP_ROWS = 2

        /** 预览元信息里尺寸读不出时的前缀（BUG.md L-1252）：说明状态，别让用户以为元信息坏了 */
        const val TEXT_NO_DIM = "尺寸读不出（原图已保存）· "

        /** 图片分类操作行里的布局入口（与键盘面板的「布局」键同名同义） */
        const val TEXT_IMAGE_LAYOUT = "布局"
        const val TEXT_LAYOUT_COLS = "每行张数："
        const val TEXT_LAYOUT_HEIGHT = "缩略图行高："
        const val TEXT_LAYOUT_HINT = "与图库快贴、键盘面板的「布局」键是同一组参数，改完立即生效。"
        const val LAYOUT_HEIGHT_STEP_DP = 8

        const val TEXT_IMAGE_CELL = "剪贴板图片"

        /** 网格 cell 的收藏角标（与收藏列表行里的 ★ 同一符号） */
        const val TEXT_IMAGE_FAVORITE = "★"

        /** 图片条目的动作文案（长按菜单 / 预览对话框共用） */
        const val TEXT_IMAGE_PREVIEW_TITLE = "图片"
        const val TEXT_IMAGE_COPY = "复制到剪贴板"
        const val TEXT_IMAGE_SAVE = "保存到相册"
        const val TEXT_IMAGE_MOVE = "转移到图库目录"
        const val TEXT_IMAGE_DELETE_ALL = "删图片"
        const val TEXT_IMAGE_EXPORT_ALL = "导出全部图片"
        const val TEXT_IMAGE_EXPORTING = "正在导出图片…"

        /** 批量导出回执（成功 / 失败分账；`String.format` 两个整数参数） */
        const val TEXT_IMAGE_EXPORT_DONE = "导出完成：成功 %1\$d 张，失败 %2\$d 张"

        /** 回执标题（与按钮同名：用户点的是它，回执也报它） */
        const val TEXT_IMAGE_EXPORT_TITLE = "导出全部图片"

        /** 回执里的落点（`String.format` 一个字符串参数；路径可能含 `%`，只能走参数位） */
        const val TEXT_IMAGE_EXPORT_PATH = "位置：%1\$s"
        const val TEXT_IMAGE_EXPORT_FAIL_HINT = "失败的图片仍留在历史里，可以再导一次"
        const val TEXT_IMAGE_EXPORT_ACK = "好"
        const val TEXT_IMAGE_EXPORT_CHANGE_DIR = "改目录"
        const val TEXT_IMAGE_EXPORT_REPICK = "重新选择"
        const val TEXT_IMAGE_EXPORT_LATER = "稍后"
        const val TEXT_IMAGE_EXPORT_EMPTY = "没有可导出的图片"
        const val TEXT_IMAGE_EXPORT_NO_PICKER = "这台设备没有文件选择器，无法选择导出目录"
        const val TEXT_IMAGE_EXPORT_DIR_READONLY = "该目录不可写，请换一个（有些位置只允许读取）"
        const val TEXT_IMAGE_EXPORT_DIR_BAD =
            "这个目录写不进去（可能已被删除，或授权被系统收回）。\n" +
                "一张图片都没有导出，历史里的图片都还在。请换一个目录再试。"
        const val TEXT_IMAGE_NEED_BIND = "未绑定图库目录：设置 → 图库快贴"
        const val TEXT_IMAGE_UNAVAILABLE = "该图片已损坏或密钥失效"
        const val TEXT_IMAGE_DELETE_CONFIRM = "删除所有图片（收藏保留）？"
        const val TEXT_IMAGE_COPIED = "已复制到剪贴板，请在输入框长按粘贴"
        const val TEXT_IMAGE_COPY_FAILED = "复制图片失败，请重试"
        const val TEXT_IMAGE_SAVED = "已保存到相册"
        const val TEXT_IMAGE_SAVE_FAILED = "保存失败（图片已损坏或系统拒绝写入）"
        const val TEXT_IMAGE_MOVED = "已转移到图库目录，并从历史删除"

        /** 收藏移出（设置项「收藏图片时移到图库目录」）的回执与引导 */
        const val TEXT_IMAGE_FAV_MOVED = "已收藏，原图已移到图库目录（明文）"
        const val TEXT_IMAGE_FAV_MOVE_FAILED = "已收藏，但移到图库目录失败（原图仍加密保存在历史里）"
        const val TEXT_IMAGE_FAV_NEED_BIND_TITLE = "未绑定图库目录"
        const val TEXT_IMAGE_FAV_NEED_BIND = "已收藏（加密保存）。要按设置把原图移到图库目录，请先绑定目录。"
        const val TEXT_IMAGE_GO_BIND = "去绑定"
        const val TEXT_LATER = "稍后"

        /** 原图已被移到图库目录（不是损坏）：动作读不到图时给准确说法 */
        const val TEXT_IMAGE_MOVED_OUT = "原图已移到图库目录（相册里可看）"
        const val TEXT_IMAGE_MOVE_FAILED = "转移失败，图片仍在历史中"

        /** 目录只有读授权（BUG.md L-1322）：给出可完成的动作，别让用户反复重试 */
        const val TEXT_IMAGE_MOVE_DENIED = "该目录没有写入授权，图片仍在历史中\n请到「设置 → 图库快贴」重新绑定目录后再试"

        /** 网格缩略图的最小目标像素（窄屏 / 多列时的兜底，避免解码出 1px 图） */
        const val MIN_IMAGE_TARGET_PX = 64
    }
}
