package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * host 合法性校验单测（纯函数，不依赖 Android）。
 *
 * 这条判据是**崩不崩的边界**：`Prefs.wsUrl` 会被直接送进 OkHttp，而 `HttpUrl` 对含空白、
 * 重复端口的 host 会抛 `IllegalArgumentException`（实测），调用点又都在主线程 —— 一旦命中
 * 就是整个 IME 崩掉。语音链路属禁改区，所以校验只能放在配置层，这里锁住它的边界。
 */
class PrefsHostValidationTest {

    @Test
    fun 正常地址放行() {
        assertTrue(Prefs.isValidHost("192.168.1.3"))
        assertTrue(Prefs.isValidHost("nas.local"))
        assertTrue(Prefs.isValidHost("my-nas_1"))
        assertTrue("首尾空白会被 trim 后放行", Prefs.isValidHost("  192.168.1.3  "))
    }

    @Test
    fun 非ASCII主机名放行() {
        // 实测 OkHttp 的 HttpUrl 接受中文主机（内部做 IDN 转换），拒绝反而误伤用户
        assertTrue(Prefs.isValidHost("我的主机"))
    }

    @Test
    fun 空与纯空白拒绝() {
        assertFalse(Prefs.isValidHost(""))
        assertFalse(Prefs.isValidHost("   "))
    }

    @Test
    fun 带冒号的写法拒绝() {
        // 把 host 填成 host:port 是常见误操作：拼出来是 ws://192.168.1.3:6016:6016，
        // HttpUrl 直接抛异常（实测）
        assertFalse(Prefs.isValidHost("192.168.1.3:6016"))
        assertFalse(Prefs.isValidHost("http://192.168.1.3"))
        assertFalse(Prefs.isValidHost("192.168.1.3/path"))
        assertFalse(Prefs.isValidHost("user@host"))
        assertFalse(Prefs.isValidHost("[::1]"))
    }

    @Test
    fun 含空白与控制字符拒绝() {
        assertFalse("host 内含空格同样会让 HttpUrl 抛异常（实测）", Prefs.isValidHost("192.168.1.3 foo"))
        assertFalse(Prefs.isValidHost("192.168.1.3\tfoo"))
        assertFalse(Prefs.isValidHost("192.168.1.3\u0007"))
    }

    @Test
    fun 首尾空白会被trim后放行() {
        // setter 与校验都先 trim，故尾随换行/空格不会构成非法值（不是漏洞）
        assertTrue(Prefs.isValidHost("192.168.1.3\n"))
        assertTrue(Prefs.isValidHost("\t192.168.1.3 "))
    }
}
