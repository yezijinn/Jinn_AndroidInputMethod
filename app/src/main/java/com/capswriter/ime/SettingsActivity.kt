package com.capswriter.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
 * 设置页：配置飞牛 NAS 上的 CapsWriter 服务端地址、识别语言、提示词，
 * 引导授权麦克风、启用并切换到本输入法，并提供后台保活 / 防杀后台能力。
 * 同时作为应用入口从桌面启动。
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var spinnerLanguage: Spinner
    private lateinit var editPrompt: EditText
    private lateinit var checkStrip: CheckBox
    private lateinit var checkComposing: CheckBox
    private lateinit var btnGrant: Button
    private lateinit var btnEnable: Button
    private lateinit var btnPick: Button
    private lateinit var btnSave: Button
    private lateinit var textTest: TextView
    private lateinit var textMicState: TextView

    // 保活 / 防杀后台
    private lateinit var switchKeepAlive: Switch
    private lateinit var switchRoot: Switch
    private lateinit var switchNotifyHigh: Switch
    private lateinit var btnAccessibility: Button
    private lateinit var btnBattery: Button
    private lateinit var btnRoot: Button
    private lateinit var textKeepalive: TextView

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
        spinnerLanguage = findViewById(R.id.spinner_language)
        editPrompt = findViewById(R.id.edit_prompt)
        checkStrip = findViewById(R.id.check_strip)
        checkComposing = findViewById(R.id.check_composing)
        btnGrant = findViewById(R.id.btn_grant)
        btnEnable = findViewById(R.id.btn_enable)
        btnPick = findViewById(R.id.btn_pick)
        btnSave = findViewById(R.id.btn_save)
        textTest = findViewById(R.id.text_test)
        textMicState = findViewById(R.id.text_mic_state)

        switchKeepAlive = findViewById(R.id.switch_keepalive)
        switchRoot = findViewById(R.id.switch_root)
        switchNotifyHigh = findViewById(R.id.switch_notify_high)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnBattery = findViewById(R.id.btn_battery)
        btnRoot = findViewById(R.id.btn_root)
        textKeepalive = findViewById(R.id.text_keepalive)

        spinnerLanguage.adapter = ArrayAdapter.createFromResource(
            this, R.array.language_entries, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        loadPrefs()
        refreshMicState()

        btnGrant.setOnClickListener { requestMic() }
        btnEnable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btnPick.setOnClickListener {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        }
        btnSave.setOnClickListener { saveAndTest() }

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
        val values = resources.getStringArray(R.array.language_values)
        spinnerLanguage.setSelection(values.indexOf(prefs.language).coerceAtLeast(0))
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
