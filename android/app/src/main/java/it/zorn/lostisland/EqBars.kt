package it.zorn.lostisland

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Five bouncing bars, the universal "something is playing" glyph.
 * Sine pseudo-beat mirroring the desktop fallback; the ticker only runs
 * while the view is shown and music plays.
 */
class EqBars(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val barW = dpF(3f)
    private val gap = dpF(2.5f)
    private var phase = 0f
    private var playing = false

    private val tick = object : Runnable {
        override fun run() {
            phase += 0.55f
            invalidate()
            postDelayed(this, 80)
        }
    }

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        sync()
        invalidate()
    }

    private fun sync() {
        removeCallbacks(tick)
        if (playing && isShown) postDelayed(tick, 80)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        sync()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((5 * barW + 4 * gap).toInt(), dpF(14f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        for (i in 0 until 5) {
            val frac = if (playing) 0.35f + 0.65f * abs(sin(phase + i * 1.1f)) else 0.25f
            val bh = h * frac
            val x = i * (barW + gap)
            val y = (h - bh) / 2
            canvas.drawRoundRect(x, y, x + barW, y + bh, barW / 2, barW / 2, paint)
        }
    }

    private fun dpF(v: Float) = v * resources.displayMetrics.density
}
