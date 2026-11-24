package io.github.freew_w.fischdroid.automation

import android.graphics.Bitmap
import io.github.freew_w.fischdroid.config.HotbarSlots
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
        hotbar = HotbarSlots(rodX = ROD_X, rodY = SLOT_Y, bestiaryX = BESTIARY_X, bestiaryY = SLOT_Y),
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
    fun firstSearchFrameBurstsThenCasts() {
        pipeline.start()

        pipeline.step(frame(), settings, nowMs = 2_000L)

        assertEquals(GameState.SEARCHING, pipeline.currentState)
        assertEquals(
            listOf(
                PressStep(BESTIARY_X, SLOT_Y, 30L, 250L),
                PressStep(BESTIARY_X, SLOT_Y, 30L, 250L),
                PressStep(ROD_X, SLOT_Y, 30L, 250L),
                PressStep(CENTRE_X, CENTRE_Y, 500L, 0L),
            ),
            gestures.sequences.single(),
        )
        assertTrue("the burst carries the cast itself", gestures.presses.isEmpty())
    }

    @Test
    fun repeatCastsArePlainCentrePresses() {
        pipeline.start()
        pipeline.step(frame(), settings, nowMs = 2_000L)

        pipeline.step(frame(), settings, nowMs = 2_500L)
        assertTrue("no second burst while waiting", gestures.presses.isEmpty())

        // The burst occupies the injector until 2000 + 1340 ms; a press inside that window would
        // cancel the cast stroke, so the repeat cast waits a full cadence after the burst ends.
        pipeline.step(frame(), settings, nowMs = 3_000L)
        assertTrue("no press inside the burst", gestures.presses.isEmpty())

        pipeline.step(frame(), settings, nowMs = 4_400L)
        assertEquals(listOf(Triple(CENTRE_X, CENTRE_Y, 500L)), gestures.presses)
        assertEquals(1, gestures.sequences.size)
    }

    @Test
    fun shakeTapsWaitForTheBurstToFinish() {
        val button = ShakeButton(centerX = 500, centerY = 600, radius = 40, score = 1.0)
        pipeline.start()
        pipeline.step(frame(), settings, nowMs = 2_000L)

        pipeline.step(frame(shakeButton = button), settings, nowMs = 2_400L)
        assertEquals(GameState.SHAKING, pipeline.currentState)
        assertTrue("a shake tap must not cut into the burst's cast stroke", gestures.presses.isEmpty())

        pipeline.step(frame(shakeButton = button), settings, nowMs = 3_400L)
        assertEquals(1, gestures.presses.size)
    }

    @Test
    fun shakeButtonTapsAndSuppressesCasting() {
        val button = ShakeButton(centerX = 500, centerY = 600, radius = 40, score = 1.0)
        pipeline.start()

        pipeline.step(frame(shakeButton = button), settings, 2_000L)
        assertEquals(GameState.SHAKING, pipeline.currentState)
        assertEquals(1, gestures.presses.size)
        assertEquals(500, gestures.presses[0].first)
        assertEquals(40L, gestures.presses[0].third)

        pipeline.step(frame(shakeButton = button), settings, 2_050L)
        assertEquals("taps are throttled to the 100 ms scan interval", 1, gestures.presses.size)

        pipeline.step(frame(shakeButton = button), settings, 2_100L)
        assertEquals(2, gestures.presses.size)

        pipeline.step(frame(), settings, 2_200L)
        assertEquals(
            "no cast immediately after the shake button disappears",
            2,
            gestures.presses.size
        )

        pipeline.step(frame(), settings, 3_200L)
        assertEquals(
            "the first cast still opens the hotbar burst",
            listOf(
                PressStep(BESTIARY_X, SLOT_Y, 30L, 250L),
                PressStep(BESTIARY_X, SLOT_Y, 30L, 250L),
                PressStep(ROD_X, SLOT_Y, 30L, 250L),
                PressStep(CENTRE_X, CENTRE_Y, 500L, 0L),
            ),
            gestures.sequences.single(),
        )
    }

    @Test
    fun minigameHoldsAtTheFrameCentre() {
        pipeline.start()

        fishFrame()
        pipeline.step(frame(shakeButton = null), settings, 2_100L)

        assertEquals(GameState.SEARCHING, pipeline.currentState)
        assertEquals(Pair(CENTRE_X, CENTRE_Y), gestures.holdPoints.last())
    }

    @Test
    fun minigameDrivesTheControllerAndHold() {
        pipeline.start()

        fishFrame()

        assertEquals(GameState.MINIGAME, pipeline.currentState)
        assertEquals(true, gestures.holds.last())
        assertEquals(Pair(CENTRE_X, CENTRE_Y), gestures.holdPoints.last())
        assertEquals(Fish(300), hud.fish)
        assertEquals(Bar(100, 100), hud.bar)
    }

    @Test
    fun leavingMinigameRearmsTheHotbarBurst() {
        pipeline.start()
        fishFrame()

        pipeline.step(frame(), settings, 2_100L)

        assertEquals(GameState.SEARCHING, pipeline.currentState)
        assertEquals(false, gestures.holds.last())

        pipeline.step(frame(), settings, 3_200L)
        assertEquals(
            "the closing screen swallows taps, so the burst waits for it",
            0,
            gestures.sequences.size
        )

        pipeline.step(frame(), settings, 5_200L)

        assertEquals(
            "leaving the minigame arms the burst for the next cast",
            1,
            gestures.sequences.size
        )
        assertEquals(PressStep(CENTRE_X, CENTRE_Y, 500L, 0L), gestures.sequences.single().last())
    }

    @Test
    fun leavingMinigameReleasesTheHoldAndClearsDetections() {
        pipeline.start()
        fishFrame()

        pipeline.step(frame(), settings, 2_100L)

        assertEquals(GameState.SEARCHING, pipeline.currentState)
        assertEquals(false, gestures.holds.last())
        assertNull(hud.fish)
        assertNull(hud.bar)
    }

    @Test
    fun stopReleasesTheHoldAndClearsTheHud() {
        pipeline.start()
        fishFrame()
        assertTrue(gestures.holds.last())

        pipeline.stop()

        assertEquals(GameState.IDLE, pipeline.currentState)
        assertEquals(false, gestures.holds.last())
        assertTrue(hud.clearCount > 0)
        assertNull(hud.fish)
        assertNull(hud.bar)
    }

    /** Drives three minigame frames so [BarController] finishes its warm-up and holds. */
    private fun fishFrame() {
        pipeline.step(
            FrameObservation(
                inMinigame = true,
                frameWidth = FRAME_WIDTH,
                frameHeight = FRAME_HEIGHT,
                fish = Fish(100),
                bar = Bar(100, 100),
            ),
            settings,
            2_000L
        )
        pipeline.step(
            FrameObservation(
                inMinigame = true,
                frameWidth = FRAME_WIDTH,
                frameHeight = FRAME_HEIGHT,
                fish = Fish(300),
                bar = Bar(100, 100),
            ),
            settings,
            2_016L
        )
        pipeline.step(
            FrameObservation(
                inMinigame = true,
                frameWidth = FRAME_WIDTH,
                frameHeight = FRAME_HEIGHT,
                fish = Fish(300),
                bar = Bar(100, 100),
            ),
            settings,
            2_032L
        )
    }

    private fun frame(
        shakeButton: ShakeButton? = null,
        fish: Fish? = null,
        bar: Bar? = null,
    ) = FrameObservation(
        inMinigame = false,
        frameWidth = FRAME_WIDTH,
        frameHeight = FRAME_HEIGHT,
        fish = fish,
        bar = bar,
        shakeButton = shakeButton,
    )

    private class FakeGestures : GestureSink {
        val presses = mutableListOf<Triple<Int, Int, Long>>()
        val sequences = mutableListOf<List<PressStep>>()
        val holds = mutableListOf<Boolean>()
        val holdPoints = mutableListOf<Pair<Int, Int>>()

        override fun press(x: Int, y: Int, durationMs: Long) {
            presses += Triple(x, y, durationMs)
        }

        override fun pressSequence(steps: List<PressStep>) {
            sequences += steps
        }

        override fun setHolding(hold: Boolean, x: Int, y: Int) {
            holds += hold
            holdPoints += Pair(x, y)
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

    private companion object {
        const val FRAME_WIDTH = 1920
        const val FRAME_HEIGHT = 1080
        const val CENTRE_X = FRAME_WIDTH / 2
        const val CENTRE_Y = FRAME_HEIGHT / 2
        const val ROD_X = 1700
        const val BESTIARY_X = 1500
        const val SLOT_Y = 1000
    }
}
