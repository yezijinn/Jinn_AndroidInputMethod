package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 建连日志脱敏（[loggableUrl]）：只留 scheme + host + path。
 *
 * 这条路径守的是 logcat —— 落盘侧有 `Diagnostics.redactSensitive`，logcat 没有。
 */
class AsrClientLogTest {

    @Test
    fun 查询串整体截掉() {
        assertEquals(
            "ws://nas.local:6016/asr",
            loggableUrl("ws://nas.local:6016/asr?token=secret&x=1"),
        )
    }

    @Test
    fun 去掉地址里的用户名密码() {
        assertEquals(
            "ws://10.0.0.2:6016/asr",
            loggableUrl("ws://user:pw@10.0.0.2:6016/asr"),
        )
        // 路径里的 '@' 不是凭据，不能被一起吞掉
        assertEquals(
            "ws://10.0.0.2:6016/a@b",
            loggableUrl("ws://10.0.0.2:6016/a@b"),
        )
    }

    @Test
    fun 普通地址原样保留() {
        assertEquals("ws://127.0.0.1:6016", loggableUrl("ws://127.0.0.1:6016"))
    }

    @Test
    fun 没有协议头时只截查询串() {
        assertEquals("nas.local:6016", loggableUrl("nas.local:6016?token=x"))
    }
}
