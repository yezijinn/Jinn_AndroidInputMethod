package com.jinn.inputmethod

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 候选区的**纵向**滚动层（展开态专用）。
 *
 * 展开态最多四排（`CandidateRows.MAX_EXPANDED_ROWS`），而栏高又按网格向上取整到整行字母键 ⇒
 * 内容高（拼音条 + 排数 × 行高）恒 ≤ 视口高，**正常几何下本层产生不了滚动量**。它留在树里是
 * 给「网格暂时高于栏高」的失配窗口兜底：展开排数缓存与当前字号不一致时栏高被
 * `MAX_COVERED_KEY_ROWS` 夹顶，被裁的末排还能拖出来看一眼（外观一改即自愈）。
 * 横向交给内层 [CandidateScrollView]（两者嵌套，按位移的主导方向决定谁消费，见
 * [onInterceptTouchEvent]）。
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
