package com.jinn.inputmethod

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * 屏幕翻译的设置页辅助：系统开关查询 / 跳转 / 状态文案 / 磁贴刷新。
 *
 * 全部方法都不抛异常（`runCatching` 收口）：设置页与磁贴都在「用户刚点完」的路径上，
 * 这里抛出去只会让设置页崩在无关的地方。
 */
internal object ScreenTranslateSettings {

    /**
     * 本服务是否已在系统「无障碍」里开启。
     *
     * 系统把已启用服务存成 `flattenToString()` 形态（`包名/类全名`），但个别 OEM / 旧版存
     * `flattenToShortString()`（`包名/.类简名`）⇒ 两种形态都要比；分隔符兼容冒号与分号
     * （部分 ROM 用 `;`），并忽略空白与大小写。
     */
    fun isSystemServiceEnabled(context: Context): Boolean {
        val cn = ComponentName(context, ScreenTranslateService::class.java)
        val full = cn.flattenToString()
        val short = cn.flattenToShortString()
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return false
        return raw.split(":", ";").any {
            val t = it.trim()
            t.equals(full, ignoreCase = true) || t.equals(short, ignoreCase = true)
        }
    }

    fun openSystemSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Diagnostics.w(TAG, "打开无障碍设置失败: ${it.javaClass.simpleName}") }
    }

    /**
     * 服务连接 / 解绑、应用内开关切换后调用，让磁贴的可用状态即时跟上。
     *
     * 磁贴进程与宿主不同（SystemUI 持有磁贴视图），只能请系统重新回调一次 `onStartListening`。
     */
    fun notifyTileStateChanged(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context.applicationContext, ScreenTranslateTile::class.java),
            )
        }.onFailure { Diagnostics.w(TAG, "刷新磁贴状态失败: ${it.javaClass.simpleName}") }
    }

    /** 设置页状态行文案（四态矩阵见方案 §10.3）。 */
    fun statusText(context: Context, enabled: Boolean): String = when {
        !enabled -> TEXT_OFF
        ScreenTranslateService.running != null -> TEXT_RUNNING
        isSystemServiceEnabled(context) -> TEXT_SYSTEM_ON_NO_CONN
        else -> TEXT_SYSTEM_OFF
    }

    private const val TAG = "JinnDiag"
    private const val TEXT_OFF = "功能已在应用内关闭"
    private const val TEXT_RUNNING = "服务运行中"
    private const val TEXT_SYSTEM_ON_NO_CONN = "系统已开启，服务未连接（关一次再开本开关，或重启应用即生效）"
    private const val TEXT_SYSTEM_OFF = "系统服务未开启"
}
