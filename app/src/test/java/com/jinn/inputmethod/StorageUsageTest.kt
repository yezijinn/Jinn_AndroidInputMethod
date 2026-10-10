package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [StorageUsage] 的纯逻辑护栏（2026-10-10）。
 *
 * 这里钉的是算法本身：目录递归、**逐文件展开**（相对路径名 + 字节，子目录带层级前缀）、
 * 单文件、**已认领去重**（「其他」兜底项不许把已单列的路径再算一遍 —— 那会让总账虚高）、
 * 报表与成分内条目的排序、空项过滤。
 * 真机上「哪些目录算哪一类」靠人工核对（目录常量全部引用各自 owner 的定义，不另抄字面量）。
 */
class StorageUsageTest {

    private fun tempDir(name: String): File {
        val d = File(System.getProperty("java.io.tmpdir"), "jinn_storage_test_$name")
        d.deleteRecursively()
        assertTrue("建临时目录失败: $d", d.mkdirs())
        return d
    }

    private fun write(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { 1 })
    }

    @Test
    fun 递归列出每个文件的名字与字节() {
        val root = tempDir("scan")
        try {
            write(File(root, "a.txt"), 10)
            write(File(root, "sub/b.txt"), 20)
            write(File(root, "sub/deep/c.txt"), 30)
            val entries = StorageUsage.scan(root).sortedBy { it.name }
            // 子目录带层级前缀：页面直接展示，同名文件也能区分开
            assertEquals(listOf("a.txt", "sub/b.txt", "sub/deep/c.txt"), entries.map { it.name })
            assertEquals(listOf(10L, 20L, 30L), entries.map { it.bytes })
            // 汇总口径与明细同源（同一次遍历）
            val (bytes, files) = StorageUsage.measure(root)
            assertEquals(60L, bytes)
            assertEquals(3, files)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 单文件与不存在的路径按零计() {
        val root = tempDir("single")
        try {
            val f = File(root, "one.txt")
            write(f, 7)
            assertEquals(7L to 1, StorageUsage.measure(f))
            assertEquals(listOf(StorageUsage.Entry("one.txt", 7L)), StorageUsage.scan(f))
            assertEquals(0L to 0, StorageUsage.measure(File(root, "nope")))
            assertEquals(0L to 0, StorageUsage.measure(null))
            assertEquals(emptyList<StorageUsage.Entry>(), StorageUsage.scan(null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 已认领的文件不重复计入() {
        val root = tempDir("claimed")
        try {
            write(File(root, "listed.txt"), 10)
            write(File(root, "rest.txt"), 20)
            val claimed = HashSet<String>()
            // 先单列 10 字节那个（模拟「下载词库包」逐文件认领）
            assertEquals(10L to 1, StorageUsage.measure(File(root, "listed.txt"), claimed))
            // 再整体兜底：只剩未被认领的 20 字节
            val (bytes, files) = StorageUsage.measure(root, claimed)
            assertEquals(20L, bytes)
            assertEquals(1, files)
            // 明细口径同理只列未认领的那个（认领集合会被上面写满，这里换一份新集合验同一条语义）
            val claimedAgain = HashSet<String>()
            StorageUsage.scan(File(root, "listed.txt"), claimedAgain)
            assertEquals(listOf("rest.txt"), StorageUsage.scan(root, claimedAgain).map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 报表与成分内条目都按体积降序且滤掉空项() {
        val items = listOf(
            StorageUsage.Item("小", 10, listOf(StorageUsage.Entry("小.txt", 10)), ""),
            StorageUsage.Item("空", 0, emptyList(), ""),
            StorageUsage.Item(
                "大", 1000,
                listOf(StorageUsage.Entry("a.bin", 100), StorageUsage.Entry("b.bin", 900)), "",
            ),
        )
        val r = StorageUsage.assemble(apkBytes = 300, items = items, freeBytes = 500)
        assertEquals(listOf("大", "小"), r.items.map { it.label })
        assertEquals(listOf("b.bin", "a.bin"), r.items[0].entries.map { it.name })
        assertEquals(2, r.items[0].files)
        assertEquals(1010L, r.dataBytes)
        assertEquals(300L, r.apkBytes)
        assertEquals(500L, r.freeBytes)
    }
}
