package io.github.freew_w.fischdroid.detection

/**
 * The calibrated play-field rectangle.
 *
 * [rodControl] is the user's bar-width control, not a controller gain: the bar's expected pixel
 * width is derived from it in [DetectionSpec]. Control-loop gains live in
 * `io.github.freew_w.fischdroid.control.ControlTuning`.
 */
data class TrackGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val rodControl: Float,
)
