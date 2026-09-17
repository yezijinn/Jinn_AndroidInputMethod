package com.jinn.inputmethod

/**
 * 词库二进制索引（只读，P1 Stage 1）。
 *
 * 为什么要有它
 * ------------
 * 旧形态是「xz 文本 + 百万级 HashMap」：每次进程启动都要解压、逐行解析、建哈希表
 * （真机实测基础包 **6~10.7s**、进程 PSS **305MB**）。索引把解析与建表挪到**构建期**
 * （`tools/dict_builder/build_dict_index.py`），运行时只做两件事：
 *  1. 解压 4.8MB → 17.3MB 字节数组；
 *  2. 把偏移表读成 `IntArray`（键区/词区共用一份字节数组，不复制）。
 * 查询用**二分查找**（按字节比较，命中后才解码成 String），因此不再需要任何哈希容器。
 *
 * 格式（小端，**v2：长度数组**，见构建脚本）
 * ----------------------------------------
 * ```
 * magic "JNIH" | version u16=2 | keyCount u32 | keysLen u32 | wordsLen u32 | reserved | srcDigest u64
 * keysBlob    : 全部键按字典序串联的 UTF-8 字节
 * keyLengths  : u8  × keyCount        （键都是拼音，≤255 字节）
 * wordsBlob   : 每个键的词表（`词1|词2|...`，顺序即词频序）
 * wordLengths : u16 × keyCount
 * ```
 * v1 用 u32 偏移表（两张表共 4.83MB），v2 换成长度数组（0.60MB + 1.21MB）：
 * **原始体积 17.3MB → 14.4MB、xz 5.04MB → 4.48MB**，解压耗时随之下降。
 * 读取时把长度数组还原成前缀和（IntArray），运行时结构与 v1 一致。
 * 键区与词区共用同一个 `bytes` 数组，切片为绝对下标——**不额外复制数据**。
 *
 * 线程安全：构造完成后完全不可变，可被多线程并发读取（IME 主线程查询 + 后台 merge 各读各的）。
 */
internal class PhraseIndex private constructor(
    private val bytes: ByteArray,
    private val keyCount: Int,
    private val keysStart: Int,
    private val wordsStart: Int,
    private val keyOffsets: IntArray,
    private val wordOffsets: IntArray,
) {

    /** 键数（诊断用） */
    val size: Int get() = keyCount

    /**
     * 头部记录的**来源摘要**（64 位）。
     *
     * · APK 内置索引：构建脚本写入的「源文本 FNV-1a」；
     * · 设备端为可选包构建的索引：写入「源文件 length:mtime 的 FNV-1a」，
     *   加载时与磁盘上源文件的实际值比对 —— 不一致就重建（见 [stampOfFile]）。
     */
    val sourceStamp: Long get() = stampOf(bytes, 22)

    /** 第 [i] 个键（字典序）；越界返回 null。仅诊断/测试用，热路径不要调用 */
    fun keyAt(i: Int): String? = if (i in 0 until keyCount) decode(keysStart + keyOffsets[i],
        keysStart + keyOffsets[i + 1]) else null

    /**
     * 精确查键，返回词表；键不存在返回 null。
     *
     * 词表是**按需解码**的：命中后逐个切词，未命中零分配（二分只比较字节）。
     */
    fun wordsFor(key: String): Array<String>? {
        val i = indexOf(key) ?: return null
        val from = wordsStart + wordOffsets[i]
        val to = wordsStart + wordOffsets[i + 1]
        val out = ArrayList<String>(4)
        var segStart = from
        for (p in from until to) {
            if (bytes[p] == SEP) {
                out.add(String(bytes, segStart, p - segStart, Charsets.UTF_8))
                segStart = p + 1
            }
        }
        if (segStart < to) out.add(String(bytes, segStart, to - segStart, Charsets.UTF_8))
        return out.toTypedArray()
    }

    /**
     * 前缀扫描：返回以 [prefix] 开头的键（字典序）。
     *
     * 供智能预测使用（旧实现遍历 `sortedPhraseKeys`，那是一份 60 万个 String 的快照，
     * 仅这一项就占几十 MB；索引版直接在键区字节上二分 + 顺序扫，不再持有键字符串）。
     */
    fun keysWithPrefix(prefix: String): List<String> {
        if (prefix.isEmpty() || keyCount == 0) return emptyList()
        val p = prefix.toByteArray(Charsets.UTF_8)
        var lo = lowerBound(p)
        val out = ArrayList<String>(8)
        while (lo < keyCount) {
            val from = keysStart + keyOffsets[lo]
            val to = keysStart + keyOffsets[lo + 1]
            if (!startsWith(bytes, from, to, p)) break
            out.add(String(bytes, from, to - from, Charsets.UTF_8))
            lo++
        }
        return out
    }

    private fun indexOf(key: String): Int? {
        if (keyCount == 0) return null
        val p = key.toByteArray(Charsets.UTF_8)
        val i = lowerBound(p)
        if (i >= keyCount) return null
        val from = keysStart + keyOffsets[i]
        val to = keysStart + keyOffsets[i + 1]
        return if (to - from == p.size && regionEquals(bytes, from, p)) i else null
    }

    /** 二分：第一个 >= [p] 的键下标 */
    private fun lowerBound(p: ByteArray): Int {
        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val from = keysStart + keyOffsets[mid]
            val to = keysStart + keyOffsets[mid + 1]
            if (compare(bytes, from, to, p) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun decode(from: Int, to: Int): String = String(bytes, from, to - from, Charsets.UTF_8)

    internal companion object {
        /** 从索引字节构造；结构不自洽返回 null（调用方回退，不影响可用性） */
        fun of(bytes: ByteArray): PhraseIndex? = parse(bytes)

        /** 源文本摘要（与构建脚本 `build_dict_index.py` 的 FNV-1a 64 同算法） */
        fun stampOfText(text: String): Long = fnv1a64(text.toByteArray(Charsets.UTF_8))

        /** 源文件摘要：把 length 与 lastModified 拼起来做 FNV，用于「文件是否变过」的复用校验 */
        fun stampOfFile(length: Long, modified: Long): Long =
            fnv1a64("$length:$modified".toByteArray(Charsets.UTF_8))

        /**
         * 从「键 → 词表行」构建索引字节（**设备端**首次加载可选包时使用）。
         *
         * 与构建脚本产出**完全同格式**（同一套 header/偏移布局），因此可以互相读取；
         * 已由 `IndexBuilderParityTest` 对拍钉住。
         *
         * @param lines 形如 `拼音<TAB>词1|词2` 的行；**流水线已保证按键升序**，
         *   这里做一次校验，万一乱序则退化为排序（正确性优先，正常不会触发）。
         * @param stamp 写入头部的来源摘要（供复用校验）
         */
        fun build(lines: Sequence<String>, stamp: Long): ByteArray {
            // 单趟流式：只累积「键区/键长/词区/词长」四个缓冲，不再维护偏移数组。
            // 峰值 ≈ 索引体积，绝不把百万行读成一个 List（可选包 114 万行 ≈ 150MB+，会顶爆堆）。
            val keyBlob = java.io.ByteArrayOutputStream(1 shl 20)
            val keyLens = java.io.ByteArrayOutputStream(1 shl 16)
            val wordBlob = java.io.ByteArrayOutputStream(1 shl 21)
            val wordLens = java.io.ByteArrayOutputStream(1 shl 17)
            var count = 0
            var prev: String? = null
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab)
                // 索引靠二分查找，**必须按键升序**；乱序时由调用方回退到旧路径（见 PinyinEngine）
                check(prev == null || key >= prev) { "词库键不是升序: $key < $prev" }
                prev = key
                val kb = key.toByteArray(Charsets.UTF_8)
                val wb = line.substring(tab + 1).toByteArray(Charsets.UTF_8)
                require(kb.size <= 255) { "键过长（>255B），长度数组无法表示" }
                require(wb.size <= 65535) { "词表过长（>64KB），长度数组无法表示" }
                keyBlob.write(kb)
                keyLens.write(kb.size)
                wordBlob.write(wb)
                wordLens.write(wb.size and 0xFF)
                wordLens.write((wb.size ushr 8) and 0xFF)
                count++
            }

            val keys = keyBlob.toByteArray()
            val kl = keyLens.toByteArray()
            val words = wordBlob.toByteArray()
            val wl = wordLens.toByteArray()
            val out = ByteArray(HEADER_SIZE + keys.size + kl.size + words.size + wl.size)
            out[0] = 'J'.code.toByte(); out[1] = 'N'.code.toByte()
            out[2] = 'I'.code.toByte(); out[3] = 'H'.code.toByte()
            writeU16(out, 4, 2)
            writeI32(out, 6, count)
            writeI32(out, 10, keys.size)
            writeI32(out, 14, words.size)
            writeI32(out, 18, 0)
            writeI64(out, 22, stamp)
            var p = HEADER_SIZE
            System.arraycopy(keys, 0, out, p, keys.size); p += keys.size
            System.arraycopy(kl, 0, out, p, kl.size); p += kl.size
            System.arraycopy(words, 0, out, p, words.size); p += words.size
            System.arraycopy(wl, 0, out, p, wl.size)
            return out
        }

        private fun stampOf(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        fun fnv1a64(data: ByteArray): Long {
            var h = -3750763034362895579L          // 0xCBF29CE484222325
            for (b in data) {
                h = h xor (b.toLong() and 0xFF)
                h *= 1099511628211L                // 0x100000001B3
            }
            return h
        }

        private fun writeU16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        private fun writeI32(b: ByteArray, o: Int, v: Int) {
            for (i in 0 until 4) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }

        private fun writeI64(b: ByteArray, o: Int, v: Long) {
            for (i in 0 until 8) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }

        /** 词分隔符：与文本词库一致 */
        const val SEP = '|'.code.toByte()

        /**
         * 头部长度：magic(4) + version(2) + keyCount(4) + keysLen(4) + wordsLen(4) +
         * reserved(4) + srcDigest(8) —— 必须与构建脚本 `build_dict_index.py` 的 struct 完全一致。
         */
        const val HEADER_SIZE = 30

        fun compare(a: ByteArray, from: Int, to: Int, b: ByteArray): Int {
            var i = from
            var j = 0
            while (i < to && j < b.size) {
                val x = a[i].toInt() and 0xFF
                val y = b[j].toInt() and 0xFF
                if (x != y) return x - y
                i++
                j++
            }
            return (to - i) - (b.size - j)
        }

        fun regionEquals(a: ByteArray, from: Int, b: ByteArray): Boolean {
            for (k in b.indices) if (a[from + k] != b[k]) return false
            return true
        }

        fun startsWith(a: ByteArray, from: Int, to: Int, prefix: ByteArray): Boolean {
            if (to - from < prefix.size) return false
            for (k in prefix.indices) if (a[from + k] != prefix[k]) return false
            return true
        }

        /**
         * 解析索引字节；头部/长度/偏移不自洽时返回 null（调用方回退，不影响可用性）。
         *
         * 这里只做**廉价的结构校验**（magic/版本/段长/偏移单调且首尾对齐）——
         * 足以挡住截断、版本错配、字节序问题这类真实故障。
         */
        fun parse(bytes: ByteArray): PhraseIndex? {
            if (bytes.size < HEADER_SIZE) return null
            if (bytes[0] != 'J'.code.toByte() || bytes[1] != 'N'.code.toByte() ||
                bytes[2] != 'I'.code.toByte() || bytes[3] != 'H'.code.toByte()
            ) {
                return null
            }
            val version = u16(bytes, 4)
            if (version != 2) return null
            val keyCount = i32(bytes, 6)
            val keysLen = i32(bytes, 10)
            val wordsLen = i32(bytes, 14)
            if (keyCount <= 0 || keysLen <= 0 || wordsLen <= 0) return null

            // 段长校验必须用 Long 累加：keysLen / wordsLen / keyCount 都来自外部字节，
            // 直接 Int 相加会在「超大声明值」上溢出成负数、绕过校验，随后在切片时抛数组越界
            // （本方法的契约是「结构不自洽就返回 null」，绝不抛异常——调用方靠它决定是否回退）。
            // 总长是精确可算的：header + keysLen + keyCount(u8) + wordsLen + keyCount(u16×2)。
            val need = HEADER_SIZE.toLong() + keysLen + wordsLen + keyCount.toLong() * 3
            if (need != bytes.size.toLong()) return null

            var p = HEADER_SIZE
            val keysStart = p
            p += keysLen
            val keyOffsets = expandLengths(bytes, p, keyCount, 1)
            p += keyCount
            val wordsStart = p
            p += wordsLen
            val wordOffsets = expandLengths(bytes, p, keyCount, 2)
            p += keyCount * 2

            // 自洽性：长度数组拼出的总长必须与头部声明一致
            if (keyOffsets[keyCount] != keysLen) return null
            if (wordOffsets[keyCount] != wordsLen) return null

            return PhraseIndex(bytes, keyCount, keysStart, wordsStart, keyOffsets, wordOffsets)
        }

        fun u16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        fun i32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        /**
         * 把长度数组展开成前缀和偏移数组（v2）。
         *
         * @param width 每项字节数：1 = u8（键长），2 = u16（词表长）
         */
        fun expandLengths(b: ByteArray, start: Int, count: Int, width: Int): IntArray {
            val out = IntArray(count + 1)
            var acc = 0
            if (width == 1) {
                for (i in 0 until count) {
                    acc += b[start + i].toInt() and 0xFF
                    out[i + 1] = acc
                }
            } else {
                for (i in 0 until count) {
                    acc += (b[start + i * 2].toInt() and 0xFF) or
                        ((b[start + i * 2 + 1].toInt() and 0xFF) shl 8)
                    out[i + 1] = acc
                }
            }
            return out
        }
    }
}
