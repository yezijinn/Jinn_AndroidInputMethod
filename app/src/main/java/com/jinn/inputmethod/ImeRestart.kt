package com.jinn.inputmethod

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 「保存并重启输入法进程」的唯一实现（2026-11-01 由 `SettingsActivity` 抽出，设置页与语音设置页共用）。
 *
 * 语义与抽取前**逐条一致**（`BUG.md` L-1420 / L-1492 / L-1319）：
 *
 *  - `apply()` 是异步的，只靠延时 800ms 赌它写完，IO 压力大时会丢最后一批键 ⇒ 先 `flush()` 确认落盘；
 *  - 用户学习的两份缓冲（词频 2s 防抖 / 台账写线程）只在内存里排队，`killProcess` 不走 `onDestroy`
 *    ⇒ 一并刷（刷不动只留日志、照常重启：重刷失败不该把「保存并重启」卡死）；
 *  - 配置落盘失败 ⇒ **不重启**（否则是确定性地丢这批设置），由调用方把那句失败文案写进自己的状态行。
 */
internal object ImeRestart {

    private const val TAG = "ImeRestart"

    /** 杀进程前的等待：留出 SharedPreferences 真正落盘的时间（刷过之后仍留余量） */
    private const val KILL_DELAY_MS = 800L

    /**
     * 重启前的落盘失败（`BUG.md` L-1420）。
     *
     * 此刻界面刚写过「正在重启」，再杀进程就是确定性丢设置，所以这条**同时**进状态行与
     * Toast：只写日志的话用户只看到「没生效」，无从知道是磁盘问题。两页取同一句。
     */
    const val TEXT_RESTART_FLUSH_FAILED = "写入失败，设置未保存，已取消重启"

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 先把配置写实、再延迟杀进程：系统随后会自动重建 IME 服务。
     *
     * @param onFlushFailed 落盘失败时的回退：把 [TEXT_RESTART_FLUSH_FAILED] 写进调用方自己的状态行。
     *   在调用方主线程执行（本函数本身就在主线程被调用）。
     */
    fun restart(activity: Activity, onFlushFailed: (String) -> Unit) {
        Diagnostics.i(TAG, "restart: 重启输入法进程")
        // ⚠ 下面两行在主线程**同步**等：`SelectionLedger.flush()` 最坏吃满 `FLUSH_TIMEOUT_MS`（2000ms），
        // 点「保存并重启」后这段空窗可达 2s —— 低于 ANR 阈值 5s，且随后必然重启，别当新缺陷重报。
        val prefsFlushed = runCatching { Prefs(activity).flush() }.getOrDefault(false)
        val clipboardFlushed = runCatching { ClipboardPrefs.of(activity).flush() }.getOrDefault(false)
        val freqFlushed = runCatching { UserFrequency.flush() }.getOrDefault(false)
        val ledgerFlushed = runCatching { SelectionLedger.flush() }.getOrDefault(false)
        if (!freqFlushed || !ledgerFlushed) {
            // 与词库页同口径：刷不动只留日志、照常重启
            Diagnostics.w(TAG, "restart: 学习缓冲未刷完 freq=$freqFlushed ledger=$ledgerFlushed")
        }
        if (!prefsFlushed || !clipboardFlushed) {
            Diagnostics.w(
                TAG,
                "restart: 落盘失败，已取消重启 prefs=$prefsFlushed clipboard=$clipboardFlushed",
            )
            // 状态行先撤掉「正在重启」（调用方在调本函数之前就写了那句），
            // 否则界面同时存在「正在重启」与「已取消」两个相反的说法
            onFlushFailed(TEXT_RESTART_FLUSH_FAILED)
            Toast.makeText(activity, TEXT_RESTART_FLUSH_FAILED, Toast.LENGTH_LONG).show()
            return
        }
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, KILL_DELAY_MS)
    }
}
