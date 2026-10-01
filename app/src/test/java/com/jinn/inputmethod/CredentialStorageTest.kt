package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据保护（Keystore 加密落盘 / 防截屏 / 剪贴板不留痕）的守卫 —— 用户 2026-09-30 要求增强防泄露。
 *
 * 为什么用「纯函数 + 源码对拍」而不是行为测试：Keystore 与 `SharedPreferences` 在 JVM 单测里都不存在，
 * 而这几条防线恰恰是「有人顺手改回明文、删掉一行 FLAG_SECURE」的高危位置 —— 机械对拍正对这种回退。
 */
class CredentialStorageTest {

    private fun prefsSource(): String = TestSources.codeSource("Prefs.kt")

    // ── 密文识别（纯函数）────────────────────────────────────

    @Test
    fun `looksEncrypted 只认本模块的密文形态`() {
        // 真实密文形态：16 个 base64 字符（IV 12 字节）+ ':' + base64 密文（GCM tag 16 字节 ⇒ ≥24 字符）
        assertTrue(CredentialCrypto.looksEncrypted("AAAAAAAAAAAAAAAA:" + "B".repeat(44)))
        // 真实凭据里唯一带冒号的形态：DeepL 的 `uuid:fx`（首段 36 字符）—— 绝不误判成密文
        assertFalse(CredentialCrypto.looksEncrypted("01234567-89ab-cdef-0123-456789abcdef:fx"))
        assertFalse(CredentialCrypto.looksEncrypted("sk-proj-Ab12Cd34Ef56"))
        assertFalse(CredentialCrypto.looksEncrypted("LTAI5t8EbSZh2Jqx1ucnyda2"))
        assertFalse(CredentialCrypto.looksEncrypted(""))
        assertFalse(CredentialCrypto.looksEncrypted("short:abc"))
        // 首段 16 字符合法 base64、但密文段过短 ⇒ 不是本模块的密文
        assertFalse(CredentialCrypto.looksEncrypted("AAAAAAAAAAAAAAAA:short"))
    }

    /**
     * L-219 回归守卫（两个方向都要 fail-closed）。
     *
     * 反方向（本测试）：判成密文最多让用户重填一次；判成明文则会把密文当 Key 发出去、
     * 并把它当明文**覆盖写回**（原始密文永久丢失）。
     */
    @Test
    fun `带版本前缀的一律算密文`() {
        assertTrue(CredentialCrypto.looksEncrypted("v1:AAAAAAAAAAAAAAAA:" + "B".repeat(44)))
        // 前缀在身时即使后半段形态不规范也按密文对待
        assertTrue(CredentialCrypto.looksEncrypted("v1:whatever"))
        // 真凭据不会以 v1: 开头
        assertFalse(CredentialCrypto.looksEncrypted("sk-proj-Ab12Cd34Ef56"))
    }

    @Test
    fun `首段 24 字符的 base64 形态也算密文（换 IV 长度不 fail-open）`() {
        assertTrue(CredentialCrypto.looksEncrypted("A".repeat(24) + ":" + "B".repeat(44)))
    }

    // ── 加密落盘（源码对拍）─────────────────────────────────

    @Test
    fun `九类凭据键必须走加密读写，不得回退成 sp 明文读写`() {
        val src = prefsSource()
        val keys = listOf(
            "KEY_AZURE_API_KEY", "KEY_BAIDU_APP_ID", "KEY_BAIDU_SECRET_KEY",
            "KEY_ALIYUN_ACCESS_KEY_ID", "KEY_ALIYUN_ACCESS_KEY_SECRET",
            "KEY_DEEPL_API_KEY", "KEY_BAIDU_LLM_APP_ID", "KEY_BAIDU_LLM_API_KEY",
            "KEY_OPENAI_API_KEY",
        )
        for (k in keys) {
            assertTrue("$k 的 getter 必须走 readCredential", "get() = readCredential($k)" in src)
            assertTrue("$k 的 setter 必须走 writeCredential", "set(value) = writeCredential($k, value)" in src)
            // 反向：这几个键不许再出现直接的 sp 明文写（writeCredential 里落的是变量 key + 密文）
            assertFalse("$k 不得再直接 putString 明文", "putString($k," in src)
        }
    }

    @Test
    fun `凭据落盘必须是密文，加密失败时不落盘`() {
        val src = prefsSource()
        assertTrue("落盘的应是加密结果", "putString(key, encrypted)" in src)
        assertFalse("不得把清洗后的明文直接落盘", "putString(key, cleaned)" in src)
        assertTrue("加密失败要记 W（而不是静默）", "凭据加密失败，未落盘" in src)
        assertTrue("读取要有进程内缓存（Keystore 解密 5~20ms/次）", "credentialCache" in src)
    }

    @Test
    fun `明文旧值自动迁移，解不开的密文按未配置处理`() {
        val src = prefsSource()
        assertTrue("要识别旧版明文并迁移", "凭据迁移" in src)
        assertTrue("迁移判据用 looksEncrypted", "CredentialCrypto.looksEncrypted(raw)" in src)
        // 关键：解不开的密文**不能当 Key 用**，否则用户会看到「已配置但认证失败」
        assertTrue("解不开的密文按未配置处理", "CredentialCrypto.decrypt(raw)" in src)
        assertTrue("要有明确的 W 日志", "按未配置处理" in src)
    }

    // ── 防截屏 / 剪贴板不留痕（源码对拍）──────────────────────

    @Test
    fun `两个凭据页必须设置 FLAG_SECURE 防截屏与最近任务缩略图`() {
        for (f in listOf("TranslationSettingsActivity.kt", "OpenAiSettingsActivity.kt")) {
            val src = TestSources.codeSource(f)
            assertTrue("$f 必须设置 FLAG_SECURE", "FLAG_SECURE" in src)
        }
    }

    @Test
    fun `凭据不留痕：删除接口存在、排除收藏，且两个设置页都调用`() {
        val db = TestSources.codeSource("ClipboardDb.kt")
        assertTrue("ClipboardDb 需提供按明文删除", "fun deleteByPlaintext(" in db)
        assertTrue("删除必须排除收藏条目", "content_hash = ? AND is_favorite = 0" in db)
        assertTrue(
            "翻译设置页要在打开与保存时清理",
            "purgeClipboardHistory" in TestSources.codeSource("TranslationSettingsActivity.kt"),
        )
        assertTrue(
            "OpenAI 配置页要在打开与保存时清理",
            "purgeApiKeyFromClipboardHistory" in TestSources.codeSource("OpenAiSettingsActivity.kt"),
        )
    }

    @Test
    fun `凭据加密走独立密钥别名（与剪贴板互不牵连）`() {
        val src = TestSources.codeSource("CredentialCrypto.kt")
        assertTrue("必须有独立别名", "jinn_credentials_key" in src)
        assertFalse("不得复用剪贴板的密钥别名", "jinn_clipboard_key" in src)
        // 锁屏不可解密（API 28+）：防锁屏状态下的取证读取
        assertTrue("要设置 setUnlockedDeviceRequired", "setUnlockedDeviceRequired(true)" in src)
    }
}
