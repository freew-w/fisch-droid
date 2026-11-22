package io.github.freew_w.fischdroid.config

import io.github.freew_w.fischdroid.control.ControlTuning
import io.github.freew_w.fischdroid.detection.TrackGeometry

/**
 * Immutable snapshot of the values the automation loop needs for one frame.
 *
 * The pipeline takes one snapshot per frame, so edits made in the setup screens apply live without
 * giving the pipeline a `SharedPreferences` dependency. Adding a new tunable means adding a field
 * here and to [Config.snapshot], not widening a detector type.
 */
data class Settings(
    val track: TrackGeometry,
    val control: ControlTuning,
    val cast: CastTarget,
    val fishColor: Int,
)
