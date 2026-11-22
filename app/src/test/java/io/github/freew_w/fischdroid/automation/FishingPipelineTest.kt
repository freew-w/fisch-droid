package io.github.freew_w.fischdroid.automation

import android.graphics.Bitmap
import io.github.freew_w.fischdroid.config.CastTarget
import io.github.freew_w.fischdroid.config.Settings
import io.github.freew_w.fischdroid.control.BarController
import io.github.freew_w.fischdroid.control.ControlTuning
import io.github.freew_w.fischdroid.detection.Bar
import io.github.freew_w.fischdroid.detection.CoinsAnalyzer
import io.github.freew_w.fischdroid.detection.DetectionSpec
import io.github.freew_w.fischdroid.detection.Fish
import io.github.freew_w.fischdroid.detection.FishBarAnalyzer
import io.github.freew_w.fischdroid.detection.ShakeAnalyzer
import io.github.freew_w.fischdroid.detection.ShakeButton
import io.github.freew_w.fischdroid.detection.TrackGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingPipelineTest {
    private val settings = Settings(
        track = TrackGeometry(x = 0, y = 0, width = 1000, height = 100, rodControl = 0f),
        control = ControlTuning(positionGain = 4f, velocityGain = 3.2f, switchBand = 50f),
        cast = CastTarget(x = 10f, y = 20f),
        fishColor = 0,
    )

    private val gestures = FakeGestures()
    private val hud = FakeHud()
    private val pipeline = FishingPipeline(
        minigameDetector = UnusedMinigameDetector(),
        coinsDetector = UnusedCoinsDetector(),
        shakeDetector = UnusedShakeDetector(),
        controller = BarController(),
        gestures = gestures,
        hud = hud,
        settings = { settings },
        clock = { 0L },
    )

    @Test
    fun firstSearchFrameCasts() {
        pipeline.start()

        pipeline.step(FrameObservation(inMinigame = false), settings, nowMs = 2_000L)

        assertEquals(GameState.SEARCHING, pipeline.currentState)
        assertEquals(listOf(Triple(10f, 20f, 500L)), gestures.presses)
    }

    @Test
    fun castingIsThrottled() {
        pipeline.start()
        pipeline.step(FrameObservation(inMinigame = false), settings, nowMs = 2_000L)

        pipeline.step(FrameObservation(inMinigame = false), settings, nowMs = 2_500L)
        assertEquals(1, gestures.presses.size)

        pipeline.step(FrameObservation(inMinigame = false), settings, nowMs = 3_100L)
        assertEquals(2, gestures.presses.size)
    }

    @Test
    fun shakeButtonTapsAndSuppressesCasting() {
        val button = ShakeButton(centerX = 500, centerY = 600, radius = 40, score = 1.0)
        pipeline.start()

        pipeline.step(FrameObservation(inMinigame = false, shakeButton = button), settings, 2_000L)
        assertEquals(GameState.SHAKING, pipeline.currentState)
        assertEquals(1, gestures.presses.size)
        assertEquals(500f, gestures.presses[0].first)
        assertEquals(40L, gestures.presses[0].third)

        pipeline.step(FrameObservation(inMinigame = false, shakeButton = button), settings, 2_050L)
        assertEquals("taps are throttled to the 100 ms scan interval", 1, gestures.presses.size)

        pipeline.step(FrameObservation(inMinigame = false, shakeButton = button), settings, 2_100L)
        assertEquals(2, gestures.presses.size)

        pipeline.step(FrameObservation(inMinigame = false), settings, 2_200L)
        assertEquals(
            "no cast immediately after the shake button disappears",
            2,
            gestures.presses.size
        )

        pipeline.step(FrameObservation(inMinigame = false), settings, 3_200L)
        assertEquals(3, gestures.presses.size)
        assertEquals(10f, gestures.presses[2].first)
    }

    @Test
    fun minigameDrivesTheControllerAndHold() {
        pipeline.start()

        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(100), bar = Bar(100, 100)),
            settings,
            2_000L
        )
        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(300), bar = Bar(100, 100)),
            settings,
            2_016L
        )
        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(300), bar = Bar(100, 100)),
            settings,
            2_032L
        )

        assertEquals(GameState.MINIGAME, pipeline.currentState)
        assertEquals(true, gestures.holds.last())
        assertEquals(Fish(300), hud.fish)
        assertEquals(Bar(100, 100), hud.bar)
    }

    @Test
    fun leavingMinigameReleasesTheHoldAndClearsDetections() {
        pipeline.start()
        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(100), bar = Bar(100, 100)),
            settings,
            2_000L
        )
        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(300), bar = Bar(100, 100)),
            settings,
            2_016L
        )
        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(300), bar = Bar(100, 100)),
            settings,
            2_032L
        )

        pipeline.step(FrameObservation(inMinigame = false), settings, 2_100L)

        assertEquals(GameState.SEARCHING, pipeline.currentState)
        assertEquals(false, gestures.holds.last())
        assertNull(hud.fish)
        assertNull(hud.bar)
    }

    @Test
    fun stopReleasesTheHoldAndClearsTheHud() {
        pipeline.start()
        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(100), bar = Bar(100, 100)),
            settings,
            2_000L
        )
        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(300), bar = Bar(100, 100)),
            settings,
            2_016L
        )
        pipeline.step(
            FrameObservation(inMinigame = true, fish = Fish(300), bar = Bar(100, 100)),
            settings,
            2_032L
        )
        assertTrue(gestures.holds.last())

        pipeline.stop()

        assertEquals(GameState.IDLE, pipeline.currentState)
        assertEquals(false, gestures.holds.last())
        assertTrue(hud.clearCount > 0)
        assertNull(hud.fish)
        assertNull(hud.bar)
    }

    private class FakeGestures : GestureSink {
        val presses = mutableListOf<Triple<Float, Float, Long>>()
        val holds = mutableListOf<Boolean>()

        override fun press(x: Float, y: Float, durationMs: Long) {
            presses += Triple(x, y, durationMs)
        }

        override fun setHolding(hold: Boolean, x: Float, y: Float) {
            holds += hold
        }
    }

    private class FakeHud : DetectionHudSink {
        var fish: Fish? = null
        var bar: Bar? = null
        var shake: ShakeButton? = null
        var clearCount = 0

        override fun showMinigame(fish: Fish?, bar: Bar?) {
            this.fish = fish
            this.bar = bar
        }

        override fun showShake(button: ShakeButton?) {
            shake = button
        }

        override fun clear() {
            clearCount++
            fish = null
            bar = null
            shake = null
        }
    }

    private class UnusedMinigameDetector : FishBarAnalyzer {
        override fun detect(bitmap: Bitmap, spec: DetectionSpec): Pair<Fish?, Bar?> =
            error("step tests do not detect frames")

        override fun reset() = Unit
    }

    private class UnusedCoinsDetector : CoinsAnalyzer {
        override fun isVisible(bitmap: Bitmap, width: Int, height: Int): Boolean =
            error("step tests do not detect frames")
    }

    private class UnusedShakeDetector : ShakeAnalyzer {
        override fun find(bitmap: Bitmap, width: Int, height: Int): ShakeButton? =
            error("step tests do not detect frames")
    }
}
