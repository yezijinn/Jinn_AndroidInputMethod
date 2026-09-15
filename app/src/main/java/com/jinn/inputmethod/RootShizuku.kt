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
        // 逐条执行完再汇总：原先写成 `commands.all { ... }`，会在第一条失败时短路，
        // 后面几条白名单命令一条都不执行。这几条彼此独立（Doze 白名单 / 两个 appops /
        // set-inactive），部分成功也比一条不做有价值。
        val results = commands.map { if (useShizuku) runShizuku(it) else runSu(it) }
        val okCount = results.count { it }
        Diagnostics.i(TAG, "applyKeepAlive: 成功 $okCount/${results.size} 条")
        return okCount == results.size
    }

    /**
     * 等待子进程结束并返回退出码；超时返回 -1 并销毁进程。
     *
     * 两处必要防护：
     *  - **排空 stdout / stderr**：只等退出码、不读输出时，子进程写满管道缓冲区就会
     *    阻塞，永远结束不了。本次几条命令输出虽少，但与 ClipboardFirewall 保持同一标准，
     *    避免将来加命令时踩坑。
     *  - **超时销毁**：`dumpsys` 在系统繁忙时可能长时间不返回，不能无限等待。
     *
     * 用 `Process.waitFor(timeout, unit)` 替代原先的轮询 + sleep（minSdk 26 已支持）。
     */
    private fun execAndWait(process: Process): Int = try {
        drainAsync(process)
        if (process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            Diagnostics.w(TAG, "命令执行超时（${PROCESS_TIMEOUT_MS}ms），已销毁进程")
            -1
        }
    } finally {
        runCatching { process.destroy() }
    }

    /** 后台排空子进程输出流，避免管道写满导致子进程阻塞 */
    private fun drainAsync(process: Process) {
        for (stream in listOf(process.inputStream, process.errorStream)) {
            Thread {
                runCatching { stream.bufferedReader().forEachLine { } }
            }.apply { isDaemon = true; start() }
        }
    }

    private fun runShizuku(command: String): Boolean = runCatching {
        val process = shizukuNewProcess(arrayOf("sh", "-c", command), null, null)
        val code = execAndWait(process)   // 内部已保证最终 destroy
        Diagnostics.i(TAG, "shizuku: `$command` → exit=$code")
        code == 0
    }.onFailure { Diagnostics.e(TAG, "shizuku: `$command` 执行异常", it) }
        .getOrDefault(false)

    private fun runSu(command: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val code = execAndWait(process)   // 内部已保证最终 destroy
        Diagnostics.i(TAG, "su: `$command` → exit=$code")
        code == 0
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
