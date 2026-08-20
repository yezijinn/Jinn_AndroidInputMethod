package com.jinn.voiceinput

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 剪贴板统一的后台 IO 调度器（性能规范：主线程零阻塞）。
 *
 * 单线程串行执行数据库查询 / AES-GCM 解密 / 剪贴板保存等任务，
 * 避免零散 `Thread{}.start()` 的调度与内存开销，也避免多线程并发写库。
 * 主线程只负责提交 IO 任务与在回调里更新 UI 快照。
 */
object BackgroundIo {

    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jinn-clipboard-io").apply { isDaemon = true }
    }

    /** 提交一个后台任务；调度器关闭/拒绝时静默忽略（不抛异常） */
    fun run(task: () -> Unit) {
        try {
            exec.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }
}