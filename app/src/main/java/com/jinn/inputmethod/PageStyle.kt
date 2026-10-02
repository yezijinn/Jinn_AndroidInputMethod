package com.jinn.inputmethod

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 页面里**代码生成**的控件样式 —— 与 `values/styles.xml` 一一对应。
 *
 * 为什么需要它：XML 里的控件已经统一走样式了，但这几个页面的列表项是**代码造的**
 * （`LinearLayout(this)` + 手写内边距 / 背景），于是又长出两套写法：收藏符号 / 模糊音 /
 * 生僻字 / 符号排序四页的行**根本没有外框**，词库页自造 `surface_hi + 10dp` 圆角（无描边，
 * 与 XML 侧的 `card_aurora` 不是一套）。现在「列表项 = 一张卡片」只有这一处定义
 * （守卫 `PageStyleParityTest` 会盯）。
 *
 * 与之对应的 XML 侧规格：`SettingsCard`（`card_aurora` + 12dp 内边距 + 10dp 间距）、
 * `SectionTitle`（13sp + `text_primary`）。改这里要同步改那里。
 */
internal object PageStyle {

    /** 卡片之间的间距（与 `SettingsCard` 的观感一致） */
    private const val CARD_GAP_DP = 2

    /** 卡片内边距（与 `SettingsCard` 的 `android:padding` 一致） */
    private const val CARD_PADDING_DP = 6

    /**
     * 把 [view] 当作一张卡片挂到 [parent] 上：与 XML 侧的 `@style/SettingsCard` 同款
     * （同底色 / 同圆角 / 同描边 / 同内边距），并留出与下一项的间距。
     *
     * [paddingDp] 只给「行要一屏放完」的紧凑页留出口子（符号分组顺序页 13 行，见
     * [SymbolOrderActivity]）：改的是这一页的行高，不是列表项规格本身。
     */
    fun addCard(parent: ViewGroup, view: View, paddingDp: Int = CARD_PADDING_DP) {
        dressAsCard(view, paddingDp)
        parent.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(parent.context, CARD_GAP_DP) },
        )
    }

    /**
     * 只取外观（底色 / 圆角 / 描边 / 内边距）：给那些**自己管布局参数**的页面用
     * （词库页的卡片由它自己的 `matchWrap(bottom = …)` 决定外边距）。
     */
    fun dressAsCard(view: View, paddingDp: Int = CARD_PADDING_DP) {
        val context = view.context
        view.background = context.getDrawable(R.drawable.card_aurora)
        val padding = dp(context, paddingDp)
        view.setPadding(padding, padding, padding, padding)
    }

    /** 卡片内的小节标题（与 `@style/SectionTitle` 同款：13sp + `text_primary`） */
    fun sectionTitle(view: TextView) {
        view.setTextColor(view.context.getColor(R.color.text_primary))
        view.textSize = 13f
    }

    /** dp → px（各页原先各自内联 `resources.displayMetrics.density` 相乘） */
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
