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
 * `commitContent` 返回值不可信、缺 GRANT 标志时宿主会把 URI 当纯文本。另有一批复核修出的
 * 缺口（输入框标识、清理保护面、提交前存在性、失败上报），都在下面钉住。
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
     * 保留张数必须由另两个常量推导（L-1007）。
     *
     * 写死 5 会掩盖「张数与字节在单张上限下互斥」这件事，后来调参的人容易以为放宽张数就能多留。
     */
    @Test
    fun 保留张数由总字节与单张上限推导() {
        val src = TestSources.codeSource("GalleryInsert.kt")
        assertTrue(
            "KEEP_FILES 必须由常量推导",
            "private val KEEP_FILES = (MAX_CACHE_BYTES / MAX_BYTES).toInt() + PROTECTED_RECENT" in src,
        )
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
     * 图库的输入框标识比粘贴暂存宽，但只取静态属性（L-1005 / L-1008）。
     *
     * `fieldKeyOf` 只有包名 + fieldId，而自绘输入框（微信聊天框等）的 fieldId 常恒为同一个值
     * ⇒ 分不出同一应用的不同会话，故多带 `inputType` / `imeOptions`。
     *
     * `hintText` 不能参与：它随输入状态变（聚焦后 placeholder 常会被宿主收起或换文案），
     * 而「点图库」与「回原应用」是两次采样，对不上就会把同一个输入框判成换了框，合法选图被丢弃。
     */
    @Test
    fun 图库输入框标识更宽但只取静态属性() {
        val src = TestSources.codeSource("GalleryInsert.kt")
        val body = src.substringAfter("internal fun galleryFieldKeyOf(").substringBefore("fun putPending(")
        for (field in listOf("fieldId", "inputType", "imeOptions")) {
            assertTrue("标识要带 $field", field in body)
        }
        assertFalse("hint 随输入状态变，不能进标识", "hintText" in body)
        val mine = TestSources.codeSource("JinnIme.kt")
        val uses = Regex("""galleryFieldKeyOf\(currentInputEditorInfo\)""").findAll(mine).count()
        assertEquals("发起点与落库都要用图库口径", 2, uses)
    }

    /**
     * 落地阶段的**本地可判定**失败要上报（L-1006）：文件失效与输入框已切换各给一次提示；
     * 宿主拒收与无连接保持静默，免得变成噪音。
     */
    @Test
    fun 落地失败的两类会上报界面() {
        val mine = TestSources.codeSource("JinnIme.kt")
        val body = mine.substringAfter("private fun flushPendingGalleryImage()")
            .substringBefore("private fun openGalleryPicker()")
        assertTrue("文件失效要提示", "toast(TEXT_GALLERY_GONE)" in body)
        assertTrue("输入框已切换要提示", "toast(TEXT_GALLERY_FIELD_CHANGED)" in body)
        val rejectedTail = body.substringAfter("InsertResult.Rejected", "").take(60)
        assertFalse("宿主拒收不弹提示", "toast" in rejectedTail)
        val gallery = TestSources.codeSource("GalleryInsert.kt")
        for (state in listOf("Submitted", "Rejected", "NoConnection", "FileMissing")) {
            assertTrue("提交结果要有 $state 态", state in gallery)
        }
    }

    /**
     * 复制失败必须给可见提示（L-1000）。
     *
     * 此前只落一条泛化告警，界面表现与「选完图但键盘没弹回来」无从区分。
     */
    @Test
    fun 复制失败的两条路径都要有界面提示() {
        val src = TestSources.codeSource("GalleryPickActivity.kt")
        assertTrue("超限提示走同源函数", "GalleryInsert.tooLargeText()" in src)
        assertFalse("数字不能写死在文案里", "超过 20MB" in src)
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
        assertTrue("并回报 FileMissing 让调用方说话", "InsertResult.FileMissing" in body)
    }

    /**
     * 类型不给通配，按文件头嗅探出具体类型（L-1009）。
     *
     * `ClipDescription` 的 mimeTypes 要求具体类型，通配属无定义行为；文件名也不能停在 `.tmp` / `.img`
     * 这类无意义后缀 —— 按后缀判类型的宿主会认不出这张图。
     */
    @Test
    fun 图片类型按文件头嗅探且兜底不用通配() {
        val src = TestSources.codeSource("GalleryInsert.kt")
        assertFalse("类型兜底不能用通配", "\"image/*\"" in src)
        assertTrue("取不到声明时按文件头嗅探", "sniffImageMime(readHead(" in src)
        assertTrue("文件名带嗅探出的扩展名", "extOf(mime)" in src)

        assertEquals("JPEG", "image/jpeg", GalleryInsert.sniffImageMime(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("PNG", "image/png", GalleryInsert.sniffImageMime(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("GIF", "image/gif", GalleryInsert.sniffImageMime("GIF89a".toByteArray()))
        assertEquals("WebP", "image/webp", GalleryInsert.sniffImageMime("RIFF????WEBPVP8 ".toByteArray()))
        assertEquals("HEIF", "image/heic", GalleryInsert.sniffImageMime("????ftypheic".toByteArray()))
        assertEquals("HEIF 家族 mif1", "image/heic", GalleryInsert.sniffImageMime("????ftypmif1".toByteArray()))
        assertEquals("AVIF 不能被当成 HEIC", "image/avif", GalleryInsert.sniffImageMime("????ftypavif".toByteArray()))
        assertEquals("非图片的 ftyp 走兜底", "image/png", GalleryInsert.sniffImageMime("????ftypisom".toByteArray()))
        assertEquals("都不认时兜底具体类型", "image/png", GalleryInsert.sniffImageMime(bytes(0x01, 0x02)))
        assertEquals("空头不炸", "image/png", GalleryInsert.sniffImageMime(ByteArray(0)))
        assertEquals("截断的 JPEG 头不误判", "image/png", GalleryInsert.sniffImageMime(bytes(0xFF, 0xD8)))
        assertTrue("未列出的类型取 MIME 子类型当扩展名", "substringAfter('/', \"\")" in src)
    }

    /**
     * 超时丢弃要能看见（L-1014）。
     *
     * 此前 `takePending` 把「没有待插入」与「有但过期」都返回 null，超时路径既无提示也无日志，
     * 查诊断包时分不清两者。
     */
    @Test
    fun 超时丢弃要与没有待插入区分开() {
        val gallery = TestSources.codeSource("GalleryInsert.kt")
        assertTrue("取桥结果要分三态", "sealed interface TakeResult" in gallery)
        assertTrue("并回报 Expired", "TakeResult.Expired" in gallery)
        val mine = TestSources.codeSource("JinnIme.kt")
        val body = mine.substringAfter("private fun flushPendingGalleryImage()")
            .substringBefore("private fun openGalleryPicker()")
        assertTrue("过期要落日志", "TakeResult.Expired" in body && "已过期" in body)
    }

    /**
     * 图片目录与 FileProvider 配置必须一致（L-1016）。
     *
     * 目录名在代码与 `res/xml` 里各写一遍，改一处忘另一处时 `getUriForFile` 抛异常，
     * 而 `commit` 把它记成「宿主未接受本次提交」—— 排查方向跑偏，界面上还什么都没有。
     */
    @Test
    fun 图片目录与提供者配置一致() {
        val xml = listOf(
            File("src/main/res/xml/share_file_paths.xml"),
            File("app/src/main/res/xml/share_file_paths.xml"),
        ).firstOrNull { it.isFile }
        assertTrue("paths 配置要能找到（工作目录变了？）", xml != null)
        // 先剥注释再比：否则「删掉真配置、把 path 留在注释里」也能满足断言（L-1019，同 L-116）
        val text = TestSources.codeOf(xml!!.readText())
        assertTrue("配置要放行 cache 子目录", "cache-path" in text)
        assertTrue("path 要等于 DIR_NAME", "path=\"${GalleryInsert.DIR_NAME}/\"" in text)
    }

    /**
     * 超限提示的数字必须来自阈值（L-1017）。
     *
     * 写死数字时，调 `MAX_BYTES` 只改了日志、提示还在说旧值 —— 同一处代码两个口径。
     */
    @Test
    fun 超限提示的数字取自阈值() {
        val src = TestSources.codeSource("GalleryPickActivity.kt")
        assertTrue("文案由 MAX_BYTES 拼出", "MAX_BYTES / 1024 / 1024" in src)
        assertFalse("不能写死数字", "超过 20MB" in src)
    }

    /**
     * 超限提示必须真的由阈值算出来（L-1021）。
     *
     * 放在 `GalleryInsert` 而不是选图页的 `private companion`，就是为了能断言输出本身 ——
     * 只看源码里有没有那段算式，算式写错照样蒙过。
     */
    @Test
    fun 超限提示的数字与阈值同源() {
        val mb = GalleryInsert.MAX_BYTES / 1024 / 1024
        assertEquals("提示里的数字要等于阈值", "图片超过 ${mb}MB，未插入", GalleryInsert.tooLargeText())
    }

    /**
     * 功能面板到 8 键必须收窄内边距（L-1020）。
     *
     * 这段逻辑删掉、或阈值改回 6，门禁都不会红，窄屏上的标签挤压（L-1010 修的现象）会悄悄回来。
     */
    @Test
    fun 功能面板到八键要收窄内边距() {
        val src = TestSources.codeSource("PinyinKeyboardView.kt")
        val body = src.substringAfter("private fun renderFunctionPanel()")
            .substringBefore("private fun buildFunctionButton(")
        assertTrue("收窄逻辑要在面板渲染里", "PANEL_COMPACT_SLOTS" in body)
        assertTrue("按实际按钮数判断", "childCount >= PANEL_COMPACT_SLOTS" in body)
        assertTrue("内边距收到 4dp", "setPadding(dp(4), dp(4), dp(4), dp(4))" in body)
        assertTrue("阈值常量要存在", "const val PANEL_COMPACT_SLOTS = 8" in src)
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
