package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 符号分组排序页：从设置页「符号分组顺序」按钮进入。
 *
 * 每行一个分组 + ↑↓ 调整，改动**即时落盘**（[Prefs.symbolGroupOrder]）；
 * 离开页面时（[onPause]）通知 IME 重建键盘视图，让新顺序立即生效 ——
 * 键盘视图在创建时读取一次顺序，不通知就只会等到下次键盘整体重建。
 *
 * 顺序规则（归一 / 移动 / 兜底）全部在 [SymbolOrder]，本页只负责展示与写盘。
 */
class SymbolOrderActivity : Activity() {

    private lateinit var orderList: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_symbol_order)
        orderList = findViewById(R.id.symbol_order_list)
        findViewById<Button>(R.id.btn_symbol_order_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_symbol_order_reset).setOnClickListener {
            Prefs(this).symbolGroupOrder = "" // 空串 = 默认次序
            renderRows()
        }
        renderRows()
    }

    override fun onPause() {
        super.onPause()
        JinnIme.onSymbolOrderChanged()
    }

    /** 兼容旧入口（如有外部组件直接拉起本页） */
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SymbolOrderActivity::class.java))
        }
    }

    private fun renderRows() {
        val labels = SymbolOrder.parse(Prefs(this).symbolGroupOrder)
        orderList.removeAllViews()
        val density = resources.displayMetrics.density
        labels.forEachIndexed { idx, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            }
            val name = TextView(this).apply {
                text = "${idx + 1}. $label"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(name)
            row.addView(orderArrowButton(getString(R.string.symbol_order_move_up), idx > 0) {
                applyOrder(SymbolOrder.move(labels, idx, idx - 1))
            })
            row.addView(orderArrowButton(getString(R.string.symbol_order_move_down), idx < labels.lastIndex) {
                applyOrder(SymbolOrder.move(labels, idx, idx + 1))
            })
            orderList.addView(row)
        }
    }

    private fun applyOrder(labels: List<String>) {
        Prefs(this).symbolGroupOrder = SymbolOrder.serialize(labels)
        renderRows()
    }

    /** ↑↓ 小按钮：主题同款（半透明蓝底圆角 + 白字），首/末行相应方向禁用（半透明） */
    private fun orderArrowButton(label: String, enabled: Boolean, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            // 系统默认灰底与极光主题不符（用户反馈）：换主题次级按钮底 + 白字
            background = getDrawable(R.drawable.btn_aurora_secondary)
            setTextColor(getColor(R.color.text_primary))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                (44 * density).toInt(), (36 * density).toInt()).apply {
                marginStart = (6 * density).toInt()
            }
        }
    }
}
