package com.jinn.inputmethod

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 剪贴板历史数据库。
 *
 * 存储策略：
 *  - 正文以 AES-256-GCM 密文入库（[ClipboardCrypto]），绝不落明文；
 *  - 来源 APP、时间、分类、收藏、隐私标记等元数据明文存储；
 *  - 超出数量上限时删除最旧记录（**收藏永不删；隐私最后才删**），
 *    数据库 + 内存缓存同步清理；
 *  - 全部操作走单例 + 后台线程，避免主线程 IO 与并发写冲突。
 *
 * 删除来源仅限：用户主动删除 / 清理重复 / 容量限制裁剪。
 * 裁剪优先级见 [TRIM_PRIORITY]：非收藏非隐私 → 非收藏隐私 → 收藏永不删。
 * 不因内容性质（敏感与否）做任何判断或删除（v3 起已移除敏感字段）。
 *
 * 搜索：按需解密（查询所有条目 → 逐条解密过滤），不建明文全文索引，
 * 数据量 ≤9999 条时性能可接受，安全性优先。
 */
class ClipboardDb private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(createTableSql())
        db.execSQL("CREATE INDEX idx_items_created ON $TABLE_ITEMS(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            // v3：移除敏感内容功能。重建表去掉 is_sensitive / expire_at 列，
            // 既有记录全部迁移为长期保留（不再存在按内容性质的过期删除），
            // id 与收藏/隐私标记原样保留。
            db.execSQL("ALTER TABLE $TABLE_ITEMS RENAME TO ${TABLE_ITEMS}_old")
            db.execSQL(createTableSql())
            db.execSQL(
                "INSERT INTO $TABLE_ITEMS (id, encrypted_content, content_type, created_at, " +
                    "source_package, source_app_name, content_hash, category, is_favorite, is_private) " +
                    "SELECT id, encrypted_content, content_type, created_at, " +
                    "source_package, source_app_name, content_hash, category, is_favorite, is_private " +
                    "FROM ${TABLE_ITEMS}_old"
            )
            db.execSQL("DROP TABLE ${TABLE_ITEMS}_old")
            // 修正自增序列：显式插入 id 后 sqlite_sequence 不自动更新，避免新插入 id 冲突
            db.execSQL(
                "UPDATE sqlite_sequence SET seq = (SELECT MAX(id) FROM $TABLE_ITEMS) " +
                    "WHERE name = '$TABLE_ITEMS'"
            )
            db.execSQL("CREATE INDEX idx_items_created ON $TABLE_ITEMS(created_at DESC)")
        }
        if (oldVersion < 4) {
            // v4：入库阶段去重。先清理历史遗留的重复行（保留每组最新 + 合并收藏/隐私标记），
            // 再建立 content_hash 唯一索引，后续写入由 upsert 在入库时去重，
            // 不再依赖「打开面板时全表 deduplicate」维持数据正确性。
            mergeDuplicates(db)
            db.execSQL("CREATE UNIQUE INDEX idx_items_hash ON $TABLE_ITEMS(content_hash)")
        }
    }

    /** 合并重复行：每组 content_hash 保留最新一条，收藏/隐私标记以「任一为 true」合并到保留行 */
    private fun mergeDuplicates(db: SQLiteDatabase) {
        val keepIds = HashMap<String, Long>()
        val favIds = HashMap<String, Boolean>()
        val privIds = HashMap<String, Boolean>()
        val dupIds = ArrayList<Long>()
        db.rawQuery(
            "SELECT id, content_hash, is_favorite, is_private FROM $TABLE_ITEMS ORDER BY created_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val hash = c.getString(1)
                val fav = c.getInt(2) != 0
                val priv = c.getInt(3) != 0
                if (!keepIds.containsKey(hash)) {
                    keepIds[hash] = id
                    favIds[hash] = fav
                    privIds[hash] = priv
                } else {
                    if (fav) favIds[hash] = true
                    if (priv) privIds[hash] = true
                    dupIds.add(id)
                }
            }
        }
        if (dupIds.isEmpty()) return
        db.beginTransaction()
        try {
            for ((hash, keepId) in keepIds) {
                val fav = favIds[hash] == true
                val priv = privIds[hash] == true
                if (fav || priv) {
                    val values = ContentValues().apply {
                        if (fav) put("is_favorite", 1)
                        if (priv) put("is_private", 1)
                    }
                    db.update(TABLE_ITEMS, values, "id = ?", arrayOf(keepId.toString()))
                }
            }
            for (id in dupIds) db.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createTableSql(): String =
        """
        CREATE TABLE $TABLE_ITEMS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            encrypted_content TEXT NOT NULL,
            content_type TEXT NOT NULL DEFAULT 'text',
            created_at INTEGER NOT NULL,
            source_package TEXT NOT NULL DEFAULT '',
            source_app_name TEXT NOT NULL DEFAULT '',
            content_hash TEXT NOT NULL DEFAULT '',
            category TEXT NOT NULL DEFAULT 'OTHER',
            is_favorite INTEGER NOT NULL DEFAULT 0,
            is_private INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent()

    /** 剪贴板历史条目（明文仅在读取时存在，不长期驻留） */
    data class Item(
        val id: Long,
        val content: String,      // 已解密明文（内存中）
        val contentType: String,
        val createdAt: Long,
        val sourcePackage: String,
        val sourceAppName: String,
        val contentHash: String,
        val category: String = "OTHER",   // URL / NUMBER / OTHER
        val isFavorite: Boolean = false,
        val isPrivate: Boolean = false,
    )

    // ── 写入 ──────────────────────────────────────────────

    /**
     * 入库去重写入（原子，@Synchronized 串行）：同一内容（content_hash 相同）已存在时
     * 只更新必要元数据并重新置顶（created_at=now），绝不产生重复记录；
     * 收藏/隐私标记是用户主动状态，重复复制时**不覆盖**。
     * 不存在则插入，超上限裁剪最旧非收藏。返回条目 id，失败返回 -1。
     */
    @Synchronized
    fun upsert(
        content: String,
        contentType: String = "text",
        sourcePackage: String,
        sourceAppName: String,
        maxItems: Int,
        category: String = "OTHER",
        isFavorite: Boolean = false,
        isPrivate: Boolean = false,
    ): Long {
        if (content.isBlank()) return -1
        val encrypted = ClipboardCrypto.encrypt(content) ?: return -1
        val hash = stableHash(content)
        val existingId = findIdByHash(hash)
        if (existingId != null) {
            // 已存在：只更新必要元数据 + 置顶，保留收藏/隐私标记
            val values = ContentValues().apply {
                put("content_type", contentType)
                put("created_at", System.currentTimeMillis())
                put("source_package", sourcePackage)
                put("source_app_name", sourceAppName)
                put("category", category)
            }
            writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(existingId.toString()))
            return existingId
        }
        val values = ContentValues().apply {
            put("encrypted_content", encrypted)
            put("content_type", contentType)
            put("created_at", System.currentTimeMillis())
            put("source_package", sourcePackage)
            put("source_app_name", sourceAppName)
            put("content_hash", hash)
            put("category", category)
            put("is_favorite", if (isFavorite) 1 else 0)
            put("is_private", if (isPrivate) 1 else 0)
        }
        val id = writableDatabase.insert(TABLE_ITEMS, null, values)
        if (id > 0) trimTo(maxItems)
        return id
    }

    /** 按内容哈希查已存在的记录 id（入库去重用） */
    private fun findIdByHash(hash: String): Long? =
        readableDatabase.rawQuery(
            "SELECT id FROM $TABLE_ITEMS WHERE content_hash = ? LIMIT 1",
            arrayOf(hash)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /**
     * 删除单条；返回是否真正删除。
     * 用可写库：getReadableDatabase 在磁盘满等异常场景会退回只读句柄，删除会静默失败。
     */
    fun delete(id: Long): Boolean =
        writableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString())) > 0

    /**
     * 删除全部历史：收藏与隐私是受保护条目，清空只删除普通记录。
     * @return 删除的条数
     */
    fun deleteAll(): Int =
        writableDatabase.delete(
            TABLE_ITEMS,
            "is_favorite = 0 AND is_private = 0",
            null,
        )

    /** 更新收藏状态 */
    fun setFavorite(id: Long, favorite: Boolean): Boolean {
        val values = ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) }
        return writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(id.toString())) > 0
    }

    /**
     * 更新隐私标记。**隐私只能由用户主动标记**，数据层不提供任何自动判定入口。
     * 标记后该条：在列表中默认隐藏明文、被 [deleteAll] 豁免、可被「隐私」分类筛出。
     */
    fun setPrivate(id: Long, privateFlag: Boolean): Boolean {
        val values = ContentValues().apply { put("is_private", if (privateFlag) 1 else 0) }
        return writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(id.toString())) > 0
    }

    /**
     * 清理完全相同的重复记录：只保留每组中最新的一条。
     * 严格字符串比较（不 trim / 不转小写 / 不改换行）。
     *
     * 用户状态合并：同一组重复记录中，收藏 / 隐私标记以「任一为 true」合并
     * 到保留的最新记录上，避免用户主动标记被静默丢失。
     *
     * 原子性：查找 → 合并 → 删除全部在单事务内，失败整体回滚。
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
     * 裁剪到最多 [maxItems] 条。
     *
     * **裁剪优先级**（按顺序取证，凑够 overflow 即停）：
     *   ① 非收藏且非隐私的最旧记录
     *   ② 非收藏的**隐私**记录 —— 隐私是用户刻意主动标记的，语义上比普通记录
     *      更该保留，因此排到最后才动
     *   ③ **收藏永不删除**
     *
     * 若按 ①② 仍凑不够 overflow（剩余全是收藏），则不再裁剪 ——
     * 宁可超出上限，也不删用户明确标记保留的内容。
     */
    fun trimTo(maxItems: Int) {
        if (maxItems <= 0) return
        val count = count()
        if (count <= maxItems) return
        val overflow = count - maxItems

        // 按优先级分轮取证：先最不敏感的，隐私留到最后
        val ids = ArrayList<Long>(overflow)
        for (where in TRIM_PRIORITY) {
            if (ids.size >= overflow) break
            val need = overflow - ids.size
            readableDatabase.rawQuery(
                "SELECT id FROM $TABLE_ITEMS WHERE $where " +
                    "ORDER BY created_at ASC LIMIT $need",
                null
            ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        }
        if (ids.isEmpty()) return // 剩余全是收藏，不裁剪
        writableDatabase.beginTransaction()
        try {
            for (id in ids) writableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // ── 查询 ──────────────────────────────────────────────

    /** SELECT 列清单（分页查询与历史接口共用） */
    private val selectCols = "id, encrypted_content, content_type, created_at, source_package, " +
        "source_app_name, content_hash, category, is_favorite, is_private"

    /**
     * 组装过滤条件（分类 / 仅收藏 / 仅隐私），返回 WHERE 片段与参数。
     *
     * 收藏与隐私是**独立标签**，可与分类并存：
     *  - [favoritesOnly] / [privateOnly] 为 true 时按对应标记列过滤；
     *  - [category] 为 URL / NUMBER / OTHER 按分类列过滤（FAVORITE、PRIVATE 是
     *    上层的伪分类，调用方需自行转成对应标记后传 null）。
     */
    private fun whereClause(
        category: String?,
        favoritesOnly: Boolean = false,
        privateOnly: Boolean = false,
    ): Pair<String, Array<String>> {
        val where = StringBuilder("1 = 1")
        val args = ArrayList<String>(1)
        if (favoritesOnly) where.append(" AND is_favorite = 1")
        if (privateOnly) where.append(" AND is_private = 1")
        if (category != null) {
            where.append(" AND category = ?")
            args.add(category)
        }
        return where.toString() to args.toTypedArray()
    }

    /**
     * 分页读取记录（新→旧），只解密本页 [limit] 条。
     * 大容量历史（数千条）的列表加载路径：解密只覆盖可见窗口。
     */
    fun recentPage(
        offset: Int,
        limit: Int,
        category: String? = null,
        favoritesOnly: Boolean = false,
        privateOnly: Boolean = false,
    ): List<Item> {
        if (limit <= 0 || offset < 0) return emptyList()
        val (where, args) = whereClause(category, favoritesOnly, privateOnly)
        val c = readableDatabase.rawQuery(
            "SELECT $selectCols FROM $TABLE_ITEMS WHERE $where " +
                "ORDER BY created_at DESC LIMIT $limit OFFSET $offset",
            args
        )
        c.use { cur ->
            val out = ArrayList<Item>(limit)
            while (cur.moveToNext()) readItem(cur)?.let { out.add(it) }
            return out
        }
    }

    /** 记录总数（纯 SQL 计数，不解密；可按分类/收藏/隐私过滤） */
    fun count(
        category: String? = null,
        favoritesOnly: Boolean = false,
        privateOnly: Boolean = false,
    ): Int {
        val (where, args) = whereClause(category, favoritesOnly, privateOnly)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_ITEMS WHERE $where",
            args
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 总条数（无过滤，等价 count(null, false)，供旧调用方兼容） */
    fun count(): Int = count(null, false)

    /**
     * 按 id 读取单条记录的**正文**（解密后明文）。
     *
     * 供 IME 处理「点击记录粘贴」广播时使用：广播**不携带正文**——
     * Binder 事务上限约 1MB，长文本（长文章/日志/大段代码）会让
     * `sendBroadcast` 抛 `TransactionTooLargeException` 直接崩溃。
     * 改由这里按 id 取回。
     *
     * 注意：本方法含读库 + AES 解密，**必须在 [BackgroundIo] 线程调用**。
     */
    fun contentById(id: Long): String? {
        if (id <= 0) return null
        return readableDatabase.rawQuery(
            "SELECT $selectCols FROM $TABLE_ITEMS WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToNext()) readItem(c)?.content else null }
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
            contentHash = c.getString(6),
            category = c.getString(7),
            isFavorite = c.getInt(8) != 0,
            isPrivate = c.getInt(9) != 0,
        )
    }

    companion object {
        private const val DB_NAME = "jinn_clipboard.db"
        private const val DB_VERSION = 4
        private const val TABLE_ITEMS = "clipboard_items"
        private const val TAG = "ClipboardDb"

        /**
         * 容量裁剪的优先级：**越靠前越先被删除**。
         *
         * 收藏不在其列 —— 收藏永不参与裁剪。
         * 隐私排在最后：它是用户刻意主动标记的（约定「隐私只能用户手动标记，
         * 绝不自动」），语义上比普通记录更该保留。
         */
        private val TRIM_PRIORITY = listOf(
            "is_favorite = 0 AND is_private = 0",   // ① 普通记录
            "is_favorite = 0 AND is_private = 1",   // ② 隐私记录（最后才动）
        )

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
         * 去重用户状态合并规则（纯函数，可单测）：
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

/**
 * 剪贴板列表的筛选条件：把分类栏的「伪分类」翻译成 SQL 参数。
 *
 * 分类栏有 5 个 Tab：全部 / 网址 / 数字 / 收藏 / 隐私。其中网址、数字是真正的
 * `category` 列取值，而**收藏和隐私是独立的标签列**（is_favorite / is_private），
 * 绝不能当 category 传给 SQL——否则 `WHERE category = 'FAVORITE'` 恒不成立，
 * 列表会永远是空的。这个翻译必须显式做，是本项目最容易踩的坑之一。
 *
 * 该翻译原先在 5 处（Activity 的首页 / 下一页 / 搜索，Panel 的刷新 / 下一页）
 * 各写一遍，收敛到这里：① 消除重复 ② 纯函数、不依赖 Android，可 JVM 单测。
 */
data class ClipboardFilter(
    /** 传给 SQL 的 category 值；收藏/隐私这类伪分类此处为 null */
    val category: String?,
    val favoritesOnly: Boolean,
    val privateOnly: Boolean,
) {
    companion object {

        /** 伪分类：收藏（独立标签列，非 category 取值） */
        const val PSEUDO_FAVORITE = "FAVORITE"

        /** 伪分类：隐私（独立标签列，非 category 取值） */
        const val PSEUDO_PRIVATE = "PRIVATE"

        /**
         * 由分类栏选中的值解析筛选条件。
         * @param raw null=全部；URL/NUMBER/OTHER=分类；FAVORITE/PRIVATE=伪分类
         */
        fun of(raw: String?): ClipboardFilter = when (raw) {
            PSEUDO_FAVORITE -> ClipboardFilter(null, favoritesOnly = true, privateOnly = false)
            PSEUDO_PRIVATE -> ClipboardFilter(null, favoritesOnly = false, privateOnly = true)
            else -> ClipboardFilter(raw, favoritesOnly = false, privateOnly = false)
        }
    }
}
