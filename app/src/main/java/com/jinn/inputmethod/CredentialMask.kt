package com.jinn.inputmethod

/**
 * 凭据输入框的「已填过」提示形态：**前 3 位 + 掩码**（例 `b86••••••••`）。
 *
 * 凭据加密落盘，页面回填的是解密真值。若把真值直接塞进输入框，重进页面看到空框会被误认成
 * 「没保存」而反复重填。故未聚焦时只露前 3 位，既不暴露完整凭据，又明确表示「已经填过」。
 *
 * 掩码长度恒定，不随凭据长度变化：否则「几个点」就等于把 Key 的长度说出去了。
 *
 * ⚠ **硬性错误防线**：掩码串一旦被当成输入写回磁盘，凭据就永久变成 `b86••••••••`。
 * 故 [looksMasked] 是保存路径的必过关卡 —— 形态像掩码的字段一律折回磁盘原值
 * （见 `VoiceSettingsActivity.credentialText`）。
 * 判据只看是否含掩码字符；真值字符集不含 U+2022，任意长度的真值（含短值）均不会被误判。
 * 另两页（OpenAI / 翻译配置）只走 [CredentialSaveGuard] 的双基线挡误写，不识别掩码。
 */
internal object CredentialMask {

    /** 掩码字符（U+2022，与界面上的圆点一致；ASCII 之外的字符不在任何凭据字符集里） */
    const val CHAR = '•'

    /** 固定 8 个点：不透露真实长度 */
    const val DOTS = "••••••••"

    /**
     * 掩码形态：前 3 位 + 8 个点；凭据不足 3 位就全显 + 点（`take(3)` 自身就是这个语义）。
     *
     * @return 空串进、空串出（未配置的字段留在 hint 状态，不显示掩码）
     */
    fun of(plain: String): String = if (plain.isEmpty()) "" else plain.take(3) + DOTS

    /** 文本是否为掩码形态（含掩码字符即算：保存时据此沿用磁盘原值） */
    fun looksMasked(text: String): Boolean = text.indexOf(CHAR) >= 0
}
