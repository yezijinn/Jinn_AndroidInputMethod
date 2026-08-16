package com.jinn.voiceinput

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    // 保活 / 防杀后台
    private lateinit var switchKeepAlive: Switch
    private lateinit var switchRoot: Switch
    private lateinit var switchNotifyHigh: Switch
    private lateinit var btnAccessibility: Button
    private lateinit var btnBattery: Button
    private lateinit var btnRoot: Button
    private lateinit var textKeepalive: TextView

    // 诊断
    private lateinit var btnExportDiag: Button
    private lateinit var textDiagDir: TextView

    // 剪贴板
    private lateinit var clipboardPrefs: ClipboardPrefs
    private lateinit var editClipboardMax: EditText
    private lateinit var spinnerSensitivePolicy: Spinner
    private lateinit var spinnerSensitiveTemp: Spinner
    private lateinit var btnClipboardPerms: Button

    // Root 增强模式
    private lateinit var textRootStatus: TextView
    private lateinit var switchRootEnhance: Switch
    private lateinit var switchRootClearSensitive: Switch

    /** 仅用于"保存并测试连接"，用完即关，不干扰输入法自身的连接 */
    private var tester: AsrClient? = null

    /** Activity Result API 替代已弃用的 requestPermissions */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshMicState() }

    private val notifyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 通知权限结果不影响保活逻辑，仅作为常驻通知的前置授权
    }

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

        switchKeepAlive = findViewById(R.id.switch_keepalive)
        switchRoot = findViewById(R.id.switch_root)
        switchNotifyHigh = findViewById(R.id.switch_notify_high)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnBattery = findViewById(R.id.btn_battery)
        btnRoot = findViewById(R.id.btn_root)
        textKeepalive = findViewById(R.id.text_keepalive)
        btnExportDiag = findViewById(R.id.btn_export_diag)
        textDiagDir = findViewById(R.id.text_diag_dir)

        // 剪贴板卡片
        clipboardPrefs = ClipboardPrefs.of(this)
        editClipboardMax = findViewById(R.id.edit_clipboard_max)
        spinnerSensitivePolicy = findViewById(R.id.spinner_sensitive_policy)
        spinnerSensitiveTemp = findViewById(R.id.spinner_sensitive_temp)
        btnClipboardPerms = findViewById(R.id.btn_clipboard_perms)

        textRootStatus = findViewById(R.id.text_root_status)
        switchRootEnhance = findViewById(R.id.switch_root_enhance)
        switchRootClearSensitive = findViewById(R.id.switch_root_clear_sensitive)

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
        btnSave.setOnClickListener { saveAndTest() }

        // 输入法测试：发送按钮 + 回车发送，文本回显到接收区并写诊断日志
        btnImeSend.setOnClickListener { sendImeTest() }
        editImeTest.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendImeTest()
                true
            } else {
                false
            }
        }

        switchKeepAlive.setOnCheckedChangeListener { _, checked -> toggleKeepAlive(checked) }
        switchRoot.setOnCheckedChangeListener { _, checked -> prefs.useRootShizuku = checked }
        switchNotifyHigh.setOnCheckedChangeListener { _, checked ->
            prefs.notifyHighPriority = checked
            // 重启保活服务以重建通知通道
            if (prefs.keepAlive) {
                runCatching { KeepAliveService.stop(this) }
                runCatching { KeepAliveService.start(this) }
            }
        }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast(R.string.accessibility_open)
        }
        btnBattery.setOnClickListener {
            val uri = Uri.parse("package:$packageName")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri))
            toast(R.string.battery_open)
        }
        btnRoot.setOnClickListener { runRootKeepAlive() }

        // 诊断导出：把日志目录打包到共享存储，方便取出排查
        btnExportDiag.setOnClickListener { exportDiagnostics() }
        Diagnostics.currentLogDir?.let {
            textDiagDir.text = getString(R.string.settings_diag_dir_hint, it.absolutePath)
        }

        // ── 剪贴板卡片 ──────────────────────────────────────────
        initClipboardCard()
    }

    /** 初始化剪贴板卡片：历史数量 / 敏感策略 / 权限管理（剪贴板历史强制启用，无开关） */
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

        // 敏感内容策略：不保存 / 临时保存 / 按普通内容保存
        val policies = arrayOf(
            getString(R.string.clipboard_sensitive_never),
            getString(R.string.clipboard_sensitive_temp),
            getString(R.string.clipboard_sensitive_normal),
        )
        spinnerSensitivePolicy.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, policies
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerSensitivePolicy.setSelection(clipboardPrefs.sensitivePolicy)
        spinnerSensitivePolicy.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                if (position != clipboardPrefs.sensitivePolicy) {
                    clipboardPrefs.sensitivePolicy = position
                    Diagnostics.i(TAG, "敏感内容策略: $position")
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 临时保存时长（敏感内容策略 = 临时保存时有效）
        val durations = arrayOf(
            getString(R.string.clipboard_sensitive_temp_30s),
            getString(R.string.clipboard_sensitive_temp_5m),
            getString(R.string.clipboard_sensitive_temp_30m),
            getString(R.string.clipboard_sensitive_temp_1h),
        )
        spinnerSensitiveTemp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, durations
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerSensitiveTemp.setSelection(clipboardPrefs.sensitiveTempSeconds)
        spinnerSensitiveTemp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                if (position != clipboardPrefs.sensitiveTempSeconds) {
                    clipboardPrefs.sensitiveTempSeconds = position
                    Diagnostics.i(TAG, "敏感临时时长: $position")
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 第三方 APP 访问权限管理（跳转系统应用信息或简单列表页）
        btnClipboardPerms.setOnClickListener { openPermissionManager() }

        // ── Root 增强模式 ──────────────────────────────────────
        refreshRootStatus()
        switchRootEnhance.isChecked = clipboardPrefs.rootEnhanceEnabled
        switchRootEnhance.setOnCheckedChangeListener { _, checked ->
            clipboardPrefs.rootEnhanceEnabled = checked
            Diagnostics.i(TAG, "Root 增强模式: ${if (checked) "开启" else "关闭"}")
            if (checked) {
                // 后台线程检测 root；不可用则回退并提示
                Thread {
                    val ok = ClipboardFirewall.start(this)
                    runOnUiThread {
                        if (!ok) {
                            clipboardPrefs.rootEnhanceEnabled = false
                            switchRootEnhance.isChecked = false
                            toast(R.string.clipboard_root_unavailable)
                        }
                        refreshRootStatus()
                    }
                }.start()
            } else {
                ClipboardFirewall.stop()
                refreshRootStatus()
            }
        }
        switchRootClearSensitive.isChecked = clipboardPrefs.rootClearOnSensitive
        switchRootClearSensitive.setOnCheckedChangeListener { _, checked ->
            clipboardPrefs.rootClearOnSensitive = checked
            ClipboardFirewall.clearOnSensitive = checked
            Diagnostics.i(TAG, "敏感内容清空系统剪贴板: ${if (checked) "开" else "关"}")
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
            // 立即裁剪数据库
            Thread { ClipboardDb.get(this).trimTo(clipboardPrefs.maxItems) }.start()
        } else {
            editClipboardMax.setText(clipboardPrefs.maxItems.toString())
        }
    }

    /** 第三方 APP 访问权限管理：进入权限列表页 */
    private fun openPermissionManager() {
        Diagnostics.i(TAG, "openPermissionManager: 打开剪贴板权限管理")
        startActivity(Intent(this, ClipboardPermissionActivity::class.java))
    }

    /** 导出诊断包：zip 到 /storage/emulated/0/JinnIme/ 并提示路径 */
    private fun exportDiagnostics() {
        btnExportDiag.isEnabled = false
        Diagnostics.i(TAG, "exportDiagnostics: 开始导出诊断包")
        Thread {
            val file = Diagnostics.exportBundle(this)
            runOnUiThread {
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
        switchKeepAlive.isChecked = prefs.keepAlive
        switchRoot.isChecked = prefs.useRootShizuku
        switchNotifyHigh.isChecked = prefs.notifyHighPriority
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

    // ── 保活开关 ────────────────────────────────────────────────

    private fun toggleKeepAlive(checked: Boolean) {
        prefs.keepAlive = checked
        if (checked) {
            ensureNotifyPermission()
            runCatching { KeepAliveService.start(this) }
            toast(R.string.keepalive_on)
        } else {
            runCatching { KeepAliveService.stop(this) }
            toast(R.string.keepalive_off)
        }
    }

    /** Android 13+ 需通知权限才能弹出保活常驻通知 */
    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun runRootKeepAlive() {
        if (!prefs.useRootShizuku) {
            Diagnostics.w(TAG, "runRootKeepAlive: 未开启 Root/Shizuku 开关，直接提示")
            textKeepalive.setText(R.string.root_need)
            return
        }
        Diagnostics.i(TAG, "runRootKeepAlive: 开始执行防杀后台白名单命令")
        // Root/Shizuku 的命令执行走子进程，waitFor 会阻塞：放后台线程避免主线程 ANR
        val report: (Boolean) -> Unit = { ok ->
            Diagnostics.i(TAG, "runRootKeepAlive: 执行完成 ok=$ok")
            // 后台线程回调时 Activity 可能已销毁（超时 5s），避免操作已 detach 的 view
            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        textKeepalive.setText(if (ok) R.string.root_done else R.string.root_fail)
                    }
                }
            }
        }
        Thread {
            if (RootShizuku.shizukuReady()) {
                Diagnostics.i(TAG, "runRootKeepAlive: Shizuku 已就绪，走 Shizuku")
                report(RootShizuku.applyKeepAlive(this))
            } else {
                // 未授权 Shizuku：先申请，授权后执行；被拒则 applyKeepAlive 内部会退回 su
                Diagnostics.i(TAG, "runRootKeepAlive: Shizuku 未就绪，先申请授权")
                RootShizuku.requestShizukuPermission { granted ->
                    Diagnostics.i(TAG, "runRootKeepAlive: Shizuku 授权结果 granted=$granted")
                    report(RootShizuku.applyKeepAlive(this))
                }
            }
        }.start()
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

    // ── 保存并测试连接 ──────────────────────────────────────────

    private fun saveAndTest() {
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().trim().toIntOrNull()

        if (host.isBlank()) {
            Diagnostics.w(TAG, "saveAndTest: host 为空")
            editHost.error = getString(R.string.settings_invalid_host)
            return
        }
        if (port == null || port !in 1..65535) {
            Diagnostics.w(TAG, "saveAndTest: 端口非法 port=$port")
            editPort.error = getString(R.string.settings_invalid_port)
            return
        }

        prefs.host = host
        prefs.port = port
        prefs.language = readLanguage()
        prefs.prompt = editPrompt.text.toString()
        prefs.stripTrailingPunc = checkStrip.isChecked
        prefs.useComposing = checkComposing.isChecked

        Diagnostics.i(TAG, "saveAndTest: host=$host port=$port lang=${prefs.language} prompt=${prefs.prompt.take(30)}")
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()

        // 通知常驻输入法立即刷新（强制重连 + 键盘方案重载），无需重启进程。
        // prefs 写入是同步的，广播在同进程即时送达。
        runCatching {
            sendBroadcast(Intent(JinnIme.ACTION_CONFIG_UPDATED).setPackage(packageName))
            Diagnostics.i(TAG, "saveAndTest: 已发送配置更新广播")
        }.onFailure {
            Diagnostics.w(TAG, "saveAndTest: 发送配置广播失败 ${it.message}")
        }

        testConnection()
    }

    private fun testConnection() {
        textTest.text = getString(R.string.test_running, prefs.wsUrl)
        tester?.close()
        Diagnostics.i(TAG, "testConnection: 测试 ${prefs.wsUrl}")
        tester = AsrClient(
            prefs = prefs,
            onState = { state, detail ->
                // Activity 销毁后 IO 线程仍可能回调，避免 v 已 detach 时 view 操作抛异常
                if (isFinishing || isDestroyed) return@AsrClient
                Diagnostics.i(TAG, "testConnection: 状态变化 $state detail=$detail")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (state) {
                        LinkState.ONLINE -> textTest.setText(R.string.test_ok)
                        LinkState.OFFLINE -> textTest.text = getString(R.string.test_fail, detail ?: "")
                        else -> Unit
                    }
                }
            },
            onResult = {},
        )
        tester?.connect(force = true)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        Diagnostics.i(TAG, "onDestroy: 设置页销毁")
        tester?.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SettingsActivity"
    }
}
