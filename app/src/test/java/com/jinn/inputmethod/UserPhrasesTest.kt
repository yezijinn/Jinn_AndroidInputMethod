package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** 自造词组表（P0）：只测纯 JVM 的存取、开关与闸门；加密密钥由测试注入。 */
class UserPhrasesTest {

    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    private fun file(): File {
        val d = java.nio.file.Files.createTempDirectory("phrases").toFile()
        return File(d, UserPhrases.FILE_NAME)
    }

    @Test
    fun 自造词组写入后可读回() {
        val f = file()
        UserPhrases.setKeyForTest(key)
        UserPhrases.loadFromFile(f, enabled = true)
        UserPhrases.offer("风曳", "fengye")
        assertEquals(listOf("风曳"), UserPhrases.forPinyin("fengye"))
        // 重新从盘上装载：模拟进程重启
        UserPhrases.loadFromFile(f, enabled = true)
        assertEquals(listOf("风曳"), UserPhrases.forPinyin("fengye"))
        UserPhrases.setKeyForTest(null)
    }

    @Test
    fun 关闭时不学习也不注入() {
        val f = file()
        UserPhrases.setKeyForTest(key)
        UserPhrases.loadFromFile(f, enabled = false)
        UserPhrases.offer("风曳", "fengye")
        assertTrue(UserPhrases.forPinyin("fengye").isEmpty())
        UserPhrases.setKeyForTest(null)
    }

    @Test
    fun 超长词与超长键不入表() {
        val f = file()
        UserPhrases.setKeyForTest(key)
        UserPhrases.loadFromFile(f, enabled = true)
        UserPhrases.offer("一二三四五六七八九", "yi")
        UserPhrases.offer("风曳", "a".repeat(33))
        assertTrue(UserPhrases.forPinyin("yi").isEmpty())
        assertTrue(UserPhrases.forPinyin("a".repeat(33)).isEmpty())
        UserPhrases.setKeyForTest(null)
    }

    @Test
    fun 词组与单字同组时不重复学习条目() {
        val f = file()
        UserPhrases.setKeyForTest(key)
        UserPhrases.loadFromFile(f, enabled = true)
        UserPhrases.offer("你好", "nihao")
        UserPhrases.offer("你好", "nihao")
        assertEquals("同词同键只一条", 1, UserPhrases.sizeForTest())
        UserPhrases.setKeyForTest(null)
    }

    @Test
    fun 同键最多注入两条权重最高的() {
        val f = file()
        UserPhrases.setKeyForTest(key)
        UserPhrases.loadFromFile(f, enabled = true)
        UserPhrases.offer("甲一", "jiayi")
        UserPhrases.offer("乙一", "jiayi")
        UserPhrases.offer("乙一", "jiayi")
        UserPhrases.offer("丙一", "jiayi")
        UserPhrases.offer("丙一", "jiayi")
        UserPhrases.offer("丙一", "jiayi")
        val got = UserPhrases.forPinyin("jiayi")
        assertEquals("同键只返两条", 2, got.size)
        assertEquals("权重最高两条按降序", listOf("丙一", "乙一"), got)
        UserPhrases.setKeyForTest(null)
    }

    @Test
    fun 查询侧按前缀注入且成词即时入库() {
        val engine = TestSources.codeSource("PinyinEngine.kt")
        assertTrue("查询要查自造词表", "UserPhrases.forPinyin(key)" in engine)
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        assertTrue("成词要即时入库", "UserPhrases.offer(" in view)
        assertTrue("段键取当前串从头消费的输入音节", "full.substring(0, n)" in view)
        assertTrue("段键字段必须已删除（绝对偏移会越界误清链）", !("phraseConsumed" in view))
        assertTrue("默认开关要接 Prefs", "userPhraseEnabled" in view)
        // 简繁归一落点：查询注入处先 toSimplified 再 displayTake（存量繁体词同修）
        assertTrue(
            "注入处必须先归一简体",
            "displayTake(listOf(toSimplified(p)), 1)" in engine,
        )
    }

    @Test
    fun 妮皓自造词组往返() {
        val f = file()
        UserPhrases.setKeyForTest(key)
        UserPhrases.loadFromFile(f, enabled = true)
        UserPhrases.offer("妮皓", "nihao")
        assertEquals(listOf("妮皓"), UserPhrases.forPinyin("nihao"))
        UserPhrases.loadFromFile(f, enabled = true)
        assertEquals("进程重启后仍在", listOf("妮皓"), UserPhrases.forPinyin("nihao"))
        UserPhrases.setKeyForTest(null)
    }
}
