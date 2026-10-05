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
 * 备份里**单个字符串值**的长度上限（导入侧闸与写入侧闸**共用同一来源**，2026-10-02 修复 L-375）。
 *
 * 取 64 KB：它本是 `asString` 的既有阈值（对抗性防护 —— 外部包塞一个巨大字符串），此前
 * **只用在导入侧**，于是出现「自产的包不一定导得回来」：导出不设限 ⇒ 该键被自己的导入端拒收，
 * 而清单说「包含」、导入说「忽略 N 项」，两个数字互相矛盾。
 * 两侧共用同一常量后，改这个数字只需改一处，也不会再出现「一边放行、一边拒收」。
 */
internal const val MAX_BACKUP_STRING_CHARS = 64 * 1024

/**
 * 配置存储。只暴露真正需要用户改的项，其余走协议默认值。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("jinn_inputmethod", Context.MODE_PRIVATE)

    /**
     * 读侧类型防御（收口，见 BUG.md L-638 / L-653）：`SharedPreferences` 对**类型不符**的键是
     * `(Integer) mMap.get(key)` 强转 ⇒ 抛 `ClassCastException`（**不是**返回默认值）。
     * 配置被手改 / 第三方克隆工具恢复 / `adb restore` 旧 prefs 时就会命中，而抛点在
     * 主线程点击回调里、未捕获即崩溃。
     *
     * **全部读点**都走这五个助手（2026-10-04 修复 L-653）：L-638 只收了翻译相关的字符串 / 整型，
     * 凭据键、布尔总开关与更新检查时间戳仍是裸读 —— 「打开设置页就崩」比「点翻译才崩」更早暴露。
     * 备份导出 / 导入那几条走变量键、自带类型门（`as? Int` 等），不经这里。
     *
     * 写侧（`putInt` 等）类型由自己决定，不需要这层。
     */
    private fun intOr(key: String, def: Int): Int = runCatching { sp.getInt(key, def) }.getOrDefault(def)

    private fun strOr(key: String, def: String? = null): String? =
        runCatching { sp.getString(key, def) }.getOrNull() ?: def

    private fun boolOr(key: String, def: Boolean): Boolean =
        runCatching { sp.getBoolean(key, def) }.getOrDefault(def)

    private fun longOr(key: String, def: Long): Long =
        runCatching { sp.getLong(key, def) }.getOrDefault(def)

    private fun floatOr(key: String, def: Float): Float =
        runCatching { sp.getFloat(key, def) }.getOrDefault(def)

    /**
     * 飞牛 NAS 的局域网地址。
     *
     * getter 做一次合法性兜底：存档里的非法值（旧版本写入、手动改 prefs）一律回落到默认地址。
     * 这是崩不崩的边界，[wsUrl] 会被直接送进 OkHttp，而 `HttpUrl` 对含空格或重复端口的
     * host 会抛 `IllegalArgumentException`，调用点又都在主线程（整个 IME 会崩）。
     * 语音链路属禁改区，所以校验放在配置层，保证交出去的 URL 一定是合法形式。
     */
    var host: String
        get() = normalizeHost(strOr(KEY_HOST, DEFAULT_HOST).orEmpty()) ?: DEFAULT_HOST
        set(value) = sp.edit { putString(KEY_HOST, value.trim()) }

    /**
     * 服务端口，对齐 config_server.py 的 6016。
     * getter 钳到合法端口区间：越界端口同样会让 `HttpUrl` 抛异常。
     */
    var port: Int
        get() = intOr(KEY_PORT, DEFAULT_PORT).takeIf { it in 1..65535 } ?: DEFAULT_PORT
        set(value) = sp.edit { putInt(KEY_PORT, value) }

    /** 固定 NAS 地址与端口：勾选后设置页编辑框变灰不可编辑，防误触乱改（默认勾选） */
    var lockServer: Boolean
        get() = boolOr(KEY_LOCK_SERVER, true)
        set(value) = sp.edit { putBoolean(KEY_LOCK_SERVER, value) }

    /** 统一语言代码，取值见服务端 engines/language.py */
    var language: String
        get() = strOr(KEY_LANGUAGE, DEFAULT_LANGUAGE).orEmpty().ifBlank { DEFAULT_LANGUAGE }
        // 等于默认值就**删键**（2026-10-01 修复 L-255）：否则「打开过一次设置页」就把当时的默认
        // 语言固化进 prefs，将来改默认语言对该用户无效（口径同 [defaulted]）
        set(value) = sp.edit {
            if (value == DEFAULT_LANGUAGE) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, value)
        }

    /** 识别提示词，人名/地名/术语写在这里能提升准确率 */
    var prompt: String
        get() = strOr(KEY_PROMPT, "").orEmpty()
        set(value) = sp.edit { putString(KEY_PROMPT, value) }

    /** 去掉句尾逗号句号，对齐桌面端 trash_punc 的习惯 */
    var stripTrailingPunc: Boolean
        get() = boolOr(KEY_STRIP_PUNC, true)
        set(value) = sp.edit { putBoolean(KEY_STRIP_PUNC, value) }

    /**
     * 用预编辑（composing）文本实时回显。
     * 少数输入框对长预编辑串兼容不好，关掉后改为识别完成一次性提交。
     * 默认关闭：识别完成一次性提交更稳，避免长串兼容问题。
     */
    var useComposing: Boolean
        get() = boolOr(KEY_COMPOSING, false)
        set(value) = sp.edit { putBoolean(KEY_COMPOSING, value) }

    /**
     * 是否启用双拼。
     *
     * 2026-09-20 起由设置页「输入方案」下拉写（选全拼 = false / 选某套双拼 = true；
     * 按键面板的「全拼 / 双拼」按钮已按用户要求移除）。老版本即用此键，故无需迁移。
     */
    var useShuangpin: Boolean
        get() = boolOr(KEY_SHUANGPIN, false)
        set(value) = sp.edit { putBoolean(KEY_SHUANGPIN, value) }

    /**
     * 选定的双拼方案，取值见 [ShuangpinScheme]（1 自然码 … 7 加加，不存 0）。
     *
     * 只在设置页「输入方案」下拉里改（选双拼项时写入；面板已无方案切换入口）。
     * 选全拼不清除本键：下次选回双拼时回到上次那套，不会丢用户的选择。
     */
    var shuangpinScheme: Int
        get() {
            val v = intOr(KEY_SHUANGPIN_SCHEME, ShuangpinScheme.ZIRANMA.prefsValue)
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
        get() = boolOr(KEY_KB_ENGLISH, false)
        set(value) = sp.edit { putBoolean(KEY_KB_ENGLISH, value) }

    /**
     * 自动唤起键盘：true 编辑框聚焦时正常自动显示；false 永久禁止本输入法
     * 主动唤起（不受编辑框焦点、APP 切换、IME 生命周期重启影响），
     * 直到用户在设置页重新开启。拦截点在 [JinnIme.onShowInputRequested]。
     */
    var autoShowKeyboard: Boolean
        get() = boolOr(KEY_AUTO_SHOW_KB, true)
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
            val v = intOr(KEY_DEFAULT_MODE, DefaultKeyboardMode.PINYIN_CN)
            return v.takeIf { it in DefaultKeyboardMode.VOICE..DefaultKeyboardMode.PINYIN_EN }
                ?: DefaultKeyboardMode.PINYIN_CN
        }
        set(value) = sp.edit {
            putInt(KEY_DEFAULT_MODE, value.coerceIn(DefaultKeyboardMode.VOICE, DefaultKeyboardMode.PINYIN_EN))
        }

    /**
     * 可选字档 2（`单字注音_二级简体.txt`，837 字）是否放行，**默认关**。
     *
     * 2026-09-27 起「加更多生僻字」从开关改为设置页按钮 + 独立页面（[RareCharsActivity]）里的
     * 两个档位开关：档 2 / 档 3 都按查询期判据放行（`isLoadableChar`），**改完即时生效**，
     * 不再需要重启输入法（旧 [KEY_SHOW_RARE_CHARS] 的「重启生效」语义随两档开关一起退场）。
     *
     * 旧键只作一次性迁移：新键没写过时回落到旧值，保证升级用户不丢设置。
     * ⚠ **回落口径 = 旧 true 即两档全开**（旧版语义是「放行表外字」，而旧版默认本就放行三级字）——
     * 与 `importFromBackup` 的旧键分支同款；两档的回落**必须一致**，否则原生升级用户会只得到档 2，
     * 三级字（2,923 字）与含三级字的词条整体消失（2026-09-27 修，BUG.md L-41）。
     */
    var rareTier2: Boolean
        get() = if (sp.contains(KEY_RARE_TIER2)) boolOr(KEY_RARE_TIER2, false)
        else boolOr(KEY_SHOW_RARE_CHARS_LEGACY, false)
        set(value) = sp.edit { putBoolean(KEY_RARE_TIER2, value) }

    /**
     * 可选字档 3（`单字注音_三级简体.txt`，2,923 字）是否放行，**默认关，且依赖 [rareTier2]**。
     *
     * 「不能只开档 3」由设置页强制（[RareCharsActivity] 里档 2 关闭时档 3 不可点），
     * 引擎侧再兜一次（`setRareTiers` 内 `rareTier3 = tier2 && tier3`），
     * 避免从导入的备份或测试注入里混进非法组合。
     *
     * 旧键回落与 [rareTier2] **同口径**（见其 KDoc）：`rare_tier3` 缺失但旧 `show_rare_chars` 为 true 时
     * 也返回 true。这条曾经缺失 ⇒ 原生升级（旧开关 true + 覆盖安装 + 不导入备份）只开档 2，
     * 而导出又把这个回落值写进备份、把旧键丢掉，于是「升级丢档 3 → 备份固化 → 换机永久丢」。
     * 修法两种都可行：① 这里补回落（本处采用，getter / 导入 / 导出三条路径自然一致）；
     * ② 改成一次性迁移（`ensureRareTiersMigrated()` 按旧键写入两个新键，与 `ensureSkinSlotsMigrated` 同款）
     * —— 真值只剩一份，但要新增调用点，留待将来真加档位时一并做。
     */
    var rareTier3: Boolean
        get() = if (sp.contains(KEY_RARE_TIER3)) boolOr(KEY_RARE_TIER3, false)
        else boolOr(KEY_SHOW_RARE_CHARS_LEGACY, false)
        set(value) = sp.edit { putBoolean(KEY_RARE_TIER3, value) }

    /**
     * 「只使用繁体字」（默认关）：候选里的简体字按简繁映射表替换为繁体字。
     *
     * 查询期转换（`PinyinEngine.toDisplay`），**改完即时生效**：不改词库数据、不改拼音键；
     * 用户词频恒按简体存储（`rememberChoice`），两种模式共享同一份学习结果。
     */
    var useTraditional: Boolean
        get() = boolOr(KEY_USE_TRADITIONAL, false)
        set(value) = sp.edit { putBoolean(KEY_USE_TRADITIONAL, value) }

    /**
     * 模糊音容错：按 [FuzzyPinyin] 的分组位掩码存，**默认 [FuzzyPinyin.NONE]（关闭）**。
     *
     * 默认关是刻意选择：打开才派生变体候选，关闭时查询结果与历史逐候选一致。
     * 读写两端都过 [FuzzyPinyin.clampMask]：导入的备份里可能带未定义的位。
     */
    var fuzzyPinyinMask: Int
        get() = FuzzyPinyin.clampMask(intOr(KEY_FUZZY_PINYIN, FuzzyPinyin.NONE))
        set(value) = sp.edit { putInt(KEY_FUZZY_PINYIN, FuzzyPinyin.clampMask(value)) }

    /** 用户词频学习：记录「实际选过」的候选并提到前面（默认关；本地存储，不上传） */
    var userLearning: Boolean
        get() = boolOr(KEY_USER_LEARNING, false)
        set(value) = sp.edit { putBoolean(KEY_USER_LEARNING, value) }

    /**
     * 候选的智能预测词：选完一个词后，在候选栏预告下一个字词（默认关）。
     * 打开后选完文字才会出现预测候选；关掉时完全不产生预测。
     */
    var predictEnabled: Boolean
        get() = boolOr(KEY_PREDICT_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_PREDICT_ENABLED, value) }

    /**
     * 候选词行数：1 = 单行横向（默认），2 = 双行（上排偶数项、下排奇数项，上下成列）。
     *
     * 只影响候选栏的结构与高度（见 [CandidateRows]），不改候选顺序：
     * 空格 / 回车仍取第 1 个候选（= 下排首项）。越界值一律落回 1 行。
     */
    var candidateRows: Int
        get() = intOr(KEY_CANDIDATE_ROWS, CandidateRows.SINGLE).takeIf { it in CandidateRows.SINGLE..CandidateRows.DOUBLE }
            ?: CandidateRows.SINGLE
        set(value) = sp.edit { putInt(KEY_CANDIDATE_ROWS, value.coerceIn(CandidateRows.SINGLE, CandidateRows.DOUBLE)) }

    /**
     * 键面韵母提示（仅双拼有意义）：字母键下方显示各键对应的韵母 / 声母小字，**默认开**（保持历史观感）。
     *
     * 关闭后键面只显示字母，字母改为铺满居中（见 [KeyHint]）。与候选词行数一样，
     * 键盘下次弹出即按新设置渲染，不必重启输入法。
     */
    var showKeyHint: Boolean
        get() = boolOr(KEY_SHOW_KEY_HINT, true)
        set(value) = sp.edit { putBoolean(KEY_SHOW_KEY_HINT, value) }

    /**
     * 双拼候选栏拼音行的显示方式：false（默认）= 显示按下的英文字母；true = 按声母 / 韵母展开成全拼。
     *
     * 只影响候选栏显示，不改输入、查询与上屏；残码逐键展开、绝不丢键（见 [Shuangpin.displayQuanpin]）。
     * 与 [showKeyHint] 一样，键盘下次弹出即按新设置渲染。
     */
    var showQuanpin: Boolean
        get() = boolOr(KEY_SHOW_QUANPIN, false)
        set(value) = sp.edit { putBoolean(KEY_SHOW_QUANPIN, value) }

    /**
     * 上次「检查更新」成功的时刻（epoch ms，0 = 从未成功检查过）。
     *
     * 只由设置页在拿到有效结果（有更新 / 已最新）时写入：失败不写，下次打开设置页仍会静默重试；
     * 成功则 [UpdateChecker.AUTO_CHECK_INTERVAL_MS] 内不再自动检查，避免反复打扰。
     */
    var updateLastCheckAt: Long
        get() = longOr(KEY_UPDATE_LAST_CHECK_AT, 0L)
        set(value) = sp.edit { putLong(KEY_UPDATE_LAST_CHECK_AT, value) }

    /**
     * 主题模式（见 [ThemeManager]）：0 跟随系统 / 1 亮白 / 2 暗黑 / 3 定时。
     *
     * 默认跟随系统（用户 2026-09-21 指定）：系统深浅色即 App 主题；越界值同样退回该默认。
     */
    var themeMode: Int
        get() = intOr(KEY_THEME_MODE, ThemeManager.MODE_SYSTEM)
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
        get() = strOr(KEY_SYMBOL_ORDER, "").orEmpty()
        set(value) = sp.edit { putString(KEY_SYMBOL_ORDER, SymbolOrder.serialize(SymbolOrder.parse(value))) }

    /**
     * 「收藏」分组的内容（JSON 二维数组，见 [FavoriteSymbols]）。
     *
     * null（键不存在 = 从未编辑过）→ 上层按出厂预置（D I Y）处理；`"[]"` = 用户删光了，尊重之。
     *
     * 写入即归一（与导入路径同款）：解析发生在键盘视图构造的**主线程**上，超长或非规范的 JSON
     * 会让每次重建键盘都做百万级解析，而值已落盘、重启输入法也无效。超过上限直接不写
     * （保留原值），调用方自己要把内容收敛后再来。
     */
    var favoriteSymbols: String?
        get() = strOr(KEY_FAVORITE_SYMBOLS, null)
        set(value) = sp.edit {
            when {
                value == null -> putString(KEY_FAVORITE_SYMBOLS, null)
                value.length <= MAX_FAVORITE_SYMBOLS_CHARS ->
                    putString(KEY_FAVORITE_SYMBOLS, FavoriteSymbols.serialize(FavoriteSymbols.parse(value)))
            }
        }

    /** 定时模式：切到亮白的时刻（当天第几分钟），默认 07:00 */
    var themeLightAtMinutes: Int
        get() = intOr(KEY_THEME_LIGHT_AT, DEFAULT_THEME_LIGHT_AT_MIN)
            .takeIf { it in 0 until ThemeManager.MINUTES_PER_DAY } ?: DEFAULT_THEME_LIGHT_AT_MIN
        set(value) = sp.edit {
            // 写入即归一：读取端虽已兜底，但存进配置里的脏值（负数 / 超 24h）会坑到绕过 Prefs 的读方
            putInt(KEY_THEME_LIGHT_AT, Math.floorMod(value, ThemeManager.MINUTES_PER_DAY))
        }

    /** 定时模式：切到暗黑的时刻（当天第几分钟），默认 19:00 */
    var themeDarkAtMinutes: Int
        get() = intOr(KEY_THEME_DARK_AT, DEFAULT_THEME_DARK_AT_MIN)
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
            floatOr(KEY_KEY_CORNER_DP, KeyAppearance.DEFAULT_CORNER_DP)
        )
        set(value) = sp.edit { putFloat(KEY_KEY_CORNER_DP, KeyAppearance.clampCornerDp(value)) }

    /**
     * 26 键区（3 行 28 键）的统一按键间隙（dp），相邻两键之间的空隙，左右与上下一致。
     *
     * 定义域与默认值见 [KeyAppearance]（0~8dp，默认 0.5dp，步进 0.5dp）。
     */
    var keyGapDp: Float
        get() = KeyAppearance.clampGapDp(
            floatOr(KEY_KEY_GAP_DP, KeyAppearance.DEFAULT_GAP_DP)
        )
        set(value) = sp.edit { putFloat(KEY_KEY_GAP_DP, KeyAppearance.clampGapDp(value)) }

    /**
     * 候选栏字距（dp）：**相邻两个候选词之间的水平间隔**（只影响水平方向，行高不动）。
     *
     * 与按键「间隙」同语义：实现上每个候选左右各内缩本值的一半（见 PinyinKeyboardView
     * 的候选渲染）。定义域 5~30dp、默认 10dp（用户 2026-10-02 指定）。
     */
    var candidateSpacingDp: Float
        // getter 也要钳位（与圆角 / 间隙 / 透明度三个兄弟键同款，2026-10-02 第二轮审查）：
        // 手改 prefs、第三方写入或将来某条绕过 setter 的路径写进越界值时会**直达绘制** ——
        // 值越大候选项左右内缩越多，巨大值会把每个候选撑到屏幕外（跨行溢出型布局异常）。
        get() = KeyAppearance.clampSpacingDp(
            floatOr(KEY_CANDIDATE_SPACING_DP, KeyAppearance.DEFAULT_SPACING_DP),
        )
        set(value) = sp.edit { putFloat(KEY_CANDIDATE_SPACING_DP, KeyAppearance.clampSpacingDp(value)) }

    /**
     * 候选文字字号（sp，默认 20sp）：**整个候选栏按它派生** —— 行高、拼音条高度、栏高
     * 都由 [CandidateRows] 按本值算（见 [CandidateText] 的定义域说明）。
     *
     * getter 也做一次钳位（与圆角 / 间隙 / 字距 / 透明度四个兄弟键同款）：手改 prefs、
     * 第三方写入或将来某条绕过 setter 的路径写进越界值时会直达布局 —— 字号过大把候选栏撑满
     * 整屏、过小则连字都点不中。
     */
    var candidateTextSp: Float
        get() = CandidateText.clampSp(floatOr(KEY_CANDIDATE_TEXT_SP, CandidateText.DEFAULT_SP))
        set(value) = sp.edit { putFloat(KEY_CANDIDATE_TEXT_SP, CandidateText.clampSp(value)) }

    /**
     * 键盘透明度（百分比；0 = 完全不透明，上界与两档 alpha 换算见 [KeyTransparency]）。
     *
     * 只影响「面」（背板 / 键面 / 候选栏），文字始终不透明。getter 也做一次钳位：
     * 历史配置或外部写入可能带越界值，读出来必须是合法值才允许进绘制流程。
     */
    var keyTransparencyPercent: Int
        get() = KeyTransparency.clampPercent(
            intOr(KEY_KEY_TRANSPARENCY_PERCENT, KeyTransparency.DEFAULT_PERCENT)
        )
        set(value) = sp.edit {
            putInt(KEY_KEY_TRANSPARENCY_PERCENT, KeyTransparency.clampPercent(value))
        }

    // ── 敲击音效反馈（设置页「敲击音效反馈」，2026-10-05 起）────────────────
    //
    // 出厂默认**音效关 / 震动关**：按键发声与震动都是打扰性反馈，必须由用户主动开启。
    // 本组只表达「用户意愿」；系统侧的闸门（静音模式 / 触摸提示音 / 触摸时振动）在播放侧
    // 另行判断（见 [KeyFeedback]），两者是 AND 关系 —— 用户开了但如果系统关了，仍然不出声/不震。

    /** 敲击音效总开关（默认关，见 [KeyFeedback]） */
    var tapSoundEnabled: Boolean
        get() = boolOr(KEY_TAP_SOUND_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_TAP_SOUND_ENABLED, value) }

    /**
     * 音效强度（0–100，默认 70）。
     *
     * 为什么要它：按键音走 `USAGE_ASSISTANCE_SONIFICATION`（= 系统音效音量），
     * 而系统音效音量在多数 ROM 上**没有**给用户的可见滑杆，只能靠 SoundPool 的
     * 单次播放音量做独立调节。getter 也钳位：脏配置不能把音量算成非法值。
     */
    var tapSoundVolume: Int
        get() = TapSound.clampVolume(intOr(KEY_TAP_SOUND_VOLUME, TapSound.VOLUME_DEFAULT))
        set(value) = sp.edit { putInt(KEY_TAP_SOUND_VOLUME, TapSound.clampVolume(value)) }

    /** 静音（含仅震动）时是否仍然播放按键音（默认关 = 严格跟随系统响铃模式） */
    var tapSoundOnSilent: Boolean
        get() = boolOr(KEY_TAP_SOUND_ON_SILENT, false)
        set(value) = sp.edit { putBoolean(KEY_TAP_SOUND_ON_SILENT, value) }

    /**
     * 分组 → 音效索引映射（`"7,3,0,14,13,2"` 形式，分组顺序见 [TapSound.GROUP_LABELS]，
     * 索引 -1 = 该组不播放）。
     *
     * 读写两侧都过 [TapSound.parseMap] / [TapSound.formatMap]：备份导入允许外部文件写进
     * 任意字符串，没有这层钳位就是一次数组越界崩溃。归一后落盘的永远是规范串。
     */
    var tapSoundMap: String
        get() = TapSound.formatMap(TapSound.parseMap(strOr(KEY_TAP_SOUND_MAP, null)))
        set(value) = sp.edit { putString(KEY_TAP_SOUND_MAP, TapSound.formatMap(TapSound.parseMap(value))) }

    /** 按键震动总开关（默认关） */
    var tapVibrateEnabled: Boolean
        get() = boolOr(KEY_TAP_VIBRATE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_TAP_VIBRATE_ENABLED, value) }

    /** 震动强度档（0 关 / 1 弱 / 2 中 / 3 强，见 [TapSound.vibrationSpec]） */
    var tapVibrateStrength: Int
        get() = TapSound.clampVibrationTier(intOr(KEY_TAP_VIBRATE_STRENGTH, TapSound.VIB_DEFAULT))
        set(value) = sp.edit { putInt(KEY_TAP_VIBRATE_STRENGTH, TapSound.clampVibrationTier(value)) }

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
            val raw = strOr(KEY_SKIN_LIGHT, null) ?: return KeyboardSkins.INITIAL_LIGHT_ID
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
            val raw = strOr(KEY_SKIN_DARK, null) ?: return KeyboardSkins.INITIAL_DARK_ID
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
        val legacy = strOr(KEY_KEYBOARD_SKIN, null) ?: return
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
        get() = boolOr(KEY_VOICE_INPUT, false)
        set(value) = sp.edit { putBoolean(KEY_VOICE_INPUT, value) }

    /**
     * 拼接后的 WebSocket 地址。
     *
     * [host] 与 [port] 的 getter 已保证取值合法（host 经 [normalizeHost] 规范化、port 钳到有效区间），
     * 因此这里的字面拼接不会再产出 OkHttp 会拒绝的地址。
     */
    val wsUrl: String get() = "ws://$host:$port"

    // ── 在线翻译（BYOK：用户自带 API Key，2026-09-30 起） ─────────────────

    /**
     * 翻译总开关（默认关闭）。
     *
     * 关闭时功能面板连「翻译」键都不出现（见 `PinyinKeyboardView.renderFunctionPanel`），
     * 也就不会有任何误触发起的网络请求；开启后仍需在「翻译设置」页填好凭据才可用。
     */
    var translateEnabled: Boolean
        get() = boolOr(KEY_TRANSLATE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_TRANSLATE_ENABLED, value) }

    /**
     * 翻译服务提供方 id（见 [TranslationProviderId]）。
     *
     * 键**缺失**（用户从未动过服务方下拉）时按「已填的凭据」推导，而不是直接回落当前默认 ——
     * 这是 2026-09-30 第二轮审查发现的升级回归：第一版默认是 Azure，而 `providerTouched` 闸门只在
     * 用户动过下拉时才写键，于是「填了 Azure Key、从没碰过下拉」的老用户升级后会落到新默认（阿里云），
     * 凭据为空 ⇒ 翻译直接不可用、提示还指向「填写阿里云凭据」；更糟的是导出会把推导结果写进备份，
     * 换机后**永远回不去**（与 rareTier3「升级丢档 → 备份固化」同一失效模式）。
     *
     * 只做「读取时推导」、不写盘：IME、设置页摘要、备份导出三条路径读到同一个值，且无副作用；
     * 用户一碰下拉就会写入显式选择，此后按显式值走。未知取值仍由 [TranslationProviderId.of] 回落。
     */
    var translateProvider: String
        get() {
            val stored = strOr(KEY_TRANSLATE_PROVIDER, null)
            if (stored != null) return TranslationProviderId.of(stored).id
            return TranslationProviderId.entries.firstOrNull { hasCredentialFor(it) }?.id
                ?: TranslationProviderId.DEFAULT.id
        }
        set(value) = sp.edit { putString(KEY_TRANSLATE_PROVIDER, TranslationProviderId.of(value).id) }

    /**
     * 该家是否已有可用凭据（仅供上面「键缺失时推导」使用）。
     *
     * 判据必须与 [providerOf] **逐字对齐**：那里所有凭据都先走 [cleanCredential]（剥零宽 / BOM / NBSP
     * 再 trim），这里若用 `isNotBlank()`，一个纯零宽字符的「假凭据」会让摘要说「已配置」而点翻译
     * 说「未配置」（2026-10-01 复审 L-231：L-228 只对齐了 joinUrl 那半）。
     */
    private fun hasCredentialFor(id: TranslationProviderId): Boolean = when (id) {
        TranslationProviderId.ALIYUN ->
            aliyunAccessKeyId.cleanCredential().isNotEmpty() &&
                aliyunAccessKeySecret.cleanCredential().isNotEmpty()

        TranslationProviderId.AZURE -> azureApiKey.cleanCredential().isNotEmpty()

        TranslationProviderId.BAIDU ->
            baiduAppId.cleanCredential().isNotEmpty() && baiduSecretKey.cleanCredential().isNotEmpty()

        TranslationProviderId.BAIDU_LLM ->
            baiduLlmAppId.cleanCredential().isNotEmpty() && baiduLlmApiKey.cleanCredential().isNotEmpty()

        TranslationProviderId.DEEPL -> deeplApiKey.cleanCredential().isNotEmpty()

        // OpenAI 兼容多一道端点校验（2026-10-01 审查 L-228）：`providerOf` 还要求 joinUrl 能解析出
        // URL，少了这一条会出现「摘要说已配置、点翻译说未配置」的自相矛盾。
        // ⚠ 还要求**必须是 https**（2026-10-03 修复 L-513）：只判「能解析」时 `http://` 会一路放行到
        // 「已配置：…」摘要，直到第一次点翻译才被 `INSECURE` 拒掉 —— 配置信任链在这里断裂，
        // 而平台层对用户自填的域名没有兜底（见 `network_security_config` 的说明）。
        // ⚠ 2026-10-03 修复 L-811：判据收敛到 `OpenAiTranslator.isReady`（**唯一真源**）——
        // 此前这份「端点必须 https」的事实只写在运行链路的对面（`providerOf` 只判「能解析」），
        // 两边分叉 ⇒ `http://` 端点时摘要说已配置、点翻译必报 INSECURE。这里不再自己拼判据。
        TranslationProviderId.OPENAI -> OpenAiTranslator.isReady(
            openAiApiKey, openAiModel, openAiBaseUrl, openAiChatPath,
        )
    }

    /**
     * 当前生效的服务方是否**凭据完整可用**（含 OpenAI 兼容的 https 端点要求）。
     *
     * ⚠ 设置页**不要**用它判「保存提示」（2026-10-03 修复 L-546）：那边的判据是「本页选中的那家」，
     * 而本方法走全局 `translateProvider`，在键未写入时会逐家推导 ⇒ 已配好 DeepL 的用户编辑
     * 「阿里云」时会被判成"已配置"。本方法保留给「当前生效服务方是否可用」这类全局问句。
     */
    internal fun currentProviderConfigured(): Boolean =
        hasCredentialFor(TranslationProviderId.of(translateProvider))

    /**
     * 「翻译不可用」的**成因**（2026-10-03 修复 L-815）。
     *
     * 为什么要分因：`providerOf` 把「凭据齐备 + 端点是 https」收成**一个**判据（`isReady`）之后，
     * `null` 这个结果就**不再区分**「Key / 模型名没填」与「Base URL 写成 `http://`」——
     * 而这两种情况下用户该做的事完全相反（去填凭据 vs 去改地址）。
     * 合并判据时若不同时把「未满足」这一态变成可分因，就会把**有用的错误归因一起吞掉**：
     * http 端点用户会被引导去检查已经填好的 Key 与模型名。
     *
     * 判据与 [hasCredentialFor] / `providerOf` 同源（端点部分走 [OpenAiTranslator.endpointReady]），
     * 本方法只做**归因**，不新增事实。
     */
    internal fun translateNotReadyReason(): TranslateNotReady {
        val id = TranslationProviderId.of(translateProvider)
        if (id != TranslationProviderId.OPENAI) {
            return if (hasCredentialFor(id)) TranslateNotReady.NONE else TranslateNotReady.MISSING_CREDENTIAL
        }
        // OpenAI 兼容：先判凭据、再判端点 —— 顺序反了会把「没填 Key」说成「地址要 https」
        if (openAiApiKey.cleanCredential().isEmpty() || openAiModel.cleanCredential().isEmpty()) {
            return TranslateNotReady.MISSING_CREDENTIAL
        }
        return if (OpenAiTranslator.endpointReady(openAiBaseUrl, openAiChatPath)) {
            TranslateNotReady.NONE
        } else {
            TranslateNotReady.ENDPOINT
        }
    }

    /** [translateNotReadyReason] 的取值（`NONE` = 可用）。 */
    internal enum class TranslateNotReady {
        /** 凭据与端点都齐备，可以发请求 */
        NONE,

        /** 凭据没填 / 填了空白：用户该去「翻译设置」或 OpenAI 配置页填 Key 与模型名 */
        MISSING_CREDENTIAL,

        /**
         * 凭据齐备但端点不合格（`http://` 或解析不出来）：用户该去改 Base URL。
         * 与 [MISSING_CREDENTIAL] 必须分开 —— 合并会把用户引向错误的页面。
         */
        ENDPOINT,
    }

    // ── 「哪些算翻译原文」：范围模式 + 单次字节上限，**按 Provider 各一份**（用户 2026-09-30 定）──

    /**
     * 「哪些内容算翻译原文」的模式（见 [TranslationScope]），每家 Provider 各一份。
     *
     * 存 id 字符串并走 [TranslationScope.of] 归一：脏备份（未知 id）不会带进运行期。
     */
    internal fun translateScopeOf(id: TranslationProviderId): String =
        TranslationScope.of(strOr(scopeKeyOf(id), null)).id

    internal fun setTranslateScopeOf(id: TranslationProviderId, value: String) {
        sp.edit {
            val v = TranslationScope.of(value).id
            // 等于默认就**删键**（2026-10-02 修复 L-425 的后半）：与 [setTranslateMaxBytesOf] 同款 ——
            // 否则用户只要在「原文范围」页选过一次（哪怕选回默认档），键就落下去了，导出段
            // 的 `putIfSetString` 见键存在便把**当时的默认**固化成显式值，跨版本默认调整拿不到。
            if (v == TranslationScope.DEFAULT.id) {
                remove(scopeKeyOf(id))
            } else {
                putString(scopeKeyOf(id), v)
            }
        }
    }

    /**
     * 单次翻译的字节上限（UTF-8 字节；超出时从头截断），每家 Provider 各一份。
     *
     * 键缺失时取 [TranslationProviderId.defaultMaxBytes]（已查到的官方值）；读写两侧都钳到
     * [TranslationText.MIN_MAX_BYTES]..[TranslationText.MAX_MAX_BYTES] —— 导入的是一份外部文件，
     * `0` 或 `Int.MAX_VALUE` 这类值不钳住就会进运行期（前者让原文恒为空、后者绕过读取上限）。
     */
    internal fun translateMaxBytesOf(id: TranslationProviderId): Int {
        val stored = intOr(maxBytesKeyOf(id), id.defaultMaxBytes)
        // `< MIN` 视为「未设置」⇒ 回落该家默认（2026-10-02 修复 L-434）：界面里「清空 / 0」的
        // 语义是「恢复出厂默认」，而 `coerceIn` 会把它钳成 1 —— 手改或损坏的备份
        // （`translate_max_bytes_x: 0`）导入后该家会变成「每次只翻 1 字节」，
        // 叠加「读取窗口跟着上限走」（L-427）后两档必然失败、提示还归因错误。
        return if (stored < TranslationText.MIN_MAX_BYTES) {
            id.defaultMaxBytes
        } else {
            stored.coerceAtMost(TranslationText.MAX_MAX_BYTES)
        }
    }

    internal fun setTranslateMaxBytesOf(id: TranslationProviderId, value: Int) {
        sp.edit {
            val v = if (value < TranslationText.MIN_MAX_BYTES) {
                id.defaultMaxBytes
            } else {
                value.coerceAtMost(TranslationText.MAX_MAX_BYTES)
            }
            // 等于该家默认就**删键**（2026-10-01 修复 L-255）：否则打开过一次「原文范围」页就把这家
            // 钉死在当时的默认值，将来抬高默认（如 DeepL）老用户拿不到
            if (v == id.defaultMaxBytes) {
                remove(maxBytesKeyOf(id))
            } else {
                putInt(maxBytesKeyOf(id), v)
            }
        }
    }

    /**
     * 范围模式的存储键。
     *
     * 用 `when` 而不是拼字符串（`"translate_scope_" + id.id`）：导出 / 导入白名单要显式引用
     * `KEY_*` 常量，[PrefsBackupCoverageTest] 的源码对拍才看得见这些键 —— 拼出来的键名
     * 会让「新增键必须进两份白名单」的守卫静默失效。
     */
    private fun scopeKeyOf(id: TranslationProviderId): String = when (id) {
        TranslationProviderId.ALIYUN -> KEY_TRANSLATE_SCOPE_ALIYUN
        TranslationProviderId.AZURE -> KEY_TRANSLATE_SCOPE_AZURE
        TranslationProviderId.BAIDU -> KEY_TRANSLATE_SCOPE_BAIDU
        TranslationProviderId.BAIDU_LLM -> KEY_TRANSLATE_SCOPE_BAIDU_LLM
        TranslationProviderId.DEEPL -> KEY_TRANSLATE_SCOPE_DEEPL
        TranslationProviderId.OPENAI -> KEY_TRANSLATE_SCOPE_OPENAI
    }

    /** 字节上限的存储键（同上，不拼字符串） */
    private fun maxBytesKeyOf(id: TranslationProviderId): String = when (id) {
        TranslationProviderId.ALIYUN -> KEY_TRANSLATE_MAX_BYTES_ALIYUN
        TranslationProviderId.AZURE -> KEY_TRANSLATE_MAX_BYTES_AZURE
        TranslationProviderId.BAIDU -> KEY_TRANSLATE_MAX_BYTES_BAIDU
        TranslationProviderId.BAIDU_LLM -> KEY_TRANSLATE_MAX_BYTES_BAIDU_LLM
        TranslationProviderId.DEEPL -> KEY_TRANSLATE_MAX_BYTES_DEEPL
        TranslationProviderId.OPENAI -> KEY_TRANSLATE_MAX_BYTES_OPENAI
    }

    // ── 凭据的加密落盘（2026-09-30 起，见 CredentialCrypto）────────────

    private val TAG = "Prefs"

    // 凭据明文的缓存持有者在 companion object 里 —— 必须**进程级**，原因见那里的 KDoc
    // （2026-10-01 修复 L-298）。

    /**
     * 读凭据：缓存 → Keystore 解密 → 明文旧值一次性迁移。
     *
     * 三种情况：
     *  1. 空值 ⇒ 空串（未配置）；
     *  2. **旧版明文** ⇒ 返回明文并把加密结果写回（迁移）；
     *  3. 密文但解不开（换机 / Keystore 失效 / 系统重置）⇒ **返回空串 + W 日志**，等价「未配置」。
     *
     * 第 3 条是关键：**绝不能把密文原文当成 Key 用** —— 那会发一次莫名失败的请求，
     * 而用户看到的是「凭据已配置却认证失败」，比「未配置」难查得多。
     */
    /**
     * 当前**只在内存、未落盘**的凭据键（空集合 = 全部已落盘）。
     *
     * 供两个设置页的显式保存如实报告（2026-10-02 修复 L-355）：加密不可用时（锁屏）值只进内存，
     * 而回读核对比的是同一个 getter（命中缓存）⇒ 必然"相等"，仅靠字符串比对揭不穿 —— 必须由写侧记账。
     */
    internal fun unpersistedCredentialKeys(): Set<String> = unpersistedCredentials.toSet()

    private fun readCredential(key: String, retry: Int = 0): String {
        credentialCache[key]?.let { return it }
        val raw = strOr(key, "").orEmpty()
        if (raw.isEmpty()) return ""
        if (!CredentialCrypto.looksEncrypted(raw)) {
            Diagnostics.i(TAG, "凭据迁移: 明文 → Keystore 密文")
            // ⚠ 顺序是「**先加密、再比对、最后落盘**」（2026-10-02 修复：上一轮把闸放在加密
            // 之前，两次 `getString` 紧邻 ⇒ 必然相等 ⇒ 闸等于没挡）。加密是慢操作（5~20ms），
            // 期间别的 `Prefs` 实例写入新值时，这里会把旧明文重新加密写回磁盘 ——
            // 缓存与**磁盘**一起回退，正是 L-342 要根除的场景（连重启都恢复不回来）。
            // 这里不复用 [writeCredential]：那条路径没有「比对期望旧值」这一步，
            // 而本分支的判据必须在加密之后、提交之前，形态不同。
            val encrypted = CredentialCrypto.encrypt(raw)
            if (strOr(key, "").orEmpty() != raw) {
                Diagnostics.w(TAG, "凭据在迁移期间被改写，改读新值")
                return if (retry >= 1) raw else readCredential(key, retry + 1)
            }
            if (encrypted == null) {
                Diagnostics.w(TAG, "凭据迁移: 加密不可用，只留在内存（重启后需重填）")
                credentialCache[key] = raw
                unpersistedCredentials.add(key)
                sp.edit { remove(key) }
                return raw
            }
            credentialCache[key] = raw
            unpersistedCredentials.remove(key)
            sp.edit { putString(key, encrypted) }
            return raw
        }
        val plain = CredentialCrypto.decrypt(raw)
        if (plain == null) {
            Diagnostics.w(TAG, "凭据解密失败（换机 / Keystore 失效），按未配置处理，需重新填写")
            return ""
        }
        // 解密是慢操作（Keystore 5~20ms），期间别的 `Prefs` 实例可能已经写入了新值 ——
        // 无条件回填会把缓存**盖回旧值**，而「凭据不留痕」随后正是拿这个值去比对剪贴板的，
        // 用户刚复制的新 Key 会因此留在剪贴板历史里（2026-10-01 修复 L-298）。
        if (strOr(key, "").orEmpty() != raw) {
            Diagnostics.w(TAG, "凭据在解密期间被改写，本次不回填缓存（改读新值）")
            // 2026-10-02 修复 L-422：此前这里直接 `return plain` —— 闸门只挡住了"污染缓存"，
            // 没挡住"本次结论已过期"：窗口里发生的若是**删除**（用户清空字段并保存），
            // 紧接着的一次翻译会拿已撤销的凭据发请求。重读一次即收敛（命中新缓存或新磁盘值，
            // 删除场景得空串）；极端并发下最多重试一次，之后退回旧值并留 W，不做无限重读。
            return if (retry >= 1) plain else readCredential(key, retry + 1)
        }
        credentialCache[key] = plain
        return plain
    }

    /**
     * 写凭据：清洗（剥不可见字符）→ 加密 → 落盘；**加密失败绝不回退成明文**（安全优先）。
     *
     * 失败时（Keystore 不可用，如 API 28+ 锁屏 + `setUnlockedDeviceRequired`）只留在内存缓存里，
     * 同时**删掉磁盘上仍可解密的旧密文**（fail-closed：宁可让该家回到「未配置」并如实告知，
     * 也不要悄悄退回旧 Key），并把该键记入 [unpersistedCredentialKeys] 供显式保存如实报告
     * （2026-10-02 修复 L-355：此前缓存命中让回读核对必然相等 ⇒ 报「已保存」，而重启后该家
     * 回到「未配置」，用户与日志都看不出真因）。
     */
    private fun writeCredential(key: String, value: String) {
        val cleaned = value.cleanCredential()
        if (cleaned.isEmpty()) {
            credentialCache.remove(key)
            unpersistedCredentials.remove(key)
            sp.edit { remove(key) }
            return
        }
        val encrypted = CredentialCrypto.encrypt(cleaned)
        if (encrypted == null) {
            Diagnostics.w(TAG, "凭据加密失败，未落盘（重启后需重新填写）")
            credentialCache[key] = cleaned
            unpersistedCredentials.add(key)
            sp.edit { remove(key) }
            return
        }
        credentialCache[key] = cleaned
        unpersistedCredentials.remove(key)
        sp.edit { putString(key, encrypted) }
    }

    /** 目标语言（见 [TranslationLanguage]）；未知取值回落英文 */
    var translateTarget: String
        get() = TranslationLanguage.of(strOr(KEY_TRANSLATE_TARGET, null)).name
        set(value) = sp.edit { putString(KEY_TRANSLATE_TARGET, TranslationLanguage.of(value).name) }

    /**
     * Azure Translator 订阅密钥；作者不提供、不内置任何共享 Key。
     *
     * **加密落盘**（2026-09-30 起）：走 [readCredential] / [writeCredential] —— Keystore AES-GCM 密文，
     * 密钥材料不出安全硬件；旧版明文值在首次读取时自动迁移。
     */
    var azureApiKey: String
        get() = readCredential(KEY_AZURE_API_KEY)
        set(value) = writeCredential(KEY_AZURE_API_KEY, value)

    /**
     * Azure 资源区域（多服务/区域资源必填；单区域全局资源留空即可）—— 非机密，明文存。
     *
     * **读写两侧都归一**（2026-10-02 修复 L-347）：门户显示的是 `East Asia`，而该头要求
     * `eastasia`。写侧归一让"照抄门户"直接可用；读侧归一让**存量脏值**（升级前填的 `East Asia`）
     * 不必用户重填就自愈 —— 否则该类用户会一直 401，而提示只说「检查凭据」。
     */
    var azureRegion: String
        get() = normalizeAzureRegion(strOr(KEY_AZURE_REGION, "").orEmpty())
        set(value) = sp.edit { putString(KEY_AZURE_REGION, normalizeAzureRegion(value)) }

    /** 百度翻译开放平台 AppID（**加密落盘**，见 [readCredential]） */
    var baiduAppId: String
        get() = readCredential(KEY_BAIDU_APP_ID)
        set(value) = writeCredential(KEY_BAIDU_APP_ID, value)

    /** 百度翻译开放平台密钥（只参与本地 MD5 签名，绝不进日志与异常信息；**加密落盘**） */
    var baiduSecretKey: String
        get() = readCredential(KEY_BAIDU_SECRET_KEY)
        set(value) = writeCredential(KEY_BAIDU_SECRET_KEY, value)

    /** 阿里云机器翻译 AccessKey ID（默认服务方，见 [TranslationProviderId.DEFAULT]；**加密落盘**） */
    var aliyunAccessKeyId: String
        get() = readCredential(KEY_ALIYUN_ACCESS_KEY_ID)
        set(value) = writeCredential(KEY_ALIYUN_ACCESS_KEY_ID, value)

    /** 阿里云机器翻译 AccessKey Secret（进 HMAC-SHA1 签名，绝不进日志；**加密落盘**） */
    var aliyunAccessKeySecret: String
        get() = readCredential(KEY_ALIYUN_ACCESS_KEY_SECRET)
        set(value) = writeCredential(KEY_ALIYUN_ACCESS_KEY_SECRET, value)

    /** DeepL API Key（Free 密钥以 `:fx` 结尾，Free / Pro 域名由它自动判定；**加密落盘**） */
    var deeplApiKey: String
        get() = readCredential(KEY_DEEPL_API_KEY)
        set(value) = writeCredential(KEY_DEEPL_API_KEY, value)

    /** 百度大模型文本翻译 APPID（与通用文本翻译是两个服务，分开开通与计费；**加密落盘**） */
    var baiduLlmAppId: String
        get() = readCredential(KEY_BAIDU_LLM_APP_ID)
        set(value) = writeCredential(KEY_BAIDU_LLM_APP_ID, value)

    /** 百度大模型文本翻译 API Key（Bearer 鉴权；**加密落盘**） */
    var baiduLlmApiKey: String
        get() = readCredential(KEY_BAIDU_LLM_API_KEY)
        set(value) = writeCredential(KEY_BAIDU_LLM_API_KEY, value)

    /**
     * OpenAI 兼容：配置名称（仅用于列表与摘要显示，如 `DeepSeek` / `本地 Ollama`）。
     *
     * 多配置档（新增 / 复制 / 删除 / 导入导出）排在下一轮；本轮先把单档的每一项配置都打开。
     */
    /**
     * 「留空即默认」字段的统一读法（2026-10-01 修复 L-251）。
     *
     * 设置页会把 getter 的**默认值**灌进输入框，用户打开一次页面再返回就会回写一次，所以存量里
     * 随时可能出现「恰好等于默认值」的字面量。写入侧由 [putDefaulted] 保证它不再堆积（等于默认就
     * 删键），读取侧这里只做一件事：空串回落默认值。
     *
     * ⚠ 此前这里对「等于默认值」的存量**返回空串**（想用「值为空」表达「没配过」）。出发点可以理解，
     * 但八个字段里 `openAiBaseUrl` 的下游 [OpenAiTranslator.joinUrl] 拿到空串是**直接失败**而不是
     * 回落默认 —— 于是「打开一次 OpenAI 设置页再返回」就让 OpenAI 兼容翻译整体不可用，而且重填
     * 同一个 URL 也无效（2026-10-01 审查 L-293）。同款症状还让提示词框显示为空、并误报缺
     * `{{text}}`（L-294）。「未配置」这个概念由写入侧的**删键**表达，不能由读值表达。
     */
    private fun defaulted(stored: String, fallback: String): String = stored.ifBlank { fallback }

    /**
     * 「留空即默认」字段的统一写法（与 [defaulted] 配对，2026-10-01 修复 L-293）。
     *
     * 清洗后为空、或恰好等于默认值 ⇒ **删键**，不写死字面量 —— 这样 App 升级默认值时老用户能跟着
     * 拿到新值（口径同 [openAiTimeoutSec] 的 setter，2026-10-01 修复 L-255）。
     *
     * 清洗分两档（2026-10-03 修复 L-715）：
     *  - [multiline] = true（提示词 / 配置名 / 目标语言）走 [cleanPromptText]，**保留换行** ——
     *    这些字段本就是多行文本（设置页 `textMultiLine`，且默认提示词自带 `\n`），用严格版会把它压平，
     *    还会让下面「值 == 默认值就删键」的判据失效（默认提示词被剥成单行 ⇒ 显式写键 ⇒ 将来改默认值
     *    这批用户永远拿不到新默认）。
     *  - 其余（Base URL / 三个 path）走 [cleanCredential] 严格版：这些值要进 URL 与 HTTP 头，
     *    换行是**非法**字符，留着会让 [OpenAiTranslator.joinUrl] 直接解析失败。
     */
    private fun putDefaulted(
        key: String,
        value: String,
        fallback: String,
        multiline: Boolean = false,
    ) = sp.edit {
        val v = if (multiline) value.cleanPromptText() else value.cleanCredential()
        if (v.isEmpty() || v == fallback) remove(key) else putString(key, v)
    }

    var openAiName: String
        get() = defaulted(strOr(KEY_OPENAI_NAME, "").orEmpty(), OpenAiTranslator.DEFAULT_PROFILE_NAME)
        set(value) = putDefaulted(KEY_OPENAI_NAME, value, OpenAiTranslator.DEFAULT_PROFILE_NAME, multiline = true)

    /**
     * OpenAI 兼容服务的 Base URL（默认官方 `https://api.openai.com/v1`）。
     *
     * 原样保存用户输入（只剥不可见字符，不补 `https://`、不剥尾部端点）：规范化放在
     * [OpenAiTranslator.joinUrl] 里做 —— 存归一后的值会让用户看不到自己填的是什么，出问题时无从对照。
     *
     * ⚠ 与另外三个路径键同口径走 [putDefaulted]（2026-10-01 修复 L-288）：`joinUrl` 内部只做
     * `trim()`，挡不住从网页复制时混进来的 NBSP / 零宽字符，而解析结果在两处被消费
     *（[hasCredentialFor] 与 [openAiConfig]）—— 口径不一致就会出现「摘要说未配置、却能翻译」。
     */
    var openAiBaseUrl: String
        get() = defaulted(strOr(KEY_OPENAI_BASE_URL, "").orEmpty(), OpenAiTranslator.DEFAULT_BASE_URL)
        set(value) = putDefaulted(KEY_OPENAI_BASE_URL, value, OpenAiTranslator.DEFAULT_BASE_URL)

    /** OpenAI 兼容服务的 API Key（Bearer 鉴权；**加密落盘**，界面与日志都不回显） */
    var openAiApiKey: String
        get() = readCredential(KEY_OPENAI_API_KEY)
        set(value) = writeCredential(KEY_OPENAI_API_KEY, value)

    /** OpenAI 兼容服务的模型名（如 `gpt-4o-mini` / `qwen3.8-flash-free`）；空 = 未配置 */
    var openAiModel: String
        get() = strOr(KEY_OPENAI_MODEL, "").orEmpty()
        // 与同页其它字符串字段同口径：走 cleanCredential 去掉复制粘贴带进来的 NBSP / ZWSP /
        // 全角空格（2026-10-02 第二轮审查）。只 trim 时「是否已配置」的判据看着正常，
        // 请求真发出去必然 404 / 参数被拒 ⇒ 归 PARAM/AUTH，用户按提示核对 Key、路径、语言都查不出真因。
        set(value) = sp.edit { putString(KEY_OPENAI_MODEL, value.cleanCredential()) }

    /** 对话端点路径（默认 `/chat/completions`；换网关只改这一项即可，不必等 App 更新） */
    var openAiChatPath: String
        get() = defaulted(strOr(KEY_OPENAI_CHAT_PATH, "").orEmpty(), OpenAiTranslator.DEFAULT_CHAT_PATH)
        set(value) = putDefaulted(KEY_OPENAI_CHAT_PATH, value, OpenAiTranslator.DEFAULT_CHAT_PATH)

    /** 模型列表端点路径（默认 `/models`；「获取模型」用它，服务端没实现也不影响翻译） */
    var openAiModelsPath: String
        get() = defaulted(strOr(KEY_OPENAI_MODELS_PATH, "").orEmpty(), OpenAiTranslator.DEFAULT_MODELS_PATH)
        set(value) = putDefaulted(KEY_OPENAI_MODELS_PATH, value, OpenAiTranslator.DEFAULT_MODELS_PATH)

    /** 目标语言（自由文本：简体中文 / 繁體中文（台灣）/ 粤语 / 古文…，进 `{{target_language}}`） */
    var openAiTargetLanguage: String
        get() = defaulted(
            strOr(KEY_OPENAI_TARGET_LANGUAGE, "").orEmpty(),
            OpenAiTranslator.DEFAULT_TARGET_LANGUAGE,
        )
        // 目标语言是单行值，但用户可能从网页复制带零宽/全角空格的形态 ⇒ 仍走宽松版（L-715）
        set(value) = putDefaulted(KEY_OPENAI_TARGET_LANGUAGE, value, OpenAiTranslator.DEFAULT_TARGET_LANGUAGE, multiline = true)

    /** System 提示词（可整段改写；支持 `{{text}}` / `{{target_language}}` / `{{source_language}}` / `{{date}}`） */
    var openAiSystemPrompt: String
        get() = defaulted(strOr(KEY_OPENAI_SYSTEM_PROMPT, "").orEmpty(), OpenAiTranslator.DEFAULT_SYSTEM_PROMPT)
        // ⚠ `multiline = true`：提示词**保留换行**（L-715）。用严格版会把多行提示词压成一行，
        // 还会让「值 == 默认值就删键」失效 ⇒ 恢复默认后变成显式写键 ⇒ 将来改默认值这批用户拿不到新值。
        // （写成单行是刻意的：守卫要按 `putDefaulted(<键名>` 定位，跨行会让锚点失配）
        set(value) = putDefaulted(KEY_OPENAI_SYSTEM_PROMPT, value, OpenAiTranslator.DEFAULT_SYSTEM_PROMPT, multiline = true)

    /** User 提示词模板（同上；变量替换见 [OpenAiTranslator.applyTemplate]） */
    var openAiUserPrompt: String
        get() = defaulted(strOr(KEY_OPENAI_USER_PROMPT, "").orEmpty(), OpenAiTranslator.DEFAULT_USER_PROMPT)
        // 默认模板本身就含一个 `\n`（`…只返回译文：\n{{text}}`）⇒ 同样必须走宽松版（L-715）
        set(value) = putDefaulted(KEY_OPENAI_USER_PROMPT, value, OpenAiTranslator.DEFAULT_USER_PROMPT, multiline = true)

    /** Temperature；**空串 = 不发送该参数**（部分推理模型不接受它） */
    var openAiTemperature: String
        get() = strOr(KEY_OPENAI_TEMPERATURE, "").orEmpty()
        set(value) = sp.edit { putString(KEY_OPENAI_TEMPERATURE, value.trim()) }

    /** Top P；空串 = 不发送 */
    var openAiTopP: String
        get() = strOr(KEY_OPENAI_TOP_P, "").orEmpty()
        set(value) = sp.edit { putString(KEY_OPENAI_TOP_P, value.trim()) }

    /** Max Tokens；空串 = 不发送 */
    var openAiMaxTokens: String
        get() = strOr(KEY_OPENAI_MAX_TOKENS, "").orEmpty()
        set(value) = sp.edit { putString(KEY_OPENAI_MAX_TOKENS, value.trim()) }

    /** 自定义请求头（一行一条 `Key: Value`；`Authorization` 会被忽略，它由 API Key 字段独占） */
    var openAiExtraHeaders: String
        get() = strOr(KEY_OPENAI_EXTRA_HEADERS, "").orEmpty()
        // 写入侧与导入侧**共用同一长度闸**（2026-10-02 修复 L-375）：此前闸只在导入侧（[asString]），
        // 而导出侧不设限 ⇒ 自产的包会被自己的导入端拒收该键 —— 直接违反本仓「自产的包必须自己导得
        // 回来」的约定，且清单说「包含」、导入说「忽略」，两个数字互相矛盾。
        set(value) = sp.edit { putString(KEY_OPENAI_EXTRA_HEADERS, value.capForBackup(KEY_OPENAI_EXTRA_HEADERS)) }

    /** 自定义请求体 JSON（**最高优先级**：同名键覆盖标准参数，厂商私有参数写这里） */
    var openAiExtraJson: String
        get() = strOr(KEY_OPENAI_EXTRA_JSON, "").orEmpty()
        // 写入侧闸同 [openAiExtraHeaders]（2026-10-02 修复 L-375）：超限截尾后会是非法 JSON，
        // 设置页的 `isValidExtraJson` 会当场报错 —— 比「导出成功、导入静默丢键」好
        set(value) = sp.edit { putString(KEY_OPENAI_EXTRA_JSON, value.capForBackup(KEY_OPENAI_EXTRA_JSON)) }

    /** 响应解析路径（默认 `choices[0].message.content`；换协议如 `output_text` 只改这一项） */
    var openAiResponsePath: String
        get() = defaulted(strOr(KEY_OPENAI_RESPONSE_PATH, "").orEmpty(), OpenAiTranslator.DEFAULT_RESPONSE_PATH)
        set(value) = putDefaulted(KEY_OPENAI_RESPONSE_PATH, value, OpenAiTranslator.DEFAULT_RESPONSE_PATH)

    /** 整体超时（秒）：大模型首字延迟不可控，钳到 5~300 秒 */
    var openAiTimeoutSec: Int
        get() = intOr(KEY_OPENAI_TIMEOUT_SEC, OpenAiTranslator.DEFAULT_TIMEOUT_SEC)
            .coerceIn(TIMEOUT_MIN_SEC, TIMEOUT_MAX_SEC)
        // 等于默认值就**删键**而不是写死（2026-10-01 修复 L-255）：口径同 [defaulted] ——
        // 否则「打开过一次设置页」会把当时的默认超时固化，将来调大默认对老用户无效
        set(value) = sp.edit {
            val v = value.coerceIn(TIMEOUT_MIN_SEC, TIMEOUT_MAX_SEC)
            if (v == OpenAiTranslator.DEFAULT_TIMEOUT_SEC) {
                remove(KEY_OPENAI_TIMEOUT_SEC)
            } else {
                putInt(KEY_OPENAI_TIMEOUT_SEC, v)
            }
        }

    /** 最近一次成功获取的模型列表缓存（换行分隔；接口暂时不可用时下拉仍可用） */
    var openAiModelsCache: String
        get() = strOr(KEY_OPENAI_MODELS_CACHE, "").orEmpty()
        // 写入侧闸（2026-10-02 修复 L-375）：这个字段是**服务端可控**的（`/models` 返回几千条时
        // 拼出的名字串可超 64 KB），正是最容易撞上导入侧闸的一个 —— 截尾即可（缓存丢了可重取）
        set(value) = sp.edit {
            putString(KEY_OPENAI_MODELS_CACHE, value.trim().capForBackup(KEY_OPENAI_MODELS_CACHE))
        }

    /** 组装 OpenAI 兼容 Provider 的配置（UI / Prefs / 翻译器共用一处口径） */
    internal val openAiConfig: OpenAiConfig
        get() = OpenAiConfig(
            baseUrl = openAiBaseUrl,
            apiKey = openAiApiKey,
            model = openAiModel,
            chatPath = openAiChatPath,
            modelsPath = openAiModelsPath,
            targetLanguage = openAiTargetLanguage,
            systemPrompt = openAiSystemPrompt,
            userPrompt = openAiUserPrompt,
            temperature = openAiTemperature,
            topP = openAiTopP,
            maxTokens = openAiMaxTokens,
            extraHeaders = openAiExtraHeaders,
            extraJson = openAiExtraJson,
            responsePath = openAiResponsePath,
            timeoutSec = openAiTimeoutSec,
        )

    /**
     * 按当前配置组装翻译 Provider；凭据不全时返回 null（调用方据此提示「先配置」）。
     *
     * 取值口径只此一处：IME 的「翻译」键与设置页的状态摘要都走它，避免两边各列一遍参数。
     */
    internal fun translationProvider(): TranslationProvider? {
        val id = TranslationProviderId.of(translateProvider)
        return translationProvider(id)
    }

    /**
     * 按 [id] 组装 Provider —— **只读该家的凭据**（2026-10-03 修复 L-530 / L-531）。
     *
     * 此前把关凭据的 10 个 getter 全部作为 `providerOf` 的参数求值（Kotlin 的参数在调用前求值）
     * ⇒ 冷缓存下一次翻译要在**主线程**做最多 8 次 TEE 解密（5~20ms/次 = **40~160ms 停顿**），
     * 而本次只用其中一家；且 `translateProvider` 的 getter 在键未写入时会**逐家探测凭据**
     * （首次使用者必然如此），同帧还会被求值两次。现在 id 只求值一次、凭据只读该家。
     *
     * 调用方若已持有 id（如设置页的下拉选中项），直接传进来即可复用同一次求值。
     */
    internal fun translationProvider(id: TranslationProviderId): TranslationProvider? = when (id) {
        TranslationProviderId.ALIYUN -> TranslationClient.providerOf(
            id,
            aliyunKeyId = aliyunAccessKeyId,
            aliyunKeySecret = aliyunAccessKeySecret,
        )

        TranslationProviderId.AZURE -> TranslationClient.providerOf(
            id,
            azureKey = azureApiKey,
            azureRegion = azureRegion,
        )

        TranslationProviderId.BAIDU -> TranslationClient.providerOf(
            id,
            baiduAppId = baiduAppId,
            baiduSecret = baiduSecretKey,
        )

        TranslationProviderId.BAIDU_LLM -> TranslationClient.providerOf(
            id,
            baiduLlmAppId = baiduLlmAppId,
            baiduLlmApiKey = baiduLlmApiKey,
        )

        TranslationProviderId.DEEPL -> TranslationClient.providerOf(id, deeplKey = deeplApiKey)

        TranslationProviderId.OPENAI -> TranslationClient.providerOf(id, openAi = openAiConfig)
    }

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
        put(KEY_SHOW_QUANPIN, showQuanpin)
        put(KEY_USER_LEARNING, userLearning)
        put(KEY_RARE_TIER2, rareTier2)
        put(KEY_RARE_TIER3, rareTier3)
        put(KEY_USE_TRADITIONAL, useTraditional)
        put(KEY_FUZZY_PINYIN, fuzzyPinyinMask)
        put(KEY_VOICE_INPUT, voiceInputEnabled)
        put(KEY_TRANSLATE_ENABLED, translateEnabled)
        // ⚠ 这两个键只在**用户真的设置过**时才导出（2026-10-03 修复 L-537）：`translateProvider`
        // 的 getter 在键缺失时按「谁有凭据」逐家推导、`translateTarget` 会回落默认 ⇒ 无条件导出
        // 会把「未设置」固化成显式值。后果：新机上先配好 DeepL，再导入一份旧包（导出时用户还没选过
        // 服务方）⇒ provider 被钉死为推导值，点翻译报「请先填写凭据」而 DeepL 明明已配置。
        // L-425 已为 12 个 scope / maxBytes 键修过同一类问题，这里是漏掉的两个。
        if (sp.contains(KEY_TRANSLATE_PROVIDER)) put(KEY_TRANSLATE_PROVIDER, translateProvider)
        if (sp.contains(KEY_TRANSLATE_TARGET)) put(KEY_TRANSLATE_TARGET, translateTarget)
        // 「哪些算原文」按 Provider 各一份：逐家显式导出（白名单里必须看得见常量名，
        // 否则 PrefsBackupCoverageTest 的源码对拍抓不到这些键）。
        //
        // ⚠ 这 12 个键只导出**用户真的设置过**的（2026-10-02 修复 L-425）：getter 在键缺失时
        // 返回出厂默认，无条件导出会把「当时的默认」固化成显式值 —— 跨版本导入时（默认值已改，
        // 例如 L-307 把百度大模型从 32768 降到 6000）它会被当成用户显式值写回，把默认值的
        // 调整整个吃掉。`putIfSet*` 让「未设置」保持为一个**状态**而不是一个值。
        // 取一次原始值快照、**不做类型假设**（2026-10-02 修复）：
        //  · `getInt` / `getString` 对**类型不符**的键会抛 `ClassCastException`（不是返回默认），
        //    而备份导入允许外部文件写进任意类型 ⇒ 一个脏键就让**整次导出**失败，外层只有一句
        //    通用失败文案，用户不知道是哪个键；
        //  · `contains` → 再读之间还有竞态（导出跑在后台线程）：期间别的实例 `remove` 掉该键时，
        //    int 侧会读回默认 `0` 并被当成真实值导出。一次快照把两个问题一起消掉。
        val rawValues: Map<String, *> = runCatching { sp.all }.getOrNull().orEmpty()
        fun putIfSetString(exportKey: String, storeKey: String) {
            val v = rawValues[storeKey] as? String ?: return
            put(exportKey, v)
        }
        fun putIfSetInt(exportKey: String, storeKey: String) {
            val v = rawValues[storeKey] as? Int ?: return
            put(exportKey, v)
        }
        putIfSetString(KEY_TRANSLATE_SCOPE_ALIYUN, scopeKeyOf(TranslationProviderId.ALIYUN))
        putIfSetInt(KEY_TRANSLATE_MAX_BYTES_ALIYUN, maxBytesKeyOf(TranslationProviderId.ALIYUN))
        putIfSetString(KEY_TRANSLATE_SCOPE_AZURE, scopeKeyOf(TranslationProviderId.AZURE))
        putIfSetInt(KEY_TRANSLATE_MAX_BYTES_AZURE, maxBytesKeyOf(TranslationProviderId.AZURE))
        putIfSetString(KEY_TRANSLATE_SCOPE_BAIDU, scopeKeyOf(TranslationProviderId.BAIDU))
        putIfSetInt(KEY_TRANSLATE_MAX_BYTES_BAIDU, maxBytesKeyOf(TranslationProviderId.BAIDU))
        putIfSetString(KEY_TRANSLATE_SCOPE_BAIDU_LLM, scopeKeyOf(TranslationProviderId.BAIDU_LLM))
        putIfSetInt(KEY_TRANSLATE_MAX_BYTES_BAIDU_LLM, maxBytesKeyOf(TranslationProviderId.BAIDU_LLM))
        putIfSetString(KEY_TRANSLATE_SCOPE_DEEPL, scopeKeyOf(TranslationProviderId.DEEPL))
        putIfSetInt(KEY_TRANSLATE_MAX_BYTES_DEEPL, maxBytesKeyOf(TranslationProviderId.DEEPL))
        putIfSetString(KEY_TRANSLATE_SCOPE_OPENAI, scopeKeyOf(TranslationProviderId.OPENAI))
        putIfSetInt(KEY_TRANSLATE_MAX_BYTES_OPENAI, maxBytesKeyOf(TranslationProviderId.OPENAI))
        put(KEY_AZURE_API_KEY, azureApiKey)
        put(KEY_AZURE_REGION, azureRegion)
        put(KEY_BAIDU_APP_ID, baiduAppId)
        put(KEY_BAIDU_SECRET_KEY, baiduSecretKey)
        put(KEY_ALIYUN_ACCESS_KEY_ID, aliyunAccessKeyId)
        put(KEY_ALIYUN_ACCESS_KEY_SECRET, aliyunAccessKeySecret)
        put(KEY_DEEPL_API_KEY, deeplApiKey)
        put(KEY_BAIDU_LLM_APP_ID, baiduLlmAppId)
        put(KEY_BAIDU_LLM_API_KEY, baiduLlmApiKey)
        put(KEY_OPENAI_NAME, openAiName)
        put(KEY_OPENAI_BASE_URL, openAiBaseUrl)
        put(KEY_OPENAI_API_KEY, openAiApiKey)
        put(KEY_OPENAI_MODEL, openAiModel)
        put(KEY_OPENAI_CHAT_PATH, openAiChatPath)
        put(KEY_OPENAI_MODELS_PATH, openAiModelsPath)
        put(KEY_OPENAI_TARGET_LANGUAGE, openAiTargetLanguage)
        put(KEY_OPENAI_SYSTEM_PROMPT, openAiSystemPrompt)
        put(KEY_OPENAI_USER_PROMPT, openAiUserPrompt)
        put(KEY_OPENAI_TEMPERATURE, openAiTemperature)
        put(KEY_OPENAI_TOP_P, openAiTopP)
        put(KEY_OPENAI_MAX_TOKENS, openAiMaxTokens)
        put(KEY_OPENAI_EXTRA_HEADERS, openAiExtraHeaders)
        put(KEY_OPENAI_EXTRA_JSON, openAiExtraJson)
        put(KEY_OPENAI_RESPONSE_PATH, openAiResponsePath)
        put(KEY_OPENAI_TIMEOUT_SEC, openAiTimeoutSec)
        put(KEY_OPENAI_MODELS_CACHE, openAiModelsCache)
        put(KEY_KEY_CORNER_DP, keyCornerDp)
        put(KEY_KEY_GAP_DP, keyGapDp)
        put(KEY_CANDIDATE_SPACING_DP, candidateSpacingDp)
        put(KEY_CANDIDATE_TEXT_SP, candidateTextSp)
        put(KEY_KEY_TRANSPARENCY_PERCENT, keyTransparencyPercent)
        // 敲击音效反馈：音色选择与震动档位都是用户偏好，换机必须带走（见 KEY_TAP_* 注释）
        put(KEY_TAP_SOUND_ENABLED, tapSoundEnabled)
        put(KEY_TAP_SOUND_VOLUME, tapSoundVolume)
        put(KEY_TAP_SOUND_ON_SILENT, tapSoundOnSilent)
        // ⚠ 分组映射只在**用户真的改过**时才导出（与 translate_provider 同款）：缺键时 getter
        // 回落的是当前默认映射，无条件导出会把「那时的默认」固化成显式值 —— 将来调整默认音色
        // （例如替换删除键那个警报音）对这批用户失效。
        if (sp.contains(KEY_TAP_SOUND_MAP)) put(KEY_TAP_SOUND_MAP, tapSoundMap)
        put(KEY_TAP_VIBRATE_ENABLED, tapVibrateEnabled)
        put(KEY_TAP_VIBRATE_STRENGTH, tapVibrateStrength)
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
                KEY_SHOW_QUANPIN -> asBool(v)?.let { showQuanpin = it; ok() } ?: bad(key)
                KEY_USER_LEARNING -> asBool(v)?.let { userLearning = it; ok() } ?: bad(key)
                KEY_RARE_TIER2 -> asBool(v)?.let { rareTier2 = it; ok() } ?: bad(key)
                KEY_RARE_TIER3 -> asBool(v)?.let { rareTier3 = it; ok() } ?: bad(key)
                KEY_USE_TRADITIONAL -> asBool(v)?.let { useTraditional = it; ok() } ?: bad(key)
                // 旧备份（≤2026-09-26）里是单个「显示生僻字」：true 视为两档都开，false 保持默认关
                KEY_SHOW_RARE_CHARS_LEGACY -> asBool(v)?.let {
                    if (it) {
                        rareTier2 = true
                        rareTier3 = true
                    }
                    ok()
                } ?: bad(key)
                KEY_FUZZY_PINYIN -> asInt(v)?.let { fuzzyPinyinMask = it; ok() } ?: bad(key)
                KEY_VOICE_INPUT -> asBool(v)?.let { voiceInputEnabled = it; ok() } ?: bad(key)
                KEY_TRANSLATE_ENABLED -> asBool(v)?.let { translateEnabled = it; ok() } ?: bad(key)
                // 三个枚举型取值都走 setter 里的 of() 归一：脏备份（未知 id / 未知语言）不会带进运行期
                KEY_TRANSLATE_PROVIDER -> asString(v)?.let { translateProvider = it; ok() } ?: bad(key)
                KEY_TRANSLATE_TARGET -> asString(v)?.let { translateTarget = it; ok() } ?: bad(key)
                // 原文范围 / 字节上限：脏值由 of() 与 coerceIn 归一，不会带进运行期
                KEY_TRANSLATE_SCOPE_ALIYUN ->
                    asString(v)?.let { setTranslateScopeOf(TranslationProviderId.ALIYUN, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_MAX_BYTES_ALIYUN ->
                    asInt(v)?.let { setTranslateMaxBytesOf(TranslationProviderId.ALIYUN, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_SCOPE_AZURE ->
                    asString(v)?.let { setTranslateScopeOf(TranslationProviderId.AZURE, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_MAX_BYTES_AZURE ->
                    asInt(v)?.let { setTranslateMaxBytesOf(TranslationProviderId.AZURE, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_SCOPE_BAIDU ->
                    asString(v)?.let { setTranslateScopeOf(TranslationProviderId.BAIDU, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_MAX_BYTES_BAIDU ->
                    asInt(v)?.let { setTranslateMaxBytesOf(TranslationProviderId.BAIDU, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_SCOPE_BAIDU_LLM ->
                    asString(v)?.let { setTranslateScopeOf(TranslationProviderId.BAIDU_LLM, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_MAX_BYTES_BAIDU_LLM ->
                    asInt(v)?.let { setTranslateMaxBytesOf(TranslationProviderId.BAIDU_LLM, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_SCOPE_DEEPL ->
                    asString(v)?.let { setTranslateScopeOf(TranslationProviderId.DEEPL, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_MAX_BYTES_DEEPL ->
                    asInt(v)?.let { setTranslateMaxBytesOf(TranslationProviderId.DEEPL, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_SCOPE_OPENAI ->
                    asString(v)?.let { setTranslateScopeOf(TranslationProviderId.OPENAI, it); ok() }
                        ?: bad(key)
                KEY_TRANSLATE_MAX_BYTES_OPENAI ->
                    asInt(v)?.let { setTranslateMaxBytesOf(TranslationProviderId.OPENAI, it); ok() }
                        ?: bad(key)
                KEY_AZURE_API_KEY -> asString(v)?.let { azureApiKey = it; ok() } ?: bad(key)
                KEY_AZURE_REGION -> asString(v)?.let { azureRegion = it; ok() } ?: bad(key)
                KEY_BAIDU_APP_ID -> asString(v)?.let { baiduAppId = it; ok() } ?: bad(key)
                KEY_BAIDU_SECRET_KEY -> asString(v)?.let { baiduSecretKey = it; ok() } ?: bad(key)
                KEY_ALIYUN_ACCESS_KEY_ID -> asString(v)?.let { aliyunAccessKeyId = it; ok() } ?: bad(key)
                KEY_ALIYUN_ACCESS_KEY_SECRET -> asString(v)?.let { aliyunAccessKeySecret = it; ok() } ?: bad(key)
                KEY_DEEPL_API_KEY -> asString(v)?.let { deeplApiKey = it; ok() } ?: bad(key)
                KEY_BAIDU_LLM_APP_ID -> asString(v)?.let { baiduLlmAppId = it; ok() } ?: bad(key)
                KEY_BAIDU_LLM_API_KEY -> asString(v)?.let { baiduLlmApiKey = it; ok() } ?: bad(key)
                KEY_OPENAI_NAME -> asString(v)?.let { openAiName = it; ok() } ?: bad(key)
                KEY_OPENAI_BASE_URL -> asString(v)?.let { openAiBaseUrl = it; ok() } ?: bad(key)
                KEY_OPENAI_API_KEY -> asString(v)?.let { openAiApiKey = it; ok() } ?: bad(key)
                KEY_OPENAI_MODEL -> asString(v)?.let { openAiModel = it; ok() } ?: bad(key)
                KEY_OPENAI_CHAT_PATH -> asString(v)?.let { openAiChatPath = it; ok() } ?: bad(key)
                KEY_OPENAI_MODELS_PATH -> asString(v)?.let { openAiModelsPath = it; ok() } ?: bad(key)
                KEY_OPENAI_TARGET_LANGUAGE -> asString(v)?.let { openAiTargetLanguage = it; ok() } ?: bad(key)
                KEY_OPENAI_SYSTEM_PROMPT -> asString(v)?.let { openAiSystemPrompt = it; ok() } ?: bad(key)
                KEY_OPENAI_USER_PROMPT -> asString(v)?.let { openAiUserPrompt = it; ok() } ?: bad(key)
                KEY_OPENAI_TEMPERATURE -> asString(v)?.let { openAiTemperature = it; ok() } ?: bad(key)
                KEY_OPENAI_TOP_P -> asString(v)?.let { openAiTopP = it; ok() } ?: bad(key)
                KEY_OPENAI_MAX_TOKENS -> asString(v)?.let { openAiMaxTokens = it; ok() } ?: bad(key)
                KEY_OPENAI_EXTRA_HEADERS -> asString(v)?.let { openAiExtraHeaders = it; ok() } ?: bad(key)
                KEY_OPENAI_EXTRA_JSON -> asString(v)?.let { openAiExtraJson = it; ok() } ?: bad(key)
                KEY_OPENAI_RESPONSE_PATH -> asString(v)?.let { openAiResponsePath = it; ok() } ?: bad(key)
                KEY_OPENAI_TIMEOUT_SEC -> asInt(v)?.let { openAiTimeoutSec = it; ok() } ?: bad(key)
                KEY_OPENAI_MODELS_CACHE -> asString(v)?.let { openAiModelsCache = it; ok() } ?: bad(key)
                KEY_KEY_CORNER_DP -> asFloat(v)?.let { keyCornerDp = it; ok() } ?: bad(key)
                KEY_KEY_GAP_DP -> asFloat(v)?.let { keyGapDp = it; ok() } ?: bad(key)
                KEY_CANDIDATE_SPACING_DP -> asFloat(v)?.let { candidateSpacingDp = it; ok() } ?: bad(key)
                KEY_CANDIDATE_TEXT_SP -> asFloat(v)?.let { candidateTextSp = it; ok() } ?: bad(key)
                KEY_KEY_TRANSPARENCY_PERCENT ->
                    asInt(v)?.let { keyTransparencyPercent = it; ok() } ?: bad(key)
                // 敲击音效反馈 6 键：setter 里已有钳位（TapSound.clampVolume / parseMap /
                // clampVibrationTier），脏备份的越界值不会进运行期
                KEY_TAP_SOUND_ENABLED -> asBool(v)?.let { tapSoundEnabled = it; ok() } ?: bad(key)
                KEY_TAP_SOUND_VOLUME -> asInt(v)?.let { tapSoundVolume = it; ok() } ?: bad(key)
                KEY_TAP_SOUND_ON_SILENT -> asBool(v)?.let { tapSoundOnSilent = it; ok() } ?: bad(key)
                KEY_TAP_SOUND_MAP -> asString(v)?.let { tapSoundMap = it; ok() } ?: bad(key)
                KEY_TAP_VIBRATE_ENABLED -> asBool(v)?.let { tapVibrateEnabled = it; ok() } ?: bad(key)
                KEY_TAP_VIBRATE_STRENGTH -> asInt(v)?.let { tapVibrateStrength = it; ok() } ?: bad(key)
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

    /**
     * 字符串取值 + **长度闸**（2026-09-30 第二轮审查）。
     *
     * 导入包的单节上限是 16MB：被改坏的包能把 `openai_base_url` 写成十几 MB 字符串，此后每次
     * `translationProvider()` / `joinUrl`（都在主线程）都要复制它，且每次 `sp.edit{}` 都会重写整份
     * prefs XML —— 卡顿与 GC 压力是**持久**的。这里统一挡在 64KB（所有正常取值都远小于它；
     * `favorite_symbols` 另有更严的 32KB 闸，不受影响），超限按「类型不符」计入忽略数。
     */
    /**
     * 写入侧的长度闸（2026-10-02 修复 L-375）：超限即**截尾**并留痕（只记键名与长度，不记内容）。
     *
     * 为什么截断而不是拒绝：这三个字段的合理用法远小于 64 KB（几行 Header / 几百字节 JSON /
     * 一屏模型名），超限只可能是粘贴失误或**服务端可控**的长列表。截断能让「导出的包一定导得回来」，
     * 而拒绝写入会让界面上的值与落盘值不一致（用户以为存了，重启后才发现没了）。
     */
    private fun String.capForBackup(key: String): String =
        if (length <= MAX_BACKUP_STRING_CHARS) {
            this
        } else {
            Diagnostics.w(TAG, "写入侧长度闸($key): 超 $MAX_BACKUP_STRING_CHARS 字，已截断（原 $length）")
            take(MAX_BACKUP_STRING_CHARS)
        }

    private fun asString(v: ConfigBackup.BackupValue): String? =
        (v.value as? String)?.takeIf { it.length <= MAX_BACKUP_STRING_CHARS }
    private fun asInt(v: ConfigBackup.BackupValue): Int? = v.value as? Int
    private fun asLong(v: ConfigBackup.BackupValue): Long? = v.value as? Long
    private fun asFloat(v: ConfigBackup.BackupValue): Float? = v.value as? Float
    private fun asBool(v: ConfigBackup.BackupValue): Boolean? = v.value as? Boolean

    companion object {
        /**
         * 凭据解密的**进程级**缓存：`存储键 → 明文`。
         *
         * 为什么必须缓存：Keystore 解密要走 binder/TEE（5~20ms/次），而凭据读取分布在「组装 Provider」
         * 「设置页摘要」「多配置判空」等多处、且可能在主线程 —— 每次都解密会明显卡顿。
         *
         * 为什么必须**进程级**（2026-10-01 修复 L-298）：`Prefs` 在本进程里被创建三十余次
         * （IME 一份常驻、每个设置页各一份）。缓存挂在实例上时，设置页改完 Key 只更新它自己那一份，
         * 常驻的 IME 会**一直拿旧 Key 发请求** —— 改 Key 不生效、删 Key 也不生效（已撤销的凭据继续
         * 外发），直到进程被杀；这与设置页写下的「本页改完不需要重启输入法」直接矛盾。
         *
         * 它**不额外增加**内存暴露面：`SharedPreferences` 本身就把整个 XML 解析进内存 Map，
         * 加密之前那些明文本来就常驻在进程里，这里只是换了个持有者。（Java `String` 不可擦除，
         * 不做「用完清零」这类样子货。）
         */
        private val credentialCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * 「只在内存、未落盘」的凭据键（2026-10-02 修复 L-355）。
         *
         * 加密不可用时（API 28+ 锁屏 + `setUnlockedDeviceRequired`）值只进 [credentialCache]，
         * 重启即失；设置页的显式保存必须能看见这件事 —— 否则「缓存命中 ⇒ 回读核对必然相等」
         * 会让界面报「已保存到本机」，而实际磁盘上一个 Key 都没有。与缓存同为进程级，同源同清。
         */
        private val unpersistedCredentials: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

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
        /** 双拼候选栏拼音行显示方式（false = 按下的字母 / true = 声母韵母全拼，见 [Prefs.showQuanpin]） */
        private const val KEY_SHOW_QUANPIN = "show_quanpin"
        private const val KEY_USER_LEARNING = "user_learning"
        /** 上次成功检查更新的时刻（epoch ms；0 = 从未成功检查过） */
        private const val KEY_UPDATE_LAST_CHECK_AT = "update_last_check_at"

    /** 可选字档 2 / 档 3（设置页「加更多生僻字」页内两个开关，2026-09-27 起） */
    private const val KEY_RARE_TIER2 = "rare_tier2"
    private const val KEY_RARE_TIER3 = "rare_tier3"

    /** 「只使用繁体字」（设置页，2026-09-27 起） */
    private const val KEY_USE_TRADITIONAL = "use_traditional"

    /** 旧「显示生僻字」键（2026-09-27 退役为两档开关）：只作读取迁移与旧备份导入兼容，不再写入 */
    private const val KEY_SHOW_RARE_CHARS_LEGACY = "show_rare_chars"
        /** 模糊音容错掩码（见 [FuzzyPinyin]）；0 = 关闭，也是出厂默认 */
        private const val KEY_FUZZY_PINYIN = "fuzzy_pinyin"
        private const val KEY_VOICE_INPUT = "voice_input"

        /** 在线翻译（BYOK，2026-09-30 起）：开关 + Provider / 目标语言 + 六家各自的一组凭据 */
        private const val KEY_TRANSLATE_ENABLED = "translate_enabled"
        private const val KEY_TRANSLATE_PROVIDER = "translate_provider"
        private const val KEY_TRANSLATE_TARGET = "translate_target"
        private const val KEY_AZURE_API_KEY = "azure_api_key"
        private const val KEY_AZURE_REGION = "azure_region"
        private const val KEY_BAIDU_APP_ID = "baidu_app_id"
        private const val KEY_BAIDU_SECRET_KEY = "baidu_secret_key"
        private const val KEY_ALIYUN_ACCESS_KEY_ID = "aliyun_access_key_id"
        private const val KEY_ALIYUN_ACCESS_KEY_SECRET = "aliyun_access_key_secret"
        private const val KEY_DEEPL_API_KEY = "deepl_api_key"
        private const val KEY_BAIDU_LLM_APP_ID = "baidu_llm_app_id"
        private const val KEY_BAIDU_LLM_API_KEY = "baidu_llm_api_key"
        private const val KEY_OPENAI_NAME = "openai_name"
        private const val KEY_OPENAI_BASE_URL = "openai_base_url"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_OPENAI_CHAT_PATH = "openai_chat_path"
        private const val KEY_OPENAI_MODELS_PATH = "openai_models_path"
        private const val KEY_OPENAI_TARGET_LANGUAGE = "openai_target_language"
        private const val KEY_OPENAI_SYSTEM_PROMPT = "openai_system_prompt"
        private const val KEY_OPENAI_USER_PROMPT = "openai_user_prompt"
        private const val KEY_OPENAI_TEMPERATURE = "openai_temperature"
        private const val KEY_OPENAI_TOP_P = "openai_top_p"
        private const val KEY_OPENAI_MAX_TOKENS = "openai_max_tokens"
        private const val KEY_OPENAI_EXTRA_HEADERS = "openai_extra_headers"
        private const val KEY_OPENAI_EXTRA_JSON = "openai_extra_json"
        private const val KEY_OPENAI_RESPONSE_PATH = "openai_response_path"
        private const val KEY_OPENAI_TIMEOUT_SEC = "openai_timeout_sec"
        private const val KEY_OPENAI_MODELS_CACHE = "openai_models_cache"

        /**
         * 「哪些算翻译原文」按 Provider 各一份：范围模式 + 单次字节上限。
         *
         * 十二个键都显式写出来（不用循环拼名）：导出 / 导入白名单要逐键引用常量，
         * `PrefsBackupCoverageTest` 的源码对拍才能把「新增键必须进两份白名单」守住。
         */
        private const val KEY_TRANSLATE_SCOPE_ALIYUN = "translate_scope_aliyun"
        private const val KEY_TRANSLATE_MAX_BYTES_ALIYUN = "translate_max_bytes_aliyun"
        private const val KEY_TRANSLATE_SCOPE_AZURE = "translate_scope_azure"
        private const val KEY_TRANSLATE_MAX_BYTES_AZURE = "translate_max_bytes_azure"
        private const val KEY_TRANSLATE_SCOPE_BAIDU = "translate_scope_baidu"
        private const val KEY_TRANSLATE_MAX_BYTES_BAIDU = "translate_max_bytes_baidu"
        private const val KEY_TRANSLATE_SCOPE_BAIDU_LLM = "translate_scope_baidu_llm"
        private const val KEY_TRANSLATE_MAX_BYTES_BAIDU_LLM = "translate_max_bytes_baidu_llm"
        private const val KEY_TRANSLATE_SCOPE_DEEPL = "translate_scope_deepl"
        private const val KEY_TRANSLATE_MAX_BYTES_DEEPL = "translate_max_bytes_deepl"
        private const val KEY_TRANSLATE_SCOPE_OPENAI = "translate_scope_openai"
        private const val KEY_TRANSLATE_MAX_BYTES_OPENAI = "translate_max_bytes_openai"

        /** 整体超时的允许区间（秒）：太短会误杀大模型，太长会把按钮的「翻译中」挂死 */
        private const val TIMEOUT_MIN_SEC = 5

        /**
         * 超时上限（秒）。`internal`（2026-10-03 修复 L-602）——
         * [JinnIme.TRANSLATE_WATCHDOG_MS] 必须**派生**自它，否则两者是人肉耦合：
         * 把上限提到 400 而忘了改看门狗，看门狗就会抢在 `callTimeout` 之前收尾、
         * 用户看到「翻译中」凭空消失且一句提示都没有（编译与门禁全绿，只有真机才发现）。
         */
        internal const val TIMEOUT_MAX_SEC = 300
        private const val KEY_KEY_CORNER_DP = "key_corner_dp"
        private const val KEY_KEY_GAP_DP = "key_gap_dp"
        private const val KEY_CANDIDATE_SPACING_DP = "candidate_spacing_dp"
        private const val KEY_CANDIDATE_TEXT_SP = "candidate_text_sp"
        private const val KEY_KEY_TRANSPARENCY_PERCENT = "key_transparency_percent"

        /**
         * 敲击音效反馈 6 键（2026-10-05）：音效开关 / 强度 / 静音仍播 / 分组映射 + 震动开关 / 档位。
         *
         * 全部要进备份白名单：用户对这项功能的验收标准是「所有配置与设置参数都能导出 / 导入」，
         * 音色选择与震动档位同样是用户偏好，换机必须带走。
         */
        private const val KEY_TAP_SOUND_ENABLED = "tap_sound_enabled"
        private const val KEY_TAP_SOUND_VOLUME = "tap_sound_volume"
        private const val KEY_TAP_SOUND_ON_SILENT = "tap_sound_on_silent"
        private const val KEY_TAP_SOUND_MAP = "tap_sound_map"
        private const val KEY_TAP_VIBRATE_ENABLED = "tap_vibrate_enabled"
        private const val KEY_TAP_VIBRATE_STRENGTH = "tap_vibrate_strength"
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
