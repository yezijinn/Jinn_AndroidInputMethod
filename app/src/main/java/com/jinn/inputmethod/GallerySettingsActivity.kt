package com.jinn.inputmethod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * 「图库快贴功能」页：图库相关设置集中于此。
 *
 * 统一收口目录绑定 / 清除绑定 / 自动返回 / 缩略图布局四项，避免散落在设置页与键盘面板两处。
 *
 * 键盘面板里的缩略图调节保留（看图时随手可调），与本页读写同一组偏好。
 * 改动无需通知输入法进程：面板每次展开（[GalleryPanelView.onPanelShown]）都会重读目录与布局参数。
 */
class GallerySettingsActivity : ComponentActivity() {

    /**
     * 两行数值标签，在 [onCreate] 里取一次并缓存。
     *
     * 拖动回调里不再 `findViewById`：`setProgress` 会**同步**回调 `onProgressChanged`，
     * 缓存并判空后，取不到也只留一条日志，不会在回调里 NPE。
     */
    private var textColumns: TextView? = null
    private var textCellHeight: TextView? = null

    /**
     * 图库目录绑定：SAF 目录树授权。
     *
     * 用户在此调起系统选择器；持久化授权拿到后写入 [Prefs.galleryTreeUri]，
     * 键盘图库面板直接列该目录（历史页的「转移到图库目录」也写同一棵树）。
     * 拿不到持久授权就不落绑定（旧授权不撤、偏好不变）：本进程临时授权在重启后必然失效。
     * 只拿到读位时仍落绑定并留痕 —— 读图正常，仅「转移」不可用。
     */
    private val dirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            Diagnostics.i(TAG, "目录: 用户取消选择")
            return@registerForActivityResult
        }
        val prefs = Prefs(this)
        val previous = prefs.galleryTreeUri
        // 先还旧授权再写新绑定：旧目录从此不再被本应用读到。同一条目录重绑时不还 ——
        // 还掉等于撤销刚拿到的授权，面板会立刻读不到内容。
        // 读、写两位都要 take：图库面板只读图，而「转移到图库目录」要在同一棵树下 `createDocument`。
        // 只 take 读位时当次进程靠临时授权尚可写，进程重建后写必然失败；provider 只给读位时
        // 降级为只读并留痕，不假装拿到了写权限。
        val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val readWrite = runCatching {
            contentResolver.takePersistableUriPermission(uri, rwFlags)
        }.isSuccess
        val granted = readWrite || runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (granted && !readWrite) {
            Diagnostics.w(TAG, "目录授权只拿到读位，转图库不可用 provider=${uri.authority}")
        }
        // 拿不到持久授权就不能落绑定：新目录只靠临时授权，重启后必然失效，面板读不到且无入口可修。
        // 与导出目录（ClipboardHistoryActivity）同是失败保留原状：那边还弹提示，本页只留日志并重画。
        if (!granted) {
            Diagnostics.w(TAG, "目录: 未拿到持久授权，保留原绑定 provider=${uri.authority}")
            render()
            return@registerForActivityResult
        }
        if (previous.isNotEmpty() && previous != uri.toString()) releaseTreePermission(previous)
        prefs.galleryTreeUri = uri.toString()
        // 不记 uri 本身：SAF 树 URI 含用户目录路径，日志会落盘并随诊断包外带。
        Diagnostics.i(TAG, "目录: 已绑定 persist=$granted provider=${uri.authority}")
        render()
    }

    /**
     * 释放一条目录的持久授权。
     *
     * 换目录 / 清除绑定时要还掉旧授权，否则每换一次就攒下一条不再使用的目录读权限。
     * 释放失败多半是这条授权本就不在（或已被系统回收）：留痕即可，不必打扰用户。
     */
    private fun releaseTreePermission(raw: String) {
        if (raw.isEmpty()) return
        val uri = android.net.Uri.parse(raw)
        // 释放同样按读 + 写两位来：老绑定只有读位，整体释放会抛异常 ⇒ 回落单读位
        val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val released = runCatching {
            contentResolver.releasePersistableUriPermission(uri, rwFlags)
        }.isSuccess || runCatching {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        Diagnostics.i(TAG, "目录: 已释放旧授权 released=$released provider=${uri.authority}")
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    /** 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 起、[onStop] 撤 */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_settings)
        findViewById<TextView>(R.id.text_gallery_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_gallery_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_gallery_close).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }
        findViewById<Button>(R.id.btn_gallery_dir).setOnClickListener { dirLauncher.launch(null) }
        findViewById<Button>(R.id.btn_gallery_dir_clear).setOnClickListener {
            val prefs = Prefs(this)
            // 清绑定要连同读授权一起交出去：只清偏好等于「界面说不读了，系统层面还留着」
            releaseTreePermission(prefs.galleryTreeUri)
            prefs.galleryTreeUri = ""
            Diagnostics.i(TAG, "目录: 已清除绑定")
            render()
        }
        // 「自动返回」：点一张图后收面板、回打字键盘（默认关，见 Prefs.galleryAutoReturn）；
        // 宿主收到图的行为各异，故交给用户按习惯定。
        findViewById<Button>(R.id.btn_gallery_auto_return).setOnClickListener {
            val prefs = Prefs(this)
            prefs.galleryAutoReturn = !prefs.galleryAutoReturn
            Diagnostics.i(TAG, "自动返回: ${prefs.galleryAutoReturn}")
            render()
        }

        // 数值标签先取好：拖动回调里要用，而回调可能在 setProgress 内同步触发
        textColumns = findViewById(R.id.text_gallery_columns)
        textCellHeight = findViewById(R.id.text_gallery_cell_height)
        if (textColumns == null) Diagnostics.w(TAG, "布局里找不到 text_gallery_columns")
        if (textCellHeight == null) Diagnostics.w(TAG, "布局里找不到 text_gallery_cell_height")

        // 两个拖动条与「键盘外观」页同一套做法：上限先设好、监听后挂（初值由 render 写，
        // 避免初始化本身触发一次无意义回调）。只在松手时打日志，避免拖动过程每格都写一条。
        // 每行张数：3..8，默认 5；progress = 值 − 下限，与 Prefs setter 的 coerceIn 同源
        findViewById<SeekBar>(R.id.seek_gallery_columns).apply {
            max = Prefs.GALLERY_COLUMNS_MAX - Prefs.GALLERY_COLUMNS_MIN
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    // 程序化同步（render 的 setProgress）不算用户改动：放它进来会凭空落一条草稿，
                    // 之后被当成用户改动写回。
                    if (!fromUser) return
                    val value = Prefs.GALLERY_COLUMNS_MIN + progress
                    draftColumns = value      // 只记草稿：落盘统一走 commitDrafts
                    textColumns?.text = String.format(Locale.US, TEXT_COLUMNS_VALUE, value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    commitDrafts()
                    Diagnostics.i(TAG, "每行张数: ${prefs.galleryColumns}")
                }
            })
        }
        // 缩略图行高（dp）：40..120，默认 76；映射同上
        findViewById<SeekBar>(R.id.seek_gallery_cell_height).apply {
            max = Prefs.GALLERY_CELL_HEIGHT_MAX - Prefs.GALLERY_CELL_HEIGHT_MIN
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    // 同列数那条：程序化同步不落草稿
                    if (!fromUser) return
                    val value = Prefs.GALLERY_CELL_HEIGHT_MIN + progress
                    draftHeight = value       // 只记草稿：落盘统一走 commitDrafts
                    textCellHeight?.text = String.format(Locale.US, TEXT_HEIGHT_VALUE, value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    commitDrafts()
                    Diagnostics.i(TAG, "缩略图行高: ${prefs.galleryCellHeightDp}")
                }
            })
        }
    }

    /**
     * 每次回前台都按盘上值重画。
     *
     * 渲染不能只留在 [onCreate]：本页在栈里时，别处（键盘面板的布局调节、备份导入）
     * 可能已改过这些值，返回本页会停在旧快照上。
     */
    override fun onStart() {
        super.onStart()
        themeTicker.start()
        render()
    }

    override fun onStop() {
        super.onStop()
        // 拖动中直接退出时草稿还没提交：离开兜底一次
        commitDrafts()
        themeTicker.stop()
    }

    /** 本页共用的偏好对象：拖动回调原先每格都新建一个再提交 */
    private val prefs by lazy { Prefs(this) }

    /** 拖动中的草稿（松手或离开页面时统一落盘并核对，见 [commitDrafts]） */
    private var draftColumns: Int? = null
    private var draftHeight: Int? = null

    /**
     * 把拖动草稿落盘并核对。
     *
     * 原先每经过一格就 apply() 一次：进程被回收时可能回退，写入失败也没有痕迹。
     * 现在只在松手 / 离开页面时写一次，用 flush()（commit）确认结果，失败给提示 ——
     * 与在线翻译设置页「只有真的写过才同步落盘」同一口径。
     */
    private fun commitDrafts() {
        val cols = draftColumns
        val height = draftHeight
        if (cols == null && height == null) return
        draftColumns = null
        draftHeight = null
        cols?.let { prefs.galleryColumns = it }
        height?.let { prefs.galleryCellHeightDp = it }
        if (!prefs.flush()) {
            Diagnostics.w(TAG, "布局参数落盘失败（改动可能回退）")
            Toast.makeText(this, TEXT_LAYOUT_SAVE_FAILED, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 按当前偏好刷新全部文案与状态色。
     *
     * 重画前先提交草稿：页内按钮（自动返回 / 清除绑定、目录选择回调）只调本方法，
     * 而重画读的是盘上值 —— 草稿若还挂着，界面会被画回旧值而草稿随后才落盘，
     * 于是「看到的」与「生效的」相反。先提交再重画 ⇒ 两者同源（见 [commitDrafts]）。
     */
    private fun render() {
        commitDrafts()
        val prefs = Prefs(this)
        val bound = prefs.galleryTreeUri.isNotEmpty()
        findViewById<Button>(R.id.btn_gallery_dir).text =
            if (bound) TEXT_DIR_CHANGE else TEXT_DIR_PICK
        findViewById<Button>(R.id.btn_gallery_dir_clear).apply {
            text = TEXT_DIR_CLEAR
            isEnabled = bound
        }
        // 开关用颜色表态：开 = 提示红（与键盘上「返回」「退出」同一套令牌），关 = 普通前景色
        findViewById<Button>(R.id.btn_gallery_auto_return).apply {
            val on = prefs.galleryAutoReturn
            text = if (on) TEXT_AUTO_ON else TEXT_AUTO_OFF
            setTextColor(getColor(if (on) R.color.kb_key_hint_red else R.color.text_primary))
        }
        // 两行都是「固定标题 + 右侧数值」（与「键盘外观」页同款三件套）
        findViewById<TextView>(R.id.label_gallery_columns).text = TEXT_COLUMNS_TITLE
        findViewById<TextView>(R.id.label_gallery_cell_height).text = TEXT_HEIGHT_TITLE
        textColumns?.text = String.format(Locale.US, TEXT_COLUMNS_VALUE, prefs.galleryColumns)
        textCellHeight?.text = String.format(Locale.US, TEXT_HEIGHT_VALUE, prefs.galleryCellHeightDp)
        bindSeek(R.id.seek_gallery_columns, prefs.galleryColumns, Prefs.GALLERY_COLUMNS_MIN)
        bindSeek(
            R.id.seek_gallery_cell_height,
            prefs.galleryCellHeightDp,
            Prefs.GALLERY_CELL_HEIGHT_MIN,
        )
    }

    /** 把档位值同步到拖动条；值没变就不写（SeekBar 在值相同时也回调，白跑一次落盘） */
    private fun bindSeek(id: Int, value: Int, min: Int) {
        val seek = findViewById<SeekBar>(id) ?: return
        val progress = value - min
        if (seek.progress != progress) seek.progress = progress
    }

    private companion object {
        const val TAG = "GallerySettings"

        const val TEXT_TITLE = "图库快贴功能"
        const val TEXT_DESC = "图库目录:图库会读取这个位置的图片\n清除绑定:不再读取任何目录,可新增目录\n自动返回:开启时,点一张图后直接回到打字键盘"
        const val TEXT_DIR_PICK = "选择图库目录"
        const val TEXT_DIR_CHANGE = "更换图库目录"
        const val TEXT_DIR_CLEAR = "清除图库绑定"
        const val TEXT_AUTO_ON = "自动返回：开"
        const val TEXT_AUTO_OFF = "自动返回：关"
        const val TEXT_COLUMNS_TITLE = "每行张数"
        const val TEXT_HEIGHT_TITLE = "缩略图行高"
        const val TEXT_COLUMNS_VALUE = "%d 张"

        /** 落盘失败提示：改完没生效要说出来，不能静默回退 */
        const val TEXT_LAYOUT_SAVE_FAILED = "布局参数保存失败，请重试"
        const val TEXT_HEIGHT_VALUE = "%d dp"
    }
}
