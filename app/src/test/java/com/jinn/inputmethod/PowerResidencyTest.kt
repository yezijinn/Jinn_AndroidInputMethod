package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 59 轮：省电 / 唤醒 / 后台驻留链路的守卫。
 *
 * 输入法是**常驻进程**，这里的错误形态都不是崩溃，而是「没人看得出来」的持续消耗：
 * 自我续排的定时器活过会话收尾、21~34s 的后台解析砸在打字时、12MB 解码位图没有让位入口。
 * 三条断言都钉「收尾点齐不齐」与「重活有没有前台闸门」，而不是钉某个函数怎么写。
 *
 * ⚠ 正向钉一律走 [TestSources.codeSource]（先剥注释）：这些名字在本项目注释里到处出现，
 * 拿原文对拍时「删掉实现、把名字留在注释里」能让全部断言保持全绿（`BUG.md` L-116）。
 */
class PowerResidencyTest {

    /**
     * 不得把「定时」交给系统级机制：`AlarmManager` / `JobScheduler` / `WorkManager` 都是
     * **能把进程唤醒**的入口，而后台唤醒正是用户这一轮点名的耗电源。本应用的定时全是
     * 主线程 `Handler`（uptime 计时、进程不再休眠时自然顺延，不唤醒设备）。
     *
     * `WakeLock` 同理：输入法没有任何理由把屏幕 / CPU 按住不放（录音只持有音频焦点）。
     */
    @Test
    fun 不得持有唤醒锁或注册系统级定时任务() {
        val banned = listOf(
            "newWakeLock",
            "PowerManager.WakeLock",
            "AlarmManager",
            "setExactAndAllowWhileIdle",
            "JobScheduler",
            "JobInfo.Builder",
            "WorkManager",
            "scheduleAtFixedRate",
            // 与 scheduleAtFixedRate 同一族的「自我续排」：只禁一个等于留了后门
            "scheduleWithFixedDelay",
            "java.util.Timer",
            // Kotlin stdlib 的两个周期入口（`fixedRateTimer` 与具名工厂 `kotlin.concurrent.timer`）：
            // 不写限定类名也能建出周期定时器，只禁 `java.util.Timer` 等于同一个后门
            "fixedRateTimer",
            "kotlin.concurrent.timer",
        )
        val offenders = mutableListOf<String>()
        for (f in mainSources()) {
            val code = TestSources.codeOf(f.readText())
            for (token in banned) {
                if (token in code) offenders += "${f.name}: $token"
            }
        }
        assertEquals("出现系统级唤醒 / 定时入口（见本用例 KDoc）: $offenders", emptyList<String>(), offenders)
    }

    /**
     * 会话收尾必须撤掉全部「自我续排」的定时器。
     *
     * 三条链都是自己给自己续命，**收尾点漏一处就一直跑到进程死**：
     *  · 退格连删 55ms 循环（`PinyinKeyboardView.stopBackspaceRepeat`）—— 漏了就是「用户没碰键盘，
     *    字一直被杀」；三个收尾点是 `onFinishInputView` / `onWindowHidden` / `onDestroy`，
     *    视图侧还有 `onDetachedFromWindow`，但服务销毁不保证 detach，故第三处不能省；
     *  · 定时换肤 ticker（到点无条件续排）—— 漏了会在**键盘已收起**时到点重建视图（最长 12h 后）；
     *  · 悬浮把手长按换档 —— 漏了会在键盘消失后照样换档：在别的应用上弹一次百分比、改掉几何。
     */
    @Test
    fun 会话收尾要撤掉自我续排的定时任务() {
        val ime = TestSources.codeSource("JinnIme.kt")
        val finishView = TestSources.blockAfter(ime, "override fun onFinishInputView(finishingInput: Boolean)")
        assertTrue("收起键盘要停视图侧连删", "pinyinKeyboard?.stopBackspaceRepeat()" in finishView)
        assertTrue("收起键盘要停定时换肤", "themeTicker.stop()" in finishView)

        val hidden = TestSources.blockAfter(ime, "override fun onWindowHidden()")
        assertTrue("窗口隐藏要停视图侧连删", "pinyinKeyboard?.stopBackspaceRepeat()" in hidden)
        assertTrue("窗口隐藏要撤悬浮把手长按", "ui.removeCallbacks(floatingLongPress)" in hidden)
        assertTrue("撤计时器同时要复位长按待发标志", "floatingLongPressArmed = false" in hidden)

        val destroy = TestSources.blockAfter(ime, "override fun onDestroy()")
        assertTrue("销毁要停视图侧连删", "pinyinKeyboard?.stopBackspaceRepeat()" in destroy)
        assertTrue("销毁要停定时换肤", "themeTicker.stop()" in destroy)
        assertTrue("销毁要清自己的消息队列", "ui.removeCallbacksAndMessages(null)" in destroy)
        // 顺序：必须在把视图引用置空之前调 —— 反过来就是空操作，白写一行
        assertTrue(
            "stopBackspaceRepeat 必须早于 pinyinKeyboard = null",
            destroy.indexOf("pinyinKeyboard?.stopBackspaceRepeat()") in
                0 until destroy.indexOf("pinyinKeyboard = null"),
        )

        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val detach = TestSources.blockAfter(view, "override fun onDetachedFromWindow()")
        assertTrue("detach 要停连删", "stopBackspaceRepeat()" in detach)
        assertTrue("detach 要撤铺底诊断的延迟任务", "removeCallbacks(backgroundsLogTask)" in detach)
        assertTrue("detach 要撤待揭示的面板请求", "cancelPendingPanelReveal()" in detach)
    }

    /**
     * 兜底装载（180s）到点时要**再看一眼前台**：可选包首次装载实测 21~34s，砸在用户打字时
     * 就是掉帧与发热。另两条触发（息屏 / 键盘收起 20s）本身就是空闲信号，只有这条没有。
     *
     * 顺延必须有上限，否则「键盘一直开着」等于永远不装 —— 那会变成功能回归。
     */
    @Test
    fun 兜底装载到点要再看一眼前台() {
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue(
            "兜底定时器要走带前台判据的闸门",
            "ui.postDelayed(optionalFallbackCheck, OPTIONAL_FALLBACK_DELAY_MS)" in ime,
        )
        assertFalse(
            "不得再写成裸 lambda 直接装载（那样到点就砸重活）",
            "postDelayed({ maybeLoadOptionalDict(" in ime,
        )
        val fallback = TestSources.blockAfter(ime, "private fun runOptionalFallback()")
        assertTrue("判据取窗口是否在显示", "window?.isShowing == true" in fallback)
        assertTrue("顺延要计数", "optionalFallbackDeferrals++" in fallback)
        // ⚠ 上限**必须被用上**，只钉常量值不算：删掉「< 上限」这半句 = 键盘一开着就永不装载
        // （功能回归：可选词库永远不装），而上面那些断言（判据、计数、重排、到限装载串都还在）
        // 照样全绿 —— 第 59 轮 Agent-2 实测过这个变异。
        assertTrue(
            "顺延判据里要真的比上限",
            "optionalFallbackDeferrals < OPTIONAL_FALLBACK_MAX_DEFERRALS" in fallback,
        )
        assertTrue("顺延要重新排队", "ui.postDelayed(optionalFallbackCheck, OPTIONAL_FALLBACK_DELAY_MS)" in fallback)
        assertTrue("到限后照常装载（不得变成永不装载）", "maybeLoadOptionalDict(\"兜底超时\")" in fallback)
        // 「计数不归零也没事」这个论证的前提是**定时器一次性**（onCreate 排一次 + 顺延分支重排）：
        // 每实例最多触发「上限 + 1」次，饱和之后就没有下一次了。
        // ⚠ 这里**不钉「恰好 2 处」**（第 59 轮 Agent-3 变异实证）：将来若合法地改成每会话重排
        // （`onStartInputView` / `onFinishInputView` 补排一次以应对长会话），钉处数只会误红、
        // 逼人去改守卫；真正要守的是「寿命模型一变，计数必须配对归零」—— 否则饱和的计数会让
        // 闸门**永久失效**、静默回落到「到点就装」。同一变异下：**带归零**的写法应当绿，
        // **不带归零**才是真回归，本条按这个口径判。
        val schedules = Regex("""postDelayed\(optionalFallbackCheck""").findAll(ime).count()
        // 归零语句必须是**函数体内的赋值**：字段声明 `private var optionalFallbackDeferrals = 0`
        // 也是「= 0」，按裸正则算会把上面那条判据一次性废掉（本轮变异实证：M-A2 仍全绿）。
        // 本项目类成员缩进 4 空格、函数体 8 起，故用行首缩进把它排除。
        val resets = Regex("""(?m)^ {8,}optionalFallbackDeferrals\s*=\s*0""").findAll(ime).count()
        assertTrue(
            "兜底定时器已不是一次性（$schedules 处排程）：每轮首次排程前必须把 optionalFallbackDeferrals 归零" +
                "（当前只有 $resets 处）",
            resets >= schedules - 2,
        )
        val cap = Regex("""const val OPTIONAL_FALLBACK_MAX_DEFERRALS = (\d+)""").find(ime)
            ?: error("找不到 OPTIONAL_FALLBACK_MAX_DEFERRALS 常量")
        assertTrue("顺延上限必须为正（0 = 闸门形同虚设）", cap.groupValues[1].toInt() > 0)
        // 顺延轮数是「什么时候装」而不是「装不装」：上限过大等于把兜底废掉
        assertTrue("顺延上限不宜超过 5 轮", cap.groupValues[1].toInt() <= 5)
    }

    /**
     * 缩略图缓存（12MB / 低档机 6MB）必须有**服务之外**的让位入口。
     *
     * 原先是 `JinnIme` 的两个内存回调独占（L-1489）；而缓存由「键盘面板」与「历史页」共用 ——
     * `am force-stop` 后只起历史页时服务是 DEAD，12MB 由活动页填充却没人让位。
     * 现在两个入口共用同一份判据（[ClipboardThumbLoader.shouldReleaseForTrim]），
     * 白名单**只能写在一处**：写两份，下次改档位必有一处漏改。
     */
    @Test
    fun 缩略图缓存要有服务之外的让位入口() {
        val loader = TestSources.codeSource("ClipboardThumbLoader.kt")
        val predicate = TestSources.blockAfter(loader, "internal fun shouldReleaseForTrim(level: Int)")
        for (level in listOf("TRIM_MEMORY_RUNNING_LOW", "TRIM_MEMORY_RUNNING_CRITICAL", "TRIM_MEMORY_COMPLETE")) {
            assertTrue("判据要收 $level", level in predicate)
        }
        for (level in listOf("TRIM_MEMORY_UI_HIDDEN", "TRIM_MEMORY_BACKGROUND", "TRIM_MEMORY_MODERATE")) {
            assertFalse("常态往返档不得清缓存：$level", level in predicate)
        }
        // 进程级回调：注册一次，且失败要放开标记（否则这一整个进程都没人让位）
        val register = TestSources.blockAfter(loader, "private fun ensureTrimCallbacks(context: Context)")
        assertTrue("要挂在 Application 上", "context.applicationContext.registerComponentCallbacks" in register)
        assertTrue("回调里用同一份判据", "shouldReleaseForTrim(level)" in register)
        assertTrue("低内存同样让位", "override fun onLowMemory()" in register)
        assertTrue("注册失败要放开标记", "trimCallbacksRegistered.set(false)" in register)
        assertTrue("只注册一次", "trimCallbacksRegistered.compareAndSet(false, true)" in loader)
        assertTrue(
            "load 时挂上（本对象是 Object，只有这里拿得到 Context）",
            "ensureTrimCallbacks(context)" in TestSources.blockAfter(loader, "fun load(context: Context"),
        )
        // 释放只 evict 位图：负缓存 / 在途登记都不含位图，动它们会让坏图立刻重排解码
        val release = TestSources.blockAfter(loader, "fun releaseMemory()")
        assertTrue("释放要清空位图缓存", "cache.evictAll()" in release)
        assertFalse("负缓存不含位图、不动", "failedAt" in release)
        assertFalse("在途登记不动", "inFlight" in release)

        // IME 侧两个回调都必须转调，但**不得**再自带一份白名单（防两处漂移）
        val ime = TestSources.codeSource("JinnIme.kt")
        val imeTrim = TestSources.blockAfter(ime, "override fun onTrimMemory(level: Int)")
        assertTrue(
            "onTrimMemory 要走共用判据",
            "ClipboardThumbLoader.shouldReleaseForTrim(level)" in imeTrim,
        )
        // 只接判据不释放 = 入口静默失效：`if (判据) { }` 这种删法不会被任何一条断言发现
        // （第 59 轮 Agent-3 变异实证：删掉这一句，两条相关用例全绿）
        assertTrue(
            "onTrimMemory 判中就要真的释放",
            "ClipboardThumbLoader.releaseMemory()" in imeTrim,
        )
        assertTrue(
            "onLowMemory 同样让位",
            "ClipboardThumbLoader.releaseMemory()" in
                TestSources.blockAfter(ime, "override fun onLowMemory()"),
        )
        for (level in listOf("TRIM_MEMORY_RUNNING_LOW", "TRIM_MEMORY_RUNNING_CRITICAL", "TRIM_MEMORY_COMPLETE")) {
            assertFalse("白名单只许写一处，IME 侧不得再抄一遍：$level", level in ime)
        }
        // BUG.md L-1262 仍然是约束：按实例的退出路径不许清这份共享缓存
        for (f in listOf("ClipboardImageGridView.kt", "ClipboardPanelView.kt", "ClipboardHistoryActivity.kt")) {
            assertFalse("$f 不得按实例清共享缓存", "releaseMemory()" in TestSources.codeSource(f))
        }
    }

    /**
     * 档位判据的行为面（不是源码形态）：只认 `RUNNING_LOW(10)` / `RUNNING_CRITICAL(15)` /
     * `COMPLETE(80)`，其余一律不动。
     */
    @Test
    fun 内存档位判据只认真紧张的三档() {
        for (level in listOf(10, 15, 80)) {
            assertTrue("档位 $level 应当让位", ClipboardThumbLoader.shouldReleaseForTrim(level))
        }
        for (level in listOf(5, 20, 40, 60, 0, -1, 999)) {
            assertFalse("档位 $level 不得清缓存", ClipboardThumbLoader.shouldReleaseForTrim(level))
        }
    }

    /** 主源码目录（仓库根 / `app/` 两种工作目录都认，与 [TestSources] 同口径） */
    private fun mainSources(): List<File> =
        listOf(
            File("src/main/java/com/jinn/inputmethod"),
            File("app/src/main/java/com/jinn/inputmethod"),
        ).first { it.isDirectory }
            .walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
}
