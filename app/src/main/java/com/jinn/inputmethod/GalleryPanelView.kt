package com.jinn.inputmethod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 图库快贴面板：绑定目录（SAF 目录树）下的图片格子，点一下直接插入当前输入框。
 *
 * 与 [ClipboardPanelView] 同款：占满候选栏下方的 contentArea，字母区隐藏，键盘总高由外层改 `contentArea`
 * 的 LayoutParams 决定（见 `PinyinKeyboardView.showGalleryPanel`）。选图全程不跳 Activity，输入框焦点不丢，
 * 所以不会再有「已切换输入框」那类降级提示。
 *
 * 配色与手感同样照 [ClipboardPanelView]：底色取皮肤 plate、按钮用键面几何（`buildKeyFaceBackground`）
 * 与 functionGlyph / functionFill、点击发 G_FUNC 反馈；皮肤或透明度一变由 [applyPanelColors] 统一重设。
 *
 * 目录内容在 [BackgroundIo] 里读，缩略图按采样解码并用**按字节数限容**的 [LruCache] 缓存 ——
 * 输入法是常驻进程，缩略图不设上限会被系统杀掉（表现为键盘突然消失）。
 * 授权失效（用户撤销 / 换机恢复）表现为查询不到内容，面板转为重绑提示，并给两个出口：去设置重绑、系统选择器。
 *
 * 列目录不用 `DocumentFile`（那要引 androidx.documentfile 依赖）：直接走 `DocumentsContract` 的子文档查询，
 * framework API 在 minSdk 26 天然可用。
 */
internal class GalleryPanelView(context: Context) : LinearLayout(context) {

    interface Listener {
        /** 选中一张图：IME 侧复制进 cache 后走现有 `commitContent` 链路插入 */
        fun onPick(uri: Uri)

        /** 目录读不出来（未绑定 / 授权失效）时用户要去设置里重绑 */
        fun onRebindRequested()

        /** 回退到系统相册选择器（看绑定目录之外的位置） */
        fun onSystemPicker()
    }

    var listener: Listener? = null

    /** 当前皮肤（键盘侧经 [applySkin] 推送）；面板内所有配色都由它派生 */
    private var skin: KeyboardSkin = KeyboardSkins.LEGACY_LIGHT

    /** 面透明度档（键盘侧经 [applySurfaceAlpha] 推送，重建键面时要一起用） */
    private var surfaceAlpha = 1f

    private val title = TextView(context)
    private val status = TextView(context)
    private val grid = LinearLayout(context)
    private val scroll = ScrollView(context)

    /** 本面板全部按钮：换肤 / 改透明度后统一重设文字与键面 */
    private val buttons = mutableListOf<TextView>()

    /**
     * 未绑定 / 授权失效时的「去设置里绑定」行。**常驻视图，只切可见性**。
     *
     * ⚠ 它曾经是在 [render] 里 `addView` 的（L-1025）：而 [render] 每次展开都会被调用，
     * [grid] 有自己的 `removeAllViews`、这一行却挂在面板本身没人清，于是用户每进一次面板
     * 就多一个按钮（实测进 4 次出现 4 个）。
     */
    private val rebindRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(smallButton(TEXT_GO_SETTINGS) { listener?.onRebindRequested() })
    }

    /** 缩略图每行张数（「布局」调节；读写都在 [Prefs] 里归一） */
    private var columns = 0

    /** 缩略图行高 dp（同上） */
    private var cellHeightDp = 0

    /** 「布局」展开行的两个数值标签（配色在 [applyPanelColors] 里统一给） */
    private val rowCountLabel = TextView(context)
    private val rowHeightLabel = TextView(context)

    /** 「布局」展开的调节行：每行张数 / 行高 各一组「−/+」（内容在 `init` 里填） */
    private val tuneRow = LinearLayout(context)

    private val thumbCache = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 本帧列出的图片；缩略图回填时按它找格子 */
    private var items: List<Uri> = emptyList()

    /** 当前页（0 基）与总页数：一页 [PAGE_SIZE] 张，翻页才解下一页 */
    private var pageIndex = 0
    private var pageCount = 1

    /** 标题行中间的页码标签（「当前页/总数」） */
    private val pageLabel = TextView(context)

    /** 面板收起后到达的缩略图要丢掉（每次显隐递增，在途任务据此自然失效） */
    private var generation = 0

    /**
     * 缩略图专用小线程池（[THUMB_THREADS] 条）。
     *
     * 不借剪贴板那条 [BackgroundIo]：它是单线程串行，且是全应用共用 —— 图片解码插队会拖慢
     * 剪贴板保存，反之也一样。这里要的是「首屏十几张一起解」，代价（多两个常驻线程、内存峰值
     * 高一点）是用户 2026-10-07 明确同意让掉的。
     */
    private val thumbPool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(THUMB_THREADS) { r ->
            Thread(r, "jinn-gallery-thumb").apply { isDaemon = true }
        }

    /** 在途解码的 key：并发下防同一张重复提交（滚动事件会连着来好几次） */
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var foundTotal = 0

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))

        addView(buildTitleRow(), LayoutParams(MATCH, WRAP))

        status.apply {
            textSize = 12f
            maxLines = 2
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        addView(status, LayoutParams(MATCH, WRAP))

        // 绑定异常时的入口行：常驻，只切可见性（见 rebindRow 的字段说明）
        addView(rebindRow, LayoutParams(MATCH, WRAP))
        rebindRow.visibility = GONE

        // 「布局」调节行：每行张数 / 行高，各一组「−/+」。同样常驻，点「布局」才露出来
        tuneRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            rowCountLabel.textSize = 12f
            rowCountLabel.setPadding(dp(2), 0, dp(4), 0)
            rowHeightLabel.textSize = 12f
            rowHeightLabel.setPadding(dp(10), 0, dp(4), 0)
            addView(rowCountLabel)
            addView(smallButton(TEXT_MINUS) { stepColumns(-1) })
            addView(smallButton(TEXT_PLUS) { stepColumns(1) })
            addView(rowHeightLabel)
            addView(smallButton(TEXT_MINUS) { stepHeight(-HEIGHT_STEP_DP) })
            addView(smallButton(TEXT_PLUS) { stepHeight(HEIGHT_STEP_DP) })
            visibility = GONE
        }
        addView(tuneRow, LayoutParams(MATCH, WRAP))

        // 网格参数先读一次：columns / cellHeightDp 还是 0 的话，fill 会铺出零高度的行
        readGridTuning()

        grid.orientation = VERTICAL
        scroll.isFillViewport = true
        scroll.addView(grid)
        addView(scroll, LayoutParams(MATCH, 0, 1f))

        // 建完控件再统一上色：默认皮肤先铺一版，键盘挂载后会推真实皮肤过来
        applyPanelColors()
        render(null)
    }

    /** 面板每次展开时调用：重读目录（可能换了目录，或授权已被撤销） */
    fun onPanelShown() {
        // 每次展开都按盘上现值刷一次网格参数：设置页的「图库快贴功能」页也能改这两项
        readGridTuning()
        // 「布局」调节行是临时操作：每次进入都从收起态开始，不留在屏幕上（用户 2026-10-07 要求）
        tuneRow.visibility = GONE
        generation++
        val gen = generation
        val tree = Prefs(context).galleryTreeUri
        if (tree.isEmpty()) {
            render(State.NeedsBinding)
            return
        }
        render(State.Loading)
        BackgroundIo.run {
            val result = runCatching { listImages(Uri.parse(tree)) }
            post {
                if (gen != generation) return@post
                result.fold(
                    onSuccess = { list -> if (list.isEmpty()) render(State.Empty) else showList(list) },
                    onFailure = { t ->
                        Diagnostics.w(TAG, "列目录失败: ${t.javaClass.simpleName}:${t.message}")
                        render(State.Unavailable)
                    },
                )
            }
        }
    }

    /** 面板收起：丢掉在途缩略图（递增 [generation] 即可），缩略图缓存留着下次用 */
    fun onPanelHidden() {
        generation++
        grid.removeAllViews()
        items = emptyList()
        // 收起面板的每条路径（键盘上那个红色「返回」、切到别的面板、收起键盘、换输入框）
        // 都汇到这里，所以调节行在这里收一次就够（用户 2026-10-07：展开的布局设置必须自动回收）
        tuneRow.visibility = GONE
        pageCount = 1
        pageIndex = 0
        applyPageLabel()
    }

    /** 键盘换肤：面板内所有静态配色重设（与 [ClipboardPanelView] 同一入口，由键盘侧统一调用） */
    fun applySkin(newSkin: KeyboardSkin) {
        if (newSkin.id == skin.id) return
        skin = newSkin
        applyPanelColors()
    }

    /** 面板透明度档（键面 alpha，与 [ClipboardPanelView] 同一入口） */
    fun applySurfaceAlpha(alpha: Float) {
        surfaceAlpha = alpha
        applyPanelColors()
    }

    /**
     * 键盘重建 / 面板移除前停掉在途工作。
     *
     * 两件事都要做：`generation++` 作废在途任务的结果，`shutdownNow()` 结束 [thumbPool] ——
     * 视图重建（换肤 / 符号布局变更 / 配置变化）会连面板一起换掉，而旧池的 core 线程不超时回收，
     * 不关就是每次重建永久漏三条（L-1035；X-333 的反证只覆盖到进程生命周期这一层）。
     */
    fun stopBackgroundWork() {
        generation++
        thumbPool.shutdownNow()
        // 与剪贴板面板 / 搜索面板同款留一条：这条路径原先没有任何日志，
        // 重建是否真的走到这里、旧池是否关掉，事后从日志里看不出来
        Diagnostics.i(TAG, "图库面板: 视图重建，作废在途缩略图并关池")
    }

    /** 按当前皮肤重设面板配色：底、标题、状态行、每个按钮的文字与键面 */
    private fun applyPanelColors() {
        setBackgroundColor(skinColor(context, skin.plate, R.color.app_bg))
        title.setTextColor(skinColor(context, skin.functionGlyph, R.color.text_primary))
        val hintColor = skinColor(context, skin.functionHint, R.color.text_secondary)
        status.setTextColor(hintColor)
        // 页码标签与「布局」调节行里那两个数值标签：都不是按钮，不参与下面那轮统一上色
        pageLabel.setTextColor(hintColor)
        rowCountLabel.setTextColor(hintColor)
        rowHeightLabel.setTextColor(hintColor)
        for (b in buttons) {
            b.setTextColor(skinColor(context, skin.functionGlyph, R.color.text_primary))
            val fill = skinColor(context, skin.functionFill, R.color.key_bg)
            b.background = buildKeyFaceBackground(
                context, surfaceAlpha, KEY_FACE_CORNER_DP, fill, keyFaceRipple(fill),
            )
        }
    }

    /** 点「布局」：展开 / 收起缩略图调节行（每行张数、行高） */
    private fun toggleTuneRow() {
        val show = tuneRow.visibility != VISIBLE
        tuneRow.visibility = if (show) VISIBLE else GONE
        Diagnostics.i(TAG, "布局调节行: ${if (show) "展开" else "收起"}")
    }

    /**
     * 每行张数 ±1（[Prefs.galleryColumns] 的 setter 会归一），随后按当前 items 就地重排。
     *
     * 先判等再落盘：到边界后继续点按，值不会变 —— 那时若照旧走「写盘 + 清缓存 + 整页重铺」，
     * 全是白工（缓存被清空、整页重解），而按钮看起来毫无反应（L-1043）。
     */
    private fun stepColumns(delta: Int) {
        val next = (columns + delta)
            .coerceIn(Prefs.GALLERY_COLUMNS_MIN, Prefs.GALLERY_COLUMNS_MAX)
        if (next == columns) return
        Prefs(context).galleryColumns = next
        readGridTuning()
        rebuildGrid()
    }

    /** 行高 ±[HEIGHT_STEP_DP] dp（同上，到边界直接返回） */
    private fun stepHeight(delta: Int) {
        val next = (cellHeightDp + delta)
            .coerceIn(Prefs.GALLERY_CELL_HEIGHT_MIN, Prefs.GALLERY_CELL_HEIGHT_MAX)
        if (next == cellHeightDp) return
        Prefs(context).galleryCellHeightDp = next
        readGridTuning()
        rebuildGrid()
    }

    /**
     * 从偏好读回网格参数并刷新两个数值标签。
     *
     * 读的是 setter 归一之后的值 ⇒ 标签显示的永远是即将生效的那一档，不会出现
     * 「点了 + 但数字停在范围外」。
     */
    private fun readGridTuning() {
        val prefs = Prefs(context)
        columns = prefs.galleryColumns
        cellHeightDp = prefs.galleryCellHeightDp
        rowCountLabel.text = TEXT_ROW_COUNT.format(columns)
        rowHeightLabel.text = TEXT_ROW_HEIGHT.format(cellHeightDp)
    }

    /** 参数变了就地重排（用当前 items，不重新读目录；空目录没什么可排的） */
    private fun rebuildGrid() {
        if (items.isEmpty()) return
        // 目标像素随单元格变 ⇒ 旧缩略图尺寸不匹配了，整个丢掉重解（只在用户主动调布局时发生）
        thumbCache.evictAll()
        fillPage()
        // 回到顶部：单元格尺寸已变，停在原滚动位置看起来像「跳到了别处」（L-1043，与 showList 同款处置）
        scroll.scrollTo(0, 0)
        Diagnostics.i(TAG, "网格已重排: 每行 $columns 张，行高 ${cellHeightDp}dp")
    }

    /**
     * 键面涟漪：默认皮肤走令牌（随主题明暗），其余按按钮底色明暗推导 ——
     * 与键盘侧 `rippleColor`、剪贴板面板同一口径。
     */
    private fun keyFaceRipple(base: Int): Int =
        if (skin.isToken) context.getColor(R.color.key_ripple) else KeyboardSkins.rippleOn(base)

    /**
     * 面板顶部两行：第一行目录名，第二行翻页与操作按钮。
     *
     * 原先标题和 7 个按钮挤在同一行：标题是弹性的，被压到 0 之后按钮还会溢出屏幕 ——
     * 系统字体放大到 1.3 倍时按钮合计就吃满面板宽度，末尾的「自返」被推出可视区（L-1028）。
     * 拆成两行后标题独占一行、按钮按内容宽排，互不挤占。
     */
    private fun buildTitleRow(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        title.apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(title, LayoutParams(MATCH, WRAP))

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 翻页：← 当前页/总数 →
            addView(smallButton(TEXT_PREV) { stepPage(-1) })
            pageLabel.apply {
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(6), 0, dp(6), 0)
                maxLines = 1
            }
            addView(pageLabel)
            addView(smallButton(TEXT_NEXT) { stepPage(1) })
            addView(smallButton(TEXT_LAYOUT) { toggleTuneRow() })
            addView(smallButton(TEXT_REFRESH) { onPanelShown() })
            addView(smallButton(TEXT_OTHER) { listener?.onSystemPicker() })
            // 退出口不在这里：键盘上那个「图库」键此刻已变成红色「返回」。
            // 「自动返回」开关也不在这里 —— 它属于设置而非看图时的即时操作，已挪到设置页的
            // 「图库快贴功能」子页面（见 GallerySettingsActivity）
        }
        addView(actions, LayoutParams(WRAP, WRAP))
    }

    /**
     * 面板小按钮：`TextView` + 键面背景（与 [ClipboardPanelView.tabButton] 同一套几何与配色）。
     *
     * 文字取皮肤的 functionGlyph、面取 functionFill，换肤后由 [applyPanelColors] 统一重设；
     * `maxLines = 1` + 省略号是给系统字体放大兜底（固定高度下多行会被硬裁）。
     */
    private fun smallButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            // 11sp + 8dp 内边距：翻页那 7 个按钮要在一行里排下（系统字体放大时也要），
            // 比剪贴板面板那套紧一档（L-1028）
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            // 面板动作补反馈：与剪贴板面板同组（功能切换），否则用户听不出「点到了没有」
            setOnClickListener {
                KeyFeedback.fire(TapSound.G_FUNC)
                onClick()
            }
            buttons += this
        }

    /** 一屏状态；[fill] 之外的四种都是「没有格子可点」 */
    private enum class State { Loading, Empty, NeedsBinding, Unavailable }

    private fun render(state: State?) {
        grid.removeAllViews()
        // 没有列表可翻：页码回到 1/1
        pageCount = 1
        pageIndex = 0
        applyPageLabel()
        if (state == null) {
            status.visibility = GONE
            rebindRow.visibility = GONE
            return
        }
        status.visibility = VISIBLE
        status.text = when (state) {
            State.Loading -> TEXT_LOADING
            State.Empty -> TEXT_EMPTY
            State.NeedsBinding -> TEXT_NEEDS_BINDING
            State.Unavailable -> TEXT_UNAVAILABLE
        }
        // 绑定出问题的两种状态才露出入口行 —— 常驻视图只切可见性（L-1025：以前是 addView，
        // 而 render 每次展开都会被调用，用户实测进 4 次就多出 4 个「去设置里绑定」）
        rebindRow.visibility =
            if (state == State.NeedsBinding || state == State.Unavailable) VISIBLE else GONE
    }

    /**
     * 收到目录列表：算总页数、回到第 1 页，再铺这一页。
     *
     * 分页是用户 2026-10-07 指定的：点开只加载当前这一页（[PAGE_SIZE] 张），翻页才解下一页。
     */
    private fun showList(list: List<Uri>) {
        items = list
        pageCount = maxOf(1, (list.size + PAGE_SIZE - 1) / PAGE_SIZE)
        pageIndex = 0
        applyPageLabel()
        scroll.scrollTo(0, 0)
        fillPage()
    }

    /** 翻页：改页码 → 重铺 → 回到顶部（解码只做新这一页） */
    private fun stepPage(delta: Int) {
        val next = (pageIndex + delta).coerceIn(0, pageCount - 1)
        if (next == pageIndex) return
        pageIndex = next
        applyPageLabel()
        scroll.scrollTo(0, 0)
        fillPage()
        Diagnostics.i(TAG, "翻页: ${pageIndex + 1}/$pageCount")
    }

    /** 页码标签「当前/总数」 */
    private fun applyPageLabel() {
        pageLabel.text = TEXT_PAGE.format(pageIndex + 1, pageCount)
    }

    /** 铺**当前页**的格子：每行 [columns] 个等分宽度、行高 [cellHeightDp]（都可从「布局」调） */
    private fun fillPage() {
        grid.removeAllViews()
        if (foundTotal > items.size) {
            status.visibility = VISIBLE
            status.text = TEXT_TRUNCATED.format(foundTotal)
        } else {
            status.visibility = GONE
        }
        val from = (pageIndex * PAGE_SIZE).coerceAtMost(items.size)
        val page = items.subList(from, minOf(from + PAGE_SIZE, items.size))
        val gen = generation
        page.chunked(columns).forEach { rowUris ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowUris.forEach { uri -> row.addView(cell(uri), LayoutParams(0, dp(cellHeightDp), 1f)) }
            // 末行不足时补占位，避免最后一格被拉宽
            repeat(columns - rowUris.size) {
                row.addView(View(context), LayoutParams(0, dp(cellHeightDp), 1f))
            }
            grid.addView(row, LayoutParams(MATCH, WRAP))
        }
        scroll.post { if (gen == generation) loadThumbs(gen) }
    }

    private fun cell(uri: Uri): View = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setPadding(dp(CELL_PADDING_DP), dp(CELL_PADDING_DP), dp(CELL_PADDING_DP), dp(CELL_PADDING_DP))
        tag = uri
        thumbCache.get(uri.toString())?.let { setImageBitmap(it) }
        contentDescription = TEXT_CELL
        setOnClickListener {
            // 只记 provider 维度：SAF 的路径段就是照片文件名（L-1036）
            Diagnostics.i(TAG, "选中图片: provider=${uri.authority}")
            listener?.onPick(uri)
        }
    }

    /**
     * 解码**当前页**的缩略图（分页之后：一页就这么点，整页一起解完）。
     *
     * - 并发解（[thumbPool]），不再借剪贴板那条单线程串行；
     * - 已缓存的跳过，在途的（[inFlight]）不重复提交 —— 翻页 / 刷新会连着来几次；
     * - 解码结果 `post` 回主线程再贴到格子上：View 只能在主线程碰。
     */
    private fun loadThumbs(gen: Int) {
        val uris = items
        val from = (pageIndex * PAGE_SIZE).coerceAtMost(uris.size)
        val to = minOf(from + PAGE_SIZE, uris.size)
        for (i in from until to) {
            val uri = uris[i]
            val key = uri.toString()
            if (thumbCache.get(key) != null) continue
            if (!inFlight.add(key)) continue
            try {
                thumbPool.execute {
                    // 档位在任务里现算（L-1027）：提交时算的那份可能已被「布局」改过，
                    // 用旧档解出来的图会写进刚清空的缓存、并在之后一直被命中
                    val bmp = if (gen != generation) null else decodeThumb(uri, sampleFor(columns))
                    if (bmp != null) thumbCache.put(key, bmp)
                    inFlight.remove(key)
                    if (bmp == null) return@execute
                    post { if (gen == generation) bindThumb(uri, bmp) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                inFlight.remove(key)
            }
        }
    }

    private fun bindThumb(uri: Uri, bmp: Bitmap) {
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val cell = row.getChildAt(j) as? ImageView ?: continue
                if (cell.tag == uri) {
                    cell.setImageBitmap(bmp)
                    return
                }
            }
        }
    }

    /**
     * 按 [sample] 档采样解码，**只开一次输入流**（不探尺寸）。
     *
     * 原来这里是两段式：先 `inJustDecodeBounds` 量尺寸、再按目标像素算采样率解码。那次多出来的
     * 开流在 SAF 上要几十毫秒，是首屏最贵的一步。现在按固定档一次解完，代价是小图会被缩得更狠
     * （1000px 的截图变成 62px）—— 这是用户 2026-10-07「速度优先、其他都能舍弃」明确换来的。
     *
     * `RGB_565`：缩略图不需要透明通道，内存与解码耗时都按半算。
     */
    private fun decodeThumb(uri: Uri, sample: Int): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** 采样率只按「每行几张」两档切：格子小的时候多缩一半，省一半像素 */
    private fun sampleFor(cols: Int): Int =
        if (cols >= COMPACT_COLUMNS) THUMB_SAMPLE_COMPACT else THUMB_SAMPLE

    /**
     * 列出绑定目录里的图片（按文件名排序）。
     *
     * 全量列出（面板自己分页，见 [PAGE_SIZE]），只受 [MAX_LISTED] 这个安全阀约束；超限时由状态行说明，
     * 找具体某张也可以走「单选」（系统选择器）。查询走 `DocumentsContract`，零额外依赖。
     */
    private fun listImages(tree: Uri): List<Uri> {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val found = ArrayList<Pair<String, Uri>>()
        val cursor = context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        ) ?: throw IllegalStateException("查询不到目录内容")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                if (!mime.startsWith("image/")) continue
                found.add(name to DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        foundTotal = found.size
        val dirName = docId.substringAfterLast('/').substringAfterLast(':')
        post { title.text = dirName.ifEmpty { TEXT_TITLE_DEFAULT } }
        // 分页之后这里不再按「每页张数」截断，只受安全阀约束（见 MAX_LISTED）
        return found.sortedBy { it.first }.take(MAX_LISTED).map { it.second }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "GalleryPanel"

        /** 键面圆角（与剪贴板面板同值） */
        const val KEY_FACE_CORNER_DP = 10f

        /** 布局参数常量（本类不在 ViewGroup 的静态常量作用域里，统一在这里取） */
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /**
         * 行高每按一次 ±多少 dp。
         *
         * 每行张数与行高本身是用户偏好（见 [Prefs.galleryColumns] / [Prefs.galleryCellHeightDp]），
         * 不在这里写死 —— 它俩由「布局」调节，默认 5 张 / 76dp。
         */
        const val HEIGHT_STEP_DP = 4

        /**
         * 缩略图固定采样率：**不探尺寸**直接解。
         *
         * 原来每张要开两次输入流（先量尺寸再解码），SAF 打开一次流就要几十毫秒 —— 那是首屏最贵的一步。
         * 代价是小图会被缩得更狠（1000px 的截图会变成 62px，糊到只能看轮廓），
         * 这是用户 2026-10-07「速度优先、其他都能舍弃」明确换来的。
         */
        const val THUMB_SAMPLE = 16

        /** 每行张数到这档以上时换更大的采样率（格子更小，再省一半像素） */
        const val COMPACT_COLUMNS = 7
        const val THUMB_SAMPLE_COMPACT = 32

        /** 缩略图解码线程数：首屏十几张并发解，3 条足够且不至于把内存峰值顶太高 */
        const val THUMB_THREADS = 3

        /** 单元格内边距（dp）：算目标像素时要把它扣掉 */
        const val CELL_PADDING_DP = 3

        /**
         * 每页张数：点开面板只加载当前这一页（用户 2026-10-07 指定）。
         *
         * 一页 24 张 ≈ 五行（默认一行 5 张），翻页才解下一页 —— 不再一次把整个目录的缩略图
         * 都排上队。注意它是**每页**张数，不是列表总数上限。
         */
        const val PAGE_SIZE = 24

        /**
         * 列表条数的安全阀（不是每页张数）。
         *
         * 目录里塞几千张时，光是列表本身（SAF 查询 + 排序）就会拖住；到这一档就截断，
         * 由状态行说明。设得比正常相册大得多，日常用不到。
         */
        const val MAX_LISTED = 2000

        /**
         * 缩略图缓存上限：按「上限张数 × 250px × RGB_565」估的，12MB 让整页都留在内存里。
         *
         * 常驻进程 —— 宁可多占几 MB，也别让用户滚回去时重解（用户 2026-10-07 同意为速度让内存）。
         */
        const val THUMB_CACHE_BYTES = 12 * 1024 * 1024

        const val TEXT_TITLE_DEFAULT = "图库快贴"
        const val TEXT_LAYOUT = "布局"
        const val TEXT_REFRESH = "刷新"
        const val TEXT_OTHER = "单选"
        const val TEXT_PREV = "←"
        const val TEXT_NEXT = "→"

        /** 页码标签：当前页 / 总页数 */
        const val TEXT_PAGE = "%d/%d"
        const val TEXT_MINUS = "−"
        const val TEXT_PLUS = "+"
        const val TEXT_ROW_COUNT = "每行 %d"
        const val TEXT_ROW_HEIGHT = "高 %d"
        const val TEXT_CELL = "插入这张图片"
        const val TEXT_GO_SETTINGS = "去设置里绑定"
        const val TEXT_LOADING = "正在读取目录…"
        const val TEXT_EMPTY = "这个目录里没有图片"
        const val TEXT_NEEDS_BINDING = "还没绑定图库目录：\n到「输入法主页设置 → 图库快贴目录」\n绑定一个文件夹，之后就能直接展开"
        const val TEXT_UNAVAILABLE = "读不到这个目录（授权可能已失效）：重绑一次\n或点「单选」用系统选择器"
        const val TEXT_TRUNCATED = "图太多，只列出前 $MAX_LISTED 张（共 %d 张）—— 找具体某张可用「单选」"
    }
}
