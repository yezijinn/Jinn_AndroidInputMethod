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
    /** 压缩后体积（MB），用于页面标注供用户权衡 */
    val sizeMb: Double,
    /**
     * 预计增加的「开机后首次使用输入法」候选就绪时间（秒）。
     *
     * 口径（2026-09-17 校正）：这是首次构建索引的耗时，加载只在空闲时进行
     * （息屏 / 键盘闲置 20s / 兜底 180s），期间不影响打字；构建完成后索引落盘，
     * 之后每次启动直接内存映射复用（实测约 0.05s）。页面文案据此生成。
     */
    val startupSec: Int,
    /** 下载源，按顺序尝试（Gitee 国内快，GitHub 备用） */
    val urls: List<String>,
    /**
     * 该包未压缩文件的 SHA-256（小写 64 位十六进制）。
     *
     * 下载完成后逐字节校验，不一致即丢弃：词库内容会直接变成候选词上屏到任意
     * 输入框，没有完整性校验时，一次被替换的 Release 附件或被劫持的重定向
     * 就等于拿到了「往用户每一次输入里塞词」的能力。
     * 两个源必须落同一个摘要，否则换源下载必然校验失败（实测两端字节一致）。
     */
    val checksum: String,
)

object OptionalDicts {

    private const val GITEE =
        "https://gitee.com/yezijinn/Jinn_AndroidInputMethod/releases/download"
    private const val GITHUB =
        "https://github.com/yezijinn/Jinn_AndroidInputMethod/releases/download"

    /** 长词包的 Release tag（基础包已含四字成语，此包补五字及以上长词） */
    private const val TAG_EXT = "dict-ext-20260915-v2"

    /** 腾讯大词库的 Release tag */
    private const val TAG_TENCENT = "dict-opt-tencent-v1"

    val ALL: List<OptionalDict> = listOf(
        OptionalDict(
            fileName = "ext.xz",
            // 界面上叫「长词包」，代码与文档里一般叫「扩展包」（dict_ext），指的是同一个包
            name = "长词包",
            descLines = listOf(
                "五字及以上的长词与专有名词。",
                "含人名、地名、作品名、机构名等。",
            ),
            sizeMb = 1.81,
            startupSec = 2,          // 实测首次构建索引 1764ms
            urls = listOf(
                "$GITEE/$TAG_EXT/dict_ext.txt.xz",
                "$GITHUB/$TAG_EXT/dict_ext.txt.xz",
            ),
            checksum = "f831f41101b555d2f7a54648b3cd3507b55333a7637914a65882c678cce8e5b1",
        ),
        OptionalDict(
            fileName = "opt_tencent.xz",
            name = "腾讯大词库",
            descLines = listOf(
                "约 95.5 万词条。",
                "覆盖大量专业术语与短语搭配。",
                "体积较大，建议按需安装。",
            ),
            sizeMb = 6.36,
            startupSec = 7,          // 实测首次构建索引 6927ms
            urls = listOf(
                "$GITEE/$TAG_TENCENT/opt_tencent.txt.xz",
                "$GITHUB/$TAG_TENCENT/opt_tencent.txt.xz",
            ),
            checksum = "622b0ea87fb08afd87eadf72e055a8377e2f008da20e1c2a3d819599302146ea",
        ),
    )

    /** 按落地文件名查清单项（用于已装列表回显名称） */
    fun byFileName(fileName: String): OptionalDict? = ALL.firstOrNull { it.fileName == fileName }

    /**
     * 流式计算文件的 SHA-256（纯 IO 逻辑，便于 JVM 单测）。
     *
     * 边读边更新摘要，不把整个文件读进内存（现役最大包 6.36MB，往后可能更大）。
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
        md.digest().joinToString("") { "%02x".format(it) }
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
