package io.github.freew_w.fischdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class OverlayView(context: Context) : View(context) {
    private var edges: List<Int>? = null
    private var fish: Int? = null
    private var bar: Pair<Int, Int>? = null
    private val drawY = Detector.TRACK_SCAN_YS[2].toFloat() - 150f
    private val fishPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
    private val barPaint = Paint().apply {
        color = Color.argb(128, 0, 255, 0) // 128 is 50% opacity
        style = Paint.Style.FILL
    }

    fun update(fish: Int?, bar: Pair<Int, Int>?) {
        this.fish = fish
        this.bar = bar
        invalidate()
    }

    fun dbgDots(edges: List<Int>?) {
        this.edges = edges
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bar?.let {
            canvas.drawRect(it.first.toFloat(), drawY, it.second.toFloat(), drawY + 10, barPaint)
        }

        fish?.let {
            canvas.drawRect(it.toFloat(), drawY, it.toFloat() + 5f, drawY + 10, fishPaint)
        }

        edges?.let {
            for (edge in it) {
                canvas.drawCircle(edge.toFloat(), drawY, 5f, barPaint)
            }
        }
    }
}