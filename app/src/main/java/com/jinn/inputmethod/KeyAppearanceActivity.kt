package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

/**
 * 「按钮圆角间隙」设置页：26 键区（3 行 28 键）统一的按键圆角与按键间隙。
 *
 * 从设置页「按钮圆角间隙」按钮进入；拖动即落盘（[Prefs.keyCornerDp] / [Prefs.keyGapDp]），
 * 不广播、不重启进程 —— [PinyinKeyboardView] 每次弹键盘（onStartInputView → configure）
 * 都会按最新配置重新套用外观。
 *
 * 定义域与换算一律取自 [KeyAppearance]（与绘制逻辑共用一套边界），本页不写死任何数值。
 */
class KeyAppearanceActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_appearance)
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
            }
        })
    }

    private companion object {
        const val TAG = "KeyAppearance"
    }
}
