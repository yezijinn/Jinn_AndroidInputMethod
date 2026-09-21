package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 符号分组自定义顺序（长按拖拽排序的持久化层）。
 *
 * 关键不变量：结果恒为 [SymbolOrder.DEFAULT] 的一个排列 —— 缺项、脏 label、重复项
 * 都必须被修正，否则视图会**少一个分组**（用户只会看到「标签不见了」，排查不到原因）。
 */
class SymbolOrderTest {

    private val default = SymbolOrder.DEFAULT

    @Test
    fun 空串走默认顺序() {
        assertEquals(default, SymbolOrder.parse(""))
        assertEquals(default, SymbolOrder.parse("   "))
    }

    @Test
    fun 自定义顺序原样保留并往返稳定() {
        val swapped = listOf("半角", "全角") + default.drop(2)
        assertEquals(swapped, SymbolOrder.parse(SymbolOrder.serialize(swapped)))
    }

    @Test
    fun 缺失的分组补到末尾_未知的剔除() {
        val got = SymbolOrder.parse("半角,全角,不存在的组")
        assertEquals(listOf("半角", "全角"), got.take(2))
        assertEquals(default.size, got.size)       // 一个不少
        assertEquals(default.toSet(), got.toSet()) // 一个不多
    }

    @Test
    fun 重复项去重且保留首次位置() {
        val got = SymbolOrder.parse("半角,半角,全角,半角")
        assertEquals(listOf("半角", "全角"), got.take(2))
        assertEquals(default.size, got.size)
    }

    @Test
    fun 移动_同位置原样_越界钳位() {
        assertEquals(default, SymbolOrder.move(default, 0, 0))
        assertEquals(default, SymbolOrder.move(default, -5, 0))
        val moved = SymbolOrder.move(default, 0, 2)
        assertEquals(listOf(default[1], default[2], default[0]), moved.take(3))
        assertEquals(default.last(), SymbolOrder.move(default, default.lastIndex, 0).first())
    }

    @Test
    fun 分组对象按用户顺序返回() {
        val raw = SymbolOrder.serialize(listOf("半角", "全角") + default.drop(2))
        val groups = SymbolOrder.groupsInOrder(raw)
        assertEquals(default.size, groups.size)
        assertEquals("半角", groups.first().label)
        assertEquals("全角", groups[1].label)
    }
}
