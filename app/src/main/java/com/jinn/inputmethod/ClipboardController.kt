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
 *  - 前台 / 自身变化时捕获内容 → 自动分类 → 加密保存到 [ClipboardDb]；
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

    /** 是否应保存到历史：来源为自身 IME 上屏的文本不算「外部剪贴板」，跳过 */
    @Volatile
    private var ownCommit = false

    /** ownCommit 置位时间戳：超过 OWN_COMMIT_WINDOW_MS 的标记视为过期（打字后复制不应被误杀） */
    @Volatile
    private var ownCommitAt = 0L

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

    /** IME 自身提交文本时调用，标记下一次剪贴板变化来自自身，不保存历史 */
    fun onOwnCommit() {
        ownCommit = true
        ownCommitAt = System.currentTimeMillis()
    }

    private fun onClipboardChanged() {
        // 功能关闭时不入库。此前只有 copySelection 那条路径检查了 enabled，
        // 监听路径无条件保存 —— 用户关掉剪贴板功能后系统复制仍会被记录。
        if (!ClipboardPrefs.of(appContext).enabled) return

        // 只有「短时间内的自身粘贴」才跳过保存；过期的 ownCommit 标记（打字后
        // 用户手动复制）必须正常保存，否则真实复制会被误杀。
        if (ownCommit && System.currentTimeMillis() - ownCommitAt <= OWN_COMMIT_WINDOW_MS) {
            ownCommit = false
            return
        }
        // 过期标记直接清除，不拦截本次复制
        ownCommit = false

        // Android 10+ 后台进程读 primaryClip 可能拿到 null（时序/权限边界）：
        // 监听回调触发时系统可能尚未完成写入，或本进程刚退到后台。
        // 延迟 250ms 重试一次，避免把真实复制误判为空。
        if (clipboard.primaryClip == null) {
            // 等待用主线程 Handler，**不能**在 BackgroundIo 里 sleep：那是单线程串行队列，
            // 一睡就把入库、搜索解密、粘贴取正文、词频落盘全部堵住（实测同队列同一线程）。
            retryHandler.postDelayed({
                BackgroundIo.run {
                    val retryClip = clipboard.primaryClip ?: return@run
                    val retryText = retryClip.getItemAt(0).coerceToText(appContext)?.toString() ?: return@run
                    if (retryText.isBlank()) return@run
                    val retrySource = resolveSourcePackage()
                    ClipboardStore.save(appContext, db, retryText, retrySource)
                }
            }, RETRY_DELAY_MS)
            return
        }
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return
        if (text.isBlank()) return

        val sourcePkg = resolveSourcePackage()
        // 复制事件只入队一次线程化保存；upsert 在库层按 content_hash 去重，不会重复写入
        BackgroundIo.run { ClipboardStore.save(appContext, db, text, sourcePkg) }
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

        /** 自身写入剪贴板的标记有效期：粘贴写入后短时间内变化才可能是自身的 */
        const val OWN_COMMIT_WINDOW_MS = 3_000L

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
 * 注意：不做任何内容性质检测，不因内容特征跳过保存、加过期或删除；
 * 删除只来自用户主动删除、去重和容量上限裁剪最旧的非收藏记录。
 */
object ClipboardStore {

    /**
     * 单条上限（UTF-8 **明文字节**）：超过**直接不入库**，避免一条巨文本就把库撑到失控。
     *
     * 注意口径：本条按**明文**字节算，而总量预算（[ClipboardDb.DEFAULT_MAX_TOTAL_BYTES]）
     * 按**库内密文**体积算 —— 两者单位不同，不要互相换算成同一个数。
     * 另：本条只拦**采集**（新复制的内容）；库里既有的超限行不会被它清理，只会被总量预算按最旧非收藏淘汰。
     */
    const val MAX_ITEM_BYTES = 256 * 1024

    /**
     * 一次批量解密窗口的内存预算（字节）。
     *
     * 分页/分块查询会对**整个窗口**逐条解密后才返回，故最坏内存 = 窗口条数 × [MAX_ITEM_BYTES]。
     * 由 [decryptWindowPeakBytes] 与 `ClipboardLimitsTest` 共同守住这个上界。
     */
    const val DECRYPT_WINDOW_BUDGET_BYTES = 16L * 1024 * 1024

    /**
     * 批量解密窗口的最坏内存占用（字节，**纯函数**）。
     * 调用处用「窗口条数 × 单条上限 ≤ [DECRYPT_WINDOW_BUDGET_BYTES]」锁住参数，避免调大窗口时静默抬高内存峰值。
     */
    fun decryptWindowPeakBytes(windowItems: Int, maxItemBytes: Int = MAX_ITEM_BYTES): Long =
        windowItems.toLong() * maxItemBytes.toLong()

    /**
     * 文本的 UTF-8 字节数是否超过 [limit]（**纯函数**，便于单测）。
     *
     * 先按**字符数**快筛：UTF-8 字节数恒 ≥ 字符数，字符数都超了就不必再编码 ——
     * 否则一个 20MB 的串先被复制成 27MB 字节数组，检查本身就成了内存风险。
     */
    fun exceedsItemLimit(text: String, limit: Int = MAX_ITEM_BYTES): Boolean {
        if (limit <= 0) return false
        if (text.length > limit) return true
        return text.toByteArray(Charsets.UTF_8).size > limit
    }

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
        // 单条体积上限：超出即丢弃（不截断——半截内容比不记更糟）
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
