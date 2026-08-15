package com.jinn.voiceinput

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 剪贴板历史数据库（方案第二 + 第三阶段）。
 *
 * 存储策略：
 *  - 正文以 AES-256-GCM 密文入库（[ClipboardCrypto]），绝不落明文；
 *  - 来源 APP、时间、敏感标记、过期时间等元数据明文存储（不含隐私内容）；
 *  - 超出数量上限时删除最旧记录，数据库 + 内存缓存同步清理；
 *  - 全部操作走单例 + 后台线程，避免主线程 IO 与并发写冲突。
 *
 * 搜索：按需解密（查询所有条目 → 逐条解密过滤），不建明文全文索引，
 * 数据量 ≤9999 条时性能可接受，安全性优先（方案第十九阶段取舍）。
 */
class ClipboardDb private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ITEMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                encrypted_content TEXT NOT NULL,
                content_type TEXT NOT NULL DEFAULT 'text',
                created_at INTEGER NOT NULL,
                source_package TEXT NOT NULL DEFAULT '',
                source_app_name TEXT NOT NULL DEFAULT '',
                is_sensitive INTEGER NOT NULL DEFAULT 0,
                expire_at INTEGER NOT NULL DEFAULT 0,
                content_hash TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX idx_items_created ON $TABLE_ITEMS(created_at DESC)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ITEMS")
        onCreate(db)
    }

    /** 剪贴板历史条目（明文仅在读取时存在，不长期驻留） */
    data class Item(
        val id: Long,
        val content: String,      // 已解密明文（内存中）
        val contentType: String,
        val createdAt: Long,
        val sourcePackage: String,
        val sourceAppName: String,
        val isSensitive: Boolean,
        val expireAt: Long,       // 0 = 不自动过期
        val contentHash: String,
    )

    // ── 写入 ──────────────────────────────────────────────

    /** 新增一条历史。先加密，再插入；超上限时裁剪最旧记录。返回新条目 id，失败返回 -1。 */
    fun insert(
        content: String,
        contentType: String = "text",
        sourcePackage: String,
        sourceAppName: String,
        isSensitive: Boolean,
        expireAt: Long = 0L,
        maxItems: Int,
    ): Long {
        if (content.isBlank()) return -1
        val encrypted = ClipboardCrypto.encrypt(content) ?: return -1
        val values = ContentValues().apply {
            put("encrypted_content", encrypted)
            put("content_type", contentType)
            put("created_at", System.currentTimeMillis())
            put("source_package", sourcePackage)
            put("source_app_name", sourceAppName)
            put("is_sensitive", if (isSensitive) 1 else 0)
            put("expire_at", expireAt)
            put("content_hash", stableHash(content))
        }
        val id = writableDatabase.insert(TABLE_ITEMS, null, values)
        if (id > 0) trimTo(maxItems)
        return id
    }

    /** 删除单条；返回是否真正删除（含过期条目清理共用路径） */
    fun delete(id: Long): Boolean =
        readableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString())) > 0

    /** 删除全部历史；返回删除条数 */
    fun deleteAll(): Int = readableDatabase.delete(TABLE_ITEMS, null, null)

    /** 裁剪到最多 [maxItems] 条：删除最旧记录 */
    fun trimTo(maxItems: Int) {
        if (maxItems <= 0) return
        val count = count()
        if (count <= maxItems) return
        val overflow = count - maxItems
        // 保留最新的 maxItems 条，删除其余（按 created_at 升序删除最旧）
        val ids = readableDatabase.rawQuery(
            "SELECT id FROM $TABLE_ITEMS ORDER BY created_at ASC LIMIT $overflow", null
        ).use { c ->
            val list = ArrayList<Long>(overflow)
            while (c.moveToNext()) list.add(c.getLong(0))
            list
        }
        writableDatabase.beginTransaction()
        try {
            for (id in ids) writableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** 清理所有已过期的条目（敏感内容临时保存到期调用） */
    fun deleteExpired(): Int {
        val now = System.currentTimeMillis()
        return readableDatabase.delete(TABLE_ITEMS, "expire_at > 0 AND expire_at < ?", arrayOf(now.toString()))
    }

    // ── 查询 ──────────────────────────────────────────────

    /** 按 id 读取单条（解密）。不存在或解密失败返回 null。 */
    fun get(id: Long): Item? {
        val c = readableDatabase.rawQuery(
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash FROM $TABLE_ITEMS WHERE id = ?",
            arrayOf(id.toString())
        )
        return c.use { cur ->
            if (!cur.moveToFirst()) null else readItem(cur)
        }
    }

    /** 读取最近 [limit] 条（新→旧）。逐条解密，解密失败跳过。 */
    fun recent(limit: Int): List<Item> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Item>(limit)
        val c = readableDatabase.rawQuery(
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash FROM $TABLE_ITEMS " +
                "ORDER BY created_at DESC LIMIT $limit",
            null
        )
        c.use { cur ->
            while (cur.moveToNext()) {
                readItem(cur)?.let { out.add(it) }
            }
        }
        return out
    }

    /** 搜索：遍历全部条目按需解密，过滤包含所有 [keywords] 的（大小写不敏感） */
    fun search(keywords: List<String>): List<Item> {
        val out = ArrayList<Item>()
        val c = readableDatabase.rawQuery(
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash FROM $TABLE_ITEMS " +
                "ORDER BY created_at DESC",
            null
        )
        c.use { cur ->
            while (cur.moveToNext()) {
                val item = readItem(cur) ?: continue
                val lower = item.content.lowercase()
                if (keywords.all { k -> lower.contains(k.lowercase()) }) out.add(item)
            }
        }
        return out
    }

    /** 总条数 */
    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_ITEMS", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // ── 内部 ──────────────────────────────────────────────

    private fun readItem(c: android.database.Cursor): Item? {
        val id = c.getLong(0)
        val encrypted = c.getString(1)
        val content = ClipboardCrypto.decrypt(encrypted) ?: run {
            Diagnostics.w(TAG, "解密失败，跳过 id=$id")
            return null
        }
        return Item(
            id = id,
            content = content,
            contentType = c.getString(2),
            createdAt = c.getLong(3),
            sourcePackage = c.getString(4),
            sourceAppName = c.getString(5),
            isSensitive = c.getInt(6) != 0,
            expireAt = c.getLong(7),
            contentHash = c.getString(8),
        )
    }

    companion object {
        private const val DB_NAME = "jinn_clipboard.db"
        private const val DB_VERSION = 1
        private const val TABLE_ITEMS = "clipboard_items"
        private const val TAG = "ClipboardDb"

        @Volatile
        private var instance: ClipboardDb? = null

        fun get(context: Context): ClipboardDb =
            instance ?: synchronized(this) {
                instance ?: ClipboardDb(context).also { instance = it }
            }

        /** 稳定哈希：内容去重与来源追踪用（不暴露原文） */
        fun stableHash(text: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
