package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「清单外包」筛选的守卫（BUG.md L-45）。
 *
 * 引擎按目录扫 `dicts/` 全量加载，而分类词库页原先只渲染 [OptionalDicts.ALL] 里的三条 ——
 * 升级前装过旧「长词包」/「腾讯大词库」的设备，文件仍被加载（映射约 34MB），页面上却看不到、
 * 也删不掉。页面的「其他包」区靠 [OptionalDicts.unknownPackages] 决定列出哪些文件，
 * 这里钉住它的四条契约：清单内不动、旧包露出、临时件与非常规文件不列、输出有序。
 */
class OptionalDictJunkTest {

    @Test
    fun `清单内的包不会被列成其他包`() {
        val known = OptionalDicts.ALL.map { it.fileName }
        assertTrue("清单本身不该为空", known.isNotEmpty())
        assertEquals(emptyList<String>(), OptionalDicts.unknownPackages(known))
    }

    @Test
    fun `清单外只多出一个时必须只列它`() {
        val names = OptionalDicts.ALL.map { it.fileName } + "ext.xz"
        assertEquals(listOf("ext.xz"), OptionalDicts.unknownPackages(names))
    }

    @Test
    fun `旧版两个包会被列出`() {
        assertEquals(
            listOf("ext.xz", "opt_tencent.xz"),
            OptionalDicts.unknownPackages(listOf("opt_tencent.xz", "ext.xz")),
        )
    }

    @Test
    fun `下载中的临时件与非 xz 文件都不列`() {
        // 下载写的是 `<名字>.xz.tmp`，此刻不该在页面上出现一个「可删除的包」；
        // 其余非 .xz 文件（索引、说明）同样不是词库包
        assertEquals(
            emptyList<String>(),
            OptionalDicts.unknownPackages(listOf("part5.xz.tmp", "notes.txt", "readme", "part5")),
        )
    }

    @Test
    fun `输出按名字排序`() {
        assertEquals(
            listOf("aaa.xz", "ext.xz", "zzz.xz"),
            OptionalDicts.unknownPackages(listOf("zzz.xz", "ext.xz", "aaa.xz")),
        )
    }

    /**
     * 旧包卡片必须用自己的完整文案（BUG.md L-69）。
     *
     * 清单卡片已瘦到「名称 / 说明 / 状态+按钮」三块（2026-10-03 删掉体积与加载说明），
     * `dict_startup_cost_line1~3` 随之删除；那两句加载说明（`dict_load_timing` /
     * `dict_startup_instant`）搬到了**页面顶部**的四句提示里，不再进卡片。
     * 旧包没有对应清单条目可复用，`TEXT_LEGACY_LOAD` 仍是它唯一合适的写法 —— 因此这里既钉
     * 「代码里用了自己的文案」，也钉「那三条资源不许复活」。
     */
    @Test
    fun `旧包卡片不得复用前缀型资源`() {
        val src = sourceOf("DictManagerActivity.kt")
        val card = src.substringAfter("private fun buildUnknownCard").substringBefore("\n    /**")
        assertTrue("没取到 buildUnknownCard 的函数体（结构变了？）", card.length > 200)
        // 两条钉都只判**代码**（剥注释，BUG.md L-116）：注释里说明「为什么不能用」「该用哪个文案」
        // 是应该留的，而「实现删掉、名字留在注释里」这种情况必须转红 —— 本条正是 L-116 的实证场景
        // （把 `text = TEXT_LEGACY_LOAD` 换成 `text = ""` 并在旁边注释里写下该名，旧版守卫仍绿）。
        val code = TestSources.codeOf(card)
        assertTrue(
            "旧包卡片引用了 dict_startup_cost 系列资源：那三条已删除，且都是半句残话",
            "R.string.dict_startup_cost" !in code,
        )
        assertTrue("旧包卡片没有用自己的加载提示文案（TEXT_LEGACY_LOAD）", "TEXT_LEGACY_LOAD" in code)
        val xml = resFile("values/strings.xml").readText()
        assertTrue(
            "dict_startup_cost_line1~3 已随卡片瘦身删除（卡片只留名称 / 说明 / 状态+按钮），不许复活",
            "dict_startup_cost" !in xml,
        )
    }

    // ── 残留下载临时件（BUG.md L-798）────────────────────────────

    @Test
    fun `残留临时件按后缀筛出且排除正在下载的那个`() {
        val names = listOf(
            "dict_part2.txt.xz", // 已装好的包：不是临时件
            "dict_part2.txt.xz.tmp", // 正在下载的那个的临时件
            "dict_part3.txt.xz.tmp", // 上次被杀留下的残件
            "notes.txt",
        )
        assertEquals(
            listOf("dict_part3.txt.xz.tmp"),
            OptionalDicts.staleTempNames(names, active = "dict_part2.txt.xz"),
        )
    }

    @Test
    fun `空闲时（没有下载）全部临时件都算残留`() {
        assertEquals(
            listOf("a.xz.tmp", "b.xz.tmp"),
            OptionalDicts.staleTempNames(listOf("a.xz.tmp", "b.xz.tmp", "a.xz"), active = null),
        )
    }

    @Test
    fun `刚写入的临时件须在保护窗内豁免清理`() {
        // 自定义词库导入走固定临时名：导入中回到前台时若把它当残留删掉，随后的改名必然失败（BUG.md L-827）
        val now = 1_000_000_000_000L
        val names = listOf("custom_user.txt.xz.tmp", "dict_part2.txt.xz.tmp", "notes.txt")
        val mtimes = mapOf(
            "custom_user.txt.xz.tmp" to now - 1_000L,                            // 正在写（1 秒前）
            "dict_part2.txt.xz.tmp" to now - OptionalDicts.TMP_PROTECT_MS - 1,   // 老残留
        )
        assertEquals(
            listOf("dict_part2.txt.xz.tmp"),
            OptionalDicts.cleanableTempNames(names, active = null, now = now) { mtimes[it] ?: 0L },
        )
        // 边界：恰好等于保护窗的也算「刚写入」，不清理（宁多留一轮）
        assertEquals(
            emptyList<String>(),
            OptionalDicts.cleanableTempNames(listOf("a.xz.tmp"), active = null, now = now) {
                now - OptionalDicts.TMP_PROTECT_MS
            },
        )
    }

    @Test
    fun `残留件的清理与重启窗口接线不得回退（L-798、L-801）`() {
        val src = TestSources.codeOf(TestSources.rawSourceOfShortName("DictManagerActivity.kt"))
        assertTrue(
            "refreshList 必须清理残留临时件（否则一次下载中进程被杀就在存储里留最多 64MB）；" +
                "2026-10-05 起走 cleanableTempNames（带刚写入保护窗，BUG.md L-827）",
            "OptionalDicts.cleanableTempNames(" in block(src, "private fun refreshList"),
        )
        assertTrue(
            "不得退回只按名字筛的 staleTempNames：会把正在写入的自定义词库临时件当残留删掉，随后的改名必失败（BUG.md L-827）",
            "OptionalDicts.staleTempNames(" !in block(src, "private fun refreshList"),
        )
        assertTrue(
            "清理必须留一条日志（否则事后无法判断有没有清过）",
            "清理残留下载临时件" in src,
        )
        // L-801：1.5s 重启窗口内不得再接受改 dicts/ 的操作
        val restart = block(src, "private fun restartImeForDict")
        assertTrue("重启窗口必须先置位 restartPending", "restartPending = true" in restart)
        assertTrue(
            "置位必须排在 postDelayed 之前（否则窗口内按钮仍可点）",
            restart.indexOf("restartPending = true") < restart.indexOf("postDelayed"),
        )
        for (entry in listOf(
            "private fun download", "private fun remove", "private fun confirmRemoveLegacy",
            "private fun removeLegacy",
        )) {
            assertTrue("$entry 必须判重启窗口（blockedByRestart）", "blockedByRestart()" in block(src, entry))
        }
        assertTrue(
            "按钮可用性必须走 actionsEnabled()（下载中或重启窗口内都禁用）",
            "actionsEnabled()" in src && "enabled = downloading == null" !in src,
        )
    }

    /** 取 [marker] 之后的花括号块（与 RecentFixesRegressionTest.blockAfter 同款；那边是 private 助手） */
    private fun block(src: String, marker: String): String {
        val i = src.indexOf(marker)
        assertTrue("源码里找不到锚点「$marker」（改名了？）", i >= 0)
        val open = src.indexOf('{', i)
        var depth = 0
        for (j in open until src.length) {
            when (src[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, j + 1)
                }
            }
        }
        error("块没有闭合：$marker")
    }

    private fun sourceOf(name: String): String = TestSources.rawSourceOfShortName(name)

    /**
     * 资源文件读取（与 [ThemeColorParityTest] 同款，工作目录可能是模块目录或仓库根）。
     *
     * ⚠ 两份副本是刻意的：那个是它的 private 助手、这里需要跨类复用同一个「两个候选路径」口径 ——
     * 统一到 [TestSources] 的收益只有一处调用点，代价是动一个已冻结的共用助手。
     */
    private fun resFile(relative: String): File =
        listOf(File("src/main/res/$relative"), File("app/src/main/res/$relative"))
            .firstOrNull { it.isFile }
            ?: error("找不到资源文件: $relative")
}
