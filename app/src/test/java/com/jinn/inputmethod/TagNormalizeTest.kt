package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本标签归一化的守卫（BUG.md L-04）。
 *
 * 更新检查拿仓库 tag 的最大日期当「在线最新版本」，所以标签怎么认直接决定用户会不会被误报打扰：
 *  - 认得太宽：`dict-parts-20260927-v1` 这类词库包标签、或打错的日期（`20261331`）会被当成新版本，
 *    此后每次检查都提示有更新、点进去却没有对应页面 —— 而且不会自愈；
 *  - 认得太严：真正的新版本被无视，用户永远收不到更新提示。
 *
 * 用例里的标签全部取自本仓库真实 tag（`git tag -l`），不是编的。
 */
class TagNormalizeTest {

    @Test
    fun 纯日期与带v前缀的日期都认() {
        assertEquals(20260927, UpdateChecker.normalizeTag("20260927"))
        assertEquals(20260830, UpdateChecker.normalizeTag("v20260830"))
        assertEquals(20260919, UpdateChecker.normalizeTag("v20260919"))
    }

    @Test
    fun 词库包与语义化版本标签不认() {
        // 词库包 tag 里的日期是「包」的日期，不是应用版本；认了会在包先于 APK 发布时误报
        assertNull(UpdateChecker.normalizeTag("dict-parts-20260927-v1"))
        assertNull(UpdateChecker.normalizeTag("dict-opt-tencent-v1"))
        assertNull(UpdateChecker.normalizeTag("v1.1.0"))
        assertNull(UpdateChecker.normalizeTag(""))
        assertNull(UpdateChecker.normalizeTag("v"))
    }

    @Test
    fun 打错日期的八位标签不认_否则永久误报() {
        assertNull("月份 13", UpdateChecker.normalizeTag("20261331"))
        assertNull("日 32", UpdateChecker.normalizeTag("20260932"))
        assertNull("不存在的大月日", UpdateChecker.normalizeTag("20261100"))
        assertNull(UpdateChecker.normalizeTag("20269999"))
    }

    @Test
    fun 六位按yyMMdd收_七位与非ASCII数字不收() {
        // 6 位是历史上的 `yyMMdd` 写法，照收 —— 但要走同一道日期闸（`20yyMMdd`）：
        // `v260929` = 2026-09-29 ✓；`v202609` 展开成 2020 年 26 月、`v260999` 日 99 ⇒ 都拒收
        assertEquals(260929, UpdateChecker.normalizeTag("v260929"))
        assertNull("按 20yyMMdd 展开后月份非法", UpdateChecker.normalizeTag("v202609"))
        assertNull("日 99", UpdateChecker.normalizeTag("v260999"))
        // 7 位既不是 `yyMMdd` 也不是 `yyyyMMdd`：现状（tag 全 8 位）里它数值上小于任何 8 位、
        // 不会误报，但标签规范一旦回退到 6 位，它就会**恒大于**全部合法值 ⇒ 永久误报（BUG.md L-83）
        assertNull(UpdateChecker.normalizeTag("2026092"))
        assertNull(UpdateChecker.normalizeTag("v2026131"))
        // 非 ASCII 数字：`Char::isDigit` 与 `toIntOrNull` 都认（桌面 JVM 实测），
        // 而 tag 与 Gitee 直达链接都要求 ASCII ⇒ 拒收（BUG.md L-126）
        assertNull("全角数字", UpdateChecker.normalizeTag("２０２６０９２９"))
        assertNull("混合全角（会静默解析成 20260920）", UpdateChecker.normalizeTag("2026092０"))
        assertNull("阿拉伯-印度数字", UpdateChecker.normalizeTag("٢٠٢٦٠٩٢٩"))
    }

    @Test
    fun 日期合理性判据的边界() {
        assertTrue(UpdateChecker.isPlausibleDate(20000101))
        assertTrue(UpdateChecker.isPlausibleDate(20991231))
        assertFalse("年份越界", UpdateChecker.isPlausibleDate(19991231))
        assertFalse("月份 0", UpdateChecker.isPlausibleDate(20260001))
        assertFalse("月份 13", UpdateChecker.isPlausibleDate(20261301))
        assertFalse("日 0", UpdateChecker.isPlausibleDate(20261200))
    }
}
