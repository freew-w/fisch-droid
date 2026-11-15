package io.github.freew_w.fischdroid.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import io.github.freew_w.fischdroid.minigame.Detector.Bar
import io.github.freew_w.fischdroid.minigame.Detector.Fish

object DetectionOverlay {
    private class DetectionOverlayView(
        context: Context,
        private val drawY: () -> Float,
    ) : View(context) {
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
        private val shakeCenterPaint =
            Paint().apply { color = Color.CYAN; style = Paint.Style.FILL }

        var dots: List<Int>? = null
        var fish: Fish? = null
        var bar: Bar? = null
        var shake: io.github.freew_w.fischdroid.shake.Detector.Button? = null

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val y = drawY()

            bar?.let {
                canvas.drawRect(it.x1.toFloat(), y, it.x2.toFloat(), y + 10, barPaint)
            }

            fish?.let {
                canvas.drawRect(it.x.toFloat(), y, it.x.toFloat() + 5f, y + 10, fishPaint)
            }

            dots?.let {
                for (edge in it) {
                    canvas.drawCircle(edge.toFloat(), y, 5f, barPaint)
                }
            }

            shake?.let {
                canvas.drawCircle(
                    it.centerX.toFloat(), it.centerY.toFloat(), it.radius.toFloat(), shakePaint
                )
                canvas.drawCircle(it.centerX.toFloat(), it.centerY.toFloat(), 6f, shakeCenterPaint)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var detectionOverlayView: DetectionOverlayView? = null

    fun show(service: AccessibilityService, drawY: () -> Float) {
        if (detectionOverlayView != null) return

        val windowManager =
            service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val view = DetectionOverlayView(service, drawY).apply {
            isClickable = false
            isFocusable = false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(view, params)

        this.windowManager = windowManager
        detectionOverlayView = view
    }

    fun updateMinigame(fish: Fish?, bar: Bar?) {
        detectionOverlayView?.let {
            it.fish = fish
            it.bar = bar
            it.invalidate()
        }
    }

    fun updateShake(button: io.github.freew_w.fischdroid.shake.Detector.Button?) {
        detectionOverlayView?.let {
            it.shake = button
            it.invalidate()
        }
    }

    fun dbgDots(edges: List<Int>?) {
        detectionOverlayView?.let {
            it.dots = edges
            it.invalidate()
        }
    }

    fun hide() {
        if (detectionOverlayView == null) return
        windowManager?.let { runCatching { it.removeView(detectionOverlayView) } }
        detectionOverlayView = null
        windowManager = null
    }
}
