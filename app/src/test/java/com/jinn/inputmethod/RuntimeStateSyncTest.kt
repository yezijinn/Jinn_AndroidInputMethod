package com.jinn.inputmethod

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 运行期状态副本守卫（源码对拍）。
 *
 * 有些设置在组件里另有一份运行期副本：模糊音掩码同时存在 `Prefs.fuzzyPinyinMask` 与
 * `PinyinEngine.fuzzyMask`（`@Volatile`，初始化时读一次）。只写其中一处的后果是
 * 「设置页显示与实际候选行为不一致」——2026-09-24 的缺陷就是导入备份后忘了同步这一份。
 *
 * 这类缺陷普通单测看不见（真实读写要 Android Context），所以用源码对拍把同步点钉住：
 * 删掉那一行、或把它挪到导入之前，这两条会先红。
 */
class RuntimeStateSyncTest {

    private fun sourceOf(name: String): String = (
        listOf(
            File("src/main/java/com/jinn/inputmethod/$name"),
            File("app/src/main/java/com/jinn/inputmethod/$name"),
        ).firstOrNull { it.isFile } ?: error("找不到 $name（当前工作目录=${File("").absolutePath}）")
        ).readText()

    @Test
    fun `导入设置项之后必须同步模糊音掩码到引擎`() {
        val text = sourceOf("ConfigBackupManager.kt")
        assertTrue(
            "导入设置项后必须调用 PinyinEngine.setFuzzyMask(prefs.fuzzyPinyinMask)：掩码在 Prefs 与引擎里各有一份",
            text.contains("PinyinEngine.setFuzzyMask(prefs.fuzzyPinyinMask)"),
        )
    }

    @Test
    fun `同步必须排在 prefs 导入调用之后`() {
        val text = sourceOf("ConfigBackupManager.kt")
        val imported = text.indexOf("prefs.importFromBackup(main)")
        val synced = text.indexOf("PinyinEngine.setFuzzyMask(prefs.fuzzyPinyinMask)")
        assertTrue("两个调用点都应存在（imported=$imported synced=$synced）", imported >= 0 && synced >= 0)
        assertTrue("同步必须在导入之后，否则同步的是旧值", synced > imported)
    }
}
