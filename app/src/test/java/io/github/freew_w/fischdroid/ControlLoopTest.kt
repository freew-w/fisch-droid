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

        /** Gesture dispatch delay in the simulated plant; the controller does not model it. */
        const val PHYSICAL_LATENCY_MS = 30L
        const val RESTITUTION = 0.3f

        val BAR_CENTER_MIN = Detector.BAR_CENTER_MIN
        val BAR_CENTER_MAX = Detector.BAR_CENTER_MAX
        const val BAR_HALF_WIDTH = 171

        const val SETTLE_MS = 3000L
    }

    private class Plant(start: Float) {
        var position = start
        var velocity = 0f
        private val presses = ArrayDeque<LongArray>()

        fun press(nowMs: Long, durationMs: Long) {
            presses.addLast(longArrayOf(nowMs + PHYSICAL_LATENCY_MS, nowMs + PHYSICAL_LATENCY_MS + durationMs))
        }

        fun step(nowMs: Long) {
            while (presses.isNotEmpty() && presses.first()[1] <= nowMs) {
                presses.removeFirst()
            }
            val holding = presses.isNotEmpty() && nowMs >= presses.first()[0]
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
        noisePx: Float = 0f,
        fishAt: (Double) -> Float,
    ): Run {
        ControlLoop.reset()
        val plant = Plant(start)
        val random = Random(7)
        var nextDecisionMs = 0L
        val errors = mutableListOf<Float>()

        for (nowMs in 0 until durationMs step STEP_MS) {
            plant.step(nowMs)

            if (nowMs % FRAME_MS == 0L) {
                val fishX = fishAt(nowMs / 1000.0)
                val noise = if (noisePx > 0f) (random.nextFloat() * 2f - 1f) * noisePx else 0f
                val measuredBar = (plant.position + noise).roundToInt()

                if (nowMs >= nextDecisionMs) {
                    val action = ControlLoop.decide(
                        Fish(fishX.roundToInt()),
                        Bar(measuredBar - BAR_HALF_WIDTH, measuredBar + BAR_HALF_WIDTH),
                        nowMs,
                    )
                    if (action != null) {
                        if (action.isHold) plant.press(nowMs, action.durationMs)
                        nextDecisionMs = nowMs + action.durationMs
                    }
                }
                if (nowMs >= SETTLE_MS) errors += abs(plant.position - fishX)
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
        ControlLoop.reset()
        val plant = Plant(BAR_CENTER_MAX)
        plant.velocity = 1200f
        val errors = mutableListOf<Float>()
        var nextDecisionMs = 0L

        for (nowMs in 0 until 10_000L) {
            plant.step(nowMs)
            if (nowMs % FRAME_MS == 0L) {
                val measuredBar = plant.position.roundToInt()
                if (nowMs >= nextDecisionMs) {
                    val action = ControlLoop.decide(
                        Fish(900),
                        Bar(measuredBar - BAR_HALF_WIDTH, measuredBar + BAR_HALF_WIDTH),
                        nowMs,
                    )
                    if (action != null) {
                        if (action.isHold) plant.press(nowMs, action.durationMs)
                        nextDecisionMs = nowMs + action.durationMs
                    }
                }
                if (nowMs >= SETTLE_MS) errors += abs(plant.position - 900f)
            }
        }

        val run = Run(errors)
        assertTrue("mean error ${run.meanError}", run.meanError < 80f)
        assertTrue("overlap ${run.overlapFraction}", run.overlapFraction > 0.90f)
    }
}
