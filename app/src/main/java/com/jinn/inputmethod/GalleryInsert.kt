package com.jinn.inputmethod

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
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

    /** 选图页读取发起输入框标识的 Intent extra（值是 [galleryFieldKeyOf] 的结果） */
    const val EXTRA_HOST_KEY = "com.jinn.inputmethod.extra.GALLERY_HOST_KEY"

    /** 存放待插入图片的 cache 子目录（须与 `res/xml/share_file_paths.xml` 的 cache-path 一致，守卫对拍） */
    internal const val DIR_NAME = "gallery_share"

    /** 单张上限：超过直接放弃（避免把几百 MB 的原图复制进 cache） */
    const val MAX_BYTES = 20L * 1024 * 1024

    /** cache 总字节上限：与 [KEEP_FILES] 共同约束驻留量，单张上限再大也不会无界堆积 */
    private const val MAX_CACHE_BYTES = 60L * 1024 * 1024

    /** 桥的有效期：选完图到回到原输入框之间可能隔一会儿，超时丢弃（同 `pendingPasteText` 思路） */
    private const val TTL_MS = 60_000L

    /**
     * 清理时无条件保留的最新张数。
     *
     * 取 2 而不是 1：`commitContent` 交给宿主的是文件 URI，宿主读取是异步的；用户连选多张时
     * 「正在被读的」往往是最新那张与上一轮插入的那张（L-1003）。
     */
    private const val PROTECTED_RECENT = 2

    /**
     * 保留张数上限：由总字节上限与单张上限反推（60MB ÷ 20MB = 3），再加受保护的最近两张。
     *
     * 不写死魔数：三者在单张上限下互斥，写死会让后来调参的人以为「放宽张数就能多留」（L-1007）。
     */
    private val KEEP_FILES = (MAX_CACHE_BYTES / MAX_BYTES).toInt() + PROTECTED_RECENT

    private const val TAG = "GalleryInsert"

    /** 选图复制的三种结果：失败要能区分原因，界面才好给准确提示 */
    internal sealed interface CopyResult {
        class Ok(val file: File, val mime: String) : CopyResult
        object TooLarge : CopyResult
        object ReadFailed : CopyResult
    }

    /**
     * 本次提交的结果。
     *
     * `Submitted` 只代表提交调用返回 true —— 「宿主是否真的收下」仍以宿主表现为准（见类注释），
     * 调用方不可把它当成成功承诺；`FileMissing` / `NoConnection` 是本地可判定的失败，
     * 调用方据此给用户可见反馈（L-1006）。
     */
    internal enum class InsertResult { Submitted, Rejected, NoConnection, FileMissing }

    /**
     * 待插入的一张图。
     *
     * [hostKey] 是发起选图时的**输入框**标识（见 [galleryFieldKeyOf]），取不到时为 null、比对时放行。
     */
    internal class Picked(val file: File, val mime: String, val hostKey: String?)

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

    /**
     * **图库专用**的输入框标识：包名 + `fieldId` + 输入类型 + `imeOptions`，全部取自宿主给的静态属性。
     *
     * 为什么不与粘贴暂存（`fieldKeyOf`，只有包名 + fieldId）共用：`EditorInfo.fieldId` 由宿主自定，
     * 自绘输入框（微信聊天框等）常恒为同一个值 ⇒ 单靠包名 + fieldId 区分不出同一应用的不同会话，
     * 图片会被插进用户刚切过去的那个（L-1005）。图库的误插代价（图片进了别的会话，落在收到即发的
     * 宿主上等于发出去）高于文本粘贴，故这里用更宽的判据。
     *
     * `hintText` **不参与**（L-1008）：它随输入状态变 —— 点「图库」时输入框已聚焦，placeholder 多半
     * 已被宿主收起或换过文案，选完图回来是重新聚焦的一刻，两次采样对不上就会把同一个输入框判成
     * 「换了框」，合法选图被丢弃。签名两侧一个在上面取、一个在 `flushPendingGalleryImage` 里取，
     * 只能比稳定量。
     */
    internal fun galleryFieldKeyOf(info: EditorInfo?): String? {
        val i = info ?: return null
        val pkg = i.packageName ?: return null
        if (pkg.isEmpty()) return null
        return "$pkg#${i.fieldId}#${i.inputType}#${i.imeOptions}"
    }

    /** 选图页复制完成后调用（覆盖式：只保留最后一次选择） */
    fun putPending(file: File, mime: String, hostKey: String?) {
        pending = Pending(Picked(file, mime, hostKey), System.currentTimeMillis())
        trimCache(file.parentFile)
    }

    /**
     * 取桥的结果（三态）。
     *
     * 别把「没有待插入」与「有但过期」合并成一个 null：过期丢弃是**本地可判定**的失败，
     * 调用方要能区分并留痕（L-1014）。
     */
    internal sealed interface TakeResult {
        /** 桥里没有东西（用户没选图，或已被上一次取走） */
        object None : TakeResult

        /** 有待插入的图片且未过期 */
        class Ready(val picked: Picked) : TakeResult

        /** 有图片但已超过有效期，已丢弃 */
        object Expired : TakeResult
    }

    /** IME 回到前台时取（一次性：无论哪种结果都把桥清空） */
    fun takePending(): TakeResult {
        val p = pending ?: return TakeResult.None
        pending = null
        return if (System.currentTimeMillis() - p.atMs <= TTL_MS) TakeResult.Ready(p.picked) else TakeResult.Expired
    }

    /**
     * 执行插入。返回值见 [InsertResult] —— 它是**本地**判定（文件还在不在、有没有连接、调用是否被接受），
     * 不代表宿主真的收下了（见类注释）。
     */
    fun commit(ime: JinnIme, file: File, mime: String): InsertResult {
        // 文件可能已被系统清 cache 或清理逻辑删掉：URI 仍能构造、commitContent 也可能返回 true，
        // 宿主却读到空内容且没有提示 —— 显式拦下并让调用方说话（L-1004 / L-1006）
        if (!file.exists() || file.length() == 0L) {
            Diagnostics.w(TAG, "待插入图片已不存在（${file.name}），跳过提交")
            return InsertResult.FileMissing
        }
        val ic: InputConnection = ime.currentInputConnection ?: return InsertResult.NoConnection
        val uri = runCatching {
            FileProvider.getUriForFile(ime, "${ime.packageName}.share", file)
        }.getOrNull() ?: return InsertResult.Rejected
        val item = InputContentInfo(uri, ClipDescription("jinn-gallery", arrayOf(mime)), null)
        val flags = InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        val submitted = runCatching { ic.commitContent(item, flags, null) }.getOrDefault(false)
        return if (submitted) InsertResult.Submitted else InsertResult.Rejected
    }

    /**
     * 把选中的图片复制进私有 cache。在后台线程调用（原图可能几十 MB）。
     *
     * 类型优先取宿主声明的（`ContentResolver.getType`），取不到时按文件头嗅探；**不回退通配类型**
     * —— `ClipDescription` 要求具体类型，通配属未定义用法（L-1009）。文件名带嗅探出的扩展名，
     * 供按后缀判类型的宿主使用。
     */
    fun copyToCache(context: Context, uri: Uri): CopyResult {
        val resolver = context.contentResolver
        val declared = resolver.getType(uri)?.takeIf { it.startsWith("image/") }
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val temp = File(dir, "pick_$stamp.tmp")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            temp.delete()
                            return CopyResult.TooLarge
                        }
                        output.write(buf, 0, n)
                    }
                }
            } ?: return CopyResult.ReadFailed
            if (temp.length() == 0L) {
                temp.delete()
                return CopyResult.ReadFailed
            }
            val mime = declared ?: sniffImageMime(readHead(temp))
            val named = File(dir, "pick_$stamp.${extOf(mime)}")
            // 改名失败（少见）就沿用 .tmp：类型已定，扩展名只影响按后缀判类型的宿主
            CopyResult.Ok(if (temp.renameTo(named)) named else temp, mime)
        } catch (t: Throwable) {
            temp.delete()
            Diagnostics.w(TAG, "选图复制失败: ${t.javaClass.simpleName}:${t.message}")
            CopyResult.ReadFailed
        }
    }

    /** 读文件头供 [sniffImageMime] 用（读不到给空数组，嗅探自然走兜底） */
    private fun readHead(file: File): ByteArray = runCatching {
        val head = ByteArray(12)
        var filled = 0
        java.io.FileInputStream(file).use { input ->
            // 单次 read 允许短读，循环读到 12 字节或 EOF（L-1012）
            while (filled < head.size) {
                val n = input.read(head, filled, head.size - filled)
                if (n <= 0) break
                filled += n
            }
        }
        if (filled == 0) ByteArray(0) else head.copyOf(filled)
    }.getOrDefault(ByteArray(0))

    /**
     * 按文件头判图片类型（纯函数，供守卫枚举格式）。
     *
     * 认 JPEG / PNG / GIF / WebP / HEIF 家族 / AVIF 六种形态，都不是时兜底 `image/png` ——
     * 兜底必须是**具体**类型：通配属性在 `ClipDescription` 里是无定义行为，严格解析的宿主会直接认不出。
     * ISO-BMFF 的 brand 决定 HEIF 家族还是 AVIF，只判偏移 4 的 `ftyp` 会把 AVIF 当成 HEIC（L-1011）；
     * 两者都不是（MP4 的 `isom` 等）同样走兜底 —— 入口是 `PickVisualMedia.ImageOnly`，正常拿不到视频。
     */
    internal fun sniffImageMime(head: ByteArray): String {
        fun at(i: Int): Int = if (i < head.size) head[i].toInt() and 0xFF else -1
        fun tag4(from: Int): String = buildString {
            for (k in from until from + 4) {
                val b = at(k)
                if (b < 0) return ""
                append(b.toChar())
            }
        }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            tag4(4) == "ftyp" -> when (val brand = tag4(8)) {
                "avif", "avis" -> "image/avif"
                "mif1", "msf1" -> "image/heic"
                // 其余 HEIF 家族 brand（heic / heix / hevc / hevx / heim / heis …）都以 he 起头
                else -> if (brand.startsWith("he")) "image/heic" else "image/png"
            }
            else -> "image/png"
        }
    }

    /**
     * 与 [sniffImageMime] 结果对应的扩展名。
     *
     * 未列出的类型（`image/bmp`、`image/tiff` 等）取 MIME 子类型当扩展名，滤掉非字母数字 ——
     * 固定给 `png` 会让后缀与内容不符（L-1011）。
     */
    private fun extOf(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/avif" -> "avif"
        else -> mime.substringAfter('/', "").filter { it.isLetterOrDigit() }.ifEmpty { "png" }
    }

    /**
     * 应清理的文件（纯函数，供守卫枚举场景）。
     *
     * 入参按**最近使用在前**排序。最新 [protectRecent] 张无条件保留 —— 宿主读取是异步的，
     * 刚提交与上一轮提交的都可能仍在被重读，删掉在界面上表现为「发出去的图裂了」（L-1003）；
     * 其余按「保留 [keepFiles] 张 + 保留集总字节不超过 [maxBytes]」淘汰，超出即删最旧。
     */
    internal fun staleFilesForTrim(
        entries: List<Pair<File, Long>>,
        keepFiles: Int,
        maxBytes: Long,
        protectRecent: Int = PROTECTED_RECENT,
    ): List<File> {
        val out = ArrayList<File>()
        var keptBytes = 0L
        entries.forEachIndexed { idx, (file, len) ->
            if (idx < protectRecent) {
                keptBytes += len
                return@forEachIndexed
            }
            val tooMany = idx >= keepFiles
            val tooBig = keptBytes + len > maxBytes
            if (tooMany || tooBig) out.add(file) else keptBytes += len
        }
        return out
    }

    /** 目录内按「保护最近若干张 + 保留张数 + 总字节」清理（判据见 [staleFilesForTrim]） */
    private fun trimCache(dir: File?) {
        val files = dir?.listFiles() ?: return
        val entries = files.sortedByDescending { it.lastModified() }.map { it to it.length() }
        staleFilesForTrim(entries, KEEP_FILES, MAX_CACHE_BYTES).forEach { it.delete() }
    }
}
