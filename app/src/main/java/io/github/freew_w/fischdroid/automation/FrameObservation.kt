package io.github.freew_w.fischdroid.automation

import io.github.freew_w.fischdroid.detection.Bar
import io.github.freew_w.fischdroid.detection.Fish
import io.github.freew_w.fischdroid.detection.ShakeButton

/**
 * An already-classified frame. [FishingPipeline.processFrame] builds one from the detectors;
 * tests and future frame sources can call [FishingPipeline.step] directly without a `Bitmap`.
 */
data class FrameObservation(
    val inMinigame: Boolean,
    val fish: Fish? = null,
    val bar: Bar? = null,
    val shakeButton: ShakeButton? = null,
)
