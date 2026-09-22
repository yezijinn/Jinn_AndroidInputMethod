package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「打开设置页时自动检查更新」的节流判据单测（纯函数，不依赖 Android 运行时）。
 *
 * 判据只有一行减法，但它决定用户会不会被反复打扰、或反过来被永久静默：
 * 成功检查写入时间戳后 7 天内不得再查；而 0/负数（老版本从未写过）与未来时间
 * （系统时钟回拨、外部改写 prefs）必须放行，否则 `now - last` 恒为负、永久静默。
 */
class UpdateCheckerTest {

    @Test
    fun 从未成功检查过_执行() {
        assertTrue("0 = 从未检查", UpdateChecker.shouldAutoCheck(0L, NOW))
        assertTrue("负数同属脏值，按未检查处理", UpdateChecker.shouldAutoCheck(-1L, NOW))
    }

    @Test
    fun 不足七天_跳过() {
        assertFalse("刚刚检查过", UpdateChecker.shouldAutoCheck(NOW, NOW))
        assertFalse(UpdateChecker.shouldAutoCheck(NOW - 1, NOW))
        assertFalse(UpdateChecker.shouldAutoCheck(NOW - 6 * DAY, NOW))
        assertFalse("差 1 毫秒不满七天", UpdateChecker.shouldAutoCheck(NOW - 7 * DAY + 1, NOW))
    }

    @Test
    fun 满七天_执行() {
        assertTrue(UpdateChecker.shouldAutoCheck(NOW - 7 * DAY, NOW))
        assertTrue(UpdateChecker.shouldAutoCheck(NOW - 30 * DAY, NOW))
    }

    @Test
    fun 时间戳在未来_视为未检查而不是永久静默() {
        assertTrue(UpdateChecker.shouldAutoCheck(NOW + 1, NOW))
        assertTrue(UpdateChecker.shouldAutoCheck(NOW + 365 * DAY, NOW))
    }

    /** 需求写死 7 天：改间隔必须是有意为之，不能被顺手改掉 */
    @Test
    fun 间隔为七天() {
        assertEquals(7L * 24 * 60 * 60 * 1000, UpdateChecker.AUTO_CHECK_INTERVAL_MS)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val NOW = 1_800_000_000_000L
    }
}
