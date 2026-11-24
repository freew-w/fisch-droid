package io.github.freew_w.fischdroid.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import io.github.freew_w.fischdroid.Orchestrator
import io.github.freew_w.fischdroid.automation.GestureSink
import io.github.freew_w.fischdroid.config.Config
import io.github.freew_w.fischdroid.overlay.OverlayHost

private const val TAG = "TapService"

class TapService : AccessibilityService(), GestureSink {
    companion object {
        private const val STROKE_DURATION_MS = 20L
        private const val RELEASE_DURATION_MS = 1L
        private const val HOLD_JITTER_PX = 1f
        private const val HUD_OFFSET_PX = 150f

        /**
         * Whether the system currently has this accessibility service enabled. Reads the system
         * setting rather than the pipeline, so it is correct before the service process connects
         * (and while it is dead). No permission required.
         */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            if (!manager.isEnabled) return false
            val serviceName = TapService::class.java.name
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == context.packageName && serviceInfo.name == serviceName
                }
        }
    }

    /**
     * Every gesture call and its result callback runs on this handler, so all the hold state
     * below is confined to the main thread even though callers live on background dispatchers.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var holdRequested = false
    private var holdX = 0f
    private var holdY = 0f

    /** Where the last injected point ended up; a continuation has to start exactly here. */
    private var pointX = 0f
    private var pointY = 0f

    /** The stroke the system accepted last, and the gesture carrying it while it is in flight. */
    private var stroke: GestureDescription.StrokeDescription? = null
    private var gesture: GestureDescription? = null

    /**
     * The injector only learns the touch point when it *processes* the gesture, not when
     * `dispatchGesture` returns. A continuation arriving before that is rejected, and the system
     * then drops the gesture's still-queued `ACTION_DOWN`, so the game never sees the touch at all.
     * Every continuation below is therefore dispatched from a result callback (or while [gesture]
     * is null), never on top of a gesture that is still in flight.
     */
    private val holdCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            if (gestureDescription !== gesture) return
            gesture = null

            if (holdRequested) {
                startHoldSegment()
            } else if (stroke?.willContinue() == true) {
                sendEndSegment()
            } else {
                stroke = null
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (gestureDescription !== gesture) return
            // The system dropped the touch (another input event arrived, or it refused the
            // continuation), so the stroke id chain is gone and the hold has to be re-pressed.
            Log.w(TAG, "hold segment dropped")
            gesture = null
            stroke = null

            if (holdRequested) {
                startHoldSegment()
            }
        }
    }

    private var overlayHost: OverlayHost? = null

    /** Keeps the floating start/stop bubble in step with screen capture being active. */
    private val captureStateListener: (Boolean) -> Unit = { active ->
        overlayHost?.setControlsVisible(active)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")
        super.onServiceConnected()

        overlayHost?.hide()
        val overlays = OverlayHost(
            service = this,
            drawY = { Config.trackY + Config.trackH / 2f - HUD_OFFSET_PX },
            isRunning = { Orchestrator.isRunning },
            onToggle = { Orchestrator.toggle() },
        )
        overlays.show()
        overlayHost = overlays
        Orchestrator.attach(gestures = this, hud = overlays.hud)
        // addStateListener reports the current value immediately, so this also sets the initial state.
        ScreenCaptureService.addStateListener(captureStateListener)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        ScreenCaptureService.removeStateListener(captureStateListener)
        Orchestrator.detach()
        overlayHost?.hide()
        overlayHost = null
        resetHold()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenCaptureService.removeStateListener(captureStateListener)
        super.onDestroy()
    }

    override fun press(x: Int, y: Int, durationMs: Long) {
        mainHandler.post {
            // The tap is a new gesture, so any tracked hold is no longer valid.
            resetHold()

            val tapPath = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val tapStroke = GestureDescription.StrokeDescription(tapPath, 0L, durationMs)
            val tapGesture = GestureDescription.Builder().addStroke(tapStroke).build()

            if (!dispatchGesture(tapGesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "press completed at $x, $y")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "press at $x, $y cancelled")
                    }
                }, mainHandler)) {
                Log.e(TAG, "press: dispatchGesture was rejected by the system.")
            }
        }
    }

    override fun setHolding(hold: Boolean, x: Int, y: Int) {
        mainHandler.post { updateHolding(hold, x.toFloat(), y.toFloat()) }
    }

    private fun updateHolding(hold: Boolean, x: Float, y: Float) {
        if (hold) {
            holdX = x
            holdY = y
            if (holdRequested) return
            holdRequested = true
            // A gesture still in flight is either a hold segment that keeps itself going or a
            // release that ends shortly; its callback continues from here.
            if (gesture == null) startHoldSegment()
            return
        }

        if (!holdRequested) return
        holdRequested = false
        if (gesture == null) {
            if (stroke?.willContinue() == true) sendEndSegment() else resetHold()
        }
        // Otherwise the in-flight segment ends the hold when its callback arrives.
    }

    private fun startHoldSegment() {
        // Never continue a gesture the injector has not seen yet; the pending callback will.
        if (gesture != null) return

        val previous = stroke
        val continuing = previous != null && previous.willContinue()

        val startX: Float
        val startY: Float
        if (continuing) {
            startX = pointX
            startY = pointY
        } else {
            // A fresh stroke starts *on* the button: the ACTION_DOWN has to land there, otherwise
            // the gesture begins outside the control and is ignored even though it ends on it.
            startX = holdX
            startY = holdY
            pointX = holdX
            pointY = holdY
            Log.i(TAG, "hold down at $holdX, $holdY")
        }

        // A continuation that does not move generates no MotionEvent and is reported as refused,
        // so skirt one pixel around the hold point on every segment.
        val endX = if (pointX == holdX) holdX + HOLD_JITTER_PX else holdX
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, holdY)
        }

        val next = if (continuing) {
            previous.continueStroke(path, 0L, STROKE_DURATION_MS, true)
        } else {
            GestureDescription.StrokeDescription(path, 0L, STROKE_DURATION_MS, true)
        }

        pointX = endX
        pointY = holdY
        dispatchSegment(next)
    }

    private fun sendEndSegment() {
        if (gesture != null) return

        val previous = stroke
        if (previous == null || !previous.willContinue()) {
            resetHold()
            return
        }

        // A zero-length continuation is still injected as a plain ACTION_UP.
        val path = Path().apply { moveTo(pointX, pointY) }
        dispatchSegment(previous.continueStroke(path, 0L, RELEASE_DURATION_MS, false))
    }

    private fun dispatchSegment(next: GestureDescription.StrokeDescription) {
        val nextGesture = GestureDescription.Builder().addStroke(next).build()
        stroke = next
        gesture = nextGesture

        if (!dispatchGesture(nextGesture, holdCallback, mainHandler)) {
            Log.e(TAG, "hold: dispatchGesture was rejected by the system.")
            resetHold()
        }
    }

    private fun resetHold() {
        holdRequested = false
        stroke = null
        gesture = null
    }
}
