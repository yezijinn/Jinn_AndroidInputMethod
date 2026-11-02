package com.jinn.inputmethod

/**
 * 阿里云百炼实时语音识别的地址策略（纯函数，JVM 单测直接跑）。
 *
 * 规则与 [OpenAiEndpoint] 同一条主线：**默认只允许 `wss://`**；`ws://` 仅当主机是回环或私网
 * （`127.0.0.1` / `localhost` / `::1` / `10.*` / `192.168.*` / `172.16-31.*` / `*.local`）时放行。
 *
 * 为什么让用户填**完整 URL** 而不是 host + 端口：百炼有华北专属域名
 * （`wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference`），
 * 端点本身属账号配置，不是一个固定常量。
 *
 * 拒绝原因都要能直接显示给用户（落到 `edit_ali_ws_url.error` 或状态条），
 * 说明「哪里不对、该怎么改」，而不是一句「保存失败」。
 */
internal object AliEndpoint {

    const val WSS_PREFIX = "wss://"
    const val WS_PREFIX = "ws://"

    /** 为空时的提示（给用户看，不含技术黑话） */
    const val TEXT_EMPTY = "地址为空"

    const val TEXT_NO_SCHEME = "地址要以 wss:// 开头（本机可用 ws://）"

    const val TEXT_BLANK = "地址不能含空格"

    const val TEXT_NO_HOST = "地址里缺少主机名"

    /** 查询串 / 锚点：拼接与协议协商都不认，且凭据会随地址进日志 */
    const val TEXT_BAD_CHARS = "地址里不要带 ? # @（API Key 填下面那一栏）"

    /** 公网明文：这条要能直接显示给用户，说明为什么被拒、该怎么改 */
    const val TEXT_PUBLIC_WS = "公网地址必须用 wss://（ws 只允许本机与局域网）"

    /**
     * 归一 WS 地址：去首尾空白、scheme 转小写、去掉尾部 `/`，路径原样保留
     * （付费端点本身就带 `/api-ws/v1/inference`）。
     *
     * @return 归一后的地址；失败时 `exceptionOrNull().message` 就是给用户看的那句话
     */
    fun normalize(url: String): Result<String> {
        val raw = url.trim()
        if (raw.isEmpty()) return failed(TEXT_EMPTY)
        if (raw.any { it.isWhitespace() }) return failed(TEXT_BLANK)
        val lower = raw.lowercase()
        val secure = lower.startsWith(WSS_PREFIX)
        if (!secure && !lower.startsWith(WS_PREFIX)) return failed(TEXT_NO_SCHEME)
        val rest = raw.substring(if (secure) WSS_PREFIX.length else WS_PREFIX.length)
        if (rest.any { it == '?' || it == '#' }) return failed(TEXT_BAD_CHARS)
        // `@` 只拒 authority 里的那个（`user:pass@host` 的分隔符），路径里的不带凭据
        if ('@' in rest.substringBefore('/')) return failed(TEXT_BAD_CHARS)
        val host = hostOf(rest)
        if (host.isEmpty()) return failed(TEXT_NO_HOST)
        // 明文 ws 只在本机 / 局域网内放行：上行是整段录音，下行是识别正文
        if (!secure && !OpenAiEndpoint.isLocalHost(host)) return failed(TEXT_PUBLIC_WS)
        return Result.success((if (secure) WSS_PREFIX else WS_PREFIX) + rest.trimEnd('/'))
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
