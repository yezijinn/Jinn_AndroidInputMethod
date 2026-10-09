package com.jinn.inputmethod

/**
 * 屏幕坐标（**不用 `android.graphics.Rect`**：它在 JVM 单测下方法是 `Stub!`，
 * 纯层带上它会让排序测试静默失真 —— 假节点造出来的矩形全是 0）。
 */
internal data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object { val ZERO = ScreenBounds(0, 0, 0, 0) }
}

/**
 * 采集器面向的最小节点接口（只为「可单测」而存在）。
 *
 * 生产实现是 `ScreenTranslateNode.kt` 的 [A11yTextNode]；测试实现是纯 Kotlin 假节点。
 * ⚠ 本文件**禁止** import 任何 `android.*` 类（守卫钉 5）—— 一旦混进框架类，
 * 那些逻辑就只能靠真机手测，仓内测试依赖（junit + org.json）撑不起 Robolectric。
 */
internal interface TextNode {
    val text: String?
    val contentDescription: String?
    val isPassword: Boolean
    val isVisibleToUser: Boolean
    val isEditable: Boolean
    val windowId: Int
    val bounds: ScreenBounds
    val childCount: Int
    fun childAt(index: Int): TextNode?

    /** 写回：生产实现转 `performAction(ACTION_SET_TEXT)`；节点失效等失败一律返回 false */
    fun performSetText(value: String): Boolean

    /**
     * 让本节点后续的属性读取拿到**宿主当前**的真实值（生产实现转 `refresh()`；失效返回 false）。
     *
     * 为什么必须要有它：从窗口树取到的节点是「封存」（sealed）的快照，属性读的是取回那一刻的值 ——
     * 不刷新就比对「原文是否变过」，等于拿旧值跟旧值比，**必然相等**，替换闸门形同虚设。
     * 只在这一处使用：用户点「替换原文」的那一刻。
     */
    fun refreshAlive(): Boolean
}

/** 采集到的一段文本。 */
internal class ScreenSegment(
    /** 已 trim 的显示 / 上传文本 */
    val text: String,
    /** 写回用（接口，非框架类） */
    val node: TextNode,
    /** 替换前校验「窗口没被切换」 */
    val windowId: Int,
    /** 节点可编辑（`ACTION_SET_TEXT` 的必要条件） */
    val editable: Boolean,
    /** 段文本 == 节点全文（trim 后）⇒ 才允许整节点替换 */
    val wholeNode: Boolean,
)

/** 一次采集的结果（不可变；面板与服务只读它）。 */
internal class ScreenCapture(
    val segments: List<ScreenSegment>,
    val totalChars: Int,
    /** 四重上限任一触顶 ⇒ 面板提示「内容较多，仅列出前 N 段」 */
    val truncatedByLimit: Boolean,
    /** 来源应用包名（面板首行展示，用户据此确认「采的是哪个 App」） */
    val sourcePackage: String?,
) {
    companion object { val EMPTY = ScreenCapture(emptyList(), 0, false, null) }
}

/**
 * UI 树文本采集器（纯逻辑）。设计约束逐条对应一类缺陷：
 *  ① 节点 / 深度 / 字符 / 段数**四重上限**：与输入法同进程，长列表页面不设限会 OOM；
 *  ② 密码节点**整棵子树**跳过（递归入口判，不是叶子判）；
 *  ③ 不可见节点跳过（滚出屏的内容不该进列表，更不该被上传）；
 *  ④ 去重：同一段文本被父子节点重复上报时只留第一条；
 *  ⑤ 排序：行带归并 + 行内 left（DFS 顺序在 ConstraintLayout 下不等于视觉顺序）。
 *
 * 线程模型：**非线程安全**，一次采集用一个实例、只在主线程跑完（无跨采集共享状态）。
 * 采集期间宿主界面可能正在变化 ⇒ 节点读失败由 [A11yTextNode] 归一成「不可见 / 无内容」，
 * 本层不需要 try/catch。
 */
internal class ScreenTextCollector(
    /** 行带阈值（`24dp × density`），由调用方给：纯层不碰 displayMetrics */
    private val bandPx: Int,
    private val maxNodes: Int = MAX_NODES,
    private val maxDepth: Int = MAX_DEPTH,
    private val maxTotalChars: Int = MAX_TOTAL_CHARS,
    private val maxSegments: Int = MAX_SEGMENTS,
) {
    companion object {
        /** 单次采集访问的节点上限（防御长列表 / 大页面） */
        const val MAX_NODES = 1500

        /** UI 树最大深度（防御异常深的树） */
        const val MAX_DEPTH = 32

        /**
         * 单次采集的字符上限：**与上传上限解耦**。
         *
         * 复用一个 10 万字符的常量（旧稿 v0-B 的做法）是错的：整屏采 10 万字符要遍历数千节点，
         * 且一次请求就按 10 万字符计费。4000 字符已覆盖整屏正文，超出多半是长文 / 列表，
         * 整屏翻既不必要也不经济。
         */
        const val MAX_TOTAL_CHARS = 4000

        /** 面板可勾选的段数上限（超出丢尾部并置截断标志） */
        const val MAX_SEGMENTS = 80
    }

    private class Raw(
        val text: String,
        val node: TextNode,
        val wholeNode: Boolean,
        val bounds: ScreenBounds,
    ) {
        val editable: Boolean get() = node.isEditable
        val windowId: Int get() = node.windowId
    }

    private val raw = ArrayList<Raw>()
    private val seen = HashSet<String>()
    private var visited = 0
    private var chars = 0
    private var truncated = false

    /** 已采段数（供调用方判「要不要建面板」；`toCapture` 之前也可读） */
    val size: Int get() = raw.size

    /**
     * 深度优先遍历（递归深度由 [maxDepth] 封顶，不会爆栈）。
     *
     * 上限判定的**精确口径**：只有「本来可采的内容确实被丢」才置截断标志 ——
     * 段数满时又遇到非正文的标签（`…` / `》`）不算截断，否则面板会常年挂着一句误导的提示。
     */
    fun traverse(node: TextNode, depth: Int = 0) {
        if (depth > maxDepth) {
            truncated = true
            return
        }
        if (visited >= maxNodes) {
            truncated = true
            return
        }
        visited++
        // ② 密码节点整子树跳过；③ 不可见跳过
        if (node.isPassword || !node.isVisibleToUser) return

        val text = extractText(node)
        if (text != null && ScreenTranslateLogic.isCollectable(text)) {
            val remaining = maxTotalChars - chars
            when {
                raw.size >= maxSegments -> truncated = true
                // 余量放不下一个可读段（< 2 字符）时不再塞半截噪声
                remaining < 2 -> truncated = true
                text.length > remaining -> {
                    // 尾段被额度截断：仍然列出（否则超长正文会一段都看不到），但**不能算整节点**
                    truncated = true
                    raw.add(Raw(text.substring(0, remaining), node, wholeNode = false, bounds = node.bounds))
                    chars = maxTotalChars
                }
                else -> {
                    raw.add(
                        Raw(
                            text = text,
                            node = node,
                            wholeNode = ScreenTranslateLogic.isWholeNode(text, node.text),
                            bounds = node.bounds,
                        ),
                    )
                    chars += text.length
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.childAt(i) ?: continue
            traverse(child, depth + 1)
        }
    }

    /** text 优先、contentDescription 兜底；trim 后为空或**已出现过**则丢弃（④）。 */
    private fun extractText(node: TextNode): String? {
        val rawText = node.text?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.takeIf { it.isNotBlank() }
        val trimmed = rawText?.trim() ?: return null
        if (!seen.add(trimmed)) return null
        return trimmed
    }

    /** 收口：按（行带, left）排序并封成 [ScreenCapture]。多次调用返回同一结果（幂等，不再改内部状态）。 */
    fun toCapture(sourcePackage: String?): ScreenCapture {
        raw.sortWith(
            compareBy({ ScreenTranslateLogic.bandOf(it.bounds.top, bandPx) }, { it.bounds.left }),
        )
        val segments = raw.map { ScreenSegment(it.text, it.node, it.windowId, it.editable, it.wholeNode) }
        return ScreenCapture(segments, chars, truncated, sourcePackage)
    }
}
