package com.jinn.inputmethod

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 「按钮圆角间隙」设置页：26 键区（3 行 28 键）统一的按键圆角、按键间隙与整块键盘的透明度。
 *
 * 从设置页「按钮圆角间隙」按钮进入；拖动即落盘（[Prefs.keyCornerDp] / [Prefs.keyGapDp] /
 * [Prefs.keyTransparencyPercent]），不广播、不重启进程：
 *  - 松手时调 [JinnIme.onKeyAppearanceChanged] → 键盘正显示就即时重套外观（只改外观，不动拼音串）；
 *  - 键盘未显示时不用管：[PinyinKeyboardView] 每次弹键盘（onStartInputView → configure）都会读最新配置。
 *
 * 定义域与换算一律取自 [KeyAppearance] / [KeyTransparency]（与绘制逻辑共用一套边界），
 * 本页不写死任何数值。
 */
class KeyAppearanceActivity : Activity() {

    /**
     * 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 对一次表并排下一次，
     * [onStop] 撤掉。原先本页自带一份 Handler + `appliedDark` 字段，2026-10-02 收归共用实现（L-476）。
     */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    // ── 2026-10-09 由设置页迁入的四组控件（外观相关集中在本页）──
    private lateinit var spinnerCandidateRows: UserAwareSpinner
    private lateinit var switchKeyHint: Switch
    private lateinit var switchPinyinQuanpin: Switch
    private lateinit var spinnerTheme: UserAwareSpinner
    private lateinit var btnThemeLightAt: Button
    private lateinit var btnThemeDarkAt: Button
    private lateinit var rowThemeSchedule: View

    /** 时间选择器引用：本页退出时要撤掉，否则窗口泄漏（设置页用 showTipDialog 收口，本页单点管理） */
    private var timePicker: android.app.TimePickerDialog? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 极光画在窗口底色上（根布局不再自带背景）：键盘弹起时页面内容被顶到键盘上方，
        // 键盘覆盖区背后只剩窗口底色，画在窗口上才能连成一片，且让键盘的半透明有对比可透。
        window.setBackgroundDrawableResource(R.drawable.key_appearance_bg)
        setContentView(R.layout.activity_key_appearance)
        // 本页背景是固定的深色「七彩极光」：状态栏跟着用夜空色 + 浅色图标。
        // 否则亮白主题下顶端会横一条浅色状态栏，与页面背景割裂（暗黑主题下本来也是浅色图标，无副作用）。
        @Suppress("DEPRECATION")
        window.statusBarColor = AURORA_TOP
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        val prefs = Prefs(this)

        findViewById<Button>(R.id.btn_key_appearance_close).setOnClickListener { finish() }

        val seekCorner = findViewById<SeekBar>(R.id.seek_key_corner)
        val textCorner = findViewById<TextView>(R.id.text_key_corner)
        val seekGap = findViewById<SeekBar>(R.id.seek_key_gap)
        val textGap = findViewById<TextView>(R.id.text_key_gap)

        seekCorner.max = KeyAppearance.CORNER_PROGRESS_MAX
        seekGap.max = KeyAppearance.GAP_PROGRESS_MAX
        // 先写初值再挂监听：否则初始化写入也会触发一次无意义的回调
        seekCorner.progress = KeyAppearance.cornerDpToProgress(prefs.keyCornerDp)
        seekGap.progress = KeyAppearance.gapDpToProgress(prefs.keyGapDp)
        textCorner.text = KeyAppearance.formatDp(prefs.keyCornerDp)
        textGap.text = KeyAppearance.formatDp(prefs.keyGapDp)

        seekCorner.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = KeyAppearance.cornerProgressToDp(progress)
                prefs.keyCornerDp = dp
                textCorner.text = KeyAppearance.formatDp(dp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            // 只在松手时打一条日志：拖动过程每格都写日志会变成每秒几十次文件 IO
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Diagnostics.i(TAG, "键盘圆角: ${KeyAppearance.formatDp(prefs.keyCornerDp)}")
                // 键盘正显示时即时生效（否则要收起再弹出才看到，用户会以为没生效）
                JinnIme.onKeyAppearanceChanged()
            }
        })

        seekGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = KeyAppearance.gapProgressToDp(progress)
                prefs.keyGapDp = dp
                textGap.text = KeyAppearance.formatDp(dp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Diagnostics.i(TAG, "键盘间隙: ${KeyAppearance.formatDp(prefs.keyGapDp)}")
                JinnIme.onKeyAppearanceChanged()
            }
        })

        // 键高：三行字母键（10 + 9 + 9 = 28 键）的单行高度。标题与数值同「字距」走代码下发
        // （strings.xml 默认禁改）；定义域与换算一律取自 KeyAppearance，本页不写死数值。
        val labelKeyHeight = findViewById<TextView>(R.id.label_key_height)
        val seekKeyHeight = findViewById<SeekBar>(R.id.seek_key_height)
        val textKeyHeight = findViewById<TextView>(R.id.text_key_height)
        labelKeyHeight.text = TEXT_KEY_HEIGHT_TITLE
        seekKeyHeight.max = KeyAppearance.KEY_HEIGHT_PROGRESS_MAX
        seekKeyHeight.progress = KeyAppearance.keyHeightDpToProgress(prefs.keyHeightDp)
        textKeyHeight.text = KeyAppearance.formatDp(prefs.keyHeightDp)

        seekKeyHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = KeyAppearance.keyHeightProgressToDp(progress)
                prefs.keyHeightDp = dp
                textKeyHeight.text = KeyAppearance.formatDp(dp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Diagnostics.i(TAG, "键高: ${KeyAppearance.formatDp(prefs.keyHeightDp)}")
                JinnIme.onKeyAppearanceChanged()
            }
        })

        // 字距：候选栏里相邻候选词之间的水平间隔（标题在代码里下发 —— strings.xml 默认禁改，
        // 本页其余三行的标题仍在 strings.xml，属历史遗留）
        val labelSpacing = findViewById<TextView>(R.id.label_candidate_spacing)
        val seekSpacing = findViewById<SeekBar>(R.id.seek_candidate_spacing)
        val textSpacing = findViewById<TextView>(R.id.text_candidate_spacing)
        labelSpacing.text = TEXT_SPACING_TITLE
        seekSpacing.max = KeyAppearance.SPACING_PROGRESS_MAX
        seekSpacing.progress = KeyAppearance.spacingDpToProgress(prefs.candidateSpacingDp)
        textSpacing.text = KeyAppearance.formatDp(prefs.candidateSpacingDp)

        seekSpacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = KeyAppearance.spacingProgressToDp(progress)
                prefs.candidateSpacingDp = dp
                textSpacing.text = KeyAppearance.formatDp(dp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Diagnostics.i(TAG, "候选字距: ${KeyAppearance.formatDp(prefs.candidateSpacingDp)}")
                // 与圆角 / 间隙 / 透明度同一条即时生效路径：refreshAppearance → refreshCandidateBar
                // → 候选条目按新字距重建（键盘正显示时立刻看到）
                JinnIme.onKeyAppearanceChanged()
            }
        })

        // 候选字号：候选词的字号（sp）。**整个候选栏按它派生**（行高 / 拼音条高度 / 栏高一起变），
        // 标题同样在代码里下发（strings.xml 默认禁改）
        val labelTextSize = findViewById<TextView>(R.id.label_candidate_text_size)
        val seekTextSize = findViewById<SeekBar>(R.id.seek_candidate_text_size)
        val textTextSize = findViewById<TextView>(R.id.text_candidate_text_size)
        labelTextSize.text = TEXT_TEXT_SIZE_TITLE
        seekTextSize.max = CandidateText.PROGRESS_MAX
        seekTextSize.progress = CandidateText.spToProgress(prefs.candidateTextSp)
        textTextSize.text = CandidateText.formatSp(prefs.candidateTextSp)

        seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sp = CandidateText.progressToSp(progress)
                prefs.candidateTextSp = sp
                textTextSize.text = CandidateText.formatSp(sp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Diagnostics.i(TAG, "候选字号: ${CandidateText.formatSp(prefs.candidateTextSp)}")
                // 与字距同一条即时生效路径：松手后 applyCandidateRows 按新字号重落栏高、候选条目重建
                JinnIme.onKeyAppearanceChanged()
            }
        })

        findViewById<TextView>(R.id.label_key_corner).text = TEXT_CORNER_TITLE
        findViewById<TextView>(R.id.label_key_gap).text = TEXT_GAP_TITLE
        findViewById<TextView>(R.id.label_key_transparency).text = TEXT_TRANSPARENCY_TITLE
        val seekTransparency = findViewById<SeekBar>(R.id.seek_key_transparency)
        val textTransparency = findViewById<TextView>(R.id.text_key_transparency)
        seekTransparency.max = KeyTransparency.PROGRESS_MAX
        seekTransparency.progress = KeyTransparency.percentToProgress(prefs.keyTransparencyPercent)
        textTransparency.text = KeyTransparency.formatPercent(prefs.keyTransparencyPercent)

        seekTransparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = KeyTransparency.progressToPercent(progress)
                prefs.keyTransparencyPercent = percent
                textTransparency.text = KeyTransparency.formatPercent(percent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Diagnostics.i(
                    TAG,
                    "键盘透明度: ${KeyTransparency.formatPercent(prefs.keyTransparencyPercent)}",
                )
                JinnIme.onKeyAppearanceChanged()
            }
        })

        // 26 键常显大写：只改键面字形（拼音模式），不改上屏；文案同样代码下发（strings.xml 默认禁改）。
        // 开关在切换瞬间落盘并即时刷键盘 —— 与滑杆同一套即时生效口径（JinnIme.onKeyAppearanceChanged）。
        val switchLetterUpper = findViewById<Switch>(R.id.switch_letter_uppercase)
        switchLetterUpper.text = TEXT_LETTER_UPPER_TITLE
        switchLetterUpper.isChecked = prefs.keyLetterUppercase
        switchLetterUpper.setOnCheckedChangeListener { _, checked ->
            prefs.keyLetterUppercase = checked
            Diagnostics.i(TAG, "26 键大写显示: $checked")
            JinnIme.onKeyAppearanceChanged()
        }

        initInputDisplaySection(prefs)
        initThemeCard(prefs)

        initSkinSelector(prefs)
    }

    /**
     * 「输入与显示」四组控件（2026-10-09 由设置页迁来）。
     *
     * 全部即时生效：写盘后调 [JinnIme.onKeyAppearanceChanged] 让正显示的键盘立刻按新设置重绘
     * （与滑杆、皮肤同一套口径）；键盘未显示时不用管 —— 下次 `configure()` 会读最新配置。
     */
    private fun initInputDisplaySection(prefs: Prefs) {
        // 候选词行数：1 行（默认，横向滚动）/ 2 行（上排偶数项、下排奇数项，见 CandidateRows）
        spinnerCandidateRows = findViewById(R.id.spinner_candidate_rows)
        findViewById<TextView>(R.id.label_candidate_rows).text = TEXT_CANDIDATE_ROWS_TITLE
        spinnerCandidateRows.adapter = ArrayAdapter(
            this, R.layout.item_spinner, arrayOf("1 行", "2 行"),
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerCandidateRows.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
                ) {
                    // 同本页其他下拉：初始化 setSelection 与恢复实例状态都会回调，不挡住会把用户选择改回第 0 项
                    if (!spinnerCandidateRows.userInteracted) return
                    val rows = position + 1
                    if (rows != prefs.candidateRows) {
                        prefs.candidateRows = rows
                        Diagnostics.i(TAG, "候选词行数: $rows")
                        JinnIme.onKeyAppearanceChanged()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        spinnerCandidateRows.setSelection(prefs.candidateRows - 1)

        // 键面韵母提示（双拼）：关掉后键面只显示字母
        switchKeyHint = findViewById(R.id.switch_key_hint)
        switchKeyHint.text = TEXT_KEY_HINT_TITLE
        switchKeyHint.isChecked = prefs.showKeyHint
        switchKeyHint.setOnCheckedChangeListener { _, checked ->
            prefs.showKeyHint = checked
            Diagnostics.i(TAG, "键面韵母提示: ${if (checked) "开启" else "关闭"}")
            JinnIme.onKeyAppearanceChanged()
        }

        // 双拼候选全拼：关掉后候选栏显示按下的字母
        switchPinyinQuanpin = findViewById(R.id.switch_pinyin_quanpin)
        switchPinyinQuanpin.text = TEXT_QUANPIN_TITLE
        switchPinyinQuanpin.isChecked = prefs.showQuanpin
        switchPinyinQuanpin.setOnCheckedChangeListener { _, checked ->
            prefs.showQuanpin = checked
            Diagnostics.i(TAG, "拼音显示为声韵: ${if (checked) "开启" else "关闭"}")
            JinnIme.onKeyAppearanceChanged()
        }
    }

    /**
     * 界面明暗切换（2026-10-09 由设置页迁来）：模式下拉 + 两档皮肤说明 + 定时档的两个切换时刻。
     *
     * 任一改动都写盘 + `recreate()`：重建页面让 `attachBaseContext` 读到新主题，整页颜色跟着换。
     * 与设置页版唯一差别是时间选择器由本页自己持有（见 [timePicker]）。
     */
    private fun initThemeCard(prefs: Prefs) {
        spinnerTheme = findViewById(R.id.spinner_theme)
        btnThemeLightAt = findViewById(R.id.btn_theme_light_at)
        btnThemeDarkAt = findViewById(R.id.btn_theme_dark_at)
        rowThemeSchedule = findViewById(R.id.row_theme_schedule)
        findViewById<TextView>(R.id.label_theme_mode).text = TEXT_THEME_TITLE

        spinnerTheme.adapter = ArrayAdapter.createFromResource(
            this, R.array.theme_mode_entries, R.layout.item_spinner
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                if (!spinnerTheme.userInteracted) return
                val values = resources.getStringArray(R.array.theme_mode_values)
                val mode = values.getOrNull(position)?.toIntOrNull() ?: return
                if (mode != prefs.themeMode) {
                    prefs.themeMode = mode
                    Diagnostics.i(TAG, "主题模式: $mode")
                    JinnIme.notifyThemeChanged() // 键盘正显示时同进程立即换肤（不必等下次弹出）
                    recreate()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnThemeLightAt.setOnClickListener {
            pickThemeTime(prefs.themeLightAtMinutes, "亮起") { minutes -> prefs.themeLightAtMinutes = minutes }
        }
        btnThemeDarkAt.setOnClickListener {
            pickThemeTime(prefs.themeDarkAtMinutes, "暗起") { minutes -> prefs.themeDarkAtMinutes = minutes }
        }

        // 回填当前值（recreate 之后走的也是这里）
        val modeValues = resources.getStringArray(R.array.theme_mode_values)
        spinnerTheme.setSelection(modeValues.indexOf(prefs.themeMode.toString()).coerceAtLeast(0))
        refreshThemeScheduleRow(prefs)
        refreshSkinGroupState(prefs)
    }

    /** 定时行只在「定时」模式显示；两个按钮的文案随配置刷新 */
    private fun refreshThemeScheduleRow(prefs: Prefs) {
        if (!::rowThemeSchedule.isInitialized) return
        val scheduled = prefs.themeMode == ThemeManager.MODE_SCHEDULED
        val visibility = if (scheduled) View.VISIBLE else View.GONE
        rowThemeSchedule.visibility = visibility
        btnThemeLightAt.text = getString(R.string.theme_light_at_tpl, formatMinutes(prefs.themeLightAtMinutes))
        btnThemeDarkAt.text = getString(R.string.theme_dark_at_tpl, formatMinutes(prefs.themeDarkAtMinutes))
    }

    /**
     * 皮肤两段的标题与状态行。
     *
     * 主标题只有「亮色」「暗色」两个词，生效与否写在下面一行：生效的那段提示可以直接预览
     * （本页的「点这里唤起键盘」就能看到），另一段要等档位切过去才看得到。
     */
    @SuppressLint("SetTextI18n")
    private fun refreshSkinGroupState(prefs: Prefs) {
        val dark = ThemeManager.isDark(this, prefs)
        findViewById<TextView>(R.id.text_skin_light_title).text = SKIN_TONE_LIGHT
        findViewById<TextView>(R.id.text_skin_dark_title).text = SKIN_TONE_DARK
        findViewById<TextView>(R.id.text_skin_light_desc).text =
            if (dark) TEXT_SKIN_INACTIVE_DESC else TEXT_SKIN_ACTIVE_DESC
        findViewById<TextView>(R.id.text_skin_dark_desc).text =
            if (dark) TEXT_SKIN_ACTIVE_DESC else TEXT_SKIN_INACTIVE_DESC
    }

    /** 「当天第几分钟」→ `HH:mm`（脏值先 floorMod 归一，负值不会显示成 -1:-30） */
    private fun formatMinutes(minutes: Int): String {
        val m = Math.floorMod(minutes, ThemeManager.MINUTES_PER_DAY)
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /**
     * 弹时间选择器并即时落盘（回调参数为「当天第几分钟」0..1439）。
     *
     * 写入后 `recreate()`：定时档的两个时刻决定键盘何时换肤，本页与设置页同款 —— 改完立刻按新模式重排。
     */
    private fun pickThemeTime(initialMinutes: Int, label: String, apply: (Int) -> Unit) {
        val m = Math.floorMod(initialMinutes, ThemeManager.MINUTES_PER_DAY)
        timePicker?.dismiss()
        val picker = android.app.TimePickerDialog(
            this,
            { _, hour, minute ->
                apply(hour * 60 + minute)
                Diagnostics.i(TAG, "定时切换: $label ${formatMinutes(hour * 60 + minute)}")
                JinnIme.notifyThemeChanged()
                recreate()
            },
            m / 60,
            m % 60,
            true,
        )
        timePicker = picker
        picker.setOnDismissListener { if (timePicker === picker) timePicker = null }
        picker.show()
    }

    /**
     * 定时主题到点后重建本页：色板来自 `attachBaseContext`、段标题在 [initSkinSelector] 里写死，
     * 没有这个钩子就会停在旧档（标题仍写「现为亮色」、黄框停在旧档），而键盘已经换肤。
     *
     * 接在 [onStart]（不是 `onCreate`）：从后台回来时也补一次对表 —— 在后台跨过切换点的那段时间
     * 定时器是撤着的（见 [ThemeManager.ScheduledThemeTicker.start]）。
     */
    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
        // 页面退出时撤掉可能还开着的时间选择器（否则窗口泄漏）
        timePicker?.dismiss()
        timePicker = null
    }

    /**
     * 键盘皮肤选择器：**按明暗档分两段**，每段 16 套、每行八个（两整行，紧凑版式不横向滚动）。
     *
     * 段内顺序固定（取 [KeyboardSkins.ofTone]，即 `ALL` 的定义顺序）：亮色段首 = 原白、暗色段首 = 原黑。
     * 段标题的括号随当前档位变化（用户 2026-09-24 设计的提示）：生效的那一段写「现为亮色 / 现为暗色,
     * 唤起键盘可直接预览」（点选即落盘并立刻生效），另一段写「未激活,不可预览」（要等档位切换才见效）。
     * 这条说明取代了原先「黄框＝当前生效 / 蓝框＝另一档已选」的图例，但**框色仍保留**：
     * 黄框 = 生效段里选中的那套，蓝框 = 另一段已选的那套。
     *
     * 预览键直接用 [PinyinKey] 自绘（同款圆角 / 渐变 / 描边 / 底边厚度），因此不引入任何图片资源；
     * 按钮内文字取 [KeyboardSkin.label]（一律两字，`fitTextSize` 会按宽度自适应字号）：
     * 既不改 `strings.xml`（默认禁改），也不在 XML 里硬编码文本（避免 HardcodedText）。
     * 标题与选项文本为字面量，按项目既有做法就地抑制 lint 的 SetTextI18n。
     */
    @SuppressLint("SetTextI18n")
    private fun initSkinSelector(prefs: Prefs) {
        refreshSkinGroupState(prefs)
        val density = resources.displayMetrics.density
        // 令牌皮肤（原白 / 原黑）不覆盖键面色，色值只能从色板令牌取：预览必须固定吃自己那一档，
        // 否则在亮色页面上看「原黑」会是一块白键面（与原白看不出区别）。
        val lightCtx = ThemeManager.wrapContext(this, false)
        val darkCtx = ThemeManager.wrapContext(this, true)
        val cells = mutableListOf<SkinCell>()
        for ((tone, rowId) in SEGMENTS) {
            val grid = findViewById<LinearLayout>(rowId)
            val skins = KeyboardSkins.ofTone(tone)
            var line: LinearLayout? = null
            for ((index, skin) in skins.withIndex()) {
                if (index % SKINS_PER_ROW == 0) {
                    line = newSkinLine()
                    grid.addView(line)
                }
                val currentLine = line ?: continue
                val ctx = if (KeyboardSkins.tokenPaletteDark(skin) == true) darkCtx else lightCtx
                val key = PinyinKey(ctx).apply {
                    // 名称直接写在按钮内（两字，居中并按宽度自适应字号）—— 不再另起一行文字，行高更紧凑。
                    label = skin.label
                    centeredStyle = true
                    setKeyAppearance(dp(6).toFloat(), dp(2).toFloat())
                    applySkin(
                        KeyboardSkins.visualFor(
                            skin, 0, 1f, density,
                            ctx.getColor(R.color.kb_key),
                            ctx.getColor(R.color.kb_key_pressed),
                            ctx.getColor(R.color.kb_key_text),
                            KeyTransparency.withAlpha(ctx.getColor(R.color.kb_key_text), 160f / 255f),
                            ctx.getColor(R.color.kb_key_hint_red),
                        )
                    )
                    isClickable = true
                    // 预览键是 PinyinKey：它自身的 onTouchEvent 恒返回 true（键位手势需要），
                    // 外层容器收不到点击，所以选择逻辑就挂在这里。
                    setOnClickListener { pickSkin(prefs, skin, cells) }
                }
                // 选中框套在外层容器上：不动 KeyVisual，皮肤自身的描边 / 键帽厚度照原样展示
                val cell = FrameLayout(ctx).apply {
                    addView(
                        key,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                cells += SkinCell(skin, cell)
                // 宽按行等分（一行八个），高固定：屏宽 ÷ 8 恰好放下，不再横向滚动
                currentLine.addView(cell, LinearLayout.LayoutParams(0, dp(SKIN_CELL_HEIGHT_DP), 1f))
            }
            // 末行不足一行时补空占位：等分权重下，缺位会把剩下的项拉伸变宽（两段各 16 套恰两整行，
            // 这条只是兜底：将来某段数量不是 8 的倍数时仍要排齐）
            val remainder = skins.size % SKINS_PER_ROW
            if (remainder != 0) {
                repeat(SKINS_PER_ROW - remainder) {
                    line?.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
                }
            }
        }
        refreshSkinSelection(cells, prefs)
    }

    /** 点选一套皮肤：按它自己的档位入槽（亮色皮肤进亮色档、暗色皮肤进暗色档），并刷新两个选中标记 */
    private fun pickSkin(prefs: Prefs, skin: KeyboardSkin, cells: List<SkinCell>) {
        // 两档皮肤决定「明暗切换时键盘换哪一套」，本页的说明行显示的就是它 —— 选完立刻刷新
        refreshSkinGroupState(prefs)
        val isLightSkin = skin.tone == SkinTone.LIGHT
        if (isLightSkin) prefs.skinLightId = skin.id else prefs.skinDarkId = skin.id
        Diagnostics.i(TAG, "键盘皮肤: ${if (isLightSkin) "亮色" else "暗色"}档 = ${skin.id}（${skin.label}）")
        // 只有「被改动的那一档正在生效」才需要即时换肤；改另一档时当前皮肤没变，重建键盘纯属白费
        if (ThemeManager.isDark(this, prefs) != isLightSkin) JinnIme.onKeyAppearanceChanged()
        refreshSkinSelection(cells, prefs)
    }

    /** 皮肤选择器的一行：横向等分容器，行间距压到 [SKIN_ROW_GAP_DP]（紧凑排布） */
    private fun newSkinLine(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(SKIN_ROW_GAP_DP) }
        }

    /**
     * 刷新选择器标记：黄框 = 当前档位正在生效的那一套，蓝框 = 另一档已选的那一套，
     * 未选中的降到 [UNSELECTED_ALPHA]（预览块自身即对应皮肤样式，只降不隐，仍可看清）。
     */
    private fun refreshSkinSelection(cells: List<SkinCell>, prefs: Prefs) {
        val active = ThemeManager.keyboardSkin(prefs, ThemeManager.isDark(this, prefs)).id
        val lightId = prefs.skinLightId
        val darkId = prefs.skinDarkId
        for (cell in cells) {
            val selected = cell.skin.id == lightId || cell.skin.id == darkId
            cell.view.alpha = if (selected) 1f else UNSELECTED_ALPHA
            cell.view.background = when {
                cell.skin.id == active -> ring(RING_ACTIVE)
                selected -> ring(RING_OTHER_SLOT)
                else -> null
            }
        }
    }

    /** 选中框：透明底 + 圆角描边（画在预览块外层，不覆盖皮肤自身的描边） */
    private fun ring(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(dp(RING_STROKE_DP), color)
        cornerRadius = dp(RING_CORNER_DP).toFloat()
    }

    /** 预览块：外层容器承载选中框，内层 [PinyinKey] 画皮肤本身 */
    private class SkinCell(val skin: KeyboardSkin, val view: FrameLayout)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "KeyAppearance"

        /**
         * 「字距」项的标题文案。
         *
         * 写在代码里而不是 strings.xml：项目约定「strings.xml 默认禁改」（与各页 `TEXT_*` 同做法），
         * 新项不再往资源里加标题。
         */
        const val TEXT_SPACING_TITLE = "候选字距"

        /** 「候选字号」项的标题文案（同上：写在代码里，不进 strings.xml） */
        const val TEXT_TEXT_SIZE_TITLE = "候选字号"

    /** 「键高」行的标题（同上：代码下发，见 [TEXT_SPACING_TITLE] 的说明） */
    const val TEXT_KEY_HEIGHT_TITLE = "按钮键高"

    /**
     * 「26 键常显大写」开关的标题与说明。
     *
     * 说明里写清「上屏内容不变」：这个开关改的是键面字形，很多用户会以为打开后打出来就是大写，
     * 而拼音串本来就是小写（真正要上屏大写另有「大写锁定」键）。
     */
    const val TEXT_LETTER_UPPER_TITLE = "拼音键盘大写"

    /** 2026-10-09 由设置页迁入的四组控件文案（同上：代码下发，见 [TEXT_SPACING_TITLE] 的说明） */
    const val TEXT_CANDIDATE_ROWS_TITLE = "候选词的行数"
    const val TEXT_KEY_HINT_TITLE = "键盘内显韵母"
    const val TEXT_QUANPIN_TITLE = "双拼候选全音"
    const val TEXT_THEME_TITLE = "界面明暗切换"

    /** 圆角 / 间隙 / 透明三项的标题（2026-10-09 按需求统一带「按钮 / 面板」前缀） */
    const val TEXT_CORNER_TITLE = "按钮圆角"
    const val TEXT_GAP_TITLE = "按钮间隙"
    const val TEXT_TRANSPARENCY_TITLE = "面板透明"

    /** 皮肤两段的主标题与状态行：生效的那段可直接预览，另一段要等档位切过去 */
    const val SKIN_TONE_LIGHT = "亮色"
    const val SKIN_TONE_DARK = "暗色"
    const val TEXT_SKIN_ACTIVE_DESC = "已激活，唤起键盘可预览"
    const val TEXT_SKIN_INACTIVE_DESC = "未激活，不可预览"

        /** 皮肤选择器每行个数（每段 16 套排成两行八个，两段共四行） */
        const val SKINS_PER_ROW = 8

        /**
         * 两段的渲染顺序与各自的容器 id（段顺序与 [KeyboardSkins.ALL] 一致：亮色在前）。
         *
         * 容器是两个 `LinearLayout`（XML 里各一个），代码只往里插行 —— 32 个预览块若写进 XML
         * 会变成一大坨重复标签，且选项名要取自 [KeyboardSkin.label]。
         */
        val SEGMENTS = listOf(
            SkinTone.LIGHT to R.id.skin_row_light,
            SkinTone.DARK to R.id.skin_row_dark,
        )

        /** 皮肤预览键的高度（宽度由行内等分给出：屏宽 ÷ 8） */
        const val SKIN_CELL_HEIGHT_DP = 30

        /** 皮肤选择器的行间距：压到最小，同时留一点缝，避免相邻两行的键面贴死 */
        const val SKIN_ROW_GAP_DP = 2

        /** 未选中皮肤框的降透明度（选中项满亮） */
        const val UNSELECTED_ALPHA = 0.55f

        /** 选中框描边宽度与圆角：圆角取得比预览块自身的 6dp 略大，框才绕得出来 */
        const val RING_STROKE_DP = 2
        const val RING_CORNER_DP = 9

        /** 当前明暗档位正在生效的皮肤：琥珀色框 */
        val RING_ACTIVE = 0xFFFFC24B.toInt()

        /** 另一档已选中的皮肤：青色框（与琥珀色分得开，深浅键面上都看得见） */
        val RING_OTHER_SLOT = 0xFF6FD3FF.toInt()

        /** 与 key_appearance_bg.xml 的夜空底色一致（状态栏只吃颜色值，取不到 drawable） */
        val AURORA_TOP = 0xFF161240.toInt()
    }
}
