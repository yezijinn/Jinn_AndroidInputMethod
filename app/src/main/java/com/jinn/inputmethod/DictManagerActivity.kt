package com.jinn.inputmethod

import android.app.Activity
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
import android.widget.Toast
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
 * 引擎启动时自动扫描加载。加载是延迟的，基础词库先就绪，可选包在后台补齐，
 * 所以页面标注的是「开机后首次输入候选就绪需等待约 N 秒」，而不是「启动耗时」。
 *
 * 说明文案按句拆成多行渲染（[OptionalDict.descLines]），一行就是一句话：
 * 交给系统自动折行会出现断句不良的折行，读起来别扭，所以主动分行。
 */
class DictManagerActivity : Activity() {

    /** 主题应用点：与设置页同一套（早于 onCreate，避免先按系统配置渲染一帧再换色） */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    private lateinit var listHost: LinearLayout
    private lateinit var textStatus: TextView

    /**
     * 正在下载的文件名，用于禁用按钮与显示进度（null 表示空闲）。
     *
     * 存在 companion 里：Activity 会因旋转/重建换成新实例，实例字段随即丢失，
     * 新页面的「下载」按钮恢复可点，再点一次就是第二个线程写同一个 `.tmp`，
     * 字节交错后被 renameTo 成「有效」词库。跨线程写，故 @Volatile。
     */
    private var downloading: String?
        get() = activeDownload
        set(value) { activeDownload = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }

        root.addView(buildTopBar())

        // 提示：两句各占一行，避免被系统折行
        root.addView(hint(getString(R.string.dict_manager_hint_line1)), matchWrap(top = 10))
        root.addView(hint(getString(R.string.dict_manager_hint_line2)), matchWrap())

        // 动态状态行：初始隐藏，下载/删除时才出现。单行 + 省略号，
        // 内容是「正在下载 X…」这类变长文案，不适合按句拆分。
        textStatus = TextView(this).apply {
            setTextColor(getColor(R.color.accent))
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
        setTextColor(getColor(R.color.text_secondary))
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
            setBackgroundColor(getColor(R.color.card_bg))
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }

        bar.addView(TextView(this).apply {
            text = getString(R.string.dict_manager_title)
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 右上角关闭：结束本页返回设置页
        bar.addView(TextView(this).apply {
            text = "✕"
            setTextColor(getColor(R.color.text_secondary))
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
            background = rounded(getColor(R.color.surface_hi), 10)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // 词库名
        card.addView(line(dict.name, getColor(R.color.text_primary), 16f, bold = true))

        // 说明：每句独立一行，不做自动折行
        for (sentence in dict.descLines) {
            card.addView(line(sentence, getColor(R.color.text_secondary), 13f, top = 4))
        }

        // 代价一行一句，避免长句被折
        card.addView(line("体积 %.1f MB。".format(Locale.US, dict.sizeMb), getColor(R.color.warn), 13f, top = 8))
        card.addView(line(getString(R.string.dict_startup_cost_line1), getColor(R.color.warn), 13f, top = 2))
        card.addView(line(getString(R.string.dict_startup_cost_line2, dict.startupSec), getColor(R.color.warn), 13f, top = 2))
        card.addView(line(getString(R.string.dict_startup_cost_line3), getColor(R.color.warn), 13f, top = 2))

        // 安装状态
        card.addView(
            line(
                text = if (installed) {
                    getString(R.string.dict_status_installed, formatSize(file.length()))
                } else {
                    getString(R.string.dict_status_absent)
                },
                color = if (installed) getColor(R.color.ok) else getColor(R.color.text_secondary),
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
                color = getColor(R.color.accent),
                enabled = downloading == null,
            ) { download(dict) },
            buttonLp(right = 8),
        )
        if (installed) {
            row.addView(
                actionButton(
                    text = getString(R.string.dict_action_remove),
                    color = getColor(R.color.danger),
                    enabled = downloading == null,
                ) { remove(dict) },
                buttonLp(),
            )
        }
        card.addView(row, matchWrap(top = 12))
        return card
    }

    /**
     * 单行文字。每句话单独一个 TextView，从根上避免长句被自动折行，
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
        setTextColor(getColor(R.color.text_on_accent))
        textSize = 14f
        background = rounded(if (enabled) color else getColor(R.color.btn_disabled), 8)
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
                    val len = fetchToFile(url, dict.fileName, dict.checksum)
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
            // 用户可能在下载完成前点 ✕ 关闭页面：此时 View 已销毁、不能动 UI，
            // 但词库已经下完、用户本就希望生效，所以仍要重启 IME。
                if (isFinishing || isDestroyed) {
                    Diagnostics.i(TAG, "下载已完成但页面已关闭（ok=$ok），仍重启输入法以加载")
                    if (ok) restartImeForDict()
                    return@runOnUiThread
                }
                val msg = if (ok) getString(R.string.dict_download_done, dict.name)
                else getString(R.string.dict_download_failed, lastError)
                setStatus(msg)
                // 失败必须用 Toast 再提示一次：状态行是 Activity 的 View，
                // 若下载期间页面发生过重建，runOnUiThread 里拿到的仍是旧实例的
                // textStatus，提示写进了已不在屏幕上的 View，用户什么也看不到
                // （实测：断网点下载后页面毫无反应，只会以为按钮坏了）。
                // Toast 挂在系统窗口上，不受 Activity 重建影响。
                if (!ok) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshList()
                if (ok) restartImeForDict()
            }
        }.start()
    }

    /**
     * 下载到临时文件、校验 SHA-256 后再改名，避免中途失败留下半个文件被引擎当作有效词库加载
     * （引擎只认 `.xz` 结尾，`X.xz.tmp` 不会被扫到，但失败时仍会残留占空间，所以显式清理）。
     * 返回写入字节数。
     *
     * 摘要校验是收下的唯一判据：词库内容会直接变成候选词上屏到任意输入框，
     * 只要有一处环节能改字节（被替换的 Release 附件、被劫持的重定向、传输截断），
     * 就等于拿到了「往用户每一次输入里塞词」的能力。校验不通过时绝不改名，
     * 旧版本（若存在）保持不变，临时文件立即删除。
     */
    private fun fetchToFile(url: String, fileName: String, checksum: String): Long {
        val dir = File(filesDir, PinyinEngine.OPT_DICT_DIR).apply { mkdirs() }
        val tmp = File(dir, "$fileName.tmp")
        val dst = File(dir, fileName)

        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                // 禁止 https→http 降级：Manifest 全局开了 usesCleartextTraffic，
                // OkHttp 默认 followSslRedirects=true 会跟随这种跳转，一次 302
                // 就能把词库下载降到明文 HTTP（同网段 MITM 改内容即可注入任意候选词）。
                .followSslRedirects(false)
                .build()
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("响应为空")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out -> copyCapped(input, out) }
                }
            }
            // 校验必须在改名之前：一旦 rename 成 `.xz`，引擎下一次空闲加载就会扫到它。
            val actual = OptionalDicts.sha256Of(tmp)
            if (!OptionalDicts.matchesChecksum(actual, checksum)) {
                error("文件校验失败（期望 $checksum，实际 $actual），已丢弃")
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

    /**
     * 带上限的流拷贝，超过 [MAX_DOWNLOAD_BYTES] 立即抛错（临时文件由调用方清理）。
     *
     * 下载 URL 是固定的 Release 附件，但 `followRedirects(true)` 会把请求交给目标主机
     * 继续指路：没有上限时，一个「一直有数据、永不结束」的响应足以写满用户存储。
     * 上限取现役最大包（6.36MB）的约 10 倍，正常包碰不到线。
     */
    private fun copyCapped(input: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_DOWNLOAD_BYTES) {
                error("响应超过上限 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB，已中止")
            }
            out.write(buf, 0, n)
        }
    }

    /**
     * 删除已装词库。
     *
     * 下载进行中一律拒绝：下载线程是「写 `.xz.tmp` → 改名成 `.xz`」，
     * 与删除并发时会出现「用户点了删除、删除成功后下载又把包改回来」，
     * 界面上表现为删不掉，而用户以为已经卸载的那个包仍会被引擎加载。
     */
    private fun remove(dict: OptionalDict) {
        if (downloading != null) {
            Toast.makeText(this, R.string.dict_remove_busy, Toast.LENGTH_SHORT).show()
            return
        }
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

        @Volatile
        private var activeDownload: String? = null

        /** 单个词库包的下载上限（字节）：现役最大包 6.36MB，取 64MB 留足余量 */
        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
    }
}
