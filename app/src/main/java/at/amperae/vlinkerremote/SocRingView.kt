package at.amperae.vlinkerremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        color = 0xFF2A2A40.toInt()
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF4CAF50.toInt()
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f * scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFFE0E0E0.toInt()
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * scaledDensity
        textAlign = Paint.Align.CENTER
        color = 0xFF9E9E9E.toInt()
    }

    private val rect = RectF()

    fun setPercent(p: Float) {
        percent = p.coerceIn(0f, 100f)
        arcPaint.color = when {
            percent > 50f -> 0xFF4CAF50.toInt()
            percent > 20f -> 0xFFFFB300.toInt()
            else -> 0xFFF44336.toInt()
        }
        invalidate()
    }

    fun clear() {
        percent = -1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (140 * density).toInt()
        val w = resolveSize(size, widthMeasureSpec)
        val h = resolveSize(size, heightMeasureSpec)
        val s = min(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = trackPaint.strokeWidth / 2f
        val radius = min(width, height) / 2f - half

        canvas.drawCircle(cx, cy, radius, trackPaint)

        if (percent >= 0f) {
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            val sweep = (percent / 100f) * 360f
            canvas.drawArc(rect, -90f, sweep, false, arcPaint)
        }

        val valueText = if (percent >= 0f) String.format("%.0f", percent) else "– –"
        val valueY = cy + valuePaint.textSize * 0.33f - labelPaint.textSize * 0.4f
        canvas.drawText(valueText, cx, valueY, valuePaint)

        val labelY = valueY + labelPaint.textSize + 4f * density
        canvas.drawText("% SOC", cx, labelY, labelPaint)
    }
}
