package com.jinn.inputmethod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * OpenAI 兼容的**完整配置页**：基础配置 / 翻译设置 / 模型参数 / 高级，四段纵排。
 *
 * 设计原则（接入文档）：固定的只有协议骨架，其余全部开放 ——
 *  · 可选参数「留空即不发送」：某些推理模型不接受 `temperature` / `top_p`；
 *  · Prompt 完全可改，支持 `{{text}}` / `{{target_language}}` / `{{source_language}}` / `{{date}}`；
 *  · 自定义 Headers 与 Custom JSON（后者同名覆盖标准参数），换厂商不必等 App 更新；
 *  · 端点路径与响应解析路径都可配（`/chat/completions`、`output_text`…）。
 *
 * 「获取模型 / 测试连接」走 `GET {Base}{ModelsPath}`：拿不到时不判死 —— 该接口不是
 * OpenAI 兼容的必选项，用户照样可以手填 Model。最近一次成功结果写入缓存供下次直接选。
 */
class OpenAiSettingsActivity : Activity() {

    private lateinit var editName: EditText
    private lateinit var editBaseUrl: EditText
    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editChatPath: EditText
    private lateinit var editModelsPath: EditText
    private lateinit var editTargetCustom: EditText
    private lateinit var editSystem: EditText
    private lateinit var editUser: EditText
    private lateinit var editTemperature: EditText
    private lateinit var editTopP: EditText
    private lateinit var editMaxTokens: EditText
    private lateinit var editResponsePath: EditText
    private lateinit var editTimeout: EditText
    private lateinit var editHeaders: EditText
    private lateinit var editExtraJson: EditText
    private lateinit var spinnerTarget: Spinner
    private lateinit var textHint: TextView

    /** 显式保存入口与结果提示（用户 2026-10-01 要求：参数多，改完要有看得见的「保存」与结果） */
    private lateinit var btnSave: Button
    private lateinit var textSaveHint: TextView

    /** 用户触摸过才允许写配置：初始化 setSelection 也会回调 onItemSelected（与设置页各下拉同款闸门） */
    private var targetTouched = false

    /** 在途的「获取模型 / 测试连接」请求；[onDestroy] 时取消（超时上限可到 300s，不取消会拖住 Activity） */
    private var fetchCall: okhttp3.Call? = null

    /** 请求代际：连续点两次时，先发的旧响应后到也不能覆盖新结果（2026-09-30 第二轮审查） */
    private var fetchGeneration = 0

    private val prefs: Prefs by lazy { Prefs(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    /**
     * 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 对一次表并排下一次，
     * [onStop] 撤掉 —— 页面在后台跨过切换点，回来时也能补上。
     */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 凭据页防**截屏 / 录屏 / 投屏 / 最近任务缩略图**（2026-09-30 用户要求增强防泄露），
        // 与 TranslationSettingsActivity 同款（那里有完整的取舍说明）：本页有 API Key 输入框。
        // ⚠ 投屏（ADB / scrcpy）下整页不可见是**预期行为**，不是缺陷。
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContentView(R.layout.activity_openai_settings)

        findViewById<TextView>(R.id.text_ai_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_ai_desc).text = TEXT_DESC
        findViewById<TextView>(R.id.label_ai_section_basic).text = SECTION_BASIC
        findViewById<TextView>(R.id.label_ai_section_prompt).text = SECTION_PROMPT
        findViewById<TextView>(R.id.label_ai_section_params).text = SECTION_PARAMS
        findViewById<TextView>(R.id.label_ai_section_advanced).text = SECTION_ADVANCED
        findViewById<TextView>(R.id.label_ai_name).text = TEXT_NAME
        findViewById<TextView>(R.id.label_ai_base_url).text = TEXT_BASE_URL
        findViewById<TextView>(R.id.label_ai_api_key).text = TEXT_API_KEY
        findViewById<TextView>(R.id.label_ai_model).text = TEXT_MODEL
        findViewById<TextView>(R.id.label_ai_target).text = TEXT_TARGET
        findViewById<TextView>(R.id.label_ai_target_custom).text = TEXT_TARGET_CUSTOM
        findViewById<TextView>(R.id.label_ai_system).text = TEXT_SYSTEM
        findViewById<TextView>(R.id.label_ai_user).text = TEXT_USER
        findViewById<TextView>(R.id.label_ai_temperature).text = TEXT_TEMPERATURE
        findViewById<TextView>(R.id.label_ai_top_p).text = TEXT_TOP_P
        findViewById<TextView>(R.id.label_ai_max_tokens).text = TEXT_MAX_TOKENS
        findViewById<TextView>(R.id.label_ai_chat_path).text = TEXT_CHAT_PATH
        findViewById<TextView>(R.id.label_ai_models_path).text = TEXT_MODELS_PATH
        findViewById<TextView>(R.id.label_ai_response_path).text = TEXT_RESPONSE_PATH
        findViewById<TextView>(R.id.label_ai_timeout).text = TEXT_TIMEOUT
        findViewById<TextView>(R.id.label_ai_headers).text = TEXT_HEADERS
        findViewById<TextView>(R.id.label_ai_extra_json).text = TEXT_EXTRA_JSON
        textHint = findViewById(R.id.text_ai_hint)
        findViewById<TextView>(R.id.text_ai_footer).text = TEXT_FOOTER
        findViewById<Button>(R.id.btn_ai_close).apply {
            // 关闭键：键面字形与可听名统一来自 PageChrome（原先各页自写 `X` / `关闭`，见 BUG.md L-477）
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }

        editName = findViewById(R.id.edit_ai_name)
        editBaseUrl = findViewById(R.id.edit_ai_base_url)
        editApiKey = findViewById(R.id.edit_ai_api_key)
        editModel = findViewById(R.id.edit_ai_model)
        editChatPath = findViewById(R.id.edit_ai_chat_path)
        editModelsPath = findViewById(R.id.edit_ai_models_path)
        editTargetCustom = findViewById(R.id.edit_ai_target_custom)
        // 用户**亲手改过**自定义语言 ⇒ 它优先于下拉（2026-10-02 修复 L-367）：
        // `loading` 抑制程序化回填（loadValues 的 setText），只有真键入才算数
        editTargetCustom.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!loading) customEdited = true
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        editSystem = findViewById(R.id.edit_ai_system)
        editUser = findViewById(R.id.edit_ai_user)
        editTemperature = findViewById(R.id.edit_ai_temperature)
        editTopP = findViewById(R.id.edit_ai_top_p)
        editMaxTokens = findViewById(R.id.edit_ai_max_tokens)
        editResponsePath = findViewById(R.id.edit_ai_response_path)
        editTimeout = findViewById(R.id.edit_ai_timeout)
        editHeaders = findViewById(R.id.edit_ai_headers)
        editExtraJson = findViewById(R.id.edit_ai_extra_json)
        spinnerTarget = findViewById(R.id.spinner_ai_target)
        // 结果提示复位（2026-10-02 修复 L-371）：保存成功后提示一直停在「已保存到本机」，
        // 用户继续改字段时它与屏幕上的未保存改动矛盾。挂「获得焦点即回 idle」，零侵入
        // （不用 TextWatcher：`loadValues()` 的回填 setText 会把刚显示的保存结果立刻抹掉）。
        for (f in listOf(
            editName, editBaseUrl, editApiKey, editModel, editChatPath, editModelsPath,
            editTargetCustom, editSystem, editUser, editTemperature, editTopP, editMaxTokens,
            editResponsePath, editTimeout, editHeaders, editExtraJson,
        )) {
            f.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !loading) textSaveHint.text = TEXT_SAVE_IDLE
            }
        }
        // 显式保存（2026-10-01 用户要求）：固定在滚动区之外，任何位置都能点，点完给明确结果提示
        btnSave = findViewById(R.id.btn_ai_save)
        btnSave.text = TEXT_SAVE
        btnSave.setOnClickListener { saveAndNotify() }
        textSaveHint = findViewById(R.id.text_ai_save_hint)
        textSaveHint.text = TEXT_SAVE_IDLE

        editName.hint = TEXT_NAME_HINT
        editBaseUrl.hint = TEXT_BASE_URL_HINT
        editApiKey.hint = TEXT_API_KEY_HINT
        editModel.hint = TEXT_MODEL_HINT
        editChatPath.hint = TEXT_CHAT_PATH_HINT
        editModelsPath.hint = TEXT_MODELS_PATH_HINT
        editTargetCustom.hint = TEXT_TARGET_CUSTOM_HINT
        editTemperature.hint = TEXT_TEMPERATURE_HINT
        editTopP.hint = TEXT_TOP_P_HINT
        editMaxTokens.hint = TEXT_MAX_TOKENS_HINT
        editResponsePath.hint = TEXT_RESPONSE_PATH_HINT
        editTimeout.hint = TEXT_TIMEOUT_HINT
        editHeaders.hint = TEXT_HEADERS_HINT
        editExtraJson.hint = TEXT_EXTRA_JSON_HINT
        findViewById<Button>(R.id.btn_ai_fetch_models).text = TEXT_FETCH_MODELS
        findViewById<Button>(R.id.btn_ai_prompt_reset).text = TEXT_PROMPT_RESET
        findViewById<Button>(R.id.btn_ai_test).text = TEXT_TEST

        initTargetSpinner()
        findViewById<Button>(R.id.btn_ai_fetch_models).setOnClickListener { fetchModels(showPicker = true) }
        findViewById<Button>(R.id.btn_ai_test).setOnClickListener { fetchModels(showPicker = false) }
        findViewById<Button>(R.id.btn_ai_prompt_reset).setOnClickListener {
            editSystem.setText(OpenAiTranslator.DEFAULT_SYSTEM_PROMPT)
            editUser.setText(OpenAiTranslator.DEFAULT_USER_PROMPT)
            toast(TEXT_PROMPT_RESET_DONE)
        }
    }

    /**
     * 离开页面即落盘，并显式等一次写入 —— 与翻译设置页同款两处坑：
     * EditText 失焦在返回时不一定派发回调；且必须写在 `onPause`（下一个页面的 onResume 早于它）。
     */
    override fun onPause() {
        super.onPause()
        // 导入进行中一律不回写（2026-10-02 修复 L-405）：本页的回写是**无条件**的全字段
        // （15 项直接 setText → saveValues），与导入线程写同一份 Prefs ⇒ 交错时界面旧值
        // 会把刚导入的 model / baseUrl / 提示词覆盖回去。导入结束会 `recreate()` 设置页，
        // 界面值本就要重置，跳过不丢用户输入。
        if (ConfigBackupManager.importing) {
            Diagnostics.w(TAG, "onPause: 导入进行中，跳过配置回写（避免覆盖导入结果）")
        } else {
            saveValues()
        }
        // flush 的布尔值就是「有没有真落盘」，不许丢（2026-10-02 修复 L-407）
        if (!prefs.flush()) Diagnostics.w(TAG, "onPause: 配置落盘失败（改动可能回退）")
        // 「凭据不留痕」（2026-09-30）：用户从云控制台复制的 API Key 已被剪贴板监听采集入库
        // （面板里明文可见、随备份导出），保存后把它从历史里删掉（精确匹配，收藏条目不动）
        purgeApiKeyFromClipboardHistory()
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
        loadValues()
        purgeApiKeyFromClipboardHistory()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /** 从剪贴板历史删除与本机 API Key 相同的条目（后台线程；见 [purgeApiKeyFromClipboardHistory] 的说明） */
    private fun purgeApiKeyFromClipboardHistory() {
        val appContext = applicationContext
        BackgroundIo.run {
            val db = runCatching { ClipboardDb.get(appContext) }.getOrElse {
                // 库打不开必须留痕（2026-10-02 修复 L-410）：此前静默 return —— 从网页控制台
                // 复制的 Key 明文可能一直留在历史里（面板可见、随备份导出），而诊断包里
                // 连「试过清理」都看不到
                Diagnostics.w(TAG, "凭据不留痕: 库打不开，本轮未清理（${it.javaClass.simpleName}）")
                return@run
            }
            // 与翻译设置页共用同一条链路（精确哈希 → 剥不可见字符后相等，一次遍历处理全部目标）：
            // 本页此前只有精确哈希，带 NBSP / ZWSP 的 Key 一条都删不掉（2026-10-01 修复 L-245）
            // 目标除 API Key 外，还包含自定义请求头与自定义 JSON 里的值（2026-10-01 修复 L-252）
            val targets = listOf(prefs.openAiApiKey) +
                CredentialTrace.candidatesFrom(prefs.openAiExtraHeaders, prefs.openAiExtraJson)
            val removed = CredentialTrace.purge(db, targets)
            if (removed > 0) {
                Diagnostics.i(TAG, "凭据不留痕: 从剪贴板历史删除 $removed 条")
            } else {
                Diagnostics.v(TAG, "凭据不留痕: 本轮无需删除（${targets.count { it.isNotBlank() }} 个目标）")
            }
        }
    }

    /**
     * 取消在途的「获取模型 / 测试连接」（2026-09-30 第二轮审查）。
     *
     * 该请求的超时上限可到 300s：用户点完就返回时，不取消会让回调闭包**持有本 Activity 最多 5 分钟**
     * 不释放；而且第二次请求的旧响应后到时会覆盖用户刚做的选择（提示被改、甚至弹出第二个选模型对话框）。
     * 代际（[fetchGeneration]）是第二道闸门：取消未必来得及（响应可能已在路上）。
     */
    override fun onDestroy() {
        super.onDestroy()
        fetchGeneration++
        fetchCall?.cancel()
        fetchCall = null
    }

    private fun initTargetSpinner() {
        spinnerTarget.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            TARGET_LANGUAGES,
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerTarget.setOnTouchListener { view, event ->
            targetTouched = true
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 程序化回填期间一律不放行（2026-10-02 修复）：`loadValues` 里的 `setSelection`
                // 也会走到这里，此时 Spinner 往往正好有焦点 ⇒ 仅凭 `isUserDriven` 会误判成用户操作。
                if (loading) return
                // 闸门要认全「用户来源」：触摸只是其中一种（2026-10-02 修复）—— 读屏 / 外接键盘选
                // 下拉不产生触摸事件，只认 `targetTouched` 的话它们选的语言**清不掉残留的自定义值**，
                // 而 `saveValues` 正是按「自定义框是否还有内容」判定生效值 ⇒ 选了不生效。
                if (!targetTouched && !isUserDriven(spinnerTarget)) return
                // 选了标准语言就清掉自定义框（两者只应有一个生效）。`customEdited` 必须一并复位：
                // 它现在也会被「载入自定义值」置真（见 `applyTargetLanguage`），不复位的话读屏用户
                // 选完下拉保存下来仍是旧的自定义语言。
                customEdited = false
                editTargetCustom.setText("")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 只回写用户真的改过的字段（2026-10-01 修复 L-247）：见 [CredentialSaveGuard] */
    private val saveGuard = CredentialSaveGuard()

    /** 程序化回填期间抑制下拉 / 自定义框的回调（2026-10-02 修复 L-367 的必要配套） */
    private var loading = false

    /**
     * 用户是否**亲手编辑过**「自定义语言」框（2026-10-02 修复 L-367 的判据底座）。
     *
     * 目标语言的两个控件（下拉 + 自定义框）此前按「自定义框非空优先」保存 —— 读屏 / 外接键盘
     * 用户选了下拉也不会清掉残留的自定义值 ⇒ **选了不生效**（用户看着下拉变了，保存后仍是旧值）。
     * 改成按「最后动过哪个控件」判定。
     */
    private var customEdited = false

    /** 「这次变化来自用户」的判据：触摸 / 焦点 / 无障碍焦点（键盘与读屏都不产生触摸事件） */
    private fun isUserDriven(view: View): Boolean =
        view.isFocused || view.isPressed || view.isAccessibilityFocused

    /** 灌值并记下「载入时的原值」（与 [saveGuard] 配套） */
    private fun loadField(field: android.widget.EditText, value: String) {
        field.setText(value)
        saveGuard.remember(field, value)
    }

    private fun loadValues() {
        targetTouched = false
        saveGuard.reset()
        // 程序化回填整段抑制（2026-10-02 修复 L-367 的配套）：下面的 setText 会触发自定义框的
        // TextWatcher，不抑制就会把「载入」当成「用户编辑」
        loading = true
        customEdited = false
        editName.setText(prefs.openAiName)
        editBaseUrl.setText(prefs.openAiBaseUrl)
        loadField(editApiKey, prefs.openAiApiKey)
        editModel.setText(prefs.openAiModel)
        editChatPath.setText(prefs.openAiChatPath)
        editModelsPath.setText(prefs.openAiModelsPath)
        editSystem.setText(prefs.openAiSystemPrompt)
        editUser.setText(prefs.openAiUserPrompt)
        editTemperature.setText(prefs.openAiTemperature)
        editTopP.setText(prefs.openAiTopP)
        editMaxTokens.setText(prefs.openAiMaxTokens)
        editResponsePath.setText(prefs.openAiResponsePath)
        editTimeout.setText(prefs.openAiTimeoutSec.toString())
        editHeaders.setText(prefs.openAiExtraHeaders)
        editExtraJson.setText(prefs.openAiExtraJson)
        applyTargetLanguage(prefs.openAiTargetLanguage)
        // 提示词里没有 {{text}} 时请求不会带原文（运行时已兜底，但用户应当知道）：
        // 用 EditText 的 error 显示，零布局改动（2026-10-01 修复 L-250）
        // ⚠ 判据与运行期同源（2026-10-02 修复 L-338 / L-419）：此前是 `VAR_TEXT !in 原串`，
        // 用户写 `{{ text }}` / `{{　text　}}` 这类容错写法时运行期已正常展开，这里却报
        // 「提示词里没有 {{text}}」——把完全正确的配置标红。
        val promptMissing = !OpenAiTranslator.hasTextVar(prefs.openAiSystemPrompt) &&
            !OpenAiTranslator.hasTextVar(prefs.openAiUserPrompt)
        editUser.error = if (promptMissing) TEXT_PROMPT_MISSING else null
        // 自定义 JSON 写错时参数会被静默丢弃（2026-10-01 修复 L-257）：用 error 让用户看得见
        editExtraJson.error =
            if (OpenAiTranslator.isValidExtraJson(prefs.openAiExtraJson)) null else TEXT_EXTRA_JSON_INVALID
        loading = false
    }

    /** 标准语言落到下拉；不在表内的（粤语 / 古文 / 繁體中文（台灣）…）放进「自定义语言」 */
    private fun applyTargetLanguage(value: String) {
        val index = TARGET_LANGUAGES.indexOf(value)
        if (index >= 0) {
            spinnerTarget.setSelection(index)
            editTargetCustom.setText("")
        } else {
            spinnerTarget.setSelection(0)
            editTargetCustom.setText(value)
            // ⚠ 载入进来的自定义值**就是**当前生效值（2026-10-02 修复）：不置这一位的话，
            // `saveValues` 的判据（`customEdited && custom.isNotEmpty()`）会判「用户没编辑过自定义框」
            // ⇒ 落回下拉第 0 项「简体中文」⇒ **打开页面再返回就把翻译方向改掉**（连点「保存」都救不了：
            // `saveAndNotify` 保存后立刻 `loadValues()` 又把这一位清零）。这里必须显式置真，因为
            // 「回填自定义值」与「用户输入自定义值」在数据面是等价的；`value` 为空时不置（空串走下拉）。
            if (value.isNotEmpty()) customEdited = true
        }
    }

    private fun saveValues() {
        prefs.openAiName = editName.text.toString()
        prefs.openAiBaseUrl = editBaseUrl.text.toString()
        // 只回写真的改过的字段（2026-10-01 修复 L-247）：Key 解密失败时读出来是空串，
        // 无条件回写等于「打开又离开」就把密文删了
        if (saveGuard.changed(editApiKey)) prefs.openAiApiKey = editApiKey.text.toString()
        prefs.openAiModel = editModel.text.toString()
        prefs.openAiChatPath = editChatPath.text.toString()
        prefs.openAiModelsPath = editModelsPath.text.toString()
        prefs.openAiSystemPrompt = editSystem.text.toString()
        prefs.openAiUserPrompt = editUser.text.toString()
        // 数字参数（2026-10-02 修复 L-370）：非空但非法的值此前原样存下、请求里被静默丢弃
        // （`numberOrNull` 返 null ⇒ 该参数根本不发），而保存提示照说「已保存到本机」
        applyNumberField(editTemperature) { prefs.openAiTemperature = it }
        applyNumberField(editTopP) { prefs.openAiTopP = it }
        applyNumberField(editMaxTokens) { prefs.openAiMaxTokens = it }
        prefs.openAiResponsePath = editResponsePath.text.toString()
        // `toIntOrNull()` 不忽略空白（走 `Integer.parseInt` 语义）⇒ 粘贴 `" 30"` 会静默落回默认值，
        // 而同一页的数字三项（`applyNumberField`）与「原文范围」页的字节上限都做了 `trim()`
        // （2026-10-02 修复：同族数值字段口径统一）
        prefs.openAiTimeoutSec = editTimeout.text.toString().trim().toIntOrNull()
            ?: OpenAiTranslator.DEFAULT_TIMEOUT_SEC
        prefs.openAiExtraHeaders = editHeaders.text.toString()
        prefs.openAiExtraJson = editExtraJson.text.toString()
        // 目标语言（2026-10-02 修复 L-367）：按「用户最后动过哪个控件」判定 —— 旧判据是
        // 「自定义框非空优先」，读屏 / 外接键盘用户选了下拉也清不掉残留的自定义值 ⇒
        // 用户看着下拉变了、保存后仍是旧语言。现在只有**亲手编辑过**自定义框才让它优先。
        val custom = editTargetCustom.text.toString().trim()
        prefs.openAiTargetLanguage = if (customEdited && custom.isNotEmpty()) {
            custom
        } else {
            TARGET_LANGUAGES.getOrNull(spinnerTarget.selectedItemPosition)
                ?: OpenAiTranslator.DEFAULT_TARGET_LANGUAGE
        }
        // 日志只记长度（API Key / Prompt / JSON 一律不进日志）
        Diagnostics.i(
            TAG,
            "OpenAI 兼容配置已保存（长度）: name=${prefs.openAiName.length} base=${prefs.openAiBaseUrl.length} " +
                "key=${prefs.openAiApiKey.length} model=${prefs.openAiModel.length} " +
                "prompt=${prefs.openAiSystemPrompt.length}/${prefs.openAiUserPrompt.length} " +
                "extra=${prefs.openAiExtraJson.length} headers=${prefs.openAiExtraHeaders.length}",
        )
    }

    /**
     * 数字字段的保存：留空 = 不发该参数（清错误）；非空但非法 ⇒ 标红并留痕
     * （2026-10-02 修复 L-370：此前非法值原样存下、请求里被静默丢弃，用户以为参数生效了）。
     *
     * 非法值仍照写（"留空即不发"的语义与所见即所得不变），由 [OpenAiTranslator.numberOrNull]
     * 在组装请求体时兜住；这里只负责让用户**在设置页就看见**。
     */
    private fun applyNumberField(field: android.widget.EditText, write: (String) -> Unit) {
        val raw = field.text.toString().trim()
        val invalid = raw.isNotEmpty() && OpenAiTranslator.numberOrNull(raw) == null
        field.error = if (invalid) TEXT_NUMBER_INVALID else null
        if (invalid) Diagnostics.w(TAG, "数字参数非法，该参数不会被发送（字段 id=${field.id}）")
        write(raw)
    }

    /**
     * 显式保存（用户 2026-10-01 要求）：本页参数多且分四段，改完要有明确的「保存」出口与结果提示
     * —— 参数本来就会在离开页面时自动落盘，但「自动」是用户看不见的。
     *
     * 「确保真的保存了」落在三处：
     *  ① [Prefs.flush] 走 `commit()`（同步落盘、返回是否成功），不是异步 `apply()`；
     *  ② API Key 是唯一带「不许误删」闸门的字段，按 [CredentialSaveGuard] 语义**回读核对**
     *     （与写入同一个 `cleanCredential()` 口径）；不一致就不报「已保存」；
     *  ③ 核对后把持久化值**回填界面**（[loadValues]）—— 超时被钳位、空值回默认这类归一
     *     都会立刻显示出来，界面与「本机真实值」不再分叉。
     */
    private fun saveAndNotify() {
        saveValues()
        val flushed = prefs.flush()
        val keyOk = !saveGuard.changed(editApiKey) ||
            prefs.openAiApiKey == editApiKey.text.toString().cleanCredential()
        // 加密不可用（锁屏）时 Key 只在内存、重启即失（2026-10-02 修复 L-355）：
        // 回读核对命中同一个缓存 ⇒ 必然"相等"，必须由写侧记账才能如实报告
        val unpersisted = prefs.unpersistedCredentialKeys()
        loadValues()
        // 校验也计入「保存成功」的口径（2026-10-03 修复 L-514）：`loadValues()` 刚按持久化值重算过
        // 各字段的 error，这里直接读 —— 非法 JSON / 缺 {{text}} 的提示词不能与「已保存到本机」并存。
        // 端点必须能解析成 https，与运行期同源（2026-10-03 修复 L-513）：此前保存、状态摘要、
        // 「已配置」三处全放行 `http://`，直到第一次点翻译才被 INSECURE 拒掉 —— 用户拿到的是
        // 「配置成功」的正反馈。这里与 Prefs.hasCredentialFor 用同一份判据（joinUrl + isHttps）。
        editBaseUrl.error = if (
            OpenAiTranslator.joinUrl(prefs.openAiBaseUrl, prefs.openAiChatPath)?.isHttps == true
        ) {
            null
        } else {
            TEXT_NEED_HTTPS
        }
        val fieldsOk = listOf(editBaseUrl, editUser, editExtraJson).all { it.error == null }
        val ok = flushed && keyOk && unpersisted.isEmpty() && fieldsOk
        // 三态 +1（2026-10-02 修复 L-420 的 OpenAI 页侧）：原来失败只有一句
        // 「保存未完成」，分不清是写盘失败、Key 未落盘、还是核对不一致
        textSaveHint.text = when {
            !flushed -> TEXT_SAVE_DISK_FAILED
            unpersisted.isNotEmpty() -> TEXT_SAVE_NOT_PERSISTED
            !keyOk -> TEXT_SAVE_KEY_FAILED
            !fieldsOk -> TEXT_SAVE_FIELDS_INVALID
            else -> TEXT_SAVED
        }
        if (ok) {
            toast(TEXT_SAVED)
        } else {
            Diagnostics.w(TAG, "显式保存未完成: flushed=$flushed keyOk=$keyOk 未落盘=${unpersisted.size}")
            toast(TEXT_SAVE_FAILED)
        }
    }

    /**
     * 拉取模型列表：先保存当前输入（避免用旧值请求），再 `GET {Base}{ModelsPath}`。
     *
     * 拿到就弹选择框（并写缓存）；失败只提示，**不判死** —— `/models` 不是所有兼容服务的必选项，
     * 用户照样能手填 Model。
     */
    private fun fetchModels(showPicker: Boolean) {
        // 连点保护（2026-10-03 修复 L-634）：原先没有任何请求中判据 —— 连点会 cancel 上一个
        // （**服务端已收到**）并重发，部分网关把它计入限流、返回 429 后本页还会把提示误导成
        // 「网络失败」；同时每次点击都跑一遍 `saveValues()`（整份 prefs 写 + 凭据加解密）。
        // 这里直接早退并保持「请求中…」提示，用户看到的是明确状态而不是"点了没反应"。
        // ⚠ 必须配合回调里的 `fetchCall = null`：否则这个判据会在首次请求后**永久成立**。
        if (fetchCall != null) {
            setHint(TEXT_FETCHING)
            return
        }
        saveValues()
        val url = OpenAiTranslator.modelsUrl(prefs.openAiBaseUrl, prefs.openAiModelsPath)
        if (url == null) {
            setHint(TEXT_URL_INVALID)
            return
        }
        setHint(TEXT_FETCHING)
        // 直接读内存值：saveValues() 刚把编辑框写进 Prefs，请求用的就是这份内存值。
        // （2026-09-30 第二轮审查：此处原先的 flush() 是多余的 —— 它挡不住任何真实故障，却把
        //  「整份 prefs 重写 + fsync」放进主线程点击回调；真正的落盘在 onPause。）
        fetchCall?.cancel()
        fetchGeneration++
        val generation = fetchGeneration
        fetchCall = TranslationClient.fetchModels(url, prefs.openAiApiKey, prefs.openAiTimeoutSec) { models, code ->
            runOnUiThread {
                // 页面可能在响应回来前就被关掉（整体超时上限可到 300s）：往已 finish 的 Activity
                // 上 show() 会抛 WindowManager$BadTokenException 直接崩溃（与 SettingsActivity:810
                // 同款防线，第一轮审查发现）。
                // 请求已回来 ⇒ 清句柄，让「连点保护」重新放开（2026-10-03 修复 L-634）。
                // ⚠ 必须放在**两个早退之后**：早退时说明这次响应已过期或不适用，
                // 句柄属于**更新的那次请求**（与 JinnIme 里代际匹配才清句柄是同一条纪律）。
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 代际闸门：连点两次时，先发的旧响应后到也不能覆盖用户刚看到的新结果
                if (generation != fetchGeneration) return@runOnUiThread
                fetchCall = null
                when {
                    models == null && code == -2 -> setHint(TEXT_NEED_HTTPS)
                    // -3 = 凭据含非法字符（header 设置抛异常，见 fetchModels）：不能混进「网络错误」——
                    // 用户去查网络与地址永远查不出问题（2026-10-01 复审 L-231③）
                    models == null && code == -3 -> setHint(TEXT_KEY_INVALID)
                    models == null && code < 0 -> setHint(TEXT_NETWORK_FAILED)
                    models == null -> setHint(String.format(Locale.US, TEXT_TEST_FAIL_FMT, code))
                    models.isEmpty() -> setHint(TEXT_MODELS_EMPTY)
                    !showPicker -> setHint(String.format(Locale.US, TEXT_TEST_OK_FMT, models.size))
                    else -> {
                        prefs.openAiModelsCache = models.joinToString("\n")
                        setHint(String.format(Locale.US, TEXT_TEST_OK_FMT, models.size))
                        AlertDialog.Builder(this)
                            .setTitle(TEXT_PICK_MODEL)
                            .setItems(models.toTypedArray()) { _, which -> editModel.setText(models[which]) }
                            .setNegativeButton(TEXT_CANCEL, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun setHint(text: String) {
        textHint.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "OpenAiSettings"

        /** 标准目标语言（下拉项）；表外的值走「自定义语言」输入框 */
        val TARGET_LANGUAGES = listOf(
            "简体中文", "繁體中文", "English", "日本語", "한국어", "Français",
            "Deutsch", "Español", "Italiano", "Português", "Русский", "العربية",
        )

        // 文案在代码里下发（strings.xml 默认禁改），与本项目其它设置页同做法
        const val TEXT_TITLE = "OpenAI 兼容配置"

        // 显式保存（2026-10-01 用户要求）：按钮文案 + 结果提示（与翻译设置页同款措辞）
        const val TEXT_SAVE = "保存"
        const val TEXT_SAVE_IDLE = "点「保存」立即写入本机（改动也会在离开页面时自动保存）"
        const val TEXT_SAVED = "已保存到本机"
        const val TEXT_SAVE_FAILED = "保存未完成，请重试"

        /** 保存结果三态（2026-10-02 修复 L-420 的 OpenAI 页侧）：失败要能分清是哪一步 */
        const val TEXT_SAVE_DISK_FAILED = "写入本机失败，请检查存储空间后重试"
        const val TEXT_SAVE_NOT_PERSISTED = "凭据未写入本机（设备可能已锁定，解锁后重试）"
        const val TEXT_SAVE_KEY_FAILED = "API Key 未写入本机，请重试"

        /**
         * 写盘成功、但有字段不合法（2026-10-03 修复 L-514）。
         *
         * 此前 `ok` 只看写盘与凭据回读，于是非法 JSON / 缺 `{{text}}` 的提示词会同时出现
         * 「已保存到本机」与一条红色 error —— 用户以为配置生效了。
         */
        const val TEXT_SAVE_FIELDS_INVALID = "已保存到本机；但有字段不合法（见输入框下方的提示）"
        const val TEXT_DESC = "留空 = 用默认值或不发送该参数；提示词支持 {{text}} / {{target_language}} / {{source_language}} / {{date}}"
        const val SECTION_BASIC = "基础配置"
        const val SECTION_PROMPT = "翻译设置"
        const val SECTION_PARAMS = "模型参数"
        const val SECTION_ADVANCED = "高级"

        const val TEXT_NAME = "配置名称"
        const val TEXT_NAME_HINT = "如 DeepSeek、OpenRouter、本地 Ollama"
        const val TEXT_BASE_URL = "API Base URL"
        const val TEXT_BASE_URL_HINT = "如 https://api.openai.com/v1"
        const val TEXT_API_KEY = "API Key"
        const val TEXT_API_KEY_HINT = "Bearer 鉴权用（只存本机，不进日志）"
        const val TEXT_MODEL = "Model"
        const val TEXT_MODEL_HINT = "模型 ID，可点「获取模型」拉取"
        const val TEXT_FETCH_MODELS = "获取模型"
        const val TEXT_TEST = "测试连接"

        const val TEXT_TARGET = "目标语言"
        const val TEXT_TARGET_CUSTOM = "自定义语言（非空则优先）"
        const val TEXT_TARGET_CUSTOM_HINT = "如 粤语、古文、繁體中文（台灣）"
        const val TEXT_SYSTEM = "System Prompt"
        const val TEXT_USER = "User Prompt"
        const val TEXT_PROMPT_RESET = "恢复默认提示词"
        const val TEXT_PROMPT_RESET_DONE = "已恢复默认提示词"

        const val TEXT_TEMPERATURE = "Temperature（留空 = 不发送）"
        const val TEXT_TEMPERATURE_HINT = "如 0"
        const val TEXT_TOP_P = "Top P（留空 = 不发送）"
        const val TEXT_TOP_P_HINT = "如 1"
        const val TEXT_MAX_TOKENS = "Max Tokens（留空 = 不发送）"
const val TEXT_MAX_TOKENS_HINT =
            "如 2048。推理模型（o1 / o3 / gpt-5 等）只认 max_completion_tokens：" +
                "此处留空，在自定义 JSON 里写 {\"max_completion_tokens\": 2048}"

        const val TEXT_CHAT_PATH = "对话端点路径"
        const val TEXT_CHAT_PATH_HINT = "/chat/completions"
        const val TEXT_MODELS_PATH = "模型列表路径"
        const val TEXT_MODELS_PATH_HINT = "/models"
        const val TEXT_RESPONSE_PATH = "响应解析路径"
        const val TEXT_RESPONSE_PATH_HINT = "choices[0].message.content"
        const val TEXT_TIMEOUT = "整体超时（秒，5~300）"
        const val TEXT_TIMEOUT_HINT = "60"
        const val TEXT_HEADERS = "自定义 Headers（一行一条 Key: Value）"
        const val TEXT_HEADERS_HINT = "X-Custom: 1\n# 井号开头是注释，Authorization 由 API Key 独占"
        const val TEXT_EXTRA_JSON = "自定义 JSON（优先级最高，同名覆盖）"
const val TEXT_EXTRA_JSON_HINT =
            "{\"enable_thinking\": false}；值写 null = 删掉同名标准参数" +
                "（如 {\"max_tokens\": null, \"max_completion_tokens\": 2048}）"

        const val TEXT_FOOTER = "提示：JSON 写错只会被忽略并在此提示，不影响翻译；换服务只需改这里的路径与参数。"
        const val TEXT_FETCHING = "正在请求…"
        const val TEXT_MODELS_EMPTY = "服务端返回了空列表（不代表配置错误）"
        const val TEXT_PICK_MODEL = "选择模型"
        const val TEXT_CANCEL = "取消"
        const val TEXT_URL_INVALID = "Base URL 无法解析，请检查格式"
        const val TEXT_NETWORK_FAILED = "请求失败：网络错误或超时"
        const val TEXT_NEED_HTTPS = "地址需以 https:// 开头（明文会把 Key 暴露在链路上）"
        const val TEXT_KEY_INVALID = "Key 里混入了不可见字符（从网页复制常见），请重新粘贴"
    const val TEXT_PROMPT_MISSING = "提示词里没有 {{text}}：请求不会带原文，会把原文追加到提示词之后"

    /** 数字参数非法（2026-10-02 修复 L-370：该参数不会被发送，此前完全静默） */
    const val TEXT_NUMBER_INVALID = "不是合法数字，该参数不会被发送"

    // ⚠ 上面这条 [TEXT_NEED_HTTPS] 现在**保存侧也在用**（2026-10-03 修复 L-513）：
    // 运行期 `TranslationClient` 对明文地址一律拒发（归 INSECURE），而保存、状态摘要、「已配置」
    // 三处此前全部放行 `http://` ⇒ 配置信任链断裂（用户拿到「配置成功」的正反馈，直到第一次
    // 点翻译才失败）；平台层对用户自填域名没有兜底，这里是唯一的把关点。

    const val TEXT_EXTRA_JSON_INVALID =
        "自定义 JSON 不可用：解析失败、开了 stream（本客户端按非流式解析）、删必需键（model / messages）、或覆盖 messages（原文在里面）"
        const val TEXT_TEST_OK_FMT = "连接正常，可选模型 %d 个"
        const val TEXT_TEST_FAIL_FMT = "连接失败：HTTP %d（该服务可能未实现 /models，不影响翻译）"
    }
}
