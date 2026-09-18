package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 26 键键盘的按键触发逻辑压力测试（纯 JVM，不依赖真机）。
 *
 * 模拟"按键 → 引擎"这条链路：字母键走 `Shuangpin.toQuanpin` + `query`；空格/候选点选走 `consumption`
 * 后按实际消费量裁掉 composing；全部消费后走 `predict`。每个动作单独计时，给出 p50/p90/p99/max
 * 与最慢几键的现场（当时的 composing 长度），用来量化"哪一类按键会慢"：
 * 连续、高频、组合（字母+空格+退格+候选+预测+方案切换）。
 *
 * 说明：Android 单元测试编译对着 android.jar，没有 java.lang.management / com.sun.management，
 * 量不到精确分配量与 GC 次数，所以这里只报耗时（真机上的内存指标用 gfxinfo/logcat 量）。
 */
class KeyboardStressTest {

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("未找到 asset $name")
    }

    private fun indexBytes(): ByteArray {
        for (p in listOf("src/main/assets/pinyin_index.bin.xz", "app/src/main/assets/pinyin_index.bin.xz")) {
            val f = File(p)
            if (f.isFile) return XZInputStream(f.inputStream()).use { it.readBytes() }
        }
        throw AssertionError("未找到索引资产")
    }

    private fun loadEngine() {
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromIndexBytes(
            indexBytes(), asset("pinyin_chars.txt"), asset("pinyin_syllables.txt"), asset("common_chars.txt"),
        )
    }

    @After
    fun tearDown() = PinyinEngine.resetForTest()

    /** 一台模拟键盘：只复刻按键触发的逻辑，不碰任何 View */
    private inner class SimKeyboard(val scheme: ShuangpinScheme) {
        val composing = StringBuilder()
        var lastCommitted = ""

        private fun quanpin(): String =
            if (scheme == ShuangpinScheme.QUANPIN) composing.toString()
            else Shuangpin.toQuanpin(composing.toString(), scheme)

        /** 按键位对应的 composing 裁剪量（双拼两键一音节） */
        private fun keysFor(used: PinyinEngine.Consumption): Int =
            if (scheme == ShuangpinScheme.QUANPIN) used.quanpinChars
            else (used.syllables * 2).coerceAtMost(composing.length)

        fun pressLetter(c: Char): Int {
            composing.append(c)
            return PinyinEngine.query(quanpin()).candidates.size
        }

        fun pressSpace(): Boolean {
            val q = quanpin()
            val first = PinyinEngine.query(q).candidates.firstOrNull() ?: return false
            val keys = keysFor(PinyinEngine.consumption(q, first))
            if (keys >= composing.length) {
                composing.clear()
                lastCommitted = first
                PinyinEngine.predict(first)
            } else {
                composing.delete(0, keys)
            }
            return true
        }

        fun tapCandidate(idx: Int): Boolean {
            val q = quanpin()
            val cand = PinyinEngine.query(q).candidates.getOrNull(idx) ?: return false
            val keys = keysFor(PinyinEngine.consumption(q, cand))
            if (keys >= composing.length) {
                composing.clear()
                lastCommitted = cand
                PinyinEngine.predict(cand)
            } else {
                composing.delete(0, keys)
            }
            return true
        }

        fun pressBackspace() {
            if (composing.isNotEmpty()) composing.deleteCharAt(composing.length - 1)
        }

        fun pressEnter() {
            composing.clear()
            lastCommitted = ""
        }

        fun tapPrediction(): Boolean {
            val pred = PinyinEngine.predict(lastCommitted).firstOrNull() ?: return false
            lastCommitted += pred
            return true
        }
    }

    /** 一个场景的采样集 */
    private class Stat(val name: String) {
        val times = ArrayList<Long>(1024)
        val tags = ArrayList<String>(1024)

        fun p(q: Double): Double {
            if (times.isEmpty()) return 0.0
            val s = times.sorted()
            return s[((s.size - 1) * q).toInt()] / 1e6
        }

        fun max(): Double = (times.maxOrNull() ?: 0L) / 1e6

        /** 最慢的 [n] 键现场，便于定位是哪种输入触发的 */
        fun slowest(n: Int): List<String> =
            times.indices.sortedByDescending { times[it] }.take(n)
                .map { "第 ${it + 1} 键 ${tags[it]} = ${"%.2f".format(times[it] / 1e6)}ms" }

        fun report() {
            println(
                "STRESS ${name.padEnd(20)} n=${times.size.toString().padStart(4)}  " +
                    "p50=${"%.3f".format(p(0.5))}ms  p90=${"%.3f".format(p(0.9))}ms  " +
                    "p99=${"%.3f".format(p(0.99))}ms  max=${"%.3f".format(max())}ms",
            )
        }
    }

    /** 跑一个场景：每键一次计时采样，现场取自当时的 composing 长度 */
    private fun scenario(name: String, body: (SimKeyboard, (() -> Unit) -> Unit) -> Unit): Stat {
        val kb = SimKeyboard(ShuangpinScheme.ZIRANMA)
        val st = Stat(name)
        val press: (() -> Unit) -> Unit = { action ->
            val tag = "composing=${kb.composing.length}"
            val t0 = System.nanoTime()
            action()
            st.times.add(System.nanoTime() - t0)
            st.tags.add(tag)
        }
        body(kb, press)
        st.report()
        st.slowest(3).forEach { println("STRESS   └─ 最慢: $it") }
        return st
    }

    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    @Test
    fun 各类按键的触发耗时() {
        loadEngine()

        // ① 连续快打：三行轮扫 6 遍，composing 一路涨到 80 字符上下（最坏路径）
        val s1 = scenario("① 连续快打(长缓存)") { kb, p ->
            repeat(6) { rows.forEach { r -> r.forEach { c -> p { kb.pressLetter(c) } } } }
        }

        // ② 对照组：同样节奏，每 4 个字母退 3 次，缓存始终很短
        val s2 = scenario("② 短缓存对照") { kb, p ->
            repeat(20) {
                "qwer".forEach { c -> p { kb.pressLetter(c) } }
                repeat(3) { p { kb.pressBackspace() } }
            }
        }

        // ③ 字母 + 空格上屏
        val s3 = scenario("③ 字母+空格上屏") { kb, p ->
            repeat(60) {
                "nih".forEach { c -> p { kb.pressLetter(c) } }
                p { kb.pressSpace() }
            }
        }

        // ④ 字母 + 候选点选 + 预测
        val s4 = scenario("④ 字母+点候选+预测") { kb, p ->
            repeat(40) {
                "nihao".forEach { c -> p { kb.pressLetter(c) } }
                p { kb.tapCandidate(2) }
                p { kb.tapPrediction() }
            }
        }

        // ⑤ 退格狂按
        val s5 = scenario("⑤ 退格狂按") { kb, p ->
            "zhongguorenmin".forEach { c -> p { kb.pressLetter(c) } }
            repeat(160) { p { kb.pressBackspace() } }
        }

        // ⑥ 同键狂按
        val s6 = scenario("⑥ 同键 a×200") { kb, p ->
            repeat(200) { p { kb.pressLetter('a') } }
        }

        // ⑦ 字母 + 回车清空
        val s7 = scenario("⑦ 字母+回车清空") { kb, p ->
            repeat(40) {
                "woaini".forEach { c -> p { kb.pressLetter(c) } }
                p { kb.pressEnter() }
            }
        }

        println("STRESS --- 汇总（ms）---")
        listOf(s1, s2, s3, s4, s5, s6, s7).forEach {
            println(
                "STRESS ${it.name.padEnd(20)} p50=${"%.3f".format(it.p(0.5))} " +
                    "p90=${"%.3f".format(it.p(0.9))} p99=${"%.3f".format(it.p(0.99))} " +
                    "max=${"%.3f".format(it.max())}  最慢键: ${it.slowest(1).firstOrNull() ?: "-"}",
            )
        }

        // 阈值宽松：只守"别出现秒级卡顿"这类灾难性退化
        listOf(s1, s2, s3, s4, s5, s6, s7).forEach {
            assertTrue("${it.name} 最慢一键 ${"%.1f".format(it.max())}ms 超过 500ms", it.max() < 500.0)
        }
    }

    @Test
    fun 七套方案的首次建表代价() {
        val schemes = ShuangpinScheme.entries.filter { it != ShuangpinScheme.QUANPIN }
        var worst = 0.0
        var worstName = ""
        for (sc in schemes) {
            loadEngine()                                   // 故意不预热，量首次使用
            val kb = SimKeyboard(sc)
            val t0 = System.nanoTime()
            "nihao".forEach { kb.pressLetter(it) }
            val ms = (System.nanoTime() - t0) / 1e6
            println("STRESS 首次用 ${sc.name.padEnd(9)} 打 5 键 = ${"%.2f".format(ms)}ms")
            if (ms > worst) { worst = ms; worstName = sc.name }
        }
        println("STRESS 最慢首次方案 = $worstName ${"%.2f".format(worst)}ms（含该方案键位表首次构建）")
    }
    /** 乱序/超长拼音的模糊测试：固定种子，可复现；报最慢的 5 个输入 */
    @Test
    fun 乱序与超长拼音的模糊测试() {
        loadEngine()

        class Case(val tag: String, val input: String, var ms: Double, var cand: Int, var partial: String)

        val rnd = kotlin.random.Random(20260918)
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val cases = ArrayList<Case>()

        // ① 纯随机字母：长度 1~40
        repeat(400) {
            val len = 1 + rnd.nextInt(40)
            cases.add(Case("随机字母 len=$len", (1..len).map { letters[rnd.nextInt(26)] }.joinToString(""), 0.0, 0, ""))
        }
        // ② 真实拼音打乱顺序（乱序但不缺字母）
        val real = "zhongguorenminzhengzhixieshanghuiyi"
        repeat(60) {
            cases.add(Case("真实拼音打乱", real.toList().shuffled(rnd).joinToString(""), 0.0, 0, ""))
        }
        // ③ 合法音节拼接 + 尾巴损坏（最容易制造"前缀能切、尾巴不通"）
        val syls = listOf("ba", "na", "lan", "zhong", "guo", "ren", "min", "zhi", "xie", "shang", "hui", "yi", "nihao")
        repeat(120) {
            val n = 1 + rnd.nextInt(12)
            cases.add(Case("音节拼接+坏尾", (1..n).map { syls[rnd.nextInt(syls.size)] }.joinToString("") + "z", 0.0, 0, ""))
        }
        // ④ 超长：把上面几种拉到 60 / 120 / 240 字符
        for (len in listOf(60, 120, 240)) {
            cases.add(Case("超长随机 len=$len", (1..len).map { letters[rnd.nextInt(26)] }.joinToString(""), 0.0, 0, ""))
            cases.add(Case("超长音节流 len≈$len", real.repeat(10).substring(0, len), 0.0, 0, ""))
            cases.add(Case("超长同键 len=$len", "a".repeat(len), 0.0, 0, ""))
        }

        for (c in cases) {
            PinyinEngine.query(c.input)                       // 预热（缓存/缺页）
            val t0 = System.nanoTime()
            val r = PinyinEngine.query(c.input)
            c.ms = (System.nanoTime() - t0) / 1e6
            c.cand = r.candidates.size
            c.partial = r.partialSyllable.take(12)
        }

        val sorted = cases.sortedByDescending { it.ms }
        val times = cases.map { it.ms }.sorted()
        println("FUZZ 用例=${cases.size}  p50=${"%.3f".format(times[times.size / 2])}ms  " +
            "p99=${"%.3f".format(times[(times.size * 99) / 100])}ms  max=${"%.3f".format(times.last())}ms")
        println("FUZZ 最慢 5 例：")
        sorted.take(5).forEach {
            println("FUZZ   ${"%.3f".format(it.ms)}ms  [${it.tag}] len=${it.input.length} 候选=${it.cand} 残码=\"${it.partial}\"")
        }
        val worst = sorted.first()
        assertTrue("乱序/超长最坏一例 ${"%.1f".format(worst.ms)}ms（${worst.tag}）超过 200ms", worst.ms < 200.0)
    }

}
