package com.jinn.inputmethod

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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

/**
 * 用户词库页：选择台账的查看、手动合并、导出与导入。
 *
 * 台账本身只追加（见 [SelectionLedger]）；本页只做**派生视图**：读全量流水 → 按「词 + 读音」
 * 合并出频次 → 展示 / 导出。导出与导入都是手动操作，因此可以直接在主线程读几 MB 的文件；
 * 真正写流水仍走台账的后台队列。
 *
 * **默认关闭**：进入本页时若未开启，先弹说明框，必须手输大写 `OK` 才开启；
 * 关闭则先提示导出，再二次确认（同样输入 `OK`），确认后删除 `user_picks.txt`。
 */
class UserDictActivity : ComponentActivity() {

    /** 定时换色：到点在页面存活期间重建一次（与设置页其余页面同款） */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

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

    private lateinit var textSummary: TextView
    private lateinit var listEntries: ListView
    private lateinit var btnExportYaml: Button
    private lateinit var btnExportTsv: Button
    private lateinit var btnExportTxt: Button
    private lateinit var btnImport: Button
    private lateinit var btnClose: Button

    /** 当前合并结果（词, 读音, 次数），导出直接用它 */
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
        btnClose = findViewById(R.id.btn_close_dict)

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

        if (prefs.userDictEnabled) {
            load()
        } else {
            showEnableDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.userDictEnabled) load()
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
                load()
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
                val ok = SelectionLedger.deleteFile(this)
                prefs.userDictEnabled = false
                dialog.dismiss()
                toast(if (ok) TEXT_CLOSED else TEXT_CLOSE_FAILED)
                finish()
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

    /**
     * 按 ScrollView 的真实宽度重测文字高度，能放下就收紧到文字高度。
     *
     * 在 `show` 之后调用：创建时还不知道对话框的实际宽度，按屏幕宽度估算会把高度算少（文字被裁）。
     */
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

    // ── 词库视图 ───────────────────────────────────────────────

    /** 读全量流水 → 合并 → 刷新摘要与列表 */
    private fun load() {
        val picks = SelectionLedger.readAll(this)
        val merged = SelectionLedger.aggregate(picks)
        rows = merged.entries
            .map { Row(it.key.first, it.key.second, it.value) }
            .sortedWith(compareByDescending<Row> { it.count }.thenBy { it.word })

        val file = File(filesDir, SelectionLedger.FILE_NAME)
        val kb = if (file.isFile) (file.length() + 1023) / 1024 else 0L
        val more = if (rows.size > MAX_ROWS) String.format(Locale.US, TEXT_MORE_SUFFIX, rows.size - MAX_ROWS) else ""
        textSummary.text = String.format(Locale.US, TEXT_SUMMARY, picks.size, merged.size, kb, more)

        listEntries.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            rows.take(MAX_ROWS).map { "${it.word}   ${it.pinyin}   ×${it.count}" },
        )
        val empty = rows.isEmpty()
        btnExportYaml.isEnabled = !empty
        btnExportTsv.isEnabled = !empty
        btnExportTxt.isEnabled = !empty
    }

    private fun startExport(format: SelectionLedger.ExportFormat) {
        if (rows.isEmpty()) {
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
        val merged = LinkedHashMap<Pair<String, String>, Int>()
        for (r in rows) merged[r.word to r.pinyin] = r.count
        val text = SelectionLedger.exportText(merged, format)
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } != null
        }.getOrElse { false }
        toast(if (ok) String.format(Locale.US, TEXT_EXPORT_OK, rows.size) else TEXT_EXPORT_FAIL)
    }

    private fun previewImport(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                // 上限 8MB：词库是纯文本，超过这个量级多半选错了文件
                val buf = ByteArray(IMPORT_MAX_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            }
        }.getOrNull()
        if (text == null) {
            toast(TEXT_IMPORT_FAIL)
            return
        }
        val picked = SelectionLedger.parseImport(text)
        if (picked.isEmpty()) {
            toast(TEXT_IMPORT_EMPTY)
            return
        }
        val words = picked.map { it.first }.toSet().size
        AlertDialog.Builder(this)
            .setTitle(TEXT_IMPORT_CONFIRM_TITLE)
            .setMessage(String.format(Locale.US, TEXT_IMPORT_PREVIEW, picked.size, words))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SelectionLedger.importPicks(picked)
                SelectionLedger.flush()
                toast(String.format(Locale.US, TEXT_IMPORT_OK, picked.size))
                load()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private data class Row(val word: String, val pinyin: String, val count: Int)

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
        const val TEXT_MORE_SUFFIX = "（列表只显示前 " + MAX_ROWS + " 条，其余见导出件）"
        const val TEXT_NO_PICKER = "没有可用的文件选择器"
        const val TEXT_EXPORT_OK = "已导出 %d 条"
        const val TEXT_EXPORT_FAIL = "导出失败"
        const val TEXT_NOTHING_TO_EXPORT = "还没有可导出的词"
        const val TEXT_IMPORT_FAIL = "读取文件失败"
        const val TEXT_IMPORT_EMPTY = "文件里没有可导入的词"
        const val TEXT_IMPORT_PREVIEW = "将追加 %d 次选择（%d 个词）。\n只增不删，导入后不可撤销。"
        const val TEXT_IMPORT_OK = "已追加 %d 条"

        const val TEXT_ENABLE_TITLE = "开启用户词库"
        const val TEXT_ENABLE_OK = "开启"
        const val TEXT_CANCEL = "取消"
        const val TEXT_OK_HINT = "输入大写 OK"
        const val TEXT_NEED_OK = "请输入大写 OK"
        const val TEXT_ENABLED = "已开启，之后的选词才会被记录"
        const val TEXT_CLOSE_TITLE = "关闭用户词库"
        const val TEXT_CLOSE_STEP1 = "关闭将删除全部记录（user_picks.txt），不可恢复。建议先导出。"
        const val TEXT_EXPORT_FIRST = "先去导出"
        const val TEXT_CONTINUE_CLOSE = "继续关闭"
        const val TEXT_CLOSE_STEP2 = "删除 files/user_picks.txt 并停止记录，不可恢复。\n输入大写 OK 确认。"
        const val TEXT_CLOSE_OK = "删除并关闭"
        const val TEXT_CLOSED = "已删除记录并关闭"
        const val TEXT_CLOSE_FAILED = "关闭失败，请重试"

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
    }
}

/** 开启/关闭的确认口径：逐字大写 `OK`（允许首尾空格，小写不算） */
internal fun isConfirmOkText(input: String): Boolean = input.trim() == "OK"
