package com.jinn.inputmethod

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * 设置页：各功能入口 + 启用 / 切换输入法 + 配置备份与诊断导出，同时作为应用入口从桌面启动。
 *
 * 语音（总开关 / 授权麦克风 / 服务端地址与识别选项）2026-11-01 起收在 [VoiceSettingsActivity]：
 * 本页只留「语音转为文字」入口按钮（与「绑定悬浮键盘」同行，状态小字已删），\n * 别再把那几块卡片搬回来。
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

    private lateinit var spinnerShuangpin: UserAwareSpinner

    private lateinit var btnEnable: Button
    private lateinit var btnPick: Button
    private lateinit var btnSave: Button
    private lateinit var textTest: TextView

    /** 「语音转为文字」入口按钮（与「绑定悬浮键盘」同行；文案在代码里下发） */
    private lateinit var btnVoiceSettings: Button

    /** 底部使用说明（六行纯文本，文案在 [TEXT_USAGE] 里下发） */
    private lateinit var textUsage: TextView

    // 功能开关（自动唤起键盘 / 词频学习 / 预测 / 键面韵母提示 / 拼音声韵显示）
    private lateinit var switchAutoShowKeyboard: Switch
    private lateinit var switchUserLearning: Switch
    private lateinit var switchPredict: Switch

    /** 在线翻译（BYOK）：总开关 + 入口按钮 + 状态摘要（文案全部代码下发） */
    private lateinit var switchTranslate: Switch
    private lateinit var btnTranslateSettings: Button
    private lateinit var textTranslateState: TextView

    /** 模糊音容错 / 加更多生僻字入口按钮（文案在代码里下发：strings.xml 默认不改动） */
    private lateinit var btnFuzzyPinyin: Button

    /** 「只使用繁体字」胶囊开关（2026-09-27 起：候选里的简体字全部替换为繁体字；与「自动唤起键盘」同行） */
    private lateinit var switchUseTraditional: Switch

    /**
     * 「补充短语词库」入口当前是不是提醒态（2 级词库未就位）。
     *
     * null = 还没应用过（首次进页面前）。只用来省掉重复的视图改动与日志 —— `onResume` 每次
     * 回来都会调 [refreshDictEntryEmphasis]，状态没变就不该动视图。
     */
    private var dictEntryEmphasized: Boolean? = null

    // 检查更新：版本号取构建日期，与远程 tag 比较
    private lateinit var btnCheckUpdate: Button
    /** 「打开下载页面」：与检查结果无关，直接跳 Gitee 发行版列表 */
    private lateinit var btnUpdateDownload: Button
    /** 更新检查状态：只区分「空闲 / 进行中」，结果由对话框呈现（见 [UpdateState]） */
    private var updateState: UpdateState = UpdateState.Idle

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

    /** 处理期间的模态忙框（加密/解密/写入都可能耗时上秒，没有可见反馈用户会以为点了没反应） */
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

    /**
     * 提示类对话框（更新提示 / 结果提示 / 时间选择器）：同一时刻只留一个，走 [showTipDialog] 弹。
     *
     * 这些框由代码创建，旋转重建时不会自动恢复 —— 不显式 dismiss 就是 WindowLeaked
     * （manifest 未声明 configChanges，旋转必然重建页面）。
     */
    private var tipDialog: Dialog? = null

    // 剪贴板

    /**
     * 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 对一次表并排下一次，
     * [onStop] 撤掉。原先本页自带一份 Handler + `appliedThemeDark` 字段，2026-10-02 收归共用实现（L-476）。
     */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

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
                // 目标文件此时已被 CreateDocument 创建为空壳：不删就留下一个「外观与有效包无异」
                // 的 0 字节文件，用户拿去发给维护者才发现里面什么都没有（BUG.md L-1098）
                deleteEmptyTarget(uri)
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
        // 产物是 `JINNENC1` 加密壳，**不是** zip：写 "application/zip" 会让文件管理器按压缩包
        // 处理（错误图标 / 错误打开方式 / 自动加 .zip 后缀）。诊断包那条保持 "application/zip"
        // —— 那个确实是 zip（N8，与用户词库导出同源缺陷）
        ActivityResultContracts.CreateDocument("application/octet-stream")
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
                // 同诊断包：删掉 CreateDocument 建出的空壳（BUG.md L-1098）
                deleteEmptyTarget(uri)
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

        spinnerShuangpin = findViewById(R.id.spinner_shuangpin)

        btnEnable = findViewById(R.id.btn_enable)
        btnPick = findViewById(R.id.btn_pick)
        btnSave = findViewById(R.id.btn_save)
        textTest = findViewById(R.id.text_test)

        switchAutoShowKeyboard = findViewById(R.id.switch_auto_show_keyboard)
        switchUserLearning = findViewById(R.id.switch_user_learning)
        switchPredict = findViewById(R.id.switch_predict)
        // 在线翻译（BYOK）：开关 + 入口 + 状态摘要，文案全部代码下发（strings.xml 默认不改动）
        switchTranslate = findViewById(R.id.switch_translate)
        switchTranslate.text = TEXT_TRANSLATE_SWITCH
        btnTranslateSettings = findViewById(R.id.btn_translate_settings)
        btnTranslateSettings.text = TEXT_TRANSLATE_SETTINGS
        btnTranslateSettings.setOnClickListener {
            Diagnostics.i(TAG, "设置页: 打开翻译设置")
            runCatching { startActivity(Intent(this, TranslationSettingsActivity::class.java)) }
                .onFailure { Diagnostics.w(TAG, "打开翻译设置失败: ${it.message}") }
        }
        textTranslateState = findViewById(R.id.text_translate_state)

        // 只使用繁体字：候选里的简体字全部替换为繁体字（文案同样代码下发）
        switchUseTraditional = findViewById(R.id.switch_use_traditional)
        switchUseTraditional.text = TEXT_USE_TRADITIONAL
        findViewById<TextView>(R.id.text_use_traditional_desc).text = "开启后候选全部显示繁体字"
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        btnUpdateDownload = findViewById(R.id.btn_update_download)
        bindCheckUpdate()
        // 打开设置页时的自动检查：距上次成功检查 ≥7 天才执行（失败静默，见 maybeAutoCheckUpdate）
        maybeAutoCheckUpdate()
        btnExportDiag = findViewById(R.id.btn_export_diag)
        textDiagDir = findViewById(R.id.text_diag_dir)


        // 收藏符号编辑：单按钮入口，打开独立编辑页
        findViewById<Button>(R.id.btn_edit_favorites).setOnClickListener {
            startActivity(Intent(this, FavoriteSymbolsActivity::class.java))
        }
        // 符号分组顺序：单按钮入口，打开独立排序页（设置主页只留一个按钮，不再内嵌列表）
        findViewById<Button>(R.id.btn_symbol_order).setOnClickListener {
            startActivity(Intent(this, SymbolOrderActivity::class.java))
        }
        // 补充短语词库：独立页面按需下载分片短语库（词库_第 2/3/4 部分）
        findViewById<Button>(R.id.btn_dict_expand).setOnClickListener {
            Diagnostics.i(TAG, "设置页: 打开分类词库")
            runCatching { startActivity(Intent(this, DictManagerActivity::class.java)) }
                .onFailure { Diagnostics.w(TAG, "打开分类词库失败: ${it.message}") }
        }
        // 需求：2 级词库没就位时，这个入口要比旁边那个更显眼 —— 词库直接决定能打出多少词，
        // 而原先它只是一行同款普通按钮，下没下载过看起来一模一样
        refreshDictEntryEmphasis()
        // 按钮圆角间隙（键盘外观）：独立页面。入口文案随需求改为「键盘外观自选」（在代码里下发，
        // strings.xml 默认不改动 —— 布局仍引用原 string，运行时被这里的文本覆盖）
        findViewById<Button>(R.id.btn_key_appearance).apply {
            text = TEXT_KEY_APPEARANCE_ENTRY
            setOnClickListener { startActivity(Intent(this@SettingsActivity, KeyAppearanceActivity::class.java)) }
        }
        // 模糊音容错：独立全屏页（页面内每组一个勾选框，勾选即落盘并即时生效）。
        // 入口文案固定，不显示「（N 组）」——用户要的是一句话说明入口，当前勾选进页面看
        btnFuzzyPinyin = findViewById(R.id.btn_fuzzy_pinyin)
        btnFuzzyPinyin.text = TEXT_FUZZY_ENTRY
        btnFuzzyPinyin.setOnClickListener { startActivity(Intent(this, FuzzyPinyinActivity::class.java)) }
        // 加更多生僻字：同一行的右侧按钮 → 独立全屏页（两档开关，档 3 依赖档 2，勾选即落盘即时生效）
        findViewById<Button>(R.id.btn_rare_chars).apply {
            text = TEXT_RARE_ENTRY
            setOnClickListener { startActivity(Intent(this@SettingsActivity, RareCharsActivity::class.java)) }
        }
        // 敲击音效反馈：独占下一行的单按钮入口 → 独立全屏页（音效六组音色 + 震动四档，改动即落盘）
        findViewById<Button>(R.id.btn_tap_sound).apply {
            text = TEXT_TAP_SOUND_ENTRY
            setOnClickListener { startActivity(Intent(this@SettingsActivity, TapSoundActivity::class.java)) }
        }
        findViewById<Button>(R.id.btn_clipboard_customize).apply {
            text = TEXT_CLIPBOARD_CUSTOMIZE_ENTRY
            setOnClickListener { startActivity(Intent(this@SettingsActivity, ClipboardCustomizeActivity::class.java)) }
        }
        // 图库快贴：目录绑定 / 清除绑定 / 自动返回 / 缩略图布局都收在子页面里，这里只留一个入口
        findViewById<Button>(R.id.btn_gallery_settings).apply {
            text = TEXT_GALLERY_SETTINGS_ENTRY
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, GallerySettingsActivity::class.java))
            }
        }
        // 空格左右按钮：入口按钮（与图库入口同行），点击进入「底栏标点输出」独立页
        // （逗号 / 句号字符自定义收在那一页里）。
        // ⚠ 不要再提「空格键左右滑动移动光标开关」：那个开关已于 2026-10-16 随功能整体移除
        // （`PunctOutputPageTest.空格移光标开关已彻底移除` 反向钉着，标点页里已无 `switch_space_cursor`）。
        findViewById<Button>(R.id.btn_space_cursor).apply {
            text = TEXT_SPACE_CURSOR
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, PunctOutputActivity::class.java))
            }
        }
        // 存储占用：只读统计页（用户问「存储由什么组成」——大头常是看不见的词库索引缓存）
        findViewById<Button>(R.id.btn_storage_usage).apply {
            text = TEXT_STORAGE_ENTRY
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, StorageUsageActivity::class.java))
            }
        }
        // 悬浮键盘：总开关 + 逐 APP 绑定都在子页面里，这里只留一个入口（默认关闭）
        findViewById<Button>(R.id.btn_floating_keyboard).apply {
            text = TEXT_FLOATING_ENTRY
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, FloatingKeyboardActivity::class.java))
            }
        }
        // 语音转为文字：四块语音配置（总开关 / 授权麦克风 / 服务端 / 识别选项）已迁到独立页，
        // 本页只留一个入口按钮（与「绑定悬浮键盘」同行，见 bindVoiceEntry）
        bindVoiceEntry()
        // 底部使用说明：六行纯文本，文案在代码里下发（values/strings.xml 默认禁改）
        textUsage = findViewById(R.id.text_usage)
        textUsage.text = TEXT_USAGE

        // 输入方案下拉（全局，2026-09-20 起）：全拼 + 八套双拼（共 9 项，语音键盘不在此列）。
        // 选中即全局生效：选全拼 = 关闭双拼；选某套双拼 = 记住该方案并启用。
        // 按键面板不再提供「全拼 / 双拼」切换按钮，这里是唯一的输入方案入口。
        // 列表取自 [ShuangpinScheme.ALL]，与引擎共用同一份数据，不会脱节。
        spinnerShuangpin.adapter = ArrayAdapter(
            this, R.layout.item_spinner,
            ShuangpinScheme.ALL.map { it.displayName },
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerShuangpin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                // 与「默认键盘模式」同一个坑：初始化 setSelection 与恢复实例状态都会回调，
                // 不挡住就会把用户已选的方案静默改掉（真机上实测被改写过）。
                if (!spinnerShuangpin.userInteracted) return
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

        btnEnable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btnPick.setOnClickListener {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        }
        btnSave.setOnClickListener { saveAndRestart() }

        // 生成用户词库（台账查看 / 合并 / 导出 / 导入）：入口与「存储占用统计」同行
        findViewById<Button>(R.id.btn_user_dict).apply {
            text = TEXT_USER_DICT
            setOnClickListener { startActivity(Intent(this@SettingsActivity, UserDictActivity::class.java)) }
        }

        // 自动唤起键盘：勾选即写入，立即生效（JinnIme.onShowInputRequested 每次实时读取）
        switchAutoShowKeyboard.setOnCheckedChangeListener { _, checked ->
            prefs.autoShowKeyboard = checked
            Diagnostics.i(TAG, "自动唤起键盘: ${if (checked) "开启" else "关闭"}")
        }
        // 只使用繁体字：拨动即写入并立即生效（引擎侧 setTraditional 只改查询期转换，不需要重启）
        switchUseTraditional.setOnCheckedChangeListener { _, checked ->
            prefs.useTraditional = checked
            PinyinEngine.setTraditional(checked)
            Diagnostics.i(TAG, "只使用繁体字: ${if (checked) "开启" else "关闭"}（立即生效）")
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

        // 配置备份：导出 / 导入（跨设备迁移）。按钮文本在代码里下发——strings.xml 默认不改动
        btnConfigExport = findViewById(R.id.btn_config_export)
        btnConfigImport = findViewById(R.id.btn_config_import)
        textConfigHint = findViewById(R.id.text_config_hint)
        // 导出 / 导入 / 解密的结果都写在这行上：状态会变，读屏要能听见（BUG.md L-1195）
        textConfigHint.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
        // 上次异常中断留下的明文中间件（导出 zip / 导入临时 zip / 用户词库页的暂存件）
        // 在这里补清：进程被杀不会跑任何收尾代码，只能等下次进设置页（N9）
        BackgroundIo.run {
            ConfigBackupManager.cleanupStaleCache(this@SettingsActivity)
            UserDictExport.cleanup(this@SettingsActivity)
        }
        // 页面重建期间完成的后台任务（导出/解密/导入）会把结论留在这里，进入页面时补提示
        pendingNotice?.let { notice ->
            pendingNotice = null
            alert("提示", notice)
        }
        Diagnostics.currentLogDir?.let {
            textDiagDir.text = getString(R.string.settings_diag_dir_hint, it.absolutePath)
        }

    }


    /**
     * 定时换色：`onStart` 里对一次表并排下一次、`onStop` 里撤掉（见 [ThemeManager.ScheduledThemeTicker]）。
     *
     * 对表那一下也覆盖「在后台跨过切换点」—— 原先只在 `onCreate` 排一次，后台期间到点的换色要等
     * 用户重新进页面才补上。
     */
    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /** 从「键盘外观」页返回时两档皮肤可能已改：这里补一次刷新（本页其余状态不变，无需 recreate） */
    override fun onResume() {
        super.onResume()

        // 翻译设置页返回：状态摘要（已配置 / 未配置）可能已变
        if (::textTranslateState.isInitialized) refreshTranslateState()
        // 从分类词库页返回：刚下完 2 级词库时提醒态要收回去，否则按钮一直亮着
        refreshDictEntryEmphasis()
    }

    /**
     * 「补充短语词库」入口的提醒态：2 级词库没就位时换成主按钮外观（极光渐变填充 + 加粗 + 反差字色），
     * 就位后回到次级外观。
     *
     * 为什么值得这么显眼：词库直接决定能打出多少词，而「没下载」在一排同款按钮里看不出任何差别，
     * 用户也就不会去点。判据与分类词库页的「已安装」同一句（[OptionalDicts.isInstalled]），
     * **旧包也算没就位**（换发版后老用户停在上一个版本，同样该提醒）。
     *
     * 不拿「索引就绪」（[PinyinEngine.isOptionalIndexReady]）当判据：它受首次加载时机影响，
     * 装好了也可能有几秒显示成未装，按钮会闪一下再收回去。
     */
    private fun refreshDictEntryEmphasis() {
        val btn = findViewById<Button>(R.id.btn_dict_expand) ?: return
        val ready = OptionalDicts.isInstalled(
            File(filesDir, PinyinEngine.OPT_DICT_DIR),
            OptionalDicts.LEVEL2,
        )
        val emphasized = !ready
        // 只在状态真的变了时改视图与写日志：onResume 每回来一次都调，重复改会白重绘
        if (dictEntryEmphasized == emphasized) return
        dictEntryEmphasized = emphasized
        btn.setBackgroundResource(
            if (emphasized) R.drawable.btn_aurora_primary else R.drawable.btn_aurora_secondary,
        )
        btn.setTextColor(getColor(if (emphasized) R.color.text_on_accent else R.color.text_primary))
        btn.setTypeface(null, if (emphasized) Typeface.BOLD else Typeface.NORMAL)
        Diagnostics.i(
            TAG,
            "补充短语词库入口: ${if (emphasized) "提醒态（2 级词库未就位）" else "常规态"}",
        )
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
            // 诊断包要打包日志与快照，降后台优先级（BUG.md L-1194）
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val outcome = Diagnostics.exportBundleChecked(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    pendingNotice = when (outcome) {
                        is Diagnostics.ExportOutcome.Ok ->
                            "诊断包已生成，但页面被重建，请重新点「导出诊断数据」选择保存位置。"
                        is Diagnostics.ExportOutcome.Failed ->
                            "导出诊断包失败（页面被重建），请重试"
                    }
                    return@runOnUiThread
                }
                btnExportDiag.isEnabled = true
                when (outcome) {
                    is Diagnostics.ExportOutcome.Ok -> {
                        pendingDiagZip = outcome.file
                        exportDiagLauncher.launch(outcome.file.name)
                    }
                    is Diagnostics.ExportOutcome.Failed -> {
                        Diagnostics.w(TAG, "exportDiagnostics: 打包失败 - ${outcome.reason}")
                        textDiagDir.text = getString(R.string.settings_diag_export_fail, outcome.reason)
                    }
                }
            }
        }.apply { isDaemon = true; name = "jinn-diag-export" }.start()
    }

    /** 把临时诊断包写入用户选定的位置（SAF），写完即删临时文件 */
    private fun copyDiagZipTo(uri: Uri, src: java.io.File) {
        // 提示里用自己生成的文件名：部分 provider 的 lastPathSegment 是文档 ID（如 "18"），对用户没有意义
        val displayName = src.name
        Thread {
            // 写入整份包（上限随剪贴板数据走），降后台优先级（BUG.md L-1194）
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val ok = runCatching {
                openTruncatingOutput(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } != null
            }.getOrElse {
                // 异常会转成用户可见的「写入失败」，但类名必须留痕：权限 / 空间 / provider 三类的
                // 处置完全不同，只看一句「写入失败」无从判断
                Diagnostics.w(TAG, "exportDiagnostics: 写包异常 ${it.javaClass.simpleName}")
                false
            }
            // 失败时把刚写的半截文件删掉：覆盖写一开始就把目标清空，留下一份 0 字节或截断的文件
            // 只会让用户误以为是有效产物（配置包拿去导入必然得到「密码错误或文件已损坏」，把排查带偏）
            if (!ok) runCatching { contentResolver.delete(uri, null, null) }
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
        }.apply { isDaemon = true; name = "jinn-diag-copy" }.start()
    }

    private fun loadPrefs() {
        switchAutoShowKeyboard.isChecked = prefs.autoShowKeyboard
        bindTranslateCard()

        switchUseTraditional.isChecked = prefs.useTraditional
        switchUserLearning.isChecked = prefs.userLearning
        switchPredict.isChecked = prefs.predictEnabled

        // 输入方案：按当前生效方案定位（全拼状态下选中「26 键全拼」，与全局语义一致）
        spinnerShuangpin.setSelection(
            ShuangpinScheme.ALL.indexOf(prefs.effectiveShuangpinScheme).coerceAtLeast(0)
        )
        // 候选词行数：档位 1/2 对应下拉下标 0/1（Prefs 已归一，下标必然合法）

    }

    /**
     * 「语音转为文字」入口（2026-11-01）：按钮打开 [VoiceSettingsActivity]，与「绑定悬浮键盘」
     * 并排在同一张卡里（用户第 2 条：两个按钮放同一行、不要小字描述）。
     *
     * 本页不再有语音开关本身，也不再显示「已开启 · ws://…」那一行 —— 状态在子页里看，
     * 免得同一件事在两个页面上各显示一次、各自不同步。
     */
    private fun bindVoiceEntry() {
        btnVoiceSettings = findViewById(R.id.btn_voice_settings)
        btnVoiceSettings.text = TEXT_VOICE_ENTRY
        btnVoiceSettings.setOnClickListener {
            Diagnostics.i(TAG, "设置页: 打开语音设置")
            runCatching { startActivity(Intent(this, VoiceSettingsActivity::class.java)) }
                .onFailure { Diagnostics.w(TAG, "打开语音设置失败: ${it.message}") }
        }
    }

    /**
     * 更新检查状态：只区分「空闲 / 进行中」。
     *
     * 结果（有更新 / 已最新 / 网络错误）由对话框呈现，早先预留的
     * UpToDate / Available / NetworkError 三态从未被任何消费方读取（死状态），已移除。
     */
    private enum class UpdateState { Idle, Checking }

    /**
     * 「本次检查」的序号（BUG.md L-145）：晚到的回调**不得**动界面。
     *
     * 看门狗 30s 只是常态余量（网络侧总预算 25s，而预算本身此前管不住滴水响应）——
     * 一旦某次请求真的超过 30s，看门狗会解锁按钮、用户再点一次；此时**上一次**的回调若照旧
     * `removeCallbacks` + `setUpdateState(Idle)`，删掉的就是**新那次**的看门狗，并把新请求
     * 变成「在飞但没有看门狗」：它的回调再丢，按钮就永久停在「检查中…」。
     */
    private var checkSeq = 0

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
            val seq = ++checkSeq
            UpdateChecker.checkAsync(BuildConfig.VERSION_CODE) { result ->
                if (seq != checkSeq) {
                    // 过期结果：只记日志，不解锁、不弹窗（弹了就是「答非所问」的那一次）
                    Diagnostics.i(TAG, "忽略过期的检查结果（seq=$seq，当前=$checkSeq）")
                    return@checkAsync
                }
                onUpdateChecked(result)
            }
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
        // 网络与电量门控（MEM-17）：非 WiFi 且未充电时不做后台检查 —— 用户没在等它，
        // 不该为一次版本检查付流量与唤醒。**不推进 `updateLastCheckAt`**：下次打开设置页还会再判，
        // 一旦回到 WiFi / 开始充电就补上（推进了就等于把这次检查吞掉）
        if (!UpdateChecker.autoCheckNetworkOk(this)) {
            Diagnostics.i(TAG, "自动检查更新: 当前非 WiFi 且未充电，跳过（下次打开设置页再判）")
            return
        }
        // 与手动检查共用状态机：进行中不重入，避免并发请求与重复弹窗
        if (updateState == UpdateState.Checking) return
        val since = if (last <= 0L) "从未检查" else "${(now - last) / DAY_MS} 天前"
        Diagnostics.i(TAG, "自动检查更新: 开始（上次=$since）")
        setUpdateState(UpdateState.Checking)
        val seq = ++checkSeq
        UpdateChecker.checkAsync(BuildConfig.VERSION_CODE) { result ->
            // 与手动检查同一条纪律（BUG.md L-145）：过期结果不动界面
            if (seq != checkSeq) {
                Diagnostics.i(TAG, "忽略过期的自动检查结果（seq=$seq，当前=$checkSeq）")
                return@checkAsync
            }
            onAutoChecked(result)
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
        btnCheckUpdate.removeCallbacks(updateWatchdogRunnable)
        if (checking) btnCheckUpdate.postDelayed(updateWatchdogRunnable, UPDATE_WATCHDOG_MS)
    }

    /** 手动检查：结果由三段式对话框呈现（措辞属 unified-update-check 统一，不得改写） */
    private fun onUpdateChecked(result: UpdateChecker.Result) {
        btnCheckUpdate.removeCallbacks(updateWatchdogRunnable)
        // 检查是后台线程 + 10s 网络超时：结果回来时页面可能已关闭或已重建。
        // 拿已销毁的 Activity 去 show() 会抛 BadTokenException（主线程崩溃）。
        if (isFinishing || isDestroyed) {
            Diagnostics.i(TAG, "检查更新结果已到达，但页面已销毁，跳过弹窗")
            return
        }
        // 记账放在守卫之后：弹窗是「发现新版本」唯一的提示出口，页面销毁时它不会出现，
        // 提前记账等于让用户什么都没看到却被静默七天（L-1047）
        recordUpdateCheckTime(result)
        // 结果本身由对话框呈现：这里只需解锁（早先先赋 UpToDate/Available/NetworkError、
        // 紧接着又被 Idle 覆盖，是没有任何消费方的死代码，已随本次修复移除）。
        setUpdateState(UpdateState.Idle)
        showUpdateDialog(result)
    }

    /** 自动检查：失败/已最新一律静默，只有检测到新版本才弹「有新版本，要更新吗？」 */
    private fun onAutoChecked(result: UpdateChecker.Result) {
        btnCheckUpdate.removeCallbacks(updateWatchdogRunnable)
        if (isFinishing || isDestroyed) {
            Diagnostics.i(TAG, "自动检查更新结果已到达，但页面已销毁，跳过弹窗")
            return
        }
        // 同上（L-1047）：只有这次结果真被呈现（弹窗或静默结论落日志）才记账，页面销毁时不记
        recordUpdateCheckTime(result)
        setUpdateState(UpdateState.Idle)
        when (result) {
            is UpdateChecker.Result.Available -> showAskUpdateDialog(result)
            is UpdateChecker.Result.UpToDate ->
                Diagnostics.i(
                    TAG,
                    "自动检查更新: 已是最新 ${result.latest}（归一 ${UpdateChecker.comparableVersion(result.latest)}，静默）",
                )
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

    /** 三类结果统一三套文案，措辞由 unified-update-check 统一，不得改写。 */
    private fun showUpdateDialog(result: UpdateChecker.Result) {
        // 展示与比较必须同量纲（BUG.md L-131）：比较走 [UpdateChecker.comparableVersion]，
        // 文案若仍传 `normalizeTag` 的原始值，标签规范回退到 6 位时会出现
        // 「你目前的版本：20260929 / 在线最新版本：260930」却提示有更新（数字看着更小）——自相矛盾。
        val local = getString(R.string.update_local_version, BuildConfig.VERSION_CODE)
        fun shown(v: Int) = UpdateChecker.comparableVersion(v)
        val builder = AlertDialog.Builder(this)
        when (result) {
            is UpdateChecker.Result.Available -> builder
                .setTitle(R.string.update_title_available)
                .setMessage(
                    local + "\n" + getString(R.string.update_latest_version, shown(result.latest))
                )
                .setNegativeButton(R.string.update_btn_later, null)
                .setPositiveButton(R.string.update_btn_go) { _, _ ->
                    openUrl(UpdateChecker.releasesUrl(result.latest, result.source, result.tag))
                }

            is UpdateChecker.Result.UpToDate -> builder
                .setTitle(R.string.update_title_prompt)
                .setMessage(
                    local + "\n" +
                        getString(R.string.update_latest_version, shown(result.latest)) + "\n" +
                        getString(R.string.update_uptodate)
                )
                .setPositiveButton(R.string.update_btn_ok, null)

            UpdateChecker.Result.NetworkError -> builder
                .setTitle(R.string.update_title_prompt)
                .setMessage(local + "\n" + getString(R.string.update_latest_unreachable))
                .setPositiveButton(R.string.update_btn_ok, null)
        }
        showTipDialog(builder.create())
    }

    /**
     * 自动检查发现新版本时的确认框：一个问句 + 「不要」/「去更新」。
     *
     * 与手动检查的三段式对话框分开：后者措辞属 unified-update-check 统一（不得改写），
     * 本对话框只服务「打开设置页自动检查」这条路径。
     */
    private fun showAskUpdateDialog(result: UpdateChecker.Result.Available) {
        showTipDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.update_title_prompt)
                .setMessage(R.string.update_ask_message)
                .setNegativeButton(R.string.update_btn_no, null)
                .setPositiveButton(R.string.update_btn_go) { _, _ ->
                    openUrl(UpdateChecker.releasesUrl(result.latest, result.source, result.tag))
                }
                .create()
        )
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Diagnostics.w(TAG, "打开更新页失败: ${it.message}") }
    }

    /**
     * 翻译卡片：开关写 [Prefs.translateEnabled]（键盘功能面板据此决定是否显示「翻译」键，
     * 收起键盘再弹出即生效，不需要重启）；状态摘要只说「已配置 / 未配置」，
     * 不回显任何凭据字符（凭据本身在翻译设置页里可见可改）。
     */
    private fun bindTranslateCard() {
        switchTranslate.isChecked = prefs.translateEnabled
        switchTranslate.setOnCheckedChangeListener { _, checked ->
            prefs.translateEnabled = checked
            Diagnostics.i(TAG, "翻译功能: $checked")
        }
        refreshTranslateState()
    }

    private fun refreshTranslateState() {
        val provider = TranslationProviderId.of(prefs.translateProvider)
        // 注意：用「不可用成因」而不是 `translationProvider() != null`（2026-10-03 修复 L-815）：
        // ① `null` 不再区分「没填凭据」与「端点不是 https」，摘要必须分因，否则 http 端点用户
        //    会去反复检查已经填好的 Key 与模型名；② 顺带**省掉一次 provider 组装**（那会解密凭据）。
        val reason = prefs.translateNotReadyReason()
        // 目标语言按 Provider 取：OpenAI 兼容用的是**它自己那套**（配置页里选，12 项预设 + 自定义），
        // 显示 translateTarget 会是一个永远不生效的死值（2026-09-30 审查发现 —— 用户改了
        // 本页的下拉、摘要却报另一套语言，指向与实际行为不符）
        val targetLabel = if (provider == TranslationProviderId.OPENAI) {
            prefs.openAiTargetLanguage
        } else {
            TranslationLanguage.of(prefs.translateTarget).label
        }
        textTranslateState.text = when {
            reason == Prefs.TranslateNotReady.NONE -> "已配置：${provider.label} · 目标 $targetLabel"
            // 端点不合格：用户该改的是 Base URL，不是凭据 —— 文案与键盘 toast 同一处定义（L-816）
            reason == Prefs.TranslateNotReady.ENDPOINT ->
                "${provider.label}：${TranslationError.INSECURE.message}"
            else -> "未配置：点「翻译设置」填写 ${provider.label} 凭据"
        }
    }

    // ── 保存并重启输入法进程 ──────────────────────────────────────────

    /**
     * 保存并重启输入法进程。
     *
     * 语音各项（总开关 / 地址 / 端口 / 语言 / 提示词 / 识别选项）2026-11-01 起在
     * [VoiceSettingsActivity] 里显式落盘；本页其余开关与下拉都是**选中即写**，
     * 所以这里只剩「写状态行 → 重启」两件事。
     *
     * 重启走 [ImeRestart]（flush → 800ms → killProcess），与语音页共用同一套语义与失败回退文案。
     */
    private fun saveAndRestart() {
        textTest.setText(R.string.settings_restarting)
        Diagnostics.i(TAG, "saveAndRestart: 重启输入法进程")
        // 落盘失败时不重启，状态行改成那句失败文案（否则界面同时存在「正在重启」与「已取消」）
        ImeRestart.restart(this) { message -> textTest.text = message }
    }

    override fun onDestroy() {
        // 检查更新的看门狗同理：它挂在按钮上，页面销毁后仍会跑一次并回头改按钮状态
        btnCheckUpdate.removeCallbacks(updateWatchdogRunnable)
        // 忙框不能随页面销毁留下（WindowLeaked）；后台任务回来时另有 isFinishing 守卫兜底
        dismissBusy()
        // 三个配置流程对话框同理：它们是代码创建的，旋转重建时不会自动恢复
        exportDialog?.dismiss()
        exportDialog = null
        importPwdDialog?.dismiss()
        importPwdDialog = null
        importConfirmDialog?.dismiss()
        importConfirmDialog = null
        // 提示类框（更新提示 / 结果提示 / 时间选择器）同一套道理
        tipDialog?.dismiss()
        tipDialog = null
        // 明文临时包不跨页面生命周期：页面销毁（含旋转重建）时一并清掉。
        // 但导入正在进行时不能删——后台线程还要按节重开它；这种残留由下次进设置页的清扫兜底
        if (!importInFlight) {
            pendingImportZip?.let {
                it.delete()
                ConfigBackupManager.releaseUnlockedTemp(it)
            }
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
        val dialog = securePasswordDialog(
            AlertDialog.Builder(this)
                .setTitle(TEXT_EXPORT_CONFIG)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导出", null)
                .create(),
        )
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
            // 配置导出要打 zip + AES-GCM，降后台优先级（BUG.md L-1194）
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // 兜底：导出链路上还有 SQLite 与文件 IO（剪贴板分页、词库摘要），漏网异常会连带
            // 杀死同进程的输入法（设置页与 IME 同进程），所以这里必须整体包住
            val result = runCatching {
                ConfigBackupManager.export(
                    this@SettingsActivity,
                    ConfigBackupManager.ExportOptions(includeClipboard, includeDicts, chars),
                )
            }.onFailure {
                Diagnostics.w(TAG, "exportConfig: 打包异常 ${it.javaClass.simpleName}")
            }.getOrNull()
            chars.fill('\u0000')
            // 本次结果在后台线程取出（原因随返回值，不读 Manager 的共享字段）——避免快速连点串读（BUG.md L-1358）
            val outcome = result?.value
            val reason = result?.reason ?: TEXT_EXPORT_FAIL
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    // 页面已销毁：把结论留下，重建后补提示（否则用户不知道这次导出到底成没成）
                    pendingNotice = if (outcome == null) {
                        reason
                    } else {
                        "配置包已生成，但页面被重建，请重新点「导出配置」选择保存位置。"
                    }
                    return@runOnUiThread
                }
                dismissBusy()
                btnConfigExport.isEnabled = true
                if (outcome == null) {
                    // 具体原因随本次结果返回（如「剪贴板历史过大」），比通用文案有用得多
                    Diagnostics.w(TAG, "exportConfig: 打包或加密失败（$reason）")
                    textConfigHint.text = reason
                    alert(TEXT_EXPORT_CONFIG, reason)
                } else {
                    pendingConfigZip = outcome.file
                    val extra = buildString {
                        if (outcome.clipDropped > 0) {
                            append("（剪贴板 ${outcome.clipDropped} 条因超限/超预算未导出）")
                        }
                        // 导出期间库一直在变、重试已耗尽：包完整可用，但这一份视图可能少了刚落库的条目。
                        // 不许静默报「全部导出成功」（BUG-13）。
                        if (outcome.clipUnstable) {
                            append("（导出期间剪贴板持续变化，本次快照可能不完整；如需完整备份请稍后重试）")
                        }
                        // 词频没落盘（BUG.md L-1319）：包里是盘上旧版本，少的是防抖窗口内那几次学习。
                        // 与上一条同款——如实说，别让用户以为刚背下来的词都在包里
                        if (outcome.freqStale) {
                            append("（用户词频未能落盘，包内是盘上旧版本，缺最近几次学习记录）")
                        }
                    }
                    textConfigHint.text = "已生成加密备份包，请选择保存位置$extra"
                    exportConfigLauncher.launch(outcome.file.name)
                }
            }
        }.apply { isDaemon = true; name = "jinn-config-export" }.start()
    }

    /**
     * 以「截断写」打开 SAF 输出流。
     *
     * 必须带 `"wt"`：默认的 `"w"` 在部分 provider（云盘/文档型）上不保证截断，覆盖一个更大的
     * 旧文件时会在尾部留下残骸 —— 用户看到的是「密码错误或文件已损坏」这种完全误导的提示。
     * 少数 provider 不认 `"wt"`，退回默认 mode（行为与从前一致，不会更差）。
     */
    /**
     * 删除一个「已创建但没写进任何内容」的 SAF 目标（[ActivityResultContracts.CreateDocument] 会先建空文件）。
     *
     * 导出在中途失败时，目标位置那份 0 字节文件外观与有效包一模一样，用户拿去用只会得到
     * 「密码错误或文件已损坏」，把排查带偏（BUG.md L-1098）。删失败不抛：目标可能被 provider
     * 收回授权，此时留个空壳也比崩掉强。
     */
    private fun deleteEmptyTarget(uri: Uri) {
        // 只有确实是 0 字节才删：少数 provider 在重名时会返回**既有文档**的 uri，无条件删会毁掉旧备份
        val size = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        // SAF 的 AFD 长度常是 UNKNOWN_LENGTH(-1)：未知长度一律不删（留空壳远优于删掉用户的旧备份）
        if (size != 0L) return
        runCatching { contentResolver.delete(uri, null, null) }
            .onFailure { Diagnostics.w(TAG, "删除空壳目标失败: ${it.javaClass.simpleName}") }
    }

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
            // 写入整份包（上限随剪贴板数据走），降后台优先级（BUG.md L-1194）
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val ok = runCatching {
                openTruncatingOutput(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } != null
            }.getOrElse {
                // 同诊断包：异常转成用户可见的「写入失败」，类名仍要留痕
                Diagnostics.w(TAG, "exportConfig: 写包异常 ${it.javaClass.simpleName}")
                false
            }
            // 失败时把刚写的半截文件删掉：覆盖写一开始就把目标清空，留下一份 0 字节或截断的文件
            // 只会让用户误以为是有效产物（配置包拿去导入必然得到「密码错误或文件已损坏」，把排查带偏）
            if (!ok) runCatching { contentResolver.delete(uri, null, null) }
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
        }.apply { isDaemon = true; name = "jinn-config-copy" }.start()
    }

    /**
     * 给**含密码输入框**的对话框加防截屏 / 录屏 / 投屏 / 最近任务缩略图标志（2026-10-04 修复 L-588）。
     *
     * 为什么加在对话框窗口、而不是整个设置页：`FLAG_SECURE` 是**按窗口**生效的，而本页只有
     * 「导出配置 / 导入配置」两个密码框是机密（其余是 host / port、提示词这类非机密配置）。
     * 整页加会把「设置页截图」一并废掉（真机排查与用户留证都要用），对密码框却没有任何额外收益。
     * 注意：投屏（ADB / scrcpy）下对话框内容不可见是**预期行为**，不是缺陷。
     */
    private fun securePasswordDialog(dialog: AlertDialog): AlertDialog = dialog.apply {
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
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
        val dialog = securePasswordDialog(
            AlertDialog.Builder(this)
                .setTitle(TEXT_IMPORT_PWD_TITLE)
                .setView(box)
                .setNegativeButton("取消") { _, _ ->
                    // 取消要复位入口按钮并给出反馈，否则提示行会停在上一次的失败文案上（误导）
                    btnConfigImport.isEnabled = true
                    textConfigHint.text = TEXT_IMPORT_CANCELED
                }
                .setPositiveButton("解密", null)
                .create(),
        )
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
            // 解包 + 解锁是重活，降后台优先级（BUG.md L-1194）
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val result = runCatching { ConfigBackupManager.unlock(this@SettingsActivity, uri, chars) }
                .onFailure { Diagnostics.w(TAG, "importConfig: 解锁异常 ${it.javaClass.simpleName}") }
                .getOrElse { ConfigBackupManager.UnlockResult.ReadFailed }
            chars.fill('\u0000')
            val zip = (result as? ConfigBackupManager.UnlockResult.Ok)?.zip
            val info = zip?.let { runCatching { ConfigBackupManager.inspect(it) }.getOrNull() }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    zip?.let {
                        it.delete()
                        ConfigBackupManager.releaseUnlockedTemp(it)
                    }
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
        }.apply { isDaemon = true; name = "jinn-config-unlock" }.start()
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
            // 端点类设置也在「设置项」里（语音服务地址、OpenAI 兼容 Base URL / Chat Path 等）：
            // 「覆盖还原」会用包里的值覆盖本机，而翻译请求会把**本机已有的凭据与光标前的正文**发往
            // 那个地址 —— 包若来自不可信来源，等于把 Key 交出去（2026-09-30 审查）。
            // 这里明说，不让它藏在「设置 N 项」这个计数后面。
            append("\n\n提示：选「覆盖还原」时，包内的服务端地址与自定义端点（语音服务地址、")
            append("OpenAI 兼容 Base URL 等）会覆盖本机 —— 请确认备份来源可信。")
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
                // 取消也要删掉明文临时 zip，不让它在缓存里留着；同时注销在册登记（L-1086）
                if (pendingImportZip === zip) pendingImportZip = null
                zip.delete()
                ConfigBackupManager.releaseUnlockedTemp(zip)
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
            ConfigBackupManager.releaseUnlockedTemp(zip)
            // 与 negative 一致：否则提示行会永久停在上一句「正在解密…」
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
        // 页面重建后按钮会重新可点，而上一轮的导入线程可能还在跑：进程级标记拦下这种重入。
        // 注意：这里必须自己清明文临时包：确认框的 positive 点击后已自动 dismiss，
        // setOnCancelListener（负责删包与注销）**不会**触发，而 cleanupStaleCache 又按
        // in-flight 跳过该名字 ⇒ 否则这份已解密的明文备份会跨会话留在缓存目录里。
        if (ConfigBackupManager.importing) {
            if (pendingImportZip === zip) pendingImportZip = null
            zip.delete()
            ConfigBackupManager.releaseUnlockedTemp(zip)
            textConfigHint.text = TEXT_IMPORTING
            alert(TEXT_IMPORT_CONFIG, TEXT_IMPORTING)
            return
        }
        btnConfigImport.isEnabled = false
        textConfigHint.text = TEXT_IMPORTING
        showBusy(TEXT_BUSY_IMPORT)
        // 标记进行中：此时 onDestroy 不能删明文 zip（后台线程还要读它）
        importInFlight = true
        Thread {
            // 配置导入要写库 / 词频 / prefs，降后台优先级（BUG.md L-1194）
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // 兜底：导入会写 SQLite / 词频 / prefs，任一处漏网异常都会连带杀死同进程的输入法
            val result = runCatching {
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
            // 本次结果在后台线程取出（原因随返回值，不读共享字段）——避免快速连点串读（BUG.md L-1358）
            val r = result?.value
            val reason = result?.reason ?: TEXT_IMPORT_FAIL
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    // 最危险的一种：导入其实已经落盘，用户却什么都没看到，多半会以为失败再导一次
                    pendingNotice = if (r == null) {
                        reason
                    } else {
                        "配置导入已完成（写入的设置与词库需重启输入法后生效）。"
                    }
                    return@runOnUiThread
                }
                dismissBusy()
                btnConfigImport.isEnabled = true
                if (r == null) {
                    // 具体原因随本次结果返回（如「词库正在写入，请稍后重试」），比通用文案有用
                    Diagnostics.w(TAG, "importConfig: 导入失败（$reason）")
                    textConfigHint.text = reason
                    alert(TEXT_IMPORT_CONFIG, reason)
                    return@runOnUiThread
                }
                // 设置已落盘：先把「即时生效」的那几项同步给引擎，再走下面的「需重启」提示
                // （用户点「稍后」时，繁体 / 档位 / 模糊音 / 学习这些不必等重启就该生效）
                syncImmediateSettings()
                // 外观与主题也要同步（BUG.md L-1324）：导入一次性改写了候选字号 / 键高 / 圆角 / 间隙 /
                // 透明度 / 明暗档 / 皮肤 / 候选行数 / 大写显示 —— 这些既不走引擎，全仓也没有偏好变更
                // 监听通道。不同步的话用户点「稍后」后键盘毫无变化（外观页承诺「改完即生效」），
                // 要等下一次弹键盘才自愈。两个入口自身都做了「实例不在就当无事」与幂等处理
                JinnIme.onKeyAppearanceChanged()
                JinnIme.notifyThemeChanged()
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
                        // 与「保存并重启」同一条路径：落盘失败时不重启，把原因写进配置提示行
                        .setPositiveButton("重启输入法") { _, _ ->
                            textConfigHint.setText(R.string.settings_restarting)
                            ImeRestart.restart(this) { message -> textConfigHint.text = message }
                        }
                } else {
                    builder.setPositiveButton("好", null)
                }
                val dialog = builder.create()
                // 返回键 / 点到框外走 cancel，**不会**触发上面的「稍后」回调（同 importConfirmDialog）：
                // 此刻配置已落盘，页面上的旧值必须一并刷新
                dialog.setOnCancelListener { recreate() }
                // 必须登记（showTipDialog）：本框是代码创建的，不登记就会在旋转重建时泄漏窗口、
                // 且重建后这次导入的结论无处可看
                showTipDialog(dialog)
            }
        }.apply { isDaemon = true; name = "jinn-config-import" }.start()
    }

    /**
     * 导入完成后，把「不需要重启就生效」的开关立即同步到运行时。
     *
     * 这些开关平时靠各自监听器里的 `setXxx` 即时生效；导入是**绕过监听器**直接写 prefs 的，
     * 不同步的话用户点「稍后」后看到的是「导入成功但行为没变」—— 繁体 / 档位 / 模糊音 / 学习
     * 都属于这一类（真正必须重启的只有词库与其它启动期读取的项，提示语里已经写明）。
     */
    private fun syncImmediateSettings() {
        val p = Prefs(this)
        PinyinEngine.setRareTiers(p.rareTier2, p.rareTier3)
        PinyinEngine.setTraditional(p.useTraditional)
        PinyinEngine.setFuzzyMask(p.fuzzyPinyinMask)
        UserFrequency.setEnabled(p.userLearning)
        Diagnostics.i(
            TAG,
            "导入后同步即时设置: 档2=${p.rareTier2} 档3=${p.rareTier3} " +
                "繁体=${p.useTraditional} 模糊音=${p.fuzzyPinyinMask} 学习=${p.userLearning}",
        )
    }

    /**
     * 处理期间的模态忙框。
     *
     * 为什么必须有：密钥派生按 OWASP 量级取 600k 次 PBKDF2，手机上要 1~2 秒；SAF 写入、
     * 词库解包也可能更久。这段空窗期里如果界面上什么都没有（原来只改一行 11sp 的灰色小字），
     * 用户会以为点了没反应、甚至重复点击。模态忙框把「正在进行」摆到台面上，
     * 结束（成功或失败）时由 [dismissBusy] 统一关闭。
     *
     * 框内只留一行文字，不放系统不确定进度条：那条横向条的循环滑动是框架自带的无限动画，
     * 与全 App 的 0 帧口径冲突（此处关不掉 —— 它由 ProgressBar 的 drawable 自己驱动）。
     */
    private fun showBusy(message: String) {
        if (isFinishing || isDestroyed) return
        dismissBusy()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpOf(20), dpOf(16), dpOf(20), dpOf(16))
            addView(
                TextView(this@SettingsActivity).apply {
                    text = message
                    textSize = 14f
                }
            )
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
        showTipDialog(builder.create())
    }

    /**
     * 弹出提示类对话框并登记，`onDestroy` 时统一 dismiss（否则旋转/返回时 WindowLeaked）。
     *
     * 同一时刻只保留一个：后弹的会先收掉前一个，避免叠窗。**不要**给它传
     * `setOnDismissListener`（这里要用它清引用，覆盖会丢）。
     */
    private fun showTipDialog(dialog: Dialog): Dialog {
        tipDialog?.dismiss()
        tipDialog = dialog
        dialog.setOnDismissListener { if (tipDialog === dialog) tipDialog = null }
        dialog.show()
        return dialog
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

        // ── 配置备份文案（strings.xml 默认不改动，文案收敛在此处） ──
        const val TEXT_EXPORT_CONFIG = "导出配置文件"
        const val TEXT_IMPORT_CONFIG = "导入配置文件"
        const val TEXT_CONFIG_HINT = "AES-256 加密  密码只能用汉字  导入时要输密码"
        const val TEXT_EXPORT_DESC = "导出内容：全部设置项、用户词频\n" +
            "包含连接设置（服务器地址/端口）与语音提示词" +
            "包含翻译设置的 API Key 凭据" +
            "禁止分享给不信任的人(即使它无法破解)\n" +
            "密码不保存在本机，忘记后无法解密 永久丢失"
        const val TEXT_INCLUDE_CLIPBOARD =
            "额外导出:剪贴板历史 明文，靠压缩密码保护\n可能含身份地址、手机银行、账号密码\n" +
                "（不含图片：图片不进任何备份，换机前用「剪贴板历史 → 图片 → 导出全部图片」导出到你选定的文件夹）"
        const val TEXT_INCLUDE_DICTS = "额外导出:已下载词库（没必要,可重复下）"
        const val TEXT_PWD_HINT = "备份密码（4~64 个不相同的汉字）"
        const val TEXT_PWD_HINT_CONFIRM = "再输一次备份密码"
        const val TEXT_PWD_MISMATCH = "两次输入的密码不一致"
        const val TEXT_EXPORTING = "正在打包并加密…"
        const val TEXT_EXPORT_FAIL = "导出失败，请重试"
        const val TEXT_IMPORT_PWD_TITLE = "输入备份密码"
        const val TEXT_IMPORT_PWD_DESC = "备份已加密  请输入导出时设置的密码\n" +
            "密码错误 / 文件被改动 都无法解密"
        const val TEXT_DECRYPTING = "正在解密（密钥校验中，请稍候）…"
        const val TEXT_DECRYPT_FAIL = "密码错误或文件已损坏，无法解密"
        const val TEXT_EXPORT_CANCELED = "已取消导出"
        const val TEXT_IMPORT_CANCELED = "已取消导入"
        const val TEXT_BUSY_TITLE = "请稍候"
        const val TEXT_BUSY_EXPORT = "正在打包并加密配置…\n（密钥派生按安全强度计算，约需 1~2 秒，请勿退出）"
        const val TEXT_BUSY_DECRYPT = "正在解密并校验密码…\n（约需 1~2 秒，请勿退出）"
        const val TEXT_BUSY_IMPORT = "正在导入配置与数据…"

        // 模糊音容错：入口文案（页面内的标题 / 说明 / 按钮文案在 FuzzyPinyinActivity 里下发）
        const val TEXT_FUZZY_ENTRY = "增加模糊拼音"

        // 加更多生僻字：入口文案（页面内的文案在 RareCharsActivity 里下发）
        const val TEXT_RARE_ENTRY = "加更多生僻字"

        // 敲击音效反馈：入口文案（页面内文案在 TapSoundActivity 里下发）
        const val TEXT_TAP_SOUND_ENTRY = "敲击音效反馈"
        const val TEXT_CLIPBOARD_CUSTOMIZE_ENTRY = "剪贴板自定义"

        /** 图库快贴：设置页只留这一个入口，目录绑定 / 自动返回 / 缩略图布局都在子页面里 */
        const val TEXT_GALLERY_SETTINGS_ENTRY = "图库快贴功能"

        /** 键盘外观入口（布局仍引用 `@string/key_appearance_entry`，这里覆盖成新文案） */
        const val TEXT_KEY_APPEARANCE_ENTRY = "键盘外观自选"

        /** 底栏标点输出入口按钮（与「图库快贴功能」同行；页面内文案在 PunctOutputActivity 里下发） */
        const val TEXT_SPACE_CURSOR = "空格左右按钮"

        /** 存储占用：只读统计页（安装包 / 词库包与索引缓存 / 剪贴板 / 日志 各占多少） */
        const val TEXT_STORAGE_ENTRY = "存储占用统计"

        /** 悬浮键盘入口（与「语音转为文字」同行；页面内文案在 FloatingKeyboardActivity 里下发） */
        const val TEXT_FLOATING_ENTRY = "绑定悬浮键盘"
        /** 用户词库入口：动作名，状态由页面内的说明弹框交代 */
        const val TEXT_USER_DICT = "生成用户词库"

        // 只使用繁体字：胶囊开关文案（与「自动唤起键盘」同行，紧随其后）
        const val TEXT_USE_TRADITIONAL = "只使用繁体字"

        // 在线翻译（BYOK）：卡片文案（页面内的标题 / 说明 / 按钮文案在 TranslationSettingsActivity 里下发）
        const val TEXT_TRANSLATE_SWITCH = "启用翻译(需要联网,发送原文,接收译文)"
        const val TEXT_TRANSLATE_SETTINGS = "翻译设置"

        const val TEXT_BUSY_WRITE = "正在写入文件…"
        const val TEXT_READ_FAIL = "无法读取所选文件：可能已被移走、授权已失效，或不是本应用的加密备份包" +
            "（也可能是文件超过 256MB 上限）"
        const val TEXT_READ_TIMEOUT = "读取备份文件超时（网络盘或文件过大）：请把文件保存到本机后重试"
        const val TEXT_FORMAT_UNKNOWN = "备份内容无法识别：可能由不兼容的版本生成，或文件已被改动"
        const val TEXT_IMPORT_FAIL = "导入失败：备份包不完整/已损坏，或写入本机失败（如存储空间不足）"
        const val TEXT_IMPORTING = "正在导入…"
        const val TEXT_MODE_RESTORE = "覆盖还原（设置与词频按备份写回，剪贴板并入本机历史）"
        const val TEXT_MODE_MERGE = "仅并入数据（只合并词频、剪贴板与词库，保留本机设置）"

        /**
         * 「语音转为文字」入口按钮（2026-11-01：四块语音配置迁到 [VoiceSettingsActivity] 后，
         * 本页只留这一个入口，与「绑定悬浮键盘」同排；状态小字已删）。
         */
        const val TEXT_VOICE_ENTRY = "语音转为文字"

        /**
         * 最底部使用说明（六行，`\n` 分隔）。
         *
         * 2026-11-01：原第 5 行「启用语音输入之后,长按空格键即可进入语音功能页面」已过期
         * （语音改成松手即转文字），按用户授权改成现在的说法。文案仍走代码常量，
         * `values/strings.xml` 里那条 `settings_usage` 保持原样不动。
         */
        const val TEXT_USAGE = "拼音键盘：26 键全拼 / 双拼（8 套方案），空格选词。\n" +
            "长串拼音打错了：点候选栏右侧的 ✕ 可整串清掉\n" +
            "「删除键」双击再长按，清空整个输入框。\n" +
            "拼音键盘按下的字母，按回车直接输出英文。\n" +
            "长按空格键：按住说话，上滑取消，松手转文字\n" +
            "作者 https://github.com/yezijinn"

        /**
         * 「检查更新」看门狗超时：两源串行的总预算（`UpdateChecker.TOTAL_BUDGET_MS` = 25s）+ 5s 余量。
         *
         * 15s 版本按「单个 10s 超时」留余量，但 GitHub 不可达时还要回退 Gitee 再来一轮，
         * 看门狗会先于请求熄灯解锁 —— 防重入判据随之失效，用户可并发发起第二次检查并重复弹窗。
         */
        const val UPDATE_WATCHDOG_MS = 30_000L

        /** 自动检查的日志换算用（毫秒/天）；节流判据本体在 [UpdateChecker.shouldAutoCheck] */
        const val DAY_MS = 24L * 60 * 60 * 1000

        // 可选词库的文件名、下载源与体积/耗时说明已统一收敛到 OptionalDicts，
        // 由「分类词库」页使用；设置页只保留一个跳转入口。
    }
}
