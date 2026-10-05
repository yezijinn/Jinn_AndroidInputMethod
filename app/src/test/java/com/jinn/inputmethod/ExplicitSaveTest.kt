package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显式保存守卫（2026-10-01）：「翻译设置」与「OpenAI 兼容配置」两页必须有明确的
 * 保存按钮与结果提示，且**每个输入框**都要进保存路径 —— 「我填了但没生效」在编译期与界面上都无感，
 * 只有真机点按 + 落盘才能验，而最容易出的错正是「新增字段忘了保存」。
 *
 * 判据全部是源码对拍（走 [TestSources] 剥注释）：把「布局里的 `edit_*`」与「保存函数体里的变量」
 * 逐项对上，漏一个就红；保存入口也必须真的调用落盘与回填（`flush` / `loadValues`）。
 */
class ExplicitSaveTest {

    /** 布局源码（剥 XML 注释；`//` 与 `<!-- -->` 都会被 [TestSources.codeOf] 处理） */
    private fun xml(name: String): String = TestSources.codeOf(
        TestSources.rawSource(
            "src/main/res/layout/$name",
            "app/src/main/res/layout/$name",
        )
    )

    /**
     * 花括号配对取函数体（含签名行到收尾大括号）。
     *
     * `source` 必须是**已剥注释**的代码文本（[TestSources.codeSource]）—— 否则注释里写一句
     * `saveCredentials()` 就能让断言看起来满足（BUG.md L-116 的同型坑）。
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里没找到 `$signature`（改名了？本守卫要同步）", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("`$signature` 不是函数定义（没有 `{`）", open > start)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("`$signature` 的花括号没配平")
    }

    private fun layoutEditIds(name: String): Set<String> =
        Regex("""@\+id/(edit_[a-z_]+)""").findAll(xml(name)).map { it.groupValues[1] }.toSet()

    /**
     * 布局里的输入框 id → Activity 里的变量名。
     *
     * 新增输入框时必须同时登记（否则第一条断言直接红）——「登记了但保存函数漏写」由第二条抓。
     */
    private val translationFields = mapOf(
        "edit_aliyun_key_id" to "editAliyunKeyId",
        "edit_aliyun_key_secret" to "editAliyunKeySecret",
        "edit_azure_key" to "editAzureKey",
        "edit_azure_region" to "editAzureRegion",
        "edit_baidu_app_id" to "editBaiduAppId",
        "edit_baidu_secret" to "editBaiduSecret",
        "edit_baidu_llm_app_id" to "editBaiduLlmAppId",
        "edit_baidu_llm_key" to "editBaiduLlmKey",
        "edit_deepl_key" to "editDeeplKey",
    )

    private val openAiFields = mapOf(
        "edit_ai_name" to "editName",
        "edit_ai_base_url" to "editBaseUrl",
        "edit_ai_api_key" to "editApiKey",
        "edit_ai_model" to "editModel",
        "edit_ai_chat_path" to "editChatPath",
        "edit_ai_models_path" to "editModelsPath",
        "edit_ai_target_custom" to "editTargetCustom",
        "edit_ai_system" to "editSystem",
        "edit_ai_user" to "editUser",
        "edit_ai_temperature" to "editTemperature",
        "edit_ai_top_p" to "editTopP",
        "edit_ai_max_tokens" to "editMaxTokens",
        "edit_ai_response_path" to "editResponsePath",
        "edit_ai_timeout" to "editTimeout",
        "edit_ai_headers" to "editHeaders",
        "edit_ai_extra_json" to "editExtraJson",
    )

    @Test
    fun `翻译设置页的每个输入框都必须进保存路径`() {
        val ids = layoutEditIds("activity_translation_settings.xml")
        assertEquals("布局里的输入框与登记表必须一一对应（新增字段要同时登记）", translationFields.keys, ids)
        val body = bodyOf(
            TestSources.codeSource("TranslationSettingsActivity.kt"),
            // 只钉到函数名（形参在 2026-10-03 修复 L-717 时加了 `notify: Boolean = true`，
            // 锁死完整签名会让「加一个默认参数」这种无害改动也变红 —— 那是形态钉的过度收紧）
            "private fun saveCredentials(",
        )
        val missing = translationFields.values.filterNot { it in body }
        assertTrue("这些输入框没进 saveCredentials()：$missing（填了不会生效）", missing.isEmpty())
    }

    @Test
    fun `OpenAI 兼容页的每个输入框都必须进保存路径`() {
        val ids = layoutEditIds("activity_openai_settings.xml")
        assertEquals("布局里的输入框与登记表必须一一对应（新增字段要同时登记）", openAiFields.keys, ids)
        val body = bodyOf(
            TestSources.codeSource("OpenAiSettingsActivity.kt"),
            "private fun saveValues()",
        )
        val missing = openAiFields.values.filterNot { it in body }
        assertTrue("这些输入框没进 saveValues()：$missing（填了不会生效）", missing.isEmpty())
    }

    @Test
    fun `两页都要有固定的保存按钮与结果提示行`() {
        val expectations = listOf(
            "activity_translation_settings.xml" to ("btn_translate_save" to "text_translate_save_hint"),
            "activity_openai_settings.xml" to ("btn_ai_save" to "text_ai_save_hint"),
        )
        for ((layout, ids) in expectations) {
            val text = xml(layout)
            assertTrue("$layout 缺保存按钮 ${ids.first}", "@+id/${ids.first}" in text)
            assertTrue("$layout 缺提示行 ${ids.second}", "@+id/${ids.second}" in text)
        }
    }

    @Test
    fun `保存按钮必须落盘、回填并给提示`() {
        val pages = listOf(
            // 同上：只认「调用了 saveCredentials」这件事，不锁参数形态
            Triple("TranslationSettingsActivity.kt", "saveCredentials(", "btn_translate_save"),
            Triple("OpenAiSettingsActivity.kt", "saveValues()", "btn_ai_save"),
        )
        for ((file, writeCall, buttonId) in pages) {
            val source = TestSources.codeSource(file)
            assertTrue("$file 没把 $buttonId 绑到 saveAndNotify()", "setOnClickListener { saveAndNotify() }" in source)
            val body = bodyOf(source, "private fun saveAndNotify()")
            // 写 → 同步落盘 → 回填 → 提示：四步缺一，「真的保存了」就不成立
            assertTrue("$file 的保存入口没调用 $writeCall", writeCall in body)
            assertTrue("$file 的保存入口没调用 prefs.flush()（异步落盘不算保存）", "prefs.flush()" in body)
            assertTrue("$file 的保存入口没回填（界面与本机真实值会分叉）", "loadValues()" in body)
            assertTrue("$file 的保存入口没写结果提示", "textSaveHint.text" in body)
            assertTrue("$file 的保存入口没有成功的 toast 提示", "toast(TEXT_SAVED)" in body)
            assertTrue("$file 的保存入口没有失败的 toast 提示", "toast(TEXT_SAVE_FAILED)" in body)
        }
    }

    @Test
    fun `保存提示文案两页一致`() {
        val translate = TestSources.codeSource("TranslationSettingsActivity.kt")
        val openAi = TestSources.codeSource("OpenAiSettingsActivity.kt")
        for (line in listOf(
            """const val TEXT_SAVE = "保存"""",
            """const val TEXT_SAVED = "已保存到本机"""",
            """const val TEXT_SAVE_FAILED = "保存未完成，请重试"""",
        )) {
            assertTrue("翻译设置页缺文案：$line", line in translate)
            assertTrue("OpenAI 兼容页缺文案：$line", line in openAi)
        }
    }

    /**
     * 凭据输入框的**成组防护**守卫（2026-10-02 修复 L-409 的配套）。
     *
     * 两个口径必须在位，缺一等于没防护：
     *  · `inputType="textPassword"` —— 无障碍树里是掩码。少了它，TalkBack 会**朗读凭据全文**，
     *    任何持 `canRetrieveWindowContent` 的服务也能程序化读走（百度两家 AppID 就是这么漏的：
     *    `Prefs` 里它们加密落盘、并被 `configuredCredentials()` 当凭据清理，通道上却按普通文本对待）；
     *  · `saveEnabled="false"` —— 不进系统 SavedState。三页都没有覆写 `onSaveInstanceState`，
     *    全靠 View 默认保存 ⇒ 旋转 / 进程回收后 Bundle 里就是明文。
     *
     * 为什么要**清单式**对拍：同类缺口此前被分三次发现（L-372 / L-390 / L-409），每次都只补一层。
     * 新增凭据框忘了任一项，本用例立刻红。
     */
    @Test
    fun `承载凭据的输入框必须同时有掩码与 SavedState 防护`() {
        // 机密类：值本身就是凭据 —— 掩码 + 不进 Bundle，两项都要。
        // 百度两家 AppID 按 L-409 的裁定算机密（`Prefs` 里它们加密落盘、并被
        // `configuredCredentials()` 当凭据清理，通道上按普通文本对待是口径矛盾）。
        val secrets = mapOf(
            "activity_translation_settings.xml" to listOf(
                "edit_aliyun_key_id", "edit_aliyun_key_secret", "edit_azure_key",
                "edit_baidu_app_id", "edit_baidu_secret",
                "edit_baidu_llm_app_id", "edit_baidu_llm_key", "edit_deepl_key",
            ),
            "activity_openai_settings.xml" to listOf("edit_ai_api_key"),
        )
        // 配置类：值不算机密（区域名 / 端点），但**同样不该进系统 Bundle** —— 旋转或回收后
        // Bundle 里就是明文，而 URL 里可能承载凭据（见 L-412）。
        val configs = mapOf(
            "activity_translation_settings.xml" to listOf("edit_azure_region"),
            "activity_openai_settings.xml" to listOf("edit_ai_base_url"),
        )
        for ((layout, ids) in secrets) {
            for (id in ids) {
                val element = editElement(layout, id)
                assertTrue(
                    "$layout 的 $id 缺 inputType=\"textPassword\"（读屏会朗读凭据全文）",
                    "textPassword" in element,
                )
                assertTrue(
                    "$layout 的 $id 缺 saveEnabled=\"false\"（旋转后被写进系统 Bundle）",
                    "saveEnabled=\"false\"" in element,
                )
            }
        }
        for ((layout, ids) in configs) {
            for (id in ids) {
                assertTrue(
                    "$layout 的 $id 缺 saveEnabled=\"false\"（旋转后被写进系统 Bundle）",
                    "saveEnabled=\"false\"" in editElement(layout, id),
                )
            }
        }

        // **名单完备性**（2026-10-02 补）：上面的机密名单是硬编码的 —— 新增一个凭据框时若忘了登记，
        // 上面两个循环就会**整段漏过它**（守卫只检查名单里的），这正是 L-372 / L-390 / L-409 三次
        // 「只补一层」能反复发生的原因。用命名约定做机械兜底：名字含敏感词的框必须进机密名单。
        val sensitive = Regex("key|secret|app_id|token|password|credential")
        for ((layout, ids) in secrets) {
            val missing = layoutEditIds(layout)
                // `max_tokens` 是数字参数（模型输出上限），不是凭据 —— 命名里带 `token` 是巧合
                .filter { sensitive.containsMatchIn(it) && "max_tokens" !in it }
                .filterNot { it in ids }
            assertTrue(
                "$layout 里这些输入框名字像凭据却没进机密名单（会逃过掩码 / Bundle 防护）：$missing",
                missing.isEmpty(),
            )
        }
    }

    @Test
    fun `导入在途时显式保存也要拦住`() {
        // 导入在途时界面还是旧值，全字段写回会把刚导入的 model / baseUrl / 提示词覆盖掉。
        // onPause 早有守卫，显式保存这条路必须同款 —— 两处口径一致才算修好
        val src = TestSources.codeSource("OpenAiSettingsActivity.kt")
        val save = src.substringAfter("private fun saveAndNotify()")
        assertTrue("saveAndNotify 锚点失效（源码结构变了）", save.isNotEmpty() && save.length < src.length)
        assertTrue("导入在途必须早退", "ConfigBackupManager.importing" in save)
        assertTrue("早退要给出提示文案", "TEXT_SAVE_BLOCKED_IMPORTING" in save)
    }

    /** 取某个 `edit_*` 所在的 `<EditText … />` 元素源码（找不到直接失败，提示同步守卫） */
    private fun editElement(layout: String, id: String): String =
        Regex("""<EditText[\s\S]*?/>""").findAll(xml(layout))
            .map { it.value }
            .firstOrNull { "@+id/$id" in it }
            ?: error("$layout 里找不到 $id（改名了？本守卫要同步）")
}
