package com.jinn.inputmethod

/**
 * 词库二进制索引（只读，P1 Stage 1）。
 *
 * 旧的形态是「xz 文本 + 百万级 HashMap」，每次启动都要解压、逐行解析、建哈希表
 * （真机实测基础包 6~10.7s、进程 PSS 305MB）。改索引后解析和建表都挪到构建期
 * （`tools/dict_builder/build_dict_index.py`），运行时只解压字节、把长度数组还原成偏移，
 * 查询用二分查找按字节比较，命中才解码成 String，不再需要哈希容器。
 *
 * 格式（小端，**v2：长度数组**，见构建脚本）
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
 * 读取一律走 [java.nio.ByteBuffer]，两种承载：APK 里的索引先解 xz 再 `wrap`；
 * 设备端的 `.idx` 缓存用 [ofMapped] 直接映射（零拷贝，页可被内核回收，省下几十 MB 私有堆）。
 * 两者共用同一套偏移/二分/解码逻辑，行为一致由 `IndexParityTest` 对拍。
 *
 * 线程安全：构造完成后完全不可变，可被多线程并发读取（IME 主线程查询 + 后台 merge 各读各的）。
 */
internal class PhraseIndex private constructor(
    private val buf: java.nio.ByteBuffer,
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
    val sourceStamp: Long get() = stampOf(buf, 22)

    /** 第 [i] 个键（字典序）；越界返回 null。仅诊断/测试用，热路径不要调用 */
    fun keyAt(i: Int): String? = if (i in 0 until keyCount) {
        val from = keysStart + keyOffsets[i]
        decode(from, keyOffsets[i + 1] - keyOffsets[i])
    } else null

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
            if (buf.get(p) == SEP) {
                out.add(decode(segStart, p - segStart))
                segStart = p + 1
            }
        }
        if (segStart < to) out.add(decode(segStart, to - segStart))
        return out.toTypedArray()
    }

    /**
     * 前缀扫描：返回以 [prefix] 开头的键（字典序）。
     *
     * 供智能预测使用（旧实现遍历 `sortedPhraseKeys`，那是一份 60 万个 String 的快照，
     * 仅这一项就占几十 MB；索引版直接在键区字节上二分 + 顺序扫，不再持有键字符串）。
     *
     * 注意它会**把命中的键全部实例化**：短前缀代价不小（实测 `"ni"` → 6185 个 String / 2.18ms），
     * 调用方要自带上限（见 [PinyinEngine.predict] 的提前退出）。真要无条件全量扫描，
     * 用下面那个不建 String 的区间接口。
     */
    fun keysWithPrefix(prefix: String): List<String> {
        if (prefix.isEmpty() || keyCount == 0) return emptyList()
        val p = prefix.toByteArray(Charsets.UTF_8)
        var lo = lowerBound(p)
        val out = ArrayList<String>(8)
        while (lo < keyCount) {
            val from = keysStart + keyOffsets[lo]
            val to = keysStart + keyOffsets[lo + 1]
            if (!startsWith(buf, from, to, p)) break
            out.add(decode(from, to - from))
            lo++
        }
        return out
    }

    /**
     * 前缀区间枚举（吸收 librime `Prism::ExpandSearch` 的 `Match(value, length)` 设计）：
     * 只回调 `(键字节长度, 词区起止)`，**不实例化键字符串**。
     *
     * 与 [keysWithPrefix] 的差别在短前缀下非常明显：实测 `"ni"` 有 6185 个键，
     * 旧写法会一次性建出 6185 个 String（2.18ms）；这里调用方可以直接用字节长度判断，
     * 只在真正需要时才解码（见 [collectLongerSuffixes]，只解码命中的后缀）。
     *
     * @param action 返回 false 表示提前结束扫描（调用方自带上限）
     */
    fun forEachRangeWithPrefix(
        prefix: String,
        action: (keyByteLen: Int, wordsFrom: Int, wordsTo: Int) -> Boolean,
    ) {
        if (prefix.isEmpty() || keyCount == 0) return
        val p = prefix.toByteArray(Charsets.UTF_8)
        var lo = lowerBound(p)
        while (lo < keyCount) {
            val from = keysStart + keyOffsets[lo]
            val to = keysStart + keyOffsets[lo + 1]
            if (!startsWith(buf, from, to, p)) break
            val cont = action(to - from, wordsStart + wordOffsets[lo], wordsStart + wordOffsets[lo + 1])
            if (!cont) return
            lo++
        }
    }

    /**
     * 在词区区间内，把「以 [wordBytes] 开头且更长」的词**后缀**追加进 [out]，返回新增个数。
     *
     * 前缀判断走**字节级**比较（`regionEquals`）：未命中零分配，命中者也只解码后缀，
     * 不做 `substring` 之前的整词解码。
     */
    fun collectLongerSuffixes(
        wordsFrom: Int,
        wordsTo: Int,
        wordBytes: ByteArray,
        out: MutableCollection<String>,
    ): Int {
        var added = 0
        var segStart = wordsFrom
        for (p in wordsFrom until wordsTo) {
            if (buf.get(p) == SEP) {
                added += addSuffixIfLonger(segStart, p - segStart, wordBytes, out)
                segStart = p + 1
            }
        }
        if (segStart < wordsTo) {
            added += addSuffixIfLonger(segStart, wordsTo - segStart, wordBytes, out)
        }
        return added
    }

    private fun addSuffixIfLonger(
        from: Int,
        len: Int,
        wordBytes: ByteArray,
        out: MutableCollection<String>,
    ): Int {
        if (len <= wordBytes.size) return 0
        if (!regionEquals(buf, from, wordBytes)) return 0
        out.add(decode(from + wordBytes.size, len - wordBytes.size))
        return 1
    }

    private fun indexOf(key: String): Int? {
        if (keyCount == 0) return null
        val p = key.toByteArray(Charsets.UTF_8)
        val i = lowerBound(p)
        if (i >= keyCount) return null
        val from = keysStart + keyOffsets[i]
        val to = keysStart + keyOffsets[i + 1]
        return if (to - from == p.size && regionEquals(buf, from, p)) i else null
    }

    /** 二分：第一个 >= [p] 的键下标 */
    private fun lowerBound(p: ByteArray): Int {
        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val from = keysStart + keyOffsets[mid]
            val to = keysStart + keyOffsets[mid + 1]
            if (compare(buf, from, to, p) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 按绝对下标解码 [len] 个字节为 String。
     *
     * 堆内承载走 `String(array, offset, len)`（零额外拷贝）；映射承载没有数组可借，
     * 退化为逐字节拷进小数组——只有**命中后**才会调用，未命中路径零分配。
     */
    private fun decode(from: Int, len: Int): String {
        val arr = heapArray
        if (arr != null) return String(arr, from, len, Charsets.UTF_8)
        val tmp = ByteArray(len)
        for (i in 0 until len) tmp[i] = buf.get(from + i)
        return String(tmp, Charsets.UTF_8)
    }

    /** 堆内承载时的底层数组（映射承载返回 null） */
    private val heapArray: ByteArray? = if (buf.hasArray()) buf.array() else null

    internal companion object {
        /** 从索引字节构造（堆内承载）；结构不自洽返回 null（调用方回退，不影响可用性） */
        fun of(bytes: ByteArray): PhraseIndex? = parse(java.nio.ByteBuffer.wrap(bytes))

        /**
         * **内存映射**构造（吸收 librime `Prism : MappedFile`）：设备端 `.idx` 缓存首选。
         *
         * 失败（文件缺失/截断/平台不支持）返回 null → 调用方回退到 [of] + `readBytes()`。
         * 映射的生命周期与本对象一致：只要索引还在用，缓冲区就不会被回收/关闭。
         * 注意：**映射期间不要覆盖该文件**（截断会让映射读到空洞，Linux 上甚至 SIGBUS），
         * 重写缓存必须「写临时文件 + 原子改名」（见 PinyinEngine.writeIndexCacheAtomically）。
         */
        fun ofMapped(file: java.io.File): PhraseIndex? = runCatching {
            if (!file.isFile) return null
            val raf = java.io.RandomAccessFile(file, "r")
            try {
                val ch = raf.channel
                val size = ch.size()
                if (size <= 0 || size > Int.MAX_VALUE) return null
                val mapped = ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, size)
                parse(mapped)
            } finally {
                // 关掉 fd 不影响已建立的映射（Linux/Android 语义），但必须关，避免 fd 泄漏
                raf.close()
            }
        }.getOrNull()

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
         * @param lines 形如 `拼音<TAB>词1|词2` 的行；**流水线已保证按键升序**（乱序即抛错，
         *   由调用方回退到文本路径）；同键的**连续多行会合并进同一个词表**（与文本路径一致）。
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
            // 逐键写出：同键的连续多行**合并进同一个词表**（`词1|词2` 拼接）。
            // 语义与文本路径（loadPhrasesReader 对同键合并）对齐——旧实现按行写出，
            // 同键第二个词表会被二分查找永久跳过（静默少词，无日志无异常）。
            // 只缓冲**单个键**的词表（受 64KB 上限约束），不是整份词库，流式性质不变。
            var pendingKey: String? = null
            var pendingWords: ByteArray? = null
            fun flushPending() {
                val key = pendingKey ?: return
                val words = pendingWords ?: ByteArray(0)
                val kb = key.toByteArray(Charsets.UTF_8)
                require(kb.size <= 255) { "键过长（>255B），长度数组无法表示" }
                require(words.size <= 65535) { "词表过长（>64KB），长度数组无法表示（同键合并后超限）" }
                keyBlob.write(kb)
                keyLens.write(kb.size)
                wordBlob.write(words)
                wordLens.write(words.size and 0xFF)
                wordLens.write((words.size ushr 8) and 0xFF)
                count++
            }
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab)
                // 索引靠二分查找，**必须按键升序**；乱序时由调用方回退到旧路径（见 PinyinEngine）
                val prev = pendingKey
                check(prev == null || key >= prev) { "词库键不是升序: $key < $prev" }
                val wb = line.substring(tab + 1).toByteArray(Charsets.UTF_8)
                if (prev != null && key == prev) {
                    val old = pendingWords ?: ByteArray(0)
                    val merged = ByteArray(old.size + 1 + wb.size)
                    System.arraycopy(old, 0, merged, 0, old.size)
                    merged[old.size] = SEP
                    System.arraycopy(wb, 0, merged, old.size + 1, wb.size)
                    pendingWords = merged
                    continue
                }
                flushPending()
                pendingKey = key
                pendingWords = wb
            }
            flushPending()

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

        private fun stampOf(b: java.nio.ByteBuffer, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b.get(off + i).toLong() and 0xFF) shl (8 * i))
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

        fun compare(a: java.nio.ByteBuffer, from: Int, to: Int, b: ByteArray): Int {
            var i = from
            var j = 0
            while (i < to && j < b.size) {
                val x = a.get(i).toInt() and 0xFF
                val y = b[j].toInt() and 0xFF
                if (x != y) return x - y
                i++
                j++
            }
            return (to - i) - (b.size - j)
        }

        fun regionEquals(a: java.nio.ByteBuffer, from: Int, b: ByteArray): Boolean {
            for (k in b.indices) if (a.get(from + k) != b[k]) return false
            return true
        }

        fun startsWith(a: java.nio.ByteBuffer, from: Int, to: Int, prefix: ByteArray): Boolean {
            if (to - from < prefix.size) return false
            for (k in prefix.indices) if (a.get(from + k) != prefix[k]) return false
            return true
        }

        /**
         * 解析索引字节；头部/长度/偏移不自洽时返回 null（调用方回退，不影响可用性）。
         *
         * 这里只做**廉价的结构校验**（magic/版本/段长/偏移单调且首尾对齐）——
         * 足以挡住截断、版本错配、字节序问题这些常见故障。
         */
        fun parse(buf: java.nio.ByteBuffer): PhraseIndex? {
            if (buf.capacity() < HEADER_SIZE) return null
            if (buf.get(0) != 'J'.code.toByte() || buf.get(1) != 'N'.code.toByte() ||
                buf.get(2) != 'I'.code.toByte() || buf.get(3) != 'H'.code.toByte()
            ) {
                return null
            }
            val version = u16(buf, 4)
            if (version != 2) return null
            val keyCount = i32(buf, 6)
            val keysLen = i32(buf, 10)
            val wordsLen = i32(buf, 14)
            if (keyCount <= 0 || keysLen <= 0 || wordsLen <= 0) return null

            // 段长校验必须用 Long 累加：keysLen / wordsLen / keyCount 都来自外部字节，
            // 直接 Int 相加会在「超大声明值」上溢出成负数、绕过校验，随后在切片时抛数组越界
            // （本方法的契约是「结构不自洽就返回 null」，绝不抛异常——调用方靠它决定是否回退）。
            // 总长是精确可算的：header + keysLen + keyCount(u8) + wordsLen + keyCount(u16×2)。
            val need = HEADER_SIZE.toLong() + keysLen + wordsLen + keyCount.toLong() * 3
            if (need != buf.capacity().toLong()) return null

            var p = HEADER_SIZE
            val keysStart = p
            p += keysLen
            val keyOffsets = expandLengths(buf, p, keyCount, 1) ?: return null
            p += keyCount
            val wordsStart = p
            p += wordsLen
            val wordOffsets = expandLengths(buf, p, keyCount, 2) ?: return null
            p += keyCount * 2

            // 自洽性：长度数组拼出的总长必须与头部声明一致
            if (keyOffsets[keyCount] != keysLen) return null
            if (wordOffsets[keyCount] != wordsLen) return null

            return PhraseIndex(buf, keyCount, keysStart, wordsStart, keyOffsets, wordOffsets)
        }

        fun u16(b: java.nio.ByteBuffer, o: Int): Int =
            (b.get(o).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8)

        fun i32(b: java.nio.ByteBuffer, o: Int): Int =
            (b.get(o).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8) or
                ((b.get(o + 2).toInt() and 0xFF) shl 16) or ((b.get(o + 3).toInt() and 0xFF) shl 24)

        /**
         * 把长度数组展开成前缀和偏移数组（v2）；累加越界返回 null（调用方据此拒绝该文件）。
         *
         * - @param width 每项字节数：1 = u8（键长），2 = u16（词表长）
         *
         * 注意：累加必须用 Long 判溢出。每项最大 255/65535，条目数又只被 `need == capacity`
         * 松散约束（大索引可达千万级），Int 累加会静默回绕成负数 —— 负值随后被当成合法偏移，
         * 既可能让 `keyOffsets[keyCount] != keysLen` 的自洽校验失效，也可能在切片时越界。
         */
        fun expandLengths(b: java.nio.ByteBuffer, start: Int, count: Int, width: Int): IntArray? {
            val out = IntArray(count + 1)
            var acc = 0L
            if (width == 1) {
                for (i in 0 until count) {
                    acc += b.get(start + i).toLong() and 0xFFL
                    if (acc > Int.MAX_VALUE) return null
                    out[i + 1] = acc.toInt()
                }
            } else {
                for (i in 0 until count) {
                    acc += ((b.get(start + i * 2).toLong() and 0xFFL) or
                        ((b.get(start + i * 2 + 1).toLong() and 0xFFL) shl 8))
                    if (acc > Int.MAX_VALUE) return null
                    out[i + 1] = acc.toInt()
                }
            }
            return out
        }
    }
}
