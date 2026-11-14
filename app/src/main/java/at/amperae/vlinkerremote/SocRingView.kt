package at.amperae.vlinkerremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class SocRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private var percent = -1f
    private var gradientDirty = true

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        color = 0xFF1A2A3A.toInt()
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f * density
        strokeCap = Paint.Cap.ROUND
        alpha = 60
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f * scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFFFFFFFF.toInt()
    }

    private val percentSignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f * scaledDensity
        textAlign = Paint.Align.LEFT
        color = 0xCCFFFFFF.toInt()
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * scaledDensity
        textAlign = Paint.Align.CENTER
        color = 0xAA_FFFFFF.toInt()
    }

    private val rect = RectF()

    fun setPercent(p: Float) {
        percent = p.coerceIn(0f, 100f)
        gradientDirty = true
        invalidate()
    }

    fun clear() {
        percent = -1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradientDirty = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (160 * density).toInt()
        val w = resolveSize(size, widthMeasureSpec)
        val h = resolveSize(size, heightMeasureSpec)
        val s = min(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = trackPaint.strokeWidth / 2f
        val radius = min(width, height) / 2f - glowPaint.strokeWidth / 2f

        canvas.drawCircle(cx, cy, radius, trackPaint)

        if (percent >= 0f) {
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            val sweep = (percent / 100f) * 270f

            if (gradientDirty) {
                val gradient = LinearGradient(
                    cx, cy - radius, cx + radius, cy + radius,
                    intArrayOf(0xFF81D4FA.toInt(), 0xFF4FC3F7.toInt(), 0xFF039BE5.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                arcPaint.shader = gradient
                glowPaint.shader = gradient
                gradientDirty = false
            }

            canvas.drawArc(rect, 135f, sweep, false, glowPaint)
            canvas.drawArc(rect, 135f, sweep, false, arcPaint)
        }

        val valueText = if (percent >= 0f) String.format("%.0f", percent) else "– –"
        val valueY = cy + valuePaint.textSize * 0.15f
        canvas.drawText(valueText, cx, valueY, valuePaint)

        if (percent >= 0f) {
            val valueWidth = valuePaint.measureText(valueText)
            canvas.drawText("%", cx + valueWidth / 2f + 2f * density, valueY, percentSignPaint)
        }

        val labelY = valueY + labelPaint.textSize + 8f * density
        canvas.drawText("Batterie Ladestand (SoC)", cx, labelY, labelPaint)
    }
}
