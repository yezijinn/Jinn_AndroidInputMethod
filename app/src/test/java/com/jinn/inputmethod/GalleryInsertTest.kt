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
        assertEquals("发起点、落库与面板选图都要用图库口径", 3, uses)
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

    /**
     * 图库面板打开后，退出口落在「图库」键本身（用户 2026-10-07 指定，参考数字层 / 剪贴板的做法）。
     *
     * 面板里只剩三个按钮：刷新 / 文件单选 / 自动返回（开关）。原先那个独立的「返回」按钮撤掉 ——
     * 面板盖住字母区，退出口要落在用户刚点过的那一键上，红色粗体一眼可见。
     */
    @Test
    fun 图库面板的退出口落在图库键上() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val body = view.substringAfter("if (hostImageCapable) {").substringBefore("// 「翻译」键")
        assertTrue("构建后立即同步一次状态", "refreshGalleryButton()" in body)
        assertTrue("点击时收起面板", "hideGalleryPanel()" in body)
        val refresh = view.substringAfter("private fun refreshGalleryButton()")
            .substringBefore("fun setTranslating")
        assertTrue("就地改文案", "\"返回\" else \"图库\"" in refresh)
        assertTrue("就地改颜色（提示红）", "skin.hintRed" in refresh)
        assertTrue(
            "展开态只留大号「返回」，底部小字整条收起",
            "hintView?.visibility = if (active) View.GONE else View.VISIBLE" in refresh,
        )
        assertTrue(
            "展开与收起都要刷一次（定义之外至少两处调用）",
            view.split("refreshGalleryButton()").size - 1 >= 3,
        )

        val panel = TestSources.codeSource("GalleryPanelView.kt")
        assertTrue("面板按钮：刷新", "\"刷新\"" in panel)
        assertTrue("面板按钮：单选", "\"单选\"" in panel)
        assertTrue("面板按钮：自返", "\"自返\"" in panel)
        assertTrue("翻页控件与页码", "\"←\"" in panel && "\"→\"" in panel && "\"%d/%d\"" in panel)
        assertFalse("面板里不再有独立的关闭按钮", "listener?.onClose()" in panel)
        assertTrue("开关态用提示红与普通前景色区分", "galleryAutoReturn" in panel && "skin.hintRed" in panel)

        // L-1024：图库面板是 init 里最后 addView 的那个，同屏时盖在最上层 ——
        // 打开其它面板必须把它收起来，否则用户看到的是「点了没反应」、键还卡在红色「返回」态
        val dir = view.substringAfter("private fun showDirectionPanel()").substringBefore("字母区: 隐藏三行")
        val clip = view.substringAfter("fun showClipboardPanel()").substringBefore("fun hideClipboardPanel()")
        val search = view.substringAfter("fun showSearchPanel()").substringBefore("fun hideSearchPanel()")
        assertTrue("方向面板要收起图库面板", "hideGalleryPanel()" in dir)
        assertTrue("剪贴板面板要收起图库面板", "hideGalleryPanel()" in clip)
        assertTrue("搜索面板要收起图库面板", "hideGalleryPanel()" in search)
    }

    /** 「自动返回」开着时，点一张图就收起面板回打字键盘（用户 2026-10-07 指定） */
    @Test
    fun 自动返回开关接在选图上() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val pick = view.substringAfter("override fun onPick(uri: android.net.Uri)")
            .substringBefore("override fun onRebindRequested")
        assertTrue("先交付插入，再谈收不收面板", "onGalleryImagePicked(uri)" in pick)
        assertTrue("开着才收起", "if (Prefs(context).galleryAutoReturn)" in pick)
        assertTrue("收起动作就是已有那一个", "hideGalleryPanel()" in pick)

        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("偏好项默认关", "boolOr(KEY_GALLERY_AUTO_RETURN, false)" in prefs)
        assertTrue("导出侧带走", "put(KEY_GALLERY_AUTO_RETURN, galleryAutoReturn)" in prefs)
        assertTrue("导入侧还原", "KEY_GALLERY_AUTO_RETURN -> asBool(v)" in prefs)
    }

    /**
     * 绑定异常入口必须是**常驻行** —— 只切可见性，不再 addView（L-1025）。
     *
     * 它曾经在 render 里 addView，而 render 每次展开都跑一次，于是每进一次图库面板就多一个
     * 「去设置里绑定」按钮（用户实测进 4 次出现 4 个）。改动这一处时别改回 addView。
     */
    @Test
    fun 绑定入口行常驻不再累积() {
        val panel = TestSources.codeSource("GalleryPanelView.kt")
        val render = panel.substringAfter("private fun render(state: State?)")
            .substringBefore("private fun showList(")
        assertFalse("render 里不得再往面板挂入口行", "addView(row" in render)
        assertTrue("改成按状态切可见性", "rebindRow.visibility =" in render)
        assertEquals("常驻行只在 init 里挂一次", 1, panel.split("addView(rebindRow").size - 1)
    }

    /** 缩略图布局：默认一行 5 张、行高 76dp，都能从面板的「布局」键调；读数两侧都归一 */
    @Test
    fun 缩略图布局可调且默认五张() {
        val prefs = TestSources.codeSource("Prefs.kt")
        assertTrue("默认一行 5 张", "GALLERY_COLUMNS_DEFAULT = 5" in prefs)
        assertTrue("张数在 setter 里归一", "coerceIn(GALLERY_COLUMNS_MIN" in prefs)
        assertTrue("行高同样归一", "coerceIn(GALLERY_CELL_HEIGHT_MIN" in prefs)
        assertTrue("张数进导出白名单", "put(KEY_GALLERY_COLUMNS, galleryColumns)" in prefs)
        assertTrue("行高进导入白名单", "KEY_GALLERY_CELL_HEIGHT_DP -> asInt(v)" in prefs)

        val panel = TestSources.codeSource("GalleryPanelView.kt")
        assertTrue("标题行有「布局」键", "smallButton(TEXT_LAYOUT)" in panel)
        assertEquals("调节行含两组 −/+", 2, panel.split("smallButton(TEXT_MINUS)").size - 1)
        assertTrue("调完就地重排", "private fun rebuildGrid()" in panel && "fillPage()" in panel)
        assertTrue("网格按可调参数铺", "page.chunked(columns)" in panel && "dp(cellHeightDp)" in panel)

        // 「布局」是临时操作，不能永久赖在屏幕上（用户 2026-10-07 要求）：
        // 收面板时收回，且每次重新展开都从收起态开始
        val hidden = panel.substringAfter("fun onPanelHidden()").substringBefore("fun applySkin(")
        assertTrue("收起面板时把调节行收回", "tuneRow.visibility = GONE" in hidden)
        val shown = panel.substringAfter("fun onPanelShown()").substringBefore("val tree = Prefs")
        assertTrue("每次展开也从收起态开始", "tuneRow.visibility = GONE" in shown)
    }

    /**
     * 「文件单选」必须真的走系统选择器（L-1026）。
     *
     * 它原先接的是 `onOpenGallery`（= 展开键盘内面板），而按钮自身刚把面板收起来 ⇒
     * 点了等于把面板重开一遍，什么都没发生 —— 用户直接问「这个按钮干嘛用的」。
     */
    @Test
    fun 文件单选走系统选择器() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        assertTrue("接口里有这个方法", "fun onPickFromSystemGallery()" in view)
        val pick = view.substringAfter("override fun onSystemPicker()")
            .substringBefore("visibility = View.GONE")
        assertTrue("面板里的按钮接系统选择器回调", "onPickFromSystemGallery()" in pick)
        assertFalse("不再接面板展开", "listener?.onOpenGallery()" in pick)

        val ime = TestSources.codeSource("JinnIme.kt")
        val impl = ime.substringAfter("override fun onPickFromSystemGallery()")
            .substringBefore("override fun onOpenGallerySettings()")
        assertTrue("实现里真的打开选图页", "openGalleryPicker()" in impl)
    }

    /**
     * 缩略图走「宁可糊、只要快」的路线（用户 2026-10-07：以最快速度优先，其他可以舍弃）。
     *
     * 这些都是刻意为之、且容易被当成「还能优化回去」的地方，逐条钉住：
     *  - 一次输入流解完（**不**探尺寸）—— 那次多出来的 SAF 开流是首屏最贵的一步；
     *  - `RGB_565` + 按每行张数两档采样；
     *  - 只解可见行、并发解、在途去重、滚动防抖；
     *  - 缓存够装整页（滚回去不重解）。
     */
    @Test
    fun 缩略图按省算路线解码() {
        val panel = TestSources.codeSource("GalleryPanelView.kt")
        assertTrue("用 RGB_565 解码", "inPreferredConfig = Bitmap.Config.RGB_565" in panel)
        assertTrue("固定档一次解完", "private fun decodeThumb(uri: Uri, sample: Int)" in panel)
        assertFalse("不许退回两段式探尺寸", "inJustDecodeBounds" in panel)
        assertTrue("采样率按每行张数两档", "COMPACT_COLUMNS" in panel && "THUMB_SAMPLE_COMPACT" in panel)
        assertTrue("并发解 + 在途去重", "thumbPool.execute" in panel && "inFlight.add(key)" in panel)
        assertTrue("缓存够装整页", "THUMB_CACHE_BYTES = 12 * 1024 * 1024" in panel)
        assertTrue("布局变化后丢掉旧尺寸缓存", "thumbCache.evictAll()" in panel)
        assertTrue("一页就这么多张", "const val PAGE_SIZE = 24" in panel)
        assertTrue("列表只受安全阀约束", "take(MAX_LISTED)" in panel)
        assertTrue("解码范围是整页", "(pageIndex * PAGE_SIZE)" in panel)
        assertFalse("不再有滚动补解那套", "scheduleVisibleThumbs" in panel)
    }

    /**
     * 三条用户能感觉到、但不做也不会有人报警的地方（L-1031 / L-1032 / L-1027）。
     *
     *  - 插入图片的解码必须在后台：`commitContent` 的回调本来就在主线程；
     *  - 图库面板打开时要清拼音缓冲：插入走 `commitContent`，不会替用户把拼音上屏；
     *  - 缩略图档位要在任务内现算：提交时算好的那份会被「布局」改动作废。
     */
    @Test
    fun 三条可感问题不许回退() {
        val host = TestSources.codeSource("ReceivingEditText.kt")
        val insert = host.substringAfter("private fun insertImage(uri: Uri)")
            .substringBefore("private fun decodeSampledBitmap")
        assertTrue("解码丢后台", "BackgroundIo.run" in insert)
        assertTrue(
            "解码调用要在后台块里面",
            insert.indexOf("BackgroundIo.run") < insert.indexOf("decodeSampledBitmap"),
        )
        assertTrue("回主线程贴图", "post { attachDecoded(" in insert)

        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        val gallery = view.substringAfter("fun showGalleryPanel()").substringBefore("val density")
        assertTrue("面板打开时清拼音缓冲", "clearComposingState()" in gallery)

        val panel = TestSources.codeSource("GalleryPanelView.kt")
        assertTrue("档位在任务内现算", "decodeThumb(uri, sampleFor(columns))" in panel)
        assertFalse("不许退回提交时算好的档位", "val sample = sampleFor(cols)" in panel)
    }

    /**
     * 方向面板第三行是「到开头 / 到末尾」（需求：把复制粘贴换成全文跳转）。
     *
     * 两个动作必须按**绝对下标**执行：长文档下文本窗口只是光标附近一段，全文两端常在窗口之外，
     * 走 `moveCursor` 那套窗口内相对下标会被 `toWindowOffset` 判为「不在窗口内」而放弃。
     */
    @Test
    fun 方向面板的到开头到末尾走绝对下标() {
        val view = TestSources.codeSource("PinyinKeyboardView.kt")
        assertTrue("枚举含两个全文端点动作", "DOC_START" in view && "DOC_END" in view)
        assertFalse("复制粘贴不再挂方向面板", "\"复制\", DirectionAction.COPY" in view)
        val panel = view.substringAfter("private fun ensureDirectionPanel()")
            .substringBefore("directionPanel = panel")
        assertTrue("第三行是到开头", "\"到开头\", DirectionAction.DOC_START" in panel)
        assertTrue("第三行是到末尾", "\"到末尾\", DirectionAction.DOC_END" in panel)

        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue("分发进移动逻辑", "DirectionAction.DOC_END -> moveOrExtend(action)" in ime)
        val target = ime.substringAfter("private fun absoluteDocTarget(")
            .substringBefore("private fun moveCursor(")
        assertTrue("到开头 = 绝对 0", "DirectionAction.DOC_START -> 0" in target)
        assertTrue("到末尾按剩余文本长度算", "range.end + after.length" in target)
        assertTrue("读取带上限", "getTextAfterCursor(DOC_JUMP_MAX_CHARS, 0)" in target)
        assertEquals(
            "普通模式与拖选模式都要接上",
            2,
            ime.split("absoluteDocTarget(connection, action, range)").size - 1,
        )
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
