package com.capswriter.ime

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
class CapsWriterAccessibilityService : AccessibilityService() {

    /** 保活拉起节流，避免每次文本变化都触发 startService */
    private var lastKeepAliveStart = 0L

    /** 缓存保活开关：文本变化事件高频触发，避免每次都新建 Prefs 读 SharedPreferences */
    private var keepAliveCached = false
    private var prefsLoaded = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diagnostics.init(this)
        Diagnostics.i(TAG, "onServiceConnected: 无障碍服务已连接")
        keepAliveCached = Prefs(this).keepAlive
        prefsLoaded = true
        if (keepAliveCached) KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!prefsLoaded) {
            keepAliveCached = Prefs(this).keepAlive
            prefsLoaded = true
        }
        if (!keepAliveCached) return
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
        if (now - lastKeepAliveStart > KEEPALIVE_THROTTLE_MS) {
            lastKeepAliveStart = now
            Diagnostics.v(TAG, "onAccessibilityEvent: 检测到输入框 $cls，拉起保活")
            // 节流窗口外重读开关：否则用户在设置页关闭保活后，
            // 缓存值仍是 true，无障碍会一直错误地把保活拉起来
            keepAliveCached = Prefs(this).keepAlive
            if (keepAliveCached) KeepAliveService.start(this)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Diagnostics.i(TAG, "onUnbind: 无障碍服务解绑")
        // 用户关闭无障碍 ≠ 关闭保活：KeepAliveService 用户在设置页开启，
        // 其生命周期应由用户的 keepAlive 开关控制；reset 缓存避免下次重建后误拉起
        keepAliveCached = Prefs(this).keepAlive
        if (!keepAliveCached) KeepAliveService.stop(this)
        return super.onUnbind(intent)
    }

    private companion object {
        const val KEEPALIVE_THROTTLE_MS = 5_000L
        const val TAG = "CapsWriterAcc"
    }
}
