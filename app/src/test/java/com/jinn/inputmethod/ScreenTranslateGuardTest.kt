package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕翻译的源码钉（照 `NetworkPolicyTest` 的形态）。
 *
 * 钉的都是**行为测试覆盖不到、又必须靠代码形态保证**的红线：零事件订阅、不截屏、
 * 不申请悬浮权限、纯层不许碰框架类、结果路径只走统一翻译客户端（不得自建 OkHttp）。
 *
 * ⚠ 取值一律走 [TestSources]：
 *  · 正向钉（「这段实现必须在」）用 `codeOf` / `codeSource`（**剥注释**，注释里提到不算实现）；
 *  · 「连注释也不许提」才用 `rawSource` —— 本类里只有「不得声明某权限」这类**负向**判据用它，
 *    因为配置文件的注释里要正当地写「为什么不申请 SYSTEM_ALERT_WINDOW」。
 */
class ScreenTranslateGuardTest {

    private companion object {
        const val SERVICE = "ScreenTranslateService.kt"
        const val TILE = "ScreenTranslateTile.kt"
        const val LOGIC = "ScreenTranslateLogic.kt"
        const val COLLECTOR = "ScreenTextCollector.kt"
        const val CONFIG = "src/main/res/xml/screen_translate_config.xml"
    }

    private fun manifest(): String = TestSources.codeOf(
        TestSources.rawSource("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"),
    )

    private fun configCode(): String = TestSources.codeOf(
        TestSources.rawSource(CONFIG, "app/$CONFIG"),
    )

    private fun configRaw(): String = TestSources.rawSource(CONFIG, "app/$CONFIG")

    // ── 1. 清单登记 ─────────────────────────────────────

    @Test
    fun 清单必须登记无障碍服务与它的配置文件() {
        val m = manifest()
        assertTrue("清单缺少 ScreenTranslateService", ".ScreenTranslateService" in m)
        assertTrue("无障碍服务必须用签名级权限 BIND_ACCESSIBILITY_SERVICE", "BIND_ACCESSIBILITY_SERVICE" in m)
        assertTrue("无障碍服务缺少 accessibilityservice 的 meta-data", "android.accessibilityservice" in m)
        assertTrue("meta-data 必须指向 @xml/screen_translate_config", "@xml/screen_translate_config" in m)
        // 磁贴与中转页：磁贴是唯一入口，中转页必须不导出
        assertTrue("清单缺少磁贴 ScreenTranslateTile", ".ScreenTranslateTile" in m)
        assertTrue("磁贴必须用签名级权限 BIND_QUICK_SETTINGS_TILE", "BIND_QUICK_SETTINGS_TILE" in m)
        assertTrue("清单缺少中转页 ScreenTranslateTriggerActivity", ".ScreenTranslateTriggerActivity" in m)
        assertTrue("中转页必须 exported=false", Regex("""ScreenTranslateTriggerActivity[\s\S]{0,200}?exported="false"""").containsMatchIn(m))
    }

    // ── 2. 权限面（负向） ────────────────────────────────

    @Test
    fun 清单不得申请任何悬浮窗权限也不得新增权限() {
        val raw = TestSources.rawSource("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        // 注释剥离后再判：注释里写「不申请 SYSTEM_ALERT_WINDOW」是正当说明，但代码里不许出现
        val code = TestSources.codeOf(raw)
        assertFalse(
            "清单的代码部分不得出现 SYSTEM_ALERT_WINDOW（气泡用无障碍专属浮层类型即可）",
            code.contains("SYSTEM_ALERT_WINDOW"),
        )
        // 更广的钉：这次改动**一个 uses-permission 都不许加**（无障碍与磁贴都由系统在设置页授权）
        val permissions = Regex("""<uses-permission android:name="([^"]+)"""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "uses-permission 集合变了 —— 无障碍与磁贴都不需要新增权限，别顺手填一个",
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.RECORD_AUDIO",
                "android.permission.VIBRATE",
            ),
            permissions,
        )
    }

    // ── 3. 服务配置：零事件订阅 ──────────────────────────

    @Test
    fun 服务配置必须零事件订阅且可读窗口内容() {
        val code = configCode()
        assertFalse(
            "配置里不得出现 accessibilityEventTypes（零订阅是「不后台监听」的硬保证）",
            code.contains("accessibilityEventTypes"),
        )
        assertTrue("必须声明 canRetrieveWindowContent=true，否则读不到节点文本", "canRetrieveWindowContent=\"true\"" in code)
        assertTrue("必须开启 flagRetrieveInteractiveWindows（采集靠 windows 列表）", "flagRetrieveInteractiveWindows" in code)
        assertFalse("不得写 canTakeScreenshot=true（本功能不截屏）", "canTakeScreenshot=\"true\"" in code)
        assertTrue("必须如实声明 isAccessibilityTool=false", "isAccessibilityTool=\"false\"" in code)
        // 描述必须在（系统「无障碍」页展示，用户据此知情），且**必须引用字符串资源**：
        // 该属性是 reference 型，写字面量会被 aapt2 直接拒绝（2026-10-10 实测）
        assertTrue(
            "description 必须用 @string 引用（字面量会编译失败）",
            "android:description=\"@string/" in code,
        )
    }

    @Test
    fun 服务配置的高频事件一个都不许订() {
        val code = configCode()
        for (event in listOf("typeWindowContentChanged", "typeViewTextChanged", "typeViewClicked", "typeViewFocused")) {
            assertFalse("$event 是高频事件，与「不后台监听」相悖", code.contains(event))
        }
        // 选区事件是 v2 的档位（方案 §18.3），v1 不得出现
        assertFalse("v1 不订选区事件（选中即译属 v2 档位）", code.contains("typeViewTextSelectionChanged"))
        // 配置里的注释本身也要讲清「为什么不订」——这是文档钉，用 rawSource
        assertTrue("配置必须写明「故意不写事件类型」的理由", "故意" in configRaw() || "不写" in configRaw())
    }

    // ── 4. 服务：零订阅不得演变成偷偷采集 ────────────────

    @Test
    fun 事件回调必须是空实现() {
        val src = TestSources.codeSource(SERVICE)
        val body = src.substringAfter("override fun onAccessibilityEvent")
            .substringBefore("private fun")
        assertTrue("onAccessibilityEvent 应为空实现（= Unit）", "= Unit" in body)
        for (token in listOf("translate", "Traversal", "getText", "text")) {
            assertFalse("零订阅下事件回调不得出现「$token」（否则事件面会变成偷偷采集）", body.contains(token))
        }
    }

    @Test
    fun 结果路径只走统一翻译客户端() {
        val src = TestSources.codeSource(SERVICE)
        assertEquals(
            "TranslationClient.translate 必须恰好一处调用（唯一出口，便于审计「什么情况下会发请求」）",
            1,
            Regex("TranslationClient\\.translate\\(").findAll(src).count(),
        )
        assertFalse("不得自建 OkHttpClient（复用统一客户端才有一致的超时/错误归一/HTTPS 判据）", "OkHttpClient" in src)
        assertTrue("回调必须切回主线程（onDone 线程不确定）", "ui.post" in src)
        assertTrue("必须有代际判断（旧结果丢弃）", Regex("""if \(gen != generation\)""").containsMatchIn(src))
    }

    // ── 5. 不截屏 / 不改系统悬浮（负向钉） ─────────────────

    @Test
    fun 服务不得截屏也不得用系统悬浮窗() {
        val src = TestSources.codeSource(SERVICE)
        assertFalse("不得调用 takeScreenshot（本功能不截屏）", src.contains("takeScreenshot"))
        assertFalse("不得使用 SYSTEM_ALERT_WINDOW", src.contains("SYSTEM_ALERT_WINDOW"))
        // 浮层类型由面板持有（服务只管生命周期）：
        val panel = TestSources.codeSource("ScreenTranslatePanel.kt")
        assertTrue("浮层必须用无门槛的 TYPE_ACCESSIBILITY_OVERLAY", panel.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertTrue("浮层不得抢宿主焦点（FLAG_NOT_FOCUSABLE 必须保留）", panel.contains("FLAG_NOT_FOCUSABLE"))
        assertFalse("浮层不得加 FLAG_SECURE（用户要能截图分享译文）", panel.contains("FLAG_SECURE"))
    }

    // ── 6. 纯层不许碰框架类（可测性红线） ─────────────────

    @Test
    fun 采集层与决策层不得引用框架类() {
        val collector = TestSources.codeSource(COLLECTOR)
        assertFalse("ScreenTextCollector 必须纯 JVM 可测（不得出现 android.）", collector.contains("android."))
        assertFalse("ScreenTextCollector 不得引用 AccessibilityNodeInfo", collector.contains("AccessibilityNodeInfo"))

        val logic = TestSources.codeSource(LOGIC)
        assertFalse("ScreenTranslateLogic 不得引用 AccessibilityNodeInfo", logic.contains("AccessibilityNodeInfo"))
        assertFalse("ScreenTranslateLogic 不得引用 android.graphics", logic.contains("android.graphics"))
        // 唯一允许的 Android 符号是编译期常量（窗口类型）
        assertTrue(
            "窗口过滤必须认 AccessibilityWindowInfo.TYPE_APPLICATION",
            logic.contains("AccessibilityWindowInfo.TYPE_APPLICATION"),
        )
    }

    // ── 7. 隐私红线：密码子树跳过 ────────────────────────

    @Test
    fun 采集器必须跳过密码节点() {
        val collector = TestSources.codeSource(COLLECTOR)
        assertTrue("采集器必须含 isPassword 判据（密码节点整棵子树跳过）", collector.contains("isPassword"))
        assertTrue("密码判据必须与「不可见跳过」同处递归入口", Regex("""isPassword \|\| !node\.isVisibleToUser""").containsMatchIn(collector))
    }

    // ── 8. 磁贴入口：不得裸起 Activity ────────────────────

    @Test
    fun 磁贴只能经中转页收起快捷面板() {
        val tile = TestSources.codeSource(TILE)
        assertFalse("磁贴不得裸调 startActivity（界面被抢走的观感很差）", tile.contains("startActivity("))
        assertTrue("磁贴必须经 startActivityAndCollapse 收起快捷面板", tile.contains("startActivityAndCollapse"))
        assertTrue("Android 14+ 必须走 PendingIntent 重载（Intent 重载已废弃）", tile.contains("PendingIntent.getActivity"))
        assertTrue("磁贴状态必须随「应用内开关 + 服务已连接」刷新", tile.contains("STATE_UNAVAILABLE"))
    }

    // ── 9. 替换闸门：五闸缺一不写 ────────────────────────

    @Test
    fun 替换必须在写入前重判闸门() {
        val src = TestSources.codeSource(SERVICE)
        val replace = src.substringAfter("override fun onReplace()")
            .substringBefore("override fun onOpenSettings")
        for (token in listOf("replaceable(", "windowId", "refreshAlive()", "text?.trim()", "performSetText(")) {
            assertTrue("替换路径缺少闸门：$token", replace.contains(token))
        }
    }
}
