package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「语音转为文字」页的接线守卫（2026-11-01：设置页的四块语音配置整体迁到 [VoiceSettingsActivity]）。
 *
 * 这条只能在源码 / 资源上对拍（Activity 与视图进不了 JVM 单测）：
 *  - 服务商目录是**纯数据**，可以直接对拍（URL 全 https、id / URL 不重复、分类被覆盖）；
 *  - 主页必须不再残留语音控件、只剩一个入口按钮；
 *  - 新页的控件 id 必须一个不少（保存 / 总开关 / 麦克风 / 服务端 / 两个下拉 / 提示词 / 两个勾选 / 服务商容器）；
 *  - 清单注册与按钮跳转必须接上；
 *  - 新文案不许回流 `values/strings.xml`，链接打不开必须留痕 + 提示。
 *
 * ⚠ 反向钉一律走 `TestSources`（剥注释后再判），别自己写「按整行剥注释」（会被元守卫点名）。
 */
class VoiceSettingsPageTest {

    @Test
    fun `服务商目录的链接与分类必须自洽`() {
        val all = VoiceProviders.ALL
        assertTrue("服务商目录少于 20 条（调研表要求尽可能齐全）：${all.size}", all.size >= 20)

        for (p in all) {
            assertTrue("${p.id} 的文档地址必须 https：${p.docUrl}", p.docUrl.startsWith("https://"))
            assertTrue("${p.id} 的 id 不能为空", p.id.isNotBlank())
            assertTrue("${p.id} 的显示名不能为空", p.name.isNotBlank())
            assertTrue("${p.id} 的特点不能为空", p.note.isNotBlank())
            assertTrue("${p.id} 的分类「${p.category}」不在 CATEGORIES 里", p.category in VoiceProviders.CATEGORIES)
        }
        assertEquals(
            "id 必须唯一（重复会让日志与测试对不上号）",
            all.size, all.map { it.id }.toSet().size,
        )
        assertEquals(
            "URL 必须唯一（同一条文档不该挂两个名字）",
            all.size, all.map { it.docUrl }.toSet().size,
        )
        // 这两条实测原地址会 302，逐条探活后改成最终地址（其余 27 条原本就是 200）
        assertEquals(
            "Speechmatics 必须用最终地址（/introduction 会 302）",
            "https://docs.speechmatics.com/",
            all.first { it.id == "speechmatics" }.docUrl,
        )
        assertEquals(
            "Gladia 必须用最终地址（根路径会跳转）",
            "https://docs.gladia.io/chapters/introduction",
            all.first { it.id == "gladia" }.docUrl,
        )
        // 每个分类至少一条：空分类会在页面上留下一个只有标题的空段
        for (category in VoiceProviders.CATEGORIES) {
            assertTrue(
                "分类「$category」一条都没有",
                all.any { it.category == category },
            )
        }
        assertEquals(
            "条目用了 CATEGORIES 之外的分类（页面按 CATEGORIES 分组，那些条目永远不显示）",
            VoiceProviders.CATEGORIES.toSet(), all.map { it.category }.toSet(),
        )
    }

    @Test
    fun `主页不再有语音控件只剩一个入口`() {
        // ⚠ 反向钉（「本页不许再出现」）走原文：连注释里也不许再提这些 id，否则后来人会照着补回来
        val raw = TestSources.rawSource(
            "src/main/res/layout/activity_settings.xml",
            "app/src/main/res/layout/activity_settings.xml",
        )
        for (gone in listOf("switch_voice_input", "card_voice_server", "card_mic_permission", "edit_host", "edit_port")) {
            assertFalse("主页布局仍在用已迁走的语音控件：$gone", gone in raw)
        }
        // 正向钉走剥注释后的代码：在注释里写一遍 id 不算接线
        val layout = TestSources.codeOf(raw)
        assertTrue("主页缺少语音入口按钮 btn_voice_settings", "@+id/btn_voice_settings" in layout)
        // 用户第 2 条：状态小字「已开启 · ws://…」要删干净（布局、代码、测试都不许再提）
        assertFalse("主页仍残留语音状态行 text_voice_state", "@+id/text_voice_state" in raw)
        assertFalse("设置页代码仍引用 text_voice_state", "text_voice_state" in TestSources.codeSource("SettingsActivity.kt"))
        // 用户原话是「改成单击的按钮」：入口必须是 Button，不是开关也不是整行可点
        assertTrue(
            "语音入口必须是 Button（用户要求改成单击按钮）",
            Regex("""<Button\b[^>]*@\+id/btn_voice_settings""").containsMatchIn(layout),
        )

        // 用户第 2 条：两个按钮（绑定悬浮键盘 / 语音转为文字）在同一张卡里一行两个，各占一半、中间 8dp。
        // 取下限：从「包含 btn_floating_keyboard 的那一层 LinearLayout」到「btn_voice_settings 之后的收尾」。
        val rowStart = layout.lastIndexOf("<LinearLayout", layout.indexOf("@+id/btn_floating_keyboard"))
        val rowEnd = layout.indexOf("</LinearLayout>", layout.indexOf("@+id/btn_voice_settings"))
        assertTrue("找不到两个按钮所在的那一行", rowStart in 0 until rowEnd)
        val row = layout.substring(rowStart, rowEnd)
        assertTrue("同一行里缺少「绑定悬浮键盘」按钮", "@+id/btn_floating_keyboard" in row)
        assertTrue("同一行里缺少「语音转为文字」按钮", "@+id/btn_voice_settings" in row)
        assertEquals(
            "两个按钮必须各占一半（layout_width=0dp + weight=1）",
            2, Regex("""android:layout_weight="1"""").findAll(row).count(),
        )
        assertEquals(
            "两个按钮都必须写 layout_width=\"0dp\"（否则 weight 不生效）",
            2, Regex("""android:layout_width="0dp"""").findAll(row).count(),
        )
        assertTrue("两个按钮之间必须留 8dp", """android:layout_marginStart="8dp"""" in row)
        // 两行内不许再夹小字描述（用户第 2 条：「小字描述不要」）
        assertFalse("两个按钮之间还夹着 TextView 小字", "<TextView" in row)

        val code = TestSources.codeSource("SettingsActivity.kt")
        assertTrue("设置页没有绑定入口按钮", "R.id.btn_voice_settings" in code)
        for (gone in listOf("R.id.switch_voice_input", "R.id.card_voice_server", "R.id.card_mic_permission")) {
            assertFalse("设置页代码仍在引用已迁走的控件：$gone", gone in code)
        }
        // 按钮文本逐字（不是「语音转文字」「语音听写」）：这一句是用户点名的文案
        assertTrue(
            "入口按钮文本必须逐字是「语音转为文字」",
            "const val TEXT_VOICE_ENTRY = \"语音转为文字\"" in code,
        )
        assertTrue("入口按钮文本没有下发到按钮上", "btnVoiceSettings.text = TEXT_VOICE_ENTRY" in code)
        // 用户第 2 条：悬浮键盘按钮改名为「绑定悬浮键盘」
        assertTrue(
            "悬浮键盘按钮文本必须逐字是「绑定悬浮键盘」",
            "const val TEXT_FLOATING_ENTRY = \"绑定悬浮键盘\"" in code,
        )
        assertTrue("悬浮键盘按钮文本没有下发到按钮上", "text = TEXT_FLOATING_ENTRY" in code)
        // 状态行删除后，onResume 不该再刷新一个不存在的摘要
        assertFalse(
            "onResume 仍在刷新已删除的语音状态摘要",
            "refreshVoiceState" in TestSources.blockAfter(code, "override fun onResume"),
        )
    }

    @Test
    fun `底部使用说明六行由代码下发且第 5 行已更新`() {
        val layout = TestSources.codeOf(
            TestSources.rawSource(
                "src/main/res/layout/activity_settings.xml",
                "app/src/main/res/layout/activity_settings.xml",
            )
        )
        // 布局只留 id：文案走代码常量（values/strings.xml 默认禁改）
        assertTrue("底部使用说明缺少 @+id/text_usage", "@+id/text_usage" in layout)
        assertFalse("布局仍在引用 @string/settings_usage（文案应由 TEXT_USAGE 下发）", "@string/settings_usage" in layout)
        val idAt = layout.indexOf("@+id/text_usage")
        val tag = layout.substring(layout.lastIndexOf("<TextView", idAt), layout.indexOf("/>", idAt))
        assertFalse("使用说明 TextView 上还写着 android:text", "android:text=" in tag)

        val code = TestSources.codeSource("SettingsActivity.kt")
        assertTrue("使用说明没有下发到控件", "textUsage.text = TEXT_USAGE" in code)
        val usage = Regex("""const val TEXT_USAGE = ([\s\S]*?)\n\n""").find(code)
            ?.groupValues?.get(1)
            ?: error("源码里没找到 TEXT_USAGE 常量")
        // 六行 = 五个 \n 分隔符（多一行少一行都说明文案被改过）
        assertEquals("使用说明必须正好六行", 5, Regex("""\\n""").findAll(usage).count())
        for (line in listOf(
            "拼音键盘：26 键全拼 / 双拼（8 套方案），空格选词。",
            "长串拼音打错了：点候选栏右侧的 ✕ 可整串清掉",
            "「删除键」双击再长按，清空整个输入框。",
            "拼音键盘按下的字母，按回车直接输出英文。",
            "长按空格键：按住说话，上滑取消，松手转文字",
            "作者 https://github.com/yezijinn",
        )) {
            assertTrue("使用说明缺了这一行：$line", line in usage)
        }
        // 过期的那句必须彻底消失（用户第 1 条点名要改）
        assertFalse("使用说明仍留着过期的语音说明", "启用语音输入之后" in usage)
    }

    @Test
    fun `新页控件必须齐全`() {
        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_voice_settings.xml",
            "app/src/main/res/layout/activity_voice_settings.xml",
        )
        for (id in listOf(
            "switch_voice_input", "card_mic_permission", "card_voice_server", "text_mic_state", "btn_grant",
            "edit_host", "edit_port", "check_lock_server",
            "spinner_language", "spinner_default_mode",
            "edit_prompt", "check_strip", "check_composing",
            "btn_voice_save", "text_voice_save_hint", "container_providers",
            // 2026-11-01：识别后端选择 + 讯飞三件凭据
            "card_voice_backend", "spinner_voice_backend",
            "card_xf_credentials", "edit_xf_app_id", "edit_xf_api_key", "edit_xf_api_secret",
            "check_xf_show",
            // 2026-11-01：OpenAI 兼容（第三个后端）的地址 / 模型 / Key 卡
            "card_oai_credentials", "text_oai_desc", "edit_oai_base", "edit_oai_model",
            "edit_oai_api_key", "check_oai_show",
            // 2026-11-01：阿里云百炼（第四个后端）的 WS 地址 / 模型 / Key 卡
            "card_ali_credentials", "text_ali_desc", "edit_ali_ws_url", "edit_ali_model",
            "edit_ali_api_key", "check_ali_show",
            // 2026-11-02：腾讯云一句话识别（第五个后端）与 Azure 短音频识别（第六个后端）
            "card_tencent_credentials", "text_tencent_desc", "edit_tencent_secret_id",
            "edit_tencent_secret_key", "edit_tencent_engine", "check_tencent_show",
            "card_azure_credentials", "text_azure_desc", "edit_azure_base_url",
            "edit_azure_api_key", "edit_azure_language", "check_azure_show",
            // 2026-11-02：百度短语音识别（第七个后端）与 Deepgram（第八个后端）
            "card_baidu_credentials", "text_baidu_desc", "edit_baidu_api_key",
            "edit_baidu_secret_key", "edit_baidu_dev_pid", "check_baidu_show",
            "card_deepgram_credentials", "text_deepgram_desc", "edit_deepgram_api_key",
            "edit_deepgram_model", "edit_deepgram_language", "check_deepgram_show",
            // 2026-11-01：总开关标题（文案由常量下发）+ 语音测试卡
            "text_voice_master_title", "label_voice_test", "card_voice_test",
            "edit_voice_test", "btn_voice_test", "text_voice_test_result",
        )) {
            assertTrue("新页布局缺少 @+id/$id", "@+id/$id" in layout)
        }
        // 两个下拉必须带「用户没碰过就不覆盖」的探针（BUG.md L-821）；第三个是识别后端
        assertEquals(
            "语言 / 默认键盘样式 / 识别后端必须都用 UserAwareSpinner",
            3, Regex("""<com\.jinn\.inputmethod\.UserAwareSpinner\b""").findAll(layout).count(),
        )

        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        assertTrue("页面没读 userInteracted（闸门丢了）", "userInteracted" in code)
        assertTrue("保存入口没接线", "saveAndRestart(" in code)
        assertTrue("host 校验丢了", "Prefs.isValidHost(" in code)
        assertTrue("端口校验丢了", "in 1..65535" in code)
        assertTrue("总开关没联动隐藏两张卡", "applyVoiceBlocksVisibility(" in code)
        assertTrue("重启必须走共用的 ImeRestart", "ImeRestart.restart(" in code)
        assertTrue("主题应用点必须走 ThemeManager.themedContext", "ThemeManager.themedContext(" in code)
        assertTrue("关闭键必须取 PageChrome.CLOSE", "PageChrome.CLOSE" in code)

        // 用户第 1 条要求是「把那个开关**和它的功能**搬进来」：开关必须是同一件事 ——
        // 同 id（上面已钉）、回填读同一个 Prefs 键、保存写回同一个键（否则搬完之后开关与 IME 脱钩）
        assertTrue(
            "总开关没有从 Prefs 回填（打开页面必须先显示真实状态）",
            "switchVoiceInput.isChecked = prefs.voiceInputEnabled" in code,
        )
        val save = TestSources.blockAfter(code, "private fun saveAndRestart")
        assertTrue("保存时开关状态不是取自开关本身", "val voiceEnabled = switchVoiceInput.isChecked" in save)
        assertTrue("保存没有写回 voiceInputEnabled", "prefs.voiceInputEnabled = voiceEnabled" in save)
        // 关闭态保存必须跳过 host/port 校验（否则开关关着就永远存不了）：
        // 校验块现在按后端分成两支，两条都必须在 voiceEnabled 之内
        assertTrue("关闭态保存没有跳过校验", "if (voiceEnabled && backend == VoiceBackend.XF) {" in save)
        assertTrue("caps 分支的校验没挂在 voiceEnabled 上", "} else if (voiceEnabled) {" in save)
        assertTrue("接口空回落默认端口的兜底丢了", "port ?: Prefs.DEFAULT_PORT" in save)

        // 总开关那张卡**不随开关隐藏**（关掉之后还得能再打开）：隐藏的只有麦克风卡与服务端卡
        assertFalse(
            "card_voice_master 不许跟着总开关隐藏（关掉就再也点不开了）",
            "card_voice_master.visibility" in code,
        )
        // 服务商目录卡同理：总开关关着也要能看见（设计如此），文案里已注明只内置 CapsWriter 协议
        assertFalse(
            "card_voice_providers 不许跟着总开关隐藏",
            "card_voice_providers.visibility" in code,
        )
        // 四个即时写盘项（三勾选 + 默认键盘样式）必须真的在写 Prefs：文案说的是「即时生效」
        for (write in listOf(
            "prefs.stripTrailingPunc = checked",
            "prefs.useComposing = checked",
            "prefs.lockServer = checked",
            "prefs.defaultKeyboardMode = mode",
        )) {
            assertTrue("即时写盘丢了：$write", write in code)
        }

        // 服务商行是 card_voice_providers **卡内的纯行**：逐行再套一层卡就是卡套卡（双层描边 + 内缩）
        assertFalse("服务商行不许再套一层 PageStyle.addCard（卡套卡）", "PageStyle.addCard(" in code)
        assertTrue("卡内纯行必须有分隔线", "rowDivider(" in code)
        // 「文档」按钮的可点区域**就是按钮自己**（行内边距点在按钮外不触发）：键高必须 ≥ 40dp 基线。
        // 36dp + 行上下各 2dp 的算法看着是 40dp，实际只有 36dp 能点。
        // 只扫 addProviderRow 内部：分组标题行也有 minHeight，全文件扫会把它算进来
        val docRow = TestSources.blockAfter(code, "private fun addProviderRow")
        val docHeights = Regex("""min(?:imum)?Height = PageStyle\.dp\(context, (\d+)\)""")
            .findAll(docRow).map { it.groupValues[1].toInt() }.toList()
        assertEquals("「文档」按钮必须同时设 minHeight 与 minimumHeight", 2, docHeights.size)
        for (h in docHeights) assertTrue("「文档」按钮键高 ${h}dp 低于 40dp 触摸基线", h >= 40)
    }

    @Test
    fun `清单注册与按钮跳转必须接上`() {
        val manifest = TestSources.codeOf(
            TestSources.rawSource(
                "src/main/AndroidManifest.xml",
                "app/src/main/AndroidManifest.xml",
            )
        )
        assertTrue("AndroidManifest 未注册 VoiceSettingsActivity", "android:name=\".VoiceSettingsActivity\"" in manifest)
        assertTrue(
            "新页必须 exported=false（只有本应用能打开）",
            Regex("""android:name="\.VoiceSettingsActivity"\s+android:exported="false"""").containsMatchIn(manifest),
        )
        assertTrue(
            "设置页按钮必须真的跳到新页",
            "VoiceSettingsActivity::class.java" in TestSources.codeSource("SettingsActivity.kt"),
        )
    }

    @Test
    fun `新文案不得回流资源且链接失败要有兜底`() {
        val strings = TestSources.rawSource(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        // 新页文案一律在代码里下发（AGENTS.md「values/strings.xml 默认禁改」）
        for (text in listOf("语音转为文字", "保存并重启输入法", "在线语音服务", "服务商")) {
            assertFalse("strings.xml 出现了新页文案「$text」（应放在 VoiceSettingsActivity 的 TEXT_ 常量里）", text in strings)
        }
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        // 只判「常量声明在」是自证式断言（声明了没人用照样绿）：这里一律钉**下发到控件**的那一行
        for (use in listOf(
            ".text = TEXT_TITLE",
            ".text = TEXT_DESC",
            "text = TEXT_SAVE",
            "textSaveHint.text = TEXT_SAVE_HINT_IDLE",
            ".text = TEXT_SECTION_SERVER",
            ".text = TEXT_SECTION_PROVIDERS",
            ".text = TEXT_PROVIDERS_DESC",
            "textSaveHint.text = TEXT_RESTARTING",
            "text = TEXT_DOC",
            "text = PageChrome.CLOSE",
        )) {
            assertTrue("文案常量没有下发到控件（只声明等于没接线）：$use", use in code)
        }
        // 落盘失败那句必须写回**本页**的状态行，否则用户只看到「正在重启」而进程没重启
        val restart = TestSources.blockAfter(code, "ImeRestart.restart(this)")
        assertTrue(
            "落盘失败的回退没写回本页状态行",
            "textSaveHint.text = message" in restart,
        )
        assertTrue(
            "落盘失败必须恢复保存按钮（进程没被杀，用户还要能重试）",
            "btnSave.isEnabled = true" in restart,
        )

        // 打不开文档时不许静默：判据限定在 openDoc 内部 —— 全文件里任何一处 Diagnostics.w
        // 都能满足的写法是空的（本类另有两处 Diagnostics.w，删掉这里这行照样绿，已实测）
        val openDoc = TestSources.blockAfter(code, "private fun openDoc")
        assertTrue("打开文档失败必须留日志", "Diagnostics.w(" in openDoc)
        assertTrue(
            "打开文档失败必须给用户一句提示",
            "Toast.makeText(this, TEXT_DOC_FAILED" in openDoc,
        )
        assertTrue("文档必须走 ACTION_VIEW 交给系统浏览器", "Intent.ACTION_VIEW" in openDoc)
        assertTrue("打不开文档必须有失败分支（runCatching 的 false 走不到就静默）", "if (!ok)" in openDoc)

        // 文案与行为不得相反：四个即时写盘项（三勾选 + 默认键盘样式）在页面说明里必须如实交代
        assertFalse(
            "文案不许说「不点保存不生效」——勾选项与默认键盘样式是改动即写",
            "不点保存不生效" in code,
        )
        assertTrue("页面说明必须交代哪些项改动即保存", "改动即保存" in code)
    }

    @Test
    fun `服务商目录默认只展开国内云且标题带条数与读屏`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")

        // 默认只展开「国内云服务」：初始集合必须恰好由 CAT_CN 一个元素构造
        val decl = TestSources.lineOf(code, "expandedCategories: MutableSet<String> =")
        assertTrue("默认展开集合必须只含 CAT_CN：$decl", "mutableSetOf(VoiceProviders.CAT_CN)" in decl)
        for (other in listOf("CAT_INTL", "CAT_AGG", "CAT_SELF")) {
            assertFalse("默认展开集合混进了 $other（默认只允许展开国内云服务）", other in decl)
        }

        // 标题 = 方向标记 + 分类名 + 条数
        val titleText = TestSources.blockAfter(code, "private fun groupTitleText")
        assertTrue("展开态方向标记丢了", "TEXT_GROUP_EXPANDED" in titleText)
        assertTrue("收起态方向标记丢了", "TEXT_GROUP_COLLAPSED" in titleText)
        assertTrue(
            "标题必须带条数（括号由常量下发）",
            "TEXT_GROUP_COUNT_LEFT" in titleText && "TEXT_GROUP_COUNT_RIGHT" in titleText,
        )

        // 标题行可点 + 读屏读出「条数 / 已展开 / 已收起」
        val title = TestSources.blockAfter(code, "private fun providerGroupTitle")
        assertTrue("分组标题必须可点", "isClickable = true" in title)
        assertTrue("点标题必须切该组", "toggleGroup(category)" in title)
        // 13sp 正文 + 上下各 10dp 内边距只有 39~40dp：靠内边距凑不出触摸基线，必须显式给 40dp
        val titleHeight = Regex("""minHeight = PageStyle\.dp\(context, (\d+)\)""")
            .findAll(title).singleOrNull()?.groupValues?.get(1)?.toInt()
        assertTrue("分组标题行高必须显式 ≥ 40dp（实测内边距凑到的只有 39dp）", (titleHeight ?: 0) >= 40)
        val state = TestSources.blockAfter(code, "private fun applyGroupState")
        assertTrue("标题必须下发 contentDescription", "group.title.contentDescription" in state)
        assertTrue(
            "组内容只靠 visibility 收起（不许动布局树）",
            "group.content.visibility = if (expanded) View.VISIBLE else View.GONE" in state,
        )
        assertTrue("读屏文案必须带条数与展开状态", "groupDesc(" in state)
        assertTrue("展开态读屏文案丢了", "const val TEXT_STATE_EXPANDED = \"已展开\"" in code)
        assertTrue("收起态读屏文案丢了", "const val TEXT_STATE_COLLAPSED = \"已收起\"" in code)

        // 反向钉：点标题不许重建整段列表（重建会把滚动位置甩回顶部）
        val toggle = TestSources.blockAfter(code, "private fun toggleGroup")
        assertFalse("点标题不许 removeAllViews", "removeAllViews" in toggle)
        assertFalse("点标题不许重建列表", "renderProviders()" in toggle)
        assertEquals(
            "renderProviders 只能「声明 + 渲染入口调一次」；点击回调里再调就是整段重建",
            2, Regex("renderProviders\\(\\)").findAll(code).count(),
        )
        // 只改 content / 标题文本，不动布局树的成员（改 visibility 与文案之外别无动作）
        assertTrue(
            "点击后必须显式播报状态（只改 text / contentDescription，读屏不会主动重读）",
            "announceForAccessibility(" in toggle,
        )

        // 分隔线归属：行间分隔线挂在**组自己的容器**里 ⇒ 组收起时整块 GONE，不会残留一条孤立横线；
        // 卡片里每组只挂「标题 + 内容」两块 ⇒ 组末不会多出一条
        val render = TestSources.blockAfter(code, "private fun renderProviders")
        assertTrue("行间分隔线必须加进组容器", "content.addView(rowDivider())" in render)
        assertEquals(
            "每组只往卡片挂两块（标题 + 内容）；第三处就是漏出来的分隔线",
            2, Regex("containerProviders\\.addView\\(").findAll(render).count(),
        )
        assertFalse(
            "分隔线不许直接挂到卡片上（收起后就是一条孤立横线）",
            Regex("containerProviders\\.addView\\(\\s*rowDivider").containsMatchIn(render),
        )
    }

    @Test
    fun `折叠状态存进 onSaveInstanceState 并在重建后恢复`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        val save = TestSources.blockAfter(code, "override fun onSaveInstanceState")
        assertTrue("折叠状态必须写进 Bundle", "putStringArray(" in save)
        assertTrue("Bundle 键必须是 STATE_EXPANDED_GROUPS", "STATE_EXPANDED_GROUPS" in save)
        assertTrue(
            "必须存分类名（StringArray），不是下标",
            "expandedCategories.toTypedArray()" in save,
        )

        val restore = TestSources.blockAfter(code, "private fun restoreExpandedGroups")
        assertTrue("重建后必须读回", "getStringArray(STATE_EXPANDED_GROUPS)" in restore)
        assertTrue("恢复后必须把状态落到视图上", "applyGroupState(category)" in restore)
        assertTrue("恢复必须过滤已不存在的分类", "it in VoiceProviders.CATEGORIES" in restore)
        // 空数组 = 「上次全部收起」这种合法状态：必须原样落成空集合，不许回落到默认展开国内云
        assertTrue(
            "恢复必须原样采用存下来的集合（空数组 = 全收起，不许被当成没存过）",
            "expandedCategories = saved.filter {" in restore,
        )
        assertTrue(
            "onCreate 没有调用恢复（只声明没人调等于没做）",
            "restoreExpandedGroups(savedInstanceState)" in
                TestSources.blockAfter(code, "override fun onCreate"),
        )
    }

    @Test
    fun `要保存的项改动后状态行变成有未保存的修改`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        val onCreate = TestSources.blockAfter(code, "override fun onCreate")
        // 六个编辑框 + 六家的凭据框（讯飞三件 / OpenAI 三项 / 阿里三项 / 腾讯三项 / Azure 三项 / 百度三项 / Deepgram 三项）
        // = 24；勾选框与默认键盘样式是即时写盘，不进脏状态
        assertEquals(
            "二十四个编辑框（主机 / 端口 / 提示词 / 六家后端凭据）都必须挂脏监听",
            24, Regex("markDirtyOnEdit\\(edit").findAll(onCreate).count(),
        )
        assertTrue(
            "总开关改动必须算脏",
            "refreshDirty()" in TestSources.blockAfter(code, "switchVoiceInput.setOnCheckedChangeListener"),
        )
        val lang = TestSources.blockAfter(code, "spinnerLanguage.onItemSelectedListener")
        assertTrue("语言下拉改动必须算脏", "refreshDirty()" in lang)
        // 脏判定这里**不许**闸 userInteracted：`AbsSpinner.onRestoreInstanceState` 只把位置记进
        // `mSyncPosition` 再 `requestLayout()`，选中位置与 onItemSelected 都在**之后第一次 layout**
        // 才落地（AOSP `AbsSpinner` / `Spinner.layout`）。闸掉探针 ⇒「只改语言再旋转」永远不重算，
        // 状态行停在干净、✕ 直接退。闸门（不改写导入值）在 readLanguage，下一条钉着。
        assertFalse(
            "语言下拉的脏判定不许闸 userInteracted（会把重建恢复的重算一起闸掉）",
            "userInteracted" in lang,
        )
        assertTrue(
            "「不许改写导入值」的闸门必须留在 readLanguage（BUG.md L-821，另有 RecentFixesRegressionTest 钉着）",
            "!spinnerLanguage.userInteracted && pos == languageAtLoad" in
                TestSources.blockAfter(code, "private fun readLanguage"),
        )

        // 只用 afterTextChanged：另两个回调必须是空实现
        val watch = TestSources.blockAfter(code, "private fun markDirtyOnEdit")
        assertTrue(
            "beforeTextChanged 不许写逻辑",
            "override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}" in watch,
        )
        assertTrue(
            "onTextChanged 不许写逻辑",
            "override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}" in watch,
        )
        assertTrue("脏标记必须来自 afterTextChanged", "override fun afterTextChanged(s: Editable?) {" in watch)
        assertTrue("afterTextChanged 里必须重算脏状态", "refreshDirty()" in watch)

        // 判据是「与上次落盘取值不同」，不是「碰过控件」：程序化 setText（回填 / 重建恢复）都会触发
        // TextWatcher，按「碰过就算脏」会让刚进页面、什么都没改的旋转判脏，也让改回原值无法变干净
        assertTrue(
            "脏状态必须按控件取值快照比对（不是置位式标记）",
            "dirty = currentInputs() != base" in TestSources.blockAfter(code, "private fun recomputeDirty"),
        )
        val inputs = TestSources.window(code, "private fun currentInputs", "private fun recomputeDirty")
        assertTrue("快照必须覆盖五项要保存的取值", "language = readLanguage()" in inputs)
        assertTrue("快照必须取自开关本身", "voiceEnabled = switchVoiceInput.isChecked" in inputs)
        assertTrue("快照必须取自三个编辑框", "prompt = editPrompt.text.toString()" in inputs)
        val snapshotInit = onCreate.indexOf("savedInputs = currentInputs()")
        assertTrue("基线必须在 onCreate 里建立", snapshotInit > 0)
        assertTrue(
            "基线必须取在所有程序化回填之后（否则刚进页面就判脏）",
            snapshotInit > onCreate.indexOf("restoreExpandedGroups(savedInstanceState)"),
        )
        // 旋转 / 进程回收：dirty 不进 Bundle，界面值却会被系统放回来 ⇒ 恢复后必须重算
        assertTrue(
            "恢复实例状态后必须重算脏标记（否则改了再旋转 = 状态行说干净、退出不提示）",
            "refreshDirty()" in TestSources.blockAfter(code, "override fun onRestoreInstanceState"),
        )

        // 即时写盘的四项不许掺进脏状态（勾选 / 换默认键盘立刻落盘，不需要再按保存）
        for (anchor in listOf(
            "checkStrip.setOnCheckedChangeListener",
            "checkComposing.setOnCheckedChangeListener",
            "checkLockServer.setOnCheckedChangeListener",
            "spinnerDefaultMode.onItemSelectedListener",
        )) {
            assertFalse(
                "「$anchor」是即时写盘，不许标脏",
                "refreshDirty()" in TestSources.blockAfter(code, anchor),
            )
        }

        assertTrue("TEXT_DIRTY 文案不对", "const val TEXT_DIRTY = \"有未保存的修改\"" in code)
        assertTrue(
            "状态行必须按脏状态切换（脏 → TEXT_DIRTY，干净 → 空闲提示）",
            "val text = if (dirty) TEXT_DIRTY else TEXT_SAVE_HINT_IDLE" in code,
        )
        // 活区上重复写同一句 = 每次按键重念一遍（onItemSelected 还会在 layout 里重排兄弟视图）
        assertTrue(
            "状态行文案没变就不许重写",
            "if (textSaveHint.text.toString() != text) textSaveHint.text = text" in code,
        )
        val save = TestSources.blockAfter(code, "private fun saveAndRestart")
        assertTrue("保存成功后必须清脏（否则状态行停在「有未保存的修改」）", "dirty = false" in save)
        // 落盘失败 = 没保存成功：基线退回、脏标记回来，而状态行要留给 ImeRestart 那句
        val failed = TestSources.blockAfter(code, "ImeRestart.restart(this)")
        val rollback = failed.indexOf("savedInputs = previous")
        val recount = failed.indexOf("recomputeDirty()")
        val message = failed.indexOf("textSaveHint.text = message")
        assertTrue("落盘失败必须把基线退回（否则这批改动被当成已保存）", rollback >= 0)
        assertTrue("落盘失败必须先重算脏标记再写状态行", rollback < recount && recount < message)
    }

    @Test
    fun `未保存退出走同一个确认入口`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        val confirm = TestSources.blockAfter(code, "private fun confirmExit")
        assertTrue("有未保存改动必须弹确认框", "AlertDialog.Builder(this)" in confirm)
        assertTrue("「继续编辑」按钮丢了", "setPositiveButton(TEXT_DIALOG_KEEP" in confirm)
        assertTrue("「放弃并退出」按钮丢了", "setNegativeButton(TEXT_DIALOG_DISCARD" in confirm)
        assertTrue("没有脏状态必须直接退出", "if (!dirty)" in confirm)
        assertTrue(
            "退出前必须静默重算脏标记（否则落盘失败后按 ✕ 会不提示就退出）",
            "recomputeDirty()" in confirm,
        )
        assertTrue("对话框标题必须是有未保存的修改", ".setTitle(TEXT_DIRTY)" in confirm)
        assertTrue("继续编辑文案不对", "const val TEXT_DIALOG_KEEP = \"继续编辑\"" in code)
        assertTrue("放弃并退出文案不对", "const val TEXT_DIALOG_DISCARD = \"放弃并退出\"" in code)

        // ✕ 与返回键必须共用一个入口（两处各自 finish 就是两套行为）
        assertTrue(
            "✕ 必须走 confirmExit",
            "confirmExit()" in TestSources.blockAfter(code, "R.id.btn_voice_close"),
        )
        // 返回键不覆写 onBackPressed（`ComponentActivity.onBackPressed` 带 @CallSuper，
        // 覆写不调 super 必报 lint MissingSuperCall），改 OnBackPressedDispatcher 回调；
        // 出口必须仍是同一个 confirmExit，也不能绕过确认直接 finish
        val back = TestSources.blockAfter(code, "onBackPressedDispatcher.addCallback(this)")
        assertTrue("返回键必须走同一个 confirmExit", "confirmExit()" in back)
        assertFalse("返回键不许绕过确认直接 finish", "finish()" in back)
        assertFalse("onBackPressed 覆写必须删掉（否则 lint 仍红）", "override fun onBackPressed" in code)
        assertFalse("✕ 不许绕过确认直接 finish", "setOnClickListener { finish() }" in code)
    }

    @Test
    fun `识别后端切换只显示各自的配置卡`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_voice_settings.xml",
            "app/src/main/res/layout/activity_voice_settings.xml",
        )
        // 凭据卡默认隐藏（出厂后端是自建服务端）；三件里两件必须是密码型输入
        assertTrue(
            "card_xf_credentials 必须默认 GONE（默认后端是 caps）",
            Regex("""android:id="@\+id/card_xf_credentials"\s+android:visibility="gone""").containsMatchIn(layout),
        )
        assertTrue(
            "card_ali_credentials 必须默认 GONE（默认后端是 caps）",
            Regex("""android:id="@\+id/card_ali_credentials"\s+android:visibility="gone""").containsMatchIn(layout),
        )
        assertEquals(
            "APIKey / APISecret / OpenAI Key / 阿里百炼 Key / 腾讯 SecretKey / Azure 订阅密钥 /" +
                "百度 SecretKey / Deepgram Key 必须是 password 型输入框" +
                "（APPID、AK、SecretId、URL、模型名、引擎、语言码、dev_pid 不是密码）",
            8, Regex("""android:inputType="textPassword"""").findAll(layout).count(),
        )
        // 明文凭据不许随实例状态过 Binder：旋转 / 分屏 / 后台回收时 `TextView.onSaveInstanceState`
        // 会把框里正文写进 `android:viewHierarchyState` 交给 system_server，`FLAG_SECURE` 挡不住这条。
        // 同仓 `activity_openai_settings` / `activity_translation_settings` 的凭据框早就关掉了它。
        val passwordBlocks = Regex("""<EditText[^>]*android:inputType="textPassword"[^>]*/>""")
            .findAll(layout).toList()
        assertEquals("密码框块数解析失败（布局改动要同步改这段判据）", 8, passwordBlocks.size)
        for (block in passwordBlocks) {
            assertTrue(
                "密码框必须 android:saveEnabled=\"false\"：${block.value.take(90)}",
                "android:saveEnabled=\"false\"" in block.value,
            )
        }

        // 四张卡互斥：按后端切；总开关关掉时全部藏
        val apply = TestSources.blockAfter(code, "private fun applyBackendVisibility")
        assertTrue(
            "caps 选中时必须显示 NAS 地址卡",
            "cardVoiceServer.visibility = if (enabled && backend == VoiceBackend.CAPS) View.VISIBLE else View.GONE" in apply,
        )
        assertTrue(
            "xf 选中时必须显示凭据卡",
            "cardXfCredentials.visibility = if (enabled && backend == VoiceBackend.XF) View.VISIBLE else View.GONE" in apply,
        )
        assertTrue(
            "oai 选中时必须只显示 OpenAI 兼容卡（另三张藏）",
            "cardOaiCredentials.visibility = if (enabled && backend == VoiceBackend.OAI) View.VISIBLE else View.GONE" in
                apply,
        )
        assertTrue(
            "ali 选中时必须只显示阿里百炼卡（另三张藏）",
            "cardAliCredentials.visibility = if (enabled && backend == VoiceBackend.ALI) View.VISIBLE else View.GONE" in
                apply,
        )
        assertEquals(
            "八张配置卡必须逐张按后端切（少一张就会出现两张同显或全藏）",
            8, Regex("""\.visibility = if \(enabled && backend == VoiceBackend\.\w+\)""").findAll(apply).count(),
        )
        val blocks = TestSources.blockAfter(code, "private fun applyVoiceBlocksVisibility")
        assertTrue("后端选择卡也要随总开关隐藏（关掉语音就没有可配的后端）", "cardVoiceBackend.visibility = visibility" in blocks)
        assertTrue("总开关没联动重算后端卡", "applyBackendVisibility()" in blocks)

        // 取值 / 回填 / 写盘都必须接上真实键，不能只是摆设
        val read = TestSources.window(code, "private fun readBackend", "\n\n")
        assertTrue(
            "后端取值必须按 BACKEND_VALUES 下标取（下标异常才回落配置）",
            "BACKEND_VALUES.getOrElse(spinnerVoiceBackend.selectedItemPosition)" in read,
        )
        assertTrue("后端没从 Prefs 回填", "BACKEND_VALUES.indexOf(prefs.voiceBackend)" in code)
        val save = TestSources.blockAfter(code, "private fun saveAndRestart")
        assertTrue("后端没写回 Prefs", "prefs.voiceBackend = backend" in save)
        // 选中即切显隐并重算脏状态（否则换了后端状态行还说没改）
        val listener = TestSources.blockAfter(code, "spinnerVoiceBackend.onItemSelectedListener")
        assertTrue("切后端必须切卡片显隐", "applyBackendVisibility()" in listener)
        assertTrue("切后端必须重算脏状态", "refreshDirty()" in listener)

        // 凭据可切换显示（三个输入框里的两个密码型）：只切 `transformationMethod`，
        // 不动 `inputType` —— 后者会重建按键监听与滤镜，长串凭据可能被重新过滤一次
        val visible = TestSources.blockAfter(code, "private fun applyCredentialVisibility")
        assertTrue("显示 / 隐藏必须落到两个密码框", "field.transformationMethod = method" in visible)
        assertTrue("切到明文后光标必须落到末尾", "field.setSelection(field.text.length)" in visible)
        assertTrue("勾选框文案必须走 TEXT_ 常量", "checkXfShow.text = TEXT_XF_SHOW" in code)
        assertTrue(
            "显示 / 隐藏只是显示态，不许标脏（它不写盘）",
            "refreshDirty" !in TestSources.blockAfter(code, "checkXfShow.setOnCheckedChangeListener"),
        )
    }

    @Test
    fun `讯飞三件凭据缺一不可且只在该后端下写盘`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        val save = TestSources.blockAfter(code, "private fun saveAndRestart")
        // 三件逐个校验，缺哪个标在哪个框上（edit_*.error），不重启、不写盘
        for (field in listOf("editXfAppId", "editXfApiKey", "editXfApiSecret")) {
            assertTrue("$field 缺少必填校验（error 提示）", "$field.error = TEXT_XF_REQUIRED" in save)
        }
        // 校验过的值必须真的落盘（走 setter = 走 CredentialCrypto 加密）。
        // 取值来自 [credentialText]：掩码形态折回磁盘原值 —— 直接把输入框文本写回会把
        // `b86••••••••` 存成凭据（用户第 4 条要求的硬性错误防线）
        assertTrue("APPID 没写回", "prefs.xfAppId = appIdValue" in save)
        assertTrue("APIKey 没写回", "prefs.xfApiKey = apiKeyValue" in save)
        assertTrue("APISecret 没写回", "prefs.xfApiSecret = apiSecretValue" in save)
        // caps 下三件输入框是隐藏的：写回隐藏字段会覆盖用户先前填好的凭据
        assertTrue("凭据写盘必须只发生在 xf 分支", "if (backend == VoiceBackend.XF) {" in save)

        // 秘密不进日志：只允许长度
        assertTrue("保存日志必须只记凭据长度", "xfApiKeyLen=" in save)
        assertFalse(
            "日志里出现了凭据原文（只许记长度）",
            Regex("""\$\{prefs\.xf(?:ApiKey|ApiSecret|AppId)\}""").containsMatchIn(code),
        )
    }

    @Test
    fun `总开关标题与语音测试卡必须按用户要求接线`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        val layout = TestSources.rawSource(
            "src/main/res/layout/activity_voice_settings.xml",
            "app/src/main/res/layout/activity_voice_settings.xml",
        )

        // 用户第 1 条：标题逐字（ASCII 双引号包住「语音输入转文字」，逗号在引号外）。
        // 用 raw 串逐字比对声明那一行 —— 拼串或换引号都会当场变红
        val decl = TestSources.lineOf(code, "const val TEXT_VOICE_MASTER_TITLE")
        val expected = "const val TEXT_VOICE_MASTER_TITLE = \"启用\\\"语音输入转文字\\\",并连接对应服务器\""
        assertEquals(expected, decl.trim())
        assertTrue(
            "标题没有下发到总开关那行 TextView",
            "findViewById<TextView>(R.id.text_voice_master_title).text = TEXT_VOICE_MASTER_TITLE" in code,
        )
        assertTrue("布局缺少 @+id/text_voice_master_title", "@+id/text_voice_master_title" in layout)
        // 文案只能有一处定义：布局里不许再写死（否则常量与界面会各说各话）
        assertFalse(
            "布局仍在用 @string/settings_voice_input（文案应由 TEXT_ 常量下发）",
            "@string/settings_voice_input\"" in layout,
        )

        // 用户第 2、3 条：输入框 + 语音测试按钮 + 结论行，三件都在同一张卡里
        for (id in listOf("edit_voice_test", "btn_voice_test", "text_voice_test_result")) {
            assertTrue("语音测试卡缺少 @+id/$id", "@+id/$id" in layout)
        }
        assertTrue("测试按钮文案必须走 TEXT_ 常量", "btnVoiceTest.text = TEXT_TEST_BUTTON" in code)
        assertTrue("输入框 hint 必须走 TEXT_ 常量", "editVoiceTest.hint = TEXT_TEST_HINT" in code)
        assertTrue("点下后结论行必须显示「测试中…」", "textVoiceTestResult.text = TEXT_TEST_RUNNING" in code)
        assertTrue("测试期间按钮必须禁用", "btnVoiceTest.isEnabled = false" in code)
        assertTrue("测试结束必须恢复按钮", "btnVoiceTest.isEnabled = true" in code)
        // 失败结论不带正文：只覆盖非空结果，别把用户自己打进框里的样例句清掉
        assertTrue(
            "识别正文必须落进输入框（且只覆盖非空结果）",
            "if (result.text.isNotEmpty()) editVoiceTest.setText(result.text)" in code,
        )
        // 缺权限时走本页已有的授权入口，结论如实说未授权（不偷偷跑一次必然失败的采集）
        val start = TestSources.blockAfter(code, "private fun startVoiceTest")
        assertTrue("缺麦克风权限必须申请", "requestMic()" in start)
        assertTrue("结论必须写明未授权", "TEXT_TEST_NEED_PERMISSION" in start)
        // 后端不可用时不发请求：判断在 VoiceSelfTest.start 的第一步
        assertTrue("语音测试必须走自包含实现", "VoiceSelfTest(prefs)" in code)
        assertTrue(
            "onDestroy 必须收掉语音测试（否则漏麦克风采集线程 / 连接）",
            "voiceSelfTest?.cancel()" in TestSources.blockAfter(code, "override fun onDestroy"),
        )
    }

    @Test
    fun `凭据掩码不被写回且聚焦才揭示真值`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        // 回填：已配置的只显示前 3 位 + 掩码（用户第 4 条：别让用户以为忘了填）
        val load = TestSources.blockAfter(code, "private fun loadPrefs")
        assertTrue("凭据回填没走掩码", "setCredentialText(editXfAppId, prefs.xfAppId)" in load)
        assertTrue("凭据回填没走掩码", "setCredentialText(editXfApiKey, prefs.xfApiKey)" in load)
        assertTrue("凭据回填没走掩码", "setCredentialText(editXfApiSecret, prefs.xfApiSecret)" in load)
        val setText = TestSources.blockAfter(code, "private fun setCredentialText")
        assertTrue("回填必须折成掩码形态", "CredentialMask.of(value)" in setText)

        // 保存：掩码形态沿用磁盘原值 —— 直接把输入框文本写回会把掩码串存成凭据（硬性错误）
        val save = TestSources.blockAfter(code, "private fun saveAndRestart")
        assertTrue("凭据有效取值没走 credentialText", "val appIdValue = credentialText(editXfAppId)" in save)
        for (bad in listOf(
            "prefs.xfAppId = editXfAppId.text.toString()",
            "prefs.xfApiKey = editXfApiKey.text.toString()",
            "prefs.xfApiSecret = editXfApiSecret.text.toString()",
        )) {
            assertFalse("保存直接写回了输入框文本（掩码会被写坏）：$bad", bad in save)
        }
        val text = TestSources.blockAfter(code, "private fun credentialText")
        assertTrue("掩码形态必须折回磁盘原值", "diskCredentialOf(field)" in text)
        assertTrue("掩码判据必须覆盖「文本本身就是掩码串」", "isMaskedDisplay(field)" in text)

        // 聚焦揭示真值、失焦回到掩码
        val wire = TestSources.blockAfter(code, "private fun wireCredentialMask")
        assertTrue("聚焦后必须显示真值", "field.setText(disk())" in wire)
        assertTrue("失焦且没改动必须回到掩码", "field.setText(CredentialMask.of(real))" in wire)
        // ⚠ 只钉上面两行是自证式的：`setText` 之后 applyCredentialVisibility 仍会给两个密码框
        // 装回 PasswordTransformationMethod（此时 isMaskedDisplay 已为 false），用户看到的是一整排点，
        // 而且点数 = 真实长度 —— 掩码「固定 8 点不泄露长度」被一起破坏。
        // 2026-11-01 真机实测：16 位 Key 聚焦后显示 19 个点。判据必须落在**分支条件**上。
        val vis = TestSources.blockAfter(code, "private fun applyCredentialVisibility")
        assertTrue(
            "聚焦中的凭据框必须当「已揭示」处理（否则「聚焦显示真值」等于没做）",
            "val revealed = show || field.hasFocus() || isMaskedDisplay(field)" in vis,
        )
        assertTrue("掩码形态仍不许加密码转换", "isMaskedDisplay(field)" in vis)
        // 反向：不许把 hasFocus 从条件里拿掉再靠别处补一句（那正是原形态）
        assertFalse(
            "密码转换的判据不许只剩 show || isMaskedDisplay",
            "if (show || isMaskedDisplay(field)) null" in vis,
        )
        // 取消「显示」时不许把用户改过的内容覆盖成掩码（那等于静默丢掉这次编辑）
        assertTrue(
            "取消勾选只许折回「原样未改」的框",
            "!CredentialMask.looksMasked(text)" in TestSources.blockAfter(code, "private fun maskCredentials"),
        )
        // 脏状态按有效值算：只点一下输入框（掩码 → 真值）不该判脏
        val inputs = TestSources.window(code, "private fun currentInputs", "private fun recomputeDirty")
        assertTrue("脏状态必须按有效取值比对", "xfApiKey = credentialText(editXfApiKey)" in inputs)
    }

    /**
     * 「显示」勾选只对当前那张卡生效。
     *
     * [VoiceSettingsActivity.revealAll] 是「任一勾选 = 全部揭示」的合成判据（八张卡的凭据框共用
     * 一套掩码 / 密码转换），`revealCredentials()` 又会把**所有**卡的框都填成真值。
     * 于是「在讯飞卡勾了显示 → 切到 Deepgram 卡」时，Deepgram 的 Key 会以明文摆在那里，
     * 而它那张卡的勾选框明明没勾 —— 掩码在这一步等于白做。切卡时必须复位别的卡的勾选。
     */
    @Test
    fun `显示勾选不得跨卡泄漏另一家的凭据`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        val apply = TestSources.blockAfter(code, "private fun applyBackendVisibility")
        assertTrue(
            "切卡没有复位别家的「显示」勾选（A 家勾了显示 ⇒ B 家的密钥明文可见）",
            "if (id != backend && box.isChecked) box.setChecked(false)" in apply,
        )
        assertTrue("复位用的判据表没接上（revealChecks）", "revealChecks()" in apply)
        // 判据表必须覆盖七张凭据卡（caps 没有凭据，也就没有勾选框）。
        // ⚠ 这里不能用 blockAfter：revealChecks 是表达式体、函数体里没有花括号，
        // 花括号配对会一路找到后面的函数里去（判据静默失真）。
        val checks = TestSources.window(code, "private fun revealChecks", "private fun setCredentialText")
        for (box in listOf(
            "checkXfShow", "checkOaiShow", "checkAliShow", "checkTencentShow",
            "checkAzureShow", "checkBaiduShow", "checkDeepgramShow",
        )) {
            assertTrue("revealChecks 漏了 $box", box in checks)
        }
        // 复位必须早于「把目标卡显示出来」：反过来会闪一下明文再被退回掩码
        assertTrue(
            "复位必须排在卡片显隐之前",
            apply.indexOf("box.setChecked(false)") in 0 until
                apply.indexOf("cardVoiceServer.visibility ="),
        )
    }

    @Test
    fun `凭据解不开时页面必须如实说清`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        // 磁盘上有密文、本机解不开（软件密钥件丢失 / 密文损坏）时，不能再给三个空框了事 ——
        // 用户与自己「明明填过」的记忆冲突，最坏会以为凭据被偷。
        assertTrue("页面没读 hasUnreadableXfCredential（解不开时一句话都不说）", "prefs.hasUnreadableXfCredential" in code)
        val onCreate = TestSources.blockAfter(code, "override fun onCreate")
        assertTrue("提示没落到凭据卡说明行（只声明常量等于没接线）", "TEXT_XF_DESC + TEXT_XF_UNREADABLE" in onCreate)
        assertTrue("正常态不许挂警告（必须是条件分支）", "if (prefs.hasUnreadableXfCredential)" in onCreate)
        assertTrue("提示必须说清成因与出路", "已保存的凭据在本机解不开" in code && "需要重新填写" in code)

        // 判据本身：只认「永久解不开」。暂时解不开（锁屏 / IO 抖动）报出来就是误报
        val prop = TestSources.blockAfter(
            TestSources.codeSource("Prefs.kt"),
            "internal val hasUnreadableXfCredential",
        )
        assertTrue(
            "暂时解不开的重试闸内必须不下结论（否则把锁屏误报成凭据坏了）",
            "System.currentTimeMillis() < credentialRetryAt" in prop,
        )
        assertTrue("判据是「磁盘上有值 + 读出来是空」", "readCredential(key).isEmpty()" in prop)
        for (k in listOf("KEY_XF_APP_ID", "KEY_XF_API_KEY", "KEY_XF_API_SECRET")) {
            assertTrue("$k 没进判据（三件缺一，用户会漏看半页）", k in prop)
        }
        // OpenAI 兼容那张卡同一句话：Key 解不开时不能只给一个空框（用户会以为从没填过）
        val oaiProp = TestSources.blockAfter(
            TestSources.codeSource("Prefs.kt"),
            "internal val hasUnreadableOaiCredential",
        )
        assertTrue("OpenAI Key 没进「解不开」判据", "KEY_OAI_API_KEY" in oaiProp)
        assertTrue(
            "OpenAI 侧也要有重试闸（否则把锁屏误报成凭据坏了）",
            "System.currentTimeMillis() < credentialRetryAt" in oaiProp,
        )
        assertTrue("oai 卡说明行没接 hasUnreadableOaiCredential", "if (prefs.hasUnreadableOaiCredential)" in onCreate)
    }

    @Test
    fun `凭据页必须防截屏`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        assertTrue(
            "本页含 APPID / APIKey / APISecret 明文输入，必须 FLAG_SECURE（防截屏 / 录屏 / 最近任务）",
            "WindowManager.LayoutParams.FLAG_SECURE" in code,
        )
        // 必须在 setContentView 之前加上：晚一步就可能先渲染出一帧明文
        val onCreate = TestSources.blockAfter(code, "override fun onCreate")
        val flag = onCreate.indexOf("FLAG_SECURE")
        val content = onCreate.indexOf("setContentView(")
        assertTrue("FLAG_SECURE 必须在 setContentView 之前设置", flag in 0 until content)
    }

    @Test
    fun `保存按钮防连点且落盘失败可重试`() {
        val code = TestSources.codeSource("VoiceSettingsActivity.kt")
        val save = TestSources.blockAfter(code, "private fun saveAndRestart")
        val guard = save.indexOf("if (!btnSave.isEnabled) return")
        val disable = save.indexOf("btnSave.isEnabled = false")
        val restart = save.indexOf("ImeRestart.restart(")
        assertTrue("必须先判按钮是否已禁用（第二次点击直接返回）", guard >= 0)
        assertTrue("禁用必须早于任何写入（否则第二次点击已重写一遍配置）", disable > guard)
        assertTrue("禁用必须早于 ImeRestart.restart 调用", restart > disable)
        // 判据限定在 restart 的回调里：整个 saveAndRestart 里另有三处校验失败的恢复，
        // 拿全块判「落盘失败能重试」会被那三处满足（删掉回调里的恢复照样绿）
        assertTrue(
            "落盘失败必须恢复按钮（进程没被杀）",
            "btnSave.isEnabled = true" in TestSources.blockAfter(code, "ImeRestart.restart(this)"),
        )
        assertTrue(
            "校验失败也要恢复按钮（否则用户卡在禁用态）",
            Regex(
                """edit(?:Host|Port)\.error = getString\([^)]*\)\s*\n\s*btnSave\.isEnabled = true"""
            ).containsMatchIn(save),
        )
        // 凭据只进内存时不许重启（A-726）：killProcess 不走 onDestroy，内存里那份随进程丢，
        // 而磁盘旧密文已被 writeCredential 的 fail-closed 分支删掉 ⇒ 重启后变「未配置」。
        val unpersisted = save.indexOf("unpersistedCredentialKeys()")
        assertTrue("必须查凭据是否真的落盘", unpersisted >= 0)
        assertTrue("查落盘必须早于 ImeRestart.restart", unpersisted < restart)
        assertTrue(
            "未落盘时必须把失败文案写回状态行（与落盘失败同一句）",
            "ImeRestart.TEXT_RESTART_FLUSH_FAILED" in save,
        )
    }
}
