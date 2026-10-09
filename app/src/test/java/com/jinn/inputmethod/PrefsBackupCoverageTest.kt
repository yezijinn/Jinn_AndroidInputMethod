package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份覆盖面守卫（源码对拍）：`Prefs` 里每新增一个持久化键，都必须同时出现在导出与导入白名单里。
 *
 * 为什么需要它：用户对这项功能的验收标准是「**所有**配置与设置参数都能导出/导入」，漏一个键
 * 就等于备份不完整；而键名是字符串常量，编译器不报、普通单测也看不见（真实读写要 Android Context）。
 * 所以这里直接读 `Prefs.kt` 源码做静态对拍：提取全部 `KEY_* = "..."` 常量，逐个核对两个白名单方法体
 * 是否都提到了它。以后加键忘了加进备份，这条会先红。
 */
class PrefsBackupCoverageTest {

    /**
     * 已退役的持久化键：只被迁移逻辑读取，既不写也不再进备份白名单。
     *
     *  - `KEY_KEYBOARD_SKIN`：旧版单值皮肤 → 双槽位（`Prefs.ensureSkinSlotsMigrated`）
     *  - `KEY_SHOW_RARE_CHARS_LEGACY`：旧「显示生僻字」→ 档 2 / 档 3 两开关（2026-09-27），
     *    只保留读取迁移与旧备份导入兼容
     *
     * 它们留在常量表里是为了让本守卫仍能清点，因此必须显式列出而不是悄悄改名绕过。
     */
    private val retiredKeys = setOf("KEY_KEYBOARD_SKIN", "KEY_SHOW_RARE_CHARS_LEGACY")

    private val sourceFile: File = listOf(
        File("src/main/java/com/jinn/inputmethod/Prefs.kt"),
        File("app/src/main/java/com/jinn/inputmethod/Prefs.kt"),
    ).firstOrNull { it.isFile } ?: error("找不到 Prefs.kt（当前工作目录=${File("").absolutePath}）")

    /**
     * 源码文本（**剥注释**后再对拍）：注释里的同名字符串不该能满足任何判据 —— 把 `put(KEY_X, …)`
     * 注释掉时本文件全部用例都必须变红（L-116 / L-128 的纪律）。剥注释走仓库的共用助手，
     * 别在这里自建（元守卫按实现形态点名，自建形态会被漏掉）。
     */
    private val source: String = TestSources.codeOf(sourceFile.readText())

    /**
     * 常量名 → 键名字符串。
     *
     * 字符集放宽到大小写与数字：原正则只认小写+下划线，将来出现 `KEY_API_V2 = "apiV2"` 这类命名
     * 会**静默漏检**（数量断言照样通过），守卫就失效了。
     */
    private val keyConstants: Map<String, String> =
        Regex("""private const val (KEY_[A-Z0-9_]+) = "([A-Za-z0-9_]+)"""").findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * 截取某个方法体的源码文本：从方法签名起，到下一个同级成员（`internal fun` / `private fun` /
     * `internal class`）为止。只用来做「键名是否出现」的文本核对，不追求语法级精确。
     */
    private fun bodyOf(funName: String): String {
        val start = source.indexOf("internal fun $funName(")
        assertTrue("源码里没找到 $funName", start > 0)
        val tail = source.substring(start + 1)
        val cuts = listOf("\n    internal fun ", "\n    private fun ", "\n    internal class ")
            .map { tail.indexOf(it) }
            .filter { it > 0 }
        // 注释在 [source] 那一层就剥掉了（L-50：当初用「注释掉」做变异没变红、改用「删掉整行」才变红），
        // 这里只管截取范围。
        return tail.substring(0, cuts.minOrNull() ?: tail.length)
    }

    @Test
    fun `每个持久化键都必须同时在导出与导入白名单里`() {
        // 精确 76：用「>=」时，新增键被正则漏检或键被误删都不会报警，守卫价值被高估。
        // 改键数是正常维护，改完同步这个数（35 个旧键 + 41 个翻译键，含 2 个退役键）。
        // 2026-09-30 起 64 → 76：新增「原文范围」12 键（6 家 × 范围模式 / 字节上限）。
        // 2026-10-03 起 77 → 78：新增「候选字号」1 键（外观参数，整个候选栏按它派生）。
        // 2026-10-05 起 78 → 84：新增「敲击音效反馈」6 键（音效开关 / 强度 / 静音仍播 / 分组映射
        //                        + 震动开关 / 档位）—— 音色与档位是用户偏好，换机必须带走。
        // 2026-10-07 起 84 → 85：新增「图库快贴目录」1 键（SAF 目录树 URI；换机后授权通常失效，
        //                        导入侧仍原样还原，由键盘面板检测并提示重绑）
        // 2026-10-07 起 85 → 86：新增「图库快贴自动返回」1 键（面板里那个开关的用户偏好）
        // 2026-10-07 起 86 → 88：新增「图库缩略图每行张数」「图库缩略图行高」2 键（「布局」调节）
        // 2026-10-09 起 88 → 90：新增「键高」「26 键常显大写」2 键（外观参数；换机要带走）
        // 2026-10-10 起 90 → 91：新增「屏幕翻译」1 键（无障碍功能的应用内总开关，纯 UI 偏好）
        assertEquals("提取到的键常量应是 91 个（改键数请同步本断言）", 91, keyConstants.size)

        val export = bodyOf("exportForBackup")
        val import = bodyOf("importFromBackup")

        val liveKeys = keyConstants.keys - retiredKeys
        val missingExport = liveKeys.filterNot { export.contains(it) }
        val missingImport = liveKeys.filterNot { import.contains(it) }

        assertTrue("这些键没进导出白名单：$missingExport", missingExport.isEmpty())
        assertTrue("这些键没进导入白名单：$missingImport", missingImport.isEmpty())
    }

    @Test
    fun `键名常量不重复（同名键会让两个配置互相覆盖）`() {
        val names = keyConstants.values
        assertTrue("存在重复键名：${names.groupingBy { it }.eachCount().filter { it.value > 1 }}", names.size == names.toSet().size)
    }

    @Test
    fun `类型键与取值工具必须配对`() {
        // 只核对「键名是否出现」会漏掉这类错误：把 KEY_PORT 写成 asString(v) 照样绿，
        // 但导入后这项设置会静默丢失（类型不符 → 计入忽略）。压缩空白以容忍换行写法。
        val import = bodyOf("importFromBackup").replace(Regex("\\s+"), " ")
        // 下表全部「普通」键（数量见 :61 的 32 个常量：30 个现有键 + skin_light/skin_dark，其中 1 个退役）
        // → 期望的取值工具（`KEY_FAVORITE_SYMBOLS` 走归一分支，单列在下面）。
        // 只钉少数几对时，剩下的键把 Int 写成 asString 这类错误不会被发现（导入时类型不符 = 静默丢失）
        val pairs = mapOf(
            "KEY_HOST" to "asString(v)",
            "KEY_PORT" to "asInt(v)",
            "KEY_LOCK_SERVER" to "asBool(v)",
            "KEY_LANGUAGE" to "asString(v)",
            "KEY_PROMPT" to "asString(v)",
            "KEY_STRIP_PUNC" to "asBool(v)",
            "KEY_COMPOSING" to "asBool(v)",
            "KEY_SHUANGPIN" to "asBool(v)",
            "KEY_SHUANGPIN_SCHEME" to "asInt(v)",
            "KEY_KB_ENGLISH" to "asBool(v)",
            "KEY_AUTO_SHOW_KB" to "asBool(v)",
            "KEY_DEFAULT_MODE" to "asInt(v)",
            "KEY_PREDICT_ENABLED" to "asBool(v)",
            "KEY_CANDIDATE_ROWS" to "asInt(v)",
            "KEY_SHOW_KEY_HINT" to "asBool(v)",
            "KEY_SHOW_QUANPIN" to "asBool(v)",
            "KEY_USER_LEARNING" to "asBool(v)",
            "KEY_RARE_TIER2" to "asBool(v)",
            "KEY_RARE_TIER3" to "asBool(v)",
            "KEY_USE_TRADITIONAL" to "asBool(v)",
            "KEY_FUZZY_PINYIN" to "asInt(v)",
            "KEY_VOICE_INPUT" to "asBool(v)",
            "KEY_TRANSLATE_ENABLED" to "asBool(v)",
            // 屏幕翻译（无障碍）：应用内总开关，类型 bool
            "KEY_SCREEN_TRANSLATE" to "asBool(v)",
            "KEY_TRANSLATE_PROVIDER" to "asString(v)",
            "KEY_TRANSLATE_TARGET" to "asString(v)",
            // 「哪些算原文」12 键：范围模式是字符串（走 of() 归一），字节上限是整数（走 coerceIn 钳位）
            "KEY_TRANSLATE_SCOPE_ALIYUN" to "asString(v)",
            "KEY_TRANSLATE_MAX_BYTES_ALIYUN" to "asInt(v)",
            "KEY_TRANSLATE_SCOPE_AZURE" to "asString(v)",
            "KEY_TRANSLATE_MAX_BYTES_AZURE" to "asInt(v)",
            "KEY_TRANSLATE_SCOPE_BAIDU" to "asString(v)",
            "KEY_TRANSLATE_MAX_BYTES_BAIDU" to "asInt(v)",
            "KEY_TRANSLATE_SCOPE_BAIDU_LLM" to "asString(v)",
            "KEY_TRANSLATE_MAX_BYTES_BAIDU_LLM" to "asInt(v)",
            "KEY_TRANSLATE_SCOPE_DEEPL" to "asString(v)",
            "KEY_TRANSLATE_MAX_BYTES_DEEPL" to "asInt(v)",
            "KEY_TRANSLATE_SCOPE_OPENAI" to "asString(v)",
            "KEY_TRANSLATE_MAX_BYTES_OPENAI" to "asInt(v)",
            "KEY_AZURE_API_KEY" to "asString(v)",
            "KEY_AZURE_REGION" to "asString(v)",
            "KEY_BAIDU_APP_ID" to "asString(v)",
            "KEY_BAIDU_SECRET_KEY" to "asString(v)",
            "KEY_ALIYUN_ACCESS_KEY_ID" to "asString(v)",
            "KEY_ALIYUN_ACCESS_KEY_SECRET" to "asString(v)",
            "KEY_DEEPL_API_KEY" to "asString(v)",
            "KEY_BAIDU_LLM_APP_ID" to "asString(v)",
            "KEY_BAIDU_LLM_API_KEY" to "asString(v)",
            "KEY_OPENAI_NAME" to "asString(v)",
            "KEY_OPENAI_BASE_URL" to "asString(v)",
            "KEY_OPENAI_API_KEY" to "asString(v)",
            "KEY_OPENAI_MODEL" to "asString(v)",
            "KEY_OPENAI_CHAT_PATH" to "asString(v)",
            "KEY_OPENAI_MODELS_PATH" to "asString(v)",
            "KEY_OPENAI_TARGET_LANGUAGE" to "asString(v)",
            "KEY_OPENAI_SYSTEM_PROMPT" to "asString(v)",
            "KEY_OPENAI_USER_PROMPT" to "asString(v)",
            "KEY_OPENAI_TEMPERATURE" to "asString(v)",
            "KEY_OPENAI_TOP_P" to "asString(v)",
            "KEY_OPENAI_MAX_TOKENS" to "asString(v)",
            "KEY_OPENAI_EXTRA_HEADERS" to "asString(v)",
            "KEY_OPENAI_EXTRA_JSON" to "asString(v)",
            "KEY_OPENAI_RESPONSE_PATH" to "asString(v)",
            "KEY_OPENAI_TIMEOUT_SEC" to "asInt(v)",
            "KEY_OPENAI_MODELS_CACHE" to "asString(v)",
            "KEY_KEY_CORNER_DP" to "asFloat(v)",
            "KEY_KEY_GAP_DP" to "asFloat(v)",
            "KEY_CANDIDATE_SPACING_DP" to "asFloat(v)",
            "KEY_CANDIDATE_TEXT_SP" to "asFloat(v)",
            "KEY_KEY_TRANSPARENCY_PERCENT" to "asInt(v)",
            "KEY_SKIN_LIGHT" to "asString(v)",
            "KEY_SKIN_DARK" to "asString(v)",
            "KEY_THEME_MODE" to "asInt(v)",
            "KEY_SYMBOL_ORDER" to "asString(v)",
            "KEY_THEME_LIGHT_AT" to "asInt(v)",
            "KEY_THEME_DARK_AT" to "asInt(v)",
            "KEY_UPDATE_LAST_CHECK_AT" to "asLong(v)",
            // 敲击音效反馈 6 键（2026-10-05）：三个布尔 / 两个整型 + 一个分组映射串
            "KEY_TAP_SOUND_ENABLED" to "asBool(v)",
            "KEY_TAP_SOUND_VOLUME" to "asInt(v)",
            "KEY_TAP_SOUND_ON_SILENT" to "asBool(v)",
            "KEY_TAP_SOUND_MAP" to "asString(v)",
            "KEY_TAP_VIBRATE_ENABLED" to "asBool(v)",
            "KEY_TAP_VIBRATE_STRENGTH" to "asInt(v)",
            // 键高 / 26 键大写 / 图库四键：这六项此前从未进过本表（BUG.md L-1148 的键集合断言
            // 一落地就报出来）；取值工具逐一核对过类型，与各自 setter 的语义一致。
            "KEY_KEY_HEIGHT_DP" to "asFloat(v)",
            "KEY_KEY_LETTER_UPPERCASE" to "asBool(v)",
            "KEY_GALLERY_TREE_URI" to "asString(v)",
            "KEY_GALLERY_AUTO_RETURN" to "asBool(v)",
            "KEY_GALLERY_COLUMNS" to "asInt(v)",
            "KEY_GALLERY_CELL_HEIGHT_DP" to "asInt(v)",
        )
        for ((key, tool) in pairs) {
            assertTrue(
                "$key 的取值工具不是 $tool（写错会让这项设置导入后静默丢失）",
                import.contains("$key -> $tool"),
            )
        }
        // 收藏符号走「归一 + 限长」分支，形态不是 `-> asXxx(v)`，单独钉住
        assertTrue("KEY_FAVORITE_SYMBOLS 分支丢失", import.contains("KEY_FAVORITE_SYMBOLS ->"))
        assertTrue(
            "KEY_FAVORITE_SYMBOLS 未做归一（会绕过长度上限，导致主线程解析超大 JSON）",
            import.contains("FavoriteSymbols.serialize(FavoriteSymbols.parse("),
        )
        // 键集合必须覆盖导入侧：新增键时漏补这张表、或把类型写错，此前不会有任何断言报出来
        // （BUG.md L-1148）。例外只有三类 —— 退役键、单独钉住的收藏、凭据（凭据不进备份，
        // 只在导入侧兼容旧包）。
        val importKeys = Regex("""(KEY_\w+)\s*->\s*as""").findAll(import).map { it.groupValues[1] }.toSet()
        val credentialKeys = setOf(
            "KEY_AZURE_API_KEY", "KEY_BAIDU_APP_ID", "KEY_BAIDU_SECRET_KEY",
            "KEY_ALIYUN_ACCESS_KEY_ID", "KEY_ALIYUN_ACCESS_KEY_SECRET", "KEY_DEEPL_API_KEY",
            "KEY_BAIDU_LLM_APP_ID", "KEY_BAIDU_LLM_API_KEY", "KEY_OPENAI_API_KEY",
        )
        val allowed = pairs.keys + "KEY_FAVORITE_SYMBOLS" + retiredKeys + credentialKeys
        assertEquals(
            "导入分支里有键没进配对表（新增键要在 pairs 里声明取值工具）",
            emptySet<String>(),
            importKeys - allowed,
        )
    }

    /**
     * 提取属性定义块：`var 名字:` 起，到下一个同级属性/函数/注释块为止。
     *
     * 归一调用都写在属性的 getter/setter 里，用块内包含来判断「这个键有归一」比全局搜字符串可靠
     * （全局搜会因别处的同类调用而误判为有守卫）。
     */
    private fun propertyBlock(propName: String): String {
        val start = source.indexOf("var $propName:")
        assertTrue("源码里没找到属性 $propName", start > 0)
        val tail = source.substring(start + 1)
        val cuts = listOf("\n    var ", "\n    val ", "\n    internal fun ", "\n    private fun ", "\n    /**")
            .map { tail.indexOf(it) }
            .filter { it > 0 }
        return tail.substring(0, cuts.minOrNull() ?: tail.length)
    }

    @Test
    fun `取值可能有越界的键必须在读写路径上有归一`() {
        // 导入的是一份外部文件，可以被手工构造。下面这些键一旦放进越界值就会进运行期，
        // 最坏是主线程崩溃（wsUrl 会被直接送进 OkHttp，非法端口/主机头会让 HttpUrl 抛异常）。
        // 归一分散在 setter（写入即规范化）或 getter（读出来一定合法）—— 两种都算守住，
        // 本测试只钉「这个键的块里存在归一调用」，避免今后有人把钳位当成冗余删掉。
        val guards = listOf(
            Triple("host", "normalizeHost", "越界 host 会让 wsUrl 进 OkHttp 时抛异常"),
            Triple("port", "in 1..65535", "越界端口同样会让 HttpUrl 抛异常"),
            Triple("defaultKeyboardMode", "coerceIn", "未知模式编号会落到无效键盘模式"),
            Triple("shuangpinScheme", "ShuangpinScheme.of", "未知方案编号会取不到键位表"),
            Triple("keyCornerDp", "KeyAppearance.clampCornerDp", "越界圆角会进绘制流程"),
            Triple("keyGapDp", "KeyAppearance.clampGapDp", "越界间隙会进绘制流程"),
            Triple("keyTransparencyPercent", "KeyTransparency.clampPercent", "越界透明度会算出非法 alpha"),
            Triple("candidateTextSp", "CandidateText.clampSp", "越界字号会把候选栏撑满整屏或缩到点不中"),
            Triple("skinLightId", "KeyboardSkins.byId", "未知皮肤 id 会取不到色板"),
            Triple("skinDarkId", "KeyboardSkins.byId", "未知皮肤 id 会取不到色板"),
            Triple("fuzzyPinyinMask", "FuzzyPinyin.clampMask", "越界掩码会派生出未定义的模糊音组"),
            Triple("candidateRows", "coerceIn", "越界行数会让候选栏高度算不出合法档位"),
            Triple("symbolGroupOrder", "SymbolOrder.serialize", "脏顺序串会缺分组"),
            Triple("themeMode", "MODE_SYSTEM..", "未知模式编号会落到无效主题"),
            Triple("themeLightAtMinutes", "floorMod", "负值/超 24h 会显示成 -1:-30 这类非法时刻"),
            Triple("themeDarkAtMinutes", "floorMod", "同上"),
            Triple("translateProvider", "TranslationProviderId.of", "未知 id 会取不到 Provider 实现"),
            Triple("translateTarget", "TranslationLanguage.of", "未知语言码会被服务端直接拒（400 / 58001）"),
            Triple("tapSoundVolume", "TapSound.clampVolume", "越界音量会算出非法播放增益"),
            Triple("tapSoundMap", "TapSound.parseMap", "越界音效索引会让数组访问直接崩"),
            Triple("tapVibrateStrength", "TapSound.clampVibrationTier", "未知档位会让震动规格取不到值"),
            Triple("galleryColumns", "coerceIn", "越界张数会让每行格子宽度算出非法值"),
            Triple("galleryCellHeightDp", "coerceIn", "越界行高会把面板撑破或缩成点不中"),
        )
        for ((prop, marker, why) in guards) {
            assertTrue("$prop 的归一（$marker）不见了：$why", propertyBlock(prop).contains(marker))
        }
    }

    /**
     * 钳位不只是「有」，区间必须等于定义域（BUG.md L-1147）。
     *
     * 上一条只断言「实现里出现了钳位调用」—— 把上界从 3 改成 30 同样绿，而钳错界正是会出事的那种改法
     * （脏备份的越界值照样进运行期）。这里改成行为判据：喂进远超定义域的值，回读必须落在定义域常量上；
     * 期望值一律从常量读，不写死数字 —— 改定义域时两条一起动，改钳位区间而没动定义域才红。
     */
    @Test
    fun `钳位区间必须等于定义域常量`() {
        assertEquals(KeyAppearance.MIN_CORNER_DP, KeyAppearance.clampCornerDp(-1_000f), 0.001f)
        assertEquals(KeyAppearance.MAX_CORNER_DP, KeyAppearance.clampCornerDp(1_000f), 0.001f)
        assertEquals(KeyAppearance.MIN_GAP_DP, KeyAppearance.clampGapDp(-1_000f), 0.001f)
        assertEquals(KeyAppearance.MAX_GAP_DP, KeyAppearance.clampGapDp(1_000f), 0.001f)
        assertEquals(KeyAppearance.MIN_KEY_HEIGHT_DP, KeyAppearance.clampKeyHeightDp(-1_000f), 0.001f)
        assertEquals(KeyAppearance.MAX_KEY_HEIGHT_DP, KeyAppearance.clampKeyHeightDp(1_000f), 0.001f)
        assertEquals(KeyTransparency.MIN_PERCENT, KeyTransparency.clampPercent(-500))
        assertEquals(KeyTransparency.MAX_PERCENT, KeyTransparency.clampPercent(500))
        assertEquals(CandidateText.MIN_SP, CandidateText.clampSp(0f), 0.001f)
        assertEquals(CandidateText.MAX_SP, CandidateText.clampSp(1_000f), 0.001f)
        assertEquals(TapSound.VOLUME_MIN, TapSound.clampVolume(-500))
        assertEquals(TapSound.VOLUME_MAX, TapSound.clampVolume(500))
        // 掩码是位集合，区间即「只留已定义的位」：全 1 与带高位脏值的输入都必须归到 MASK_ALL
        assertEquals(FuzzyPinyin.MASK_ALL, FuzzyPinyin.clampMask(-1))
        assertEquals(FuzzyPinyin.MASK_ALL, FuzzyPinyin.clampMask(FuzzyPinyin.MASK_ALL or (1 shl 20)))
    }

    @Test
    fun `非平凡默认值的键导出前必须问「用户改过没有」`() {
        // 这三个键的 getter 在缺键时回落的不是平凡默认（音量 50 / 六组统一用 kbd_10 / 中档）：
        // 无条件导出会把「当时的默认」固化成显式值，将来调默认值这批人（恰恰是从没改过的那批）
        // 永远拿不到。两个开关的默认是 false，导出与否等价，故不在其列。
        val export = bodyOf("exportForBackup")
        for (key in listOf(
            "KEY_TAP_SOUND_VOLUME", "KEY_TAP_SOUND_MAP", "KEY_TAP_VIBRATE_STRENGTH",
            "KEY_SKIN_LIGHT", "KEY_SKIN_DARK", "KEY_THEME_MODE",
        )) {
            // 判据是「守卫**罩住**这次写入」，不是「文本里出现过 if (…)」—— 后者在「空守卫体 +
            // 另起一行无条件写入」下仍然成立。换成 `sp.all` 快照写法（putIfSetXxx）同样算数。
            assertTrue(
                "$key 的导出没有守卫（缺键时会把当时的默认值写进备份）",
                Regex("""if \(sp\.contains\($key\)\)[\s\S]{0,160}?put\($key""")
                    .containsMatchIn(export) ||
                    Regex("""putIfSet\w+\($key""").containsMatchIn(export),
            )
        }
    }

    @Test
    fun `导出与导入两侧都必须覆盖全部键`() {
        // 比逐项类型更强的兜底：新增一个键时，只加进导出、或只加进导入，都会在这里被抓住
        val export = bodyOf("exportForBackup")
        val import = bodyOf("importFromBackup")
        val exportKeys = Regex("KEY_[A-Z0-9_]+").findAll(export).map { it.value }.toSet()
        val importKeys = Regex("KEY_[A-Z0-9_]+").findAll(import).map { it.value }.toSet()
        val liveKeys = keyConstants.keys - retiredKeys

        assertEquals(
            "导出侧漏掉的键：${liveKeys - exportKeys}",
            emptySet<String>(),
            liveKeys - exportKeys,
        )
        assertEquals(
            "导入侧漏掉的键：${liveKeys - importKeys}",
            emptySet<String>(),
            liveKeys - importKeys,
        )
    }

    /**
     * 退役键的方向守卫：**导出侧不得再出现它**（新版本不再写这个键），**导入侧必须保留兼容分支**
     * （否则「新机器导入旧版备份」会丢掉用户选过的皮肤，静默落回出厂默认）。
     *
     * 上面那条「liveKeys ⊆ 白名单」抓不到这两个方向：把退役键加回导出、或删掉导入兼容分支，
     * 它都不会变红。
     */
    @Test
    fun `退役键只在导入侧作为兼容分支出现`() {
        val export = bodyOf("exportForBackup")
        val import = bodyOf("importFromBackup")
        for (key in retiredKeys) {
            assertTrue("$key 已退役，不得再写进导出", !export.contains(key))
            assertTrue("$key 的导入兼容分支不见了（旧版备份会丢皮肤）", import.contains(key))
        }
    }

    @Test
    fun `剪贴板白名单只认容量键：总开关是产品不变量，不进备份`() {
        // 走共用装载器（剥注释）：直接 readText 时，注释里写一句含某个键名的说明就能满足正向钉，
        // 也能把「总开关不得进白名单」这条负向钉判红（BUG.md L-1149）
        val text = TestSources.codeSource("ClipboardPrefs.kt")
        // ⚠ 只看 importFromBackup 的**函数体**：`substringAfter("importFromBackup")` 会一路吃到
        // companion object，而那里仍有 `private const val KEY_ENABLED`（声明，不是白名单）⇒
        // 用它判「导入白名单里没有 KEY_ENABLED」永远为假（2026-10-03 实测）。
        val importBody = text.substringAfter("internal fun importFromBackup")
            .substringBefore("companion object")
        for (key in listOf("KEY_ENABLED", "KEY_MAX_ITEMS")) {
            assertTrue("$key 未在 ClipboardPrefs 中声明", text.contains("private const val $key"))
        }
        // 容量键是用户偏好，必须往返都进白名单
        assertTrue("KEY_MAX_ITEMS 未进导出白名单", text.contains("out[KEY_MAX_ITEMS] = it"))
        assertTrue(
            "KEY_MAX_ITEMS 未进导入白名单",
            importBody.contains("KEY_MAX_ITEMS"),
        )
        // ⚠ 总开关**必须不在**往返里（2026-10-03 修复 L-751）：它是产品不变量（设置页每次进页面都
        // 强制 true），而导入侧曾无条件写回 ⇒ 「覆盖还原」一份带 false 的包会静默停止剪贴板采集，
        // 导入摘要与完成提示都不提它，且该状态会在包与本机之间自我传播。
        assertTrue(
            "KEY_ENABLED 不得进导出白名单（否则覆盖还原会静默停采集）",
            !text.contains("out[KEY_ENABLED] = it"),
        )
        assertTrue(
            "KEY_ENABLED 不得进导入白名单（旧包里的 false 也必须忽略）",
            !importBody.contains("KEY_ENABLED"),
        )
        // 与另一条运行态标记口径一致（reclassified 早已以同样理由剔除）
        assertTrue(
            "剪贴板的运行态标记应一致地都不进备份",
            !text.contains("out[KEY_RECLASSIFIED] = it"),
        )
    }

    /**
     * 旧「加更多生僻字」开关的迁移口径：**两档的回落必须一致**（旧 true = 两档全开）。
     *
     * 迁移读的是 `show_rare_chars`（[retiredKeys] 之一），只能靠源码对拍钉住：
     * `rare_tier2` 写了回落、`rare_tier3` 漏写时，原生升级（旧开关 true + 覆盖安装 + 不导入备份）
     * 只得到档 2 —— 三级字 2,923 个与含三级字的词条整体消失，而**导出**又把这个回落值写进备份并把
     * 旧键丢掉，于是「升级丢档 3 → 备份固化 → 换机永久丢」（2026-09-27 修，BUG.md L-41）。
     *
     * ⚠ 判据先剥 `//` 注释再匹配：`PrefsBackupCoverageTest` 的 `bodyOf` 曾因不剥注释而“看起来绿”
     * （BUG.md L-50），属性块里现在也有讲述这条迁移的注释，别让注释自己满足断言。
     */
    @Test
    fun `旧生僻字开关必须同时回落到两个档位`() {
        for (prop in listOf("rareTier2", "rareTier3")) {
            val code = propertyBlock(prop).lineSequence()
                .joinToString("\n") { it.substringBefore("//") }
            assertTrue(
                "$prop 的 getter 未回落旧键：`rare_tier*` 缺失时应读 KEY_SHOW_RARE_CHARS_LEGACY" +
                    "（否则原生升级只开档 2，三级字与含三级字的词条消失）",
                // 读侧收口后（BUG.md L-653）是 `else boolOr(…, false)`；两种形态都认，
                // 否则「把裸读换成收口助手」这种纯加固会把本条守卫打红
                code.contains("else sp.getBoolean(KEY_SHOW_RARE_CHARS_LEGACY, false)") ||
                    code.contains("else boolOr(KEY_SHOW_RARE_CHARS_LEGACY, false)"),
            )
        }
    }

    /**
     * 「哪些算原文」两项（2026-09-30）的**归一守卫**：它们按 Provider 存，是 `fun` 而非 `var`，
     * 上面那条 `取值可能有越界的键必须在读写路径上有归一` 的 `propertyBlock` 覆盖不到。
     *
     * 字节上限是 Int，导入的备份可以是任意数值：`0` 不钳位会让原文恒为空（翻译永远提示
     * 「没有可翻译的文字」），`Int.MAX_VALUE` 则绕过读取上限。范围模式走 `of()` 同理 ——
     * 未知 id 会取不到对应的取值分支。
     */
    @Test
    fun `原文范围与字节上限的读写两侧都必须归一`() {
        val scopeGuard = "TranslationScope.of("
        for ((marker, guard) in listOf(
            "fun translateScopeOf" to scopeGuard,
            "fun setTranslateScopeOf" to scopeGuard,
        )) {
            val start = source.indexOf(marker)
            assertTrue("源码里没找到 $marker", start > 0)
            val body = source.substring(start, (start + 400).coerceAtMost(source.length))
            assertTrue("$marker 缺少归一（$guard）—— 脏备份的越界值会进运行期", body.contains(guard))
        }
        // 字节上限两侧（2026-10-02 改判据，见 L-434）：下界不再是 `coerceIn` 而是「回落该家默认」——
        // 界面里「清空 / 0」的语义是恢复出厂默认，钳成 1 会让该家变成「每次只翻 1 字节」；
        // 上界仍是钳制。两侧都必须有，缺哪边都放脏值进运行期。
        for (marker in listOf("fun translateMaxBytesOf", "fun setTranslateMaxBytesOf")) {
            val start = source.indexOf(marker)
            assertTrue("源码里没找到 $marker", start > 0)
            val body = source.substring(start, (start + 600).coerceAtMost(source.length))
            assertTrue(
                "$marker 缺少下界归一（MIN_MAX_BYTES ⇒ 回落该家默认）",
                "TranslationText.MIN_MAX_BYTES" in body,
            )
            assertTrue(
                "$marker 缺少上界归一（coerceAtMost(MAX_MAX_BYTES)）—— 脏备份的越界值会进运行期",
                "coerceAtMost(TranslationText.MAX_MAX_BYTES)" in body,
            )
        }
    }

    /**
     * 高价值键的**键名不得改**（2026-10-09）。
     *
     * 键名就是用户数据的地址：把某个键的值改个写法（`"candidate_rows"` → 别的），等于「老用户那一项设置
     * 清零」，同时旧备份里的同项被当未知键丢掉。编译器不报错，本类的备份覆盖面守卫也看不见 —— 它只比
     * 常量名集合，常量与字符串一起改照样绿。
     *
     * 这张表是这些地址的登记册：确要改键，必须显式改本表**并**写一次性迁移（读旧键 → 写新键）。
     *
     * 取表口径：「用户调过一次就不希望丢」的设置 —— 键盘外观页一整套（六个滑杆 + 三个开关 + 两个下拉，
     * 2026-10-09 由设置页迁入，键名刻意一个没动，见提交 `2629fbb`）+ 图库格子高 + 主题定时的三个键；
     * `KEY_KEYBOARD_SKIN` 虽已退役，但旧用户的皮肤迁移仍读它，改名会让迁移读不到。
     */
    @Test
    fun 高价值键的名字不得改() {
        val golden = linkedMapOf(
            // 2026-10-09 迁入外观页的四个开关 / 下拉（迁入时键名保持不变，老用户设置不受影响）
            "KEY_CANDIDATE_ROWS" to "candidate_rows",
            "KEY_SHOW_KEY_HINT" to "show_key_hint",
            "KEY_SHOW_QUANPIN" to "show_quanpin",
            "KEY_KEY_LETTER_UPPERCASE" to "key_letter_uppercase",
            // 外观页的滑杆
            "KEY_KEY_CORNER_DP" to "key_corner_dp",
            "KEY_KEY_GAP_DP" to "key_gap_dp",
            "KEY_KEY_HEIGHT_DP" to "key_height_dp",
            "KEY_CANDIDATE_SPACING_DP" to "candidate_spacing_dp",
            "KEY_KEY_TRANSPARENCY_PERCENT" to "key_transparency_percent",
            // 皮肤与主题
            "KEY_KEYBOARD_SKIN" to "keyboard_skin",
            "KEY_SKIN_LIGHT" to "skin_light",
            "KEY_SKIN_DARK" to "skin_dark",
            "KEY_THEME_MODE" to "theme_mode",
            "KEY_THEME_LIGHT_AT" to "theme_light_at",
            "KEY_THEME_DARK_AT" to "theme_dark_at",
            // 图库
            "KEY_GALLERY_CELL_HEIGHT_DP" to "gallery_cell_height_dp",
        )
        val wrong = golden.filter { (name, value) -> keyConstants[name] != value }
            .map { (name, value) -> "$name 期望 \"$value\"，实际 \"${keyConstants[name] ?: "（未定义）"}\"" }
        assertTrue(
            "这些键的名字变了 —— 等于老用户那项设置清零、旧备份里的同项被丢弃：\n    ${wrong.joinToString("\n    ")}\n" +
                "确要改键，请同时改本表并写「读旧键 → 写新键」的一次性迁移。",
            wrong.isEmpty(),
        )
    }
}
