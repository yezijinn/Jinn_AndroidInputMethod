package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份覆盖面守卫（源码对拍）：`Prefs` 里每新增一个持久化键，都必须同时出现在导出与导入白名单里。
 *
 * 为什么需要它：用户对这项功能的验收口径是「**所有**配置与设置参数都能导出/导入」，漏一个键
 * 就等于备份不完整；而键名是字符串常量，编译器不报、普通单测也看不见（真实读写要 Android Context）。
 * 所以这里直接读 `Prefs.kt` 源码做静态对拍：提取全部 `KEY_* = "..."` 常量，逐个核对两个白名单方法体
 * 是否都提到了它。以后加键忘了加进备份，这条会先红。
 */
class PrefsBackupCoverageTest {

    private val sourceFile: File = listOf(
        File("src/main/java/com/jinn/inputmethod/Prefs.kt"),
        File("app/src/main/java/com/jinn/inputmethod/Prefs.kt"),
    ).firstOrNull { it.isFile } ?: error("找不到 Prefs.kt（当前工作目录=${File("").absolutePath}）")

    private val source: String = sourceFile.readText()

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
        return tail.substring(0, cuts.minOrNull() ?: tail.length)
    }

    @Test
    fun `每个持久化键都必须同时在导出与导入白名单里`() {
        // 精确 26：用「>=」时，新增键被正则漏检或键被误删都不会报警，守卫价值被高估。
        // 改键数是正常维护，改完同步这个数。
        assertEquals("提取到的键常量应是 26 个（改键数请同步本断言）", 26, keyConstants.size)

        val export = bodyOf("exportForBackup")
        val import = bodyOf("importFromBackup")

        val missingExport = keyConstants.keys.filterNot { export.contains(it) }
        val missingImport = keyConstants.keys.filterNot { import.contains(it) }

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
        // 全部 25 个「普通」键 → 期望的取值工具（`KEY_FAVORITE_SYMBOLS` 走归一分支，单列在下面）。
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
            "KEY_USER_LEARNING" to "asBool(v)",
            "KEY_SHOW_RARE_CHARS" to "asBool(v)",
            "KEY_VOICE_INPUT" to "asBool(v)",
            "KEY_KEY_CORNER_DP" to "asFloat(v)",
            "KEY_KEY_GAP_DP" to "asFloat(v)",
            "KEY_KEY_TRANSPARENCY_PERCENT" to "asInt(v)",
            "KEY_KEYBOARD_SKIN" to "asString(v)",
            "KEY_THEME_MODE" to "asInt(v)",
            "KEY_SYMBOL_ORDER" to "asString(v)",
            "KEY_THEME_LIGHT_AT" to "asInt(v)",
            "KEY_THEME_DARK_AT" to "asInt(v)",
            "KEY_UPDATE_LAST_CHECK_AT" to "asLong(v)",
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
            Triple("keyboardSkinId", "KeyboardSkins.byId", "未知皮肤 id 会取不到色板"),
            Triple("symbolGroupOrder", "SymbolOrder.serialize", "脏顺序串会缺分组"),
            Triple("themeMode", "MODE_SYSTEM..", "未知模式编号会落到无效主题"),
            Triple("themeLightAtMinutes", "floorMod", "负值/超 24h 会显示成 -1:-30 这类非法时刻"),
            Triple("themeDarkAtMinutes", "floorMod", "同上"),
        )
        for ((prop, marker, why) in guards) {
            assertTrue("$prop 的归一（$marker）不见了：$why", propertyBlock(prop).contains(marker))
        }
    }

    @Test
    fun `导出与导入两侧都必须覆盖全部键`() {
        // 比逐项类型更强的兜底：新增一个键时，只加进导出、或只加进导入，都会在这里被抓住
        val export = bodyOf("exportForBackup")
        val import = bodyOf("importFromBackup")
        val exportKeys = Regex("KEY_[A-Z0-9_]+").findAll(export).map { it.value }.toSet()
        val importKeys = Regex("KEY_[A-Z0-9_]+").findAll(import).map { it.value }.toSet()

        assertEquals(
            "导出侧漏掉的键：${keyConstants.keys - exportKeys}",
            emptySet<String>(),
            keyConstants.keys - exportKeys,
        )
        assertEquals(
            "导入侧漏掉的键：${keyConstants.keys - importKeys}",
            emptySet<String>(),
            keyConstants.keys - importKeys,
        )
    }

    @Test
    fun `剪贴板配置的两个键也必须进白名单`() {
        val clipSource = listOf(
            File("src/main/java/com/jinn/inputmethod/ClipboardPrefs.kt"),
            File("app/src/main/java/com/jinn/inputmethod/ClipboardPrefs.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 ClipboardPrefs.kt")
        val text = clipSource.readText()
        for (key in listOf("KEY_ENABLED", "KEY_MAX_ITEMS")) {
            assertTrue("$key 未在 ClipboardPrefs 中声明", text.contains("private const val $key"))
            assertTrue("$key 未进导出白名单", text.contains("out[$key] = it"))
            assertTrue("$key 未进导入白名单", text.substringAfter("importFromBackup").contains(key))
        }
    }
}
