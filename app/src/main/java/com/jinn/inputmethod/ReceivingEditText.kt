package com.jinn.inputmethod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/**
 * 能接收图片的编辑框（宿主侧）：向输入法声明「我可以收图」（键盘的「图库」键因此出现），
 * 并把收到的图以缩略图插进自己（插在末尾）。设置页与键盘外观页的「点这里唤起键盘」
 * 测试框都用它。
 *
 * 为什么需要这个子类：原生 `EditText.onCreateInputConnection` **不包装连接**，
 * 声明过的类型传不出 `EditorInfo.contentMimeTypes` —— 输入法看不到这个框能收图；
 * 即使入口出现了，交付过来的内容也没有地方接。只用 `ViewCompat.setOnReceiveContentListener`
 * 是 API ≥ 31 的路径（系统自己会填类型、自己会回调），而本模块 minSdk 26：
 * API 29 真机上该字段恒为空、且无任何报错（实测确认）。
 *
 * 所以这里走全版本通用的那条：声明 [EditorInfoCompat.setContentMimeTypes]，再用
 * [InputConnectionCompat.createWrapper] 交出包装后的连接，由 `commitContent` 把图交付进来。
 * 不换成 `AppCompatEditText` 是因为那要引整套 androidx.appcompat，而本模块只依赖 core。
 */
internal class ReceivingEditText(context: Context, attrs: AttributeSet?) : EditText(context, attrs) {

    /** 解码结果回主线程的送信人：与视图附着状态无关，见 [insertImage] */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** `commitContent` 的接收入口：返回 true 表示已消费 */
    private val receiver = InputConnectionCompat.OnCommitContentListener { info, _, _ ->
        val uri = info?.contentUri
        if (uri != null) {
            insertImage(uri)
            true
        } else {
            Diagnostics.w(TAG, "收到内容但取不到图片 uri")
            false
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        EditorInfoCompat.setContentMimeTypes(outAttrs, arrayOf(MIME_IMAGE))
        return InputConnectionCompat.createWrapper(base, outAttrs, receiver)
    }

    /**
     * 把图插进框里（`ImageSpan`，插在末尾）。
     *
     * 解码前先按目标高度算采样率：相册原图动辄 4000px，直接解码会 OOM。
     * 图源是应用自己的 FileProvider（图库快贴先把图复制进 cache 再提交），读它不需要额外授权。
     *
     * 解码放后台（L-1031）：这里是 `commitContent` 的回调，本来就在主线程上，而开流加解码
     * 要几十毫秒 —— 留在主线程会让贴图那一下明显顿住。
     */
    private fun insertImage(uri: Uri) {
        val target = (TARGET_HEIGHT_DP * resources.displayMetrics.density).toInt()
        BackgroundIo.run {
            val bmp = runCatching { decodeSampledBitmap(uri, target) }.getOrNull()
            // 回主线程用**自己的** Handler，不用 `View.post`：解码这几十毫秒里视图可能已经
            // 脱离窗口（换肤 / 收起键盘重建视图），`View.post` 会把任务压进视图私有的 attach
            // 队列、等下一次 attach —— 而这一份实例不会再 attach，贴图与它那几条日志一起
            // 石沉大海，用户看到的是「选了图没反应」。
            mainHandler.post { attachDecoded(uri, bmp) }
        }
    }

    /** 解码结果回主线程再贴上去（View 只能在主线程碰） */
    private fun attachDecoded(uri: Uri, bmp: Bitmap?) {
        if (!isAttachedToWindow) {
            // 视图已不在窗口上（页面被销毁 / 重建）：这张图无处可贴，但要留一条记录，
            // 否则排查「选了图没反应」时看不到任何痕迹
            Diagnostics.w(TAG, "图片未插入：视图已脱离窗口 provider=${uri.authority}")
            return
        }
        if (bmp == null) {
            // 只记 provider：完整 content URI 的路径段可能是用户文件名，
            // 而 w 级会 sanitizeForFile 后落盘、随「导出诊断数据」外带（L-1036 的同族漏面）
            Diagnostics.w(TAG, "图片解码失败 provider=${uri.authority}")
            return
        }
        val drawable = BitmapDrawable(resources, bmp).apply { setBounds(0, 0, bmp.width, bmp.height) }
        // 对象替换符（\uFFFC）：EditText 靠它给 ImageSpan 占位。
        //
        // 用**区间追加**而不是整段重建（BUG.md L-1099）：`setText` 会替换掉整份文本，顺带清掉
        // 输入法的组合态 —— 用户正在拼音未上屏时贴图，组合串会丢、而 IME 仍按旧光标偏移处理后续提交。
        // 追加只动尾部这一段，连接状态原样保留；追加前先让组合区收尾，免得图被算进组合串里。
        val editable = getText() as? Editable
        if (editable != null) {
            BaseInputConnection.removeComposingSpans(editable)
            val start = editable.length
            editable.append(OBJECT_REPLACEMENT)
            editable.setSpan(
                ImageSpan(drawable), start, start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            editable.append(" ")
            setSelection(editable.length)
        } else {
            // 保底：宿主塞进来的若不是 Editable，仍走整段重建（此时也无组合态可保）
            val sb = SpannableStringBuilder(getText()).append(OBJECT_REPLACEMENT)
            sb.setSpan(
                ImageSpan(drawable), sb.length - 1, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.append(" ")
            setText(sb)
            setSelection(sb.length)
        }
        Diagnostics.i(TAG, "已插入图片 ${bmp.width}x${bmp.height}")
    }

    /** 采样解码到长边不超过约 [targetPx]（两段式，与图库面板同一套做法） */
    private fun decodeSampledBitmap(uri: Uri, targetPx: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > targetPx * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private companion object {
        const val TAG = "ReceivingEditText"

        /** 声明可收的类型：与键盘侧 `GalleryInsert.canHostAccept` 的判据一致 */
        const val MIME_IMAGE = "image/*"

        /** 缩略图目标高度（dp） */
        const val TARGET_HEIGHT_DP = 48

        /** 对象替换符：`EditText` 靠它给 `ImageSpan` 占位 */
        const val OBJECT_REPLACEMENT = "\uFFFC"
    }
}
