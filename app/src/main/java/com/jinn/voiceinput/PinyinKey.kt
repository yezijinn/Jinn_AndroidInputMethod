package com.jinn.voiceinput

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
 * 只负责画一个圆角矩形 + 字母（和符号层小字），点击/触摸判定交给
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

    private val corner = KEY_CORNER_DP * resources.displayMetrics.density
    private val rect = RectF()

    /** 主显示：字母 */
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** 符号层小字（字母键右上角），空则不画 */
    var subLabel: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private var pressed = false

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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(KEY_MARGIN_DP * resources.displayMetrics.density, 0f, w - KEY_MARGIN_DP * resources.displayMetrics.density, h)

        keyPaint.color = if (pressed) colorKeyPressed else colorKey
        canvas.drawRoundRect(rect, corner, corner, keyPaint)

        textPaint.color = colorText
        textPaint.textSize = height * TEXT_RATIO
        textPaint.textAlign = Paint.Align.CENTER
        val fm = textPaint.fontMetrics
        val baseline = (h - fm.ascent - fm.descent) / 2f
        canvas.drawText(label, w / 2f, baseline, textPaint)

        if (subLabel.isNotEmpty()) {
            subPaint.color = colorText
            subPaint.alpha = 130
            subPaint.textSize = height * SUB_RATIO
            subPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                subLabel,
                w - KEY_MARGIN_DP * resources.displayMetrics.density,
                h * 0.42f,
                subPaint,
            )
        }
    }

    private companion object {
        const val KEY_CORNER_DP = 6f
        const val KEY_MARGIN_DP = 1.5f
        const val TEXT_RATIO = 0.42f
        const val SUB_RATIO = 0.24f
    }
}
