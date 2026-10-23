package com.jinn.inputmethod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 悬浮键盘页：总开关 + 逐 APP 绑定（白名单）。
 *
 * ## 为什么绑定用白名单
 * 悬浮键盘的诉求是**例外**（横屏游戏 / 视频时不想被底部面板挡住），不是默认形态。
 * 白名单（只勾选的 APP 悬浮）比黑名单安全：新装的 APP 一律沿用熟悉的底部全宽键盘，
 * 不会一开开关就把聊天、搜索框全变成浮层。绑定为空 ⇒ 谁都不悬浮。
 *
 * ## 包可见性
 * 本页只列**有桌面入口的应用**（`ACTION_MAIN` + `CATEGORY_LAUNCHER`），manifest 用
 * `<queries>` 声明这一条 intent —— **不申请 `QUERY_ALL_PACKAGES`**。理由：悬浮键盘的绑定对象
 * 必然是用户会去用的前台 APP，没有桌面入口的应用（输入法自身之外的服务、插件）既进不去前台，
 * 也就没有必要暴露包名；权限面越小越好。
 *
 * ## 勾选即落盘
 * 拨动开关 / 勾选应用立即写 prefs 并清掉 IME 侧的「回到底部」进程内豁免
 * （[JinnIme.clearFloatingDockOverrides]）：否则用户按过「回到底部」再回来重新勾上同一个 APP，
 * 界面写着「已绑定」而键盘仍然不浮。
 */
class FloatingKeyboardActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var switchEnabled: Switch
    private lateinit var appList: LinearLayout
    private lateinit var textHint: TextView

    /** 批量改勾选（清空绑定 / 渲染）期间挂起逐项回调：只写一次盘 */
    private var bulk = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    /** 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]） */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floating_keyboard)
        prefs = Prefs(this)
        findViewById<TextView>(R.id.text_floating_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_floating_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_floating_close).apply {
            // 关闭键的键面字形与可听名统一来自 PageChrome（与其它页面同一份定义）
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }
        switchEnabled = findViewById(R.id.switch_floating_enabled)
        switchEnabled.text = TEXT_ENABLE
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            // 渲染阶段回填开关态会同步回调一次：那时写盘等于把「读出来的值」当成用户操作
            if (bulk) return@setOnCheckedChangeListener
            prefs.floatingKeyboardEnabled = checked
            JinnIme.clearFloatingDockOverrides()
            Diagnostics.i(TAG, "悬浮键盘: ${if (checked) "开启" else "关闭"}")
            renderHint()
        }
        textHint = findViewById(R.id.text_floating_hint)
        appList = findViewById(R.id.floating_app_list)
        findViewById<Button>(R.id.btn_floating_clear).apply {
            text = TEXT_CLEAR
            setOnClickListener { clearAllBindings() }
        }
    }

    /** 每次回前台重读配置与列表（设置页可导入备份 / 别的页面也能改绑定，不能停在旧快照） */
    override fun onStart() {
        super.onStart()
        themeTicker.start()
        prefs = Prefs(this)
        renderRows()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    private fun renderRows() {
        bulk = true
        switchEnabled.isChecked = prefs.floatingKeyboardEnabled
        bulk = false
        val apps = launchableApps()
        pruneStaleBindings(apps)
        val bound = prefs.floatingBoundPackages()
        appList.removeAllViews()
        if (apps.isEmpty()) {
            appList.addView(
                TextView(this).apply {
                    text = TEXT_EMPTY
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                },
            )
        }
        for (app in apps) {
            addRow(app, app.packageName in bound)
        }
        renderHint()
    }

    /**
     * 清掉绑列表里已经不存在于应用列表的包名（卸载 / 该版本没有桌面入口 / 备份被手改）。
     *
     * 不清的后果：状态行「已绑定 N 个」虚高，而其中几条永远匹配不到任何宿主 —— 用户看到
     * 数字对不上、又没有可取消的勾选框（页面只列现存应用），只能靠「清空全部绑定」重来。
     * 只删「应用列表里查不到的」，因此不会误删暂时不可见的应用。
     */
    private fun pruneStaleBindings(apps: List<AppEntry>) {
        val bound = prefs.floatingBoundPackages()
        val stale = FloatingKeyboard.stalePackages(bound, apps.map { it.packageName }.toSet())
        if (stale.isEmpty()) return
        prefs.floatingKeyboardApps = FloatingKeyboard.serializePackages(bound - stale)
        JinnIme.clearFloatingDockOverrides()
        Diagnostics.i(TAG, "悬浮键盘绑定: 清理失效包名 ${stale.size} 个 $stale")
    }

    /** 一行 = 一张卡片：勾选框（应用名）+ 包名小字 */
    private fun addRow(app: AppEntry, checked: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val check = CheckBox(this).apply {
            text = app.label
            textSize = 14f
            isChecked = checked
            setTextColor(getColor(R.color.text_primary))
        }
        // 勾选态必须在**挂监听器之前**设好：赋值会同步回调一次监听器，渲染阶段就写盘，
        // 还会把「渲染出来的旧值」当成用户操作。
        check.setOnCheckedChangeListener { _, isChecked ->
            if (bulk) return@setOnCheckedChangeListener
            setBound(app.packageName, isChecked)
        }
        val sub = TextView(this).apply {
            text = app.packageName
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(PageStyle.dp(context, 28), 0, 0, 0)
        }
        row.addView(check)
        row.addView(sub)
        PageStyle.addCard(appList, row)
    }

    private fun setBound(pkg: String, bound: Boolean) {
        val set = prefs.floatingBoundPackages().toMutableSet()
        if (bound) set.add(pkg) else set.remove(pkg)
        prefs.floatingKeyboardApps = FloatingKeyboard.serializePackages(set)
        JinnIme.clearFloatingDockOverrides()
        Diagnostics.i(TAG, "悬浮键盘绑定: ${if (bound) "加" else "减"} $pkg（共 ${set.size}）")
        renderHint()
    }

    /** 清空全部绑定：显式二次确认的代价不值得，勾选框本来就能逐个取消 —— 这里只清空并回报数量 */
    private fun clearAllBindings() {
        val count = prefs.floatingBoundPackages().size
        prefs.floatingKeyboardApps = ""
        JinnIme.clearFloatingDockOverrides()
        Diagnostics.i(TAG, "悬浮键盘绑定: 清空（原 $count 个）")
        renderRows()
    }

    private fun renderHint() {
        val bound = prefs.floatingBoundPackages().size
        textHint.text = when {
            !prefs.floatingKeyboardEnabled -> TEXT_HINT_OFF
            bound == 0 -> TEXT_HINT_EMPTY
            else -> String.format(java.util.Locale.US, TEXT_HINT_ON, bound)
        }
    }

    /** 有桌面入口的应用（按显示名排序）；查询依赖 manifest 里的 `<queries>` intent 声明 */
    private fun launchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }
            .onFailure { Diagnostics.w(TAG, "悬浮键盘: 查询应用列表失败 ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        return resolved
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .map { AppEntry(it.packageName, runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)) }
            .sortedBy { it.label.lowercase() }
    }

    private data class AppEntry(val packageName: String, val label: String)

    private companion object {
        const val TAG = "FloatingKeyboard"

        // 文案代码下发：strings.xml 默认禁改（与设置页 / 生僻字页同一做法）
        const val TEXT_TITLE = "悬浮键盘"
        const val TEXT_DESC = "开启后，只有下面勾选的应用弹出键盘时使用可拖动的浮层：\n" +
            "按住顶部把手可移动，长按把手切换大小。\n" +
            "浮层的宽高与位置竖屏、横屏各记一套。"
        const val TEXT_ENABLE = "启用悬浮键盘（默认关闭）"
        const val TEXT_CLEAR = "清空全部绑定"
        const val TEXT_EMPTY = "没有可列出的应用（应用列表查询为空）"
        const val TEXT_HINT_OFF = "开关关闭时所有应用都是底部全宽键盘，与原来一致。"
        const val TEXT_HINT_EMPTY = "开关已开，但还没有勾选应用 —— 绑定为空时谁都不悬浮。"
        const val TEXT_HINT_ON = "已绑定 %d 个应用；浮层上的「回到底部」可按应用临时退出悬浮。"
    }
}
