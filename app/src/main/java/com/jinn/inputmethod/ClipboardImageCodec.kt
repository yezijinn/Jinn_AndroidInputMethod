package com.jinn.inputmethod

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 剪贴板图片的编解码：缩略图生成与采样解码。
 *
 * 只在采集（生成缩略图）与渲染（网格解码）两处使用，全部是纯函数式工具：
 * 输入字节、输出字节/位图，不持有状态、不碰文件与数据库 —— 采样决策可直接 JVM 单测。
 *
 * 内存口径：解码一律先 `inJustDecodeBounds` 读尺寸、再按目标长边取 `inSampleSize`（2 的幂），
 * 采集侧再退回 [Bitmap.Config.RGB_565]（缩略图不需要 alpha，内存减半）。
 * 单张上限 20MB 的原图在这条链路上的峰值 = 原字节 + 采样位图（512 档 ≈ 0.5MB），与
 * `GalleryInsert` 的选图链路同量级。
 */
internal object ClipboardImageCodec {

    /** 缩略图长边（像素）：覆盖历史页最细档（8 列 @3x ≈ 405px）与面板网格，512 留余量 */
    const val THUMB_LONG_EDGE = 512

    /** 缩略图 JPEG 质量（有损但只用于网格展示；原图另行加密保存，粘贴走原字节） */
    const val THUMB_QUALITY = 80

    /** 目标长边对应的 2 的幂采样率（纯函数）：保证解码结果 ≥ 目标，且不超过目标的 2 倍 */
    fun sampleSizeFor(srcW: Int, srcH: Int, targetLongEdge: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetLongEdge <= 0) return 1
        var sample = 1
        var longEdge = maxOf(srcW, srcH)
        while (longEdge / 2 >= targetLongEdge) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    /** 读图片尺寸（不解码像素）；读不出返回 null */
    fun bounds(bytes: ByteArray): IntArray? {
        if (bytes.isEmpty()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return intArrayOf(o.outWidth, o.outHeight)
    }

    /** 按文件头嗅探 MIME（12 字节足够；与图库选图共用 [GalleryInsert.sniffImageMime]） */
    fun sniffMime(bytes: ByteArray): String =
        GalleryInsert.sniffImageMime(if (bytes.size <= 12) bytes else bytes.copyOf(12))

    /**
     * 生成缩略图（JPEG 字节）；原图解不出像素（损坏 / 不支持的格式）返回 null ——
     * 采集侧据此仍保存原图，只是网格里显示灰块（见实施计划 §5「Keystore 失效」同款降级）。
     */
    fun thumbJpeg(bytes: ByteArray): ByteArray? = runCatching {
        val size = bounds(bytes) ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(size[0], size[1], THUMB_LONG_EDGE)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val scaled = scaleToLongEdge(decoded, THUMB_LONG_EDGE)
        // 缩放会产出**新位图**：中间那张立刻回收 —— 采样后仍可能有几 MB（4000px 档 ≈ 4MB），
        // 采集线程是长活池，容不得它等到下一次 GC（与「常驻进程内存纪律」同口径）
        if (scaled !== decoded) decoded.recycle()
        val out = ByteArrayOutputStream(64 * 1024)
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)) return null
        out.toByteArray()
    }.getOrElse {
        Diagnostics.w(TAG, "缩略图生成失败: ${it.javaClass.simpleName}")
        null
    }

    /**
     * 网格渲染解码：把（已解密的）缩略图字节按目标像素采样成位图。
     *
     * [targetPx] 是单元格长边（像素），解码结果供 `centerCrop` 缩放 —— 这里不做二次缩放，
     * 交给 ImageView 的 scaleType（与图库面板同款，省一次全尺寸位图分配）。
     */
    fun decodeForTarget(bytes: ByteArray, targetPx: Int): Bitmap? = runCatching {
        val size = bounds(bytes) ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(size[0], size[1], targetPx.coerceAtLeast(1))
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    private fun scaleToLongEdge(src: Bitmap, target: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= 0 || longEdge <= target) return src
        val ratio = target.toFloat() / longEdge
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrDefault(src)
    }

    private const val TAG = "ClipboardImageCodec"
}
