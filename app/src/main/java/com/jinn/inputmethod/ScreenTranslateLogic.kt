package com.jinn.inputmethod

/**
 * 屏幕翻译的纯决策层：零 Android import、纯 JVM 可单测。
 *
 * 唯一例外：[isCapturableWindow] 里的 `AccessibilityWindowInfo.TYPE_APPLICATION` ——
 * 那是 `static final int` 常量（编译期折叠，不加载类、不产生 Stub!），全限定引用即可。
 *
 * 分工：[ScreenTextCollector] 只做「遍历 + 过滤」，[ScreenTranslateService] 只做
 * 「从框架取原始值 + 执行副作用」，一切判断收口在本文件。
 */
internal object ScreenTranslateLogic {

    /**
     * 过滤规则①：trim 后长度 ≥ 2、含可见内容、且含至少一个字母 / 数字。
     *
     * 可见内容判据复用 [hasVisibleContent]（`Translation.kt` 的 `CharSequence.hasVisibleContent()`，
     * 按码位遍历 + 零宽全集：ZWSP / BOM / NBSP / 变体选择符 / TAG 字符都算「没有内容」）——
     * 零宽表全仓只此一份，别再写第二份（两份实现必然漂移）。
     *
     * `length >= 2` 与 `isLetterOrDigit` 各挡一类噪声：单字符是图标角标 / 播放器倍速标签，
     * 纯符号段（`·—` / `》` / `——`）是分隔线与装饰 —— 两者都进过列表的话，
     * 用户要在几十行噪声里挑正文。
     */
    fun isCollectable(trimmed: String): Boolean =
        trimmed.length >= 2 && trimmed.hasVisibleContent() && trimmed.any { it.isLetterOrDigit() }

    /**
     * 「整节点」判据：只有取自 `node.text` 且与节点全文（trim 后）完全一致的段，才允许替换。
     *
     * `ACTION_SET_TEXT` 是**整段替换**语义 —— 段比节点短（或段取自 contentDescription）时替换会
     * 吞掉节点里剩下的内容。判为 false 即不给「替换原文」按钮，从根上排除这类误写。
     */
    fun isWholeNode(segmentText: String, nodeText: String?): Boolean =
        nodeText != null && segmentText == nodeText.trim()

    /**
     * 勾选归并：按面板顺序以 `\n` 拼装上传文本，再按 provider 字节上限截断。
     *
     * 返回 `null` = **不发请求**（未勾选 / 勾选内容全是空白）。这是 INV-2 的第一道：
     * 调用方拿到 null 就不组 provider、不建 Call。
     *
     * 多段合并成一次请求（而不是逐段各发一次）：省往返也省每次请求的固定开销；
     * 代价是译文不再与段一一对应，所以只勾一段时才允许替换（见 [replaceable]）。
     */
    fun composeSource(picked: List<String>, maxBytes: Int): Pair<String, Boolean>? {
        val joined = picked.filter { it.isNotEmpty() }.joinToString("\n")
        // 判据用 hasVisibleContent 而不是 isBlank：`isBlank()` 只认空白，一串零宽字符
        // （ZWSP / BOM / TAG，网页复制极常见）会被当成有内容 ⇒ 发出一次真实计费请求，
        // 而模型面对一串不可见字符**可能编造整句译文**（Translation.kt 的 L-324 / L-339 同因）。
        if (!joined.hasVisibleContent()) return null
        val (cut, truncated) = TranslationText.takeHeadBytes(joined, maxBytes)
        return if (cut.hasVisibleContent()) cut to truncated else null
    }

    /**
     * 目标语言：非 OpenAI 走 [decideTranslateTarget]（中⇄英对称对调，日 / 韩同语时报「已是目标语言」），
     * OpenAI 自由文本不互换 —— 与 IME 侧同口径。
     *
     * 返回 [TranslateTarget] 而不是一个语言：日 / 韩同语时**不能发请求**（发了只会原样返回且真计费，
     * L-1131），调用方必须处理 `AlreadyTarget` 这一支。
     */
    fun decideTarget(raw: String, id: TranslationProviderId, targetName: String): TranslateTarget {
        val base = TranslationLanguage.of(targetName)
        return if (id != TranslationProviderId.OPENAI) {
            decideTranslateTarget(raw, base)
        } else {
            TranslateTarget.To(base)
        }
    }

    /**
     * 行带归并键：top 差小于一个行带视为同一行，行内再按 left 排。
     *
     * 为什么不能直接用 DFS 顺序：`ConstraintLayout` / `RelativeLayout` 的子节点顺序 = **添加顺序**，
     * 与屏幕上下顺序无关（v0-B 曾据此写出「系统保证按视觉顺序」，是错的）。行带阈值由调用方按
     * `24dp × density` 传进来（纯层不碰 displayMetrics）。
     *
     * `bandPx <= 0` 时退化为「按 top 精确分组」，避免除零。
     */
    fun bandOf(top: Int, bandPx: Int): Int = if (bandPx <= 0) top else top / bandPx

    /**
     * 可替换判据里**不随时间变化**的三闸：单段 + 可编辑 + 整节点 + 未被截断。
     * 另两闸（窗口未切换、节点文本未变）必须在点「替换」那一刻用实时值再判一次，见服务层。
     */
    fun replaceable(pickedCount: Int, editable: Boolean, wholeNode: Boolean, truncated: Boolean): Boolean =
        pickedCount == 1 && editable && wholeNode && !truncated

    /**
     * 采集窗口级过滤：只认应用窗口，且包名不是本应用。
     *
     * 挡掉四类窗口：`TYPE_SYSTEM`（快捷设置遮罩 / 状态栏 —— 不挡的话点磁贴采到的是
     * 「WLAN / 蓝牙 / 亮度」这些系统开关文字）、`TYPE_INPUT_METHOD`（键盘自己）、
     * 其它覆盖层，以及本应用（自采集面板里的原文与译文）。
     */
    fun isCapturableWindow(type: Int, pkg: String?, selfPkg: String): Boolean =
        type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
            pkg != null && pkg != selfPkg

    /** 面板高度钳制（max 小于 min 时回落 min，避免构造出非法的窗口高度）。 */
    fun clamp(v: Int, min: Int, max: Int): Int = if (max < min) min else v.coerceIn(min, max)
}
