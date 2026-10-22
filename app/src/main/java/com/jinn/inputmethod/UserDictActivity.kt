package com.jinn.inputmethod

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 用户词库页：选择台账的查看、手动合并、导出与导入。
 *
 * 台账本身只追加（见 [SelectionLedger]）；本页只做**派生视图**：读全量流水 → 按「词 + 读音」
 * 合并出频次 → 展示 / 导出。台账无上限增长，因此读盘、聚合、导入、SAF 读写**全部在后台线程**，
 * 主线程只渲染与弹框。
 *
 * **导出是加密包**（[UserDictExport]）：外层 AES-256-GCM 壳、内层普通 zip 且条目明文；
 * 密码规则与「导出配置文件」同一套（[ConfigCrypto.validatePassword]，只能用汉字、≥4 位、不重复单字）。
 * 导入时按文件头分流：加密包先要密码再解密，旧版明文导出件照旧直读。
 *
 * **默认关闭**：进入本页时若未开启，先弹说明框，必须手输大写 `OK` 才开启；
 * 关闭则先提示导出，再二次确认（同样输入 `OK`），确认后删除 `user_picks.txt`。
 */
/** 本页日志标识（伴生对象是 private，日志用固定串） */
private const val TAG_NAME = "UserDictActivity"

class UserDictActivity : ComponentActivity() {

    /** 定时换色：到点在页面存活期间重建一次（与设置页其余页面同款） */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    /** 页面专用后台线程：台账可能很大，读 / 聚合 / 导入 / SAF 读写在主线程会卡死 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jinn-user-dict").apply { isDaemon = true }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    override fun onDestroy() {
        // 选位置 / 输密码期间可能残留一份导入暂存件（明文），必须在这里兜住
        clearStaged()
        io.shutdownNow()
        super.onDestroy()
    }

    private lateinit var textSummary: TextView
    private lateinit var listEntries: ListView
    private lateinit var btnExportYaml: Button
    private lateinit var btnExportTsv: Button
    private lateinit var btnExportTxt: Button
    private lateinit var btnImport: Button
    private lateinit var btnClose: Button

    /** 当前合并结果（词, 读音, 次数），导出直接用它 */
    @Volatile
    private var rows: List<Row> = emptyList()

    private var pendingFormat: SelectionLedger.ExportFormat? = null

    /**
     * 导出密码（选保存位置期间暂存）。
     *
     * **刻意不进 `onSaveInstanceState`**：Bundle 会被系统落盘，而密码不该有任何持久化副本。
     * 代价是页面在选位置期间被重建时这次导出作废 —— 回调里发现密码为空就清空壳并提示重试。
     */
    private var pendingPassword: CharArray? = null

    /**
     * 本次导出是否走明文（无密码、无外壳）。
     *
     * 与 [pendingFormat] 一起进 `onSaveInstanceState`：选位置期间页面被重建时，明文分支
     * 不依赖任何内存里的敏感值，靠这个标志就能接着写完（加密分支的密码必丢，见上）。
     */
    private var pendingPlain = false

    /** 导入暂存件（SAF → 缓存目录的一份拷贝；解密 / 解析完即删） */
    private var stagedImport: File? = null

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val format = pendingFormat
        val password = pendingPassword
        val plain = pendingPlain
        pendingFormat = null
        pendingPassword = null
        pendingPlain = false
        if (uri == null) {
            password?.fill('\u0000')
            return@registerForActivityResult
        }
        if (format == null) {
            // 页面被重建：密码已丢（不落 Bundle）。删掉刚建出的空壳，并说明原因，别静默不发生
            password?.fill('\u0000')
            deleteEmptyTarget(uri)
            toast(TEXT_EXPORT_RESTART)
            return@registerForActivityResult
        }
        if (plain) {
            // 明文导出没有密码可丢，重建也不影响
            writePlainExport(uri, format)
            return@registerForActivityResult
        }
        if (password == null) {
            deleteEmptyTarget(uri)
            toast(TEXT_EXPORT_RESTART)
            return@registerForActivityResult
        }
        writeExport(uri, format, password)
    }

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        previewImport(uri)
    }

    private val prefs by lazy { Prefs(this) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 选位置期间若发生重建（旋转 / 定时换肤），回调里的 format 不能丢，否则导出静默不发生
        pendingFormat?.let { outState.putString(KEY_PENDING_FORMAT, it.name) }
        outState.putBoolean(KEY_PENDING_PLAIN, pendingPlain)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_dict)

        findViewById<TextView>(R.id.text_dict_title).text = TEXT_TITLE
        textSummary = findViewById(R.id.text_dict_summary)
        listEntries = findViewById(R.id.list_dict_entries)
        btnExportYaml = findViewById(R.id.btn_export_yaml)
        btnExportTsv = findViewById(R.id.btn_export_tsv)
        btnExportTxt = findViewById(R.id.btn_export_txt)
        btnImport = findViewById(R.id.btn_import_dict)
        btnClose = findViewById(R.id.btn_delete_dict)

        // 页面关闭按钮：与其它子页同款（文案只由 PageChrome 定义）
        findViewById<Button>(R.id.btn_dict_close).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }

        btnExportYaml.text = TEXT_EXPORT_YAML
        btnExportTsv.text = TEXT_EXPORT_TSV
        btnExportTxt.text = TEXT_EXPORT_TXT
        btnImport.text = TEXT_IMPORT
        btnClose.text = TEXT_CLOSE

        btnExportYaml.setOnClickListener { startExport(SelectionLedger.ExportFormat.RIME_YAML) }
        btnExportTsv.setOnClickListener { startExport(SelectionLedger.ExportFormat.TSV) }
        btnExportTxt.setOnClickListener { startExport(SelectionLedger.ExportFormat.TXT) }
        btnImport.setOnClickListener {
            // 加密包是 `application/octet-stream`（多数文件管理器认不出 `.jinn`）：
            // 三种类型都列上，别让用户找不到自己的导出件
            runCatching {
                openDoc.launch(arrayOf("text/*", "application/octet-stream", "application/zip"))
            }.onFailure { toast(TEXT_NO_PICKER) }
        }
        btnClose.setOnClickListener { confirmCloseStepOne() }

        pendingFormat = savedInstanceState?.getString(KEY_PENDING_FORMAT)
            ?.let { runCatching { SelectionLedger.ExportFormat.valueOf(it) }.getOrNull() }
        pendingPlain = savedInstanceState?.getBoolean(KEY_PENDING_PLAIN, false) ?: false

        // 台账只在 PinyinEngine 初始化时绑定；用户可能先开设置页（从未唤起键盘）就导入 ——
        // 不在这里补一次，record/flush 会因 target==null 静默丢弃，而页面还回执「已追加」。
        SelectionLedger.init(applicationContext, prefs.userDictEnabled)

        // 上次导出 / 导入异常中断留下的暂存件（可能含明文 zip）按年龄回收
        runCatching { io.execute { UserDictExport.cleanup(applicationContext) } }

        if (!prefs.userDictEnabled) showEnableDialog()
    }

    override fun onResume() {
        super.onResume()
        // 只在开启态加载（关闭态由说明弹框接管）；onCreate 不读盘，避免首次进页读两遍
        if (prefs.userDictEnabled) loadAsync()
    }

    private fun alive(): Boolean = !isFinishing && !isDestroyed

    // ── 开启 / 关闭 ─────────────────────────────────────────────

    /** 未开启时的说明框：讲清用途、边界、风险与防护，输入「我明白」才开启 */
    private fun showEnableDialog() {
        // 退一档的机器上必须如实追加一行（N13）：不能在任何机型上都声称「锁屏也读不到」
        val body = TEXT_ENABLE_BODY + if (LedgerCrypto.isDegraded(filesDir)) TEXT_ENABLE_BODY_NOLOCK else ""
        val confirm = confirmView(body)
        val edit = confirm.edit
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_ENABLE_TITLE)
            .setView(confirm.root)
            .setNegativeButton(TEXT_CANCEL) { _, _ -> finish() }
            .setPositiveButton(TEXT_ENABLE_OK, null)
            .create()
        dialog.setCancelable(false)
        dialog.show()
        styleDialogButtons(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!isConfirmText(edit.text.toString())) {
                toast(TEXT_NEED_CONFIRM)
                return@setOnClickListener
            }
            prefs.userDictEnabled = true
            SelectionLedger.setEnabled(true)
            dialog.dismiss()
            toast(TEXT_ENABLED)
            loadAsync()
        }
    }

    /** 关闭第一步：先提示导出（可以先去导出，或继续关闭） */
    private fun confirmCloseStepOne() {
        AlertDialog.Builder(this)
            .setTitle(TEXT_CLOSE_TITLE)
            .setMessage(TEXT_CLOSE_STEP1)
            .setPositiveButton(TEXT_EXPORT_FIRST) { _, _ ->
                startExport(SelectionLedger.ExportFormat.RIME_YAML)
            }
            .setNeutralButton(TEXT_CONTINUE_CLOSE) { _, _ -> confirmCloseStepTwo() }
            .setNegativeButton(TEXT_CANCEL, null)
            .show()
    }

    /** 关闭第二步：说明删除不可恢复，输入「我明白」才执行 */
    private fun confirmCloseStepTwo() {
        val confirm = confirmView(TEXT_CLOSE_STEP2)
        val edit = confirm.edit
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_CLOSE_TITLE)
            .setView(confirm.root)
            .setNegativeButton(TEXT_CANCEL, null)
            .setPositiveButton(TEXT_CLOSE_OK, null)
            .create()
        dialog.show()
        styleDialogButtons(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!isConfirmText(edit.text.toString())) {
                toast(TEXT_NEED_CONFIRM)
                return@setOnClickListener
            }
            // 先落开关再删文件：反过来的话，进程内任何一次重读偏好都会把记录重新打开
            prefs.userDictEnabled = false
            val ok = SelectionLedger.deleteFile(this)
            if (!ok) {
                // 删除失败：重新绑定并打开记录，否则偏好显示「已开启」而记录全丢
                prefs.userDictEnabled = true
                SelectionLedger.init(applicationContext, true)
            }
            dialog.dismiss()
            toast(if (ok) TEXT_CLOSED else TEXT_CLOSE_FAILED)
            if (ok) finish()
        }
    }

    /**
     * 确认框的公共视图：可滚动的说明文字 + 确认词输入框。
     *
     * 说明较长，必须能滚动；输入框固定在最下方，避免被键盘挡住后滚不到。
     * 三处危险操作（开启 / 关闭并删除 / 导出明文）共用这一个视图与同一个确认词。
     *
     * **高度先量后建**：ScrollView 一开始就按文字真实高度给（超出 [TEXT_MAX_DP] 才截到上限），
     * 不按屏高比例占位。旧写法先占 0.58 屏高再靠 post 收缩，AlertDialog 的窗口按占位高度建完
     * 就不回缩，中间留下一大片空白（真机：正文才 6 行，窗口有近 2000px 高）。
     */
    private fun confirmView(body: String): ConfirmView {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val text = TextView(this).apply {
            this.text = body
            textSize = 13f
            setLineSpacing(0f, 1.1f)
        }
        // 文字可用宽度 = 屏宽 − 两侧内边距 − 对话框自带的左右边距（约 24dp）
        val textWidthPx = (resources.displayMetrics.widthPixels - pad * 2 - (24 * density).toInt())
            .coerceAtLeast((200 * density).toInt())
        text.measure(
            View.MeasureSpec.makeMeasureSpec(textWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxTextPx = (TEXT_MAX_DP * density).toInt()
        val textHeight = minOf(text.measuredHeight + (4 * density).toInt(), maxTextPx)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(text) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, textHeight),
        )
        val edit = EditText(this).apply {
            hint = TEXT_CONFIRM_HINT
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
        }
        root.addView(
            edit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return ConfirmView(root, edit)
    }

    private class ConfirmView(
        val root: LinearLayout,
        val edit: EditText,
    )

    // ── 词库视图（后台读盘 → 主线程渲染）────────────────────────

    private fun loadAsync() {
        // onDestroy 已 shutdownNow：此后（含后台线程回调里）再提交会抛 RejectedExecutionException，
        // 而工作线程上的未捕获异常会直接终止进程 —— 这里必须自守
        if (isFinishing || isDestroyed) return
        val submitted = runCatching { io.execute { loadOnIo() } }.isSuccess
        if (!submitted) Diagnostics.w(TAG_NAME, "台账加载提交失败（页面已销毁）")
    }

    private fun loadOnIo() {
        val picks = SelectionLedger.readAll(applicationContext)
        // 密钥件缺失时 readAll 已把旧台账改名保留（N2）：这条提示只报一次
        val orphaned = LedgerCrypto.consumeOrphanNotice()
        val degraded = LedgerCrypto.isDegraded(filesDir)
        val merged = SelectionLedger.aggregate(picks)
        val list = merged.entries
            .map { Row(it.key.first, it.key.second, it.value) }
            .sortedWith(compareByDescending<Row> { it.count }.thenBy { it.word })
        val file = File(filesDir, SelectionLedger.FILE_NAME)
        val kb = if (file.isFile) (file.length() + 1023) / 1024 else 0L
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            rows = list
            renderSummary(picks.size, merged.size, kb, degraded)
            bindList()
            if (orphaned) toast(TEXT_ORPHANED)
        }
    }

    private fun renderSummary(picks: Int, distinct: Int, kb: Long, degraded: Boolean = false) {
        val more = if (rows.size > MAX_ROWS) String.format(Locale.US, TEXT_MORE_SUFFIX, rows.size - MAX_ROWS) else ""
        val note = if (degraded) TEXT_SUMMARY_NOLOCK else ""
        textSummary.text = String.format(Locale.US, TEXT_SUMMARY, picks, distinct, kb, more) + note
        val empty = rows.isEmpty()
        btnExportYaml.isEnabled = !empty
        btnExportTsv.isEnabled = !empty
        btnExportTxt.isEnabled = !empty
    }

    private fun bindList() {
        listEntries.adapter = RowAdapter(rows.take(MAX_ROWS))
    }

    // ── 导出（加密包 / 明文文件）──────────────────────────────

    private fun startExport(format: SelectionLedger.ExportFormat) {
        val snapshot = rows
        if (snapshot.isEmpty()) {
            toast(TEXT_NOTHING_TO_EXPORT)
            return
        }
        askExportFormat(format)
    }

    /**
     * 导出格式二选一：加密包（既有）或明文文件（新增）。
     *
     * 明文导出的风险不靠按钮文案讲清 —— 那是下一层「输入我明白」警告框的事；
     * 这一层只把两种形态的差别摆出来，让用户知道自己在选什么。
     *
     * 用两个自定义按钮而不是 `setItems`：本页其余对话框都是这套（[plainTextField] /
     * [confirmView]），视觉一致；而且 `setItems` 的项在无障碍树上拿不到稳定文本，
     * 自动化验证只能盲点。
     */
    private fun askExportFormat(format: SelectionLedger.ExportFormat) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * density).toInt(), pad, 0)
        }
        box.addView(
            TextView(this).apply {
                text = TEXT_FORMAT_HINT
                textSize = 13f
                setLineSpacing(0f, 1.1f)
            },
            lpOf(10),
        )
        // 规格与页面里的四个导出按钮一致（SettingsButton：40dp 高、12dp 圆角描边、13sp）：
        // 早先直接用系统 Button，是个浅灰圆角块，跟整页风格脱节。
        val btnEnc = settingsButton(TEXT_FORMAT_ENCRYPTED)
        val btnPlain = settingsButton(TEXT_FORMAT_PLAIN)
        box.addView(btnEnc, lpOf(6))
        box.addView(btnPlain)
        val dlg = AlertDialog.Builder(this)
            .setTitle(TEXT_FORMAT_TITLE)
            .setView(box)
            .setNegativeButton(TEXT_CANCEL, null)
            .create()
        btnEnc.setOnClickListener {
            dlg.dismiss()
            askExportPassword(format)
        }
        btnPlain.setOnClickListener {
            dlg.dismiss()
            askPlainExportConfirm(format)
        }
        dlg.show()
        styleDialogButtons(dlg)
    }

    /**
     * 明文导出的警告：必须逐字输入「我明白」三个字才放行。
     *
     * 与开启 / 关闭共用 [confirmView] 与同一个确认词（[CONFIRM_WORD]），判据走同一个
     * [isConfirmText] —— 三处危险操作一套口径，不分叉。没有密码可设，走 [writePlainExport]。
     */
    private fun askPlainExportConfirm(format: SelectionLedger.ExportFormat) {
        val confirm = confirmView(TEXT_PLAIN_WARN_BODY)
        val edit = confirm.edit
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_PLAIN_WARN_TITLE)
            .setView(confirm.root)
            .setNegativeButton(TEXT_CANCEL, null)
            .setPositiveButton(TEXT_PLAIN_CONFIRM, null)
            .create()
        dialog.show()
        styleDialogButtons(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!isConfirmText(edit.text.toString())) {
                toast(TEXT_NEED_CONFIRM)
                return@setOnClickListener
            }
            edit.text.clear()
            dialog.dismiss()
            pendingFormat = format
            pendingPlain = true
            runCatching { createDoc.launch(UserDictExport.plainName(format)) }
                .onFailure {
                    pendingFormat = null
                    pendingPlain = false
                    toast(TEXT_NO_PICKER)
                }
        }
    }

    /**
     * 导出前的密码框：两次输入，规则直接复用 [ConfigCrypto.validatePassword]。
     *
     * 输入框刻意**不用**密码类型（与设置页同款）：备份密码只能用汉字，而 `textPassword`
     * 一类在部分 ROM 上会唤起打不出拼音的系统安全键盘。
     */
    private fun askExportPassword(format: SelectionLedger.ExportFormat) {
        val pwd1 = plainTextField(TEXT_PWD_HINT)
        val pwd2 = plainTextField(TEXT_PWD_HINT_CONFIRM)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(
                TextView(this@UserDictActivity).apply {
                    text = TEXT_EXPORT_PWD_DESC
                    textSize = 13f
                },
            )
            addView(pwd1, lpOf(10))
            addView(pwd2, lpOf(6))
        }
        val dialog = secureDialog(
            AlertDialog.Builder(this)
                .setTitle(TEXT_EXPORT_PWD_TITLE)
                .setView(box)
                .setNegativeButton(TEXT_CANCEL, null)
                .setPositiveButton(TEXT_EXPORT_CONFIRM, null)
                .create(),
        )
        dialog.show()
        // 自校验不过时必须让对话框留在原地（默认 positive 会先 dismiss 再回调，密码就白输了）
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // 校验与比对都直接吃 `Editable`（N7）：**不产生 String 副本** —— String 无法清零，
            // 只能等 GC，而这里原先每点一次就多留两三份密码
            when (val check = ConfigCrypto.validatePassword(pwd1.text)) {
                is ConfigCrypto.PasswordCheck.Invalid -> {
                    pwd1.error = check.reason
                    toast(check.reason)
                }

                else -> if (!TextUtils.equals(pwd1.text, pwd2.text)) {
                    pwd2.error = TEXT_PWD_MISMATCH
                    toast(TEXT_PWD_MISMATCH)
                } else {
                    val chars = CharArray(pwd1.text.length) { pwd1.text[it] }
                    // 输入框里的副本用完即清（对话框还在显示，但内容不再需要）
                    pwd1.text.clear()
                    pwd2.text.clear()
                    dialog.dismiss()
                    pendingFormat = format
                    pendingPassword = chars
                    runCatching { createDoc.launch(UserDictExport.suggestedName(format)) }
                        .onFailure {
                            pendingFormat = null
                            pendingPassword?.fill('\u0000')
                            pendingPassword = null
                            toast(TEXT_NO_PICKER)
                        }
                }
            }
        }
    }

    /** 后台造包 + 写 SAF；密码用完即清零 */
    private fun writeExport(uri: Uri, format: SelectionLedger.ExportFormat, password: CharArray) {
        val snapshot = rows
        val submitted = runCatching { io.execute { writeExportOnIo(uri, format, snapshot, password) } }.isSuccess
        if (!submitted) password.fill('\u0000')
    }

    /**
     * 明文导出：直接把词表文本写进 SAF，**不过外壳、不加密、无密码**。
     *
     * 与加密包共用同一份 `SelectionLedger.exportText`（条目形态完全一致），
     * 差别只在壳 —— 用户要的就是「外部可读」。
     * 文件内容本身就是隐私数据，写失败时同样不留空壳（[deleteEmptyTarget]）。
     */
    private fun writePlainExport(uri: Uri, format: SelectionLedger.ExportFormat) {
        val snapshot = rows
        val submitted = runCatching { io.execute { writePlainExportOnIo(uri, format, snapshot) } }.isSuccess
        if (!submitted) toast(TEXT_EXPORT_FAIL)
    }

    private fun writePlainExportOnIo(
        uri: Uri,
        format: SelectionLedger.ExportFormat,
        snapshot: List<Row>,
    ) {
        val ok = try {
            val merged = LinkedHashMap<Pair<String, String>, Int>()
            for (r in snapshot) merged[r.word to r.pinyin] = r.count
            val text = SelectionLedger.exportText(merged, format)
            val out = openTruncatingOutput(uri)
            if (out == null) {
                false
            } else {
                out.use { o ->
                    o.write(text.toByteArray(Charsets.UTF_8))
                    o.flush()
                }
                true
            }
        } catch (t: Throwable) {
            Diagnostics.w(TAG_NAME, "明文导出异常: ${t.javaClass.simpleName}")
            false
        }
        if (!ok) deleteEmptyTarget(uri)
        val count = snapshot.size
        runOnUiThread {
            if (!alive()) return@runOnUiThread
            toast(if (ok) String.format(Locale.US, TEXT_EXPORT_OK, count) else TEXT_EXPORT_FAIL)
        }
    }

    private fun writeExportOnIo(
        uri: Uri,
        format: SelectionLedger.ExportFormat,
        snapshot: List<Row>,
        password: CharArray,
    ) {
        val ok = try {
            buildAndWrite(uri, format, snapshot, password)
        } catch (t: Throwable) {
            Diagnostics.w(TAG_NAME, "导出异常: ${t.javaClass.simpleName}")
            false
        } finally {
            password.fill('\u0000')
        }
        if (!ok) {
            // CreateDocument 已建出目标文件：不删就留一个 0 字节空壳（与 L-1098 同型）。
            // 只删**确实为空**的目标：少数 provider 在重名时会返回既有文档的 uri，
            // 无条件删会把用户上一份备份一起删掉（设置页 `deleteEmptyTarget` 同一口径）。
            deleteEmptyTarget(uri)
        }
        val count = snapshot.size
        runOnUiThread {
            if (!alive()) return@runOnUiThread
            toast(if (ok) String.format(Locale.US, TEXT_EXPORT_OK, count) else TEXT_EXPORT_FAIL)
        }
    }

    /**
     * 造加密包并写入目标位置。
     *
     * 明文 zip 只在缓存目录里存在极短一瞬（[UserDictExport] 的中间件），写完密文立即删除；
     * 写到 SAF 的是**密文**，所以 provider 那一侧不会留下明文。
     */
    private fun buildAndWrite(
        uri: Uri,
        format: SelectionLedger.ExportFormat,
        snapshot: List<Row>,
        password: CharArray,
    ): Boolean {
        val merged = LinkedHashMap<Pair<String, String>, Int>()
        for (r in snapshot) merged[r.word to r.pinyin] = r.count
        val text = SelectionLedger.exportText(merged, format)
        val dir = UserDictExport.cacheDir(this).apply { mkdirs() }
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)
            .format(java.time.LocalDateTime.now())
        val plain = File(dir, "userdict-$stamp.zip")
        val enc = File(dir, "userdict-$stamp$SUFFIX")
        // 在途登记：页面的残留清理（cleanup）按年龄删文件，别把正在读写的这份删掉（N9）
        UserDictExport.markInFlight(plain)
        UserDictExport.markInFlight(enc)
        try {
            plain.writeBytes(UserDictExport.zipOf(UserDictExport.entryName(format), text))
            if (!ConfigCrypto.encrypt(plain, enc, password)) {
                Diagnostics.w(TAG_NAME, "导出失败（加密）")
                return false
            }
            val out = openTruncatingOutput(uri) ?: return false
            out.use { o ->
                enc.inputStream().use { it.copyTo(o) }
                o.flush()
            }
            return true
        } finally {
            plain.delete()
            enc.delete()
            UserDictExport.release(plain)
            UserDictExport.release(enc)
        }
    }

    /**
     * 以「截断写」打开 SAF 输出流。
     *
     * 必须带 `"wt"`：默认的 `"w"` 在部分 provider 上不保证截断，覆盖一个更大的旧文件时会在尾部
     * 留下残骸（读回来是「密码错误或文件已损坏」）。少数 provider 不认 `"wt"`（抛
     * IllegalArgumentException 而不是返回 null），因此**两级都要各自兜住**再退回默认 mode。
     */
    private fun openTruncatingOutput(uri: Uri): java.io.OutputStream? =
        runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { contentResolver.openOutputStream(uri) }.getOrNull()

    /**
     * 删除一个「已创建但没写进任何内容」的 SAF 目标（[ActivityResultContracts.CreateDocument] 会先建空文件）。
     *
     * 只有确实是 0 字节才删：少数 provider 在重名时会返回**既有文档**的 uri，无条件删会毁掉旧备份。
     * AFD 长度常是 `UNKNOWN_LENGTH(-1)`，未知长度一律不删（留个空壳远优于删掉用户的旧文件）。
     */
    private fun deleteEmptyTarget(uri: Uri) {
        val size = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (size != 0L) return
        runCatching { contentResolver.delete(uri, null, null) }
            .onFailure { Diagnostics.w(TAG_NAME, "删除空壳目标失败: ${it.javaClass.simpleName}") }
    }

    private fun plainTextField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        maxLines = 1
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        // 顺序有讲究：`maxLines` 一类属性会回头改写 inputType，而 `setRawInputType` 不走那套单行处理；
        // imeOptions 会重写 TYPE_MASK_FLAGS 那一批位，必须放在 setRawInputType 之前。
        // NO_SUGGESTIONS 是给键盘侧看的：本输入法据此不联想、不记词频（见 InputFieldPrivacy）。
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }

    /** 密码框窗口加 FLAG_SECURE：防截屏 / 录屏 / 最近任务缩略图（与设置页同款，按窗口生效） */
    private fun secureDialog(dialog: AlertDialog): AlertDialog = dialog.apply {
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    // ── 导入（自动识别加密包 / 旧版明文）────────────────────────

    /**
     * 导入第一步：把选中文件拷进缓存（限流），再按文件头分流。
     *
     * 为什么要先落盘：加密包必须整份交给 [ConfigCrypto.decrypt]（它是流式读文件的），
     * 而 SAF 的流只能顺序读一次；同时这一步顺手挡住了误选的巨型文件。
     */
    private fun previewImport(uri: Uri) {
        io.execute {
            val staged = stageImport(uri)
            if (staged == null) {
                uiToast(TEXT_IMPORT_FAIL)
                return@execute
            }
            if (UserDictExport.looksEncrypted(UserDictExport.readHead(staged))) {
                // 加密包：先问密码（在 UI 线程），暂存件留到解密完再删
                runOnUiThread {
                    if (!alive()) {
                        dropStaged(staged)
                        return@runOnUiThread
                    }
                    askImportPassword(staged)
                }
                return@execute
            }
            val read = readCapped(staged)
            dropStaged(staged)
            if (read == null) {
                uiToast(TEXT_IMPORT_FAIL)
                return@execute
            }
            val picked = SelectionLedger.parseImport(read.text)
            if (picked.isEmpty()) {
                uiToast(TEXT_IMPORT_EMPTY)
                return@execute
            }
            showImportPreview(picked, read.truncated)
        }
    }

    /** 加密包导入：先问密码，再解密 → 取词表条目 → 预览 */
    private fun askImportPassword(staged: File) {
        stagedImport = staged
        val pwd = plainTextField(TEXT_PWD_HINT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(
                TextView(this@UserDictActivity).apply {
                    text = TEXT_IMPORT_PWD_DESC
                    textSize = 13f
                },
            )
            addView(pwd, lpOf(10))
        }
        val dialog = secureDialog(
            AlertDialog.Builder(this)
                .setTitle(TEXT_IMPORT_PWD_TITLE)
                .setView(box)
                .setNegativeButton(TEXT_CANCEL) { _, _ -> clearStaged() }
                .setPositiveButton(TEXT_IMPORT_CONFIRM, null)
                .create(),
        )
        dialog.show()
        dialog.setOnCancelListener { clearStaged() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val check = ConfigCrypto.validatePassword(pwd.text)
            if (check is ConfigCrypto.PasswordCheck.Invalid) {
                pwd.error = check.reason
                toast(check.reason)
                return@setOnClickListener
            }
            // 只留一份可擦除的副本（N7）：密码从 EditText 逐字取出，输入框立即清空
            val chars = CharArray(pwd.text.length) { pwd.text[it] }
            pwd.text.clear()
            dialog.dismiss()
            decryptImport(staged, chars)
        }
    }

    /**
     * 解密加密包并导入。
     *
     * 密码以 [CharArray] 传入（N7）：调用方从 `EditText` 逐字取，不产生 `String` 副本 ——
     * `String` 无法擦除，只会在堆里留到 GC，而这里已经有三份副本。
     */
    private fun decryptImport(staged: File, password: CharArray) {
        io.execute {
            val zip = File(staged.parentFile, staged.name + ".zip")
            UserDictExport.markInFlight(zip)
            val decrypted = try {
                ConfigCrypto.decrypt(staged, zip, password)
            } finally {
                password.fill('\u0000')
            }
            clearStaged()
            if (!decrypted) {
                zip.delete()
                UserDictExport.release(zip)
                uiToast(TEXT_IMPORT_DECRYPT_FAIL)
                return@execute
            }
            val entry = UserDictExport.readSingleEntry(zip)
            zip.delete()
            UserDictExport.release(zip)
            if (entry == null) {
                // 解开了但没有词表条目：多半是把「配置文件」备份包选进来了
                Diagnostics.w(TAG_NAME, "导入: 加密包里没有词表条目")
                uiToast(TEXT_IMPORT_NOT_PACKAGE)
                return@execute
            }
            val picked = SelectionLedger.parseImport(entry.second)
            if (picked.isEmpty()) {
                uiToast(TEXT_IMPORT_EMPTY)
                return@execute
            }
            showImportPreview(picked, false)
        }
    }

    private fun showImportPreview(picked: List<Pair<String, String>>, truncated: Boolean) {
        val words = picked.map { it.first }.toSet().size
        runOnUiThread {
            if (!alive()) return@runOnUiThread
            if (truncated) toast(TEXT_IMPORT_TRUNCATED)
            AlertDialog.Builder(this)
                .setTitle(TEXT_IMPORT_CONFIRM_TITLE)
                .setMessage(String.format(Locale.US, TEXT_IMPORT_PREVIEW, picked.size, words))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    io.execute {
                        // appendAll 现在会如实回报「有没有排进写入」（功能被关掉 / 未绑定 / 无有效行）
                        val queued = SelectionLedger.appendAll(picked)
                        val flushed = SelectionLedger.flush()
                        uiToast(
                            when {
                                !queued -> TEXT_IMPORT_NOT_ENABLED
                                !flushed -> String.format(Locale.US, TEXT_IMPORT_PENDING, picked.size)
                                else -> String.format(Locale.US, TEXT_IMPORT_OK, picked.size)
                            },
                        )
                        loadAsync()
                    }
                }
                .show()
        }
    }

    /**
     * 把 SAF 的选中文件拷进缓存（上限 [UserDictExport.MAX_PACKAGE_BYTES]）。
     *
     * @return 缓存文件；读不到 / 空文件 / 超限返回 null（调用方按「读取失败」提示）。
     *   任何失败路径都会把半截暂存件删掉 —— 它可能是一份明文词表。
     */
    private fun stageImport(uri: Uri): File? {
        val dir = UserDictExport.cacheDir(this).apply { mkdirs() }
        val dest = File(dir, "userdict-import-" + System.nanoTime() + ".bin")
        var ok = false
        // 在途登记在写之前：cleanup 与本流程可能并发（N9）
        UserDictExport.markInFlight(dest)
        try {
            val copied = contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var emptyReads = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        // read 允许返回 0（非阻塞流 / 故障 provider）：不处理就是空转死循环
                        if (n == 0) {
                            if (++emptyReads > MAX_EMPTY_READS) {
                                Diagnostics.w(TAG_NAME, "导入: 读取无进展，已放弃")
                                return null
                            }
                            continue
                        }
                        emptyReads = 0
                        total += n
                        if (total > UserDictExport.MAX_PACKAGE_BYTES) {
                            Diagnostics.w(
                                TAG_NAME,
                                "导入: 所选文件超过 ${UserDictExport.MAX_PACKAGE_BYTES} 字节上限",
                            )
                            return null
                        }
                        out.write(buf, 0, n)
                    }
                    total
                }
            }
            ok = copied != null && copied > 0L
            if (copied == 0L) Diagnostics.w(TAG_NAME, "导入: 所选文件是空的")
        } catch (t: Throwable) {
            Diagnostics.w(TAG_NAME, "导入: 读取失败 ${t.javaClass.simpleName}")
            ok = false
        } finally {
            if (!ok) {
                dest.delete()
                UserDictExport.release(dest)
            }
        }
        return if (ok) dest else null
    }

    /**
     * 读入导入件（上限 [IMPORT_MAX_BYTES]）；返回文本与「是否被截断」。
     *
     * 连续空读触顶 ⇒ 返回 null（N12）。原先这里 `break` 出循环，于是把**半份文件**当成
     * 「已读完、未截断」交给 `parseImport` —— 与 `stageImport` 的 `return null` 同型缺陷，
     * 两个读流必须一个口径：读不出就明确失败，别拿半份当全份。
     */
    private fun readCapped(file: File): CapText? = runCatching {
        // 按实际长度开缓冲：小文件不再一开口就要 8MB（导入是手动操作，但没必要白占堆）
        val cap = minOf(IMPORT_MAX_BYTES.toLong(), file.length().coerceAtLeast(1L)).toInt()
        file.inputStream().use { input ->
            val buf = ByteArray(cap)
            var read = 0
            var emptyReads = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                if (n == 0) {
                    if (++emptyReads > MAX_EMPTY_READS) {
                        Diagnostics.w(TAG_NAME, "导入: 读取无进展，已放弃")
                        return null
                    }
                    continue
                }
                emptyReads = 0
                read += n
            }
            // 多读一个字节即可判断是否还有剩余
            val truncated = read >= buf.size && input.read() != -1
            // 截断时把字节退到最后一个完整行：否则半行 / 被砍断的多字节字符会被当成一条词条
            val end = if (truncated) {
                var i = read - 1
                while (i >= 0 && buf[i] != '\n'.code.toByte()) i--
                i + 1
            } else {
                read
            }
            CapText(String(buf, 0, end, Charsets.UTF_8), truncated)
        }
    }.getOrNull()

    /** 删掉导入暂存件（可能含明文，任何出口都必须清）并注销在途登记 */
    private fun clearStaged() {
        val f = stagedImport
        stagedImport = null
        if (f != null) dropStaged(f)
    }

    /** 删一份缓存中间件 + 注销在途登记（N9） */
    private fun dropStaged(f: File) {
        f.delete()
        UserDictExport.release(f)
    }

    private fun uiToast(msg: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) toast(msg)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private data class Row(val word: String, val pinyin: String, val count: Int)
    private data class CapText(val text: String, val truncated: Boolean)

    /**
     * 三列行：频次 / 汉字 / 拼音。
     *
     * 频次列固定宽，汉字与拼音按权重分栏；拼音为空时该列留空。
     */
    private inner class RowAdapter(private val items: List<Row>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val item = items[position]
            (row.getChildAt(0) as TextView).text = "×${item.count}"
            (row.getChildAt(1) as TextView).text = item.word
            (row.getChildAt(2) as TextView).text = item.pinyin
            return row
        }

        private fun buildRow(): LinearLayout = LinearLayout(this@UserDictActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(
                TextView(this@UserDictActivity).apply {
                    width = dp(64)
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                },
            )
            addView(
                TextView(this@UserDictActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                    textSize = 16f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text_primary))
                },
            )
            addView(
                TextView(this@UserDictActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text_secondary))
                },
            )
        }
    }

    /**
     * 造一个与页面按钮同规格的 [Button]（代码侧复刻 `@style/SettingsButton`）。
     *
     * 对话框里的按钮拿不到 XML 的 style（`AlertDialog` 的自定义视图是代码搭的），
     * 而系统默认 Button 在这里是个浅灰圆角块，与整页的描边按钮明显两种风格。
     */
    private fun settingsButton(label: String): Button = Button(this).apply {
        this.text = label
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (40 * resources.displayMetrics.density).toInt(),
        )
        background = androidx.core.content.ContextCompat.getDrawable(
            this@UserDictActivity,
            R.drawable.btn_aurora_secondary,
        )
        setTextColor(androidx.core.content.ContextCompat.getColor(
            this@UserDictActivity,
            R.color.text_primary,
        ))
        textSize = 13f
        gravity = android.view.Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        isAllCaps = false
    }

    /**
     * 把对话框的按钮刷成与页面一致的规格。
     *
     * AlertDialog 的默认按钮是系统样式（大写、加粗、主题色），本页其余入口都是
     * `SettingsButton`（13sp、不大写、描边底）。两套并排放会显得对话框是外来物。
     * 必须在 `show()` 之后调（`getButton` 之前返回 null）。
     */
    private fun styleDialogButtons(dialog: AlertDialog) {
        for (which in intArrayOf(
            AlertDialog.BUTTON_POSITIVE,
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL,
        )) {
            val btn = dialog.getButton(which) ?: continue
            btn.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary))
            btn.textSize = 13f
            btn.isAllCaps = false
            btn.setBackgroundResource(R.drawable.btn_aurora_secondary)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lpOf(topDp: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(topDp) }

    companion object {
        /** 列表只铺这么多行：全量在导出件里，页面不为几万行付渲染代价 */
        const val MAX_ROWS = 500

        const val TEXT_TITLE = "用户词库"
        const val TEXT_EXPORT_YAML = "导出 Rime 词库"
        const val TEXT_EXPORT_TSV = "导出 TSV"
        const val TEXT_EXPORT_TXT = "导出词表"
        const val TEXT_IMPORT = "导入"
        const val TEXT_CLOSE = "关闭用户词库并删除记录"
        const val TEXT_IMPORT_CONFIRM_TITLE = "导入词库"
        const val TEXT_SUMMARY = "共 %d 次选择 · %d 个词条 · 文件 %d KB%s"
        const val TEXT_MORE_SUFFIX = "（只列前 " + MAX_ROWS + " 条，其余见导出件）"
        const val TEXT_NO_PICKER = "没有可用的文件选择器"
        const val TEXT_EXPORT_OK = "已导出 %d 条"
        const val TEXT_EXPORT_FAIL = "导出失败"
        const val TEXT_NOTHING_TO_EXPORT = "还没有可导出的词"
        const val TEXT_IMPORT_FAIL = "读取文件失败"
        const val TEXT_IMPORT_EMPTY = "文件里没有可导入的词"
        const val TEXT_IMPORT_TRUNCATED = "文件超过 8MB，只导入了前一部分"
        const val TEXT_IMPORT_PREVIEW = "将追加 %d 次选择（%d 个词）。\n只增不删，导入后不可撤销。"
        const val TEXT_IMPORT_OK = "已追加 %d 条"
        const val TEXT_IMPORT_NOT_ENABLED = "用户词库未开启，未写入"
        const val TEXT_IMPORT_PENDING = "已排入 %d 条，尚未全部写完，稍后可在列表确认"

        const val TEXT_ENABLE_TITLE = "开启用户词库"
        const val TEXT_ENABLE_OK = "开启"
        const val TEXT_CANCEL = "取消"
        /** 确认框文字区的最大高度（dp）：超出才滚动，避免长说明把窗口撑满屏 */
        private const val TEXT_MAX_DP = 260

        const val TEXT_CONFIRM_HINT = "现在输入三个汉字：\"我明白\""
        const val TEXT_NEED_CONFIRM = "请输入「我明白」三个字"
        const val TEXT_ENABLED = "已开启，之后的选词才会被记录"
        const val TEXT_CLOSE_TITLE = "关闭用户词库"
        const val TEXT_CLOSE_STEP1 = "将删除全部记录（user_picks.txt），不可恢复。建议先导出。"
        const val TEXT_EXPORT_FIRST = "先去导出"
        const val TEXT_CONTINUE_CLOSE = "继续关闭"
        const val TEXT_CLOSE_STEP2 = "删除 files/user_picks.txt 并停止记录，不可恢复。\n输入「我明白」确认。"
        const val TEXT_CLOSE_OK = "删除并关闭"
        const val TEXT_CLOSED = "已删除记录并关闭"
        const val TEXT_CLOSE_FAILED = "关闭失败（文件可能被占用），已恢复记录开关"

        const val TEXT_ENABLE_BODY = """记录范围
· 仅 26 键候选上屏：点选、空格取首、追加词（含合并整词）
· 一行一词＋读音，只追加
· 不记：粘贴、语音、翻译、图库、符号、英文、密码框、NO_SUGGESTIONS

用途
· 词频统计；导出加密词库包，可再导入

存储与防护
· files/user_picks.txt 与 files/user_freq.txt，加密存储（密钥由系统密钥库托管）
· 不联网、不上传、不进系统备份与设备迁移
· 其他应用受 UID 隔离，无 root 不可读；离线取证镜像读不到明文词条
  （本机已 root、或有人以本应用身份调用系统密钥库时不在此列）
· 默认关闭；关闭即删文件"""

        /**
         * 退一档机型（本机 Keymaster 不支持 `setUnlockedDeviceRequired`）追加的一行。
         *
         * 上面的 `TEXT_ENABLE_BODY` 不能写死「锁屏不可解密」—— 在 MuMu 这类机器上它不成立，
         * 文案与事实不符比少说一句更糟（N13）。
         */
        const val TEXT_ENABLE_BODY_NOLOCK = """

· 注意：本机密钥库不支持锁屏隔离，锁屏状态下的取证防护在此机型上不可用"""

        /** 密钥件缺失、旧数据已改名保留时的提示（一次性） */
        const val TEXT_ORPHANED = "密钥文件丢失：旧的用户词库记录已改名保留，本次从空表重新开始"

        /** 摘要行末尾的退一档提示 */
        const val TEXT_SUMMARY_NOLOCK = "\n本机密钥库不支持锁屏隔离（其余防护不变）"

        // ── 导出 / 导入的密码与加密包文案（待定稿）──────────────────
        const val TEXT_EXPORT_PWD_TITLE = "设置导出密码"
        const val TEXT_EXPORT_PWD_DESC = "导出件是加密压缩包：整个包体加密，包内的词表是明文。" +
            "密码只能用汉字，本机不保存，忘记后无法解开。"
        const val TEXT_PWD_HINT = "导出密码（4~64 个不相同的汉字）"
        const val TEXT_PWD_HINT_CONFIRM = "再输一次导出密码"
        const val TEXT_PWD_MISMATCH = "两次输入的密码不一致"
        const val TEXT_EXPORT_CONFIRM = "开始导出"
        const val TEXT_EXPORT_RESTART = "页面已重建，请重新点导出"
        const val TEXT_IMPORT_PWD_TITLE = "输入导出密码"
        const val TEXT_IMPORT_PWD_DESC = "该文件是加密的词库包，请输入导出时设置的密码。\n密码错误 / 文件被改动 都无法解密"
        const val TEXT_IMPORT_CONFIRM = "解密"
        const val TEXT_IMPORT_DECRYPT_FAIL = "密码错误或文件已损坏，无法解密"
        const val TEXT_IMPORT_NOT_PACKAGE = "这个加密包里没有词表（像是配置备份包）"

        /** 单次导入读入上限（明文文本） */
        const val IMPORT_MAX_BYTES = 8 * 1024 * 1024

        /** 读流允许的连续「空读」次数（`InputStream.read` 允许返回 0） */
        const val MAX_EMPTY_READS = 64

        /** 加密包后缀（与 [UserDictExport.FILE_SUFFIX] 一致） */
        const val SUFFIX = UserDictExport.FILE_SUFFIX

        // ── 导出格式选择与明文导出警告 ───────────────────────────
        const val TEXT_FORMAT_TITLE = "选择导出格式"
        const val TEXT_FORMAT_ENCRYPTED = "加密包（需密码，外部不可读）"
        const val TEXT_FORMAT_PLAIN = "明文文件（任何编辑器可读）"
        const val TEXT_FORMAT_HINT =
            "加密包整个文件加密，只能用本 App 输入密码后导入。\n" +
                "明文文件就是纯文本，任何编辑器、任何应用都能直接打开。"

        const val TEXT_PLAIN_WARN_TITLE = "导出明文词库"
        const val TEXT_PLAIN_WARN_BODY = """这份词库，记录你打过的汉字，涉及个人隐私，当心泄密。

明文文件不加任何保护，能读取这个文件的人或应用，都能看到你的全部词条与读音。确认要导出，请输入「我明白」。"""

        const val TEXT_PLAIN_CONFIRM = "导出明文"

        const val KEY_PENDING_FORMAT = "pending_format"
        const val KEY_PENDING_PLAIN = "pending_plain"
    }
}

/** 本页三处危险操作的确认口径：逐字「我明白」（允许首尾空白） */
internal fun isConfirmText(input: String): Boolean = input.trim() == CONFIRM_WORD

/** 确认词：开启用户词库、关闭并删除记录、导出明文词库共用同一个 */
internal const val CONFIRM_WORD = "我明白"
