package com.jinn.voiceinput

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * 前台保活服务：常驻通知把进程优先级抬到前台，降低被系统回收的概率，
 * 让输入法到飞牛服务端的 WebSocket 连接更稳定（防掉后台）。
 *
 * 配合 [JinnAccessibilityService] 使用：无障碍服务被系统托管，
 * 在其 onServiceConnected 里拉起本服务，服务被杀时由无障碍服务再次拉起。
 */
class KeepAliveService : android.app.Service() {

    companion object {
        private const val CHANNEL_ID = "jinn_keepalive"
        private const val NOTIFY_ID = 1001
        private const val TAG = "KeepAliveService"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            Diagnostics.i(TAG, "start: 请求启动前台保活服务")
            // Android 12+ 后台启动前台服务受限，当前 IME 有豁免但用户切换后失效；
            // 统一在此 try-catch，避免 ForegroundServiceStartNotAllowedException 致崩
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "start 失败: ${it.message}")
                Diagnostics.e(TAG, "start 失败: ${it.message}")
            }
        }

        fun stop(context: Context) {
            Diagnostics.i(TAG, "stop: 请求停止保活服务")
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
        Diagnostics.i(TAG, "onCreate: 前台保活服务创建")
        createChannel()
        // Android 14 targetSdk 34 起推荐显式传入 foregroundServiceType；
        // ServiceCompat 这种重载会按最低 API 选取合适的方式，且对老旧机型零影响
        ServiceCompat.startForeground(
            this, NOTIFY_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diagnostics.i(TAG, "onStartCommand: flags=$flags startId=$startId")
        // 设置页切换通知优先级时 stop+start 可能复用本实例（onCreate 不重跑），
        // 这里每次补一次 createChannel 保证通道按最新优先级生效（幂等，已存在则跳过）
        createChannel()
        // 每次 onStartCommand 也补一次 startForeground：Android 14 文档要求
        // 在调用 startForegroundService 后的 5s 内必须 startForeground，
        // 有时系统会延迟回调 onStartCommand，重复调用是幂等的
        ServiceCompat.startForeground(
            this, NOTIFY_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // START_STICKY：进程被回收后系统会尝试重建并重新 onStartCommand
        return android.app.Service.START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diagnostics.i(TAG, "onTaskRemoved: 用户划掉任务，keepAlive=${Prefs(applicationContext).keepAlive}")
        // 用户在最近任务里划掉本应用：若仍开启保活，重建前台服务。
        // Android 12+ 后台启动前台服务受限，当前输入法虽有豁免，
        // 但用户切换到其他 IME 后豁免失效，这里必须 try-catch，否则
        // 抛 ForegroundServiceStartNotAllowedException 会让应用崩溃
        if (Prefs(applicationContext).keepAlive) {
            runCatching { start(applicationContext) }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val wantHigh = Prefs(applicationContext).notifyHighPriority
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null && existing.importance != expectedImportance(wantHigh)) {
                // 用户切换了优先级，删旧通道重建以生效
                manager.deleteNotificationChannel(CHANNEL_ID)
            }
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keepalive_channel),
                    expectedImportance(wantHigh),
                ).apply {
                    setShowBadge(false)
                    setBypassDnd(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun expectedImportance(high: Boolean): Int =
        if (high) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN

    private fun buildNotification(): Notification {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keepalive_notice))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
