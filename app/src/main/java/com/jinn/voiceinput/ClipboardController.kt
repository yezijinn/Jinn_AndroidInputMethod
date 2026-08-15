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
 *  - 前台 / 自身变化时捕获内容 → 敏感检测 → 按策略保存到 [ClipboardDb]；
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
    }

    private fun onClipboardChanged() {
        if (ownCommit) {
            ownCommit = false
            return
        }
        // 立即置位，避免自身提交产生的剪贴板变化被后续线程误判
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return
        if (text.isBlank()) return

        val sourcePkg = resolveSourcePackage()
        Thread {
            ClipboardStore.save(appContext, db, text, sourcePkg)
        }.start()
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
    }
}

/**
 * 保存一条剪贴板到历史的纯逻辑（独立出来便于单测）：
 *  1. 敏感检测 → 按策略处理（不保存 / 临时保存 / 正常保存）
 *  2. 加密入库 → 裁剪数量上限 → 清理过期
 */
object ClipboardStore {

    /** 敏感内容保存策略（对齐方案第十五节） */
    enum class SensitivePolicy(val value: Int) {
        NEVER_SAVE(0),       // 不保存（默认）
        TEMPORARY(1),        // 临时保存（30s / 5min / 30min / 1h）
        SAVE_NORMALLY(2);    // 按普通内容保存

        companion object {
            fun from(v: Int): SensitivePolicy = entries.firstOrNull { it.value == v } ?: NEVER_SAVE
        }
    }

    /** 敏感临时保存时长（毫秒） */
    enum class TempDuration(val millis: Long) {
        S30(30_000L), M5(5 * 60_000L), M30(30 * 60_000L), H1(60 * 60_000L);

        companion object {
            fun from(v: Int): TempDuration = entries.getOrNull(v) ?: S30
        }
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
        policy: SensitivePolicy = readPolicy(context),
        tempDuration: TempDuration = readTempDuration(context),
        maxItems: Int = readMaxItems(context),
    ): Long? {
        val match = SensitiveDetector.detect(text)
        val sensitive = match != null
        val appName = sourceAppName.ifBlank { guessAppName(context, sourcePackage) }

        // Root 增强模式联动：敏感内容命中且开启了「自动清空系统剪贴板」时，
        // 用 root 立即清空系统剪贴板（普通模式做不到，root 模式才做）。
        if (sensitive) {
            val prefs = ClipboardPrefs.of(context)
            if (prefs.rootEnhanceEnabled && prefs.rootClearOnSensitive) {
                ClipboardFirewall.onSensitiveClipboardDetected(context)
            }
        }

        when {
            sensitive && policy == SensitivePolicy.NEVER_SAVE -> {
                Diagnostics.i(TAG, "save: 敏感内容(${match?.type})，策略=不保存，跳过")
                return null
            }
            sensitive && policy == SensitivePolicy.TEMPORARY -> {
                val expireAt = System.currentTimeMillis() + tempDuration.millis
                Diagnostics.i(TAG, "save: 敏感内容(${match?.type})，临时保存 ${tempDuration.millis / 1000}s")
                return db.insert(text, "text", sourcePackage, appName, true, expireAt, maxItems)
            }
            else -> {
                Diagnostics.i(TAG, "save: 已保存 ${text.take(20)}… (敏感=$sensitive)")
                return db.insert(text, "text", sourcePackage, appName, sensitive, 0L, maxItems)
            }
        }
    }

    /** 从数据库清理已过期条目（供定时器/启动时调用） */
    fun cleanupExpired(db: ClipboardDb) {
        val removed = db.deleteExpired()
        if (removed > 0) Diagnostics.i(TAG, "cleanupExpired: 清理 $removed 条过期历史")
    }

    private fun readPolicy(context: Context): SensitivePolicy =
        SensitivePolicy.from(ClipboardPrefs.of(context).sensitivePolicy)

    private fun readTempDuration(context: Context): TempDuration =
        TempDuration.from(ClipboardPrefs.of(context).sensitiveTempSeconds)

    private fun readMaxItems(context: Context): Int =
        ClipboardPrefs.of(context).maxItems

    private fun guessAppName(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    }.getOrElse { pkg.substringAfterLast('.') }

    private const val TAG = "ClipboardStore"
}
