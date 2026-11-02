package com.jinn.inputmethod

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞听写协议层（[XfIatProtocol] / [XfTextAccumulator]）的单测。
 *
 * 为什么必须有这一层：真机上只能看到「识别到 0 个字」或「连不上」，分不出是签名算错、
 * 帧字段少了一个、还是 `rpl` 没做区间替换（后者表现为文字重复或前文丢失，看起来像识别不准）。
 *
 * 判据一律用**可复算**的期望值：签名在测试里按文档公式独立算一遍再比，
 * 不把实现里的中间串当期望值 —— 否则实现写错时两边一起错，测试照样绿。
 */
class XfIatProtocolTest {

    // ── 鉴权 URL ────────────────────────────────────────────────

    @Test
    fun `鉴权 URL 的签名可按文档公式复算`() {
        val apiKey = "test-key-not-a-real-credential"
        val apiSecret = "test-secret-not-a-real-credential"
        val date = "Sun, 01 Nov 2026 08:00:00 GMT"

        val url = XfIatProtocol.buildAuthUrl("app-id-test", apiKey, apiSecret, date)

        assertTrue("必须走 wss 且指向听写端点：$url", url.startsWith("wss://${XfIatProtocol.HOST}${XfIatProtocol.PATH}?"))
        // 同一输入两次必须逐字节相等（签名里不许混进时间戳 / 随机数）
        assertEquals(url, XfIatProtocol.buildAuthUrl("app-id-test", apiKey, apiSecret, date))

        val query = url.substringAfter("?")
        val host = queryParam(query, "host")
        val gotDate = queryParam(query, "date")
        val authorization = queryParam(query, "authorization")
        assertEquals("host 参数就是端点主机", XfIatProtocol.HOST, host)
        assertEquals("date 参数必须等于签名用的原文（编码前后一致）", date, gotDate)
        // query 只允许这三项，且顺序固定；appId 只进首帧 header.app_id（进 URL 等于把凭据摊到地址里）
        assertEquals(
            "query 多出了协议外的参数",
            listOf("host", "date", "authorization"),
            query.split("&").map { it.substringBefore("=") },
        )
        assertTrue("appId 不许出现在鉴权 URL 里", "app-id-test" !in url)

        // 独立复算：Base64(HMAC-SHA256(secret, "host: h\ndate: d\nGET /v1 HTTP/1.1"))
        val origin = "host: ${XfIatProtocol.HOST}\ndate: $date\nGET ${XfIatProtocol.PATH} HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(origin.toByteArray(Charsets.UTF_8)))

        val authorizationOrigin = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line\", signature=\"$signature\""
        val expected = Base64.getEncoder().encodeToString(authorizationOrigin.toByteArray(Charsets.UTF_8))
        assertEquals("authorization 与文档公式算出的不一致（两级 Base64 / headers 固定串）", expected, authorization)

        // 再解一层：里面必须是原文形态，而不是又一次 Base64
        val decoded = String(Base64.getDecoder().decode(authorization), Charsets.UTF_8)
        assertTrue("authorization 解码后必须含 api_key=（只做了一次 Base64？）", decoded.startsWith("api_key=\"$apiKey\""))
        assertTrue("headers 必须是固定字符串（不是取值）", "headers=\"host date request-line\"" in decoded)
    }

    @Test
    fun `鉴权 URL 的 query 顺序固定且 date 已编码`() {
        val url = XfIatProtocol.buildAuthUrl(
            appId = "app", apiKey = "k", apiSecret = "s", date = XfIatProtocol.rfc1123Gmt(0L),
        )
        val query = url.substringAfter("?")
        val hostAt = query.indexOf("host=")
        val dateAt = query.indexOf("&date=")
        val authAt = query.indexOf("&authorization=")
        assertTrue("query 顺序必须是 host → date → authorization", hostAt == 0 && dateAt > hostAt && authAt > dateAt)
        // 空格 / 逗号必须编码（服务端解码后再比对，签名用的是原文）
        assertTrue("date 里的空格没有编码（URL 会被再次改写）", " " !in query)
        assertTrue("date 必须带 GMT", queryParam(query, "date").endsWith("GMT"))
    }

    @Test
    fun `GMT 日期串用 Locale US 且不随本机时区漂移`() {
        // 0 ms = 1970-01-01T00:00:00Z，星期四
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", XfIatProtocol.rfc1123Gmt(0L))
        // 2026-11-01T08:00:00Z（东八区 16:00）必须仍是 GMT 的那一小时
        val ms = 1793520000000L
        assertEquals("Sun, 01 Nov 2026 08:00:00 GMT", XfIatProtocol.rfc1123Gmt(ms))
    }

    // ── 帧 ──────────────────────────────────────────────────────

    @Test
    fun `首帧必须带全部业务参数`() {
        val pcm = ByteArray(1280) { (it % 100).toByte() }
        val json = JSONObject(XfIatProtocol.firstFrame("app-id", pcm, 0))
        assertEquals(0, json.getJSONObject("header").getInt("status"))
        assertEquals("app-id", json.getJSONObject("header").getString("app_id"))
        val iat = json.getJSONObject("parameter").getJSONObject("iat")
        assertEquals("spark_asr_v2", iat.getString("domain"))
        assertEquals("zh_cn", iat.getString("language"))
        assertEquals("mandarin", iat.getString("accent"))
        assertEquals(60000, iat.getInt("eos"))
        assertEquals("wpgs", iat.getString("dwa"))
        val result = iat.getJSONObject("result")
        assertEquals("utf8", result.getString("encoding"))
        assertEquals("raw", result.getString("compress"))
        assertEquals("json", result.getString("format"))
        val audio = json.getJSONObject("payload").getJSONObject("audio")
        assertEquals("raw", audio.getString("encoding"))
        assertEquals(16000, audio.getInt("sample_rate"))
        assertEquals(1, audio.getInt("channels"))
        assertEquals(16, audio.getInt("bit_depth"))
        assertEquals(0, audio.getInt("status"))
        assertEquals(0, audio.getInt("seq"))
        assertTrue("首帧音频必须 base64 编码", audio.getString("audio") == Base64.getEncoder().encodeToString(pcm))
    }

    @Test
    fun `中间帧与末帧的状态和 seq 递增`() {
        val frame = ByteArray(1280) { 1 }
        val mid = JSONObject(XfIatProtocol.audioFrame("app-id", frame, 3))
        assertEquals(1, mid.getJSONObject("header").getInt("status"))
        assertEquals(3, mid.getJSONObject("payload").getJSONObject("audio").getInt("seq"))
        assertEquals(1, mid.getJSONObject("payload").getJSONObject("audio").getInt("status"))
        assertTrue("中间帧不该带 parameter（只有首帧有）", !mid.has("parameter"))
        // 2026-11-01 真机实测：音频帧不带 header.app_id 会被服务端当场拒（10163），
        // 只在首帧带不够。官方端点 A/B：带 app_id 全绿、不带必报，故每帧都得带。
        assertEquals("中间帧必须带 header.app_id", "app-id", mid.getJSONObject("header").getString("app_id"))

        val last = JSONObject(XfIatProtocol.lastFrame("app-id", 9))
        assertEquals(2, last.getJSONObject("header").getInt("status"))
        assertEquals("末帧必须带 header.app_id", "app-id", last.getJSONObject("header").getString("app_id"))
        val audio = last.getJSONObject("payload").getJSONObject("audio")
        assertEquals(2, audio.getInt("status"))
        assertEquals(9, audio.getInt("seq"))
        assertEquals("末帧 audio 必须是空串（不是缺字段）", "", audio.getString("audio"))
    }

    // ── 响应解析 ─────────────────────────────────────────────────

    @Test
    fun `响应解析解出 base64 文本并拼接 ws`() {
        val raw = responseOf(
            words = listOf("今天", "天气", "不错"),
            ls = true,
            pgs = "apd",
            rg = listOf(1, 1),
            status = 2,
            sn = 7,
        )
        val resp = XfIatProtocol.parseResponse(raw)
        assertNotNull("合法回包必须解析成功", resp)
        resp!!
        assertEquals(0, resp.code)
        assertEquals("success", resp.message)
        assertEquals("sid-1", resp.sid)
        assertEquals(2, resp.status)
        val result = resp.result
        assertNotNull("payload.result 必须解出来", result)
        result!!
        assertEquals(2, result.status)
        assertEquals("今天天气不错", result.text)
        assertTrue("ls=true 必须读出来", result.ls)
        assertEquals("apd", result.pgs)
        assertEquals(listOf(1, 1), result.rg)
        // sn 在解码后的内层 JSON 里（`payload.result.sn` 恒为 null），累积器靠它断句
        assertEquals("sn 必须从内层 JSON 解出来", 7, result.sn)
    }

    @Test
    fun `坏报文只返回 null 不抛`() {
        assertNull(XfIatProtocol.parseResponse(""))
        assertNull(XfIatProtocol.parseResponse("not json"))
        assertNull("缺 header 的报文不算合法回包", XfIatProtocol.parseResponse("""{"payload":{}}"""))
    }

    // ── 文本累积 ─────────────────────────────────────────────────

    @Test
    fun `累积器单次 apd 直接给出该段`() {
        val acc = XfTextAccumulator()
        assertEquals("你好", acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("你好"), pgs = "apd")))))
    }

    @Test
    fun `累积器连续 apd 逐段追加`() {
        val acc = XfTextAccumulator()
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("今天"), sn = 1, pgs = "apd"))))
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("天气"), sn = 2, pgs = "apd"))))
        val text = acc.accept(
            checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("不错"), sn = 3, pgs = "apd"))),
        )
        assertEquals("今天天气不错", text)
    }

    @Test
    fun `累积器 rpl 按 rg 区间回退替换`() {
        val acc = XfTextAccumulator()
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("今天"), sn = 1, pgs = "apd"))))
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("天汽"), sn = 2, pgs = "apd"))))
        // 第 2 段说错了：整段换掉（rg 是 1 基、含端点）
        val text = acc.accept(
            checkNotNull(
                XfIatProtocol.parseResponse(
                    responseOf(listOf("天气"), sn = 3, pgs = "rpl", rg = listOf(2, 2)),
                ),
            ),
        )
        assertEquals("今天天气", text)
    }

    @Test
    fun `累积器 rg 为 1 1 时整段替换首段`() {
        val acc = XfTextAccumulator()
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("你好"), sn = 1, pgs = "apd"))))
        // 只有一段时 rg=[1,1] 就是「全替换」
        val text = acc.accept(
            checkNotNull(
                XfIatProtocol.parseResponse(responseOf(listOf("您好"), sn = 2, pgs = "rpl", rg = listOf(1, 1))),
            ),
        )
        assertEquals("您好", text)

        // 多段时 [1,1] 只动首段，其余保留
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("世界"), sn = 3, pgs = "apd"))))
        val two = acc.accept(
            checkNotNull(
                XfIatProtocol.parseResponse(responseOf(listOf("大家好"), sn = 4, pgs = "rpl", rg = listOf(1, 1))),
            ),
        )
        assertEquals("大家好世界", two)
    }

    @Test
    fun `累积器遇到空结果不改动已有文本`() {
        val acc = XfTextAccumulator()
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("已经识别"), sn = 1, pgs = "apd"))))
        // 无 result 的回包
        val heartbeat = """{"header":{"code":0,"message":"success","sid":"sid-1","status":1},"payload":{}}"""
        assertEquals("已经识别", acc.accept(checkNotNull(XfIatProtocol.parseResponse(heartbeat))))
        // 空文本段 + 未收尾
        val empty = responseOf(listOf(""), sn = 2, pgs = "apd", ls = false)
        assertEquals("已经识别", acc.accept(checkNotNull(XfIatProtocol.parseResponse(empty))))
    }

    @Test
    fun `累积器把无 pgs 的 ls 收尾当最终文本`() {
        // 非 wpgs 形态：服务端每次给整段，最后一片就是全量
        val acc = XfTextAccumulator()
        val text = acc.accept(
            checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("完整的一句话"), ls = true))),
        )
        assertEquals("完整的一句话", text)
    }

    @Test
    fun `累积器不会把 wpgs 末片当成整段而丢掉前文`() {
        // wpgs 形态：先 apd 过两段，最后一片没有 pgs 但显然是增量
        val acc = XfTextAccumulator()
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("今天"), sn = 1, pgs = "apd"))))
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("天气"), sn = 2, pgs = "apd"))))
        val text = acc.accept(
            checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("不错"), sn = 3, ls = true))),
        )
        assertEquals("今天天气不错", text)
    }

    @Test
    fun `累积器对越界 rg 不崩`() {
        val acc = XfTextAccumulator()
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("一"), sn = 1, pgs = "apd"))))
        // 服务端丢包重传时给过超出本地下标的 rg
        val text = acc.accept(
            checkNotNull(
                XfIatProtocol.parseResponse(responseOf(listOf("二"), sn = 2, pgs = "rpl", rg = listOf(5, 9))),
            ),
        )
        assertTrue("越界 rg 必须夹到已有片段范围，不能抛异常", text.isNotEmpty())
    }

    // ── 累积器 · 真实会话序列（A）─────────────────────────────────

    /** 一句 16 个字的样本：每帧的 `ws` 都是「本句至今全部字」，与实测形态逐字对应 */
    private val realWords = "今日天气晴朗适宜出门散步赏花听风".map { it.toString() }.toList()

    /**
     * 实测的 16 帧序列（`pgs` / `rg` / `sn` 都在 `text` 解码后的内层 JSON 里）：
     * ```
     * sn=1  apd  ws=[1 个词]
     * sn=N  rpl  rg=[1,N-1]  ws=[本句至今全部词]         (N=2..14)
     * sn=15 rpl  rg=[1,14]   ws=[1 个词 = 整句]           ← 整句被改成一个条目
     * sn=16 ls=true apd      ws=[{"w":""}]                ← 末帧文本为空串
     * ```
     * 每一步的累积文本都必须等于**该步 `ws` 拼出的整句**，第 16 步不许倒退 / 变空。
     */
    @Test
    fun `累积器按真实会话的 16 帧逐步给出整句`() {
        val acc = XfTextAccumulator()
        // 实测的第 0 条回包：首帧应答，payload.result 里什么都没有。
        // 它既不能改动文本，也不能把「sawFrame」判成「已经说过一句」而把随后真正的 sn=1 当成断句。
        val hello = """{"header":{"code":0,"message":"success","sid":"sid-1","status":1},"payload":{"result":{}}}"""
        assertEquals("首帧应答不该改动文本", "", acc.accept(checkNotNull(XfIatProtocol.parseResponse(hello))))
        for (sn in 1..14) {
            val wordsSoFar = realWords.subList(0, sn)
            val frame = if (sn == 1) {
                responseOf(words = wordsSoFar, sn = 1, pgs = "apd")
            } else {
                responseOf(words = wordsSoFar, sn = sn, pgs = "rpl", rg = listOf(1, sn - 1))
            }
            val resp = checkNotNull(XfIatProtocol.parseResponse(frame)) { "第 $sn 帧必须能解析" }
            assertEquals("第 $sn 帧的 sn 解析错了", sn, resp.result!!.sn)
            assertEquals(
                "第 $sn 帧后的累积文本必须等于该帧 ws 拼出的整句",
                wordsSoFar.joinToString(""),
                acc.accept(resp),
            )
        }

        val whole = realWords.joinToString("")
        // sn=15：整句被换成一个 ws 条目，rg 仍指向前 14 片 —— 累积文本不变
        assertEquals(
            "sn=15（整句写成 1 个条目）后的累积文本必须仍是整句",
            whole,
            acc.accept(
                checkNotNull(
                    XfIatProtocol.parseResponse(
                        responseOf(words = listOf(whole), sn = 15, pgs = "rpl", rg = listOf(1, 14)),
                    ),
                ),
            ),
        )

        // sn=16：ls=true、pgs=apd、ws=[{"w":""}] —— 末帧不许把整句清空
        val last = checkNotNull(
            XfIatProtocol.parseResponse(
                responseOf(words = listOf(""), sn = 16, pgs = "apd", ls = true, status = 2),
            ),
        )
        assertTrue("ls=true 那一帧必须能被判成收尾（客户端 isFinal 的判据之一）", last.result!!.ls)
        assertEquals("末帧空文本把整句清空了", whole, acc.accept(last))
    }

    @Test
    fun `累积器 rpl 的 rg 是 1 基闭区间`() {
        val acc = XfTextAccumulator()
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("一"), sn = 1, pgs = "apd"))))
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("二"), sn = 2, pgs = "apd"))))
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("三"), sn = 3, pgs = "apd"))))
        assertEquals("一二三", acc.text)

        // rg=[1,2] = 第 1、2 两片整体换掉（含端点），第 3 片保留
        val text = acc.accept(
            checkNotNull(
                XfIatProtocol.parseResponse(
                    responseOf(listOf("替换"), sn = 4, pgs = "rpl", rg = listOf(1, 2)),
                ),
            ),
        )
        assertEquals("闭区间 [1,2] 必须清掉第 1、2 片", "替换三", text)
        assertFalse("第 2 片没被替换掉（rg 被当成左闭右开）", text.contains("二"))
    }

    @Test
    fun `末帧空文本不清空且无 pgs 的空收尾也不清空`() {
        val acc = XfTextAccumulator()
        val text = acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("已经识别"), pgs = "apd"))))
        assertEquals("已经识别", text)
        // 形态 1：ls=true + apd + 空 ws（实测末帧）
        assertEquals(
            "ls 末帧的空文本把整句清空了",
            "已经识别",
            acc.accept(
                checkNotNull(
                    XfIatProtocol.parseResponse(
                        responseOf(listOf(""), pgs = "apd", ls = true, status = 2),
                    ),
                ),
            ),
        )
        // 形态 2：ls=true 且没有 pgs，文本为空 —— 不许被当成「整段最终文本 = 空」而覆盖前文
        assertEquals(
            "无 pgs 的空收尾被当成整段最终文本，前文被清空",
            "已经识别",
            acc.accept(
                checkNotNull(
                    XfIatProtocol.parseResponse(responseOf(listOf(""), ls = true, status = 2)),
                ),
            ),
        )
    }

    /**
     * `vgap` 断句：第二句的 `sn` 与它自己的 `rg` 重新从 1 开始。
     *
     * 若两句共用一个片段下标空间，第二句的 `rg=[1,1]` 会把第一句整句替换掉 ——
     * 表现是「说了两句，只上屏后一句」。
     */
    @Test
    fun `多句场景第二句的 rg 不抹掉第一句`() {
        val acc = XfTextAccumulator()
        // 第一句
        acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("第一句"), sn = 1, pgs = "apd"))))
        assertEquals(
            "第一句话",
            acc.accept(
                checkNotNull(
                    XfIatProtocol.parseResponse(
                        responseOf(listOf("第一句话"), sn = 2, pgs = "rpl", rg = listOf(1, 1)),
                    ),
                ),
            ),
        )
        // 第二句：sn 回到 1
        assertEquals(
            "断句后第二句的第一个字必须接在第一句后面",
            "第一句话第二句",
            acc.accept(checkNotNull(XfIatProtocol.parseResponse(responseOf(listOf("第二句"), sn = 1, pgs = "apd")))),
        )
        assertEquals(
            "第二句的 rg=[1,1] 只许改第二句自己，第一句被抹掉了",
            "第一句话第二句话",
            acc.accept(
                checkNotNull(
                    XfIatProtocol.parseResponse(
                        responseOf(listOf("第二句话"), sn = 2, pgs = "rpl", rg = listOf(1, 1)),
                    ),
                ),
            ),
        )
    }

    // ── float32 → PCM16 ─────────────────────────────────────────

    @Test
    fun `float32 转 PCM16 的边界值`() {
        assertEquals(32767, pcm16Of(1.0f))
        assertEquals(-32768, pcm16Of(-1.0f))
        assertEquals(16384, pcm16Of(0.5f))
        assertEquals(0, pcm16Of(0f))
        // 饱和钳位：越界浮点不能回绕成反相
        assertEquals("+1.5 必须钳到满量程", 32767, pcm16Of(1.5f))
        assertEquals("-1.5 必须钳到负满量程", -32768, pcm16Of(-1.5f))
    }

    @Test
    fun `float32 转 PCM16 保持小端与样本数`() {
        val src = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.5f).putFloat(-0.5f).array()
        val out = XfIatProtocol.float32ToPcm16(src)
        assertEquals("4 字节 float × 2 → 2 字节 short × 2", 4, out.size)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16384, buf.short.toInt())
        assertEquals(-16384, buf.short.toInt())
    }

    // ── 工具 ────────────────────────────────────────────────────

    /** 单样本转换的期望值（只喂一个 float 再读回唯一那个 short） */
    private fun pcm16Of(value: Float): Int {
        val src = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        return ByteBuffer.wrap(XfIatProtocol.float32ToPcm16(src)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
    }

    private fun queryParam(query: String, name: String): String {
        val raw = query.split("&").firstOrNull { it.startsWith("$name=") }?.substringAfter("=").orEmpty()
        return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    }

    /**
     * 造一条服务端回包：`text` 是 base64(`{sn,ls,pgs,rg,ws[].cw[].w}`)。
     *
     * `sn` / `pgs` / `rg` 一律放在**解码后的内层 JSON** 里 —— 实测形态就是如此
     * （`payload.result.pgs` 恒为 null），放在外层会让用例测不到真实解析路径。
     *
     * @param words 多个 `ws` 条目（每个取第一个候选）—— 用来验「拼接」，不是每段一个包。
     */
    private fun responseOf(
        words: List<String>,
        ls: Boolean = false,
        pgs: String? = null,
        rg: List<Int>? = null,
        status: Int = 1,
        sn: Int = 1,
    ): String {
        val inner = JSONObject().apply {
            put("sn", sn)
            put("ls", ls)
            if (pgs != null) put("pgs", pgs)
            if (rg != null) {
                put("rg", JSONArray().also { arr -> rg.forEach { arr.put(it) } })
            }
            put(
                "ws",
                JSONArray().also { arr ->
                    words.forEach { w ->
                        arr.put(
                            JSONObject().put(
                                "cw",
                                JSONArray().put(JSONObject().put("w", w)),
                            ),
                        )
                    }
                },
            )
        }
        val encoded = Base64.getEncoder().encodeToString(inner.toString().toByteArray(Charsets.UTF_8))
        val result = JSONObject().apply {
            put("encoding", "utf8")
            put("compress", "raw")
            put("format", "json")
            put("seq", 0)
            put("status", status)
            put("text", encoded)
        }
        return JSONObject().apply {
            put(
                "header",
                JSONObject().apply {
                    put("code", 0)
                    put("message", "success")
                    put("sid", "sid-1")
                    put("status", status)
                },
            )
            put("payload", JSONObject().put("result", result))
        }.toString()
    }
}
