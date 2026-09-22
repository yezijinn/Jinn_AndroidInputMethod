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

    // ── Root 增强模式 ──────────────────────────────────────

    /** Root 增强模式总开关（默认关，需用户主动开启） */
    var rootEnhanceEnabled: Boolean
        get() = sp.getBoolean(KEY_ROOT_ENHANCE, false)
        set(value) = sp.edit { putBoolean(KEY_ROOT_ENHANCE, value) }

    companion object {
        const val DEFAULT_MAX_ITEMS = 500

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_ITEMS = "max_items"
        private const val KEY_ROOT_ENHANCE = "root_enhance"

        @Volatile
        private var instance: ClipboardPrefs? = null

        fun of(context: Context): ClipboardPrefs =
            instance ?: synchronized(this) {
                instance ?: ClipboardPrefs(context).also { instance = it }
            }
    }
}
