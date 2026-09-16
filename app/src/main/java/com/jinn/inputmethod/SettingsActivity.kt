package com.jinn.inputmethod

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import java.io.File

/**
 * 设置页：配置飞牛 NAS 上的 Jinn 服务端地址、识别语言、提示词，
 * 引导授权麦克风、启用并切换到本输入法，并提供后台保活 / 防杀后台能力。
 * 同时作为应用入口从桌面启动。
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var checkLockServer: CheckBox
    private lateinit var spinnerLanguage: Spinner
    private lateinit var spinnerDefaultMode: Spinner
    private lateinit var editPrompt: EditText
    private lateinit var checkStrip: CheckBox
    private lateinit var checkComposing: CheckBox
    private lateinit var btnGrant: Button
    private lateinit var btnEnable: Button
    private lateinit var btnPick: Button
    private lateinit var btnSave: Button
    private lateinit var textTest: TextView
    private lateinit var textMicState: TextView

    // 输入法测试
    private lateinit var editImeTest: EditText
    private lateinit var btnImeSend: Button
    private lateinit var textImeReceived: TextView
    private lateinit var switchAutoShowKeyboard: Switch
    private lateinit var switchShowRareChars: Switch
    private lateinit var switchVoiceInput: Switch

    // 检查更新：版本号取构建日期，与远程 tag 比较
    private lateinit var btnCheckUpdate: Button
    /** 更新检查状态机：Idle / Checking / UpToDate / Available / NetworkError */
    private var updateState: UpdateState = UpdateState.Idle
    /** 语音相关区块（授权麦克风 / NAS 语音）：随总开关动态隐藏 */
    private lateinit var cardMicPermission: View
    private lateinit var cardVoiceServer: View

    // 扩展词库（长词包）：不进 APK，按需下载
    private lateinit var btnDictManager: Button

    // Root 增强模式（剪贴板数据目录安全审计，与已移除的保活无关）

    // 诊断
    private lateinit var btnExportDiag: Button
    private lateinit var textDiagDir: TextView

    // 剪贴板
    private lateinit var clipboardPrefs: ClipboardPrefs
    private lateinit var editClipboardMax: EditText

    // Root 增强模式
    private lateinit var textRootStatus: TextView
    private lateinit var switchRootEnhance: Switch

    /**
     * 程序化修改 Root 开关时置位（避免触发回调）。
     *
     * 场景：Root 不可用时需要在回调里把开关拨回关闭，而改 `isChecked` 本身又会
     * 触发同一个监听器 → 递归进入 else 分支，把刚设置的「Root 不可用」提示
     * 覆盖成功能描述文案，用户等于什么提示都没看到。
     */
    private var suppressRootSwitchCallback = false

    /** 主线程 Handler：保存配置后延迟片刻再杀进程重启输入法 */
    private val uiHandler = Handler(Looper.getMainLooper())

    /** Spinner 是否已完成初始化（setSelection 会触发 onItemSelected，未就绪时不响应） */
    private var defaultModeSpinnerReady = false

    /** Activity Result API 替代已弃用的 requestPermissions */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshMicState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.init(this)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        Diagnostics.i(TAG, "onCreate: 设置页启动")

        editHost = findViewById(R.id.edit_host)
        editPort = findViewById(R.id.edit_port)
        checkLockServer = findViewById(R.id.check_lock_server)
        spinnerLanguage = findViewById(R.id.spinner_language)
        spinnerDefaultMode = findViewById(R.id.spinner_default_mode)
        editPrompt = findViewById(R.id.edit_prompt)
        checkStrip = findViewById(R.id.check_strip)
        checkComposing = findViewById(R.id.check_composing)
        btnGrant = findViewById(R.id.btn_grant)
        btnEnable = findViewById(R.id.btn_enable)
        btnPick = findViewById(R.id.btn_pick)
        btnSave = findViewById(R.id.btn_save)
        textTest = findViewById(R.id.text_test)
        textMicState = findViewById(R.id.text_mic_state)

        editImeTest = findViewById(R.id.edit_ime_test)
        btnImeSend = findViewById(R.id.btn_ime_send)
        textImeReceived = findViewById(R.id.text_ime_received)
        switchAutoShowKeyboard = findViewById(R.id.switch_auto_show_keyboard)
        switchShowRareChars = findViewById(R.id.switch_show_rare_chars)
        switchVoiceInput = findViewById(R.id.switch_voice_input)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        bindCheckUpdate()
        cardMicPermission = findViewById(R.id.card_mic_permission)
        cardVoiceServer = findViewById(R.id.card_voice_server)
        btnDictManager = findViewById(R.id.btn_dict_manager)
        btnExportDiag = findViewById(R.id.btn_export_diag)
        textDiagDir = findViewById(R.id.text_diag_dir)

        // 剪贴板卡片
        clipboardPrefs = ClipboardPrefs.of(this)
        editClipboardMax = findViewById(R.id.edit_clipboard_max)

        textRootStatus = findViewById(R.id.text_root_status)
        switchRootEnhance = findViewById(R.id.switch_root_enhance)

        spinnerLanguage.adapter = ArrayAdapter.createFromResource(
            this, R.array.language_entries, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerDefaultMode.adapter = ArrayAdapter.createFromResource(
            this, R.array.default_mode_entries, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerDefaultMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                // 初始化时的 setSelection 同样会回调这里。若此时 prefs 里的值不在
                // values 中（如配置损坏或新增了模式），indexOf 会退回第 0 项，
                // 未加保护就会把用户的默认键盘**静默改成第 0 项**。
                if (!defaultModeSpinnerReady) return
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

        loadPrefs()
        // loadPrefs 内部的 setSelection 已回调过监听器，此后才是用户的真实选择
        defaultModeSpinnerReady = true
        refreshMicState()

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
        btnEnable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btnPick.setOnClickListener {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        }
        btnSave.setOnClickListener { saveAndRestart() }

        // 输入法测试：发送按钮 + 回车发送，文本回显到接收区并写诊断日志
        btnImeSend.setOnClickListener { sendImeTest() }
        // 自动唤起键盘：勾选即写入，立即生效（JinnIme.onShowInputRequested 每次实时读取）
        switchAutoShowKeyboard.setOnCheckedChangeListener { _, checked ->
            prefs.autoShowKeyboard = checked
            Diagnostics.i(TAG, "自动唤起键盘: ${if (checked) "开启" else "关闭"}")
        }
        // 生僻字开关：词库在 IME 进程启动时加载，这里只落盘，需重启输入法才生效
        switchShowRareChars.setOnCheckedChangeListener { _, checked ->
            prefs.showRareChars = checked
            Diagnostics.i(TAG, "显示生僻字: ${if (checked) "开启" else "关闭"}（重启输入法后生效）")
            toast(if (checked) R.string.rare_chars_on else R.string.rare_chars_off)
        }
        editImeTest.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendImeTest()
                true
            } else {
                false
            }
        }

        // 保活相关（前台服务 / 无障碍互保 / ROOT 白名单 / 电池白名单）已全部移除：
        // 语音输入改为按需连接后，不再需要进程常驻，也就不需要这些保活手段。

        // 诊断导出：把日志目录打包到共享存储，方便取出排查
        btnExportDiag.setOnClickListener { exportDiagnostics() }
        Diagnostics.currentLogDir?.let {
            textDiagDir.text = getString(R.string.settings_diag_dir_hint, it.absolutePath)
        }

        // ── 剪贴板卡片 ──────────────────────────────────────────
        initClipboardCard()

        // ── 扩展词库（长词包）────────────────────────────────────
        initDictEntry()
    }

    // ── 扩展词库（长词包）──────────────────────────────────────
    //
    // 完整词库 xz 后仍有 8MB、占 APK 体积 95%，其中 85.9% 的词条是三字以上的长尾
    // 专有名词。基础包（≤3 字）随 APK 分发，长词包放在这里按需下载，
    // 换来安装包从 8.4MB 降到 4.2MB。

    /** 分类词库入口：跳转到独立页面按需下载（长词包、专业词库等） */
    private fun initDictEntry() {
        btnDictManager.setOnClickListener {
            Diagnostics.i(TAG, "设置页: 打开分类词库")
            runCatching { startActivity(Intent(this, DictManagerActivity::class.java)) }
                .onFailure { Diagnostics.w(TAG, "打开分类词库失败: ${it.message}") }
        }
    }
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /**
     * 下载并安装扩展词库。
     *
     * 地址按「用户填写 → 内置默认源」依次尝试，任一个成功即停：
     * Gitee 源国内快，GitHub 源作备用（两个源都实测可访问）。
     *
     * 下载成功后**自动重启输入法进程**——词库只在 IME 启动时加载，重启才能合并生效。
     * 这样用户点一次按钮就走完「下载 → 安装 → 生效」，不用再去点顶部的重启按钮。
     */
    private fun initClipboardCard() {
        // 剪贴板历史强制启用：用户无需也无法关闭（核心功能，UI 不提供开关）
        if (!clipboardPrefs.enabled) {
            clipboardPrefs.enabled = true
            Diagnostics.i(TAG, "剪贴板历史: 强制启用")
        }

        editClipboardMax.setText(clipboardPrefs.maxItems.toString())
        editClipboardMax.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveMaxItems()
        }

        // 第三方 APP 访问权限管理（跳转系统应用信息或简单列表页）
        // 第三方 APP 访问权限管理：规范要求 JinnIme 不提供第三方读取 History API，此功能已移除。

        // ── Root 增强模式 ──────────────────────────────────────
        refreshRootStatus()
        switchRootEnhance.isChecked = clipboardPrefs.rootEnhanceEnabled
        switchRootEnhance.setOnCheckedChangeListener { _, checked ->
            // 程序化拨动开关（如 Root 不可用时自动关闭）不进入业务分支，避免递归
            if (suppressRootSwitchCallback) return@setOnCheckedChangeListener
            clipboardPrefs.rootEnhanceEnabled = checked
            Diagnostics.i(TAG, "Root 增强模式: ${if (checked) "开启" else "关闭"}")
            if (checked) {
                // 后台线程执行数据目录安全审计
                Thread {
                    if (!ClipboardFirewall.isRootAvailable()) {
                        runOnUiThread {
                            clipboardPrefs.rootEnhanceEnabled = false
                            // 抑制期间改 isChecked，避免递归进入 else 分支覆盖下面的提示
                            suppressRootSwitchCallback = true
                            switchRootEnhance.isChecked = false
                            suppressRootSwitchCallback = false
                            textRootStatus.text = getString(R.string.clipboard_root_unavailable)
                        }
                        return@Thread
                    }
                    val auditResults = ClipboardFirewall.audit(this)
                    runOnUiThread {
                        textRootStatus.text = auditResults.joinToString("\n") { "${it.first}: ${it.second}" }
                    }
                }.start()
            } else {
                textRootStatus.setText(R.string.clipboard_root_enhance_desc)
            }
        }
    }

    /** 刷新 Root 状态显示（后台线程检测，避免阻塞 UI） */
    private fun refreshRootStatus() {
        textRootStatus.text = getString(R.string.clipboard_root_status, getString(R.string.clipboard_root_unavailable))
        Thread {
            val ok = ClipboardFirewall.isRootAvailable()
            runOnUiThread {
                textRootStatus.text = getString(
                    R.string.clipboard_root_status,
                    getString(if (ok) R.string.clipboard_root_available else R.string.clipboard_root_unavailable),
                )
            }
        }.start()
    }

    private fun saveMaxItems() {
        val v = editClipboardMax.text.toString().toIntOrNull()
        if (v != null) {
            clipboardPrefs.maxItems = v
            Diagnostics.i(TAG, "剪贴板历史数量上限: ${clipboardPrefs.maxItems}")
            // 立即裁剪数据库（统一走 BackgroundIo 单线程，避免并发写库）
            BackgroundIo.run { ClipboardDb.get(this).trimTo(clipboardPrefs.maxItems) }
        } else {
            editClipboardMax.setText(clipboardPrefs.maxItems.toString())
        }
    }

    // 第三方 APP 访问权限管理已移除：规范要求 JinnIme 不提供第三方读取 History API。
    // 对应 ClipboardPermissionActivity / PermissionStore / Provider 已删除。

    /** 导出诊断包：zip 到 /storage/emulated/0/JinnIme/ 并提示路径 */
    private fun exportDiagnostics() {
        btnExportDiag.isEnabled = false
        Diagnostics.i(TAG, "exportDiagnostics: 开始导出诊断包")
        Thread {
            val file = Diagnostics.exportBundle(this)
            runOnUiThread {
                // 导出是后台任务，回调时 Activity 可能已销毁（避免操作已 detach 的 view）
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnExportDiag.isEnabled = true
                if (file != null) {
                    Diagnostics.i(TAG, "exportDiagnostics: 导出完成 ${file.absolutePath}")
                    textDiagDir.text = getString(R.string.settings_diag_exported, file.absolutePath)
                } else {
                    Diagnostics.w(TAG, "exportDiagnostics: 导出失败")
                    textDiagDir.text = getString(R.string.settings_diag_export_fail, "日志目录不可写")
                }
            }
        }.start()
    }

    private fun loadPrefs() {
        editHost.setText(prefs.host)
        editPort.setText(prefs.port.toString())
        editPrompt.setText(prefs.prompt)
        checkStrip.isChecked = prefs.stripTrailingPunc
        checkComposing.isChecked = prefs.useComposing
        switchAutoShowKeyboard.isChecked = prefs.autoShowKeyboard
        bindVoiceInputSwitch()

        switchShowRareChars.isChecked = prefs.showRareChars
        checkLockServer.isChecked = prefs.lockServer
        applyServerLock()
        val values = resources.getStringArray(R.array.language_values)
        spinnerLanguage.setSelection(values.indexOf(prefs.language).coerceAtLeast(0))
        val modeValues = resources.getStringArray(R.array.default_mode_values)
        spinnerDefaultMode.setSelection(
            modeValues.indexOf(prefs.defaultKeyboardMode.toString()).coerceAtLeast(0)
        )
    }

    /**
     * 固定 NAS 地址/端口：勾选后编辑框变灰不可编辑，防误触乱改。
     * 解锁（取消勾选）后可正常编辑。
     */
    /**
     * 绑定「语音输入」总开关。
     *
     * 关闭时语音相关区块（授权麦克风 + NAS 语音）一并隐藏 —— 此时语音在 IME 侧
     * 完全沉寂（不建实例、不连 WebSocket），这些配置项没有意义，显示出来只会误导。
     * 开关变更后需重启输入法进程才生效（与设置页其他项一致，由「保存并重启」触发）。
     */
    /** 更新检查五态（Idle / Checking / UpToDate / Available / NetworkError） */
    private enum class UpdateState { Idle, Checking, UpToDate, Available, NetworkError }

    /** Checking 高亮持续时长：到点自动熄灯，避免按钮常亮 */
    private val updateDimRunnable = Runnable { setUpdateState(UpdateState.Idle) }

    /**
     * 「检查更新」绑定。点击进入 Checking：禁用重复点击并高亮；
     * 结果由 [UpdateChecker] 回主线程后统一以对话框呈现。
     */
    private fun bindCheckUpdate() {
        btnCheckUpdate.setOnClickListener {
            if (updateState == UpdateState.Checking) return@setOnClickListener
            setUpdateState(UpdateState.Checking)
            UpdateChecker.checkAsync(BuildConfig.VERSION_CODE) { onUpdateChecked(it) }
        }
    }

    private fun setUpdateState(state: UpdateState) {
        updateState = state
        val checking = state == UpdateState.Checking
        btnCheckUpdate.isEnabled = !checking
        btnCheckUpdate.alpha = if (checking) 0.6f else 1f
        btnCheckUpdate.text = getString(
            if (checking) R.string.update_checking else R.string.settings_check_update
        )
        if (checking) btnCheckUpdate.postDelayed(updateDimRunnable, UPDATE_HIGHLIGHT_MS)
    }

    private fun onUpdateChecked(result: UpdateChecker.Result) {
        btnCheckUpdate.removeCallbacks(updateDimRunnable)
        updateState = when (result) {
            is UpdateChecker.Result.UpToDate -> UpdateState.UpToDate
            is UpdateChecker.Result.Available -> UpdateState.Available
            UpdateChecker.Result.NetworkError -> UpdateState.NetworkError
        }
        setUpdateState(UpdateState.Idle)
        showUpdateDialog(result)
    }

    /** 三类结果统一三套文案，措辞由 unified-update-check 约定，不得改写。 */
    private fun showUpdateDialog(result: UpdateChecker.Result) {
        val local = getString(R.string.update_local_version, BuildConfig.VERSION_CODE)
        val builder = AlertDialog.Builder(this)
        when (result) {
            is UpdateChecker.Result.Available -> builder
                .setTitle(R.string.update_title_available)
                .setMessage(
                    local + "\n" + getString(R.string.update_latest_version, result.latest)
                )
                .setNegativeButton(R.string.update_btn_later, null)
                .setPositiveButton(R.string.update_btn_go) { _, _ ->
                    openUrl(UpdateChecker.releasesUrl(result.latest, result.source))
                }

            is UpdateChecker.Result.UpToDate -> builder
                .setTitle(R.string.update_title_prompt)
                .setMessage(
                    local + "\n" +
                        getString(R.string.update_latest_version, result.latest) + "\n" +
                        getString(R.string.update_uptodate)
                )
                .setPositiveButton(R.string.update_btn_ok, null)

            UpdateChecker.Result.NetworkError -> builder
                .setTitle(R.string.update_title_prompt)
                .setMessage(local + "\n" + getString(R.string.update_latest_unreachable))
                .setPositiveButton(R.string.update_btn_ok, null)
        }
        builder.show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Diagnostics.w(TAG, "打开更新页失败: ${it.message}") }
    }

    private fun bindVoiceInputSwitch() {
        val enabled = prefs.voiceInputEnabled
        switchVoiceInput.isChecked = enabled
        applyVoiceBlocksVisibility(enabled)
        switchVoiceInput.setOnCheckedChangeListener { _, checked ->
            applyVoiceBlocksVisibility(checked)
        }
    }

    private fun applyVoiceBlocksVisibility(voiceEnabled: Boolean) {
        val visibility = if (voiceEnabled) View.VISIBLE else View.GONE
        cardMicPermission.visibility = visibility
        cardVoiceServer.visibility = visibility
    }

    private fun applyServerLock() {
        val locked = checkLockServer.isChecked
        editHost.isEnabled = !locked
        editPort.isEnabled = !locked
        // 置灰效果：enabled=false 时系统自动降低文字/背景透明度，
        // 再配合降低背景 alpha 让灰色更明显
        editHost.alpha = if (locked) 0.5f else 1f
        editPort.alpha = if (locked) 0.5f else 1f
        Diagnostics.i(TAG, "服务器配置固定: $locked")
    }

    private fun readLanguage(): String {
        val values = resources.getStringArray(R.array.language_values)
        val pos = spinnerLanguage.selectedItemPosition.coerceIn(0, values.lastIndex)
        return values[pos]
    }

    // ── 麦克风授权 ──────────────────────────────────────────────

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            refreshMicState()
            return
        }
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun refreshMicState() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val text = getString(if (granted) R.string.settings_mic_granted else R.string.settings_grant_mic)
        textMicState.text = text
        btnGrant.text = text
        btnGrant.isEnabled = !granted
    }

    // ── 输入法测试 ──────────────────────────────────────────

    private fun sendImeTest() {
        val text = editImeTest.text?.toString().orEmpty()
        Diagnostics.i(TAG, "imeTest: 发送文本 \"${text.take(80)}\" (共${text.length}字)")
        if (text.isBlank()) {
            textImeReceived.setText(R.string.settings_ime_received_empty)
            return
        }
        // 回显到接收区，作为"内部接收"的可见结果
        textImeReceived.text = getString(R.string.settings_ime_received, text)
        // 发送后清空输入框，方便连续测试；接收区展示最近一次发送
        editImeTest.setText("")
    }

    // ── 保存并重启输入法进程 ──────────────────────────────────────────

    /**
     * 保存全部永久配置并重启输入法进程：写入 SharedPreferences 后杀掉本进程，
     * 系统会自动重建 IME 服务按新配置初始化（连接、键盘方案、剪贴板、保活全部重载）。
     * 比发广播刷新更彻底，等价于「设置保存 + 输入法进程重启」。
     */
    private fun saveAndRestart() {
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().trim().toIntOrNull()

        // 语音关闭时 host/port 字段是隐藏的，校验没有意义且会阻塞保存
        val voiceEnabled = switchVoiceInput.isChecked
        if (voiceEnabled) {
            if (host.isBlank()) {
                Diagnostics.w(TAG, "saveAndRestart: host 为空")
                editHost.error = getString(R.string.settings_invalid_host)
                return
            }
            if (port == null || port !in 1..65535) {
                Diagnostics.w(TAG, "saveAndRestart: 端口非法 port=$port")
                editPort.error = getString(R.string.settings_invalid_port)
                return
            }
        }

        prefs.voiceInputEnabled = voiceEnabled
        prefs.host = host
        // 语音关闭时上面的校验被跳过，port 可能为 null，退回默认端口
        prefs.port = port ?: Prefs.DEFAULT_PORT
        prefs.language = readLanguage()
        prefs.prompt = editPrompt.text.toString()
        prefs.stripTrailingPunc = checkStrip.isChecked
        prefs.useComposing = checkComposing.isChecked
        // 剪贴板上限平时靠输入框失焦保存，但点「保存并重启」时失焦回调的时序不保证
        // 早于本次保存（输入框仍有焦点时可能压根没触发）。这里显式再存一次（幂等），
        // 避免用户改完上限点保存却发现没生效。
        saveMaxItems()

        Diagnostics.i(TAG, "saveAndRestart: 配置已保存 host=$host port=$port lang=${prefs.language} prompt=${prefs.prompt.take(30)}")
        textTest.setText(R.string.settings_restarting)

        // SharedPreferences apply 异步落盘：延迟片刻等写盘完成再杀进程，
        // 系统随后自动重启 IME 服务加载新配置，本 Activity 随进程一并结束。
        uiHandler.postDelayed({
            Diagnostics.i(TAG, "saveAndRestart: 重启输入法进程")
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 800L)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        Diagnostics.i(TAG, "onDestroy: 设置页销毁")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SettingsActivity"

        /** 「检查更新」按钮高亮时长：到点自动熄灭，避免常亮 */
        const val UPDATE_HIGHLIGHT_MS = 3_000L

        // 可选词库的文件名、下载源与体积/耗时说明已统一收敛到 OptionalDicts，
        // 由「分类词库」页使用；设置页只保留一个跳转入口。
    }
}
