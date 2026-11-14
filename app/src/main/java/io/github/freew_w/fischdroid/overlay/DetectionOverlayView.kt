package io.github.freew_w.fischdroid.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import io.github.freew_w.fischdroid.minigame.Detector

class DetectionOverlayView(context: Context) : View(context) {
    private var edges: List<Int>? = null
    private var fish: Detector.Fish? = null
    private var bar: Detector.Bar? = null
    private var shake: io.github.freew_w.fischdroid.shake.Detector.Button? = null
    private val drawY = Detector.TRACK_SCAN_YS[2].toFloat() - 150f
    private val fishPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
    private val barPaint = Paint().apply {
        color = Color.argb(128, 0, 255, 0) // 128 is 50% opacity
        style = Paint.Style.FILL
    }
    private val shakePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val shakeCenterPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.FILL }

    fun updateMinigame(fish: Detector.Fish?, bar: Detector.Bar?) {
        this.fish = fish
        this.bar = bar
        invalidate()
    }

    fun updateShake(button: io.github.freew_w.fischdroid.shake.Detector.Button?) {
        if (shake == button) return
        shake = button
        invalidate()
    }

    fun dbgDots(edges: List<Int>?) {
        this.edges = edges
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bar?.let {
            canvas.drawRect(it.x1.toFloat(), drawY, it.x2.toFloat(), drawY + 10, barPaint)
        }

        fish?.let {
            canvas.drawRect(it.x.toFloat(), drawY, it.x.toFloat() + 5f, drawY + 10, fishPaint)
        }

        edges?.let {
            for (edge in it) {
                canvas.drawCircle(edge.toFloat(), drawY, 5f, barPaint)
            }
        }

        shake?.let {
            canvas.drawCircle(
                it.centerX.toFloat(),
                it.centerY.toFloat(),
                it.radius.toFloat(),
                shakePaint
            )
            canvas.drawCircle(it.centerX.toFloat(), it.centerY.toFloat(), 6f, shakeCenterPaint)
        }
    }
}