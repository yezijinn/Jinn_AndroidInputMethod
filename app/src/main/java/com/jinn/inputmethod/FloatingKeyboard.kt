package com.jinn.inputmethod

import kotlin.math.roundToInt

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

    /** 宽度百分比下限（= [MIN_SCALE]，四角拉伸与换档共用） */
    const val MIN_PERCENT = (MIN_SCALE * 100).toInt()

    /** 宽度百分比上限：内容宽 = 屏幕宽，再宽也放不进屏幕 */
    const val MAX_WIDTH_PERCENT = (MAX_SCALE * 100).toInt()

    /**
     * 高度百分比上限（内容高 ÷ 自然高）。
     *
     * 1.9 倍是「还能看出是一块键盘」的上界：再往上键面细长得离谱，且离屏高上限只剩几十像素。
     * 下限不写在这里 —— 内容里有一半高度是候选栏 / 底排这些不随拉伸变的固定块，
     * 百分比下限必须与它们联动（见 [stretchFactorFor] 的倍率夹取）。
     */
    const val MAX_HEIGHT_PERCENT = 190

    /** 可伸缩块的倍率下限（0.6：底排是固定 dp 字号的行，再压两行标签就被键框裁掉） */
    const val MIN_STRETCH = 0.6f

    /** 可伸缩块的倍率上限（与 [MAX_HEIGHT_PERCENT] 联动） */
    const val MAX_STRETCH = 1.9f

    /** 「回到底部」按钮的键面文字（strings.xml 禁改，文案一律代码下发） */
    const val TEXT_DOCK = "回到底部"

    /** 「回到底部」按钮的可听名 */
    const val TEXT_DOCK_DESC = "退出悬浮，键盘回到底部全宽"

    /** 长按把手循环的三档尺寸（百分比），落在几何里持久化 */
    val SCALE_STOPS = intArrayOf(62, 78, 92)

    /** 四角（顺序即触摸的方向符号：左下 = 左 + 下） */
    enum class Corner(private val left: Boolean, private val top: Boolean) {
        TOP_START(left = true, top = true),
        TOP_END(left = false, top = true),
        BOTTOM_START(left = true, top = false),
        BOTTOM_END(left = false, top = false);

        /** 拖这个角会不会改左边缘（否则改右边缘） */
        val movesLeftEdge: Boolean get() = left

        /** 拖这个角会不会改上边缘（否则改下边缘） */
        val movesTopEdge: Boolean get() = top
    }

    /**
     * 悬浮几何：左上角坐标 + 宽 / 高各自的百分比。
     *
     * 百分比是**相对内容自然尺寸**（宽恒等于屏宽、高由排数与键高派生）：存像素会在换密度后失真，
     * 存比例换密度仍然自洽 —— 这也是四角拉伸能既「宽高独立」又不写死像素的原因。
     *
     * 三段格式 `x,y,scale` 是旧档位写法，等价于「宽高同比例」；四角拉过之后才写四段。
     * [heightPercent] 省略时跟随 [widthPercent]，所以 `Geometry(x, y, 92)` 仍是等比 92%。
     */
    data class Geometry(
        val x: Int,
        val y: Int,
        val widthPercent: Int,
        val heightPercent: Int = widthPercent,
    ) {

        /** 等比时的百分比（四角拉过就是「较小那边」，长按换档按它取下一档） */
        val uniformPercent: Int get() = minOf(widthPercent, heightPercent)

        /** 换档 / 脏值归一后的百分比 */
        fun normalized(): Geometry = copy(
            widthPercent = clampWidthPercent(widthPercent),
            heightPercent = clampHeightPercent(heightPercent),
        )
    }

    /** 宽度百分比（等价旧 [clampPercent]，45~100） */
    fun clampWidthPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_WIDTH_PERCENT)

    /** 高度百分比（45~[MAX_HEIGHT_PERCENT]；实际可拉伸范围由可伸缩块倍率兜住） */
    fun clampHeightPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_HEIGHT_PERCENT)

    /** 旧名（等比档位 / 三段格式共用），语义 = 宽度百分比 */
    fun clampPercent(percent: Int): Int = clampWidthPercent(percent)

    /** 窗宽（px）：自然宽（= 屏宽）× 宽度百分比 */
    fun windowWidthPx(naturalW: Int, widthPercent: Int): Int =
        (naturalW * clampWidthPercent(widthPercent) / 100f).toInt().coerceAtLeast(1)

    /** 内容高（px）：自然高 × 高度百分比（不含把手） */
    fun contentHeightPx(naturalH: Int, heightPercent: Int): Int =
        (naturalH * clampHeightPercent(heightPercent) / 100f).toInt().coerceAtLeast(1)

    /** px → 宽度百分比（夹到定义域）。取整用四舍五入：截断会让 92% 的窗口读回 91% */
    fun widthPercentOf(px: Int, naturalW: Int): Int =
        if (naturalW <= 0) MAX_WIDTH_PERCENT else clampWidthPercent((px * 100f / naturalW).roundToInt())

    /** px → 高度百分比（夹到定义域；同上，四舍五入） */
    fun heightPercentOf(px: Int, naturalH: Int): Int =
        if (naturalH <= 0) MAX_WIDTH_PERCENT else clampHeightPercent((px * 100f / naturalH).roundToInt())

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

    /**
     * 解析几何串，两种段数都认：
     *  - **3 段** `x,y,scale`（旧版写的，用户已存过）⇒ 宽高同比；
     *  - **4 段** `x,y,widthPercent,heightPercent`（四角拉过之后写的）。
     *
     * 其余段数 / 非数字 / 明显越界 ⇒ null（由调用方回落到默认几何）。
     */
    fun parseGeometry(raw: String?): Geometry? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(',')
        if (parts.size != 3 && parts.size != 4) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        val width = parts[2].trim().toIntOrNull() ?: return null
        val height = if (parts.size == 4) parts[3].trim().toIntOrNull() ?: return null else width
        // 绝对值的极端脏值（备份被手改）直接丢弃：几十万像素的坐标在任何屏幕外都没有意义
        if (x < -MAX_COORD || y < -MAX_COORD || x > MAX_COORD || y > MAX_COORD) return null
        return Geometry(x, y, width, height).normalized()
    }

    /**
     * 序列化：等比时写**旧的三段格式**（旧版本读得懂，也是用户存档的原样），
     * 四角拉过（宽高不同）才写四段。
     */
    fun serializeGeometry(geometry: Geometry): String {
        val g = geometry.normalized()
        return if (g.widthPercent == g.heightPercent) {
            "${g.x},${g.y},${g.widthPercent}"
        } else {
            "${g.x},${g.y},${g.widthPercent},${g.heightPercent}"
        }
    }

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
        return (scale * 100).toInt().coerceAtLeast(MIN_PERCENT)
    }

    /**
     * 默认位置：水平居中、贴可用区底部（与「底部键盘」的观感连续，用户第一眼认得出来）。
     */
    fun defaultGeometry(
        widthPercent: Int,
        naturalW: Int,
        naturalH: Int,
        screenW: Int,
        areaH: Int,
    ): Geometry {
        val w = windowWidthPx(naturalW, widthPercent)
        val h = contentHeightPx(naturalH, widthPercent)
        val x = ((screenW - w) / 2).coerceAtLeast(0)
        val y = (areaH - h).coerceAtLeast(0)
        return Geometry(x, y, widthPercent)
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
     * 四角拉伸后的几何（纯算术）。
     *
     * 两条口径：
     *  - **宽高独立**：拖左右边只改 [Geometry.widthPercent]，拖上下边只改 [Geometry.heightPercent]；
     *  - **对角固定**：拖左边 ⇒ 右边缘不动（x 补偿宽差），拖上边 ⇒ 下边缘不动（y 补偿高差）。
     *    不补偿的话拖左上角会变成「整块往右下缩」，手指像在推整块面板而不是拉边。
     *
     * **起点尺寸必须传实际值**（[originWinW] / [originContentH]），不能在这里由百分比反算：
     * 行高逐行取整 + 可伸缩块倍率被夹住，会让「百分比算出来的高」与窗口真值差几像素；
     * 每拖一次上边就补一次这个差，连着拖几次下边缘会一路漂走。
     *
     * 位置是否越界由调用方再用 [clamp] 收一次（这里不夹，是因为夹取要的是真实窗口尺寸）。
     */
    fun resizeTo(
        origin: Geometry,
        corner: Corner,
        dx: Int,
        dy: Int,
        originWinW: Int,
        originContentH: Int,
        naturalW: Int,
        naturalH: Int,
    ): Geometry {
        val widthPx = originWinW + if (corner.movesLeftEdge) -dx else dx
        val contentPx = originContentH + if (corner.movesTopEdge) -dy else dy
        val widthPercent = widthPercentOf(widthPx, naturalW)
        val heightPercent = heightPercentOf(contentPx, naturalH)
        val newWinW = windowWidthPx(naturalW, widthPercent)
        val newContentH = contentHeightPx(naturalH, heightPercent)
        return Geometry(
            x = origin.x + if (corner.movesLeftEdge) originWinW - newWinW else 0,
            y = origin.y + if (corner.movesTopEdge) originContentH - newContentH else 0,
            widthPercent = widthPercent,
            heightPercent = heightPercent,
        )
    }

    /**
     * 把窗位置钉回拖动起点那一刻的**对边**（拖左边的角钉右边缘、拖上边的角钉下边缘）。
     *
     * 为什么不能只用 [resizeTo] 里的「起点 ± 模型尺寸差」：[resizeTo] 的位移补偿按
     * `contentHeightPx(naturalH, 百分比)` 算，那只是**模型值**；真正落地时内容高还要过
     * 可伸缩块的倍率夹取（[MIN_STRETCH]~[MAX_STRETCH]）与逐行取整，两者最多差几十像素。
     * 按模型值补位置的后果是真机实测到的：顶边往下拖过下限之后，面板**不再只变尺寸，
     * 而是整块跟着手指平移**（一次 500px 的拖动里整体下沉 100px，尺寸一像素没变）。
     *
     * 这里改用落地时**实际生效**的 [winW] / [winH] 反推，夹取与取整都不会漏。
     * 只改「这一角真正拖动的那两条边」对应的坐标，另外两条保持 [resizeTo] 的值。
     */
    fun pinToEdges(
        geometry: Geometry,
        corner: Corner,
        originRight: Int,
        originBottom: Int,
        winW: Int,
        winH: Int,
    ): Geometry = geometry.copy(
        x = if (corner.movesLeftEdge) originRight - winW else geometry.x,
        y = if (corner.movesTopEdge) originBottom - winH else geometry.y,
    )

    /**
     * 目标内容高 → 可伸缩块（字母行 + 底排）的倍率。
     *
     * 内容里有一块**不随拉伸变**的固定高（候选栏与上边距）：直接把整块内容乘百分比，
     * 拉伸幅度会被它吃掉一半，手感是「拉一点就不动了」。所以按「固定块 + 倍率 × 可伸缩块」
     * 反解倍率，再夹到 [MIN_STRETCH]~[MAX_STRETCH]。
     */
    fun stretchFactorFor(targetContentH: Int, chromePx: Int, blockPx: Int): Float {
        if (blockPx <= 0) return 1f
        return ((targetContentH - chromePx).toFloat() / blockPx).coerceIn(MIN_STRETCH, MAX_STRETCH)
    }

    /** 倍率 → 实际内容高（与 [stretchFactorFor] 互逆；调用方用它反算有效百分比） */
    fun contentHeightFor(factor: Float, chromePx: Int, blockPx: Int): Int =
        (chromePx + (factor.coerceIn(MIN_STRETCH, MAX_STRETCH) * blockPx)).toInt().coerceAtLeast(1)

    /**
     * 长按把手的换档：取「比当前档大的第一档」，都不比它大就回到最小档。
     *
     * 不用 `indexOf + 1`：默认缩放是按宽度比例与高度上限算出来的（横屏 70 / 竖屏 92），
     * 通常**不在**档表里，按下标取会直接跳到 62 而不是相邻档。
     */
    fun nextScaleStop(currentPercent: Int): Int =
        SCALE_STOPS.firstOrNull { it > currentPercent } ?: SCALE_STOPS.first()
}
