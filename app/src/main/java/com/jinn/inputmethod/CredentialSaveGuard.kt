package com.jinn.inputmethod

import android.widget.EditText

/**
 * 「只回写用户真的改过的字段」（2026-10-01 修复 L-247）。
 *
 * 为什么必须有这道闸：**凭据在解密失败时读出来是空串**（Keystore 瞬时不可用、换机、改锁屏，
 * 见 `Prefs.readCredential`），而写入空串的语义是**删除该键** —— 于是「打开设置页 → 直接返回」
 * 这一串无害动作，就会把还躺在 prefs 里的密文**永久删掉**，且不可回退（界面无任何警告）。
 *
 * 判据：把载入时灌进输入框的原值记下来，保存时逐字段比对 —— 只有值真的变了才回写。
 * 用户显式清空（原值非空 → 现在空）照样算「变过」，**清除凭据的能力不受影响**。
 *
 * 用法：载入时 [remember]、保存时 `if (changed(field)) 回写`、重新载入前 [reset]。
 */
internal class CredentialSaveGuard {

    private val loaded = HashMap<EditText, String>()

    /** 记录某字段「载入时的值」：由载入方在 `setText` 之后立即调用 */
    fun remember(field: EditText, value: String) {
        loaded[field] = value
    }

    /** 该字段相对载入时是否变过（没记录过的一律按「变过」处理，避免将来新增字段被漏保存） */
    fun changed(field: EditText): Boolean = loaded[field] != field.text?.toString()

    /** 重新载入前清空（`onStart` 会重新灌值） */
    fun reset() = loaded.clear()
}
