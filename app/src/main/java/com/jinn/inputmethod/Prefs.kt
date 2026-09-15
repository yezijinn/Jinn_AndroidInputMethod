package com.jinn.inputmethod

import android.content.Context
import androidx.core.content.edit

/**
 * 输入法默认启动模式（对齐 Prefs.defaultKeyboardMode 的取值）。
 */
object DefaultKeyboardMode {
    const val VOICE = 0
    const val PINYIN_CN = 1
    const val PINYIN_EN = 2
}

/**
 * 配置存储。只暴露真正需要用户改的项，其余走协议默认值。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("jinn_inputmethod", Context.MODE_PRIVATE)

    /** 飞牛 NAS 的局域网地址 */
    var host: String
        get() = sp.getString(KEY_HOST, DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST }
        set(value) = sp.edit { putString(KEY_HOST, value.trim()) }

    /** 服务端口，对齐 config_server.py 的 6016 */
    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit { putInt(KEY_PORT, value) }

    /** 固定 NAS 地址与端口：勾选后设置页编辑框变灰不可编辑，防误触乱改（默认勾选） */
    var lockServer: Boolean
        get() = sp.getBoolean(KEY_LOCK_SERVER, true)
        set(value) = sp.edit { putBoolean(KEY_LOCK_SERVER, value) }

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
     * 默认关闭：识别完成一次性提交更稳，避免长串兼容问题。
     */
    var useComposing: Boolean
        get() = sp.getBoolean(KEY_COMPOSING, false)
        set(value) = sp.edit { putBoolean(KEY_COMPOSING, value) }

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

    /**
     * 自动唤起键盘：true 编辑框聚焦时正常自动显示；false 永久禁止本输入法
     * 主动唤起（不受编辑框焦点、APP 切换、IME 生命周期重启影响），
     * 直到用户在设置页重新开启。拦截点在 [JinnIme.onShowInputRequested]。
     */
    var autoShowKeyboard: Boolean
        get() = sp.getBoolean(KEY_AUTO_SHOW_KB, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_SHOW_KB, value) }

    /**
     * 输入法启动时的默认键盘模式：
     *  - [DefaultKeyboardMode.VOICE] 语音键盘
     *  - [DefaultKeyboardMode.PINYIN_CN] 26 键全拼中文
     *  - [DefaultKeyboardMode.PINYIN_EN] 26 键英文
     *
     * 默认 26 键全拼中文（用户诉求：初始布局为 26 键全拼）。
     */
    var defaultKeyboardMode: Int
        get() = sp.getInt(KEY_DEFAULT_MODE, DefaultKeyboardMode.PINYIN_CN)
        set(value) = sp.edit { putInt(KEY_DEFAULT_MODE, value) }

    /**
     * 显示生僻字（默认关闭）。
     *
     * 关闭时**不加载**生僻字：单字表中《通用规范汉字表》三级及表外字（约占 60%）
     * 与含生僻字的词条（约占 17%）都不会进入内存，直接降低词库常驻占用。
     *
     * 词库在 IME 进程启动时加载，因此修改此开关需要重启输入法才生效
     * （设置页「保存配置并立即重启生效」按钮）。
     */
    var showRareChars: Boolean
        get() = sp.getBoolean(KEY_SHOW_RARE_CHARS, false)
        set(value) = sp.edit { putBoolean(KEY_SHOW_RARE_CHARS, value) }

    val wsUrl: String get() = "ws://$host:$port"

    companion object {
        const val DEFAULT_HOST = "192.168.1.3"
        const val DEFAULT_PORT = 6016
        const val DEFAULT_LANGUAGE = "auto"

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_LOCK_SERVER = "lock_server"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_STRIP_PUNC = "strip_punc"
        private const val KEY_COMPOSING = "composing"
        private const val KEY_SHUANGPIN = "shuangpin"
        private const val KEY_KB_ENGLISH = "kb_english"
        private const val KEY_AUTO_SHOW_KB = "auto_show_keyboard"
        private const val KEY_DEFAULT_MODE = "default_mode"
        private const val KEY_SHOW_RARE_CHARS = "show_rare_chars"
    }
}
