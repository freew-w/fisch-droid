package io.github.freew_w.fischdroid.detection

/**
 * Everything [MinigameDetector] needs for one frame.
 *
 * This is intentionally only detection input: track geometry and the fish colour. Control gains
 * and hotbar slots live in their own settings types, so tuning one concern cannot silently
 * change another.
 */
data class DetectionSpec(
    val track: TrackGeometry,
    val fishColor: Int,
) {
    /** Inclusive x range of the track, widened by a small margin for motion between frames. */
    val x1Iter = track.x - 5
    val x2Iter = track.x + track.width - 1 + 5

    /** Rows sampled across the track; the median of their results rejects HUD noise. */
    val scanYs = listOf(
        track.y + 2,
        track.y + 4,
        track.y + track.height / 2,
        track.y + track.height - 4,
        track.y + track.height - 2,
    )

    /** Expected bar width in pixels, including the +1 that rounds up. */
    val barWidth = (track.width * (0.3f + track.rodControl) + 1).toInt()
    val nextBarScanRangeOffset = (barWidth * 0.7f).toInt()
}
