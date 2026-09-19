package com.jinn.inputmethod

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 词库 asset 完整性测试：守住「连写歧义」类词条不丢。
 *
 * 拼音连写会吞掉音节边界。「企鹅」的拼音是 qi e，连写成 qie，
 * 与单音节「切」同键；「西安」xi an → xian 与「先」同键。
 * 源词表一旦漏收这类词，用户输入 qie（双拼 qiee）只能看到「切/且/窃」，
 * 永远打不出「企鹅」——这是真实发生过的缺陷。
 *
 * 本测试直接校验**打包进 APK 的那份词库**（`assets/pinyin_index.bin.xz`，词库改二进制索引后
 * 它才是唯一权威载体），任何一次词库重新生成/替换若丢掉这些词，测试立刻失败。
 *
 * 性能：只解析索引头部 + 二分查找目标键，不建任何哈希表。
 */
class PhraseDictIntegrityTest {

    /** 「连写歧义」类词：连写后恰为另一合法音节，是最容易在词库处理中丢的一类 */
    private val requiredWords = linkedMapOf(
        "qie" to "企鹅",
        "xian" to "西安",
        "jie" to "饥饿",
        "tian" to "提案",
        "bian" to "彼岸",
        "lian" to "立案",
    )

    @Test
    fun ambiguousWordsExistInAssetDictionary() {
        // 直接查打包进 APK 的那份**二进制索引**（换成索引后它才是唯一权威载体）
        val index = openPhraseIndex()
        for ((key, word) in requiredWords) {
            val words = index.wordsFor(key)
            assertTrue("词库中缺少键「$key」", words != null)
            assertTrue(
                "键「$key」下缺少词「$word」——连写歧义类漏词回归（排查见 tools/dict_builder/detect_ambiguous_keys.py）",
                words!!.contains(word),
            )
        }
    }

    /**
     * 打开打包用的词库文件（xz 压缩），校验的就是真正进 APK 的那份内容。
     * 兼容 Gradle 测试从 app/ 或项目根启动两种工作目录。
     */
    private fun openPhraseIndex(): PhraseIndex {
        val candidates = listOf(
            "src/main/assets/pinyin_index.bin.xz",
            "app/src/main/assets/pinyin_index.bin.xz",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.isFile) {
                val bytes = org.tukaani.xz.XZInputStream(f.inputStream()).use { it.readBytes() }
                return PhraseIndex.of(bytes) ?: throw AssertionError("索引结构异常: $path")
            }
        }
        throw AssertionError("未找到词库索引，尝试过: $candidates")
    }
}
