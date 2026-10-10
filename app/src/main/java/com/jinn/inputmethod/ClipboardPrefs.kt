package com.jinn.inputmethod

import android.content.Context
import androidx.core.content.edit

/**
 * 剪贴板功能配置。
 * 与主 [Prefs] 分开存到独立 SharedPreferences 文件，互不干扰。
 */
class ClipboardPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("jinn_clipboard", Context.MODE_PRIVATE)

    /**
     * 读侧收口（BUG.md L-1271）：偏好值可能被外部写坏 —— 备份导入串了键、跨版本改名后旧类型残留、
     * 其它进程改同一个文件 —— 裸 `getInt` / `getBoolean` 的 `ClassCastException` 会直达调用点，
     * 而调用点分布在采集、面板刷新、备份导入这些主线程与常驻后台路径上（崩溃或采集静默停摆）。
     * 口径与主 [Prefs] 的 `*Or` 一致：读不到 / 类型不对都退默认值。
     */
    private fun intOr(key: String, def: Int): Int = runCatching { sp.getInt(key, def) }.getOrDefault(def)

    private fun boolOr(key: String, def: Boolean): Boolean =
        runCatching { sp.getBoolean(key, def) }.getOrDefault(def)


    /**
     * 联动参数的**一致快照**（BUG-12）。字段与「剪贴板自定义」页的草稿一一对应。
     *
     * 2026-10-10 起从八键扩到十二键（图片四项）。
     */
    internal data class Snapshot(
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

    /**
     * 一次性取一组联动参数（BUG-12）。
     *
     * 逐键读多次会让「导入 / 批量保存正在进行」的读侧拿到**半新半旧**的组合；与 [applyDraft]、
     * [importFromBackup] 共用同一把锁之后，读到的必然是同一次写入之后的完整状态。
     */
    internal fun snapshot(): Snapshot = synchronized(LOCK) {
        Snapshot(
            enabled = enabled,
            maxItems = maxItems,
            maxTotalMb = maxTotalBytesMb,
            favMaxItems = favoriteMaxItems,
            favMaxMb = favoriteMaxBytesMb,
            maxItemKb = maxItemBytesKb,
            panelPage = panelPageItems,
            maxSearch = maxSearchResults,
            imageEnabled = imageCaptureEnabled,
            imageMaxItems = imageMaxItems,
            imageMaxTotalMb = imageMaxTotalMb,
            imageMaxItemMb = imageMaxItemMb,
        )
    }

    /** 剪贴板历史总开关 */
    var enabled: Boolean
        get() = boolOr(KEY_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_ENABLED, value) }

    /** 历史数量上限：1 ~ [MAX_ITEMS_CAP]，默认 500 */
    var maxItems: Int
        get() {
            // 读侧同样钳位：存档里的 0 / 负数（旧版本写入、手动改 prefs）会让
            // trimByCount 直接 return，条数上限形同虚设（只剩字节预算兜底）。
            // 写侧已经钳了，两侧算法必须一致，否则「读取的一定合法」这个前提是假的。
            val v = intOr(KEY_MAX_ITEMS, DEFAULT_MAX_ITEMS)
            return v.coerceIn(1, MAX_ITEMS_CAP)
        }
        set(value) = sp.edit { putInt(KEY_MAX_ITEMS, value.coerceIn(1, MAX_ITEMS_CAP)) }

    /** 历史体积上限（MB）：[MIN_TOTAL_MB] ~ [MAX_TOTAL_MB]，默认 100 */
    var maxTotalBytesMb: Int
        get() = intOr(KEY_MAX_TOTAL_MB, DEFAULT_MAX_TOTAL_MB).coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB)
        set(value) = sp.edit { putInt(KEY_MAX_TOTAL_MB, value.coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB)) }

    /** 历史体积上限（字节） */
    val maxTotalBytes: Long get() = maxTotalBytesMb * 1024L * 1024L

    /**
     * 收藏条数软上限：1 ~ [MAX_FAV_ITEMS_CAP]，默认取上限（等于不按条数淘汰）；超限淘汰最旧收藏。
     *
     * 默认值定成上限而不是 200（BUG.md L-980）：软上限是 2026-10-06 才引入的，老库的收藏条数
     * 可能早就超过旧默认值 —— 那样下次复制就会静默删掉最旧的收藏，与「收藏永不删」的既有口径
     * 冲突。放宽只会少删，方向是单向安全的；要收紧可在剪贴板自定义页调小。
     */
    var favoriteMaxItems: Int
        get() = intOr(KEY_FAV_MAX_ITEMS, MAX_FAV_ITEMS_CAP).coerceIn(1, MAX_FAV_ITEMS_CAP)
        set(value) = sp.edit { putInt(KEY_FAV_MAX_ITEMS, value.coerceIn(1, MAX_FAV_ITEMS_CAP)) }

    /**
     * 收藏体积软上限（MB）的存储值；**默认**取到当前动态上限（总量上限的四分之一）。
     *
     * 与条数上限同一理由（BUG.md L-980）：默认值不能比历史行为更紧，否则老库的收藏会被静默淘汰。
     */
    var favoriteMaxBytesMb: Int
        get() = favoriteMaxMbStored(intOr(KEY_FAV_MAX_MB, favoriteBytesCapMb(maxTotalBytesMb)))
        set(value) = sp.edit { putInt(KEY_FAV_MAX_MB, favoriteMaxMbStored(value)) }

    /**
     * 淘汰侧用的**生效**收藏体积（字节）：原值与动态上限取小。
     *
     * ⚠ 这里必须是生效值，不能退回 [favoriteMaxBytesMb]（原值）：原值可能大于 1/4，用它当预算等于
     * 把尚未生效的那部分也放行。
     */
    val favoriteMaxBytes: Long
        get() = effectiveFavoriteMaxMb(favoriteMaxBytesMb, maxTotalBytesMb) * 1024L * 1024L

    /** 面板单页条数：2 ~ [ClipboardStore.PANEL_PAGE_ITEMS_MAX]，默认 50 */
    var panelPageItems: Int
        get() = intOr(KEY_PANEL_PAGE, DEFAULT_PANEL_PAGE).coerceIn(2, ClipboardStore.PANEL_PAGE_ITEMS_MAX)
        set(value) = sp.edit { putInt(KEY_PANEL_PAGE, value.coerceIn(2, ClipboardStore.PANEL_PAGE_ITEMS_MAX)) }

    /** 搜索结果条数上限：2 ~ [MAX_SEARCH_CAP]，默认 200 */
    var maxSearchResults: Int
        get() = intOr(KEY_MAX_SEARCH, DEFAULT_MAX_SEARCH).coerceIn(2, MAX_SEARCH_CAP)
        set(value) = sp.edit { putInt(KEY_MAX_SEARCH, value.coerceIn(2, MAX_SEARCH_CAP)) }

    /**
     * 是否采集图片（默认开）。
     *
     * 与 [enabled] 是**两级开关**：总开关关 = 文本图片全不采集；本开关关 = 只不采集图片，
     * 文本照常。图片会让历史多出截图 / 证件照一类的敏感面，给用户一个只关图片的出口。
     */
    var imageCaptureEnabled: Boolean
        get() = boolOr(KEY_IMAGE_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_IMAGE_ENABLED, value) }

    /** 图片张数上限：1 ~ [MAX_IMAGE_ITEMS_CAP]，默认 300（与文本条数上限各自独立） */
    var imageMaxItems: Int
        get() = intOr(KEY_IMAGE_MAX_ITEMS, DEFAULT_IMAGE_MAX_ITEMS).coerceIn(1, MAX_IMAGE_ITEMS_CAP)
        set(value) = sp.edit { putInt(KEY_IMAGE_MAX_ITEMS, value.coerceIn(1, MAX_IMAGE_ITEMS_CAP)) }

    /** 图片体积上限（MB）：[MIN_IMAGE_TOTAL_MB] ~ [MAX_IMAGE_TOTAL_MB]，默认 200 */
    var imageMaxTotalMb: Int
        get() = intOr(KEY_IMAGE_MAX_TOTAL_MB, DEFAULT_IMAGE_TOTAL_MB)
            .coerceIn(MIN_IMAGE_TOTAL_MB, MAX_IMAGE_TOTAL_MB)
        set(value) = sp.edit {
            putInt(KEY_IMAGE_MAX_TOTAL_MB, value.coerceIn(MIN_IMAGE_TOTAL_MB, MAX_IMAGE_TOTAL_MB))
        }

    /**
     * 单张图片上限（MB）：[MIN_IMAGE_ITEM_MB] ~ [MAX_IMAGE_ITEM_MB]，默认 20。
     *
     * 默认值与 `GalleryInsert.MAX_BYTES` 同值（选图链路的既有上限）：两条链路对「一张多大的图
     * 可以被处理」给出同一个答案，避免「图库能贴、剪贴板历史收不下」的不一致。
     */
    var imageMaxItemMb: Int
        get() = intOr(KEY_IMAGE_MAX_ITEM_MB, DEFAULT_IMAGE_ITEM_MB)
            .coerceIn(MIN_IMAGE_ITEM_MB, MAX_IMAGE_ITEM_MB)
        set(value) = sp.edit {
            putInt(KEY_IMAGE_MAX_ITEM_MB, value.coerceIn(MIN_IMAGE_ITEM_MB, MAX_IMAGE_ITEM_MB))
        }

    /** 图片体积上限（字节） */
    val imageMaxBytes: Long get() = imageMaxTotalMb * 1024L * 1024L

    /** 单张图片上限（字节） */
    val imageMaxItemBytes: Long get() = imageMaxItemMb * 1024L * 1024L

    /** 历史管理页单页条数：[PAGE_SIZE_OPTIONS] 之一，默认 50 */
    var historyPageSize: Int
        get() {
            val v = intOr(KEY_HISTORY_PAGE_SIZE, DEFAULT_HISTORY_PAGE_SIZE)
            return if (v in PAGE_SIZE_OPTIONS) v else DEFAULT_HISTORY_PAGE_SIZE
        }
        set(value) = sp.edit {
            putInt(KEY_HISTORY_PAGE_SIZE, if (value in PAGE_SIZE_OPTIONS) value else DEFAULT_HISTORY_PAGE_SIZE)
        }

    /** 单条上限（KB）：4 ~ [MAX_ITEM_KB_CAP]，默认 256；生效值再受解密窗天花板约束 */
    var maxItemBytesKb: Int
        get() = intOr(KEY_MAX_ITEM_KB, DEFAULT_MAX_ITEM_KB).coerceIn(4, MAX_ITEM_KB_CAP)
        set(value) = sp.edit { putInt(KEY_MAX_ITEM_KB, value.coerceIn(4, MAX_ITEM_KB_CAP)) }

    /**
     * 生效的单条上限（字节）：用户值再与解密窗反推的天花板取小
     * （天花板算法与依据见 [ClipboardStore.effectiveItemLimitBytes]）。
     *
     * 三处同闸：采集（新写入）/ 备份导出与导入 / 粘贴·复制·翻译的提交侧。
     * 任何一处直接用用户原值，都会让窗口峰值突破 [ClipboardStore.DECRYPT_WINDOW_BUDGET_BYTES]。
     */
    fun effectiveMaxItemBytes(): Long = ClipboardStore.effectiveItemLimitBytes(maxItemBytesKb * 1024L)

    /**
     * 分组标签是否已按多标签规则重算过（2026-09-25 起；**运行态标记，不进备份**）。
     *
     * 重算本身幂等，但全库解密有成本，用标记保证只跑一次；失败不置位，下次启动自动重试。
     */
    var reclassified: Boolean
        get() = boolOr(KEY_RECLASSIFIED, false)
        set(value) = sp.edit { putBoolean(KEY_RECLASSIFIED, value) }

    /**
     * 等待已排队的 `apply()` 落盘（导入后要杀进程重启时调用）。
     *
     * `apply()` 是异步的，而杀进程不走任何收尾：不 flush 的话这个文件的改动可能回退，
     * 出现「主设置生效了、剪贴板设置没生效」的半套状态。
     */
    internal fun flush(): Boolean = runCatching { sp.edit().commit() }.getOrDefault(false)

    /**
     * 一次性写入整组参数（**单次 `commit`**，2026-10-06 起「剪贴板自定义」页的保存入口）。
     *
     * 为什么不用逐个 setter：每个 setter 各做一次 `apply()`（异步、无序），保存到一半进程被杀
     * 就留下**半套参数**（例如体积上限生效了、单条上限没生效）；而「保存」是用户显式的原子意图，
     * 落盘必须跟着原子。钳位口径与各 setter 保持一致，上界一律取本文件的 `*_CAP` 常量。
     *
     * @return 是否确认落盘（`commit()` 的结果；false 时调用方必须提示重试，不能当成功）
     */
    internal fun applyDraft(
        enabled: Boolean,
        maxItems: Int,
        maxTotalMb: Int,
        favMaxItems: Int,
        favMaxMb: Int,
        maxItemKb: Int,
        panelPage: Int,
        maxSearch: Int,
        imageEnabled: Boolean = true,
        imageMaxItems: Int = DEFAULT_IMAGE_MAX_ITEMS,
        imageMaxTotalMb: Int = DEFAULT_IMAGE_TOTAL_MB,
        imageMaxItemMb: Int = DEFAULT_IMAGE_ITEM_MB,
    ): Boolean {
        // 与 [snapshot] / [importFromBackup] 同一把锁（BUG-12）：读侧要么看到写入前的整组，
        // 要么看到写入后的整组，不会取到两次写入之间的混合。
        return synchronized(LOCK) {
            val totalMb = maxTotalMb.coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB)
            val editor = sp.edit()
            editor.putBoolean(KEY_ENABLED, enabled)
            editor.putInt(KEY_MAX_ITEMS, maxItems.coerceIn(1, MAX_ITEMS_CAP))
            editor.putInt(KEY_MAX_TOTAL_MB, totalMb)
            editor.putInt(KEY_FAV_MAX_ITEMS, favMaxItems.coerceIn(1, MAX_FAV_ITEMS_CAP))
            // 收藏体积按静态上界存原值：动态上限只决定生效值，这里钳掉它 = 用户的设定真丢（L-982）
            editor.putInt(KEY_FAV_MAX_MB, favoriteMaxMbStored(favMaxMb))
            editor.putInt(KEY_MAX_ITEM_KB, maxItemKb.coerceIn(4, MAX_ITEM_KB_CAP))
            editor.putInt(KEY_PANEL_PAGE, panelPage.coerceIn(2, ClipboardStore.PANEL_PAGE_ITEMS_MAX))
            editor.putInt(KEY_MAX_SEARCH, maxSearch.coerceIn(2, MAX_SEARCH_CAP))
            editor.putBoolean(KEY_IMAGE_ENABLED, imageEnabled)
            editor.putInt(KEY_IMAGE_MAX_ITEMS, imageMaxItems.coerceIn(1, MAX_IMAGE_ITEMS_CAP))
            editor.putInt(KEY_IMAGE_MAX_TOTAL_MB, imageMaxTotalMb.coerceIn(MIN_IMAGE_TOTAL_MB, MAX_IMAGE_TOTAL_MB))
            editor.putInt(KEY_IMAGE_MAX_ITEM_MB, imageMaxItemMb.coerceIn(MIN_IMAGE_ITEM_MB, MAX_IMAGE_ITEM_MB))
            runCatching { editor.commit() }.getOrDefault(false)
        }
    }

    // ── 备份导出 / 导入（见 ConfigBackup / ConfigBackupManager） ──

    /**
     * 导出**进备份**的配置键：全部是用户偏好（历史容量 / 收藏上限 / 单条与分页 / 搜索上限 / 管理页分页）。
     *
     * 运行态标记（[reclassified]）与开关（[enabled]）一律不进，否则一份包能把
     * 「本机是否采集、是否已重算」的状态带偏。
     */
    internal fun exportForBackup(): Map<String, ConfigBackup.BackupValue> {
        val out = LinkedHashMap<String, ConfigBackup.BackupValue>()
        // ⚠ `enabled` **不进备份**（2026-10-03 修复 L-751；2026-10-06 复核仍成立）：
        // 它自 2026-10-06 起已是「剪贴板自定义」页里的用户开关（不再是强制 true 的产品不变量），
        // 但导出侧照样不写回 —— 「覆盖还原」一份带 `enabled=false` 的包会**静默停止剪贴板采集**，
        // 导入摘要与完成提示都不提它，且该状态会在包与本机之间自我传播。
        // 与 [KEY_RECLASSIFIED] 同类：跨机迁移时开关该不该跟着走尚未定论，先不随包走。
        ConfigBackup.BackupValue.of(maxItems)?.let { out[KEY_MAX_ITEMS] = it }
        ConfigBackup.BackupValue.of(maxTotalBytesMb)?.let { out[KEY_MAX_TOTAL_MB] = it }
        ConfigBackup.BackupValue.of(favoriteMaxItems)?.let { out[KEY_FAV_MAX_ITEMS] = it }
        ConfigBackup.BackupValue.of(favoriteMaxBytesMb)?.let { out[KEY_FAV_MAX_MB] = it }
        ConfigBackup.BackupValue.of(panelPageItems)?.let { out[KEY_PANEL_PAGE] = it }
        ConfigBackup.BackupValue.of(maxSearchResults)?.let { out[KEY_MAX_SEARCH] = it }
        ConfigBackup.BackupValue.of(maxItemBytesKb)?.let { out[KEY_MAX_ITEM_KB] = it }
        ConfigBackup.BackupValue.of(historyPageSize)?.let { out[KEY_HISTORY_PAGE_SIZE] = it }
        // 图片四项：同为用户偏好（历史容量语义的一部分），换机应当带走。
        // ⚠ 图片**文件**不进备份（见实施计划 §1 U4），带走这三项只影响「新机器上后续采集」的额度。
        ConfigBackup.BackupValue.of(imageCaptureEnabled)?.let { out[KEY_IMAGE_ENABLED] = it }
        ConfigBackup.BackupValue.of(imageMaxItems)?.let { out[KEY_IMAGE_MAX_ITEMS] = it }
        ConfigBackup.BackupValue.of(imageMaxTotalMb)?.let { out[KEY_IMAGE_MAX_TOTAL_MB] = it }
        ConfigBackup.BackupValue.of(imageMaxItemMb)?.let { out[KEY_IMAGE_MAX_ITEM_MB] = it }
        return out
    }

    /**
     * 批量写入并返回成功应用的键数（越界值按**与 setter 相同**的钳位处理）。
     *
     * 三处口径（BUG-12）：
     *  1. 落盘走**单次 `commit`** —— 原先逐个 setter 各做一次 `apply()`，八键不同批，中途进程被杀
     *     就留下半套参数（体积上限生效了、收藏上限没生效）；
     *  2. 与 [snapshot] / [applyDraft] 共用同一把锁，读侧不会再取到两次写入之间的混合；
     *  3. 收藏体积的钳位按「**本次导入的** max_total_mb（给了就用）→ 否则当前值」算 —— 原先逐键写
     *     时它读的是当时的当前值，会写出与「导入后的总量」不自洽的收藏上限（与 [applyDraft] 同口径）。
     *
     * 落盘结果不由本函数判定：调用方另有 `flush()` 复核并把失败计进忽略数（既有口径，见导入流程）。
     */
    internal fun importFromBackup(values: Map<String, ConfigBackup.BackupValue>): Int = synchronized(LOCK) {
        var applied = 0
        val editor = sp.edit()
        for ((key, v) in values) {
            when (key) {
                KEY_MAX_ITEMS -> (v.value as? Int)?.let {
                    editor.putInt(KEY_MAX_ITEMS, it.coerceIn(1, MAX_ITEMS_CAP)); applied++
                }
                KEY_MAX_TOTAL_MB -> (v.value as? Int)?.let {
                    editor.putInt(KEY_MAX_TOTAL_MB, it.coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB)); applied++
                }
                KEY_FAV_MAX_ITEMS -> (v.value as? Int)?.let {
                    editor.putInt(KEY_FAV_MAX_ITEMS, it.coerceIn(1, MAX_FAV_ITEMS_CAP)); applied++
                }
                // 导入的收藏体积同样只按静态上界钳位：按动态上限钳会把包里的原值改掉，
                // 而「总量 1/4」那条限制只该影响生效值（BUG.md L-982）
                KEY_FAV_MAX_MB -> (v.value as? Int)?.let {
                    editor.putInt(KEY_FAV_MAX_MB, favoriteMaxMbStored(it)); applied++
                }
                KEY_PANEL_PAGE -> (v.value as? Int)?.let {
                    editor.putInt(KEY_PANEL_PAGE, it.coerceIn(2, ClipboardStore.PANEL_PAGE_ITEMS_MAX)); applied++
                }
                KEY_MAX_SEARCH -> (v.value as? Int)?.let {
                    editor.putInt(KEY_MAX_SEARCH, it.coerceIn(2, MAX_SEARCH_CAP)); applied++
                }
                KEY_MAX_ITEM_KB -> (v.value as? Int)?.let {
                    editor.putInt(KEY_MAX_ITEM_KB, it.coerceIn(4, MAX_ITEM_KB_CAP)); applied++
                }
                KEY_HISTORY_PAGE_SIZE -> (v.value as? Int)?.let {
                    editor.putInt(
                        KEY_HISTORY_PAGE_SIZE,
                        if (it in PAGE_SIZE_OPTIONS) it else DEFAULT_HISTORY_PAGE_SIZE,
                    )
                    applied++
                }
                KEY_IMAGE_ENABLED -> (v.value as? Boolean)?.let {
                    editor.putBoolean(KEY_IMAGE_ENABLED, it); applied++
                }
                KEY_IMAGE_MAX_ITEMS -> (v.value as? Int)?.let {
                    editor.putInt(KEY_IMAGE_MAX_ITEMS, it.coerceIn(1, MAX_IMAGE_ITEMS_CAP)); applied++
                }
                KEY_IMAGE_MAX_TOTAL_MB -> (v.value as? Int)?.let {
                    editor.putInt(
                        KEY_IMAGE_MAX_TOTAL_MB,
                        it.coerceIn(MIN_IMAGE_TOTAL_MB, MAX_IMAGE_TOTAL_MB),
                    ); applied++
                }
                KEY_IMAGE_MAX_ITEM_MB -> (v.value as? Int)?.let {
                    editor.putInt(
                        KEY_IMAGE_MAX_ITEM_MB,
                        it.coerceIn(MIN_IMAGE_ITEM_MB, MAX_IMAGE_ITEM_MB),
                    ); applied++
                }
            }
        }
        // 一键都没认出来就不必落盘（每次导入都写一遍空 commit 是白付）
        if (applied > 0) runCatching { editor.commit() }
        applied
    }

    companion object {

        /**
         * 八键联动参数的读写互斥（BUG-12）。
         *
         * 挂在**伴生对象**上而不是实例上：锁的有效性不该依赖「`of()` 是否给出同一个实例」这个实现细节，
         * 将来换成不缓存也照样成立。
         */
        private val LOCK = Any()

        const val DEFAULT_MAX_ITEMS = 500
        const val DEFAULT_MAX_TOTAL_MB = 100

        /**
         * 图片三项的默认值（2026-10-10 实施计划 §4.2）。
         *
         * 300 张 / 200MB / 单张 20MB：单张对齐 `GalleryInsert.MAX_BYTES`（两条链路的「一张图
         * 多大能处理」必须同答案）；总量与张数按「常态 100 张内、极端不失控」取值 ——
         * 复制图片是低频动作，且超限只淘汰最旧的**非收藏图片**（不碰文本）。
         */
        const val DEFAULT_IMAGE_MAX_ITEMS = 300
        const val DEFAULT_IMAGE_TOTAL_MB = 200
        const val DEFAULT_IMAGE_ITEM_MB = 20
        // 收藏两个上限不再有独立的默认常量（BUG.md L-980）：条数默认取 MAX_FAV_ITEMS_CAP，
        // 体积默认取 favoriteBytesCapMb(maxTotalBytesMb)，都改成按现有上限动态取值
        const val DEFAULT_PANEL_PAGE = 50
        const val DEFAULT_MAX_SEARCH = 200
        const val DEFAULT_MAX_ITEM_KB = 256
        const val DEFAULT_HISTORY_PAGE_SIZE = 50

        /** 各参数定义域的上界（唯一真源：自定义页滑块的 max 也取自这里） */
        const val MAX_ITEMS_CAP = 20000
        const val MAX_FAV_ITEMS_CAP = 1000
        const val MAX_SEARCH_CAP = 800
        const val MAX_ITEM_KB_CAP = 1024

        /** 历史体积上限的定义域（MB）；下界也是自定义页滑块的 min */
        const val MIN_TOTAL_MB = 10
        const val MAX_TOTAL_MB = 500

        /** 图片三项的定义域上/下界（唯一真源：自定义页滑块的 min/max 也取自这里） */
        const val MAX_IMAGE_ITEMS_CAP = 2000
        const val MIN_IMAGE_TOTAL_MB = 10
        const val MAX_IMAGE_TOTAL_MB = 1000
        const val MIN_IMAGE_ITEM_MB = 1
        const val MAX_IMAGE_ITEM_MB = 50

        /**
         * 收藏体积**存储值**的静态上界（MB）：总量上限的四分之一 —— 历史上能设到的最大收藏体积。
         *
         * 有它兜着，「先调小总量再调回」才不会把用户的设定压丢：动态上限只决定**生效值**
         * （[effectiveFavoriteMaxMb]），写侧一律按这条静态上界存原值（BUG.md L-982 / L-988）。
         */
        const val FAV_MAX_MB_HARD_CAP = MAX_TOTAL_MB / 4

        /** 历史管理页可选的单页条数（页面按此循环切换；定义域的唯一真源） */
        val PAGE_SIZE_OPTIONS = intArrayOf(10, 20, 50, 100)

        /** 收藏体积上限的联动钳位（纯函数）：不超过历史体积上限的 1/4，至少 1MB */
        fun favoriteBytesCapMb(maxTotalMb: Int): Int = (maxTotalMb / 4).coerceAtLeast(1)

        /**
         * 收藏体积软上限（MB）的**存储值**：只做静态合法性钳位（1 ~ [FAV_MAX_MB_HARD_CAP]），
         * 不参与「历史体积的 1/4」那条动态上限。
         *
         * 动态上限随历史体积变，把它当写侧钳位就会**真丢用户的设定**：先把历史体积调小、再调回来，
         * 收藏上限停在被压过的值上再也回不去（BUG.md L-982 / L-988）。所以存原值，生效值单独算
         * （[effectiveFavoriteMaxMb]），界面把生效值标出来。
         */
        fun favoriteMaxMbStored(raw: Int): Int = raw.coerceIn(1, FAV_MAX_MB_HARD_CAP)

        /** 收藏体积的**生效值**（MB）：原值与「历史体积的 1/4」取小（纯函数，便于单测） */
        fun effectiveFavoriteMaxMb(raw: Int, totalMb: Int): Int =
            favoriteMaxMbStored(raw).coerceAtMost(favoriteBytesCapMb(totalMb))

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_ITEMS = "max_items"
        private const val KEY_MAX_TOTAL_MB = "max_total_mb"
        private const val KEY_FAV_MAX_ITEMS = "fav_max_items"
        private const val KEY_FAV_MAX_MB = "fav_max_mb"
        private const val KEY_PANEL_PAGE = "panel_page_items"
        private const val KEY_MAX_SEARCH = "max_search_results"
        private const val KEY_MAX_ITEM_KB = "max_item_kb"
        private const val KEY_HISTORY_PAGE_SIZE = "history_page_size"

        /** 图片四项（2026-10-10 实施计划 §4.2） */
        private const val KEY_IMAGE_ENABLED = "image_capture_enabled"
        private const val KEY_IMAGE_MAX_ITEMS = "image_max_items"
        private const val KEY_IMAGE_MAX_TOTAL_MB = "image_max_total_mb"
        private const val KEY_IMAGE_MAX_ITEM_MB = "image_max_item_mb"

        /**
         * 分组标签重算完成标记（运行态）：**不进备份**（见 [exportForBackup]），
         * 它描述的是「本机库是否已按当前规则重算」，导入到别的设备没有意义。
         */
        private const val KEY_RECLASSIFIED = "reclassified"

        @Volatile
        private var instance: ClipboardPrefs? = null

        fun of(context: Context): ClipboardPrefs =
            instance ?: synchronized(this) {
                instance ?: ClipboardPrefs(context).also { instance = it }
            }
    }
}
