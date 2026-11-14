package io.github.freew_w.fischdroid.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.freew_w.fischdroid.overlay.DetectionOverlay
import io.github.freew_w.fischdroid.overlay.FloatingOverlay

private const val TAG = "TapService"

class TapService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: TapService? = null
            private set

        private const val STROKE_DURATION_MS = 20L
        private const val RELEASE_DURATION_MS = 1L
        private const val HOLD_JITTER_PX = 1f
    }

    private var holdRequested = false
    private var holdX = 0f
    private var holdY = 0f
    private var strokeEndX = 0f
    private var strokeEndY = 0f
    private var previousStroke: GestureDescription.StrokeDescription? = null
    private var previousGesture: GestureDescription? = null

    private val holdCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            if (gestureDescription !== previousGesture) return
            if (holdRequested) sendHoldSegment() else sendEndSegment()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (gestureDescription !== previousGesture) return
            Log.w(TAG, "hold segment cancelled")
            resetHold()
        }
    }

    private var floatingOverlay: FloatingOverlay? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")
        super.onServiceConnected()
        DetectionOverlay.show(this)
        floatingOverlay = FloatingOverlay(this)
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        resetHold()
        DetectionOverlay.hide(this)
        floatingOverlay?.hide()
        instance = null
        return super.onUnbind(intent)
    }

    fun press(x: Float, y: Float, duration: Long) {
        resetHold()

        val tapPath = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(tapPath, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        if (!dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "press completed at $x, $y")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "press at $x, $y cancelled")
                }
            }, null)) {
            Log.e(TAG, "press: dispatchGesture was rejected by the system.")
        }
    }

    fun setHolding(hold: Boolean, x: Float, y: Float) {
        if (hold == holdRequested) return
        holdRequested = hold
        if (hold) {
            holdX = x
            holdY = y
            if (previousGesture != null) return
            Log.i(TAG, "sending a fresh hold segment")
            sendHoldSegment()
        }
    }

    private fun sendHoldSegment() {
        val previous = previousStroke
        if (previous != null && !previous.willContinue()) {
            Log.i(TAG, "cannot continue a hold segment")
            resetHold()
            return
        }

        Log.i(TAG, "sending a hold segment")
        val targetX = if (strokeEndX == holdX) holdX + HOLD_JITTER_PX else holdX
        val path = Path().apply {
            moveTo(strokeEndX, strokeEndY)
            lineTo(targetX, holdY)
        }
        val next = previous?.continueStroke(path, 0L, STROKE_DURATION_MS, true)
            ?: GestureDescription.StrokeDescription(path, 0L, STROKE_DURATION_MS, true)

        strokeEndX = targetX
        strokeEndY = holdY
        dispatchSegment(next)
    }

    private fun sendEndSegment() {
        val previous = previousStroke
        if (previous == null || !previous.willContinue()) {
            Log.i(TAG, "cannot end a segment")
            resetHold()
            return
        }

        Log.i(TAG, "sending an end segment")
        val path = Path().apply { moveTo(strokeEndX, strokeEndY) }
        val next = previous.continueStroke(path, 0L, RELEASE_DURATION_MS, false)
        dispatchSegment(next)
    }

    private fun dispatchSegment(next: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(next).build()
        previousStroke = next
        previousGesture = gesture

        if (!dispatchGesture(gesture, holdCallback, null)) {
            Log.e(TAG, "hold: dispatchGesture was rejected by the system.")
            resetHold()
        }
    }

    private fun resetHold() {
        holdRequested = false
        previousStroke = null
        previousGesture = null
    }
}
