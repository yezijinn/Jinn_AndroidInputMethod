package com.jinn.inputmethod

import android.content.Context
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    /**
     * 飞牛 NAS 的局域网地址。
     *
     * getter 做一次合法性兜底：存档里的非法值（旧版本写入、手动改 prefs）一律回落到默认地址。
     * 这是崩不崩的边界，[wsUrl] 会被直接送进 OkHttp，而 `HttpUrl` 对含空格或重复端口的
     * host 会抛 `IllegalArgumentException`，调用点又都在主线程（整个 IME 会崩）。
     * 语音链路属禁改区，所以校验放在配置层，保证交出去的 URL 一定是合法形式。
     */
    var host: String
        get() = normalizeHost(sp.getString(KEY_HOST, DEFAULT_HOST).orEmpty()) ?: DEFAULT_HOST
        set(value) = sp.edit { putString(KEY_HOST, value.trim()) }

    /**
     * 服务端口，对齐 config_server.py 的 6016。
     * getter 钳到合法端口区间：越界端口同样会让 `HttpUrl` 抛异常。
     */
    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in 1..65535 } ?: DEFAULT_PORT
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
     * 是否启用双拼。
     *
     * 2026-09-20 起由设置页「输入方案」下拉写（选全拼 = false / 选某套双拼 = true；
     * 按键面板的「全拼 / 双拼」按钮已按用户要求移除）。老版本即用此键，故无需迁移。
     */
    var useShuangpin: Boolean
        get() = sp.getBoolean(KEY_SHUANGPIN, false)
        set(value) = sp.edit { putBoolean(KEY_SHUANGPIN, value) }

    /**
     * 选定的双拼方案，取值见 [ShuangpinScheme]（1 自然码 … 7 加加，不存 0）。
     *
     * 只在设置页「输入方案」下拉里改（选双拼项时写入；面板已无方案切换入口）。
     * 选全拼不清除本键：下次选回双拼时回到上次那套，不会丢用户的选择。
     */
    var shuangpinScheme: Int
        get() {
            val v = sp.getInt(KEY_SHUANGPIN_SCHEME, ShuangpinScheme.ZIRANMA.prefsValue)
            // 历史/异常值（含 0 全拼、未知编号）一律落到自然码：本键的语义就是「双拼用哪套」。
            // 回写的必须是 of() 规范化之后的取值：of() 对未知编号会兜底成自然码，
            // 此时原值 v 仍是个脏编号，判据成立却把脏值原样返回，与上面的说明不符。
            val scheme = ShuangpinScheme.of(v)
            return if (scheme.isShuangpin) scheme.prefsValue else ShuangpinScheme.ZIRANMA.prefsValue
        }
        set(value) = sp.edit { putInt(KEY_SHUANGPIN_SCHEME, value) }

    /** 当前生效的输入方案：没启用双拼就是全拼，启用则取设置页选定的那套 */
    val effectiveShuangpinScheme: ShuangpinScheme
        get() = if (useShuangpin) ShuangpinScheme.of(shuangpinScheme) else ShuangpinScheme.QUANPIN

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
        get() {
            // 越界值（旧版本/外部写入）会让 JinnIme 建视图时的 when 落到 else 分支，
            // 默认键盘变成语音键盘，与「默认 26 键全拼」的预期正好相反。
            val v = sp.getInt(KEY_DEFAULT_MODE, DefaultKeyboardMode.PINYIN_CN)
            return v.takeIf { it in DefaultKeyboardMode.VOICE..DefaultKeyboardMode.PINYIN_EN }
                ?: DefaultKeyboardMode.PINYIN_CN
        }
        set(value) = sp.edit {
            putInt(KEY_DEFAULT_MODE, value.coerceIn(DefaultKeyboardMode.VOICE, DefaultKeyboardMode.PINYIN_EN))
        }

    /**
     * 显示生僻字（默认关闭）。
     *
     * 关闭时按《通用规范汉字表》过滤：候选里不出现三级字、表外字，也不出现含生僻字的词条。
     * 过滤在查询期做（`phrasesFor`），词库与索引原样加载，不因开关变化重建。
     *
     * 开关在加载时读取，改完要重启输入法才生效（设置页「保存配置并立即重启生效」）。
     */
    var showRareChars: Boolean
        get() = sp.getBoolean(KEY_SHOW_RARE_CHARS, false)
        set(value) = sp.edit { putBoolean(KEY_SHOW_RARE_CHARS, value) }

    /**
     * 模糊音容错：按 [FuzzyPinyin] 的分组位掩码存，**默认 [FuzzyPinyin.NONE]（关闭）**。
     *
     * 默认关是刻意选择：打开才派生变体候选，关闭时查询结果与历史逐候选一致。
     * 读写两端都过 [FuzzyPinyin.clampMask]：导入的备份里可能带未定义的位。
     */
    var fuzzyPinyinMask: Int
        get() = FuzzyPinyin.clampMask(sp.getInt(KEY_FUZZY_PINYIN, FuzzyPinyin.NONE))
        set(value) = sp.edit { putInt(KEY_FUZZY_PINYIN, FuzzyPinyin.clampMask(value)) }

    /** 用户词频学习：记录「实际选过」的候选并提到前面（默认关；本地存储，不上传） */
    var userLearning: Boolean
        get() = sp.getBoolean(KEY_USER_LEARNING, false)
        set(value) = sp.edit { putBoolean(KEY_USER_LEARNING, value) }

    /**
     * 候选的智能预测词：选完一个词后，在候选栏预告下一个字词（默认关）。
     * 打开后选完文字才会出现预测候选；关掉时完全不产生预测。
     */
    var predictEnabled: Boolean
        get() = sp.getBoolean(KEY_PREDICT_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_PREDICT_ENABLED, value) }

    /**
     * 候选词行数：1 = 单行横向（默认），2 = 双行（上排偶数项、下排奇数项，上下成列）。
     *
     * 只影响候选栏的结构与高度（见 [CandidateRows]），不改候选顺序：
     * 空格 / 回车仍取第 1 个候选（= 下排首项）。越界值一律落回 1 行。
     */
    var candidateRows: Int
        get() = sp.getInt(KEY_CANDIDATE_ROWS, CandidateRows.SINGLE).takeIf { it in CandidateRows.SINGLE..CandidateRows.DOUBLE }
            ?: CandidateRows.SINGLE
        set(value) = sp.edit { putInt(KEY_CANDIDATE_ROWS, value.coerceIn(CandidateRows.SINGLE, CandidateRows.DOUBLE)) }

    /**
     * 键面韵母提示（仅双拼有意义）：字母键下方显示各键对应的韵母 / 声母小字，**默认开**（保持历史观感）。
     *
     * 关闭后键面只显示字母，字母改为铺满居中（见 [KeyHint]）。与候选词行数一样，
     * 键盘下次弹出即按新设置渲染，不必重启输入法。
     */
    var showKeyHint: Boolean
        get() = sp.getBoolean(KEY_SHOW_KEY_HINT, true)
        set(value) = sp.edit { putBoolean(KEY_SHOW_KEY_HINT, value) }

    /**
     * 上次「检查更新」成功的时刻（epoch ms，0 = 从未成功检查过）。
     *
     * 只由设置页在拿到有效结果（有更新 / 已最新）时写入：失败不写，下次打开设置页仍会静默重试；
     * 成功则 [UpdateChecker.AUTO_CHECK_INTERVAL_MS] 内不再自动检查，避免反复打扰。
     */
    var updateLastCheckAt: Long
        get() = sp.getLong(KEY_UPDATE_LAST_CHECK_AT, 0L)
        set(value) = sp.edit { putLong(KEY_UPDATE_LAST_CHECK_AT, value) }

    /**
     * 主题模式（见 [ThemeManager]）：0 跟随系统 / 1 亮白 / 2 暗黑 / 3 定时。
     *
     * 默认跟随系统（用户 2026-09-21 指定）：系统深浅色即 App 主题；越界值同样退回该默认。
     */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, ThemeManager.MODE_SYSTEM)
            .takeIf { it in ThemeManager.MODE_SYSTEM..ThemeManager.MODE_SCHEDULED }
            ?: ThemeManager.MODE_SYSTEM
        set(value) = sp.edit {
            // 与读取端一致：非法值一律落「跟随系统」。
            // 用 coerceIn 会把 9 钳成「定时」（最接近的合法值），与 isDarkNow 的 else 分支语义相左。
            val mode = value.takeIf { it in ThemeManager.MODE_SYSTEM..ThemeManager.MODE_SCHEDULED }
                ?: ThemeManager.MODE_SYSTEM
            putInt(KEY_THEME_MODE, mode)
        }

    /**
     * 符号分组顺序（label 的逗号分隔串；空串 = 默认次序，见 [SymbolOrder]）。
     *
     * 由排序页（[SymbolOrderActivity]）写入；只存顺序不存内容，版本新增的分组自动落到末尾。
     * 写入即归一（[SymbolOrder.serialize]）：落盘的恒为完整序列串，不会是空串。
     */
    var symbolGroupOrder: String
        get() = sp.getString(KEY_SYMBOL_ORDER, "").orEmpty()
        set(value) = sp.edit { putString(KEY_SYMBOL_ORDER, SymbolOrder.serialize(SymbolOrder.parse(value))) }

    /**
     * 「收藏」分组的内容（JSON 二维数组，见 [FavoriteSymbols]）。
     *
     * null（键不存在 = 从未编辑过）→ 上层按出厂预置（D I Y）处理；`"[]"` = 用户删光了，尊重之。
     */
    var favoriteSymbols: String?
        get() = sp.getString(KEY_FAVORITE_SYMBOLS, null)
        set(value) = sp.edit { putString(KEY_FAVORITE_SYMBOLS, value) }

    /** 定时模式：切到亮白的时刻（当天第几分钟），默认 07:00 */
    var themeLightAtMinutes: Int
        get() = sp.getInt(KEY_THEME_LIGHT_AT, DEFAULT_THEME_LIGHT_AT_MIN)
            .takeIf { it in 0 until ThemeManager.MINUTES_PER_DAY } ?: DEFAULT_THEME_LIGHT_AT_MIN
        set(value) = sp.edit {
            // 写入即归一：读取端虽已兜底，但存进配置里的脏值（负数 / 超 24h）会坑到绕过 Prefs 的读方
            putInt(KEY_THEME_LIGHT_AT, Math.floorMod(value, ThemeManager.MINUTES_PER_DAY))
        }

    /** 定时模式：切到暗黑的时刻（当天第几分钟），默认 19:00 */
    var themeDarkAtMinutes: Int
        get() = sp.getInt(KEY_THEME_DARK_AT, DEFAULT_THEME_DARK_AT_MIN)
            .takeIf { it in 0 until ThemeManager.MINUTES_PER_DAY } ?: DEFAULT_THEME_DARK_AT_MIN
        set(value) = sp.edit {
            putInt(KEY_THEME_DARK_AT, Math.floorMod(value, ThemeManager.MINUTES_PER_DAY))
        }

    /**
     * 26 键区（3 行 28 键：字母 + 大写 + 删除）的统一按键圆角半径（dp）。
     *
     * 定义域与默认值见 [KeyAppearance]（0~24dp，默认 1dp 微圆角）。getter 也做一次钳位：
     * 历史配置或外部写入可能带越界值，读出来必须是合法值才允许进绘制流程。
     */
    var keyCornerDp: Float
        get() = KeyAppearance.clampCornerDp(
            sp.getFloat(KEY_KEY_CORNER_DP, KeyAppearance.DEFAULT_CORNER_DP)
        )
        set(value) = sp.edit { putFloat(KEY_KEY_CORNER_DP, KeyAppearance.clampCornerDp(value)) }

    /**
     * 26 键区（3 行 28 键）的统一按键间隙（dp），相邻两键之间的空隙，左右与上下一致。
     *
     * 定义域与默认值见 [KeyAppearance]（0~8dp，默认 0.5dp，步进 0.5dp）。
     */
    var keyGapDp: Float
        get() = KeyAppearance.clampGapDp(
            sp.getFloat(KEY_KEY_GAP_DP, KeyAppearance.DEFAULT_GAP_DP)
        )
        set(value) = sp.edit { putFloat(KEY_KEY_GAP_DP, KeyAppearance.clampGapDp(value)) }

    /**
     * 键盘透明度（百分比；0 = 完全不透明，上界与两档 alpha 换算见 [KeyTransparency]）。
     *
     * 只影响「面」（背板 / 键面 / 候选栏），文字始终不透明。getter 也做一次钳位：
     * 历史配置或外部写入可能带越界值，读出来必须是合法值才允许进绘制流程。
     */
    var keyTransparencyPercent: Int
        get() = KeyTransparency.clampPercent(
            sp.getInt(KEY_KEY_TRANSPARENCY_PERCENT, KeyTransparency.DEFAULT_PERCENT)
        )
        set(value) = sp.edit {
            putInt(KEY_KEY_TRANSPARENCY_PERCENT, KeyTransparency.clampPercent(value))
        }

    /**
     * 亮色档的键盘皮肤 id（见 [KeyboardSkins]）。
     *
     * 两档槽位 + 明暗判定 = 当前生效皮肤（见 [ThemeManager.keyboardSkin]）：亮色阶段用本槽，
     * 暗色阶段用 [skinDarkId]。皮肤只覆盖键盘自身的色值（键面 / 功能键 / 候选栏 / 背板）与质感参数，
     * 与「半透明 / 圆角 / 间隙」正交。
     *
     * 缺省值取 [KeyboardSkins.INITIAL_LIGHT_ID]（雪原，用户 2026-09-24 指定）；
     * 未知 / 脏值 / 跨档值由 `byId` 归一为本档令牌基线（原白），不改历史观感。
     */
    var skinLightId: String
        get() {
            ensureSkinSlotsMigrated()
            val raw = sp.getString(KEY_SKIN_LIGHT, null) ?: return KeyboardSkins.INITIAL_LIGHT_ID
            return KeyboardSkins.byId(raw, SkinTone.LIGHT).id
        }
        set(value) = sp.edit { putString(KEY_SKIN_LIGHT, KeyboardSkins.byId(value, SkinTone.LIGHT).id) }

    /**
     * 暗色档的键盘皮肤 id。语义与读写规则同 [skinLightId]，缺省值为
     * [KeyboardSkins.INITIAL_DARK_ID]（紫晶，用户 2026-09-24 指定）。
     */
    var skinDarkId: String
        get() {
            ensureSkinSlotsMigrated()
            val raw = sp.getString(KEY_SKIN_DARK, null) ?: return KeyboardSkins.INITIAL_DARK_ID
            return KeyboardSkins.byId(raw, SkinTone.DARK).id
        }
        set(value) = sp.edit { putString(KEY_SKIN_DARK, KeyboardSkins.byId(value, SkinTone.DARK).id) }

    /**
     * 旧单值皮肤键（`keyboard_skin`）→ 双槽位的一次性迁移。
     *
     * 只在两个新键都缺失、且旧键存在时执行：[KeyboardSkins.migrateLegacySkin] 把旧值按档位拆成
     * 两份并落盘，旧键**原样保留**（回滚到旧版 APK 仍读得到，配置不丢）。
     * 放在 getter 而不是初始化里：配置可能在本次进程启动**之后**才被写入（备份导入、先回滚旧版
     * 再升级回来），只认启动时刻会漏掉这些路径。
     *
     * 反向不成立：两个新键已在时旧键不再参与判断 —— 「升级 → 回滚旧版改皮肤 → 再升级」会忽略
     * 回滚期的改动。这是把单值拆成双槽位的固有代价，不是漏改。
     */
    private fun ensureSkinSlotsMigrated() {
        if (sp.contains(KEY_SKIN_LIGHT) || sp.contains(KEY_SKIN_DARK)) return
        val legacy = sp.getString(KEY_KEYBOARD_SKIN, null) ?: return
        val (light, dark) = KeyboardSkins.migrateLegacySkin(legacy)
        sp.edit {
            putString(KEY_SKIN_LIGHT, light)
            putString(KEY_SKIN_DARK, dark)
        }
    }

    /**
     * 语音输入总开关，默认禁用。
     *
     * 禁用时语音功能完全沉寂：不创建 [AsrClient]/[MicRecorder]、不发起 WebSocket 连接，
     * 长按空格等入口一律置空，因此不占用任何语音相关内存。
     * 启用后才在 IME 重建（设置页保存会重启进程）时装配语音组件。
     */
    var voiceInputEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE_INPUT, false)
        set(value) = sp.edit { putBoolean(KEY_VOICE_INPUT, value) }

    /**
     * 拼接后的 WebSocket 地址。
     *
     * [host] 与 [port] 的 getter 已保证取值合法（host 经 [normalizeHost] 规范化、port 钳到有效区间），
     * 因此这里的字面拼接不会再产出 OkHttp 会拒绝的地址。
     */
    val wsUrl: String get() = "ws://$host:$port"

    /**
     * 等待所有已排队的 `apply()` 真正落盘。
     *
     * 导入结束后要 `killProcess` 重启输入法，而 `apply()` 是异步的、Activity 也不会走
     * onPause/onStop 替我们等 —— IO 压力大时最后一批键可能丢，用户看到的是「导入了一半」。
     * `commit()` 会排到队列末尾并等待其完成（SharedPreferences 的既有语义）。
     */
    internal fun flush(): Boolean =
        runCatching { sp.edit().commit() }.getOrDefault(false)

    // ── 备份导出 / 导入（见 ConfigBackup / ConfigBackupManager） ──────────

    /**
     * 备份导出：返回全部**可迁移**的键值。
     *
     * 覆盖 [Prefs] 的**全部**键（现在连 `update_last_check_at` 这类运行态时间戳也带上）：
     * 用户要求「所有配置与设置参数都能导出 / 导入」，少一个键都算漏。照搬该时间戳的副作用
     * 只是新机上首次自动检查更新可能晚一点（最长 7 天），不影响任何功能。
     *
     * `favorite_symbols` 用 null 标记「键不存在」= 从未编辑过（出厂预置 D I Y），
     * 与用户删光的 `"[]"` 是两种语义，导入时必须原样还原，故 null 也要导出。
     */
    internal fun exportForBackup(): Map<String, ConfigBackup.BackupValue> {
        val out = LinkedHashMap<String, ConfigBackup.BackupValue>()
        fun put(key: String, value: Any?) {
            ConfigBackup.BackupValue.of(value)?.let { out[key] = it }
        }
        put(KEY_HOST, host)
        put(KEY_PORT, port)
        put(KEY_LOCK_SERVER, lockServer)
        put(KEY_LANGUAGE, language)
        put(KEY_PROMPT, prompt)
        put(KEY_STRIP_PUNC, stripTrailingPunc)
        put(KEY_COMPOSING, useComposing)
        put(KEY_SHUANGPIN, useShuangpin)
        put(KEY_SHUANGPIN_SCHEME, shuangpinScheme)
        put(KEY_KB_ENGLISH, keyboardEnglish)
        put(KEY_AUTO_SHOW_KB, autoShowKeyboard)
        put(KEY_DEFAULT_MODE, defaultKeyboardMode)
        put(KEY_PREDICT_ENABLED, predictEnabled)
        put(KEY_CANDIDATE_ROWS, candidateRows)
        put(KEY_SHOW_KEY_HINT, showKeyHint)
        put(KEY_USER_LEARNING, userLearning)
        put(KEY_SHOW_RARE_CHARS, showRareChars)
        put(KEY_FUZZY_PINYIN, fuzzyPinyinMask)
        put(KEY_VOICE_INPUT, voiceInputEnabled)
        put(KEY_KEY_CORNER_DP, keyCornerDp)
        put(KEY_KEY_GAP_DP, keyGapDp)
        put(KEY_KEY_TRANSPARENCY_PERCENT, keyTransparencyPercent)
        put(KEY_SKIN_LIGHT, skinLightId)
        put(KEY_SKIN_DARK, skinDarkId)
        put(KEY_THEME_MODE, themeMode)
        put(KEY_SYMBOL_ORDER, symbolGroupOrder)
        put(KEY_FAVORITE_SYMBOLS, favoriteSymbols)
        put(KEY_THEME_LIGHT_AT, themeLightAtMinutes)
        put(KEY_THEME_DARK_AT, themeDarkAtMinutes)
        put(KEY_UPDATE_LAST_CHECK_AT, updateLastCheckAt)
        return out
    }

    /** 导入报告：成功写入的键数 + 白名单外的键 + 类型不符的键 */
    internal class ImportReport(val applied: Int, val unknown: List<String>, val mismatch: List<String>)

    /**
     * 备份导入（覆盖语义）：按白名单逐键写入，一律走本类 setter。
     *
     * 越界 / 未知取值由 setter 与各消费点的既有归一逻辑兜底（皮肤 id → 默认皮肤、
     * 方案编号 → 自然码、符号顺序 → 补回缺失分组），脏备份不会把非法值带进运行期。
     * 白名单外的键（更高版本生成的备份）忽略并计数，不报错。
     */
    internal fun importFromBackup(values: Map<String, ConfigBackup.BackupValue>): ImportReport {
        var applied = 0
        val unknown = ArrayList<String>()
        val mismatch = ArrayList<String>()

        fun ok() { applied++ }
        fun bad(key: String) { mismatch.add(key) }

        for ((key, v) in values) {
            when (key) {
                KEY_HOST -> asString(v)?.let { host = it; ok() } ?: bad(key)
                KEY_PORT -> asInt(v)?.let { port = it; ok() } ?: bad(key)
                KEY_LOCK_SERVER -> asBool(v)?.let { lockServer = it; ok() } ?: bad(key)
                KEY_LANGUAGE -> asString(v)?.let { language = it; ok() } ?: bad(key)
                KEY_PROMPT -> asString(v)?.let { prompt = it; ok() } ?: bad(key)
                KEY_STRIP_PUNC -> asBool(v)?.let { stripTrailingPunc = it; ok() } ?: bad(key)
                KEY_COMPOSING -> asBool(v)?.let { useComposing = it; ok() } ?: bad(key)
                KEY_SHUANGPIN -> asBool(v)?.let { useShuangpin = it; ok() } ?: bad(key)
                KEY_SHUANGPIN_SCHEME -> asInt(v)?.let { shuangpinScheme = it; ok() } ?: bad(key)
                KEY_KB_ENGLISH -> asBool(v)?.let { keyboardEnglish = it; ok() } ?: bad(key)
                KEY_AUTO_SHOW_KB -> asBool(v)?.let { autoShowKeyboard = it; ok() } ?: bad(key)
                KEY_DEFAULT_MODE -> asInt(v)?.let { defaultKeyboardMode = it; ok() } ?: bad(key)
                KEY_PREDICT_ENABLED -> asBool(v)?.let { predictEnabled = it; ok() } ?: bad(key)
                KEY_CANDIDATE_ROWS -> asInt(v)?.let { candidateRows = it; ok() } ?: bad(key)
                KEY_SHOW_KEY_HINT -> asBool(v)?.let { showKeyHint = it; ok() } ?: bad(key)
                KEY_USER_LEARNING -> asBool(v)?.let { userLearning = it; ok() } ?: bad(key)
                KEY_SHOW_RARE_CHARS -> asBool(v)?.let { showRareChars = it; ok() } ?: bad(key)
                KEY_FUZZY_PINYIN -> asInt(v)?.let { fuzzyPinyinMask = it; ok() } ?: bad(key)
                KEY_VOICE_INPUT -> asBool(v)?.let { voiceInputEnabled = it; ok() } ?: bad(key)
                KEY_KEY_CORNER_DP -> asFloat(v)?.let { keyCornerDp = it; ok() } ?: bad(key)
                KEY_KEY_GAP_DP -> asFloat(v)?.let { keyGapDp = it; ok() } ?: bad(key)
                KEY_KEY_TRANSPARENCY_PERCENT ->
                    asInt(v)?.let { keyTransparencyPercent = it; ok() } ?: bad(key)
                KEY_SKIN_LIGHT -> asString(v)?.let { skinLightId = it; ok() } ?: bad(key)
                KEY_SKIN_DARK -> asString(v)?.let { skinDarkId = it; ok() } ?: bad(key)
                // 退役的旧皮肤键：新版本不再写它，但**旧备份里只有它** —— 不还原就会让
                // 「新机器导入旧版备份」丢掉用户选过的皮肤（静默落回出厂默认）。
                // 还原语义同迁移：旧皮肤入自己档位的槽、另一槽取该档令牌基线。
                // 包内同带新键时以新键为准（新包不写旧键，并存只可能是手工构造的包）。
                KEY_KEYBOARD_SKIN -> when {
                    values.containsKey(KEY_SKIN_LIGHT) || values.containsKey(KEY_SKIN_DARK) ->
                        unknown.add(key)
                    else -> asString(v)?.let { legacy ->
                        val (light, dark) = KeyboardSkins.migrateLegacySkin(legacy)
                        skinLightId = light
                        skinDarkId = dark
                        ok()
                    } ?: bad(key)
                }
                KEY_THEME_MODE -> asInt(v)?.let { themeMode = it; ok() } ?: bad(key)
                KEY_SYMBOL_ORDER -> asString(v)?.let { symbolGroupOrder = it; ok() } ?: bad(key)
                // 键存在但值为 null = 从未编辑过：必须移除本机取值才能还原「出厂预置」态
                KEY_FAVORITE_SYMBOLS -> if (v.kind == ConfigBackup.BackupValue.KIND_NULL) {
                    sp.edit { remove(KEY_FAVORITE_SYMBOLS) }
                    ok()
                } else {
                    val raw = asString(v)
                    when {
                        raw == null -> bad(key)
                        // 与 symbol_group_order 同理必须归一：解析在主线程（键盘视图构造时）执行，
                        // 十几 MB 的数组会让每次重建键盘都做百万级解析。归一后落盘的是规范结构，
                        // 符号集合与顺序不变（见 FavoriteSymbols 的结构规则）。
                        raw.length > MAX_FAVORITE_SYMBOLS_CHARS -> bad(key)
                        else -> {
                            favoriteSymbols = FavoriteSymbols.serialize(FavoriteSymbols.parse(raw))
                            ok()
                        }
                    }
                }
                KEY_THEME_LIGHT_AT -> asInt(v)?.let { themeLightAtMinutes = it; ok() } ?: bad(key)
                KEY_THEME_DARK_AT -> asInt(v)?.let { themeDarkAtMinutes = it; ok() } ?: bad(key)
                // 运行态时间戳同样照搬（全量），也是 Long 类型路径的唯一真实使用者
                KEY_UPDATE_LAST_CHECK_AT -> asLong(v)?.let { updateLastCheckAt = it; ok() } ?: bad(key)
                else -> unknown.add(key)
            }
        }
        return ImportReport(applied, unknown, mismatch)
    }

    private fun asString(v: ConfigBackup.BackupValue): String? = v.value as? String
    private fun asInt(v: ConfigBackup.BackupValue): Int? = v.value as? Int
    private fun asLong(v: ConfigBackup.BackupValue): Long? = v.value as? Long
    private fun asFloat(v: ConfigBackup.BackupValue): Float? = v.value as? Float
    private fun asBool(v: ConfigBackup.BackupValue): Boolean? = v.value as? Boolean

    companion object {
        const val DEFAULT_HOST = "192.168.1.3"
        const val DEFAULT_PORT = 6016
        const val DEFAULT_LANGUAGE = "auto"

        /** 会破坏 `ws://host:port` 结构的字符（`: / \ ? # @ [ ]`；方括号 IPv6 走单独分支） */
        private val INVALID_HOST_CHARS = charArrayOf(':', '/', '\\', '?', '#', '@', '[', ']')

        /**
         * host 合法性校验（纯函数，可直接 JVM 单测）。
         *
         * 两道判据，缺一不可：
         *  1. 结构规则：挡掉语义明显不对的写法，把 `host:port`、`http://…`、`h/path`、
         *     `user@h` 当 host 填进来，或整串只有标点（`.` / `-` / `..`，这些不是主机名）；
         *     IPv6 字面量按 URL 规则必须写成 `[::1]`，方括号内只放十六进制、冒号、点与 `%`（zone id）。
         *  2. 终判交给真实消费者：按 `http://$h:1` 解析一次。
         *     只靠字符黑名单挡不住全部非法值，实测 `..` 不含任何黑名单字符，
         *     却会让 `HttpUrl` 抛 `IllegalArgumentException`（那就是主线程崩溃）。
         *     用 `http` 而非 `ws` 起头：`Request.Builder.url()` 收到 `ws://` 时本身就是先改写成
         *     `http://` 再解析的，而 `toHttpUrlOrNull()` 不接受 `ws:` 方案 ，
         *     拿 `ws://` 去判会一律得到 null（把合法 host 全判成非法）。
         *     端口用 1 而非实际端口：合法 host 下端口取值不影响能否解析。
         */
        fun isValidHost(raw: String): Boolean {
            val h = raw.trim()
            if (h.isEmpty()) return false

            if (h.startsWith("[") && h.endsWith("]")) {
                val inner = h.substring(1, h.length - 1)
                if (inner.isEmpty()) return false
                val ok = inner.all {
                    it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%'
                }
                if (!ok) return false
            } else {
                // 至少含一个字母/数字：`.`, `-`, `..` 这类纯标点不是主机
                if (h.none { it.isLetterOrDigit() }) return false
                for (c in h) {
                    if (c.isWhitespace() || c.isISOControl()) return false
                    if (INVALID_HOST_CHARS.contains(c)) return false
                }
            }

            return runCatching { ("http://$h:1").toHttpUrlOrNull() }.getOrNull() != null
        }

        /**
         * 规范化 host：去首尾空白后校验，非法返回 null。
         *
         * 取值方必须用它、而不是拿 `isValidHost` 判完就把原串拼进 URL，校验内部会 trim，
         * 但原串没变：存了 `" 192.168.1.3 "` 时会拼出 `ws:// 192.168.1.3 :6016`，
         * OkHttp 照样抛异常（实测），等于绕过校验把主线程崩溃点留在原地。
         */
        fun normalizeHost(raw: String): String? {
            val h = raw.trim()
            return if (isValidHost(h)) h else null
        }

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_LOCK_SERVER = "lock_server"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_STRIP_PUNC = "strip_punc"
        private const val KEY_COMPOSING = "composing"
        /** 是否启用双拼（2026-09-20 起由设置页「输入方案」下拉写；老版本由面板按钮写） */
        private const val KEY_SHUANGPIN = "shuangpin"
        private const val KEY_SHUANGPIN_SCHEME = "shuangpin_scheme"
        private const val KEY_KB_ENGLISH = "kb_english"
        private const val KEY_AUTO_SHOW_KB = "auto_show_keyboard"
        private const val KEY_DEFAULT_MODE = "default_mode"
        private const val KEY_PREDICT_ENABLED = "predict_enabled"
        /** 候选词行数（1 单行 / 2 双行，见 [Prefs.candidateRows] 与 [CandidateRows]） */
        private const val KEY_CANDIDATE_ROWS = "candidate_rows"
        /** 键面韵母提示开关（见 [Prefs.showKeyHint] 与 [KeyHint]） */
        private const val KEY_SHOW_KEY_HINT = "show_key_hint"
        private const val KEY_USER_LEARNING = "user_learning"
        /** 上次成功检查更新的时刻（epoch ms；0 = 从未成功检查过） */
        private const val KEY_UPDATE_LAST_CHECK_AT = "update_last_check_at"

    private const val KEY_SHOW_RARE_CHARS = "show_rare_chars"
        /** 模糊音容错掩码（见 [FuzzyPinyin]）；0 = 关闭，也是出厂默认 */
        private const val KEY_FUZZY_PINYIN = "fuzzy_pinyin"
        private const val KEY_VOICE_INPUT = "voice_input"
        private const val KEY_KEY_CORNER_DP = "key_corner_dp"
        private const val KEY_KEY_GAP_DP = "key_gap_dp"
        private const val KEY_KEY_TRANSPARENCY_PERCENT = "key_transparency_percent"
        /**
         * 已退役：旧版单值皮肤键，现在只在 [ensureSkinSlotsMigrated] 里读取，不再写入。
         * 常量名保留原样，好让备份覆盖面守卫把它一并清点（见 `PrefsBackupCoverageTest.retiredKeys`）。
         */
        private const val KEY_KEYBOARD_SKIN = "keyboard_skin"

        /** 亮色 / 暗色两档的键盘皮肤（见 [KeyboardSkins] 与 [ThemeManager.keyboardSkin]） */
        private const val KEY_SKIN_LIGHT = "skin_light"
        private const val KEY_SKIN_DARK = "skin_dark"
        /** 主题模式与定时切换时刻（见 [ThemeManager]） */
        private const val KEY_THEME_MODE = "theme_mode"

        /** 符号分组顺序（label 串；空 = 默认，见 [SymbolOrder]） */
        private const val KEY_SYMBOL_ORDER = "symbol_group_order"
        private const val KEY_FAVORITE_SYMBOLS = "favorite_symbols"

        /**
         * `favorite_symbols` 的导入长度上限（32K 字符 ≈ 百页符号，远超任何真实用法）。
         *
         * 它必须与 `symbol_group_order` 一样在导入时归一：解析发生在键盘视图构造的**主线程**上，
         * 超长 JSON 会让每次重建键盘都做百万级解析（ANR/OOM），且值已落盘、重启输入法也无效。
         *
         * 取值要留出真实容量：单项最长 8 字符、序列化后每项约 12 字符，原先的 4096 约三百项即触顶，
         * 收藏更多的用户自己的备份会导不回来（该键被整条拒收）。32K 下解析仍是毫秒级。
         */
        internal const val MAX_FAVORITE_SYMBOLS_CHARS = 32 * 1024
        private const val KEY_THEME_LIGHT_AT = "theme_light_at"
        private const val KEY_THEME_DARK_AT = "theme_dark_at"

        /** 定时切换的默认时刻：07:00 亮起 / 19:00 暗起 */
        const val DEFAULT_THEME_LIGHT_AT_MIN = 7 * 60
        const val DEFAULT_THEME_DARK_AT_MIN = 19 * 60
    }
}
