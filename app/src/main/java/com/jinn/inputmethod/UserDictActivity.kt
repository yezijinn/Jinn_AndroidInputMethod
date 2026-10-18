package com.jinn.inputmethod

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
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

        btnExportYaml.text = TEXT_EXPORT_YAML
        btnExportTsv.text = TEXT_EXPORT_TSV
        btnExportTxt.text = TEXT_EXPORT_TXT
        btnImport.text = TEXT_IMPORT

        btnExportYaml.setOnClickListener { startExport(SelectionLedger.ExportFormat.RIME_YAML) }
        btnExportTsv.setOnClickListener { startExport(SelectionLedger.ExportFormat.TSV) }
        btnExportTxt.setOnClickListener { startExport(SelectionLedger.ExportFormat.TXT) }
        btnImport.setOnClickListener {
            // 只收文本：三种导出格式都是纯文本
            runCatching { openDoc.launch(arrayOf("text/*", "application/octet-stream")) }
                .onFailure { toast(TEXT_NO_PICKER) }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        // 回到本页时流水可能又长了（刚在别处打过字）
        load()
    }

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
        if (rows.isEmpty()) return
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
        const val TEXT_IMPORT_CONFIRM_TITLE = "导入词库"
        const val TEXT_SUMMARY = "共 %d 次选择 · %d 个词条 · 文件 %d KB%s"
        const val TEXT_MORE_SUFFIX = "（列表只显示前 " + MAX_ROWS + " 条，其余见导出件）"
        const val TEXT_NO_PICKER = "没有可用的文件选择器"
        const val TEXT_EXPORT_OK = "已导出 %d 条"
        const val TEXT_EXPORT_FAIL = "导出失败"
        const val TEXT_IMPORT_FAIL = "读取文件失败"
        const val TEXT_IMPORT_EMPTY = "文件里没有可导入的词"
        const val TEXT_IMPORT_PREVIEW = "将追加 %d 次选择（%d 个词）。\n只增不删，导入后不可撤销。"
        const val TEXT_IMPORT_OK = "已追加 %d 条"

        const val IMPORT_MAX_BYTES = 8 * 1024 * 1024
    }
}
