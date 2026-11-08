package io.github.freew_w.fischdroid.minigame

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.get
import io.github.freew_w.fischdroid.overlay.OverlayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

object Detector {
    data class Fish(val x: Int)
    data class Bar(val x1: Int, val x2: Int) {
        val center = (x1 + x2) / 2
    }

    private const val TRACK_X = 564
    private const val TRACK_Y = 842
    private const val TRACK_W = 764
    private const val TRACK_H = 31
    private const val TRACK_X1_ITER = TRACK_X - 5 // Inclusive
    private const val TRACK_X2_ITER = TRACK_X + TRACK_W - 1 + 5 // Inclusive
    val TRACK_SCAN_YS = listOf(
        TRACK_Y + 2,
        TRACK_Y + 4,
        TRACK_Y + TRACK_H / 2,
        TRACK_Y + TRACK_H - 4,
        TRACK_Y + TRACK_H - 2
    )

    private const val CONTROL = 0.15
    private const val EXPECTED_BAR_WIDTH = ((TRACK_W - 5) * (0.3 + CONTROL) + 1).toInt()
    private const val WIDTH_TOLERANCE = 4
    private const val SMOOTHING_FRAMES = 10

    private val lab1 = DoubleArray(3)
    private val lab2 = DoubleArray(3)
    private var historyBarCenters = ArrayDeque<Int>()

    private fun colorIntWithinRgbRange(
        color: Int, r: Int, g: Int, b: Int, threshold: Int
    ): Boolean {
        val rInRange = Color.red(color) in r - threshold..r + threshold
        val gInRange = Color.green(color) in g - threshold..g + threshold
        val bInRange = Color.blue(color) in b - threshold..b + threshold
        return rInRange && gInRange && bInRange
    }

    private fun getColorDistance(color1: Int, color2: Int): Double {
        ColorUtils.RGBToLAB(Color.red(color1), Color.green(color1), Color.blue(color1), lab1)
        ColorUtils.RGBToLAB(Color.red(color2), Color.green(color2), Color.blue(color2), lab2)

        val deltaL = lab1[0] - lab2[0]
        val deltaA = lab1[1] - lab2[1]
        val deltaB = lab1[2] - lab2[2]
        return sqrt(deltaL * deltaL + deltaA * deltaA + deltaB * deltaB)
    }

    private fun findPossibleBarsFromEdges(edges: List<Int>): List<Bar> {
        val bars = mutableListOf<Bar>()
        outerLoop@ for (i in 0 until edges.size - 1) {
            for (j in i + 1 until edges.size) {
                val width = edges[j] - edges[i]
                if (width in EXPECTED_BAR_WIDTH - WIDTH_TOLERANCE..EXPECTED_BAR_WIDTH + WIDTH_TOLERANCE) {
                    bars.add(Bar(edges[i], edges[j]))
                }
            }
        }
        return bars
    }

    private fun findFishAndBar(
        bitmap: Bitmap, scanY: Int, lastBarCenter: Int?
    ): Pair<Fish?, Bar?> {
        var fish: Fish? = null
        val edges = mutableListOf<Int>()

        for (x in TRACK_X1_ITER + 1..TRACK_X2_ITER) {
            val colorLeft = bitmap[x - 1, scanY]
            val colorRight = bitmap[x, scanY]
            val diff = getColorDistance(colorLeft, colorRight)
            if (diff > 10f) {
                edges.add(x)
            }

            if (colorIntWithinRgbRange(colorRight, 67, 75, 91, 5)) {
                fish = Fish(x)
            }
        }

        val candidates = findPossibleBarsFromEdges(edges)
        if (candidates.isEmpty()) return fish to null

        var bestBar: Bar? = null
        var bestScore = -Double.MAX_VALUE

        for (bar in candidates) {
            val barCenter = bar.center
            var score = 0.0

            if (lastBarCenter != null) {
                val dist = abs(barCenter - lastBarCenter)
                score -= dist * 3.0
            }

            val centerColor = bitmap[barCenter, scanY]
            val lum = luminance(centerColor)
            score += lum * 2.0

            if (score > bestScore) {
                bestScore = score
                bestBar = bar
            }
        }

        return fish to bestBar
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun takeMedian(list: List<Int>): Int? = if (list.isNotEmpty()) {
        list.sorted()
            .let { if (it.size % 2 == 0) (it[it.size / 2] + it[(it.size - 1) / 2]) / 2 else it[it.size / 2] }
    } else {
        null
    }

    /*
    How it works:
    Finding the fish and bar of one scan row:
        Find all edges and scan for fish with color concurrently
        Find all possible bars from edges
        Score all possible bars based on their distance from last bar and brightness to find the best bar

    Find the fish and bar for five rows
    For fish and bar center:
        Filter out the nulls
        Sort them
        Take the median
        Validate them (valid when its x coordinate is within the track)
    Add bar center to history
    Take median from history
    Return the fish and bar
     */
    suspend fun processFrame(bitmap: Bitmap) {
        withContext(Dispatchers.Default) {
            if (bitmap.height > bitmap.width) {
                return@withContext
            }

            val fishAndBars = mutableListOf<Pair<Fish?, Bar?>>()
            TRACK_SCAN_YS.forEach { scanY ->
                fishAndBars += findFishAndBar(
                    bitmap, scanY, historyBarCenters.lastOrNull()
                )
            }

            val fish = takeMedian(fishAndBars.mapNotNull { it.first }.map { it.x }
                .sorted())?.let { Fish(it) }

            val barCenter = takeMedian(fishAndBars.mapNotNull { it.second }.map { it.center })
            barCenter?.also { historyBarCenters.addLast(it) }
            if (historyBarCenters.size > SMOOTHING_FRAMES) {
                historyBarCenters.removeFirst()
            }
            val bar = takeMedian(historyBarCenters.sorted())?.let {
                Bar(
                    it - EXPECTED_BAR_WIDTH / 2,
                    it + EXPECTED_BAR_WIDTH / 2
                )
            }

            withContext(Dispatchers.Main) {
                OverlayManager.updateMinigame(fish, bar)
            }
        }
    }
}