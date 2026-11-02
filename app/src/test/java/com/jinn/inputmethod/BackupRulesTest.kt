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

    /** 与平台同口径归一：`./x`、`x/`、`x` 同指；`.` / 空串 / 缺省都是域根（归一成 ""） */
    private fun normPath(p: String): String {
        val s = p.trim().removePrefix("./").trimEnd('/')
        return if (s.isEmpty() || s == ".") "" else s
    }

    private val TAG_ATTR = Regex("""\b(domain|path)\s*=\s*["']([^"']*)["']""")

    /** 取这段规则里 `domain` 域的全部排除路径（已归一；缺 `path` 即域根） */
    private fun pathsOf(xml: String, domain: String): Set<String> =
        Regex("""<exclude\b([^>]*)>""").findAll(xml).mapNotNull { m ->
            val attrs = TAG_ATTR.findAll(m.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
            if (attrs["domain"] != domain) return@mapNotNull null
            normPath(attrs["path"] ?: "")
        }.toSet()

    /**
     * 与平台同口径地判「这段规则有没有排除 `domain/path`」。
     *
     * 平台侧（`FullBackup.BackupScheme` + `BackupAgent`）：规则先经
     * `new File(domainDir, path).getCanonicalPath()` 变成**绝对路径**，遍历时按
     * `excludePath.equals(filePath)` **精确相等**判定 —— 没有前缀、没有优先级。
     * 于是这些写法彼此等价，判据必须同样认：
     *  · 属性顺序（`domain` / `path` 谁先写）
     *  · 等号两侧空格、单引号或双引号
     *  · `path` 的 `.` / `./` / 结尾斜杠 / 整个 `path` 缺省（都指域根）
     * 写死字面比对的话，`path="dicts"`（最常见的漏写斜杠）会让 `assertFalse` 那条照样绿 ——
     * 而平台真的把 `dicts/` 整目录排除了，用户的真词库全丢。
     */
    private fun hasExclude(xml: String, domain: String, path: String): Boolean =
        normPath(path) in pathsOf(xml, domain)

    /**
     * 该段里有没有**任何** `<exclude domain=DOMAIN …>`（不看 path）。
     *
     * 用于负向保底：`root` 域指向 `/data/data/<pkg>/`，`path="files/dicts/part2.xz"` 这类
     * 逐文件写法与 `file` 域同指（排除集是扁平绝对路径集），按名字逐条的正向断言一条都不响。
     */
    private fun hasDomainExclude(xml: String, domain: String): Boolean =
        pathsOf(xml, domain).isNotEmpty()

    /**
     * 隐私数据的**逐条**排除必须在三份清单里都在（数据驱动，不靠手点三条）。
     *
     * 判据一律精确路径 + 三段分别判：清单里有 `jinn_clipboard.db-journal`、`user_freq.txt.tmp`、
     * `jinn_inputmethod.xml.bak` 这类同前缀兄弟名，子串判据在「误删主件那一行」时仍命中兄弟 ⇒ 静默失守
     * （L-408 就回归过一次）；而 `data_extraction_rules.xml` 的 cloud / device-transfer 两段是
     * 逐条重复的，只查一次会让「只删换机那段」也全绿。
     */
    @Test
    fun 隐私数据的排除必须在三份清单里逐条都在() {
        val required = listOf(
            "file" to "index/",
            "file" to "clipboard/",
            "file" to "ledger_orphan/",
            "file" to "user_freq.txt",
            "file" to "user_freq.txt.tmp",
            "file" to "user_picks.txt",
            "file" to "user_picks.key",
            "file" to "user_picks.key.tmp",
            // 凭据的软件密钥件（2026-11-01）：无锁屏设备上凭据是 `v2:` 密文，密钥就躺在 filesDir 里 ——
            // 密钥与密文一起出机等于把「解锁的钥匙」也交出去，而 prefs 早已被排除，只剩这把钥匙裸奔。
            // 取源码常量而不是写字面量：改名必须同时改规则，否则这条当场红（规则会静默排空路径）。
            "file" to LocalCredentialCipher.FILE_NAME,
            "file" to LocalCredentialCipher.FILE_NAME + ".tmp",
            "database" to "jinn_clipboard.db",
            "database" to "jinn_clipboard.db-journal",
            "database" to "jinn_clipboard.db-wal",
            "database" to "jinn_clipboard.db-shm",
            "sharedpref" to "jinn_inputmethod.xml",
            "sharedpref" to "jinn_inputmethod.xml.bak",
            "sharedpref" to "jinn_clipboard.xml",
            "sharedpref" to "jinn_clipboard.xml.bak",
            "external" to "logs/",
        )
        val segments = mutableMapOf("backup_rules.xml" to rules("backup_rules.xml"))
        val transfer = rules("data_extraction_rules.xml")
        segments["cloud-backup"] = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        segments["device-transfer"] = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        for ((domain, path) in required) {
            for ((seg, text) in segments) {
                assertTrue(
                    "$seg 缺 $domain:$path（隐私数据不得随备份 / 迁移出机）",
                    hasExclude(text, domain, path),
                )
            }
        }
        // 整域保底：任何域的**全域排除**都不许出现（`path` 为 `.` / `./` / 空 / 缺省都指域根）。
        // 实测（2026-11-01，API 34 + `bmgr backupnow`）：往 `data_extraction_rules.xml` 的
        // `<cloud-backup>` 段加一行 `<exclude domain="file" path="."/>` ⇒
        // `/data/data/<pkg>/files` 从 `FullBackup_native: measured` 列表里**整条消失**，
        // 备份随后被 transport 拒绝（-1002）⇒ `dicts/` 真词库一个都不上云。
        // 加在 `backup_rules.xml` 里则**无效** —— 该文件在 API≥31 根本不被解析
        // （`dataExtractionRules` 存在即走新 scheme），这也是上一轮误判「不生效」的原因。
        // 用户后果：换机后词库全无，而备份界面照旧报成功。将来真要整域排除，先改本断言并说明理由。
        for ((seg, text) in segments) {
            for (d in listOf("file", "database", "sharedpref", "external")) {
                assertFalse(
                    "$seg 出现了 $d 域的全域排除 —— 整域排除会让真词库/库/配置整体不上云",
                    hasExclude(text, d, ""),
                )
            }
            // `root` 域要**整段禁**（不是只禁域根，也不必再列进上面的五域表 —— 那条被本条严格包含）：
            // `root` = `/data/data/<pkg>/`，而排除集在同一 destination 内**跨域共享**且按
            // `excludePath.equals(filePath)` 精确相等判定（`BackupAgent.fullBackupFileTree`：
            // 每个节点含起节点都先判排除），所以 `root`+`files/dicts/` 与 `file`+`dicts/`
            // 归一成**同一条绝对路径**，命中即整棵子树不出。
            // 实测（2026-11-01，bk34 / API 34，`bmgr backupnow`）：加这一行后
            // `/data/data/<pkg>/files` 从 `FullBackup_native: measured` 列表**整条消失**
            // （只剩 `user_de` 那条），备份随后被 transport 拒绝（-1002）。
            // 只禁域根的断言对此**一条都不响** —— 本仓曾这样缩窄过一次，特此钉死。
            // 逃生阀：确需 root 域（例如 dataDir 顶层第三方自建目录）时改本断言并说明理由。
            assertFalse(
                "$seg 出现了 root 域排除 —— root 是跨域口，子树写法同样清空真词库",
                hasDomainExclude(text, "root"),
            )
        }
        // 正向对照：上面的断言全靠 `pathsOf` 认得出规则。若它被「简化」成坏正则，
        // 十二条 assertFalse 会一起变绿 —— 整域保底就形同不存在。这里用合成样本钉住谓词本身。
        assertTrue(
            "判据要认得带空格与单引号的等价写法",
            hasExclude("""<exclude domain = 'root' path="."/>""", "root", ""),
        )
        assertTrue("缺 path 也算全域", hasExclude("""<exclude domain="file"/>""", "file", ""))
        assertTrue("结尾斜杠与缺省要视同", hasExclude("""<exclude domain="file" path="dicts"/>""", "file", "dicts/"))
        // `./` 前缀：平台 `getCanonicalPath()` 会吃掉它。今天清单里没有这种写法，
        // 若 `normPath` 的 `removePrefix("./")` 被删，上面三条都不会红 ⇒ 必须单独钉住
        assertTrue("`./` 前缀也要认", hasExclude("""<exclude domain="file" path="./dicts/"/>""", "file", "dicts/"))
    }

    @Test
    fun 用户下载的词库不得被备份规则顺手排除() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = rules(name)
            assertFalse(
                "$name 排除了 dicts/ —— 那是用户下载的真数据（可重下，但不是缓存）",
                hasExclude(xml, "file", "dicts/"),
            )
        }
        // 云端与设备迁移是两段：只改一段会让另一条路径继续把缓存送出去
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        assertTrue("cloud-backup 段缺索引排除", hasExclude(cloud, "file", "index/"))
        assertTrue("device-transfer 段缺索引排除", hasExclude(device, "file", "index/"))
        // 逐名也要钉：只判 `dicts/` 整个目录的话，把**某一个真包**单独排除（比如嫌它太大）
        // 全部断言仍绿，而换机恢复后那个包就永远没了、引擎退化成小词库且无提示
        val keep = buildList {
            for (d in OptionalDicts.ALL) add("dicts/${d.fileName}")
            add("dicts/${CustomDicts.PACK_NAME}")
        }
        val segments = mapOf(
            "backup_rules.xml" to rules("backup_rules.xml"),
            "cloud-backup" to cloud,
            "device-transfer" to device,
        )
        for (n in keep) {
            for ((seg, text) in segments) {
                assertFalse("$seg 把真词库 $n 排除了 —— 那是换机后唯一的词库来源", hasExclude(text, "file", n))
                // `root` 域是 `file` 域的等价写法（`root` = `/data/data/<pkg>/`），能编译、能生效，
                // 只查 `file` 的话这条越域口全绿 ⇒ 真包照样被排除（黑名单机制唯一的跨域缺口）
                assertFalse("$seg 用 root 域把真词库 $n 排除了", hasExclude(text, "root", "files/$n"))
            }
        }
        assertFalse(
            "dicts/ 不得被 root 域整体排除",
            hasExclude(rules("backup_rules.xml"), "root", "files/dicts/"),
        )
        // 明文源文本**不**并列进 keep：它不是词库来源（引擎只装 `.xz`，src 只服务编辑页回显），
        // 且它该不该随备份出机属作者拍板项（L-1540）—— 钉成「不得排除」等于替作者做了决定。
        // 这里只钉三条**任何决定都不能破**的：
        //  ① 两条云备份规则必须同口径（`backup_rules.xml` 是 11- 的云备份，漏一条等于漏一档系统）；
        //  ② 云侧与设备迁移侧不得同时排除 —— 那是「换机后编辑页必空」的丙方案，
        //     须先做「从 xz 反推源文本」才成立（反推有损：注释 / 空行 / 列序等 7 类信息不在 pack 里）；
        //  ③ 迁移侧排除集必须是云侧的子集 —— 只排除迁移侧是最容易犯的写错段，
        //     结果是「明文照旧上云 + 换机又丢回显」两头都亏。
        // 于是「只排除 cloud-backup、device-transfer 保留」的甲方案照样绿，不会被门禁挡住。
        val src = "dicts/${CustomDicts.SOURCE_NAME}"
        val inFull = hasExclude(rules("backup_rules.xml"), "file", src)
        val inCloud = hasExclude(cloud, "file", src)
        val inTransfer = hasExclude(device, "file", src)
        assertEquals("源文本在两条云备份规则里必须同口径（L-1540）", inFull, inCloud)
        assertFalse(
            "云侧与设备迁移都排除了源文本 $src —— 换机后编辑页没有可回显的字（走丙须先做反推，见 L-1540）",
            inCloud && inTransfer,
        )
        assertFalse(
            "只排除设备迁移侧而不排除云侧 $src —— 明文仍上云、换机又丢回显，两头都亏（L-1540）",
            inTransfer && !inCloud,
        )
    }

    @Test
    fun 词库包中间件必须排除在备份与迁移之外() {
        // `dicts/` 整体不排除（用户下载的真数据），但半截包会越 25MB 配额；
        // 排除项不支持通配 ⇒ 临时件名取自写侧同一处拼装函数，改名时本用例跟着红。
        // ⚠ 排除项不支持通配，后缀一旦与源码不同源就会静默失守：XML 与本用例都停在旧值而照样绿。
        // 故这里直接用源码常量，不再写字面量副本（也就不需要再钉「RESTORE_SUFFIX 仍是 .restore」）。
        val restore = ConfigBackupManager.RESTORE_SUFFIX
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
            assertTrue("backup_rules.xml 缺 $n", hasExclude(full, "file", n))
        }
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        for (n in names) {

            assertTrue("cloud-backup 段缺 $n", hasExclude(cloud, "file", n))
            assertTrue("device-transfer 段缺 $n", hasExclude(device, "file", n))
        }
    }

    @Test
    fun 台账原子写中间件必须排除在备份与迁移之外() {
        // 写整表时先建 `user_picks.txt.<suffix>` 再 rename；崩溃落在两步之间会留下整表副本。
        // 排除项只能写字面路径（不支持通配），三处清单都要逐名排。
        // 名字由 `SelectionLedger` 的常量拼出：源码只有一份定义，改后缀时本用例与三份 XML 一起红，
        // 不需要再用正则去钉「源码里出现过这个字面量」（那种钉会被删侧的字面量满足）。
        val names = listOf(
            "${SelectionLedger.FILE_NAME}${SelectionLedger.MIG_SUFFIX}",
            "${SelectionLedger.FILE_NAME}${SelectionLedger.REHEADER_SUFFIX}",
        )
        val full = rules("backup_rules.xml")
        for (n in names) {
            assertTrue(
                "backup_rules.xml 缺台账中间件 $n",
                hasExclude(full, "file", n),
            )
        }
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        for (n in names) {

            assertTrue("cloud-backup 段缺台账中间件 $n", hasExclude(cloud, "file", n))
            assertTrue("device-transfer 段缺台账中间件 $n", hasExclude(device, "file", n))
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
        assertTrue(
            "backup_rules.xml 必须按 external 域排除 logs/（那是 Auto Backup 的默认上传域）",
            hasExclude(rules("backup_rules.xml"), "external", "logs/")
        )
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        assertTrue("cloud-backup 段缺 external 域日志排除", hasExclude(cloud, "external", "logs/"))
        assertTrue("device-transfer 段缺 external 域日志排除", hasExclude(device, "external", "logs/"))
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
        assertTrue("backup_rules.xml 必须排除孤儿件目录", hasExclude(rules("backup_rules.xml"), "file", "ledger_orphan/"))
        assertTrue("backup_rules.xml 必须排除密钥中间件", hasExclude(rules("backup_rules.xml"), "file", "user_picks.key.tmp"))
        val transfer = rules("data_extraction_rules.xml")
        val cloud = TestSources.window(transfer, "<cloud-backup>", "</cloud-backup>")
        val device = TestSources.window(transfer, "<device-transfer>", "</device-transfer>")
        for ((name, seg) in listOf("cloud-backup" to cloud, "device-transfer" to device)) {
            assertTrue("$name 段缺孤儿件目录排除", hasExclude(seg, "file", "ledger_orphan/"))
            assertTrue("$name 段缺密钥中间件排除", hasExclude(seg, "file", "user_picks.key.tmp"))
        }
        // 反向：孤儿件目录名必须与源码常量一致，否则规则排的是另一条路径
        val src = TestSources.codeOf(sourceOf("src/main/java/com/jinn/inputmethod/LedgerCrypto.kt"))
        assertTrue(
            "ORPHAN_DIR 常量必须与备份规则的目录名同值",
            Regex("""ORPHAN_DIR\s*=\s*"ledger_orphan"""").containsMatchIn(src),
        )
        // 词频坏件（`.bad-<毫秒>`）排不掉 ⇒ 必须落进同一目录，否则随备份出机且永不回收
        val freq = TestSources.codeOf(sourceOf("src/main/java/com/jinn/inputmethod/UserFrequency.kt"))
        val q = TestSources.blockAfter(freq, "private fun quarantine(f: File)")
        assertTrue("词频坏件必须落在孤儿件目录", "LedgerCrypto.ORPHAN_DIR" in q)
        assertTrue("坏件名要带毫秒后缀去重", "currentTimeMillis" in q)
    }

    /**
     * 词库恢复中间件（`dicts/` 下以 `.restore` 结尾的残件）的**常规回收口**必须还在。
     *
     * 排除出备份只解决「不出机」，盘上的残件仍要有人清：唯一入口是
     * `ConfigBackupManager.cleanupStaleCache` 尾部的 `dicts/` 那段，触发点是进设置页。
     * 它此前零测试引用 —— 整段被删也能全绿，而残件会一直占着词库行且删不掉。
     *
     * 判据一律收窄到 `dicts/` 段：**整个函数体**里 `tempInFlight` / `STALE_MIN_AGE_MS` /
     * `f.delete()` / `Diagnostics.w` 四处 token 在前半段（cacheDir 清理）也出现，
     * 按函数体判的话「删掉 dicts 段的在途判或年龄窗」照样全绿。
     */
    @Test
    fun 词库恢复中间件必须有常规回收口() {
        val mgr = TestSources.codeOf(sourceOf("src/main/java/com/jinn/inputmethod/ConfigBackupManager.kt"))
        val dicts = TestSources.window(
            mgr,
            "val dictDir = File(context.filesDir, DICT_DIR)",
            "\n    }",
        )
        assertTrue("回收口要按后缀常量收", "endsWith(RESTORE_SUFFIX)" in dicts)
        assertTrue("在途中间件不得被清", "tempInFlight" in dicts)
        assertTrue("年龄窗是漏登记的兜底", "STALE_MIN_AGE_MS" in dicts)
        assertTrue("回收口必须真的删", "f.delete()" in dicts)
        assertTrue("删不掉要留痕（否则静默失败没人知道）", "Diagnostics.w" in dicts)
        val act = TestSources.codeOf(TestSources.rawSourceOfShortName("SettingsActivity.kt"))
        assertTrue("回收口必须挂在设置页启动路径上", "ConfigBackupManager.cleanupStaleCache" in act)

        // 明文中间件那一段同样要留痕：它删的是导出包的配置节明文，失败静默会一直躺在缓存里。
        // 判据钉**分支归属**而不是 token 存在：
        //  · 只判「有 Diagnostics.w / 有 f.delete()」⇒ 「把 W 挪进成功分支」仍绿，失败与成功混成
        //    同一种日志；按先后顺序判（W 在 delete 之后）同样挡不住这种形态。
        //  · 先取成功块：它不含 W（否则失败与成功混成同一种日志）；
        //  · 再取 else 块：它必须含 W。两处都用 `blockAfter`，锚缺失即抛错 ⇒ fail-closed。
        //    ⚠ 不能拿「else 之前的整段」判「不含 W」：cache 段别处再加一条 W（很自然的改进）
        //    就会假红 —— 判据必须圈在分支里，不是圈在分支之前。
        val plain = TestSources.window(mgr, "val dir = File(context.cacheDir, CACHE_DIR)", "val dictDir =")
        assertTrue("明文回收口必须是「删成功或已不在」", "f.delete() || !f.exists()" in plain)
        val okBlock = TestSources.blockAfter(plain, "if (f.delete() || !f.exists())")
        assertFalse("成功分支不许打失败留痕（失败与成功会混成一种日志）", "Diagnostics.w" in okBlock)
        val failBlock = TestSources.blockAfter(plain.substringAfter(okBlock), "else")
        assertTrue("失败分支必须留痕", "Diagnostics.w" in failBlock)
    }

    /**
     * `file` 域必须是**黑名单**（只写 `<exclude>`，不能出现 `<include>`）。
     *
     * 白名单化试过两次都被 lint 否决（判据来源：AGP 8.5.2 / `lint-checks 31.5.2` 的
     * `FullBackupContent`，报错 `X is not in an included path`；升级后需重评）：
     *  ① 加 `<include domain="file" path="dicts/"/>` ⇒ 其余三域 20+ 条 exclude 全被判冗余；
     *  ② 再给其余三域各补 `<include domain="…" path="."/>`（`"."` 是 lint 的全路径特例）
     *     ⇒ `file` 域自己的 9 条 exclude 仍全报错，因为它们不在 `dicts/` 之下。
     * 根因：include 按域生效、只覆盖同域内落在其路径下的 exclude，没有「本域全收 + 逐条排除」的写法。
     *
     * 代价要记牢：黑名单的默认值是「上云」，新增持久化文件必须同步本清单。
     * 实测佐证（2026-11-01，AVD + `bmgr backupnow` 解包，见 `docs/备份排除运行时验证-2026-11-01.md`）：
     * 新建一个规则没覆盖的 `zzz_unknown_probe.txt`，它确实出现在备份包里。
     */
    @Test
    fun 备份规则必须是黑名单不得出现include() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            assertFalse(
                "$name 出现了 <include>：它按域生效，会让其它域的 exclude 全被 lint 判冗余（实测构建失败）",
                Regex("""<include\b""").containsMatchIn(rules(name)),
            )
        }
    }

    /**
     * manifest 必须同时声明两份规则 —— 本仓所有备份结论的前提。
     *
     * 平台 `FullBackup.BackupScheme.parseSchemeForBackupDestination`：
     * `dataExtractionRules` 里**有对应段**即走新 scheme，`fullBackupContent` **一次都不解析**。
     * 于是 API≥31 只有 `data_extraction_rules.xml` 生效，`backup_rules.xml` 是死文件
     * （它仅在 Android ≤11 生效）。
     *
     * 这个前提若没被钉住：属性被删 / 改名而两份 XML 都在 ⇒ 生效文件静默换回 `backup_rules.xml`，
     * 上面 29 条三段对比与「哪份生效」的结论全部失真。本仓已因「探针写进死文件」误判过一次。
     *
     * 同一处还要钉 `allowBackup`：它是**更上位**的开关，置 false 后 Auto Backup / 设备迁移整体关闭，
     * 三份规则与上面全部断言都变成死配置而**照样全绿** —— 换机后词库、配置全丢且无任何提示。
     * 「隐私加固」类改动的第一反应往往就是它，所以必须钉住。
     */
    @Test
    fun manifest的备份开关与两份规则都必须声明() {
        val manifest = TestSources.codeOf(
            sourceOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"),
        )
        // 锚在 `<application>` 元素内：这三个属性都挂在它上面，全文匹配会漏掉「值被改到别处」
        val app = TestSources.window(manifest, "<application", ">")
        val attrs = Regex("""\bandroid:([A-Za-z]+)\s*=\s*["']([^"']*)["']""")
            .findAll(app).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            "allowBackup=false 会让上面全部规则变成死配置（换机后一切归零且无提示）",
            "true",
            attrs["allowBackup"],
        )
        assertEquals(
            "缺 fullBackupContent（Android ≤11 的生效文件）",
            "@xml/backup_rules",
            attrs["fullBackupContent"],
        )
        assertEquals(
            "缺 dataExtractionRules —— API≥31 只解析它，删了会让结论整体失真",
            "@xml/data_extraction_rules",
            attrs["dataExtractionRules"],
        )
    }
}
