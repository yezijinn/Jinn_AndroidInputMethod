package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板建钥策略守卫。
 *
 * 背景（真机事故）：Keystore 别名被覆盖式重建后，库里全部旧密文永久 AEADBadTag、而之后的新写
 * 全部正常 —— 用户看到的是「历史全没了、新复制却正常」，极难与「密文损坏」区分。此前的实现
 * 只要 `getKey` 返回 null 就无条件 `generateKey()`（同名 = 先删后建），读路径也能走到那里。
 *
 * 本测试钉三层：
 *  1. [keyDecision] 的纯判据真值表（JVM 直跑，本仓无 Robolectric）；
 *  2. 探测必须在 `ClipboardCrypto` 监视器**之外**（写路径锁序 DB → Crypto，反序死锁）；
 *  3. 探针装在 `ClipboardDb.get()`（唯一构造点）与重算失败计数必须上报。
 */
class ClipboardKeyPolicyTest {

    // ── 纯判据 ──────────────────────────────────────────

    @Test
    fun `已有密钥一律复用`() {
        assertEquals(
            KeyDecision.UseExisting,
            keyDecision(keyFound = true, keyReadFailed = false, allowCreate = true, presenceKnown = true, hasCiphertextRows = true),
        )
        // 句柄能拿到时不必关心其余参数（现实中 keyFound 与 readFailed 互斥，这里只钉优先级）
        assertEquals(
            KeyDecision.UseExisting,
            keyDecision(keyFound = true, keyReadFailed = true, allowCreate = false, presenceKnown = false, hasCiphertextRows = true),
        )
    }

    @Test
    fun `取键抛异常不等于别名不存在`() {
        assertEquals(
            KeyDecision.Refuse,
            keyDecision(keyFound = false, keyReadFailed = true, allowCreate = true, presenceKnown = false, hasCiphertextRows = false),
        )
    }

    @Test
    fun `解密路径永不建钥`() {
        assertEquals(
            KeyDecision.Refuse,
            keyDecision(keyFound = false, keyReadFailed = false, allowCreate = false, presenceKnown = true, hasCiphertextRows = false),
        )
    }

    @Test
    fun `探针缺失按有密文处理`() {
        assertEquals(
            KeyDecision.Refuse,
            keyDecision(keyFound = false, keyReadFailed = false, allowCreate = true, presenceKnown = false, hasCiphertextRows = false),
        )
    }

    @Test
    fun `库内仍有密文时拒绝建钥`() {
        assertEquals(
            KeyDecision.Refuse,
            keyDecision(keyFound = false, keyReadFailed = false, allowCreate = true, presenceKnown = true, hasCiphertextRows = true),
        )
    }

    @Test
    fun `只有全新空库才允许建钥`() {
        assertEquals(
            KeyDecision.Create,
            keyDecision(keyFound = false, keyReadFailed = false, allowCreate = true, presenceKnown = true, hasCiphertextRows = false),
        )
    }

    // ── 源码守卫 ────────────────────────────────────────

    private val cryptoCode: String by lazy {
        TestSources.codeOf(TestSources.rawSourceOfShortName("ClipboardCrypto.kt"))
    }

    private val dbCode: String by lazy {
        TestSources.codeOf(TestSources.rawSourceOfShortName("ClipboardDb.kt"))
    }

    private val controllerCode: String by lazy {
        TestSources.codeOf(TestSources.rawSourceOfShortName("ClipboardController.kt"))
    }

    @Test
    fun `建钥前必须探测库内密文`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun keyOrNull(")
        val probe = body.indexOf("ClipboardKeyPresence.hasCiphertextRows")
        val create = body.indexOf("createKey()")
        assertTrue("keyOrNull 必须探测库内是否还有密文", probe >= 0)
        assertTrue("探测必须在建钥之前", probe < create)
    }

    @Test
    fun `探测不得在建钥的同步块内`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun keyOrNull(")
        val probe = body.indexOf("ClipboardKeyPresence.hasCiphertextRows")
        val create = body.indexOf("createKey()")
        val between = body.substring(probe, create)
        // 探测与建钥之间恰好一个同步块（建钥用），探测本身必须在它之前 ——
        // 写路径是「DB 监视器 → Crypto 监视器」，锁内查库就是反向边（非 WAL 单连接 ⇒ 死锁）
        assertTrue(
            "探测必须在建钥的 synchronized 之外",
            between.indexOf("synchronized(this)") > 0,
        )
    }

    @Test
    fun `生成密钥的函数不得回查库`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun createKey(")
        assertTrue("createKey 内不得探测库（会与 DB 监视器形成反向锁序）", "ClipboardKeyPresence" !in body)
        assertTrue("createKey 内不得再走判据", "keyDecision" !in body)
    }

    @Test
    fun `解密入口仍然禁止建钥`() {
        for (anchor in listOf("fun decrypt(", "fun decryptBytes(")) {
            val body = TestSources.blockAfter(cryptoCode, anchor)
            assertTrue("$anchor 必须传 allowCreate = false", "allowCreate = false" in body)
        }
    }

    @Test
    fun `密钥探针必须装在库的唯一构造点`() {
        val body = TestSources.blockAfter(dbCode, "fun get(context")
        assertTrue(
            "ClipboardKeyPresence.attach 必须挂在 ClipboardDb.get（备份导入/管理页都不经 ClipboardController）",
            "ClipboardKeyPresence.attach" in body,
        )
    }

    @Test
    fun `分组重算必须上报解不开的行数`() {
        val body = TestSources.blockAfter(dbCode, "fun reclassifyAll(")
        assertTrue("返回值必须带 failed", "ReclassResult(changed, failed)" in body)
        assertTrue("解不开的行必须累加 failed", "failed++" in body)
    }

    @Test
    fun `重算解不开的行必须有日志出口`() {
        val body = TestSources.blockAfter(controllerCode, "private fun reclassifyIfNeeded(")
        assertTrue("解不开的行必须在诊断日志里可见", "result.failed" in body)
        assertTrue("迁移标记仍要置位（语义是跑完了，不是库健康）", "prefs.reclassified = true" in body)
    }
}
