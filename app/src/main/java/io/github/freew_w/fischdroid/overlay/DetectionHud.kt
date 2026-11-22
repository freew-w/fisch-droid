package io.github.freew_w.fischdroid.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import io.github.freew_w.fischdroid.automation.DetectionHudSink
import io.github.freew_w.fischdroid.detection.Bar
import io.github.freew_w.fischdroid.detection.Fish
import io.github.freew_w.fischdroid.detection.ShakeButton

/**
 * Full-screen, non-touchable debug HUD that draws the latest detections.
 *
 * The pipeline calls the sink methods from its worker thread; this class coalesces identical
 * updates and posts the actual view mutation to the main thread.
 */
class DetectionHud(
    private val service: AccessibilityService,
    private val drawY: () -> Float,
) : DetectionHudSink {
    private class HudView(
        context: Context,
        private val drawY: () -> Float,
    ) : View(context) {
        private val fishPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
        private val barPaint = Paint().apply {
            color = Color.argb(128, 0, 255, 0) // 50% opacity
            style = Paint.Style.FILL
        }
        private val shakePaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val shakeCenterPaint =
            Paint().apply { color = Color.CYAN; style = Paint.Style.FILL }

        var fish: Fish? = null
        var bar: Bar? = null
        var shake: ShakeButton? = null

        fun clearDetections() {
            fish = null
            bar = null
            shake = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val y = drawY()

            bar?.let {
                canvas.drawRect(it.x1.toFloat(), y, it.x2.toFloat(), y + 10, barPaint)
            }

            fish?.let {
                canvas.drawRect(it.x.toFloat(), y, it.x.toFloat() + 5f, y + 10, fishPaint)
            }

            shake?.let {
                canvas.drawCircle(
                    it.centerX.toFloat(), it.centerY.toFloat(), it.radius.toFloat(), shakePaint
                )
                canvas.drawCircle(it.centerX.toFloat(), it.centerY.toFloat(), 6f, shakeCenterPaint)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private var hudView: HudView? = null
    private var lastFish: Fish? = null
    private var lastBar: Bar? = null
    private var lastShake: ShakeButton? = null
    private var shakeVisible = false

    @Synchronized
    fun show() {
        if (hudView != null) return

        val view = HudView(service, drawY).apply {
            isClickable = false
            isFocusable = false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(view, params)
        hudView = view
        lastFish = null
        lastBar = null
        lastShake = null
        shakeVisible = false
    }

    @Synchronized
    fun hide() {
        val view = hudView ?: return
        runCatching { windowManager.removeView(view) }
        hudView = null
        lastFish = null
        lastBar = null
        lastShake = null
        shakeVisible = false
    }

    @Synchronized
    override fun showMinigame(fish: Fish?, bar: Bar?) {
        if (fish == lastFish && bar == lastBar) return
        lastFish = fish
        lastBar = bar
        mainHandler.post {
            hudView?.let {
                it.fish = fish
                it.bar = bar
                it.invalidate()
            }
        }
    }

    @Synchronized
    override fun showShake(button: ShakeButton?) {
        if (shakeVisible && button == lastShake) return
        shakeVisible = true
        lastShake = button
        mainHandler.post {
            hudView?.let {
                it.shake = button
                it.invalidate()
            }
        }
    }

    @Synchronized
    override fun clear() {
        if (lastFish == null && lastBar == null && !shakeVisible) return
        lastFish = null
        lastBar = null
        lastShake = null
        shakeVisible = false
        mainHandler.post {
            hudView?.let {
                it.clearDetections()
                it.invalidate()
            }
        }
    }
}
