package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 前缀联想「收集端上限」的等价性守卫（2026-10-20）。
 *
 * 背景：[PinyinEngine.matchCharsByPrefix] 原先**无上限**收集，再由调用方 `displayTake(..., MAX_CHARS)`
 * 截断。实测 `query("nih")` 会收集 63,680 字节 / 136µs，最终只有 60 条被显示 —— 无谓的分配与遍历。
 * 改为收集端内联上限后，必须证明**没有改变用户看到的内容**。
 *
 * 能安全截断的前提（本类逐条钉住）：
 *  1. 该函数**不排序** ⇒ 前 N 条与全量前 N 条是同一批（不存在「排序后再截断」的次序差异）；
 *  2. 档位过滤在 `out.add` **之前** ⇒ 稀有字档关闭时不会因先截断而返回不足 60 条；
 *  3. 去重（`seen`）在计数**之前** ⇒ 重复字不会白占名额。
 *
 * 这三条任一被改坏，用户侧的表现都是「候选变少 / 顺序变了」，静态看代码看不出来。
 */
class CharPrefixCapTest {

    companion object {
        /** 与引擎 MAX_CHARS 同值；这里独立写一份，避免测试跟着常量一起被改掉而失去守卫意义 */
        private const val CAP = 60
    }

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
        // 单音节挂大量单字：让「收集量 >> 上限」这个条件成立（真实表 yi 有 326 字）
        // 必须远超上限（CAP*2=120）才谈得上「截断」：0x4E00..0x4E45 只有 70 字，不够
        val manyChars = (0x4E00..0x4F00).joinToString(",") { it.toChar().toString() }
        PinyinEngine.loadFromTexts(
            chars = """
                ni	$manyChars
                hao	$manyChars
            """.trimIndent(),
            phrases = "nihao\t你好|拟好",
            syllables = """
                ni
                hao
            """.trimIndent(),
        )
    }

    /** 前缀联想的结果不得超过上限，且与「全量前 N 条」完全一致（不排序 ⇒ 截断不改序） */
    @Test
    fun prefixCharsAreCappedAtEngineLimit() {
        val result = PinyinEngine.query("n")
        val chars = result.candidates

        assertTrue(
            "前缀联想不得超过上限 $CAP，实际=${chars.size}",
            chars.size <= CAP,
        )
        // 关键：必须**恰好**满额 —— 若过滤被挪到收集之后，这里会不足（稀有档关闭时）
        assertEquals(
            "全集足够时应恰好返回 $CAP 条（不足说明过滤与截断的顺序被颠倒）",
            CAP,
            chars.size,
        )
        // 不排序 ⇒ 结果与「原序取前 N」一致（本库的单字按音节内原有顺序给出）
        assertEquals(
            "次序必须与无上限收集的前 N 条一致（不排序是截断安全的唯一依据）",
            fullTake("n", CAP),
            chars,
        )
    }

    /** 前提：本用例的全集必须**远超**上限，否则截断根本没被触发，上面两条形同虚设 */
    @Test
    fun fixtureMustOverflowTheCap() {
        val full = PinyinEngine.matchCharsByPrefixUncappedForTest("n")
        assertTrue(
            "对拍语料的全集必须远超上限（实际=${full.size}），否则截断未被触发",
            full.size > CAP * 2,
        )
    }

    /** 去重在计数之前：重复字不占名额 */
    @Test
    fun duplicatesDoNotConsumeTheCap() {
        val chars = PinyinEngine.query("n").candidates
        assertEquals(
            "结果里不应有重复（seen 必须在 add 之前）",
            chars.size,
            chars.toSet().size,
        )
    }

    /** 边界：空前缀与不存在的音节不得炸，且返回空 */
    @Test
    fun emptyAndUnknownPrefixAreSafe() {
        assertTrue(PinyinEngine.query("").candidates.isEmpty())
        val unknown = PinyinEngine.query("zzzzzz").candidates
        assertTrue("不存在的音节应为空，实际=${unknown.size}", unknown.isEmpty())
    }

    /**
     * 独立参考实现：用引擎暴露的「无上限收集」出口取全集，再取前 [n] 条。
     *
     * ⚠ 不能用 `query(prefix).candidates.take(n)` 当参考 —— 那就是被断言的同一个值，
     *    断言恒真、没有守卫意义（本轮第一版正是这么错的）。
     */
    private fun fullTake(prefix: String, n: Int): List<String> =
        PinyinEngine.matchCharsByPrefixUncappedForTest(prefix).take(n)
}
