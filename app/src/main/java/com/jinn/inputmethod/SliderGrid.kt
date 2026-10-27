package com.jinn.inputmethod

/**
 * 拖动条的步进网格算术（纯函数）：自定义页与单测共用同一份算式。
 *
 * 为什么单独抽出来：这套「上界取上整、末格由 `coerceIn` 收口」的写法**决定了上界一定可达**
 * —— 取上整后末格必然覆盖到上界，多出的那一截被收掉。而「上界够不到」曾被当成一类缺陷追过
 * 一轮（BUG.md L-1514）：真正的缺陷是**上界超出了可生效域**（滑杆承诺了一个不会生效的值），
 * 不是取整除不尽。抽成纯函数后这条性质由 `ClipboardLimitsTest` 逐域对拍，不必再读页内算式推断。
 *
 * 已知取舍（BUG.md L-983 / L-997）：存着的值不在格点上时（默认 500 条之于 1+50k、200 条之于 2+10k），
 * 滑块停在最近的格点、右值仍显示原值；用户拖动即从格点起算。
 *
 * ⚠ 这条取舍有硬边界（2026-10-27 实测）：四舍五入若把**默认值顶进末格**，滑块一打开就贴在
 * 最右端，此时再往右点 / 拖不产生回调（进度没变）⇒ 末格那一点值域只剩「先往左拖、再往右拖」
 * 一条路，与 L-1514 的「拖到头也到不了上界」同形。故步进必须让默认值落在格点上，
 * 推导见 `ClipboardPrefs.ITEM_KB_STEP`。
 *
 * 线程：纯函数，任意线程。
 */
internal object SliderGrid {

    /** 最大进度：取上整 ⇒ 最后一格必落在上界之内（末格可能不足一步，由 [valueAt] 收口）。 */
    fun progressMax(min: Int, max: Int, step: Int): Int =
        (((max - min) + step - 1) / step).coerceAtLeast(1)

    /** 某一进度对应的取值；末格越过上界的那一截在这里收到上界。 */
    fun valueAt(progress: Int, min: Int, max: Int, step: Int): Int =
        (min + progress * step).coerceIn(min, max)

    /** 已存的值对应的进度：四舍五入；上界落在格点上时「存着上界」正好是末格。 */
    fun progressFor(value: Int, min: Int, max: Int, step: Int): Int =
        Math.round((value - min).toFloat() / step).coerceIn(0, progressMax(min, max, step))
}
