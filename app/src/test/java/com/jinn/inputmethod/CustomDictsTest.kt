package com.jinn.inputmethod

import org.junit.After
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
}
