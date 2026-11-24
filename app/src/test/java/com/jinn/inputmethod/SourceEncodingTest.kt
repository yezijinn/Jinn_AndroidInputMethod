package com.jinn.inputmethod

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码文本里不得留下 U+FFFD 替换字符。
 *
 * 起因：编码转换事故会把整段中文注释变成一串问号或替换字符，编译不受影响，
 * 但那段 KDoc 从此不可读（曾经发生过一次，整块翻译收尾说明全毁）。
 */
class SourceEncodingTest {

    private fun mainRoot(): File {
        val candidates = listOf("src/main", "app/src/main")
        return candidates.map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("找不到主源码目录（当前工作目录=${File("").absolutePath}）")
    }

    @Test
    fun 主源码与资源不得残留替换字符() {
        val bad = mainRoot()
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .mapNotNull { file ->
                val text = file.readText()
                val index = text.indexOf('\uFFFD')
                if (index < 0) {
                    null
                } else {
                    val line = text.take(index).count { it == '\n' } + 1
                    "${file.path}:$line"
                }
            }
            .toList()
        assertTrue("源码含 U+FFFD（编码损坏）：$bad", bad.isEmpty())
    }
}
