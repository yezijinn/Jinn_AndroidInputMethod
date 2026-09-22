package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 高频子集词库（`assets/hot_phrases.txt.xz`）的资产守卫。
 *
 * 它决定「键盘弹出后多久能打字」，因此必须有测试钉住三件事：
 *  1. 规模：够小（加载才快）且够大（日常词要覆盖）；
 *  2. 内容：最高频的那批词必须在里面（否则冷启动第一印象就是「打不出来」）；
 *  3. 每个键的词表必须是全量词表的前缀，这是「子集先行 + 全量并入」后
 *     候选顺序与全量单载一致的根本保证（见 `HotDictMergeTest`）。
 *
 * 生成脚本：`tools/dict_builder/gen_hot_dict.py`。
 */
class HotDictAssetTest {

    private fun openAsset(name: String): java.io.InputStream {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return XZInputStream(f.inputStream())
        }
        throw AssertionError("未找到资产 $name")
    }

    private fun assetSize(name: String): Long {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.length()
        }
        throw AssertionError("未找到资产 $name")
    }

    private fun openIndex(): PhraseIndex {
        for (p in listOf("src/main/assets/pinyin_index.bin.xz", "app/src/main/assets/pinyin_index.bin.xz")) {
            val f = File(p)
            if (f.isFile) {
                val bytes = XZInputStream(f.inputStream()).use { it.readBytes() }
                return PhraseIndex.of(bytes) ?: throw AssertionError("索引结构异常: $p")
            }
        }
        throw AssertionError("未找到词库索引")
    }

    private fun readHot(): LinkedHashMap<String, List<String>> {
        val map = LinkedHashMap<String, List<String>>()
        openAsset("hot_phrases.txt.xz").bufferedReader(Charsets.UTF_8).use { r ->
            var line = r.readLine()
            while (line != null) {
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    map[line.substring(0, tab)] = line.substring(tab + 1).split('|')
                }
                line = r.readLine()
            }
        }
        return map
    }

    @Test
    fun 高频子集_体积与规模合理() {
        val sizeKb = assetSize("hot_phrases.txt.xz") / 1024
        assertTrue("xz 体积 ${sizeKb}KB 过大，会拖慢加载", sizeKb <= 400)
        val keys = readHot().size
        assertTrue("键数 $keys 过少，日常词覆盖不足", keys >= 30_000)
        assertTrue("键数 $keys 过多，加载会变慢", keys <= 60_000)
    }

    @Test
    fun 高频子集_含最高频词() {
        val all = readHot().values.flatten().toHashSet()
        val mustHave = listOf("你好", "我们", "什么", "这个", "可以", "没有", "今天", "谢谢", "因为")
        val missing = mustHave.filterNot { it in all }
        assertTrue("高频子集缺少最常用词: $missing", missing.isEmpty())
    }

    @Test
    fun 高频子集_是全量词表的前缀() {
        val hot = readHot()
        // 抽样（每 100 个键取 1 个），避免把 60 万行全量读完再比对
        val sample = HashMap<String, List<String>>()
        hot.entries.filterIndexed { i, _ -> i % 100 == 0 }.forEach { sample[it.key] = it.value }
        assertTrue("抽样为空，资产可能未生成", sample.isNotEmpty())

        // 全量词库现在是二进制索引：直接按键查，不必再流式扫 60 万行
        val index = openIndex()
        val problems = mutableListOf<String>()
        for ((key, hotWords) in sample) {
            val full = index.wordsFor(key)?.toList()
            if (full == null || full.size < hotWords.size || full.subList(0, hotWords.size) != hotWords) {
                problems += key
            }
        }
        assertTrue("以下键的子集词表不是全量前缀（并入后顺序会漂移）: $problems", problems.isEmpty())
    }
}
