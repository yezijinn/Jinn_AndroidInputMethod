package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * 亮白 / 暗黑主题的决策与应用。
 *
 * 设计要点：
 *  - 色板走资源限定符（`values/` 亮白、`values-night/` 暗黑），因此「跟随系统」不需要任何代码；
 *    只有「亮白 / 暗黑 / 定时」三种强制模式才需要把 uiMode 覆盖进 Context。
 *  - 本项目不引入 AppCompat，用不了 `AppCompatDelegate.setDefaultNightMode`；
 *    统一走 [themedContext]，由 Activity 的 `attachBaseContext` 与 IME 的键盘视图创建点调用。
 *  - 判定全是纯函数（[isDarkNow] / [minutesUntilSwitch]），可直接 JVM 单测。
 *  - 「定时」模式的**到点换色**由 [ScheduledThemeTicker] 统一负责（页面接在 `onStart`/`onStop`、
 *    键盘接在弹出/收起），全仓只有这一份实现；到点该不该动界面由各调用方自己判。
 */
object ThemeManager {

    /** 跟随系统深浅色（不覆盖 uiMode） */
    const val MODE_SYSTEM = 0

    /** 强制亮白 */
    const val MODE_LIGHT = 1

    /** 强制暗黑 */
    const val MODE_DARK = 2

    /** 按用户设定的两个时刻自动切换 */
    const val MODE_SCHEDULED = 3

    /** 一天的总分钟数：时刻统一按「当天第几分钟」（0..1439）参与运算 */
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * 纯函数：此刻是否应使用深色主题。
     *
     * @param mode 主题模式；未知取值按「跟随系统」处理（配置损坏时不得悄悄落到某个强制值上）
     * @param systemIsDark 系统当前是否深色（调用方从 Configuration 读取）
     * @param lightAtMin 定时模式：切到亮色的时刻（当天第几分钟）
     * @param darkAtMin 定时模式：切到暗色的时刻
     * @param nowMin 当前时刻（当天第几分钟）
     *
     * 定时语义：亮起时刻进入亮色、暗起时刻进入暗色，亮色区间为 `[light, dark)`；
     * 若 `light > dark`（例：20:00 亮 / 08:00 暗，作息跨零点）则亮色区间为 `[light, 24h) ∪ [0, dark)`；
     * 两者相等属无效配置，按全天亮色处理（宁可保留白天观感，也不让判定无解）。
     * 时刻一律 `floorMod` 归一，负数或超过 24h 的脏值不会越界。
     */
    fun isDarkNow(
        mode: Int,
        systemIsDark: Boolean,
        lightAtMin: Int,
        darkAtMin: Int,
        nowMin: Int,
    ): Boolean = when (mode) {
        MODE_LIGHT -> false
        MODE_DARK -> true
        MODE_SCHEDULED -> !inLightWindow(lightAtMin, darkAtMin, nowMin)
        else -> systemIsDark
    }

    /** 亮色区间判定（含跨零点与无效配置） */
    private fun inLightWindow(lightAtMin: Int, darkAtMin: Int, nowMin: Int): Boolean {
        val light = Math.floorMod(lightAtMin, MINUTES_PER_DAY)
        val dark = Math.floorMod(darkAtMin, MINUTES_PER_DAY)
        val now = Math.floorMod(nowMin, MINUTES_PER_DAY)
        if (light == dark) return true // 无效配置：全天亮色
        return if (light < dark) now >= light && now < dark
        else now >= light || now < dark
    }

    /**
     * 纯函数：距离下一次切换还有多少分钟（停在设置页时用它安排准点刷新）。
     *
     * 返回 -1 表示「模式与时刻无关」（非定时 / 无效配置）；其余情况恒为 `1..1440`
     *，恰好站在切换点上时给的是到另一个端点的距离（07:00 亮 / 19:00 暗时站在 07:00 得 720），
     * 而不是 0，避免 0 值被当成「立即切换」而反复重建页面。
     */
    fun minutesUntilSwitch(mode: Int, lightAtMin: Int, darkAtMin: Int, nowMin: Int): Int {
        if (mode != MODE_SCHEDULED) return -1
        val light = Math.floorMod(lightAtMin, MINUTES_PER_DAY)
        val dark = Math.floorMod(darkAtMin, MINUTES_PER_DAY)
        if (light == dark) return -1
        val now = Math.floorMod(nowMin, MINUTES_PER_DAY)
        val candidates = listOf(
            Math.floorMod(light - now, MINUTES_PER_DAY),
            Math.floorMod(dark - now, MINUTES_PER_DAY),
        ).filter { it > 0 }
        return candidates.minOrNull() ?: MINUTES_PER_DAY
    }

    /** 当前时刻（当天第几分钟），按本机时区 */
    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /**
     * 指定 Context **自己解析出的**深浅（读它的 uiMode 快照）。
     *
     * 与 [isDark] 的分工：后者按 Prefs 现算决策，前者读 Context 自身 —— 对 [themedContext] 造出来的
     * Context（键盘视图的创建 Context）就是「这份色板属于哪一档」。
     * **键盘选皮肤必须用这个**（见 `PinyinKeyboardView.syncKeyboardSkin`）：视图的色板是创建时刻
     * 定死的，而换主题的重建可能被延后（`JinnIme.applyThemeIfNeeded` 遇未上屏输入 / 面板打开时
     * 只退出自己，随后 `configure()` 照常跑）。此刻若按现算决策取皮肤，令牌皮肤会拿着旧档色板画 ——
     * 暗档的原黑被画成白键面，日志却写着「原黑」。
     */
    fun paletteIsDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /** 系统当前是否深色（读 Configuration 的 uiMode 掩码位） */
    fun isSystemDark(context: Context): Boolean = paletteIsDark(context)

    /** 按 Prefs 解析「此刻是否深色」：强制模式看配置时刻，跟随系统看系统 */
    fun isDark(context: Context, prefs: Prefs = Prefs(context)): Boolean = isDarkNow(
        mode = prefs.themeMode,
        systemIsDark = isSystemDark(context),
        lightAtMin = prefs.themeLightAtMinutes,
        darkAtMin = prefs.themeDarkAtMinutes,
        nowMin = nowMinutes(),
    )

    /**
     * 纯函数：按明暗决策取当前档位的键盘皮肤 id（亮色档 / 暗色档，见 `Prefs.skinLightId`）。
     *
     * 「亮色 / 暗色」两个设置项各自记住一套皮肤，明暗切换时键盘皮肤随之切换 —— 这是该设置项的语义：
     * 不再是一套固定死的皮肤加一套固定死的色板，而是「每一档用哪套皮肤」由用户指定。
     */
    fun skinIdFor(isDark: Boolean, lightId: String, darkId: String): String =
        if (isDark) darkId else lightId

    /**
     * 当前生效的键盘皮肤：档位归一（跨档脏值回退本档令牌基线）在这里收口，调用方不必自己判档。
     *
     * 明暗判定与页面色板同源（都出自 [isDark]），因此不会出现「浅色页面 + 深色键盘」的搭配。
     */
    fun keyboardSkin(prefs: Prefs, isDark: Boolean): KeyboardSkin = KeyboardSkins.byId(
        skinIdFor(isDark, prefs.skinLightId, prefs.skinDarkId),
        if (isDark) SkinTone.DARK else SkinTone.LIGHT,
    )

    /**
     * 返回把 uiMode 固定为指定深浅的 Context：Activity 的 `attachBaseContext` 与 IME 键盘视图的
     * 创建点都走这里，资源解析（`@color` / drawable / 系统控件配色）随之下沉到目标色板。
     *
     * 只动 `UI_MODE_NIGHT_*` 两个位，其余配置（方向 / 密度 / 语言 / 字体缩放）原样保留。
     */
    fun wrapContext(base: Context, isDark: Boolean): Context {
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return base.createConfigurationContext(config)
    }

    /**
     * 按 Prefs 决策后的 Context：跟随系统时原样返回，让系统深浅色变化由系统自己驱动
     * （也避免无谓地再造一个 Context）；其余模式返回固定 uiMode 的覆盖 Context。
     */
    fun themedContext(base: Context, prefs: Prefs = Prefs(base)): Context {
        if (prefs.themeMode == MODE_SYSTEM) return base
        return wrapContext(base, isDark(base, prefs))
    }

    /**
     * 页面用：定时到点时若「此刻应有的档」与「本页**正在用的调色板**」不一致，就重建本页。
     *
     * 判据用 [paletteIsDark]（读本页 Context 自己的 uiMode 快照）而不是记一个「创建时是什么档」的字段：
     * 前者说的正是「这一页**画出来**的是哪一档」。字段法在「[themedContext] 与 `onCreate` 恰好跨过
     * 切换点」这类边角上会记错，而那时重建判据就永远为假（页面卡在旧配色上，直到下次切换）。
     */
    fun recreateIfPaletteStale(activity: Activity) {
        if (isDark(activity) != paletteIsDark(activity)) activity.recreate()
    }

    /**
     * 页面用：建一个「定时到点若本页调色板过期就重建」的定时器。
     *
     * 接入方式在**全仓统一**为「[ScheduledThemeTicker.start] 放 `onStart`、[ScheduledThemeTicker.stop]
     * 放 `onStop`」（由 `ThemeColorParityTest.每个设置页都必须接入定时换色` 机械对拍）——
     * 曾经只有设置页与键盘外观页各写了一份，其余 8 页没有，同一模式下「停在这页会到点换、停在另一页不会」。
     */
    fun scheduledRebuildTicker(activity: Activity): ScheduledThemeTicker =
        ScheduledThemeTicker(Prefs(activity)) { recreateIfPaletteStale(activity) }

    /**
     * 定时换肤的准点定时器（**页面与键盘共用这一份**，见 `BUG.md` L-476）。
     *
     * 为什么需要它：「到点」这件事与界面在不在前台无关 —— 页面停在屏幕上、键盘正显示着，
     * 都可能跨过切换点。此前 `SettingsActivity` / `KeyAppearanceActivity` / `JinnIme` 各写了一份，
     * 而其余 8 个页面干脆没有。
     *
     * 语义：
     *  - 只在「定时」模式排：其余模式（含无效配置）[minutesUntilSwitch] 返回 -1，[schedule] 直接返回；
     *  - 排到**下一次切换** + [MARGIN_MS] 余量。两个时刻按**分钟粒度**算（[nowMinutes] 截到分钟），
 *    于是这个延时只会落在边界**之后**、不会提前 —— 这一点是必需的：提前触发时
 *    [onSwitch] 会判定「还没到点」，而它接着会把下一次排到**下一个切换点**（12 小时后），
 *    等于整次切换被漏掉；
     *  - 到点后**无条件续排**下一次；**是否真需要动界面由 [onSwitch] 自己判**（页面比调色板、
     *    键盘调 `JinnIme.applyThemeIfNeeded` 且它自己会跳过没变的），所以「档位没变」不会反复重建界面；
     *  - 模式与两个时刻在**每次排程时现读** [Prefs]：用户刚改完定时时刻，下一次排的就是新值。
     *
     * [onSwitch] 在主线程被调用（定时器自己建在主线程 Looper 上）。
     */
    class ScheduledThemeTicker(private val prefs: Prefs, private val onSwitch: () -> Unit) {

        private val handler = Handler(Looper.getMainLooper())

        private val tick = Runnable {
            // onSwitch 必须兜住（2026-10-03 修复 L-674）：两条链分别是 activity.recreate() 与
            // JinnIme.recreateKeyboardView()，异常抛到主 Looper 会崩掉设置页 / 正在打字的 IME 进程；
            // 且异常发生在 schedule() 之前，一次就把续排链打断 ⇒ 到点不再换色直到下次 onStart。
            runCatching { onSwitch() }.onFailure { Diagnostics.w("ThemeManager", "定时换色失败: " + it.javaClass.simpleName) }
            schedule()
        }

        /**
         * 惯用入口：**先立刻对一次表**，再排下一次。
         *
         * 立刻对表不能省：界面可能在后台跨过切换点（[stop] 期间不排程），回来时靠这一下补上；
         * 漏了它就是「回到页面仍是旧配色，直到下一次切换」。
         */
        fun start() {
            onSwitch()
            schedule()
        }

        /** 排下一次切换（先撤掉挂着的那一次）。非定时模式不排 */
        fun schedule() {
            handler.removeCallbacks(tick)
            val minutes = ThemeManager.minutesUntilSwitch(
                prefs.themeMode,
                prefs.themeLightAtMinutes,
                prefs.themeDarkAtMinutes,
                ThemeManager.nowMinutes(),
            )
            if (minutes <= 0) return
            handler.postDelayed(tick, minutes * 60_000L + MARGIN_MS)
        }

        /** 撤掉挂着的那一次（与 [start] 成对） */
        fun stop() {
            handler.removeCallbacks(tick)
        }

        private companion object {
            /** 到点后多等 1 秒再判定（见类注释） */
            const val MARGIN_MS = 1_000L
        }
    }
}
