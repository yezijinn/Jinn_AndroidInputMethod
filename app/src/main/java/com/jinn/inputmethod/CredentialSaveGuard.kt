package com.jinn.inputmethod

import android.widget.EditText

/**
 * 「只回写用户真的改过的字段」（2026-10-01 修复 L-247）。
 *
 * 为什么必须有这道闸：**凭据在解密失败时读出来是空串**（Keystore 瞬时不可用、换机、改锁屏，
 * 见 `Prefs.readCredential`），而写入空串的语义是**删除该键** —— 于是「打开设置页 → 直接返回」
 * 这一串无害动作，就会把还躺在 prefs 里的密文**永久删掉**，且不可回退（界面无任何警告）。
 *
 * 两条基线，各答一个问题（BUG.md L-970）：
 *  - [loaded]：**载入时**的值 —— 「用户动过这个字段没有」（回读校验、状态判定用它）。
 *  - [written]：**上次落盘**的值 —— 「现在要不要再写一次」（回写决策用它）。
 *
 * 只用载入基线时，输入中途落过盘的那一段会漂：粘贴 → 停顿落盘 → 全选删空之后，
 * 字段值又回到载入时的样子 ⇒ 判成「没改过」、不写，盘上的密文**留在原地**，
 * 而界面显示该家未配置（用户以为已删除）。
 *
 * 用法：载入时 [remember]、回写前 `changed(field)`、写成功后 [markWritten]、重新载入前 [reset]。
 */
internal class CredentialSaveGuard {

    private val loaded = HashMap<Any, String?>()
    private val written = HashMap<Any, String?>()

    /** 记录某字段「载入时的值」：由载入方在 `setText` 之后立即调用（两条基线同时就位） */
    fun remember(key: Any, value: String) {
        loaded[key] = value
        written[key] = value
    }

    /**
     * 该字段相对**上次落盘**是否变过（要不要回写）。
     *
     * 没记录过的一律按「变过」处理，避免将来新增字段被漏保存。
     */
    fun changed(key: Any, current: String?): Boolean = written[key] != current

    /** 该字段相对**载入时**是否变过（只问「用户动过没有」，与回写决策无关）。 */
    fun edited(key: Any, current: String?): Boolean = loaded[key] != current

    /** 落盘成功后把写入基线推到当前值 —— 不推就会漏掉「中间态落盘、随后清空」这一类改动 */
    fun markWritten(key: Any, current: String?) {
        written[key] = current
    }

    /** 重新载入前清空（`onStart` 会重新灌值，两条基线一起清） */
    fun reset() {
        loaded.clear()
        written.clear()
    }

    fun remember(field: EditText, value: String) = remember(field as Any, value)

    fun changed(field: EditText): Boolean = changed(field as Any, field.text?.toString())

    fun edited(field: EditText): Boolean = edited(field as Any, field.text?.toString())

    fun markWritten(field: EditText) = markWritten(field as Any, field.text?.toString())
}
