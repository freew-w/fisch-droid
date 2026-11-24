package io.github.freew_w.fischdroid.automation

import android.graphics.Bitmap
import io.github.freew_w.fischdroid.config.HotbarSlots
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
        const val HOTBAR_TAP_DURATION_MS = 30L

        /**
         * Interval between the burst's taps, from the start of one to the start of the next. The
         * cast follows the rod by the same interval, so the whole burst shares one cadence.
         */
        const val HOTBAR_TAP_GAP_MS = 250L

        /**
         * Wall-clock span the burst occupies the touch injector: three taps with their gaps, then
         * the cast stroke, which only *starts* once those taps are done.
         */
        const val HOTBAR_BURST_MS =
            3 * (HOTBAR_TAP_DURATION_MS + HOTBAR_TAP_GAP_MS) + CAST_DURATION_MS
        const val SHAKE_SCAN_INTERVAL_MS = 100L
        const val SHAKE_TAP_INTERVAL_MS = 100L
        const val SHAKE_TAP_DURATION_MS = 40L

        /**
         * Grace period after a minigame ends (caught or lost) before the next cast. The minigame's
         * closing animation still owns the screen for a moment, and hotbar taps during it are
         * swallowed by the game, which made the burst run against a dead screen and lose the cast.
         */
        const val POST_MINIGAME_DELAY_MS = 1500L
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

    /**
     * Whether the next cast should be preceded by the Bestiary, Bestiary, rod hotbar burst. Armed
     * when the macro starts and after every minigame; the repeat cast presses in between are plain
     * presses.
     */
    @Volatile
    private var hotbarBurstPending = false

    /**
     * Clock value before which nothing may dispatch a gesture. Two things own the touch injector:
     * a running burst (its cast stroke is the last step, so a press scheduled from the burst's
     * start would land inside that stroke and the system cancels the still-running stroke — the
     * game then sees a short tap followed by a second, long press), and the closing screen of a
     * minigame, which swallows hotbar taps for a moment.
     */
    @Volatile
    private var injectorBusyUntilMs = 0L

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
        hotbarBurstPending = true
        injectorBusyUntilMs = 0L
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
            FrameObservation(
                inMinigame = true,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                fish = fish,
                bar = bar,
            )
        } else {
            if (now - lastShakeScanMs >= SHAKE_SCAN_INTERVAL_MS) {
                lastShakeScanMs = now
                cachedShakeButton = shakeDetector.find(bitmap, frameWidth, frameHeight)
            }
            FrameObservation(
                inMinigame = false,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                shakeButton = cachedShakeButton,
            )
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
            leaveMinigame(observation, nowMs)
        }

        val button = observation.shakeButton
        val injectorBusy = nowMs < injectorBusyUntilMs
        if (button != null) {
            state = GameState.SHAKING
            // Preserve the old cast cadence: a shake detection also delays the next cast.
            lastInteractionMs = maxOf(lastInteractionMs, nowMs)
            hud.showShake(button)
            if (!injectorBusy && nowMs - lastShakeTapMs >= SHAKE_TAP_INTERVAL_MS) {
                lastShakeTapMs = nowMs
                gestures.press(
                    button.centerX,
                    button.centerY,
                    SHAKE_TAP_DURATION_MS,
                )
            }
            return
        }

        state = GameState.SEARCHING
        hud.showShake(null)
        if (!injectorBusy && nowMs - lastInteractionMs >= CAST_AFTER_MS) {
            lastInteractionMs = nowMs
            cast(observation, snapshot.hotbar, nowMs)
        }
    }

    /**
     * Casts from the frame centre. The first cast of a cycle (macro start, or the first after a
     * minigame) is preceded by the mandatory hotbar burst: Bestiary, Bestiary, rod, then the cast
     * itself, so opening and closing the Bestiary panel dismisses any popup that swallowed the cast
     * tap. The Bestiary panel draws no text that [ShakeDetector] mistakes for the shake button,
     * which the equipment bag's UI did.
     */
    private fun cast(observation: FrameObservation, hotbar: HotbarSlots, nowMs: Long) {
        val castX = observation.frameWidth / 2
        val castY = observation.frameHeight / 2

        if (!hotbarBurstPending) {
            gestures.press(castX, castY, CAST_DURATION_MS)
            return
        }

        hotbarBurstPending = false
        // Keep the injector free until the burst's own cast stroke is over, and restart the cast
        // cadence from that point instead of from the moment the burst was triggered.
        injectorBusyUntilMs = nowMs + HOTBAR_BURST_MS
        lastInteractionMs = injectorBusyUntilMs
        val tap = HOTBAR_TAP_DURATION_MS
        val gap = HOTBAR_TAP_GAP_MS
        gestures.pressSequence(
            listOf(
                PressStep(hotbar.bestiaryX, hotbar.bestiaryY, tap, gap),
                PressStep(hotbar.bestiaryX, hotbar.bestiaryY, tap, gap),
                PressStep(hotbar.rodX, hotbar.rodY, tap, gap),
                PressStep(castX, castY, CAST_DURATION_MS, 0L),
            )
        )
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

        val castX = observation.frameWidth / 2
        val castY = observation.frameHeight / 2
        val fish = observation.fish
        val bar = observation.bar
        val hold = if (fish != null && bar != null) {
            lastInteractionMs = nowMs
            controller.decide(fish, bar, nowMs, snapshot.control)
        } else {
            null
        }
        gestures.setHolding(hold == true, castX, castY)
    }

    private fun leaveMinigame(observation: FrameObservation, nowMs: Long) {
        controller.reset()
        minigameDetector.reset()
        cachedShakeButton = null
        lastShakeScanMs = 0L
        hud.showMinigame(null, null)
        gestures.setHolding(false, observation.frameWidth / 2, observation.frameHeight / 2)
        hotbarBurstPending = true
        // The closing screen of a won or lost minigame swallows taps for a moment, so hold the
        // burst back until it is gone; the cast check fires as soon as the window ends.
        injectorBusyUntilMs = nowMs + POST_MINIGAME_DELAY_MS
        lastInteractionMs = nowMs
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
        gestures.setHolding(false, 0, 0)
    }
}
