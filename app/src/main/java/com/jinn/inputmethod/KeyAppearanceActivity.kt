package com.jinn.inputmethod

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
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
    }

    /**
     * 键盘皮肤选择器：横向排列「预览块 + 名称」，点选即落盘并即时生效。
     *
     * 预览块直接用 [PinyinKey] 自绘（同款圆角 / 渐变 / 描边 / 底边厚度），因此不引入任何图片资源；
     * 文案（标题 / 说明 / 选项名）取代码常量与 [KeyboardSkins.label]：既不改 `strings.xml`
     * （默认禁改），也不在 XML 里硬编码文本（避免 HardcodedText）。选项文本为字面量，
     * 按项目既有做法就地抑制 lint 的 SetTextI18n。
     */
    @SuppressLint("SetTextI18n")
    private fun initSkinSelector(prefs: Prefs) {
        findViewById<TextView>(R.id.text_skin_title).text = "键盘皮肤"
        findViewById<TextView>(R.id.text_skin_hint).text =
            "只改键盘配色与质感；圆角 / 间隙 / 透明度仍由上面的滑杆控制"
        val row = findViewById<LinearLayout>(R.id.skin_row)
        val density = resources.displayMetrics.density
        val items = mutableListOf<Pair<KeyboardSkin, View>>()
        for (skin in KeyboardSkins.ALL) {
            val preview = PinyinKey(this).apply {
                label = "A"
                centeredStyle = true
                setKeyAppearance(dp(8).toFloat(), dp(2).toFloat())
                applySkin(
                    if (skin.isDefault) {
                        null
                    } else {
                        KeyboardSkins.visualFor(
                            skin, 0, 1f, density,
                            getColor(R.color.kb_key),
                            getColor(R.color.kb_key_pressed),
                            getColor(R.color.kb_key_text),
                            KeyTransparency.withAlpha(getColor(R.color.kb_key_text), 160f / 255f),
                            getColor(R.color.kb_key_hint_red),
                        )
                    }
                )
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(44))
            }
            val name = TextView(this).apply {
                text = skin.label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_secondary))
            }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                isClickable = true
                addView(preview)
                addView(name)
                setOnClickListener {
                    prefs.keyboardSkinId = skin.id
                    Diagnostics.i(TAG, "键盘皮肤: ${skin.id}（${skin.label}）")
                    JinnIme.onKeyAppearanceChanged()
                    refreshSkinSelection(items, prefs.keyboardSkinId)
                }
            }
            // 预览块是 PinyinKey：它自身的 onTouchEvent 恒返回 true（键位手势需要），
            // 会把触摸吃掉、外层选项收不到点击 —— 这里把点击转回选项容器。
            preview.setOnClickListener { column.performClick() }
            items += skin to column
            row.addView(column)
        }
        refreshSkinSelection(items, prefs.keyboardSkinId)
    }

    /** 刷新皮肤选择器选中态：选中项全亮，其余降透明度（预览块自身即对应皮肤样式） */
    private fun refreshSkinSelection(items: List<Pair<KeyboardSkin, View>>, selectedId: String) {
        for ((skin, view) in items) view.alpha = if (skin.id == selectedId) 1f else 0.55f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "KeyAppearance"

        /** 与 key_appearance_bg.xml 的夜空底色一致（状态栏只吃颜色值，取不到 drawable） */
        val AURORA_TOP = 0xFF161240.toInt()
    }
}
