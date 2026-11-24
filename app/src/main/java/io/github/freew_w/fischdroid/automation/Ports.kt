package io.github.freew_w.fischdroid.automation

import android.os.SystemClock
import io.github.freew_w.fischdroid.detection.Bar
import io.github.freew_w.fischdroid.detection.Fish
import io.github.freew_w.fischdroid.detection.ShakeButton

/**
 * Ports used by [FishingPipeline]. The pipeline never reaches for `TapService`, an overlay
 * singleton or the system clock directly, which keeps the state machine testable and lets another
 * input/overlay implementation be plugged in later.
 */

/**
 * One tap in a [GestureSink.pressSequence] burst. [delayAfterMs] is the pause between this tap
 * finishing and the next one starting.
 */
data class PressStep(val x: Int, val y: Int, val durationMs: Long, val delayAfterMs: Long)

/** The only way the pipeline can inject touches. Implemented by `TapService`. */
interface GestureSink {
    fun press(x: Int, y: Int, durationMs: Long)

    /**
     * Dispatches [steps] strictly one after another: each tap starts only after the previous one
     * completed plus its [PressStep.delayAfterMs]. Used for the hotbar burst that precedes a cast.
     */
    fun pressSequence(steps: List<PressStep>)

    fun setHolding(hold: Boolean, x: Int, y: Int)
}

/** The pipeline's view of the debug overlay. Implementations decide which thread to draw on. */
interface DetectionHudSink {
    fun showMinigame(fish: Fish?, bar: Bar?)

    fun showShake(button: ShakeButton?)

    fun clear()
}

/** Injectable time source so the state machine can be tested without a device clock. */
fun interface Clock {
    fun nowMs(): Long
}

/** Production clock backed by the monotonic uptime clock used by the rest of the pipeline. */
object RealClock : Clock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
