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
 * chattering when the bar sits on the line. Positions come straight from the detector and
 * velocities are raw differences between consecutive detections; there is no motion model or
 * prediction.
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

    private const val POSITION_GAIN = 4.0f
    private const val VELOCITY_GAIN = 3.2f

    /** Command hysteresis, in px/s, to avoid toggling on velocity estimate noise. */
    private const val SWITCH_BAND = 50f

    /** Ignore the first few samples while the raw velocities settle. */
    private const val MIN_SAMPLES = 3

    private const val RESET_AFTER_MS = 500L

    private var state: State? = null

    fun reset() {
        state = null
    }

    /** Returns true to hold, false to release, or null while there are not enough samples. */
    fun decide(fish: Fish, bar: Bar, nowMs: Long): Boolean? {
        val s = state
        if (s == null || nowMs - s.lastSampleMs > RESET_AFTER_MS) {
            state = State(nowMs, bar.center.toFloat(), 0f, fish.x.toFloat(), 0f, false, 1)
            return null
        }

        val dtMs = nowMs - s.lastSampleMs
        if (dtMs <= 0L) return if (s.samples >= MIN_SAMPLES) s.holding else null
        val dt = dtMs / 1000f

        val barX = bar.center.toFloat()
        val fishX = fish.x.toFloat()
        s.barV = (barX - s.barX) / dt
        s.fishV = (fishX - s.fishX) / dt
        s.barX = barX
        s.fishX = fishX

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
