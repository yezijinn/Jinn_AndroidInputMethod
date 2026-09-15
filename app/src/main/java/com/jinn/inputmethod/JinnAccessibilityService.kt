package com.jinn.inputmethod

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：被系统托管、常驻进程，是"防杀后台"的关键一环。
 *
 * 开启后从 [onServiceConnected] 拉起前台 [KeepAliveService]；服务被杀时
 * 系统重建本无障碍服务，从而再次拉起前台服务，形成互保。
 *
 * 真实辅助用途（避免被系统判定为"仅用于保活"而受限）：监测用户进入文本
 * 输入框（聊天 / 记事 / 搜索）时，确保语音输入服务常驻，让语音听写在输入
 * 场景下始终可用。仅依据控件类型做判断，不读取任何输入内容。
 */
class JinnAccessibilityService : AccessibilityService() {

    /** 保活拉起节流，避免每次文本变化都触发 startService */
    private var lastKeepAliveStart = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diagnostics.init(this)
        Diagnostics.i(TAG, "onServiceConnected: 无障碍服务已连接")
        if (Prefs(this).keepAlive) KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 先做廉价过滤：与输入框无关的事件直接丢弃，避免无谓地重读开关
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return
        }
        val cls = event.className?.toString().orEmpty()
        val isTextInput = cls.endsWith("EditText") ||
            cls.endsWith("AutoCompleteTextView") ||
            cls.endsWith("SearchView")
        if (!isTextInput) return

        val now = System.currentTimeMillis()
        if (now - lastKeepAliveStart <= KEEPALIVE_THROTTLE_MS) return
        lastKeepAliveStart = now

        // 节流窗口外**双向**重读开关：
        //  - 缓存为 true 而用户已关闭 → 不再错误拉起；
        //  - 缓存为 false 而用户刚开启 → 必须能感知到。
        // 后者是原先的缺陷：那时这里先 `if (!keepAliveCached) return` 提前退出，
        // 缓存一旦为 false 就永远不会重读，用户在设置页开启保活后若前台服务被杀，
        // 无障碍再也不会把它拉起来（互保失效）。
        val enabled = Prefs(this).keepAlive
        if (enabled) {
            Diagnostics.v(TAG, "onAccessibilityEvent: 检测到输入框 $cls，拉起保活")
            KeepAliveService.start(this)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Diagnostics.i(TAG, "onUnbind: 无障碍服务解绑")
        // 用户关闭无障碍 ≠ 关闭保活：KeepAliveService 由设置页的 keepAlive 开关控制，
        // 这里只在开关确实为关时才停掉它
        if (!Prefs(this).keepAlive) KeepAliveService.stop(this)
        return super.onUnbind(intent)
    }

    private companion object {
        const val KEEPALIVE_THROTTLE_MS = 5_000L
        const val TAG = "JinnAcc"
    }
}
