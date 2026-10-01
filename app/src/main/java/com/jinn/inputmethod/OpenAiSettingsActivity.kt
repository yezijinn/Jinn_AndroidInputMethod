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
            text = TEXT_CLOSE
            contentDescription = TEXT_CLOSE_DESC
            setOnClickListener { finish() }
        }

        editName = findViewById(R.id.edit_ai_name)
        editBaseUrl = findViewById(R.id.edit_ai_base_url)
        editApiKey = findViewById(R.id.edit_ai_api_key)
        editModel = findViewById(R.id.edit_ai_model)
        editChatPath = findViewById(R.id.edit_ai_chat_path)
        editModelsPath = findViewById(R.id.edit_ai_models_path)
        editTargetCustom = findViewById(R.id.edit_ai_target_custom)
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
        saveValues()
        prefs.flush()
        // 「凭据不留痕」（2026-09-30）：用户从云控制台复制的 API Key 已被剪贴板监听采集入库
        // （面板里明文可见、随备份导出），保存后把它从历史里删掉（精确匹配，收藏条目不动）
        purgeApiKeyFromClipboardHistory()
    }

    override fun onStart() {
        super.onStart()
        loadValues()
        purgeApiKeyFromClipboardHistory()
    }

    /** 从剪贴板历史删除与本机 API Key 相同的条目（后台线程；见 [purgeApiKeyFromClipboardHistory] 的说明） */
    private fun purgeApiKeyFromClipboardHistory() {
        val appContext = applicationContext
        BackgroundIo.run {
            val db = runCatching { ClipboardDb.get(appContext) }.getOrNull() ?: return@run
            // 与翻译设置页共用同一条链路（精确哈希 → 剥不可见字符后相等，一次遍历处理全部目标）：
            // 本页此前只有精确哈希，带 NBSP / ZWSP 的 Key 一条都删不掉（2026-10-01 修复 L-245）
            // 目标除 API Key 外，还包含自定义请求头与自定义 JSON 里的值（2026-10-01 修复 L-252）
            val targets = listOf(prefs.openAiApiKey) +
                CredentialTrace.candidatesFrom(prefs.openAiExtraHeaders, prefs.openAiExtraJson)
            val removed = CredentialTrace.purge(db, targets)
            if (removed > 0) Diagnostics.i(TAG, "凭据不留痕: 从剪贴板历史删除 $removed 条")
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
            android.R.layout.simple_spinner_item,
            TARGET_LANGUAGES,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerTarget.setOnTouchListener { view, event ->
            targetTouched = true
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!targetTouched) return
                // 选了标准语言就清掉自定义框（两者只应有一个生效）
                editTargetCustom.setText("")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 只回写用户真的改过的字段（2026-10-01 修复 L-247）：见 [CredentialSaveGuard] */
    private val saveGuard = CredentialSaveGuard()

    /** 灌值并记下「载入时的原值」（与 [saveGuard] 配套） */
    private fun loadField(field: android.widget.EditText, value: String) {
        field.setText(value)
        saveGuard.remember(field, value)
    }

    private fun loadValues() {
        targetTouched = false
        saveGuard.reset()
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
        val promptMissing = OpenAiTranslator.VAR_TEXT !in prefs.openAiSystemPrompt &&
            OpenAiTranslator.VAR_TEXT !in prefs.openAiUserPrompt
        editUser.error = if (promptMissing) TEXT_PROMPT_MISSING else null
        // 自定义 JSON 写错时参数会被静默丢弃（2026-10-01 修复 L-257）：用 error 让用户看得见
        editExtraJson.error =
            if (OpenAiTranslator.isValidExtraJson(prefs.openAiExtraJson)) null else TEXT_EXTRA_JSON_INVALID
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
        prefs.openAiTemperature = editTemperature.text.toString()
        prefs.openAiTopP = editTopP.text.toString()
        prefs.openAiMaxTokens = editMaxTokens.text.toString()
        prefs.openAiResponsePath = editResponsePath.text.toString()
        prefs.openAiTimeoutSec = editTimeout.text.toString().toIntOrNull() ?: OpenAiTranslator.DEFAULT_TIMEOUT_SEC
        prefs.openAiExtraHeaders = editHeaders.text.toString()
        prefs.openAiExtraJson = editExtraJson.text.toString()
        // 自定义语言非空则优先；否则用下拉里选中的那项
        val custom = editTargetCustom.text.toString().trim()
        prefs.openAiTargetLanguage = custom.ifEmpty {
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
     * 拉取模型列表：先保存当前输入（避免用旧值请求），再 `GET {Base}{ModelsPath}`。
     *
     * 拿到就弹选择框（并写缓存）；失败只提示，**不判死** —— `/models` 不是所有兼容服务的必选项，
     * 用户照样能手填 Model。
     */
    private fun fetchModels(showPicker: Boolean) {
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
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 代际闸门：连点两次时，先发的旧响应后到也不能覆盖用户刚看到的新结果
                if (generation != fetchGeneration) return@runOnUiThread
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
        const val TEXT_CLOSE = "X"
        const val TEXT_CLOSE_DESC = "关闭"
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
        const val TEXT_MAX_TOKENS_HINT = "如 2048"

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
        const val TEXT_EXTRA_JSON_HINT = "{\"enable_thinking\": false, \"reasoning_effort\": \"low\"}"

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
    const val TEXT_EXTRA_JSON_INVALID = "自定义 JSON 解析失败：这些参数不会生效"
        const val TEXT_TEST_OK_FMT = "连接正常，可选模型 %d 个"
        const val TEXT_TEST_FAIL_FMT = "连接失败：HTTP %d（该服务可能未实现 /models，不影响翻译）"
    }
}
