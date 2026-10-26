package com.jinn.inputmethod

import kotlin.math.ln

/**
 * 词图整句候选（吸收 librime `ScriptTranslator` + `Poet` 的做法）。
 *
 * **这是词图，不是「先分词再拼」**：节点是输入串上的位置 `0..n`，边是任意一个**可达跨度**
 * `[start, end)` —— 只要这段串本身能按合法音节切完（逐音节都是合法音节）就成立，不依赖唯一切分。
 * 边的权重取该跨度键下的词条 `-ln(名次+1) + 每词罚分`：名次分让词频高的词占优，罚分让「用更少的词」
 * 占优。整条路径得分是各边之和，每多一个词就多扣一次 [PER_WORD_PENALTY]（≈ `ln(1e-6)`，对应
 * librime `grammar.h` 无 grammar 时的罚分），所以最优路径会自发把同一段输入压成**更长的词** ——
 * 「五湖四海」+「为家」胜过「五湖」+「四海」+「为」+「家」。
 *
 * 一次交 **top-[MAX_SENTENCES]** 而非单条最优（对应 librime `max_sentences` / beam）：名次分只差
 * `ln2`，真实词库里 `weijia` 首位是「为佳」，单条最优会给出「五湖四海为佳」；把第二、三名一并交出，
 * 「五湖四海为家」才落进候选由用户选。分数与罚分不做任何特判。
 *
 * 平局规则：得分相同时取**起点更靠后**的路径（`prevPos` 更大），即让末尾那个词更短。实际同分的是
 * 「五湖四海」+「为佳」与「芜湖」+「四海为家」（两条都 2 个词、名次分都是 0），它只决定谁排第一；
 * 该例的正确切分在 top-3 里落在第三位。
 *
 * 整条路径都没有多字词（等于「每音节取首字」的串）时丢弃：那不是候选，只会挤占候选栏。
 *
 * **状态表的不变量（别照着「读旧值」去改）**：`multi` / `prevRank` 都是插入当时从**起点列**读的值，
 * 看起来会被后续下移覆盖，实际不会 —— 所有边都指向更远的终点（`end > i`），处理 `i` 的那一轮
 * 只写 `i` 之后的列，而 `i` 列在此之前已被更早的起点写满（各起点单调递增处理）。所以起点列
 * 在「读它」时已是终值，回溯也不会拿到别人的 `rank`。`SentenceComposerTest` 用一份数据结构完全
 * 不同的参考实现（不可变链节点 + 从链上重算 `multi`）逐例对拍钉着这条。
 */
internal object SentenceComposer {
    /** 每个词的固定罚分：`ln(1e-6)`，对应 librime `grammar.h` 无 grammar 时的权重 */
    private const val PER_WORD_PENALTY = -13.815510557964274
    private const val MAX_SYLLABLE_LEN = 6

    /**
     * 每个跨度最多取几个同音词。6 是本机热路径与召回折中（librime 默认 1，我们词库小）：
     * 再多会让长输入的路径枚举平方级涨，整句候选却没明显变好。
     */
    private const val MAX_HOMOPHONES = 6
    private const val MAX_INPUT_CHARS = 64

    /** 每条输入最多交出几条整句（librime `max_sentences` 的 beam 宽度） */
    private const val MAX_SENTENCES = 3
    private const val NEG = Double.NEGATIVE_INFINITY

    /**
     * 在 [input] 的词图上求 top-k 路径并拼成整句。
     *
     * @param input 全拼串（小写字母，无空格）；超过 [MAX_INPUT_CHARS] 直接放弃
     * @param isSyllable 是否为合法完整音节（决定哪些跨度可达）
     * @param wordsOf 跨度键 → 词条列表（按词频降序，null 表示无词条）
     * @param charsOf 单音节键 → 单字列表（仅当整个跨度本身就是一个音节时才可用）
     * @return 按分数降序、去重后的整句（最多 [MAX_SENTENCES] 条）；不可达或整条路径没有多字词时为空
     */
    fun compose(
        input: String,
        isSyllable: (String) -> Boolean,
        wordsOf: (String) -> List<String>?,
        charsOf: (String) -> List<String>,
    ): List<String> {
        if (input.isEmpty() || input.length > MAX_INPUT_CHARS) return emptyList()
        val n = input.length
        val k = MAX_SENTENCES
        // 每个位置保留 k 个备选状态：score 降序（同分时 prevPos 降序），rank 0 即最优
        val score = Array(k) { DoubleArray(n + 1) { NEG } }
        val prevPos = Array(k) { IntArray(n + 1) { -1 } }
        val prevRank = Array(k) { IntArray(n + 1) }
        val wordAt = Array(k) { arrayOfNulls<String>(n + 1) }
        val multi = Array(k) { BooleanArray(n + 1) }
        val count = IntArray(n + 1)
        score[0][0] = 0.0
        count[0] = 1

        // 把一条候选插进 end 的 top-k 表：按分数降序、同分按起点降序，插位后整体下移，只留前 k
        fun offer(end: Int, cand: Double, text: String, from: Int, fromRank: Int, isMulti: Boolean) {
            if (text.isEmpty()) return
            val c = count[end]
            var idx = 0
            while (idx < c) {
                val s = score[idx][end]
                if (cand > s || (cand == s && from > prevPos[idx][end])) break
                idx++
            }
            if (idx >= k) return
            if (c < k) count[end] = c + 1
            var j = count[end] - 1
            while (j > idx) {
                score[j][end] = score[j - 1][end]
                prevPos[j][end] = prevPos[j - 1][end]
                prevRank[j][end] = prevRank[j - 1][end]
                wordAt[j][end] = wordAt[j - 1][end]
                multi[j][end] = multi[j - 1][end]
                j--
            }
            score[idx][end] = cand
            prevPos[idx][end] = from
            prevRank[idx][end] = fromRank
            wordAt[idx][end] = text
            multi[idx][end] = multi[fromRank][from] || isMulti
        }

        // 音节判定记忆化（0 = 未判 / 1 = 是 / 2 = 否）：`walk` 在每个起点 i 都要重判同一批
        // `(pos, end)`，同一个跨度在更早的起点也会被再判一次 —— 64 字符最坏情况是 6n² 次
        // `substring`（≈2.4 万次短命对象，实测占这一路一半以上耗时）。判定只与 `(pos, end)`
        // 有关且 [isSyllable] 在一次 compose 内是纯函数，记忆化后全程只判 6n 次（≈380 次）。
        // 平铺成一维（pos * (n+1) + end），避免 n 个 ByteArray 的对象头开销。
        val sylKnown = ByteArray((n + 1) * (n + 1))
        fun isSyllableAt(pos: Int, end: Int): Boolean {
            val at = pos * (n + 1) + end
            val known = sylKnown[at].toInt()
            if (known != 0) return known == 1
            val ok = isSyllable(input.substring(pos, end))
            sylKnown[at] = if (ok) 1 else 2
            return ok
        }

        // ends / seen 按位置复用：每个起点各建一个 ArrayList + BooleanArray 会每键多两次分配。
        // `seen[end] == i` 即「本轮 walk 已访问」（i 单调递增，不必清零）。
        val ends = IntArray(n + 1)
        val seen = IntArray(n + 1) { -1 }

        for (i in 0 until n) {
            val states = count[i]
            if (states == 0) continue
            // 枚举从 i 出发的可达终点：按音节切分链式展开，同一终点只保留一份（顺序仍递增）
            var endCount = 0

            fun walk(pos: Int) {
                val stop = minOf(pos + MAX_SYLLABLE_LEN, n)
                for (end in pos + 1..stop) {
                    if (seen[end] == i) continue
                    if (!isSyllableAt(pos, end)) continue
                    seen[end] = i
                    ends[endCount++] = end
                    walk(end)
                }
            }
            walk(i)

            for (e in 0 until endCount) {
                val end = ends[e]
                val key = input.substring(i, end)
                // 逐条候选就地展开：不为每条边建集合（每次按键会多出上千个短命对象）
                val words = wordsOf(key)
                if (words != null) {
                    val lim = minOf(words.size, MAX_HOMOPHONES)
                    for (r in 0 until states) {
                        val base = score[r][i]
                        for (rank in 0 until lim) {
                            val word = words[rank]
                            if (word.isEmpty()) continue
                            offer(end, base - ln((rank + 1).toDouble()) + PER_WORD_PENALTY,
                                word, i, r, word.length >= 2)
                        }
                    }
                }
                // 整个跨度本身是一个音节时，单字也是这条边上的候选（不参与「多字词」判定）
                // 判据走记忆化：同一跨度在 walk 里刚判过（直接边），这里直接命中缓存
                if (isSyllableAt(i, end)) {
                    val chars = charsOf(key)
                    val lim = minOf(chars.size, MAX_HOMOPHONES)
                    for (r in 0 until states) {
                        val base = score[r][i]
                        for (rank in 0 until lim) {
                            val c = chars[rank]
                            if (c.isEmpty()) continue
                            offer(end, base - ln((rank + 1).toDouble()) + PER_WORD_PENALTY, c, i, r, false)
                        }
                    }
                }
            }
        }

        val out = LinkedHashSet<String>(k)
        // 丢弃「整条没有多字词」的路径。**为什么这条过滤不会把好整句一起丢掉**：覆盖同样 S 个
        // 音节的路径里，含多字词的那条至少少用一个词（词少一个就少罚 13.8），而名次分每词最多
        // 让 1.79（`ln(6)`）⇒ 多字词路径的得分总比全单字路径好 13.8 - 1.79×(S-1)，S ≤ 8 时恒正。
        // 调用方 `PinyinEngine.query` 固定按 `MAX_SYLLABLES` = 8 截断后再送进来，所以应用内
        // 不会出现「top-k 全是单字路径、多字词整句被挡在 beam 外」；直接以 9 音节以上调用本函数时
        // 这条余量才可能变负（当前无此调用方）。
        for (r in 0 until count[n]) {
            if (!multi[r][n]) continue
            val parts = ArrayList<String>(4)
            var pos = n
            var rank = r
            var ok = true
            while (pos > 0) {
                val text = wordAt[rank][pos]
                if (text == null) {
                    ok = false
                    break
                }
                parts.add(text)
                val from = prevPos[rank][pos]
                if (from < 0) {
                    ok = false
                    break
                }
                rank = prevRank[rank][pos]
                pos = from
            }
            if (!ok || pos != 0) continue
            parts.reverse()
            out.add(parts.joinToString(""))
            if (out.size >= k) break
        }
        return out.toList()
    }
}
