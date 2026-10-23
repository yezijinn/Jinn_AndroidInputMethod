package com.jinn.inputmethod

import java.util.regex.Pattern

/**
 * 剪贴板内容自动分类与片段提取。
 *
 * 分类是**多标签**（2026-09-25 起）：一条内容可同时属于「网址」与「数字」，
 * 例如「我的电话是123456，记得访问 www.baidu.com」= `URL,NUMBER`：
 *  - URL：含网址片段（http/https/ftp、深链接、IP、www、白名单 TLD 的裸域名）
 *  - NUMBER：含数字片段（手机号、金额、6~20 位数字串、关键词后的号码、整段 4~8 位）
 *  - OTHER：两者都没有
 *
 * 分组展示与粘贴走 [firstUrl] / [firstNumber]：只取**最先出现**的那一个匹配，并归一成
 * 干净片段（网址剥尾随标点、手机号去分隔符、金额去货币符号）。分类与提取共用同一套规则
 * （[classify] 就是「两个提取函数是否为 null」），于是「进了某分组 ⇒ 一定能提取到片段」
 * 在代码层面成立，不会出现筛选命中却只能显示原文的错配。
 *
 * 数字侧不能只看「是否含数字」：句中至少 6 位连续数字才算片段，更短的只有整段就是数字
 * 或带关键词（验证码/订单号…）才算，否则 "我有123个苹果" 会被误判、误提取。
 *
 * 纯 JVM 可测，不依赖 Android，正则常量都收在这里。
 */
object ClipboardClassifier {

    /** 分类取值（与数据库 category 列一致；多标签用 [LABEL_SEPARATOR] 连接） */
    const val CATEGORY_URL = "URL"
    const val CATEGORY_NUMBER = "NUMBER"
    const val CATEGORY_OTHER = "OTHER"

    /** 多标签分隔符。标签集固定且互不包含，所以 SQL 侧可直接 `LIKE '%URL%'` 判命中 */
    const val LABEL_SEPARATOR = ","

    /**
     * 分类扫描的前缀上限（字符，见 [classify]）。
     *
     * 8192 远大于任何真实的一条网址 / 数字片段（最长也就几百字符），又远小于入库上限
     * （256KB）—— 把最坏情况的扫描量从 11 × 256KB 压到 11 × 8KB。
     */
    private const val MAX_CLASSIFY_SCAN_CHARS = 8 * 1024

    /**
     * 自动分类：含网址 → 带 URL 标签；含数字片段 → 带 NUMBER 标签；都没有 → OTHER。
     * 两者都有时为 `URL,NUMBER`（顺序固定，便于比较与测试）。不输出明文日志。
     */
    fun classify(text: String): String {
        // MEM-27：分类只扫前缀。入库上限是 256KB（`ClipboardController.MAX_ITEM_BYTES`），
        // 11 条规则（5 条 URL + 6 条数字）逐条 `find()` 就是 11 遍扫描；而标签只需要回答
        // 「这份内容**开头**像不像网址/数字」—— 靠前的片段本来就是列表展示与粘贴用的那一段
        // （见 [pieceFor]），扫到几万字符之后才出现的片段没人会看到。
        //
        // 只在 [classify] 内截断，不动 [firstUrl]/[firstNumber]：那两个函数还有展示与粘贴的
        // 调用方（`pieceFor`），对它们截断会改变列表里显示与粘出去的原文片段。
        val head = if (text.length > MAX_CLASSIFY_SCAN_CHARS) text.substring(0, MAX_CLASSIFY_SCAN_CHARS) else text
        val url = firstUrl(head) != null
        val number = firstNumber(head) != null
        return when {
            url && number -> "$CATEGORY_URL$LABEL_SEPARATOR$CATEGORY_NUMBER"
            url -> CATEGORY_URL
            number -> CATEGORY_NUMBER
            else -> CATEGORY_OTHER
        }
    }

    /** [category] 是否含 [label] 标签 */
    fun hasLabel(category: String?, label: String): Boolean =
        category != null && category.split(LABEL_SEPARATOR).any { it.trim() == label }

    /**
     * 归一外部来源的分类值（备份导入）：只保留合法标签、去重、按固定顺序拼接，
     * 无合法标签一律 OTHER —— 导入包里的任意字符串不能进列表元数据（会拖垮布局）。
     */
    fun normalizeLabels(raw: String?): String {
        val labels = raw.orEmpty().split(LABEL_SEPARATOR)
        val url = labels.any { it.trim() == CATEGORY_URL }
        val number = labels.any { it.trim() == CATEGORY_NUMBER }
        return when {
            url && number -> "$CATEGORY_URL$LABEL_SEPARATOR$CATEGORY_NUMBER"
            url -> CATEGORY_URL
            number -> CATEGORY_NUMBER
            else -> CATEGORY_OTHER
        }
    }

    // ── 片段提取（分组展示与粘贴用）────────────────────────

    /** 提取最先出现的网址片段；没有返回 null */
    fun firstUrl(text: String): String? = earliest(text, URL_RULES)

    /** 提取最先出现的数字片段；没有返回 null */
    fun firstNumber(text: String): String? = earliest(text, NUMBER_RULES)

    /**
     * 按分组取「要展示、也要粘贴」的文本（列表 UI 的两处调用点共用）：
     * 网址组只给网址片段、数字组只给数字片段，其余分组（全部 / 收藏）给原文。
     *
     * 提取不到时回退原文 —— 进组条件本身就是「能提取」（见 [classify]），这里只是兜底，
     * 保证任何一条都不会在列表里显示成空白。
     */
    fun pieceFor(category: String?, content: String): String = when (category) {
        CATEGORY_URL -> firstUrl(content) ?: content
        CATEGORY_NUMBER -> firstNumber(content) ?: content
        else -> content
    }

    /**
     * 列表预览的码点上限（历史页 / 键盘面板 / 搜索结果三处正文列共用）。
     *
     * 这三处正文列都只是**预览**（宽度受限、行数受限），却原来把整份文本塞进去。单条接近
     * 256KB 的文本是一行没有任何断点的巨串，`TextView` 在 Android Q+ 默认的
     * `BREAK_STRATEGY_HIGH_QUALITY` 下走 `minikin::breakLineOptimal`（最优断行，近似 O(n²)）：
     * 真机实测（2026-10-24，256,000 字节单行）历史页 PSS 70MB → **1,473MB**、
     * 键盘「历史」面板 76MB → **1,298MB**，随后 `lowmemorykiller` 杀掉整个进程（输入法当场消失）。
     * 取 200 码点：四行预览绰绰有余，布局代价与普通文本同量级。
     */
    const val PREVIEW_MAX_CODE_POINTS = 200

    /**
     * 截到 [PREVIEW_MAX_CODE_POINTS] 个**码点**的预览串；未超限时原样返回（同一引用，不做拷贝）。
     *
     * 码点口径来自 [FavoriteSymbols.takeCodePoints]（BUG.md L-1426）：`substring(0, 200)`
     * 数的是 UTF-16 码元，会把 emoji 的代理对切一半、末尾留孤立高位代理。
     *
     * 上限刻意**不做参数**：调用方只该「按预览规则截」，能自定长度就又能传个 100000 回来。
     *
     * ⚠ 只用于**显示**。粘贴 / 收藏 / 删除一律拿 `item.content` 原文，绝不拿预览串。
     */
    fun previewOf(text: String): String {
        // 快筛：`codePointCount ≤ length`，长度都没超就不必逐个码点扫一遍 —— 预览在滚动的
        // 每一次条目绑定上都要算，长条目的 `codePointCount` 是 O(n) 的主线程开销。
        if (text.length <= PREVIEW_MAX_CODE_POINTS) return text
        return FavoriteSymbols.takeCodePoints(text, PREVIEW_MAX_CODE_POINTS)
    }

    /**
     * 内容对话框的单段字符上限：全文按此**分段**渲染（一段一个 TextView）。
     *
     * 为什么不是「一个 TextView 装全文 + 降断行策略」：实测那条路走不通。250,000 字符的单行
     * 文本进对话框（已设 `BREAK_STRATEGY_SIMPLE`）时 PSS 仍 153MB → **1,868MB** 随后被
     * `lowmemorykiller` 杀掉（同日同一 APK 复现两次，另有一次峰值 2,006MB 未杀）；只降策略
     * 不降 **n**，代价仍随「无断点巨串」的长度走。分段把每次布局的 n 压到常数：
     * 4000 字符 ≈ 140 行的量级，与普通文本同阶。
     *
     * 取 4000：短于它的文本仍是**一段**（走原来的单 TextView 路径，视觉零变化），
     * 250KB 全文切成 63 段。上限不由调用方传：能自定长度就又能传个 100000 回来把病复发
     * （与 [previewOf] 同理），而且 `max <= 0` 会让切分循环原地打转。
     * 段数由单条闸封顶：单条 ≤ 268,435B ⇒ 最多 **68 段**（`ClipboardStore.effectiveItemLimitBytes`）。
     */
    const val SEGMENT_MAX_CHARS = 4000

    /**
     * 把全文切成每段 ≤ [SEGMENT_MAX_CHARS] 字符的分段，**拼接起来与原文一字不差**（内容不丢、不改）。
     *
     * 断点策略：优先落在换行符之后（多行文本 → 段落边界对齐，不会把一行劈成两行）；
     * 一整段都没有换行时（单行巨串）才硬切。这也是「单行 250KB」唯一可能的切法。
     *
     * 未超限时返回单元素列表且是**同一引用**（常见路径零拷贝）。
     */
    fun segmentsOf(text: String): List<String> {
        if (text.isEmpty() || text.length <= SEGMENT_MAX_CHARS) return listOf(text)
        val out = ArrayList<String>(text.length / SEGMENT_MAX_CHARS + 2)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + SEGMENT_MAX_CHARS, text.length)
            if (end < text.length) {
                // 只在**本窗口内**往回找换行：`lastIndexOf` 没有下界，单行巨串时每段都会一路扫回 0
                // （63 段就是 800 万次比较的二次行为，全在主线程）
                var i = end - 1
                while (i >= start && text[i] != '\n') i--
                // 找不到就硬切；`end = i + 1` 保证每轮至少前进 1
                if (i >= start) end = i + 1
            }
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }

    /**
     * 单段在内容对话框里的**显示**文本：非末段去掉结尾那一个换行。
     *
     * [segmentsOf] 保证多行文本的非末段以 `\n` 收尾，于是这一行换行被**算了两次** ——
     * 一次是段尾那个 `\n`（`StaticLayout` 会在末尾多铺一条空行），一次是下一段 TextView 自己另起一行。
     * 结果就是每 4000 字处多出一条空行。竖直堆叠本来就提供了这次断行，剥掉段尾那一个换行即可：
     * 段落边界仍是一行，`\n\n` 里的第二个换行（真空行）保留不动。
     *
     * 只剥**一个**后缀、且只剥非末段：末段之后没有下一段，那个换行是内容本身的结尾，动它就是改内容。
     *
     * CRLF 也要一起剥：段尾是 `\r\n` 时只去掉 `\n` 会把一个裸 `\r` 留在段末（它会渲染成豆腐块）。
     * 剪贴板里确实会出现 CRLF —— 真机实测从 PC 剪贴板同步进来的条目内容是 `\r\n`（6 字的 `DDDD\r\n`）。
     */
    fun segmentText(segment: String, isLast: Boolean): String {
        if (isLast) return segment
        if (segment.endsWith("\r\n")) return segment.dropLast(2)
        if (segment.endsWith("\n")) return segment.dropLast(1)
        return segment
    }

    /**
     * 分类标签的中文短名（列表 meta 行用）：`URL,NUMBER` → `网址·数字`；
     * OTHER / 空 / 脏值 → 空串（调用方据此不显示该段）。
     */
    fun labelText(category: String?): String = buildList {
        if (hasLabel(category, CATEGORY_URL)) add("网址")
        if (hasLabel(category, CATEGORY_NUMBER)) add("数字")
    }.joinToString("·")

    /** 一条提取规则：[pattern] 定位匹配，[normalize] 把匹配文本归一成干净片段 */
    private class Rule(val pattern: Pattern, val normalize: (String) -> String = { it })

    /**
     * 按「最先出现」取片段：逐条规则 `find()`，比较匹配起点，起点最小者胜（同起点按规则顺序）。
     * 不按规则优先级取，是因为句子里先出现的那个通常才是用户想要的那一个。
     */
    private fun earliest(text: String, rules: List<Rule>): String? {
        if (text.isBlank()) return null
        var best: String? = null
        var bestStart = Int.MAX_VALUE
        for (rule in rules) {
            val m = rule.pattern.matcher(text)
            if (!m.find() || m.start() >= bestStart) continue
            val value = rule.normalize(m.group()).trim()
            if (value.isEmpty()) continue
            best = value
            bestStart = m.start()
        }
        return best
    }

    // ── 网址 ──────────────────────────────────────────────

    /**
     * 网址正文允许的字符（RFC 3986 的 unreserved + reserved + 百分号）。
     *
     * 不能用 `[^\s]+`：中文标点与汉字都不是空白，`见 https://a.com/x，谢谢` 会被整段吞掉，
     * 事后剥尾随标点也救不回来（脏字符在中间）。限定字符集后，匹配在「，」处自然停止。
     */
    private const val URL_CHARS = "A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%"

    /**
     * 边界一律用显式前后瞻，**禁止 `\b` / `\B`**。
     *
     * Android 的 [Pattern] 走 ICU，`\b` 是 **Unicode 单词边界**（汉字算单词字符），
     * 而 JVM 的是 ASCII 语义 —— 同一个正则在两端行为不同：「是123456」在设备上产生不了
     * 数字边界（提取不到），在 JVM 单测里却能过。2026-09-25 真机实测踩到这个坑
     * （用户例句「我的电话是123456…」被分成只有 URL 标签）。用前后瞻后两端一致，
     * 单测与真机不会分叉。
     */
    private const val NOT_DIGIT_BEFORE = "(?<!\\d)"
    private const val NOT_DIGIT_AFTER = "(?!\\d)"
    private const val NOT_ALNUM_BEFORE = "(?<![a-z0-9])"

    private val URL_PATTERN = Pattern.compile(
        "(?i)$NOT_ALNUM_BEFORE(?:https?|ftp)://[$URL_CHARS]+"  // http/https/ftp
    )
    private val SCHEME_PATTERN = Pattern.compile(
        "$NOT_ALNUM_BEFORE[a-z][a-z0-9+.-]{1,15}://[$URL_CHARS]+" // 深链接 myapp://abc
    )
    private val IP_PATTERN = Pattern.compile(
        "(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![\\d.])"      // IP 地址
    )
    private val WWW_PATTERN = Pattern.compile(
        "(?i)${NOT_ALNUM_BEFORE}www\\.[a-z0-9-]+\\.[a-z]{2,}[$URL_CHARS]*" // www.example.com
    )
    private val DOMAIN_PATTERN = Pattern.compile(
        // 尾部用负向前瞻而不是边界断言：带路径时路径可能以非单词字符结尾（`example.com/a/`），
        // 单词边界会让整个路径组回退掉只留域名；`(?![a-z0-9-])` 只负责挡住 `example.company` 这类误匹配
        "(?i)(?<![a-z0-9-])[a-z0-9-]+\\.(?:com|cn|net|org|io|dev|me|app|xyz|top|info|biz|edu|gov|tv|cc)" +
            "(?:[/?#][$URL_CHARS]*)?(?![a-z0-9-])"
    )

    private val URL_RULES = listOf(
        Rule(URL_PATTERN, ::trimTrailingPunct),
        Rule(SCHEME_PATTERN, ::trimTrailingPunct),
        Rule(IP_PATTERN),
        Rule(WWW_PATTERN, ::trimTrailingPunct),
        Rule(DOMAIN_PATTERN, ::trimTrailingPunct),
    )

    /**
     * 剥掉网址右端的句读标点（`访问 www.baidu.com。` → `www.baidu.com`）。
     *
     * 只裁右端；`.` 与 `?` `!` 也在集合里 —— 它们在网址**末尾**几乎总是句子标点
     * （域名不会以点结尾），而路径里合法出现的位置都在中间，不受影响。
     * 斜杠、等号、井号、百分号等 URL 语法字符一律保留。
     */
    private fun trimTrailingPunct(raw: String): String = raw.trimEnd { it in TRAILING_PUNCT }

    private const val TRAILING_PUNCT = "。，、；：！？…（）【】《》「」『』“”‘’,;:!?)]}>\"'."

    // ── 数字 ──────────────────────────────────────────────

    private val AMOUNT_PATTERN = Pattern.compile(
        "[¥￥$€]\\s*\\d+(?:\\.\\d{1,2})?"                   // 金额
    )
    private val PHONE_PATTERN = Pattern.compile(
        "$NOT_DIGIT_BEFORE" + "1[3-9]\\d{9}" + "$NOT_DIGIT_AFTER"   // 大陆手机号
    )
    private val PHONE_ALT_PATTERN = Pattern.compile(
        "$NOT_DIGIT_BEFORE" + "(?:\\+?86[ -]?)?1[3-9]\\d[ -]?\\d{4}[ -]?\\d{4}" + "$NOT_DIGIT_AFTER"
    )
    /** 句中的长数字串（6~20 位，可带 1~2 位小数）：订单号 / 卡号 / 上下文里的号码 */
    private val LONG_DIGITS_PATTERN = Pattern.compile(
        "$NOT_DIGIT_BEFORE" + "\\d{6,20}(?:\\.\\d{1,2})?" + "$NOT_DIGIT_AFTER"
    )
    private val LONG_NUMBER_CTX = Pattern.compile(
        // 下界与 [VERIFY_CODE_ONLY] 的 4~8 位对齐：同一串验证码带不带标签都要能提取出来。
        // 关键词表本身足够窄（订单号/编号/验证码/金额…），放宽到 4 位不会把「第 1234 章」拉进来。
        "(?i)(?:订单号|单号|编号|流水号|验证码|交易号|卡号|订单|金额)[:：\\s]*(\\d{4,}(?:\\.\\d{1,2})?)"
    )
    /** 整段就是 4~8 位数字（验证码）：短数字只有整段命中才算片段，句中短数字一律不算 */
    private val VERIFY_CODE_ONLY = Pattern.compile(
        "^\\s*\\d{4,8}\\s*$"
    )

    private val NUMBER_RULES = listOf(
        Rule(AMOUNT_PATTERN) { it.replace(CURRENCY_AND_SPACE, "") },   // 金额只留数字（分组名就是「数字」）
        Rule(PHONE_PATTERN, ::normalizePhone),
        Rule(PHONE_ALT_PATTERN, ::normalizePhone),
        Rule(LONG_DIGITS_PATTERN),
        Rule(LONG_NUMBER_CTX) { m -> TRAILING_DIGITS.find(m)?.value ?: m }, // 关键词后只取号码
        Rule(VERIFY_CODE_ONLY),
    )

    /**
     * 手机号归一：去空格与连字符，再去国家码（`+86` / `0086` / 前导 `86`）。
     * 国内场景粘出的就是 11 位裸号 —— 拨号与填表都最省事。
     */
    private fun normalizePhone(raw: String): String {
        val digits = raw.replace(PHONE_SEPARATOR, "").trimStart('+')
        return when {
            digits.startsWith("0086") -> digits.drop(4)
            digits.startsWith("86") && digits.length > 11 -> digits.drop(2)
            else -> digits
        }
    }

    private val CURRENCY_AND_SPACE = Regex("[¥￥$€\\s]")
    private val PHONE_SEPARATOR = Regex("[ -]")
    private val TRAILING_DIGITS = Regex("\\d[\\d.]*$")
}
