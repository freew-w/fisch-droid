package io.github.freew_w.fischdroid

import android.graphics.Bitmap
import io.github.freew_w.fischdroid.automation.DetectionHudSink
import io.github.freew_w.fischdroid.automation.FishingPipeline
import io.github.freew_w.fischdroid.automation.GestureSink
import io.github.freew_w.fischdroid.config.Config
import io.github.freew_w.fischdroid.config.Settings
import io.github.freew_w.fischdroid.control.BarController
import io.github.freew_w.fischdroid.detection.CoinsDetector
import io.github.freew_w.fischdroid.detection.MinigameDetector
import io.github.freew_w.fischdroid.detection.ShakeDetector

/**
 * Process-wide composition root for automation.
 *
 * `TapService` attaches the accessibility-backed gesture/overlay adapters when it connects, and
 * `ScreenCaptureService` feeds frames in. Everything below this object is constructor-injected and
 * testable; only the Android service boundary uses this singleton.
 */
object Orchestrator {
    @Volatile
    private var pipeline: FishingPipeline? = null

    @Synchronized
    fun attach(
        gestures: GestureSink,
        hud: DetectionHudSink,
        settings: () -> Settings = Config::snapshot,
    ) {
        pipeline?.stop()
        pipeline = FishingPipeline(
            minigameDetector = MinigameDetector(),
            coinsDetector = CoinsDetector(),
            shakeDetector = ShakeDetector(),
            controller = BarController(),
            gestures = gestures,
            hud = hud,
            settings = settings,
        )
    }

    @Synchronized
    fun detach() {
        pipeline?.stop()
        pipeline = null
    }

    /**
     * No-op until [attach] runs. Capture is gated on the accessibility service, so this is only a
     * defensive guard for the window between capture start and the accessibility service attaching.
     */
    fun processFrame(bitmap: Bitmap, frameWidth: Int, frameHeight: Int) {
        pipeline?.processFrame(bitmap, frameWidth, frameHeight)
    }

    val isRunning: Boolean
        get() = pipeline?.isRunning == true

    /** Toggles automation and returns the new running state. */
    fun toggle(): Boolean {
        val current = pipeline ?: return false
        current.toggle()
        return current.isRunning
    }
}
