package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮键盘：绑定判定 / 几何夹取 / 竖横各存一套 / 关闭态不改行为。
 *
 * 这里只测**纯逻辑**（[FloatingKeyboard]）与**源码形态**：
 * 真正的窗口行为（`setGravity` / `setLayout` / `updateViewLayout` / `onComputeInsets`）只能在真机上验，
 * 已在本轮真机记录里逐项实测（竖屏 1324×960 悬浮、夹取到 (116,2156)、横屏 2016×752、`wm size reset`
 * 后按竖屏几何复原）。JVM 里没有 WindowManager，把这部分写成"能跑绿"的断言只会是假的。
 */
class FloatingKeyboardTest {

    // ──────────── 绑定判定（白名单）────────────

    @Test
    fun 开关关闭时一律不悬浮() {
        assertFalse(
            "总开关关着时即使绑了当前应用也必须走底部全宽（这是「默认关闭不改行为」的判据）",
            FloatingKeyboard.shouldFloat(
                enabled = false,
                boundPackages = setOf("com.example.game"),
                hostPackage = "com.example.game",
            ),
        )
    }

    @Test
    fun 绑定为空时开关开着也不悬浮() {
        // 语义拍板：绑定为空 ≠ 全部悬浮。开关只是「允许悬浮」，白名单才是「谁用」。
        assertFalse(
            "绑定为空时不该有任何应用悬浮",
            FloatingKeyboard.shouldFloat(true, emptySet(), "com.example.game"),
        )
        assertFalse(
            "包名为空（取不到 EditorInfo）时也不悬浮",
            FloatingKeyboard.shouldFloat(true, setOf("com.example.game"), null),
        )
    }

    @Test
    fun 只有被勾选的应用才悬浮() {
        val bound = setOf("com.example.game", "com.example.video")
        assertTrue(FloatingKeyboard.shouldFloat(true, bound, "com.example.game"))
        assertFalse(
            "未勾选的应用必须保持底部全宽（白名单语义）",
            FloatingKeyboard.shouldFloat(true, bound, "com.example.chat"),
        )
    }

    @Test
    fun 绑定列表要容忍脏分隔符并归一() {
        val parsed = FloatingKeyboard.parsePackages(" b.app ,a.app\nb.app;c.app\t\nd.app ")
        assertEquals(
            "换行 / 空格 / 分号 / 制表符都要当分隔符，重复项要去掉",
            setOf("a.app", "b.app", "c.app", "d.app"),
            parsed,
        )
        assertEquals("空串解析为空集", emptySet<String>(), FloatingKeyboard.parsePackages(""))
        assertEquals("null 解析为空集", emptySet<String>(), FloatingKeyboard.parsePackages(null))
        assertEquals(
            "序列化要稳定（排序 + 逗号），否则同一份集合每次写出不同串",
            "a.app,b.app",
            FloatingKeyboard.serializePackages(setOf("b.app", "a.app", "b.app")),
        )
        assertEquals(
            "解析 → 序列化 必须幂等",
            "a.app,b.app",
            FloatingKeyboard.serializePackages(FloatingKeyboard.parsePackages("b.app,a.app,a.app")),
        )
    }

    // ──────────── 几何：夹取与解析 ────────────

    @Test
    fun 几何夹取必须落在可用区内() {
        val g = FloatingKeyboard.Geometry(x = 5000, y = 5000, scalePercent = 70)
        val clamped = FloatingKeyboard.clamp(g, screenW = 1440, areaH = 3116, winW = 1010, winH = 760)
        assertEquals("x 不得超过 屏宽 − 窗宽", 1440 - 1010, clamped.x)
        assertEquals("y 不得超过 可用高 − 窗高", 3116 - 760, clamped.y)

        val negative = FloatingKeyboard.clamp(
            FloatingKeyboard.Geometry(-800, -800, 70), 1440, 3116, 1010, 760,
        )
        assertEquals("负坐标贴左", 0, negative.x)
        assertEquals("负坐标贴顶", 0, negative.y)

        // 窗口比屏幕还大（脏缩放 / 小屏）：夹取只能贴边，不能算出负的上界
        val oversize = FloatingKeyboard.clamp(
            FloatingKeyboard.Geometry(300, 300, 100), 800, 600, 1200, 900,
        )
        assertEquals("窗口超宽时 x 归零", 0, oversize.x)
        assertEquals("窗口超高时 y 归零", 0, oversize.y)
    }

    @Test
    fun 拖动位移先加后夹() {
        val base = FloatingKeyboard.Geometry(100, 200, 80)
        val moved = FloatingKeyboard.dragTo(base, dx = 50, dy = -50, screenW = 1440, areaH = 3116, winW = 1000, winH = 800)
        assertEquals(150, moved.x)
        assertEquals(150, moved.y)
        val stuck = FloatingKeyboard.dragTo(base, dx = -1000, dy = 5000, screenW = 1440, areaH = 3116, winW = 1000, winH = 800)
        assertEquals("拖出左边界要夹住", 0, stuck.x)
        assertEquals("拖出下边界要夹住", 3116 - 800, stuck.y)
    }

    @Test
    fun 脏几何串回落默认() {
        assertNull("空串没有几何", FloatingKeyboard.parseGeometry(""))
        assertNull("null 没有几何", FloatingKeyboard.parseGeometry(null))
        assertNull("段数不对要丢弃", FloatingKeyboard.parseGeometry("10,20"))
        assertNull("非数字要丢弃", FloatingKeyboard.parseGeometry("10,abc,80"))
        assertNull("夸张坐标（脏备份）要丢弃", FloatingKeyboard.parseGeometry("999999,20,80"))
        val g = FloatingKeyboard.parseGeometry("116,2156,92")
        assertEquals(FloatingKeyboard.Geometry(116, 2156, 92), g)
        assertEquals("序列化 / 解析必须往返一致", "116,2156,92", FloatingKeyboard.serializeGeometry(g!!))
        assertEquals(
            "越界缩放要钳到定义域",
            (FloatingKeyboard.MIN_SCALE * 100).toInt(),
            FloatingKeyboard.parseGeometry("1,1,1")!!.scalePercent,
        )
    }

    @Test
    fun 默认缩放同时受宽度比例与高度上限约束() {
        // 竖屏 1440×3200：自然高 945，宽度比例 92% 不被高度上限卡住
        assertEquals(
            92,
            FloatingKeyboard.defaultScalePercent(
                naturalW = 1440, naturalH = 945, screenW = 1440, screenH = 3200, portrait = true,
            ),
        )
        // 横屏 2880×1440：宽度 70%，高度 0.6×1440/945 ≈ 91%，取小 = 70%（高度落在 52%）
        val landscape = FloatingKeyboard.defaultScalePercent(
            naturalW = 2880, naturalH = 945, screenW = 2880, screenH = 1440, portrait = false,
        )
        assertEquals(70, landscape)
        assertTrue(
            "横屏缩放必须让高度不超过屏高 60%（945 × 70% ≈ 662 < 864）",
            945 * landscape / 100 <= (1440 * FloatingKeyboard.MAX_HEIGHT_FRACTION).toInt(),
        )
        // 极矮屏：高度上限接管，且不得跌破缩放下限
        val squeezed = FloatingKeyboard.defaultScalePercent(
            naturalW = 1440, naturalH = 945, screenW = 1440, screenH = 900, portrait = true,
        )
        assertEquals(
            "高度上限接管时取 0.6×900/945 ≈ 57%",
            57,
            squeezed,
        )
        assertEquals(
            "离谱输入回落 100%",
            100,
            FloatingKeyboard.defaultScalePercent(0, 0, 1440, 3200, true),
        )
    }

    @Test
    fun 默认位置水平居中贴底() {
        val g = FloatingKeyboard.defaultGeometry(
            scalePercent = 92, naturalW = 1440, naturalH = 945, screenW = 1440, areaH = 3116 - 91,
        )
        val w = (1440 * 92 / 100f).toInt()
        val h = (945 * 92 / 100f).toInt()
        assertEquals("水平居中", (1440 - w) / 2, g.x)
        assertEquals("贴可用区底部", (3116 - 91) - h, g.y)
    }

    @Test
    fun 换档后继续拖动的基线必须换成新几何() {
        // 真机两次复现（竖屏 92%→62%，长按换档后手指稍有位移）：位移仍按换档前的 x/y 算 ⇒
        // 窗口被甩回换档前的位置；抬手时又把旧档位写回存档（屏幕 62%、存档 92%），
        // 下次弹键盘回到 92% ——「长按把手切换大小」等于没生效。
        val move = TestSources.blockAfter(TestSources.codeSource("JinnIme.kt"), "MotionEvent.ACTION_MOVE ->")
        assertTrue(
            "ACTION_MOVE 必须先认出「几何被外部改写」并重取基线：current != floatingDragApplied → floatingDragOrigin = current",
            "current != floatingDragApplied" in move && "floatingDragOrigin = current" in move,
        )
        assertTrue(
            "每次拖动写完窗口都要同步基线，否则下一帧会把自己刚写的位置当成「外部改写」",
            "floatingDragApplied = floatingGeometry" in move,
        )
        // 弱点（与其余形态钉相同）：它只能挡「删掉这段」，挡不住「条件永远为假」。
        // 行为判据在真机：长按换档后继续拖 → 位置不跳，存档档位与屏幕一致。
    }

    @Test
    fun 长按换档要跳到相邻档() {
        assertEquals("62 的下一档是 78", 78, FloatingKeyboard.nextScaleStop(62))
        assertEquals("78 的下一档是 92", 92, FloatingKeyboard.nextScaleStop(78))
        assertEquals("92 之后回到最小档", 62, FloatingKeyboard.nextScaleStop(92))
        assertEquals("70（横屏默认）的下一档是 78", 78, FloatingKeyboard.nextScaleStop(70))
    }

    @Test
    fun 放大后旧坐标必须重夹() {
        // 62% 档贴底时的合法 y（窗高 676 = 91 把手 + 945×62%）；放大到 92%（窗高 960）后
        // 同一个 y 会让底边越出可用区 284px ⇒ 夹取必须把它推回来。
        val at62 = FloatingKeyboard.Geometry(x = 0, y = 3116 - 676, scalePercent = 62)
        val grown = FloatingKeyboard.clamp(at62, screenW = 1440, areaH = 3116, winW = 1324, winH = 960)
        assertEquals("放大到 92% 后必须重夹到底（不重夹就有 284px 在屏外）", 3116 - 960, grown.y)
        assertEquals("x 没越界时不许被顺手挪动", 0, grown.x)
        // 缩小相反方向：位置仍然合法就不该被改动（夹取不是「重新居中」）
        val shrunk = FloatingKeyboard.clamp(FloatingKeyboard.Geometry(0, 100, 92), 1440, 3116, 892, 676)
        assertEquals("缩小后仍在屏内 ⇒ 位置原样保留", 100, shrunk.y)
    }

    @Test
    fun 绑定列表里的失效包名要能被清掉() {
        val bound = setOf("a.app", "b.app", "gone.app")
        val available = setOf("a.app", "b.app", "c.app")
        assertEquals(
            "只清「应用列表里查不到」的包名，其余绑定必须原样留着",
            setOf("gone.app"),
            FloatingKeyboard.stalePackages(bound, available),
        )
        assertEquals("应用全在时没有可清的", emptySet<String>(), FloatingKeyboard.stalePackages(bound, bound))
        assertEquals(
            "应用列表为空（查询失败 / 系统抖动）时一个绑定都不许清：" +
                "照算差集会把整份绑定写没，页面只写一次盘、退不回来",
            emptySet<String>(),
            FloatingKeyboard.stalePackages(bound, emptySet()),
        )
        assertEquals(
            "清理后的列表再序列化，不能留下空段 / 重复项",
            "a.app,b.app",
            FloatingKeyboard.serializePackages(bound - FloatingKeyboard.stalePackages(bound, available)),
        )
        // 光有纯函数不算修好：页面必须在渲染前真的调用它，否则「已绑定 N 个」照样虚高
        assertTrue(
            "FloatingKeyboardActivity 必须在渲染前清理失效绑定",
            "pruneStaleBindings(apps)" in TestSources.codeSource("FloatingKeyboardActivity.kt"),
        )
    }

    // ──────────── 竖横各存一套 / 关闭态不改行为（源码形态）────────────

    @Test
    fun 两个方向的几何必须各存一套() {
        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue(
            "竖屏 / 横屏必须是两个独立的键（横屏比例不同，不能共用一套）",
            "KEY_FLOATING_GEOMETRY_PORTRAIT" in prefs && "KEY_FLOATING_GEOMETRY_LANDSCAPE" in prefs,
        )
        assertTrue(
            "按方向取键的分支必须存在（读侧与写侧共用同一处判断）",
            "private fun floatingGeometryKey(portrait: Boolean)" in prefs,
        )
        // 读写两侧都要出现这两个键：只加一侧会让换机后几何静默丢失
        val export = TestSources.blockAfter(prefs, "internal fun exportForBackup(")
        val import = TestSources.blockAfter(prefs, "internal fun importFromBackup(")
        for (key in listOf("KEY_FLOATING_GEOMETRY_PORTRAIT", "KEY_FLOATING_GEOMETRY_LANDSCAPE")) {
            assertTrue("$key 不在导出白名单里", key in export)
            assertTrue("$key 不在导入白名单里", key in import)
        }
        // 键都在还不够：**方向对应关系写反**（portrait 分支返回横屏键）时上面几条全绿，
        // 而「竖横各存一套」直接退化成两方向共用一个键 —— 必须钉住哪个方向进哪个键。
        // 该函数是单表达式（没有花括号），不能用 blockAfter，按行取声明 + 紧随的表达式。
        val lines = prefs.lines()
        val at = lines.indexOfFirst { "private fun floatingGeometryKey(" in it }
        assertTrue("Prefs.kt 找不到 floatingGeometryKey 的声明", at >= 0)
        val keyFn = lines.subList(at, minOf(at + 3, lines.size)).joinToString("\n")
        assertTrue(
            "竖屏分支必须返回竖屏键（写反 = 两方向共用一套）",
            "KEY_FLOATING_GEOMETRY_PORTRAIT" in keyFn.substringBefore("else"),
        )
        assertTrue(
            "横屏分支必须返回横屏键（写反 = 两方向共用一套）",
            "KEY_FLOATING_GEOMETRY_LANDSCAPE" in keyFn.substringAfter("else"),
        )
        assertTrue("悬浮开关不在导出白名单里", "KEY_FLOATING_ENABLED" in export)
        assertTrue("绑定列表不在导入白名单里", "KEY_FLOATING_APPS" in import)
    }

    @Test
    fun 关闭态必须恢复普通窗口参数且不报浮层insets() {
        val ime = TestSources.codeSource("JinnIme.kt")
        val body = TestSources.blockAfter(ime, "private fun applyFloatingWindow(")
        assertTrue(
            "关闭态且从未悬浮过时必须直接返回：每会话多一次 updateViewLayout 就是一次多余重排，" +
                "违背「默认关闭 = 与历史逐像素一致」",
            "if (!floatingApplied) return" in body,
        )
        assertTrue(
            "关闭态必须把窗口参数还原成首次改窗口前的快照（否则关掉开关后键盘仍停在浮层位置）",
            "lp.gravity = defaultGravity" in body &&
                "lp.width = defaultWidth" in body &&
                "lp.height = defaultHeight" in body,
        )
        assertTrue(
            "关闭态必须连 x/y 一起还原（只还原 gravity / 宽高会让窗口停在旧位置）",
            "lp.x = defaultX" in body && "lp.y = defaultY" in body,
        )
        assertTrue("关闭态必须把把手收起", "handle?.visibility = View.GONE" in body)
        assertTrue("关闭态必须撤销视图缩放", "container.scaleX = 1f" in body && "container.scaleY = 1f" in body)
        val insets = TestSources.blockAfter(ime, "override fun onComputeInsets(")
        assertTrue(
            "非悬浮时必须原样交回框架自己算的 insets（不接管宿主可视区）",
            "if (!floatingActive) return" in insets,
        )
        assertTrue(
            "悬浮态要把宿主可视区报成整屏高，否则宿主仍按「底部全宽键盘」把画面缩上去",
            "insets.contentTopInsets = fullHeight" in insets && "insets.visibleTopInsets = fullHeight" in insets,
        )
        assertTrue(
            "可触区必须是窗口内局部坐标（真机实证：填绝对坐标会被框架再加一遍，按键全部点不中）",
            "insets.touchableRegion.set(0, 0, floatingWinW, floatingWinH)" in insets,
        )
    }

    @Test
    fun 悬浮只在判定为真时才碰窗口() {
        val ime = TestSources.codeSource("JinnIme.kt")
        val resolve = TestSources.blockAfter(ime, "private fun resolveFloatingState(")
        assertTrue(
            "悬浮判定必须走 FloatingKeyboard.shouldFloat（不要在 IME 里另写一套判据）",
            "FloatingKeyboard.shouldFloat(" in resolve,
        )
        assertTrue(
            "「回到底部」的进程内豁免要参与判定（否则按了还会浮起来）",
            "pkg !in floatingDocked" in resolve,
        )
        val onStart = TestSources.blockAfter(ime, "override fun onStartInputView(")
        assertTrue("onStartInputView 必须重算悬浮态", "resolveFloatingState(info)" in onStart)
        assertTrue("onStartInputView 必须套一次窗口形态", "applyFloatingWindow()" in onStart)
        // 框架在「同会话内换框 / restartInput()」时可能不重发 onStartInputView（同文件密码框那条注释），
        // 只判一次会让分屏 / 连续换框期间的形态停在上一个输入框的答案上。
        val onStartInput = TestSources.blockAfter(ime, "override fun onStartInput(")
        assertTrue("onStartInput 也必须判定悬浮态", "resolveFloatingState(info)" in onStartInput)
        assertTrue(
            "onStartInput 只在状态真的变了时动窗口（同包换框不该多一次 relayout）",
            "if (wasFloating != floatingActive) applyFloatingWindow()" in onStartInput,
        )
    }
}
