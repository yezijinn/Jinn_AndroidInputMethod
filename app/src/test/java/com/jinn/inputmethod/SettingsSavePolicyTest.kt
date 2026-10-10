package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘时机与「默认值不许比历史行为更紧」两条策略的守卫（`BUG.md` L-967 / L-980）。
 *
 * 这两条都属于「写错不报错、只在真实场景下悄悄丢数据」：前者要用户粘贴完凭据就被系统回收
 * 才暴露，后者要老库的收藏超过旧默认值才暴露。所以钉源码里的收敛点。
 */
class SettingsSavePolicyTest {

    /**
     * OpenAI 配置页必须有「输入停顿即落盘」（L-967，与翻译设置页 L-209 同款）。
     *
     * 原先只有 `onPause` 与显式保存两个回写点：用户粘贴完 Key、焦点还没移开就被回收
     * （内存压力、后台清理），这段配置根本不写盘，而页面提示写着「改动也会在离开页面时自动保存」。
     */
    @Test
    fun OpenAI配置页必须有输入停顿自动保存() {
        val src = TestSources.codeSource("OpenAiSettingsActivity.kt")
        assertTrue("要有防抖窗口常量", "AUTOSAVE_DEBOUNCE_MS" in src)
        // 值域钉（BUG-09）：只查符号时，把 800L 改成 0L（防抖消失 ⇒ 每敲一键写一次盘）照样全绿，
        // 而那正是这条守卫要防的行为退化。下界防「形同没有防抖」，上界防「久到用户以为没保存」。
        for (file in listOf("OpenAiSettingsActivity.kt", "TranslationSettingsActivity.kt")) {
            val s = TestSources.codeSource(file)
            val name = if (file.startsWith("OpenAi")) "AUTOSAVE_DEBOUNCE_MS" else "CREDENTIAL_AUTOSAVE_DEBOUNCE_MS"
            val ms = Regex(name + """\s*[:=]\s*(\d+)L?""").find(s)?.groupValues?.get(1)?.toLong()
            assertTrue("$name 没解析出数值（写法变了？）", ms != null)
            assertTrue("$file：防抖窗口 $ms ms 太短（等于没有防抖，每次按键都写盘）", ms!! >= 200L)
            assertTrue("$file：防抖窗口 $ms ms 太长（用户会以为保存没生效）", ms <= 5_000L)
        }
        assertTrue("字段装配时挂上 watcher", "addTextChangedListener(autosaveWatcher)" in src)
        val watcher = TestSources.blockAfter(src, "autosaveWatcher = object")
        assertTrue("程序化回填不算改动（用 loading 抑制）", "if (!loading) scheduleAutosave()" in watcher)
        val pause = TestSources.blockAfter(src, "override fun onPause()")
        assertTrue("离开页面要撤掉排队的任务", "removeCallbacks(autosaveRunnable)" in pause)
        val runnable = TestSources.window(src, "autosaveRunnable = Runnable", "private val autosaveWatcher")
        assertTrue("导入在途时跳过（与 onPause 同一条守卫）", "ConfigBackupManager.importing" in runnable)
        // L-1050：三条落盘路径口径一致 —— 停顿自动保存也要消费未落盘集合并给提示，
        // 否则 Keystore 锁住时填完 Key 就不再动的用户全程零信号，重启后回到未配置
        assertTrue("自动保存也要消费未落盘集合", "unpersistedCredentialKeys()" in runnable)
        assertTrue("未落盘要给提示", "textSaveHint.text = TEXT_SAVE_NOT_PERSISTED" in runnable)
        // 落盘结论如实记：无条件写「已保存」会把 fail-closed 的失败伪装成成功（L-1050）
        val save = TestSources.blockAfter(src, "private fun saveValues()")
        assertTrue("失败要写成未落盘", "未完全落盘" in save)
    }

    /**
     * 未落盘提示在落盘成功后要撤回（L-1054）。
     *
     * 两个配置页的自动保存路径原先只会置位这一句：锁屏时录入 → 提示「凭据未写入本机」→
     * 解锁后在同一输入框继续打并成功落盘，提示若仍停在旧态就与真实状态分叉，
     * 用户会把刚输好的凭据再输一遍。唯一复位点是输入框重新获得焦点，停在同框里就不会触发。
     */
    @Test
    fun 未落盘提示在落盘成功后要撤回() {
        for (file in listOf("OpenAiSettingsActivity.kt", "TranslationSettingsActivity.kt")) {
            val src = TestSources.codeSource(file)
            assertTrue(
                "$file：落盘成功要撤回未落盘提示",
                "else if (textSaveHint.text == TEXT_SAVE_NOT_PERSISTED)" in src &&
                    "textSaveHint.text = TEXT_SAVE_IDLE" in src,
            )
        }
    }

    /**
     * 收藏两个软上限的默认值不许比历史行为更紧（L-980）。
     *
     * 软上限是后加的，老库的收藏条数/体积可能早就超过旧默认值（200 条 / 5MB）——
     * 那样下次复制就会静默删掉最旧的收藏，与「收藏永不删」的既有口径冲突。
     * 所以默认取到各自的上限，方向是单向安全的；用户要收紧自己去调小。
     */
    @Test
    fun 收藏上限的默认值不得比历史行为更紧() {
        val src = TestSources.codeSource("ClipboardPrefs.kt")
        assertTrue(
            "条数默认取上限",
            "intOr(KEY_FAV_MAX_ITEMS, MAX_FAV_ITEMS_CAP)" in src,
        )
        assertTrue(
            "体积默认取联动上限",
            "intOr(KEY_FAV_MAX_MB, favoriteBytesCapMb(maxTotalBytesMb))" in src,
        )
        assertFalse("旧的独立默认常量不再存在", "DEFAULT_FAV_MAX_ITEMS" in src)
        assertFalse("旧的独立默认常量不再存在", "DEFAULT_FAV_MAX_MB" in src)
        // 用户显式设过的值仍优先：键存在时读出来的就是用户值
        assertTrue("用户设置仍可收紧", "coerceIn(1, MAX_FAV_ITEMS_CAP)" in src)
    }
}
