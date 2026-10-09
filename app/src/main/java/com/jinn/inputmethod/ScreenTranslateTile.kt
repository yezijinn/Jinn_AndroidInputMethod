package com.jinn.inputmethod

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * 快捷磁贴：屏幕翻译的**唯一触发入口**。
 *
 * ⚠ 绝不起任何业务 Activity（那会把宿主切到后台；采集虽然按包名过滤不怕采到自己，但用户会看到
 * 界面被抢走）。唯一例外是 [ScreenTranslateTriggerActivity] —— 它透明、立即 finish，职责只是
 * **把快捷设置面板收起来**：磁贴点击时快捷遮罩（SystemUI 窗口）还在最上层，不收掉的话面板可能
 * 被压在遮罩下面（`startActivityAndCollapse` 是平台为这件事提供的唯一可靠 API）。
 */
internal class ScreenTranslateTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        if (!Prefs(this).screenTranslateEnabled) {
            toast(TEXT_TILE_APP_OFF)
            return
        }
        val intent = Intent(this, ScreenTranslateTriggerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 起 Intent 重载被废弃（会抛异常），改用 PendingIntent 重载
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure {
            Diagnostics.w(TAG, "磁贴触发失败: ${it.javaClass.simpleName}")
            toast(TEXT_TILE_UNAVAILABLE)
        }
    }

    /**
     * 磁贴状态：就绪 = 应用内开关开 **且** 服务已连接（系统「无障碍」已开启）。
     *
     * 二者缺一都置 `STATE_UNAVAILABLE`：磁贴点下去没反应比点下去给一句提示更让人困惑。
     */
    private fun refresh() {
        val tile = qsTile ?: return
        val ready = Prefs(this).screenTranslateEnabled && ScreenTranslateService.running != null
        tile.state = if (ready) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        runCatching { tile.updateTile() }
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
            .onFailure { Diagnostics.w(TAG, "磁贴 toast 失败: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = Diagnostics.TAG
        const val TEXT_TILE_APP_OFF = "屏幕翻译已在应用内关闭"
        const val TEXT_TILE_UNAVAILABLE = "屏幕翻译不可用：请先在系统「无障碍」里开启本服务"
    }
}
