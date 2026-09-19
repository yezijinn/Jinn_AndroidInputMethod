package com.jinn.inputmethod

/**
 * 可选词库清单 —— 「分类词库」页展示与下载的依据。
 *
 * 新增一类词库只需两步：
 *  1. 在此加一条记录（fileName 必须与 Release 附件名一致）
 *  2. 把文件传到 GitHub / Gitee 的 Release
 *
 * 下装后落在 `filesDir/dicts/<fileName>`，引擎启动时自动扫描加载
 * （见 [PinyinEngine.OPT_DICT_DIR]）。加载是**延迟**的：基础词库就绪后
 * 才在后台补齐，不影响日常打字。
 */
data class OptionalDict(
    /** 落地文件名；必须与 Release 附件名、以及 urls 里的文件名一致 */
    val fileName: String,
    /** 页面显示名 */
    val name: String,
    /**
     * 说明文案，**每项是一句完整的话**（页面会逐句单独渲染成一行）。
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
     * 口径（2026-09-17 校正）：这是**首次构建索引**的耗时——加载只在空闲时进行
     * （息屏 / 键盘闲置 20s / 兜底 180s），**期间不影响打字**；构建完成后索引落盘，
     * 之后每次启动直接内存映射复用（实测约 0.05s）。页面文案据此生成。
     */
    val startupSec: Int,
    /** 下载源，按顺序尝试（Gitee 国内快，GitHub 备用） */
    val urls: List<String>,
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
        ),
    )

    /** 按落地文件名查清单项（用于已装列表回显名称） */
    fun byFileName(fileName: String): OptionalDict? = ALL.firstOrNull { it.fileName == fileName }
}
