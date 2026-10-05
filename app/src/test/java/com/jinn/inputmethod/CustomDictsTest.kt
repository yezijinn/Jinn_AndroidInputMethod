package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

/**
 * 自定义词库（人写 `.txt` → 可选包 raw）的解析 / 校验 / 落盘守卫。
 *
 * 这一层是「用户内容进词库」的唯一入口：词条会直接变成候选词上屏，所以行级校验
 * （音节表、上限、去重）与落盘原子性都在这里钉住；备份导入侧的放行判据
 * （[CustomDicts.isValidPackFile]）复用同一套规则，两处不会各写一份。
 */
class CustomDictsTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "jinn-custom-" + System.nanoTime())

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private val syllables = setOf("zhang", "san", "ji", "qi", "xue", "xi", "lv", "ke", "si", "li")

    @Test
    fun `注释空行与缺拼音行须跳过并计数`() {
        val text = listOf(
            "# 公司人名补充",
            "",
            "张三\tzhang san",
            "只有词没有拼音",
            "机器学习\tji qi xue xi",
        ).joinToString("\n")
        val r = CustomDicts.parseHuman(text, syllables)
        assertEquals(listOf("张三", "机器学习"), r.entries.map { it.text })
        assertEquals(1, r.skipped)
        assertFalse(r.truncated)
    }

    @Test
    fun `制表符与两个空格都要能分列`() {
        val r = CustomDicts.parseHuman("张三\tzhang san\n李四  li si", syllables)
        assertEquals(listOf("张三", "李四"), r.entries.map { it.text })
        assertEquals(0, r.skipped)
    }

    @Test
    fun `大小写声调与 ü 须归一`() {
        val r = CustomDicts.parseHuman("张三\tZhang San3\n旅客\tlü ke", syllables)
        assertEquals("zhang san", r.entries[0].pinyin)
        assertEquals("lv ke", r.entries[1].pinyin)
    }

    @Test
    fun `拼音须逐节在音节表内`() {
        val r = CustomDicts.parseHuman("张三\tzhang zzz", syllables)
        assertEquals(0, r.entries.size)
        assertEquals("不在表内的音节整行跳过", 1, r.skipped)
        // 表读不出来时降级为形状校验，不让资产问题挡住导入
        assertEquals(1, CustomDicts.parseHuman("张三\tzhang zzz", null).entries.size)
    }

    @Test
    fun `同音同词须去重`() {
        val r = CustomDicts.parseHuman("张三\tzhang san\n张三\tzhang san", syllables)
        assertEquals(1, r.entries.size)
        assertEquals(1, r.skipped)
    }

    @Test
    fun `条数达到上限须截断`() {
        val text = buildString {
            repeat(CustomDicts.MAX_ENTRIES + 5) { append("w").append(it).append("\ti\n") }
        }
        val r = CustomDicts.parseHuman(text, null)
        assertEquals(CustomDicts.MAX_ENTRIES, r.entries.size)
        assertTrue("达到上限必须如实上报（页面提示词条过多）", r.truncated)
    }

    @Test
    fun `打包须键升序且按词表去重`() {
        val lines = CustomDicts.toRawPackLines(
            listOf(
                CustomDicts.Entry("张三", "zhang san"),
                CustomDicts.Entry("机器学习", "ji qi xue xi"),
                CustomDicts.Entry("张三", "zhang san"),
                CustomDicts.Entry("张四", "zhang san"),
            ),
        )
        assertEquals(listOf("jiqixuexi\t机器学习", "zhangsan\t张三|张四"), lines.lines)
        assertEquals("同键同词是去重、不是丢弃（L-864 的计数口径）", 0, lines.dropped)
    }

    @Test
    fun `单键词数须限长且丢弃数须回传`() {
        val entries = (1..(CustomDicts.MAX_WORDS_PER_KEY + 20)).map {
            CustomDicts.Entry("词$it", "a")
        }
        val pack = CustomDicts.toRawPackLines(entries)
        val line = pack.lines.single()
        assertEquals(
            "同键词表超 64KB 会让 PhraseIndex.build 拒收整包",
            CustomDicts.MAX_WORDS_PER_KEY,
            line.substringAfter('\t').split('|').size,
        )
        assertEquals(
            "被同键上限丢下的条数必须如实回传（BUG.md L-864：提示条数要与实际入包一致）",
            20,
            pack.dropped,
        )
    }

    @Test
    fun `写入进行中删除被拒且一个文件都不删`() {
        // L-865：删除与保存并发会留下「包在、源文本没了」这类半状态 —— 删除也走同一把写盘锁
        val dir = File(tmp, "dicts-del")
        CustomDicts.writePack(dir, listOf(CustomDicts.Entry("旧词", "jiu ci")))
        assertTrue("单测先占住写盘位，模拟保存进行中", CustomDicts.beginWrite())
        try {
            assertTrue("被占位时删除必须失败（而不是删掉一半）", !CustomDicts.deletePackIn(dir))
        } finally {
            CustomDicts.endWrite()
        }
        assertTrue("包必须还在（一个文件都没删）", File(dir, CustomDicts.PACK_NAME).isFile)
    }

    @Test
    fun `跳过回填时保存必须保持停用`() {
        // L-871：跳过回填后编辑器是空的，若保存可点，用户补几条再保存就会把整份大词表整体替换掉
        val src = TestSources.codeSource("CustomDictEditActivity.kt")
        val branch = src.substringAfter("!shouldRefill(text.length) ->").substringBefore("else ->")
        assertTrue("跳过回填分支必须置 refillSkipped", "refillSkipped = true" in branch)
        assertTrue("跳过回填分支必须停用保存", "saveButton.isEnabled = false" in branch)
        // 锚点必须落在 save() 方法体里（BUG.md L-874）：`saving = false` 在字段声明处先出现一次，
        // 直接从文件头 substringAfter 会把断言范围扩到整个文件 —— 守卫强度远低于预期
        val saveBody = src.substringAfter("private fun save()").substringBefore("\n    private fun ")
        assertTrue("save() 方法体锚点失效（源码结构变了）", saveBody.isNotEmpty() && saveBody.length < src.length)
        val restore = saveBody.substringAfter("saving = false")
        assertTrue(
            "保存结束后的恢复必须判 refillSkipped（否则会把整体替换的风险放回来）",
            "if (!refillSkipped)" in restore,
        )
    }

    @Test
    fun `raw 校验须拒绝乱序空词与大写键`() {
        assertTrue(CustomDicts.validateRawPack("zhangsan\t张三\n"))
        assertFalse("键必须严格升序", CustomDicts.validateRawPack("zhangsan\t张三\njiqi\t机器\n"))
        assertFalse("空词会在引擎侧被丢弃，先挡在门外", CustomDicts.validateRawPack("zhangsan\t张三||李四\n"))
        assertFalse("键是小写字母连写", CustomDicts.validateRawPack("ZhangSan\t张三\n"))
        assertFalse("键长须与 PhraseIndex.build 的 255B 闸同源", CustomDicts.validateRawPack("a".repeat(256) + "\t词\n"))
        assertTrue("恰好 255 字符合法", CustomDicts.validateRawPack("a".repeat(255) + "\t词\n"))
    }

    @Test
    fun `超长键与竖线符控制字符须在解析期跳过`() {
        // 键长上限与 PhraseIndex.build 的 255B 硬闸同源（BUG.md L-828）：超限会让索引构建抛错、整包转回退
        val ok = (1..200).joinToString(" ") { "a" }
        assertTrue("200 个音节（键 200 字符）仍应合法", CustomDicts.parseHuman("词\t$ok", null).entries.isNotEmpty())
        val tooLong = (1..300).joinToString(" ") { "a" }
        val r = CustomDicts.parseHuman("词\t$tooLong\n甲\tjia\n乙|丙\tjia\n丙\u0000丁\tjia", null)
        assertEquals(listOf("甲"), r.entries.map { it.text })
        assertEquals("超长键 / 竖线符词 / 控制字符词各跳过一行（BUG.md L-829）", 3, r.skipped)
    }

    @Test
    fun `读上限须拒绝超长并剥 BOM`() {
        assertNull(CustomDicts.readCapped(StringReader("a".repeat(100)), 99))
        assertEquals(
            "Windows 记事本默认带 BOM，不剥会让首行变成注释/缺词",
            "张三\tzhang san",
            CustomDicts.readCapped(StringReader("\uFEFF张三\tzhang san"), 1024),
        )
    }

    @Test
    fun `落盘包须通过结构校验而垃圾字节不许通过`() {
        tmp.mkdirs()
        val good = File(tmp, CustomDicts.PACK_NAME).apply {
            writeBytes(CustomDicts.encodePack(listOf(CustomDicts.Entry("张三", "zhang san"))))
        }
        assertTrue("本应用产出的包必须被备份导入侧认下", CustomDicts.isValidPackFile(good))
        val bad = File(tmp, "bad.txt.xz").apply { writeBytes(ByteArray(64) { (it * 7).toByte() }) }
        assertFalse("解不开或不符 raw 形态的内容绝不能落盘（引擎会全量解压它）", CustomDicts.isValidPackFile(bad))
    }

    @Test
    fun `自定义包不得被列成其他包`() {
        assertEquals(
            "自定义词库有自己的卡片，列进「其他包」等于把自己的词库显示成未知包",
            emptyList<String>(),
            OptionalDicts.unknownPackages(listOf(CustomDicts.PACK_NAME)),
        )
    }

    @Test
    fun `源文本须原子落盘且可读回`() {
        val dir = File(tmp, "dicts")
        val text = "# 草稿\n张三\tzhang san\n半成品没有拼音\n"
        val f = CustomDicts.writeSource(dir, text)
        assertEquals(CustomDicts.SOURCE_NAME, f?.name)
        assertEquals(
            "「快捷补充」页下次打开要能原样回显（导入与保存共用这一份）",
            text,
            CustomDicts.readSource(File(dir, CustomDicts.SOURCE_NAME)),
        )
        assertEquals(
            "临时件改名后不许留残件（残留会被词库页的清理器当垃圾扫）",
            listOf(CustomDicts.SOURCE_NAME),
            dir.listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun `源文本不存在时读回空串`() {
        assertEquals(
            "没有源文本时返回空串（编辑页据此留空，不是「读失败」）",
            "",
            CustomDicts.readSource(File(tmp, CustomDicts.SOURCE_NAME)),
        )
    }

    @Test
    fun `源文本校验须放行草稿并拒绝超限`() {
        tmp.mkdirs()
        val draft = File(tmp, CustomDicts.SOURCE_NAME).apply { writeText("张三\tzhang san\n半成品") }
        assertTrue("草稿允许有不合法的行（编辑页随时保存，不要求全文可导入）", CustomDicts.isValidSourceFile(draft))
        val huge = File(tmp, "huge.src.txt").apply {
            outputStream().use { out ->
                val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                repeat(CustomDicts.MAX_INPUT_CHARS / chunk.size + 1) { out.write(chunk) }
            }
        }
        assertFalse("超过 8MB 上限的源文本不许随备份落盘", CustomDicts.isValidSourceFile(huge))
    }

    @Test
    fun `单空格分隔的词与拼音须能分列`() {
        // 手机上打不出 TAB（2026-10-05 用户实测）：`黄霄雲 huang xiao yun` 必须能直接保存
        val r = CustomDicts.parseHuman("黄霄雲 huang xiao yun\n张三 zhang san3\n机器学习  ji qi  xue xi", null)
        assertEquals(listOf("黄霄雲", "张三", "机器学习"), r.entries.map { it.text })
        assertEquals(listOf("huang xiao yun", "zhang san", "ji qi xue xi"), r.entries.map { it.pinyin })
        assertEquals(0, r.skipped)
    }

    @Test
    fun `全角空格与不换行空格也要能分列`() {
        // 从微信 / 网页 / WPS 粘来的词条常用全角空格当分隔符（BUG.md L-845）
        val r = CustomDicts.parseHuman("张三\u3000zhang san\n李四\u00A0li si", null)
        assertEquals(listOf("张三", "李四"), r.entries.map { it.text })
        assertEquals(listOf("zhang san", "li si"), r.entries.map { it.pinyin })
        assertEquals(0, r.skipped)
    }

    @Test
    fun `导入后源文本须归一为标准格式`() {
        // 用户在手机上什么样的分隔符都可能打出来：多空格 / TAB / 全角空格 / NBSP / 声调数字（2026-10-05 要求）
        val text = "# 注释保留\n张三\t\tzhang   san\n李四\u3000li\u00A0si\n无效行没有拼音\n" +
            "黄霄雲\u3000huang xiao yun3\n带备注\tpi yin\t插入位置\n"
        val formatted = CustomDicts.formatHuman(text, null)
        assertEquals(
            listOf(
                "# 注释保留",
                "张三 zhang san",
                "李四 li si",
                "无效行没有拼音",
                "黄霄雲 huang xiao yun",
                "带备注\tpi yin\t插入位置",
            ),
            formatted.trimEnd('\n').split('\n'),
        )
        assertEquals("格式化须幂等（再跑一次结果不变）", formatted, CustomDicts.formatHuman(formatted, null))
        assertTrue("原文以换行结尾时须补回", formatted.endsWith("\n"))
        assertEquals(
            "格式化后语义不变：仍是同四条词条",
            listOf("张三", "李四", "黄霄雲", "带备注"),
            CustomDicts.parseHuman(formatted, null).entries.map { it.text },
        )
    }

    @Test
    fun `源文本超过上限须拒绝写入`() {
        // 与 readSource / isValidSourceFile 同一口径：写进去的必须能读回来（BUG.md L-846）
        val dir = File(tmp, "dicts-overflow")
        val huge = "a".repeat(CustomDicts.MAX_INPUT_CHARS + 1)
        assertNull("超 8MB 的源文本不许落盘", CustomDicts.writeSource(dir, huge))
        assertFalse("被拒绝时不许留下临时件", File(dir, CustomDicts.SOURCE_NAME + ".tmp").exists())
        assertTrue(
            "恰好等于上限仍应放行",
            CustomDicts.writeSource(dir, "a".repeat(CustomDicts.MAX_INPUT_CHARS)) != null,
        )
    }

    @Test
    fun `土耳其语 Locale 下大写拼音仍须归一`() {
        // 裸 lowercase() 在 tr-TR 下把 "NI" 折成 "nı"（无点 i）⇒ 整行被跳过（BUG.md L-847）
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val r = CustomDicts.parseHuman("张三 NI HAO", null)
            assertEquals("tr-TR 下大写拼音仍须解析", listOf("张三"), r.entries.map { it.text })
            assertEquals("ni hao", r.entries[0].pinyin)
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    @Test
    fun `配置恢复进行中必须挡住词库写入口`() {
        // 源码对拍（BUG.md L-848）：定义 1 处 + 两个入口各 1 处调用
        val mgr = TestSources.codeSource("DictManagerActivity.kt")
        assertEquals(
            "词库页两个入口都要判 ConfigBackupManager.importing（定义 1 + 调用 2）",
            3,
            Regex("blockedByConfigImport\\(\\)").findAll(mgr).count(),
        )
        val edit = TestSources.codeSource("CustomDictEditActivity.kt")
        assertTrue(
            "快捷补充保存也要判 ConfigBackupManager.importing",
            edit.contains("ConfigBackupManager.importing"),
        )
    }

    @Test
    fun `快捷补充回填须避开用户已输入的内容`() {
        // 源码对拍（BUG.md L-849）：回填前必须判编辑框是否已被改动
        val edit = TestSources.codeSource("CustomDictEditActivity.kt")
        val body = edit.substringAfter("private fun loadSource(")
            .substringBefore("private fun save(")
        assertTrue("loadSource 回填前要判 editor.text.isNotEmpty()", body.contains("editor.text.isNotEmpty()"))
    }

    @Test
    fun `纯拼音行与含空格的词须跳过`() {
        assertEquals(
            "整行都是拼音 ⇒ 没有词，不猜（避免误粘一行拼音变成垃圾词条）",
            0,
            CustomDicts.parseHuman("huang xiao yun", null).entries.size,
        )
        assertEquals(
            "词含空格（拼音段前面还剩多段）⇒ 跳过；这类词请用 TAB 形态写",
            0,
            CustomDicts.parseHuman("黄 霄雲 huang xiao yun", null).entries.size,
        )
        assertEquals(
            "TAB 形态下即使词是纯拼音形状（如英文缩写）也照收",
            1,
            CustomDicts.parseHuman("CPU\tcpu", null).entries.size,
        )
    }

    // ── 保存流程的两条不变量（BUG.md L-851 / L-856）────────────────

    @Test
    fun `截断超限时一个字都不许落盘`() {
        // L-851：此前两处都是「先 writePack 再 when 判闸」——
        // 界面报「词条超过 5 万条」失败，磁盘上却已被替换成截断版（前 5 万条）
        val dir = File(tmp, "dicts-truncated")
        val old = CustomDicts.writePack(dir, listOf(CustomDicts.Entry("旧词", "jiu ci")))
        val oldBytes = old!!.readBytes()
        val text = buildString {
            repeat(CustomDicts.MAX_ENTRIES + 5) { append("w").append(it).append("\ti\n") }
        }
        val report = CustomDicts.saveHuman(dir, text, null)
        assertEquals(CustomDicts.SaveGate.TOO_MANY, report.gate)
        assertArrayEquals(
            "报失败时磁盘上的包必须原封不动：写进截断版等于静默丢词条",
            oldBytes,
            File(dir, CustomDicts.PACK_NAME).readBytes(),
        )
        assertFalse("失败路径连源文本也不该写", File(dir, CustomDicts.SOURCE_NAME).exists())
    }

    @Test
    fun `没有合法词条时不许落盘`() {
        // 清空词库要用卡片上的「删除」，不能让一次误编辑把已有内容清掉
        val dir = File(tmp, "dicts-empty")
        CustomDicts.writeSource(dir, "旧内容\n")
        val report = CustomDicts.saveHuman(dir, "只有词没有拼音\n# 注释\n", null)
        assertEquals(CustomDicts.SaveGate.NO_VALID, report.gate)
        assertFalse("没有合法词条就不许动包", File(dir, CustomDicts.PACK_NAME).exists())
        assertEquals(
            "源文本也不许被这次保存改写",
            "旧内容\n",
            CustomDicts.readSource(File(dir, CustomDicts.SOURCE_NAME)),
        )
    }

    @Test
    fun `源文本写不进去时不许写包`() {
        // L-856：真相（源文本）先落、派生（包）后落 —— 源文本失败即中止，避免「包新文本旧」
        // （用户在原内容上再保存，等于用旧文本把新包静默回滚）
        val dir = File(tmp, "dicts-srclock")
        dir.mkdirs()
        File(dir, CustomDicts.SOURCE_NAME).mkdirs() // 同名目录占位 ⇒ writeSource 的改名必失败
        val report = CustomDicts.saveHuman(dir, "张三 zhang san\n", null)
        assertEquals(CustomDicts.SaveGate.WRITE_FAIL_SOURCE, report.gate)
        assertFalse("源文本没落盘时包也不许落盘", File(dir, CustomDicts.PACK_NAME).exists())
    }

    @Test
    fun `保存成功时源文本归一且包可装载`() {
        val dir = File(tmp, "dicts-ok")
        val report = CustomDicts.saveHuman(dir, "张三\t\tzhang   san3\n# 注释\n", null)
        assertEquals(CustomDicts.SaveGate.OK, report.gate)
        assertEquals(1, report.entries)
        assertEquals(
            "源文本落的是归一后的标准格式（多空格 / TAB / 声调数字都收敛）",
            "张三 zhang san\n# 注释\n",
            CustomDicts.readSource(File(dir, CustomDicts.SOURCE_NAME)),
        )
        assertTrue(
            "包必须是引擎与备份导入侧认得的 raw 形态",
            CustomDicts.isValidPackFile(File(dir, CustomDicts.PACK_NAME)),
        )
    }

    @Test
    fun `词库写盘只许走 saveHuman 一个入口`() {
        // L-851 / L-856 的根因是「两个 UI 各写一份流程」—— 谁都会漏掉一条不变量。
        // 现在两处都只调 saveHuman；writePack / writeSource 不得再被 UI 直接调用
        for (name in listOf("DictManagerActivity.kt", "CustomDictEditActivity.kt")) {
            val src = TestSources.codeSource(name)
            assertTrue("$name 必须走 CustomDicts.saveHuman", "CustomDicts.saveHuman(" in src)
            assertTrue(
                "$name 不得直接调 writePack：先判闸后写盘的不变量在 saveHuman 里",
                "CustomDicts.writePack(" !in src,
            )
            assertTrue(
                "$name 不得直接调 writeSource：源文本先落的不变量在 saveHuman 里",
                "CustomDicts.writeSource(" !in src,
            )
        }
    }

    @Test
    fun `超上限的文本不许落盘`() {
        // 数据闸按**格式化后**的文本判（L-852）：直接给超限输入，走 TOO_BIG 且一个字不落盘
        val dir = File(tmp, "dicts-toobig")
        val report = CustomDicts.saveHuman(dir, "a".repeat(CustomDicts.MAX_INPUT_CHARS + 1), null)
        assertEquals(CustomDicts.SaveGate.TOO_BIG, report.gate)
        assertFalse(File(dir, CustomDicts.PACK_NAME).exists())
        assertFalse(File(dir, CustomDicts.SOURCE_NAME).exists())
    }

    @Test
    fun `写入进行中第二次保存被拒且不落盘`() {
        // L-859：两处 UI 的防连点都是实例级，跨页面并发挡不住 —— 两条路写同一个固定临时名
        // （同一 inode，默认截断模式），交错写会把包或源文本写坏；互斥建成进程级后第二次直接 BUSY
        val dir = File(tmp, "dicts-busy")
        assertTrue("单测先占住写盘位，模拟另一次写入在跑", CustomDicts.beginWrite())
        try {
            val report = CustomDicts.saveHuman(dir, "张三 zhang san\n", null)
            assertEquals(CustomDicts.SaveGate.BUSY, report.gate)
            assertFalse("被拒时一个字都不许写", File(dir, CustomDicts.PACK_NAME).exists())
            assertFalse(File(dir, CustomDicts.SOURCE_NAME).exists())
        } finally {
            CustomDicts.endWrite()
        }
        assertEquals(
            "释放后必须能正常保存（互斥不许把自己锁死）",
            CustomDicts.SaveGate.OK,
            CustomDicts.saveHuman(dir, "张三 zhang san\n", null).gate,
        )
    }

    @Test
    fun `包写不进去时源文本已落且闸位与源文本失败分开`() {
        // L-861：包失败 = 文本已落、只差词库（提示「内容已保留」）；源文本失败 = 磁盘没变
        val dir = File(tmp, "dicts-packlock")
        dir.mkdirs()
        File(dir, CustomDicts.PACK_NAME).mkdirs() // 同名目录占位 ⇒ writePack 的改名必失败
        val report = CustomDicts.saveHuman(dir, "张三 zhang san\n", null)
        assertEquals(CustomDicts.SaveGate.WRITE_FAIL_PACK, report.gate)
        assertEquals(
            "包失败时源文本必须已经落成新内容（这正是两档文案不同的依据）",
            "张三 zhang san\n",
            CustomDicts.readSource(File(dir, CustomDicts.SOURCE_NAME)),
        )
    }

    @Test
    fun `恰好达到上限不算截断而多一条才算`() {
        // L-863：旧写法把配额判定放在循环头，恰好 5 万条（后面只剩注释 / 空行）也判成截断 ——
        // 提示「超过 5 万条」且一个字不落盘，用户正好 5 万条的词库永远存不进去
        val exact = buildString {
            repeat(CustomDicts.MAX_ENTRIES) { append("w").append(it).append("\ti\n") }
        }
        val r = CustomDicts.parseHuman(exact, null)
        assertEquals(CustomDicts.MAX_ENTRIES, r.entries.size)
        assertFalse("恰好 5 万条 + 后面无词条，不许判成截断", r.truncated)
        assertFalse("后面只剩注释 / 空行同样不算", CustomDicts.parseHuman(exact + "# 注释\n\n", null).truncated)
        assertTrue("真超一条就必须如实上报", CustomDicts.parseHuman(exact + "w50000\ti\n", null).truncated)
    }

    @Test
    fun `编辑页保存中必须冻结编辑器且旋转不重建`() {
        // L-866：保存用的是点击那一刻的快照，等待里补敲的字不会进词库、而完成后页面直接 finish ⇒ 静默丢失。
        // L-867：编辑器是代码创建且无 id，重建会让未保存的编辑消失（真机实证）；旋转必须走 configChanges 免重建。
        val src = TestSources.codeSource("CustomDictEditActivity.kt")
        assertTrue("保存中必须冻结编辑器（L-866）", "editor.isEnabled = false" in src)
        assertTrue("保存结束后必须恢复可编辑", "editor.isEnabled = true" in src)
        val manifest = File("src/main/AndroidManifest.xml")
            .let { if (it.isFile) it else File("app/src/main/AndroidManifest.xml") }
            .readText()
        val block = manifest.substringAfter("android:name=\".CustomDictEditActivity\"")
            .substringBefore("/>")
        assertTrue(
            "编辑页必须声明 configChanges（L-867），否则旋转重建丢草稿",
            "android:configChanges=" in block,
        )
        val declared = block.split("android:configChanges=\"")[1].substringBefore("\"")
        assertTrue("configChanges 必须覆盖 orientation（否则竖横屏仍重建），实际：$declared", "orientation" in declared)
        assertTrue("configChanges 必须覆盖 screenSize（否则分屏/尺寸变化仍重建），实际：$declared", "screenSize" in declared)
    }

    @Test
    fun `回填阈值须恰好放行上限并拒绝多一字符`() {
        // L-870：`EditText.setText` 在主线程同步排版，8M 字符实测卡 18 秒（Skipped 1083 frames）——
        // 回填有独立体验阈值，边界要钉住（等于阈值放行、多一字符拒绝）
        assertTrue(
            "恰好等于阈值仍应回填",
            CustomDictEditActivity.shouldRefill(CustomDictEditActivity.MAX_REFILL_CHARS),
        )
        assertFalse(
            "超过阈值不许回填（否则主线程停顿秒级）",
            CustomDictEditActivity.shouldRefill(CustomDictEditActivity.MAX_REFILL_CHARS + 1),
        )
        assertTrue("空文本当然回填", CustomDictEditActivity.shouldRefill(0))
    }

    @Test
    fun `编辑页保存中关闭与返回键都被拦`() {
        // L-860 源码对拍：✕ 置灰与 onBackPressed 早退缺一不可 ——
        // 少任何一个，保存中的 finish() 都会让写盘结果回执丢失（包已更新却不重启引擎）
        val src = TestSources.codeSource("CustomDictEditActivity.kt")
        assertTrue("保存中必须禁用关闭按钮", "closeButton.isEnabled = false" in src)
        val back = src.substringAfter("override fun onBackPressed()")
            .substringBefore("super.onBackPressed()")
        assertTrue("返回键必须在 saving 时早退", "if (saving)" in back)
        assertTrue("返回键拦截要给出提示（否则用户以为点了没反应）", "TEXT_SAVING_WAIT" in back)
    }

    @Test
    fun `保存走逐行扫描时与格式化后解析逐项一致`() {
        // L-858 / L-862：保存路径改为「逐行格式化 + 逐行解析 + 逐行落盘」，不再为全文造副本。
        // 判定与落盘文本对拍到旧写法（formatHuman 再 parseHuman）—— 换实现不许换语义
        val cases = listOf(
            "张三 zhang san\n李四  li si",
            "张三\tzhang san3\n旅客\tlü ke\n",
            "# 注释\nCPU\tcpu\n张三\tzhang san\n",
            "黄霄雲 huang xiao yun\n张三 zhang san\n机器学习  ji qi  xue xi\n",
            "甲\tjia\t备注列\n乙 jia\n",
            "张三\u3000zhang san\n李四\u00A0li si\n",
            "",
            "\n",
            "a\n\n",
            "词  ci\n\n# 尾注释",
        )
        for ((i, text) in cases.withIndex()) {
            val dir = File(tmp, "dicts-stream-$i")
            val report = CustomDicts.saveHuman(dir, text, null)
            val formatted = CustomDicts.formatHuman(text, null)
            val parsed = CustomDicts.parseHuman(formatted, null)
            val src = File(dir, CustomDicts.SOURCE_NAME)
            if (parsed.entries.isEmpty()) {
                assertEquals("该判 NO_VALID：<$text>", CustomDicts.SaveGate.NO_VALID, report.gate)
                assertFalse("判空时不许落盘：<$text>", src.exists())
            } else {
                assertEquals("闸位：<$text>", CustomDicts.SaveGate.OK, report.gate)
                assertEquals("条数：<$text>", parsed.entries.size, report.entries)
                assertEquals("跳过行数：<$text>", parsed.skipped, report.skipped)
                assertEquals(
                    "源文本必须与 formatHuman 逐字符一致：<$text>",
                    formatted,
                    CustomDicts.readSource(src),
                )
            }
        }
    }

    @Test
    fun `原文超上限而格式化后不超限的内容可以保存`() {
        // L-852 的口径：数据闸按**格式化后**的文本判。极端「多空格 → 单空格」输入里原文可以远超
        // 8M 字符、格式化后只剩几十万 —— 逐行扫描按格式化后统计，不该误拒，也不需要先造一份大副本
        val text = buildString {
            repeat(20_000) { append("词").append(it).append(" ".repeat(520)).append("ci\n") }
        }
        assertTrue("测试数据本身要超过原文上限", text.length > CustomDicts.MAX_INPUT_CHARS)
        val dir = File(tmp, "dicts-raw-big")
        val report = CustomDicts.saveHuman(dir, text, null)
        assertEquals(CustomDicts.SaveGate.OK, report.gate)
        assertEquals(20_000, report.entries)
        val src = CustomDicts.readSource(File(dir, CustomDicts.SOURCE_NAME))!!
        assertTrue("落盘的是格式化后的短文本", src.length < CustomDicts.MAX_INPUT_CHARS)
        assertEquals("行数不变", 20_000, src.count { it == '\n' })
        assertTrue("首行已归一：<${src.take(20)}>", src.startsWith("词0 ci\n"))
        assertTrue("末行已归一", src.endsWith("词19999 ci\n"))
    }

    @Test
    fun `格式化结果的末尾补行规则要有确定值`() {
        // 期望值对照旧实现逐个推过（拼接 + 「原文以换行结尾且拼接结果尚未以换行结尾时补一个」）：
        // lineSequence 保留末尾空行，所以原文以换行结尾时拼接结果本来就带换行，补行分支构造不出来。
        // 这条规则曾在三处各写一遍，收口后靠这组期望值防回归
        val cases = mapOf(
            "" to "",
            "\n" to "\n",
            "a" to "a",
            "a\n" to "a\n",
            "a\n\n" to "a\n\n",
            "\n\n" to "\n\n",
            "a\nb" to "a\nb",
            "a\nb\n\n" to "a\nb\n\n",
            "张三\tzhang  san3\n" to "张三 zhang san\n",
            "张三 zhang san" to "张三 zhang san",
        )
        for ((input, want) in cases) {
            assertEquals("formatHuman(<$input>)", want, CustomDicts.formatHuman(input, null))
        }
    }

    @Test
    fun `闸位命中时不许留下临时件`() {
        // 单遍写：源文本先在临时名上写好，命中数据闸要把它删掉 —— 留着半截临时件既脏了目录，
        // 也会让「目录里还剩什么」这类判断失真（残留清理器要等保护窗过去才收）
        val dir = File(tmp, "dicts-stage-clean").apply { mkdirs() }
        val tooMany = buildString {
            repeat(CustomDicts.MAX_ENTRIES + 1) { append("w").append(it).append("\ti\n") }
        }
        assertEquals(CustomDicts.SaveGate.TOO_MANY, CustomDicts.saveHuman(dir, tooMany, null).gate)
        assertTrue(
            "超限被拒后目录要干净：<${dir.listFiles().orEmpty().joinToString { it.name }}>",
            dir.listFiles().orEmpty().isEmpty(),
        )
        assertEquals(CustomDicts.SaveGate.NO_VALID, CustomDicts.saveHuman(dir, "只有词\n", null).gate)
        assertTrue(
            "无合法词条时同样要干净：<${dir.listFiles().orEmpty().joinToString { it.name }}>",
            dir.listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun `自定义词库改过后要跳过空闲等待`() {
        // 可选包默认只在息屏 / 收起键盘 / 3 分钟兜底时才装载；自定义词库是刚写完的小包，
        // 跟着官方大包一起等，用户保存后马上打字就是「没有候选」。判据：IME 启动路径发现
        // 自定义包索引未就绪时，必须立即触发一次装载
        val src = TestSources.codeSource("JinnIme.kt")
        val i = src.indexOf("isOptionalIndexReady")
        assertTrue("IME 启动路径要查自定义包的索引就绪状态", i > 0)
        val around = src.substring(maxOf(0, i - 400), minOf(src.length, i + 400))
        assertTrue("就绪检查要针对自定义包", "CustomDicts.PACK_NAME" in around)
        assertTrue("未就绪时要立即触发装载", "maybeLoadOptionalDict" in around)
    }

    @Test
    fun `保存路径不许再构造整份格式化副本`() {
        // L-858 / L-862 的守卫：formatHuman 返回整份副本，保存路径一旦回头用它，内存峰值立刻翻倍
        val src = TestSources.codeSource("CustomDicts.kt")
        val body = src.substringAfter("fun saveHuman(").substringBefore("备份导入侧的源文本放行判据")
        assertTrue("saveHuman 方法体锚点失效（源码结构变了）", body.isNotEmpty() && body.length < src.length)
        assertFalse("saveHuman 不得调用 formatHuman（会为全文再造一份副本）", "formatHuman(" in body)
        assertTrue("源文本必须逐行写出", "scanAndWriteSource(" in body)
    }

    @Test
    fun `删除顺序：包删不掉时草稿必须留着`() {
        // 包是词库本体，源文本是用户写过的草稿。包删失败时若草稿已经删掉，编辑页从此是空的，
        // 写过的内容无处可取；反过来留下草稿，再点一次保存就能把包重建出来
        val dir = File(tmp, "dicts-delorder").apply { mkdirs() }
        CustomDicts.writeSource(dir, "张三 zhang san\n")
        File(dir, CustomDicts.PACK_NAME).mkdirs()
        File(File(dir, CustomDicts.PACK_NAME), "x").writeText("x") // 非空目录：delete() 必失败
        assertFalse("包没删掉就不算删除成功", CustomDicts.deletePackIn(dir))
        assertTrue("包还在时草稿必须保留", File(dir, CustomDicts.SOURCE_NAME).isFile)
    }

    @Test
    fun `删除成功时包与源文本一起消失`() {
        val dir = File(tmp, "dicts-delok").apply { mkdirs() }
        CustomDicts.writePack(dir, listOf(CustomDicts.Entry("张三", "zhang san")))
        CustomDicts.writeSource(dir, "张三 zhang san\n")
        assertTrue(CustomDicts.deletePackIn(dir))
        assertFalse(File(dir, CustomDicts.PACK_NAME).exists())
        assertFalse(File(dir, CustomDicts.SOURCE_NAME).exists())
    }

    @Test
    fun `保存路径的统计与整份格式化同源`() {
        // 这条只防「两侧各写一套规则」：判闸用的字数与落盘用的文本都出自 forEachFormattedLine。
        // 规则本身改错时两边会一起错，所以另有 `格式化结果的末尾补行规则要有确定值` 用期望值钉住
        val cases = listOf("", "\n", "a", "a\n", "a\n\n", "\n\n", "词  ci\n\n# 尾注释", "张三 zhang san\n李四 li si")
        for ((i, text) in cases.withIndex()) {
            val dir = File(tmp, "dicts-parity-$i")
            val pass = CustomDicts.scanAndWriteSource(dir, text, null)
            val formatted = CustomDicts.formatHuman(text, null)
            assertEquals("长度口径：<$text>", formatted.length.toLong(), pass.scan.formattedChars)
            assertEquals(
                "条数口径：<$text>",
                CustomDicts.parseHuman(formatted, null).entries.size,
                pass.scan.result.entries.size,
            )
        }
    }

    @Test
    fun `自定义卡片三入口必须等宽`() {
        // 固定宽度的按钮行在字体放大或窄屏上会把「删除」挤出可用区域（折行、被裁到屏幕外）。
        // 真机复现成本高，这条用源码对拍兜住：三个入口都得走 weightedButtonLp
        val src = TestSources.codeSource("DictManagerActivity.kt")
        val card = src.substringAfter("private fun buildCustomCard()").substringBefore("private fun customResultText(")
        assertTrue("自定义卡片锚点失效（源码结构变了）", card.isNotEmpty() && card.length < src.length)
        assertEquals("三个入口都要等宽", 3, Regex("weightedButtonLp\\(").findAll(card).count())
        assertFalse(
            "不许回退到固定宽度",
            "buttonLp(" in card.replace("weightedButtonLp(", ""),
        )
    }
}
