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

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** 一页宽 = 视口可见宽（内容区），至少 1px 防除零 */
    private fun pageWidth(): Int = (width - paddingLeft - paddingRight).coerceAtLeast(1)

    private fun maxScroll(): Int =
        ((getChildAt(0)?.width ?: 0) - pageWidth()).coerceAtLeast(0)

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
                    val target = (scrollX + dir * pageWidth()).coerceIn(0, maxScroll())
                    if (target != scrollX) smoothScrollTo(target, 0)
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
