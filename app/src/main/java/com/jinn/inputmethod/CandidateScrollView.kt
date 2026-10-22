package com.jinn.inputmethod

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * 候选栏滚动容器：跟手横滑（[HorizontalScrollView] 原生行为）。
 *
 * 展开态（多行候选）：横向仍走列滑动，**纵向**交给父级 —— 内容按列排布后，排数受四排上限与
 * 栏高夹取，正常几何下父级也没有纵向滚动量（见 [CandidateRowsScrollView]）。
 * 本容器是 HorizontalScrollView，纵向不消费事件（展开态父级负责）。
 *
 * 曾有一个「整屏翻页」模式（手指一划直接翻过一整屏、不做跟手位移），随设置项一起移除：
 * 它把「追加候选落在新的一页」做成了优点，实际代价是候选永远不跟手，
 * 而跟手滑动本就能覆盖同一批使用场景。
 */
class CandidateScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    /**
     * 展开态（多行候选）：横向仍走列滑动（展开态强制跟手），**纵向**交给父级 —— 内容按列排布后，
     * 排数受四排上限与栏高夹取，正常几何下父级也没有纵向滚动量（见 [CandidateRowsScrollView]）。
     * 本容器是 HorizontalScrollView，纵向不消费事件（展开态父级负责）。
     */
    var expanded: Boolean = false

    /**
     * 回到最左，并**当场结束进行中的甩动**。
     *
     * `scrollTo(0, 0)` 只改本视图的滚动位、不碰内部 `OverScroller`：下一帧 `computeScroll()` 仍按
     * 滚动器自己的位置再滚回去 ⇒ 复位被覆盖。真机表现是换一批候选后，旧偏移仍生效，候选行左边缘
     * 出现被裁切的字形（实测首个暗像素 x=28，正常位恒 51）。
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
