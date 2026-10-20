package com.jinn.inputmethod

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs

/**
 * 候选栏滚动容器。
 *
 * 两种浏览方式（[paging]，由 `Prefs.candidatePaging` 每帧下发）：
 *  - false（滑动，默认）：沿用 [HorizontalScrollView] 的**跟手横滑**；
 *  - true（翻页）：手指一划**直接翻过一整屏**（页宽 = 视口可见宽），**不做跟手位移** ——
 *    既让「追加候选」落在新的一页、不出现跟手时插内容的不跟手观感，也让每页边界稳定。
 *
 * 翻页态只拦「横向拖动」：位移超过 touchSlop 才拦截（[onInterceptTouchEvent]），轻点因此仍落到
 * 候选条目上、点击选中不受影响；翻页方向按横向位移符号定（左划 = 下一页）。
 */
class CandidateScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    /** 翻页模式开关（PinyinKeyboardView 每帧下发） */
    var paging: Boolean = false

    /**
     * 展开态（多行候选，BUG.md L-1393）：横向仍走列滑动（展开态强制跟手），
     * **纵向**交给父级滚动 —— 内容按列排布后，行数超过一屏可见排数时由父级纵向跟手滚动。
     * 本容器是 HorizontalScrollView，纵向不消费事件（展开态父级负责）。
     */
    var expanded: Boolean = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** 一页宽 = 视口可见宽（内容区），至少 1px 防除零 */
    private fun pageWidth(): Int = (width - paddingLeft - paddingRight).coerceAtLeast(1)

    /** 内容总页数（向上取整；末页不满也算一页） */
    private fun pageCount(): Int {
        val contentWidth = getChildAt(0)?.width ?: 0
        return ((contentWidth + pageWidth() - 1) / pageWidth()).coerceAtLeast(1)
    }

    /**
     * 当前页号。
     *
     * ⚠ 必须特判**钳位态**（BUG.md L-1388）：`HorizontalScrollView.scrollTo` 把 x 钳在 `[0, C−页宽]`，
     * 而末页起点 `(总页数−1)×页宽` **大于** `C−页宽`（除非内容恰为页宽整数倍）⇒ 停在末页时
     * `scrollX = C−页宽`，用它四舍五入会算成**倒数第二页**，回翻于是多退一页（末页不足半屏时
     * 甚至直接跳回第一页）—— 这正是「翻到最后一页再翻回来会少一页」那一族。
     */
    private fun currentPage(): Int {
        val max = ((getChildAt(0)?.width ?: 0) - pageWidth()).coerceAtLeast(0)
        val page = if (max > 0 && scrollX >= max) {
            pageCount() - 1
        } else {
            Math.round(scrollX.toFloat() / pageWidth())
        }
        return page.coerceIn(0, pageCount() - 1)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!paging) return super.onInterceptTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                return false // 先不拦：轻点要能落到候选条目上
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                if (!dragging && abs(dx) > slop && abs(dx) > abs(ev.y - downY)) dragging = true
                return dragging
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!paging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                if (abs(dx) > slop) {
                    val dir = if (dx < 0f) 1 else -1 // 左划看后面的候选
                    // 按**页号**翻页，而不是 `scrollX ± pageWidth`（BUG.md L-1387）：相对位移在
                    // 「末页不满」时会被 maxScroll 钳位、产生累计偏移，页边界越翻越歪；真实症状是
                    // 「翻到最后一页再翻回来会少一页」。页号制下每页起点恒为 `页号 × 页宽`，
                    // 且内容被追加后页数自然重算。
                    // 瞬时到位（不用 smoothScrollTo 的 ~250ms 插值）：作者 2026-10-20
                    // 要求整个 App 无过渡动画。onScrollChange 仍同步派发，末页追加不受影响。
                    val target = (currentPage() + dir).coerceIn(0, pageCount() - 1) * pageWidth()
                    if (target != scrollX) scrollTo(target, 0)
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }
}
