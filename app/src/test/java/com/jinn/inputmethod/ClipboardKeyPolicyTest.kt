package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板建钥策略守卫（2026-11-14 复核后为「代际别名」版）。
 *
 * 真机事故：Keystore 别名被覆盖式重建后，库里全部旧密文永久 AEADBadTag、而之后的新写全部正常。
 * 根因是 `generateKey()` 对同名别名「先删后建」，而密钥不可导出 ⇒ 一次误判就毁掉全部旧数据。
 *
 * 现行模型：**永不覆盖已有别名**。库内仍有文本密文时，新建的密钥写到下一代别名
 * （[KeyDecision.CreateNewGeneration]），因此「取不到」即便是假象，旧别名与旧密文也还在。
 *
 * 本测试钉三层：
 *  1. [keyDecision] 纯判据真值表（JVM 直跑，本仓无 Robolectric）；
 *  2. 建钥别名必须是 `aliasFor(...)`、绝不能是历史别名常量（否则又会覆盖）；
 *  3. 解密只读不建钥，且按代际回退。
 */
class ClipboardKeyPolicyTest {

    // ── 纯判据 ──────────────────────────────────────────

    @Test
    fun `已有密钥一律复用`() {
        assertEquals(
            KeyDecision.UseExisting,
            keyDecision(keyFound = true, keyReadFailed = false, allowCreate = true, hasCiphertextRows = true),
        )
        assertEquals(
            KeyDecision.UseExisting,
            keyDecision(keyFound = true, keyReadFailed = true, allowCreate = false, hasCiphertextRows = true),
        )
    }

    @Test
    fun `取键抛异常不等于别名不存在`() {
        assertEquals(
            KeyDecision.Refuse,
            keyDecision(keyFound = false, keyReadFailed = true, allowCreate = true, hasCiphertextRows = false),
        )
    }

    @Test
    fun `解密路径永不建钥`() {
        assertEquals(
            KeyDecision.Refuse,
            keyDecision(keyFound = false, keyReadFailed = false, allowCreate = false, hasCiphertextRows = false),
        )
    }

    @Test
    fun `库内仍有密文时必须换下一代别名`() {
        // ← 该缺陷的单测形态：不是「拒绝写入」（那会形成永久死结），而是「换个别名建」
        assertEquals(
            KeyDecision.CreateNewGeneration,
            keyDecision(keyFound = false, keyReadFailed = false, allowCreate = true, hasCiphertextRows = true),
        )
    }

    @Test
    fun `只有全新空库才沿用历史别名`() {
        assertEquals(
            KeyDecision.Create,
            keyDecision(keyFound = false, keyReadFailed = false, allowCreate = true, hasCiphertextRows = false),
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
    fun `建钥必须用代际别名而不是历史别名常量`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun createKey(")
        assertTrue(
            "KeyGenParameterSpec.Builder 的实参必须是 aliasFor(gen)：直接写 KEY_ALIAS 会覆盖历史密钥",
            "KeyGenParameterSpec.Builder(\n                aliasFor(gen)" in body ||
                "KeyGenParameterSpec.Builder(aliasFor(gen)" in body,
        )
        assertFalse("建钥不得出现裸 KEY_ALIAS 作为 Builder 实参", "Builder(\n                KEY_ALIAS" in body)
    }

    @Test
    fun `库内仍有密文时不得复用第一代`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun pickGeneration(")
        assertTrue(
            "有密文时必须从第 2 代起找空闲别名（pickGeneration 的起点由 hasRows 决定）",
            "if (hasRows) 2 else 1" in body || "hasRows) 2 else 1" in body,
        )
        assertTrue("必须先问 containsAlias 再建，避免覆盖", "containsAlias" in body)
    }

    @Test
    fun `建钥前必须探测库内密文`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun keyOrNull(")
        val probe = body.indexOf("ClipboardKeyPresence.hasCiphertextRows")
        val create = body.indexOf("createKey(")
        assertTrue("keyOrNull 必须探测库内是否还有密文", probe >= 0)
        assertTrue("探测必须在建钥之前", probe < create)
    }

    @Test
    fun `生成密钥的函数不得回查库`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun createKey(")
        assertTrue("createKey 内不得探测库（会与 DB 监视器形成反向锁序）", "ClipboardKeyPresence" !in body)
        assertTrue("createKey 内不得再走判据", "keyDecision" !in body)
    }

    @Test
    fun `解密只读不建钥且按代际回退`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun decryptRaw(")
        assertFalse("解密路径不得建钥", "createKey(" in body)
        assertTrue("解密必须按代际回退（旧密文可能是上一代密钥写的）", "MAX_KEY_GEN downTo 1" in body)
        val bytes = TestSources.blockAfter(cryptoCode, "fun decryptBytes(")
        assertTrue("字节解密同样要按代际回退", "MAX_KEY_GEN downTo 1" in bytes)
        assertFalse("字节解密不得建钥", "createKey(" in bytes)
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

    // ── 缓存 / 读路径自愈 / 代际占用 ──

    @Test
    fun `代际句柄必须缓存且失效时一起清`() {
        assertTrue("必须有代际句柄缓存（否则每行重走 9 次 getKey）", "genKeys" in cryptoCode)
        val inv = TestSources.blockAfter(cryptoCode, "private fun invalidateKey()")
        assertTrue("失效必须清句柄缓存（否则重建后仍返回旧句柄）", "genKeys.clear()" in inv)
        assertTrue("失效必须清缺失缓存", "genMissingAt.clear()" in inv)
    }

    @Test
    fun `缺失缓存只能由正常返回 null 写入`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun storedKeyFor(")
        assertFalse(
            "取钥抛异常不得记成缺失：一次抖动会被固化成「整进程读不出」",
            "catch (e" in body,
        )
        assertTrue("只有明确不存在才写负缓存", "genMissingAt[gen] = System.currentTimeMillis()" in body)
        assertTrue("负缓存必须带有效期", "GEN_MISSING_TTL_MS" in cryptoCode)
    }

    @Test
    fun `读路径遇密钥失效要自愈一次`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun decryptRaw(")
        assertTrue("必须能触发重建（读路径原先只能靠写路径顺带修好）", "invalidateKey()" in body)
        assertTrue("必须有冷却，否则密钥真丢失时每行重建一次", "reserveReadRepair()" in body)
        assertTrue("重试必须只做一次", "allowRepair = false" in body)
        assertTrue("回退循环必须留在函数体内", "MAX_KEY_GEN downTo 1" in body)
        val bytes = TestSources.blockAfter(cryptoCode, "fun decryptBytes(")
        assertTrue("字节解密同样要自愈", "invalidateKey()" in bytes)
    }

    @Test
    fun `选代必须可空且不得覆盖已占用代际`() {
        assertTrue(
            "pickGeneration 必须可返回 null（覆盖同名 = 先删后建 = 旧密文永久失钥）",
            "private fun pickGeneration(hasRows: Boolean): Int?" in cryptoCode,
        )
        val body = TestSources.blockAfter(cryptoCode, "private fun pickGeneration(")
        assertFalse("不得用最高代兜底", "return MAX_KEY_GEN" in body)
        assertTrue("探测抖动与真耗尽必须分开归因", "Diagnostics.w" in body && "Diagnostics.e" in body)
    }

    @Test
    fun `KeyStore 实例只能有一个写入口`() {
        assertTrue("必须有唯一写入口", "private fun keyStoreInstance()" in cryptoCode)
        assertEquals(
            "裸写只允许出现在 keyStoreInstance 内（外层 + 双重检查内层）",
            2,
            Regex("""keyStore \?:""").findAll(cryptoCode).count(),
        )
        assertTrue(
            "该写入口必须在监视器内",
            "private fun keyStoreInstance(): KeyStore = keyStore ?: synchronized(this) {" in cryptoCode,
        )
    }

    @Test
    fun `选代与建钥必须在同一监视器内`() {
        val body = TestSources.blockAfter(cryptoCode, "private fun keyOrNull(")
        val first = body.indexOf("synchronized(this)")
        val second = body.indexOf("synchronized(this)", first + 1)
        assertTrue("keyOrNull 至少两个同步块", second > first)
        assertTrue(
            "选代必须在第二个同步块内（与建钥同一临界区，否则并发会覆盖刚建的同代密钥）",
            body.indexOf("pickGeneration(") > second,
        )
    }
}
