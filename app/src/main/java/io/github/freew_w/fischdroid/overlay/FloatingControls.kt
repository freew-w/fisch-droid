package io.github.freew_w.fischdroid.overlay

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.freew_w.fischdroid.R
import kotlin.math.abs

/**
 * Draggable start/stop bubble shown over the game.
 *
 * The bubble is a plain UI control: it asks the supplied callbacks whether automation is running
 * and toggles it, so it has no compile-time dependency on the pipeline or any global. It is
 * inflated with the app theme so it uses the same M3 filled/tonal button language as the main
 * screen, with the HUD's capsule corners.
 */
class FloatingControls(
    service: AccessibilityService,
    private val isRunning: () -> Boolean,
    private val onToggle: () -> Unit,
) {
    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val buttonParams = createParams()
    private val themedContext = ContextThemeWrapper(service, R.style.Theme_FischDroid)
    private val toggleButton: MaterialButton =
        LayoutInflater.from(themedContext).inflate(R.layout.floating_button, null) as MaterialButton
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private val startBackground = ContextCompat.getColor(themedContext, R.color.md_primary)
    private val startForeground = ContextCompat.getColor(themedContext, R.color.md_on_primary)
    private val runningBackground =
        ContextCompat.getColor(themedContext, R.color.md_secondary_container)
    private val runningForeground =
        ContextCompat.getColor(themedContext, R.color.md_on_secondary_container)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var attached = false

    private fun createParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private val dragListener = View.OnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = buttonParams.x
                initialY = buttonParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                // Set pressed manually: the drag listener consumes the event before the button's
                // own touch handling can show its ripple.
                v.isPressed = true
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                    v.isPressed = false
                }
                if (isDragging) {
                    buttonParams.x = initialX + dx.toInt()
                    buttonParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(toggleButton, buttonParams)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (!isDragging) v.performClick()
                isDragging = false
                true
            }

            else -> false
        }
    }

    init {
        makeNotTouchable(toggleButton)
        toggleButton.setOnTouchListener(dragListener)
        toggleButton.setOnClickListener {
            onToggle()
            updateLabel()
        }
    }

    fun show() {
        if (attached) return
        updateLabel()
        windowManager.addView(toggleButton, buttonParams)
        attached = true
    }

    fun hide() {
        if (!attached) return
        runCatching { windowManager.removeView(toggleButton) }
        attached = false
    }

    private fun updateLabel() {
        val running = isRunning()
        toggleButton.setText(
            if (running) R.string.overlay_toggle_stop else R.string.overlay_toggle_start
        )
        toggleButton.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        val background = if (running) runningBackground else startBackground
        val foreground = if (running) runningForeground else startForeground
        toggleButton.backgroundTintList = ColorStateList.valueOf(background)
        toggleButton.setTextColor(foreground)
        toggleButton.iconTint = ColorStateList.valueOf(foreground)
    }

    private fun makeNotTouchable(view: View) {
        view.isClickable = false
        view.isLongClickable = false
        view.isFocusable = false
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                makeNotTouchable(view.getChildAt(i))
            }
        }
    }
}
