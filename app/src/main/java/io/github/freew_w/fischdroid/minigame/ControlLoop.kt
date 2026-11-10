package io.github.freew_w.fischdroid.minigame

import io.github.freew_w.fischdroid.minigame.Detector.Bar
import io.github.freew_w.fischdroid.minigame.Detector.Fish

/**
 * Continuous PD controller for a plant where holding accelerates the bar right and releasing
 * accelerates it left.
 *
 *     u = POSITION_GAIN * error - VELOCITY_GAIN * relativeVelocity
 *
 * `u > 0` means "hold", `u < 0` means "release". A small switching band keeps the command from
 * chattering when the bar sits on the line. The bar and fish states are tracked with an
 * alpha-beta observer that propagates the bar through the action chosen on the previous frame,
 * so the derivative term stays usable with coarse, quantized detections.
 */
object ControlLoop {
    private class State(
        var lastSampleMs: Long,
        var barX: Float,
        var barV: Float,
        var fishX: Float,
        var fishV: Float,
        var holding: Boolean,
        var samples: Int,
    )

    /** Fixed accelerations, in px/s^2. Used only by the observer's motion model. */
    private const val ACCEL_HOLD = 1150f
    private const val ACCEL_RELEASE = 1000f

    private const val POSITION_GAIN = 4.0f
    private const val VELOCITY_GAIN = 3.2f

    /** Command hysteresis, in px/s, to avoid toggling on velocity estimate noise. */
    private const val SWITCH_BAND = 10f

    private const val EST_ALPHA = 0.7f
    private const val EST_BETA = 0.5f

    /** Ignore the first few samples while the observer settles. */
    private const val MIN_SAMPLES = 3

    private const val RESET_AFTER_MS = 500L

    private var state: State? = null

    fun reset() {
        state = null
    }

    /** Returns true to hold, false to release, or null while the observer is not ready. */
    fun decide(fish: Fish, bar: Bar, nowMs: Long): Boolean? {
        val s = state
        if (s == null || nowMs - s.lastSampleMs > RESET_AFTER_MS) {
            state = State(nowMs, bar.center.toFloat(), 0f, fish.x.toFloat(), 0f, false, 1)
            return null
        }

        val dtMs = nowMs - s.lastSampleMs
        if (dtMs <= 0L) return if (s.samples >= MIN_SAMPLES) s.holding else null
        val dt = dtMs / 1000f

        val acceleration = if (s.holding) ACCEL_HOLD else -ACCEL_RELEASE
        val predictedBarX = s.barX + s.barV * dt + 0.5f * acceleration * dt * dt
        val predictedBarV = s.barV + acceleration * dt

        val barResidual = bar.center - predictedBarX
        s.barX = (predictedBarX + EST_ALPHA * barResidual).coerceIn(
            Detector.BAR_CENTER_MIN, Detector.BAR_CENTER_MAX
        )
        s.barV = predictedBarV + (EST_BETA / dt) * barResidual

        val predictedFishX = s.fishX + s.fishV * dt
        val fishResidual = fish.x - predictedFishX
        s.fishX = predictedFishX + EST_ALPHA * fishResidual
        s.fishV += (EST_BETA / dt) * fishResidual

        s.lastSampleMs = nowMs
        s.samples++
        if (s.samples < MIN_SAMPLES) return null

        val error = s.fishX - s.barX
        val relativeVelocity = s.barV - s.fishV
        val control = POSITION_GAIN * error - VELOCITY_GAIN * relativeVelocity

        s.holding = if (s.holding) {
            control > -SWITCH_BAND
        } else {
            control > SWITCH_BAND
        }
        return s.holding
    }
}
