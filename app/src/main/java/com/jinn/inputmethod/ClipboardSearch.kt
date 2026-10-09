package com.jinn.inputmethod

import java.text.Normalizer

/**
 * 剪贴板搜索的纯匹配层（BUG.md L-1134 / L-1135 / L-984）。
 *
 * 判据原先写在 `SearchPanelView.runSearch` 的循环里：视图类在 JVM 上起不来，于是「全半角」「零宽
 * 字符」「扫描止损」这些边界只能靠真机试 —— 而它们全是纯字符串判断，搬出来就能定值测。
 *
 * 三件事各对应一条：
 *  - 查询词归一：符号层打「，」要能搜到半角逗号，反向同理（[queryForms] / [matches]）；
 *  - 扫描止损的收尾状态：库比 [SCAN_LIMIT] 大时最旧那批**没被扫描**，界面要说清是「没查」而不是
 *    「没找到」（[capState]）；
 *  - 提示文案不写死上限值：命中的真正上界可能是条数，也可能是驻留字节预算（[Cap]）。
 */
internal object ClipboardSearch {

    /**
     * 单次搜索最多扫描的行数。
     *
     * 扫描按「新 → 旧」，所以省掉的是**最旧**的那批（可能正是用户要翻的收藏）。它触顶与
     * 「命中太多被截断」是两种不同的截断：一个是「结果太多」，一个是「更早的内容没查」，
     * 界面必须分开说，否则用户会把「没查到」当成「库里没有」。
     */
    const val SCAN_LIMIT = 20_000

    /** 一轮搜索收尾时的截断状态。 */
    enum class Cap {
        /** 没有截断：库里所有行都扫过，命中也没触顶。 */
        NONE,

        /** 命中太多：条数或驻留字节触顶（[ClipboardStore.searchRetainLimitReached]）。 */
        RETAIN,

        /** 扫描止损：库比 [SCAN_LIMIT] 大，这轮只扫了最近的一部分。 */
        SCAN,
    }

    /**
     * 查询词的等价形态。
     *
     * **不改写用户输入**，只是多为它准备几种写法去试：原文之外加「剥不可见与分隔符」
     * （[FavoriteSymbols.clean]：零宽、方向控制、各类 Unicode 分隔符）与「NFKC 宽度折叠」
     * （全角 ASCII ⇒ 半角）两套，并各叠一次。
     *
     * 折叠只当**候选**：原形排在最前，命中就返回，行为与旧实现逐字一致；只有原形全落空时，
     * 折叠形态才可能命中 —— 所以「用户粘来一个大写 NFKC 会有别的含义的字符」不会被改写。
     *
     * 空形态会被丢掉：`contains("")` 恒真，一个纯空白的查询词会命中全部条目。判据用
     * [hasVisibleContent] 而不是 `isNotBlank()` —— 后者看不见零宽字符，一个纯零宽的形态会留下来
     * （`hasVisibleContent` 也正是全仓判「有没有内容」的那一份，别另写一套）。
     */
    fun queryForms(raw: String): List<String> {
        val lower = raw.lowercase()
        val cleaned = FavoriteSymbols.clean(raw).lowercase()
        val forms = LinkedHashSet<String>()
        for (form in listOf(lower, cleaned, fold(lower), fold(cleaned))) {
            if (form.hasVisibleContent()) forms += form
        }
        return forms.toList()
    }

    /**
     * 行内容是否命中任一等价形态。
     *
     * 先按原形试（与旧实现同为一次 `contains`，常见路径不多花一分钱）；全落空、且内容里**确实**
     * 有需要折叠的字符时，折叠清洗后再试一次 —— 覆盖「查询是半角、库里存的是全角」与「库里存的内容
     * 自带零宽字符」两个反向。内容全是普通汉字 / 字母数字时第三步直接跳过，不给大库搜索加负担。
     */
    fun matches(content: String, forms: List<String>): Boolean {
        if (forms.isEmpty()) return false
        val lower = content.lowercase()
        if (forms.any { lower.contains(it) }) return true
        if (!needsFold(lower)) return false
        val folded = fold(FavoriteSymbols.clean(lower))
        return folded != lower && forms.any { folded.contains(it) }
    }

    /**
     * 收尾状态。
     *
     * 保留量触顶优先：那时循环已经提前 `break`，后面扫到哪儿都不影响「结果被截断」这个事实。
     * 其次是「库比 [limit] 大」**且这轮不是扫到没数据才停**（`exhausted` 为 false 说明循环是被上界
     * 掐停的）——两条都成立才是「更旧的内容没查」。库刚好等于上限时不算截断：那些行全扫过了。
     */
    fun capState(
        retainReached: Boolean,
        exhausted: Boolean,
        dbTotal: Int,
        limit: Int = SCAN_LIMIT,
    ): Cap = when {
        retainReached -> Cap.RETAIN
        !exhausted && dbTotal > limit -> Cap.SCAN
        else -> Cap.NONE
    }

    /**
     * 内容里是否含「要折叠后才等价比对」的字符：全角 ASCII 段、表意空格、NBSP、软连字符，
     * 以及格式类 `Cf`（零宽族与方向控制都在这一类里）。
     *
     * 不含 ASCII 空格：它是 `Zs`，几乎每段多词文本都有，拿它当判据会让折叠在每次未命中时都白跑。
     */
    private val FOLD_WORTHY = Regex("[\uFF01-\uFF5E\u3000\u00A0\u00AD]|\\p{Cf}")

    private fun needsFold(text: String): Boolean = FOLD_WORTHY.containsMatchIn(text)

    /** NFKC：全角 ASCII 折成半角、表意空格折成普通空格（只用于比对，不写回任何用户内容） */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
}
