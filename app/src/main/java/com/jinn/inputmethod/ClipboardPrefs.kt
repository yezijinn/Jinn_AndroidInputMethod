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

    /** 剪贴板历史总开关 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_ENABLED, value) }

    /** 历史数量上限：1 ~ [MAX_ITEMS_CAP]，默认 500 */
    var maxItems: Int
        get() {
            // 读侧同样钳位：存档里的 0 / 负数（旧版本写入、手动改 prefs）会让
            // trimByCount 直接 return，条数上限形同虚设（只剩字节预算兜底）。
            // 写侧已经钳了，两侧算法必须一致，否则「读取的一定合法」这个前提是假的。
            val v = sp.getInt(KEY_MAX_ITEMS, DEFAULT_MAX_ITEMS)
            return v.coerceIn(1, MAX_ITEMS_CAP)
        }
        set(value) = sp.edit { putInt(KEY_MAX_ITEMS, value.coerceIn(1, MAX_ITEMS_CAP)) }

    /** 历史体积上限（MB）：10 ~ 500，默认 100 */
    var maxTotalBytesMb: Int
        get() = sp.getInt(KEY_MAX_TOTAL_MB, DEFAULT_MAX_TOTAL_MB).coerceIn(10, 500)
        set(value) = sp.edit { putInt(KEY_MAX_TOTAL_MB, value.coerceIn(10, 500)) }

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
        get() = sp.getInt(KEY_FAV_MAX_ITEMS, MAX_FAV_ITEMS_CAP).coerceIn(1, MAX_FAV_ITEMS_CAP)
        set(value) = sp.edit { putInt(KEY_FAV_MAX_ITEMS, value.coerceIn(1, MAX_FAV_ITEMS_CAP)) }

    /**
     * 收藏体积软上限（MB）：1 ~ [favoriteBytesCapMb] 所容；默认取到该上限（总量上限的四分之一）。
     *
     * 与条数上限同一理由（BUG.md L-980）：默认值不能比历史行为更紧，否则老库的收藏会被静默淘汰。
     */
    var favoriteMaxBytesMb: Int
        get() = sp.getInt(KEY_FAV_MAX_MB, favoriteBytesCapMb(maxTotalBytesMb))
            .coerceIn(1, favoriteBytesCapMb(maxTotalBytesMb))
        set(value) = sp.edit { putInt(KEY_FAV_MAX_MB, value.coerceIn(1, favoriteBytesCapMb(maxTotalBytesMb))) }

    val favoriteMaxBytes: Long get() = favoriteMaxBytesMb * 1024L * 1024L

    /** 面板单页条数：2 ~ [ClipboardStore.PANEL_PAGE_ITEMS_MAX]，默认 50 */
    var panelPageItems: Int
        get() = sp.getInt(KEY_PANEL_PAGE, DEFAULT_PANEL_PAGE).coerceIn(2, ClipboardStore.PANEL_PAGE_ITEMS_MAX)
        set(value) = sp.edit { putInt(KEY_PANEL_PAGE, value.coerceIn(2, ClipboardStore.PANEL_PAGE_ITEMS_MAX)) }

    /** 搜索结果条数上限：2 ~ [MAX_SEARCH_CAP]，默认 200 */
    var maxSearchResults: Int
        get() = sp.getInt(KEY_MAX_SEARCH, DEFAULT_MAX_SEARCH).coerceIn(2, MAX_SEARCH_CAP)
        set(value) = sp.edit { putInt(KEY_MAX_SEARCH, value.coerceIn(2, MAX_SEARCH_CAP)) }

    /** 历史管理页单页条数：[PAGE_SIZE_OPTIONS] 之一，默认 50 */
    var historyPageSize: Int
        get() {
            val v = sp.getInt(KEY_HISTORY_PAGE_SIZE, DEFAULT_HISTORY_PAGE_SIZE)
            return if (v in PAGE_SIZE_OPTIONS) v else DEFAULT_HISTORY_PAGE_SIZE
        }
        set(value) = sp.edit {
            putInt(KEY_HISTORY_PAGE_SIZE, if (value in PAGE_SIZE_OPTIONS) value else DEFAULT_HISTORY_PAGE_SIZE)
        }

    /** 单条上限（KB）：4 ~ [MAX_ITEM_KB_CAP]，默认 256；生效值再受解密窗天花板约束 */
    var maxItemBytesKb: Int
        get() = sp.getInt(KEY_MAX_ITEM_KB, DEFAULT_MAX_ITEM_KB).coerceIn(4, MAX_ITEM_KB_CAP)
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
        get() = sp.getBoolean(KEY_RECLASSIFIED, false)
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
    ): Boolean {
        val totalMb = maxTotalMb.coerceIn(10, 500)
        val editor = sp.edit()
        editor.putBoolean(KEY_ENABLED, enabled)
        editor.putInt(KEY_MAX_ITEMS, maxItems.coerceIn(1, MAX_ITEMS_CAP))
        editor.putInt(KEY_MAX_TOTAL_MB, totalMb)
        editor.putInt(KEY_FAV_MAX_ITEMS, favMaxItems.coerceIn(1, MAX_FAV_ITEMS_CAP))
        editor.putInt(KEY_FAV_MAX_MB, favMaxMb.coerceIn(1, favoriteBytesCapMb(totalMb)))
        editor.putInt(KEY_MAX_ITEM_KB, maxItemKb.coerceIn(4, MAX_ITEM_KB_CAP))
        editor.putInt(KEY_PANEL_PAGE, panelPage.coerceIn(2, ClipboardStore.PANEL_PAGE_ITEMS_MAX))
        editor.putInt(KEY_MAX_SEARCH, maxSearch.coerceIn(2, MAX_SEARCH_CAP))
        return runCatching { editor.commit() }.getOrDefault(false)
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
        return out
    }

    /** 写入并返回成功应用的键数（越界值由 setter 钳位，与运行期一致） */
    internal fun importFromBackup(values: Map<String, ConfigBackup.BackupValue>): Int {
        var applied = 0
        for ((key, v) in values) {
            when (key) {
                KEY_MAX_ITEMS -> (v.value as? Int)?.let { maxItems = it; applied++ }
                KEY_MAX_TOTAL_MB -> (v.value as? Int)?.let { maxTotalBytesMb = it; applied++ }
                KEY_FAV_MAX_ITEMS -> (v.value as? Int)?.let { favoriteMaxItems = it; applied++ }
                KEY_FAV_MAX_MB -> (v.value as? Int)?.let { favoriteMaxBytesMb = it; applied++ }
                KEY_PANEL_PAGE -> (v.value as? Int)?.let { panelPageItems = it; applied++ }
                KEY_MAX_SEARCH -> (v.value as? Int)?.let { maxSearchResults = it; applied++ }
                KEY_MAX_ITEM_KB -> (v.value as? Int)?.let { maxItemBytesKb = it; applied++ }
                KEY_HISTORY_PAGE_SIZE -> (v.value as? Int)?.let { historyPageSize = it; applied++ }
            }
        }
        return applied
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 500
        const val DEFAULT_MAX_TOTAL_MB = 100
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

        /** 历史管理页可选的单页条数（页面按此循环切换；定义域的唯一真源） */
        val PAGE_SIZE_OPTIONS = intArrayOf(10, 20, 50, 100)

        /** 收藏体积上限的联动钳位（纯函数）：不超过历史体积上限的 1/4，至少 1MB */
        fun favoriteBytesCapMb(maxTotalMb: Int): Int = (maxTotalMb / 4).coerceAtLeast(1)

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_ITEMS = "max_items"
        private const val KEY_MAX_TOTAL_MB = "max_total_mb"
        private const val KEY_FAV_MAX_ITEMS = "fav_max_items"
        private const val KEY_FAV_MAX_MB = "fav_max_mb"
        private const val KEY_PANEL_PAGE = "panel_page_items"
        private const val KEY_MAX_SEARCH = "max_search_results"
        private const val KEY_MAX_ITEM_KB = "max_item_kb"
        private const val KEY_HISTORY_PAGE_SIZE = "history_page_size"

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
