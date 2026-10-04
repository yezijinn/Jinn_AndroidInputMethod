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
            // 写侧已经钳了，两侧算法必须一致，否则「读取的一定合法」这个前提是假的。
            val v = sp.getInt(KEY_MAX_ITEMS, DEFAULT_MAX_ITEMS)
            return v.coerceIn(1, 9999)
        }
        set(value) = sp.edit { putInt(KEY_MAX_ITEMS, value.coerceIn(1, 9999)) }

    /**
     * 分组标签是否已按多标签规则重算过（2026-09-25 起；**运行态标记，不进备份**）。
     *
     * 重算本身幂等，但全库解密有成本，用标记保证只跑一次；失败不置位，下次启动自动重试。
     */
    var reclassified: Boolean
        get() = sp.getBoolean(KEY_RECLASSIFIED, false)
        set(value) = sp.edit { putBoolean(KEY_RECLASSIFIED, value) }

    /**
     * 等待已排队的 `apply()` 落盘（导入后要杀进程重启时调用）。
     *
     * `apply()` 是异步的，而杀进程不走任何收尾：不 flush 的话这个文件的改动可能回退，
     * 出现「主设置生效了、剪贴板设置没生效」的半套状态。
     */
    internal fun flush(): Boolean = runCatching { sp.edit().commit() }.getOrDefault(false)

    // ── 备份导出 / 导入（见 ConfigBackup / ConfigBackupManager） ──

    /**
     * 导出**进备份**的配置键：只含用户偏好（当前仅 [maxItems]）。
     *
     * 运行态标记（[reclassified]）与产品不变量（[enabled]）一律不进，
     * 否则一份包能把本机的采集开关或重算状态带偏。
     */
    internal fun exportForBackup(): Map<String, ConfigBackup.BackupValue> {
        val out = LinkedHashMap<String, ConfigBackup.BackupValue>()
        // ⚠ `enabled` **不进备份**（2026-10-03 修复 L-751）：它是产品不变量、不是用户偏好 ——
        // 设置页每次进页面都强制 `enabled = true`（历史功能没有独立开关）。而导入侧曾无条件写回，
        // 于是「覆盖还原」一份带 `enabled=false` 的包会**静默停止剪贴板采集**，且导入摘要与完成提示都不提它；
        // 更糟的是这个状态会在包与本机之间自我传播。与 [KEY_RECLASSIFIED] 同类，运行态不进备份。
        ConfigBackup.BackupValue.of(maxItems)?.let { out[KEY_MAX_ITEMS] = it }
        return out
    }

    /** 写入并返回成功应用的键数（越界值由 setter 钳位，与运行期一致） */
    internal fun importFromBackup(values: Map<String, ConfigBackup.BackupValue>): Int {
        var applied = 0
        for ((key, v) in values) {
            when (key) {
                KEY_MAX_ITEMS -> (v.value as? Int)?.let { maxItems = it; applied++ }
            }
        }
        return applied
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 500

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_ITEMS = "max_items"

        /**
         * 分组标签重算完成标记（运行态）：**不进备份**（见 [exportForBackup]），
         * 它描述的是「本机库是否已按当前规则重算」，导入到别的设备没有意义。
         */
        private const val KEY_RECLASSIFIED = "reclassified"

        @Volatile
        private var instance: ClipboardPrefs? = null

        fun of(context: Context): ClipboardPrefs =
            instance ?: synchronized(this) {
                instance ?: ClipboardPrefs(context).also { instance = it }
            }
    }
}
