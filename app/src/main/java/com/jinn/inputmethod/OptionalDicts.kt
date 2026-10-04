package com.jinn.inputmethod

/**
 * 可选词库清单，「分类词库」页展示与下载的依据。
 *
 * 新增一类词库只需三步：
 *  1. 在此加一条记录（fileName 必须与 Release 附件名一致）
 *  2. 把文件传到 GitHub / Gitee 的 Release（两端必须是同一份字节）
 *  3. 填入该文件的 SHA-256（[checksum]），下载侧会逐字节校验后才收下
 *
 * 下装后落在 `filesDir/dicts/<fileName>`，引擎启动时自动扫描加载
 * （见 [PinyinEngine.OPT_DICT_DIR]）。加载是延迟的：基础词库就绪后
 * 才在后台补齐，不影响日常打字。
 */
data class OptionalDict(
    /** 落地文件名；必须与 Release 附件名、以及 urls 里的文件名一致 */
    val fileName: String,
    /** 页面显示名 */
    val name: String,
    /**
     * 说明文案，每项是一句完整的话（页面会逐句单独渲染成一行）。
     *
     * 刻意做成列表而不是一整段：长句自动换行会出现断句不良的折行，
     * 所以按句主动分行，一行就是一句话。
     */
    val descLines: List<String>,
    /** 压缩后体积（MB）—— **清单数据，卡片上不再显示**（2026-10-03 删掉那一行） */
    val sizeMb: Double,
    /**
     * 首次构建索引的耗时（秒）—— **清单实测数据，不再显示在卡片上**（2026-10-03）。
     *
     * 卡片改说「息屏/收起键盘才会完成加载 / 以后启动都是瞬间就绪」两句通用文案：
     * 实测值每次换包都会变，写死在卡片上等于给用户一个会过期的数。
     * 本字段保留为**换包时的实测依据**（下面这套值就是），别删。
     *
     * 说明：加载只在空闲时进行（息屏 / 键盘闲置 20s / 兜底 180s），期间不影响打字；
     * 构建完成后索引落盘，之后每次启动直接内存映射复用（实测约 0.05s）。
     *
     * ⚠ 取**实测值向上取整**，不要凭条数外推 —— 2026-09-27 在 PACM00 上实测
     * （清掉 `index/<包名>.idx` 后息屏触发重建，日志 `已构建词库包索引: …（…ms）`）：
     * part2 **4022ms** / part3 **4859ms** / part4 **13978ms**；
     * 此前按条数估的 3 / 4 / 5 秒把 part4 低报了近 3 倍（BUG.md L-46）。
     * 换包（改分片规模）必须重新实测并同步这里。
     */
    val startupSec: Int,
    /** 下载源，按顺序尝试（Gitee 国内快，GitHub 备用） */
    val urls: List<String>,
    /**
     * 该包**压缩文件**（下载到的 `.xz` 原始字节）的 SHA-256（小写 64 位十六进制）。
     *
     * ⚠ 不是解压后的文本摘要：下载侧与备份导入侧两条校验路径算的都是 `.xz` 字节
     * （`OptionalDicts.sha256Of` / `ConfigBackup` 的摘要白名单），写成「未压缩文件」
     * 会让后续维护者填错值 —— 那时两条校验路径 100% 失败，用户只看到「文件校验失败」。
     *
     * 校验不通过即丢弃：词库内容会直接变成候选词上屏到任意输入框，没有完整性校验时，
     * 一次被替换的 Release 附件或被劫持的重定向就等于拿到了「往用户每一次输入里塞词」的能力。
     * 两个源必须落同一个摘要，否则换源下载必然校验失败（实测两端字节一致）。
     */
    val checksum: String,
)

object OptionalDicts {

    private const val GITEE =
        "https://gitee.com/yezijinn/Jinn_AndroidInputMethod/releases/download"
    private const val GITHUB =
        "https://github.com/yezijinn/Jinn_AndroidInputMethod/releases/download"

    /**
     * 分片短语库的 Release tag（2026-09-27 起）。
     *
     * 三个包与内置索引同源同格式（`docs/所有词库/短语/词库_第 2~4 部分.txt`，
     * 由 `tools/dict_builder/build_dicts.py` 产出），按词频从高到低切分、**逐级叠加**：
     * 内置已含第 1 部分（40 万条），装一个加一档，不需要按顺序（后装的包照常合并）。
     */
    private const val TAG_PARTS = "dict-parts-20260927-v1"

    val ALL: List<OptionalDict> = listOf(
        OptionalDict(
            fileName = "part2.xz",
            name = "2级词库 +40万条短语",
            descLines = listOf(
                "在内置 40 万条基础上再加 40 万\n软件的体积占用 +11.7 MiB",
            ),
            sizeMb = 2.62,
            startupSec = 5,          // 实测 4022ms（PACM00，2026-09-27）
            urls = listOf(
                "$GITEE/$TAG_PARTS/dict_part2.txt.xz",
                "$GITHUB/$TAG_PARTS/dict_part2.txt.xz",
            ),
            checksum = "aa224cea041536a8e0a050aad8865c2abfb99c9fc893e037a117f93ef38c867a",
        ),
        OptionalDict(
            fileName = "part3.xz",
            name = "3级词库 +50万条短语",
            descLines = listOf(
                "再加 50 万条短语，总数约 130 万\n软件的体积占用 +14.4 MiB",
            ),
            sizeMb = 3.30,
            startupSec = 5,          // 实测 4859ms（PACM00，2026-09-27）
            urls = listOf(
                "$GITEE/$TAG_PARTS/dict_part3.txt.xz",
                "$GITHUB/$TAG_PARTS/dict_part3.txt.xz",
            ),
            checksum = "619428b7c87f5183df72938c9661529f91ba5bd415e9e16a3889d966f98fc48f",
        ),
        OptionalDict(
            fileName = "part4.xz",
            name = "4级词库 +60万条短语",
            descLines = listOf(
                "再加 60 万条短语，总数约 190 万\n软件的体积占用 +20.8 MiB",
            ),
            sizeMb = 4.21,
            startupSec = 14,         // 实测 13978ms（PACM00，2026-09-27）—— 别按条数外推，会低报近 3 倍
            urls = listOf(
                "$GITEE/$TAG_PARTS/dict_part4.txt.xz",
                "$GITHUB/$TAG_PARTS/dict_part4.txt.xz",
            ),
            checksum = "5e08d39e582765090574506b7dede461633cc8e876ee39494d6845bb5fbba639",
        ),
    )

    /** 按落地文件名查清单项（用于已装列表回显名称） */
    fun byFileName(fileName: String): OptionalDict? = ALL.firstOrNull { it.fileName == fileName }

    /**
     * 从 `dicts/` 的文件名里挑出「清单外的包」（旧版遗留、手工放入）。
     *
     * 引擎是**扫目录**加载的（`loadExtensionDict` 收 `dicts/` 下全部 `.xz`），而分类词库页原先只渲染
     * [ALL] 里的三条 —— 升级前装过旧「长词包」/「腾讯大词库」的设备，文件仍在被内存映射
     * （两个包合计约 34MB），页面上却看不到、也删不掉，只能 adb / Root 动手（BUG.md L-45）。
     * 这里把筛选做成纯函数：调用方传真实文件名，返回该展示成「其他包」的那些。
     *
     * 过滤规则（顺序即判定顺序）：
     *  - 只收 `.xz`：下载中的 `<名字>.xz.tmp` 是临时件，不属于用户可见的包；
     *  - 排除清单内的名字：那些由既有卡片渲染，重复列出会出现两个删除入口；
     *  - 排除用户自定义词库（[CustomDicts.PACK_NAME]）：它由词库页的专用卡片渲染，
     *    被当成「旧版遗留包」列出等于把自己的词库显示成未知包；
     *  - 排序输出：文件系统的列目录顺序不保证稳定，页面每次重画要长得一样。
     *
     * 注意：清单外的包**仍然照常被引擎加载**（既定的兼容口径，见 `更新日志.md`）——
     * 这里只提供删除入口，不改变加载行为。
     */
    fun unknownPackages(fileNames: Collection<String>): List<String> =
        fileNames.filter { it.endsWith(".xz") }
            .filter { name -> ALL.none { it.fileName == name } }
            .filter { name -> !CustomDicts.isPackName(name) }
            .sorted()

    /**
     * 下载**残留临时件**（`*.xz.tmp`，见 BUG.md L-798）：排除 [active]（正在下载的那个包名）的 tmp。
     *
     * 为什么需要：`DictManagerActivity.fetchToFile` 只在 `catch` 里删 tmp，进程被系统杀掉
     * （或下载中页面被关、随后进程回收）就留下最多 `MAX_DOWNLOAD_BYTES` = 64MB 垃圾；而残留件
     * 既不在清单、也不以 `.xz` 结尾 ⇒ 「其他包」区也看不见、页面上删不掉，只有清数据 / 卸载能释放。
     *
     * 纯函数（不碰文件系统）：只按名字筛，删除与跳过由调用侧决定 —— 与 [unknownPackages] 同口径。
     */
    fun staleTempNames(fileNames: Collection<String>, active: String?): List<String> {
        // ⚠ 正在下载那个的临时件名是 `"$fileName.tmp"`（文件名本身已含 `.xz`），
        // 而不是 `"$fileName$TEMP_SUFFIX"` —— 后者会拼成 `X.xz.xz.tmp`，永远排不掉在跑的下载
        val activeTmp = active?.let { "$it.tmp" }
        return fileNames.filter { it.endsWith(TEMP_SUFFIX) && it != activeTmp }.sorted()
    }

    /** 下载临时件后缀：`fetchToFile` 写 `"$fileName.tmp"`，而文件名恒以 `.xz` 结尾 */
    const val TEMP_SUFFIX = ".xz.tmp"

    /**
     * 刚写入的临时件保护窗（见 [cleanableTempNames]）。
     *
     * 自定义词库导入走固定名 `custom_user.txt.xz.tmp`（`CustomDicts.writePack`），页面只要再进一次前台
     * （`onResume → refreshList`）就会跑清理，而 Linux 上删除已打开文件是**成功**的、随后的 `renameTo`
     * 必失败 ⇒ 本次导入报废（BUG.md L-827）。清理前用文件系统事实（mtime）兜一层，
     * 对下载与导入两条路径同时生效，也不受页面实例重建影响。
     */
    const val TMP_PROTECT_MS = 10 * 60 * 1000L

    /**
     * 「可以清理的临时件」= [staleTempNames] 的结果里，去掉**修改时间在 [TMP_PROTECT_MS] 之内**的。
     *
     * 纯函数（mtime 由调用侧传入，不碰文件系统），与 [staleTempNames] 同款便于单测；
     * 保护窗放宽只会推迟清理（下次进页面再清），不会漏清。
     */
    fun cleanableTempNames(
        fileNames: Collection<String>,
        active: String?,
        now: Long,
        modifiedAtOf: (String) -> Long,
    ): List<String> = staleTempNames(fileNames, active)
        .filter { now - modifiedAtOf(it) > TMP_PROTECT_MS }

    /**
     * 流式计算文件的 SHA-256（纯 IO 逻辑，便于 JVM 单测）。
     *
     * 边读边更新摘要，不把整个文件读进内存（现役最大包 4.21MB，往后可能更大）。
     * 失败返回 null，由调用方决定重试还是拒收。
     */
    fun sha256Of(file: java.io.File): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
    }.getOrNull()

    /**
     * 校验文件摘要是否与 [expected] 一致（纯函数）。
     *
     * 判据不含「空串放行」：清单里每一条都必须带摘要，缺失即视为失败，
     * 否则新增词库时漏填 checksum 会让整条校验链形同虚设。
     */
    fun matchesChecksum(actual: String?, expected: String): Boolean =
        expected.length == 64 && expected.all { it.isDigit() || it in 'a'..'f' } &&
            actual != null && actual.equals(expected, ignoreCase = true)
}
