package com.jinn.inputmethod

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 设置页：配置飞牛 NAS 上的 Jinn 服务端地址、识别语言、提示词，
 * 引导授权麦克风、启用并切换到本输入法。同时作为应用入口从桌面启动。
 */
class SettingsActivity : ComponentActivity() {

    /**
     * 主题应用点：必须在这里（早于 `onCreate` 与任何资源解析）。
     * 若放到 `onCreate` 里再 `setTheme`，会先按系统配置解析一帧再换色，那就是"打开页面闪一下"的来源。
     * 跟随系统时 [ThemeManager.themedContext] 原样返回 base，行为与改造前一致。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    private lateinit var prefs: Prefs

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var checkLockServer: CheckBox
    private lateinit var spinnerLanguage: Spinner
    private lateinit var spinnerDefaultMode: Spinner
    private lateinit var spinnerShuangpin: Spinner

    // 主题：亮白 / 暗黑 / 跟随系统 / 定时
    private lateinit var spinnerTheme: Spinner
    private lateinit var btnThemeLightAt: Button
    private lateinit var btnThemeDarkAt: Button
    private lateinit var rowThemeSchedule: View
    private lateinit var textThemeScheduleHint: TextView
    private lateinit var editPrompt: EditText
    private lateinit var checkStrip: CheckBox
    private lateinit var checkComposing: CheckBox
    private lateinit var btnGrant: Button
    private lateinit var btnEnable: Button
    private lateinit var btnPick: Button
    private lateinit var btnSave: Button
    private lateinit var textTest: TextView
    private lateinit var textMicState: TextView

    // 功能开关（自动唤起键盘 / 生僻字 / 词频学习 / 预测 / 语音）
    private lateinit var switchAutoShowKeyboard: Switch
    private lateinit var switchShowRareChars: Switch
    private lateinit var switchUserLearning: Switch
    private lateinit var switchPredict: Switch
    private lateinit var switchVoiceInput: Switch

    // 检查更新：版本号取构建日期，与远程 tag 比较
    private lateinit var btnCheckUpdate: Button
    /** 「打开下载页面」：与检查结果无关，直接跳 Gitee 发行版列表 */
    private lateinit var btnUpdateDownload: Button
    /** 更新检查状态：只区分「空闲 / 进行中」，结果由对话框呈现（见 [UpdateState]） */
    private var updateState: UpdateState = UpdateState.Idle
    /** 语音相关区块（授权麦克风 / NAS 语音）：随总开关动态隐藏 */
    private lateinit var cardMicPermission: View
    private lateinit var cardVoiceServer: View

    // Root 增强模式（剪贴板数据目录安全审计，与已移除的保活无关）

    // 诊断
    private lateinit var btnExportDiag: Button
    private lateinit var textDiagDir: TextView

    /** 待写入用户选定位置的诊断包（生成在缓存目录，写入或取消后删除） */
    private var pendingDiagZip: java.io.File? = null

    // 配置备份（导出 / 导入）
    private lateinit var btnConfigExport: Button
    private lateinit var btnConfigImport: Button
    private lateinit var textConfigHint: TextView

    /** 待写入用户选定位置的配置包（生成在缓存目录，写入或取消后删除） */
    private var pendingConfigZip: java.io.File? = null

    /** 已解密待导入的临时 zip（跨对话框保存；导入结束或取消后删除） */
    private var pendingImportZip: java.io.File? = null

    /** 处理期间的模态进度框（加密/解密/写入都可能耗时上秒，没有可见反馈用户会以为点了没反应） */
    private var busyDialog: AlertDialog? = null

    /**
     * 导入进行中标志（后台线程置假、主线程读，故 @Volatile）。
     *
     * 为什么需要：导入要按节多次重开同一个明文 zip，而旋转/回收会走 `onDestroy`；
     * 那里若把正在读的 zip 删掉，导入会以「包不完整/已损坏」这种完全不沾边的原因失败。
     */
    @Volatile
    private var importInFlight = false

    /** 三个配置流程对话框：旋转/回收时要显式 dismiss，否则窗口随 Activity 销毁而泄漏（WindowLeaked） */
    private var exportDialog: AlertDialog? = null
    private var importPwdDialog: AlertDialog? = null
    private var importConfirmDialog: AlertDialog? = null

    // 剪贴板
    private lateinit var clipboardPrefs: ClipboardPrefs
    private lateinit var editClipboardMax: EditText

    /** 主线程 Handler：保存配置后延迟片刻再杀进程重启输入法 */
    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * 两个 Spinner 是否已被用户实际碰过。
     *
     * 不能只用「初始化是否完成」做闸门：`setSelection` 之外，Activity 恢复实例状态
     * （`onRestoreInstanceState`）也会触发 `onItemSelected`，而且是在 `onCreate` 返回之后，
     * 只靠 ready 标志挡不住，会把用户配置静默改成上次的临时选择。
     * 因此改为只有用户触摸过 Spinner 才允许写配置。
     */
    private var defaultModeSpinnerTouched = false
    private var shuangpinSpinnerTouched = false

    /** 主题下拉：与上面两个 Spinner 共用「只认用户触摸」的闸门 */
    private var themeSpinnerTouched = false

    /** 本次进页面时生效的深浅色；定时到点后用它与重新解析的结果比较，变了才重建页面 */
    private var appliedThemeDark = false

    /** Activity Result API 替代已弃用的 requestPermissions */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshMicState() }

    /**
     * 诊断包导出：走系统文件选择器让用户自选保存位置（全程不申请存储权限）。
     *
     * 回调可能发生在 Activity 重建之后（进程被系统回收再恢复，ColorOS 上概率不低）：
     * 此时内存字段已丢，用缓存目录里最近的包兜底定位；两条异常分支都必须明确提示，
     * 否则会静默什么都不做，并在目标位置留下 0 字节空文件。
     */
    private val exportDiagLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val src = pendingDiagZip ?: Diagnostics.latestBundle(this)
        pendingDiagZip = null
        when {
            uri == null -> {
                Diagnostics.i(TAG, "exportDiagnostics: 用户取消导出（清理临时包 ${src?.name ?: "无"}）")
                src?.delete()
                resetDiagHint()
            }
            src == null || !src.isFile -> {
                Diagnostics.w(TAG, "exportDiagnostics: 临时包不存在（导出被中断）")
                textDiagDir.text = getString(R.string.settings_diag_export_fail, "导出被中断，请重试")
            }
            else -> copyDiagZipTo(uri, src)
        }
    }

    /**
     * 配置包导出：与诊断包同一套 SAF 流程（先在缓存目录打包，再让用户挑保存位置）。
     *
     * 回调可能发生在 Activity 重建之后（ColorOS 上概率不低）：内存字段已丢时用缓存目录里
     * 最近的包兜底定位；用户取消则清理临时包，避免缓存目录堆积。
     */
    private val exportConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val src = pendingConfigZip ?: ConfigBackupManager.latestBundle(this)
        pendingConfigZip = null
        when {
            uri == null -> {
                Diagnostics.i(TAG, "exportConfig: 用户取消导出（清理临时包 ${src?.name ?: "无"}）")
                src?.delete()
                textConfigHint.text = TEXT_EXPORT_CANCELED
            }
            src == null || !src.isFile -> {
                Diagnostics.w(TAG, "exportConfig: 临时包不存在（导出被中断）")
                textConfigHint.text = TEXT_EXPORT_FAIL
            }
            else -> copyConfigZipTo(uri, src)
        }
    }

    /** 配置包导入：走系统文件选择器读取（SAF，全程不申请存储权限） */
    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Diagnostics.i(TAG, "importConfig: 用户取消选择文件")
            textConfigHint.text = TEXT_IMPORT_CANCELED
            btnConfigImport.isEnabled = true
        } else {
            askImportPassword(uri)
        }
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
        spinnerShuangpin = findViewById(R.id.spinner_shuangpin)
        editPrompt = findViewById(R.id.edit_prompt)
        checkStrip = findViewById(R.id.check_strip)
        checkComposing = findViewById(R.id.check_composing)
        btnGrant = findViewById(R.id.btn_grant)
        btnEnable = findViewById(R.id.btn_enable)
        btnPick = findViewById(R.id.btn_pick)
        btnSave = findViewById(R.id.btn_save)
        textTest = findViewById(R.id.text_test)
        textMicState = findViewById(R.id.text_mic_state)

        switchAutoShowKeyboard = findViewById(R.id.switch_auto_show_keyboard)
        switchShowRareChars = findViewById(R.id.switch_show_rare_chars)
        switchUserLearning = findViewById(R.id.switch_user_learning)
        switchPredict = findViewById(R.id.switch_predict)
        switchVoiceInput = findViewById(R.id.switch_voice_input)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        btnUpdateDownload = findViewById(R.id.btn_update_download)
        bindCheckUpdate()
        // 打开设置页时的自动检查：距上次成功检查 ≥7 天才执行（失败静默，见 maybeAutoCheckUpdate）
        maybeAutoCheckUpdate()
        cardMicPermission = findViewById(R.id.card_mic_permission)
        cardVoiceServer = findViewById(R.id.card_voice_server)
        btnExportDiag = findViewById(R.id.btn_export_diag)
        textDiagDir = findViewById(R.id.text_diag_dir)

        // 剪贴板卡片
        clipboardPrefs = ClipboardPrefs.of(this)
        editClipboardMax = findViewById(R.id.edit_clipboard_max)

        // 主题卡片：模式下拉 + 定时切换时刻；再记录本次生效的深浅色、排一次到点刷新
        initThemeCard()
        // 收藏符号编辑：单按钮入口，打开独立编辑页
        findViewById<Button>(R.id.btn_edit_favorites).setOnClickListener {
            startActivity(Intent(this, FavoriteSymbolsActivity::class.java))
        }
        // 符号分组顺序：单按钮入口，打开独立排序页（设置主页只留一个按钮，不再内嵌列表）
        findViewById<Button>(R.id.btn_symbol_order).setOnClickListener {
            startActivity(Intent(this, SymbolOrderActivity::class.java))
        }
        // 补充短语词库：独立页面按需下载长词包 / 专业词库
        findViewById<Button>(R.id.btn_dict_expand).setOnClickListener {
            Diagnostics.i(TAG, "设置页: 打开分类词库")
            runCatching { startActivity(Intent(this, DictManagerActivity::class.java)) }
                .onFailure { Diagnostics.w(TAG, "打开分类词库失败: ${it.message}") }
        }
        // 按钮圆角间隙（键盘外观）：独立页面
        findViewById<Button>(R.id.btn_key_appearance).setOnClickListener {
            startActivity(Intent(this, KeyAppearanceActivity::class.java))
        }
        appliedThemeDark = ThemeManager.isDark(this)
        scheduleThemeTick()

        spinnerLanguage.adapter = ArrayAdapter.createFromResource(
            this, R.array.language_entries, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerDefaultMode.adapter = ArrayAdapter.createFromResource(
            this, R.array.default_mode_entries, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // 用户触摸过才允许写配置（见字段说明）；ACTION_UP 补 performClick 供无障碍服务识别
        spinnerDefaultMode.setOnTouchListener { view, event ->
            defaultModeSpinnerTouched = true
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerDefaultMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                // 初始化 setSelection（以及恢复实例状态）都会回调这里。若此时 prefs 里的值不在
                // values 中（如配置损坏或新增了模式），indexOf 会退回第 0 项，
                // 未加保护就会把用户的默认键盘静默改成第 0 项。
                if (!defaultModeSpinnerTouched) return
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

        // 输入方案下拉（全局，2026-09-20 起）：全拼 + 七套双拼（共 8 项，语音键盘不在此列）。
        // 选中即全局生效：选全拼 = 关闭双拼；选某套双拼 = 记住该方案并启用。
        // 按键面板不再提供「全拼 / 双拼」切换按钮，这里是唯一的输入方案入口。
        // 列表取自 [ShuangpinScheme.ALL]，与引擎共用同一份数据，不会脱节。
        spinnerShuangpin.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            ShuangpinScheme.ALL.map { it.displayName },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerShuangpin.setOnTouchListener { view, event ->
            shuangpinSpinnerTouched = true
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerShuangpin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                // 与「默认键盘模式」同一个坑：初始化 setSelection 与恢复实例状态都会回调，
                // 不挡住就会把用户已选的方案静默改掉（真机上实测被改写过）。
                if (!shuangpinSpinnerTouched) return
                val scheme = ShuangpinScheme.ALL.getOrNull(position) ?: return
                prefs.useShuangpin = scheme.isShuangpin
                // 双拼方案记忆：只在选双拼项时写（选全拼不清记忆，再选回双拼时回到上次那套）
                if (scheme.isShuangpin) prefs.shuangpinScheme = scheme.prefsValue
                Diagnostics.i(TAG, "输入方案: ${scheme.displayName}（双拼=${scheme.isShuangpin}）")
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        loadPrefs()
        // 之后 Spinner 若回调（含恢复实例状态），只有「用户触摸过」才会写配置
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
        // 用户词频学习：勾选即写入并立即生效（不需要重启输入法）
        switchUserLearning.setOnCheckedChangeListener { _, checked ->
            prefs.userLearning = checked
            UserFrequency.setEnabled(checked)
            Diagnostics.i(TAG, "用户词频学习: ${if (checked) "开启" else "关闭"}（立即生效）")
        }
        // 候选预测词：勾选即写入；键盘每次要用预测时读 pref，因此立即生效
        switchPredict.setOnCheckedChangeListener { _, checked ->
            prefs.predictEnabled = checked
            Diagnostics.i(TAG, "候选预测词: ${if (checked) "开启" else "关闭"}（立即生效）")
        }
        // 保活相关（前台服务 / 无障碍互保 / ROOT 白名单 / 电池白名单）已全部移除：
        // 语音输入改为按需连接后，不再需要进程常驻，也就不需要这些保活手段。

        // 诊断导出：把日志目录打包到共享存储，方便取出排查
        btnExportDiag.setOnClickListener { exportDiagnostics() }

        // 配置备份：导出 / 导入（跨设备迁移）。按钮文本在代码里下发——strings.xml 默认禁改
        btnConfigExport = findViewById(R.id.btn_config_export)
        btnConfigImport = findViewById(R.id.btn_config_import)
        textConfigHint = findViewById(R.id.text_config_hint)
        btnConfigExport.text = TEXT_EXPORT_CONFIG
        btnConfigImport.text = TEXT_IMPORT_CONFIG
        textConfigHint.text = TEXT_CONFIG_HINT
        btnConfigExport.setOnClickListener { showExportConfigDialog() }
        btnConfigImport.setOnClickListener {
            // 先禁用再打开选择器：连点会连开两个选择器，第二份结果会覆盖 pendingImportZip 的引用，
            // 使「取消时删掉明文临时包」的守卫失效（清理只能等下次解锁或启动清扫兜底）
            btnConfigImport.isEnabled = false
            importConfigLauncher.launch(ConfigBackupManager.OPEN_MIME_TYPES)
        }
        // 上次异常中断留下的明文中间件（导出 zip / 导入临时 zip）在这里补清：
        // 进程被杀不会跑任何收尾代码，只能等下次进设置页
        BackgroundIo.run { ConfigBackupManager.cleanupStaleCache(this@SettingsActivity) }
        // 页面重建期间完成的后台任务（导出/解密/导入）会把结论留在这里，进入页面时补提示
        pendingNotice?.let { notice ->
            pendingNotice = null
            alert("提示", notice)
        }
        Diagnostics.currentLogDir?.let {
            textDiagDir.text = getString(R.string.settings_diag_dir_hint, it.absolutePath)
        }

        // ── 剪贴板卡片 ──────────────────────────────────────────
        initClipboardCard()
    }

    /**
     * 主题卡片：模式下拉（跟随系统 / 亮白 / 暗黑 / 定时）+ 定时的两个切换时刻。
     *
     * 与另外两个下拉共用「只认用户触摸」的闸门（见 [themeSpinnerTouched]）：初始化 `setSelection`
     * 与实例状态恢复都会回调 `onItemSelected`，不挡住就会把用户配置写花。
     * 任一改动都立即生效：写盘后 `recreate()`，`attachBaseContext` 会读到新主题重建整页颜色。
     */
    private fun initThemeCard() {
        spinnerTheme = findViewById(R.id.spinner_theme)
        btnThemeLightAt = findViewById(R.id.btn_theme_light_at)
        btnThemeDarkAt = findViewById(R.id.btn_theme_dark_at)
        rowThemeSchedule = findViewById(R.id.row_theme_schedule)
        textThemeScheduleHint = findViewById(R.id.text_theme_schedule_hint)

        spinnerTheme.adapter = ArrayAdapter.createFromResource(
            this, R.array.theme_mode_entries, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerTheme.setOnTouchListener { view, event ->
            themeSpinnerTouched = true
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                if (!themeSpinnerTouched) return
                val values = resources.getStringArray(R.array.theme_mode_values)
                val mode = values.getOrNull(position)?.toIntOrNull() ?: return
                if (mode != prefs.themeMode) {
                    prefs.themeMode = mode
                    Diagnostics.i(TAG, "主题模式: $mode")
                    JinnIme.notifyThemeChanged() // 键盘正显示时同进程立即换肤（不必等下次弹出）
                    recreate() // 写盘即生效：重建页面让 attachBaseContext 读到新主题
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnThemeLightAt.setOnClickListener {
            pickThemeTime(prefs.themeLightAtMinutes) { minutes ->
                prefs.themeLightAtMinutes = minutes
                Diagnostics.i(TAG, "定时切换: 亮起 ${formatMinutes(minutes)}")
                JinnIme.notifyThemeChanged()
                recreate()
            }
        }
        btnThemeDarkAt.setOnClickListener {
            pickThemeTime(prefs.themeDarkAtMinutes) { minutes ->
                prefs.themeDarkAtMinutes = minutes
                Diagnostics.i(TAG, "定时切换: 暗起 ${formatMinutes(minutes)}")
                JinnIme.notifyThemeChanged()
                recreate()
            }
        }

        // 回填当前值（recreate 之后走的也是这里）
        val modeValues = resources.getStringArray(R.array.theme_mode_values)
        spinnerTheme.setSelection(modeValues.indexOf(prefs.themeMode.toString()).coerceAtLeast(0))
        refreshThemeScheduleRow()
    }

    /** 定时行只在「定时」模式显示；两个按钮的文案随配置刷新 */
    private fun refreshThemeScheduleRow() {
        val scheduled = prefs.themeMode == ThemeManager.MODE_SCHEDULED
        val visibility = if (scheduled) View.VISIBLE else View.GONE
        rowThemeSchedule.visibility = visibility
        textThemeScheduleHint.visibility = visibility
        btnThemeLightAt.text = getString(R.string.theme_light_at_tpl, formatMinutes(prefs.themeLightAtMinutes))
        btnThemeDarkAt.text = getString(R.string.theme_dark_at_tpl, formatMinutes(prefs.themeDarkAtMinutes))
    }

    /** 「当天第几分钟」→ `HH:mm`（脏值先 floorMod 归一，负值不会显示成 -1:-30） */
    private fun formatMinutes(minutes: Int): String {
        val m = Math.floorMod(minutes, ThemeManager.MINUTES_PER_DAY)
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** 弹时间选择器；回调参数为「当天第几分钟」（0..1439） */
    private fun pickThemeTime(initialMinutes: Int, onPicked: (Int) -> Unit) {
        val m = Math.floorMod(initialMinutes, ThemeManager.MINUTES_PER_DAY)
        android.app.TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            m / 60,
            m % 60,
            true,
        ).show()
    }

    /**
     * 定时模式：停在设置页时到点自动换色（不必等下一次操作或重开页面）。
     *
     * 只在「定时」模式排一次延时任务；到点重新解析，结果变了才 `recreate()` ，
     * 没变（例如刚过切换点）就继续排下一次，不会无谓地重建页面。
     */
    private val themeTickRunnable = Runnable {
        val dark = ThemeManager.isDark(this)
        if (dark != appliedThemeDark) {
            Diagnostics.i(TAG, "定时切换到点: 主题转为 ${if (dark) "暗黑" else "亮白"}")
            recreate()
        } else {
            scheduleThemeTick()
        }
    }

    private fun scheduleThemeTick() {
        uiHandler.removeCallbacks(themeTickRunnable)
        val minutes = ThemeManager.minutesUntilSwitch(
            prefs.themeMode,
            prefs.themeLightAtMinutes,
            prefs.themeDarkAtMinutes,
            ThemeManager.nowMinutes(),
        )
        if (minutes <= 0) return // 非定时模式 / 无效配置
        // +1s 余量：刚好卡在切换点上时避免边界抖动
        uiHandler.postDelayed(themeTickRunnable, minutes * 60_000L + 1000L)
    }

    /**
     * 剪贴板卡片初始化：强制启用历史、绑定数量上限、接 Root 增强开关。
     * 历史功能无独立开关，这里直接强制 enabled=true；上限在失焦或「保存并重启」时落盘。
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

        // 第三方 APP 访问权限管理已移除：规范要求 JinnIme 不提供第三方读取 History API。
    }

    private fun saveMaxItems() {
        val v = editClipboardMax.text.toString().toIntOrNull()
        if (v != null) {
            clipboardPrefs.maxItems = v
            Diagnostics.i(TAG, "剪贴板历史数量上限: ${clipboardPrefs.maxItems}")
            // 立即裁剪数据库（统一走 BackgroundIo 单线程，避免并发写库）
            BackgroundIo.run { ClipboardDb.get(this).trimTo(clipboardPrefs.maxItems) }
        }
        // 输入越界（0 / 99999）会被 Prefs 钳到 1..9999，非数字则完全忽略，
        // 两种情况下输入框都回写为真实生效值，否则用户看到的和生效的不一致。
        val effective = clipboardPrefs.maxItems.toString()
        if (editClipboardMax.text.toString() != effective) {
            editClipboardMax.setText(effective)
        }
    }

    // 第三方 APP 访问权限管理已移除：规范要求 JinnIme 不提供第三方读取 History API。
    // 对应 ClipboardPermissionActivity / PermissionStore / Provider 已删除。

    /** 提示行恢复为「日志目录：…」（用户取消导出、未产生结果时用） */
    private fun resetDiagHint() {
        Diagnostics.currentLogDir?.let {
            textDiagDir.text = getString(R.string.settings_diag_dir_hint, it.absolutePath)
        }
    }

    /**
     * 导出诊断包：先在缓存目录打包（零权限），再弹系统文件选择器让用户挑保存位置。
     *
     * 打包与写入都走后台线程（SAF 的 OutputStream 可能较慢）；两处都保留 Activity
     * 销毁守卫，避免操作已 detach 的 view。
     */
    private fun exportDiagnostics() {
        btnExportDiag.isEnabled = false
        Diagnostics.i(TAG, "exportDiagnostics: 开始打包诊断包")
        Thread {
            val file = Diagnostics.exportBundle(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnExportDiag.isEnabled = true
                if (file != null) {
                    pendingDiagZip = file
                    exportDiagLauncher.launch(file.name)
                } else {
                    Diagnostics.w(TAG, "exportDiagnostics: 打包失败")
                    textDiagDir.text = getString(R.string.settings_diag_export_fail, "日志目录不可读")
                }
            }
        }.start()
    }

    /** 把临时诊断包写入用户选定的位置（SAF），写完即删临时文件 */
    private fun copyDiagZipTo(uri: Uri, src: java.io.File) {
        // 提示里用自己生成的文件名：部分 provider 的 lastPathSegment 是文档 ID（如 "18"），对用户没有意义
        val displayName = src.name
        Thread {
            val ok = runCatching {
                openTruncatingOutput(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } != null
            }.getOrDefault(false)
            src.delete()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    Diagnostics.i(TAG, "exportDiagnostics: 导出完成 ${safeUriLabel(uri)}")
                    textDiagDir.text = getString(R.string.settings_diag_exported, displayName)
                } else {
                    Diagnostics.w(TAG, "exportDiagnostics: 写入失败")
                    textDiagDir.text = getString(R.string.settings_diag_export_fail, "写入失败")
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
        switchUserLearning.isChecked = prefs.userLearning
        switchPredict.isChecked = prefs.predictEnabled
        checkLockServer.isChecked = prefs.lockServer
        applyServerLock()
        val values = resources.getStringArray(R.array.language_values)
        spinnerLanguage.setSelection(values.indexOf(prefs.language).coerceAtLeast(0))
        val modeValues = resources.getStringArray(R.array.default_mode_values)
        spinnerDefaultMode.setSelection(
            modeValues.indexOf(prefs.defaultKeyboardMode.toString()).coerceAtLeast(0)
        )
        // 输入方案：按当前生效方案定位（全拼状态下选中「26 键全拼」，与全局语义一致）
        spinnerShuangpin.setSelection(
            ShuangpinScheme.ALL.indexOf(prefs.effectiveShuangpinScheme).coerceAtLeast(0)
        )
    }

    /**
     * 固定 NAS 地址/端口：勾选后编辑框变灰不可编辑，防误触乱改。
     * 解锁（取消勾选）后可正常编辑。
     */
    /**
     * 绑定「语音输入」总开关。
     *
     * 关闭时语音相关区块（授权麦克风 + NAS 语音）一并隐藏，此时语音在 IME 侧
     * 完全沉寂（不建实例、不连 WebSocket），这些配置项没有意义，显示出来只会误导。
     * 开关变更后需重启输入法进程才生效（与设置页其他项一致，由「保存并重启」触发）。
     */
    /**
     * 更新检查状态：只区分「空闲 / 进行中」。
     *
     * 结果（有更新 / 已最新 / 网络错误）由对话框呈现，早先预留的
     * UpToDate / Available / NetworkError 三态从未被任何消费方读取（死状态），已移除。
     */
    private enum class UpdateState { Idle, Checking }

    /**
     * 请求看门狗：网络侧超时 10s，留 5s 余量。
     *
     * 不能像早先那样 3s 就无条件熄灯复位：那会让按钮在请求仍在途时重新可用，
     * `updateState == Checking` 的防重入判据随之失效，用户可并发发起第二次检查并重复弹窗。
     * 正常情况结果必达（[onUpdateChecked] 负责解锁），看门狗只在回调极端丢失时兜底。
     */
    private val updateWatchdogRunnable = Runnable {
        if (updateState == UpdateState.Checking) {
            Diagnostics.w(TAG, "检查更新超时未返回，看门狗解锁按钮")
            setUpdateState(UpdateState.Idle)
        }
    }

    /**
     * 「检查更新」绑定。点击进入 Checking：禁用重复点击并高亮；
     * 结果由 [UpdateChecker] 回主线程后统一以对话框呈现。
     * 右侧「打开下载页面」与检查结果无关，直接跳 Gitee 发行版列表。
     */
    private fun bindCheckUpdate() {
        btnCheckUpdate.setOnClickListener {
            if (updateState == UpdateState.Checking) return@setOnClickListener
            setUpdateState(UpdateState.Checking)
            UpdateChecker.checkAsync(BuildConfig.VERSION_CODE) { onUpdateChecked(it) }
        }
        btnUpdateDownload.setOnClickListener {
            val url = UpdateChecker.downloadPageUrl()
            Diagnostics.i(TAG, "打开下载页面: $url")
            openUrl(url)
        }
    }

    /**
     * 打开设置页时的自动检查：距上次成功检查 ≥7 天才执行一次。
     *
     * 只有「检测到新版本」才弹确认框（见 [showAskUpdateDialog]）；失败一律静默
     * （不弹「网络异常」），「已最新」也只复位按钮状态，不打断用户操作。
     */
    private fun maybeAutoCheckUpdate() {
        val now = System.currentTimeMillis()
        val last = prefs.updateLastCheckAt
        if (!UpdateChecker.shouldAutoCheck(last, now)) {
            Diagnostics.i(TAG, "自动检查更新: 距上次 ${(now - last) / DAY_MS} 天，跳过")
            return
        }
        // 与手动检查共用状态机：进行中不重入，避免并发请求与重复弹窗
        if (updateState == UpdateState.Checking) return
        val since = if (last <= 0L) "从未检查" else "${(now - last) / DAY_MS} 天前"
        Diagnostics.i(TAG, "自动检查更新: 开始（上次=$since）")
        setUpdateState(UpdateState.Checking)
        UpdateChecker.checkAsync(BuildConfig.VERSION_CODE) { onAutoChecked(it) }
    }

    private fun setUpdateState(state: UpdateState) {
        updateState = state
        val checking = state == UpdateState.Checking
        btnCheckUpdate.isEnabled = !checking
        btnCheckUpdate.alpha = if (checking) 0.6f else 1f
        btnCheckUpdate.text = getString(
            if (checking) R.string.update_checking else R.string.settings_check_update
        )
        btnCheckUpdate.removeCallbacks(updateWatchdogRunnable)
        if (checking) btnCheckUpdate.postDelayed(updateWatchdogRunnable, UPDATE_WATCHDOG_MS)
    }

    /** 手动检查：结果由三段式对话框呈现（措辞属 unified-update-check 约定，不得改写） */
    private fun onUpdateChecked(result: UpdateChecker.Result) {
        btnCheckUpdate.removeCallbacks(updateWatchdogRunnable)
        recordUpdateCheckTime(result)
        // 检查是后台线程 + 10s 网络超时：结果回来时页面可能已关闭或已重建。
        // 拿已销毁的 Activity 去 show() 会抛 BadTokenException（主线程崩溃）。
        if (isFinishing || isDestroyed) {
            Diagnostics.i(TAG, "检查更新结果已到达，但页面已销毁，跳过弹窗")
            return
        }
        // 结果本身由对话框呈现：这里只需解锁（早先先赋 UpToDate/Available/NetworkError、
        // 紧接着又被 Idle 覆盖，是没有任何消费方的死代码，已随本次修复移除）。
        setUpdateState(UpdateState.Idle)
        showUpdateDialog(result)
    }

    /** 自动检查：失败/已最新一律静默，只有检测到新版本才弹「有新版本，要更新吗？」 */
    private fun onAutoChecked(result: UpdateChecker.Result) {
        btnCheckUpdate.removeCallbacks(updateWatchdogRunnable)
        recordUpdateCheckTime(result)
        if (isFinishing || isDestroyed) {
            Diagnostics.i(TAG, "自动检查更新结果已到达，但页面已销毁，跳过弹窗")
            return
        }
        setUpdateState(UpdateState.Idle)
        when (result) {
            is UpdateChecker.Result.Available -> showAskUpdateDialog(result)
            is UpdateChecker.Result.UpToDate ->
                Diagnostics.i(TAG, "自动检查更新: 已是最新 ${result.latest}（静默）")
            UpdateChecker.Result.NetworkError ->
                Diagnostics.w(TAG, "自动检查更新: 网络异常（静默）")
        }
    }

    /**
     * 记下本次检查时刻，供 7 天节流使用（判据见 [UpdateChecker.shouldAutoCheck]）。
     *
     * 只记成功（有更新 / 已最新）：失败不写，下次打开设置页仍会静默重试；成功则在
     * 间隔内不再自动检查。写入的是 SharedPreferences（apply 异步落盘），不阻塞主线程。
     */
    private fun recordUpdateCheckTime(result: UpdateChecker.Result) {
        if (result is UpdateChecker.Result.NetworkError) return
        prefs.updateLastCheckAt = System.currentTimeMillis()
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

    /**
     * 自动检查发现新版本时的确认框：一个问句 + 「不要」/「去更新」。
     *
     * 与手动检查的三段式对话框分开：后者措辞属 unified-update-check 约定（不得改写），
     * 本对话框只服务「打开设置页自动检查」这条路径。
     */
    private fun showAskUpdateDialog(result: UpdateChecker.Result.Available) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_title_prompt)
            .setMessage(R.string.update_ask_message)
            .setNegativeButton(R.string.update_btn_no, null)
            .setPositiveButton(R.string.update_btn_go) { _, _ ->
                openUrl(UpdateChecker.releasesUrl(result.latest, result.source))
            }
            .show()
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
            // 非法 host 会被拼成 HttpUrl 拒绝的地址，进而在主线程抛异常把 IME 打崩；
            // 这里拦在保存入口，配合 Prefs 的兜底形成两道防线（语音链路禁改，不能在那里兜）。
            if (!Prefs.isValidHost(host)) {
                Diagnostics.w(TAG, "saveAndRestart: host 含非法字符，已拒绝保存")
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

        // 提示词是用户自己写的正文（可能含个人信息），只记长度不记内容
        Diagnostics.i(TAG, "saveAndRestart: 配置已保存 host=$host port=$port lang=${prefs.language} promptLen=${prefs.prompt.length}")
        textTest.setText(R.string.settings_restarting)

        // SharedPreferences apply 异步落盘：延迟片刻等落盘完成再杀进程，
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
        // 定时刷新的延时任务必须随页面撤销，否则会持有已销毁的 Activity
        uiHandler.removeCallbacks(themeTickRunnable)
        // 进度框不能随页面销毁留下（WindowLeaked）；后台任务回来时另有 isFinishing 守卫兜底
        dismissBusy()
        // 三个配置流程对话框同理：它们是代码创建的，旋转重建时不会自动恢复
        exportDialog?.dismiss()
        exportDialog = null
        importPwdDialog?.dismiss()
        importPwdDialog = null
        importConfirmDialog?.dismiss()
        importConfirmDialog = null
        // 明文临时包不跨页面生命周期：页面销毁（含旋转重建）时一并清掉。
        // 但导入正在进行时不能删——后台线程还要按节重开它；这种残留由下次进设置页的清扫兜底
        if (!importInFlight) {
            pendingImportZip?.delete()
            pendingImportZip = null
        }
        Diagnostics.i(TAG, "onDestroy: 设置页销毁")
        super.onDestroy()
    }

    // ── 配置备份：导出 / 导入 ────────────────────────────────

    /**
     * 导出前的选项对话框：剪贴板明文默认不勾（隐私），词库默认勾（换省事），
     * 外加两次密码输入 —— 整包加密，密码只能用汉字且本机不保存。
     */
    private fun showExportConfigDialog() {
        val cbClipboard = CheckBox(this).apply {
            text = TEXT_INCLUDE_CLIPBOARD
            isChecked = false
        }
        val cbDicts = CheckBox(this).apply {
            text = TEXT_INCLUDE_DICTS
            isChecked = true
        }
        val pwd1 = plainTextField(TEXT_PWD_HINT)
        val pwd2 = plainTextField(TEXT_PWD_HINT_CONFIRM)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpOf(20), dpOf(4), dpOf(20), dpOf(4))
            addView(
                TextView(this@SettingsActivity).apply {
                    text = TEXT_EXPORT_DESC
                    textSize = 13f
                }
            )
            addView(pwd1, lpOf(10))
            addView(pwd2, lpOf(6))
            addView(cbClipboard, lpOf(10))
            addView(cbDicts, lpOf(2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_EXPORT_CONFIG)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始导出", null)
            .create()
        dialog.show()
        exportDialog = dialog
        // 自校验不过时必须让对话框留在原地（默认 positive 会先 dismiss 再回调，密码就白输了）
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p1 = pwd1.text.toString()
            val p2 = pwd2.text.toString()
            val check = ConfigCrypto.validatePassword(p1)
            when {
                // 同时给「输入框红字」与「Toast」：EditText 的 error 气泡几秒就消失，
                // 慢机 + 亮屏下很容易被当成「点了没反应」
                check is ConfigCrypto.PasswordCheck.Invalid -> {
                    pwd1.error = check.reason
                    Toast.makeText(this, check.reason, Toast.LENGTH_LONG).show()
                }
                p1 != p2 -> {
                    pwd2.error = TEXT_PWD_MISMATCH
                    Toast.makeText(this, TEXT_PWD_MISMATCH, Toast.LENGTH_LONG).show()
                }
                else -> {
                    dialog.dismiss()
                    doExportConfig(cbClipboard.isChecked, cbDicts.isChecked, p1)
                }
            }
        }
    }

    private fun doExportConfig(includeClipboard: Boolean, includeDicts: Boolean, password: String) {
        btnConfigExport.isEnabled = false
        textConfigHint.text = TEXT_EXPORTING
        showBusy(TEXT_BUSY_EXPORT)
        Diagnostics.i(TAG, "exportConfig: 开始打包并加密（剪贴板=$includeClipboard 词库=$includeDicts）")
        // 密码以 CharArray 传进加解密层，用完立刻清零；全程不写日志、不落盘
        val chars = password.toCharArray()
        Thread {
            // 兜底：导出链路上还有 SQLite 与文件 IO（剪贴板分页、词库摘要），漏网异常会连带
            // 杀死同进程的输入法（设置页与 IME 同进程），所以这里必须整体包住
            val outcome = runCatching {
                ConfigBackupManager.export(
                    this@SettingsActivity,
                    ConfigBackupManager.ExportOptions(includeClipboard, includeDicts, chars),
                )
            }.onFailure {
                Diagnostics.w(TAG, "exportConfig: 打包异常 ${it.javaClass.simpleName}")
            }.getOrNull()
            chars.fill('\u0000')
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    // 页面已销毁：把结论留下，重建后补提示（否则用户不知道这次导出到底成没成）
                    pendingNotice = if (outcome == null) {
                        ConfigBackupManager.lastError ?: TEXT_EXPORT_FAIL
                    } else {
                        "配置包已生成，但页面被重建，请重新点「导出配置」选择保存位置。"
                    }
                    return@runOnUiThread
                }
                dismissBusy()
                btnConfigExport.isEnabled = true
                if (outcome == null) {
                    // 具体原因由 Manager 记在 lastError（如「剪贴板历史过大」），比通用文案有用得多
                    val reason = ConfigBackupManager.lastError ?: TEXT_EXPORT_FAIL
                    Diagnostics.w(TAG, "exportConfig: 打包或加密失败（$reason）")
                    textConfigHint.text = reason
                    alert(TEXT_EXPORT_CONFIG, reason)
                } else {
                    pendingConfigZip = outcome.file
                    val extra =
                        if (outcome.clipDropped > 0) "（剪贴板 ${outcome.clipDropped} 条因超限/超预算未导出）" else ""
                    textConfigHint.text = "已生成加密备份包，请选择保存位置$extra"
                    exportConfigLauncher.launch(outcome.file.name)
                }
            }
        }.start()
    }

    /**
     * 以「截断写」打开 SAF 输出流。
     *
     * 必须带 `"wt"`：默认的 `"w"` 在部分 provider（云盘/文档型）上不保证截断，覆盖一个更大的
     * 旧文件时会在尾部留下残骸 —— 用户看到的是「密码错误或文件已损坏」这种完全误导的提示。
     * 少数 provider 不认 `"wt"`，退回默认 mode（行为与从前一致，不会更差）。
     */
    private fun openTruncatingOutput(uri: Uri): java.io.OutputStream? =
        runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { contentResolver.openOutputStream(uri) }.getOrNull()

    /**
     * 只保留 `scheme://authority` 的 URI 标签。
     *
     * 完整 uri 可能带账号/目录名（`content://com.xxx.docs/某目录/...`），而日志与诊断包正是
     * 用户拿去求助、可能上传的东西 —— 不该把这类信息写进去。
     */
    private fun safeUriLabel(uri: Uri): String = "${uri.scheme ?: "?"}://${uri.authority ?: "?"}"

    /** 把临时配置包写入用户选定的位置（SAF），写完即删临时文件 */
    private fun copyConfigZipTo(uri: Uri, src: java.io.File) {
        val displayName = src.name
        showBusy(TEXT_BUSY_WRITE)
        Thread {
            val ok = runCatching {
                openTruncatingOutput(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } != null
            }.getOrDefault(false)
            src.delete()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dismissBusy()
                if (ok) {
                    Diagnostics.i(TAG, "exportConfig: 导出完成 ${safeUriLabel(uri)}")
                    textConfigHint.text = "配置已导出：$displayName"
                    alert(TEXT_EXPORT_CONFIG, "已导出：$displayName\n\n请妥善保管该文件与密码：忘记密码无法解密。")
                } else {
                    Diagnostics.w(TAG, "exportConfig: 写入失败")
                    textConfigHint.text = TEXT_EXPORT_FAIL
                    // 覆盖写会一开始就把目标截断：失败时用户的原文件可能已经空了，必须提示到
                    alert(
                        TEXT_EXPORT_CONFIG,
                        "$TEXT_EXPORT_FAIL\n\n若您选择的是已存在的文件，该文件可能已被清空，请重新导出一次。",
                    )
                }
            }
        }.start()
    }

    /**
     * 导入第一步：先审密码（明文输入框，刻意不触发系统安全键盘）。
     *
     * 备份整包加密，密码不对就什么都拿不到（连包里的设备信息也看不到），
     * 所以密码必须在最前面 —— 这是「配置 / 剪贴板过于敏感」的第一道门。
     */
    private fun askImportPassword(uri: Uri) {
        val pwd = plainTextField(TEXT_PWD_HINT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpOf(20), dpOf(4), dpOf(20), dpOf(4))
            addView(
                TextView(this@SettingsActivity).apply {
                    text = TEXT_IMPORT_PWD_DESC
                    textSize = 13f
                }
            )
            addView(pwd, lpOf(10))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_IMPORT_PWD_TITLE)
            .setView(box)
            .setNegativeButton("取消") { _, _ ->
                // 取消要复位入口按钮并给出反馈，否则提示行会停在上一次的失败文案上（误导）
                btnConfigImport.isEnabled = true
                textConfigHint.text = TEXT_IMPORT_CANCELED
            }
            .setPositiveButton("解密", null)
            .create()
        dialog.show()
        importPwdDialog = dialog
        // 返回键 / 点对话框外面也要复位入口按钮：这两种取消不会触发 negative 回调，
        // 漏掉的话按钮会永久停在禁用态、提示行永久停在「正在解密…」
        dialog.setOnCancelListener {
            btnConfigImport.isEnabled = true
            textConfigHint.text = TEXT_IMPORT_CANCELED
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = pwd.text.toString()
            val check = ConfigCrypto.validatePassword(value)
            if (check is ConfigCrypto.PasswordCheck.Invalid) {
                pwd.error = check.reason
                Toast.makeText(this, check.reason, Toast.LENGTH_LONG).show()
            } else {
                dialog.dismiss()
                unlockConfigBackup(uri, value)
            }
        }
    }

    /** 解密（PBKDF2 很慢，必须在后台线程）→ 成功后弹清单确认框，失败给出可重试的弹框 */
    private fun unlockConfigBackup(uri: Uri, password: String) {
        btnConfigImport.isEnabled = false
        textConfigHint.text = TEXT_DECRYPTING
        showBusy(TEXT_BUSY_DECRYPT)
        val chars = password.toCharArray()
        Thread {
            val result = runCatching { ConfigBackupManager.unlock(this@SettingsActivity, uri, chars) }
                .onFailure { Diagnostics.w(TAG, "importConfig: 解锁异常 ${it.javaClass.simpleName}") }
                .getOrElse { ConfigBackupManager.UnlockResult.ReadFailed }
            chars.fill('\u0000')
            val zip = (result as? ConfigBackupManager.UnlockResult.Ok)?.zip
            val info = zip?.let { runCatching { ConfigBackupManager.inspect(it) }.getOrNull() }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    zip?.delete()
                    pendingNotice = "已通过密码校验，但页面被重建；请重新点「导入配置」。"
                    return@runOnUiThread
                }
                dismissBusy()
                btnConfigImport.isEnabled = true
                when {
                    // 「读不到」与「解不开」必须分开提示：把文件被移走/授权失效说成密码错，
                    // 用户会反复重输密码，而真正的原因在别处
                    result is ConfigBackupManager.UnlockResult.ReadFailed -> {
                        Diagnostics.w(TAG, "importConfig: 无法读取所选文件")
                        textConfigHint.text = TEXT_READ_FAIL
                        alert(TEXT_IMPORT_CONFIG, TEXT_READ_FAIL)
                    }
                    result is ConfigBackupManager.UnlockResult.ReadTimeout -> {
                        Diagnostics.w(TAG, "importConfig: 读取备份文件超时")
                        textConfigHint.text = TEXT_READ_TIMEOUT
                        alert(TEXT_IMPORT_CONFIG, TEXT_READ_TIMEOUT)
                    }
                    zip == null -> {
                        Diagnostics.w(TAG, "importConfig: 解密失败")
                        textConfigHint.text = TEXT_DECRYPT_FAIL
                        // 失败必须有明确弹框（原来只改一行小灰字，用户看不到会以为整个流程坏了），
                        // 并给一个「重新输入密码」的直达入口，免得再从文件选择器走一遍
                        alert(TEXT_IMPORT_PWD_TITLE, TEXT_DECRYPT_FAIL) { askImportPassword(uri) }
                    }
                    info == null -> {
                        // 已解密但清单不可识别（manifest 骨架不符）：密码是对的，引导重输只会让人怀疑自己
                        zip.delete()
                        Diagnostics.w(TAG, "importConfig: 包格式无法识别")
                        textConfigHint.text = TEXT_FORMAT_UNKNOWN
                        alert(TEXT_IMPORT_CONFIG, TEXT_FORMAT_UNKNOWN)
                    }
                    else -> {
                        pendingImportZip = zip
                        showImportConfigDialog(zip, info)
                    }
                }
            }
        }.start()
    }

    private fun showImportConfigDialog(zip: java.io.File, info: ConfigBackupManager.BackupInfo) {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(info.manifest.createdAt))
        val hasClipboard = info.clipCount > 0
        val hasDicts = info.dictNames.isNotEmpty()

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbRestore = RadioButton(this).apply {
            id = View.generateViewId()
            text = TEXT_MODE_RESTORE
        }
        val rbMerge = RadioButton(this).apply {
            id = View.generateViewId()
            text = TEXT_MODE_MERGE
            isChecked = true
        }
        group.addView(rbRestore)
        group.addView(rbMerge)
        val cbClipboard = CheckBox(this).apply {
            text = "导入剪贴板历史（${info.clipCount} 条）"
            isChecked = hasClipboard
            isEnabled = hasClipboard
        }
        val cbDicts = CheckBox(this).apply {
            text = "导入已下载词库（${info.dictNames.size} 个）"
            isChecked = hasDicts
            isEnabled = hasDicts
        }
        val summary = buildString {
            append("备份时间：").append(time).append('\n')
            append("来源版本：").append(info.manifest.appVersionCode)
            append("（本机 ").append(BuildConfig.VERSION_CODE).append("）\n")
            append("来源设备：").append(info.manifest.device).append('\n')
            append("包含：设置 ").append(info.prefsKeys).append(" 项、词频 ")
                .append(info.freqCount).append(" 条、剪贴板 ")
                .append(info.clipCount).append(" 条、词库 ")
                .append(info.dictNames.size).append(" 个")
            if (info.tooNew) append("\n\n该备份由更新版本的 App 生成，本机版本无法导入。")
            if (info.hasOversized) {
                append("\n\n包内的 ").append(info.oversizedSections.joinToString("、"))
                    .append(" 超过单节读入上限或无法读取，导入会被拒绝（通常是包被手工改过）。")
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpOf(20), dpOf(4), dpOf(20), dpOf(4))
            addView(
                TextView(this@SettingsActivity).apply {
                    text = summary
                    textSize = 13f
                }
            )
            addView(group, lpOf(8))
            addView(cbClipboard, lpOf(4))
            addView(cbDicts, lpOf(2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_IMPORT_CONFIG)
            .setView(box)
            .setNegativeButton("取消") { _, _ ->
                // 取消也要删掉明文临时 zip，不让它在缓存里留着
                if (pendingImportZip === zip) pendingImportZip = null
                zip.delete()
                textConfigHint.text = TEXT_IMPORT_CANCELED
            }
            .setPositiveButton("开始导入") { _, _ ->
                doImportConfig(
                    zip = zip,
                    mode = if (rbRestore.isChecked) {
                        ConfigBackupManager.ImportMode.RESTORE
                    } else {
                        ConfigBackupManager.ImportMode.MERGE_DATA
                    },
                    includeClipboard = cbClipboard.isChecked,
                    includeDicts = cbDicts.isChecked,
                )
            }
            .create()
        dialog.show()
        importConfirmDialog = dialog
        // 返回键、点到对话框外面都走 cancel（**不会**触发 negative 按钮的点击回调）：
        // 明文临时 zip 必须在这里也删掉，否则它会一直留在缓存目录里
        dialog.setOnCancelListener {
            if (pendingImportZip === zip) pendingImportZip = null
            zip.delete()
            // 与 negative 同口径：否则提示行会永久停在上一句「正在解密…」
            textConfigHint.text = TEXT_IMPORT_CANCELED
        }
        // 两种情况都只允许查看清单：包由更新版生成，或包内有过大的节（导入端必然拒绝，
        // 提前禁用能省掉一次「输完密码才失败」）
        if (info.tooNew || info.hasOversized) dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }

    private fun doImportConfig(
        zip: java.io.File,
        mode: ConfigBackupManager.ImportMode,
        includeClipboard: Boolean,
        includeDicts: Boolean,
    ) {
        btnConfigImport.isEnabled = false
        textConfigHint.text = TEXT_IMPORTING
        showBusy(TEXT_BUSY_IMPORT)
        // 标记进行中：此时 onDestroy 不能删明文 zip（后台线程还要读它）
        importInFlight = true
        Thread {
            // 兜底：导入会写 SQLite / 词频 / prefs，任一处漏网异常都会连带杀死同进程的输入法
            val r = runCatching {
                ConfigBackupManager.import(
                    this@SettingsActivity, zip, mode, includeClipboard, includeDicts,
                )
            }.onFailure {
                Diagnostics.w(TAG, "importConfig: 导入异常 ${it.javaClass.simpleName}")
            }.getOrNull()
            // 明文临时 zip 用完即删（成功失败都删）：它是本次导入唯一的明文副本
            zip.delete()
            if (pendingImportZip === zip) pendingImportZip = null
            importInFlight = false
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    // 最危险的一种：导入其实已经落盘，用户却什么都没看到，多半会以为失败再导一次
                    pendingNotice = if (r == null) {
                        TEXT_IMPORT_FAIL
                    } else {
                        "配置导入已完成（写入的设置与词库需重启输入法后生效）。"
                    }
                    return@runOnUiThread
                }
                dismissBusy()
                btnConfigImport.isEnabled = true
                if (r == null) {
                    Diagnostics.w(TAG, "importConfig: 导入失败")
                    textConfigHint.text = TEXT_IMPORT_FAIL
                    alert(TEXT_IMPORT_CONFIG, TEXT_IMPORT_FAIL)
                    return@runOnUiThread
                }
                val parts = ArrayList<String>()
                if (mode == ConfigBackupManager.ImportMode.RESTORE) parts.add("设置 ${r.prefsApplied} 项")
                parts.add("词频 ${r.freqBefore}→${r.freqAfter} 条")
                if (includeClipboard) parts.add("剪贴板 +${r.clipAdded} 条")
                if (includeDicts) parts.add("词库 +${r.dictsWritten} 个")
                // 静默失败是最坏的一类问题：被忽略/跳过的数量必须让用户看见
                if (r.prefsIgnored > 0) parts.add("忽略 ${r.prefsIgnored} 项")
                if (includeClipboard && r.clipSkipped > 0) parts.add("剪贴板跳过 ${r.clipSkipped} 条")
                if (includeDicts && r.dictsSkipped > 0) parts.add("词库跳过 ${r.dictsSkipped} 个")
                // 部分完成必须如实说：前面的阶段已落盘且不回滚，重试是幂等的
                if (r.failedStage != null) parts.add("${r.failedStage}（可重试）")
                val summary = parts.joinToString("、")
                textConfigHint.text =
                    if (r.failedStage == null) "导入完成：$summary" else "导入部分完成：$summary"
                // 需要重启的两类：覆盖还原动了设置项；或写入了词库（词库只在 IME 启动时加载）
                val needRestart = (mode == ConfigBackupManager.ImportMode.RESTORE && r.prefsApplied > 0) ||
                    (includeDicts && r.dictsWritten > 0)
                val builder = AlertDialog.Builder(this)
                    .setTitle("导入完成")
                    .setMessage(summary + if (needRestart) "\n\n写入的设置与词库需重启输入法后生效。" else "")
                if (needRestart) {
                    // 选「稍后」时刷新本页：设置已落盘，页面上的开关/输入框要显示新值而不是旧值
                    builder.setNegativeButton("稍后") { _, _ -> recreate() }
                        .setPositiveButton("重启输入法") { _, _ -> restartImeProcess() }
                } else {
                    builder.setPositiveButton("好", null)
                }
                builder.show()
            }
        }.start()
    }

    /** 先把设置写实、再延迟杀进程：系统随后会自动重建 IME 服务 */
    private fun restartImeProcess() {
        Diagnostics.i(TAG, "restartImeProcess: 重启输入法进程")
        // `apply()` 是异步的：只靠延时 800ms 赌它写完，IO 压力大时会丢最后一批键
        // （进程被直接杀时不走 onPause/onStop，没有别的时机能替我们等）
        // 失败要留痕：磁盘满时表现为「重启后设置回退」，没有日志就无从排查
        if (!runCatching { Prefs(this).flush() }.getOrDefault(false)) {
            Diagnostics.w(TAG, "restartImeProcess: 主设置落盘失败")
        }
        if (!runCatching { ClipboardPrefs.of(this).flush() }.getOrDefault(false)) {
            Diagnostics.w(TAG, "restartImeProcess: 剪贴板设置落盘失败")
        }
        uiHandler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 800L)
    }

    /**
     * 处理期间的模态进度框。
     *
     * 为什么必须有：密钥派生按 OWASP 量级取 600k 次 PBKDF2，手机上要 1~2 秒；SAF 写入、
     * 词库解包也可能更久。这段空窗期里如果界面上什么都没有（原来只改一行 11sp 的灰色小字），
     * 用户会以为点了没反应、甚至重复点击。模态框 + 不确定进度条把「正在进行」摆到台面上，
     * 结束（成功或失败）时由 [dismissBusy] 统一关闭。
     */
    private fun showBusy(message: String) {
        if (isFinishing || isDestroyed) return
        dismissBusy()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpOf(20), dpOf(16), dpOf(20), dpOf(16))
            addView(
                TextView(this@SettingsActivity).apply {
                    text = message
                    textSize = 14f
                }
            )
            addView(bar, lpOf(14))
        }
        busyDialog = AlertDialog.Builder(this)
            .setTitle(TEXT_BUSY_TITLE)
            .setView(box)
            .setCancelable(false)
            .create()
        busyDialog?.show()
    }

    private fun dismissBusy() {
        busyDialog?.dismiss()
        busyDialog = null
    }

    /** 结果提示统一走模态框：一行灰色小字在慢机/亮屏下太容易被忽略 */
    private fun alert(title: String, message: String, action: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this).setTitle(title).setMessage(message)
        if (action == null) {
            builder.setPositiveButton("好", null)
        } else {
            builder.setNegativeButton("关闭", null).setPositiveButton("重新输入密码") { _, _ -> action() }
        }
        builder.show()
    }

    private fun dpOf(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun lpOf(topDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dpOf(topDp) }

    /**
     * 明文输入框（刻意**不用**密码类型）。
     *
     * 备份密码只能用汉字，而 `textPassword` / `textVisiblePassword` 一类在部分 ROM 上会唤起
     * 系统的「安全键盘」，安全键盘打不出拼音汉字。这里用普通文本 + `TYPE_TEXT_FLAG_NO_SUGGESTIONS`：
     * 内容可见、能正常调用本输入法打汉字，同时让键盘侧的敏感度判定生效
     * （不做联想、不记词频，见 [InputFieldPrivacy]）。
     */
    private fun plainTextField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        maxLines = 1
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        // 顺序有讲究：`maxLines` 一类属性会回头改写 inputType（单行化会清标志），
        // 而 `setRawInputType` 不走那套单行处理，能保证「普通文本 + 不联想」原样送达输入法。
        // NO_SUGGESTIONS 是给键盘侧看的：本项目键盘据此不联想、不记词频（见 InputFieldPrivacy）。
        // imeOptions 会重写 TYPE_MASK_FLAGS 那一批位，必须放在 setRawInputType **之前**，
        // 否则会把刚设好的 NO_SUGGESTIONS 抹掉（隐私效果靠 imeOptions 通道仍在，但少一层保险）
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }

    private companion object {
        const val TAG = "SettingsActivity"

        /**
         * 进程级「待提示结果」。
         *
         * 后台任务可能跨越页面重建（旋转、系统回收后恢复）才回来，这时结果没人展示。
         * 把结论留在这里、由新实例的 onCreate 消费，用户至少不会「什么都没看到就以为失败」
         * （导入尤其危险：数据其实已经落盘，用户重导一次等于白覆盖一遍）。
         */
        @Volatile
        private var pendingNotice: String? = null

        // ── 配置备份文案（strings.xml 默认禁改，文案收敛在此处） ──
        const val TEXT_EXPORT_CONFIG = "导出配置"
        const val TEXT_IMPORT_CONFIG = "导入配置"
        const val TEXT_CONFIG_HINT = "导出为加密备份包（AES-256 整包加密，密码只能用中文汉字）；导入需输入该密码"
        const val TEXT_EXPORT_DESC = "导出内容：全部设置项、用户词频，以及下面勾选的附加数据。\n" +
            "设置项里还包含连接设置（服务器地址/端口）与语音提示词，包内另记录来源设备型号与 App 版本；" +
            "分享给他人前请先确认。\n" +
            "备份包用密码加密，密码只能用中文汉字、4~64 个字（建议 6 个字以上）；密码不保存在本机，忘记后无法解密。"
        const val TEXT_INCLUDE_CLIPBOARD =
            "包含剪贴板历史（在包内以明文保存，仅靠整包密码保护；可能含身份证、手机号、地址、银行卡、账号口令）"
        const val TEXT_INCLUDE_DICTS = "包含已下载的可选词库（体积较大）"
        const val TEXT_PWD_HINT = "备份密码（只能用汉字，4~64 字，别用同一个字重复）"
        const val TEXT_PWD_HINT_CONFIRM = "再输一次备份密码"
        const val TEXT_PWD_MISMATCH = "两次输入的密码不一致"
        const val TEXT_EXPORTING = "正在打包并加密…"
        const val TEXT_EXPORT_FAIL = "导出失败，请重试"
        const val TEXT_IMPORT_PWD_TITLE = "输入备份密码"
        const val TEXT_IMPORT_PWD_DESC = "该备份已加密。请输入导出时设置的密码（只能用汉字）。\n" +
            "密码错误或文件被改动都无法解密，这是加密包的保护机制。"
        const val TEXT_DECRYPTING = "正在解密（密钥校验中，请稍候）…"
        const val TEXT_DECRYPT_FAIL = "密码错误或文件已损坏，无法解密"
        const val TEXT_EXPORT_CANCELED = "已取消导出"
        const val TEXT_IMPORT_CANCELED = "已取消导入"
        const val TEXT_BUSY_TITLE = "请稍候"
        const val TEXT_BUSY_EXPORT = "正在打包并加密配置…\n（密钥派生按安全强度计算，约需 1~2 秒，请勿退出）"
        const val TEXT_BUSY_DECRYPT = "正在解密并校验密码…\n（约需 1~2 秒，请勿退出）"
        const val TEXT_BUSY_IMPORT = "正在导入配置与数据…"
        const val TEXT_BUSY_WRITE = "正在写入文件…"
        const val TEXT_READ_FAIL = "无法读取所选文件：可能已被移走、授权已失效，或不是本应用的加密备份包" +
            "（也可能是文件超过 256MB 上限）"
        const val TEXT_READ_TIMEOUT = "读取备份文件超时（网络盘或文件过大）：请把文件保存到本机后重试"
        const val TEXT_FORMAT_UNKNOWN = "备份内容无法识别：可能由不兼容的版本生成，或文件已被改动"
        const val TEXT_IMPORT_FAIL = "导入失败：备份包不完整/已损坏，或写入本机失败（如存储空间不足）"
        const val TEXT_IMPORTING = "正在导入…"
        const val TEXT_MODE_RESTORE = "覆盖还原（设置与词频按备份写回，剪贴板并入本机历史）"
        const val TEXT_MODE_MERGE = "仅并入数据（只合并词频、剪贴板与词库，保留本机设置）"

        /** 「检查更新」看门狗超时：网络超时 10s + 5s 余量，到点（仍处于 Checking 时）兜底解锁 */
        const val UPDATE_WATCHDOG_MS = 15_000L

        /** 自动检查的日志换算用（毫秒/天）；节流判据本体在 [UpdateChecker.shouldAutoCheck] */
        const val DAY_MS = 24L * 60 * 60 * 1000

        // 可选词库的文件名、下载源与体积/耗时说明已统一收敛到 OptionalDicts，
        // 由「分类词库」页使用；设置页只保留一个跳转入口。
    }
}
