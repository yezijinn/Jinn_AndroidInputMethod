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
     * 清单卡片已瘦到「名称 / 说明 / 状态+按钮」三块（2026-10-03 按用户要求删掉体积与加载说明），
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
        //（把 `text = TEXT_LEGACY_LOAD` 换成 `text = ""` 并在旁边注释里写下该名，旧版守卫仍绿）。
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
