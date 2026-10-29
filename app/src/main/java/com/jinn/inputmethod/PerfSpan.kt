package com.jinn.inputmethod

/**
 * 弹键盘 / 换输入框路径的分段计时（只服务排障，任何功能都不依赖它）。
 *
 * 为什么留在这条通道：这两条路径原先只有「整段回调」的上界 —— logcat 里
 * `onCreateInputView` 与 `onWindowShown` 两行 I 级日志的时间差 —— 拆不开是哪一段贵，
 * 也没法复跑（一次性手工计时每次都要人盯着秒表）。按段打一条 V 级日志后，
 * 「首次弹出有没有白付两遍 configure」「configure 占整段多少」都能用
 * `adb logcat -s JinnPerf` 复现。
 *
 * **零成本约定**（本项目的硬约束：日志不影响业务）：
 *  - 开关是 [Diagnostics.KEY_TRACE]（= `BuildConfig.DEBUG`）。release 包里 [start] 恒返回 null，
 *    调用点的 `?.mark` / `?.dump` 全部退化成一次空判断，字符串拼接与 logd 调用被编译器消除；
 *  - 只在**会话边界**（建视图 / 焦点进入 / 窗口显示）与**每次按键一次的候选刷新**上取时间，
 *    不在逐键的绘制、触摸回调里打点；
 *  - 日志走 V 级：只进 logcat，不进诊断包（[Diagnostics] 的 V 级默认不落盘）。
 */
internal class PerfSpan private constructor(private val label: String) {

    /** 上一次 mark 的时刻（首段 = 构造时刻） */
    private var last = System.nanoTime()

    /** 起点时刻 */
    private val start = last

    /** 各段耗时（微秒），形如 `皮肤=120us 透明度=340us ` */
    private val parts = StringBuilder(96)

    /** 记一段：自上一次 [mark]（或构造）以来的耗时，单位微秒 */
    fun mark(name: String) {
        val now = System.nanoTime()
        parts.append(name).append('=').append((now - last) / 1000L).append("us ")
        last = now
    }

    /** 自构造以来的总耗时（微秒） */
    fun totalUs(): Long = (System.nanoTime() - start) / 1000L

    /** 落一条 V 级日志：`标签 分段… 合计=Nus 附带信息` */
    fun dump(extra: String = "") {
        Diagnostics.v(TAG, "$label ${parts}合计=${totalUs()}us $extra")
    }

    companion object {
        /** 固定 TAG：`adb logcat -s JinnPerf` 一条命令能捞全（两个调用类共用） */
        const val TAG = "JinnPerf"

        /** release 返回 null ⇒ 调用点全部短路，无字符串构造与 logd 调用 */
        fun start(label: String): PerfSpan? = if (Diagnostics.KEY_TRACE) PerfSpan(label) else null

        /** 纳秒 → 毫秒（保留一位小数），只给日志用 */
        fun ms(nanos: Long): String = String.format(java.util.Locale.US, "%.1f", nanos / 1_000_000.0)
    }
}
