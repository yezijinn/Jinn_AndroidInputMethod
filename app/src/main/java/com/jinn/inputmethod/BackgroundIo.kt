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
 * 注意：单线程串行，某个任务卡住会拖住后面所有 IO。
 */
object BackgroundIo {

    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jinn-clipboard-io").apply { isDaemon = true }
    }

    /** 提交一个后台任务；调度器关闭/拒绝时静默忽略（不抛异常） */
    fun run(task: () -> Unit) {
        val wrapped: () -> Unit = {
            runCatching(task).onFailure {
                Diagnostics.e(TAG, "后台任务异常: ${it.javaClass.simpleName} ${it.message}", it)
            }
        }
        try {
            exec.execute(wrapped)
        } catch (_: RejectedExecutionException) {
        }
    }

    private const val TAG = "BackgroundIo"
}