package io.github.freew_w.fischdroid.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.content.edit
import io.github.freew_w.fischdroid.control.ControlTuning
import io.github.freew_w.fischdroid.detection.TrackGeometry

object Config {
    private const val PREFS_NAME = "config"
    private const val KEY_TRACK_X = "track_x"
    private const val KEY_TRACK_Y = "track_y"
    private const val KEY_TRACK_W = "track_w"
    private const val KEY_TRACK_H = "track_h"
    private const val KEY_ROD_CONTROL = "rod_control"
    private const val KEY_POSITION_GAIN = "position_gain"
    private const val KEY_VELOCITY_GAIN = "velocity_gain"
    private const val KEY_SWITCH_BAND = "switch_band"
    private const val KEY_CAST_HOLD_X = "cast_hold_x"
    private const val KEY_CAST_HOLD_Y = "cast_hold_y"
    private const val KEY_FISH_COLOR = "fish_color"
    private const val KEY_SETUP_COMPLETE = "setup_complete"
    private const val KEY_LAST_IMAGE_URI = "last_image_uri"

    private var preferences: SharedPreferences? = null

    fun init(context: Context) {
        if (preferences != null) return
        preferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences
        get() = preferences
            ?: error("Config.init(context) must be called before reading or writing configuration")

    var trackX: Int
        get() = prefs.getInt(KEY_TRACK_X, 0)
        set(value) = prefs.edit { putInt(KEY_TRACK_X, value) }

    var trackY: Int
        get() = prefs.getInt(KEY_TRACK_Y, 0)
        set(value) = prefs.edit { putInt(KEY_TRACK_Y, value) }

    var trackW: Int
        get() = prefs.getInt(KEY_TRACK_W, 0)
        set(value) = prefs.edit { putInt(KEY_TRACK_W, value) }

    var trackH: Int
        get() = prefs.getInt(KEY_TRACK_H, 0)
        set(value) = prefs.edit { putInt(KEY_TRACK_H, value) }

    var rodControl: Float
        get() = prefs.getFloat(KEY_ROD_CONTROL, 0f)
        set(value) = prefs.edit { putFloat(KEY_ROD_CONTROL, value) }

    var positionGain: Float
        get() = prefs.getFloat(KEY_POSITION_GAIN, 4f)
        set(value) = prefs.edit { putFloat(KEY_POSITION_GAIN, value) }

    var velocityGain: Float
        get() = prefs.getFloat(KEY_VELOCITY_GAIN, 3.2f)
        set(value) = prefs.edit { putFloat(KEY_VELOCITY_GAIN, value) }

    var switchBand: Float
        get() = prefs.getFloat(KEY_SWITCH_BAND, 200f)
        set(value) = prefs.edit { putFloat(KEY_SWITCH_BAND, value) }

    var castHoldX: Float
        get() = prefs.getFloat(KEY_CAST_HOLD_X, 1100f)
        set(value) = prefs.edit { putFloat(KEY_CAST_HOLD_X, value) }

    var castHoldY: Float
        get() = prefs.getFloat(KEY_CAST_HOLD_Y, 600f)
        set(value) = prefs.edit { putFloat(KEY_CAST_HOLD_Y, value) }

    var fishColor: Int
        get() = prefs.getInt(KEY_FISH_COLOR, Color.rgb(67, 75, 91))
        set(value) = prefs.edit { putInt(KEY_FISH_COLOR, value) }

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_COMPLETE, value) }

    var lastImageUri: String?
        get() = prefs.getString(KEY_LAST_IMAGE_URI, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_IMAGE_URI) else putString(KEY_LAST_IMAGE_URI, value)
        }

    /**
     * Reads the whole automation configuration at once. The pipeline calls this once per frame so
     * edits from the setup screens are picked up live, and so tests can build a [Settings] without
     * touching `SharedPreferences`.
     */
    fun snapshot(): Settings = Settings(
        track = TrackGeometry(trackX, trackY, trackW, trackH, rodControl),
        control = ControlTuning(positionGain, velocityGain, switchBand),
        cast = CastTarget(castHoldX, castHoldY),
        fishColor = fishColor,
    )
}
