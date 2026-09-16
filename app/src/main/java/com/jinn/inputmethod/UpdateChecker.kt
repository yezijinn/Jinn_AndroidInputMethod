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
 * 注意 Gitee 的网页版 /tags 路径会返回 405，因此备选源改走其开放 API，
 * 返回的 JSON 里同样含 tag 名，**标签解析规则与 GitHub 完全一致**
 * （去 v 前缀、仅保留 6..8 位纯数字、取最大）。
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
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }

    private const val TAG = "UpdateChecker"
}
