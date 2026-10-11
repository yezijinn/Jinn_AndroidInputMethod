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

    /** 网格可用宽度（布局后才有值）：格子按它 ÷ 列数取固定宽，见 [cellWidthPx] */
    private var contentWidthPx = 0

    /** 视图世代令牌：视图被丢弃（键盘重建）后在飞任务不得再动界面 */
    private var generation = 0

    private var items = ArrayList<ClipboardDb.Item>()
    private var cursor: ClipboardCursor? = null
    private var loading = false
    private var exhausted = false

    /** 最近一次取数失败（BUG.md L-1299）：与「本来就没有图片」分开，空态据此说真话并给重试出口 */
    private var loadFailed = false
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
    var onDataChanged: ((empty: Boolean, count: Int, exhausted: Boolean) -> Unit)? = null

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
            // 失败态下这块就是重试入口（BUG.md L-1299）：失败若只能干等下一次重载，
            // 用户唯一的出路是关掉面板再打开 —— 那不叫出口
            setOnClickListener {
                if (!loadFailed) return@setOnClickListener
                loadFailed = false
                renderEmpty()
                load(reset = true)
            }
        }
        addView(emptyText, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            if (child.height - (scroll.height + scrollY) < LOAD_AHEAD_PX) loadMore()
        }
        // 宽度变化（首次布局 / 旋转 / 分屏 / 字体缩放）要按新宽度重切格子：格子宽是固定像素而非 weight
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val w = width
            if (w > 0 && w != contentWidthPx) {
                contentWidthPx = w
                // 列数上限依赖可用宽，宽度变了必须重算（BUG.md L-1266）；重建走 rerender ——
                // 它按新参数重排并尽力恢复滚动位置，而原来那条「清空 + 重建」的路径会把人弹回顶部
                applyTuning()
                if (items.isNotEmpty()) rerender()
            }
        }
    }

    /** 面板打开 / 切到图片分类时调用：按当前布局参数重载第一页 */
    fun show(traceId: String, favoritesOnly: Boolean = false) {
        this.traceId = traceId
        this.favoritesOnly = favoritesOnly
        // 重载即作废在飞查询（BUG.md L-1242）：本方法清空 items 后重新取数，而回调的判据是
        // `gen == generation`；不推进世代的话，上一次仍在飞的查询回来时判据照样成立 ⇒ 它那一页
        // 被追加进**新**列表（重复卡片、游标与分页错位）。切分类 / 开合面板 / 改布局 / 切筛选
        // 都会走到这里，连点「±」时最容易撞上。
        generation++
        // 先清空、再读布局参数（BUG.md L-1254）：applyTuning 会按**当时**的 items 整片重建 cell
        // 并触发缩略图绑定；排在清空之前的话，这一屏是按旧数据重建的，解码结果必然被判过期丢弃
        //（每次重载白解一屏缩略图，改列数时最明显）。
        items = ArrayList()
        applyTuning()
        // 每次都重新取数（面板每次打开都刷列表，网格同款）：用户可能刚复制了图
        cursor = null
        exhausted = false
        loading = false
        loadFailed = false
        column.removeAllViews()
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
        // 只作废在飞任务，**不清缓存**（BUG.md L-1262）：缓存是进程级共享的（键盘面板与历史页
        // 共用同一份），而本方法是按实例调的 —— 一处退出就把整份清掉，另一处正在用的一屏要全部重解。
        // 内存交由缓存的字节上限（LRU）自己淘汰，不需要在这里替别人做决定。
    }

    private fun applyTuning() {
        val prefs = Prefs(context)
        val avail = if (contentWidthPx > 0) contentWidthPx else resources.displayMetrics.widthPixels
        // 列数上限按可用宽回算（BUG.md L-1263）：极窄屏（折叠态 / 分屏）下「列数 × 格子宽下限」
        // 会超出可用宽、末列被裁。下限兜底是 24px，正常屏不会触到这个上限
        val maxFit = (avail / MIN_CELL_WIDTH_PX).coerceAtLeast(1)
        columns = prefs.galleryColumns.coerceIn(1, maxFit)
        cellHeightPx = (prefs.galleryCellHeightDp * resources.displayMetrics.density).toInt()
    }

    /**
     * 按当前布局参数**重排已加载的格子**（BUG.md L-1243）：列数 / 行高变化时数据没变，
     * 只重建 cell、保留 items 与游标 —— 翻到第 5 页再调布局不会退回第一页，也不会把
     * 刚解好的缩略图判为过期重解一遍。滚动位置尽力按原值恢复。
     */
    fun rerender() {
        val keepY = scroll.scrollY
        applyTuning()
        if (items.isEmpty()) return
        column.removeAllViews()
        appendCells(0)
        scroll.post { scroll.scrollTo(0, keepY) }
        // 重排后一屏可能装得下更多（列数 / 行高调小）：同样要复检填满（BUG.md L-1269），
        // 否则停在已加载的那一批上，后面的图又变成「够不着」
        fillViewportIfNeeded(generation)
    }

    private fun load(reset: Boolean) {
        if (loading) return
        loading = true
        val gen = generation
        // 游标在主线程抓快照（BUG.md L-1259）：字段可能与主线程的写入竞争，后台读到旧值会从错误位置翻页
        val cursorNow = cursor
        BackgroundIo.run {
            val page = runCatching {
                db.recentPageAfter(cursorNow, PAGE_ITEMS, null, favoritesOnly, ClipboardDb.CONTENT_TYPE_IMAGE)
            }.getOrNull()
            post {
                // 过期回调**只 return、不复位共享状态**（BUG.md L-1259）：loading 此刻可能属于新一代的
                // 任务，复位它会让滚动到底的判断误以为「没在加载」⇒ 同一页被取两次。面板侧的纪律是
                // 「过期即 return，绝不碰共享计数」，网格改成同款。
                if (gen != generation) return@post
                loading = false
                if (page == null) {
                    Diagnostics.w(TAG, "[$traceId] 图片网格取数失败")
                    loadFailed = true
                    renderEmpty()
                    return@post
                }
                loadFailed = false
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
                // 首屏装不满时自动续取（BUG.md L-1265）：见 [fillViewportIfNeeded]
                fillViewportIfNeeded(gen)
            }
        }
    }

    private fun loadMore() {
        // 不按「已加载集合为空」提前退出（BUG.md L-1270）：整页解密失败时集合仍为空，而取数侧判的是
        // 「扫过的行数」—— 两边口径不同 ⇒ 一边判「还没到底」，一边停止续页，后面能读的图永远翻不到。
        // 到底与否只由取数侧的 exhausted 决定
        if (exhausted || loading) return
        load(reset = false)
    }

    /**
     * 内容不足视口且还没到底时续取下一页（BUG.md L-1265 / L-1269）。
     *
     * 取数回调与重排末尾都要调：容器开了 isFillViewport（内容不足时拉伸），内容放得下就不产生滚动
     * ⇒ 挂在滚动监听上的续页永不触发。重排路径（改列数 / 行高）也要复检 —— 调小列数或行高之后
     * 一屏装得下更多，只在取数回调里补的话，同一条路径会复发「后面的图够不着」。
     * 高度要等布局完成，所以 post 一帧再测；视口高度为 0（还没布局）时不判，免得空转连取。
     */
    private fun fillViewportIfNeeded(gen: Int) {
        scroll.post {
            if (gen != generation || exhausted) return@post
            // 视口高度为 0 有两种，处置相反（BUG.md L-1299）：
            //  - 还没布局完（宽度已有、视图仍可见）⇒ 必须继续判，否则条带在「第一页整页不可读」
            //    时会停摆 —— 那时 items 为空、宿主收起条带、高度恒 0，后面能读的图再也取不到；
            //  - 宿主已把这块收起（切到别的分类 / 键盘收起）⇒ 不能续，否则会白读整库。
            // 用「是否仍可见」区分这两者，别再用高度当唯一判据。
            if (scroll.height <= 0) {
                if (!isShown || width <= 0) return@post
            }
            if (column.height <= scroll.height) loadMore()
        }
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

    /**
     * 单元格目标像素：**实测格宽**与行高取小（解码尺寸不必超过实际显示面积，BUG.md L-1263）。
     *
     * 原先按「屏幕宽 − 边距」推算，与格宽用的实测可用宽不是同源 —— 折叠屏展开 / 折叠、分屏、
     * 横竖屏切换时两者不等：偏大就白付解码与内存，偏小就沿用偏小的缓存（显示发虚）。
     */
    private fun resolveCellTargetPx(): Int =
        minOf(cellWidthPx(), cellHeightPx).coerceAtLeast(MIN_TARGET_PX)

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
        // 空态按**真因**分文案（BUG.md L-1251 / L-1299）：失败要说真话并给出重试出口，
        // 不能渲染成「复制的图片会自动保存在这里」—— 那会让用户以为图本来就不在，而图可能好好的
        emptyText.text = when {
            loadFailed && empty -> TEXT_LOAD_FAILED
            favoritesOnly -> TEXT_EMPTY_FAV
            else -> TEXT_EMPTY
        }
        emptyText.isClickable = loadFailed && empty
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        scroll.visibility = if (empty) View.GONE else View.VISIBLE
        onDataChanged?.invoke(empty, items.size, exhausted)
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

        /**
         * 只看收藏时的空态（BUG.md L-1251 / L-1255）：出口只能指向**该分支下确实存在**的控件 ——
         * 「布局」键只在图片分类可见（收藏分组的条带里它是隐藏的），提它等于给一个死出口。
         * 「图片」分类的页签在面板与历史页都是常驻可见的，指向它两处都成立。
         */
        const val TEXT_EMPTY_FAV = "还没有收藏的图片\n在图片上长按可以收藏，切到「图片」分类可看全部"

        /** 取数失败（BUG.md L-1299）：必须与「暂无图片」分开 —— 说真因，并给出重试出口 */
        const val TEXT_LOAD_FAILED = "图片加载失败\n点这里重试"
        const val TEXT_CELL_DESC = "剪贴板图片"

        /** 网格 cell 的收藏角标（与历史页网格、收藏列表行的 ★ 同一符号） */
        const val TEXT_FAVORITE = "★"
    }
}
