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

    /**
     * 翻译的**常量端点**必须进禁明文名单（2026-09-30 第二轮审查补）。
     *
     * 代码侧有 `TranslationClient` 的 `isHttps` 判据，但平台层此前对这几条链路是**放行明文**的
     * —— 判据一旦被绕开或误删，网络层不会兜底。这里从各 Translator 源码**提取** ENDPOINT 的 host
     * 再对照名单：新增一家 Provider 而忘了登记时直接变红。
     *
     * （用户自填的 OpenAI 兼容域名无法静态枚举，仍靠代码判据 —— 见配置文件的注释。）
     */
    @Test
    fun 翻译常量端点必须进禁明文名单() {
        // ① 端点 host 清单（与各 Translator 的 ENDPOINT 常量一一对应；新增一家 Provider 时同步这里与 XML）
        val hosts = listOf(
            "mt.cn-hangzhou.aliyuncs.com",           // AliyunTranslator.ENDPOINT
            "api.cognitive.microsofttranslator.com", // AzureTranslator.ENDPOINT
            "fanyi-api.baidu.com",                   // BaiduTranslator / BaiduLlmTranslator.ENDPOINT
            "api.deepl.com",                         // DeepLTranslator.PRO_ENDPOINT
            "api-free.deepl.com",                    // DeepLTranslator.FREE_ENDPOINT
            "api.deepseek.com",                       // OpenAiTranslator.DEFAULT_BASE_URL（2026-10-03 核对：
            //                                            默认端点已是 deepseek；`api.openai.com` 仍作为
            //                                            「用户可填的示例」出现在设置页提示与 KDoc 里，
            //                                            而 KDoc 不在端点常量的扫描范围内）
        )
        // ② 反向对拍：每个 host 必须真的写在某个 Translator 源码里（清单过时/写错 → 变红提示同步，
        //    不依赖正则匹配 URL 的引号形态，比"提取"更稳）
        val sources = listOf(
            "AliyunTranslator.kt", "AzureTranslator.kt", "BaiduTranslator.kt",
            "BaiduLlmTranslator.kt", "DeepLTranslator.kt", "OpenAiTranslator.kt",
        ).map { name ->
            TestSources.codeOf(
                sourceOf(
                    "src/main/java/com/jinn/inputmethod/$name",
                    "app/src/main/java/com/jinn/inputmethod/$name",
                ),
            )
        }
        for (host in hosts) {
            assertTrue(
                "$host 应出现在某个 Translator 的端点常量里（本清单过时请同步）",
                sources.any { "https://$host" in it },
            )
        }
        // ③ 配置层：清单里每个 host 都必须在「显式禁明文」块内
        val xml = TestSources.codeOf(
            sourceOf(
                "src/main/res/xml/network_security_config.xml",
                "app/src/main/res/xml/network_security_config.xml",
            ),
        )
        val start = xml.indexOf("cleartextTrafficPermitted=\"false\"")
        assertTrue("配置里必须存在「显式禁明文」的 domain-config", start > 0)
        val denyBlock = xml.substring(start, xml.indexOf("</domain-config>", start))
        for (host in hosts) {
            assertTrue(
                "翻译端点 $host 必须显式禁明文（代码判据之外再加一层平台兜底）",
                Regex(">\\s*" + Regex.escape(host) + "\\s*<").containsMatchIn(denyBlock),
            )
        }
        // ④ 正向（2026-10-01 复审 L-231④）：上面 ①②③ 都是「清单 → 别处」的单向校验，XML 里
        //    新增一个域名不会有任何东西变红 —— api.openai.com 就是这样漏过一次。这条把方向补齐：
        //    禁明文块里的**每个**域名都得在已知清单里（翻译端点 ∪ 词库下载端点）。
        //    清单只增不减：域名该不该禁明文是安全决策，不能为了让断言变绿而从清单里删。
        val knownHosts = hosts + listOf(
            // 用户可填的示例端点：不是代码里的默认常量（默认是 deepseek），但它在设置页提示与
            // KDoc 里以明文出现、且用户确实会照抄 ⇒ 平台层同样要禁明文（L-228 的原意）
            "api.openai.com",
            "github.com", "objects.githubusercontent.com", "gitee.com",
        )
        val declared = Regex("<domain[^>]*>\\s*([A-Za-z0-9.\\-]+)\\s*</domain>")
            .findAll(denyBlock)
            .map { it.groupValues[1] }
            .toSet()
        for (host in declared) {
            assertTrue(
                "禁明文块里的域名 $host 没登记（新增域名请同步本测试的清单与注释）",
                host in knownHosts,
            )
        }
        assertTrue("禁明文块里应当解析出域名（解析式失效时本断言会先红）", declared.isNotEmpty())
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

    /**
     * 翻译客户端同款策略（BUG.md L-306 / L-313）。
     *
     * 翻译端点由**用户自填**（中转 / 聚合网关是常见用法）：`followRedirects` 一旦回到默认的 true，
     * 一个不太可信的网关回 307/308 就能把 POST 与**用户正文**原样重发到任意主机，响应还会被当成
     * 正常译文插进输入框。而这两行原先**删掉不会有任何测试变红** —— 上面那条守卫只覆盖词库下载
     * 客户端，翻译客户端的同类设置一直裸着。
     */
    @Test
    fun `翻译客户端必须只准TLS且不跟随任何重定向`() {
        val src = TestSources.codeOf(
            sourceOf(
                "src/main/java/com/jinn/inputmethod/TranslationClient.kt",
                "app/src/main/java/com/jinn/inputmethod/TranslationClient.kt",
            ),
        )
        val client = blockAfter(src, "private val http: OkHttpClient by lazy")
        assertTrue(
            "翻译客户端必须只准 TLS（connectionSpecs 去掉 CLEARTEXT）",
            "connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))" in client,
        )
        assertTrue(
            "必须显式 followRedirects(false)：默认值会让 307/308 把用户正文转投到任意主机（L-306）",
            "followRedirects(false)" in client,
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
