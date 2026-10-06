package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
    /**
     * 程序性回填下拉时为 true：此时 `onItemSelected` 只切界面、**不落盘**（2026-10-04 修复 L-820）。
     *
     * 取代原先的 `providerTouched`：那套判据靠 `isFocused || isPressed || isAccessibilityFocused`
     * 猜「是否用户操作」，而下拉弹窗在 ColorOS 上这三项常常都是 false ⇒ 换了服务方凭据区块不跟随，
     * 重开页面才刷新。落盘闸门必须由**我们自己置位**（回填时置 true），不能由系统状态反推。
     */
    private var suppressProviderPersist = false

    /** 已经渲染在界面上的服务方；与下拉选中项不同才需要重切凭据区块 */
    private var renderedProvider: TranslationProviderId? = null
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
            // 同上：onPause 是自动保存路径，弹 toast 会与失焦那次重复（L-728）
            saveCredentials(notify = false)
            // 改动已经在这一行写完，撤掉排队的防抖任务（否则过一会儿再跑一次空转）
            credentialAutosave.removeCallbacks(autosaveCredentials)
            // ⚠ 下拉回写也必须在本守卫**之内**（2026-10-03 修复 L-774）：L-758 把它加在了 if/else 之外，
            // 而 L-405 的守卫只判「`importing` 字符串在 onPause 块里出现过」、不判「所有回写都在分支内」
            // ⇒ 一旦可达就是「界面旧值覆盖刚导入的值」。姊妹页 OpenAiSettingsActivity 的同类回写已整段在守卫内。
            //
            // 读屏 / 外接键盘选中的那一次可能既没被 `onItemSelected` 的闸门放行、也没进
            // `saveCredentials`（它只管凭据字段）⇒ 服务方 / 目标语言会静默丢失。
            // 这里按「当前选中项」补写一次：用户能看见的就是下拉框显示的那一项。
            val pickedProvider = TranslationProviderId.entries.getOrNull(spinnerProvider.selectedItemPosition)
            if (pickedProvider != null && pickedProvider.id != prefs.translateProvider) {
                prefs.translateProvider = pickedProvider.id
                Diagnostics.i(TAG, "翻译服务(onPause 回写): ${pickedProvider.id}")
            }
            val pickedTarget = TranslationLanguage.entries.getOrNull(spinnerTarget.selectedItemPosition)
            if (pickedTarget != null && pickedTarget.name != prefs.translateTarget) {
                prefs.translateTarget = pickedTarget.name
                Diagnostics.i(TAG, "翻译目标语言(onPause 回写): ${pickedTarget.name}")
            }
        }
        if (!prefs.flush()) Diagnostics.w(TAG, "onPause: 凭据落盘失败（改动可能回退）")
    }

    /**
     * 这次改动**是不是用户做的**。
     *
     * ⚠ 必须含 [View.isAccessibilityFocused]（2026-10-03 修复 L-758）：读屏与外接键盘选下拉**不产生触摸事件**
     *   ⇒ 少了这一位，TalkBack 双击选中的服务方 / 目标语言会被判成「不是用户动的」而**不落盘**，
     *   而界面早已切到对应区块，用户以为生效了。同一项目 `OpenAiSettingsActivity` 早就这么写。
     */
    private fun isUserDriven(view: View): Boolean =
        view.isFocused || view.isPressed || view.isAccessibilityFocused


    private fun initSpinners() {
        spinnerProvider.adapter = ArrayAdapter(
            this, R.layout.item_spinner,
            TranslationProviderId.entries.map { it.label },
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerProvider.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pickedNow = TranslationProviderId.entries.getOrNull(position) ?: return
                // ① 界面跟随选择：不看 isFocused / isPressed（2026-10-04 修复 L-820）。
                //    旧闸门里那三个判据（isUserDriven = isFocused || isPressed || isAccessibilityFocused）
                //    在下拉弹窗路径上常常都是 false ⇒ 改了服务方凭据区块不跟随，重开页面才刷新。
                if (renderedProvider != pickedNow) {
                    applyProviderVisibility(pickedNow)
                }
                // ② 落盘闸门由我们自己置位（loadValues 回填时为 true），不再由系统状态反推：
                //    触摸 / 外接键盘 / 读屏选中一律「先切界面、后落盘」，界面与落盘不会分叉。
                if (suppressProviderPersist) return
                // 与目标语言监听器对称：选中即落盘，不等「保存」按钮
                if (pickedNow.id != prefs.translateProvider) {
                    prefs.translateProvider = pickedNow.id
                    Diagnostics.i(TAG, "翻译服务: ${pickedNow.id}")
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
        // 失焦是高频触发点（9 个输入框），弹 toast 会变成十几次，而 textSaveHint 的常驻提示
        // 已经说清一切 ⇒ 自动路径一律 notify = false（2026-10-03 修复 L-728：上一轮只改了
        // 显式保存那条，自动路径漏了，代码与自己写的注释相互矛盾）
        val save = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCredentials(notify = false)
        }
        // 输入停顿即落盘（BUG.md L-209）：只靠失焦 / onPause 时，用户在输入框里粘贴完凭据、
        // 焦点还没移开就被系统回收（后台清理、内存压力），这一段配置**根本不会写盘**，
        // 而页面上的提示写着「自动保存」。防抖到停顿后再写，字段没变时 [CredentialSaveGuard]
        // 会把回写整条挡掉，所以连续输入不会反复加密落盘。
        val autosave = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) = scheduleCredentialAutosave()
        }
        for (field in credentialFields()) {
            field.setOnFocusChangeListener(save)
            field.addTextChangedListener(autosave)
        }
    }

    /** 本页凭据输入框清单：失焦暂存与输入即落盘共用，避免两份清单走散 */
    private fun credentialFields(): List<EditText> = listOf(
        editAliyunKeyId, editAliyunKeySecret,
        editAzureKey, editAzureRegion,
        editBaiduAppId, editBaiduSecret,
        editBaiduLlmAppId, editBaiduLlmKey,
        editDeeplKey,
    )

    /** 字段改动后延迟落盘；下一次改动把前一次排队顶掉（见 [bindCredentialFields]） */
    private fun scheduleCredentialAutosave() {
        credentialAutosave.removeCallbacks(autosaveCredentials)
        credentialAutosave.postDelayed(autosaveCredentials, CREDENTIAL_AUTOSAVE_DEBOUNCE_MS)
    }

    private val credentialAutosave = Handler(Looper.getMainLooper())

    private val autosaveCredentials = Runnable {
        // 导入进行中不回写：界面旧值会盖掉刚导入的凭据（与 onPause 同一守卫）
        if (!ConfigBackupManager.importing) saveCredentials(notify = false)
    }

    private val saveGuard = CredentialSaveGuard()

    /** 填充输入框并记录初始值，用于变更检测 */
    private fun loadField(field: android.widget.EditText, value: String) {
        field.setText(value)
        saveGuard.remember(field, value)
    }

    private fun loadValues() {
        // 先复位交互标记，防止触发写入
        targetTouched = false
        // 程序性回填下拉：setSelection 照样会触发 onItemSelected，此时只切界面、不落盘
        suppressProviderPersist = true
        spinnerProvider.setSelection(TranslationProviderId.of(prefs.translateProvider).ordinal)
        spinnerTarget.setSelection(TranslationLanguage.of(prefs.translateTarget).ordinal)
        suppressProviderPersist = false

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

    /**
     * 切换服务商卡片展示；OpenAI 目标语言由其专用配置页控制，此处置灰。
     *
     * @param picked 本次要渲染的服务方，默认取下拉当前选中项。显式传入是让去重判据与实际渲染同源：
     *   回调期间读 `spinnerProvider.selectedItemPosition` 在部分 ROM 上可能仍是旧值。
     */
    private fun applyProviderVisibility(
        picked: TranslationProviderId = TranslationProviderId.entries
            .getOrNull(spinnerProvider.selectedItemPosition) ?: TranslationProviderId.DEFAULT,
    ) {
        renderedProvider = picked
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

    /**
     * 保存修改过的凭据，日志仅输出长度。
     *
     * @param notify 是否由本函数弹「未写入本机」的 toast。**显式保存那条路要传 `false`**
     *   （2026-10-03 修复 L-717）：它自己会按三态出文案（未落盘 / 回读不符 / 已保存），
     *   两处都弹会互相打脸 —— Toast 队列后者覆盖前者，用户最终只看到信息量最少的那句
     *   「保存未完成，请重试」，而本可执行的「解锁后重试」被吃掉。
     *   ⚠ **没有默认参数**（2026-10-03 修复 L-814）：三个调用点（onPause / 失焦 / 显式保存）全都
     *   传 `false`，留默认值等于凭空造一条「不传就是弹 toast」的路径 —— 那条路径不存在。
     */
    private fun saveCredentials(notify: Boolean) {
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
        // ⚠ 自动保存路径必须也消费 `unpersistedCredentialKeys`（2026-10-03 修复 L-665）：
        // `writeCredential` 在 Keystore 不可用时把键记进该集合并**删掉磁盘旧密文**（fail-closed，
        // 有意设计），而它的 KDoc 写明「供**显式保存**如实报告」—— 显式保存是 `saveAndNotify` 那条路。
        // 本页的落盘时机是**失焦 / onPause 自动保存**（L-209 已记该窗口），也就是说绝大多数改动
        // 根本走不到告知分支：用户看到「已配置 / 已保存」，重启后该家回到未配置 ⇒ **Key 静默丢失**。
        // 不新增状态词，复用既有的 TEXT_SAVE_NOT_PERSISTED。
        val unpersisted = prefs.unpersistedCredentialKeys()
        if (unpersisted.isNotEmpty()) {
            Diagnostics.w(TAG, "凭据未落盘（自动保存路径）: ${unpersisted.size} 项")
            // 只改常驻提示，不弹 toast —— 失焦/onPause 都是高频触发点（9 个输入框 + 离开页面），
            // 弹一次会变成十几次（2026-10-03 修复 L-717 的第二半）。
            textSaveHint.text = TEXT_SAVE_NOT_PERSISTED
            if (notify) toast(TEXT_SAVE_NOT_PERSISTED)
        }
    }

    /** 执行同步落盘并回读校验，展示确认状态 */
    private fun saveAndNotify() {
        val pickedProvider = TranslationProviderId.entries.getOrNull(spinnerProvider.selectedItemPosition)
        if (pickedProvider != null) prefs.translateProvider = pickedProvider.id
        val pickedTarget = TranslationLanguage.entries.getOrNull(spinnerTarget.selectedItemPosition)
        if (pickedTarget != null && pickedProvider != TranslationProviderId.OPENAI) {
            prefs.translateTarget = pickedTarget.name
        }
        // 显式保存：由本函数按三态统一出文案（未落盘 / 回读不符 / 已保存），
        // 所以 saveCredentials 不再自己弹 —— 否则两处 toast 互相打脸（L-717）
        saveCredentials(notify = false)
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

        // ⚠ 判据收敛到 `OpenAiTranslator.isReady`（2026-10-03 修复 L-811）：原先这里只判
        // 「端点能解析」而**不判 https**，`providerOf` 也一样 ⇒ Base URL 填 `http://` 时本页显示
        // 「已配置 · 模型名」，点翻译却必被 INSECURE 拒掉（界面说配好了、一用就报错）。
        val openAiReady = OpenAiTranslator.isReady(
            prefs.openAiApiKey, prefs.openAiModel, prefs.openAiBaseUrl, prefs.openAiChatPath,
        )
        textOpenAiState.text = when {
            openAiReady -> "${prefs.openAiName} · ${prefs.openAiModel}"
            // ⚠ 端点不是 https 时说清原因（2026-10-03 修复 L-811）：判据与运行期同源后，
            // `http://` 端点在本页已判「未配置」，若仍只写「点上面的按钮填…」会把用户引去
            // 反复检查已经填好的 Key / 模型名，真因（端点协议）一个字都不提。
            prefs.openAiApiKey.cleanCredential().isNotEmpty() &&
                prefs.openAiModel.cleanCredential().isNotEmpty() -> TEXT_ENDPOINT_NEEDS_HTTPS
            else -> TEXT_OPENAI_UNCONFIGURED
        }
    }

    private fun stateText(configured: Boolean): String =
        if (configured) TEXT_CONFIGURED else TEXT_UNCONFIGURED

    private companion object {
        const val TAG = "TranslationSettings"

        /**
         * 输入停顿多久后落盘（BUG.md L-209）。
         *
         * 取 0.8 秒：足够长到「连续粘贴 / 连续输入」只写一次，又短到聚焦状态下被回收也基本不会
         * 落在窗口里。字段没变时回写被 [CredentialSaveGuard] 挡掉，所以这个值不是写盘次数的上界。
         */
        const val CREDENTIAL_AUTOSAVE_DEBOUNCE_MS = 800L


        const val TEXT_TITLE = "翻译设置"
        const val TEXT_DESC = "使用你申请的 API Key 和相关凭证 \n" +
            "有框选,原文消失,选中的内容直接用译文替换,原文消失 \n" +
            "没框选,原文不变,根据'翻译原文范围'取文本,在下行补充译文 \n" +
            "免费额度：阿里云 100 万字符/月；Azure 200 万字符/月 \n" +
            "百度 机翻 5 万字符/月；百度大模型与 DeepL 一次性 100 万字符"
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
        const val TEXT_TARGET_OPENAI = "输出目标语言（OpenAI 兼容在配置页里设置）"
        const val TEXT_PRIVACY = "上传的原文是哪一段?\n有框选,你框选的那一段内容就是要传的原文(译文覆盖原文) \n" +
            "没框选,按「翻译原文范围」取原文(译文放下一行) \n" +
            "密码类输入框、以及声明「不要个性化学习」的输入框不会翻译。\n" +
            "凭据只存本机，随配置备份加密；界面与日志只显示是否已配置。"
        const val TEXT_CONFIGURED = "已配置"
        const val TEXT_UNCONFIGURED = "未配置：还没填凭据"

        const val TEXT_SAVE = "保存"
        const val TEXT_SAVE_IDLE = "点「保存」时 立即存入本机（不点也会自动保存：输入停顿后、离开本页时）"
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
        const val TEXT_SAVE_INCOMPLETE = "已保存；当前服务方凭据不完整，补齐后才能翻译"
    }
}
