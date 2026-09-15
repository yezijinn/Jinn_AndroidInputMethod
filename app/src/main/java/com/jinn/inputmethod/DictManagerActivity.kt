package com.jinn.inputmethod

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * 分类词库页：列出可选词库，按需下载 / 删除。
 *
 * 下载落地到 `filesDir/dicts/<fileName>`（[PinyinEngine.OPT_DICT_DIR]），
 * 引擎启动时自动扫描加载。加载是延迟的——基础词库先就绪，可选包在后台补齐，
 * 所以页面标注的是「**开机后首次输入**候选就绪需等待约 N 秒」，而不是「启动耗时」。
 *
 * 下载完成后自动重启输入法进程：词库只在 IME 启动时加载，重启才会合并生效。
 */
class DictManagerActivity : Activity() {

    private lateinit var listHost: LinearLayout
    private lateinit var textStatus: TextView

    /** 正在下载的文件名，用于禁用按钮与显示进度（null 表示空闲） */
    private var downloading: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.dict_manager_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#0F1115"))
        }

        textStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#ECEEF2"))
            textSize = 14f
            text = getString(R.string.dict_manager_hint)
        }
        root.addView(textStatus, lp(marginBottom = 10))

        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listHost) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        Diagnostics.i(TAG, "DictManagerActivity: 打开分类词库页")
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ── 列表 ────────────────────────────────────────────────

    private fun refreshList() {
        listHost.removeAllViews()
        for (dict in OptionalDicts.ALL) {
            listHost.addView(buildRow(dict), lp(marginBottom = 10))
        }
    }

    private fun buildRow(dict: OptionalDict): View {
        val file = dictFile(dict.fileName)
        val installed = file.isFile

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1F26"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        card.addView(TextView(this).apply {
            text = dict.name
            setTextColor(Color.parseColor("#ECEEF2"))
            textSize = 16f
        })

        card.addView(TextView(this).apply {
            text = dict.desc
            setTextColor(Color.parseColor("#9AA3B2"))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })

        // 体积 + 代价：两者都要给用户看，光看体积会低估成本
        val cost = getString(R.string.dict_startup_cost, dict.startupSec)
        card.addView(TextView(this).apply {
            text = "体积 %.1f MB · %s".format(Locale.US, dict.sizeMb, cost)
            setTextColor(Color.parseColor("#F0A020"))
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })

        card.addView(TextView(this).apply {
            text = if (installed) {
                getString(R.string.dict_status_installed, formatSize(file.length()))
            } else {
                getString(R.string.dict_status_absent)
            }
            setTextColor(if (installed) Color.parseColor("#4CC38A") else Color.parseColor("#9AA3B2"))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(actionButton(
            text = if (installed) getString(R.string.dict_action_reinstall)
            else getString(R.string.dict_action_download),
            enabled = downloading == null,
        ) { download(dict) }, buttonLp(right = 8))

        if (installed) {
            row.addView(actionButton(
                text = getString(R.string.dict_action_remove),
                enabled = downloading == null,
            ) { remove(dict) }, buttonLp())
        }
        card.addView(row, lp(top = 8))
        return card
    }

    /** 按钮用 WRAP_CONTENT：否则第一个按钮占满整行，把后面的按钮挤出屏幕 */
    private fun buttonLp(right: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(right) }

    private fun actionButton(text: String, enabled: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isEnabled = enabled
            setOnClickListener { onClick() }
        }

    // ── 下载 / 删除 ─────────────────────────────────────────

    private fun download(dict: OptionalDict) {
        if (downloading != null) return
        downloading = dict.fileName
        textStatus.text = getString(R.string.dict_downloading, dict.name)
        refreshList()

        Thread {
            var lastError = "未知错误"
            var ok = false
            for (url in dict.urls) {
                runCatching {
                    val len = fetchToFile(url, dict.fileName)
                    Diagnostics.i(TAG, "分类词库下载成功: ${dict.fileName} ($len B) url=$url")
                    ok = true
                }.onFailure {
                    lastError = it.message ?: it.toString()
                    Diagnostics.w(TAG, "分类词库下载失败 url=$url : $lastError")
                }
                if (ok) break
            }
            runOnUiThread {
                downloading = null
                textStatus.text = if (ok) {
                    getString(R.string.dict_download_done, dict.name)
                } else {
                    getString(R.string.dict_download_failed, lastError)
                }
                refreshList()
                if (ok) promptRestart()
            }
        }.start()
    }

    /**
     * 下载到临时文件再改名 —— 避免中途失败留下半个文件被引擎当作有效词库加载。
     * 返回写入字节数。
     */
    private fun fetchToFile(url: String, fileName: String): Long {
        val dir = File(filesDir, PinyinEngine.OPT_DICT_DIR).apply { mkdirs() }
        val tmp = File(dir, "$fileName.tmp")
        val dst = File(dir, fileName)

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("响应为空")
            body.byteStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) error("写入失败")
        return dst.length()
    }

    private fun remove(dict: OptionalDict) {
        val f = dictFile(dict.fileName)
        val ok = runCatching { f.delete() }.getOrDefault(false)
        Diagnostics.i(TAG, "分类词库删除: ${dict.fileName} ok=$ok")
        textStatus.text = if (ok) {
            getString(R.string.dict_removed, dict.name)
        } else {
            getString(R.string.dict_remove_failed)
        }
        refreshList()
        if (ok) promptRestart()
    }

    /**
     * 词库只在 IME 启动时加载，增删后必须重启输入法进程才会生效。
     *
     * 延迟片刻再杀进程：等文件写入落盘。系统会自动重建 IME 服务并加载新词库，
     * 本 Activity 随进程一并结束（与设置页 saveAndRestart 同款做法）。
     */
    private fun promptRestart() {
        textStatus.text = getString(R.string.dict_need_restart)
        Diagnostics.i(TAG, "分类词库变更：1.5s 后重启输入法进程以加载")
        listHost.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 1500L)
    }

    // ── 工具 ────────────────────────────────────────────────

    private fun dictFile(fileName: String) = File(File(filesDir, PinyinEngine.OPT_DICT_DIR), fileName)

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0, right: Int = 0, marginBottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            this.topMargin = dp(top)
            this.rightMargin = dp(right)
            this.bottomMargin = dp(marginBottom)
        }

    private companion object {
        const val TAG = "DictManager"
    }
}
