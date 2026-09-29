package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网络安全策略守卫（`BUG.md` L-100）：明文只准留给用户自填的局域网语音，其余链路只准 TLS。
 *
 * 背景：`ws://` 的 host 由用户自填（任意 IP / 域名 / 端口），`domain-config` 又只认具体域名、
 * 不支持网段 ⇒ 静态白名单覆盖不了语音链路，`base-config` 只能放行明文。于是「闸门开得比需要的
 * 大」这件事**收窄不了根因，只能收窄影响面**：词库下载与更新检查必须在配置层与代码层双重只准 TLS。
 *
 * 门都是「删掉一条配置/一行参数就变红」的形状（反向验证见更新日志的修复记录）：
 *  - manifest 把 `usesCleartextTraffic` 换成了 `networkSecurityConfig`；
 *  - 配置 XML：base 放行（语音），github / gitee 等公网端点**显式**禁明文；
 *  - `DictManagerActivity` 的下载客户端去掉 CLEARTEXT 且不跟随 SSL 重定向；
 *  - `UpdateChecker.httpGet` 只接受 `https://`；
 *  - 交叉校验：`OptionalDicts` 的下载 URL 全是 https（否则上面那条 TLS-only 会把下载打死）。
 */
class NetworkPolicyTest {

    private fun sourceOf(vararg candidates: String) = TestSources.rawSource(*candidates)

    /** `marker` 之后那个花括号块（与 RecentFixesRegressionTest.blockAfter 同款：配对而非取窗口） */
    private fun blockAfter(text: String, marker: String): String {
        val i = text.indexOf(marker)
        assertTrue("源码里找不到锚点「$marker」—— 改名/重构后请同步本用例", i >= 0)
        val open = text.indexOf('{', i)
        assertTrue("锚点「$marker」之后没有花括号块", open > i)
        var depth = 0
        for (j in open until text.length) {
            when (text[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, j + 1)
                }
            }
        }
        error("锚点「$marker」的花括号不配对")
    }

    @Test
    fun 明文闸门必须由网络安全配置收窄而不是全局属性() {
        val manifest = TestSources.codeOf(
            sourceOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"),
        )
        assertTrue(
            "manifest 必须挂上 network_security_config（否则平台默认禁明文的保护被全局属性顶掉）",
            "android:networkSecurityConfig=\"@xml/network_security_config\"" in manifest,
        )
        assertFalse(
            "不得再用全局 android:usesCleartextTraffic=\"true\"（它一开，全应用任何 http:// 都被放行）",
            "usesCleartextTraffic" in manifest,
        )
    }

    @Test
    fun 公网端点必须显式禁明文而base只为局域网语音放行() {
        val xml = TestSources.codeOf(
            sourceOf(
                "src/main/res/xml/network_security_config.xml",
                "app/src/main/res/xml/network_security_config.xml",
            ),
        )
        // 空白不敏感：这条配置是给人读的，允许换行缩进调整（`<base-config` 与属性分会不止一行）
        assertTrue(
            "base-config 必须放行明文，否则用户自填地址的局域网语音 ws:// 直接连不上",
            Regex("<base-config\\s+cleartextTrafficPermitted=\"true\"").containsMatchIn(xml),
        )
        // 公网禁明文的名单：截取 domain-config(false) 块再核对成员
        val start = xml.indexOf("cleartextTrafficPermitted=\"false\"")
        assertTrue("配置里必须存在「显式禁明文」的 domain-config", start > 0)
        val end = xml.indexOf("</domain-config>", start)
        assertTrue("domain-config 未闭合", end > start)
        val denyBlock = xml.substring(start, end)
        for (host in listOf("github.com", "objects.githubusercontent.com", "gitee.com")) {
            assertTrue(
                "公网端点 $host 必须显式禁明文（少一个就等于它仍可被同网段明文 MITM）",
                Regex(">\\s*" + Regex.escape(host) + "\\s*<").containsMatchIn(denyBlock),
            )
        }
        // 反向口径：`cleartextTrafficPermitted="true"` 只准出现一次（base 那处）。第二个 `true`
        // 就意味着某个 domain-config 被写成放行 —— 白名单反了，且比不写更危险（看着像收窄了）。
        val allowCount = Regex("cleartextTrafficPermitted=\"true\"").findAll(xml).count()
        assertTrue("只准 base-config 一处放行明文，实测 $allowCount 处", allowCount == 1)
    }

    @Test
    fun 词库下载客户端必须只准TLS且不跟随SSL重定向() {
        val src = TestSources.codeOf(
            sourceOf(
                "src/main/java/com/jinn/inputmethod/DictManagerActivity.kt",
                "app/src/main/java/com/jinn/inputmethod/DictManagerActivity.kt",
            ),
        )
        val client = blockAfter(src, "private val httpClient: okhttp3.OkHttpClient by lazy")
        assertTrue(
            "下载客户端必须只准 TLS（connectionSpecs 去掉 CLEARTEXT），否则一条 http:// 源照样能下",
            "connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS))" in client,
        )
        assertFalse(
            "不得把 CLEARTEXT 放回 connectionSpecs（它会让明文闸门的收窄全部失效）",
            "CLEARTEXT" in client,
        )
        assertTrue(
            "必须保持 followSslRedirects(false)：默认值会跟随 https→http 降级跳转",
            "followSslRedirects(false)" in client,
        )
    }

    @Test
    fun 更新检查只接受https地址() {
        val src = TestSources.codeOf(
            sourceOf(
                "src/main/java/com/jinn/inputmethod/UpdateChecker.kt",
                "app/src/main/java/com/jinn/inputmethod/UpdateChecker.kt",
            ),
        )
        val body = blockAfter(src, "private fun httpGet(")
        val guard = body.indexOf("\"https://\"")
        val connect = body.indexOf("openConnection()")
        assertTrue("httpGet 里必须有 https 前缀判据（拒绝非 HTTPS 地址）", guard >= 0)
        assertTrue("https 判据必须在建立连接之前（放到后面等于先明文再检查）", connect < 0 || guard < connect)
    }

    @Test
    fun 词库下载URL必须全是https() {
        // 剥注释后判（共用 TestSources.codeOf）：KDoc 里可能出现 http:// 的举例（BUG.md L-116）
        val code = TestSources.codeOf(
            sourceOf(
                "src/main/java/com/jinn/inputmethod/OptionalDicts.kt",
                "app/src/main/java/com/jinn/inputmethod/OptionalDicts.kt",
            ),
        )
        assertFalse(
            "词库下载 URL 不得出现 http://（TLS-only 客户端会把它直接打死，等于下载功能失效）",
            Regex("\"http://").containsMatchIn(code),
        )
        assertTrue("至少要有一条 https 下载源", code.contains("\"https://"))
    }
}
