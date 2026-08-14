package com.jinn.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自然码双拼转换单元测试。
 *
 * 覆盖对齐 libime `ShuangpinBuiltinProfile::Ziranma` 的键位映射：
 * 声母 zh→v、ch→i、sh→u，韵母键位表，零声母 o + 韵母键，
 * 单韵母 a/o/e 双写（aa/oo/ee）。
 */
class ShuangpinTest {

    @Test
    fun 声母映射_zh到v() {
        assertEquals("zhong", Shuangpin.toQuanpin("vs"))
    }

    @Test
    fun 声母映射_ch到i() {
        assertEquals("chi", Shuangpin.toQuanpin("ii"))
    }

    @Test
    fun 声母映射_sh到u() {
        assertEquals("shi", Shuangpin.toQuanpin("ui"))
    }

    @Test
    fun 普通声母不变() {
        assertEquals("ni", Shuangpin.toQuanpin("ni"))
        assertEquals("hao", Shuangpin.toQuanpin("hk"))
    }

    @Test
    fun 常用词双拼_你好() {
        // ni + hao → 你好（ao 键为 k）
        assertEquals("nihao", Shuangpin.toQuanpin("nihk"))
    }

    @Test
    fun 常用词双拼_中国() {
        // zhong(zh=v, ong=s) + guo(g, uo=o) → zhongguo
        assertEquals("zhongguo", Shuangpin.toQuanpin("vsgo"))
    }

    @Test
    fun 零声母_安() {
        // an → o + j
        assertEquals("an", Shuangpin.toQuanpin("oj"))
    }

    @Test
    fun 零声母_欧() {
        // ou → o + b
        assertEquals("ou", Shuangpin.toQuanpin("ob"))
    }

    @Test
    fun 单韵母双写_啊() {
        assertEquals("a", Shuangpin.toQuanpin("aa"))
    }

    @Test
    fun 末尾单键按声母前缀() {
        // v → zh
        assertEquals("zh", Shuangpin.toQuanpin("v"))
    }

    @Test
    fun 多字词连续双拼() {
        // 我(wo) + 爱(ai→ol) → wool → woai
        assertEquals("woai", Shuangpin.toQuanpin("wool"))
    }

    @Test
    fun 空输入返回空() {
        assertEquals("", Shuangpin.toQuanpin(""))
    }
}
