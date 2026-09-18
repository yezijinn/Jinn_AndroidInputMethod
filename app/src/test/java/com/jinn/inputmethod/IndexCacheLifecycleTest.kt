package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引缓存生命周期的回归护栏。
 *
 * 起因是真机缺陷：可选包加载时那道"清理源包已删除的索引缓存"的扫地逻辑按
 * 「文件名去掉 .idx 后还在不在词库包列表里」判断，而基础索引缓存叫 `base.<APK mtime>.idx`，
 * 源是 APK 内资产、永远不在包列表里，于是每次可选包加载都把它删掉——内存映射再也命中不了，
 * 冷启动每次都退回解压 1.8s，日志上只有一行「清理失效索引缓存」，很难往这上面想。
 */
class IndexCacheLifecycleTest {

    @Test
    fun 基础索引缓存绝不被可选包清扫删除() {
        val existing = listOf(
            "base.1789655607000.idx",      // 基础索引缓存（源在 APK 内，不在包列表）
            "ext.xz.idx",                  // 仍在用的可选包缓存
            "opt_tencent.xz.idx",          // 仍在用的可选包缓存
            "gone.xz.idx",                 // 源包已删除 → 应清理
        )
        val alive = setOf("ext.xz", "opt_tencent.xz")

        val stale = PinyinEngine.staleOptionalCacheNames(existing, alive)

        assertEquals("只应清理源包已删除的那一个", listOf("gone.xz.idx"), stale)
        assertTrue("基础索引缓存必须被排除", stale.none { it.startsWith("base.") })
    }

    @Test
    fun 临时文件与非法后缀不参与清扫() {
        val existing = listOf("ext.xz.idx.tmp", "readme.txt", "ext.xz.idx")
        val stale = PinyinEngine.staleOptionalCacheNames(existing, setOf("ext.xz"))
        assertTrue("在用包的缓存与临时文件都不该被删: $stale", stale.isEmpty())
    }

    @Test
    fun 包被重命名后旧缓存会被清理() {
        val existing = listOf("old.xz.idx", "new.xz.idx")
        val stale = PinyinEngine.staleOptionalCacheNames(existing, setOf("new.xz"))
        assertEquals(listOf("old.xz.idx"), stale)
    }
}
