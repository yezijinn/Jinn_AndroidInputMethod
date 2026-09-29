package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * `BUG.md` L-153：缓存键取不到时间戳时**必须**退到 versionCode。
     *
     * 退回常量 `"0"` 会让所有版本共用同一个 `base.0.idx`（升级后仍复用旧索引，日志只写「复用磁盘缓存」，
     * 用户只能清应用数据才恢复）。
     */
    @Test
    fun 缓存键退化时必须带上版本号() {
        assertEquals("12345", PinyinEngine.baseCacheStampOf(12345L, 999L, 20260929L))
        assertEquals("999", PinyinEngine.baseCacheStampOf(0L, 999L, 20260929L))
        assertEquals("v20260929", PinyinEngine.baseCacheStampOf(0L, 0L, 20260929L))
        assertFalse(
            "不同版本不得共用同一个键（退化路径）",
            PinyinEngine.baseCacheStampOf(0L, 0L, 20260929L) == PinyinEngine.baseCacheStampOf(0L, 0L, 20260930L),
        )
        // 源码钉：真正的**字符串插值**（写成 `\$versionCode` 的话是常量文本，等于没修 —— 这次真踩过）
        val src = TestSources.codeSource("PinyinEngine.kt")
        assertTrue("缓存键必须走 baseCacheStampOf", "baseCacheStampOf(apkMtime, updated" in src)
        assertTrue("退化分支必须带版本号（插值，不是转义）", "else -> \"v\$versionCode\"" in src)
    }

    /**
     * `BUG.md` L-151：「能解析但一行都没解析出来」必须判为**加载失败** ——
     * 资产空文件 / 格式变更（列错位被静默跳过）会走到 `loaded = true`：候选栏永远空白，
     * 连「词库载入中」的提示都不出现，也没有重试入口。
     */
    @Test
    fun 词库空载必须判为失败() {
        assertFalse(PinyinEngine.isDictionaryUsable(0, 0))
        assertFalse(PinyinEngine.isDictionaryUsable(0, 500))
        assertFalse(PinyinEngine.isDictionaryUsable(400, 0))
        assertTrue(PinyinEngine.isDictionaryUsable(1, 1))
        // 源码钉：加载收口必须过这条判据（切片定位，避免误伤「测试注入」那两处 loaded = true）
        val src = TestSources.codeSource("PinyinEngine.kt")
        val after = src.substringAfter("val indexMs = loadFullIndex(context)")
        assertTrue(
            "加载收口必须过 isDictionaryUsable（否则零行资产会静默变成空词典）",
            after.substringBefore("Log.i(").contains("isDictionaryUsable("),
        )
    }

}
