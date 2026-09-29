package com.jinn.inputmethod

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
 */
class BuildToolGatesTest {

    @Test
    fun 构建脚本里不得出现裸assert() {
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
        for (k in listOf("def write_bytes_atomically", "\".tmp\"", "os.replace(", "os.path.getsize")) {
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
    private fun toolScripts(): List<File> =
        listOf(File("tools"), File("../tools")).first { it.isDirectory }
            .walkTopDown().filter { it.isFile && it.name.endsWith(".py") }.toList()

    private fun source(name: String): String {
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
