package com.jinn.inputmethod

/**
 * AI 文本动作（2026-10-09 新增）。
 *
 * 把现有「翻译」泛化为「对选中 / 光标处文本执行一次 LLM 变换」：
 *  - 单击翻译键 = [DEFAULT]（翻译，行为与改造前逐字一致）
 *  - 长按翻译键 = 候选栏整条切到 [EXPANDABLE] 动作排（不含 TRANSLATE —— 它是单击语义）
 *  - 落地方式 v1 不区分：有选区替换选区、无选区追加下一行（见 `JinnIme.appendTranslation`）
 *
 * ⚠ 只有支持自定义提示词的 Provider（当前仅 OpenAI 兼容）能执行非 TRANSLATE 动作，
 *    判据见 [TranslationProviderId.supportsActions]；其余服务方（阿里云 / Azure / 百度 /
 *    DeepL / 百度大模型）接口承载不了提示词任务。
 */
internal enum class AiAction(val id: String, val label: String) {
    TRANSLATE("translate", "翻译"),
    POLISH("polish", "润色"),
    FORMAL("formal", "正式"),
    CASUAL("casual", "口语"),
    SHORTEN("shorten", "精简"),
    EXPAND("expand", "扩写"),
    SUMMARY("summary", "提炼"),
    PROOFREAD("proofread", "纠错"),
    REPLY("reply", "回复"),
    ;

    companion object {
        /** 单击翻译键执行的动作 */
        val DEFAULT = TRANSLATE

        /** 长按展开时展示的动作（顺序即界面顺序；TRANSLATE 不在其中） */
        val EXPANDABLE: List<AiAction> = entries.filter { it != TRANSLATE }
    }
}
