package com.jinn.inputmethod

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 剪贴板统一的后台 IO 调度器，所有 IO 跑在单个后台线程，主线程不阻塞。
 *
 * - 串行执行数据库查询 / AES-GCM 解密 / 剪贴板保存等任务，省去零散
 *   `Thread{}.start()` 的调度与内存开销，也避免多线程并发写库
 * - 主线程只负责提交任务，结果在回调里更新 UI 快照
 * - 任务抛出的异常一律记录：静默吞掉会让「剪贴板没保存」「搜索没结果」
 *   这类问题在日志里完全不留痕迹，事后无从查起
 *
 * 单线程串行，某个任务卡住会拖住后面所有 IO。
 */
object BackgroundIo {

    private const val TAG = "BackgroundIo"

    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jinn-clipboard-io").apply { isDaemon = true }
    }

    /**
     * 长活池（跨批契约 C-1）：图片整份拷贝 / 草稿分块 / SAF 列目录这类**秒级**任务。
     *
     * 与 [exec] 分开的理由：单线程队列是 FIFO，一个几秒的大活排在前头，后面的交互短活
     * （剪贴板保存、面板首屏查询）就要一起等 —— 用户看到的是「复制了没反应、面板白屏」。
     * 长活另开一条线程后，短活队列不再被长活推后。
     *
     * 三条约束：
     *  - **单线程**：投进来的任务仍按提交顺序串行（草稿分块靠这一点保序，不能换多线程池）；
     *  - **后台优先级**：长活不该与前台输入抢 CPU（与 [exec] 不同，[exec] 跑的是交互短活，
     *    降优先级等于把延迟推给用户）；
     *  - 守护线程：随进程退出，不需要显式 shutdown。
     */
    private val longExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jinn-long-io").apply {
            isDaemon = true
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
    }

    /** 提交一个后台任务（交互短活，默认优先级）；调度器关闭/拒绝时静默忽略（不抛异常） */
    fun run(task: () -> Unit) {
        try {
            exec.execute(wrapped(task))
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * 提交一个**秒级长任务**（契约 C-1，见 [longExec] 注释）。
     *
     * 只投三类活：图片整份拷贝、草稿分块写入、SAF 列目录。**不要**把剪贴板 DB / 解密 /
     * 面板查询这类交互短活投进来 ——「哪些算长活」写错会把同一类活拆到两条队列上互相等。
     */
    fun runLong(task: () -> Unit) {
        try {
            longExec.execute(wrapped(task))
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * 统一的后台线程入口（契约 C-4）：建线程 + 命名 + 后台优先级 + 守护。
     *
     * 解决两个既有问题：裸 `Thread { }` 没有名字，诊断行头的 `[Thread-n]` 无法对应到功能；
     * `setThreadPriority` 在 19 处各写一遍两行，漏一处就变成与输入抢 CPU。
     * **不强制**迁移既有线程（按批次归属慢慢来），新线程一律走这里。
     *
     * 异常处理与 [run] 同口径：记一条 E 级日志，不让异常落到未捕获处理器。
     */
    fun backgroundThread(name: String, block: () -> Unit) {
        Thread(
            {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                wrapped(block).invoke()
            },
            name,
        ).apply { isDaemon = true }.start()
    }

    /** 统一的异常兜底：任务抛出的异常一律记录（静默吞掉会让问题在日志里查无实据） */
    private fun wrapped(task: () -> Unit): () -> Unit = {
        runCatching(task).onFailure {
            Diagnostics.e(TAG, "后台任务异常: ${it.javaClass.simpleName} ${it.message}", it)
        }
    }
}
