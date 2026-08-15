package com.jinn.voiceinput

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 第三方 APP 剪贴板历史访问权限（方案第九 ~ 十二节）。
 *
 * 独立数据库存储授权表：
 *  - 默认所有第三方 APP 禁止读取本输入法历史（方案第九节）；
 *  - 用户按 packageName 授权（不能只按应用名）；
 *  - 授权 APP 每次最多读取最新 3 条（限制在 IPC/API 层强制执行，见
 *    [ClipboardHistoryProvider]，不依赖 UI 层）；
 *  - 记录临时授权过期时间（本次允许 / 5min / 30min / 1h / 永久）。
 *
 * 线程模型：Provider 回调可能在 Binder 线程，本类操作加锁保证串行。
 */
class ClipboardPermissionStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PERM (
                package_name TEXT PRIMARY KEY,
                app_name TEXT NOT NULL DEFAULT '',
                read_allowed INTEGER NOT NULL DEFAULT 0,
                listener_allowed INTEGER NOT NULL DEFAULT 0,
                write_allowed INTEGER NOT NULL DEFAULT 1,
                max_history_items INTEGER NOT NULL DEFAULT 3,
                temporary_expire_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PERM")
        onCreate(db)
    }

    data class Permission(
        val packageName: String,
        val appName: String,
        val readAllowed: Boolean,
        val listenerAllowed: Boolean,
        val writeAllowed: Boolean,
        val maxHistoryItems: Int,
        val temporaryExpireAt: Long, // 0 = 永久授权
    ) {
        /** 临时授权是否仍有效（已过期视为未授权） */
        val isCurrentlyAllowed: Boolean
            get() = readAllowed && (temporaryExpireAt == 0L || temporaryExpireAt > System.currentTimeMillis())
    }

    @Synchronized
    fun setPermission(perm: Permission) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("package_name", perm.packageName)
            put("app_name", perm.appName)
            put("read_allowed", if (perm.readAllowed) 1 else 0)
            put("listener_allowed", if (perm.listenerAllowed) 1 else 0)
            put("write_allowed", if (perm.writeAllowed) 1 else 0)
            put("max_history_items", perm.maxHistoryItems)
            put("temporary_expire_at", perm.temporaryExpireAt)
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict(TABLE_PERM, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun getPermission(packageName: String): Permission? {
        val c = readableDatabase.rawQuery(
            "SELECT package_name, app_name, read_allowed, listener_allowed, write_allowed, " +
                "max_history_items, temporary_expire_at FROM $TABLE_PERM WHERE package_name = ?",
            arrayOf(packageName)
        )
        return c.use { cur ->
            if (!cur.moveToFirst()) null
            else Permission(
                packageName = cur.getString(0),
                appName = cur.getString(1),
                readAllowed = cur.getInt(2) != 0,
                listenerAllowed = cur.getInt(3) != 0,
                writeAllowed = cur.getInt(4) != 0,
                maxHistoryItems = cur.getInt(5).coerceIn(0, 3),
                temporaryExpireAt = cur.getLong(6),
            )
        }
    }

    /** 判断某 APP 当前是否有权读取历史（默认未授权） */
    fun canRead(packageName: String): Boolean = getPermission(packageName)?.isCurrentlyAllowed == true

    @Synchronized
    fun listAll(): List<Permission> {
        val out = ArrayList<Permission>()
        val c = readableDatabase.rawQuery(
            "SELECT package_name, app_name, read_allowed, listener_allowed, write_allowed, " +
                "max_history_items, temporary_expire_at FROM $TABLE_PERM ORDER BY updated_at DESC",
            null
        )
        c.use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    Permission(
                        packageName = cur.getString(0),
                        appName = cur.getString(1),
                        readAllowed = cur.getInt(2) != 0,
                        listenerAllowed = cur.getInt(3) != 0,
                        writeAllowed = cur.getInt(4) != 0,
                        maxHistoryItems = cur.getInt(5).coerceIn(0, 3),
                        temporaryExpireAt = cur.getLong(6),
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun delete(packageName: String) {
        writableDatabase.delete(TABLE_PERM, "package_name = ?", arrayOf(packageName))
    }

    companion object {
        private const val DB_NAME = "jinn_clipboard_perm.db"
        private const val DB_VERSION = 1
        private const val TABLE_PERM = "clipboard_permissions"

        /** 第三方每次最多可读取的历史条数（方案第十节，IPC 层强制） */
        const val MAX_ITEMS_THIRD_PARTY = 3

        @Volatile
        private var instance: ClipboardPermissionStore? = null

        fun get(context: Context): ClipboardPermissionStore =
            instance ?: synchronized(this) {
                instance ?: ClipboardPermissionStore(context).also { instance = it }
            }
    }
}
