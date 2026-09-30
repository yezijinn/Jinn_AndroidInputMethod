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

/**
 * 翻译设置页（BYOK）：选 Provider、填对应凭据、选目标语言，**改动即落盘**
 * （与模糊音页 / 键盘外观页一致，不设「保存」按钮）。
 *
 * 六家服务各一个凭据区块，只显示当前选中的那家（另一个用途是防串用凭据）；
 * 每个区块底部一行状态摘要，只写「已配置 / 未配置」，不出现任何凭据字符。
 *
 * 生效方式：IME 每次点击「翻译」都从 [Prefs] 现读配置、用 [Prefs.translationProvider] 现组
 * Provider，所以本页改完**不需要重启输入法**，收起键盘再弹出即生效。
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

    /** 用户触摸过才允许写配置：初始化 `setSelection` 也会回调 onItemSelected（与设置页各下拉同款闸门） */
    private var providerTouched = false
    private var targetTouched = false

    private val prefs: Prefs by lazy { Prefs(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 凭据页防**截屏 / 录屏 / 投屏 / 最近任务缩略图**（防泄露增强）：
        // 页面里有掩码后的凭据输入框，FLAG_SECURE 让系统级截取通道拿不到内容
        // （实测：本页前台时 `adb exec-out screencap -p` 输出 **0 字节**）。
        //
        // ⚠ **预期行为，别当 BUG 修**：用 ADB / scrcpy 投屏时，本页在电脑上
        // **整页不可见**，连 Spinner 的下拉弹窗也看不到 —— `PopupWindow` 会继承宿主窗口的 secure 标志，
        // 走的是同一条被屏蔽的通道；手机本机屏幕上一切正常（含下拉）。
        // 这是「凭据页不出现在任何镜像通道上」的应有代价；若要恢复投屏可见，等于放弃这条防护。
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
            text = TEXT_CLOSE
            contentDescription = TEXT_CLOSE_DESC
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

    /**
     * 每次回前台都从 [Prefs] 重载（含备份导入改写过配置的场景）：
     * 重载前必须先复位触摸闸门，否则 `setSelection` 的回调会把 UI 的选中项写回配置。
     */
    override fun onStart() {
        super.onStart()
        loadValues()
        // 打开页面时清一次「历史里与本机凭据相同」的条目（2026-09-30）：覆盖「之前复制过 Key
        // 但没粘进本页」的场景 —— 那条内容仍躺在剪贴板历史里（面板里明文可见、随备份导出）
        purgeClipboardHistory(configuredCredentials())
    }

    /** 本机已配置的全部凭据（**只**用于「不留痕」比对，不参与任何日志与 UI 展示） */
    private fun configuredCredentials(): List<String> = listOf(
        prefs.aliyunAccessKeyId, prefs.aliyunAccessKeySecret,
        prefs.azureApiKey, prefs.baiduAppId, prefs.baiduSecretKey,
        prefs.baiduLlmAppId, prefs.baiduLlmApiKey, prefs.deeplApiKey,
    )

    /**
     * 「凭据不留痕」：用户从密码管理器 / 云控制台复制 API Key 时，
     * 那条内容会被本应用的剪贴板监听采集入库 —— 库里虽是 Keystore 密文，但**剪贴板面板里明文可见**，
     * 且随配置备份整体导出。这里把与凭据内容**完全相同**的条目从历史里删掉（精确匹配，零误伤；
     * 用户手动收藏过的条目不动）。
     *
     * 走 [BackgroundIo]：有 DB 写，不能放主线程（本方法会在 onStart / onPause 被调用）。
     */
    private fun purgeClipboardHistory(values: List<String>) {
        val targets = values.filter { it.isNotBlank() }
        if (targets.isEmpty()) return
        val appContext = applicationContext
        BackgroundIo.run {
            val db = runCatching { ClipboardDb.get(appContext) }.getOrNull() ?: return@run
            var removed = 0
            for (v in targets) removed += runCatching { db.deleteByPlaintext(v) }.getOrDefault(0)
            if (removed > 0) Diagnostics.i(TAG, "凭据不留痕: 从剪贴板历史删除 $removed 条")
        }
    }

    /**
     * 离开页面即落盘，并显式等一次写入（[Prefs.flush]）。
     *
     * 两处真机实测的坑，缺一不可：
     *  1. 只在 EditText 失焦时保存不够 —— 输入完直接按返回时，焦点丢失**不一定**派发
     *     `onFocusChange`，凭据会静默丢掉；
     *  2. 保存必须在 [onPause] 而不是 `onStop` —— 返回设置页时，**下一个页面的 onResume
     *     早于本页的 onStop**，写在 onStop 里会让设置页的摘要读到保存前的值。
     */
    override fun onPause() {
        super.onPause()
        saveCredentials()
        prefs.flush()
    }

    private fun initSpinners() {
        spinnerProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            TranslationProviderId.entries.map { it.label },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerProvider.setOnTouchListener { view, event ->
            providerTouched = true
            // ACTION_UP 补 performClick 供无障碍服务识别（与设置页各下拉一致）
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyProviderVisibility()
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
            this, android.R.layout.simple_spinner_item,
            TranslationLanguage.entries.map { it.label },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerTarget.setOnTouchListener { view, event ->
            targetTouched = true
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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

    /**
     * 凭据输入框失焦即落盘（与剪贴板数量上限同款）：每次键入都写盘没必要，
     * 且会与 IME 侧的现读抢一次 IO。返回/切页由 [onPause] 兜底。
     */
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

    private fun loadValues() {
        // 闸门复位必须在本方法的 setSelection 之前：否则重载会把 UI 选中项当成用户选择写回配置
        providerTouched = false
        targetTouched = false
        spinnerProvider.setSelection(TranslationProviderId.of(prefs.translateProvider).ordinal)
        spinnerTarget.setSelection(TranslationLanguage.of(prefs.translateTarget).ordinal)
        editAliyunKeyId.setText(prefs.aliyunAccessKeyId)
        editAliyunKeySecret.setText(prefs.aliyunAccessKeySecret)
        editAzureKey.setText(prefs.azureApiKey)
        editAzureRegion.setText(prefs.azureRegion)
        editBaiduAppId.setText(prefs.baiduAppId)
        editBaiduSecret.setText(prefs.baiduSecretKey)
        editBaiduLlmAppId.setText(prefs.baiduLlmAppId)
        editBaiduLlmKey.setText(prefs.baiduLlmApiKey)
        editDeeplKey.setText(prefs.deeplApiKey)
        applyProviderVisibility()
        refreshState()
    }

    /**
     * 只显示与当前 Provider 相关的凭据区块（避免用户填错一组），并同步「目标语言」的可用性。
     *
     * **OpenAI 兼容的目标语言是它自己那一套**（在配置页里选，支持 30+ 种）——本页这个下拉对它
     * 无效，因此选中它时禁用下拉、并把原因写进标签：否则用户改完以为生效，实际 IME 用的是
     * 配置页里的值（2026-09-30 审查发现）。
     */
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

    /**
     * 刷新「原文范围」入口的摘要：显示**当前下拉选中那家**正在用的范围与字节上限。
     *
     * 读的是入参而不是 [Prefs.translateProvider]：切换服务方的回调里，触摸闸门
     * （[providerTouched]）没被碰过时不会写配置，摘要就会与用户眼前的选中项不一致。
     */
    private fun refreshSourceState(picked: TranslationProviderId) {
        val scope = TranslationScope.of(prefs.translateScopeOf(picked))
        textTranslateSourceState.text =
            "当前：${scope.label} · 上限 ${prefs.translateMaxBytesOf(picked)} 字节"
    }

    /** 凭据落盘；日志只记长度不记内容（日志会随「导出诊断包」整体外发） */
    private fun saveCredentials() {
        prefs.aliyunAccessKeyId = editAliyunKeyId.text.toString()
        prefs.aliyunAccessKeySecret = editAliyunKeySecret.text.toString()
        prefs.azureApiKey = editAzureKey.text.toString()
        prefs.azureRegion = editAzureRegion.text.toString()
        prefs.baiduAppId = editBaiduAppId.text.toString()
        prefs.baiduSecretKey = editBaiduSecret.text.toString()
        prefs.baiduLlmAppId = editBaiduLlmAppId.text.toString()
        prefs.baiduLlmApiKey = editBaiduLlmKey.text.toString()
        prefs.deeplApiKey = editDeeplKey.text.toString()
        // OpenAI 兼容的一整组配置由 OpenAiSettingsActivity 落盘，这里只记本页那几家的长度
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
        // 刚保存的凭据同样从剪贴板历史里清掉（用户的真实路径就是「复制 → 粘贴到本页 → 保存」）
        purgeClipboardHistory(configuredCredentials())
    }

    /** 状态摘要：只说是否已配置，不回显任何凭据字符 */
    private fun refreshState() {
        textAliyunState.text = stateText(
            prefs.aliyunAccessKeyId.isNotBlank() && prefs.aliyunAccessKeySecret.isNotBlank()
        )
        textAzureState.text = stateText(prefs.azureApiKey.isNotBlank())
        textBaiduState.text = stateText(
            prefs.baiduAppId.isNotBlank() && prefs.baiduSecretKey.isNotBlank()
        )
        textBaiduLlmState.text = stateText(
            prefs.baiduLlmAppId.isNotBlank() && prefs.baiduLlmApiKey.isNotBlank()
        )
        textDeeplState.text = stateText(prefs.deeplApiKey.isNotBlank())
        // OpenAI 兼容：给「配置名 · 模型」这样一眼能认的摘要（Key / Prompt / JSON 一律不回显）。
        // 判据必须与 Prefs.translationProvider() 一致（**含 Base URL 可解析**）：只看 Key 与 Model 时，
        // Base URL 乱填会出现「本页说已配置、设置页说未配置、IME 点翻译说未配置」的三方矛盾
        // （2026-09-30 审查发现）。
        val openAiReady = prefs.openAiApiKey.isNotBlank() &&
            prefs.openAiModel.isNotBlank() &&
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

        // 文案在代码里下发：strings.xml 默认禁改，与本页模板（模糊音页）同做法
        const val TEXT_TITLE = "翻译设置"
        const val TEXT_CLOSE = "X"

        /** 关闭键的可听键名（与模糊音页/生僻字页同值，三页是复刻关系） */
        const val TEXT_CLOSE_DESC = "关闭"
        const val TEXT_DESC = "使用你自带的 API 凭据翻译（BYOK）：输入法不内置任何共享 Key，\n" +
            "免费额度与计费以各平台官方为准（默认服务：阿里云）。\n" +
            "填好后点键盘功能面板的「翻译」，译文追加在原文下一行。"
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

        /** OpenAI 兼容选中时的标签：它的目标语言在配置页里（本页下拉对它无效，见 applyProviderVisibility） */
        const val TEXT_TARGET_OPENAI = "目标语言（OpenAI 兼容在配置页里设置）"
        const val TEXT_PRIVACY = "翻译按「翻译原文范围」里选的取法把正文上传到所选服务商" +
            "（默认只取光标所在这一行里光标前面的内容）；密码等敏感输入框不会翻译。\n" +
            "凭据只存本机，随配置备份一起加密；界面与日志只显示是否已配置。"
        const val TEXT_CONFIGURED = "已配置"
        const val TEXT_UNCONFIGURED = "未配置：还没填凭据"
    }
}
