package io.github.freew_w.fischdroid.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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
        get() = prefs.getInt(KEY_TRACK_X, 564)
        set(value) = prefs.edit { putInt(KEY_TRACK_X, value) }

    var trackY: Int
        get() = prefs.getInt(KEY_TRACK_Y, 842)
        set(value) = prefs.edit { putInt(KEY_TRACK_Y, value) }

    var trackW: Int
        get() = prefs.getInt(KEY_TRACK_W, 764)
        set(value) = prefs.edit { putInt(KEY_TRACK_W, value) }

    var trackH: Int
        get() = prefs.getInt(KEY_TRACK_H, 31)
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
        get() = prefs.getFloat(KEY_SWITCH_BAND, 50f)
        set(value) = prefs.edit { putFloat(KEY_SWITCH_BAND, value) }
}
