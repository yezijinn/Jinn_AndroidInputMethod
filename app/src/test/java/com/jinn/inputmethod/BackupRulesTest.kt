package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 / 设备迁移规则的回归护栏（`BUG.md` L-152）。
 *
 * 索引缓存是**可再生**的（基础 14 MB 上下、可选包各十几 MB）。它此前没有被排除在 Auto Backup /
 * 设备迁移之外 ⇒ 挤占 25MB 配额（超限会让**整包备份失败**，连真正该备份的下载词库也一起备不上），
 * 而换机恢复后它的键（APK mtime / versionCode）必然对不上，仍要靠清扫自愈。
 * 反向也要钉住：`dicts/`（用户下载的真数据）**不得**被顺手排除掉。
 */
class BackupRulesTest {

    private fun sourceOf(vararg candidates: String) = TestSources.rawSource(*candidates)

    private fun rules(name: String) = TestSources.codeOf(
        sourceOf("src/main/res/xml/$name", "app/src/main/res/xml/$name"),
    )

    @Test
    fun 可再生的索引缓存必须排除在备份与迁移之外() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = rules(name)
            assertTrue(
                "$name 必须排除索引缓存（可再生的十几 MB，会挤爆 25MB 配额）",
                Regex("""domain="file"\s+path="index/"""").containsMatchIn(xml),
            )
            assertTrue("$name 仍须排除剪贴板库（含加密正文）", "jinn_clipboard.db" in xml)
            assertTrue("$name 仍须排除用户词频（明文记录选过的词）", "user_freq.txt" in xml)
            assertTrue("$name 仍须排除配置（服务端地址 / 提示词正文）", "jinn_inputmethod.xml" in xml)
        }
    }

    @Test
    fun 用户下载的词库不得被备份规则顺手排除() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = rules(name)
            assertFalse(
                "$name 排除了 dicts/ —— 那是用户下载的真数据（可重下，但不是缓存）",
                Regex("""domain="file"\s+path="dicts/"""").containsMatchIn(xml),
            )
        }
        // 云端与设备迁移是两段：只改一段会让另一条路径继续把缓存送出去
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        assertTrue("cloud-backup 段缺索引排除", "index/" in cloud)
        assertTrue("device-transfer 段缺索引排除", "index/" in device)
    }

    /**
     * 诊断日志目录也必须排除（2026-10-02 修复）。
     *
     * 日志目录 = `getExternalFilesDir()/logs/`，落在 Auto Backup 的 **`external` 域**里 —— 那是
     * **默认会被整包上传**的域，与「用户主动点按钮导出诊断包」是两条独立出路（后者有 X-169 复核，
     * 前者从未被核过）：
     *  · 隐私：崩溃快照在退回全量抓取时含**其它应用**的 I/D/W/E 行（L-193）、端点主机名（L-212）、
     *    凭据长度指纹（L-384）；
     *  · 配额：目录预算 32 MB > Auto Backup 的 25 MB 配额 ⇒ 超限会让**整包备份失败**，
     *    连用户下载的 `dicts/` 都备不上（与索引缓存同款代价）。
     * 三处（full-backup-content / cloud-backup / device-transfer）缺一，那条路径就仍在往外送。
     */
    @Test
    fun 诊断日志目录必须排除在备份与迁移之外() {
        val pattern = Regex("""domain="external"\s+path="logs/"""")
        assertTrue(
            "backup_rules.xml 必须按 external 域排除 logs/（那是 Auto Backup 的默认上传域）",
            pattern.containsMatchIn(rules("backup_rules.xml")),
        )
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        assertTrue("cloud-backup 段缺 external 域日志排除", pattern.containsMatchIn(cloud))
        assertTrue("device-transfer 段缺 external 域日志排除", pattern.containsMatchIn(device))
    }
}
