package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Stage 2 护栏：
 *  1. 构建器对拍，设备端 Kotlin 构建器产出的索引字节，必须与构建脚本（Python）产出的
 *     完全一致（同一格式、同一顺序），否则两类索引将无法互换读取；
 *  2. 逐段合并语义，「运行时 ∪ 基础索引 ∪ 可选索引」的结果必须与旧实现一致：
 *     先到先得 + 去重，基础在前、可选包追加。
 *
 * 对拍分两层（缺任一层就有假绿口子）：
 *  - **冻结层**：与随仓库保存的 fixture 产物逐字节比 —— 不需要 Python，永远跑得了；
 *  - **实跑层**：真去执行 `tools/dict_builder/build_dict_index.py`（只喂 tiny fixture，不产资产）
 *    —— 脚本改了写盘顺序 / 字节序 / 长度数组宽度却不同步重生成 fixture 时，这一层会红。
 * 本机没有 python 解释器时实跑层按 [Assume] 跳过（冻结层照跑）。
 */
class IndexBuilderParityTest {

    private fun res(name: String): File {
        for (p in listOf("src/test/resources/$name", "app/src/test/resources/$name")) {
            val f = File(p)
            if (f.isFile) return f
        }
        throw AssertionError("未找到测试资源 $name")
    }

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
            // 文本资产自 2026-09-26 起以 .xz 存进 APK（体积余量），测试按短名取、自动解压
            val xz = File("$p.xz")
            if (xz.isFile) {
                return org.tukaani.xz.XZInputStream(xz.inputStream())
                    .use { String(it.readBytes(), Charsets.UTF_8) }
            }
        }
        throw AssertionError("未找到 asset $name")
    }

    private fun fixtureText(): String = res("dict_index_fixture.txt").readText().trim('\n')

    private fun fixtureBytes(): ByteArray =
        XZInputStream(res("dict_index_fixture.bin.xz").inputStream()).use { it.readBytes() }

    /** 冻结层：与仓库里保存的脚本产物逐字节比（不执行脚本，任何机器都跑得动） */
    @Test
    fun 设备端构建器与冻结的脚本产物逐字节一致() {
        val pythonBuilt = fixtureBytes()
        val stamp = PhraseIndex.of(pythonBuilt)!!.sourceStamp      // 用同一摘要才能逐字节比对
        val kotlinBuilt = PhraseIndex.build(fixtureText().lineSequence(), stamp)
        assertArrayEquals("Kotlin 构建器与冻结的脚本产物必须逐字节一致",
            pythonBuilt, kotlinBuilt)
    }

    /**
     * 实跑层（L-1141）：真执行构建脚本，对同一 fixture 文本现算一份索引。
     *
     * 两层断言各挡一类漂移：
     *  ① 脚本产出 ≠ 冻结产物 ⇒ fixture 已过期（脚本改过而没重生成）；
     *  ② 脚本产出 ≠ 设备端构建器 ⇒ 两端格式/顺序真的分叉。
     * 只喂 tiny fixture（脚本侧只调纯函数 `build_index_bytes`），不写 `assets/`、不产全量词库。
     */
    @Test
    fun 现跑构建脚本时两端产出仍逐字节一致() {
        val dir = dictBuilderDir()
        val py = pythonExe()
        Assume.assumeTrue(
            "未找到 tools/dict_builder 或 python 解释器，跳过脚本实跑对拍（冻结层仍已覆盖）",
            dir != null && py != null,
        )
        val text = fixtureText()
        val fresh = runPythonBuilder(py!!, dir!!, text)
        assertArrayEquals(
            "脚本对同一 fixture 的产出与冻结产物不一致 —— fixture 已过期，请用 --fixture 重新生成",
            fixtureBytes(), fresh,
        )
        val stamp = PhraseIndex.of(fresh)?.sourceStamp ?: throw AssertionError("脚本产出无法解析")
        assertArrayEquals(
            "设备端构建器与本次脚本产出必须逐字节一致（格式 / 段序 / 长度数组宽度 / 摘要同源）",
            fresh, PhraseIndex.build(text.lineSequence(), stamp),
        )
    }

    // ── 实跑层的脚手架：进程调用只在本文件用，且一律落在系统临时目录 ──

    /** `tools/dict_builder` 目录（测试工作目录可能是仓库根，也可能是 `app/`） */
    private fun dictBuilderDir(): File? =
        listOf("tools/dict_builder", "../tools/dict_builder")
            .map { File(it) }
            .firstOrNull { File(it, "build_dict_index.py").isFile }

    /** 可用的 python 解释器（Windows 上通常是 `python`，部分环境只有 `py`） */
    private fun pythonExe(): String? = listOf("python", "python3", "py").firstOrNull { exe ->
        runCatching {
            val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    /**
     * 跑一次脚本并取回它产出的索引字节。
     *
     * 用临时驱动脚本**只调纯函数**（不执行 `main()`）——`main()` 会写 `assets/pinyin_index.bin.xz`
     * 与 `tools/dict_builder/out/`，测试绝不能碰资产。
     */
    private fun runPythonBuilder(py: String, dir: File, text: String): ByteArray {
        val tmpTxt = File.createTempFile("jinn_parity_in", ".txt")
        val tmpBin = File.createTempFile("jinn_parity_out", ".bin")
        val driver = File.createTempFile("jinn_parity_driver", ".py")
        try {
            tmpTxt.writeText(text, Charsets.UTF_8)
            driver.writeText(DRIVER, Charsets.UTF_8)
            val proc = ProcessBuilder(
                py, driver.absolutePath, dir.absolutePath, tmpTxt.absolutePath, tmpBin.absolutePath,
            ).redirectErrorStream(true).start()
            val log = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                proc.destroy()
                throw AssertionError("构建脚本超时（120s）")
            }
            if (proc.exitValue() != 0) {
                throw AssertionError("构建脚本执行失败（exit=${proc.exitValue()}）：$log")
            }
            return tmpBin.readBytes()
        } finally {
            tmpTxt.delete(); tmpBin.delete(); driver.delete()
        }
    }

    private companion object {
        /** 驱动脚本：把 fixture 文本喂给脚本的纯函数，产出裸索引字节 */
        val DRIVER = """
            import io, sys
            sys.path.insert(0, sys.argv[1])
            from build_dict_index import build_index_bytes
            text = io.open(sys.argv[2], encoding="utf-8").read().strip("\n")
            io.open(sys.argv[3], "wb").write(build_index_bytes(text))
        """.trimIndent()
    }

    @Test
    fun 索引可被两端构建器互相读取() {
        val stamp = 123456789L
        val mine = PhraseIndex.build(fixtureText().lineSequence(), stamp)
        val parsed = PhraseIndex.of(mine) ?: throw AssertionError("自建索引无法解析")
        assertEquals(stamp, parsed.sourceStamp)
        for (line in fixtureText().lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            assertEquals(
                "键 ${line.substring(0, tab)} 的词表读回不一致",
                line.substring(tab + 1).split('|'),
                parsed.wordsFor(line.substring(0, tab))?.toList(),
            )
        }
    }

    @Test
    fun 可选索引按旧语义合并_基础在前去重追加() {
        val chars = asset("pinyin_chars.txt")
        val syllables = asset("pinyin_syllables.txt")

        // 基础：nihao → 你好|你号 ；可选包：nihao → 你好|拟好|尼豪（含重复项与非重复项）
        val baseText = "nihao\t你好|你号\n"
        val optionalText = "nihao\t你好|拟好|尼豪\n"

        PinyinEngine.resetForTest()
        PinyinEngine.loadFromIndexBytes(
            PhraseIndex.build(baseText.lineSequence(), 1L), chars, syllables, null,
        )
        val optionalIndex = PhraseIndex.of(PhraseIndex.build(optionalText.lineSequence(), 2L))!!
        PinyinEngine.setOptionalIndexesForTest(listOf(optionalIndex))

        val words = PinyinEngine.query("nihao").candidates.toList()
        // 期望：基础在前、可选包中未出现过的追加（「你好」重复项不重复出现）
        assertTrue("应包含基础词与可选包追加词: $words", words.contains("你号") && words.contains("尼豪"))
        assertEquals("重复词不应出现两次", 1, words.count { it == "你好" })
    }

    /**
     * 回归：同键的连续多行必须合并进同一个词表（语义与文本路径的合并一致）。
     *
     * 旧实现按行写出，同键第二个词表会被二分查找永久跳过，静默少词，无日志无异常。
     * 注意「无重复键输入逐字节不变」已由上面的对拍用例钉住：本用例只覆盖合并语义。
     */
    @Test
    fun 同键的连续多行被合并进同一个词表() {
        // 字典序：ni < nihao（"ni" 是 "nihao" 的前缀），同键行必须相邻
        val text = "ni\t你\nnihao\t你好\nnihao\t拟好|你号\n"
        val parsed = PhraseIndex.of(PhraseIndex.build(text.lineSequence(), 7L))
            ?: throw AssertionError("自建索引无法解析")
        assertEquals("同键只应产生一个键", 2, parsed.size)
        assertEquals(
            "同键两行的词表应按出现顺序用 | 拼接",
            listOf("你好", "拟好", "你号"),
            parsed.wordsFor("nihao")?.toList(),
        )
        assertEquals(listOf("你"), parsed.wordsFor("ni")?.toList())
    }

    /**
     * 回归：同键合并按出现顺序去重（与文本路径 `existing + kept.filter { seen }` 同语义）。
     *
     * 裸拼接会让重复词在词表里出现两次，虽被查询侧的 LinkedHashSet 掩盖，
     * 但索引体积与 [PhraseIndex.wordsFor] 的原始语义都被放大。
     */
    @Test
    fun 同键合并按出现顺序去重() {
        val text = "nihao\t你好|拟好\nnihao\t拟好|你号\nnihao\t你好\n"
        val parsed = PhraseIndex.of(PhraseIndex.build(text.lineSequence(), 9L))
            ?: throw AssertionError("自建索引无法解析")
        assertEquals("同键只应产生一个键", 1, parsed.size)
        assertEquals(
            "重复词只保留首次出现的位置",
            listOf("你好", "拟好", "你号"),
            parsed.wordsFor("nihao")?.toList(),
        )
    }

    /**
     * 收尾复位：PinyinEngine 是单例，本文件会用真实 asset 注入词库；
     * 不复位会把「真实单字表/音节表」留给后续测试类，破坏它们的既有假设。
     */
    @After
    fun tearDown() {
        PinyinEngine.resetForTest()
    }
}
