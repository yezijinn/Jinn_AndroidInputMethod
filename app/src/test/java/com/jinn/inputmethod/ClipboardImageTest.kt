package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板图片功能的守卫（2026-10-10 实施计划 §7）。
 *
 * 全部是纯 JVM 可测项：`MediaStore` / `GridView` / `commitContent` / 位图解码这些
 * 需要 Android 环境的行为靠真机验收（实施计划 §6 的十二项总表），这里钉的是
 * **纯函数、常量与源码形态**（形态钉的都是「回退之后单测依然全绿」的位置）。
 */
class ClipboardImageTest {

    // ── 哈希与文件名 ─────────────────────────────────────────

    @Test
    fun 图片哈希与文本哈希空间隔离() {
        val bytes = "hello".toByteArray()
        val img = ClipboardDb.imageHash(bytes)
        assertTrue("图片哈希必须带 img: 前缀", img.startsWith(ClipboardDb.IMAGE_HASH_PREFIX))
        // 同一字节序列：图片哈希 = 前缀 + 同一份 sha256 hex（两套哈希共用一个唯一索引，靠前缀隔离）
        assertEquals(ClipboardDb.IMAGE_HASH_PREFIX + ClipboardDb.stableHash("hello"), img)
        assertNotEquals("文本哈希是裸 hex，绝不能等于图片哈希", ClipboardDb.stableHash("hello"), img)
    }

    @Test
    fun 文件名由哈希派生且不含前缀与冒号() {
        val hash = ClipboardDb.IMAGE_HASH_PREFIX + "ab12"
        assertEquals("ab12", ClipboardImageFiles.stem(hash))
        assertEquals("ab12.enc", ClipboardImageFiles.encName(hash))
        assertEquals("ab12.thumb", ClipboardImageFiles.thumbName(hash))
        assertFalse("冒号不能进文件名", ClipboardImageFiles.encName(hash).contains(':'))
    }

    // ── 文件 GC ──────────────────────────────────────────────

    @Test
    fun 孤儿文件判定跳过保护窗内的新文件() {
        val now = 1_000_000L
        val existing = listOf(
            "a.enc" to (now - ClipboardImageFiles.GC_PROTECT_MS - 1),   // 老孤儿 ⇒ 删
            "b.thumb" to (now - 1_000),                                 // 保护窗内（可能在途写入）⇒ 留
            "c.enc" to (now - ClipboardImageFiles.GC_PROTECT_MS - 1),   // 在库内（keep）⇒ 留
        )
        assertEquals(listOf("a.enc"), ClipboardImageFiles.staleNames(existing, setOf("c.enc"), now))
    }

    // ── 预算淘汰（图片与文本各自独立） ────────────────────────

    @Test
    fun 图片预算淘汰最旧非收藏() {
        val rows = listOf(
            ClipboardRowSize(1, 10, favorite = false, image = true),
            ClipboardRowSize(2, 10, favorite = true, image = true),
            ClipboardRowSize(3, 10, favorite = false, image = true),
        )
        // 张数超限（3 > 2）：从最旧开始删，收藏跳过
        assertEquals(listOf(1L), ClipboardDb.imageOverflowIds(rows, maxItems = 2, maxBytes = 0))
        // 字节超限（30 > 20）：删一条即落回
        assertEquals(listOf(1L), ClipboardDb.imageOverflowIds(rows, maxItems = 0, maxBytes = 20))
        // 全是收藏 ⇒ 无可删（宁可超限也不删用户明确保留的内容）
        assertTrue(ClipboardDb.imageOverflowIds(rows.map { it.copy(favorite = true) }, 1, 1).isEmpty())
    }

    @Test
    fun 文本字节淘汰不碰图片行() {
        val rows = listOf(
            ClipboardRowSize(1, 10, favorite = false, image = true),   // 图片：文本预算跳过它
            ClipboardRowSize(2, 10, favorite = false),
        )
        assertEquals(listOf(2L), ClipboardDb.overflowIdsForByteBudget(rows, maxBytes = 5))
    }

    // ── 过滤维度 ─────────────────────────────────────────────

    @Test
    fun 图片过滤走内容类型维度而非分类标签() {
        val img = ClipboardFilter.of(ClipboardFilter.PSEUDO_IMAGE)
        assertNull("图片不是 category 取值（塞进 LIKE 体系会恒空）", img.category)
        assertFalse(img.favoritesOnly)
        assertEquals(ClipboardDb.CONTENT_TYPE_IMAGE, img.contentType)
        // 收藏伪分类仍走独立标签列；内容类型收窄到文本（收藏的图片在收藏分类的网格区里）
        val fav = ClipboardFilter.of(ClipboardFilter.PSEUDO_FAVORITE)
        assertTrue(fav.favoritesOnly)
        assertEquals(ClipboardDb.CONTENT_TYPE_TEXT, fav.contentType)
    }

    // ── 编解码纯函数 ─────────────────────────────────────────

    @Test
    fun 缩略图采样率是二的幂且不小于目标() {
        assertEquals(1, ClipboardImageCodec.sampleSizeFor(512, 384, 512))
        assertEquals(2, ClipboardImageCodec.sampleSizeFor(1024, 768, 512))
        // 4000 长边：/2=2000 ≥512 →2，/2=1000 ≥512 →4，/2=500 <512 停 ⇒ 4（解码后 1000px，∈[512,1024)）
        assertEquals(4, ClipboardImageCodec.sampleSizeFor(4000, 3000, 512))
        assertEquals("非法尺寸给 1（不崩）", 1, ClipboardImageCodec.sampleSizeFor(0, 0, 512))
        assertEquals("非法目标给 1", 1, ClipboardImageCodec.sampleSizeFor(100, 100, 0))
    }

    // ── 数据层形态（读路径与裁剪口径） ────────────────────────

    @Test
    fun 图片行读路径不解密且内容为空串() {
        val src = TestSources.codeSource("ClipboardDb.kt")
        assertTrue("图片行必须有独立分支（不解密占位密文）", "if (contentType == CONTENT_TYPE_IMAGE)" in src)
        assertTrue("图片行的 content 恒为空串（搜索/渲染/备份/预算都当文本读它）", "content = \"\"," in src)
        assertTrue("图片分支必须填 ImageMeta", "image = ImageMeta(" in src)
        assertTrue("分页列清单必须带图片元数据列", "image_bytes, image_w, image_h, image_mime" in src)
    }

    @Test
    fun 文本裁剪只统计文本行且图片预算独立() {
        val src = TestSources.codeSource("ClipboardDb.kt")
        assertTrue("条数裁剪按文本计数", "countByContentType(CONTENT_TYPE_TEXT)" in src)
        assertTrue("字节候选集跳过图片行", "if (row.image) continue" in src)
        assertTrue("图片预算独立成段", "private fun trimImages()" in src)
        assertTrue("收藏字节按行类型取真实体积", "CASE WHEN content_type = ?" in src)
        // 图片裁剪必须**只扫图片行**：这两条是「预算分离」的落脚点，缺一条就会退化成
        // 「文本/图片互相淘汰」（未来重构最容易被合并掉的地方）
        assertTrue("图片裁剪 SQL 必须限定内容类型", "WHERE content_type = ? " in src)
        assertTrue("图片裁剪必须绑定 IMAGE 常量", "arrayOf(CONTENT_TYPE_IMAGE)" in src)
    }

    @Test
    fun 图片入库失败要清文件且不留死行() {
        val src = TestSources.codeSource("ClipboardController.kt")
        assertTrue("入库失败必须删掉已写的文件", "ClipboardImageFiles.deleteFor(context, hash)" in src)
        assertTrue("入库成功后收孤儿", "ClipboardImageFiles.gc(context, db)" in src)
        // BUG.md L-1228：尺寸读不出（HEIC / AVIF）不再整条丢弃，与「缩略图写失败仍存原图」同一降级口径
        assertTrue(
            "尺寸读不出要按原字节入库",
            "if (size == null) Diagnostics.w(TAG, \"图片解不出尺寸，按原字节入库（网格显示灰块）\")" in src &&
                "width = size?.get(0) ?: 0" in src,
        )
        assertTrue("图片采集走长活池（不占短活队列）", "BackgroundIo.runLong { saveImageFromUri(uri, type) }" in src)
    }

    @Test
    fun 删除路径必须立即回收文件() {
        // 单条删除/转移：走 deleteFor（精准），**不能**只走 gc —— 10 分钟保护窗会把刚入的图
        // 当「在途写入」留在盘上（2026-10-10 真机实证：转移成功后 .enc/.thumb 仍在）
        val hist = TestSources.codeSource("ClipboardHistoryActivity.kt")
        assertTrue("历史页单条删除要精准删", "ClipboardImageFiles.deleteFor(this@ClipboardHistoryActivity, item.contentHash)" in hist)
        assertTrue("历史页转移成功要精准删", "ClipboardImageFiles.deleteFor(this, item.contentHash)" in hist)
        assertTrue(
            "历史页批量删除要用短保护窗",
            "ClipboardImageFiles.DELETE_GC_PROTECT_MS" in hist,
        )
        val panel = TestSources.codeSource("ClipboardPanelView.kt")
        assertTrue("面板单条删除要精准删", "ClipboardImageFiles.deleteFor(context, item.contentHash)" in panel)
        assertTrue("面板清空要用短保护窗", "ClipboardImageFiles.gc(context, db, ClipboardImageFiles.DELETE_GC_PROTECT_MS)" in panel)
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue("IME 转移成功要精准删", "ClipboardImageFiles.deleteFor(this, item.contentHash)" in ime)
        assertTrue("启动 GC 保持长保护窗（在途写入兜底）", "ClipboardImageFiles.gc(this, db)" in ime)
    }

    @Test
    fun 存储占用页要单列剪贴板图片() {
        val src = TestSources.codeSource("StorageUsage.kt")
        assertTrue(
            "图片目录要单列（否则混进「其他」，用户看不到也管不了）",
            "ClipboardImageFiles.DIR_NAME" in src && "\"剪贴板图片\"" in src,
        )
    }

    @Test
    fun 采集分支前置于文本取文() {
        val src = TestSources.codeSource("ClipboardController.kt")
        val img = src.indexOf("if (handleImageClip(first)) return")
        val txt = src.indexOf("ClipboardStore.itemTextResult(clip.getItemAt(0), appContext)")
        assertTrue("两个分支都要在（图片分支 + 共用取文入口）", img in 0 until txt && txt > 0)
        assertTrue("采集侧读流必须走字节版带预算入口", "ClipboardStore.readBytesWithBudget(" in src)
    }

    // ── 界面层形态 ───────────────────────────────────────────

    @Test
    fun 面板与历史页的图片入口就位() {
        val panel = TestSources.codeSource("ClipboardPanelView.kt")
        assertTrue("面板要有图片分类键", "btnCategoryImage" in panel)
        // 图片不进文本列表（2026-10-11）：文本分类按 content_type='text' 取数，图片只在「图片」分类的网格里
        assertTrue("文本列表不再渲染图片占位行", "TEXT_IMAGE_PLACEHOLDER" !in panel)
        assertTrue("图片长按要出三个动作", "imageAction(ClipboardImageAction.Copy)" in panel)
        // 「布局」键：图片分类下取代搜索（图片不进搜索，键位让给布局调节），可在面板内改每行张数 / 行高
        assertTrue("面板要有布局键", "btnLayout = tabButton(\"布局\")" in panel)
        assertTrue(
            "布局键只在图片分类露面",
            "btnLayout.visibility = if (imageMode) View.VISIBLE else View.GONE" in panel,
        )
        assertTrue("调节行改每行张数", "Prefs(context).galleryColumns = next" in panel)
        assertTrue("调节行改行高", "Prefs(context).galleryCellHeightDp = next" in panel)
        // BUG.md L-1239：分页取数必须与首屏同源过滤 —— 漏 contentType 会让第二页起图片混回文本列表
        assertTrue(
            "面板分页取数必须带内容类型",
            "cursor, ClipboardPrefs.of(context).panelPageItems," in panel &&
                "filter.category, filter.favoritesOnly, filter.contentType," in panel,
        )
        // BUG.md L-1240：收藏分类排除图片行后，网格必须能按收藏筛选，且有可见的切换入口
        assertTrue("网格取数要能吃收藏筛选", "null, favoritesOnly, ClipboardDb.CONTENT_TYPE_IMAGE" in
            TestSources.codeSource("ClipboardImageGridView.kt"))
        assertTrue("面板要有只看收藏切换", "toggleFavOnly()" in panel)
        assertTrue(
            "历史页也要有只看收藏切换",
            "text = if (imageFavOnly) TEXT_IMAGE_FAV_ALL else TEXT_IMAGE_FAV_ONLY" in
                TestSources.codeSource("ClipboardHistoryActivity.kt"),
        )
        // BUG.md L-1229：裁剪删掉图片行后必须回收密文文件（行与文件是两套存储）
        assertTrue(
            "裁剪后要收孤儿文件",
            "ClipboardImageFiles.gc(appContext, this, ClipboardImageFiles.DELETE_GC_PROTECT_MS)" in
                TestSources.codeSource("ClipboardDb.kt"),
        )
        val hist = TestSources.codeSource("ClipboardHistoryActivity.kt")
        assertTrue("历史页要有图片 chips", "FILTER_IMAGE to \"图片\"" in hist)
        assertTrue("历史页要有网格", "R.id.hist_grid" in hist)
        assertTrue("历史页文本列表不再渲染图片占位行", "TEXT_IMAGE_PLACEHOLDER" !in hist)
        assertTrue("历史页要有导出全部图片", "TEXT_IMAGE_EXPORT_ALL" in hist)
        assertTrue("历史页要有删图片", "deleteByContentType" in hist)
        assertTrue("删图片后要收文件", "ClipboardImageFiles.gc" in hist)
    }

    @Test
    fun 图片视图都已挂载到各自容器() {
        val panel = TestSources.codeSource("ClipboardPanelView.kt")
        assertTrue("网格要挂进面板", "addView(imageGrid," in panel)
        assertTrue("网格回调要绑定", "ClipboardImageGridView.Listener" in panel)
        val hist = TestSources.codeSource("ClipboardHistoryActivity.kt")
        assertTrue("历史页网格要绑定适配器", "grid.adapter = gridAdapter" in hist)
        assertTrue("历史页图片动作组就位", "private fun imageAction(" in hist)
        assertTrue("历史页预览与菜单就位", "private fun showImageDialog(" in hist && "private fun showImageMenu(" in hist)
        assertTrue("历史页导出全部图片就位", "private fun exportAllImages()" in hist)
        val page = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        assertTrue("图片容量卡片要挂上", "PageStyle.addCard(list, card4)" in page)
        assertTrue("记录图片开关要挂上", "makeSwitch(TEXT_IMAGE_ENABLED" in page)
        val ime = TestSources.codeSource("JinnIme.kt")
        assertTrue("IME 图片上屏就位", "private fun pasteClipboardImage(" in ime)
        assertTrue("IME 图片动作就位", "private fun handleClipboardImageAction(" in ime)
        assertTrue("启动 GC 就位", "ClipboardImageFiles.deleteMissingRows(this, db)" in ime)
    }

    @Test
    fun 网格走共享缩略图加载器并做身份校验() {
        val grid = TestSources.codeSource("ClipboardImageGridView.kt")
        assertTrue("要复用图库布局偏好", "prefs.galleryColumns" in grid && "galleryCellHeightDp" in grid)
        assertTrue("缩略图走共享加载器", "ClipboardThumbLoader.load(context, key, targetPx)" in grid)
        assertTrue("回调要按 hash 校验（cell 复用会串图）", "cell.tag == hash" in grid)
        assertTrue("网格要有收藏角标", "TEXT_FAVORITE" in grid)
        assertTrue("取数只看图片", "ClipboardDb.CONTENT_TYPE_IMAGE" in grid)
        val hist = TestSources.codeSource("ClipboardHistoryActivity.kt")
        assertTrue("历史页网格也要校验身份", "cell.tag == hash" in hist)
        assertTrue("历史页网格也走共享加载器", "ClipboardThumbLoader.load(" in hist)
        assertTrue(
            "历史页网格的点击/长按必须走 GridView 的 item 监听（cell 自己 clickable 时本机收不到手势）",
            "grid.setOnItemClickListener" in hist && "grid.setOnItemLongClickListener" in hist,
        )
        assertTrue("网格 cell 不得自挂点击监听", "cell.setOnClickListener" !in hist)
        val loader = TestSources.codeSource("ClipboardThumbLoader.kt")
        assertTrue("缩略图必须先解密（密文落盘）", "ClipboardCrypto.decryptBytes(enc)" in loader)
    }

    @Test
    fun 图片模式不被文本列表路径抢回前台() {
        val panel = TestSources.codeSource("ClipboardPanelView.kt")
        assertTrue(
            "面板空态在图片模式下必须让位（收藏/删除的异步回调会走 updateEmpty）",
            "if (inImageMode()) return" in panel,
        )
        assertTrue(
            "面板清空在图片模式下要刷新网格并收文件（refresh 只刷文本列表）",
            "if (imageMode) imageGrid.show(tid) else refresh(resetScroll = true)" in panel,
        )
        assertTrue(
            "布局调节行只在图片分类展开（切走要收起）",
            "if (!imageMode) tuneRow.visibility = View.GONE" in panel,
        )
        val page = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        assertTrue(
            "自定义页要有缩略图布局项（与图库 / 面板同一对参数）",
            "TEXT_LAYOUT_COLUMNS" in page && "layoutPrefs.galleryColumns = it" in page,
        )
        val hist = TestSources.codeSource("ClipboardHistoryActivity.kt")
        assertTrue(
            "历史页要按分类切网格可见性",
            "grid.visibility = if (imageMode) View.VISIBLE else View.GONE" in hist,
        )
        assertTrue("历史页进图片分类要退出多选（图片分类不提供多选）", "multiSelect = false" in hist)
        assertTrue(
            "图片分类要收起搜索行（搜索范围只有文本，留着只会给出「暂无图片」）",
            "searchRow.visibility = if (imageMode) View.GONE else View.VISIBLE" in hist,
        )
        assertTrue(
            "切进图片分类要清掉残留关键词（否则图片全被关键词滤掉）",
            "if (category == FILTER_IMAGE && keyword.isNotEmpty())" in hist,
        )
    }

    @Test
    fun 搜索与配置备份都排除图片() {
        assertTrue(
            "搜索必须只看文本（图片 content 恒空串，进窗口只会白解密）",
            "contentType = ClipboardDb.CONTENT_TYPE_TEXT" in TestSources.codeSource("SearchPanelView.kt"),
        )
        assertTrue(
            "配置备份只导文本（图片不进备份）",
            "ClipboardDb.CONTENT_TYPE_TEXT," in TestSources.codeSource("ConfigBackupManager.kt"),
        )
    }

    @Test
    fun 自动备份三域都排除图片目录() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = TestSources.codeOf(
                TestSources.rawSource("src/main/res/xml/$name", "app/src/main/res/xml/$name"),
            )
            assertTrue("$name 必须排除 filesDir/clipboard/", "path=\"clipboard/\"" in xml)
        }
        // data_extraction_rules 里云备份与设备迁移两个域各有一份清单
        val extraction = TestSources.codeOf(
            TestSources.rawSource(
                "src/main/res/xml/data_extraction_rules.xml",
                "app/src/main/res/xml/data_extraction_rules.xml",
            ),
        )
        assertEquals(
            "云备份 + 设备迁移都要排（只排一处会让敏感图上云）",
            2,
            Regex("""path="clipboard/" """.trim()).findAll(extraction).count(),
        )
    }

    // ── 偏好与动作能力 ───────────────────────────────────────

    @Test
    fun 图片偏好常量与键就位() {
        assertEquals(300, ClipboardPrefs.DEFAULT_IMAGE_MAX_ITEMS)
        assertEquals(20, ClipboardPrefs.DEFAULT_IMAGE_ITEM_MB)
        assertEquals(200, ClipboardPrefs.DEFAULT_IMAGE_TOTAL_MB)
        assertTrue(
            "上界必须容得下默认值",
            ClipboardPrefs.MAX_IMAGE_ITEMS_CAP > ClipboardPrefs.DEFAULT_IMAGE_MAX_ITEMS,
        )
        val prefs = TestSources.codeSource("ClipboardPrefs.kt")
        // 三项都要钳位（读侧 + 写侧 + 备份导入侧同一组上下界）：漏钳位会让备份包里的越界值
        // 直接进库（导入是他机文件，属不可信输入）
        assertTrue("张数上限必须钳位", "coerceIn(1, MAX_IMAGE_ITEMS_CAP)" in prefs)
        assertTrue("体积上限必须钳位", "coerceIn(MIN_IMAGE_TOTAL_MB, MAX_IMAGE_TOTAL_MB)" in prefs)
        assertTrue("单张上限必须钳位", "coerceIn(MIN_IMAGE_ITEM_MB, MAX_IMAGE_ITEM_MB)" in prefs)
        for (key in listOf(
            "KEY_IMAGE_ENABLED", "KEY_IMAGE_MAX_ITEMS", "KEY_IMAGE_MAX_TOTAL_MB", "KEY_IMAGE_MAX_ITEM_MB",
        )) {
            assertTrue("$key 必须进备份导出", "out[$key]" in prefs)
            assertTrue("$key 必须进备份导入", "KEY_IMAGE_MAX_TOTAL_MB ->" in prefs || "$key ->" in prefs)
        }
        assertTrue("原子保存要含图片键", "editor.putBoolean(KEY_IMAGE_ENABLED" in prefs)
    }

    @Test
    fun 自定义页只写草稿不直接落盘() {
        val page = TestSources.codeSource("ClipboardCustomizeActivity.kt")
        assertTrue("图片参数要有卡片与开关", "TEXT_IMAGE_TITLE" in page && "TEXT_IMAGE_ENABLED" in page)
        assertTrue("图片参数要进草稿模型", "val imageMaxItemMb: Int," in page)
        assertTrue("保存仍走原子入口", "imageMaxTotalMb = target.imageMaxTotalMb," in page)
        assertTrue("收紧判定要含图片预算（保存会真删）", "draft.imageMaxTotalMb < snapshot.imageMaxTotalMb" in page)
    }
}
