package com.jinn.inputmethod

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
    /**
     * 图标分两枚：空闲态底盘是*跟主题走的*（亮色近白 / 暗色深灰），录音态底盘是红色（两档都深）。
     * 只给一枚令牌就会在一档里与自己的底盘撞车 —— 亮色档白图标压近白底盘只有 1.2~1.4:1
     * （2026-10-02 修复 L-475）。
     */
    private val colorGlyph = context.getColor(R.color.mic_glyph)
    private val colorGlyphActive = context.getColor(R.color.mic_glyph_active)

    /** 光圈最大扩散半径 */
    private val ringSpan = RING_SPAN_DP * resources.displayMetrics.density

    private var levelSmoothed = 0f

    /**
     * 径向渐变缓存（MEM-22）：录音时 [updateLevel] 每个音频帧都 invalidate，而原先每次 [onDraw]
     * 都新建一个 RadialGradient（native 对象）。
     *
     * 缓存键是「几何 + 两个颜色」，几何取实际算出的中心与半径（尺寸变化自然反映）；
     * 电平只影响外圈描边、不参与渐变颜色，所以音量变化不触发重建。取消态与空闲态同色，
     * 故取消手势切换也不会重建（那一路的差异只体现在 [fillPaint] 的 alpha 上）。
     */
    private var cachedShader: android.graphics.Shader? = null
    private var shaderX = Float.NaN
    private var shaderY = Float.NaN
    private var shaderRadius = Float.NaN
    private var shaderCenterColor = 0
    private var shaderBaseColor = 0

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
        // 径向渐变：中心略亮，模拟受光面，比纯色更立体。
        // 参数没变就复用已建的 Shader（MEM-22）：录音期间这里是每帧一次，别按帧造 native 对象。
        val gradX = centerX
        val gradY = centerY - radius * 0.25f
        val gradR = radius * 1.15f
        if (cachedShader == null || gradX != shaderX || gradY != shaderY || gradR != shaderRadius ||
            centerColor != shaderCenterColor || baseColor != shaderBaseColor
        ) {
            cachedShader = RadialGradient(
                gradX, gradY, gradR,
                centerColor, baseColor, Shader.TileMode.CLAMP,
            )
            shaderX = gradX
            shaderY = gradY
            shaderRadius = gradR
            shaderCenterColor = centerColor
            shaderBaseColor = baseColor
        }
        fillPaint.shader = cachedShader
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
            // 录音态底盘转到红档（cancelArmed 会让底盘退回空闲色）⇒ 图标跟着换那一枚
            icon.setTint(if (recording && !cancelArmed) colorGlyphActive else colorGlyph)
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
