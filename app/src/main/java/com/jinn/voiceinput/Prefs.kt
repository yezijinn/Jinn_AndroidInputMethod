package com.jinn.voiceinput

import android.content.Context
import androidx.core.content.edit

/**
 * 配置存储。只暴露真正需要用户改的项，其余走协议默认值。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("jinn_voiceinput", Context.MODE_PRIVATE)

    /** 飞牛 NAS 的局域网地址 */
    var host: String
        get() = sp.getString(KEY_HOST, DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST }
        set(value) = sp.edit { putString(KEY_HOST, value.trim()) }

    /** 服务端口，对齐 config_server.py 的 6016 */
    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit { putInt(KEY_PORT, value) }

    /** 统一语言代码，取值见服务端 engines/language.py */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE).orEmpty().ifBlank { DEFAULT_LANGUAGE }
        set(value) = sp.edit { putString(KEY_LANGUAGE, value) }

    /** 识别提示词，人名/地名/术语写在这里能提升准确率 */
    var prompt: String
        get() = sp.getString(KEY_PROMPT, "").orEmpty()
        set(value) = sp.edit { putString(KEY_PROMPT, value) }

    /** 去掉句尾逗号句号，对齐桌面端 trash_punc 的习惯 */
    var stripTrailingPunc: Boolean
        get() = sp.getBoolean(KEY_STRIP_PUNC, true)
        set(value) = sp.edit { putBoolean(KEY_STRIP_PUNC, value) }

    /**
     * 用预编辑（composing）文本实时回显。
     * 少数输入框对长预编辑串兼容不好，关掉后改为识别完成一次性提交。
     */
    var useComposing: Boolean
        get() = sp.getBoolean(KEY_COMPOSING, true)
        set(value) = sp.edit { putBoolean(KEY_COMPOSING, value) }

    /**
     * 后台保活总开关：开启后启动前台服务与无障碍互保，并在开机后自动拉起。
     */
    var keepAlive: Boolean
        get() = sp.getBoolean(KEY_KEEP_ALIVE, false)
        set(value) = sp.edit { putBoolean(KEY_KEEP_ALIVE, value) }

    /**
     * 是否用 Root / Shizuku 把本包加入系统白名单（防杀后台的进阶手段）。
     * 仅记录用户偏好，实际执行在设置页点按钮时触发。
     */
    var useRootShizuku: Boolean
        get() = sp.getBoolean(KEY_ROOT_SHIZUKU, false)
        set(value) = sp.edit { putBoolean(KEY_ROOT_SHIZUKU, value) }

    /** 保活通知优先级：true 用 IMPORTANCE_LOW（可见但不响），false 用 IMPORTANCE_MIN（更省电但部分厂商易杀） */
    var notifyHighPriority: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_HIGH, true)
        set(value) = sp.edit { putBoolean(KEY_NOTIFY_HIGH, value) }

    /**
     * 键盘输入方案：true 用自然码双拼，false 用 26 键全拼。
     */
    var useShuangpin: Boolean
        get() = sp.getBoolean(KEY_SHUANGPIN, false)
        set(value) = sp.edit { putBoolean(KEY_SHUANGPIN, value) }

    /** 键盘是否默认英文模式（字母直通，不查候选） */
    var keyboardEnglish: Boolean
        get() = sp.getBoolean(KEY_KB_ENGLISH, false)
        set(value) = sp.edit { putBoolean(KEY_KB_ENGLISH, value) }

    val wsUrl: String get() = "ws://$host:$port"

    companion object {
        const val DEFAULT_HOST = "192.168.1.3"
        const val DEFAULT_PORT = 6016
        const val DEFAULT_LANGUAGE = "auto"

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_STRIP_PUNC = "strip_punc"
        private const val KEY_COMPOSING = "composing"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_ROOT_SHIZUKU = "root_shizuku"
        private const val KEY_NOTIFY_HIGH = "notify_high"
        private const val KEY_SHUANGPIN = "shuangpin"
        private const val KEY_KB_ENGLISH = "kb_english"
    }
}
