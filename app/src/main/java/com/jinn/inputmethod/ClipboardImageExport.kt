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
 *  - 相册写入走 `MediaStore` + `RELATIVE_PATH`，**API 29+ 免权限**；26–28 需
 *    `WRITE_EXTERNAL_STORAGE`，本应用不申请 ⇒ [albumAvailable] 为 false 时界面不提供「保存」；
 *  - 转移到图库目录走 SAF（`Prefs.galleryTreeUri` 的持久授权）—— 未绑定 / 授权失效都归
 *    [MoveResult.NeedBinding] 或 [MoveResult.Failed]，**绝不删历史**（只有写成功才删，见调用方）。
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
            out.use { it.write(bytes) }
            // 写完解除 pending：这条记录此时才对相册与其它应用可见
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null,
                )
            }.onFailure { Diagnostics.w(TAG, "相册条目解除写入中标记失败（图已落盘）") }
            true
        }.getOrElse {
            Diagnostics.w(TAG, "保存到相册失败: ${it.javaClass.simpleName}")
            false
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
            out.use { it.write(bytes) }
            MoveResult.Ok
        }.getOrElse {
            Diagnostics.w(TAG, "转移到图库目录失败: ${it.javaClass.simpleName}")
            MoveResult.Failed
        }
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
            FileProvider.getUriForFile(context, "${context.packageName}.share", file)
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
        val clip = android.content.ClipData.newUri(context.contentResolver, CLIP_LABEL, uri)
        return runCatching {
            manager.setPrimaryClip(clip)
            true
        }.getOrElse {
            Diagnostics.w(TAG, "复制图片到剪贴板失败: ${it.javaClass.simpleName}")
            false
        }
    }

    /**
     * 出库用的 MIME：**按字节嗅探优先**（BUG.md L-1277）。
     *
     * 原先只信条目记录的类型、取不到就回落 `image/png` —— HEIC / AVIF 一类内容若记录缺失
     * （或入库当时就嗅探失败），会被存成 `.png`，相册与后续应用按扩展名解析就可能打不开或显示异常，
     * 而用户不知道这张图被换了类型。嗅探与入库侧同一套（[GalleryInsert.sniffImageMime]）。
     * 嗅探兜底回 `image/png` 只说明「头都不认识」，此时才回落到记录里的类型（它可能更准）。
     */
    private fun mimeOf(item: ClipboardDb.Item, bytes: ByteArray): String {
        val sniffed = GalleryInsert.sniffImageMime(bytes)
        if (sniffed != "image/png") return sniffed
        return item.image?.mime?.takeIf { it.isNotBlank() && it != "image/*" } ?: sniffed
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

    private const val CLIP_LABEL = "jinn-image"
    private const val TAG = "ClipboardImageExport"
}
