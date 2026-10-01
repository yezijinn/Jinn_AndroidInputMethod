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
    CHINESE("中文", "zh-Hans", "zh", "zh", "ZH"),
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
    NOT_CONFIGURED("请先在设置里填写翻译 API 凭据"),
    /** 地址不是 HTTPS（自定义 Base URL 可填出 `http://`）：明文会把 Bearer 凭据暴露在链路上 */
    INSECURE("翻译地址必须以 https:// 开头"),
    NETWORK("网络错误，请检查网络后重试"),
    TIMEOUT("翻译请求超时，请重试"),
    AUTH("认证失败：请检查凭据（API Key / AppID / SecretKey / AccessKey）"),
    QUOTA("额度不足或请求过于频繁，请稍后重试"),
    PARAM("翻译参数被服务端拒绝，请检查语言设置"),
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
        TranslationProviderId.BAIDU_LLM -> 32_768
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
        val head = lineHead(before)
        val raw: String
        val appendOffset: Int
        var exact = true
        when (scope) {
            TranslationScope.LINE_BEFORE -> {
                raw = head
                appendOffset = 0
            }
            TranslationScope.LINE_FULL -> {
                val nl = after.indexOf('\n')
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
        val (cut, truncated) = takeHeadBytes(raw, maxBytes)
        return Slice(cut.trim(), truncated, appendOffset, exact)
    }

    /**
     * 选区模式：用户选中的整段就是原文。
     *
     * 与 [extract] 的差别只在挑选区间的方式 —— 那里按「光标前 / 整行 / 全篇」去切，而选区已经是
     * 用户的明确选择，再按行切会把选中的内容丢掉一部分。截断方向与其他模式一致：从前面取、舍弃后面的。
     */
    fun extractSelection(text: CharSequence, maxBytes: Int): Slice {
        val (cut, truncated) = takeHeadBytes(text.toString(), maxBytes)
        return Slice(cut.trim(), truncated, 0, true)
    }

    /** 光标所在行的行首之后那一段（[before] 里最后一个换行之后的部分；没有换行就是整段） */
    private fun lineHead(before: CharSequence): String {
        val nl = before.lastIndexOf('\n')
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
     * 只有 OpenAI 兼容一家暴露这个值：大模型首字延迟不可控，用户可把它调到 60s 以上；
     * 其余各家的 REST 接口都在 10s 级返回，不需要各自的旋钮。
     */
    val callTimeoutSec: Int get() = 0
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
 * `isBlank()` 走 `Char.isWhitespace()`，对 **U+200B（ZWSP）/ U+FEFF（BOM）/ U+00A0（NBSP）**
 * 都返回 false：一个从网页复制来的零宽字符会被当成「有内容」⇒ 发起一次真实（计费）请求，
 * 而模型面对一个不可见字符**可能编造整句译文**并把它追加进用户正文（2026-09-30 第二轮审查发现）。
 * 反向同理：服务端若只回零宽字符，也不能当成有效译文写回。
 */
internal fun CharSequence.hasVisibleContent(): Boolean =
    any { !it.isWhitespace() && it !in ZERO_WIDTH }

/** 常见「看起来是空、`isWhitespace()` 却不认」的零宽字符（含 NBSP：网页/文档复制的常客） */
private const val ZERO_WIDTH = "\u200B\u200C\u200D\u2060\uFEFF\u00A0"

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
