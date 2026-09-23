package com.jinn.inputmethod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

    /**
     * 拼音键盘的单个字母键。
     *
     * 布局（对齐主流双拼键盘）：
     *  - 大写字母置顶，贴上边摆放，占上方约 50% 高度
     *  - 下方 50% 区域显示自然码双拼的韵母提示（如 Q 键下显示 iu、W 键下显示 ia ua）
     *
     * 只负责画一个圆角矩形 + 字母 + 韵母提示，点击/触摸判定交给
     * [PinyinKeyboardView] 统一分发（本类把 press 状态画出来）。
     * 视觉风格对齐 [MicButton]：深色圆角键 + 按压缩放，不引入额外依赖。
     * 圆角半径与四边内缩由设置页参数决定（见 [KeyAppearance] 与
     * [setKeyAppearance]），确保与本行的大写键/删除键完全一致。
     */
    class PinyinKey @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {

        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val colorKey = context.getColor(R.color.kb_key)
        private val colorKeyPressed = context.getColor(R.color.kb_key_pressed)
        private val colorText = context.getColor(R.color.kb_key_text)
        private val colorCorner = context.getColor(R.color.kb_key_hint_red)

        /**
         * 键面填充色（普通 / 按压两态）。
         *
         * 半透明键盘只淡「面」不淡文字：由 [setFaceAlpha] 把 alpha 合进这两个颜色，
         * 绘制路径只取用它们；不透明度 1f 时与 [colorKey] / [colorKeyPressed] 逐位相等。
         */
        private var faceColor = colorKey
        private var faceColorPressed = colorKeyPressed

        /** 当前键面不透明度（1f = 不透明），见 [setFaceAlpha] */
        private var faceAlpha = 1f

        /**
         * 皮肤视觉参数（null = 默认皮肤）。
         *
         * 非空时键面走皮肤绘制路径（渐变 / 描边 / 底边厚度），颜色与 alpha 由
         * [KeyboardSkins.visualFor] 一次算好，本类不再读 Prefs、也不自行合成 alpha；
         * 为空时保持历史路径（[faceColor] + [faceColorPressed]，与半透明键盘逐像素一致）。
         */
        private var visual: KeyVisual? = null

        /**
         * 皮肤渐变 shader 缓存：按「首色 / 次色 / 角度 / 键面尺寸」四个数值字段判据复用。
         *
         * 原来用拼接字符串做 key（每次绘制都分配一个临时字符串），与绘制路径「零分配」的约定相悖。
         */
        private var skinShader: LinearGradient? = null
        private var shaderFace = 0
        private var shaderFace2 = 0
        private var shaderAngle = -1f
        private var shaderW = -1
        private var shaderH = -1

        /** 键帽面矩形：复用一个实例（原实现每次绘制都 new RectF，见 [drawKeyFace]） */
        private val faceRect = RectF()

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        /**
         * 套用皮肤（null = 回到默认令牌路径）。
         *
         * 由 [PinyinKeyboardView] 在每次弹键盘、改皮肤、拖动透明度滑杆时统一下发；
         * 参数未变时提前返回（[KeyVisual] 是数据类，结构相等即不重绘）。
         */
        fun applySkin(value: KeyVisual?) {
            if (visual == value) return
            visual = value
            skinShader = null
            invalidate()
        }

        /**
         * 设置键面不透明度（1f = 不透明）：只降填充色，文字与提示色不动。
         * 值没变时提前返回，避免每次弹键盘都触发一次 invalidate。
         */
        fun setFaceAlpha(alpha: Float) {
            val a = alpha.coerceIn(0f, 1f)
            if (a == faceAlpha) return
            faceAlpha = a
            faceColor = KeyTransparency.withAlpha(colorKey, a)
            faceColorPressed = KeyTransparency.withAlpha(colorKeyPressed, a)
            invalidate()
        }

        /**
         * 键面外观：圆角半径 + 四边内缩（像素）。
         *
         * 数值来自设置页的 [KeyAppearance] 参数（圆角 0~24dp / 间隙 0~8dp），
         * 由 [PinyinKeyboardView] 在初始化与每次弹键盘时统一套用（见
         * `applyKeyAppearance`）。这里刻意不持有默认常量：26 个字母键必须与
         * 同一行的大写键、删除键用同一组数值，任何一处自己写死就会错位。
         *
         * 初值取 [KeyAppearance] 的默认值，保证未被套用时也能正常绘制。
         */
        private var cornerPx = KeyAppearance.DEFAULT_CORNER_DP * resources.displayMetrics.density
        private var insetPx = KeyAppearance.DEFAULT_GAP_DP * resources.displayMetrics.density / 2f

        private val rect = RectF()

        /**
         * 套用键面外观（像素）。数值未变化时直接返回，避免每次弹键盘都无谓重绘。
         *
         * @param cornerPx 圆角半径（像素），0 为直角
         * @param insetPx  四边内缩（像素），等于间隙的一半，相邻两键各缩一半，
         *                 合起来正好是用户设置的间隙宽度
         */
        fun setKeyAppearance(cornerPx: Float, insetPx: Float) {
            if (this.cornerPx == cornerPx && this.insetPx == insetPx) return
            this.cornerPx = cornerPx
            this.insetPx = insetPx
            invalidate()
        }

        /** 主显示：大写字母 */
        var label: String = ""

        /**
         * 符号层样式：一律水平 + 垂直居中。
         *
         * 符号层不再沿用字母层「小字顶置 + 下方提示」的排版，符号是独立内容，
         * 顶置会显得偏上、且短符号（如 "if"）与长关键字看起来不一致。
         * 长文本仍按可用宽度收缩字号（见 onDraw）。
         */
        var centeredStyle: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        /**
         * 双拼韵母提示（键下方，普通色）。多行用 `\n` 分隔，每行垂直均分下半区
         * （如 Y 键显示 uai/ing 两行）。
         */
        var subLabel: String = ""
            set(value) {
                field = value
                cachedNormalLines = splitHintLines(value)
                invalidate()
            }

        /**
         * 双拼红色提示（键下方，追加在 [subLabel] 之后）。用于 u/i/v 键的 sh/ch/zh。
         * 多行用 `\n` 分隔。
         */
        var subLabelRed: String = ""
            set(value) {
                field = value
                cachedRedLines = splitHintLines(value)
                invalidate()
            }

        /**
         * 提示文本的拆分缓存。
         *
         * 拆分（`split` + `filter` + `trim`）原先写在 [onDraw] 里，每次 invalidate 都要为每个
         * 按键重新分配两个 List 与若干临时 String；按键只在设置提示时变化，所以把结果缓存在
         * setter 里，绘制路径只读现成列表。行为与原地拆分完全一致（空行丢弃、去首尾空白）。
         */
        private var cachedNormalLines: List<String> = emptyList()
        private var cachedRedLines: List<String> = emptyList()

        /** 提示文本 → 非空行列表（已去首尾空白）；空串返回空列表，绘制端按「无提示」处理 */
        private fun splitHintLines(text: String): List<String> =
            if (text.isEmpty()) {
                emptyList()
            } else {
                text.split('\n').filter { it.isNotBlank() }.map { it.trim() }
            }

        /**
         * 全拼模式大字样式：true 时字母铺满按键居中（约 60% 键高），
         * 不绘制双拼提示；false 保持双拼布局（字母顶置 42% + 下方韵母提示）。
         */
        var fullPinyinStyle: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        private var pressed = false

        /**
         * 由外部触摸监听驱动按压视觉。
         *
         * 字母键的触摸由 [PinyinKeyboardView.handleKeyTouch] 通过 OnTouchListener 处理
         * （要兼听符号层横滑翻页），OnTouchListener 返回 true 后本 View 的
         * [onTouchEvent] 不再执行，[pressed] 也就永远不会变化，按键看起来没有反应。
         * 因此外层必须在 DOWN/UP/CANCEL 时显式调用本方法刷新按压态。
         *
         * 命名避开 [View.setPressed]，不复用系统按压态（系统态会被父容器重置）。
         */
        fun setPressedVisual(value: Boolean) {
            if (pressed == value) return
            pressed = value
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 触摸按下/抬起用于视觉反馈，抬手时通过 performClick 触发 OnClickListener
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    pressed = false
                    invalidate()
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    invalidate()
                }
            }
            return true // 消费事件，保证 performClick 正常分发
        }

        // 无障碍 / 键盘可达性：声明可点击，允许辅助服务触发
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        /**
         * 按可用宽度收缩字号：文本超宽时等比缩小，下限为 [minSize]。
         *
         * 用于编程关键字这类长文本，固定字号会让 "return""static" 左右溢出、看不全。
         *
         * [minSize] 由调用方按键高比例给出（见 [MIN_LONG_TEXT_RATIO]），不用固定像素值：
         * 固定值在低密度屏（mdpi 下 9px = 9dp）会大于按宽度算出的适配字号，反而把文字
         * 顶出按键左右边界；按比例取值在任何密度下都与按键尺寸同步。
         */
        private fun fitTextSize(text: String, desiredSize: Float, maxWidth: Float, minSize: Float): Float {
            if (text.isEmpty() || maxWidth <= 0f) return desiredSize
            textPaint.textSize = desiredSize
            val measured = textPaint.measureText(text)
            if (measured <= maxWidth) return desiredSize
            return (desiredSize * maxWidth / measured).coerceAtLeast(minSize)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            // 四边等量内缩：左右间隙与上下间隙由同一个参数决定（见 KeyAppearance）。
            // 上限保护：内缩超过键尺寸一半会把矩形压没，此时退化到「不留缩进」。
            val inset = insetPx.coerceIn(0f, (min(w, h) / 2f - 1f).coerceAtLeast(0f))
            rect.set(inset, inset, w - inset, h - inset)

            // 皮肤色：默认皮肤（visual == null）取历史令牌；皮肤路径由 [KeyVisual] 给出
            val v = visual
            val glyphColor = v?.glyph ?: colorText
            val hintColor = v?.hint ?: colorText
            val hintRedColor = v?.hintRed ?: colorCorner

            drawKeyFace(canvas, rect)

            // ── 全拼模式：字母铺满按键居中（约 70% 键高），无双拼提示 ──
            if (fullPinyinStyle) {
                textPaint.color = glyphColor
                textPaint.textSize = h * FULL_TEXT_RATIO
                textPaint.textAlign = Paint.Align.CENTER
                val fm = textPaint.fontMetrics
                val baseline = (h - fm.ascent - fm.descent) / 2f
                canvas.drawText(label, w / 2f, baseline, textPaint)
                return
            }

            // ── 符号层：一律水平 + 垂直居中（长文本按宽度收缩字号）──
            if (centeredStyle) {
                textPaint.color = glyphColor
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = fitTextSize(label, h * TEXT_RATIO, w - inset * 2f, h * MIN_LONG_TEXT_RATIO)
                val centerFm = textPaint.fontMetrics
                canvas.drawText(label, w / 2f, (h - centerFm.ascent - centerFm.descent) / 2f, textPaint)
                return
            }

            // ── 双拼模式：大写字母置顶 + 下方韵母提示 ──
            textPaint.color = glyphColor
            textPaint.textAlign = Paint.Align.CENTER
            // 长文本（多字符符号等）按可用宽度收缩字号并垂直居中。
            // 沿用小字顶置样式会左右溢出、内容显示不完整。
            if (label.length > LONG_TEXT_THRESHOLD) {
                textPaint.textSize = fitTextSize(label, h * LONG_TEXT_RATIO, w - inset * 2f, h * MIN_LONG_TEXT_RATIO)
                val longFm = textPaint.fontMetrics
                canvas.drawText(label, w / 2f, (h - longFm.ascent - longFm.descent) / 2f, textPaint)
                return
            }
            // 大写字母：置顶贴上边，占上方约 50%
            textPaint.textSize = height * TEXT_RATIO
            val fm = textPaint.fontMetrics
            val letterBaseline = (h * LETTER_TOP_RATIO - fm.ascent - fm.descent) / 2f + h * LETTER_TOP_RATIO * 0.1f
            canvas.drawText(label, w / 2f, letterBaseline, textPaint)

            // 双拼提示：下半区，底部对齐（最后一行/单行贴按钮底边）。
            // 普通行在前、红色行在后，多行紧凑排布。
            // 行文本已在 setter 里拆分/去空白并缓存，绘制路径零分配（见 cachedNormalLines）
            val normalLines = cachedNormalLines
            val redLines = cachedRedLines
            val total = normalLines.size + redLines.size
            if (total > 0) {
                subPaint.textSize = height * SUB_RATIO
                subPaint.textAlign = Paint.Align.CENTER
                val subFm = subPaint.fontMetrics
                // 紧凑行距：略小于完整字高
                val lineHeight = (subFm.bottom - subFm.top) * LINE_COMPACT_RATIO
                // 最后一行基线：让文字底部贴 SUB_BOTTOM_RATIO 位置（近按钮底边）
                val lastBaseline = h * SUB_BOTTOM_RATIO - subFm.descent
                var i = 0
                for (line in normalLines) {
                    subPaint.color = hintColor
                    // 默认皮肤走历史路径（字色 + paint alpha 160）；皮肤色自带 alpha，用 255 避免二次淡化
                    subPaint.alpha = if (v == null) 160 else 255
                    canvas.drawText(
                        line, w / 2f,
                        lastBaseline - (total - 1 - i) * lineHeight, subPaint,
                    )
                    i++
                }
                for (line in redLines) {
                    subPaint.color = hintRedColor
                    subPaint.alpha = 255
                    canvas.drawText(
                        line, w / 2f,
                        lastBaseline - (total - 1 - i) * lineHeight, subPaint,
                    )
                    i++
                }
            }
        }

        /**
         * 绘制键面。
         *
         * 默认路径（[visual] 为空）：单色圆角矩形，与半透明键盘的历史观感逐像素一致；
         * 皮肤路径：可选的底边「键帽厚度」+ 渐变或纯色键帽面 + 可选描边，
         * 圆角与几何仍与同类键（大写/删除键）对齐。
         */
        private fun drawKeyFace(canvas: Canvas, r: RectF) {
            val v = visual
            if (v == null) {
                keyPaint.shader = null
                keyPaint.color = if (pressed) faceColorPressed else faceColor
                canvas.drawRoundRect(r, cornerPx, cornerPx, keyPaint)
                return
            }
            // 底边厚度：先整块铺厚度色，再把键帽面压在下方留出一条边（厚度上限 = 键面高的 1/3）
            val thickness = v.thicknessPx.coerceIn(0f, r.height() / 3f)
            if (thickness > 0f) {
                keyPaint.shader = null
                keyPaint.color = v.thicknessColor
                canvas.drawRoundRect(r, cornerPx, cornerPx, keyPaint)
            }
            faceRect.set(r.left, r.top, r.right, r.bottom - thickness)
            if (pressed) {
                keyPaint.shader = null
                keyPaint.color = v.pressed
            } else if (v.face != v.face2) {
                keyPaint.shader = gradientShader(v, faceRect)
                keyPaint.color = v.face
            } else {
                keyPaint.shader = null
                keyPaint.color = v.face
            }
            canvas.drawRoundRect(faceRect, cornerPx, cornerPx, keyPaint)
            keyPaint.shader = null
            if (v.strokeWidthPx > 0f) {
                strokePaint.strokeWidth = v.strokeWidthPx
                strokePaint.color = v.strokeColor
                canvas.drawRoundRect(faceRect, cornerPx, cornerPx, strokePaint)
            }
        }

        /** 皮肤渐变（缓存：首色 / 次色 / 角度 / 键面尺寸不变时复用同一实例，绘制路径零分配） */
        private fun gradientShader(v: KeyVisual, r: RectF): LinearGradient {
            val w = r.width().toInt()
            val h = r.height().toInt()
            skinShader?.let {
                if (v.face == shaderFace && v.face2 == shaderFace2 &&
                    v.gradientAngle == shaderAngle && w == shaderW && h == shaderH
                ) {
                    return it
                }
            }
            val rad = Math.toRadians(v.gradientAngle.toDouble())
            val dx = (Math.cos(rad) * r.width() / 2.0).toFloat()
            val dy = (Math.sin(rad) * r.height() / 2.0).toFloat()
            val shader = LinearGradient(
                r.centerX() - dx, r.centerY() - dy,
                r.centerX() + dx, r.centerY() + dy,
                v.face, v.face2, Shader.TileMode.CLAMP,
            )
            skinShader = shader
            shaderFace = v.face
            shaderFace2 = v.face2
            shaderAngle = v.gradientAngle
            shaderW = w
            shaderH = h
            return shader
        }

        private companion object {

            /** 表示大写字母字号占按键高度的比例 （0.5f 即字母高度为键高的 50%）。值越大字母越大 */
            const val TEXT_RATIO = 0.5f

            /** 超过该字符数按长文本处理：收缩字号并垂直居中（编程关键字、多字符符号） */
            const val LONG_TEXT_THRESHOLD = 2

            /** 长文本基准字号比例（比 TEXT_RATIO 小一号，因为长文本占的空间更多） */
            const val LONG_TEXT_RATIO = 0.30f

            /** 长文本收缩下限，避免极长字符串缩到无法辨认 */
            /**
             * 长文本收缩下限（占键高比例），避免极长字符串缩到无法辨认。
             *
             * 用比例而非固定像素：旧值 9f（像素）在 2.75x 屏上约 3.3dp 尚可，但在 mdpi 上
             * 等于 9dp，比按宽度算出的适配字号还大，会把 synchronized 这类长标签顶出
             * 按键左右边界。0.06 × 54dp 键高 ≈ 3.2dp，与旧观感等效且随密度自洽。
             */
            const val MIN_LONG_TEXT_RATIO = 0.06f
            const val SUB_RATIO = 0.2f

            /** 全拼模式：字母高度占键高比例（铺满感，约 70%） */
            const val FULL_TEXT_RATIO = 0.7f

            /** 大写字母顶部起始比例 */
            const val LETTER_TOP_RATIO = 0.42f

            /** 韵母文本底部对齐位置（近按钮底边，0~1 相对键高） */
            const val SUB_BOTTOM_RATIO = 0.94f

            /** 多行紧凑行距系数（<1 收窄行距，避免超出按键） */
            const val LINE_COMPACT_RATIO = 0.8f
        }
    }
