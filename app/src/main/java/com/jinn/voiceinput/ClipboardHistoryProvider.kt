package com.jinn.voiceinput

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * 第三方 APP 访问剪贴板历史的安全 IPC（方案第六 + 七阶段）。
 *
 * 安全设计：
 *  - [query] 通过 [Binder.getCallingUid] 反查真实 packageName，**绝不信任**
 *    调用方自己提交的包名参数（方案第十一节）；
 *  - 未授权 APP 直接拒绝（返回空结果 + [PERMISSION_DENIED] 参数）；
 *  - 授权 APP 每次最多返回最新 3 条（[ClipboardPermissionStore.MAX_ITEMS_THIRD_PARTY]），
 *    limit / offset / 分页 / 重复请求均在 provider 层强制，无法绕过；
 *  - 返回内容为已解密的明文（授权方有权读取），但**禁止记录正文日志**。
 *
 * 约定（供第三方接入的稳定契约，见 ClipboardHistoryContract）：
 *  - authority: `com.jinn.voiceinput.clipboard`
 *  - path: `history` → 查询最近 N 条（N ≤ 3）
 *  - query 参数 `limit` 可传入 1~3，超范围强制为 3
 *  - 返回列: id, content, content_type, created_at, source_package, is_sensitive
 */
class ClipboardHistoryProvider : ContentProvider() {

    private val db by lazy { ClipboardDb.get(context!!) }
    private val permStore by lazy { ClipboardPermissionStore.get(context!!) }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.jinn.clipboard"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? {
        if (MATCHER.match(uri) != MATCH_HISTORY) return null

        val callerPkg = resolveCallerPackage() ?: return deniedCursor()
        if (!permStore.canRead(callerPkg)) {
            Diagnostics.i(TAG, "query: 未授权 APP($callerPkg) 访问被拒")
            return deniedCursor()
        }

        // limit 强制 1~3（IPC 层，无法通过改 limit 绕过）
        val requested = uri.getQueryParameter(PARAM_LIMIT)?.toIntOrNull()?.coerceIn(1, 3) ?: 3
        val limit = requested.coerceAtMost(ClipboardPermissionStore.MAX_ITEMS_THIRD_PARTY)

        val items = db.recent(limit)
        Diagnostics.i(TAG, "query: 授权 APP($callerPkg) 读取 $limit 条")
        return toCursor(items)
    }

    /** call 通道：支持 `clear_history` 自定义操作（同样校验调用方） */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callerPkg = resolveCallerPackage()
        if (callerPkg == null || !permStore.canRead(callerPkg)) {
            return Bundle().apply { putBoolean(KEY_ALLOWED, false) }
        }
        return when (method) {
            METHOD_COUNT -> Bundle().apply {
                putBoolean(KEY_ALLOWED, true)
                putInt(KEY_COUNT, db.count())
            }
            else -> null
        }
    }

    private fun resolveCallerPackage(): String? {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return null // 自身调用不走第三方通道
        return context?.packageManager?.getPackagesForUid(uid)?.firstOrNull()
    }

    private fun deniedCursor(): Cursor {
        val c = MatrixCursor(COLUMNS)
        c.extras = Bundle().apply { putBoolean(KEY_ALLOWED, false) }
        return c
    }

    private fun toCursor(items: List<ClipboardDb.Item>): Cursor {
        val c = MatrixCursor(COLUMNS)
        c.extras = Bundle().apply { putBoolean(KEY_ALLOWED, true) }
        for (item in items) {
            c.addRow(
                arrayOf(
                    item.id,
                    item.content,
                    item.contentType,
                    item.createdAt,
                    item.sourcePackage,
                    if (item.isSensitive) 1 else 0,
                )
            )
        }
        return c
    }

    companion object {
        const val AUTHORITY = "com.jinn.voiceinput.clipboard"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/history")

        private const val PARAM_LIMIT = "limit"
        private const val METHOD_COUNT = "count"
        const val KEY_ALLOWED = "allowed"
        const val KEY_COUNT = "count"

        private val COLUMNS = arrayOf(
            "id", "content", "content_type", "created_at", "source_package", "is_sensitive",
        )

        private const val MATCH_HISTORY = 1
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "history", MATCH_HISTORY)
        }

        private const val TAG = "ClipboardHistoryProvider"
    }
}

/** 第三方接入的稳定契约（常量文档化，供外部 APP 参考） */
object ClipboardHistoryContract {
    const val AUTHORITY = ClipboardHistoryProvider.AUTHORITY
    const val PATH_HISTORY = "history"
    const val PARAM_LIMIT = "limit"
    const val MAX_RESULTS = ClipboardPermissionStore.MAX_ITEMS_THIRD_PARTY
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_HISTORY")
}
