package com.jinn.inputmethod

import org.junit.Assert.assertEquals
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
                Regex("""domain="file"\s+path="dicts/"\s*/>""").containsMatchIn(xml),
            )
        }
        // 云端与设备迁移是两段：只改一段会让另一条路径继续把缓存送出去
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        assertTrue("cloud-backup 段缺索引排除", "index/" in cloud)
        assertTrue("device-transfer 段缺索引排除", "index/" in device)
    }

    @Test
    fun 词库包中间件必须排除在备份与迁移之外() {
        // `dicts/` 整体不排除（用户下载的真数据），但半截包会越 25MB 配额；
        // 排除项不支持通配 ⇒ 临时件名取自写侧同一处拼装函数，改名时本用例跟着红。
        // `.restore` 后缀只在 `ConfigBackupManager` 内部可见，这里写字面量：它改名时三份 XML
        // 与本用例会一起红（报「缺 …」），不是静默失守。
        val restore = ".restore"
        val names = buildList {
            for (d in OptionalDicts.ALL) {
                add("dicts/${OptionalDicts.tempNameOf(d.fileName)}")
                add("dicts/${d.fileName}$restore")
            }
            add("dicts/${CustomDicts.packTempName()}")
            add("dicts/${CustomDicts.PACK_NAME}$restore")
            add("dicts/${CustomDicts.sourceTempName()}")
            add("dicts/${CustomDicts.SOURCE_NAME}$restore")
        }
        assertEquals("中间件名单应为 10 条（3 包 + 自定义包 + 源文本，各两种后缀）", 10, names.size)
        val full = rules("backup_rules.xml")
        for (n in names) {
            assertTrue("backup_rules.xml 缺 $n", Regex("path=\"${Regex.escape(n)}\"").containsMatchIn(full))
        }
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        for (n in names) {
            val p = Regex("path=\"${Regex.escape(n)}\"")
            assertTrue("cloud-backup 段缺 $n", p.containsMatchIn(cloud))
            assertTrue("device-transfer 段缺 $n", p.containsMatchIn(device))
        }
    }

    @Test
    fun 台账原子写中间件必须排除在备份与迁移之外() {
        // 写整表时先建 `user_picks.txt.<suffix>` 再 rename；崩溃落在两步之间会留下整表副本。
        // 排除项只能写字面路径（不支持通配），三处清单都要逐名排。
        val names = listOf("user_picks.txt.mig", "user_picks.txt.reheader")
        val full = rules("backup_rules.xml")
        for (n in names) {
            assertTrue(
                "backup_rules.xml 缺台账中间件 $n",
                Regex("path=\"${Regex.escape(n)}\"").containsMatchIn(full),
            )
        }
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        for (n in names) {
            val p = Regex("path=\"${Regex.escape(n)}\"")
            assertTrue("cloud-backup 段缺台账中间件 $n", p.containsMatchIn(cloud))
            assertTrue("device-transfer 段缺台账中间件 $n", p.containsMatchIn(device))
        }
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

    /**
     * 台账 / 词频的**孤儿件**同样不得出机（2026-10-23 安全审计，L-1454）。
     *
     * 密钥件缺失时 `LedgerCrypto.orphanExistingData` 会把台账与词频改名保留，而这两份文件
     * 书面承诺「不进系统备份 / 设备迁移」。排除项只能写字面路径，所以孤儿件必须落在固定目录
     * （`ledger_orphan/`）里 —— 早先落在 `filesDir` 根下、名字还带毫秒后缀，一条也排不掉。
     * 密钥包裹件的原子写中间件（`user_picks.key.tmp`）同属这份清单（它是半份包裹件）。
     */
    @Test
    fun 台账孤儿件目录与密钥中间件必须排除在备份与迁移之外() {
        val orphan = Regex("""domain="file"\s+path="ledger_orphan/"""")
        val keyTmp = Regex("""domain="file"\s+path="user_picks\.key\.tmp"""")
        assertTrue("backup_rules.xml 必须排除孤儿件目录", orphan.containsMatchIn(rules("backup_rules.xml")))
        assertTrue("backup_rules.xml 必须排除密钥中间件", keyTmp.containsMatchIn(rules("backup_rules.xml")))
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        for ((name, seg) in listOf("cloud-backup" to cloud, "device-transfer" to device)) {
            assertTrue("$name 段缺孤儿件目录排除", orphan.containsMatchIn(seg))
            assertTrue("$name 段缺密钥中间件排除", keyTmp.containsMatchIn(seg))
        }
        // 反向：孤儿件目录名必须与源码常量一致，否则规则排的是另一条路径
        val src = TestSources.codeOf(sourceOf("src/main/java/com/jinn/inputmethod/LedgerCrypto.kt"))
        assertTrue(
            "ORPHAN_DIR 常量必须与备份规则的目录名同值",
            Regex("""ORPHAN_DIR\s*=\s*"ledger_orphan"""").containsMatchIn(src),
        )
    }
}
