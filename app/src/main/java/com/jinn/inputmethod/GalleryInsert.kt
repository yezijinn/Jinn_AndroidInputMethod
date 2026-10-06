package com.jinn.inputmethod

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import java.io.File

/**
 * 图库快贴：把选中的图片经 `commitContent` 插入当前宿主输入框。
 *
 * 职责：托管「选图 → 复制进私有 cache → 待插入」的桥，以及在 IME 有 `InputConnection` 时执行插入。
 * 入口显隐由 `JinnIme` 按宿主声明决定（见 [canHostAccept]）。
 *
 * 真机实测约束（2026-10-07，微信 / Telegram / 豆包 / Grok 验证通过）：
 * - 图片**必须先复制进应用私有目录**：Photo Picker 的 URI 只能授权给本应用，
 *   转授不了宿主（授权方是 MediaProvider），只有自家 FileProvider 的 URI 才能 grant 给宿主；
 * - `commitContent` **必须**带 [InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION]：
 *   缺了它微信会「假成功」—— 返回 true 却把 `content://` 当纯文本贴进输入框（实测踩到过）；
 * - **返回值不可信**：闲鱼、DeepSeek 返回 true 却毫无效果，因此入口只按声明显隐，
 *   返回值仅记日志，不作判据。
 */
internal object GalleryInsert {

    /** 选图页读取发起宿主的 Intent extra：选图期间前台可能被切换，回来时由 IME 核对 */
    const val EXTRA_HOST = "com.jinn.inputmethod.extra.GALLERY_HOST"

    /** 存放待插入图片的 cache 子目录（与 `res/xml/share_file_paths.xml` 的 cache-path 对应） */
    private const val DIR_NAME = "gallery_share"

    /** 单张上限：超过直接放弃（避免把几百 MB 的原图复制进 cache） */
    const val MAX_BYTES = 20L * 1024 * 1024

    /** cache 总字节上限：与 [KEEP_FILES] 共同约束驻留量，单张上限再大也不会无界堆积 */
    private const val MAX_CACHE_BYTES = 60L * 1024 * 1024

    /** 桥的有效期：选完图到回到原输入框之间可能隔一会儿，超时丢弃（同 `pendingPasteText` 思路） */
    private const val TTL_MS = 60_000L

    /** cache 里最多保留几张，超出清理最旧（防选图反复使用后堆积） */
    private const val KEEP_FILES = 5

    private const val TAG = "GalleryInsert"

    /** 选图复制的三种结果：失败要能区分原因，界面才好给准确提示 */
    internal sealed interface CopyResult {
        class Ok(val file: File, val mime: String) : CopyResult
        object TooLarge : CopyResult
        object ReadFailed : CopyResult
    }

    /** 待插入的一张图：文件、类型、发起选图时的宿主包名（null = 当时取不到，核对时放行） */
    internal class Picked(val file: File, val mime: String, val host: String?)

    private class Pending(val picked: Picked, val atMs: Long)

    @Volatile
    private var pending: Pending? = null

    /**
     * 宿主输入框是否声明可接收图片。
     *
     * 入口显隐的**唯一判据**（实测：声明空的宿主必定不可用；声明了也不一定可用，
     * 但没有声明就绝无可能，故按声明过滤即可避免把入口露给永不可用的宿主）。
     */
    fun canHostAccept(mimeTypes: Array<String>?): Boolean =
        mimeTypes?.any { it == "*/*" || it.startsWith("image/") } == true

    /** 选图页复制完成后调用（覆盖式：只保留最后一次选择） */
    fun putPending(file: File, mime: String, host: String?) {
        pending = Pending(Picked(file, mime, host), System.currentTimeMillis())
        trimCache(file.parentFile)
    }

    /** IME 回到前台时取（一次性；超时或已被取走返回 null） */
    fun takePending(): Picked? {
        val p = pending ?: return null
        pending = null
        return if (System.currentTimeMillis() - p.atMs <= TTL_MS) p.picked else null
    }

    /**
     * 执行插入。`false` 只代表本次没有提交成功（无连接 / Provider 出错 / 宿主返回 false）；
     * 「宿主是否真的收下了」以宿主表现为准，方法返回值不作判据（见类注释）。
     */
    fun commit(ime: JinnIme, file: File, mime: String): Boolean {
        val ic: InputConnection = ime.currentInputConnection ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(ime, "${ime.packageName}.share", file)
        }.getOrNull() ?: return false
        val item = InputContentInfo(uri, ClipDescription("jinn-gallery", arrayOf(mime)), null)
        val flags = InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        return runCatching { ic.commitContent(item, flags, null) }.getOrDefault(false)
    }

    /**
     * 把选中的图片复制进私有 cache。在后台线程调用（原图可能几十 MB）；
     * mime 取真实类型（如 image/jpeg），拿不到时退回通配图片类型（宿主声明里两者通常都在）。
     */
    fun copyToCache(context: Context, uri: Uri): CopyResult {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/*"
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val out = File(dir, "pick_${System.currentTimeMillis()}.img")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            out.delete()
                            return CopyResult.TooLarge
                        }
                        output.write(buf, 0, n)
                    }
                }
            } ?: return CopyResult.ReadFailed
            if (out.length() > 0) CopyResult.Ok(out, mime) else CopyResult.ReadFailed
        } catch (t: Throwable) {
            out.delete()
            Diagnostics.w(TAG, "选图复制失败: ${t.javaClass.simpleName}:${t.message}")
            CopyResult.ReadFailed
        }
    }

    /**
     * 应清理的文件（纯函数，供守卫枚举场景）。
     *
     * 入参按**最近使用在前**排序；保留最近 [keepFiles] 张、且保留集总字节不超过 [maxBytes]。
     * 最新的那一张**永不删**：`commitContent` 交给宿主的是文件 URI，宿主读取是异步的
     * （上传前可能重读），删掉会在界面上表现为「发出去的图裂了」。
     */
    internal fun staleFilesForTrim(
        entries: List<Pair<File, Long>>,
        keepFiles: Int,
        maxBytes: Long,
    ): List<File> {
        val out = ArrayList<File>()
        var keptBytes = 0L
        entries.forEachIndexed { idx, (file, len) ->
            if (idx == 0) {
                keptBytes += len
                return@forEachIndexed
            }
            val tooMany = idx >= keepFiles
            val tooBig = keptBytes + len > maxBytes
            if (tooMany || tooBig) out.add(file) else keptBytes += len
        }
        return out
    }

    /** 目录内按「保留张数 + 总字节」清理（判据见 [staleFilesForTrim]） */
    private fun trimCache(dir: File?) {
        val files = dir?.listFiles() ?: return
        val entries = files.sortedByDescending { it.lastModified() }.map { it to it.length() }
        staleFilesForTrim(entries, KEEP_FILES, MAX_CACHE_BYTES).forEach { it.delete() }
    }
}
