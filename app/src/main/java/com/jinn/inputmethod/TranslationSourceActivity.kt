package com.jinn.inputmethod

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView

/**
 * 「翻译原文范围」设置页：**每家服务方独立配置**
 * ① 「哪些内容算翻译原文」（[TranslationScope] 四选一）；② 单次翻译的字节上限。
 *
 * 生效方式：IME 每次点「翻译」都从 [Prefs] 现读这两项（`translateScopeOf` / `translateMaxBytesOf`），
 * 所以本页改完不需要重启输入法。
 *
 * 落盘时机与翻译设置页同款：下拉 / 单选**即时**写，数字输入框在 [onPause] 写并显式
 * [Prefs.flush] —— 输入框仍聚焦时进程被强停会丢刚键入的值（与凭据页同一取舍：
 * 逐键写盘会把「1」这类中间状态也存下来）。
 */
class TranslationSourceActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var spinnerProvider: Spinner
    private lateinit var groupScope: RadioGroup
    private lateinit var radios: List<RadioButton>
    private lateinit var textScopeDetail: TextView
    private lateinit var editMaxBytes: EditText
    private lateinit var textOfficial: TextView

    /** 当前正在编辑的服务方；初值在 [onStart] 里按 `prefs.translateProvider` 定（见那里的说明） */
    private var currentId: TranslationProviderId = TranslationProviderId.DEFAULT

    /** 用户是否在本页动过服务方下拉：动过就不再按 prefs 复位，否则刚选完就被弹回去 */
    private var providerTouched = false

    /**
     * 程序化设置控件期间为 true：下拉位置 / 单选 / 输入框文本的写入不触发落盘回调，
     * 否则「载入配置」会被当成「用户改了配置」再写回去。
     */
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与两个凭据页同款防护：防截屏 / 录屏 / 投屏 / 最近任务缩略图。
        // ⚠ 投屏（ADB / scrcpy）下整页不可见是**预期行为**、不是缺陷：
        // PopupWindow 会继承本窗口的 secure 标志，详见 TranslationSettingsActivity 的完整说明。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContentView(R.layout.activity_translation_source)
        prefs = Prefs(this)

        findViewById<TextView>(R.id.text_source_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_source_desc).text = TEXT_DESC
        findViewById<TextView>(R.id.label_source_provider).text = TEXT_PROVIDER
        findViewById<TextView>(R.id.label_source_scope).text = TEXT_SCOPE
        findViewById<TextView>(R.id.label_source_max_bytes).text = TEXT_MAX_BYTES
        findViewById<TextView>(R.id.text_source_truncate).text = TEXT_TRUNCATE
        findViewById<Button>(R.id.btn_source_close).apply {
            text = TEXT_CLOSE
            contentDescription = TEXT_CLOSE_DESC
            setOnClickListener { finish() }
        }

        spinnerProvider = findViewById(R.id.spinner_source_provider)
        groupScope = findViewById(R.id.group_source_scope)
        textScopeDetail = findViewById(R.id.text_source_scope_detail)
        editMaxBytes = findViewById(R.id.edit_source_max_bytes)
        textOfficial = findViewById(R.id.text_source_official)
        radios = listOf(
            findViewById(R.id.radio_scope_line_before),
            findViewById(R.id.radio_scope_line_full),
            findViewById(R.id.radio_scope_before_all),
            findViewById(R.id.radio_scope_all),
        )
        // 单选按钮的文案跟着枚举走：以后加一档范围，这里的按钮数量对不上时下面会自动跳过
        // （`getOrNull` 而不是下标，避免「枚举加了、布局没加」直接崩在 onCreate）
        TranslationScope.entries.forEachIndexed { index, scope ->
            radios.getOrNull(index)?.text = scope.label
        }

        loading = true
        spinnerProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            TranslationProviderId.entries.map { it.label },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerProvider.setOnTouchListener { view, event ->
            providerTouched = true
            // ACTION_UP 补 performClick 供无障碍服务识别（与翻译设置页同款）
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (loading) return
                val picked = TranslationProviderId.entries.getOrNull(position) ?: return
                // 位置没变 = 程序化设置的回调（Spinner 的选中通知是 **post** 投递，布尔闸门盖不住）
                if (picked == currentId) return
                // 切服务方 = 换一份配置：先把手头输入框里的值落盘，再载入新的一家
                saveMaxBytes()
                currentId = picked
                loadValues()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        groupScope.setOnCheckedChangeListener { _, checkedId ->
            if (loading) return@setOnCheckedChangeListener
            val scope = TranslationScope.entries.getOrNull(radios.indexOfFirst { it.id == checkedId })
                ?: return@setOnCheckedChangeListener
            prefs.setTranslateScopeOf(currentId, scope.id)
            Diagnostics.i(TAG, "原文范围: ${currentId.id} → ${scope.id}")
            textScopeDetail.text = scope.detail
        }
        loading = false

        loadValues()
    }

    /**
     * 每次回前台都重载（覆盖「备份导入改写过配置」的场景）。
     *
     * 下拉位置只在**用户没动过**时按 prefs 复位：动过就保持他的选择，否则刚选完切个页面
     * 回来就被弹回当前服务方（2026-10-01 审查 L-215）。
     */
    override fun onStart() {
        super.onStart()
        if (!providerTouched) {
            currentId = TranslationProviderId.of(prefs.translateProvider)
            loading = true
            spinnerProvider.setSelection(TranslationProviderId.entries.indexOf(currentId))
            loading = false
        }
        loadValues()
    }

    /** 离开页面即落盘并等一次写入（理由见类注释；[Prefs.flush] 与凭据页同款） */
    override fun onPause() {
        super.onPause()
        saveMaxBytes()
        prefs.flush()
    }

    /** 载入当前服务方的一份配置（程序化写入全部走 [loading] 闸门） */
    private fun loadValues() {
        loading = true
        val scope = TranslationScope.of(prefs.translateScopeOf(currentId))
        radios.getOrNull(TranslationScope.entries.indexOf(scope))?.isChecked = true
        textScopeDetail.text = scope.detail
        editMaxBytes.setText(prefs.translateMaxBytesOf(currentId).toString())
        // hint 给该家的出厂默认值：用户清空输入框后，一眼能看到「不填就是它」
        editMaxBytes.hint = currentId.defaultMaxBytes.toString()
        textOfficial.text = officialNoteOf(currentId)
        loading = false
    }

    /**
     * 把输入框里的值写回当前服务方。
     *
     * 空串（用户清空输入框）按**出厂默认**处理而不是 0：0 会被 [Prefs.translateMaxBytesOf]
     * 钳到 1，等于把这家改成「什么都翻不了」—— 而用户的动作语义是「清掉手填值」。
     */
    private fun saveMaxBytes() {
        val raw = editMaxBytes.text?.toString()?.trim().orEmpty()
        // 0 / 负数视为「恢复默认」（2026-10-01 修复 L-257）：以前填 0 会被钳到 1，该家变成
        // 「每次只翻 1 字节」，界面只回填一个 1 且没有任何解释，用户难以归因
        val parsed = raw.toIntOrNull()?.takeIf { it >= TranslationText.MIN_MAX_BYTES }
        prefs.setTranslateMaxBytesOf(currentId, parsed ?: currentId.defaultMaxBytes)
        // 回填**真实生效值**（2026-10-01 审查 L-225）：钳位后的数才是下次翻译真正用的；
        // 显示未生效的数会让用户以为「填 0 就是不限」（实际存 1）。
        val effective = prefs.translateMaxBytesOf(currentId).toString()
        if (editMaxBytes.text?.toString() != effective) editMaxBytes.setText(effective)
    }

    /**
     * 该家的官方单次上限说明（只写**已查到出处**的数值，没查到的如实说「无公开上限」）。
     *
     * 阿里云 / Azure 官方按**字符**计，这里按字节填：说明里写出换算关系，用户自己就能对准 ——
     * 默认值取「官方字符数」（保守，任何文本都不撞服务端错误），想用满额度就按 3 字节/汉字调大。
     */
    private fun officialNoteOf(id: TranslationProviderId): String = when (id) {
        TranslationProviderId.ALIYUN -> NOTE_ALIYUN
        TranslationProviderId.AZURE -> NOTE_AZURE
        TranslationProviderId.BAIDU -> NOTE_BAIDU
        TranslationProviderId.BAIDU_LLM -> NOTE_BAIDU_LLM
        TranslationProviderId.DEEPL -> NOTE_DEEPL
        TranslationProviderId.OPENAI -> NOTE_OPENAI
    }

    private companion object {
        const val TAG = "TranslationSource"

        const val TEXT_TITLE = "翻译原文范围"
        const val TEXT_DESC = "两件事各自独立保存：①「哪些内容算原文」② 单次翻译的字节上限（超出时" +
            "从前面开始取、舍弃后面的）。\n" +
            "这两项都在「没有选中内容」时才生效：选中了内容就翻选中的那一段，译文原地替换选区，" +
            "与光标位置、下两项都无关。"
        const val TEXT_PROVIDER = "翻译服务"
        const val TEXT_SCOPE = "哪些内容算翻译原文"
        /** 可填范围用常量插值：改 [TranslationText.MAX_MAX_BYTES] 时文案自动跟随，不会脱节 */
        val TEXT_MAX_BYTES = "单次翻译的字节上限（可填 ${TranslationText.MIN_MAX_BYTES} 至 " +
            "${TranslationText.MAX_MAX_BYTES} 字节）"
        const val TEXT_TRUNCATE = "统一规则：原文按 UTF-8 字节数算，超出上限时从前面开始取、" +
            "舍弃后面的。\n译文插在光标处（通常即原文之后）并另起一行；原文一个字都不动。" +
            "\n选中模式：原文就是选中的那一段，译文原地替换它（不补换行、不改动选区以外的内容）。"
        const val TEXT_CLOSE = "关闭"
        const val TEXT_CLOSE_DESC = "关闭翻译原文范围设置"

        const val NOTE_ALIYUN = "阿里云官方：单次 5000 字符（超出报错 10008）。UTF-8 中文 3 字节/字，" +
            "默认 5000 字节是保守值（约 1600 汉字）；主要翻中文可改为 15000（≈5000 汉字）。" +
            "\n额度：每月 100 万字符免费，每月 1 日 0 点刷新、不结转。常见错误：" +
            "InvalidAccessKeyId / SignatureDoesNotMatch → 认证类；Throttling → 限流。"
        val NOTE_AZURE = "Azure 官方：单次 50000 字符。默认 50000 字节是保守值；主要翻中文可改为 " +
            "${TranslationText.MAX_MAX_BYTES}（≈3.3 万汉字，已到本机单次读取上界）。" +
            "\n额度：F0 免费层每月 200 万字符。常见错误：401 密钥无效；403 免费层额度用尽；429 请求过频。"
        const val NOTE_BAIDU = "百度官方：单次 6000 字节（官方口径就是字节，已按官方填）。" +
            "约合 2000 汉字。\n额度：标准版 5 万字符/月；个人认证后 100 万字符/月，每月 1 日刷新。" +
            "常见错误码：54001 签名错误；54003 频率受限；54004 余额不足。"
        const val NOTE_BAIDU_LLM = "百度大模型接口：官方文档写 q 上限 6000 字符、建议单次 2000 字符以内，" +
            "默认 32768 字节偏大，建议调到 6000 字节以内。\n额度：认证后一次性 100 万字符（不按月重置）。"
        val NOTE_DEEPL = "DeepL 官方：单次请求体上限 128 KiB = 131072 字节；本机单次最多读取 " +
            "${TranslationText.MAX_MAX_BYTES} 字节（输入法框架的限制），已按本机上界填 —— " +
            "官方更大的额度在本机用不上。\n额度：Developer 免费计划一次性 100 万字符。" +
            "常见错误：403 密钥无效；456 额度用尽。"
        const val NOTE_OPENAI = "OpenAI 兼容接口的上限由服务方模型决定（通常几万到几十万 token）：" +
            "默认 32768 字节（≈1 万汉字），可自行调大。\n费用与额度由所选服务方决定，与本应用无关。"
    }
}
