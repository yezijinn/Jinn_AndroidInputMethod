package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「打开设置页时自动检查更新」的节流判据单测（纯函数，不依赖 Android 运行时）。
 *
 * 判据只有一行减法，但它决定用户会不会被反复打扰、或反过来被永久静默：
 * 成功检查写入时间戳后 7 天内不得再查；而 0/负数（老版本从未写过）与未来时间
 * （系统时钟回拨、外部改写 prefs）必须放行，否则 `now - last` 恒为负、永久静默。
 */
class UpdateCheckerTest {

    @Test
    fun 从未成功检查过_执行() {
        assertTrue("0 = 从未检查", UpdateChecker.shouldAutoCheck(0L, NOW))
        assertTrue("负数同属脏值，按未检查处理", UpdateChecker.shouldAutoCheck(-1L, NOW))
    }

    @Test
    fun 不足七天_跳过() {
        assertFalse("刚刚检查过", UpdateChecker.shouldAutoCheck(NOW, NOW))
        assertFalse(UpdateChecker.shouldAutoCheck(NOW - 1, NOW))
        assertFalse(UpdateChecker.shouldAutoCheck(NOW - 6 * DAY, NOW))
        assertFalse("差 1 毫秒不满七天", UpdateChecker.shouldAutoCheck(NOW - 7 * DAY + 1, NOW))
    }

    @Test
    fun 满七天_执行() {
        assertTrue(UpdateChecker.shouldAutoCheck(NOW - 7 * DAY, NOW))
        assertTrue(UpdateChecker.shouldAutoCheck(NOW - 30 * DAY, NOW))
    }

    @Test
    fun 时间戳在未来_视为未检查而不是永久静默() {
        assertTrue(UpdateChecker.shouldAutoCheck(NOW + 1, NOW))
        assertTrue(UpdateChecker.shouldAutoCheck(NOW + 365 * DAY, NOW))
    }

    /** 需求写死 7 天：改间隔必须是有意为之，不能被顺手改掉 */
    @Test
    fun 间隔为七天() {
        assertEquals(7L * 24 * 60 * 60 * 1000, UpdateChecker.AUTO_CHECK_INTERVAL_MS)
    }

    /**
     * 仓库 tag 里 `v20260919` 这类带前缀的写法与纯数字并存，归一化只用于比较：
     * 直达链接必须用**原始名**，否则落到 404。
     */
    @Test
    fun 去更新链接必须用原始标签名() {
        assertTrue(
            UpdateChecker.releasesUrl(20260919, "gitee", "v20260919")
                .endsWith("/releases/tag/v20260919")
        )
        assertTrue(
            UpdateChecker.releasesUrl(20260926, "gitee", "20260926")
                .endsWith("/releases/tag/20260926")
        )
        // 调用方没给 tag 时退回数字（老调用点兼容，不会拼出空 tag）
        assertTrue(UpdateChecker.releasesUrl(20260926, "gitee").endsWith("/releases/tag/20260926"))
        // GitHub 分支走发行版列表页，带不带 tag 都一样
        assertEquals(
            "https://github.com/yezijinn/Jinn_AndroidInputMethod/releases",
            UpdateChecker.releasesUrl(20260926, "github", "20260926"),
        )
    }

    @Test
    fun `只有六位与八位数字标签算版本`() {
        // BUG.md L-83：7 位既不是 yyyyMMdd 也不是 yyMMdd —— 现状（全 8 位）下它小于任何 8 位、不会
        // 误报，但标签规范若回退到 6 位，它就会恒大于全部合法值 ⇒ 永久误报 ⇒ 直接拒收
        assertNull(UpdateChecker.normalizeTag("v2026131"))
        assertNull(UpdateChecker.normalizeTag("2026131"))
        // 6 位与 8 位照收；两者都要过同一道日期闸（8 位 L-04，6 位按 20yyMMdd）
        assertEquals(20260929, UpdateChecker.normalizeTag("v20260929"))
        assertEquals(260929, UpdateChecker.normalizeTag("v260929"))
        assertNull(UpdateChecker.normalizeTag("v260999"))
        assertNull(UpdateChecker.normalizeTag("v20261331"))
    }

    @Test
    fun `六位与八位标签比较时按归一键`() {
        // BUG.md L-123：260929（=2026-09-29）必须大于 20260901（=2026-09-01），
        // 否则混用两种长度时跨源会挑旧、与本地比会判「已最新」⇒ 漏报新版
        assertTrue(UpdateChecker.comparableVersion(260929) > UpdateChecker.comparableVersion(20260901))
        assertEquals(20260929, UpdateChecker.comparableVersion(260929))
        assertEquals(20260929, UpdateChecker.comparableVersion(20260929)) // 8 位原样
    }

    /**
     * 展示值与比较口径必须**同量纲**（`BUG.md` L-131）。
     *
     * 比较已在 L-123 归一到 `comparableVersion`，而对话框文案若仍传 `normalizeTag` 的原始值，
     * 标签规范回退到 6 位时就会出现「你目前的版本：20260929 / 在线最新版本：260930」却提示有更新
     * ——数字看着更小。两处文案都必须经「归一键」这一道（本地 helper `shown` 就是它）。
     */
    @Test
    fun `更新对话框的展示值与比较口径同量纲`() {
        assertEquals(20260930, UpdateChecker.comparableVersion(UpdateChecker.normalizeTag("v260930")!!))
        assertTrue(
            "6 位标签归一后必须大于同日的 8 位值",
            UpdateChecker.comparableVersion(UpdateChecker.normalizeTag("v260930")!!) > 20260929,
        )
        val src = TestSources.codeSource("SettingsActivity.kt")
        assertTrue(
            "展示值必须经 UpdateChecker.comparableVersion（缺这个 helper 就等于退回原始值）",
            "fun shown(v: Int) = UpdateChecker.comparableVersion(v)" in src,
        )
        val args = Regex("""R\.string\.update_latest_version,\s*([A-Za-z0-9_.()]+)""")
            .findAll(src).map { it.groupValues[1] }.toList()
        assertEquals("update_latest_version 的调用点数量变了，钉要跟着改：$args", 2, args.size)
        assertTrue("两处文案都必须传归一后的值：$args", args.all { it.startsWith("shown(") })
    }

    /**
     * 关键函数的 KDoc 必须**紧邻**声明（`BUG.md` L-130）。
     *
     * 插新函数时把原 KDoc 挤成「浮动注释」：读新函数的人先看到旧函数的说明，读旧函数的人反而
     * 看不到自己的 KDoc（Kotlin 只把紧邻声明的那条当 KDoc），而编译器一个字都不报。
     */
    @Test
    fun `关键函数的KDoc必须紧邻声明`() {
        val raw = TestSources.rawSourceOfShortName("UpdateChecker.kt")
        for (name in listOf("comparableVersion", "normalizeTag")) {
            val head = raw.substringBefore("internal fun $name(").trimEnd()
            assertTrue("$name 上方没有 KDoc：…${head.takeLast(40)}", head.endsWith("*/"))
            val opener = head.lastIndexOf("/**")
            assertTrue("$name 上方的注释不是 KDoc 形态", opener > 0)
            val prevEnd = head.lastIndexOf("*/", head.length - 3)
            if (prevEnd > 0) {
                val gap = head.substring(prevEnd + 2, opener)
                assertTrue(
                    "$name 上方是连排注释（浮动 KDoc，读者会认错）：gap=「${gap.take(24)}」",
                    gap.isNotBlank(),
                )
            }
        }
    }

    /**
     * 两个源的返回顺序不同（GitHub `/tags` 降序、Gitee API 按名称升序）⇒ 挑最大必须与顺序无关
     * （`BUG.md` L-146）。抽成 [UpdateChecker.pickLatest] 就是为了让这件事可测。
     */
    @Test
    fun `挑最新版本必须与标签顺序无关`() {
        val asc = listOf("v20260821", "20260920", "20260928", "v260929", "dict-parts-20260927-v1")
        val cases = listOf(asc, asc.reversed(), asc.shuffled(java.util.Random(7)))
        for (tags in cases) {
            val best = UpdateChecker.pickLatest(tags, "gitee")!!
            // `Latest.date` 是**归一值**（6 位仍是 6 位，见 normalizeTag 的 KDoc）——「谁更大」必须过
            // comparableVersion；展示面同理（SettingsActivity 的 shown）。
            assertEquals("乱序 / 升序 / 降序都要挑出 2026-09-29：$tags", 20260929,
                UpdateChecker.comparableVersion(best.date))
            assertEquals("原始标签名要留着（跳转链接要用）", "v260929", best.tag)
        }
    }

    /**
     * 大写 `V` 前缀此前被**静默忽略**（`removePrefix("v")` 只去小写）⇒ 该 tag 等价于不存在，
     * 退化为取次大值：可能误报「已是最新」且不自愈（`BUG.md` L-147）。
     * 只削**一个**前缀字符，`vV20260919` 仍然拒收 —— 不把「猜」放进来。
     */
    @Test
    fun `大写V前缀不得被静默忽略`() {
        assertEquals(20260919, UpdateChecker.normalizeTag("V20260919"))
        assertEquals(260919, UpdateChecker.normalizeTag("V260919"))
        assertNull(UpdateChecker.normalizeTag("vV20260919"))
        assertNull(UpdateChecker.normalizeTag("vv20260919"))
    }

    /**
     * 源码钉：Gitee 侧必须**翻页取并集**、读取必须把**总预算**传进去（`BUG.md` L-146 / L-145 / L-1052）。
     *
     * 背景（2026-10-07 重新实测，取代此前「默认 20 条/页」那条旧结论）：接口按名称**升序**返回，
     * 不传 `per_page` 时服务端**返回整份列表**（本仓库 28 条、大仓库 58 / 63 条都一次给全），
     * 而 `page` 只在同时传 `per_page` 时才被采纳 —— 只传 `page` 会每页都拿到首页副本，
     * 上限守卫形同虚设。所以这条判据钉两件事：翻页要真的翻（两个参数同时带）；一旦服务端对整份返回
     * 设了上限，被截断的正是升序末尾、也就是最新的日期标签，守卫必须存在。
     * 另一半是读取：`readTimeout` 只约束单次 read，滴水响应能把总时长拉到远超 25s 预算 ⇒ 看门狗提前解锁。
     */
    @Test
    fun `Gitee必须翻页且读取受总预算约束`() {
        val src = TestSources.codeSource("UpdateChecker.kt")
        assertTrue("Gitee 侧必须翻页（截断的正是升序末尾，也就是最新的日期标签）", "GITEE_TAGS_MAX_PAGES" in src)
        assertTrue(
            "翻页必须同时带 per_page 与 page（只传 page 时服务端忽略它，每页都返回首页副本）",
            "tags?per_page=" in src && "&page=" in src,
        )
        assertTrue("单页条数要有常量（改名成 PAGE_LIMIT，与当年那个终止判据标记区分开）",
            "GITEE_TAGS_PAGE_LIMIT" in src)
        assertTrue(
            "tagger 段要先削掉再取 name（那是用户名，不是标签名）",
            "taggerRe" in src && "body.replace(taggerRe" in src,
        )
        assertTrue("读取必须把 deadline 传进去（socket 超时管不住滴水响应）",
            "readCapped(it, MAX_BODY_CHARS, deadline)" in src)
        assertTrue("挑最新必须走顺序无关的 pickLatest", "pickLatest(" in src)
        assertTrue("到翻页上限要留日志（不静默漏）", "可能还有更靠后的标签" in src)
    }

    /**
     * 翻页途中的取页失败必须与「空页到底」分开（`BUG.md` L-1048）。
     *
     * 两者都只终止循环，但语义完全不同：空页是正常到底，失败是**只拿到最旧的那段**（接口按名称
     * 升序返回）⇒ 据此定论会判出偏旧的「最新版本」，表现为假的「已最新」加七天节流。
     * 所以取不到页时本源作废、走回退源，而不是拿部分结果定论。
     */
    @Test
    fun `Gitee翻页的中途失败不许折叠成到底`() {
        val src = TestSources.codeSource("UpdateChecker.kt")
        val loop = TestSources.window(src, "while (page <= GITEE_TAGS_MAX_PAGES)", "if (page > GITEE_TAGS_MAX_PAGES)")
        assertFalse("取页失败不许直接 break（那等于当成到底）", "?: break" in loop)
        assertTrue("取不到页即本源作废", "return@runCatching null" in loop)
        assertTrue("空页才是正常到底", "if (names.isEmpty()) break" in loop)
        assertTrue("作废要留日志", "本源作废" in loop)
    }

    /**
     * 源顺序必须是 **Gitee 优先**，失败文案的域名顺序与之同序（统一规范 6.9）。
     *
     * 这条不能用「结果对不对」验证：两源都通时谁先谁后结果完全一样，只有在 GitHub 不可达的
     * 真实网络下才暴露 —— 那时把 GitHub 排前面，用户要白等一轮超时、甚至直接落到「网络异常」。
     * 2026-10-07 审查发现顺序与文案都还是旧的 GitHub 优先，而上面所有测试都是绿的，正因为
     * 缺的就是这一条。所以直接钉源码里的先后。
     */
    @Test
    fun `源顺序必须Gitee优先且文案同序`() {
        val src = TestSources.codeSource("UpdateChecker.kt")
        val gitee = src.indexOf("fetchFromGitee(deadline)")
        val github = src.indexOf("fetchFromGithub(deadline)")
        assertTrue("两个源都要出现在取数主流程里", gitee > 0 && github > 0)
        assertTrue("Gitee 必须排在 GitHub 之前", gitee < github)
        assertTrue("回退提示要随之更新", "Gitee 源无可用标签，回退 GitHub" in src)

        val xml = resFile("values/strings.xml").readText()
        assertTrue(
            "失败文案必须按同一优先级写：访问 gitee.com / github.com 失败",
            "在线最新版本：访问 gitee.com / github.com 失败" in xml,
        )
    }

    /**
     * 翻页判据与记账时机必须留在原位（`BUG.md` L-1046 / L-1047）。
     *
     * 两条同型：写错就静默失效。翻页判据错会漏掉最新那批标签（接口按名称升序返回，截断的正是末尾），
     * 记账时机错会让用户什么都没看到却被静默七天。
     */
    @Test
    fun `翻页判据与记账时机必须留在原位`() {
        val src = TestSources.codeSource("UpdateChecker.kt")
        assertTrue("空页才停是唯一终止条件", "if (names.isEmpty()) break" in src)
        assertFalse("不再拿实测页大小当判据", "GITEE_TAGS_PAGE_SIZE" in src)
        assertTrue("翻页上限仍在", "GITEE_TAGS_MAX_PAGES" in src)

        val settings = TestSources.codeSource("SettingsActivity.kt")
        for (mark in listOf(
            "检查更新结果已到达，但页面已销毁，跳过弹窗",
            "自动检查更新结果已到达，但页面已销毁，跳过弹窗",
        )) {
            val at = settings.indexOf(mark)
            assertTrue("源码里找不到锚点（改名后请同步本用例）：$mark", at >= 0)
            val record = settings.indexOf("recordUpdateCheckTime(result)", at)
            assertTrue("销毁守卫之后必须紧跟记账：$mark", record - at in 1..400)
        }
    }

    /** 资源定位：与 `OptionalDictJunkTest` 同一口径（Gradle 的任务工作目录不止一种） */
    private fun resFile(relative: String): File =
        listOf(File("src/main/res/$relative"), File("app/src/main/res/$relative"))
            .firstOrNull { it.isFile }
            ?: error("找不到资源文件: $relative")

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val NOW = 1_800_000_000_000L
    }
}
