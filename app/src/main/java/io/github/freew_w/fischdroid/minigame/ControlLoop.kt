package io.github.freew_w.fischdroid.minigame

import io.github.freew_w.fischdroid.minigame.Detector.Bar
import io.github.freew_w.fischdroid.minigame.Detector.Fish
import kotlin.math.abs
import kotlin.math.min

/**
 * Port of the AutoJs controller: a one-step search over hold/release pulse durations.
 *
 * Every candidate pulse is simulated forward from the estimated bar and fish state, followed by
 * a short braking phase that tries to match the fish velocity, and the cheapest outcome is
 * executed. A small alpha-beta observer keeps the state usable despite coarse detections, and
 * the two accelerations are adapted from observed velocity changes.
 *
 * Kept from the JS controller: pulse search, braking lookahead, wall bounces, deadband, velocity
 * cost, acceleration adaptation, minigame lock. Removed: press latency and loop overhead (native
 * Kotlin captures faster than the JS host).
 */
object ControlLoop {
    data class Action(val isHold: Boolean, val durationMs: Long)

    private class State(
        var lastSampleMs: Long,
        var barX: Float,
        var barV: Float,
        var fishX: Float,
        var fishV: Float,
        var lastAction: Action?,
        var accelHold: Float = ACCEL_HOLD_INIT,
        var accelRelease: Float = ACCEL_RELEASE_INIT,
        val firstSeenMs: Long = lastSampleMs,
    )

    private class BarState(var x: Float, var v: Float) {
        var impacts = 0
        var contact = false
    }

    private class FishState(var x: Float, val v: Float)

    private class Prediction(
        val error: Float,
        val velocity: Float,
        val impacts: Int,
        val contact: Boolean,
    )

    private class Choice(val isHold: Boolean, val durationMs: Long, val cost: Float)

    private const val ACCEL_HOLD_INIT = 1150f
    private const val ACCEL_RELEASE_INIT = 1000f
    private const val ACCEL_ADAPT = 0.10f
    private const val EST_ALPHA = 0.7f
    private const val EST_BETA = 0.5f
    private const val MIN_ACCEL = 100f
    private const val MAX_ACCEL = 2000f

    private const val HOLD_MIN_MS = 30L
    private const val HOLD_MAX_MS = 500L
    private const val RELEASE_MIN_MS = 30L
    private const val RELEASE_MAX_MS = 60L
    private const val PULSE_STEP_MS = 10L

    private const val VELOCITY_COST_S = 0.180f
    private const val DEADBAND_PX = 15f
    private const val BOUNCE_PENALTY = 99999f
    private const val CONTACT_PENALTY = 9999f

    private const val BRAKE_MS = 100f
    private const val SIM_STEP_MS = 10f
    private const val SIM_STEP_S = SIM_STEP_MS / 1000f
    private const val BRAKE_S = BRAKE_MS / 1000f
    private const val WALL_BOUNCE = 0.30f
    private const val MIN_WALL_BOUNCE_SPEED = 20f
    private const val MIN_BRAKE_SPEED = 100f

    private const val RESET_AFTER_MS = 500L
    private const val MINIGAME_LOCK_MS = 1500L

    private var state: State? = null

    fun reset() {
        state = null
    }

    /** Returns null while the observer is warming up or has no usable previous sample. */
    fun decide(fish: Fish, bar: Bar, nowMs: Long): Action? {
        var s = state
        if (s == null) {
            s = State(nowMs, bar.center.toFloat(), 0f, fish.x.toFloat(), 0f, null)
            state = s
            return null
        }

        if (nowMs - s.lastSampleMs > RESET_AFTER_MS) {
            state = State(nowMs, bar.center.toFloat(), 0f, fish.x.toFloat(), 0f, null)
            return null
        }

        val locked = nowMs - s.firstSeenMs >= MINIGAME_LOCK_MS
        if (!update(s, nowMs, bar.center.toFloat(), fish.x.toFloat(), locked)) return null

        val choice = choosePulse(s)
        s.lastAction = choice
        return choice
    }

    private fun update(s: State, nowMs: Long, barX: Float, fishX: Float, adapt: Boolean): Boolean {
        val dtMs = nowMs - s.lastSampleMs
        if (dtMs <= 0L) return false
        val dt = dtMs / 1000f

        val predicted = predictBarInterval(s, dt)
        val barResidual = barX - predicted.x
        s.barX = (predicted.x + EST_ALPHA * barResidual)
            .coerceIn(Detector.BAR_CENTER_MIN, Detector.BAR_CENTER_MAX)
        val previousBarV = s.barV
        s.barV = predicted.v + (EST_BETA / dt) * barResidual

        val predictedFishX = s.fishX + s.fishV * dt
        val fishResidual = fishX - predictedFishX
        s.fishX = predictedFishX + EST_ALPHA * fishResidual
        s.fishV += (EST_BETA / dt) * fishResidual

        if (adapt && !predicted.contact &&
            s.barX > Detector.BAR_CENTER_MIN + 0.5f &&
            s.barX < Detector.BAR_CENTER_MAX - 0.5f
        ) {
            val dv = s.barV - previousBarV
            val releaseSeconds = dt - predicted.holdSeconds
            if (predicted.holdSeconds >= 0.03f) {
                s.accelHold = adaptAccel(
                    s.accelHold,
                    (dv + s.accelRelease * releaseSeconds) / predicted.holdSeconds,
                )
            } else if (predicted.holdSeconds == 0f) {
                s.accelRelease = adaptAccel(s.accelRelease, -dv / dt)
            }
        }

        s.lastSampleMs = nowMs
        return true
    }

    private fun choosePulse(s: State): Action? {
        var best: Choice? = null

        var durationMs = RELEASE_MIN_MS
        while (durationMs <= RELEASE_MAX_MS) {
            best = consider(best, simulate(s, durationMs, false), durationMs, false)
            durationMs += PULSE_STEP_MS
        }

        durationMs = HOLD_MIN_MS
        while (durationMs <= HOLD_MAX_MS) {
            best = consider(best, simulate(s, durationMs, true), durationMs, true)
            durationMs += PULSE_STEP_MS
        }

        return best?.let { Action(it.isHold, it.durationMs) }
    }

    /** Simulates the pulse followed by [BRAKE_MS] of maximum braking. */
    private fun simulate(s: State, durationMs: Long, isHold: Boolean): Prediction {
        val bar = BarState(s.barX, s.barV)
        val fish = FishState(s.fishX, s.fishV)
        val seconds = durationMs / 1000f

        if (isHold) {
            advanceBar(bar, s.accelHold, seconds)
        } else {
            advanceBar(bar, -s.accelRelease, seconds)
        }
        fish.x += fish.v * seconds
        brakePhase(bar, fish, s.accelHold, s.accelRelease)

        return Prediction(
            error = bar.x - fish.x,
            velocity = bar.v - fish.v,
            impacts = bar.impacts,
            contact = bar.contact,
        )
    }

    private fun consider(
        best: Choice?,
        predicted: Prediction,
        durationMs: Long,
        isHold: Boolean,
    ): Choice {
        val outside = (abs(predicted.error) - DEADBAND_PX).coerceAtLeast(0f)
        val velocityCost = predicted.velocity * VELOCITY_COST_S
        var cost = outside * outside + velocityCost * velocityCost
        cost += predicted.impacts * BOUNCE_PENALTY
        if (predicted.contact) cost += CONTACT_PENALTY

        if (best == null || cost < best.cost) return Choice(isHold, durationMs, cost)
        return best
    }

    private class PredictedBar(
        val x: Float,
        val v: Float,
        val holdSeconds: Float,
        val contact: Boolean,
    )

    /** Advances the last estimated state through the action that was in flight. */
    private fun predictBarInterval(s: State, dt: Float): PredictedBar {
        val bar = BarState(s.barX, s.barV)
        var elapsed = 0f
        var holdSeconds = 0f
        val action = s.lastAction

        if (action != null && action.isHold) {
            holdSeconds = min(action.durationMs / 1000f, dt)
            advanceBar(bar, s.accelHold, holdSeconds)
            elapsed += holdSeconds
        }
        if (elapsed < dt) advanceBar(bar, -s.accelRelease, dt - elapsed)

        return PredictedBar(bar.x, bar.v, holdSeconds, bar.contact)
    }

    private fun advanceBar(bar: BarState, acceleration: Float, seconds: Float) {
        var remaining = seconds
        while (remaining > 0f) {
            val dt = min(remaining, SIM_STEP_S)
            bar.x += bar.v * dt + 0.5f * acceleration * dt * dt
            bar.v += acceleration * dt

            if (bar.x <= Detector.BAR_CENTER_MIN) {
                bar.x = Detector.BAR_CENTER_MIN
                bar.contact = true
                if (bar.v < -MIN_WALL_BOUNCE_SPEED) {
                    bar.v = -bar.v * WALL_BOUNCE
                    bar.impacts++
                } else if (bar.v < 0f) {
                    bar.v = 0f
                }
            } else if (bar.x >= Detector.BAR_CENTER_MAX) {
                bar.x = Detector.BAR_CENTER_MAX
                bar.contact = true
                if (bar.v > MIN_WALL_BOUNCE_SPEED) {
                    bar.v = -bar.v * WALL_BOUNCE
                    bar.impacts++
                } else if (bar.v > 0f) {
                    bar.v = 0f
                }
            }

            remaining -= dt
        }
    }

    /** Applies maximum acceleration toward zero relative velocity, coasting when already slow. */
    private fun brakePhase(
        bar: BarState,
        fish: FishState,
        accelHold: Float,
        accelRelease: Float,
    ) {
        var remaining = BRAKE_S

        while (remaining > 0f) {
            val relativeVelocity = bar.v - fish.v
            val acceleration = when {
                relativeVelocity < -MIN_BRAKE_SPEED -> accelHold
                relativeVelocity > MIN_BRAKE_SPEED -> -accelRelease
                else -> 0f
            }

            if (acceleration == 0f) {
                val dt = min(remaining, SIM_STEP_S)
                advanceBar(bar, 0f, dt)
                fish.x += fish.v * dt
                remaining -= dt
                continue
            }

            var dt = min(remaining, abs(relativeVelocity) / abs(acceleration))
            if (dt < 0.000001f) dt = min(remaining, SIM_STEP_S)
            advanceBar(bar, acceleration, dt)
            fish.x += fish.v * dt
            remaining -= dt
        }
    }

    private fun adaptAccel(current: Float, observed: Float): Float {
        val clamped = observed.coerceIn(MIN_ACCEL, MAX_ACCEL)
        return current + (clamped - current) * ACCEL_ADAPT
    }
}
