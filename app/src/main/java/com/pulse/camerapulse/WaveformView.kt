package com.pulse.camerapulse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxPoints = 120
    private val points = FloatArray(maxPoints)
    private var head = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2430")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val path = Path()

    fun addSample(value: Float) {
        points[head] = value
        head = (head + 1) % maxPoints
        postInvalidate()
    }

    fun setWaveformColor(colorInt: Int) {
        linePaint.color = colorInt
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, gridPaint)
        canvas.drawLine(0f, h * 0.50f, w, h * 0.50f, gridPaint)
        canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, gridPaint)

        path.reset()
        val stepX = w / (maxPoints - 1)
        val midY = h / 2f
        val scaleY = h * 0.40f

        var first = true
        for (i in 0 until maxPoints) {
            val idx = (head + i) % maxPoints
            val x = i * stepX
            val raw = points[idx].coerceIn(-1.5f, 1.5f)
            val y = midY - (raw * scaleY)

            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, linePaint)
    }
}
