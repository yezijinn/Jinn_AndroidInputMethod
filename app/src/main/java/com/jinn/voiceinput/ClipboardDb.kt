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
                content_hash TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT 'OTHER',
                is_favorite INTEGER NOT NULL DEFAULT 0,
                is_private INTEGER NOT NULL DEFAULT 0
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
        if (oldVersion < 2) {
            // 新增分类 / 收藏 / 隐私字段（保留已有数据，补默认值）
            db.execSQL("ALTER TABLE $TABLE_ITEMS ADD COLUMN category TEXT NOT NULL DEFAULT 'OTHER'")
            db.execSQL("ALTER TABLE $TABLE_ITEMS ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_ITEMS ADD COLUMN is_private INTEGER NOT NULL DEFAULT 0")
        }
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
        val category: String = "OTHER",   // URL / NUMBER / OTHER
        val isFavorite: Boolean = false,
        val isPrivate: Boolean = false,
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
        category: String = "OTHER",
        isFavorite: Boolean = false,
        isPrivate: Boolean = false,
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
            put("category", category)
            put("is_favorite", if (isFavorite) 1 else 0)
            put("is_private", if (isPrivate) 1 else 0)
        }
        val id = writableDatabase.insert(TABLE_ITEMS, null, values)
        if (id > 0) trimTo(maxItems)
        return id
    }

    /** 删除单条；返回是否真正删除（含过期条目清理共用路径） */
    fun delete(id: Long): Boolean =
        readableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString())) > 0

    /**
     * 删除全部历史：收藏与隐私是受保护条目，清空只删除普通记录。
     * @return 删除的条数
     */
    fun deleteAll(): Int =
        readableDatabase.delete(
            TABLE_ITEMS,
            "is_favorite = 0 AND is_private = 0",
            null,
        )

    /** 更新收藏状态 */
    fun setFavorite(id: Long, favorite: Boolean): Boolean {
        val values = ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) }
        return writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(id.toString())) > 0
    }

    /** 更新隐私标记（仅用户主动操作调用，绝不自动） */
    fun setPrivate(id: Long, isPrivate: Boolean): Boolean {
        val values = ContentValues().apply { put("is_private", if (isPrivate) 1 else 0) }
        return writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(id.toString())) > 0
    }

    /**
     * 清理完全相同的重复记录：只保留每组中最新的一条。
     * 严格字符串比较（不 trim / 不转小写 / 不改换行）。
     *
     * 用户状态合并（bug 审查计划 §23）：同一组重复记录中，收藏 / 隐私标记
     * 以「任一为 true」合并到保留的最新记录上——例如旧记录已收藏而新记录未收藏，
     * 去重后保留的新记录必须仍然带收藏标记，避免用户主动标记被静默丢失。
     *
     * 原子性（§24）：查找 → 合并 → 删除全部在单事务内，失败整体回滚。
     * @return 删除的条数
     */
    fun deduplicate(): Int {
        // 新→旧遍历：首次出现的 hash 为保留组；后续同 hash 为待删除，
        // 并累积该组的收藏/隐私标记（组内任一为 true 则最终为 true）。
        data class Group(val keepId: Long, val keepRowId: Long) {
            var fav = false
            var priv = false
        }
        val keepHash = HashMap<String, Group>()
        val deleteIds = ArrayList<Long>()
        val c = readableDatabase.rawQuery(
            "SELECT id, content_hash, is_favorite, is_private FROM $TABLE_ITEMS ORDER BY created_at DESC",
            null
        )
        c.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val hash = cur.getString(1)
                val fav = cur.getInt(2) != 0
                val priv = cur.getInt(3) != 0
                val group = keepHash[hash]
                if (group == null) {
                    keepHash[hash] = Group(id, 0L).also { it.fav = fav; it.priv = priv }
                } else {
                    // 组内重复：合并用户标记 + 记入待删除
                    if (fav) group.fav = true
                    if (priv) group.priv = true
                    deleteIds.add(id)
                }
            }
        }
        if (deleteIds.isEmpty()) return 0
        writableDatabase.beginTransaction()
        try {
            // 先把合并后的用户标记写回保留记录（组内任一标记 true → 保留记录置 true）
            for (group in keepHash.values) {
                if (group.fav || group.priv) {
                    val values = ContentValues().apply {
                        if (group.fav) put("is_favorite", 1)
                        if (group.priv) put("is_private", 1)
                    }
                    writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(group.keepId.toString()))
                }
            }
            for (id in deleteIds) writableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return deleteIds.size
    }

    /**
     * 裁剪到最多 [maxItems] 条：删除最旧记录。
     * 收藏记录豁免（bug 审查计划 §2）：优先删除非收藏的最旧记录；
     * 若全是收藏记录则不再裁剪（避免静默丢收藏）。
     */
    fun trimTo(maxItems: Int) {
        if (maxItems <= 0) return
        val count = count()
        if (count <= maxItems) return
        val overflow = count - maxItems
        // 优先删非收藏最旧记录；收藏永远保留
        val ids = readableDatabase.rawQuery(
            "SELECT id FROM $TABLE_ITEMS WHERE is_favorite = 0 " +
                "ORDER BY created_at ASC LIMIT $overflow",
            null
        ).use { c ->
            val list = ArrayList<Long>(overflow)
            while (c.moveToNext()) list.add(c.getLong(0))
            list
        }
        if (ids.isEmpty()) return // 全部是收藏，不裁剪
        writableDatabase.beginTransaction()
        try {
            for (id in ids) writableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * 清理所有已过期的条目（敏感内容临时保存到期调用）。
     * 收藏记录豁免：用户主动收藏 = 永久存储，即使原为敏感临时保存也不到期删除。
     */
    fun deleteExpired(): Int {
        val now = System.currentTimeMillis()
        return readableDatabase.delete(
            TABLE_ITEMS,
            "expire_at > 0 AND expire_at < ? AND is_favorite = 0",
            arrayOf(now.toString())
        )
    }

    // ── 查询 ──────────────────────────────────────────────

    /** 按 id 读取单条（解密）。不存在或解密失败返回 null。 */
    fun get(id: Long): Item? {
        val c = readableDatabase.rawQuery(
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash, category, is_favorite, is_private " +
                "FROM $TABLE_ITEMS WHERE id = ?",
            arrayOf(id.toString())
        )
        return c.use { cur ->
            if (!cur.moveToFirst()) null else readItem(cur)
        }
    }

    /** 读取最近 [limit] 条（新→旧）。逐条解密，解密失败跳过。 */
    fun recent(limit: Int, category: String? = null): List<Item> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Item>(limit)
        val sql = if (category != null) {
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash, category, is_favorite, is_private " +
                "FROM $TABLE_ITEMS WHERE category = ? ORDER BY created_at DESC LIMIT $limit"
        } else {
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash, category, is_favorite, is_private " +
                "FROM $TABLE_ITEMS ORDER BY created_at DESC LIMIT $limit"
        }
        val args = if (category != null) arrayOf(category) else null
        val c = readableDatabase.rawQuery(sql, args)
        c.use { cur ->
            while (cur.moveToNext()) {
                readItem(cur)?.let { out.add(it) }
            }
        }
        return out
    }

    /** 读取最近 [limit] 条中收藏的记录（新→旧） */
    fun recentFavorites(limit: Int): List<Item> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Item>(limit)
        val c = readableDatabase.rawQuery(
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash, category, is_favorite, is_private " +
                "FROM $TABLE_ITEMS WHERE is_favorite = 1 ORDER BY created_at DESC LIMIT $limit",
            null
        )
        c.use { cur ->
            while (cur.moveToNext()) {
                readItem(cur)?.let { out.add(it) }
            }
        }
        return out
    }

    /** 读取最近 [limit] 条中标记为隐私的记录（新→旧） */
    fun recentPrivate(limit: Int): List<Item> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Item>(limit)
        val c = readableDatabase.rawQuery(
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash, category, is_favorite, is_private " +
                "FROM $TABLE_ITEMS WHERE is_private = 1 ORDER BY created_at DESC LIMIT $limit",
            null
        )
        c.use { cur ->
            while (cur.moveToNext()) {
                readItem(cur)?.let { out.add(it) }
            }
        }
        return out
    }

    /**
     * 搜索：遍历全部条目按需解密，过滤包含 [query] 的（大小写不敏感、任意位置匹配）。
     * 支持分类过滤（category 非 null 时只在该分类内搜）。
     * 返回保留原始排序（新→旧），调用方需自行映射原始序号。
     */
    fun search(query: String, category: String? = null, limit: Int = 9999): List<Item> {
        val out = ArrayList<Item>()
        val q = query.lowercase()
        val c = readableDatabase.rawQuery(
            "SELECT id, encrypted_content, content_type, created_at, source_package, " +
                "source_app_name, is_sensitive, expire_at, content_hash, category, is_favorite, is_private " +
                "FROM $TABLE_ITEMS ORDER BY created_at DESC",
            null
        )
        c.use { cur ->
            while (cur.moveToNext()) {
                val item = readItem(cur) ?: continue
                if (category != null && item.category != category) continue
                if (item.content.lowercase().contains(q)) out.add(item)
                if (out.size >= limit) break
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
            category = c.getString(9),
            isFavorite = c.getInt(10) != 0,
            isPrivate = c.getInt(11) != 0,
        )
    }

    companion object {
        private const val DB_NAME = "jinn_clipboard.db"
        private const val DB_VERSION = 2
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

        /**
         * 去重用户状态合并规则（纯函数，可单测；bug 审查计划 §23）：
         * 同一内容重复组内，收藏 / 隐私以「任一为 true」合并到保留记录。
         * @param kept 保留（最新）记录的用户标记
         * @param dupes 待删除旧记录的标记序列（[favorite, private]）
         */
        fun mergeDedupeFlags(
            kept: Pair<Boolean, Boolean>,
            dupes: List<Pair<Boolean, Boolean>>,
        ): Pair<Boolean, Boolean> {
            var fav = kept.first
            var priv = kept.second
            for ((f, p) in dupes) {
                if (f) fav = true
                if (p) priv = true
            }
            return fav to priv
        }
    }
}
