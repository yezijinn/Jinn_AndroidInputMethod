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

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val format = pendingFormat
        pendingFormat = null
        if (uri == null || format == null) return@registerForActivityResult
        writeExport(uri, format)
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
            runCatching { openDoc.launch(arrayOf("text/*", "application/octet-stream")) }
                .onFailure { toast(TEXT_NO_PICKER) }
        }
        btnClose.setOnClickListener { confirmCloseStepOne() }

        pendingFormat = savedInstanceState?.getString(KEY_PENDING_FORMAT)
            ?.let { runCatching { SelectionLedger.ExportFormat.valueOf(it) }.getOrNull() }

        // 台账只在 PinyinEngine 初始化时绑定；用户可能先开设置页（从未唤起键盘）就导入 ——
        // 不在这里补一次，record/flush 会因 target==null 静默丢弃，而页面还回执「已追加」。
        SelectionLedger.init(applicationContext, prefs.userDictEnabled)

        if (!prefs.userDictEnabled) showEnableDialog()
    }

    override fun onResume() {
        super.onResume()
        // 只在开启态加载（关闭态由说明弹框接管）；onCreate 不读盘，避免首次进页读两遍
        if (prefs.userDictEnabled) loadAsync()
    }

    // ── 开启 / 关闭 ─────────────────────────────────────────────

    /** 未开启时的说明框：讲清用途、边界、风险与防护，输入大写 OK 才开启 */
    private fun showEnableDialog() {
        val confirm = confirmView(TEXT_ENABLE_BODY)
        val edit = confirm.edit
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_ENABLE_TITLE)
            .setView(confirm.root)
            .setNegativeButton(TEXT_CANCEL) { _, _ -> finish() }
            .setPositiveButton(TEXT_ENABLE_OK, null)
            .create()
        dialog.setCancelable(false)
        dialog.setOnShowListener {
            fitConfirmText(confirm)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isConfirmOk(edit.text.toString())) {
                    toast(TEXT_NEED_OK)
                    return@setOnClickListener
                }
                prefs.userDictEnabled = true
                SelectionLedger.setEnabled(true)
                dialog.dismiss()
                toast(TEXT_ENABLED)
                loadAsync()
            }
        }
        dialog.show()
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

    /** 关闭第二步：说明删除不可恢复，输入大写 OK 才执行 */
    private fun confirmCloseStepTwo() {
        val confirm = confirmView(TEXT_CLOSE_STEP2)
        val edit = confirm.edit
        val dialog = AlertDialog.Builder(this)
            .setTitle(TEXT_CLOSE_TITLE)
            .setView(confirm.root)
            .setNegativeButton(TEXT_CANCEL, null)
            .setPositiveButton(TEXT_CLOSE_OK, null)
            .create()
        dialog.setOnShowListener {
            fitConfirmText(confirm)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isConfirmOk(edit.text.toString())) {
                    toast(TEXT_NEED_OK)
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
        dialog.show()
    }

    /**
     * 确认框的公共视图：可滚动的说明文字 + 大写 OK 输入框。
     *
     * 说明较长，必须能滚动；输入框固定在最下方，避免被键盘挡住后滚不到。
     */
    private fun confirmView(body: String): ConfirmView {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val text = TextView(this).apply {
            text = body
            textSize = 13f
            setLineSpacing(0f, 1.1f)
        }
        val maxTextPx = (resources.displayMetrics.heightPixels * 0.58f).toInt()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(text) }
        // 先按上限占位（不裁字），show 之后按真实宽度收缩到文字高度
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxTextPx))
        val edit = EditText(this).apply {
            hint = TEXT_OK_HINT
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
        }
        root.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ConfirmView(root, edit, scroll, text, maxTextPx)
    }

    /** 按 ScrollView 的真实宽度重测文字高度，能放下就收紧到文字高度 */
    private fun fitConfirmText(v: ConfirmView) {
        v.scroll.post {
            val w = v.scroll.width
            if (w <= 0) return@post
            v.text.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val measured = v.text.measuredHeight + (4 * resources.displayMetrics.density).toInt()
            val h = minOf(measured, v.maxHeight)
            if (v.scroll.layoutParams.height != h) {
                v.scroll.layoutParams = v.scroll.layoutParams.apply { height = h }
            }
        }
    }

    private class ConfirmView(
        val root: LinearLayout,
        val edit: EditText,
        val scroll: ScrollView,
        val text: TextView,
        val maxHeight: Int,
    )

    /** 必须逐字是大写 OK（允许首尾空格，不许小写） */
    private fun isConfirmOk(input: String): Boolean = isConfirmOkText(input)

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
        val merged = SelectionLedger.aggregate(picks)
        val list = merged.entries
            .map { Row(it.key.first, it.key.second, it.value) }
            .sortedWith(compareByDescending<Row> { it.count }.thenBy { it.word })
        val file = File(filesDir, SelectionLedger.FILE_NAME)
        val kb = if (file.isFile) (file.length() + 1023) / 1024 else 0L
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            rows = list
            renderSummary(picks.size, merged.size, kb)
            bindList()
        }
    }

    private fun renderSummary(picks: Int, distinct: Int, kb: Long) {
        val more = if (rows.size > MAX_ROWS) String.format(Locale.US, TEXT_MORE_SUFFIX, rows.size - MAX_ROWS) else ""
        textSummary.text = String.format(Locale.US, TEXT_SUMMARY, picks, distinct, kb, more)
        val empty = rows.isEmpty()
        btnExportYaml.isEnabled = !empty
        btnExportTsv.isEnabled = !empty
        btnExportTxt.isEnabled = !empty
    }

    private fun bindList() {
        listEntries.adapter = RowAdapter(rows.take(MAX_ROWS))
    }

    private fun startExport(format: SelectionLedger.ExportFormat) {
        val snapshot = rows
        if (snapshot.isEmpty()) {
            toast(TEXT_NOTHING_TO_EXPORT)
            return
        }
        pendingFormat = format
        runCatching { createDoc.launch("user_dict.${format.ext}") }
            .onFailure {
                pendingFormat = null
                toast(TEXT_NO_PICKER)
            }
    }

    private fun writeExport(uri: Uri, format: SelectionLedger.ExportFormat) {
        val snapshot = rows
        io.execute {
            val merged = LinkedHashMap<Pair<String, String>, Int>()
            for (r in snapshot) merged[r.word to r.pinyin] = r.count
            val text = SelectionLedger.exportText(merged, format)
            val ok = runCatching {
                // provider 不认 "wt" 时退回默认 mode（与设置页导出同款）
                val out = contentResolver.openOutputStream(uri, "wt")
                    ?: contentResolver.openOutputStream(uri)
                out?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                    it.flush()
                } != null
            }.getOrElse { false }
            if (!ok) {
                // CreateDocument 已建出目标文件：不删就留一个 0 字节空壳（与 L-1098 同型）
                runCatching { contentResolver.delete(uri, null, null) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(if (ok) String.format(Locale.US, TEXT_EXPORT_OK, snapshot.size) else TEXT_EXPORT_FAIL)
            }
        }
    }

    private fun previewImport(uri: Uri) {
        io.execute {
            val read = readCapped(uri)
            if (read == null) {
                uiToast(TEXT_IMPORT_FAIL)
                return@execute
            }
            val picked = SelectionLedger.parseImport(read.text)
            if (picked.isEmpty()) {
                uiToast(TEXT_IMPORT_EMPTY)
                return@execute
            }
            val words = picked.map { it.first }.toSet().size
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (read.truncated) toast(TEXT_IMPORT_TRUNCATED)
                AlertDialog.Builder(this)
                    .setTitle(TEXT_IMPORT_CONFIRM_TITLE)
                    .setMessage(String.format(Locale.US, TEXT_IMPORT_PREVIEW, picked.size, words))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        io.execute {
                            SelectionLedger.appendAll(picked)
                            SelectionLedger.flush()
                            uiToast(String.format(Locale.US, TEXT_IMPORT_OK, picked.size))
                            loadAsync()
                        }
                    }
                    .show()
            }
        }
    }

    /** 读入导入件（上限 8MB）；返回文本与「是否被截断」 */
    private fun readCapped(uri: Uri): CapText? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(IMPORT_MAX_BYTES)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n <= 0) break
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
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

        const val TEXT_ENABLE_TITLE = "开启用户词库"
        const val TEXT_ENABLE_OK = "开启"
        const val TEXT_CANCEL = "取消"
        const val TEXT_OK_HINT = "输入大写 OK"
        const val TEXT_NEED_OK = "请输入大写 OK"
        const val TEXT_ENABLED = "已开启，之后的选词才会被记录"
        const val TEXT_CLOSE_TITLE = "关闭用户词库"
        const val TEXT_CLOSE_STEP1 = "将删除全部记录（user_picks.txt），不可恢复。建议先导出。"
        const val TEXT_EXPORT_FIRST = "先去导出"
        const val TEXT_CONTINUE_CLOSE = "继续关闭"
        const val TEXT_CLOSE_STEP2 = "删除 files/user_picks.txt 并停止记录，不可恢复。\n输入大写 OK 确认。"
        const val TEXT_CLOSE_OK = "删除并关闭"
        const val TEXT_CLOSED = "已删除记录并关闭"
        const val TEXT_CLOSE_FAILED = "关闭失败（文件可能被占用），已恢复记录开关"

        const val TEXT_ENABLE_BODY = """记录范围
· 仅 26 键候选上屏：点选、空格取首、追加词（含合并整词）
· 一行一词＋读音，只追加
· 不记：粘贴、语音、翻译、图库、符号、英文、密码框、NO_SUGGESTIONS

用途
· 词频统计；导出 Rime / TSV / 词表，可再导入

存储与防护
· files/user_picks.txt，明文，应用私有目录
· 不联网、不上传、不进系统备份与设备迁移
· 其他应用受 UID 隔离，无 root 不可读；root 或取证镜像可读
· 默认关闭；关闭即删文件"""

        const val IMPORT_MAX_BYTES = 8 * 1024 * 1024
        const val KEY_PENDING_FORMAT = "pending_format"
    }
}

/** 开启/关闭的确认口径：逐字大写 `OK`（允许首尾空格，小写不算） */
internal fun isConfirmOkText(input: String): Boolean = input.trim() == "OK"
