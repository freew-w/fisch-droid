package io.github.freew_w.fischdroid

import io.github.freew_w.fischdroid.minigame.ControlLoop
import io.github.freew_w.fischdroid.minigame.Detector
import io.github.freew_w.fischdroid.minigame.Detector.Bar
import io.github.freew_w.fischdroid.minigame.Detector.Fish
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class ControlLoopTest {
    private companion object {
        const val ACCEL_HOLD = 1150f
        const val ACCEL_RELEASE = 1000f
        const val STEP_MS = 1L
        const val FRAME_MS = 16L

        /** Continued-stroke segment; a release takes effect at the next segment boundary. */
        const val SEGMENT_MS = 20L
        const val RESTITUTION = 0.3f

        val BAR_CENTER_MIN = Detector.BAR_CENTER_MIN
        val BAR_CENTER_MAX = Detector.BAR_CENTER_MAX
        const val BAR_HALF_WIDTH = 171

        const val SETTLE_MS = 3000L
    }

    /** Simulated bar. Holds start immediately but can only be released at a segment boundary. */
    private class Plant(start: Float, startVelocity: Float = 0f) {
        var position = start
        var velocity = startVelocity
        private var holding = false
        private var nextBoundaryMs = 0L

        fun step(nowMs: Long, desiredHold: Boolean) {
            if (holding) {
                if (nowMs >= nextBoundaryMs) {
                    if (desiredHold) nextBoundaryMs += SEGMENT_MS else holding = false
                }
            } else if (desiredHold) {
                holding = true
                nextBoundaryMs = nowMs + SEGMENT_MS
            }

            val acceleration = if (holding) ACCEL_HOLD else -ACCEL_RELEASE
            velocity += acceleration * (STEP_MS / 1000f)
            position += velocity * (STEP_MS / 1000f)

            if (position < BAR_CENTER_MIN) {
                position = 2f * BAR_CENTER_MIN - position
                velocity = -velocity * RESTITUTION
            } else if (position > BAR_CENTER_MAX) {
                position = 2f * BAR_CENTER_MAX - position
                velocity = -velocity * RESTITUTION
            }
        }
    }

    private class Run(val errors: List<Float>) {
        val meanError: Float get() = errors.average().toFloat()
        val overlapFraction: Float
            get() = errors.count { it <= BAR_HALF_WIDTH }.toFloat() / errors.size
    }

    private fun simulate(
        durationMs: Long,
        start: Float = 800f,
        startVelocity: Float = 0f,
        noisePx: Float = 0f,
        fishAt: (Double) -> Float,
    ): Run {
        ControlLoop.reset()
        val plant = Plant(start, startVelocity)
        val random = Random(7)
        var desiredHold = false
        val errors = mutableListOf<Float>()

        for (nowMs in 0 until durationMs step STEP_MS) {
            if (nowMs % FRAME_MS == 0L) {
                val fishX = fishAt(nowMs / 1000.0)
                val noise = if (noisePx > 0f) (random.nextFloat() * 2f - 1f) * noisePx else 0f
                val measuredBar = (plant.position + noise).roundToInt()

                desiredHold = ControlLoop.decide(
                    Fish(fishX.roundToInt()),
                    Bar(measuredBar - BAR_HALF_WIDTH, measuredBar + BAR_HALF_WIDTH),
                    nowMs,
                ) ?: false
            }

            plant.step(nowMs, desiredHold)

            if (nowMs >= SETTLE_MS && nowMs % FRAME_MS == 0L) {
                errors += abs(plant.position - fishAt(nowMs / 1000.0))
            }
        }
        return Run(errors)
    }

    @Test
    fun `converges to a stationary line`() {
        val run = simulate(10_000L) { 1000.0f }
        assertTrue("mean error ${run.meanError}", run.meanError < 40f)
        assertTrue("overlap ${run.overlapFraction}", run.overlapFraction > 0.95f)
    }

    @Test
    fun `tracks a slowly moving line`() {
        val run = simulate(14_000L) { t -> 950f + 150f * sin(2.0 * PI * t / 3.0).toFloat() }
        assertTrue("mean error ${run.meanError}", run.meanError < 90f)
        assertTrue("overlap ${run.overlapFraction}", run.overlapFraction > 0.90f)
    }

    @Test
    fun `tolerates detection noise`() {
        val run = simulate(12_000L, noisePx = 2f) { 950.0f }
        assertTrue("mean error ${run.meanError}", run.meanError < 60f)
        assertTrue("overlap ${run.overlapFraction}", run.overlapFraction > 0.90f)
    }

    @Test
    fun `recovers after bouncing off the track end`() {
        val run = simulate(10_000L, start = BAR_CENTER_MAX, startVelocity = 1200f) { 900.0f }
        assertTrue("mean error ${run.meanError}", run.meanError < 80f)
        assertTrue("overlap ${run.overlapFraction}", run.overlapFraction > 0.90f)
    }
}
