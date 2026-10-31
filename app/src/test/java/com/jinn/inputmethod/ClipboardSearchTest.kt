package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板搜索的纯匹配层护栏（BUG.md L-1134 / L-1135 / L-984）。
 *
 * 覆盖三件事：查询词的等价形态（全半角 / 零宽 / 词内空白）、行内容的命中判据（含只在必要时才做的
 * 折叠兜底）、以及收尾的截断状态（命中截断 vs 扫描止损）。
 *
 * 不覆盖：真正的取库与分块扫描（要 Android SQLite，走真机验证）、视图上的提示行渲染。
 */
class ClipboardSearchTest {

    // ── 查询词的等价形态 ────────────────────────────────────────────────────

    @Test
    fun 普通查询只有原形且折成小写() {
        assertEquals(listOf("hello"), ClipboardSearch.queryForms("Hello"))
        // 多词查询：原形之外还给「去掉分隔符」的一种写法（库里存的常常是没空格的那种形态）
        assertEquals(listOf("hi there", "hithere"), ClipboardSearch.queryForms("hi there"))
    }

    @Test
    fun 全角标点会多出一种半角形态() {
        val forms = ClipboardSearch.queryForms("你好，世界")
        assertTrue("原形在", "你好，世界" in forms)
        assertTrue("半角形态在：符号层打的全角逗号要能搜到库里存半角的条目", "你好,世界" in forms)
    }

    @Test
    fun 查询词里的零宽字符会被剥掉() {
        // 从网页 / 聊天记录粘进来的查询词可能夹着零宽空格与 BOM
        val forms = ClipboardSearch.queryForms("关键\u200B词\uFEFF")
        assertTrue("剥零宽后的形态在", "关键词" in forms)
    }

    @Test
    fun 表意空格与普通空格互认() {
        val forms = ClipboardSearch.queryForms("a\u3000b")
        assertTrue("全角空格叠出的普通空格形态在", "a b" in forms)
        assertTrue("去掉分隔符的形态也在", "ab" in forms)
    }

    @Test
    fun 纯空白查询没有任何形态() {
        // contains("") 恒真：空形态一旦留下，空白查询会命中整个库
        assertTrue(ClipboardSearch.queryForms("").isEmpty())
        assertTrue(ClipboardSearch.queryForms("   ").isEmpty())
        assertTrue(ClipboardSearch.queryForms("\u200B").isEmpty())
    }

    // ── 命中判据 ────────────────────────────────────────────────────────────

    @Test
    fun 原形命中与大小写不敏感() {
        assertTrue(ClipboardSearch.matches("你好世界", ClipboardSearch.queryForms("你好")))
        assertTrue(ClipboardSearch.matches("Hello World", ClipboardSearch.queryForms("hello world")))
    }

    @Test
    fun 全角查询命中半角内容() {
        assertTrue(ClipboardSearch.matches("1,234,567", ClipboardSearch.queryForms("1，234")))
    }

    @Test
    fun 半角查询命中全角内容() {
        // 反向：内容里存的是全角，查询打的是半角 —— 靠内容的折叠兜底
        assertTrue(ClipboardSearch.matches("１，２３４", ClipboardSearch.queryForms("1,234")))
    }

    @Test
    fun 含零宽的查询与内容都能互认() {
        assertTrue("查询带零宽", ClipboardSearch.matches("关键词", ClipboardSearch.queryForms("关键\u200B词")))
        assertTrue("内容带零宽", ClipboardSearch.matches("关键\uFEFF词", ClipboardSearch.queryForms("关键词")))
    }

    @Test
    fun 不匹配时不误报() {
        assertFalse(ClipboardSearch.matches("abc", ClipboardSearch.queryForms("abd")))
        // 内容里含全角字符（会走到折叠兜底），但折叠后仍不匹配
        assertFalse(ClipboardSearch.matches("１２３４５", ClipboardSearch.queryForms("99")))
    }

    @Test
    fun 折叠判据覆盖C类Z类与兼容字符() {
        // 旧判据只认全角 ASCII / 3000 / A0 / AD / Cf，下列内容明明库里有却搜不到
        assertTrue("Zs（EM SPACE）", ClipboardSearch.matches("a\u2003b", ClipboardSearch.queryForms("ab")))
        assertTrue("Cc（BEL）", ClipboardSearch.matches("a\u0007b", ClipboardSearch.queryForms("ab")))
        assertTrue("Zl（行分隔符）", ClipboardSearch.matches("a\u2028b", ClipboardSearch.queryForms("ab")))
        assertTrue("NFKC 兼容字符 ①", ClipboardSearch.matches("①", ClipboardSearch.queryForms("1")))
        assertTrue("NFKC 兼容字符 Ⅰ", ClipboardSearch.matches("Ⅰ", ClipboardSearch.queryForms("i")))
        assertTrue("半角片假名", ClipboardSearch.matches("ｱｲｳ", ClipboardSearch.queryForms("アイウ")))
    }

    @Test
    fun 空格不当折叠信号() {
        // ASCII 空格是 Z 类（clean 会删），若当判据每次未命中都要白跑折叠
        assertFalse(ClipboardSearch.matches("a b", ClipboardSearch.queryForms("ab")))
        assertTrue(ClipboardSearch.matches("a b", ClipboardSearch.queryForms("a b")))
    }

    @Test
    fun 判据为假时折叠与原串恒等() {
        val samples = listOf("abc", "你好世界", "Hello,世界", "a-b_c", "１２３", "①", "a\u2003b")
        for (s in samples) {
            if (' ' in s || ClipboardSearch.foldWorthy(s)) continue
            assertEquals(
                "判据说不用折叠，折叠结果就必须与原串相同：${s.map { it.code }}",
                s,
                java.text.Normalizer.normalize(FavoriteSymbols.clean(s), java.text.Normalizer.Form.NFKC),
            )
        }
    }

    @Test
    fun 空形态集永不命中() {
        assertFalse(ClipboardSearch.matches("任意内容", emptyList()))
    }

    // ── 收尾的截断状态 ──────────────────────────────────────────────────────

    @Test
    fun 扫完且没触顶时不算截断() {
        assertEquals(
            ClipboardSearch.Cap.NONE,
            ClipboardSearch.capState(retainReached = false, exhausted = true, dbTotal = 500, limit = 20_000),
        )
        // 库刚好等于上限：这些行全扫过了，不算「更旧的没查」
        assertEquals(
            ClipboardSearch.Cap.NONE,
            ClipboardSearch.capState(retainReached = false, exhausted = true, dbTotal = 20_000, limit = 20_000),
        )
    }

    @Test
    fun 库超上限且被掐停时是扫描止损() {
        assertEquals(
            ClipboardSearch.Cap.SCAN,
            ClipboardSearch.capState(retainReached = false, exhausted = false, dbTotal = 50_000, limit = 20_000),
        )
        // 库比上限大，但这一轮扫到没数据才停（库在扫描期间被清空一类）⇒ 不算止损
        assertEquals(
            ClipboardSearch.Cap.NONE,
            ClipboardSearch.capState(retainReached = false, exhausted = true, dbTotal = 50_000, limit = 20_000),
        )
    }

    @Test
    fun 命中截断优先于扫描止损() {
        assertEquals(
            ClipboardSearch.Cap.RETAIN,
            ClipboardSearch.capState(retainReached = true, exhausted = false, dbTotal = 50_000, limit = 20_000),
        )
        assertEquals(
            ClipboardSearch.Cap.RETAIN,
            ClipboardSearch.capState(retainReached = true, exhausted = true, dbTotal = 10, limit = 20_000),
        )
    }

    // ── 命中点开窗的预览（BUG.md L-1478：搜到了却看不见）────────────────────

    /** 长文本 + 命中点，构造「命中落在开头 200 码点之外」的样本 */
    private fun longTextHitAt(offset: Int, hit: String = "关键词"): String =
        "a".repeat(offset) + hit + "b".repeat(2000)

    @Test
    fun 命中在开头窗口外时窗口从命中点起() {
        val content = longTextHitAt(5_000)
        val preview = ClipboardSearch.previewFor(content, ClipboardSearch.queryForms("关键词"))
        assertTrue("命中词必须在行内：当前预览「${preview.take(20)}…」", preview.startsWith("关键词"))
        assertTrue(
            "预览不得超过上限（一个码点一个字，长度即码点数）",
            preview.length <= ClipboardClassifier.PREVIEW_MAX_CODE_POINTS,
        )
        // 行尾是紧随命中点的正文，不是「a 一段加省略」：窗口长度顶满上限
        assertEquals(ClipboardClassifier.PREVIEW_MAX_CODE_POINTS, preview.length)
        assertEquals("窗口必须从原文第 5000 个字符处开始", 5_000, content.indexOf(preview))
    }

    @Test
    fun 命中在可见行之外时窗口从命中点起() {
        // 未超限：整份都是预览，开窗无从谈起（同一引用，零拷贝）
        val short = "短内容关键词"
        assertTrue(
            "未超限时 previewFor 与 previewOf 同路",
            short === ClipboardSearch.previewFor(short, ClipboardSearch.queryForms("关键词")),
        )
        // ⚠ 判据不是「命中点在 200 码点内」：正文列只显示 1 行（键盘面板）或 4 行（历史页），
        // 一屏几十个字的东西，命中落在第 3、第 50 个字符同样在窗外 ⇒ 也必须挪
        for (offset in listOf(3, 50)) {
            val content = longTextHitAt(offset)
            val preview = ClipboardSearch.previewFor(content, ClipboardSearch.queryForms("关键词"))
            assertTrue(
                "命中点在第 $offset 个字符：行内装不下那么多字，命中词必须挪到行首（200 码点不是可见宽度）",
                preview.startsWith("关键词"),
            )
            assertEquals("窗口必须从命中点起", offset, content.indexOf(preview))
        }
    }

    @Test
    fun 命中大小写不敏感且偏移对齐原文() {
        val content = "x".repeat(3_000) + "HelloWorld" + "y".repeat(500)
        val preview = ClipboardSearch.previewFor(content, ClipboardSearch.queryForms("helloworld"))
        assertTrue(
            "查询词小写也要在原文上定位（偏移必须落在原文，不能落在 lowercase 副本上）",
            preview.startsWith("HelloWorld"),
        )
    }

    @Test
    fun 没有任何形态命中时退回开头窗口() {
        val content = "b".repeat(3_000)
        assertEquals(
            "未命中（正常不会走到这里，防御用）时退回开头窗口",
            ClipboardClassifier.previewOf(content),
            ClipboardSearch.previewFor(content, ClipboardSearch.queryForms("关键词")),
        )
        assertEquals("形态集为空（无查询词）时退回开头窗口", ClipboardClassifier.previewOf(content),
            ClipboardSearch.previewFor(content, emptyList()))
    }

    @Test
    fun 命中被窗口边界切开时也要整段可见() {
        // 命中词从第 195 码点起、长 10 ⇒ 开头窗口只装得下前 5 个字符，会显示成半截
        val content = "a".repeat(195) + "关键词关键词" + "b".repeat(500)
        val preview = ClipboardSearch.previewFor(content, ClipboardSearch.queryForms("关键词关键词"))
        assertTrue("跨边界的那半截必须整段补出来：当前「${preview.take(10)}…」", preview.startsWith("关键词关键词"))
        assertEquals("窗口仍从命中点起", 195, content.indexOf(preview))
    }

    @Test
    fun 只靠折叠命中的条目仍退回开头窗口() {
        // 库里存全角、查询是半角：matches 为真，但折叠形态在原文里 indexOf 不到
        val content = "c".repeat(3_000) + "ＡＢＣ" + "d".repeat(100)
        val forms = ClipboardSearch.queryForms("abc")
        assertTrue("前置：这条确实算命中", ClipboardSearch.matches(content, forms))
        assertEquals(
            "折叠命中的偏移与原文对不上 ⇒ 保持原窗口（乱挪窗口比看不见更糟）",
            ClipboardClassifier.previewOf(content),
            ClipboardSearch.previewFor(content, forms),
        )
    }

    @Test
    fun 开窗起点落在代理对后半时退一格() {
        val content = "a".repeat(3_000) + "😀" + "z".repeat(500)
        val lowSurrogate = content.indexOf('\uDE00')
        assertTrue("样本里必须有低代理项", lowSurrogate > 0)
        val preview = ClipboardClassifier.previewFrom(content, lowSurrogate)
        assertTrue("起点不许落在代理对中间（会切出孤立代理项）", !preview.first().isLowSurrogate())
        assertTrue("退一格后从整个 emoji 起", preview.startsWith("😀"))
        // 末尾同理：按码点收口，不劈出孤立高位代理
        val tail = ClipboardClassifier.previewFrom("a".repeat(3_000) + "😀".repeat(300), 3_000)
        assertTrue("末尾不得以孤立高位代理收尾", !tail.last().isHighSurrogate())
        assertEquals(
            "控制台口径：200 个 emoji = 200 码点 = 400 码元",
            ClipboardClassifier.PREVIEW_MAX_CODE_POINTS * 2,
            tail.length,
        )
    }
}
