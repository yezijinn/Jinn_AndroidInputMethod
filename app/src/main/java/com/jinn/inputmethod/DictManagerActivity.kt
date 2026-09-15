package com.jinn.inputmethod

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * 分类词库页：列出可选词库，按需下载 / 删除。
 *
 * 视觉对齐本项目既有页面（与剪贴板历史页同一套配色）：
 *   背景 #0B1020 ｜ 顶栏 #141C33 ｜ 卡片 #1C1F26
 *   主文字 #ECEEF2 ｜ 次文字 #9CA3AF ｜ 强调 #4C8DFF ｜ 危险 #E5484D
 *
 * 下载落地到 `filesDir/dicts/<fileName>`（[PinyinEngine.OPT_DICT_DIR]），
 * 引擎启动时自动扫描加载。加载是延迟的——基础词库先就绪，可选包在后台补齐，
 * 所以页面标注的是「**开机后首次输入**候选就绪需等待约 N 秒」，而不是「启动耗时」。
 *
 * 说明文案按句拆成多行渲染（[OptionalDict.descLines]），**一行就是一句话**：
 * 交给系统自动折行会出现断句不良的折行，读起来别扭，所以主动分行。
 */
class DictManagerActivity : Activity() {

    private lateinit var listHost: LinearLayout
    private lateinit var textStatus: TextView

    /** 正在下载的文件名，用于禁用按钮与显示进度（null 表示空闲） */
    private var downloading: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
        }

        root.addView(buildTopBar())

        // 提示：两句各占一行，避免被系统折行
        root.addView(hint(getString(R.string.dict_manager_hint_line1)), matchWrap(top = 10))
        root.addView(hint(getString(R.string.dict_manager_hint_line2)), matchWrap())

        // 动态状态行：初始隐藏，下载/删除时才出现。单行 + 省略号，
        // 内容是「正在下载 X…」这类变长文案，不适合按句拆分。
        textStatus = TextView(this).apply {
            setTextColor(COLOR_ACCENT)
            textSize = 12f
            setPadding(dp(16), dp(6), dp(16), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(textStatus, matchWrap())

        listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        root.addView(
            ScrollView(this).apply { addView(listHost) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)
        Diagnostics.i(TAG, "DictManagerActivity: 打开分类词库页")
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    /** 顶栏下方的静态提示行（每句一个 TextView，不折行） */
    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(COLOR_TEXT_SECONDARY)
        textSize = 12f
        setPadding(dp(16), 0, dp(16), 0)
    }

    /** 更新动态状态行（自动显示出来） */
    private fun setStatus(text: String) {
        textStatus.text = text
        textStatus.visibility = View.VISIBLE
    }

    /** 顶栏：左标题、右关闭（沿用本项目深蓝顶栏 #141C33） */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COLOR_TOPBAR)
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }

        bar.addView(TextView(this).apply {
            text = getString(R.string.dict_manager_title)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 右上角关闭：结束本页返回设置页
        bar.addView(TextView(this).apply {
            text = "✕"
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 20f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(12), dp(2), dp(12), dp(2))
            setOnClickListener {
                Diagnostics.i(TAG, "DictManagerActivity: 用户关闭页面")
                finish()
            }
        }, wrapWrap())

        return bar
    }

    // ── 列表 ────────────────────────────────────────────────

    private fun refreshList() {
        listHost.removeAllViews()
        for (dict in OptionalDicts.ALL) {
            listHost.addView(buildCard(dict), matchWrap(bottom = 10))
        }
    }

    private fun buildCard(dict: OptionalDict): View {
        val file = dictFile(dict.fileName)
        val installed = file.isFile

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, 10)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // 词库名
        card.addView(line(dict.name, COLOR_TEXT_PRIMARY, 16f, bold = true))

        // 说明：每句独立一行，不做自动折行
        for (sentence in dict.descLines) {
            card.addView(line(sentence, COLOR_TEXT_SECONDARY, 13f, top = 4))
        }

        // 代价一行一句，避免长句被折
        card.addView(line("体积 %.1f MB。".format(Locale.US, dict.sizeMb), COLOR_WARN, 13f, top = 8))
        card.addView(line(getString(R.string.dict_startup_cost_line1), COLOR_WARN, 13f, top = 2))
        card.addView(line(getString(R.string.dict_startup_cost_line2, dict.startupSec), COLOR_WARN, 13f, top = 2))
        card.addView(line(getString(R.string.dict_startup_cost_line3), COLOR_WARN, 13f, top = 2))

        // 安装状态
        card.addView(
            line(
                text = if (installed) {
                    getString(R.string.dict_status_installed, formatSize(file.length()))
                } else {
                    getString(R.string.dict_status_absent)
                },
                color = if (installed) COLOR_OK else COLOR_TEXT_SECONDARY,
                size = 13f,
                top = 10,
            ),
        )

        // 操作按钮
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(
            actionButton(
                text = if (installed) getString(R.string.dict_action_reinstall)
                else getString(R.string.dict_action_download),
                color = COLOR_ACCENT,
                enabled = downloading == null,
            ) { download(dict) },
            buttonLp(right = 8),
        )
        if (installed) {
            row.addView(
                actionButton(
                    text = getString(R.string.dict_action_remove),
                    color = COLOR_DANGER,
                    enabled = downloading == null,
                ) { remove(dict) },
                buttonLp(),
            )
        }
        card.addView(row, matchWrap(top = 12))
        return card
    }

    /**
     * 单行文字。**每句话单独一个 TextView** —— 从根上避免长句被自动折行，
     * 保证页面上「一行就是一句话」。
     */
    private fun line(
        text: String,
        color: Int,
        size: Float,
        top: Int = 0,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(top), 0, 0)
    }

    /** 操作按钮：与剪贴板页 actionButton 同款（实心圆角色块 + 白字） */
    private fun actionButton(
        text: String,
        color: Int,
        enabled: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#FFFFFF"))
        textSize = 14f
        background = rounded(if (enabled) color else Color.parseColor("#3A4150"), 8)
        setPadding(dp(18), dp(9), dp(18), dp(9))
        isClickable = true
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    // ── 下载 / 删除 ─────────────────────────────────────────

    private fun download(dict: OptionalDict) {
        if (downloading != null) return
        downloading = dict.fileName
        setStatus(getString(R.string.dict_downloading, dict.name))
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
                // 用户可能在下完前点了 ✕ 关闭页面：此时不能动 UI（View 已随页面销毁），
                // 但下载确实完成了——他点下载就是想要词库生效，所以仍要重启 IME。
                if (isFinishing || isDestroyed) {
                    Diagnostics.i(TAG, "下载已完成但页面已关闭（ok=$ok），仍重启输入法以加载")
                    if (ok) restartImeForDict()
                    return@runOnUiThread
                }
                setStatus(
                    if (ok) getString(R.string.dict_download_done, dict.name)
                    else getString(R.string.dict_download_failed, lastError),
                )
                refreshList()
                if (ok) restartImeForDict()
            }
        }.start()
    }

    /**
     * 下载到临时文件再改名 —— 避免中途失败留下半个文件被引擎当作有效词库加载
     * （引擎只认 `.xz` 结尾，`X.xz.tmp` 不会被扫到，但失败时仍会残留占空间，所以显式清理）。
     * 返回写入字节数。
     */
    private fun fetchToFile(url: String, fileName: String): Long {
        val dir = File(filesDir, PinyinEngine.OPT_DICT_DIR).apply { mkdirs() }
        val tmp = File(dir, "$fileName.tmp")
        val dst = File(dir, fileName)

        try {
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
        } catch (t: Throwable) {
            // 半截文件清掉：否则一次失败就在用户存储里留 6MB 垃圾
            runCatching { if (tmp.exists()) tmp.delete() }
                .onFailure { Diagnostics.w(TAG, "清理临时文件失败: ${it.message}") }
            throw t
        }
    }

    private fun remove(dict: OptionalDict) {
        val f = dictFile(dict.fileName)
        val ok = runCatching { f.delete() }.getOrDefault(false)
        Diagnostics.i(TAG, "分类词库删除: ${dict.fileName} ok=$ok")
        setStatus(if (ok) {
            getString(R.string.dict_removed, dict.name)
        } else {
            getString(R.string.dict_remove_failed)
        })
        refreshList()
        if (ok) promptRestart()
    }

    /**
     * 词库只在 IME 启动时加载，增删后必须重启输入法进程才会生效。
     */
    private fun promptRestart() {
        setStatus(getString(R.string.dict_need_restart))
        restartImeForDict()
    }

    /**
     * 重启输入法进程以加载新词库。
     *
     * 延迟 1.5 秒让文件写入落盘；系统会自动重建 IME 服务并加载 `dicts/` 下的词库，
     * 本进程（含本 Activity）随之结束。与设置页 saveAndRestart 同款做法。
     *
     * 用 Handler 而不是 View 的 postDelayed：页面可能已被用户关闭，
     * 挂在已销毁 View 上的延时任务不保证执行，而这里必须执行。
     */
    private fun restartImeForDict() {
        Diagnostics.i(TAG, "分类词库变更：1.5s 后重启输入法进程以加载")
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 1500L)
    }

    // ── 工具 ────────────────────────────────────────────────

    private fun dictFile(fileName: String) =
        File(File(filesDir, PinyinEngine.OPT_DICT_DIR), fileName)

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun wrapWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun buttonLp(right: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { rightMargin = dp(right) }

    private companion object {
        const val TAG = "DictManager"

        /** 与剪贴板历史页同一套配色，保证观感一致 */
        val COLOR_BG = Color.parseColor("#0B1020")
        val COLOR_TOPBAR = Color.parseColor("#141C33")
        val COLOR_CARD = Color.parseColor("#1C1F26")
        val COLOR_TEXT_PRIMARY = Color.parseColor("#ECEEF2")
        val COLOR_TEXT_SECONDARY = Color.parseColor("#9CA3AF")
        val COLOR_ACCENT = Color.parseColor("#4C8DFF")
        val COLOR_DANGER = Color.parseColor("#E5484D")
        val COLOR_OK = Color.parseColor("#4CC38A")
        val COLOR_WARN = Color.parseColor("#F0A020")
    }
}
