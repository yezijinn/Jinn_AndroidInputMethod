package com.jinn.inputmethod

/**
 * OpenAI 兼容后端的地址策略（纯函数，JVM 单测直接跑）。
 *
 * 规则只有一条主线：**默认只允许 `https://`**；`http://` 仅当主机是回环或私网
 * （`127.0.0.1` / `localhost` / `::1` / `10.*` / `192.168.*` / `172.16-31.*` / `*.local`）时放行。
 *
 * 理由与自建 CapsWriter 的 `ws://<局域网>` 同一条：明文协议只在本机回环或自家局域网里才不会被
 * 中间人读到，而这条链路上传的是**整段录音**和（HTTP 头里的）API Key。公网明文一律拒绝，
 * 并且**拒绝原因要能直接显示给用户**（落到 `edit_oai_base.error` 或状态条），
 * 让用户知道该改成 `https://`，而不是「保存不了」。
 */
internal object OpenAiEndpoint {

    /** 转写接口路径（拼在归一后的 base 之后） */
    const val PATH = "/audio/transcriptions"

    const val HTTPS_PREFIX = "https://"
    const val HTTP_PREFIX = "http://"

    /** 为空时的提示（给用户看，不含任何技术黑话） */
    const val TEXT_EMPTY = "地址为空"

    const val TEXT_NO_SCHEME = "地址要以 http:// 或 https:// 开头"

    const val TEXT_BLANK = "地址不能含空格"

    const val TEXT_NO_HOST = "地址里缺少主机名"

    /** 用户名密码 / 查询串 / 锚点：拼上固定路径后语义会变，且凭据会随地址进日志 */
    const val TEXT_BAD_CHARS = "地址里不要带 ? # @（凭据填下面那一栏）"

    /** 公网明文：这条要能直接显示给用户，说明为什么被拒、该怎么改 */
    const val TEXT_PUBLIC_HTTP = "公网地址必须用 https://（http 只允许本机与局域网）"

    /**
     * 归一 base：去首尾空白、scheme 转小写、去掉尾部 `/`，路径部分原样保留
     * （用户可能填 `https://api.groq.com/openai/v1`）。
     *
     * @return 归一后的 base（**不含** [PATH]）；失败时 `exceptionOrNull().message` 就是给用户看的那句话
     */
    fun normalize(base: String): Result<String> {
        val raw = base.trim()
        if (raw.isEmpty()) return failed(TEXT_EMPTY)
        if (raw.any { it.isWhitespace() }) return failed(TEXT_BLANK)
        val lower = raw.lowercase()
        val https = lower.startsWith(HTTPS_PREFIX)
        if (!https && !lower.startsWith(HTTP_PREFIX)) return failed(TEXT_NO_SCHEME)
        val rest = raw.substring(if (https) HTTPS_PREFIX.length else HTTP_PREFIX.length)
        // `?` / `#` 拼上固定路径后语义会变（`?x=1` + `/audio/transcriptions` 变成查询值的一部分），一律拒。
        if (rest.any { it == '?' || it == '#' }) return failed(TEXT_BAD_CHARS)
        // `@` 只拒 **authority 里的**那个：它是 `user:pass@host` 的分隔符，会随 [endpoint] 一起进日志
        // （凭据只许留在 Authorization 头里），而且各家解析器对 `evil.com\@127.0.0.1` 这种混杂写的
        // 取值不一致（地址策略会被绕开）。路径里的 `@` 不带凭据、也不参与 [hostOf] 取主机，
        // 而 Cloudflare / 部分网关的路径本身就含 `@cf/` —— 一并拒掉只会给出一句误导的提示
        // （让人「删掉 @」，而删掉它就再也不是那个地址了）。
        if ('@' in rest.substringBefore('/')) return failed(TEXT_BAD_CHARS)
        val host = hostOf(rest)
        if (host.isEmpty()) return failed(TEXT_NO_HOST)
        // 明文 http 只在本机 / 局域网内放行：那段录音与 API Key 都会裸奔在链路上
        if (!https && !isLocalHost(host)) return failed(TEXT_PUBLIC_HTTP)
        // scheme 统一小写：用户手写 `HTTPS://` 很常见，而下游 `Request.Builder().url()` 对大小写的
        // 容忍度不该成为隐蔽失败点（解析器一变，坏的是整条语音链路而不报错）
        return Result.success((if (https) HTTPS_PREFIX else HTTP_PREFIX) + rest.trimEnd('/'))
    }

    /** 归一后的 base + [PATH]；调用方应先用 [normalize] 校验过 */
    fun transcriptionsUrl(normalizedBase: String): String = normalizedBase + PATH

    /** 主机是否回环 / 私网 / mDNS（入参可带 `[]` 与端口，`[::1]:8080` 也算） */
    fun isLocalHost(host: String): Boolean {
        val h = hostOf(host).lowercase().trim('[', ']')
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 127 || a == 10 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31)
    }

    /**
     * 从「scheme 之后的部分」取主机：`user@host:port/path?q#f` ⇒ `host`。
     *
     * 手写而不走 `java.net.URI`：用户填的地址常常没转义（含中文 / 空格 / 缺 host），
     * `URI.create` 对这类输入直接抛 `URISyntaxException`，校验就变成了崩溃点。
     */
    private fun hostOf(rest: String): String {
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val afterUser = authority.substringAfterLast('@')
        if (afterUser.startsWith("[")) {
            val end = afterUser.indexOf(']')
            return if (end > 0) afterUser.substring(0, end + 1) else ""
        }
        return afterUser.substringBefore(':')
    }

    private fun failed(message: String): Result<String> =
        Result.failure(IllegalArgumentException(message))
}
