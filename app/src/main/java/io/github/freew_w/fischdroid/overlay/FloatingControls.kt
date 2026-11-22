package io.github.freew_w.fischdroid.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import io.github.freew_w.fischdroid.R
import kotlin.math.abs

/**
 * Draggable start/stop bubble shown over the game.
 *
 * The bubble is a plain UI control: it asks the supplied callbacks whether automation is running
 * and toggles it, so it has no compile-time dependency on the pipeline or any global.
 */
class FloatingControls(
    service: AccessibilityService,
    private val isRunning: () -> Boolean,
    private val onToggle: () -> Unit,
) {
    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val buttonParams = createParams()
    private val floatingButtonView: View =
        LayoutInflater.from(service).inflate(R.layout.floating_button, null)
    private val toggleLabel: TextView = floatingButtonView.findViewById(R.id.btnToggle)
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

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
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    buttonParams.x = initialX + dx.toInt()
                    buttonParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(floatingButtonView, buttonParams)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) v.performClick()
                isDragging = false
                true
            }

            else -> false
        }
    }

    init {
        makeNotTouchable(floatingButtonView)
        floatingButtonView.setOnTouchListener(dragListener)
        floatingButtonView.setOnClickListener {
            onToggle()
            updateLabel()
        }
    }

    fun show() {
        if (attached) return
        updateLabel()
        windowManager.addView(floatingButtonView, buttonParams)
        attached = true
    }

    fun hide() {
        if (!attached) return
        runCatching { windowManager.removeView(floatingButtonView) }
        attached = false
    }

    private fun updateLabel() {
        toggleLabel.text = if (isRunning()) "Stop" else "Start"
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
