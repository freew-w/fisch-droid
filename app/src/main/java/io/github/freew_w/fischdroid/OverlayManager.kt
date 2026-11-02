package io.github.freew_w.fischdroid

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.WindowManager

object OverlayManager {
    private var overlayView: OverlayView? = null

    fun show(service: AccessibilityService) {
        if (overlayView != null) return

        val windowManager =
            service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

        overlayView = OverlayView(service).apply {
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

        windowManager.addView(overlayView, params)
    }

    fun update(fish: Int?, bar: Pair<Int, Int>?) {
        overlayView?.update(fish, bar)
    }

    fun dbgDots(edges: List<Int>?) {
        overlayView?.dbgDots(edges)
    }

    fun hide(service: AccessibilityService) {
        overlayView?.let {
            val windowManager =
                service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(it)
            overlayView = null
        }
    }
}