package com.jinn.voiceinput

import android.content.Context
import androidx.core.content.edit

/**
 * 剪贴板功能配置（方案第八 / 十五 / 三十七节）。
 * 与主 [Prefs] 分离，独立 SharedPreferences 文件，避免互相污染。
 */
class ClipboardPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("jinn_clipboard", Context.MODE_PRIVATE)

    /** 剪贴板历史总开关 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_ENABLED, value) }

    /** 历史数量上限：1 ~ 9999，默认 200 */
    var maxItems: Int
        get() = sp.getInt(KEY_MAX_ITEMS, DEFAULT_MAX_ITEMS)
        set(value) = sp.edit { putInt(KEY_MAX_ITEMS, value.coerceIn(1, 9999)) }

    /** 敏感内容策略：临时保存（默认 5 分钟，超时自动删除） */
    var sensitivePolicy: Int
        get() = sp.getInt(KEY_SENSITIVE_POLICY, ClipboardStore.SensitivePolicy.TEMPORARY.value)
        set(value) = sp.edit { putInt(KEY_SENSITIVE_POLICY, value) }

    /** 敏感内容临时保存时长：默认 5 分钟 */
    var sensitiveTempSeconds: Int
        get() = sp.getInt(KEY_SENSITIVE_TEMP, ClipboardStore.TempDuration.M5.ordinal)
        set(value) = sp.edit { putInt(KEY_SENSITIVE_TEMP, value) }

    // ── Root 增强模式 ──────────────────────────────────────

    /** Root 增强模式总开关（默认关，需用户主动开启） */
    var rootEnhanceEnabled: Boolean
        get() = sp.getBoolean(KEY_ROOT_ENHANCE, false)
        set(value) = sp.edit { putBoolean(KEY_ROOT_ENHANCE, value) }

    /** 敏感内容时自动清空系统剪贴板（root 下生效） */
    var rootClearOnSensitive: Boolean
        get() = sp.getBoolean(KEY_ROOT_CLEAR_SENSITIVE, true)
        set(value) = sp.edit { putBoolean(KEY_ROOT_CLEAR_SENSITIVE, value) }

    /** 定时清空系统剪贴板间隔分钟（0 = 不启用） */
    var rootClearIntervalMin: Int
        get() = sp.getInt(KEY_ROOT_CLEAR_INTERVAL, 0)
        set(value) = sp.edit { putInt(KEY_ROOT_CLEAR_INTERVAL, value.coerceIn(0, 60)) }

    companion object {
        const val DEFAULT_MAX_ITEMS = 200

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_ITEMS = "max_items"
        private const val KEY_SENSITIVE_POLICY = "sensitive_policy"
        private const val KEY_SENSITIVE_TEMP = "sensitive_temp"
        private const val KEY_ROOT_ENHANCE = "root_enhance"
        private const val KEY_ROOT_CLEAR_SENSITIVE = "root_clear_sensitive"
        private const val KEY_ROOT_CLEAR_INTERVAL = "root_clear_interval"

        @Volatile
        private var instance: ClipboardPrefs? = null

        fun of(context: Context): ClipboardPrefs =
            instance ?: synchronized(this) {
                instance ?: ClipboardPrefs(context).also { instance = it }
            }
    }
}
