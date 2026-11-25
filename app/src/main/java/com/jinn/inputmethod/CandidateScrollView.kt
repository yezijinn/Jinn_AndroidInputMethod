package com.jinn.inputmethod

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * 候选栏滚动容器：跟手横滑，走 [HorizontalScrollView] 原生行为（列滑动）。
 *
 * 触摸语义：候选词是 TextView + 点击监听，点选才上屏；反馈音在点击时发、不在 ACTION_DOWN 发，
 * 横滑列表不会先响一声。候选词未挂长按监听，长按抬手仍按点选处理。横向拖动即原生跟手滚动，
 * overScrollMode=OVER_SCROLL_NEVER 关掉边缘回弹；滑到末尾追加更多候选。
 *
 * 本类不覆写拦截、也不调 requestDisallowInterceptTouchEvent：纵向取舍全在父层
 * [CandidateRowsScrollView] —— 它只在展开态且纵向位移占优时才拦，横向天然留在本层，无需争抢。
 * 本类纵向不消费事件；正常几何下父层也没有纵向滚动量（排数受四排上限与栏高夹取，内容高 ≤ 视口）。
 *
 * 曾做过「整屏翻页」（一划翻过一屏、不跟手），随设置项移除：它把「追加候选落在新页」当优点，
 * 代价是候选永不跟手，而跟手滑动已覆盖同一批场景。
 */
class CandidateScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    /**
     * 展开态提示，由 [PinyinKeyboardView] 每帧下发。
     * 本类没有拦截逻辑、也不读它，真正的取舍在父层 [CandidateRowsScrollView]：
     * 展开时父层才可能拦纵向，未展开则放行。横滑始终归本层。
     */
    var expanded: Boolean = false

    /**
     * 回到最左，并**当场结束进行中的甩动**。
     *
     * `scrollTo(0, 0)` 只改本视图的滚动位、不碰内部 `OverScroller`：下一帧 `computeScroll()` 仍按
     * 滚动器自己的位置再滚回去 ⇒ 复位被覆盖。现象是换一批候选后，旧偏移仍生效，
     * 候选行左边缘出现被裁切的字形（实测首个暗像素 x=28，正常位恒 51）。
     *
     * ⚠ 顺序不可反：`fling()` 的起点取**调用时的滚动位**。先 `fling(0)` 会把复位前的偏移记成终值，
     * 一旦框架在速度为 0 时仍派发一帧就会弹回旧位；先 `scrollTo` 则起点=终点=0，任何后续帧都只写 0，
     * 不依赖「速度为 0 必然当帧结束」这条框架内部行为。
     *
     * `OverScroller.abortAnimation()` 在本视图上不可达，`smoothScrollTo` 又被同一个滚动器支配
     * ⇒ `fling(0)` 是唯一公开手段（L-1440）。
     */
    fun resetScrollX() {
        scrollTo(0, 0)
        fling(0)
    }
}
