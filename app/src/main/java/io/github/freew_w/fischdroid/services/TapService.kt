package io.github.freew_w.fischdroid.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.freew_w.fischdroid.overlay.OverlayManager

private const val TAG = "TapService"

class TapService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: TapService? = null
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlayManager.show(this)
        instance = this
        Log.i(TAG, "onServiceConnected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        OverlayManager.hide(this)
        instance = null
        super.onDestroy()
    }

    fun press(x: Float, y: Float, duration: Long) {
        val tapPath = Path()
        tapPath.moveTo(x, y)

        val stroke = GestureDescription.StrokeDescription(tapPath, 0L, duration)

        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val accepted = dispatchGesture(
            gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "press: COMPLETED at ($x, $y) for ${duration}ms")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "press: CANCELLED at ($x, $y)")
                }
            }, null
        )

        if (!accepted) {
            Log.e(TAG, "press: dispatchGesture was REJECTED by the system.")
        }
    }
}