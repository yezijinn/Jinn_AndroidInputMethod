package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 可选词库合并（merge）与清单数据的回归测试。
 *
 * 需要这个文件：合并必须去重，基础包与扩展包会收录同一个词
 * （rime-ice 自身的 base/ext 就有重叠），不去重则候选栏出现两个完全相同的候选。
 * 该行为此前只靠真机肉眼看，没有任何测试守着；测试注入入口当时也固定走覆盖语义，
 * 从设计上就测不到 merge。本文件补上这条防线。
 *
 * 覆盖：
 *  - 合并去重（同键同词只保留一份）
 *  - 合并顺序（基础包词条在前，扩展包只追加）
 *  - 合并新增键（扩展包独有的拼音键要能查到）
 *  - 覆盖语义对照（merge=false 时确实整体替换，证明两种语义确有区别）
 *  - 可选词库清单的元数据自洽（文件名/下载源/展示信息）
 */
class OptionalDictMergeTest {

    @Before
    fun setUp() {
        // PinyinEngine 是单例且数据累加，每个用例前必须复位，否则相互污染
        PinyinEngine.resetForTest()
    }

    /** 注入基础包：pinyin 键 → 词条（| 分隔） */
    private fun loadBase(phrases: String) {
        PinyinEngine.loadFromTexts(
            chars = BASE_CHARS,
            phrases = phrases.trimIndent(),
            syllables = SYLLABLES.trimIndent(),
        )
    }

    /** 取某拼音键的候选列表（[PinyinEngine.query] 返回 Result，候选在 candidates 里） */
    private fun candidates(key: String): List<String> = PinyinEngine.query(key).candidates

    /**
     * 核心用例：同一拼音键下基础包与扩展包含有同一个词时，合并后不得重复。
     *
     * 修复前 `existing + kept` 直接拼接，会得到 [且, 切, 且] 这类重复候选。
     */
    @Test
    fun mergeDeduplicatesSameWordInSameKey() {
        loadBase("qie\t且|切")
        // 扩展包在同一个键下重复收录「且」，并带一个新词「茄」
        PinyinEngine.mergePhrasesForTest("qie\t且|茄")

        val result = candidates("qie")
        assertEquals(
            "合并后同键不得出现重复词条，实际=$result",
            result.size, result.toSet().size,
        )
        assertEquals("基础包词条应保留", 1, result.count { it == "且" })
        assertTrue("扩展包新词应并入", result.contains("茄"))
    }

    /** 合并时基础包词条应排在扩展包之前（基础候选优先级更高） */
    @Test
    fun mergeKeepsBaseEntriesFirst() {
        loadBase("qie\t切|且")
        PinyinEngine.mergePhrasesForTest("qie\t茄")

        val result = candidates("qie")
        val baseIdx = result.indexOf("切")
        val extIdx = result.indexOf("茄")
        assertTrue("基础包词条应排在扩展包之前，实际=$result", baseIdx in 0 until extIdx)
    }

    /** 扩展包独有的拼音键必须能查到（合并而非丢弃） */
    @Test
    fun mergeAddsKeysOnlyPresentInExtension() {
        loadBase("qie\t切|且")
        PinyinEngine.mergePhrasesForTest("qiezhiding\t且指定")

        assertTrue(
            "扩展包独有键应可查到",
            candidates("qiezhiding").contains("且指定"),
        )
    }

    /** 合并后新增的键要参与前缀补全（finalizeLoad 必须重建有序键表） */
    @Test
    fun mergeRebuildsSortedKeysForCompletion() {
        loadBase("ni\t你")
        val before = candidates("nihao")
        PinyinEngine.mergePhrasesForTest("nihao\t你好")

        val after = candidates("nihao")
        assertTrue("合并后应能查到新键的候选，实际=$after", after.contains("你好"))
        assertTrue("合并前不应查到，实际=$before", !before.contains("你好"))
    }

    /**
     * 对照用例：覆盖语义（merge=false）确实替换而非合并。
     *
     * 判据必须用多字词：单字候选来自单字表（charsBySyllable），
     * 和词候选在 query 结果里混在一起，用单字区分不了两种语义。
     */
    @Test
    fun overwriteModeReplacesInsteadOfMerging() {
        loadBase("qiezhiding\t且指定")
        assertTrue("覆盖前应有旧词", candidates("qiezhiding").contains("且指定"))

        // 走覆盖语义注入同一个键的另一个词
        PinyinEngine.loadFromTexts(
            chars = BASE_CHARS,
            phrases = "qiezhiding\t切确定",
            syllables = SYLLABLES.trimIndent(),
        )

        val result = candidates("qiezhiding")
        assertTrue("覆盖后新词应存在，实际=$result", result.contains("切确定"))
        assertTrue(
            "覆盖语义下旧词应消失（若走合并则仍会保留），实际=$result",
            !result.contains("且指定"),
        )
    }

    /**
     * 清单元数据自洽性，这几项错了会直接导致下载失败或页面信息误导用户：
     *  ① fileName 唯一且以 .xz 结尾（引擎只扫 .xz，且重名会互相覆盖）
     *  ② 每个下载源都包含 fileName 对应的实际文件
     *  ③ 体积/耗时为正数（页面据此提示用户代价）
     */
    @Test
    fun optionalDictsMetaIsConsistent() {
        val all = OptionalDicts.ALL
        assertTrue("清单不应为空", all.isNotEmpty())

        val names = all.map { it.fileName }
        assertEquals("fileName 不得重复", names.size, names.toSet().size)
        names.forEach { assertTrue("fileName 必须以 .xz 结尾: $it", it.endsWith(".xz")) }

        all.forEach { dict ->
            assertTrue("${dict.name} 缺少下载源", dict.urls.isNotEmpty())
            // 落地文件名与 Release 附件名可以不同（如 ext.xz 对应 dict_ext.txt.xz），
            // 但 URL 必须指向 .xz 资源，否则引擎加载不了
            dict.urls.forEach { url ->
                assertTrue("${dict.name} 的下载源应以 .xz 结尾: $url", url.endsWith(".xz"))
            }
            assertTrue("${dict.name} 体积应为正数", dict.sizeMb > 0)
            assertTrue("${dict.name} 启动耗时应为正数", dict.startupSec > 0)
            assertTrue("${dict.name} 说明不应为空", dict.descLines.isNotEmpty())
        }
    }

    /** resetForTest 必须清干净，否则用例相互污染（其他用例都依赖这一点） */
    @Test
    fun resetForTestClearsPreviousData() {
        loadBase("qie\t切|且")
        assertTrue("注入后应有数据", candidates("qie").isNotEmpty())

        PinyinEngine.resetForTest()
        assertTrue("复位后应查不到任何数据", candidates("qie").isEmpty())
    }

    private companion object {
        /** 单字表：逗号分隔 */
        val BASE_CHARS = """
            qie	且,切,茄,窃
            ni	你,尼
            hao	好,号
        """.trimIndent()

        /** 合法音节表 */
        val SYLLABLES = """
            qie
            ni
            hao
            zhi
            ding
        """.trimIndent()
    }
}
