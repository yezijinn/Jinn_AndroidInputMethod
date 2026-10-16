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
import android.widget.Toast

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
         * 紧凑行参数（2026-10-02：13 个分组要一屏放完，不必上下滑动）。
         *
         * 行高 = 卡片上下内边距 ×2 + 箭头按钮高 = 5×2+32 = 42dp，加卡片间距 2dp 共 44dp，
         * 13 行 ≈ 572dp。原先 6dp 内边距 + 36dp 按钮 = 50dp/行，一屏差半行（13. 注音 被切）。
         */
        const val ROW_PADDING_DP = 5
        const val ARROW_HEIGHT_DP = 32

        /** 日志 TAG（本页是纯 JVM 之外的 Activity，日志走 [Diagnostics]） */
        const val TAG = "SymbolOrder"

        /** 禁用态下行文字的降透明度（该分组已从符号面板移出） */
        const val DISABLED_ALPHA = 0.4f

        // 页面文案在代码里下发（AGENTS.md：`values/strings.xml` 默认不改动）—— 与其余页面一致。
        // 这两页（本页与收藏符号页）此前是唯一把说明 / 按钮文案也留在 strings.xml 的
        // （2026-10-02 统一，见 B-152）。
        // ⚠ 标题**不在**这里：`symbol_order_title` 同时是 `AndroidManifest` 里本 activity 的
        //   `android:label`，必须与 `app_name` / `settings_title` 一样留在 strings.xml，
        //   所以标题由布局直接引用那条 string（设置页的入口按钮也用它）。
        const val TEXT_DESC = "改变符号面板分组的排序，收起键盘即生效；点「X」可禁用某一分组"
        const val TEXT_RESET = "恢复默认顺序"

        /** 至少保留一个可用分组时的提示（不写死「最后一个」以外的细节） */
        const val TEXT_KEEP_ONE = "至少保留一个符号分组"
    }

    override fun onPause() {
        super.onPause()
        // 只有真正调过才通知（进来看看就退出的场景不必重建键盘视图）。
        // 标记不复位：重建可能被「有未上屏输入」守卫延后，下次 onPause 再通知一次（重建幂等）。
        if (dirty) JinnIme.onSymbolLayoutChanged()
    }

    private fun renderRows() {
        val prefs = Prefs(this)
        val labels = SymbolOrder.parse(prefs.symbolGroupOrder)
        val disabled = SymbolOrder.disabledParse(prefs.symbolDisabledGroups)
        orderList.removeAllViews()
        labels.forEachIndexed { idx, label ->
            val off = label in disabled
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = "${idx + 1}. $label"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                // 禁用态视觉：该分组在符号面板里不再出现，整行文字压暗以示区别（可再次点击 X 恢复）
                alpha = if (off) DISABLED_ALPHA else 1f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(name)
            row.addView(
                orderArrowButton(R.drawable.ic_arrow_up, up = true, label = label, enabled = idx > 0) {
                    applyOrder(SymbolOrder.move(labels, idx, idx - 1))
                },
            )
            row.addView(
                orderArrowButton(
                    R.drawable.ic_arrow_down,
                    up = false,
                    label = label,
                    enabled = idx < labels.lastIndex,
                ) {
                    applyOrder(SymbolOrder.move(labels, idx, idx + 1))
                },
            )
            row.addView(toggleButton(label, off))
            PageStyle.addCard(orderList, row, ROW_PADDING_DP)
        }
    }

    /**
     * 「X」禁用 / 启用按钮：点击后该分组从符号面板移出（再点恢复），状态即时落盘。
     *
     * 与 ↑↓ 同款纯图标按钮（无底色），只把字形换成叉号；禁用态下动作变成「启用」，
     * 所以图标本身不压暗 —— 压暗的是该行文字（见 [renderRows]）。
     */
    private fun toggleButton(label: String, disabled: Boolean): ImageButton = ImageButton(this).apply {
        setImageResource(R.drawable.ic_symbol_close)
        // 图标没有文字，名字就是全部信息：动作随当前状态取「禁用××」/「启用××」
        contentDescription = SymbolOrder.toggleDescription(disabled, label)
        background = null
        setOnClickListener { toggleDisabled(label) }
        layoutParams = LinearLayout.LayoutParams(
            PageStyle.dp(context, 40), PageStyle.dp(context, ARROW_HEIGHT_DP),
        ).apply {
            marginStart = PageStyle.dp(context, 6)
        }
    }

    /**
     * 切换某分组的禁用态并落盘。
     *
     * 不允许把**最后一个**可用分组也禁用：符号面板至少要有内容可显示（脏备份里的「全禁用」
     * 由 [SymbolOrder.groupsInOrder] 的另一道回退兜底，这里只是让界面行为与之一致）。
     */
    private fun toggleDisabled(label: String) {
        val prefs = Prefs(this)
        val all = SymbolOrder.parse(prefs.symbolGroupOrder)
        val disabled = SymbolOrder.disabledParse(prefs.symbolDisabledGroups).toMutableSet()
        if (label in disabled) {
            disabled.remove(label)
        } else {
            if (all.none { it != label && it !in disabled }) {
                Toast.makeText(this, TEXT_KEEP_ONE, Toast.LENGTH_SHORT).show()
                return
            }
            disabled.add(label)
        }
        prefs.symbolDisabledGroups = SymbolOrder.disabledSerialize(disabled)
        dirty = true
        Diagnostics.i(TAG, "符号分组${if (label in disabled) "禁用" else "启用"}: $label")
        renderRows()
    }

    private fun applyOrder(labels: List<String>) {
        Prefs(this).symbolGroupOrder = SymbolOrder.serialize(labels)
        dirty = true // ↑↓ 只在可移动时才启用，走到这里顺序一定变过
        renderRows()
    }

    /**
     * ↑↓ 按钮：**纯矢量箭头**（无底色 / 无边框），首/末行相应方向禁用（半透明）。
     *
     * 2026-10-02：取消包裹箭头的次级按钮矩形，箭头本身要放大 —— 文字字形 `↑` / `↓`
     * 在系统字体里只占 em 的一小块（20sp 真机实测不足 5dp 高），所以改用 28dp 画布的矢量图标
     * （`ic_arrow_up` / `ic_arrow_down`）。按压反馈与热区仍由这个按钮承担。
     */
    private fun orderArrowButton(
        iconRes: Int,
        up: Boolean,
        label: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            // 无障碍名 = 动作 + 对象（BUG.md L-1138）：图标没有文字，名字就是全部信息。
            // 原先用的是 strings.xml 里那两条字形（「↑」/「↓」）⇒ 读屏只念「上箭头」，
            // 听不出对哪一组做什么；那两条资源因此不再被引用（按 strings.xml 约定留着）。
            contentDescription = SymbolOrder.moveDescription(up, label)
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
