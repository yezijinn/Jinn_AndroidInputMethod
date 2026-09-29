package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引缓存生命周期的回归护栏。
 *
 * 起因是真机缺陷：可选包加载时那道"清理源包已删除的索引缓存"的扫地逻辑按
 * 「文件名去掉 .idx 后还在不在词库包列表里」判断，而基础索引缓存叫 `base.<APK mtime>.idx`，
 * 源是 APK 内资产、永远不在包列表里，于是每次可选包加载都把它删掉，内存映射再也命中不了，
 * 冷启动每次都退回解压 1.8s，日志上只有一行「清理失效索引缓存」，很难往这上面想。
 */
class IndexCacheLifecycleTest {

    @Test
    fun 基础索引缓存绝不被可选包清扫删除() {
        val existing = listOf(
            "base.1789655607000.idx",      // 基础索引缓存（源在 APK 内，不在包列表）
            "part2.xz.idx",                // 仍在用的可选包缓存
            "part3.xz.idx",                // 仍在用的可选包缓存
            "gone.xz.idx",                 // 源包已删除 → 应清理
        )
        val alive = setOf("part2.xz", "part3.xz")

        val stale = PinyinEngine.staleOptionalCacheNames(existing, alive)

        assertEquals("只应清理源包已删除的那一个", listOf("gone.xz.idx"), stale)
        assertTrue("基础索引缓存必须被排除", stale.none { it.startsWith("base.") })
    }

    @Test
    fun 在用包的临时文件与非法后缀不参与清扫() {
        val existing = listOf("part2.xz.idx.tmp", "readme.txt", "part2.xz.idx")
        val stale = PinyinEngine.staleOptionalCacheNames(existing, setOf("part2.xz"))
        assertTrue("在用包的缓存与临时文件都不该被删: $stale", stale.isEmpty())
    }

    /**
     * 源包已删除的**半截临时件**要一并回收（`BUG.md` L-20）。
     *
     * 「装过包 → 写索引时被杀 → 删包」会留下 `<包名>.idx.tmp`：既不在 `.idx` 判据里，
     * 又没人再写同名临时件（包已不在 ⇒ 不会重建索引 ⇒ `renameTo` 自愈不了），于是永久占空间
     * （单份可十几 MB）。只回收**死包**的临时件 —— 在用包的临时件可能正被写线程持有，
     * 删了会让它改名失败、退回堆内。`base.` 前缀（源在 APK 内、不在包列表）同样交给另一条清扫。
     */
    @Test
    fun 死包的半截临时件要回收() {
        val existing = listOf(
            "part2.xz.idx.tmp",            // 在用包 → 不动
            "gone.xz.idx.tmp",             // 源包已删 → 回收
            "gone2.xz.idx",                // 源包已删 → 回收
            "base.1789655607000.idx.tmp",  // 基础缓存前缀 → 另一条清扫负责
            "readme.txt",
        )
        val stale = PinyinEngine.staleOptionalCacheNames(existing, setOf("part2.xz"))
        assertEquals(listOf("gone.xz.idx.tmp", "gone2.xz.idx"), stale)
    }

    @Test
    fun 包被重命名后旧缓存会被清理() {
        val existing = listOf("old.xz.idx", "new.xz.idx")
        val stale = PinyinEngine.staleOptionalCacheNames(existing, setOf("new.xz"))
        assertEquals(listOf("old.xz.idx"), stale)
    }
}
