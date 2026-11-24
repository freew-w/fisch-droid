package io.github.freew_w.fischdroid.setup

import java.io.Serializable
import kotlin.math.abs
import kotlin.math.min

/** The kinds of value the setup widget can pick from an image. */
enum class SetupFieldType {
    /** Two pixels: top-left, then bottom-right. Stored as x, y, width, height. */
    REGION,

    /** A single screen point (a hotbar slot). Stored as x, y. */
    POINT,

    /** The RGB color of a single pixel. */
    COLOR,
}

data class PixelPoint(val x: Int, val y: Int) : Serializable {
    fun toIntArray() = intArrayOf(x, y)
}

data class RegionSelection(val x: Int, val y: Int, val width: Int, val height: Int) : Serializable {
    fun toIntArray() = intArrayOf(x, y, width, height)

    companion object {
        /**
         * Builds an inclusive pixel region from two corners. Two adjacent corners still describe
         * a 1x1 region, so width and height are the distance plus one.
         */
        fun between(first: PixelPoint, second: PixelPoint): RegionSelection {
            val left = min(first.x, second.x)
            val top = min(first.y, second.y)
            return RegionSelection(
                x = left,
                y = top,
                width = abs(first.x - second.x) + 1,
                height = abs(first.y - second.y) + 1,
            )
        }
    }
}

/**
 * One value the setup widget can collect. Serializable because it is passed between the wizard,
 * the main screen and [SetupActivity] through an Intent.
 */
data class SetupField(
    val id: String,
    val label: String,
    val type: SetupFieldType,
    val initialRegion: RegionSelection? = null,
    val initialPoint: PixelPoint? = null,
    val initialColor: Int? = null,
) : Serializable {
    companion object {
        fun region(id: String, label: String, initial: RegionSelection? = null) =
            SetupField(
                id = id,
                label = label,
                type = SetupFieldType.REGION,
                initialRegion = initial,
            )

        fun point(id: String, label: String, initial: PixelPoint? = null) =
            SetupField(
                id = id,
                label = label,
                type = SetupFieldType.POINT,
                initialPoint = initial,
            )

        fun color(id: String, label: String, initial: Int? = null) =
            SetupField(
                id = id,
                label = label,
                type = SetupFieldType.COLOR,
                initialColor = initial,
            )
    }
}

/** Formats an ARGB color as #RRGGBB for display. */
fun Int.toHexColor(): String = String.format(java.util.Locale.US, "#%06X", this and 0xFFFFFF)
