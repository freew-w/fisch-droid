package io.github.freew_w.fischdroid.control

/**
 * Gains for the bar controller. Kept separate from [io.github.freew_w.fischdroid.detection.DetectionSpec]
 * so detector tuning and control tuning can change independently.
 */
data class ControlTuning(
    val positionGain: Float,
    val velocityGain: Float,
    val switchBand: Float,
)
