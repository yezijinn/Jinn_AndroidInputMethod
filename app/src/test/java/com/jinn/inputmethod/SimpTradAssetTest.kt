package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 简繁资产与**加载端口径**的一致性守卫（2026-09-27）。
 *
 * 三张简繁表都是生成物，判据必须与 `PinyinEngine` 的加载代码逐条对齐，否则会出现
 * 「生成端收下了、运行期静默丢弃」的隐性错配（`BUG.md` L-52 / L-58）：
 *
 *  - `simp_trad.txt` / `simplify.txt` 是**字级**表，加载端判据是 `String.length == 1`（UTF-16 语义）——
 *    非 BMP 字符是代理对（长度 2）⇒ 生成进去也永远用不到，只是白占资产体积；
 *  - `simp_trad_words.txt` 是**词级**表，OpenCC 右侧允许多候选（空格分隔、按优先序）：
 *    必须**先取首候选再判等长**，否则那一整条被丢 —— 繁体模式下这些词退化成逐字而**转出别字**
 *    （反复→「反復」应作「反覆」）。
 */
class SimpTradAssetTest {

    private fun assetText(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (!f.isFile) continue
            // ⚠ `.xz` 必须解压后再当文本读：直接 readText() 拿到的是压缩字节（守卫会在垃圾数据上
            // 「全都不含」地通过/失败 —— 上一版就这么写的，跑门禁才发现）
            return if (name.endsWith(".xz")) {
                XZInputStream(f.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
            } else {
                f.readText()
            }
        }
        throw AssertionError("未找到 asset $name")
    }

    /** `简<TAB>繁` 词条（跳过注释行与格式不合的行） */
    private fun pairs(name: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in assetText(name).lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.count { it == '\t' } != 1) continue
            val (a, b) = t.split("\t")
            out[a] = b
        }
        return out
    }

    /**
     * 词级消歧表必须收下「多候选」条目里逐字映射会出错的那批。
     *
     * 判据取自 OpenCC `STPhrases`（右侧形如 `不準 不准`）：这些词的**首候选**既不等于原词、
     * 也不等于逐字映射结果；漏收就会走逐字而转错。
     */
    @Test
    fun 词级消歧表必须收下多候选首项() {
        val words = pairs("simp_trad_words.txt.xz")
        val golden = mapOf(
            "反复" to "反覆", "发卡" to "髮卡", "白干" to "白乾", "回复" to "回覆", "干点" to "乾點",
            "借词" to "藉詞", "冲垮" to "沖垮", "冷面" to "冷麪", "谷风" to "穀風", "金杯" to "金盃",
        )
        for ((simp, trad) in golden) {
            assertEquals("词级表应含「$simp → $trad」（右侧多候选取首项）", trad, words[simp])
        }
    }

    /** 端到端：用**真实资产**装载后，「反复」必须整体作「反覆」（逐字路径只会给「反復」） */
    @Test
    fun 真实资产下反复应作反覆() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts("fa\t发\nfu\t复\n", "fafu\t反复\n", "fa\nfu\n")
        PinyinEngine.setSimpTradText(assetText("simp_trad.txt.xz"))
        PinyinEngine.setSimplifyText(assetText("simplify.txt.xz"))
        PinyinEngine.setSimpTradWordsText(assetText("simp_trad_words.txt.xz"))
        PinyinEngine.setTraditional(true)

        val c = PinyinEngine.query("fafu").candidates
        assertTrue("繁体下「反复」应作「反覆」: $c", c.contains("反覆"))
        assertFalse("不得出现逐字错配的「反復」: $c", c.contains("反復"))
    }

    /**
     * 字级表不得含非 BMP 项 —— 加载端 `length == 1` 是 UTF-16 语义，代理对必被丢弃。
     *
     * 生成端（`tools/dict_builder/build_dicts.py` 的 `_bmp()`）与这里必须同口径：
     * 一边收、一边丢就是「白占体积 + 口径不一致」。
     */
    @Test
    fun 字级表不得含非BMP项() {
        for (name in listOf("simp_trad.txt.xz", "simplify.txt.xz")) {
            val text = assetText(name)
            val surrogates = text.count { Character.isSurrogate(it) }
            assertEquals("$name 含非 BMP 字符（加载端会静默丢弃，生成端必须按 _bmp 过滤）", 0, surrogates)
        }
    }

    /**
     * 测试注入 API 必须复位简繁表（`BUG.md` L-55）。
     *
     * 四张表是全局单例状态：`loadFromTexts` 只复位档位位图时，上一个用例设过的映射会渗进下一个用例，
     * 而**漏掉复位不会报错** —— 断言只是在错误的数据上通过。
     */
    @Test
    fun 测试注入必须复位简繁表() {
        PinyinEngine.resetForTest()
        PinyinEngine.setSimpTradText("头\t頭\n")
        assertEquals("前置条件：表已生效", "头", PinyinEngine.toSimplified("頭"))

        PinyinEngine.loadFromTexts("tou\t头\n", "", "tou\n")
        assertEquals(
            "loadFromTexts 必须复位简繁表（否则上一用例的映射渗进来）",
            "頭",
            PinyinEngine.toSimplified("頭"),
        )
    }
}
