package io.github.freew_w.fischdroid.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.github.freew_w.fischdroid.R
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
        private val density = resources.displayMetrics.density

        private fun dp(value: Float): Float = value * density

        private val barHeight = dp(8f)
        private val fishRadius = dp(6f)
        private val fishHaloStroke = dp(1f)
        private val shakeStroke = dp(3f)
        private val shakeHaloStroke = dp(6f)
        private val dotRadius = dp(3f)

        private val secondary = ContextCompat.getColor(context, R.color.md_secondary)
        private val primary = ContextCompat.getColor(context, R.color.md_primary)
        private val halo = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.md_inverse_surface), 140
        )

        private val barHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = halo
            style = Paint.Style.FILL
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondary
            style = Paint.Style.FILL
        }
        private val fishHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = halo
            style = Paint.Style.STROKE
            strokeWidth = fishHaloStroke
        }
        private val fishPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            style = Paint.Style.FILL
        }
        private val shakeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = halo
            style = Paint.Style.STROKE
            strokeWidth = shakeHaloStroke
            strokeCap = Paint.Cap.ROUND
        }
        private val shakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondary
            style = Paint.Style.STROKE
            strokeWidth = shakeStroke
            strokeCap = Paint.Cap.ROUND
        }
        private val shakeCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondary
            style = Paint.Style.FILL
        }

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

            // Shared horizontal baseline: every marker is centred vertically on the HUD line.
            val centerY = drawY()
            val barRadius = barHeight / 2f
            val haloPad = dp(2f)

            // Halos go down first so each theme-coloured marker keeps a contrasting edge
            // over bright water or dark cave frames.
            bar?.let {
                canvas.drawRoundRect(
                    it.x1.toFloat() - haloPad,
                    centerY - barRadius - haloPad,
                    it.x2.toFloat() + haloPad,
                    centerY + barRadius + haloPad,
                    barRadius + haloPad,
                    barRadius + haloPad,
                    barHaloPaint,
                )
            }

            bar?.let {
                canvas.drawRoundRect(
                    it.x1.toFloat(),
                    centerY - barRadius,
                    it.x2.toFloat(),
                    centerY + barRadius,
                    barRadius,
                    barRadius,
                    barPaint,
                )
            }

            fish?.let {
                canvas.drawCircle(
                    it.x.toFloat(), centerY, fishRadius + fishHaloStroke, fishHaloPaint
                )
                canvas.drawCircle(it.x.toFloat(), centerY, fishRadius, fishPaint)
            }

            shake?.let {
                val centerX = it.centerX.toFloat()
                val y = it.centerY.toFloat()
                val radius = it.radius.toFloat()
                canvas.drawCircle(centerX, y, radius, shakeHaloPaint)
                canvas.drawCircle(centerX, y, radius, shakePaint)
                canvas.drawCircle(centerX, y, dotRadius, shakeCenterPaint)
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
