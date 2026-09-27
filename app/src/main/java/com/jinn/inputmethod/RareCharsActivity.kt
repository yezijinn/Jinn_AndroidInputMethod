package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 加更多生僻字页：从设置页「加更多生僻字」按钮进入（2026-09-27 起取代原单一开关）。
 *
 * 两档可选字表的来源（`tools/dict_builder/build_dicts.py` 生成 `tier2_chars.txt.xz` /
 * `tier3_chars.txt.xz`）：
 *  - 档 2 = `docs/所有词库/单字/单字注音_二级简体.txt`（837 字 / 885 条）
 *  - 档 3 = `docs/所有词库/单字/单字注音_三级简体.txt`（2,923 字 / 3,112 条）
 *
 * **依赖关系：档 3 必须先开档 2** —— 三级字比二级更生僻，「只开三级」没有意义。
 * 档 2 关闭时档 3 的勾选框置灰并自动取消（[syncTier3Enabled]）。
 *
 * 勾选即落盘并**即时生效**（[PinyinEngine.setRareTiers] 只改查询期放行判据，
 * 不需要重启输入法 —— 这与旧「显示生僻字」开关的「重启生效」不同）。
 */
class RareCharsActivity : Activity() {

    private lateinit var tierList: LinearLayout
    private lateinit var checkTier2: CheckBox
    private lateinit var checkTier3: CheckBox

    /** 「全开 / 全关」批量改勾选态期间挂起逐项回调：两个框只写一次盘 */
    private var bulk = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rare_chars)
        findViewById<TextView>(R.id.text_rare_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_rare_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_rare_close).apply {
            text = TEXT_CLOSE
            setOnClickListener { finish() }
        }
        findViewById<Button>(R.id.btn_rare_all).apply {
            text = TEXT_ALL
            setOnClickListener { setAll(true) }
        }
        findViewById<Button>(R.id.btn_rare_none).apply {
            text = TEXT_NONE
            setOnClickListener { setAll(false) }
        }
        tierList = findViewById(R.id.rare_tier_list)
    }

    /**
     * 每次回到前台都重画勾选态（不在 `onCreate` 里画）。
     *
     * 设置页可以导入备份、也可以被系统回收后重建，而本页可能仍留在返回栈里 ——
     * 只在 `onCreate` 画一次会让页面停在做旧快照上，此时再拨任一档会把**另一档**按旧显示写回
     * （等于回滚导入结果），见 `BUG.md` L-48。
     */
    override fun onStart() {
        super.onStart()
        renderRows()
    }

    /** 按当前设置渲染勾选态；显示值 = 引擎实际生效值（档 3 还要乘以档 2，见 [apply]） */
    private fun renderRows() {
        val density = resources.displayMetrics.density
        val prefs = Prefs(this)
        tierList.removeAllViews()
        checkTier2 = addRow(TIER2_LABEL, TIER2_DESC, prefs.rareTier2, density)
        // 档 3 显示 prefs 与档 2 的**与**：导入 / 手改的非法组合（只有 rare_tier3=true）在引擎侧
        // 按 `tier2 && tier3` 当关处理，页面照原值显示就会出现「界面勾着、候选不受影响」的错位。
        checkTier3 = addRow(TIER3_LABEL, TIER3_DESC, prefs.rareTier3 && prefs.rareTier2, density)
        checkTier2.setOnCheckedChangeListener { _, checked ->
            if (bulk) return@setOnCheckedChangeListener
            // 关档 2 必须连带关档 3（依赖关系；引擎侧 setRareTiers 也会再兜一次）
            if (!checked) checkTier3.isChecked = false
            syncTier3Enabled()
            apply()
        }
        checkTier3.setOnCheckedChangeListener { _, _ ->
            if (bulk) return@setOnCheckedChangeListener
            apply()
        }
        syncTier3Enabled()
    }

    /** 一行：勾选框 + 说明小字；返回勾选框供上层接线 */
    private fun addRow(label: String, desc: String, checked: Boolean, density: Float): CheckBox {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        val check = CheckBox(this).apply {
            text = label
            isChecked = checked
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
        }
        val hint = TextView(this).apply {
            text = desc
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding((28 * density).toInt(), 0, 0, 0)
        }
        row.addView(check)
        row.addView(hint)
        tierList.addView(row)
        return check
    }

    /** 档 3 只在档 2 开着时可点：置灰而不是隐藏，让依赖关系可见 */
    private fun syncTier3Enabled() {
        checkTier3.isEnabled = checkTier2.isChecked
        checkTier3.alpha = if (checkTier2.isChecked) 1f else 0.5f
    }

    /** 全开 / 全关：先同步勾选态（挂起回调），最后统一写一次 */
    private fun setAll(checked: Boolean) {
        bulk = true
        checkTier2.isChecked = checked
        checkTier3.isChecked = checked
        bulk = false
        syncTier3Enabled()
        apply()
    }

    /**
     * 写盘 + 即时生效。
     *
     * 依赖关系在这里**再兜一次**（档 3 只可能在档 2 开着时成立）：导入的备份、手工改过的
     * prefs 都可能造出「只开档 3」的非法组合。
     */
    private fun apply() {
        val tier2 = checkTier2.isChecked
        val tier3 = tier2 && checkTier3.isChecked
        Prefs(this).apply {
            rareTier2 = tier2
            rareTier3 = tier3
        }
        PinyinEngine.setRareTiers(tier2, tier3)
        Diagnostics.i(TAG, "生僻字档位: 档2=${if (tier2) "开" else "关"} 档3=${if (tier3) "开" else "关"}")
    }

    private companion object {
        const val TAG = "RareCharsActivity"

        // 文案在代码里下发：strings.xml 默认禁改，与设置页 / 模糊音页的 TEXT_* 同做法
        const val TEXT_TITLE = "加更多生僻字"
        const val TEXT_DESC = "默认只收常用字（5,613 字）。\n开启档位后，这些字连同它们组成的词一起放行；\n" +
            "档 3 必须先开档 2。"
        const val TEXT_CLOSE = "X"
        const val TEXT_ALL = "全开"
        const val TEXT_NONE = "全关"
        const val TIER2_LABEL = "增加二级生僻字885个"
        // 例字必须取自对应档位表本身：改档位表时同步核对（「亟 谌 髯」曾在此、后被移入默认档，
        // 举例就变成了「默认档的字」；守卫 `RareCharsFilterTest.档位页例字必须落在对应档里` 钉住）
        const val TIER2_DESC = "较常用的生僻字：谶 阚 仝 瑭 崤 一类"
        const val TIER3_LABEL = "增加三级生僻字3112个"
        const val TIER3_DESC = "更少用的字：覅 锊 掴 砗 一类（需先开启二级）"
    }
}
