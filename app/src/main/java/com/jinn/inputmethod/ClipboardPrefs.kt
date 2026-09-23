package com.jinn.inputmethod

import android.content.Context
import androidx.core.content.edit

/**
 * 剪贴板功能配置。
 * 与主 [Prefs] 分开存到独立 SharedPreferences 文件，互不干扰。
 */
class ClipboardPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("jinn_clipboard", Context.MODE_PRIVATE)

    /** 剪贴板历史总开关 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_ENABLED, value) }

    /** 历史数量上限：1 ~ 9999，默认 500 */
    var maxItems: Int
        get() {
            // 读侧同样钳位：存档里的 0 / 负数（旧版本写入、手动改 prefs）会让
            // trimByCount 直接 return，条数上限形同虚设（只剩 100MB 字节预算兜底）。
            // 写侧已经钳了，两侧口径必须一致，否则「读取的一定合法」这个前提是假的。
            val v = sp.getInt(KEY_MAX_ITEMS, DEFAULT_MAX_ITEMS)
            return v.coerceIn(1, 9999)
        }
        set(value) = sp.edit { putInt(KEY_MAX_ITEMS, value.coerceIn(1, 9999)) }

    /**
     * 等待已排队的 `apply()` 落盘（导入后要杀进程重启时调用）。
     *
     * `apply()` 是异步的，而杀进程不走任何收尾：不 flush 的话这个文件的改动可能回退，
     * 出现「主设置生效了、剪贴板设置没生效」的半套状态。
     */
    internal fun flush(): Boolean = runCatching { sp.edit().commit() }.getOrDefault(false)

    // ── 备份导出 / 导入（见 ConfigBackup / ConfigBackupManager） ──

    internal fun exportForBackup(): Map<String, ConfigBackup.BackupValue> {
        val out = LinkedHashMap<String, ConfigBackup.BackupValue>()
        ConfigBackup.BackupValue.of(enabled)?.let { out[KEY_ENABLED] = it }
        ConfigBackup.BackupValue.of(maxItems)?.let { out[KEY_MAX_ITEMS] = it }
        return out
    }

    /** 写入并返回成功应用的键数（越界值由 setter 钳位，与运行期口径一致） */
    internal fun importFromBackup(values: Map<String, ConfigBackup.BackupValue>): Int {
        var applied = 0
        for ((key, v) in values) {
            when (key) {
                KEY_ENABLED -> (v.value as? Boolean)?.let { enabled = it; applied++ }
                KEY_MAX_ITEMS -> (v.value as? Int)?.let { maxItems = it; applied++ }
            }
        }
        return applied
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 500

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_ITEMS = "max_items"

        @Volatile
        private var instance: ClipboardPrefs? = null

        fun of(context: Context): ClipboardPrefs =
            instance ?: synchronized(this) {
                instance ?: ClipboardPrefs(context).also { instance = it }
            }
    }
}
