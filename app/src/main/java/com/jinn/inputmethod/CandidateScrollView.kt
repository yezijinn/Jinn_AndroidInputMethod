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
}
