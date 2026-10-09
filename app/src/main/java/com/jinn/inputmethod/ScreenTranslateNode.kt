package com.jinn.inputmethod

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * [TextNode] 的生产实现：一层属性映射 + `ACTION_SET_TEXT`。
 *
 * 保持极薄（无业务逻辑）：引入 Robolectric 只为测这几行映射不值当（仓内测试依赖只有
 * junit + org.json），所有判定都写在 [ScreenTextCollector] / [ScreenTranslateLogic] 里，
 * 那两处 100% 可在纯 JVM 下对拍。
 *
 * **失败口径（异常处理的第一道，逐条有理由）**：采集期间宿主界面可能正在重建（滚动、页面切换、
 * 应用被杀），任何一次读都可能抛 `IllegalStateException`（节点已失效）或 `SecurityException`
 * （窗口被撤权）。逐项包 `runCatching` 而不是让整次采集崩掉，回退值一律取**保守侧**：
 *  · `text` / `contentDescription` 读不到 ⇒ null（当没有内容）；
 *  · `isPassword` 读不到 ⇒ **true**（宁可当密码整棵子树跳过，也不赌它不是密码）；
 *  · `isVisibleToUser` 读不到 ⇒ false（当不可见跳过）；
 *  · `isEditable` 读不到 ⇒ false（不给「替换原文」，只给复制）；
 *  · `windowId` 读不到 ⇒ -1（与任何活动窗口都不相等 ⇒ 替换闸门自动拒绝）；
 *  · `bounds` 读不到 ⇒ 零矩形（只影响排序位次，不影响内容）。
 *
 * 不调用 `recycle()`：API 33 起已废弃，且本方案是「一次采集、随即丢弃」的短生命周期；
 * 只有被勾选的那一段节点会被持有到替换时刻，使用前一律 `refresh()` 验活（见服务层）。
 */
internal class A11yTextNode(private val n: AccessibilityNodeInfo) : TextNode {

    /**
     * 文本缓存：`extractText` 与 `isWholeNode` 会各读一次 —— 缓存让两次读到**同一份**文本，
     * 避免节点在两读之间被宿主改写而把「非整节点」判成整节点（替换会吞掉节点其余内容）。
     *
     * ⚠ 不能用 `by lazy`（不可失效）：[refreshAlive] 成功后必须丢弃缓存，否则「替换前比对原文」
     * 读到的还是取回那一刻的旧值 —— 拿旧值跟旧值比必然相等，闸门等于没有。
     */
    private var cachedText: String? = null
    private var textReady = false

    private val boundsCache: ScreenBounds by lazy {
        runCatching {
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            ScreenBounds(r.left, r.top, r.right, r.bottom)
        }.getOrDefault(ScreenBounds.ZERO)
    }

    override val text: String?
        get() {
            if (!textReady) {
                cachedText = runCatching { n.text?.toString() }.getOrNull()
                textReady = true
            }
            return cachedText
        }

    override val contentDescription: String?
        get() = runCatching { n.contentDescription?.toString() }.getOrNull()

    override val isPassword: Boolean get() = runCatching { n.isPassword }.getOrDefault(true)

    override val isVisibleToUser: Boolean get() = runCatching { n.isVisibleToUser }.getOrDefault(false)

    override val isEditable: Boolean get() = runCatching { n.isEditable }.getOrDefault(false)

    override val windowId: Int get() = runCatching { n.windowId }.getOrDefault(-1)

    override val bounds: ScreenBounds get() = boundsCache

    override val childCount: Int get() = runCatching { n.childCount }.getOrDefault(0)

    override fun childAt(index: Int): TextNode? =
        runCatching { n.getChild(index) }.getOrNull()?.let { A11yTextNode(it) }

    override fun performSetText(value: String): Boolean {
        if (!isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        // 节点失效会抛 IllegalStateException、应用拒绝会返回 false —— 两种都如实归 false，
        // 由服务层提示「该控件拒绝了写入，可改用复制」，绝不重试。
        return runCatching { n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    /** 替换前的验活：刷新失败或节点已从窗口树上摘下 ⇒ 本次写入不可信（服务层据此拒绝）。 */
    override fun refreshAlive(): Boolean {
        val ok = runCatching { n.refresh() }.getOrDefault(false)
        if (ok) textReady = false   // 刷新成功 ⇒ 丢弃文本缓存，让比对读到宿主当前值
        return ok
    }
}
