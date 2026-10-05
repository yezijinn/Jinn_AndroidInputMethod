package com.jinn.inputmethod

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * 快捷补充：直接编辑自定义词库的**源文本**，保存即等于走一遍导入。
 *
 * 与「导入.txt文档」共享同一份内容：导入成功后把原文写进 `dicts/custom_user.src.txt`
 * （见 [CustomDicts.writeSource]），本页打开时回显；本页保存也写同一文件。所以两个入口
 * 互为「同一份文本的两种打法」，用户先导入再进来补两行也不会互相覆盖。
 *
 * 保存结果用 `RESULT_OK`（带条数与跳过行数）回传给 [DictManagerActivity]，由它统一提示并
 * 重启输入法 —— 重启窗口的状态位在那边，本页自己 kill 会把「已保存」的提示一起吞掉。
 *
 * 版面与词库页同源（深蓝背景 + 顶栏右关闭），输入区走等宽字体并占满剩余高度：
 * 「一行一条」的文本在视觉上就是一列，眼睛好对齐。
 */
class CustomDictEditActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var textStatus: TextView
    private lateinit var saveButton: TextView

    /**
     * 顶栏「✕」。保存期间与「保存」一起置灰并拦返回键（BUG.md L-860）：
     * 此前保存中点 ✕ 会先 `finish()`，写盘继续但结果回执被 `isFinishing` 早退吃掉 ——
     * 包已更新却不重启引擎、词库页也不刷新，用户以为「没保存」。
     */
    private lateinit var closeButton: TextView

    /** 保存线程在跑时按钮置灰，防连点（与词库页的 importingCustom 同款） */
    @Volatile
    private var saving = false

    /**
     * 本次因源文本超出回填阈值而**没有**把内容铺进编辑器（BUG.md L-871）。
     *
     * 置上后保存保持禁用：编辑器是空的，用户顺手补几条再保存，就会把整份大词表（源文本 + 包）
     * 整体替换掉，而提示只显示「已保存 N 条」—— 那是静默的数据丢失。
     */
    private var refillSkipped = false

    /**
     * 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 对一次表并排下一次，
     * [onStop] 撤掉 —— 页面在后台跨过切换点，回来时也能补上。
     */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /** 主题应用点：与设置页同一套（早于 onCreate，避免先按系统配置渲染一帧再换色） */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 键盘由 Manifest 的 `stateAlwaysVisible|adjustResize` 拉起（本页用途就是「直接开写」，
        // 不该再让用户点一次输入框）。这里**不再**调 window.setSoftInputMode：它会把 adjust 位
        // 清成 UNSPECIFIED、与 Manifest 声明打架（BUG.md L-855）；真机实测同样会弹键盘。

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }
        root.addView(buildTopBar())
        root.addView(hint(TEXT_HINT_LINE1), matchWrap(top = 8))
        root.addView(hint(TEXT_HINT_LINE2), matchWrap(top = 2))
        root.addView(hint(TEXT_HINT_LINE3), matchWrap(top = 2))

        textStatus = TextView(this).apply {
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            setPadding(dp(16), dp(6), dp(16), 0)
            visibility = View.GONE
        }
        root.addView(textStatus, matchWrap())

        editor = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            hint = TEXT_EDITOR_HINT
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        // 外观走卡片同款（底色 / 圆角 / 描边 / 内边距）；padding 由 dressAsCard 统一给
        PageStyle.dressAsCard(editor)
        root.addView(
            editor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply {
                topMargin = dp(8)
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(10)
            },
        )

        setContentView(root)
        Diagnostics.i(TAG, "CustomDictEditActivity: 打开快捷补充页")
        loadSource()
    }

    override fun onResume() {
        super.onResume()
        editor.requestFocus()
    }

    /**
     * 保存中拦返回键（BUG.md L-860）：返回键的 `finish()` 与点 ✕ 是同一个后果 ——
     * 写盘继续跑，而结果回执被 `isFinishing` 早退吃掉（包已更新却不重启引擎、词库页不刷新）。
     */
    @Deprecated("onBackPressed 已废弃，但此页是 Activity（非 ComponentActivity），无 OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (saving) {
            setStatus(TEXT_SAVING_WAIT)
            return
        }
        super.onBackPressed()
    }

    // ── 载入 / 保存 ──────────────────────────────────────────

    /**
     * 回显源文本（与「导入.txt文档」共享同一份）。
     *
     * 读放后台：上限 8MB 的文本一次性读进内存并铺到 EditText，主线程做会掉帧。
     * 读不出来时**提示**而不是静默留空 —— 否则用户会以为词库内容丢了。
     */
    private fun loadSource() {
        val app = applicationContext
        Thread {
            val file = CustomDicts.sourceFile(app)
            val text = CustomDicts.readSource(file)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    text == null -> setStatus(TEXT_READ_FAIL)
                    text.isEmpty() -> Unit
                    // 用户已开始输入（打开即弹键盘，大文本回填可达数百毫秒）：跳过回填，别把刚敲的内容盖掉（BUG.md L-849）
                    editor.text.isNotEmpty() -> {
                        Diagnostics.i(TAG, "快捷补充：回填跳过（编辑框已有 ${editor.text.length} 字符）")
                        setStatus(TEXT_SKIP_REFILL)
                    }
                    // 体验阈值（BUG.md L-870）：`EditText.setText` 在主线程同步排版，真机实测 8M 字符
                    // 会卡住主线程 18 秒（Skipped 1083 frames / Davey 18.067s）——超过阈值就不铺，
                    // 改为指路「导入.txt文档」。此时编辑器仍为空，点保存走「还没有内容」分支，不会误清词库。
                    !shouldRefill(text.length) -> {
                        Diagnostics.i(TAG, "快捷补充：源文本 ${text.length} 字符超过回填阈值，跳过铺入")
                        setStatus(TEXT_TOO_LARGE_TO_REFILL.format(text.length / 10000))
                        // 连保存一并停用（BUG.md L-871）：编辑器是空的，若允许保存，
                        // 用户补几条就会把整份大词表整体替换掉，且提示只显示「已保存 N 条」
                        refillSkipped = true
                        saveButton.isEnabled = false
                        saveButton.alpha = 0.45f
                    }
                    else -> editor.setText(text)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 保存 = 导入：校验归一 → 原子写包 → 原子写源文本 → 回传条数交给词库页重启生效。
     *
     * 无合法词条时**不落盘**（与导入同一口径）：清空词库请用卡片上的「删除」，
     * 免得一次误编辑把已有词库清掉。
     */
    private fun save() {
        if (saving) return
        // 备份恢复在途时拒绝写（BUG.md L-848）：恢复线程写的是同一个 custom_user.txt.xz，
        // 两边都走「临时名 → rename」，后 rename 者胜 —— 保存的成果会被旧包静默盖掉。
        if (ConfigBackupManager.importing) {
            setStatus(TEXT_CONFIG_IMPORTING)
            return
        }
        val text = editor.text.toString()
        if (text.isBlank()) {
            setStatus(TEXT_EMPTY_INPUT)
            return
        }
        // 这里只做**内存保护**：原文超过两倍上限就不进后台（formatHuman 会为全文再造一份副本，
        // 见 L-858）。真正的数据闸在 saveHuman 里按**格式化后**的文本判（BUG.md L-852）——
        // 此前直接在入口按原文拦，原文超限而格式化后不超限的内容会被无辜拒绝。
        if (text.length > CustomDicts.MAX_INPUT_CHARS * 2) {
            setStatus(TEXT_TOO_BIG)
            return
        }
        saving = true
        setStatus(TEXT_SAVING)
        // 「保存」与「✕」一起置灰：保存中关闭会让写盘结果回执丢失（BUG.md L-860）
        saveButton.isEnabled = false
        saveButton.alpha = 0.45f
        closeButton.isEnabled = false
        closeButton.alpha = 0.45f
        // 编辑器一并冻结（BUG.md L-866）：保存用的是上面 `text` 这个快照，等待期间补敲的字不会进词库，
        // 而保存完成后页面会直接 finish —— 那些字会静默消失（重开编辑页也看不到）
        editor.isEnabled = false
        val app = applicationContext
        Thread {
            // 与词库页导入同款：解析 + xz 压缩是重活，降后台优先级，别和前台输入争 CPU
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var ok = false
            var entries = 0
            var skipped = 0
            var filtered = 0
            var dropped = 0
            val status = try {
                val syllables = CustomDicts.loadSyllables(app)
                // 解析 → 判闸 → 落盘（源文本归一后**先**落、包**后**落）全在 saveHuman 里，见其
                // 两条不变量（BUG.md L-851 / L-856）。本页此前那份「先 writePack 再判闸、源文本写
                // 失败只记日志」已删除：截断时会写坏包、且包新文本旧
                val report = CustomDicts.saveHuman(dictDir(), text, syllables)
                entries = report.entries
                skipped = report.skipped
                filtered = report.filtered
                dropped = report.dropped
                when (report.gate) {
                    CustomDicts.SaveGate.TOO_BIG -> TEXT_TOO_BIG
                    CustomDicts.SaveGate.TOO_MANY -> TEXT_TOO_MANY
                    CustomDicts.SaveGate.NO_VALID -> TEXT_NO_VALID.format(report.skipped)
                    // 两档写盘失败文案不同（BUG.md L-861）：前者磁盘没变，后者文本已落、只差词库
                    CustomDicts.SaveGate.WRITE_FAIL_SOURCE -> TEXT_WRITE_FAIL
                    CustomDicts.SaveGate.WRITE_FAIL_PACK -> TEXT_WRITE_FAIL_PACK
                    CustomDicts.SaveGate.BUSY -> TEXT_BUSY
                    CustomDicts.SaveGate.OK -> {
                        ok = true
                        ""
                    }
                }
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "快捷补充保存失败: ${t.javaClass.simpleName}")
                TEXT_WRITE_FAIL
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saving = false
                // 跳过回填时保存保持停用（BUG.md L-871）：无条件恢复会把「补几条 = 整体替换」放回来
                if (!refillSkipped) {
                    saveButton.isEnabled = true
                    saveButton.alpha = 1f
                }
                closeButton.isEnabled = true
                closeButton.alpha = 1f
                editor.isEnabled = true
                if (ok) {
                    Diagnostics.i(
                        TAG,
                        "快捷补充保存: $entries 条 / 跳过 $skipped 行 / 打不出 $filtered 条 / 同键挤掉 $dropped 条",
                    )
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_ENTRIES, entries)
                            .putExtra(EXTRA_SKIPPED, skipped)
                            .putExtra(EXTRA_FILTERED, filtered)
                            .putExtra(EXTRA_DROPPED, dropped),
                    )
                    finish()
                } else {
                    setStatus(status)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 词库目录（包与源文本都在这里；与词库页、备份侧同一处 `filesDir/dicts`） */
    private fun dictDir(): File = File(filesDir, PinyinEngine.OPT_DICT_DIR)

    // ── 版面 ────────────────────────────────────────────────

    /**
     * 顶栏：左标题、右「保存」+ 关闭（与词库页同款 #141C33 深蓝顶栏）。
     *
     * 「保存」放顶栏而不是底部：本页打开即弹键盘，而 Android 10 真机上键盘是**覆盖**窗口
     * （`adjustResize` 未缩短布局），底部按钮会被键盘压住点不到 —— 2026-10-05 真机实证。
     */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.card_bg))
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        bar.addView(TextView(this).apply {
            text = TEXT_TITLE
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveButton = TextView(this).apply {
            text = TEXT_SAVE
            contentDescription = TEXT_SAVE
            setTextColor(getColor(R.color.accent))
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { save() }
        }
        bar.addView(saveButton, wrapWrap())
        closeButton = TextView(this).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setTextColor(getColor(R.color.text_secondary))
            textSize = 20f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                // 保存中先拦住（BUG.md L-860）：写盘已完成但回执被 finish 吃掉 = 静默半生效
                if (saving) {
                    setStatus(TEXT_SAVING_WAIT)
                    return@setOnClickListener
                }
                Diagnostics.i(TAG, "CustomDictEditActivity: 用户关闭页面")
                finish()
            }
        }
        bar.addView(closeButton, wrapWrap())
        return bar
    }

    /** 顶栏下方的静态提示行（每句一个 TextView，不折行） */
    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(dp(16), 0, dp(16), 0)
    }

    /** 更新动态状态行（自动显示出来） */
    private fun setStatus(text: String) {
        textStatus.text = text
        textStatus.visibility = View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun wrapWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {

        /** 词库页从结果里取「已保存条数 / 跳过行数 / 打不出的条数」，复用同一句结果文案 */
        const val EXTRA_ENTRIES = "custom_entries"
        const val EXTRA_SKIPPED = "custom_skipped"
        const val EXTRA_FILTERED = "custom_filtered"

        /** 同键词表超 100 被丢下的条数（BUG.md L-864） */
        const val EXTRA_DROPPED = "custom_dropped"

        // 文案在代码里下发（与词库页的 TEXT_CUSTOM_* 同做法，strings.xml 只承载卡片按钮）
        const val TEXT_TITLE = "快捷补充"
        const val TEXT_HINT_LINE1 = "# 开头是注释\n每行一条：你好 ni hao\n拼音用'空格'分隔每个字的音节"
        const val TEXT_HINT_LINE2 = "这里的内容与「导入.txt文档」共用同一份文本。"
        const val TEXT_HINT_LINE3 = "词组不受字表限制：生僻、繁体 不启用的都能输出"
        const val TEXT_EDITOR_HINT = "# 示范例子：\n许嵩 xu song\n冯禧 feng xi\n黄龄 huang ling\n黄霄雲 huang xiao yun"
        const val TEXT_SAVE = "保存"
        const val TEXT_SAVING = "正在保存并生成词库…"
        const val TEXT_READ_FAIL = "无法读取已保存的内容（文件过大或损坏）"
        const val TEXT_EMPTY_INPUT = "还没有内容：每行写「词 空格 拼音」后再保存"
        const val TEXT_TOO_BIG = "内容超过 800 万字符上限，请精简后重试"
        const val TEXT_CONFIG_IMPORTING = "配置恢复进行中，暂不可修改词库"
        const val TEXT_SKIP_REFILL = "已跳过回填：编辑框里已有你输入的内容"
        const val TEXT_TOO_MANY = "词条超过 5 万条上限，请精简后重试"
        const val TEXT_NO_VALID = "没有可保存的合法词条（跳过 %d 行）"
        const val TEXT_WRITE_FAIL = "保存失败，请重试"
        const val TEXT_WRITE_FAIL_PACK = "内容已保留，词库生成失败：请再点一次保存"
        const val TEXT_BUSY = "正在写入词库，请稍候再试"
        const val TEXT_SAVING_WAIT = "正在保存，请稍候…"
        const val TEXT_TOO_LARGE_TO_REFILL = "内容约 %d 万字符，已跳过回填：请用「导入.txt文档」整体替换（此页保存已停用）"

        /** 回填体验阈值（字符）：见 [shouldRefill] 与 BUG.md L-870 */
        const val MAX_REFILL_CHARS = 1_000_000

        /**
         * 是否把源文本铺进编辑器（纯函数，便于单测）。
         *
         * 数据侧上限是 [CustomDicts.MAX_INPUT_CHARS]（8M 字符），但 UI 侧承受不了：
         * `EditText.setText` 在主线程同步构建 Spannable 并排版，真机实测 880k 行 / 8.1MB
         * 会让主线程卡 18 秒（BUG.md L-870），所以回填单独设一条更低的体验阈值。
         */
        internal fun shouldRefill(textLength: Int): Boolean = textLength <= MAX_REFILL_CHARS

        private const val TAG = "CustomDictEdit"
    }
}
