package com.jinn.inputmethod

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查（遵循 unified-update-check 约定）。
 *
 * 版本方案：versionCode 取构建当日日期（yyyyMMdd，纯整数），天然单调可比，
 * versionName 仅展示不参与比较。远程真源取仓库 tag 中的最大日期数字。
 *
 * 源策略：**GitHub 优先，Gitee 备选**；GitHub 失败自动回退 Gitee，两源皆失败才判网络异常。
 * Gitee 的网页 /tags 会返回 405，所以备选源改走它的开放 API，
 * 返回的 JSON 里同样带 tag 名，**标签解析规则与 GitHub 完全一致**
 * （去 v 前缀、只保留 6..8 位纯数字、取最大）。
 */
object UpdateChecker {

    private const val OWNER = "yezijinn"
    private const val REPO = "Jinn_AndroidInputMethod"
    private const val UA = "jinn-update-check"
    private const val TIMEOUT_MS = 10_000

    /** 检查结果。Checking 是 UI 侧的瞬时态，不放这里。 */
    sealed interface Result {
        /** 已最新：远程最大日期 <= 本地 */
        data class UpToDate(val latest: Int, val source: String) : Result

        /** 有更新：远程最大日期 > 本地 */
        data class Available(val latest: Int, val source: String) : Result

        /** 两个源都拉不到有效日期标签 */
        data object NetworkError : Result
    }

    /**
     * 后台线程检查更新，结果回主线程。
     *
     * @param local 本地 versionCode（构建日期整数）
     * @param onDone 主线程回调，必被调用一次
     */
    fun checkAsync(local: Int, onDone: (Result) -> Unit) {
        Thread {
            val result = runCatching { fetchLatestDateTag() }.fold(
                onSuccess = { (latest, source) ->
                    if (latest > local) Result.Available(latest, source)
                    else Result.UpToDate(latest, source)
                },
                onFailure = {
                    Diagnostics.w(TAG, "检查更新失败: ${it.message}")
                    Result.NetworkError
                },
            )
            Handler(Looper.getMainLooper()).post { onDone(result) }
        }.apply { name = "jinn-update-check" }.start()
    }

    /** 「去更新」跳转地址：按成功源切换，Gitee 直达对应 tag 页。 */
    fun releasesUrl(latest: Int, source: String): String =
        if (source == "gitee") "https://gitee.com/$OWNER/$REPO/releases/tag/$latest"
        else "https://github.com/$OWNER/$REPO/releases"

    /** 返回 (最大日期标签, 来源)；GitHub 失败自动回退 Gitee；两源皆败抛异常。 */
    private fun fetchLatestDateTag(): Pair<Int, String> {
        fetchFromGithub()?.let { return it to "github" }
        Diagnostics.i(TAG, "GitHub 源无可用标签，回退 Gitee")
        fetchFromGitee()?.let { return it to "gitee" }
        throw IOException("all update sources unreachable")
    }

    /** GitHub：/tags 是 HTML，链接形如 owner/repo/releases/tag/&lt;tag&gt; 或 .../tree/&lt;tag&gt;。 */
    private fun fetchFromGithub(): Int? = runCatching {
        val html = httpGet("https://github.com/$OWNER/$REPO/tags") ?: return@runCatching null
        val linkRe = Regex("""$OWNER/$REPO/(?:tree|releases/tag)/([^"'<>?#\s]+)""")
        val nums = linkRe.findAll(html).mapNotNull { m -> normalizeTag(m.groupValues[1]) }
        nums.maxOrNull()
    }.onFailure { Diagnostics.w(TAG, "GitHub 源异常: ${it.message}") }.getOrNull()

    /** Gitee：网页 /tags 返回 405，改走开放 API；JSON 内的 tag 名同样按统一规则归一化。 */
    private fun fetchFromGitee(): Int? = runCatching {
        val body = httpGet("https://gitee.com/api/v5/repos/$OWNER/$REPO/tags")
            ?: return@runCatching null
        val nameRe = Regex(""""name"\s*:\s*"([^"]+)"""")
        val nums = nameRe.findAll(body).mapNotNull { m -> normalizeTag(m.groupValues[1]) }
        nums.maxOrNull()
    }.onFailure { Diagnostics.w(TAG, "Gitee 源异常: ${it.message}") }.getOrNull()

    /** 统一标签归一化：去 v 前缀 → 仅接受 6..8 位纯数字（两源同规则）。 */
    private fun normalizeTag(raw: String): Int? =
        raw.removePrefix("v").takeIf { it.length in 6..8 && it.all(Char::isDigit) }?.toIntOrNull()

    /** 返回响应体；非 200 或异常返回 null（由调用方决定是否回退下一源）。 */
    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Diagnostics.w(TAG, "HTTP ${conn.responseCode}: $url")
                null
            } else if (!conn.url.protocol.equals("https", ignoreCase = true)) {
                // HttpURLConnection 默认跟随**跨协议**重定向，https 起点也可能被
                // 带到 http 终点（内容可被中间人改写）。更新检查只认 https 终态。
                Diagnostics.w(TAG, "重定向后非 HTTPS，拒绝: ${conn.url}")
                null
            } else {
                // 限长读取：响应体没有上限时，一个「一直有数据、永不结束」的响应
                // 能在 readTimeout 内累积到几十 MB（/tags 页正常只有几百 KB）。
                conn.inputStream.bufferedReader().use { readCapped(it) }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 带上限的读取（**纯逻辑**，便于单测）。
     *
     * 超出 [MAX_BODY_CHARS] 的字符直接丢弃、停止读取：更新检查只需要 tag 名，
     * 而 tag 一定出现在页面前部，截断不影响判定。
     *
     * 注意截断按**行**判定（整行超限就整行不要）：真遇到「单行就超过 1MB」的响应会返回空串，
     * 结果是走「两个源都拉不到」分支报网络异常（不会误报成「已最新」），可以接受。
     */
    internal fun readCapped(reader: java.io.BufferedReader, maxChars: Int = MAX_BODY_CHARS): String {
        val sb = StringBuilder(minOf(maxChars, 8192))
        var total = 0
        while (true) {
            val line = reader.readLine() ?: break
            total += line.length + 1
            if (total > maxChars) break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private const val TAG = "UpdateChecker"

    /** tags 页 / tags API 的响应上限（字符）：正常响应几百 KB，1MB 留足余量 */
    private const val MAX_BODY_CHARS = 1_000_000
}
