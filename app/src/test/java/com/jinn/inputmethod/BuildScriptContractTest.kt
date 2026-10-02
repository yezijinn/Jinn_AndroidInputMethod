package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `build_apk.py` 的源码契约守卫（`BUG.md` L-72 / L-77）。
 *
 * 构建脚本不在 JVM 单测覆盖范围内，但它的两条契约靠一次踩坑换来：
 *  - `--install` 的**每条**失败路径都必须**非 0 退出**（否则 CI / `&&` 链路把「没装上」当成功）；
 *    失败原因还要分开说 —— 「adb 用不了」与「没有设备」混成一句会把前者的可行动原因藏起来；
 *  - 设备列表必须按 `adb devices -l` 的**列位**解析（按子串 `"device"` 过滤会把表头与
 *    `no permissions` 行算成设备）；构建工具按**版本元组**排序（`Path` 排序是字符串比较，
 *    `9.0.0` 会盖过 `34.0.0`）。
 * 这里只做源码对拍 —— 与 `PanelRebuildWorkTest` 对 Kotlin 源码的做法一致。
 */
class BuildScriptContractTest {

    private val src: String by lazy { script() }

    @Test
    fun 安装失败必须非零退出() {
        val block = src.substringAfter("if args.install:").substringBefore("\nif __name__")
        // 四条失败路径：adb 不可用 / 无设备 / 多设备未指定 / 指定设备不在位。
        // 第一条是 2026-10-02 补的：此前「adb 不在 PATH」与「没有设备」报同一句，
        // 会把「装 platform-tools、配 PATH」这种可行动的原因说成「没有设备」。
        assertEquals(
            "安装段的四条失败路径都必须 sys.exit(1)",
            4, Regex("sys\\.exit\\(1\\)").findAll(block).count(),
        )
        for (msg in listOf("adb 不可用", "未检测到可用设备", "请用 --device", "不在已连接列表")) {
            assertTrue("安装段的失败文案缺「$msg」", msg in block)
        }
        assertTrue("失败文案必须打到 stderr", "file=sys.stderr" in block)
    }

    @Test
    fun 设备列表必须按列位解析() {
        assertTrue("必须有 parse_adb_devices（纯函数便于实测）", "def parse_adb_devices(" in src)
        assertTrue("必须用 adb devices -l", "adb devices -l" in src)
        assertFalse(
            "不得按子串 \"device\" 过滤设备行（表头 / no permissions 行都会被算成设备）",
            Regex("""if\s+"device"\s+in\s""").containsMatchIn(src),
        )
    }

    @Test
    fun 构建工具必须按版本元组排序() {
        assertTrue("必须按 version_key 排序", "candidates.sort(key=version_key, reverse=True)" in src)
        assertFalse(
            "不得直接 sort(reverse=True)：Path 排序是字符串比较，9.0.0 会排在 34.0.0 之前",
            "candidates.sort(reverse=True)" in src,
        )
    }

    private fun script(): String =
        listOf(File("build_apk.py"), File("../build_apk.py"))
            .first { it.isFile }.readText()
}
