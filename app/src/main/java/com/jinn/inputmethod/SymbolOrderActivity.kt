package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 符号分组排序页：从设置页「符号分组顺序」按钮进入。
 *
 * 每行一个分组 + ↑↓ 调整，改动即时落盘（[Prefs.symbolGroupOrder]）；
 * [onPause] 时仅在真正调过顺序的情况下通知 IME 重建键盘视图，让新顺序立即生效 ，
 * 键盘视图在创建时读取一次顺序，不通知就只会等到下次键盘整体重建。
 *
 * 顺序规则（归一 / 移动 / 兜底）全部在 [SymbolOrder]，本页只负责展示与写盘。
 */
class SymbolOrderActivity : Activity() {

    private lateinit var orderList: LinearLayout

    /** 本次进入是否调过顺序（决定离开时要不要通知 IME 重建键盘视图） */
    private var dirty = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_symbol_order)
        orderList = findViewById(R.id.symbol_order_list)
        findViewById<Button>(R.id.btn_symbol_order_close).apply {
            // 关闭键：键面字形与可听名统一来自 PageChrome（原先各页自写 `X` / `关闭`，见 BUG.md L-477）
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.text_symbol_order_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_symbol_order_reset).apply {
            text = TEXT_RESET
            setOnClickListener {
                // 「恢复默认」：写入空串，Prefs 的 setter 会归一为完整的默认序列串（见 [SymbolOrder]）
                Prefs(this@SymbolOrderActivity).symbolGroupOrder = ""
                dirty = true
                renderRows()
            }
        }
        renderRows()
    }

    private companion object {
        /**
         * 紧凑行参数（用户 2026-10-02：13 个分组要一屏放完，不必上下滑动）。
         *
         * 行高 = 卡片上下内边距 ×2 + 箭头按钮高 = 5×2+32 = 42dp，加卡片间距 2dp 共 44dp，
         * 13 行 ≈ 572dp。原先 6dp 内边距 + 36dp 按钮 = 50dp/行，一屏差半行（13. 注音 被切）。
         */
        const val ROW_PADDING_DP = 5
        const val ARROW_HEIGHT_DP = 32

        // 页面文案在代码里下发（AGENTS.md：`values/strings.xml` 默认禁改）—— 与其余页面一致。
        // 这两页（本页与收藏符号页）此前是唯一把说明 / 按钮文案也留在 strings.xml 的
        // （2026-10-02 统一，见 B-152）。
        // ⚠ 标题**不在**这里：`symbol_order_title` 同时是 `AndroidManifest` 里本 activity 的
        //   `android:label`，必须与 `app_name` / `settings_title` 一样留在 strings.xml，
        //   所以标题由布局直接引用那条 string（设置页的入口按钮也用它）。
        const val TEXT_DESC = "改变符号面板分组的排序，收起键盘即生效"
        const val TEXT_RESET = "恢复默认顺序"
    }

    override fun onPause() {
        super.onPause()
        // 只有真正调过才通知（进来看看就退出的场景不必重建键盘视图）。
        // 标记不复位：重建可能被「有未上屏输入」守卫延后，下次 onPause 再通知一次（重建幂等）。
        if (dirty) JinnIme.onSymbolLayoutChanged()
    }

    private fun renderRows() {
        val labels = SymbolOrder.parse(Prefs(this).symbolGroupOrder)
        orderList.removeAllViews()
        labels.forEachIndexed { idx, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = "${idx + 1}. $label"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(name)
            row.addView(orderArrowButton(R.drawable.ic_arrow_up, getString(R.string.symbol_order_move_up), idx > 0) {
                applyOrder(SymbolOrder.move(labels, idx, idx - 1))
            })
            row.addView(
                orderArrowButton(
                    R.drawable.ic_arrow_down,
                    getString(R.string.symbol_order_move_down),
                    idx < labels.lastIndex,
                ) {
                    applyOrder(SymbolOrder.move(labels, idx, idx + 1))
                },
            )
            PageStyle.addCard(orderList, row, ROW_PADDING_DP)
        }
    }

    private fun applyOrder(labels: List<String>) {
        Prefs(this).symbolGroupOrder = SymbolOrder.serialize(labels)
        dirty = true // ↑↓ 只在可移动时才启用，走到这里顺序一定变过
        renderRows()
    }

    /**
     * ↑↓ 按钮：**纯矢量箭头**（无底色 / 无边框），首/末行相应方向禁用（半透明）。
     *
     * 用户 2026-10-02：取消包裹箭头的次级按钮矩形，箭头本身要放大 —— 文字字形 `↑` / `↓`
     * 在系统字体里只占 em 的一小块（20sp 真机实测不足 5dp 高），所以改用 28dp 画布的矢量图标
     * （`ic_arrow_up` / `ic_arrow_down`）。按压反馈与热区仍由这个按钮承担。
     */
    private fun orderArrowButton(
        iconRes: Int,
        desc: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            // 无障碍名沿用 strings.xml 里那两条（图标没有文字，没有它就只剩「按钮」）
            contentDescription = desc
            background = null
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setOnClickListener { onClick() }
            // dp 换算统一走 PageStyle（原先这里内联 resources.displayMetrics.density 相乘）
            layoutParams = LinearLayout.LayoutParams(
                PageStyle.dp(context, 40), PageStyle.dp(context, ARROW_HEIGHT_DP)).apply {
                marginStart = PageStyle.dp(context, 6)
            }
        }
    }
}
