package io.github.freew_w.fischdroid.detection

/**
 * Detection results shared by the analyzers, the pipeline and the debug overlay.
 *
 * Keeping these types in one place means the overlay does not need to import a detector, and a
 * new analyzer can return the same shapes without creating a package cycle.
 */

/** The fish marker's x position in frame pixels. */
data class Fish(val x: Int)

/** The control bar's horizontal extent in frame pixels. */
data class Bar(val x1: Int, val x2: Int) {
    val center = (x1 + x2) / 2
}

/** The detected "SHAKE" button: centre, ring radius and the detector's confidence score. */
data class ShakeButton(
    val centerX: Int,
    val centerY: Int,
    val radius: Int,
    val score: Double,
)
