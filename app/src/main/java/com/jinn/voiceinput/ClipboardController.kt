package com.jinn.voiceinput

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 剪贴板控制器（方案第二 + 第四阶段核心）。
 *
 * 职责：
 *  - 监听系统剪贴板变化（[addPrimaryClipChangedListener]）；
 *  - 前台 / 自身变化时捕获内容 → 自动分类 → 加密保存到 [ClipboardDb]；
 *  - 依据 Android 版本约束执行「普通模式」的读取边界（API 29+ 只有前台
 *    或本 IME 为前台输入法时才能读取系统剪贴板；API 33+ 系统会弹出
 *    剪贴板访问提示并可能自动清空，本类不绕过这些系统机制）。
 *
 * 普通模式不做任何系统级拦截（方案第六条）：读取与否完全遵循
 * Android 官方行为，本控制器只管理「本输入法自己的历史数据库」。
 *
 * 线程模型：ClipboardManager 回调在主线程，入库等 IO 操作切后台线程。
 */
class ClipboardController(context: Context) {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val db = ClipboardDb.get(appContext)

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

    /** 停用：注销监听。幂等。 */
    fun stop() {
        if (!listenerRegistered) return
        clipboard.removePrimaryClipChangedListener(listener)
        listenerRegistered = false
        Diagnostics.i(TAG, "stop: 剪贴板监听已注销")
    }

    /** IME 自身提交文本时调用，标记下一次剪贴板变化来自自身，不保存历史 */
    fun onOwnCommit() {
        ownCommit = true
        ownCommitAt = System.currentTimeMillis()
    }

    private fun onClipboardChanged() {
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
            BackgroundIo.run {
                Thread.sleep(250)
                val retryClip = clipboard.primaryClip ?: return@run
                val retryText = retryClip.getItemAt(0).coerceToText(appContext)?.toString() ?: return@run
                if (retryText.isBlank()) return@run
                val retrySource = resolveSourcePackage()
                ClipboardStore.save(appContext, db, retryText, retrySource)
            }
            return
        }
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return
        if (text.isBlank()) return

        val sourcePkg = resolveSourcePackage()
        // 复制事件只入队一次线程化保存；combine sbapsert 在库层去重，不会重复写入
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
    }
}

/**
 * 保存一条剪贴板到历史的纯逻辑（独立出来便于单测）：
 *  1. 自动分类（URL / NUMBER / OTHER；隐私分类绝不自动判断）
 *  2. 加密入库 → 裁剪数量上限
 *
 * 不做任何敏感检测 / 内容性质判断：不因内容特性跳过保存、加过期或删除记录。
 * 删除仅来自用户主动删除、清理重复、容量上限裁剪最旧非收藏记录。
 */
object ClipboardStore {

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
