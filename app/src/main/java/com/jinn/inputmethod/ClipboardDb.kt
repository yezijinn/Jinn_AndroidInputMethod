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
 * 原值 200 条 ≈ 131MB（越界）；同量级的问题后来在**配置备份导出**那条路径上又出现过一次
 * （`ConfigBackupManager.CLIP_PAGE` 原值同为 200，已一并压回预算内，见 BUG.md L-102）。
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
        // 复合索引：分页 SQL 是 `ORDER BY created_at DESC, id DESC`（L-97 的 tie-breaker），
        // 索引必须同形，否则每次分页都要临时 B 树排序（BUG.md L-104）
        db.execSQL("CREATE INDEX idx_items_created ON $TABLE_ITEMS(created_at DESC, id DESC)")
        // content_hash 唯一索引原先只在升级路径（v4 / v5）里建，全新安装拿不到它：
        // 两条安装路径的 schema 必须一致，少了它，「同一内容不重复」就只剩 upsert 里
        // 的 findIdByHash 一处代码保证，库层本可以兜住；入库查找也会退化成全表扫描。
        db.execSQL("CREATE UNIQUE INDEX idx_items_hash ON $TABLE_ITEMS(content_hash)")
    }

    /**
     * 降级：设备上的库版本**高于**本版本期望（用户装回旧 APK，BUG.md L-101）。
     *
     * 不覆写会命中平台默认实现，直接抛 `SQLiteException: Can't downgrade database from version X to Y`
     * ⇒ 库永远打不开，剪贴板面板 / 搜索 / 自动记录全部失效，而调用点大多有兜底（BackgroundIo 包装 +
     * 面板 runCatching）⇒ **不崩、只留日志**，比崩溃更难自查。
     *
     * 这里按「向前兼容」处理：只把 `user_version` 降回本版本认识的值，**不动数据、不动表结构** ——
     * 前提是库层遵循「加列不删列」的演进口径（本文件历次迁移都是重建表后补齐列，没有删过用户数据列；
     * 多余的列对旧的列名显式查询无害）。代价要说清：① 更早的构建若依赖已不存在的列（如 v4 时代的
     * `is_private`），仍可能在查询时报「no such column」——那不是本函数能解决的；② 本次降级会留下
     * 一条 W 日志，便于排查「为什么历史里少了几条 / 面板空」这类问题。
     *
     * 刻意**不重建表 / 不清库**：降级安装多半是回滚排查，用户的历史仍在其中，清掉才是真的数据丢失。
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Diagnostics.w(
            TAG,
            "库版本降级: $oldVersion -> $newVersion（装回了旧版 APK），仅回写 user_version，保留全部数据",
        )
        db.version = newVersion
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
        if (oldVersion < 6) {
            // v6：索引升级成复合键（BUG.md L-104）。已装机库的同名索引只有 created_at 一列，
            // 而分页 SQL 从 L-97 起是 `ORDER BY created_at DESC, id DESC` ⇒ 不改索引就会
            // 每次分页多一棵临时 B 树（深翻页实测 ≈10×）。`DROP IF EXISTS` 兜住
            // 「v3/v5 重建分支刚建过旧形状」与「v4 及更早的库也走到这里」两种情形。
            db.execSQL("DROP INDEX IF EXISTS idx_items_created")
            db.execSQL("CREATE INDEX idx_items_created ON $TABLE_ITEMS(created_at DESC, id DESC)")
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
            // `, id DESC` 与分页查询同因（BUG.md L-97）：同毫秒的行在「谁是最新一条」上没有确定序，
            // 去重时留哪一条就成了实现细节 ⇒ 用 id 兜成确定序（id 大 = 后插入 = 更新）
            "SELECT id, content_hash, is_favorite FROM $TABLE_ITEMS ORDER BY created_at DESC, id DESC",
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
     *
     * **坏行自愈**：命中哈希但那一行的密文已经解不开（Keystore 密钥变更 / 单行损坏）时，
     * 用本次的新内容重加密覆盖该行 —— 否则它会永远不可见、又继续占条数与字节配额，
     * 而重复复制本是唯一的恢复机会（BUG.md L-173）。
     *
     * 加密放在**判重之后**：去重只需要明文哈希，重复内容不必白做一次 AES-GCM + base64（BUG.md L-182）。
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
        val hash = stableHash(content)
        val existingId = findIdByHash(hash)
        if (existingId != null) {
            if (!decryptsById(existingId)) {
                val reEncrypted = ClipboardCrypto.encrypt(content) ?: return -1
                val fix = ContentValues().apply {
                    put("encrypted_content", reEncrypted)
                    put("content_type", contentType)
                    put("created_at", System.currentTimeMillis())
                    put("source_package", sourcePackage)
                    put("source_app_name", sourceAppName)
                    put("category", category)
                }
                writableDatabase.update(TABLE_ITEMS, fix, "id = ?", arrayOf(existingId.toString()))
                Diagnostics.w(TAG, "重加密自愈: id=$existingId（原密文解不开，已用新内容覆盖）")
                return existingId
            }
            // 已存在且可解：只更新必要元数据 + 置顶，保留收藏标记
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
        // 到这一步才加密：上面的判重分支不需要密文（BUG.md L-182）
        val encrypted = ClipboardCrypto.encrypt(content) ?: return -1
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

    /**
     * 该行的密文现在还能解开吗（坏行自愈的前置判据，BUG.md L-173）。
     *
     * 解不开或行已不在都返回 false：前者要重加密覆盖，后者的哈希行是脏数据（交给插入路径）。
     */
    private fun decryptsById(id: Long): Boolean {
        val c = readableDatabase.rawQuery(
            "SELECT encrypted_content FROM $TABLE_ITEMS WHERE id = ? LIMIT 1",
            arrayOf(id.toString()),
        )
        return c.use {
            if (!it.moveToFirst()) return@use false
            val enc = it.getString(0) ?: return@use false
            ClipboardCrypto.decrypt(enc) != null
        }
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
     * 键集游标分页读取（新→旧），只解密本页 [limit] 条（BUG.md L-92）。
     *
     * **为什么不用 SQL OFFSET**：面板打开期间别的应用改一次剪贴板，库里就是「头部插一条 + 尾部裁一条」
     * （[ClipboardStore] 到上限时 `insertItem` → `trimTo` 成对发生，总条数**刚好不变**）⇒ 行的物理位置
     * 整体位移，OFFSET 会让下一页重复一行或跳过一行；数量比对（`count` 不变）也拦不住。
     * 键集游标只认「上一页最后**扫描过**的那一行的键」，位置怎么移都不会错 —— [reclassifyAll] 早就是
     * 这个口径（「游标是上一页最后一个 id 而不是 SQL OFFSET」）。
     *
     * [cursor]：`null` 表示从最新一条开始；否则是上一页 [KeyedPage.last]。
     *
     * 排序仍必须是 `created_at DESC, id DESC`：created_at 是毫秒时间戳，同毫秒的多行构成**等值组**，
     * SQLite 不承诺等值组内的顺序（计划 / 索引 / VACUUM 变化都可能改序，BUG.md L-97）⇒
     * 游标判据也必须带上 id 做等值内的次序（[ClipboardCursor.isOlderThan] 与 SQL 里的
     * `(created_at < ? OR (created_at = ? AND id < ?))` 同口径）。
     */
    fun recentPageAfter(
        cursor: ClipboardCursor?,
        limit: Int,
        category: String? = null,
        favoritesOnly: Boolean = false,
    ): KeyedPage {
        if (limit <= 0) return KeyedPage(emptyList(), 0, cursor)
        val (where, args) = whereClause(category, favoritesOnly)
        val cursorClause =
            if (cursor == null) "" else " AND (created_at < ? OR (created_at = ? AND id < ?))"
        val cursorArgs =
            if (cursor == null) emptyArray()
            else arrayOf(
                cursor.createdAt.toString(),
                cursor.createdAt.toString(),
                cursor.id.toString(),
            )
        val c = readableDatabase.rawQuery(
            "SELECT $selectCols FROM $TABLE_ITEMS WHERE $where$cursorClause " +
                "ORDER BY created_at DESC, id DESC LIMIT $limit",
            args + cursorArgs,
        )
        c.use { cur ->
            val out = ArrayList<Item>(limit)
            var scanned = 0
            var last: ClipboardCursor? = null
            while (cur.moveToNext()) {
                scanned++
                // 游标取「扫描过的行」而不是「解密成功的条目」：整页解密失败时也必须能前进，
                // 否则下一页会原地重扫同一批行（列表路径与导出 / 搜索路径都靠这条）。
                last = ClipboardCursor(cur.getLong(3), cur.getLong(0))
                readItem(cur)?.let { out.add(it) }
            }
            return KeyedPage(out, scanned, if (scanned > 0) last else cursor)
        }
    }

    /**
     * 键集分页结果。
     *
     * [scanned] 是**扫描过的原始行数**（与 [Page.nextOffset] 同口径），不是 [items] 的条数：
     * [readItem] 遇到解密失败的行会跳过，两者混用会错位（旧实现就栽在这上面，BUG.md L-92）。
     * [last] 是本次最后一个扫描行的键，交给下一页当 [recentPageAfter] 的游标；一页都没扫到时为 `null`。
     */
    data class KeyedPage(
        val items: List<Item>,
        val scanned: Int,
        val last: ClipboardCursor?,
    )

    /** 一页查询结果：[items] 为解密成功的条目，[nextOffset] 为**已扫描过的原始行数**（进度，不是 SQL OFFSET） */
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

        /**
         * v6：把 `idx_items_created` 升级成**复合索引** `(created_at DESC, id DESC)`（BUG.md L-104）。
         *
         * L-97 给分页 SQL 补了 `, id DESC` 兜等值组的序，但索引只有 `created_at` 一列
         * ⇒ SQLite 每次分页都要 `USE TEMP B-TREE FOR LAST TERM OF ORDER BY`
         * （本机 SQLite 实测 9,999 行：深翻页 0.28 → 2.41ms，复合索引后回 0.75ms）。
         * 已装机库的索引同名已存在，改 `onCreate` 只影响全新安装 ⇒ 必须配一条迁移分支。
         */
        private const val DB_VERSION = 6
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

        /**
         * 首屏连续填页的扫描上限（页数）：坏行密集时（如密钥整体失效）的止损。
         *
         * 取 8 页（≤400 原始行）的依据：稀疏坏行一页就够；连续坏行只可能来自
         * 「整段写入失败 / 密钥失效」，而密钥失效是全表坏、扫多少页都无解 ——
         * 上限只需覆盖「首屏附近有一段坏行」的现实情形，避免每次开面板都白解密全表。
         */
        const val FIRST_PAGE_MAX_SCAN_PAGES = 8

        /**
         * 首屏填页的**明文字节预算**：累计 `Σ(条目 UTF-8 字节)` 超过它就不再收新页（BUG.md L-91）。
         *
         * 为什么不能只限页数：`out` 的累计上限是 `limit - 1 + limit`（末页整页收下）——
         * 面板 50 条时最坏 **99 条**；若这 99 条都接近单条上限（256KB），
         * 解密峰值 = 99 × 256KB × [ClipboardStore.DECRYPT_ITEM_AMPLIFICATION] ≈ **61.9MB**，
         * 超过 [ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES]（48MB）。填页跑在后台线程
         * `jinn-clipboard-io`，越界就是 OOM 杀 IME 进程（键盘突然消失、输入中断）。
         *
         * 按**实际明文**限预算（不是条数）：正常条目几十~几百字节时，8 页 × 50 条也就几十 KB，
         * 离预算差三个数量级 ⇒ 填页能力一点不减；只有「满额大条目 + 坏行穿插」这种极端组合
         * 才会提前收工。收工时游标停在**被拒那页之前**，剩下的行走正常分页 / 续扫照常取到。
         */
        val FIRST_PAGE_MAX_PLAIN_BYTES: Long = ClipboardStore.decryptWindowPlainBudgetBytes()

        /**
         * 首屏填页：连续取页直到凑满 [limit] 条**可解密**条目、或扫到末尾、或达 [maxScanPages]、
         * 或累计明文超过 [maxPlainBytes]。
         *
         * 为什么不能只取一页：解密失败的行会被 [Item] 读取路径跳过（对用户不可见），
         * 若最新的一整页恰好都是坏行，列表会拿到空结果 —— 面板据此显示空态并隐藏列表，
         * 而预取闸门要求列表非空才继续翻页（`totalItemCount > 0`）⇒ 后面还能解密的
         * 历史**永久翻不到**（功能死路）。填页让首屏要么给出真实条目，要么如实报告「有内容但读不出」。
         *
         * 健康库上只会调用一次 [fetch]（一页取满即停），无额外解密开销，
         * 因此这是纯函数：取页语义与 [recentPageAfter] 相同（游标按**扫过的原始行**前进，只解密本页），
         * 可直接 JVM 单测。
         *
         * ⚠ 返回的 [Page.items] **可能多于** [limit]（末页整页收下）—— 不能裁剪：
         * 游标已扫过这些行，裁掉就再也没人能看到它们。调用方按「一屏多几条」处理即可。
         *
         * @param total 纯 SQL 计数的原始行数（[count]；坏行也计入）
         * @param limit 每页取的行数（面板为 PANEL_PAGE_ITEMS）
         * @param startOffset 从哪一行开始扫（默认 0 = 首屏；>0 = 从既有游标**续扫**，
         *   用于「扫到上限仍没凑出条目、用户点空态继续找」的场景 —— 这时不重扫前面已看过的行）
         * @param maxPlainBytes 累计明文预算（见 [FIRST_PAGE_MAX_PLAIN_BYTES]）；`<= 0` 视为不限制
         * @param fetch 取页函数，语义同 [recentPageAfter]（游标必须按**扫过的原始行**前进；
         *   ⚠ 键集取页器请配合 [pageAccepted] 使用：本函数被字节预算拒收末页时**不会**推进 offset，
         *   取页器若已把游标推过那一页，就会把该页条目永久跳过，见 BUG.md L-117）
         */
        fun fillFirstPage(
            total: Int,
            limit: Int,
            maxScanPages: Int = FIRST_PAGE_MAX_SCAN_PAGES,
            // ⚠ 新参数一律排在最后（BUG.md L-86）：`startOffset` 曾经插在 `maxScanPages` 之前，
            // 两个可选参数同为 Int ⇒ `fillFirstPage(total, 50, 8) { … }` 会把 8 静默传成 startOffset。
            // 调用方也一律用命名参数（见 ClipboardPanelView.continueScan）。
            startOffset: Int = 0,
            maxPlainBytes: Long = FIRST_PAGE_MAX_PLAIN_BYTES,
            fetch: (offset: Int, limit: Int) -> Page,
        ): Page {
            if (limit <= 0 || total <= 0 || maxScanPages <= 0) {
                return Page(emptyList(), startOffset.coerceIn(0, maxOf(total, 0)))
            }
            val out = ArrayList<Item>(limit)
            var offset = startOffset.coerceIn(0, total)
            var pages = 0
            var plainBytes = 0L
            while (out.size < limit && offset < total && pages < maxScanPages) {
                val page = fetch(offset, limit)
                // 游标没前进（坏页/异常返回）：立刻退出，否则会无限取同一页
                if (page.nextOffset <= offset) break
                // 字节预算（L-91）：**先试算再决定收不收** —— 越界就整页拒收、游标留在它之前，
                // 于是这一页会被后续分页 / 续扫重新取到（不裁剪、不丢条目）。
                // 首屏非空由「第一页无条件收下」保证：单页最坏 50 × 256KB × 2.5 = 32.8MB < 48MB 预算。
                val pageBytes = page.items.sumOf { ClipboardStore.utf8ByteSize(it.content) }
                if (out.isNotEmpty() && maxPlainBytes > 0 && plainBytes + pageBytes > maxPlainBytes) break
                out.addAll(page.items)
                plainBytes += pageBytes
                offset = page.nextOffset
                pages++
            }
            return Page(out, offset)
        }

        /**
         * 填页收尾时判断「最后取回的那一页到底有没有被接受」（BUG.md L-117）。
         *
         * [fillFirstPage] 的字节预算判据是**先试算再决定收不收**：接受 ⇒ 游标推进到
         * `lastFetchOffset + lastFetchedScanned`；拒收 ⇒ 游标**留在** `lastFetchOffset`。
         * 于是「返回值是否等于 `lastFetchOffset + lastFetchedScanned`」就是接受与否的充要判据。
         *
         * 为什么必须问它：键集取页器（`ClipboardPanelView` 的两个闭包）会在**取回**那一刻就记住这一页的
         * 末键；若末页被拒收而取页器仍把它当作下一页游标，那一页的条目既不在当前列表里、也再也翻不到
         * —— 与 L-92 的「漏行」同症状，成因从位置位移换成了预算拒收。
         *
         * `lastFetchedScanned <= 0` 时恒为 false（取页器原地没动，没有可推进的页；对应 fillFirstPage 的
         * 「游标不前进即退出」那一支）。
         */
        fun pageAccepted(
            nextOffsetAfterFill: Int,
            lastFetchOffset: Int,
            lastFetchedScanned: Int,
        ): Boolean =
            lastFetchedScanned > 0 && nextOffsetAfterFill == lastFetchOffset + lastFetchedScanned

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
 * 分页键集游标：`(created_at, id)` 二元组，与 `ORDER BY created_at DESC, id DESC` 同序（BUG.md L-92）。
 *
 * 为什么不是 SQL OFFSET：面板打开期间外部改一次剪贴板就是「头部插一条 + 尾部裁一条」（到上限时
 * `insertItem` → `trimTo` 成对发生，总条数不变）⇒ 行位置整体位移，OFFSET 分页会重复 / 跳过一行。
 * 键集游标只认「上一页最后扫描过的那一行的键」，位移免疫。
 *
 * 做成顶层纯数据：它同时是 [ClipboardDb.recentPageAfter] 的入参 / 出参，且 [isOlderThan] 与 SQL 里的
 * `(created_at < ? OR (created_at = ? AND id < ?))` 是同一口径 —— 两边必须一起改，单测就钉在它身上。
 */
data class ClipboardCursor(val createdAt: Long, val id: Long) {

    /**
     * 是否排在 [other] **之后**（更旧，即分页方向上的下一个）。
     *
     * 先比 created_at，等值再比 id —— 等值组（同毫秒）内不靠 id 兜序的话，跨页边界会重复或漏行
     * （与 BUG.md L-97 补的 `, id DESC` 是同一件事的两端）。
     */
    fun isOlderThan(other: ClipboardCursor): Boolean =
        createdAt < other.createdAt || (createdAt == other.createdAt && id < other.id)
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
