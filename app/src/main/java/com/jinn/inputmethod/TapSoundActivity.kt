package com.jinn.inputmethod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 敲击音效反馈页：从设置页「敲击音效反馈」按钮进入。
 *
 * 与模糊音 / 生僻字两页同一套骨架：**改动即落盘并即时生效**（[JinnIme.onKeyFeedbackChanged]），
 * 不设「保存」按钮；每次回前台都重画（[onStart]），避免与设置页 / 备份导入后的值不一致。
 *
 * 定义域、默认值与解析钳位全部在 [TapSound]（纯 JVM，可单测），本页只负责展示与写盘。
 *
 * 版式一律沿用既有页面的现成规格（`PageStyleParityTest` 会盯）：
 *  - 滑块行 = 标签（左）+ 拖动条（中）+ 数值（右，强调色）—— 同键盘外观页；
 *  - 单选组 = 字段标签 + `RadioGroup` 竖排 —— 同翻译原文范围页；
 *  - 卡片内小字用 `SectionTitle` / `CardHint` 的字号与色值；
 *  - 卡片一律走 [PageStyle.addCard]（与 XML 侧 `SettingsCard` 同款）。
 */
class TapSoundActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    /** 试听用的独立 SoundPool：本页不依赖 IME 是否在运行（IME 未启动也能试听） */
    private var previewPool: SoundPool? = null
    private val previewIds = IntArray(TapSound.SOUND_COUNT)

    /**
     * 音色选择弹窗。代码创建的对话框不登记就会随页面重建而泄漏（`WindowLeaked`），
     * 且它的回调会落在已销毁的实例上（写盘 + 整页重画 + 试听）—— 与 [FavoriteSymbolsActivity] 同款处理。
     */
    private var pickerDialog: AlertDialog? = null

    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tap_sound)
        prefs = Prefs(this)
        findViewById<TextView>(R.id.text_tap_title).text = TEXT_TITLE
        findViewById<TextView>(R.id.text_tap_desc).text = TEXT_DESC
        findViewById<Button>(R.id.btn_tap_close).apply {
            // 关闭键：键面字形与可听名统一来自 PageChrome（原先各页自写 ✕ / X / 关闭）
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }
        list = findViewById(R.id.tap_group_list)
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
        renderAll()
        // 先把 17 个音效排进加载队列再让用户挑：`SoundPool.load` 是异步的，
        // 而「点试听」与「建池」在同一帧发生（`playPreview` 里的惰性建池）⇒ 进页后**第一次**
        // 试听几乎必然无声（`play` 对未加载完的 id 返回 0），第二次起才正常。
        // 预载把这段时间提前到页面渲染期，用户读说明的那一两秒足够加载完。
        ensurePreview()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
        releasePreview()
    }

    override fun onDestroy() {
        // 弹窗不登记会随页面重建泄漏；它的回调还会在已销毁的实例上把试听池重新建起来，
        // 而那个实例此后再没有 onStop ⇒ 池与它持有的 native 资源直到进程结束才回收
        pickerDialog?.dismiss()
        pickerDialog = null
        releasePreview()
        super.onDestroy()
    }

    // ── 渲染 ─────────────────────────────────────────────────

    /** 三张卡片：音效 / 震动 / 分组音色 */
    private fun renderAll() {
        list.removeAllViews()

        // 卡片 1：音效（总开关 + 静音仍播 + 强度）
        val soundOn = prefs.tapSoundEnabled
        val vibrateOn = prefs.tapVibrateEnabled
        val soundCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        soundCard.addView(
            makeSwitch(TEXT_SOUND_SWITCH, soundOn) {
                prefs.tapSoundEnabled = it
                JinnIme.onKeyFeedbackChanged()
                renderAll() // 子控件的可用态随总开关变化
            },
        )
        // 子控件跟随总开关：关闭时置灰且不可点 —— 那时它们本来就不生效，
        // 允许点只会让用户以为「设了却没反应」。与震动卡同一口径（同页两套写法最费解）。
        val soundSub = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        soundSub.addView(
            makeSwitch(TEXT_SILENT_SWITCH, prefs.tapSoundOnSilent) {
                prefs.tapSoundOnSilent = it
                JinnIme.onKeyFeedbackChanged()
            },
        )
        soundSub.addView(makeVolumeRow())
        soundSub.alpha = if (soundOn) 1f else 0.45f
        setEnabledDeep(soundSub, soundOn)
        soundCard.addView(soundSub)
        PageStyle.addCard(list, soundCard)

        // 卡片 2：震动（总开关 + 四选一强度）
        val vibCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vibCard.addView(
            makeSwitch(TEXT_VIBRATE_SWITCH, vibrateOn) {
                prefs.tapVibrateEnabled = it
                JinnIme.onKeyFeedbackChanged()
                renderAll() // 档位组的可用态随总开关变化
            },
        )
        vibCard.addView(makeFieldLabel(TEXT_STRENGTH))
        val vibGroup = makeVibrateGroup(vibrateOn)
        vibGroup.alpha = if (vibrateOn) 1f else 0.45f
        setEnabledDeep(vibGroup, vibrateOn)
        vibCard.addView(vibGroup)
        PageStyle.addCard(list, vibCard)

        // 卡片 3：六组音色（一张卡 6 行 —— 它们是一组「分配」，不该散成 6 张卡；点行换音，右侧单独试听）
        val groupCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        groupCard.addView(TextView(this).apply {
            text = TEXT_GROUP_TITLE
            PageStyle.sectionTitle(this)
        })
        groupCard.addView(TextView(this).apply {
            text = TEXT_GROUP_HINT
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        val map = TapSound.parseMap(prefs.tapSoundMap)
        for (group in 0 until TapSound.GROUP_COUNT) {
            groupCard.addView(makeGroupRow(group, map[group]))
        }
        PageStyle.addCard(list, groupCard)
    }

    /** 开关行：与设置页的 `Switch` 同规格（13sp + 主色） */
    private fun makeSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            this.text = text
            isChecked = checked
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    /** 字段标签：同 `@style/SettingsLabel`（次级色 12sp，下留 2dp） */
    private fun makeFieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(2) }
    }

    /**
     * 强度行：标签（左）+ 拖动条（中）+ 数值（右，强调色）。规格照键盘外观页的滑杆行，
     * 区别只在**松手时补一次试听** —— 强度是听觉量，不试听等于盲调。
     */
    private fun makeVolumeRow(): LinearLayout {
        val value = TextView(this).apply {
            text = TapSound.volumeLabel(prefs.tapSoundVolume)
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            gravity = Gravity.END
            minWidth = dp(52)
        }
        val seek = SeekBar(this).apply {
            max = TapSound.VOLUME_MAX - TapSound.VOLUME_MIN
            progress = TapSound.clampVolume(prefs.tapSoundVolume) - TapSound.VOLUME_MIN
            // 读屏：并列的标签 TextView 不会被自动关联，不给名字就只播报一个百分比
            contentDescription = TEXT_SOUND_STRENGTH
            progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.card_stroke))
            // 实时写盘（同外观页：拖动即生效），松手才补日志与试听 ——
            // 拖动中逐格出声会糊成一片，逐格打日志也没人看
            setOnSeekBarChangeListener(progressWriter(value))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@TapSoundActivity).apply {
                // 与震动卡的「强度」区分开：同页两个「强度」分不清是在调音量还是调震感
                text = TEXT_SOUND_STRENGTH
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
            addView(value)
        }
    }

    /** 强度拖动条的监听：拖动中写盘 + 刷新数值，松手补一次日志与试听 */
    private fun progressWriter(value: TextView): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = TapSound.clampVolume(TapSound.VOLUME_MIN + progress)
                value.text = TapSound.volumeLabel(v)
                prefs.tapSoundVolume = v
                JinnIme.onKeyFeedbackChanged()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit

            override fun onStopTrackingTouch(sb: SeekBar?) {
                Diagnostics.i(TAG, "音效强度: ${prefs.tapSoundVolume}%")
                playPreview(currentTextSound())
            }
        }

    /**
     * 震动强度：四选一，规格照翻译原文范围页的单选组（主色 14sp）。
     *
     * 可用态由调用方统一处理（[setEnabledDeep] + alpha）：总开关关着时整组置灰但**仍可见** ——
     * 用户要知道有哪几档，只是暂时不可点。
     */
    private fun makeVibrateGroup(enabled: Boolean): RadioGroup {
        val current = prefs.tapVibrateStrength
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            for (tier in 0..TapSound.VIB_MAX) {
                addView(RadioButton(this@TapSoundActivity).apply {
                    id = View.generateViewId()
                    text = TapSound.VIB_LABELS[tier]
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                    // ⚠ 先设选中态、**后**挂监听：反过来的话初始赋值本身就会触发一次写入
                    isChecked = tier == current
                    isEnabled = enabled
                    setOnCheckedChangeListener { _, checked ->
                        if (!checked) return@setOnCheckedChangeListener
                        prefs.tapVibrateStrength = tier
                        JinnIme.onKeyFeedbackChanged()
                        Diagnostics.i(TAG, "震动强度: ${TapSound.VIB_LABELS[tier]}")
                    }
                })
            }
        }
    }

    /** 递归设置整棵子树的可用态：`ViewGroup.isEnabled` 不会往下传，只设根等于没设 */
    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
        }
    }

    /** 分组行：组名（左，撑开）+ 当前音色（右）+ 试听；点整行换音色 */
    private fun makeGroupRow(group: Int, index: Int): LinearLayout {
        val current = TextView(this).apply {
            text = if (index == TapSound.NONE) TEXT_NONE else TapSound.soundLabel(index)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        }
        val preview = Button(this).apply {
            text = TEXT_PREVIEW
            textSize = 12f
            setTextColor(getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.btn_aurora_secondary)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            // 「不播放」的组没有音可试：置灰并禁用，而不是点了没反应
            isEnabled = index != TapSound.NONE
            alpha = if (isEnabled) 1f else 0.4f
            setOnClickListener { playPreview(index) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@TapSoundActivity).apply {
                text = TapSound.GROUP_LABELS[group]
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(current, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) })
            addView(preview)
            // 可点击的 ViewGroup 必须自报名字，否则读屏落在这一行只念得到一个空节点
            contentDescription = "${TapSound.GROUP_LABELS[group]}，当前 ${current.text}，点按更换"
            // 整行可点：给系统标准的行按压反馈（ripple），否则「能点」这件事没有任何视觉暗示
            isClickable = true
            background = selectableItemBackground()
            setOnClickListener { showSoundPicker(group) }
        }
    }

    /** 系统标准的行按压背景（`?android:attr/selectableItemBackground`） */
    private fun selectableItemBackground(): Drawable? {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    private fun dp(value: Int): Int = PageStyle.dp(this, value)

    /** 当前「文字输入」组用的音效（强度试听就用它：那是用户按得最多的那个音） */
    private fun currentTextSound(): Int =
        TapSound.parseMap(prefs.tapSoundMap).getOrElse(TapSound.G_TEXT) { TapSound.NONE }

    private fun showSoundPicker(group: Int) {
        // 列表项带时长：用户不点开也能分辨「短 tick」与「长 thock」
        val items = Array(TapSound.SOUND_COUNT + 1) { i ->
            if (i == TapSound.SOUND_COUNT) TEXT_NONE else TapSound.soundLabel(i)
        }
        val current = TapSound.parseMap(prefs.tapSoundMap).getOrElse(group) { TapSound.NONE }
        // 单选形态而不是纯列表：当前用的是哪一个直接标在列表上，不必回头看行内文字
        val checked = if (current == TapSound.NONE) TapSound.SOUND_COUNT else current
        // 连点两行时先收掉上一个窗口：否则两个弹窗叠着，只有后关的那个被释放
        pickerDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(TapSound.GROUP_LABELS[group])
            .setSingleChoiceItems(items, checked) { d, which ->
                val idx = if (which == TapSound.SOUND_COUNT) TapSound.NONE else which
                val map = TapSound.parseMap(prefs.tapSoundMap)
                map[group] = idx
                prefs.tapSoundMap = TapSound.formatMap(map)
                JinnIme.onKeyFeedbackChanged()
                // 单选形态不会自动关闭：选完自己收起来，否则用户还要再点一次
                d.dismiss()
                renderAll()
                // 选中即试听：换音色是听觉决策，弹窗关掉再点试听要多一步
                if (idx != TapSound.NONE) playPreview(idx)
            }
            .create()
        pickerDialog = dialog
        dialog.show()
    }

    // ── 试听 ─────────────────────────────────────────────────

    /**
     * 本页自建的试听 SoundPool：不复用 [KeyFeedback] —— 后者是 IME 进程内的单例，
     * IME 没启动（用户只是进来挑音色）时它根本没挂载。试听必须独立于键盘可用与否。
     */
    private fun ensurePreview() {
        if (previewPool != null) return
        val pool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        for (i in 0 until TapSound.SOUND_COUNT) {
            val path = "${TapSound.ASSET_DIR}/${TapSound.assetName(i)}"
            previewIds[i] = runCatching {
                assets.openFd(path).use { pool.load(it, 1) }
            }.getOrDefault(0)
        }
        previewPool = pool
    }

    private fun playPreview(index: Int) {
        if (index == TapSound.NONE) return
        // 已销毁的实例上不再建池：弹窗回调可能晚于 onDestroy 到达，那时建起来的池再没机会释放
        // （这个实例不会再有 onStop），而且会出现「看不见的页面在出声」
        if (isFinishing || isDestroyed) return
        ensurePreview()
        val pool = previewPool ?: return
        val id = previewIds.getOrElse(index) { 0 }
        if (id == 0) return
        val vol = TapSound.clampVolume(prefs.tapSoundVolume) / 100f
        runCatching { pool.play(id, vol, vol, 1, 0, 1.0f) }
    }

    private fun releasePreview() {
        previewPool?.release()
        previewPool = null
        previewIds.fill(0)
    }

    private companion object {
        const val TAG = "TapSound"

        // 文案在代码里下发：strings.xml 默认不改动，与设置页的 TEXT_* 同做法
        const val TEXT_TITLE = "敲击音效反馈"
        const val TEXT_DESC = "给按键配上声音与震动。\n" +
            "六个分组可各选一个音色（全程真实键盘录音）。\n" +
            "手机静音或关闭系统触摸提示音时，按键音按系统口径不出声。"
        const val TEXT_SOUND_SWITCH = "敲击音效"
        const val TEXT_SILENT_SWITCH = "静音时仍播放"
        const val TEXT_SOUND_STRENGTH = "音效强度"
        const val TEXT_VIBRATE_SWITCH = "按键震动"
        const val TEXT_STRENGTH = "强度"
        const val TEXT_GROUP_TITLE = "分组音色"
        const val TEXT_GROUP_HINT = "点一行即可换音色；右侧按钮可单独试听当前音色"
        const val TEXT_PREVIEW = "试听"
        const val TEXT_NONE = "不播放"
    }
}
