package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/**
 * 「底栏标点输出」页：从设置页「空格左右按钮」按钮进入。
 *
 * 底栏「逗号 / 句号」两枚固定键各自可自定义上屏字符，中文态与英文态分开，各限一个字符 ——
 * 多输入的字符由 [PunctOutput.sanitizeField] 裁到最后一个码点并回写输入框，保证「屏幕上看到的」
 * 就是「会打出来的」。取值即时落盘（[Prefs] 的四个标点键），键盘每次弹出重读即生效：
 * 本页不广播、不重启进程。
 *
 * 页内另承载「空格键左右滑动移动光标」开关（原设置页那个开关迁入本页，功能不变）。
 */
class PunctOutputActivity : Activity() {

    /**
     * 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 对一次表并排下一次，
     * [onStop] 撤掉 —— 页面在后台跨过切换点，回来时也能补上。
     */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_punct_output)
        val prefs = Prefs(this)

        findViewById<TextView>(R.id.text_punct_output_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_punct_output_desc).text = TEXT_HINT
        findViewById<Button>(R.id.btn_punct_output_close).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }

        // 中文态的两项在前、英文态的两项在后（页面正文按「中文态 / 英文态」两行排布）。
        bindField(
            R.id.edit_punct_comma_cn, R.id.label_punct_comma_cn, TEXT_PUNCT_COMMA_CN,
            PunctOutput.DEFAULT_COMMA_CN, { prefs.punctCommaCn }, { prefs.punctCommaCn = it },
        )
        bindField(
            R.id.edit_punct_period_cn, R.id.label_punct_period_cn, TEXT_PUNCT_PERIOD_CN,
            PunctOutput.DEFAULT_PERIOD_CN, { prefs.punctPeriodCn }, { prefs.punctPeriodCn = it },
        )
        bindField(
            R.id.edit_punct_comma_en, R.id.label_punct_comma_en, TEXT_PUNCT_COMMA_EN,
            PunctOutput.DEFAULT_COMMA_EN, { prefs.punctCommaEn }, { prefs.punctCommaEn = it },
        )
        bindField(
            R.id.edit_punct_period_en, R.id.label_punct_period_en, TEXT_PUNCT_PERIOD_EN,
            PunctOutput.DEFAULT_PERIOD_EN, { prefs.punctPeriodEn }, { prefs.punctPeriodEn = it },
        )

        // 空格键左右滑动移动光标：原设置页的开关迁入本页，切换即落盘（键盘下次弹出读新值）。
        findViewById<Switch>(R.id.switch_space_cursor).apply {
            text = TEXT_SPACE_CURSOR
            isChecked = prefs.spaceCursorMove
            setOnCheckedChangeListener { _, checked ->
                prefs.spaceCursorMove = checked
                Diagnostics.i(TAG, "空格左右滑动移动光标: ${if (checked) "开启" else "关闭"}")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /**
     * 绑定一个标点输入框：写栏标题、回填当前值，并在每次文本变化后归一为单字符。
     *
     * 归一后若与框内文本不同就回写（用 [updating] 挡住自触发的下一轮）—— 否则用户看到 2 个字符、
     * 实际只会上屏 1 个，界面与行为对不上。
     */
    private fun bindField(
        editId: Int,
        labelId: Int,
        labelText: String,
        fallback: String,
        getValue: () -> String,
        setValue: (String) -> Unit,
    ) {
        findViewById<TextView>(labelId).text = labelText
        val edit = findViewById<EditText>(editId)
        var updating = false
        edit.setText(getValue())
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val raw = s?.toString().orEmpty()
                val one = PunctOutput.sanitizeField(raw, fallback)
                setValue(one)
                if (raw != one) {
                    updating = true
                    edit.setText(one)
                    edit.setSelection(one.length)
                    updating = false
                }
            }
        })
    }

    private companion object {
        const val TAG = "PunctOutput"

        /** 页面标题与说明（strings.xml 默认禁改，文案在代码里下发） */
        const val TEXT_TITLE = "底栏标点输出"
        const val TEXT_HINT = "逗号/句号键的字符，各限一个；中文态与英文态分开设置"

        /** 四个输入项的栏标题（各限一个字符） */
        const val TEXT_PUNCT_COMMA_CN = "中文逗号"
        const val TEXT_PUNCT_PERIOD_CN = "中文句号"
        const val TEXT_PUNCT_COMMA_EN = "英文逗号"
        const val TEXT_PUNCT_PERIOD_EN = "英文句号"

        /** 空格键左右滑动移动光标开关（原设置页开关迁入本页） */
        const val TEXT_SPACE_CURSOR = "空格键左右移动光标"
    }
}
