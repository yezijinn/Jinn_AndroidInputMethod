package com.jinn.inputmethod

import okhttp3.Request
import org.json.JSONObject

/**
 * 在线翻译（BYOK：用户自带 API Key）的领域模型与纯函数。
 *
 * 三件事收敛在这里：
 *  - 应用层语言口径 [TranslationLanguage]：UI 与配置只认它，Provider 各自映射到自家语言码，
 *    以后加 Google / DeepL 不会污染设置页；
 *  - 失败原因 [TranslationError]：归一分类，给用户看的是这里的一句话，
 *    **绝不透传服务端原始 JSON**；
 *  - 原文截取与译文追加 [TranslationText]：纯函数，运行期与 JVM 单测共用同一份规则。
 *
 * Provider 只做「造请求 / 解析响应」两件纯事（[TranslationProvider]）；网络执行、HTTPS 判据与
 * 超时由 [TranslationClient] 统一负责，**请求代际（旧结果作废）由调用方 JinnIme 负责** ——
 * 两侧都能在纯 JVM 下单测，不需要真网络与设备。
 */

/**
 * 目标语言（用户 2026-09-30 定：至少中/英/日/韩，默认英文）。
 *
 * 语言码只在 Provider 自己的映射里出现，设置页与 [Prefs.translateTarget] 只认枚举 name：
 * `zh / zh-Hans` 这类差异、DeepL 的全大写代码都收敛在这一层，UI 与配置不受影响。
 */
internal enum class TranslationLanguage(
    val label: String,
    val azureCode: String,
    val baiduCode: String,
    val aliyunCode: String,
    val deeplCode: String,
) {
    // 末位 = DeepL 目标语言码。中文用正名 `ZH-HANS` 而不是别名 `ZH`（2026-10-02 修复 L-487）：
    // 官方文档已把 `ZH` 标为 deprecated（当前等同简体），一旦别名被移除 ⇒ `target_lang` 非法 ⇒
    // 400 ⇒ 归一成 PARAM（提示指向「语言方向」）—— 用户会以为是自己选错了语言。
    CHINESE("中文", "zh-Hans", "zh", "zh", "ZH-HANS"),
    ENGLISH("English", "en", "en", "en", "EN"),
    JAPANESE("日本語", "ja", "jp", "ja", "JA"),
    KOREAN("한국어", "ko", "kor", "ko", "KO");

    companion object {
        /** 默认目标语言：中文用户最常见的是「译成英文」 */
        val DEFAULT = ENGLISH

        /** 归一：null / 未知取值一律回落默认（Prefs getter 与备份导入共用同一入口） */
        fun of(name: String?): TranslationLanguage = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 翻译服务提供方（[Prefs.translateProvider] 存 id 字符串）。
 *
 * 顺序即设置页下拉顺序；[DEFAULT] = 阿里云（用户 2026-09-30 指定）。
 * 旧配置里的 `azure` / `baidu` 取值继续有效（id 不变），只有未知取值才回落到默认。
 */
internal enum class TranslationProviderId(val id: String, val label: String) {
    ALIYUN("aliyun", "阿里云"),
    AZURE("azure", "Azure"),
    BAIDU("baidu", "百度"),
    BAIDU_LLM("baidu_llm", "百度大模型"),
    DEEPL("deepl", "DeepL"),
    OPENAI("openai", "OpenAI 兼容");

    companion object {
        val DEFAULT = ALIYUN

        fun of(id: String?): TranslationProviderId = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 翻译失败原因（归一后的分类，直接当提示文案用）。
 *
 * 文案里不含服务端返回的原始 JSON：排障信息走 [Diagnostics] 的响应码与异常类名，
 * 凭据与待译正文一律不进日志（沿用 AsrClient「只记长度不记原文」的口径）。
 */
internal enum class TranslationError(val message: String) {
    /** 当前选定的 Provider 还没填凭据 */
    // 补全文路径（2026-10-03 修复 L-538）：键盘上点翻译时这是**唯一**的提示，而实际路径是
    // 「系统设置 → 语言与输入法 → 本输入法 → 设置 → 翻译设置」四级 —— 只说「设置里」会让
    // 新用户在这一步卡死。设置页摘要早已写全落点，这里对齐。
    NOT_CONFIGURED("请先在「设置 → 翻译设置」里填写翻译 API 凭据"),
    /** 地址不是 HTTPS（自定义 Base URL 可填出 `http://`）：明文会把 Bearer 凭据暴露在链路上 */
    INSECURE("翻译地址必须以 https:// 开头"),

    /**
     * 凭据含 Header 非法字符（从网页复制时混入 U+3000 / 控制符很常见）。
     *
     * 与 [AUTH] 不同：**请求根本没发出去**；与 [PARAM] 不同：真因不在语言/模型/路径上。
     * 与 `fetchModels` 的 `-3`（`TEXT_KEY_INVALID`）同源（2026-10-02 修复 L-429）。
     */
    CREDENTIAL("凭据含不可见字符，未发送请求（请重新粘贴 Key）"),
    NETWORK("网络错误，请检查网络后重试"),
    TIMEOUT("翻译请求超时，请重试"),
    AUTH("认证失败：请检查凭据（API Key / AppID / SecretKey / AccessKey）与 Azure 区域"),
    QUOTA("额度不足或请求过于频繁，请稍后重试"),
    PARAM("服务端拒绝了这次请求的参数（语言方向 / 模型名 / 路径 / 原文长度），请对照服务方文档核对"),

    /**
     * 输出被上限截断（OpenAI 兼容那家的 `finish_reason == "length"`）。
     *
     * 与 [PARAM] 分开（2026-10-02 第二轮审查）：PARAM 的文案让用户去核对「语言方向 / 模型名 /
     * 路径 / 原文长度」，而真因是**输出上限**（Max Tokens 或网关自带上限）—— 按 PARAM 的提示
     * 逐项核对必然无果。
     */
    TRUNCATED("译文被输出上限截断（可在该服务商设置里调大 Max Tokens，或改用非推理模型）"),

    /**
     * 服务端「少给行」：译文行数 < 原文非空行数（按行对齐的 Provider，如百度系）。
     *
     * 与 [PARAM] 分开（2026-10-02 第五轮审查）：PARAM 会让用户去核「语言方向 / 模型名 / 路径 /
     * 原文长度」，而真因是服务端少返回了行 —— 逐行对应会错位，宁可拒绝也不写进正文。
     */
    LINE_MISMATCH("服务端返回的行与原文不齐，为避免逐行错位未写入（可去掉空行后重试）"),

    /**
     * 端点返回 3xx 重定向（2026-10-03 修复 L-600）。
     *
     * 重定向是**刻意关闭**的（`followRedirects(false)`，防不可信网关把用户正文转投到
     * `Location` 指定的任意主机，2026-10-01 修复 L-306）—— 关掉它是对的，缺的是**出路**：
     * 原先 3xx 与 5xx 同归 [SERVER]，于是「网关尾斜杠归一 / `http→https` 升级 / `/openai`
     * 路径补全」这类**用户可修**的配置问题被显示成「翻译服务异常，请稍后重试」，
     * 用户只会反复重试（每次真计费）而永远不会去改 Base URL。
     */
    REDIRECT("翻译端点返回了重定向，请检查 Base URL 的路径与结尾斜杠"),

    SERVER("翻译服务异常，请稍后重试"),
    EMPTY("翻译结果为空"),
}

/** 翻译结果：要么拿到译文，要么是一个归一后的失败分类 */
internal sealed interface TranslationOutcome {
    data class Ok(val text: String) : TranslationOutcome
    data class Fail(val error: TranslationError) : TranslationOutcome
}

/**
 * 「哪些内容算翻译原文」的取值范围（**每家 Provider 独立配置**）。
 *
 * 四种模式都只描述「取哪一段文本」，里面**没有句子切分** —— 切分单位只有一个：行（`\n`）。
 * 所以没有换行的输入框（聊天框 / 搜索框）里，前三种模式会取到同一段（整篇即一行）。
 */
internal enum class TranslationScope(val id: String, val label: String, val detail: String) {
    LINE_BEFORE(
        "line_before",
        "光标前本行",
        "只翻光标所在这一行里、光标前面的内容（上一行、下一行都不算）",
    ),
    LINE_FULL(
        "line_full",
        "光标所在整行",
        "翻光标所在这一整行，不分光标前后",
    ),
    BEFORE_ALL(
        "before_all",
        "光标前全部",
        "翻从文首到光标之间的全部内容，不分多少行",
    ),
    ALL(
        "all",
        "编辑框全部",
        "翻编辑框里能取到的全部文本（不分行、不分光标前后）",
    ),
    ;

    companion object {
        /** 默认模式：上传面最小的那种（用户可在「翻译原文范围」页改） */
        val DEFAULT = LINE_BEFORE

        fun of(id: String?): TranslationScope = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 每家的默认单次字节上限（用户可改；已查到的官方值直接填上，出处见设置页说明）。
 *
 * 官方按**字符**计的两家（阿里云 5000 / Azure 50000）这里填等值字节：一个字符至少占 1 字节，
 * 所以「字节数 ≤ 官方字符上限」是保守成立的 —— 中文文本会提前截断（可自行调大），
 * 但绝不会撞上服务端的「字符串过长」错误。
 *
 * ⚠ 所有默认值都必须 ≤ [TranslationText.MAX_MAX_BYTES]（= 本机单次读取上限，2026-10-01 审查 L-223）：
 * 服务端允许更大也没用，**读不回来的文本发不出去**。DeepL 官方给到 131072 字节，本机读取窗口
 * 只有 100000，故按窗口填（≈3.3 万汉字）—— 要真正用满官方额度，得先把读取上限提上去。
 */
internal val TranslationProviderId.defaultMaxBytes: Int
    get() = when (this) {
        TranslationProviderId.ALIYUN -> 5_000
        TranslationProviderId.AZURE -> 50_000
        TranslationProviderId.BAIDU -> 6_000
        // 官方 q 上限 6000 字符、建议 2000 字符以内 ⇒ 与通用版取同一档。原先填 32_768
        // （与 OpenAI 兼容同款）会让**默认配置**必然撞服务端上限，而超限码不在精确码表里 ⇒
        // 一律「服务异常」，用户重试永远失败且每次都真计费（2026-10-01 修复 L-307）。
        TranslationProviderId.BAIDU_LLM -> 6_000
        TranslationProviderId.DEEPL -> 100_000
        TranslationProviderId.OPENAI -> 32_768
    }

/**
 * 原文提取与译文追加（纯函数，运行期与 JVM 单测共用同一份规则）。
 *
 * 追加语义（用户 2026-09-30 定）：原文一个字都不动，译文另起一行跟在后面；
 * 翻译失败时**不写任何东西**（失败路径根本不经过这里）。
 * 截断规则（用户 2026-09-30 定）：超出字节上限时**从前面开始取、舍弃后面的**。
 */
internal object TranslationText {

    /**
     * 单次读取上限（字符）：防止超大文档整篇走 Binder 回包（宿主侧
     * `TransactionTooLargeException` 的触发源，见 `JinnIme.readTextBeforeCursor`）。
     *
     * 它同时是「字节上限」可填范围的上界（[MAX_MAX_BYTES] 直接引用它，2026-10-01 审查 L-223）：
     * 二者曾经脱节（窗口 50000 / 可填 1000000），用户填到窗口之外时实际生效的仍是窗口值，
     * 日志却按填的值报「已截断」—— 阈值失真。绑在一起后读取窗口恒等于填写的字节上限。
     *
     * 100000 字符 = 200 KB（UTF-16）走 Binder 往返，在事务缓冲最小的机型（1 MB）上也留有充足余量。
     */
    const val MAX_READ_CHARS = 100_000

    /** 单个 Provider 的字节上限可填写范围（设置页与备份导入共用同一钳位） */
    const val MIN_MAX_BYTES = 1

    /** 可填上界 = 读取上限：一个字符至少 1 字节 ⇒ 不超过窗口的字节数一定读得全 */
    const val MAX_MAX_BYTES = MAX_READ_CHARS

    /** 截取结果：上传的原文 + 追加位置 */
    internal class Slice(
        /** 上传给服务端的原文（已按字节上限从头部截断、两端去空白） */
        val text: String,
        /** 是否因字节上限被截断（日志与提示用） */
        val truncated: Boolean,
        /** 译文追加位置：0 = 光标处；N = 光标后第 N 个单元（整行 / 整篇模式取区间末尾） */
        val appendOffset: Int,
        /**
         * [appendOffset] 指向的位置是否**确定**是原文区间末尾。
         *
         * `after` 只读到读取上限（[MAX_READ_CHARS] / 字节上限）时就分不清「本行 / 本篇到此结束」
         * 与「窗口读满了」—— 此时 `appendOffset` 会落在行中 / 文中，照它移光标会把译文插进
         * 用户句子的中间。调用方据此拒绝提交（2026-10-01 审查 L-213）。
         */
        val appendOffsetExact: Boolean,
    )

    /**
     * 取待译原文。
     *
     * [before] 是光标前文本（**尾部对齐**：取不满时拿到的是紧邻光标的那一段），[after] 是光标后
     * 文本（头部对齐），两者的长度上限由调用方按 [MAX_READ_CHARS] 把住；
     * [afterTruncated] 表示 [after] 是否**读满了窗口**（调用方按 `after.length >= limit` 判定，
     * 无法区分「恰好读完」与「还有内容」时按后者处理）。
     *
     * [Slice.text] 为空串表示「这段范围里没有可翻译的文字」，调用方据此提示
     * （零宽字符由调用方的 `hasVisibleContent` 再筛一道）。
     */
    fun extract(
        before: CharSequence,
        after: CharSequence,
        scope: TranslationScope,
        maxBytes: Int,
        afterTruncated: Boolean = false,
    ): Slice {
        val raw: String
        val appendOffset: Int
        var exact = true
        when (scope) {
            TranslationScope.LINE_BEFORE -> {
                raw = lineHead(before)
                appendOffset = 0
            }
            TranslationScope.LINE_FULL -> {
                // `lineHead` 只在「按行」的两档需要（2026-10-02 修复 L-432）：BEFORE_ALL / ALL
                // 此前也先算一遍，白付一次全窗口扫描 + 最多 10 万字符的 substring 拷贝，
                // 而这条路径由点击直接跑在 IME 主线程上（键盘场景对主线程停顿敏感）
                val head = lineHead(before)
                // 本行到哪结束：与 [lineHead] 同源（[firstLineBreak] 认 `\n` / `\r` / Unicode 行终止符），
                // 否则 CR-only / U+2028 文档里「本行」的两端会用两套判据（2026-10-03 修复 L-499）
                val nl = firstLineBreak(after)
                val tailLen = if (nl < 0) after.length else nl
                raw = head + after.subSequence(0, tailLen)
                appendOffset = tailLen
                // 窗口读满且窗口内没有换行 ⇒ 本行可能还没结束，末尾位置不可信
                exact = nl >= 0 || !afterTruncated
            }
            TranslationScope.BEFORE_ALL -> {
                raw = before.toString()
                appendOffset = 0
            }
            TranslationScope.ALL -> {
                raw = before.toString() + after
                appendOffset = after.length
                // 文档比读取窗口长时，after 的末尾不是全篇末尾
                exact = !afterTruncated
            }
        }
        // 先 trim、再截断（2026-10-02 修复 L-433）：顺序反了时首尾空白先吃掉字节配额、
        // 随后又被丢掉 —— 缩进代码块 / 网页复制的行首空格会让上传量凭空少一截
        // （方向安全，属「少翻」而非「多发」）。`trimEnd()` 兜住「截断刀口留在空白上」的情形。
        val trimmed = raw.trim()
        val (cut, truncated) = takeHeadBytes(trimmed, maxBytes)
        return Slice(cut.trimEnd(), truncated, appendOffset, exact)
    }

    /**
     * 选区模式：用户选中的整段就是原文。
     *
     * 与 [extract] 的差别只在挑选区间的方式 —— 那里按「光标前 / 整行 / 全篇」去切，而选区已经是
     * 用户的明确选择，再按行切会把选中的内容丢掉一部分。截断方向与其他模式一致：从前面取、舍弃后面的。
     */
    fun extractSelection(text: CharSequence, maxBytes: Int): Slice {
        // 与 [extract] 同款顺序（2026-10-02 修复 L-433）：先 trim 再截断，别让首尾空白吃配额
        val (cut, truncated) = takeHeadBytes(text.toString().trim(), maxBytes)
        return Slice(cut.trimEnd(), truncated, 0, true)
    }

    /**
     * 行分隔符：`\n`、`\r`，以及 Unicode 行终止符（U+2028 / U+2029 / U+0085 / U+000B / U+000C）。
     *
     * ⚠ 只认 `\n` 是不够的（2026-10-03 修复 L-499）：CR-only 的文本（部分应用粘贴、网页编辑器、
     * PDF 复制）与以 U+2028 分隔的文本里，「光标前本行」会**静默退化成整段上文** ——
     * 而默认档对用户的承诺恰恰是「只上传本行、上传面最小」（设置页有明确文案）。
     * 退化成最多 `maxBytes` 字节的上文，等于把对外承诺打破，且用户毫无察觉。
     */
    private val LINE_BREAKS = charArrayOf('\n', '\r', '\u2028', '\u2029', '\u0085', '\u000B', '\u000C')

    /**
     * 单个字符是否是行分隔符（见 [LINE_BREAKS]）。
     *
     * 供提交阶段的「落点是不是区间末尾」复用（2026-10-03 第十二轮审查）：那一处此前只认 `'\n'`，
     * 而行分隔符在 L-499 已扩到 CR / U+2028 / U+0085… ⇒ CR-only 与 U+2028 文档里
     * 「光标所在整行 / 编辑框全部」两档会在**已付费之后**被判成「落点不对」而拒绝。
     * 同一个概念必须只有一处判据。
     */
    internal fun isLineBreak(c: Char): Boolean = c in LINE_BREAKS

    /** [text] 里最后一个行分隔符（见 [LINE_BREAKS]）的下标；`-1` = 没有 */
    private fun lastLineBreak(text: CharSequence): Int {
        for (i in text.length - 1 downTo 0) {
            if (text[i] in LINE_BREAKS) return i
        }
        return -1
    }

    /** [text] 从 [from] 起第一个行分隔符的下标；`-1` = 没有 */
    private fun firstLineBreak(text: CharSequence, from: Int = 0): Int {
        for (i in from until text.length) {
            if (text[i] in LINE_BREAKS) return i
        }
        return -1
    }

    /**
     * [text] 里是否含任何行分隔符（见 [LINE_BREAKS]）。
     *
     * 供「行首能否定位」的日志判据复用：此前那边只查 `\n`，**措辞与事实相反**
     * （说「上传的是光标前末段」，实际是整段上文），排障时会被误导（2026-10-03 修复 L-499）。
     */
    internal fun hasLineBreak(text: CharSequence): Boolean = lastLineBreak(text) >= 0

    /**
     * 光标所在行的行首之后那一段（[before] 里最后一个**行分隔符**之后的部分；没有就是整段）。
     *
     * 判据取自 [lastLineBreak]（`\n` / `\r` / Unicode 行终止符）—— 只认 `\n` 会让默认档
     * 在 CR-only / U+2028 文档里静默上传整段上文（2026-10-03 修复 L-499）。
     */
    private fun lineHead(before: CharSequence): String {
        val nl = lastLineBreak(before)
        return if (nl < 0) before.toString() else before.substring(nl + 1)
    }

    /**
     * 从头部取到不超过 [maxBytes] 个 **UTF-8 字节**（统一截断规则：
     * 超限时舍弃后面的）。
     *
     * 逐**码位**累加，而不是先编码成字节数组再切：后者要么多一次整段拷贝，要么把刀落在
     * UTF-8 序列中间 —— 半个汉字 / 半个 emoji 进请求体后会被替换成 `?`，服务端看到的
     * 与用户屏幕上看到的不是一个东西。按码位走天然落在字符边界（代理对也不会被切开）。
     */
    fun takeHeadBytes(text: CharSequence, maxBytes: Int): Pair<String, Boolean> {
        if (maxBytes <= 0) return "" to text.isNotEmpty()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) return text.subSequence(0, i).toString() to true
            bytes += size
            i += Character.charCount(codePoint)
        }
        return text.toString() to false
    }

    /**
     * 追加译文的提交文本：原文一个字都不动（它已经在输入框里），这里只补「前导换行 + 译文」。
     *
     * [prev] 是**插入点前面那一个字符**（调用方按追加位置的上下文给）：它本来就是换行
     * （用户自己敲了空行，或追加在整行 / 整篇末尾）时不再补前导换行，避免多出一个空行。
     */
    /**
     * 剥掉模型 / 网关给译文加的**包装**（2026-10-02 修复 L-321）。
     *
     * 六家的出口原本只做 `trim()`：OpenAI 兼容那家最容易中 —— 模型即使被提示「只输出译文」，
     * 也常在首尾加上 ``` 代码块围栏，或「译文：」「以下是翻译：」这类前缀；这些内容会**原样插进
     * 用户的输入框**。这里只剥**成对 / 明确**的首尾包装：
     *  ① 首行 ``` 与末行 ``` 成对的代码块围栏（带语言标注也算）；
     *  ② 「译文：」「翻译：」「以下是翻译：」「Translation:」等首前缀；
     *  ③ 整体被一对引号包住（直引号 / 弯引号 / 书名号式引号）。
     * **剥离后为空则回退原值** —— 宁可留着围栏，也绝不把有效译文剥没。
     */
    /**
     * 包装剥离用的两个正则：**提到 object 级只编译一次**（2026-10-03 修复 L-636）。
     *
     * 此前它们写在 `stripWrapper` 函数体内，每次翻译（成功与 2xx 承载失败都算）都会重新
     * 编译两份 `Pattern` —— 与本仓「正则与常量都是单例」的口径不一致（Prompt 模板那边早已如此）。
     * `Regex` 线程安全，六家共用同一出口。
     */
    private val WRAPPER_FENCE_RE = Regex("^```[^\\n]*\\n([\\s\\S]*?)\\n?```$")

    private val WRAPPER_PREFIX_RE =
        Regex("^(?:译文|翻译|以下是翻译|翻译结果|Translation|Translated)\\s*[:：]\\s*")

    fun stripWrapper(text: String): String {
        var t = text.trim()
        WRAPPER_FENCE_RE.find(t)?.let { t = it.groupValues[1].trim() }
        t = t.replace(WRAPPER_PREFIX_RE, "").trim()
        if (t.length >= 2) {
            val pairs = mapOf('"' to '"', '\'' to '\'', '\u201c' to '\u201d', '\u2018' to '\u2019', '「' to '」', '『' to '』')
            if (pairs[t.first()] == t.last()) t = t.substring(1, t.length - 1).trim()
        }
        return t.ifEmpty { text.trim() }
    }

    fun appendText(prev: Char?, translated: String): String {
        val body = translated.trim()
        // 空 / 纯零宽译文只返回空串：绝不留下孤立换行或不可见字符（上游按 EMPTY 拦截，
        // 这里是纯函数兜底；零宽判据见 [hasVisibleContent]），2026-09-30 第二轮审查
        if (!body.hasVisibleContent()) return ""
        if (prev == null || prev == '\n') return body
        return "\n$body"
    }
}

/**
 * Provider 的最小契约：造请求、解析响应 —— 两件都必须是纯函数，好让 JVM 单测直接断言
 * URL / Header / 签名 / 错误分类，不需要真网络。
 *
 * 网络执行、超时与回调线程约定都在 [TranslationClient] 一侧。
 */
internal interface TranslationProvider {

    /** 源语言两边都走自动检测（Azure 不传 from、百度 from=auto），目标语言由应用层决定 */
    fun buildRequest(text: String, target: TranslationLanguage): Request

    /** [code] 是 HTTP 状态码，[body] 可能为 null（读体失败）；实现必须自己兜住非法 JSON */
    fun parseResponse(code: Int, body: String?): TranslationOutcome

    /**
     * 该 Provider 期望的**整体**超时（秒）；`0` = 用 [TranslationClient] 的默认预算。
     *
     * 当前**两家**覆写：OpenAI 兼容（用户可调，默认 60s）与百度大模型（固定 60s，见
     * [BaiduLlmTranslator]）—— 都是「首字延迟不可控、20s 默认预算会把长句打成假超时」的大模型接口。
     * 其余四家是 10s 级返回的 REST 接口，用默认预算，不需要各自的旋钮。
     */
    val callTimeoutSec: Int get() = 0

    /**
     * 响应是否**按输入行对齐**（每行一条译文；服务端对空行 / 被跳过的行可能**根本不返回元素**）。
     *
     * 百度系两家（通用版 / 大模型）为 `true`：`trans_result` 只给数组，靠**顺序**与输入行对应 ——
     * 服务端「少给行」时其后所有行上移一位，译文与原文**逐行错位**（2026-10-02 修复 L-483）。
     * 解析侧手里只有响应体、无法自证，所以由出口（[TranslationClient]）按本标志做一次对拍，
     * 宁可拒绝也不把错位译文写进用户正文。
     */
    val alignsPerLine: Boolean get() = false
}

/**
 * 从 JSON 对象取字符串字段：**显式 `null` 与类型不符都返回 null**。
 *
 * `optString(key)` 对「存在但为 JSON null」返回的是字面量 `"null"`（不是空串），
 * `isNullOrBlank()` 拦不住它 —— 字面量 `null` 会被当译文提交进用户输入框。
 * `optString` 对数组/对象还会回序列化文本，同样是假译文。六家 Provider 统一走这里，
 * 不再各写各的口径（OpenAiTranslator 的 `keyOf` 早就过滤了 `JSONObject.NULL`，
 * 其余各家漏了，属内部不一致 —— 2026-09-30 审查发现）。
 */
internal fun jsonText(json: JSONObject?, key: String): String? = json?.opt(key) as? String

/**
 * 取**可能是数字**的字符串字段（错误码、状态码）。
 *
 * 百度的 `error_code` 官方示例写成字符串，但反过来（数字）也出现过：只认 String 会让
 * `{"error_code":54003}` 落进成功分支，`trans_result` 缺失后报「翻译结果为空」——
 * 用户拿着真实错误码（配额用尽）去查「结果为空」（2026-10-01 审查 L-226）。
 * JSON null 走 [JSONObject.NULL] 哨兵，必须显式排除，否则会取到字面量 `"null"`。
 */
internal fun jsonCode(json: JSONObject?, key: String): String? {
    val raw = json?.opt(key) ?: return null
    if (raw === JSONObject.NULL) return null
    return raw.toString()
}

/**
 * 是否含**可见内容**（非空白、且非零宽字符）。
 *
 * ⚠ 判据是 `Char.isWhitespace()`，它在 JVM 上是 `Character.isWhitespace(c) || Character.isSpaceChar(c)`：
 * **ZWSP（U+200B）与 BOM（U+FEFF）返回 false**（`Cf` 类，Java 明列为非空白），而
 * **NBSP（U+00A0）返回 true**（它是 `Zs`，被 `isSpaceChar` 接住）—— 两种都判不出来，要靠
 * [ZERO_WIDTH] 兜。少一个码位就多一条「一个不可见字符也算有内容」的漏洞：用户从网页复制来的
 * 零宽字符会被当成有内容 ⇒ 发出一次真实（计费）请求，而模型面对一个不可见字符**可能编造整句译文**
 * 并把它追加进用户正文（2026-09-30 第二轮审查发现）。反向同理：服务端若只回零宽字符，也不能写回。
 *
 * ⚠ 但**判空不等于可写**：`U+202E`（RLO）这类双向文本控制符既非空白也不在 [ZERO_WIDTH] 里，
 * 「有可见内容」成立，写进输入框却会改变**用户自己那段文字**的显示顺序 —— 见 [sanitizedForOutput]。
 */
internal fun CharSequence.hasVisibleContent(): Boolean {
    var i = 0
    while (i < length) {
        // 按**码位**遍历（2026-10-02 修复 L-339）：TAG 字符是代理对，逐 `Char` 判时低代理
        // 既非空白、也不在任何表里 ⇒ 一串纯 TAG 文本会被判「有内容」，一段用户看不见的东西
        // 被写进输入框并随复制传染 —— 正是本条要关掉的那条路。
        val cp = Character.codePointAt(this, i)
        // ⚠ 必须**同时**问 `isWhitespace` 与 `isSpaceChar`（2026-10-02 修复）：JDK 的
        // `Character.isWhitespace(int)` **明确排除非断行空格**（U+00A0 / U+2007 / U+202F），
        // 而本函数的语义（下面 KDoc 与全部调用点）是 Kotlin `Char.isWhitespace()` 那一套
        // （= `isWhitespace || isSpaceChar`）。少了这一半，一个 U+202F（窄 NBSP，从网页复制极常见）
        // 就足以让「纯不可见内容」过检 ⇒ 发出一次真实（计费）请求，而模型面对它可能编造整句译文
        // 并写进用户正文。U+00A0 另有 [ZERO_WIDTH] 兜住，U+2007 / U+202F 依赖这里。
        if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !isInvisibleCodePoint(cp)) {
            return true
        }
        i += Character.charCount(cp)
    }
    return false
}

/**
 * **判空**用的「不可见全集」（2026-10-01 修复 L-324）。
 *
 * 在 [ZERO_WIDTH] 之外再补几个「本身没有内容、只在依附于基字符时才有意义」的面：
 * 软连字符、不可见运算符、变体选择符、TAG 字符（后者能编码出一串隐藏 ASCII）。
 * 少了它们，服务端只回一个 `U+FE0F` 或一串 TAG 字符也能过「有没有内容」这一关，
 * 于是一段**用户看不见的东西**被写进输入框，并随复制继续传染到邮件与网页表单。
 *
 * ⚠ 只用于**判空，不用于剥除**：变体选择符与 TAG 字符在 emoji 序列（❤️ / 旗帜）里是语义相关的，
 * 剥掉会破坏正文；但一串**只有它们**的「译文」没有任何意义，不能当成有效结果写回。
 */
private fun isInvisibleCodePoint(cp: Int): Boolean = when {
    cp in ZERO_WIDTH_CODEPOINTS -> true
    cp == 0x00AD -> true                      // 软连字符
    cp in 0x2061..0x2064 -> true               // 不可见运算符（U+2060 已在 ZERO_WIDTH 里）
    cp == 0x061C -> true                      // ALM（阿拉伯字母标记）：同族，判空侧也要认（L-343）
    cp in 0xFE00..0xFE0F -> true               // 变体选择符（没有基字符时无意义）
    cp in 0xE0000..0xE007F -> true             // TAG 字符（按**码位**整段覆盖，2026-10-02 修复 L-339）
    cp == 0x2800 -> true                      // 盲文空白（L-343）
    cp == 0x115F || cp == 0xFFA0 -> true       // Hangul 填充符（L-343）
    else -> false
}

/** 常见「看起来是空、`isWhitespace()` 却不认」的零宽字符（含 NBSP：网页/文档复制的常客） */
private const val ZERO_WIDTH = "\u200B\u200C\u200D\u2060\uFEFF\u00A0"

/**
 * [ZERO_WIDTH] 的码位形态（判空按码位遍历，与 Char 表同源等值）。
 *
 * ⚠ 必须定义在 [ZERO_WIDTH] **之后**：顶层 `val` 按文件内声明顺序初始化，
 * 反过来写在前面会直接编译失败（"must be initialized"）。
 */
private val ZERO_WIDTH_CODEPOINTS: Set<Int> = ZERO_WIDTH.map { it.code }.toSet()

/**
 * 双向文本控制符：本身看不见，却会改变**其后同段落**里字符的显示顺序。
 *
 * 插入一个 `U+202E`（RLO）之后，用户自己写的文字会按从右到左渲染 —— 看到的顺序与真正存进去的
 * 顺序不一致，而这段文字会随复制、发送流到邮件、工单与代码 diff 里继续传染（经典的 trojan source）。
 * 译阿拉伯语 / 希伯来语 / 波斯语时，模型**自发**带上 `U+200E` / `U+200F` 也是常态。
 */
private const val BIDI_CONTROLS =
    "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

/**
 * 译文落地前的净化（2026-10-01 修复 L-314）。
 *
 * 服务端返回的文本是**不可信输入**：它既可能夹着双向文本控制符（改变用户文字的显示顺序），也可能
 * 夹着 C0/C1 控制符 —— `U+0000` 在部分宿主上等同字符串终止符、`U+0085` 是「看不见的换行」、
 * `U+001B` 开头的转义序列会在用户把这段文字复制到终端之后才发作。这些字符**都不会**让
 * [hasVisibleContent] 判空：那个判据管的是「有没有内容」，管不了「内容里夹了什么」。
 *
 * 规则：保留 `\n` 与 `\t`（译文本就是多行的），其余 C0、`DEL`、C1 与双向控制符一律剥掉。
 * 只剥不可见字符，**不动任何可见字符**。
 */
internal fun CharSequence.sanitizedForOutput(): String {
    var dirty = false
    for (c in this) {
        if (c.isInvisibleControl()) {
            dirty = true
            break
        }
    }
    if (!dirty) return toString()
    val sb = StringBuilder(length)
    for (c in this) if (!c.isInvisibleControl()) sb.append(c)
    return sb.toString()
}

private fun Char.isInvisibleControl(): Boolean = when {
    this == '\n' || this == '\t' -> false
    this < ' ' -> true                    // C0（含 \r、\u0000、ESC）
    this == '\u007F' -> true              // DEL
    this in '\u0080'..'\u009F' -> true     // C1（含 U+0085 NEL ——「看不见的换行」）
    this in BIDI_CONTROLS -> true         // 双向文本控制符
    this == '\u061C' -> true              // ALM：改显示序（与 BIDI 同族），此前漏在剥除表外（L-343）
    else -> false
}

/**
 * 凭据/端点取值的统一清洗：**剥掉不可见字符再 trim**。
 *
 * 为什么必须剥（2026-09-30 第二轮审查，并用探针单测实测确认）：这些字符从网页/文档复制时极常见，
 * 而 `String.trim()` **不删**它们（trim 只删 ≤ U+0020）。带着 NBSP 的 Key 进 OkHttp 的
 * `header("Authorization", …)` 会抛 `IllegalArgumentException: Unexpected char 0xa0`，
 * 被 TranslationClient 兜成 PARAM → 用户看到「请检查语言设置」，与真实原因（Key 里混了个不可见字符）
 * 完全无关，而且肉眼永远查不出来。
 */
internal fun String.cleanCredential(): String = filter { it !in ZERO_WIDTH }.trim()

/**
 * Azure 区域的归一：**小写、去空格与连字符**（2026-10-02 修复 L-347）。
 *
 * `Ocp-Apim-Subscription-Region` 头要求 `eastasia` / `global` 这类标识符形态，而 Azure 门户上
 * 显示给用户的是 **`East Asia`**（带空格、首字母大写）。照抄进输入框 → 头值非法 → 服务端 401
 * → 归 AUTH → 提示只说「检查凭据」，用户重贴一遍 Key 仍然失败 —— 整条链上没有一处指向真因。
 * 写侧（[Prefs.azureRegion]）与显式保存的核对侧共用这一个函数，避免两侧口径分叉（L-365 的同族坑）。
 */
internal fun normalizeAzureRegion(raw: String): String =
    // 只保留 ASCII 小写字母与数字（区域名的官方形态：`eastasia` / `global` / `westus2`）。
    // ⚠ 不要只 `replace(" ", "")`（2026-10-02 修复：上一轮就是这么写的，漏掉 U+3000 全角空格 ——
    // 中文输入法空格键的默认输出）。`East\u3000Asia` 会带着一个**非法头值字符**进
    // `Ocp-Apim-Subscription-Region`，OkHttp 抛 `IllegalArgumentException`、被兜成 PARAM，
    // toast 却说「语言方向 / 模型名 / 路径 / 原文长度」—— 比它要修的 401 更难查。
    // `cleanCredential()` 仍先跑：它负责剥零宽族与两端空白，本行负责收口到合法字符集。
    raw.cleanCredential().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
