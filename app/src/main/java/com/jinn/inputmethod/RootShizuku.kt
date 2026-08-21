package com.jinn.inputmethod

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Root / Shizuku 壳层：用于"防杀后台"时把本应用加入系统白名单。
 *
 * 优先用 Shizuku（免 root、走 Shizuku 授权），未授权或不可用时退回 root 的 `su`。
 * 执行的命令均为将本包加入 Doze 白名单、允许后台运行，不触碰其他应用。
 *
 * 注意：Shizuku API 13 起 `Shizuku.newProcess` 被标记 deprecated 并改为 private，
 * 官方计划在 API 14 移除。这里用反射调用私有方法保持兼容；
 * 若未来版本删除该方法会抛 NoSuchMethodException，届时自动退回 su。
 */
object RootShizuku {

    const val SHIZUKU_REQUEST_CODE = 2048

    /** Shizuku 是否已可用（已安装且已授权） */
    fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 申请 Shizuku 授权；结果通过一次性回调返回 */
    fun requestShizukuPermission(cb: (granted: Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == SHIZUKU_REQUEST_CODE) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    cb(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }.onFailure {
            // 申请抛异常时同样要移除监听器，避免一次性 listener 泄漏常驻内存
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            cb(false)
        }
    }

    /** 防杀后台：把本包加入 Doze 白名单 + 允许后台运行。返回 true 表示全部命令成功 */
    fun applyKeepAlive(context: Context): Boolean {
        val pkg = context.packageName
        val commands = listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "am set-inactive $pkg false",
        )
        val useShizuku = shizukuReady()
        Diagnostics.i(TAG, "applyKeepAlive: pkg=$pkg 走${if (useShizuku) "Shizuku" else "su(root)"}")
        return if (useShizuku) {
            commands.all { runShizuku(it) }
        } else {
            commands.all { runSu(it) }
        }
    }

    /** 进程 waitFor 超时销毁并返回退出码；超时返回非 0 */
    private fun waitForOrKill(process: Process, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                if (System.currentTimeMillis() > deadline) {
                    runCatching { process.destroy() }
                    return -1
                }
                Thread.sleep(50)
            }
        }
    }

    private fun runShizuku(command: String): Boolean = runCatching {
        val process = shizukuNewProcess(arrayOf("sh", "-c", command), null, null)
        try {
            val code = waitForOrKill(process, PROCESS_TIMEOUT_MS)
            Diagnostics.i(TAG, "shizuku: `$command` → exit=$code")
            code == 0
        } finally {
            runCatching { process.destroy() }
        }
    }.onFailure { Diagnostics.e(TAG, "shizuku: `$command` 执行异常", it) }
        .getOrDefault(false)

    private fun runSu(command: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        try {
            val code = waitForOrKill(process, PROCESS_TIMEOUT_MS)
            Diagnostics.i(TAG, "su: `$command` → exit=$code")
            code == 0
        } finally {
            runCatching { process.destroy() }
        }
    }.onFailure { Diagnostics.e(TAG, "su: `$command` 执行异常", it) }
        .getOrDefault(false)

    /** 单条命令最长等待 5 秒，超时直接销毁进程并视为失败 */
    private const val PROCESS_TIMEOUT_MS = 5_000L

    private const val TAG = "RootShizuku"

    // ── 反射调用 Shizuku 私有 newProcess ─────────────────────
    // 签名：private static ShizukuRemoteProcess newProcess(String[] cmd, String[] envp, String dir)

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    private fun shizukuNewProcess(
        cmd: Array<String>,
        envp: Array<String>?,
        dir: String?,
    ): Process = newProcessMethod.invoke(null, cmd, envp, dir) as Process
}
