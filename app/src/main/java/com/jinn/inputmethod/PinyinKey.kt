package com.jinn.inputmethod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

        private val corner = KEY_CORNER_DP * resources.displayMetrics.density
        private val rect = RectF()

        /** 主显示：大写字母 */
        var label: String = ""

        /**
         * 符号层样式：一律水平 + 垂直居中。
         *
         * 符号层不再沿用字母层「小字顶置 + 下方提示」的排版——符号是独立内容，
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
                invalidate()
            }

        /**
         * 双拼红色提示（键下方，追加在 [subLabel] 之后）。用于 u/i/v 键的 sh/ch/zh。
         * 多行用 `\n` 分隔。
         */
        var subLabelRed: String = ""
            set(value) {
                field = value
                invalidate()
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
         * [onTouchEvent] 不再执行，[pressed] 也就永远不会变化 —— 按键看起来没有反应。
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
         * 按可用宽度收缩字号：文本超宽时等比缩小（下限 [MIN_LONG_TEXT_SIZE]）。
         *
         * 用于编程关键字这类长文本——固定字号会让 "return""static" 左右溢出、看不全。
         */
        private fun fitTextSize(text: String, desiredSize: Float, maxWidth: Float): Float {
            if (text.isEmpty() || maxWidth <= 0f) return desiredSize
            textPaint.textSize = desiredSize
            val measured = textPaint.measureText(text)
            if (measured <= maxWidth) return desiredSize
            return (desiredSize * maxWidth / measured).coerceAtLeast(MIN_LONG_TEXT_SIZE)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val margin = KEY_MARGIN_DP * resources.displayMetrics.density
            rect.set(margin, 0f, w - margin, h)

            keyPaint.color = if (pressed) colorKeyPressed else colorKey
            canvas.drawRoundRect(rect, corner, corner, keyPaint)

            // ── 全拼模式：字母铺满按键居中（约 60% 键高），无双拼提示 ──
            if (fullPinyinStyle) {
                textPaint.color = colorText
                textPaint.textSize = h * FULL_TEXT_RATIO
                textPaint.textAlign = Paint.Align.CENTER
                val fm = textPaint.fontMetrics
                val baseline = (h - fm.ascent - fm.descent) / 2f
                canvas.drawText(label, w / 2f, baseline, textPaint)
                return
            }

            // ── 符号层：一律水平 + 垂直居中（长文本按宽度收缩字号）──
            if (centeredStyle) {
                textPaint.color = colorText
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = fitTextSize(label, h * TEXT_RATIO, w - margin * 2f)
                val centerFm = textPaint.fontMetrics
                canvas.drawText(label, w / 2f, (h - centerFm.ascent - centerFm.descent) / 2f, textPaint)
                return
            }

            // ── 双拼模式：大写字母置顶 + 下方韵母提示 ──
            textPaint.color = colorText
            textPaint.textAlign = Paint.Align.CENTER
            // 长文本（多字符符号等）按可用宽度收缩字号并垂直居中。
            // 沿用小字顶置样式会左右溢出、内容显示不完整。
            if (label.length > LONG_TEXT_THRESHOLD) {
                textPaint.textSize = fitTextSize(label, h * LONG_TEXT_RATIO, w - margin * 2f)
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
            val normalLines = subLabel.split('\n').filter { it.isNotBlank() }
            val redLines = subLabelRed.split('\n').filter { it.isNotBlank() }
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
                    subPaint.color = colorText
                    subPaint.alpha = 160
                    canvas.drawText(
                        line.trim(), w / 2f,
                        lastBaseline - (total - 1 - i) * lineHeight, subPaint,
                    )
                    i++
                }
                for (line in redLines) {
                    subPaint.color = colorCorner
                    subPaint.alpha = 255
                    canvas.drawText(
                        line.trim(), w / 2f,
                        lastBaseline - (total - 1 - i) * lineHeight, subPaint,
                    )
                    i++
                }
            }
        }

        private companion object {
            const val KEY_CORNER_DP = 6f
            const val KEY_MARGIN_DP = 1.5f
            const val TEXT_RATIO = 0.4f

            /** 超过该字符数按长文本处理：收缩字号并垂直居中（编程关键字、多字符符号） */
            const val LONG_TEXT_THRESHOLD = 2

            /** 长文本基准字号比例（比常规 TEXT_RATIO 小一号，长文本本身更长） */
            const val LONG_TEXT_RATIO = 0.30f

            /** 长文本收缩下限，避免极长字符串缩到无法辨认 */
            const val MIN_LONG_TEXT_SIZE = 9f
            const val SUB_RATIO = 0.2f

            /** 全拼模式：字母高度占键高比例（铺满感，约 60%） */
            const val FULL_TEXT_RATIO = 0.6f

            /** 大写字母顶部起始比例 */
            const val LETTER_TOP_RATIO = 0.42f

            /** 韵母文本底部对齐位置（近按钮底边，0~1 相对键高） */
            const val SUB_BOTTOM_RATIO = 0.94f

            /** 多行紧凑行距系数（<1 收窄行距，避免超出按键） */
            const val LINE_COMPACT_RATIO = 0.8f
        }
    }
