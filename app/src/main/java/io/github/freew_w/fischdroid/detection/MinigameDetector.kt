package io.github.freew_w.fischdroid.detection

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.get
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the fish marker and the control bar inside the calibrated [TrackGeometry].
 *
 * The detector is stateful: the previous fish/bar positions narrow the next scan range and break
 * ties between candidate bars. Keep one instance per capture session and call [reset] whenever
 * automation stops or the game leaves the minigame.
 */
class MinigameDetector : FishBarAnalyzer {
    private companion object {
        const val WIDTH_TOLERANCE = 4
        const val NEXT_FISH_SCAN_RANGE_OFFSET = 20
        const val FISH_COLOR_TOLERANCE = 5
    }

    // Runtime variables
    private val lab1 = DoubleArray(3)
    private val lab2 = DoubleArray(3)
    private var lastFish: Fish? = null
    private var lastBar: Bar? = null

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

    private fun findPossibleBarsFromEdges(edges: List<Int>, spec: DetectionSpec): List<Bar> {
        val bars = mutableListOf<Bar>()
        for (i in 0 until edges.size - 1) {
            for (j in i + 1 until edges.size) {
                val width = edges[j] - edges[i]
                if (width in spec.barWidth - WIDTH_TOLERANCE..spec.barWidth + WIDTH_TOLERANCE) {
                    bars.add(Bar(edges[i], edges[j]))
                }
            }
        }
        return bars
    }

    private fun findFishAndBar(
        bitmap: Bitmap,
        spec: DetectionSpec,
        lastBar: Bar?,
        fishScanX1: Int,
        fishScanX2: Int,
        barScanX1: Int,
        barScanX2: Int,
        scanY: Int,
    ): Pair<Fish?, Bar?> {
        var fish: Fish? = null
        val edges = mutableListOf<Int>()
        val fishR = Color.red(spec.fishColor)
        val fishG = Color.green(spec.fishColor)
        val fishB = Color.blue(spec.fishColor)

        for (x in fishScanX1..fishScanX2) {
            val color = bitmap[x, scanY]
            if (colorIntWithinRgbRange(color, fishR, fishG, fishB, FISH_COLOR_TOLERANCE)) {
                fish = Fish(x)
            }
        }

        for (x in barScanX1 + 1..barScanX2) {
            val colorLeft = bitmap[x - 1, scanY]
            val colorRight = bitmap[x, scanY]
            val diff = getColorDistance(colorLeft, colorRight)
            if (diff > 10.0) {
                edges.add(x)
            }
        }

        val candidates = findPossibleBarsFromEdges(edges, spec)
        if (candidates.isEmpty()) return fish to null

        var bestBar: Bar? = null
        var bestScore = -Double.MAX_VALUE

        for (bar in candidates) {
            val barCenter = bar.center
            var score = 0.0

            if (lastBar != null) {
                val dist = abs(barCenter - lastBar.center)
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
    override fun detect(bitmap: Bitmap, spec: DetectionSpec): Pair<Fish?, Bar?> {
        val fishScanX1 =
            lastFish?.let { max(it.x - NEXT_FISH_SCAN_RANGE_OFFSET, spec.x1Iter) } ?: spec.x1Iter
        val fishScanX2 =
            lastFish?.let { min(it.x + NEXT_FISH_SCAN_RANGE_OFFSET, spec.x2Iter) } ?: spec.x2Iter
        val barScanX1 = lastBar?.let { max(it.center - spec.nextBarScanRangeOffset, spec.x1Iter) }
            ?: spec.x1Iter
        val barScanX2 = lastBar?.let { min(it.center + spec.nextBarScanRangeOffset, spec.x2Iter) }
            ?: spec.x2Iter

        val fishAndBars = mutableListOf<Pair<Fish?, Bar?>>()
        spec.scanYs.forEach { scanY ->
            fishAndBars += findFishAndBar(
                bitmap, spec, lastBar, fishScanX1, fishScanX2, barScanX1, barScanX2, scanY
            )
        }

        val fish = takeMedian(fishAndBars.mapNotNull { it.first }.map { it.x })?.let { Fish(it) }
        lastFish = fish

        val bar = takeMedian(fishAndBars.mapNotNull { it.second }
            .map { it.center })?.let { Bar(it - spec.barWidth / 2, it + spec.barWidth / 2) }
        lastBar = bar

        return fish to bar
    }

    override fun reset() {
        lastFish = null
        lastBar = null
    }
}
