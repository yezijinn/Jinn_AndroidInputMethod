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
     * `dict_startup_cost_line1` 的值以逗号结尾（「…（息屏或收起键盘后），」），它是给清单内卡片做**前缀**的
     * （后面接「第一次约 N 秒」），单用会在卡片上留下半句残话。旧包没有实测耗时（见 `buildUnknownCard` 的 KDoc），
     * 所以只写一句完整的话。
     */
    @Test
    fun `旧包卡片不得复用前缀型资源`() {
        val src = sourceOf("DictManagerActivity.kt")
        val card = src.substringAfter("private fun buildUnknownCard").substringBefore("\n    /**")
        assertTrue("没取到 buildUnknownCard 的函数体（结构变了？）", card.length > 200)
        // 只禁「使用」（`R.string.dict_startup_cost*`）：注释里说明「为什么不能用」是应该留的
        assertTrue(
            "旧包卡片引用了 dict_startup_cost 系列资源：它们以逗号结尾，单用会留半句残话",
            "R.string.dict_startup_cost" !in card,
        )
        assertTrue("旧包卡片没有用自己的加载提示文案（TEXT_LEGACY_LOAD）", "TEXT_LEGACY_LOAD" in card)
    }

    private fun sourceOf(name: String): String {
        val path = listOf(
            File("src/main/java/com/jinn/inputmethod/$name"),
            File("app/src/main/java/com/jinn/inputmethod/$name"),
        ).firstOrNull { it.isFile } ?: error("找不到 $name")
        return path.readText()
    }
}
