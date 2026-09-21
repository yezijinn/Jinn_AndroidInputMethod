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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
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
     * 主题应用点：**必须在这里**（早于 `onCreate` 与任何资源解析）。
     * 若放到 `onCreate` 里再 `setTheme`，会先按系统配置解析一帧再换色 —— 那就是"打开页面闪一下"的来源。
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

    // 输入法测试
    private lateinit var editImeTest: EditText
    private lateinit var btnImeSend: Button
    private lateinit var textImeReceived: TextView
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

    // 扩展词库（长词包）：不进 APK，按需下载
    private lateinit var btnDictManager: Button

    // 键盘外观：26 键区（3 行 28 键）统一圆角 / 间隙
    private lateinit var seekKeyCorner: SeekBar
    private lateinit var textKeyCorner: TextView
    private lateinit var seekKeyGap: SeekBar
    private lateinit var textKeyGap: TextView

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

    /**
     * 两个 Spinner 是否已被用户实际碰过。
     *
     * 不能只用「初始化是否完成」做闸门：`setSelection` 之外，Activity 恢复实例状态
     * （`onRestoreInstanceState`）也会触发 `onItemSelected`，而且是在 `onCreate` 返回之后——
     * 只靠 ready 标志挡不住，会把用户配置静默改成上次的临时选择。
     * 因此改为**只有用户触摸过 Spinner 才允许写配置**。
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

        editImeTest = findViewById(R.id.edit_ime_test)
        btnImeSend = findViewById(R.id.btn_ime_send)
        textImeReceived = findViewById(R.id.text_ime_received)
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
        btnDictManager = findViewById(R.id.btn_dict_manager)
        btnExportDiag = findViewById(R.id.btn_export_diag)
        textDiagDir = findViewById(R.id.text_diag_dir)

        // 剪贴板卡片
        clipboardPrefs = ClipboardPrefs.of(this)
        editClipboardMax = findViewById(R.id.edit_clipboard_max)

        textRootStatus = findViewById(R.id.text_root_status)
        switchRootEnhance = findViewById(R.id.switch_root_enhance)

        seekKeyCorner = findViewById(R.id.seek_key_corner)
        textKeyCorner = findViewById(R.id.text_key_corner)
        seekKeyGap = findViewById(R.id.seek_key_gap)
        textKeyGap = findViewById(R.id.text_key_gap)

        // 主题卡片：模式下拉 + 定时切换时刻；再记录本次生效的深浅色、排一次到点刷新
        initThemeCard()
        // 符号分组顺序：单按钮入口，打开独立排序页（设置主页只留一个按钮，不再内嵌列表）
        findViewById<Button>(R.id.btn_symbol_order).setOnClickListener {
            startActivity(Intent(this, SymbolOrderActivity::class.java))
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
                // 未加保护就会把用户的默认键盘**静默改成第 0 项**。
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

        // 输入方案下拉（**全局**，2026-09-20 起）：全拼 + 七套双拼（共 8 项，语音键盘不在此列）。
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
                // 双拼方案记忆：只在选双拼项时写（选全拼**不清记忆**——再选回双拼时回到上次那套）
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
        // 用户词频学习：勾选即写入并**立即生效**（不需要重启输入法）
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

        // ── 键盘外观（26 键区 28 键统一圆角 / 间隙）──────────────
        initKeyAppearanceCard()
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

    // ── 键盘外观（26 键区 28 键统一圆角 / 间隙）────────────────────

    /**
     * 两个滑杆：按键圆角 + 按键间隙，拖动即落盘并刷新数值文案。
     *
     * 键盘重绘在 IME 侧完成——[PinyinKeyboardView] 每次弹键盘（onStartInputView →
     * configure）都会按最新配置重新套用外观，所以这里不必发广播或重启进程；
     * 顶部「保存配置并立即重启生效」对这两项同样有效。
     *
     * 定义域与步进一律取自 [KeyAppearance]：界面与绘制逻辑共用一套边界，
     * 不允许在布局或 Activity 里另写一份范围。
     */
    /**
     * 主题卡片：模式下拉（跟随系统 / 亮白 / 暗黑 / 定时）+ 定时的两个切换时刻。
     *
     * 与另外两个下拉共用「只认用户触摸」的闸门（见 [themeSpinnerTouched]）：初始化 `setSelection`
     * 与实例状态恢复都会回调 `onItemSelected`，不挡住就会把用户配置写花。
     * 任一改动都**立即生效**：写盘后 `recreate()` —— `attachBaseContext` 会读到新主题重建整页颜色。
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
     * 只在「定时」模式排一次延时任务；到点重新解析，**结果变了才 `recreate()`** ——
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

    private fun initKeyAppearanceCard() {
        seekKeyCorner.max = KeyAppearance.CORNER_PROGRESS_MAX
        seekKeyGap.max = KeyAppearance.GAP_PROGRESS_MAX
        // 先写初值再挂监听：否则初始化写入也会触发一次无意义的回调
        seekKeyCorner.progress = KeyAppearance.cornerDpToProgress(prefs.keyCornerDp)
        seekKeyGap.progress = KeyAppearance.gapDpToProgress(prefs.keyGapDp)
        textKeyCorner.text = KeyAppearance.formatDp(prefs.keyCornerDp)
        textKeyGap.text = KeyAppearance.formatDp(prefs.keyGapDp)

        seekKeyCorner.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = KeyAppearance.cornerProgressToDp(progress)
                prefs.keyCornerDp = dp
                textKeyCorner.text = KeyAppearance.formatDp(dp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            // 只在松手时打一条日志：拖动过程每格都写日志会变成每秒几十次文件 IO
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Diagnostics.i(TAG, "键盘圆角: ${KeyAppearance.formatDp(prefs.keyCornerDp)}")
            }
        })

        seekKeyGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = KeyAppearance.gapProgressToDp(progress)
                prefs.keyGapDp = dp
                textKeyGap.text = KeyAppearance.formatDp(dp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Diagnostics.i(TAG, "键盘间隙: ${KeyAppearance.formatDp(prefs.keyGapDp)}")
            }
        })
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
        }
        // 输入越界（0 / 99999）会被 Prefs 钳到 1..9999，非数字则完全忽略——
        // 两种情况下输入框都回写为**真实生效值**，否则用户看到的和生效的不一致。
        val effective = clipboardPrefs.maxItems.toString()
        if (editClipboardMax.text.toString() != effective) {
            editClipboardMax.setText(effective)
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
        // 输入方案：按**当前生效方案**定位（全拼状态下选中「26 键全拼」，与全局语义一致）
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
     * 关闭时语音相关区块（授权麦克风 + NAS 语音）一并隐藏 —— 此时语音在 IME 侧
     * 完全沉寂（不建实例、不连 WebSocket），这些配置项没有意义，显示出来只会误导。
     * 开关变更后需重启输入法进程才生效（与设置页其他项一致，由「保存并重启」触发）。
     */
    /**
     * 更新检查状态：只区分「空闲 / 进行中」。
     *
     * 结果（有更新 / 已最新 / 网络错误）由对话框呈现——早先预留的
     * UpToDate / Available / NetworkError 三态从未被任何消费方读取（死状态），已移除。
     */
    private enum class UpdateState { Idle, Checking }

    /**
     * 请求看门狗：网络侧超时 10s，留 5s 余量。
     *
     * **不能**像早先那样 3s 就无条件熄灯复位：那会让按钮在请求仍在途时重新可用，
     * `updateState == Checking` 的防重入判据随之失效 —— 用户可并发发起第二次检查并重复弹窗。
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
     * 打开设置页时的自动检查：距上次**成功**检查 ≥7 天才执行一次。
     *
     * 只有「检测到新版本」才弹确认框（见 [showAskUpdateDialog]）；失败一律静默
     * （不弹「网络异常」），「已最新」也只复位按钮状态 —— 不打断用户操作。
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
     * 只记**成功**（有更新 / 已最新）：失败不写，下次打开设置页仍会静默重试；成功则在
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
     * 与手动检查的三段式对话框**分开**：后者措辞属 unified-update-check 约定（不得改写），
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

    // ── 输入法测试 ──────────────────────────────────────────

    private fun sendImeTest() {
        val text = editImeTest.text?.toString().orEmpty()
        Diagnostics.v(TAG, "imeTest: 发送文本 \"${text.take(80)}\" (共${text.length}字)")
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
        Diagnostics.i(TAG, "onDestroy: 设置页销毁")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SettingsActivity"

        /** 「检查更新」看门狗超时：网络超时 10s + 5s 余量，到点（仍处于 Checking 时）兜底解锁 */
        const val UPDATE_WATCHDOG_MS = 15_000L

        /** 自动检查的日志换算用（毫秒/天）；节流判据本体在 [UpdateChecker.shouldAutoCheck] */
        const val DAY_MS = 24L * 60 * 60 * 1000

        // 可选词库的文件名、下载源与体积/耗时说明已统一收敛到 OptionalDicts，
        // 由「分类词库」页使用；设置页只保留一个跳转入口。
    }
}
