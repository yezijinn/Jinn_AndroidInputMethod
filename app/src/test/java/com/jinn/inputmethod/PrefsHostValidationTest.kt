package com.jinn.inputmethod

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * host 合法性校验单测（纯函数，不依赖 Android 运行时）。
 *
 * 这条判据是**崩不崩的边界**：`Prefs.wsUrl` 会被直接送进 OkHttp，而 `HttpUrl` 对含空白、
 * 重复端口、`..` 之类的取值会抛 `IllegalArgumentException`，调用点又都在主线程 —— 一旦命中
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
    fun IPv6字面量按URL规则放行() {
        assertTrue(Prefs.isValidHost("[::1]"))
        assertTrue(Prefs.isValidHost("[2408:8207:0:1::5]"))
        assertFalse("裸 IPv6 会拼坏 URL，必须拒绝", Prefs.isValidHost("2408:8207::1"))
        assertFalse("方括号后不能再跟端口", Prefs.isValidHost("[::1]:6016"))
        assertFalse(Prefs.isValidHost("[not ip]"))
    }

    @Test
    fun 空与纯空白拒绝() {
        assertFalse(Prefs.isValidHost(""))
        assertFalse(Prefs.isValidHost("   "))
    }

    @Test
    fun 纯标点不是主机() {
        assertFalse(Prefs.isValidHost("."))
        assertFalse(Prefs.isValidHost(".."))
        assertFalse(Prefs.isValidHost("-"))
        assertFalse(Prefs.isValidHost("_"))
    }

    @Test
    fun 带冒号的写法拒绝() {
        // 把 host 填成 host:port 是常见误操作：拼出来是 ws://192.168.1.3:6016:6016，
        // HttpUrl 直接抛异常（实测）
        assertFalse(Prefs.isValidHost("192.168.1.3:6016"))
        assertFalse(Prefs.isValidHost("http://192.168.1.3"))
        assertFalse(Prefs.isValidHost("192.168.1.3/path"))
        assertFalse(Prefs.isValidHost("user@host"))
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

    // ── 不变式护栏 ─────────────────────────────────────────────────────────

    @Test
    fun 规范化会去掉首尾空白() {
        // 取值方拼 URL 必须用 normalizeHost 的返回值：校验内部 trim，但原串带空白时
        // `ws:// 192.168.1.3 :6016` 一样会被 OkHttp 拒绝（实测）—— 曾因此留下崩溃点。
        assertEquals("192.168.1.3", Prefs.normalizeHost("  192.168.1.3  "))
        assertEquals("192.168.1.3", Prefs.normalizeHost("192.168.1.3\n"))
        assertEquals("nas.local", Prefs.normalizeHost(" nas.local\t"))
    }

    @Test
    fun 规范化对非法取值返回null() {
        assertEquals(null, Prefs.normalizeHost(""))
        assertEquals(null, Prefs.normalizeHost("   "))
        assertEquals(null, Prefs.normalizeHost(".."))
        assertEquals(null, Prefs.normalizeHost("192.168.1.3:6016"))
    }

    @Test
    fun 规范化保留合法取值原样() {
        assertEquals("192.168.1.3", Prefs.normalizeHost("192.168.1.3"))
        assertEquals("[::1]", Prefs.normalizeHost("[::1]"))
        assertEquals("我的主机", Prefs.normalizeHost("我的主机"))
    }

    private fun okhttpAccepts(host: String): Boolean =
        runCatching { Request.Builder().url("ws://$host:6016").build() }.isSuccess

    /**
     * **校验/规范化与真实消费者的一致性**：凡是被规范化接受的取值，拼进 URL 后必须能被 OkHttp 解析。
     *
     * 这条不变式至今抓到过三类实现缺陷：
     *  1. 只做字符黑名单时 `..` 漏过，却让 `HttpUrl` 抛异常（崩溃路径没堵死）；
     *  2. 终判错用 `ws://` 解析 —— `toHttpUrlOrNull()` 不接受 `ws:` 方案，
     *     会把**所有**合法 host 判成非法（用户配置被静默回落成默认值）；
     *  3. 校验内部 trim 但取值方拿**原串**去拼 URL —— 存了带空白的值就绕过校验，
     *     `ws:// 192.168.1.3 :6016` 仍然抛异常。
     */
    @Test
    fun 被规范化的取值必须都能被OkHttp接受() {
        val candidates = listOf(
            "192.168.1.3", "nas.local", "my-nas_1", "localhost", "1", "0.0.0.0",
            "999.999.999.999", "192.168.1.3.", "a..b", ".a", "a.", "-a-", "_.", "a-.b",
            "[::1]", "[2408:8207:0:1::5]", "[fe80::1%eth0]", "[]", "[not ip]", "[::1]:6016",
            "2408:8207::1", "::1", ".", "..", "-", "_", "---",
            "我的主机", "名前.local",
            "192.168.1.3:6016", "http://h", "h/path", "user@h", "h?x=1", "h#f",
            "", "   ", "h h", "192.168.1.3\u0007",
            // 带首尾空白：规范化的核心场景
            "  192.168.1.3  ", "192.168.1.3\n", "\t nas.local ", " 我的主机 ",
        )
        val bad = candidates.mapNotNull { c -> Prefs.normalizeHost(c)?.takeIf { !okhttpAccepts(it) } }
        assertEquals("规范化放行但 OkHttp 会抛异常的取值（会在主线程崩掉 IME）: $bad", emptyList<String>(), bad)
    }

    @Test
    fun 校验放行的取值必须都能被OkHttp接受() {
        val candidates = listOf("192.168.1.3", "[::1]", "我的主机", "..", ".", "", "192.168.1.3:6016")
        val gaps = candidates.filter { Prefs.isValidHost(it) && !okhttpAccepts(it.trim()) }
        assertEquals("校验放行但 OkHttp 会抛异常的取值: $gaps", emptyList<String>(), gaps)
    }
}
