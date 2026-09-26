package com.jinn.inputmethod

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 存量标签重算（[ClipboardDb.reclassifyAll]）的分页大小。
 *
 * 一页的密文串会同时驻留内存（base64 ≈ 明文的 4/3，再加解出的明文串，实测合计 2.33 倍），
 * 峰值按 [ClipboardStore.decryptWindowPeakBytes] 估算：50 条 ≈ 32.8MB（最坏情形），
 * 在 [ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES]（48MB）内，由 `ClipboardLimitsTest` 守卫。
 * 原值 200 条 ≈ 131MB，是唯一越界的解密窗口。
 */
internal const val RECLASSIFY_PAGE = 50

/**
 * 剪贴板历史数据库。
 *
 * 存储策略：
 *  - 正文以 AES-256-GCM 密文入库（[ClipboardCrypto]），绝不落明文；
 *  - 来源 APP、时间、分类、收藏标记等元数据明文存储；
 *  - 超出数量上限时删除最旧的非收藏记录（收藏永不删），
 *    数据库 + 内存缓存同步清理；
 *  - 全部操作走单例 + 后台线程，避免主线程 IO 与并发写冲突。
 *
 * 删除来源仅限：用户主动删除 / 清理重复 / 容量限制裁剪。
 * 裁剪见 [TRIM_PRIORITY]：只动非收藏记录，收藏永不删。
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
        // content_hash 唯一索引原先只在升级路径（v4 / v5）里建，全新安装拿不到它：
        // 两条安装路径的 schema 必须一致，少了它，「同一内容不重复」就只剩 upsert 里
        // 的 findIdByHash 一处代码保证，库层本可以兜住；入库查找也会退化成全表扫描。
        db.execSQL("CREATE UNIQUE INDEX idx_items_hash ON $TABLE_ITEMS(content_hash)")
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
                    "source_package, source_app_name, content_hash, category, is_favorite) " +
                    // COALESCE 兜住旧表的 NULL：新表的 content_hash 是 NOT NULL，
                    // 直接搬 NULL 会让 INSERT 失败、升级抛异常 —— 后果是整库打不开
                    "SELECT id, encrypted_content, content_type, created_at, " +
                    "source_package, source_app_name, COALESCE(content_hash, ''), category, is_favorite " +
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
            // v4：入库阶段去重。空 content_hash 先补成互不相同的 legacy 值（见 backfillBlankHashes）——
            // 否则多行空值会被判成同一内容、只留最新一条，静默删掉用户的不同内容。
            backfillBlankHashes(db)
            mergeDuplicates(db)
            db.execSQL("CREATE UNIQUE INDEX idx_items_hash ON $TABLE_ITEMS(content_hash)")
        }
        if (oldVersion < 5) {
            // v5：移除隐私功能（2026-09-16）。重建表去掉 is_private 列，其余内容原样保留。
            // 隐私标记本身被丢弃，该功能已整体移除，不做兼容。
            db.execSQL("ALTER TABLE $TABLE_ITEMS RENAME TO ${TABLE_ITEMS}_old")
            db.execSQL(createTableSql())
            db.execSQL(
                "INSERT INTO $TABLE_ITEMS (id, encrypted_content, content_type, created_at, " +
                    "source_package, source_app_name, content_hash, category, is_favorite) " +
                    // 同 v3 分支：COALESCE 兜住 NULL，NOT NULL 列搬 NULL 会失败
                    "SELECT id, encrypted_content, content_type, created_at, " +
                    "source_package, source_app_name, COALESCE(content_hash, ''), category, is_favorite " +
                    "FROM ${TABLE_ITEMS}_old"
            )
            db.execSQL("DROP TABLE ${TABLE_ITEMS}_old")
            // 修正自增序列（显式插入 id 后 sqlite_sequence 不自动更新）
            db.execSQL(
                "UPDATE sqlite_sequence SET seq = (SELECT MAX(id) FROM $TABLE_ITEMS) " +
                    "WHERE name = '$TABLE_ITEMS'"
            )
            // 重建只搬列、不重算：旧表的空哈希到这里仍为空，建唯一索引前同样要补齐，
            // 否则多行空值会让建索引失败 —— 升级抛异常就是整库打不开
            backfillBlankHashes(db)
            db.execSQL("CREATE INDEX idx_items_created ON $TABLE_ITEMS(created_at DESC)")
            db.execSQL("CREATE UNIQUE INDEX idx_items_hash ON $TABLE_ITEMS(content_hash)")
        }
    }

    /**
     * 空 content_hash 兜底：补成 `legacy:<id>`。
     *
     * 早期版本的记录可能没有哈希（列默认 `''`，更早的表还可能留 NULL），而哈希算的是**明文**
     * 内容（[stableHash]）—— 空值不代表「同一内容」，只说明这一行没有哈希。让它参与
     * 「同 hash 即同内容」的去重（[mergeDuplicates]）或建唯一索引，会有两个后果：
     * 多行被判同一组、只留最新一条（静默删掉用户的不同内容）；或索引建不上、升级失败。
     * 补成互不相同的值后，这些行既不参与合并，也能安全建索引；后续入库的稳定哈希是
     * 64 位 hex，绝不会与 `legacy:` 前缀碰撞（入库查重因此不会误命中这些旧行）。
     */
    private fun backfillBlankHashes(db: SQLiteDatabase) {
        db.execSQL(
            "UPDATE $TABLE_ITEMS SET content_hash = '$LEGACY_HASH_PREFIX' || id " +
                "WHERE content_hash IS NULL OR content_hash = ''"
        )
    }

    /** 合并重复行：每组 content_hash 保留最新一条，收藏标记以「任一为 true」合并到保留行 */
    private fun mergeDuplicates(db: SQLiteDatabase) {
        val keepIds = HashMap<String, Long>()
        val favIds = HashMap<String, Boolean>()
        val dupIds = ArrayList<Long>()
        db.rawQuery(
            "SELECT id, content_hash, is_favorite FROM $TABLE_ITEMS ORDER BY created_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val hash = c.getString(1)
                val fav = c.getInt(2) != 0
                if (!keepIds.containsKey(hash)) {
                    keepIds[hash] = id
                    favIds[hash] = fav
                } else {
                    if (fav) favIds[hash] = true
                    dupIds.add(id)
                }
            }
        }
        if (dupIds.isEmpty()) return
        db.beginTransaction()
        try {
            for ((hash, keepId) in keepIds) {
                val fav = favIds[hash] == true
                if (fav) {
                    val values = ContentValues().apply { put("is_favorite", 1) }
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
            is_favorite INTEGER NOT NULL DEFAULT 0
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
    )

    // ── 写入 ──────────────────────────────────────────────

    /**
     * 入库去重写入（原子，@Synchronized 串行）：同一内容（content_hash 相同）已存在时
     * 只更新必要元数据并重新置顶（created_at=now），绝不产生重复记录；
     * 收藏标记是用户主动状态，重复复制时不覆盖。
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
    ): Long {
        if (content.isBlank()) return -1
        val encrypted = ClipboardCrypto.encrypt(content) ?: return -1
        val hash = stableHash(content)
        val existingId = findIdByHash(hash)
        if (existingId != null) {
            // 已存在：只更新必要元数据 + 置顶，保留收藏标记
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
     * 删除全部历史：收藏是受保护条目，清空只删除非收藏记录。
     * @return 删除的条数
     */
    fun deleteAll(): Int =
        writableDatabase.delete(
            TABLE_ITEMS,
            "is_favorite = 0",
            null,
        )

    /** 更新收藏状态 */
    fun setFavorite(id: Long, favorite: Boolean): Boolean {
        val values = ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) }
        return writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(id.toString())) > 0
    }

    /**
     * 裁剪历史：先按条数裁到 [maxItems]，再按总体积裁到 [maxTotalBytes]。
     *
     * 裁剪优先级：只取非收藏的最旧记录（见 [TRIM_PRIORITY]）。
     *
     * 任一维度剩的全是收藏、删不到目标时即停止 ，
     * 宁可超出上限，也不删用户明确标记保留的内容。
     */
    fun trimTo(maxItems: Int, maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES) {
        trimByCount(maxItems)
        trimByByteBudget(maxTotalBytes)
    }

    /** 按条数裁剪（原有行为：只删最旧的非收藏记录） */
    private fun trimByCount(maxItems: Int) {
        if (maxItems <= 0) return
        val count = count()
        if (count <= maxItems) return
        val overflow = count - maxItems

        // 按优先级分轮取证：先删最旧的非收藏记录
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
        deleteByIds(ids)
    }

    /**
     * 按总体积裁剪：密文总量超过 [maxTotalBytes] 时，从最旧的非收藏记录开始删，直到落回预算内。
     *
     * 体积按 `LENGTH(encrypted_content)`，该列是 base64（纯 ASCII），字符数即字节数，
     * 单条 SQL 就能算出总量与逐条大小，无需解密、无需把正文读进内存。
     * 收藏计入总量但不参与淘汰（与 [TRIM_PRIORITY] 同一条原则）：若收藏本身就超预算，只能停手。
     */
    private fun trimByByteBudget(maxTotalBytes: Long) {
        if (maxTotalBytes <= 0) return
        // 先算总量再决定要不要细化：不超时直接返回，避免每次复制都把全表行拉到 Java 侧
        // （本方法是每次入库都走的 hot path，行数是 O(n)）。
        val total = readableDatabase.rawQuery(
            "SELECT SUM(LENGTH(encrypted_content)) FROM $TABLE_ITEMS", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (total <= maxTotalBytes) return
        val rows = ArrayList<ClipboardRowSize>()
        readableDatabase.rawQuery(
            "SELECT id, LENGTH(encrypted_content), is_favorite FROM $TABLE_ITEMS " +
                "ORDER BY created_at ASC",   // 最旧在前：淘汰顺序即此序
            null
        ).use { c -> while (c.moveToNext()) rows.add(ClipboardRowSize(c.getLong(0), c.getLong(1), c.getInt(2) != 0)) }
        val ids = overflowIdsForByteBudget(rows, maxTotalBytes)
        if (ids.isEmpty()) return
        deleteByIds(ids)
        Diagnostics.i(TAG, "按体积裁剪: 删除 ${ids.size} 条非收藏记录（预算 ${maxTotalBytes / 1024 / 1024}MB）")
    }

    /** 批量删除（单事务） */
    private fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
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
        "source_app_name, content_hash, category, is_favorite"

    /**
     * 组装过滤条件（分类 / 仅收藏），返回 WHERE 片段与参数。
     *
     * 收藏是独立标签，可与分类并存：
     *  - [favoritesOnly] 为 true 时按收藏标记列过滤；
     *  - [category] 为 URL / NUMBER 时按「分类列是否含该标签」过滤（多标签语义，见 [whereClause]；
     *    FAVORITE 是上层的伪分类，调用方需自行转成对应标记后传 null）。
     */
    private fun whereClause(
        category: String?,
        favoritesOnly: Boolean = false,
    ): Pair<String, Array<String>> {
        val where = StringBuilder("1 = 1")
        val args = ArrayList<String>(1)
        if (favoritesOnly) where.append(" AND is_favorite = 1")
        if (category != null) {
            // 多标签（2026-09-25）：category 存的是「含哪些片段」（如 `URL,NUMBER`），
            // 命中条件是**含**该标签而非等值。标签集固定且互不包含（URL / NUMBER / OTHER），
            // 所以 `LIKE '%URL%'` 不会误命中别的标签；旧库的单值（"URL"）同样被覆盖，无需迁移。
            where.append(" AND category LIKE ?")
            args.add("%$category%")
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
    ): List<Item> = recentPageWithOffset(offset, limit, category, favoritesOnly).items

    /**
     * 分页读取记录（新→旧），只解密本页 [limit] 条，并带回下一页的游标位置。
     *
     * 游标含义：[Page.nextOffset] 是实际扫描过的原始行数，不是解密成功的条数。
     * [readItem] 遇到解密失败的行会跳过，两者一旦混用就会错位，调用方普遍拿
     * 「已加载条数」当 OFFSET，只要首页有 1 条解密失败，下一页就会重复取到已显示的行、
     * 并把尾部行永久跳过。分页必须改用 [Page.nextOffset]。
     */
    fun recentPageWithOffset(
        offset: Int,
        limit: Int,
        category: String? = null,
        favoritesOnly: Boolean = false,
    ): Page {
        if (limit <= 0 || offset < 0) return Page(emptyList(), offset)
        val (where, args) = whereClause(category, favoritesOnly)
        val c = readableDatabase.rawQuery(
            "SELECT $selectCols FROM $TABLE_ITEMS WHERE $where " +
                "ORDER BY created_at DESC LIMIT $limit OFFSET $offset",
            args
        )
        c.use { cur ->
            val out = ArrayList<Item>(limit)
            while (cur.moveToNext()) readItem(cur)?.let { out.add(it) }
            return Page(out, offset + cur.count)
        }
    }

    /** 一页查询结果：[items] 为解密成功的条目，[nextOffset] 为下一页的 SQL OFFSET */
    data class Page(val items: List<Item>, val nextOffset: Int)

    /** 记录总数（纯 SQL 计数，不解密；可按分类/收藏/隐私过滤） */
    fun count(
        category: String? = null,
        favoritesOnly: Boolean = false,
    ): Int {
        val (where, args) = whereClause(category, favoritesOnly)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_ITEMS WHERE $where",
            args
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 总条数（无过滤，等价 count(null, false)，供旧调用方兼容） */
    fun count(): Int = count(null, false)

    // ── 分组标签重算（存量迁移）───────────────────────────

    /**
     * 按当前规则重算全部分组标签（2026-09-25 多标签语义变更的存量迁移；幂等）。
     *
     * 逐条解密 → [ClipboardClassifier.classify] → 仅在有变化时 UPDATE。
     * 解密是必须的：标签由内容决定，而内容只存密文。因此只能在后台线程跑，
     * 由 [ClipboardController.start] 用一次性标记触发，跑完置位。
     *
     * 分页扫描（不一次性把全库密文读进内存）：每页 [RECLASSIFY_PAGE] 条，
     * 游标是「上一页最后一个 id」而不是 SQL OFFSET —— 重算期间用户复制入库、
     * 导入备份、按上限裁剪都会增删行，OFFSET 会随物理行位置漂移而漏扫。
     * 页内解密失败的行跳过（与列表路径同款容错）。
     *
     * 页内写入包一个事务：几百条各自成事务等于几百次 fsync，会把这趟迁移拖成
     * 秒级以上的后台长任务。
     *
     * @return 实际改动的条数
     */
    fun reclassifyAll(): Int {
        var changed = 0
        var lastId = 0L
        while (true) {
            val rows = ArrayList<Pair<Long, String>>(RECLASSIFY_PAGE)
            readableDatabase.rawQuery(
                "SELECT id, encrypted_content FROM $TABLE_ITEMS WHERE id > ? ORDER BY id " +
                    "LIMIT $RECLASSIFY_PAGE",
                arrayOf(lastId.toString()),
            ).use { c -> while (c.moveToNext()) rows.add(c.getLong(0) to c.getString(1)) }
            if (rows.isEmpty()) return changed
            lastId = rows.last().first
            writableDatabase.beginTransaction()
            try {
                for ((id, encrypted) in rows) {
                    val text = ClipboardCrypto.decrypt(encrypted) ?: continue
                    val label = ClipboardClassifier.classify(text)
                    val values = ContentValues().apply { put("category", label) }
                    if (writableDatabase.update(
                            TABLE_ITEMS, values, "id = ? AND category <> ?",
                            arrayOf(id.toString(), label),
                        ) > 0
                    ) {
                        changed++
                    }
                }
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        }
    }

    // ── 备份导入 ──────────────────────────────────────────

    /**
     * 全部内容哈希（备份导入去重用）。
     *
     * 只查 `content_hash` 一列、不解密：历史可能有几千条，逐条解密只为拿到哈希
     * 等于把整个库的明文都搬进内存，与分页加载的初衷相悖。
     */
    fun allHashes(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT content_hash FROM $TABLE_ITEMS", null).use { c ->
            while (c.moveToNext()) {
                val h = c.getString(0)
                if (!h.isNullOrEmpty()) out.add(h)
            }
        }
        return out
    }

    /**
     * 备份导入写入：保留原始时间戳与收藏标记。
     *
     * 与 [upsert] 的区别是不做「置顶」也不覆盖同 hash 的既有行——去重由调用方
     * （[allHashes] + `ConfigBackup.planClipboardImport`）先行规划，这里只负责
     * 按原样落库，否则导入的上百条历史会被统一改写成「刚刚复制」。
     *
     * @return 新行 id；内容为空或加密失败返回 -1
     */
    @Synchronized
    fun insertRestored(
        content: String,
        createdAt: Long,
        sourcePackage: String,
        sourceAppName: String,
        category: String,
        favorite: Boolean,
    ): Long {
        if (content.isBlank()) return -1
        // 与采集路径一致的单条上限：备份里的超长单条会让面板每次打开都为它解密一遍，
        // 而「新复制的同样内容不入库、导入的却入库」本身就是行为不一致
        if (ClipboardStore.exceedsItemLimit(content)) return -1
        val encrypted = ClipboardCrypto.encrypt(content) ?: return -1
        val values = ContentValues().apply {
            put("encrypted_content", encrypted)
            put("content_type", "text")
            put("created_at", createdAt)
            put("source_package", sourcePackage)
            put("source_app_name", sourceAppName)
            put("content_hash", stableHash(content))
            put("category", category)
            put("is_favorite", if (favorite) 1 else 0)
        }
        return writableDatabase.insert(TABLE_ITEMS, null, values)
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
        )
    }

    companion object {
        private const val DB_NAME = "jinn_clipboard.db"
        private const val DB_VERSION = 5
        private const val TABLE_ITEMS = "clipboard_items"
        private const val TAG = "ClipboardDb"

        /**
         * 容量裁剪的优先级：越靠前越先被删除。
         * 目前只有一档，非收藏的最旧记录；收藏永不参与裁剪。
         */
        private val TRIM_PRIORITY = listOf(
            "is_favorite = 0",   // 非收藏记录；收藏永不参与裁剪
        )

        /**
         * 历史密文总量预算：超出即按最旧非收藏淘汰（100 MB）。
         *
         * 注意：按库内密文（base64）体积计（`LENGTH(encrypted_content)`），
         * 不是明文字节，base64 膨胀约 4/3，故 100 MB 预算约对应 73 MB 明文。
         */
        const val DEFAULT_MAX_TOTAL_BYTES = 100L * 1024 * 1024

        /**
         * 按字节预算挑选应删除的记录 id（纯函数）。
         *
         * @param rows 必须按最旧在前传入（查询侧即 `ORDER BY created_at ASC`）
         * @param maxBytes 总量预算；`<= 0` 视为不限制
         * @return 应删除的 id；收藏永不出现在结果里
         *
         * 收藏计入总量但不参与淘汰，若收藏本身就超预算，可删的条目删完仍超限，
         * 此时只能停手（与条数裁剪同一条原则：宁可超限，也不删用户明确标记保留的内容）。
         */
        fun overflowIdsForByteBudget(rows: List<ClipboardRowSize>, maxBytes: Long): List<Long> {
            if (maxBytes <= 0) return emptyList()
            var total = rows.sumOf { it.bytes }
            if (total <= maxBytes) return emptyList()
            val out = ArrayList<Long>()
            for (row in rows) {
                if (total <= maxBytes) break
                if (row.favorite) continue
                out.add(row.id)
                total -= row.bytes
            }
            return out
        }

        @Volatile
        private var instance: ClipboardDb? = null

        fun get(context: Context): ClipboardDb =
            instance ?: synchronized(this) {
                instance ?: ClipboardDb(context).also { instance = it }
            }

        /** 迁移期给空哈希补的占位前缀（见 backfillBlankHashes）：稳定哈希是 64 位 hex，不会碰撞 */
        private const val LEGACY_HASH_PREFIX = "legacy:"

        /** 稳定哈希：内容去重与来源追踪用（不暴露原文） */
        fun stableHash(text: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 按体积淘汰时的一行信息：id、密文长度（base64 纯 ASCII，字符数即字节数）、是否收藏。
 *
 * 与 [ClipboardFilter] 一样做成顶层类：它同时是 [ClipboardDb.overflowIdsForByteBudget]
 * 的入参类型，纯数据、可直接 JVM 单测。
 */
data class ClipboardRowSize(val id: Long, val bytes: Long, val favorite: Boolean)

/**
 * 剪贴板列表的筛选条件：把分类栏的「伪分类」翻译成 SQL 参数。
 *
 * 分类栏有 4 个 Tab：全部 / 网址 / 数字 / 收藏。其中网址、数字是真正的
 * `category` 列取值，而收藏是独立的标签列（is_favorite），
 * 绝不能当 category 传给 SQL，否则 `WHERE category = 'FAVORITE'` 恒不成立，
 * 列表会永远是空的。这个翻译必须显式做，是本项目最容易踩的坑之一。
 *
 * 该翻译原先在 5 处（Activity 的首页 / 下一页 / 搜索，Panel 的刷新 / 下一页）
 * 各写一遍，现在收敛到这里：一来不用重复，二来它是纯函数、不依赖 Android，能直接 JVM 单测。
 */
data class ClipboardFilter(
    /** 传给 SQL 的 category 值；收藏/隐私这类伪分类此处为 null */
    val category: String?,
    val favoritesOnly: Boolean,

) {
    companion object {

        /** 伪分类：收藏（独立标签列，非 category 取值） */
        const val PSEUDO_FAVORITE = "FAVORITE"


        /**
         * 由分类栏选中的值解析筛选条件。
         * @param raw null=全部；URL/NUMBER/OTHER=分类；FAVORITE=伪分类
         */
        fun of(raw: String?): ClipboardFilter = when (raw) {
            PSEUDO_FAVORITE -> ClipboardFilter(null, favoritesOnly = true)
            else -> ClipboardFilter(raw, favoritesOnly = false)
        }
    }
}
