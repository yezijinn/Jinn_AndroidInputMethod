package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板分组「提取干净片段」单测（纯 JVM）。
 *
 * 覆盖 2026-09-25 的需求：分组不再按「整条内容属于哪类」筛选，而是从内容里提取该类的片段，
 * 展示与粘贴都用这个片段（`firstUrl` / `firstNumber`）；多标签分类保证「进了某分组
 * ⇒ 一定能提取到片段」。
 */
class ClipboardClassifierExtractTest {

    private val classifier = ClipboardClassifier

    /** 用户给的原始例子：一条内容同时进两个分组，各自只显示自己的片段 */
    @Test
    fun userExampleSplitsIntoBothGroups() {
        val text = "我的电话是123456,你记住了吗?记得访问www.baidu.com"
        assertEquals(
            "既含网址又含数字时应同时带两个标签",
            "URL,NUMBER",
            classifier.classify(text),
        )
        assertEquals("网址分组只显示网址片段", "www.baidu.com", classifier.firstUrl(text))
        assertEquals("数字分组只显示数字片段", "123456", classifier.firstNumber(text))
    }

    // ── 网址片段 ──────────────────────────────────────────

    @Test
    fun urlExtractionStripsTrailingPunctuation() {
        assertEquals("www.baidu.com", classifier.firstUrl("记得访问www.baidu.com。"))
        assertEquals("https://example.com/a", classifier.firstUrl("见 https://example.com/a，谢谢"))
        assertEquals("https://example.com/a", classifier.firstUrl("（https://example.com/a）"))
        assertEquals("example.com/path", classifier.firstUrl("example.com/path。"))
    }

    @Test
    fun urlExtractionCoversAllForms() {
        assertEquals("https://example.com/x", classifier.firstUrl("https://example.com/x"))
        assertEquals("myapp://abc/def", classifier.firstUrl("打开 myapp://abc/def"))
        assertEquals("192.168.1.1", classifier.firstUrl("路由器 192.168.1.1"))
        assertEquals("www.baidu.com", classifier.firstUrl("www.baidu.com"))
    }

    /** 只取最先出现的那个：句子前面的优先，不按规则种类优先级 */
    @Test
    fun urlExtractionTakesEarliestMatch() {
        assertEquals(
            "www.first.com",
            classifier.firstUrl("先看 www.first.com 再看 https://second.com/x"),
        )
    }

    // ── 数字片段 ──────────────────────────────────────────

    @Test
    fun numberExtractionNormalizesForms() {
        // 手机号变体去空格/连字符
        assertEquals("13800138000", classifier.firstNumber("手机号 138-0013-8000"))
        assertEquals("13800138000", classifier.firstNumber("手机号 +86 138 0013 8000"))
        // 金额只留数字（分组名就是「数字」）
        assertEquals("128.50", classifier.firstNumber("¥128.50"))
        assertEquals("99", classifier.firstNumber("价格：$99"))
        // 关键词后只取号码
        assertEquals("1234", classifier.firstNumber("验证码：1234"))
        assertEquals("20260815001", classifier.firstNumber("订单号 20260815001"))
        // 整段短数字
        assertEquals("1234", classifier.firstNumber("1234"))
        // 句中长数字串
        assertEquals("123456", classifier.firstNumber("我的电话是123456，记住了吗"))
        assertEquals("1234567890123456", classifier.firstNumber("卡 1234567890123456 已到账"))
    }

    @Test
    fun numberExtractionTakesEarliestMatch() {
        assertEquals(
            "12345678",
            classifier.firstNumber("先 12345678 再 87654321"),
        )
    }

    /** 负例：含数字但不是「数字片段」的正文不得被提取（避免数字分组被噪音撑满） */
    @Test
    fun shortDigitsInSentenceAreNotExtracted() {
        assertNull("句中 3 位数字不算片段", classifier.firstNumber("我有123个苹果"))
        assertNull("句中 4 位数字无关键词不算片段", classifier.firstNumber("第 1234 章"))
        assertNull("版本号逐段都太短", classifier.firstNumber("版本 2.0.1 发布说明"))
        assertNull("纯文本", classifier.firstNumber("今天天气很好"))
    }

    /** 超过 20 位的连续数字不是片段（与分类器的既有口径一致） */
    @Test
    fun overTwentyDigitsNotExtracted() {
        assertNull(classifier.firstNumber("123456789012345678901"))
    }

    // ── 多标签 ────────────────────────────────────────────

    @Test
    fun singleLabelCases() {
        assertEquals("URL", classifier.classify("https://example.com/no-digits"))
        assertEquals("NUMBER", classifier.classify("13800138000"))
        assertEquals("OTHER", classifier.classify("Hello World"))
        assertEquals("OTHER", classifier.classify(""))
    }

    /** 不变式：分组标签与实际提取结果必须一致（筛选进来一定能显示片段） */
    @Test
    fun labelsAlwaysImplyExtractablePiece() {
        val samples = listOf(
            "我的电话是123456,你记住了吗?记得访问www.baidu.com",
            "https://example.com/order/123456789",
            "手机号 138-0013-8000",
            "验证码：1234",
            "www.baidu.com",
            "192.168.1.1",
            "第1234567章读后感",
            "Hello World",
            "我有123个苹果",
        )
        for (s in samples) {
            val labels = classifier.classify(s)
            if (classifier.hasLabel(labels, ClipboardClassifier.CATEGORY_URL)) {
                assertTrue("带 URL 标签却提取不到网址：$s", classifier.firstUrl(s) != null)
            }
            if (classifier.hasLabel(labels, ClipboardClassifier.CATEGORY_NUMBER)) {
                assertTrue("带 NUMBER 标签却提取不到数字：$s", classifier.firstNumber(s) != null)
            }
            if (labels == ClipboardClassifier.CATEGORY_OTHER) {
                assertNull("判为 OTHER 却提取到网址：$s", classifier.firstUrl(s))
                assertNull("判为 OTHER 却提取到数字：$s", classifier.firstNumber(s))
            }
        }
    }

    @Test
    fun normalizeLabelsFromExternalSource() {
        assertEquals("URL,NUMBER", classifier.normalizeLabels("URL,NUMBER"))
        assertEquals("URL,NUMBER", classifier.normalizeLabels("NUMBER,URL")) // 顺序归一
        assertEquals("URL", classifier.normalizeLabels("URL,URL"))           // 去重
        assertEquals("NUMBER", classifier.normalizeLabels("NUMBER"))
        assertEquals("OTHER", classifier.normalizeLabels("OTHER"))
        assertEquals("OTHER", classifier.normalizeLabels("FAVORITE"))        // 伪分类不得进列
        assertEquals("OTHER", classifier.normalizeLabels("乱写的值"))
        assertEquals("OTHER", classifier.normalizeLabels(null))
    }

    // ── 列表展示 / 粘贴取值 ───────────────────────────────

    @Test
    fun pieceForSelectsByGroup() {
        val text = "我的电话是123456,你记住了吗?记得访问www.baidu.com"
        assertEquals("网址组给网址片段", "www.baidu.com", classifier.pieceFor("URL", text))
        assertEquals("数字组给数字片段", "123456", classifier.pieceFor("NUMBER", text))
        assertEquals("全部分组给原文", text, classifier.pieceFor(null, text))
        assertEquals("收藏分组给原文", text, classifier.pieceFor("FAVORITE", text))
        assertEquals(
            "提取不到时回退原文（不留空白行）",
            "没有片段", classifier.pieceFor("URL", "没有片段"),
        )
    }

    @Test
    fun labelTextIsHumanReadable() {
        assertEquals("网址", classifier.labelText("URL"))
        assertEquals("数字", classifier.labelText("NUMBER"))
        assertEquals("网址·数字", classifier.labelText("URL,NUMBER"))
        assertEquals("OTHER 不显示标签", "", classifier.labelText("OTHER"))
        assertEquals("", classifier.labelText(null))
    }

    // ── 平台语义守卫 ──────────────────────────────────────

    /**
     * 源码守卫：分类器里**不准出现单词边界断言 `\b` / `\B`**。
     *
     * Android 的 `Pattern` 是 ICU 实现（`\b` = Unicode 单词边界，汉字算单词字符），
     * JVM 的是 ASCII 语义 —— 同一个正则在两端行为不同，而单测跑在 JVM 上**永远看不出来**。
     * 2026-09-25 真机踩坑：「我的电话是123456…」在设备上只拿到 URL 标签（数字边界没成立，
     * URL 是裸域名 `baidu.com` 命中的）。边界只能用显式前后瞻（见 `NOT_DIGIT_BEFORE`）。
     */
    @Test
    fun sourceMustNotUseWordBoundary() {
        // 注释里可能引用这两个记号（说明规则），只看代码：剥注释走共用 TestSources.codeOf
        // （BUG.md L-116）—— 旧写法只丢整行注释，行尾注释里引用 `\b` 仍会误红
        // ⚠ 覆盖范围含 `Diagnostics`（脱敏表）/ `TranslationClient`（hex 凭据判据）：它们的
        // `\b` 同样走 Android ICU，CJK 紧贴凭据时漏遮（真机才暴露、JVM 单测永不红，BUG.md L-1374）
        for (name in listOf("ClipboardClassifier.kt", "Diagnostics.kt", "TranslationClient.kt")) {
            val code = TestSources.codeSource(name)
            assertTrue("$name 代码里禁止用单词边界 b", !code.contains("\\b"))
            assertTrue("$name 代码里禁止用单词边界 B", !code.contains("\\B"))
        }
    }

    @Test
    fun hasLabelChecksSingleLabels() {
        assertTrue(classifier.hasLabel("URL,NUMBER", ClipboardClassifier.CATEGORY_URL))
        assertTrue(classifier.hasLabel("URL,NUMBER", ClipboardClassifier.CATEGORY_NUMBER))
        assertTrue(classifier.hasLabel("URL", ClipboardClassifier.CATEGORY_URL))
        assertTrue(!classifier.hasLabel("OTHER", ClipboardClassifier.CATEGORY_URL))
        assertTrue(!classifier.hasLabel(null, ClipboardClassifier.CATEGORY_URL))
    }

    // ── 列表预览截断（2026-10-24，256KB 单行把进程撑爆）──────────

    @Test
    fun 预览未超限时原样返回() {
        val short = "短文本"
        assertTrue(
            "未超限时必须是同一个引用（不做无谓拷贝）",
            short === classifier.previewOf(short),
        )
        val exact = "a".repeat(ClipboardClassifier.PREVIEW_MAX_CODE_POINTS)
        assertEquals("正好等于上限不截", exact, classifier.previewOf(exact))
    }

    /**
     * 截断按**码点**：上限落在 emoji 中间时不许切出孤立高位代理。
     *
     * 真机事故（2026-10-24）：单条 256KB 的文本进列表正文列后，`TextView` 对无断点单行走
     * `minikin::breakLineOptimal`（最优断行），历史页 PSS 70MB → 1,473MB、键盘面板
     * 76MB → 1,298MB，随后被 `lowmemorykiller` 杀掉。改成预览截断后仍需保证不劈代理对，
     * 否则末尾会显成方块（与 L-1426 同一条口径）。
     */
    @Test
    fun 预览截断按码点不劈代理对() {
        val text = "😀".repeat(ClipboardClassifier.PREVIEW_MAX_CODE_POINTS) + "尾"
        val cut = classifier.previewOf(text)
        assertEquals("按码点截到上限（UTF-16 长度翻倍）", 400, cut.length)
        assertTrue("不许以孤立高位代理结尾", !cut.last().isHighSurrogate())
        assertTrue("结尾必须是完整 emoji（低位代理）", cut.last().isLowSurrogate())
        assertEquals("上限 +1 的那个字被截掉", text.dropLast(1), cut)
    }

    @Test
    fun 预览对超长单行只留上限长度() {
        val huge = "a".repeat(256 * 1024)
        assertEquals(ClipboardClassifier.PREVIEW_MAX_CODE_POINTS, classifier.previewOf(huge).length)
        assertEquals("空串原样返回", "", classifier.previewOf(""))
    }

    // ── 内容对话框的分段渲染（2026-10-24，只降断行策略救不了 250KB 单行）──────────

    /**
     * 分段必须**拼接回原文**（内容不丢、不改、不加字符）。
     *
     * 这是「对话框看全文」这条承诺的底线：分段只是渲染手段，用户复制到的东西必须与库里一致。
     * 真机实测（2026-10-24）：250KB 单行即便 `BREAK_STRATEGY_SIMPLE` 也把 PSS 顶到 1,868MB
     * 并被 LMK 杀掉 —— 单 TextView 装全文这条路已经堵死，只能分段。
     */
    @Test
    fun 分段拼接与原文一字不差() {
        val single = "a".repeat(250_000)
        val multi = buildString {
            repeat(2000) { append("第").append(it).append("行内容，带标点。\n") }
        }
        for (text in listOf(single, multi, "短", "")) {
            val segs = classifier.segmentsOf(text)
            assertEquals("拼接必须还原原文（len=${text.length}）", text, segs.joinToString(""))
            assertTrue(
                "每段不得超过上限：${segs.map { it.length }.maxOrNull()}",
                segs.all { it.length <= ClipboardClassifier.SEGMENT_MAX_CHARS },
            )
        }
        // 250KB 单行没有换行可断 ⇒ 硬切成 63 段（每段 4000）
        assertEquals(63, classifier.segmentsOf(single).size)
    }

    /** 未超限的文本（绝大多数条目）必须仍是**一段且同一引用** —— 常见路径零变化、零拷贝 */
    @Test
    fun 短文本不分段也不拷贝() {
        val exact = "a".repeat(ClipboardClassifier.SEGMENT_MAX_CHARS)
        val segs = classifier.segmentsOf(exact)
        assertEquals(1, segs.size)
        assertTrue("正好等于上限时返回同一引用", exact === segs[0])
        assertEquals("空串也给一段", 1, classifier.segmentsOf("").size)
    }

    /**
     * 多行文本的分段断点必须落在换行符**之后**（不把一行劈成两行）。
     *
     * ⚠ 段尾那个 `\n` 是个陷阱：`StaticLayout` 会为它多铺一条空行，而下一段 TextView 自己
     * 也另起一行 —— 直接用 `segment` 当显示文本，每 4000 字就多一条空行。显示文本必须过
     * [ClipboardClassifier.segmentText]（见下一条用例）。
     */
    @Test
    fun 分段断点落在换行之后() {
        val line = "x".repeat(999) + "\n"      // 每行 1000 字符
        val text = line.repeat(10)              // 10000 字符 ⇒ 至少 3 段
        val segs = classifier.segmentsOf(text)
        assertEquals("拼接仍须一字不差", text, segs.joinToString(""))
        assertTrue("段数应大于 1", segs.size > 1)
        for (seg in segs.dropLast(1)) {
            assertEquals("非末段必须以换行收尾", '\n', seg.last())
        }
    }

    /** 显示文本：非末段剥掉段尾**一个**换行（否则每 4000 字多一条空行），内容与末段不动 */
    @Test
    fun 分段显示不多出空行() {
        val text = buildString {
            repeat(2000) { append("第").append(it).append("行，带标点。\n") }
            append('\n')                        // 结尾再补一条空行：末段必须原样保留
        }
        val segs = classifier.segmentsOf(text)
        assertTrue("样本要真的被切开", segs.size > 1)
        for (i in 0 until segs.lastIndex) {
            val shown = classifier.segmentText(segs[i], isLast = false)
            assertTrue("非末段显示文本不许以换行结尾", !shown.endsWith("\n"))
            assertEquals("只剥一个换行，补回去必须与段原文一致", segs[i], shown + "\n")
        }
        val last = segs.last()
        assertEquals("末段原样（结尾的空行属于内容）", last, classifier.segmentText(last, isLast = true))
        // 真空行（`\n\n` 正好断在段边界上）只剥一个，另一个保留
        val blanks = classifier.segmentsOf("x".repeat(3998) + "\n\n" + "y".repeat(1000))
        val shown = classifier.segmentText(blanks.first(), isLast = false)
        assertTrue("第一个换行被剥掉、第二个留下", shown.endsWith("\n") && !shown.endsWith("\n\n"))
        // CRLF：段尾是 `\r\n`，剥完不许把裸 `\r` 留在末尾（真机同步进来的内容就是 CRLF）
        val crlf = classifier.segmentsOf("x".repeat(3998) + "\r\n" + "y".repeat(1000))
        val crlfShown = classifier.segmentText(crlf.first(), isLast = false)
        assertTrue("CRLF 段尾要把 \\r 一并剥掉", !crlfShown.endsWith("\r") && !crlfShown.endsWith("\n"))
    }

    /**
     * 源码守卫：三处列表正文列都必须走预览截断；内容对话框必须**保留全文**。
     *
     * 这条钉死的是「原文直接进 TextView」这个写法本身 —— 单测在 JVM 上量不到 Android 的
     * 最优断行开销（`minikin` 在设备侧），只有源码守卫能在编译期把回退挡住。
     *
     * 判据取**正文列那条赋值语句**（跨行时把下一行接上，再压掉全部空白），不在整文件里找串：
     * 三个视图里各有别的 `holder.content.text =`（图片行占位、内容对话框），整文件找串
     * 只能证明「文件里出现过这个函数名」，钉不住「这条赋值用了什么」。
     */
    @Test
    fun 列表正文列必须走预览截断() {
        fun compact(text: String) = text.replace(Regex("\\s+"), "")

        /** 取某视图里**列表正文列**那条赋值（含续行），并断言它只有一处 */
        fun previewAssignment(name: String): String {
            val lines = TestSources.codeSource(name).lines().map { it.trim() }
            val hits = lines.withIndex().filter { (_, line) ->
                line.startsWith("holder.content.text =") && !line.contains("TEXT_IMAGE_ROW_PLACEHOLDER")
            }
            assertEquals("$name 的列表正文列赋值必须唯一（多一处就无法判定预览规则）", 1, hits.size)
            val (at, head) = hits.single()
            // 跨行赋值：`= ` 收尾时把下一行接上（三处视图里都是这种写法）
            val full = if (head.endsWith("=")) head + " " + lines[at + 1] else head
            return compact(full)
        }

        val history = previewAssignment("ClipboardHistoryActivity.kt")
        assertTrue(
            "历史页正文列必须按命中点开窗、且形态集与结果同代（BUG.md L-1478），当前：$history",
            history.contains("ClipboardSearch.previewFor(item.content,activeForms)"),
        )
        val historySrc = compact(TestSources.codeSource("ClipboardHistoryActivity.kt"))
        assertTrue(
            "历史页正文列不许在 getView 里现算查询形态（每行每次绑定都要归一一遍，且会拿新词裁旧结果）",
            !historySrc.contains("previewFor(item.content,ClipboardSearch.queryForms("),
        )
        assertTrue(
            "历史页结果落地时必须同步预览形态集（少了它 previewFor 恒收空集 ⇒ 永远退回开头窗口）",
            historySrc.contains("activeForms=forms"),
        )
        assertTrue(
            "历史页正文列不许再直接塞原文",
            !historySrc.contains("holder.content.text=item.content"),
        )
        // 内容对话框是唯一的「看全文」入口：截断它等于把用户内容藏起来，比 OOM 更糟
        assertTrue(
            "内容对话框必须按段渲染全文（segmentsOf(item.content)）",
            historySrc.contains("ClipboardClassifier.segmentsOf(item.content)"),
        )
        assertTrue(
            "每段正文来自分段结果，不是原文整份",
            historySrc.contains("text=ClipboardClassifier.segmentText(segment,"),
        )
        assertTrue(
            "段文本必须过 segmentText（直接塞 segment 会在每 4000 字处多一条空行）",
            !historySrc.contains("text=segment"),
        )
        assertTrue(
            "内容对话框不许把全文塞进单个 TextView",
            !historySrc.contains("text=item.content"),
        )
        // 单段仍要降断行策略：4000 字里的一整串无断点西文，最优断行没有收益只有代价
        assertTrue(
            "对话片段必须显式降断行策略",
            historySrc.contains("breakStrategy=android.text.Layout.BREAK_STRATEGY_SIMPLE"),
        )

        val search = previewAssignment("SearchPanelView.kt")
        assertTrue(
            "搜索结果正文列必须按命中点开窗（BUG.md L-1478），当前：$search",
            search.contains("ClipboardSearch.previewFor(item.content,activeForms)"),
        )
        val searchSrc = compact(TestSources.codeSource("SearchPanelView.kt"))
        assertTrue(
            "搜索结果正文列不许再直接塞原文",
            !searchSrc.contains("holder.content.text=item.content"),
        )

        val panel = previewAssignment("ClipboardPanelView.kt")
        assertTrue(
            "键盘面板正文列必须 previewOf（面板不搜索，没有命中点可对齐），当前：$panel",
            panel.contains(
                "ClipboardClassifier.previewOf(" +
                    "ClipboardClassifier.pieceFor(currentCategory,item.content))",
            ),
        )
        val panelSrc = compact(TestSources.codeSource("ClipboardPanelView.kt"))
        assertTrue(
            "面板正文列不许绕过 previewOf 直接塞片段 / 原文",
            !panelSrc.contains("holder.content.text=ClipboardClassifier.pieceFor(currentCategory,item.content)") &&
                !panelSrc.contains("holder.content.text=item.content"),
        )

        // 状态接线：开窗用的是「判出这批命中的那份形态集」，必须与结果**同代**赋值。
        // 少了它，getView 拿到的是空集 ⇒ 永远退回开头窗口，症状与本条缺陷一模一样（而源码守卫照绿）。
        val searchCode = TestSources.codeSource("SearchPanelView.kt")
        val publish = TestSources.blockAfter(searchCode, "fun publish(")
        assertTrue(
            "搜索结果落地时必须同步预览形态集（否则开窗恒为空 ⇒ 永远退回开头窗口）",
            "activeForms = forms" in publish,
        )
        val emptyQuery = TestSources.blockAfter(searchCode, "if (q.isEmpty()) {")
        assertTrue("查询词清空时要复位预览形态集", "activeForms = emptyList()" in emptyQuery)
    }
}
