package com.jinn.inputmethod

import java.text.Normalizer
import java.util.Locale

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
    const val SCAN_LIMIT = 2_000

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
        val lower = raw.lowercase(Locale.ROOT)
        val cleaned = FavoriteSymbols.clean(raw).lowercase(Locale.ROOT)
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
        val lower = content.lowercase(Locale.ROOT)
        if (forms.any { lower.contains(it) }) return true
        if (!foldWorthy(lower)) return false
        val folded = fold(FavoriteSymbols.clean(lower))
        return folded != lower && forms.any { folded.contains(it) }
    }

    /**
     * 搜索结果的正文列预览（BUG.md L-1478）。
     *
     * 命中判据走 [matches]，而正文列只放得下**几个字**：预览上限是
     * [ClipboardClassifier.PREVIEW_MAX_CODE_POINTS] 个码点，行数还另有上限（键盘搜索面板 1 行、
     * 历史管理页 4 行）⇒ 命中点落在真正看得见的那一小段之外时，用户看到的是一行不含关键词的
     * 结果，会判成「搜错了 / 没搜到」。
     *
     * 所以只要能在**原文**上直接定位到命中点，窗口就从命中点开始
     * （[ClipboardClassifier.previewFrom]）：命中词落在行首第一个字，一眼就能认出「这行为什么被
     * 搜出来」。⚠ 判据不能用「命中点在 200 码点内」代替「已经看得见」：面板那行 15sp 只装得下
     * 二三十个字、历史页 4 行 13sp 也不过百余字，这一档里命中词同样在窗外，而且是更常见的那半。
     *
     * 只取「原形能直接定位」的命中：`indexOf(ignoreCase)` 在**原文**上找，所以偏移天然对齐原文。
     * 只在折叠（全半角 / 零宽）后才命中的条目退回开头窗口 —— 折叠形态与原文的偏移对不上，
     * 硬算会把窗口挪到无关的位置（比看不见更糟）。这一类仍可能「看不见命中词」，属已知残留。
     *
     * 未超限时与 [ClipboardClassifier.previewOf] 完全同路（同一引用），短条目不受影响。
     */
    fun previewFor(content: String, forms: List<String>): String {
        if (content.length <= ClipboardClassifier.PREVIEW_MAX_CODE_POINTS) {
            return ClipboardClassifier.previewOf(content)
        }
        // 形态按「原形 → 剥不可见与分隔符 → 宽度折叠」的顺序排（见 queryForms），第一个命中就是最精确的
        // 那一个，取到即开窗：逐个形态都扫一遍只为挑「最早」，长条目上就是每次绑定几遍全文，
        // 而两种取法在行内看到的都是同一句「为什么被搜出来」。
        for (form in forms) {
            val at = content.indexOf(form, ignoreCase = true)
            if (at >= 0) return ClipboardClassifier.previewFrom(content, at)
        }
        // 一个形态都定位不到（只在折叠后才算命中 / 无查询词）⇒ 退回开头窗口
        return ClipboardClassifier.previewOf(content)
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
     * 内容里是否含「折叠后才会变」的字符。
     *
     * 判据 = **除 ASCII 空格以外精确**：`clean` 会删的（C / Z 类，含未分配与增补平面）由
     * [FavoriteSymbols.hasCleanable] 单遍判；NFKC 会变的（全角 ASCII / 兼容字符 / 各类分隔符）
     * 由 [Normalizer.isNormalized] 整体判。都比旧判据宽 —— 旧判据只认 4 个字面量 + `Cf`，
     * `①`、`Ⅰ`、半角假名、`Cs/Co/Cn`、非 3000/A0 的 Z 类都会漏（那些内容明明在库里却搜不到）。
     *
     * **ASCII 空格有意不触发**：它是 Z 类，`clean` 会删；若算进来，每段多词文本都会白跑折叠。
     * 反向由 `空格不当折叠信号` 用例钉住。
     *
     * 顺序：ASCII 快路径（绝大多数行）→ `isNormalized`（中文行首个全角标点就命中）→ C/Z 单遍扫。
     * 反过来先扫 C/Z 会让中文行从 0.17 涨到 3.0 ns/字符。
     */
    internal fun foldWorthy(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code >= 0x80) break
            if (c != ' ' && FavoriteSymbols.isCleanable(c)) return true
            i++
        }
        if (i == text.length) return false            // 纯 ASCII ⇒ NFKC 恒等
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFKC)) return true
        return FavoriteSymbols.hasCleanable(text)
    }

    /** NFKC：全角 ASCII 折成半角、表意空格折成普通空格（只用于比对，不写回任何用户内容） */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
}
