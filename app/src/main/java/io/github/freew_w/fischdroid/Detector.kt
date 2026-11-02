package io.github.freew_w.fischdroid

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

object Detector {
    private const val TRACK_X = 564 - 5
    private const val TRACK_Y = 842
    private const val TRACK_W = 764 + 5
    private const val TRACK_H = 31

//    private const val TRACK_X = 758 - 5
//    private const val TRACK_Y = 887
//    private const val TRACK_W = 884 + 5
//    private const val TRACK_H = 35

    val TRACK_SCAN_YS = listOf(
        TRACK_Y + 2,
        TRACK_Y + 4,
        TRACK_Y + TRACK_H / 2,
        TRACK_Y + TRACK_H - 4,
        TRACK_Y + TRACK_H - 2
    )

//    private const val EXPECTED_BAR_WIDTH = 382 // heaven rod
//    private const val EXPECTED_BAR_WIDTH = 398 // rotd physical

    private const val EXPECTED_BAR_WIDTH = 344 // rotd
    private const val WIDTH_TOLERANCE = 4
    private const val SMOOTHING_FRAMES = 10

    private fun colorIntWithinRgbRange(
        color: Int, r: Int, g: Int, b: Int, threshold: Int
    ): Boolean {
        val rInRange = Color.red(color) in r - threshold..r + threshold
        val gInRange = Color.green(color) in g - threshold..g + threshold
        val bInRange = Color.blue(color) in b - threshold..b + threshold
        return rInRange && gInRange && bInRange
    }

    private fun getColorDistance(color1: Int, color2: Int): Double {
        val lab1 = DoubleArray(3)
        val lab2 = DoubleArray(3)
        ColorUtils.RGBToLAB(Color.red(color1), Color.green(color1), Color.blue(color1), lab1)
        ColorUtils.RGBToLAB(Color.red(color2), Color.green(color2), Color.blue(color2), lab2)

        val deltaL = lab1[0] - lab2[0]
        val deltaA = lab1[1] - lab2[1]
        val deltaB = lab1[2] - lab2[2]
        return sqrt(deltaL * deltaL + deltaA * deltaA + deltaB * deltaB)
    }

    private fun findPossibleBarsFromEdges(edges: List<Int>): List<Pair<Int, Int>> {
        val bars = mutableListOf<Pair<Int, Int>>()
        outerLoop@ for (i in 0 until edges.size - 1) {
            for (j in i + 1 until edges.size) {
                val width = edges[j] - edges[i]
                if (width in EXPECTED_BAR_WIDTH - WIDTH_TOLERANCE..EXPECTED_BAR_WIDTH + WIDTH_TOLERANCE) {
                    bars.add(edges[i] to edges[j])
                }
            }
        }
        return bars
    }

    private fun findFishAndBar(
        bitmap: Bitmap, scanY: Int, lastBarCenter: Int?
    ): Pair<Int?, Pair<Int, Int>?> {
        var fish: Int? = null
        val edges = mutableListOf<Int>()

        for (x in TRACK_X + 1 until TRACK_X + TRACK_W) {
            val colorLeft = bitmap[x - 1, scanY]
            val colorRight = bitmap[x, scanY]
            val diff = getColorDistance(colorLeft, colorRight)
            if (diff > 10f) {
                edges.add(x)
            }

            if (colorIntWithinRgbRange(colorRight, 67, 75, 91, 5)) {
                fish = x
            }
        }

        val candidates = findPossibleBarsFromEdges(edges)
        if (candidates.isEmpty()) return fish to null

        var bestBar: Pair<Int, Int>? = null
        var bestScore = -Double.MAX_VALUE

        for (bar in candidates) {
            val barCenter = bar.first + (bar.second - bar.first) / 2
            var score = 0.0

            if (lastBarCenter != null) {
                val dist = abs(barCenter - lastBarCenter)
                score -= dist * 3.0
            }

            val centerColor = bitmap[barCenter, scanY]
            val lum =
                (Color.red(centerColor) + Color.green(centerColor) + Color.blue(centerColor)) / 3.0
            score += lum * 2.0

            if (score > bestScore) {
                bestScore = score
                bestBar = bar
            }
        }

        return fish to bestBar
    }

    fun takeMedian(list: List<Int>): Int? = if (list.isNotEmpty()) {
        list.sorted().let {
            if (it.size % 2 == 0) (it[it.size / 2] + it[(it.size - 1) / 2]) / 2
            else it[it.size / 2]
        }
    } else {
        null
    }

    private fun validatePosition(position: Int): Int? {
        return if (position in TRACK_X..TRACK_X + TRACK_W) {
            position
        } else {
            null
        }
    }

    /*
    How it works:
    Finding the fish and bar of one scan row:
        Find all edges and scan for fish with color concurrently
        Find all possible bars from edges
        Score all possible bars based on their distance from last bar and brightness to find the best bar

    Find the fish and bar for five rows
    For fish and bar cneter:
        Filter out the nulls
        Sort them
        Take the median
        Validate them (valid when its x coordinate is within the track)
    Add bar center to history
    Take median from history
    Return the fish and bar
     */
    var historyBarCenters = ArrayDeque<Int>()
    suspend fun processFrame(bitmap: Bitmap) {
        withContext(Dispatchers.Default) {
            if (bitmap.height > bitmap.width) {
                return@withContext
            }

            var fishAndBars = emptyList<Pair<Int?, Pair<Int, Int>?>>()
            TRACK_SCAN_YS.forEach { scanY ->
                val (fish, bar) = findFishAndBar(bitmap, scanY, historyBarCenters.lastOrNull())
                fishAndBars += fish to bar
            }

            val fish = takeMedian(fishAndBars.mapNotNull { it.first }
                .sorted())?.let { validatePosition(it) }

            val barCenter = takeMedian(fishAndBars.mapNotNull { it.second }
                .map { it.first + (it.second - it.first) / 2 })?.let {
                validatePosition(it)
            }
            barCenter?.let { historyBarCenters.addLast(it) }
            if (historyBarCenters.size > SMOOTHING_FRAMES) {
                historyBarCenters.removeFirst()
            }
            val bar =
                takeMedian(historyBarCenters.sorted())?.let { it - EXPECTED_BAR_WIDTH / 2 to it + EXPECTED_BAR_WIDTH / 2 }

            withContext(Dispatchers.Main, {
                OverlayManager.update(fish, bar)
            })
        }
    }
}