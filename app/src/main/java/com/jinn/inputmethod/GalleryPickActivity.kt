package com.jinn.inputmethod

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 图库快贴的选图中转页（无界面，选完即退）。
 *
 * 存在的理由：系统 Photo Picker / SAF 只能由 Activity 启动，而输入法（Service）
 * 拿不到 Activity 结果回调。于是本页负责「选图 → 复制进私有 cache → 交给
 * [GalleryInsert] 桥」，IME 回到前台（`onStartInputView`）后再落 `commitContent`。
 *
 * 发起宿主的包名经 [GalleryInsert.EXTRA_HOST] 带入并随桥保存：选图期间前台可能被切换，
 * 回来时由 IME 核对，避免把图插进别的输入框。
 *
 * 零存储权限：`PickVisualMedia` 在 Android 13+ 走系统相册选择器（Photo Picker），
 * 低版本自动退回 SAF，全程不需要任何存储权限，与项目「不申请存储权限」的约定一致。
 */
class GalleryPickActivity : ComponentActivity() {

    /** 无界面页也跟随明暗主题：两条「每个设置页…」守卫覆盖全部 `*Activity.kt`（见 ThemeColorParityTest） */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    /** 定时换色定时器（同款接入要求）：本页驻留极短，接上是为了与全体页面保持一致 */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    private val picker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                finish() // 用户取消
                return@registerForActivityResult
            }
            val host = intent?.getStringExtra(GalleryInsert.EXTRA_HOST)
            // 复制原图可能几十 MB，放后台；完成后关页回原应用，IME 在下一个输入会话里插入
            BackgroundIo.run {
                val result = GalleryInsert.copyToCache(this@GalleryPickActivity, uri)
                val toast = when (result) {
                    is GalleryInsert.CopyResult.Ok -> {
                        GalleryInsert.putPending(result.file, result.mime, host)
                        Diagnostics.i(TAG, "已备好待插入图片（${result.mime}）")
                        null
                    }
                    GalleryInsert.CopyResult.TooLarge -> {
                        Diagnostics.w(TAG, "选图超过 ${GalleryInsert.MAX_BYTES / 1024 / 1024}MB，未插入")
                        TEXT_TOO_LARGE
                    }
                    GalleryInsert.CopyResult.ReadFailed -> {
                        Diagnostics.w(TAG, "选图读取失败，未插入")
                        TEXT_READ_FAILED
                    }
                }
                runOnUiThread {
                    // 失败必须说话：此前只落日志，用户看到的只有「选择器关了、什么都没发生」
                    toast?.let { Toast.makeText(this@GalleryPickActivity, it, Toast.LENGTH_SHORT).show() }
                    finish()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只在首次创建时拉起选择器（旋转/重建不重复拉）
        if (savedInstanceState == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private companion object {
        const val TAG = "GalleryPick"

        /** 超限提示（阈值与 `GalleryInsert.MAX_BYTES` 同步） */
        const val TEXT_TOO_LARGE = "图片超过 20MB，未插入"

        /** 读取失败提示 */
        const val TEXT_READ_FAILED = "读取图片失败，未插入"
    }
}
