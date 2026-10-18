package com.jinn.inputmethod

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 剪贴板图片的三个**出库**动作：保存到相册 / 转移到图库绑定目录 / 复制到系统剪贴板。
 *
 * 与 [ClipboardImageFiles]（入库与文件生命周期）分工：这里只做「把已解密的原字节交出去」，
 * 不持有目录布局知识（文件名委托 [ClipboardImageFiles.stem] 与 [GalleryInsert.extOf]）。
 *
 * 权限口径（minSdk 26）：
 *  - 单张「保存到相册」走 `MediaStore` + `RELATIVE_PATH`，**API 29+ 免权限**；26–28 需
 *    `WRITE_EXTERNAL_STORAGE`，本应用不申请 ⇒ [albumAvailable] 为 false 时界面不提供「保存」；
 *  - 一切**写用户目录**的动作都走 SAF 目录树（「转移到图库目录」用 `Prefs.galleryTreeUri`，
 *    「导出全部图片」用 `Prefs.clipExportTreeUri`），两者共用 [writeToTree]，全 API 级别可用；
 *    未绑定 / 授权失效归 [MoveResult.NeedBinding] / [MoveResult.Denied]，**绝不删历史**
 *    （只有写成功才删，见调用方）。
 *
 * 线程：全部是文件 / provider IO，调用方必须放后台（`BackgroundIo.runLong`）。
 * 日志：只记结果与异常类名，不记路径与 URI（L-1036 口径）。
 */
internal object ClipboardImageExport {

    /** 转移到图库目录的结果：三种都要能让用户看懂「为什么没成功」 */
    sealed interface MoveResult {
        object Ok : MoveResult

        /** 未绑定图库目录（或授权已被系统回收） —— 提示去设置里绑定 */
        object NeedBinding : MoveResult

        /**
         * 有读授权但没有**写**授权（BUG.md L-1322）：目录是在「只 take 读位」的年代绑的
         * （或 provider 只给读位）。与 [Failed] 分开 —— 用户要的动作是重绑目录，不是「重试」。
         */
        object Denied : MoveResult

        object Failed : MoveResult
    }

    /** 相册写入是否可用（API 29+ 免权限；低版本不提供该功能） */
    fun albumAvailable(): Boolean = Build.VERSION.SDK_INT >= 29

    /** 读一条图片的原字节（解密）；失败返回 null（密钥失效 / 文件缺失 / 数据损坏） */
    fun readOriginal(context: Context, item: ClipboardDb.Item): ByteArray? {
        val hash = item.contentHash
        if (hash.isEmpty()) return null
        val enc = ClipboardImageFiles.readBytes(ClipboardImageFiles.encFile(context, hash)) ?: return null
        return ClipboardCrypto.decryptBytes(enc)
    }

    /**
     * 保存到相册（MediaStore）。返回是否成功。
     *
     * 写入流打不开时把刚插入的 MediaStore 行删掉：否则相册里会留一条 0 字节的空条目
     * （用户看到「保存了但打不开」，比提示失败更糟）。
     */
    fun saveToAlbum(context: Context, item: ClipboardDb.Item, bytes: ByteArray): Boolean {
        if (!albumAvailable()) return false
        val mime = mimeOf(item, bytes)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileNameOf(item, mime))
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR")
            // 写入中标记（BUG.md L-1238，API 29+ 的官方流程）：不设的话相册可能在流写完之前
            // 就扫到这条 0 字节记录，用户看到「保存了但打不开」
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return runCatching {
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            // 写入异常同样回收这一行（BUG.md L-1300）：它带着 IS_PENDING=1，对相册与其它应用
            // 都不可见 ⇒ 用户与开发者都看不到，只在存储紧张 / 远端断流时成批出现、长期占相册库
            val wrote = runCatching { out.use { it.write(bytes) } }
            if (wrote.isFailure) {
                runCatching { resolver.delete(uri, null, null) }
                Diagnostics.w(TAG, "保存到相册写入中断，已回收相册行: ${wrote.exceptionOrNull()?.javaClass?.simpleName}")
                return false
            }
            // 写完解除 pending：这条记录此时才对相册与其它应用可见。
            // ⚠ 失败必须回收并如实返回：带着 IS_PENDING=1 的行对谁都不可见，回执却说「已保存」
            // ⇒ 用户永远找不到这张图，也没有任何入口可修。与紧邻的写失败分支同口径。
            val unpend = runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null,
                )
            }
            // ContentResolver.update 返回**受影响行数**：0 表示行已不在/未命中，此时 IS_PENDING
            // 仍是 1，这条记录对相册与其它应用都不可见 —— 不能当成成功（与异常分支同口径）。
            val rows = unpend.getOrNull() ?: 0
            if (rows <= 0) {
                runCatching { resolver.delete(uri, null, null) }
                Diagnostics.w(TAG, "相册条目解除写入中标记未生效（rows=$rows），已回收")
                return false
            }
            true
        }.getOrElse {
            Diagnostics.w(TAG, "保存到相册失败: ${it.javaClass.simpleName}")
            false
        }
    }

    /**
     * 把一条图片写进 SAF 目录树（「转移到图库目录」与「导出全部图片」共用这一处）。
     *
     * 两条出库路径踩的是同一批坑：`createDocument` 返回 null / 流打不开 / 写入中途失败，
     * **都要删掉刚建的半截文档**（BUG.md L-1301）—— 残留物在用户自己的目录里（不是私有缓存），
     * 提示却说「失败」，用户以为什么都没发生，实际每失败一次就多一个打不开的文件
     * （文件名精确到毫秒，不会互相覆盖）。
     *
     * 返回 [MoveResult.Ok] / [MoveResult.Denied]（没有写授权：绑定年代只 take 了读位，见 L-1322，
     * 界面该请用户重选目录而不是「重试」）/ [MoveResult.Failed]。
     */
    internal fun writeToTree(
        context: Context,
        treeUri: Uri,
        item: ClipboardDb.Item,
        bytes: ByteArray,
    ): MoveResult {
        val mime = mimeOf(item, bytes)
        return runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri),
            )
            // 文件名带哈希前缀（同内容不会重复入库 ⇒ 天然不撞名）与创建时间（保可读性）
            val doc = DocumentsContract.createDocument(
                context.contentResolver, parent, mime, fileNameOf(item, mime),
            ) ?: return MoveResult.Failed
            val out = context.contentResolver.openOutputStream(doc)
            if (out == null) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
                return MoveResult.Failed
            }
            val wrote = runCatching { out.use { it.write(bytes) } }
            if (wrote.isFailure) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
                Diagnostics.w(TAG, "目录树写入中断，已删半截文档: ${wrote.exceptionOrNull()?.javaClass?.simpleName}")
                return MoveResult.Failed
            }
            MoveResult.Ok
        }.getOrElse {
            // 「没有写授权」单列一类（BUG.md L-1322）：只 take 读位的绑定，当次进程靠临时授权
            // 尚可写，进程重建后必然 SecurityException —— 界面要说清该去重选目录，而不是「重试」
            if (it is SecurityException) MoveResult.Denied else MoveResult.Failed
        }
    }

    /**
     * 转移到图库快贴的绑定目录（SAF 写文件）。返回 [MoveResult]。
     *
     * 只有返回 [MoveResult.Ok] 时调用方才删历史 —— 「文件没写成功却把历史删了」是不可恢复的数据丢失。
     */
    fun moveToGalleryFolder(context: Context, item: ClipboardDb.Item, bytes: ByteArray): MoveResult {
        val tree = Prefs(context).galleryTreeUri
        if (tree.isEmpty()) return MoveResult.NeedBinding
        val treeUri = runCatching { Uri.parse(tree) }.getOrNull() ?: return MoveResult.NeedBinding
        return writeToTree(context, treeUri, item, bytes).also {
            if (it != MoveResult.Ok) Diagnostics.w(TAG, "转移到图库目录未成功: $it")
        }
    }

    /**
     * 目录树的**可读落点**（导出回执要告诉用户文件去了哪）。
     *
     * 能拿到「卷 + 相对路径」时（外部存储提供方）说「内部存储/Download/剪贴板图片」；其余提供方
     * （云端盘、第三方文件管理器）拿不到真实路径，退回到「提供方名字 / 文件夹名」—— 宁可不精确，
     * 也不能编一条用户照着找会扑空的路径。
     */
    internal fun displayPath(context: Context, treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (docId != null && treeUri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            externalStoragePath(docId)?.let { return it }
        }
        val name = runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(
                parent, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val provider = runCatching {
            context.packageManager
                .resolveContentProvider(treeUri.authority.orEmpty(), 0)
                ?.loadLabel(context.packageManager)?.toString()
        }.getOrNull()
        return when {
            !name.isNullOrBlank() && !provider.isNullOrBlank() -> "$provider / $name"
            !name.isNullOrBlank() -> name
            !provider.isNullOrBlank() -> provider
            else -> PICKED_FOLDER_ALIAS
        }
    }

    /**
     * 外部存储提供方的文档 id（`卷:相对路径`）转可读路径（纯函数，便于单测）：
     * `primary:Download/x` → `内部存储/Download/x`；没有卷分隔符（不是这个提供方的形态）返回 null。
     *
     * 非 `primary` 的卷是外置存储（SD 卡 / U 盘），本应用拿不到卷标签，写「外置存储」而不猜
     * 「SD 卡」—— 猜错比笼统更糟，用户会照着错的名字去找。
     */
    internal fun externalStoragePath(docId: String): String? {
        val i = docId.indexOf(':')
        if (i <= 0) return null
        val volume = docId.substring(0, i)
        val rel = docId.substring(i + 1).trim('/')
        val root = if (volume.equals("primary", ignoreCase = true)) "内部存储" else "外置存储"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /**
     * 把图片写入**系统剪贴板**（自家 FileProvider URI + 读权限）。
     *
     * 用途：宿主不支持 `commitContent`（纯文本编辑框 / 部分 WebView）时，用户仍能通过长按粘贴。
     * 明文临时文件落 `cacheDir/gallery_share/`（与选图链路同一目录 + 同一套保留策略）。
     *
     * 这不算「干预系统剪贴板」：是用户主动点「复制 / 粘贴图片」的意图延伸，且界面会明说
     * （与 `JinnIme.copySelection` 同款主动写入）。副作用：会触发本机剪贴板监听把该图置顶
     * （hash 已存在，不新增条目），用户预期之内。
     */
    fun copyToSystemClipboard(context: Context, item: ClipboardDb.Item, bytes: ByteArray): Boolean {
        val mime = mimeOf(item, bytes)
        val file = GalleryInsert.stageForInsert(context, bytes, mime) ?: return false
        // 失败分支同样要收尾（BUG.md L-1287）：临时件是本次刚生成的唯一名文件，不删就在缓存目录
        // 留一份**明文**图片 —— 目录的保留策略只在成功路径上跑，失败多了会堆着没人收
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}${GalleryInsert.FILE_PROVIDER_AUTHORITY_SUFFIX}", file)
        }.getOrNull() ?: run {
            file.delete()
            Diagnostics.w(TAG, "复制图片到剪贴板失败：取分享 URI 失败，已清理临时件")
            return false
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: run {
            file.delete()
            Diagnostics.w(TAG, "复制图片到剪贴板失败：剪贴板服务不可用，已清理临时件")
            return false
        }
        return runCatching {
            // newUri 会走 resolver.getType：provider 异常时同样要落到下面的清理分支
            val clip = android.content.ClipData.newUri(context.contentResolver, CLIP_LABEL, uri)
            manager.setPrimaryClip(clip)
            true
        }.getOrElse {
            // 第三条失败分支同样收尾（AI2 A-185，L-1287 的残余）：setPrimaryClip 会抛
            // （Binder 事务超限 / 远端异常 / 剪贴板服务瞬断），不删就留一份明文图片在缓存里。
            // 成功路径**不能**删：宿主读的是这个 file:// URI，删了图就裂了。
            file.delete()
            Diagnostics.w(TAG, "复制图片到剪贴板失败：写入剪贴板抛异常，已清理临时件: ${it.javaClass.simpleName}")
            false
        }
    }

    /**
     * 出库用的 MIME：**按字节嗅探优先**（BUG.md L-1277）。
     *
     * 原先只信条目记录的类型、取不到就回落 `image/png` —— HEIC / AVIF 一类内容若记录缺失
     * （或入库当时就嗅探失败），会被存成 `.png`，相册与后续应用按扩展名解析就可能打不开或显示异常，
     * 而用户不知道这张图被换了类型。嗅探与出库 / 交付路径共用同一个入口
     * （[ClipboardImageCodec.sniffMimeOrNull]：**认不出返回 null**）。
     * 真 PNG 与「头都不认识」由此可分，后者才回落到记录里的类型（它可能更准）。
     */
    internal fun mimeOf(item: ClipboardDb.Item, bytes: ByteArray): String {
        // 嗅探与记录冲突时留痕（BUG.md L-1280 的建议）：排查「导出扩展名不对」要看当时采信了哪一边
        val recorded = item.image?.mime?.takeIf { it.isNotBlank() && it != "image/*" }
        val sniffed = ClipboardImageCodec.sniffMimeOrNull(bytes)
        if (sniffed != null && recorded != null && sniffed != recorded) {
            Diagnostics.v(TAG, "类型不一致：字节=$sniffed 记录=$recorded，按字节为准")
        }
        return sniffed ?: recorded ?: "image/png"
    }

    /**
     * 文件名：哈希前 12 位 + 入库时间（**到毫秒**）+ 真实扩展名（`img:` 前缀与冒号不进文件名）。
     *
     * 毫秒位是给「同一张图在同一秒内被重复保存」用的（BUG.md L-1238）：只到秒时，
     * MediaStore / SAF 的同名行为未定（自动改名，或 `createDocument` 直接返回 null ⇒ 报失败）。
     */
    private fun fileNameOf(item: ClipboardDb.Item, mime: String): String {
        val stem = ClipboardImageFiles.stem(item.contentHash).take(12)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(item.createdAt))
        return "${stem}_$stamp.${GalleryInsert.extOf(mime)}"
    }

    /** 相册里的子目录名（与图库选图链路的目录命名哲学一致：用户能一眼认出是自己存的） */
    const val ALBUM_DIR = "精灵输入法"

    /**
     * 「收藏并移出」成功的回执文案（收藏 + 移出 + 从历史移除三件事都说全）。
     *
     * 面板与历史页**共用这一处字面量**：两份各写一份时会「改一处漏一处」——面板那句原先少了
     * 「并从历史移除」，用户以为记录还在（`BUG.md` L-1335）。
     */
    const val TEXT_FAV_MOVED = "已收藏，原图已移到图库目录（明文），并从历史移除"

    /**
     * 「收藏并移出」**失败**的回执（动作没白做：原图仍加密保存在历史里）。
     *
     * 与 [TEXT_FAV_MOVED] 同源：面板与历史页**共用这一处字面量**，避免「改一处漏一处」（`BUG.md` L-1346）。
     */
    const val TEXT_FAV_MOVE_FAILED = "已收藏，但移到图库目录失败（原图仍加密保存在历史里）"

    /**
     * 「收藏并移出」**未绑定目录**的回执（收藏已加密完成，引导用户去绑定目录）。
     *
     * 同样面板与历史页共用一处字面量（`BUG.md` L-1346）。
     */
    const val TEXT_FAV_NEED_BIND = "已收藏（加密保存）。要按设置把原图移到图库目录，请先绑定目录。"

    /**
     * 普通图片动作（复制 / 保存到相册 / 转移到图库目录）的回执。
     *
     * IME 的图片动作与历史管理页的图片动作是同一套语义，两份各写一份时「改一处漏一处」
     * （与 [TEXT_FAV_MOVED] 同类，见 `BUG.md` L-1346 的处理口径）：字面量只留在本模块，
     * 两处引用同一常量。
     */
    const val TEXT_ACTION_COPIED = "已复制到剪贴板，请在输入框长按粘贴"
    const val TEXT_ACTION_COPY_FAILED = "复制图片失败，请重试"
    const val TEXT_ACTION_SAVED = "已保存到相册"
    const val TEXT_ACTION_SAVE_FAILED = "保存失败（图片已损坏或系统拒绝写入）"
    const val TEXT_ACTION_MOVED = "已转移到图库目录，并从历史删除"
    const val TEXT_ACTION_MOVE_FAILED = "转移失败，图片仍在历史中"
    const val TEXT_ACTION_NEED_BIND = "未绑定图库目录：设置 → 图库快贴"

    /** 外部存储提供方（`DocumentsContract` 的 `primary:` / `XXXX-XXXX:` 形态只出现在这里） */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 连文件夹名都取不到时的兜底说法（回执仍要给出一个「去哪找」的指向） */
    private const val PICKED_FOLDER_ALIAS = "你选择的文件夹"

    private const val CLIP_LABEL = "jinn-image"
    private const val TAG = "ClipboardImageExport"
}
