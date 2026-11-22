package io.github.freew_w.fischdroid.automation

import android.graphics.Bitmap
import io.github.freew_w.fischdroid.config.Settings
import io.github.freew_w.fischdroid.control.BarController
import io.github.freew_w.fischdroid.detection.CoinsAnalyzer
import io.github.freew_w.fischdroid.detection.DetectionSpec
import io.github.freew_w.fischdroid.detection.FishBarAnalyzer
import io.github.freew_w.fischdroid.detection.ShakeAnalyzer
import io.github.freew_w.fischdroid.detection.ShakeButton

/**
 * Per-frame state machine for the whole automation loop.
 *
 * The pipeline owns all session state (game state, controller history, scan throttles) and talks
 * to the outside world only through [GestureSink] and [DetectionHudSink]. Frame capture and
 * detection happen on the caller's worker thread; the sinks marshal to whatever thread they need.
 *
 * To add a new mode, add a [GameState] and a branch in [step]; detectors and gestures stay
 * unchanged. To test the decision logic without Android, call [step] with a [FrameObservation].
 */
class FishingPipeline(
    private val minigameDetector: FishBarAnalyzer,
    private val coinsDetector: CoinsAnalyzer,
    private val shakeDetector: ShakeAnalyzer,
    private val controller: BarController,
    private val gestures: GestureSink,
    private val hud: DetectionHudSink,
    private val settings: () -> Settings,
    private val clock: Clock = RealClock,
) {
    private companion object {
        const val CAST_AFTER_MS = 1000L
        const val CAST_DURATION_MS = 500L
        const val SHAKE_SCAN_INTERVAL_MS = 100L
        const val SHAKE_TAP_INTERVAL_MS = 100L
        const val SHAKE_TAP_DURATION_MS = 40L
    }

    @Volatile
    private var running = false

    @Volatile
    private var state = GameState.IDLE
    private var lastInteractionMs = 0L

    @Volatile
    private var lastShakeScanMs = 0L
    private var lastShakeTapMs = 0L

    @Volatile
    private var cachedShakeButton: ShakeButton? = null

    val isRunning: Boolean
        get() = running

    val currentState: GameState
        get() = state

    @Synchronized
    fun start() {
        if (running) return
        running = true
        controller.reset()
        minigameDetector.reset()
        lastInteractionMs = 0L
        lastShakeScanMs = 0L
        lastShakeTapMs = 0L
        cachedShakeButton = null
        state = GameState.SEARCHING
    }

    @Synchronized
    fun stop() {
        running = false
        enterIdle()
    }

    @Synchronized
    fun toggle(): Boolean {
        if (running) stop() else start()
        return running
    }

    /**
     * Detects the current frame and feeds it to [step]. Safe to call before [start]; it simply
     * keeps the hold released and the overlay clear.
     */
    fun processFrame(bitmap: Bitmap, frameWidth: Int, frameHeight: Int) {
        if (!running) {
            synchronized(this) { if (!running) enterIdle() }
            return
        }
        if (frameHeight > frameWidth) {
            synchronized(this) { if (running) enterIdle() }
            return
        }

        val snapshot = settings()
        val now = clock.nowMs()

        // "C$" visible means the HUD is showing, which means the fishing bar is NOT on screen.
        val inMinigame = !coinsDetector.isVisible(bitmap, frameWidth, frameHeight)
        val observation = if (inMinigame) {
            cachedShakeButton = null
            val (fish, bar) = minigameDetector.detect(
                bitmap,
                DetectionSpec(snapshot.track, snapshot.fishColor),
            )
            FrameObservation(inMinigame = true, fish = fish, bar = bar)
        } else {
            if (now - lastShakeScanMs >= SHAKE_SCAN_INTERVAL_MS) {
                lastShakeScanMs = now
                cachedShakeButton = shakeDetector.find(bitmap, frameWidth, frameHeight)
            }
            FrameObservation(inMinigame = false, shakeButton = cachedShakeButton)
        }

        step(observation, snapshot, now)
    }

    /**
     * Advances the state machine from an already-classified frame. This is the seam tests use and
     * the place to add another frame source later.
     */
    @Synchronized
    fun step(observation: FrameObservation, snapshot: Settings, nowMs: Long) {
        if (!running) {
            enterIdle()
            return
        }

        if (observation.inMinigame) {
            enterMinigame(observation, snapshot, nowMs)
            return
        }

        if (state == GameState.MINIGAME) {
            leaveMinigame(snapshot)
        }

        val button = observation.shakeButton
        if (button != null) {
            state = GameState.SHAKING
            // Preserve the old cast cadence: a shake detection also delays the next cast.
            lastInteractionMs = nowMs
            hud.showShake(button)
            if (nowMs - lastShakeTapMs >= SHAKE_TAP_INTERVAL_MS) {
                lastShakeTapMs = nowMs
                gestures.press(
                    button.centerX.toFloat(),
                    button.centerY.toFloat(),
                    SHAKE_TAP_DURATION_MS,
                )
            }
            return
        }

        state = GameState.SEARCHING
        hud.showShake(null)
        if (nowMs - lastInteractionMs >= CAST_AFTER_MS) {
            lastInteractionMs = nowMs
            gestures.press(snapshot.cast.x, snapshot.cast.y, CAST_DURATION_MS)
        }
    }

    private fun enterMinigame(observation: FrameObservation, snapshot: Settings, nowMs: Long) {
        if (state != GameState.MINIGAME) {
            state = GameState.MINIGAME
            controller.reset()
            minigameDetector.reset()
            cachedShakeButton = null
            lastShakeScanMs = 0L
        }

        hud.showShake(null)
        hud.showMinigame(observation.fish, observation.bar)

        val fish = observation.fish
        val bar = observation.bar
        val hold = if (fish != null && bar != null) {
            lastInteractionMs = nowMs
            controller.decide(fish, bar, nowMs, snapshot.control)
        } else {
            null
        }
        gestures.setHolding(hold == true, snapshot.cast.x, snapshot.cast.y)
    }

    private fun leaveMinigame(snapshot: Settings) {
        controller.reset()
        minigameDetector.reset()
        cachedShakeButton = null
        lastShakeScanMs = 0L
        hud.showMinigame(null, null)
        gestures.setHolding(false, snapshot.cast.x, snapshot.cast.y)
        state = GameState.SEARCHING
    }

    private fun enterIdle() {
        if (state != GameState.IDLE) {
            controller.reset()
            minigameDetector.reset()
            cachedShakeButton = null
            lastShakeScanMs = 0L
            state = GameState.IDLE
            hud.clear()
        }
        // A released hold needs no coordinates, but keeping one argument path avoids a second API.
        gestures.setHolding(false, 0f, 0f)
    }
}
