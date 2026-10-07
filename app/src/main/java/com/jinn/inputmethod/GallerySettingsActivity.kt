package com.jinn.inputmethod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 「图库快贴功能」页：图库相关的设置都收在这一页。
 *
 * 原先三处各管一段 —— 目录绑定与清除在设置页、「自动返回」只藏在键盘面板里，找起来费劲
 * （需求：统一放进一个页面）。现在本页统一：目录绑定 / 清除绑定 / 自动返回 / 缩略图布局。
 *
 * 键盘面板里那份缩略图调节保留（看图时随手调更顺手），两处读写同一组偏好，谁改都立即落盘。
 * 不需要通知输入法进程：键盘面板每次展开（[GalleryPanelView.onPanelShown]）都会重读全部参数。
 */
class GallerySettingsActivity : ComponentActivity() {

    /**
     * 两行的数值标签，在 [onCreate] 里取一次并缓存。
     *
     * ⚠ 拖动条的回调里**不再** `findViewById`：`setProgress` 会**同步**回调 `onProgressChanged`，
     * 一旦那一刻取不到该 id 就直接在回调里 NPE（真机实测崩在 `SeekBar.onProgressRefresh` 里，
     * 栈上只有混淆后的匿名类，完全看不出是哪个 id）。缓存 + 判空后，取不到也只留一条日志。
     */
    private var textColumns: TextView? = null
    private var textCellHeight: TextView? = null

    /**
     * 图库目录：SAF 目录树授权。
     *
     * 用户只在这里跳一次系统选择器；持久化授权拿到后，键盘内的图库面板直接列这个目录。
     * 拿授权失败也先把 URI 记下 —— 可用性由面板侧的一次真实读取判定，不可用即提示重绑。
     */
    private val dirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            Diagnostics.i(TAG, "目录: 用户取消选择")
            return@registerForActivityResult
        }
        val granted = runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        Prefs(this).galleryTreeUri = uri.toString()
        // 不记 uri 本身：SAF 树 URI 含用户目录路径，i 级日志会落盘并随诊断包外带（L-1036）
        Diagnostics.i(TAG, "目录: 已绑定 persist=$granted provider=${uri.authority}")
        render()
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
            Prefs(this).galleryTreeUri = ""
            Diagnostics.i(TAG, "目录: 已清除绑定")
            render()
        }
        findViewById<Button>(R.id.btn_gallery_auto_return).setOnClickListener {
            val prefs = Prefs(this)
            prefs.galleryAutoReturn = !prefs.galleryAutoReturn
            Diagnostics.i(TAG, "自动返回: ${prefs.galleryAutoReturn}")
            render()
        }

        // 数值标签先取好：拖动条回调里要用，而回调可能在任何一次 setProgress 内同步触发
        textColumns = findViewById(R.id.text_gallery_columns)
        textCellHeight = findViewById(R.id.text_gallery_cell_height)
        if (textColumns == null) Diagnostics.w(TAG, "布局里找不到 text_gallery_columns")
        if (textCellHeight == null) Diagnostics.w(TAG, "布局里找不到 text_gallery_cell_height")

        // 两个拖动条：与「键盘外观」页同一套做法 —— 上限先设好、监听后挂（初值由 render 写，
        // 避免初始化本身触发一次无意义回调）。只在松手时打日志：拖动过程每格都写日志会变成
        // 每秒几十次文件 IO。
        findViewById<SeekBar>(R.id.seek_gallery_columns).apply {
            max = Prefs.GALLERY_COLUMNS_MAX - Prefs.GALLERY_COLUMNS_MIN
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = Prefs.GALLERY_COLUMNS_MIN + progress
                    Prefs(this@GallerySettingsActivity).galleryColumns = value
                    textColumns?.text = TEXT_COLUMNS_VALUE.format(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    Diagnostics.i(
                        TAG,
                        "每行张数: ${Prefs(this@GallerySettingsActivity).galleryColumns}",
                    )
                }
            })
        }
        findViewById<SeekBar>(R.id.seek_gallery_cell_height).apply {
            max = Prefs.GALLERY_CELL_HEIGHT_MAX - Prefs.GALLERY_CELL_HEIGHT_MIN
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = Prefs.GALLERY_CELL_HEIGHT_MIN + progress
                    Prefs(this@GallerySettingsActivity).galleryCellHeightDp = value
                    textCellHeight?.text = TEXT_HEIGHT_VALUE.format(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    Diagnostics.i(
                        TAG,
                        "缩略图行高: ${Prefs(this@GallerySettingsActivity).galleryCellHeightDp}",
                    )
                }
            })
        }
    }

    /**
     * 每次回前台都按盘上值重画。
     *
     * 渲染不能只留在 `onCreate`：本页在栈里时，别处（键盘面板里的「布局」调节、备份导入）
     * 可能已经改过这些值，返回本页会停在旧快照上。
     */
    override fun onStart() {
        super.onStart()
        themeTicker.start()
        render()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /** 按当前偏好刷新全部文案与状态色 */
    private fun render() {
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
        textColumns?.text = TEXT_COLUMNS_VALUE.format(prefs.galleryColumns)
        textCellHeight?.text = TEXT_HEIGHT_VALUE.format(prefs.galleryCellHeightDp)
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
        const val TEXT_HEIGHT_VALUE = "%d dp"
    }
}
