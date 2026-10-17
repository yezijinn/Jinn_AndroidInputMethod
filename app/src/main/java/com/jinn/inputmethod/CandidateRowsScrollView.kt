package com.jinn.inputmethod

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 候选区的**纵向**滚动层（展开态专用）。
 *
 * 展开后候选可能多于四行，第 5 行起靠纵向滑动浏览 —— 这一层负责纵向，横向交给内层
 * [CandidateScrollView]（两者嵌套，按位移的主导方向决定谁消费，见 [onInterceptTouchEvent]）。
 *
 * 未展开时本层**不滚动也不拦截**：纵向手势直接放行（键盘整体不响应纵向拖动，行为与现状一致）。
 * 作者 2026-10-17 指定：纵向为**跟手连续滚动**（不是整行步进）。
 */
class CandidateRowsScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    /** 展开态（PinyinKeyboardView 每帧下发）：未展开时纵向不消费 */
    var expanded: Boolean = false

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!expanded) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 纵向位移占主导才拦 —— 横向仍归内层（换列）
                if (!dragging && Math.abs(dy) > Math.abs(dx)) dragging = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!expanded) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return super.onTouchEvent(ev)
    }
}
