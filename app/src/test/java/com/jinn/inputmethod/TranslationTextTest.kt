package com.jinn.inputmethod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译原文提取与译文追加的纯逻辑守卫（2026-09-30 改版：范围模式 + 按字节从头部截断）。
 *
 * 这些规则直接决定「发出去的是什么」与「用户输入框里最后变成什么」：取错范围 =
 * 把不该发的正文发了出去，截错 = 发的内容与屏幕上的不是一回事，追加错 = 原文被改或凭空多空行。
 * 它们都在 IME 主线程之外的路径上，真机肉眼很难逐个覆盖。
 */
class TranslationTextTest {

    // ── 四种「哪些算原文」的范围 ────────────────────────────────

    @Test
    fun `光标前本行：只取本行光标之前的部分，上下行都不算`() {
        val before = "第一行\n第二行内容"
        assertEquals(
            "第二行内容",
            TranslationText.extract(before, "第三行", TranslationScope.LINE_BEFORE, 10_000).text,
        )
    }

    @Test
    fun `光标所在整行：光标前后都要，且停在行尾不越到下一行`() {
        val slice = TranslationText.extract(
            before = "第一行\n第二行的前半",
            after = "后半\n第三行",
            scope = TranslationScope.LINE_FULL,
            maxBytes = 10_000,
        )
        assertEquals("第二行的前半后半", slice.text)
        // 追加位置 = 光标后那半行的长度（译文落在整行末尾，不会插进行中间）
        assertEquals("后半".length, slice.appendOffset)
    }

    @Test
    fun `光标前全部：不分行，把光标前的内容整段拿来`() {
        assertEquals(
            "第一行\n第二行\n第三行",
            TranslationText.extract("第一行\n第二行\n第三行", "", TranslationScope.BEFORE_ALL, 10_000).text,
        )
    }

    @Test
    fun `编辑框全部：光标前后都算，追加位置指向整篇末尾`() {
        val slice = TranslationText.extract("A\nB", "C\nD", TranslationScope.ALL, 10_000)
        assertEquals("A\nBC\nD", slice.text)
        assertEquals("C\nD".length, slice.appendOffset)
    }

    @Test
    fun `没有换行的输入框里前三种范围取到同一段（聊天框-搜索框场景）`() {
        // 这是这套设计的已知后果：切分单位只有「行」，没有换行时「本行」就等于「全部」
        val text = "今天天气不错我们去公园吧"
        for (scope in listOf(
            TranslationScope.LINE_BEFORE,
            TranslationScope.LINE_FULL,
            TranslationScope.BEFORE_ALL,
        )) {
            assertEquals(scope.id, text, TranslationText.extract(text, "", scope, 10_000).text)
        }
    }

    @Test
    fun `光标后取不到（宿主不支持）时整行模式只翻光标前那半行`() {
        val slice = TranslationText.extract("第一行\n本行前半", "", TranslationScope.LINE_FULL, 10_000)
        assertEquals("本行前半", slice.text)
        assertEquals(0, slice.appendOffset)
    }

    @Test
    fun `区域里没有可翻的文字时返回空串（由上层提示）`() {
        assertEquals("", TranslationText.extract("第一行\n", "", TranslationScope.LINE_BEFORE, 10_000).text)
        assertEquals("", TranslationText.extract("", "", TranslationScope.ALL, 10_000).text)
        assertEquals("", TranslationText.extract("   ", "", TranslationScope.BEFORE_ALL, 10_000).text)
    }

    @Test
    fun `两端空白被裁掉（上传无意义的空白只浪费字节）`() {
        assertEquals("你好", TranslationText.extract("  你好  ", "", TranslationScope.BEFORE_ALL, 10_000).text)
    }

    // ── 单次字节上限：超出时从前面取、舍弃后面的（用户 2026-09-30 定的统一规则）──

    @Test
    fun `takeHeadBytes 的字节口径：ASCII 1 - 汉字 3 - emoji 4`() {
        assertEquals("ab" to false, TranslationText.takeHeadBytes("ab", 2))
        assertEquals("a" to true, TranslationText.takeHeadBytes("ab", 1))
        assertEquals("甲" to false, TranslationText.takeHeadBytes("甲", 3))
        assertEquals("" to true, TranslationText.takeHeadBytes("甲", 2))
        assertEquals("🙂" to false, TranslationText.takeHeadBytes("🙂", 4))
        assertEquals("" to true, TranslationText.takeHeadBytes("🙂", 3))
        assertEquals("" to false, TranslationText.takeHeadBytes("", 10))
    }

    @Test
    fun `maxBytes 非正时一律空串，绝不退化成「不限长」`() {
        // 导入的备份可以是任意数值：0 若不拦住就会变成「整篇都发」
        assertEquals("" to true, TranslationText.takeHeadBytes("abc", 0))
        assertEquals("" to true, TranslationText.takeHeadBytes("abc", -5))
        assertEquals("" to false, TranslationText.takeHeadBytes("", 0))
    }

    @Test
    fun `超出上限时保留前面、丢弃后面`() {
        val slice = TranslationText.extract("甲乙丙", "", TranslationScope.BEFORE_ALL, 6)
        assertEquals("甲乙", slice.text)
        assertTrue("应标记已截断", slice.truncated)
    }

    @Test
    fun `未超限时不标记截断`() {
        val slice = TranslationText.extract("甲乙", "", TranslationScope.BEFORE_ALL, 6)
        assertEquals("甲乙", slice.text)
        assertFalse(slice.truncated)
    }

    @Test
    fun `范围提取的结果同样受字节上限约束`() {
        // 「本行」是 bbbb（8 字节），上限 2 → 只留 bb
        val slice = TranslationText.extract("aaaa\nbbbb", "", TranslationScope.LINE_BEFORE, 2)
        assertEquals("bb", slice.text)
        assertTrue(slice.truncated)
    }

    @Test
    fun `截断只影响上传正文，追加位置仍指向原文区间末尾`() {
        // 上限 6 字节 = 2 个汉字：正文被截成「甲甲」，但追加位置仍是光标后那 2 个单元处
        val slice = TranslationText.extract("甲甲甲", "乙乙", TranslationScope.ALL, 6)
        assertEquals("甲甲", slice.text)
        assertTrue("应标记已截断", slice.truncated)
        assertEquals(2, slice.appendOffset)
    }

    @Test
    fun `任意字节上限下都不会留下孤立代理（emoji 不被切碎）`() {
        // 逐字节上限扫一遍：这是最容易写错的地方（先编码再切字节必然踩）
        val body = "甲".repeat(10) + "🙂🙂🙂"
        for (max in 1..body.toByteArray(Charsets.UTF_8).size) {
            val cut = TranslationText.takeHeadBytes(body, max).first
            for (i in cut.indices) {
                if (cut[i].isLowSurrogate()) {
                    assertTrue(
                        "低位代理必须紧跟高位代理（maxBytes=$max, i=$i）",
                        i > 0 && cut[i - 1].isHighSurrogate(),
                    )
                }
            }
        }
    }

    // ── 读取窗口被截断时的位置确定性（L-213 回归守卫）────────────

    @Test
    fun `读取窗口被截断时，整行与整篇的追加位置标记为不确定`() {
        // 窗口读满且窗口内没有换行 ⇒ 本行可能还没结束，appendOffset 不可信
        val line = TranslationText.extract("", "abcdefg", TranslationScope.LINE_FULL, 100, afterTruncated = true)
        assertEquals(7, line.appendOffset)
        assertFalse("行尾不可确认时必须标记不确定", line.appendOffsetExact)

        val all = TranslationText.extract("", "abcdefg", TranslationScope.ALL, 100, afterTruncated = true)
        assertFalse(all.appendOffsetExact)

        // 窗口内出现换行 ⇒ 行尾位置确定
        val withNl = TranslationText.extract("", "abc\ndef", TranslationScope.LINE_FULL, 100, afterTruncated = true)
        assertTrue(withNl.appendOffsetExact)
        assertEquals(3, withNl.appendOffset)

        // 没读满 ⇒ 都确定
        assertTrue(TranslationText.extract("", "abc", TranslationScope.ALL, 100, false).appendOffsetExact)
    }

    @Test
    fun `光标处追加的两种范围永远算确定位置`() {
        for (scope in listOf(TranslationScope.LINE_BEFORE, TranslationScope.BEFORE_ALL)) {
            val slice = TranslationText.extract("abc", "def", scope, 100, afterTruncated = true)
            assertEquals(scope.id, 0, slice.appendOffset)
            assertTrue(scope.id, slice.appendOffsetExact)
        }
    }

    // ── 范围模式与默认上限的取值归一 ────────────────────────────

    @Test
    fun `范围模式的归一：未知取值回落默认`() {
        assertEquals(TranslationScope.DEFAULT, TranslationScope.of(null))
        assertEquals(TranslationScope.DEFAULT, TranslationScope.of("不存在的范围"))
        for (scope in TranslationScope.entries) assertEquals(scope, TranslationScope.of(scope.id))
    }

    @Test
    fun `每种范围模式都有可展示的文案`() {
        for (scope in TranslationScope.entries) {
            assertTrue("${scope.id} 缺标签", scope.label.isNotBlank())
            assertTrue("${scope.id} 缺说明", scope.detail.isNotBlank())
        }
    }

    @Test
    fun `每家的默认字节上限都在可填范围内且为正`() {
        for (id in TranslationProviderId.entries) {
            val bytes = id.defaultMaxBytes
            assertTrue("${id.id} 默认上限 $bytes 应为正", bytes > 0)
            assertTrue(
                "${id.id} 默认上限 $bytes 越出可填范围",
                bytes in TranslationText.MIN_MAX_BYTES..TranslationText.MAX_MAX_BYTES,
            )
        }
    }

    @Test
    fun `已查到官方出处的默认值被钉住（改这里要同步设置页的说明文案）`() {
        // 出处：阿里云 TranslateGeneral「字符长度上限是 5000 字符」、Azure service limits
        // 「50,000 characters per request」、百度通用「单次 6000 字节」、DeepL「128 KiB 请求体」
        assertEquals(5_000, TranslationProviderId.ALIYUN.defaultMaxBytes)
        assertEquals(50_000, TranslationProviderId.AZURE.defaultMaxBytes)
        assertEquals(6_000, TranslationProviderId.BAIDU.defaultMaxBytes)
        // DeepL 官方给 131072 字节，但本机单次读取窗口只有 MAX_MAX_BYTES（100000）—— 填官方值也
        // 发不出去（读不回来的文本不会被上传），故按本机上界填，设置页说明同步（2026-10-01 L-223）
        assertEquals(TranslationText.MAX_MAX_BYTES, TranslationProviderId.DEEPL.defaultMaxBytes)
    }

    // ── 提交前的快照判据（2026-09-30 用户实测缺陷的回归守卫）────

    @Test
    fun `提交前的快照判据必须是「原始读数逐字相等」`() {
        val src = TestSources.codeSource("JinnIme.kt")
        assertTrue(
            "判据应为读取时刻的原始文本严格相等",
            "beforeNow != snapshot.before" in src,
        )
        assertTrue(
            "「提交时读不到」必须单独分支（不能混进「输入已变化」，2026-10-01 修复 L-240）",
            "TEXT_TRANSLATE_BEFORE_UNREADABLE" in src,
        )
        assertFalse("不得再拿截取结果做 endsWith（会被 trim 与字节截断误伤）", "endsWith(source)" in src)
        assertTrue(
            "提交时要传读取时刻的原始快照",
            "appendTranslation(connection, snapshot, outcome.text)" in src,
        )
    }

    @Test
    fun `整理行-整篇模式的快照还要比光标后的文本`() {
        val src = TestSources.codeSource("JinnIme.kt")
        assertTrue(
            "光标后文本变化同样要丢弃（整行/整篇的原文含着它）",
            "afterNow != snapshot.after" in src,
        )
        assertTrue(
            "「提交时读不到」也要单独分支，不算「输入已变化」（2026-10-01 修复 L-242）",
            "TEXT_TRANSLATE_AFTER_UNREADABLE" in src,
        )
    }

    // ── 可见内容与凭据清洗（2026-09-30 第二轮审查）────────────

    @Test
    fun `零宽字符不算可翻译内容`() {
        // isBlank() 对 ZWSP / BOM / NBSP 都返回 false：一个不可见字符会被当成「有内容」
        // ⇒ 发出一次真实（计费）请求，模型还可能编造整句译文
        assertFalse("\u200B".hasVisibleContent())
        assertFalse("\u00A0\u200B\uFEFF".hasVisibleContent())
        // 非断行空格族（2026-10-02 修复）：JDK 的 `Character.isWhitespace(int)` **明确排除**
        // 这三个码位，只问它会让「一串窄 NBSP」被判成有内容 ⇒ 发一次真实计费请求。
        // U+00A0 另有 ZERO_WIDTH 兜住，U+2007 / U+202F 靠 `isSpaceChar` 那一半。
        assertFalse("\u2007".hasVisibleContent())
        assertFalse("\u202F".hasVisibleContent())
        assertFalse("\u2007\u202F\u2007".hasVisibleContent())
        assertFalse("   \n\t".hasVisibleContent())
        assertTrue("好".hasVisibleContent())
        assertTrue("a\u200B".hasVisibleContent())
    }

    @Test
    fun `凭据清洗：剥掉不可见字符再 trim，内部空格保留`() {
        assertEquals("sk-abc", "sk-abc\u00A0".cleanCredential())
        assertEquals("abc:fx", "  abc:fx\u200B  ".cleanCredential())
        assertEquals("", "\u00A0\u200B".cleanCredential())
        assertEquals("a b", "a b".cleanCredential())
    }

    // ── 追加译文 ────────────────────────────────────────────

    @Test
    fun `追加 = 前导换行 + 译文（原文一个字不动）`() {
        val submit = TranslationText.appendText('？', "How are you today?")
        assertEquals("\nHow are you today?", submit)
        // 拼起来必须等于「原文 + 换行 + 译文」：这正是用户验收的现象
        assertEquals("你好，今天怎么样？\nHow are you today?", "你好，今天怎么样？" + submit)
    }

    @Test
    fun `插入点之前已是换行或没有前文时不补空行`() {
        assertEquals("Hello", TranslationText.appendText('\n', "Hello"))
        assertEquals("Hello", TranslationText.appendText(null, "Hello"))
    }

    @Test
    fun `译文首尾空白被裁掉`() {
        assertEquals("\nHello", TranslationText.appendText('好', "  Hello  "))
    }

    @Test
    fun `空译文不产生空行以外的内容`() {
        // 空译文在调用链上游已被 EMPTY 拦下，这里只保证纯函数本身不产生空白噪声
        assertEquals("", TranslationText.appendText('好', "   "))
    }

    @Test
    fun `纯零宽译文同样不写回（不留不可见字符）`() {
        assertEquals("", TranslationText.appendText('好', "\u200B"))
        assertEquals("", TranslationText.appendText('好', " \uFEFF "))
    }

    @Test
    fun `可填的字节上限不得超过本机单次读取窗口`() {
        // 二者脱节时用户能填到窗口之外：实际生效的仍是窗口值，而日志按填的值报「已截断」，
        // 阈值失真（2026-10-01 审查 L-223）。绑定后读取窗口恒等于填写的上限。
        assertEquals(
            "MAX_MAX_BYTES 必须等于 MAX_READ_CHARS（一个字符至少 1 字节 ⇒ 不超过窗口一定读得全）",
            TranslationText.MAX_READ_CHARS,
            TranslationText.MAX_MAX_BYTES,
        )
        for (id in TranslationProviderId.entries) {
            assertTrue(
                "$id 的默认上限 ${id.defaultMaxBytes} 必须在可填范围 " +
                    "${TranslationText.MIN_MAX_BYTES}..${TranslationText.MAX_MAX_BYTES} 内",
                id.defaultMaxBytes in TranslationText.MIN_MAX_BYTES..TranslationText.MAX_MAX_BYTES,
            )
        }
    }

    @Test
    fun `数字型错误码也能取到（字符串与数字两种形态等价）`() {
        // 百度官方示例把 error_code 写成字符串，数字形态也出现过：只认 String 会让
        // 配额用尽（54003）落进成功分支，最后报「翻译结果为空」（2026-10-01 审查 L-226）
        assertEquals("54003", jsonCode(JSONObject("""{"error_code":54003}"""), "error_code"))
        assertEquals("54003", jsonCode(JSONObject("""{"error_code":"54003"}"""), "error_code"))
        // JSON null 必须返回 null，不能变成字面量 "null"（那会被当成一个「错误码」）
        assertEquals(null, jsonCode(JSONObject("""{"error_code":null}"""), "error_code"))
        assertEquals(null, jsonCode(JSONObject("{}"), "error_code"))
        assertEquals(null, jsonCode(null, "error_code"))
    }

    @Test
    fun `模型包装必须剥掉：围栏、前缀、引号，且不误伤正文`() {
        // OpenAI 兼容那家常给译文加包装：``` 围栏、「译文：」前缀，原样插进输入框很难看
        // （2026-10-02 修复 L-321；剥离在 TranslationClient 的唯一出口，六家共用）
        assertEquals("你好", TranslationText.stripWrapper("```\n你好\n```"))
        assertEquals("你好", TranslationText.stripWrapper("```text\n你好\n```"))
        assertEquals("你好", TranslationText.stripWrapper("译文：你好"))
        assertEquals("你好", TranslationText.stripWrapper("以下是翻译：你好"))
        assertEquals("你好", TranslationText.stripWrapper("Translation: 你好"))
        assertEquals("你好", TranslationText.stripWrapper("\"你好\""))
        assertEquals("你好", TranslationText.stripWrapper("「你好」"))
        assertEquals("你好", TranslationText.stripWrapper("  你好  "))
        // 不误伤：``` 与前缀**不在首尾**时保持原样（正文里合法出现）
        assertEquals("代码 ``` 示例", TranslationText.stripWrapper("代码 ``` 示例"))
        assertEquals("请翻译：这行", TranslationText.stripWrapper("请翻译：这行"))
        // 剥空回退：只有一个围栏符、正则不成对 ⇒ 原样返回，绝不把有效内容剥没
        assertEquals("```", TranslationText.stripWrapper("```"))
    }

    // ── 行分隔符不只有 `\n`（2026-10-03 修复 L-499）──────────────────────

    @Test
    fun `行边界也认 CR 与 Unicode 行终止符（默认档不该退化成上传全部上文）`() {
        // CR-only（部分应用粘贴、网页编辑器、PDF 复制）：默认档「光标前本行」只应取最后一行。
        // 修复前它取不到行首 ⇒ 静默把**整段上文**当成本行发出去（上传面承诺被破）。
        assertEquals(
            "第三行",
            TranslationText.extract("第一行\r第二行\r第三行", "", TranslationScope.LINE_BEFORE, 10_000).text,
        )
        // U+2028 LINE SEPARATOR / U+0085 NEL 同理
        assertEquals(
            "乙",
            TranslationText.extract("甲\u2028乙", "", TranslationScope.LINE_BEFORE, 10_000).text,
        )
        assertEquals(
            "乙",
            TranslationText.extract("甲\u0085乙", "", TranslationScope.LINE_BEFORE, 10_000).text,
        )
        // 光标所在整行：行尾也要认 CR，否则会把下一行一起吃进来
        val slice = TranslationText.extract(
            before = "上一行\r这一行",
            after = "\r下一行",
            scope = TranslationScope.LINE_FULL,
            maxBytes = 10_000,
        )
        assertEquals("这一行", slice.text)
        assertEquals(0, slice.appendOffset)
        // 老行为不变：`\n` 仍然只取最后一行
        assertEquals(
            "第三行",
            TranslationText.extract("一\n二\n第三行", "", TranslationScope.LINE_BEFORE, 10_000).text,
        )
        // 判据与日志同源：hasLineBreak 认全部分隔符（JinnIme 的「行首不可定位」日志据此报）
        assertTrue(TranslationText.hasLineBreak("甲\r乙"))
        assertTrue(TranslationText.hasLineBreak("甲\u2028乙"))
        assertFalse(TranslationText.hasLineBreak("甲乙"))
    }
}
