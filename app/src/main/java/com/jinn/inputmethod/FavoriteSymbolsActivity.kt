package com.jinn.inputmethod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
 * 交互（用户确认的方案）：
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite_symbols)
        listContainer = findViewById(R.id.favorite_list)
        findViewById<Button>(R.id.btn_favorite_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_favorite_add).setOnClickListener { showAddDialog() }
        renderPages()
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
                setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 0)
                gravity = Gravity.CENTER
            })
            return
        }
        val density = resources.displayMetrics.density
        pages.forEachIndexed { pi, page ->
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.favorite_page_fmt, pi + 1, page.size)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, if (pi == 0) 0 else (10 * density).toInt(), 0, (4 * density).toInt())
            })
            listContainer.addView(gridForPage(pi, page))
        }
    }

    /** 一页的符号网格：每格 = 符号按钮 + 右上角 ✕ 移除角标 */
    private fun gridForPage(pageIndex: Int, page: List<String>): GridLayout {
        val density = resources.displayMetrics.density
        val cell = (44 * density).toInt()
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
                setPadding(0, 0, 0, 0)
                background = getDrawable(R.drawable.btn_aurora_secondary)
                setTextColor(getColor(R.color.text_primary))
                layoutParams = FrameLayout.LayoutParams(cell, cell)
            })
            frame.addView(Button(this).apply {
                text = getString(R.string.favorite_delete)
                textSize = 9f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                background = getDrawable(R.drawable.btn_aurora_secondary)
                setTextColor(getColor(R.color.kb_key_hint_red))
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
                    (22 * density).toInt(), (22 * density).toInt(),
                    Gravity.TOP or Gravity.END,
                ).apply {
                    marginEnd = (-4 * density).toInt()
                    topMargin = (-4 * density).toInt()
                }
            })
            grid.addView(frame)
        }
        return grid
    }

    /** 添加对话框：仅一个输入框（自由键入/粘贴），确定后校验空/超长/重复 */
    private fun showAddDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.favorite_dialog_hint)
            setSingleLine(true)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
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
        dialog.show()
    }

    private fun addItem(raw: String) {
        // 粘贴可能带换行/制表符（键面无法显示）：剥掉再校验
        val item = raw.trim().replace(Regex("[\\n\\t\\r]"), "")
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
