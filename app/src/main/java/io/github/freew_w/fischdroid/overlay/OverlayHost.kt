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

    /** Sink handed to the automation pipeline. */
    val hud: DetectionHudSink
        get() = detectionHud

    fun show() {
        detectionHud.show()
        controls.show()
    }

    fun hide() {
        controls.hide()
        detectionHud.hide()
    }
}
