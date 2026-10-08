package com.jinn.inputmethod

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查（遵循 unified-update-check 统一）。
 *
 * 版本方案：versionCode 取构建当日日期（yyyyMMdd，纯整数），天然单调可比，
 * versionName 仅展示不参与比较。远程真源取仓库 tag 中的最大日期数字。
 *
 * 源策略：**Gitee 优先，GitHub 备选**；Gitee 拉取失败才回退 GitHub，两源皆失败才判网络异常。
 * 顺序如此定是因为国内网络下 GitHub 常常超时/被重置，把它当首选会让多数用户直接落到
 * 「网络异常」（统一规范 6.9 定规）；Gitee 可直连，公开仓库的 tags API 匿名可访问。
 * Gitee 的网页 /tags 会返回 405，所以它改走开放 API，
 * 返回的 JSON 里同样带 tag 名，标签解析规则与 GitHub 完全一致
 * （去 v 前缀、只保留 6 或 8 位 **ASCII** 数字、取最大；7 位与非 ASCII 数字见 BUG.md L-83 / L-126）。
 */
object UpdateChecker {

    private const val OWNER = "yezijinn"
    private const val REPO = "Jinn_AndroidInputMethod"
    private const val UA = "jinn-update-check"
    private const val TIMEOUT_MS = 10_000

    /**
     * 两源串行的总预算。
     *
     * 单个请求的 [TIMEOUT_MS] 管不住整体：GitHub 不可达是常态，超时后还要回退 Gitee
     * 再来一轮，最坏是「10s + 10s」× 2。调用方的看门狗必须覆盖本预算 + 余量，
     * 否则超时一到就解锁按钮，防重入判据失效、用户能并发发起第二次检查并重复弹窗。
     */
    internal const val TOTAL_BUDGET_MS = 25_000L

    /** 检查结果。Checking 是 UI 侧的瞬时态，不放这里。 */
    sealed interface Result {
        /** 已最新：远程最大日期 <= 本地 */
        data class UpToDate(val latest: Int, val source: String, val tag: String = "") : Result

        /** 有更新：远程最大日期 > 本地 */
        data class Available(val latest: Int, val source: String, val tag: String = "") : Result

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
            // 后台优先级：这是一次网络抓取（最长可到超时上限），跑在用户刚打开设置页的时刻，
            // 不该与前台输入/键盘弹出抢 CPU
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val result = runCatching { fetchLatest() }.fold(
                onSuccess = { l ->
                    if (comparableVersion(l.date) > comparableVersion(local)) Result.Available(l.date, l.source, l.tag)
                    else Result.UpToDate(l.date, l.source, l.tag)
                },
                onFailure = {
                    Diagnostics.w(TAG, "检查更新失败: ${it.message}")
                    Result.NetworkError
                },
            )
            Handler(Looper.getMainLooper()).post { onDone(result) }
        }.apply { name = "jinn-update-check"; isDaemon = true }.start()
    }

    /**
     * 「去更新」跳转地址：按成功源切换，Gitee 直达对应 tag 页。
     *
     * [tag] 必须用**原始标签名**（[normalizeTag] 剥掉的 `v` 前缀要留着）：直达链接按名字取页面，
     * 而仓库 tag 里 `v20260919` 这类带前缀的写法与纯数字并存，用归一化后的数字会落到 404。
     */
    fun releasesUrl(latest: Int, source: String, tag: String = ""): String =
        if (source == "gitee") {
            "https://gitee.com/$OWNER/$REPO/releases/tag/${tag.ifBlank { latest.toString() }}"
        } else {
            "https://github.com/$OWNER/$REPO/releases"
        }

    /** 「打开下载页面」跳转地址：Gitee 发行版列表（附件下载入口），与当前更新源无关。 */
    fun downloadPageUrl(): String = "https://gitee.com/$OWNER/$REPO/releases"

    /** 自动检查间隔：设置页每次打开最多触发一次，两次成功检查之间至少隔 7 天 */
    internal const val AUTO_CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 是否需要执行自动检查（纯函数，可直接 JVM 单测）。
     *
     * @param lastCheckAt 上次成功检查的时刻（epoch ms），从未成功检查为 0
     * @param now 当前时刻（epoch ms）
     *
     * 两种脏数据都按「需要检查」处理，否则会把自动检查永久静默掉：
     * 0/负数（老版本未写入）与未来时间（系统时钟回拨或被外部改写）。
     */
    /**
     * 自动检查的网络与电量门控（MEM-17）：只在「不计费网络」或「正在充电」时跑。
     *
     * 自动检查是后台行为，用户没在等它；手机用流量、靠电池时不该为一次版本检查付流量与唤醒。
     * 判定取不到服务时一律**放行**（宁可多跑一次，也不要让功能静默失效）。
     * **手动检查不经过这里** —— 用户明确点了按钮，就该立刻发请求。
     */
    internal fun autoCheckNetworkOk(context: Context): Boolean = runCatching {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return@runCatching true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return@runCatching true
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@runCatching false
        val bm = context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.isCharging == true
    }.getOrDefault(true)

    internal fun shouldAutoCheck(lastCheckAt: Long, now: Long): Boolean {
        if (lastCheckAt <= 0L) return true
        if (lastCheckAt > now) return true
        return now - lastCheckAt >= AUTO_CHECK_INTERVAL_MS
    }

    /** 一次检查的候选结果：日期数字 + 来源 + **原始标签名**（`v` 前缀留着，跳转链接要用）。 */
    internal data class Latest(val date: Int, val source: String, val tag: String)

    /**
     * 从一批原始标签里挑出**日期最大**的那个（BUG.md L-146）。
     *
     * 抽成纯函数的原因：两个源的**返回顺序不同**（GitHub `/tags` 降序、Gitee API 按名称升序），
     * 而「挑最大」必须与顺序无关 —— 这样它才能被单测钉住（升序 / 降序 / 6 位 8 位混用各来一遍）。
     */
    internal fun pickLatest(tags: List<String>, source: String): Latest? =
        tags.mapNotNull { raw -> normalizeTag(raw)?.let { d -> Latest(d, source, raw) } }
            .maxByOrNull { comparableVersion(it.date) }

    /**
     * 取最大日期标签；**Gitee 优先**，失败才回退 GitHub；两源皆败抛异常。
     *
     * 顺序不可颠倒（统一规范 6.9）：GitHub 在国内网络常不可达，把它排前面会让绝大多数用户
     * 白等一轮超时、甚至直接落到「网络异常」，防护再完整也等于没有。
     *
     * 全程受 [TOTAL_BUDGET_MS] 总预算约束：没有它时「一源超时 + 回退 + 另一源再超时」
     * 会把调用方的看门狗远远甩在后面（见该常量的说明）。
     */
    private fun fetchLatest(): Latest {
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        fetchFromGitee(deadline)?.let { return it }
        Diagnostics.i(TAG, "Gitee 源无可用标签，回退 GitHub")
        fetchFromGithub(deadline)?.let { return it }
        throw IOException("all update sources unreachable")
    }

    /** GitHub：/tags 是 HTML，链接形如 owner/repo/releases/tag/&lt;tag&gt; 或 .../tree/&lt;tag&gt;。 */
    private fun fetchFromGithub(deadline: Long): Latest? = runCatching {
        val html = httpGet("https://github.com/$OWNER/$REPO/tags", deadline) ?: return@runCatching null
        val linkRe = Regex("""$OWNER/$REPO/(?:tree|releases/tag)/([^"'<>?#\s]+)""")
        val best = pickLatest(linkRe.findAll(html).map { it.groupValues[1] }.toList(), "github")
        // 有响应但一个可用标签都没有：与「连不上」是两回事，日志里必须分开，
        // 否则只凭界面文案（由 unified-update-check 约定统一为「访问失败」）无法判断是哪一种
        if (best == null) Diagnostics.w(TAG, "GitHub 源有响应，但没有可解析的日期标签（tag 规范可能变了）")
        best
    }.onFailure { Diagnostics.w(TAG, "GitHub 源异常: ${it.message}") }.getOrNull()

    /** Gitee：网页 /tags 返回 405，改走开放 API；JSON 内的 tag 名同样按统一规则归一化。 */
    private fun fetchFromGitee(deadline: Long): Latest? = runCatching {
        // 只认条目自身的 `name`：注解标签的条目里 `tagger` 是个对象，它的 `name` 是用户名
        // （实测同一页因此多出 4 个候选：`YeZiJinn` / `yezijinn`，见 BUG.md L-1053）⇒ 先把它整段削掉。
        // 轻量标签的 `tagger` 是 `null`，正则要求 `{`，不会误伤。
        val taggerRe = Regex(""""tagger"\s*:\s*\{[^{}]*\}""")
        val nameRe = Regex(""""name"\s*:\s*"([^"]+)"""")
        // **翻页必须同时带 `per_page`**（BUG.md L-1052，接口实测 2026-10-07）：只传 `page` 时服务端
        // 忽略它 —— 六次逐页请求的响应字节数完全相同（6022），于是并集是同一页的 N 份复制
        // （真机日志因此虚报「160 个」，实际仓库只有 28 个标签），`GITEE_TAGS_MAX_PAGES` 这道
        // 上限守卫空转；而 `fetchLatest` 先返回非空结果 ⇒ 这份可能被截断的答案会**压制** GitHub
        // 回退源，一旦接口给「不传 per_page」的整份返回施加更大上限，表现就是永久「已最新」
        // 且不自愈（L-146 的永久版，而这套翻页对它是无效防线）。加上 `per_page` 后 `page` 才生效：
        // 实测 `per_page=5&page=2` 返回第二页（`20260925`…`20260930`，与首页不重叠）。
        //
        // 不依赖排序参数：并集 + 取最大与顺序无关（`direction=desc` 现在虽可用，但它不是接口文档
        // 承诺的参数，一旦哪天被拒就是 400 ⇒ 本源作废，代价远大于收益）。
        val all = ArrayList<String>()
        var page = 1
        while (page <= GITEE_TAGS_MAX_PAGES) {
            val body = httpGet(
                "https://gitee.com/api/v5/repos/$OWNER/$REPO/tags?per_page=$GITEE_TAGS_PAGE_LIMIT&page=$page",
                deadline,
            )
            if (body == null) {
                // 中途取不到**不等于**到底（BUG.md L-1048）：接口按名称升序返回，已取到的页恰好是
                // 最旧的那一段，拿它跑 pickLatest 会定出一个偏旧的「最新版本」⇒ 假的「已最新」
                // + 七天节流且不自愈（与 L-146 同型）。本源作废、交给调用方回退下一源，
                // 宁可如实报网络异常，也不给一个错结论。
                Diagnostics.w(TAG, "Gitee 标签第 $page 页取不到，本源作废（转回退源）")
                return@runCatching null
            }
            val names = nameRe.findAll(body.replace(taggerRe, "")).map { it.groupValues[1] }.toList()
            // 只有「空页」才是正常到底
            if (names.isEmpty()) break
            all += names
            page++
        }
        if (page > GITEE_TAGS_MAX_PAGES) {
            // 到上限不等于到底（可能有更多 tag 且更新的还在后面）⇒ 说出来，别让它变成静默漏报
            Diagnostics.w(TAG, "Gitee 标签翻页到上限 ${GITEE_TAGS_MAX_PAGES} 页（${all.size} 个），可能还有更靠后的标签")
        }
        val best = pickLatest(all, "gitee")
        // 能走到这里说明至少有一页是真响应（取不到页的那条已在上面作废）⇒「有响应」这句才是准的
        if (best == null) Diagnostics.w(TAG, "Gitee 源有响应，但没有可解析的日期标签（tag 规范可能变了）")
        best
    }.onFailure { Diagnostics.w(TAG, "Gitee 源异常: ${it.message}") }.getOrNull()

    /**
     * 比较用的**归一键**（BUG.md L-123）：6 位 `yyMMdd` 补成 8 位（`+20000000`），8 位原样。
     *
     * `normalizeTag` 的返回值会进 URL / 文案（必须保持原始写法），所以**只在这里**归一：
     * 否则混用两种长度时按数值比会恒错（`260929` 恒小于 `20260929`）⇒ 跨源挑旧、与本地比判「已最新」。
     */
    internal fun comparableVersion(value: Int): Int =
        if (value < 10_000_000) value + 20_000_000 else value

    /**
     * 标签归一化：去 `v` 前缀 → 只认 **6 位或 8 位**纯数字且须像日期。
     *
     * 返回值既进 URL / 文案（要保持原始写法），也进比较 —— 比较前必须先过 [comparableVersion]。
     * 逐条判据（7 位为什么拒收、为什么必须是 ASCII 数字、日期闸的宽严）写在本函数体内。
     */
    internal fun normalizeTag(raw: String): Int? {
        // 大小写 `v` 前缀都认（BUG.md L-147）：仓库现行约定是小写，但 `V20260919` 这种写法
        // 会被只去小写的实现**静默忽略**（等价于该 tag 不存在 ⇒ 退化为取次大值 ⇒ 可能误报
        // 「已是最新」且不自愈）。只削**一个**前缀字符：`vV20260929` 仍然是拒收（长度 9）。
        val digits = if (raw.startsWith("v") || raw.startsWith("V")) raw.substring(1) else raw
        // 只认 6 位（yyMMdd）与 8 位（yyyyMMdd，= versionCode 格式）：7 位两者都不是（BUG.md L-83）。
        // 现状（tag 全 8 位）里 7 位数值上**恒小于**任何 8 位、不会误报；但标签规范一旦回退到 6 位
        // （`yyMMdd`，即 L-123 记的混用场景），7 位就会**恒大于**全部合法值 ⇒「永远提示有新版本、
        // 点进去打不开」且不自愈（与 L-04 同型）⇒ 按「别猜」口径拒收，别留下要看前提才成立的判据。
        if (digits.length != 6 && digits.length != 8) return null
        // **ASCII** 数字：`Char::isDigit` 认 Unicode 全角 / 阿拉伯-印度数字，`toIntOrNull`
        // （经 `Character.digit`）同样认 —— 实测 `"２０２６０９２９"` → 20260929、`"2026092０"` → 20260920
        // ⇒ 用中文输入法打出的全角 tag 会被当成版本号，而 `releasesUrl` 按约定把**原始串**拼进直达
        // 链接（不编码）⇒ 点进去 404（BUG.md L-126）。
        if (!digits.all { it in '0'..'9' }) return null
        val value = digits.toIntOrNull() ?: return null
        // 6 位按 `20yyMMdd` 过同一道日期闸：`v260999` 这种打错的同样要挡掉
        if (digits.length == 6 && !isPlausibleDate(20000000 + value)) return null
        // 8 位（= 版本号格式 yyyyMMdd）必须是**合法日期**：tag 由人手动建，一个打错的
        // 20261331 会被当成「未来版本」⇒ 此后每次检查都提示有更新、点进去却打不开对应页面，
        // 而且这种误报不会自愈（要等一个更大的正确 tag 出现）。日期不合法即不认。
        if (digits.length == 8 && !isPlausibleDate(value)) return null
        return value
    }

    /**
     * `yyyyMMdd` 是否构成合法日期（年 2000..2099、月 1..12、日 1..31；不查闰年与大小月）。
     *
     * 判据刻意宽（只挡「明显不是日期」的标签），因为版本号只要求单调可比，
     * 目的不是校历而是过滤打错的 tag（BUG.md L-04）。
     */
    internal fun isPlausibleDate(yyyymmdd: Int): Boolean {
        val year = yyyymmdd / 10_000
        val month = (yyyymmdd / 100) % 100
        val day = yyyymmdd % 100
        return year in 2000..2099 && month in 1..12 && day in 1..31
    }

    /** 返回响应体；非 200 / 总预算耗尽 / 异常返回 null（由调用方决定是否回退下一源）。 */
    private fun httpGet(url: String, deadline: Long): String? {
        // 只准 https（BUG.md L-100）：本文件两条源的 URL 都是 https 字面量，这里把口径钉成
        // 代码级契约 —— 将来有人加一条 http 源（或把某处的 scheme 写错），会在这里被拒并留日志，
        // 而不是静默走明文（明文闸门只对用户自填的局域网语音地址放开，见 network_security_config.xml）。
        if (!url.startsWith("https://")) {
            Diagnostics.w(TAG, "拒绝非 HTTPS 地址: ${url.substringBefore('?')}")
            return null
        }
        // 单个请求的超时不能超过剩余总预算：否则两源串行会把调用方的看门狗甩掉
        val remain = (deadline - System.currentTimeMillis()).coerceAtMost(TIMEOUT_MS.toLong())
        if (remain <= 0L) {
            Diagnostics.w(TAG, "总预算已耗尽，跳过请求: ${url.substringBefore('?')}")
            return null
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = remain.toInt()
            readTimeout = remain.toInt()
            setRequestProperty("User-Agent", UA)
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Diagnostics.w(TAG, "HTTP ${conn.responseCode}: $url")
                null
            } else if (!conn.url.protocol.equals("https", ignoreCase = true)) {
                // HttpURLConnection 默认跟随跨协议重定向，https 起点也可能被
                // 带到 http 终点（内容可被中间人改写）。更新检查只认 https 终态。
                Diagnostics.w(TAG, "重定向后非 HTTPS，拒绝: ${conn.url}")
                null
            } else {
                // 限长读取：响应体没有上限时，一个「一直有数据、永不结束」的响应
                // 能在 readTimeout 内累积到几十 MB（/tags 页正常只有几百 KB）。
                // 读取也受**总预算**约束（BUG.md L-145）：socket 超时只约束「单次 read」，
                // 一个滴水的服务器（每次都在超时前吐一行）能把总时长拉到远超 [TOTAL_BUDGET_MS]，
                // 把调用方的看门狗甩掉 ⇒ 按钮解锁后用户可再点，晚到的回调又回来捣乱。
                val body = conn.inputStream.bufferedReader().use { readCapped(it, MAX_BODY_CHARS, deadline) }
                if (System.currentTimeMillis() >= deadline) {
                    Diagnostics.w(TAG, "读取到总预算上限，已截断: ${url.substringBefore('?')}")
                }
                body
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 带上限的读取（纯逻辑，便于单测）。
     *
     * 超出 [MAX_BODY_CHARS] 的字符直接丢弃、停止读取：更新检查只需要 tag 名，
     * 而 tag 一定出现在页面前部，截断不影响判定。
     *
     * 注意截断按行判定（整行超限就整行不要）：真遇到「单行就超过 1MB」的响应会返回空串，
     * 结果是走「两个源都拉不到」分支报网络异常（不会误报成「已最新」），可以接受。
     *
     * [deadline] 是**总预算**（墙钟毫秒）：到点立刻停读（BUG.md L-145）。没有它时，
     * `readTimeout` 只约束单次 read，滴水响应用「每次都在超时前吐一点」就能把总时长无限拉长。
     * 本函数保持纯逻辑（不写日志），截断原因由调用方 [httpGet] 判定并记录。
     */
    internal fun readCapped(
        reader: java.io.BufferedReader,
        maxChars: Int = MAX_BODY_CHARS,
        deadline: Long = Long.MAX_VALUE,
    ): String {
        val sb = StringBuilder(minOf(maxChars, 8192))
        var total = 0
        while (true) {
            if (System.currentTimeMillis() > deadline) break
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

    /**
     * 请求参数 `per_page`：**必须与 `page` 一起传**，否则服务端忽略 `page`、每页都返回首页副本
     * （BUG.md L-1052）。100 是这类接口的常见上限档，取满一页把请求数压到最低。
     *
     * 名字刻意不叫 `GITEE_TAGS_PAGE_SIZE` —— 那一个是当年「拿实测页大小当终止判据」的标记，
     * 已被守卫禁止复现（终止只看空页）；这里只是请求参数，与判据无关。
     */
    private const val GITEE_TAGS_PAGE_LIMIT = 100

    /** 翻页上限（5 页 × 100 条 ≈ 500 个标签，够用数年；到上限会留日志，不静默漏） */
    private const val GITEE_TAGS_MAX_PAGES = 5
}
