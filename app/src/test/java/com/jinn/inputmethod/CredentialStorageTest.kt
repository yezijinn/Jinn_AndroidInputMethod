package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据保护（Keystore 加密落盘 / 防截屏 / 剪贴板不留痕）的守卫 —— 2026-09-30 加固防泄露。
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
        assertFalse(CredentialCrypto.looksEncrypted("LTAI5tExampleAccessKeyId"))
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
        assertTrue("加密失败要记 W（而不是静默）", "凭据加密失败（Keystore 与软件密钥均不可用），未落盘" in src)
        // 两条加密支路都必须在：Keystore 优先（密钥不出安全硬件），失败再试软件密钥
        // （无锁屏设备上 Keystore 恒失败，没有这一支凭据就只能留在内存、重启即丢）
        assertTrue(
            "Keystore 失败必须再试软件密钥",
            "LocalCredentialCipher.encrypt(credentialKeyFile, cleaned)" in src,
        )
        // 兜底顺序是硬要求：`?:` 保证 Keystore 成功时**不会**再调软件密钥
        // （写成 `LocalCipher(...) ?: keystore` 或两次都写，会让每把凭据多存一份软件密钥副本，
        // 等于给所有设备都留一条低一档的旁路 —— 与「只在 Keystore 不可用时才降级」相反）
        assertTrue(
            "写侧必须是 `keystore ?: 软件密钥`（Keystore 成功就不得再走软件密钥）",
            "val encrypted = keystore ?: LocalCredentialCipher.encrypt(credentialKeyFile, cleaned)" in src,
        )
        assertTrue("读取要有进程内缓存（Keystore 解密本机实测约 1.1 ms/次）", "credentialCache" in src)
    }

    @Test
    fun `明文旧值自动迁移，解不开的密文按未配置处理`() {
        val src = prefsSource()
        assertTrue("要识别旧版明文并迁移", "凭据迁移" in src)
        assertTrue("迁移判据用 looksEncrypted", "CredentialCrypto.looksEncrypted(raw)" in src)
        // 关键：解不开的密文**不能当 Key 用**，否则用户会看到「已配置但认证失败」。
        // 走 decryptDetailed 而不是一个 String?：失败还要分「锁屏暂时」与「换机永久」，
        // 前者绝不能缓存成「未配置」（AI2 A-672 / L-1462）
        assertTrue("解不开的密文按未配置处理", "CredentialCrypto.decryptDetailed(raw)" in src)
        assertTrue("要有明确的 W 日志", "按未配置处理" in src)
    }

    /**
     * `v2:` 密文（软件密钥，见 [LocalCredentialCipher]）必须**先于**明文迁移分支处理。
     *
     * 这条顺序是硬不变量、且失败是静默的：`looksEncrypted("v2:…")` 是 false（它只认 `v1:` 与无前缀形态），
     * 一旦落到迁移分支，密文会被当成用户手填的凭据**再加密一次** —— 解开后是个 `v2:…` 字符串，
     * 拿它去请求服务端必然鉴权失败，而磁盘上的原始密文也被覆盖（L-1069 同型的永久损坏）。
     * 原先没有任何用例钉住这个顺序：把 v2 分支挪到 `if (!looksEncrypted(raw))` 之后，全套测试照绿。
     */
    @Test
    fun `v2 密文必须先于明文迁移分支处理`() {
        val src = prefsSource()
        // 窗口限定在 readCredential 内部：别的函数里也有 looksEncrypted 调用（写侧拒收密文）
        val read = TestSources.window(src, "private fun readCredential", "private fun writeCredential")
        val v2 = read.indexOf("LocalCredentialCipher.isLocal(raw)")
        val migrate = read.indexOf("if (!CredentialCrypto.looksEncrypted(raw))")
        assertTrue("readCredential 里找不到 v2 分流点", v2 >= 0)
        assertTrue("readCredential 里找不到明文迁移分支", migrate >= 0)
        assertTrue(
            "v2 分流必须排在明文迁移之前（否则密文会被再加密一层，永久解不开）",
            v2 < migrate,
        )
        // 位置对还不够：条件被短路（`false && isLocal(raw)`）等于悄悄关掉这条路，行为与挪走它完全相同
        assertTrue(
            "v2 分流点必须是真正的分支条件",
            "if (LocalCredentialCipher.isLocal(raw)) {" in read,
        )
        // 解不开的 v2 密文必须直接按「未配置」返回，不许落到迁移分支或把原文当凭据
        val v2Block = read.substring(v2, migrate)
        assertTrue("v2 解密失败必须按未配置缓存", "credentialCache[key] = \"\"" in v2Block)
        assertTrue("v2 解密失败必须返回空串（不能把密文原文当凭据）", "return \"\"" in v2Block)
        // 明文迁移分支的加密也必须带软件密钥兜底（无锁屏设备上 Keystore 恒失败）
        assertTrue(
            "迁移分支没走软件密钥兜底：无锁屏设备上明文永远迁不成密文",
            "LocalCredentialCipher.encrypt(credentialKeyFile, raw)" in read,
        )
    }

    /**
     * `v2:` 读失败必须先分「暂时」与「永久」（Stage 3 复核补）。
     *
     * 软件密钥件「在、这次读不出」（IO 抖动）与「缺失 / 损坏」在 `decrypt` 眼里一样是 null，
     * 但处置相反：前者**不许缓存**（缓存 = 本进程内凭据全消失，用户重开页面看到整页空框），
     * 后者才缓存「未配置」。这与 Keystore 的 `Transient` / `Permanent` 是同一条口径，
     * 原先 v2 分支只有「一律 Permanent」一种 —— 没有用例钉住这个不对称。
     */
    @Test
    fun `v2 读失败要先分暂时与永久再决定缓存`() {
        val src = prefsSource()
        val read = TestSources.window(src, "private fun readCredential", "private fun writeCredential")
        val v2 = read.indexOf("LocalCredentialCipher.isLocal(raw)")
        val migrate = read.indexOf("if (!CredentialCrypto.looksEncrypted(raw))")
        val block = read.substring(v2, migrate)

        val transient = block.indexOf("KeyState.UNREADABLE")
        val retry = block.indexOf("credentialRetryAt")
        val cacheEmpty = block.indexOf("credentialCache[key] = \"\"")
        assertTrue("v2 分支没判 KeyState.UNREADABLE（一次 IO 抖动 = 凭据全消失）", transient >= 0)
        assertTrue("暂时分支必须排在「缓存空串」之前", 0 <= transient && transient < cacheEmpty)
        assertTrue("暂时分支必须推后重试而不是缓存", 0 <= retry && retry < cacheEmpty)
        assertTrue("暂时分支必须原样返回空串（不许把密文当凭据）", "return \"\"" in block)
        // 永久分支的缓存必须还在（解不开就别每读一次跑一遍解密并刷一条 W）
        assertTrue("永久解不开仍要缓存空串", cacheEmpty >= 0)
        // 失败分支也要回查「解密期间被改写」（A-730）：否则用户在另一线程刚保存的新值
        // 会被下面那行 credentialCache[key] = "" 覆写成空串（v1 路径早就有这道闸）。
        // 数**两处**（成功 + 失败）：只查存在性的话，成功路径那一处会让守卫永远绿。
        val rechecks = Regex("""!=\s*raw""").findAll(block).count()
        assertTrue("v2 分支要两处回查（成功 + 失败），实际 $rechecks 处", rechecks >= 2)
    }

    // ── 防截屏 / 剪贴板不留痕（源码对拍）──────────────────────
    @Test
    fun `两个凭据页必须设置 FLAG_SECURE 防截屏与最近任务缩略图`() {
        for (f in listOf("TranslationSettingsActivity.kt", "OpenAiSettingsActivity.kt")) {
            val src = TestSources.codeSource(f)
            assertTrue("$f 必须设置 FLAG_SECURE", "FLAG_SECURE" in src)
            // 作用域钉（BUG-15）：原先只查整份源码里出现过该符号 —— 写进注释、或在别的页面/别的时机
            // 提一句都算过。真正的契约是「在 onCreate 里加到窗口」：晚了会有一帧明文进入最近任务缩略图，
            // 而凭据页的 Key 就在输入框里。
            val onCreate = TestSources.blockAfter(src, "override fun onCreate")
            assertTrue("$f：FLAG_SECURE 必须在 onCreate 内", "FLAG_SECURE" in onCreate)
            assertTrue(
                "$f：必须真的把它设到窗口上（window.addFlags / setFlags 都算）",
                "addFlags" in onCreate || "setFlags" in onCreate,
            )
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
