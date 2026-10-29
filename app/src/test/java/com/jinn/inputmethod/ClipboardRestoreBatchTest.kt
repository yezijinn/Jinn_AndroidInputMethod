package com.jinn.inputmethod

import com.jinn.inputmethod.TestSources.blockAfter
import com.jinn.inputmethod.TestSources.codeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份导入的**批量写入**（BUG.md L-1533 / AI2 A-250 / A-699）。
 *
 * 这一组守三件在真机上「看着都对、其实在拖时间」的事：
 *  ① 每块一个事务（原先逐条 `insert`，一万行一万次 fsync）；
 *  ② 加密在锁外（原先在 `@Synchronized` 里做 AES-GCM，用户的复制 / 粘贴排在它后面）；
 *  ③ `verifiedReadable.clear()` 整批一次（原先每条一次）。
 *
 * ⚠ 真正的插入要 SQLite + Keystore，JVM 层跑不了 —— 所以这里钉**代码形态与纯函数边界**，
 * 端到端的耗时与分账在真机（`ClipboardRestoreBatchTest` 的说明见 `更新日志.md` 本轮记录）。
 */
class ClipboardRestoreBatchTest {

    // ── 纯函数：一条恢复行该不该入库 ──────────────────────

    @Test
    fun 恢复行的空行与单条上限口径必须与采集路径一致() {
        val limit = 64
        assertTrue("空串不收", ClipboardDb.rejectsRestore("", limit))
        assertTrue("纯空白不收", ClipboardDb.rejectsRestore("   \n\t", limit))
        assertFalse("普通内容照收", ClipboardDb.rejectsRestore("复制的一段话", limit))
        assertFalse(
            "恰好等于上限（ASCII 一字一节）必须收",
            ClipboardDb.rejectsRestore("a".repeat(limit), limit),
        )
        assertTrue(
            "超过上限必须拒",
            ClipboardDb.rejectsRestore("a".repeat(limit + 1), limit),
        )
        // 字符数未超但 UTF-8 字节超：汉字 3 字节/字，这条走的是上限的第二道筛
        assertTrue(
            "汉字按字节计超限必须拒",
            ClipboardDb.rejectsRestore("字".repeat(limit / 2 + 1), limit),
        )
        assertFalse("上限非正视为不限制", ClipboardDb.rejectsRestore("任意长".repeat(100), 0))
    }

    // ── 批量入口的形态 ────────────────────────────────────

    @Test
    fun 批量入口必须按块提交且备忘只在批末清一次() {
        val src = codeSource("ClipboardDb.kt")
        val batch = blockAfter(src, "fun insertRestoredBatch(")
        assertTrue("必须按块切分（否则一次事务写完一万行）", "rows.chunked(RESTORE_CHUNK_ROWS)" in batch)
        assertTrue("块必须交给单块写入函数", "insertRestoredChunk(prepared)" in batch)

        assertEquals(
            "备忘清空在整个批量入口里只能出现一次（原先每条一次）",
            1,
            Regex("""verifiedReadable\.clear\(\)""").findAll(batch).count(),
        )
        val loop = blockAfter(batch, "for (chunk in rows.chunked(")
        assertTrue("清空不得留在循环里", "verifiedReadable.clear()" !in loop)
        assertTrue(
            "清空必须在循环之后（位置决定它是不是批末一次）",
            batch.indexOf("verifiedReadable.clear()") > batch.indexOf("insertRestoredChunk(prepared)"),
        )
        // 结果必须能把「成功 id」与「失败条数」分开交回：分账不变式的根在这里
        assertTrue("必须交回成功 id 与失败条数", "RestoreResult(insertedIds, failed)" in batch)
        // 块内单行失败（insert 返回 -1）与整块回滚都必须计入 failed，否则
        // `insertedIds.size + failed == planned.size` 会静默变小 —— 导入侧的分账跟着少报跳过数
        assertTrue(
            "整块回滚必须整块计失败",
            "failed += prepared.size" in batch,
        )
        assertTrue(
            "块内单行失败也要计：failed += prepared.size - ids.size",
            "failed += prepared.size - ids.size" in batch,
        )
        // 上报路径必须按实际写法查一遍（不是凭记忆）：两条分支都要在
        assertTrue("提交成功分支必须加入 insertedIds", "insertedIds.addAll(ids)" in batch)
        // 拒收分支（空 / 超限）与加密失败分支**各自** failed++：漏一个，这两类行就既不进
        // insertedIds 也不进 failed ⇒ 不变式静默变小（导入侧少报跳过数）。逐条数，别只看有没有
        assertEquals(
            "两条「不进 prepared」的分支必须各自 failed++（空/超限 + 加密失败）",
            2,
            Regex("""failed\+\+""").findAll(batch).count(),
        )
        val addAt = batch.indexOf("prepared.add(")
        assertTrue("两个 failed++ 都必须在 prepared.add 之前（即同一轮逐行循环里）", addAt > 0)
        assertEquals(
            "两个 failed++ 都必须落在 prepared.add 之前",
            2,
            Regex("""failed\+\+""").findAll(batch.substring(0, addAt)).count(),
        )
        assertTrue(
            "整块被拒时必须跳过写库（不能拿空 prepared 去开一个空事务）",
            "if (prepared.isEmpty()) continue" in batch,
        )
    }

    @Test
    fun 加密必须在对象监视器之外() {
        val src = codeSource("ClipboardDb.kt")
        val declAt = src.indexOf("fun insertRestoredBatch(")
        assertTrue("找不到批量入口声明", declAt > 0)
        // 声明的前文（含注解行）也不许出现监视器：注解在函数体外，只看函数体会漏
        val head = src.substring(maxOf(0, declAt - 400), declAt)
        assertFalse("批量入口整体不得 @Synchronized（那会把加密也关进锁里）", "@Synchronized" in head)
        val batch = blockAfter(src, "fun insertRestoredBatch(")
        assertFalse("批量入口内不得自带 synchronized 包住加密", "synchronized(" in batch)
        val encryptAt = batch.indexOf("ClipboardCrypto.encrypt(")
        assertTrue("加密必须真在批量入口里做", encryptAt > 0)
        assertTrue(
            "加密必须在交给写事务**之前**完成",
            encryptAt < batch.indexOf("insertRestoredChunk(prepared)"),
        )
    }

    @Test
    fun 单块写入必须一个事务且整块失败只算这一块() {
        val src = codeSource("ClipboardDb.kt")
        val chunk = blockAfter(src, "private fun insertRestoredChunk(")
        assertTrue("必须显式开事务", "writableDatabase.beginTransaction()" in chunk)
        assertTrue("必须标成功", "writableDatabase.setTransactionSuccessful()" in chunk)
        assertTrue("必须无条件结束事务", "writableDatabase.endTransaction()" in chunk)
        assertTrue("写必须落在对象监视器内（与 upsert / trimTo 同一把锁）", "synchronized(this)" in chunk)
        assertTrue(
            "整块失败必须交回 null（由调用方按本块全失败记账，不牵连其他块）",
            "return if (committed) ids else null" in chunk,
        )
        // 提交阶段才失败时也必须翻成未提交：宁可少报进库数，不能虚报
        assertTrue("提交失败必须回翻 committed", "committed = false" in chunk)
        // 失败只能是「本块」的事：原先逐条写靠 `insert` 自己吞 SQLException，导入从不会因为
        // 写入失败整个中断。批量入口不能丢掉这个性质 —— 一次 beginTransaction 抛出就外溢，
        // 等于「一条坏行毁掉一万行」（任务书明令禁止）
        // 先把 try / finally 的存在本身钉死：缺任一个时，下面两句位置比较会退化成恒真
        val tryAt = chunk.indexOf("try {")
        val finAt = chunk.indexOf("finally")
        assertTrue("必须是 try/catch/finally 三段", tryAt > 0 && finAt > tryAt)
        assertTrue(
            "开事务也必须在 try 内（否则开事务失败会把整块顶出去）",
            chunk.indexOf("beginTransaction()") > tryAt,
        )
        assertTrue("收尾要按「是否真开过事务」决定（开事务失败时不得再 endTransaction）", "if (began)" in chunk)
        // 收尾必须在 finally 里：挪回 try 体后，块内一抛就跳过 endTransaction，事务挂在打开态，
        // 后面每块都嵌进它、直到进程结束一起回滚 —— 整批静默丢失，比「本块失败」糟得多
        assertTrue(
            "结束事务必须在 finally 里（否则中途抛出会把事务挂着）",
            chunk.indexOf("writableDatabase.endTransaction()") > finAt,
        )
        // committed 只能是「标记成功之后」的结论：提前置真则提交失败也交回 ids = 虚报进库数
        assertTrue(
            "committed 必须在 setTransactionSuccessful() 之后才置真",
            chunk.indexOf("setTransactionSuccessful()") < chunk.indexOf("committed = true"),
        )
        assertFalse("本块失败只记日志交回 null，不得外抛", "throw " in chunk)
        // 备忘不得回到单块里逐条清（原「每条插完清一次」换个位置复活，形态钉看不出来）
        assertFalse("单块写入不得碰备忘（清了就等于每条一次复活）", "verifiedReadable" in chunk)
        // 日志一律在监视器之外打：文件 I/O 不该占着 DB 锁（还会牵进日志目录锁）
        val locked = blockAfter(chunk, "synchronized(this)")
        assertFalse("锁内不得写日志", "Diagnostics." in locked)
    }

    @Test
    fun 块大小必须有界且远小于导入上限() {
        assertTrue("块大小至少两位数（否则等于没合批）", ClipboardDb.RESTORE_CHUNK_ROWS >= 10)
        assertTrue(
            "一块坏掉最多毁掉一成：块大小 × 10 不得超导入上限",
            ClipboardDb.RESTORE_CHUNK_ROWS * 10 <= ConfigBackup.MAX_CLIP_IMPORT_ITEMS,
        )
        assertTrue(
            "块也不能大到把整包一次写进内存",
            ClipboardDb.RESTORE_CHUNK_ROWS <= 2_000,
        )
    }

    // ── 调用点：分账口径不得跟着批量入口一起变形 ────────────

    @Test
    fun 导入循环不得退回逐条插入且分账三项仍逐字在场() {
        val mgr = codeSource("ConfigBackupManager.kt")
        val block = blockAfter(mgr, "if (failedStage == null && includeClipboard &&")
        assertTrue("必须走批量入口", "db.insertRestoredBatch(" in block)
        assertFalse("不得再逐条插入", "db.insertRestored(" in block)
        assertTrue(
            "分类仍按本机规则当场算（不是用包里的值）",
            "category = ClipboardClassifier.classify(e.content)" in block,
        )
        // 顺序：批量写完 → 才裁 → 才按 id 复查
        val batchAt = block.indexOf("db.insertRestoredBatch(")
        val trimAt = block.indexOf("db.trimTo(")
        val countAt = block.indexOf("db.countPresent(")
        assertTrue("批量写入必须在 trimTo 之前", batchAt in 0 until trimAt)
        assertTrue("trimTo 必须在按 id 复查之前（裁掉的行不计入 +N）", trimAt < countAt)
        assertTrue(
            "跳过数必须仍由三项相加",
            "clipSkipped = dedupeSkipped + insertFailed + trimmedAway" in block,
        )
        assertTrue(
            "失败数必须来自批量入口（不是自己数）",
            "val insertFailed = restored.failed" in block,
        )
        assertTrue(
            "不变式必须当场核对（不是只写在注释里）：insertedIds.size + insertFailed == planned.size",
            "insertedIds.size + insertFailed != planned.size" in block,
        )
    }

    @Test
    fun 台账入口引用的批量入口必须真的存在() {
        // 反向：批量入口被改名 / 删掉时，上面所有形态钉会一起失配（这条给出人话的理由）
        val src = codeSource("ClipboardDb.kt")
        assertTrue(
            "ClipboardDb 必须有 insertRestoredBatch（台账 L-1533 的修法锚点）",
            "fun insertRestoredBatch(rows: List<RestoreRow>): RestoreResult" in src,
        )
        assertTrue(
            "旧的逐条入口不得复活（两套写路径 = 下一轮必然改漏一套）",
            "fun insertRestored(" !in src,
        )
        assertEquals(
            "裁剪仍只有 trimTo 一个入口",
            1,
            Regex("""fun trimTo\(""").findAll(src).count(),
        )
    }
}
