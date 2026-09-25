package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * 本次缺陷修复的回归护栏。
 *
 * 每条断言对应一个已交叉验证过的真实缺陷，回归即复现：
 *  - [fieldKeyOf] 守住「暂存粘贴只回原输入框」（跨字段/跨应用注入）；
 *  - [searchRetainLimitReached] 守住「搜索命中集合有驻留上限」（内存护栏）；
 *  - [readCapped] 守住「更新检查响应体限长读取」；
 *  - [isInsideKeyBounds] 守住「按键抬起必须落在键内（含外扩）才算命中」，
 *    分号键曾漏掉该判定，按下后滑到相邻键抬起仍会把 `;` 追加进拼音串。
 *
 * 这些都是曾经没有、被补上的约束，断言失败意味着缺陷回归，
 * 不要通过放宽阈值让它们变绿。
 */
class RecentFixesRegressionTest {

    // ── 暂存粘贴的字段身份 ────────────────────────────────

    @Test
    fun `fieldKeyOf 空包名返回 null 表示身份未知`() {
        assertNull(fieldKeyOf(null, 0))
        assertNull(fieldKeyOf("", 12))
    }

    @Test
    fun `fieldKeyOf 同包同 fieldId 才相等`() {
        assertEquals("com.tencent.mm#7", fieldKeyOf("com.tencent.mm", 7))
        assertEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.tencent.mm", 7))
    }

    @Test
    fun `fieldKeyOf 跨应用同 fieldId 必须不相等`() {
        // 只比对 fieldId 会让微信的粘贴落进备忘录，这正是缺陷本身
        assertNotEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.notes.app", 7))
    }

    @Test
    fun `fieldKeyOf 同应用不同 fieldId 必须不相等`() {
        assertNotEquals(fieldKeyOf("com.tencent.mm", 7), fieldKeyOf("com.tencent.mm", 8))
    }

    // ── 搜索结果的驻留上限 ────────────────────────────────

    @Test
    fun `搜索驻留未触限时继续累积`() {
        assertFalse(ClipboardStore.searchRetainLimitReached(0, 0L))
        assertFalse(
            ClipboardStore.searchRetainLimitReached(
                ClipboardStore.MAX_SEARCH_RESULTS - 1, 0L
            )
        )
    }

    @Test
    fun `搜索驻留条数触顶即停`() {
        assertTrue(
            ClipboardStore.searchRetainLimitReached(ClipboardStore.MAX_SEARCH_RESULTS, 0L)
        )
    }

    @Test
    fun `搜索驻留字节触顶即停_少量巨文本也要拦住`() {
        // 只限条数挡不住 3 条 256KB 的巨文本：字节预算必须独立生效
        assertTrue(
            ClipboardStore.searchRetainLimitReached(
                3, ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES + 1
            )
        )
    }

    @Test
    fun `搜索驻留预算不超过解密窗口预算`() {
        // 驻留集合活得比瞬时解密窗口久，预算只能更小，不能反过来
        assertTrue(
            ClipboardStore.SEARCH_RETAIN_BUDGET_BYTES <= ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES
        )
    }

    // ── 更新检查响应体限长 ────────────────────────────────

    private fun reader(text: String): BufferedReader = BufferedReader(StringReader(text))

    @Test
    fun `readCapped 未超限返回原文`() {
        val body = "line1\nline2\nline3\n"
        assertEquals(body, UpdateChecker.readCapped(reader(body), 1000))
    }

    @Test
    fun `readCapped 超限时截断且不抛异常`() {
        val long = "x".repeat(50)
        val out = UpdateChecker.readCapped(reader("$long\n$long\n$long\n"), 60)
        assertTrue("超限必须截断: $out", out.length <= 60)
    }

    @Test
    fun `readCapped 空响应返回空串`() {
        assertEquals("", UpdateChecker.readCapped(reader(""), 100))
    }

    // ── 按键命中的几何判定（分号键滑出不得上字）────────────

    @Test
    fun `命中判定 键内与边界外扩内都算命中`() {
        // 键 (100,200) 100x100，外扩 8px
        assertTrue(isInsideKeyBounds(110f, 210f, 100, 200, 100, 100, 8f))   // 正中
        assertTrue(isInsideKeyBounds(93f, 210f, 100, 200, 100, 100, 8f))    // 左边界外扩内
        assertTrue(isInsideKeyBounds(207f, 210f, 100, 200, 100, 100, 8f))   // 右边界外扩内
        assertTrue(isInsideKeyBounds(110f, 193f, 100, 200, 100, 100, 8f))   // 上边界外扩内
        assertTrue(isInsideKeyBounds(110f, 307f, 100, 200, 100, 100, 8f))   // 下边界外扩内
    }

    @Test
    fun `命中判定 滑出到相邻键必须算不命中`() {
        // 分号键的缺陷场景：按下 `;` 后滑到相邻键再抬起，不应把 `;` 追加进拼音串
        assertFalse(isInsideKeyBounds(91f, 210f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(209f, 210f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(110f, 191f, 100, 200, 100, 100, 8f))
        assertFalse(isInsideKeyBounds(110f, 309f, 100, 200, 100, 100, 8f))
    }

    @Test
    fun `命中判定 取不到布局信息时保守算命中`() {
        // width/height ≤ 0（未测量/已分离）：宁可保留旧行为，也不能让用户按不出字
        assertTrue(isInsideKeyBounds(0f, 0f, 0, 0, 0, 0, 8f))
        assertTrue(isInsideKeyBounds(9999f, 9999f, 100, 200, 0, 100, 8f))
    }

    // ── 源码对拍：只能用源码钉住的修复（行为要 Context / 视图 / 进程，JVM 测不到）──
    //
    // 这一组对应 2026-09-26 那批修复里「改回去会静默复现」的几处：改法都在一两行里，
    // 单测与 lint 都看不见（真机崩溃、隐私留盘、丢设置都属于这类），所以用源码把**结论**钉住。
    // 断言失败先回 `BUG.md` 的「已修复」区看当初的判定依据，别直接把断言删掉。

    private fun sourceOf(name: String): String = (
        listOf(
            File("src/main/java/com/jinn/inputmethod/$name"),
            File("app/src/main/java/com/jinn/inputmethod/$name"),
        ).firstOrNull { it.isFile } ?: error("找不到 $name（cwd=${File("").absolutePath}）")
        ).readText()

    /**
     * 只留代码行（去掉整行注释）。
     *
     * 这一组的修复点旁边都写着「为什么必须这么写」的注释 —— 注释里大概率出现同一个调用名，
     * 不剔掉的话「把调用删掉、注释留着」也会绿（实测过：`saveAndRestart` 的 KDoc 里就写着
     * `restartImeProcess()`）。只丢整行注释，不动行尾注释：行尾注释里带 `//` 的字符串（URL）会被误切。
     */
    private fun codeOf(name: String): String = sourceOf(name).lines()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }
        .joinToString("\n")

    /**
     * 截取 [marker] 之后那个花括号块（从 marker 后的第一个 `{` 起配对到对应 `}`）。
     *
     * 不用「取 marker 之后 N 个字符」：窗口取小了会把修复点漏在外面（`saveAndRestart` 第一版就栽在
     * 1500 字符窗口上，函数实际 2500+ 字符），取大了又会把相邻函数的代码算进来 —— 花括号配对没有这个两难。
     */
    private fun blockAfter(text: String, marker: String): String {
        val i = text.indexOf(marker)
        assertTrue("源码里找不到锚点「$marker」—— 改名/重构后请同步本用例", i >= 0)
        val open = text.indexOf('{', i)
        assertTrue("锚点「$marker」之后没有花括号块", open > i)
        var depth = 0
        for (j in open until text.length) {
            when (text[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, j + 1)
                }
            }
        }
        error("锚点「$marker」的花括号不配对")
    }

    @Test
    fun `保存并重启必须先落盘再杀进程`() {
        val body = blockAfter(codeOf("SettingsActivity.kt"), "fun saveAndRestart")
        assertTrue(
            "saveAndRestart 必须调 restartImeProcess()：直接 postDelayed + killProcess " +
                "会在 IO 抖动时丢掉最后一批 apply()（重启后设置回退）",
            body.contains("restartImeProcess()"),
        )
    }

    @Test
    fun `崩溃快照的原始中转件必须落在缓存目录且成对删除`() {
        val text = codeOf("Diagnostics.kt")
        assertTrue(
            "中转件是**未过滤** logcat 的唯一副本，不能落在会被导出诊断包整体打包的日志目录",
            text.contains("File(cacheDir ?: dir"),
        )
        assertTrue("中转件成功 / 失败都要删（finally 里收口）", text.contains("runCatching { raw.delete() }"))
    }

    @Test
    fun `七天清理必须覆盖导出用的 device-info`() {
        val body = blockAfter(codeOf("Diagnostics.kt"), "private fun cleanupOldLogs")
        assertTrue(
            "cleanupOldLogs 要认 DEVICE_INFO_FILE：进程被杀留下的该文件不匹配 jinn- / logcat- 前缀，" +
                "会永久留在日志目录并混进之后每次导出包",
            body.contains("DEVICE_INFO_FILE"),
        )
    }

    @Test
    fun `切符号层与数字层必须先收起剪贴板面板`() {
        val text = codeOf("PinyinKeyboardView.kt")
        for (anchor in listOf("btnSymbol.setOnClickListener", "btnDigit.setOnClickListener")) {
            assertTrue(
                "$anchor 必须调 hidePanelForLayerSwitch()：两层的键都在字母区里，" +
                    "面板显示时字母区整体 GONE ⇒ 切了层既看不到键、红色「返回」也被顶替，用户没有退出口",
                blockAfter(text, anchor).contains("hidePanelForLayerSwitch()"),
            )
        }
    }

    @Test
    fun `进符号层必须清掉未上屏的拼音`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "btnSymbol.setOnClickListener")
        assertTrue(
            "进符号层要调 clearComposingState()：该层不显示拼音条与候选，残留 composing 会「看不见却仍生效」" +
                "（退格空删、收起键盘把上一次首候选上屏）",
            body.contains("clearComposingState()"),
        )
    }

    @Test
    fun `预测候选必须让清空按钮可见`() {
        val body = blockAfter(codeOf("PinyinKeyboardView.kt"), "private fun refreshCandidateBar")
        assertTrue(
            "预测分支要走 showPinyinBarOnly()：✕ 是拼音条的子视图，拼音条 GONE 时它一起消失（只能退格清预测）",
            body.contains("showPinyinBarOnly()"),
        )
    }

    @Test
    fun `滚动收起操作条必须判 lateinit 已初始化`() {
        val text = codeOf("ClipboardPanelView.kt")
        assertTrue(
            "onScroll 里读 actionBar 前必须判 ::actionBar.isInitialized —— setOnScrollListener 注册时会**同步回调一次**，" +
                "那时 actionBar 还没赋值，真机实测会让键盘完全弹不出来（连崩 4 次）",
            text.contains("::actionBar.isInitialized"),
        )
    }

    @Test
    fun `词库重装不得先删旧包`() {
        val text = codeOf("DictManagerActivity.kt")
        assertTrue("必须直接 renameTo（POSIX 原子替换，目标已存在也覆盖）", text.contains("tmp.renameTo(dst)"))
        assertFalse(
            "不得出现 dst.delete()：先删目标再改名，改名失败时用户会同时失去旧包与新包",
            text.contains("dst.delete()"),
        )
    }
}
