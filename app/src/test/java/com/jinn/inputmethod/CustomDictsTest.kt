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
        assertEquals(listOf("jiqixuexi\t机器学习", "zhangsan\t张三|张四"), lines)
    }

    @Test
    fun `单键词数须限长`() {
        val entries = (1..(CustomDicts.MAX_WORDS_PER_KEY + 20)).map {
            CustomDicts.Entry("词$it", "a")
        }
        val line = CustomDicts.toRawPackLines(entries).single()
        assertEquals(
            "同键词表超 64KB 会让 PhraseIndex.build 拒收整包",
            CustomDicts.MAX_WORDS_PER_KEY,
            line.substringAfter('\t').split('|').size,
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
        assertEquals(CustomDicts.SaveGate.WRITE_FAIL, report.gate)
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
}
