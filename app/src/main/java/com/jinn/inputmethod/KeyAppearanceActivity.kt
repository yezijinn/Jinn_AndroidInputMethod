package com.jinn.inputmethod

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
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

    /** 主线程 Handler：定时主题到点后重建本页（机制与 [SettingsActivity] 一致） */
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 本次进页面时生效的深浅色；定时到点后与重新解析的结果比较，变了才重建页面 */
    private var appliedDark = false

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

        initSkinSelector(prefs)
        // 定时主题到点后重建本页：色板来自 attachBaseContext、段标题在 initSkinSelector 里写死，
        // 没有这个钩子就会停在旧档（标题仍写「现为亮色」、黄框停在旧档），而键盘已经换肤
        appliedDark = ThemeManager.isDark(this, prefs)
        scheduleThemeTick()
    }

    /**
     * 定时模式：停在本页时到点自动换色。
     *
     * 与 [SettingsActivity] 同一机制：只在「定时」模式排一次延时任务，到点重新解析，
     * 结果变了才 [recreate]（重建后色板与段标题一起换档），没变就继续排下一次。
     */
    private val themeTickRunnable = Runnable {
        val dark = ThemeManager.isDark(this, Prefs(this))
        if (dark != appliedDark) {
            Diagnostics.i(TAG, "定时切换到点: 主题转为 ${if (dark) "暗色" else "亮色"}")
            recreate()
        } else {
            scheduleThemeTick()
        }
    }

    private fun scheduleThemeTick() {
        uiHandler.removeCallbacks(themeTickRunnable)
        val prefs = Prefs(this)
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

    override fun onDestroy() {
        // 定时刷新的延时任务必须随页面撤销，否则会持有已销毁的 Activity
        uiHandler.removeCallbacks(themeTickRunnable)
        super.onDestroy()
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
        val dark = ThemeManager.isDark(this, prefs)
        findViewById<TextView>(R.id.text_skin_light_title).text =
            skinGroupTitle("亮色皮肤", "亮色", active = !dark)
        findViewById<TextView>(R.id.text_skin_dark_title).text =
            skinGroupTitle("暗色皮肤", "暗色", active = dark)
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

    /**
     * 段标题：生效的那一段写「现为亮色 / 现为暗色」并提示可直接预览，另一段写未激活。
     * 括号内容随当前档位实时变化（用户 2026-09-24 设计的提示方式，不改 `strings.xml`）。
     */
    private fun skinGroupTitle(name: String, toneName: String, active: Boolean): String =
        if (active) "$name(界面明暗切换:现为$toneName,唤起键盘可直接预览)"
        else "$name(界面明暗切换:未激活,不可预览)"

    /** 点选一套皮肤：按它自己的档位入槽（亮色皮肤进亮色档、暗色皮肤进暗色档），并刷新两个选中标记 */
    private fun pickSkin(prefs: Prefs, skin: KeyboardSkin, cells: List<SkinCell>) {
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
