package com.jinn.inputmethod

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
        }
    }
}
