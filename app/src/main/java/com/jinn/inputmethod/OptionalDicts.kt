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
    /**
     * 压缩后体积（MB，**十进制**口径：5.89 对应 5,890,000 字节）—— 卡片上不再显示（2026-10-03 删掉那一行）。
     *
     * ⚠ 换包时必须与实测字节对得上（差 ≤512KB），它有两个消费方：
     *  - 下载前空间预检（`DictManagerActivity`，按 MiB 再放大一份，偏保守无害）；
     *  - 旧包检测 [isCurrentPack]（同名附件重切后判「已装文件是不是当前版本」）。
     * 折中口径不会误判：512KB 容差覆盖两种算法的最大差（≈5% × 5.89MB ≈ 290KB）。
     */
    val sizeMb: Double,
    /**
     * 首次构建索引的耗时（秒）—— **清单实测数据，不再显示在卡片上**（2026-10-03）。
     *
     * 卡片改说「息屏/收起键盘才会完成加载 / 以后启动都是瞬间就绪」两句通用文案：
     * 实测值每次换包都会变，写死在卡片上等于给用户一个会过期的数。
     * 本字段保留为**换包时的实测依据**（下面这套值就是），别删。
     *
     * 说明：需要首次构建的包在空闲时装（息屏 / 键盘闲置 20s / 兜底 180s），期间不影响打字；
     * 构建完成后索引落盘，之后每次启动直接内存映射复用（实测约 0.05s）—— 所以**索引已就绪**
     * 的包随启动装载，不必再等空闲信号（BUG.md L-892）。
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
     * 分片短语库的 Release tag（2026-10-10 起，B+ 重切）。
     *
     * 三个包与内置索引同源同格式（`docs/所有词库/短语/词库_第 2~4 部分.txt`，
     * 由 `tools/dict_builder/build_dicts.py` 产出），按词频从高到低切分、**逐级叠加**：
     * 内置已含第 1 部分（40 万条），装一个加一档，不需要按顺序（后装的包照常合并）。
     *
     * 2026-10-10 重切口径：第 2 片 = 旧 2+3 片（90 万条）；第 3 片 = 旧第 4 片 + 常用类新词
     * （教材/诗词/地名/成语/名人等，70 万条）；第 4 片 = 专业长尾新词（联想/物品/医化药，21 万条）。
     * ⚠ 换 tag 时**不能**复用旧 tag 下的同名附件（老 APK 的 checksum 会对新字节校验失败）。
     */
    private const val TAG_PARTS = "dict-parts-20261010-v1"

    val ALL: List<OptionalDict> = listOf(
        OptionalDict(
            fileName = "part2.xz",
            name = "2级词库 +90万条短语",
            descLines = listOf(
                "在内置 40 万条基础上再加 90 万\n软件的体积占用 +26 MiB",
            ),
            sizeMb = 5.89,
            startupSec = 20,         // 实测 19580ms（PACM00，2026-10-10 换包后清 idx 重建；旧包 40 万条曾为 4022ms）
            urls = listOf(
                "$GITEE/$TAG_PARTS/dict_part2.txt.xz",
                "$GITHUB/$TAG_PARTS/dict_part2.txt.xz",
            ),
            checksum = "7c712ef3a93a565fd2750a93554cd3fbda77860005a4303bc2556c32f6f638e6",
        ),
        OptionalDict(
            fileName = "part3.xz",
            name = "3级词库 +70万条短语",
            descLines = listOf(
                "再加 70 万条短语，总数约 200 万\n软件的体积占用 +24 MiB",
            ),
            sizeMb = 5.29,
            startupSec = 17,         // 实测 16168ms（PACM00，2026-10-10 换包后清 idx 重建；旧包 50 万条曾为 4859ms）
            urls = listOf(
                "$GITEE/$TAG_PARTS/dict_part3.txt.xz",
                "$GITHUB/$TAG_PARTS/dict_part3.txt.xz",
            ),
            checksum = "96b5279c14c5fa95c4deda5019e88b91f6b60b9b1d4067292b42fdcb9a91fbb6",
        ),
        OptionalDict(
            fileName = "part4.xz",
            name = "4级词库 +21万条短语",
            descLines = listOf(
                "再加 21 万条专业词，总数约 221 万\n软件的体积占用 +9 MiB",
            ),
            sizeMb = 2.14,
            startupSec = 6,          // 实测 5372ms（PACM00，2026-10-10 换包后清 idx 重建；旧包 60 万条曾为 13978ms）
            urls = listOf(
                "$GITEE/$TAG_PARTS/dict_part4.txt.xz",
                "$GITHUB/$TAG_PARTS/dict_part4.txt.xz",
            ),
            checksum = "fa5454f52036bcf1029cddcef8d4efc880aca8a19a8d9feae1d66c849532be81",
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
     * 另收自定义词库**源文本**的临时件（`custom_user.src.txt.tmp`，BUG.md L-857）：它与 `.xz.tmp`
     * 同款 —— 写到一半被杀就永久留下（≤8M 字符），既不在清单、也不以 `.xz` 结尾 ⇒ 同样无人清理。
     *
     * 纯函数（不碰文件系统）：只按名字筛，删除与跳过由调用侧决定 —— 与 [unknownPackages] 同口径。
     */
    fun staleTempNames(fileNames: Collection<String>, active: String?): List<String> {
        // ⚠ 正在下载那个的临时件名是 `"$fileName.tmp"`（文件名本身已含 `.xz`），
        // 而不是 `"$fileName$TEMP_SUFFIX"` —— 后者会拼成 `X.xz.xz.tmp`，永远排不掉在跑的下载
        val activeTmp = active?.let { tempNameOf(it) }
        // 自定义词库**源文本**的临时件（`custom_user.src.txt.tmp`）也一并收：它不匹配 `.xz.tmp`，
        // 却同样是「写到一半进程被杀」就永久留下的垃圾（BUG.md L-857），页面看不见也删不掉
        return fileNames
            .filter { (it.endsWith(TEMP_SUFFIX) || CustomDicts.isSourceTempName(it)) && it != activeTmp }
            .sorted()
    }

    /** 下载临时件后缀：`fetchToFile` 写 `"$fileName.tmp"`，而文件名恒以 `.xz` 结尾 */
    const val TEMP_SUFFIX = ".xz.tmp"

    /**
     * 下载临时件名：写侧（`DictManagerActivity.fetchToFile`）与清理侧共用同一处拼装。
     *
     * 分开各拼一次的话，改名或清理任一侧漏改都会出错 —— 轻则残件没人清，重则清理器把正在下载的
     * 那份删掉（Linux 删已打开文件是成功的，随后的改名必失败，见 L-827）。
     */
    fun tempNameOf(fileName: String): String = fileName + ".tmp"

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

    /**
     * 已装文件是否与**当前清单**一致（旧包检测；2026-10-10 随下载包重切引入）。
     *
     * 为什么需要：卡片原先只按「文件存在」判已安装（`DictManagerActivity.buildCard` 的
     * `file.isFile`）。换包发版时（tag 更换 + 同名附件重切，如本次 2 级 40 万 → 90 万），
     * 老用户本地仍是旧包，页面却显示「已安装」、按钮是「重新下载」⇒ 用户不会点，
     * 词库永远停在上一个版本，旧包还白占空间。
     *
     * 判据 = 文件大小与 [OptionalDict.sizeMb] 相符（容差 ±[PACK_SIZE_TOLERANCE_BYTES]）：
     *  - [OptionalDict.sizeMb] 按**十进制 MB** 填（5.89 对应 5,893,252 B），容差吸收两位小数的
     *    舍入（≤5KB）与换版时的小幅增删；
     *  - **不读文件内容**（sha256 每次刷新列表都要读 6MB×3，主线程代价不可接受）。
     *
     * ⚠ 容差从 512KB 收到 64KB（BUG.md L-1234）：原值下「换版但体积差小于 512KB」会判成同一版，
     * 用户「下了新版却还是旧的」且没有提示。两个方向的风险不对称 —— 误把当前包判成旧包只是让
     * 用户重下一次（无害），漏判却让旧包永久留着，所以要往「宁可重下」的一侧压。
     * 仍不读摘要：那需要每次刷新列表读 6MB×3，属主线程不可接受的代价（彻底消除漏判需把摘要
     * 校验挪到后台并接受列表变慢，属另一笔账）。
     */
    fun isCurrentPack(file: java.io.File, dict: OptionalDict): Boolean {
        val expected = dict.sizeMb * 1_000_000
        return kotlin.math.abs(file.length() - expected) <= PACK_SIZE_TOLERANCE_BYTES
    }

    /** 已装包与清单体积的允许偏差（BUG.md L-1234）：见 [isCurrentPack] 的风险不对称说明 */
    internal const val PACK_SIZE_TOLERANCE_BYTES = 64L * 1024
}
