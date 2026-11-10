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

    private var holding = false
    private var holdX = 0f
    private var holdY = 0f
    private var strokeX = 0f
    private var strokeY = 0f
    private var stroke: GestureDescription.StrokeDescription? = null
    private var runningGesture: GestureDescription? = null

    private val holdCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            if (gestureDescription !== runningGesture) return
            runningGesture = null

            val previous = stroke
            when {
                holding -> {
                    if (previous == null || !previous.willContinue()) {
                        stroke = null
                        strokeX = holdX
                        strokeY = holdY
                    }
                    startHoldSegment()
                }

                previous != null && previous.willContinue() -> endHoldSegment()

                else -> clearHold()
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (gestureDescription !== runningGesture) return
            Log.w(TAG, "hold segment cancelled")
            clearHold()
        }
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
        clearHold()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        clearHold()
        OverlayManager.hide(this)
        instance = null
        super.onDestroy()
    }

    fun press(x: Float, y: Float, duration: Long) {
        clearHold()

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

    fun setHolding(hold: Boolean, x: Float, y: Float) {
        if (holding == hold) return
        holding = hold

        if (hold) {
            holdX = x
            holdY = y
            if (runningGesture == null) {
                stroke = null
                strokeX = x
                strokeY = y
                startHoldSegment()
            }
        }
    }

    private fun startHoldSegment() {
        val targetX = if (strokeX == holdX) holdX + 1f else holdX
        val path = Path().apply {
            moveTo(strokeX, strokeY)
            lineTo(targetX, holdY)
        }
        val next = stroke?.continueStroke(path, 0L, 20L, true)
            ?: GestureDescription.StrokeDescription(path, 0L, 20L, true)

        strokeX = targetX
        strokeY = holdY
        dispatchHoldSegment(next)
    }

    private fun endHoldSegment() {
        val previous = stroke ?: run {
            clearHold()
            return
        }
        val path = Path().apply { moveTo(strokeX, strokeY) }
        val next = previous.continueStroke(path, 0L, 1L, false)
        dispatchHoldSegment(next)
    }

    private fun dispatchHoldSegment(next: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(next).build()
        stroke = next
        runningGesture = gesture

        if (!dispatchGesture(gesture, holdCallback, null)) {
            Log.e(TAG, "hold: dispatchGesture was REJECTED by the system.")
            clearHold()
        }
    }

    private fun clearHold() {
        holding = false
        stroke = null
        runningGesture = null
    }
}
