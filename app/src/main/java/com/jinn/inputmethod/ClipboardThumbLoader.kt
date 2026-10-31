package com.jinn.inputmethod

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
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

    /**
     * 「这张已在解」时挂在这里的回调，与在解的那次同批投递（BUG.md L-1489 复测实证）。
     *
     * 丢掉重复请求的旧写法有个坑：后一个消费者永远拿不到图。典型触发是**同一屏在解码期间重排**
     * （改列数 / 行高、面板重开、收藏切换都会 `generation++` 重建 cell）：旧 cell 的结果被世代判据
     * 丢弃，新 cell 的请求又被去重挡下 —— 两边的回调都没了，那一格就一直停在空白
     * （真机日志：`@260` 连提两次，1ms 内第二次被去重）。挂在同一个 key 上，一次解码喂所有等待者。
     *
     * 用写时复制列表：投递侧与登记侧都不需要外部同步，遍历中途有人登记也不会抛
     * `ConcurrentModificationException`（那会从主线程的 `Handler.post` 冒出去，等于崩进程）。
     */
    private val waiters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(String, Bitmap) -> Unit>>()

    /** 主线程投递用的 Handler：提到字段，不再每次解码完成都新建（与图库池同款） */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 按机型折半后的缓存上限（与图库池同款，BUG.md L-1390）：首次用到时按档位落定一次 */
    private fun thumbCacheBytes(context: Context) =
        DeviceTier.budget(context, THUMB_CACHE_BYTES.toLong()).toInt()

    /** 缓存是否已按当前档位设过上限（避免每次 load 都重设、把已有图清掉） */
    private val cacheSized = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 进程级内存回调是否已注册（只注册一次，见 [ensureTrimCallbacks]）。
     *
     * 用原子位而不是布尔：`load()` 全在主线程，但这份缓存是共享单例，将来谁把它挪到后台线程
     * 也不该多注册一份回调（多注册就是同一个档位清两次，且持有两份回调对象）。
     */
    private val trimCallbacksRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 内存紧张时该不该把解码位图让出去 —— **唯一判据**（`JinnIme` 与本文件的进程级回调共用）。
     *
     * 只在真紧张的三档动手：`RUNNING_LOW` / `RUNNING_CRITICAL` / `COMPLETE`。
     * ⚠ 刻意**不含** `UI_HIDDEN`(20) / `BACKGROUND`(40) / `MODERATE`(60)，理由不是「它们常来」：
     * ① `BACKGROUND` / `MODERATE` / `COMPLETE` 只发给 `curProcState >= PROCESS_STATE_HOME`(14) 的
     *    缓存 / LRU 档进程（另有 heavy-weight），而 IME 服务活着时最差也只是 perceptible ——
     *    实测键盘弹出 `adj=0 / state=2`、收起 `adj=200 / state=7`，都 < 14 ⇒ 前两档**结构性不可达**
     *    （`COMPLETE` 留下只为纵深防御：真到那一档时本进程已不在）；
     * ② `UI_HIDDEN` 是**本应用自己的页面**（设置 / 历史页）离屏，与内存压力无关；按它清等于
     *    每次离开设置页就把键盘面板要用的图清一遍，收益为零。
     */
    internal fun shouldReleaseForTrim(level: Int): Boolean {
        return level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    }

    /**
     * 把 [releaseMemory] 挂到 **Application** 上（一次性，幂等）。
     *
     * 为什么不能只靠 `JinnIme.onTrimMemory`（BUG.md L-1489 记录的已知边界）：那份缓存由
     * 「键盘面板」与「历史页」共用，而让位入口原先只长在 IME 服务上 —— `am force-stop` 之后
     * 只起历史页（`dumpsys activity services` 里 `JinnIme` 为 DEAD）时，12MB 全由活动页填充，
     * 系统报内存紧张却没有任何人让位。挂在 Application 上就没有这个前提：进程还在就收得到。
     *
     * 回调对象不捕获任何 Context / View（只调本单例的方法），Application 持有它不会钉住页面。
     * 注册失败只记一笔：`JinnIme` 那份入口仍在，功能不受影响。
     */
    private fun ensureTrimCallbacks(context: Context) {
        if (!trimCallbacksRegistered.compareAndSet(false, true)) return
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (shouldReleaseForTrim(level)) releaseMemory()
            }

            override fun onLowMemory() {
                releaseMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        runCatching { context.applicationContext.registerComponentCallbacks(callbacks) }
            .onFailure {
                // 注册失败要放开标记：下一次 load() 还能再试一遍，而不是这一整个进程都没人让位
                trimCallbacksRegistered.set(false)
                Diagnostics.w(TAG, "内存回调注册失败: ${it.javaClass.simpleName}")
            }
    }

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
     * 系统报内存紧张时把解码位图让出去（BUG.md L-1489）。
     *
     * 原先这份缓存**没有第二个释放点**：`ClipboardImageGridView.stopWork()` 明确不清缓存
     * （按实例清会误伤另一处正在用的屏，见 L-1262），LRU 只在装不下时按字节上限自己淘汰
     * ⇒ 浏览过剪贴板图片后最多 12MB（低档机 6MB）解码位图长期驻留在常驻的 IME 进程里。
     *
     * 现在有**两个**入口，判据同一份（[shouldReleaseForTrim]）：① `JinnIme` 的两个内存回调；
     * ② 本对象挂在 Application 上的进程级 ComponentCallbacks2（服务不在时也照样让位）。
     *
     * 清缓存不改正确性：缓存是纯派生数据，下一次 [load] 必然 miss 并重新解密解码；
     * **已在屏幕上的位图不会消失**（由 ImageView 持有，不随缓存清空而回收），所以不会出现空白格子。
     * 负缓存 [failedAt] 与在途登记 [inFlight] 不动：两者都不含位图，清掉反而会让坏图立刻重排解码。
     */
    fun releaseMemory() {
        val before = cache.size()
        cache.evictAll()
        if (before > 0) Diagnostics.i(TAG, "缩略图缓存让位: 释放 ${before / 1024}KB")
    }

    /**
     * 异步取一张缩略图（解密 + 采样解码）。
     *
     * [onReady] 在主线程回调，参数是 (hash, 位图)；调用方必须用它与自己视图当前绑定的
     * hash 比对后再 `setImageBitmap`（cell 会被复用，晚到的结果可能属于别的条目）。
     * 缓存命中时**同步**回调（在同一线程栈上），调用方需容忍这一点。
     */
    fun load(context: Context, hash: String, targetPx: Int, onReady: (String, Bitmap) -> Unit) {
        // 让位入口跟着「第一次真要显示缩略图」挂上（见 [ensureTrimCallbacks]）：本对象是 Object，
        // 拿到 Context 的唯一自然时机就在这里；晚一步注册也不会漏掉这张图 —— 缓存刚放进来的
        // 那一张，下一次内存回调照样清得掉。
        ensureTrimCallbacks(context)
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
        if (!inFlight.add(key)) {
            // 已在解：把这次的回调挂上去，与在解的那次**同批投递**。
            // 直接 return 会让后来的消费者空手而归（见上面 waiters 的说明）。
            // computeIfAbsent 而不是 getOrPut：后者的「先 get 再 put」不是原子操作，
            // 两个线程同时登记会丢掉其中一个回调 —— 正是要修的那种空白格
            waiters.computeIfAbsent(key) { java.util.concurrent.CopyOnWriteArrayList() }.add(onReady)
            return
        }
        runCatching {
            pool.execute {
                // 解码本身抛异常（Keystore 抛错 / OOM）也要走下面的收尾分支：让任务带着异常结束的话，
                // 这个 key 会永久留在 inFlight 里（这张图之后再也排不进去），挂上的等待者也永远收不到图
                val bmp = runCatching { cache.get(key) ?: decode(context, hash, key, targetPx) }
                    .onFailure { Diagnostics.w(TAG, "缩略图解码异常: ${it.javaClass.simpleName}") }
                    .getOrNull()
                if (bmp != null) {
                    uiHandler.post {
                        inFlight.remove(key)
                        onReady(hash, bmp)
                        // 同一 key 的其它等待者：同一批投递，别让它们停在空白格上（见上面 waiters）
                        waiters.remove(key)?.forEach { cb -> runCatching { cb(hash, bmp) } }
                    }
                } else {
                    // 登记在主线程，收尾也必须回主线程：跨线程清表会与 computeIfAbsent 交错，
                    // 留下一条永不投递的回调（持有旧 cell）
                    uiHandler.post {
                        inFlight.remove(key)
                        waiters.remove(key)
                    }
                }
            }
        }.onFailure {
            // 池关闭 / 饱和时 execute 抛 RejectedExecutionException，任务整份丢失：必须留痕，
            // 否则表现为「某一格一直是灰块」且诊断包里查不到任何线索（与 BackgroundIo.rejected 同口径，BUG.md L-1369）
            // ⚠ 同时清在途标记：否则该 key 永久留在集合里，之后再也排不进去（比重复解码更糟）
            inFlight.remove(key)
            waiters.remove(key)
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
