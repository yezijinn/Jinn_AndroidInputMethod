package com.jinn.inputmethod

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
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
 * 图片条目的长按动作（面板与历史页共用一套语义）。
 *
 * 三个动作都涉及文件 IO / 系统服务（MediaStore、SAF、剪贴板），执行方是 IME 或页面侧，
 * 面板只负责把意图递出去 —— 面板不持 Context 之外的能力，也不判断宿主是否可用。
 */
enum class ClipboardImageAction {
    /** 复制到系统剪贴板（自家 FileProvider URI + 读权限）：给不支持 commitContent 的宿主长按粘贴用 */
    Copy,

    /** 保存到相册（MediaStore，API 29+；低版本不提供） */
    Save,

    /** 转移到图库快贴的绑定目录（成功才从历史删除） */
    MoveToGallery,
}

class ClipboardPanelView(context: Context) : LinearLayout(context) {

    /** 面板回调（全部主线程） */
    interface Listener {
        /** 点击记录：IME 用当前 InputConnection 粘贴文本。返回是否成功提交。 */
        fun onPaste(text: String): Boolean

        /**
         * 点击图片条目：IME 走 `commitContent` 上屏（宿主不支持时回退写系统剪贴板）。
         * 返回「本次是否已处理完成」（true = 关面板；false = 保持面板）。
         *
         * 带默认实现：既有实现方（含测试替身）不必随接口扩展而改。
         */
        fun onPasteImage(item: ClipboardDb.Item): Boolean = false

        /** 图片条目的长按动作（保存 / 转移 / 复制）。返回是否已处理。 */
        fun onImageAction(item: ClipboardDb.Item, action: ClipboardImageAction): Boolean = false

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
    private var skin: KeyboardSkin = KeyboardSkins.LEGACY_LIGHT

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
    private lateinit var btnCategoryImage: TextView

    /** 图片分类的网格视图（与 [listView] 互斥显隐；自己的取数与缩略图生命周期） */
    private lateinit var imageGrid: ClipboardImageGridView

    /** 图片分类的「布局」键（展开 [tuneRow]）；其余分类下隐藏，该位置让给「搜索」 */
    private lateinit var btnLayout: TextView

    /** 图片分类的「只看收藏」切换键（住在调节行里，不占顶栏键位） */
    private lateinit var btnFavOnly: TextView

    /** 图片分类是否只看收藏：收藏分类不再有图片行后，这里是看「收藏过的图片」的入口（BUG.md L-1240） */
    private var imageFavOnly = false

    /** 布局调节行：每行张数 / 行高 各一组「−/+」（图片分类下点「布局」才露出） */
    private val tuneRow = LinearLayout(context)

    /** 调节行的两个数值标签 */
    private val rowCountLabel = TextView(context)
    private val rowHeightLabel = TextView(context)

    private lateinit var btnSearch: TextView
    private lateinit var btnClear: TextView

    /** 内联操作条（长按条目时显示，替代 AlertDialog，IME 内嵌面板无窗口 token） */
    private lateinit var actionBar: LinearLayout
    private lateinit var actionFavorite: TextView
    private lateinit var actionDelete: TextView

    /** 图片条目专属的三个动作（文本条目时隐藏） */
    private lateinit var actionCopy: TextView
    private lateinit var actionSave: TextView
    private lateinit var actionMove: TextView
    private var longPressItem: ClipboardDb.Item? = null

    /** 清空二次确认条（IME 内无窗口 token，用内联确认替代 AlertDialog） */
    private lateinit var confirmBar: LinearLayout

    private var currentCategory: String? = null
    private var currentItems: MutableList<ClipboardDb.Item> = mutableListOf()

    /**
     * 「库里有行、但一条都读不出来」的状态（密钥失效 / 密文损坏）。
     *
     * 只在 [refresh] 判定：此时空态必须区别于「从未复制过」，否则界面对用户撒谎
     * （明明有历史，却显示「暂无剪贴板历史」并隐藏列表）。
     */
    private var unreadableHistory = false

    /**
     * 一条都没读出来、但**后面还有没扫过的行**：空态要给「继续查找」入口（BUG.md L-76）。
     *
     * 判据来自 [ClipboardDb.fillFirstPage] 的返回值：`items.isEmpty() && nextOffset < total`
     * 只可能出现在「扫到上限提前收工」（扫完末尾时 nextOffset == total）；整表扫完且全坏时是 false。
     */
    private var scanStoppedEarly = false

    /** 本次刷新**整体失败**（查询 / 解密抛异常）：空态要说「读取失败」，不能冒充「暂无历史」（BUG.md L-144） */
    private var loadFailed = false

    /**
     * 「当前这条长按操作条」的令牌（BUG.md L-144）。
     *
     * 长按后的收藏 / 删除是**异步**的（写库走 [BackgroundIo]，前面可能还排着剪贴板入库 / 解密）。
     * 回调回来时，用户可能已经长按了另一条、或切了分类 ⇒ 旧回调若照样 `hideActionBar()`，
     * 用户刚打开的那条会被收掉（与 BUG.md L-127 同一症状）；`refresh(resetScroll = true)`
     * 还会把列表拉回顶部。令牌在「显示操作条」与「收起操作条」两处自增，回调只在令牌未变时才动界面。
     */
    private var actionToken = 0

    /** 当前分类的总条数（分页编号用：序号 = 分类总数 − 位置，与全局条目数无关） */
    private var categoryTotal = 0

    /**
     * 已扫描过的原始行数（进度；**不是** SQL OFFSET —— 分页改用键集游标，见 [nextCursor]）。
     *
     * 必须取查询回带的扫描行数，不能拿 [currentItems] 的条数顶替：解密失败的行不进列表但仍占游标位，
     * 用条数当进度会让下一页重复取到已显示的行、并把尾部行跳过。
     */
    private var nextPageOffset = 0

    /**
     * 下一页的**键集游标**：上一页最后扫描过的那一行的 `(created_at, id)`（BUG.md L-92）。
     *
     * 不用 SQL OFFSET 的原因：面板打开期间外部改一次剪贴板就是「头部插一条 + 尾部裁一条」
     * （到上限时成对发生、总条数不变）⇒ 行位置整体位移，OFFSET 会重复 / 跳过一行；
     * 键集游标只认行键，位移免疫。`null` = 还没加载过，下一次从最新一条开始。
     */
    private var nextCursor: ClipboardCursor? = null

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

    /**
     * 用户是否正在**拖动 / 惯性滑动**列表（`SCROLL_STATE_TOUCH_SCROLL` / `FLING` / `SETTLING`）。
     *
     * 长按操作条只在「用户真的滚了列表」时才允许收起（BUG.md L-127）：`onScroll` 在**每一次布局**
     * 都会回调 —— 面板打开、数据到达、乃至**操作条自己出现**导致列表变矮都会触发一次；
     * 原判据「回调即收起」于是让操作条刚显示就被自己收掉（真机实测：15:06:53.007 显示操作条，
     * 20ms 后 15:06:53.027 的布局回调又把它收起 ⇒ 长按后什么都看不到）。
     */
    private var listScrolling = false

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
            // 复用已有 ColorDrawable（MEM-24）：滚动 / notifyDataSetChanged 都会走到这里，
            // 原先每次都新建一个；只有背景不是纯色（被皮肤换成 drawable）时才重建
            val cardColor = KeyTransparency.withAlpha(
                skinColor(context, skin.functionFill, R.color.card_bg), surfaceAlpha,
            )
            val cardFace = root.background
            if (cardFace is android.graphics.drawable.ColorDrawable) {
                cardFace.color = cardColor
            } else {
                root.background = android.graphics.drawable.ColorDrawable(cardColor)
            }
            // 条目内文字同理：换皮肤后已渲染的行必须跟着变，不能只在首次构建时设一次
            holder.num.setTextColor(skinColor(context, skin.accent, R.color.accent))
            holder.content.setTextColor(skinColor(context, skin.functionGlyph, R.color.text_primary))
            holder.meta.setTextColor(skinColor(context, skin.functionHint, R.color.text_secondary))
            holder.itemId = item.id  // 身份绑定：每次渲染写稳定 ID，复用 View 时更新
            holder.num.text = (categoryTotal - pos).toString()
            // 网址 / 数字组只显示提取出的干净片段（「全部 / 收藏」仍是原文）
            holder.content.text = ClipboardClassifier.pieceFor(currentCategory, item.content)
            holder.meta.text = buildString {
                append(PanelTimes.entryStamp(item.createdAt))
                val labels = ClipboardClassifier.labelText(item.category)
                if (labels.isNotEmpty()) append(" · ").append(labels)
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
        // ── 顶栏：全部/网址/数字/收藏 + 搜索 + 清空，均分（weight=1）──
        // 面板内不放「返回」：退出走键盘功能面板的「历史」键 —— 面板打开时它变成红色「返回」
        // （见 PinyinKeyboardView.renderFunctionPanel 与 showClipboardPanel）
        val topRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        btnCategoryAll = tabButton("全部") { selectCategory(null) }
        btnCategoryUrl = tabButton("网址") { selectCategory(ClipboardClassifier.CATEGORY_URL) }
        btnCategoryNumber = tabButton("数字") { selectCategory(ClipboardClassifier.CATEGORY_NUMBER) }
        btnCategoryImage = tabButton("图片") { selectCategory(CATEGORY_IMAGE) }
        btnCategoryFavorite = tabButton("收藏") { selectCategory(CATEGORY_FAVORITE) }
        btnSearch = tabButton("搜索") { listener?.onSearch() }
        // 「布局」与「搜索」互斥：图片不进搜索，图片分类下那个位置让给布局调节（见 selectCategory）
        btnLayout = tabButton("布局") { toggleTuneRow() }
        btnLayout.visibility = View.GONE
        btnClear = tabButton("清空", TapSound.G_ERASE) { showClearConfirm() }
            .apply { setTextColor(context.getColor(R.color.danger)) }
            .also { dangerButtons += it }
        val cells = listOf(btnCategoryAll, btnCategoryUrl, btnCategoryNumber,
            btnCategoryImage, btnCategoryFavorite, btnSearch, btnLayout, btnClear)
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
        // 条目点击是「粘进宿主」，与候选词上屏同一语义（文字输入组）；长按只弹操作条，归功能组。
        // 取不到条目时不响：那说明这一行已不是它渲染时的那条，跟着不动作
        listView.setOnItemClickListener { _, view, pos, _ ->
            itemAt(pos, view)?.let {
                KeyFeedback.fire(TapSound.G_TEXT)
                handleItemClick(it)
            }
        }
        listView.setOnItemLongClickListener { _, view, pos, _ ->
            itemAt(pos, view)?.let {
                KeyFeedback.fire(TapSound.G_FUNC)
                showItemMenu(it)
            }
            true
        }
        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {
                listScrolling = scrollState != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE
            }

            override fun onScroll(view: android.widget.AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                // 用户滚动时收起操作条：滚动后它挂在「已经滚出视口」的条目上，此时点删除删的不是眼前那条，
                // 而删除后的 `refresh(resetScroll)` 会把列表拉回顶部，用户无从知道丢了哪条
                // （selectCategory 的注释已承认同一危害，此处是漏覆盖的滚动分支）
                //
                // ⚠ 判据必须是「用户真的滚了」（[listScrolling]），**不能**用「onScroll 被调用」：
                // 该方法在每一次布局都会回调（数据到达 / 面板重排 / 操作条自己出现把列表压矮…），
                // 用「回调即收起」会让操作条刚显示就被自己收掉（BUG.md L-127，真机取证：显示后 20ms 即被收起）。
                // 内容变化导致锚点失效的那些路径已有显式收起（`onPanelShown` / `selectCategory` /
                // 收藏与删除的回调 / `showClearConfirm`），不依赖这里。
                //
                // ⚠ `setOnScrollListener` 会**立即**同步回调一次，此刻 actionBar 还是未初始化的
                // lateinit（在本构造函数靠后处才赋值）⇒ 必须先判 `isInitialized`，
                // 否则每次 onCreateInputView 都抛 UninitializedPropertyAccessException（真机复现过）
                if (listScrolling && ::actionBar.isInitialized && actionBar.visibility == View.VISIBLE) hideActionBar()
                // 距底部不足 LOAD_AHEAD 条时预取下一页
                if (hasMorePages && totalItemCount > 0 &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - LOAD_AHEAD) {
                    loadNextPage()
                }
            }
        })
        addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 布局调节行（图片分类）──
        // 与图库快贴共用同一对偏好（Prefs.galleryColumns / galleryCellHeightDp）：这里调一次，
        // 图库面板与「剪贴板自定义」页同步生效；改完立即重排网格
        tuneRow.apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            rowCountLabel.textSize = 12f
            rowCountLabel.setPadding(dp(2), 0, dp(4), 0)
            rowHeightLabel.textSize = 12f
            rowHeightLabel.setPadding(dp(10), 0, dp(4), 0)
            addView(rowCountLabel)
            addView(tabButton("-") { stepColumns(-1) })
            addView(tabButton("+") { stepColumns(1) })
            addView(rowHeightLabel)
            addView(tabButton("-") { stepHeight(-HEIGHT_STEP_DP) })
            addView(tabButton("+") { stepHeight(HEIGHT_STEP_DP) })
            // 只看收藏：文本分类排除图片后，这是「收藏过的图片」的唯一入口（BUG.md L-1240）
            btnFavOnly = tabButton(TEXT_FAV_ONLY) { toggleFavOnly() }
            addView(btnFavOnly)
            visibility = View.GONE
        }
        readGridTuning()
        addView(tuneRow, lp())

        // ── 图片网格（与列表互斥；只有「图片」分类显示） ──
        imageGrid = ClipboardImageGridView(context).apply {
            visibility = GONE
            listener = object : ClipboardImageGridView.Listener {
                override fun onPaste(item: ClipboardDb.Item) {
                    // 上屏由 IME 决定（commitContent / 回退写剪贴板）；返回 true = 已处理 ⇒ 收面板
                    val handled = this@ClipboardPanelView.listener?.onPasteImage(item) ?: false
                    if (handled) this@ClipboardPanelView.listener?.onClose()
                }

                override fun onMenu(item: ClipboardDb.Item) {
                    showItemMenu(item)
                }
            }
        }
        addView(imageGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 空状态 ──
        textEmpty = TextView(context).apply {
            text = TEXT_EMPTY_IDLE
            gravity = android.view.Gravity.CENTER
            setTextColor(skinColor(context, skin.functionHint, R.color.text_secondary))
            textSize = 14f
            visibility = GONE
            // 只有在「还有没扫完的行」时空态才可点（见 scanStoppedEarly / continueScan）——
            // 其余两种空态点它什么也不会发生，文案里也不提「点这里」
            setOnClickListener {
                if (!scanStoppedEarly) return@setOnClickListener
                KeyFeedback.fire(TapSound.G_FUNC)
                continueScan()
            }
        }
        addView(textEmpty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 长按操作条（收藏/删除） ──
        actionBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        actionFavorite = tabButton("收藏") { toggleFavorite() }
        actionDelete = tabButton("删除", TapSound.G_ERASE) { deleteItem() }
        actionDelete.setTextColor(context.getColor(R.color.danger))
        dangerButtons += actionDelete
        // 图片条目的三个动作：文本条目时隐藏（同一排最多 5 键，每个约 1/5 面板宽，足够「转图库」四字）
        actionCopy = tabButton("复制") { imageAction(ClipboardImageAction.Copy) }
        actionSave = tabButton("保存") { imageAction(ClipboardImageAction.Save) }
        actionMove = tabButton("转图库") { imageAction(ClipboardImageAction.MoveToGallery) }
        val actions = listOf(actionFavorite, actionDelete, actionCopy, actionSave, actionMove)
        for (a in actions) actionBar.addView(a, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(actionBar, lp())

        // ── 清空二次确认条 ──
        confirmBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        // 文案必须与 deleteAll 的实际口径一致：只删普通记录，收藏永不参与清空（数据层保护）
        val confirmText = tabButton("清空历史？（收藏保留）", onClick = { }).apply {
            textSize = 12f
        }
        val confirmOk = tabButton("确定", TapSound.G_ERASE) {
            hideConfirmBar()
            // 清空会删掉非收藏图片行：① 文件要立刻收一次（否则要等下次启动 GC）；
            // ② 图片分类必须刷网格（refresh 只刷文本列表，网格会停在旧数据上、
            //    点已删的图只会得到「已损坏」提示）
            val imageMode = inImageMode()
            val tid = currentTraceId
            BackgroundIo.run {
                db.deleteAll()  // 数据层保护：只删普通记录，收藏保留
                // 批量删除走 GC（收藏的图片文件不能删，不能按全库 hash 一刀切），用短保护窗立即回收
                ClipboardImageFiles.gc(context, db, ClipboardImageFiles.DELETE_GC_PROTECT_MS)
                post { if (imageMode) imageGrid.show(tid) else refresh(resetScroll = true) }
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
        val imageMode = category == CATEGORY_IMAGE
        imageGrid.visibility = if (imageMode) View.VISIBLE else View.GONE
        listView.visibility = if (imageMode) View.GONE else View.VISIBLE
        textEmpty.visibility = View.GONE
        // 「布局」只在图片分类露面（该分类下搜索无意义，那个键位让给布局）：切出去时收起调节行
        btnSearch.visibility = if (imageMode) View.GONE else View.VISIBLE
        btnLayout.visibility = if (imageMode) View.VISIBLE else View.GONE
        if (!imageMode) tuneRow.visibility = View.GONE
        if (imageMode) {
            // 网格自己取数（图片走文件 + 缩略图缓存，与列表的解密分页是两条路）
            Diagnostics.i(TAG, "[$currentTraceId] 切到图片分类")
            readGridTuning()
            updateFavOnlyLabel()
            imageGrid.show(currentTraceId, favoritesOnly = imageFavOnly)
            return
        }
        refresh(resetScroll = true)
    }

    /** 当前是否处于图片分类（网格模式）：文本列表的分页与空态在此时全部让位 */
    private fun inImageMode(): Boolean = currentCategory == CATEGORY_IMAGE

    // ── 分页加载 ─────────────────────────────────────────

    /** 重新加载当前分类的列表（第一页）。查询与加解密在后台线程，主线程只提交快照。 */
    private fun refresh(resetScroll: Boolean = false) {
        val tid = currentTraceId.ifEmpty { Diagnostics.traceId("CLIP") }
        currentTraceId = tid
        val category = currentCategory
        val reqToken = ++refreshToken
        loadingPage = true
        BackgroundIo.run {
            // 分页加载：COUNT 不解密，解密只覆盖第一页（ClipboardPrefs.of(context).panelPageItems）
            val filter = ClipboardFilter.of(category)
            // 查询异常（数据库损坏、磁盘满等）绝不能把 loadingPage 永远留在 true：
            // 那会让 loadNextPage() 的第一道闸门永久拦住后续分页（静默"没有更多了"）。
            val loaded = runCatching {
                // 分页加载：COUNT 不解密，解密只覆盖首屏要显示的那些条目。
                // 首屏必须连续填页（不是只取一页）：解密失败的行对用户不可见，若它们
                // 占满了最新的一整页，只取一页会拿到空结果 ⇒ 空态 + 隐藏列表，
                // 而预取闸门要求 totalItemCount > 0 ⇒ 后面还能解密的历史永久翻不到。
                // 见 ClipboardDb.fillFirstPage（健康库上仍只取一页，无额外开销）。
                val tCount = SystemClock.elapsedRealtime()
                val total = db.count(filter.category, filter.favoritesOnly, filter.contentType)
                val countMs = SystemClock.elapsedRealtime() - tCount
                // 键集游标（BUG.md L-92 / L-117）：取页器自己持有游标，交回 fillFirstPage 的 nextOffset 仍是
                // 「已扫描行数」（`off` 只当进度基线）⇒ 两边口径一致。但 fillFirstPage 的字节预算是**取回之后**
                // 才决定收不收（见其 KDoc）⇒ 游标不能一取回就提交：accepted = 已确定被接受的那一页末键，
                // pending = 刚取回、还没确认的一页；收尾用 ClipboardDb.pageAccepted 判一次（被拒就保留 accepted，
                // 让那一页在下次分页/续扫时被重新取到 —— 否则它的条目会被永久跳过）。
                var accepted: ClipboardCursor? = null
                var pending: ClipboardCursor? = null
                var fetchOff = 0
                var pendingScanned = 0
                val page = ClipboardDb.fillFirstPage(total, ClipboardPrefs.of(context).panelPageItems) { off, lim ->
                    // 又被调用一次 = 上一页已被接受（fillFirstPage 只在接受后仍需更多时才再取）
                    if (pending != null) accepted = pending
                    val keyed = db.recentPageAfter(accepted, lim, filter.category, filter.favoritesOnly, filter.contentType)
                    pending = keyed.last
                    fetchOff = off
                    pendingScanned = keyed.scanned
                    ClipboardDb.Page(keyed.items, off + keyed.scanned)
                }
                if (pending != null && ClipboardDb.pageAccepted(page.nextOffset, fetchOff, pendingScanned)) {
                    accepted = pending
                }
                // count= / page= 分开计时：分类 / 收藏过滤没有可用索引（全表扫描），
                // 两个数字才能分别看清「库大了会不会痛」——page 里还含解密，坏行多时会明显偏高
                // （解密失败要走异常处理与日志）。见 BUG.md L-06
                Diagnostics.i(
                    TAG,
                    "[$tid] DB category=${category ?: "ALL"} total=$total page=${page.items.size} " +
                        "next=${page.nextOffset} count=${countMs}ms " +
                        "page=${SystemClock.elapsedRealtime() - tCount - countMs}ms " +
                        "thread=${Thread.currentThread().name}",
                )
                Triple(total, page, accepted)
            }
            post {
                if (reqToken != refreshToken) return@post
                loadingPage = false
                loaded.onSuccess { (total, page, cursor) ->
                    categoryTotal = total
                    currentItems = page.items.toMutableList()
                    nextPageOffset = page.nextOffset
                    nextCursor = cursor
                    hasMorePages = page.nextOffset < total
                    // 一条都读不出来但库里有行 ⇒ 空态要说真话（见 unreadableHistory）
                    unreadableHistory = currentItems.isEmpty() && total > 0
                    // 还有没扫过的行（扫到止损上限提前收工）⇒ 空态要给「继续查找」入口，否则晚到的
                    // 可读历史永远翻不到（BUG.md L-76）
                    scanStoppedEarly = currentItems.isEmpty() && nextPageOffset < total
                    loadFailed = false
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
                    // 查询 / 解密整体失败：**视图状态也必须归位**（BUG.md L-144）——
                    // 只打日志的话，列表还留着**上一个分类**的条目，而顶栏分类已经切走：
                    // 用户点一条粘贴出来的是另一个分类的正文（与「粘的与看到的是同一份」相反）；
                    // 首次加载即失败时还会是「空白面板、连空态文案都没有」。
                    // 空态要说真话：`loadFailed` 让它显示「读取失败」而不是「暂无剪贴板历史」。
                    categoryTotal = 0
                    currentItems = mutableListOf()
                    nextPageOffset = 0
                    nextCursor = null
                    hasMorePages = false
                    unreadableHistory = false
                    scanStoppedEarly = false
                    loadFailed = true
                    adapter.notifyDataSetChanged()
                    updateEmpty()
                    Diagnostics.e(TAG, "[$tid] 列表刷新失败（视角已归位）: ${it.message}", it)
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
        val cursor = nextCursor
        val reqToken = refreshToken
        loadingPage = true
        BackgroundIo.run {
            val filter = ClipboardFilter.of(category)
            // 与 refresh 一致：查询异常也要复位 loadingPage（否则分页永久停摆）
            val loaded = runCatching {
                // 面板打开期间可能又有新内容入库（用户复制）或条目被删：键集游标对位置位移免疫
                // （BUG.md L-92）⇒ 不再需要「总数对不上就回首页重载」那套兜底（它会白白把列表弹回顶部），
                // 总数只用来刷新编号基准。可读性上也一致：游标跟着「最后扫描过的行」走，不跟位置走。
                // ⚠ 分页的两处取数必须与 [refresh] / [continueScan] 同源（含 contentType）：漏传会让
                // `contentType` 缺省成 null（不限类型）⇒ 第二页起图片行混回文本列表（空串行）且
                // 编号基准跳变（BUG-1239）
                val total = db.count(filter.category, filter.favoritesOnly, filter.contentType)
                if (total != categoryTotal) {
                    Diagnostics.i(TAG, "分页: 总数 $categoryTotal → $total（编号基准已刷新，继续翻页）")
                }
                total to db.recentPageAfter(
                    cursor, ClipboardPrefs.of(context).panelPageItems,
                    filter.category, filter.favoritesOnly, filter.contentType,
                )
            }
            post {
                if (reqToken != refreshToken) return@post
                loadingPage = false
                loaded.onSuccess { (total, page) ->
                    categoryTotal = total
                    // 判停用「扫描行数」而非本页条数：整页解密失败时 items 为空但后面仍有内容，
                    // 以空页判停会让用户再也翻不到后面的条目。
                    if (page.scanned > 0) {
                        currentItems.addAll(page.items)
                        nextPageOffset += page.scanned
                        nextCursor = page.last
                    }
                    hasMorePages = page.scanned > 0 && nextPageOffset < categoryTotal
                    adapter.notifyDataSetChanged()
                    Diagnostics.i(
                        TAG,
                        "分页加载: offset=$offset +${page.items.size} scanned=${page.scanned} " +
                            "next=$nextPageOffset hasMore=$hasMorePages",
                    )
                }.onFailure {
                    Diagnostics.e(TAG, "分页加载失败（loadingPage 已复位）: ${it.message}", it)
                }
            }
        }
    }

    /**
     * 视图即将被换掉时作废在飞的查询（与 [SearchPanelView.stopBackgroundWork] 同款，
     * 见 `BUG.md` L-17）：面板的刷新/分页靠 [refreshToken] 判停，而重建键盘不走
     * `onShown` / `onHidden`，不作废就会把结果回填到已经脱离视图树的列表上。
     *
     * 剪贴板面板只取单页（50 条），代价远小于搜索的整库扫描，这一层是防御性对称。
     */
    fun stopBackgroundWork() {
        refreshToken++
        // 网格的缩略图线程池与缓存也归这里收：视图被丢弃后「缩略图线程仍钉住旧视图树」
        // 是 L-1035 同型的泄漏，释放点必须与列表的作废点同处
        if (::imageGrid.isInitialized) imageGrid.stopWork()
        Diagnostics.i(TAG, "剪贴板面板: 视图重建，作废在飞的查询")
    }

    /**
     * 空态可点：沿既有游标再扫一批（[ClipboardDb.FIRST_PAGE_MAX_SCAN_PAGES] 页），找更早的条目。
     *
     * 为什么是手动触发：首屏止损存在的意义就是**不**在每次开面板时白解密全表 —— 密钥失效时整表都
     * 读不出，自动续扫等于每次开面板把所有密文页读一遍。手动则把代价限定在「用户真的想找」的点击上
     * （每点一次多扫 ≤400 行；找不到就还是这个空态，可以再点）。
     */
    private fun continueScan() {
        if (loadingPage || !scanStoppedEarly) return
        val category = currentCategory
        val offset = nextPageOffset
        val reqToken = refreshToken
        loadingPage = true
        BackgroundIo.run {
            val filter = ClipboardFilter.of(category)
            val loaded = runCatching {
                // 键集游标（BUG.md L-92）：与 loadNextPage 同策 —— 总数只刷新编号基准，不再中断续扫
                val total = db.count(filter.category, filter.favoritesOnly, filter.contentType)
                if (total != categoryTotal) {
                    Diagnostics.i(TAG, "续扫: 总数 $categoryTotal → $total（编号基准已刷新，继续扫）")
                }
                // 命名参数（BUG.md L-86）：第三个位置参数在语义上不可读
                // （`(total, limit, maxScanPages, startOffset, fetch)` 两个可选参数同为 Int）
                // 与 refresh 同款（BUG.md L-117）：只有被 fillFirstPage 接受的页才能提交游标
                var accepted = nextCursor
                var pending: ClipboardCursor? = null
                var fetchOff = 0
                var pendingScanned = 0
                val filled = ClipboardDb.fillFirstPage(total, ClipboardPrefs.of(context).panelPageItems, startOffset = offset) { off, lim ->
                    if (pending != null) accepted = pending
                    val keyed = db.recentPageAfter(accepted, lim, filter.category, filter.favoritesOnly, filter.contentType)
                    pending = keyed.last
                    fetchOff = off
                    pendingScanned = keyed.scanned
                    ClipboardDb.Page(keyed.items, off + keyed.scanned)
                }
                if (pending != null && ClipboardDb.pageAccepted(filled.nextOffset, fetchOff, pendingScanned)) {
                    accepted = pending
                }
                Triple(filled, accepted, total)
            }
            post {
                if (reqToken != refreshToken) return@post
                loadingPage = false
                loaded.onSuccess { (page, cursor, total) ->
                    categoryTotal = total
                    Diagnostics.i(
                        TAG,
                        "续扫: 从 $offset 起读到 ${page.items.size} 条，游标 → ${page.nextOffset}",
                    )
                    // 判停用游标（页的 nextOffset = 已扫描行数），不是本页条数：整页解密失败时
                    // items 为空但游标仍在推进，以空页判停会让用户再也找不到后面的条目。
                    if (page.nextOffset > offset) {
                        currentItems.addAll(page.items)
                        nextPageOffset = page.nextOffset
                        nextCursor = cursor
                    }
                    hasMorePages = page.nextOffset > offset && page.nextOffset < categoryTotal
                    // 仍为空且还有没扫的行 ⇒ 保持可点（用户想继续找就再点）；扫到底则不再是这条死路
                    scanStoppedEarly = currentItems.isEmpty() && nextPageOffset < categoryTotal
                    adapter.notifyDataSetChanged()
                    updateEmpty()
                }
                loaded.onFailure { e ->
                    // 失败不吞：留在原空态上（可再点重试），日志留下原因
                    Diagnostics.w(TAG, "续扫失败: ${e.message}")
                }
            }
        }
    }

    /** 空态与列表可见性切换（GONE→VISIBLE 后强制重布局，避免有高度有数据却显示空白） */
    private fun updateEmpty() {
        // 图片分类的可见性由 [selectCategory] 与网格自己管：这里插手会把列表刷回前台
        // （收藏 / 删除的异步回调都会走本函数）
        if (inImageMode()) return
        val empty = currentItems.isEmpty()
        // 空态有四种含义，不能混用同一句话：读取失败 / 真没有历史 / 有行但解密不出来 /
        // 还有没扫完的行（可继续找）。前两种在 L-144 之前是**同一句**「暂无剪贴板历史」——
        // 查询失败时那句是假的，用户会以为历史被清空了。
        if (empty) {
            textEmpty.text = when {
                loadFailed -> TEXT_EMPTY_LOAD_FAILED
                scanStoppedEarly -> TEXT_EMPTY_UNREADABLE_MORE
                unreadableHistory -> TEXT_EMPTY_UNREADABLE
                else -> TEXT_EMPTY_IDLE
            }
        }
        textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        // 「可点」必须与文案同源更新（BUG.md L-85）：setOnClickListener 会把视图**永久**标成可点击，
        // 于是「暂无历史 / 全坏读不出」两种空态下读屏仍播报「双击激活」而双击毫无反应。
        // 只有「还有没扫完的行」才真的可点（continueScan）。
        textEmpty.isClickable = scanStoppedEarly
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        listView.requestLayout()
        listView.invalidate()
    }

    // ── 点击粘贴 / 长按菜单 ────────────────────────────────

    private fun handleItemClick(item: ClipboardDb.Item) {
        // 「全部」分类里的图片行只是**排序占位**（灰字、不可点）：图片的打开方式是点开大图，
        // 那在「图片」分类（网格）里做；混在文本流里点它只能贴文本，语义不通（实施计划 §D3+U1）
        if (item.image != null) return
        if (isPasting) return
        isPasting = true
        // 粘的与看到的是同一份（网址 / 数字组 = 提取出的干净片段，其余组 = 原文）
        val ok = listener?.onPaste(ClipboardClassifier.pieceFor(currentCategory, item.content)) ?: false
        isPasting = false
        if (ok) {
            listener?.onClose()
        } else {
            // 只有「没提交成功」这一个事实，分不清「连接为空 → IME 已暂存、重聚焦时自动提交」
            // 与「真提交失败」：在这里弹「粘贴失败」既会与 IME 的「内容过大，未粘贴」叠成两条，
            // 也会把暂存态报成失败。提示统一由 IME 出（它能分辨原因），这里只留日志。
            Diagnostics.w(TAG, "点击粘贴: id=${item.id} 未提交成功，保持面板")
        }
    }

    /** 长按条目：显示内联操作条（收藏/删除）。IME 内无窗口 token，不用 AlertDialog。 */
    private fun showItemMenu(item: ClipboardDb.Item) {
        // 与「清空」确认条互斥：两条都可见时按钮紧挨着，容易按到另一条上的操作
        hideConfirmBar()
        // 换令牌：此后任何**旧**的长按回调都不得再动界面（BUG.md L-144）
        actionToken++
        longPressItem = item
        // 操作条一出现就把「用户正在滚动」标志清掉（BUG.md L-132）：该标志只在 onScrollStateChanged
        // 里写，而触摸流被截断时（一指按住列表、另一指把键盘收掉）ACTION_CANCEL 可能永远不来 ⇒
        // 标志粘在 true，之后操作条**自身**引起的布局回调（onScroll）就会立刻把它收掉 ——
        // 那正是 L-127 的原症状（长按后什么都看不到）。复位点就是「保证判据不靠上一版手势的残余」。
        listScrolling = false
        actionFavorite.text = if (item.isFavorite) "取消收藏" else "收藏"
        // 图片条目多三个动作（复制 / 保存 / 转图库）；文本条目把它们藏起来（同一排按钮，均分宽度）
        val isImage = item.image != null
        val imageOnly = listOf(actionCopy, actionSave, actionMove)
        for (b in imageOnly) b.visibility = if (isImage) View.VISIBLE else View.GONE
        // 低版本（<29）没有免权限的相册写入通道：不提供「保存」（实施计划 U5：不为 3 个老版本引 SAF 分支）
        if (!ClipboardImageExport.albumAvailable()) actionSave.visibility = View.GONE
        actionBar.visibility = View.VISIBLE
        Diagnostics.i(TAG, "[$currentTraceId] 长按菜单: 显示操作条 id=${item.id}")
    }

    private fun hideActionBar() {
        actionBar.visibility = View.GONE
        longPressItem = null
        // 条都不在了 ⇒ 在飞的长按回调也不得再动界面（切分类 / 内容刷新都会走到这里，BUG.md L-144）
        actionToken++
        // 条都不在了，就没有「别被布局回调收掉」要保护；顺带把粘性标志复位（BUG.md L-132）
        listScrolling = false
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
        val token = actionToken    // 抓令牌（BUG.md L-144）：只认「我这一条」的操作条
        val imageMode = inImageMode()
        BackgroundIo.run {
            db.setFavorite(item.id, favorite)
            Diagnostics.i(TAG, "[$tid] 长按操作: 收藏切换 id=${item.id}")
            post {
                val mine = token == actionToken
                if (mine) hideActionBar()
                else Diagnostics.i(TAG, "[$tid] 长按操作回调已过期（用户已长按别的条目 / 切了分类），不动界面")
                if (mine && imageMode) imageGrid.show(tid) else refresh(resetScroll = mine)
                // 与删除一致走 resetScroll：refresh 只取第一页，不重置滚动的话已加载的多页被
                // 整体截回、ListView 的 firstPosition 又被钳到末尾，用户既不在原位置、
                // 也找不到刚操作的那一条。回顶至少是明确、可预期的行为。
                // ⚠ 但「回顶」只在本操作仍然有效时才做（令牌未变）；过期回调拉回顶部会把
                // 用户正在看的位置也不讲道理地抽走（BUG.md L-144）。
            }
        }
    }

    private fun deleteItem() {
        val item = longPressItem ?: return
        val tid = currentTraceId   // 主线程取值后再进后台，避免跨线程读视图字段
        val token = actionToken    // 抓令牌（BUG.md L-144）
        val imageMode = inImageMode()
        BackgroundIo.run {
            db.delete(item.id)
            // 图片条目：行删了、文件还在 ⇒ 精准删掉自己的两个文件
            // （走 GC 的话保护窗会把它当「在途写入」、留在盘上 ≤10 分钟）
            if (item.image != null) ClipboardImageFiles.deleteFor(context, item.contentHash)
            Diagnostics.i(TAG, "[$tid] 长按操作: 删除 id=${item.id}")
            post {
                val mine = token == actionToken
                if (mine) {
                    hideActionBar()
                } else {
                    Diagnostics.i(TAG, "[$tid] 长按操作回调已过期（用户已长按别的条目 / 切了分类），不动界面")
                }
                if (mine && imageMode) imageGrid.show(tid) else refresh(resetScroll = mine)
            }
        }
    }

    /** 图片条目的长按动作：面板只递意图（能力判断与提示都在 IME / 页面侧） */
    private fun imageAction(action: ClipboardImageAction) {
        val item = longPressItem ?: return
        if (item.image == null) return
        hideActionBar()
        if (listener?.onImageAction(item, action) != true) {
            Diagnostics.w(TAG, "[$currentTraceId] 图片动作未处理: $action id=${item.id}")
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
        if (!::btnCategoryAll.isInitialized) return
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
        if (::imageGrid.isInitialized) imageGrid.setBackgroundColor(plate)
        textEmpty.setTextColor(skinColor(context, skin.functionHint, R.color.text_secondary))
        rowCountLabel.setTextColor(skinColor(context, skin.functionHint, R.color.text_secondary))
        rowHeightLabel.setTextColor(skinColor(context, skin.functionHint, R.color.text_secondary))
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
        if (skin.isToken) context.getColor(R.color.key_ripple) else KeyboardSkins.rippleOn(base)

    /** [b] 是否为「当前选中分类」的按钮（返回 / 搜索 / 清空等非分类按钮恒为 false） */
    private fun isSelectedCategoryButton(b: TextView): Boolean {
        val selected = currentCategory
        return when (b) {
            btnCategoryAll -> selected == null
            btnCategoryUrl -> selected == ClipboardClassifier.CATEGORY_URL
            btnCategoryNumber -> selected == ClipboardClassifier.CATEGORY_NUMBER
            btnCategoryImage -> selected == CATEGORY_IMAGE
            btnCategoryFavorite -> selected == CATEGORY_FAVORITE
            else -> false
        }
    }

    private fun tabButton(label: String, group: Int = TapSound.G_FUNC, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            setTextColor(skinColor(context, skin.functionGlyph, R.color.text_primary))
            textSize = 12f
            // 固定 36dp 高的操作条 + SP 文本：系统字体放大（fontScale ≥ ~1.7）时会换行、多余的行被硬裁
            // ⇒ 退化成省略号而不是被裁掉（与 L-35 设置页开关同一口径，BUG.md L-98）
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 键面背景按当前透明度档 + 皮肤面色运行时构建（XML 的 key_bg 带不了动态 alpha；几何与其一致）
            val fill = skinColor(context, skin.functionFill, R.color.key_bg)
            background = buildKeyFaceBackground(
                context, surfaceAlpha, KEY_FACE_CORNER_DP, fill, faceRipple(fill),
            )
            isClickable = true
            // 面板动作补反馈：同一屏的键盘外圈都有声，面板内整块没有反馈时，用户听不出
            // 「点到了没有」—— 而这里的删除与清空历史都不可撤销。默认归功能组
            // （分类、搜索、长按操作条都是面板操作，不是文字输入），破坏性的那几个由调用点改组。
            setOnClickListener {
                KeyFeedback.fire(group)
                onClick()
            }
            // 注册到统一列表：换皮肤时由 applyPanelColors 重设文字与面
            tabButtons += this
        }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** 点「布局」：展开 / 收起调节行（每行张数、行高） */
    private fun toggleTuneRow() {
        val show = tuneRow.visibility != View.VISIBLE
        tuneRow.visibility = if (show) View.VISIBLE else View.GONE
        Diagnostics.i(TAG, "布局调节行: ${if (show) "展开" else "收起"}")
    }

    /**
     * 每行张数 ±1（[Prefs.galleryColumns] 的 setter 会归一），随后网格按新参数重排。
     *
     * 先判等再落盘：到边界后继续点按值不会变，那时照旧重排只是白工（缩略图缓存被清、
     * 整页重解），而按钮看起来毫无反应（与图库面板同款处置）。
     */
    private fun stepColumns(delta: Int) {
        val cur = Prefs(context).galleryColumns
        val next = (cur + delta).coerceIn(Prefs.GALLERY_COLUMNS_MIN, Prefs.GALLERY_COLUMNS_MAX)
        if (next == cur) return
        Prefs(context).galleryColumns = next
        readGridTuning()
        imageGrid.show(currentTraceId, favoritesOnly = imageFavOnly)
    }

    /** 行高 ±[HEIGHT_STEP_DP] dp（同上，到边界直接返回） */
    private fun stepHeight(delta: Int) {
        val cur = Prefs(context).galleryCellHeightDp
        val next = (cur + delta).coerceIn(Prefs.GALLERY_CELL_HEIGHT_MIN, Prefs.GALLERY_CELL_HEIGHT_MAX)
        if (next == cur) return
        Prefs(context).galleryCellHeightDp = next
        readGridTuning()
        imageGrid.show(currentTraceId, favoritesOnly = imageFavOnly)
    }

    /**
     * 切换「只看收藏的图片」并重排网格（BUG.md L-1240）。
     *
     * 筛选状态**不随切分类重置**：用户显式开的筛选，回头看图片时不该被静默关掉；
     * 键面文案始终显示「当前点它会切到什么」，所以状态是可见的。
     */
    private fun toggleFavOnly() {
        imageFavOnly = !imageFavOnly
        updateFavOnlyLabel()
        imageGrid.show(currentTraceId, favoritesOnly = imageFavOnly)
    }

    /** 刷新「只看收藏」键的文案（它标示的是**下一个动作**） */
    private fun updateFavOnlyLabel() {
        if (::btnFavOnly.isInitialized) {
            btnFavOnly.text = if (imageFavOnly) TEXT_FAV_ALL else TEXT_FAV_ONLY
        }
    }

    /** 读回布局参数并刷新两个数值标签（读的是 setter 归一之后的值，标签永远等于即将生效的档位） */
    private fun readGridTuning() {
        val prefs = Prefs(context)
        rowCountLabel.text = String.format(Locale.US, TEXT_ROW_COUNT, prefs.galleryColumns)
        rowHeightLabel.text = String.format(Locale.US, TEXT_ROW_HEIGHT, prefs.galleryCellHeightDp)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ClipboardPanel"
        /** 面板按钮的圆角（与 `key_bg.xml` 的 10dp 对齐，改 XML 时必须同步） */
        const val KEY_FACE_CORNER_DP = 10f
        // 常量统一取自 ClipboardFilter，避免 UI 与数据层各定义一份而漂移
        const val CATEGORY_FAVORITE = ClipboardFilter.PSEUDO_FAVORITE
        const val CATEGORY_IMAGE = ClipboardFilter.PSEUDO_IMAGE

        /** 行高调节步进（dp）：与图库面板的步进同值，两处调参手感一致 */
        const val HEIGHT_STEP_DP = 8

        /** 布局调节行的两个标签（与图库面板同款文案） */
        const val TEXT_ROW_COUNT = "每行 %d 张"
        const val TEXT_ROW_HEIGHT = "行高 %d dp"

        /** 「只看收藏」切换键的两种文案（显示的是**下一个动作**） */
        const val TEXT_FAV_ONLY = "★ 只看收藏"
        const val TEXT_FAV_ALL = "★ 看全部"
        /** 距底部还有多少条时预取下一页 */
        const val LOAD_AHEAD = 10

        /** 空态：从未有过历史（或历史已被清空/裁剪干净） */
        const val TEXT_EMPTY_IDLE = "暂无剪贴板历史\n复制内容后将自动保存"

        /**
         * 空态：库里有行但一条都读不出来。
         *
         * 不说「暂无历史」是硬要求 —— 那是谎报；同时要点明「新的复制仍会正常记录」：
         * 密钥失效后新条目会用新密钥加密，功能并未坏掉，用户在意的「以后还能不能用」要给答案。
         */
        const val TEXT_EMPTY_UNREADABLE = "历史内容无法读取\n（加密密钥失效或数据损坏；新的复制仍会正常记录）"

    /** 读取整体失败（查询 / 解密抛异常）：与「暂无历史」「读不出」都不同 —— 前者是没数据，后者是数据坏 */
    const val TEXT_EMPTY_LOAD_FAILED = "读取剪贴板历史失败\n（可稍后重试；新的复制仍会正常记录）"

        /**
         * 空态：**扫到止损上限仍一条都没读出来，但后面还有没扫过的行**（BUG.md L-76）。
         *
         * 与 [TEXT_EMPTY_UNREADABLE] 的区别是「还有得找」：文案明说可点，点一下沿游标再扫一批
         * （[continueScan]）。首屏止损让「最新 400 行连续坏」时不再白解密全表，代价是更早那批
         * 能解密的历史要靠这一步才能翻到 —— 不给出入口就是死路。
         */
        const val TEXT_EMPTY_UNREADABLE_MORE = "历史内容无法读取\n（点这里继续查找更早的记录；新的复制仍会正常记录）"
    }
}
