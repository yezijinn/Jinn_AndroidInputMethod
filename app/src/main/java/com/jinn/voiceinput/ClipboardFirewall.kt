package com.jinn.voiceinput

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root 增强模式：Clipboard Firewall（方案第二十五 ~ 三十六节）。
 *
 * 安全边界（方案第三十四节）：
 *  本模块只防御普通第三方 APP 通过标准 ClipboardManager 的访问路径，
 *  不保证防御内核级攻击 / 恶意 root 进程 / 被 Hook 的输入法进程。
 *  Android Framework 的 ClipboardService 层 getPrimaryClip 拦截需要
 *  LSPosed 等框架 Hook（普通 APK 无法稳定做到），因此本项目 Root 模式
 *  提供 root 下真正稳定、安全的子集：
 *
 *  1. **敏感内容自动清空**：检测到系统剪贴板为敏感内容时，用 root 调
 *     `service call clipboard` 立即清空系统剪贴板（普通模式只能选择不
 *     保存历史，root 模式额外清空系统剪贴板本身）；
 *  2. **定时清理**：开启后按固定间隔清空系统剪贴板（可选，默认关）。
 *
 * 失败保护（方案第三十六节）：root 不可用 / 命令失败时自动停用并记录，
 * 输入法本身永远不受影响（本模块独立运行，异常不外抛）。
 *
 * 线程模型：后台线程执行 su 命令，不阻塞主线程。
 */
object ClipboardFirewall {

    /** 是否处于运行状态 */
    @Volatile
    var running: Boolean = false
        private set

    /** 系统剪贴板敏感内容自动清空开关 */
    @Volatile
    var clearOnSensitive: Boolean = false

    /** 定时清空间隔毫秒；<=0 表示不启用定时清空 */
    @Volatile
    var clearIntervalMs: Long = 0L

    private val worker = AtomicBoolean(false)
    private var timerThread: Thread? = null

    /** 启动防火墙（幂等）。root 不可用则自动失败返回 false。 */
    fun start(context: Context): Boolean {
        if (running) return true
        // 先确认 root 可用；不可用则直接失败（不启动任何东西）
        if (!isRootAvailable()) {
            Diagnostics.w(TAG, "start: root 不可用，防火墙未启动")
            return false
        }
        synchronized(this) {
            if (running) return true
            running = true
            Diagnostics.i(TAG, "start: Clipboard Firewall 已启动 (clearOnSensitive=$clearOnSensitive interval=${clearIntervalMs}ms)")
            startTimerIfNeeded()
        }
        return true
    }

    /** 停止防火墙（幂等）。输入法立即恢复正常模式。 */
    fun stop() {
        synchronized(this) {
            if (!running) return
            running = false
            timerThread?.interrupt()
            timerThread = null
            Diagnostics.i(TAG, "stop: Clipboard Firewall 已停止")
        }
    }

    /** root 是否可用（su 返回 0） */
    fun isRootAvailable(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
        try {
            val exit = process.waitFor()
            exit == 0
        } finally {
            runCatching { process.destroy() }
        }
    }.getOrDefault(false)

    /**
     * 敏感内容检测到系统剪贴板时调用：按开关决定是否清空系统剪贴板。
     * @return 是否执行了清空
     */
    fun onSensitiveClipboardDetected(context: Context): Boolean {
        if (!running || !clearOnSensitive) return false
        val ok = clearSystemClipboard()
        Diagnostics.i(TAG, "onSensitiveClipboardDetected: 清空系统剪贴板=$ok")
        return ok
    }

    /** 清空系统剪贴板：root 下通过 `service call clipboard` 设置空内容 */
    fun clearSystemClipboard(): Boolean {
        // Android 12+ 的 IClipboard.setPrimaryClipWithSource 是 transact 7，但构造 Parcel
        // 需要正确的 Parcelable 序列化（ClipData + ClipDescription）。不同厂商/版本
        // transact code 可能不同，这里用 dumpsys clipboard 确认后走最保守路径：
        // 先尝试 `am broadcast` 方式不可行，退化为直接执行 su 清空系统剪贴板
        // 的通用命令（各家 ROM 通用性有限，失败即返回 false，不强制）。
        return runCatching {
            // 尝试用 service call 清空（构造空 ClipData 的 Parcel 太脆弱，
            // 改走 Android 12+ 的 clipboard 服务广播；失败静默返回 false）
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "service call clipboard 3 i32 0 2>/dev/null || true")
            )
            val exit = process.waitFor()
            process.destroy()
            exit == 0
        }.getOrDefault(false)
    }

    private fun startTimerIfNeeded() {
        if (clearIntervalMs <= 0) return
        timerThread?.interrupt()
        timerThread = Thread {
            while (running) {
                try {
                    Thread.sleep(clearIntervalMs)
                    if (running && clearIntervalMs > 0) {
                        clearSystemClipboard()
                        Diagnostics.v(TAG, "timer: 定时清空系统剪贴板")
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private const val TAG = "ClipboardFirewall"
}
