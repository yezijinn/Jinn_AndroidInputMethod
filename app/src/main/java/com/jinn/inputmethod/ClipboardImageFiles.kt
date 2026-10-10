package com.jinn.inputmethod

import android.content.Context
import java.io.File

/**
 * 剪贴板图片的文件层：目录、命名、原子写读与孤儿清理。
 *
 * 布局：`filesDir/clipboard/<hex>.enc`（原字节密文）+ `<hex>.thumb`（缩略图密文），
 * `hex` = 条目 `content_hash` 去掉 [ClipboardDb.IMAGE_HASH_PREFIX] —— 文件名**由哈希派生**、不落库列：
 * ① 读路径不必信任列值（列坏了也能按 hash 找到文件）；② 重复复制按同路径覆盖写即是自愈；
 * ③ 备份/恢复只涉及哈希，不需要额外的引用映射。
 *
 * 与 [ClipboardDb] 的分工：DB 只管行（纯数据，不碰文件），文件生命周期全在本对象。
 * 删除路径有九条（单删 / 多删 / 清空 / 分类删 / 条数裁 / 字节裁 / 收藏裁 / 凭据清理 / 恢复覆盖），
 * 逐条挂钩必漏 ⇒ 采用**集中 GC**：比对目录与库内哈希，删「不在库里且超过保护窗」的文件。
 *
 * [GC_PROTECT_MS] 保护窗：采集写文件（`BackgroundIo.runLong`）与 GC（`run`）可能并发，
 * 刚落盘、行还没插进去的文件不能被当成孤儿删掉（与 `OptionalDicts.TMP_PROTECT_MS` 同思路）。
 * 写盘中的 `.tmp` 也在同一保护窗内，超窗后被 GC 收走 —— 不需要单独的残留清理器。
 */
internal object ClipboardImageFiles {

    /** 图片文件目录名（`filesDir` 下；与库同级、私有、随卸载清除） */
    const val DIR_NAME = "clipboard"

    private const val ENC_SUFFIX = ".enc"
    private const val THUMB_SUFFIX = ".thumb"
    private const val TMP_SUFFIX = ".tmp"

    /**
     * 孤儿保护窗：文件的最后修改时间距今不足这个时长的，一律不删。
     *
     * 10 分钟（与可选词库的 TEMP_PROTECT_MS 同量级）：WriteAtomic 的一次写盘是毫秒级，
     * 窗口只需要覆盖「文件已落盘、行还没插入」这一小段并发窗口；取大值只是让孤儿多留一会儿，
     * 下次 GC 仍会收走，不损失正确性。
     */
    const val GC_PROTECT_MS = 10 * 60 * 1000L

    /**
     * 用户主动删除（清空 / 删图片 / 多选删除）后那次 GC 的保护窗。
     *
     * 取 5 秒而不是 0：删除动作可能与「另一张图正在采集」并发（文件已落盘、行还没插），
     * 完全忽略保护窗会删掉在途文件、让刚入库的行指向缺失文件。采集写文件到入库的间隙是
     * 毫秒级（同一后台线程内），5 秒足够覆盖；而正常删除（文件早于 5 秒创建）能立即回收。
     * ⚠ 单条删除不走这里：它知道自己的 hash，用 [deleteFor] 精准删（没有并发窗口问题）。
     */
    const val DELETE_GC_PROTECT_MS = 5_000L

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 哈希 → 文件主名（纯 hex，不带 `img:` 前缀，也不带目录） */
    fun stem(hash: String): String = hash.removePrefix(ClipboardDb.IMAGE_HASH_PREFIX)

    fun encName(hash: String): String = stem(hash) + ENC_SUFFIX

    fun thumbName(hash: String): String = stem(hash) + THUMB_SUFFIX

    fun encFile(context: Context, hash: String): File = File(dir(context), encName(hash))

    fun thumbFile(context: Context, hash: String): File = File(dir(context), thumbName(hash))

    /**
     * 原子写：先写 `<name>.tmp` 再改名。
     *
     * 直接覆盖写会在写一半时被读到（渲染出半张图）。改名在同一文件系统内是原子的；
     * 目标已存在（重复复制刷新同 hash）时先删再改名 —— rename 不保证覆盖语义。
     */
    fun writeAtomic(file: File, bytes: ByteArray): Boolean = runCatching {
        val parent = file.parentFile ?: return false
        parent.mkdirs()
        val tmp = File(parent, file.name + TMP_SUFFIX)
        tmp.outputStream().use { it.write(bytes) }
        if (tmp.renameTo(file)) return true
        file.delete()
        if (tmp.renameTo(file)) return true
        tmp.delete()
        false
    }.getOrElse {
        Diagnostics.w(TAG, "图片文件写入失败: ${it.javaClass.simpleName}")
        false
    }

    fun readBytes(file: File): ByteArray? = runCatching {
        if (!file.isFile) return null
        file.readBytes()
    }.getOrElse {
        Diagnostics.w(TAG, "图片文件读取失败: ${it.javaClass.simpleName}")
        null
    }

    /** 删除一条图片的两个文件；返回实际删除数（0~2） */
    fun deleteFor(context: Context, hash: String): Int {
        val a = if (encFile(context, hash).delete()) 1 else 0
        val b = if (thumbFile(context, hash).delete()) 1 else 0
        return a + b
    }

    /**
     * 在途写盘的哈希（BUG.md L-1249）：从写盘到插行之间文件不在库里，回收若只按「文件修改时间
     * 是否落在保护窗内」判断，大图加密写盘一旦超过窗口就会被当成孤儿删掉。登记后 gc 的保留集
     * 直接含它，不再靠时间窗猜。
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 写盘开始前登记（与 [endWrite] 成对）：成功插行或失败清理后都要注销 */
    fun beginWrite(hash: String) {
        if (hash.isNotEmpty()) inFlight.add(hash)
    }

    /** 写盘流程收尾：文件已插行（或已被清理）后注销 */
    fun endWrite(hash: String) {
        inFlight.remove(hash)
    }

    /**
     * 应删除的孤儿文件名（纯函数，供守卫枚举场景）。
     *
     * @param existing 目录内现存文件（名字 → 最后修改毫秒）
     * @param keep 库内图片行的全部文件名（[encName]/[thumbName] 两种都在内）
     */
    fun staleNames(
        existing: List<Pair<String, Long>>,
        keep: Set<String>,
        nowMs: Long,
        protectMs: Long = GC_PROTECT_MS,
    ): List<String> = existing
        .filter { (name, mtime) -> name !in keep && nowMs - mtime >= protectMs }
        .map { it.first }

    /**
     * 清理孤儿文件；返回删除数。
     *
     * 调用点（三处 + 启动一次）：① 图片入库成功后（同线程，行已在库，保护窗兜住并发）；
     * ② 启动（`JinnIme` 降优先级线程一次）；③ 三类批量删除的回调（清空 / 删图片 / 删分类）。
     * `listFiles()` 自身会抛（目录不存在等）：整段 runCatching，GC 失败不影响业务。
     */
    fun gc(context: Context, db: ClipboardDb, protectMs: Long = GC_PROTECT_MS): Int = runCatching {
        val dir = dir(context)
        val existing = dir.listFiles()?.filter { it.isFile }?.map { it.name to it.lastModified() } ?: return 0
        if (existing.isEmpty()) return 0
        val keep = HashSet<String>()
        for (hash in db.imageHashes()) {
            if (hash.isEmpty()) continue
            keep.add(encName(hash))
            keep.add(thumbName(hash))
        }
        // 在途写盘的哈希同样保留（BUG.md L-1249）：它的文件可能刚写好、行还没插进库
        for (hash in inFlight) {
            keep.add(encName(hash))
            keep.add(thumbName(hash))
        }
        var n = 0
        for (name in staleNames(existing, keep, System.currentTimeMillis(), protectMs)) {
            if (File(dir, name).delete()) n++
        }
        if (n > 0) Diagnostics.i(TAG, "GC: 清理孤儿图片文件 $n 个")
        n
    }.getOrElse {
        Diagnostics.w(TAG, "GC 失败: ${it.javaClass.simpleName}")
        0
    }

    /**
     * 删除「有行无文件」的图片死行（启动 GC 顺带跑）：两个文件都不在的行永远打不开，
     * 留着只会占序号与额度。返回删除行数。
     */
    fun deleteMissingRows(context: Context, db: ClipboardDb): Int = runCatching {
        val dead = db.imageRows().filter { (_, hash) ->
            !encFile(context, hash).isFile && !thumbFile(context, hash).isFile
        }.map { it.first }
        if (dead.isEmpty()) return 0
        val n = db.deleteAnyByIds(dead)
        Diagnostics.i(TAG, "清理无文件的图片行 $n 条")
        n
    }.getOrElse {
        Diagnostics.w(TAG, "清理无文件图片行失败: ${it.javaClass.simpleName}")
        0
    }

    private const val TAG = "ClipboardImageFiles"
}
