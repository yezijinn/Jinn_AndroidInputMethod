package com.jinn.inputmethod

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 「语音转为文字」页：设置页里的语音四块（总开关 / 授权麦克风 / 后端与识别选项 / 服务商目录）整体搬到这里。
 *
 * 为什么单独一页：语音是**作者自用的可选链路**（自建 CapsWriter 服务端，或自带凭据的讯飞在线 /
 * OpenAI 兼容 / 阿里百炼 / 腾讯云 / Azure / 百度 / Deepgram），长期占着设置页首页的四块卡片，
 * 对不用语音的人全是噪声。设置页只留一个入口按钮（2026-11-01 起与「悬浮键盘」同行，状态小字已删）。
 *
 * 行为与搬迁前**逐条一致**：
 *  - 总开关关闭 ⇒ 授权麦克风卡、后端卡与八张后端配置卡整块 `GONE`（语音在 IME 侧完全沉寂）；
 *  - 关闭状态下保存**跳过** host / port / 凭据校验（字段是隐藏的），port 为空回落 [Prefs.DEFAULT_PORT]；
 *  - 识别后端（caps / xf / oai / ali / tencent / azure / baidu / deepgram）决定显示哪一张配置卡
 *    （NAS 地址 / 讯飞凭据 / OpenAI 兼容 / 阿里百炼 / 腾讯云 / Azure / 百度 / Deepgram），八张互斥；
 *  - 语言 / 默认键盘样式 / 后端三个下拉走 [UserAwareSpinner]，「用户没碰过就不覆盖」（`BUG.md` L-821）；
 *  - **改动即保存**（2026-11-10）：编辑框停顿 [AUTOSAVE_DEBOUNCE_MS] 后、离开页面（[onPause]）时
 *    自动写盘，不再要求点保存；「保存并重启输入法」按钮只负责重启进程让设置生效；
 *  - 勾选框（去句尾标点 / 语音文字实时回显 / 固定服务端）保持「勾选即写」；
 *  - 语音设置写盘后要重启输入法进程才生效（[ImeRestart]，与设置页同一套 flush → killProcess），
 *    按钮只承担这件事；
 *  - 「语音测试」是自包含诊断入口（[VoiceSelfTest]）：自己建客户端与采集器跑 3 秒识别，
 *    不碰 IME 的 asr / recorder，总开关关着也能跑（结论里注明）；识别正文直接落进页面输入框。
 *  - 已保存的凭据在未聚焦时只显示「前 3 位 + 掩码」，聚焦才揭示真值；
 *    保存时掩码形态一律折回磁盘原值（[CredentialMask]，绝不把掩码串写回）。
 *
 * 麦克风授权：永久拒绝后系统不再弹窗，按钮改为「前往系统设置」并跳应用详情页。
 * 本页有凭据明文输入 ⇒ 全程 `FLAG_SECURE`（防截屏 / 录屏 / 最近任务预览）。
 * 文案全部在本类的 `TEXT_*` 常量里（`values/strings.xml` 默认禁改）。
 */
class VoiceSettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private lateinit var switchVoiceInput: Switch
    private lateinit var cardMicPermission: View
    private lateinit var cardVoiceBackend: View
    private lateinit var cardVoiceServer: View
    private lateinit var cardXfCredentials: View
    private lateinit var cardOaiCredentials: View
    private lateinit var cardAliCredentials: View
    private lateinit var cardTencentCredentials: View
    private lateinit var cardAzureCredentials: View
    private lateinit var cardBaiduCredentials: View
    private lateinit var cardDeepgramCredentials: View
    private lateinit var spinnerVoiceBackend: UserAwareSpinner
    private lateinit var editXfAppId: EditText
    private lateinit var editXfApiKey: EditText
    private lateinit var editXfApiSecret: EditText
    private lateinit var checkXfShow: CheckBox
    private lateinit var textXfDesc: TextView
    private lateinit var editOaiBase: EditText
    private lateinit var editOaiModel: EditText
    private lateinit var editOaiApiKey: EditText
    private lateinit var checkOaiShow: CheckBox
    private lateinit var textOaiDesc: TextView
    private lateinit var editAliWsUrl: EditText
    private lateinit var editAliModel: EditText
    private lateinit var editAliApiKey: EditText
    private lateinit var checkAliShow: CheckBox
    private lateinit var textAliDesc: TextView
    private lateinit var editTencentSecretId: EditText
    private lateinit var editTencentSecretKey: EditText
    private lateinit var editTencentEngine: EditText
    private lateinit var checkTencentShow: CheckBox
    private lateinit var textTencentDesc: TextView
    private lateinit var editAzureBaseUrl: EditText
    private lateinit var editAzureApiKey: EditText
    private lateinit var editAzureLanguage: EditText
    private lateinit var checkAzureShow: CheckBox
    private lateinit var textAzureDesc: TextView
    private lateinit var editBaiduApiKey: EditText
    private lateinit var editBaiduSecretKey: EditText
    private lateinit var editBaiduDevPid: EditText
    private lateinit var checkBaiduShow: CheckBox
    private lateinit var textBaiduDesc: TextView
    private lateinit var editDeepgramApiKey: EditText
    private lateinit var editDeepgramModel: EditText
    private lateinit var editDeepgramLanguage: EditText
    private lateinit var checkDeepgramShow: CheckBox
    private lateinit var textDeepgramDesc: TextView
    private lateinit var textMicState: TextView
    private lateinit var btnGrant: Button
    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var checkLockServer: CheckBox
    private lateinit var spinnerLanguage: UserAwareSpinner
    private lateinit var spinnerDefaultMode: UserAwareSpinner
    private lateinit var editPrompt: EditText
    private lateinit var checkStrip: CheckBox
    private lateinit var checkComposing: CheckBox
    private lateinit var textSaveHint: TextView
    private lateinit var btnSave: Button
    private lateinit var containerProviders: LinearLayout
    private lateinit var editVoiceTest: EditText
    private lateinit var btnVoiceTest: Button
    private lateinit var textVoiceTestResult: TextView

    /**
     * 正在显示「前 3 位 + 掩码」的凭据框（未聚焦、用户没编辑过）。
     *
     * 保存路径据此判定「沿用磁盘原值」—— 绝不能把掩码串写回磁盘（那会把凭据永久写坏）。
     * 表单里的凭据框全进这个集合（见 [credentialFields]）。
     */
    private val maskedCredentials = mutableSetOf<EditText>()

    /** 正在跑的语音测试（`onDestroy` 必须取消它：否则漏线程与麦克风占用） */
    private var voiceSelfTest: VoiceSelfTest? = null

    /** 有未保存的修改（判据见 [recomputeDirty]；防抖窗口内也算「尚未落盘」） */
    private var dirty = false

    /** 点了「放弃并退出」：所有自动落盘路径一律停手，直到页面销毁 */
    private var discardOnExit = false

    /** [onPause] 的 flush 失败：退出要再问一次，别让没落盘的改动静默消失 */
    private var flushFailed = false

    /** 已进入重启窗口：排队的任务不再写盘；[onPause] 仍补一次落盘，兜住窗口内的新改动 */
    private var restarting = false

    /** 点 ✕ / 返回键退出且确有改动：等 [onPause] 确认落盘后再发「重启后生效」提醒 */
    private var exitNeedsRestartHint = false

    /**
     * [loadPrefs] 时选中的语言下标。
     *
     * 之后下标变了才算「用户换过」（或重建后系统把选择放回来）—— 见 [readLanguage]：
     * 与它相等时沿用 `Prefs` 原值，否则 `indexOf` 退到第 0 项会把导入的未知取值静默改写（`BUG.md` L-821）。
     */
    private var languageAtLoad = 0

    /**
     * 上次落盘（进入页面 / 保存成功）时的控件取值快照：脏 = 当前取值与它不一致。
     *
     * 只能用快照比对，不能「碰过控件就算脏」：`setText` 与实例状态恢复都会触发 `TextWatcher`
     * （旋转时系统把编辑框正文放回来同样触发一次），按「碰过就算」会在什么都没改的旋转后判脏，
     * 且「改一个字再改回原值」永远回不到干净。
     */
    private var savedInputs: VoiceInputs? = null

    /**
     * 折叠状态：分类名 → 该组的标题行与行容器。
     *
     * 点标题只改 [ProviderGroup.content] 的 `visibility`（不重建列表：重建会把滚动位置甩回顶部）。
     */
    private val providerGroups = LinkedHashMap<String, ProviderGroup>()

    /** 当前展开的分类；默认只展开「国内云服务」，重建页面时从 [STATE_EXPANDED_GROUPS] 恢复 */
    private var expandedCategories: MutableSet<String> = mutableSetOf(VoiceProviders.CAT_CN)

    /** 一个可折叠分组的两个视图：标题行（可点）与内容容器（行 + 分隔线都在里面，收起即整块隐藏） */
    private class ProviderGroup(
        val count: Int,
        val title: TextView,
        val content: LinearLayout,
    )

    /** 影响「保存」结果的控件取值（[savedInputs] 的比较对象，data class 才有逐字段相等） */
    private data class VoiceInputs(
        val host: String,
        val port: String,
        val prompt: String,
        val language: String,
        val voiceEnabled: Boolean,
        val backend: String,
        val xfAppId: String,
        val xfApiKey: String,
        val xfApiSecret: String,
        val oaiBase: String,
        val oaiModel: String,
        val oaiApiKey: String,
        val aliWsUrl: String,
        val aliModel: String,
        val aliApiKey: String,
        val tencentSecretId: String,
        val tencentSecretKey: String,
        val tencentEngine: String,
        val azureBaseUrl: String,
        val azureApiKey: String,
        val azureLanguage: String,
        val baiduApiKey: String,
        val baiduSecretKey: String,
        val baiduDevPid: String,
        val deepgramApiKey: String,
        val deepgramModel: String,
        val deepgramLanguage: String,
    )

    /** 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 对表并排下一次，[onStop] 撤掉 */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    /**
     * 麦克风被永久拒绝（拒绝后系统不再弹窗）。
     *
     * 该状态下再 launch 会立刻回调失败、按钮永远没效果 —— 唯一出路是系统应用详情页，
     * 所以按钮文案与动作都改走那里（见 [requestMic]）。
     */
    private var micDeniedForever = false

    /**
     * 本页是否已发起过权限请求。
     *
     * 「已发起但仍未授予」是比 `shouldShowRequestPermissionRationale` 更可靠的
     * 「弹窗不会再出现」判据 —— Android 10 起第二次请求会被系统静默拒绝（连弹窗都没有），
     * 而那个 API 此时仍返回 true，只看它会让按钮继续「点了没反应」。
     */
    private var micRequested = false

    /** Activity Result API 替代已弃用的 requestPermissions */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 授权成功后复位「已请求」：之后若被系统撤销，仍可重新走弹窗
        if (granted) micRequested = false
        // 拒绝且系统不再允许弹窗 → 记下来供按钮切换
        micDeniedForever = !granted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        refreshMicState()
        // 语音测试正在等授权：授权成功后给一句「再点一次」，别让用户对着「未授权」发呆
        if (granted && ::textVoiceTestResult.isInitialized &&
            textVoiceTestResult.text.toString() == TEXT_TEST_NEED_PERMISSION
        ) {
            textVoiceTestResult.text = TEXT_TEST_PERMISSION_OK
        }
    }

    /**
     * 主题应用点：必须早于 `onCreate` 与任何资源解析（放到 `onCreate` 里再 `setTheme` 会先解析一帧再换色）。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 凭据页防截屏 / 录屏 / 最近任务预览（本页有 APPID / APIKey / APISecret 明文）
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_voice_settings)
        prefs = Prefs(this)
        Diagnostics.i(TAG, "onCreate: 语音设置页启动")

        findViewById<TextView>(R.id.text_voice_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_voice_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_voice_close).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            // ✕ 与返回键走同一个确认入口（有未保存改动时不能直接丢）
            setOnClickListener { confirmExit() }
        }
        registerBackHandling()
        findViewById<TextView>(R.id.label_voice_server).text = TEXT_SECTION_SERVER
        findViewById<TextView>(R.id.label_voice_backend).text = TEXT_BACKEND_LABEL
        findViewById<TextView>(R.id.label_voice_providers).text = TEXT_SECTION_PROVIDERS
        findViewById<TextView>(R.id.text_voice_providers_desc).text = TEXT_PROVIDERS_DESC
        // 总开关卡片的标题是用户点名的句子（逐字，含 ASCII 双引号与逗号位置）
        findViewById<TextView>(R.id.text_voice_master_title).text = TEXT_VOICE_MASTER_TITLE
        findViewById<TextView>(R.id.label_voice_test).text = TEXT_SECTION_TEST

        textSaveHint = findViewById(R.id.text_voice_save_hint)
        // 保存结果会变（正在重启 / 落盘失败），读屏要能听见
        textSaveHint.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        textSaveHint.text = TEXT_SAVE_HINT_IDLE
        btnSave = findViewById<Button>(R.id.btn_voice_save).apply {
            text = TEXT_SAVE
            setOnClickListener { saveAndRestart() }
        }

        switchVoiceInput = findViewById(R.id.switch_voice_input)
        cardMicPermission = findViewById(R.id.card_mic_permission)
        cardVoiceBackend = findViewById(R.id.card_voice_backend)
        cardVoiceServer = findViewById(R.id.card_voice_server)
        cardXfCredentials = findViewById(R.id.card_xf_credentials)
        cardOaiCredentials = findViewById(R.id.card_oai_credentials)
        cardAliCredentials = findViewById(R.id.card_ali_credentials)
        cardTencentCredentials = findViewById(R.id.card_tencent_credentials)
        cardAzureCredentials = findViewById(R.id.card_azure_credentials)
        cardBaiduCredentials = findViewById(R.id.card_baidu_credentials)
        cardDeepgramCredentials = findViewById(R.id.card_deepgram_credentials)
        spinnerVoiceBackend = findViewById(R.id.spinner_voice_backend)
        editXfAppId = findViewById(R.id.edit_xf_app_id)
        editXfApiKey = findViewById(R.id.edit_xf_api_key)
        editXfApiSecret = findViewById(R.id.edit_xf_api_secret)
        checkXfShow = findViewById(R.id.check_xf_show)
        textXfDesc = findViewById(R.id.text_xf_desc)
        editOaiBase = findViewById(R.id.edit_oai_base)
        editOaiModel = findViewById(R.id.edit_oai_model)
        editOaiApiKey = findViewById(R.id.edit_oai_api_key)
        checkOaiShow = findViewById(R.id.check_oai_show)
        textOaiDesc = findViewById(R.id.text_oai_desc)
        editAliWsUrl = findViewById(R.id.edit_ali_ws_url)
        editAliModel = findViewById(R.id.edit_ali_model)
        editAliApiKey = findViewById(R.id.edit_ali_api_key)
        checkAliShow = findViewById(R.id.check_ali_show)
        textAliDesc = findViewById(R.id.text_ali_desc)
        editTencentSecretId = findViewById(R.id.edit_tencent_secret_id)
        editTencentSecretKey = findViewById(R.id.edit_tencent_secret_key)
        editTencentEngine = findViewById(R.id.edit_tencent_engine)
        checkTencentShow = findViewById(R.id.check_tencent_show)
        textTencentDesc = findViewById(R.id.text_tencent_desc)
        editAzureBaseUrl = findViewById(R.id.edit_azure_base_url)
        editAzureApiKey = findViewById(R.id.edit_azure_api_key)
        editAzureLanguage = findViewById(R.id.edit_azure_language)
        checkAzureShow = findViewById(R.id.check_azure_show)
        textAzureDesc = findViewById(R.id.text_azure_desc)
        editBaiduApiKey = findViewById(R.id.edit_baidu_api_key)
        editBaiduSecretKey = findViewById(R.id.edit_baidu_secret_key)
        editBaiduDevPid = findViewById(R.id.edit_baidu_dev_pid)
        checkBaiduShow = findViewById(R.id.check_baidu_show)
        textBaiduDesc = findViewById(R.id.text_baidu_desc)
        editDeepgramApiKey = findViewById(R.id.edit_deepgram_api_key)
        editDeepgramModel = findViewById(R.id.edit_deepgram_model)
        editDeepgramLanguage = findViewById(R.id.edit_deepgram_language)
        checkDeepgramShow = findViewById(R.id.check_deepgram_show)
        textDeepgramDesc = findViewById(R.id.text_deepgram_desc)
        textMicState = findViewById(R.id.text_mic_state)
        btnGrant = findViewById(R.id.btn_grant)
        editHost = findViewById(R.id.edit_host)
        editPort = findViewById(R.id.edit_port)
        checkLockServer = findViewById(R.id.check_lock_server)
        spinnerLanguage = findViewById(R.id.spinner_language)
        spinnerDefaultMode = findViewById(R.id.spinner_default_mode)
        editPrompt = findViewById(R.id.edit_prompt)
        checkStrip = findViewById(R.id.check_strip)
        checkComposing = findViewById(R.id.check_composing)
        containerProviders = findViewById(R.id.container_providers)

        // 语音测试：结果直接落进多行输入框（用户能在同一个位置看到有没有效果）
        editVoiceTest = findViewById(R.id.edit_voice_test)
        btnVoiceTest = findViewById(R.id.btn_voice_test)
        textVoiceTestResult = findViewById(R.id.text_voice_test_result)
        editVoiceTest.hint = TEXT_TEST_HINT
        btnVoiceTest.text = TEXT_TEST_BUTTON
        textVoiceTestResult.text = TEXT_TEST_IDLE
        // 结论会变（测试中… → 成功 / 失败），读屏要能听见
        textVoiceTestResult.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        btnVoiceTest.setOnClickListener { startVoiceTest() }

        spinnerLanguage.adapter = ArrayAdapter.createFromResource(
            this, R.array.language_entries, R.layout.item_spinner,
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerDefaultMode.adapter = ArrayAdapter.createFromResource(
            this, R.array.default_mode_entries, R.layout.item_spinner,
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerDefaultMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                // 初始化 setSelection（以及恢复实例状态）都会回调这里。若此时 prefs 里的值不在
                // values 中（如配置损坏或新增了模式），indexOf 会退回第 0 项，
                // 未加保护就会把用户的默认键盘静默改成第 0 项。
                // 闸门见 [UserAwareSpinner]（BUG.md L-821）：触摸 / 确认键 / 读屏三条路径都算用户操作。
                if (!spinnerDefaultMode.userInteracted) return
                val values = resources.getStringArray(R.array.default_mode_values)
                val mode = values.getOrNull(position)?.toIntOrNull()
                    ?: DefaultKeyboardMode.VOICE
                if (mode != prefs.defaultKeyboardMode) {
                    prefs.defaultKeyboardMode = mode
                    Diagnostics.i(TAG, "默认键盘模式: $mode")
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        spinnerLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                // 这里**不闸** userInteracted：脏状态按取值快照比对，程序化回填算出来的就是「没变」。
                // 而重建恢复只能走这条路 —— `AbsSpinner.onRestoreInstanceState` 只把位置记进
                // `mSyncPosition` + `requestLayout()`，真正落到选中位置并在**之后第一次 layout**
                // 里回调 `onItemSelected`（AOSP `AbsSpinner` / `Spinner.layout`）。所以在
                // [onRestoreInstanceState] 里重算读到的还是 `loadPrefs` 那个旧下标，
                // 闸掉探针会让「只改语言再旋转」停在干净态：状态行说没改，✕ 直接退。
                // 「不许改写导入值」的闸门留在 [readLanguage]（BUG.md L-821）。
                refreshDirty()
                scheduleAutosave()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 识别后端下拉：标签是纯数据（不进 strings.xml），选中即切八张配置卡的显隐。
        // 同样不闸 userInteracted —— 脏状态按取值快照比对，程序化回填算出来就是「没变」；
        // 重建后系统在第一次 layout 才把位置放回来，闸掉会让「只换后端再旋转」停在干净态。
        spinnerVoiceBackend.adapter = ArrayAdapter(
            this, R.layout.item_spinner, BACKEND_LABELS.toList(),
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerVoiceBackend.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                applyBackendVisibility()
                refreshDirty()
                scheduleAutosave()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 凭据输入框的标签用 hint 下发（values/strings.xml 默认禁改）
        textXfDesc.text = if (prefs.hasUnreadableXfCredential) {
            // 磁盘上明明有密文、本机却解不开（软件密钥丢失 / 密文损坏）：不说清的话
            // 用户只看到三个空框，会以为凭据被偷了或从没存过（用户第 4 条的同一件事）
            TEXT_XF_DESC + TEXT_XF_UNREADABLE
        } else {
            TEXT_XF_DESC
        }
        editXfAppId.hint = TEXT_XF_APP_ID
        editXfApiKey.hint = TEXT_XF_API_KEY
        editXfApiSecret.hint = TEXT_XF_API_SECRET
        // 显示 / 隐藏只切两个密码框（APPID 不是秘密，本来就明文）；属于显示态，不进脏状态。
        // 勾选 = 主动要看真值（三个框全部揭示）；取消 = 未聚焦的框回到「前 3 位 + 掩码」。
        checkXfShow.text = TEXT_XF_SHOW
        checkXfShow.setOnCheckedChangeListener { _, show ->
            if (show) revealCredentials() else maskCredentials()
            applyCredentialVisibility(revealAll())
        }
        // OpenAI 兼容卡同理（Key 是密码型；Base URL / 模型名不是秘密，本来就明文）。
        // 解不开的 Key 与讯飞那张卡同一句话：不说清的话用户只看到一个空框，会以为从没填过。
        textOaiDesc.text = if (prefs.hasUnreadableOaiCredential) {
            TEXT_OAI_DESC + TEXT_XF_UNREADABLE
        } else {
            TEXT_OAI_DESC
        }
        editOaiBase.hint = TEXT_OAI_BASE
        editOaiModel.hint = TEXT_OAI_MODEL
        editOaiApiKey.hint = TEXT_OAI_API_KEY
        checkOaiShow.text = TEXT_OAI_SHOW
        checkOaiShow.setOnCheckedChangeListener { _, show ->
            if (show) revealCredentials() else maskCredentials()
            applyCredentialVisibility(revealAll())
        }
        // 阿里云百炼卡同理（Key 是密码型；WS 地址 / 模型名不是秘密，本来就明文）。
        // 解不开的 Key 与另两张卡同一句话：不说清的话用户只看到一个空框，会以为从没填过。
        textAliDesc.text = if (prefs.hasUnreadableAliCredential) {
            TEXT_ALI_DESC + TEXT_XF_UNREADABLE
        } else {
            TEXT_ALI_DESC
        }
        editAliWsUrl.hint = TEXT_ALI_WS_URL
        editAliModel.hint = TEXT_ALI_MODEL
        editAliApiKey.hint = TEXT_ALI_API_KEY
        checkAliShow.text = TEXT_ALI_SHOW
        checkAliShow.setOnCheckedChangeListener { _, show ->
            if (show) revealCredentials() else maskCredentials()
            applyCredentialVisibility(revealAll())
        }
        // 腾讯云卡同理（SecretKey 是密码型；SecretId / 引擎型号不是秘密，本来就明文）。
        // 解不开的凭据与另三张卡同一句话：不说清的话用户只看到空框，会以为从没填过。
        textTencentDesc.text = if (prefs.hasUnreadableTencentCredential) {
            TEXT_TENCENT_DESC + TEXT_XF_UNREADABLE
        } else {
            TEXT_TENCENT_DESC
        }
        editTencentSecretId.hint = TEXT_TENCENT_SECRET_ID
        editTencentSecretKey.hint = TEXT_TENCENT_SECRET_KEY
        editTencentEngine.hint = TEXT_TENCENT_ENGINE
        checkTencentShow.text = TEXT_TENCENT_SHOW
        checkTencentShow.setOnCheckedChangeListener { _, show ->
            if (show) revealCredentials() else maskCredentials()
            applyCredentialVisibility(revealAll())
        }
        // Azure 卡同理（订阅密钥是密码型；Base URL / 语言码不是秘密）。
        textAzureDesc.text = if (prefs.hasUnreadableAzureCredential) {
            TEXT_AZURE_DESC + TEXT_XF_UNREADABLE
        } else {
            TEXT_AZURE_DESC
        }
        editAzureBaseUrl.hint = TEXT_AZURE_BASE_URL
        editAzureApiKey.hint = TEXT_AZURE_KEY
        editAzureLanguage.hint = TEXT_AZURE_LANGUAGE
        checkAzureShow.text = TEXT_AZURE_SHOW
        checkAzureShow.setOnCheckedChangeListener { _, show ->
            if (show) revealCredentials() else maskCredentials()
            applyCredentialVisibility(revealAll())
        }
        // 百度短语音识别卡：两件凭据都按掩码回显（与讯飞 APPID 同款 —— AK 也是账号标识），
        // dev_pid 是普通数字配置。解不开的凭据与另几张卡同一句话。
        textBaiduDesc.text = if (prefs.hasUnreadableBaiduCredential) {
            TEXT_BAIDU_DESC + TEXT_XF_UNREADABLE
        } else {
            TEXT_BAIDU_DESC
        }
        editBaiduApiKey.hint = TEXT_BAIDU_API_KEY
        editBaiduSecretKey.hint = TEXT_BAIDU_SECRET_KEY
        editBaiduDevPid.hint = TEXT_BAIDU_DEV_PID
        checkBaiduShow.text = TEXT_BAIDU_SHOW
        checkBaiduShow.setOnCheckedChangeListener { _, show ->
            if (show) revealCredentials() else maskCredentials()
            applyCredentialVisibility(revealAll())
        }
        // Deepgram 卡：Key 是密码型；模型名 / 语言码是明文配置。
        textDeepgramDesc.text = if (prefs.hasUnreadableDeepgramCredential) {
            TEXT_DEEPGRAM_DESC + TEXT_XF_UNREADABLE
        } else {
            TEXT_DEEPGRAM_DESC
        }
        editDeepgramApiKey.hint = TEXT_DEEPGRAM_API_KEY
        editDeepgramModel.hint = TEXT_DEEPGRAM_MODEL
        editDeepgramLanguage.hint = TEXT_DEEPGRAM_LANGUAGE
        checkDeepgramShow.text = TEXT_DEEPGRAM_SHOW
        checkDeepgramShow.setOnCheckedChangeListener { _, show ->
            if (show) revealCredentials() else maskCredentials()
            applyCredentialVisibility(revealAll())
        }

        loadPrefs()
        // 掩码 / 焦点接线必须在 loadPrefs 之后：回填时才有真值可掩，程序化赋值也不会触发焦点回调
        wireCredentialMask(editXfAppId) { prefs.xfAppId }
        wireCredentialMask(editXfApiKey) { prefs.xfApiKey }
        wireCredentialMask(editXfApiSecret) { prefs.xfApiSecret }
        wireCredentialMask(editOaiApiKey) { prefs.oaiApiKey }
        wireCredentialMask(editAliApiKey) { prefs.aliApiKey }
        wireCredentialMask(editTencentSecretId) { prefs.tencentSecretId }
        wireCredentialMask(editTencentSecretKey) { prefs.tencentSecretKey }
        wireCredentialMask(editAzureApiKey) { prefs.azureSpeechKey }
        wireCredentialMask(editBaiduApiKey) { prefs.baiduSpeechApiKey }
        wireCredentialMask(editBaiduSecretKey) { prefs.baiduSpeechSecretKey }
        wireCredentialMask(editDeepgramApiKey) { prefs.deepgramApiKey }
        applyCredentialVisibility(revealAll())
        // 脏状态只认「要按保存才写」的输入：编辑框走 afterTextChanged（loadPrefs 之后挂，
        // 回填不算用户改动）；勾选框与默认键盘样式是即时写盘，不进来
        markDirtyOnEdit(editHost)
        markDirtyOnEdit(editPort)
        markDirtyOnEdit(editPrompt)
        // 讯飞三件凭据同样按保存才写（凭据是敏感输入，不做即时落盘）
        markDirtyOnEdit(editXfAppId)
        markDirtyOnEdit(editXfApiKey)
        markDirtyOnEdit(editXfApiSecret)
        // OpenAI 兼容：地址 / 模型名 / Key 同样按保存才写
        markDirtyOnEdit(editOaiBase)
        markDirtyOnEdit(editOaiModel)
        markDirtyOnEdit(editOaiApiKey)
        // 阿里云百炼：WS 地址 / 模型名 / Key 同样按保存才写
        markDirtyOnEdit(editAliWsUrl)
        markDirtyOnEdit(editAliModel)
        markDirtyOnEdit(editAliApiKey)
        // 腾讯云：两件凭据 + 引擎型号；Azure：Base URL / 语言码 / 订阅密钥
        markDirtyOnEdit(editTencentSecretId)
        markDirtyOnEdit(editTencentSecretKey)
        markDirtyOnEdit(editTencentEngine)
        markDirtyOnEdit(editAzureBaseUrl)
        markDirtyOnEdit(editAzureApiKey)
        markDirtyOnEdit(editAzureLanguage)
        // 百度：两件凭据 + dev_pid；Deepgram：Key + 模型名 + 语言码
        markDirtyOnEdit(editBaiduApiKey)
        markDirtyOnEdit(editBaiduSecretKey)
        markDirtyOnEdit(editBaiduDevPid)
        markDirtyOnEdit(editDeepgramApiKey)
        markDirtyOnEdit(editDeepgramModel)
        markDirtyOnEdit(editDeepgramLanguage)

        // 总开关：关闭时两张卡整块隐藏（与搬迁前 applyVoiceBlocksVisibility 一致）
        switchVoiceInput.isChecked = prefs.voiceInputEnabled
        applyVoiceBlocksVisibility(prefs.voiceInputEnabled)
        switchVoiceInput.setOnCheckedChangeListener { _, checked ->
            applyVoiceBlocksVisibility(checked)
            // 改动即保存（状态行只是反馈），快照比对见 recomputeDirty
            refreshDirty()
            scheduleAutosave()
        }

        // 识别选项即时保存：勾选即写入，下次识别立即生效
        checkStrip.setOnCheckedChangeListener { _, checked ->
            prefs.stripTrailingPunc = checked
        }
        checkComposing.setOnCheckedChangeListener { _, checked ->
            prefs.useComposing = checked
        }
        // 固定 NAS 地址/端口：勾选后编辑框变灰不可编辑
        checkLockServer.setOnCheckedChangeListener { _, checked ->
            prefs.lockServer = checked
            applyServerLock()
        }

        btnGrant.setOnClickListener { requestMic() }

        renderProviders()
        restoreExpandedGroups(savedInstanceState)

        // 基线取「所有回填都做完之后」的取值：开关 / 下拉是程序化赋值（在监听器挂上之前），
        // 放在这里才与 loadPrefs 的落盘取值逐项相等 —— 刚进页面必须是干净的
        savedInputs = currentInputs()
        refreshDirty()
    }

    /**
     * 折叠状态进 Bundle（`StringArray`）：旋转 / 进程回收重建后回到用户展开的那几组。
     *
     * 用分类名而不是下标：`CATEGORIES` 增删分类时下标会错位到别的组。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_EXPANDED_GROUPS, expandedCategories.toTypedArray())
    }

    /**
     * 重建（旋转 / 进程回收）后系统把各控件取值放回来：编辑框正文、总开关都在 `super` 里同步恢复，
     * **下拉的选中位置不是** —— `AbsSpinner.onRestoreInstanceState` 只记 `mSyncPosition` 并
     * `requestLayout()`，位置与回调都在之后第一次 layout 落地（那条路走
     * [spinnerLanguage] 的选中回调，那里也重算一次）。
     *
     * 恢复完必须重算脏状态 —— `dirty` 不进 Bundle，而界面值可能已经不等于上次落盘的值
     * （典型：改了提示词再旋转）。不算一次的话状态行说「干净」、按 ✕ 也不弹确认，
     * 那批改动静默丢失。
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        refreshDirty()
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /** 从系统设置授权麦克风后返回：页面不能还显示「未授权」；顺便撤回已失效的未落盘提示 */
    override fun onResume() {
        super.onResume()
        refreshMicState()
        // 解锁后凭据加密恢复、且没有新的未落盘项：把「未写入本机」提示撤回到真实状态
        if (prefs.unpersistedCredentialKeys().isEmpty()) updateSaveHint()
    }

    private fun loadPrefs() {
        editHost.setText(prefs.host)
        editPort.setText(prefs.port.toString())
        editPrompt.setText(prefs.prompt)
        checkStrip.isChecked = prefs.stripTrailingPunc
        checkComposing.isChecked = prefs.useComposing
        checkLockServer.isChecked = prefs.lockServer
        applyServerLock()
        // 后端选择：取值已由 Prefs 归一到六个合法值，indexOf 不会退到 -1
        spinnerVoiceBackend.setSelection(
            BACKEND_VALUES.indexOf(prefs.voiceBackend).coerceAtLeast(0),
        )
        // 三件凭据回填（走 readCredential：解不开等价「未配置」，不会把密文当明文显示）。
        // 已配置的只露前 3 位 + 掩码：用户看到「已经填过」，而不是以为忘了填（用户第 4 条要求）
        setCredentialText(editXfAppId, prefs.xfAppId)
        setCredentialText(editXfApiKey, prefs.xfApiKey)
        setCredentialText(editXfApiSecret, prefs.xfApiSecret)
        // OpenAI 兼容：地址与模型名是明文配置，Key 与讯飞同一套掩码回显
        editOaiBase.setText(prefs.oaiBaseUrl)
        editOaiModel.setText(prefs.oaiModel)
        setCredentialText(editOaiApiKey, prefs.oaiApiKey)
        // 阿里云百炼：WS 地址与模型名是明文配置，Key 与前两家同一套掩码回显
        editAliWsUrl.setText(prefs.aliWsUrl)
        editAliModel.setText(prefs.aliModel)
        setCredentialText(editAliApiKey, prefs.aliApiKey)
        // 腾讯云：SecretId / SecretKey 与另三家同一套掩码回显，引擎型号是明文配置
        setCredentialText(editTencentSecretId, prefs.tencentSecretId)
        setCredentialText(editTencentSecretKey, prefs.tencentSecretKey)
        editTencentEngine.setText(prefs.tencentEngine)
        // Azure：地址与语言码是明文配置，订阅密钥同一套掩码回显
        editAzureBaseUrl.setText(prefs.azureBaseUrl)
        setCredentialText(editAzureApiKey, prefs.azureSpeechKey)
        editAzureLanguage.setText(prefs.azureLanguage)
        // 百度：两件凭据走掩码回显，dev_pid 是明文数字配置
        setCredentialText(editBaiduApiKey, prefs.baiduSpeechApiKey)
        setCredentialText(editBaiduSecretKey, prefs.baiduSpeechSecretKey)
        editBaiduDevPid.setText(prefs.baiduDevPid.toString())
        // Deepgram：Key 走掩码回显，模型名与语言码是明文配置
        setCredentialText(editDeepgramApiKey, prefs.deepgramApiKey)
        editDeepgramModel.setText(prefs.deepgramModel)
        editDeepgramLanguage.setText(prefs.deepgramLanguage)
        applyBackendVisibility()
        val values = resources.getStringArray(R.array.language_values)
        languageAtLoad = values.indexOf(prefs.language).coerceAtLeast(0)
        spinnerLanguage.setSelection(languageAtLoad)
        val modeValues = resources.getStringArray(R.array.default_mode_values)
        spinnerDefaultMode.setSelection(
            modeValues.indexOf(prefs.defaultKeyboardMode.toString()).coerceAtLeast(0),
        )
    }

    private fun applyVoiceBlocksVisibility(voiceEnabled: Boolean) {
        val visibility = if (voiceEnabled) View.VISIBLE else View.GONE
        cardMicPermission.visibility = visibility
        cardVoiceBackend.visibility = visibility
        applyBackendVisibility()
    }

    /**
     * 按后端切八张配置卡：自建服务端显示 NAS 地址卡；讯飞在线显示三件凭据卡；
     * OpenAI 兼容显示 Base URL / 模型名 / Key 卡；阿里云百炼显示 WS 地址 / 模型名 / Key 卡；
     * 腾讯云显示 SecretId / SecretKey / 引擎卡；Azure 显示 Base URL / 语言 / 密钥卡；
     * 百度显示 API Key / Secret Key / dev_pid 卡；Deepgram 显示 Key / 模型 / 语言卡。八张互斥。
     *
     * 总开关关闭时全部藏（`enabled=false` 优先于后端选择）：字段不可见时保存也不校验
     * 它们（见 [saveAndRestart]），避免「关掉语音就再也存不了」。
     */
    private fun applyBackendVisibility() {
        val enabled = switchVoiceInput.isChecked
        val backend = readBackend()
        // 每张卡自己的「显示」勾选只对那张卡生效：[revealAll] 是「任一勾选 = 全部揭示」的合成判据
        // （八张卡的凭据框共用一套掩码 / 密码转换），不复位的话「在 A 家勾了显示 → 切到 B 家」
        // 会让 B 家的密钥以**明文**摆在那里（勾选框明明没勾）—— 掩码的全部意义就在这一步。
        for ((id, box) in revealChecks()) {
            if (id != backend && box.isChecked) box.setChecked(false)
        }
        cardVoiceServer.visibility = if (enabled && backend == VoiceBackend.CAPS) View.VISIBLE else View.GONE
        cardXfCredentials.visibility = if (enabled && backend == VoiceBackend.XF) View.VISIBLE else View.GONE
        cardOaiCredentials.visibility = if (enabled && backend == VoiceBackend.OAI) View.VISIBLE else View.GONE
        cardAliCredentials.visibility = if (enabled && backend == VoiceBackend.ALI) View.VISIBLE else View.GONE
        cardTencentCredentials.visibility = if (enabled && backend == VoiceBackend.TENCENT) View.VISIBLE else View.GONE
        cardAzureCredentials.visibility = if (enabled && backend == VoiceBackend.AZURE) View.VISIBLE else View.GONE
        cardBaiduCredentials.visibility = if (enabled && backend == VoiceBackend.BAIDU) View.VISIBLE else View.GONE
        cardDeepgramCredentials.visibility = if (enabled && backend == VoiceBackend.DEEPGRAM) View.VISIBLE else View.GONE
    }

    /** 下拉当前选中的后端；下标异常时回落配置里的值（导入的未知取值不被静默改写） */
    private fun readBackend(): String =
        BACKEND_VALUES.getOrElse(spinnerVoiceBackend.selectedItemPosition) { prefs.voiceBackend }

    /**
     * 显示 / 隐藏 APIKey / APISecret / 各家订阅密钥（APPID、URL、模型名不是秘密，本来就明文）。
     *
     * 用 `transformationMethod` 而不是改 `inputType`：后者会就地重建按键监听与滤镜，
     * 长串凭据在部分机型上被重新过滤一次（粘贴进去的值与显示值不再一致），
     * 而这里要的只是「看得见 / 看不见」。
     *
     * 掩码形态（`b86••••••••`）**永远不加密码转换**：它本来就只露前 3 位，
     * 再盖一层密码点就变成一整排点，用户看不出「已经填过」（用户第 4 条要求）。
     *
     * **正在编辑的那个框同样不加**：用户点进去就是要核对 / 改字符，盖成点等于「聚焦揭示」没做
     * （2026-11-01 Stage 3 真机复核发现；用户第 4 条 + 任务书 C 段「聚焦显示真值供编辑」）。
     */
    private fun applyCredentialVisibility(show: Boolean) {
        for (field in listOf(
            editXfApiKey, editXfApiSecret, editOaiApiKey, editAliApiKey,
            editTencentSecretKey, editAzureApiKey, editBaiduSecretKey, editDeepgramApiKey,
        )) {
            // 正在编辑的那个框必须给真值：掩码换真值之后 `isMaskedDisplay` 立刻变 false，
            // 只判这两个条件的话密码转换会重新装上去，用户看到的是一整排点 ——
            // 更糟的是点数 = 真实长度，把 [CredentialMask]「固定 8 点不泄露长度」也一起破了
            // （2026-11-01 真机实测：16 位 Key 聚焦后显示 19 个点）。FLAG_SECURE 已在窗口上。
            val revealed = show || field.hasFocus() || isMaskedDisplay(field)
            val method = if (revealed) null else PasswordTransformationMethod.getInstance()
            field.transformationMethod = method
            // 切换后光标落到末尾：否则明文下光标还停在掩码时期的旧位置
            if (show) field.setSelection(field.text.length)
        }
    }

    // ── 凭据回显：前 3 位 + 掩码（2026-11-01） ──────────────────────

    /** 凭据框清单（回填 / 揭示 / 掩码三条路径共用一份清单：讯飞三件 + OpenAI Key + 阿里 Key + 腾讯两件 + Azure Key + 百度两件 + Deepgram Key） */
    private fun credentialFields(): List<EditText> = listOf(
        editXfAppId, editXfApiKey, editXfApiSecret, editOaiApiKey, editAliApiKey,
        editTencentSecretId, editTencentSecretKey, editAzureApiKey,
        editBaiduApiKey, editBaiduSecretKey, editDeepgramApiKey,
    )

    /** 该框对应的磁盘取值（走 readCredential：解不开等价「未配置」） */
    private fun diskCredentialOf(field: EditText): String = when {
        field === editXfAppId -> prefs.xfAppId
        field === editXfApiKey -> prefs.xfApiKey
        field === editXfApiSecret -> prefs.xfApiSecret
        field === editOaiApiKey -> prefs.oaiApiKey
        field === editAliApiKey -> prefs.aliApiKey
        field === editTencentSecretId -> prefs.tencentSecretId
        field === editTencentSecretKey -> prefs.tencentSecretKey
        field === editAzureApiKey -> prefs.azureSpeechKey
        field === editBaiduApiKey -> prefs.baiduSpeechApiKey
        field === editBaiduSecretKey -> prefs.baiduSpeechSecretKey
        field === editDeepgramApiKey -> prefs.deepgramApiKey
        else -> ""
    }

    /**
     * 「显示」勾选状态：每张凭据卡各有自己的勾选框，但同一时刻只有一张卡可见，
     * 故合成一个判据（任一勾选 = 全部揭示），掩码 / 密码转换都用它。
     *
     * ⚠ 合成判据的前提是「别的卡不可能还勾着」—— 由 [applyBackendVisibility] 在切卡时复位
     * （见 [revealChecks]）。少了那一步，A 家勾了显示就让 B 家的密钥也明文可见。
     */
    private fun revealAll(): Boolean = checkXfShow.isChecked || checkOaiShow.isChecked || checkAliShow.isChecked ||
        checkTencentShow.isChecked || checkAzureShow.isChecked || checkBaiduShow.isChecked ||
        checkDeepgramShow.isChecked

    /** 「显示」勾选框 → 所属后端（切卡时据此复位：见 [applyBackendVisibility] 里的说明） */
    private fun revealChecks(): List<Pair<String, CheckBox>> = listOf(
        VoiceBackend.XF to checkXfShow,
        VoiceBackend.OAI to checkOaiShow,
        VoiceBackend.ALI to checkAliShow,
        VoiceBackend.TENCENT to checkTencentShow,
        VoiceBackend.AZURE to checkAzureShow,
        VoiceBackend.BAIDU to checkBaiduShow,
        VoiceBackend.DEEPGRAM to checkDeepgramShow,
    )

    /** 回填：有值就只显示前 3 位 + 掩码，并记进 [maskedCredentials] */
    private fun setCredentialText(field: EditText, value: String) {
        if (value.isEmpty()) {
            maskedCredentials.remove(field)
            field.setText("")
            return
        }
        maskedCredentials.add(field)
        field.setText(CredentialMask.of(value))
    }

    /** 该框当前是否以掩码形态显示（集合成员，或文本本身就是掩码串 —— 见 [credentialText]） */
    private fun isMaskedDisplay(field: EditText): Boolean =
        field in maskedCredentials || CredentialMask.looksMasked(field.text.toString())

    /**
     * 聚焦 → 揭示真值供编辑；失焦且没改动 → 回到掩码。
     *
     * 「没改动」按**与磁盘取值相等**判：只有原样未改才回掩码，用户改过的内容原样留着。
     */
    private fun wireCredentialMask(field: EditText, disk: () -> String) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (maskedCredentials.remove(field)) field.setText(disk())
                field.setSelection(field.text.length)
            } else {
                val real = disk()
                // 勾选「显示」时保持揭示（用户明确要求看真值），失焦也不回掩码
                if (!revealAll() && real.isNotEmpty() && field.text.toString() == real) {
                    maskedCredentials.add(field)
                    field.setText(CredentialMask.of(real))
                }
            }
            applyCredentialVisibility(revealAll())
        }
    }

    /** 「显示」勾选：所有凭据框全部揭示真值 */
    private fun revealCredentials() {
        for (field in credentialFields()) {
            // 掩码形态（集合成员，或输入框里就是掩码串 —— 实例状态恢复后的形态）才换成真值
            val masked = maskedCredentials.remove(field) ||
                CredentialMask.looksMasked(field.text.toString())
            if (masked) field.setText(diskCredentialOf(field))
        }
    }

    /** 取消勾选：未聚焦的、且没改动过的框回到掩码（正在编辑的那个不动，否则光标会被打断） */
    private fun maskCredentials() {
        for (field in credentialFields()) {
            val real = diskCredentialOf(field)
            if (real.isEmpty() || field.hasFocus()) continue
            val text = field.text.toString()
            // 只折「原样未改」或「本来就是掩码」的框：用户改过的内容绝不能被掩码覆盖掉
            // （覆盖 = 静默丢失这次编辑，且保存时又会折回磁盘旧值）
            if (text != real && !CredentialMask.looksMasked(text)) continue
            maskedCredentials.add(field)
            field.setText(CredentialMask.of(real))
        }
    }

    /**
     * 保存时该框的有效取值。
     *
     * **掩码形态 ⇒ 沿用磁盘原值**：这是硬性错误防线 —— 把 `b86••••••••` 当成用户输入写回，
     * 凭据会永久损坏（再解密得到的是掩码串本身）。判据取两道：集合成员 + 文本含掩码字符
     * （后者兜住「实例状态恢复把掩码串放回输入框、集合却是新实例」这一种）。
     *
     * 空串是合法输入（用户清空了字段 ⇒ 删除该凭据），原样返回。
     */
    private fun credentialText(field: EditText): String {
        if (!isMaskedDisplay(field)) return field.text.toString()
        if (field !in maskedCredentials) {
            Diagnostics.w(TAG, "凭据框里是掩码串，保存时沿用磁盘原值（不写回掩码）")
        }
        return diskCredentialOf(field)
    }

    /**
     * 固定 NAS 地址/端口：勾选后编辑框变灰不可编辑，防误触乱改。解锁（取消勾选）后可正常编辑。
     */
    private fun applyServerLock() {
        val locked = checkLockServer.isChecked
        editHost.isEnabled = !locked
        editPort.isEnabled = !locked
        // 置灰效果：enabled=false 时系统自动降低文字/背景透明度，再配合降低背景 alpha 让灰色更明显
        editHost.alpha = if (locked) 0.5f else 1f
        editPort.alpha = if (locked) 0.5f else 1f
        Diagnostics.i(TAG, "服务器配置固定: $locked")
    }

    /**
     * 重启窗口内冻结表单：进程马上被 `killProcess` 杀掉，窗口里的新编辑无处可靠落盘。
     *
     * 授权按钮与语音测试按钮各有自己的启用态（未授权 / 测试中），不进本函数；「重启输入法」
     * 按钮由 [saveAndRestart] 自己管；✕ 不冻结，退出入口照常可用。恢复时重新压一次 [applyServerLock]，
     * 免得把「固定服务端」锁住的 host / port 误放开。
     */
    private fun setFormEnabled(enabled: Boolean) {
        val edits = credentialFields() + listOf(
            editOaiBase, editOaiModel, editAliWsUrl, editAliModel,
            editTencentEngine, editAzureBaseUrl, editAzureLanguage,
            editBaiduDevPid, editDeepgramModel, editDeepgramLanguage,
            editHost, editPort, editPrompt, editVoiceTest,
        )
        for (field in edits) field.isEnabled = enabled
        for (spinner in listOf(spinnerVoiceBackend, spinnerLanguage, spinnerDefaultMode)) {
            spinner.isEnabled = enabled
        }
        for (box in revealChecks().map { it.second }) box.isEnabled = enabled
        for (box in listOf(switchVoiceInput, checkStrip, checkComposing, checkLockServer)) {
            box.isEnabled = enabled
        }
        if (enabled) applyServerLock()
    }

    /**
     * 用户没碰过就沿用 [Prefs] 的原值：导入的备份可能带本版不认识的取值（更高版本 / 手工构造），
     * 此时 `loadPrefs` 里 indexOf 退回选中第 0 项，读回它等于把导入的语言静默改写
     * （闸门判据见 [UserAwareSpinner]，BUG.md L-821）。
     *
     * 「没碰过」的判据是**下标仍等于载入时那个**，不是只看 `userInteracted`：旋转 / 进程回收后
     * 系统把用户选中的下标放回来，探针仍是 false（程序化恢复不算用户操作），只看探针会把
     * 「改过语言再旋转」的那次改动连同脏标记一起丢掉 —— 界面显示新值、保存写的还是旧值。
     */
    private fun readLanguage(): String {
        val values = resources.getStringArray(R.array.language_values)
        val pos = spinnerLanguage.selectedItemPosition.coerceIn(0, values.lastIndex)
        if (!spinnerLanguage.userInteracted && pos == languageAtLoad) return prefs.language
        return values[pos]
    }

    // ── 麦克风授权 ──────────────────────────────────────────────

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            refreshMicState()
            return
        }
        // 已请求过仍未授予（弹窗不会再出现）或已确认永久拒绝 → 只能去应用详情页手动开
        if (micRequested || micDeniedForever) {
            openAppPermissionSettings()
            return
        }
        micRequested = true
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** 跳系统应用详情页：永久拒绝后唯一能重新授权麦克风的地方 */
    private fun openAppPermissionSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        }.onFailure { Diagnostics.w(TAG, "打开应用详情页失败: ${it.message}") }
    }

    private fun refreshMicState() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // 点过一次仍未授权时按钮改为「前往系统设置」：本页再申请不会弹窗，只能去应用详情页手动开
        val text = getString(
            when {
                granted -> R.string.settings_mic_granted
                micRequested || micDeniedForever -> R.string.settings_mic_open_settings
                else -> R.string.settings_grant_mic
            }
        )
        textMicState.text = text
        btnGrant.text = text
        btnGrant.isEnabled = !granted
    }

    // ── 在线语音服务（API 文档目录） ────────────────────────────

    /**
     * 按 [VoiceProviders.CATEGORIES] 的顺序分组渲染：每个分类一个**可折叠分组**（标题行 + 行容器），
     * 逐条一行「名称 · 特点」+ 右侧「文档」按钮。列表是纯数据，不进 `strings.xml`。
     *
     * 29 条一次铺开会把服务端配置挤到很远，所以默认只展开「国内云服务」，其余收起；
     * 展开 / 收起只切行容器的 `visibility`（见 [toggleGroup]），**不重建列表** ——
     * 重建会把滚动位置甩回顶部。
     */
    private fun renderProviders() {
        containerProviders.removeAllViews()
        providerGroups.clear()
        for (category in VoiceProviders.CATEGORIES) {
            val items = VoiceProviders.ALL.filter { it.category == category }
            if (items.isEmpty()) continue
            // 行与分隔线都挂在组自己的容器里：收起时整块 GONE，分隔线跟着消失（不留一段空线）
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            items.forEachIndexed { index, provider ->
                if (index > 0) content.addView(rowDivider())
                addProviderRow(content, provider)
            }
            val title = providerGroupTitle(category)
            providerGroups[category] = ProviderGroup(items.size, title, content)
            containerProviders.addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            containerProviders.addView(
                content,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            applyGroupState(category)
        }
        Diagnostics.i(TAG, "在线语音服务: ${VoiceProviders.ALL.size} 条 / ${VoiceProviders.CATEGORIES.size} 类")
    }

    /**
     * 分组标题行：与卡片外的 `SectionTitle` 同款（13sp 主色），上下各一档内边距撑到可点高度。
     *
     * 整行可点（读屏把它当按钮读）；文案与 `contentDescription` 都由 [applyGroupState] 下发 ——
     * 那里才能同时知道条数与展开状态。
     */
    private fun providerGroupTitle(category: String): TextView = TextView(this).apply {
        PageStyle.sectionTitle(this)
        setPadding(0, PageStyle.dp(context, 10), 0, PageStyle.dp(context, 10))
        // 13sp 正文 + 上下各 10dp 内边距只有 39~40dp，靠它凑触摸基线不稳定：显式给 40dp 并垂直居中
        minHeight = PageStyle.dp(context, 40)
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { toggleGroup(category) }
    }

    /** 点标题：只切这一组的展开状态并刷新标题文案，**不重建列表**（重建会让滚动位置跳回顶部） */
    private fun toggleGroup(category: String) {
        if (!expandedCategories.remove(category)) expandedCategories.add(category)
        applyGroupState(category)
        // 标题文本与 contentDescription 只是改内容，读屏不会主动重读；显式播报一次状态变化
        providerGroups[category]?.let {
            it.title.announceForAccessibility(groupDesc(category, it.count, category in expandedCategories))
        }
        Diagnostics.i(
            TAG,
            "服务商分组 $category: ${if (category in expandedCategories) "展开" else "收起"}",
        )
    }

    /** 把某一组的展开状态落到视图上：行容器显隐 + 标题的方向标记 / 条数 / 读屏文案 */
    private fun applyGroupState(category: String) {
        val group = providerGroups[category] ?: return
        val expanded = category in expandedCategories
        group.content.visibility = if (expanded) View.VISIBLE else View.GONE
        group.title.text = groupTitleText(category, group.count, expanded)
        group.title.contentDescription = groupDesc(category, group.count, expanded)
    }

    /**
     * 重建后恢复折叠状态：`StringArray` 里存的是分类名（不是下标 —— `CATEGORIES` 增删分类时下标会错位），
     * 认不出的名字直接丢掉。
     */
    private fun restoreExpandedGroups(state: Bundle?) {
        val saved = state?.getStringArray(STATE_EXPANDED_GROUPS) ?: return
        expandedCategories = saved.filter { it in VoiceProviders.CATEGORIES }.toMutableSet()
        for (category in VoiceProviders.CATEGORIES) applyGroupState(category)
    }

    /** 标题文案：方向标记 + 分类名 + 条数，例 `▾ 国内云服务（12）` / `▸ 国际云服务（13）` */
    private fun groupTitleText(category: String, count: Int, expanded: Boolean): String {
        val mark = if (expanded) TEXT_GROUP_EXPANDED else TEXT_GROUP_COLLAPSED
        return mark + " " + category + TEXT_GROUP_COUNT_LEFT + count + TEXT_GROUP_COUNT_RIGHT
    }

    /** 读屏文案：`国内云服务，12 家，已展开` / `…，已收起`（不可见的条目数与状态也要能听见） */
    private fun groupDesc(category: String, count: Int, expanded: Boolean): String {
        val state = if (expanded) TEXT_STATE_EXPANDED else TEXT_STATE_COLLAPSED
        return category + TEXT_DESC_SEP + count + TEXT_STATE_COUNT_UNIT + TEXT_DESC_SEP + state
    }

    /** 行间分隔线（1dp、取卡片描边色）：卡内列表靠它分行，不必逐行再套一层卡 */
    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.card_stroke))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            PageStyle.dp(this@VoiceSettingsActivity, 1),
        )
    }

    /**
     * 追加一行服务商：「名称 · 特点」（过长折到第二行并省略），右侧「文档」按钮打开官方文档。
     *
     * 行是**卡内纯行**，不套 [PageStyle.addCard]：整段列表本来就挂在 `card_voice_providers` 这张卡里，
     * 逐行套卡就是卡套卡（双层描边 + 无意义的内缩），行与行之间用 [rowDivider] 分隔即可。
     * **本函数自己挂到 [parent]（该分类的行容器）上**，调用方不要再 `addView`
     * （同一个 View 挂两次会抛 `child already has a parent`）。
     */
    private fun addProviderRow(parent: LinearLayout, provider: VoiceApiProvider) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 行高由「文档」按钮撑到 40dp 触摸基线；行自己不再加纵向内边距 ——
            // **点击区域是按钮本身**，行内边距点在按钮外不触发任何事，靠它凑 40dp 是假达标
        }
        val label = TextView(this).apply {
            // 名称与特点同一行：一屏能多看几条；长名（CapsWriter Offline（本机协议））折到第二行后省略
            text = provider.name + TEXT_NOTE_SEP + provider.note
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.text_primary))
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            Button(this).apply {
                text = TEXT_DOC
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                // 键高就是本项目 40dp 触摸基线（别改成 36 或 0：这是按钮**自身**的可点区域）
                minHeight = PageStyle.dp(context, 40)
                minimumHeight = PageStyle.dp(context, 40)
                setPadding(PageStyle.dp(context, 10), PageStyle.dp(context, 4), PageStyle.dp(context, 10), PageStyle.dp(context, 4))
                setBackgroundResource(R.drawable.btn_aurora_secondary)
                setTextColor(getColor(R.color.text_primary))
                contentDescription = "$TEXT_DOC：${provider.name}"
                setOnClickListener { openDoc(provider) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = PageStyle.dp(this@VoiceSettingsActivity, 6) },
        )
        parent.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** 打开官方文档；没有可用的浏览器时只留日志 + 一句 toast（绝不静默什么都不发生） */
    private fun openDoc(provider: VoiceApiProvider) {
        val ok = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.docUrl)))
        }.onFailure {
            Diagnostics.w(TAG, "打开文档失败 id=${provider.id} 类=${it.javaClass.simpleName}")
        }.isSuccess
        if (!ok) Toast.makeText(this, TEXT_DOC_FAILED, Toast.LENGTH_SHORT).show()
    }

    // ── 语音测试（自包含，不改动 IME 的语音状态） ────────────────────

    /**
     * 点「语音测试」：一次 3 秒的真实识别 —— 结论写进结论行，识别正文写进多行输入框。
     *
     * 顺序固定为「权限 → 后端可用性 → 真跑」：
     *  - 没授权：走本页已有的授权入口（弹窗 / 永久拒绝后转系统设置），结论如实说「未授权」；
     *  - 后端不齐（讯飞缺凭据 / 自建地址非法）：结论直接点名缺什么，**不发请求**；
     *  - 总开关关着也照跑：测试是诊断手段，结论里注明「当前总开关关闭」。
     */
    private fun startVoiceTest() {
        if (!btnVoiceTest.isEnabled) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            textVoiceTestResult.text = TEXT_TEST_NEED_PERMISSION
            requestMic()
            return
        }
        btnVoiceTest.isEnabled = false
        textVoiceTestResult.text = TEXT_TEST_RUNNING
        Diagnostics.i(TAG, "startVoiceTest: 开始 backend=${prefs.voiceBackend}")
        // 上一轮万一还在跑（极快连点）：先收掉，避免两路采集抢同一个麦克风
        voiceSelfTest?.cancel()
        val test = VoiceSelfTest(prefs) { result -> onVoiceTestFinished(result) }
        voiceSelfTest = test
        test.start()
    }

    /** 测试结束（主线程回调）：正文落框、结论落行、按钮恢复 */
    private fun onVoiceTestFinished(result: VoiceSelfTest.Result) {
        voiceSelfTest = null
        // 页面已关：晚到的回调不碰视图（测试对象在 onDestroy 里已取消）
        if (isFinishing || isDestroyed) return
        // 失败结论不带正文：此时别把用户自己打进框里的样例句清掉（成功才覆盖）
        if (result.text.isNotEmpty()) editVoiceTest.setText(result.text)
        textVoiceTestResult.text = result.verdict
        btnVoiceTest.isEnabled = true
        // 只记结论与长度：识别正文是用户说的话，不进日志
        Diagnostics.i(TAG, "语音测试结论: ${result.verdict} 正文长度=${result.text.length}")
    }

    /**
     * 页面销毁：必须收掉正在跑的测试 —— 它自带麦克风采集线程与 WebSocket 连接，
     * 漏掉就是「退出页面后麦克风仍被占用」或线程泄漏。
     */
    override fun onDestroy() {
        voiceSelfTest?.cancel()
        voiceSelfTest = null
        // 页面销毁后任何排队任务都不该再碰 Prefs
        autosaveHandler.removeCallbacks(autosaveRunnable)
        super.onDestroy()
    }

    // ── 保存并重启输入法进程 ──────────────────────────────────────────

    /**
     * 「重启输入法」按钮：改动本身在输入停顿 / 离开页面时已自动保存（[persistInputs]），
     * 这里先把排队的自动保存顶掉、立即落盘一次，再重启 IME 进程让设置生效。
     *
     * 防连点：进来先看按钮是否已禁用，再立即禁用自己；三条不杀进程的出口（校验没过、
     * 凭据未落盘、`ImeRestart` 的 flush 失败回调）都要把按钮与 [restarting] 复位。
     *
     * 走到真正要杀进程的那一步就冻结表单：[ImeRestart] 的 `killProcess` 不走生命周期，
     * 窗口内的新编辑没有 `onPause` 兜底，也不该靠 800ms 防抖去赌它写完。
     */
    private fun saveAndRestart() {
        if (!btnSave.isEnabled) return
        btnSave.isEnabled = false
        autosaveHandler.removeCallbacks(autosaveRunnable)
        if (ConfigBackupManager.importing) {
            textSaveHint.text = TEXT_SAVE_BLOCKED_IMPORTING
            btnSave.isEnabled = true
            return
        }
        // 基线必须在写盘**之前**捕获：落盘失败要退回到这一批之前的值，否则退出确认会被绕过
        val previous = savedInputs
        if (!persistInputs()) {
            // 校验没过：这批没写，状态行按脏态提示（error 已标在对应输入框上）
            refreshDirty()
            restarting = false
            btnSave.isEnabled = true
            return
        }
        // 凭据只进了内存（Keystore 与软件密钥都不可用）时**不能重启**：killProcess 不走 onDestroy，
        // 内存里那份随进程一起丢，而磁盘上旧密文已被删（Prefs.writeCredential 的 fail-closed 分支）
        // ⇒ 重启后该家回到「未配置」。基线一并退回，让退出确认能问到这批改动。
        val unpersisted = prefs.unpersistedCredentialKeys()
        if (unpersisted.isNotEmpty()) {
            Diagnostics.w(TAG, "saveAndRestart: ${unpersisted.size} 个凭据未落盘，已取消重启")
            savedInputs = previous
            recomputeDirty()
            textSaveHint.text = TEXT_SAVE_NOT_PERSISTED
            restarting = false
            btnSave.isEnabled = true
            return
        }
        textSaveHint.text = TEXT_RESTARTING
        restarting = true
        setFormEnabled(false)
        // flush 确认落盘再杀进程（只赌延时在 IO 抖动时会丢最后一批设置）；落盘失败不重启
        ImeRestart.restart(this) { message ->
            // 进程没被杀：设置没落盘 ⇒ 基线退回、脏标记跟着回来，否则按 ✕ 直接退出会静默丢输入
            savedInputs = previous
            recomputeDirty()
            textSaveHint.text = message
            restarting = false
            btnSave.isEnabled = true
            setFormEnabled(true)
        }
    }

    /**
     * 校验全过之后把旧红字清掉（25 处 `.error =` 赋值目标去重；只在需要时 set，避免气泡闪烁）。
     *
     * 独立函数而不是内联进 [persistInputs]：清 error 的覆盖面要能被守卫单独对拍。
     */
    private fun clearFieldErrors() {
        for (field in listOf(
            editHost, editPort,
            editXfAppId, editXfApiKey, editXfApiSecret,
            editOaiBase, editOaiModel, editOaiApiKey,
            editAliWsUrl, editAliModel, editAliApiKey,
            editTencentSecretId, editTencentSecretKey,
            editAzureBaseUrl, editAzureApiKey,
            editBaiduApiKey, editBaiduSecretKey, editBaiduDevPid,
            editDeepgramApiKey, editDeepgramModel, editDeepgramLanguage,
        )) {
            if (field.error != null) field.error = null
        }
    }

    /**
     * 把当前表单写入 Prefs（改动即保存的**唯一写盘点**：输入停顿、离开页面、重启前共用）。
     *
     * 2026-11-10 起不再要求「必须点保存」：编辑框停顿 [AUTOSAVE_DEBOUNCE_MS] 后自动走到这里，
     * 离开页面（[onPause]）也走这里。校验顺序与原实现一致：先 host 非空、再 `Prefs.isValidHost`、
     * 最后端口 1..65535；任一字段不过就只把 error 标在对应输入框上并返回 false，**整表不写盘** ——
     * 换后端却没填凭据这种半配置状态若写进去，运行中的输入法会拿着空凭据连新服务端。
     *
     * [dryRun] 为 true 时只校验、只标红，绝不写盘（退出确认用）；成功落盘才推进基线、清脏、清红字。
     */
    private fun persistInputs(dryRun: Boolean = false): Boolean {
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().trim().toIntOrNull()

        // 语音关闭时 host/port 字段是隐藏的，校验没有意义且会阻塞保存
        val voiceEnabled = switchVoiceInput.isChecked
        val backend = readBackend()
        // 凭据的有效取值：掩码形态取磁盘原值（绝不把掩码串写回），空串才是「用户清空」
        val appIdValue = credentialText(editXfAppId)
        val apiKeyValue = credentialText(editXfApiKey)
        val apiSecretValue = credentialText(editXfApiSecret)
        val oaiBase = editOaiBase.text.toString().trim()
        val oaiModelValue = editOaiModel.text.toString().trim()
        val oaiKeyValue = credentialText(editOaiApiKey)
        val aliWsUrlValue = editAliWsUrl.text.toString().trim()
        val aliModelValue = editAliModel.text.toString().trim()
        val aliKeyValue = credentialText(editAliApiKey)
        val tencentIdValue = credentialText(editTencentSecretId)
        val tencentKeyValue = credentialText(editTencentSecretKey)
        val tencentEngineValue = editTencentEngine.text.toString().trim()
        val azureBaseValue = editAzureBaseUrl.text.toString().trim()
        val azureKeyValue = credentialText(editAzureApiKey)
        val azureLanguageValue = editAzureLanguage.text.toString().trim()
        val baiduKeyValue = credentialText(editBaiduApiKey)
        val baiduSecretValue = credentialText(editBaiduSecretKey)
        val baiduDevPidValue = editBaiduDevPid.text.toString().trim()
        val deepgramKeyValue = credentialText(editDeepgramApiKey)
        val deepgramModelValue = editDeepgramModel.text.toString().trim()
        val deepgramLanguageValue = editDeepgramLanguage.text.toString().trim()
        if (voiceEnabled && backend == VoiceBackend.XF) {
            // 讯飞在线：三件凭据缺一不可（缺哪个就在哪个框上标红，不重启、不写盘）
            if (appIdValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 讯飞 APPID 为空")
                editXfAppId.error = TEXT_XF_REQUIRED
                return false
            }
            if (apiKeyValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 讯飞 APIKey 为空")
                editXfApiKey.error = TEXT_XF_REQUIRED
                return false
            }
            if (apiSecretValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 讯飞 APISecret 为空")
                editXfApiSecret.error = TEXT_XF_REQUIRED
                return false
            }
        } else if (voiceEnabled && backend == VoiceBackend.OAI) {
            // OpenAI 兼容：地址先过策略（含 https 强制），再要求模型名与 Key；
            // 拒绝原因原样落到 edit_oai_base.error —— 用户看到的必须是「为什么不能用」，
            // 而不是一句「保存失败」。
            if (oaiBase.isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: OpenAI Base URL 为空")
                editOaiBase.error = TEXT_OAI_REQUIRED
                return false
            }
            val addressError = OpenAiEndpoint.normalize(oaiBase).exceptionOrNull()?.message
            if (addressError != null) {
                Diagnostics.w(TAG, "saveAndRestart: OpenAI 地址不合法 reason=$addressError")
                editOaiBase.error = addressError
                return false
            }
            if (oaiModelValue.isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: OpenAI 模型名为空")
                editOaiModel.error = TEXT_OAI_REQUIRED
                return false
            }
            if (oaiKeyValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: OpenAI API Key 为空")
                editOaiApiKey.error = TEXT_OAI_REQUIRED
                return false
            }
        } else if (voiceEnabled && backend == VoiceBackend.ALI) {
            // 阿里云百炼：地址先过 WS 策略（wss 强制，ws 只允许本机），再要求模型名与 Key；
            // 拒绝原因原样落到 edit_ali_ws_url.error —— 用户要看到「为什么不能用」与「该怎么改」。
            if (aliWsUrlValue.isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 阿里百炼 WS 地址为空")
                editAliWsUrl.error = TEXT_ALI_REQUIRED
                return false
            }
            val addressError = AliEndpoint.normalize(aliWsUrlValue).exceptionOrNull()?.message
            if (addressError != null) {
                Diagnostics.w(TAG, "saveAndRestart: 阿里百炼地址不合法 reason=$addressError")
                editAliWsUrl.error = addressError
                return false
            }
            if (aliModelValue.isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 阿里百炼模型名为空")
                editAliModel.error = TEXT_ALI_REQUIRED
                return false
            }
            if (aliKeyValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 阿里百炼 API Key 为空")
                editAliApiKey.error = TEXT_ALI_REQUIRED
                return false
            }
        } else if (voiceEnabled && backend == VoiceBackend.TENCENT) {
            // 腾讯云：两件凭据缺一不可（SecretKey 才能做 TC3 签名；SecretId 也参与 Credential 段）
            if (tencentIdValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 腾讯云 SecretId 为空")
                editTencentSecretId.error = TEXT_TENCENT_REQUIRED
                return false
            }
            if (tencentKeyValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 腾讯云 SecretKey 为空")
                editTencentSecretKey.error = TEXT_TENCENT_REQUIRED
                return false
            }
        } else if (voiceEnabled && backend == VoiceBackend.AZURE) {
            // Azure：地址先过策略（https 强制，http 只允许本机 / 私网），再要求订阅密钥；
            // 拒绝原因原样落到 edit_azure_base_url.error —— 用户要看到「为什么不能用」与「该怎么改」。
            if (azureBaseValue.isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: Azure Base URL 为空")
                editAzureBaseUrl.error = TEXT_AZURE_REQUIRED
                return false
            }
            val addressError = AzureAsrEndpoint.normalize(azureBaseValue).exceptionOrNull()?.message
            if (addressError != null) {
                Diagnostics.w(TAG, "saveAndRestart: Azure 地址不合法 reason=$addressError")
                editAzureBaseUrl.error = addressError
                return false
            }
            if (azureKeyValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: Azure 订阅密钥为空")
                editAzureApiKey.error = TEXT_AZURE_REQUIRED
                return false
            }
        } else if (voiceEnabled && backend == VoiceBackend.BAIDU) {
            // 百度：两件凭据缺一不可（AK 取 token、SK 签 client_secret）；dev_pid 必须是数字 ——
            // 服务端对非数字直接 3300，而「保存后识别不出」在真机上完全看不出是这里填错了
            if (baiduKeyValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 百度 API Key 为空")
                editBaiduApiKey.error = TEXT_BAIDU_REQUIRED
                return false
            }
            if (baiduSecretValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: 百度 Secret Key 为空")
                editBaiduSecretKey.error = TEXT_BAIDU_REQUIRED
                return false
            }
            if (baiduDevPidValue.toIntOrNull() == null) {
                Diagnostics.w(TAG, "saveAndRestart: 百度 dev_pid 不是数字")
                editBaiduDevPid.error = TEXT_BAIDU_PID_REQUIRED
                return false
            }
        } else if (voiceEnabled && backend == VoiceBackend.DEEPGRAM) {
            // Deepgram：Key 必填；模型名与语言码不许留空（清空过就会被 setter 回落默认，
            // 这里拦住是为了让「空着不填」在保存时就暴露，而不是悄悄用默认跑一次）
            if (deepgramKeyValue.trim().isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: Deepgram API Key 为空")
                editDeepgramApiKey.error = TEXT_DEEPGRAM_REQUIRED
                return false
            }
            if (deepgramModelValue.isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: Deepgram 模型名为空")
                editDeepgramModel.error = TEXT_DEEPGRAM_REQUIRED
                return false
            }
            if (deepgramLanguageValue.isEmpty()) {
                Diagnostics.w(TAG, "saveAndRestart: Deepgram 语言码为空")
                editDeepgramLanguage.error = TEXT_DEEPGRAM_REQUIRED
                return false
            }
        } else if (voiceEnabled) {
            if (host.isBlank()) {
                Diagnostics.w(TAG, "saveAndRestart: host 为空")
                editHost.error = getString(R.string.settings_invalid_host)
                return false
            }
            // 非法 host 会被拼成 HttpUrl 拒绝的地址，进而在主线程抛异常把 IME 打崩；
            // 这里拦在保存入口，配合 Prefs 的兜底形成两道防线（语音链路不改动，不能在那里兜）。
            if (!Prefs.isValidHost(host)) {
                Diagnostics.w(TAG, "saveAndRestart: host 含非法字符，已拒绝保存")
                editHost.error = getString(R.string.settings_invalid_host)
                return false
            }
            if (port == null || port !in 1..65535) {
                Diagnostics.w(TAG, "saveAndRestart: 端口非法 port=$port")
                editPort.error = getString(R.string.settings_invalid_port)
                return false
            }
        }

        // 退出确认只想知道「有没有非法字段」，此时一个键都不许写：写进去就撤不回，
        // 「放弃并退出」也会变成假放弃（apply 的值会随框架排队落盘）
        if (dryRun) return true
        // 旧红字只在校验全过时清：失败分支保留标红，用户照着补
        clearFieldErrors()

        prefs.voiceInputEnabled = voiceEnabled
        prefs.voiceBackend = backend
        // 讯飞凭据只在选中该后端时写：caps 下三件输入框是隐藏的，写回隐藏字段等于覆盖用户改好的值。
        // 掩码形态已在 [credentialText] 折回磁盘原值 —— 写进去的是凭据，绝不会是 `b86••••••••`。
        if (backend == VoiceBackend.XF) {
            prefs.xfAppId = appIdValue
            prefs.xfApiKey = apiKeyValue
            prefs.xfApiSecret = apiSecretValue
        }
        // OpenAI 兼容：同样只在该后端下写（另两张卡里的字段此时是隐藏的旧值）
        if (backend == VoiceBackend.OAI) {
            prefs.oaiBaseUrl = oaiBase
            prefs.oaiModel = oaiModelValue
            prefs.oaiApiKey = oaiKeyValue
        }
        // 阿里云百炼：同款，只在该后端下写
        if (backend == VoiceBackend.ALI) {
            prefs.aliWsUrl = aliWsUrlValue
            prefs.aliModel = aliModelValue
            prefs.aliApiKey = aliKeyValue
        }
        // 腾讯云：同款，只在该后端下写（另五张卡的字段此刻是隐藏的旧值）
        if (backend == VoiceBackend.TENCENT) {
            prefs.tencentSecretId = tencentIdValue
            prefs.tencentSecretKey = tencentKeyValue
            prefs.tencentEngine = tencentEngineValue
        }
        // Azure：同款，只在该后端下写
        if (backend == VoiceBackend.AZURE) {
            prefs.azureBaseUrl = azureBaseValue
            prefs.azureSpeechKey = azureKeyValue
            prefs.azureLanguage = azureLanguageValue
        }
        // 百度：同款，只在该后端下写。dev_pid 已校验是数字（上面那一支），这里 toInt 必然成功
        if (backend == VoiceBackend.BAIDU) {
            prefs.baiduSpeechApiKey = baiduKeyValue
            prefs.baiduSpeechSecretKey = baiduSecretValue
            baiduDevPidValue.toIntOrNull()?.let { prefs.baiduDevPid = it }
        }
        // Deepgram：同款，只在该后端下写
        if (backend == VoiceBackend.DEEPGRAM) {
            prefs.deepgramApiKey = deepgramKeyValue
            prefs.deepgramModel = deepgramModelValue
            prefs.deepgramLanguage = deepgramLanguageValue
        }
        prefs.host = host
        // 语音关闭时上面的校验被跳过，port 可能为 null，退回默认端口
        prefs.port = port ?: Prefs.DEFAULT_PORT
        prefs.language = readLanguage()
        prefs.prompt = editPrompt.text.toString()
        prefs.stripTrailingPunc = checkStrip.isChecked
        prefs.useComposing = checkComposing.isChecked

        // 提示词是用户自己写的正文（可能含个人信息），只记长度不记内容；
        // 凭据同样只记长度（绝不出原文）
        Diagnostics.i(
            TAG,
            "persistInputs: 语音配置已保存 backend=$backend host=$host port=$port " +
                "lang=${prefs.language} promptLen=${prefs.prompt.length} enabled=$voiceEnabled " +
                "xfAppIdLen=${prefs.xfAppId.length} xfApiKeyLen=${prefs.xfApiKey.length} " +
                "xfApiSecretLen=${prefs.xfApiSecret.length} " +
                "oaiBase=${prefs.oaiBaseUrl} oaiModel=${prefs.oaiModel} oaiKeyLen=${prefs.oaiApiKey.length} " +
                "aliWsUrl=${prefs.aliWsUrl} aliModel=${prefs.aliModel} aliKeyLen=${prefs.aliApiKey.length} " +
                "tencentEngine=${prefs.tencentEngine} tencentIdLen=${prefs.tencentSecretId.length} " +
                "tencentKeyLen=${prefs.tencentSecretKey.length} " +
                "azureBase=${prefs.azureBaseUrl} azureLang=${prefs.azureLanguage} " +
                "azureKeyLen=${prefs.azureSpeechKey.length} " +
                "baiduKeyLen=${prefs.baiduSpeechApiKey.length} baiduSecretLen=${prefs.baiduSpeechSecretKey.length} " +
                "baiduDevPid=${prefs.baiduDevPid} " +
                "deepgramModel=${prefs.deepgramModel} deepgramLang=${prefs.deepgramLanguage} " +
                "deepgramKeyLen=${prefs.deepgramApiKey.length}",
        )
        // 基线先记成「这批已保存」：停顿窗口 / 重启窗口里若再动控件，按新基线算脏才对
        savedInputs = currentInputs()
        dirty = false
        return true
    }

    /**
     * 离开页面即落盘，并显式等一次写入 —— 与 OpenAI 兼容页 / 翻译设置页同款两处坑：
     * EditText 失焦在返回时不一定派发回调；且必须写在 `onPause`（下一个页面的 onResume 早于它）。
     *
     * 「放弃并退出」只放弃**表单回写**：[persistInputs] 跳过，但 `prefs.flush()` 照做，
     * 把此前自动保存已 `apply()` 的合法改动与「勾选即写」项确认写盘，并给 [flushFailed] 一个复位点。
     *
     * 点 ✕ / 返回键的「重启后生效」提醒也等 `prefs.flush()` 成功之后才发：宣告前必须真正确认
     * 本批已落盘、凭据没卡在内存里，否则零写入时那句「设置已保存」是虚假成功提示。
     */
    override fun onPause() {
        super.onPause()
        // 撤掉排队的防抖任务：改动已在下面写完（否则过一会儿再跑一次空转）
        autosaveHandler.removeCallbacks(autosaveRunnable)
        // 配置导入在途：连一次空 commit 也不做，导入线程正在写同一份 Prefs；退出提醒意图一并作废
        if (ConfigBackupManager.importing) {
            exitNeedsRestartHint = false
            return
        }
        // 重启窗口不早退：ImeRestart 的 flush 已跑完，窗口里新改的字段靠这里补齐一次
        if (!discardOnExit) {
            persistInputs()
            // 自动保存路径也要消费 `unpersistedCredentialKeys`（BUG.md L-741 / L-1050 口径）：
            // `writeCredential` 在 Keystore 不可用时会**删掉磁盘上的旧密文**（fail-closed，有意设计），
            // 不提示的话填完 Key 直接返回的用户全程零提示，重启后回到「未配置」。
            val unpersisted = prefs.unpersistedCredentialKeys()
            if (unpersisted.isNotEmpty()) {
                Diagnostics.w(TAG, "onPause: ${unpersisted.size} 个凭据未落盘")
                textSaveHint.text = TEXT_SAVE_NOT_PERSISTED
                // 凭据只进了内存：这次退出不许再发「设置已保存」
                exitNeedsRestartHint = false
            } else {
                // 落盘成功即撤回旧提示（优先级：凭据未落盘 > 字段非法 > 空闲）
                updateSaveHint()
            }
        }
        // `Prefs.flush` 是空 edit 的同步 `commit()`：不读表单、不调 setter，只写实此前 apply 的值。
        // 放弃分支之外执行，同时给 flushFailed 一个页面内可达的复位点；失败必须有可见反馈
        if (prefs.flush()) {
            flushFailed = false
            // 只有点 ✕ / 返回键退出、且本批已确认落盘、凭据也没卡在内存里，才发「已保存」提醒
            if (exitNeedsRestartHint) {
                Toast.makeText(this, TEXT_RESTART_NEEDED, Toast.LENGTH_SHORT).show()
            }
            exitNeedsRestartHint = false
        } else {
            flushFailed = true
            exitNeedsRestartHint = false
            Diagnostics.w(TAG, "onPause: 配置落盘失败（改动可能回退）")
            Toast.makeText(this, TEXT_FLUSH_FAILED, Toast.LENGTH_LONG).show()
        }
    }

    // ── 改动即保存（输入停顿自动落盘） ────────────────────────────────

    /** 与 OpenAI 兼容页 / 翻译设置页同款（`BUG.md` L-967 / L-209）：连续输入只在停顿后写一次 */
    private fun scheduleAutosave() {
        if (restarting || discardOnExit || ConfigBackupManager.importing) return
        autosaveHandler.removeCallbacks(autosaveRunnable)
        autosaveHandler.postDelayed(autosaveRunnable, AUTOSAVE_DEBOUNCE_MS)
    }

    private val autosaveHandler = Handler(Looper.getMainLooper())

    /**
     * 输入停顿后的回写。
     *
     * 取值与基线相同就直接空转：程序化回填（[loadPrefs] 的 `setText` / 旋转重建的实例恢复 /
     * 掩码折回）都会触发 watcher 并把任务排进队，靠快照比对挡住，不做无谓的写盘与 flush。
     */
    private val autosaveRunnable = Runnable {
        if (restarting || discardOnExit || ConfigBackupManager.importing) return@Runnable
        if (currentInputs() == savedInputs) return@Runnable
        persistInputs()
        // 自动保存路径同样要消费未落盘集合（BUG.md L-1050 口径）：`writeCredential` 在
        // Keystore 不可用时 fail-closed（删掉盘上旧密文），不提示的话填完 Key 就不再动的
        // 用户全程零信号，重启后回到「未配置」。三条落盘路径的口径必须一致。
        val unpersisted = prefs.unpersistedCredentialKeys()
        if (unpersisted.isNotEmpty()) {
            Diagnostics.w(TAG, "输入停顿自动保存: ${unpersisted.size} 个凭据未落盘")
            textSaveHint.text = TEXT_SAVE_NOT_PERSISTED
        } else {
            // 不在 if 之外无条件调：那会把刚写下的「未落盘」提示覆盖成「已保存」
            updateSaveHint()
        }
    }

    // ── 脏状态与未保存退出 ──────────────────────────────────────────

    /**
     * 给编辑框挂「改动即重算脏状态并排队自动保存」的监听。
     *
     * **只用 `afterTextChanged`**：`beforeTextChanged` / `onTextChanged` 里不写任何东西 ——
     * 后两者在每次按键都会先跑，写逻辑会把「还没改完的中间态」当成一次改动。
     */
    private fun markDirtyOnEdit(view: EditText) {
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                // 只是「重算」：回填 / 状态恢复同样会走到这里，靠快照比对才不会误判成脏
                refreshDirty()
                scheduleAutosave()
            }
        })
    }

    /** 当前控件取值（与 [savedInputs] 逐字段比对） */
    private fun currentInputs(): VoiceInputs = VoiceInputs(
        host = editHost.text.toString(),
        port = editPort.text.toString(),
        prompt = editPrompt.text.toString(),
        language = readLanguage(),
        voiceEnabled = switchVoiceInput.isChecked,
        backend = readBackend(),
        // 凭据取**有效值**：掩码形态折回磁盘原值 ⇒ 聚焦（显示真值）与失焦（回到掩码）都不算改动，
        // 「只点了一下输入框」不会把页面判成脏
        xfAppId = credentialText(editXfAppId),
        xfApiKey = credentialText(editXfApiKey),
        xfApiSecret = credentialText(editXfApiSecret),
        // OpenAI 兼容：Key 同款折回磁盘原值，地址 / 模型名按去空白的有效值算
        oaiBase = editOaiBase.text.toString().trim(),
        oaiModel = editOaiModel.text.toString().trim(),
        oaiApiKey = credentialText(editOaiApiKey),
        // 阿里云百炼：Key 同款折回磁盘原值，地址 / 模型名按去空白的有效值算
        aliWsUrl = editAliWsUrl.text.toString().trim(),
        aliModel = editAliModel.text.toString().trim(),
        aliApiKey = credentialText(editAliApiKey),
        // 腾讯云：两件凭据按掩码折回磁盘原值，引擎型号按去空白的有效值算
        tencentSecretId = credentialText(editTencentSecretId),
        tencentSecretKey = credentialText(editTencentSecretKey),
        tencentEngine = editTencentEngine.text.toString().trim(),
        // Azure：密钥同款折回磁盘原值，地址 / 语言码按去空白的有效值算
        azureBaseUrl = editAzureBaseUrl.text.toString().trim(),
        azureApiKey = credentialText(editAzureApiKey),
        azureLanguage = editAzureLanguage.text.toString().trim(),
        // 百度：两件凭据按掩码折回磁盘原值，dev_pid 按去空白的有效值算
        baiduApiKey = credentialText(editBaiduApiKey),
        baiduSecretKey = credentialText(editBaiduSecretKey),
        baiduDevPid = editBaiduDevPid.text.toString().trim(),
        // Deepgram：Key 同款折回磁盘原值，模型名 / 语言码按去空白的有效值算
        deepgramApiKey = credentialText(editDeepgramApiKey),
        deepgramModel = editDeepgramModel.text.toString().trim(),
        deepgramLanguage = editDeepgramLanguage.text.toString().trim(),
    )

    /**
     * 重算脏标记，**不动状态行**。
     *
     * 判据是「当前取值与上次落盘不一致」，不是「碰过控件」：`setText` 与实例状态恢复都会触发
     * `TextWatcher`（旋转时系统把编辑框正文放回来也算一次），按「碰过就算脏」会让刚进页面、
     * 什么都没改的旋转判成脏，也让「改一个字再改回原值」永远回不到干净。
     *
     * 与 [refreshDirty] 分开是为了落盘失败那条路径：脏标记要回来，状态行得留给 `ImeRestart` 那句。
     */
    private fun recomputeDirty() {
        val base = savedInputs ?: return
        dirty = currentInputs() != base
    }

    /** 控件变化 / 重建恢复后调用：重算标记并把状态行切过去 */
    private fun refreshDirty() {
        recomputeDirty()
        updateSaveHint()
    }

    /** 状态行：脏 → [TEXT_DIRTY]，干净 → [TEXT_SAVE_HINT_IDLE]（文案没变不重写，见下） */
    private fun updateSaveHint() {
        val text = if (dirty) TEXT_DIRTY else TEXT_SAVE_HINT_IDLE
        // 这行挂了 POLITE 活区：`setText` 即使文案相同也会发一次文本变化事件，读屏会在每次按键
        // 重念一遍「有未保存的修改」；另外 onItemSelected 在 layout 里回调，重写兄弟视图会再排一次布局。
        if (textSaveHint.text.toString() != text) textSaveHint.text = text
    }

    /**
     * 关闭入口（✕ 与返回键**共用这一个**）：只做「只校验不写盘」的判断，
     * 合法改动交给 [onPause] 落盘；只有真有非法字段才问一句。
     *
     * 写入失败与配置导入在途各是独立状态：都留在页面并给 Toast，不发「已保存」提示、
     * 不弹语义错配的确认框。「放弃并退出」置 [discardOnExit]，让 [onPause] 与排队任务
     * 都不回写表单 —— 改动真被放弃。
     */
    private fun confirmExit() {
        // 每次进入都先清掉上次的意图：下面每个早退分支都不该带着旧标志
        exitNeedsRestartHint = false
        // 导入线程正在写同一份 Prefs：退出会让这批编辑无处落盘，留在页面等导入结束
        if (ConfigBackupManager.importing) {
            Toast.makeText(this, TEXT_SAVE_BLOCKED_IMPORTING, Toast.LENGTH_SHORT).show()
            return
        }
        // 撤掉排队的自动保存：下面的判断必须是「此刻表单」的最终结论
        autosaveHandler.removeCallbacks(autosaveRunnable)
        // 上次写入失败：先给一次不读表单的同步确认。仍失败就留在页面（保留「再问一次」），
        // 既不能发「设置已保存」，也不能弹「有字段不合法」框 —— 表单可能全是合法的
        if (flushFailed && !prefs.flush()) {
            Toast.makeText(this, TEXT_FLUSH_FAILED, Toast.LENGTH_LONG).show()
            return
        }
        flushFailed = false
        // dryRun：一个键都不写，只把非法字段标红。写进去就撤不回，「放弃」会变成假放弃
        if (!persistInputs(dryRun = true)) {
            recomputeDirty()
            AlertDialog.Builder(this)
                .setTitle(TEXT_DIRTY)
                .setPositiveButton(TEXT_DIALOG_KEEP) { dialog, _ -> dialog.dismiss() }
                .setNegativeButton(TEXT_DIALOG_DISCARD) { _, _ ->
                    discardOnExit = true
                    dirty = false
                    finish()
                }
                .show()
            return
        }
        recomputeDirty()
        // 有改动且校验通过：只记下提醒意图，不在这里发「设置已保存」—— 此刻 dryRun 一个键都没写，
        // 真正落盘在 onPause；凭据 fail-closed 或 flush 失败时那句「已保存」会是虚假成功提示
        if (dirty) exitNeedsRestartHint = true
        finish()
    }

    /**
     * 返回键入口（与 ✕ 共用 [confirmExit]）。
     *
     * 不覆写 `onBackPressed`：`ComponentActivity` 的那个方法带 `@CallSuper`，覆写不调 super 就是
     * lint `MissingSuperCall`（error 级，`lintDebug` 直接红）。`OnBackPressedDispatcher` 的回调时机
     * 与覆写等价，且「继续编辑」分支不会被系统先行 `finish()`。
     */
    private fun registerBackHandling() {
        onBackPressedDispatcher.addCallback(this) { confirmExit() }
    }

    private companion object {
        const val TAG = "VoiceSettings"

        /** 页面标题（= 设置页入口按钮上的那句） */
        const val TEXT_TITLE = "语音转为文字"

        /** 页面说明。
         *
         * ⚠ 不许写成「本页配置不点保存不生效」：三个勾选框（去句尾标点 / 实时回显 / 固定服务端）
         * 与默认键盘样式都是**改动即写盘**（原设置页的即时语义，见 `onCreate` 的四个监听器），
         * 保存只对总开关 / 后端 / 地址 / 端口 / 凭据 / 语言 / 提示词有意义。文案与行为必须一致。
         */
        const val TEXT_DESC = "语音识别可选自建服务端（CapsWriter 协议，需在家用 NAS 上部署）、" +
            "讯飞在线听写、OpenAI 兼容转写、阿里云百炼、腾讯云一句话识别、Azure 语音识别、" +
            "百度短语音识别或 Deepgram。" +
            "勾选项与默认键盘样式改动即保存。"

        /** 识别后端选择（八张配置卡跟着切） */
        const val TEXT_BACKEND_LABEL = "识别服务"
        const val TEXT_BACKEND_CAPS = "自建服务端（CapsWriter）"
        const val TEXT_BACKEND_XF = "讯飞在线（星火听写 V2）"
        const val TEXT_BACKEND_OAI = "OpenAI 兼容（语音转写）"
        const val TEXT_BACKEND_ALI = "阿里云百炼（Paraformer）"
        const val TEXT_BACKEND_TENCENT = "腾讯云（一句话识别）"
        const val TEXT_BACKEND_AZURE = "Azure（短音频识别）"
        const val TEXT_BACKEND_BAIDU = "百度智能云（短语音识别）"
        const val TEXT_BACKEND_DEEPGRAM = "Deepgram（Pre-recorded）"

        /** 百度短语音识别：API Key / Secret Key / dev_pid（标签走 hint，文案不进 strings.xml） */
        const val TEXT_BAIDU_DESC = "百度智能云「短语音识别标准版」：填 API Key（AK）与 Secret Key（SK），" +
            "dev_pid 默认 1537（普通话）。录音只在本机缓冲，松手后整段上传；单次不超过 60 秒。"
        const val TEXT_BAIDU_API_KEY = "API Key（AK）"
        const val TEXT_BAIDU_SECRET_KEY = "Secret Key（SK）"
        const val TEXT_BAIDU_DEV_PID = "dev_pid（默认 1537 普通话）"
        const val TEXT_BAIDU_REQUIRED = "必填"
        const val TEXT_BAIDU_PID_REQUIRED = "dev_pid 必须是数字"
        const val TEXT_BAIDU_SHOW = "显示 Secret Key"

        /** Deepgram：API Key / 模型 / 语言（标签走 hint，文案不进 strings.xml） */
        const val TEXT_DEEPGRAM_DESC = "Deepgram 的 Pre-recorded 识别：填 API Key，模型默认 nova-3、" +
            "语言默认 zh。录音只在本机缓冲，松手后整段上传（裸 WAV）。"
        const val TEXT_DEEPGRAM_API_KEY = "API Key"
        const val TEXT_DEEPGRAM_MODEL = "模型（默认 nova-3）"
        const val TEXT_DEEPGRAM_LANGUAGE = "语言（默认 zh）"
        const val TEXT_DEEPGRAM_REQUIRED = "必填"
        const val TEXT_DEEPGRAM_SHOW = "显示 API Key"

        /** 腾讯云一句话识别：SecretId / SecretKey / 引擎型号（标签走 hint，文案不进 strings.xml） */
        const val TEXT_TENCENT_DESC = "腾讯云「一句话识别」（SentenceRecognition）：填 SecretId 与 SecretKey，" +
            "引擎型号默认 16k_zh。录音只在本机缓冲，松手后整段上传；单次不超过 60 秒。"
        const val TEXT_TENCENT_SECRET_ID = "SecretId"
        const val TEXT_TENCENT_SECRET_KEY = "SecretKey"
        const val TEXT_TENCENT_ENGINE = "引擎型号（默认 16k_zh）"
        const val TEXT_TENCENT_REQUIRED = "必填"
        const val TEXT_TENCENT_SHOW = "显示 SecretKey"

        /** Azure 短音频识别：Base URL / 订阅密钥 / 语言码（标签走 hint，文案不进 strings.xml） */
        const val TEXT_AZURE_DESC = "Azure AI Speech 的短音频识别 REST：填资源的 Base URL" +
            "（例 https://你的资源名.cognitiveservices.azure.com）与订阅密钥，语言默认 zh-CN。" +
            "地址必须 https，局域网可用 http。"
        const val TEXT_AZURE_BASE_URL = "Base URL（例 https://资源名.cognitiveservices.azure.com）"
        const val TEXT_AZURE_KEY = "订阅密钥（Key 1 / Key 2）"
        const val TEXT_AZURE_LANGUAGE = "语言（默认 zh-CN）"
        const val TEXT_AZURE_REQUIRED = "必填"
        const val TEXT_AZURE_SHOW = "显示订阅密钥"

        /** 阿里云百炼：WS 地址 / 模型名 / API Key（标签走 hint，文案不进 strings.xml） */
        const val TEXT_ALI_DESC = "阿里云百炼的实时语音识别（Paraformer / Qwen-ASR）：填 WS 地址、" +
            "模型名与 API Key。地址必须 wss://（本机可用 ws://），默认端点在国内可直连。"
        const val TEXT_ALI_WS_URL = "WS 地址（默认 wss://dashscope.aliyuncs.com/api-ws/v1/inference）"
        const val TEXT_ALI_MODEL = "模型名（默认 paraformer-realtime-v2）"
        const val TEXT_ALI_API_KEY = "API Key"
        const val TEXT_ALI_REQUIRED = "必填"
        const val TEXT_ALI_SHOW = "显示 API Key"

        /** OpenAI 兼容：Base URL / 模型名 / API Key（标签走 hint，文案不进 strings.xml） */
        const val TEXT_OAI_DESC = "任何提供 /audio/transcriptions 的 OpenAI 兼容服务（OpenAI 官方 / Groq / " +
            "硅基流动 / Together 等）：填 Base URL、模型名与 API Key。地址必须 https，" +
            "局域网可用 http。录音只在本机缓冲，松手后整段上传。"
        const val TEXT_OAI_BASE = "Base URL（例 https://api.groq.com/openai/v1）"
        const val TEXT_OAI_MODEL = "模型名（默认 whisper-1）"
        const val TEXT_OAI_API_KEY = "API Key"
        const val TEXT_OAI_REQUIRED = "必填"
        const val TEXT_OAI_SHOW = "显示 API Key"

        /** 讯飞在线：三件凭据（标签走 hint，文案不进 strings.xml） */
        const val TEXT_XF_DESC = "讯飞开放平台的「语音听写大模型（中英）V2.0」凭据：" +
            "APPID / APIKey / APISecret 只加密保存在本机，不会写进日志。"
        const val TEXT_XF_APP_ID = "APPID"
        const val TEXT_XF_API_KEY = "APIKey"
        const val TEXT_XF_API_SECRET = "APISecret"
        const val TEXT_XF_REQUIRED = "必填"

        /**
         * 磁盘上有密文、本机解不开时的如实说明。
         *
         * 少了它，用户只会看到几个空框 —— 与自己「明明填过」的记忆冲突，最坏会以为凭据被偷。
         * 实际成因是加密密钥件（`filesDir/credential_soft.key`）丢失或密文损坏，重填即可恢复。
         */
        const val TEXT_XF_UNREADABLE =
            "\n⚠ 已保存的凭据在本机解不开（加密密钥丢失或损坏），需要重新填写。"

        /** 显示 / 隐藏两个密码框（对齐粘贴进来的长串凭据用；APPID 本来就明文） */
        const val TEXT_XF_SHOW = "显示 APIKey / APISecret"

        /** 后端下拉的取值与显示文案（一一对应，靠下标对应） */
        val BACKEND_VALUES = arrayOf(
            VoiceBackend.CAPS, VoiceBackend.XF, VoiceBackend.OAI, VoiceBackend.ALI,
            VoiceBackend.TENCENT, VoiceBackend.AZURE, VoiceBackend.BAIDU, VoiceBackend.DEEPGRAM,
        )
        val BACKEND_LABELS = arrayOf(
            TEXT_BACKEND_CAPS, TEXT_BACKEND_XF, TEXT_BACKEND_OAI, TEXT_BACKEND_ALI,
            TEXT_BACKEND_TENCENT, TEXT_BACKEND_AZURE, TEXT_BACKEND_BAIDU, TEXT_BACKEND_DEEPGRAM,
        )

        /**
         * 顶部主按钮与状态行。
         *
         * 2026-11-10 起改动即保存（输入停顿 / 离开页面自动落盘），按钮不再承担「保存」，
         * 只负责重启输入法进程让设置生效（语音组件在 IME 进程里，不重启改了也不生效）。
         */
        const val TEXT_SAVE = "重启输入法"
        const val TEXT_SAVE_HINT_IDLE = "改动即保存，重启输入法后生效"
        const val TEXT_RESTARTING = "正在重启输入法…"

        /** 凭据只写进了内存（Keystore 不可用的 fail-closed 分支）：如实说，别让用户以为保存成功 */
        const val TEXT_SAVE_NOT_PERSISTED = "凭据未写入本机（设备可能已锁定，解锁后重试）"

        /** 退出时确认过「已保存」：语音组件在 IME 进程里，不点重启不生效，提醒一次即可 */
        const val TEXT_RESTART_NEEDED = "设置已保存，点「重启输入法」后生效"

        /** onPause 的 commit() 失败：页面正离开，状态行看不见，只能给 Toast */
        const val TEXT_FLUSH_FAILED = "设置写入失败，请重试或点「重启输入法」"

        /** 配置导入在途：不回写、不重启，避免覆盖导入线程刚写进 Prefs 的值 */
        const val TEXT_SAVE_BLOCKED_IMPORTING = "配置导入进行中，暂不可重启"

        /**
         * 脏状态与状态行文案（同时是未保存退出对话框的标题）。
         *
         * 自动保存之后「脏」只剩一种来源：校验没过的字段没写盘 —— 退出就丢，所以要问一句。
         */
        const val TEXT_DIRTY = "有字段不合法，尚未保存（见输入框提示）"
        const val TEXT_DIALOG_KEEP = "继续编辑"
        const val TEXT_DIALOG_DISCARD = "放弃并退出"

        /** 输入停顿自动保存的防抖窗口（与 OpenAI 兼容页同款；守卫测试钉值域 200..5000） */
        const val AUTOSAVE_DEBOUNCE_MS = 800L

        /** 折叠分组：方向标记与条数（例 `▾ 国内云服务（12）`，读屏读 `国内云服务，12 家，已展开`） */
        const val TEXT_GROUP_EXPANDED = "▾"
        const val TEXT_GROUP_COLLAPSED = "▸"
        const val TEXT_GROUP_COUNT_LEFT = "（"
        const val TEXT_GROUP_COUNT_RIGHT = "）"
        const val TEXT_STATE_EXPANDED = "已展开"
        const val TEXT_STATE_COLLAPSED = "已收起"
        const val TEXT_DESC_SEP = "，"
        const val TEXT_STATE_COUNT_UNIT = " 家"

        /** 折叠状态进 Bundle 的键（分类名数组，不是下标） */
        const val STATE_EXPANDED_GROUPS = "expanded_groups"

        /** 分组标题（章节） */
        const val TEXT_SECTION_SERVER = "服务端连接"
        const val TEXT_SECTION_PROVIDERS = "在线语音服务（API 文档）"

        /**
         * 总开关卡片的标题（用户点名的那一句）：
         * 中文双引号包住「语音输入转文字」，逗号在引号外、句子末尾无句号。
         *
         * 逐字核对：引号是 ASCII 双引号、逗号是全角，**不要**顺手改成中文引号或补句号
         * （文案由用户指定，且 `VoiceSettingsPageTest` 逐字对拍声明那一行）。
         */
        const val TEXT_VOICE_MASTER_TITLE = "启用\"语音输入转文字\",并连接对应服务器"

        /** 语音测试卡（自带一组：一次 3 秒识别，结果直接给用户看有没有效果） */
        const val TEXT_SECTION_TEST = "语音测试"
        const val TEXT_TEST_BUTTON = "语音测试"
        const val TEXT_TEST_HINT = "点这里唤起键盘，或点下方「语音测试」"
        const val TEXT_TEST_IDLE = "点上方「语音测试」跑一次 3 秒识别，结果直接写进上面的框"
        const val TEXT_TEST_RUNNING = "正在录音,请对手机说话,3秒后自动停."
        const val TEXT_TEST_NEED_PERMISSION = "未授权：允许麦克风权限后再点一次「语音测试」"
        const val TEXT_TEST_PERMISSION_OK = "已授权，请再点一次「语音测试」"

        /** 服务商目录说明（章节内的正文） */
        const val TEXT_PROVIDERS_DESC =
            "本输入法内置八种识别后端：自建服务端（CapsWriter）、讯飞在线听写、OpenAI 兼容转写、" +
                "阿里云百炼、腾讯云一句话识别、Azure 短音频识别、百度短语音识别、Deepgram。" +
                "以下为常见在线语音服务，点击查看官方文档"
        const val TEXT_NOTE_SEP = " · "
        const val TEXT_DOC = "文档"
        const val TEXT_DOC_FAILED = "没有可打开文档的应用"
    }
}
