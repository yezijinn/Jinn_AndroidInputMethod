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
 * 发起输入框的标识经 [GalleryInsert.EXTRA_HOST_KEY] 带入并随桥保存：选图期间前台可能被切换
 * （同一个应用换个会话也是另一个输入框），回来时由 IME 核对，避免把图插进别的输入框。
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
            val hostKey = intent?.getStringExtra(GalleryInsert.EXTRA_HOST_KEY)
            // 复制原图可能几十 MB，放后台；完成后关页回原应用，IME 在下一个输入会话里插入。
            // 走长活池（契约 C-1）：整份拷贝是秒级活，投进交互短活队列会把剪贴板保存与面板首屏
            // 一起推后（用户此刻在选图页感知不到，但回到输入框立刻能感知）。
            // 标记「复制在飞」：重建分支据此决定该不该收尾（BUG.md L-1381）。写在回调所在实例，
            // 读在重建后新实例的 onCreate，故用 @Volatile 置于伴生对象（进程级）。
            copyInFlight = true
            BackgroundIo.runLong {
                val result = GalleryInsert.copyToCache(this@GalleryPickActivity, uri)
                val toast = when (result) {
                    is GalleryInsert.CopyResult.Ok -> {
                        GalleryInsert.putPending(result.file, result.mime, hostKey)
                        Diagnostics.i(TAG, "已备好待插入图片（${result.mime}）")
                        null
                    }
                    GalleryInsert.CopyResult.TooLarge -> {
                        Diagnostics.w(TAG, "选图超过 ${GalleryInsert.MAX_BYTES / 1024 / 1024}MB，未插入")
                        GalleryInsert.tooLargeText()
                    }
                    GalleryInsert.CopyResult.ReadFailed -> {
                        Diagnostics.w(TAG, "选图读取失败，未插入")
                        TEXT_READ_FAILED
                    }
                }
                runOnUiThread {
                    // 复制收尾：先清「在飞」标记，再关页（BUG.md L-1381）
                    copyInFlight = false
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
        } else if (copyInFlight) {
            // 重建分支必须自己收尾（BUG.md L-1062）：复制进行中触发 recreate() 时，新实例既拉不起
            // 选择器也不 finish ⇒ 透明页常驻前台（用户看到「点了图库之后界面卡住」），而收尾的
            // finish() 落在**已销毁**的旧实例上是空操作。图不会丢：putPending 是进程级的，
            // 回到应用后 IME 仍会把它插进原输入框（桥带 60s 有效期）。
            //
            // 但**只在复制在飞时**才收尾（BUG.md L-1381）：重建也可能发生在选择器还没返回的时候
            // （旋转 / 定时换色），此时结果仍会投递给重建后的新实例 —— 无条件 finish() 会把这份
            // 用户刚选的图静默丢掉，比「透明页」严重。没有在飞复制就让重注册的 launcher 照常收尾。
            finish()
        }
    }

    private companion object {
        const val TAG = "GalleryPick"

        /** 读取失败提示 */
        const val TEXT_READ_FAILED = "读取图片失败，未插入"

        /**
         * 复制是否在飞（进程级，BUG.md L-1381）。
         *
         * 写在复制回调所在的（可能即将被销毁的）实例、读在重建后新实例的 `onCreate`，故 `@Volatile`。
         * 重建分支只在它为 true 时才收尾 —— 否则会收掉仍在等待投递的选择结果，把用户刚选的图丢掉。
         */
        @Volatile
        var copyInFlight = false
    }
}
