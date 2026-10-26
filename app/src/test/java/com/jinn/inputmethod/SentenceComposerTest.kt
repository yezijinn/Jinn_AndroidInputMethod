package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * 词图整句候选 [SentenceComposer] 的直接用例（此前只有 `PinyinEngineTest` 的间接覆盖）。
 *
 * 三条主线：
 *  - **状态表不变量**：`multi` / `prevRank` 都是「插入当时读起点列」的值 —— 起点列在处理它自己
 *    那一轮之前就被更早的起点写满、之后不会再被写（所有边都指向更远的终点），所以不存在
 *    「读到随后被下移覆盖的旧值」。这里用**与实现不同数据结构**的参考实现（不可变链节点 +
 *    从链上重算 `multi`、回溯不走下标）逐例对拍，把这条不变量钉成可执行断言。
 *  - **多字标记沿路径累计**：末条是单字时，整句的「含多字词」结论只能来自更早的边。
 *  - **平局**：同分取起点更靠后（末尾那个词更短），KDoc 与代码一致。
 *
 * 参考实现的价位、罚分、插入序与实现逐字对齐（同分判据用精确 `==`，与实现同一口径）——
 * 它一旦与实现分叉就说明**语义**变了，而不是「写法不同」。
 */
class SentenceComposerTest {

    private val syllables = setOf(
        "a", "aa", "ha", "hai", "han", "hao", "hu", "jia", "ma", "ni", "si", "wei", "wu",
    )

    private fun isSyllable(s: String) = s in syllables

    private fun compose(
        input: String,
        words: Map<String, List<String>> = emptyMap(),
        chars: Map<String, List<String>> = emptyMap(),
    ) = SentenceComposer.compose(input, ::isSyllable, { words[it] }, { chars[it] ?: emptyList() })

    // ── 1. 词图主路径 ──────────────────────────────────────────

    /**
     * 6 音节：一条整词边（五湖四海）+ 一条两字词（为佳/为家）。
     *
     * 两条整句都是 2 个词、名次都是 0 ⇒ 同分；末尾那条边的起点都是 `wei`(5 音节处) ⇒ 同起点，
     * 次序由插入序决定（先插的先留）。名次 1 的「为家」排在「为佳」之后。
     */
    @Test
    fun `整句_两字词的两种落点都进 top3`() {
        val words = mapOf(
            "wuhu" to listOf("五湖"),
            "sihai" to listOf("四海"),
            "weijia" to listOf("为佳", "为家"),
            "wuhusihai" to listOf("五湖四海"),
        )
        val chars = mapOf(
            "wu" to listOf("五", "无"), "hu" to listOf("湖"), "si" to listOf("四"),
            "hai" to listOf("海"), "wei" to listOf("为"), "jia" to listOf("家"),
        )
        assertEquals(listOf("五湖四海为佳", "五湖四海为家"), compose("wuhusihaiweijia", words, chars))
    }

    // ── 2. 多字标记（含主代理怀疑点）────────────────────────────

    /**
     * 末条边是单字时，「含多字词」只能来自更早的边（五湖 是两字词）。
     *
     * 这条同时是两个变异锚点：
     *  ① 把 `multi[idx][end] = multi[fromRank][from] || isMulti` 改成 `= isMulti`
     *    ⇒ 芜湖四（末条是单字）被判成「没有多字词」整条丢弃；
     *  ② 改成 `= multi[fromRank][from]`（漏掉本条边的贡献）⇒ 芜湖（本条边就是两字词）被丢。
     * 另外 无乎四（整条都是单字边）必须**不**出现：它的起点状态 `multi=false`，不能被邻位的
     * `multi=true` 串成 true。
     */
    @Test
    fun `多字标记_沿路径累计且不串位`() {
        val words = mapOf("wuhu" to listOf("芜湖", "五湖"))
        val chars = mapOf(
            "wu" to listOf("无", "五"), "hu" to listOf("乎", "湖"), "si" to listOf("四"),
        )
        val out = compose("wuhusi", words, chars)
        assertEquals("两个多字词状态各出一条整句", listOf("芜湖四", "五湖四"), out)
        assertTrue("全单字路径不得靠邻位状态混成多字词：$out", "无乎四" !in out)
    }

    /**
     * 起点列在它自己那一轮之前就已写满：处理 i 时只往「更远的终点」写，不会回头改 i 列。
     *
     * 构造：`wuhu` 的两种词序让 5 号列（wu 之后）出现 3 个状态（芜湖 / 五湖 / 无乎），其中
     * `multi` 各不相同；末条边是 `sihai`（两字词）。若 `multi` / `prevRank` 读到被下移覆盖的
     * 旧值，第三位的「无乎四海」会被串成多字词或拼出别人的前缀。
     */
    @Test
    fun `起点列不被回写_回溯各拼自己的链`() {
        val words = mapOf("wuhu" to listOf("芜湖", "五湖"), "sihai" to listOf("四海", "死海"))
        val chars = mapOf(
            "wu" to listOf("无", "五"), "hu" to listOf("乎", "湖"),
            "si" to listOf("四"), "hai" to listOf("海"),
        )
        val out = compose("wuhusihai", words, chars)
        // 同分（2 个词、名次 1+1）的「芜湖死海」「五湖四海」：末条边起点相同 ⇒ 先插者先留
        assertEquals(listOf("芜湖四海", "芜湖死海", "五湖四海"), out)
        assertTrue("不得出现跨状态的错拼：$out", out.none { it.contains("芜湖五湖") || it.contains("无乎") })
    }

    // ── 3. 平局规则 ───────────────────────────────────────────

    /**
     * 同分取起点更靠后的路径（`prevPos` 更大 ⇒ 末尾那个词更短）。
     *
     * 5 个音节、两条 4 词的路径得分逐位相同（名次全 0）：
     *  - A：`哈豪`(hahao) + `还` + `汉` + `吗` —— 末条边起点 11
     *  - B：`哈` + `好` + `还` + `汉马`(hanma) —— 末条边起点 8
     * 按 KDoc，A 在前。把 `from > prevPos` 写反（`<`）或删掉同分分支（只比得分），A/B 顺序反转。
     * 首条是 3 词的 `哈豪还汉马`：词少一条就少一次罚分，甩开上面两条。
     */
    @Test
    fun `平局_同分取起点更靠后`() {
        val words = mapOf("hahao" to listOf("哈豪"), "hanma" to listOf("汉马"))
        val chars = mapOf(
            "ha" to listOf("哈"), "hao" to listOf("好"), "hai" to listOf("还"),
            "han" to listOf("汉"), "ma" to listOf("吗"),
        )
        assertEquals(
            listOf("哈豪还汉马", "哈豪还汉吗", "哈好还汉马"),
            compose("hahaohaihanma", words, chars),
        )
    }

    // ── 4. 多字词判定与整条路径的取舍 ────────────────────────────

    /**
     * 两字词算多字词、单字边不算：同一串字符 `你好吗` 只有「两字词 + 单字」这条路径能出。
     *
     * 「一个词长度正好 2」的边界：`你好` 无论由几个单字读音拼得出来，都按**词长**算多字词；
     * 而 `你好` 拆成「你」+「好」两条单字边时整条路径没有多字词 ⇒ 不出整句（否则候选栏里
     * 会多出一条与词候选重复、且没有任何词依据的拼串）。
     */
    @Test
    fun `词长判定_两字词算多字词_单字路径不算`() {
        val chars = mapOf("ni" to listOf("你"), "hao" to listOf("好"), "ma" to listOf("吗"))
        assertEquals("全单字边不出整句", emptyList<String>(), compose("nihaoma", emptyMap(), chars))
        assertEquals(
            "加了 你好 才出，且只出一条",
            listOf("你好吗"),
            compose("nihaoma", mapOf("nihao" to listOf("你好")), chars),
        )
    }

    // ── 5. 递归 walk 的边界 ────────────────────────────────────

    /** 64 字符（上限）必须能跑完且每条整句覆盖全部输入。 */
    @Test
    fun `深度_六十四字符覆盖全部输入`() {
        val input = "a".repeat(64)
        val words = mapOf("aa" to listOf("啊啊"))
        val chars = mapOf("a" to listOf("阿"))
        val out = compose(input, words, chars)
        assertTrue("应出整句：$out", out.isNotEmpty())
        assertTrue("每条都要 64 字：${out.map { it.length }}", out.all { it.length == 64 })
        assertEquals("重复调用结果恒定", out, compose(input, words, chars))
    }

    /** 超过上限、空输入、整串不可切分都直接放弃（不抛异常）。 */
    @Test
    fun `边界_超长与不可达返回空`() {
        assertEquals(emptyList<String>(), compose(""))
        assertEquals("65 字符直接放弃", emptyList<String>(), compose("a".repeat(65)))
        assertEquals("没有任何合法音节", emptyList<String>(), compose("zzz"))
    }

    // ── 6. 与独立参考实现对拍（状态表不变量的系统化守卫）──────────

    /**
     * 随机词图对拍：既有的 `multi` / `prevRank` 写法只要有一处读错起点状态，这里就会分叉。
     *
     * 参考实现刻意换掉三处结构（`@see ReferenceGraph`）：状态是**不可变链节点**（回溯不走
     * 下标）、`multi` **从链上重算**（不靠传播）、可达终点用**逐位布尔 DP**（不用递归）。
     * 位次、罚分、同分判据与插入序与实现逐字一致 —— 只有语义变了才会红。
     */
    @Test
    fun `对拍_随机词图与独立参考一致`() {
        val dict = listOf(
            "a", "ha", "hai", "han", "hao", "hu", "jia", "ma", "ni", "si", "wei", "wu",
        )
        var checked = 0
        for (seed in 1L..2L) {
            val rnd = java.util.Random(seed * 1000003 + 7)
            for (round in 0 until 120) {
                val sylCount = 2 + rnd.nextInt(5)
                val parts = (0 until sylCount).map { dict[rnd.nextInt(dict.size)] }
                val input = parts.joinToString("")
                if (input.length > 64) continue
                val words = HashMap<String, List<String>>()
                val chars = HashMap<String, List<String>>()
                for (i in 0 until sylCount) {
                    for (span in 1..minOf(2, sylCount - i)) {
                        val key = parts.subList(i, i + span).joinToString("")
                        words[key] = (0 until 1 + rnd.nextInt(4)).map { r -> "词$r" }
                    }
                    chars[parts[i]] = (0 until 1 + rnd.nextInt(4)).map { r -> if (r == 0) "字" else "字$r" }
                }
                val actual = compose(input, words, chars)
                val expected = ReferenceGraph(::isSyllable).compose(input, words, chars)
                assertEquals("对拍不一致 input=$input parts=$parts words=$words", expected, actual)
                checked++
            }
        }
        assertTrue("对拍样本太少（$checked），守卫会形同虚设", checked >= 200)
    }

    /**
     * 音节判定记忆化只能**复用已判过的结论**，不能把「判过」当成「合法」。
     *
     * 图：`a` / `ca` 是音节、`b` 不是；词表里有 `b` → `乙乙`（两字词）。0→1 可达（`阿`），
     * 位置 1 处理时 `(1,2)`= `b` 要再判一次（0 的那一轮已经判过并缓存「否」）。
     * 若记忆化把「判过」写成「是」，就会凭空多出 `阿`+`乙乙`+`家` = `阿乙乙家` 这条整句。
     * 末尾的对照断言证明这张图是「上膛」的：把 `b` 也当音节时同一条整句确实出得来。
     */
    @Test
    fun `音节判定记忆化_不得把判过的跨度当成合法`() {
        val words = mapOf("b" to listOf("乙乙"))
        val chars = mapOf("a" to listOf("阿"), "ca" to listOf("家"))
        assertEquals("`b` 不是音节 ⇒ 这条整句不该存在", emptyList<String>(), compose("abca", words, chars))
        val withB = SentenceComposer.compose(
            "abca",
            { it == "a" || it == "ca" || it == "b" },
            { words[it] },
            { chars[it] ?: emptyList() },
        )
        assertEquals("对照：`b` 合法时同一条会出（证明上面的空结果不是图搭错）", listOf("阿乙乙家"), withB)
    }

    // ── 参考实现 ──────────────────────────────────────────────

    /**
     * 独立参考实现：链节点 + 链上重算 `multi` + 逐位布尔 DP 求可达。
     *
     * 位次 / 罚分 / 同分判据 / 插入序与 [SentenceComposer] 一致；数据结构全换掉，用来把
     * 「状态表下标」这一类错误暴露成输出差异。
     */
    private class ReferenceGraph(private val isSyllable: (String) -> Boolean) {

        private class Node(val word: String, val parent: Node?) {
            val multi: Boolean get() = word.length >= 2 || (parent?.multi ?: false)
        }

        fun compose(input: String, words: Map<String, List<String>>, chars: Map<String, List<String>>): List<String> {
            if (input.isEmpty() || input.length > 64) return emptyList()
            val n = input.length
            val k = 3
            val nodes = Array(n + 1) { ArrayList<Node>() }
            val scores = Array(n + 1) { ArrayList<Double>() }
            val froms = Array(n + 1) { ArrayList<Int>() }
            nodes[0].add(Node("", null))
            scores[0].add(0.0)
            froms[0].add(0)
            for (i in 0 until n) {
                if (nodes[i].isEmpty()) continue
                // 可达终点：逐位布尔 DP，与实现的递归 walk 无关
                val reach = BooleanArray(n + 1)
                reach[i] = true
                for (p in i until n) {
                    if (!reach[p]) continue
                    for (e in p + 1..minOf(p + 6, n)) {
                        if (isSyllable(input.substring(p, e))) reach[e] = true
                    }
                }
                val snapNodes = ArrayList(nodes[i])
                val snapScores = ArrayList(scores[i])
                for (e in i + 1..n) {
                    if (!reach[e]) continue
                    val key = input.substring(i, e)
                    val wordList = words[key]?.take(6)?.filter { it.isNotEmpty() } ?: emptyList()
                    val charList = if (isSyllable(key)) {
                        chars[key].orEmpty().take(6).filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    }
                    put(e, i, wordList, snapNodes, snapScores, nodes, scores, froms, k)
                    put(e, i, charList, snapNodes, snapScores, nodes, scores, froms, k)
                }
            }
            val out = LinkedHashSet<String>()
            for (node in nodes[n]) {
                if (!node.multi) continue
                val parts = ArrayList<String>()
                var cur = node
                while (cur.parent != null) {
                    parts.add(cur.word)
                    cur = cur.parent!!
                }
                if (cur.word.isNotEmpty()) continue
                parts.reverse()
                out.add(parts.joinToString(""))
                if (out.size >= k) break
            }
            return out.toList()
        }

        private fun put(
            end: Int,
            from: Int,
            list: List<String>,
            snapNodes: List<Node>,
            snapScores: List<Double>,
            nodes: Array<ArrayList<Node>>,
            scores: Array<ArrayList<Double>>,
            froms: Array<ArrayList<Int>>,
            k: Int,
        ) {
            for (si in snapNodes.indices) {
                for (rank in list.indices) {
                    val score = snapScores[si] - ln((rank + 1).toDouble()) + PENALTY
                    var idx = 0
                    while (idx < scores[end].size) {
                        val s = scores[end][idx]
                        if (score > s || (score == s && from > froms[end][idx])) break
                        idx++
                    }
                    if (idx >= k) continue
                    nodes[end].add(idx, Node(list[rank], snapNodes[si]))
                    scores[end].add(idx, score)
                    froms[end].add(idx, from)
                    while (nodes[end].size > k) {
                        nodes[end].removeAt(nodes[end].size - 1)
                        scores[end].removeAt(scores[end].size - 1)
                        froms[end].removeAt(froms[end].size - 1)
                    }
                }
            }
        }

        private companion object {
            /** `ln(1e-6)`：与实现的 `PER_WORD_PENALTY` 同值（口径写在这里，不引用被测实现的私有常量） */
            const val PENALTY = -13.815510557964274
        }
    }
}
