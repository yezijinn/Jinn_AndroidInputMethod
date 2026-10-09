package com.jinn.inputmethod

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.io.FileOutputStream

/**
 * 快捷补充：直接编辑自定义词库的**源文本**，保存即等于走一遍导入。
 *
 * 与「导入 .txt」共享同一份内容：导入成功后把原文写进 `dicts/custom_user.src.txt`
 * （见 [CustomDicts.writeSource]），本页打开时回显；本页保存也写同一文件。所以两个入口
 * 互为「同一份文本的两种打法」，用户先导入再进来补两行也不会互相覆盖。
 *
 * 保存结果用 `RESULT_OK`（带条数与跳过行数）回传给 [DictManagerActivity]，由它统一提示并
 * 重启输入法 —— 重启窗口的状态位在那边，本页自己 kill 会把「已保存」的提示一起吞掉。
 *
 * 版面与词库页同源（深蓝背景 + 顶栏右关闭），输入区走等宽字体并占满剩余高度：
 * 「一行一条」的文本在视觉上就是一列，眼睛好对齐。
 */
class CustomDictEditActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var textStatus: TextView
    private lateinit var saveButton: TextView

    /**
     * 顶栏「✕」。保存期间与「保存」一起置灰并拦返回键（BUG.md L-860）：
     * 此前保存中点 ✕ 会先 `finish()`，写盘继续但结果回执被 `isFinishing` 早退吃掉 ——
     * 包已更新却不重启引擎、词库页也不刷新，用户以为「没保存」。
     */
    private lateinit var closeButton: TextView

    /** 保存线程在跑时按钮置灰，防连点（与词库页的 importingCustom 同款） */
    @Volatile
    private var saving = false

    /**
     * 本次因源文本超出回填阈值而**没有**把内容铺进编辑器（BUG.md L-871）。
     *
     * 置上后保存保持禁用：编辑器是空的，用户顺手补几条再保存，就会把整份大词表（源文本 + 包）
     * 整体替换掉，而提示只显示「已保存 N 条」—— 那是静默的数据丢失。
     */
    private var refillSkipped = false

    /** 载入时记下的源文本身份（null = 当时没有源文本）；保存前再取一次比对，见 [save]（BUG.md L-896） */
    private var loadedSourceStamp: Long? = null

    /**
     * 上面那份身份是否可信（BUG.md L-899）。
     *
     * 载入分「读内容」与「取身份」两步：源文本在两步之间被外部改名落盘时，记下的是**新**身份、
     * 编辑器里铺的是**旧**内容 ⇒ 保存时比对相等、不弹确认，旧文本静默盖掉新词库。
     * 读前读后各取一次即可发现，不一致就把身份标成不可信，保存时一律按「已变化」处理。
     */
    private var sourceStampTrusted = false

    /** 编辑器里有未保存改动（返回 / ✕ 时据此确认，BUG.md L-897） */
    private var dirty = false

    /** 正在程序化回填（`editor.setText`）：期间的文本变化不算用户改动 */
    private var refilling = false

    /**
     * 大稿的增量落盘（BUG.md L-1173）：输入停顿后由 [BackgroundIo] 写文件，重建时不必再同步写整份。
     *
     * 新鲜度按**修订号**判定（BUG.md L-1177）：[draftRevision] 每次文本变化自增，
     * [draftWrittenRevision] 记下已落盘那份对应的修订号（-1 = 没有可用文件）。
     * 此前比的是文本长度 —— 同长度改写（覆盖粘贴、替换一个字符）会让长度相等而内容不同，
     * 重建时只带令牌，新实例读回的是改写前的草稿，最后一次编辑静默丢失。
     */
    private val draftHandler = Handler(Looper.getMainLooper())
    // ⚠ 修订号是**实例内**单调计数器（重建后从 0 重新开始）：比较只发生在同一实例内
    // （draftWrittenRevision 记录的那个号也是本实例写的），**禁止跨实例比较** ——
    // 两个实例各自的 0/1/2 毫无关系。要跨实例判定新鲜度请另用时间戳 + 长度（BUG-32）。
    private var draftRevision = 0
    @Volatile
    private var draftWrittenRevision = -1

    /**
     * 写入世代：每批落盘与每次同步补写自增。分块任务投递到 [BackgroundIo] 之后无法撤销，
     * 靠它与投递时的世代比对自行作废（BUG.md L-1180）。
     */
    @Volatile
    private var draftWriteGeneration = 0

    /**
     * 草稿发布锁：把「复核世代」与「临时名 → 正式名」的改名绑成一个原子步（BUG-25）。
     *
     * 两处参与：分块任务的末块发布、保存侧的同步补写。不锁的话，保存侧能挤进分块的
     * 「复核通过」与「改名」之间自增世代并写完正式名，随后分块用旧快照盖回去 ——
     * 正式名是旧内容，修订号却已被标成最新，重建时不再补写。
     */
    private val draftLock = Any()

    /**
     * 草稿文件还在、编辑器里还没有它（读盘在途 / 读失败待重试 / 大稿等首帧铺）：
     * 保存时把令牌与预期长度原样带下去，下一次重建接着读（BUG.md L-1185 / L-1190 / L-1192）。
     * 铺进编辑器之后由 [finishDraftApply] 清掉。
     */
    private var pendingDraftFile: String? = null

    /** [pendingDraftFile] 对应的预期字符数（0 = 不知道；恢复端据此判「写到一半」，BUG.md L-1192） */
    private var pendingDraftLen = 0

    /**
     * 编辑器里的内容是刚铺进来的草稿（而不是用户敲的，也不是源文本）。
     *
     * 只用于状态行（BUG-23）：草稿铺完会把「草稿已恢复」写上状态行，紧接着 `loadSource` 的回填
     * 判据看到编辑器非空而跳过 —— 那句提示若照旧写成「编辑框里已有你输入的内容」，
     * 就把刚刚恢复的草稿说成了用户自己的输入，用户会以为草稿没恢复成功。
     */
    private var draftJustRestored = false

    /** 大稿草稿已排进首帧之后、还没铺进编辑器（BUG.md L-1181） */
    private var draftPending = false

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

    /** 主题应用点：与设置页同一套（早于 onCreate，避免先按系统配置渲染一帧再换色） */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 键盘由 Manifest 的 `stateAlwaysVisible|adjustResize` 拉起（本页用途就是「直接开写」，
        // 不该再让用户点一次输入框）。这里**不再**调 window.setSoftInputMode：它会把 adjust 位
        // 清成 UNSPECIFIED、与 Manifest 声明打架（BUG.md L-855）；真机实测同样会弹键盘。

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }
        root.addView(buildTopBar())
        root.addView(hint(TEXT_HINT_LINE1), matchWrap(top = 8))
        root.addView(hint(TEXT_HINT_LINE2), matchWrap(top = 2))
        root.addView(hint(TEXT_HINT_LINE3), matchWrap(top = 2))

        textStatus = TextView(this).apply {
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            setPadding(dp(16), dp(6), dp(16), 0)
            visibility = View.GONE
            // 状态行是这页唯一的失败/进度反馈通道：读屏要能听见它变了（BUG.md L-1195）
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(textStatus, matchWrap())

        editor = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            hint = TEXT_EDITOR_HINT
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        // 外观走卡片同款（底色 / 圆角 / 描边 / 内边距）；padding 由 dressAsCard 统一给
        PageStyle.dressAsCard(editor)
        // 未保存改动的判据（BUG.md L-897）：程序化回填期间的变化不算，其余都算用户改过
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (refilling) return
                dirty = true
                draftRevision++
                scheduleDraftWrite(s?.length ?: 0)
            }
        })
        root.addView(
            editor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply {
                topMargin = dp(8)
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(10)
            },
        )

        // 重建时恢复未保存的草稿（BUG.md L-1163）：编辑器是代码创建、无 android:id，系统不会
        // 替它保存内容；定时换色（主题节拍器）与系统深浅色切换都会重建本页。先铺回草稿，
        // loadSource() 的回填分支会因「编辑框已有内容」自动跳过，不会把草稿盖掉。
        // 小稿在实例状态里、大稿在 cacheDir 临时文件里（BUG.md L-1169）；读盘放后台（L-1193）
        savedInstanceState?.let { startDraftRestore(it) }

        setContentView(root)
        Diagnostics.i(TAG, "CustomDictEditActivity: 打开快捷补充页")
        loadSource()
    }

    /**
     * 跨重建保存草稿（BUG.md L-1163 / L-1169）。
     *
     * 读屏 / 输入法之外的另一种丢失路径：`recreate()` 会把未保存的编辑连同 `dirty` 一起清掉，
     * 而 `confirmExit()` 只在 `dirty` 为真时才问「放弃未保存的改动」⇒ 重建后连确认都不弹。
     *
     * **按长度分档**：小稿照旧进实例状态；大稿写 `cacheDir` 临时文件、Bundle 只留文件名 ——
     * 编辑框没有长度上限（保存路径给的界是 `MAX_INPUT_CHARS * 2` = 16M 字符），整份进 Bundle
     * 会在系统侧事务上超限（约 1MB 量级），崩溃且草稿全丢。
     *
     * 大稿平时由 [scheduleDraftWrite] 增量落盘，这里只在「文件不是最新」时才同步补一次
     * （刚打完就重建的窄场景）；两种情况都要保证新实例读到的是完整文件（BUG.md L-1173）。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val text = editor.text?.toString() ?: ""
        // 文件还在、编辑器里没有它（读盘在途 / 读失败待重试 / 大稿等首帧铺）：令牌与预期长度原样带下去，
        // 下一次重建接着读（BUG.md L-1185 / L-1190 / L-1192）。用户一动手就作废这条支路 ——
        // 他现在的编辑比那份草稿更要紧
        val pending = pendingDraftFile
        // BUG-22：判据里原先还有 `!dirty`，于是「读盘/铺稿在途时用户先敲了字」（dirty 置真、
        // 编辑器里还没有草稿）会掉进下面的分支 —— 大稿此时 `text` 是空的，`draftInBundle(0)` 为真，
        // Bundle 里只留下一个**空串**与「没有文件令牌」，新实例两头都拿不到 ⇒ 那份草稿成了孤儿文件。
        // 去掉 `!dirty` 即可：文件还在、且没有比它更新的已落盘修订，就该把令牌带下去。
        if (pending != null && draftWrittenRevision < 0) {
            outState.putString(STATE_EDITOR_FILE, pending)
            outState.putInt(STATE_DRAFT_LEN, pendingDraftLen)
            outState.putBoolean(STATE_DIRTY, dirty)
            return
        }
        if (draftInBundle(text.length)) {
            outState.putString(STATE_EDITOR_TEXT, text)
        } else if (draftWrittenRevision == draftRevision) {
            // 增量落盘已是最新：Bundle 只带令牌，保存侧不再碰主线程
            outState.putString(STATE_EDITOR_FILE, DRAFT_FILE)
            outState.putInt(STATE_DRAFT_LEN, text.length)
        } else {
            // 同步补写也走临时名 + 改名（BUG.md L-1185）：直接写正式名时被中断（掉电 / 被杀）会留下半截
            // 文件，而恢复端此前会把它当完整草稿铺开、读完又删掉 —— 大稿只有这一份副本。
            // 临时名与增量落盘分开，并先行作废在途的分块任务（时间戳更强的世代号）
            draftHandler.removeCallbacks(draftWriteTask)
            // 自增与改名都进 [draftLock]（BUG-25）：只自增不锁，分块任务可能在复核通过之后、
            // 改名之前被自增，然后照旧把旧快照发布出去。全文写入不进锁（那是 48MB 级的 IO，
            // 锁内只做「自增」与「改名」两个瞬时动作）。
            synchronized(draftLock) { draftWriteGeneration++ }
            val staged = File(cacheDir, DRAFT_FILE_SAVE_TMP)
            val written = runCatching {
                writeTextChunked(staged, text)
                synchronized(draftLock) { staged.renameTo(File(cacheDir, DRAFT_FILE)) }
            }.getOrDefault(false)
            if (written) {
                draftWrittenRevision = draftRevision
                // 新草稿已落地 ⇒ 留档槽里的旧稿被更新的内容取代（BUG-30：只留最新一份，不堆积）。
                // 放在成功分支里：块内最后一句会变成 runCatching 的返回值，且落盘失败时不该先丢留档。
                runCatching { File(cacheDir, DRAFT_FILE_ARCHIVE).delete() }
                outState.putString(STATE_EDITOR_FILE, DRAFT_FILE)
                outState.putInt(STATE_DRAFT_LEN, text.length)
                pendingDraftFile = null
            } else {
                // 落盘失败（空间不足等）时退一步：截断进 Bundle，不崩、留住开头那部分；
                // 同时留一个可见标记，恢复后由状态行说明（BUG.md L-1174）
                val kept = text.take(DRAFT_BUNDLE_MAX_CHARS)
                outState.putString(STATE_EDITOR_TEXT, kept)
                outState.putInt(STATE_DRAFT_TRUNCATED, kept.length)
            }
        }
        outState.putBoolean(STATE_DIRTY, dirty)
    }

    /**
     * 输入停顿 [DRAFT_WRITE_DEBOUNCE_MS] 后落一次盘（只对大稿；缩回上限以内则删掉旧件）。
     *
     * 写文件走 [BackgroundIo]（全应用唯一的单线程 IO 队列），`toString` 快照留在主线程但只做一次拷贝。
     * 临时名 + `renameTo`：新实例读到的永远是完整文件，不会撞上写了一半的版本（BUG.md L-1173）。
     */
    private fun scheduleDraftWrite(length: Int) {
        draftHandler.removeCallbacks(draftWriteTask)
        if (length <= DRAFT_BUNDLE_MAX_CHARS && draftWrittenRevision < 0) return
        draftHandler.postDelayed(draftWriteTask, DRAFT_WRITE_DEBOUNCE_MS)
    }

    private val draftWriteTask = Runnable {
        // 修订号与文本在同一趟主线程里取，两者必然对应（BUG.md L-1177）
        val revision = draftRevision
        val text = editor.text?.toString() ?: ""
        if (text.length <= DRAFT_BUNDLE_MAX_CHARS) {
            // 缩回上限以内：Bundle 那条链自己带得动，文件版草稿留着只会变成残留（BUG.md L-1176）
            draftWrittenRevision = -1
            val dest = File(cacheDir, DRAFT_FILE)
            BackgroundIo.run { runCatching { dest.delete() } }
            return@Runnable
        }
        enqueueDraftChunk(text, revision, ++draftWriteGeneration, 0)
    }

    /**
     * 分块落盘（BUG.md L-1180）：[BackgroundIo] 是全应用唯一的串行队列，整份大稿（最长 16M 字符）
     * 一次写进去会把排队其后的剪贴板入库与搜索解密一起堵住。改成按块投递 —— **每块一个任务**，
     * 队列在块之间能处理别的活儿；下一块由当前块自己续投，绝不让两代写入交错（同一条队列上串行）。
     *
     * 世代号 [draftWriteGeneration] 让「已作废的批次」自行停下：用户又改了、或保存侧要同步补写时，
     * 世代自增，尚未开跑的分块直接返回，正在跑的那一块写完就停。
     */
    private fun enqueueDraftChunk(text: String, revision: Int, generation: Int, from: Int) {
        // 走长活池（MEM-30）：分块写整份大稿是以秒计的活，投进交互短活队列会把剪贴板入库与
        // 面板首屏压在后头。长活池同样是单线程，**保序不变**，世代校验与「每块一任务」的
        // trampoline 形态也不变。
        BackgroundIo.runLong {
            if (generation != draftWriteGeneration) return@runLong
            var end = minOf(from + DRAFT_WRITE_CHUNK_CHARS, text.length)
            // 别把代理对劈成两半：分块各自编码，劈开会让半个字符变成替换符
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            val last = end >= text.length
            val ok = runCatching {
                // 第一块截断、其余追加（同一队列串行执行，顺序有保证）
                FileOutputStream(File(cacheDir, DRAFT_FILE_TMP), from == 0)
                    .use { writeChunk(it, text, from, end) }
                if (last) {
                    // 发布前再查一次世代（BUG.md L-1189）：本任务开头查过之后，保存侧可能已经自增世代
                    // 并写好了正式名 —— 此时再改名就等于用旧快照盖掉刚写的那份，而修订号已被标成最新。
                    //
                    // 复核与改名必须在**同一把锁**里（BUG-25）：分开做时，保存侧能挤在两者之间自增世代
                    // 并写完正式名，随后这里再把旧快照改过去 —— 正式名成了旧内容，修订号却被标成最新。
                    //
                    // 返回**改名结果**（BUG-24）：原先块末尾是字面量 `true`，改名失败（空间不足 / 被占）
                    // 也被当成成功，于是 `draftWrittenRevision` 被标成最新，之后重建只带令牌、不再补写，
                    // 而正式名里其实是上一代内容。
                    synchronized(draftLock) {
                        if (generation != draftWriteGeneration) return@runLong
                        File(cacheDir, DRAFT_FILE_TMP).renameTo(File(cacheDir, DRAFT_FILE))
                    }
                } else {
                    true
                }
            }.getOrDefault(false)
            if (!ok) {
                draftWrittenRevision = -1
                return@runLong
            }
            if (last) draftWrittenRevision = revision else enqueueDraftChunk(text, revision, generation, end)
        }
    }

    /**
     * 草稿文件只在本实例**不再重建**时收尾删除（BUG.md L-1176）：重建时新实例还要读它，
     * 而保存成功 / 放弃 / 直接关闭三条出口都不该把它留在 cacheDir 里。
     */
    override fun onDestroy() {
        // 去抖任务**无条件**撤销（BUG.md L-1178）：重建时它捕获的是旧实例的编辑器，而落盘文件是全进程
        // 共用的那一份 —— 主线程被占住（大稿恢复、图库解码）时它可能在新实例写完之后才跑，把旧内容盖上去。
        // 文件本体则只在「不再重建」时删，重建时新实例还要读它。
        draftHandler.removeCallbacks(draftWriteTask)
        if (!isChangingConfigurations) {
            for (name in listOf(DRAFT_FILE, DRAFT_FILE_TMP, DRAFT_FILE_SAVE_TMP)) {
                runCatching { File(cacheDir, name).delete() }
            }
        }
        super.onDestroy()
    }

    /**
     * 恢复未保存的草稿（BUG.md L-1163 / L-1169 / L-1193）。
     *
     * 小稿在实例状态里，直接铺；大稿在 [cacheDir] 的临时文件里，**读盘放后台**：
     * 整份上限 16M 字符，慢存储上单次读取要几百毫秒，此前同步做在 `onCreate` 里会挡住首屏。
     *
     * 文件**读到时不删**（BUG.md L-1190）：铺进编辑器之前它仍是唯一副本。这段窗口里再重建
     * （首帧前、或者读盘还在途），[pendingDraftFile] / [pendingDraftLen] 会把令牌原样交给下一个实例。
     * 读失败同样保留文件与令牌（BUG.md L-1185），下一次重建再试。
     */
    private fun startDraftRestore(state: Bundle) {
        val inline = state.getString(STATE_EDITOR_TEXT)
        val restoredDirty = state.getBoolean(STATE_DIRTY, true)
        if (!inline.isNullOrEmpty()) {
            applyDraft(inline, state.getInt(STATE_DRAFT_TRUNCATED, 0), null, restoredDirty)
            return
        }
        val draftName = state.getString(STATE_EDITOR_FILE) ?: return
        pendingDraftFile = draftName
        pendingDraftLen = state.getInt(STATE_DRAFT_LEN, 0)
        // 文件路径一开读就算「草稿在途」（BUG.md L-1196）：读盘回来之前编辑器是空的，而源文本回填与
        // 保存使能都按「草稿已经在里面」判断 —— 不先占住这一位，回填会把旧源文本铺进来，
        // 草稿到达时反而被当成「用户已敲字」丢掉
        draftPending = true
        // BUG-26：崩溃可能停在「同步补写写完临时名、改名还没做」那一瞬 —— 正式名要么不存在、
        // 要么还是上一代。旧实现无条件删掉两个临时名，等于把用户唯一完整的那份一起删了。
        // 改为把「比正式名新」或「正式名缺失时存在」的临时件**改名**成正式名再读；rename 是原子替换，
        // 也不会撞「恢复阶段不许删草稿本体」那条守卫。
        promoteNewestDraftTmp(draftName)
        Thread {
            // 整份读盘与词库装载同一口径：降后台优先级，别和前台争 CPU
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val file = File(cacheDir, draftName)
            val existed = file.isFile
            // 读一次就够（BUG-28）：草稿的发布是「临时名 → 正式名」的原子改名，同目录 rename 不存在
            // 读到半截的可能；失败再读一次只是把同一个 IO 错误重试一遍。失败时文件保持原样，
            // 状态行照旧承诺「重建时自动再试」。
            //
            // 带上限读（BUG-31）：上限取编辑器自身的界（与保存路径同源），超限时**不**当「读取失败」——
            // 文件仍在、内容也完整，只是这一版不铺；把「过大」报成「损坏」会让用户以为稿子坏了，
            // 而反复重读几十 MB 的文件又会在每次重建时挨一次主线程/内存。
            val restoreLimit = CustomDicts.MAX_INPUT_CHARS * 2
            var tooLarge = false
            var fromArchive = false
            var restored = if (existed) {
                runCatching {
                    file.reader(Charsets.UTF_8).use { reader ->
                        CustomDicts.readCapped(reader, restoreLimit) ?: run { tooLarge = true; null }
                    }
                }.getOrNull()
            } else {
                null
            }
            // 本体不在了（系统清缓存 / 已被收尾删除）：看一眼留档槽（BUG-30）。
            // 那是「上次草稿没铺进去、用户已抢先输入」时留下的旧稿 —— 用户手上最新的那份长文。
            if (restored == null && !tooLarge) {
                val archived = File(cacheDir, DRAFT_FILE_ARCHIVE)
                if (archived.isFile) {
                    restored = runCatching {
                        archived.reader(Charsets.UTF_8).use { reader ->
                            CustomDicts.readCapped(reader, restoreLimit) ?: run { tooLarge = true; null }
                        }
                    }.getOrNull()
                    fromArchive = restored != null
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                draftPending = false
                if (restored == null) {
                    if (!existed) {
                        // 文件不在了（系统清缓存 / 已被收尾删除）：令牌作废，别承诺「保留待重试」（BUG.md L-1197）
                        pendingDraftFile = null
                        pendingDraftLen = 0
                        Diagnostics.w(TAG, "快捷补充：草稿文件已不在缓存里（$draftName）")
                        setStatus(TEXT_DRAFT_GONE)
                    } else if (tooLarge) {
                        // 超过恢复上限（BUG-31）：文件完好、只是这一版不铺；不删文件、也不说成「读取失败」
                        Diagnostics.w(TAG, "快捷补充：草稿超过恢复上限（$restoreLimit 字符），保留原文件")
                        setStatus(String.format(Locale.US, TEXT_DRAFT_TOO_LARGE, restoreLimit / 10000))
                    } else {
                        Diagnostics.w(TAG, "快捷补充：草稿文件读取失败（${file.length()} 字节），保留待重试")
                        setStatus(TEXT_DRAFT_UNREADABLE)
                    }
                    return@runOnUiThread
                }
                if (fromArchive) {
                    // 铺的是留档稿：令牌换成留档槽名，收尾时由同一条路径把它消费掉（不再算「待恢复草稿」）
                    pendingDraftFile = DRAFT_FILE_ARCHIVE
                    pendingDraftLen = 0
                    val willSkip = editor.text.isNotEmpty() && dirty
                    applyDraft(restored, 0, DRAFT_FILE_ARCHIVE, restoredDirty)
                    // 只有真铺进去才改状态行（未铺入时 applyDraft 已写明「未铺入」，不许被这句盖掉）；
                    // 大稿走首帧后铺的分支，状态行由那条路径自己写。
                    if (!willSkip && !draftPending) setStatus(TEXT_DRAFT_ARCHIVED)
                    return@runOnUiThread
                }
                val expected = pendingDraftLen
                val truncatedAt = if (expected > 0 && restored.length < expected) {
                    // 比预期短 = 写到一半被杀 / 掉电（BUG.md L-1185）：不删文件，按截断提示
                    Diagnostics.w(TAG, "快捷补充：草稿文件不完整（${restored.length} / $expected 字符）")
                    restored.length
                } else {
                    0
                }
                applyDraft(restored, truncatedAt, draftName, restoredDirty)
            }
        }.apply { isDaemon = true; name = "jinn-draft-restore" }.start()
    }

    /**
     * 分段编码写一段文本（MEM-08② / BUG-27）。
     *
     * 原先两处各自「先 substring 再 toByteArray」：一次分块写要两份临时拷贝，兜底路径更是
     * 把整份 16M 字符一次编成约 48MB 的字节数组（还跑在 onSaveInstanceState 的主线程上）。
     * 这里直接按 CharBuffer 视图编码并写进信道：一次分配（约 192KB / 块）、无中间 String。
     * 调用方负责块边界不劈开代理对（见 [enqueueDraftChunk] 的 end--）。
     */
    private fun writeChunk(out: FileOutputStream, text: CharSequence, from: Int, end: Int) {
        val bytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(text, from, end))
        while (bytes.hasRemaining()) out.channel.write(bytes)
    }

    /** 把整份文本按块写进 [dest]（覆盖写；兜底路径用，见 [writeChunk]） */
    private fun writeTextChunked(dest: File, text: CharSequence) {
        FileOutputStream(dest, false).use { out ->
            var from = 0
            while (from < text.length) {
                var end = minOf(from + DRAFT_WRITE_CHUNK_CHARS, text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                writeChunk(out, text, from, end)
                from = end
            }
        }
    }

    /**
     * 发布崩溃残留的临时草稿：见 [startDraftRestore] 的 BUG-26 说明。
     *
     * 提升顺序按「完整度」排：`SAVE_TMP` 是保存侧一次写成的全文，`TMP` 是分块增量（可能只写到一半）。
     * 只有比正式名更新的才提升；提升后正式名的时间戳跟着变，后面那个临时件自然输给它。
     * 长度为零的临时件直接跳过（写到一半就被杀的那份没有恢复价值）。
     */
    private fun promoteNewestDraftTmp(draftName: String) {
        val target = File(cacheDir, draftName)
        for (tmp in listOf(DRAFT_FILE_SAVE_TMP, DRAFT_FILE_TMP)) {
            val f = File(cacheDir, tmp)
            if (!f.isFile || f.length() == 0L) continue
            if (target.isFile && f.lastModified() <= target.lastModified()) {
                runCatching { f.delete() }
                continue
            }
            runCatching { f.renameTo(target) }.onSuccess {
                Diagnostics.w(TAG, "快捷补充：草稿主体缺失或更旧，已把 $tmp 提升为 $draftName")
            }
        }
    }

    /**
     * 把草稿铺进编辑器。铺之前先看编辑器里有没有东西（BUG.md L-1191）：读盘在途、
     * 或大稿排在首帧之后铺的这段时间里用户可能已经敲了字 —— 那些输入优先，草稿不铺
     * （与「用户已开始输入就跳过回填」同一口径，BUG.md L-849）。
     */
    private fun applyDraft(draft: String, truncatedAt: Int, file: String?, restoredDirty: Boolean) {
        // 让路只让给**用户真敲进去**的内容（BUG.md L-1196）：程序回填不会置 dirty，
        // 读盘在途那段时间里被铺进来的旧源文本要能被草稿盖掉（改动前就是草稿优先）
        if (editor.text.isNotEmpty() && dirty) {
            Diagnostics.i(TAG, "快捷补充：草稿未铺入（编辑框已有你输入的 ${editor.text.length} 字符）")
            discardPendingDraft()
            setStatus(TEXT_DRAFT_SKIPPED)
            return
        }
        if (!dirty) dirty = restoredDirty
        refilling = true
        if (draft.length > DRAFT_BUNDLE_MAX_CHARS) {
            // 大稿的排版代价随长度走（真机实测 880k 行 / 8.1MB 让主线程卡 18 秒，见 MAX_REFILL_CHARS）：
            // 推迟到首帧之后再铺，页面先可见，状态行说明发生了什么（BUG.md L-1173）
            setStatus(TEXT_DRAFT_RESTORING)
            // 排进首帧之后到铺完之前，编辑器仍是空的：这段窗口里 loadSource() 的回填判据
            // 会误判成「没有草稿」（BUG.md L-1181）
            draftPending = true
            editor.postOnAnimation {
                draftPending = false
                refilling = false
                if (editor.text.isNotEmpty() && dirty) {
                    Diagnostics.i(TAG, "快捷补充：草稿未铺入（铺之前编辑器已有你输入的 ${editor.text.length} 字符）")
                    discardPendingDraft()
                    setStatus(TEXT_DRAFT_SKIPPED)
                    return@postOnAnimation
                }
                editor.setText(draft)
                finishDraftApply(file, truncatedAt)
            }
        } else {
            editor.setText(draft)
            refilling = false
            finishDraftApply(file, truncatedAt)
        }
    }

    /** 铺完收尾：删文件、清令牌、给状态行 —— 文件留到这一刻才删（BUG.md L-1190）。 */
    private fun finishDraftApply(file: String?, truncatedAt: Int) {
        if (file != null) runCatching { File(cacheDir, file).delete() }
        pendingDraftFile = null
        pendingDraftLen = 0
        draftPending = false
        draftJustRestored = true
        setStatus(if (truncatedAt > 0) String.format(Locale.US, TEXT_DRAFT_TRUNCATED, truncatedAt) else TEXT_DRAFT_RESTORED)
    }

    /**
     * 草稿不再铺了（用户抢先输入）：清令牌，并把草稿**留档**而不是删掉（BUG-30）。
     *
     * 「恢复不覆盖编辑框里已有输入」是既有口径（L-1191），但那不该以丢掉用户旧长文为代价：
     * 旧稿改名进 [DRAFT_FILE_ARCHIVE]（只留最新一份），下次打开本页且没有更新的草稿时会被铺回来。
     * 改名失败（跨目录/被占）时退回旧的删除行为，不留下半截状态。
     */
    private fun discardPendingDraft() {
        val file = pendingDraftFile
        if (file != null) {
            val src = File(cacheDir, file)
            val dst = File(cacheDir, DRAFT_FILE_ARCHIVE)
            val bytes = runCatching { src.length() }.getOrDefault(0L)
            val archived = if (src.absolutePath == dst.absolutePath) {
                true
            } else {
                runCatching { src.renameTo(dst) }.getOrDefault(false)
            }
            if (archived) {
                Diagnostics.w(TAG, "快捷补充：草稿未铺入，已留档 $bytes 字节（下次编辑框为空时铺回）")
            } else {
                runCatching { src.delete() }
            }
        }
        pendingDraftFile = null
        pendingDraftLen = 0
        draftPending = false
    }

    override fun onResume() {
        super.onResume()
        editor.requestFocus()
    }

    /**
     * 保存中拦返回键（BUG.md L-860）：返回键的 `finish()` 与点 ✕ 是同一个后果 ——
     * 写盘继续跑，而结果回执被 `isFinishing` 早退吃掉（包已更新却不重启引擎、词库页不刷新）。
     * 另有未保存改动时先确认（BUG.md L-897）：本页专门用来粘贴大词表，误触返回就白干。
     */
    @Deprecated("onBackPressed 已废弃，但此页是 Activity（非 ComponentActivity），无 OnBackPressedDispatcher")
    override fun onBackPressed() {
        confirmExit()
    }

    /** 返回 / ✕ 的共用出口：保存中拦住，有改动先确认 */
    private fun confirmExit() {
        if (saving) {
            setStatus(TEXT_SAVING_WAIT)
            return
        }
        if (!dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(TEXT_DISCARD_TITLE)
            .setMessage(TEXT_DISCARD_BODY)
            .setPositiveButton(TEXT_DISCARD_OK) { _, _ -> finish() }
            .setNegativeButton(TEXT_KEEP_EDITING, null)
            .show()
    }

    /** 源文本在别处被改过时的二次确认：继续保存会用编辑框里的内容覆盖它（BUG.md L-896） */
    private fun confirmOverwrite() {
        AlertDialog.Builder(this)
            .setTitle(TEXT_OVERWRITE_TITLE)
            .setMessage(TEXT_OVERWRITE_BODY)
            .setPositiveButton(TEXT_OVERWRITE_OK) { _, _ -> save(force = true) }
            .setNegativeButton(TEXT_CONFIRM_CANCEL, null)
            .show()
    }

    // ── 载入 / 保存 ──────────────────────────────────────────

    /**
     * 回显源文本（与「导入 .txt」共享同一份）。
     *
     * 读放后台：上限 8MB 的文本一次性读进内存并铺到 EditText，主线程做会掉帧。
     * 读不出来时**提示**而不是静默留空 —— 否则用户会以为词库内容丢了。
     */
    private fun loadSource() {
        val app = applicationContext
        Thread {
            // 整份源文本读盘是重活（上限 16M 字符），降后台优先级（BUG.md L-1194）
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val file = CustomDicts.sourceFile(app)
            // 读前读后各取一次身份（BUG.md L-899）：只在读完后取一次的话，「读内容」与「取身份」
            // 之间被外部改名落盘时，记下的是新身份、铺的是旧内容 ⇒ 保存时比对相等、静默覆盖。
            val stampBefore = CustomDicts.sourceStamp(file)
            val text = CustomDicts.readSource(file)
            val stampAfter = CustomDicts.sourceStamp(file)
            val stampStable = stampBefore == stampAfter
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 读失败不认这份身份（编辑器也留空，保存走「还没有内容」分支）
                if (text != null) {
                    loadedSourceStamp = stampAfter
                    sourceStampTrusted = stampStable
                }
                when {
                    text == null -> setStatus(TEXT_READ_FAIL)
                    text.isEmpty() -> Unit
                    // 用户已开始输入（打开即弹键盘，大文本回填可达数百毫秒）：跳过回填，别把刚敲的内容盖掉（BUG.md L-849）；
                    // 草稿在途时同样跳过（BUG.md L-1181）—— 大稿的草稿推迟到首帧之后铺，
                    // 这段窗口里编辑器还是空的，若按「源文本太大」处理会停用保存并把状态行写成「已跳过回填」，
                    // 而编辑器里马上就要铺上用户自己的草稿
                    draftPending || editor.text.isNotEmpty() -> {
                        Diagnostics.i(TAG, "快捷补充：回填跳过（${if (draftPending) "草稿在途" else "编辑框已有 ${editor.text.length} 字符"}）")
                        // BUG-23：草稿刚铺进来时编辑器非空是「草稿在编辑器里」，不是「用户已经输入」——
                        // 照旧写「编辑框里已有你输入的内容」会把刚恢复的草稿说成用户的输入
                        if (!draftPending && !draftJustRestored) setStatus(TEXT_SKIP_REFILL)
                    }
                    // 体验阈值（BUG.md L-870）：`EditText.setText` 在主线程同步排版，真机实测 8M 字符
                    // 会卡住主线程 18 秒（Skipped 1083 frames / Davey 18.067s）——超过阈值就不铺，
                    // 改为指路「导入 .txt」。此时编辑器仍为空，点保存走「还没有内容」分支，不会误清词库。
                    !shouldRefill(text.length) -> {
                        Diagnostics.i(TAG, "快捷补充：源文本 ${text.length} 字符超过回填阈值，跳过铺入")
                        setStatus(String.format(Locale.US, TEXT_TOO_LARGE_TO_REFILL, text.length / 10000))
                        // 连保存一并停用（BUG.md L-871）：编辑器是空的，若允许保存，
                        // 用户补几条就会把整份大词表整体替换掉，且提示只显示「已保存 N 条」
                        refillSkipped = true
                        saveButton.isEnabled = false
                        saveButton.alpha = 0.45f
                    }
                    else -> {
                        refilling = true
                        editor.setText(text)
                        refilling = false
                        dirty = false
                        draftJustRestored = false
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 保存 = 导入：校验归一 → 原子写包 → 原子写源文本 → 回传条数交给词库页重启生效。
     *
     * 无合法词条时**不落盘**（与导入同一口径）：清空词库请用卡片上的「删除」，
     * 免得一次误编辑把已有词库清掉。
     *
     * [force] 只对**本次**调用有效：覆盖确认里点「继续保存」时由 [confirmOverwrite] 传进来。
     * 写在字段上会跨多次保存累积（BUG.md L-900）—— 一次失败之后，后续几次都不再比对，
     * 期间别人恢复进来的内容会被静默覆盖。
     */
    private fun save(force: Boolean = false) {
        if (saving) return
        // 备份恢复在途时拒绝写（BUG.md L-848）：恢复线程写的是同一个 custom_user.txt.xz，
        // 两边都走「临时名 → rename」，后 rename 者胜 —— 保存的成果会被旧包静默盖掉。
        if (ConfigBackupManager.importing) {
            setStatus(TEXT_CONFIG_IMPORTING)
            return
        }
        // 便宜且确定的判据先判（BUG.md L-901）：尺寸与空文本都不必问用户，排在覆盖确认**之前** ——
        // 否则编辑器为空时点保存会先弹「会用编辑框里的内容覆盖它」，点继续才说「还没有内容」。
        // 两道入口判据都读编辑器自身（`Editable` 就是 CharSequence，读长度与判空都不必先复制快照）
        if (editor.text.length > CustomDicts.MAX_INPUT_CHARS * 2) {
            setStatus(TEXT_TOO_BIG)
            return
        }
        if (editor.text.isBlank()) {
            setStatus(TEXT_EMPTY_INPUT)
            return
        }
        // 恢复写完了但进程还没重启时（对话框有「稍后」），这里的编辑内容已经过期；导入 .txt 与
        // 手工替换同理 —— 闷头保存会把别人的改动整份盖掉（BUG.md L-896），先问一句。
        // 身份不可信（载入时两次取值不一致，见 [sourceStampTrusted]）也走这里：
        // 宁可多问一次，不可静默覆盖（BUG.md L-899）。
        if (!force) {
            val now = CustomDicts.sourceStamp(CustomDicts.sourceFile(this))
            if (!sourceStampTrusted || now != loadedSourceStamp) {
                confirmOverwrite()
                return
            }
        }
        // 这里只做**内存保护**：原文超过两倍上限就不进后台。真正的数据闸在 saveHuman 里按
        // **格式化后**的文本判（BUG.md L-852）—— 按原文拦会把「原文超限而格式化后不超限」的内容误拒。
        val text = editor.text.toString()
        saving = true
        setStatus(TEXT_SAVING)
        // 「保存」与「✕」一起置灰：保存中关闭会让写盘结果回执丢失（BUG.md L-860）
        saveButton.isEnabled = false
        saveButton.alpha = 0.45f
        closeButton.isEnabled = false
        closeButton.alpha = 0.45f
        // 编辑器一并冻结（BUG.md L-866）：保存用的是上面 `text` 这个快照，等待期间补敲的字不会进词库，
        // 而保存完成后页面会直接 finish —— 那些字会静默消失（重开编辑页也看不到）
        editor.isEnabled = false
        val app = applicationContext
        Thread {
            // 与词库页导入同款：解析 + xz 压缩是重活，降后台优先级，别和前台输入争 CPU
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var ok = false
            var entries = 0
            var skipped = 0
            var filtered = 0
            var dropped = 0
            val status = try {
                val syllables = CustomDicts.loadSyllables(app)
                // 解析 → 判闸 → 落盘（源文本归一后**先**落、包**后**落）全在 saveHuman 里，见其
                // 两条不变量（BUG.md L-851 / L-856）。本页此前那份「先 writePack 再判闸、源文本写
                // 失败只记日志」已删除：截断时会写坏包、且包新文本旧
                val report = CustomDicts.saveHuman(dictDir(), text, syllables)
                entries = report.entries
                skipped = report.skipped
                filtered = report.filtered
                dropped = report.dropped
                when (report.gate) {
                    CustomDicts.SaveGate.TOO_BIG -> TEXT_TOO_BIG
                    CustomDicts.SaveGate.TOO_MANY -> TEXT_TOO_MANY
                    CustomDicts.SaveGate.NO_VALID -> String.format(Locale.US, TEXT_NO_VALID, report.skipped)
                    // 两档写盘失败文案不同（BUG.md L-861）：前者磁盘没变，后者文本已落、只差词库
                    CustomDicts.SaveGate.WRITE_FAIL_SOURCE -> TEXT_WRITE_FAIL
                    CustomDicts.SaveGate.WRITE_FAIL_PACK -> TEXT_WRITE_FAIL_PACK
                    CustomDicts.SaveGate.BUSY -> TEXT_BUSY
                    CustomDicts.SaveGate.OK -> {
                        ok = true
                        ""
                    }
                }
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "快捷补充保存失败: ${t.javaClass.simpleName}")
                TEXT_WRITE_FAIL
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saving = false
                // 跳过回填时保存保持停用（BUG.md L-871）：无条件恢复会把「补几条 = 整体替换」放回来
                if (!refillSkipped) {
                    saveButton.isEnabled = true
                    saveButton.alpha = 1f
                }
                closeButton.isEnabled = true
                closeButton.alpha = 1f
                editor.isEnabled = true
                if (ok) {
                    Diagnostics.i(
                        TAG,
                        "快捷补充保存: $entries 条 / 跳过 $skipped 行 / 打不出 $filtered 条 / 同键挤掉 $dropped 条",
                    )
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_ENTRIES, entries)
                            .putExtra(EXTRA_SKIPPED, skipped)
                            .putExtra(EXTRA_FILTERED, filtered)
                            .putExtra(EXTRA_DROPPED, dropped),
                    )
                    finish()
                } else {
                    setStatus(status)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 词库目录（包与源文本都在这里；与词库页、备份侧同一处 `filesDir/dicts`） */
    private fun dictDir(): File = File(filesDir, PinyinEngine.OPT_DICT_DIR)

    // ── 版面 ────────────────────────────────────────────────

    /**
     * 顶栏：左标题、右「保存」+ 关闭（与词库页同款 #141C33 深蓝顶栏）。
     *
     * 「保存」放顶栏而不是底部：本页打开即弹键盘，而 Android 10 真机上键盘是**覆盖**窗口
     * （`adjustResize` 未缩短布局），底部按钮会被键盘压住点不到 —— 2026-10-05 真机实证。
     */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.card_bg))
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        bar.addView(TextView(this).apply {
            text = TEXT_TITLE
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveButton = TextView(this).apply {
            text = TEXT_SAVE
            contentDescription = TEXT_SAVE
            setTextColor(getColor(R.color.accent))
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { save() }
        }
        bar.addView(saveButton, wrapWrap())
        closeButton = TextView(this).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setTextColor(getColor(R.color.text_secondary))
            textSize = 20f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                // 保存中先拦住（BUG.md L-860）：写盘已完成但回执被 finish 吃掉 = 静默半生效；
                // 有未保存改动时同样先确认（BUG.md L-897）
                if (saving || dirty) {
                    Diagnostics.i(TAG, "CustomDictEditActivity: 关闭前需确认（saving=$saving dirty=$dirty）")
                    confirmExit()
                    return@setOnClickListener
                }
                Diagnostics.i(TAG, "CustomDictEditActivity: 用户关闭页面")
                finish()
            }
        }
        bar.addView(closeButton, wrapWrap())
        return bar
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

    companion object {

        /** 词库页从结果里取「已保存条数 / 跳过行数 / 打不出的条数」，复用同一句结果文案 */
        const val EXTRA_ENTRIES = "custom_entries"
        const val EXTRA_SKIPPED = "custom_skipped"
        const val EXTRA_FILTERED = "custom_filtered"

        /** 跨重建保存的草稿与脏标记（BUG.md L-1163）：小稿进状态、大稿进文件（L-1169） */
        private const val STATE_EDITOR_TEXT = "custom_editor_text"
        private const val STATE_EDITOR_FILE = "custom_editor_file"
        private const val STATE_DIRTY = "custom_editor_dirty"

        /** 大稿临时件（`cacheDir` 下，读完即删）与写盘用的临时名（rename 前；同步补写另用一个，见 L-1185） */
        private const val DRAFT_FILE = "custom_editor.draft.txt"
        private const val DRAFT_FILE_TMP = "custom_editor.draft.txt.tmp"
        private const val DRAFT_FILE_SAVE_TMP = "custom_editor.draft.txt.save"

        /**
         * 未铺入的草稿**留档槽**（只留最新一份，天然不堆积；BUG-30）。
         *
         * 用户在草稿读盘在途时抢先敲了字 ⇒ 按既有口径「用户的输入优先、草稿不铺」，但旧长文
         * 不该就此消失：改名为这一槽留档；下次打开本页若没有更新的草稿，就把它铺回来。
         * 用户随后写的新草稿一旦落盘即清掉本槽（那时它已被更新的内容取代）。
         */
        private const val DRAFT_FILE_ARCHIVE = "custom_editor.draft.archive.txt"

        /** 铺的是留档稿时的状态行文案 */
        private const val TEXT_DRAFT_ARCHIVED = "已把上次未铺入的草稿留档并铺回（编辑框原为空）"

        /** 大稿增量落盘的去抖窗口（毫秒）：输入停顿后写一次 */
        private const val DRAFT_WRITE_DEBOUNCE_MS = 1_500L

        /** 分块落盘的块大小（字符）：每块一个 IO 任务，让串行队列在块间处理别的活儿（BUG.md L-1180） */
        private const val DRAFT_WRITE_CHUNK_CHARS = 64 * 1024

        /** 落盘时随令牌记下的字符数：恢复端据此判断文件是不是写到一半（BUG.md L-1185） */
        private const val STATE_DRAFT_LEN = "custom_editor_len"

        /** 兜底截断时记下的恢复长度（0 = 没截断，BUG.md L-1174） */
        private const val STATE_DRAFT_TRUNCATED = "custom_editor_truncated"

        /** 草稿进实例状态的字符上限：Bundle 在系统侧约 1MB 量级，这里留足余量 */
        internal const val DRAFT_BUNDLE_MAX_CHARS = 100_000

        /** 草稿走实例状态还是走临时文件（纯函数，便于单测） */
        internal fun draftInBundle(textLength: Int): Boolean = textLength <= DRAFT_BUNDLE_MAX_CHARS

        /** 同键词表超 100 被丢下的条数（BUG.md L-864） */
        const val EXTRA_DROPPED = "custom_dropped"

        // 文案在代码里下发（与词库页的 TEXT_CUSTOM_* 同做法，strings.xml 只承载卡片按钮）
        const val TEXT_TITLE = "快捷补充"
        const val TEXT_HINT_LINE1 = "# 开头是注释\n每行一条：你好 ni hao\n拼音用'空格'分隔每个字的音节"
        const val TEXT_HINT_LINE2 = "这里的内容与「导入 .txt」共用同一份文本。"
        const val TEXT_HINT_LINE3 = "词组不受字表限制：生僻、繁体 不启用的都能输出"
        const val TEXT_EDITOR_HINT = "# 示范例子：\n许嵩 xu song\n冯禧 feng xi\n黄龄 huang ling\n黄霄雲 huang xiao yun"
        const val TEXT_SAVE = "保存"
        const val TEXT_SAVING = "正在保存并生成词库…"
        const val TEXT_READ_FAIL = "无法读取已保存的内容（文件过大或损坏）"
        const val TEXT_EMPTY_INPUT = "还没有内容：每行写「词 空格 拼音」后再保存"

        /** 大稿恢复期间的状态行（排版代价随长度走，先让页面画出来，BUG.md L-1173） */
        const val TEXT_DRAFT_RESTORING = "正在恢复未保存的草稿…"

        /** 兜底截断后的状态行（%d = 已恢复的字符数，BUG.md L-1174） */
        const val TEXT_DRAFT_TRUNCATED = "草稿过大且未能落盘，只恢复了前 %d 字符"

        /** 大稿草稿恢复完成（替换掉恢复期间的「正在恢复…」，否则那句话会一直挂在状态行上） */
        const val TEXT_DRAFT_RESTORED = "已恢复未保存的草稿"

        /** 草稿超过恢复上限（%d = 万字；文件保留，不当作读取失败，BUG-31） */
        const val TEXT_DRAFT_TOO_LARGE = "草稿太大（超过 %,d 万字），本次未铺入；原稿保留在缓存里"

        /** 草稿文件读取失败（保留文件，页面重建时自动再试，BUG.md L-1185） */
        const val TEXT_DRAFT_UNREADABLE = "草稿读取失败：原稿仍保留在缓存里（未删除），页面重建时会自动再试"

        /** 草稿没铺进去：用户已经在编辑框里敲了字，那些输入优先（BUG.md L-1191） */
        const val TEXT_DRAFT_SKIPPED = "草稿未铺入：编辑框里已有你输入的内容"

        /** 草稿文件已不在缓存里（系统清理 / 已收尾删除）：别再承诺「保留待重试」（BUG.md L-1197） */
        const val TEXT_DRAFT_GONE = "未找到上次未保存的草稿（缓存已被系统清理），本次没有可恢复的内容"
        const val TEXT_TOO_BIG = "内容超过 800 万字符上限，请精简后重试"
        const val TEXT_CONFIG_IMPORTING = "配置恢复进行中，暂不可修改词库"
        const val TEXT_SKIP_REFILL = "已跳过回填：编辑框里已有你输入的内容"
        const val TEXT_TOO_MANY = "词条超过 5 万条上限，请精简后重试"
        const val TEXT_NO_VALID = "没有可保存的合法词条（跳过 %d 行）"
        const val TEXT_WRITE_FAIL = "保存失败，请重试"
        const val TEXT_WRITE_FAIL_PACK = "内容已保留，词库生成失败：请再点一次保存"
        const val TEXT_BUSY = "正在写入词库，请稍候再试"
        const val TEXT_SAVING_WAIT = "正在保存，请稍候…"
        const val TEXT_TOO_LARGE_TO_REFILL = "内容约 %d 万字符，已跳过回填：请用「导入 .txt」整体替换（此页保存已停用）"

        // 覆盖确认（BUG.md L-896）：打开之后词库被别处改过
        const val TEXT_OVERWRITE_TITLE = "词库已在别处改过"
        const val TEXT_OVERWRITE_BODY =
            "打开这一页之后，词库被「配置恢复 / 导入 .txt」改过。继续保存会用编辑框里的内容覆盖它。"
        const val TEXT_OVERWRITE_OK = "继续保存"
        const val TEXT_CONFIRM_CANCEL = "取消"

        // 放弃确认（BUG.md L-897）：返回 / ✕ 时还有没保存的改动
        const val TEXT_DISCARD_TITLE = "放弃未保存的改动？"
        const val TEXT_DISCARD_BODY = "编辑框里的改动还没有保存，离开后会丢失。"
        const val TEXT_DISCARD_OK = "放弃"
        const val TEXT_KEEP_EDITING = "继续编辑"

        /** 回填体验阈值（字符）：见 [shouldRefill] 与 BUG.md L-870 */
        const val MAX_REFILL_CHARS = 1_000_000

        /**
         * 是否把源文本铺进编辑器（纯函数，便于单测）。
         *
         * 数据侧上限是 [CustomDicts.MAX_INPUT_CHARS]（8M 字符），但 UI 侧承受不了：
         * `EditText.setText` 在主线程同步构建 Spannable 并排版，真机实测 880k 行 / 8.1MB
         * 会让主线程卡 18 秒（BUG.md L-870），所以回填单独设一条更低的体验阈值。
         */
        internal fun shouldRefill(textLength: Int): Boolean = textLength <= MAX_REFILL_CHARS

        private const val TAG = "CustomDictEdit"
    }
}
