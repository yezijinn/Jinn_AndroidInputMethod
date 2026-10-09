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
 * - 改动即时落盘（[Prefs.favoriteSymbols]）；[onPause] 时仅在真正改过的情况下通知
 *   IME 重建键盘视图（进来看看就退出不触发整块重建）。
 */
class FavoriteSymbolsActivity : Activity() {

    private lateinit var listContainer: LinearLayout

    /** 本次进入是否改过收藏内容（决定离开时要不要通知 IME 重建键盘视图） */
    private var dirty = false

    /** 「添加符号」对话框：代码创建的，旋转重建时不会自动恢复，必须自己登记并 dismiss */
    private var addDialog: AlertDialog? = null

    /** 添加对话框的输入框（重建时把已输入 / 已粘贴的内容带回去，BUG.md L-1136） */
    private var addInput: EditText? = null

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

        /** 跨重建保存的对话框输入（BUG.md L-1136） */
        private const val STATE_ADD_TEXT = "favorite_add_text"
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
        Prefs(this).favoriteSymbols = FavoriteSymbols.serialize(pages)
        dirty = true
    }

    private fun renderPages() {
        val pages = currentPages()
        listContainer.removeAllViews()
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
            item.isEmpty() || item.length > FavoriteSymbols.MAX_CHARS ->
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
