package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 凭据回写守卫的两条基线（BUG.md L-970）。
 *
 * 关键场景是「粘贴 → 停顿落盘 → 全选删空 → 离开页面」：只按**载入时**判改动时，最后那次清空
 * 会被当成没改过 ⇒ 盘上的密文留着、界面却显示该家未配置（用户以为已经删掉）。
 *
 * 键用字符串：守卫的判据只与「记下的值 / 当前值」有关，`EditText` 那组重载只是把字段当键用。
 */
class CredentialSaveGuardTest {

    @Test
    fun `落盘之后清空字段仍算改动`() {
        val guard = CredentialSaveGuard()
        guard.remember(KEY, "")
        assertEquals("打开页面直接返回：不回写", false, guard.changed(KEY, ""))
        assertEquals("粘贴进来的值要回写", true, guard.changed(KEY, "AKIA-EXAMPLE"))
        guard.markWritten(KEY, "AKIA-EXAMPLE")
        assertEquals("没再动过：不重复回写", false, guard.changed(KEY, "AKIA-EXAMPLE"))
        assertEquals("删空仍要回写（写空串的语义是删掉该键）", true, guard.changed(KEY, ""))
    }

    @Test
    fun `载入基线只回答用户动过没有`() {
        val guard = CredentialSaveGuard()
        guard.remember(KEY, "")
        guard.markWritten(KEY, "AKIA-EXAMPLE")
        // 相对上次落盘没变 ⇒ 不回写；相对载入时变过 ⇒ 回读校验要查它
        assertEquals(false, guard.changed(KEY, "AKIA-EXAMPLE"))
        assertEquals(true, guard.edited(KEY, "AKIA-EXAMPLE"))
        guard.reset()
        assertEquals("复位后没记录过的一律按动过处理（防止新增字段被漏保存）", true, guard.edited(KEY, "AKIA-EXAMPLE"))
    }

    private companion object {
        const val KEY = "aliyun-access-key-id"
    }
}
