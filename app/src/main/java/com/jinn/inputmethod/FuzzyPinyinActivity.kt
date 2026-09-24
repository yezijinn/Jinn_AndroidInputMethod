package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 模糊音容错页：从设置页「增加模糊拼音」按钮进入。
 *
 * 每组一行勾选框（清单见 [FuzzyPinyin.GROUPS]），**勾选即落盘并即时生效**
 * （[Prefs.fuzzyPinyinMask] + [PinyinEngine.setFuzzyMask]）——不设「保存」按钮，与符号排序页
 * 「改动即落盘」一致。规则、上限与顺序契约全在 [FuzzyPinyin] 与 [PinyinEngine.query]，本页只负责展示与写盘。
 */
class FuzzyPinyinActivity : Activity() {

    private lateinit var groupList: LinearLayout

    /** 「全开 / 全关」批量改勾选态期间挂起逐项回调：11 个框只写一次盘 */
    private var bulk = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fuzzy_pinyin)
        findViewById<TextView>(R.id.text_fuzzy_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_fuzzy_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_fuzzy_close).apply {
            text = TEXT_CLOSE
            setOnClickListener { finish() }
        }
        findViewById<Button>(R.id.btn_fuzzy_all).apply {
            text = TEXT_ALL
            setOnClickListener { setAll(true) }
        }
        findViewById<Button>(R.id.btn_fuzzy_none).apply {
            text = TEXT_NONE
            setOnClickListener { setAll(false) }
        }
        groupList = findViewById(R.id.fuzzy_group_list)
        renderRows()
    }

    /** 按当前掩码渲染勾选态；每次进入都重画，避免与设置页/备份导入后的值不一致 */
    private fun renderRows() {
        val density = resources.displayMetrics.density
        val mask = Prefs(this).fuzzyPinyinMask
        groupList.removeAllViews()
        for (group in FuzzyPinyin.GROUPS) {
            val check = CheckBox(this).apply {
                text = group.label
                isChecked = mask and group.bit != 0
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
                // 掩码每次都从 Prefs 现读：不缓存快照，避免与批量写盘/备份导入抢写
                setOnCheckedChangeListener { _, checked ->
                    if (!bulk) applyMask(if (checked) currentMask() or group.bit else currentMask() and group.bit.inv())
                }
            }
            groupList.addView(check)
        }
    }

    /** 全开 / 全关：先同步勾选态（挂起回调），最后统一写一次 */
    private fun setAll(checked: Boolean) {
        bulk = true
        for (i in 0 until groupList.childCount) {
            (groupList.getChildAt(i) as? CheckBox)?.isChecked = checked
        }
        bulk = false
        applyMask(if (checked) FuzzyPinyin.MASK_ALL else FuzzyPinyin.NONE)
    }

    private fun currentMask(): Int = Prefs(this).fuzzyPinyinMask

    /** 写入掩码并立即生效（[PinyinEngine.setFuzzyMask] 只改查询派生，不需要重启输入法） */
    private fun applyMask(mask: Int) {
        Prefs(this).fuzzyPinyinMask = mask
        PinyinEngine.setFuzzyMask(currentMask())
    }

    private companion object {
        // 文案在代码里下发：strings.xml 默认禁改，与设置页的 TEXT_* 同做法
        const val TEXT_TITLE = "模糊音容错"
        const val TEXT_DESC = "按自己的口音勾选不分的音。\n勾选后，某个音打不出想要的字时，\n" +
            "会把该音的其他读法作为补充候选加上\n（精确候选一个不动、不被替换）。"
        const val TEXT_CLOSE = "X"
        const val TEXT_ALL = "全开"
        const val TEXT_NONE = "全关"
    }
}
