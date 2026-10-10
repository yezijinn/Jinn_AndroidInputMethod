package com.jinn.inputmethod

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 剪贴板面板的「图片」分类视图：网格平铺缩略图（点击上屏 / 长按出操作条）。
 *
 * 独立成类而不是塞进 [ClipboardPanelView]：网格与列表的**取数、生命周期、内存模型**都不同
 * （列表逐页解密文本；网格走文件 + 缩略图缓存 + 3 线程池），混在一起会让两条分页逻辑互相干扰。
 *
 * 布局与线程纪律照抄 `GalleryPanelView`（图库快贴）：
 *  - 列数 / 行高读 [Prefs.galleryColumns] / [Prefs.galleryCellHeightDp]（**同一对偏好**：
 *    图库面板里调一次「布局」，两个网格同步生效；本面板不另设调参入口）；
 *  - 缩略图按**字节数**限容的 [LruCache]（12MB）+ 3 线程池（输入法是常驻进程，
 *    不设上限会被系统杀掉 —— 表现为键盘突然消失）；
 *  - 缓存延后释放（[CACHE_RELEASE_DELAY_MS]）：面板收起后马上又打开时不必全部重解。
 *
 * 缩略图是**密文**：渲染前解密（同 Key，见 [ClipboardCrypto.decryptBytes]）。解密 + 解码
 * 都在线程池里做，主线程只 `setImageBitmap`；cell 用 `tag = hash` 判新旧，避免复用错位。
 */
internal class ClipboardImageGridView(context: Context) : LinearLayout(context) {

    interface Listener {
        /** 点击一张图片（上屏由 IME 走 commitContent，面板不做宿主能力判断） */
        fun onPaste(item: ClipboardDb.Item)

        /** 长按一张图片（面板侧显示内联操作条） */
        fun onMenu(item: ClipboardDb.Item)
    }

    var listener: Listener? = null

    private val db by lazy { ClipboardDb.get(context) }

    private val scroll = ScrollView(context)
    private val column = LinearLayout(context)
    private val emptyText = TextView(context)

    private var columns = 0
    private var cellHeightPx = 0
    private var renderedColumns = -1

    /** 网格可用宽度（布局后才有值）：格子按它 ÷ 列数取固定宽，见 [cellWidthPx] */
    private var contentWidthPx = 0

    /** 视图世代令牌：视图被丢弃（键盘重建）后在飞任务不得再动界面 */
    private var generation = 0

    private var items = ArrayList<ClipboardDb.Item>()
    private var cursor: ClipboardCursor? = null
    private var loading = false
    private var exhausted = false
    private var traceId = ""

    /**
     * 只看收藏的图片（「图片」分类的收藏筛选用）：[show] 时指定。
     *
     * 存在的原因（BUG.md L-1240）：文本分类按 `content_type='text'` 取数后，收藏分类里不再有图片行；
     * 若网格也不能按收藏过滤，用户收藏过的图片就**没有任何入口**可看（数据还在，够不着）。
     */
    private var favoritesOnly = false

    /** 当前是否只看收藏的图片（宿主据此显示「看全部」的出口提示） */
    val isFavoritesOnly: Boolean get() = favoritesOnly

    /** 当前是否有图（面板据此选空态文案） */
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * 数据变化回调（空的 / 条数）：**条带模式**（收藏分组上方的网格）据此在无图时收起自己 ——
     * 条带里的空态文案是给全屏网格写的（「复制的图片会自动保存在这里」），留在分组上方会白占两行。
     */
    var onDataChanged: ((empty: Boolean, count: Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        column.orientation = VERTICAL
        scroll.isFillViewport = true
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        emptyText.apply {
            text = TEXT_EMPTY
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            visibility = GONE
        }
        addView(emptyText, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            if (child.height - (scroll.height + scrollY) < LOAD_AHEAD_PX) loadMore()
        }
        // 宽度变化（首次布局 / 旋转 / 字体缩放）要按新宽度重切格子：格子宽是固定像素而非 weight
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val w = width
            if (w > 0 && w != contentWidthPx) {
                contentWidthPx = w
                if (items.isNotEmpty()) {
                    column.removeAllViews()
                    appendCells(0)
                }
            }
        }
    }

    /** 面板打开 / 切到图片分类时调用：按当前布局参数重载第一页 */
    fun show(traceId: String, favoritesOnly: Boolean = false) {
        this.traceId = traceId
        this.favoritesOnly = favoritesOnly
        applyTuning()
        // 重载即作废在飞查询（BUG.md L-1242）：本方法清空 items 后重新取数，而回调的判据是
        // `gen == generation`；不推进世代的话，上一次仍在飞的查询回来时判据照样成立 ⇒ 它那一页
        // 被追加进**新**列表（重复卡片、游标与分页错位）。切分类 / 开合面板 / 改布局 / 切筛选
        // 都会走到这里，连点「±」时最容易撞上。
        generation++
        // 每次都重新取数（面板每次打开都刷列表，网格同款）：用户可能刚复制了图
        items = ArrayList()
        cursor = null
        exhausted = false
        loading = false
        column.removeAllViews()
        renderedColumns = columns
        renderEmpty()
        load(reset = true)
    }

    /**
     * 视图被丢弃（键盘重建）时作废在飞任务并释放缩略图内存。
     *
     * 只在这一刻释放：面板收起再打开（同一视图树）时缓存留着，避免每次重解一整屏；
     * 上限由 [THUMB_CACHE_BYTES] 兜住（LRU 自然淘汰），不需要额外的时间窗。
     */
    fun stopWork() {
        generation++
        ClipboardThumbLoader.clearCache()
    }

    private fun applyTuning() {
        val prefs = Prefs(context)
        columns = prefs.galleryColumns.coerceAtLeast(1)
        cellHeightPx = (prefs.galleryCellHeightDp * resources.displayMetrics.density).toInt()
        if (columns != renderedColumns && items.isNotEmpty()) {
            // 列数变了：格子宽度全部失效 ⇒ 整片重建（数据不动，只重排）
            renderedColumns = columns
            column.removeAllViews()
            appendCells(0)
        }
    }

    private fun load(reset: Boolean) {
        if (loading) return
        loading = true
        val gen = generation
        BackgroundIo.run {
            val page = runCatching {
                db.recentPageAfter(cursor, PAGE_ITEMS, null, favoritesOnly, ClipboardDb.CONTENT_TYPE_IMAGE)
            }.getOrNull()
            post {
                if (gen != generation) {
                    loading = false
                    return@post
                }
                loading = false
                if (page == null) {
                    Diagnostics.w(TAG, "[$traceId] 图片网格取数失败")
                    renderEmpty()
                    return@post
                }
                val start = items.size
                items.addAll(page.items)
                cursor = page.last
                exhausted = page.scanned <= 0
                if (reset) {
                    column.removeAllViews()
                    appendCells(0)
                } else {
                    appendCells(start)
                }
                renderEmpty()
                Diagnostics.i(TAG, "[$traceId] 图片网格: +${page.items.size} 共 ${items.size} 条（到底=$exhausted）")
            }
        }
    }

    private fun loadMore() {
        if (exhausted || loading || items.isEmpty()) return
        load(reset = false)
    }

    /** 增量追加格子（从 [from] 起）；最后一行没满时接着塞 */
    private fun appendCells(from: Int) {
        var row: LinearLayout? = column.getChildAt(column.childCount - 1) as? LinearLayout
        if (row != null && row.childCount >= columns) row = null
        for (idx in from until items.size) {
            var cur = row
            if (idx % columns == 0 || cur == null) {
                cur = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, cellHeightPx)
                }
                column.addView(cur)
            }
            cur.addView(cell(items[idx]), LayoutParams(cellWidthPx(), LayoutParams.MATCH_PARENT))
            row = cur
        }
    }

    /**
     * 格子宽度：按列数等分网格可用宽度。
     *
     * 不用 `weight=1` 均分：那样**末行不满时剩下的格子会被拉宽**（三张图在「每行 5 张」下各占 1/3 宽），
     * 与图库快贴的观感不一致 —— 图库是补空位、格子尺寸恒定。这里按固定像素切，行尾自然留白。
     * 可用宽度取自布局后的实际宽度（首帧未布局时回退屏幕宽度）。
     */
    private fun cellWidthPx(): Int {
        val avail = if (contentWidthPx > 0) contentWidthPx else resources.displayMetrics.widthPixels
        return (avail / columns.coerceAtLeast(1)).coerceAtLeast(MIN_CELL_WIDTH_PX)
    }

    private fun cell(item: ClipboardDb.Item): View {
        val target = resolveCellTargetPx()
        // cell = FrameLayout（缩略图 + 收藏角标；child[0]=图、child[1]=★）：点击/长按挂在**根**上
        // （与历史页不同：这里的父是普通 LinearLayout，不涉及 AbsListView 的手势分发）
        val view = FrameLayout(context).apply {
            tag = item.contentHash
            setOnClickListener {
                KeyFeedback.fire(TapSound.G_TEXT)
                listener?.onPaste(item)
            }
            setOnLongClickListener {
                KeyFeedback.fire(TapSound.G_FUNC)
                listener?.onMenu(item)
                true
            }
            addView(ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                )
                setPadding(CELL_PADDING_PX, CELL_PADDING_PX, CELL_PADDING_PX, CELL_PADDING_PX)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = TEXT_CELL_DESC
            })
            addView(TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = 2
                    marginEnd = 6
                }
                text = TEXT_FAVORITE
                textSize = 13f
                setTextColor(context.getColor(R.color.accent))
            })
        }
        (view.getChildAt(1) as TextView).visibility = if (item.isFavorite) View.VISIBLE else View.GONE
        bindThumb(view, item, target)
        return view
    }

    /** 单元格目标像素：列宽与行高取小（解码尺寸不必超过实际显示面积） */
    private fun resolveCellTargetPx(): Int {
        val width = (resources.displayMetrics.widthPixels - dp(24)) / columns.coerceAtLeast(1)
        return minOf(width, cellHeightPx).coerceAtLeast(MIN_TARGET_PX)
    }

    private fun bindThumb(cell: FrameLayout, item: ClipboardDb.Item, targetPx: Int) {
        val key = item.contentHash
        val thumb = cell.getChildAt(0) as ImageView
        thumb.setImageDrawable(null)
        val gen = generation
        ClipboardThumbLoader.load(context, key, targetPx) { hash, bmp ->
            // cell 可能已被复用给别的条目（或视图已被丢弃）：tag / 世代对不上就丢弃本次结果
            if (gen == generation && cell.tag == hash) thumb.setImageBitmap(bmp)
        }
    }

    private fun renderEmpty() {
        val empty = items.isEmpty()
        // 空态按筛选态分文案（BUG.md L-1251）：只看收藏时若沿用「复制的图片会自动保存在这里」，
        // 用户会以为收藏的图丢了（其实只是当前筛选下没有）
        emptyText.text = if (favoritesOnly) TEXT_EMPTY_FAV else TEXT_EMPTY
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        scroll.visibility = if (empty) View.GONE else View.VISIBLE
        onDataChanged?.invoke(empty, items.size)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ClipboardImageGrid"

        /** 每页张数（滚动到底继续取）：12 与图库面板的 PAGE_SIZE 同量级，超过一屏即可触发预取 */
        const val PAGE_ITEMS = 12

        /** 距底部不足这个距离（像素）时预取下一页 */
        const val LOAD_AHEAD_PX = 600

        const val CELL_PADDING_PX = 4
        const val MIN_TARGET_PX = 64

        /** 格子宽度下限（列数极端时兜底，避免算出 0 宽） */
        const val MIN_CELL_WIDTH_PX = 24

        const val TEXT_EMPTY = "暂无图片\n复制的图片会自动保存在这里"

        /** 只看收藏时的空态（BUG.md L-1251）：说明出口在哪，避免被当成「收藏的图丢了」 */
        const val TEXT_EMPTY_FAV = "还没有收藏的图片\n在图片上长按可以收藏，点上方「布局」可看全部"
        const val TEXT_CELL_DESC = "剪贴板图片"

        /** 网格 cell 的收藏角标（与历史页网格、收藏列表行的 ★ 同一符号） */
        const val TEXT_FAVORITE = "★"
    }
}
