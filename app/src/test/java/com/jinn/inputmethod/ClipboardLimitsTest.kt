package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板容量两条上限的纯逻辑护栏。
 *
 * 采集侧只拦条数是不够的：单条巨文本（大段代码/日志）能让库无界增长，
 * 因此补两道闸，单条 256KB 直接不入库 + 总量 100MB 按最旧非收藏淘汰。
 * 这里只测纯函数部分（真正的入库/裁剪要 Android SQLite，走真机验证）。
 */
class ClipboardLimitsTest {

    // ── 单条上限 ────────────────────────────────────────────────────────────

    @Test
    fun 单条按UTF8字节判定() {
        val limit = 1024
        assertFalse("空串不超", ClipboardStore.exceedsItemLimit("", limit))
        assertFalse("恰好等于上限不超", ClipboardStore.exceedsItemLimit("a".repeat(limit), limit))
        assertTrue("超 1 字节即超", ClipboardStore.exceedsItemLimit("a".repeat(limit + 1), limit))
    }

    @Test
    fun 多字节字符按字节而非字符计() {
        // 「汉」UTF-8 占 3 字节
        assertFalse(ClipboardStore.exceedsItemLimit("汉".repeat(3), limit = 9))
        assertTrue(ClipboardStore.exceedsItemLimit("汉".repeat(4), limit = 11))
    }

    @Test
    fun 超长串走字符数快筛且结果一致() {
        // 字符数 > 上限时不必再编码：字节数恒 ≥ 字符数，结论必然为「超」
        val huge = "a".repeat(ClipboardStore.MAX_ITEM_BYTES + 1)
        assertTrue(ClipboardStore.exceedsItemLimit(huge))
    }

    @Test
    fun 非正上限视为不限制() {
        assertFalse(ClipboardStore.exceedsItemLimit("任意内容", limit = 0))
        assertFalse(ClipboardStore.exceedsItemLimit("任意内容", limit = -1))
    }

    // ── 总体积预算淘汰 ──────────────────────────────────────────────────────

    private fun row(id: Long, bytes: Long, fav: Boolean = false) = ClipboardRowSize(id, bytes, fav)

    @Test
    fun 总量未超预算时不删任何条目() {
        assertEquals(emptyList<Long>(), ClipboardDb.overflowIdsForByteBudget(listOf(row(1, 100), row(2, 100)), 200))
        assertEquals(emptyList<Long>(), ClipboardDb.overflowIdsForByteBudget(emptyList(), 100))
    }

    @Test
    fun 超预算时从最旧开始删到落回预算内() {
        // 三档按最旧在前：1→2→3
        val rows = listOf(row(1, 100), row(2, 100), row(3, 100))
        // 总量 300，预算 250 → 删最旧的 1（剩 200 ≤ 250）即停，不多删
        assertEquals(listOf(1L), ClipboardDb.overflowIdsForByteBudget(rows, 250))
        // 预算 150 → 需删 1、2（剩 100）
        assertEquals(listOf(1L, 2L), ClipboardDb.overflowIdsForByteBudget(rows, 150))
    }

    @Test
    fun 收藏计入总量但不参与淘汰() {
        // 最旧的 1 是收藏：跳过它，去删后面的非收藏
        val rows = listOf(
            row(1, 300, fav = true),
            row(2, 100),
            row(3, 100),
        )
        // 总量 500，预算 350 → 跳过收藏 1，删 2（剩 400 仍超）→ 再删 3（剩 300 ≤ 350）
        assertEquals(listOf(2L, 3L), ClipboardDb.overflowIdsForByteBudget(rows, 350))
    }

    @Test
    fun 收藏自身超预算时删完非收藏即停手() {
        // 收藏 300 已超预算 250，非收藏仅 10：全删也回不到预算内，只能停手（不删收藏）
        val rows = listOf(row(1, 300, fav = true), row(2, 10))
        assertEquals(listOf(2L), ClipboardDb.overflowIdsForByteBudget(rows, 250))
    }

    @Test
    fun 非正预算视为不限制() {
        assertEquals(emptyList<Long>(), ClipboardDb.overflowIdsForByteBudget(listOf(row(1, 999)), 0))
        assertEquals(emptyList<Long>(), ClipboardDb.overflowIdsForByteBudget(listOf(row(1, 999)), -1))
    }

    @Test
    fun 默认总量预算为100MB() {
        assertEquals(100L * 1024 * 1024, ClipboardDb.DEFAULT_MAX_TOTAL_BYTES)
    }

    // ── 解密窗口内存预算护栏 ────────────────────────────────────────────────
    //
    // 分页/分块查询会把整个窗口逐条解密后才返回，故单次明文峰值 = 窗口条数 × 单条上限。
    // 这组护栏把「窗口 × 单条上限 ≤ 预算」钉住：调大任何一侧都会被拦下。

    @Test
    fun 搜索窗口的最坏内存不超过预算() {
        val peak = ClipboardStore.decryptWindowPeakBytes(SEARCH_WINDOW_ITEMS)
        assertTrue(
            "搜索窗口峰值 ${peak / 1024 / 1024}MB 超预算 ${ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES / 1024 / 1024}MB",
            peak <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES,
        )
    }

    @Test
    fun 面板分页窗口同样在预算内() {
        val peak = ClipboardStore.decryptWindowPeakBytes(PANEL_PAGE_ITEMS)
        assertTrue("面板分页峰值 $peak 超预算", peak <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES)
    }

    @Test
    fun 导出分页窗口同样在预算内() {
        // 配置备份导出剪贴板节的页宽（BUG.md L-102）：原值 200 条 ≈ 131MB，是当时**唯一**
        // 没被窗口守卫覆盖的越界路径（搜索 / 面板分页 / 存量重算三条都有守卫，导出这条漏了）。
        // 页宽即一次解密窗口：本测试只量这个窗口的峰值。
        val peak = ClipboardStore.decryptWindowPeakBytes(ConfigBackupManager.CLIP_PAGE)
        assertTrue(
            "导出分页峰值 ${peak / 1024 / 1024}MB 超预算 ${ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES / 1024 / 1024}MB",
            peak <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES,
        )
    }

    @Test
    fun 存量重算分页窗口同样在预算内() {
        val peak = ClipboardStore.decryptWindowPeakBytes(RECLASSIFY_PAGE)
        assertTrue("存量重算峰值 $peak 超预算", peak <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES)
    }

    @Test
    fun 护栏能拦下旧的300条窗口() {
        // 证明护栏有效：旧值 300 × 256KB ≈ 262MB（含放大），必然越界（若哪天有人调回去，这条会先失败）
        assertTrue(
            "300 条窗口应被判超预算",
            ClipboardStore.decryptWindowPeakBytes(300) > ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES,
        )
    }

    @Test
    fun 护栏能拦下存量重算用过的200条窗口() {
        // 存量重算的分页原值 200 条 ≈ 131MB（含放大）—— 这是唯一越界的解密窗口
        assertTrue(
            "200 条窗口应被判超预算",
            ClipboardStore.decryptWindowPeakBytes(200) > ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES,
        )
    }

    @Test
    fun 峰值估算含密文与String放大而非只算明文() {
        // 只算明文会低估：单条还要算 base64 密文串（≈4/3）与解出的明文串（1.0），实测合计 2.33 倍
        val plainOnly = 10L * ClipboardStore.MAX_ITEM_BYTES
        val estimate = ClipboardStore.decryptWindowPeakBytes(10)
        assertTrue("估算应显著高于纯明文：plain=$plainOnly estimate=$estimate", estimate > plainOnly * 2)
    }

    @Test
    fun 窗口峰值的边界取值() {
        assertEquals(0L, ClipboardStore.decryptWindowPeakBytes(0))
        assertEquals(0L, ClipboardStore.decryptWindowPeakBytes(-5))
        assertEquals(0L, ClipboardStore.decryptWindowPeakBytes(10, maxItemBytes = 0))
    }

    @Test
    fun 填页路径的累计峰值不超过预算() {
        // BUG.md L-91：`fillFirstPage` 的累计上限曾经只由**页数**决定（`limit - 1 + limit`）。
        // 关键场景是「坏行把每页削薄」—— 一页 50 行只有 49 条可解密时，两页就累计 98 条；
        // 若这 98 条都接近单条上限（256KB），峰值 = 98 × 256KB × 2.5 ≈ 61MB > 48MB 预算
        // （页一旦凑满 50 条就停，所以整页健康的数据永远碰不到这个上限）。
        // 现值按**实际明文**限预算（`FIRST_PAGE_MAX_PLAIN_BYTES`）⇒ 第二页就该被拒。
        val big = "汉".repeat(256 * 1024 / 3)              // UTF-8 约 256KB，等于单条上限
        fun pageOf(from: Int) = ClipboardDb.Page(
            // 一页 50 行里 1 行是坏行 ⇒ 只返回 49 条（正好凑不满 limit，于是继续取下一页）
            items = (0 until 49).map {
                ClipboardDb.Item(
                    id = (from + it).toLong(),
                    content = big,
                    contentType = "text",
                    createdAt = (from + it).toLong(),
                    sourcePackage = "com.example",
                    sourceAppName = "示例",
                    contentHash = "h${from + it}",
                )
            },
            nextOffset = from + 50,
        )

        val got = ClipboardDb.fillFirstPage(total = 400, limit = 50) { off, _ -> pageOf(off) }

        val plain = got.items.sumOf { ClipboardStore.utf8ByteSize(it.content) }
        assertTrue(
            "填页累计明文 ${plain / 1024 / 1024}MB 超明文预算 " +
                "${ClipboardStore.decryptWindowPlainBudgetBytes() / 1024 / 1024}MB" +
                "（峰值 = 明文 × 放大系数，越过明文预算就等于越过 ${ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES / 1024 / 1024}MB 预算）",
            plain <= ClipboardStore.decryptWindowPlainBudgetBytes(),
        )
        assertEquals("首屏给出一页（49 条），第二页必须被预算拦下", 49, got.items.size)
        assertEquals("游标停在被拒那页之前 ⇒ 剩下的行由分页 / 续扫照常取到", 50, got.nextOffset)
    }

    // ── 搜索驻留预算的算法 ──────────────────────────────────────────────────
    //
    // 命中集合要一直活到用户改关键词，所以有独立的驻留预算。预算按 UTF-8 字节 算，
    // 而 String.length 是 UTF-16 字符数，中文下 1 字符 = 3 字节，字符数当字节用会把
    // 预算低估到 1/3（200 条顶格中文实际能驻留 ~52MB 而账上只有 17MB）。

    @Test
    fun UTF8字节数与字符数在中英文下不同() {
        assertEquals(3L, ClipboardStore.utf8ByteSize("汉"))
        assertEquals(1L, ClipboardStore.utf8ByteSize("a"))
        assertEquals(0L, ClipboardStore.utf8ByteSize(""))
    }

    @Test
    fun 驻留预算按字节判定时中文不会低估() {
        // 同一批中文内容：按字节判已经触顶，按字符判还差得远，后者正是护栏失效的原因
        val cjk = "汉".repeat(1000)          // 1000 字符 / 3000 字节
        val byBytes = ClipboardStore.utf8ByteSize(cjk)
        assertEquals(3000L, byBytes)
        assertTrue("按字节应触顶", ClipboardStore.searchRetainLimitReached(1, byBytes, maxBytes = 3000))
        assertFalse("按字符数会漏判", ClipboardStore.searchRetainLimitReached(1, cjk.length.toLong(), maxBytes = 3000))
    }

    @Test
    fun 驻留预算条数与字节任一触顶即停() {
        assertFalse(ClipboardStore.searchRetainLimitReached(0, 0))
        assertTrue(ClipboardStore.searchRetainLimitReached(ClipboardStore.MAX_SEARCH_RESULTS, 0))
        assertTrue(ClipboardStore.searchRetainLimitReached(0, ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES))
    }

    /**
     * `BUG.md` L-171：URI 型剪贴板条目必须**边读边判**预算。
     *
     * 原先走 `coerceToText`（AOSP 对 URI 的实现是「打开 provider 的流 → `while (read)` 追加进
     * StringBuilder」，**没有任何长度上限**），而单条上限在写库那一步才判 ⇒ 复制一个大文件会先把
     * 整份内容分配出来、把后台线程 OOM 掉，用户看到的是键盘突然消失。
     */
    @Test
    fun URI型条目必须边读边判预算() {
        fun text(s: String) = java.io.ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))
        fun raw(n: Int) = java.io.ByteArrayInputStream(ByteArray(n) { 'a'.code.toByte() })

        assertEquals("小内容原样读出", "你好", ClipboardStore.readTextWithBudget(text("你好"), 64))
        assertEquals("恰好等于预算 ⇒ 收下", "abcd", ClipboardStore.readTextWithBudget(text("abcd"), 4))
        assertNull("超一个字节就放弃（不返回半截内容）", ClipboardStore.readTextWithBudget(text("abcde"), 4))
        assertNull(
            "默认预算下超限的大块同样放弃",
            ClipboardStore.readTextWithBudget(raw(ClipboardStore.MAX_ITEM_BYTES + 1)),
        )
        assertNull("预算 ≤ 0 ⇒ 直接放弃", ClipboardStore.readTextWithBudget(raw(8), 0))

        // 非阻塞流允许返回 0：连续空读必须有上限，否则后台线程被钉死在一次读取里
        val alwaysZero = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertNull("永远返回 0 的流必须放弃（不是死循环）", ClipboardStore.readTextWithBudget(alwaysZero, 64))

        val src = TestSources.codeSource("ClipboardController.kt")
        val ime = TestSources.codeSource("JinnIme.kt")
        // 正向：URI 型真的走了带预算的读入口，且先看 MIME
        assertTrue("URI 型必须走带预算的读入口", "readBounded(input, deadlineNanos = deadlineNanos)" in src)
        assertTrue("必须先做 MIME 三态判定", "val textual = textualMime(type)" in src)
        assertTrue(
            "采集侧必须调共用取文入口（带原因的结果）",
            "ClipboardStore.itemTextResult(clip.getItemAt(0), appContext)" in src,
        )
        assertTrue("超限必须走 TooLarge 分支并留日志（读入即止后 save 的 W 到不了）",
            "ClipboardStore.ItemText.TooLarge -> {" in src)
        // 反向钉：无上限的整份读取一旦回来就红
        assertFalse(
            "采集侧不得再走无上限的 coerceToText",
            "clip.getItemAt(0).coerceToText(appContext)" in src,
        )
        // 粘贴侧（L-177 的 P0）：必须共用同一入口、带时间预算，且不得再裸调 coerceToText
        assertTrue("粘贴侧必须调共用取文入口（带原因的结果）", "ClipboardStore.itemTextResult(" in ime)
        assertTrue("粘贴侧必须按原因分层", "ClipboardStore.ItemText.TooLarge -> {" in ime)
        assertTrue("「内容过大，未粘贴」必须被保住（L-183：读入即止后不能只剩「剪贴板为空」）",
            "toast(\"内容过大，未粘贴\")" in ime)
        assertTrue("MIME 未知档之外也要过「像不像文本」（L-184）", "if (!looksLikeText(text)) {" in src)
        assertFalse("不得退回「只在未知 MIME 档才检查」",
            "textual == null && !looksLikeText" in src)
        assertTrue("粘贴侧必须带时间预算", "ClipboardStore.PASTE_READ_BUDGET_MS * 1_000_000L" in ime)
        assertFalse("粘贴侧不得再裸调 coerceToText（那是主线程无预算读流）", "coerceToText(this)" in ime)
    }

    @Test
    fun MIME必须三态分明() {
        val starSlashStar = "*" + "/" + "*"   // 直接写字面量会在本文件里被注释扫描器误判，拼出来更安全
        val STAR_SLASH_STAR = starSlashStar
        // 明确文本
        assertTrue("text/plain 是文本", ClipboardStore.textualMime("text/plain") == true)
        assertTrue("text/csv 是文本", ClipboardStore.textualMime("text/csv") == true)
        assertTrue("application/json 是文本", ClipboardStore.textualMime("application/json") == true)
        assertTrue("+json 后缀算文本", ClipboardStore.textualMime("application/vnd.api+json") == true)
        assertTrue("+xml 后缀算文本", ClipboardStore.textualMime("application/atom+xml") == true)
        assertTrue("带参数的 text/* 也算", ClipboardStore.textualMime("text/plain; charset=utf-8") == true)
        // 明确二进制
        assertTrue("图片判为二进制", ClipboardStore.textualMime("image/png") == false)
        assertTrue("视频判为二进制", ClipboardStore.textualMime("video/mp4") == false)
        assertTrue("PDF 判为二进制", ClipboardStore.textualMime("application/pdf") == false)
        assertTrue("字节流判为二进制", ClipboardStore.textualMime("application/octet-stream") == false)
        // 未知（必须与「二进制」分开：provider 常报通配或不给类型）
        assertNull("通配类型是未知（不是二进制）", ClipboardStore.textualMime(STAR_SLASH_STAR))
        assertNull("不给类型是未知", ClipboardStore.textualMime(null))
        assertNull("自定义 application/* 是未知", ClipboardStore.textualMime("application/x-something"))

        // 未知档的兜底：解码结果必须像文本
        assertTrue("正常文本通过", ClipboardStore.looksLikeText("你好，世界 hello"))
        assertFalse("含 NUL 不算文本", ClipboardStore.looksLikeText("abc\u0000def"))
        val binary = String(ByteArray(256) { 0xFF.toByte() }, Charsets.UTF_8)
        assertFalse("整段非法字节（全替换字符）不算文本", ClipboardStore.looksLikeText(binary))
        assertTrue("偶发一个替换字符仍算文本", ClipboardStore.looksLikeText("a".repeat(999) + "\uFFFD"))
    }

    @Test
    fun 粘贴路径的读入必须有时间预算() {
        fun text(s: String) = java.io.ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))
        // 时间预算已经过期 ⇒ 即便有内容也放弃（主线程上「读得少」还不够，还要「读得短」）
        assertNull(
            "过期的时间预算必须放弃",
            ClipboardStore.readTextWithBudget(text("abc"), deadlineNanos = System.nanoTime() - 1),
        )
        assertEquals(
            "未过期的预算不影响正常读取",
            "abc",
            ClipboardStore.readTextWithBudget(text("abc"), deadlineNanos = System.nanoTime() + 60_000_000_000L),
        )
        assertEquals("不传时间预算时行为不变（采集侧在后台线程）", "abc", ClipboardStore.readTextWithBudget(text("abc")))
    }


    /**
     * `BUG.md` L-183：读入失败必须**分层**（先前一律 null ⇒ 调用方只能当成「剪贴板为空」，
     * 粘贴时连一句提示都给不出，采集侧也少了「为什么没入库」）。
     */
    @Test
    fun 读入失败必须分层() {
        fun raw(n: Int) = java.io.ByteArrayInputStream(ByteArray(n) { 'a'.code.toByte() })
        fun text(s: String) = java.io.ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

        assertTrue("恰好等于预算 ⇒ Ok", ClipboardStore.readBounded(raw(4), 4) is ClipboardStore.ItemText.Ok)
        assertTrue(
            "超一字节 ⇒ TooLarge（不是 Ok、也不是截断内容）",
            ClipboardStore.readBounded(raw(5), 4) is ClipboardStore.ItemText.TooLarge,
        )
        assertTrue(
            "时间预算耗尽 ⇒ TimedOut",
            ClipboardStore.readBounded(text("abc"), deadlineNanos = System.nanoTime() - 1)
                is ClipboardStore.ItemText.TimedOut,
        )
        assertTrue("预算非法 ⇒ Failed", ClipboardStore.readBounded(raw(1), 0) is ClipboardStore.ItemText.Failed)

        // 兼容层口径不变：失败仍返回 null（既有调用方与旧用例都依赖它）
        assertEquals("兼容层成功时返回文本", "aaaa", ClipboardStore.readTextWithBudget(raw(4), 4))
        assertNull("兼容层失败时仍为 null", ClipboardStore.readTextWithBudget(raw(5), 4))
    }


    /**
     * `BUG.md` L-173 + L-182：坏行必须能自愈，且加密要在判重之后。
     *
     * 命中 content_hash 后先试解一次：解不开就用新内容重加密覆盖（否则那行永远不可见、还占配额）；
     * 加密本身只服务插入路径 —— 重复内容不该白做一次 AES-GCM + base64。
     */
    @Test
    fun 坏行必须能自愈且加密在判重之后() {
        val src = TestSources.codeSource("ClipboardDb.kt")
        assertTrue("必须有按 id 试解的入口", "private fun decryptsById(id: Long): Boolean" in src)
        assertTrue("命中坏行必须重加密覆盖", "重加密自愈: id=\$existingId" in src)
        assertTrue(
            "自愈必须复用既有行（原地覆盖密文，不插入重复行）",
            "values.put(\"encrypted_content\", reEncrypted)" in src,
        )

        // 只在 upsert 的范围内比较次序（文件里另有一条导入路径也会加密，那条不做判重、不该被算进来）
        val upsert = src.substringAfter("fun upsert(").substringBefore("/** 按内容哈希查")
        val hashAt = upsert.indexOf("stableHash(content)")
        val encAt = upsert.indexOf("ClipboardCrypto.encrypt(content)")
        assertTrue("upsert 里必须能定位到判重与加密两处", hashAt >= 0 && encAt >= 0)
        assertTrue("加密必须在判重之后（L-182）", encAt > hashAt)

        // L-172：解密失败要先截断再删，删除失败要留痕
        val cc = TestSources.codeSource("ConfigCrypto.kt")
        assertTrue("失败路径必须先截断临时件", "runCatching { tmp.outputStream().use { } }" in cc)
        assertTrue("删除失败要留痕", "解密失败后清残件未成功" in cc)
        assertFalse("不得再只调 tmp.delete() 就返回", "tmp.delete()\n            Diagnostics.w(\"ConfigCrypto\", \"解密失败" in cc)
    }

    /**
     * `BUG.md` L-200：命中哈希但那一行已经不存在时，`update` 影响 0 行 —— 不能再把
     * `existingId` 当成功返回（调用方只把 -1 当失败，会以为「已入库」而库里什么都没有）。
     */
    @Test
    fun 命中行消失不得返回幽灵id() {
        val src = TestSources.codeSource("ClipboardDb.kt")
        val upsert = src.substringAfter("fun upsert(").substringBefore("/** 按内容哈希查")

        assertTrue(
            "命中分支必须看 update 的返回行数",
            "val rows = writableDatabase.update(TABLE_ITEMS, values, \"id = ?\"" in upsert,
        )
        assertTrue("写入成功才返回既有 id", "if (rows > 0) {" in upsert)
        assertTrue("行消失必须留痕（不是静默改用插入）", "命中哈希但行已消失" in upsert)
        assertTrue(
            "幽灵路径必须落到插入路径重建这一行",
            "writableDatabase.insert(TABLE_ITEMS, null, values)" in upsert,
        )
        // 反向钉：老写法（不看行数、直接 return existingId）一旦回来就红
        assertFalse(
            "不得无条件返回既有 id（fix 是旧变量名）",
            "writableDatabase.update(TABLE_ITEMS, fix, \"id = ?\"" in upsert,
        )
    }

    /**
     * `BUG.md` L-199：命中哈希是重复复制最常见的路径，试解必须有界 ——
     * 本进程验证过的行不再整行解密；没验证过的仍会真解一次（L-173 的自愈机会不丢）。
     * 读路径发现解不开要撤销备忘，备份恢复后要整批作废（恢复集可能来自别的密钥）。
     */
    @Test
    fun 重复复制不得重复解密() {
        val src = TestSources.codeSource("ClipboardDb.kt")
        val upsert = src.substringAfter("fun upsert(").substringBefore("/** 按内容哈希查")

        assertTrue(
            "试解必须有界：已在备忘里的哈希不再试解",
            "hash !in verifiedReadable && !decryptsById(existingId)" in upsert,
        )
        assertFalse("不得无条件每次试解（旧写法）", "if (!decryptsById(existingId)) {" in upsert)
        assertTrue("写入成功后要登记备忘", "rememberReadable(hash)" in upsert)
        assertTrue("备忘必须有上限", "VERIFIED_MEMO_MAX" in src)
        assertTrue(
            "读路径解不开要撤销备忘（否则白丢一次自愈机会）",
            "forgetReadable(hash)\n            Diagnostics.w(TAG, \"解密失败，跳过 id=\$id\")" in src,
        )
        assertTrue("存量重算解不开也要撤销", "forgetReadable(hash)\n                        continue" in src)
        assertTrue("备份恢复后市进程内的「已验证」结论整批作废", "if (id > 0) verifiedReadable.clear()" in src)
    }

}
