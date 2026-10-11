package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 诊断落盘护栏（[Diagnostics.sanitizeForFile] / [Diagnostics.redactSensitive]）的纯函数用例。
 *
 * 背景：日志正文由调用点自撰，一旦把用户内容（手机号 / 邮箱 / 整篇文本）写进 d/i/w 级日志，
 * 会绕过 V 级闸落盘并随导出诊断包外传 —— 这两道处理是唯一的机械防线，改回去必须变红。
 */
class DiagnosticsSanitizeTest {

    @Test
    fun `手机号只遮中间四位`() {
        assertEquals("联系 138****5678 谢谢", Diagnostics.sanitizeForFile("联系 13812345678 谢谢"))
    }

    @Test
    fun `邮箱只遮本地部分保留域名`() {
        assertEquals("发给 ****@example.com", Diagnostics.sanitizeForFile("发给 user.name+tag@example.com"))
    }

    @Test
    fun `十五位以上纯数字只留前四后二`() {
        assertEquals("卡号 6222****23", Diagnostics.sanitizeForFile("卡号 6222021234567890123"))
        assertEquals("1234****45", Diagnostics.sanitizeForFile("123456789012345"))
    }

    /**
     * `BUG.md` L-169 的后半：脱敏表要认**分隔写法**与**全角 / 阿拉伯-印度数字**。
     *
     * 号码从通讯录、网页、聊天记录里复制时常带分隔符，而本 App 自己是中文输入法 —— 全角数字是
     * 它的默认输出形态。两者都不认时，整条号码原样落盘并随导出诊断包外传。
     */
    @Test
    fun `分隔写法与全角数字的号码同样要遮`() {
        assertEquals("联系 138****5678 谢谢", Diagnostics.sanitizeForFile("联系 138-1234-5678 谢谢"))
        assertEquals("联系 138****5678 谢谢", Diagnostics.sanitizeForFile("联系 138 1234 5678 谢谢"))
        assertEquals("联系 １３８****５６７８ 谢谢", Diagnostics.sanitizeForFile("联系 １３８１２３４５６７８ 谢谢"))
        assertEquals("卡号 ６２２２****２３", Diagnostics.sanitizeForFile("卡号 ６２２２０２１２３４５６７８９０１２３"))
        val arabic = Diagnostics.sanitizeForFile("联系 ١٣٨١٢٣٤٥٦٧٨ 谢谢")
        assertTrue("阿拉伯-印度数字也要遮: $arabic", arabic.contains("****"))
        // 反向：日期与连写手机号不受牵连（分隔符规则不能把 `2026-10-06` 当成号码）
        assertEquals("ts=2026-10-06", Diagnostics.sanitizeForFile("ts=2026-10-06"))
    }

    /**
     * `BUG.md` L-968：分隔符与形态收口的另一半 —— 全角分隔、分组写法、国际前缀。
     *
     * 中文输入法打得出全角数字，也打得出全角分隔；而卡号 / 证件号的常见写法是每 4 位一组，
     * 逐段都不足 15 位、规则够不着。两组都漏时号码原样落盘并随诊断包外传。
     */
    @Test
    fun `全角分隔、分组长串与国际前缀同样要遮`() {
        // 全角空格分隔（全角数字与半角数字各一条）
        assertEquals(
            "联系 １３８****５６７８ 谢谢",
            Diagnostics.sanitizeForFile("联系 １３８　１２３４　５６７８ 谢谢"),
        )
        assertEquals("联系 138****5678 谢谢", Diagnostics.sanitizeForFile("联系 138　1234　5678 谢谢"))
        // 全角连字符
        assertEquals("联系 138****5678 谢谢", Diagnostics.sanitizeForFile("联系 138－1234－5678 谢谢"))
        // 分组写法：每 4 位一组、4 组（留首 4 尾 4）
        assertEquals("卡号 6222****7890", Diagnostics.sanitizeForFile("卡号 6222 0212 3456 7890"))
        assertEquals("卡号 6222****7890", Diagnostics.sanitizeForFile("卡号 6222-0212-3456-7890"))
        // 国际前缀：不认前缀时 `1` 的前一位落在数字上，整条会被边界否掉
        assertEquals("联系 +86****5678", Diagnostics.sanitizeForFile("联系 +8613812345678"))
        assertEquals("联系 861****5678", Diagnostics.sanitizeForFile("联系 8613812345678"))
        // `0086` 前缀 + 11 位 = 15 位：**长数字规则先跑**（L-971 起），所以按长数字串遮（留 `0086`
        // 与末 2 位），不再按手机号留末 4 位 —— 遮得更多，代价是留给用户对照的只剩国码与末两位
        assertEquals("联系 0086****78", Diagnostics.sanitizeForFile("联系 008613812345678"))
        // 反向：3 组（12 位）不算分组长串、日期与时间不受牵连
        assertEquals("分组 1234 5678 9012", Diagnostics.sanitizeForFile("分组 1234 5678 9012"))
        assertEquals("ts=2026-10-06 12:34", Diagnostics.sanitizeForFile("ts=2026-10-06 12:34"))
    }

    /**
     * `BUG.md` L-971：前后边界从「三种数字形态的并集」收成**逐字形**，混排写法不再整条漏遮。
     *
     * 中文输入法用户常在半角 / 全角之间来回切，也常从网页粘来全角数字：`１13812345678` 这种
     * 「全角一位紧贴半角号码」在并集边界下会被前一位的 `１` 整条否掉、原样落盘 —— 比放宽数字类
     * 之前还差（那时 ASCII 边界认不出全角，反而遮了后面那 11 位）。
     *
     * 逐字形边界只看**同形**数字：异形紧贴算两个号，同形连写才算「更长的同一个号」（不被切开）。
     * 同时长数字规则提到手机号规则之前，混排长串整段遮住，不会被 `****` 切碎。
     */
    @Test
    fun `混排字形紧贴的号码同样要遮`() {
        // 全角一位 + 半角 11 位号码：号码遮住，那一位全角数字留着（它不是号码的一部分）
        assertEquals("联系 １138****5678 谢谢", Diagnostics.sanitizeForFile("联系 １13812345678 谢谢"))
        // 反向：半角一位 + 全角 11 位号码
        assertEquals("联系 1１３８****５６７８ 谢谢", Diagnostics.sanitizeForFile("联系 1１３８１２３４５６７８ 谢谢"))
        // 阿拉伯-印度数字紧贴 ASCII 号码
        assertEquals("联系 ١138****5678 谢谢", Diagnostics.sanitizeForFile("联系 ١13812345678 谢谢"))
        // 17 位混排长串（6 位全角 + 11 位半角）：整段遮（留前 4 后 2）。若让手机号规则先跑，
        // 它会认下后面那 11 位、插进 `****` 把整段切开 ⇒ 前 6 位裸奔且长数字规则再也拼不回去
        assertEquals(
            "卡号 １２３４****78",
            Diagnostics.sanitizeForFile("卡号 １２３４５６13812345678"),
        )
        // 同形 12 位：既够不上长数字串、也不该被当成 11 位号码截出来（同形边界仍然挡着）
        assertEquals("联系 138123456789 谢谢", Diagnostics.sanitizeForFile("联系 138123456789 谢谢"))
        // 分隔写法 + 异形紧贴：号码照旧按首尾取留 3 / 4 位（下标切会把分隔符算进去，所以按首尾取）
        assertEquals("联系 １138****5678 谢谢", Diagnostics.sanitizeForFile("联系 １138-1234-5678 谢谢"))
    }

    @Test
    fun `短数字与十六进制串不受影响`() {
        // 13 位时间戳：长度不足 15，且 11 位手机号规则被数字边界挡住
        assertEquals("ts=1730000000000", Diagnostics.sanitizeForFile("ts=1730000000000"))
        val sha = "a".repeat(16) + "0123456789abcdef"
        assertEquals("hash=$sha", Diagnostics.sanitizeForFile("hash=$sha"))
    }

    @Test
    fun `凭据形态被兜底脱敏（sk-、_fx 后缀、Bearer）`() {
        // 2026-09-30 审查：失败响应体、异常 message 一旦被写进日志，这层是最后的兜底
        assertTrue(Diagnostics.redactSensitive("key=sk-proj-Ab12cd34EF56gh78").contains("****"))
        assertTrue(Diagnostics.redactSensitive("k=abcdefghijklmnopqrst:fx").contains("****"))
        assertTrue(Diagnostics.redactSensitive("Authorization: Bearer abcdef1234567890").contains("****"))
        // 凭据串里含手机号形态时也必须**整段**替换：凭据规则排在号码规则之前（2026-10-02 起），
        // 号码规则插进去的 `****` 不会再截断凭据串、让尾部明文漏下（BUG.md L-1071 的复核锚点）
        assertEquals("key=****", Diagnostics.redactSensitive("key=sk-abc13812345678defghijklmnopqr"))
        // 32 位纯 hex 与哈希同形：刻意不脱敏（误伤面大，见 API_KEY_RES 的说明）
        val hex = "aaaaaaaaaaaaaaaa0123456789abcdef"
        assertEquals("hash=$hex", Diagnostics.redactSensitive("hash=$hex"))
    }

    @Test
    fun `落盘时换行与控制字符被替换（防伪造日志行）`() {
        // 日志行以换行分界：用户可控串（Base URL / host / 包内设备名）带换行就能伪造完整假日志行
        val out = Diagnostics.sanitizeForFile("a\nb\r\nc\u0000d")
        assertFalse("不能含裸换行：$out", out.contains('\n'))
        assertTrue("换行应变成可见占位：$out", out.contains("⏎"))
    }

    @Test
    fun `超长正文截断并标注原长`() {
        val out = Diagnostics.sanitizeForFile("x".repeat(600))
        assertTrue("保留前 512 字", out.startsWith("x".repeat(512)))
        assertTrue("标注被截断与原长", out.contains("截断") && out.contains("600"))
        assertTrue("总长明显小于原文", out.length < 560)
    }

    @Test
    fun `常规计数日志原样保留`() {
        val line = "粘贴: len=15 success=true"
        assertEquals(line, Diagnostics.sanitizeForFile(line))
    }

    /**
     * `BUG.md` L-170：日志目录只有年龄闸（7 天）时，崩溃循环能在一天内写出几百 MB
     * —— 这里验「超体积预算时按最旧先删、当天的文件不动」这条纯函数。
     */
    @Test
    fun 日志体积超预算时必须按最旧先删() {
        val keep = "jinn-today.log"
        assertTrue(
            "预算内不删",
            Diagnostics.overBudgetVictims(listOf(Triple("a", 1L, 10L)), 100L, keep).isEmpty(),
        )
        val victims = Diagnostics.overBudgetVictims(
            listOf(
                Triple("new.log", 300L, 60L),
                Triple("old.log", 100L, 60L),
                Triple(keep, 400L, 60L),
            ),
            100L,
            keep,
        )
        // 180 > 100 ⇒ 删最旧的 old.log(60) 后仍 120 > 100 ⇒ 再删 new.log(60)，留下当天的 keep(60)
        assertEquals("最旧先删、删到预算内、当天文件不动", listOf("old.log", "new.log"), victims)
        assertTrue(
            "只剩当天的文件时不动它（那是正在追加的目标）",
            Diagnostics.overBudgetVictims(listOf(Triple(keep, 9L, 999L)), 10L, keep).isEmpty(),
        )
        // 手算一遍：keep(999B, 时间 9) + x(1B, 时间 1)，预算 10B ⇒ 总 1000 ⇒ 先跳过 keep、删最旧的 x
        // ⇒ 剩 999 仍超、但没有更多可删 ⇒ 恰好 listOf("x")（BUG.md L-190：弱断言用 || 并起两个互斥期望
        // 等于没约束；这条改成精确期望）
        assertEquals(
            "没有更多可删时返回已挑出的那些（不返回 keepName）",
            listOf("x"),
            Diagnostics.overBudgetVictims(listOf(Triple(keep, 9L, 999L), Triple("x", 1L, 1L)), 10L, keep),
        )
    }

    /**
     * `BUG.md` L-969：体积预算对**当日文件**是豁免的（年龄闸嫌它新、体积闸当它是保留项），
     * 于是一个跑飞的当日日志能一路长到写满同分区。闸门落在写盘路径上：单文件到顶就滚动一段。
     */
    @Test
    fun 当日日志超上限要滚动并只留最近两段() {
        val day = "2026-10-06"
        val dir = java.nio.file.Files.createTempDirectory("jinn-log").toFile()
        try {
            val current = File(dir, "jinn-$day.log")
            current.writeText("x".repeat(32))
            // 未超上限：不动
            assertEquals(current, Diagnostics.rollDailyLogIfNeeded(dir, day, 64L, 2))
            // 已有 1/2/3 段，超上限 ⇒ 滚成第 4 段，只留编号最大的两段（4、3）
            for (n in 1..3) File(dir, "jinn-$day-$n.log").writeText("old$n")
            assertEquals(current, Diagnostics.rollDailyLogIfNeeded(dir, day, 16L, 2))
            assertEquals("滚出来的段落要接在当日文件后面", 32L, File(dir, "jinn-$day-4.log").length())
            assertTrue("最近一段保留", File(dir, "jinn-$day-3.log").exists())
            assertTrue("更旧的段删掉", !File(dir, "jinn-$day-1.log").exists() && !File(dir, "jinn-$day-2.log").exists())
            // 别的日期与别的前缀都不参与；同日段按编号保大删小
            val names = listOf(
                "jinn-$day-9.log", "jinn-$day-8.log", "jinn-$day.log",
                "jinn-2026-10-05-9.log", "logcat-crash-1.log",
            )
            assertEquals(
                "只挑同日滚动段、按编号保大删小（当日文件与别的日期都不在名单里）",
                listOf("jinn-$day-8.log"),
                Diagnostics.segmentsToDelete(names, day, 1),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * `BUG.md` L-169：logcat 快照是「第二条日志通道」，它的护栏必须与日文件同一句，
     * 且只能取**本进程**的行（root 下 logcat 默认给全系统，而这份快照会进要外传的诊断包）。
     */
    @Test
    fun 快照通道必须脱敏且只取本进程() {
        val src = TestSources.codeSource("Diagnostics.kt")
        assertTrue("快照落盘前必须过同一句脱敏", "val safe = redactSensitive(filtered)" in src)
        assertFalse("不得再直接把未脱敏的快照写盘", "dest.writeText(filtered)" in src)
        assertTrue("抓取必须限定本进程", "--pid=\${Process.myPid()}" in src)
        // BUG.md L-188：按 pid 抓失败时必须有**退路**（退回全量）且失败要留痕；
        // 反向钉：单次抓取 + 静默 null 的老形状不得回来
        // 结构钉（BUG.md L-195）：钉「两次抓取」的形状 —— 改日志措辞不该变红，删掉兜底必须变红
        assertTrue(
            "必须有一次按 pid 抓取",
            "runLogcat(baseArgs + \"--pid=\${Process.myPid()}\", raw, waitMs)" in src,
        )
        assertTrue("必须有一次不带 pid 的抓取（退回）", "runLogcat(baseArgs, raw, waitMs)" in src)
        // 弱钉换成块内顺序断言（BUG.md L-198）：全文件有三处 raw.delete()，只匹配标识符会假绿；
        // 这里要求「退回条件块里：先删残留、再退回抓取」，且与缩进无关。
        assertTrue(
            "退回分支必须先清残留再抓取",
            Regex("""if \(code != 0 \|\| emptyAfterPid\) \{[\s\S]*?raw\.delete\(\)[\s\S]*?runLogcat\(baseArgs, raw, waitMs\)""")
                .containsMatchIn(src),
        )
        // BUG.md L-196：按 pid 成功但为空同样要退回（空文件对排查无用，丢弃前先试一次全量）
        assertTrue("空结果必须并入退回条件", "val emptyAfterPid = code == 0 && (!raw.exists() || raw.length() == 0L)" in src)
        assertTrue("空退回要有自己的日志", "按 pid 抓取为空，退回全量再试一次" in src)
        // BUG.md L-197：来源标记的每一句都要能被实现印证（退回模式下所有 V 行都已剔除）
        assertTrue("标记必须写明 V 级已剔", "V 级已剔" in src)
        assertFalse("不得再写「V 级仅本进程」（与实现不符）", "V 级仅本进程" in src)
        // 退回全量时剔掉其它进程的 V 级行；快照首行自证来源
        assertTrue("退回时必须切换筛选模式", "foreignVerboseToo = fullDevice" in src)
        assertTrue("快照必须自带来源标记", "# snapshot source=\$label" in src)
        assertTrue("两次都失败仍要留日志（文案钉保留为附加断言）", "logcat 快照失败: exit=" in src)
        assertFalse("不得回到「单次抓取 + 静默返回 null」", "val ok = finished && process.exitValue() == 0" in src)
        // BUG.md L-189：清理是尽力而为，但失败不能静默
        assertTrue("体积清理失败必须留痕", "日志体积清理失败" in src)
    }

    /**
     * `BUG.md` L-174（最小部分）：导出诊断包时单个文件读不出来，只该跳过它 ——
     * 原先一个失败就是整包失败、且只返回 null（用户看到「导出失败」却看不到原因）。
     */
    @Test
    fun 导出必须对单文件失败容错() {
        val src = TestSources.codeSource("Diagnostics.kt")
        assertTrue("导出必须先读后写、可跳过", "导出诊断包: 跳过读不出的文件" in src)
        assertFalse("不得再边读边写（失败会留下半截条目）", "f.inputStream().use { it.copyTo(zos) }" in src)
        assertTrue("体积预算必须接在清理路径上", "overBudgetVictims(entries, LOG_DIR_BUDGET_BYTES, nowName)" in src)
    }

    /**
     * `BUG.md` L-174：清理、快照、导出碰的是同一批文件，必须共用一把目录锁。
     *
     * 清理走 tryLock：它在写日志的路径上被调用，等一次十秒级的抓取会把业务调用一起拖住；
     * 拿不到锁就跳过这一轮，并做一次短退避（BUG.md L-972①）。快照与导出经 `withDirLockBusy`
     * 进锁：除了共用这把锁，还要挂上「目录正忙」标志让清理整轮跳过（重入锁挡不住自己人，L-972②）。
     */
    @Test
    fun 清理与打包共用目录锁() {
        val src = TestSources.codeSource("Diagnostics.kt")
        assertTrue("清理必须试锁", "if (!dirLock.tryLock())" in src)
        assertTrue("拿不到锁要留痕", "日志清理跳过" in src)
        assertFalse("不得再各用一把锁", "private val snapshotLock" in src)
        assertTrue("快照走共用锁 + 挂忙标志", "withDirLockBusy { dumpLogcatLocked(suffix, waitMs) }" in src)
        assertTrue("导出同样走共用锁 + 挂忙标志", "withDirLockBusy { exportBundleLocked(context) }" in src)
        assertTrue("忙标志必须由共用锁那层统一置位", "private inline fun <T> withDirLockBusy(" in src)
    }

    /**
     * `BUG.md` L-170：体积预算定点在 **100MB**，而打包侧对单个文件另有内存上限 ——
     * 预算放大之后，一个跑飞的当日日志能在整份读入时把进程 OOM 掉（比丢一段日志更糟）。
     */
    @Test
    fun 体积预算与打包单文件上限() {
        val src = TestSources.codeSource("Diagnostics.kt")
        assertTrue("预算为 100MB", "val LOG_DIR_BUDGET_BYTES = 100L * 1024 * 1024" in src)
        assertTrue("超大文件只打末段", "BUNDLE_INMEM_MAX_BYTES" in src && "readForBundle(f)" in src)
        assertFalse("不得把整份超大文件读进内存", "val bytes = runCatching { f.readBytes() }" in src)
    }

}
