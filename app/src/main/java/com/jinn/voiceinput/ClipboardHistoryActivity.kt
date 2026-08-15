package com.jinn.voiceinput

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 剪贴板历史页：列表 + 搜索 + 删除（方案第十八 / 二十 / 二十三节）。
 *
 * 隐私设计：
 *  - 页面启用 [WindowManager.LayoutParams.FLAG_SECURE]，禁止截图与最近任务预览；
 *  - 内容按需解密：仅显示当前列表项时解密（[ClipboardDb.recent/search] 已按需解密）；
 *  - 删除单条/全部：真正删除数据库记录，不留缓存；
 *  - 不输出任何剪贴板正文日志。
 */
class ClipboardHistoryActivity : Activity() {

    private val db by lazy { ClipboardDb.get(this) }
    private val prefs by lazy { ClipboardPrefs.of(this) }

    private lateinit var listView: ListView
    private lateinit var editSearch: EditText
    private lateinit var textEmpty: TextView
    private var currentItems: List<ClipboardDb.Item> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = currentItems.size
        override fun getItem(pos: Int) = currentItems[pos]
        override fun getItemId(pos: Int) = currentItems[pos].id
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = currentItems[pos]
            val holder = convertView?.tag as? Holder
            val root = convertView ?: run {
                val v = LinearLayout(this@ClipboardHistoryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = android.graphics.drawable.ColorDrawable(Color.parseColor("#141C33"))
                }
                val meta = TextView(this@ClipboardHistoryActivity).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                }
                val content = TextView(this@ClipboardHistoryActivity).apply {
                    textSize = 16f
                    setTextColor(Color.parseColor("#ECEEF2"))
                    setPadding(0, dp(4), 0, 0)
                }
                v.addView(meta, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.addView(content, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.tag = Holder(meta, content)
                v
            }
            val h = holder ?: return root
            h.meta.text = buildString {
                append(SDF.format(Date(item.createdAt)))
                if (item.sourcePackage.isNotBlank()) append(" · ").append(item.sourceAppName.ifBlank { item.sourcePackage.substringAfterLast('.') })
                if (item.isSensitive) append(" · 敏感")
            }
            h.content.text = item.content
            return root
        }
    }

    private class Holder(val meta: TextView, val content: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Diagnostics.i(TAG, "onCreate: 剪贴板历史页启动")

        // 垂直布局：搜索框 + 列表 + 底部操作栏
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1020"))
            setPadding(dp(12), dp(16), dp(12), dp(12))
        }

        editSearch = EditText(this).apply {
            hint = getString(R.string.clipboard_search_hint)
            setTextColor(Color.parseColor("#ECEEF2"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#1C1F26"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        editSearch.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }

        listView = ListView(this).apply {
            divider = null
            setBackgroundColor(Color.parseColor("#0B1020"))
            adapter = this@ClipboardHistoryActivity.adapter
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            val item = currentItems[pos]
            // 点击复制回系统剪贴板
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("clipboard", item.content))
            Toast.makeText(this, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val item = currentItems[pos]
            db.delete(item.id)
            refresh()
            Toast.makeText(this, R.string.clipboard_deleted, Toast.LENGTH_SHORT).show()
            true
        }

        textEmpty = TextView(this).apply {
            text = getString(R.string.clipboard_empty)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            visibility = View.GONE
        }

        // 底部操作栏：搜索 / 删除全部
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnSearch = TextView(this).apply {
            text = getString(R.string.clipboard_search)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 14f
            setBackgroundColor(Color.parseColor("#4C8DFF"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        btnSearch.setOnClickListener { performSearch() }
        val btnDeleteAll = TextView(this).apply {
            text = getString(R.string.clipboard_delete_all)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#E5484D"))
            textSize = 14f
            setBackgroundColor(Color.parseColor("#1C1F26"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        btnDeleteAll.setOnClickListener {
            db.deleteAll()
            refresh()
            Toast.makeText(this, R.string.clipboard_deleted_all, Toast.LENGTH_SHORT).show()
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val searchLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        val delLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        actionRow.addView(btnSearch, searchLp)
        actionRow.addView(btnDeleteAll, delLp)

        root.addView(editSearch, lp)
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(textEmpty, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(actionRow, lp)
        setContentView(root)

        refresh()
    }

    private fun performSearch() {
        val q = editSearch.text.toString().trim()
        refresh()
        if (q.isNotEmpty()) {
            currentItems = db.search(q.split(Regex("\\s+")))
            adapter.notifyDataSetChanged()
            updateEmpty()
        }
    }

    private fun refresh() {
        currentItems = db.recent(prefs.maxItems)
        adapter.notifyDataSetChanged()
        updateEmpty()
    }

    private fun updateEmpty() {
        val empty = currentItems.isEmpty()
        textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ClipboardHistory"
        val SDF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
