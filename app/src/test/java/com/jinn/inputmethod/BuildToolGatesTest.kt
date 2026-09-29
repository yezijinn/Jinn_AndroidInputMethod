package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 构建工具的闸门纪律（BUG.md L-106 / L-107 / L-108）。
 *
 * 这些 Python 脚本不在编译、lint 与任何断言的视野里，最容易「改回去也没人发现」。三条纪律：
 *  - **不许裸 `assert`**：`python -O` / `PYTHONOPTIMIZE=1` 会把它整体摘除 ⇒ 闸门静默失效（L-106）——
 *    本文件的守卫本身就是这条纪律的机械兜底（tools 下出现裸 assert 即红）；
 *  - **资产落盘走原子写**（`asset_io.write_bytes_atomically`）：直接写目标文件在中断 / 磁盘满时
 *    会留下半截资产（内置索引 / 下载包 / 文本资产），而它们要么进 APK 要么上传附件（L-107）；
 *  - **分片完备性核查必须在写盘之前**：四片一旦重叠，宁可一条产物都不写（L-108）。
 *
 * ⚠ 字面量钉一律走 [source]（= `TestSources.pyCode(rawSource(name))`，剥掉 `#` 注释）——
 * `BUG.md` L-128：Python 的 `#` 注释里同样写实现名，拿原文对拍时「把实现改成注释」能骗过全部四条钉。
 */
class BuildToolGatesTest {

    @Test
    fun 构建脚本里不得出现裸assert() {
        // ⚠ 这条**故意**用原文（`readLines()`）：判据是「行首出现 `assert `」这种**语句形态**，
        // 注释里的 `# assert …` 天然不以 `assert` 开头 ⇒ 原文不会误报；反之若改吃剥注释文本，
        // 三引号块里的示例仍会被保留（[TestSources.pyCode] 不识别 docstring），两者结果相同。

        val hits = toolScripts().flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, l) -> BARE_ASSERT.containsMatchIn(l) }
                .map { (i, l) -> "${f.name}:${i + 1} ${l.trim().take(60)}" }
        }
        assertTrue(
            "构建脚本里出现裸 assert（`python -O` 会摘除 ⇒ 闸门静默失效，见 BUG.md L-106）；" +
                "改成真判据：`if not 条件: raise SystemExit(\"…\")`：\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun 资产落盘必须走原子写() {
        val assetIo = source("asset_io.py")
        // 临时件既是**同目录**（`os.replace` 只在同文件系统内原子）、名字又带进程号（L-135）⇒ ".tmp."
        for (k in listOf("def write_bytes_atomically", ".tmp.", "os.replace(", "os.path.getsize")) {
            assertTrue("asset_io.py 的原子写实现里缺少 $k（BUG.md L-107）", k in assetIo)
        }
        assertTrue(
            "写文本资产必须**调用**原子写（不能只在注释里提它，也不能退回 open(path, \"wb\").write）",
            assetIo.substringAfter("def write_asset_text").contains("write_bytes_atomically(path, "),
        )
        val build = source("build_dicts.py")
        assertTrue(
            "write_bytes 必须**调用**原子写（不能只在注释里提它，也不能退回 open(path, \"wb\").write）",
            build.substringAfter("def write_bytes(path, data):").contains("write_bytes_atomically(path, "),
        )
    }

    @Test
    fun 分片完备性核查必须在写盘之前() {
        val build = source("build_dicts.py")
        assertTrue("必须有分片完备性真判据（BUG.md L-108）", build.contains("def check_parts_partition"))
        // 调用（不是定义）必须排在第一个产物落盘之前 —— 顺序反了就会出现「先写坏包、再报错」
        val call = build.indexOf("    check_parts_partition()")
        val firstWrite = build.indexOf("write_bytes(os.path.join(A, \"pinyin_index.bin.xz\")")
        assertTrue("找不到分片核查的调用点（是不是被注释掉了？）", call > 0)
        assertTrue("找不到第一个写盘点（命名变了？）", firstWrite > 0)
        assertTrue("分片核查必须早于写盘（check=$call write=$firstWrite）", call < firstWrite)
        assertTrue(
            "重叠 / 重复时必须中止（SystemExit），且检查要覆盖四片",
            build.substringAfter("def check_parts_partition").substringBefore("def build_parts")
                .let { it.contains("raise SystemExit") && it.contains("(1, 2, 3, 4)") },
        )
    }

    /** `tools/` 下所有 Python 脚本（含子目录）；工作目录不同时回退上一级。 */
    /**
     * 出货路径的写盘必须原子（BUG.md L-122）。
     *
     * 这几支脚本写的是「进 APK 的资产 / Release 附件 / 仓库内 Kotlin 源码」——裸写
     * `open(..., "w"/"wb")` 在中断 / 磁盘满 / 进程被杀时留下半截文件：进包 ⇒ 运行期解压失败；
     * 当附件 ⇒ 用户下到坏包（与文档里的 checksum 对不上）；写成**源码** ⇒ 照样编译，只静默缺尾。
     * （写 `out/` 中间产物不受此限，故只钉这五支 + 分片脚本。）
     */
    @Test
    fun `出货路径的写盘必须原子`() {
        for (k in listOf(
            "build_dict_index.py", "export_dicts.py", "merge_game_dicts.py",
            "convert_rime_tencent.py", "gen_shuangpin_tables.py",
        )) {
            val src = source(k)
            assertTrue(
                "$k 写的是出货文件（资产 / 附件 / 源码），必须走 write_bytes_atomically（BUG.md L-122）",
                "write_bytes_atomically(" in src && "asset_io" in src,
            )
        }
        val split = source("split_dict.py")
        assertTrue("split_dict.py 写 Release 分片，必须 tmp + os.replace 原子替换", "os.replace(" in split)
        assertTrue("split_dict.py 用到 os 就必须 import os（这次实测：漏了就 NameError）", "import os" in split)
    }

    /**
     * 元守卫：本文件的字面量钉必须吃**剥注释**后的文本（`BUG.md` L-128）。
     *
     * 失败说明有人把 [source] 退回直读原文（例如改回 `rawSource(name)` 或换成 `codeOf`）——
     * 那样「把实现改成注释」能让四条钉同时保持全绿（2026-09-29 变异实证，见回执 B-101）。
     */
    @Test
    fun 工具链守卫必须走剥注释路径() {
        val self = TestSources.codeOf(
            listOf(
                File("src/test/java/com/jinn/inputmethod/BuildToolGatesTest.kt"),
                File("app/src/test/java/com/jinn/inputmethod/BuildToolGatesTest.kt"),
            ).first { it.isFile }.readText(),
        )
        // ⚠ 必须**行首锚定**取实现行：本用例自己的代码里也有同名字面量（`substringAfter` 会命中它）
        val impl = Regex("""(?m)^\s*private fun source\(name: String\): String =.*$""")
            .find(self)?.value ?: error("找不到 source() 的实现行（改名了？请同步本用例）")
        assertTrue(
            "字面量钉必须走 TestSources.pyCode（L-128），当前实现是：$impl",
            "TestSources.pyCode(" in impl,
        )
    }

    /**
     * 其余出货脚本的**写盘前真判据**必须在位、且早于写盘（`BUG.md` L-140）。
     *
     * `build_dicts.py` 早有 `check_parts_partition`（L-108 立的分片核查）；这三支写的是 Release 附件 /
     * 分片，此前**只有打印没有中止** ⇒ 输入坏行、缺前提时照样产出「看起来正常」的残缺包。
     * 判据：① 三支都要有 `def check_before_write`；② 调用点必须在写盘**之前**（顺序反了就是
     * 「先写坏包、再报错」，与 L-108 同一条教训）。字面量钉吃的是剥注释文本（L-128）。
     */
    @Test
    fun 其余出货脚本的写盘前判据必须存在且早于写盘() {
        for ((name, writeMark) in listOf(
            "split_dict.py" to "os.replace(",
            "convert_rime_tencent.py" to "write_bytes_atomically(",
            "merge_game_dicts.py" to "write_bytes_atomically(",
        )) {
            val src = source(name)
            assertTrue("$name 缺少写盘前真判据 `def check_before_write`（BUG.md L-140）",
                "def check_before_write" in src)
            // 调用点（任意缩进；`def check_before_write` 那行不算）
            val call = Regex("""(?m)^[ \t]+check_before_write\(""").find(src)?.range?.first ?: -1
            val write = src.indexOf(writeMark)
            assertTrue("$name 找不到 check_before_write 的调用点（被注释掉 / 改名了？）", call > 0)
            assertTrue("$name 找不到写盘点（$writeMark）", write > 0)
            assertTrue("$name 的写盘前判据必须早于写盘（check=$call write=$write）", call < write)
        }
    }

    /**
     * 工具链的**临时件名必须带进程号**（`BUG.md` L-135）。
     *
     * 固定名（`path + ".tmp"`）在两进程并发跑同一支脚本时会互相截断 / 抢走：本机（Windows）
     * 实测表现为**响亮失败**（长度校验中止、`finally` 清理竞态抛 `FileNotFoundError` 盖掉真实原因、
     * 残留临时件）；按 POSIX 语义构造的模型里更重 —— 替换之后落后的写者仍往**目标 inode** 写 ⇒
     * **静默**产出「A 一半 + B 一半」的资产（另一支已「成功」返回）。带 pid 后各写各的临时件，
     * 终态必是某一个写者的完整内容。清理也要无竞态（`try/except FileNotFoundError`）。
     */
    @Test
    fun 工具链的临时件名必须带进程号() {
        for (name in listOf("asset_io.py", "split_dict.py")) {
            val src = source(name)
            assertTrue("$name 的临时件名必须带进程号（防并发互踩）", "os.getpid()" in src)
            assertFalse("$name 不得再用固定名临时件（`+ \".tmp\"`）", "+ \".tmp\"" in src)
            assertTrue(
                "$name 的失败清理必须无竞态（`exists` 再 `remove` 会被别人抢走）",
                "except FileNotFoundError" in src,
            )
        }
    }

    private fun toolScripts(): List<File> =
        listOf(File("tools"), File("../tools")).first { it.isDirectory }
            .walkTopDown().filter { it.isFile && it.name.endsWith(".py") }.toList()

    /**
     * `tools/dict_builder/<name>` 的**代码文本**（已剥 `#` 注释 —— `BUG.md` L-128）。
     *
     * 所有字面量钉都必须用这个入口：`#` 注释里同样写着实现名（「为什么必须这么写」），
     * 拿原文对拍时「把实现改成注释」能让全部四条钉保持全绿（2026-09-29 变异实证）。
     */
    private fun source(name: String): String = TestSources.pyCode(rawSource(name))

    /** 原始文本：只给「必须看注释」的判据用（当前只有裸 `assert` 那条，理由见该用例）。 */
    private fun rawSource(name: String): String {
        val f = listOf(
            File("tools/dict_builder/$name"),
            File("../tools/dict_builder/$name"),
        ).firstOrNull { it.isFile } ?: error("找不到 tools/dict_builder/$name（工作目录变了？）")
        return f.readText()
    }

    private companion object {
        /** 行首（允许缩进）的 `assert ` —— Python 的 assert 语句形态 */
        val BARE_ASSERT = Regex("""^\s*assert\s""")
    }
}
