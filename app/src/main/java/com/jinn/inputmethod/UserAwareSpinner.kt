package com.jinn.inputmethod

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.Spinner

/**
 * 带「用户主动操作过」探针的下拉（Spinner）。
 *
 * 为什么需要它（BUG.md L-821）：设置页的下拉此前用 `setOnTouchListener` 里置位的标记当落盘闸门，
 * 于是**键盘（确认键）与读屏（ACTION_CLICK）选中后不落盘** —— 界面显示新值、配置却没变，
 * 退出重进还静默还原。三条用户路径里只有触摸会产生 MotionEvent：
 *
 *  - `spinnerMode="dropdown"` 的触摸由框架 `ForwardingListener` 直接 `mPopup.show()` 打开，
 *    **不经过** `performClick()`（AOSP `Spinner.onTouchEvent` 与构造器）⇒ 只能靠 [onTouchEvent] 埋点；
 *  - 键盘确认键（ENTER / DPAD_CENTER / SPACE）与无障碍 `ACTION_CLICK` 都走
 *    `View.performClickInternal()` → `View.performClick()`（AOSP `View.onKeyUp` /
 *    `performAccessibilityActionInternal`）⇒ 在 [performClick] 埋点即可覆盖；
 *  - 程序化路径（`setSelection`、实例状态恢复、`AdapterView` 内部恢复）**都不经过这两处**
 *    ⇒ 探针恰好等于「用户动过它」，导入备份后重建页面的回填不会被误判成用户操作。
 *
 * ⚠ 不许给它设 `OnClickListener`：`Spinner.performClick()` 在 `super.performClick()` 返回 true 时
 * **跳过一次打开下拉**（AOSP 原文 `if (!handled) { … mPopup.show() }`），设了监听器下拉就弹不出来。
 */
class UserAwareSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.spinnerStyle,
) : Spinner(context, attrs, defStyleAttr) {

    /** 用户是否亲手操作过它（触摸 / 确认键 / 无障碍点击）；程序化回填不置位。 */
    var userInteracted: Boolean = false
        private set

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) userInteracted = true
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        userInteracted = true
        return super.performClick()
    }

    /** 同一实例内重复载入配置时复位（新建实例天然为 false）。 */
    fun resetUserInteracted() {
        userInteracted = false
    }
}
