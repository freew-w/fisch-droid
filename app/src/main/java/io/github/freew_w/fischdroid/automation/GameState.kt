package io.github.freew_w.fischdroid.automation

/**
 * What the pipeline currently believes the game is doing. Transitions are driven by the cream
 * "C$" indicator, the shake button and the minigame fish/bar detections.
 */
enum class GameState {
    /** Automation is stopped or the frame is portrait; the hold is released. */
    IDLE,

    /** No "C$" and no shake button: cast periodically and wait for the minigame. */
    SEARCHING,

    /** No "C$" but the shake button is visible: tap it instead of casting. */
    SHAKING,

    /** "C$" is hidden: the fishing bar is on screen, so drive the PD controller. */
    MINIGAME,
}
