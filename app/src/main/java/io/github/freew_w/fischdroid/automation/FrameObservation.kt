package io.github.freew_w.fischdroid.automation

import io.github.freew_w.fischdroid.detection.Bar
import io.github.freew_w.fischdroid.detection.Fish
import io.github.freew_w.fischdroid.detection.ShakeButton

/**
 * An already-classified frame. [FishingPipeline.processFrame] builds one from the detectors;
 * tests and future frame sources can call [FishingPipeline.step] directly without a `Bitmap`.
 *
 * [frameWidth] and [frameHeight] are the real capture dimensions, so the pipeline can derive the
 * screen-centre cast/hold point without reaching for Android display APIs.
 */
data class FrameObservation(
    val inMinigame: Boolean,
    val frameWidth: Int,
    val frameHeight: Int,
    val fish: Fish? = null,
    val bar: Bar? = null,
    val shakeButton: ShakeButton? = null,
)
