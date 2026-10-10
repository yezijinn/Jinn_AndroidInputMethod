package com.jinn.inputmethod

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.io.FileOutputStream

/**
 * 分类词库页：列出可选词库，按需下载 / 删除。
 *
 * 视觉对齐本项目既有页面（与剪贴板历史页同一套配色）：
 *   背景 #0B1020 ｜ 顶栏 #141C33 ｜ 卡片 #1C1F26
 *   主文字 #ECEEF2 ｜ 次文字 #9CA3AF ｜ 强调 #4C8DFF ｜ 危险 #E5484D
 *
 * 下载落地到 `filesDir/dicts/<fileName>`（[PinyinEngine.OPT_DICT_DIR]），
 * 引擎启动时自动扫描加载。加载是延迟的，基础词库先就绪，可选包在后台补齐，
 * 所以页面标注的是「开机后首次输入候选就绪需等待约 N 秒」，而不是「启动耗时」。
 *
 * 说明文案按句拆成多行渲染（[OptionalDict.descLines]），一行就是一句话：
 * 交给系统自动折行会出现断句不良的折行，读起来别扭，所以主动分行。
 */
class DictManagerActivity : Activity() {

    /** 主题应用点：与设置页同一套（早于 onCreate，避免先按系统配置渲染一帧再换色） */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    private lateinit var listHost: LinearLayout
    private lateinit var textStatus: TextView

    /**
     * 正在下载的文件名，用于禁用按钮与显示进度（null 表示空闲）。
     *
     * 存在 companion 里：Activity 会因旋转/重建换成新实例，实例字段随即丢失，
     * 新页面的「下载」按钮恢复可点，再点一次就是第二个线程写同一个 `.tmp`，
     * 字节交错后被 renameTo 成「有效」词库。跨线程写，故 @Volatile。
     */
    private var downloading: String?
        get() = activeDownload
        set(value) { activeDownload = value }

    /**
     * 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]）：[onStart] 对一次表并排下一次，
     * [onStop] 撤掉 —— 页面在后台跨过切换点，回来时也能补上。
     */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }

        root.addView(buildTopBar())

        // 提示：四句各占一行，避免被系统折行（2026-10-03 补足到四句：
        // 下载取舍 / 下载后重启 / 加载时机 / 以后不用等）。
        // ⚠ 这四句对三个包通用，只在顶部说一次 —— 卡片里重复三遍反而啰嗦。
        listOf(
            R.string.dict_manager_hint_line1,
            R.string.dict_manager_hint_line2,
            R.string.dict_load_timing,
            R.string.dict_startup_instant,
        ).forEachIndexed { i, res ->
            root.addView(hint(getString(res)), matchWrap(top = if (i == 0) 4 else 0))
        }

        // 动态状态行：初始隐藏，下载/删除时才出现。单行 + 省略号，
        // 内容是「正在下载 X…」这类变长文案，不适合按句拆分。
        textStatus = TextView(this).apply {
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            setPadding(dp(16), dp(6), dp(16), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 下载/导入/删除的结果都写在这行上：读屏要能听见（BUG.md L-1195）
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        root.addView(textStatus, matchWrap())

        listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        root.addView(
            ScrollView(this).apply { addView(listHost) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)
        Diagnostics.i(TAG, "DictManagerActivity: 打开分类词库页")
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        activePage = WeakReference(this)
        // 上次下载完成时页面已关闭、因而没有重启（2026-10-03 修复 L-547）：现在用户回到了本页
        // （前台、键盘已收起）⇒ 此刻重启是安全的，把已下载的词库真正加载进来。
        if (pendingDictRestart) {
            pendingDictRestart = false
            Toast.makeText(this, TEXT_RESUME_RESTART, Toast.LENGTH_LONG).show()
            Diagnostics.i(TAG, "回到本页：补做上次未执行的重启（加载已下载的词库）")
            restartImeForDict()
        }
        // 下载仍在进行时页面被重建（旋转 / 关闭重进）：按钮与状态行按当前进度恢复，
        // 否则新页面只显示一排禁用按钮，看不出正在下载什么
        downloading?.let { name ->
            OptionalDicts.ALL.firstOrNull { it.fileName == name }?.let {
                setStatus(getString(R.string.dict_downloading, it.name))
            }
        }
        refreshList()
    }

    override fun onPause() {
        // 只清自己的引用：重建时旧实例的 onPause 不能把新实例刚登记的引用冲掉
        if (activePage?.get() === this) activePage = null
        super.onPause()
    }

    /** 顶栏下方的静态提示行（每句一个 TextView，不折行） */
    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(dp(16), 0, dp(16), 0)
    }

    /** 更新动态状态行（自动显示出来） */
    private fun setStatus(text: String) {
        textStatus.text = text
        textStatus.visibility = View.VISIBLE
    }

    /** 顶栏：左标题、右关闭（沿用本项目深蓝顶栏 #141C33） */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.card_bg))
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }

        bar.addView(TextView(this).apply {
            text = getString(R.string.dict_manager_title)
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 右上角关闭：结束本页返回设置页（统一走 PageChrome 的文案与无障碍名，2026-10-03 修复 L-676）
        bar.addView(TextView(this).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setTextColor(getColor(R.color.text_secondary))
            textSize = 20f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                Diagnostics.i(TAG, "DictManagerActivity: 用户关闭页面")
                finish()
            }
        }, wrapWrap())

        return bar
    }

    // ── 列表 ────────────────────────────────────────────────

    private val RC_CUSTOM_DICT = 0x6901
    private val RC_CUSTOM_EDIT = 0x6902

    /** 导入线程在跑时按钮置灰，防连点重复解析（见 [actionsEnabled]） */
    @Volatile
    private var importingCustom = false

    private fun openCustomPicker() {
        if (downloading != null) return
        if (blockedByRestart()) return
        if (blockedByConfigImport()) return
        // 已有自定义词库时先确认（BUG.md L-830）：导入是**整体替换**（固定名 `custom_user.txt.xz`），
        // 用户自己攒的词表会被这个文件的内容换掉。同一张卡片上的「删除」早有二次确认，导入反倒没有 ——
        // 空库时导入是纯新增，不加这道手续（「快捷补充」页不在此列：编辑器里回显的就是当前内容，
        // 保存是显式编辑，不是拿陌生文件盖掉它）。
        if (!CustomDicts.packFile(this).isFile) {
            launchCustomPicker()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(TEXT_CUSTOM_TITLE)
            .setMessage(TEXT_CUSTOM_IMPORT_CONFIRM)
            .setPositiveButton(TEXT_CONFIRM_OK) { _, _ -> launchCustomPicker() }
            .setNegativeButton(TEXT_CONFIRM_CANCEL, null)
            .show()
    }

    /**
     * 真正打开 SAF 选文件（[openCustomPicker] 在「已有词库」时会先过一道确认）。
     *
     * ⚠ 类型保持通配（见下一行的取值）是**有意**的：`.txt` 在很多 provider 里报
     * `application/octet-stream`，限成 `text/plain` 会把正常词表挡在门外；内容层的把关在解析侧
     * （非法行计入跳过、无合法词条即拒存）。
     */
    private fun launchCustomPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        runCatching { startActivityForResult(intent, RC_CUSTOM_DICT) }
            .onFailure { Toast.makeText(this, it.message ?: it.toString(), Toast.LENGTH_SHORT).show() }
    }

    /** 打开「快捷补充」编辑页：与「导入 .txt」共享同一份源文本，保存即导入 */
    private fun openCustomEditor() {
        if (downloading != null) return
        if (blockedByRestart()) return
        if (blockedByConfigImport()) return
        runCatching {
            startActivityForResult(Intent(this, CustomDictEditActivity::class.java), RC_CUSTOM_EDIT)
        }.onFailure { Toast.makeText(this, it.message ?: it.toString(), Toast.LENGTH_SHORT).show() }
    }

    /**
     * 两个自定义词库入口的回执分发：导入（SAF 选文件）与快捷补充（编辑页保存）。
     *
     * 此页是 Activity（非 ComponentActivity），没有 registerForActivityResult，只能走旧接口。
     */
    @Deprecated("onActivityResult 已废弃，但此页是 Activity（非 ComponentActivity），无 registerForActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RC_CUSTOM_EDIT -> onCustomEditResult(resultCode, data)
            RC_CUSTOM_DICT -> onCustomImportResult(resultCode, data)
        }
    }

    /**
     * 快捷补充页保存成功：与导入共用同一句结果文案与重启路径。
     *
     * 重启动作留给本页做（`restartPending` 这个窗口位在 companion 里）：编辑页自己 kill
     * 会把「已保存」的提示一起带走，用户只看到页面闪退。
     */
    private fun onCustomEditResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        if (blockedByRestart()) return
        val entries = data.getIntExtra(CustomDictEditActivity.EXTRA_ENTRIES, 0)
        val skipped = data.getIntExtra(CustomDictEditActivity.EXTRA_SKIPPED, 0)
        val filtered = data.getIntExtra(CustomDictEditActivity.EXTRA_FILTERED, 0)
        val dropped = data.getIntExtra(CustomDictEditActivity.EXTRA_DROPPED, 0)
        Diagnostics.i(TAG, "快捷补充保存: $entries 条 / 跳过 $skipped 行 / 打不出 $filtered 条 / 同键挤掉 $dropped 条")
        setStatus(customResultText(entries, skipped, filtered, dropped))
        refreshList()
        restartImeForDict()
    }

    /**
     * 自定义词库导入：人写的 `.txt` → [CustomDicts] 校验归一 → 原子写 `dicts/custom_user.txt.xz`，
     * 同时把原文留一份到 `dicts/custom_user.src.txt`（「快捷补充」页回显用）。
     *
     * 流在主线程取（URI 授权绑本进程，取到流即持有），**解析与打包放后台**：8MB 文本的逐行
     * 校验加 xz 压缩放主线程会掉帧。所有失败都落到状态行，不抛给系统。
     */
    private fun onCustomImportResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        if (blockedByRestart()) return
        val uri = data.data ?: return
        val input = runCatching { applicationContext.contentResolver.openInputStream(uri) }.getOrNull()
        if (input == null) {
            setStatus(TEXT_CUSTOM_READ_FAIL)
            return
        }
        importingCustom = true
        setStatus(TEXT_CUSTOM_IMPORTING)
        refreshList()
        val app = applicationContext
        Thread {
            // 读词表 + 解析 + xz 压缩是重活（上限 5 万条），降后台优先级（BUG.md L-1194）
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var restart = false
            val status = try {
                val text = input.use { CustomDicts.readUtf8Capped(it, CustomDicts.MAX_INPUT_CHARS) }
                val syllables = CustomDicts.loadSyllables(app)
                // 解析 → 判闸 → 落盘（源文本归一后**先**落、包**后**落）走同一个入口，见
                // CustomDicts.saveHuman 的两条不变量（BUG.md L-851 / L-856）——此前这里先 writePack
                // 再判闸，导入超 5 万条的现成词表时会把截断版写进磁盘、界面却报失败
                val report = CustomDicts.saveHuman(dictDir(), text, syllables)
                when (report.gate) {
                    CustomDicts.SaveGate.TOO_BIG -> TEXT_CUSTOM_TOO_BIG
                    CustomDicts.SaveGate.TOO_MANY -> TEXT_CUSTOM_TOO_MANY
                    CustomDicts.SaveGate.NO_VALID -> String.format(Locale.US, TEXT_CUSTOM_EMPTY, report.skipped)
                    // 两档写盘失败文案不同（BUG.md L-861）：前者磁盘没变，后者文本已落、只差词库
                    CustomDicts.SaveGate.WRITE_FAIL_SOURCE -> TEXT_CUSTOM_WRITE_FAIL
                    CustomDicts.SaveGate.WRITE_FAIL_PACK -> TEXT_CUSTOM_WRITE_FAIL_PACK
                    CustomDicts.SaveGate.BUSY -> TEXT_CUSTOM_BUSY
                    CustomDicts.SaveGate.OK -> {
                        restart = true
                        Diagnostics.i(
                            TAG,
                            "自定义词库导入: ${report.entries} 条 / 跳过 ${report.skipped} 行 / 打不出 ${report.filtered} 条" +
                                " / 同键挤掉 ${report.dropped} 条",
                        )
                        customResultText(report.entries, report.skipped, report.filtered, report.dropped)
                    }
                }
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "自定义词库导入失败: ${t.javaClass.simpleName}")
                if (t is CustomDicts.TooLargeException) TEXT_CUSTOM_TOO_BIG else TEXT_CUSTOM_READ_FAIL
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                importingCustom = false
                setStatus(status)
                refreshList()
                if (restart) restartImeForDict()
            }
        }.apply { isDaemon = true; name = "jinn-dict-import" }.start()
    }

    private fun refreshList() {
        // 残留临时件清理（2026-10-04 修复 L-798；2026-10-05 加 mtime 保护，见 BUG.md L-827）：
        // 下载中被系统杀会留下 `*.xz.tmp`（上限 64MB），而它既不在清单、也不以 `.xz` 结尾 ⇒ 词库页看不见
        // 也删不掉，只能清数据 / 卸载才释放。判据三条：`*.xz.tmp`、不是**正在下载**的那个（`downloading`
        // 跨实例跟踪，重建页面也不会误删）、也不在**刚写入**的保护窗内（自定义词库导入走固定临时名，
        // 导入中回到前台时这里若把它当残留删掉，随后的改名必然失败 —— Linux 删除已打开文件是成功的）。
        runCatching {
            val dir = dictDir()
            val byName = dir.listFiles()?.filter { it.isFile }
                ?.associate { it.name to it.lastModified() } ?: emptyMap()
            val stale = OptionalDicts.cleanableTempNames(
                byName.keys,
                downloading,
                System.currentTimeMillis(),
            ) { byName[it] ?: 0L }
            if (stale.isNotEmpty()) {
                var freed = 0L
                for (name in stale) {
                    val f = File(dir, name)
                    val len = f.length()
                    if (f.delete()) freed += len
                }
                Diagnostics.i(TAG, "清理残留下载临时件: ${stale.size} 个 / $freed B")
            }
        }.onFailure { Diagnostics.w(TAG, "清理残留下载临时件失败: ${it.javaClass.simpleName}") }
        // 渲染单独兜底：清理失败不该挡住渲染，渲染异常也不能把同进程的 IME 服务一起带崩
        // （BUG.md L-831；对照下载回调里那句 L-800 —— 那次实测过「一次刷新异常会杀 IME 进程」）
        runCatching {
            listHost.removeAllViews()
            for (dict in OptionalDicts.ALL) {
                listHost.addView(buildCard(dict), matchWrap(bottom = 2))
            }
            // 用户自定义词库：导入为 OPT_DICT_DIR/custom_user.txt.xz，引擎与备份均走可选包同一通道
            listHost.addView(buildCustomCard(), matchWrap(bottom = 2))
            // 清单外的包（旧版遗留）：只列出来给个删除入口，见 OptionalDicts.unknownPackages
            for (fileName in unknownPackagesInDir()) {
                listHost.addView(buildUnknownCard(fileName), matchWrap(bottom = 2))
            }
        }.onFailure {
            Diagnostics.w(TAG, "词库页刷新失败: ${it.javaClass.simpleName}")
            runCatching { setStatus(TEXT_REFRESH_FAIL) }
        }
    }

    /** 下载 / 删除 / 导入按钮的可用性：下载中、导入中或**重启窗口内**一律禁用（后者见 L-801） */
    private fun actionsEnabled() = downloading == null && !restartPending && !importingCustom

    /**
     * 重启窗口内拒绝会改 `dicts/` 的操作（2026-10-04 修复 L-801）；已拒绝时给提示并返回 true。
     */
    private fun blockedByRestart(): Boolean {
        if (!restartPending) return false
        Toast.makeText(this, R.string.dict_need_restart, Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * `dicts/` 下不在清单里的包名（纯筛选在 [OptionalDicts.unknownPackages]）。
     *
     * 只在**文件**里挑（BUG.md L-84）：`File.list()` 会把目录名一起交出去，而纯函数只认 `.xz` 后缀
     * ⇒ 名为 `foo.xz` 的**目录**会被渲染成「其他包」卡片，点删除还必然失败
     * （`File.delete()` 对非空目录返回 false）。目录过滤留在调用侧，纯函数保持「只认名字」的语义。
     */
    private fun unknownPackagesInDir(): List<String> =
        OptionalDicts.unknownPackages(
            dictDir().listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList(),
        )

    /**
     * 旧版遗留包的卡片。
     *
     * 与清单内的卡片有两处**有意不同**：
     *  - 不给下载按钮：这些包的下载源已随版本下线，删了就回不来（所以删除要二次确认）；
     *  - 不写「第一次约 N 秒」：它们的索引建立耗时没有实测值，凭空标一个数字等于撒谎。
     */
    private fun buildUnknownCard(fileName: String): View {
        val file = dictFile(fileName)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 外观与 XML 侧的 @style/SettingsCard 同款（原先自造 surface_hi + 10dp 圆角、无描边）
            PageStyle.dressAsCard(this)
        }
        card.addView(line(legacyName(fileName), getColor(R.color.text_primary), 16f, bold = true))
        card.addView(line(TEXT_LEGACY_DESC, getColor(R.color.text_secondary), 13f, top = 4))
        card.addView(
            line(
                text = getString(R.string.dict_status_installed, formatSize(file.length())),
                color = getColor(R.color.ok),
                size = 13f,
                top = 10,
            ),
        )
        card.addView(
            line(
                // 旧包卡片自己写完整一句：清单卡片的加载说明已改成「通用两句」（不报实测耗时），
                // 旧包没有对应清单条目可复用，单用半句会留下残话（BUG.md L-69）
                text = TEXT_LEGACY_LOAD,
                color = getColor(R.color.warn),
                size = 13f,
                top = 8,
            ),
        )
        card.addView(
            actionButton(
                text = getString(R.string.dict_action_remove),
                color = getColor(R.color.danger),
                enabled = actionsEnabled(),
            ) { confirmRemoveLegacy(fileName) },
            matchWrap(top = 12).also { it.gravity = Gravity.END },
        )
        return card
    }

    /**
     * 自定义词库卡片。
     *
     * 它是**用户自己写的**补充包，没有下载源 —— 删掉只能重新导入，所以删除要二次确认
     * （与旧版遗留包同款）。两个入口同在这张卡片上，且共用同一份源文本：
     * 「快捷补充」直接编辑（保存即导入），「导入 .txt」从 SAF 选一份 txt。
     */
    private fun buildCustomCard(): View {
        val file = CustomDicts.packFile(this)
        val installed = file.isFile
        // 索引是否跟得上包（BUG.md L-868）：清单卡片有 L-155 的就绪判据，自定义卡片此前只看文件存在 ——
        // 包被写坏 / 加载失败时仍显绿色「已安装（N B）」，用户以为正常，实际引擎整包拒收、候选一条不出。
        // 只读文件头，主线程开销可忽略（同 L-167 的优化口径）。
        val indexReady = installed && PinyinEngine.isOptionalIndexReady(this, CustomDicts.PACK_NAME)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            PageStyle.dressAsCard(this)
        }
        card.addView(line(TEXT_CUSTOM_TITLE, getColor(R.color.text_primary), 16f, bold = true))
        for (sentence in TEXT_CUSTOM_DESC) {
            card.addView(line(sentence, getColor(R.color.text_secondary), 13f, top = 4))
        }

        // 状态行独占一行：三个按钮同排时，状态文字与按钮挤在一行会被压成竖排（2026-10-05 真机实证）
        card.addView(
            line(
                text = when {
                    !installed -> getString(R.string.dict_status_absent)
                    indexReady -> getString(R.string.dict_status_installed, formatSize(file.length()))
                    else -> getString(R.string.dict_status_installed_pending, formatSize(file.length()))
                },
                color = when {
                    !installed -> getColor(R.color.text_secondary)
                    indexReady -> getColor(R.color.ok)
                    // 中性色：刚保存、还没重启输入法时本来就短暂未就绪，不该显示成故障
                    else -> getColor(R.color.text_secondary)
                },
                size = 13f,
            ),
            matchWrap(top = 12),
        )

        // 三按钮等宽（BUG.md L-854）：固定宽度行在字体放大到 1.3 倍时只剩几像素余量，「删除」被挤到
        // 折行、更窄的屏上会被裁到屏幕外（删不掉词库）；等宽后每个入口只在自己那一份里收缩，不再互相挤压
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(
            actionButton(
                text = getString(R.string.dict_action_quick_edit),
                color = getColor(R.color.accent),
                enabled = actionsEnabled(),
                hPad = 8,
            ) { openCustomEditor() },
            weightedButtonLp(right = 8),
        )
        row.addView(
            actionButton(
                text = getString(R.string.dict_action_import_custom),
                color = getColor(R.color.accent),
                enabled = actionsEnabled(),
                hPad = 8,
            ) { openCustomPicker() },
            weightedButtonLp(right = if (installed) 8 else 0),
        )
        if (installed) {
            row.addView(
                actionButton(
                    text = getString(R.string.dict_action_remove),
                    color = getColor(R.color.danger),
                    enabled = actionsEnabled(),
                    hPad = 8,
                ) { confirmRemoveCustom() },
                weightedButtonLp(),
            )
        }
        card.addView(row, matchWrap(top = 8))
        return card
    }

    /**
     * 结果文案：有词条会被字表闸丢下时改说「候选里不会出现」。
     *
     * 用户写「黄霄雲」这类繁体时包能落盘、界面显示「已导入」，但候选出口按字表整条丢下 ——
     * 不点名的话用户只会在键盘上反复试却打不出，无从归因（2026-10-05 实测）。
     */
    private fun customResultText(entries: Int, skipped: Int, filtered: Int, dropped: Int): String = when {
        // 同键超 100 被丢下（L-864）：比字表闸更该先说 —— 它是「提示条数与实际入包不符」的直接原因
        dropped > 0 -> String.format(Locale.US, TEXT_CUSTOM_RESULT_DROPPED, entries, skipped, dropped)
        filtered > 0 -> String.format(Locale.US, TEXT_CUSTOM_RESULT_FILTERED, entries, skipped, filtered)
        else -> String.format(Locale.US, TEXT_CUSTOM_RESULT, entries, skipped)
    }

    /** 删除自定义词库前二次确认（没有下载源，删掉就得重新导入） */
    private fun confirmRemoveCustom() {
        if (downloading != null) {
            Toast.makeText(this, R.string.dict_remove_busy, Toast.LENGTH_SHORT).show()
            return
        }
        if (blockedByRestart()) return
        AlertDialog.Builder(this)
            .setTitle(TEXT_CUSTOM_TITLE)
            .setMessage(TEXT_CUSTOM_CONFIRM)
            .setPositiveButton(TEXT_CONFIRM_OK) { _, _ -> removeCustom() }
            .setNegativeButton(TEXT_CONFIRM_CANCEL, null)
            .show()
    }

    private fun removeCustom() {
        // 确认框可能在窗口开始前就已打开（下载完成会立刻安排重启）⇒ 真正动手这一步也要判
        if (blockedByRestart()) return
        // 「忙」与「删失败」分开显示（BUG.md L-865）：原先两者都落进同一句「删除失败」，
        // 用户会以为是权限或磁盘问题，其实只是另有写入在进行
        if (CustomDicts.writing) {
            setStatus(TEXT_CUSTOM_BUSY)
            return
        }
        val ok = CustomDicts.deletePack(this)
        Diagnostics.i(TAG, "自定义词库删除 ok=$ok")
        setStatus(
            if (ok) getString(R.string.dict_removed, TEXT_CUSTOM_TITLE)
            else getString(R.string.dict_remove_failed),
        )
        refreshList()
        if (ok) promptRestart()
    }

    /**
     * 配置恢复（备份导入）进行中拒绝改 `dicts/`（BUG.md L-848）。
     *
     * 恢复线程写的是同一个 `custom_user.txt.xz`（`RESTORE_SUFFIX` 临时名 → rename），
     * 与导入 / 快捷补充保存的 `.tmp` → rename 撞在同一目标上：用户在恢复期间保存，界面提示
     * 「已保存」而内容可能被恢复包里的旧版静默盖掉。四个设置页早已有同款守卫，词库两页漏了。
     */
    private fun blockedByConfigImport(): Boolean {
        if (!ConfigBackupManager.importing) return false
        Toast.makeText(this, TEXT_CONFIG_IMPORTING, Toast.LENGTH_SHORT).show()
        return true
    }

    /** 旧包的中文名：老用户认得这两个名字，比裸文件名 `ext.xz` 好认 */
    private fun legacyName(fileName: String): String = when (fileName) {
        "ext.xz" -> TEXT_LEGACY_EXT
        "opt_tencent.xz" -> TEXT_LEGACY_TENCENT
        else -> fileName
    }

    /**
     * 删除旧包前二次确认：这些包在页面上没有下载源（旧 Release 附件已清理），
     * 删掉就是永久失去，不能像清单内的包那样一点就删。
     */
    private fun confirmRemoveLegacy(fileName: String) {
        if (downloading != null) {
            Toast.makeText(this, R.string.dict_remove_busy, Toast.LENGTH_SHORT).show()
            return
        }
        if (blockedByRestart()) return
        AlertDialog.Builder(this)
            .setTitle(legacyName(fileName))
            .setMessage(TEXT_LEGACY_CONFIRM)
            .setPositiveButton(TEXT_CONFIRM_OK) { _, _ -> removeLegacy(fileName) }
            .setNegativeButton(TEXT_CONFIRM_CANCEL, null)
            .show()
    }

    /**
     * 删除旧包并重启输入法。
     *
     * 删除成功后必须重启：文件已被内存映射，不重启的话本次会话里它仍会被用来出词，
     * 用户看到的会是「删了还在」——与清单内包的删除同一条口径（[promptRestart]）。
     */
    private fun removeLegacy(fileName: String) {
        // 确认框可能在窗口开始前就已打开（下载完成会立刻安排重启）⇒ 真正动手这一步也要判
        if (blockedByRestart()) return
        val ok = runCatching { dictFile(fileName).delete() }.getOrDefault(false)
        Diagnostics.i(TAG, "遗留词库包删除: $fileName ok=$ok")
        setStatus(
            if (ok) getString(R.string.dict_removed, legacyName(fileName))
            else getString(R.string.dict_remove_failed),
        )
        refreshList()
        if (ok) promptRestart()
    }

    private fun buildCard(dict: OptionalDict): View {
        val file = dictFile(dict.fileName)
        val installed = file.isFile
        // 旧包检测（2026-10-10 随下载包重切引入）：文件存在但大小与清单不符 = 上一个版本的包。
        // 换发版时同名附件会被重切（如 2 级 40 万 → 90 万），不做这一步的话老用户看到
        // 「已安装」就永远不会点更新，词库停在上一个版本。
        val current = installed && OptionalDicts.isCurrentPack(file, dict)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 外观与 XML 侧的 @style/SettingsCard 同款（原先自造 surface_hi + 10dp 圆角、无描边）
            PageStyle.dressAsCard(this)
        }

        // 词库名
        card.addView(line(dict.name, getColor(R.color.text_primary), 16f, bold = true))

        // 说明：每句独立一行，不做自动折行
        for (sentence in dict.descLines) {
            card.addView(line(sentence, getColor(R.color.text_secondary), 13f, top = 4))
        }

        // 「已安装」不等于「真的会出词」：索引还没跟上就如实说（BUG.md L-155）。
        // 判据与装载路径同一句（PinyinEngine.isOptionalIndexReady），只在**装了但没就绪**时多一行。
        if (installed && !PinyinEngine.isOptionalIndexReady(this, dict.fileName)) {
            card.addView(line(TEXT_INDEX_PENDING, getColor(R.color.warn), 13f, top = 10))
        }

        // 安装状态与操作按钮**同一行**（2026-10-03）：状态占满剩余宽度、按钮靠右。
        // ⚠ 别再拆成「状态一行、按钮另起一行」—— 每张卡片多出一行竖直空白，三张连起来很松散。
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            line(
                text = when {
                    !installed -> getString(R.string.dict_status_absent)
                    current -> getString(R.string.dict_status_installed, formatSize(file.length()))
                    // 显式 Locale（与 formatSize 同口径；`TEXT_*.format(` 的默认 Locale 会触发守卫 L-1179）
                    else -> String.format(Locale.US, TEXT_STATUS_STALE, formatSize(file.length()))
                },
                color = when {
                    !installed -> getColor(R.color.text_secondary)
                    current -> getColor(R.color.ok)
                    else -> getColor(R.color.warn)
                },
                size = 13f,
            ),
            // weight = 1：状态文字长短不一时，按钮仍右对齐（wrap_content + weight 0 会紧贴状态、被顶到中间）
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            actionButton(
                text = when {
                    !installed -> getString(R.string.dict_action_download)
                    current -> getString(R.string.dict_action_reinstall)
                    else -> TEXT_ACTION_UPDATE
                },
                color = getColor(R.color.accent),
                enabled = actionsEnabled(),
            ) { download(dict) },
            // 左边距让按钮与状态文字之间留出呼吸位；右边距是原按钮之间的间距
            buttonLp(right = 8).apply { leftMargin = dp(8) },
        )
        if (installed) {
            row.addView(
                actionButton(
                    text = getString(R.string.dict_action_remove),
                    color = getColor(R.color.danger),
                    enabled = actionsEnabled(),
                ) { remove(dict) },
                buttonLp(),
            )
        }
        card.addView(row, matchWrap(top = 12))
        return card
    }

    /**
     * 单行文字。每句话单独一个 TextView，从根上避免长句被自动折行，
     * 保证页面上「一行就是一句话」。
     */
    private fun line(
        text: String,
        color: Int,
        size: Float,
        top: Int = 0,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(top), 0, 0)
    }

    /**
     * 操作按钮：与剪贴板页 actionButton 同款（实心圆角色块 + 白字）。
     *
     * [hPad] 是水平内边距；等宽行（自定义词库卡片）传小值 —— 每个按钮只占整行的一部分，
     * 18dp 两侧会让 4 字标签在大字体下折行（BUG.md L-854）。
     */
    private fun actionButton(
        text: String,
        color: Int,
        enabled: Boolean,
        hPad: Int = 18,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.text_on_accent))
        textSize = 14f
        background = rounded(if (enabled) color else getColor(R.color.btn_disabled), 8)
        setPadding(dp(hPad), dp(9), dp(hPad), dp(9))
        isClickable = true
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    // ── 下载 / 删除 ─────────────────────────────────────────

    private fun download(dict: OptionalDict) {
        if (downloading != null) return
        if (blockedByRestart()) return
        downloading = dict.fileName
        setStatus(getString(R.string.dict_downloading, dict.name))
        refreshList()

        Thread {
            // ⚠ 必须降后台优先级（2026-10-03 修复 L-797）：本仓所有后台重活线程都显式降优先级，
            // 而 `RecentFixesRegressionTest` 里就有「存量重算线程必须降优先级」这条守卫 —— 约定存在，只是漏了本链路。
            // 本线程做「网络读 + 持续磁盘写」最长可活 600s（callTimeout），默认优先级会与前台输入争 CPU/IO。
            // 放在 for 之前，不影响 downloading 的复位时机。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // MEM-11：线程只持**进程级**引用（应用上下文 / 静态客户端 / 目标目录）。
            // 原先直接用 `filesDir`、成员函数 `fetchToFile` / `copyCapped` 与 `runOnUiThread`，
            // 匿名线程据此把整个 Activity（含整棵视图树）强引用到下载结束 —— 最长 600s（callTimeout），
            // 用户「点了下载就返回」时旧实例一直回收不掉。收尾改静态 Handler：页面在不在，由
            // [activePage] 弱引用在**收尾那一刻**判定（与原先同语义，但不再要求发起者还活着）。
            val app = applicationContext
            val dir = File(app.filesDir, PinyinEngine.OPT_DICT_DIR)
            val client = httpClient
            var lastError = "未知错误"
            var ok = false
            // 空间预检（L-799）：下载前先确认磁盘可写、容量够得下，避免写到一半 ENOSPC
            // 才收到英文 IOException。需要量 = 压缩包 ×2 + 8MB 余量（解压/校验/移动都要临时空间）。
            val stat = runCatching { android.os.StatFs(dir.absolutePath) }.getOrNull()
            val freeBytes = stat?.availableBytes ?: Long.MAX_VALUE
            val needBytes = (dict.sizeMb * 1024 * 1024 * 2).toLong() + 8L * 1024 * 1024
            if (freeBytes < needBytes) {
                lastError = "存储空间不足：约需 ${needBytes / 1024 / 1024}MB，仅剩 ${freeBytes / 1024 / 1024}MB"
                Diagnostics.w(TAG, "分类词库下载中止：$lastError file=${dict.fileName}")
            } else {
            for (url in dict.urls) {
                runCatching {
                    val len = fetchToFile(client, dir, url, dict.fileName, dict.checksum)
                    Diagnostics.i(TAG, "分类词库下载成功: ${dict.fileName} ($len B) url=$url")
                    ok = true
                }.onFailure {
                    lastError = it.message ?: it.toString()
                    Diagnostics.w(TAG, "分类词库下载失败 url=$url : $lastError")
                }
                if (ok) break
            }
            }
            Handler(Looper.getMainLooper()).post {
                downloading = null
                // 刷新「当前活着的页面」而不是发起下载的那个实例：下载期间用户可能旋转或
                // 关闭重进本页，旧实例上的刷新会写进已 detach 的 View（原实现直接 return），
                // 新页面就停在「按钮禁用、状态行不显示」的旧快照上，要等下次 resume 才自愈。
                // 页面确实不在时（用户已关闭）：View 不能动，但词库已下完、用户本就希望生效，
                // 仍要重启 IME 让引擎加载。
                val page = activePage?.get()
                if (page == null || page.isFinishing || page.isDestroyed) {
                    // ⚠ 页面已关闭时**不重启进程**（2026-10-03 修复 L-547）：本进程同时承载键盘、
                    // 在途翻译请求、composing 未提交文本、剪贴板面板与候选状态 —— 在用户已离开本页
                    // （很可能正在别的应用用本输入法打字）时强杀，会把这些**一起带走**：键盘当场消失、
                    // 未提交输入与在途翻译全部丢失，且用户无从归因到「刚才在下载词库」。
                    // 词库已落盘；这里只置标记，等用户回到本页（前台、键盘收起）时再重启。
                    // 进程若在此期间自然结束，IME 重建时会直接加载 `dicts/` ⇒ 标记无需持久化。
                    Diagnostics.i(TAG, "下载已完成但页面已关闭（ok=$ok），不重启（等用户回到本页）")
                    if (ok) pendingDictRestart = true
                    return@post
                }
                val msg = if (ok) page.getString(R.string.dict_download_done, dict.name)
                else page.getString(R.string.dict_download_failed, lastError)
                // ⚠ 只把「刷界面」兜住（2026-10-03 修复 L-800 + L-812）：`page.refreshList()` 在**主线程**
                // 做文件 IO（listFiles / file.length() / 打开索引读头），一次异常不该让整个 IME 进程崩掉；
                // 但**生效动作必须在这段 try 之外** —— 原先 `restartImeForDict()` 排在同一 try 内，
                // 一次刷新异常就会把「重启让词库生效」静默吃掉，用户看到「说下好了但没生效」。
                runCatching {
                    page.setStatus(msg)
                    // 失败再用 Toast 提示一次（提示挂在系统窗口上，不受页面重建影响）：
                    // 断网点下载时若状态行又随重建消失，用户只会以为按钮坏了
                    if (!ok) Toast.makeText(page, msg, Toast.LENGTH_LONG).show()
                    page.refreshList()
                }.onFailure { Diagnostics.w(TAG, "下载完成刷新界面失败: ${it.javaClass.simpleName}") }
                if (ok) page.restartImeForDict()
            }
        }.apply { isDaemon = true; name = "jinn-dict-download" }.start()
    }

    /**
     * 下载到临时文件、校验 SHA-256 后再改名，避免中途失败留下半个文件被引擎当作有效词库加载
     * （引擎只认 `.xz` 结尾，`X.xz.tmp` 不会被扫到，但失败时仍会残留占空间，所以显式清理）。
     * 返回写入字节数。
     *
     * 摘要校验是收下的唯一判据：词库内容会直接变成候选词上屏到任意输入框，
     * 只要有一处环节能改字节（被替换的 Release 附件、被劫持的重定向、传输截断），
     * 就等于拿到了「往用户每一次输入里塞词」的能力。校验不通过时绝不改名，
     * 旧版本（若存在）保持不变，临时文件立即删除。
     */

    /**
     * 删除已装词库。
     *
     * 下载进行中一律拒绝：下载线程是「写 `.xz.tmp` → 改名成 `.xz`」，
     * 与删除并发时会出现「用户点了删除、删除成功后下载又把包改回来」，
     * 界面上表现为删不掉，而用户以为已经卸载的那个包仍会被引擎加载。
     */
    private fun remove(dict: OptionalDict) {
        if (downloading != null) {
            Toast.makeText(this, R.string.dict_remove_busy, Toast.LENGTH_SHORT).show()
            return
        }
        if (blockedByRestart()) return
        val f = dictFile(dict.fileName)
        val ok = runCatching { f.delete() }.getOrDefault(false)
        Diagnostics.i(TAG, "分类词库删除: ${dict.fileName} ok=$ok")
        setStatus(if (ok) {
            getString(R.string.dict_removed, dict.name)
        } else {
            getString(R.string.dict_remove_failed)
        })
        refreshList()
        if (ok) promptRestart()
    }

    /**
     * 词库只在 IME 启动时加载，增删后必须重启输入法进程才会生效。
     */
    private fun promptRestart() {
        setStatus(getString(R.string.dict_need_restart))
        restartImeForDict()
    }

    /**
     * 重启输入法进程以加载新词库。
     *
     * 延迟 1.5 秒让文件写入落盘；系统会自动重建 IME 服务并加载 `dicts/` 下的词库，
     * 本进程（含本 Activity）随之结束。与设置页 saveAndRestart 同款做法。
     *
     * 用 Handler 而不是 View 的 postDelayed：页面可能已被用户关闭，
     * 挂在已销毁 View 上的延时任务不保证执行，而这里必须执行。
     */
    private fun restartImeForDict() {
        // 1.5s 窗口内不再接受任何改 `dicts/` 的操作（L-801）：置位 + 立刻置灰按钮
        // （置位必须在 refreshList 之前，否则按钮仍是「可点」态）
        restartPending = true
        runCatching { refreshList() }
            .onFailure { Diagnostics.w(TAG, "重启前刷新列表失败: ${it.javaClass.simpleName}") }
        Diagnostics.i(TAG, "分类词库变更：1.5s 后重启输入法进程以加载")
        // SIGKILL 不会走 onDestroy：用户词频的尾沿补写（防抖 2s）与 onDestroy 里的 flush 都来不及跑，
        // 「刚选过候选就来装/删词库」时那几次学习会丢。这里先同步刷一次（文件几千行，1~3ms）。
        runCatching { PinyinEngine.flushUserFrequency() }
            .onFailure { Diagnostics.w(TAG, "重启前刷用户词频失败: ${it.message}") }
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 1500L)
    }

    // ── 工具 ────────────────────────────────────────────────

    private fun dictDir() = File(filesDir, PinyinEngine.OPT_DICT_DIR)

    private fun dictFile(fileName: String) = File(dictDir(), fileName)

    /**
     * 体积文案：口径收在 [ByteSize]（存储占用页要报 GB 量级，两页各写一套必然分叉）。
     *
     * 2026-10-10 起进制改为**十进制**（与 Android `Formatter`、清单 `sizeMb` 同口径）：
     * 本页数字因此与卡片说明里的「5.89MB」对齐（旧文案按 1024 进制写 5.6 MB，对不上账）。
     */
    private fun formatSize(bytes: Long): String = ByteSize.text(bytes)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun wrapWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun buttonLp(right: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { rightMargin = dp(right) }

    /**
     * 等宽按钮（宽 0 + 权重 1）：整行按份数分完，任何字体 / 屏宽下都挤不出屏幕。
     * 自定义词库卡片的三个入口用它（BUG.md L-854）。
     */
    private fun weightedButtonLp(right: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(right) }

    private companion object {

        // 旧版遗留包区的文案在代码里下发：strings.xml 默认不改动，与生僻字页 / 模糊音页的 TEXT_* 同做法
        const val TEXT_LEGACY_DESC = "旧版本装过的词库包，现在的下载源已下线。"

        // 自定义词库的文案同款：按钮复用 strings 里的 dict_action_import_custom，其余代码里下发
        const val TEXT_CUSTOM_TITLE = "自定义词库"
        val TEXT_CUSTOM_DESC = listOf(
            "导入自己写的.txt补充词库 (可备份,随配置导出)",
            "每行都是词组 拼音 (点'快捷补充'有参考)"
        )

        const val TEXT_CUSTOM_IMPORTING = "正在导入自定义词库…"
        const val TEXT_CUSTOM_READ_FAIL = "无法读取该文件"
        const val TEXT_CUSTOM_TOO_BIG = "文件超过 800 万字符上限，请拆分后导入"
        const val TEXT_CUSTOM_TOO_MANY = "词条超过 5 万条上限，请精简后重试"
        const val TEXT_CUSTOM_EMPTY = "没有可导入的合法词条（跳过 %d 行）"
        const val TEXT_CUSTOM_WRITE_FAIL = "写入词库失败"
        const val TEXT_CUSTOM_WRITE_FAIL_PACK = "内容已读入，词库生成失败：请重新导入一次"
        const val TEXT_CUSTOM_BUSY = "正在写入词库，请稍候再试"
        const val TEXT_CUSTOM_RESULT = "已导入 %d 条（跳过 %d 行），输入法将重启以生效"
        const val TEXT_CUSTOM_RESULT_FILTERED =
            "已导入 %d 条（跳过 %d 行），其中 %d 条是单字且不在字表内，候选里不会出现"
        const val TEXT_CUSTOM_RESULT_DROPPED =
            "已导入 %d 条（跳过 %d 行），另有 %d 条因同音超过 100 条未入包"
        const val TEXT_CUSTOM_CONFIRM = "删除后需要重新导入，确定删除自定义词库吗？"
        /** 已有词库时导入前的二次确认（BUG.md L-830）：导入是整体替换，现有词条不会保留 */
        const val TEXT_CUSTOM_IMPORT_CONFIRM = "导入会把自定义词库整份换成本文件的内容（现有词条不再保留），确定继续？"
        const val TEXT_CONFIG_IMPORTING = "配置恢复进行中，暂不可修改词库"

        /** 列表渲染异常时的兜底提示（BUG.md L-831：刷新失败不崩进程，重进本页即恢复） */
        const val TEXT_REFRESH_FAIL = "刷新词库列表失败，重进本页可恢复"

        /** 回到本页补做重启时的提示（2026-10-03 修复 L-547） */
        const val TEXT_RESUME_RESTART = "词库已下载完成，输入法将重启以加载"

        /** 旧版包的状态行与按钮（2026-10-10 换包检测，判据 [OptionalDicts.isCurrentPack]） */
        const val TEXT_STATUS_STALE = "需更新（本地为旧版 %s）"
        const val TEXT_ACTION_UPDATE = "更新"

        /**
         * 下载完成时页面已关闭、因而**没有**重启进程（2026-10-03 修复 L-547）。
         *
         * 只需在进程内存活：进程若自然结束，IME 重建时会直接加载 `dicts/` ⇒ 标记无需持久化。
         */
        @Volatile
        private var pendingDictRestart = false

        /**
         * 「已安排重启」窗口（2026-10-04 修复 L-801）：[restartImeForDict] 延迟 1.5s 才 SIGKILL，
         * 而这段时间里 `activeDownload` 已是 null、按钮又是「可点」态 ⇒ 用户能再发起一次下载或删除，
         * 写到一半被随后的 kill 打断（还会叠加 L-798 的残留），而他刚看到 Toast「输入法将重启以加载」。
         * 置位后所有会改 `dicts/` 的入口一律拒绝，直到进程真的结束（本进程内的窗口，无需持久化）。
         */
        @Volatile
        private var restartPending = false

        /** 旧包的加载提示：完整一句话（旧包没有实测耗时，见 [buildUnknownCard] 的 KDoc） */
        const val TEXT_LEGACY_LOAD = "空闲时才在后台加载（息屏或收起键盘后生效）。"
        const val TEXT_LEGACY_EXT = "长词包（旧版）"
        const val TEXT_LEGACY_TENCENT = "腾讯大词库（旧版）"
        const val TEXT_LEGACY_CONFIRM = "这些包已没有下载源，删除后无法重新下载。确定删除吗？"
        const val TEXT_CONFIRM_OK = "删除"
        const val TEXT_CONFIRM_CANCEL = "取消"

        /** 装了包但索引还没跟上（后台空闲时装载；换了包会重建）—— 文案在代码里下发，与本节其它 TEXT_* 同做法 */
        const val TEXT_INDEX_PENDING =
            "索引未就绪：息屏或收起键盘后会自动装载。若长时间没变化，请删除该包后重新下载。"


        /**
         * 词库下载用的共享客户端（懒加载单例）。
         *
         * 每个 URL 都 new 一次会各建一套连接池与调度线程池，重试（Gitee 失败 → GitHub）
         * 与反复点击会在同一进程里叠加 —— `AsrClient` 的注释已把这条列为反例。
         *
         * 超时与重定向策略固定，且**只准 TLS**（BUG.md L-100）：
         *  - `connectionSpecs(MODERN_TLS)` 去掉 CLEARTEXT ⇒ 请求 URL 只可能是 https（词库 URL
         *    全是 https 字面量，见 `OptionalDicts`）；即便将来有人误加一条 http 源，也会在这里
         *    直接失败并留日志，而不是走明文；
         *  - `followSslRedirects(false)` 再加一道：OkHttp 默认会跟随 https→http 跳转，
         *    一次 302 就能把词库下载降到明文 HTTP，同网段 MITM 改内容即可注入任意候选词；
         *  - 收下的最后一关是 SHA-256 校验（见 [fetchToFile]），三者叠加才叫「改不了字节」。
         */
        private val httpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                // 整体预算（2026-10-03 修复 L-548）：只有 connect/read 两层时，服务端「开始回包后极慢地
                // 吐字节」（每 179s 一个字节）能让下载线程挂到理论无限，而 `activeDownload` 只在成功/失败
                // 时清 ⇒ 按钮永久禁用、用户既不能取消也没有提示。600s 覆盖 64MB 上限在慢速网络下的合理耗时。
                .callTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
                .connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS))
                .followRedirects(true)
                .followSslRedirects(false)
                .build()
        }

        @Volatile
        private var activeDownload: String? = null

        /**
         * 当前活着的页面实例（onResume 登记 / onPause 清除）。
         *
         * 下载完成回调捕获的是**发起下载**的那个 Activity，而下载期间页面可能已被重建：
         * 刷新必须落在「用户眼前这个实例」上，否则新页面拿不到这次刷新（弱引用，不延长生命周期）。
         */
        @Volatile
        private var activePage: WeakReference<DictManagerActivity>? = null

        /** 单个词库包的下载上限（字节）：现役最大包 5.89MB（第 2 部分，2026-10-10 重切后），取 64MB 留足余量 */
    }
}


// ── 词库下载的文件级实现（MEM-11）────────────────────────────
// 搬出类体的原因：下载线程不能碰任何实例成员，否则匿名线程会把 Activity 一起持久化；
// 这些常量原先在 `private companion object` 里，顶层函数看不到，故一并搬出（同文件可见性不变）。
private const val TAG = "DictManager"
private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
private fun fetchToFile(client: okhttp3.OkHttpClient, dir: File, url: String, fileName: String, checksum: String): Long {
    dir.mkdirs()
    val tmp = File(dir, OptionalDicts.tempNameOf(fileName))
    val dst = File(dir, fileName)

    try {
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("响应为空")
            body.byteStream().use { input ->
                // 落盘（fsync）必须早于改名：摘要是在**页缓存**上算的，不 sync 就 rename，
                // 掉电后可能留下「名字合法、内容半截」的 .xz —— 引擎按后缀无条件扫 dicts/，
                // 只会记一条加载失败并当作未装，而词库页按文件存在渲染成「已装」，
                // 旧包又已被 rename 覆盖、无法回退。本仓其余 tmp→rename 都带这一步
                // （CustomDicts / UserFrequency），这条此前漏了。
                FileOutputStream(tmp).use { out ->
                    copyCapped(input, out)
                    out.fd.sync()
                }
            }
        }
        // 校验必须在改名之前：一旦 rename 成 `.xz`，引擎下一次空闲加载就会扫到它。
        val actual = OptionalDicts.sha256Of(tmp)
        if (!OptionalDicts.matchesChecksum(actual, checksum)) {
            error("文件校验失败（期望 $checksum，实际 $actual），已丢弃")
        }
        // 不先删旧包：POSIX 的 rename(2) 本身就是原子替换（目标已存在也直接覆盖），
        // 而「先删 → 改名」在改名失败时会让用户同时失去旧包与新包
        // （`UserFrequency.writeAtomically` 的注释把「先删目标」列为反例，同一形态）
        if (!tmp.renameTo(dst)) error("写入失败")
        return dst.length()
    } catch (t: Throwable) {
        // 半截文件清掉：否则一次失败就在用户存储里留 6MB 垃圾
        runCatching { if (tmp.exists()) tmp.delete() }
            .onFailure { Diagnostics.w(TAG, "清理临时文件失败: ${it.message}") }
        throw t
    }
}

/**
 * 带上限的流拷贝，超过 [MAX_DOWNLOAD_BYTES] 立即抛错（临时文件由调用方清理）。
 *
 * 下载 URL 是固定的 Release 附件，但 `followRedirects(true)` 会把请求交给目标主机
 * 继续指路：没有上限时，一个「一直有数据、永不结束」的响应足以写满用户存储。
 * 上限取现役最大包（6.36MB）的约 10 倍，正常包碰不到线。
 */
private fun copyCapped(input: java.io.InputStream, out: java.io.OutputStream) {
    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        total += n
        if (total > MAX_DOWNLOAD_BYTES) {
            error("响应超过上限 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB，已中止")
        }
        out.write(buf, 0, n)
    }
}
