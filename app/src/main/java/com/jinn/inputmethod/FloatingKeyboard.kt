package com.jinn.inputmethod

/**
 * 悬浮键盘的**纯逻辑**：绑定判定、几何解析 / 夹取 / 缩放换算。
 *
 * 这里不碰任何 View 与窗口 —— 窗口侧全部在 [JinnIme]（`setGravity` / `setLayout` / 拖动 / insets），
 * 本文件只放能进 JVM 单测的算术与字符串处理。
 *
 * 三条口径（写死在实现里，页面与 IME 两侧共用）：
 *  - **白名单语义**：只有被勾选的包进入悬浮态；绑定为空 ⇒ 谁都不悬浮（不是「全部悬浮」）。
 *  - 位置 / 尺寸**竖屏与横屏各存一份**：键名不同，旋转回来各自复原。
 *  - 几何一律先夹取再使用：屏幕 / 方向 / 字体密度变了之后，旧坐标不得把键盘留在屏外。
 */
object FloatingKeyboard {

    /** 竖屏浮层宽度占屏幕宽度的比例（留出左右视线的余量，又不至于窄到点不准） */
    const val PORTRAIT_WIDTH_FRACTION = 0.92f

    /** 横屏浮层宽度占屏幕宽度的比例（横屏是游戏 / 视频的主场，宽度必须给画面让位） */
    const val LANDSCAPE_WIDTH_FRACTION = 0.70f

    /**
     * 浮层高度上限（占可用高度）。
     *
     * 横屏 3200×1440 下键盘天然高 945px（66%）—— 直接铺上去就挡住小半个画面，所以按此上限
     * 反推缩放比：`0.60 × 1440 / 945 ≈ 0.914`，再与宽度比例取小 ⇒ 命中 0.70，高度落在 46%。
     */
    const val MAX_HEIGHT_FRACTION = 0.60f

    /** 缩放下限：再小按键就点不中（26 键最窄键 = 屏幕宽的 1/10 × 0.45） */
    const val MIN_SCALE = 0.45f

    const val MAX_SCALE = 1.0f

    /** 「回到底部」按钮的键面文字（strings.xml 禁改，文案一律代码下发） */
    const val TEXT_DOCK = "回到底部"

    /** 「回到底部」按钮的可听名 */
    const val TEXT_DOCK_DESC = "退出悬浮，键盘回到底部全宽"

    /** 长按把手循环的三档尺寸（百分比），落在几何里持久化 */
    val SCALE_STOPS = intArrayOf(62, 78, 92)

    /**
     * 悬浮几何：左上角坐标 + 缩放百分比。
     *
     * 宽高**不单独存** —— 它们由「内容自然尺寸 × 缩放」唯一决定：键盘高度来自排数与键高，
     * 存一份可能过期的像素高，换密度后就成了错的。存缩放比换密度仍然自洽。
     */
    data class Geometry(val x: Int, val y: Int, val scalePercent: Int) {

        /** 换档 / 脏值归一后的百分比（[SCALE_STOPS] 之外的合法值也放行，只钳区间） */
        fun normalized(): Geometry = copy(scalePercent = clampPercent(scalePercent))
    }

    fun clampPercent(percent: Int): Int =
        (percent.coerceIn((MIN_SCALE * 100).toInt(), (MAX_SCALE * 100).toInt()))

    /**
     * 绑定列表的解析：`,` / 换行 / 空格 / 分号都算分隔符（导出的包可能被手工编辑过）。
     *
     * 包名本身只含小写字母、数字、下划线与点，因此这些字符不会出现在合法的包名里。
     */
    fun parsePackages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',', '\n', '\r', ' ', ';', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** 序列化：排序后逗号连接（稳定输出，避免同一份集合每次写出不同串） */
    fun serializePackages(packages: Set<String>): String =
        packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")

    /**
     * 本次输入框是否进悬浮态。
     *
     * **绑定为空 + 开关开 ⇒ 不悬浮**：开关是「允许悬浮」的总闸，白名单是「哪些 APP 用」的清单；
     * 空清单若等价「全部悬浮」，用户一开开关就会在所有聊天 / 搜索框里丢掉全宽键盘，
     * 而这正是本功能的**例外**用法（游戏 / 视频），不该是默认覆盖面。
     */
    fun shouldFloat(enabled: Boolean, boundPackages: Set<String>, hostPackage: String?): Boolean {
        if (!enabled) return false
        if (hostPackage.isNullOrBlank()) return false
        return hostPackage in boundPackages
    }

    /**
     * 绑定列表里已经不在应用列表中的包名（卸载 / 该版本没有桌面入口 / 备份被手改）。
     *
     * 页面用它们把脏数据清掉：不清的话「已绑定 N 个」会虚高，而那几条既匹配不到宿主、
     * 也没有勾选框可以取消（页面只列现存应用）。
     */
    fun stalePackages(bound: Set<String>, available: Set<String>): Set<String> =
        // 空的应用列表不是「所有应用都没了」，而是**查询没拿到东西**（包管理器抛异常 / 权限、
        // 系统繁忙时 `queryIntentActivities` 返回空）。此时若照算差集，用户的整份绑定会被一次
        // 抖动抹掉 —— 那是不可逆的数据损失，页面也只写一次盘。宁可这轮不清，也不误清。
        if (available.isEmpty()) emptySet() else bound - available

    /** 解析 `x,y,scale`；段数不对 / 非数字 / 明显越界 ⇒ null（由调用方回落到默认几何） */
    fun parseGeometry(raw: String?): Geometry? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(',')
        if (parts.size != 3) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        val scale = parts[2].trim().toIntOrNull() ?: return null
        // 绝对值的极端脏值（备份被手改）直接丢弃：几十万像素的坐标在任何屏幕外都没有意义
        if (x < -MAX_COORD || y < -MAX_COORD || x > MAX_COORD || y > MAX_COORD) return null
        return Geometry(x, y, clampPercent(scale))
    }

    fun serializeGeometry(geometry: Geometry): String =
        "${geometry.x},${geometry.y},${clampPercent(geometry.scalePercent)}"

    /** 坐标绝对值上限（脏备份闸；合法坐标恒远小于它） */
    private const val MAX_COORD = 100_000

    /**
     * 默认缩放：宽度比例与高度上限反推值取小，再夹到 [MIN_SCALE]。[naturalW]/[naturalH] 是
     * 键盘在「不缩放」时的自然尺寸（宽度恒等于屏幕宽，故宽度比例直接就是缩放比）。
     */
    fun defaultScalePercent(
        naturalW: Int,
        naturalH: Int,
        screenW: Int,
        screenH: Int,
        portrait: Boolean,
    ): Int {
        if (naturalW <= 0 || naturalH <= 0 || screenW <= 0 || screenH <= 0) return 100
        val byWidth = (if (portrait) PORTRAIT_WIDTH_FRACTION else LANDSCAPE_WIDTH_FRACTION) *
            screenW / naturalW
        val byHeight = MAX_HEIGHT_FRACTION * screenH / naturalH
        val scale = minOf(byWidth, byHeight).coerceIn(MIN_SCALE, MAX_SCALE)
        return (scale * 100).toInt().coerceAtLeast((MIN_SCALE * 100).toInt())
    }

    /**
     * 默认位置：水平居中、贴可用区底部（与「底部键盘」的观感连续，用户第一眼认得出来）。
     */
    fun defaultGeometry(scalePercent: Int, naturalW: Int, naturalH: Int, screenW: Int, areaH: Int): Geometry {
        val w = (naturalW * scalePercent / 100f).toInt()
        val h = (naturalH * scalePercent / 100f).toInt()
        val x = ((screenW - w) / 2).coerceAtLeast(0)
        val y = (areaH - h).coerceAtLeast(0)
        return Geometry(x, y, scalePercent)
    }

    /**
     * 夹取：窗口必须完整落在可用区内。
     *
     * [areaH] 是**可用高度**（屏幕高 − 状态栏；IME 窗口的 x/y 是相对这块区域的，实测本机
     * 状态栏 84px 被排除在外）。旋转 / 改分辨率 / 改字体后旧坐标一律经此重算。
     */
    fun clamp(geometry: Geometry, screenW: Int, areaH: Int, winW: Int, winH: Int): Geometry {
        val maxX = (screenW - winW).coerceAtLeast(0)
        val maxY = (areaH - winH).coerceAtLeast(0)
        return geometry.copy(
            x = geometry.x.coerceIn(0, maxX),
            y = geometry.y.coerceIn(0, maxY),
        ).normalized()
    }

    /** 拖动位移后的几何（先加位移再夹取） */
    fun dragTo(geometry: Geometry, dx: Int, dy: Int, screenW: Int, areaH: Int, winW: Int, winH: Int): Geometry =
        clamp(geometry.copy(x = geometry.x + dx, y = geometry.y + dy), screenW, areaH, winW, winH)

    /**
     * 长按把手的换档：取「比当前档大的第一档」，都不比它大就回到最小档。
     *
     * 不用 `indexOf + 1`：默认缩放是按宽度比例与高度上限算出来的（横屏 70 / 竖屏 92），
     * 通常**不在**档表里，按下标取会直接跳到 62 而不是相邻档。
     */
    fun nextScaleStop(currentPercent: Int): Int =
        SCALE_STOPS.firstOrNull { it > currentPercent } ?: SCALE_STOPS.first()
}
