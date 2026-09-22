package com.jinn.inputmethod

import android.view.inputmethod.EditorInfo

/**
 * 输入框敏感度判定（纯函数，便于 JVM 单测）。
 *
 * 决定「能不能学用户词频」：密码框、显式声明不联想的框、以及无文本输入语义的
 * TYPE_NULL，都不能把用户敲进去的内容写进本地词频文件，那等于把口令片段
 * 存成长期明文，还会让它在之后任何输入框里被优先推荐出来。
 *
 * 规则：
 *  - `TYPE_NULL`（值为 0，不是"未知"）→ 敏感：宿主明确表示这里没有文本输入语义
 *  - 文本/数字类的密码变体（PASSWORD / VISIBLE_PASSWORD / WEB_PASSWORD / 数字密码）→ 敏感
 *  - 带 `TYPE_TEXT_FLAG_NO_SUGGESTIONS` → 敏感（宿主要求不要联想、不要学习）
 *  - `imeOptions` 带 `IME_FLAG_NO_PERSONALIZED_LEARNING` → 敏感（宿主声明「不要个性化学习」
 *    的标准方式；拿不到 password 变体的 App 走这条）。
 *    imeOptions 侧没有公开的「不要联想」常量（`IME_FLAG_NO_SUGGESTIONS` 是 @hide，
 *    公开 SDK 引用它会直接编译失败），所以这里只认这一个标志。
 *
 * 只引用 [EditorInfo] 的编译期常量（static final int 会被编译器内联），
 * 所以纯 JVM 单测能直接调用，不依赖 Robolectric 或 `returnDefaultValues`。
 */
object InputFieldPrivacy {

    /**
     * 该输入框是否应暂停用户词频学习。
     *
     * @param inputType [EditorInfo.inputType]；取不到 EditorInfo 时传 null。
     * @param imeOptions [EditorInfo.imeOptions]；取不到 EditorInfo 时传 0（0 表示没有声明）。
     *
     * 不能用 0 表示"未知"，[EditorInfo.TYPE_NULL] 的值就是 0，
     * 拿 0 当缺省会把空值误判成 TYPE_NULL，必须区分可空与真实取值。
     */
    fun suppressLearning(inputType: Int?, imeOptions: Int = 0): Boolean {
        // imeOptions 与 inputType 是两条独立通道，宿主在任一条里声明「不要学」就成立。
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        if (inputType == null) return false   // 信息缺失：宁可继续学习，不降级正常输入
        if (inputType == EditorInfo.TYPE_NULL) return true
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        when (variation) {
            EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD,
            -> return true
        }
        return (inputType and EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
    }
}
