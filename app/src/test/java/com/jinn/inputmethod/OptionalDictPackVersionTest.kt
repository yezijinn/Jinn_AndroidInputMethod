package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * 旧包检测（[OptionalDicts.isCurrentPack]）的守卫。
 *
 * 背景（2026-10-10 下载包重切）：卡片原先只按「文件存在」判已安装，换发版后（同名附件重切，
 * 2 级 40 万 → 90 万）老用户会一直看到「已安装」而不再点更新，词库停在上一个版本。
 * 这里用**真实字节数**钉住：当前版本判最新、上一版本判需更新，且三个包的新旧大小都能区分。
 */
class OptionalDictPackVersionTest {

    private fun pack(sizeMb: Double, installMiB: Int = 26) = OptionalDict(
        fileName = "part2.xz",
        name = "t",
        descLines = emptyList(),
        sizeMb = sizeMb,
        installMiB = installMiB,
        startupSec = 1,
        urls = listOf("https://example.com/a.xz"),
        checksum = "0".repeat(64),
    )

    /** 稀疏文件：setLength 不实际落盘 6MB，秒级内可建 */
    private fun tempOfSize(bytes: Long): File =
        File.createTempFile("pack", ".xz").also { RandomAccessFile(it, "rw").use { raf -> raf.setLength(bytes) } }

    @Test
    fun 当前版本的包判为最新() {
        // part2′ 实际 5,893,252 B，清单 sizeMb = 5.89（十进制 MB）
        val f = tempOfSize(5_893_252)
        try {
            assertTrue(OptionalDicts.isCurrentPack(f, pack(5.89)))
        } finally {
            f.delete()
        }
    }

    @Test
    fun 上一版的包判为需更新() {
        // 旧 part2 = 2,747,068 B（2.62MB）；清单已换成 5.89MB 后必须判旧
        val f = tempOfSize(2_747_068)
        try {
            assertFalse(OptionalDicts.isCurrentPack(f, pack(5.89)))
        } finally {
            f.delete()
        }
    }

    /**
     * 就位判据（[OptionalDicts.isInstalled]）：文件缺席 / 空壳 / 旧包都不算就位。
     *
     * 它是两处的共同判据 —— 分类词库页的「已安装」与设置页「补充短语词库」入口的提醒态。
     * 判错的方向不对称：把当前包误判成没就位只是让入口多亮一次（无害），漏判却让提醒永远不出现。
     */
    @Test
    fun 就位判据要求文件在场且与清单相符() {
        val dir = java.nio.file.Files.createTempDirectory("dicts").toFile()
        try {
            val dict = pack(5.89)
            assertFalse("文件缺席 = 没就位", OptionalDicts.isInstalled(dir, dict))
            val f = File(dir, dict.fileName)
            assertFalse("空文件 = 没就位", f.createNewFile() && OptionalDicts.isInstalled(dir, dict))
            RandomAccessFile(f, "rw").use { it.setLength(2_747_068) }
            assertFalse("旧包 = 没就位", OptionalDicts.isInstalled(dir, dict))
            RandomAccessFile(f, "rw").use { it.setLength(5_893_252) }
            assertTrue("当前包 = 就位", OptionalDicts.isInstalled(dir, dict))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 提醒态盯的是 2 级词库：清单重排时这条要跟着改，不能悄悄把提醒挪到别的包上 */
    @Test
    fun 提醒态盯的是2级词库() {
        assertEquals("part2.xz", OptionalDicts.LEVEL2.fileName)
        assertEquals(OptionalDicts.ALL.first(), OptionalDicts.LEVEL2)
    }

    @Test
    fun 三个包的新旧大小都能区分() {
        // 真实值：新旧两版大小差 2~3.3MB，远大于 512KB 容差
        val cases = listOf(
            Triple(5_893_252L, 5.89, 2_747_068L),   // part2′：新 5.89 / 旧 2.62
            Triple(5_290_704L, 5.29, 3_460_000L),   // part3′：新 5.29 / 旧 3.30
            Triple(2_142_528L, 2.14, 4_414_000L),   // part4′：新 2.14 / 旧 4.21
        )
        for ((newBytes, sizeMb, oldBytes) in cases) {
            val nf = tempOfSize(newBytes)
            val of = tempOfSize(oldBytes)
            try {
                assertTrue("新包应判最新（$sizeMb）", OptionalDicts.isCurrentPack(nf, pack(sizeMb)))
                assertFalse("旧包应判需更新（$sizeMb）", OptionalDicts.isCurrentPack(of, pack(sizeMb)))
            } finally {
                nf.delete()
                of.delete()
            }
        }
    }

    /**
     * 下载预检必须按**安装占用**估算，不能按压缩体积。
     *
     * 2026-10-19 修：原来按 `sizeMb × 2 + 8MB` 算，而 part2 压缩包 5.89MB 建出的索引有 26MiB
     * —— 预检值（19.8MiB）连索引本身都不够，于是「下载成功 → 回执成功 → 后台建索引 ENOSPC
     * → 词库永不生效」，且用户看不到原因。现在按 [OptionalDict.installMiB] × 2 + 8MB 取。
     */
    @Test
    fun 下载预检按安装占用而非压缩体积() {
        // part2：压缩 5.89MB，安装 26MiB ⇒ 需要 26×2+8 = 60MiB
        val need = DictManagerActivity.requiredFreeBytes(pack(sizeMb = 5.89, installMiB = 26))
        assertEquals(60L * 1024 * 1024, need)

        // 关键不变式：需要量必须大于安装占用本身（否则连索引都放不下）
        assertTrue(
            "预检值必须大于安装占用，否则索引写不下",
            need > 26L * 1024 * 1024,
        )

        // 小包同理（part4：安装 9MiB ⇒ 9×2+8 = 26MiB）
        assertEquals(
            26L * 1024 * 1024,
            DictManagerActivity.requiredFreeBytes(pack(sizeMb = 2.14, installMiB = 9)),
        )
    }

    /**
     * 空间预检必须量一个**必然存在**的路径（BUG.md L-1424）。
     *
     * `StatFs(不存在的路径)` 抛 `IllegalArgumentException`（设备实测：
     * `Invalid path: …/files/dicts`），而预检写的是 `runCatching{}.getOrNull() ?: Long.MAX_VALUE`
     * ⇒ 异常被吞成「空间无限」。`files/dicts` 首次下载前并不存在（全仓只有 `fetchToFile` 里
     * `dir.mkdirs()`，它在预检之后）⇒ 预检整段静默跳过，L-799 要挡的「写一半 ENOSPC / 建索引 ENOSPC」
     * 原样打开。改量 `filesDir`：同一数据分区、数值相同，且它必然存在（与 [StorageUsage.freeBytes] 同口径）。
     */
    @Test
    fun 空间预检必须量必然存在的路径() {
        val src = TestSources.codeSource("DictManagerActivity.kt")
        assertTrue(
            "预检必须量 app.filesDir（dir 首次下载前不存在，StatFs 会抛 IllegalArgumentException）",
            "StatFs(app.filesDir.absolutePath)" in src,
        )
        assertFalse(
            "预检不许再量 dir（不存在 ⇒ 异常 ⇒ 预检静默跳过）",
            "StatFs(dir.absolutePath)" in src,
        )
        // 量不出来必须留痕：否则「预检没跑」与「空间充足」在日志里同形，事后无从归因
        val block = TestSources.window(src, "val stat = runCatching", "val freeBytes")
        assertTrue("预检取不到剩余空间时必须打 W 日志", "Diagnostics.w(" in block)
    }

    /**
     * 清单里三个包的 installMiB 必须与卡片文案「体积占用 +N MiB」对得上。
     *
     * 两者不同源就是给用户一个假的安装预期；也是本条预检的数据来源。
     */
    @Test
    fun 安装占用与卡片文案同源() {
        for (dict in OptionalDicts.ALL) {
            // 用正则整段匹配取数字：按锚点截串的写法被元守卫禁（BUG.md L-1145），
            // 且截串在文案微调时会静默取错值，正则要么整段命中要么明确报错
            val claimed = dict.descLines
                .asSequence()
                .flatMap { it.lineSequence() }
                .mapNotNull { line ->
                    // 解构取分组（不用 groupValues[N]：元守卫把「按索引取子串」一并禁掉）
                    val (mib) = Regex("""体积占用 \+(\d+) MiB""").find(line)?.destructured
                        ?: return@mapNotNull null
                    mib.toIntOrNull()
                }
                .firstOrNull()
                ?: error("${dict.fileName} 卡片文案里找不到「体积占用 +N MiB」")
            assertEquals("${dict.fileName} 的 installMiB 与文案不一致", claimed, dict.installMiB)
        }
    }

}
