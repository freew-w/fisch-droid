package io.github.freew_w.fischdroid.minigame

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.get
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Detector {
    data class Fish(val x: Int)
    data class Bar(val x1: Int, val x2: Int) {
        val center = (x1 + x2) / 2
    }

    data class ScanSpec(
        val trackX: Int,
        val trackY: Int,
        val trackW: Int,
        val trackH: Int,
        val control: Float,
        val positionGain: Float,
        val velocityGain: Float,
        val switchBand: Float,
        val castHoldX: Float,
        val castHoldY: Float,
        val fishColor: Int,
    ) {
        val x1Iter = trackX - 5 // Inclusive
        val x2Iter = trackX + trackW - 1 + 5 // Inclusive
        val scanYs = listOf(
            trackY + 2, trackY + 4, trackY + trackH / 2, trackY + trackH - 4, trackY + trackH - 2
        )
        val barWidth = (trackW * (0.3f + control) + 1).toInt() // Plus one to round up
        val nextBarScanRangeOffset = (barWidth * 0.7f).toInt()
    }

    // Constants
    private const val WIDTH_TOLERANCE = 4
    private const val NEXT_FISH_SCAN_RANGE_OFFSET = 20
    private const val FISH_COLOR_TOLERANCE = 5

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

    private fun findPossibleBarsFromEdges(edges: List<Int>, spec: ScanSpec): List<Bar> {
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
        spec: ScanSpec,
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
    fun detect(bitmap: Bitmap, spec: ScanSpec): Pair<Fish?, Bar?> {
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

    fun notInMinigame(bitmap: Bitmap): Boolean = CoinsDetector.isVisible(bitmap)

    fun reset() {
        lastFish = null
        lastBar = null
    }
}