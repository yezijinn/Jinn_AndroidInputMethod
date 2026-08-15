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

    /** 敏感内容策略：ClipboardStore.SensitivePolicy.value */
    var sensitivePolicy: Int
        get() = sp.getInt(KEY_SENSITIVE_POLICY, ClipboardStore.SensitivePolicy.NEVER_SAVE.value)
        set(value) = sp.edit { putInt(KEY_SENSITIVE_POLICY, value) }

    /** 敏感内容临时保存时长（秒枚举下标 → ClipboardStore.TempDuration） */
    var sensitiveTempSeconds: Int
        get() = sp.getInt(KEY_SENSITIVE_TEMP, ClipboardStore.TempDuration.S30.ordinal)
        set(value) = sp.edit { putInt(KEY_SENSITIVE_TEMP, value) }

    companion object {
        const val DEFAULT_MAX_ITEMS = 200

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_ITEMS = "max_items"
        private const val KEY_SENSITIVE_POLICY = "sensitive_policy"
        private const val KEY_SENSITIVE_TEMP = "sensitive_temp"

        @Volatile
        private var instance: ClipboardPrefs? = null

        fun of(context: Context): ClipboardPrefs =
            instance ?: synchronized(this) {
                instance ?: ClipboardPrefs(context).also { instance = it }
            }
    }
}
