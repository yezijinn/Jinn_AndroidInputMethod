package com.jinn.inputmethod

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 剪贴板图片缩略图的共享加载器：面板网格与历史页网格共用**一套**线程池与缓存。
 *
 * 为什么共享而不是各写一份（`GalleryPanelView` 是各写一份的）：
 * 剪贴板缩略图的两个消费方（键盘内网格 / 历史页网格）会**同时活着**（历史页打开时键盘
 * 也可能重建），两份 12MB 缓存 + 两个 3 线程池在常驻进程里是白付的内存与线程；
 * 而它们的取数口径完全一致（同一个目录、同一套文件名、同一种密文）。
 *
 * 内存纪律：按**字节数**限容的 [LruCache]（12MB，与图库面板同值）；输入法是常驻进程，
 * 缩略图不设上限会被系统杀掉（表现为键盘突然消失）。
 * 线程纪律：3 线程（与图库面板同值）；解码全在池内，主线程只 `setImageBitmap`。
 */
internal object ClipboardThumbLoader {

    private const val THUMB_THREADS = 3
    private const val THUMB_CACHE_BYTES = 12 * 1024 * 1024

    private val pool: ExecutorService = Executors.newFixedThreadPool(THUMB_THREADS) { r ->
        Thread(r, "jinn-clipthumb").apply { isDaemon = true }
    }

    private val cache = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 缓存键 = 哈希 + 目标像素（BUG.md L-1245）：不带尺寸的话，调大行高 / 减少列数之后格子变大，
     * 缓存里仍是按旧尺寸解出的位图，直接放大显示会发虚（要等视图被丢弃才恢复）。
     */
    private fun keyOf(hash: String, targetPx: Int): String = "$hash@$targetPx"

    fun cached(hash: String, targetPx: Int): Bitmap? = cache.get(keyOf(hash, targetPx))

    /**
     * 异步取一张缩略图（解密 + 采样解码）。
     *
     * [onReady] 在主线程回调，参数是 (hash, 位图)；调用方必须用它与自己视图当前绑定的
     * hash 比对后再 `setImageBitmap`（cell 会被复用，晚到的结果可能属于别的条目）。
     * 缓存命中时**同步**回调（在同一线程栈上），调用方需容忍这一点。
     */
    fun load(context: Context, hash: String, targetPx: Int, onReady: (String, Bitmap) -> Unit) {
        val key = keyOf(hash, targetPx)
        cache.get(key)?.let {
            onReady(hash, it)
            return
        }
        runCatching {
            pool.execute {
                val bmp = cache.get(key) ?: decode(context, hash, key, targetPx)
                if (bmp != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(hash, bmp) }
                }
            }
        }
    }

    /** 视图被丢弃 / 页面退出时清缓存（线程池常驻，进程级 3 线程，不必反复建销） */
    fun clearCache() {
        cache.evictAll()
    }

    private fun decode(context: Context, hash: String, key: String, targetPx: Int): Bitmap? {
        val file = ClipboardImageFiles.thumbFile(context, hash)
        val enc = ClipboardImageFiles.readBytes(file) ?: return null
        val plain = ClipboardCrypto.decryptBytes(enc) ?: return null
        val bmp = ClipboardImageCodec.decodeForTarget(plain, targetPx) ?: return null
        cache.put(key, bmp)
        return bmp
    }
}
