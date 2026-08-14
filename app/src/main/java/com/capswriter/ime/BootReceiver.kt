package com.capswriter.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后拉起保活服务，让"防掉后台"在重启后依然生效。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Diagnostics.init(context)
        val action = intent.action ?: return
        Diagnostics.i(TAG, "onReceive: action=$action keepAlive=${Prefs(context).keepAlive}")
        val restart = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (restart && Prefs(context).keepAlive) {
            KeepAliveService.start(context)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
