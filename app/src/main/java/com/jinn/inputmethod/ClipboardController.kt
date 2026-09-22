package com.jinn.inputmethod

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 剪贴板控制器。
 *
 * 职责：
 *  - 监听系统剪贴板变化（[addPrimaryClipChangedListener]）；
 *  - 系统剪贴板任何变化（含本 IME 功能面板的「复制选中」）→ 自动分类 → 加密保存到 [ClipboardDb]；
 *  - 依据 Android 版本约束执行「普通模式」的读取边界（API 29+ 只有前台
 *    或本 IME 为前台输入法时才能读取系统剪贴板；API 33+ 系统会弹出
 *    剪贴板访问提示并可能自动清空，本类不绕过这些系统机制）。
 *
 * 普通模式不做任何系统级拦截：读取与否完全遵循
 * Android 官方行为，本控制器只管理「本输入法自己的历史数据库」。
 *
 * 线程模型：ClipboardManager 回调在主线程，入库等 IO 操作切后台线程。
 */
class ClipboardController(context: Context) {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val db = ClipboardDb.get(appContext)

    /** 空剪贴板重试的延时载体：等待走主线程 Handler，不占用共享的 IO 单线程队列 */
    private val retryHandler = Handler(Looper.getMainLooper())

    private var listenerRegistered = false
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    // 这里故意没有「本次变化来自自身，跳过保存」的抑制标记。
    // 本类是全应用唯一的入库入口，而 IME 侧唯一的 setPrimaryClip（功能面板「复制选中」）
    // 本就希望被记进历史（见 JinnIme.copySelection）。早先的 ownCommit 标记却被三处
    // 「粘贴」路径置位，它们都不写系统剪贴板，标记只会被监听侧当成「下一次变化是自身的」
    // 吞掉一次：粘贴后 3 秒内的真实复制会被静默丢弃，历史里根本查不到。

    /** 启用：注册系统剪贴板监听。幂等。 */
    fun start() {
        if (listenerRegistered) return
        clipboard.addPrimaryClipChangedListener(listener)
        listenerRegistered = true
        Diagnostics.i(TAG, "start: 剪贴板监听已注册")
    }

    /** 停用：注销监听（幂等）并丢弃待执行的重试，避免停用后仍落库 */
    fun stop() {
        if (!listenerRegistered) return
        clipboard.removePrimaryClipChangedListener(listener)
        listenerRegistered = false
        retryHandler.removeCallbacksAndMessages(null)
        Diagnostics.i(TAG, "stop: 剪贴板监听已注销")
    }

    private fun onClipboardChanged() {
        // 功能关闭时不入库。此前只有 copySelection 那条路径检查了 enabled，
        // 监听路径无条件保存，用户关掉剪贴板功能后系统复制仍会被记录。
        if (!ClipboardPrefs.of(appContext).enabled) return

        // Android 10+ 后台进程读 primaryClip 可能拿到 null（时序/权限边界）：
        // 监听回调触发时系统可能尚未完成写入，或本进程刚退到后台。
        // 延迟 250ms 重试一次，避免把真实复制误判为空。
        if (clipboard.primaryClip == null) {
            // 等待用主线程 Handler，不能在 BackgroundIo 里 sleep：那是单线程串行队列，
            // 一睡就把入库、搜索解密、粘贴取正文、词频落盘全部堵住（实测同队列同一线程）。
            retryHandler.postDelayed({
                val retryClip = clipboard.primaryClip ?: return@postDelayed
                BackgroundIo.run { extractAndSave(retryClip) }
            }, RETRY_DELAY_MS)
            return
        }
        val clip = clipboard.primaryClip ?: return
        // 取文本（coerceToText）也放后台：URI 型条目会同步读取整个 content:// 流
        // （AOSP 实现把流读进 StringBuilder，无长度上限），在主线程上就是一次文件读 ，
        // 输入法键盘卡顿甚至 ANR 的来源。回调里只做开销极小的 ClipData 快照读取。
        BackgroundIo.run { extractAndSave(clip) }
    }

    /**
     * 提取文本并入库（后台线程）。
     *
     * `coerceToText` 可能打开 ContentResolver 流，绝不能放主线程；正常与重试两条路径
     * 统一走它，口径一致。0 条目守卫也集中在这里（`getItemAt(0)` 越界会抛异常）。
     */
    private fun extractAndSave(clip: android.content.ClipData) {
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return
        if (text.isBlank()) return
        // upsert 在库层按 content_hash 去重，不会重复写入
        ClipboardStore.save(appContext, db, text, resolveSourcePackage())
    }

    /**
     * 来源 APP。
     * 理想做法是读 ClipData 项的 contentDescription（API 33+ 部分 APP 写入来源包名），
     * 但该 API 在低版本不可用且不同 APP 写法不一，普通模式下系统剪贴板本身
     * 不携带可靠来源信息。这里保守返回本包名（历史条目来源标记为「本输入法」），
     * 来源追踪留待 Root 增强模式从 ClipboardService 层获取。
     */
    private fun resolveSourcePackage(): String = appContext.packageName

    private companion object {
        const val TAG = "ClipboardController"

        /** 空剪贴板的重试延时：等系统把 primaryClip 写完 */
        const val RETRY_DELAY_MS = 250L
    }
}

/**
 * 把一条剪贴板内容存进历史的纯逻辑（独立出来便于单测）。
 *
 * - 自动分类（URL / NUMBER / OTHER；隐私类内容绝不自动判定）
 * - 加密入库，并按数量上限裁剪
 *
 * 不做任何内容性质检测，不因内容特征跳过保存、加过期或删除；
 * 删除只来自用户主动删除、去重和容量上限裁剪最旧的非收藏记录。
 */
object ClipboardStore {

    /**
     * 单条上限（UTF-8 明文字节）：超过直接不入库，避免一条巨文本就把库撑到失控。
     *
     * 注意口径：本条按明文字节算，而总量预算（[ClipboardDb.DEFAULT_MAX_TOTAL_BYTES]）
     * 按库内密文体积算，两者单位不同，不要互相换算成同一个数。
     * 另：本条只拦采集（新复制的内容）；库里既有的超限行不会被它清理，只会被总量预算按最旧非收藏淘汰。
     */
    const val MAX_ITEM_BYTES = 256 * 1024

    /**
     * 单条解密的内存放大系数（实测值 + 余量）。
     *
     * 单条解密后常驻两份数据：库内密文串（base64 ≈ 明文 4/3）+ 解出的明文串。
     * 实测（`TmpAmplificationProbeTest`，256KB 明文、ASCII/CJK/base64 样三种）：
     * 密文串 349,564 B + 明文串 262,144 B ÷ 明文 262,144 B = 2.33 倍
     *，与「4/3 + 1」吻合；明文串按 UTF-8 字节计（JVM/ART 均为紧凑字符串，
     * 早先"按 UTF-16 翻倍"的假设不成立）。取 2.5 留约 7% 余量覆盖对象头与瞬时解码缓冲。
     */
    private const val DECRYPT_ITEM_AMPLIFICATION = 2.5

    /**
     * 一次批量解密窗口的内存预算（字节）。
     *
     * 按当前参数：窗口 50 × 单条上限 256KB × 放大 2.5 ≈ 32.8MB（最坏情形：整窗口都顶到单条上限，
     * 实测常驻约 29.8MB）；常规内容远低于此。若日后放宽单条上限或调大窗口，
     * `ClipboardLimitsTest` 的护栏会先失败（本预算约允许到 73 条窗口）。
     */
    const val DECRYPT_WINDOW_BUDGET_BYTES = 48L * 1024 * 1024

    /**
     * 批量解密窗口的最坏内存估算（字节，纯函数）。
     *
     * 口径 = 窗口条数 × 单条上限 × [DECRYPT_ITEM_AMPLIFICATION]；
     * 调用处用「≤ [DECRYPT_WINDOW_BUDGET_BYTES]」锁住参数，避免调大窗口时静默抬高内存峰值。
     */
    fun decryptWindowPeakBytes(windowItems: Int, maxItemBytes: Int = MAX_ITEM_BYTES): Long {
        if (windowItems <= 0 || maxItemBytes <= 0) return 0L
        return (windowItems.toLong() * maxItemBytes.toLong() * DECRYPT_ITEM_AMPLIFICATION).toLong()
    }

    /**
     * 文本的 UTF-8 字节数是否超过 [limit]（纯函数，便于单测）。
     *
     * 先按字符数快筛：UTF-8 字节数恒 ≥ 字符数，字符数都超了就不必再编码 ，
     * 否则一个 20MB 的串先被复制成 27MB 字节数组，检查本身就成了内存风险。
     */
    fun exceedsItemLimit(text: String, limit: Int = MAX_ITEM_BYTES): Boolean {
        if (limit <= 0) return false
        if (text.length > limit) return true
        return text.toByteArray(Charsets.UTF_8).size > limit
    }

    /**
     * 搜索结果驻留上限（条数）。
     *
     * 搜索是「分块扫描 + 命中即累积」：`SEARCH_WINDOW_ITEMS` 只约束单次解密窗口，
     * 命中集合却跨全表增长，且每次发布还要再复制一份列表。命中「a」「的」这类
     * 高频词时集合会吃掉整库的明文字节，直接违背 [DECRYPT_WINDOW_BUDGET_BYTES]
     * 那条内存护栏。给集合本身也设上限，超了就停止累积。
     */
    const val MAX_SEARCH_RESULTS = 200

    /**
     * 搜索结果驻留的明文字节预算。
     *
     * 取 [DECRYPT_WINDOW_BUDGET_BYTES] 的一半：解密窗口是瞬时的，命中集合要一直
     * 活到用户改关键词，留一半给窗口与 UI 周转。
     */
    const val SEARCH_RETAIN_BUDGET_BYTES = DECRYPT_WINDOW_BUDGET_BYTES / 2

    /**
     * 文本的 UTF-8 字节数（纯函数）。
     *
     * 驻留/容量这类预算都按 UTF-8 字节算，而 `String.length` 是 UTF-16 字符数：
     * 中文 1 字符在 UTF-8 下占 3 字节，拿长度当字节会把预算低估到 1/3，护栏形同虚设。
     * 这里与 [exceedsItemLimit] 保持同一口径。
     */
    fun utf8ByteSize(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * 搜索结果是否该停止累积（纯函数，便于单测）。
     *
     * 条数与累计明文字节任一触顶即停：只限条数挡不住 200 条 256KB 的巨文本，
     * 只限字节又会让大量短条目把 UI 列表撑爆。
     */
    fun searchRetainLimitReached(
        matches: Int,
        retainedBytes: Long,
        maxResults: Int = MAX_SEARCH_RESULTS,
        maxBytes: Long = SEARCH_RETAIN_BUDGET_BYTES,
    ): Boolean = matches >= maxResults || retainedBytes >= maxBytes

    /**
     * 保存流程。返回保存的条目 id，未保存返回 null。
     * @param sourceAppName 来源应用名（显示用，为空则按包名推断）
     */
    fun save(
        context: Context,
        db: ClipboardDb,
        text: String,
        sourcePackage: String,
        sourceAppName: String = "",
        maxItems: Int = readMaxItems(context),
    ): Long? {
        // 单条体积上限：超出即丢弃（不截断，半截内容比不记更糟）
        if (exceedsItemLimit(text)) {
            Diagnostics.w(TAG, "超单条上限，不入库: len=${text.length} 上限=${MAX_ITEM_BYTES}B")
            return null
        }
        val appName = sourceAppName.ifBlank { guessAppName(context, sourcePackage) }
        // 自动分类（URL / NUMBER / OTHER）；隐私分类绝不自动判断
        val category = ClipboardClassifier.classify(text)
        Diagnostics.i(TAG, "save: 已保存 len=${text.length} (分类=$category)")
        return db.upsert(
            text, "text", sourcePackage, appName, maxItems,
            category = category,
        )
    }

    private fun readMaxItems(context: Context): Int =
        ClipboardPrefs.of(context).maxItems

    private fun guessAppName(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    }.getOrElse { pkg.substringAfterLast('.') }

    private const val TAG = "ClipboardStore"
}
