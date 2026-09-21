package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 两套色板必须**逐名对齐**（`values/` 亮白 vs `values-night/` 暗黑）。
 *
 * 少写一个名字不会编译报错，也不会崩：该令牌在另一套主题下**静默退回**默认目录的值，
 * 表现为「亮色下某个部件还是深色」这类只有肉眼能发现的问题（配色与对比度双输）。
 * 本测试把两边的 `<color name>` 集合钉死相等。
 */
class ThemeColorParityTest {

    @Test
    fun 亮白与暗黑色板逐名对齐() {
        val light = colorNames(resFile("values/colors.xml"))
        val dark = colorNames(resFile("values-night/colors.xml"))
        assertEquals("暗黑色板多出的令牌: ${dark - light}", emptySet<String>(), dark - light)
        assertEquals("暗黑色板缺少的令牌（会静默退回亮色值）: ${light - dark}", emptySet<String>(), light - dark)
    }

    @Test
    fun 两套色板都非空() {
        // 防呆：路径写错时上面那条会因为「两边都是空集」而假绿
        val light = colorNames(resFile("values/colors.xml"))
        assertEquals(true, light.size > 30)
    }

    private fun colorNames(file: File): Set<String> =
        Regex("<color name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    /** 单测的 working dir 是模块目录（app/），但仍容忍从仓库根运行的情况 */
    private fun resFile(relative: String): File =
        listOf(File("src/main/res/$relative"), File("app/src/main/res/$relative"))
            .firstOrNull { it.isFile }
            ?: error("找不到资源文件: $relative")
}
