package com.jinn.voiceinput

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 麦克风按钮：一个实心圆，录音时外面套一层随音量呼吸的光圈。
 *
 * 只负责画。手势判定（短按切换 / 长按说话 / 上滑取消）放在
 * [JinnIme] 里，避免视图和输入逻辑互相纠缠。
 */
class MicButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val micIcon = context.getDrawable(R.drawable.ic_mic)?.mutate()

    private val colorIdle = context.getColor(R.color.mic_idle)
    private val colorIdleCenter = context.getColor(R.color.mic_idle_center)
    private val colorActive = context.getColor(R.color.mic_active)
    private val colorActiveCenter = context.getColor(R.color.mic_active_center)
    private val colorRing = context.getColor(R.color.mic_ring)
    private val colorGlyph = context.getColor(R.color.mic_glyph)

    /** 光圈最大扩散半径 */
    private val ringSpan = RING_SPAN_DP * resources.displayMetrics.density

    private var levelSmoothed = 0f

    var recording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) levelSmoothed = 0f
            invalidate()
        }

    /** 是否处于"松手即取消"状态，用于给出视觉警示 */
    var cancelArmed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 喂入实时音量（0f..1f）。内部做一阶平滑，否则光圈会抖。
     */
    fun updateLevel(raw: Float) {
        levelSmoothed = levelSmoothed * LEVEL_SMOOTH + raw.coerceIn(0f, 1f) * (1f - LEVEL_SMOOTH)
        if (recording) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f - ringSpan
        if (radius <= 0f) return

        // 常驻微光：让按钮在空闲时也有层次感
        ringPaint.color = colorRing
        ringPaint.alpha = RING_AMBIENT_ALPHA
        canvas.drawCircle(centerX, centerY, radius + ringSpan * 0.45f, ringPaint)

        if (recording) {
            ringPaint.alpha = (RING_ALPHA_BASE + RING_ALPHA_RANGE * levelSmoothed).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                centerX,
                centerY,
                radius + ringSpan * (0.3f + 0.7f * levelSmoothed),
                ringPaint,
            )
        }

        val centerColor: Int
        val baseColor: Int
        if (cancelArmed) {
            centerColor = colorIdleCenter
            baseColor = colorIdle
        } else if (recording) {
            centerColor = colorActiveCenter
            baseColor = colorActive
        } else {
            centerColor = colorIdleCenter
            baseColor = colorIdle
        }
        // 径向渐变：中心略亮，模拟受光面，比纯色更立体
        fillPaint.shader = RadialGradient(
            centerX, centerY - radius * 0.25f, radius * 1.15f,
            centerColor, baseColor, Shader.TileMode.CLAMP,
        )
        fillPaint.alpha = if (cancelArmed) CANCEL_ALPHA else 255
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        fillPaint.shader = null

        micIcon?.let { icon ->
            val size = (radius * ICON_RATIO).toInt()
            val half = size / 2
            icon.setBounds(
                centerX.toInt() - half,
                centerY.toInt() - half,
                centerX.toInt() + half,
                centerY.toInt() + half,
            )
            icon.setTint(colorGlyph)
            icon.alpha = if (cancelArmed) CANCEL_ALPHA else 255
            icon.draw(canvas)
        }
    }

    private companion object {
        const val RING_SPAN_DP = 18f
        const val LEVEL_SMOOTH = 0.6f
        const val RING_AMBIENT_ALPHA = 28
        const val RING_ALPHA_BASE = 70f
        const val RING_ALPHA_RANGE = 130f
        const val ICON_RATIO = 0.95f
        const val CANCEL_ALPHA = 110
    }
}
