package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复制 / 启动都不得删历史」的源码守卫（2026-11-14 二轮审计后补）。
 *
 * 背景：首轮修复（`d26052c`）在「历史读不出」的空态上开了一个「连收藏一起清空」的入口。
 * 二轮审计发现该判据是**瞬态**的（`items 空 && total>0` 只说明本次解密没成功：
 * 竞态、Keystore 抖动、真的换钥都会造成同一形态），而且判据按**当前筛选**算、动作按**全库**删
 * ⇒ 一次误触即不可逆地删光全部历史。入口已撤回，本测试钉住「不要再长回来」。
 *
 * 同一轮还收紧了两处非用户动作的删行/格式风险：
 *  - `deleteMissingRows` 用 `File.isFile` 判死行，`isFile` 在 EACCES / IO 抖动时返回 false，
 *    且它经 `deleteAnyByIds` 连收藏一起删 ⇒ 改为只记录不删；
 *  - 密钥探针只数**文本行**（图片行与密钥无关，算进来会让「只剩图片行」永久禁止建钥）。
 */
class ClipboardLossGuardTest {

    private val panel: String by lazy {
        TestSources.codeOf(TestSources.rawSourceOfShortName("ClipboardPanelView.kt"))
    }
    private val hist: String by lazy {
        TestSources.codeOf(TestSources.rawSourceOfShortName("ClipboardHistoryActivity.kt"))
    }
    private val db: String by lazy {
        TestSources.codeOf(TestSources.rawSourceOfShortName("ClipboardDb.kt"))
    }
    private val imgFiles: String by lazy {
        TestSources.codeOf(TestSources.rawSourceOfShortName("ClipboardImageFiles.kt"))
    }

    @Test
    fun `任何入口都不得连收藏一起清空`() {
        // 「含收藏全清」不是修复前的能力，是首轮修复新增的；它唯一的使用理由（读不出）是瞬态判据
        assertFalse("面板不得出现含收藏清空", "includingFavorites" in panel)
        assertFalse("管理页不得出现含收藏清空", "includingFavorites" in hist)
        assertFalse("库层不得再有可分档的清空参数", "includingFavorites" in db)
    }

    @Test
    fun `清空只删非收藏`() {
        val body = TestSources.blockAfter(db, "fun deleteAll(")
        assertTrue("清空必须以 is_favorite = 0 为条件", "is_favorite = 0" in body)
    }

    @Test
    fun `不可读空态不得可点`() {
        val body = TestSources.blockAfter(panel, "private fun updateEmpty(")
        val line = body.lines().first { "isClickable" in it }
        assertTrue("载入中一律不可点：文案还没落定，用上一轮旧值接点击就是盲操作", "loadingPage" in line)
        assertFalse("读不出态不得参与可点判定", "unreadableHistory" in line)
    }

    @Test
    fun `刷新起点必须复位空态判据`() {
        val body = TestSources.blockAfter(panel, "fun refresh(")
        val reset = body.indexOf("unreadableHistory = false")
        val loading = body.indexOf("loadingPage = true")
        assertTrue("刷新起点必须复位 unreadableHistory（否则首帧带着上次会话的旧值）", reset >= 0)
        assertTrue("复位必须排在 loadingPage = true 之前", reset < loading)
        assertTrue("刷新起点必须复位 scanStoppedEarly", "scanStoppedEarly = false" in body)
    }

    @Test
    fun `历史页总数必须在扫描之前取`() {
        val body = TestSources.blockAfter(hist, "private fun loadAsync(")
        val count = body.indexOf("val total = db.count(")
        val loop = body.indexOf("while (true)")
        assertTrue("loadAsync 必须取总数", count >= 0)
        assertTrue("取数必须在扫描之前：扫描后取会被「期间新入库一条」骗成「一条都读不出」", count < loop)
    }

    @Test
    fun `无文件图片行只记录不删`() {
        val body = TestSources.blockAfter(imgFiles, "fun deleteMissingRows(")
        assertFalse("不得再按 File.isFile 判死行并删（抖动即误删，且连收藏一起删）", "deleteAnyByIds" in body)
        assertTrue("至少要留一条日志", "Diagnostics.w" in body)
    }

    @Test
    fun `库重建必须留下线索`() {
        // 平台删库重建无法接管：`SQLiteOpenHelper.onCorruption` 不在 SDK 公开面（compileSdk 34 报
        // "overrides nothing"），没有可覆写点。唯一可做的事是让「库是新建的」这件事有日志 ——
        // 否则「历史全没了」与「密钥失配」在界面上完全同形，事后无据可查。
        val body = TestSources.blockAfter(db, "override fun onCreate(")
        assertTrue("库新建必须打日志（首次安装与损坏重建共用这一条）", "Diagnostics" in body)
        assertTrue("日志必须点明「损坏后重建」这一可能", "损坏" in body)
    }

    @Test
    fun `密钥探针只数文本行`() {
        assertTrue(
            "探针必须限定 content_type = text：图片行与密钥解耦，算进来会让「只剩图片行」永久禁止建钥",
            "count(contentType = CONTENT_TYPE_TEXT)" in db,
        )
    }

    @Test
    fun `不可读文案不得承诺后续行为`() {
        for (src in listOf(panel, hist)) {
            assertFalse(
                "「新的复制仍会正常记录」在拒绝建钥态是假话",
                "新的复制仍会正常记录" in src,
            )
            assertFalse(
                "「清空后可重新开始」在已撤回清空能力后是假话",
                "清空后可重新开始记录" in src,
            )
            assertFalse("「可稍后重试」在迁移失败时是假话（同一段迁移必然再失败）", "可稍后重试" in src)
        }
    }

    // ── 容量额度 / 裁剪顺序 / 生命周期 ──

    @Test
    fun `条数额度只按非收藏计`() {
        // 收藏是用户的显式保留、不占名额。把收藏计入额度时，收藏一旦达到上限，
        // 每次复制都会把非收藏（含刚写入那条）全部删光 —— 用户看到「复制了，历史里没有」。
        assertEquals(0, trimOverflow(3, 3))
        assertEquals(1, trimOverflow(4, 3))
        assertEquals(0, trimOverflow(0, 3))
        assertEquals(0, trimOverflow(5, 0))
    }

    @Test
    fun `条数裁剪的计数必须限定非收藏`() {
        val body = TestSources.blockAfter(db, "private fun trimByCount(")
        assertTrue(
            "计数必须与候选集同源（都只算非收藏）",
            "countByContentType(CONTENT_TYPE_TEXT, TRIM_PRIORITY.first())" in body,
        )
        assertTrue("裁剪删行必须留痕（原先零日志，事故时无法归因）", "Diagnostics.i" in body)
    }

    @Test
    fun `图片额度同样只按非收藏计`() {
        val body = TestSources.blockAfter(db, "fun imageOverflowIds(")
        assertTrue("张数额度必须只数非收藏", "count { !it.favorite }" in body)
        assertTrue(
            "字节额度也必须只算非收藏：收藏自身超预算时否则会把全部非收藏一次删光",
            "rows.filter { !it.favorite }.sumOf" in body,
        )
    }

    @Test
    fun `图片预算停手要留痕`() {
        val body = TestSources.blockAfter(db, "private fun trimImages(")
        assertTrue("只剩收藏仍超预算时必须留 W（否则既看不到现象也看不到原因）", "图片字节裁剪停手" in body)
    }

    @Test
    fun `收藏软上限必须先于字节裁剪`() {
        val body = TestSources.blockAfter(db, "fun trimTo(")
        val fav = body.indexOf("trimFavorites()")
        val bytes = body.indexOf("trimByByteBudget(")
        assertTrue("trimTo 必须调收藏软上限", fav >= 0)
        assertTrue(
            "收藏必须先被压回预算占比，否则调小总上限那一次会把非收藏（含新行）删光",
            fav < bytes,
        )
    }

    @Test
    fun `重建表必须逐列兜 NULL`() {
        val body = TestSources.blockAfter(db, "private fun rebuildKeepingData(")
        assertTrue("必须逐列 COALESCE", "COALESCE(\$col, \$fallback)" in body)
        assertTrue("空密文列要有兜底值", "\"encrypted_content\" to \"''\"" in body)
    }

    @Test
    fun `视图重建要复位载入态`() {
        val body = TestSources.blockAfter(panel, "fun stopBackgroundWork(")
        assertTrue(
            "作废在飞查询后不会再有回调复位 loadingPage，必须在这里清",
            "loadingPage = false" in body,
        )
    }

    @Test
    fun `条目点击的防重入必须 finally 复位`() {
        val body = TestSources.blockAfter(panel, "private fun handleItemClick(")
        assertTrue("必须用 finally 复位（宿主提交链没有兜异常）", "finally" in body)
        assertTrue("复位语句必须在", "isPasting = false" in body)
    }

    @Test
    fun `重算的解密必须在事务之外`() {
        val body = TestSources.blockAfter(db, "fun reclassifyAll(")
        val decrypt = body.indexOf("ClipboardCrypto.decrypt")
        val begin = body.indexOf("beginTransaction")
        assertTrue("必须先解密", decrypt >= 0)
        assertTrue("解密必须在开事务之前（否则整趟独占唯一连接）", decrypt < begin)
    }
}
