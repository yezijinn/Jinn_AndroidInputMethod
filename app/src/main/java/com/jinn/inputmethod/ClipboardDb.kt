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
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        // 库是**新建**的：首次安装，或库损坏后平台删库重建（BUG.md L-260）。
        // 后一种情况下历史里最大 id 从 1 重来，而「凭据不留痕」的水位记的是旧库里的 id ⇒
        // 不清掉的话新 id 永远小于旧水位，「没有新条目才跳过」恒成立 ⇒ 该进程内凭据清理彻底失效，
        // 用户之后再复制 API Key 会明文留在历史里（面板可见、随备份导出）。首次安装时那张表本来
        // 就是空的，清一次无副作用。
        CredentialTrace.clearWatermarks()
        Diagnostics.i(TAG, "剪贴板库新建（首次安装或损坏后重建）：凭据清理水位已复位")
        db.execSQL(createTableSql())
        // 复合索引：分页 SQL 是 `ORDER BY created_at DESC, id DESC`（L-97 的 tie-breaker），
        // 索引必须同形，否则每次分页都要临时 B 树排序（BUG.md L-104）
        db.execSQL("CREATE INDEX idx_items_created ON $TABLE_ITEMS(created_at DESC, id DESC)")
        // content_hash 唯一索引原先只在升级路径（v4 / v5）里建，全新安装拿不到它：
        // 两条安装路径的 schema 必须一致，少了它，「同一内容不重复」就只剩 upsert 里
        // 的 findIdByHash 一处代码保证，库层本可以兜住；入库查找也会退化成全表扫描。
        db.execSQL("CREATE UNIQUE INDEX idx_items_hash ON $TABLE_ITEMS(content_hash)")
        // v7：密文总长的表达式索引，让 trimByByteBudget 的全表 SUM 退化成索引扫描（见 DB_VERSION 注释）
        db.execSQL(bytesIndexSql())
        // v8：内容类型索引 —— 图片行的全部查询（裁剪 / GC / 计数）都按 content_type 过滤，
        // 无索引时每次图片入库都要全表扫（见 DB_VERSION 注释）
        db.execSQL(contentTypeIndexSql())
    }

    /**
     * 降级：设备上的库版本**高于**本版本期望（用户装回旧 APK，BUG.md L-101）。
     *
     * 不覆写会命中平台默认实现，直接抛 `SQLiteException: Can't downgrade database from version X to Y`
     * ⇒ 库永远打不开，剪贴板面板 / 搜索 / 自动记录全部失效，而调用点大多有兜底（BackgroundIo 包装 +
     * 面板 runCatching）⇒ **不崩、只留日志**，比崩溃更难排查。
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
        if (oldVersion < 7) {
            // v7：密文总长的表达式索引（见 DB_VERSION 注释）。`IF NOT EXISTS` 兜住
            // 「v3/v5 重建分支刚建过」与「旧库从未建过」两种情形
            db.execSQL(bytesIndexSql())
        }
        if (oldVersion < 8) {
            // v8：图片支持。四条元数据列全部带 NOT NULL DEFAULT，老行（全文本）天然合法，
            // 无需 backfill；配套建内容类型索引（见 DB_VERSION 注释）。
            // ⚠ 加列不删列：装回旧版 APK 时旧版按显式列名查询，新列被忽略（onDowngrade 只回写版本号）。
            // ⚠ 必须按**列是否存在**决定加不加：v3/v5 的重建分支用的是**新版**建表 SQL（已含 image_* 列），
            // 从那些版本一路升上来时列已存在，无条件 ALTER 会报 `duplicate column name` ⇒ 整库打不开。
            if (!hasColumn(db, "image_bytes")) {
                db.execSQL("ALTER TABLE $TABLE_ITEMS ADD COLUMN image_bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_ITEMS ADD COLUMN image_w     INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_ITEMS ADD COLUMN image_h     INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_ITEMS ADD COLUMN image_mime  TEXT    NOT NULL DEFAULT ''")
            }
            db.execSQL(contentTypeIndexSql())
        }
    }

    /** 该表当前是否已有某列（v8 的加列分支用它兜住 v3/v5 重建路径，见那里的注释） */
    private fun hasColumn(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("PRAGMA table_info($TABLE_ITEMS)", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(idx) == name) return true
            false
        }

    /** 密文总长的表达式索引（v7 起；`onCreate` 与迁移分支共用同一句，避免两处写法漂移） */
    private fun bytesIndexSql(): String =
        "CREATE INDEX IF NOT EXISTS idx_items_bytes ON $TABLE_ITEMS(LENGTH(encrypted_content))"

    /** 内容类型索引（v8 起；同样 onCreate / 迁移共用一句） */
    private fun contentTypeIndexSql(): String =
        "CREATE INDEX IF NOT EXISTS idx_items_ctype ON $TABLE_ITEMS(content_type)"

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
            is_favorite INTEGER NOT NULL DEFAULT 0,
            image_bytes INTEGER NOT NULL DEFAULT 0,
            image_w INTEGER NOT NULL DEFAULT 0,
            image_h INTEGER NOT NULL DEFAULT 0,
            image_mime TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent()

    /**
     * 图片条目的元数据。
     *
     * 全部字段都不敏感（尺寸 / 体积 / MIME / 哈希），明文列存储；真正的内容（像素）在
     * `filesDir/clipboard/<hex>.enc` 里加密保存，文件名由 [hash] 派生（见 [ClipboardImageFiles]）。
     *
     * 图片行的 `encrypted_content` 只存**占位密文**（加密后的 `img:<hex>` 串），其唯一作用是
     * 让行在「坏行自愈 / 可读性备忘」机制下与文本行同构 —— 读路径对图片行**不解密**。
     */
    data class ImageMeta(
        val hash: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val mime: String,
    )

    /** 剪贴板历史条目（明文仅在读取时存在，不长期驻留） */
    data class Item(
        val id: Long,
        val content: String,      // 已解密明文（内存中）；图片行恒为空串（见 ImageMeta 注释）
        val contentType: String,
        val createdAt: Long,
        val sourcePackage: String,
        val sourceAppName: String,
        val contentHash: String,
        val category: String = "OTHER",   // URL / NUMBER / OTHER
        val isFavorite: Boolean = false,
        /** 图片行非空；文本行恒为 null。新字段带默认值：既有构造点（含测试）无需改动 */
        val image: ImageMeta? = null,
    )

    // ── 写入 ──────────────────────────────────────────────

    /**
     * 本进程内已确认「该哈希对应的行密文可解」的备忘（BUG.md L-199）。
     *
     * 命中 `content_hash` 是重复复制**最常见**的路径，而为发现极少数坏行让每次都整行解密
     * （AES-GCM + base64，单条最大 256KB）不划算：验证过一次就不再验。
     * 只记「验证通过」这一个结论 —— 没验证过的行仍会真解一次，坏行的自愈机会不因备忘而消失
     * （BUG.md L-173）。写路径（自愈 / 插入）成功后登记，读路径解不开时撤销（见 [readItem]），
     * 备份恢复整批作废（见 [insertRestored]）。
     *
     * 并发：写路径在对象监视器内，[readItem] 可能跑在多个读线程上 ⇒ 用并发集合。
     * 它只是缓存，竞态的最坏结果是多解一次密。
     */
    private val verifiedReadable: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 登记「可解」；超过上限整体清空（有界，避免长会话无限增长） */
    private fun rememberReadable(hash: String) {
        if (hash.isEmpty()) return
        if (verifiedReadable.size >= VERIFIED_MEMO_MAX) verifiedReadable.clear()
        verifiedReadable.add(hash)
    }

    /** 撤销备忘（读路径发现解不开 / 备份恢复批处理） */
    private fun forgetReadable(hash: String?) {
        if (!hash.isNullOrEmpty()) verifiedReadable.remove(hash)
    }

    /**
     * 入库去重写入（原子，@Synchronized 串行）：同一内容（content_hash 相同）已存在时
     * 只更新必要元数据并重新置顶（created_at=now），绝不产生重复记录；
     * 收藏标记是用户主动状态，重复复制时不覆盖。
     *
     * **坏行自愈**：命中哈希但那一行的密文已经解不开（Keystore 密钥变更 / 单行损坏）时，
     * 用本次的新内容重加密覆盖该行 —— 否则它会永远不可见、又继续占条数与字节配额，
     * 而重复复制本是唯一的恢复机会（BUG.md L-173）。试解本身**有界**：本进程验证过一次的行
     * 不再重复解密（见 [verifiedReadable]，BUG.md L-199）；没验证过的行仍会真解一次。
     *
     * 加密放在**判重之后**：去重只需要明文哈希，重复内容不必白做一次 AES-GCM + base64（BUG.md L-182）。
     * 不存在则插入，超上限裁剪最旧非收藏。
     *
     * @return 条目 id（已存在的行或新插入的行）；内容为空 / 加密失败返回 -1。
     *   命中哈希但那一行已经不在了（外部改库、库文件被换）时**落到插入路径重建**，
     *   绝不把不存在的 id 当成功返回（BUG.md L-200）。
     */
    @Synchronized
    fun upsert(
        content: String,
        contentType: String = "text",
        sourcePackage: String,
        sourceAppName: String,
        maxItems: Int,
        // MEM-28①：分类只在**插入分支**算（null = 还没算）。同一段内容分类结果恒定（纯函数），
        // 重复复制（最常见路径）沿用库里那一行的值即可 —— 11 条正则 × 最多 8KB 的扫描不必每次做。
        category: String? = null,
        isFavorite: Boolean = false,
    ): Long {
        if (content.isBlank()) return -1
        val hash = stableHash(content)
        val existingId = findIdByHash(hash)
        if (existingId != null) {
            // 试解只在「本进程还没验证过这一行」时做（BUG.md L-199）：命中哈希是重复复制
            // 最常见的路径，为发现极少数坏行让每次都整行解密不划算。没验证过的行仍会真解一次，
            // 所以坏行的自愈机会照旧（BUG.md L-173）。
            val unreadable = hash !in verifiedReadable && !decryptsById(existingId)
            val values = ContentValues()
            if (unreadable) {
                // 坏行自愈：用本次的新内容重加密覆盖，行 id 不变（BUG.md L-173）
                val reEncrypted = ClipboardCrypto.encrypt(content) ?: return -1
                values.put("encrypted_content", reEncrypted)
            }
            // 可解与否都要更新元数据 + 置顶；收藏标记是用户主动状态，不覆盖
            values.put("content_type", contentType)
            values.put("created_at", System.currentTimeMillis())
            values.put("source_package", sourcePackage)
            values.put("source_app_name", sourceAppName)
            // 没显式给分类就不动这一列（判重命中 = 同内容已在库，值必然一致；重分类另走 reclassifyAll）
            if (category != null) values.put("category", category)
            val rows = writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(existingId.toString()))
            if (rows > 0) {
                if (unreadable) {
                    Diagnostics.w(TAG, "重加密自愈: id=$existingId（原密文解不开，已用新内容覆盖）")
                }
                rememberReadable(hash)
                return existingId
            }
            // 命中哈希却一行未改 ⇒ 那一行已经不在库里。这时**不能**再 return existingId：
            // 它是幽灵 id —— 调用方只把 -1 当失败，会以为「已入库」而库里什么都没有（BUG.md L-200）。
            Diagnostics.w(TAG, "命中哈希但行已消失: id=$existingId，改走插入")
        }
        // 到这一步才加密：上面的判重分支不需要密文（BUG.md L-182）
        val encrypted = ClipboardCrypto.encrypt(content) ?: return -1
        // 也是到这一步才分类（MEM-28①）：判重命中走不到这里，重复复制就不必白跑 11 条正则
        val resolvedCategory = category ?: ClipboardClassifier.classify(content)
        val values = ContentValues().apply {
            put("encrypted_content", encrypted)
            put("content_type", contentType)
            put("created_at", System.currentTimeMillis())
            put("source_package", sourcePackage)
            put("source_app_name", sourceAppName)
            put("content_hash", hash)
            put("category", resolvedCategory)
            put("is_favorite", if (isFavorite) 1 else 0)
        }
        val id = writableDatabase.insert(TABLE_ITEMS, null, values)
        if (id > 0) {
            // 刚写入的行当然可解：登记备忘，下一次同内容复制不必再试解（BUG.md L-199）
            rememberReadable(hash)
            trimTo(maxItems, ClipboardPrefs.of(appContext).maxTotalBytes)
        }
        return id
    }

    /**
     * 图片入库（去重置顶语义与 [upsert] 一致，@Synchronized 串行）。
     *
     * 与文本路径的三处关键差异：
     *  - 正文不在列里：`encrypted_content` 只存**占位密文**（调用方加密好的 `img:<hex>` 串），
     *    真正的内容在 `filesDir/clipboard/<hex>.enc`（调用方写），本方法只维护列；
     *  - 不去试解既有行：图片行的可读性由文件决定、与 Keystore 状态无关，没有「坏行自愈」要判
     *    （文件层的自愈是调用方按同路径覆盖写）；
     *  - 占位密文与元数据列在命中与插入两条路径上都**整组覆盖**：重复复制即刷新（幂等）。
     *
     * @return 条目 id（既有行或新行）；入参非法 / 写库失败返回 -1
     */
    @Synchronized
    fun upsertImage(
        hash: String,
        placeholder: String,
        imageBytes: Long,
        width: Int,
        height: Int,
        mime: String,
        sourcePackage: String,
        sourceAppName: String,
    ): Long {
        if (hash.isEmpty() || placeholder.isEmpty()) return -1
        val values = ContentValues().apply {
            put("content_type", CONTENT_TYPE_IMAGE)
            put("encrypted_content", placeholder)
            put("created_at", System.currentTimeMillis())
            put("source_package", sourcePackage)
            put("source_app_name", sourceAppName)
            put("image_bytes", imageBytes)
            put("image_w", width)
            put("image_h", height)
            put("image_mime", mime)
        }
        val existingId = findIdByHash(hash)
        if (existingId != null) {
            // 收藏标记是用户主动状态，重复复制不覆盖（与文本 upsert 同一条不变量）
            val rows = writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(existingId.toString()))
            if (rows > 0) return existingId
            // 命中哈希却一行未改 ⇒ 行已不在库里（外部改库 / 库被换）：落到插入路径重建（BUG.md L-200 同款）
            Diagnostics.w(TAG, "图片命中哈希但行已消失: id=$existingId，改走插入")
        }
        values.put("content_hash", hash)
        // 图片不进 URL/NUMBER 多标签体系（那是文本片段的分类，见 ClipboardClassifier）；
        // 图片恒 OTHER，查询侧按 content_type 维度过滤
        values.put("category", ClipboardClassifier.CATEGORY_OTHER)
        values.put("is_favorite", 0)
        val id = writableDatabase.insert(TABLE_ITEMS, null, values)
        if (id > 0) {
            val prefs = ClipboardPrefs.of(appContext)
            trimTo(prefs.maxItems, prefs.maxTotalBytes)
        }
        return id
    }

    /**
     * 全部图片行的哈希集（孤儿文件 GC 用；只读一列、不解密）。
     *
     * 走 `idx_items_ctype`（只扫图片行），常态几百条。
     */
    fun imageHashes(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT content_hash FROM $TABLE_ITEMS WHERE content_type = ?",
            arrayOf(CONTENT_TYPE_IMAGE),
        ).use { c ->
            while (c.moveToNext()) {
                val h = c.getString(0)
                if (!h.isNullOrEmpty()) out.add(h)
            }
        }
        return out
    }

    /** 全部图片行的 (id, hash)（「有行无文件」死行清理用） */
    fun imageRows(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT id, content_hash FROM $TABLE_ITEMS WHERE content_type = ?",
            arrayOf(CONTENT_TYPE_IMAGE),
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to (c.getString(1) ?: ""))
        }
        return out
    }

    /**
     * 该行的密文现在还能解开吗（坏行自愈的前置判据，BUG.md L-173）。
     *
     * 解不开或行已不在都返回 false，两种原因由**调用方**区分：解不开 ⇒ 用新内容重加密覆盖（自愈）；
     * 行已不在 ⇒ `update` 影响 0 行，落到插入路径重建，**不返回那一行的 id**（幽灵 id，BUG.md L-200）。
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
    /**
     * 写路径统一监视器（BUG-04）：本类此前只有 [upsert] 与 [insertRestored] 同步，其余写方法
     * 各自「读一遍再改」（按条数 / 体积裁剪、按明文匹配删、收藏上限淘汰）—— 并发于入库时
     * 会删掉刚插入的行、或在同一份预算上各算一次。所有改动行集的方法与入库共用同一把锁。
     *（[reclassifyAll] 不长持锁：它的每条 UPDATE 自身原子，且整趟是全库解密级的秒级任务，
     * 持锁会把用户此刻的复制保存一起拖住。）
     */
    @Synchronized
    fun delete(id: Long): Boolean =
        writableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString())) > 0

    /**
     * 删除全部历史：收藏是受保护条目，清空只删除非收藏记录。
     * @return 删除的条数
     */
    // 写路径统一监视器：说明见 delete 上方那段（BUG-04）
    @Synchronized
    fun deleteAll(): Int =
        writableDatabase.delete(
            TABLE_ITEMS,
            "is_favorite = 0",
            null,
        )

    /**
     * 按**明文内容**删除历史条目（「凭据不留痕」，2026-09-30 加固防泄露）。
     *
     * 背景：用户从密码管理器 / 云控制台复制 API Key 的那一刻，本应用的剪贴板监听已经把那条内容
     * 采集入库 —— 库里虽是密文，但**剪贴板面板里明文可见**，还会随配置备份整体导出。
     * 凭据设置页在保存后会调用本方法把它从历史里抹掉。
     *
     * 口径：哈希命中即删（与入库去重同一个 [stableHash]）；**收藏条目不删**（用户明确标记要留的）；
     * 内容为空返回 0。调用方应放到后台线程（有 DB 写）。
     *
     * @return 删除条数（0 = 历史里没有这条）
     */
    // 写路径统一监视器：说明见 delete 上方那段（BUG-04）
    @Synchronized
    fun deleteByPlaintext(text: String): Int {
        if (text.isEmpty()) return 0
        return writableDatabase.delete(
            TABLE_ITEMS,
            "content_hash = ? AND is_favorite = 0",
            arrayOf(stableHash(text)),
        )
    }

    /**
     * 按**清洗后相等**删除历史条目（「凭据不留痕」的补强，2026-10-01 修复 L-239）。
     *
     * 为什么不能只靠 [deleteByPlaintext]：用户从网页 / 控制台复制的 Key 常带不可见字符
     * （NBSP / ZWSP / BOM），监听采集入库的是**那份原文**；而凭据保存进 Prefs 时会先经
     * `cleanCredential()` 剥掉杂质 —— 两端形态不同，精确哈希必然对不上，一条都删不掉，
     * Key 就留在面板里（明文可见）并随备份导出。而这恰好是本功能要覆盖的场景。
     *
     * 口径：只删**未收藏**、且「剥掉不可见字符并 trim 后」与目标相等的条目。比精确哈希宽松
     * 一档，代价是可能连带删掉「只差不可见字符」的同内容条目 —— 那正是要清理的东西。
     * 有 DB 读 + 解密，调用方放在后台线程（与 [deleteByPlaintext] 同一处调用）。
     *
     * @return 删除条数（0 = 历史里没有等价条目）
     */
    // 写路径统一监视器：说明见 delete 上方那段（BUG-04）
    @Synchronized
    fun deleteByCleanedPlaintexts(targets: Collection<String>): Int {
        val wanted = targets
            .mapNotNull { it.cleanCredential().takeIf { s -> s.isNotEmpty() } }
            .toHashSet()
        if (wanted.isEmpty()) return 0
        val ids = ArrayList<Long>()
        readableDatabase.query(
            TABLE_ITEMS,
            arrayOf("id", "encrypted_content"),
            "is_favorite = 0",
            null,
            null,
            null,
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val plain = ClipboardCrypto.decrypt(c.getString(1)) ?: continue
                if (plain.cleanCredential() in wanted) ids.add(c.getLong(0))
            }
        }
        if (ids.isEmpty()) return 0
        var removed = 0
        writableDatabase.beginTransaction()
        try {
            for (id in ids) {
                removed += writableDatabase.delete(
                    TABLE_ITEMS,
                    "id = ? AND is_favorite = 0",
                    arrayOf(id.toString()),
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return removed
    }

    /**
     * 目标明文 → **仍因收藏被保留**的等价条目数（2026-10-04 修复 L-592）。
     *
     * 为什么要单独问一次：上面两个删除接口都带 `is_favorite = 0`（收藏是用户明确要留的），
     * 于是「删了 0 条」有两种含义 —— 「历史里没有」与「有、但被收藏保护」。调用方原本分不出来，
     * 把第二种也当成「已清干净」并推进水位 ⇒ 该凭据在本进程内**永不重扫**，Key 明文长期留在
     * 面板里（可列、可搜索、一键粘贴）并随配置备份明文外发，而日志只说「本轮无需删除」。
     *
     * 只扫收藏行（用户手工标记的几条），一次遍历判定全部目标；判据与清洗遍同源
     * （`cleanCredential()` 后相等 —— 它是精确相等的超集）。
     *
     * @return 清洗后的目标值 → 保留条数；没有保留的键不出现在结果里
     */
    fun favoriteKeptCounts(targets: Collection<String>): Map<String, Int> {
        val wanted = targets.mapNotNull { it.cleanCredential().takeIf { s -> s.isNotEmpty() } }.toHashSet()
        if (wanted.isEmpty()) return emptyMap()
        val kept = HashMap<String, Int>()
        readableDatabase.query(
            TABLE_ITEMS,
            arrayOf("encrypted_content"),
            "is_favorite = 1 AND content_type = ?",
            arrayOf(CONTENT_TYPE_TEXT),
            null,
            null,
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val plain = ClipboardCrypto.decrypt(c.getString(0)) ?: continue
                val cleaned = plain.cleanCredential()
                if (cleaned in wanted) kept[cleaned] = (kept[cleaned] ?: 0) + 1
            }
        }
        return kept
    }

    /**
     * 当前历史里最大的条目 id（0 = 空表）。
     *
     * 「凭据不留痕」用它判断「上次清理之后历史里是否又出现了新条目」：有新条目就重扫，
     * 没有就跳过 —— 既收敛，又不会漏掉用户**再次复制**的同一个 Key（2026-10-01 修复 L-254）。
     */
    fun maxItemId(): Long =
        readableDatabase.rawQuery("SELECT MAX(id) FROM $TABLE_ITEMS", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /** 更新收藏状态 */
    // 写路径统一监视器：说明见 delete 上方那段（BUG-04）
    @Synchronized
    fun setFavorite(id: Long, favorite: Boolean): Boolean {
        val values = ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) }
        return writableDatabase.update(TABLE_ITEMS, values, "id = ?", arrayOf(id.toString())) > 0
    }

    /**
     * 裁剪历史：先按条数裁到 [maxItems]，再按总体积裁到 [maxTotalBytes]，最后收一遍收藏软上限。
     *
     * 裁剪优先级：只取非收藏的最旧记录（见 [TRIM_PRIORITY]）。
     *
     * 任一维度剩的全是收藏、删不到目标时即停止 ，
     * 宁可超出上限，也不删用户明确标记保留的内容。
     *
     * ⚠ 唯一的例外是 [trimFavorites]：收藏**自身**的软上限（用户显式设置，见
     * [ClipboardPrefs.favoriteMaxItems]）超限时会淘汰最旧收藏 —— 那不是总预算的溢出，
     * 而是用户对「收藏能留多少」的直接约定，两条不变量不冲突。
     */
    // 写路径统一监视器：说明见 delete 上方那段（BUG-04）
    @Synchronized
    fun trimTo(maxItems: Int, maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES) {
        trimByCount(maxItems)
        trimByByteBudget(maxTotalBytes)
        trimImages()
        trimFavorites()
    }

    /**
     * 图片预算裁剪：张数 / 字节任一超限即淘汰**最旧的非收藏图片**（用户可调，见 [ClipboardPrefs]）。
     *
     * 与文本预算**完全分离**（实施计划 §2.4）：文本的条数/字节只统计与裁剪文本行
     * （见 [trimByCount] / [trimByByteBudget]），图片同理只统计自己 —— 否则「复制几张图」
     * 会把文本历史挤空，或反过来图片被文本预算连带删掉。
     *
     * 图片行常态只有几百条，直接全取（不像文本那条 hot path 需要先算总量短路）。
     * 走 `idx_items_ctype`（只扫图片行）。
     */
    private fun trimImages() {
        val prefs = ClipboardPrefs.of(appContext)
        val rows = ArrayList<ClipboardRowSize>()
        readableDatabase.rawQuery(
            "SELECT id, image_bytes, is_favorite FROM $TABLE_ITEMS WHERE content_type = ? " +
                "ORDER BY created_at ASC",   // 最旧在前：淘汰顺序即此序
            arrayOf(CONTENT_TYPE_IMAGE),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(ClipboardRowSize(c.getLong(0), c.getLong(1), c.getInt(2) != 0, image = true))
            }
        }
        val ids = imageOverflowIds(rows, prefs.imageMaxItems, prefs.imageMaxBytes)
        if (ids.isEmpty()) return
        deleteNonFavoriteByIds(ids)
        Diagnostics.i(TAG, "按图片预算裁剪: 删除 ${ids.size} 条非收藏图片")
    }

    /**
     * 收藏软上限：条数 / 字节任一超限即淘汰**最旧**收藏（用户可调，见 [ClipboardPrefs]）。
     *
     * ⚠ 删除必须走 [deleteAnyByIds]（含收藏），**不能**用普通裁剪入口 [deleteNonFavoriteByIds]：
     * 后者的 SQL 固定带 `is_favorite = 0`，用它删收藏一行都删不掉，而日志却按候选集报
     * 「删除最旧收藏 N 条」⇒ 功能静默失效 + 日志谎报（2026-10-06 审查实证，由守卫钉住）。
     */
    private fun trimFavorites() {
        val prefs = ClipboardPrefs.of(appContext)
        val favRows = ArrayList<ClipboardRowSize>()
        // 字节口径：图片行按真实体积（image_bytes）算，文本行按密文长度 —— 否则一张收藏图
        // 只占 ~百字节（占位密文），收藏字节上限对图片形同虚设。收藏行只有几条~千条量级，CASE 无性能顾虑。
        readableDatabase.rawQuery(
            "SELECT id, CASE WHEN content_type = ? THEN image_bytes " +
                "ELSE LENGTH(encrypted_content) END FROM $TABLE_ITEMS WHERE is_favorite = 1 " +
                "ORDER BY created_at ASC",
            arrayOf(CONTENT_TYPE_IMAGE),
        ).use { c -> while (c.moveToNext()) favRows.add(ClipboardRowSize(c.getLong(0), c.getLong(1), true)) }
        val ids = favoriteOverflowIds(favRows, prefs.favoriteMaxItems, prefs.favoriteMaxBytes)
        if (ids.isEmpty()) return
        // 按**实际删除行数**记账：候选集是快照，这几毫秒里用户可能已取消收藏或删除该行
        val deleted = deleteAnyByIds(ids)
        Diagnostics.i(TAG, "收藏软上限淘汰: 候选 ${ids.size} 条，实删 $deleted 条")
    }

    /** 按条数裁剪：只统计与删除**文本**行（图片有独立张数上限，见 [trimImages]） */
    private fun trimByCount(maxItems: Int) {
        if (maxItems <= 0) return
        val count = countByContentType(CONTENT_TYPE_TEXT)
        if (count <= maxItems) return
        val overflow = count - maxItems

        // 按优先级分轮取证：先删最旧的非收藏记录
        val ids = ArrayList<Long>(overflow)
        for (where in TRIM_PRIORITY) {
            if (ids.size >= overflow) break
            val need = overflow - ids.size
            readableDatabase.rawQuery(
                "SELECT id FROM $TABLE_ITEMS WHERE $where AND content_type = ? " +
                    "ORDER BY created_at ASC LIMIT $need",
                arrayOf(CONTENT_TYPE_TEXT),
            ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        }
        if (ids.isEmpty()) return // 剩余全是收藏，不裁剪
        deleteNonFavoriteByIds(ids)
    }

    /** 按类型计数（图片的条数/字节预算与文本分离，各自只统计自己那一类） */
    private fun countByContentType(type: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_ITEMS WHERE content_type = ?",
            arrayOf(type),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

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
        // 这一句由 v7 的表达式索引兜着：走覆盖索引扫描（只读长度，不读内容页），
        // 实测 2 万行从 17.55ms 降到 1.18ms（见 DB_VERSION 注释）。
        // ⚠ 这一句**故意不加** `WHERE content_type = 'text'`：加了它 SQLite 会改走 idx_items_ctype
        // 再逐行读内容页算长度，v7 那条覆盖索引就作废了（17.55ms 级）。图片行的 encrypted_content
        // 只是 ~百字节的占位密文，计进总量可忽略；真正的图片体积在 image_bytes 列、由 trimImages 管，
        // 下面的候选集把图片行**跳过**（不参与文本淘汰）。
        val total = readableDatabase.rawQuery(
            "SELECT SUM(LENGTH(encrypted_content)) FROM $TABLE_ITEMS", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (total <= maxTotalBytes) return
        val rows = ArrayList<ClipboardRowSize>()
        readableDatabase.rawQuery(
            "SELECT id, LENGTH(encrypted_content), is_favorite, content_type FROM $TABLE_ITEMS " +
                "ORDER BY created_at ASC",   // 最旧在前：淘汰顺序即此序
            null
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    ClipboardRowSize(
                        c.getLong(0), c.getLong(1), c.getInt(2) != 0,
                        image = c.getString(3) == CONTENT_TYPE_IMAGE,
                    )
                )
            }
        }
        val ids = overflowIdsForByteBudget(rows, maxTotalBytes)
        if (ids.isEmpty()) {
            // 可删的只剩收藏（收藏计入总量但不参与淘汰）⇒ 库会**长期停在预算之上**。
            // 这种情况原先一条日志都没有：出问题时既看不到现象也看不到原因（2026-09-30 审查发现）
            Diagnostics.w(
                TAG,
                "按体积裁剪停手: 可删条目已尽（收藏不计淘汰），当前 ${total / 1024 / 1024}MB " +
                    "仍超预算 ${maxTotalBytes / 1024 / 1024}MB",
            )
            return
        }
        deleteNonFavoriteByIds(ids)
        Diagnostics.i(TAG, "按体积裁剪: 删除 ${ids.size} 条非收藏记录（预算 ${maxTotalBytes / 1024 / 1024}MB）")
    }

    /**
     * 批量删除（单事务）；**只删非收藏**（`is_favorite = 0`）。
     *
     * 名字把约束写进调用点：要删收藏走 [deleteAnyByIds]，误用本方法会静默删 0 行。
     */
    private fun deleteNonFavoriteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            // 条件里再判一次收藏（2026-09-30 审查）：候选集是按快照算的，用户在这几毫秒里把某条
            // 标成收藏后，原写法仍会把它删掉 —— 而「收藏不参与裁剪」是本模块的不变量。少删一条无害
            // （下次入库会重新评估），删错一条则不可恢复。
            for (id in ids) {
                writableDatabase.delete(TABLE_ITEMS, "id = ? AND is_favorite = 0", arrayOf(id.toString()))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** 管理页专用：删除任意行（含收藏），不受「收藏不参与裁剪」那条不变量限制 */
    // 写路径统一监视器：说明见 delete 上方那段（BUG-04）
    @Synchronized
    fun deleteAnyByIds(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        var n = 0
        writableDatabase.beginTransaction()
        try {
            for (id in ids) n += writableDatabase.delete(TABLE_ITEMS, "id = ?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return n
    }

    /** 按分类标签删（LIKE 命中即删，不动收藏）；传 null 删全部非收藏行 */
    // 写路径统一监视器：说明见 delete 上方那段（BUG-04）
    @Synchronized
    fun deleteByCategory(tag: String?): Int {
        val where = if (tag == null) "is_favorite = 0" else "is_favorite = 0 AND category LIKE ?"
        val args = if (tag == null) null else arrayOf("%$tag%")
        return writableDatabase.delete(TABLE_ITEMS, where, args)
    }

    /**
     * 按内容类型删（不动收藏）：历史页「删图片」用。
     *
     * 注意与 [deleteByCategory] 的分工：`deleteByCategory(null)`（清空）删的是**全部非收藏行**
     * （文本 + 图片，用户预期「清空历史」就是清空）；本方法只删某一类的行。
     * 两条路径删掉的图片文件：单条删除走 `deleteFor`（精准），批量走 `gc`（短保护窗）——
     * 都不在本层做（DB 只管行、不碰文件）。
     */
    // 写路径统一监视器：说明见 delete 上方那段（BUG-04）
    @Synchronized
    fun deleteByContentType(type: String): Int {
        if (type.isEmpty()) return 0
        return writableDatabase.delete(
            TABLE_ITEMS,
            "is_favorite = 0 AND content_type = ?",
            arrayOf(type),
        )
    }

    // ── 查询 ──────────────────────────────────────────────

    /**
     * SELECT 列清单（分页查询与历史接口共用）。
     *
     * ⚠ [readItem] 的下标与本清单**同序**：追加列一律排在末尾（图片元数据 9–12），
     * 中间插列会静默错位（读出来的是别的列的值）。
     */
    private val selectCols = "id, encrypted_content, content_type, created_at, source_package, " +
        "source_app_name, content_hash, category, is_favorite, " +
        "image_bytes, image_w, image_h, image_mime"

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
        contentType: String? = null,
    ): Pair<String, Array<String>> {
        val where = StringBuilder("1 = 1")
        val args = ArrayList<String>(2)
        if (favoritesOnly) where.append(" AND is_favorite = 1")
        if (category != null) {
            // 多标签（2026-09-25）：category 存的是「含哪些片段」（如 `URL,NUMBER`），
            // 命中条件是**含**该标签而非等值。标签集固定且互不包含（URL / NUMBER / OTHER），
            // 所以 `LIKE '%URL%'` 不会误命中别的标签；旧库的单值（"URL"）同样被覆盖，无需迁移。
            where.append(" AND category LIKE ?")
            args.add("%$category%")
        }
        if (contentType != null) {
            // 内容类型维度（图片分类 / 搜索排除图片）：与 category 正交，图片恒 category=OTHER
            where.append(" AND content_type = ?")
            args.add(contentType)
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
        contentType: String? = null,
    ): KeyedPage {
        if (limit <= 0) return KeyedPage(emptyList(), 0, cursor)
        val (where, args) = whereClause(category, favoritesOnly, contentType)
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

    /** 记录总数（纯 SQL 计数，不解密；可按分类/收藏/内容类型过滤） */
    fun count(
        category: String? = null,
        favoritesOnly: Boolean = false,
        contentType: String? = null,
    ): Int {
        val (where, args) = whereClause(category, favoritesOnly, contentType)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_ITEMS WHERE $where",
            args
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 总条数（无过滤，等价 count(null, false)，供旧调用方兼容） */
    fun count(): Int = count(null, false)

    /**
     * 这批 id 里还有多少行在库里（导入计数用，BUG-13）。
     *
     * 导入要报「实际进库多少」时不能用「前后总数之差」：采集常开，导入期间用户的每次复制
     * 都会改动总数，差值会把**并发条目**算进本次导入（界面数字虚高）。只查本次插入的 id，
     * 与并发无关；顺带把「刚插入就被裁剪掉」的那部分量出来（旧时间戳的行在本机库满时最旧）。
     *
     * 分块查询：SQLite 的变量上限是 999（老版本更低），块大小取 400 留足余量。
     */
    @Synchronized
    fun countPresent(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        var n = 0
        for (chunk in ids.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_ITEMS WHERE id IN ($marks)",
                chunk.map { it.toString() }.toTypedArray(),
            ).use { c -> if (c.moveToFirst()) n += c.getInt(0) }
        }
        return n
    }

    /**
     * 导出用的库指纹：条数 / 最大 id / 密文总长，一次查询取三样。
     *
     * 只比对条数会在**到上限后的插入 + 淘汰**（`upsert` → `trimTo` 成对发生、总数不变，其 KDoc 自述）
     * 时判成「库没变」：那一趟导出的载荷既漏掉新复制的条目、也不会进丢弃计数，界面却按
     * 「全部导出成功」上报（BUG.md L-1085）。三者任一变化都说明库在动 —— 插入抬最大 id、
     * 删除或改写改总长，重复复制已有内容走 UPDATE 也会改总长。
     */
    fun exportStamp(): String =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(MAX(id), 0), IFNULL(SUM(LENGTH(encrypted_content)), 0) FROM $TABLE_ITEMS",
            null,
        ).use { c -> if (c.moveToFirst()) "${c.getLong(0)}:${c.getLong(1)}:${c.getLong(2)}" else "0:0:0" }

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
            val rows = ArrayList<Triple<Long, String, String>>(RECLASSIFY_PAGE)
            readableDatabase.rawQuery(
                // 只重算文本行：图片行的 category 恒 OTHER（图片不进 URL/NUMBER 标签体系），
                // 且它的 encrypted_content 是占位密文 —— 解密它、再对 `img:<hex>` 跑分类纯属白付
                "SELECT id, encrypted_content, content_hash FROM $TABLE_ITEMS " +
                    "WHERE id > ? AND content_type = ? ORDER BY id LIMIT $RECLASSIFY_PAGE",
                arrayOf(lastId.toString(), CONTENT_TYPE_TEXT),
            ).use { c -> while (c.moveToNext()) rows.add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
            if (rows.isEmpty()) return changed
            lastId = rows.last().first
            writableDatabase.beginTransaction()
            try {
                for ((id, encrypted, hash) in rows) {
                    val text = ClipboardCrypto.decrypt(encrypted)
                    if (text == null) {
                        // 与 readItem 同口径：解不开就撤销备忘（BUG.md L-199）
                        forgetReadable(hash)
                        continue
                    }
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
        if (ClipboardStore.exceedsItemLimit(content, ClipboardPrefs.of(appContext).effectiveMaxItemBytes().toInt())) return -1
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
        val id = writableDatabase.insert(TABLE_ITEMS, null, values)
        // 恢复进来的密文可能来自别的设备 / 别的密钥（解不开是常态），而备忘里可能还留着
        // 「恢复前这个哈希可解」的结论 ⇒ 整体作废，让下一次命中重新试解（BUG.md L-173 / L-199）
        if (id > 0) verifiedReadable.clear()
        return id
    }

    // ── 内部 ──────────────────────────────────────────────

    private fun readItem(c: android.database.Cursor): Item? {
        val id = c.getLong(0)
        val contentType = c.getString(2)
        val hash = c.getString(6)
        // 图片行**不解密**（列里只是占位密文）：可显示性由「文件在不在」决定（文件按 hash 派生），
        // 与 Keystore 状态解耦 —— 密钥失效后网格显示灰块，但行不会从列表里消失。
        // `content` 恒为空串：搜索匹配 / 历史页渲染 / 备份导出 / 明文预算都把它当文本，空串让
        // 这些既有路径自然降级（不命中、不占预算、不导出），见实施计划 §2.2 契约。
        if (contentType == CONTENT_TYPE_IMAGE) {
            return Item(
                id = id,
                content = "",
                contentType = contentType,
                createdAt = c.getLong(3),
                sourcePackage = c.getString(4),
                sourceAppName = c.getString(5),
                contentHash = hash,
                category = c.getString(7),
                isFavorite = c.getInt(8) != 0,
                image = ImageMeta(
                    hash = hash,
                    width = c.getInt(10),
                    height = c.getInt(11),
                    bytes = c.getLong(9),
                    mime = c.getString(12) ?: "",
                ),
            )
        }
        val encrypted = c.getString(1)
        val content = ClipboardCrypto.decrypt(encrypted) ?: run {
            // 解不开就撤销备忘：否则下一次同内容复制会因「已验证」跳过试解，白丢一次自愈机会
            // （BUG.md L-173 与 L-199 的边界）
            forgetReadable(hash)
            Diagnostics.w(TAG, "解密失败，跳过 id=$id")
            return null
        }
        rememberReadable(hash)
        return Item(
            id = id,
            content = content,
            contentType = contentType,
            createdAt = c.getLong(3),
            sourcePackage = c.getString(4),
            sourceAppName = c.getString(5),
            contentHash = hash,
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
        /**
         * v7：给「密文总长」加**表达式索引** `LENGTH(encrypted_content)`（性能优化清单 MEM-06）。
         *
         * `trimByByteBudget` 每次入库都要 `SELECT SUM(LENGTH(encrypted_content))`（hot path），
         * 而该列是整条密文（base64），全表扫要读全部内容页。表达式索引只存长度（约十几字节一行）：
         * 本机 SQLite 实测 2 万行 / 8.7MB 的表，同一条 SQL 从 `SCAN items` 17.55ms 降到
         * `SCAN items USING COVERING INDEX idx_items_bytes` 1.18ms，库体积 +2.3%，插入无可测代价。
         *
         * 与 v6 同理：`onCreate` 只覆盖全新安装，已装机库必须配迁移分支。
         */
        /**
         * v8：图片支持。加四条图片元数据列（`image_bytes/w/h/mime`，全部 NOT NULL DEFAULT，
         * 老行天然合法）+ 内容类型索引 `idx_items_ctype`。
         *
         * 索引的依据：图片的全部查询都按 `content_type` 过滤（条数计数 / 字节预算 / GC 取哈希集 /
         * 死行清理），而图片行在库中占比很小 —— 无索引时每次图片入库的裁剪都要全表扫
         * （2 万行量级，读 created_at / image_bytes / is_favorite 三列）。索引让这几条查询
         * 退化成「只扫图片行」（本机实测图片行 300 条）。
         *
         * ⚠ 列名/索引名与 `onCreate` 必须同源：两处写法漂移会让「全新安装」与「升级安装」
         * 的 schema 不一致（v4 的 content_hash 唯一索引就踩过，见 onCreate 里的注释）。
         */
        private const val DB_VERSION = 8
        private const val TABLE_ITEMS = "clipboard_items"

        /** [verifiedReadable] 的上限：超过就整体清空（备忘只是加速，不是正确性依赖） */
        private const val VERIFIED_MEMO_MAX = 512
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
                // 图片行不参与文本字节淘汰（体积走 image 预算；这里它的 bytes 只是占位密文长度）
                if (row.image) continue
                out.add(row.id)
                total -= row.bytes
            }
            return out
        }

        /**
         * 图片预算的淘汰候选（纯函数）。
         *
         * 与 [favoriteOverflowIds] 的分工：这里收藏**不参与**淘汰（与文本总预算同一条不变量：
         * 收藏永不因容量被删）；与 [overflowIdsForByteBudget] 的分工：条数 / 字节两个上限
         * **同时**生效（文本侧只有字节上限，条数走 [ClipboardDb.trimByCount]）。
         *
         * @param rows 必须按最旧在前传入（查询侧即 `ORDER BY created_at ASC`）
         * @param maxItems 张数上限；`<= 0` 视为不限制
         * @param maxBytes 体积上限；`<= 0` 视为不限制
         */
        fun imageOverflowIds(rows: List<ClipboardRowSize>, maxItems: Int, maxBytes: Long): List<Long> {
            if (rows.isEmpty()) return emptyList()
            val itemCap = if (maxItems > 0) maxItems else rows.size
            val byteCap = if (maxBytes > 0) maxBytes else Long.MAX_VALUE
            var kept = rows.size
            var bytes = rows.sumOf { it.bytes }
            if (kept <= itemCap && bytes <= byteCap) return emptyList()
            val out = ArrayList<Long>()
            for (row in rows) {
                if (kept <= itemCap && bytes <= byteCap) break
                if (row.favorite) continue
                out.add(row.id)
                kept--
                bytes -= row.bytes
            }
            return out
        }

        /**
         * 收藏软上限的淘汰候选（纯函数）。
         *
         * 与 [overflowIdsForByteBudget] 的分工：这里收藏**参与**淘汰（软上限就是为它设的，
         * 见 [ClipboardPrefs.favoriteMaxItems]），条数 / 字节任一超限即从最旧开始删；
         * 那边则「收藏计入总量但不参与淘汰」（收藏永不因总预算被删）。
         *
         * @param rows 必须按最旧在前传入（查询侧即 `ORDER BY created_at ASC`）
         * @param maxItems 条数上限；`<= 0` 视为不限制
         * @param maxBytes 字节上限；`<= 0` 视为不限制
         * @return 应删除的 id（**含收藏**；调用方必须走 `deleteAnyByIds` 之外的含收藏删除入口）
         */
        fun favoriteOverflowIds(rows: List<ClipboardRowSize>, maxItems: Int, maxBytes: Long): List<Long> {
            if (rows.isEmpty()) return emptyList()
            val itemCap = if (maxItems > 0) maxItems else rows.size
            val byteCap = if (maxBytes > 0) maxBytes else Long.MAX_VALUE
            var kept = rows.size
            var bytes = rows.sumOf { it.bytes }
            if (kept <= itemCap && bytes <= byteCap) return emptyList()
            val out = ArrayList<Long>()
            for (row in rows) {
                if (kept <= itemCap && bytes <= byteCap) break
                out.add(row.id)
                kept--
                bytes -= row.bytes
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

        /** `content_type` 的两种取值（文本行 / 图片行；表的 DEFAULT 是文本） */
        const val CONTENT_TYPE_TEXT = "text"
        const val CONTENT_TYPE_IMAGE = "image"

        /**
         * 图片哈希前缀。
         *
         * 与文本哈希（裸 64 位 hex）**空间隔离**：两套哈希共用一个 `content_hash` 唯一索引，
         * 前缀保证「图片不会与某段文本撞 hash」（且按前缀可一眼分辨行类型，见 `ClipboardFileImporter`）。
         * 占位方案里的 `legacy:` 同款思路。
         */
        const val IMAGE_HASH_PREFIX = "img:"

        /** 稳定哈希：内容去重与来源追踪用（不暴露原文） */
        fun stableHash(text: String): String = hex(sha256(text.toByteArray(Charsets.UTF_8)))

        /**
         * 图片哈希：对**入库原字节**取 sha256（带 [IMAGE_HASH_PREFIX]）。
         *
         * 「入库原字节」是唯一定义点：采集读到什么字节就哈希什么字节，粘贴/导出时也是这份字节 ——
         * 同一张图重复复制（同一 provider 同一份数据）必然命中；不同来源的转码版（有损重压）
         * 字节不同、各成一条，这是有意的（不做感知哈希，见实施计划 §8）。
         */
        fun imageHash(bytes: ByteArray): String =
            IMAGE_HASH_PREFIX + hex(sha256(bytes))

        private fun sha256(bytes: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
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
 * 按体积淘汰时的一行信息：id、生效字节数、是否收藏、是否图片行。
 *
 * [bytes] 的含义按行类型分：文本行 = 密文长度（base64 纯 ASCII，字符数即字节数）；
 * 图片行 = 真实文件体积（`image_bytes`）。
 *
 * 与 [ClipboardFilter] 一样做成顶层类：它同时是 [ClipboardDb.overflowIdsForByteBudget] /
 * [ClipboardDb.imageOverflowIds] 的入参类型，纯数据、可直接 JVM 单测。
 */
data class ClipboardRowSize(
    val id: Long,
    val bytes: Long,
    val favorite: Boolean,
    /** 图片行：文本的字节预算候选集里要跳过它（图片体积由 image 预算单独管） */
    val image: Boolean = false,
)

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
    /** 传给 SQL 的 category 值；收藏/图片这类伪分类此处为 null */
    val category: String?,
    val favoritesOnly: Boolean,
    /**
     * 内容类型维度（`image` = 图片分类；null = 不限）。
     *
     * 与 [category] **正交**：图片恒 `category='OTHER'`（不进 URL/NUMBER 标签体系），
     * 图片分类靠这一维过滤。搜索路径也用它（传 `text` 排除图片，见 SearchPanelView）。
     */
    val contentType: String? = null,

) {
    companion object {

        /** 伪分类：收藏（独立标签列，非 category 取值） */
        const val PSEUDO_FAVORITE = "FAVORITE"

        /**
         * 伪分类：图片（走 `content_type` 列，与 category 正交）。
         *
         * 与 [PSEUDO_FAVORITE] 同款职责：分类栏的选中值不能直接传给 category 列
         * （`WHERE category = 'IMAGE'` 恒不成立，列表永远是空的）。
         */
        const val PSEUDO_IMAGE = "IMAGE"


        /**
         * 由分类栏选中的值解析筛选条件。
         * @param raw null=全部；URL/NUMBER/OTHER=分类；FAVORITE/IMAGE=伪分类
         */
        fun of(raw: String?): ClipboardFilter = when (raw) {
            PSEUDO_FAVORITE -> ClipboardFilter(null, favoritesOnly = true)
            PSEUDO_IMAGE -> ClipboardFilter(null, favoritesOnly = false, contentType = ClipboardDb.CONTENT_TYPE_IMAGE)
            else -> ClipboardFilter(raw, favoritesOnly = false)
        }
    }
}
