package com.jinn.voiceinput

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 第三方 APP 剪贴板历史访问权限管理（方案第九 ~ 十二节）。
 *
 * 功能：
 *  - 列出已授权的 APP（packageName + 应用名 + 是否允许读）；
 *  - 勾选开关权限（默认所有 APP 禁止）；
 *  - 添加/移除 APP；
 *  - 说明每次最多读取 3 条（IPC 层强制，UI 不负责限制）。
 *
 * 页面为隐私敏感页，启用 FLAG_SECURE 禁止截图。
 */
class ClipboardPermissionActivity : Activity() {

    private val permStore by lazy { ClipboardPermissionStore.get(this) }

    private lateinit var listView: ListView
    private var currentPerms: List<ClipboardPermissionStore.Permission> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = currentPerms.size
        override fun getItem(pos: Int) = currentPerms[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val perm = currentPerms[pos]
            val holder = convertView?.tag as? Holder
            val root = convertView ?: run {
                val v = LinearLayout(this@ClipboardPermissionActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setBackgroundColor(Color.parseColor("#141C33"))
                }
                val info = LinearLayout(this@ClipboardPermissionActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val name = TextView(this@ClipboardPermissionActivity).apply {
                    textSize = 14f
                    setTextColor(Color.parseColor("#ECEEF2"))
                }
                val pkg = TextView(this@ClipboardPermissionActivity).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                }
                val check = CheckBox(this@ClipboardPermissionActivity).apply {
                    setTextColor(Color.parseColor("#ECEEF2"))
                }
                info.addView(name, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                info.addView(pkg, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.addView(info, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                v.addView(check, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                v.tag = Holder(name, pkg, check)
                v
            }
            val h = holder ?: return root
            h.name.text = perm.appName.ifBlank { perm.packageName.substringAfterLast('.') }
            h.pkg.text = perm.packageName
            h.check.isChecked = perm.readAllowed
            h.check.setOnCheckedChangeListener { _, checked ->
                permStore.setPermission(
                    ClipboardPermissionStore.Permission(
                        packageName = perm.packageName,
                        appName = perm.appName,
                        readAllowed = checked,
                        listenerAllowed = perm.listenerAllowed,
                        writeAllowed = perm.writeAllowed,
                        maxHistoryItems = perm.maxHistoryItems,
                        temporaryExpireAt = perm.temporaryExpireAt,
                    )
                )
                Diagnostics.i(TAG, "权限变更: ${perm.packageName} read=$checked")
            }
            return root
        }
    }

    private class Holder(val name: TextView, val pkg: TextView, val check: CheckBox)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Diagnostics.i(TAG, "onCreate: 剪贴板权限管理页启动")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1020"))
            setPadding(dp(12), dp(16), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = getString(R.string.clipboard_perm_manage)
            textSize = 18f
            setTextColor(Color.parseColor("#ECEEF2"))
            setPadding(0, 0, 0, dp(4))
        }

        val descView = TextView(this).apply {
            text = getString(R.string.clipboard_perm_desc)
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 0, 0, dp(10))
        }

        val hintView = TextView(this).apply {
            text = getString(R.string.clipboard_perm_add_hint)
            textSize = 12f
            setTextColor(Color.parseColor("#F5A623"))
            setPadding(0, 0, 0, dp(8))
        }

        listView = ListView(this).apply {
            divider = null
            adapter = this@ClipboardPermissionActivity.adapter
        }

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val editPkg = android.widget.EditText(this).apply {
            hint = getString(R.string.clipboard_perm_input_pkg)
            setTextColor(Color.parseColor("#ECEEF2"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#1C1F26"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val btnAdd = Button(this).apply {
            text = getString(R.string.clipboard_perm_add)
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#4C8DFF"))
        }
        btnAdd.setOnClickListener {
            val pkg = editPkg.text.toString().trim()
            if (pkg.isEmpty()) return@setOnClickListener
            // 允许该 APP 读取（每次最多 3 条）
            permStore.setPermission(
                ClipboardPermissionStore.Permission(
                    packageName = pkg,
                    appName = "",
                    readAllowed = true,
                    listenerAllowed = false,
                    writeAllowed = true,
                    maxHistoryItems = ClipboardPermissionStore.MAX_ITEMS_THIRD_PARTY,
                    temporaryExpireAt = 0L,
                )
            )
            editPkg.setText("")
            refresh()
            Toast.makeText(this, R.string.clipboard_perm_added, Toast.LENGTH_SHORT).show()
            Diagnostics.i(TAG, "授权添加: $pkg")
        }

        val addLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        val btnLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addRow.addView(editPkg, addLp)
        addRow.addView(btnAdd, btnLp)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(title, lp)
        root.addView(descView, lp)
        root.addView(hintView, lp)
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(addRow, lp)
        setContentView(root)

        refresh()
    }

    private fun refresh() {
        currentPerms = permStore.listAll()
        adapter.notifyDataSetChanged()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ClipboardPermission"
    }
}
