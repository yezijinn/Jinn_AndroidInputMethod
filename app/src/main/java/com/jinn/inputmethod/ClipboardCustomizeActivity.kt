package com.jinn.inputmethod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 剪贴板自定义页：从设置页「敲击音效反馈」同行右侧的「剪贴板自定义」进入。
 *
 * 交互模型（2026-10-06 用户拍板）：**默认锁定只读 → 解锁才可调 → 改动进草稿 → 点「保存」才落盘**。
 *  - 「解锁参数」开关：**会话级、不落盘**（每次进入都从锁定态开始，防误触最彻底）；
 *  - 总开关与 7 个容量参数**都走草稿**（[draft] 是渲染的唯一真源，`prefs` 只在建快照 / 保存时读）；
 *  - 「重置」= 撤销本次编辑（回到进入页面时的 [snapshot]，不写盘、不触发裁剪）；
 *  - 保存 / 重置后**自动回锁**；
 *  - 保存走 [ClipboardPrefs.applyDraft]（单次 `commit`，避免半套参数）；
 *  - 有未保存改动时离开要二次确认（草稿是页面私有的，退出即丢）。
 *
 * 草稿必须能在 Activity 重建后存活（旋转；`ThemeManager` 定时换肤到点会 `recreate()`），
 * 所以随 [onSaveInstanceState] 存取。
 */
class ClipboardCustomizeActivity : Activity() {

    /** 页面参数草稿（渲染唯一真源）：2026-10-10 起从八项扩到十二项（图片四项） */
    private data class Draft(
        val enabled: Boolean,
        val maxItems: Int,
        val maxTotalMb: Int,
        val favMaxItems: Int,
        val favMaxMb: Int,
        val maxItemKb: Int,
        val panelPage: Int,
        val maxSearch: Int,
        val imageEnabled: Boolean,
        val imageMaxItems: Int,
        val imageMaxTotalMb: Int,
        val imageMaxItemMb: Int,
    )

    private lateinit var prefs: ClipboardPrefs
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button

    /** 进入页面时的盘上快照：「重置」的回滚目标，也是「有无改动」的比较基准 */
    private lateinit var snapshot: Draft

    /** 当前编辑值 */
    private lateinit var draft: Draft

    /** 解锁开关：会话级（不落盘），重建后由 [onSaveInstanceState] 恢复 */
    private var unlocked = false

    /** 二次确认防重入：连点按钮会让 AlertDialog 堆叠 */
    private var confirming = false

    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard_customize)
        prefs = ClipboardPrefs.of(this)
        snapshot = readPrefs()
        draft = restoreDraft(savedInstanceState)
        unlocked = savedInstanceState?.getBoolean(STATE_UNLOCKED) ?: false

        findViewById<TextView>(R.id.text_clip_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_clip_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_clip_close).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { tryFinish() }
        }
        list = findViewById(R.id.clip_customize_list)
        scroll = findViewById(R.id.clip_scroll)
        saveButton = findViewById<Button>(R.id.btn_clip_save).apply {
            text = TEXT_SAVE
            setOnClickListener { confirmSave() }
        }
        resetButton = findViewById<Button>(R.id.btn_clip_reset).apply {
            text = TEXT_RESET
            setOnClickListener { confirmReset() }
        }
        findViewById<Button>(R.id.btn_clip_history).apply {
            text = TEXT_HISTORY_ENTRY
            setOnClickListener { startActivity(Intent(this@ClipboardCustomizeActivity, ClipboardHistoryActivity::class.java)) }
        }
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
        renderAll()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ENABLED, draft.enabled)
        outState.putInt(STATE_MAX_ITEMS, draft.maxItems)
        outState.putInt(STATE_MAX_TOTAL_MB, draft.maxTotalMb)
        outState.putInt(STATE_FAV_MAX_ITEMS, draft.favMaxItems)
        outState.putInt(STATE_FAV_MAX_MB, draft.favMaxMb)
        outState.putInt(STATE_MAX_ITEM_KB, draft.maxItemKb)
        outState.putInt(STATE_PANEL_PAGE, draft.panelPage)
        outState.putInt(STATE_MAX_SEARCH, draft.maxSearch)
        outState.putBoolean(STATE_IMAGE_ENABLED, draft.imageEnabled)
        outState.putInt(STATE_IMAGE_MAX_ITEMS, draft.imageMaxItems)
        outState.putInt(STATE_IMAGE_MAX_TOTAL_MB, draft.imageMaxTotalMb)
        outState.putInt(STATE_IMAGE_MAX_ITEM_MB, draft.imageMaxItemMb)
        outState.putBoolean(STATE_UNLOCKED, unlocked)
    }

    /** 离开页面：有未保存改动时拦一道（草稿退出即丢，不拦等于静默吞掉用户的调整） */
    @Deprecated("onBackPressed 已废弃，但此页是 Activity（非 ComponentActivity），无 OnBackPressedDispatcher")
    override fun onBackPressed() {
        tryFinish()
    }

    // ── 数据 ────────────────────────────────────────────────

    /**
     * 盘上取值 → 页面草稿。
     *
     * 走 [ClipboardPrefs.snapshot] 一次性取（BUG-12）：逐键读八次时，若导入 / 批量保存正在跑，
     * 页面会拿到「半新半旧」的组合（例如总量上限已是新值、收藏上限还是旧值），用户随手一保存
     * 就把旧值写回，抵消掉刚导入的配置。
     */
    private fun readPrefs(): Draft {
        val s = prefs.snapshot()
        return Draft(
            enabled = s.enabled,
            maxItems = s.maxItems,
            maxTotalMb = s.maxTotalMb,
            favMaxItems = s.favMaxItems,
            favMaxMb = s.favMaxMb,
            maxItemKb = s.maxItemKb,
            panelPage = s.panelPage,
            maxSearch = s.maxSearch,
            imageEnabled = s.imageEnabled,
            imageMaxItems = s.imageMaxItems,
            imageMaxTotalMb = s.imageMaxTotalMb,
            imageMaxItemMb = s.imageMaxItemMb,
        )
    }

    private fun restoreDraft(b: Bundle?): Draft {
        if (b == null || !b.containsKey(STATE_MAX_ITEMS)) return snapshot
        return Draft(
            enabled = b.getBoolean(STATE_ENABLED, snapshot.enabled),
            maxItems = b.getInt(STATE_MAX_ITEMS, snapshot.maxItems),
            maxTotalMb = b.getInt(STATE_MAX_TOTAL_MB, snapshot.maxTotalMb),
            favMaxItems = b.getInt(STATE_FAV_MAX_ITEMS, snapshot.favMaxItems),
            favMaxMb = b.getInt(STATE_FAV_MAX_MB, snapshot.favMaxMb),
            maxItemKb = b.getInt(STATE_MAX_ITEM_KB, snapshot.maxItemKb),
            panelPage = b.getInt(STATE_PANEL_PAGE, snapshot.panelPage),
            maxSearch = b.getInt(STATE_MAX_SEARCH, snapshot.maxSearch),
            imageEnabled = b.getBoolean(STATE_IMAGE_ENABLED, snapshot.imageEnabled),
            imageMaxItems = b.getInt(STATE_IMAGE_MAX_ITEMS, snapshot.imageMaxItems),
            imageMaxTotalMb = b.getInt(STATE_IMAGE_MAX_TOTAL_MB, snapshot.imageMaxTotalMb),
            imageMaxItemMb = b.getInt(STATE_IMAGE_MAX_ITEM_MB, snapshot.imageMaxItemMb),
        )
    }

    private fun isDirty(): Boolean = draft != snapshot

    // ── 渲染 ────────────────────────────────────────────────

    private fun renderAll() {
        // 重建整页会重置滚动：先记位置，重建后恢复（否则拖卡片 3 的滑块一松手就被弹回顶部）
        val keepY = if (this::scroll.isInitialized) scroll.scrollY else 0
        list.removeAllViews()
        // 可调只取决于解锁态：总开关自身也是草稿的一部分，若把它算进来，「关掉历史」这条改动会连
        // 保存 / 重置一起禁用 —— 想关却按不了保存。**唯一**置灰合成处，避免多层 alpha 叠加。
        val editable = unlocked

        val card1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card1.addView(makeSwitch(TEXT_ENABLED, draft.enabled) { v ->
            // 总开关也走草稿（用户拍板）：勾选只改草稿，点「保存」才写盘并由广播让 IME 启停
            draft = draft.copy(enabled = v)
            renderAll()
        })
        card1.addView(makeSwitch(TEXT_UNLOCK, unlocked) { v ->
            unlocked = v
            renderAll()
        })
        card1.addView(makeHint(hintText()))
        val sub = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sub.addView(makeSlider(
            TEXT_MAX_ITEMS,
            draft.maxItems, 1, ClipboardPrefs.MAX_ITEMS_CAP, 50,
            { draft = draft.copy(maxItems = it) },
            { "$it 条" },
        ))
        sub.addView(makeSlider(
            TEXT_MAX_TOTAL,
            draft.maxTotalMb, ClipboardPrefs.MIN_TOTAL_MB, ClipboardPrefs.MAX_TOTAL_MB, 10,
            {
                // 收藏体积草稿**不跟着钳位**（BUG.md L-982 / L-988）：原值留着，只有生效值随
                // 「历史体积 1/4」这条动态限制变化（下面那行「实际生效」就是给它看的）。
                // 原先在这里 minOf(...) 会把用户的设定直接压掉，把历史体积调回去也回不来。
                draft = draft.copy(maxTotalMb = it)
            },
            { "$it MB" },
        ))
        // 记录图片：与总开关独立（总开关关 = 文本图片全不采集；这里关 = 只不采集图片）
        sub.addView(makeSwitch(TEXT_IMAGE_ENABLED, draft.imageEnabled) { v ->
            draft = draft.copy(imageEnabled = v)
            renderAll()
        })
        dim(sub, editable)
        card1.addView(sub)
        PageStyle.addCard(list, card1)

        val card2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card2.addView(TextView(this).apply {
            text = TEXT_FAV_TITLE
            PageStyle.sectionTitle(this)
        })
        card2.addView(makeSlider(
            TEXT_FAV_ITEMS,
            draft.favMaxItems, 1, ClipboardPrefs.MAX_FAV_ITEMS_CAP, 10,
            { draft = draft.copy(favMaxItems = it) },
            { "$it 条" },
        ))
        card2.addView(makeSlider(
            TEXT_FAV_BYTES,
            // 上限用**静态**上界而不是当前的「历史体积 1/4」：滑块是设定原值的地方，
            // 够不到原值就表示不出来（BUG.md L-982）；动态限制由下面那行「实际生效」说明
            draft.favMaxMb, 1, ClipboardPrefs.FAV_MAX_MB_HARD_CAP, 1,
            { draft = draft.copy(favMaxMb = it) },
            { "$it MB" },
        ))
        // 生效值按**草稿**算（含页宽联动）：拖动历史体积时能立刻看到收藏上限被压到多少
        val effectiveFav = ClipboardPrefs.effectiveFavoriteMaxMb(draft.favMaxMb, draft.maxTotalMb)
        val clampNote = if (effectiveFav < draft.favMaxMb) TEXT_FAV_CLAMPED_SUFFIX else ""
        card2.addView(makeHint(TEXT_FAV_EFFECTIVE_PREFIX + effectiveFav + TEXT_FAV_EFFECTIVE_SUFFIX + clampNote))
        card2.addView(makeHint(TEXT_FAV_HINT))
        dim(card2, editable)
        PageStyle.addCard(list, card2)

        val card3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card3.addView(TextView(this).apply {
            text = TEXT_LIMIT_TITLE
            PageStyle.sectionTitle(this)
        })
        card3.addView(makeSlider(
            TEXT_MAX_ITEM,
            draft.maxItemKb, 4, ClipboardPrefs.MAX_ITEM_KB_CAP, 32,
            { draft = draft.copy(maxItemKb = it) },
            { "$it KB" },
        ))
        // 生效值按**草稿**算（含页宽联动）：用盘上值会让草稿改动看不到反馈
        val effectiveKb = ClipboardStore.effectiveItemLimitBytes(draft.maxItemKb * 1024L) / 1024
        card3.addView(makeHint("$TEXT_EFFECTIVE_PREFIX$effectiveKb$TEXT_EFFECTIVE_SUFFIX"))
        card3.addView(makeSlider(
            TEXT_PANEL_PAGE,
            draft.panelPage, 2, ClipboardStore.PANEL_PAGE_ITEMS_MAX, 2,
            { draft = draft.copy(panelPage = it) },
            { "$it 条/页" },
        ))
        card3.addView(makeSlider(
            TEXT_MAX_SEARCH,
            draft.maxSearch, 2, ClipboardPrefs.MAX_SEARCH_CAP, 10,
            { draft = draft.copy(maxSearch = it) },
            { "$it 条" },
        ))
        dim(card3, editable)
        PageStyle.addCard(list, card3)

        // ── 图片容量（2026-10-10 实施计划 §4.2：与文本预算完全分离，各自淘汰） ──
        val card4 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card4.addView(TextView(this).apply {
            text = TEXT_IMAGE_TITLE
            PageStyle.sectionTitle(this)
        })
        card4.addView(makeSlider(
            TEXT_IMAGE_MAX_ITEMS,
            draft.imageMaxItems, 1, ClipboardPrefs.MAX_IMAGE_ITEMS_CAP, 50,
            { draft = draft.copy(imageMaxItems = it) },
            { "$it 张" },
        ))
        card4.addView(makeSlider(
            TEXT_IMAGE_MAX_TOTAL,
            draft.imageMaxTotalMb, ClipboardPrefs.MIN_IMAGE_TOTAL_MB, ClipboardPrefs.MAX_IMAGE_TOTAL_MB, 10,
            { draft = draft.copy(imageMaxTotalMb = it) },
            { "$it MB" },
        ))
        card4.addView(makeSlider(
            TEXT_IMAGE_MAX_ITEM,
            draft.imageMaxItemMb, ClipboardPrefs.MIN_IMAGE_ITEM_MB, ClipboardPrefs.MAX_IMAGE_ITEM_MB, 1,
            { draft = draft.copy(imageMaxItemMb = it) },
            { "$it MB" },
        ))
        card4.addView(makeHint(TEXT_IMAGE_HINT))
        dim(card4, editable)
        PageStyle.addCard(list, card4)

        // ── 缩略图布局（与图库快贴共用同一组参数：本页、图库面板、剪贴板图片网格三处同步） ──
        // 即时生效、不走草稿：这对参数住在 Prefs（图库偏好）里，与草稿机制管辖的剪贴板容量
        // 不是同一套键空间；图库设置页同样是即时生效，两处手感保持一致
        val card5 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card5.addView(TextView(this).apply {
            text = TEXT_LAYOUT_TITLE
            PageStyle.sectionTitle(this)
        })
        val layoutPrefs = Prefs(this)
        card5.addView(makeSlider(
            TEXT_LAYOUT_COLUMNS,
            layoutPrefs.galleryColumns, Prefs.GALLERY_COLUMNS_MIN, Prefs.GALLERY_COLUMNS_MAX, 1,
            { layoutPrefs.galleryColumns = it },
            { "$it 张/行" },
        ))
        card5.addView(makeSlider(
            TEXT_LAYOUT_HEIGHT,
            layoutPrefs.galleryCellHeightDp, Prefs.GALLERY_CELL_HEIGHT_MIN, Prefs.GALLERY_CELL_HEIGHT_MAX, 4,
            { layoutPrefs.galleryCellHeightDp = it },
            { "$it dp" },
        ))
        card5.addView(makeHint(TEXT_LAYOUT_HINT))
        // 这对参数是图库与剪贴板两个网格共享的布局偏好，与「剪贴板历史」总开关无关：
        // 用 editable（含总开关关闭）会把只想调图库布局的人挡在门外（BUG.md L-1247）
        dim(card5, unlocked)
        PageStyle.addCard(list, card5)

        // 锁定 / 无改动时按钮不可点：一个只读页面里「保存」能按本身就是误导
        val dirty = isDirty()
        saveButton.isEnabled = editable && dirty
        resetButton.isEnabled = editable && dirty
        scroll.post { scroll.scrollTo(0, keepY) }
    }

    /**
     * 参数区置灰（半透明 + 不可交互）。
     *
     * ⚠ 置灰范围必须**排除两个开关自身**：把「解锁参数」一起禁掉就是自锁死（打开后再也关不掉）。
     */
    private fun dim(view: View, editable: Boolean) {
        view.alpha = if (editable) 1f else 0.45f
        setEnabledDeep(view, editable)
    }

    /** 参数区顶部说明：把「为什么拖不动 / 什么时候生效」写在最显眼处（未解锁优先，其次看总开关） */
    private fun hintText(): String = when {
        !unlocked -> TEXT_HINT_LOCKED
        !draft.enabled -> TEXT_HINT_HISTORY_OFF
        else -> TEXT_HINT_EDITING
    }

    private fun makeHint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 11f
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun makeSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            this.text = text
            isChecked = checked
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    /**
     * 拖动条行：标签（左）+ 拖动条（中）+ 数值（右，与外观页同规格）。
     *
     * 数值文本一律用**盘上原值**，不做步进对齐：默认值与步进相位不齐（500 / 200 / 256 都不在
     * 格点上），对齐会把它们显示成 451 / 191 / 228 —— 用户以为值被改小了，保存后还会真写回。
     * 代价是导入的非步进值（如 565）与滑块位置差半格，拖动即从格点起算，取其轻（L-997 复核结论）。
     */
    private fun makeSlider(
        label: String,
        value: Int, min: Int, max: Int, step: Int,
        set: (Int) -> Unit,
        format: (Int) -> String,
    ): LinearLayout {
        val valueText = TextView(this).apply {
            text = format(value)
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            gravity = android.view.Gravity.END
            minWidth = dp(52)
        }
        val seek = SeekBar(this).apply {
            // 上界必须可达：步进除不尽时（如 4~1024 / 每格 32、2~75 / 每格 2），整除会把最大进度
            // 压到上界之下 —— 用户存着 1024 或 75 时滑杆选不出来，拖一下就静默变小。
            // 取上整，最后一格落到上界之内由回调里的 coerceIn 收口。
            this.max = (((max - min) + step - 1) / step).coerceAtLeast(1)
            // 显示侧同样取整：存着上界时进度落在最后一格（否则会显示成一格之下，一拖就掉值）
            progress = Math.round((value - min).toFloat() / step).coerceIn(0, this.max)
            contentDescription = label
            progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.card_stroke))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    // 只改草稿（不写盘）：拖动中不重画，避免联动项抖动与页面跳动
                    val v = (min + progress * step).coerceIn(min, max)
                    set(v)
                    valueText.text = format(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                // 松手：整页重画一次（收藏体积上限 / 生效单条等联动跟随）+ 刷新按钮可用态；滚动位置会恢复
                override fun onStopTrackingTouch(sb: SeekBar?) = renderAll()
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(this@ClipboardCustomizeActivity).apply {
                text = label
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
            addView(valueText)
        }
    }

    // ── 保存 / 重置 / 离开 ───────────────────────────────────

    private fun confirmSave() {
        if (confirming) return
        val msg = buildString {
            append(TEXT_SAVE_SUMMARY)
            val lines = diffSummary()
            if (lines.isEmpty()) {
                append('\n').append(TEXT_NO_CHANGE)
            } else {
                lines.forEach { append('\n').append(it) }
            }
            if (isTightened()) {
                append('\n').append(TEXT_SAVE_WARN_DELETE)
            }
        }
        confirm(TEXT_SAVE, msg) { doSave() }
    }

    private fun doSave() {
        val target = draft
        val wasEnabled = snapshot.enabled
        // 单次 commit：任何一项失败都不该留下「半套参数」
        val ok = prefs.applyDraft(
            enabled = target.enabled,
            maxItems = target.maxItems,
            maxTotalMb = target.maxTotalMb,
            favMaxItems = target.favMaxItems,
            favMaxMb = target.favMaxMb,
            maxItemKb = target.maxItemKb,
            panelPage = target.panelPage,
            maxSearch = target.maxSearch,
            imageEnabled = target.imageEnabled,
            imageMaxItems = target.imageMaxItems,
            imageMaxTotalMb = target.imageMaxTotalMb,
            imageMaxItemMb = target.imageMaxItemMb,
        )
        if (!ok) {
            // 盘上还是旧值：草稿原样留着让用户重试，绝不假报成功
            Diagnostics.w(TAG, "保存失败: commit 未落盘")
            toast(TEXT_SAVE_FAILED)
            return
        }
        if (wasEnabled != target.enabled) {
            // 采集器跑在输入法进程里，不读本页状态：发轻量广播让它立刻启停（见 JinnIme.configReceiver）
            sendBroadcast(Intent(JinnIme.ACTION_CLIPBOARD_CONFIG_UPDATED).setPackage(packageName))
        }
        // 落盘后的裁剪：上限调小时既有库不会自己收敛（与设置页 / 备份导入同款）
        trimNow()
        snapshot = target
        unlocked = false
        renderAll()
        toast(TEXT_SAVED)
        Diagnostics.i(TAG, "参数已保存（自动回锁）")
    }

    private fun confirmReset() {
        if (confirming) return
        confirm(TEXT_RESET, TEXT_RESET_MSG) {
            draft = snapshot
            unlocked = false
            renderAll()
            toast(TEXT_RESET_DONE)
        }
    }

    private fun tryFinish() {
        if (!isDirty()) {
            finish()
            return
        }
        if (confirming) return
        confirm(TEXT_DISCARD_TITLE, TEXT_DISCARD_MSG) { finish() }
    }

    /**
     * 二次确认（防重入：连点按钮不会堆叠对话框）。
     *
     * 三个回调都要复位 [confirming] —— 只复位确定键的话，用户点「取消」或点外部关掉之后
     * 按钮就再也弹不出确认框了。
     */
    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        if (confirming) return
        confirming = true
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(TEXT_CONFIRM_OK) { _, _ ->
                confirming = false
                onYes()
            }
            .setNegativeButton(TEXT_CONFIRM_CANCEL) { _, _ -> confirming = false }
            .setOnCancelListener { confirming = false }
            .show()
    }

    /** 改动摘要：只列真正变了的项（保存确认框里逐行列出） */
    private fun diffSummary(): List<String> {
        val items = ArrayList<String>()
        if (draft.enabled != snapshot.enabled) {
            items.add("$TEXT_ENABLED：${onOff(snapshot.enabled)} → ${onOff(draft.enabled)}")
        }
        if (draft.maxItems != snapshot.maxItems) items.add("$TEXT_MAX_ITEMS：${snapshot.maxItems} → ${draft.maxItems} 条")
        if (draft.maxTotalMb != snapshot.maxTotalMb) items.add("$TEXT_MAX_TOTAL：${snapshot.maxTotalMb} → ${draft.maxTotalMb} MB")
        if (draft.favMaxItems != snapshot.favMaxItems) items.add("$TEXT_FAV_ITEMS：${snapshot.favMaxItems} → ${draft.favMaxItems} 条")
        if (draft.favMaxMb != snapshot.favMaxMb) items.add("$TEXT_FAV_BYTES：${snapshot.favMaxMb} → ${draft.favMaxMb} MB")
        if (draft.maxItemKb != snapshot.maxItemKb) items.add("$TEXT_MAX_ITEM：${snapshot.maxItemKb} → ${draft.maxItemKb} KB")
        if (draft.panelPage != snapshot.panelPage) items.add("$TEXT_PANEL_PAGE：${snapshot.panelPage} → ${draft.panelPage} 条/页")
        if (draft.maxSearch != snapshot.maxSearch) items.add("$TEXT_MAX_SEARCH：${snapshot.maxSearch} → ${draft.maxSearch} 条")
        if (draft.imageEnabled != snapshot.imageEnabled) {
            items.add("$TEXT_IMAGE_ENABLED：${onOff(snapshot.imageEnabled)} → ${onOff(draft.imageEnabled)}")
        }
        if (draft.imageMaxItems != snapshot.imageMaxItems) {
            items.add("$TEXT_IMAGE_MAX_ITEMS：${snapshot.imageMaxItems} → ${draft.imageMaxItems} 张")
        }
        if (draft.imageMaxTotalMb != snapshot.imageMaxTotalMb) {
            items.add("$TEXT_IMAGE_MAX_TOTAL：${snapshot.imageMaxTotalMb} → ${draft.imageMaxTotalMb} MB")
        }
        if (draft.imageMaxItemMb != snapshot.imageMaxItemMb) {
            items.add("$TEXT_IMAGE_MAX_ITEM：${snapshot.imageMaxItemMb} → ${draft.imageMaxItemMb} MB")
        }
        if (items.size <= DIFF_MAX_LINES) return items
        return items.take(DIFF_MAX_LINES) + listOf("…等 ${items.size} 项")
    }

    private fun onOff(v: Boolean): String = if (v) TEXT_ON else TEXT_OFF

    /** 是否收紧了上限：收紧会在保存后真删记录（不可恢复）⇒ 确认框必须写明 */
    private fun isTightened(): Boolean =
        draft.maxItems < snapshot.maxItems ||
            draft.maxTotalMb < snapshot.maxTotalMb ||
            draft.favMaxItems < snapshot.favMaxItems ||
            draft.favMaxMb < snapshot.favMaxMb ||
            // 图片预算收紧同样会真删图片（不可恢复）⇒ 确认框必须一起写明
            draft.imageMaxItems < snapshot.imageMaxItems ||
            draft.imageMaxTotalMb < snapshot.imageMaxTotalMb ||
            draft.imageMaxItemMb < snapshot.imageMaxItemMb

    /**
     * 按**已落盘**的偏好立即裁剪一次库。
     *
     * 上限调小时既有库不会自己收敛（只有下次复制入库才顺带裁剪），与设置页数量上限、
     * 备份导入后的处理同款；[ClipboardDb.trimTo] 内含收藏软上限那一趟，故收藏类参数一并生效。
     */
    private fun trimNow() {
        val p = prefs
        BackgroundIo.run {
            runCatching { ClipboardDb.get(this).trimTo(p.maxItems, p.maxTotalBytes) }
                .onFailure {
                    Diagnostics.w(TAG, "容量裁剪失败: ${it.message}")
                    // 参数已落盘、库未收敛：不提示的话「已保存」就成了假象（L-993）；
                    // 页面可能已被销毁，回调前先确认窗口还在
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) toast(TEXT_TRIM_FAILED)
                    }
                }
        }
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = PageStyle.dp(this, v)

    private companion object {
        const val TAG = "ClipboardCustomize"

        const val STATE_ENABLED = "draft_enabled"
        const val STATE_MAX_ITEMS = "draft_max_items"
        const val STATE_MAX_TOTAL_MB = "draft_max_total_mb"
        const val STATE_FAV_MAX_ITEMS = "draft_fav_max_items"
        const val STATE_FAV_MAX_MB = "draft_fav_max_mb"
        const val STATE_MAX_ITEM_KB = "draft_max_item_kb"
        const val STATE_PANEL_PAGE = "draft_panel_page"
        const val STATE_MAX_SEARCH = "draft_max_search"
        const val STATE_IMAGE_ENABLED = "draft_image_enabled"
        const val STATE_IMAGE_MAX_ITEMS = "draft_image_max_items"
        const val STATE_IMAGE_MAX_TOTAL_MB = "draft_image_max_total_mb"
        const val STATE_IMAGE_MAX_ITEM_MB = "draft_image_max_item_mb"
        const val STATE_UNLOCKED = "draft_unlocked"

        /** 保存确认框最多列几行改动（超出只报条数） */
        const val DIFF_MAX_LINES = 6

        const val TEXT_TITLE = "剪贴板自定义"
        const val TEXT_DESC = "调节历史容量 / 收藏上限 / 单条与分页 / 搜索上限 / 图片容量 / 缩略图布局。\n" +
            "默认锁定：打开「解锁参数」后可调，改完点「保存」才生效（缩略图布局即时生效）。"
        const val TEXT_ENABLED = "剪贴板历史"
        const val TEXT_UNLOCK = "解锁参数"
        const val TEXT_SAVE = "保存"
        const val TEXT_RESET = "重置"
        const val TEXT_HINT_HISTORY_OFF = "剪贴板历史将关闭：点「保存」后停止采集，参数改动一并生效。"
        const val TEXT_HINT_LOCKED = "参数已锁定：打开上方「解锁参数」后可调节。"
        const val TEXT_HINT_EDITING = "编辑中：点「保存」才生效，「重置」可撤销本次修改。"
        const val TEXT_MAX_ITEMS = "历史数量上限"
        const val TEXT_MAX_TOTAL = "历史体积上限"
        const val TEXT_FAV_TITLE = "收藏软上限（超限淘汰最旧收藏）"
        const val TEXT_FAV_ITEMS = "收藏条数上限"
        const val TEXT_FAV_BYTES = "收藏体积上限"
        const val TEXT_FAV_HINT = "收藏体积上限不超过历史体积上限的 1/4。"
        /** 收藏体积的生效值说明：原值留着，动态上限只压缩**生效**的那一份（BUG.md L-982 / L-988） */
        const val TEXT_FAV_EFFECTIVE_PREFIX = "实际生效："
        const val TEXT_FAV_EFFECTIVE_SUFFIX = " MB"
        const val TEXT_FAV_CLAMPED_SUFFIX = "（受历史体积上限的四分之一限制；把历史体积调回去即可恢复本值）"
        const val TEXT_LIMIT_TITLE = "单条与分页"
        const val TEXT_MAX_ITEM = "单条上限"
        const val TEXT_EFFECTIVE_PREFIX = "生效单条上限："
        const val TEXT_EFFECTIVE_SUFFIX = " KB（受解密窗约束自动收缩）"
        const val TEXT_PANEL_PAGE = "面板单页条数"
        const val TEXT_MAX_SEARCH = "搜索结果上限"
        const val TEXT_HISTORY_ENTRY = "剪贴板历史管理"

        // ── 图片容量（2026-10-10 实施计划 §4.2） ──
        const val TEXT_IMAGE_ENABLED = "记录图片"
        const val TEXT_IMAGE_TITLE = "图片容量（与文本预算各自独立）"
        const val TEXT_IMAGE_MAX_ITEMS = "图片张数上限"
        const val TEXT_IMAGE_MAX_TOTAL = "图片体积上限"
        const val TEXT_IMAGE_MAX_ITEM = "单张图片上限"
        const val TEXT_IMAGE_HINT =
            "图片与文本各自统计 / 各自淘汰：调小这里只删最旧的非收藏图片，不动文本；" +
                "超单张上限的图不入库。图片不进备份（换机前用「剪贴板历史管理 → 图片 → 导出全部图片」）。"

        // ── 缩略图布局（2026-10-11）──
        const val TEXT_LAYOUT_TITLE = "缩略图布局（与图库快贴共用）"
        const val TEXT_LAYOUT_COLUMNS = "每行张数"
        const val TEXT_LAYOUT_HEIGHT = "缩略图行高"
        const val TEXT_LAYOUT_HINT =
            "改完立即生效：本页、图库快贴面板的「布局」键、剪贴板图片网格三处共用同一组参数；" +
                "本项与上面的「剪贴板历史」开关无关，关掉开关也照常可调。"
        const val TEXT_SAVE_SUMMARY = "将保存以下改动："
        const val TEXT_NO_CHANGE = "（没有改动）"
        const val TEXT_SAVE_WARN_DELETE = "⚠ 已收紧上限：保存后超出的记录会被删除（含最旧的收藏），不可恢复。"
        const val TEXT_SAVE_FAILED = "保存失败：设置未落盘，请重试"
        const val TEXT_TRIM_FAILED = "参数已保存，但历史裁剪未完成，稍后会自动重试"
        const val TEXT_SAVED = "已保存"
        const val TEXT_RESET_MSG = "撤销本次修改，回到进入本页时的值？（不会写盘）"
        const val TEXT_RESET_DONE = "已撤销本次修改"
        const val TEXT_DISCARD_TITLE = "放弃修改？"
        const val TEXT_DISCARD_MSG = "有未保存的修改，离开将丢弃它们。"
        const val TEXT_CONFIRM_OK = "确定"
        const val TEXT_CONFIRM_CANCEL = "取消"
        const val TEXT_ON = "开启"
        const val TEXT_OFF = "关闭"
    }
}
