package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScreenTextCollector] 的纯 JVM 单测：用假 [TextNode] 造树，不依赖 Robolectric
 * （仓内测试依赖只有 junit + org.json；`AccessibilityNodeInfo` 在 JVM 下是 `Stub!`，
 * 直接 new 它测不出任何真实行为）。
 *
 * 覆盖面：过滤（密码 / 不可见 / 噪声文本）、去重、四重上限、视觉顺序、整节点标记。
 */
class ScreenTextCollectorTest {

    /** 假节点：默认可见、非密码、不可编辑，children 顺序即 DFS 顺序。 */
    private class FakeNode(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val isPassword: Boolean = false,
        override val isVisibleToUser: Boolean = true,
        override val isEditable: Boolean = false,
        override val windowId: Int = 7,
        override val bounds: ScreenBounds = ScreenBounds.ZERO,
        private val children: List<TextNode> = emptyList(),
        private val setTextOk: Boolean = true,
    ) : TextNode {
        override val childCount: Int get() = children.size
        override fun childAt(index: Int): TextNode? = children.getOrNull(index)
        override fun performSetText(value: String): Boolean = setTextOk
        override fun refreshAlive(): Boolean = true
    }

    private fun collector(
        maxNodes: Int = ScreenTextCollector.MAX_NODES,
        maxDepth: Int = ScreenTextCollector.MAX_DEPTH,
        maxTotalChars: Int = ScreenTextCollector.MAX_TOTAL_CHARS,
        maxSegments: Int = ScreenTextCollector.MAX_SEGMENTS,
    ) = ScreenTextCollector(
        bandPx = 24,
        maxNodes = maxNodes,
        maxDepth = maxDepth,
        maxTotalChars = maxTotalChars,
        maxSegments = maxSegments,
    )

    private fun collect(root: TextNode, sourcePackage: String? = "com.demo.app"): ScreenCapture =
        collector().also { it.traverse(root) }.toCapture(sourcePackage)

    // ── 过滤 ───────────────────────────────────────────

    @Test
    fun 父子同文只留一条() {
        val child = FakeNode(text = "hello")
        val root = FakeNode(children = listOf(child))
        // 父节点自身没有文本，只有子节点有 ⇒ 1 段
        assertEquals(1, collect(root).segments.size)
        // 父子同文（应用常把子节点文字镜像到父节点）⇒ 仍只 1 段
        val mirror = FakeNode(text = "hello", children = listOf(FakeNode(text = "hello")))
        assertEquals(1, collect(mirror).segments.size)
    }

    @Test
    fun 密码节点整棵子树跳过() {
        val leaf = FakeNode(text = "secret-value")
        val child = FakeNode(text = "username", children = listOf(leaf))
        val password = FakeNode(text = "password", isPassword = true, children = listOf(child))
        val root = FakeNode(children = listOf(password, FakeNode(text = "normal")))
        val texts = collect(root).segments.map { it.text }
        assertEquals(listOf("normal"), texts)
    }

    @Test
    fun 不可见节点不进列表() {
        val hidden = FakeNode(text = "滚出屏的内容", isVisibleToUser = false, children = listOf(FakeNode(text = "子节点也不该进")))
        val root = FakeNode(children = listOf(hidden, FakeNode(text = "在屏内")))
        assertEquals(listOf("在屏内"), collect(root).segments.map { it.text })
    }

    @Test
    fun 噪声文本不进列表() {
        val root = FakeNode(
            children = listOf(
                FakeNode(text = "+"),
                FakeNode(text = "·—"),
                FakeNode(text = "   "),
                FakeNode(text = "\u200B\u2060"),
                FakeNode(text = "正文内容"),
            ),
        )
        assertEquals(listOf("正文内容"), collect(root).segments.map { it.text })
    }

    @Test
    fun contentDescription兜底当文本() {
        val root = FakeNode(children = listOf(FakeNode(contentDescription = "无文本节点")))
        val seg = collect(root).segments.single()
        assertEquals("无文本节点", seg.text)
        // 取自 contentDescription 的段**永不**给替换：它不等于节点正文（node.text 为空）
        assertFalse(seg.wholeNode)
    }

    // ── 上限（四条，逐条对应一类事故） ────────────────────

    @Test
    fun 节点上限封顶并被标记为截断() {
        val children = (1..30).map { FakeNode(text = "item$it") }
        val c = collector(maxNodes = 10)
        c.traverse(FakeNode(children = children))
        val snap = c.toCapture(null)
        assertTrue("触达节点上限必须置截断标志", snap.truncatedByLimit)
    }

    @Test
    fun 深度上限不栈溢出且被标记() {
        // 100 层链：从叶子往上套
        var node: TextNode = FakeNode(text = "深处文本")
        repeat(99) { node = FakeNode(children = listOf(node)) }
        val c = collector(maxDepth = 32)
        c.traverse(node)
        assertTrue(c.toCapture(null).truncatedByLimit)
    }

    @Test
    fun 字符上限截断尾段且总字数不超上限() {
        // 每段 60 字符且互不相同（同文会被去重，测不到额度逻辑）
        val children = (1..3).map { FakeNode(text = "x".repeat(59) + "$it") }
        val c = collector(maxTotalChars = 100)
        c.traverse(FakeNode(children = children))
        val snap = c.toCapture(null)
        assertEquals(100, snap.totalChars)
        assertEquals(2, snap.segments.size)          // 60 + 40（尾段被额度切断）
        assertEquals(60, snap.segments[0].text.length)
        assertEquals(40, snap.segments[1].text.length)
        assertFalse("被额度截断的段不能算整节点", snap.segments[1].wholeNode)
        assertTrue(snap.truncatedByLimit)
    }

    @Test
    fun 段数上限只留前N段() {
        val children = (1..5).map { FakeNode(text = "段$it") }
        val c = collector(maxSegments = 3)
        c.traverse(FakeNode(children = children))
        val snap = c.toCapture(null)
        assertEquals(3, snap.segments.size)
        assertTrue(snap.truncatedByLimit)
    }

    @Test
    fun 非正文标签不触发截断标志() {
        // 段数未满、字符未满，只有纯符号节点 ⇒ 不该谎报「内容较多」
        val root = FakeNode(children = listOf(FakeNode(text = "正文"), FakeNode(text = "…")))
        assertFalse(collect(root).truncatedByLimit)
    }

    // ── 视觉顺序 ───────────────────────────────────────

    @Test
    fun 按上下顺序排列而不是遍历顺序() {
        val root = FakeNode(
            children = listOf(
                FakeNode(text = "第三行", bounds = ScreenBounds(0, 200, 100, 230)),
                FakeNode(text = "第一行", bounds = ScreenBounds(0, 10, 100, 40)),
                FakeNode(text = "第二行", bounds = ScreenBounds(0, 100, 100, 130)),
            ),
        )
        assertEquals(listOf("第一行", "第二行", "第三行"), collect(root).segments.map { it.text })
    }

    @Test
    fun 同一行内按左右排列() {
        val root = FakeNode(
            children = listOf(
                FakeNode(text = "右侧", bounds = ScreenBounds(100, 10, 200, 40)),
                FakeNode(text = "左侧", bounds = ScreenBounds(10, 10, 90, 40)),
            ),
        )
        assertEquals(listOf("左侧", "右侧"), collect(root).segments.map { it.text })
    }

    @Test
    fun 上下相邻不足一个行带视为同一行() {
        val root = FakeNode(
            children = listOf(
                FakeNode(text = "右侧文本", bounds = ScreenBounds(200, 20, 300, 40)),
                FakeNode(text = "左侧文本", bounds = ScreenBounds(10, 10, 100, 30)),
            ),
        )
        // top 10 与 20 落在同一行带（24px）⇒ 按 left 排
        assertEquals(listOf("左侧文本", "右侧文本"), collect(root).segments.map { it.text })
    }

    // ── 写回所需的两个标记 ───────────────────────────────

    @Test
    fun 可编辑标记与整节点标记随节点带上() {
        val editable = FakeNode(text = "整段可编辑", isEditable = true, windowId = 42)
        val readonly = FakeNode(text = "只读文本", isEditable = false, windowId = 42)
        val snap = collect(FakeNode(children = listOf(editable, readonly)))
        val first = snap.segments[0]
        assertTrue(first.editable)
        assertTrue(first.wholeNode)
        assertEquals("整段可编辑", first.text)
        assertEquals(42, first.windowId)
        assertFalse(snap.segments[1].editable)
    }

    @Test
    fun 采集结果带上来源包名与总字数() {
        val snap = collect(FakeNode(children = listOf(FakeNode(text = "四个字"))), sourcePackage = "com.demo.app")
        assertEquals("com.demo.app", snap.sourcePackage)
        assertEquals(3, snap.totalChars)
    }

    @Test
    fun 空树得到空快照() {
        val snap = collect(FakeNode(), sourcePackage = null)
        assertEquals(0, snap.segments.size)
        assertEquals(0, snap.totalChars)
        assertFalse(snap.truncatedByLimit)
    }
}
