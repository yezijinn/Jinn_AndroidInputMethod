package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 文件导入的**块事务**（BUG.md L-1536）护栏。
 *
 * 钉三件事：
 *  1. 攒块边界：每 [ClipboardFileImporter.IMPORT_CHUNK_ROWS] 行写一次，不足一块的尾巴（含中止）照样写出；
 *  2. 回执分账：`added + duplicate + tooLong + blank + failed` 恒等于 `lines`，写库抛异常也不漏记；
 *  3. 源码形态：成批入口一块一个事务、逐条走与单条入口**同一条**实现、裁剪在事务之外、
 *     导入侧不得退回逐条 `ClipboardStore.save`。
 *
 * 真库上的加密 / 判重 / 置顶单调 / 坏行自愈 / 幽灵 id 兜底走真机验证（见 `.agent-workflow/round72-import/01-agent-1.md`）。
 */
class ClipboardImportBatchTest {

    private fun import(
        content: String,
        maxLines: Int = ClipboardFileImporter.MAX_IMPORT_LINES,
        write: (List<String>) -> BooleanArray = { texts -> BooleanArray(texts.size) { true } },
    ): Pair<ClipboardFileImporter.StreamResult, List<Int>> {
        val batches = ArrayList<Int>()
        val result = ClipboardFileImporter.importStream(
            input = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
            limitBytes = 1024,
            knownHashes = mutableSetOf(),
            writeChunk = { texts ->
                batches.add(texts.size)
                write(texts)
            },
            maxLines = maxLines,
        )
        return result to batches
    }

    @Test
    fun 块大小与裁剪同频且有界() {
        // 同值只让「一块 = 一次裁剪」可预测；裁剪在事务之外靠的是调用点顺序（写块返回之后才裁），
        // 与本值与块大小是否相等无关 —— 所以这里钉的是「别退化成整批」与「别小到没有摊销意义」，
        // 不是「不同值就会把裁剪塞进事务」
        // 下面这个等值断言挡的是「只改一个、另一个忘了看」：裁剪次数只取决于成功条数，
        // 真要解绑就按新值同步这一行（不是行为约束）
        assertEquals(ClipboardFileImporter.TRIM_EVERY, ClipboardFileImporter.IMPORT_CHUNK_ROWS)
        assertTrue("块不能退化成整批一个事务", ClipboardFileImporter.IMPORT_CHUNK_ROWS > 1)
        assertTrue(
            "块也不能小到没有摊销意义",
            ClipboardFileImporter.IMPORT_CHUNK_ROWS <= 1000,
        )
        assertTrue(
            "一块远小于导入上限（整批回滚才不至于毁掉一次导入）",
            ClipboardFileImporter.IMPORT_CHUNK_ROWS < ClipboardFileImporter.MAX_IMPORT_LINES,
        )
    }

    @Test
    fun 按块写出且不足一块的尾巴也写() {
        val rows = ClipboardFileImporter.IMPORT_CHUNK_ROWS + 50
        val content = (1..rows).joinToString("\n") { "行$it" }
        val (r, batches) = import(content)
        assertEquals(rows, r.added)
        // 100 + 50：尾巴必须在流末补写，不能留在缓冲里
        assertEquals(
            listOf(ClipboardFileImporter.IMPORT_CHUNK_ROWS, 50),
            batches,
        )
    }

    @Test
    fun 中止时已接受的行照样写出() {
        // 行数闸触发中止：已接受的 50 行必须落库（逐条写的旧口径里它们同样是立刻入库的）
        val content = (1..200).joinToString("\n") { "行$it" }
        val (r, batches) = import(content, maxLines = 50)
        assertEquals(ClipboardFileImporter.Abort.TOO_MANY_LINES, r.abort)
        assertEquals("行数包含触发中止的那一行", 51, r.lines)
        assertEquals(50, r.added)
        assertEquals(50, batches.sum())
    }

    @Test
    fun 写库抛异常时该批计失败且分账不漏() {
        var calls = 0
        val content = (1..ClipboardFileImporter.IMPORT_CHUNK_ROWS).joinToString("\n") { "行$it" }
        val (r, _) = import(content) { texts ->
            calls++
            if (calls == 1) throw IllegalStateException("模拟写库抛异常")
            BooleanArray(texts.size) { true }
        }
        assertEquals(ClipboardFileImporter.Abort.INTERRUPTED, r.abort)
        assertEquals(0, r.added)
        assertEquals(ClipboardFileImporter.IMPORT_CHUNK_ROWS, r.failed)
        assertEquals(r.lines, r.added + r.duplicate + r.tooLong + r.blank + r.failed)
    }

    @Test
    fun 分账三项之和恒等于行数() {
        val content = "甲\n甲\n\n@\n" + "很长".repeat(600) + "\n乙"
        val (r, _) = import(content) { texts ->
            // 「@」写失败，其余成功
            BooleanArray(texts.size) { texts[it] != "@" }
        }
        assertEquals(r.lines, r.added + r.duplicate + r.tooLong + r.blank + r.failed)
        assertEquals(1, r.duplicate)
        assertEquals(1, r.blank)
        assertEquals(1, r.tooLong)
        assertEquals(1, r.failed)
        assertEquals(2, r.added)
    }

    @Test
    fun 成批入块一个事务且裁剪留在事务之外() {
        val body = TestSources.blockAfter(
            TestSources.codeSource("ClipboardDb.kt"),
            "fun upsertImportChunk(",
        )
        assertTrue("必须开事务", "beginTransaction()" in body)
        assertTrue("必须提交", "setTransactionSuccessful()" in body)
        assertTrue("必须收尾", "endTransaction()" in body)
        assertTrue("逐条必须走与单条入口同一条实现", "upsertRow(" in body)
        assertFalse(
            "裁剪不得进事务（删除与写入共享回滚域，一次回滚连裁剪一起撤销）",
            "trimTo(" in body,
        )
        assertFalse(
            "整批作废备忘是备份导入的做法（那里整批都是新密文）：这条路逐条 rememberReadable，" +
                "整批清空只会让下一次重复复制多解一次密",
            "verifiedReadable.clear()" in body,
        )
    }

    @Test
    fun 单条入口与成批入口共用同一条实现() {
        val db = TestSources.codeSource("ClipboardDb.kt")
        val at = db.indexOf("fun upsert(\n        content: String,")
        assertTrue("单条入口锚点不得漂移", at > 0)
        assertTrue("单条入口必须走同一条实现", "upsertRow(" in TestSources.blockAfter(db, "fun upsert(\n        content: String,"))
        assertTrue(
            "单条入口仍逐条串行（与写锁同一把）",
            "@Synchronized" in db.substring(at - 40, at),
        )
        // 两处各写一份实现是本条缺陷的同族回退（下一轮必然改漏一套）
        assertEquals(
            "插入分支只许有一处实现（upsertRow）",
            2,
            db.split("resolvedCategory = category ?: ClipboardClassifier.classify(").size,
        )
    }

    @Test
    fun 导入侧不得退回逐条写库() {
        val importer = TestSources.codeSource("ClipboardFileImporter.kt")
        assertTrue("必须走成批入口", "ClipboardStore.saveChunk(" in importer)
        assertFalse(
            "逐条 save 就是每条一个隐式事务（L-1536 的原始形态）",
            "ClipboardStore.save(" in importer,
        )
    }

    @Test
    fun 成批保存仍逐条判单条上限() {
        val body = TestSources.blockAfter(
            TestSources.codeSource("ClipboardController.kt"),
            "fun saveChunk(",
        )
        assertTrue("单条闸不能因为成批而跳过", "exceedsItemLimit(" in body)
        assertTrue("写库走成批入口", "upsertImportChunk(" in body)
        assertTrue("逐条结果要与入参同序", "BooleanArray(texts.size)" in body)
    }
}
