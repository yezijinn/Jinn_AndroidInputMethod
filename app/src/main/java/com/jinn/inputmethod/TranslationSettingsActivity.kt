package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * 翻译设置页（BYOK）：配置各服务商凭据与目标语言。
 * 支持改动即时落盘及手动显式保存；输入法每次请求实时读取 Prefs，修改即刻生效。
 */
class TranslationSettingsActivity : Activity() {

    private lateinit var spinnerProvider: Spinner
    private lateinit var spinnerTarget: Spinner
    private lateinit var blockAliyun: LinearLayout
    private lateinit var blockAzure: LinearLayout
    private lateinit var blockBaidu: LinearLayout
    private lateinit var blockBaiduLlm: LinearLayout
    private lateinit var blockDeepl: LinearLayout
    private lateinit var blockOpenAi: LinearLayout
    private lateinit var editAliyunKeyId: EditText
    private lateinit var editAliyunKeySecret: EditText
    private lateinit var editAzureKey: EditText
    private lateinit var editAzureRegion: EditText
    private lateinit var editBaiduAppId: EditText
    private lateinit var editBaiduSecret: EditText
    private lateinit var editBaiduLlmAppId: EditText
    private lateinit var editBaiduLlmKey: EditText
    private lateinit var editDeeplKey: EditText
    private lateinit var btnOpenAiConfig: Button
    private lateinit var labelTarget: TextView
    private lateinit var textAliyunState: TextView
    private lateinit var textAzureState: TextView
    private lateinit var textBaiduState: TextView
    private lateinit var textBaiduLlmState: TextView
    private lateinit var textDeeplState: TextView
    private lateinit var textOpenAiState: TextView
    private lateinit var btnTranslateSource: Button
    private lateinit var textTranslateSourceState: TextView

    // 显式保存按钮与反馈提示
    private lateinit var btnSave: Button
    private lateinit var textSaveHint: TextView

    // 避免 setSelection 初始化误触发写配置
    private var providerTouched = false
    private var targetTouched = false

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
        // 凭据防截屏/录屏/镜像（注：投屏环境下本页面及弹出层均会被系统黑屏遮蔽）
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContentView(R.layout.activity_translation_settings)

        findViewById<TextView>(R.id.text_translate_settings_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_translate_settings_desc).text = TEXT_DESC
        findViewById<TextView>(R.id.label_translate_provider).text = TEXT_PROVIDER
        findViewById<TextView>(R.id.label_aliyun_key_id).text = TEXT_ALIYUN_KEY_ID
        findViewById<TextView>(R.id.label_aliyun_key_secret).text = TEXT_ALIYUN_KEY_SECRET
        findViewById<TextView>(R.id.label_azure_key).text = TEXT_AZURE_KEY
        findViewById<TextView>(R.id.label_azure_region).text = TEXT_AZURE_REGION
        findViewById<TextView>(R.id.label_baidu_app_id).text = TEXT_BAIDU_APP_ID
        findViewById<TextView>(R.id.label_baidu_secret).text = TEXT_BAIDU_SECRET
        findViewById<TextView>(R.id.label_baidu_llm_app_id).text = TEXT_BAIDU_LLM_APP_ID
        findViewById<TextView>(R.id.label_baidu_llm_key).text = TEXT_BAIDU_LLM_KEY
        findViewById<TextView>(R.id.label_deepl_key).text = TEXT_DEEPL_KEY
        labelTarget = findViewById(R.id.label_translate_target)
        labelTarget.text = TEXT_TARGET
        findViewById<TextView>(R.id.text_translate_privacy).text = TEXT_PRIVACY
        findViewById<Button>(R.id.btn_translate_settings_close).apply {
            // 关闭键：键面字形与可听名统一来自 PageChrome（原先各页自写 `X` / `关闭`，见 BUG.md L-477）
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }

        blockAliyun = findViewById(R.id.block_translate_aliyun)
        blockAzure = findViewById(R.id.block_translate_azure)
        blockBaidu = findViewById(R.id.block_translate_baidu)
        blockBaiduLlm = findViewById(R.id.block_translate_baidu_llm)
        blockDeepl = findViewById(R.id.block_translate_deepl)
        blockOpenAi = findViewById(R.id.block_translate_openai)
        editAliyunKeyId = findViewById(R.id.edit_aliyun_key_id)
        editAliyunKeySecret = findViewById(R.id.edit_aliyun_key_secret)
        editAzureKey = findViewById(R.id.edit_azure_key)
        editAzureRegion = findViewById(R.id.edit_azure_region)
        editBaiduAppId = findViewById(R.id.edit_baidu_app_id)
        editBaiduSecret = findViewById(R.id.edit_baidu_secret)
        editBaiduLlmAppId = findViewById(R.id.edit_baidu_llm_app_id)
        editBaiduLlmKey = findViewById(R.id.edit_baidu_llm_key)
        editDeeplKey = findViewById(R.id.edit_deepl_key)
        btnOpenAiConfig = findViewById(R.id.btn_openai_config)
        btnTranslateSource = findViewById(R.id.btn_translate_source)
        textTranslateSourceState = findViewById(R.id.text_translate_source_state)
        textAliyunState = findViewById(R.id.text_aliyun_state)
        textAzureState = findViewById(R.id.text_azure_state)
        textBaiduState = findViewById(R.id.text_baidu_state)
        textBaiduLlmState = findViewById(R.id.text_baidu_llm_state)
        textDeeplState = findViewById(R.id.text_deepl_state)
        textOpenAiState = findViewById(R.id.text_openai_state)
        spinnerProvider = findViewById(R.id.spinner_translate_provider)
        spinnerTarget = findViewById(R.id.spinner_translate_target)

        // 显式保存按钮
        btnSave = findViewById(R.id.btn_translate_save)
        btnSave.text = TEXT_SAVE
        btnSave.setOnClickListener { saveAndNotify() }
        textSaveHint = findViewById(R.id.text_translate_save_hint)
        textSaveHint.text = TEXT_SAVE_IDLE

        editAliyunKeyId.hint = TEXT_ALIYUN_KEY_ID_HINT
        editAliyunKeySecret.hint = TEXT_ALIYUN_KEY_SECRET_HINT
        editAzureKey.hint = TEXT_AZURE_KEY_HINT
        editAzureRegion.hint = TEXT_AZURE_REGION_HINT
        editBaiduAppId.hint = TEXT_BAIDU_APP_ID_HINT
        editBaiduSecret.hint = TEXT_BAIDU_SECRET_HINT
        editBaiduLlmAppId.hint = TEXT_BAIDU_LLM_APP_ID_HINT
        editBaiduLlmKey.hint = TEXT_BAIDU_LLM_KEY_HINT
        editDeeplKey.hint = TEXT_DEEPL_KEY_HINT
        btnOpenAiConfig.text = TEXT_OPENAI_CONFIG
        btnOpenAiConfig.setOnClickListener {
            Diagnostics.i(TAG, "翻译设置: 打开 OpenAI 兼容配置页")
            runCatching { startActivity(Intent(this, OpenAiSettingsActivity::class.java)) }
                .onFailure { Diagnostics.w(TAG, "打开 OpenAI 兼容配置失败: ${it.message}") }
        }
        btnTranslateSource.text = TEXT_SOURCE_CONFIG
        btnTranslateSource.setOnClickListener {
            Diagnostics.i(TAG, "翻译设置: 打开原文范围设置页")
            runCatching { startActivity(Intent(this, TranslationSourceActivity::class.java)) }
                .onFailure { Diagnostics.w(TAG, "打开原文范围设置失败: ${it.message}") }
        }

        initSpinners()
        bindCredentialFields()
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
        loadValues()
        // 清除剪贴板历史中残留的已配置凭据，防止泄露
        purgeClipboardHistory(configuredCredentials())
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /** 当前配置的全部凭据列表，仅用于历史清理比对 */
    private fun configuredCredentials(): List<String> = listOf(
        prefs.aliyunAccessKeyId, prefs.aliyunAccessKeySecret,
        prefs.azureApiKey, prefs.baiduAppId, prefs.baiduSecretKey,
        prefs.baiduLlmAppId, prefs.baiduLlmApiKey, prefs.deeplApiKey,
        prefs.openAiApiKey,
    )

    /** 从剪贴板数据库中异步剔除匹配当前凭据的历史记录（保留已收藏项） */
    private fun purgeClipboardHistory(values: List<String>) {
        val targets = values.filter { it.isNotBlank() }
        if (targets.isEmpty()) {
            Diagnostics.v(TAG, "凭据不留痕: 无目标（本页未填凭据）")
            return
        }
        val appContext = applicationContext
        BackgroundIo.run {
            val db = runCatching { ClipboardDb.get(appContext) }.getOrElse {
                Diagnostics.w(TAG, "凭据不留痕: 库打不开，本轮未清理（${it.javaClass.simpleName}）")
                return@run
            }
            val removed = CredentialTrace.purge(db, targets)
            if (removed > 0) {
                Diagnostics.i(TAG, "凭据不留痕: 从剪贴板历史删除 $removed 条")
            } else {
                Diagnostics.v(TAG, "凭据不留痕: 本轮无需删除（${targets.size} 个目标）")
            }
        }
    }

    /** 退出前执行凭据落盘；避免在 onStop 保存导致上级页面读取旧值 */
    override fun onPause() {
        super.onPause()
        // 配置导入进行中跳过回写，避免界面旧值覆盖导入数据
        if (ConfigBackupManager.importing) {
            Diagnostics.w(TAG, "onPause: 导入进行中，跳过凭据回写（避免覆盖导入结果）")
        } else {
            saveCredentials()
        }
        if (!prefs.flush()) Diagnostics.w(TAG, "onPause: 凭据落盘失败（改动可能回退）")
    }

    /** 区分用户交互（触控/无障碍/键盘）与代码初始化回调 */
    private fun isUserDriven(view: View): Boolean = view.isFocused || view.isPressed

    private fun initSpinners() {
        spinnerProvider.adapter = ArrayAdapter(
            this, R.layout.item_spinner,
            TranslationProviderId.entries.map { it.label },
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerProvider.setOnTouchListener { view, event ->
            providerTouched = true
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyProviderVisibility()
                if (isUserDriven(spinnerProvider)) providerTouched = true
                if (!providerTouched) return
                val picked = TranslationProviderId.entries.getOrNull(position) ?: return
                if (picked.id != prefs.translateProvider) {
                    prefs.translateProvider = picked.id
                    Diagnostics.i(TAG, "翻译服务: ${picked.id}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTarget.adapter = ArrayAdapter(
            this, R.layout.item_spinner,
            TranslationLanguage.entries.map { it.label },
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerTarget.setOnTouchListener { view, event ->
            targetTouched = true
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUserDriven(spinnerTarget)) targetTouched = true
                if (!targetTouched) return
                val picked = TranslationLanguage.entries.getOrNull(position) ?: return
                if (picked.name != prefs.translateTarget) {
                    prefs.translateTarget = picked.name
                    Diagnostics.i(TAG, "翻译目标语言: ${picked.name}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 焦点移出输入框时自动暂存 */
    private fun bindCredentialFields() {
        val save = View.OnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveCredentials() }
        editAliyunKeyId.setOnFocusChangeListener(save)
        editAliyunKeySecret.setOnFocusChangeListener(save)
        editAzureKey.setOnFocusChangeListener(save)
        editAzureRegion.setOnFocusChangeListener(save)
        editBaiduAppId.setOnFocusChangeListener(save)
        editBaiduSecret.setOnFocusChangeListener(save)
        editBaiduLlmAppId.setOnFocusChangeListener(save)
        editBaiduLlmKey.setOnFocusChangeListener(save)
        editDeeplKey.setOnFocusChangeListener(save)
    }

    private val saveGuard = CredentialSaveGuard()

    /** 填充输入框并记录初始值，用于变更检测 */
    private fun loadField(field: android.widget.EditText, value: String) {
        field.setText(value)
        saveGuard.remember(field, value)
    }

    private fun loadValues() {
        // 先复位交互标记，防止触发写入
        providerTouched = false
        targetTouched = false
        spinnerProvider.setSelection(TranslationProviderId.of(prefs.translateProvider).ordinal)
        spinnerTarget.setSelection(TranslationLanguage.of(prefs.translateTarget).ordinal)

        // 仅在字段被真实修改后回写，避免解密失败时的空值覆盖已有密文
        saveGuard.reset()
        loadField(editAliyunKeyId, prefs.aliyunAccessKeyId)
        loadField(editAliyunKeySecret, prefs.aliyunAccessKeySecret)
        loadField(editAzureKey, prefs.azureApiKey)
        loadField(editAzureRegion, prefs.azureRegion)
        loadField(editBaiduAppId, prefs.baiduAppId)
        loadField(editBaiduSecret, prefs.baiduSecretKey)
        loadField(editBaiduLlmAppId, prefs.baiduLlmAppId)
        loadField(editBaiduLlmKey, prefs.baiduLlmApiKey)
        loadField(editDeeplKey, prefs.deeplApiKey)
        applyProviderVisibility()
        refreshState()
    }

    /** 切换服务商卡片展示；OpenAI 目标语言由其专用配置页控制，此处置灰 */
    private fun applyProviderVisibility() {
        val picked = TranslationProviderId.entries.getOrNull(spinnerProvider.selectedItemPosition)
            ?: TranslationProviderId.DEFAULT
        blockAliyun.visibility = if (picked == TranslationProviderId.ALIYUN) View.VISIBLE else View.GONE
        blockAzure.visibility = if (picked == TranslationProviderId.AZURE) View.VISIBLE else View.GONE
        blockBaidu.visibility = if (picked == TranslationProviderId.BAIDU) View.VISIBLE else View.GONE
        blockBaiduLlm.visibility = if (picked == TranslationProviderId.BAIDU_LLM) View.VISIBLE else View.GONE
        blockDeepl.visibility = if (picked == TranslationProviderId.DEEPL) View.VISIBLE else View.GONE
        blockOpenAi.visibility = if (picked == TranslationProviderId.OPENAI) View.VISIBLE else View.GONE
        val openAi = picked == TranslationProviderId.OPENAI
        spinnerTarget.isEnabled = !openAi
        labelTarget.text = if (openAi) TEXT_TARGET_OPENAI else TEXT_TARGET
        refreshSourceState(picked)
    }

    /** 根据当前选中的服务商显示其原文捕获范围与字节上限 */
    private fun refreshSourceState(picked: TranslationProviderId) {
        val scope = TranslationScope.of(prefs.translateScopeOf(picked))
        textTranslateSourceState.text =
            "当前：${scope.label} · 上限 ${prefs.translateMaxBytesOf(picked)} 字节"
    }

    /** 保存修改过的凭据，日志仅输出长度 */
    private fun saveCredentials() {
        if (saveGuard.changed(editAliyunKeyId)) prefs.aliyunAccessKeyId = editAliyunKeyId.text.toString()
        if (saveGuard.changed(editAliyunKeySecret)) {
            prefs.aliyunAccessKeySecret = editAliyunKeySecret.text.toString()
        }
        if (saveGuard.changed(editAzureKey)) prefs.azureApiKey = editAzureKey.text.toString()
        if (saveGuard.changed(editAzureRegion)) prefs.azureRegion = editAzureRegion.text.toString()
        if (saveGuard.changed(editBaiduAppId)) prefs.baiduAppId = editBaiduAppId.text.toString()
        if (saveGuard.changed(editBaiduSecret)) prefs.baiduSecretKey = editBaiduSecret.text.toString()
        if (saveGuard.changed(editBaiduLlmAppId)) prefs.baiduLlmAppId = editBaiduLlmAppId.text.toString()
        if (saveGuard.changed(editBaiduLlmKey)) prefs.baiduLlmApiKey = editBaiduLlmKey.text.toString()
        if (saveGuard.changed(editDeeplKey)) prefs.deeplApiKey = editDeeplKey.text.toString()

        Diagnostics.i(
            TAG,
            "翻译凭据已保存（长度）: aliyunId=${prefs.aliyunAccessKeyId.length} " +
                "aliyunSecret=${prefs.aliyunAccessKeySecret.length} " +
                "azureKey=${prefs.azureApiKey.length} azureRegion=${prefs.azureRegion.length} " +
                "baiduAppId=${prefs.baiduAppId.length} baiduSecret=${prefs.baiduSecretKey.length} " +
                "baiduLlmAppId=${prefs.baiduLlmAppId.length} baiduLlmKey=${prefs.baiduLlmApiKey.length} " +
                "deeplKey=${prefs.deeplApiKey.length}",
        )
        refreshState()
        purgeClipboardHistory(configuredCredentials())
    }

    /** 执行同步落盘并回读校验，展示确认状态 */
    private fun saveAndNotify() {
        val pickedProvider = TranslationProviderId.entries.getOrNull(spinnerProvider.selectedItemPosition)
        if (pickedProvider != null) prefs.translateProvider = pickedProvider.id
        val pickedTarget = TranslationLanguage.entries.getOrNull(spinnerTarget.selectedItemPosition)
        if (pickedTarget != null && pickedProvider != TranslationProviderId.OPENAI) {
            prefs.translateTarget = pickedTarget.name
        }
        saveCredentials()
        val flushed = prefs.flush()
        val failed = mismatchedCredentials()
        val unpersisted = prefs.unpersistedCredentialKeys()

        // 重新载入持久化后的值刷新界面
        loadValues()
        refreshState()
        val ok = failed.isEmpty() && flushed && unpersisted.isEmpty()

        textSaveHint.text = when {
            !flushed -> TEXT_SAVE_DISK_FAILED
            unpersisted.isNotEmpty() -> TEXT_SAVE_NOT_PERSISTED
            failed.isNotEmpty() -> TEXT_SAVE_MISMATCH + failed.joinToString("、")
            // 凭据不完整也要说清（2026-10-03 修复 L-515）：写盘没失败，但同一屏上不能一边
            // 「已保存到本机」一边「未配置：还没填凭据」—— 用户会以为保存没生效而反复粘贴。
            // ⚠ 判据取**本页选中的那家**（2026-10-03 修复 L-546）：全局 getter 在键未写入时会逐家
            // 推导，已配好 DeepL 的用户来编辑「阿里云」时会被判成"已配置"，收不到这条提示 ——
            // 而这条提示描述的是"你刚编辑的那家"，就该用选中的那家判。
            pickedProvider != null && prefs.translationProvider(pickedProvider) == null -> TEXT_SAVE_INCOMPLETE
            else -> TEXT_SAVED
        }
        if (ok) {
            toast(TEXT_SAVED)
        } else {
            Diagnostics.w(TAG, "显式保存未完成: flushed=$flushed 未落盘=${unpersisted.size} 不一致字段=$failed")
            toast(TEXT_SAVE_FAILED)
        }
    }

    /** 校验本次修改过的字段在落盘后与预期值是否一致 */
    private fun mismatchedCredentials(): List<String> {
        val pairs = listOf(
            Triple("阿里云 AccessKey ID", editAliyunKeyId, prefs.aliyunAccessKeyId),
            Triple("阿里云 AccessKey Secret", editAliyunKeySecret, prefs.aliyunAccessKeySecret),
            Triple("Azure API Key", editAzureKey, prefs.azureApiKey),
            Triple("Azure 区域", editAzureRegion, prefs.azureRegion),
            Triple("百度 AppID", editBaiduAppId, prefs.baiduAppId),
            Triple("百度 SecretKey", editBaiduSecret, prefs.baiduSecretKey),
            Triple("百度大模型 APPID", editBaiduLlmAppId, prefs.baiduLlmAppId),
            Triple("百度大模型 API Key", editBaiduLlmKey, prefs.baiduLlmApiKey),
            Triple("DeepL API Key", editDeeplKey, prefs.deeplApiKey),
        )
        return pairs.filter { (_, field, saved) ->
            if (!saveGuard.changed(field)) return@filter false
            val expected = if (field.id == R.id.edit_azure_region) {
                normalizeAzureRegion(field.text.toString())
            } else {
                field.text.toString().cleanCredential()
            }
            saved != expected
        }.map { it.first }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 刷新凭据状态文本，判空逻辑与 TranslationClient.providerOf 保持一致 */
    private fun refreshState() {
        textAliyunState.text = stateText(
            prefs.aliyunAccessKeyId.cleanCredential().isNotEmpty() &&
                prefs.aliyunAccessKeySecret.cleanCredential().isNotEmpty()
        )
        textAzureState.text = stateText(prefs.azureApiKey.cleanCredential().isNotEmpty())
        textBaiduState.text = stateText(
            prefs.baiduAppId.cleanCredential().isNotEmpty() && prefs.baiduSecretKey.cleanCredential().isNotEmpty()
        )
        textBaiduLlmState.text = stateText(
            prefs.baiduLlmAppId.cleanCredential().isNotEmpty() && prefs.baiduLlmApiKey.cleanCredential().isNotEmpty()
        )
        textDeeplState.text = stateText(prefs.deeplApiKey.cleanCredential().isNotEmpty())

        val openAiReady = prefs.openAiApiKey.cleanCredential().isNotEmpty() &&
            prefs.openAiModel.cleanCredential().isNotEmpty() &&
            OpenAiTranslator.joinUrl(prefs.openAiBaseUrl, prefs.openAiChatPath) != null
        textOpenAiState.text = if (openAiReady) {
            "${prefs.openAiName} · ${prefs.openAiModel}"
        } else {
            TEXT_OPENAI_UNCONFIGURED
        }
    }

    private fun stateText(configured: Boolean): String =
        if (configured) TEXT_CONFIGURED else TEXT_UNCONFIGURED

    private companion object {
        const val TAG = "TranslationSettings"

        const val TEXT_TITLE = "翻译设置"
        const val TEXT_DESC = "使用你自带的 API 凭据翻译（BYOK）：输入法不内置任何共享 Key，凭据只存本机。\n" +
            "怎么用：点键盘功能面板的「翻译」—— 选中了内容就翻选中的那一段、译文原地替换；" +
            "没选中就按「翻译原文范围」取光标前后的文本，译文另起一行追加、原文一个字不动。\n" +
            "免费额度（2026-10 核对，以平台为准）：阿里云 100 万字符/月；Azure F0 层 200 万字符/月；" +
            "百度标准版 5 万字符/月（个人认证后 100 万）；百度大模型与 DeepL 各为一次性 100 万字符。"
        const val TEXT_PROVIDER = "翻译服务"
        const val TEXT_ALIYUN_KEY_ID = "阿里云 AccessKey ID"
        const val TEXT_ALIYUN_KEY_ID_HINT = "RAM 用户的 AccessKey ID"
        const val TEXT_ALIYUN_KEY_SECRET = "阿里云 AccessKey Secret"
        const val TEXT_ALIYUN_KEY_SECRET_HINT = "AccessKey Secret（只在本机做签名）"
        const val TEXT_AZURE_KEY = "Azure API Key"
        const val TEXT_AZURE_KEY_HINT = "粘贴 Azure 订阅密钥"
        const val TEXT_AZURE_REGION = "区域（单区域资源可留空）"
        const val TEXT_AZURE_REGION_HINT = "如 eastasia"
        const val TEXT_BAIDU_APP_ID = "百度 AppID"
        const val TEXT_BAIDU_APP_ID_HINT = "通用文本翻译的开发者 AppID"
        const val TEXT_BAIDU_SECRET = "百度 SecretKey"
        const val TEXT_BAIDU_SECRET_HINT = "通用文本翻译的密钥（只在本机做签名）"
        const val TEXT_BAIDU_LLM_APP_ID = "百度大模型 APPID"
        const val TEXT_BAIDU_LLM_APP_ID_HINT = "大模型文本翻译服务的 APPID"
        const val TEXT_BAIDU_LLM_KEY = "百度大模型 API Key"
        const val TEXT_BAIDU_LLM_KEY_HINT = "Bearer 鉴权用（控制台「API Key 管理」里创建）"
        const val TEXT_DEEPL_KEY = "DeepL API Key"
        const val TEXT_DEEPL_KEY_HINT = "Free 密钥以 :fx 结尾，域名自动切换"
        const val TEXT_OPENAI_CONFIG = "配置模型与参数"
        const val TEXT_OPENAI_UNCONFIGURED = "未配置：点上面的按钮填 Base URL / API Key / 模型"
        const val TEXT_SOURCE_CONFIG = "翻译原文范围（每家服务方独立）"
        const val TEXT_TARGET = "目标语言"
        const val TEXT_TARGET_OPENAI = "目标语言（OpenAI 兼容在配置页里设置）"
        const val TEXT_PRIVACY = "上传的是哪一段：选中模式下只有你选中的那一段；" +
            "没选中时按「翻译原文范围」取（默认只取光标所在这一行里光标前面的内容）。\n" +
            "密码类输入框、以及声明「不要个性化学习」的输入框不会翻译。\n" +
            "凭据只存本机，随配置备份一起加密；界面与日志只显示是否已配置。"
        const val TEXT_CONFIGURED = "已配置"
        const val TEXT_UNCONFIGURED = "未配置：还没填凭据"

        const val TEXT_SAVE = "保存"
        const val TEXT_SAVE_IDLE = "点「保存」立即写入本机（改动也会在离开页面时自动保存）"
        const val TEXT_SAVED = "已保存到本机"
        const val TEXT_SAVE_FAILED = "保存未完成，请重试"
        const val TEXT_SAVE_MISMATCH = "未写入的字段："
        const val TEXT_SAVE_DISK_FAILED = "写入本机失败，请检查存储空间后重试"
        const val TEXT_SAVE_NOT_PERSISTED = "凭据未写入本机（设备可能已锁定，解锁后重试）"

        /**
         * 写盘成功、但当前服务方的凭据不完整（2026-10-03 修复 L-515）。
         *
         * 不算失败（配置确实存下来了），但必须说清 —— 否则同一屏上「已保存到本机」与状态文字的
         * 「未配置：还没填凭据」并存，用户会以为保存没生效而反复粘贴。
         */
        const val TEXT_SAVE_INCOMPLETE = "已保存到本机；当前服务方的凭据还不完整，补齐后才能翻译"
    }
}