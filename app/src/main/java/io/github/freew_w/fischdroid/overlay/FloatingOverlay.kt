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
import io.github.freew_w.fischdroid.Orchestrator
import io.github.freew_w.fischdroid.R
import kotlin.math.abs

class FloatingOverlay(service: AccessibilityService) {
    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val buttonParams = createParams()

    //    private val menuParams   = createParams()
    private val floatingButtonView: View =
        LayoutInflater.from(service).inflate(R.layout.floating_button, null)

    //    private val floatingMenuView: View =
//        LayoutInflater.from(service).inflate(R.layout.floating_menu, null)
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

//    private var isMenuOpen = false

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
            Orchestrator.started = !Orchestrator.started
            floatingButtonView.findViewById<TextView>(R.id.btnToggle).text = if (Orchestrator.started) "Stop" else "Start"
        }
        windowManager.addView(floatingButtonView, buttonParams)

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

//    private fun toggleMenu() {
//        if (isMenuOpen) {
//            windowManager.removeView(floatingMenuView)
//            floatingButtonView.visibility = View.VISIBLE
//        } else {
//            floatingButtonView.visibility = View.GONE
//
////            val params = createLayoutParams(isMenu = true)
//
//            windowManager.addView(floatingMenuView, menuParams)
////            menuView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.menu_scale_in))
//        }
//        isMenuOpen = !isMenuOpen
//    }

    fun hide() {
        runCatching { windowManager.removeView(floatingButtonView) }
//        runCatching { windowManager.removeView(floatingMenuView) }
    }
}