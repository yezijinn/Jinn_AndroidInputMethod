package com.jinn.inputmethod

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

    private fun pack(sizeMb: Double) = OptionalDict(
        fileName = "part2.xz",
        name = "t",
        descLines = emptyList(),
        sizeMb = sizeMb,
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
}
