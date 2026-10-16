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

    private const val TAG = "ClipThumb"

    private const val THUMB_THREADS = 3

    /** 解码失败的负结果存活时长（BUG.md L-1237）：坏图在这段时间内不再反复排队解码 */
    private const val FAIL_TTL_MS = 10_000L

    /** 解码失败的负缓存（键 → 失败时刻）：快速滚动 / 视图复用会把同一张坏图反复请求 */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val THUMB_CACHE_BYTES = 12 * 1024 * 1024

    /** 在途解码的 key：并发下防同一张重复提交（与图库池同款，BUG.md L-1390） */
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 主线程投递用的 Handler：提到字段，不再每次解码完成都新建（与图库池同款） */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 按机型折半后的缓存上限（与图库池同款，BUG.md L-1390）：首次用到时按档位落定一次 */
    private fun thumbCacheBytes(context: Context) =
        DeviceTier.budget(context, THUMB_CACHE_BYTES.toLong()).toInt()

    /** 缓存是否已按当前档位设过上限（避免每次 load 都重设、把已有图清掉） */
    private val cacheSized = java.util.concurrent.atomic.AtomicBoolean(false)

    private val pool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(THUMB_THREADS) { r ->
            // 解码是满核的活，且与输入法同进程：在线程体内降后台优先级（BUG.md L-1194，
            // 与图库缩略图池同款 —— 剪贴板面板铺图时不与主线程打字抢 CPU）
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "jinn-clipthumb").apply { isDaemon = true }
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
        // 低内存档把缓存折半（与图库池同款）：只做一次，避免每帧重设把已有缩略图清掉
        if (cacheSized.compareAndSet(false, true)) {
            val budget = thumbCacheBytes(context)
            if (budget != THUMB_CACHE_BYTES) cache.resize(budget)
        }
        val key = keyOf(hash, targetPx)
        cache.get(key)?.let {
            onReady(hash, it)
            return
        }
        // 负缓存（BUG.md L-1237）：解不出的图在 TTL 内直接跳过 —— 失败路径原先没有任何缓存，
        // 同一张坏图每被请求一次就重解一次（快速滚动 / cell 复用时最明显）
        failedAt[key]?.let { at ->
            if (System.currentTimeMillis() - at < FAIL_TTL_MS) return
            failedAt.remove(key)
        }
        // 在途去重（BUG.md L-1390）：cell 复用 / 快速滚动会把同一张连着提交好几次，
        // 3 条线程可能对同一张各解一次（每张含一次 Keystore 解密 + 位图解码）
        if (!inFlight.add(key)) return
        runCatching {
            pool.execute {
                val bmp = cache.get(key) ?: decode(context, hash, key, targetPx)
                if (bmp != null) {
                    uiHandler.post { inFlight.remove(key); onReady(hash, bmp) }
                } else {
                    inFlight.remove(key)
                }
            }
        }.onFailure {
            // 池关闭 / 饱和时 execute 抛 RejectedExecutionException，任务整份丢失：必须留痕，
            // 否则表现为「某一格一直是灰块」且诊断包里查不到任何线索（与 BackgroundIo.rejected 同口径，BUG.md L-1369）
            // ⚠ 同时清在途标记：否则该 key 永久留在集合里，之后再也排不进去（比重复解码更糟）
            inFlight.remove(key)
            Diagnostics.w(TAG, "缩略图任务提交被拒（池已关闭/饱和）: ${it.javaClass.simpleName}")
        }
    }

    private fun decode(context: Context, hash: String, key: String, targetPx: Int): Bitmap? {
        val file = ClipboardImageFiles.thumbFile(context, hash)
        val enc = ClipboardImageFiles.readBytes(file)
        // 灰块有三种原因（没文件 / 解密失败 / 解不出图），不分清楚的话诊断包里查不到是哪种、
        // 也不知道是哪个哈希，只能靠复现（BUG.md L-1264）
        // 负缓存三条失败路径**都要**记（BUG.md L-1330）：原先只有「解不出图」那条记，
        // 于是换机 / 密钥失效（整库缩略图全走解密失败）与 `.thumb` 缺失这两种场景下，
        // 每一格每次重新绑定（滚动、cell 复用、翻页）都重新排一次任务并各写一条 W 日志 ——
        // 而这两种恰恰是排查时最该看清的，日志反被自己刷爆
        if (enc == null) {
            Diagnostics.w(TAG, "缩略图读取失败 ${hash.take(12)}")
            markFailed(key)
            return null
        }
        val plain = ClipboardCrypto.decryptBytes(enc)
        if (plain == null) {
            Diagnostics.w(TAG, "缩略图解密失败 ${hash.take(12)}")
            markFailed(key)
            return null
        }
        val bmp = ClipboardImageCodec.decodeForTarget(plain, targetPx)
        if (bmp == null) {
            // 这条属**预期**情形（尺寸读不出的图只存了占位字节）：记 V 不记 W ——
            // 每次滚动重渲染都会走到这里，W 会把诊断包刷满
            Diagnostics.v(TAG, "缩略图解码失败 ${hash.take(12)} target=$targetPx")
            markFailed(key)
            return null
        }
        cache.put(key, bmp)
        return bmp
    }

    /**
     * 记一次失败（负缓存）：读取 / 解密 / 解码三条路径共用（BUG.md L-1237 / L-1330）。
     *
     * 只挡 [FAIL_TTL_MS]：窗口内不再为这张图排队，窗口外仍会再试一次 ——
     * 密钥恢复、`.thumb` 被重新写出这类情形不必重启才能看到。
     */
    private fun markFailed(key: String) {
        failedAt[key] = System.currentTimeMillis()
    }
}
