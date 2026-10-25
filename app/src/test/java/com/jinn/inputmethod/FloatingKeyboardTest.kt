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
        val g = FloatingKeyboard.Geometry(5000, 5000, 70)
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
            FloatingKeyboard.parseGeometry("1,1,1")!!.widthPercent,
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
            widthPercent = 92, naturalW = 1440, naturalH = 945, screenW = 1440, areaH = 3116 - 91,
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
        val at62 = FloatingKeyboard.Geometry(0, 3116 - 676, 62)
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
            "onStartInput 只在状态变化或窗口被外部重置时才动窗口（同包换框不该多一次 relayout）",
            "wasFloating != floatingActive || drifted" in onStartInput,
        )
        // 漂移判据：第三方 APP 悬浮窗在场时杀掉它，框架会把 IME 窗口重置成
        // `(x,y)(fillxwrap)` —— x/y 还是上次的、宽高参数丢了（真机实证：1080x922 → fillxwrap）。
        // 不识别这一项的后果是键盘内容按自然宽高撑开，用户看到「悬浮键盘位移 / 变形」。
        val drift = TestSources.blockAfter(ime, "private fun floatingWindowDrifted(")
        assertTrue("漂移判据必须比宽高（fillxwrap 的宽高与悬浮态不同）", "lp.width != floatingWinW" in drift)
        assertTrue("漂移判据必须比 gravity", "lp.gravity" in drift)
        assertTrue(
            "漂移判据必须真的参与判定（只在正文里提到不算，必须进 if 条件）",
            "if (floatingApplied && floatingActive && floatingWindowDrifted(lp))" in
                TestSources.blockAfter(ime, "private fun applyFloatingWindow("),
        )
        assertTrue(
            "判定为真时必须把 floatingApplied 复位（否则下一轮仍以为窗口是好的）",
            "floatingApplied = false" in TestSources.blockAfter(ime, "private fun applyFloatingWindow("),
        )
    }

    // ──────────── 四角拉伸 / 新格式 / 把手外观（2026-10-23）────────────

    @Test
    fun 新格式解析并兼容旧三段() {
        // 旧存档（3 段）= 等比：宽高同值，读出来必须与旧行为逐像素一致
        val legacy = FloatingKeyboard.parseGeometry("116,2156,92")
        assertEquals(FloatingKeyboard.Geometry(116, 2156, 92), legacy)
        assertEquals(
            "等比几何仍写旧的三段格式（旧版本读得懂，也是用户存档的原样）",
            "116,2156,92",
            FloatingKeyboard.serializeGeometry(legacy!!),
        )
        // 四角拉过之后（4 段）：宽高各自独立
        val stretched = FloatingKeyboard.parseGeometry("116,2156,72,138")
        assertEquals(72, stretched!!.widthPercent)
        assertEquals(138, stretched.heightPercent)
        assertEquals(
            "四段几何要原样往返（否则下次弹出就回到等比）",
            "116,2156,72,138",
            FloatingKeyboard.serializeGeometry(stretched),
        )
        assertNull("五段不是合法格式", FloatingKeyboard.parseGeometry("1,2,3,4,5"))
        assertNull("四段里非数字要丢弃", FloatingKeyboard.parseGeometry("1,2,3,x"))
        assertEquals(
            "高度越界钳到上限",
            FloatingKeyboard.MAX_HEIGHT_PERCENT,
            FloatingKeyboard.parseGeometry("1,1,100,999")!!.heightPercent,
        )
        assertEquals(
            "宽度越界钳到下限（1% 的键盘点不中任何键）",
            FloatingKeyboard.MIN_PERCENT,
            FloatingKeyboard.parseGeometry("1,1,1,1")!!.widthPercent,
        )
        // 等比判据（长按换档按它取下一档）
        assertEquals("宽高不同的几何取较小那边", 72, stretched.uniformPercent)
        assertEquals("等比几何两边同值", 92, legacy.uniformPercent)
    }

    @Test
    fun 四角拉伸宽高独立且对角固定() {
        val origin = FloatingKeyboard.Geometry(200, 800, 92, 92)
        val naturalW = 1440
        val naturalH = 945
        val oldW = FloatingKeyboard.windowWidthPx(naturalW, 92)
        val oldH = FloatingKeyboard.contentHeightPx(naturalH, 92)
        // 右下角：往右下拖 ⇒ 宽高一起长，左上角不动
        val br = FloatingKeyboard.resizeTo(
            origin, FloatingKeyboard.Corner.BOTTOM_END, 60, 40, oldW, oldH, naturalW, naturalH,
        )
        assertTrue("往右拖必须变宽", br.widthPercent > 92)
        assertTrue("往下拖必须变高", br.heightPercent > 92)
        assertEquals("拖右下角时左上角不动", 200, br.x)
        assertEquals("拖右下角时左上角不动", 800, br.y)
        // 左上角：往左上拖 ⇒ 右边缘 / 下边缘固定（x / y 反向补偿，否则整块往右下缩）
        val tl = FloatingKeyboard.resizeTo(
            origin, FloatingKeyboard.Corner.TOP_START, -60, -40, oldW, oldH, naturalW, naturalH,
        )
        assertTrue("往左上拖要变大", tl.widthPercent > 92 && tl.heightPercent > 92)
        assertEquals(
            "拖左上角时右边缘必须固定",
            200 + oldW,
            tl.x + FloatingKeyboard.windowWidthPx(naturalW, tl.widthPercent),
        )
        assertEquals(
            "拖左上角时下边缘必须固定",
            800 + oldH,
            tl.y + FloatingKeyboard.contentHeightPx(naturalH, tl.heightPercent),
        )
        // 起点尺寸取**实际值**（不是百分比反算的模型值）：差几像素时下边缘不许跟着漂
        val drifted = FloatingKeyboard.resizeTo(
            origin, FloatingKeyboard.Corner.TOP_START, 0, -40, oldW, oldH - 7, naturalW, naturalH,
        )
        assertEquals(
            "对角锚点必须按实际内容高算（否则每拖一次下边缘就漂几像素）",
            800 + oldH - 7,
            drifted.y + FloatingKeyboard.contentHeightPx(naturalH, drifted.heightPercent),
        )
        // 两条轴互不串味：只拖横边不动高度、只拖竖边不动宽度
        val onlyWide = FloatingKeyboard.resizeTo(
            origin, FloatingKeyboard.Corner.BOTTOM_END, 80, 0, oldW, oldH, naturalW, naturalH,
        )
        assertEquals("只拖横边不许改高度", 92, onlyWide.heightPercent)
        val onlyTall = FloatingKeyboard.resizeTo(
            origin, FloatingKeyboard.Corner.BOTTOM_END, 0, 40, oldW, oldH, naturalW, naturalH,
        )
        assertEquals("只拖竖边不许改宽度", 92, onlyTall.widthPercent)
        // 零位移必须完全不动（否则抬手那一刻会自己跳一格）
        val still = FloatingKeyboard.resizeTo(
            origin, FloatingKeyboard.Corner.TOP_END, 0, 0, oldW, oldH, naturalW, naturalH,
        )
        assertEquals(origin, still)
    }

    @Test
    fun 拖角时对边必须钉在实际生效的尺寸上() {
        // 真机缺陷：顶边往下拖过高度下限后，面板尺寸不变却整体下沉 100px。
        // 根因是位置按「百分比 × 自然高」的**模型值**补偿，而落地高要过倍率夹取与逐行取整。
        val originRight = 200 + 1324
        val originBottom = 800 + 960
        val model = FloatingKeyboard.Geometry(400, 1200, 92, 62)
        // 62% 的目标高被 MIN_STRETCH 夹住后，真正落地的是 648（不是模型算的 585）
        val appliedWinH = 91 + 648
        val pinned = FloatingKeyboard.pinToEdges(
            model, FloatingKeyboard.Corner.TOP_START, originRight, originBottom, 1324, appliedWinH,
        )
        assertEquals("拖左上角时右边缘必须不动", originRight, pinned.x + 1324)
        assertEquals("拖左上角时下边缘必须不动（模型高被夹住也不许整块平移）", originBottom, pinned.y + appliedWinH)
        assertEquals("只有位置被纠正，百分比不许被动", 62, pinned.heightPercent)
        // 拖右上的角：右边缘本来就在动，只能钉下边缘
        val topEnd = FloatingKeyboard.pinToEdges(
            model, FloatingKeyboard.Corner.TOP_END, originRight, originBottom, 1100, appliedWinH,
        )
        assertEquals("拖右上角不许把右边缘拉回起点", 400, topEnd.x)
        assertEquals("拖右上角时下边缘同样必须不动", originBottom, topEnd.y + appliedWinH)
        // 下两角不动上边缘：y 保持 resizeTo 的值
        val bottomEnd = FloatingKeyboard.pinToEdges(
            model, FloatingKeyboard.Corner.BOTTOM_END, originRight, originBottom, 1100, appliedWinH,
        )
        assertEquals("拖下角不许改窗顶", 1200, bottomEnd.y)
    }

    @Test
    fun 四角拉伸的尺寸定义域不许越界() {
        val naturalW = 1440
        val naturalH = 945
        val handleH = 91
        val origin = FloatingKeyboard.Geometry(0, 0, 92, 92)
        val oldW = FloatingKeyboard.windowWidthPx(naturalW, origin.widthPercent)
        val oldH = FloatingKeyboard.contentHeightPx(naturalH, origin.heightPercent)
        val huge = FloatingKeyboard.resizeTo(
            origin, FloatingKeyboard.Corner.BOTTOM_END, 5000, 5000, oldW, oldH, naturalW, naturalH,
        )
        assertEquals(
            "宽度最多铺满屏幕（自然宽 = 屏宽）",
            FloatingKeyboard.MAX_WIDTH_PERCENT,
            huge.widthPercent,
        )
        assertEquals("高度上限", FloatingKeyboard.MAX_HEIGHT_PERCENT, huge.heightPercent)
        val tiny = FloatingKeyboard.resizeTo(
            origin, FloatingKeyboard.Corner.TOP_START, 5000, 5000, oldW, oldH, naturalW, naturalH,
        )
        assertEquals("宽度下限", FloatingKeyboard.MIN_PERCENT, tiny.widthPercent)
        assertEquals("高度下限", FloatingKeyboard.MIN_PERCENT, tiny.heightPercent)
        // 拉到最大后再夹位置：窗口仍要完整落在可用区内
        val winW = FloatingKeyboard.windowWidthPx(naturalW, huge.widthPercent)
        val winH = handleH + FloatingKeyboard.contentHeightPx(naturalH, huge.heightPercent)
        val clamped = FloatingKeyboard.clamp(huge, 1440, 3116, winW, winH)
        assertTrue("夹取后不得越出可用区", clamped.x >= 0 && clamped.y >= 0)
        assertTrue("宽度不得超出屏幕", clamped.x + winW <= 1440)
        assertTrue("高度不得超出可用区", clamped.y + winH <= 3116)
    }

    @Test
    fun 拉伸倍率只作用在可伸缩块上() {
        // 内容 = 固定块（候选栏 / 上边距）+ 倍率 × 可伸缩块（字母行 + 底排）
        val chrome = 255
        val block = 690
        val naturalH = chrome + block
        val plain = FloatingKeyboard.stretchFactorFor(naturalH, chrome, block)
        assertEquals("100% 必须正好是原样", 1f, plain, 1e-4f)
        assertEquals(
            "100% 的内容高要逐像素回到自然高",
            naturalH,
            FloatingKeyboard.contentHeightFor(plain, chrome, block),
        )
        val taller = FloatingKeyboard.stretchFactorFor((naturalH * 1.4f).toInt(), chrome, block)
        assertTrue("要拉高时倍率必须大于 1", taller > 1f)
        assertTrue("倍率不得越上限", taller <= FloatingKeyboard.MAX_STRETCH)
        assertEquals(
            "夹取后的高度与倍率必须互逆（否则拖动与窗口尺寸对不上）",
            (chrome + taller * block).toInt(),
            FloatingKeyboard.contentHeightFor(taller, chrome, block),
        )
        assertEquals("没有可伸缩块时倍率保持 1", 1f, FloatingKeyboard.stretchFactorFor(1200, 1200, 0), 1e-4f)
        assertEquals(
            "拉到超出上限时钳死（不留一像素的抖动）",
            FloatingKeyboard.MAX_STRETCH,
            FloatingKeyboard.stretchFactorFor(99_999, chrome, block),
            1e-4f,
        )
        assertEquals(
            "压到超出下限时钳死",
            FloatingKeyboard.MIN_STRETCH,
            FloatingKeyboard.stretchFactorFor(1, chrome, block),
            1e-4f,
        )
    }

    @Test
    fun 把手不得再有整条背板且四角默认收起() {
        val ime = TestSources.codeSource("JinnIme.kt")
        val handle = TestSources.blockAfter(ime, "private fun buildFloatingHandle(")
        // 用户原话：「不要用这样的超级加粗的底色矩形」—— 整条实心背板与它的半透明数值都不许回来
        assertFalse(
            "把手不许再铺整条底色（那是用户点名要去掉的东西）",
            "setColor(keyboardColor(R.color.kb_bg))" in handle,
        )
        assertFalse("把手背板不得复位成半透明实心条", "alpha = 0xCC" in handle)
        assertTrue(
            "中间要留一条短条（宽度 / 粗细各一个常量）",
            "FLOATING_PILL_WIDTH_DP" in handle && "FLOATING_PILL_HEIGHT_DP" in handle,
        )
        assertTrue("短条要半透明", "FLOATING_PILL_ALPHA" in handle)
        // 用户原话是「给中间位置一个短小的半透明拖动条」：短条必须相对**整条把手**居中，
        // 不能在「扣掉右端小字后的剩余宽」里居中（那会把短条推到面板 40% 处）
        assertTrue("把手根必须是 FrameLayout（短条按整条宽度居中）", "FrameLayout(this)" in handle)
        assertTrue("短条必须用 Gravity.CENTER 放在整条中间", "Gravity.CENTER," in handle)
        assertFalse("把手不得再拆出「左侧可拖区」把短条挤偏", "dragArea" in handle)
        // 颜色必须跟主题 / 皮肤同源：写死灰度在某几套皮肤上就是「看不见」或「一道脏印子」
        val chrome = TestSources.blockAfter(ime, "private fun floatingChromeColor(")
        assertTrue("取色必须走皮肤令牌", "skinColor(" in chrome)
        assertTrue("无皮肤覆盖时回落到主题的次要文字色", "R.color.text_secondary" in chrome)
        assertTrue(
            "取色必须换成不透明 RGB（抹掉 alpha 却不补 FF 会让短条与小字全透明消失）",
            "and 0x00FFFFFF" in chrome && "0xFF000000" in chrome,
        )
        // 视觉变细，热区不许跟着变细
        assertTrue(
            "整条 26dp 仍是拖动热区（视觉变细不改可拖面积）",
            "bar.setOnTouchListener" in handle,
        )
        // 「回到底部」也要接同一套监听：它在拖动路径上，只靠 click 会「一拖就误收回底部」
        assertTrue("「回到底部」必须与把手共用拖动监听", "dock.setOnTouchListener(listener)" in handle)
        val drag = TestSources.blockAfter(ime, "private fun onFloatingHandleTouch(")
        assertTrue(
            "落在「回到底部」上的位移要当拖动，只有没动过才算点击",
            "view === floatingDockView" in drag && "if (!moved) dockToBottom()" in drag,
        )
        // 四角：角标只画不摸（摸了就成了按键死区），手势由根容器拦截
        val root = TestSources.blockAfter(ime, "private fun buildFloatingRoot(")
        assertTrue(
            "四角的角标要在内容之后添加（否则会被键盘背板盖住）",
            "floatingCorners = buildFloatingCorners(wrapper)" in root,
        )
        val corners = TestSources.blockAfter(ime, "private fun buildFloatingCorners(")
        assertTrue("四角默认必须是 GONE", "visibility = View.GONE" in corners)
        assertFalse(
            "角标不许接触摸（那会把「符号 / 回车」的外角变成死区）",
            "setOnTouchListener" in corners,
        )
        assertEquals(
            "四角必须真的建出四个（少一个就是一个角拖不动）",
            4,
            listOf("TOP_START", "TOP_END", "BOTTOM_START", "BOTTOM_END")
                .count { "FloatingKeyboard.Corner.$it" in corners },
        )
        val apply = TestSources.blockAfter(ime, "private fun applyFloatingWindow(")
        assertTrue("非悬浮态四角要收起", "floatingCorners.forEach { it.visibility = View.GONE }" in apply)
        assertTrue("悬浮态四角才出现", "floatingCorners.forEach { it.visibility = View.VISIBLE }" in apply)
        assertTrue("离开悬浮必须撤销行高拉伸（否则全宽键盘顶着悬浮时的键高）", "setFloatingStretch(null)" in apply)
        assertTrue(
            "拉伸必须真的改布局尺寸，不许用 scaleX/scaleY 假冒（会把字拉变形）",
            "container.scaleX = scaleX" !in apply && "applyFloatingResize(" in ime,
        )
    }

    @Test
    fun 四角手势必须让点位先给按键() {
        val ime = TestSources.codeSource("JinnIme.kt")
        val root = TestSources.blockAfter(ime, "private inner class FloatingRootView")
        // 真机实测：角视图直接吃 ACTION_DOWN 时，点「符号」左下角毫无反应。
        // 判据：按下那一帧不拦截（返回 false），只有位移超过 touchSlop 才接管
        val downBranch = TestSources.window(root, "MotionEvent.ACTION_DOWN -> {", "}")
        assertTrue(
            "按下必须放行给按键（DOWN 分支不得返回 true）",
            "return false" in downBranch && "return true" !in downBranch,
        )
        assertTrue("位移阈值必须用平台 touchSlop", "floatingTouchSlop" in root)
        val moveBranch = TestSources.window(
            root,
            "MotionEvent.ACTION_MOVE -> {",
            "MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL",
        )
        assertTrue(
            "位移没过 touchSlop 时必须放行（否则按一下就抢走事件，键又成死区）",
            "return false" in moveBranch,
        )
        // 手指移出窗口后坐标被夹在窗口边界，位移长不大 ⇒ 右 / 下角往外拉会卡死在 slop 里
        //（真机实测：面板只能缩不能放大）。判据：贴边也允许开始缩放
        assertTrue(
            "贴到窗口边界也要能开始缩放（否则右 / 下角永远放不大）",
            "pinnedOutside(ev)" in moveBranch,
        )
        assertTrue(
            "位移必须从按下点算（从过阈值的那个点算恒为 0）",
            "rawStartX = rawDownX" in root && "rawStartY = rawDownY" in root,
        )
        // 接管后必须重排 + 抬手落盘，否则拖完就丢
        assertTrue("每帧要重排窗口", "applyFloatingResize(" in root)
        assertTrue("抬手要落盘", "persistFloatingGeometry()" in root)
        // 对边必须钉在**起点**的实际边缘上：只按「起点 ± 模型尺寸差」补位置，拉过上下限后
        // 整块面板会跟着手指平移（真机实测顶边拖过下限后下沉 100px、尺寸不变）
        assertTrue(
            "拖角必须把起点对边一路传到落窗",
            "applyFloatingResize(next, corner, start.x + originWinW, start.y + originWinH)" in root,
        )
        assertTrue(
            "落窗必须把角与对边交给 pinToEdges",
            "applyGeometryToWindow(geometry, null, corner, anchorRight, anchorBottom)" in ime,
        )
        // 起点尺寸取实际窗宽 / 内容高（不是百分比反算）—— 取模型值会让下边缘逐次漂移
        assertTrue(
            "起点尺寸必须取实际值",
            "originWinW" in root && "originContentH" in root,
        )
    }

    @Test
    fun 底排图标的内边距必须随宽度收() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val scale = TestSources.blockAfter(view, "private fun applyFloatingTextScale(")
        // 回车键是 XML 的 padding=13dp + fitCenter：宽度收窄后内边距吃掉整个键宽，
        // 箭头被压成 0 像素（真机 54% 宽已复现）。判据：内边距也要有基线快照并按同一倍率缩
        assertTrue("必须给图标记一份内边距基线", "paddingLeft" in scale && "paddingRight" in scale)
        assertTrue("内边距要按同一个 factor 缩", "setPadding(" in scale)
        // 下限必须是真的常量：写死一个随手的小数等于没有下限（改小了仍然没人发现）
        assertTrue(
            "倍率下限要是常量（45% 宽时底排 5.85dp 已不可读）",
            "const val MIN_BOTTOM_ROW_FACTOR = 0.72f" in view && "MIN_BOTTOM_ROW_FACTOR" in scale,
        )
        // 高度那一条轴同样是缺陷入口：100% 宽 × 60% 高时行高只剩 33dp，13dp 的内边距不变
        // ⇒ 回车箭头只剩 7dp（真机实测整键看起来是空的）。判据：缩放系数取宽度与行高倍率的较小者
        val stretch = TestSources.blockAfter(view, "fun setFloatingStretch(")
        assertTrue(
            "底排缩放必须同时受宽度与行高倍率约束（只看宽度会让矮面板上的箭头消失）",
            "applyFloatingTextScale(minOf(widthFactor, next ?: 1f))" in stretch,
        )
        // 方向面板与字母行同源：拉伸改了行高，面板必须跟着（否则展开 / 收起会跳）
        val dir = TestSources.blockAfter(view, "private fun directionRowHeightPx(")
        assertTrue("方向面板行高必须读 letterRowPx()（含拉伸覆盖值）", "letterRowPx()" in dir)
        assertFalse("不许再直读 prefs 键高（会绕过覆盖值）", "prefs.keyHeightDp" in dir)
    }

    @Test
    fun 方向面板也要算进可伸缩块() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        // 面板在场上时字母行全 GONE；若「可伸缩块」按字母行计数就会算成 0，
        // 窗口在配置变更后按自然高重排 ⇒ 拉高过的面板开一次方向就跳回 100%
        val rows = TestSources.blockAfter(view, "fun floatingLetterRowCount(")
        assertTrue(
            "面板在场上要按它的行数计可伸缩块",
            "if (directionPanelVisible) return DIRECTION_PANEL_ROWS" in rows,
        )
        // 常量与九宫格实际行数必须对得上：写 4 行的常量会让窗口比内容多一条空白带
        val build = TestSources.blockAfter(view, "private fun ensureDirectionPanel(")
        assertEquals(
            "DIRECTION_PANEL_ROWS 必须等于九宫格实际加进去的行数",
            3,
            Regex("panel\\.addView\\(directionRow\\(").findAll(build).count(),
        )
        assertTrue(
            "常量本身要与九宫格同值",
            "private val DIRECTION_PANEL_ROWS = 3" in view,
        )
        // 面板每行总高 = letterRowPx：键高按 rowH - 4dp 落、行容器自带上下各 2dp 内边距
        assertTrue(
            "面板行高的减法必须留在 directionRowHeightPx（否则总高对不上 letterRowPx）",
            "rowPx - dp(2) * 2" in TestSources.blockAfter(view, "private fun directionRowHeightPx("),
        )
    }

    // ──────────── 面板高度按窗口可用高收（L-1470 / L-1471 / L-1472）────────────

    /**
     * 面板高度只许「按窗口收」，不许「把窗口撑高」。
     *
     * 真机实测的缺陷形态（2026-10-24）：悬浮窗口 1080×922（内容 830），面板写死 1134 ⇒
     * 可视只剩 628（裁 44.6%），底排（符号 / 数字 / 空格 / 回车）整排移出窗外。
     */
    @Test
    fun 面板高度按窗口可用高收窄() {
        // 底部全宽态：容器 wrap_content ⇒ 可用高未知（传 0）⇒ 取首选值（与历史逐像素一致）
        assertEquals(1134, FloatingKeyboard.panelHeightPx(1134, 0))
        // 悬浮态 75% × 88%：830（窗口 922 − 把手 91）− 候选栏让位 202 − 底排 166 = 462
        // （真机 `剪贴板面板: 显示 panelH=462`）
        assertEquals(462, FloatingKeyboard.panelHeightPx(1134, 462))
        // 窗口比面板还高（用户把悬浮窗拉大）：面板不跟着长 —— 绝不能反过来把窗口撑高
        assertEquals(1134, FloatingKeyboard.panelHeightPx(1134, 2000))
        // 脏值 / 退化：负余量当不受限、0 首选当没有面板
        assertEquals(1134, FloatingKeyboard.panelHeightPx(1134, -50))
        assertEquals(0, FloatingKeyboard.panelHeightPx(0, 430))
        // 余量小到 1px 也要给 1，不能返回 0 高度（面板会整块消失）
        assertEquals(1, FloatingKeyboard.panelHeightPx(1134, 1))
    }

    /**
     * 两处面板入口都必须走 [FloatingKeyboard.panelHeightPx]，不许再有第二份写死的高度。
     *
     * 复制粘贴一处分算式就够让图库面板重新被裁（剪贴板面板与图库面板是两条独立入口）。
     */
    @Test
    fun 两处面板入口共用同一个高度算式() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val clip = TestSources.blockAfter(view, "fun showClipboardPanel()")
        val gallery = TestSources.blockAfter(view, "fun showGalleryPanel()")
        assertTrue("剪贴板面板要按可用高取高度", "val panelH = panelHeightPx()" in clip)
        assertTrue("图库面板要按可用高取高度", "val panelH = panelHeightPx()" in gallery)
        assertFalse("不许再有写死的 162dp×2", "162 * 2 * density" in view)
        // 可用高的来源必须扣掉「候选栏让位 + 底排」两块：只扣一块，另一样照样被窗口裁
        val avail = TestSources.blockAfter(view, "private fun panelAvailableHeightPx(")
        assertTrue("要扣候选栏让位", "topMargin" in avail)
        assertTrue("要扣底排", "bottomRowView()" in avail)
        assertTrue(
            "判据必须是容器高度参数（悬浮态定高 / 底部态 wrap_content）",
            "layoutParams?.height ?: 0" in avail,
        )
    }

    /**
     * 量自然高之前必须先把面板摆回字母区，量完再摆回去（L-1471）。
     *
     * 面板开着时长按换档：contentArea 被撑成面板态高，量到的「自然高」是 1533 而不是 945 ⇒
     * 落盘 `237,1107,78,81`、窗口 1123×1327（+44%），关掉面板也不回收。
     */
    @Test
    fun 量自然高前必须撤掉面板() {
        val ime = TestSources.codeSource("JinnIme.kt")
        val apply = TestSources.blockAfter(ime, "private fun applyFloatingWindow()")
        val suspendAt = apply.indexOf("suspendPanelForMeasure()")
        val measureAt = apply.indexOf("measureNaturalHeight(container, naturalW)")
        val resumeAt = apply.lastIndexOf("resumePanelAfterMeasure()")
        assertTrue("换档量高前要先撤面板", suspendAt >= 0)
        assertTrue("量高调用必须在撤面板之后", measureAt > suspendAt)
        assertTrue("量完要摆回面板（且在新窗口落地之后）", resumeAt > measureAt)
        // 早退分支（量不到自然高 ⇒ 不悬浮）也必须收尾，否则面板永久停在字母态
        val early = apply.substring(measureAt, suspendAt + apply.substring(suspendAt).indexOf("return"))
        assertTrue("量不到自然高时也要把面板摆回去", "resumePanelAfterMeasure()" in early)

        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val suspend = TestSources.blockAfter(view, "fun suspendPanelForMeasure(")
        assertTrue("只动布局，不动面板状态", "clipboardActive =" !in suspend && "galleryActive =" !in suspend)
        val resume = TestSources.blockAfter(view, "fun resumePanelAfterMeasure(")
        assertTrue("摆回去要按新的可用高", "panelHeightPx()" in resume)
    }

    /** 窗口高被改过之后（换档 / 四角拖动 / 回到底部）面板高度必须重算 */
    @Test
    fun 窗口高变化后面板高度重算() {
        val ime = TestSources.codeSource("JinnIme.kt")
        val geometry = TestSources.blockAfter(ime, "private fun applyGeometryToWindow(")
        assertTrue("落地新几何后要重算面板高", "refitActivePanelHeight()" in geometry)
        val apply = TestSources.blockAfter(ime, "private fun applyFloatingWindow()")
        val dock = apply.substringBefore("val metrics = resources.displayMetrics")
        assertTrue("回到底部（wrap_content）后要收回首选高度", "refitActivePanelHeight()" in dock)
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val refit = TestSources.blockAfter(view, "fun refitActivePanelHeight(")
        assertTrue("没面板时不做任何事", "if (!isPanelActive()) return" in refit)
        assertTrue("高度没变就不写 layoutParams（拖动每帧都会调）", "== target" in refit)
    }
}
