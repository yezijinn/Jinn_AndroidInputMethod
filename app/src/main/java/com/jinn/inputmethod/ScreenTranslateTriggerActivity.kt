package com.jinn.inputmethod

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 磁贴触发的中转页：**唯一职责**是把快捷设置面板收起来（见 [ScreenTranslateTile] 的说明），
 * 随后把自己收掉，再把采集请求转交服务。透明、无 UI、`noHistory`、不入最近任务。
 *
 * ⚠ **必须先 finish 再采集**（2026-10-10 真机实测）：
 * 无障碍的窗口列表（`AccessibilityService.getWindows()`）在本机只给出「顶层窗口 + 系统窗口」——
 * 本页只要还在最前面，列表里就只有 `SystemUI` 与本页自己，宿主应用的窗口**根本不出现**，
 * 采集必然报「没有可读的应用窗口」。所以顺序是：`finish()` → 等窗口真正撤掉 → 采集。
 *
 * 两个等待合并为一个常量：快捷面板收起动画 + 本页窗口撤除（两者并行发生）。
 */
internal class ScreenTranslateTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!ScreenTranslateService.requestCapture()) {
                // 服务未连接（系统「无障碍」里没开）：本页已不可见，用应用上下文的 toast
                Toast.makeText(applicationContext, TEXT_NEED_A11Y, Toast.LENGTH_LONG).show()
            }
        }, TRIGGER_SETTLE_MS)
    }

    private companion object {
        /** 等「快捷面板收起」+「本页窗口撤除」；真机上若仍报读不到窗口，先调大这个值 */
        const val TRIGGER_SETTLE_MS = 260L
        const val TEXT_NEED_A11Y = "屏幕翻译未启用：请先在系统「无障碍」里开启本服务"
    }
}
