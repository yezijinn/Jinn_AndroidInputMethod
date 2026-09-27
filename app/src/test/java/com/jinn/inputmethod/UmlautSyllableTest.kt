package com.jinn.inputmethod

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ü 系音节的 v / u 双写法。
 *
 * 字表统一用 v 型（`lve 略,掠,圙,锊` / `nve 虐,疟`），而合法音节表、全拼按键、双拼输出走的都是
 * u 型（`lue` / `nue`）。若加载时只登记 v 型，输入 `lue` 会「切分成功、单字候选为空」——
 * 略 / 掠 / 虐 / 疟 / 圙 的单字全打不出来（词语候选被别的词兜着，所以长期没暴露）。
 */
class UmlautSyllableTest {

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
        UserFrequency.resetForTest()
        PinyinEngine.loadFromTexts(
            "lve\t略,掠\nnve\t虐,疟\nyue\t月\n",
            // 刻意**不给词语**：`lue<TAB>略` 这种单字词会从词语路径满足断言，
            // 把「单字表别名缺失」这个真正的问题盖住（变异验证时它就不红了）。
            "",
            "lue\nlve\nnue\nnve\nyue\n",
        )
    }

    @Test
    fun 输入u型也能拿到v型单字() {
        assertTrue(
            "输入 lue 应能出「略」: ${PinyinEngine.query("lue").candidates}",
            PinyinEngine.query("lue").candidates.contains("略"),
        )
        assertTrue(
            "输入 nue 应能出「虐」: ${PinyinEngine.query("nue").candidates}",
            PinyinEngine.query("nue").candidates.contains("虐"),
        )
    }

    @Test
    fun v型写法照旧可用() {
        assertTrue("输入 lve 仍应能出「略」", PinyinEngine.query("lve").candidates.contains("略"))
        assertTrue("输入 nve 仍应能出「虐」", PinyinEngine.query("nve").candidates.contains("虐"))
    }

    /** 别名只对 v 型加：原生 ue 音节（jue / que / xue / yue）不该被动到 */
    @Test
    fun 原生ue音节不受影响() {
        assertTrue("yue 应出「月」", PinyinEngine.query("yue").candidates.contains("月"))
    }
}
