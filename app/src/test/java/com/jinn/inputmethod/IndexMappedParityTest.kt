package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 索引改成内存映射（librime `Prism : MappedFile` 那套）之后的三条护栏：
 * 映射和堆内读同一份字节必须结果一样；`collectLongerSuffixes` 和旧的「物化键再 startsWith」要一致；
 * 文件缺失/截断/magic 不符时 `ofMapped` 返回 null 而不是抛异常。
 */
class IndexMappedParityTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "jinn-idx-test")

    @After
    fun tearDown() {
        tmp.deleteRecursively()
        PinyinEngine.resetForTest()
    }

    private fun res(name: String): File {
        for (p in listOf("src/test/resources/$name", "app/src/test/resources/$name")) {
            val f = File(p)
            if (f.isFile) return f
        }
        throw AssertionError("未找到测试资源 $name")
    }

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("未找到 asset $name")
    }

    private fun fixtureBytes(): ByteArray =
        XZInputStream(res("dict_index_fixture.bin.xz").inputStream()).use { it.readBytes() }

    @Test
    fun 内存映射与堆内读取完全等价() {
        val bytes = fixtureBytes()
        tmp.mkdirs()
        val f = File(tmp, "fix.idx")
        f.writeBytes(bytes)

        val heap = PhraseIndex.of(bytes)
        val mapped = PhraseIndex.ofMapped(f)
        assertNotNull("映射读取失败（应能映射普通文件）", mapped)

        assertEquals(heap!!.size, mapped!!.size)
        assertEquals(heap.sourceStamp, mapped.sourceStamp)

        // 逐键比对：键文本 + 词表
        for (i in 0 until heap.size) {
            assertEquals("第 $i 个键文本不一致", heap.keyAt(i), mapped.keyAt(i))
        }
        var checked = 0
        for (i in 0 until heap.size) {
            val key = heap.keyAt(i) ?: continue
            assertArrayEquals("键 $key 的词表不一致", heap.wordsFor(key), mapped.wordsFor(key))
            checked++
        }
        assertTrue("应比对了足够多的键，实际 $checked", checked > 100)
    }

    @Test
    fun 区间枚举与后缀收集等价于物化键的旧写法() {
        val bytes = fixtureBytes()
        tmp.mkdirs()
        val f = File(tmp, "fix2.idx")
        f.writeBytes(bytes)
        val mapped = PhraseIndex.ofMapped(f)!!
        val heap = PhraseIndex.of(bytes)!!

        for (prefix in listOf("a", "ni", "nihao", "zh", "z")) {
            // 旧写法：物化键 → 对更长键的每个词做 startsWith
            for (lastWord in listOf("你好", "阿", "你")) {
                val expect = LinkedHashSet<String>()
                for (key in heap.keysWithPrefix(prefix)) {
                    if (key.length <= prefix.length) continue
                    heap.wordsFor(key)?.forEach { w ->
                        if (w.length > lastWord.length && w.startsWith(lastWord)) {
                            expect.add(w.substring(lastWord.length))
                        }
                    }
                }
                // 新写法：区间枚举 + 字节级后缀收集
                val got = LinkedHashSet<String>()
                val wb = lastWord.toByteArray(Charsets.UTF_8)
                val plen = prefix.toByteArray(Charsets.UTF_8).size
                mapped.forEachRangeWithPrefix(prefix) { keyLen, wf, wt ->
                    if (keyLen > plen) mapped.collectLongerSuffixes(wf, wt, wb, got)
                    true
                }
                assertEquals("前缀 $prefix / 词 $lastWord 的预测集合不一致", expect, got)
            }
        }
    }

    @Test
    fun 映射失败时返回null而不是抛异常() {
        tmp.mkdirs()
        assertNull("文件不存在应返回 null", PhraseIndex.ofMapped(File(tmp, "nope.idx")))
        val truncated = File(tmp, "trunc.idx")
        truncated.writeBytes(ByteArray(8))          // 短于头部
        assertNull("截断文件应返回 null", PhraseIndex.ofMapped(truncated))
        val garbage = File(tmp, "bad.idx")
        garbage.writeBytes(ByteArray(64) { 0x7F })  // magic 不符
        assertNull("magic 不符应返回 null", PhraseIndex.ofMapped(garbage))
    }
}
