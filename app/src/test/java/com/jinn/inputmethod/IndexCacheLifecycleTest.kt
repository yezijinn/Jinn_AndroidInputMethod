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


    /**
     * `BUG.md` L-155（+ L-154 的另一半）：可选包的装载尝试必须**按包身份记账** ——
     * 换了文件（重下 / 换包）要能再试一次，而不是「本进程试过一次就再也不看」。
     */
    @Test
    fun 可选包装载必须按文件身份记账() {
        // 判据是纯函数，直接验四档：没试过 / 没变且成功过 / 没变但失败过 / 换过文件
        assertTrue("没试过的包必须尝试", PinyinEngine.packShouldRetry(null, 100L, null))
        assertFalse("没变且没失败过 ⇒ 不必再试", PinyinEngine.packShouldRetry(100L, 100L, null))
        assertTrue("没变但上次失败过 ⇒ 要重试（词库页那句「空闲时自动装载」必须为真）",
            PinyinEngine.packShouldRetry(100L, 100L, 1))
        assertFalse("同一个身份的失败重试用完 ⇒ 不再白烧 CPU",
            PinyinEngine.packShouldRetry(100L, 100L, PinyinEngine.MAX_OPTIONAL_RETRIES))
        assertTrue("换了文件（重下 / 换包）必须再试，哪怕旧身份试满过",
            PinyinEngine.packShouldRetry(100L, 101L, PinyinEngine.MAX_OPTIONAL_RETRIES))

        val src = TestSources.codeSource("PinyinEngine.kt")
        val ime = TestSources.codeSource("JinnIme.kt")
        // 正向：闸门真的用了这个判据（改语义就红）
        assertTrue(
            "闸门必须逐包调 packShouldRetry（身份 + 失败次数）",
            "packShouldRetry(optionalAttempted[it.name], packStamp(it), optionalFailed[it.name])" in src,
        )
        assertTrue("失败次数必须被累计", "optionalFailed[f.name] = (optionalFailed[f.name] ?: 0) + 1" in src)
        assertTrue("换过文件必须清零旧身份的失败次数", "optionalFailed.remove(f.name)" in src)
        assertTrue("失败集合必须对外可见（原先只有 logcat 知道）", "internal fun failedOptionalPacks()" in src)
        assertTrue("找到文件但没读进来的包名必须被收集", "failed.add(f.name)" in src)
        // 反向钉：这两句一旦回来，就是「失败的包永远不再试」的老毛病（BUG.md L-154 / L-167）
        assertFalse("闸门不得退回布尔闸", "if (optionalLoaded) return" in src)
        assertFalse("调用方不得再放一次性旗标（放回去 ⇒ 失败后没有第二次触发）", "optionalLoadTriggered" in ime)
        assertTrue("调用方必须直接调装载（并只在真的开始时打日志）",
            "val started = PinyinEngine.loadOptionalAsync(this, delayMs = 0L)" in ime)
    }

    /**
     * `BUG.md` L-155：词库页要能看出「装了但没生效」——判据必须与装载路径同一句，
     * 并且用**真文件**验正反两档（不是只钉字符串）。
     */
    @Test
    fun 索引就绪判据必须与源文件同口径() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "jinn-idxready-" + System.nanoTime())
        assertTrue("建临时目录失败", dir.mkdirs())
        try {
            val src = java.io.File(dir, "opt_demo.xz")
            src.writeBytes(ByteArray(64) { 7 })
            val cache = java.io.File(dir, "opt_demo.xz.idx")
            assertFalse("缓存不存在 ⇒ 未就绪", PhraseIndex.cacheMatchesSource(cache, src))
            assertFalse("缓存为 null ⇒ 未就绪", PhraseIndex.cacheMatchesSource(null, src))
            val stamp = PhraseIndex.stampOfFile(src.length(), src.lastModified())
            cache.writeBytes(PhraseIndex.build(sequenceOf("nihao\t你好"), stamp))
            assertTrue("同 stamp 的缓存 ⇒ 就绪", PhraseIndex.cacheMatchesSource(cache, src))
            src.writeBytes(ByteArray(65) { 7 })     // 源换了（长度变）⇒ stamp 变
            assertFalse("源变了 ⇒ 未就绪（必须重建）", PhraseIndex.cacheMatchesSource(cache, src))
        // 快路径必须与完整解析**同口径**：同一个缓存，两种读法给出同一个来源摘要
        val stamp2 = PhraseIndex.stampOfFile(src.length(), src.lastModified())
        cache.writeBytes(PhraseIndex.build(sequenceOf("nihao\t你好"), stamp2))
        assertEquals(
            "只读头部的快路径与 ofMapped 必须给出同一个 stamp（否则界面会误报）",
            PhraseIndex.ofMapped(cache)!!.sourceStamp,
            PhraseIndex.mappedSourceStamp(cache)!!,
        )
        // 头部不合法（magic 坏 / 长度不成比例）不算「就绪」。
        // 注意残余：快路径只校验头部，**头合法而体被截断**的缓存仍会显示「就绪」——
        // 那种缓存会被装载路径的 parse() 拒绝并重建，界面最多少提示一次（取舍记在 L-167）。
        // 用**另一个文件**做这两种情形：Windows 不允许重写已被内存映射的文件（ofMapped 刚映射过它）
        val good = cache.readBytes()
        val bad = java.io.File(dir, "opt_bad.xz")
        bad.writeBytes(ByteArray(48) { 3 })
        val badCache = java.io.File(dir, "opt_bad.xz.idx")
        badCache.writeBytes(ByteArray(good.size) { 0 })
        assertFalse("magic 坏的缓存不算就绪", PhraseIndex.cacheMatchesSource(badCache, bad))
        badCache.writeBytes(good.copyOf(12))
        assertFalse("只剩头部的缓存不算就绪", PhraseIndex.cacheMatchesSource(badCache, bad))
        } finally {
            dir.deleteRecursively()
        }

        val page = TestSources.codeSource("DictManagerActivity.kt")
        // 钉**整条判据**（含条件与取反），而不是只钉常量名：常量声明就在被扫文件里，钉名字会假绿
        assertTrue(
            "词库页必须按「已安装 && 未就绪」显示（判据与装载路径同一句）",
            "if (installed && !PinyinEngine.isOptionalIndexReady(this, dict.fileName)) {" in page,
        )
        assertTrue("未就绪文案必须在（代码内下发，不动 strings.xml）", "TEXT_INDEX_PENDING" in page)
        assertTrue("文案必须给出「有界重试用尽」时的出路", "请删除该包后重新下载" in page)
    }

}
