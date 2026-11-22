package io.github.freew_w.fischdroid.detection

import android.graphics.Bitmap

/**
 * Ports for the three independent visual checks. The automation pipeline depends on these
 * interfaces, not on the concrete implementations, so a new detector can be swapped in (or a
 * fake used in tests) without touching the state machine.
 */

/** Finds the fish marker and control bar inside the calibrated track. Stateful; call [reset]. */
interface FishBarAnalyzer {
    fun detect(bitmap: Bitmap, spec: DetectionSpec): Pair<Fish?, Bar?>

    /** Forgets the previous frame's positions. Call when automation stops or leaves the minigame. */
    fun reset()
}

/** Reports whether the cream "C$" HUD indicator is visible (i.e. the game is *not* in the minigame). */
interface CoinsAnalyzer {
    fun isVisible(
        bitmap: Bitmap,
        width: Int = bitmap.width,
        height: Int = bitmap.height,
    ): Boolean
}

/** Finds the "SHAKE" button. Returns null for portrait frames and frames without the button. */
interface ShakeAnalyzer {
    fun find(
        bitmap: Bitmap,
        width: Int = bitmap.width,
        height: Int = bitmap.height,
    ): ShakeButton?
}
