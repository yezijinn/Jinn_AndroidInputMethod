package com.jinn.inputmethod

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 「存储占用」页：把 [StorageUsage] 的统计结果摊成卡片（2026-10-10 用户提出）。
 *
 * 只读，不改任何文件：给用户一个「存储由什么组成」的答案。分类与口径全部在 [StorageUsage]
 * 里（含「安装包单列」「其他兜底」的原因），这里只管排版 —— 每项一张卡：名称 + 体积 + 一句
 * 「它是什么 / 在哪管理」+ **逐文件明细**（文件名与体积，不折叠、不省略成「N 个文件」：
 * 2026-10-10 用户明确要求展开到每个文件），顶部一张总账（数据合计 / 设备剩余 / 最大一项）。
 *
 * 样式沿用本项目的代码侧规格（[PageStyle] / [PageChrome]），与词库页同一骨架：
 * 深色顶栏 + 关闭按钮 + 提示行 + 卡片列表。
 */
class StorageUsageActivity : ComponentActivity() {

    private lateinit var listHost: LinearLayout

    /** 定时换色的准点定时器（与词库页同款：跨过切换点时回来也能补上） */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }
        root.addView(buildTopBar())
        HINTS.forEachIndexed { i, t ->
            root.addView(hint(t), matchWrap(top = if (i == 0) 4 else 0))
        }

        listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        root.addView(
            ScrollView(this).apply { addView(listHost) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        Diagnostics.i(TAG, "StorageUsageActivity: 打开存储占用页")
    }

    /** 每次回到前台都重算：用户可能刚在词库页删了包、在剪贴板页清了历史 */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 统计放后台（目录遍历是 IO）；回主线程渲染，页面已销毁就丢弃结果 */
    private fun refresh() {
        BackgroundIo.run {
            val report = runCatching { StorageUsage.survey(this@StorageUsageActivity) }
                .onFailure { Diagnostics.w(TAG, "存储统计失败: ${it.javaClass.simpleName}") }
                .getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(report)
            }
        }
    }

    // ── 渲染 ───────────────────────────────────────────────

    private fun render(report: StorageUsage.Report?) {
        listHost.removeAllViews()
        if (report == null) {
            PageStyle.addCard(listHost, cardView().apply {
                addView(line(TEXT_FAILED, getColor(R.color.text_secondary), 13f))
            })
            return
        }

        // 总览
        PageStyle.addCard(listHost, cardView().apply {
            addView(line(TEXT_DATA_TOTAL + ByteSize.text(report.dataBytes), getColor(R.color.text_primary), 17f, bold = true))
            if (report.freeBytes > 0) {
                addView(line(TEXT_FREE + ByteSize.text(report.freeBytes), getColor(R.color.text_secondary), 12f, top = 4))
            }
            report.items.firstOrNull()?.let {
                addView(
                    line(
                        TEXT_BIGGEST + it.label + " " + ByteSize.text(it.bytes),
                        getColor(R.color.text_secondary), 12f, top = 2,
                    ),
                )
            }
        })

        // 成分（已按体积降序）；每项**逐文件摊开**（需求：不写成「N 个文件」，每个文件是什么、多大都要有）
        for (item in report.items) {
            PageStyle.addCard(listHost, cardView().apply {
                addView(row(item.label, ByteSize.text(item.bytes)))
                addView(line(item.note, getColor(R.color.text_secondary), 12f, top = 4))
                for (e in item.entries) {
                    addView(fileRow(e.name, ByteSize.text(e.bytes)))
                }
            })
        }

        // 安装包单列（用户最容易把 assets 里的内置词库误算进「下载的包」）
        if (report.apkBytes > 0) {
            PageStyle.addCard(listHost, cardView().apply {
                addView(row(TEXT_APK, ByteSize.text(report.apkBytes)))
                addView(line(TEXT_APK_NOTE, getColor(R.color.text_secondary), 12f, top = 4))
            })
        }
    }

    /** 一行：名称（占满左侧）+ 体积（右对齐） */
    private fun row(label: String, size: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            line(label, getColor(R.color.text_primary), 14f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(line(size, getColor(R.color.text_secondary), 14f))
    }

    /**
     * 明细行：文件名 + 体积，比类别行缩进一档、字号小一档。
     *
     * 长名（如 `base.1791633154000.idx`）**中间省略**而不是折行：一条文件一行，
     * 折行会把「名字 ↔ 体积」的对应关系打断（用户要的就是一眼对账）。
     */
    private fun fileRow(name: String, size: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(3), 0, 0)
        addView(
            TextView(this@StorageUsageActivity).apply {
                text = name
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            TextView(this@StorageUsageActivity).apply {
                text = size
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(8), 0, 0, 0)
            },
        )
    }

    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

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

    /** 静态提示行（每句一个 TextView，不折行；与词库页同款） */
    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(dp(16), 0, dp(16), 0)
    }

    /** 顶栏：左标题、右关闭（深蓝顶栏 #141C33，与其他设置子页一致） */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.card_bg))
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        bar.addView(
            TextView(this).apply {
                text = TEXT_TITLE
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                setTypeface(Typeface.DEFAULT_BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            TextView(this).apply {
                text = PageChrome.CLOSE
                contentDescription = PageChrome.CLOSE_DESC
                setTextColor(getColor(R.color.text_secondary))
                textSize = 20f
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    Diagnostics.i(TAG, "StorageUsageActivity: 用户关闭页面")
                    finish()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return bar
    }

    private fun dp(v: Int): Int = PageStyle.dp(this, v)

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private companion object {
        const val TAG = "StorageUsage"

        const val TEXT_TITLE = "存储占用"
        const val TEXT_DATA_TOTAL = "应用数据合计 "
        const val TEXT_FREE = "设备剩余空间 "
        const val TEXT_BIGGEST = "其中最大一项："
        const val TEXT_APK = "安装包"
        const val TEXT_APK_NOTE = "内置词库与按键音都在安装包里 —— 不占上面这些空间，卸载时才释放"
        const val TEXT_FAILED = "统计失败：读不到应用目录（可返回后重试）"

        val HINTS = listOf(
            "统计本机实际占用：词库包与索引缓存、剪贴板、日志等。",
            "索引缓存由词库包首次使用时生成；删除对应词库包后会自动清理。",
        )
    }
}
