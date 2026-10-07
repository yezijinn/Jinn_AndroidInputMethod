package com.jinn.inputmethod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
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
        Diagnostics.i(TAG, "目录: 已绑定 persist=$granted uri=$uri")
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
        findViewById<Button>(R.id.btn_gallery_columns_minus).apply {
            text = TEXT_MINUS
            setOnClickListener { stepColumns(-1) }
        }
        findViewById<Button>(R.id.btn_gallery_columns_plus).apply {
            text = TEXT_PLUS
            setOnClickListener { stepColumns(+1) }
        }
        findViewById<Button>(R.id.btn_gallery_height_minus).apply {
            text = TEXT_MINUS
            setOnClickListener { stepHeight(-HEIGHT_STEP_DP) }
        }
        findViewById<Button>(R.id.btn_gallery_height_plus).apply {
            text = TEXT_PLUS
            setOnClickListener { stepHeight(+HEIGHT_STEP_DP) }
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
        findViewById<TextView>(R.id.label_gallery_columns).text =
            TEXT_COLUMNS.format(prefs.galleryColumns)
        findViewById<TextView>(R.id.label_gallery_cell_height).text =
            TEXT_HEIGHT.format(prefs.galleryCellHeightDp)
    }

    /** 每行张数 ±1（[Prefs.galleryColumns] 的 setter 会归一），改完立即重画 */
    private fun stepColumns(delta: Int) {
        val prefs = Prefs(this)
        prefs.galleryColumns = prefs.galleryColumns + delta
        render()
    }

    /** 行高 ±[HEIGHT_STEP_DP] dp（同上） */
    private fun stepHeight(delta: Int) {
        val prefs = Prefs(this)
        prefs.galleryCellHeightDp = prefs.galleryCellHeightDp + delta
        render()
    }

    private companion object {
        const val TAG = "GallerySettings"

        /** 行高步长：与键盘面板那个「布局」调节保持同一个值（面板侧见 GalleryPanelView 的 HEIGHT_STEP_DP） */
        const val HEIGHT_STEP_DP = 4

        const val TEXT_TITLE = "图库快贴功能"
        const val TEXT_DESC = "目录绑定、自动返回、缩略图布局都在这里。自动返回开着时，键盘里点一张图就直接回到打字键盘。"
        const val TEXT_DIR_PICK = "选择图库目录"
        const val TEXT_DIR_CHANGE = "更换图库目录"
        const val TEXT_DIR_CLEAR = "清除图库绑定"
        const val TEXT_AUTO_ON = "自动返回：开"
        const val TEXT_AUTO_OFF = "自动返回：关"
        const val TEXT_COLUMNS = "每行张数：%d"
        const val TEXT_HEIGHT = "缩略图行高：%d dp"
        const val TEXT_MINUS = "−"
        const val TEXT_PLUS = "+"
    }
}
