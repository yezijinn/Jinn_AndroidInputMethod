package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图库快贴的纯逻辑护栏。
 *
 * 真机取证（2026-10-07）确认了三条不做就会踩的约束：宿主不声明图片能力时装入口只会白点、
 * `commitContent` 返回值不可信、缺 GRANT 标志时宿主会把 URI 当纯文本。另有三次复核修出的
 * 缺口（输入框粒度、清理保护面、提交前存在性），都在下面钉住。
 */
class GalleryInsertTest {

    @Test
    fun 入口判据只认图片类型与全通配() {
        assertTrue(GalleryInsert.canHostAccept(arrayOf("image/*")))
        assertTrue(GalleryInsert.canHostAccept(arrayOf("image/png", "video/*")))
        assertTrue(GalleryInsert.canHostAccept(arrayOf("*/*")))
        assertFalse("只声明视频不算", GalleryInsert.canHostAccept(arrayOf("video/*", "audio/*")))
        assertFalse("空声明不算", GalleryInsert.canHostAccept(emptyArray()))
        assertFalse("取不到声明不算", GalleryInsert.canHostAccept(null))
    }

    private fun entry(name: String, len: Long) = File("/tmp/gallery_share/$name") to len

    @Test
    fun 清理保留最近若干张且最新一张永不删() {
        val entries = (1..8).map { entry("pick_$it", 1_000L) } // index 0 = 最新
        val stale = GalleryInsert.staleFilesForTrim(entries, keepFiles = 5, maxBytes = 1_000_000L)
        assertEquals("超出保留数的按最旧先删", listOf("pick_6", "pick_7", "pick_8"), stale.map { it.name })
        assertFalse("最新一张永不删（可能正被宿主读取）", stale.any { it.name == "pick_1" })
    }

    /**
     * 保留面是 **2** 而不是 1：最新那张刚提交，次新那张常在上一轮插入后仍被宿主异步重读
     * （L-1003）。用 `keepFiles = 1` 构造能区分 1 张与 2 张保护的场景。
     */
    @Test
    fun 清理至少保留最近两张() {
        val entries = (1..6).map { entry("pick_$it", 1_000L) }
        val stale = GalleryInsert.staleFilesForTrim(entries, keepFiles = 1, maxBytes = 1_000_000L)
        assertEquals(listOf("pick_3", "pick_4", "pick_5", "pick_6"), stale.map { it.name })
        assertFalse("次新一张也受保护", stale.any { it.name == "pick_2" })
    }

    @Test
    fun 清理按总字节上限淘汰最旧() {
        val mb = 20L * 1024 * 1024 // 单张上限
        val entries = (1..5).map { entry("pick_$it", mb) } // 共 100MB
        val stale = GalleryInsert.staleFilesForTrim(entries, keepFiles = 5, maxBytes = 45L * 1024 * 1024)
        assertEquals("超总字节时从最旧开始删到落回上限", listOf("pick_3", "pick_4", "pick_5"), stale.map { it.name })
    }

    @Test
    fun 清理在双条件都满足时不删任何文件() {
        val entries = (1..3).map { entry("pick_$it", 1_000L) }
        assertTrue(GalleryInsert.staleFilesForTrim(entries, keepFiles = 5, maxBytes = 1_000_000L).isEmpty())
        assertTrue("空目录不炸", GalleryInsert.staleFilesForTrim(emptyList(), 5, 1_000L).isEmpty())
    }

    /**
     * 能力标记必须由 IME 重放（L-998）。
     *
     * 视图重建（换肤 / 反馈开关 / 符号布局变更）换掉整棵树，视图字段回落 `false`；
     * 两处重放点（`onCreateInputView` 与 `recreateKeyboardView`）都得带上它，
     * 漏一处就有一条路径会让「图库」键在本会话剩余时间里消失。
     */
    @Test
    fun 能力标记在视图重建的两处重放点都要带上() {
        val src = TestSources.codeSource("JinnIme.kt")
        val replay = Regex("""setHostImageCapable\(hostImageCapableForSession\)""").findAll(src).count()
        assertEquals("重放点应为两处（onCreateInputView + recreateKeyboardView）", 2, replay)
        assertTrue("推送处要存 IME 侧字段", "hostImageCapableForSession = capable" in src)
    }

    /**
     * 落库前必须核对发起**输入框**（L-999 首修到包名，L-1002 收到 fieldKey）。
     *
     * 只比包名时，同一个应用里换个会话（另一个输入框）会被放行，图插进用户刚切过去的那个；
     * `flushPendingPaste` 早就用 `fieldKeyOf(packageName, fieldId)`，两处口径必须一致。
     */
    @Test
    fun 落库前核对发起输入框() {
        val src = TestSources.codeSource("JinnIme.kt")
        val body = src.substringAfter("private fun flushPendingGalleryImage()")
            .substringBefore("private fun openGalleryPicker()")
        assertTrue(
            "落库要按输入框标识比对，不能只比包名",
            "pendingPasteFieldKey(currentInputEditorInfo)" in body,
        )
        assertTrue("发起点要把标识带进选图页", "putExtra(GalleryInsert.EXTRA_HOST_KEY" in src)
        assertTrue(
            "选图页要读回标识并随桥保存",
            "putPending(result.file, result.mime, hostKey)" in TestSources.codeSource("GalleryPickActivity.kt"),
        )
    }

    /**
     * 复制失败必须给可见提示（L-1000）。
     *
     * 此前只落一条泛化告警，界面表现与「选完图但键盘没弹回来」无从区分。
     */
    @Test
    fun 复制失败的两条路径都要有界面提示() {
        val src = TestSources.codeSource("GalleryPickActivity.kt")
        assertTrue("超限提示", "TEXT_TOO_LARGE" in src && "\"图片超过 20MB，未插入\"" in src)
        assertTrue("读失败提示", "TEXT_READ_FAILED" in src && "\"读取图片失败，未插入\"" in src)
        assertTrue("提示要真的弹出来", "Toast.makeText" in src)
    }

    /**
     * 提交前必须校验文件还在（L-1004）。
     *
     * 文件被系统清 cache 或清理逻辑删掉后，URI 仍能构造、`commitContent` 也可能返回 true，
     * 宿主读到空内容却没有任何提示 —— 与「假成功」叠加后从 IME 侧诊断看不出区别。
     */
    @Test
    fun 提交前拦截已消失的文件() {
        val src = TestSources.codeSource("GalleryInsert.kt")
        val body = src.substringAfter("fun commit(").substringBefore("fun copyToCache(")
        assertTrue("commit 要先判文件存在与长度", "!file.exists() || file.length() == 0L" in body)
    }
}
