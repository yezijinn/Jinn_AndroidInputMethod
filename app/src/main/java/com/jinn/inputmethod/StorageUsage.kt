package com.jinn.inputmethod

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * 存储占用构成：把 APP 自己写下的东西，按**用户看得懂的类别**加总，并逐文件摊开
 * （2026-10-10 用户提出；同日按要求从「N 个文件」改为列出每个文件的名字与体积）。
 *
 * 为什么需要它：系统「应用信息」只给「数据 / 缓存」两个笼统数字，说不出成分 —— 而本应用真正
 * 的大头往往是**看不见的词库索引缓存**（三片可选包装齐时 ≈ 56MB，比下载包本体 13MB 还大），
 * 用户既不知道它存在、也不知道删掉词库包后它会自动清掉。次之是被遗忘的下载包与诊断日志。
 *
 * 口径：
 *  - **自算**，不依赖任何系统权限：按清单递归累加真实字节（`File.length()`），读不到的项按 0 计；
 *  - 目录遍历用显式栈（迭代式），深目录不会栈溢出；同一次 [survey] 里用 [claimed] 去重，
 *    已单列的文件不会被「其他」重复计入；每个文件都带**相对所属类别的路径名**，页面直接展示；
 *  - **安装包单列**：内置词库与按键音在 APK 的 assets 里，不占数据目录 —— 用户最容易把它与
 *    「下载词库包」混为一谈（前者卸载才释放，后者可在词库页随时删）；
 *  - **未列入清单的落盘位置不丢**：统一落进「其他」兜底项，将来新增目录会自动出现在报表里，
 *    而不是变成看不见的幽灵占用。
 *
 * 采集放后台线程（几百个文件的 `length()` 级遍历，毫秒量级，但别压在主线程上开页）。
 */
internal object StorageUsage {

    /** 单个文件：[name] 是相对所属类别根目录的路径名（页面直接展示），[bytes] 是 `length()` */
    internal data class Entry(val name: String, val bytes: Long)

    /** 一项成分。[label] 是用户看得懂的名字，[note] 说明它是什么、在哪管理。 */
    internal data class Item(
        val label: String,
        val bytes: Long,
        val entries: List<Entry>,
        val note: String,
    ) {
        val files: Int get() = entries.size
    }

    internal data class Report(
        /** 安装包本体（含 split APK）；0 = 取不到 */
        val apkBytes: Long,
        /** 数据目录内的成分，已按字节降序、已滤掉空项，各成分内条目也按字节降序 */
        val items: List<Item>,
        /** 设备数据分区剩余空间；0 = 取不到 */
        val freeBytes: Long,
    ) {
        val dataBytes: Long get() = items.sumOf { it.bytes }
    }

    /** 自定义词库的文件名前缀：由 [CustomDicts] 的两个常量推出，不另立字面量 */
    private val CUSTOM_PREFIX = CustomDicts.PACK_NAME.substringBefore('.')

    /**
     * 递归列出文件（名字 + 字节），按相对 [root] 的路径命名（子目录带层级前缀，如 `sub/b.txt`）。
     *
     * [claimed] 非空时两侧生效：**已列入的文件跳过**（供「其他」兜底项排除已单列的路径），
     * **计入的文件收进集合**。键用 canonicalPath（读不到时退 absolutePath），沿路径去重。
     */
    internal fun scan(root: File?, claimed: MutableSet<String>? = null): List<Entry> {
        if (root == null || !root.exists()) return emptyList()
        if (root.isFile) {
            return if (claimed != null && !claimed.add(canonical(root))) {
                emptyList()
            } else {
                listOf(Entry(root.name, root.length()))
            }
        }
        val out = ArrayList<Entry>()
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(root to "")
        while (true) {
            val (dir, prefix) = stack.removeLastOrNull() ?: break
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    stack.addLast(child to rel)
                } else if (child.isFile) {
                    if (claimed != null && !claimed.add(canonical(child))) continue
                    out += Entry(rel, child.length())
                }
            }
        }
        return out
    }

    /** 与 [scan] 同一次遍历的汇总口径：字节合计 to 文件数（供不需要明细的调用方与单测用） */
    internal fun measure(root: File?, claimed: MutableSet<String>? = null): Pair<Long, Int> {
        val entries = scan(root, claimed)
        return entries.sumOf { it.bytes } to entries.size
    }

    /** 滤掉空项 + 按字节降序（成分与成分内的文件都排），纯函数便于单测 */
    internal fun assemble(apkBytes: Long, items: List<Item>, freeBytes: Long): Report =
        Report(
            apkBytes,
            items.filter { it.bytes > 0 }
                .map { it.copy(entries = it.entries.sortedByDescending { e -> e.bytes }) }
                .sortedByDescending { it.bytes },
            freeBytes,
        )

    /**
     * 采集本机实际占用（后台线程调用）。
     *
     * 顺序即分类清单：先列明确的位置，最后用 [claimed] 对 dataDir / 外部目录兜底 ——
     * 兜底那一项保证「报表里的总和 = 应用真实占用」，不会因为将来新增目录而漏账。
     */
    internal fun survey(context: Context): Report {
        val files = context.filesDir
        val cache = context.cacheDir
        val data = context.dataDir
        val ext = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        val claimed = HashSet<String>()
        val items = ArrayList<Item>()

        /** 收一项：字节与条目一起算，避免两处各扫一遍（口径只此一处） */
        fun item(label: String, note: String, entries: List<Entry>) {
            items += Item(label, entries.sumOf { it.bytes }, entries, note)
        }

        // 词库包目录（dicts/）按用途拆两类，逐文件认领
        val packEntries = ArrayList<Entry>()
        val customEntries = ArrayList<Entry>()
        for (f in runCatching { File(files, PinyinEngine.OPT_DICT_DIR).listFiles() }.getOrNull().orEmpty()) {
            if (!f.isFile) continue
            claimed.add(canonical(f))
            val entry = Entry(f.name, f.length())
            if (f.name.startsWith(CUSTOM_PREFIX)) customEntries += entry else packEntries += entry
        }
        item("下载词库包", "在「分类词库」页下载或删除（词库的数据源；删包会连同它的索引缓存一起释放）", packEntries)
        item("自定义词库", "在「快捷补充」页添加的词（打包副本与原文）", customEntries)

        // 词库索引缓存（dicts 之外的第二大项，也是用户最看不见的一项）
        item(
            "词库索引缓存", "词库包首次使用时生成；在词库页删除对应词库包后自动清理，需要时重建",
            scan(File(files, PinyinEngine.INDEX_CACHE_DIR), claimed),
        )

        item("用户词频", "随打字自动累积，用于把常用词排在前面", scan(File(files, UserFrequency.FILE_NAME), claimed))
        item("剪贴板历史", "在「剪贴板历史」页管理（容量上限见该页）", scan(File(data, "databases"), claimed))
        // 剪贴板图片（2026-10-10 起）：原图与缩略图都加密落在这里，体积可与数据库一个量级
        // （单张上限默认 20MB、总上限 200MB）—— 不单列的话它会混进「其他」，用户既看不到也管不了
        item(
            "剪贴板图片",
            "复制的图片（原图与缩略图都加密）；容量上限见「剪贴板自定义」页；" +
                "换机前用「剪贴板历史 → 图片 → 导出全部图片」存到相册（图片不进任何备份）",
            scan(File(files, ClipboardImageFiles.DIR_NAME), claimed),
        )
        item("设置与偏好", "各页面的开关与参数", scan(File(data, "shared_prefs"), claimed))

        // cacheDir/JinnIme：配置备份与诊断导出的中间件
        item(
            "配置/导出缓存", "导入导出过程中的临时文件，打开设置页时自动清理",
            scan(File(cache, ConfigBackupManager.CACHE_DIR), claimed),
        )
        item("图库快贴缓存", "待插入的图片副本，按上限自动淘汰", scan(File(cache, GalleryInsert.DIR_NAME), claimed))
        item("编辑器草稿与其他缓存", "词库编辑器大稿等临时文件（关闭页面时自清）", scan(cache, claimed))

        // 外部专属目录：诊断日志（`<外部目录>/logs` —— 只有 LOG_DIR_NAME 这一层，见 Diagnostics.resolveLogDir）
        item(
            "诊断日志", "运行日志（外部目录 logs/），7 天后自动清理",
            scan(ext?.let { File(it, Diagnostics.LOG_DIR_NAME) }, claimed),
        )

        // 兜底：dataDir 与外部目录里未被上面认领的部分（code_cache、系统杂项、将来新增的位置）
        val other = ArrayList<Entry>()
        other += scan(data, claimed)
        other += scan(ext, claimed)
        item("其他", "系统为应用保存的少量杂项（含编译缓存等）", other)

        return assemble(apkBytes(context), items, freeBytes(context))
    }

    /** 安装包本体（sourceDir + split APK）；取不到按 0 */
    internal fun apkBytes(context: Context): Long {
        val info = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        }.getOrNull() ?: return 0L
        var total = runCatching { File(info.sourceDir).length() }.getOrDefault(0L)
        info.splitSourceDirs?.forEach { total += runCatching { File(it).length() }.getOrDefault(0L) }
        return total
    }

    /** 设备数据分区剩余空间；取不到按 0（页面据此隐藏那一行） */
    internal fun freeBytes(context: Context): Long =
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)

    private fun canonical(f: File): String =
        runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
}
