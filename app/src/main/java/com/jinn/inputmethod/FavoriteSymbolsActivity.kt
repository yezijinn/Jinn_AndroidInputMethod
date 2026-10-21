package com.jinn.inputmethod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 「收藏」分组编辑页：从设置页「编辑收藏符号」按钮进入，用户自由 DIY 符号。
 *
 * 交互：
 * - 按页分节网格展示，每个符号右上角小 ✕ 移除（后续符号前移补位、删空的页自动收起）；
 * - 底部「＋ 添加符号」：仅输入框（自由键入/粘贴），末页满 26 自动开新页；
 * - 禁止重复：添加已存在的符号提示「已存在」；
 * - 顶部「数字行文本」分区（数字行开关开着才渲染）：10 个格子对应键盘那排按钮，
 *   点开改文本、留空回默认数字；改完即时生效（只刷 10 个键的 label，不重建视图）；
 * - 改动即时落盘（[Prefs.favoriteSymbols] / [Prefs.numberRowLabels]）；
 *   [onPause] 时仅在真正改过收藏内容的情况下通知 IME 重建键盘视图
 *   （进来看看就退出不触发整块重建）。⚠ 数字行文本不走这条：它不重建视图，
 *   由 [saveLabel] 当场推 [JinnIme.onNumberRowLabelsChanged]。
 */
class FavoriteSymbolsActivity : Activity() {

    private lateinit var listContainer: LinearLayout

    /** 本次进入是否改过收藏内容（决定离开时要不要通知 IME 重建键盘视图） */
    private var dirty = false

    /** 「添加符号」对话框：代码创建的，旋转重建时不会自动恢复，必须自己登记并 dismiss */
    private var addDialog: AlertDialog? = null

    /** 添加对话框的输入框（重建时把已输入 / 已粘贴的内容带回去，BUG.md L-1136） */
    private var addInput: EditText? = null

    /** 数字行单槽编辑对话框（同 [addDialog]：不登记就会随页面旋转泄漏） */
    private var labelDialog: AlertDialog? = null

    /** 数字行单槽编辑的输入框（重建时带回已输入内容，BUG.md L-1136 同款） */
    private var labelInput: EditText? = null

    /** 正在编辑的槽位下标（-1 = 无） */
    private var labelDialogIndex = -1

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    /**
     * 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 对一次表并排下一次，
     * [onStop] 撤掉 —— 页面在后台跨过切换点，回来时也能补上。
     */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /**
     * 跨重建保存对话框里已输入的内容（BUG.md L-1136）：本页无 `configChanges`，
     * 旋转 / 定时换色会重建，不保存就得白打一遍（符号本身在偏好里，不会丢）。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 按条目上限截断：更长的内容 [addItem] 本就拒收，而整份进实例状态会撑到系统侧事务上限
        // （BUG.md L-1169）
        outState.putString(STATE_ADD_TEXT, (addInput?.text?.toString() ?: "").take(FavoriteSymbols.MAX_CHARS))
        // 数字行单槽编辑同一套：槽位 + 已输入文本（上限同 NumberRowLabels.MAX_CHARS）
        if (labelDialogIndex >= 0) {
            outState.putInt(STATE_LABEL_INDEX, labelDialogIndex)
            outState.putString(
                STATE_LABEL_TEXT,
                (labelInput?.text?.toString() ?: "").take(NumberRowLabels.MAX_CHARS),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite_symbols)
        listContainer = findViewById(R.id.favorite_list)
        findViewById<Button>(R.id.btn_favorite_close).apply {
            // 关闭键：键面字形与可听名统一来自 PageChrome（原先各页自写 `X` / `关闭`，见 BUG.md L-477）
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.text_favorite_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_favorite_add).apply {
            text = TEXT_ADD
            setOnClickListener { showAddDialog() }
        }
        renderPages()
        // 重建后把对话框连同已输入的内容一起恢复（BUG.md L-1136）
        savedInstanceState?.getString(STATE_ADD_TEXT)?.takeIf { it.isNotEmpty() }?.let { showAddDialog(it) }
        val li = savedInstanceState?.getInt(STATE_LABEL_INDEX, -1) ?: -1
        if (li >= 0) {
            showLabelDialog(li, savedInstanceState?.getString(STATE_LABEL_TEXT) ?: "")
        }
    }

    private companion object {
        // 页面文案在代码里下发（AGENTS.md：`values/strings.xml` 默认不改动）—— 与其余页面一致。
        // 这两页（本页与符号排序页）此前是唯一把说明 / 按钮文案也留在 strings.xml 的
        // （2026-10-02 统一，见 B-152）。
        // ⚠ 标题**不在**这里：`favorite_title` 同时是 `AndroidManifest` 里本 activity 的
        //   `android:label`（任务切换器显示的名字），必须与 `app_name` / `settings_title` 一样
        //   留在 strings.xml，所以标题由布局直接引用那条 string（与设置页各入口按钮同做法）。
        const val TEXT_DESC = "增删「收藏」里的符号\n每页最多 26 个，满了自动开新页\n改完即时保存，返回键盘就生效。"
        const val TEXT_ADD = "＋ 添加符号"

        // ── 数字行按钮文本（2026-10-21 起并入本页）─────────────────────────
        //
        // ⚠ 这一组是**新增文案**，属作者域：值为建议稿，尚未经作者定稿。
        // 定稿只改这几个常量，不涉及其它位置（没有测试钉死字面量）。
        /** 分区标题 */
        const val TEXT_NUMBER_ROW_SECTION = "数字行文本"

        /** 分区说明 */
        const val TEXT_NUMBER_ROW_HINT = "点格子改按钮文本，留空回默认数字"

        /** 单槽编辑对话框标题 */
        const val TEXT_LABEL_TITLE = "按钮文本"

        /** 单槽编辑对话框输入框提示 */
        const val TEXT_LABEL_HINT = "留空 = 默认数字"

        /** 单槽编辑对话框中性键：把该槽恢复成默认数字 */
        const val TEXT_LABEL_RESET = "恢复默认"

        /** 超出 [NumberRowLabels.MAX_CHARS] 的提示 */
        const val TEXT_LABEL_TOO_LONG = "最多 4 个字符"

        /** 跨重建保存的对话框输入（BUG.md L-1136） */
        private const val STATE_ADD_TEXT = "favorite_add_text"

        /** 跨重建保存的数字行单槽编辑：槽位下标 */
        private const val STATE_LABEL_INDEX = "number_row_label_index"

        /** 跨重建保存的数字行单槽编辑：已输入文本 */
        private const val STATE_LABEL_TEXT = "number_row_label_text"

        /** 日志标签：收藏落盘失败要留痕（BUG-34） */
        private const val TAG = "FavoriteSymbols"
    }

    override fun onPause() {
        super.onPause()
        // 只有真正改过才通知（进来看看就退出的场景不必重建键盘视图）。
        // 标记不复位：重建可能被「有未上屏输入」守卫延后，下次 onPause 再通知一次（重建幂等）。
        if (dirty) JinnIme.onSymbolLayoutChanged()
    }

    override fun onDestroy() {
        // 添加对话框不登记就会随页面旋转泄漏（WindowLeaked）；此时点「确定」还会把符号
        // 写进已 detach 的旧列表，用户在当前页面上看不到任何变化
        addDialog?.dismiss()
        addDialog = null
        // 数字行单槽编辑对话框同一理由
        labelDialog?.dismiss()
        labelDialog = null
        super.onDestroy()
    }

    private fun currentPages(): List<List<String>> =
        FavoriteSymbols.parse(Prefs(this).favoriteSymbols)

    private fun save(pages: List<List<String>>) {
        // 超上限时 [Prefs.favoriteSymbols] 的 setter 会**静默丢弃**（保留旧值）：必须在这里自己
        // 判一次并明说，否则「刚添加的符号凭空消失」且零提示（BUG.md 第 15 批 M5）。
        if (FavoriteSymbols.overCapacity(pages)) {
            Toast.makeText(this, R.string.favorite_full, Toast.LENGTH_LONG).show()
            return
        }
        val prefs = Prefs(this)
        prefs.favoriteSymbols = FavoriteSymbols.serialize(pages)
        // 关键写同步落盘（BUG-34）：setter 走 `apply()`，用户加完符号立刻切走、进程被系统回收时，
        // 最后一次改动会安静地丢（存储不损坏，只是没写下去）。本页是收藏的唯一写入口，
        // 一次提交代价是一份几 KB 的 XML —— 与「保存并重启」那条路同口径（见 Prefs.flush 的说明）。
        if (!prefs.flush()) Diagnostics.w(TAG, "收藏落盘失败（改动可能回退）")
        dirty = true
    }

    private fun renderPages() {
        val pages = currentPages()
        listContainer.removeAllViews()
        // 数字行文本分区必须排在**空收藏早退之前**：收藏删光时这一页仍有内容，
        // 排在空态分支后面就会出现「收藏空了 ⇒ 数字行分区也跟着消失」。
        renderNumberRowSection()
        if (pages.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.favorite_empty)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, PageStyle.dp(context, 24), 0, 0)
                gravity = Gravity.CENTER
            })
            return
        }
        pages.forEachIndexed { pi, page ->
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.favorite_page_fmt, pi + 1, page.size)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                // dp 换算统一走 PageStyle（原先这里的 density 局部变量只服务这一行）
                setPadding(
                    0,
                    if (pi == 0) 0 else PageStyle.dp(context, 10),
                    0,
                    PageStyle.dp(context, 4),
                )
            })
            PageStyle.addCard(listContainer, gridForPage(pi, page))
        }
    }

    /**
     * 数字行按钮文本分区（2026-10-21 起并入本页）。
     *
     * 渲染判据**只有一条**：`Prefs.numberRowEnabled`。数字行开关关着时整段不 `addView`
     * （不是 GONE）—— 关着的人在本页不该看到一个与自己无关的分区。
     *
     * **一行 10 个、等宽均分**（用户 2026-10-21 指定）：与键盘那排的视觉完全一致 ——
     * 第 n 个格子在页面里的左右位置就是它在键盘上的左右位置，改哪个不用在心里换算。
     * 早先的 5 列两行为的是「44dp 格子加默认外边距不在窄屏溢出」，那个顾虑用
     * `LinearLayout` + weight 均分即可解：格宽由屏宽除出来（1440dp 屏约 128dp），
     * 高度仍取与收藏格同款的 44dp，触摸热区不缩水。
     *
     * 为什么放在收藏分页**之前**：固定 10 个槽位与「收藏按页增长」是两套结构，
     * 放在后面会让「收藏翻页」把数字行分区挤到看不见的地方。
     */
    private fun renderNumberRowSection() {
        val prefs = Prefs(this)
        if (!prefs.numberRowEnabled) return
        val labels = NumberRowLabels.effective(prefs.numberRowLabels)

        listContainer.addView(TextView(this).apply {
            text = TEXT_NUMBER_ROW_SECTION
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, PageStyle.dp(context, 4))
        })
        listContainer.addView(TextView(this).apply {
            text = TEXT_NUMBER_ROW_HINT
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, PageStyle.dp(context, 4))
        })
        val cell = PageStyle.dp(this, 44)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        labels.forEachIndexed { i, text ->
            row.addView(Button(this).apply {
                this.text = text
                // 13sp：格宽由屏宽除出来（1440dp 屏约 128dp），比收藏格窄一半，
                // 15sp 在 4 字文本下会挤到换行
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                background = getDrawable(R.drawable.btn_aurora_secondary)
                setTextColor(getColor(R.color.text_primary))
                stateListAnimator = null
                elevation = 0f
                contentDescription = text
                layoutParams = LinearLayout.LayoutParams(0, cell, 1f)
                setOnClickListener { showLabelDialog(i) }
            })
        }
        PageStyle.addCard(listContainer, row)
    }

    /**
     * 单槽编辑对话框：预填该槽**当前生效值**（空槽显示的也是默认数字，用户才知道现在是什么）。
     *
     * 中性键 = 恢复默认：把该槽写成 `""`（= 回落默认数字）。这与「确定时输入为空」同一条路径，
     * 给一个显式入口只是省得用户手动删干净再按确定。
     *
     * 本页无 `configChanges`，代码创造的对话框不会自动恢复（BUG.md L-1136）：
     * 字段登记 + `onSaveInstanceState` 与既有的「添加符号」对话框同一套处理。
     */
    private fun showLabelDialog(index: Int, initial: String? = null) {
        val prefs = Prefs(this)
        val effective = NumberRowLabels.effective(prefs.numberRowLabels)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = TEXT_LABEL_HINT
            setSingleLine(true)
            setText(initial ?: effective.getOrElse(index) { "" })
        }
        val pad = PageStyle.dp(this, 16)
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_LABEL_TITLE)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> saveLabel(index, input.text.toString()) }
            .setNeutralButton(TEXT_LABEL_RESET) { _, _ -> saveLabel(index, "") }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        labelDialog = dialog
        labelInput = input
        labelDialogIndex = index
        dialog.setOnDismissListener {
            labelInput = null
            labelDialogIndex = -1
        }
        dialog.show()
    }

    /**
     * 写一个槽位并即时生效。
     *
     * 三件事的顺序不能换：
     *  ① **清洗 + 校验在写盘之前**（与「添加符号」同口径：空 = 回落默认；超限 = 拒绝并提示，
     *     不写脏值）。限长必须走码点口径（[FavoriteSymbols.withinLimit]），否则一串 emoji
     *     会被 UTF-16 码元数误判超限。
     *  ② `flush()` 同步落盘（BUG-34）：改完立刻切走、进程被系统回收时，最后一次改动会安静地丢。
     *  ③ `JinnIme.onNumberRowLabelsChanged()` 轻量推送 —— 只改 10 个键的 label，
     *     不重建视图，因此**不需要**「有未上屏输入就延后」那一层。
     */
    private fun saveLabel(index: Int, raw: String) {
        val item = FavoriteSymbols.clean(raw)
        if (item.isNotEmpty() && !FavoriteSymbols.withinLimit(item, NumberRowLabels.MAX_CHARS)) {
            Toast.makeText(this, TEXT_LABEL_TOO_LONG, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = Prefs(this)
        val labels = NumberRowLabels.parse(prefs.numberRowLabels).toMutableList()
        labels[index] = item
        prefs.numberRowLabels = NumberRowLabels.serialize(labels)
        if (!prefs.flush()) Diagnostics.w(TAG, "数字行文本落盘失败（改动可能回退）")
        JinnIme.onNumberRowLabelsChanged()
        renderPages()
    }

    /** 一页的符号网格：每格 = 符号按钮 + 右上角 ✕ 移除角标 */
    private fun gridForPage(pageIndex: Int, page: List<String>): GridLayout {
        val cell = PageStyle.dp(this, 44)
        val grid = GridLayout(this).apply {
            columnCount = 6
            useDefaultMargins = true
        }
        page.forEachIndexed { i, sym ->
            val frame = FrameLayout(this)
            frame.addView(Button(this).apply {
                text = sym
                textSize = 15f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                background = getDrawable(R.drawable.btn_aurora_secondary)
                setTextColor(getColor(R.color.text_primary))
                // ⚠ 必须去掉 Material 按钮自带的按下抬升（`stateListAnimator` + elevation）：
                //    有 elevation 的视图在 Z 轴上**高于**无 elevation 的兄弟，会盖住后添加的
                //    角标 —— 屏幕上的表现就是「角标那圈只有按钮底色、看不到 ×」
                //    （2026-10-02 真机定位：角标视图存在、bounds 正确，采样像素却全是按钮底色）。
                //    这颗按钮是「格子里的面」，本来也不需要悬浮感。
                stateListAnimator = null
                elevation = 0f
                layoutParams = FrameLayout.LayoutParams(cell, cell)
            })
            // ✕ 移除角标（2026-10-02 修复）：
            // ① 显示**符号**而不是「移除」两个字 —— 22dp 角标里两个汉字被 `Button` 的默认内边距挤到
            //    几乎不可见，用户看到的就是「按钮和背景色相同、文字不可见」；无障碍语义仍由
            //    contentDescription 保留（读作「移除」）。
            // ② **红色粗体**：kb_key_hint_red（明 #C62828 / 暗 #FF5A5F）+ BOLD。
            // ③ 不再用负 margin：那会把角标推出格子边界、右上角被父容器裁掉，与「在字符容器
            //    **内部**的右上角」的预期相反；现在完整落在 44dp 格子内。
            // ④ 用 TextView 而不是 Button：这里只要一个符号，`Button` 自带的最小高 / 内边距 /
            //    大小写转换 / 状态动画都得逐条覆写，反而更脆（原先正是漏了内边距才被挤没的）。
            frame.addView(TextView(this).apply {
                text = getString(R.string.favorite_delete_mark)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                // ⚠ **不要背景**（2026-10-02）：原先那颗圆角蓝底（btn_aurora_secondary）
                // 会在符号按钮上再叠一个小圆片，看着像「角标自己也是一颗按钮」。这里是**纯文本** ×，
                // 直接压在符号格上；点击热区仍是这 22dp 方块（透明但不影响命中）。
                // 注意 elevation 要留着：它只影响 Z 序（透明背景不会画阴影），是「不被按钮盖住」的保证。
                setTextColor(getColor(R.color.kb_key_hint_red))
                // 与上一颗按钮的「取消抬升」成对：角标给一点点 elevation，确保它在最上层
                // （双保险 —— 只依赖添加顺序的话，将来谁给按钮加回 elevation 就会再被盖住）
                elevation = PageStyle.dp(context, 2).toFloat()
                contentDescription = getString(R.string.favorite_delete)
                setOnClickListener {
                    val pages = currentPages()
                    // 扁平下标 = 「前面各页实际长度之和」+ 页内偏移：不依赖「非末页恒满 26 键」的
                    // 隐式不变量，数据一旦被外部破坏（非末页不满 26）也能删到正确的符号
                    val flatIndex = pages.take(pageIndex).sumOf { it.size } + i
                    save(FavoriteSymbols.removeAt(pages, flatIndex))
                    renderPages()
                }
                layoutParams = FrameLayout.LayoutParams(
                    PageStyle.dp(context, 22), PageStyle.dp(context, 22),
                    Gravity.TOP or Gravity.END,
                )
            })
            grid.addView(frame)
        }
        return grid
    }

    /**
     * 添加对话框：仅一个输入框（自由键入/粘贴），确定后校验空/超长/重复。
     *
     * [initial] 供重建恢复：本页无 `configChanges`，旋转或定时换色会 `recreate()`，
     * 对话框连同已输入的内容一起没了（BUG.md L-1136）。
     */
    private fun showAddDialog(initial: String = "") {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.favorite_dialog_hint)
            setSingleLine(true)
            setText(initial)
        }
        val pad = PageStyle.dp(this, 16)
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.favorite_dialog_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> addItem(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        addDialog = dialog
        addInput = input
        dialog.setOnDismissListener { addInput = null }
        dialog.show()
    }

    private fun addItem(raw: String) {
        // 粘贴可能带换行 / 制表符 / 不换行空格 / 零宽字符（键面看不见却占格子）：统一走清洗再校验
        // （口径收在 FavoriteSymbols.clean，与 append / parse 同一份，BUG-36）
        val item = FavoriteSymbols.clean(raw)
        val pages = currentPages()
        when {
            // ⚠ 限长必须走 withinLimit（码点口径），不能写 item.length：那是 UTF-16 码元数，
            // 一个 emoji 记 2 ⇒ 5 个 emoji（10 码元）被这里判超限拒收，而 append / parse 按码点
            // 只算 5 ⇒ 页面与模型两套口径，用户粘一串 emoji 进去一半报「无效」。
            item.isEmpty() || !FavoriteSymbols.withinLimit(item) ->
                Toast.makeText(this, R.string.favorite_invalid, Toast.LENGTH_SHORT).show()
            pages.any { it.contains(item) } ->
                Toast.makeText(this, R.string.favorite_exists, Toast.LENGTH_SHORT).show()
            else -> {
                val (next, ok) = FavoriteSymbols.append(pages, item)
                if (ok) {
                    save(next)
                    renderPages()
                } else {
                    Toast.makeText(this, R.string.favorite_invalid, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
