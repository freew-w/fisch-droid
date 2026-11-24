package io.github.freew_w.fischdroid.overlay

import android.accessibilityservice.AccessibilityService
import io.github.freew_w.fischdroid.automation.DetectionHudSink

/**
 * Owns every accessibility overlay for one [AccessibilityService] instance and gives them one
 * lifecycle. The service calls [show]/[hide] once; the pipeline only ever sees the [hud] sink.
 */
class OverlayHost(
    service: AccessibilityService,
    drawY: () -> Float,
    isRunning: () -> Boolean,
    onToggle: () -> Unit,
) {
    private val detectionHud = DetectionHud(service, drawY)
    private val controls = FloatingControls(service, isRunning, onToggle)

    private var shown = false
    private var controlsVisible = false

    /** Sink handed to the automation pipeline. */
    val hud: DetectionHudSink
        get() = detectionHud

    fun show() {
        shown = true
        detectionHud.show()
        syncControls()
    }

    fun hide() {
        shown = false
        controls.hide()
        detectionHud.hide()
    }

    /**
     * Shows or hides the floating start/stop bubble. The bubble is only useful once screen capture
     * is running, so the service keeps it hidden until the macro is actually ready to fish.
     */
    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        syncControls()
    }

    private fun syncControls() {
        if (shown && controlsVisible) controls.show() else controls.hide()
    }
}
